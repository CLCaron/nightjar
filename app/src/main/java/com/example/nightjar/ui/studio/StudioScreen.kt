package com.example.nightjar.ui.studio

import com.example.nightjar.audio.AudioLatencyEstimator
import com.example.nightjar.ui.components.NjSectionTitle
import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.nightjar.ui.components.NjScrubber
import com.example.nightjar.ui.components.NjTopBar
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjStudioAccent
import com.example.nightjar.ui.theme.NjStudioBg
import com.example.nightjar.ui.theme.NjRecordCoral
import com.example.nightjar.ui.theme.NjStudioGreen
import com.example.nightjar.ui.theme.NjStudioOutline
import com.example.nightjar.ui.theme.NjStudioWaveform
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
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
    onBack: () -> Unit
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

    LaunchedEffect(Unit) {
        vm.effects.collectLatest { effect ->
            when (effect) {
                is StudioEffect.NavigateBack -> onBack()
                is StudioEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
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
        containerColor = NjStudioBg,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            if (!state.isLoading && state.errorMessage == null && !state.isRecording) {
                FloatingActionButton(
                    onClick = { vm.onAction(StudioAction.ShowAddTrackSheet) },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                ) {
                    Text("+", style = MaterialTheme.typography.titleLarge)
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
                title = state.ideaTitle.ifBlank { "Studio" },
                onBack = onBack,
                trailing = {
                    IconButton(
                        onClick = { vm.onAction(StudioAction.ShowLatencySetup) }
                    ) {
                        Text(
                            "Setup",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
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

            if (state.isRecording) {
                OverdubRecordingBar(
                    elapsedMs = state.recordingElapsedMs,
                    onStop = { vm.onAction(StudioAction.StopRecording) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NjSectionTitle("Timeline")

                if (!state.isLoading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Loop + Clear rocker pill
                        if (state.tracks.isNotEmpty()) {
                            Row(
                                modifier = Modifier.height(IntrinsicSize.Min),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                NjStudioButton(
                                    text = "Loop",
                                    icon = Icons.Outlined.Repeat,
                                    onClick = { vm.onAction(StudioAction.ToggleLoop) },
                                    isActive = state.isLoopEnabled,
                                    ledColor = NjStudioAccent,
                                )

                                Box(
                                    Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(NjStudioOutline)
                                )

                                NjStudioButton(
                                    text = "Clear",
                                    icon = Icons.Outlined.Close,
                                    onClick = {
                                        if (state.hasLoopRegion) {
                                            vm.onAction(StudioAction.ClearLoopRegion)
                                        }
                                    },
                                    isActive = !state.hasLoopRegion,
                                    ledColor = NjMuted2,
                                    activeGlow = false,
                                )
                            }
                        }

                        // Record button — coral LED, always enabled
                        NjStudioButton(
                            text = "Rec",
                            icon = Icons.Outlined.FiberManualRecord,
                            onClick = {
                                if (state.isRecording) {
                                    vm.onAction(StudioAction.StopRecording)
                                } else {
                                    vm.onAction(StudioAction.StartRecording)
                                }
                            },
                            isActive = state.isRecording,
                            ledColor = NjRecordCoral,
                        )

                        // Play / Pause
                        if (state.tracks.isNotEmpty() && !state.isRecording) {
                            NjStudioButton(
                                text = if (state.isPlaying) "Pause" else "Play",
                                icon = Icons.Outlined.PlayArrow,
                                onClick = {
                                    if (state.isPlaying) {
                                        vm.onAction(StudioAction.Pause)
                                    } else {
                                        vm.onAction(StudioAction.Play)
                                    }
                                },
                                isActive = state.isPlaying,
                                ledColor = NjStudioGreen,
                            )
                        }
                    }
                }
            }

            if (state.tracks.isEmpty()) {
                TimelinePlaceholder("No tracks yet.")
            } else {
                val displayPositionMs =
                    if (isScrubbing) scrubMs else state.globalPositionMs

                TimelinePanel(
                    tracks = state.tracks,
                    globalPositionMs = displayPositionMs,
                    totalDurationMs = state.totalDurationMs,
                    msPerDp = state.msPerDp,
                    isPlaying = state.isPlaying,
                    dragState = state.dragState,
                    trimState = state.trimState,
                    loopStartMs = state.loopStartMs,
                    loopEndMs = state.loopEndMs,
                    isLoopEnabled = state.isLoopEnabled,
                    expandedTrackIds = state.expandedTrackIds,
                    soloedTrackIds = state.soloedTrackIds,
                    armedTrackId = state.armedTrackId,
                    trackTakes = state.trackTakes,
                    expandedTakeTrackIds = state.expandedTakeTrackIds,
                    expandedTakeDrawerIds = state.expandedTakeDrawerIds,
                    getAudioFile = vm::getAudioFile,
                    onAction = vm::onAction
                )

                NjScrubber(
                    positionMs = scrubMs,
                    durationMs = state.totalDurationMs,
                    onScrub = { newMs ->
                        isScrubbing = true
                        scrubMs = newMs
                    },
                    onScrubFinished = { finalMs ->
                        isScrubbing = false
                        vm.onAction(StudioAction.SeekFinished(finalMs))
                    },
                    activeColor = NjStudioAccent.copy(alpha = 0.6f),
                    inactiveColor = NjStudioWaveform.copy(alpha = 0.15f),
                    thumbColor = NjStudioAccent
                )
            }

            Spacer(Modifier.height(80.dp))
        }
    }

    if (state.showAddTrackSheet) {
        AddTrackBottomSheet(
            onSelect = { type -> vm.onAction(StudioAction.SelectNewTrackType(type)) },
            onDismiss = { vm.onAction(StudioAction.DismissAddTrackSheet) }
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
            onOffsetChange = { vm.onAction(StudioAction.SetManualOffset(it)) },
            onClearOffset = { vm.onAction(StudioAction.ClearManualOffset) },
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
        val takeName = state.trackTakes.values.flatten()
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
private fun LatencySetupDialog(
    diagnostics: AudioLatencyEstimator.LatencyDiagnostics?,
    manualOffsetMs: Long,
    onOffsetChange: (Long) -> Unit,
    onClearOffset: () -> Unit,
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
                        color = NjStudioAccent
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
                        color = if (manualOffsetMs != 0L) NjStudioAccent
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    Text(
                        text = "If overdubs sound late, drag left (negative)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
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
            color = NjStudioWaveform.copy(alpha = 0.15f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = trackHeight
        )

        // Center tick mark
        drawLine(
            color = NjStudioWaveform.copy(alpha = 0.3f),
            start = Offset(centerX, centerY - 6.dp.toPx()),
            end = Offset(centerX, centerY + 6.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )

        // Gold fill from center to thumb
        if (thumbX != centerX) {
            val fillStart = minOf(centerX, thumbX)
            val fillEnd = maxOf(centerX, thumbX)
            drawLine(
                color = NjStudioAccent.copy(alpha = 0.6f),
                start = Offset(fillStart, centerY),
                end = Offset(fillEnd, centerY),
                strokeWidth = trackHeight
            )
        }

        // Thumb
        drawCircle(
            color = NjStudioAccent,
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
