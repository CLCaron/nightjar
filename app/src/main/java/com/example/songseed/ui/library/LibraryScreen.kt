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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.songseed.data.db.entity.IdeaEntity
import com.example.songseed.ui.library.LibraryViewModel
import com.example.songseed.ui.library.SortMode
import java.text.DateFormat
import java.util.Date

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
    val vm: LibraryViewModel = viewModel()

    val usedTags by vm.usedTags.collectAsState()
    val ideas by vm.ideas.collectAsState()
    val selectedTagNormalized by vm.selectedTagNormalized.collectAsState()
    val sortMode by vm.sortMode.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(8.dp))
            Text("Library", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(Modifier.height(12.dp))

        // Tag filter chips
        if (usedTags.isNotEmpty()) {
            Text("Filter by tag")
            Spacer(Modifier.height(6.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    AssistChip(
                        onClick = { vm.clearTagFilter() },
                        label = { Text("All") }
                    )
                }

                items(usedTags, key = { it.id }) { tag ->
                    AssistChip(
                        onClick = { vm.selectTag(tag.nameNormalized) },
                        label = { Text(tag.name) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
        }

        // Sort dropdown
        var sortMenuOpen by remember { mutableStateOf(false) }

        Box {
            OutlinedButton(onClick = { sortMenuOpen = true }) {
                Text(
                    when (sortMode) {
                        SortMode.NEWEST -> "Sort: Newest"
                        SortMode.OLDEST -> "Sort: Oldest"
                        SortMode.FAVORITES_FIRST -> "Sort: Favorites first"
                    }
                )
            }

            DropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { sortMenuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Newest") },
                    onClick = { vm.setSortMode(SortMode.NEWEST); sortMenuOpen = false }
                )
                DropdownMenuItem(
                    text = { Text("Oldest") },
                    onClick = { vm.setSortMode(SortMode.OLDEST); sortMenuOpen = false }
                )
                DropdownMenuItem(
                    text = { Text("Favorites first") },
                    onClick = { vm.setSortMode(SortMode.FAVORITES_FIRST); sortMenuOpen = false }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (ideas.isEmpty()) {
            Text(
                if (selectedTagNormalized != null) "No ideas match this tag."
                else "No ideas yet. Go record something."
            )
        } else {
            LazyColumn {
                items(ideas, key = { it.id }) { idea ->
                    IdeaRow(idea = idea, onClick = { onOpenWorkspace(idea.id) })
                }
            }
        }
    }
}