package com.example.nightjar.ui.explore

import com.example.nightjar.data.db.entity.TrackEntity

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
    val showAddTrackSheet: Boolean = false
)

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
}

sealed interface ExploreEffect {
    data object NavigateBack : ExploreEffect
    data class ShowError(val message: String) : ExploreEffect
    data object RequestMicPermission : ExploreEffect
}

enum class NewTrackType(val label: String, val description: String) {
    AUDIO_RECORDING("Audio Recording", "Record with your microphone")
}
