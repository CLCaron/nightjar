#pragma once

#include "audio_engine.h"
#include <cstdint>
#include <string>

namespace nightjar {

/**
 * Memory-mapped WAV file reader for playback.
 *
 * Uses mmap() to map the WAV file into the process address space.
 * The audio callback reads PCM samples directly from the mapped region —
 * NO disk I/O, NO syscalls, NO allocations at read time.
 *
 * The OS pages data in and out as needed. madvise(SEQUENTIAL) hints
 * the kernel to prefetch ahead for smooth sequential playback.
 *
 * Supports 16-bit PCM mono WAV files at any sample rate (though we
 * only generate 44.1kHz in Nightjar).
 */
class WavTrackSource {
public:
    WavTrackSource();
    ~WavTrackSource();

    // Non-copyable, movable
    WavTrackSource(const WavTrackSource&) = delete;
    WavTrackSource& operator=(const WavTrackSource&) = delete;
    WavTrackSource(WavTrackSource&& other) noexcept;
    WavTrackSource& operator=(WavTrackSource&& other) noexcept;

    /**
     * Open and mmap a WAV file. Parses the 44-byte header to locate
     * the PCM data region.
     * @return true on success.
     */
    bool open(const std::string& filePath);

    /** Unmap the file. Safe to call if not opened. */
    void close();

    /** Returns true if a file is currently mapped. */
    bool isOpen() const { return pcmData_ != nullptr; }

    /** Total number of sample frames in the file. */
    int64_t totalFrames() const { return totalFrames_; }

    /**
     * Read frames from the mapped file, converting int16 → float32.
     *
     * @param output Destination buffer (float).
     * @param frameOffset Offset in frames from the start of PCM data.
     * @param numFrames Number of frames to read.
     * @return Number of frames actually read (may be less at EOF).
     *
     * This method does NO syscalls — it's safe for the audio callback.
     */
    int64_t readFrames(float* output, int64_t frameOffset, int64_t numFrames) const;

private:
    void* mappedData_ = nullptr;       // mmap'd file region
    size_t mappedSize_ = 0;            // total mmap size
    const int16_t* pcmData_ = nullptr; // pointer to first PCM sample (past header)
    int64_t totalFrames_ = 0;          // total sample frames
    int32_t dataOffset_ = 0;           // byte offset of 'data' chunk payload
};

}  // namespace nightjar
