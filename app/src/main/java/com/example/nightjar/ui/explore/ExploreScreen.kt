package com.example.nightjar.ui.explore

import NjPrimaryButton
import NjSectionTitle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.nightjar.ui.components.NjScrubber
import com.example.nightjar.ui.components.NjTopBar
import com.example.nightjar.ui.components.NjWaveform
import kotlinx.coroutines.flow.collectLatest
import java.io.File

@Composable
fun ExploreScreen(
    ideaId: Long,
    onBack: () -> Unit
) {
    val vm: ExploreViewModel = hiltViewModel()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(ideaId) {
        vm.onAction(ExploreAction.Load(ideaId))
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
            vm.onAction(ExploreAction.MicPermissionGranted)
        }
    }

    LaunchedEffect(Unit) {
        vm.effects.collectLatest { effect ->
            when (effect) {
                is ExploreEffect.NavigateBack -> onBack()
                is ExploreEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
                is ExploreEffect.RequestMicPermission -> {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasPermission) {
                        vm.onAction(ExploreAction.MicPermissionGranted)
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
                vm.onAction(ExploreAction.StopOverdubRecording)
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
                    onClick = { vm.onAction(ExploreAction.ShowAddTrackSheet) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
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
                title = state.ideaTitle.ifBlank { "Explore" },
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
                    onStop = { vm.onAction(ExploreAction.StopOverdubRecording) }
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
                                vm.onAction(ExploreAction.Pause)
                            } else {
                                vm.onAction(ExploreAction.Play)
                            }
                        },
                        fullWidth = false,
                        minHeight = 36.dp
                    )
                }
            }

            if (state.tracks.isEmpty()) {
                TimelinePlaceholder("No tracks yet.")
            } else {
                state.tracks.forEach { track ->
                    val effectiveDuration =
                        track.durationMs - track.trimStartMs - track.trimEndMs
                    val displayPositionMs =
                        if (isScrubbing) scrubMs else state.globalPositionMs

                    val progressFraction = if (effectiveDuration <= 0L) {
                        -1f
                    } else {
                        val localMs = displayPositionMs - track.offsetMs
                        when {
                            localMs < 0 -> -1f
                            localMs >= effectiveDuration -> 1f
                            else -> localMs.toFloat() / effectiveDuration.toFloat()
                        }
                    }

                    TrackRow(
                        displayName = track.displayName,
                        durationMs = track.durationMs,
                        offsetMs = track.offsetMs,
                        isMuted = track.isMuted,
                        audioFile = vm.getAudioFile(track.audioFileName),
                        progressFraction = progressFraction
                    )
                }

                NjScrubber(
                    positionMs = scrubMs,
                    durationMs = state.totalDurationMs,
                    onScrub = { newMs ->
                        isScrubbing = true
                        scrubMs = newMs
                    },
                    onScrubFinished = { finalMs ->
                        isScrubbing = false
                        vm.onAction(ExploreAction.SeekFinished(finalMs))
                    }
                )
            }

            Spacer(Modifier.height(80.dp))
        }
    }

    if (state.showAddTrackSheet) {
        AddTrackBottomSheet(
            onSelect = { type -> vm.onAction(ExploreAction.SelectNewTrackType(type)) },
            onDismiss = { vm.onAction(ExploreAction.DismissAddTrackSheet) }
        )
    }
}

@Composable
private fun TimelinePlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun TrackRow(
    displayName: String,
    durationMs: Long,
    offsetMs: Long,
    isMuted: Boolean,
    audioFile: File,
    progressFraction: Float = -1f
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isMuted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isMuted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isMuted) {
                    Text(
                        text = "Muted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        NjWaveform(
            audioFile = audioFile,
            barColor = if (isMuted) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            height = 40.dp,
            progressFraction = progressFraction
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
