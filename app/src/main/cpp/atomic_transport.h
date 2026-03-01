#pragma once

#include <atomic>
#include <cstdint>

namespace nightjar {

/**
 * Lock-free transport state shared between the audio callback thread
 * (reader) and the UI thread (writer via JNI).
 *
 * All fields are std::atomic — no mutexes, safe for real-time use.
 * The audio callback reads playing_, posFrames_, loopStartFrames_,
 * loopEndFrames_ every callback. The UI thread writes via play/pause/seek.
 */
struct AtomicTransport {

    /** True when playback is active. */
    std::atomic<bool> playing{false};

    /** True while overdub recording is active — allows playhead past total. */
    std::atomic<bool> recording{false};

    /** Current playback position in frames. */
    std::atomic<int64_t> posFrames{0};

    /** Total timeline duration in frames (max of offset + effective for all tracks). */
    std::atomic<int64_t> totalFrames{0};

    /** Loop region start in frames. -1 = no loop. */
    std::atomic<int64_t> loopStartFrames{-1};

    /** Loop region end in frames. -1 = no loop. */
    std::atomic<int64_t> loopEndFrames{-1};

    /** Incremented by the audio callback each time the loop resets to loopStart. */
    std::atomic<int64_t> loopResetCount{0};

    /** Returns true if a loop region is active. */
    bool hasLoop() const {
        return loopStartFrames.load(std::memory_order_relaxed) >= 0 &&
               loopEndFrames.load(std::memory_order_relaxed) > 0;
    }
};

}  // namespace nightjar
