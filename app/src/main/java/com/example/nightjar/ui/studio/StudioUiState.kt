package com.example.nightjar.ui.studio

import com.example.nightjar.audio.AudioLatencyEstimator
import com.example.nightjar.data.db.entity.DrumStepEntity
import com.example.nightjar.data.db.entity.MidiNoteEntity
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.data.db.entity.TrackEntity

/** Snapshot of a drum clip for UI rendering, including its own pattern data. */
data class DrumClipUiState(
    val clipId: Long,
    val offsetMs: Long,
    val patternId: Long = 0L,
    val stepsPerBar: Int = 16,
    val bars: Int = 1,
    val steps: List<DrumStepEntity> = emptyList()
) {
    val totalSteps: Int get() = stepsPerBar * bars
}

/** Transient state while the user is long-press-dragging a clip to reposition it. */
data class ClipDragState(
    val trackId: Long,
    val clipId: Long,
    val originalOffsetMs: Long,
    val previewOffsetMs: Long
)

/** State for the expanded clip action panel (tap a clip to show inline buttons). */
data class ExpandedClipState(
    val trackId: Long,
    val clipId: Long,
    val clipType: String  // "drum", "midi", or "audio"
)

/** Transient state while the user is long-press-dragging a MIDI clip to reposition it. */
data class MidiClipDragState(
    val trackId: Long,
    val clipId: Long,
    val originalOffsetMs: Long,
    val previewOffsetMs: Long
)

/** Snapshot of a drum pattern for UI rendering, keyed by track ID. Per-clip data model. */
data class DrumPatternUiState(
    val clips: List<DrumClipUiState> = emptyList(),
    val selectedClipIndex: Int = 0
) {
    /** The currently selected clip (for editing). */
    val selectedClip: DrumClipUiState?
        get() = clips.getOrNull(selectedClipIndex)

    // Backward compatibility: these delegate to the selected clip's data
    val patternId: Long get() = selectedClip?.patternId ?: 0L
    val stepsPerBar: Int get() = selectedClip?.stepsPerBar ?: 16
    val bars: Int get() = selectedClip?.bars ?: 1
    val steps: List<DrumStepEntity> get() = selectedClip?.steps ?: emptyList()
    val totalSteps: Int get() = stepsPerBar * bars
}

/** Snapshot of a single MIDI clip for UI rendering. */
data class MidiClipUiState(
    val clipId: Long,
    val offsetMs: Long,
    val notes: List<MidiNoteEntity> = emptyList()
) {
    /** Duration of the clip content (max note end). */
    val contentDurationMs: Long
        get() = notes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
}

/** Snapshot of MIDI track data for UI rendering, keyed by track ID. */
data class MidiTrackUiState(
    val notes: List<MidiNoteEntity> = emptyList(),
    val clips: List<MidiClipUiState> = emptyList(),
    val midiProgram: Int = 0,
    val instrumentName: String = "Acoustic Grand Piano",
    val selectedClipId: Long? = null
)

/** Snapshot of a single audio clip for UI rendering. */
data class AudioClipUiState(
    val clipId: Long,
    val trackId: Long,
    val offsetMs: Long,
    val displayName: String,
    val isMuted: Boolean,
    val activeTake: TakeEntity?,
    val takeCount: Int,
    val takes: List<TakeEntity> = emptyList()
) {
    /** Effective duration of this clip based on the active take. */
    val effectiveDurationMs: Long
        get() {
            val take = activeTake ?: return 0L
            return (take.durationMs - take.trimStartMs - take.trimEndMs).coerceAtLeast(200L)
        }
}

/** Transient state while the user is long-press-dragging an audio clip. */
data class AudioClipDragState(
    val trackId: Long,
    val clipId: Long,
    val originalOffsetMs: Long,
    val previewOffsetMs: Long
)

/** Transient state while the user is trimming an audio clip edge. */
data class AudioClipTrimState(
    val clipId: Long,
    val trackId: Long,
    val edge: TrimEdge,
    val takeId: Long,
    val originalTrimStartMs: Long,
    val originalTrimEndMs: Long,
    val previewTrimStartMs: Long,
    val previewTrimEndMs: Long
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
    val audioClips: Map<Long, List<AudioClipUiState>> = emptyMap(),
    val expandedAudioClipId: Long? = null,
    val audioClipDragState: AudioClipDragState? = null,
    val audioClipTrimState: AudioClipTrimState? = null,
    val renamingTrackId: Long? = null,
    val renamingTrackCurrentName: String = "",
    val renamingTakeId: Long? = null,
    val renamingTakeClipId: Long? = null,
    val renamingTakeCurrentName: String = "",
    val confirmingDeleteTakeId: Long? = null,
    val confirmingDeleteTakeClipId: Long? = null,
    val bpm: Double = 120.0,
    val timeSignatureNumerator: Int = 4,
    val timeSignatureDenominator: Int = 4,
    val isSnapEnabled: Boolean = true,
    val gridResolution: Int = 16,
    val drumPatterns: Map<Long, DrumPatternUiState> = emptyMap(),
    val clipDragState: ClipDragState? = null,
    val midiTracks: Map<Long, MidiTrackUiState> = emptyMap(),
    val midiClipDragState: MidiClipDragState? = null,
    val expandedClipState: ExpandedClipState? = null,
    val showInstrumentPickerForTrackId: Long? = null,
    val isMetronomeEnabled: Boolean = false,
    val metronomeVolume: Float = 0.7f,
    val countInBars: Int = 0,
    val isCountingIn: Boolean = false,
    val lastBeatFrame: Long = -1L,
    val isMetronomeSettingsOpen: Boolean = false,
    val cursorPositionMs: Long = 0L,
    val returnToCursor: Boolean = true
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
                audioClips == other.audioClips &&
                expandedAudioClipId == other.expandedAudioClipId &&
                audioClipDragState == other.audioClipDragState &&
                audioClipTrimState == other.audioClipTrimState &&
                renamingTrackId == other.renamingTrackId &&
                renamingTrackCurrentName == other.renamingTrackCurrentName &&
                renamingTakeId == other.renamingTakeId &&
                renamingTakeClipId == other.renamingTakeClipId &&
                renamingTakeCurrentName == other.renamingTakeCurrentName &&
                confirmingDeleteTakeId == other.confirmingDeleteTakeId &&
                confirmingDeleteTakeClipId == other.confirmingDeleteTakeClipId &&
                bpm == other.bpm &&
                timeSignatureNumerator == other.timeSignatureNumerator &&
                timeSignatureDenominator == other.timeSignatureDenominator &&
                isSnapEnabled == other.isSnapEnabled &&
                gridResolution == other.gridResolution &&
                drumPatterns == other.drumPatterns &&
                clipDragState == other.clipDragState &&
                midiTracks == other.midiTracks &&
                midiClipDragState == other.midiClipDragState &&
                expandedClipState == other.expandedClipState &&
                showInstrumentPickerForTrackId == other.showInstrumentPickerForTrackId &&
                isMetronomeEnabled == other.isMetronomeEnabled &&
                metronomeVolume == other.metronomeVolume &&
                countInBars == other.countInBars &&
                isCountingIn == other.isCountingIn &&
                lastBeatFrame == other.lastBeatFrame &&
                isMetronomeSettingsOpen == other.isMetronomeSettingsOpen &&
                cursorPositionMs == other.cursorPositionMs &&
                returnToCursor == other.returnToCursor
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
        result = 31 * result + audioClips.hashCode()
        result = 31 * result + (expandedAudioClipId?.hashCode() ?: 0)
        result = 31 * result + (audioClipDragState?.hashCode() ?: 0)
        result = 31 * result + (audioClipTrimState?.hashCode() ?: 0)
        result = 31 * result + (renamingTrackId?.hashCode() ?: 0)
        result = 31 * result + renamingTrackCurrentName.hashCode()
        result = 31 * result + (renamingTakeId?.hashCode() ?: 0)
        result = 31 * result + (renamingTakeClipId?.hashCode() ?: 0)
        result = 31 * result + renamingTakeCurrentName.hashCode()
        result = 31 * result + (confirmingDeleteTakeId?.hashCode() ?: 0)
        result = 31 * result + (confirmingDeleteTakeClipId?.hashCode() ?: 0)
        result = 31 * result + bpm.hashCode()
        result = 31 * result + timeSignatureNumerator.hashCode()
        result = 31 * result + timeSignatureDenominator.hashCode()
        result = 31 * result + isSnapEnabled.hashCode()
        result = 31 * result + gridResolution.hashCode()
        result = 31 * result + drumPatterns.hashCode()
        result = 31 * result + (clipDragState?.hashCode() ?: 0)
        result = 31 * result + midiTracks.hashCode()
        result = 31 * result + (midiClipDragState?.hashCode() ?: 0)
        result = 31 * result + (expandedClipState?.hashCode() ?: 0)
        result = 31 * result + (showInstrumentPickerForTrackId?.hashCode() ?: 0)
        result = 31 * result + isMetronomeEnabled.hashCode()
        result = 31 * result + metronomeVolume.hashCode()
        result = 31 * result + countInBars.hashCode()
        result = 31 * result + isCountingIn.hashCode()
        result = 31 * result + lastBeatFrame.hashCode()
        result = 31 * result + isMetronomeSettingsOpen.hashCode()
        result = 31 * result + cursorPositionMs.hashCode()
        result = 31 * result + returnToCursor.hashCode()
        return result
    }
}

/** User-initiated actions on the Studio screen. */
sealed interface StudioAction {
    data object NavigateBack : StudioAction
    data class Load(val ideaId: Long) : StudioAction
    data object ShowAddTrackSheet : StudioAction
    data object DismissAddTrackSheet : StudioAction
    data class SelectNewTrackType(val type: NewTrackType) : StudioAction
    data object MicPermissionGranted : StudioAction
    data object StopOverdubRecording : StudioAction
    data object Play : StudioAction
    data object Pause : StudioAction
    data object RestartPlayback : StudioAction
    data class SeekTo(val positionMs: Long) : StudioAction
    data class SeekFinished(val positionMs: Long) : StudioAction

    // Drag-to-reposition (kept for non-audio track compatibility)
    data class StartDragTrack(val trackId: Long) : StudioAction
    data class UpdateDragTrack(val previewOffsetMs: Long) : StudioAction
    data class FinishDragTrack(val trackId: Long, val newOffsetMs: Long) : StudioAction
    data object CancelDrag : StudioAction

    // Trim (kept for non-audio track compatibility)
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

    // Audio clip actions
    data class TapAudioClip(val trackId: Long, val clipId: Long) : StudioAction
    data class StartDragAudioClip(val trackId: Long, val clipId: Long) : StudioAction
    data class UpdateDragAudioClip(val previewOffsetMs: Long) : StudioAction
    data class FinishDragAudioClip(val trackId: Long, val clipId: Long, val newOffsetMs: Long) : StudioAction
    data object CancelDragAudioClip : StudioAction
    data class StartTrimAudioClip(val clipId: Long, val trackId: Long, val edge: TrimEdge) : StudioAction
    data class UpdateTrimAudioClip(val previewTrimStartMs: Long, val previewTrimEndMs: Long) : StudioAction
    data class FinishTrimAudioClip(val clipId: Long, val trackId: Long, val trimStartMs: Long, val trimEndMs: Long) : StudioAction
    data object CancelTrimAudioClip : StudioAction
    data class ActivateTake(val clipId: Long, val takeId: Long, val trackId: Long) : StudioAction
    data class DeleteAudioClip(val trackId: Long, val clipId: Long) : StudioAction

    // Take rename / delete (now clip-scoped)
    data class RequestRenameTake(val takeId: Long, val clipId: Long, val currentName: String) : StudioAction
    data class ConfirmRenameTake(val takeId: Long, val newName: String) : StudioAction
    data object DismissRenameTake : StudioAction
    data class RequestDeleteTake(val takeId: Long, val clipId: Long) : StudioAction
    data object DismissDeleteTake : StudioAction
    data object ExecuteDeleteTake : StudioAction

    // Time signature / Snap / Grid
    data class SetTimeSignature(val numerator: Int, val denominator: Int) : StudioAction
    data object ToggleSnap : StudioAction
    data class SetGridResolution(val resolution: Int) : StudioAction

    // Drum sequencer
    data class ToggleDrumStep(
        val trackId: Long,
        val stepIndex: Int,
        val drumNote: Int
    ) : StudioAction
    data class SetBpm(val bpm: Double) : StudioAction
    data class SetPatternBars(val trackId: Long, val bars: Int) : StudioAction
    data class SetPatternResolution(val trackId: Long, val resolution: Int) : StudioAction
    data class SelectDrumClip(val trackId: Long, val clipIndex: Int) : StudioAction

    // Clip creation
    data class CreateDrumClip(val trackId: Long, val offsetMs: Long) : StudioAction
    data class CreateMidiClip(val trackId: Long, val offsetMs: Long) : StudioAction

    // Drum clips
    data class DuplicateClip(val trackId: Long, val clipId: Long) : StudioAction
    data class MoveClip(val trackId: Long, val clipId: Long, val newOffsetMs: Long) : StudioAction
    data class DeleteClip(val trackId: Long, val clipId: Long) : StudioAction

    // Drum clip drag-to-reposition
    data class StartDragClip(val trackId: Long, val clipId: Long) : StudioAction
    data class UpdateDragClip(val previewOffsetMs: Long) : StudioAction
    data class FinishDragClip(val trackId: Long, val clipId: Long, val newOffsetMs: Long) : StudioAction
    data object CancelDragClip : StudioAction

    // Full-screen editors
    data class OpenDrumEditor(val trackId: Long, val clipId: Long? = null) : StudioAction
    data class OpenPianoRoll(val trackId: Long, val clipId: Long? = null) : StudioAction
    data class ShowInstrumentPicker(val trackId: Long) : StudioAction
    data object DismissInstrumentPicker : StudioAction
    data class SetMidiInstrument(val trackId: Long, val program: Int) : StudioAction
    data class PreviewInstrument(val program: Int) : StudioAction

    // MIDI clips
    data class DuplicateMidiClip(val trackId: Long, val clipId: Long) : StudioAction
    data class MoveMidiClip(val trackId: Long, val clipId: Long, val newOffsetMs: Long) : StudioAction
    data class DeleteMidiClip(val trackId: Long, val clipId: Long) : StudioAction
    // Clip action panel (tap-to-expand)
    data class TapClip(val trackId: Long, val clipId: Long, val clipType: String) : StudioAction
    data object DismissClipPanel : StudioAction

    data class StartDragMidiClip(val trackId: Long, val clipId: Long) : StudioAction
    data class UpdateDragMidiClip(val previewOffsetMs: Long) : StudioAction
    data class FinishDragMidiClip(val trackId: Long, val clipId: Long, val newOffsetMs: Long) : StudioAction
    data object CancelDragMidiClip : StudioAction

    // Cursor / Transport
    data class SetCursorPosition(val positionMs: Long) : StudioAction
    data object ToggleReturnToCursor : StudioAction

    // Metronome
    data object ToggleMetronome : StudioAction
    data class SetMetronomeVolume(val volume: Float) : StudioAction
    data class SetCountInBars(val bars: Int) : StudioAction
    data object ToggleMetronomeSettings : StudioAction

    // Inline MiniPianoRoll
    data class SelectMidiClip(val trackId: Long, val clipId: Long) : StudioAction
    data class InlinePlaceNote(val trackId: Long, val clipId: Long, val pitch: Int, val startMs: Long, val durationMs: Long) : StudioAction
    data class InlineMoveNote(val trackId: Long, val noteId: Long, val newStartMs: Long, val newPitch: Int) : StudioAction
    data class InlineResizeNote(val trackId: Long, val noteId: Long, val newDurationMs: Long) : StudioAction
    data class InlineDeleteNote(val trackId: Long, val noteId: Long) : StudioAction
}

/** One-shot side effects emitted by [StudioViewModel]. */
sealed interface StudioEffect {
    data object NavigateBack : StudioEffect
    data class ShowError(val message: String) : StudioEffect
    data class ShowStatus(val message: String) : StudioEffect
    data object RequestMicPermission : StudioEffect
    data class NavigateToPianoRoll(val trackId: Long, val clipId: Long) : StudioEffect
    data class NavigateToDrumEditor(val trackId: Long, val clipId: Long = 0L) : StudioEffect
}

/** Available track types for the "Add Track" bottom sheet. */
enum class NewTrackType(val label: String, val description: String) {
    AUDIO_RECORDING("Audio Recording", "Record with your microphone"),
    DRUM_SEQUENCER("Drum Sequencer", "Step-based drum pattern"),
    MIDI_INSTRUMENT("MIDI Instrument", "Piano, guitar, bass, synths & more")
}
