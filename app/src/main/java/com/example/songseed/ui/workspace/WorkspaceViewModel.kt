package com.example.songseed.ui.workspace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.songseed.data.db.entity.IdeaEntity
import com.example.songseed.data.db.entity.TagEntity
import com.example.songseed.data.repository.IdeaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface WorkspaceEvent {
    data object NavigateBack : WorkspaceEvent
    data class ShowError(val message: String) : WorkspaceEvent
}

class WorkspaceViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = IdeaRepository(app.applicationContext)

    private var currentIdeaId: Long? = null

    private val _idea = MutableStateFlow<IdeaEntity?>(null)
    val idea: StateFlow<IdeaEntity?> = _idea.asStateFlow()

    private val _tags = MutableStateFlow<List<TagEntity>>(emptyList())
    val tags: StateFlow<List<TagEntity>> = _tags.asStateFlow()

    private val _titleDraft = MutableStateFlow("")
    val titleDraft: StateFlow<String> = _titleDraft.asStateFlow()

    private val _notesDraft = MutableStateFlow("")
    val notesDraft: StateFlow<String> = _notesDraft.asStateFlow()

    // ✅ FIX: explicit type avoids Kotlin inferring Nothing?
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _events = MutableSharedFlow<WorkspaceEvent>()
    val events = _events.asSharedFlow()

    private var titleSaveJob: Job? = null
    private var notesSaveJob: Job? = null

    fun load(ideaId: Long) {
        if (currentIdeaId == ideaId) return
        currentIdeaId = ideaId

        viewModelScope.launch {
            try {
                val loaded = repo.getIdeaById(ideaId)
                if (loaded == null) {
                    _idea.value = null
                    _errorMessage.value = "Idea not found."
                    return@launch
                }

                _errorMessage.value = null
                _idea.value = loaded
                _titleDraft.value = loaded.title
                _notesDraft.value = loaded.notes

                refreshTags()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to load idea."
                _events.emit(WorkspaceEvent.ShowError(_errorMessage.value!!))
            }
        }
    }

    private suspend fun refreshTags() {
        val id = currentIdeaId ?: return
        _tags.value = repo.getTagsForIdea(id)
    }

    fun onTitleChange(value: String) {
        _titleDraft.value = value
        scheduleTitleSave()
    }

    fun onNotesChange(value: String) {
        _notesDraft.value = value
        scheduleNotesSave()
    }

    private fun scheduleTitleSave() {
        val idea = _idea.value ?: return
        titleSaveJob?.cancel()

        titleSaveJob = viewModelScope.launch {
            delay(600)
            val finalTitle = _titleDraft.value.trim().ifBlank { idea.title }
            try {
                repo.updateTitle(idea.id, finalTitle)
                _idea.value = idea.copy(title = finalTitle)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to save title."
                _errorMessage.value = msg
                _events.emit(WorkspaceEvent.ShowError(msg))
            }
        }
    }

    private fun scheduleNotesSave() {
        val idea = _idea.value ?: return
        notesSaveJob?.cancel()

        notesSaveJob = viewModelScope.launch {
            delay(600)
            try {
                repo.updateNotes(idea.id, _notesDraft.value)
                _idea.value = idea.copy(notes = _notesDraft.value)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to save notes."
                _errorMessage.value = msg
                _events.emit(WorkspaceEvent.ShowError(msg))
            }
        }
    }

    fun toggleFavorite() {
        val idea = _idea.value ?: return
        val newValue = !idea.isFavorite

        viewModelScope.launch {
            try {
                repo.updateFavorite(idea.id, newValue)
                _idea.value = idea.copy(isFavorite = newValue)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to update favorite."
                _errorMessage.value = msg
                _events.emit(WorkspaceEvent.ShowError(msg))
            }
        }
    }

    fun addTagsFromInput(raw: String) {
        val id = currentIdeaId ?: return
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return

        viewModelScope.launch {
            try {
                parts.forEach { repo.addTagToIdea(id, it) }
                refreshTags()
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to add tag(s)."
                _errorMessage.value = msg
                _events.emit(WorkspaceEvent.ShowError(msg))
            }
        }
    }

    fun removeTag(tagId: Long) {
        val id = currentIdeaId ?: return

        viewModelScope.launch {
            try {
                repo.removeTagFromIdea(id, tagId)
                refreshTags()
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to remove tag."
                _errorMessage.value = msg
                _events.emit(WorkspaceEvent.ShowError(msg))
            }
        }
    }

    fun deleteIdea() {
        val idea = _idea.value ?: return

        viewModelScope.launch {
            try {
                repo.deleteIdeaAndAudio(idea.id)
                _events.emit(WorkspaceEvent.NavigateBack)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to delete idea."
                _errorMessage.value = msg
                _events.emit(WorkspaceEvent.ShowError(msg))
            }
        }
    }

    fun flushPendingSaves() {
        titleSaveJob?.cancel()
        notesSaveJob?.cancel()

        val idea = _idea.value ?: return

        viewModelScope.launch {
            try {
                repo.updateTitle(idea.id, _titleDraft.value)
                repo.updateNotes(idea.id, _notesDraft.value)
            } catch (_: Exception) {
                // ignore on dispose
            }
        }
    }
}
