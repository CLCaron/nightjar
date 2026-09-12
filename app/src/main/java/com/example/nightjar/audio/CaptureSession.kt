package com.example.nightjar.audio

import android.util.Log
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.repository.CaptureGroup
import com.example.nightjar.data.repository.NotesSession
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.data.storage.RecordingStorage
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CaptureOptions(
    val metronome: Boolean = false,
    val volume: Float = 0.7f,
    val bpm: Double = 120.0,
    val countInBars: Int = 0
)

enum class CapturePhase { IDLE, STARTING, RECORDING, SAVING, SAVED, FAILED }

data class CaptureState(
    val token: String = "",
    val phase: CapturePhase = CapturePhase.IDLE,
    val countingIn: Boolean = false,
    val amplitudes: List<Float> = emptyList(),
    val file: File? = null,
    val ideaId: Long? = null,
    val takes: List<TakeEntity> = emptyList(),
    val selectedTakeId: Long? = null,
    val takeNumber: Int = 0,
    val playing: Boolean = false,
    val pendingSave: Boolean = false,
    val error: String? = null
) {
    val recording: Boolean get() = phase == CapturePhase.STARTING || phase == CapturePhase.RECORDING
    val busy: Boolean get() = recording || phase == CapturePhase.SAVING
}

/** Owns initial capture and finalization independently of the screen and its ViewModel.
 * Commands and state are confined to Main. Native callbacks and overdub timing are unchanged.
 */
@Singleton
class CaptureSession @Inject constructor(
    private val engine: OboeAudioEngine,
    private val storage: RecordingStorage,
    private val repo: IdeaRepository,
    private val soundFont: SoundFontManager,
    private val foreground: CaptureForeground,
    private val takeWriter: CaptureTakeWriter,
    private val notes: NotesSession
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(CaptureState())
    val state = mutableState.asStateFlow()
    private var options = CaptureOptions()
    private var startJob: Job? = null
    private var tickJob: Job? = null
    private var durationMs = 0L
    private var ownsInput = false
    private var gateOpened = false
    private var group: CaptureGroup? = null
    private val boundaries = mutableListOf<Long>()
    private var prepared: List<CaptureAudio>? = null
    private var auditionJob: Job? = null
    private var writing: NotesSession.Document? = null
    private var ideaCreation: Deferred<Long>? = null

    fun writingDocument(): NotesSession.Document {
        return writing ?: (state.value.ideaId?.let { notes.open(it) }
            ?: notes.create { ensureIdeaId() }).also { writing = it }
    }

    private suspend fun ensureIdeaId(): Long {
        state.value.ideaId?.let { return it }
        val creation = ideaCreation ?: scope.async(start = CoroutineStart.LAZY) {
            repo.createEmptyIdea().also { id -> mutableState.value = state.value.copy(ideaId = id) }
        }.also { ideaCreation = it; it.start() }
        return try { creation.await() } finally { if (creation.isCompleted) ideaCreation = null }
    }

    fun canStartNewIdea(): Boolean = !state.value.busy && !state.value.pendingSave &&
        ideaCreation?.isActive != true && (writing?.state?.value?.safeToLeaveIdea != false)


    fun selectTake(id: Long) {
        if (state.value.busy || state.value.pendingSave) return
        val take = state.value.takes.find { it.id == id } ?: return
        stopAudition()
        mutableState.value = state.value.copy(selectedTakeId = id, file = storage.getAudioFile(take.audioFileName))
    }

    fun stopAudition() {
        if (!state.value.playing) return
        auditionJob?.cancel()
        auditionJob = null
        engine.pause()
        engine.removeAllTracks()
        mutableState.value = state.value.copy(playing = false)
    }

    fun playSelected() {
        if (state.value.busy || state.value.pendingSave) return
        if (state.value.playing) { stopAudition(); return }
        val take = state.value.takes.find { it.id == state.value.selectedTakeId } ?: return
        if (take.durationMs <= 0) return
        try {
            engine.pause()
            engine.removeAllTracks()
            engine.clearLoopRegion()
            engine.setCountIn(0, 4)
            engine.setMetronomeEnabled(false)
            check(engine.addTrack(-1, storage.getAudioFile(take.audioFileName).absolutePath,
                take.durationMs, 0, 0, 0, 1f, false)) { "Could not play this take." }
            engine.seekTo(0)
            engine.play()
            mutableState.value = state.value.copy(playing = true, error = null)
            auditionJob = scope.launch {
                while (isActive) {
                    delay(50)
                    engine.pollState()
                    if (!engine.isPlaying.value || engine.positionMs.value >= take.durationMs) {
                        stopAudition()
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Take audition failed", e)
            engine.pause()
            engine.removeAllTracks()
            mutableState.value = state.value.copy(playing = false, error = e.message ?: "Could not play this take.")
        }
    }

    fun start(options: CaptureOptions) {
        if (state.value.phase == CapturePhase.RECORDING) {
            boundaries.add(engine.getRecordedDurationMs().coerceAtLeast(boundaries.lastOrNull() ?: 0))
            mutableState.value = state.value.copy(takeNumber = state.value.takeNumber + 1)
            return
        }
        if (state.value.busy) return
        // A failed database save must be retried before replacing its in-memory recovery context.
        if (state.value.pendingSave) {
            retrySave()
            return
        }
        if (engine.isRecordingActive()) {
            mutableState.value = state.value.copy(phase = CapturePhase.FAILED, error = "Another recording is still active.")
            return
        }
        stopAudition()
        engine.pause()
        engine.removeAllTracks()
        engine.clearLoopRegion()
        engine.setCountIn(0, 4)
        engine.setMetronomeEnabled(false)
        boundaries.clear()
        gateOpened = false
        prepared = null
        this.options = options
        durationMs = 0
        val token = UUID.randomUUID().toString()
        mutableState.value = state.value.copy(token = token, phase = CapturePhase.STARTING,
            file = null, amplitudes = emptyList(), error = null, takeNumber = state.value.takes.size + 1)
        try {
            foreground.start(token)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start foreground capture", e)
            mutableState.value = state.value.copy(phase = CapturePhase.FAILED, error = "Could not start recording. Return to Nightjar and try again.")
        }
    }

    /** Called only after the service has entered the microphone foreground state. */
    fun foregroundReady(token: String) {
        if (token != state.value.token || state.value.phase != CapturePhase.STARTING || startJob != null) return
        val starting = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val countIn = options.metronome && options.countInBars > 0
                if (options.metronome) {
                    soundFont.getSoundFontPath()?.let { engine.loadSoundFont(it) }
                    engine.setBpm(options.bpm)
                    engine.setMetronomeVolume(options.volume)
                    engine.setMetronomeEnabled(true)
                    engine.setMetronomeBeatsPerBar(4)
                    if (countIn) {
                        mutableState.value = state.value.copy(countingIn = true)
                        engine.setCountIn(options.countInBars, 4)
                    }
                    engine.seekTo(0)
                    engine.play()
                }
                val file = storage.createRecordingFile()
                mutableState.value = state.value.copy(file = file)
                ownsInput = true
                check(engine.startRecording(file.absolutePath)) { "Failed to start recording." }
                check(engine.awaitFirstBuffer()) { "The microphone did not provide audio. Please try again." }
                if (countIn) delay((options.countInBars * 4 * 60_000.0 / options.bpm).toLong())
                engine.openWriteGate()
                gateOpened = true
                mutableState.value = state.value.copy(phase = CapturePhase.RECORDING, countingIn = false)
                tickJob = scope.launch {
                    // Only retain the visible tail. Long background recordings must not grow a
                    // 60fps history indefinitely or repeatedly copy the entire recording.
                    val peaks = ArrayDeque<Float>()
                    while (isActive) {
                        if (!engine.isRecordingActive()) {
                            stop("Recording was interrupted. Any captured audio has been kept.")
                            break
                        }
                        peaks.addLast(engine.getLatestPeakAmplitude())
                        if (peaks.size > MAX_PEAKS) peaks.removeFirst()
                        mutableState.value = state.value.copy(amplitudes = peaks.toList())
                        delay(50)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Capture startup failed", e)
                stop(e.message ?: "Could not start recording.")
            }
        }
        startJob = starting
        starting.start()
    }

    fun foregroundFailed(token: String, reason: String) {
        if (token == state.value.token) stop(reason)
    }

    fun stop(reason: String? = null) {
        if (!state.value.recording) { stopAudition(); return }
        mutableState.value = state.value.copy(phase = CapturePhase.SAVING, countingIn = false, error = reason)
        tickJob?.cancel()
        tickJob = null
        val starting = startJob
        startJob = null
        scope.launch {
            // In particular, wait for awaitFirstBuffer's IO call before closing its stream.
            starting?.cancelAndJoin()
            try {
                engine.setMetronomeEnabled(false)
                engine.pause()
                durationMs = if (ownsInput) engine.stopRecording() else 0L
                ownsInput = false
                mutableState.value = state.value.copy(pendingSave = gateOpened || durationMs > 0)
                if ((gateOpened || durationMs > 0) && state.value.file != null) {
                    save()
                } else {
                    mutableState.value = state.value.copy(
                        phase = if (reason != null) CapturePhase.FAILED else if (group != null) CapturePhase.SAVED else CapturePhase.IDLE,
                        file = state.value.takes.find { it.id == state.value.selectedTakeId }?.let { storage.getAudioFile(it.audioFileName) },
                        amplitudes = emptyList(), error = reason
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture finalization failed; retaining file", e)
                mutableState.value = state.value.copy(phase = CapturePhase.FAILED, pendingSave = state.value.file != null,
                    error = "Could not finish saving. The recording file has been kept.")
            }
        }
    }

    private suspend fun save() {
        val file = state.value.file ?: return
        try {
            // Retry native finalization if a previous stop failed before it returned a duration.
            if (ownsInput) { durationMs = engine.stopRecording(); ownsInput = false }
            val parts = prepared ?: takeWriter.write(file, boundaries.toList()).also { prepared = it }
            // Text creation may still be finishing when the microphone is stopped.
            val ideaId = ideaCreation?.await() ?: state.value.ideaId
            val saved = if (ideaId == null || group != null) repo.saveCaptureBatch(group, parts)
                else repo.saveCaptureBatchForIdea(ideaId, group, parts)
            group = saved.group
            writing?.attachIdea(saved.group.ideaId)
            val newest = saved.takes.last()
            durationMs = 0
            prepared = null
            boundaries.clear()
            mutableState.value = state.value.copy(phase = CapturePhase.SAVED, ideaId = saved.group.ideaId,
                file = parts.last().file, takes = state.value.takes + saved.takes,
                selectedTakeId = newest.id, pendingSave = false, amplitudes = emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Audio saved but idea indexing failed: ${file.name}", e)
            mutableState.value = state.value.copy(phase = CapturePhase.FAILED, pendingSave = true,
                error = "Audio is kept, but saving the idea failed. Press Record to retry saving.")
        }
    }

    private fun retrySave() {
        mutableState.value = state.value.copy(phase = CapturePhase.SAVING, error = null)
        scope.launch { save() }
    }

    fun clearCompleted() {
        if (canStartNewIdea()) {
            stopAudition()
            group = null
            writing = null
            mutableState.value = CaptureState()
        }
    }

    private companion object {
        const val TAG = "CaptureSession"
        const val MAX_PEAKS = 1200
    }
}
