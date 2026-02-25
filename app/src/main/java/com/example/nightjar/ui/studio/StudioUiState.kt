package com.example.nightjar.ui.studio

import com.example.nightjar.audio.AudioLatencyEstimator
import com.example.nightjar.data.db.entity.TrackEntity

/** UI state for the Studio (multi-track workspace) screen. */
data class StudioUiState(
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
    val trimState: TrackTrimState? = null,
    val confirmingDeleteTrackId: Long? = null,
    val settingsTrackId: Long? = null,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val isLoopEnabled: Boolean = false,
    val showLatencySetupDialog: Boolean = false,
    val latencyDiagnostics: AudioLatencyEstimator.LatencyDiagnostics? = null,
    val manualOffsetMs: Long = 0L
) {
    val hasLoopRegion: Boolean get() = loopStartMs != null && loopEndMs != null
}

/** User-initiated actions on the Studio screen. */
sealed interface StudioAction {
    data class Load(val ideaId: Long) : StudioAction
    data object ShowAddTrackSheet : StudioAction
    data object DismissAddTrackSheet : StudioAction
    data class SelectNewTrackType(val type: NewTrackType) : StudioAction
    data object MicPermissionGranted : StudioAction
    data object StopOverdubRecording : StudioAction
    data object Play : StudioAction
    data object Pause : StudioAction
    data class SeekTo(val positionMs: Long) : StudioAction
    data class SeekFinished(val positionMs: Long) : StudioAction

    // Drag-to-reposition
    data class StartDragTrack(val trackId: Long) : StudioAction
    data class UpdateDragTrack(val previewOffsetMs: Long) : StudioAction
    data class FinishDragTrack(val trackId: Long, val newOffsetMs: Long) : StudioAction
    data object CancelDrag : StudioAction

    // Trim
    data class StartTrim(val trackId: Long, val edge: TrimEdge) : StudioAction
    data class UpdateTrim(val previewTrimStartMs: Long, val previewTrimEndMs: Long) : StudioAction
    data class FinishTrim(
        val trackId: Long,
        val trimStartMs: Long,
        val trimEndMs: Long
    ) : StudioAction
    data object CancelTrim : StudioAction

    // Delete
    data class ConfirmDeleteTrack(val trackId: Long) : StudioAction
    data object DismissDeleteTrack : StudioAction
    data object ExecuteDeleteTrack : StudioAction

    // Track settings
    data class OpenTrackSettings(val trackId: Long) : StudioAction
    data object DismissTrackSettings : StudioAction
    data class SetTrackMuted(val trackId: Long, val muted: Boolean) : StudioAction
    data class SetTrackVolume(val trackId: Long, val volume: Float) : StudioAction

    // Loop
    data class SetLoopRegion(val startMs: Long, val endMs: Long) : StudioAction
    data object ClearLoopRegion : StudioAction
    data object ToggleLoop : StudioAction
    data class UpdateLoopRegionStart(val startMs: Long) : StudioAction
    data class UpdateLoopRegionEnd(val endMs: Long) : StudioAction

    // Latency setup
    data object ShowLatencySetup : StudioAction
    data object DismissLatencySetup : StudioAction
    data class SetManualOffset(val offsetMs: Long) : StudioAction
    data object ClearManualOffset : StudioAction
}

/** One-shot side effects emitted by [StudioViewModel]. */
sealed interface StudioEffect {
    data object NavigateBack : StudioEffect
    data class ShowError(val message: String) : StudioEffect
    data object RequestMicPermission : StudioEffect
}

/** Available track types for the "Add Track" bottom sheet. */
enum class NewTrackType(val label: String, val description: String) {
    AUDIO_RECORDING("Audio Recording", "Record with your microphone")
}
