package com.example.songseed.ui.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.songseed.audio.AudioRecorder
import com.example.songseed.data.repository.IdeaRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface RecordEvent {
    data class OpenWorkspace(val ideaId: Long) : RecordEvent
    data class ShowError(val message: String) : RecordEvent
}

class RecordViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val recorder = AudioRecorder(appContext)
    private val repo = IdeaRepository(appContext)

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _lastSavedFileName = MutableStateFlow<String?>(null)
    val lastSavedFileName = _lastSavedFileName.asStateFlow()

    // Optional: keep a state string for simple UI display.
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private val _events = MutableSharedFlow<RecordEvent>()
    val events = _events.asSharedFlow()

    fun startRecording() {
        _errorMessage.value = null

        try {
            recorder.start()
            _isRecording.value = true
        } catch (e: Exception) {
            _isRecording.value = false
            _errorMessage.value = e.message ?: "Failed to start recording."
        }
    }

    /**
     * Stop recording. If we successfully saved a file, create the Idea and emit navigation event.
     */
    fun stopAndSave() {
        _errorMessage.value = null

        val saved: File? = recorder.stop()
        _isRecording.value = false

        if (saved == null) {
            _errorMessage.value = "Recording was too short or failed to save."
            return
        }

        viewModelScope.launch {
            try {
                val ideaId = repo.createIdeaForRecordingFile(saved)
                _lastSavedFileName.value = saved.name
                _events.emit(RecordEvent.OpenWorkspace(ideaId))
            } catch (e: Exception) {
                _lastSavedFileName.value = saved.name
                val msg = e.message ?: "Saved audio, but failed to create idea."
                _errorMessage.value = msg
                _events.emit(RecordEvent.ShowError(msg))
            }
        }
    }

    /**
     * Called when the app goes to background (Lifecycle ON_STOP).
     * We stop recording for safety, but we do NOT create an idea automatically.
     * This preserves your original behavior.
     */
    fun stopForBackground(): String? {
        if (!_isRecording.value) return null

        val saved = recorder.stop()
        _isRecording.value = false
        _lastSavedFileName.value = saved?.name
        return saved?.name
    }

    override fun onCleared() {
        super.onCleared()
        // Extra safety: make sure recorder isn't left running.
        recorder.stop()
    }
}
