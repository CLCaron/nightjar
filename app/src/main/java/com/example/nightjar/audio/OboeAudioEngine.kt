package com.example.nightjar.audio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kotlin facade for the native Oboe audio engine.
 *
 * JNI calls map to C functions in `jni_bridge.cpp`. This class adds
 * Kotlin-idiomatic StateFlow exposure, coroutine-based awaiting, and
 * ms-to-frame conversions.
 *
 * Thread safety: JNI calls are safe from any thread. The native engine
 * uses atomics for all cross-thread state. StateFlow updates happen via
 * [pollState] called from the ViewModel's tick coroutine.
 *
 * ## Lifecycle
 * - [initialize] once from [com.example.nightjar.NightjarApplication.onCreate]
 * - [shutdown] from Application.onTerminate
 * - The engine is a singleton — ViewModels do NOT own or release it
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

    fun initialize(): Boolean {
        val result = nativeInit()
        Log.d(TAG, "initialize() → $result")
        return result
    }

    fun shutdown() {
        Log.d(TAG, "shutdown()")
        nativeShutdown()
    }

    fun isInitialized(): Boolean = nativeIsInitialized()

    // ── Recording ──────────────────────────────────────────────────────────

    fun startRecording(filePath: String): Boolean {
        val ok = nativeStartRecording(filePath)
        Log.d(TAG, "startRecording($filePath) → $ok")
        return ok
    }

    suspend fun awaitFirstBuffer(timeoutMs: Int = 2000): Boolean =
        withContext(Dispatchers.IO) {
            val hot = nativeAwaitFirstBuffer(timeoutMs)
            Log.d(TAG, "awaitFirstBuffer() → $hot")
            hot
        }

    fun openWriteGate() {
        nativeOpenWriteGate()
        Log.d(TAG, "openWriteGate()")
    }

    fun stopRecording(): Long {
        val durationMs = nativeStopRecording()
        Log.d(TAG, "stopRecording() → ${durationMs}ms")
        return durationMs
    }

    fun isRecordingActive(): Boolean = nativeIsRecordingActive()

    fun getLatestPeakAmplitude(): Float = nativeGetLatestPeakAmplitude()

    fun getRecordedDurationMs(): Long = nativeGetRecordedDurationMs()

    // ── Playback ───────────────────────────────────────────────────────────

    fun addTrack(
        trackId: Int, filePath: String, durationMs: Long,
        offsetMs: Long, trimStartMs: Long, trimEndMs: Long,
        volume: Float, isMuted: Boolean
    ): Boolean {
        return nativeAddTrack(trackId, filePath, durationMs, offsetMs,
            trimStartMs, trimEndMs, volume, isMuted)
    }

    fun removeTrack(trackId: Int) = nativeRemoveTrack(trackId)

    fun removeAllTracks() = nativeRemoveAllTracks()

    fun play() {
        nativePlay()
    }

    fun pause() {
        nativePause()
    }

    fun seekTo(positionMs: Long) {
        nativeSeekTo(positionMs)
    }

    /**
     * Poll native transport state and update StateFlows.
     * Call this at ~60fps from the ViewModel's tick coroutine.
     */
    fun pollState() {
        _isPlaying.value = nativeIsPlaying()
        _positionMs.value = nativeGetPositionMs()
        _totalDurationMs.value = nativeGetTotalDurationMs()
    }

    // ── Per-track controls ─────────────────────────────────────────────────

    fun setTrackVolume(trackId: Int, volume: Float) =
        nativeSetTrackVolume(trackId, volume)

    fun setTrackMuted(trackId: Int, muted: Boolean) =
        nativeSetTrackMuted(trackId, muted)

    // ── Loop ───────────────────────────────────────────────────────────────

    fun setLoopRegion(startMs: Long, endMs: Long) =
        nativeSetLoopRegion(startMs, endMs)

    fun clearLoopRegion() = nativeClearLoopRegion()

    // ── Overdub support ────────────────────────────────────────────────────

    fun setRecording(active: Boolean) = nativeSetRecording(active)

    // ── Hardware latency measurement ──────────────────────────────────────

    /**
     * Returns the output pipeline latency in ms measured via AAudio
     * hardware timestamps. Returns -1 if timestamps are unavailable
     * (API <26 / OpenSL ES fallback, or output stream not active).
     */
    fun getOutputLatencyMs(): Long = nativeGetOutputLatencyMs()

    /**
     * Returns the input pipeline latency in ms measured via AAudio
     * hardware timestamps. Returns -1 if timestamps are unavailable
     * or no recording is in progress.
     */
    fun getInputLatencyMs(): Long = nativeGetInputLatencyMs()

    // ── Native method declarations ─────────────────────────────────────────

    private external fun nativeInit(): Boolean
    private external fun nativeShutdown()
    private external fun nativeIsInitialized(): Boolean

    // Recording
    private external fun nativeStartRecording(filePath: String): Boolean
    private external fun nativeAwaitFirstBuffer(timeoutMs: Int): Boolean
    private external fun nativeOpenWriteGate()
    private external fun nativeStopRecording(): Long
    private external fun nativeIsRecordingActive(): Boolean
    private external fun nativeGetLatestPeakAmplitude(): Float
    private external fun nativeGetRecordedDurationMs(): Long

    // Playback
    private external fun nativeAddTrack(
        trackId: Int, filePath: String, durationMs: Long,
        offsetMs: Long, trimStartMs: Long, trimEndMs: Long,
        volume: Float, muted: Boolean
    ): Boolean
    private external fun nativeRemoveTrack(trackId: Int)
    private external fun nativeRemoveAllTracks()
    private external fun nativePlay()
    private external fun nativePause()
    private external fun nativeSeekTo(positionMs: Long)
    private external fun nativeIsPlaying(): Boolean
    private external fun nativeGetPositionMs(): Long
    private external fun nativeGetTotalDurationMs(): Long

    // Per-track controls
    private external fun nativeSetTrackVolume(trackId: Int, volume: Float)
    private external fun nativeSetTrackMuted(trackId: Int, muted: Boolean)

    // Loop
    private external fun nativeSetLoopRegion(startMs: Long, endMs: Long)
    private external fun nativeClearLoopRegion()

    // Overdub
    private external fun nativeSetRecording(active: Boolean)

    // Hardware latency
    private external fun nativeGetOutputLatencyMs(): Long
    private external fun nativeGetInputLatencyMs(): Long

    companion object {
        private const val TAG = "OboeAudioEngine"

        init {
            System.loadLibrary("nightjar-audio")
        }
    }
}
