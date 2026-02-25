package com.example.nightjar.ui.record

import java.io.File

/** Snapshot of a just-captured recording, held until the user navigates away. */
data class PostRecordingState(
    val ideaId: Long,
    val audioFile: File
)

/** UI state for the Record screen. */
data class RecordUiState(
    val isRecording: Boolean = false,
    val postRecording: PostRecordingState? = null,
    val errorMessage: String? = null
)

/** User-initiated actions on the Record screen. */
sealed interface RecordAction {
    data object StartRecording : RecordAction
    data object StopAndSave : RecordAction
    /** Gracefully save if the app is backgrounded mid-recording. */
    data object StopForBackground : RecordAction
    /** Navigate to Overview for the captured idea. */
    data object GoToOverview : RecordAction
    /** Navigate to Studio for the captured idea. */
    data object GoToStudio : RecordAction
}

/** One-shot side effects emitted by [RecordViewModel]. */
sealed interface RecordEffect {
    data class OpenOverview(val ideaId: Long) : RecordEffect
    data class OpenStudio(val ideaId: Long) : RecordEffect
    data class ShowError(val message: String) : RecordEffect
}
