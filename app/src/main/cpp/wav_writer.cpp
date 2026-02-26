#include "wav_writer.h"
#include "audio_engine.h"
#include <algorithm>
#include <chrono>
#include <cstring>

namespace nightjar {

// Stack-allocated buffers used in the writer loop — no heap allocation.
static constexpr size_t kWriteChunkSamples = 4096;

WavWriter::WavWriter() = default;

WavWriter::~WavWriter() {
    stopConsuming();
}

bool WavWriter::open(const std::string& filePath) {
    filePath_ = filePath;
    totalBytesWritten_.store(0, std::memory_order_relaxed);

    file_ = fopen(filePath.c_str(), "wb");
    if (!file_) {
        LOGE("WavWriter: failed to open %s", filePath.c_str());
        return false;
    }

    writeWavHeader();
    LOGD("WavWriter: opened %s", filePath.c_str());
    return true;
}

void WavWriter::startConsuming(SpscRingBuffer<kRingBufferCapacity>& ringBuffer) {
    if (!file_) {
        LOGE("WavWriter: startConsuming called but no file is open");
        return;
    }
    running_.store(true, std::memory_order_release);
    writerThread_ = std::thread(&WavWriter::writerLoop, this, std::ref(ringBuffer));
}

void WavWriter::stopConsuming() {
    running_.store(false, std::memory_order_release);
    if (writerThread_.joinable()) {
        writerThread_.join();
    }
    if (file_) {
        patchWavHeader();
        fclose(file_);
        file_ = nullptr;
        LOGD("WavWriter: closed, wrote %lld bytes (%lld ms)",
             (long long)getTotalBytesWritten(), (long long)getDurationMs());
    }
}

void WavWriter::writerLoop(SpscRingBuffer<kRingBufferCapacity>& ringBuffer) {
    float readBuf[kWriteChunkSamples];
    int16_t writeBuf[kWriteChunkSamples];

    while (running_.load(std::memory_order_relaxed)) {
        size_t read = ringBuffer.read(readBuf, kWriteChunkSamples);
        if (read > 0) {
            // Convert float32 [-1.0, 1.0] → int16 [-32767, 32767]
            for (size_t i = 0; i < read; ++i) {
                float clamped = std::max(-1.0f, std::min(1.0f, readBuf[i]));
                writeBuf[i] = static_cast<int16_t>(clamped * 32767.0f);
            }
            fwrite(writeBuf, sizeof(int16_t), read, file_);
            totalBytesWritten_.fetch_add(
                static_cast<int64_t>(read * sizeof(int16_t)),
                std::memory_order_relaxed);
        } else {
            // No data available — sleep briefly to avoid spinning.
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    }

    // Drain any remaining samples after stop signal.
    drainRingBuffer(ringBuffer);
}

void WavWriter::drainRingBuffer(SpscRingBuffer<kRingBufferCapacity>& ringBuffer) {
    float readBuf[kWriteChunkSamples];
    int16_t writeBuf[kWriteChunkSamples];

    while (ringBuffer.availableToRead() > 0) {
        size_t read = ringBuffer.read(readBuf, kWriteChunkSamples);
        if (read == 0) break;
        for (size_t i = 0; i < read; ++i) {
            float clamped = std::max(-1.0f, std::min(1.0f, readBuf[i]));
            writeBuf[i] = static_cast<int16_t>(clamped * 32767.0f);
        }
        fwrite(writeBuf, sizeof(int16_t), read, file_);
        totalBytesWritten_.fetch_add(
            static_cast<int64_t>(read * sizeof(int16_t)),
            std::memory_order_relaxed);
    }
}

void WavWriter::writeWavHeader() {
    // Write a 44-byte placeholder header. Sizes will be patched on close.
    uint8_t header[44];
    std::memset(header, 0, sizeof(header));

    // RIFF chunk
    header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
    // Bytes 4-7: file size - 8 (patched later)
    header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';

    // fmt sub-chunk
    header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
    header[16] = 16;  // sub-chunk size (16 for PCM)
    header[20] = 1;   // audio format (1 = PCM)
    header[22] = static_cast<uint8_t>(kChannelCount);

    // Sample rate (little-endian)
    header[24] = static_cast<uint8_t>(kSampleRate & 0xFF);
    header[25] = static_cast<uint8_t>((kSampleRate >> 8) & 0xFF);
    header[26] = static_cast<uint8_t>((kSampleRate >> 16) & 0xFF);
    header[27] = static_cast<uint8_t>((kSampleRate >> 24) & 0xFF);

    // Byte rate = sampleRate * channels * bytesPerSample
    int32_t byteRate = kSampleRate * kChannelCount * kBytesPerSample;
    header[28] = static_cast<uint8_t>(byteRate & 0xFF);
    header[29] = static_cast<uint8_t>((byteRate >> 8) & 0xFF);
    header[30] = static_cast<uint8_t>((byteRate >> 16) & 0xFF);
    header[31] = static_cast<uint8_t>((byteRate >> 24) & 0xFF);

    // Block align = channels * bytesPerSample
    int16_t blockAlign = kChannelCount * kBytesPerSample;
    header[32] = static_cast<uint8_t>(blockAlign & 0xFF);
    header[33] = static_cast<uint8_t>((blockAlign >> 8) & 0xFF);

    // Bits per sample
    header[34] = static_cast<uint8_t>(kBitsPerSample);

    // data sub-chunk
    header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
    // Bytes 40-43: data size (patched later)

    fwrite(header, 1, 44, file_);
}

void WavWriter::patchWavHeader() {
    if (!file_) return;

    int64_t dataSize = getTotalBytesWritten();
    int64_t fileSize = dataSize + 44;

    // Helper to write a 32-bit LE integer at an offset.
    auto writeInt32LE = [&](long offset, int32_t value) {
        fseek(file_, offset, SEEK_SET);
        uint8_t buf[4];
        buf[0] = static_cast<uint8_t>(value & 0xFF);
        buf[1] = static_cast<uint8_t>((value >> 8) & 0xFF);
        buf[2] = static_cast<uint8_t>((value >> 16) & 0xFF);
        buf[3] = static_cast<uint8_t>((value >> 24) & 0xFF);
        fwrite(buf, 1, 4, file_);
    };

    // RIFF chunk size = fileSize - 8
    writeInt32LE(4, static_cast<int32_t>(fileSize - 8));
    // data sub-chunk size
    writeInt32LE(40, static_cast<int32_t>(dataSize));

    fflush(file_);
    LOGD("WavWriter: patched header (dataSize=%lld, fileSize=%lld)",
         (long long)dataSize, (long long)fileSize);
}

}  // namespace nightjar
