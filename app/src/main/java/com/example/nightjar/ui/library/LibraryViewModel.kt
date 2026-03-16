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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
 * Observes ideas reactively via Room Flows so the list auto-updates
 * when ideas are created, edited, or deleted from any screen.
 * Supports sorting, tag-based filtering, and multi-track audio
 * preview via [OboeAudioEngine].
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

    // Separate flows for the query parameters so flatMapLatest
    // can switch the observed Room query when they change.
    private val _sortMode = MutableStateFlow(SortMode.NEWEST)
    private val _selectedTag = MutableStateFlow<String?>(null)

    init {
        // Reactive ideas observation -- auto-updates on any DB change
        viewModelScope.launch {
            @Suppress("OPT_IN_USAGE")
            combine(_sortMode, _selectedTag) { sort, tag -> sort to tag }
                .flatMapLatest { (sort, tag) ->
                    when {
                        tag != null -> repo.observeIdeasForTag(tag)
                        sort == SortMode.FAVORITES_FIRST -> repo.observeIdeasFavoritesFirst()
                        sort == SortMode.OLDEST -> repo.observeIdeasOldestFirst()
                        else -> repo.observeIdeasNewest()
                    }
                }
                .collect { ideas ->
                    _state.update { it.copy(ideas = ideas, isLoading = false) }
                }
        }

        // One-shot loads for tags and durations
        viewModelScope.launch {
            refreshUsedTags()
            refreshDurations()
        }
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

    private fun clearTagFilter() {
        _selectedTag.value = null
        _state.update { it.copy(selectedTagNormalized = null) }
    }

    private fun selectTag(tagNormalized: String) {
        _selectedTag.value = tagNormalized
        _state.update { it.copy(selectedTagNormalized = tagNormalized) }
    }

    private fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        _state.update { it.copy(sortMode = mode) }
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
