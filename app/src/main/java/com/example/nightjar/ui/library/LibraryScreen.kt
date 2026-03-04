package com.example.nightjar.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.ui.components.NjCard
import com.example.nightjar.ui.components.NjLedDot
import com.example.nightjar.ui.components.NjButton
import com.example.nightjar.ui.components.NjTopBar
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjStudioAccent
import com.example.nightjar.ui.theme.NjStudioGreen
import kotlinx.coroutines.flow.collectLatest

/** A single idea card in the library list with hardware-style press feel. */
@Composable
private fun IdeaRow(
    idea: IdeaEntity,
    durationMs: Long?,
    isPreviewing: Boolean,
    onClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    NjCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        // LED dot for favorites
        if (idea.isFavorite) {
            NjLedDot(isLit = true, litColor = NjAccent)
            Spacer(Modifier.width(10.dp))
        }

        // Title + metadata
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = idea.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.MEDIUM,
                        java.text.DateFormat.SHORT
                    ).format(java.util.Date(idea.createdAtEpochMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                if (durationMs != null && durationMs > 0L) {
                    Text(
                        formatDuration(durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            }
        }

        // Play preview button
        if (durationMs != null && durationMs > 0L) {
            Spacer(Modifier.width(8.dp))
            NjButton(
                text = "",
                icon = Icons.Default.PlayArrow,
                isActive = isPreviewing,
                ledColor = NjStudioGreen,
                onClick = onPlayClick
            )
        }
    }
}

/** Formats milliseconds as `m:ss` (e.g. 72300 -> "1:12"). */
private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms + 500) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Library screen -- browse saved ideas with sorting and tag-based filtering.
 *
 * Displays a horizontal chip bar (NjButton toggles) for filtering
 * and sorting, and a scrollable list of hardware-style idea cards (NjCard).
 * Each card has a play button for lightweight audio preview via MediaPlayer.
 */
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenOverview: (Long) -> Unit
) {
    val vm: LibraryViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
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

    // Stop preview when navigating away
    DisposableEffect(Unit) {
        onDispose { vm.onAction(LibraryAction.StopPreview) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NjTopBar(
                title = "Library",
                onBack = onBack
            )

            state.errorMessage?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error)
            }

            if (state.usedTags.isNotEmpty()) {
                Text(
                    text = "Filter",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        NjButton(
                            text = "All",
                            isActive = state.selectedTagNormalized == null,
                            ledColor = NjStudioAccent,
                            onClick = { vm.onAction(LibraryAction.ClearTagFilter) }
                        )
                    }

                    items(state.usedTags, key = { it.id }) { tag ->
                        NjButton(
                            text = tag.name,
                            isActive = state.selectedTagNormalized == tag.nameNormalized,
                            ledColor = NjStudioAccent,
                            onClick = { vm.onAction(LibraryAction.SelectTag(tag.nameNormalized)) }
                        )
                    }
                }
            }

            Text(
                text = "Sort",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    NjButton(
                        text = "Newest",
                        isActive = state.sortMode == SortMode.NEWEST,
                        ledColor = NjStudioAccent,
                        onClick = { vm.onAction(LibraryAction.SetSortMode(SortMode.NEWEST)) }
                    )
                }
                item {
                    NjButton(
                        text = "Oldest",
                        isActive = state.sortMode == SortMode.OLDEST,
                        ledColor = NjStudioAccent,
                        onClick = { vm.onAction(LibraryAction.SetSortMode(SortMode.OLDEST)) }
                    )
                }
                item {
                    NjButton(
                        text = "Favs",
                        isActive = state.sortMode == SortMode.FAVORITES_FIRST,
                        ledColor = NjStudioAccent,
                        onClick = { vm.onAction(LibraryAction.SetSortMode(SortMode.FAVORITES_FIRST)) }
                    )
                }
            }

            Spacer(Modifier.padding(top = 6.dp))

            if (state.ideas.isEmpty()) {
                Text(
                    text = if (state.selectedTagNormalized != null) {
                        "No ideas match this filter."
                    } else {
                        "No ideas yet. Record something."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            } else {
                LazyColumn {
                    items(state.ideas, key = { it.id }) { idea ->
                        IdeaRow(
                            idea = idea,
                            durationMs = state.durations[idea.id],
                            isPreviewing = state.previewingIdeaId == idea.id,
                            onClick = { onOpenOverview(idea.id) },
                            onPlayClick = { vm.onAction(LibraryAction.PlayPreview(idea.id)) }
                        )
                    }
                }
            }
        }
    }
}
