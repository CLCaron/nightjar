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
    midiSequencer_.reset();
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
    midiSequencer_.reset();
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

void SynthEngine::allSoundsOff() {
    if (synth_) {
        fluid_synth_all_sounds_off(FS_SYNTH, -1);  // -1 = all channels
    }
}

// ── Step sequencer control ──────────────────────────────────────────────

void SynthEngine::updateDrumPattern(int stepsPerBar, int bars, int64_t offsetFrames,
                                     float volume, bool muted,
                                     const std::vector<DrumHit>& hits,
                                     const std::vector<int64_t>& clipOffsetFrames,
                                     int beatsPerBar) {
    sequencer_.updatePattern(stepsPerBar, bars, offsetFrames, volume, muted,
                             hits, clipOffsetFrames, beatsPerBar);
}

void SynthEngine::updateDrumPatternClips(float volume, bool muted,
                                         const std::vector<StepSequencer::ClipSlot>& clips) {
    sequencer_.updatePattern(volume, muted, clips);
}

void SynthEngine::setSequencerEnabled(bool enabled) {
    sequencerEnabled_.store(enabled, std::memory_order_release);
    if (!enabled) {
        allSoundsOff();
    }
}

int64_t SynthEngine::getSequencerMaxEndFrame(double bpm) const {
    return sequencer_.getMaxEndFrame(bpm);
}

// ── MIDI sequencer control ─────────────────────────────────────────────

void SynthEngine::updateMidiTracks(const std::vector<MidiTrackData>& tracks) {
    if (!synth_) return;

    // Apply program changes for each track
    for (const auto& track : tracks) {
        fluid_synth_program_change(FS_SYNTH, track.channel, track.program);
    }

    midiSequencer_.updateTracks(tracks);
}

void SynthEngine::setMidiSequencerEnabled(bool enabled) {
    midiSequencerEnabled_.store(enabled, std::memory_order_release);
    if (!enabled) {
        allSoundsOff();
    }
}

int64_t SynthEngine::getMidiMaxEndFrame() const {
    return midiSequencer_.getMaxEndFrame();
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
                // CC 120 (All Sound Off) kills notes instantly with no release
                // tail, giving a clean loop transition
                fluid_synth_all_sounds_off(FS_SYNTH, -1);  // -1 = all channels
            }
            sequencer_.reset();
            renderPos_ = transport_.posFrames.load(std::memory_order_relaxed);
            midiSequencer_.resetToPosition(renderPos_);
            flushRequested_.store(false, std::memory_order_release);
        }

        // Track play/pause transitions
        bool playing = transport_.playing.load(std::memory_order_relaxed);

        if (playing && !wasPlaying_) {
            // Play started -- discard stale audio and sync to the position
            // captured by play() before it set playing=true.  Using
            // pendingStartPos avoids a race where the audio callback
            // advances posFrames before this thread wakes up, which would
            // cause notes at the start position (e.g. frame 0) to be skipped.
            ringBuffer_.reset();
            renderPos_ = transport_.pendingStartPos.load(std::memory_order_acquire);
            sequencer_.reset();
            midiSequencer_.resetToPosition(renderPos_);
        }
        if (!playing && wasPlaying_) {
            // Play stopped -- kill notes instantly (CC 120, no release tail)
            if (synth_) {
                fluid_synth_all_sounds_off(FS_SYNTH, -1);
            }
            sequencer_.reset();
            midiSequencer_.reset();
        }
        wasPlaying_ = playing;

        // Don't render while paused -- prevents stale audio accumulating
        if (!playing) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        // If the ring buffer is nearly full, sleep to avoid wasted CPU
        size_t buffered = ringBuffer_.availableToRead();
        if (buffered + kChunkSamples > kSynthRingBufferCapacity) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            continue;
        }

        // Tick step sequencer (playing is guaranteed true here)
        if (sequencerEnabled_.load(std::memory_order_relaxed)) {
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

        // Tick MIDI sequencer (playing is guaranteed true here)
        if (midiSequencerEnabled_.load(std::memory_order_relaxed)) {
            const auto& midiEvents = midiSequencer_.tick(
                renderPos_, kSynthRenderChunkFrames);
            for (const auto& e : midiEvents) {
                if (synth_) {
                    if (e.note < 0) {
                        // Sentinel: silence all notes on this channel (mute transition)
                        fluid_synth_all_notes_off(FS_SYNTH, e.channel);
                    } else if (e.velocity > 0) {
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

        // Advance render position (playing is guaranteed true here)
        renderPos_ += kSynthRenderChunkFrames;

        // Loop detection: wrap render position at loop boundary so the ring
        // buffer contains seamless audio across iterations (no flush needed)
        int64_t loopEnd = transport_.loopEndFrames.load(std::memory_order_relaxed);
        int64_t loopStart = transport_.loopStartFrames.load(std::memory_order_relaxed);
        if (loopStart >= 0 && loopEnd > loopStart && renderPos_ >= loopEnd) {
            int64_t overshoot = renderPos_ - loopEnd;
            if (synth_) {
                fluid_synth_all_sounds_off(FS_SYNTH, -1);
            }
            sequencer_.reset();
            midiSequencer_.resetToPosition(loopStart);

            // Tick sequencers for the overshoot frames so events at
            // loopStart are not skipped on loop wrap
            if (overshoot > 0) {
                auto overshootFrames = static_cast<int32_t>(overshoot);

                if (sequencerEnabled_.load(std::memory_order_relaxed)) {
                    double bpm = transport_.bpm.load(std::memory_order_relaxed);
                    const auto& events = sequencer_.tick(
                        loopStart, overshootFrames, bpm);
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

                if (midiSequencerEnabled_.load(std::memory_order_relaxed)) {
                    const auto& midiEvents = midiSequencer_.tick(
                        loopStart, overshootFrames);
                    for (const auto& e : midiEvents) {
                        if (synth_) {
                            if (e.velocity > 0) {
                                fluid_synth_noteon(FS_SYNTH, e.channel, e.note, e.velocity);
                            } else {
                                fluid_synth_noteoff(FS_SYNTH, e.channel, e.note);
                            }
                        }
                    }
                }
            }

            renderPos_ = loopStart + overshoot;
        }
    }
}

#undef FS_SETTINGS
#undef FS_SYNTH

}  // namespace nightjar
