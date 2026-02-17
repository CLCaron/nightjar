package com.example.nightjar.ui.explore

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.WavRecorder
import com.example.nightjar.data.repository.ExploreRepository
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.storage.RecordingStorage
import com.example.nightjar.player.ExplorePlaybackManager
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
 * ViewModel for the Explore (multi-track workspace) screen.
 *
 * Coordinates overdub recording via [WavRecorder], multi-track playback
 * via [ExplorePlaybackManager], and timeline editing (drag-to-reposition,
 * trim). Recording synchronization follows this protocol:
 *
 * 1. Start [WavRecorder] and wait for the audio pipeline to become hot.
 * 2. Start playback and wait for audio to actually render to the speaker.
 * 3. Open the WAV write gate — audio is captured in sync with playback.
 * 4. On stop, save the new track at the captured timeline offset.
 */
@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val ideaRepo: IdeaRepository,
    private val exploreRepo: ExploreRepository,
    private val playbackManager: ExplorePlaybackManager,
    private val wavRecorder: WavRecorder,
    private val recordingStorage: RecordingStorage
) : ViewModel() {

    private var currentIdeaId: Long? = null

    companion object {
        private const val TAG = "ExploreVM"
        private const val MIN_EFFECTIVE_DURATION_MS = 200L
    }

    private val _state = MutableStateFlow(ExploreUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ExploreEffect>()
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

    fun onAction(action: ExploreAction) {
        when (action) {
            is ExploreAction.Load -> load(action.ideaId)
            ExploreAction.ShowAddTrackSheet -> {
                _state.update { it.copy(showAddTrackSheet = true) }
            }
            ExploreAction.DismissAddTrackSheet -> {
                _state.update { it.copy(showAddTrackSheet = false) }
            }
            is ExploreAction.SelectNewTrackType -> {
                _state.update { it.copy(showAddTrackSheet = false) }
                when (action.type) {
                    NewTrackType.AUDIO_RECORDING -> {
                        viewModelScope.launch {
                            _effects.emit(ExploreEffect.RequestMicPermission)
                        }
                    }
                }
            }
            ExploreAction.MicPermissionGranted -> startOverdubRecording()
            ExploreAction.StopOverdubRecording -> stopOverdubRecording()
            ExploreAction.Play -> playbackManager.play()
            ExploreAction.Pause -> playbackManager.pause()
            is ExploreAction.SeekTo -> playbackManager.seekTo(action.positionMs)
            is ExploreAction.SeekFinished -> playbackManager.seekTo(action.positionMs)

            // Drag-to-reposition
            is ExploreAction.StartDragTrack -> startDragTrack(action.trackId)
            is ExploreAction.UpdateDragTrack -> updateDragTrack(action.previewOffsetMs)
            is ExploreAction.FinishDragTrack -> finishDragTrack(action.trackId, action.newOffsetMs)
            ExploreAction.CancelDrag -> cancelDrag()

            // Trim
            is ExploreAction.StartTrim -> startTrim(action.trackId, action.edge)
            is ExploreAction.UpdateTrim -> updateTrim(
                action.previewTrimStartMs,
                action.previewTrimEndMs
            )
            is ExploreAction.FinishTrim -> finishTrim(
                action.trackId,
                action.trimStartMs,
                action.trimEndMs
            )
            ExploreAction.CancelTrim -> cancelTrim()
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

                val tracks = exploreRepo.ensureProjectInitialized(ideaId)

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
                _effects.emit(ExploreEffect.ShowError(msg))
            }
        }
    }

    private fun startOverdubRecording() {
        val ideaId = currentIdeaId ?: return

        viewModelScope.launch {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = recordingStorage.getAudioFile("nightjar_overdub_$ts.wav")
                recordingFile = file

                wavRecorder.start(file)

                // Wait for the audio pipeline to deliver its first buffer,
                // confirming the mic is actually capturing. Buffers read
                // before markWriting() are discarded to avoid pre-roll desync.
                wavRecorder.awaitFirstBuffer()

                // Start playback FIRST, then wait for ExoPlayer to actually
                // begin rendering audio to the speaker. This avoids the desync
                // where the WAV accumulates silence while ExoPlayer is still
                // buffering/starting up.
                playbackManager.setRecording(true)
                playbackManager.play()
                playbackManager.awaitPlaybackRendering()

                // NOW open the write gate — the WAV only captures audio from
                // the moment playback is audible, keeping the overdub in sync.
                wavRecorder.markWriting()
                recordingStartGlobalMs = playbackManager.globalPositionMs.value
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
                    ExploreEffect.ShowError(e.message ?: "Failed to start recording.")
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
                _effects.emit(ExploreEffect.ShowError("Recording failed — no audio captured."))
            }
            return
        }

        viewModelScope.launch {
            try {
                exploreRepo.addTrack(
                    ideaId = ideaId,
                    audioFile = result.file,
                    durationMs = result.durationMs,
                    offsetMs = recordingStartGlobalMs
                )

                reloadAndPrepare()
            } catch (e: Exception) {
                _effects.emit(
                    ExploreEffect.ShowError(e.message ?: "Failed to save track.")
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
            exploreRepo.moveTrack(trackId, newOffsetMs.coerceAtLeast(0L))
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
        _state.update { it.copy(trimState = null) }
        viewModelScope.launch {
            exploreRepo.trimTrack(trackId, trimStartMs, trimEndMs)
            reloadAndPrepare()
        }
    }

    private fun cancelTrim() {
        _state.update { it.copy(trimState = null) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private suspend fun reloadAndPrepare() {
        val ideaId = currentIdeaId ?: return
        val tracks = exploreRepo.getTracks(ideaId)
        _state.update { it.copy(tracks = tracks) }
        playbackManager.prepare(tracks, ::getAudioFile)
    }

    fun getAudioFile(name: String): File = ideaRepo.getAudioFile(name)

    override fun onCleared() {
        super.onCleared()
        if (wavRecorder.isActive()) {
            wavRecorder.stop()
        }
        wavRecorder.release()
        playbackManager.release()
    }
}
