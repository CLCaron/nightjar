package com.example.nightjar.ui.studio

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.MusicalScaleHelper
import com.example.nightjar.audio.MusicalTimeConverter
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.data.db.entity.MidiClipEntity
import com.example.nightjar.data.db.entity.MidiNoteEntity
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.data.repository.MidiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Clip boundary info for rendering in the piano roll. */
data class PianoRollClipInfo(
    val clipId: Long,
    val offsetMs: Long,
    val endMs: Long
)

/** Sub-panel modes for the full-screen piano roll's tab bar. */
enum class PianoRollTab { TOOLS, SCALE, EDIT, INSTR }

/**
 * Per-note metadata used by move/resize operations.
 *
 * Linked clips share the same DB note rows (instance clips carry no notes of
 * their own — reads resolve through the source). A single note thus appears in
 * [PianoRollState.notes] once per linked instance, each copy at a different
 * absolute [MidiNoteEntity.startMs]. This struct carries the *invariant* info
 * about the underlying DB row so operations don't depend on which visual
 * instance was iterated last when the map was built.
 *
 * @property clipId       The note's owning clip id from the DB (always the
 *                        source clip id for linked groups).
 * @property clipOffsetMs The source clip's absolute offset on the timeline.
 * @property rawStartMs   The note's clip-relative startMs as stored in the DB
 *                        (i.e. before any display-time offset is applied).
 */
private data class NoteClipInfo(
    val clipId: Long,
    val clipOffsetMs: Long,
    val rawStartMs: Long
)

/** UI state for the piano roll editor. */
data class PianoRollState(
    val trackId: Long = 0L,
    val ideaId: Long = 0L,
    val trackName: String = "",
    val instrumentName: String = "",
    val midiProgram: Int = 0,
    val midiChannel: Int = 0,
    val trackSortIndex: Int = 0,
    val notes: List<MidiNoteEntity> = emptyList(),
    val clips: List<PianoRollClipInfo> = emptyList(),
    val highlightClipId: Long = 0L,
    val selectedNoteIds: Set<Long> = emptySet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val bpm: Double = 120.0,
    val timeSignatureNumerator: Int = 4,
    val timeSignatureDenominator: Int = 4,
    val gridResolution: Int = 16,
    val isSnapEnabled: Boolean = true,
    val stickyNoteDurationMs: Long? = null,
    // Selector (mirrors Studio's cursor): set by ruler tap, drives RESTART target.
    val selectorMs: Long = 0L,
    // Loop region (engine-level state, synced in phase 7). Drives RESTART when on.
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val isLoopEnabled: Boolean = false,
    // EDIT > VELOC latch: when true, the velocity strip becomes draggable for selected notes.
    val isVelocityEditMode: Boolean = false,
    // Scale & chord
    val scaleRoot: Int = 0,
    val scaleType: MusicalScaleHelper.ScaleType = MusicalScaleHelper.ScaleType.MAJOR,
    val isScaleEnabled: Boolean = false,
    val isChordMode: Boolean = false,
    val chordType: MusicalScaleHelper.ChordType = MusicalScaleHelper.ChordType.TRIAD,
    val diatonicChords: List<MusicalScaleHelper.ChordInfo> = emptyList(),
    // Tab/sub-panel
    val activeTab: PianoRollTab = PianoRollTab.TOOLS,
    val isLoading: Boolean = true
)

/** User-initiated actions on the piano roll editor. */
sealed interface PianoRollAction {
    data class PlaceNote(val pitch: Int, val startMs: Long, val durationMs: Long) : PianoRollAction
    data class MoveNotes(val noteIds: Set<Long>, val deltaMs: Long, val deltaPitch: Int) : PianoRollAction
    data class ResizeNotes(val noteIds: Set<Long>, val deltaDurationMs: Long) : PianoRollAction
    /** Drag the LEFT edge: start moves by deltaStartMs, end stays anchored. */
    data class ResizeNotesLeftEdge(val noteIds: Set<Long>, val deltaStartMs: Long) : PianoRollAction
    data class QuickDeleteNote(val noteId: Long) : PianoRollAction
    data class ToggleNoteSelection(val noteId: Long) : PianoRollAction
    data object ClearSelection : PianoRollAction
    data object DeleteSelected : PianoRollAction
    data object SplitSelected : PianoRollAction
    data object CopySelected : PianoRollAction
    data object Undo : PianoRollAction
    data object Redo : PianoRollAction
    data class PreviewPitch(val pitch: Int) : PianoRollAction
    data object ToggleSnap : PianoRollAction
    data object CycleGridResolution : PianoRollAction
    data class SetGridResolution(val value: Int) : PianoRollAction
    data object Play : PianoRollAction
    data object Pause : PianoRollAction
    data class SeekTo(val positionMs: Long) : PianoRollAction
    data object Restart : PianoRollAction
    // EDIT panel
    data object QuantizeSelected : PianoRollAction
    data object ToggleVelocityEditMode : PianoRollAction
    data class SetNoteVelocities(val newVelocities: Map<Long, Float>) : PianoRollAction
    // Ruler / loop / selector
    data class SetSelector(val ms: Long) : PianoRollAction
    data class SetLoopRegion(val startMs: Long, val endMs: Long) : PianoRollAction
    data object ClearLoopRegion : PianoRollAction
    data object ToggleLoopEnabled : PianoRollAction
    data class SelectNotesInRect(
        val startMs: Long,
        val endMs: Long,
        val pitchRange: IntRange
    ) : PianoRollAction
    // Scale & chord
    data object ToggleScale : PianoRollAction
    data class SetScaleRoot(val root: Int) : PianoRollAction
    data class SetScaleType(val type: MusicalScaleHelper.ScaleType) : PianoRollAction
    data object ToggleChordMode : PianoRollAction
    data object CycleChordType : PianoRollAction
    data class SetChordType(val type: MusicalScaleHelper.ChordType) : PianoRollAction
    // Tab/sub-panel
    data class SwitchTab(val tab: PianoRollTab) : PianoRollAction
    // INSTR (embedded picker)
    data class SetMidiInstrument(val program: Int) : PianoRollAction
}

/** One-shot effects from the piano roll. */
sealed interface PianoRollEffect {
    data class ShowError(val message: String) : PianoRollEffect
}

private const val PREVIEW_CHANNEL = 15
private const val PREVIEW_VELOCITY = 80
private const val PREVIEW_DURATION_MS = 200L
private const val PATCH_AUDITION_PITCH = 60   // Middle C
private const val PATCH_AUDITION_DURATION_MS = 600L

@HiltViewModel
class PianoRollViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val midiRepo: MidiRepository,
    private val ideaRepo: IdeaRepository,
    private val audioEngine: OboeAudioEngine
) : ViewModel() {

    private val trackId: Long = savedStateHandle["trackId"] ?: 0L
    private val ideaId: Long = savedStateHandle["ideaId"] ?: 0L
    private val navClipId: Long = savedStateHandle["clipId"] ?: 0L

    companion object {
        private const val TAG = "PianoRollVM"
        private const val TICK_MS = 16L
    }

    private val _state = MutableStateFlow(PianoRollState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PianoRollEffect>()
    val effects = _effects.asSharedFlow()

    private var tickJob: Job? = null
    private var observeJob: Job? = null
    private var previewNoteOffJob: Job? = null

    /**
     * Maps noteId -> [NoteClipInfo] carrying the note's DB-side identity and
     * clip-relative startMs. Populated during rebuild.
     *
     * For linked-clip groups the same note row renders at multiple absolute
     * positions, so we can't derive the clip-relative startMs by subtracting
     * "the" clip offset from a transformed display value — there isn't one.
     * Storing the DB's raw startMs directly makes the map idempotent across
     * iterations of a linked group and removes the dependency on iteration
     * order.
     */
    private var noteClipMap = mapOf<Long, NoteClipInfo>()

    /** Cached clip entities for resolving note placement. */
    private var clipEntities = listOf<MidiClipEntity>()

    private val undoManager = PianoRollUndoManager()

    init {
        load()
        startTick()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val idea = ideaRepo.getIdeaById(ideaId)
                val track = midiRepo.getTrackById(trackId)

                val clips = midiRepo.ensureClipExists(trackId)
                clipEntities = clips

                val bpm = idea?.bpm ?: 120.0
                val numerator = idea?.timeSignatureNumerator ?: 4
                val denominator = idea?.timeSignatureDenominator ?: 4
                val measureMs = MusicalTimeConverter.msPerMeasure(bpm, numerator, denominator)

                // Build absolute notes from all clips
                val absoluteNotes = mutableListOf<MidiNoteEntity>()
                val clipMap = mutableMapOf<Long, NoteClipInfo>()
                val clipInfos = mutableListOf<PianoRollClipInfo>()
                val offsetByClipId = clips.associate { it.id to it.offsetMs }

                for (clip in clips) {
                    val clipNotes = midiRepo.getNotesForClip(clip.id)
                    val contentEndMs = clipNotes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
                    val clipEndMs = clip.offsetMs + maxOf(contentEndMs, measureMs.toLong())

                    clipInfos.add(PianoRollClipInfo(clip.id, clip.offsetMs, clipEndMs))

                    for (note in clipNotes) {
                        // Shift to absolute position
                        val absNote = note.copy(startMs = clip.offsetMs + note.startMs)
                        absoluteNotes.add(absNote)
                        // Canonicalize to the note's owning (source) clip — `note.clipId`
                        // is the source's id even when the iteration is on an instance.
                        clipMap[note.id] = NoteClipInfo(
                            clipId = note.clipId,
                            clipOffsetMs = offsetByClipId[note.clipId] ?: clip.offsetMs,
                            rawStartMs = note.startMs
                        )
                    }
                }

                noteClipMap = clipMap

                val scaleRoot = idea?.scaleRoot ?: 0
                val scaleType = MusicalScaleHelper.ScaleType.fromName(
                    idea?.scaleType ?: "MAJOR"
                )

                // Pull any active loop from the engine so a region set in
                // Studio shows up here on entry. Studio and the piano roll
                // share the engine's cached state.
                val activeLoop = audioEngine.getCurrentLoopRegion()

                _state.update {
                    it.copy(
                        trackId = trackId,
                        ideaId = ideaId,
                        trackName = track?.displayName ?: "",
                        instrumentName = track?.let { t -> gmInstrumentName(t.midiProgram) } ?: "",
                        midiProgram = track?.midiProgram ?: 0,
                        midiChannel = track?.midiChannel ?: 0,
                        trackSortIndex = track?.sortIndex ?: 0,
                        notes = absoluteNotes,
                        clips = clipInfos,
                        highlightClipId = navClipId,
                        bpm = bpm,
                        timeSignatureNumerator = numerator,
                        timeSignatureDenominator = denominator,
                        gridResolution = idea?.gridResolution ?: 16,
                        scaleRoot = scaleRoot,
                        scaleType = scaleType,
                        loopStartMs = activeLoop?.first,
                        loopEndMs = activeLoop?.second,
                        isLoopEnabled = activeLoop != null,
                        isLoading = false
                    )
                }

                // Observe all notes for this track (any clip changes trigger rebuild)
                observeJob = viewModelScope.launch {
                    midiRepo.observeNotes(trackId).collect {
                        rebuildNotesFromClips()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load piano roll", e)
                _effects.emit(PianoRollEffect.ShowError(e.message ?: "Failed to load"))
            }
        }
    }

    /** Rebuild the absolute note list and clip map from current DB state. */
    private suspend fun rebuildNotesFromClips() {
        try {
            val clips = midiRepo.getClipsForTrack(trackId)
            clipEntities = clips

            val st = _state.value
            val measureMs = MusicalTimeConverter.msPerMeasure(
                st.bpm, st.timeSignatureNumerator, st.timeSignatureDenominator
            )

            val absoluteNotes = mutableListOf<MidiNoteEntity>()
            val clipMap = mutableMapOf<Long, NoteClipInfo>()
            val clipInfos = mutableListOf<PianoRollClipInfo>()
            val offsetByClipId = clips.associate { it.id to it.offsetMs }

            for (clip in clips) {
                val clipNotes = midiRepo.getNotesForClip(clip.id)
                val contentEndMs = clipNotes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
                val clipEndMs = clip.offsetMs + maxOf(contentEndMs, measureMs.toLong())

                clipInfos.add(PianoRollClipInfo(clip.id, clip.offsetMs, clipEndMs))

                for (note in clipNotes) {
                    val absNote = note.copy(startMs = clip.offsetMs + note.startMs)
                    absoluteNotes.add(absNote)
                    clipMap[note.id] = NoteClipInfo(
                        clipId = note.clipId,
                        clipOffsetMs = offsetByClipId[note.clipId] ?: clip.offsetMs,
                        rawStartMs = note.startMs
                    )
                }
            }

            noteClipMap = clipMap
            _state.update { it.copy(notes = absoluteNotes, clips = clipInfos) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebuild notes", e)
        }
    }

    private fun startTick() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                audioEngine.pollState()
                _state.update {
                    it.copy(
                        isPlaying = audioEngine.isPlaying.value,
                        positionMs = audioEngine.positionMs.value,
                        totalDurationMs = audioEngine.totalDurationMs.value
                    )
                }
                delay(TICK_MS)
            }
        }
    }

    fun onAction(action: PianoRollAction) {
        when (action) {
            is PianoRollAction.PlaceNote -> placeNote(action.pitch, action.startMs, action.durationMs)
            is PianoRollAction.MoveNotes -> moveNotes(action.noteIds, action.deltaMs, action.deltaPitch)
            is PianoRollAction.ResizeNotes -> resizeNotes(action.noteIds, action.deltaDurationMs)
            is PianoRollAction.ResizeNotesLeftEdge ->
                resizeNotesLeftEdge(action.noteIds, action.deltaStartMs)
            is PianoRollAction.QuickDeleteNote -> quickDeleteNote(action.noteId)
            is PianoRollAction.ToggleNoteSelection -> toggleNoteSelection(action.noteId)
            PianoRollAction.ClearSelection -> _state.update { it.copy(selectedNoteIds = emptySet()) }
            PianoRollAction.DeleteSelected -> deleteSelected()
            PianoRollAction.SplitSelected -> splitSelected()
            PianoRollAction.CopySelected -> copySelected()
            PianoRollAction.Undo -> undo()
            PianoRollAction.Redo -> redo()
            is PianoRollAction.PreviewPitch -> previewPitch(action.pitch)
            PianoRollAction.ToggleSnap -> _state.update { it.copy(isSnapEnabled = !it.isSnapEnabled) }
            PianoRollAction.CycleGridResolution -> cycleGridResolution()
            is PianoRollAction.SetGridResolution -> setGridResolution(action.value)
            PianoRollAction.Play -> play()
            PianoRollAction.Pause -> pause()
            is PianoRollAction.SeekTo -> audioEngine.seekTo(action.positionMs)
            PianoRollAction.Restart -> restart()
            PianoRollAction.QuantizeSelected -> quantizeSelected()
            PianoRollAction.ToggleVelocityEditMode ->
                _state.update { it.copy(isVelocityEditMode = !it.isVelocityEditMode) }
            is PianoRollAction.SetNoteVelocities -> setNoteVelocities(action.newVelocities)
            is PianoRollAction.SetSelector -> setSelector(action.ms)
            is PianoRollAction.SetLoopRegion -> setLoopRegion(action.startMs, action.endMs)
            PianoRollAction.ClearLoopRegion -> clearLoopRegion()
            PianoRollAction.ToggleLoopEnabled -> toggleLoopEnabled()
            is PianoRollAction.SelectNotesInRect ->
                selectNotesInRect(action.startMs, action.endMs, action.pitchRange)
            // Scale & chord
            PianoRollAction.ToggleScale -> toggleScale()
            is PianoRollAction.SetScaleRoot -> setScaleRoot(action.root)
            is PianoRollAction.SetScaleType -> setScaleType(action.type)
            PianoRollAction.ToggleChordMode -> toggleChordMode()
            PianoRollAction.CycleChordType -> cycleChordType()
            is PianoRollAction.SetChordType -> setChordType(action.type)
            // Tab/sub-panel
            is PianoRollAction.SwitchTab -> _state.update { it.copy(activeTab = action.tab) }
            is PianoRollAction.SetMidiInstrument -> setMidiInstrument(action.program)
        }
    }

    /**
     * Update the track's MIDI program, write to DB, and resync the engine
     * channel so the new patch takes effect immediately. Mirrors Studio's
     * setMidiInstrument flow but scoped to this track only. Plays a brief
     * audition (middle C on the preview channel) so the user hears the new
     * patch on tap without having to play a note manually.
     */
    private fun setMidiInstrument(program: Int) {
        viewModelScope.launch {
            try {
                val ch = _state.value.midiChannel
                midiRepo.setInstrument(trackId, program)
                audioEngine.synthProgramChange(ch, program)
                _state.update {
                    it.copy(
                        midiProgram = program,
                        instrumentName = gmInstrumentName(program)
                    )
                }
                auditionProgram(program)
            } catch (e: Exception) {
                Log.e(TAG, "SetMidiInstrument failed", e)
                _effects.emit(PianoRollEffect.ShowError("Failed to change instrument"))
            }
        }
    }

    /**
     * Play a short middle-C audition on the dedicated preview channel for
     * the given program. Used to confirm a patch change in the INSTR picker.
     */
    private fun auditionProgram(program: Int) {
        previewNoteOffJob?.cancel()
        audioEngine.previewNote(
            channel = PREVIEW_CHANNEL,
            pitch = PATCH_AUDITION_PITCH,
            velocity = PREVIEW_VELOCITY,
            program = program
        )
        previewNoteOffJob = viewModelScope.launch {
            delay(PATCH_AUDITION_DURATION_MS)
            audioEngine.synthNoteOff(PREVIEW_CHANNEL, PATCH_AUDITION_PITCH)
        }
    }

    /**
     * Find which clip contains the given absolute position.
     * Falls back to the first clip if no match.
     */
    private fun findClipForPosition(absoluteMs: Long): MidiClipEntity {
        val st = _state.value
        val measureMs = MusicalTimeConverter.msPerMeasure(
            st.bpm, st.timeSignatureNumerator, st.timeSignatureDenominator
        ).toLong().coerceAtLeast(1L)

        // Check existing clips
        for (clip in clipEntities) {
            val clipInfo = st.clips.find { it.clipId == clip.id }
            val clipEnd = clipInfo?.endMs ?: (clip.offsetMs + measureMs)
            if (absoluteMs >= clip.offsetMs && absoluteMs < clipEnd) {
                return clip
            }
        }

        // Fallback: use the first clip
        return clipEntities.firstOrNull()
            ?: MidiClipEntity(id = 0L, trackId = trackId, offsetMs = 0L)
    }

    private fun placeNote(pitch: Int, absoluteStartMs: Long, durationMs: Long) {
        viewModelScope.launch {
            try {
                val st = _state.value
                val clip = findClipForPosition(absoluteStartMs)
                val clipRelativeMs = absoluteStartMs - clip.offsetMs

                val pitches = if (st.isChordMode) {
                    MusicalScaleHelper.getChordPitches(
                        pitch, st.scaleRoot, st.scaleType, st.chordType
                    )
                } else {
                    listOf(pitch)
                }

                val noteEntities = pitches.map { p ->
                    MidiNoteEntity(
                        trackId = trackId,
                        clipId = clip.id,
                        pitch = p,
                        startMs = clipRelativeMs,
                        durationMs = durationMs
                    )
                }
                val newIds = midiRepo.insertNotes(noteEntities)
                val snapshots = midiRepo.getNotesByIds(newIds)

                undoManager.push(NoteOperation.Place(noteIds = newIds, snapshots = snapshots))
                syncUndoRedoState()

                midiRepo.recomputeTrackDuration(trackId)
                previewPitch(pitch)
                _state.update { it.copy(selectedNoteIds = newIds.toSet()) }
            } catch (e: Exception) {
                _effects.emit(PianoRollEffect.ShowError("Failed to add note"))
            }
        }
    }

    private fun moveNotes(noteIds: Set<Long>, deltaMs: Long, deltaPitch: Int) {
        if (noteIds.isEmpty()) return
        viewModelScope.launch {
            try {
                val st = _state.value
                val entries = mutableListOf<NoteOperation.MoveBatch.MoveEntry>()

                for (id in noteIds) {
                    val note = st.notes.find { it.id == id } ?: continue
                    val info = noteClipMap[id] ?: continue
                    val oldClipRelativeMs = info.rawStartMs
                    val newClipRelativeMs = (oldClipRelativeMs + deltaMs).coerceAtLeast(0L)
                    val newPitch = (note.pitch + deltaPitch).coerceIn(0, 127)

                    midiRepo.updateNoteTiming(id, newClipRelativeMs, note.durationMs)
                    if (newPitch != note.pitch) {
                        midiRepo.updateNotePitch(id, newPitch)
                    }

                    entries.add(
                        NoteOperation.MoveBatch.MoveEntry(
                            noteId = id,
                            oldStartMs = oldClipRelativeMs,
                            newStartMs = newClipRelativeMs,
                            oldPitch = note.pitch,
                            newPitch = newPitch
                        )
                    )
                }

                if (entries.isNotEmpty()) {
                    undoManager.push(NoteOperation.MoveBatch(entries))
                    syncUndoRedoState()
                }

                midiRepo.recomputeTrackDuration(trackId)
            } catch (e: Exception) {
                _effects.emit(PianoRollEffect.ShowError("Failed to move notes"))
            }
        }
    }

    private fun resizeNotes(noteIds: Set<Long>, deltaDurationMs: Long) {
        if (noteIds.isEmpty()) return
        viewModelScope.launch {
            try {
                val st = _state.value
                val entries = mutableListOf<NoteOperation.ResizeBatch.ResizeEntry>()
                var lastClamped = 0L

                for (id in noteIds) {
                    val note = st.notes.find { it.id == id } ?: continue
                    val info = noteClipMap[id] ?: continue
                    val clipRelativeMs = info.rawStartMs
                    val clamped = (note.durationMs + deltaDurationMs).coerceAtLeast(50L)

                    midiRepo.updateNoteTiming(id, clipRelativeMs, clamped)

                    entries.add(
                        NoteOperation.ResizeBatch.ResizeEntry(
                            noteId = id,
                            clipRelativeStartMs = clipRelativeMs,
                            oldDurationMs = note.durationMs,
                            newDurationMs = clamped
                        )
                    )
                    lastClamped = clamped
                }

                if (entries.isNotEmpty()) {
                    undoManager.push(NoteOperation.ResizeBatch(entries))
                    syncUndoRedoState()
                    _state.update { it.copy(stickyNoteDurationMs = lastClamped) }
                }

                midiRepo.recomputeTrackDuration(trackId)
            } catch (e: Exception) {
                _effects.emit(PianoRollEffect.ShowError("Failed to resize notes"))
            }
        }
    }

    /**
     * Resize from the LEFT edge: move startMs by deltaStartMs and shrink/grow
     * durationMs by the same amount so the right edge stays anchored. Each
     * note's delta is independently capped: minimum duration of 50ms and
     * clip-relative start clamped at 0. Pushes one Composite undo entry
     * (MoveBatch + ResizeBatch) so undo restores the original position and
     * length together.
     */
    private fun resizeNotesLeftEdge(noteIds: Set<Long>, deltaStartMs: Long) {
        if (noteIds.isEmpty()) return
        viewModelScope.launch {
            try {
                val st = _state.value
                val moveEntries = mutableListOf<NoteOperation.MoveBatch.MoveEntry>()
                val resizeEntries = mutableListOf<NoteOperation.ResizeBatch.ResizeEntry>()

                for (id in noteIds) {
                    val note = st.notes.find { it.id == id } ?: continue
                    val info = noteClipMap[id] ?: continue
                    val oldClipRel = info.rawStartMs
                    val oldDuration = note.durationMs
                    // Cap so duration stays >= 50ms and clip-rel start stays >= 0.
                    val capped = deltaStartMs
                        .coerceAtMost(oldDuration - 50L)
                        .coerceAtLeast(-oldClipRel)
                    if (capped == 0L) continue
                    val newClipRel = oldClipRel + capped
                    val newDuration = oldDuration - capped

                    midiRepo.updateNoteTiming(id, newClipRel, newDuration)

                    moveEntries.add(
                        NoteOperation.MoveBatch.MoveEntry(
                            noteId = id,
                            oldStartMs = oldClipRel,
                            newStartMs = newClipRel,
                            oldPitch = note.pitch,
                            newPitch = note.pitch
                        )
                    )
                    resizeEntries.add(
                        NoteOperation.ResizeBatch.ResizeEntry(
                            noteId = id,
                            clipRelativeStartMs = newClipRel,
                            oldDurationMs = oldDuration,
                            newDurationMs = newDuration
                        )
                    )
                }

                if (moveEntries.isEmpty()) return@launch

                undoManager.push(
                    NoteOperation.Composite(
                        ops = listOf(
                            NoteOperation.MoveBatch(moveEntries),
                            NoteOperation.ResizeBatch(resizeEntries)
                        )
                    )
                )
                syncUndoRedoState()
                midiRepo.recomputeTrackDuration(trackId)
            } catch (e: Exception) {
                Log.e(TAG, "Left-edge resize failed", e)
                _effects.emit(PianoRollEffect.ShowError("Failed to resize notes"))
            }
        }
    }

    private fun quickDeleteNote(noteId: Long) {
        viewModelScope.launch {
            try {
                val snapshots = midiRepo.getNotesByIds(listOf(noteId))
                if (snapshots.isEmpty()) return@launch

                midiRepo.deleteNotes(listOf(noteId))

                undoManager.push(NoteOperation.Delete(noteIds = listOf(noteId), snapshots = snapshots))
                syncUndoRedoState()

                midiRepo.recomputeTrackDuration(trackId)
                _state.update {
                    it.copy(selectedNoteIds = it.selectedNoteIds - noteId)
                }
            } catch (e: Exception) {
                _effects.emit(PianoRollEffect.ShowError("Failed to delete note"))
            }
        }
    }

    private fun toggleNoteSelection(noteId: Long) {
        _state.update {
            val updated = it.selectedNoteIds.toMutableSet()
            if (noteId in updated) updated.remove(noteId) else updated.add(noteId)
            it.copy(selectedNoteIds = updated)
        }
    }

    private fun deleteSelected() {
        viewModelScope.launch {
            try {
                val ids = _state.value.selectedNoteIds.toList()
                if (ids.isEmpty()) return@launch

                val snapshots = midiRepo.getNotesByIds(ids)
                midiRepo.deleteNotes(ids)

                undoManager.push(NoteOperation.Delete(noteIds = ids, snapshots = snapshots))
                syncUndoRedoState()

                midiRepo.recomputeTrackDuration(trackId)
                _state.update { it.copy(selectedNoteIds = emptySet()) }
            } catch (e: Exception) {
                _effects.emit(PianoRollEffect.ShowError("Failed to delete notes"))
            }
        }
    }

    /**
     * Split selected notes at the playhead, falling back to the note's midpoint
     * when the playhead is outside it. Each selected note becomes two notes that
     * sum to the original duration. Notes shorter than 100ms are left alone.
     * Wrapped as a single undo entry via NoteOperation.Composite.
     */
    private fun splitSelected() {
        viewModelScope.launch {
            try {
                val st = _state.value
                if (st.selectedNoteIds.isEmpty()) return@launch

                val resizeEntries = mutableListOf<NoteOperation.ResizeBatch.ResizeEntry>()
                val newNoteEntities = mutableListOf<MidiNoteEntity>()

                for (id in st.selectedNoteIds) {
                    val note = st.notes.find { it.id == id } ?: continue
                    val info = noteClipMap[id] ?: continue
                    if (note.durationMs < 100L) continue   // too short to split meaningfully

                    val noteStartAbs = note.startMs
                    val noteEndAbs = noteStartAbs + note.durationMs
                    val splitAbs = if (st.positionMs in (noteStartAbs + 50)..(noteEndAbs - 50)) {
                        st.positionMs
                    } else {
                        noteStartAbs + note.durationMs / 2
                    }
                    val firstHalfDuration = splitAbs - noteStartAbs
                    val secondHalfDuration = note.durationMs - firstHalfDuration
                    if (firstHalfDuration < 50L || secondHalfDuration < 50L) continue

                    val clipRelStart = info.rawStartMs
                    val clipRelSplit = clipRelStart + firstHalfDuration

                    // Truncate the original to the first half.
                    midiRepo.updateNoteTiming(id, clipRelStart, firstHalfDuration)
                    resizeEntries.add(
                        NoteOperation.ResizeBatch.ResizeEntry(
                            noteId = id,
                            clipRelativeStartMs = clipRelStart,
                            oldDurationMs = note.durationMs,
                            newDurationMs = firstHalfDuration
                        )
                    )

                    // Build the second half (same pitch, same clip).
                    newNoteEntities.add(
                        MidiNoteEntity(
                            trackId = trackId,
                            clipId = info.clipId,
                            pitch = note.pitch,
                            startMs = clipRelSplit,
                            durationMs = secondHalfDuration
                        )
                    )
                }

                if (resizeEntries.isEmpty() && newNoteEntities.isEmpty()) return@launch

                val newIds = if (newNoteEntities.isNotEmpty()) {
                    midiRepo.insertNotes(newNoteEntities)
                } else emptyList()
                val newSnapshots = if (newIds.isNotEmpty()) midiRepo.getNotesByIds(newIds) else emptyList()

                val composite = NoteOperation.Composite(
                    ops = buildList {
                        if (resizeEntries.isNotEmpty()) {
                            add(NoteOperation.ResizeBatch(resizeEntries))
                        }
                        if (newIds.isNotEmpty()) {
                            add(NoteOperation.Place(noteIds = newIds, snapshots = newSnapshots))
                        }
                    }
                )
                undoManager.push(composite)
                syncUndoRedoState()

                midiRepo.recomputeTrackDuration(trackId)
                // Selection now includes both halves of every split note.
                _state.update {
                    it.copy(selectedNoteIds = it.selectedNoteIds + newIds.toSet())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Split failed", e)
                _effects.emit(PianoRollEffect.ShowError("Failed to split notes"))
            }
        }
    }

    /**
     * Duplicate selected notes immediately to the right of the selection with
     * no gap. Each new note keeps its pitch and duration but starts at
     * (originalStart + selectionSpan). Single Place undo entry.
     */
    private fun copySelected() {
        viewModelScope.launch {
            try {
                val st = _state.value
                if (st.selectedNoteIds.isEmpty()) return@launch
                val sourceNotes = st.notes.filter { it.id in st.selectedNoteIds }
                if (sourceNotes.isEmpty()) return@launch

                val minStartAbs = sourceNotes.minOf { it.startMs }
                val maxEndAbs = sourceNotes.maxOf { it.startMs + it.durationMs }
                val spanMs = (maxEndAbs - minStartAbs).coerceAtLeast(50L)

                val newEntities = sourceNotes.map { src ->
                    val newAbsStart = src.startMs + spanMs
                    val targetClip = findClipForPosition(newAbsStart)
                    val newClipRel = (newAbsStart - targetClip.offsetMs).coerceAtLeast(0L)
                    MidiNoteEntity(
                        trackId = trackId,
                        clipId = targetClip.id,
                        pitch = src.pitch,
                        startMs = newClipRel,
                        durationMs = src.durationMs
                    )
                }

                val newIds = midiRepo.insertNotes(newEntities)
                val newSnapshots = midiRepo.getNotesByIds(newIds)

                undoManager.push(NoteOperation.Place(noteIds = newIds, snapshots = newSnapshots))
                syncUndoRedoState()

                midiRepo.recomputeTrackDuration(trackId)
                _state.update { it.copy(selectedNoteIds = newIds.toSet()) }
            } catch (e: Exception) {
                Log.e(TAG, "Copy failed", e)
                _effects.emit(PianoRollEffect.ShowError("Failed to copy notes"))
            }
        }
    }

    private fun undo() {
        viewModelScope.launch {
            try {
                val op = undoManager.popUndo() ?: return@launch
                executeOperation(op, reverse = true)
                syncUndoRedoState()
                _state.update { it.copy(selectedNoteIds = emptySet()) }
                midiRepo.recomputeTrackDuration(trackId)
            } catch (e: Exception) {
                Log.e(TAG, "Undo failed", e)
                _effects.emit(PianoRollEffect.ShowError("Undo failed"))
            }
        }
    }

    private fun redo() {
        viewModelScope.launch {
            try {
                val op = undoManager.popRedo() ?: return@launch
                executeOperation(op, reverse = false)
                syncUndoRedoState()
                _state.update { it.copy(selectedNoteIds = emptySet()) }
                midiRepo.recomputeTrackDuration(trackId)
            } catch (e: Exception) {
                Log.e(TAG, "Redo failed", e)
                _effects.emit(PianoRollEffect.ShowError("Redo failed"))
            }
        }
    }

    private suspend fun executeOperation(op: NoteOperation, reverse: Boolean) {
        when (op) {
            is NoteOperation.Place -> {
                if (reverse) {
                    // Undo place = delete
                    midiRepo.deleteNotes(op.noteIds)
                } else {
                    // Redo place = re-insert from snapshots
                    val entities = op.snapshots.map { it.copy(id = 0L) }
                    val newIds = midiRepo.insertNotes(entities)
                    op.noteIds = newIds
                }
            }
            is NoteOperation.Delete -> {
                if (reverse) {
                    // Undo delete = re-insert from snapshots
                    val entities = op.snapshots.map { it.copy(id = 0L) }
                    val newIds = midiRepo.insertNotes(entities)
                    op.noteIds = newIds
                } else {
                    // Redo delete = delete again
                    midiRepo.deleteNotes(op.noteIds)
                }
            }
            is NoteOperation.MoveBatch -> {
                for (entry in op.entries) {
                    val targetMs = if (reverse) entry.oldStartMs else entry.newStartMs
                    val targetPitch = if (reverse) entry.oldPitch else entry.newPitch
                    val dbNote = midiRepo.getNotesByIds(listOf(entry.noteId)).firstOrNull()
                        ?: continue
                    midiRepo.updateNoteTiming(entry.noteId, targetMs, dbNote.durationMs)
                    if (targetPitch != dbNote.pitch) {
                        midiRepo.updateNotePitch(entry.noteId, targetPitch)
                    }
                }
            }
            is NoteOperation.ResizeBatch -> {
                for (entry in op.entries) {
                    val targetDuration = if (reverse) entry.oldDurationMs else entry.newDurationMs
                    midiRepo.updateNoteTiming(entry.noteId, entry.clipRelativeStartMs, targetDuration)
                }
            }
            is NoteOperation.Composite -> {
                // Reverse: undo each sub-op in reverse order with reverse semantics.
                // Forward: redo each sub-op in order with forward semantics.
                val ordered = if (reverse) op.ops.reversed() else op.ops
                for (sub in ordered) {
                    executeOperation(sub, reverse)
                }
            }
            is NoteOperation.VelocityBatch -> {
                for (entry in op.entries) {
                    val target = if (reverse) entry.oldVelocity else entry.newVelocity
                    midiRepo.updateNoteVelocity(entry.noteId, target)
                }
            }
        }
    }

    private fun syncUndoRedoState() {
        _state.update {
            it.copy(canUndo = undoManager.canUndo, canRedo = undoManager.canRedo)
        }
    }

    // ── Scale & chord handlers ──────────────────────────────────────

    private fun toggleScale() {
        _state.update {
            val enabled = !it.isScaleEnabled
            val chords = if (enabled) {
                MusicalScaleHelper.getDiatonicChords(it.scaleRoot, it.scaleType, it.chordType)
            } else {
                emptyList()
            }
            it.copy(isScaleEnabled = enabled, diatonicChords = chords)
        }
    }

    private fun setScaleRoot(root: Int) {
        val clamped = root.coerceIn(0, 11)
        _state.update {
            val chords = if (it.isScaleEnabled) {
                MusicalScaleHelper.getDiatonicChords(clamped, it.scaleType, it.chordType)
            } else emptyList()
            it.copy(scaleRoot = clamped, diatonicChords = chords)
        }
        persistScale()
    }

    private fun setScaleType(type: MusicalScaleHelper.ScaleType) {
        _state.update {
            val chords = if (it.isScaleEnabled) {
                MusicalScaleHelper.getDiatonicChords(it.scaleRoot, type, it.chordType)
            } else emptyList()
            it.copy(scaleType = type, diatonicChords = chords)
        }
        persistScale()
    }

    private fun toggleChordMode() {
        _state.update { it.copy(isChordMode = !it.isChordMode) }
    }

    private fun cycleChordType() {
        val types = MusicalScaleHelper.ChordType.entries
        _state.update {
            val nextIndex = (types.indexOf(it.chordType) + 1) % types.size
            val newType = types[nextIndex]
            val chords = if (it.isScaleEnabled) {
                MusicalScaleHelper.getDiatonicChords(it.scaleRoot, it.scaleType, newType)
            } else emptyList()
            it.copy(chordType = newType, diatonicChords = chords)
        }
    }

    private fun setChordType(type: MusicalScaleHelper.ChordType) {
        if (_state.value.chordType == type) return
        _state.update {
            val chords = if (it.isScaleEnabled) {
                MusicalScaleHelper.getDiatonicChords(it.scaleRoot, it.scaleType, type)
            } else emptyList()
            it.copy(chordType = type, diatonicChords = chords)
        }
    }

    private fun persistScale() {
        viewModelScope.launch {
            try {
                val st = _state.value
                ideaRepo.updateScale(ideaId, st.scaleRoot, st.scaleType.name)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist scale", e)
            }
        }
    }

    // ── Grid ─────────────────────────────────────────────────────────

    private fun cycleGridResolution() {
        val presets = listOf(2, 4, 8, 16, 32)
        val current = _state.value.gridResolution
        val nextIndex = (presets.indexOf(current).coerceAtLeast(0) + 1) % presets.size
        _state.update { it.copy(gridResolution = presets[nextIndex], stickyNoteDurationMs = null) }
    }

    private fun setGridResolution(value: Int) {
        if (value <= 0) return
        _state.update { it.copy(gridResolution = value, stickyNoteDurationMs = null) }
    }

    /**
     * Quantize selected notes (or all notes when no selection) to the active
     * grid resolution. Each affected note's startMs snaps to the nearest grid
     * line; duration is preserved. Pitch is untouched. Pushes a single
     * MoveBatch undo entry so the user can revert in one step.
     */
    private fun quantizeSelected() {
        viewModelScope.launch {
            try {
                val st = _state.value
                val targetIds = if (st.selectedNoteIds.isNotEmpty()) {
                    st.selectedNoteIds
                } else {
                    st.notes.map { it.id }.toSet()
                }
                if (targetIds.isEmpty()) return@launch

                val entries = mutableListOf<NoteOperation.MoveBatch.MoveEntry>()
                for (id in targetIds) {
                    val note = st.notes.find { it.id == id } ?: continue
                    val info = noteClipMap[id] ?: continue
                    val snappedAbs = MusicalTimeConverter.snapToGrid(
                        note.startMs, st.bpm, st.gridResolution, st.timeSignatureDenominator
                    )
                    val deltaMs = snappedAbs - note.startMs
                    if (deltaMs == 0L) continue   // already on grid -- skip

                    val oldClipRel = info.rawStartMs
                    val newClipRel = (oldClipRel + deltaMs).coerceAtLeast(0L)

                    midiRepo.updateNoteTiming(id, newClipRel, note.durationMs)
                    entries.add(
                        NoteOperation.MoveBatch.MoveEntry(
                            noteId = id,
                            oldStartMs = oldClipRel,
                            newStartMs = newClipRel,
                            oldPitch = note.pitch,
                            newPitch = note.pitch
                        )
                    )
                }

                if (entries.isEmpty()) return@launch

                undoManager.push(NoteOperation.MoveBatch(entries))
                syncUndoRedoState()
                midiRepo.recomputeTrackDuration(trackId)
            } catch (e: Exception) {
                Log.e(TAG, "Quantize failed", e)
                _effects.emit(PianoRollEffect.ShowError("Quantize failed"))
            }
        }
    }

    /**
     * Apply a batch of velocity changes -- one per noteId -- and record a
     * single VelocityBatch undo entry. Called on velocity-strip drag release.
     * Notes whose new velocity equals the old are skipped so empty-drag
     * gestures don't pollute the undo stack.
     */
    private fun setNoteVelocities(newVelocities: Map<Long, Float>) {
        if (newVelocities.isEmpty()) return
        viewModelScope.launch {
            try {
                val st = _state.value
                val entries = mutableListOf<NoteOperation.VelocityBatch.VelocityEntry>()
                for ((id, newVel) in newVelocities) {
                    val note = st.notes.find { it.id == id } ?: continue
                    val clamped = newVel.coerceIn(0f, 1f)
                    if (clamped == note.velocity) continue
                    midiRepo.updateNoteVelocity(id, clamped)
                    entries.add(
                        NoteOperation.VelocityBatch.VelocityEntry(
                            noteId = id,
                            oldVelocity = note.velocity,
                            newVelocity = clamped
                        )
                    )
                }
                if (entries.isEmpty()) return@launch
                undoManager.push(NoteOperation.VelocityBatch(entries))
                syncUndoRedoState()
            } catch (e: Exception) {
                Log.e(TAG, "Set velocities failed", e)
                _effects.emit(PianoRollEffect.ShowError("Failed to set velocity"))
            }
        }
    }

    /**
     * Set the selector position -- the user's "where I want to start from"
     * marker. Tapping the ruler does NOT move the playhead; only PLAY and
     * RESTART consult the selector. This keeps the marker concept independent
     * from the playhead so the user can scout a start point without losing
     * their current playback position.
     */
    private fun setSelector(ms: Long) {
        val st = _state.value
        val snapped = MusicalTimeConverter.snapToGrid(
            ms.coerceAtLeast(0L), st.bpm, st.gridResolution, st.timeSignatureDenominator
        )
        _state.update { it.copy(selectorMs = snapped) }
    }

    /**
     * Start playback from the selector position. If the selector is at zero
     * (default after load) the song plays from the start; if the user has
     * tapped a position on the ruler, PLAY honors that tap.
     */
    private fun play() {
        val st = _state.value
        audioEngine.seekTo(st.selectorMs)
        audioEngine.play()
    }

    /**
     * Pause and snapshot the current playback position into the selector so
     * a follow-up PLAY resumes where it left off. The user can still tap
     * elsewhere on the ruler before pressing PLAY to override.
     */
    private fun pause() {
        audioEngine.pause()
        val pos = audioEngine.positionMs.value
        _state.update { it.copy(selectorMs = pos) }
    }

    /**
     * Set or replace the loop region. Snaps both endpoints to grid. Pushes
     * the region to the audio engine, so playback wraps within it.
     */
    private fun setLoopRegion(startMs: Long, endMs: Long) {
        val st = _state.value
        val a = startMs.coerceAtLeast(0L)
        val b = endMs.coerceAtLeast(0L)
        val rawStart = minOf(a, b)
        val rawEnd = maxOf(a, b)
        val snappedStart = MusicalTimeConverter.snapToGrid(
            rawStart, st.bpm, st.gridResolution, st.timeSignatureDenominator
        )
        val snappedEnd = MusicalTimeConverter.snapToGrid(
            rawEnd, st.bpm, st.gridResolution, st.timeSignatureDenominator
        )
        // Treat zero-length drags as "clear": snapping can collapse a tiny
        // span when both endpoints land in the same grid cell.
        if (snappedEnd <= snappedStart) {
            clearLoopRegion()
            return
        }
        _state.update {
            it.copy(
                loopStartMs = snappedStart,
                loopEndMs = snappedEnd,
                isLoopEnabled = true
            )
        }
        audioEngine.setLoopRegion(snappedStart, snappedEnd)
    }

    private fun clearLoopRegion() {
        _state.update {
            it.copy(loopStartMs = null, loopEndMs = null, isLoopEnabled = false)
        }
        audioEngine.clearLoopRegion()
    }

    /**
     * Toggle the loop's engaged state. When the region exists but is
     * disengaged, calling this re-engages it without the user having to
     * redraw the bracket. When engaged, calling this disengages without
     * losing the region (matches Studio's behavior).
     */
    /**
     * Replace the current selection with all notes whose bounding rect
     * intersects the given timeline span and pitch range. Marquee semantics:
     * a fresh marquee resets selection to the matched set.
     */
    private fun selectNotesInRect(startMs: Long, endMs: Long, pitchRange: IntRange) {
        val st = _state.value
        val matched = st.notes.asSequence()
            .filter { note ->
                val noteEnd = note.startMs + note.durationMs
                note.pitch in pitchRange &&
                    noteEnd >= startMs &&
                    note.startMs <= endMs
            }
            .map { it.id }
            .toSet()
        _state.update { it.copy(selectedNoteIds = matched) }
    }

    private fun toggleLoopEnabled() {
        val st = _state.value
        when {
            st.isLoopEnabled -> {
                _state.update { it.copy(isLoopEnabled = false) }
                audioEngine.clearLoopRegion()
            }
            !st.isLoopEnabled && st.loopStartMs != null && st.loopEndMs != null -> {
                _state.update { it.copy(isLoopEnabled = true) }
                audioEngine.setLoopRegion(st.loopStartMs, st.loopEndMs)
            }
            else -> Unit  // no region defined, nothing to toggle
        }
    }

    /**
     * Restart playback to the natural restart point: loop start if loop is on,
     * else the user-set selector position, else 0. Preserves playback state --
     * does NOT pause. This mirrors Studio's planned restart-button behavior
     * and replaces the legacy direct seekTo(0) wired to the old toolbar.
     */
    private fun restart() {
        val st = _state.value
        val target = when {
            st.isLoopEnabled && st.loopStartMs != null -> st.loopStartMs
            st.selectorMs > 0L -> st.selectorMs
            else -> 0L
        }
        audioEngine.seekTo(target)
    }

    private fun previewPitch(pitch: Int) {
        previewNoteOffJob?.cancel()
        // Align the preview channel with the track's currently-selected
        // instrument before noteOn so the preview reflects what the user
        // chose; otherwise FluidSynth defaults the preview channel to
        // Acoustic Grand and ignores the picker.
        audioEngine.previewNote(
            channel = PREVIEW_CHANNEL,
            pitch = pitch,
            velocity = PREVIEW_VELOCITY,
            program = _state.value.midiProgram
        )

        previewNoteOffJob = viewModelScope.launch {
            delay(PREVIEW_DURATION_MS)
            audioEngine.synthNoteOff(PREVIEW_CHANNEL, pitch)
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
        observeJob?.cancel()
        previewNoteOffJob?.cancel()
    }
}
