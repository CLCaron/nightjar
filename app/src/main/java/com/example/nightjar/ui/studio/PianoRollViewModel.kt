package com.example.nightjar.ui.studio

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val selectedNoteId: Long? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val bpm: Double = 120.0,
    val timeSignatureNumerator: Int = 4,
    val timeSignatureDenominator: Int = 4,
    val gridResolution: Int = 16,
    val isSnapEnabled: Boolean = true,
    val stickyNoteDurationMs: Long? = null,
    val isLoading: Boolean = true
)

/** User-initiated actions on the piano roll editor. */
sealed interface PianoRollAction {
    data class PlaceNote(val pitch: Int, val startMs: Long, val durationMs: Long) : PianoRollAction
    data class MoveNote(val noteId: Long, val newStartMs: Long) : PianoRollAction
    data class ResizeNote(val noteId: Long, val newDurationMs: Long) : PianoRollAction
    data class ChangeNotePitch(val noteId: Long, val newPitch: Int) : PianoRollAction
    data class DeleteNote(val noteId: Long) : PianoRollAction
    data class SelectNote(val noteId: Long?) : PianoRollAction
    data class PreviewPitch(val pitch: Int) : PianoRollAction
    data object ToggleSnap : PianoRollAction
    data object CycleGridResolution : PianoRollAction
    data object Play : PianoRollAction
    data object Pause : PianoRollAction
    data class SeekTo(val positionMs: Long) : PianoRollAction
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
     * Maps noteId -> (clipId, clipOffsetMs) for converting between
     * absolute (display) positions and clip-relative (storage) positions.
     */
    private var noteClipMap = mapOf<Long, Pair<Long, Long>>()

    /** Cached clip entities for resolving note placement. */
    private var clipEntities = listOf<MidiClipEntity>()

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
                val clipMap = mutableMapOf<Long, Pair<Long, Long>>()
                val clipInfos = mutableListOf<PianoRollClipInfo>()

                for (clip in clips) {
                    val clipNotes = midiRepo.getNotesForClip(clip.id)
                    val contentEndMs = clipNotes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
                    val clipEndMs = clip.offsetMs + maxOf(contentEndMs, measureMs.toLong())

                    clipInfos.add(PianoRollClipInfo(clip.id, clip.offsetMs, clipEndMs))

                    for (note in clipNotes) {
                        // Shift to absolute position
                        val absNote = note.copy(startMs = clip.offsetMs + note.startMs)
                        absoluteNotes.add(absNote)
                        clipMap[note.id] = clip.id to clip.offsetMs
                    }
                }

                noteClipMap = clipMap

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
            val clipMap = mutableMapOf<Long, Pair<Long, Long>>()
            val clipInfos = mutableListOf<PianoRollClipInfo>()

            for (clip in clips) {
                val clipNotes = midiRepo.getNotesForClip(clip.id)
                val contentEndMs = clipNotes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
                val clipEndMs = clip.offsetMs + maxOf(contentEndMs, measureMs.toLong())

                clipInfos.add(PianoRollClipInfo(clip.id, clip.offsetMs, clipEndMs))

                for (note in clipNotes) {
                    val absNote = note.copy(startMs = clip.offsetMs + note.startMs)
                    absoluteNotes.add(absNote)
                    clipMap[note.id] = clip.id to clip.offsetMs
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
            is PianoRollAction.MoveNote -> moveNote(action.noteId, action.newStartMs)
            is PianoRollAction.ResizeNote -> resizeNote(action.noteId, action.newDurationMs)
            is PianoRollAction.ChangeNotePitch -> changeNotePitch(action.noteId, action.newPitch)
            is PianoRollAction.DeleteNote -> deleteNote(action.noteId)
            is PianoRollAction.SelectNote -> _state.update { it.copy(selectedNoteId = action.noteId) }
            is PianoRollAction.PreviewPitch -> previewPitch(action.pitch)
            PianoRollAction.ToggleSnap -> _state.update { it.copy(isSnapEnabled = !it.isSnapEnabled) }
            PianoRollAction.CycleGridResolution -> cycleGridResolution()
            PianoRollAction.Play -> audioEngine.play()
            PianoRollAction.Pause -> audioEngine.pause()
            is PianoRollAction.SeekTo -> audioEngine.seekTo(action.positionMs)
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
                val clip = findClipForPosition(absoluteStartMs)
                val clipRelativeMs = absoluteStartMs - clip.offsetMs
                val noteId = midiRepo.addNote(clip.id, trackId, pitch, clipRelativeMs, durationMs)
                midiRepo.recomputeTrackDuration(trackId)
                previewPitch(pitch)
                _state.update { it.copy(selectedNoteId = noteId) }
            } catch (e: Exception) {
                _effects.emit(PianoRollEffect.ShowError("Failed to add note"))
            }
        }
    }

    private fun moveNote(noteId: Long, newAbsoluteStartMs: Long) {
        viewModelScope.launch {
            try {
                val note = _state.value.notes.find { it.id == noteId } ?: return@launch
                val (clipId, clipOffset) = noteClipMap[noteId] ?: return@launch
                val clipRelativeMs = newAbsoluteStartMs - clipOffset
                midiRepo.updateNoteTiming(noteId, clipRelativeMs, note.durationMs)
                midiRepo.recomputeTrackDuration(trackId)
            } catch (e: Exception) {
                _effects.emit(PianoRollEffect.ShowError("Failed to move note"))
            }
        }
    }

    private fun resizeNote(noteId: Long, newDurationMs: Long) {
        viewModelScope.launch {
            try {
                val note = _state.value.notes.find { it.id == noteId } ?: return@launch
                val (_, clipOffset) = noteClipMap[noteId] ?: return@launch
                val clipRelativeMs = note.startMs - clipOffset
                val clamped = newDurationMs.coerceAtLeast(50L)
                midiRepo.updateNoteTiming(noteId, clipRelativeMs, clamped)
                midiRepo.recomputeTrackDuration(trackId)
                _state.update { it.copy(stickyNoteDurationMs = clamped) }
            } catch (e: Exception) {
                _effects.emit(PianoRollEffect.ShowError("Failed to resize note"))
            }
        }
    }

    private fun changeNotePitch(noteId: Long, newPitch: Int) {
        viewModelScope.launch {
            try {
                val clamped = newPitch.coerceIn(0, 127)
                midiRepo.updateNotePitch(noteId, clamped)
                previewPitch(clamped)
            } catch (e: Exception) {
                _effects.emit(PianoRollEffect.ShowError("Failed to change pitch"))
            }
        }
    }

    private fun deleteNote(noteId: Long) {
        viewModelScope.launch {
            try {
                midiRepo.deleteNote(noteId)
                midiRepo.recomputeTrackDuration(trackId)
                if (_state.value.selectedNoteId == noteId) {
                    _state.update { it.copy(selectedNoteId = null) }
                }
            } catch (e: Exception) {
                _effects.emit(PianoRollEffect.ShowError("Failed to delete note"))
            }
        }
    }

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
