#pragma once

#include <atomic>
#include <cstddef>
#include <cstring>
#include <algorithm>

namespace nightjar {

/**
 * Lock-free single-producer single-consumer (SPSC) ring buffer.
 *
 * The producer (Oboe audio callback) calls write().
 * The consumer (WAV writer thread) calls read().
 *
 * Operates on float samples. Capacity must be a power of 2.
 * No allocations, no mutexes, no syscalls — safe for real-time use.
 */
template<size_t N>
class SpscRingBuffer {
    static_assert((N & (N - 1)) == 0, "N must be a power of 2");

public:
    SpscRingBuffer() : writePos_(0), readPos_(0) {
        std::memset(buffer_, 0, sizeof(buffer_));
    }

    /**
     * Producer: write samples into the ring buffer.
     * Returns the number of samples actually written (may be less than
     * count if the buffer is nearly full).
     */
    size_t write(const float* data, size_t count) {
        size_t w = writePos_.load(std::memory_order_relaxed);
        size_t r = readPos_.load(std::memory_order_acquire);
        size_t available = N - (w - r);
        size_t toWrite = std::min(count, available);

        for (size_t i = 0; i < toWrite; ++i) {
            buffer_[(w + i) & (N - 1)] = data[i];
        }
        writePos_.store(w + toWrite, std::memory_order_release);
        return toWrite;
    }

    /**
     * Consumer: read samples from the ring buffer.
     * Returns the number of samples actually read (may be less than
     * count if the buffer doesn't have enough data).
     */
    size_t read(float* data, size_t count) {
        size_t r = readPos_.load(std::memory_order_relaxed);
        size_t w = writePos_.load(std::memory_order_acquire);
        size_t available = w - r;
        size_t toRead = std::min(count, available);

        for (size_t i = 0; i < toRead; ++i) {
            data[i] = buffer_[(r + i) & (N - 1)];
        }
        readPos_.store(r + toRead, std::memory_order_release);
        return toRead;
    }

    /** Number of samples available for reading. */
    size_t availableToRead() const {
        return writePos_.load(std::memory_order_acquire) -
               readPos_.load(std::memory_order_relaxed);
    }

    /** Reset both pointers to zero. Only call when neither thread is active. */
    void reset() {
        writePos_.store(0, std::memory_order_relaxed);
        readPos_.store(0, std::memory_order_relaxed);
    }

private:
    float buffer_[N];
    std::atomic<size_t> writePos_;
    std::atomic<size_t> readPos_;
};

}  // namespace nightjar
