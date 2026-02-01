import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.songseed.data.db.entity.IdeaEntity
import com.example.songseed.ui.library.LibraryViewModel
import com.example.songseed.ui.library.SortMode
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.collectLatest

@Composable
private fun IdeaRow(idea: IdeaEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = idea.title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                if (idea.isFavorite) Text("★", style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Created: ${DateFormat.getDateTimeInstance().format(Date(idea.createdAtEpochMs))}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenWorkspace: (Long) -> Unit
) {
    val vm: LibraryViewModel = hiltViewModel()

    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.effects.collectLatest { effect ->
            when (effect) {
                is LibraryEffect.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = effect.message,
                        withDismissAction = true
                    )
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(8.dp))
                Text("Library", style = MaterialTheme.typography.headlineSmall)
            }

            Spacer(Modifier.height(12.dp))

            state.errorMessage?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            if (state.usedTags.isNotEmpty()) {
                Text("Filter by tag")
                Spacer(Modifier.height(6.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        AssistChip(
                            onClick = { vm.onAction(LibraryAction.ClearTagFilter) },
                            label = { Text("All") }
                        )
                    }

                    items(state.usedTags, key = { it.id }) { tag ->
                        AssistChip(
                            onClick = { vm.onAction(LibraryAction.SelectTag(tag.nameNormalized)) },
                            label = { Text(tag.name) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
            }

            var sortMenuOpen by remember { mutableStateOf(false) }

            Box {
                OutlinedButton(onClick = { sortMenuOpen = true}) {
                    Text(
                        when (state.sortMode) {
                            SortMode.NEWEST -> "Sort: Newest"
                            SortMode.OLDEST -> "Sort: Oldest"
                            SortMode.FAVORITES_FIRST -> "Sort: Favorites First"
                        }
                    )
                }

                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Newest") },
                        onClick = {
                            vm.onAction(LibraryAction.SetSortMode(SortMode.NEWEST))
                            sortMenuOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Oldest") },
                        onClick = {
                            vm.onAction(LibraryAction.SetSortMode(SortMode.OLDEST))
                            sortMenuOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Favorites First") },
                        onClick = {
                            vm.onAction(LibraryAction.SetSortMode(SortMode.FAVORITES_FIRST))
                            sortMenuOpen = false
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (state.ideas.isEmpty()) {
                Text(
                    if (state.selectedTagNormalized != null) "No ideas match this tag."
                    else "No ideas yet. Record something!"
                )
            } else {
                LazyColumn {
                    items(state.ideas, key = { it.id }) { idea ->
                        IdeaRow(idea = idea, onClick = { onOpenWorkspace(idea.id) })
                    }
                }
            }
        }
    }
}