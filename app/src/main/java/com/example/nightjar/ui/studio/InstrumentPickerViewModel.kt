package com.example.nightjar.ui.studio

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.data.db.dao.TrackDao
import com.example.nightjar.data.repository.MidiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PREVIEW_CHANNEL = 15
private const val PREVIEW_PITCH = 60        // Middle C
private const val PREVIEW_VELOCITY = 80
private const val PREVIEW_DURATION_MS = 300L

/** UI state for the full-screen instrument picker. */
data class InstrumentPickerState(
    val isLoading: Boolean = true,
    val trackId: Long = 0L,
    val trackName: String = "",
    val trackIndex: Int = 1,
    val currentProgram: Int = 0,
    val selectedProgram: Int = 0,
    val selectedCategory: PatchCategory = PatchCategory.KEYS
)

@HiltViewModel
class InstrumentPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val midiRepo: MidiRepository,
    private val trackDao: TrackDao,
    private val audioEngine: OboeAudioEngine
) : ViewModel() {

    private val trackId: Long = savedStateHandle["trackId"] ?: 0L
    private val ideaId: Long = savedStateHandle["ideaId"] ?: 0L

    private val _state = MutableStateFlow(InstrumentPickerState())
    val state = _state.asStateFlow()

    private var previewNoteOffJob: Job? = null

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val track = midiRepo.getTrackById(trackId) ?: return@launch
            val allTracks = trackDao.getTracksForIdea(ideaId)
            val index = (allTracks.indexOfFirst { it.id == trackId } + 1).coerceAtLeast(1)
            val program = track.midiProgram
            val category = curatedPatchFor(program)?.category ?: PatchCategory.KEYS
            _state.value = InstrumentPickerState(
                isLoading = false,
                trackId = trackId,
                trackName = track.displayName,
                trackIndex = index,
                currentProgram = program,
                selectedProgram = program,
                selectedCategory = category
            )
        }
    }

    fun selectCategory(category: PatchCategory) {
        _state.update { it.copy(selectedCategory = category) }
    }

    /**
     * Tap-to-commit: highlight the patch, audition middle C through the preview
     * channel. Persistence is the parent screen's job — see the savedStateHandle
     * write in [InstrumentPickerScreen].
     */
    fun selectProgram(program: Int) {
        _state.update { it.copy(selectedProgram = program) }
        previewProgram(program)
    }

    private fun previewProgram(program: Int) {
        previewNoteOffJob?.cancel()
        audioEngine.previewNote(
            channel = PREVIEW_CHANNEL,
            pitch = PREVIEW_PITCH,
            velocity = PREVIEW_VELOCITY,
            program = program
        )
        previewNoteOffJob = viewModelScope.launch {
            delay(PREVIEW_DURATION_MS)
            audioEngine.synthNoteOff(PREVIEW_CHANNEL, PREVIEW_PITCH)
        }
    }

    override fun onCleared() {
        super.onCleared()
        previewNoteOffJob?.cancel()
        audioEngine.synthNoteOff(PREVIEW_CHANNEL, PREVIEW_PITCH)
    }
}
