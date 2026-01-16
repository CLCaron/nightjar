package com.example.songseed.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.songseed.data.db.entity.IdeaEntity
import com.example.songseed.data.db.entity.TagEntity
import com.example.songseed.data.repository.IdeaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _selectedTagNormalized = MutableStateFlow<String?>(null)
    val selectedTagNormalized: StateFlow<String?> = _selectedTagNormalized.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.NEWEST)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _usedTags = MutableStateFlow<List<TagEntity>>(emptyList())
    val usedTags: StateFlow<List<TagEntity>> = _usedTags.asStateFlow()

    private val _ideas = MutableStateFlow<List<IdeaEntity>>(emptyList())
    val ideas: StateFlow<List<IdeaEntity>> = _ideas.asStateFlow()

    init {
        viewModelScope.launch {
            refreshUsedTags()
            refreshIdeas()
        }
    }

    private suspend fun refreshUsedTags() {
        _usedTags.value = repo.getAllUsedTags()
    }

    private suspend fun refreshIdeas() {
        val tag = _selectedTagNormalized.value
        val sort = _sortMode.value

        _ideas.value = when {
            tag != null -> repo.getIdeasForTag(tag)
            sort == SortMode.FAVORITES_FIRST -> repo.getIdeasFavoritesFirst()
            sort == SortMode.OLDEST -> repo.getIdeasOldestFirst()
            else -> repo.getIdeasNewest()
        }
    }

    fun clearTagFilter() {
        _selectedTagNormalized.value = null
        viewModelScope.launch { refreshIdeas() }
    }

    fun selectTag(tagNormalized: String) {
        _selectedTagNormalized.value = tagNormalized
        viewModelScope.launch { refreshIdeas() }
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        viewModelScope.launch { refreshIdeas() }
    }
}
