#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

namespace nightjar {

/** A single active hit in the drum grid. */
struct DrumHit {
    int stepIndex;   // 0-based position in pattern
    int drumNote;    // GM drum note number (e.g. 36 = kick)
    int velocity;    // MIDI velocity 0-127
};

/** A note event produced by the sequencer for SynthEngine to process. */
struct NoteEvent {
    int channel;
    int note;
    int velocity;    // > 0 = noteOn, 0 = noteOff
};

/**
 * Step sequencer that plays drum patterns in sync with the timeline.
 *
 * Pattern data is double-buffered (same strategy as TrackMixer):
 * UI writes to the inactive buffer under a mutex, then atomically swaps.
 * The render thread reads lock-free from the active buffer.
 *
 * tick() is called from SynthEngine's render thread between render chunks.
 * It returns a list of NoteEvent to fire into FluidSynth. The caller handles
 * the actual fluid_synth_noteon() calls, keeping FluidSynth dependency out
 * of this header.
 */
class StepSequencer {
public:
    StepSequencer();

    /**
     * Replace the entire pattern. Called from UI thread (JNI).
     * Mutex-protected, writes to inactive buffer, then swaps.
     *
     * @param beatsPerBar  Beats per measure from time signature numerator (default 4).
     */
    void updatePattern(int stepsPerBar, int bars, int64_t offsetFrames,
                       float volume, bool muted,
                       const std::vector<DrumHit>& hits,
                       const std::vector<int64_t>& clipOffsetFrames = {},
                       int beatsPerBar = 4);

    /**
     * Advance the sequencer and return note events for this chunk.
     * Called from the render thread -- lock-free read of active pattern.
     *
     * @param renderPos   Current render position in frames (global timeline).
     * @param chunkFrames Number of frames in this render chunk.
     * @param bpm         Current tempo from transport.
     * @return Reference to internal event buffer (valid until next tick).
     */
    const std::vector<NoteEvent>& tick(int64_t renderPos, int32_t chunkFrames, double bpm);

    /** Reset step tracking state. Call on flush (seek/loop). */
    void reset();

    /**
     * Compute the maximum end frame across all clip placements of the pattern.
     * Used by AudioEngine to determine total timeline length.
     * Must be called under editMutex_ or after the pattern has been committed.
     */
    int64_t getMaxEndFrame(double bpm) const;

private:
    struct Pattern {
        int stepsPerBar = 16;
        int bars = 1;
        int beatsPerBar = 4;  // from time signature numerator
        int64_t offsetFrames = 0;
        float volume = 1.0f;
        bool muted = false;
        std::vector<DrumHit> hits;
        std::vector<int64_t> clipOffsetFrames;   // timeline offsets for each clip

        int totalSteps() const { return stepsPerBar * bars; }
    };

    Pattern patternA_, patternB_;
    std::atomic<Pattern*> activePattern_{&patternA_};
    std::mutex editMutex_;

    int lastStepIndex_ = -1;
    std::vector<NoteEvent> pendingEvents_;

    void commitToActive();
};

}  // namespace nightjar
