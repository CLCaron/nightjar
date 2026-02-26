package com.example.nightjar.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.storage.RecordingStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * ViewModel for the Record screen.
 *
 * Manages the record → stop → post-recording → navigate flow.
 * After stopping, the user stays on the Record screen and can choose
 * to open Overview, open Studio, or start a new recording.
 * Handles lifecycle edge cases like the app being backgrounded
 * mid-recording.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    private val audioEngine: OboeAudioEngine,
    private val recordingStorage: RecordingStorage,
    private val repo: IdeaRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RecordUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<RecordEffect>()
    val effects = _effects.asSharedFlow()

    private var startJob: Job? = null
    private var amplitudeTickJob: Job? = null
    private val amplitudeBuffer = ArrayList<Float>()
    private var recordingFile: File? = null

    fun onAction(action: RecordAction) {
        when (action) {
            RecordAction.StartRecording -> startRecording()
            RecordAction.StopAndSave -> stopAndSave()
            RecordAction.StopForBackground -> stopForBackground()
            RecordAction.GoToOverview -> goToOverview()
            RecordAction.GoToStudio -> goToStudio()
            RecordAction.CreateWriteIdea -> createWriteIdea()
            RecordAction.CreateStudioIdea -> createStudioIdea()
        }
    }

    fun startRecording() {
        // Clear any post-recording state — starting fresh
        amplitudeBuffer.clear()
        recordingFile = null
        _state.value = RecordUiState(isRecording = true)
        startJob = viewModelScope.launch {
            try {
                val file = recordingStorage.createRecordingFile()
                recordingFile = file
                val started = audioEngine.startRecording(file.absolutePath)
                if (!started) {
                    recordingFile = null
                    _state.value = _state.value.copy(
                        isRecording = false,
                        errorMessage = "Failed to start recording."
                    )
                    return@launch
                }
                audioEngine.awaitFirstBuffer()
                audioEngine.openWriteGate()
                startAmplitudeTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordingFile = null
                _state.value = _state.value.copy(
                    isRecording = false,
                    errorMessage = e.message ?: "Failed to start recording."
                )
            }
        }
    }

    private fun startAmplitudeTicker() {
        amplitudeTickJob?.cancel()
        amplitudeTickJob = viewModelScope.launch {
            while (isActive) {
                val peak = audioEngine.getLatestPeakAmplitude()
                amplitudeBuffer.add(peak)
                _state.value = _state.value.copy(
                    liveAmplitudes = amplitudeBuffer.toFloatArray()
                )
                delay(AMPLITUDE_TICK_MS)
            }
        }
    }

    private fun stopAmplitudeTicker() {
        amplitudeTickJob?.cancel()
        amplitudeTickJob = null
    }

    private fun stopAndSave() {
        stopAmplitudeTicker()
        startJob?.cancel()
        startJob = null
        _state.value = _state.value.copy(errorMessage = null)

        val file = recordingFile
        recordingFile = null

        val durationMs = try {
            audioEngine.stopRecording()
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to stop/save recording."
            _state.value = _state.value.copy(isRecording = false, errorMessage = msg)
            viewModelScope.launch { _effects.emit(RecordEffect.ShowError(msg)) }
            return
        }

        _state.value = _state.value.copy(isRecording = false)

        if (durationMs <= 0 || file == null) {
            _state.value = _state.value.copy(errorMessage = "Failed to save audio.")
            return
        }

        viewModelScope.launch {
            try {
                val ideaId = repo.createIdeaWithTrack(file, durationMs)
                _state.value = _state.value.copy(
                    liveAmplitudes = FloatArray(0),
                    postRecording = PostRecordingState(
                        ideaId = ideaId,
                        audioFile = file
                    )
                )
            } catch (e: Exception) {
                val msg = e.message ?: "Saved audio, but failed to create idea."
                _state.value = _state.value.copy(errorMessage = msg)
                _effects.emit(RecordEffect.ShowError(msg))
            }
        }
    }

    private fun stopForBackground() {
        if (!_state.value.isRecording) return

        stopAmplitudeTicker()
        startJob?.cancel()
        startJob = null

        val file = recordingFile
        recordingFile = null

        val durationMs = audioEngine.stopRecording()
        _state.value = _state.value.copy(isRecording = false, liveAmplitudes = FloatArray(0))

        if (durationMs > 0 && file != null) {
            viewModelScope.launch {
                try {
                    val ideaId = repo.createIdeaWithTrack(file, durationMs)
                    _state.value = _state.value.copy(
                        postRecording = PostRecordingState(
                            ideaId = ideaId,
                            audioFile = file
                        )
                    )
                } catch (_: Exception) {
                    // Best-effort — file is saved on disk even if DB insert fails
                }
            }
        }
    }

    private fun goToOverview() {
        val post = _state.value.postRecording ?: return
        _state.value = RecordUiState() // reset to idle
        viewModelScope.launch { _effects.emit(RecordEffect.OpenOverview(post.ideaId)) }
    }

    private fun goToStudio() {
        val post = _state.value.postRecording ?: return
        _state.value = RecordUiState() // reset to idle
        viewModelScope.launch { _effects.emit(RecordEffect.OpenStudio(post.ideaId)) }
    }

    private fun createWriteIdea() {
        viewModelScope.launch {
            try {
                val ideaId = repo.createEmptyIdea()
                _effects.emit(RecordEffect.OpenOverview(ideaId))
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to create idea."
                _effects.emit(RecordEffect.ShowError(msg))
            }
        }
    }

    private fun createStudioIdea() {
        viewModelScope.launch {
            try {
                val ideaId = repo.createEmptyIdea()
                _effects.emit(RecordEffect.OpenStudio(ideaId))
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to create idea."
                _effects.emit(RecordEffect.ShowError(msg))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAmplitudeTicker()
        startJob?.cancel()
        // Engine is app-scoped singleton — don't release it here.
        // Just stop recording if it's still in progress.
        if (audioEngine.isRecordingActive()) {
            audioEngine.stopRecording()
        }
    }

    private companion object {
        const val AMPLITUDE_TICK_MS = 16L // ~60fps
    }
}
