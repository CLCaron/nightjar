#include "step_sequencer.h"
#include "common.h"
#include <algorithm>
#include <cmath>

namespace nightjar {

StepSequencer::StepSequencer() {
    pendingEvents_.reserve(16);
}

void StepSequencer::updatePattern(int stepsPerBar, int bars, int64_t offsetFrames,
                                   float volume, bool muted,
                                   const std::vector<DrumHit>& hits,
                                   const std::vector<int64_t>& clipOffsetFrames) {
    std::lock_guard<std::mutex> lock(editMutex_);
    Pattern* active = activePattern_.load(std::memory_order_acquire);
    Pattern* inactive = (active == &patternA_) ? &patternB_ : &patternA_;

    inactive->stepsPerBar = stepsPerBar;
    inactive->bars = bars;
    inactive->offsetFrames = offsetFrames;
    inactive->volume = volume;
    inactive->muted = muted;
    inactive->hits = hits;

    // If clip offsets provided, use them; otherwise fall back to single clip at offsetFrames
    if (clipOffsetFrames.empty()) {
        inactive->clipOffsetFrames = {offsetFrames};
    } else {
        inactive->clipOffsetFrames = clipOffsetFrames;
    }

    commitToActive();
}

void StepSequencer::reset() {
    lastStepIndex_ = -1;
    pendingEvents_.clear();
}

const std::vector<NoteEvent>& StepSequencer::tick(
        int64_t renderPos, int32_t chunkFrames, double bpm) {
    pendingEvents_.clear();

    Pattern* pat = activePattern_.load(std::memory_order_acquire);
    if (!pat || pat->muted || pat->hits.empty() || bpm <= 0.0) {
        return pendingEvents_;
    }

    int totalSteps = pat->totalSteps();
    if (totalSteps <= 0) return pendingEvents_;

    // Calculate frame positions for steps.
    // stepsPerBeat = stepsPerBar / 4 (4/4 time signature)
    double stepsPerBeat = pat->stepsPerBar / 4.0;
    double framesPerStep = (static_cast<double>(kSampleRate) * 60.0) / (bpm * stepsPerBeat);
    double totalPatternFrames = framesPerStep * totalSteps;

    if (totalPatternFrames <= 0.0) return pendingEvents_;

    // Iterate over each clip placement and fire notes for any clip in range
    for (int64_t clipOffset : pat->clipOffsetFrames) {
        int64_t localPos = renderPos - clipOffset;

        // Clip hasn't started yet
        if (localPos + chunkFrames <= 0) continue;

        // Clip already finished (one-shot: no looping)
        if (localPos >= static_cast<int64_t>(totalPatternFrames)) continue;

        // Clamp to clip start
        if (localPos < 0) localPos = 0;

        // Current step at this render position (no wrapping -- one-shot)
        int currentStep = static_cast<int>(std::floor(
            static_cast<double>(localPos) / framesPerStep));
        currentStep = std::min(currentStep, totalSteps - 1);

        // For multi-clip, we track step per-clip using a simple approach:
        // Since clips don't overlap in typical use, we use the shared lastStepIndex_.
        // For overlapping clips, this could miss steps, but that's an edge case
        // we'll handle if needed. For now, fire events for the current step.
        if (currentStep != lastStepIndex_) {
            int stepsToProcess;
            if (lastStepIndex_ < 0) {
                stepsToProcess = 1;
            } else {
                stepsToProcess = currentStep - lastStepIndex_;
                if (stepsToProcess <= 0) {
                    // We've moved to a new clip -- reset and trigger current step
                    stepsToProcess = 1;
                }
                stepsToProcess = std::min(stepsToProcess, totalSteps);
            }

            for (int i = 0; i < stepsToProcess; ++i) {
                int step;
                if (lastStepIndex_ < 0 || stepsToProcess == 1) {
                    step = currentStep;
                } else {
                    step = lastStepIndex_ + 1 + i;
                    if (step >= totalSteps) break;  // one-shot: don't wrap
                }

                for (const auto& hit : pat->hits) {
                    if (hit.stepIndex == step) {
                        int vel = static_cast<int>(
                            static_cast<float>(hit.velocity) * pat->volume);
                        vel = std::clamp(vel, 0, 127);
                        if (vel > 0) {
                            pendingEvents_.push_back({9, hit.drumNote, vel});
                        }
                    }
                }
            }

            lastStepIndex_ = currentStep;
        }
    }

    return pendingEvents_;
}

int64_t StepSequencer::getMaxEndFrame(double bpm) const {
    // Read from whichever buffer was last committed (called from UI thread after update)
    Pattern* pat = activePattern_.load(std::memory_order_acquire);
    if (!pat || bpm <= 0.0) return 0;

    int totalSteps = pat->totalSteps();
    if (totalSteps <= 0) return 0;

    double stepsPerBeat = pat->stepsPerBar / 4.0;
    double framesPerStep = (static_cast<double>(kSampleRate) * 60.0) / (bpm * stepsPerBeat);
    auto totalPatternFrames = static_cast<int64_t>(framesPerStep * totalSteps);

    int64_t maxEnd = 0;
    for (int64_t clipOffset : pat->clipOffsetFrames) {
        int64_t end = clipOffset + totalPatternFrames;
        if (end > maxEnd) maxEnd = end;
    }

    return maxEnd;
}

void StepSequencer::commitToActive() {
    Pattern* current = activePattern_.load(std::memory_order_acquire);
    Pattern* newActive = (current == &patternA_) ? &patternB_ : &patternA_;
    activePattern_.store(newActive, std::memory_order_release);
}

}  // namespace nightjar
