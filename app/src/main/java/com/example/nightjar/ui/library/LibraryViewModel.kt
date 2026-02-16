package com.example.nightjar.ui.library

import LibraryAction
import LibraryEffect
import LibraryUiState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.data.repository.IdeaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortMode {
    NEWEST,
    OLDEST,
    FAVORITES_FIRST
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: IdeaRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<LibraryEffect>()
    val effects = _effects.asSharedFlow()

    init {
        onAction(LibraryAction.Load)
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            LibraryAction.Load -> load()
            LibraryAction.ClearTagFilter -> clearTagFilter()
            is LibraryAction.SelectTag -> selectTag(action.tagNormalized)
            is LibraryAction.SetSortMode -> setSortMode(action.mode)
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(errorMessage = null) }
            refreshUsedTags()
            refreshIdeas()
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
}
