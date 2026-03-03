#pragma once

#include <cstdint>
#include <android/log.h>

#define LOG_TAG "NightjarAudio"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace nightjar {

constexpr int32_t kSampleRate = 44100;
constexpr int32_t kChannelCount = 1;         // mono recording
constexpr int32_t kOutputChannelCount = 2;   // stereo output (mono tracks panned center)
constexpr int32_t kBitsPerSample = 16;
constexpr int32_t kBytesPerSample = kBitsPerSample / 8;

/** Convert milliseconds to sample frames at kSampleRate. */
inline int64_t msToFrames(int64_t ms) {
    return (ms * kSampleRate) / 1000;
}

/** Convert sample frames to milliseconds at kSampleRate. */
inline int64_t framesToMs(int64_t frames) {
    return (frames * 1000) / kSampleRate;
}

}  // namespace nightjar
