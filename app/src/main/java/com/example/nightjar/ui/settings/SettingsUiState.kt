package com.example.nightjar.ui.settings

import com.example.nightjar.audio.ThemePreferences

data class SettingsUiState(
    val themeKey: String = ThemePreferences.DEFAULT_THEME
)

sealed interface SettingsAction {
    data class SetTheme(val key: String) : SettingsAction
}
