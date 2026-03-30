package com.example.nightjar.ui.studio

import com.example.nightjar.audio.AudioLatencyEstimator
import com.example.nightjar.audio.MusicalTimeConverter
import com.example.nightjar.ui.components.NjButton
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.nightjar.ui.components.NjTopBar
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjAmber
import com.example.nightjar.ui.theme.NjBg
import com.example.nightjar.ui.theme.NjRecordCoral
import com.example.nightjar.ui.theme.NjLedGreen
import com.example.nightjar.ui.theme.NjCursorTeal
import com.example.nightjar.ui.theme.NjOutline

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.SkipPrevious
import com.example.nightjar.ui.components.NjIcons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import com.example.nightjar.ui.theme.NjMetronomeLed
import com.example.nightjar.ui.theme.NjMuted
import com.example.nightjar.ui.theme.NjError
import com.example.nightjar.ui.theme.IbmPlexMono
import com.example.nightjar.ui.components.NjRecessedPanel
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest

/**
 * Studio screen — multi-track DAW-like workspace.
 *
 * Displays a scrollable timeline with stacked track lanes, a time ruler,
 * and a playhead. Users can play/pause, scrub, add overdub recordings,
 * drag tracks to reposition them, and trim track edges. Handles
 * microphone permissions and gracefully stops recording when backgrounded.
 */
@Composable
fun StudioScreen(
    ideaId: Long,
    onBack: () -> Unit,
    onOpenPianoRoll: (trackId: Long, clipId: Long) -> Unit = { _, _ -> },
    onOpenDrumEditor: (trackId: Long, clipId: Long) -> Unit = { _, _ -> }
) {
    val vm: StudioViewModel = hiltViewModel()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(ideaId) {
        vm.onAction(StudioAction.Load(ideaId))
    }

    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val currentIsRecording by rememberUpdatedState(state.isRecording)

    val scrollState = rememberScrollState()
    var transportOffsetPx by remember { mutableFloatStateOf(0f) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.globalPositionMs, isScrubbing, state.totalDurationMs) {
        if (!isScrubbing) {
            scrubMs = state.globalPositionMs.coerceIn(
                0L,
                state.totalDurationMs.coerceAtLeast(1L)
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.onAction(StudioAction.MicPermissionGranted)
        }
    }

    // Intercept system back so empty-idea cleanup runs via the ViewModel.
    BackHandler { vm.onAction(StudioAction.NavigateBack) }

    LaunchedEffect(Unit) {
        vm.effects.collectLatest { effect ->
            when (effect) {
                is StudioEffect.NavigateBack -> onBack()
                is StudioEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
                is StudioEffect.ShowStatus -> snackbarHostState.showSnackbar(effect.message)
                is StudioEffect.RequestMicPermission -> {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasPermission) {
                        vm.onAction(StudioAction.MicPermissionGranted)
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                is StudioEffect.NavigateToPianoRoll -> {
                    onOpenPianoRoll(effect.trackId, effect.clipId)
                }
                is StudioEffect.NavigateToDrumEditor -> {
                    onOpenDrumEditor(effect.trackId, effect.clipId)
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && currentIsRecording) {
                vm.onAction(StudioAction.StopRecording)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = NjBg,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NjTopBar(
                    title = state.ideaTitle.ifBlank { "Studio" },
                    onBack = { vm.onAction(StudioAction.NavigateBack) },
                    trailing = {
                        NjButton(
                            text = "Setup",
                            onClick = { vm.onAction(StudioAction.ShowLatencySetup) },
                            textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                )

                state.errorMessage?.let { msg ->
                    Text(msg, color = MaterialTheme.colorScheme.error)
                }

                if (state.isLoading) {
                    Text(
                        "Loading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                    return@Column
                }

                TransportAndControls(
                    state = state,
                    onAction = vm::onAction,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        transportOffsetPx = coords.positionInParent().y
                    }
                )

                val displayPositionMs =
                    if (isScrubbing) scrubMs else state.globalPositionMs

                TimelinePanel(
                    tracks = state.tracks,
                    globalPositionMs = displayPositionMs,
                    cursorPositionMs = state.cursorPositionMs,
                    totalDurationMs = state.totalDurationMs,
                    msPerDp = state.msPerDp,
                    isPlaying = state.isPlaying,
                    isRecording = state.isRecording,
                    isAddTrackDrawerOpen = state.isAddTrackDrawerOpen,
                    liveAmplitudes = state.liveAmplitudes,
                    recordingStartGlobalMs = state.recordingStartGlobalMs,
                    recordingTargetTrackId = state.recordingTargetTrackId,
                    recordingElapsedMs = state.recordingElapsedMs,
                    dragState = state.dragState,
                    trimState = state.trimState,
                    loopStartMs = state.loopStartMs,
                    loopEndMs = state.loopEndMs,
                    isLoopEnabled = state.isLoopEnabled,
                    expandedTrackIds = state.expandedTrackIds,
                    soloedTrackIds = state.soloedTrackIds,
                    armedTrackId = state.armedTrackId,
                    audioClips = state.audioClips,
                    expandedAudioClipId = state.expandedAudioClipId,
                    audioClipDragState = state.audioClipDragState,
                    audioClipTrimState = state.audioClipTrimState,
                    drumPatterns = state.drumPatterns,
                    midiTracks = state.midiTracks,
                    clipDragState = state.clipDragState,
                    midiClipDragState = state.midiClipDragState,
                    expandedClipState = state.expandedClipState,
                    bpm = state.bpm,
                    timeSignatureNumerator = state.timeSignatureNumerator,
                    timeSignatureDenominator = state.timeSignatureDenominator,
                    isSnapEnabled = state.isSnapEnabled,
                    gridResolution = state.gridResolution,
                    getAudioFile = vm::getAudioFile,
                    onAction = vm::onAction,
                    onScrub = { newMs ->
                        isScrubbing = true
                        scrubMs = newMs
                    },
                    onScrubFinished = { finalMs ->
                        isScrubbing = false
                        vm.onAction(StudioAction.SeekFinished(finalMs))
                    }
                )

                Spacer(Modifier.height(80.dp))
            }

            // Pinned transport overlay when scrolled past original position
            val isPinned = !state.isLoading
                && transportOffsetPx > 0f
                && scrollState.value.toFloat() >= transportOffsetPx

            if (isPinned) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NjBg)
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 4.dp)
                ) {
                    TransportAndControls(state = state, onAction = vm::onAction)
                    Spacer(Modifier.height(4.dp))
                    val dividerColor = NjOutline
                    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
                        drawLine(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    dividerColor.copy(alpha = 0.5f),
                                    dividerColor.copy(alpha = 0.5f),
                                    Color.Transparent
                                )
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = size.height
                        )
                    }
                }
            }
        }
    }

    if (state.showInstrumentPickerForTrackId != null) {
        val trackId = state.showInstrumentPickerForTrackId!!
        val currentProgram = state.midiTracks[trackId]?.midiProgram ?: 0
        InstrumentPicker(
            currentProgram = currentProgram,
            onSelect = { program ->
                vm.onAction(StudioAction.SetMidiInstrument(trackId, program))
            },
            onPreview = { program ->
                vm.onAction(StudioAction.PreviewInstrument(program))
            },
            onDismiss = { vm.onAction(StudioAction.DismissInstrumentPicker) }
        )
    }

    if (state.confirmingDeleteTrackId != null) {
        val trackName = state.tracks
            .find { it.id == state.confirmingDeleteTrackId }
            ?.displayName ?: "this track"

        AlertDialog(
            onDismissRequest = { vm.onAction(StudioAction.DismissDeleteTrack) },
            title = { Text("Delete $trackName?") },
            text = { Text("This will permanently delete the track and its audio file.") },
            confirmButton = {
                TextButton(onClick = { vm.onAction(StudioAction.ExecuteDeleteTrack) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.onAction(StudioAction.DismissDeleteTrack) }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (state.showLatencySetupDialog) {
        LatencySetupDialog(
            diagnostics = state.latencyDiagnostics,
            manualOffsetMs = state.manualOffsetMs,
            returnToCursor = state.returnToCursor,
            onOffsetChange = { vm.onAction(StudioAction.SetManualOffset(it)) },
            onClearOffset = { vm.onAction(StudioAction.ClearManualOffset) },
            onToggleReturnToCursor = { vm.onAction(StudioAction.ToggleReturnToCursor) },
            onDismiss = { vm.onAction(StudioAction.DismissLatencySetup) }
        )
    }

    // Track rename dialog
    val renamingTrackId = state.renamingTrackId
    if (renamingTrackId != null) {
        RenameDialog(
            title = "Rename track",
            currentName = state.renamingTrackCurrentName,
            onConfirm = { newName ->
                vm.onAction(
                    StudioAction.ConfirmRenameTrack(renamingTrackId, newName)
                )
            },
            onDismiss = { vm.onAction(StudioAction.DismissRenameTrack) }
        )
    }

    // Take rename dialog
    val renamingTakeId = state.renamingTakeId
    if (renamingTakeId != null) {
        RenameDialog(
            title = "Rename take",
            currentName = state.renamingTakeCurrentName,
            onConfirm = { newName ->
                vm.onAction(
                    StudioAction.ConfirmRenameTake(renamingTakeId, newName)
                )
            },
            onDismiss = { vm.onAction(StudioAction.DismissRenameTake) }
        )
    }

    // Take delete confirmation dialog
    if (state.confirmingDeleteTakeId != null) {
        val takeName = state.audioClips.values.flatten()
            .flatMap { it.takes }
            .find { it.id == state.confirmingDeleteTakeId }
            ?.displayName ?: "this take"

        AlertDialog(
            onDismissRequest = { vm.onAction(StudioAction.DismissDeleteTake) },
            title = { Text("Delete $takeName?") },
            text = { Text("This will permanently delete the take and its audio file.") },
            confirmButton = {
                TextButton(onClick = { vm.onAction(StudioAction.ExecuteDeleteTake) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.onAction(StudioAction.DismissDeleteTake) }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TransportAndControls(
    state: StudioUiState,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val hasTracksAndNotRecording = state.tracks.isNotEmpty() && !state.isRecording

            // Loop + Clear rocker pill (left) -- always visible, dimmed when disabled
            Row(
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    .alpha(if (hasTracksAndNotRecording) 1f else 0.35f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NjButton(
                    text = "Loop",
                    icon = Icons.Filled.Repeat,
                    onClick = { if (hasTracksAndNotRecording) onAction(StudioAction.ToggleLoop) },
                    isActive = state.isLoopEnabled,
                    ledColor = NjAmber,
                )

                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(NjOutline)
                )

                NjButton(
                    text = "Clear",
                    icon = Icons.Filled.Close,
                    onClick = {
                        if (hasTracksAndNotRecording && state.hasLoopRegion) {
                            onAction(StudioAction.ClearLoopRegion)
                        }
                    },
                    isActive = !state.hasLoopRegion,
                    ledColor = NjMuted2,
                    activeGlow = false,
                )
            }

            Spacer(Modifier.weight(1f))

            // Transport cluster (right): Restart, Play, Rec
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Restart -- always visible, dimmed when no tracks or recording
                NjButton(
                    text = "Restart",
                    icon = Icons.Filled.SkipPrevious,
                    onClick = { if (hasTracksAndNotRecording) onAction(StudioAction.RestartPlayback) },
                    modifier = Modifier.alpha(if (hasTracksAndNotRecording) 1f else 0.35f),
                    textColor = NjLedGreen.copy(alpha = 0.5f),
                )

                // Play / Pause -- always visible, dimmed when no tracks or recording
                NjButton(
                    text = "Play",
                    icon = NjIcons.PlayPause,
                    onClick = {
                        if (hasTracksAndNotRecording) {
                            if (state.isPlaying) {
                                onAction(StudioAction.Pause)
                            } else {
                                onAction(StudioAction.Play)
                            }
                        }
                    },
                    isActive = state.isPlaying,
                    ledColor = NjLedGreen,
                    modifier = Modifier.alpha(if (hasTracksAndNotRecording) 1f else 0.35f),
                )

                // Record button -- coral LED
                NjButton(
                    text = "Rec",
                    icon = Icons.Filled.FiberManualRecord,
                    onClick = {
                        if (state.isRecording) {
                            onAction(StudioAction.StopRecording)
                        } else {
                            onAction(StudioAction.StartRecording)
                        }
                    },
                    isActive = state.isRecording,
                    ledColor = NjRecordCoral,
                )
            }
        }

        StudioStatusLcd(state = state, onAction = onAction)

        // Project controls: time sig, BPM, snap, metronome, position
        if (!state.isLoading && state.errorMessage == null) {
            ProjectControlsBar(
                bpm = state.bpm,
                timeSignatureNumerator = state.timeSignatureNumerator,
                timeSignatureDenominator = state.timeSignatureDenominator,
                isSnapEnabled = state.isSnapEnabled,
                gridResolution = state.gridResolution,
                globalPositionMs = state.globalPositionMs,
                isMetronomeEnabled = state.isMetronomeEnabled,
                isMetronomeSettingsOpen = state.isMetronomeSettingsOpen,
                metronomeVolume = state.metronomeVolume,
                countInBars = state.countInBars,
                lastBeatFrame = state.lastBeatFrame,
                isPlaying = state.isPlaying,
                onAction = onAction
            )
        }
    }
}

/**
 * Contextual LCD status strip between the transport row and project controls.
 *
 * Shows the current studio state in an NjRecessedPanel with IBM Plex Mono text
 * (matching the Record screen's hardware LCD aesthetic). The Disarm button is
 * always visible (dimmed when inactive) for a consistent hardware panel feel.
 * A Delete button appears when a clip is selected.
 *
 * The LCD panel measures all possible text strings invisibly so its width stays
 * stable across state changes -- no jittery resizing.
 */
@Composable
private fun StudioStatusLcd(
    state: StudioUiState,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val armedTrackName = state.armedTrackId?.let { id ->
        state.tracks.find { it.id == id }?.displayName
    }
    val recordingTrackName = state.recordingTargetTrackId?.let { id ->
        state.tracks.find { it.id == id }?.displayName
    } ?: armedTrackName

    val coralColor = NjRecordCoral
    val amberColor = NjAmber
    val mutedColor = NjMuted
    val errorColor = NjError

    // Determine current LCD text and color by priority
    val lcdText: String
    val lcdColor: Color
    when {
        state.isRecording -> {
            lcdText = "REC \u00B7 ${recordingTrackName ?: "Track"}"
            lcdColor = coralColor.copy(alpha = 0.85f)
        }
        state.isCountingIn -> {
            lcdText = "COUNT IN"
            lcdColor = coralColor.copy(alpha = 0.7f)
        }
        state.armedTrackId != null -> {
            lcdText = "ARMED \u00B7 ${armedTrackName ?: "Track"}"
            lcdColor = coralColor.copy(alpha = 0.7f)
        }
        state.expandedClipState != null -> {
            lcdText = "CLIP \u00B7 ${state.expandedClipState.clipType.uppercase()}"
            lcdColor = amberColor.copy(alpha = 0.7f)
        }
        else -> {
            lcdText = "READY"
            lcdColor = mutedColor.copy(alpha = 0.4f)
        }
    }

    val lcdStyle = TextStyle(
        fontFamily = IbmPlexMono,
        fontSize = 12.sp,
        letterSpacing = 1.2.sp
    )

    // All possible texts for stable width measurement (longest track name in project)
    val longestName = state.tracks.maxByOrNull { it.displayName.length }?.displayName ?: "Track"
    val measuringTexts = remember(longestName) {
        listOf(
            "READY",
            "COUNT IN",
            "ARMED \u00B7 $longestName",
            "REC \u00B7 $longestName",
            "CLIP \u00B7 AUDIO"
        )
    }

    // Disarm button state
    val isArmed = state.armedTrackId != null
    val disarmEnabled = isArmed && !state.isRecording && !state.isCountingIn

    // Delete button state
    val showDelete = state.expandedClipState != null
        && !state.isRecording && !state.isCountingIn

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NjRecessedPanel {
            Box {
                // Invisible texts to lock the panel to the widest possible content
                measuringTexts.forEach { txt ->
                    Text(
                        text = txt,
                        style = lcdStyle,
                        maxLines = 1,
                        modifier = Modifier.alpha(0f)
                    )
                }
                // Visible LCD text
                Text(
                    text = lcdText,
                    style = lcdStyle,
                    color = lcdColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Disarm -- always visible; lights up coral when a track is armed
        NjButton(
            text = "Disarm",
            onClick = {
                if (disarmEnabled) onAction(StudioAction.ToggleArm(state.armedTrackId!!))
            },
            isActive = disarmEnabled,
            ledColor = coralColor,
            textColor = mutedColor,
        )

        // Delete -- contextual, only when a clip is selected
        if (showDelete) {
            val clip = state.expandedClipState!!
            NjButton(
                text = "Delete",
                onClick = {
                    when (clip.clipType) {
                        "audio" -> onAction(StudioAction.DeleteAudioClip(clip.trackId, clip.clipId))
                        "drum" -> onAction(StudioAction.DeleteClip(clip.trackId, clip.clipId))
                        "midi" -> onAction(StudioAction.DeleteMidiClip(clip.trackId, clip.clipId))
                    }
                    onAction(StudioAction.DismissClipPanel)
                },
                textColor = errorColor
            )
        }
    }
}

@Composable
private fun LatencySetupDialog(
    diagnostics: AudioLatencyEstimator.LatencyDiagnostics?,
    manualOffsetMs: Long,
    returnToCursor: Boolean,
    onOffsetChange: (Long) -> Unit,
    onClearOffset: () -> Unit,
    onToggleReturnToCursor: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio Sync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (diagnostics != null) {
                    // Device type
                    Text(
                        text = diagnostics.deviceType.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NjAmber
                    )

                    // Auto-estimated latency breakdown
                    Text(
                        text = "${diagnostics.estimatedOutputMs}ms output + " +
                                "${diagnostics.estimatedInputMs}ms input",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    Spacer(Modifier.height(4.dp))

                    // Manual offset slider
                    Text(
                        text = "Manual offset",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )

                    OffsetSlider(
                        offsetMs = manualOffsetMs,
                        onOffsetChange = onOffsetChange,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Offset value display
                    Text(
                        text = "${manualOffsetMs}ms",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (manualOffsetMs != 0L) NjAmber
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    Text(
                        text = "If overdubs sound late, drag left (negative)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )

                    Spacer(Modifier.height(8.dp))

                    // Transport section
                    Text(
                        text = "Transport",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )

                    NjButton(
                        text = "Return to cursor on stop",
                        onClick = onToggleReturnToCursor,
                        isActive = returnToCursor,
                        ledColor = NjCursorTeal
                    )
                } else {
                    Text(
                        "Loading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        dismissButton = {
            if (manualOffsetMs != 0L) {
                TextButton(onClick = onClearOffset) {
                    Text("Reset")
                }
            }
        }
    )
}

/**
 * Offset slider for manual latency adjustment.
 *
 * Range: -500ms to +500ms. Gold fill from center to thumb. Snaps to
 * 0 when within ±15ms of center.
 */
@Composable
private fun OffsetSlider(
    offsetMs: Long,
    onOffsetChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasWidth by remember { mutableFloatStateOf(0f) }

    // Map -500..+500 to 0.0..1.0
    val fraction = ((offsetMs + 500f) / 1000f).coerceIn(0f, 1f)

    // Hoist theme colors before Canvas (DrawScope is not composable)
    val sliderTrackColor = NjMuted
    val sliderFillColor = NjAmber

    Canvas(
        modifier = modifier
            .height(40.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        canvasWidth = size.width.toFloat()
                        if (canvasWidth > 0f) {
                            val raw = ((offset.x / canvasWidth) * 1000f - 500f).toLong()
                            onOffsetChange(snapToCenter(raw))
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (canvasWidth > 0f) {
                            val raw = ((change.position.x / canvasWidth) * 1000f - 500f).toLong()
                            onOffsetChange(snapToCenter(raw))
                        }
                    },
                    onDragEnd = {},
                    onDragCancel = {}
                )
            }
    ) {
        canvasWidth = size.width
        val centerY = size.height / 2f
        val trackHeight = 2.dp.toPx()
        val thumbRadius = 6.dp.toPx()
        val centerX = size.width / 2f
        val thumbX = size.width * fraction

        // Full track background
        drawLine(
            color = sliderTrackColor.copy(alpha = 0.15f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = trackHeight
        )

        // Center tick mark
        drawLine(
            color = sliderTrackColor.copy(alpha = 0.3f),
            start = Offset(centerX, centerY - 6.dp.toPx()),
            end = Offset(centerX, centerY + 6.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )

        // Gold fill from center to thumb
        if (thumbX != centerX) {
            val fillStart = minOf(centerX, thumbX)
            val fillEnd = maxOf(centerX, thumbX)
            drawLine(
                color = sliderFillColor.copy(alpha = 0.6f),
                start = Offset(fillStart, centerY),
                end = Offset(fillEnd, centerY),
                strokeWidth = trackHeight
            )
        }

        // Thumb
        drawCircle(
            color = sliderFillColor,
            radius = thumbRadius,
            center = Offset(thumbX, centerY)
        )
    }
}

/** Snap to 0 when within +/-15ms of center, then clamp to range. */
private fun snapToCenter(rawMs: Long): Long {
    val snapped = if (rawMs in -15L..15L) 0L else rawMs
    return snapped.coerceIn(-500L, 500L)
}

/** Reusable rename dialog with a pre-filled text field. */
@Composable
private fun RenameDialog(
    title: String,
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(currentName) { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** Common time signature presets for cycling. */
private val TIME_SIGNATURE_PRESETS = listOf(
    4 to 4,
    3 to 4,
    6 to 8,
    2 to 4
)

private val GRID_RESOLUTION_PRESETS = listOf(4, 8, 16, 32)

/**
 * Compact project controls bar: time signature, BPM, snap toggle, grid resolution, position readout.
 * Sits between the title and the timeline.
 */
@Composable
private fun ProjectControlsBar(
    bpm: Double,
    timeSignatureNumerator: Int,
    timeSignatureDenominator: Int,
    isSnapEnabled: Boolean,
    gridResolution: Int,
    globalPositionMs: Long,
    isMetronomeEnabled: Boolean,
    isMetronomeSettingsOpen: Boolean,
    metronomeVolume: Float,
    countInBars: Int,
    lastBeatFrame: Long,
    isPlaying: Boolean,
    onAction: (StudioAction) -> Unit
) {
    val position = remember(globalPositionMs, bpm, timeSignatureNumerator, timeSignatureDenominator) {
        MusicalTimeConverter.msToPosition(
            globalPositionMs, bpm, timeSignatureNumerator, timeSignatureDenominator
        )
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    NjBg.copy(alpha = 0.6f),
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Time signature picker -- cycle through presets on tap
            val currentSig = timeSignatureNumerator to timeSignatureDenominator
            NjButton(
                text = "$timeSignatureNumerator/$timeSignatureDenominator",
                onClick = {
                    val idx = TIME_SIGNATURE_PRESETS.indexOf(currentSig)
                    val next = TIME_SIGNATURE_PRESETS[(idx + 1) % TIME_SIGNATURE_PRESETS.size]
                    onAction(StudioAction.SetTimeSignature(next.first, next.second))
                },
                textColor = NjAmber.copy(alpha = 0.8f)
            )

            // BPM with +/- buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                NjButton(
                    text = "-",
                    onClick = { onAction(StudioAction.SetBpm(bpm - 1.0)) },
                    textColor = NjAmber.copy(alpha = 0.7f)
                )
                Text(
                    text = "${bpm.toInt()} BPM",
                    style = MaterialTheme.typography.labelMedium,
                    color = NjAmber.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                NjButton(
                    text = "+",
                    onClick = { onAction(StudioAction.SetBpm(bpm + 1.0)) },
                    textColor = NjAmber.copy(alpha = 0.7f)
                )
            }

            // Snap toggle
            NjButton(
                text = "Snap",
                onClick = { onAction(StudioAction.ToggleSnap) },
                isActive = isSnapEnabled,
                ledColor = NjAmber,
                textColor = NjMuted,
            )

            // Metronome + Gear rocker pill
            MetronomeButtonPill(
                isEnabled = isMetronomeEnabled,
                isSettingsOpen = isMetronomeSettingsOpen,
                lastBeatFrame = lastBeatFrame,
                isPlaying = isPlaying,
                onAction = onAction
            )

            // Push position to the right
            Spacer(Modifier.weight(1f))

            // Position readout
            Text(
                text = position.format(),
                style = MaterialTheme.typography.labelMedium,
                color = NjMuted.copy(alpha = 0.7f)
            )
        }

        // Metronome settings drawer
        androidx.compose.animation.AnimatedVisibility(
            visible = isMetronomeSettingsOpen,
            enter = androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.shrinkVertically()
        ) {
            MetronomeSettingsDrawer(
                volume = metronomeVolume,
                countInBars = countInBars,
                onAction = onAction
            )
        }
    }
}

/** Metronome + Gear rocker pill pair (matches Loop/Clear pattern). */
@Composable
private fun MetronomeButtonPill(
    isEnabled: Boolean,
    isSettingsOpen: Boolean,
    lastBeatFrame: Long,
    isPlaying: Boolean,
    onAction: (StudioAction) -> Unit
) {
    // Beat pulse animation: scale up briefly when a new beat is detected
    val beatScale = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(lastBeatFrame) {
        if (lastBeatFrame >= 0 && isEnabled && isPlaying) {
            beatScale.snapTo(1.3f)
            beatScale.animateTo(
                1f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 150,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            )
        }
    }

    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NjButton(
            text = "Met",
            onClick = { onAction(StudioAction.ToggleMetronome) },
            isActive = isEnabled,
            ledColor = NjMetronomeLed,
            textColor = NjMuted,
            ledScale = beatScale.value
        )

        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(NjOutline)
        )

        NjButton(
            text = "Cfg",
            icon = Icons.Filled.Settings,
            onClick = { onAction(StudioAction.ToggleMetronomeSettings) },
            isActive = isSettingsOpen,
            ledColor = NjMuted2,
            activeGlow = false
        )
    }
}

/** Compact settings drawer for metronome: volume knob + count-in selector. */
@Composable
private fun MetronomeSettingsDrawer(
    volume: Float,
    countInBars: Int,
    onAction: (StudioAction) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                NjBg.copy(alpha = 0.8f),
                RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Volume knob
        com.example.nightjar.ui.components.NjKnob(
            value = volume,
            onValueChange = { onAction(StudioAction.SetMetronomeVolume(it)) },
            knobSize = 36.dp,
            label = "Vol"
        )

        // Count-in selector: cycle through 0/1/2/4
        val countInOptions = listOf(0, 1, 2, 4)
        NjButton(
            text = if (countInBars == 0) "No CI" else "${countInBars} Bar",
            onClick = {
                val idx = countInOptions.indexOf(countInBars)
                val next = countInOptions[(idx + 1) % countInOptions.size]
                onAction(StudioAction.SetCountInBars(next))
            },
            textColor = NjMetronomeLed.copy(alpha = 0.8f)
        )

        Text(
            text = "Count-In",
            style = MaterialTheme.typography.labelSmall,
            color = NjMuted
        )
    }
}
