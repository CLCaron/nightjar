#pragma once

#include <atomic>
#include <memory>
#include <android/log.h>

#define LOG_TAG "NightjarAudio"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace nightjar {

class OboeRecordingStream;

/**
 * Top-level audio engine managing Oboe input (recording) and output (playback) streams.
 *
 * Phase 1: skeleton with init/shutdown only.
 * Phase 2: recording via OboeRecordingStream + WavWriter.
 * Phase 3: playback via OboePlaybackStream + TrackMixer.
 * Phase 4: hardware timestamps for latency measurement.
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

    /**
     * Start recording to the given WAV file path.
     * Opens the Oboe input stream and WAV writer, begins capturing audio.
     * Write gate is closed — call openWriteGate() to begin writing to disk.
     */
    bool startRecording(const char* filePath);

    /**
     * Block until the recording stream's first audio callback has fired.
     * Returns true if the pipeline is hot, false on timeout.
     */
    bool awaitFirstBuffer(int timeoutMs);

    /** Open the write gate — captured audio is written to the WAV file. */
    void openWriteGate();

    /**
     * Stop recording, patch WAV header, close the file.
     * Returns duration in ms, or -1 if nothing was captured.
     */
    int64_t stopRecording();

    /** Returns true if a recording is in progress. */
    bool isRecordingActive() const;

    /** Peak amplitude of the most recent audio callback, 0–1. */
    float getLatestPeakAmplitude() const;

    /** Duration of audio written so far, in ms. */
    int64_t getRecordedDurationMs() const;

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
    std::unique_ptr<OboeRecordingStream> recordingStream_;
};

}  // namespace nightjar
