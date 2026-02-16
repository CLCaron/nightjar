import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.data.db.entity.TagEntity
import com.example.nightjar.ui.library.SortMode

data class LibraryUiState(
    val usedTags: List<TagEntity> = emptyList(),
    val ideas: List<IdeaEntity> = emptyList(),
    val selectedTagNormalized: String? = null,
    val sortMode: SortMode = SortMode.NEWEST,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

sealed interface LibraryAction {
    data object Load : LibraryAction
    data object ClearTagFilter : LibraryAction
    data class SelectTag(val tagNormalized: String) : LibraryAction
    data class SetSortMode(val mode: SortMode) : LibraryAction
}

sealed interface LibraryEffect {
    data class ShowError(val message: String) : LibraryEffect
}