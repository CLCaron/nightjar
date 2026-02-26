package com.example.nightjar.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kotlin facade for the native Oboe audio engine.
 *
 * JNI calls map to C functions in `jni_bridge.cpp`. This class adds
 * Kotlin-idiomatic StateFlow exposure, coroutine-based awaiting, and
 * ms↔frame conversions.
 *
 * Thread safety: JNI calls are safe from any thread. The native engine
 * uses atomics for all cross-thread state. StateFlow updates happen on
 * the calling coroutine's thread (typically viewModelScope).
 *
 * ## Lifecycle
 * - [initialize] once from [com.example.nightjar.NightjarApplication.onCreate]
 * - [shutdown] from Application.onTerminate
 * - The engine is a singleton — ViewModels do NOT own or release it
 *
 * ## Phases
 * - Phase 1: init/shutdown skeleton (current)
 * - Phase 2: recording (startRecording, awaitFirstBuffer, openWriteGate, stopRecording)
 * - Phase 3: playback + mixer (addTrack, play, pause, seek, pollState)
 * - Phase 4: hardware sync (getOutputLatencyMs, getInputLatencyMs)
 * - Phase 5: loop support + cleanup
 */
@Singleton
class OboeAudioEngine @Inject constructor() {

    // ── StateFlows (updated by pollState() from ViewModel tick coroutine) ──

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _totalDurationMs = MutableStateFlow(0L)
    val totalDurationMs: StateFlow<Long> = _totalDurationMs

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /** Initialize the native engine. Returns true on success. */
    fun initialize(): Boolean {
        val result = nativeInit()
        Log.d(TAG, "initialize() → $result")
        return result
    }

    /** Shut down the native engine. Safe to call multiple times. */
    fun shutdown() {
        Log.d(TAG, "shutdown()")
        nativeShutdown()
    }

    /** Returns true if the native engine is initialized. */
    fun isInitialized(): Boolean = nativeIsInitialized()

    // ── Recording (Phase 2) ────────────────────────────────────────────────

    // fun startRecording(filePath: String): Boolean
    // suspend fun awaitFirstBuffer(timeoutMs: Int = 2000): Boolean
    // fun openWriteGate()
    // fun stopRecording(): Long  // returns duration in ms
    // fun isRecordingActive(): Boolean
    // fun getLatestPeakAmplitude(): Float
    // fun getRecordedDurationMs(): Long

    // ── Playback (Phase 3) ─────────────────────────────────────────────────

    // fun addTrack(trackId: Int, filePath: String, durationMs: Long,
    //              offsetMs: Long, trimStartMs: Long, trimEndMs: Long,
    //              volume: Float, isMuted: Boolean): Boolean
    // fun removeTrack(trackId: Int)
    // fun removeAllTracks()
    // fun play()
    // fun pause()
    // fun seekTo(positionMs: Long)
    // fun pollState()  -- call at ~60fps from ViewModel tick coroutine
    // fun setTrackVolume(trackId: Int, volume: Float)
    // fun setTrackMuted(trackId: Int, muted: Boolean)

    // ── Loop (Phase 5) ─────────────────────────────────────────────────────

    // fun setLoopRegion(startMs: Long, endMs: Long)
    // fun clearLoopRegion()

    // ── Sync (Phase 4) ─────────────────────────────────────────────────────

    // fun getOutputLatencyMs(): Long
    // fun getInputLatencyMs(): Long
    // suspend fun awaitPlaybackRendering(timeoutMs: Int = 2000): Boolean
    // fun setRecording(active: Boolean)

    // ── Native method declarations ─────────────────────────────────────────

    private external fun nativeInit(): Boolean
    private external fun nativeShutdown()
    private external fun nativeIsInitialized(): Boolean

    companion object {
        private const val TAG = "OboeAudioEngine"

        init {
            System.loadLibrary("nightjar-audio")
        }
    }
}
