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
class OboePlaybackStream;
class TrackMixer;
class SynthEngine;
struct AtomicTransport;

/**
 * Top-level audio engine managing Oboe input (recording) and output (playback) streams.
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

    // ── Recording API ───────────────────────────────────────────────────
    bool startRecording(const char* filePath);
    bool awaitFirstBuffer(int timeoutMs);
    void openWriteGate();
    int64_t stopRecording();
    bool isRecordingActive() const;
    float getLatestPeakAmplitude() const;
    int64_t getRecordedDurationMs() const;

    // ── Playback API ────────────────────────────────────────────────────
    bool addTrack(int trackId, const char* filePath,
                  int64_t durationMs, int64_t offsetMs,
                  int64_t trimStartMs, int64_t trimEndMs,
                  float volume, bool muted);
    void removeTrack(int trackId);
    void removeAllTracks();
    void play();
    void pause();
    void seekTo(int64_t positionMs);
    bool isPlaying() const;
    int64_t getPositionMs() const;
    int64_t getTotalDurationMs() const;

    // ── Per-track controls ──────────────────────────────────────────────
    void setTrackVolume(int trackId, float volume);
    void setTrackMuted(int trackId, bool muted);

    // ── Loop ────────────────────────────────────────────────────────────
    void setLoopRegion(int64_t startMs, int64_t endMs);
    void clearLoopRegion();

    // ── Overdub support ─────────────────────────────────────────────────
    void setRecording(bool active);

    // ── Loop reset tracking ──────────────────────────────────────────────
    int64_t getLoopResetCount() const;

    // ── Synth API ─────────────────────────────────────────────────────
    bool loadSoundFont(const char* path);
    void synthNoteOn(int channel, int note, int velocity);
    void synthNoteOff(int channel, int note);
    void setSynthVolume(float volume);

    // ── Hardware latency measurement ──────────────────────────────────
    int64_t getOutputLatencyMs() const;
    int64_t getInputLatencyMs() const;

private:
    std::atomic<bool> initialized_{false};
    std::unique_ptr<OboeRecordingStream> recordingStream_;
    std::unique_ptr<TrackMixer> mixer_;
    std::unique_ptr<SynthEngine> synthEngine_;
    std::unique_ptr<AtomicTransport> transport_;
    std::unique_ptr<OboePlaybackStream> playbackStream_;
};

}  // namespace nightjar
