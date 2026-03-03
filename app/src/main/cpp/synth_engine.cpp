#include "synth_engine.h"
#include "atomic_transport.h"
#include <fluidsynth.h>
#include <algorithm>
#include <chrono>
#include <cstring>

namespace nightjar {

// Typed accessors for the void* members (avoids FluidSynth header in synth_engine.h)
#define FS_SETTINGS  static_cast<fluid_settings_t*>(settings_)
#define FS_SYNTH     static_cast<fluid_synth_t*>(synth_)

SynthEngine::SynthEngine(AtomicTransport& transport)
    : transport_(transport) {}

SynthEngine::~SynthEngine() {
    stop();

    if (synth_) {
        delete_fluid_synth(FS_SYNTH);
        synth_ = nullptr;
    }
    if (settings_) {
        delete_fluid_settings(FS_SETTINGS);
        settings_ = nullptr;
    }
}

bool SynthEngine::loadSoundFont(const std::string& path) {
    if (soundFontLoaded_.load(std::memory_order_acquire)) {
        LOGW("SynthEngine: SoundFont already loaded");
        return true;
    }

    // Create FluidSynth settings
    settings_ = new_fluid_settings();
    if (!settings_) {
        LOGE("SynthEngine: failed to create FluidSynth settings");
        return false;
    }

    // Match our audio pipeline: 44.1kHz, 1 stereo pair, moderate polyphony
    fluid_settings_setnum(FS_SETTINGS, "synth.sample-rate",
                          static_cast<double>(kSampleRate));
    fluid_settings_setint(FS_SETTINGS, "synth.audio-channels", 1);   // 1 stereo pair
    fluid_settings_setint(FS_SETTINGS, "synth.polyphony", 64);
    fluid_settings_setint(FS_SETTINGS, "synth.reverb.active", 1);
    fluid_settings_setint(FS_SETTINGS, "synth.chorus.active", 0);    // save CPU on mobile

    // Create the synthesizer
    synth_ = new_fluid_synth(FS_SETTINGS);
    if (!synth_) {
        LOGE("SynthEngine: failed to create FluidSynth instance");
        delete_fluid_settings(FS_SETTINGS);
        settings_ = nullptr;
        return false;
    }

    // Load the SoundFont
    sfontId_ = fluid_synth_sfload(FS_SYNTH, path.c_str(), 1);  // 1 = reset presets
    if (sfontId_ == FLUID_FAILED) {
        LOGE("SynthEngine: failed to load SoundFont: %s", path.c_str());
        delete_fluid_synth(FS_SYNTH);
        synth_ = nullptr;
        delete_fluid_settings(FS_SETTINGS);
        settings_ = nullptr;
        return false;
    }

    soundFontLoaded_.store(true, std::memory_order_release);
    LOGD("SynthEngine: loaded SoundFont (id=%d) from %s", sfontId_, path.c_str());
    return true;
}

void SynthEngine::start() {
    if (running_.load(std::memory_order_acquire)) return;
    if (!soundFontLoaded_.load(std::memory_order_acquire)) {
        LOGW("SynthEngine: cannot start render thread -- no SoundFont loaded");
        return;
    }

    ringBuffer_.reset();
    renderPos_ = transport_.posFrames.load(std::memory_order_relaxed);
    wasPlaying_ = false;
    sequencer_.reset();
    running_.store(true, std::memory_order_release);
    renderThread_ = std::thread(&SynthEngine::renderThreadFunc, this);
    LOGD("SynthEngine: render thread started");
}

void SynthEngine::stop() {
    if (!running_.load(std::memory_order_acquire)) return;

    running_.store(false, std::memory_order_release);
    if (renderThread_.joinable()) {
        renderThread_.join();
    }
    ringBuffer_.reset();
    sequencer_.reset();
    LOGD("SynthEngine: render thread stopped");
}

int32_t SynthEngine::readFrames(float* output, int32_t numFrames) {
    // Stack buffer: max 2048 frames * 2 channels = 4096 floats = 16KB on stack
    static constexpr int32_t kMaxStereoSamples = 2048 * kOutputChannelCount;
    float temp[kMaxStereoSamples];

    int32_t totalSamples = std::min(numFrames * kOutputChannelCount, kMaxStereoSamples);
    size_t got = ringBuffer_.read(temp, static_cast<size_t>(totalSamples));

    float vol = volume_.load(std::memory_order_relaxed);
    for (size_t i = 0; i < got; ++i) {
        output[i] += temp[i] * vol;
    }

    return static_cast<int32_t>(got) / kOutputChannelCount;
}

void SynthEngine::noteOn(int channel, int note, int velocity) {
    if (synth_) {
        fluid_synth_noteon(FS_SYNTH, channel, note, velocity);
    }
}

void SynthEngine::noteOff(int channel, int note) {
    if (synth_) {
        fluid_synth_noteoff(FS_SYNTH, channel, note);
    }
}

void SynthEngine::setVolume(float volume) {
    volume_.store(volume, std::memory_order_relaxed);
}

void SynthEngine::requestFlush() {
    flushRequested_.store(true, std::memory_order_release);
}

// ── Step sequencer control ──────────────────────────────────────────────

void SynthEngine::updateDrumPattern(int stepsPerBar, int bars, int64_t offsetFrames,
                                     float volume, bool muted,
                                     const std::vector<DrumHit>& hits,
                                     const std::vector<int64_t>& clipOffsetFrames) {
    sequencer_.updatePattern(stepsPerBar, bars, offsetFrames, volume, muted,
                             hits, clipOffsetFrames);
}

void SynthEngine::setSequencerEnabled(bool enabled) {
    sequencerEnabled_.store(enabled, std::memory_order_release);
}

int64_t SynthEngine::getSequencerMaxEndFrame(double bpm) const {
    return sequencer_.getMaxEndFrame(bpm);
}

// ── Render thread ──────────────────────────────────────────────────────────

void SynthEngine::renderThreadFunc() {
    constexpr size_t kChunkSamples = kSynthRenderChunkFrames * kOutputChannelCount;
    float renderBuf[kChunkSamples];

    while (running_.load(std::memory_order_acquire)) {
        // Handle flush (triggered by seek/loop)
        if (flushRequested_.load(std::memory_order_acquire)) {
            ringBuffer_.reset();
            if (synth_) {
                fluid_synth_all_notes_off(FS_SYNTH, -1);  // -1 = all channels
            }
            sequencer_.reset();
            renderPos_ = transport_.posFrames.load(std::memory_order_relaxed);
            flushRequested_.store(false, std::memory_order_release);
        }

        // Track play/pause transitions
        bool playing = transport_.playing.load(std::memory_order_relaxed);

        if (playing && !wasPlaying_) {
            // Play started -- sync render position with transport
            renderPos_ = transport_.posFrames.load(std::memory_order_relaxed);
            sequencer_.reset();
        }
        if (!playing && wasPlaying_) {
            // Play stopped -- silence any ringing notes
            if (synth_) {
                fluid_synth_all_notes_off(FS_SYNTH, -1);
            }
            sequencer_.reset();
        }
        wasPlaying_ = playing;

        // If the ring buffer is nearly full, sleep to avoid wasted CPU
        size_t buffered = ringBuffer_.availableToRead();
        if (buffered + kChunkSamples > kSynthRingBufferCapacity) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            continue;
        }

        // Tick step sequencer while playing
        if (playing && sequencerEnabled_.load(std::memory_order_relaxed)) {
            double bpm = transport_.bpm.load(std::memory_order_relaxed);
            const auto& events = sequencer_.tick(
                renderPos_, kSynthRenderChunkFrames, bpm);
            for (const auto& e : events) {
                if (synth_) {
                    if (e.velocity > 0) {
                        fluid_synth_noteon(FS_SYNTH, e.channel, e.note, e.velocity);
                    } else {
                        fluid_synth_noteoff(FS_SYNTH, e.channel, e.note);
                    }
                }
            }
        }

        // Render interleaved stereo: L at even indices, R at odd
        int result = fluid_synth_write_float(
            FS_SYNTH, kSynthRenderChunkFrames,
            renderBuf, 0, 2,   // left:  buf[0], buf[2], buf[4], ...
            renderBuf, 1, 2    // right: buf[1], buf[3], buf[5], ...
        );

        if (result != FLUID_OK) {
            LOGW("SynthEngine: fluid_synth_write_float failed");
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        ringBuffer_.write(renderBuf, kChunkSamples);

        // Advance render position only while playing
        if (playing) {
            renderPos_ += kSynthRenderChunkFrames;
        }
    }
}

#undef FS_SETTINGS
#undef FS_SYNTH

}  // namespace nightjar
