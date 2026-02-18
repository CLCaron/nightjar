package com.example.nightjar.ui.record

import com.example.nightjar.ui.components.NjPrimaryButton
import com.example.nightjar.ui.components.NjSecondaryButton
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest

/**
 * Record screen — the app's landing page.
 *
 * Presents a single prominent button to start/stop recording. On save the
 * recording is persisted as an [IdeaEntity] and the user is navigated to
 * the Overview. Handles microphone permission requests and gracefully
 * saves when the app is backgrounded mid-recording.
 */
@Composable
fun RecordScreen(
    onOpenLibrary: () -> Unit,
    onOpenOverview: (Long) -> Unit
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
                is RecordEffect.OpenOverview -> onOpenOverview(effect.ideaId)
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
                RecordButton(
                    isRecording = state.isRecording,
                    onClick = {
                        if (!state.isRecording) vm.onAction(RecordAction.StartRecording)
                        else vm.onAction(RecordAction.StopAndSave)
                    }
                )

                Spacer(Modifier.height(14.dp))

                Text(
                    text = if (state.isRecording) "Recording…" else "Record",
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

/**
 * Crescent-moon record button. Idle state shows a subtle crescent carved
 * from the gold circle. On tap the shadow slides away (crescent → full
 * moon), then the circle shrinks into a rounded-square stop icon. The
 * outer ring pulses gently while recording.
 */
@Composable
private fun RecordButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.tertiary
    val bg = MaterialTheme.colorScheme.background
    val ring = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)

    // Crescent shadow offset — slides to 0 when recording (full moon)
    val shadowOffset by animateFloatAsState(
        targetValue = if (isRecording) 0f else 0.22f,
        animationSpec = tween(durationMillis = 350),
        label = "shadowOffset"
    )

    // Shadow radius shrinks to 0 when recording so it fully disappears
    val shadowRadiusFraction by animateFloatAsState(
        targetValue = if (isRecording) 0f else 1f,
        animationSpec = tween(durationMillis = 350),
        label = "shadowRadius"
    )

    // Inner shape corner radius: 50% (circle) → 26% (rounded square)
    val cornerFraction by animateFloatAsState(
        targetValue = if (isRecording) 0.26f else 0.50f,
        animationSpec = tween(durationMillis = 250, delayMillis = 150),
        label = "cornerFraction"
    )

    // Inner shape size: shrinks when recording (stop icon)
    val innerSizeFraction by animateFloatAsState(
        targetValue = if (isRecording) 0.38f else 0.72f,
        animationSpec = tween(durationMillis = 250, delayMillis = 150),
        label = "innerSize"
    )

    // Pulse the outer ring while recording
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val ringAlpha by pulseTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = if (isRecording) 0.5f else 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    Box(
        modifier = modifier
            .size(80.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 2.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            val moonRadius = size.minDimension * 0.72f / 2f

            // Outer ring (halo)
            drawCircle(
                color = if (isRecording) accent.copy(alpha = ringAlpha) else ring,
                radius = (size.minDimension / 2f) - (strokeWidth / 2f),
                style = Stroke(width = strokeWidth)
            )

            // Gold moon circle
            drawCircle(
                color = accent,
                radius = moonRadius,
                center = center
            )

            // Shadow circle carving the crescent — offset to upper-right
            // When recording, offset and radius animate to 0 (full moon)
            if (shadowRadiusFraction > 0.001f) {
                val shadowR = moonRadius * 0.92f * shadowRadiusFraction
                val offsetPx = moonRadius * shadowOffset
                drawCircle(
                    color = bg,
                    radius = shadowR,
                    center = Offset(
                        center.x + offsetPx,
                        center.y - offsetPx * 0.6f
                    )
                )
            }

            // When recording, draw the stop icon (rounded square) over the full moon
            if (isRecording) {
                val innerSize = size.minDimension * innerSizeFraction
                val cr = innerSize * cornerFraction
                val topLeft = Offset(
                    (size.width - innerSize) / 2f,
                    (size.height - innerSize) / 2f
                )
                drawRoundRect(
                    color = bg,
                    topLeft = topLeft,
                    size = Size(innerSize, innerSize),
                    cornerRadius = CornerRadius(cr, cr)
                )
            }
        }
    }
}
