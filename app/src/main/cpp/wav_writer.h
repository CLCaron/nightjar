#pragma once

#include "spsc_ring_buffer.h"
#include "common.h"
#include <atomic>
#include <thread>
#include <string>
#include <cstdio>

namespace nightjar {

/** Ring buffer capacity: 2^17 = 131072 samples (~3 seconds at 44.1kHz). */
constexpr size_t kRingBufferCapacity = 131072;

/**
 * Consumes float32 samples from a ring buffer on a dedicated thread
 * and writes them as 16-bit PCM WAV to disk.
 *
 * The writer thread is the ONLY place file I/O happens during recording.
 * The audio callback thread never touches the filesystem.
 */
class WavWriter {
public:
    WavWriter();
    ~WavWriter();

    /**
     * Open a WAV file for writing and write the placeholder header.
     * Returns true on success.
     */
    bool open(const std::string& filePath);

    /**
     * Start the consumer thread that drains the ring buffer and writes
     * samples to the open WAV file.
     */
    void startConsuming(SpscRingBuffer<kRingBufferCapacity>& ringBuffer);

    /**
     * Signal the consumer thread to stop, drain remaining data, patch
     * the WAV header, and close the file.
     */
    void stopConsuming();

    /** Total PCM data bytes written (excluding 44-byte header). */
    int64_t getTotalBytesWritten() const {
        return totalBytesWritten_.load(std::memory_order_relaxed);
    }

    /** Recording duration in milliseconds. */
    int64_t getDurationMs() const {
        int64_t bytes = getTotalBytesWritten();
        return (bytes * 1000L) / (kSampleRate * kChannelCount * kBytesPerSample);
    }

private:
    void writerLoop(SpscRingBuffer<kRingBufferCapacity>& ringBuffer);
    void writeWavHeader();
    void patchWavHeader();
    void drainRingBuffer(SpscRingBuffer<kRingBufferCapacity>& ringBuffer);

    FILE* file_ = nullptr;
    std::thread writerThread_;
    std::atomic<bool> running_{false};
    std::atomic<int64_t> totalBytesWritten_{0};
    std::string filePath_;
};

}  // namespace nightjar
