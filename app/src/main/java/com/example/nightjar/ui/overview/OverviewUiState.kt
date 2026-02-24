package com.example.nightjar.ui.overview

import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TagEntity

/** UI state for the Overview screen. */
data class OverviewUiState(
    val idea: IdeaEntity? = null,
    val tags: List<TagEntity> = emptyList(),
    val titleDraft: String = "",
    val notesDraft: String = "",
    val errorMessage: String? = null,
    val compositeWaveform: FloatArray? = null,
    val hasTracks: Boolean = false
) {
    // FloatArray doesn't have structural equals, so override for StateFlow diffing.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OverviewUiState) return false
        return idea == other.idea &&
            tags == other.tags &&
            titleDraft == other.titleDraft &&
            notesDraft == other.notesDraft &&
            errorMessage == other.errorMessage &&
            compositeWaveform.contentEquals(other.compositeWaveform) &&
            hasTracks == other.hasTracks
    }

    override fun hashCode(): Int {
        var result = idea?.hashCode() ?: 0
        result = 31 * result + tags.hashCode()
        result = 31 * result + titleDraft.hashCode()
        result = 31 * result + notesDraft.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + (compositeWaveform?.contentHashCode() ?: 0)
        result = 31 * result + hasTracks.hashCode()
        return result
    }
}

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
    data object Play : OverviewAction
    data object Pause : OverviewAction
    data class SeekTo(val positionMs: Long) : OverviewAction
    data object RefreshTracks : OverviewAction
}

/** One-shot side effects emitted by [OverviewViewModel]. */
sealed interface OverviewEffect {
    data object NavigateBack : OverviewEffect
    data class ShowError(val message: String) : OverviewEffect
}
