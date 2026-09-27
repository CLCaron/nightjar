#include <jni.h>
#include "audio_engine.h"
#include <memory>
#include <vector>

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

JNIEXPORT jlong JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeGetCapturedFrames(
        JNIEnv* /* env */, jobject /* thiz */) {
    return sEngine ? static_cast<jlong>(sEngine->getCapturedFrames()) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeGetCaptureStartPlaybackFrame(
        JNIEnv* /* env */, jobject /* thiz */) {
    return sEngine ? static_cast<jlong>(sEngine->getCaptureStartPlaybackFrame()) : -1;
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

JNIEXPORT jboolean JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeAddLoopingTrack(
        JNIEnv* env, jobject /* thiz */, jint trackId, jstring filePath,
        jlong durationMs, jlong phaseMs, jfloat volume) {
    if (!sEngine) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    bool ok = sEngine->addLoopingTrack(static_cast<int>(trackId), path,
        static_cast<int64_t>(durationMs), static_cast<int64_t>(phaseMs),
        static_cast<float>(volume));
    env->ReleaseStringUTFChars(filePath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetEndlessPlayback(
        JNIEnv* /* env */, jobject /* thiz */, jboolean enabled) {
    if (sEngine) sEngine->setEndlessPlayback(static_cast<bool>(enabled));
}

JNIEXPORT jboolean JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeAddCaptureLoop(
        JNIEnv* env, jobject /* thiz */, jint trackId, jstring filePath,
        jlong durationMs, jlong phaseFrames, jlong cycleFrames) {
    if (!sEngine) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    bool ok = sEngine->addCaptureLoop(static_cast<int>(trackId), path,
        static_cast<int64_t>(durationMs), static_cast<int64_t>(phaseFrames),
        static_cast<int64_t>(cycleFrames));
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

// ── Loop reset tracking ─────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeGetLoopResetCount(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (!sEngine) return 0;
    return static_cast<jlong>(sEngine->getLoopResetCount());
}

// ── Hardware latency measurement ────────────────────────────────────────

JNIEXPORT jlongArray JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeGetStreamEvidence(JNIEnv* env, jobject) {
    auto values = sEngine ? sEngine->getStreamEvidence() : std::array<int64_t, 35>{};
    auto result = env->NewLongArray(35);
    if (result) {
        std::array<jlong, 35> copied{};
        std::copy(values.begin(), values.end(), copied.begin());
        env->SetLongArrayRegion(result, 0, 35, copied.data());
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetPreferredInputDevice(JNIEnv*, jobject, jint id) {
    if (sEngine) sEngine->setPreferredInputDevice(id);
}

JNIEXPORT jlongArray JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeGetCaptureAnchor(JNIEnv* env, jobject) {
    auto values = sEngine ? sEngine->getCaptureAnchor() : std::array<int64_t, 3>{-1, 0, 0};
    std::array<jlong, 3> copied{};
    std::copy(values.begin(), values.end(), copied.begin());
    auto result = env->NewLongArray(3);
    if (result) env->SetLongArrayRegion(result, 0, 3, copied.data());
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeGetOutputLatencyMs(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (!sEngine) return -1;
    return static_cast<jlong>(sEngine->getOutputLatencyMs());
}

JNIEXPORT jlong JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeGetInputLatencyMs(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (!sEngine) return -1;
    return static_cast<jlong>(sEngine->getInputLatencyMs());
}

// ── Synth API ────────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeLoadSoundFont(
        JNIEnv* env, jobject /* thiz */, jstring path) {
    if (!sEngine) return JNI_FALSE;
    const char* cPath = env->GetStringUTFChars(path, nullptr);
    bool ok = sEngine->loadSoundFont(cPath);
    env->ReleaseStringUTFChars(path, cPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSynthNoteOn(
        JNIEnv* /* env */, jobject /* thiz */, jint channel, jint note, jint velocity) {
    if (sEngine) sEngine->synthNoteOn(static_cast<int>(channel), static_cast<int>(note), static_cast<int>(velocity));
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSynthNoteOff(
        JNIEnv* /* env */, jobject /* thiz */, jint channel, jint note) {
    if (sEngine) sEngine->synthNoteOff(static_cast<int>(channel), static_cast<int>(note));
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSynthProgramChange(
        JNIEnv* /* env */, jobject /* thiz */, jint channel, jint program) {
    if (sEngine) sEngine->synthProgramChange(static_cast<int>(channel), static_cast<int>(program));
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSynthRequestPreviewFlush(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (sEngine) sEngine->synthRequestPreviewFlush();
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetSynthVolume(
        JNIEnv* /* env */, jobject /* thiz */, jfloat volume) {
    if (sEngine) sEngine->setSynthVolume(static_cast<float>(volume));
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSynthAllSoundsOff(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (sEngine) sEngine->synthAllSoundsOff();
}

// ── Drum Sequencer API ───────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeUpdateDrumPattern(
        JNIEnv* env, jobject /* thiz */,
        jint stepsPerBar, jint bars, jlong offsetMs,
        jfloat volume, jboolean muted,
        jintArray stepIndicesArr, jintArray drumNotesArr, jfloatArray velocitiesArr,
        jlongArray clipOffsetsMsArr, jint beatsPerBar) {
    if (!sEngine) return;

    jint hitCount = env->GetArrayLength(stepIndicesArr);
    jint* stepIndices = env->GetIntArrayElements(stepIndicesArr, nullptr);
    jint* drumNotes = env->GetIntArrayElements(drumNotesArr, nullptr);
    jfloat* velocities = env->GetFloatArrayElements(velocitiesArr, nullptr);

    // Extract clip offsets
    jint clipCount = env->GetArrayLength(clipOffsetsMsArr);
    jlong* clipOffsetsMs = nullptr;
    if (clipCount > 0) {
        clipOffsetsMs = env->GetLongArrayElements(clipOffsetsMsArr, nullptr);
    }

    sEngine->updateDrumPattern(
        static_cast<int>(stepsPerBar), static_cast<int>(bars),
        static_cast<int64_t>(offsetMs),
        static_cast<float>(volume), static_cast<bool>(muted),
        static_cast<const int*>(stepIndices),
        static_cast<const int*>(drumNotes),
        static_cast<const float*>(velocities),
        static_cast<int>(hitCount),
        clipOffsetsMs != nullptr ? reinterpret_cast<const int64_t*>(clipOffsetsMs) : nullptr,
        static_cast<int>(clipCount),
        static_cast<int>(beatsPerBar));

    env->ReleaseIntArrayElements(stepIndicesArr, stepIndices, JNI_ABORT);
    env->ReleaseIntArrayElements(drumNotesArr, drumNotes, JNI_ABORT);
    env->ReleaseFloatArrayElements(velocitiesArr, velocities, JNI_ABORT);
    if (clipOffsetsMs != nullptr) {
        env->ReleaseLongArrayElements(clipOffsetsMsArr, clipOffsetsMs, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeUpdateDrumPatternClips(
        JNIEnv* env, jobject /* thiz */,
        jfloat volume, jboolean muted,
        jintArray clipStepsPerBarArr, jintArray clipTotalStepsArr,
        jintArray clipBeatsPerBarArr, jlongArray clipOffsetsMsArr,
        jintArray clipHitCountsArr,
        jintArray hitStepIndicesArr, jintArray hitDrumNotesArr,
        jfloatArray hitVelocitiesArr) {
    if (!sEngine) return;

    jint clipCount = env->GetArrayLength(clipStepsPerBarArr);
    jint* clipStepsPerBar = env->GetIntArrayElements(clipStepsPerBarArr, nullptr);
    jint* clipTotalSteps = env->GetIntArrayElements(clipTotalStepsArr, nullptr);
    jint* clipBeatsPerBar = env->GetIntArrayElements(clipBeatsPerBarArr, nullptr);
    jlong* clipOffsetsMs = env->GetLongArrayElements(clipOffsetsMsArr, nullptr);
    jint* clipHitCounts = env->GetIntArrayElements(clipHitCountsArr, nullptr);

    jint* hitStepIndices = env->GetIntArrayElements(hitStepIndicesArr, nullptr);
    jint* hitDrumNotes = env->GetIntArrayElements(hitDrumNotesArr, nullptr);
    jfloat* hitVelocities = env->GetFloatArrayElements(hitVelocitiesArr, nullptr);

    sEngine->updateDrumPatternClips(
        static_cast<float>(volume), static_cast<bool>(muted),
        static_cast<const int*>(clipStepsPerBar),
        static_cast<const int*>(clipTotalSteps),
        static_cast<const int*>(clipBeatsPerBar),
        reinterpret_cast<const int64_t*>(clipOffsetsMs),
        static_cast<const int*>(clipHitCounts),
        static_cast<int>(clipCount),
        static_cast<const int*>(hitStepIndices),
        static_cast<const int*>(hitDrumNotes),
        static_cast<const float*>(hitVelocities));

    env->ReleaseIntArrayElements(clipStepsPerBarArr, clipStepsPerBar, JNI_ABORT);
    env->ReleaseIntArrayElements(clipTotalStepsArr, clipTotalSteps, JNI_ABORT);
    env->ReleaseIntArrayElements(clipBeatsPerBarArr, clipBeatsPerBar, JNI_ABORT);
    env->ReleaseLongArrayElements(clipOffsetsMsArr, clipOffsetsMs, JNI_ABORT);
    env->ReleaseIntArrayElements(clipHitCountsArr, clipHitCounts, JNI_ABORT);
    env->ReleaseIntArrayElements(hitStepIndicesArr, hitStepIndices, JNI_ABORT);
    env->ReleaseIntArrayElements(hitDrumNotesArr, hitDrumNotes, JNI_ABORT);
    env->ReleaseFloatArrayElements(hitVelocitiesArr, hitVelocities, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetBpm(
        JNIEnv* /* env */, jobject /* thiz */, jdouble bpm) {
    if (sEngine) sEngine->setBpm(static_cast<double>(bpm));
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetDrumSequencerEnabled(
        JNIEnv* /* env */, jobject /* thiz */, jboolean enabled) {
    if (sEngine) sEngine->setDrumSequencerEnabled(static_cast<bool>(enabled));
}

// ── MIDI Sequencer API ──────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeUpdateMidiTracks(
        JNIEnv* env, jobject /* thiz */,
        jintArray channelsArr, jintArray programsArr,
        jfloatArray volumesArr, jbooleanArray mutedArr,
        jintArray trackEventCountsArr,
        jlongArray eventFramesArr, jintArray eventChannelsArr,
        jintArray eventNotesArr, jintArray eventVelocitiesArr) {
    if (!sEngine) return;

    jint trackCount = env->GetArrayLength(channelsArr);
    jint* channels = env->GetIntArrayElements(channelsArr, nullptr);
    jint* programs = env->GetIntArrayElements(programsArr, nullptr);
    jfloat* volumes = env->GetFloatArrayElements(volumesArr, nullptr);
    jboolean* mutedRaw = env->GetBooleanArrayElements(mutedArr, nullptr);
    jint* trackEventCounts = env->GetIntArrayElements(trackEventCountsArr, nullptr);

    // Convert jboolean array to bool array (can't use vector<bool> — it's a bit-packed
    // specialization without .data()). Use a plain bool array instead.
    std::vector<uint8_t> mutedBuf(trackCount);
    for (int i = 0; i < trackCount; ++i) {
        mutedBuf[i] = mutedRaw[i] != JNI_FALSE ? 1 : 0;
    }

    jint totalEvents = env->GetArrayLength(eventFramesArr);
    jlong* eventFrames = env->GetLongArrayElements(eventFramesArr, nullptr);
    jint* eventChannels = env->GetIntArrayElements(eventChannelsArr, nullptr);
    jint* eventNotes = env->GetIntArrayElements(eventNotesArr, nullptr);
    jint* eventVelocities = env->GetIntArrayElements(eventVelocitiesArr, nullptr);

    sEngine->updateMidiTracks(
        static_cast<const int*>(channels),
        static_cast<const int*>(programs),
        static_cast<const float*>(volumes),
        reinterpret_cast<const bool*>(mutedBuf.data()),
        static_cast<int>(trackCount),
        static_cast<const int*>(trackEventCounts),
        reinterpret_cast<const int64_t*>(eventFrames),
        static_cast<const int*>(eventChannels),
        static_cast<const int*>(eventNotes),
        static_cast<const int*>(eventVelocities),
        static_cast<int>(totalEvents));

    env->ReleaseIntArrayElements(channelsArr, channels, JNI_ABORT);
    env->ReleaseIntArrayElements(programsArr, programs, JNI_ABORT);
    env->ReleaseFloatArrayElements(volumesArr, volumes, JNI_ABORT);
    env->ReleaseBooleanArrayElements(mutedArr, mutedRaw, JNI_ABORT);
    env->ReleaseIntArrayElements(trackEventCountsArr, trackEventCounts, JNI_ABORT);
    env->ReleaseLongArrayElements(eventFramesArr, eventFrames, JNI_ABORT);
    env->ReleaseIntArrayElements(eventChannelsArr, eventChannels, JNI_ABORT);
    env->ReleaseIntArrayElements(eventNotesArr, eventNotes, JNI_ABORT);
    env->ReleaseIntArrayElements(eventVelocitiesArr, eventVelocities, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetMidiSequencerEnabled(
        JNIEnv* /* env */, jobject /* thiz */, jboolean enabled) {
    if (sEngine) sEngine->setMidiSequencerEnabled(static_cast<bool>(enabled));
}

// ── Count-in API ─────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetCountIn(
        JNIEnv* /* env */, jobject /* thiz */, jint bars, jint beatsPerBar) {
    if (sEngine) sEngine->setCountIn(static_cast<int>(bars), static_cast<int>(beatsPerBar));
}

// ── Metronome API ────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetMetronomeEnabled(
        JNIEnv* /* env */, jobject /* thiz */, jboolean enabled) {
    if (sEngine) sEngine->setMetronomeEnabled(static_cast<bool>(enabled));
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetMetronomeVolume(
        JNIEnv* /* env */, jobject /* thiz */, jfloat volume) {
    if (sEngine) sEngine->setMetronomeVolume(static_cast<float>(volume));
}

JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeSetMetronomeBeatsPerBar(
        JNIEnv* /* env */, jobject /* thiz */, jint beatsPerBar) {
    if (sEngine) sEngine->setMetronomeBeatsPerBar(static_cast<int>(beatsPerBar));
}

JNIEXPORT jlong JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeGetLastMetronomeBeatFrame(
        JNIEnv* /* env */, jobject /* thiz */) {
    if (!sEngine) return -1;
    return static_cast<jlong>(sEngine->getLastMetronomeBeatFrame());
}

JNIEXPORT jboolean JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeStartAcousticCheck(JNIEnv* env, jobject, jfloatArray probes) {
    if (!sEngine || !probes || env->GetArrayLength(probes) != 11 * 1764) return JNI_FALSE;
    std::vector<float> data(11 * 1764);
    env->GetFloatArrayRegion(probes, 0, static_cast<jsize>(data.size()), data.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    return sEngine->startAcousticCheck(data.data(), static_cast<int>(data.size()));
}
JNIEXPORT void JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeArmAcousticCheck(JNIEnv*, jobject) {
    if (sEngine) sEngine->armAcousticCheck();
}
JNIEXPORT jboolean JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeStopAcousticCheck(JNIEnv*, jobject) {
    return sEngine && sEngine->stopAcousticCheck();
}
JNIEXPORT jlongArray JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeAcousticCheckProgress(JNIEnv* env, jobject) {
    auto value = sEngine ? sEngine->acousticCheckProgress() : std::array<int64_t, 3>{0, 1, 0};
    auto result = env->NewLongArray(3);
    jlong data[3] = {value[0], value[1], value[2]};
    if (result) env->SetLongArrayRegion(result, 0, 3, data);
    return result;
}
JNIEXPORT jfloatArray JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeAcousticCheckSamples(JNIEnv* env, jobject) {
    auto data = sEngine ? sEngine->acousticCheckSamples() : std::vector<float>{};
    auto result = env->NewFloatArray(static_cast<jsize>(data.size()));
    if (result && !data.empty()) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(data.size()), data.data());
    return result;
}
JNIEXPORT jlongArray JNICALL
Java_com_example_nightjar_audio_OboeAudioEngine_nativeAcousticCheckEvidence(JNIEnv* env, jobject) {
    auto data = sEngine ? sEngine->acousticCheckEvidence() : std::vector<int64_t>{};
    std::vector<jlong> values(data.begin(), data.end());
    auto result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result && !values.empty()) env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

}  // extern "C"
