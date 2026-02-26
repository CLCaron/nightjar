#include <jni.h>
#include "audio_engine.h"
#include <memory>

/**
 * JNI bridge between Kotlin OboeAudioEngine and C++ AudioEngine.
 *
 * Uses a file-scoped singleton. The engine lifecycle is:
 *   nativeInit()     — called from NightjarApplication.onCreate()
 *   nativeShutdown() — called from NightjarApplication.onTerminate()
 *
 * All other methods delegate to the singleton.
 */

static std::unique_ptr<nightjar::AudioEngine> sEngine;

extern "C" {

// ── Lifecycle ───────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeInit(JNIEnv* /* env */, jobject /* thiz */) {
    if (sEngine) {
        return sEngine->isInitialized() ? JNI_TRUE : JNI_FALSE;
    }
    sEngine = std::make_unique<nightjar::AudioEngine>();
    return sEngine->initialize() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeShutdown(JNIEnv* /* env */, jobject /* thiz */) {
    if (sEngine) {
        sEngine->shutdown();
        sEngine.reset();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeIsInitialized(JNIEnv* /* env */, jobject /* thiz */) {
    return (sEngine && sEngine->isInitialized()) ? JNI_TRUE : JNI_FALSE;
}

// ── Recording ──────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeStartRecording(
        JNIEnv* env, jobject /* thiz */, jstring filePath) {
    if (!sEngine) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    bool ok = sEngine->startRecording(path);
    env->ReleaseStringUTFChars(filePath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeAwaitFirstBuffer(
        JNIEnv* /* env */, jobject /* thiz */, jint timeoutMs) {
    if (!sEngine) return JNI_FALSE;
    return sEngine->awaitFirstBuffer(static_cast<int>(timeoutMs)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeOpenWriteGate(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (sEngine) sEngine->openWriteGate();
}

JNIEXPORT jlong JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeStopRecording(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (!sEngine) return -1;
    return static_cast<jlong>(sEngine->stopRecording());
}

JNIEXPORT jboolean JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeIsRecordingActive(
        JNIEnv* /* env */, jobject /* thiz */) {
    return (sEngine && sEngine->isRecordingActive()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeGetLatestPeakAmplitude(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (!sEngine) return 0.0f;
    return static_cast<jfloat>(sEngine->getLatestPeakAmplitude());
}

JNIEXPORT jlong JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeGetRecordedDurationMs(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (!sEngine) return 0;
    return static_cast<jlong>(sEngine->getRecordedDurationMs());
}

// ── Playback ───────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeAddTrack(
        JNIEnv* env, jobject /* thiz */, jint trackId, jstring filePath,
        jlong durationMs, jlong offsetMs, jlong trimStartMs, jlong trimEndMs,
        jfloat volume, jboolean muted) {
    if (!sEngine) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    bool ok = sEngine->addTrack(
        static_cast<int>(trackId), path,
        static_cast<int64_t>(durationMs), static_cast<int64_t>(offsetMs),
        static_cast<int64_t>(trimStartMs), static_cast<int64_t>(trimEndMs),
        static_cast<float>(volume), static_cast<bool>(muted));
    env->ReleaseStringUTFChars(filePath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeRemoveTrack(
        JNIEnv* /* env */, jobject /* thiz */, jint trackId) {
    if (sEngine) sEngine->removeTrack(static_cast<int>(trackId));
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeRemoveAllTracks(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (sEngine) sEngine->removeAllTracks();
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativePlay(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (sEngine) sEngine->play();
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativePause(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (sEngine) sEngine->pause();
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSeekTo(
        JNIEnv* /* env */, jobject /* thiz */, jlong positionMs) {
    if (sEngine) sEngine->seekTo(static_cast<int64_t>(positionMs));
}

JNIEXPORT jboolean JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeIsPlaying(
        JNIEnv* /* env */, jobject /* thiz */) {
    return (sEngine && sEngine->isPlaying()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeGetPositionMs(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (!sEngine) return 0;
    return static_cast<jlong>(sEngine->getPositionMs());
}

JNIEXPORT jlong JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeGetTotalDurationMs(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (!sEngine) return 0;
    return static_cast<jlong>(sEngine->getTotalDurationMs());
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetTrackVolume(
        JNIEnv* /* env */, jobject /* thiz */, jint trackId, jfloat volume) {
    if (sEngine) sEngine->setTrackVolume(static_cast<int>(trackId), static_cast<float>(volume));
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetTrackMuted(
        JNIEnv* /* env */, jobject /* thiz */, jint trackId, jboolean muted) {
    if (sEngine) sEngine->setTrackMuted(static_cast<int>(trackId), static_cast<bool>(muted));
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetLoopRegion(
        JNIEnv* /* env */, jobject /* thiz */, jlong startMs, jlong endMs) {
    if (sEngine) sEngine->setLoopRegion(static_cast<int64_t>(startMs), static_cast<int64_t>(endMs));
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeClearLoopRegion(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (sEngine) sEngine->clearLoopRegion();
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetRecording(
        JNIEnv* /* env */, jobject /* thiz */, jboolean active) {
    if (sEngine) sEngine->setRecording(static_cast<bool>(active));
}

// ── Sync stubs (Phase 4) ────────────────────────────────────────────────

}  // extern "C"
