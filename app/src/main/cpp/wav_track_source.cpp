#include "wav_track_source.h"
#include "common.h"

#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>

namespace nightjar {

WavTrackSource::WavTrackSource() = default;

WavTrackSource::~WavTrackSource() {
    close();
}

WavTrackSource::WavTrackSource(WavTrackSource&& other) noexcept
    : mappedData_(other.mappedData_),
      mappedSize_(other.mappedSize_),
      pcmData_(other.pcmData_),
      totalFrames_(other.totalFrames_),
      dataOffset_(other.dataOffset_) {
    other.mappedData_ = nullptr;
    other.mappedSize_ = 0;
    other.pcmData_ = nullptr;
    other.totalFrames_ = 0;
    other.dataOffset_ = 0;
}

WavTrackSource& WavTrackSource::operator=(WavTrackSource&& other) noexcept {
    if (this != &other) {
        close();
        mappedData_ = other.mappedData_;
        mappedSize_ = other.mappedSize_;
        pcmData_ = other.pcmData_;
        totalFrames_ = other.totalFrames_;
        dataOffset_ = other.dataOffset_;
        other.mappedData_ = nullptr;
        other.mappedSize_ = 0;
        other.pcmData_ = nullptr;
        other.totalFrames_ = 0;
        other.dataOffset_ = 0;
    }
    return *this;
}

bool WavTrackSource::open(const std::string& filePath) {
    close();

    int fd = ::open(filePath.c_str(), O_RDONLY);
    if (fd < 0) {
        LOGE("WavTrackSource: failed to open %s", filePath.c_str());
        return false;
    }

    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size < 44) {
        LOGE("WavTrackSource: file too small or stat failed: %s", filePath.c_str());
        ::close(fd);
        return false;
    }

    mappedSize_ = static_cast<size_t>(st.st_size);
    mappedData_ = mmap(nullptr, mappedSize_, PROT_READ, MAP_PRIVATE, fd, 0);
    ::close(fd);  // fd can be closed after mmap

    if (mappedData_ == MAP_FAILED) {
        LOGE("WavTrackSource: mmap failed for %s", filePath.c_str());
        mappedData_ = nullptr;
        mappedSize_ = 0;
        return false;
    }

    // Hint the kernel for sequential read access
    madvise(mappedData_, mappedSize_, MADV_SEQUENTIAL);

    // Parse WAV header — find the 'data' chunk
    auto* header = static_cast<const uint8_t*>(mappedData_);

    // Verify RIFF/WAVE
    if (std::memcmp(header, "RIFF", 4) != 0 || std::memcmp(header + 8, "WAVE", 4) != 0) {
        LOGE("WavTrackSource: not a valid WAV file: %s", filePath.c_str());
        close();
        return false;
    }

    // Walk chunks to find 'data'
    int32_t offset = 12; // past RIFF header + WAVE
    int32_t dataSize = 0;
    bool foundData = false;

    while (offset + 8 <= static_cast<int32_t>(mappedSize_)) {
        int32_t chunkSize = static_cast<int32_t>(header[offset + 4]) |
                            (static_cast<int32_t>(header[offset + 5]) << 8) |
                            (static_cast<int32_t>(header[offset + 6]) << 16) |
                            (static_cast<int32_t>(header[offset + 7]) << 24);

        if (std::memcmp(header + offset, "data", 4) == 0) {
            dataOffset_ = offset + 8;
            dataSize = chunkSize;
            foundData = true;
            break;
        }

        offset += 8 + chunkSize;
        // Chunks are word-aligned
        if (chunkSize % 2 != 0) offset++;
    }

    if (!foundData) {
        LOGE("WavTrackSource: no 'data' chunk found in %s", filePath.c_str());
        close();
        return false;
    }

    // Clamp dataSize to actual file bounds
    if (dataOffset_ + dataSize > static_cast<int32_t>(mappedSize_)) {
        dataSize = static_cast<int32_t>(mappedSize_) - dataOffset_;
    }

    pcmData_ = reinterpret_cast<const int16_t*>(header + dataOffset_);
    totalFrames_ = dataSize / (kChannelCount * kBytesPerSample);

    LOGD("WavTrackSource: opened %s (dataOffset=%d, dataSize=%d, frames=%lld)",
         filePath.c_str(), dataOffset_, dataSize, (long long)totalFrames_);
    return true;
}

void WavTrackSource::close() {
    if (mappedData_ && mappedData_ != MAP_FAILED) {
        munmap(mappedData_, mappedSize_);
    }
    mappedData_ = nullptr;
    mappedSize_ = 0;
    pcmData_ = nullptr;
    totalFrames_ = 0;
    dataOffset_ = 0;
}

int64_t WavTrackSource::readFrames(float* output, int64_t frameOffset, int64_t numFrames) const {
    if (!pcmData_ || frameOffset >= totalFrames_) return 0;

    int64_t available = totalFrames_ - frameOffset;
    int64_t toRead = (numFrames < available) ? numFrames : available;

    const int16_t* src = pcmData_ + (frameOffset * kChannelCount);
    constexpr float kScale = 1.0f / 32768.0f;

    for (int64_t i = 0; i < toRead; ++i) {
        output[i] = static_cast<float>(src[i]) * kScale;
    }

    return toRead;
}

}  // namespace nightjar
