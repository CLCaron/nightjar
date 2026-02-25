package com.example.nightjar.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.ui.components.NjWaveform
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjMidnight2
import com.example.nightjar.ui.theme.NjStarlight
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

// ── Layout constants ────────────────────────────────────────────────────
private val HEADER_WIDTH = 100.dp
private val TRACK_LANE_HEIGHT = 56.dp
private val RULER_HEIGHT = 28.dp
private val TIMELINE_END_PADDING_DP = 120.dp
private const val MIN_EFFECTIVE_DURATION_MS = 200L
private val TRIM_HANDLE_WIDTH = 12.dp
private val TRIM_TOUCH_ZONE = 36.dp

/**
 * The main timeline UI — a horizontally scrollable panel showing a time
 * ruler, vertically stacked track lanes with waveforms, and a playhead.
 *
 * Fixed track headers sit on the left; the ruler, waveform blocks, and
 * playhead scroll together horizontally. Auto-scrolls to keep the
 * playhead visible during playback.
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
    getAudioFile: (String) -> File,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val timelineWidthDp = remember(totalDurationMs, msPerDp) {
        (totalDurationMs / msPerDp).dp + TIMELINE_END_PADDING_DP
    }
    val totalTrackHeight = TRACK_LANE_HEIGHT * tracks.size
    val density = LocalDensity.current

    // Auto-scroll: use snapshotFlow to throttle and avoid animation fights
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

    // Single Row: fixed headers on left, one scrollable area on right
    Row(modifier = modifier.fillMaxWidth()) {

        // Fixed header column — ruler spacer + track headers
        Column(modifier = Modifier.width(HEADER_WIDTH)) {
            Spacer(Modifier.height(RULER_HEIGHT))
            tracks.forEach { track ->
                TrackHeader(track = track, height = TRACK_LANE_HEIGHT, onAction = onAction)
            }
        }

        // Single horizontally-scrollable area for ruler + tracks + playhead
        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState)
        ) {
            // Content column: ruler on top, track lanes below
            Column(modifier = Modifier.width(timelineWidthDp)) {
                TimeRuler(
                    totalDurationMs = totalDurationMs,
                    msPerDp = msPerDp,
                    timelineWidth = timelineWidthDp
                )

                tracks.forEach { track ->
                    TimelineTrackLane(
                        track = track,
                        msPerDp = msPerDp,
                        timelineWidth = timelineWidthDp,
                        laneHeight = TRACK_LANE_HEIGHT,
                        dragState = dragState,
                        trimState = trimState,
                        getAudioFile = getAudioFile,
                        onAction = onAction
                    )
                }
            }

            // Playhead spans from ruler top to bottom of last track
            PlayheadLine(
                globalPositionMs = globalPositionMs,
                msPerDp = msPerDp,
                height = RULER_HEIGHT + totalTrackHeight
            )
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

        // Convert milliseconds → pixel x-position.
        // dp = ms / msPerDp, px = dp * density
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
    msPerDp: Float,
    timelineWidth: Dp,
    laneHeight: Dp,
    dragState: TrackDragState?,
    trimState: TrackTrimState?,
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
        track.isMuted -> 0.3f
        else -> 0.6f
    }

    // Accumulated drag offset in px for the body drag gesture
    var dragAccumulatedPx by remember { mutableFloatStateOf(0f) }

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
                .background(NjMidnight2.copy(alpha = bgAlpha))
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
            val barColor = if (track.isMuted) {
                NjStarlight.copy(alpha = 0.18f)
            } else {
                NjStarlight.copy(alpha = 0.65f)
            }

            // Show only the trimmed portion of the audio — peaks stay at
            // correct timeline positions without any width or offset tricks.
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
            .background(NjStarlight.copy(alpha = 0.45f))
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
            // Dragging right handle left (negative delta) increases trimEnd
            val maxTrimEnd = track.durationMs - track.trimStartMs - MIN_EFFECTIVE_DURATION_MS
            val newEnd = (track.trimEndMs - deltaMs)
                .coerceIn(0L, maxTrimEnd.coerceAtLeast(0L))
            Pair(track.trimStartMs, newEnd)
        }
    }
}

/** Fixed-width header showing a track's display name, duration, and mute status. Tap to open settings. */
@Composable
private fun TrackHeader(
    track: TrackEntity,
    height: Dp,
    onAction: (StudioAction) -> Unit
) {
    Column(
        modifier = Modifier
            .width(HEADER_WIDTH)
            .height(height)
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
        if (track.isMuted) {
            Text(
                text = "Muted",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
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
            .background(NjMidnight2.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/** Vertical white line indicating the current playback position on the timeline. */
@Composable
private fun PlayheadLine(
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
            .background(NjAccent)
    )
}
