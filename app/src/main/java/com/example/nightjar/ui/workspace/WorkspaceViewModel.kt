package com.example.nightjar.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightjar.data.repository.IdeaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * ViewModel for the Workspace screen.
 *
 * Manages editing of a single idea's metadata (title, notes, tags, favorite).
 * Title and notes changes are debounced (600 ms) to avoid excessive writes.
 * Pending saves are flushed when the screen is disposed.
 */
@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repo: IdeaRepository
) : ViewModel() {

    private var currentIdeaId: Long? = null
    private val _state = MutableStateFlow(WorkspaceUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<WorkspaceEffect>()
    val effects = _effects.asSharedFlow()

    private var titleSaveJob: Job? = null
    private var notesSaveJob: Job? = null

    fun onAction(action: WorkspaceAction) {
        when (action) {
            is WorkspaceAction.Load -> load(action.ideaId)
            is WorkspaceAction.TitleChanged -> onTitleChange(action.value)
            is WorkspaceAction.NotesChanged -> onNotesChange(action.value)
            WorkspaceAction.ToggleFavorite -> toggleFavorite()
            is WorkspaceAction.AddTagsFromInput -> addTagsFromInput(action.raw)
            is WorkspaceAction.RemoveTag -> removeTag(action.tagId)
            WorkspaceAction.DeleteIdea -> deleteIdea()
            WorkspaceAction.FlushPendingSaves -> flushPendingSaves()
        }
    }


    private fun load(ideaId: Long) {
        if (currentIdeaId == ideaId) return
        currentIdeaId = ideaId

        viewModelScope.launch {
            try {
                val loaded = repo.getIdeaById(ideaId)
                if (loaded == null) {
                    _state.update { it.copy(idea = null, errorMessage = "Idea not found.") }
                    return@launch
                }

                _state.update {
                    it.copy(
                        idea = loaded,
                        titleDraft = loaded.title,
                        notesDraft = loaded.notes,
                        errorMessage = null
                    )
                }

                refreshTags()
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to load idea."
                _state.update { it.copy(errorMessage = msg) }
                _effects.emit(WorkspaceEffect.ShowError(msg))
            }
        }
    }

    private suspend fun refreshTags() {
        val id = currentIdeaId ?: return
        _state.update { it.copy(tags = repo.getTagsForIdea(id)) }
    }

    private fun onTitleChange(value: String) {
        _state.update { it.copy(titleDraft = value) }
        scheduleTitleSave()
    }

    private fun onNotesChange(value: String) {
        _state.update { it.copy(notesDraft = value)}
        scheduleNotesSave()
    }

    private fun scheduleTitleSave() {
        val idea = _state.value.idea ?: return
        titleSaveJob?.cancel()

        titleSaveJob = viewModelScope.launch {
            delay(600)
            val finalTitle = _state.value.titleDraft.trim().ifBlank { idea.title }
            try {
                repo.updateTitle(idea.id, finalTitle)
                _state.update { it.copy(idea = idea.copy(title = finalTitle)) }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to save title."
                _state.update { it.copy(errorMessage = msg) }
                _effects.emit(WorkspaceEffect.ShowError(msg))
            }
        }
    }

    private fun scheduleNotesSave() {
        val idea = _state.value.idea ?: return
        notesSaveJob?.cancel()

        notesSaveJob = viewModelScope.launch {
            delay(600)
            try {
                val notes = _state.value.notesDraft
                repo.updateNotes(idea.id, notes)
                _state.update { it.copy(idea = idea.copy(notes = notes)) }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to save notes."
                _state.update { it.copy(errorMessage = msg) }
                _effects.emit(WorkspaceEffect.ShowError(msg))
            }
        }
    }

    private fun toggleFavorite() {
        val idea = _state.value.idea ?: return
        val newValue = !idea.isFavorite

        viewModelScope.launch {
            try {
                repo.updateFavorite(idea.id, newValue)
                _state.update { it.copy(idea = idea.copy(isFavorite = newValue)) }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to update favorite."
                _state.update { it.copy(errorMessage = msg) }
                _effects.emit(WorkspaceEffect.ShowError(msg))
            }
        }
    }

    private fun addTagsFromInput(raw: String) {
        val id = currentIdeaId ?: return
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return

        viewModelScope.launch {
            try {
                parts.forEach { repo.addTagToIdea(id, it) }
                refreshTags()
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to add tag(s)."
                _state.update { it.copy(errorMessage = msg) }
                _effects.emit(WorkspaceEffect.ShowError(msg))
            }
        }
    }

    private fun removeTag(tagId: Long) {
        val id = currentIdeaId ?: return

        viewModelScope.launch {
            try {
                repo.removeTagFromIdea(id, tagId)
                refreshTags()
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to remove tag."
                _state.update { it.copy(errorMessage = msg) }
                _effects.emit(WorkspaceEffect.ShowError(msg))
            }
        }
    }

    private fun deleteIdea() {
        val idea = _state.value.idea ?: return

        viewModelScope.launch {
            try {
                repo.deleteIdeaAndAudio(idea.id)
                _effects.emit(WorkspaceEffect.NavigateBack)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to delete idea."
                _state.update { it.copy(errorMessage = msg) }
                _effects.emit(WorkspaceEffect.ShowError(msg))
            }
        }
    }

    private fun flushPendingSaves() {
        titleSaveJob?.cancel()
        notesSaveJob?.cancel()

        val idea = _state.value.idea ?: return

        viewModelScope.launch {
            try {
                repo.updateTitle(idea.id, _state.value.titleDraft)
                repo.updateNotes(idea.id, _state.value.notesDraft)
            } catch (_: Exception) {
                // ignore on dispose
            }
        }
    }

    fun getAudioFile(name: String): File = repo.getAudioFile(name)
}
