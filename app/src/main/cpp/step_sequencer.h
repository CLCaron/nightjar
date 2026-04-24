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
    int velocity;       // > 0 = noteOn, 0 = noteOff
    int32_t frameOffset; // sample offset within render chunk (0 to chunkFrames-1)
};

/**
 * Step sequencer that plays drum patterns in sync with the timeline.
 *
 * Each clip slot has its own pattern (stepsPerBar, bars, hits) and timeline
 * offset, with independent step tracking so clips play correctly even when
 * overlapping.
 *
 * Pattern data is double-buffered (same strategy as TrackMixer):
 * UI writes to the inactive buffer under a mutex, then atomically swaps.
 * The render thread reads lock-free from the active buffer.
 *
 * tick() is called from SynthEngine's render thread between render chunks.
 * It returns a list of NoteEvent to fire into FluidSynth.
 */
class StepSequencer {
public:
    StepSequencer();

    /** A single clip with its own pattern data and timeline position. */
    struct ClipSlot {
        int stepsPerBar = 16;
        // Authoritative step count. Allows sub-bar and non-bar-aligned clips.
        // Fed from DrumPatternEntity.lengthSteps on the Kotlin side.
        int totalSteps = 16;
        int beatsPerBar = 4;
        int64_t offsetFrames = 0;
        std::vector<DrumHit> hits;
    };

    /**
     * Replace the entire pattern with per-clip data. Called from UI thread (JNI).
     * Mutex-protected, writes to inactive buffer, then swaps.
     */
    void updatePattern(float volume, bool muted,
                       const std::vector<ClipSlot>& clips);

    /**
     * Legacy updatePattern for backwards compatibility.
     * Wraps the single-pattern data into a single ClipSlot.
     */
    void updatePattern(int stepsPerBar, int bars, int64_t offsetFrames,
                       float volume, bool muted,
                       const std::vector<DrumHit>& hits,
                       const std::vector<int64_t>& clipOffsetFrames = {},
                       int beatsPerBar = 4);

    /**
     * Advance the sequencer and return note events for this chunk.
     * Called from the render thread -- lock-free read of active pattern.
     */
    const std::vector<NoteEvent>& tick(int64_t renderPos, int32_t chunkFrames, double bpm);

    /** Reset step tracking state. Call on flush (seek/loop). */
    void reset();

    /**
     * Compute the maximum end frame across all clip placements.
     * Used by AudioEngine to determine total timeline length.
     */
    int64_t getMaxEndFrame(double bpm) const;

private:
    struct Pattern {
        float volume = 1.0f;
        bool muted = false;
        std::vector<ClipSlot> clips;
    };

    Pattern patternA_, patternB_;
    std::atomic<Pattern*> activePattern_{&patternA_};
    std::mutex editMutex_;

    // Per-clip step tracking (indexed by clip slot position)
    std::vector<int> lastStepIndices_;
    std::vector<NoteEvent> pendingEvents_;

    void commitToActive();
};

}  // namespace nightjar
