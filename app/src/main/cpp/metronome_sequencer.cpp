#include "metronome_sequencer.h"
#include "common.h"
#include <algorithm>
#include <cmath>

namespace nightjar {

MetronomeSequencer::MetronomeSequencer() {
    pendingEvents_.reserve(8);
}

void MetronomeSequencer::reset() {
    lastBeatIndex_ = -1;
    pendingEvents_.clear();
}

void MetronomeSequencer::resetToPosition(int64_t pos) {
    pendingEvents_.clear();
    // Compute which beat we'd be on at this position so we don't re-trigger
    // the beat at the reset point. Set to -1 to allow the first beat to fire.
    lastBeatIndex_ = -1;
}

const std::vector<NoteEvent>& MetronomeSequencer::tick(
        int64_t renderPos, int32_t chunkFrames, double bpm) {
    pendingEvents_.clear();

    if (!enabled_.load(std::memory_order_acquire) || bpm <= 0.0) {
        return pendingEvents_;
    }

    float volume = volume_.load(std::memory_order_relaxed);
    int beatsPerBar = beatsPerBar_.load(std::memory_order_relaxed);
    if (beatsPerBar <= 0) beatsPerBar = 4;

    double framesPerBeat = (60.0 / bpm) * static_cast<double>(kSampleRate);
    if (framesPerBeat <= 0.0) return pendingEvents_;

    // Check all beats that fall within [renderPos, renderPos + chunkFrames)
    int64_t chunkEnd = renderPos + chunkFrames;

    // First beat index in this chunk (ceiling)
    auto firstBeat = static_cast<int64_t>(
        std::ceil(static_cast<double>(renderPos) / framesPerBeat));
    // Last possible beat in this chunk (floor, exclusive end)
    auto lastBeat = static_cast<int64_t>(
        std::floor(static_cast<double>(chunkEnd - 1) / framesPerBeat));

    for (int64_t beatIdx = firstBeat; beatIdx <= lastBeat; ++beatIdx) {
        // Skip if we already triggered this beat
        if (beatIdx == lastBeatIndex_) continue;

        auto beatFrame = static_cast<int64_t>(beatIdx * framesPerBeat);
        if (beatFrame < renderPos || beatFrame >= chunkEnd) continue;

        int32_t offset = static_cast<int32_t>(
            std::clamp(beatFrame - renderPos,
                       static_cast<int64_t>(0),
                       static_cast<int64_t>(chunkFrames - 1)));

        // Determine if this is beat 1 (accent) or a normal beat.
        // C++ remainder can be negative for negative beatIdx, so normalize.
        int beatInBar = static_cast<int>(beatIdx % beatsPerBar);
        if (beatInBar < 0) beatInBar += beatsPerBar;
        int note = (beatInBar == 0) ? kAccentNote : kNormalNote;

        int vel = static_cast<int>(volume * 100.0f);
        vel = std::clamp(vel, 0, 127);

        if (vel > 0) {
            pendingEvents_.push_back({kPercussionChannel, note, vel, offset});
        }

        // Report this beat frame for UI LED pulse
        lastBeatFrame_.store(beatFrame, std::memory_order_relaxed);
        lastBeatIndex_ = beatIdx;
    }

    return pendingEvents_;
}

}  // namespace nightjar
