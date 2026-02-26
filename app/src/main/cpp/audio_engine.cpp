#include "audio_engine.h"
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

    initialized_.store(true, std::memory_order_release);
    LOGD("AudioEngine initialized successfully");
    return true;
}

void AudioEngine::shutdown() {
    if (!initialized_.load(std::memory_order_acquire)) return;

    LOGD("AudioEngine shutting down");
    initialized_.store(false, std::memory_order_release);
    LOGD("AudioEngine shut down");
}

}  // namespace nightjar
