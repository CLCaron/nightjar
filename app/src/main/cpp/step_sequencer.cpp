#include "step_sequencer.h"
#include "common.h"
#include <algorithm>
#include <cmath>

namespace nightjar {

StepSequencer::StepSequencer() {
    pendingEvents_.reserve(16);
}

void StepSequencer::updatePattern(float volume, bool muted,
                                   const std::vector<ClipSlot>& clips) {
    std::lock_guard<std::mutex> lock(editMutex_);
    Pattern* active = activePattern_.load(std::memory_order_acquire);
    Pattern* inactive = (active == &patternA_) ? &patternB_ : &patternA_;

    inactive->volume = volume;
    inactive->muted = muted;
    inactive->clips = clips;

    commitToActive();

    // Resize step tracking to match clip count
    if (lastStepIndices_.size() != clips.size()) {
        lastStepIndices_.assign(clips.size(), -1);
    }
}

void StepSequencer::updatePattern(int stepsPerBar, int bars, int64_t offsetFrames,
                                   float volume, bool muted,
                                   const std::vector<DrumHit>& hits,
                                   const std::vector<int64_t>& clipOffsetFrames,
                                   int beatsPerBar) {
    // Convert legacy single-pattern call to per-clip format.
    // Legacy callers pass `bars`; translate to totalSteps here.
    std::vector<ClipSlot> clips;
    int bpb = beatsPerBar > 0 ? beatsPerBar : 4;
    int totalSteps = stepsPerBar * bars;

    if (clipOffsetFrames.empty()) {
        ClipSlot slot;
        slot.stepsPerBar = stepsPerBar;
        slot.totalSteps = totalSteps;
        slot.beatsPerBar = bpb;
        slot.offsetFrames = offsetFrames;
        slot.hits = hits;
        clips.push_back(std::move(slot));
    } else {
        for (int64_t clipOffset : clipOffsetFrames) {
            ClipSlot slot;
            slot.stepsPerBar = stepsPerBar;
            slot.totalSteps = totalSteps;
            slot.beatsPerBar = bpb;
            slot.offsetFrames = clipOffset;
            slot.hits = hits;
            clips.push_back(std::move(slot));
        }
    }

    updatePattern(volume, muted, clips);
}

void StepSequencer::reset() {
    std::fill(lastStepIndices_.begin(), lastStepIndices_.end(), -1);
    pendingEvents_.clear();
}

const std::vector<NoteEvent>& StepSequencer::tick(
        int64_t renderPos, int32_t chunkFrames, double bpm) {
    pendingEvents_.clear();

    Pattern* pat = activePattern_.load(std::memory_order_acquire);
    if (!pat || pat->muted || pat->clips.empty() || bpm <= 0.0) {
        return pendingEvents_;
    }

    // Ensure step tracking vector matches clip count
    if (lastStepIndices_.size() != pat->clips.size()) {
        lastStepIndices_.assign(pat->clips.size(), -1);
    }

    // Process each clip independently with its own step tracking
    for (size_t ci = 0; ci < pat->clips.size(); ++ci) {
        const auto& clip = pat->clips[ci];
        int totalSteps = clip.totalSteps;
        if (totalSteps <= 0 || clip.hits.empty()) continue;

        double stepsPerBeat = clip.stepsPerBar / static_cast<double>(clip.beatsPerBar);
        double framesPerStep = (static_cast<double>(kSampleRate) * 60.0) / (bpm * stepsPerBeat);
        double totalPatternFrames = framesPerStep * totalSteps;

        if (totalPatternFrames <= 0.0) continue;

        int64_t localPos = renderPos - clip.offsetFrames;

        // Clip hasn't started yet
        if (localPos + chunkFrames <= 0) continue;

        // Clip already finished (one-shot: no looping)
        if (localPos >= static_cast<int64_t>(totalPatternFrames)) continue;

        // Clamp to clip start
        if (localPos < 0) localPos = 0;

        int currentStep = static_cast<int>(std::floor(
            static_cast<double>(localPos) / framesPerStep));
        currentStep = std::min(currentStep, totalSteps - 1);

        int& lastStep = lastStepIndices_[ci];

        if (currentStep != lastStep) {
            int stepsToProcess;
            if (lastStep < 0) {
                stepsToProcess = 1;
            } else {
                stepsToProcess = currentStep - lastStep;
                if (stepsToProcess <= 0) {
                    // Jumped backwards (seek) -- reset and trigger current step
                    stepsToProcess = 1;
                }
                stepsToProcess = std::min(stepsToProcess, totalSteps);
            }

            for (int i = 0; i < stepsToProcess; ++i) {
                int step;
                if (lastStep < 0 || stepsToProcess == 1) {
                    step = currentStep;
                } else {
                    step = lastStep + 1 + i;
                    if (step >= totalSteps) break;  // one-shot: don't wrap
                }

                // Exact frame where this step lands on the global timeline
                auto stepFrame = static_cast<int64_t>(
                    step * framesPerStep) + clip.offsetFrames;
                int32_t offset = static_cast<int32_t>(
                    std::clamp(stepFrame - renderPos,
                               static_cast<int64_t>(0),
                               static_cast<int64_t>(chunkFrames - 1)));

                for (const auto& hit : clip.hits) {
                    // Honor the clip length ceiling: any hit beyond totalSteps
                    // is preserved in data but silent (uniform-clip-length).
                    if (hit.stepIndex >= totalSteps) continue;
                    if (hit.stepIndex == step) {
                        int vel = static_cast<int>(
                            static_cast<float>(hit.velocity) * pat->volume);
                        vel = std::clamp(vel, 0, 127);
                        if (vel > 0) {
                            pendingEvents_.push_back({9, hit.drumNote, vel, offset});
                        }
                    }
                }
            }

            lastStep = currentStep;
        }
    }

    return pendingEvents_;
}

int64_t StepSequencer::getMaxEndFrame(double bpm) const {
    Pattern* pat = activePattern_.load(std::memory_order_acquire);
    if (!pat || bpm <= 0.0) return 0;

    int64_t maxEnd = 0;
    for (const auto& clip : pat->clips) {
        int totalSteps = clip.totalSteps;
        if (totalSteps <= 0) continue;

        double stepsPerBeat = clip.stepsPerBar / static_cast<double>(clip.beatsPerBar);
        double framesPerStep = (static_cast<double>(kSampleRate) * 60.0) / (bpm * stepsPerBeat);
        auto totalPatternFrames = static_cast<int64_t>(framesPerStep * totalSteps);

        int64_t end = clip.offsetFrames + totalPatternFrames;
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
