package com.example.nightjar.ui.studio

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.WavRecorder
import com.example.nightjar.data.repository.StudioRepository
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.storage.RecordingStorage
import com.example.nightjar.player.StudioPlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the Studio (multi-track workspace) screen.
 *
 * Coordinates overdub recording via [WavRecorder], multi-track playback
 * via [StudioPlaybackManager], and timeline editing (drag-to-reposition,
 * trim). Recording synchronization follows this protocol:
 *
 * 1. Start [WavRecorder] and wait for the audio pipeline to become hot.
 * 2. Start playback and wait for audio to actually render to the speaker.
 * 3. Open the WAV write gate — audio is captured in sync with playback.
 * 4. On stop, save the new track at the captured timeline offset.
 */
@HiltViewModel
class StudioViewModel @Inject constructor(
    private val ideaRepo: IdeaRepository,
    private val studioRepo: StudioRepository,
    private val playbackManager: StudioPlaybackManager,
    private val wavRecorder: WavRecorder,
    private val recordingStorage: RecordingStorage
) : ViewModel() {

    private var currentIdeaId: Long? = null

    companion object {
        private const val TAG = "StudioVM"
        private const val MIN_EFFECTIVE_DURATION_MS = 200L
        private const val MIN_LOOP_DURATION_MS = 500L
    }

    private val _state = MutableStateFlow(StudioUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<StudioEffect>()
    val effects = _effects.asSharedFlow()

    private var recordingStartGlobalMs: Long = 0L
    private var recordingStartNanos: Long = 0L
    private var recordingTickJob: Job? = null
    private var recordingFile: File? = null

    init {
        playbackManager.setScope(viewModelScope)

        viewModelScope.launch {
            playbackManager.isPlaying.collect { playing ->
                _state.update { it.copy(isPlaying = playing) }
            }
        }
        viewModelScope.launch {
            playbackManager.globalPositionMs.collect { posMs ->
                _state.update { it.copy(globalPositionMs = posMs) }
            }
        }
        viewModelScope.launch {
            playbackManager.totalDurationMs.collect { durMs ->
                _state.update { it.copy(totalDurationMs = durMs) }
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
            StudioAction.Play -> playbackManager.play()
            StudioAction.Pause -> playbackManager.pause()
            is StudioAction.SeekTo -> playbackManager.seekTo(action.positionMs)
            is StudioAction.SeekFinished -> playbackManager.seekTo(action.positionMs)

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

            // Track settings
            is StudioAction.OpenTrackSettings -> {
                _state.update { it.copy(settingsTrackId = action.trackId) }
            }
            StudioAction.DismissTrackSettings -> {
                _state.update { it.copy(settingsTrackId = null) }
            }
            is StudioAction.SetTrackMuted -> setTrackMuted(action.trackId, action.muted)
            is StudioAction.SetTrackVolume -> setTrackVolume(action.trackId, action.volume)

            // Loop
            is StudioAction.SetLoopRegion -> setLoopRegion(action.startMs, action.endMs)
            StudioAction.ClearLoopRegion -> clearLoopRegion()
            StudioAction.ToggleLoop -> toggleLoop()
            is StudioAction.UpdateLoopRegionStart -> updateLoopRegionStart(action.startMs)
            is StudioAction.UpdateLoopRegionEnd -> updateLoopRegionEnd(action.endMs)
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

                playbackManager.prepare(tracks, ::getAudioFile)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to load project."
                _state.update { it.copy(isLoading = false, errorMessage = msg) }
                _effects.emit(StudioEffect.ShowError(msg))
            }
        }
    }

    private fun startOverdubRecording() {
        val ideaId = currentIdeaId ?: return

        // Disable looping during recording — Step C will change this
        if (_state.value.isLoopEnabled) {
            _state.update { it.copy(isLoopEnabled = false) }
            playbackManager.clearLoopRegion()
        }

        viewModelScope.launch {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = recordingStorage.getAudioFile("nightjar_overdub_$ts.wav")
                recordingFile = file

                // Capture the user's intended start position BEFORE any
                // startup latency shifts the clock forward.
                val intendedStartMs = playbackManager.globalPositionMs.value

                wavRecorder.start(file)

                // Wait for the audio pipeline to deliver its first buffer,
                // confirming the mic is actually capturing. Buffers read
                // before markWriting() are discarded to avoid pre-roll desync.
                wavRecorder.awaitFirstBuffer()

                // Start playback, then wait for ExoPlayer to actually render
                // audio to the speaker. awaitPlaybackRendering() resets the
                // monotonic clock when rendering is confirmed, eliminating
                // startup latency from the timeline position.
                playbackManager.setRecording(true)
                playbackManager.play()
                playbackManager.awaitPlaybackRendering()

                // Open the write gate — the WAV captures from the moment
                // playback is audible. Use the intended start position
                // (before startup latency), not the clock position.
                wavRecorder.markWriting()
                recordingStartGlobalMs = intendedStartMs
                recordingStartNanos = System.nanoTime()

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

        playbackManager.setRecording(false)
        playbackManager.pause()
        recordingTickJob?.cancel()
        recordingTickJob = null

        val result = wavRecorder.stop()
        Log.d(TAG, "Recording stopped: file=${result?.file?.name}, " +
            "durationMs=${result?.durationMs}, offsetMs=$recordingStartGlobalMs")

        _state.update { it.copy(isRecording = false, recordingElapsedMs = 0L) }

        if (result == null) {
            viewModelScope.launch {
                _effects.emit(StudioEffect.ShowError("Recording failed — no audio captured."))
            }
            return
        }

        viewModelScope.launch {
            try {
                studioRepo.addTrack(
                    ideaId = ideaId,
                    audioFile = result.file,
                    durationMs = result.durationMs,
                    offsetMs = recordingStartGlobalMs
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
            // When the left trim moves, the visible block shifts right on the
            // timeline.  Adjust offsetMs by the same delta so the audio samples
            // keep their global-timeline positions after commit.
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
                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to update track."))
            }
        }
    }

    private fun setTrackVolume(trackId: Long, volume: Float) {
        viewModelScope.launch {
            try {
                studioRepo.setTrackVolume(trackId, volume.coerceIn(0f, 1f))
                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(StudioEffect.ShowError(e.message ?: "Failed to update volume."))
            }
        }
    }

    // ── Delete ─────────────────────────────────────────────────────────

    private fun executeDeleteTrack() {
        val trackId = _state.value.confirmingDeleteTrackId ?: return
        _state.update { it.copy(confirmingDeleteTrackId = null) }

        viewModelScope.launch {
            try {
                playbackManager.pause()
                studioRepo.deleteTrackAndAudio(trackId)
                reloadAndPrepare()
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
        // Ensure minimum duration
        if (cEnd - cStart < MIN_LOOP_DURATION_MS) return
        _state.update { it.copy(loopStartMs = cStart, loopEndMs = cEnd, isLoopEnabled = true) }
        playbackManager.setLoopRegion(cStart, cEnd)
    }

    private fun clearLoopRegion() {
        _state.update { it.copy(loopStartMs = null, loopEndMs = null, isLoopEnabled = false) }
        playbackManager.clearLoopRegion()
    }

    private fun toggleLoop() {
        val current = _state.value
        if (current.isLoopEnabled) {
            // Disable looping but keep the region visible
            _state.update { it.copy(isLoopEnabled = false) }
            playbackManager.clearLoopRegion()
        } else {
            // Enable looping — use existing region or default to full timeline
            val start = current.loopStartMs ?: 0L
            val end = current.loopEndMs ?: current.totalDurationMs
            if (end - start < MIN_LOOP_DURATION_MS) return
            _state.update { it.copy(loopStartMs = start, loopEndMs = end, isLoopEnabled = true) }
            playbackManager.setLoopRegion(start, end)
        }
    }

    private fun updateLoopRegionStart(startMs: Long) {
        val current = _state.value
        val end = current.loopEndMs ?: return
        val clamped = startMs.coerceIn(0L, (end - MIN_LOOP_DURATION_MS).coerceAtLeast(0L))
        _state.update { it.copy(loopStartMs = clamped) }
        if (current.isLoopEnabled) {
            playbackManager.setLoopRegion(clamped, end)
        }
    }

    private fun updateLoopRegionEnd(endMs: Long) {
        val current = _state.value
        val start = current.loopStartMs ?: return
        val total = current.totalDurationMs
        val clamped = endMs.coerceIn(start + MIN_LOOP_DURATION_MS, total)
        _state.update { it.copy(loopEndMs = clamped) }
        if (current.isLoopEnabled) {
            playbackManager.setLoopRegion(start, clamped)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private suspend fun reloadAndPrepare() {
        val ideaId = currentIdeaId ?: return
        val tracks = studioRepo.getTracks(ideaId)
        _state.update { it.copy(tracks = tracks) }
        playbackManager.prepare(tracks, ::getAudioFile)

        // Clamp loop region if total duration changed
        val total = playbackManager.totalDurationMs.value
        val current = _state.value
        if (current.loopStartMs != null && current.loopEndMs != null) {
            val clampedEnd = current.loopEndMs.coerceAtMost(total)
            val clampedStart = current.loopStartMs.coerceAtMost(
                (clampedEnd - MIN_LOOP_DURATION_MS).coerceAtLeast(0L)
            )
            if (clampedEnd - clampedStart < MIN_LOOP_DURATION_MS) {
                // Region no longer valid — clear it
                clearLoopRegion()
            } else if (clampedStart != current.loopStartMs || clampedEnd != current.loopEndMs) {
                _state.update { it.copy(loopStartMs = clampedStart, loopEndMs = clampedEnd) }
                if (current.isLoopEnabled) {
                    playbackManager.setLoopRegion(clampedStart, clampedEnd)
                }
            } else if (current.isLoopEnabled) {
                // Re-sync after prepare (prepare resets playback manager state)
                playbackManager.setLoopRegion(clampedStart, clampedEnd)
            }
        }
    }

    fun getAudioFile(name: String): File = recordingStorage.getAudioFile(name)

    override fun onCleared() {
        super.onCleared()
        if (wavRecorder.isActive()) {
            wavRecorder.stop()
        }
        wavRecorder.release()
        playbackManager.release()
    }
}
