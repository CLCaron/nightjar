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
import com.example.nightjar.audio.AcousticCheckRunner
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePrefs: ThemePreferences,
    private val inputs: AudioInputPreferences,
    private val routes: AudioRouteMonitor,
    private val engine: OboeAudioEngine,
    private val capture: CaptureSession,
    private val acousticCheck: AcousticCheckRunner
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(themeKey = themePrefs.themeKey, microphones = inputs.options(),
            selectedMicrophone = inputs.selectedKey)
    )
    val state = _state.asStateFlow()
    private var checkJob: Job? = null

    init {
        viewModelScope.launch {
            while (isActive) {
                try {
                    val busyReason = audioBusyReason()
                    _state.update { it.copy(routes = routes.read(), microphones = inputs.options(),
                        selectedMicrophone = inputs.selectedKey,
                        audioBusy = busyReason != null, audioBusyReason = busyReason) }
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
            SettingsAction.CheckTiming -> {
                if (checkJob?.isActive == true || audioBusyReason() != null || !_state.value.showAudioSync) return
                checkJob = viewModelScope.launch {
                    _state.update { it.copy(checkingTiming = true, audioError = null,
                        timingMessage = "Checking timing. Keep the output close to the selected microphone.") }
                    try {
                        val measurement = acousticCheck.measure()
                        _state.update { it.copy(timingMessage =
                            "Last check: %.1f ms delay from %d trials. Timing remains Estimated until device verification."
                                .format(measurement.delayMs, measurement.acceptedTrials)) }
                    } catch (e: CancellationException) {
                        _state.update { it.copy(timingMessage = "Check stopped. Timing: Estimated.") }
                        throw e
                    } catch (e: Exception) {
                        Log.w("SettingsViewModel", "Acoustic timing check failed", e)
                        _state.update { it.copy(audioError = e.message,
                            timingMessage = "Timing: Estimated. You can still record.") }
                    } finally {
                        _state.update { it.copy(checkingTiming = false) }
                    }
                }
            }
            SettingsAction.StopTimingCheck -> checkJob?.cancel()
            SettingsAction.OpenAudioSync -> _state.update { it.copy(showAudioSync = true) }
            SettingsAction.CloseAudioSync -> {
                checkJob?.cancel()
                _state.update { it.copy(showAudioSync = false) }
            }
            is SettingsAction.SelectMicrophone -> {
                if (_state.value.checkingTiming || audioBusyReason() != null) return
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

    private fun audioBusyReason(): String? {
        val session = capture.state.value
        return when {
            session.recording || engine.isRecordingActive() -> "Stop recording before checking timing."
            session.pendingSave || session.phase == com.example.nightjar.audio.CapturePhase.SAVING ->
                "Finish saving the recording before checking timing."
            session.busy -> "Wait for the Idea to finish loading before checking timing."
            engine.isPlaybackActive() -> "Stop playback before checking timing."
            else -> null
        }
    }
}
