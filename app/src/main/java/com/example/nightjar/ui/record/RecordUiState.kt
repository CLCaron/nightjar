package com.example.nightjar.ui.record

/** UI state for the Record screen. */
data class RecordUiState(
    val isRecording: Boolean = false,
    val lastSavedFileName: String? = null,
    val errorMessage: String? = null
)

/** User-initiated actions on the Record screen. */
sealed interface RecordAction {
    data object StartRecording : RecordAction
    data object StopAndSave : RecordAction
    /** Gracefully save if the app is backgrounded mid-recording. */
    data object StopForBackground : RecordAction
}

/** One-shot side effects emitted by [RecordViewModel]. */
sealed interface RecordEffect {
    data class OpenWorkspace(val ideaId: Long) : RecordEffect
    data class ShowError(val message: String) : RecordEffect
}