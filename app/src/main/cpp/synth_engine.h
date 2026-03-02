#pragma once

#include "audio_engine.h"
#include "spsc_ring_buffer.h"
#include <atomic>
#include <thread>
#include <string>

namespace nightjar {

// Synth ring buffer: 16384 float samples = 8192 stereo frames = ~186ms at 44.1kHz.
// Large enough to absorb render-thread scheduling jitter, small enough for
// acceptable latency when flushing on seek/loop.
static constexpr size_t kSynthRingBufferCapacity = 16384;

// Render chunk: 256 frames = ~5.8ms at 44.1kHz. Small chunks keep the
// render thread responsive to flush requests and volume changes.
static constexpr int32_t kSynthRenderChunkFrames = 256;

/**
 * FluidSynth wrapper with a dedicated render thread.
 *
 * fluid_synth_write_float() is NOT real-time safe (may allocate internally),
 * so we render on a background thread and feed audio to the Oboe callback
 * via a lock-free SPSC ring buffer. Same proven pattern as the recording
 * pipeline (OboeRecordingStream), but in the opposite direction:
 *   render thread -> SPSC ring buffer -> audio callback
 *
 * MIDI events (noteOn/noteOff) go directly to FluidSynth, which is
 * internally thread-safe (uses its own mutex). These calls are fine from
 * any thread since they never touch the audio callback.
 */
class SynthEngine {
public:
    SynthEngine();
    ~SynthEngine();

    // Non-copyable, non-movable
    SynthEngine(const SynthEngine&) = delete;
    SynthEngine& operator=(const SynthEngine&) = delete;

    /**
     * Load a SoundFont (.sf2) file by absolute path.
     * Must be called before start(). Returns true on success.
     */
    bool loadSoundFont(const std::string& path);

    /** Start the render thread. Requires a loaded SoundFont. */
    void start();

    /** Stop the render thread and flush the ring buffer. */
    void stop();

    /**
     * Read rendered stereo audio from the ring buffer and ADD it
     * into the output buffer. Called from the audio callback -- lock-free.
     *
     * @param output Stereo interleaved float buffer to sum into.
     * @param numFrames Number of stereo frames to read.
     * @return Number of frames actually read (may be less on underrun).
     */
    int32_t readFrames(float* output, int32_t numFrames);

    /** Send a MIDI note-on. Thread-safe (FluidSynth uses internal mutex). */
    void noteOn(int channel, int note, int velocity);

    /** Send a MIDI note-off. Thread-safe. */
    void noteOff(int channel, int note);

    /** Set master synth volume (0.0 - 1.0). Lock-free. */
    void setVolume(float volume);

    /** Request a ring buffer flush + all-notes-off. Used on seek/loop. */
    void requestFlush();

    bool isRunning() const { return running_.load(std::memory_order_acquire); }
    bool isSoundFontLoaded() const { return soundFontLoaded_.load(std::memory_order_acquire); }

private:
    void renderThreadFunc();

    void* settings_ = nullptr;   // fluid_settings_t* (avoid header dependency)
    void* synth_ = nullptr;      // fluid_synth_t*
    int sfontId_ = -1;

    SpscRingBuffer<kSynthRingBufferCapacity> ringBuffer_;

    std::thread renderThread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> soundFontLoaded_{false};
    std::atomic<float> volume_{1.0f};
    std::atomic<bool> flushRequested_{false};
};

}  // namespace nightjar
