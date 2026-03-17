package com.example.nightjar.ui.settings

import androidx.lifecycle.ViewModel
import com.example.nightjar.audio.ThemePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePrefs: ThemePreferences
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(themeKey = themePrefs.themeKey)
    )
    val state = _state.asStateFlow()

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetTheme -> {
                themePrefs.themeKey = action.key
                _state.update { it.copy(themeKey = action.key) }
            }
        }
    }
}
