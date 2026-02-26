#pragma once

#include "audio_engine.h"
#include "spsc_ring_buffer.h"
#include "wav_writer.h"
#include <oboe/Oboe.h>
#include <atomic>
#include <string>

namespace nightjar {

/**
 * Oboe input stream for recording.
 *
 * Implements a three-phase protocol matching the old WavRecorder:
 *   1. start(filePath) — opens the Oboe input stream and WAV file,
 *      starts audio flowing. Buffers are read but NOT written to disk.
 *   2. awaitFirstBuffer(timeoutMs) — blocks until at least one audio
 *      callback has fired, confirming the hardware pipeline is hot.
 *   3. openWriteGate() — from this moment the ring buffer consumer
 *      (WavWriter) starts writing captured audio to the WAV file.
 *
 * The audio callback computes peak amplitude (atomic) and pushes
 * float32 samples into the SPSC ring buffer. The WavWriter consumer
 * thread converts to int16 and writes to disk — no file I/O in the
 * callback.
 */
class OboeRecordingStream : public oboe::AudioStreamDataCallback,
                            public oboe::AudioStreamErrorCallback {
public:
    OboeRecordingStream();
    ~OboeRecordingStream();

    /**
     * Open Oboe input stream + WAV file, start the stream.
     * Samples flow into the ring buffer but the writer does NOT write
     * to disk until openWriteGate() is called.
     */
    bool start(const std::string& filePath);

    /**
     * Block until the first audio callback has fired, or timeout.
     * Returns true if the pipeline is hot, false on timeout.
     */
    bool awaitFirstBuffer(int timeoutMs);

    /**
     * Open the write gate — the WavWriter consumer thread begins
     * writing captured audio to the WAV file.
     */
    void openWriteGate();

    /**
     * Stop recording: close the Oboe stream, stop the WavWriter,
     * patch the WAV header.
     * Returns the duration of captured audio in milliseconds,
     * or -1 if nothing was written.
     */
    int64_t stop();

    /** Returns true if recording is in progress. */
    bool isActive() const {
        return active_.load(std::memory_order_acquire);
    }

    /** Peak amplitude of the most recent audio callback, normalised to 0–1. */
    float getLatestPeakAmplitude() const {
        return peakAmplitude_.load(std::memory_order_relaxed);
    }

    /** Duration of audio written to the WAV file so far, in ms. */
    int64_t getRecordedDurationMs() const {
        return wavWriter_.getDurationMs();
    }

    // ── Oboe callbacks ──────────────────────────────────────────────────

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

    void onErrorAfterClose(
        oboe::AudioStream* stream,
        oboe::Result error) override;

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    SpscRingBuffer<kRingBufferCapacity> ringBuffer_;
    WavWriter wavWriter_;

    std::atomic<bool> active_{false};
    std::atomic<bool> pipelineHot_{false};
    std::atomic<bool> writeGateOpen_{false};
    std::atomic<float> peakAmplitude_{0.0f};
};

}  // namespace nightjar
