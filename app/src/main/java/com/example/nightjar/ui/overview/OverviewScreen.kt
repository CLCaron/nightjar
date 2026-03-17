package com.example.nightjar.ui.overview

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.nightjar.share.ShareUtils
import com.example.nightjar.ui.components.NjIcons
import com.example.nightjar.ui.components.collectIsPressedWithMinDuration
import com.example.nightjar.ui.components.NjSectionTitle
import com.example.nightjar.ui.components.NjButton
import com.example.nightjar.ui.components.NjTagChip
import com.example.nightjar.ui.components.NjTextField
import com.example.nightjar.ui.components.NjTopBar
import com.example.nightjar.ui.components.NjWaveform
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjBg
import com.example.nightjar.ui.theme.NjError
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjLedGreen
import com.example.nightjar.ui.theme.NjLedTeal
import com.example.nightjar.ui.theme.NjSurface2
import com.example.nightjar.ui.theme.NjTrackColors
import kotlinx.coroutines.flow.collectLatest

/**
 * Overview screen -- view and edit a single idea.
 *
 * Provides playback controls with waveform visualization, editable title and
 * notes fields, tag management, favorite toggle, sharing, and deletion. Title
 * and notes are auto-saved with a 600 ms debounce. The "Studio" button opens
 * the multi-track studio for this idea.
 *
 * All controls use NjButton for a unified hardware aesthetic.
 */
@Composable
fun OverviewScreen(
    ideaId: Long,
    onBack: () -> Unit,
    onOpenStudio: (Long) -> Unit = {}
) {
    val context = LocalContext.current

    val vm: OverviewViewModel = hiltViewModel()

    LaunchedEffect(ideaId) {
        vm.onAction(OverviewAction.Load(ideaId))
    }

    val state by vm.state.collectAsState()
    val loaded = state.idea
    val tags = state.tags
    val titleDraft = state.titleDraft
    val notesDraft = state.notesDraft
    val errorMessage = state.errorMessage
    val compositeWaveform = state.compositeWaveform
    val hasTracks = state.hasTracks

    val isPlaying by vm.isPlaying.collectAsState()
    val durationMs by vm.totalDurationMs.collectAsState()
    val positionMs by vm.globalPositionMs.collectAsState()

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf("") }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableStateOf(0f) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.effects.collectLatest { effect ->
            when (effect) {
                is OverviewEffect.NavigateBack -> onBack()
                is OverviewEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    // Intercept system back so empty-idea cleanup runs via the ViewModel.
    BackHandler { vm.onAction(OverviewAction.NavigateBack) }

    DisposableEffect(Unit) {
        onDispose {
            // Flush is also done in NavigateBack, but this covers cases
            // where the screen is disposed without an explicit back action
            // (e.g. process death, activity recreation).
            vm.onAction(OverviewAction.FlushPendingSaves)
        }
    }

    // Pause playback when leaving the screen, refresh tracks when returning.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onAction(OverviewAction.Pause)
                Lifecycle.Event.ON_RESUME -> vm.onAction(OverviewAction.RefreshTracks)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // First track file is only needed for the share button.
    var firstTrackFile by remember { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(loaded?.id) {
        firstTrackFile = loaded?.let { vm.getFirstTrackFile(it.id) }
    }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val displayFraction = if (isScrubbing) {
        scrubFraction
    } else {
        positionMs.toFloat() / safeDuration.toFloat()
    }
    val displayMs = if (isScrubbing) {
        (scrubFraction * safeDuration).toLong()
    } else {
        positionMs
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (loaded != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        NjButton(
                            text = "Delete",
                            icon = Icons.Filled.Delete,
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.weight(1f),
                            textColor = NjError
                        )
                        firstTrackFile?.let { file ->
                            NjButton(
                                text = "Share",
                                icon = Icons.Filled.Share,
                                onClick = {
                                    ShareUtils.shareAudioFile(
                                        context = context,
                                        file = file,
                                        title = loaded.title
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
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
                title = "Overview",
                onBack = { vm.onAction(OverviewAction.NavigateBack) }
            )

            errorMessage?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error)
            }

            if (loaded == null) {
                Text(
                    "Loading...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NjButton(
                    text = if (loaded.isFavorite) "Favorited" else "Favorite",
                    icon = Icons.Filled.Star,
                    isActive = loaded.isFavorite,
                    ledColor = NjAccent,
                    onClick = { vm.onAction(OverviewAction.ToggleFavorite) }
                )

                NjStudioEntryButton(
                    onClick = { onOpenStudio(ideaId) }
                )
            }

            NjTextField(
                value = titleDraft,
                onValueChange = { vm.onAction(OverviewAction.TitleChanged(it)) },
                label = "Title",
                placeholder = "Untitled idea",
                singleLine = true
            )

            NjSectionTitle("Playback")

            if (compositeWaveform != null && compositeWaveform.isNotEmpty()) {
                NjWaveform(
                    amplitudes = compositeWaveform,
                    modifier = Modifier.fillMaxWidth(),
                    height = 64.dp,
                    barColor = NjTrackColors[0].copy(alpha = 0.65f),
                    progressFraction = displayFraction,
                    onScrub = { fraction ->
                        isScrubbing = true
                        scrubFraction = fraction
                    },
                    onScrubFinished = { fraction ->
                        isScrubbing = false
                        val seekMs = (fraction * safeDuration).toLong()
                        vm.onAction(OverviewAction.SeekTo(seekMs))
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatMs(displayMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                    Text(
                        formatMs(durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            } else if (!hasTracks) {
                Text(
                    "No audio tracks yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }

            NjButton(
                text = if (isPlaying) "Pause" else "Play",
                icon = NjIcons.PlayPause,
                isActive = isPlaying,
                ledColor = NjLedGreen,
                onClick = {
                    if (isPlaying) {
                        vm.onAction(OverviewAction.Pause)
                    } else {
                        vm.onAction(OverviewAction.Play)
                    }
                }
            )

            NjSectionTitle("Notes")
            NjTextField(
                value = notesDraft,
                onValueChange = { vm.onAction(OverviewAction.NotesChanged(it)) },
                label = "Notes",
                placeholder = "Lyrics? Chords? Vibe?",
                minLines = 6,
                maxLines = 12
            )

            NjSectionTitle("Tags")
            if (tags.isEmpty()) {
                Text(
                    "No tags yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = tags, key = { it.id }) { tag ->
                        NjTagChip(
                            text = tag.name,
                            onClick = { vm.onAction(OverviewAction.RemoveTag(tag.id)) }
                        )
                    }
                }
            }

            NjTextField(
                value = newTagText,
                onValueChange = { newTagText = it },
                label = "Add tag",
                placeholder = "e.g. chorus, sad, 120bpm",
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val text = newTagText
                        newTagText = ""
                        vm.onAction(OverviewAction.AddTagsFromInput(text))
                    }
                )
            )

            NjButton(
                text = "Add Tag",
                onClick = {
                    val text = newTagText
                    newTagText = ""
                    vm.onAction(OverviewAction.AddTagsFromInput(text))
                }
            )

            Spacer(Modifier.height(96.dp))

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Delete this idea?") },
                    text = { Text("This will permanently delete the idea and its audio file.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirm = false
                            vm.onAction(OverviewAction.DeleteIdea)
                        }) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/**
 * Content-width Studio entry button with hardware aesthetic.
 *
 * Features a scanline overlay, teal-glowing text, Tune icon,
 * breathing teal ambient glow, and press-to-darken animation
 * matching NjButton's bevel feel.
 */
@Composable
private fun NjStudioEntryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studioEntryAccent = lerp(NjBg, NjLedTeal, 0.55f)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedWithMinDuration()
    val view = LocalView.current

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

    val bgColor = if (isPressed) {
        NjSurface2.copy(alpha = 0.4f)
    } else {
        NjMuted2.copy(alpha = 0.12f)
    }

    val glowShadow = Shadow(
        color = studioEntryAccent.copy(alpha = 0.25f),
        offset = Offset.Zero,
        blurRadius = 10f
    )

    // Perimeter-tracing teal outline animation
    val traceTransition = rememberInfiniteTransition(label = "studioTrace")
    val traceProgress by traceTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "traceProgress"
    )

    val cornerRadius = 2.dp
    val strokeWidthDp = 1.5.dp
    val mutedOutlineColor = studioEntryAccent.copy(alpha = 0.15f)
    val litTraceColor = studioEntryAccent.copy(alpha = 0.6f)

    // Outer box draws static muted outline + animated lit trace
    Box(
        modifier = modifier.drawBehind {
            val cr = cornerRadius.toPx()
            val sw = strokeWidthDp.toPx()
            val inset = sw / 2f

            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = inset,
                        top = inset,
                        right = size.width - inset,
                        bottom = size.height - inset,
                        radiusX = cr,
                        radiusY = cr
                    )
                )
            }

            // Static muted outline (always visible)
            drawPath(
                path = path,
                color = mutedOutlineColor,
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )

            val measure = PathMeasure().apply { setPath(path, forceClosed = true) }
            val totalLength = measure.length

            // Fill phase (0..0.5): leading edge advances, trailing stays at 0
            // Drain phase (0.5..1): trailing edge catches up, leading stays at end
            val startDist: Float
            val endDist: Float
            if (traceProgress <= 0.5f) {
                val fillT = traceProgress / 0.5f
                startDist = 0f
                endDist = fillT * totalLength
            } else {
                val drainT = (traceProgress - 0.5f) / 0.5f
                startDist = drainT * totalLength
                endDist = totalLength
            }

            if (endDist > startDist) {
                val segment = Path()
                measure.getSegment(startDist, endDist, segment, startWithMoveTo = true)
                drawPath(
                    path = segment,
                    color = litTraceColor,
                    style = Stroke(width = sw, cap = StrokeCap.Round)
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(cornerRadius))
                .background(bgColor)
                .drawWithContent {
                    drawContent()

                    val sw = 1.dp.toPx()

                    if (isPressed) {
                        // Pressed: dark top+left, light bottom+right
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
                        // Raised: light top+left, dark bottom+right
                        drawLine(
                            Color.White.copy(alpha = 0.09f),
                            Offset(0f, sw / 2),
                            Offset(size.width, sw / 2),
                            sw
                        )
                        drawLine(
                            Color.White.copy(alpha = 0.05f),
                            Offset(sw / 2, 0f),
                            Offset(sw / 2, size.height),
                            sw
                        )
                        drawLine(
                            Color.Black.copy(alpha = 0.35f),
                            Offset(0f, size.height - sw / 2),
                            Offset(size.width, size.height - sw / 2),
                            sw
                        )
                        drawLine(
                            Color.Black.copy(alpha = 0.18f),
                            Offset(size.width - sw / 2, 0f),
                            Offset(size.width - sw / 2, size.height),
                            sw
                        )
                    }

                    // Scanline overlay
                    val scanlineSpacing = 2.dp.toPx()
                    val scanlineColor = Color.White.copy(alpha = 0.06f)
                    var y = 0f
                    while (y < size.height) {
                        drawLine(
                            scanlineColor,
                            Offset(0f, y),
                            Offset(size.width, y),
                            0.5.dp.toPx()
                        )
                        y += scanlineSpacing
                    }
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    tint = studioEntryAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Studio",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        shadow = glowShadow
                    ),
                    color = studioEntryAccent
                )
            }
        }
    }
}
