package com.example.nightjar.ui.record

data class RecordUiState(
    val isRecording: Boolean = false,
    val lastSavedFileName: String? = null,
    val errorMessage: String? = null
)

sealed interface RecordAction {
    data object StartRecording : RecordAction
    data object StopAndSave : RecordAction
    data object StopForBackground : RecordAction
}

sealed interface RecordEffect {
    data class OpenWorkspace(val ideaId: Long) : RecordEffect
    data class ShowError(val message: String) : RecordEffect
}