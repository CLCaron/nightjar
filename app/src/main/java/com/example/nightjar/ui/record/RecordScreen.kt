package com.example.nightjar.ui.record

import com.example.nightjar.ui.components.NjLiveWaveform
import com.example.nightjar.ui.components.NjStarfield
import com.example.nightjar.ui.components.NjWaveform
import com.example.nightjar.ui.components.collectIsPressedWithMinDuration
import com.example.nightjar.ui.studio.NjStudioButton
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjBg
import com.example.nightjar.ui.theme.NjRecordCoral
import com.example.nightjar.ui.theme.NjSurface2
import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
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
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest

/**
 * Record screen -- the app's landing page.
 *
 * Centered around a hardware-style circular record button with a coral
 * LED indicator. Idle shows a coral circle; recording sinks the button
 * in, morphs the indicator to a rounded-square stop icon, and pulses
 * a coral ring. After stopping, the user sees a waveform preview with
 * options to open Overview, open Studio, or start a new recording.
 * All secondary actions use hardware-style push buttons (NjStudioButton).
 */
@Composable
fun RecordScreen(
    onOpenLibrary: () -> Unit,
    onOpenOverview: (Long) -> Unit,
    onOpenStudio: (Long) -> Unit
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
                is RecordEffect.OpenStudio -> onOpenStudio(effect.ideaId)
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            NjStarfield(
                modifier = Modifier.fillMaxSize(),
                isRecording = state.isRecording
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
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

            Spacer(Modifier.height(28.dp))

            if (!hasMicPermission) {
                Text(
                    text = "Microphone permission is required to record.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f)
                )

                Spacer(Modifier.height(14.dp))

                NjStudioButton(
                    text = "Enable microphone",
                    onClick = { requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    isActive = true,
                    ledColor = NjRecordCoral,
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            } else {
                HardwareRecordButton(
                    isRecording = state.isRecording,
                    onClick = {
                        if (!state.isRecording) vm.onAction(RecordAction.StartRecording)
                        else vm.onAction(RecordAction.StopAndSave)
                    }
                )

                Spacer(Modifier.height(14.dp))

                val postRecording = state.postRecording
                if (state.isRecording) {
                    Text(
                        text = "Recording\u2026",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )

                    if (state.liveAmplitudes.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))

                        NjLiveWaveform(
                            amplitudes = state.liveAmplitudes,
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .padding(horizontal = 8.dp),
                            height = 48.dp
                        )
                    }
                } else if (!state.isRecording && postRecording == null &&
                    state.liveAmplitudes.isNotEmpty()
                ) {
                    // Transitional state: recording just stopped, DB save in progress.
                    // Show frozen live waveform as a visual bridge.
                    Text(
                        text = "Saving\u2026",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )

                    Spacer(Modifier.height(16.dp))

                    NjLiveWaveform(
                        amplitudes = state.liveAmplitudes,
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .padding(horizontal = 8.dp),
                        height = 48.dp
                    )
                } else if (postRecording != null) {
                    Text(
                        text = "Captured audio",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )

                    Spacer(Modifier.height(16.dp))

                    NjWaveform(
                        audioFile = postRecording.audioFile,
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .padding(horizontal = 8.dp),
                        height = 48.dp
                    )

                    Spacer(Modifier.height(20.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NjStudioButton(
                            text = "Done",
                            onClick = { vm.onAction(RecordAction.GoToOverview) },
                            isActive = true,
                            ledColor = NjAccent
                        )
                        NjStudioButton(
                            text = "Open in Studio",
                            onClick = { vm.onAction(RecordAction.GoToStudio) }
                        )
                    }
                } else {
                    Text(
                        text = "Record",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )

                    Spacer(Modifier.height(20.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NjStudioButton(
                            text = "Write",
                            onClick = { vm.onAction(RecordAction.CreateWriteIdea) }
                        )
                        NjStudioButton(
                            text = "Studio",
                            onClick = { vm.onAction(RecordAction.CreateStudioIdea) }
                        )
                    }
                }

                Spacer(Modifier.height(26.dp))

                NjStudioButton(
                    text = "Open Library",
                    onClick = onOpenLibrary,
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
            }
        }
    }
}

/**
 * Hardware-style circular record button with beveled edges.
 *
 * Idle: dark body with raised bevel and a coral filled circle indicator.
 * A coral ring breathes slowly (5s cycle). Recording: body sinks in
 * (bevel inverts), indicator morphs to a coral rounded-square stop icon,
 * ring pulses faster (900ms), and a coral radial glow appears behind
 * the button. Haptic feedback on press (CONTEXT_CLICK) and release
 * (CLOCK_TICK).
 */
@Composable
private fun HardwareRecordButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val fingerDown by interactionSource.collectIsPressedWithMinDuration()
    val view = LocalView.current

    val visuallyPressed = isRecording || fingerDown
    val bodyColor = if (visuallyPressed) Color(0xFF12101A) else NjSurface2

    // Haptics on raw press/release events
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                is PressInteraction.Release ->
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    // Body + indicator scale down when pressed -- physical sink-in feel
    val pressScale by animateFloatAsState(
        targetValue = if (visuallyPressed) 0.93f else 1.0f,
        animationSpec = tween(durationMillis = 80),
        label = "pressScale"
    )

    // Inner shape corner radius: 50% (circle) to 26% (rounded square)
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

    // Ring breathing -- slow when idle, faster pulse when recording
    val ringTransition = rememberInfiniteTransition(label = "ring")
    val ringAlpha by ringTransition.animateFloat(
        initialValue = if (isRecording) 0.15f else 0.10f,
        targetValue = if (isRecording) 0.5f else 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isRecording) 900 else 5_000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    Box(
        modifier = modifier
            .size(92.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.minDimension / 2f
            val bevelStroke = 1.5f.dp.toPx()
            val ringStroke = 2.dp.toPx()
            val bodyRadius = outerRadius * 0.82f * pressScale

            // Recording glow -- coral radial gradient behind body
            if (isRecording) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NjRecordCoral.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = outerRadius * 1.3f
                    ),
                    radius = outerRadius * 1.3f,
                    center = center
                )
            }

            // Opaque backing -- blocks starfield behind the button
            drawCircle(
                color = NjBg,
                radius = outerRadius,
                center = center
            )

            // Breathing coral ring
            drawCircle(
                color = NjRecordCoral.copy(alpha = ringAlpha),
                radius = outerRadius - ringStroke / 2f,
                style = Stroke(width = ringStroke)
            )

            // Body circle
            drawCircle(
                color = bodyColor,
                radius = bodyRadius,
                center = center
            )

            // Inner shadow when pressed -- dark edge gradient for depth
            if (visuallyPressed) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.75f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.25f)
                        ),
                        center = center,
                        radius = bodyRadius
                    ),
                    radius = bodyRadius,
                    center = center
                )
            }

            // Circular bevel -- two arcs for highlight and shadow
            val bevelRadius = bodyRadius - bevelStroke / 2f
            val bevelLeft = center.x - bevelRadius
            val bevelTop = center.y - bevelRadius
            val bevelDiameter = bevelRadius * 2f
            val bevelSize = Size(bevelDiameter, bevelDiameter)

            if (visuallyPressed) {
                // Pressed: dark top-left, subtle light bottom-right
                drawArc(
                    color = Color.Black.copy(alpha = 0.45f),
                    startAngle = 225f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(bevelLeft, bevelTop),
                    size = bevelSize,
                    style = Stroke(width = bevelStroke)
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.06f),
                    startAngle = 45f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(bevelLeft, bevelTop),
                    size = bevelSize,
                    style = Stroke(width = bevelStroke)
                )
            } else {
                // Raised: light top-left, dark bottom-right
                drawArc(
                    color = Color.White.copy(alpha = 0.09f),
                    startAngle = 225f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(bevelLeft, bevelTop),
                    size = bevelSize,
                    style = Stroke(width = bevelStroke)
                )
                drawArc(
                    color = Color.Black.copy(alpha = 0.35f),
                    startAngle = 45f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(bevelLeft, bevelTop),
                    size = bevelSize,
                    style = Stroke(width = bevelStroke)
                )
            }

            // Coral indicator: circle when idle, rounded-square stop icon when recording
            // Scales with pressScale so it sinks with the body
            val innerSize = size.minDimension * innerSizeFraction * pressScale
            val cr = innerSize * cornerFraction
            val topLeft = Offset(
                (size.width - innerSize) / 2f,
                (size.height - innerSize) / 2f
            )
            drawRoundRect(
                color = NjRecordCoral,
                topLeft = topLeft,
                size = Size(innerSize, innerSize),
                cornerRadius = CornerRadius(cr, cr)
            )
        }
    }
}
