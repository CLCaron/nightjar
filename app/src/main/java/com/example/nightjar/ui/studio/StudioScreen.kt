package com.example.nightjar.ui.studio

import com.example.nightjar.audio.AudioLatencyEstimator
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SkipPrevious
import com.example.nightjar.ui.components.NjIcons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Repeat
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
    var rulerOffsetPx by remember { mutableFloatStateOf(0f) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableLongStateOf(0L) }

    // Horizontal scroll for the timeline ruler + track lanes. Hoisted so the
    // pinned ruler overlay can share it with the in-flow ruler/tracks.
    val timelineHScrollState = rememberScrollState()

    // Playhead auto-follow state — owned here so the pinned ruler overlay can
    // render the same follow-line as the in-flow ruler.
    var isFollowActive by remember { mutableStateOf(false) }
    var isFollowEligible by remember { mutableStateOf(false) }

    val density = LocalDensity.current

    // Shared timeline width: the scrollable content extends past the cursor so
    // tracks can be dragged out beyond their current end. Both the in-flow and
    // pinned rulers must use the same width to keep horizontal scroll in sync.
    val timelineWidthDp = remember(
        state.totalDurationMs, state.cursorPositionMs, state.msPerDp
    ) {
        val contentDp = (state.totalDurationMs / state.msPerDp).dp
        val cursorDp = (state.cursorPositionMs / state.msPerDp).dp
        maxOf(contentDp, cursorDp) + 600.dp
    }

    // Animated header column width — single source of truth shared between
    // TimelinePanel and the pinned ruler so both stay in sync when toggled.
    val columnWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (state.headersCollapsedMode) COLOR_TAB_WIDTH else HEADER_WIDTH,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.65f,
            stiffness = 200f
        ),
        label = "columnWidth"
    )

    LaunchedEffect(state.globalPositionMs, isScrubbing, state.totalDurationMs) {
        if (!isScrubbing) {
            scrubMs = state.globalPositionMs.coerceIn(
                0L,
                state.totalDurationMs.coerceAtLeast(1L)
            )
        }
    }

    // Mark follow-eligible when playback starts; clear on stop.
    LaunchedEffect(state.isPlaying, state.isRecording) {
        if (state.isPlaying || state.isRecording) {
            isFollowEligible = true
        } else {
            isFollowEligible = false
            isFollowActive = false
        }
    }

    // Follow loop: once the playhead reaches the 1/4 viewport mark, lock follow
    // mode and scroll the timeline to keep the playhead anchored there. The
    // playhead becomes a fixed overlay line drawn by both rulers and tracks.
    //
    // The try/finally is critical: if the loop is cancelled mid-iteration
    // (e.g. user taps a clip and onDisengageFollow flips isFollowEligible
    // false), it can race with the body's `isFollowActive = true` write and
    // leave isFollowActive stuck true after the loop dies. The finally block
    // guarantees isFollowActive is cleared no matter how the loop exits.
    val currentPositionMs by rememberUpdatedState(state.globalPositionMs)
    LaunchedEffect(isFollowEligible) {
        if (!isFollowEligible) {
            isFollowActive = false
            return@LaunchedEffect
        }
        try {
            while (true) {
                // Re-check eligibility at the top of every iteration so we
                // don't write isFollowActive=true after a disengage call.
                if (!isFollowEligible) break

                val playheadPx = with(density) {
                    (currentPositionMs / state.msPerDp).dp.toPx()
                }.toInt()
                val viewportStart = timelineHScrollState.value
                val targetOffset = timelineHScrollState.viewportSize / 4
                val playheadScreenPx = playheadPx - viewportStart

                if (!isFollowActive && playheadScreenPx >= targetOffset) {
                    isFollowActive = true
                }

                if (isFollowActive) {
                    val scrollTarget = (playheadPx - targetOffset).coerceAtLeast(0)
                    if (scrollTarget >= timelineHScrollState.maxValue) {
                        // Scroll hit the end — disengage overlay; let the in-content
                        // playhead travel through the remaining visible area.
                        timelineHScrollState.scrollTo(timelineHScrollState.maxValue)
                        isFollowActive = false
                    } else {
                        timelineHScrollState.scrollTo(scrollTarget)
                    }
                }

                kotlinx.coroutines.delay(16L)
            }
        } finally {
            isFollowActive = false
        }
    }

    // Fixed playhead X position when follow mode is engaged. Recomputed when
    // viewport size or column width changes.
    val followLineXPx = if (isFollowActive) {
        with(density) { columnWidth.toPx() + timelineHScrollState.viewportSize / 4f }
    } else 0f

    val onDisengageFollow: () -> Unit = {
        isFollowEligible = false
        isFollowActive = false
    }

    // Position used for cursor/playhead rendering — reflects scrubbing in real
    // time, then snaps back to the playback position when the gesture ends.
    val displayPositionMs = if (isScrubbing) scrubMs else state.globalPositionMs

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
                    showDivider = false,
                    trailing = {
                        NjButton(
                            text = "Setup",
                            onClick = { vm.onAction(StudioAction.ShowLatencySetup) },
                            textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                )

                HardwareGroove(fraction = 0.92f)

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

                androidx.compose.runtime.CompositionLocalProvider(
                    LocalAudioClipLinkage provides state.audioClipLinkage,
                    LocalMidiClipLinkage provides state.midiClipLinkage,
                    LocalDrumClipLinkage provides state.drumClipLinkage,
                    LocalPulseTicks provides state.pulseTicks,
                    LocalSplitMode provides SplitModeUiState(
                        clipId = state.splitModeClipId,
                        positionMs = state.splitPositionMs,
                        valid = state.splitValid
                    )
                ) {
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
                    collapsedHeaderTrackIds = state.collapsedHeaderTrackIds,
                    headersCollapsedMode = state.headersCollapsedMode,
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
                    },
                    scrollState = timelineHScrollState,
                    columnWidth = columnWidth,
                    timelineWidthDp = timelineWidthDp,
                    isFollowActive = isFollowActive,
                    followLineXPx = followLineXPx,
                    onDisengageFollow = onDisengageFollow,
                    // The ruler row is the first child of TimelinePanel's column,
                    // so TimelinePanel's Y position in the scroll column is also
                    // the ruler's Y position.
                    modifier = Modifier.onGloballyPositioned { coords ->
                        rulerOffsetPx = coords.positionInParent().y
                    }
                )
                } // CompositionLocalProvider

                Spacer(Modifier.height(80.dp))
            }

            // Pinned overlays — transport on top, ruler stacked beneath. Both
            // are derived from vertical scroll position so they snap into the
            // viewport just as their in-flow counterparts scroll past the top.
            val isTransportPinned = !state.isLoading
                && transportOffsetPx > 0f
                && scrollState.value.toFloat() >= transportOffsetPx
            val isRulerPinned = !state.isLoading
                && rulerOffsetPx > 0f
                && scrollState.value.toFloat() >= rulerOffsetPx

            if (isTransportPinned || isRulerPinned) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isTransportPinned) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NjBg)
                                .padding(horizontal = 16.dp)
                                .padding(top = 8.dp, bottom = 4.dp)
                        ) {
                            TransportAndControls(
                                state = state,
                                onAction = vm::onAction
                            )
                        }
                    }

                    if (isRulerPinned) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NjBg)
                                .padding(horizontal = 16.dp)
                        ) {
                            TimelineRulerRow(
                                scrollState = timelineHScrollState,
                                columnWidth = columnWidth,
                                headersCollapsedMode = state.headersCollapsedMode,
                                timelineWidthDp = timelineWidthDp,
                                totalDurationMs = state.totalDurationMs,
                                cursorPositionMs = state.cursorPositionMs,
                                globalPositionMs = displayPositionMs,
                                msPerDp = state.msPerDp,
                                bpm = state.bpm,
                                timeSignatureNumerator = state.timeSignatureNumerator,
                                timeSignatureDenominator = state.timeSignatureDenominator,
                                gridResolution = state.gridResolution,
                                loopStartMs = state.loopStartMs,
                                loopEndMs = state.loopEndMs,
                                isLoopEnabled = state.isLoopEnabled,
                                isRecording = state.isRecording,
                                isFollowActive = isFollowActive,
                                followLineXPx = followLineXPx,
                                onDisengageFollow = onDisengageFollow,
                                onScrub = { newMs ->
                                    isScrubbing = true
                                    scrubMs = newMs
                                },
                                onScrubFinished = { finalMs ->
                                    isScrubbing = false
                                    vm.onAction(StudioAction.SeekFinished(finalMs))
                                },
                                onAction = vm::onAction
                            )
                            // Subtle drop shadow so the pinned ruler reads as
                            // floating above the scrolling track lanes.
                            Canvas(
                                Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                            ) {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.3f),
                                            Color.Transparent
                                        )
                                    ),
                                    size = size
                                )
                            }
                        }
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

    // Clip rename dialog (propagates to linked siblings for audio clips).
    if (state.renamingClipId != null) {
        RenameDialog(
            title = "Rename clip",
            currentName = state.renamingClipCurrentName,
            onConfirm = { newName -> vm.onAction(StudioAction.ConfirmRenameClip(newName)) },
            onDismiss = { vm.onAction(StudioAction.DismissRenameClip) }
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val hasTracksAndNotRecording = state.tracks.isNotEmpty() && !state.isRecording

        // --- Responsive transport: wide inlines LCD, narrow uses two rows ---
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxWidth()
        ) {
            val isWide = maxWidth >= 520.dp

            if (isWide) {
                // Wide layout: [Loop|Clear]  [LCD .. Disarm (Delete)]  [Restart Play Rec]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LoopClearPill(hasTracksAndNotRecording, state, onAction)
                    // Center: LCD + Disarm + Delete fill available space
                    StudioStatusLcd(
                        state = state,
                        onAction = onAction,
                        modifier = Modifier.weight(1f)
                    )
                    TransportCluster(hasTracksAndNotRecording, state, onAction)
                }
            } else {
                // Narrow layout: transport row, then LCD row beneath
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LoopClearPill(hasTracksAndNotRecording, state, onAction)
                        Spacer(Modifier.weight(1f))
                        TransportCluster(hasTracksAndNotRecording, state, onAction)
                    }
                    StudioStatusLcd(state = state, onAction = onAction)
                }
            }
        }

        // --- Drawer toggle grip (always visible) ---
        DrawerToggleGrip(
            isOpen = state.isControlsDrawerOpen,
            onClick = { onAction(StudioAction.ToggleControlsDrawer) }
        )

        // --- Controls drawer ---
        androidx.compose.animation.AnimatedVisibility(
            visible = state.isControlsDrawerOpen,
            enter = androidx.compose.animation.expandVertically(
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.75f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                ),
                expandFrom = Alignment.Top
            ) + androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(200, delayMillis = 80)
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(150)
            ) + androidx.compose.animation.shrinkVertically(
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.75f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                )
            )
        ) {
            ControlsDrawer(state = state, onAction = onAction)
        }
    }
}

/** Loop + Clear rocker pill (left side of transport). */
@Composable
private fun LoopClearPill(
    hasTracksAndNotRecording: Boolean,
    state: StudioUiState,
    onAction: (StudioAction) -> Unit
) {
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
}

/** Transport cluster (right side): Restart, Play, Rec. */
@Composable
private fun TransportCluster(
    hasTracksAndNotRecording: Boolean,
    state: StudioUiState,
    onAction: (StudioAction) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NjButton(
            text = "Restart",
            icon = Icons.Filled.SkipPrevious,
            onClick = { if (hasTracksAndNotRecording) onAction(StudioAction.RestartPlayback) },
            modifier = Modifier.alpha(if (hasTracksAndNotRecording) 1f else 0.35f),
            textColor = NjLedGreen.copy(alpha = 0.5f),
        )

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

/**
 * Routed hardware groove -- two-line channel (dark shadow on top, light catch
 * on bottom) matching the Record screen's faceplate aesthetic. Horizontal
 * gradient fades at the edges so it doesn't feel stamped on.
 *
 * When used standalone (e.g. below the title bar), wrap in a centered container
 * and pass a width fraction. When used inside a weighted Row (e.g. flanking a
 * button), pass `fraction = 1f` and let the modifier control width.
 *
 * @param fraction Width fraction of the canvas relative to its container.
 */
@Composable
private fun HardwareGroove(
    modifier: Modifier = Modifier,
    fraction: Float = 0.7f
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth(fraction).height(2.dp)) {
            val darkBrush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.50f),
                    Color.Black.copy(alpha = 0.50f),
                    Color.Transparent
                )
            )
            val lightBrush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.07f),
                    Color.White.copy(alpha = 0.07f),
                    Color.Transparent
                )
            )
            drawLine(darkBrush, Offset(0f, 0f), Offset(size.width, 0f), size.height / 2)
            drawLine(lightBrush, Offset(0f, size.height), Offset(size.width, size.height), size.height / 2)
        }
    }
}

/**
 * Drawer toggle: a small NjButton with a chevron icon that latches when the
 * drawer is open, with a hardware groove running beneath it.
 */
@Composable
private fun DrawerToggleGrip(
    isOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NjButton(
            text = "",
            icon = if (isOpen) Icons.Filled.KeyboardArrowUp
                   else Icons.Filled.KeyboardArrowDown,
            onClick = onClick,
            isActive = isOpen,
            ledColor = NjAmber,
            activeGlow = false,
        )

        Spacer(Modifier.height(6.dp))
        HardwareGroove(fraction = 0.92f)
    }
}

/**
 * Controls drawer: timing, metronome, and grid controls.
 *
 * Wide (landscape): single row -- [4/4] [-BPM+] [Met Vol CI] ... [Snap 1/16]
 * Narrow: two rows -- timing+grid on row 1, metronome on row 2.
 * Very narrow (<340dp): three rows -- timing, grid, metronome each on own row.
 */
@Composable
private fun ControlsDrawer(
    state: StudioUiState,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                NjBg.copy(alpha = 0.6f),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxWidth()
        ) {
            when {
                // Wide (landscape): everything on one row
                maxWidth >= 520.dp -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TimingCluster(state, onAction)
                        MetronomeRow(state = state, onAction = onAction)
                        Spacer(Modifier.weight(1f))
                        GridCluster(state, onAction)
                    }
                }
                // Very narrow: split into three rows
                maxWidth < 340.dp -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TimingCluster(state, onAction)
                        GridCluster(state, onAction)
                        MetronomeRow(state = state, onAction = onAction)
                    }
                }
                // Normal portrait: timing+grid row, then metronome row
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TimingCluster(state, onAction)
                            Spacer(Modifier.weight(1f))
                            GridCluster(state, onAction)
                        }
                        MetronomeRow(state = state, onAction = onAction)
                    }
                }
            }
        }

        // Clip action pillrockers: only when a clip is selected, so the
        // drawer doesn't inflate when nothing's selected.
        if (state.expandedClipState != null) {
            ClipActionsCluster(state = state, onAction = onAction)
        }
    }
}

/**
 * Split + Duplicate pillrockers for the currently selected clip.
 * - Split: left half latches the mode; right half confirms (dimmed when no valid line).
 * - Duplicate: left = Unlinked, right = Linked.
 */
@Composable
private fun ClipActionsCluster(
    state: StudioUiState,
    onAction: (StudioAction) -> Unit
) {
    val expanded = state.expandedClipState ?: return
    val clipType = expanded.clipType
    val isSplitMode = state.splitModeClipId == expanded.clipId
    val splitValid = isSplitMode && state.splitValid

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Split pillrocker: Split (latch) | Confirm (dim-inert until valid).
        com.example.nightjar.ui.components.NjPillrocker(
            left = com.example.nightjar.ui.components.PillrockerHalf(
                label = "Split",
                icon = Icons.Filled.ContentCut,
                mode = com.example.nightjar.ui.components.PillrockerHalfMode.Latching,
                isLatched = isSplitMode,
                ledColor = NjAmber,
                onTap = {
                    if (isSplitMode) {
                        onAction(StudioAction.CancelSplit)
                    } else {
                        onAction(StudioAction.StartSplitMode(expanded.clipId, clipType))
                    }
                }
            ),
            right = com.example.nightjar.ui.components.PillrockerHalf(
                label = "Confirm",
                icon = Icons.Filled.Check,
                mode = com.example.nightjar.ui.components.PillrockerHalfMode.Momentary,
                isEnabled = splitValid,
                ledColor = null,
                onTap = { onAction(StudioAction.ConfirmSplit) }
            ),
            modifier = Modifier.weight(1f)
        )

        // Duplicate pillrocker: Unlinked | Linked.
        com.example.nightjar.ui.components.NjPillrocker(
            left = com.example.nightjar.ui.components.PillrockerHalf(
                label = "Copy",
                icon = Icons.Filled.ContentCopy,
                mode = com.example.nightjar.ui.components.PillrockerHalfMode.Momentary,
                onTap = {
                    val action = when (clipType) {
                        "audio" -> StudioAction.DuplicateAudioClip(
                            expanded.trackId, expanded.clipId, linked = false
                        )
                        "midi" -> StudioAction.DuplicateMidiClip(
                            expanded.trackId, expanded.clipId, linked = false
                        )
                        "drum" -> StudioAction.DuplicateClip(
                            expanded.trackId, expanded.clipId, linked = false
                        )
                        else -> null
                    }
                    action?.let { onAction(it) }
                }
            ),
            right = com.example.nightjar.ui.components.PillrockerHalf(
                label = "Link",
                icon = Icons.Filled.Link,
                mode = com.example.nightjar.ui.components.PillrockerHalfMode.Momentary,
                onTap = {
                    val action = when (clipType) {
                        "audio" -> StudioAction.DuplicateAudioClip(
                            expanded.trackId, expanded.clipId, linked = true
                        )
                        "midi" -> StudioAction.DuplicateMidiClip(
                            expanded.trackId, expanded.clipId, linked = true
                        )
                        "drum" -> StudioAction.DuplicateClip(
                            expanded.trackId, expanded.clipId, linked = true
                        )
                        else -> null
                    }
                    action?.let { onAction(it) }
                }
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

/** Time signature button + BPM [-/display/+]. */
@Composable
private fun TimingCluster(
    state: StudioUiState,
    onAction: (StudioAction) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Time signature -- cycle through presets
        val currentSig = state.timeSignatureNumerator to state.timeSignatureDenominator
        NjButton(
            text = "${state.timeSignatureNumerator}/${state.timeSignatureDenominator}",
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
                onClick = { onAction(StudioAction.SetBpm(state.bpm - 1.0)) },
                textColor = NjAmber.copy(alpha = 0.7f)
            )
            Text(
                text = "${state.bpm.toInt()} BPM",
                style = MaterialTheme.typography.labelMedium,
                color = NjAmber.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            NjButton(
                text = "+",
                onClick = { onAction(StudioAction.SetBpm(state.bpm + 1.0)) },
                textColor = NjAmber.copy(alpha = 0.7f)
            )
        }
    }
}

/** Snap toggle + Grid Resolution cycle button. */
@Composable
private fun GridCluster(
    state: StudioUiState,
    onAction: (StudioAction) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NjButton(
            text = "Snap",
            onClick = { onAction(StudioAction.ToggleSnap) },
            isActive = state.isSnapEnabled,
            ledColor = NjAmber,
            textColor = NjMuted,
        )

        // Grid resolution -- cycle through presets
        NjButton(
            text = "1/${state.gridResolution}",
            onClick = {
                val idx = GRID_RESOLUTION_PRESETS.indexOf(state.gridResolution)
                val next = GRID_RESOLUTION_PRESETS[(idx + 1) % GRID_RESOLUTION_PRESETS.size]
                onAction(StudioAction.SetGridResolution(next))
            },
            textColor = NjAmber.copy(alpha = 0.8f)
        )
    }
}

/** Metronome toggle + volume knob + count-in cycle button. */
@Composable
private fun MetronomeRow(
    state: StudioUiState,
    onAction: (StudioAction) -> Unit
) {
    // Beat pulse animation: scale up briefly when a new beat is detected
    val beatScale = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(state.lastBeatFrame) {
        if (state.lastBeatFrame >= 0 && state.isMetronomeEnabled && state.isPlaying) {
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

    val countInOptions = listOf(0, 1, 2, 4)

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NjButton(
            text = "Met",
            onClick = { onAction(StudioAction.ToggleMetronome) },
            isActive = state.isMetronomeEnabled,
            ledColor = NjMetronomeLed,
            textColor = NjMuted,
            ledScale = beatScale.value
        )

        com.example.nightjar.ui.components.NjKnob(
            value = state.metronomeVolume,
            onValueChange = { onAction(StudioAction.SetMetronomeVolume(it)) },
            knobSize = 36.dp,
            label = "Vol"
        )

        NjButton(
            text = if (state.countInBars == 0) "No CI" else "${state.countInBars} Bar",
            onClick = {
                val idx = countInOptions.indexOf(state.countInBars)
                val next = countInOptions[(idx + 1) % countInOptions.size]
                onAction(StudioAction.SetCountInBars(next))
            },
            textColor = NjMetronomeLed.copy(alpha = 0.8f)
        )
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

