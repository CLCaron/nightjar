package com.example.nightjar.ui.studio

import com.example.nightjar.audio.AudioLatencyEstimator
import com.example.nightjar.data.db.entity.DrumStepEntity
import com.example.nightjar.data.db.entity.MidiNoteEntity
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.data.db.entity.TrackEntity

/** Snapshot of a drum clip for UI rendering. */
data class DrumClipUiState(
    val clipId: Long,
    val offsetMs: Long
)

/** Transient state while the user is long-press-dragging a drum clip to reposition it. */
data class ClipDragState(
    val trackId: Long,
    val clipId: Long,
    val originalOffsetMs: Long,
    val previewOffsetMs: Long
)

/** Snapshot of a drum pattern for UI rendering, keyed by track ID. */
data class DrumPatternUiState(
    val patternId: Long = 0L,
    val stepsPerBar: Int = 16,
    val bars: Int = 1,
    val steps: List<DrumStepEntity> = emptyList(),
    val clips: List<DrumClipUiState> = emptyList()
) {
    val totalSteps: Int get() = stepsPerBar * bars
}

/** Snapshot of MIDI track data for UI rendering, keyed by track ID. */
data class MidiTrackUiState(
    val notes: List<MidiNoteEntity> = emptyList(),
    val midiProgram: Int = 0,
    val instrumentName: String = "Acoustic Grand Piano"
)

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
    val liveAmplitudes: FloatArray = FloatArray(0),
    val recordingStartGlobalMs: Long? = null,
    val recordingTargetTrackId: Long? = null,
    val showAddTrackSheet: Boolean = false,
    val msPerDp: Float = 10f,
    val dragState: TrackDragState? = null,
    val trimState: TrackTrimState? = null,
    val confirmingDeleteTrackId: Long? = null,
    val expandedTrackIds: Set<Long> = emptySet(),
    val soloedTrackIds: Set<Long> = emptySet(),
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val isLoopEnabled: Boolean = false,
    val showLatencySetupDialog: Boolean = false,
    val latencyDiagnostics: AudioLatencyEstimator.LatencyDiagnostics? = null,
    val manualOffsetMs: Long = 0L,
    val armedTrackId: Long? = null,
    val trackTakes: Map<Long, List<TakeEntity>> = emptyMap(),
    val expandedTakeTrackIds: Set<Long> = emptySet(),
    val renamingTrackId: Long? = null,
    val renamingTrackCurrentName: String = "",
    val renamingTakeId: Long? = null,
    val renamingTakeTrackId: Long? = null,
    val renamingTakeCurrentName: String = "",
    val confirmingDeleteTakeId: Long? = null,
    val confirmingDeleteTakeTrackId: Long? = null,
    val expandedTakeDrawerIds: Set<Long> = emptySet(),
    val bpm: Double = 120.0,
    val timeSignatureNumerator: Int = 4,
    val timeSignatureDenominator: Int = 4,
    val isSnapEnabled: Boolean = true,
    val drumPatterns: Map<Long, DrumPatternUiState> = emptyMap(),
    val clipDragState: ClipDragState? = null,
    val midiTracks: Map<Long, MidiTrackUiState> = emptyMap(),
    val showInstrumentPickerForTrackId: Long? = null
) {
    val hasLoopRegion: Boolean get() = loopStartMs != null && loopEndMs != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StudioUiState) return false
        return ideaTitle == other.ideaTitle &&
                tracks == other.tracks &&
                isLoading == other.isLoading &&
                errorMessage == other.errorMessage &&
                isPlaying == other.isPlaying &&
                globalPositionMs == other.globalPositionMs &&
                totalDurationMs == other.totalDurationMs &&
                isRecording == other.isRecording &&
                recordingElapsedMs == other.recordingElapsedMs &&
                liveAmplitudes.contentEquals(other.liveAmplitudes) &&
                recordingStartGlobalMs == other.recordingStartGlobalMs &&
                recordingTargetTrackId == other.recordingTargetTrackId &&
                showAddTrackSheet == other.showAddTrackSheet &&
                msPerDp == other.msPerDp &&
                dragState == other.dragState &&
                trimState == other.trimState &&
                confirmingDeleteTrackId == other.confirmingDeleteTrackId &&
                expandedTrackIds == other.expandedTrackIds &&
                soloedTrackIds == other.soloedTrackIds &&
                loopStartMs == other.loopStartMs &&
                loopEndMs == other.loopEndMs &&
                isLoopEnabled == other.isLoopEnabled &&
                showLatencySetupDialog == other.showLatencySetupDialog &&
                latencyDiagnostics == other.latencyDiagnostics &&
                manualOffsetMs == other.manualOffsetMs &&
                armedTrackId == other.armedTrackId &&
                trackTakes == other.trackTakes &&
                expandedTakeTrackIds == other.expandedTakeTrackIds &&
                renamingTrackId == other.renamingTrackId &&
                renamingTrackCurrentName == other.renamingTrackCurrentName &&
                renamingTakeId == other.renamingTakeId &&
                renamingTakeTrackId == other.renamingTakeTrackId &&
                renamingTakeCurrentName == other.renamingTakeCurrentName &&
                confirmingDeleteTakeId == other.confirmingDeleteTakeId &&
                confirmingDeleteTakeTrackId == other.confirmingDeleteTakeTrackId &&
                expandedTakeDrawerIds == other.expandedTakeDrawerIds &&
                bpm == other.bpm &&
                timeSignatureNumerator == other.timeSignatureNumerator &&
                timeSignatureDenominator == other.timeSignatureDenominator &&
                isSnapEnabled == other.isSnapEnabled &&
                drumPatterns == other.drumPatterns &&
                clipDragState == other.clipDragState &&
                midiTracks == other.midiTracks &&
                showInstrumentPickerForTrackId == other.showInstrumentPickerForTrackId
    }

    override fun hashCode(): Int {
        var result = ideaTitle.hashCode()
        result = 31 * result + tracks.hashCode()
        result = 31 * result + isLoading.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + globalPositionMs.hashCode()
        result = 31 * result + totalDurationMs.hashCode()
        result = 31 * result + isRecording.hashCode()
        result = 31 * result + recordingElapsedMs.hashCode()
        result = 31 * result + liveAmplitudes.contentHashCode()
        result = 31 * result + (recordingStartGlobalMs?.hashCode() ?: 0)
        result = 31 * result + (recordingTargetTrackId?.hashCode() ?: 0)
        result = 31 * result + showAddTrackSheet.hashCode()
        result = 31 * result + msPerDp.hashCode()
        result = 31 * result + (dragState?.hashCode() ?: 0)
        result = 31 * result + (trimState?.hashCode() ?: 0)
        result = 31 * result + (confirmingDeleteTrackId?.hashCode() ?: 0)
        result = 31 * result + expandedTrackIds.hashCode()
        result = 31 * result + soloedTrackIds.hashCode()
        result = 31 * result + (loopStartMs?.hashCode() ?: 0)
        result = 31 * result + (loopEndMs?.hashCode() ?: 0)
        result = 31 * result + isLoopEnabled.hashCode()
        result = 31 * result + showLatencySetupDialog.hashCode()
        result = 31 * result + (latencyDiagnostics?.hashCode() ?: 0)
        result = 31 * result + manualOffsetMs.hashCode()
        result = 31 * result + (armedTrackId?.hashCode() ?: 0)
        result = 31 * result + trackTakes.hashCode()
        result = 31 * result + expandedTakeTrackIds.hashCode()
        result = 31 * result + (renamingTrackId?.hashCode() ?: 0)
        result = 31 * result + renamingTrackCurrentName.hashCode()
        result = 31 * result + (renamingTakeId?.hashCode() ?: 0)
        result = 31 * result + (renamingTakeTrackId?.hashCode() ?: 0)
        result = 31 * result + renamingTakeCurrentName.hashCode()
        result = 31 * result + (confirmingDeleteTakeId?.hashCode() ?: 0)
        result = 31 * result + (confirmingDeleteTakeTrackId?.hashCode() ?: 0)
        result = 31 * result + expandedTakeDrawerIds.hashCode()
        result = 31 * result + bpm.hashCode()
        result = 31 * result + timeSignatureNumerator.hashCode()
        result = 31 * result + timeSignatureDenominator.hashCode()
        result = 31 * result + isSnapEnabled.hashCode()
        result = 31 * result + drumPatterns.hashCode()
        result = 31 * result + (clipDragState?.hashCode() ?: 0)
        result = 31 * result + midiTracks.hashCode()
        result = 31 * result + (showInstrumentPickerForTrackId?.hashCode() ?: 0)
        return result
    }
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

    // Track drawer (inline settings panel)
    data class OpenTrackSettings(val trackId: Long) : StudioAction
    data class SetTrackMuted(val trackId: Long, val muted: Boolean) : StudioAction
    data class SetTrackVolume(val trackId: Long, val volume: Float) : StudioAction
    data class ToggleSolo(val trackId: Long) : StudioAction

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

    // Arm / Record
    data class ToggleArm(val trackId: Long) : StudioAction
    data object StartRecording : StudioAction
    data object StopRecording : StudioAction

    // Track rename
    data class RequestRenameTrack(val trackId: Long, val currentName: String) : StudioAction
    data class ConfirmRenameTrack(val trackId: Long, val newName: String) : StudioAction
    data object DismissRenameTrack : StudioAction

    // Takes
    data class ToggleTakesView(val trackId: Long) : StudioAction
    data class RenameTake(val takeId: Long, val name: String) : StudioAction
    data class DeleteTake(val takeId: Long, val trackId: Long) : StudioAction
    data class SetTakeMuted(val takeId: Long, val trackId: Long, val muted: Boolean) : StudioAction
    data class DragTake(val takeId: Long, val newOffsetMs: Long) : StudioAction

    // Take drawer / rename / delete
    data class ToggleTakeDrawer(val takeId: Long) : StudioAction
    data class RequestRenameTake(val takeId: Long, val trackId: Long, val currentName: String) : StudioAction
    data class ConfirmRenameTake(val takeId: Long, val newName: String) : StudioAction
    data object DismissRenameTake : StudioAction
    data class RequestDeleteTake(val takeId: Long, val trackId: Long) : StudioAction
    data object DismissDeleteTake : StudioAction
    data object ExecuteDeleteTake : StudioAction

    // Time signature / Snap
    data class SetTimeSignature(val numerator: Int, val denominator: Int) : StudioAction
    data object ToggleSnap : StudioAction

    // Drum sequencer
    data class ToggleDrumStep(
        val trackId: Long,
        val stepIndex: Int,
        val drumNote: Int
    ) : StudioAction
    data class SetBpm(val bpm: Double) : StudioAction
    data class SetPatternBars(val trackId: Long, val bars: Int) : StudioAction

    // Drum clips
    data class DuplicateClip(val trackId: Long, val clipId: Long) : StudioAction
    data class MoveClip(val trackId: Long, val clipId: Long, val newOffsetMs: Long) : StudioAction
    data class DeleteClip(val trackId: Long, val clipId: Long) : StudioAction

    // Drum clip drag-to-reposition
    data class StartDragClip(val trackId: Long, val clipId: Long) : StudioAction
    data class UpdateDragClip(val previewOffsetMs: Long) : StudioAction
    data class FinishDragClip(val trackId: Long, val clipId: Long, val newOffsetMs: Long) : StudioAction
    data object CancelDragClip : StudioAction

    // MIDI instrument tracks
    data class OpenPianoRoll(val trackId: Long) : StudioAction
    data class ShowInstrumentPicker(val trackId: Long) : StudioAction
    data object DismissInstrumentPicker : StudioAction
    data class SetMidiInstrument(val trackId: Long, val program: Int) : StudioAction
    data class PreviewInstrument(val program: Int) : StudioAction
}

/** One-shot side effects emitted by [StudioViewModel]. */
sealed interface StudioEffect {
    data object NavigateBack : StudioEffect
    data class ShowError(val message: String) : StudioEffect
    data object RequestMicPermission : StudioEffect
    data class NavigateToPianoRoll(val trackId: Long) : StudioEffect
}

/** Available track types for the "Add Track" bottom sheet. */
enum class NewTrackType(val label: String, val description: String) {
    AUDIO_RECORDING("Audio Recording", "Record with your microphone"),
    DRUM_SEQUENCER("Drum Sequencer", "Step-based drum pattern"),
    MIDI_INSTRUMENT("MIDI Instrument", "Piano, guitar, bass, synths & more")
}
