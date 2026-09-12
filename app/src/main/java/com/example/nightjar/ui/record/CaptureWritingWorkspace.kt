package com.example.nightjar.ui.record

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightjar.ui.components.NjButton
import com.example.nightjar.ui.components.NjIcons
import com.example.nightjar.ui.components.NjKnob
import com.example.nightjar.ui.components.NjLedDot
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

    LaunchedEffect(state.words.text) {
        if (editor.text != state.words.text) {
            editor = editor.copy(text = state.words.text,
                selection = TextRange(editor.selection.start.coerceAtMost(state.words.text.length),
                    editor.selection.end.coerceAtMost(state.words.text.length)), composition = null)
        }
    }
    LaunchedEffect(state.isWriting, state.writingFocusRequest, state.words.ready) {
        if (!state.isWriting) { focusManager.clearFocus(); keyboard?.hide() }
        else if (state.words.ready && state.writingFocusRequest > handledFocus) {
            focus.requestFocus()
            keyboard?.show()
            handledFocus = state.writingFocusRequest
        }
    }
    BackHandler(enabled = state.isWriting && !keyboardOpen) { onAction(RecordAction.ShowSound) }
    BackHandler(enabled = !state.isWriting && state.isTempoDrawerOpen) {
        onAction(RecordAction.DismissTempoDrawer)
    }

    val status = when {
        state.isCountingIn -> "COUNT IN"
        state.isRecording -> "TAKE ${capture.takeNumber} / REC"
        state.isSaving -> "SAVING AUDIO"
        capture.pendingSave -> "AUDIO SAVE NEEDS RETRY"
        state.words.error != null -> "WORDS NEED SAVE RETRY"
        state.words.pending -> "SAVING WORDS"
        capture.playing -> "PLAYING TAKE ${capture.takes.find { it.id == capture.selectedTakeId }?.sortIndex?.plus(1)}"
        capture.ideaId != null -> "IDEA ${capture.ideaId} / ${capture.takes.size} TAKES"
        else -> "NIGHTJAR"
    }

    val edit: (TextFieldValue) -> Unit = { value -> editor = value; onAction(RecordAction.WordsChanged(value.text)) }
    if (landscape) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                CaptureWritingTransport(state, onRecord, onAction, Modifier.fillMaxWidth(), compact = true)
                CaptureWorkspaceModes(state, onAction, Modifier.fillMaxWidth())
            }
            Column(Modifier.weight(0.58f).fillMaxHeight()) {
                CaptureTakeShelf(state, onAction)
                if (state.isWriting) CaptureWordsEditor(state, editor, edit, focus, wordsScroll, onAction, Modifier.weight(1f), compact = true)
                else CaptureSoundPanel(state, onAction, onLibrary, onSettings, Modifier.weight(1f), compact = true)
            }
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            StatusLcd(status)
            CaptureWritingTransport(state, onRecord, onAction, Modifier.fillMaxWidth())
            CaptureWorkspaceModes(state, onAction, Modifier.fillMaxWidth())
            CaptureTakeShelf(state, onAction)
            if (state.isWriting) CaptureWordsEditor(state, editor, edit, focus, wordsScroll, onAction, Modifier.weight(1f))
            else CaptureSoundPanel(state, onAction, onLibrary, onSettings, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CaptureWordsEditor(state: RecordUiState, editor: TextFieldValue, edit: (TextFieldValue) -> Unit,
    focus: FocusRequester, scroll: androidx.compose.foundation.ScrollState,
    onAction: (RecordAction) -> Unit, modifier: Modifier, compact: Boolean = false) {
    val saveStatus: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                state.words.error != null -> state.words.error
                !state.words.ready -> "Loading words"
                state.words.pending -> "Saving words"
                state.words.ideaId != null -> "Words saved"
                else -> "Words"
            } ?: "Words", color = NjMuted, fontFamily = IbmPlexMono, fontSize = 11.sp,
                modifier = Modifier.weight(1f))
            NjButton(text = "Retry", caption = "SAVE WORDS", enabled = state.words.error != null,
                onClick = { onAction(RecordAction.RetryWords) })
        }
    }
    val paper: @Composable (Modifier) -> Unit = { paperModifier ->
        Box(paperModifier.background(NjWritingPaper).padding(if (compact) 6.dp else 14.dp)) {
            BasicTextField(value = editor, onValueChange = edit, enabled = state.words.ready,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = NjWritingInk),
                cursorBrush = SolidColor(NjWritingInk),
                modifier = Modifier.fillMaxSize().focusRequester(focus)
                    .semantics { contentDescription = "Idea words" }.verticalScroll(scroll))
        }
    }
    if (compact) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            paper(Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.width(140.dp)) { saveStatus() }
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            saveStatus()
            paper(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun CaptureSoundPanel(state: RecordUiState, onAction: (RecordAction) -> Unit,
    onLibrary: () -> Unit, onSettings: () -> Unit, modifier: Modifier, compact: Boolean = false) {
    Column(modifier.fillMaxWidth()) {
        CaptureMeterRow(state, onAction, if (compact) 72.dp else 96.dp)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            CaptureTempoDrawerSlot(state, onAction, maxHeight)
        }
        CaptureNavigationDock(state, onAction, onLibrary, onSettings)
    }
}

@Composable
private fun CaptureTempoDrawerSlot(state: RecordUiState, onAction: (RecordAction) -> Unit,
    availableHeight: androidx.compose.ui.unit.Dp) {
    AnimatedVisibility(
        visible = state.isTempoDrawerOpen,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        modifier = Modifier.fillMaxWidth().heightIn(max = availableHeight).padding(top = 8.dp)
    ) {
        CaptureTempoDrawer(state, onAction, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CaptureMeterRow(state: RecordUiState, onAction: (RecordAction) -> Unit, height: androidx.compose.ui.unit.Dp) {
    Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WaveformSection(state.postRecording, state.liveAmplitudes, NjTrackColors[0],
            { onAction(RecordAction.GoToOverview) }, Modifier.weight(1f).fillMaxHeight())
        CaptureTempoModule(state, onAction, Modifier.width(88.dp).fillMaxHeight())
    }
}

@Composable
private fun CaptureTempoModule(state: RecordUiState, onAction: (RecordAction) -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            NjButton(text = "Tempo", icon = NjIcons.Metronome, caption = "TEMPO",
                isActive = state.isTempoDrawerOpen, activeAccent = NjMetronomeLed,
                onClick = { onAction(RecordAction.ToggleTempoDrawer) }, modifier = Modifier.fillMaxSize())
            NjLedDot(isLit = state.isMetronomeEnabled, size = 5.dp, litColor = NjMetronomeLed,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 7.dp))
        }
        Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
            Text(if (state.isMetronomeEnabled) "${state.metronomeBpm.toInt()} BPM" else "",
                color = NjMetronomeLed.copy(alpha = 0.75f), fontFamily = IbmPlexMono, fontSize = 9.sp,
                maxLines = 1)
        }
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
private fun CaptureNavigationDock(state: RecordUiState, onAction: (RecordAction) -> Unit,
    onLibrary: () -> Unit, onSettings: () -> Unit) {
    val capture = state.capture
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NjButton(text = "Studio", enabled = !capture.busy && !capture.pendingSave && state.words.safeToLeaveIdea,
            onClick = { onAction(RecordAction.CreateStudioIdea) }, modifier = Modifier.weight(1f))
        NjButton(text = "Library", enabled = !capture.busy, onClick = onLibrary, modifier = Modifier.weight(1f))
        NjButton(text = "Settings", onClick = onSettings, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CaptureWritingTransport(state: RecordUiState, onRecord: () -> Unit,
    onAction: (RecordAction) -> Unit, modifier: Modifier, compact: Boolean = false) {
    val capture = state.capture
    Row(modifier.height(if (compact) 72.dp else 112.dp), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        NjButton(text = "Play", icon = Icons.Filled.PlayArrow, caption = "PLAY", isActive = capture.playing,
            enabled = !capture.busy && !capture.pendingSave && capture.takes.any { it.id == capture.selectedTakeId && it.durationMs > 0 },
            onClick = { onAction(RecordAction.PlayTake) })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HardwareRecordButton(state.isRecording, enabled = !state.isSaving && !state.isCountingIn, onClick = onRecord,
                modifier = if (compact) Modifier.size(48.dp) else Modifier)
            Text("RECORD", color = NjMuted, fontFamily = IbmPlexMono, fontSize = 10.sp)
        }
        NjButton(text = "Stop", icon = Icons.Filled.Stop, caption = "STOP", ledColor = NjRecordCoral,
            isActive = capture.recording || capture.playing, enabled = capture.recording || capture.playing,
            onClick = { onAction(RecordAction.StopAndSave) })
    }
}

@Composable
private fun CaptureWorkspaceModes(state: RecordUiState, onAction: (RecordAction) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NjButton(text = "Sound", isActive = !state.isWriting, onClick = { onAction(RecordAction.ShowSound) }, modifier = Modifier.weight(1f))
        NjButton(text = "Write", isActive = state.isWriting,
            enabled = !state.capture.busy && !state.capture.pendingSave,
            onClick = { onAction(RecordAction.CreateWriteIdea) }, modifier = Modifier.weight(1f))
        NjButton(text = "New Idea", enabled = !state.capture.busy && !state.capture.pendingSave &&
            state.words.safeToLeaveIdea && (state.capture.ideaId != null || state.words.text.isNotEmpty()),
            onClick = { onAction(RecordAction.NewIdea) }, modifier = Modifier.weight(1f))
    }
}
