package com.example.nightjar.ui.library

import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TagEntity

/** UI state for the Library screen. */
data class LibraryUiState(
    val usedTags: List<TagEntity> = emptyList(),
    val ideas: List<IdeaEntity> = emptyList(),
    val durations: Map<Long, Long> = emptyMap(),
    val selectedTagNormalized: String? = null,
    val sortMode: SortMode = SortMode.NEWEST,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

/** User-initiated actions on the Library screen. */
sealed interface LibraryAction {
    data object Load : LibraryAction
    data object ClearTagFilter : LibraryAction
    data class SelectTag(val tagNormalized: String) : LibraryAction
    data class SetSortMode(val mode: SortMode) : LibraryAction
}

/** One-shot side effects emitted by [LibraryViewModel]. */
sealed interface LibraryEffect {
    data class ShowError(val message: String) : LibraryEffect
}
