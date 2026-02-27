package com.example.nightjar.ui.studio

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.AudioLatencyEstimator
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.data.repository.StudioRepository
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.storage.RecordingStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the Studio (multi-track workspace) screen.
 *
 * Coordinates overdub recording and multi-track playback via [OboeAudioEngine],
 * and timeline editing (drag-to-reposition, trim).
 *
 * Recording synchronization follows this protocol:
 * 1. Start Oboe recording and wait for the audio pipeline to become hot.
 * 2. Open write gate and start playback.
 * 3. Measure pre-roll for latency compensation.
 * 4. On stop, save the new track at the captured timeline offset.
 */
@HiltViewModel
class StudioViewModel @Inject constructor(
    private val ideaRepo: IdeaRepository,
    private val studioRepo: StudioRepository,
    private val audioEngine: OboeAudioEngine,
    private val recordingStorage: RecordingStorage,
    private val latencyEstimator: AudioLatencyEstimator
) : ViewModel() {

    private var currentIdeaId: Long? = null

    companion object {
        private const val TAG = "StudioVM"
        private const val MIN_EFFECTIVE_DURATION_MS = 200L
        private const val MIN_LOOP_DURATION_MS = 500L
        private const val TICK_MS = 16L // ~60fps
    }

    private val _state = MutableStateFlow(StudioUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<StudioEffect>()
    val effects = _effects.asSharedFlow()

    private var recordingStartGlobalMs: Long = 0L
    private var recordingStartNanos: Long = 0L
    private var recordingTrimStartMs: Long = 0L
    private var recordingTickJob: Job? = null
    private var recordingFile: File? = null
    private var tickJob: Job? = null

    init {
        startTick()
    }

    /** Poll native engine state at ~60fps and update UI StateFlows. */
    private fun startTick() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                audioEngine.pollState()
                _state.update {
                    it.copy(
                        isPlaying = audioEngine.isPlaying.value,
                        globalPositionMs = audioEngine.positionMs.value,
                        totalDurationMs = audioEngine.totalDurationMs.value
                    )
                }
                delay(TICK_MS)
            }
        }
    }

    fun onAction(action: StudioAction) {
        when (action) {
            is StudioAction.Load -> load(action.ideaId)
            StudioAction.ShowAddTrackSheet -> {
                _state.update { it.copy(showAddTrackSheet = true) }
            }
            StudioAction.DismissAddTrackSheet -> {
                _state.update { it.copy(showAddTrackSheet = false) }
            }
            is StudioAction.SelectNewTrackType -> {
                _state.update { it.copy(showAddTrackSheet = false) }
                when (action.type) {
                    NewTrackType.AUDIO_RECORDING -> {
                        viewModelScope.launch {
                            _effects.emit(StudioEffect.RequestMicPermission)
                        }
                    }
                }
            }
            StudioAction.MicPermissionGranted -> startOverdubRecording()
            StudioAction.StopOverdubRecording -> stopOverdubRecording()
            StudioAction.Play -> audioEngine.play()
            StudioAction.Pause -> audioEngine.pause()
            is StudioAction.SeekTo -> audioEngine.seekTo(action.positionMs)
            is StudioAction.SeekFinished -> audioEngine.seekTo(action.positionMs)

            // Drag-to-reposition
            is StudioAction.StartDragTrack -> startDragTrack(action.trackId)
            is StudioAction.UpdateDragTrack -> updateDragTrack(action.previewOffsetMs)
            is StudioAction.FinishDragTrack -> finishDragTrack(action.trackId, action.newOffsetMs)
            StudioAction.CancelDrag -> cancelDrag()

            // Trim
            is StudioAction.StartTrim -> startTrim(action.trackId, action.edge)
            is StudioAction.UpdateTrim -> updateTrim(
                action.previewTrimStartMs,
                action.previewTrimEndMs
            )
            is StudioAction.FinishTrim -> finishTrim(
                action.trackId,
                action.trimStartMs,
                action.trimEndMs
            )
            StudioAction.CancelTrim -> cancelTrim()

            // Delete
            is StudioAction.ConfirmDeleteTrack -> {
                _state.update { it.copy(confirmingDeleteTrackId = action.trackId) }
            }
            StudioAction.DismissDeleteTrack -> {
                _state.update { it.copy(confirmingDeleteTrackId = null) }
            }
            StudioAction.ExecuteDeleteTrack -> executeDeleteTrack()

            // Track drawer (inline settings panel) — toggle open/closed
            is StudioAction.OpenTrackSettings -> {
                _state.update {
                    val newSet = if (action.trackId in it.expandedTrackIds) {
                        it.expandedTrackIds - action.trackId
                    } else {
                        it.expandedTrackIds + action.trackId
                    }
                    it.copy(expandedTrackIds = newSet)
                }
            }
            is StudioAction.SetTrackMuted -> setTrackMuted(action.trackId, action.muted)
            is StudioAction.SetTrackVolume -> setTrackVolume(action.trackId, action.volume)
            is StudioAction.ToggleSolo -> toggleSolo(action.trackId)

            // Loop
            is StudioAction.SetLoopRegion -> setLoopRegion(action.startMs, action.endMs)
            StudioAction.ClearLoopRegion -> clearLoopRegion()
            StudioAction.ToggleLoop -> toggleLoop()
            is StudioAction.UpdateLoopRegionStart -> updateLoopRegionStart(action.startMs)
            is StudioAction.UpdateLoopRegionEnd -> updateLoopRegionEnd(action.endMs)

            // Latency setup
            StudioAction.ShowLatencySetup -> {
                val diagnostics = latencyEstimator.getDiagnostics()
                _state.update {
                    it.copy(
                        showLatencySetupDialog = true,
                        latencyDiagnostics = diagnostics,
                        manualOffsetMs = diagnostics.manualOffsetMs
                    )
                }
            }
            StudioAction.DismissLatencySetup -> {
                _state.update { it.copy(showLatencySetupDialog = false) }
            }
            is StudioAction.SetManualOffset -> {
                latencyEstimator.saveManualOffsetMs(action.offsetMs)
                _state.update { it.copy(manualOffsetMs = action.offsetMs) }
            }
            StudioAction.ClearManualOffset -> {
                latencyEstimator.clearManualOffset()
                _state.update { it.copy(manualOffsetMs = 0L) }
            }
        }
    }

    private fun load(ideaId: Long) {
        if (currentIdeaId == ideaId) return
        currentIdeaId = ideaId

        viewModelScope.launch {
            try {
                val idea = ideaRepo.getIdeaById(ideaId)
                if (idea == null) {
                    _state.update {
                        it.copy(isLoading = false, errorMessage = "Idea not found.")
                    }
                    return@launch
                }

                val tracks = studioRepo.ensureProjectInitialized(ideaId)

                _state.update {
                    it.copy(
                        ideaTitle = idea.title,
                        tracks = tracks,
                        isLoading = false,
                        errorMessage = null
                    )
                }

                loadTracksIntoEngine(tracks)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to load project."
                _state.update { it.copy(isLoading = false, errorMessage = msg) }
                _effects.emit(StudioEffect.ShowError(msg))
            }
        }
    }

    private fun loadTracksIntoEngine(tracks: List<com.example.nightjar.data.db.entity.TrackEntity>) {
        audioEngine.removeAllTracks()
        for (track in tracks) {
            val file = getAudioFile(track.audioFileName)
            audioEngine.addTrack(
                trackId = track.id.toInt(),
                filePath = file.absolutePath,
                durationMs = track.durationMs,
                offsetMs = track.offsetMs,
                trimStartMs = track.trimStartMs,
                trimEndMs = track.trimEndMs,
                volume = track.volume,
                isMuted = track.isMuted
            )
        }
    }

    private fun startOverdubRecording() {
        val ideaId = currentIdeaId ?: return

        // Disable looping during recording — Step C will change this
        if (_state.value.isLoopEnabled) {
            _state.update { it.copy(isLoopEnabled = false) }
            audioEngine.clearLoopRegion()
        }

        viewModelScope.launch {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = recordingStorage.getAudioFile("nightjar_overdub_$ts.wav")
                recordingFile = file

                // Capture the user's intended start position BEFORE any
                // startup latency shifts the clock forward.
                val intendedStartMs = audioEngine.positionMs.value

                // ── Pre-roll capture protocol ────────────────────────────
                // 1. Start Oboe recording and wait for pipeline to become hot
                val started = audioEngine.startRecording(file.absolutePath)
                if (!started) {
                    _effects.emit(StudioEffect.ShowError("Failed to start recording."))
                    return@launch
                }
                audioEngine.awaitFirstBuffer()

                // 2. Open write gate BEFORE playback — captures pre-roll
                //    silence that serves as a safety buffer for compensation
                audioEngine.openWriteGate()
                val writeGateNanos = System.nanoTime()

                // 3. Start playback
                val hasPlayableTracks = _state.value.tracks.any { !it.isMuted }
                audioEngine.setRecording(true)
                audioEngine.play()

                // 4. Measure pre-roll and compute total compensation
                // With Oboe, playback starts nearly instantly from the audio
                // callback. The pre-roll is just the time between write gate
                // and play.
                val preRollMs = (System.nanoTime() - writeGateNanos) / 1_000_000L
                val compensation = latencyEstimator.computeCompensationMs(
                    preRollMs = preRollMs,
                    hasPlayableTracks = hasPlayableTracks
                )

                recordingStartGlobalMs = intendedStartMs
                recordingTrimStartMs = compensation
                recordingStartNanos = System.nanoTime()

                Log.d(TAG, "Overdub started: intendedStart=${intendedStartMs}ms, " +
                    "preRoll=${preRollMs}ms, compensation=${compensation}ms, " +
                    "hasPlayableTracks=$hasPlayableTracks")

                _state.update {
                    it.copy(isRecording = true, recordingElapsedMs = 0L)
                }

                recordingTickJob = viewModelScope.launch {
                    while (true) {
                        delay(100L)
                        val elapsed = (System.nanoTime() - recordingStartNanos) / 1_000_000L
                        _state.update { it.copy(recordingElapsedMs = elapsed) }
                    }
                }
            } catch (e: Exception) {
                _effects.emit(
                    StudioEffect.ShowError(e.message ?: "Failed to start recording.")
                )
            }
        }
    }

    private fun stopOverdubRecording() {
        val ideaId = currentIdeaId ?: return

        audioEngine.setRecording(false)
        audioEngine.pause()
        recordingTickJob?.cancel()
        recordingTickJob = null

        val durationMs = audioEngine.stopRecording()
        val file = recordingFile
        recordingFile = null

        _state.update { it.copy(isRecording = false, recordingElapsedMs = 0L) }

        if (durationMs <= 0 || file == null) {
            viewModelScope.launch {
                _effects.emit(StudioEffect.ShowError("Recording failed — no audio captured."))
            }
            return
        }

        // Clamp trimStartMs so it never exceeds what would leave less than
        // MIN_EFFECTIVE_DURATION_MS of audible audio.
        val maxTrim = (durationMs - MIN_EFFECTIVE_DURATION_MS).coerceAtLeast(0L)
        val safeTrimStartMs = recordingTrimStartMs.coerceIn(0L, maxTrim)

        Log.d(TAG, "Recording stopped: file=${file.name}, " +
            "durationMs=$durationMs, offsetMs=$recordingStartGlobalMs, " +
            "trimStartMs=$safeTrimStartMs (raw=${recordingTrimStartMs})")

        viewModelScope.launch {
            try {
                studioRepo.addTrack(
                    ideaId = ideaId,
                    audioFile = file,
                    durationMs = durationMs,
                    offsetMs = recordingStartGlobalMs,
                    trimStartMs = safeTrimStartMs
                )

                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(
                    StudioEffect.ShowError(e.message ?: "Failed to save track.")
                )
            }
        }
    }

    // ── Drag-to-reposition ────────────────────────────────────────────────

    private fun startDragTrack(trackId: Long) {
        val track = _state.value.tracks.find { it.id == trackId } ?: return
        _state.update {
            it.copy(
                dragState = TrackDragState(
                    trackId = trackId,
                    originalOffsetMs = track.offsetMs,
                    previewOffsetMs = track.offsetMs
                )
            )
        }
    }

    private fun updateDragTrack(previewOffsetMs: Long) {
        _state.update { st ->
            st.dragState?.let { drag ->
                st.copy(dragState = drag.copy(previewOffsetMs = previewOffsetMs.coerceAtLeast(0L)))
            } ?: st
        }
    }

    private fun finishDragTrack(trackId: Long, newOffsetMs: Long) {
        _state.update { it.copy(dragState = null) }
        viewModelScope.launch {
            studioRepo.moveTrack(trackId, newOffsetMs.coerceAtLeast(0L))
            reloadAndPrepare()
        }
    }

    private fun cancelDrag() {
        _state.update { it.copy(dragState = null) }
    }

    // ── Trim ─────────────────────────────────────────────────────────────

    private fun startTrim(trackId: Long, edge: TrimEdge) {
        val track = _state.value.tracks.find { it.id == trackId } ?: return
        _state.update {
            it.copy(
                trimState = TrackTrimState(
                    trackId = trackId,
                    edge = edge,
                    originalTrimStartMs = track.trimStartMs,
                    originalTrimEndMs = track.trimEndMs,
                    previewTrimStartMs = track.trimStartMs,
                    previewTrimEndMs = track.trimEndMs
                )
            )
        }
    }

    private fun updateTrim(previewTrimStartMs: Long, previewTrimEndMs: Long) {
        _state.update { st ->
            st.trimState?.let { trim ->
                val track = st.tracks.find { it.id == trim.trackId } ?: return@let st
                val maxTrimStart = track.durationMs - previewTrimEndMs - MIN_EFFECTIVE_DURATION_MS
                val maxTrimEnd = track.durationMs - previewTrimStartMs - MIN_EFFECTIVE_DURATION_MS

                val clampedStart = previewTrimStartMs.coerceIn(0L, maxTrimStart.coerceAtLeast(0L))
                val clampedEnd = previewTrimEndMs.coerceIn(0L, maxTrimEnd.coerceAtLeast(0L))

                st.copy(
                    trimState = trim.copy(
                        previewTrimStartMs = clampedStart,
                        previewTrimEndMs = clampedEnd
                    )
                )
            } ?: st
        }
    }

    private fun finishTrim(trackId: Long, trimStartMs: Long, trimEndMs: Long) {
        val track = _state.value.tracks.firstOrNull { it.id == trackId }
        _state.update { it.copy(trimState = null) }
        viewModelScope.launch {
            if (track != null && trimStartMs != track.trimStartMs) {
                val offsetDelta = trimStartMs - track.trimStartMs
                val newOffsetMs = (track.offsetMs + offsetDelta).coerceAtLeast(0L)
                studioRepo.moveTrack(trackId, newOffsetMs)
            }
            studioRepo.trimTrack(trackId, trimStartMs, trimEndMs)
            reloadAndPrepare()
        }
    }

    private fun cancelTrim() {
        _state.update { it.copy(trimState = null) }
    }

    // ── Track settings ─────────────────────────────────────────────────

    private fun setTrackMuted(trackId: Long, muted: Boolean) {
        viewModelScope.launch {
            try {
                studioRepo.setTrackMuted(trackId, muted)
                reloadTracks()
                reapplyEffectiveMute()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to update track."))
            }
        }
    }

    private fun toggleSolo(trackId: Long) {
        _state.update { st ->
            val newSet = if (trackId in st.soloedTrackIds) {
                emptySet()
            } else {
                setOf(trackId)
            }
            st.copy(soloedTrackIds = newSet)
        }
        reapplyEffectiveMute()
    }

    private fun reapplyEffectiveMute() {
        val st = _state.value
        val anySoloed = st.soloedTrackIds.isNotEmpty()
        for (track in st.tracks) {
            val effectivelyMuted = track.isMuted ||
                (anySoloed && track.id !in st.soloedTrackIds)
            audioEngine.setTrackMuted(track.id.toInt(), effectivelyMuted)
        }
    }

    private fun setTrackVolume(trackId: Long, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        // Update native engine immediately for instant feedback
        audioEngine.setTrackVolume(trackId.toInt(), clamped)
        viewModelScope.launch {
            try {
                studioRepo.setTrackVolume(trackId, clamped)
                reloadTracks()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to update volume."))
            }
        }
    }

    // ── Delete ─────────────────────────────────────────────────────────

    private fun executeDeleteTrack() {
        val trackId = _state.value.confirmingDeleteTrackId ?: return
        _state.update {
            it.copy(
                confirmingDeleteTrackId = null,
                expandedTrackIds = it.expandedTrackIds - trackId,
                soloedTrackIds = it.soloedTrackIds - trackId
            )
        }

        viewModelScope.launch {
            try {
                audioEngine.pause()
                studioRepo.deleteTrackAndAudio(trackId)
                reloadAndPrepare()
                reapplyEffectiveMute()
            } catch (e: Exception) {
                _effects.emit(
                    StudioEffect.ShowError(e.message ?: "Failed to delete track.")
                )
            }
        }
    }

    // ── Loop ───────────────────────────────────────────────────────────

    private fun setLoopRegion(startMs: Long, endMs: Long) {
        val total = _state.value.totalDurationMs
        val cStart = startMs.coerceIn(0L, total)
        val cEnd = endMs.coerceIn(0L, total)
        if (cEnd - cStart < MIN_LOOP_DURATION_MS) return
        _state.update { it.copy(loopStartMs = cStart, loopEndMs = cEnd, isLoopEnabled = true) }
        audioEngine.setLoopRegion(cStart, cEnd)
    }

    private fun clearLoopRegion() {
        _state.update { it.copy(loopStartMs = null, loopEndMs = null, isLoopEnabled = false) }
        audioEngine.clearLoopRegion()
    }

    private fun toggleLoop() {
        val current = _state.value
        if (current.isLoopEnabled) {
            _state.update { it.copy(isLoopEnabled = false) }
            audioEngine.clearLoopRegion()
        } else {
            val start = current.loopStartMs ?: 0L
            val end = current.loopEndMs ?: current.totalDurationMs
            if (end - start < MIN_LOOP_DURATION_MS) return
            _state.update { it.copy(loopStartMs = start, loopEndMs = end, isLoopEnabled = true) }
            audioEngine.setLoopRegion(start, end)
        }
    }

    private fun updateLoopRegionStart(startMs: Long) {
        val current = _state.value
        val end = current.loopEndMs ?: return
        val clamped = startMs.coerceIn(0L, (end - MIN_LOOP_DURATION_MS).coerceAtLeast(0L))
        _state.update { it.copy(loopStartMs = clamped) }
        if (current.isLoopEnabled) {
            audioEngine.setLoopRegion(clamped, end)
        }
    }

    private fun updateLoopRegionEnd(endMs: Long) {
        val current = _state.value
        val start = current.loopStartMs ?: return
        val total = current.totalDurationMs
        val clamped = endMs.coerceIn(start + MIN_LOOP_DURATION_MS, total)
        _state.update { it.copy(loopEndMs = clamped) }
        if (current.isLoopEnabled) {
            audioEngine.setLoopRegion(start, clamped)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Reload tracks from DB and re-prepare the native engine. */
    private suspend fun reloadAndPrepare() {
        val ideaId = currentIdeaId ?: return
        val tracks = studioRepo.getTracks(ideaId)
        _state.update { it.copy(tracks = tracks) }
        loadTracksIntoEngine(tracks)

        // Clamp loop region if total duration changed
        val total = audioEngine.totalDurationMs.value
        val current = _state.value
        if (current.loopStartMs != null && current.loopEndMs != null) {
            val clampedEnd = current.loopEndMs.coerceAtMost(total)
            val clampedStart = current.loopStartMs.coerceAtMost(
                (clampedEnd - MIN_LOOP_DURATION_MS).coerceAtLeast(0L)
            )
            if (clampedEnd - clampedStart < MIN_LOOP_DURATION_MS) {
                clearLoopRegion()
            } else if (clampedStart != current.loopStartMs || clampedEnd != current.loopEndMs) {
                _state.update { it.copy(loopStartMs = clampedStart, loopEndMs = clampedEnd) }
                if (current.isLoopEnabled) {
                    audioEngine.setLoopRegion(clampedStart, clampedEnd)
                }
            } else if (current.isLoopEnabled) {
                audioEngine.setLoopRegion(clampedStart, clampedEnd)
            }
        }
    }

    /** Reload tracks from DB without re-preparing the engine. */
    private suspend fun reloadTracks() {
        val ideaId = currentIdeaId ?: return
        val tracks = studioRepo.getTracks(ideaId)
        _state.update { it.copy(tracks = tracks) }
    }

    fun getAudioFile(name: String): File = recordingStorage.getAudioFile(name)

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
        if (audioEngine.isRecordingActive()) {
            audioEngine.stopRecording()
        }
        audioEngine.removeAllTracks()
    }
}
