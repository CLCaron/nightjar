package com.example.nightjar.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.nightjar.audio.CallbackClockMapper
import com.example.nightjar.ui.components.NjButton

/** Phase-one route evidence. Acoustic correction stays gated until device proof. */
@Composable
fun AudioRouteSettings(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    var showDetails by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onAction(SettingsAction.CheckTiming)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onAction(SettingsAction.StopTimingCheck)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            onAction(SettingsAction.StopTimingCheck)
        }
    }
    AlertDialog(
        onDismissRequest = { onAction(SettingsAction.CloseAudioSync) },
        title = { Text("Audio Sync") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Listening through: ${state.routes.outputName}")
                Text("Recording with: ${state.routes.inputName}")
                Text(state.timingMessage)
                Text("You can record without calibration. Microphone changes apply to the next recording.")
                Text("The check plays short sounds for about 15 seconds. Use a quiet room and a comfortable listening volume.")
                Text("With the phone speaker, keep the phone uncovered. With earbuds and the phone microphone, hold one removed earbud a few centimetres from the phone. Keep its top and bottom edges uncovered; try the other edge if the sounds are not detected. With a headset microphone, place the sound outlet close to that microphone.")
                if (!state.checkingTiming) state.audioBusyReason?.let { Text(it) }
                NjButton(text = "Check", caption = "CHECK", enabled = !state.audioBusy && !state.checkingTiming,
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            onAction(SettingsAction.CheckTiming)
                        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    })
                NjButton(text = "Stop", caption = "STOP", enabled = state.checkingTiming,
                    onClick = { onAction(SettingsAction.StopTimingCheck) })
                Text("MICROPHONE", style = MaterialTheme.typography.labelMedium)
                state.microphones.forEach { mic ->
                    NjButton(text = mic.label, caption = "MICROPHONE", modifier = Modifier.fillMaxWidth(),
                        isActive = mic.key == state.selectedMicrophone, enabled = !state.audioBusy && !state.checkingTiming,
                        onClick = { onAction(SettingsAction.SelectMicrophone(mic.key)) })
                }
                if (state.microphones.none { it.key == state.selectedMicrophone }) {
                    Text("Selected microphone disconnected. Choose an available microphone to record.")
                }
                state.audioError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                NjButton(text = "Details", caption = "DETAILS", isActive = showDetails,
                    onClick = { showDetails = !showDetails })
                if (showDetails) {
                    val evidence = state.routes.evidence
                    Text("Stream details", style = MaterialTheme.typography.labelMedium)
                    Text("Input: ${evidence.input.sampleRate} Hz, ${evidence.input.channels} channels, " +
                        "device ${evidence.input.deviceId}, epoch ${evidence.input.epoch}")
                    Text("Output: ${evidence.output.sampleRate} Hz, ${evidence.output.channels} channels, " +
                        "device ${evidence.output.deviceId}, epoch ${evidence.output.epoch}")
                    CallbackClockMapper.estimate(evidence.input, evidence.output)?.let {
                        Text("Callback uncertainty bound: %.2f ms. This is not an acoustic measurement.".format(it.uncertaintyMs))
                    }
                    Text("Dropped input frames: ${evidence.input.droppedFrames}")
                }
            }
        },
        confirmButton = {
            NjButton(text = "Done", caption = "DONE", onClick = { onAction(SettingsAction.CloseAudioSync) })
        }
    )
}
