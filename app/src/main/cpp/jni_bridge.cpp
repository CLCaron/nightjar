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
 * All other methods delegate to the singleton. Phase 1 exposes only
 * init/shutdown; subsequent phases add recording, playback, and sync.
 */

static std::unique_ptr<nightjar::AudioEngine> sEngine;

extern "C" {

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

// ── Recording stubs (Phase 2) ───────────────────────────────────────────

// ── Playback stubs (Phase 3) ────────────────────────────────────────────

// ── Sync stubs (Phase 4) ────────────────────────────────────────────────

}  // extern "C"
