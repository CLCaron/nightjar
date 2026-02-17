package com.example.nightjar.ui.explore

import com.example.nightjar.data.db.entity.TrackEntity

/** UI state for the Explore (multi-track workspace) screen. */
data class ExploreUiState(
    val ideaTitle: String = "",
    val tracks: List<TrackEntity> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isPlaying: Boolean = false,
    val globalPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val isRecording: Boolean = false,
    val recordingElapsedMs: Long = 0L,
    val showAddTrackSheet: Boolean = false,
    val msPerDp: Float = 10f,
    val dragState: TrackDragState? = null,
    val trimState: TrackTrimState? = null
)

/** User-initiated actions on the Explore screen. */
sealed interface ExploreAction {
    data class Load(val ideaId: Long) : ExploreAction
    data object ShowAddTrackSheet : ExploreAction
    data object DismissAddTrackSheet : ExploreAction
    data class SelectNewTrackType(val type: NewTrackType) : ExploreAction
    data object MicPermissionGranted : ExploreAction
    data object StopOverdubRecording : ExploreAction
    data object Play : ExploreAction
    data object Pause : ExploreAction
    data class SeekTo(val positionMs: Long) : ExploreAction
    data class SeekFinished(val positionMs: Long) : ExploreAction

    // Drag-to-reposition
    data class StartDragTrack(val trackId: Long) : ExploreAction
    data class UpdateDragTrack(val previewOffsetMs: Long) : ExploreAction
    data class FinishDragTrack(val trackId: Long, val newOffsetMs: Long) : ExploreAction
    data object CancelDrag : ExploreAction

    // Trim
    data class StartTrim(val trackId: Long, val edge: TrimEdge) : ExploreAction
    data class UpdateTrim(val previewTrimStartMs: Long, val previewTrimEndMs: Long) : ExploreAction
    data class FinishTrim(
        val trackId: Long,
        val trimStartMs: Long,
        val trimEndMs: Long
    ) : ExploreAction
    data object CancelTrim : ExploreAction
}

/** One-shot side effects emitted by [ExploreViewModel]. */
sealed interface ExploreEffect {
    data object NavigateBack : ExploreEffect
    data class ShowError(val message: String) : ExploreEffect
    data object RequestMicPermission : ExploreEffect
}

/** Available track types for the "Add Track" bottom sheet. */
enum class NewTrackType(val label: String, val description: String) {
    AUDIO_RECORDING("Audio Recording", "Record with your microphone")
}
