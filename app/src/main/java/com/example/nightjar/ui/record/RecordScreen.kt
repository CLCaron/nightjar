package com.example.nightjar.ui.record

import com.example.nightjar.ui.components.NjCard
import com.example.nightjar.ui.components.NjLiveWaveform
import com.example.nightjar.ui.components.PressedBodyColor
import com.example.nightjar.ui.components.RaisedBodyColor
import com.example.nightjar.ui.components.NjRecessedPanel
import com.example.nightjar.ui.components.NjButton
import com.example.nightjar.ui.components.NjWaveform
import com.example.nightjar.ui.components.collectIsPressedWithMinDuration
import com.example.nightjar.ui.components.rememberMechanicalToggleState
import com.example.nightjar.ui.theme.IbmPlexMono
import com.example.nightjar.ui.theme.NjBg
import com.example.nightjar.ui.theme.NjPanelInset
import com.example.nightjar.ui.theme.NjMuted
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjRecordCoral
import com.example.nightjar.ui.theme.NjTrackColors
import android.content.res.Configuration
import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.nightjar.ui.theme.NjStudioAccent
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.collectLatest

/**
 * Record screen -- the app's landing page.
 *
 * Centered around a hardware-style circular record button with a coral
 * LED dot. The button body blends with the background and sinks in on
 * press. After stopping, the user sees a waveform preview in a recessed
 * panel with options to open Overview, open Studio, or start a new
 * recording. All secondary actions use NjButton.
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

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = if (isLandscape) 8.dp else 18.dp)

        if (!hasMicPermission) {
            Column(
                modifier = contentModifier,
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Microphone permission is required to record.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f)
                )

                Spacer(Modifier.height(14.dp))

                NjButton(
                    text = "Enable microphone",
                    onClick = { requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    isActive = false,
                    ledColor = NjRecordCoral,
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        } else {
            val postRecording = state.postRecording
            val isSaving = !state.isRecording && postRecording == null &&
                state.liveAmplitudes.isNotEmpty()
            val isBusy = state.isRecording || isSaving
            val waveformColor = NjTrackColors[0].copy(alpha = 0.65f)
            val writeSunk = isBusy || postRecording != null

            val lcdText = when {
                state.isRecording -> "RECORDING"
                isSaving -> "SAVING"
                postRecording != null -> "SAVED"
                else -> "RECORD"
            }

            if (isLandscape) {
                LandscapeRecordLayout(
                    isRecording = state.isRecording,
                    lcdText = lcdText,
                    postRecording = postRecording,
                    liveAmplitudes = state.liveAmplitudes,
                    waveformColor = waveformColor,
                    isBusy = isBusy,
                    writeSunk = writeSunk,
                    onRecord = {
                        if (!state.isRecording) vm.onAction(RecordAction.StartRecording)
                        else vm.onAction(RecordAction.StopAndSave)
                    },
                    onGoToOverview = { vm.onAction(RecordAction.GoToOverview) },
                    onWrite = { vm.onAction(RecordAction.CreateWriteIdea) },
                    onStudio = {
                        if (postRecording != null) vm.onAction(RecordAction.GoToStudio)
                        else vm.onAction(RecordAction.CreateStudioIdea)
                    },
                    onLibrary = onOpenLibrary,
                    modifier = contentModifier
                )
            } else {
                PortraitRecordLayout(
                    isRecording = state.isRecording,
                    lcdText = lcdText,
                    postRecording = postRecording,
                    liveAmplitudes = state.liveAmplitudes,
                    waveformColor = waveformColor,
                    isBusy = isBusy,
                    writeSunk = writeSunk,
                    onRecord = {
                        if (!state.isRecording) vm.onAction(RecordAction.StartRecording)
                        else vm.onAction(RecordAction.StopAndSave)
                    },
                    onGoToOverview = { vm.onAction(RecordAction.GoToOverview) },
                    onWrite = { vm.onAction(RecordAction.CreateWriteIdea) },
                    onStudio = {
                        if (postRecording != null) vm.onAction(RecordAction.GoToStudio)
                        else vm.onAction(RecordAction.CreateStudioIdea)
                    },
                    onLibrary = onOpenLibrary,
                    modifier = contentModifier
                )
            }
        }
    }
}

@Composable
private fun PortraitRecordLayout(
    isRecording: Boolean,
    lcdText: String,
    postRecording: PostRecordingState?,
    liveAmplitudes: FloatArray,
    waveformColor: Color,
    isBusy: Boolean,
    writeSunk: Boolean,
    onRecord: () -> Unit,
    onGoToOverview: () -> Unit,
    onWrite: () -> Unit,
    onStudio: () -> Unit,
    onLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Text(
                text = "Nightjar",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
            Text(
                text = "Nightjar",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White.copy(alpha = 0.25f),
                modifier = Modifier.offset(x = 1.1.dp, y = 1.3.dp)
            )
        }

        // Subtle groove below title
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth(0.5f).height(1.dp)) {
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        NjMuted.copy(alpha = 0.18f),
                        Color.Transparent
                    )
                ),
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = size.height
            )
        }

        Spacer(Modifier.height(24.dp))

        HardwareRecordButton(
            isRecording = isRecording,
            onClick = onRecord
        )

        Spacer(Modifier.height(14.dp))

        StatusLcd(lcdText)

        Spacer(Modifier.height(16.dp))

        WaveformSection(
            postRecording = postRecording,
            liveAmplitudes = liveAmplitudes,
            waveformColor = waveformColor,
            onGoToOverview = onGoToOverview,
            modifier = Modifier.fillMaxWidth(0.85f)
        )

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RecordScreenButton(
                icon = Icons.Filled.Edit,
                label = "Write",
                onClick = onWrite,
                enabled = !writeSunk,
                modifier = Modifier.weight(1f)
            )
            RecordScreenButton(
                icon = Icons.Filled.Tune,
                label = "Studio",
                onClick = onStudio,
                enabled = !isBusy,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        RecordScreenButton(
            icon = Icons.AutoMirrored.Filled.List,
            label = "Library",
            onClick = onLibrary,
            enabled = !isBusy
        )
    }
}

@Composable
private fun LandscapeRecordLayout(
    isRecording: Boolean,
    lcdText: String,
    postRecording: PostRecordingState?,
    liveAmplitudes: FloatArray,
    waveformColor: Color,
    isBusy: Boolean,
    writeSunk: Boolean,
    onRecord: () -> Unit,
    onGoToOverview: () -> Unit,
    onWrite: () -> Unit,
    onStudio: () -> Unit,
    onLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Record button, centered
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            HardwareRecordButton(
                isRecording = isRecording,
                onClick = onRecord
            )
        }

        // Right: LCD + Waveform + Action buttons
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusLcd(lcdText)

            Spacer(Modifier.height(12.dp))

            WaveformSection(
                postRecording = postRecording,
                liveAmplitudes = liveAmplitudes,
                waveformColor = waveformColor,
                onGoToOverview = onGoToOverview,
                modifier = Modifier.fillMaxWidth(0.85f)
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RecordScreenButton(
                    icon = Icons.Filled.Edit,
                    label = "Write",
                    onClick = onWrite,
                    enabled = !writeSunk,
                    modifier = Modifier.weight(1f)
                )
                RecordScreenButton(
                    icon = Icons.Filled.Tune,
                    label = "Studio",
                    onClick = onStudio,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                )
                RecordScreenButton(
                    icon = Icons.AutoMirrored.Filled.List,
                    label = "Library",
                    onClick = onLibrary,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Waveform panel section -- shows live waveform, post-recording card, or empty panel. */
@Composable
private fun WaveformSection(
    postRecording: PostRecordingState?,
    liveAmplitudes: FloatArray,
    waveformColor: Color,
    onGoToOverview: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        postRecording != null -> {
            TransformingWaveformPanel(
                audioFile = postRecording.audioFile,
                barColor = waveformColor,
                onClick = onGoToOverview,
                modifier = modifier
            )
        }
        liveAmplitudes.isNotEmpty() -> {
            NjRecessedPanel(modifier = modifier) {
                NjLiveWaveform(
                    amplitudes = liveAmplitudes,
                    modifier = Modifier.fillMaxWidth(),
                    height = 48.dp,
                    barColor = waveformColor
                )
            }
        }
        else -> {
            NjRecessedPanel(modifier = modifier) {
                Spacer(Modifier.height(48.dp).fillMaxWidth())
            }
        }
    }
}

/**
 * Hardware-style circular record button with beveled edges and knurled ring.
 *
 * The body color is very close to the background so it "disappears" into
 * the surface when pressed -- like pressing a flush-mount button. A coral
 * LED dot is painted at the center (always circular, no shape morphing).
 * Subtle coral ring breathes slowly when idle, pulses when recording.
 * When recording stops, the ring drains counterclockwise before returning
 * to idle breathing. A faint radial glow appears behind the button during
 * recording.
 */
@Composable
private fun HardwareRecordButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val toggleState = rememberMechanicalToggleState(isRecording)
    val depth by toggleState.depth
    val view = LocalView.current

    // Depth-based scale: 1.0 raised, 0.965 latched, 0.93 deep press
    val pressScale = 1.0f - (depth * 0.07f)

    // Body blends toward background as depth increases
    val bodyColor = lerp(Color(0xFF151220), NjBg, depth * 2f)

    // Haptics on raw press/release events
    LaunchedEffect(toggleState.interactionSource) {
        toggleState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                is PressInteraction.Release ->
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    // Ring drain animation -- counterclockwise sweep from 360 to 0 when recording stops
    var isDraining by remember { mutableStateOf(false) }
    val drainSweep = remember { Animatable(360f) }
    var drainAlpha by remember { mutableFloatStateOf(0.3f) }

    // Ring breathing -- slow when idle, faster pulse when recording.
    // During drain, the infinite transition still runs but we ignore its alpha.
    val ringTransition = rememberInfiniteTransition(label = "ring")
    val breathingAlpha by ringTransition.animateFloat(
        initialValue = if (isRecording) 0.15f else 0.08f,
        targetValue = if (isRecording) 0.45f else 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isRecording) 900 else 5_000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingAlpha"
    )

    // Detect recording stop -> trigger drain
    val currentBreathingAlpha by rememberUpdatedState(breathingAlpha)
    LaunchedEffect(isRecording) {
        if (!isRecording) {
            // Capture the breathing alpha at the moment recording stopped
            drainAlpha = currentBreathingAlpha.coerceIn(0.15f, 0.45f)
            isDraining = true
            drainSweep.snapTo(360f)
            drainSweep.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 400, easing = LinearEasing)
            )
            isDraining = false
        }
    }

    // Effective ring alpha: use drain alpha during drain, breathing otherwise
    val ringAlpha = if (isDraining) drainAlpha else breathingAlpha

    Box(
        modifier = modifier
            .size(92.dp)
            .clickable(
                interactionSource = toggleState.interactionSource,
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
            val bodyRadius = outerRadius * 0.86f * pressScale
            val ringRadius = outerRadius - ringStroke / 2f

            // Recording glow -- subtle coral radial gradient behind body
            if (isRecording) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NjRecordCoral.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = outerRadius * 1.3f
                    ),
                    radius = outerRadius * 1.3f,
                    center = center
                )
            }

            // Opaque backing circle
            drawCircle(
                color = NjBg,
                radius = outerRadius,
                center = center
            )

            // Coral ring -- full circle when breathing, arc when draining
            if (isDraining) {
                val ringDiameter = ringRadius * 2f
                drawArc(
                    color = NjRecordCoral.copy(alpha = ringAlpha),
                    startAngle = -90f,
                    sweepAngle = -drainSweep.value,
                    useCenter = false,
                    topLeft = Offset(
                        center.x - ringRadius,
                        center.y - ringRadius
                    ),
                    size = androidx.compose.ui.geometry.Size(
                        ringDiameter, ringDiameter
                    ),
                    style = Stroke(width = ringStroke)
                )
            } else {
                drawCircle(
                    color = NjRecordCoral.copy(alpha = ringAlpha),
                    radius = ringRadius,
                    style = Stroke(width = ringStroke)
                )
            }

            // Knurled edge -- subtle radial ridges between ring and body
            val knurlCount = 72
            val knurlInner = bodyRadius + 1.dp.toPx()
            val knurlOuter = outerRadius - ringStroke - 1.dp.toPx()
            val knurlStroke = 1.dp.toPx()
            val knurlColor = NjMuted2.copy(alpha = 0.25f)

            for (i in 0 until knurlCount) {
                val angle = (360f / knurlCount) * i
                val rad = Math.toRadians(angle.toDouble())
                val sinA = sin(rad).toFloat()
                val cosA = cos(rad).toFloat()
                drawLine(
                    color = knurlColor,
                    start = Offset(
                        center.x + knurlInner * sinA,
                        center.y - knurlInner * cosA
                    ),
                    end = Offset(
                        center.x + knurlOuter * sinA,
                        center.y - knurlOuter * cosA
                    ),
                    strokeWidth = knurlStroke
                )
            }

            // Body circle
            drawCircle(
                color = bodyColor,
                radius = bodyRadius,
                center = center
            )

            // Inner shadow when pressed -- dark edge gradient for depth
            if (depth > 0.25f) {
                val shadowAlpha = (depth * 2f).coerceAtMost(1f) * 0.25f
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.75f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = shadowAlpha)
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
            val bevelSize = androidx.compose.ui.geometry.Size(bevelDiameter, bevelDiameter)

            if (depth > 0.25f) {
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

            // Coral LED dot -- always circular, constant size
            val dotRadius = outerRadius * 0.35f * pressScale * 0.5f
            drawCircle(
                color = NjRecordCoral,
                radius = dotRadius,
                center = center
            )
        }
    }
}

/** LCD-style status readout -- monospace text inside a small recessed panel. */
@Composable
private fun StatusLcd(
    text: String,
    modifier: Modifier = Modifier
) {
    NjRecessedPanel(
        modifier = modifier.fillMaxWidth(0.5f),
        backgroundColor = Color(0xFF080610)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = TextStyle(
                    fontFamily = IbmPlexMono,
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp
                ),
                color = NjStudioAccent.copy(alpha = 0.7f)
            )
        }
    }
}

/** Full-width button with a leading icon for the Record screen vertical stack. */
@Composable
private fun RecordScreenButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    NjCard(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        horizontalArrangement = Arrangement.Center
    ) {
        if (enabled) {
            Box {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.10f),
                    modifier = Modifier.size(18.dp).offset(x = 0.4.dp, y = 0.6.dp)
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NjMuted.copy(alpha = 0.65f),
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NjMuted.copy(alpha = 0.65f),
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        if (enabled) {
            Box {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.10f),
                    modifier = Modifier.offset(x = 0.4.dp, y = 0.6.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
    }
}

/**
 * Waveform panel that transforms from recessed display into a raised tappable card.
 *
 * On first appearance, starts recessed (like a VU meter readout). After a brief
 * delay (~600ms for ring drain + beat), animates to a raised card style over 350ms.
 * Once raised, tapping navigates to Overview. Haptic pulse on transformation complete.
 */
@Composable
private fun TransformingWaveformPanel(
    audioFile: java.io.File,
    barColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val shape = RoundedCornerShape(6.dp)

    // Animation progress: 0 = recessed, 1 = raised
    val progress = remember { Animatable(0f) }
    var isTransformed by remember { mutableStateOf(false) }

    // Press detection for raised state
    val interactionSource = remember { MutableInteractionSource() }
    val isFingerDown by interactionSource.collectIsPressedWithMinDuration()

    // Haptic on press/release when transformed
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (isTransformed) {
                when (interaction) {
                    is PressInteraction.Press ->
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    is PressInteraction.Release ->
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        // Wait for ring drain (400ms) + brief beat (200ms)
        kotlinx.coroutines.delay(600L)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 350)
        )
        isTransformed = true
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    val p = progress.value
    val isPressedIn = isFingerDown && isTransformed

    // Interpolate background: recessed dark -> raised muted surface,
    // or pressed body when finger is down on the raised card
    val bgColor = if (isPressedIn) {
        PressedBodyColor
    } else {
        lerp(NjPanelInset, RaisedBodyColor, p)
    }

    // Slight scale lift during transformation
    val scaleY = 0.97f + 0.03f * p

    Box(
        modifier = modifier
            .graphicsLayer { this.scaleY = scaleY }
            .clip(shape)
            .background(bgColor)
            .drawWithContent {
                drawContent()

                val sw = 1.dp.toPx()
                val inv = 1f - p // recessed fade-out

                if (isPressedIn) {
                    // Pressed bevels: dark top + left, light bottom + right
                    drawLine(
                        Color.Black.copy(alpha = 0.45f),
                        Offset(0f, sw / 2),
                        Offset(size.width, sw / 2),
                        sw * 1.5f
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.25f),
                        Offset(sw / 2, 0f),
                        Offset(sw / 2, size.height),
                        sw
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.06f),
                        Offset(0f, size.height - sw / 2),
                        Offset(size.width, size.height - sw / 2),
                        sw
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.04f),
                        Offset(size.width - sw / 2, 0f),
                        Offset(size.width - sw / 2, size.height),
                        sw
                    )
                } else {
                    // Recessed bevels (fade out as p increases)
                    if (inv > 0.01f) {
                        // Dark top + left
                        drawLine(
                            Color.Black.copy(alpha = 0.45f * inv),
                            Offset(0f, sw / 2),
                            Offset(size.width, sw / 2),
                            sw * 1.5f
                        )
                        drawLine(
                            Color.Black.copy(alpha = 0.25f * inv),
                            Offset(sw / 2, 0f),
                            Offset(sw / 2, size.height),
                            sw
                        )
                        // Light bottom + right
                        drawLine(
                            Color.White.copy(alpha = 0.05f * inv),
                            Offset(0f, size.height - sw / 2),
                            Offset(size.width, size.height - sw / 2),
                            sw
                        )
                        drawLine(
                            Color.White.copy(alpha = 0.03f * inv),
                            Offset(size.width - sw / 2, 0f),
                            Offset(size.width - sw / 2, size.height),
                            sw
                        )
                    }

                    // Raised bevels (fade in as p increases)
                    if (p > 0.01f) {
                        // Light top + left
                        drawLine(
                            Color.White.copy(alpha = 0.09f * p),
                            Offset(0f, sw / 2),
                            Offset(size.width, sw / 2),
                            sw
                        )
                        drawLine(
                            Color.White.copy(alpha = 0.05f * p),
                            Offset(sw / 2, 0f),
                            Offset(sw / 2, size.height),
                            sw
                        )
                        // Dark bottom + right
                        drawLine(
                            Color.Black.copy(alpha = 0.35f * p),
                            Offset(0f, size.height - sw / 2),
                            Offset(size.width, size.height - sw / 2),
                            sw
                        )
                        drawLine(
                            Color.Black.copy(alpha = 0.18f * p),
                            Offset(size.width - sw / 2, 0f),
                            Offset(size.width - sw / 2, size.height),
                            sw
                        )
                    }
                }
            }
            .then(
                if (isTransformed) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(12.dp)
    ) {
        NjWaveform(
            audioFile = audioFile,
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
            barColor = barColor
        )
    }
}
