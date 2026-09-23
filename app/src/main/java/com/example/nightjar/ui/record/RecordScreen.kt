package com.example.nightjar.ui.record

import com.example.nightjar.ui.components.NjButton
import com.example.nightjar.ui.components.NjLiveWaveform
import com.example.nightjar.ui.components.NjRecessedPanel
import com.example.nightjar.ui.components.NjWaveform
import com.example.nightjar.ui.components.PressedBodyColor
import com.example.nightjar.ui.components.RaisedBodyColor
import com.example.nightjar.ui.components.collectIsPressedWithMinDuration
import com.example.nightjar.ui.components.njGrain
import com.example.nightjar.ui.components.rememberMechanicalToggleState
import com.example.nightjar.ui.theme.IbmPlexMono
import com.example.nightjar.ui.theme.NjBg
import com.example.nightjar.ui.theme.NjMuted
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjPanelInset
import com.example.nightjar.ui.theme.NjRecordCoral
import com.example.nightjar.ui.theme.NjStarlight
import com.example.nightjar.ui.theme.NjStarfieldTint
import com.example.nightjar.ui.theme.NjSurface
import com.example.nightjar.ui.theme.NjTrackColors
import android.Manifest
import android.os.Build
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.nightjar.ui.theme.NjAmber
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding

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
    ideaId: Long? = null,
    onOpenLibrary: () -> Unit,
    onOpenOverview: (Long) -> Unit,
    onOpenStudio: (Long) -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val vm: RecordViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    LaunchedEffect(ideaId) { ideaId?.let(vm::openIdea) }

    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onAction(RecordAction.StartRecording)
    }
    val startWithNotification = {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else vm.onAction(RecordAction.StartRecording)
    }
    val requestPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startWithNotification()
        else uiScope.launch { snackbarHostState.showSnackbar("Microphone permission is needed to record. Writing is still available.") }
    }
    val startCapture = {
        if (!state.isSaving) {
            if (state.isRecording) vm.onAction(RecordAction.StartRecording)
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startWithNotification()
            else requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(vm) { onDispose { vm.onAction(RecordAction.LeaveScreen) } }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message, withDismissAction = true,
                duration = androidx.compose.material3.SnackbarDuration.Indefinite)
        }
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

    Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { padding ->
        Box(Modifier.fillMaxSize()) {
            RecordScreenBackground()
            CaptureWritingWorkspace(state = state, onRecord = startCapture,
                onAction = vm::onAction,
                onLibrary = { vm.onAction(RecordAction.LeaveScreen); onOpenLibrary() },
                onSettings = { vm.onAction(RecordAction.LeaveScreen); onOpenSettings() },
                modifier = Modifier.fillMaxSize().padding(padding)
                    .consumeWindowInsets(padding).imePadding().padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}
/**
 * Layered background treatment for the Record screen.
 *
 * Three effects composed in a single full-bleed Box:
 * 1. Fine grain -- breaks the glossy-flat digital feel
 * 2. Vignette -- gentle edge darkening via radial gradient
 * 3. Directional light -- extremely faint top-left warmth
 */
@Composable
private fun RecordScreenBackground() {
    val panelInsetColor = NjPanelInset
    val starfieldTintColor = NjStarfieldTint

    Box(
        modifier = Modifier
            .fillMaxSize()
            .njGrain(alpha = 0.015f, tintColor = NjStarlight)
            .drawBehind {
                // Vignette: transparent center, warm dark edges
                // Uses deep indigo rather than pure black to preserve warmth
                val diagonal = kotlin.math.sqrt(
                    size.width * size.width + size.height * size.height
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            panelInsetColor.copy(alpha = 0.35f)
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = diagonal * 0.375f
                    )
                )

                // Directional light: faint warmth from top-left
                // Warm cream tint instead of white to avoid gray cast
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            starfieldTintColor.copy(alpha = 0.02f),
                            Color.Transparent
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                )
            }
    )
}

@Composable
internal fun CaptureLatchStrip(state: RecordUiState, onAction: (RecordAction) -> Unit) {
    val selected = state.capture.groups.filter { it.latchedTakeId != null }
    Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("PLAYBACK", color = NjMuted, fontFamily = IbmPlexMono, fontSize = 9.sp)
        if (selected.isEmpty()) {
            Text("NOTHING LATCHED", color = NjMuted2, fontFamily = IbmPlexMono, fontSize = 9.sp)
        } else selected.forEach { group ->
            NjButton(text = group.displayName, caption = if (state.capture.playing) "PLAYING" else "LATCHED",
                isActive = true, ledColor = NjAmber, enabled = !state.capture.busy && !state.capture.playing,
                onClick = { onAction(RecordAction.UnlatchGroup(group.id)) })
        }
    }
}

@Composable
internal fun CaptureGroupStrip(state: RecordUiState, onAction: (RecordAction) -> Unit) {
    val capture = state.capture
    var renaming by remember { mutableStateOf<com.example.nightjar.data.db.entity.CaptureGroupEntity?>(null) }
    var name by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().height(56.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("RECORD TO", color = NjMuted, fontFamily = IbmPlexMono, fontSize = 9.sp)
        if (capture.groups.isEmpty()) {
            NjButton(text = "Original Idea", caption = "GROUP", isActive = true,
                ledColor = NjTrackColors[0], onClick = {})
        }
        capture.groups.forEach { group ->
            NjButton(text = group.displayName, caption = "GROUP", isActive = group.id == capture.openGroupId,
                ledColor = NjTrackColors[group.sortIndex % NjTrackColors.size],
                enabled = !capture.busy && !capture.playing,
                onClick = { onAction(RecordAction.OpenGroup(group.id)) })
        }
        NjButton(text = "+", caption = "NEW GROUP", enabled = !capture.busy && !capture.playing,
            onClick = { onAction(RecordAction.CreateGroup) })
        val open = capture.groups.find { it.id == capture.openGroupId }
        NjButton(text = "Name", caption = "RENAME", enabled = open != null && !capture.busy,
            onClick = { renaming = open; name = open?.displayName.orEmpty() })
        NjButton(text = if (open?.inStudio == true) "Added" else "Add", caption = "TO STUDIO",
            enabled = open != null && open.inStudio.not() && !capture.busy,
            onClick = { open?.let { onAction(RecordAction.AddGroupToStudio(it.id)) } })
    }
    renaming?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name this group") },
            text = { androidx.compose.material3.OutlinedTextField(value = name,
                onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Group name") }) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                if (name.isNotBlank()) onAction(RecordAction.RenameGroup(target.id, name))
                renaming = null
            }) { Text("Save") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
}

@Composable
internal fun CaptureTakeShelf(state: RecordUiState, onAction: (RecordAction) -> Unit,
    modifier: Modifier = Modifier) {
    val open = state.capture.groups.find { it.id == state.capture.openGroupId }
    Column(modifier.fillMaxWidth()) {
        Text("${(open?.displayName ?: "ORIGINAL IDEA").uppercase()} TAKES  /  ${state.capture.takes.size + state.capture.pendingTakeWaveforms.size}",
            color = NjMuted, fontFamily = IbmPlexMono, fontSize = 9.sp,
            modifier = Modifier.padding(vertical = 7.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.capture.takes.isEmpty() && state.capture.pendingTakeWaveforms.isEmpty()) {
            Text("Press Record to capture a take in this group.", color = NjMuted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }
        state.capture.pendingTakeWaveforms.indices.reversed().forEach { index ->
            val waveform = state.capture.pendingTakeWaveforms[index]
            Column(Modifier.fillMaxWidth().height(82.dp)
                .clip(RoundedCornerShape(5.dp)).background(NjPanelInset).padding(7.dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                Text("TAKE ${state.capture.takes.size + index + 1}", color = NjMuted,
                    fontFamily = IbmPlexMono, fontSize = 9.sp)
                NjLiveWaveform(amplitudes = waveform.toFloatArray(), modifier = Modifier.fillMaxWidth(),
                    height = 37.dp, barColor = NjTrackColors[0])
                Text("FINALIZES ON STOP", color = NjMuted2, fontFamily = IbmPlexMono, fontSize = 8.sp)
            }
        }
        state.capture.takes.asReversed().forEach { take ->
            val latched = state.capture.groups.any { it.latchedTakeId == take.id }
            Column(Modifier.fillMaxWidth().height(82.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (latched) NjPanelInset else RaisedBodyColor)
                .clickable(enabled = !state.capture.busy && !state.capture.playing && !state.capture.pendingSave) {
                    onAction(RecordAction.SelectTake(take.id))
                }.padding(7.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("${take.displayName.uppercase()}  ${take.durationMs / 1000}s",
                    color = if (latched) NjAmber else NjMuted, fontFamily = IbmPlexMono,
                    fontSize = 9.sp, maxLines = 1)
                state.capture.takeFiles[take.id]?.let { file ->
                    NjWaveform(audioFile = file, modifier = Modifier.fillMaxWidth(),
                        height = 37.dp, barColor = if (latched) NjAmber else NjTrackColors[0])
                }
                Text(if (latched) "LATCHED" else "TAP TO LATCH", color = NjMuted,
                    fontFamily = IbmPlexMono, fontSize = 8.sp)
            }
        }
        }
    }
}
@Composable
internal fun WaveformSection(
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
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    NjLiveWaveform(
                        amplitudes = liveAmplitudes,
                        modifier = Modifier.fillMaxWidth(),
                        height = 48.dp,
                        barColor = waveformColor
                    )
                }
            }
        }
        else -> {
            NjRecessedPanel(modifier = modifier) {
                Spacer(Modifier.fillMaxSize())
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
internal fun HardwareRecordButton(
    isRecording: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val toggleState = rememberMechanicalToggleState(isRecording)
    val depth by toggleState.depth
    val view = LocalView.current

    // Depth-based scale: 1.0 raised, 0.965 latched, 0.93 deep press
    val pressScale = 1.0f - (depth * 0.07f)

    // Hoist theme colors before Canvas (DrawScope is not composable)
    val njBg = NjBg
    val njMuted2 = NjMuted2
    val njRecordCoral = NjRecordCoral
    val njSurface = NjSurface

    // Body blends toward background as depth increases
    val bodyColor = lerp(njSurface, njBg, depth * 2f)

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
            .semantics { contentDescription = "Record" }
            .clickable(
                enabled = enabled,
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
                            njRecordCoral.copy(alpha = 0.12f),
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
                color = njBg,
                radius = outerRadius,
                center = center
            )

            // Coral ring -- full circle when breathing, arc when draining
            if (isDraining) {
                val ringDiameter = ringRadius * 2f
                drawArc(
                    color = njRecordCoral.copy(alpha = ringAlpha),
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
                    color = njRecordCoral.copy(alpha = ringAlpha),
                    radius = ringRadius,
                    style = Stroke(width = ringStroke)
                )
            }

            // Knurled edge -- subtle radial ridges between ring and body
            val knurlCount = 72
            val knurlInner = bodyRadius + 1.dp.toPx()
            val knurlOuter = outerRadius - ringStroke - 1.dp.toPx()
            val knurlStroke = 1.dp.toPx()
            val knurlColor = njMuted2.copy(alpha = 0.25f)

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
                color = njRecordCoral,
                radius = dotRadius,
                center = center
            )
        }

        // Grain overlay clipped to the body circle
        Box(
            Modifier
                .size(92.dp * 0.86f * pressScale)
                .clip(CircleShape)
                .njGrain(alpha = 0.04f)
        )
    }
}

/** LCD-style status readout -- monospace text inside a small recessed panel. */
@Composable
internal fun StatusLcd(
    text: String,
    modifier: Modifier = Modifier
) {
    NjRecessedPanel(
        modifier = modifier.fillMaxWidth().heightIn(min = 28.dp),
        backgroundColor = NjPanelInset
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = IbmPlexMono,
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp
                ),
                color = NjAmber.copy(alpha = 0.7f)
            )
        }
    }
}

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
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        NjWaveform(
            audioFile = audioFile,
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
            barColor = barColor
        )
    }
}
