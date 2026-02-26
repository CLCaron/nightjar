#include "track_mixer.h"
#include "common.h"
#include <algorithm>
#include <cstring>

namespace nightjar {

// Stack-allocated mix buffer for one callback's worth of mono samples.
static constexpr int32_t kMaxFramesPerCallback = 2048;

TrackMixer::TrackMixer() = default;
TrackMixer::~TrackMixer() = default;

bool TrackMixer::addTrack(int trackId, const std::string& filePath,
                          int64_t durationMs, int64_t offsetMs,
                          int64_t trimStartMs, int64_t trimEndMs,
                          float volume, bool muted) {

    auto source = std::make_shared<WavTrackSource>();
    if (!source->open(filePath)) {
        LOGE("TrackMixer: failed to open track %d: %s", trackId, filePath.c_str());
        return false;
    }

    auto slot = std::make_shared<TrackSlot>();
    slot->trackId = trackId;
    slot->source = source;
    slot->offsetFrames = msToFrames(offsetMs);
    slot->trimStartFrames = msToFrames(trimStartMs);
    slot->trimEndFrames = msToFrames(trimEndMs);
    slot->effectiveFrames = msToFrames(durationMs - trimStartMs - trimEndMs);
    slot->volume.store(volume, std::memory_order_relaxed);
    slot->muted.store(muted, std::memory_order_relaxed);

    LOGD("TrackMixer: addTrack id=%d, offset=%lld, trimStart=%lld, trimEnd=%lld, "
         "effective=%lld frames, volume=%.2f, muted=%d",
         trackId, (long long)slot->offsetFrames, (long long)slot->trimStartFrames,
         (long long)slot->trimEndFrames, (long long)slot->effectiveFrames,
         volume, muted ? 1 : 0);

    {
        std::lock_guard<std::mutex> lock(editMutex_);
        // Edit the inactive list
        SlotList* active = activeList_.load(std::memory_order_acquire);
        SlotList* inactive = (active == &listA_) ? &listB_ : &listA_;
        *inactive = *active;  // copy current state
        inactive->push_back(slot);
        commitToActive();
    }

    return true;
}

void TrackMixer::removeTrack(int trackId) {
    std::lock_guard<std::mutex> lock(editMutex_);
    SlotList* active = activeList_.load(std::memory_order_acquire);
    SlotList* inactive = (active == &listA_) ? &listB_ : &listA_;
    *inactive = *active;

    inactive->erase(
        std::remove_if(inactive->begin(), inactive->end(),
            [trackId](const std::shared_ptr<TrackSlot>& s) {
                return s->trackId == trackId;
            }),
        inactive->end()
    );

    commitToActive();
}

void TrackMixer::removeAllTracks() {
    std::lock_guard<std::mutex> lock(editMutex_);
    SlotList* active = activeList_.load(std::memory_order_acquire);
    SlotList* inactive = (active == &listA_) ? &listB_ : &listA_;
    inactive->clear();
    commitToActive();
}

void TrackMixer::setTrackVolume(int trackId, float volume) {
    // Lock-free — just scan the active list and write the atomic.
    SlotList* list = activeList_.load(std::memory_order_acquire);
    for (auto& slot : *list) {
        if (slot->trackId == trackId) {
            slot->volume.store(volume, std::memory_order_relaxed);
            return;
        }
    }
}

void TrackMixer::setTrackMuted(int trackId, bool muted) {
    SlotList* list = activeList_.load(std::memory_order_acquire);
    for (auto& slot : *list) {
        if (slot->trackId == trackId) {
            slot->muted.store(muted, std::memory_order_relaxed);
            return;
        }
    }
}

int64_t TrackMixer::computeTotalFrames() const {
    SlotList* list = activeList_.load(std::memory_order_acquire);
    int64_t maxEnd = 0;
    for (const auto& slot : *list) {
        int64_t end = slot->offsetFrames + slot->effectiveFrames;
        if (end > maxEnd) maxEnd = end;
    }
    return maxEnd;
}

void TrackMixer::renderFrames(float* output, int32_t numFrames, int64_t positionFrames) {
    // Zero the stereo output buffer
    std::memset(output, 0, static_cast<size_t>(numFrames) * kOutputChannelCount * sizeof(float));

    SlotList* list = activeList_.load(std::memory_order_acquire);
    if (!list || list->empty()) return;

    // Stack-allocated mono mix buffer
    float monoBuf[kMaxFramesPerCallback];
    int32_t framesToProcess = std::min(numFrames, kMaxFramesPerCallback);

    for (const auto& slot : *list) {
        if (slot->muted.load(std::memory_order_relaxed)) continue;
        if (!slot->source || !slot->source->isOpen()) continue;

        float vol = slot->volume.load(std::memory_order_relaxed);
        if (vol <= 0.0f) continue;

        // Map global position → local frame within this track
        // Global position corresponds to: offset + trimStart + localPlayFrame
        // So localPlayFrame = globalPos - offset
        // And the source frame = trimStart + localPlayFrame
        int64_t localFrame = positionFrames - slot->offsetFrames;

        // Skip if this track hasn't started or has ended
        if (localFrame >= slot->effectiveFrames || localFrame + framesToProcess <= 0) continue;

        // Clamp to the portion of this callback that overlaps the track
        int32_t skipOutput = 0;  // frames to skip in the output buffer
        int64_t sourceStart = slot->trimStartFrames + localFrame;
        int32_t readCount = framesToProcess;

        if (localFrame < 0) {
            // Track starts partway through this callback
            skipOutput = static_cast<int32_t>(-localFrame);
            sourceStart = slot->trimStartFrames;
            readCount = framesToProcess - skipOutput;
        }

        int64_t remaining = slot->effectiveFrames - std::max(localFrame, (int64_t)0);
        if (readCount > remaining) {
            readCount = static_cast<int32_t>(remaining);
        }

        if (readCount <= 0) continue;

        // Read mono samples from the mmap'd source
        int64_t read = slot->source->readFrames(monoBuf, sourceStart, readCount);

        // Mix into stereo output: mono → L+R (center pan)
        for (int64_t i = 0; i < read; ++i) {
            float sample = monoBuf[i] * vol;
            int32_t outIdx = (skipOutput + static_cast<int32_t>(i)) * kOutputChannelCount;
            output[outIdx]     += sample;  // Left
            output[outIdx + 1] += sample;  // Right
        }
    }

    // Soft-clip via tanh to prevent harsh digital clipping
    int32_t totalSamples = numFrames * kOutputChannelCount;
    for (int32_t i = 0; i < totalSamples; ++i) {
        output[i] = std::tanh(output[i]);
    }
}

void TrackMixer::commitToActive() {
    // Swap: the inactive list (which we just edited) becomes active.
    SlotList* current = activeList_.load(std::memory_order_acquire);
    SlotList* newActive = (current == &listA_) ? &listB_ : &listA_;
    activeList_.store(newActive, std::memory_order_release);
}

}  // namespace nightjar
