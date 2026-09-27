package com.example.nightjar.ui.settings

import com.example.nightjar.audio.ThemePreferences
import com.example.nightjar.audio.AudioRouteReadout
import com.example.nightjar.audio.MicrophoneOption

data class SettingsUiState(
    val themeKey: String = ThemePreferences.DEFAULT_THEME,
    val showAudioSync: Boolean = false,
    val routes: AudioRouteReadout = AudioRouteReadout(),
    val microphones: List<MicrophoneOption> = emptyList(),
    val selectedMicrophone: String = "default",
    val audioBusy: Boolean = false,
    val audioBusyReason: String? = null,
    val audioError: String? = null,
    val checkingTiming: Boolean = false,
    val savedTimingMessage: String? = null,
    val timingMessage: String = "Timing: Estimated. Run an optional timing check below."
)

sealed interface SettingsAction {
    data object CheckTiming : SettingsAction
    data object StopTimingCheck : SettingsAction
    data class SetTheme(val key: String) : SettingsAction
    data object OpenAudioSync : SettingsAction
    data object CloseAudioSync : SettingsAction
    data class SelectMicrophone(val key: String) : SettingsAction
}
