#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <vector>

namespace nightjar {
// Each stream has one writer. Data is read only after stopInput + quiesceOutput.
// Allocation, setup and analysis happen on the control thread, never callbacks.
class AcousticCheck {
public:
    static constexpr int kProbeCount = 11;
    static constexpr int kProbeFrames = 1764;
    static constexpr int kMaxFrames = 44100 * 20;
    static constexpr int kAnchorRows = 16384;
    AcousticCheck() : samples_(kMaxFrames), inputAnchors_(kAnchorRows * 3),
                      outputAnchors_(kAnchorRows * 3) {}
    bool prepare(const float* probes, int count) {
        if (running_.load() || readers_.load() || count != kProbeCount * kProbeFrames) return false;
        for (int i = 0; i < count; ++i) if (!std::isfinite(probes[i]) || std::abs(probes[i]) > 0.1f) return false;
        std::copy(probes, probes + count, probes_.begin());
        count_.store(0); inputRows_.store(0); outputRows_.store(0); inputBase_.store(-1);
        emitted_.fill(-1); origin_ = -1; nextProbe_ = 0;
        offsets_[0] = 0;
        for (int i = 1; i < kProbeCount; ++i) offsets_[i] = offsets_[i - 1] + 50715 + (i % 3) * 441;
        emit_.store(false); aborted_.store(false); done_.store(false);
        running_.store(true, std::memory_order_release);
        return true;
    }
    bool running() const { return running_.load(std::memory_order_acquire); }
    void arm() { emit_.store(true, std::memory_order_release); }
    void abort() { aborted_.store(true, std::memory_order_release); emit_.store(false); }
    void stop() { running_.store(false, std::memory_order_release); }
    bool quiescent() const { return readers_.load(std::memory_order_acquire) == 0; }
    // Returns true when this callback owns output, including silent warm-up/tail.
    bool render(float* output, int frames, int64_t frame, int64_t nanos) {
        if (!running()) return false;
        readers_.fetch_add(1, std::memory_order_acq_rel);
        if (!running()) { readers_.fetch_sub(1, std::memory_order_release); return false; }
        anchor(outputAnchors_, outputRows_, frame, nanos, frames);
        std::fill(output, output + frames * 2, 0.0f);
        if (emit_.load(std::memory_order_acquire) && !aborted_.load()) {
            if (origin_ < 0) origin_ = frame + 4410;
            for (int i = 0; i < frames; ++i) {
                const auto q = frame + i;
                while (nextProbe_ < kProbeCount && q >= origin_ + offsets_[nextProbe_] + kProbeFrames) ++nextProbe_;
                if (nextProbe_ < kProbeCount) {
                    const auto start = origin_ + offsets_[nextProbe_];
                    const auto local = q - start;
                    if (local >= 0 && local < kProbeFrames) {
                        emitted_[nextProbe_] = start;
                        const float value = std::tanh(probes_[nextProbe_ * kProbeFrames + local]);
                        output[i * 2] = value; output[i * 2 + 1] = value;
                    }
                }
            }
            if (frame + frames >= origin_ + offsets_.back() + kProbeFrames + 44100) done_.store(true);
        }
        readers_.fetch_sub(1, std::memory_order_release);
        return true;
    }
    void capture(const float* data, int frames, int64_t frame, int64_t nanos) {
        if (!running()) return;
        const int count = count_.load(std::memory_order_relaxed);
        if (count == 0) inputBase_.store(frame);
        if (frame != inputBase_.load() + count || frames > kMaxFrames - count) { abort(); return; }
        anchor(inputAnchors_, inputRows_, frame, nanos, frames);
        std::copy(data, data + frames, samples_.begin() + count);
        count_.store(count + frames, std::memory_order_release);
    }
    std::array<int64_t, 3> progress() const { return {done_.load(), aborted_.load(), count_.load()}; }
    // Control thread only, after input close and output quiescence.
    std::vector<float> samples() const { return {samples_.begin(), samples_.begin() + count_.load()}; }
    std::vector<int64_t> evidence() const {
        std::vector<int64_t> result{inputBase_.load(), inputRows_.load(), outputRows_.load()};
        result.insert(result.end(), emitted_.begin(), emitted_.end());
        result.insert(result.end(), inputAnchors_.begin(), inputAnchors_.begin() + inputRows_.load() * 3);
        result.insert(result.end(), outputAnchors_.begin(), outputAnchors_.begin() + outputRows_.load() * 3);
        return result;
    }
private:
    void anchor(std::vector<int64_t>& target, std::atomic<int>& rows, int64_t frame, int64_t nanos, int frames) {
        const auto row = rows.load(std::memory_order_relaxed);
        if (row >= kAnchorRows || nanos <= 0) { abort(); return; }
        target[row * 3] = frame; target[row * 3 + 1] = nanos; target[row * 3 + 2] = frames;
        rows.store(row + 1, std::memory_order_release);
    }
    std::vector<float> samples_;
    std::vector<int64_t> inputAnchors_, outputAnchors_;
    std::array<float, kProbeCount * kProbeFrames> probes_{};
    std::array<int64_t, kProbeCount> emitted_{}, offsets_{};
    std::atomic<bool> running_{false}, emit_{false}, aborted_{false}, done_{false};
    std::atomic<int> count_{0}, inputRows_{0}, outputRows_{0}, readers_{0};
    std::atomic<int64_t> inputBase_{-1};
    int64_t origin_ = -1;
    int nextProbe_ = 0;
};
}
