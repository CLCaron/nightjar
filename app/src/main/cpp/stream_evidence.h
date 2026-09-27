#pragma once
#include <array>
#include <atomic>
#include <cstdint>
#include <ctime>
#include <oboe/Oboe.h>

namespace nightjar {
// Callback delivery/submission coordinates, not physical audio timestamps.
// Atomic fields prevent C++ data races even if a reader observes an odd sequence.
class StreamEvidence {
public:
    static constexpr size_t kFields = 16;
    using Snapshot = std::array<int64_t, kFields>;
    void opened(oboe::AudioStream& s) {
        sequence_.fetch_add(1, std::memory_order_acq_rel);
        epoch_.fetch_add(1);
        frames_ = 0; // reset only with callbacks stopped
        fields_[1].store(s.getDeviceId());
        fields_[2].store(s.getSampleRate());
        fields_[3].store(s.getChannelCount());
        fields_[4].store(static_cast<int>(s.getAudioApi()));
        fields_[5].store(static_cast<int>(s.getFormat()));
        fields_[6].store(static_cast<int>(s.getSharingMode()));
        fields_[7].store(static_cast<int>(s.getPerformanceMode()));
        fields_[8].store(s.getFramesPerBurst());
        fields_[9].store(s.getBufferSizeInFrames());
        fields_[10].store(static_cast<int>(s.getInputPreset()));
        for (size_t i = 11; i < kFields; ++i) fields_[i].store(0);
        fields_[0].store(1, std::memory_order_release);
        sequence_.fetch_add(1, std::memory_order_release);
    }
    std::array<int64_t, 2> callback(int32_t count, int64_t timeline) {
        timespec now{};
        const auto nanos = clock_gettime(CLOCK_MONOTONIC, &now) == 0
            ? static_cast<int64_t>(now.tv_sec) * 1000000000LL + now.tv_nsec : 0;
        sequence_.fetch_add(1, std::memory_order_acq_rel);
        fields_[11].store(frames_, std::memory_order_relaxed);
        fields_[12].store(nanos, std::memory_order_relaxed);
        fields_[13].store(count, std::memory_order_relaxed);
        fields_[14].store(timeline, std::memory_order_relaxed);
        const auto startFrame = frames_;
        frames_ += count;
        sequence_.fetch_add(1, std::memory_order_release);
        return {startFrame, nanos};
    }
    void dropped(int64_t n) { fields_[15].fetch_add(n, std::memory_order_relaxed); }
    void closed() { fields_[0].store(0, std::memory_order_release); }
    int64_t epoch() const { return epoch_.load(std::memory_order_acquire); }
    Snapshot snapshot() const {
        Snapshot r{};
        for (int attempt = 0; attempt < 4; ++attempt) {
            auto before = sequence_.load(std::memory_order_acquire);
            if (before & 1) continue;
            for (size_t i = 0; i < kFields; ++i) r[i] = fields_[i].load(std::memory_order_acquire);
            if (before == sequence_.load(std::memory_order_acquire)) return r;
        }
        r[12] = 0; // torn anchor is unusable, route metadata still useful
        return r;
    }
private:
    std::array<std::atomic<int64_t>, kFields> fields_{};
    std::atomic<int64_t> epoch_{0};
    std::atomic<uint64_t> sequence_{0};
    int64_t frames_ = 0;
};
}
