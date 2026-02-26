#pragma once

#include "wav_track_source.h"
#include "audio_engine.h"
#include <atomic>
#include <memory>
#include <mutex>
#include <vector>
#include <cmath>

namespace nightjar {

/**
 * Per-track slot in the mixer.
 *
 * Volume and muted are atomic — the audio callback reads them,
 * the UI thread writes them. No locking needed.
 * The WavTrackSource is immutable once set (replaced on track list swap).
 */
struct TrackSlot {
    int trackId = 0;
    std::shared_ptr<WavTrackSource> source;
    int64_t offsetFrames = 0;
    int64_t trimStartFrames = 0;
    int64_t trimEndFrames = 0;
    int64_t effectiveFrames = 0;  // duration - trimStart - trimEnd
    std::atomic<float> volume{1.0f};
    std::atomic<bool> muted{false};
};

/**
 * Multi-track mixer using double-buffered track list.
 *
 * The audio callback reads from the active list via an atomic pointer.
 * The UI thread modifies the inactive list under a mutex, then swaps
 * the pointer. The audio callback NEVER blocks.
 *
 * ## Rendering
 * For each non-muted track, maps the global position to a local frame
 * (accounting for offset + trim), reads from the mmap'd WAV source,
 * multiplies by volume, and sums into the output buffer. The result
 * is soft-clipped via tanh() to prevent harsh digital clipping when
 * many tracks overlap.
 *
 * ## Output format
 * Mono source → stereo output (same sample to L+R channels, panned center).
 */
class TrackMixer {
public:
    TrackMixer();
    ~TrackMixer();

    /**
     * Add a track to the mixer. Called from the UI thread.
     * The source is opened (mmap'd) here.
     */
    bool addTrack(int trackId, const std::string& filePath,
                  int64_t durationMs, int64_t offsetMs,
                  int64_t trimStartMs, int64_t trimEndMs,
                  float volume, bool muted);

    /** Remove a track by ID. Called from the UI thread. */
    void removeTrack(int trackId);

    /** Remove all tracks. Called from the UI thread. */
    void removeAllTracks();

    /** Set volume for a track. Lock-free (atomic write). */
    void setTrackVolume(int trackId, float volume);

    /** Set muted state for a track. Lock-free (atomic write). */
    void setTrackMuted(int trackId, bool muted);

    /**
     * Compute the total timeline duration from all loaded tracks.
     * Returns duration in frames.
     */
    int64_t computeTotalFrames() const;

    /**
     * Render mixed audio into the output buffer.
     * Called from the audio callback — must be lock-free.
     *
     * @param output Stereo interleaved float buffer (L, R, L, R, ...).
     * @param numFrames Number of stereo frames to render.
     * @param positionFrames Current global position in frames.
     */
    void renderFrames(float* output, int32_t numFrames, int64_t positionFrames);

private:
    using SlotList = std::vector<std::shared_ptr<TrackSlot>>;

    /** Swap the active list pointer. The audio callback picks up the new list. */
    void commitToActive();

    /**
     * Two slot lists — the audio callback reads from activeList_,
     * the UI thread edits pendingList_ then swaps.
     */
    SlotList listA_;
    SlotList listB_;
    std::atomic<SlotList*> activeList_{&listA_};
    std::mutex editMutex_;  // protects UI-thread edits to the inactive list
};

}  // namespace nightjar
