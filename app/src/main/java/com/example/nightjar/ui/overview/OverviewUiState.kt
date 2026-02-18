package com.example.nightjar.ui.overview

import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TagEntity

/** UI state for the Overview screen. */
data class OverviewUiState(
    val idea: IdeaEntity? = null,
    val tags: List<TagEntity> = emptyList(),
    val titleDraft: String = "",
    val notesDraft: String = "",
    val errorMessage: String? = null
)

/** User-initiated actions on the Overview screen. */
sealed interface OverviewAction {
    data class Load(val ideaId: Long) : OverviewAction
    data class TitleChanged(val value: String) : OverviewAction
    data class NotesChanged(val value: String) : OverviewAction
    data object ToggleFavorite : OverviewAction
    data class AddTagsFromInput(val raw: String) : OverviewAction
    data class RemoveTag(val tagId: Long) : OverviewAction
    data object DeleteIdea : OverviewAction
    data object FlushPendingSaves : OverviewAction
}

/** One-shot side effects emitted by [OverviewViewModel]. */
sealed interface OverviewEffect {
    data object NavigateBack : OverviewEffect
    data class ShowError(val message: String) : OverviewEffect
}
