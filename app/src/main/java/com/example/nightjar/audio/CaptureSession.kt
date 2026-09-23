package com.example.nightjar.audio

import android.util.Log
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.repository.CaptureGroup
import com.example.nightjar.data.repository.NotesSession
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.data.db.entity.CaptureGroupEntity
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
    val loadingIdea: Boolean = false,
    val countingIn: Boolean = false,
    val amplitudes: List<Float> = emptyList(),
    val pendingTakeWaveforms: List<List<Float>> = emptyList(),
    val file: File? = null,
    val ideaId: Long? = null,
    val ideaTitle: String? = null,
    val takes: List<TakeEntity> = emptyList(),
    val takeFiles: Map<Long, File> = emptyMap(),
    val groups: List<CaptureGroupEntity> = emptyList(),
    val openGroupId: Long? = null,
    val selectedTakeId: Long? = null,
    val takeNumber: Int = 0,
    val playing: Boolean = false,
    val pendingSave: Boolean = false,
    val error: String? = null
) {
    val recording: Boolean get() = phase == CapturePhase.STARTING || phase == CapturePhase.RECORDING
    val busy: Boolean get() = recording || phase == CapturePhase.SAVING || loadingIdea
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
    private val currentPeaks = ArrayDeque<Float>()
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


    fun openIdea(ideaId: Long) {
        if (state.value.busy || state.value.pendingSave) return
        if (state.value.ideaId == ideaId) {
            scope.launch { refreshGroups() }
            return
        }
        stopAudition()
        group = null
        writing = null
        mutableState.value = CaptureState(ideaId = ideaId, loadingIdea = true)
        scope.launch {
            try {
                if (repo.getIdeaById(ideaId) == null) {
                    mutableState.value = CaptureState(error = "This Idea is no longer available.")
                    return@launch
                }
                writing = notes.open(ideaId)
                refreshGroups()
            } catch (e: Exception) {
                Log.e(TAG, "Could not reopen Idea", e)
                mutableState.value = state.value.copy(loadingIdea = false,
                    error = "Could not open this Idea.")
            }
        }
    }

    private suspend fun refreshGroups(preferredClipId: Long? = group?.clipId) {
        val ideaId = state.value.ideaId ?: return
        val groups = repo.getCaptureGroups(ideaId)
        val open = groups.find { it.clipId == preferredClipId }
            ?: groups.find { it.id == state.value.openGroupId } ?: groups.firstOrNull()
        group = open?.let { CaptureGroup(ideaId, it.clipId) }
        val takes = open?.let { repo.getCaptureTakes(it.clipId) }.orEmpty()
        mutableState.value = state.value.copy(loadingIdea = false,
            ideaTitle = repo.getIdeaById(ideaId)?.title,
            groups = groups, openGroupId = open?.id,
            takes = takes, takeFiles = takes.associate { it.id to storage.getAudioFile(it.audioFileName) },
            selectedTakeId = takes.lastOrNull()?.id,
            file = takes.lastOrNull()?.let { storage.getAudioFile(it.audioFileName) })
    }

    fun createGroup() {
        if (state.value.busy || state.value.pendingSave || state.value.playing) return
        scope.launch {
            try {
                val created = repo.createCaptureGroup(ensureIdeaId())
                refreshGroups(created.clipId)
            } catch (e: Exception) {
                Log.e(TAG, "Could not create recording group", e)
                mutableState.value = state.value.copy(error = "Could not create a group.")
            }
        }
    }

    fun selectGroup(id: Long) {
        if (state.value.busy || state.value.pendingSave || state.value.playing) return
        val target = state.value.groups.find { it.id == id } ?: return
        scope.launch { refreshGroups(target.clipId) }
    }

    fun renameGroup(id: Long, name: String) {
        if (state.value.busy || state.value.pendingSave) return
        scope.launch {
            try { repo.renameCaptureGroup(id, name); refreshGroups() }
            catch (e: Exception) { Log.e(TAG, "Could not rename group", e) }
        }
    }

    fun addGroupToStudio(id: Long) {
        if (state.value.busy || state.value.pendingSave) return
        val target = state.value.groups.find { it.id == id } ?: return
        scope.launch {
            try { repo.addCaptureGroupToStudio(target); refreshGroups() }
            catch (e: Exception) {
                Log.e(TAG, "Could not add group to Studio", e)
                mutableState.value = state.value.copy(error = "Could not add this group to Studio.")
            }
        }
    }

    fun selectTake(id: Long) {
        if (state.value.busy || state.value.pendingSave || state.value.playing) return
        val take = state.value.takes.find { it.id == id } ?: return
        val open = state.value.groups.find { it.id == state.value.openGroupId } ?: return
        scope.launch {
            try {
                repo.latchCaptureTake(open, if (open.latchedTakeId == id) null else id)
                refreshGroups()
            } catch (e: Exception) {
                Log.e(TAG, "Could not select take", e)
                mutableState.value = state.value.copy(error = "Could not select this take.")
            }
        }
    }

    fun stopAudition() {
        if (!state.value.playing) return
        auditionJob?.cancel()
        auditionJob = null
        engine.pause()
        engine.removeAllTracks()
        engine.clearLoopRegion()
        mutableState.value = state.value.copy(playing = false)
    }

    fun playSelected() {
        if (state.value.busy || state.value.pendingSave) return
        if (state.value.playing) return
        val latched = state.value.groups.mapNotNull { it.latchedTakeId }
        if (latched.size > 1) {
            mutableState.value = state.value.copy(error = "Combined playback needs an alignment check. Select one group for now.")
            return
        }
        val takeId = latched.singleOrNull() ?: return
        scope.launch { playLatched(takeId) }
    }

    fun unlatchGroup(id: Long) {
        if (state.value.busy || state.value.pendingSave || state.value.playing) return
        val target = state.value.groups.find { it.id == id } ?: return
        scope.launch {
            try { repo.latchCaptureTake(target, null); refreshGroups() }
            catch (e: Exception) { Log.e(TAG, "Could not clear take selection", e) }
        }
    }

    private suspend fun playLatched(takeId: Long) {
        val take = repo.getCaptureTake(takeId) ?: run {
            mutableState.value = state.value.copy(error = "This take is no longer available.")
            return
        }
        if (take.durationMs <= 0) return
        try {
            engine.pause()
            engine.removeAllTracks()
            engine.clearLoopRegion()
            engine.setCountIn(0, 4)
            engine.setMetronomeEnabled(false)
            check(engine.addTrack(-1, storage.getAudioFile(take.audioFileName).absolutePath,
                take.durationMs, 0, 0, 0, 1f, false)) { "Could not play this take." }
            engine.setLoopRegion(0, take.durationMs)
            engine.seekTo(0)
            engine.play()
            mutableState.value = state.value.copy(playing = true, error = null)
            auditionJob = scope.launch {
                while (isActive) {
                    delay(50)
                    engine.pollState()
                    if (!engine.isPlaying.value) {
                        stopAudition()
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Take audition failed", e)
            engine.pause()
            engine.removeAllTracks()
            engine.clearLoopRegion()
            mutableState.value = state.value.copy(playing = false, error = e.message ?: "Could not play this take.")
        }
    }

    fun start(options: CaptureOptions) {
        if (state.value.phase == CapturePhase.RECORDING) {
            boundaries.add(engine.getRecordedDurationMs().coerceAtLeast(boundaries.lastOrNull() ?: 0))
            val thumbnail = state.value.amplitudes.filterIndexed { index, _ -> index % 10 == 0 }
            currentPeaks.clear()
            mutableState.value = state.value.copy(takeNumber = state.value.takeNumber + 1,
                pendingTakeWaveforms = state.value.pendingTakeWaveforms + listOf(thumbnail),
                amplitudes = emptyList())
            return
        }
        if (state.value.busy) return
        if (state.value.playing) {
            mutableState.value = state.value.copy(
                error = "Stop playback before recording. Backing capture needs an alignment check."
            )
            return
        }
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
        currentPeaks.clear()
        gateOpened = false
        prepared = null
        this.options = options
        durationMs = 0
        val token = UUID.randomUUID().toString()
        mutableState.value = state.value.copy(token = token, phase = CapturePhase.STARTING,
            file = null, amplitudes = emptyList(), pendingTakeWaveforms = emptyList(),
            error = null, takeNumber = state.value.takes.size + 1)
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
                    while (isActive) {
                        if (!engine.isRecordingActive()) {
                            stop("Recording was interrupted. Any captured audio has been kept.")
                            break
                        }
                        currentPeaks.addLast(engine.getLatestPeakAmplitude())
                        if (currentPeaks.size > MAX_PEAKS) currentPeaks.removeFirst()
                        mutableState.value = state.value.copy(amplitudes = currentPeaks.toList())
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
                selectedTakeId = newest.id, pendingSave = false, amplitudes = emptyList(),
                pendingTakeWaveforms = emptyList())
            refreshGroups(saved.group.clipId)
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
