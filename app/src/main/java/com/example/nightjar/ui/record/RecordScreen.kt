package com.example.nightjar.ui.record

import NjPrimaryButton
import NjSecondaryButton
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.flow.collectLatest

@Composable
fun RecordScreen(
    onOpenLibrary: () -> Unit,
    onOpenWorkspace: (Long) -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val vm: RecordViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val isRecording by rememberUpdatedState(state.isRecording)

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    LaunchedEffect(Unit) {
        vm.effects.collectLatest { effect ->
            when (effect) {
                is RecordEffect.OpenWorkspace -> onOpenWorkspace(effect.ideaId)
                is RecordEffect.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = effect.message,
                        withDismissAction = true
                    )
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && isRecording) {
                vm.onAction(RecordAction.StopForBackground)
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (state.isRecording) vm.onAction(RecordAction.StopForBackground)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Nightjar",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Capture now. Write later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f)
            )

            Spacer(Modifier.height(28.dp))

            if (!hasMicPermission) {
                Text(
                    text = "Microphone permission is required to record.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f)
                )

                Spacer(Modifier.height(14.dp))

                NjPrimaryButton(
                    text = "Enable microphone",
                    onClick = { requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    minHeight = 56.dp
                )
            } else {
                NjPrimaryButton(
                    text = if (state.isRecording) "Stop & Save" else "Record",
                    onClick = {
                        if (!state.isRecording) vm.onAction(RecordAction.StartRecording)
                        else vm.onAction(RecordAction.StopAndSave)
                    },
                    minHeight = 72.dp
                )

                Spacer(Modifier.height(14.dp))

                Text(
                    text = if (state.isRecording) "Recording…" else "Ready",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )

                state.lastSavedFileName?.let { file ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Last saved: $file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }

                Spacer(Modifier.height(26.dp))

                NjSecondaryButton(
                    text = "Open Library",
                    onClick = onOpenLibrary,
                    minHeight = 56.dp
                )
            }
        }
    }
}
