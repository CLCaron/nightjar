#pragma once

#include "common.h"
#include "spsc_ring_buffer.h"
#include "step_sequencer.h"
#include <atomic>
#include <thread>
#include <string>

namespace nightjar {

struct AtomicTransport;

// Synth ring buffer: 16384 float samples = 8192 stereo frames = ~186ms at 44.1kHz.
// Large enough to absorb render-thread scheduling jitter, small enough for
// acceptable latency when flushing on seek/loop.
static constexpr size_t kSynthRingBufferCapacity = 16384;

// Render chunk: 256 frames = ~5.8ms at 44.1kHz. Small chunks keep the
// render thread responsive to flush requests and volume changes.
static constexpr int32_t kSynthRenderChunkFrames = 256;

/**
 * FluidSynth wrapper with a dedicated render thread and integrated step sequencer.
 *
 * fluid_synth_write_float() is NOT real-time safe (may allocate internally),
 * so we render on a background thread and feed audio to the Oboe callback
 * via a lock-free SPSC ring buffer. Same proven pattern as the recording
 * pipeline (OboeRecordingStream), but in the opposite direction:
 *   render thread -> SPSC ring buffer -> audio callback
 *
 * The step sequencer runs inside the render thread loop. Between render chunks,
 * it checks for step boundaries and fires MIDI noteOn events into FluidSynth.
 * This ensures note events are rendered at the correct position in the audio
 * stream, not delayed by the ring buffer.
 *
 * MIDI events (noteOn/noteOff) go directly to FluidSynth, which is
 * internally thread-safe (uses its own mutex). These calls are fine from
 * any thread since they never touch the audio callback.
 */
class SynthEngine {
public:
    explicit SynthEngine(AtomicTransport& transport);
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

    // ── Step sequencer control ──────────────────────────────────────────

    /** Replace the drum pattern. Called from UI thread via JNI. */
    void updateDrumPattern(int stepsPerBar, int bars, int64_t offsetFrames,
                           float volume, bool muted,
                           const std::vector<DrumHit>& hits,
                           const std::vector<int64_t>& clipOffsetFrames = {});

    /** Enable/disable the step sequencer. */
    void setSequencerEnabled(bool enabled);

    /** Get max end frame from the step sequencer for timeline length. */
    int64_t getSequencerMaxEndFrame(double bpm) const;

private:
    void renderThreadFunc();

    AtomicTransport& transport_;

    void* settings_ = nullptr;   // fluid_settings_t* (avoid header dependency)
    void* synth_ = nullptr;      // fluid_synth_t*
    int sfontId_ = -1;

    SpscRingBuffer<kSynthRingBufferCapacity> ringBuffer_;

    std::thread renderThread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> soundFontLoaded_{false};
    std::atomic<float> volume_{1.0f};
    std::atomic<bool> flushRequested_{false};

    // Step sequencer
    StepSequencer sequencer_;
    std::atomic<bool> sequencerEnabled_{false};
    int64_t renderPos_ = 0;       // render thread's timeline position
    bool wasPlaying_ = false;     // for detecting play/pause transitions
};

}  // namespace nightjar
