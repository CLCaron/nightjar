#pragma once

#include <atomic>
#include <android/log.h>

#define LOG_TAG "NightjarAudio"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace nightjar {

/**
 * Top-level audio engine managing Oboe input (recording) and output (playback) streams.
 *
 * Phase 1: skeleton with init/shutdown only.
 * Phase 2: recording via OboeRecordingStream + WavWriter.
 * Phase 3: playback via OboePlaybackStream + TrackMixer.
 * Phase 4: hardware timestamps for latency measurement.
 * Phase 5: loop region support, cleanup.
 */
class AudioEngine {
public:
    AudioEngine();
    ~AudioEngine();

    /** Initialize the engine. Call once from Application.onCreate(). */
    bool initialize();

    /** Shut down the engine. Call from Application.onTerminate(). */
    void shutdown();

    /** Returns true if the engine has been initialized. */
    bool isInitialized() const {
        return initialized_.load(std::memory_order_acquire);
    }

    // ── Recording API (Phase 2) ─────────────────────────────────────────
    // bool startRecording(const char* filePath);
    // void openWriteGate();
    // void stopRecording();
    // bool isRecordingActive() const;
    // float getLatestPeakAmplitude() const;
    // int64_t getRecordedDurationMs() const;
    // bool awaitFirstBuffer(int timeoutMs);

    // ── Playback API (Phase 3) ──────────────────────────────────────────
    // bool addTrack(...);
    // void removeTrack(int trackId);
    // void removeAllTracks();
    // void play();
    // void pause();
    // void seekTo(int64_t positionMs);
    // bool isPlaying() const;
    // int64_t getPositionMs() const;
    // int64_t getTotalDurationMs() const;

    // ── Per-track controls (Phase 3) ────────────────────────────────────
    // void setTrackVolume(int trackId, float volume);
    // void setTrackMuted(int trackId, bool muted);

    // ── Loop (Phase 5) ──────────────────────────────────────────────────
    // void setLoopRegion(int64_t startMs, int64_t endMs);
    // void clearLoopRegion();

    // ── Sync (Phase 4) ──────────────────────────────────────────────────
    // int64_t getOutputLatencyMs() const;
    // int64_t getInputLatencyMs() const;

private:
    std::atomic<bool> initialized_{false};
};

}  // namespace nightjar
