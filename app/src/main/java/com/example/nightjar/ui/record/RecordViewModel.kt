package com.example.nightjar.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.WavRecorder
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.storage.RecordingStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * ViewModel for the Record screen.
 *
 * Manages the record → stop → save → navigate-to-overview flow.
 * Handles lifecycle edge cases like the app being backgrounded
 * mid-recording.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    private val recorder: WavRecorder,
    private val recordingStorage: RecordingStorage,
    private val repo: IdeaRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RecordUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<RecordEffect>()
    val effects = _effects.asSharedFlow()

    private var startJob: Job? = null

    fun onAction(action: RecordAction) {
        when (action) {
            RecordAction.StartRecording -> startRecording()
            RecordAction.StopAndSave -> stopAndSave()
            RecordAction.StopForBackground -> stopForBackground()
        }
    }

    fun startRecording() {
        _state.value = _state.value.copy(isRecording = true, errorMessage = null)
        startJob = viewModelScope.launch {
            try {
                val file = recordingStorage.createRecordingFile()
                recorder.start(file)
                recorder.awaitFirstBuffer()
                recorder.markWriting()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isRecording = false,
                    errorMessage = e.message ?: "Failed to start recording."
                )
            }
        }
    }


    private fun stopAndSave() {
        startJob?.cancel()
        startJob = null
        _state.value = _state.value.copy(errorMessage = null)

        val result = try {
            recorder.stop()
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to stop/save recording."
            _state.value = _state.value.copy(isRecording = false, errorMessage = msg)
            viewModelScope.launch { _effects.emit(RecordEffect.ShowError(msg)) }
            return
        }

        _state.value = _state.value.copy(isRecording = false)

        if (result == null) {
            _state.value = _state.value.copy(errorMessage = "Failed to save audio.")
            return
        }

        viewModelScope.launch {
            try {
                val ideaId = repo.createIdeaForRecordingFile(result.file)
                _state.value = _state.value.copy(lastSavedFileName = result.file.name)
                _effects.emit(RecordEffect.OpenOverview(ideaId))
            } catch (e: Exception) {
                val msg = e.message ?: "Saved audio, but failed to create idea."
                _state.value = _state.value.copy(
                    lastSavedFileName = result.file.name,
                    errorMessage = msg
                )
                _effects.emit(RecordEffect.ShowError(msg))
            }
        }
    }

    private fun stopForBackground() {
        if (!_state.value.isRecording) return

        startJob?.cancel()
        startJob = null

        val result = recorder.stop()

        _state.value = _state.value.copy(
            isRecording = false,
            lastSavedFileName = result?.file?.name
        )
    }

    override fun onCleared() {
        super.onCleared()
        startJob?.cancel()
        recorder.release()
    }
}
