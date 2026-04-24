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
    // Scale & chord
    val scaleRoot: Int = 0,
    val scaleType: MusicalScaleHelper.ScaleType = MusicalScaleHelper.ScaleType.MAJOR,
    val isScaleEnabled: Boolean = false,
    val isChordMode: Boolean = false,
    val chordType: MusicalScaleHelper.ChordType = MusicalScaleHelper.ChordType.TRIAD,
    val diatonicChords: List<MusicalScaleHelper.ChordInfo> = emptyList(),
    val isLoading: Boolean = true
)

/** User-initiated actions on the piano roll editor. */
sealed interface PianoRollAction {
    data class PlaceNote(val pitch: Int, val startMs: Long, val durationMs: Long) : PianoRollAction
    data class MoveNotes(val noteIds: Set<Long>, val deltaMs: Long, val deltaPitch: Int) : PianoRollAction
    data class ResizeNotes(val noteIds: Set<Long>, val deltaDurationMs: Long) : PianoRollAction
    data class QuickDeleteNote(val noteId: Long) : PianoRollAction
    data class ToggleNoteSelection(val noteId: Long) : PianoRollAction
    data object ClearSelection : PianoRollAction
    data object DeleteSelected : PianoRollAction
    data object Undo : PianoRollAction
    data object Redo : PianoRollAction
    data class PreviewPitch(val pitch: Int) : PianoRollAction
    data object ToggleSnap : PianoRollAction
    data object CycleGridResolution : PianoRollAction
    data object Play : PianoRollAction
    data object Pause : PianoRollAction
    data class SeekTo(val positionMs: Long) : PianoRollAction
    // Scale & chord
    data object ToggleScale : PianoRollAction
    data class SetScaleRoot(val root: Int) : PianoRollAction
    data class SetScaleType(val type: MusicalScaleHelper.ScaleType) : PianoRollAction
    data object ToggleChordMode : PianoRollAction
    data object CycleChordType : PianoRollAction
}

/** One-shot effects from the piano roll. */
sealed interface PianoRollEffect {
    data class ShowError(val message: String) : PianoRollEffect
}

private const val PREVIEW_CHANNEL = 15
private const val PREVIEW_VELOCITY = 80
private const val PREVIEW_DURATION_MS = 200L

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
            is PianoRollAction.QuickDeleteNote -> quickDeleteNote(action.noteId)
            is PianoRollAction.ToggleNoteSelection -> toggleNoteSelection(action.noteId)
            PianoRollAction.ClearSelection -> _state.update { it.copy(selectedNoteIds = emptySet()) }
            PianoRollAction.DeleteSelected -> deleteSelected()
            PianoRollAction.Undo -> undo()
            PianoRollAction.Redo -> redo()
            is PianoRollAction.PreviewPitch -> previewPitch(action.pitch)
            PianoRollAction.ToggleSnap -> _state.update { it.copy(isSnapEnabled = !it.isSnapEnabled) }
            PianoRollAction.CycleGridResolution -> cycleGridResolution()
            PianoRollAction.Play -> audioEngine.play()
            PianoRollAction.Pause -> audioEngine.pause()
            is PianoRollAction.SeekTo -> audioEngine.seekTo(action.positionMs)
            // Scale & chord
            PianoRollAction.ToggleScale -> toggleScale()
            is PianoRollAction.SetScaleRoot -> setScaleRoot(action.root)
            is PianoRollAction.SetScaleType -> setScaleType(action.type)
            PianoRollAction.ToggleChordMode -> toggleChordMode()
            PianoRollAction.CycleChordType -> cycleChordType()
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
        val presets = listOf(4, 8, 16, 32)
        val current = _state.value.gridResolution
        val nextIndex = (presets.indexOf(current) + 1) % presets.size
        _state.update { it.copy(gridResolution = presets[nextIndex], stickyNoteDurationMs = null) }
    }

    private fun previewPitch(pitch: Int) {
        previewNoteOffJob?.cancel()
        val program = _state.value.midiProgram
        audioEngine.synthNoteOn(PREVIEW_CHANNEL, pitch, PREVIEW_VELOCITY)

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
