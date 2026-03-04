package com.example.nightjar.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.data.repository.IdeaRepository
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

/** Sort order for the idea list in the Library screen. */
enum class SortMode {
    NEWEST,
    OLDEST,
    FAVORITES_FIRST
}

/**
 * ViewModel for the Library screen.
 *
 * Loads all ideas with sorting and tag-based filtering. Refreshes
 * automatically when the sort mode or selected tag changes. Supports
 * multi-track audio preview via [OboeAudioEngine].
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: IdeaRepository,
    private val audioEngine: OboeAudioEngine
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<LibraryEffect>()
    val effects = _effects.asSharedFlow()

    private var pollJob: Job? = null

    init {
        onAction(LibraryAction.Load)
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            LibraryAction.Load -> load()
            LibraryAction.ClearTagFilter -> clearTagFilter()
            is LibraryAction.SelectTag -> selectTag(action.tagNormalized)
            is LibraryAction.SetSortMode -> setSortMode(action.mode)
            is LibraryAction.PlayPreview -> playPreview(action.ideaId)
            LibraryAction.StopPreview -> stopPreview()
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(errorMessage = null) }
            refreshUsedTags()
            refreshDurations()
            refreshIdeas()
        }
    }

    private suspend fun refreshDurations() {
        try {
            val durations = repo.getIdeaDurations()
            _state.update { it.copy(durations = durations) }
        } catch (e: Exception) {
            // Non-critical -- cards render fine without durations.
        }
    }

    private suspend fun refreshUsedTags() {
        try {
            val tags = repo.getAllUsedTags()
            _state.update { it.copy(usedTags = tags) }
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to load tags."
            _state.update { it.copy(errorMessage = msg) }
            _effects.emit(LibraryEffect.ShowError(msg))
        }
    }

    private suspend fun refreshIdeas() {
        val tag = _state.value.selectedTagNormalized
        val sort = _state.value.sortMode

        try {
            val ideas = when {
                tag != null -> repo.getIdeasForTag(tag)
                sort == SortMode.FAVORITES_FIRST -> repo.getIdeasFavoritesFirst()
                sort == SortMode.OLDEST -> repo.getIdeasOldestFirst()
                else -> repo.getIdeasNewest()
            }
            _state.update { it.copy(ideas = ideas) }
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to load ideas."
            _state.update { it.copy(errorMessage = msg) }
            _effects.emit(LibraryEffect.ShowError(msg))
        }
    }

    private fun clearTagFilter() {
        _state.update { it.copy(selectedTagNormalized = null) }
        viewModelScope.launch { refreshIdeas() }
    }

    private fun selectTag(tagNormalized: String) {
        _state.update { it.copy(selectedTagNormalized = tagNormalized) }
        viewModelScope.launch { refreshIdeas() }
    }

    private fun setSortMode(mode: SortMode) {
        _state.update { it.copy(sortMode = mode) }
        viewModelScope.launch { refreshIdeas() }
    }

    private fun playPreview(ideaId: Long) {
        // If tapping the same idea that's already playing, stop it
        if (_state.value.previewingIdeaId == ideaId) {
            stopPreview()
            return
        }

        // Stop any current preview
        stopEngine()

        viewModelScope.launch {
            try {
                val tracks = repo.getAudioTracksForIdea(ideaId)
                if (tracks.isEmpty()) {
                    _state.update { it.copy(previewingIdeaId = null) }
                    return@launch
                }

                // Load all audio tracks into the native engine
                audioEngine.removeAllTracks()
                for (track in tracks) {
                    val fileName = track.audioFileName ?: continue
                    val file = repo.getAudioFile(fileName)
                    if (!file.exists()) continue
                    audioEngine.addTrack(
                        trackId = track.id.toInt(),
                        filePath = file.absolutePath,
                        durationMs = track.durationMs,
                        offsetMs = track.offsetMs,
                        trimStartMs = track.trimStartMs,
                        trimEndMs = track.trimEndMs,
                        volume = track.volume,
                        isMuted = track.isMuted
                    )
                }

                audioEngine.seekTo(0)
                audioEngine.play()
                _state.update { it.copy(previewingIdeaId = ideaId) }
                startPolling()
            } catch (e: Exception) {
                _state.update { it.copy(previewingIdeaId = null) }
            }
        }
    }

    /** Polls the engine to detect when playback finishes. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                audioEngine.pollState()
                if (!audioEngine.isPlaying.value && _state.value.previewingIdeaId != null) {
                    _state.update { it.copy(previewingIdeaId = null) }
                    break
                }
                delay(16)
            }
        }
    }

    private fun stopPreview() {
        stopEngine()
        _state.update { it.copy(previewingIdeaId = null) }
    }

    private fun stopEngine() {
        pollJob?.cancel()
        pollJob = null
        audioEngine.pause()
        audioEngine.removeAllTracks()
    }

    override fun onCleared() {
        super.onCleared()
        stopEngine()
    }
}
