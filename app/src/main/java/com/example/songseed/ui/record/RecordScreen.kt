package com.example.songseed.ui.record

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun RecordScreen(
    onOpenLibrary: () -> Unit,
    onOpenWorkspace: (Long) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val vm: RecordViewModel = viewModel()

    // UI reads state from VM
    val isRecording by vm.isRecording.collectAsState()
    val lastSavedFileName by vm.lastSavedFileName.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()

    // Permission is still UI-owned (because request launcher is UI)
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

    // Handle ViewModel events (navigation, messages)
    LaunchedEffect(Unit) {
        vm.events.collectLatest { event ->
            when (event) {
                is RecordEvent.OpenWorkspace -> onOpenWorkspace(event.ideaId)
                is RecordEvent.ShowError -> {
                    // We already expose errorMessage StateFlow, so no-op is fine.
                    // You could also use a Snackbar here if you want.
                }
            }
        }
    }

    // Safety: stop recording if app goes background
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && isRecording) {
                vm.stopForBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (isRecording) vm.stopForBackground()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Song Seed", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (!hasMicPermission) {
            Text("Mic permission is required to record.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Enable microphone")
            }
        } else {
            Button(
                onClick = { if (!isRecording) vm.startRecording() else vm.stopAndSave() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(if (isRecording) "Stop & Save" else "Record")
            }

            Spacer(Modifier.height(12.dp))
            Text(if (isRecording) "Recording…" else "Ready")

            lastSavedFileName?.let {
                Spacer(Modifier.height(12.dp))
                Text("Last saved file: $it", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))

            OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
                Text("Open Library")
            }
        }

        errorMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
