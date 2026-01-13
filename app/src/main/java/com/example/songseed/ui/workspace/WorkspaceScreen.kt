import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.songseed.data.repository.IdeaRepository
import com.example.songseed.player.PlaybackViewModel
import com.example.songseed.share.ShareUtils
import com.example.songseed.ui.workspace.WorkspaceEvent
import com.example.songseed.ui.workspace.WorkspaceViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun WorkspaceScreen(
    ideaId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // ViewModels
    val vm: WorkspaceViewModel = viewModel()
    val playbackViewModel: PlaybackViewModel = viewModel()

    // Repo only for file path lookup / sharing (no DB writes from UI)
    val fileRepo = remember { IdeaRepository(context) }

    // Load data when ideaId changes
    LaunchedEffect(ideaId) {
        vm.load(ideaId)
    }

    // Collect VM state
    val idea by vm.idea.collectAsState()
    val tags by vm.tags.collectAsState()
    val titleDraft by vm.titleDraft.collectAsState()
    val notesDraft by vm.notesDraft.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState(initial = null)


    // Collect playback state
    val isPlaying by playbackViewModel.isPlaying.collectAsState()
    val durationMs by playbackViewModel.durationMs.collectAsState()
    val positionMs by playbackViewModel.positionMs.collectAsState()

    // UI-only state
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf("") }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf(0L) }

    // Keep scrub bar in sync when not scrubbing
    LaunchedEffect(positionMs, isScrubbing) {
        if (!isScrubbing) scrubMs = positionMs
    }

    // Snackbar for one-off messages
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle one-off VM events
    LaunchedEffect(Unit) {
        vm.events.collectLatest { event ->
            when (event) {
                is WorkspaceEvent.NavigateBack -> onBack()
                is WorkspaceEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // Flush pending saves when leaving screen
    DisposableEffect(Unit) {
        onDispose { vm.flushPendingSaves() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(8.dp))
                Text("Workspace", style = MaterialTheme.typography.headlineSmall)
            }

            Spacer(Modifier.height(12.dp))

            // Top-level error
            errorMessage?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            val loaded = idea
            if (loaded == null) return@Column

            val audioFile = fileRepo.getAudioFile(loaded.audioFileName)

            // Title
            OutlinedTextField(
                value = titleDraft,
                onValueChange = vm::onTitleChange,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            // Favorite
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.toggleFavorite() }) {
                    Text(if (loaded.isFavorite) "Unfavorite" else "Favorite")
                }
            }

            Spacer(Modifier.height(12.dp))

            // Notes
            OutlinedTextField(
                value = notesDraft,
                onValueChange = vm::onNotesChange,
                label = { Text("Notes") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )

            Spacer(Modifier.height(12.dp))

            // Tags
            Text("Tags")
            Spacer(Modifier.height(6.dp))

            if (tags.isEmpty()) {
                Text("No tags yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = tags, key = { it.id }) { tag ->
                        // AssistChip is stable (non-experimental).
                        // We use a small "✕" inside the label to hint removal.
                        AssistChip(
                            onClick = { vm.removeTag(tag.id) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tag.name)
                                    Spacer(Modifier.width(6.dp))
                                    Text("✕")
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Add tag input
            OutlinedTextField(
                value = newTagText,
                onValueChange = { newTagText = it },
                label = { Text("Add tag") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val text = newTagText
                        newTagText = ""
                        vm.addTagsFromInput(text)
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    val text = newTagText
                    newTagText = ""
                    vm.addTagsFromInput(text)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add Tag")
            }

            Spacer(Modifier.height(16.dp))

            // Playback
            Text("Playback")
            Spacer(Modifier.height(8.dp))

            Slider(
                value = scrubMs.toFloat().coerceAtLeast(0f),
                onValueChange = { newValue ->
                    isScrubbing = true
                    scrubMs = newValue.toLong()
                },
                onValueChangeFinished = {
                    isScrubbing = false
                    playbackViewModel.seekTo(scrubMs)
                },
                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { playbackViewModel.playFile(audioFile) }) {
                    Text(if (isPlaying) "Resume" else "Play")
                }
                Button(onClick = { playbackViewModel.pause() }) {
                    Text("Pause")
                }
            }

            Spacer(Modifier.height(10.dp))

            Text("File: ${loaded.audioFileName}", style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    ShareUtils.shareAudioFile(
                        context = context,
                        file = audioFile,
                        title = loaded.title
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Share / Export")
            }

            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delete Idea")
            }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Delete this idea?") },
                    text = { Text("This will permanently delete the idea and its audio file.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirm = false
                            vm.deleteIdea()
                        }) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}
