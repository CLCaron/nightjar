package com.example.nightjar.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.nightjar.data.db.entity.IdeaEntity
import com.example.nightjar.ui.components.NjSelectableChip
import com.example.nightjar.ui.components.NjTopBar
import com.example.nightjar.ui.library.LibraryViewModel
import com.example.nightjar.ui.library.SortMode
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.collectLatest

/** A single idea card in the library list — shows title, favorite star, duration, and creation date. */
@Composable
private fun IdeaRow(
    idea: IdeaEntity,
    durationMs: Long?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = idea.title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
                )
                if (idea.isFavorite) {
                    Text("★", style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.padding(top = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(idea.createdAtEpochMs)),
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
    }
}

/** Formats milliseconds as `m:ss` (e.g. 72300 → "1:12"). */
private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms + 500) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Library screen — browse saved ideas with sorting and tag-based filtering.
 *
 * Displays a horizontal tag chip bar for filtering, sort mode selectors,
 * and a scrollable list of idea cards. Tapping a card navigates to the
 * Overview.
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
                        NjSelectableChip(
                            text = "All",
                            selected = state.selectedTagNormalized == null,
                            onClick = { vm.onAction(LibraryAction.ClearTagFilter) }
                        )
                    }

                    items(state.usedTags, key = { it.id }) { tag ->
                        NjSelectableChip(
                            text = tag.name,
                            selected = state.selectedTagNormalized == tag.nameNormalized,
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
                    NjSelectableChip(
                        text = "Newest",
                        selected = state.sortMode == SortMode.NEWEST,
                        onClick = { vm.onAction(LibraryAction.SetSortMode(SortMode.NEWEST)) }
                    )
                }
                item {
                    NjSelectableChip(
                        text = "Oldest",
                        selected = state.sortMode == SortMode.OLDEST,
                        onClick = { vm.onAction(LibraryAction.SetSortMode(SortMode.OLDEST)) }
                    )
                }
                item {
                    NjSelectableChip(
                        text = "Favs",
                        selected = state.sortMode == SortMode.FAVORITES_FIRST,
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
                            onClick = { onOpenOverview(idea.id) }
                        )
                    }
                }
            }
        }
    }
}
