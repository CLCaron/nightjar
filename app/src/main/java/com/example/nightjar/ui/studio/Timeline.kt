package com.example.nightjar.ui.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.ui.components.NjWaveform
import com.example.nightjar.ui.theme.NjRecordCoral
import com.example.nightjar.ui.theme.NjError
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjStudioAccent
import com.example.nightjar.ui.theme.NjStudioLane
import com.example.nightjar.ui.theme.NjStudioSurface2
import com.example.nightjar.ui.theme.NjStudioWaveform
import com.example.nightjar.ui.theme.NjTrackColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import kotlin.math.abs

// ── Layout constants ────────────────────────────────────────────────────
private val HEADER_WIDTH = 100.dp
private val TRACK_LANE_HEIGHT = 56.dp
private val RULER_HEIGHT = 28.dp
private val TIMELINE_END_PADDING_DP = 120.dp
private const val MIN_EFFECTIVE_DURATION_MS = 200L
private val TRIM_HANDLE_WIDTH = 12.dp
private val TRIM_TOUCH_ZONE = 36.dp
private val TAKE_ROW_HEIGHT = 48.dp
private const val FAST_LONG_PRESS_MS = 200L

/**
 * The main timeline UI — per-track rows sharing a single horizontal
 * [ScrollState]. Each row has a fixed-width header on the left and a
 * scrollable lane on the right. An inline track drawer expands between
 * rows when a track header is tapped.
 */
@Composable
fun TimelinePanel(
    tracks: List<TrackEntity>,
    globalPositionMs: Long,
    totalDurationMs: Long,
    msPerDp: Float,
    isPlaying: Boolean,
    dragState: TrackDragState?,
    trimState: TrackTrimState?,
    loopStartMs: Long?,
    loopEndMs: Long?,
    isLoopEnabled: Boolean,
    expandedTrackIds: Set<Long>,
    soloedTrackIds: Set<Long>,
    armedTrackId: Long?,
    trackTakes: Map<Long, List<TakeEntity>>,
    expandedTakeTrackIds: Set<Long>,
    expandedTakeDrawerIds: Set<Long>,
    getAudioFile: (String) -> File,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val timelineWidthDp = remember(totalDurationMs, msPerDp) {
        (totalDurationMs / msPerDp).dp + TIMELINE_END_PADDING_DP
    }
    val density = LocalDensity.current

    // Auto-scroll: keep playhead visible during playback
    LaunchedEffect(scrollState, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        snapshotFlow { globalPositionMs }
            .map { ms -> with(density) { (ms / msPerDp).dp.toPx() }.toInt() }
            .distinctUntilChanged()
            .collect { playheadPx ->
                val viewportStart = scrollState.value
                val viewportEnd = viewportStart + scrollState.viewportSize
                val margin = with(density) { 60.dp.toPx() }.toInt()

                when {
                    playheadPx > viewportEnd - margin ->
                        scrollState.scrollTo(
                            (playheadPx - scrollState.viewportSize + margin)
                                .coerceAtLeast(0)
                        )
                    playheadPx < viewportStart + margin ->
                        scrollState.scrollTo((playheadPx - margin).coerceAtLeast(0))
                }
            }
    }

    // Drawer animation specs
    val drawerEnter = expandVertically(
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = Spring.StiffnessMedium
        ),
        expandFrom = Alignment.Top
    ) + fadeIn(tween(200, delayMillis = 80))

    val drawerExit = fadeOut(tween(150)) + shrinkVertically(
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = Spring.StiffnessMedium
        )
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Ruler row ──────────────────────────────────────────────
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(HEADER_WIDTH))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState)
            ) {
                Box(Modifier.width(timelineWidthDp)) {
                    TimeRuler(
                        totalDurationMs = totalDurationMs,
                        msPerDp = msPerDp,
                        timelineWidth = timelineWidthDp
                    )

                    // Loop overlay + gesture layer in the ruler
                    if (loopStartMs != null && loopEndMs != null) {
                        LoopOverlaySegment(
                            loopStartMs = loopStartMs,
                            loopEndMs = loopEndMs,
                            isLoopEnabled = isLoopEnabled,
                            msPerDp = msPerDp,
                            height = RULER_HEIGHT,
                            showHandles = true
                        )
                    }

                    LoopRulerGestureLayer(
                        loopStartMs = loopStartMs,
                        loopEndMs = loopEndMs,
                        msPerDp = msPerDp,
                        totalDurationMs = totalDurationMs,
                        timelineWidth = timelineWidthDp,
                        onAction = onAction
                    )

                    PlayheadSegment(
                        globalPositionMs = globalPositionMs,
                        msPerDp = msPerDp,
                        height = RULER_HEIGHT
                    )
                }
            }
        }

        // ── Per-track rows with drawer + take slots ─────────────
        tracks.forEachIndexed { index, track ->
            val isSoloed = track.id in soloedTrackIds
            val anySoloed = soloedTrackIds.isNotEmpty()
            val effectivelyMuted = track.isMuted || (anySoloed && !isSoloed)
            val trackColor = NjTrackColors[index % NjTrackColors.size]
            val isArmed = track.id == armedTrackId
            val takes = trackTakes[track.id] ?: emptyList()
            val takesExpanded = track.id in expandedTakeTrackIds

            // Track row: header + scrollable lane
            Row(Modifier.fillMaxWidth()) {
                TrackHeader(
                    track = track,
                    height = TRACK_LANE_HEIGHT,
                    isExpanded = track.id in expandedTrackIds,
                    isSoloed = isSoloed,
                    isArmed = isArmed,
                    onAction = onAction
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState)
                ) {
                    Box(Modifier.width(timelineWidthDp)) {
                        TimelineTrackLane(
                            track = track,
                            trackColor = trackColor,
                            msPerDp = msPerDp,
                            timelineWidth = timelineWidthDp,
                            laneHeight = TRACK_LANE_HEIGHT,
                            dragState = dragState,
                            trimState = trimState,
                            effectivelyMuted = effectivelyMuted,
                            getAudioFile = getAudioFile,
                            onAction = onAction
                        )

                        if (loopStartMs != null && loopEndMs != null) {
                            LoopOverlaySegment(
                                loopStartMs = loopStartMs,
                                loopEndMs = loopEndMs,
                                isLoopEnabled = isLoopEnabled,
                                msPerDp = msPerDp,
                                height = TRACK_LANE_HEIGHT,
                                showHandles = false
                            )
                        }

                        PlayheadSegment(
                            globalPositionMs = globalPositionMs,
                            msPerDp = msPerDp,
                            height = TRACK_LANE_HEIGHT
                        )
                    }
                }
            }

            // Drawer slot
            AnimatedVisibility(
                visible = track.id in expandedTrackIds,
                enter = drawerEnter,
                exit = drawerExit
            ) {
                TrackDrawerPanel(
                    track = track,
                    isSoloed = isSoloed,
                    isArmed = isArmed,
                    hasTakes = takes.isNotEmpty(),
                    takesExpanded = takesExpanded,
                    onAction = onAction
                )
            }

            // Take rows slot
            AnimatedVisibility(
                visible = takesExpanded && takes.isNotEmpty(),
                enter = drawerEnter,
                exit = drawerExit
            ) {
                Column {
                    takes.forEach { take ->
                        val takeDrawerOpen = take.id in expandedTakeDrawerIds
                        TakeRow(
                            take = take,
                            trackColor = trackColor,
                            msPerDp = msPerDp,
                            timelineWidthDp = timelineWidthDp,
                            scrollState = scrollState,
                            getAudioFile = getAudioFile,
                            onAction = onAction
                        )
                        // Take mini-drawer
                        AnimatedVisibility(
                            visible = takeDrawerOpen,
                            enter = drawerEnter,
                            exit = drawerExit
                        ) {
                            TakeMiniDrawer(
                                take = take,
                                onAction = onAction
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Time ruler drawn on a Canvas — tick marks every 1 s, labels every 5 s. */
@Composable
private fun TimeRuler(
    totalDurationMs: Long,
    msPerDp: Float,
    timelineWidth: Dp
) {
    val textMeasurer = rememberTextMeasurer()
    val rulerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val labelStyle = TextStyle(
        color = rulerColor,
        fontSize = 10.sp
    )

    Canvas(
        modifier = Modifier
            .width(timelineWidth)
            .height(RULER_HEIGHT)
    ) {
        val totalMs = totalDurationMs.coerceAtLeast(1L)

        fun msToX(ms: Long): Float = (ms / msPerDp) * density

        val tickIntervalMs = 1000L
        val labelIntervalMs = 5000L

        var ms = 0L
        while (ms <= totalMs) {
            val x = msToX(ms)
            if (x > size.width) break

            val isLabel = ms % labelIntervalMs == 0L
            val tickHeight = if (isLabel) 12.dp.toPx() else 6.dp.toPx()

            drawLine(
                color = rulerColor,
                start = Offset(x, size.height - tickHeight),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx()
            )

            if (isLabel) {
                val seconds = ms / 1000
                val min = seconds / 60
                val sec = seconds % 60
                val label = "%d:%02d".format(min, sec)
                val textResult = textMeasurer.measure(label, labelStyle)
                drawText(
                    textLayoutResult = textResult,
                    topLeft = Offset(
                        x - textResult.size.width / 2f,
                        size.height - tickHeight - textResult.size.height - 2.dp.toPx()
                    )
                )
            }

            ms += tickIntervalMs
        }
    }
}

/**
 * A single track lane in the timeline — waveform block positioned at
 * [TrackEntity.offsetMs] with drag-to-reposition and trim handle support.
 */
@Composable
private fun TimelineTrackLane(
    track: TrackEntity,
    trackColor: Color,
    msPerDp: Float,
    timelineWidth: Dp,
    laneHeight: Dp,
    dragState: TrackDragState?,
    trimState: TrackTrimState?,
    effectivelyMuted: Boolean,
    getAudioFile: (String) -> File,
    onAction: (StudioAction) -> Unit
) {
    val isDragging = dragState?.trackId == track.id
    val isTrimming = trimState?.trackId == track.id
    val density = LocalDensity.current

    val effectiveOffsetMs = if (isDragging) {
        dragState!!.previewOffsetMs
    } else {
        track.offsetMs
    }

    val effectiveTrimStartMs = if (isTrimming) {
        trimState!!.previewTrimStartMs
    } else {
        track.trimStartMs
    }
    val effectiveTrimEndMs = if (isTrimming) {
        trimState!!.previewTrimEndMs
    } else {
        track.trimEndMs
    }

    val effectiveDurationMs = (track.durationMs - effectiveTrimStartMs - effectiveTrimEndMs)
        .coerceAtLeast(MIN_EFFECTIVE_DURATION_MS)

    // During a left trim, shift the block right so the RIGHT edge stays fixed
    // and the LEFT handle follows the user's finger.
    val trimDelta = effectiveTrimStartMs - track.trimStartMs
    val visualOffsetMs = effectiveOffsetMs + trimDelta
    val offsetDp = (visualOffsetMs / msPerDp).dp
    val widthDp = (effectiveDurationMs / msPerDp).dp

    val bgAlpha = when {
        isDragging -> 0.8f
        effectivelyMuted -> 0.3f
        else -> 0.6f
    }

    // Accumulated drag offset in px for the body drag gesture
    var dragAccumulatedPx by remember { mutableFloatStateOf(0f) }

    // Faster long-press for track drag — 200ms instead of the default 400ms.
    val baseViewConfig = LocalViewConfiguration.current
    val fastViewConfig = remember(baseViewConfig) {
        object : ViewConfiguration by baseViewConfig {
            override val longPressTimeoutMillis: Long get() = FAST_LONG_PRESS_MS
        }
    }

    CompositionLocalProvider(LocalViewConfiguration provides fastViewConfig) {
    Box(
        modifier = Modifier
            .width(timelineWidth)
            .height(laneHeight)
    ) {
        // Waveform block — unified gesture: edges → trim, center → long-press drag
        Box(
            modifier = Modifier
                .offset(x = offsetDp)
                .width(widthDp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(NjStudioLane.copy(alpha = bgAlpha))
                .then(
                    if (isDragging) Modifier
                        .graphicsLayer { shadowElevation = 8f }
                        .alpha(0.85f)
                    else Modifier
                )
                .pointerInput(track.id, track.offsetMs, track.trimStartMs, track.trimEndMs, msPerDp) {
                    val trimZonePx = TRIM_TOUCH_ZONE.toPx()
                        .coerceAtMost(size.width / 3f)

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val touchX = down.position.x

                        when {
                            // ── Left trim zone ──
                            touchX <= trimZonePx -> {
                                down.consume()
                                var trimAccPx = 0f
                                val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                    change.consume()
                                    trimAccPx += over
                                }
                                if (slop != null) {
                                    onAction(StudioAction.StartTrim(track.id, TrimEdge.LEFT))
                                    val completed = horizontalDrag(slop.id) { change ->
                                        val delta = change.positionChange()
                                        change.consume()
                                        trimAccPx += delta.x
                                        val deltaDp = trimAccPx / density.density
                                        val deltaMs = (deltaDp * msPerDp).toLong()
                                        val (newStart, newEnd) = computeTrimValues(TrimEdge.LEFT, track, deltaMs)
                                        onAction(StudioAction.UpdateTrim(newStart, newEnd))
                                    }
                                    val deltaDp = trimAccPx / density.density
                                    val deltaMs = (deltaDp * msPerDp).toLong()
                                    val (newStart, newEnd) = computeTrimValues(TrimEdge.LEFT, track, deltaMs)
                                    if (completed) {
                                        onAction(StudioAction.FinishTrim(track.id, newStart, newEnd))
                                    } else {
                                        onAction(StudioAction.CancelTrim)
                                    }
                                }
                            }

                            // ── Right trim zone ──
                            touchX >= size.width - trimZonePx -> {
                                down.consume()
                                var trimAccPx = 0f
                                val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                    change.consume()
                                    trimAccPx += over
                                }
                                if (slop != null) {
                                    onAction(StudioAction.StartTrim(track.id, TrimEdge.RIGHT))
                                    val completed = horizontalDrag(slop.id) { change ->
                                        val delta = change.positionChange()
                                        change.consume()
                                        trimAccPx += delta.x
                                        val deltaDp = trimAccPx / density.density
                                        val deltaMs = (deltaDp * msPerDp).toLong()
                                        val (newStart, newEnd) = computeTrimValues(TrimEdge.RIGHT, track, deltaMs)
                                        onAction(StudioAction.UpdateTrim(newStart, newEnd))
                                    }
                                    val deltaDp = trimAccPx / density.density
                                    val deltaMs = (deltaDp * msPerDp).toLong()
                                    val (newStart, newEnd) = computeTrimValues(TrimEdge.RIGHT, track, deltaMs)
                                    if (completed) {
                                        onAction(StudioAction.FinishTrim(track.id, newStart, newEnd))
                                    } else {
                                        onAction(StudioAction.CancelTrim)
                                    }
                                }
                            }

                            // ── Center — long-press to reposition ──
                            else -> {
                                // Don't consume down — lets horizontalScroll handle quick swipes
                                val longPress = awaitLongPressOrCancellation(down.id)
                                if (longPress != null) {
                                    dragAccumulatedPx = 0f
                                    onAction(StudioAction.StartDragTrack(track.id))
                                    val completed = drag(longPress.id) { change ->
                                        val delta = change.positionChange()
                                        change.consume()
                                        dragAccumulatedPx += delta.x
                                        val deltaDp = dragAccumulatedPx / density.density
                                        val newOffsetMs = (track.offsetMs + (deltaDp * msPerDp).toLong())
                                            .coerceAtLeast(0L)
                                        onAction(StudioAction.UpdateDragTrack(newOffsetMs))
                                    }
                                    if (completed) {
                                        val deltaDp = dragAccumulatedPx / density.density
                                        val newOffsetMs = (track.offsetMs + (deltaDp * msPerDp).toLong())
                                            .coerceAtLeast(0L)
                                        onAction(StudioAction.FinishDragTrack(track.id, newOffsetMs))
                                    } else {
                                        onAction(StudioAction.CancelDrag)
                                    }
                                    dragAccumulatedPx = 0f
                                }
                            }
                        }
                    }
                }
                .padding(vertical = 4.dp)
        ) {
            val barColor = if (effectivelyMuted) {
                trackColor.copy(alpha = 0.18f)
            } else {
                trackColor.copy(alpha = 0.65f)
            }

            // Show only the trimmed portion of the audio
            val startFrac = if (track.durationMs > 0)
                effectiveTrimStartMs.toFloat() / track.durationMs else 0f
            val endFrac = if (track.durationMs > 0)
                1f - effectiveTrimEndMs.toFloat() / track.durationMs else 1f

            NjWaveform(
                audioFile = getAudioFile(track.audioFileName),
                modifier = Modifier.fillMaxWidth(),
                barColor = barColor,
                height = laneHeight - 8.dp,
                startFraction = startFrac,
                endFraction = endFrac
            )

            // Visual-only trim handles at each edge
            TrimHandle(
                edge = TrimEdge.LEFT,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            TrimHandle(
                edge = TrimEdge.RIGHT,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
    } // CompositionLocalProvider
}

/** Visual trim-handle indicator on the left or right edge of a track. */
@Suppress("UNUSED_PARAMETER")
@Composable
private fun TrimHandle(edge: TrimEdge, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(TRIM_HANDLE_WIDTH)
            .fillMaxHeight()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(NjStudioWaveform.copy(alpha = 0.45f))
    )
}

/** Clamps new trim values based on drag direction, ensuring the track never shrinks below [MIN_EFFECTIVE_DURATION_MS]. */
private fun computeTrimValues(
    edge: TrimEdge,
    track: TrackEntity,
    deltaMs: Long
): Pair<Long, Long> {
    return when (edge) {
        TrimEdge.LEFT -> {
            val maxTrimStart = track.durationMs - track.trimEndMs - MIN_EFFECTIVE_DURATION_MS
            val newStart = (track.trimStartMs + deltaMs)
                .coerceIn(0L, maxTrimStart.coerceAtLeast(0L))
            Pair(newStart, track.trimEndMs)
        }
        TrimEdge.RIGHT -> {
            val maxTrimEnd = track.durationMs - track.trimStartMs - MIN_EFFECTIVE_DURATION_MS
            val newEnd = (track.trimEndMs - deltaMs)
                .coerceIn(0L, maxTrimEnd.coerceAtLeast(0L))
            Pair(track.trimStartMs, newEnd)
        }
    }
}

/**
 * Fixed-width header showing a track's display name, duration, and status.
 * Tap to toggle the inline drawer. Shows visual indicators for expanded,
 * soloed, and muted states.
 */
@Composable
private fun TrackHeader(
    track: TrackEntity,
    height: Dp,
    isExpanded: Boolean,
    isSoloed: Boolean,
    isArmed: Boolean = false,
    onAction: (StudioAction) -> Unit
) {
    val borderColor = when {
        isArmed -> NjRecordCoral.copy(alpha = 0.6f)
        isExpanded -> NjStudioAccent.copy(alpha = 0.5f)
        else -> NjStudioAccent.copy(alpha = 0f)
    }

    Column(
        modifier = Modifier
            .width(HEADER_WIDTH)
            .height(height)
            .drawBehind {
                // Left border: coral when armed, gold when expanded
                if (isArmed || isExpanded) {
                    drawLine(
                        color = borderColor,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = { onAction(StudioAction.OpenTrackSettings(track.id)) }
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = track.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = if (track.isMuted) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            },
            maxLines = 1
        )
        val effectiveDur = track.durationMs - track.trimStartMs - track.trimEndMs
        val totalSeconds = effectiveDur.coerceAtLeast(0L) / 1000
        val min = totalSeconds / 60
        val sec = totalSeconds % 60
        Text(
            text = "%d:%02d".format(min, sec),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
        // Status indicators
        val statusParts = mutableListOf<String>()
        if (isArmed) statusParts.add("Armed")
        if (isSoloed) statusParts.add("Solo")
        if (track.isMuted) statusParts.add("Muted")
        if (statusParts.isNotEmpty()) {
            Text(
                text = statusParts.joinToString(" / "),
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    isArmed -> NjRecordCoral.copy(alpha = 0.7f)
                    isSoloed -> NjStudioAccent.copy(alpha = 0.7f)
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                }
            )
        }
    }
}

/** Rounded placeholder box shown when the timeline has no tracks. */
@Composable
fun TimelinePlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(NjStudioLane.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/** Playhead segment — a gold vertical line for a single row's height. */
@Composable
private fun PlayheadSegment(
    globalPositionMs: Long,
    msPerDp: Float,
    height: Dp
) {
    val offsetDp = (globalPositionMs / msPerDp).dp

    Box(
        modifier = Modifier
            .offset(x = offsetDp)
            .width(2.dp)
            .height(height)
            .background(NjStudioAccent)
    )
}

/**
 * Loop region overlay segment — gold fill and boundary lines for a single
 * row's height. Triangle handles only rendered when [showHandles] is true
 * (ruler row only).
 */
@Composable
private fun LoopOverlaySegment(
    loopStartMs: Long,
    loopEndMs: Long,
    isLoopEnabled: Boolean,
    msPerDp: Float,
    height: Dp,
    showHandles: Boolean
) {
    val startDp = (loopStartMs / msPerDp).dp
    val regionWidthDp = ((loopEndMs - loopStartMs) / msPerDp).dp
    val fillAlpha = if (isLoopEnabled) 0.08f else 0.04f
    val borderAlpha = if (isLoopEnabled) 0.35f else 0.15f
    val handleAlpha = if (isLoopEnabled) 0.7f else 0.35f

    Box(
        modifier = Modifier
            .offset(x = startDp)
            .width(regionWidthDp)
            .height(height)
    ) {
        // Semi-transparent gold fill
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(NjStudioAccent.copy(alpha = fillAlpha))
        )

        // Left boundary line
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(2.dp)
                .fillMaxHeight()
                .background(NjStudioAccent.copy(alpha = borderAlpha))
        )

        // Right boundary line
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(2.dp)
                .fillMaxHeight()
                .background(NjStudioAccent.copy(alpha = borderAlpha))
        )

        // Triangle handle indicators at the top corners (ruler only)
        if (showHandles) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val triW = 10.dp.toPx()
                val triH = 8.dp.toPx()
                val handleColor = NjStudioAccent.copy(alpha = handleAlpha)

                val leftPath = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(triW, 0f)
                    lineTo(0f, triH)
                    close()
                }
                drawPath(leftPath, handleColor)

                val rightPath = Path().apply {
                    moveTo(size.width, 0f)
                    lineTo(size.width - triW, 0f)
                    lineTo(size.width, triH)
                    close()
                }
                drawPath(rightPath, handleColor)
            }
        }
    }
}

/** Drag mode for the loop ruler gesture layer. */
private enum class LoopDragMode { NEW_SELECTION, ADJUST_START, ADJUST_END }

/**
 * Ruler-height gesture layer for all loop interactions. Confined to
 * [RULER_HEIGHT] so it never overlaps track lanes.
 */
@Composable
private fun LoopRulerGestureLayer(
    loopStartMs: Long?,
    loopEndMs: Long?,
    msPerDp: Float,
    totalDurationMs: Long,
    timelineWidth: Dp,
    onAction: (StudioAction) -> Unit
) {
    val density = LocalDensity.current
    val currentStartMs by rememberUpdatedState(loopStartMs)
    val currentEndMs by rememberUpdatedState(loopEndMs)
    val currentTotalMs by rememberUpdatedState(totalDurationMs)
    val currentOnAction by rememberUpdatedState(onAction)

    Box(
        modifier = Modifier
            .width(timelineWidth)
            .height(RULER_HEIGHT)
            .pointerInput(msPerDp) {
                val handleHitZonePx = 32.dp.toPx()

                fun pxToMs(px: Float): Long =
                    ((px / density.density) * msPerDp).toLong()
                        .coerceIn(0L, currentTotalMs)

                fun msToPx(ms: Long): Float =
                    (ms / msPerDp) * density.density

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val touchX = down.position.x

                    val sMs = currentStartMs
                    val eMs = currentEndMs
                    val hasRegion = sMs != null && eMs != null
                    val startPx = if (hasRegion) msToPx(sMs!!) else 0f
                    val endPx = if (hasRegion) msToPx(eMs!!) else 0f

                    val nearStart = hasRegion &&
                            abs(touchX - startPx) <= handleHitZonePx
                    val nearEnd = hasRegion &&
                            abs(touchX - endPx) <= handleHitZonePx

                    val dragMode = when {
                        nearStart && nearEnd -> {
                            if (abs(touchX - startPx) <= abs(touchX - endPx))
                                LoopDragMode.ADJUST_START else LoopDragMode.ADJUST_END
                        }
                        nearStart -> LoopDragMode.ADJUST_START
                        nearEnd -> LoopDragMode.ADJUST_END
                        else -> LoopDragMode.NEW_SELECTION
                    }

                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                    }
                    if (slop != null) {
                        val anchorMs = pxToMs(down.position.x)
                        horizontalDrag(slop.id) { change ->
                            change.consume()
                            val fingerMs = pxToMs(change.position.x)
                            when (dragMode) {
                                LoopDragMode.ADJUST_START ->
                                    currentOnAction(StudioAction.UpdateLoopRegionStart(fingerMs))
                                LoopDragMode.ADJUST_END ->
                                    currentOnAction(StudioAction.UpdateLoopRegionEnd(fingerMs))
                                LoopDragMode.NEW_SELECTION -> {
                                    val leftMs = minOf(anchorMs, fingerMs)
                                    val rightMs = maxOf(anchorMs, fingerMs)
                                    currentOnAction(StudioAction.SetLoopRegion(leftMs, rightMs))
                                }
                            }
                        }
                    }
                }
            }
    )
}

/**
 * A single take row displayed below its parent track when takes are expanded.
 * Shows the take name on the left and a waveform on the right.
 * Tap the header to toggle mute. Long-press the header to open/close
 * the mini-drawer (Rename / Delete).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TakeRow(
    take: TakeEntity,
    trackColor: Color,
    msPerDp: Float,
    timelineWidthDp: Dp,
    scrollState: ScrollState,
    getAudioFile: (String) -> File,
    onAction: (StudioAction) -> Unit
) {
    val effectiveDurationMs = (take.durationMs - take.trimStartMs - take.trimEndMs)
        .coerceAtLeast(MIN_EFFECTIVE_DURATION_MS)
    val offsetDp = (take.offsetMs / msPerDp).dp
    val widthDp = (effectiveDurationMs / msPerDp).dp

    Row(Modifier.fillMaxWidth()) {
        // Take header (left side) -- tap to mute, long-press for drawer
        Column(
            modifier = Modifier
                .width(HEADER_WIDTH)
                .height(TAKE_ROW_HEIGHT)
                .background(NjStudioSurface2.copy(alpha = 0.5f))
                .combinedClickable(
                    onClick = {
                        onAction(
                            StudioAction.SetTakeMuted(
                                take.id,
                                take.trackId,
                                !take.isMuted
                            )
                        )
                    },
                    onLongClick = {
                        onAction(StudioAction.ToggleTakeDrawer(take.id))
                    }
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = take.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = if (take.isMuted) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                },
                maxLines = 1
            )
            if (take.isMuted) {
                Text(
                    text = "Muted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                )
            }
        }

        // Take waveform lane (scrollable, shares scroll state)
        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState)
        ) {
            Box(
                modifier = Modifier
                    .width(timelineWidthDp)
                    .height(TAKE_ROW_HEIGHT)
            ) {
                // Waveform block
                Box(
                    modifier = Modifier
                        .offset(x = offsetDp)
                        .width(widthDp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            NjStudioLane.copy(
                                alpha = if (take.isMuted) 0.2f else 0.45f
                            )
                        )
                        .padding(vertical = 2.dp)
                ) {
                    val barColor = if (take.isMuted) {
                        trackColor.copy(alpha = 0.12f)
                    } else {
                        trackColor.copy(alpha = 0.5f)
                    }

                    val startFrac = if (take.durationMs > 0)
                        take.trimStartMs.toFloat() / take.durationMs else 0f
                    val endFrac = if (take.durationMs > 0)
                        1f - take.trimEndMs.toFloat() / take.durationMs else 1f

                    NjWaveform(
                        audioFile = getAudioFile(take.audioFileName),
                        modifier = Modifier.fillMaxWidth(),
                        barColor = barColor,
                        height = TAKE_ROW_HEIGHT - 4.dp,
                        startFraction = startFrac,
                        endFraction = endFrac
                    )
                }
            }
        }
    }
}

/**
 * Slim mini-drawer for a take -- Rename and Delete buttons.
 * Appears below the take row when toggled via long-press.
 */
@Composable
private fun TakeMiniDrawer(
    take: TakeEntity,
    onAction: (StudioAction) -> Unit
) {
    val goldBorderColor = NjStudioAccent.copy(alpha = 0.3f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .drawBehind {
                drawLine(
                    color = goldBorderColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 0.5.dp.toPx()
                )
            }
            .background(NjStudioSurface2.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        NjStudioButton(
            text = "Rename",
            onClick = {
                onAction(
                    StudioAction.RequestRenameTake(
                        take.id,
                        take.trackId,
                        take.displayName
                    )
                )
            },
            textColor = NjMuted2.copy(alpha = 0.7f)
        )
        Spacer(Modifier.width(8.dp))
        NjStudioButton(
            text = "Delete",
            onClick = {
                onAction(StudioAction.RequestDeleteTake(take.id, take.trackId))
            },
            textColor = NjError.copy(alpha = 0.7f)
        )
    }
}
