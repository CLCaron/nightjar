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

    // ── Loop reset tracking ─────────────────────────────────────────────

    /** Returns the number of times the playback loop has reset to loopStart. */
    fun getLoopResetCount(): Long = nativeGetLoopResetCount()

    // ── Synth API ────────────────────────────────────────────────────────

    /**
     * Load a SoundFont file by absolute path and start the synth render thread.
     * Must be called once after [initialize] before any note events.
     */
    fun loadSoundFont(path: String): Boolean {
        val ok = nativeLoadSoundFont(path)
        Log.d(TAG, "loadSoundFont($path) -> $ok")
        return ok
    }

    fun synthNoteOn(channel: Int, note: Int, velocity: Int) =
        nativeSynthNoteOn(channel, note, velocity)

    fun synthNoteOff(channel: Int, note: Int) =
        nativeSynthNoteOff(channel, note)

    /**
     * Send a MIDI program change to a specific channel (no scheduler
     * involvement). Used by the preview path to align the preview
     * channel with the track's currently-selected instrument before
     * each preview noteOn.
     */
    fun synthProgramChange(channel: Int, program: Int) =
        nativeSynthProgramChange(channel, program)

    /**
     * Fire a one-shot preview note: align the channel's program, send a
     * noteOn, and let the caller schedule the matching noteOff (the
     * caller owns the timing and cancellation, since previews fire from
     * gesture handlers that need to cancel on a fresh interaction).
     *
     * Audible regardless of transport state because the synth render
     * thread keeps cycling when paused (see `synth_engine.cpp`).
     */
    fun previewNote(channel: Int, pitch: Int, velocity: Int, program: Int) {
        // Drop any pre-rendered silence in the synth's output ring buffer
        // before kicking off the noteOn. Otherwise the freshly-fired
        // voice waits behind ~186ms of buffered silence (the ring's
        // near-capacity steady state when paused), which usually outlasts
        // a 200ms preview duration and renders the preview inaudible.
        // Render thread only honors this when paused, so playback isn't
        // glitched.
        nativeSynthRequestPreviewFlush()
        nativeSynthProgramChange(channel, program)
        nativeSynthNoteOn(channel, pitch, velocity)
    }

    fun setSynthVolume(volume: Float) =
        nativeSetSynthVolume(volume)

    /** Immediately silence all sounding synth notes on all channels. */
    fun synthAllSoundsOff() = nativeSynthAllSoundsOff()

    // ── Drum Sequencer ─────────────────────────────────────────────────────

    /**
     * Replace the drum pattern in the C++ step sequencer.
     * Parallel arrays represent active steps: stepIndices[i], drumNotes[i], velocities[i].
     * Optional clipOffsetsMs specifies timeline positions for multiple clip placements.
     */
    fun updateDrumPattern(
        stepsPerBar: Int,
        bars: Int,
        offsetMs: Long,
        volume: Float,
        muted: Boolean,
        stepIndices: IntArray,
        drumNotes: IntArray,
        velocities: FloatArray,
        clipOffsetsMs: LongArray = LongArray(0),
        beatsPerBar: Int = 4
    ) = nativeUpdateDrumPattern(stepsPerBar, bars, offsetMs, volume, muted,
        stepIndices, drumNotes, velocities, clipOffsetsMs, beatsPerBar)

    /**
     * Replace the drum pattern with per-clip data. Each clip has its own grid dimensions and hits.
     * Flat arrays: per-clip metadata + concatenated hit arrays.
     */
    fun updateDrumPatternClips(
        volume: Float,
        muted: Boolean,
        clipStepsPerBar: IntArray,
        clipTotalSteps: IntArray,
        clipBeatsPerBar: IntArray,
        clipOffsetsMs: LongArray,
        clipHitCounts: IntArray,
        hitStepIndices: IntArray,
        hitDrumNotes: IntArray,
        hitVelocities: FloatArray
    ) = nativeUpdateDrumPatternClips(
        volume, muted,
        clipStepsPerBar, clipTotalSteps, clipBeatsPerBar, clipOffsetsMs, clipHitCounts,
        hitStepIndices, hitDrumNotes, hitVelocities
    )

    fun setBpm(bpm: Double) = nativeSetBpm(bpm)

    fun setDrumSequencerEnabled(enabled: Boolean) =
        nativeSetDrumSequencerEnabled(enabled)

    // ── MIDI Sequencer ────────────────────────────────────────────────────

    /**
     * Replace all MIDI track data in the C++ MIDI sequencer.
     * Flat parallel arrays: per-track metadata + flattened noteOn/noteOff events.
     *
     * @param channels         MIDI channel per track (size = trackCount)
     * @param programs         GM program per track (size = trackCount)
     * @param volumes          Volume per track (size = trackCount)
     * @param muted            Mute flag per track (size = trackCount)
     * @param trackEventCounts Number of events per track (size = trackCount)
     * @param eventFrames      Frame position of each event (size = totalEvents)
     * @param eventChannels    MIDI channel of each event (size = totalEvents)
     * @param eventNotes       MIDI note of each event (size = totalEvents)
     * @param eventVelocities  Velocity of each event, 0 = noteOff (size = totalEvents)
     */
    fun updateMidiTracks(
        channels: IntArray,
        programs: IntArray,
        volumes: FloatArray,
        muted: BooleanArray,
        trackEventCounts: IntArray,
        eventFrames: LongArray,
        eventChannels: IntArray,
        eventNotes: IntArray,
        eventVelocities: IntArray
    ) = nativeUpdateMidiTracks(
        channels, programs, volumes, muted, trackEventCounts,
        eventFrames, eventChannels, eventNotes, eventVelocities
    )

    fun setMidiSequencerEnabled(enabled: Boolean) =
        nativeSetMidiSequencerEnabled(enabled)

    // ── Count-in ──────────────────────────────────────────────────────────

    /** Set count-in duration. Next play() will start from negative position. */
    fun setCountIn(bars: Int, beatsPerBar: Int) =
        nativeSetCountIn(bars, beatsPerBar)

    // ── Metronome ─────────────────────────────────────────────────────────

    fun setMetronomeEnabled(enabled: Boolean) =
        nativeSetMetronomeEnabled(enabled)

    fun setMetronomeVolume(volume: Float) =
        nativeSetMetronomeVolume(volume)

    fun setMetronomeBeatsPerBar(beatsPerBar: Int) =
        nativeSetMetronomeBeatsPerBar(beatsPerBar)

    /** Returns the frame of the last metronome beat event. Polled for LED pulse. */
    fun getLastMetronomeBeatFrame(): Long = nativeGetLastMetronomeBeatFrame()

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

    // Loop reset tracking
    private external fun nativeGetLoopResetCount(): Long

    // Synth
    private external fun nativeLoadSoundFont(path: String): Boolean
    private external fun nativeSynthNoteOn(channel: Int, note: Int, velocity: Int)
    private external fun nativeSynthNoteOff(channel: Int, note: Int)
    private external fun nativeSynthProgramChange(channel: Int, program: Int)
    private external fun nativeSynthRequestPreviewFlush()
    private external fun nativeSetSynthVolume(volume: Float)
    private external fun nativeSynthAllSoundsOff()

    // Drum sequencer
    private external fun nativeUpdateDrumPattern(
        stepsPerBar: Int, bars: Int, offsetMs: Long,
        volume: Float, muted: Boolean,
        stepIndices: IntArray, drumNotes: IntArray, velocities: FloatArray,
        clipOffsetsMs: LongArray, beatsPerBar: Int
    )
    private external fun nativeUpdateDrumPatternClips(
        volume: Float, muted: Boolean,
        clipStepsPerBar: IntArray, clipTotalSteps: IntArray,
        clipBeatsPerBar: IntArray, clipOffsetsMs: LongArray,
        clipHitCounts: IntArray,
        hitStepIndices: IntArray, hitDrumNotes: IntArray,
        hitVelocities: FloatArray
    )
    private external fun nativeSetBpm(bpm: Double)
    private external fun nativeSetDrumSequencerEnabled(enabled: Boolean)

    // MIDI sequencer
    private external fun nativeUpdateMidiTracks(
        channels: IntArray, programs: IntArray,
        volumes: FloatArray, muted: BooleanArray,
        trackEventCounts: IntArray,
        eventFrames: LongArray, eventChannels: IntArray,
        eventNotes: IntArray, eventVelocities: IntArray
    )
    private external fun nativeSetMidiSequencerEnabled(enabled: Boolean)

    // Count-in
    private external fun nativeSetCountIn(bars: Int, beatsPerBar: Int)

    // Metronome
    private external fun nativeSetMetronomeEnabled(enabled: Boolean)
    private external fun nativeSetMetronomeVolume(volume: Float)
    private external fun nativeSetMetronomeBeatsPerBar(beatsPerBar: Int)
    private external fun nativeGetLastMetronomeBeatFrame(): Long

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
