package com.example.nightjar.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.example.nightjar.audio.AudioInputPreferences
import com.example.nightjar.audio.AudioRouteMonitor
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.audio.CaptureSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.nightjar.audio.ThemePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePrefs: ThemePreferences,
    private val inputs: AudioInputPreferences,
    private val routes: AudioRouteMonitor,
    private val engine: OboeAudioEngine,
    private val capture: CaptureSession
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(themeKey = themePrefs.themeKey, microphones = inputs.options(),
            selectedMicrophone = inputs.selectedKey)
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                try {
                    _state.update { it.copy(routes = routes.read(), microphones = inputs.options(),
                        selectedMicrophone = inputs.selectedKey,
                        audioBusy = engine.isRecordingActive() || capture.state.value.busy || engine.isPlaying.value) }
                } catch (e: Exception) {
                    Log.w("SettingsViewModel", "Cannot inspect audio routing", e)
                    _state.update { it.copy(audioError = "Audio routing is unavailable. You can still record.") }
                }
                delay(500)
            }
        }
    }

    fun onAction(action: SettingsAction) {
        when (action) {
            SettingsAction.OpenAudioSync -> _state.update { it.copy(showAudioSync = true) }
            SettingsAction.CloseAudioSync -> _state.update { it.copy(showAudioSync = false) }
            is SettingsAction.SelectMicrophone -> {
                if (engine.isRecordingActive() || capture.state.value.busy || engine.isPlaying.value) return
                try {
                    inputs.select(action.key)
                    _state.update { it.copy(selectedMicrophone = action.key, audioError = null) }
                } catch (e: IllegalArgumentException) {
                    Log.w("SettingsViewModel", "Microphone selection failed", e)
                    _state.update { it.copy(audioError = e.message) }
                }
            }
            is SettingsAction.SetTheme -> {
                themePrefs.themeKey = action.key
                _state.update { it.copy(themeKey = action.key) }
            }
        }
    }
}
