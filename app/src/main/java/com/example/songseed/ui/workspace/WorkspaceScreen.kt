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
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.songseed.player.PlaybackViewModel
import com.example.songseed.share.ShareUtils
import com.example.songseed.ui.workspace.WorkspaceAction
import com.example.songseed.ui.workspace.WorkspaceEffect
import com.example.songseed.ui.workspace.WorkspaceViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun WorkspaceScreen(
    ideaId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val vm: WorkspaceViewModel = hiltViewModel()
    val playbackViewModel: PlaybackViewModel = hiltViewModel()

    LaunchedEffect(ideaId) {
        vm.onAction(WorkspaceAction.Load(ideaId))
    }

    val state by vm.state.collectAsState()
    val idea = state.idea
    val tags = state.tags
    val titleDraft = state.titleDraft
    val notesDraft = state.notesDraft
    val errorMessage = state.errorMessage

    val isPlaying by playbackViewModel.isPlaying.collectAsState()
    val durationMs by playbackViewModel.durationMs.collectAsState()
    val positionMs by playbackViewModel.positionMs.collectAsState()

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf("") }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf(0L) }

    LaunchedEffect(positionMs, isScrubbing) {
        if (!isScrubbing) scrubMs = positionMs
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.effects.collectLatest { effect ->
            when (effect) {
                is WorkspaceEffect.NavigateBack -> onBack()
                is WorkspaceEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { vm.onAction(WorkspaceAction.FlushPendingSaves) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(8.dp))
                Text("Workspace", style = MaterialTheme.typography.headlineSmall)
            }

            Spacer(Modifier.height(12.dp))

            errorMessage?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            val loaded = idea
            if (loaded == null) return@Column

            val audioFile = vm.getAudioFile(loaded.audioFileName)

            OutlinedTextField(
                value = titleDraft,
                onValueChange = {  vm.onAction(WorkspaceAction.TitleChanged(it)) },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.onAction(WorkspaceAction.ToggleFavorite) }) {
                    Text(if (loaded.isFavorite) "Unfavorite" else "Favorite")
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = notesDraft,
                onValueChange = {  vm.onAction(WorkspaceAction.NotesChanged(it)) },
                label = { Text("Notes") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )

            Spacer(Modifier.height(12.dp))

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
                            onClick = { vm.onAction(WorkspaceAction.RemoveTag(tag.id)) },
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
                        vm.onAction(WorkspaceAction.AddTagsFromInput(text))
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    val text = newTagText
                    newTagText = ""
                    vm.onAction(WorkspaceAction.AddTagsFromInput(text))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add Tag")
            }

            Spacer(Modifier.height(16.dp))

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
                            vm.onAction(WorkspaceAction.DeleteIdea)
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
