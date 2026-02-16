package com.example.nightjar.ui.explore

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

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val ideaRepo: IdeaRepository,
    private val exploreRepo: ExploreRepository,
    private val playbackManager: ExplorePlaybackManager,
    private val wavRecorder: WavRecorder,
    private val recordingStorage: RecordingStorage
) : ViewModel() {

    private var currentIdeaId: Long? = null

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

        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = recordingStorage.getAudioFile("nightjar_overdub_$ts.wav")
            recordingFile = file

            recordingStartGlobalMs = _state.value.globalPositionMs
            recordingStartNanos = System.nanoTime()

            wavRecorder.start(file)
            playbackManager.play()

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
            viewModelScope.launch {
                _effects.emit(
                    ExploreEffect.ShowError(e.message ?: "Failed to start recording.")
                )
            }
        }
    }

    private fun stopOverdubRecording() {
        val ideaId = currentIdeaId ?: return

        playbackManager.pause()
        recordingTickJob?.cancel()
        recordingTickJob = null

        val result = wavRecorder.stop()

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

                val tracks = exploreRepo.getTracks(ideaId)
                _state.update { it.copy(tracks = tracks) }
                playbackManager.prepare(tracks, ::getAudioFile)
            } catch (e: Exception) {
                _effects.emit(
                    ExploreEffect.ShowError(e.message ?: "Failed to save track.")
                )
            }
        }
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
