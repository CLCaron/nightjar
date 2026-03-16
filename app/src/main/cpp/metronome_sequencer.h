#pragma once

#include "step_sequencer.h"  // for NoteEvent
#include <atomic>
#include <cstdint>
#include <vector>

namespace nightjar {

/**
 * Metronome that fires GM percussion events on beat boundaries.
 *
 * Simple beat math -- no pattern data, no double-buffering needed.
 * Config is all atomics, safe for lock-free reads from the render thread.
 *
 * Beat 1 (accent): GM note 76 (Hi Wood Block)
 * Other beats:     GM note 37 (Side Stick)
 * All events on channel 9 (GM percussion).
 *
 * tick() is called from SynthEngine's render thread between render chunks.
 * Returns NoteEvents with sub-buffer frameOffset for sample-accurate timing.
 */
class MetronomeSequencer {
public:
    MetronomeSequencer();

    /**
     * Advance the metronome and return note events for this chunk.
     * Called from the render thread -- reads only atomics.
     */
    const std::vector<NoteEvent>& tick(int64_t renderPos, int32_t chunkFrames, double bpm);

    /** Reset internal tracking state. Call on flush/stop. */
    void reset();

    /** Reset and set position for seek/loop. */
    void resetToPosition(int64_t pos);

    // ── Configuration (atomic, set from any thread) ───────────────────

    void setEnabled(bool enabled) { enabled_.store(enabled, std::memory_order_release); }
    bool isEnabled() const { return enabled_.load(std::memory_order_acquire); }

    void setVolume(float volume) { volume_.store(volume, std::memory_order_relaxed); }
    float getVolume() const { return volume_.load(std::memory_order_relaxed); }

    void setBeatsPerBar(int beats) { beatsPerBar_.store(beats, std::memory_order_relaxed); }

    /** Frame of the last beat event fired. Polled by UI for LED pulse. */
    int64_t getLastBeatFrame() const { return lastBeatFrame_.load(std::memory_order_relaxed); }

private:
    std::atomic<bool> enabled_{false};
    std::atomic<float> volume_{0.7f};
    std::atomic<int> beatsPerBar_{4};

    // Beat tracking (render thread only, int64 to support negative count-in indices)
    int64_t lastBeatIndex_ = -1;

    // Beat pulse reporting (written by render thread, read by UI via JNI)
    std::atomic<int64_t> lastBeatFrame_{-1};

    std::vector<NoteEvent> pendingEvents_;

    // GM percussion notes
    static constexpr int kAccentNote = 76;  // Hi Wood Block
    static constexpr int kNormalNote = 37;  // Side Stick
    static constexpr int kPercussionChannel = 9;
};

}  // namespace nightjar
