package com.example.nightjar.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.AudioRecorder
import com.example.nightjar.data.repository.IdeaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Record screen.
 *
 * Manages the record → stop → save → navigate-to-workspace flow.
 * Handles lifecycle edge cases like the app being backgrounded
 * mid-recording.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    private val recorder: AudioRecorder,
    private val repo: IdeaRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RecordUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<RecordEffect>()
    val effects = _effects.asSharedFlow()

    fun onAction(action: RecordAction) {
        when (action) {
            RecordAction.StartRecording -> startRecording()
            RecordAction.StopAndSave -> stopAndSave()
            RecordAction.StopForBackground -> stopForBackground()
        }
    }

    fun startRecording() {
        _state.value = _state.value.copy(errorMessage = null)
        try {
            recorder.start()
            _state.value = _state.value.copy(
                isRecording = true,
                errorMessage = null
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isRecording = false,
                errorMessage = e.message ?: "Failed to start recording."
            )
        }
    }


    private fun stopAndSave() {
        _state.value = _state.value.copy(errorMessage = null)

        val saved = try {
            recorder.stop()
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to stop/save recording."
            _state.value = _state.value.copy(isRecording = false, errorMessage = msg)
            viewModelScope.launch { _effects.emit(RecordEffect.ShowError(msg)) }
            return
        }

        _state.value = _state.value.copy(isRecording = false)

        if (saved == null) {
            _state.value = _state.value.copy(errorMessage = "Failed to save audio.")
            return
        }

        viewModelScope.launch {
            try {
                val ideaId = repo.createIdeaForRecordingFile(saved)
                _state.value = _state.value.copy(lastSavedFileName = saved.name)
                _effects.emit(RecordEffect.OpenWorkspace(ideaId))
            } catch (e: Exception) {
                val msg = e.message ?: "Saved audio, but failed to create idea."
                _state.value = _state.value.copy(
                    lastSavedFileName = saved.name,
                    errorMessage = msg
                )
                _effects.emit(RecordEffect.ShowError(msg))
            }
        }
    }

    private fun stopForBackground() {
        if (!_state.value.isRecording) return

        val saved = recorder.stop()

        _state.value = _state.value.copy(
            isRecording = false,
            lastSavedFileName = saved?.name
        )
    }

    override fun onCleared() {
        super.onCleared()
        recorder.stop()
    }
}
