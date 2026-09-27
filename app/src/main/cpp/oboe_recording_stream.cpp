#include "oboe_recording_stream.h"
#include "atomic_transport.h"
#include "common.h"
#include <algorithm>
#include <cmath>
#include <chrono>
#include <thread>

namespace nightjar {

OboeRecordingStream::OboeRecordingStream(AtomicTransport& transport, AcousticCheck* check)
    : check_(check), transport_(transport) {}

OboeRecordingStream::~OboeRecordingStream() {
    stop();
}

bool OboeRecordingStream::start(const std::string& filePath) {
    return startInput(filePath, false);
}

bool OboeRecordingStream::startCalibration() { return startInput("", true); }

bool OboeRecordingStream::startInput(const std::string& filePath, bool calibration) {
    if (active_.load(std::memory_order_acquire)) {
        LOGE("OboeRecordingStream: already recording");
        return false;
    }

    // An error callback can mark the input inactive while its writer still needs
    // draining. Finish that file before reusing the ring buffer or writer.
    if (stream_) stop();
    calibration_ = calibration;

    // Reset state
    ringBuffer_.reset();
    pipelineHot_.store(false, std::memory_order_relaxed);
    writeGateOpen_.store(false, std::memory_order_relaxed);
    peakAmplitude_.store(0.0f, std::memory_order_relaxed);
    capturedFrames_.store(0, std::memory_order_relaxed);
    captureStartPlaybackFrame_.store(-1, std::memory_order_relaxed);
    captureStartInputFrame_.store(-1, std::memory_order_relaxed);
    captureStartNanos_.store(0, std::memory_order_relaxed);
    captureStartBlockFrames_.store(0, std::memory_order_relaxed);

    // Open WAV file
    if (!calibration_ && !wavWriter_.open(filePath)) {
        return false;
    }

    // Build the Oboe input stream
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input);
    builder.setDeviceId(preferredDevice_.load());
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(kChannelCount);
    builder.setSampleRate(kSampleRate);
    builder.setInputPreset(oboe::InputPreset::Unprocessed);
    builder.setDataCallback(this);
    builder.setErrorCallback(this);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("OboeRecordingStream: failed to open stream: %s",
             oboe::convertToText(result));
        wavWriter_.stopConsuming();
        return false;
    }

    LOGD("OboeRecordingStream: stream opened (sampleRate=%d, framesPerBurst=%d, "
         "bufferCapacity=%d, format=%s, sharingMode=%s)",
         stream_->getSampleRate(),
         stream_->getFramesPerBurst(),
         stream_->getBufferCapacityInFrames(),
         oboe::convertToText(stream_->getFormat()),
         oboe::convertToText(stream_->getSharingMode()));

    evidence_.opened(*stream_);
    // Start the WavWriter consumer thread (it will block until data arrives)
    if (!calibration_) wavWriter_.startConsuming(ringBuffer_);

    // Start the Oboe stream — audio callbacks begin firing
    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("OboeRecordingStream: failed to start stream: %s",
             oboe::convertToText(result));
        wavWriter_.stopConsuming();
        evidence_.closed();
        stream_->close();
        stream_.reset();
        return false;
    }

    active_.store(true, std::memory_order_release);
    LOGD("OboeRecordingStream: recording started → %s", filePath.c_str());
    return true;
}

bool OboeRecordingStream::awaitFirstBuffer(int timeoutMs) {
    auto deadline = std::chrono::steady_clock::now() +
                    std::chrono::milliseconds(timeoutMs);

    while (!pipelineHot_.load(std::memory_order_acquire)) {
        if (std::chrono::steady_clock::now() >= deadline) {
            LOGW("OboeRecordingStream: awaitFirstBuffer timed out after %dms",
                 timeoutMs);
            return false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    LOGD("OboeRecordingStream: pipeline hot");
    return true;
}

void OboeRecordingStream::openWriteGate() {
    writeGateOpen_.store(true, std::memory_order_release);
    LOGD("OboeRecordingStream: write gate opened");
}

int64_t OboeRecordingStream::stop() {
    if (!active_.load(std::memory_order_acquire) && !stream_) {
        return -1;
    }

    active_.store(false, std::memory_order_release);
    evidence_.closed();

    // Stop the Oboe stream (callbacks will stop)
    if (stream_) {
        if (stream_->getState() != oboe::StreamState::Closed) {
            stream_->requestStop();
            stream_->close();
        }
        stream_.reset();
    }

    // Stop the WavWriter (drains remaining ring buffer data, patches header)
    if (calibration_) { peakAmplitude_.store(0); return 0; }
    wavWriter_.stopConsuming();

    int64_t durationMs = wavWriter_.getDurationMs();
    peakAmplitude_.store(0.0f, std::memory_order_relaxed);

    LOGD("OboeRecordingStream: stopped, duration=%lldms", (long long)durationMs);

    if (wavWriter_.getTotalBytesWritten() == 0) {
        return -1;
    }
    return durationMs;
}

int64_t OboeRecordingStream::getInputLatencyMs() const {
    if (!stream_) return -1;
    auto result = stream_->calculateLatencyMillis();
    if (result) {
        return static_cast<int64_t>(result.value());
    }
    return -1;
}

// ── Audio callback (real-time thread — NO allocations, locks, or I/O) ──

oboe::DataCallbackResult OboeRecordingStream::onAudioReady(
        oboe::AudioStream* /* stream */,
        void* audioData,
        int32_t numFrames) {

    auto* floatData = static_cast<const float*>(audioData);
    const auto anchor = evidence_.callback(numFrames, transport_.posFrames.load(std::memory_order_relaxed));
    if (calibration_ && check_) check_->capture(floatData, numFrames, anchor[0], anchor[1]);

    // Compute peak amplitude for UI visualization
    float peak = 0.0f;
    for (int32_t i = 0; i < numFrames; ++i) {
        float abs = std::fabs(floatData[i]);
        if (abs > peak) peak = abs;
    }
    peakAmplitude_.store(peak, std::memory_order_relaxed);

    // Signal that the pipeline is hot (first callback)
    if (!pipelineHot_.load(std::memory_order_relaxed)) {
        pipelineHot_.store(true, std::memory_order_release);
    }

    // Only push to ring buffer when the write gate is open
    if (writeGateOpen_.load(std::memory_order_acquire)) {
        size_t accepted = ringBuffer_.write(floatData, static_cast<size_t>(numFrames));
        evidence_.dropped(numFrames - static_cast<int64_t>(accepted));
        if (accepted > 0) {
            if (capturedFrames_.load(std::memory_order_relaxed) == 0) {
                captureStartInputFrame_.store(anchor[0], std::memory_order_relaxed);
                captureStartBlockFrames_.store(numFrames, std::memory_order_relaxed);
                captureStartNanos_.store(anchor[1], std::memory_order_release);
                captureStartPlaybackFrame_.store(
                    transport_.posFrames.load(std::memory_order_relaxed),
                    std::memory_order_release);
            }
            capturedFrames_.fetch_add(static_cast<int64_t>(accepted), std::memory_order_release);
        }
    }

    return oboe::DataCallbackResult::Continue;
}

void OboeRecordingStream::onErrorAfterClose(
        oboe::AudioStream* /* stream */,
        oboe::Result error) {
    LOGE("OboeRecordingStream: stream error: %s", oboe::convertToText(error));
    // For recording, we don't auto-reopen — the caller should handle the error.
    // Mark as inactive so the UI knows recording has stopped unexpectedly.
    active_.store(false, std::memory_order_release);
    evidence_.closed();
}

}  // namespace nightjar
