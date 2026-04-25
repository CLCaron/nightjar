#include "oboe_playback_stream.h"
#include "common.h"
#include <cmath>
#include <cstring>

namespace nightjar {

OboePlaybackStream::OboePlaybackStream(TrackMixer& mixer, AtomicTransport& transport,
                                       SynthEngine* synth)
    : mixer_(mixer), transport_(transport), synth_(synth) {}

OboePlaybackStream::~OboePlaybackStream() {
    stop();
}

bool OboePlaybackStream::start() {
    if (stream_) return true;
    return openStream();
}

void OboePlaybackStream::stop() {
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
        LOGD("OboePlaybackStream: stopped");
    }
}

bool OboePlaybackStream::openStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(kOutputChannelCount);
    builder.setSampleRate(kSampleRate);
    builder.setUsage(oboe::Usage::Media);
    builder.setDataCallback(this);
    builder.setErrorCallback(this);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("OboePlaybackStream: failed to open: %s", oboe::convertToText(result));
        return false;
    }

    LOGD("OboePlaybackStream: opened (sampleRate=%d, framesPerBurst=%d, "
         "bufferCapacity=%d, channelCount=%d, sharingMode=%s)",
         stream_->getSampleRate(),
         stream_->getFramesPerBurst(),
         stream_->getBufferCapacityInFrames(),
         stream_->getChannelCount(),
         oboe::convertToText(stream_->getSharingMode()));

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("OboePlaybackStream: failed to start: %s", oboe::convertToText(result));
        stream_->close();
        stream_.reset();
        return false;
    }

    LOGD("OboePlaybackStream: started");
    return true;
}

int64_t OboePlaybackStream::getOutputLatencyMs() const {
    if (!stream_) return -1;
    auto result = stream_->calculateLatencyMillis();
    if (result) {
        return static_cast<int64_t>(result.value());
    }
    return -1;
}

// ── Audio callback (real-time thread) ──────────────────────────────────

oboe::DataCallbackResult OboePlaybackStream::onAudioReady(
        oboe::AudioStream* /* stream */,
        void* audioData,
        int32_t numFrames) {

    auto* output = static_cast<float*>(audioData);

    if (!transport_.playing.load(std::memory_order_acquire)) {
        // Paused: skip the timeline-driven track mixer and position
        // advance, but still mix in synth audio from the ring buffer
        // so direct synth API calls (preview noteOn from the piano
        // roll) reach the speaker. Without this the playback callback
        // would output silence regardless of what the synth produced.
        std::memset(output, 0,
                    static_cast<size_t>(numFrames) * kOutputChannelCount * sizeof(float));
        if (synth_ && synth_->isRunning()) {
            synth_->readFrames(output, numFrames);
            // Soft-clip the synth output for symmetry with the playing
            // branch below; preview velocities are typically below 1.0
            // so this is rarely active, but keeps the signal path
            // consistent.
            int32_t totalSamples = numFrames * kOutputChannelCount;
            for (int32_t i = 0; i < totalSamples; ++i) {
                output[i] = std::tanh(output[i]);
            }
        }
        return oboe::DataCallbackResult::Continue;
    }

    int64_t pos = transport_.posFrames.load(std::memory_order_relaxed);
    int64_t total = transport_.totalFrames.load(std::memory_order_relaxed);

    // Render WAV track audio (zeros buffer first, then sums all tracks)
    mixer_.renderFrames(output, numFrames, pos);

    // Sum synth audio from the render thread's ring buffer
    if (synth_ && synth_->isRunning()) {
        synth_->readFrames(output, numFrames);
    }

    // Soft-clip at the final mix point (after all sources are summed).
    // tanh provides smooth saturation that prevents harsh digital clipping
    // when many tracks + synth overlap.
    int32_t totalSamples = numFrames * kOutputChannelCount;
    for (int32_t i = 0; i < totalSamples; ++i) {
        output[i] = std::tanh(output[i]);
    }

    // Advance position
    pos += numFrames;

    // Loop check
    int64_t loopEnd = transport_.loopEndFrames.load(std::memory_order_relaxed);
    int64_t loopStart = transport_.loopStartFrames.load(std::memory_order_relaxed);
    if (loopStart >= 0 && loopEnd > loopStart && pos >= loopEnd) {
        pos = loopStart;
        transport_.loopResetCount.fetch_add(1, std::memory_order_release);
        // Loop detection for synth audio is handled in the render thread
        // (SynthEngine wraps renderPos_ at loop boundaries seamlessly)
    }

    // End-of-timeline check
    bool recording = transport_.recording.load(std::memory_order_relaxed);
    if (!recording && total > 0 && pos >= total) {
        // Playback finished — stop and reset to 0
        transport_.playing.store(false, std::memory_order_release);
        transport_.posFrames.store(0, std::memory_order_relaxed);
    } else {
        transport_.posFrames.store(pos, std::memory_order_relaxed);
    }

    return oboe::DataCallbackResult::Continue;
}

void OboePlaybackStream::onErrorAfterClose(
        oboe::AudioStream* /* stream */,
        oboe::Result error) {
    LOGW("OboePlaybackStream: error after close: %s — reopening",
         oboe::convertToText(error));
    // Auto-reopen on device change (headphone unplug, BT disconnect)
    stream_.reset();
    openStream();
}

}  // namespace nightjar
