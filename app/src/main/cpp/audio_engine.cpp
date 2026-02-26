#include "audio_engine.h"
#include "oboe_recording_stream.h"
#include "common.h"

namespace nightjar {

AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() {
    shutdown();
}

bool AudioEngine::initialize() {
    if (initialized_.load(std::memory_order_acquire)) {
        LOGW("AudioEngine already initialized");
        return true;
    }

    LOGD("AudioEngine initializing (sampleRate=%d, outputChannels=%d)",
         kSampleRate, kOutputChannelCount);

    recordingStream_ = std::make_unique<OboeRecordingStream>();

    initialized_.store(true, std::memory_order_release);
    LOGD("AudioEngine initialized successfully");
    return true;
}

void AudioEngine::shutdown() {
    if (!initialized_.load(std::memory_order_acquire)) return;

    LOGD("AudioEngine shutting down");

    if (recordingStream_ && recordingStream_->isActive()) {
        recordingStream_->stop();
    }
    recordingStream_.reset();

    initialized_.store(false, std::memory_order_release);
    LOGD("AudioEngine shut down");
}

// ── Recording API ──────────────────────────────────────────────────────

bool AudioEngine::startRecording(const char* filePath) {
    if (!initialized_.load(std::memory_order_acquire)) {
        LOGE("AudioEngine: startRecording called but not initialized");
        return false;
    }
    if (!recordingStream_) {
        recordingStream_ = std::make_unique<OboeRecordingStream>();
    }
    return recordingStream_->start(std::string(filePath));
}

bool AudioEngine::awaitFirstBuffer(int timeoutMs) {
    if (!recordingStream_) return false;
    return recordingStream_->awaitFirstBuffer(timeoutMs);
}

void AudioEngine::openWriteGate() {
    if (recordingStream_) {
        recordingStream_->openWriteGate();
    }
}

int64_t AudioEngine::stopRecording() {
    if (!recordingStream_) return -1;
    return recordingStream_->stop();
}

bool AudioEngine::isRecordingActive() const {
    if (!recordingStream_) return false;
    return recordingStream_->isActive();
}

float AudioEngine::getLatestPeakAmplitude() const {
    if (!recordingStream_) return 0.0f;
    return recordingStream_->getLatestPeakAmplitude();
}

int64_t AudioEngine::getRecordedDurationMs() const {
    if (!recordingStream_) return 0;
    return recordingStream_->getRecordedDurationMs();
}

}  // namespace nightjar
