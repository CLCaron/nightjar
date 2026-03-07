#pragma once

#include "common.h"
#include <atomic>
#include <memory>

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
    void synthAllSoundsOff();

    // ── Drum sequencer API ──────────────────────────────────────────
    void updateDrumPattern(int stepsPerBar, int bars, int64_t offsetMs,
                           float volume, bool muted,
                           const int* stepIndices, const int* drumNotes,
                           const float* velocities, int hitCount,
                           const int64_t* clipOffsetsMs = nullptr,
                           int clipCount = 0,
                           int beatsPerBar = 4);
    void setBpm(double bpm);
    void setDrumSequencerEnabled(bool enabled);

    // ── MIDI sequencer API ─────────────────────────────────────────
    void updateMidiTracks(const int* channels, const int* programs,
                          const float* volumes, const bool* muted, int trackCount,
                          const int* trackEventCounts,
                          const int64_t* eventFrames, const int* eventChannels,
                          const int* eventNotes, const int* eventVelocities,
                          int totalEventCount);
    void setMidiSequencerEnabled(bool enabled);

    // ── Hardware latency measurement ──────────────────────────────────
    int64_t getOutputLatencyMs() const;
    int64_t getInputLatencyMs() const;

private:
    /** Recompute totalFrames from max(mixer tracks, drum patterns, MIDI). */
    void recomputeTotalFrames();

    std::atomic<bool> initialized_{false};
    std::atomic<int64_t> drumEndFrames_{0};
    std::atomic<int64_t> midiEndFrames_{0};
    std::unique_ptr<OboeRecordingStream> recordingStream_;
    std::unique_ptr<TrackMixer> mixer_;
    std::unique_ptr<SynthEngine> synthEngine_;
    std::unique_ptr<AtomicTransport> transport_;
    std::unique_ptr<OboePlaybackStream> playbackStream_;
};

}  // namespace nightjar
