package com.example.nightjar.ui.workspace

import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TagEntity

/** UI state for the Workspace screen. */
data class WorkspaceUiState(
    val idea: IdeaEntity? = null,
    val tags: List<TagEntity> = emptyList(),
    val titleDraft: String = "",
    val notesDraft: String = "",
    val errorMessage: String? = null
)

/** User-initiated actions on the Workspace screen. */
sealed interface WorkspaceAction {
    data class Load(val ideaId: Long) : WorkspaceAction
    data class TitleChanged(val value: String) : WorkspaceAction
    data class NotesChanged(val value: String) : WorkspaceAction
    data object ToggleFavorite : WorkspaceAction
    data class AddTagsFromInput(val raw: String) : WorkspaceAction
    data class RemoveTag(val tagId: Long) : WorkspaceAction
    data object DeleteIdea : WorkspaceAction
    data object FlushPendingSaves : WorkspaceAction
}

/** One-shot side effects emitted by [WorkspaceViewModel]. */
sealed interface WorkspaceEffect {
    data object NavigateBack : WorkspaceEffect
    data class ShowError(val message: String) : WorkspaceEffect
}