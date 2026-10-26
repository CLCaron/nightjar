package com.example.nightjar.ui.record

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightjar.ui.components.NjButton
import com.example.nightjar.ui.components.NjKnob
import com.example.nightjar.ui.components.NjRecessedPanel
import com.example.nightjar.ui.theme.*

/** Transport stays outside the scrolling work area, including when the IME is open. */
@Composable
internal fun CaptureWritingWorkspace(
    state: RecordUiState,
    onRecord: () -> Unit,
    onAction: (RecordAction) -> Unit,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val capture = state.capture
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var handledFocus by rememberSaveable { mutableIntStateOf(0) }
    var editor by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.words.text)) }
    val wordsScroll = rememberScrollState()
    var panel by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.words.text) {
        if (editor.text != state.words.text) {
            editor = editor.copy(text = state.words.text,
                selection = TextRange(editor.selection.start.coerceAtMost(state.words.text.length),
                    editor.selection.end.coerceAtMost(state.words.text.length)), composition = null)
        }
    }
    LaunchedEffect(state.isWriting, state.writingFocusRequest, state.words.ready, panel) {
        if (!state.isWriting) { focusManager.clearFocus(); keyboard?.hide() }
        else if (panel == null && !state.isTempoDrawerOpen && state.words.ready && state.writingFocusRequest > handledFocus) {
            focus.requestFocus()
            keyboard?.show()
            handledFocus = state.writingFocusRequest
        }
    }
    BackHandler(enabled = state.isWriting && !keyboardOpen && panel == null && !state.isTempoDrawerOpen) { onAction(RecordAction.ShowSound) }
    BackHandler(enabled = state.isTempoDrawerOpen) {
        onAction(RecordAction.DismissTempoDrawer)
    }
    BackHandler(enabled = panel != null) { panel = null }

    val status = when {
        capture.importing -> "IMPORTING AUDIO"
        capture.loadingIdea -> "LOADING IDEA"
        state.isCountingIn -> "COUNT IN"
        state.isRecording -> "TAKE ${capture.takeNumber} / REC"
        state.isSaving -> "SAVING AUDIO"
        capture.pendingSave -> "AUDIO SAVE NEEDS RETRY"
        state.words.error != null -> "WORDS NEED SAVE RETRY"
        capture.playing -> "PLAYING LOOP"
        else -> "READY"
    }

    val edit: (TextFieldValue) -> Unit = { value -> editor = value; onAction(RecordAction.WordsChanged(value.text)) }
    val openPanel: (String) -> Unit = { next ->
        focusManager.clearFocus()
        keyboard?.hide()
        onAction(RecordAction.DismissTempoDrawer)
        panel = next
    }
    val work: @Composable (Modifier, Boolean) -> Unit = { workModifier, compact ->
        when {
            panel != null -> CaptureWorkspacePanel(
                panel = panel.orEmpty(), state = state, onAction = onAction,
                onClose = { panel = null }, onTempo = {
                    panel = null
                    onAction(RecordAction.ToggleTempoDrawer)
                }, onSettings = { panel = null; onSettings() }, modifier = workModifier)
            state.isTempoDrawerOpen -> CapturePanelFrame("Tempo", { onAction(RecordAction.DismissTempoDrawer) }, workModifier) {
                CaptureTempoDrawer(state, onAction, Modifier.weight(1f))
            }
            state.isWriting -> CaptureWordsEditor(state, editor, edit, focus, wordsScroll, onAction, workModifier, compact)
            else -> CaptureSoundPanel(state, status, onAction, workModifier, compact)
        }
    }
    BoxWithConstraints(modifier) {
        val compact = keyboardOpen || maxHeight < 500.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CaptureWorkspaceHeader(state, onLibrary, { openPanel("options") })
            if (landscape) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(0.44f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CaptureWorkspaceModes(state, onAction, { panel = null }, Modifier.fillMaxWidth())
                        CaptureDestination(state, { openPanel("groups") })
                        Spacer(Modifier.weight(1f))
                        CapturePlaybackSummary(state, { openPanel("playback") })
                        CaptureWritingTransport(state, { panel = null; onRecord() }, onAction, Modifier.fillMaxWidth(), compact = true)
                    }
                    work(Modifier.weight(0.56f).fillMaxHeight(), true)
                }
            } else {
                CaptureWorkspaceModes(state, onAction, { panel = null }, Modifier.fillMaxWidth())
                CaptureDestination(state, { openPanel("groups") })
                work(Modifier.weight(1f).fillMaxWidth(), compact)
                CapturePlaybackSummary(state, { openPanel("playback") })
                CaptureWritingTransport(state, { panel = null; onRecord() }, onAction, Modifier.fillMaxWidth(), compact)
            }
        }
    }
}

@Composable
private fun CaptureWorkspaceHeader(state: RecordUiState, onLibrary: () -> Unit, onOptions: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NjButton(text = "Library", icon = Icons.Filled.LibraryMusic, caption = "LIBRARY",
            enabled = !state.capture.busy && !state.capture.pendingSave, onClick = onLibrary)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NIGHTJAR", color = NjMuted, fontFamily = IbmPlexMono, fontSize = 10.sp)
            Text(state.capture.ideaTitle ?: "New Idea", color = NjStarlight,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        NjButton(text = "Options", icon = Icons.Filled.MoreHoriz, caption = "OPTIONS", onClick = onOptions)
    }
}

@Composable
private fun CaptureDestination(state: RecordUiState, onOpen: () -> Unit) {
    val open = state.capture.groups.find { it.id == state.capture.openGroupId }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("RECORDING INTO", color = NjMuted, fontFamily = IbmPlexMono, fontSize = 10.sp)
            Text(open?.displayName ?: "Original Idea", color = NjOnBg,
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        NjButton(text = "Groups", icon = Icons.Filled.KeyboardArrowDown, caption = "GROUPS", onClick = onOpen)
    }
}

@Composable
private fun CaptureWordsEditor(state: RecordUiState, editor: TextFieldValue, edit: (TextFieldValue) -> Unit,
    focus: FocusRequester, scroll: androidx.compose.foundation.ScrollState,
    onAction: (RecordAction) -> Unit, modifier: Modifier, compact: Boolean = false) {
    val saveStatus: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                state.words.error != null -> "WORDS NEED SAVE RETRY"
                !state.words.ready -> "Loading words"
                else -> "WORDS"
            }, color = NjMuted, fontFamily = IbmPlexMono, fontSize = 11.sp,
                modifier = Modifier.weight(1f))
            NjButton(text = "Retry", caption = "SAVE WORDS", enabled = state.words.error != null,
                onClick = { onAction(RecordAction.RetryWords) })
        }
    }
    val paper: @Composable (Modifier) -> Unit = { paperModifier ->
        Box(paperModifier.background(NjWritingPaper).padding(if (compact) 6.dp else 14.dp)) {
            BasicTextField(value = editor, onValueChange = edit, enabled = state.words.ready && !state.capture.importing,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = NjWritingInk),
                cursorBrush = SolidColor(NjWritingInk),
                modifier = Modifier.fillMaxSize().focusRequester(focus)
                    .semantics { contentDescription = "Idea words" }.verticalScroll(scroll))
        }
    }
    Column(modifier.fillMaxWidth()) {
        // Reserve the full width for words even in the narrow landscape pane.
        saveStatus()
        paper(Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun CaptureSoundPanel(state: RecordUiState, status: String, onAction: (RecordAction) -> Unit,
    modifier: Modifier, compact: Boolean = false) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status, color = if (state.isRecording || state.capture.pendingSave) NjRecordCoral else NjMuted,
                fontFamily = IbmPlexMono, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (state.isMetronomeEnabled) {
                Text("${state.metronomeBpm.toInt()} BPM", color = NjMetronomeLed,
                    fontFamily = IbmPlexMono, fontSize = 11.sp)
            }
        }
        CaptureMeterRow(state, onAction, if (compact) 48.dp else 72.dp)
        CaptureTakeShelf(state, onAction, Modifier.weight(1f))
    }
}

@Composable
private fun CaptureMeterRow(state: RecordUiState, onAction: (RecordAction) -> Unit, height: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxWidth().height(height)) {
        WaveformSection(state.postRecording, state.liveAmplitudes, NjTrackColors[0],
            { state.capture.takes.lastOrNull()?.let { onAction(RecordAction.SelectTake(it.id)) } },
            Modifier.fillMaxSize())
        if (state.capture.importing) Text("IMPORTING AUDIO", color = NjStarlight,
            fontFamily = IbmPlexMono, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun CaptureTempoDrawer(state: RecordUiState, onAction: (RecordAction) -> Unit, modifier: Modifier) {
    NjRecessedPanel(modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NjButton(text = "Click", caption = "CLICK", isActive = state.isMetronomeEnabled,
                    ledColor = NjMetronomeLed, onClick = { onAction(RecordAction.ToggleMetronome) })
                Spacer(Modifier.weight(1f))
                NjKnob(value = state.metronomeVolume,
                    onValueChange = { onAction(RecordAction.SetMetronomeVolume(it)) },
                    knobSize = 36.dp, label = "VOL")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                NjButton(text = "-", caption = "SLOWER",
                    onClick = { onAction(RecordAction.SetMetronomeBpm(state.metronomeBpm - 1.0)) },
                    modifier = Modifier.weight(1f))
                Text("${state.metronomeBpm.toInt()}", color = NjMetronomeLed,
                    fontFamily = IbmPlexMono, fontSize = 16.sp,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                NjButton(text = "+", caption = "FASTER",
                    onClick = { onAction(RecordAction.SetMetronomeBpm(state.metronomeBpm + 1.0)) },
                    modifier = Modifier.weight(1f))
                NjButton(text = "Tap", caption = "TAP", onClick = { onAction(RecordAction.TapTempo) },
                    modifier = Modifier.weight(1f))
            }
            Text("COUNT-IN", color = NjMuted, fontFamily = IbmPlexMono, fontSize = 9.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Off", 1 to "1", 2 to "2", 4 to "4").forEach { (bars, label) ->
                    NjButton(text = label, isActive = state.countInBars == bars, ledColor = NjMetronomeLed,
                        onClick = { onAction(RecordAction.SetCountInBars(bars)) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CaptureWritingTransport(state: RecordUiState, onRecord: () -> Unit,
    onAction: (RecordAction) -> Unit, modifier: Modifier, compact: Boolean = false) {
    val capture = state.capture
    Row(modifier.heightIn(min = if (compact) 80.dp else 112.dp), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        NjButton(text = "Play", icon = Icons.Filled.PlayArrow, caption = "PLAY", isActive = capture.playing,
            enabled = !capture.busy && !capture.pendingSave && capture.groups.any { it.latchedTakeId != null },
            onClick = { onAction(RecordAction.PlayTake) })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HardwareRecordButton(state.isRecording,
                enabled = !state.isSaving && !state.isCountingIn && !capture.loadingIdea && !capture.importing,
                onClick = onRecord,
                modifier = if (compact) Modifier.size(56.dp) else Modifier)
            Text("RECORD", color = NjMuted, fontFamily = IbmPlexMono, fontSize = 10.sp)
        }
        NjButton(text = "Stop", icon = Icons.Filled.Stop, caption = "STOP", ledColor = NjRecordCoral,
            isActive = capture.recording || capture.playing, enabled = capture.recording || capture.playing,
            onClick = { onAction(RecordAction.StopAndSave) })
    }
}

@Composable
private fun CaptureWorkspaceModes(state: RecordUiState, onAction: (RecordAction) -> Unit,
    onShowContent: () -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NjButton(text = "Sound", isActive = !state.isWriting,
            onClick = { onShowContent(); onAction(RecordAction.DismissTempoDrawer); onAction(RecordAction.ShowSound) }, modifier = Modifier.weight(1f))
        NjButton(text = "Write", isActive = state.isWriting,
            enabled = !state.capture.busy && !state.capture.pendingSave,
            onClick = { onShowContent(); onAction(RecordAction.CreateWriteIdea) }, modifier = Modifier.weight(1f))
        NjButton(text = "Import", enabled = !state.capture.busy && !state.capture.pendingSave && !state.capture.playing,
            onClick = { onShowContent(); onAction(RecordAction.DismissTempoDrawer); onAction(RecordAction.RequestAudioImport) },
            modifier = Modifier.weight(1f))
    }
}
