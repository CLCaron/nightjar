import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.songseed.player.PlaybackViewModel
import com.example.songseed.share.ShareUtils
import com.example.songseed.ui.components.NjInlineAction
import com.example.songseed.ui.components.NjScrubber
import com.example.songseed.ui.components.NjTagChip
import com.example.songseed.ui.components.NjTextField
import com.example.songseed.ui.components.NjTopBar
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
    val loaded = state.idea
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

    LaunchedEffect(positionMs, isScrubbing, durationMs) {
        if (!isScrubbing) scrubMs = positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L))
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

    val audioFile = loaded?.let { vm.getAudioFile(it.audioFileName) }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (loaded != null && audioFile != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        NjDestructiveButton(
                            text = "Delete",
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.weight(1f),
                            fullWidth = false
                        )
                        NjSecondaryButton(
                            text = "Share",
                            onClick = {
                                ShareUtils.shareAudioFile(
                                    context = context,
                                    file = audioFile,
                                    title = loaded.title
                                )
                            },
                            modifier = Modifier.weight(1f),
                            fullWidth = false
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NjTopBar(
                title = "Workspace",
                onBack = onBack
            )

            errorMessage?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error)
            }

            if (loaded == null || audioFile == null) {
                Text(
                    "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NjInlineAction(
                    text = if (loaded.isFavorite) "★ Favorited" else "☆ Favorite",
                    onClick = { vm.onAction(WorkspaceAction.ToggleFavorite) },
                    emphasized = loaded.isFavorite
                )
            }

            NjTextField(
                value = titleDraft,
                onValueChange = { vm.onAction(WorkspaceAction.TitleChanged(it)) },
                label = "Title",
                placeholder = "Untitled idea",
                singleLine = true
            )

            NjSectionTitle("Playback")

            NjScrubber(
                positionMs = scrubMs,
                durationMs = durationMs,
                onScrub = { newMs ->
                    isScrubbing = true
                    scrubMs = newMs
                },
                onScrubFinished = { finalMs ->
                    isScrubbing = false
                    playbackViewModel.seekTo(finalMs)
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NjPrimaryButton(
                    text = if (isPlaying) "Resume" else "Play",
                    onClick = { playbackViewModel.playFile(audioFile) },
                    modifier = Modifier.weight(1f),
                    fullWidth = false
                )
                NjPrimaryButton(
                    text = "Pause",
                    onClick = { playbackViewModel.pause() },
                    modifier = Modifier.weight(1f),
                    fullWidth = false
                )
            }

            Text(
                "File: ${loaded.audioFileName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )

            NjSectionTitle("Notes")
            NjTextField(
                value = notesDraft,
                onValueChange = { vm.onAction(WorkspaceAction.NotesChanged(it)) },
                label = "Notes",
                placeholder = "Lyrics? Chords? Vibe?",
                minLines = 6,
                maxLines = 12
            )

            NjSectionTitle("Tags")
            if (tags.isEmpty()) {
                Text(
                    "No tags yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = tags, key = { it.id }) { tag ->
                        NjTagChip(
                            text = tag.name,
                            onClick = { vm.onAction(WorkspaceAction.RemoveTag(tag.id)) }
                        )
                    }
                }
            }

            NjTextField(
                value = newTagText,
                onValueChange = { newTagText = it },
                label = "Add tag",
                placeholder = "e.g. chorus, sad, 120bpm",
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val text = newTagText
                        newTagText = ""
                        vm.onAction(WorkspaceAction.AddTagsFromInput(text))
                    }
                )
            )

            NjPrimaryButton(
                text = "Add Tag",
                onClick = {
                    val text = newTagText
                    newTagText = ""
                    vm.onAction(WorkspaceAction.AddTagsFromInput(text))
                }
            )

            Spacer(Modifier.height(96.dp))

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Delete this idea?") },
                    text = { Text("This will permanently delete the idea and its audio file.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirm = false
                            vm.onAction(WorkspaceAction.DeleteIdea)
                        }) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}
