package com.example.nightjar.ui.record

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightjar.ui.components.NjButton
import com.example.nightjar.ui.theme.*

/** Panels occupy the content area only: the transport remains reachable. */
@Composable
internal fun CapturePanelFrame(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = NjOutline)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = NjOnBg,
                modifier = Modifier.weight(1f))
            NjButton(text = "Close", icon = Icons.Filled.KeyboardArrowUp, caption = "CLOSE", onClick = onClose)
        }
        content()
    }
}

@Composable
internal fun CapturePlaybackSummary(state: RecordUiState, onOpen: () -> Unit) {
    val selected = state.capture.groups.filter { it.latchedTakeId != null }
    TextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = NjOnBg)) {
        Column(Modifier.weight(1f)) {
            Text(if (state.capture.playing) "PLAYING" else "SELECTED FOR PLAY",
                color = NjMuted, fontFamily = IbmPlexMono, fontSize = 10.sp)
            Text(if (selected.isEmpty()) "Nothing selected" else selected.joinToString(" + ") { group ->
                val take = state.capture.latchedTakes[group.latchedTakeId]
                if (take != null) "${group.displayName} · ${take.displayName}" else group.displayName
            }, color = NjOnBg, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Text("DETAILS", color = NjMuted, fontFamily = IbmPlexMono, fontSize = 10.sp,
            modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun CaptureWorkspacePanel(
    panel: String,
    state: RecordUiState,
    onAction: (RecordAction) -> Unit,
    onClose: () -> Unit,
    onTempo: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val capture = state.capture
    val canEdit = !capture.busy && !capture.pendingSave
    val canSelect = canEdit && !capture.playing
    val open = capture.groups.find { it.id == capture.openGroupId }
    var renamingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var name by rememberSaveable { mutableStateOf("") }

    CapturePanelFrame(when (panel) {
        "groups" -> "Record into"
        "playback" -> "Playback selection"
        else -> "Options"
    }, onClose, modifier) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (panel) {
                "groups" -> {
                    if (!canSelect) PanelExplanation("Stop before changing where you record.")
                    PanelExplanation("Each group holds alternate takes of one sound.")
                    if (capture.groups.isEmpty()) PanelExplanation("Original Idea is ready to record.")
                    capture.groups.forEach { group ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NjButton(text = group.displayName,
                                isActive = group.id == capture.openGroupId, enabled = canSelect,
                                onClick = { onAction(RecordAction.OpenGroup(group.id)); onClose() },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                            CaptureTextControl(text = "Rename", enabled = canEdit,
                                onClick = { name = group.displayName; renamingId = group.id },
                                modifier = Modifier.semantics { contentDescription = "Rename ${group.displayName}" })
                        }
                        if (renamingId == group.id) {
                            val focus = remember { FocusRequester() }
                            val bringIntoView = remember { BringIntoViewRequester() }
                            LaunchedEffect(group.id) {
                                focus.requestFocus()
                                bringIntoView.bringIntoView()
                            }
                            OutlinedTextField(value = name, onValueChange = { name = it.take(40) },
                                label = { Text("Name for ${group.displayName}") }, singleLine = true, enabled = canEdit,
                                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                                    .bringIntoViewRequester(bringIntoView))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NjButton(text = "Save", enabled = canEdit && name.isNotBlank(),
                                    onClick = { onAction(RecordAction.RenameGroup(group.id, name.trim())); renamingId = null })
                                CaptureTextControl(text = "Cancel", onClick = { renamingId = null })
                            }
                        }
                    }
                    HorizontalDivider(color = NjOutline)
                    NjButton(text = "New Group", enabled = canSelect,
                        onClick = { onAction(RecordAction.CreateGroup); onClose() }, modifier = Modifier.fillMaxWidth())
                    NjButton(text = "Add to Studio", caption = open?.displayName,
                        enabled = open != null && !open.inStudio && canEdit,
                        onClick = { open?.let { onAction(RecordAction.AddGroupToStudio(it.id)) } }, modifier = Modifier.fillMaxWidth())
                    if (open?.inStudio == true) PanelExplanation("This group is available in Studio.")
                }
                "playback" -> {
                    val selected = capture.groups.filter { it.latchedTakeId != null }
                    if (selected.isEmpty()) PanelExplanation("Select a take in Sound, then press Play.")
                    if (!canSelect) PanelExplanation("Stop before changing playback selection.")
                    selected.forEach { group ->
                        val take = capture.latchedTakes[group.latchedTakeId]
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(group.displayName, style = MaterialTheme.typography.titleSmall, color = NjOnBg)
                            Text(take?.let { "${it.displayName} · ${captureDurationLabel(it.durationMs)}" } ?: "Selected take",
                                color = NjMuted, style = MaterialTheme.typography.bodyMedium)
                            NjButton(text = "Remove", caption = "FROM PLAYBACK", enabled = canSelect,
                                onClick = { onAction(RecordAction.UnlatchGroup(group.id)) })
                        }
                    }
                }
                else -> {
                    if (!canEdit) PanelExplanation("Finish recording or saving before leaving this Idea.")
                    NjButton(text = "New Idea", enabled = canEdit && (capture.ideaId != null || state.words.text.isNotEmpty()),
                        onClick = { onAction(RecordAction.NewIdea); onClose() }, modifier = Modifier.fillMaxWidth())
                    NjButton(text = "Studio", enabled = canEdit && capture.ideaId != null,
                        onClick = { onAction(RecordAction.CreateStudioIdea); onClose() }, modifier = Modifier.fillMaxWidth())
                    NjButton(text = "Tempo", onClick = onTempo, modifier = Modifier.fillMaxWidth())
                    NjButton(text = "Settings", enabled = canEdit,
                        onClick = onSettings, modifier = Modifier.fillMaxWidth())
                    NjButton(text = "Retry Save", enabled = capture.pendingSave && !capture.busy,
                        onClick = { onAction(RecordAction.StartRecording); onClose() }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PanelExplanation(text: String) {
    Text(text, color = NjMuted, style = MaterialTheme.typography.bodySmall)
}
