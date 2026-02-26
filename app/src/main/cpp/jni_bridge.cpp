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

// ── Recording (Phase 2) ────────────────────────────────────────────────

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

// ── Playback stubs (Phase 3) ────────────────────────────────────────────

// ── Sync stubs (Phase 4) ────────────────────────────────────────────────

}  // extern "C"
