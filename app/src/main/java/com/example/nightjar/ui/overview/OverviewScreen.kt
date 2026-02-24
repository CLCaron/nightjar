package com.example.nightjar.ui.overview

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
import androidx.compose.material3.ButtonDefaults
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.nightjar.share.ShareUtils
import com.example.nightjar.ui.components.NjDestructiveButton
import com.example.nightjar.ui.components.NjInlineAction
import com.example.nightjar.ui.components.NjStarburst
import com.example.nightjar.ui.components.NjPrimaryButton
import com.example.nightjar.ui.components.NjSecondaryButton
import com.example.nightjar.ui.components.NjSectionTitle
import com.example.nightjar.ui.components.NjTagChip
import com.example.nightjar.ui.components.NjTextField
import com.example.nightjar.ui.components.NjTopBar
import com.example.nightjar.ui.components.NjWaveform
import kotlinx.coroutines.flow.collectLatest

/**
 * Overview screen — view and edit a single idea.
 *
 * Provides playback controls with waveform visualization, editable title and
 * notes fields, tag management, favorite toggle, sharing, and deletion. Title
 * and notes are auto-saved with a 600 ms debounce. The "Studio" button opens
 * the multi-track studio for this idea.
 */
@Composable
fun OverviewScreen(
    ideaId: Long,
    onBack: () -> Unit,
    onOpenStudio: (Long) -> Unit = {}
) {
    val context = LocalContext.current

    val vm: OverviewViewModel = hiltViewModel()

    LaunchedEffect(ideaId) {
        vm.onAction(OverviewAction.Load(ideaId))
    }

    val state by vm.state.collectAsState()
    val loaded = state.idea
    val tags = state.tags
    val titleDraft = state.titleDraft
    val notesDraft = state.notesDraft
    val errorMessage = state.errorMessage
    val compositeWaveform = state.compositeWaveform
    val hasTracks = state.hasTracks

    val isPlaying by vm.isPlaying.collectAsState()
    val durationMs by vm.totalDurationMs.collectAsState()
    val positionMs by vm.globalPositionMs.collectAsState()

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf("") }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableStateOf(0f) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.effects.collectLatest { effect ->
            when (effect) {
                is OverviewEffect.NavigateBack -> onBack()
                is OverviewEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { vm.onAction(OverviewAction.FlushPendingSaves) }
    }

    // Pause playback when leaving the screen, refresh tracks when returning.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onAction(OverviewAction.Pause)
                Lifecycle.Event.ON_RESUME -> vm.onAction(OverviewAction.RefreshTracks)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // First track file is only needed for the share button.
    var firstTrackFile by remember { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(loaded?.id) {
        firstTrackFile = loaded?.let { vm.getFirstTrackFile(it.id) }
    }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val displayFraction = if (isScrubbing) {
        scrubFraction
    } else {
        positionMs.toFloat() / safeDuration.toFloat()
    }
    val displayMs = if (isScrubbing) {
        (scrubFraction * safeDuration).toLong()
    } else {
        positionMs
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (loaded != null) {
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
                        firstTrackFile?.let { file ->
                            NjSecondaryButton(
                                text = "Share",
                                onClick = {
                                    ShareUtils.shareAudioFile(
                                        context = context,
                                        file = file,
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
                title = "Overview",
                onBack = onBack
            )

            errorMessage?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error)
            }

            if (loaded == null) {
                Text(
                    "Loading\u2026",
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
                    text = if (loaded.isFavorite) "Favorited" else "Favorite",
                    onClick = { vm.onAction(OverviewAction.ToggleFavorite) },
                    emphasized = loaded.isFavorite,
                    leadingIcon = { NjStarburst(filled = loaded.isFavorite, size = 16.dp) }
                )
                NjInlineAction(
                    text = "Studio",
                    onClick = { onOpenStudio(ideaId) }
                )
            }

            NjTextField(
                value = titleDraft,
                onValueChange = { vm.onAction(OverviewAction.TitleChanged(it)) },
                label = "Title",
                placeholder = "Untitled idea",
                singleLine = true
            )

            NjSectionTitle("Playback")

            if (compositeWaveform != null && compositeWaveform.isNotEmpty()) {
                NjWaveform(
                    amplitudes = compositeWaveform,
                    modifier = Modifier.fillMaxWidth(),
                    height = 64.dp,
                    progressFraction = displayFraction,
                    onScrub = { fraction ->
                        isScrubbing = true
                        scrubFraction = fraction
                    },
                    onScrubFinished = { fraction ->
                        isScrubbing = false
                        val seekMs = (fraction * safeDuration).toLong()
                        vm.onAction(OverviewAction.SeekTo(seekMs))
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatMs(displayMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                    Text(
                        formatMs(durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            } else if (!hasTracks) {
                Text(
                    "No audio tracks yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val quietColors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
                NjPrimaryButton(
                    text = if (isPlaying) "Pause" else "Play",
                    onClick = {
                        if (isPlaying) {
                            vm.onAction(OverviewAction.Pause)
                        } else {
                            vm.onAction(OverviewAction.Play)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    fullWidth = true,
                    colors = quietColors
                )
            }

            NjSectionTitle("Notes")
            NjTextField(
                value = notesDraft,
                onValueChange = { vm.onAction(OverviewAction.NotesChanged(it)) },
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
                            onClick = { vm.onAction(OverviewAction.RemoveTag(tag.id)) }
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
                        vm.onAction(OverviewAction.AddTagsFromInput(text))
                    }
                )
            )

            NjPrimaryButton(
                text = "Add Tag",
                onClick = {
                    val text = newTagText
                    newTagText = ""
                    vm.onAction(OverviewAction.AddTagsFromInput(text))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
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
                            vm.onAction(OverviewAction.DeleteIdea)
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

private fun formatMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
