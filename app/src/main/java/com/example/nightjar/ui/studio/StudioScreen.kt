package com.example.nightjar.ui.studio

import androidx.compose.material3.ButtonDefaults
import com.example.nightjar.ui.components.NjPrimaryButton
import com.example.nightjar.ui.components.NjSectionTitle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.nightjar.ui.components.NjScrubber
import com.example.nightjar.ui.components.NjTopBar
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
                vm.onAction(StudioAction.StopOverdubRecording)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
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
                onBack = onBack
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
                    onStop = { vm.onAction(StudioAction.StopOverdubRecording) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NjSectionTitle("Timeline")

                if (state.tracks.isNotEmpty() && !state.isRecording) {
                    NjPrimaryButton(
                        text = if (state.isPlaying) "Pause" else "Play",
                        onClick = {
                            if (state.isPlaying) {
                                vm.onAction(StudioAction.Pause)
                            } else {
                                vm.onAction(StudioAction.Play)
                            }
                        },
                        fullWidth = false,
                        minHeight = 36.dp,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    )
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
                    }
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
}
