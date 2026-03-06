package com.example.nightjar.ui.studio

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.MusicalTimeConverter
import com.example.nightjar.audio.OboeAudioEngine
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

/** UI state for the piano roll editor. */
data class PianoRollState(
    val trackId: Long = 0L,
    val ideaId: Long = 0L,
    val trackName: String = "",
    val instrumentName: String = "",
    val midiProgram: Int = 0,
    val midiChannel: Int = 0,
    val notes: List<MidiNoteEntity> = emptyList(),
    val selectedNoteId: Long? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val bpm: Double = 120.0,
    val timeSignatureNumerator: Int = 4,
    val timeSignatureDenominator: Int = 4,
    val isSnapEnabled: Boolean = true,
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

    init {
        load()
        startTick()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val idea = ideaRepo.getIdeaById(ideaId)
                val track = midiRepo.run {
                    // Get track info from DAO via repository's internal trackDao
                    getNotesForTrack(trackId) // warm up
                    null // We need the track entity
                }

                // Fetch track info via the notes DAO path -- we need track metadata
                // Use idea to get bpm and time signature
                val notes = midiRepo.getNotesForTrack(trackId)

                _state.update {
                    it.copy(
                        trackId = trackId,
                        ideaId = ideaId,
                        notes = notes,
                        bpm = idea?.bpm ?: 120.0,
                        timeSignatureNumerator = idea?.timeSignatureNumerator ?: 4,
                        timeSignatureDenominator = idea?.timeSignatureDenominator ?: 4,
                        isLoading = false
                    )
                }

                // Start observing notes
                observeJob = viewModelScope.launch {
                    midiRepo.observeNotes(trackId).collect { updatedNotes ->
                        _state.update { it.copy(notes = updatedNotes) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load piano roll", e)
                _effects.emit(PianoRollEffect.ShowError(e.message ?: "Failed to load"))
            }
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
            PianoRollAction.Play -> audioEngine.play()
            PianoRollAction.Pause -> audioEngine.pause()
            is PianoRollAction.SeekTo -> audioEngine.seekTo(action.positionMs)
        }
    }

    private fun placeNote(pitch: Int, startMs: Long, durationMs: Long) {
        viewModelScope.launch {
            try {
                val noteId = midiRepo.addNote(trackId, pitch, startMs, durationMs)
                midiRepo.recomputeTrackDuration(trackId)
                previewPitch(pitch)
                _state.update { it.copy(selectedNoteId = noteId) }
            } catch (e: Exception) {
                _effects.emit(PianoRollEffect.ShowError("Failed to add note"))
            }
        }
    }

    private fun moveNote(noteId: Long, newStartMs: Long) {
        viewModelScope.launch {
            try {
                val note = _state.value.notes.find { it.id == noteId } ?: return@launch
                midiRepo.updateNoteTiming(noteId, newStartMs, note.durationMs)
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
                val clamped = newDurationMs.coerceAtLeast(50L) // minimum 50ms
                midiRepo.updateNoteTiming(noteId, note.startMs, clamped)
                midiRepo.recomputeTrackDuration(trackId)
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

    private fun previewPitch(pitch: Int) {
        previewNoteOffJob?.cancel()
        // Use the track's program on the preview channel
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
