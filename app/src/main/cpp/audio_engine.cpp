#include "audio_engine.h"
#include "oboe_recording_stream.h"
#include "oboe_playback_stream.h"
#include "track_mixer.h"
#include "atomic_transport.h"
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
    transport_ = std::make_unique<AtomicTransport>();
    mixer_ = std::make_unique<TrackMixer>();
    playbackStream_ = std::make_unique<OboePlaybackStream>(*mixer_, *transport_);

    // Start the output stream — it sits idle (outputting silence) until play()
    if (!playbackStream_->start()) {
        LOGE("AudioEngine: failed to start playback stream");
        // Non-fatal — playback won't work but recording still can
    }

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

    if (playbackStream_) {
        playbackStream_->stop();
    }

    mixer_.reset();
    transport_.reset();
    playbackStream_.reset();
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

// ── Playback API ───────────────────────────────────────────────────────

bool AudioEngine::addTrack(int trackId, const char* filePath,
                           int64_t durationMs, int64_t offsetMs,
                           int64_t trimStartMs, int64_t trimEndMs,
                           float volume, bool muted) {
    if (!mixer_) return false;
    bool ok = mixer_->addTrack(trackId, std::string(filePath),
                               durationMs, offsetMs, trimStartMs, trimEndMs,
                               volume, muted);
    if (ok && transport_) {
        transport_->totalFrames.store(mixer_->computeTotalFrames(),
                                      std::memory_order_relaxed);
    }
    return ok;
}

void AudioEngine::removeTrack(int trackId) {
    if (!mixer_) return;
    mixer_->removeTrack(trackId);
    if (transport_) {
        transport_->totalFrames.store(mixer_->computeTotalFrames(),
                                      std::memory_order_relaxed);
    }
}

void AudioEngine::removeAllTracks() {
    if (!mixer_) return;
    mixer_->removeAllTracks();
    if (transport_) {
        transport_->totalFrames.store(0, std::memory_order_relaxed);
        transport_->posFrames.store(0, std::memory_order_relaxed);
        transport_->playing.store(false, std::memory_order_relaxed);
    }
}

void AudioEngine::play() {
    if (!transport_) return;

    int64_t pos = transport_->posFrames.load(std::memory_order_relaxed);
    int64_t total = transport_->totalFrames.load(std::memory_order_relaxed);

    // If at the end, restart from loop start or 0
    if (pos >= total) {
        int64_t loopStart = transport_->loopStartFrames.load(std::memory_order_relaxed);
        pos = (loopStart >= 0) ? loopStart : 0;
        transport_->posFrames.store(pos, std::memory_order_relaxed);
    }

    transport_->playing.store(true, std::memory_order_release);
    LOGD("AudioEngine: play (pos=%lldms)", (long long)framesToMs(pos));
}

void AudioEngine::pause() {
    if (!transport_) return;
    transport_->playing.store(false, std::memory_order_release);
    LOGD("AudioEngine: pause (pos=%lldms)",
         (long long)framesToMs(transport_->posFrames.load(std::memory_order_relaxed)));
}

void AudioEngine::seekTo(int64_t positionMs) {
    if (!transport_) return;
    int64_t frames = msToFrames(positionMs);
    int64_t total = transport_->totalFrames.load(std::memory_order_relaxed);
    frames = std::max((int64_t)0, std::min(frames, total));
    transport_->posFrames.store(frames, std::memory_order_relaxed);
}

bool AudioEngine::isPlaying() const {
    if (!transport_) return false;
    return transport_->playing.load(std::memory_order_acquire);
}

int64_t AudioEngine::getPositionMs() const {
    if (!transport_) return 0;
    return framesToMs(transport_->posFrames.load(std::memory_order_relaxed));
}

int64_t AudioEngine::getTotalDurationMs() const {
    if (!transport_) return 0;
    return framesToMs(transport_->totalFrames.load(std::memory_order_relaxed));
}

// ── Per-track controls ─────────────────────────────────────────────────

void AudioEngine::setTrackVolume(int trackId, float volume) {
    if (mixer_) mixer_->setTrackVolume(trackId, volume);
}

void AudioEngine::setTrackMuted(int trackId, bool muted) {
    if (mixer_) mixer_->setTrackMuted(trackId, muted);
}

// ── Loop ───────────────────────────────────────────────────────────────

void AudioEngine::setLoopRegion(int64_t startMs, int64_t endMs) {
    if (!transport_) return;
    transport_->loopStartFrames.store(msToFrames(startMs), std::memory_order_relaxed);
    transport_->loopEndFrames.store(msToFrames(endMs), std::memory_order_relaxed);
    LOGD("AudioEngine: setLoopRegion %lld-%lldms", (long long)startMs, (long long)endMs);
}

void AudioEngine::clearLoopRegion() {
    if (!transport_) return;
    transport_->loopStartFrames.store(-1, std::memory_order_relaxed);
    transport_->loopEndFrames.store(-1, std::memory_order_relaxed);
    LOGD("AudioEngine: clearLoopRegion");
}

// ── Overdub support ────────────────────────────────────────────────────

void AudioEngine::setRecording(bool active) {
    if (transport_) {
        transport_->recording.store(active, std::memory_order_relaxed);
    }
}

// ── Hardware latency measurement ────────────────────────────────────

int64_t AudioEngine::getOutputLatencyMs() const {
    if (!playbackStream_) return -1;
    return playbackStream_->getOutputLatencyMs();
}

int64_t AudioEngine::getInputLatencyMs() const {
    if (!recordingStream_) return -1;
    return recordingStream_->getInputLatencyMs();
}

}  // namespace nightjar
