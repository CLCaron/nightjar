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
 * uses atomics for all cross-thread state. StateFlow updates happen on
 * the calling coroutine's thread (typically viewModelScope).
 *
 * ## Lifecycle
 * - [initialize] once from [com.example.nightjar.NightjarApplication.onCreate]
 * - [shutdown] from Application.onTerminate
 * - The engine is a singleton — ViewModels do NOT own or release it
 *
 * ## Phases
 * - Phase 1: init/shutdown skeleton
 * - Phase 2: recording (startRecording, awaitFirstBuffer, openWriteGate, stopRecording)
 * - Phase 3: playback + mixer (addTrack, play, pause, seek, pollState)
 * - Phase 4: hardware sync (getOutputLatencyMs, getInputLatencyMs)
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

    /**
     * Start recording to [filePath]. Opens the Oboe input stream and WAV
     * file writer. Audio flows but is NOT written to disk until
     * [openWriteGate] is called.
     *
     * @return true if recording started successfully.
     */
    fun startRecording(filePath: String): Boolean {
        val ok = nativeStartRecording(filePath)
        Log.d(TAG, "startRecording($filePath) → $ok")
        return ok
    }

    /**
     * Suspend until the recording stream's first audio callback has fired,
     * confirming the hardware audio pipeline is hot. Call after [startRecording]
     * and before [openWriteGate].
     *
     * Runs on [Dispatchers.IO] since the native call blocks via sleep loop.
     *
     * @return true if the pipeline is hot, false on timeout.
     */
    suspend fun awaitFirstBuffer(timeoutMs: Int = 2000): Boolean =
        withContext(Dispatchers.IO) {
            val hot = nativeAwaitFirstBuffer(timeoutMs)
            Log.d(TAG, "awaitFirstBuffer() → $hot")
            hot
        }

    /**
     * Open the write gate. From this point forward captured audio is
     * written to the WAV file on disk. Call this right when playback
     * starts so the WAV is synchronised with playback.
     */
    fun openWriteGate() {
        nativeOpenWriteGate()
        Log.d(TAG, "openWriteGate()")
    }

    /**
     * Stop recording. Closes the Oboe stream, drains the ring buffer,
     * patches the WAV header.
     *
     * @return duration of captured audio in ms, or -1 if nothing captured.
     */
    fun stopRecording(): Long {
        val durationMs = nativeStopRecording()
        Log.d(TAG, "stopRecording() → ${durationMs}ms")
        return durationMs
    }

    /** Returns true if a recording is in progress. */
    fun isRecordingActive(): Boolean = nativeIsRecordingActive()

    /**
     * Peak amplitude of the most recent audio callback, normalised to 0–1.
     * Polled from the UI via ViewModel ticker for live waveform.
     */
    fun getLatestPeakAmplitude(): Float = nativeGetLatestPeakAmplitude()

    /** Duration of audio written to disk so far, in ms. */
    fun getRecordedDurationMs(): Long = nativeGetRecordedDurationMs()

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

    // Recording (Phase 2)
    private external fun nativeStartRecording(filePath: String): Boolean
    private external fun nativeAwaitFirstBuffer(timeoutMs: Int): Boolean
    private external fun nativeOpenWriteGate()
    private external fun nativeStopRecording(): Long
    private external fun nativeIsRecordingActive(): Boolean
    private external fun nativeGetLatestPeakAmplitude(): Float
    private external fun nativeGetRecordedDurationMs(): Long

    companion object {
        private const val TAG = "OboeAudioEngine"

        init {
            System.loadLibrary("nightjar-audio")
        }
    }
}
