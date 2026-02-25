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
    val liveAmplitudes: FloatArray = FloatArray(0),
    val postRecording: PostRecordingState? = null,
    val errorMessage: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordUiState) return false
        return isRecording == other.isRecording &&
                liveAmplitudes.contentEquals(other.liveAmplitudes) &&
                postRecording == other.postRecording &&
                errorMessage == other.errorMessage
    }

    override fun hashCode(): Int {
        var result = isRecording.hashCode()
        result = 31 * result + liveAmplitudes.contentHashCode()
        result = 31 * result + (postRecording?.hashCode() ?: 0)
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }
}

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
