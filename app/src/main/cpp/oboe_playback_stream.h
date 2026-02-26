#pragma once

#include "audio_engine.h"
#include "track_mixer.h"
#include "atomic_transport.h"
#include <oboe/Oboe.h>

namespace nightjar {

/**
 * Oboe output stream for multi-track playback.
 *
 * The onAudioReady callback reads the transport state, calls
 * TrackMixer::renderFrames(), advances the position, and handles
 * loop boundaries and end-of-timeline.
 *
 * Stream config: stereo, float, low-latency, 44.1kHz.
 * Auto-reopens on device change (headphone unplug).
 */
class OboePlaybackStream : public oboe::AudioStreamDataCallback,
                           public oboe::AudioStreamErrorCallback {
public:
    OboePlaybackStream(TrackMixer& mixer, AtomicTransport& transport);
    ~OboePlaybackStream();

    /** Open and start the output stream. */
    bool start();

    /** Stop and close the output stream. */
    void stop();

    /** Returns true if the stream is open and started. */
    bool isStreamOpen() const { return stream_ != nullptr; }

    /**
     * Returns the output pipeline latency in ms via hardware timestamps.
     * Uses AAudio's getTimestamp() under the hood (API 26+).
     * Returns -1 if timestamps are not available (OpenSL ES fallback
     * on API 24–25, or stream not open).
     */
    int64_t getOutputLatencyMs() const;

    // ── Oboe callbacks ──────────────────────────────────────────────────

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

    void onErrorAfterClose(
        oboe::AudioStream* stream,
        oboe::Result error) override;

private:
    bool openStream();

    TrackMixer& mixer_;
    AtomicTransport& transport_;
    std::shared_ptr<oboe::AudioStream> stream_;
};

}  // namespace nightjar
