package com.example.nightjar.ui.studio

import com.example.nightjar.ui.components.NjButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.example.nightjar.audio.MusicalTimeConverter
import com.example.nightjar.data.db.entity.MidiNoteEntity
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.ui.components.NjWaveform
import com.example.nightjar.ui.theme.NjRecordCoral
import com.example.nightjar.ui.theme.NjError
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjStudioAccent
import com.example.nightjar.ui.theme.NjStudioLane
import com.example.nightjar.ui.theme.NjSurface2
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
    isRecording: Boolean = false,
    liveAmplitudes: FloatArray = FloatArray(0),
    recordingStartGlobalMs: Long? = null,
    recordingTargetTrackId: Long? = null,
    recordingElapsedMs: Long = 0L,
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
    drumPatterns: Map<Long, DrumPatternUiState> = emptyMap(),
    midiTracks: Map<Long, MidiTrackUiState> = emptyMap(),
    clipDragState: ClipDragState? = null,
    midiClipDragState: MidiClipDragState? = null,
    expandedClipState: ExpandedClipState? = null,
    bpm: Double = 120.0,
    timeSignatureNumerator: Int = 4,
    timeSignatureDenominator: Int = 4,
    isSnapEnabled: Boolean = true,
    gridResolution: Int = 16,
    getAudioFile: (String) -> File,
    onAction: (StudioAction) -> Unit,
    onScrub: (Long) -> Unit = {},
    onScrubFinished: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val timelineWidthDp = remember(totalDurationMs, msPerDp) {
        (totalDurationMs / msPerDp).dp + TIMELINE_END_PADDING_DP
    }
    val density = LocalDensity.current

    // Auto-scroll: keep playhead/recording edge visible during playback or recording
    LaunchedEffect(scrollState, isPlaying, isRecording) {
        if (!isPlaying && !isRecording) return@LaunchedEffect
        snapshotFlow { globalPositionMs }
            .map { ms -> with(density) { (ms / msPerDp).dp.toPx() }.toInt() }
            .distinctUntilChanged()
            .collect { edgePx ->
                val viewportStart = scrollState.value
                val viewportEnd = viewportStart + scrollState.viewportSize
                val margin = with(density) { 60.dp.toPx() }.toInt()

                when {
                    edgePx > viewportEnd - margin ->
                        scrollState.scrollTo(
                            (edgePx - scrollState.viewportSize + margin)
                                .coerceAtLeast(0)
                        )
                    edgePx < viewportStart + margin ->
                        scrollState.scrollTo((edgePx - margin).coerceAtLeast(0))
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
                        timelineWidth = timelineWidthDp,
                        bpm = bpm,
                        timeSignatureNumerator = timeSignatureNumerator,
                        timeSignatureDenominator = timeSignatureDenominator,
                        gridResolution = gridResolution
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

                    RulerGestureLayer(
                        loopStartMs = loopStartMs,
                        loopEndMs = loopEndMs,
                        isRecording = isRecording,
                        msPerDp = msPerDp,
                        totalDurationMs = totalDurationMs,
                        timelineWidth = timelineWidthDp,
                        onScrub = onScrub,
                        onScrubFinished = onScrubFinished,
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
                    Box(
                        Modifier
                            .width(timelineWidthDp)
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    onAction(StudioAction.DismissClipPanel)
                                }
                            }
                    ) {
                        // Beat grid lines (behind track content)
                        if (isSnapEnabled) {
                            BeatGridOverlay(
                                totalDurationMs = totalDurationMs,
                                msPerDp = msPerDp,
                                bpm = bpm,
                                timeSignatureNumerator = timeSignatureNumerator,
                                timeSignatureDenominator = timeSignatureDenominator,
                                gridResolution = gridResolution,
                                timelineWidth = timelineWidthDp,
                                laneHeight = TRACK_LANE_HEIGHT
                            )
                        }
                        when {
                            track.isDrum -> {
                                val drumPattern = drumPatterns[track.id]
                                DrumTrackLane(
                                    track = track,
                                    trackColor = trackColor,
                                    msPerDp = msPerDp,
                                    bpm = bpm,
                                    pattern = drumPattern,
                                    clipDragState = clipDragState,
                                    expandedClipState = expandedClipState,
                                    timelineWidth = timelineWidthDp,
                                    laneHeight = TRACK_LANE_HEIGHT,
                                    effectivelyMuted = effectivelyMuted,
                                    onAction = onAction,
                                    timeSignatureNumerator = timeSignatureNumerator,
                                    timeSignatureDenominator = timeSignatureDenominator
                                )
                            }
                            track.isMidi -> {
                                val midiState = midiTracks[track.id]
                                MidiTrackLane(
                                    track = track,
                                    trackColor = trackColor,
                                    msPerDp = msPerDp,
                                    clips = midiState?.clips ?: emptyList(),
                                    midiClipDragState = midiClipDragState,
                                    expandedClipState = expandedClipState,
                                    bpm = bpm,
                                    timelineWidth = timelineWidthDp,
                                    laneHeight = TRACK_LANE_HEIGHT,
                                    effectivelyMuted = effectivelyMuted,
                                    onAction = onAction,
                                    timeSignatureNumerator = timeSignatureNumerator,
                                    timeSignatureDenominator = timeSignatureDenominator
                                )
                            }
                            else -> {
                                TimelineTrackLane(
                                    track = track,
                                    trackColor = trackColor,
                                    msPerDp = msPerDp,
                                    timelineWidth = timelineWidthDp,
                                    laneHeight = TRACK_LANE_HEIGHT,
                                    dragState = dragState,
                                    trimState = trimState,
                                    expandedClipState = expandedClipState,
                                    effectivelyMuted = effectivelyMuted,
                                    getAudioFile = getAudioFile,
                                    onAction = onAction
                                )
                            }
                        }

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
                when {
                    track.isDrum -> {
                        val drumPattern = drumPatterns[track.id]
                        DrumTrackDrawer(
                            track = track,
                            isSoloed = isSoloed,
                            pattern = drumPattern,
                            bpm = bpm,
                            onAction = onAction,
                            beatsPerBar = timeSignatureNumerator,
                            timeSignatureNumerator = timeSignatureNumerator,
                            timeSignatureDenominator = timeSignatureDenominator
                        )
                    }
                    track.isMidi -> {
                        val midiState = midiTracks[track.id]
                        MidiTrackDrawer(
                            track = track,
                            trackIndex = index,
                            isSoloed = isSoloed,
                            midiState = midiState,
                            bpm = bpm,
                            timeSignatureNumerator = timeSignatureNumerator,
                            timeSignatureDenominator = timeSignatureDenominator,
                            isSnapEnabled = isSnapEnabled,
                            gridResolution = gridResolution,
                            onAction = onAction
                        )
                    }
                    else -> {
                        TrackDrawerPanel(
                            track = track,
                            isSoloed = isSoloed,
                            isArmed = isArmed,
                            hasTakes = takes.isNotEmpty(),
                            takesExpanded = takesExpanded,
                            onAction = onAction
                        )
                    }
                }
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

            // Live recording take row below armed track
            if (isRecording && recordingTargetTrackId == track.id &&
                recordingStartGlobalMs != null
            ) {
                RecordingLaneRow(
                    liveAmplitudes = liveAmplitudes,
                    recordingStartGlobalMs = recordingStartGlobalMs,
                    recordingElapsedMs = recordingElapsedMs,
                    globalPositionMs = globalPositionMs,
                    msPerDp = msPerDp,
                    timelineWidthDp = timelineWidthDp,
                    scrollState = scrollState
                )
            }
        }

        // Phantom recording row when creating a new track (no armed track)
        if (isRecording && recordingTargetTrackId == null &&
            recordingStartGlobalMs != null
        ) {
            RecordingTrackRow(
                liveAmplitudes = liveAmplitudes,
                recordingStartGlobalMs = recordingStartGlobalMs,
                recordingElapsedMs = recordingElapsedMs,
                globalPositionMs = globalPositionMs,
                msPerDp = msPerDp,
                timelineWidthDp = timelineWidthDp,
                scrollState = scrollState
            )
        }
    }
}

/** Time ruler drawn on a Canvas — tick marks every 1 s, labels every 5 s. */
@Composable
private fun TimeRuler(
    totalDurationMs: Long,
    msPerDp: Float,
    timelineWidth: Dp,
    bpm: Double = 120.0,
    timeSignatureNumerator: Int = 4,
    timeSignatureDenominator: Int = 4,
    gridResolution: Int = 16
) {
    val textMeasurer = rememberTextMeasurer()
    val rulerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val subBeatColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
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

        fun msToX(ms: Double): Float = ((ms / msPerDp) * density).toFloat()

        val beatMs = MusicalTimeConverter.msPerBeat(bpm, timeSignatureDenominator)
        val measureMs = MusicalTimeConverter.msPerMeasure(
            bpm, timeSignatureNumerator, timeSignatureDenominator
        )

        if (beatMs <= 0.0 || measureMs <= 0.0) return@Canvas

        // Calculate pixel spacing for beats to decide density
        val beatPx = msToX(beatMs)
        val showBeatTicks = beatPx >= 4f

        // Grid step calculations for sub-beat ticks
        val gridStepMs = MusicalTimeConverter.msPerGridStep(bpm, gridResolution, timeSignatureDenominator)
        val gridStepPx = if (gridStepMs > 0.0) msToX(gridStepMs) else 0f
        val showGridTicks = gridStepPx >= 4f && gridStepMs < beatMs

        // Draw measure and beat ticks
        var ms = 0.0
        var measureNumber = 1
        while (ms <= totalMs) {
            val x = msToX(ms)
            if (x > size.width) break

            // Measure tick + label
            drawLine(
                color = rulerColor,
                start = Offset(x, size.height - 12.dp.toPx()),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx()
            )

            val label = measureNumber.toString()
            val textResult = textMeasurer.measure(label, labelStyle)
            drawText(
                textLayoutResult = textResult,
                topLeft = Offset(
                    x - textResult.size.width / 2f,
                    size.height - 12.dp.toPx() - textResult.size.height - 2.dp.toPx()
                )
            )

            // Beat ticks within this measure
            if (showBeatTicks) {
                for (beat in 1 until timeSignatureNumerator) {
                    val beatX = msToX(ms + beat * beatMs)
                    if (beatX > size.width) break

                    drawLine(
                        color = rulerColor,
                        start = Offset(beatX, size.height - 6.dp.toPx()),
                        end = Offset(beatX, size.height),
                        strokeWidth = 0.5.dp.toPx()
                    )
                }

                // Sub-beat ticks at grid resolution within this measure
                if (showGridTicks) {
                    var stepMs = gridStepMs
                    while (stepMs < measureMs - 0.5) {
                        // Skip positions that fall on beat boundaries
                        val beatFrac = stepMs / beatMs
                        val isBeat = (beatFrac - beatFrac.toLong()).let { it < 0.01 || it > 0.99 }
                        if (!isBeat) {
                            val stepX = msToX(ms + stepMs)
                            if (stepX > size.width) break
                            drawLine(
                                color = subBeatColor,
                                start = Offset(stepX, size.height - 3.dp.toPx()),
                                end = Offset(stepX, size.height),
                                strokeWidth = 0.5.dp.toPx()
                            )
                        }
                        stepMs += gridStepMs
                    }
                }
            }

            ms += measureMs
            measureNumber++
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
    expandedClipState: ExpandedClipState?,
    effectivelyMuted: Boolean,
    getAudioFile: (String) -> File,
    onAction: (StudioAction) -> Unit
) {
    val isDragging = dragState?.trackId == track.id
    val isTrimming = trimState?.trackId == track.id
    val isExpanded = expandedClipState?.trackId == track.id &&
        expandedClipState.clipId == track.id
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
        // Waveform block — unified gesture: edges → trim, center → long-press drag / short tap
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

                            // ── Center — short tap to select, long-press to reposition ──
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
                                } else {
                                    // Short tap -- only if finger didn't travel (wasn't a scroll)
                                    val upChange = currentEvent.changes.firstOrNull { it.id == down.id }
                                    val dist = upChange?.let { (it.position - down.position).getDistance() } ?: 0f
                                    if (dist < viewConfiguration.touchSlop) {
                                        onAction(StudioAction.TapClip(track.id, track.id, "audio"))
                                    }
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

            FlippableClip(
                isFlipped = isExpanded,
                modifier = Modifier.matchParentSize(),
                front = {
                    Box(Modifier.fillMaxSize()) {
                        if (track.audioFileName != null) {
                            NjWaveform(
                                audioFile = getAudioFile(track.audioFileName),
                                modifier = Modifier.fillMaxWidth(),
                                barColor = barColor,
                                height = laneHeight - 8.dp,
                                startFraction = startFrac,
                                endFraction = endFrac
                            )
                        }

                        // Visual-only trim handles at each edge
                        TrimHandle(modifier = Modifier.align(Alignment.CenterStart))
                        TrimHandle(modifier = Modifier.align(Alignment.CenterEnd))
                    }
                },
                back = {
                    ClipActionButtons(
                        onDuplicate = { /* no duplicate for audio tracks yet */ },
                        onDelete = {
                            onAction(StudioAction.ConfirmDeleteTrack(track.id))
                        },
                        onDismiss = { onAction(StudioAction.DismissClipPanel) }
                    )
                }
            )
        }
    }
    } // CompositionLocalProvider
}

/**
 * Compact MIDI track lane in the timeline. Shows small horizontal bars
 * at pitch-proportional Y positions. Tap to open piano roll.
 */
/**
 * MIDI track lane showing clip blocks on the timeline.
 * Each clip is a rounded rectangle containing mini note bars.
 * Long-press a clip to drag-to-reposition. Tap to open piano roll.
 */
@Composable
private fun MidiTrackLane(
    track: TrackEntity,
    trackColor: Color,
    msPerDp: Float,
    clips: List<MidiClipUiState>,
    midiClipDragState: MidiClipDragState? = null,
    expandedClipState: ExpandedClipState? = null,
    bpm: Double,
    timelineWidth: Dp,
    laneHeight: Dp,
    effectivelyMuted: Boolean,
    onAction: (StudioAction) -> Unit = {},
    timeSignatureNumerator: Int = 4,
    timeSignatureDenominator: Int = 4
) {
    val density = LocalDensity.current
    val mutedAlpha = if (effectivelyMuted) 0.35f else 1.0f
    val bgAlpha = if (effectivelyMuted) 0.25f else 0.5f

    // Minimum clip width = one measure
    val measureMs = com.example.nightjar.audio.MusicalTimeConverter
        .msPerMeasure(bpm, timeSignatureNumerator, timeSignatureDenominator).toLong()

    // Faster long-press for clip drag -- 200ms instead of the default 400ms
    val baseViewConfig = LocalViewConfiguration.current
    val fastViewConfig = remember(baseViewConfig) {
        object : ViewConfiguration by baseViewConfig {
            override val longPressTimeoutMillis: Long get() = FAST_LONG_PRESS_MS
        }
    }

    CompositionLocalProvider(LocalViewConfiguration provides fastViewConfig) {
    Box(
        modifier = Modifier
            .height(laneHeight)
            .width(timelineWidth)
            .alpha(mutedAlpha)
    ) {
        for (clip in clips) {
            val isDragging = midiClipDragState != null &&
                midiClipDragState.clipId == clip.clipId &&
                midiClipDragState.trackId == track.id
            val isExpanded = expandedClipState?.trackId == track.id &&
                expandedClipState.clipId == clip.clipId
            val displayOffsetMs = if (isDragging) midiClipDragState!!.previewOffsetMs else clip.offsetMs

            val clipDurationMs = clip.contentDurationMs.coerceAtLeast(measureMs)
            val offsetDp = (displayOffsetMs / msPerDp).dp
            val widthDp = (clipDurationMs / msPerDp).dp.coerceAtLeast(40.dp)

            var dragAccumulatedPx by remember { mutableFloatStateOf(0f) }

            Box(
                modifier = Modifier
                    .offset(x = offsetDp)
                    .width(widthDp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(trackColor.copy(alpha = bgAlpha))
                    .then(
                        if (isDragging) Modifier
                            .graphicsLayer { shadowElevation = 8f }
                            .alpha(0.85f)
                        else Modifier
                    )
                    .pointerInput(track.id, clip.clipId, clip.offsetMs, msPerDp) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val longPress = awaitLongPressOrCancellation(down.id)
                            if (longPress != null) {
                                dragAccumulatedPx = 0f
                                onAction(StudioAction.StartDragMidiClip(track.id, clip.clipId))
                                val completed = drag(longPress.id) { change ->
                                    val delta = change.positionChange()
                                    change.consume()
                                    dragAccumulatedPx += delta.x
                                    val deltaDp = dragAccumulatedPx / density.density
                                    val newOffsetMs = (clip.offsetMs + (deltaDp * msPerDp).toLong())
                                        .coerceAtLeast(0L)
                                    onAction(StudioAction.UpdateDragMidiClip(newOffsetMs))
                                }
                                if (completed) {
                                    val deltaDp = dragAccumulatedPx / density.density
                                    val newOffsetMs = (clip.offsetMs + (deltaDp * msPerDp).toLong())
                                        .coerceAtLeast(0L)
                                    onAction(
                                        StudioAction.FinishDragMidiClip(track.id, clip.clipId, newOffsetMs)
                                    )
                                } else {
                                    onAction(StudioAction.CancelDragMidiClip)
                                }
                                dragAccumulatedPx = 0f
                            } else {
                                // Short tap -- only if finger didn't travel (wasn't a scroll)
                                val upChange = currentEvent.changes.firstOrNull { it.id == down.id }
                                val dist = upChange?.let { (it.position - down.position).getDistance() } ?: 0f
                                if (dist < viewConfiguration.touchSlop) {
                                    onAction(StudioAction.TapClip(track.id, clip.clipId, "midi"))
                                }
                            }
                        }
                    }
                    .padding(vertical = 2.dp)
            ) {
                FlippableClip(
                    isFlipped = isExpanded,
                    modifier = Modifier.matchParentSize(),
                    front = {
                        // Draw mini note bars inside the clip
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val notes = clip.notes
                            if (notes.isEmpty()) return@Canvas

                            val pitchRange = notes.minOf { it.pitch }..notes.maxOf { it.pitch }
                            val pitchSpan = (pitchRange.last - pitchRange.first).coerceAtLeast(1)
                            val laneH = size.height
                            val clipWidthPx = size.width
                            val barHeight = (laneH / (pitchSpan + 2)).coerceIn(2f, 5f)

                            val clipContentMs = clipDurationMs.toFloat()
                            if (clipContentMs <= 0f) return@Canvas

                            for (note in notes) {
                                val noteX = (note.startMs.toFloat() / clipContentMs) * clipWidthPx
                                val noteW = ((note.durationMs.toFloat() / clipContentMs) * clipWidthPx)
                                    .coerceAtLeast(2f)
                                val normalizedPitch = (note.pitch - pitchRange.first).toFloat() / pitchSpan
                                val y = laneH - (normalizedPitch * (laneH - barHeight)) - barHeight

                                drawRect(
                                    color = trackColor,
                                    topLeft = Offset(noteX, y),
                                    size = Size(noteW, barHeight)
                                )
                            }
                        }
                    },
                    back = {
                        ClipActionButtons(
                            onDuplicate = { onAction(StudioAction.DuplicateMidiClip(track.id, clip.clipId)) },
                            onDelete = { onAction(StudioAction.DeleteMidiClip(track.id, clip.clipId)) },
                            onEdit = { onAction(StudioAction.OpenPianoRoll(track.id, clip.clipId)) },
                            onDismiss = { onAction(StudioAction.DismissClipPanel) }
                        )
                    }
                )
            }
        }

        // "+" buttons in gaps between clips
        if (clips.isNotEmpty()) {
            val clipBounds = clips.map { clip ->
                val dur = clip.contentDurationMs.coerceAtLeast(measureMs)
                clip.offsetMs to clip.offsetMs + dur
            }
            val timelineEndMs = (with(density) { timelineWidth.toPx() } * msPerDp / density.density).toLong()
            val gaps = computeGaps(clipBounds, timelineEndMs)
            val minGapDp = 40.dp
            for ((gapStart, gapEnd) in gaps) {
                val gapWidthDp = ((gapEnd - gapStart) / msPerDp).dp
                if (gapWidthDp >= minGapDp) {
                    val gapOffsetDp = (gapStart / msPerDp).dp
                    Box(
                        modifier = Modifier
                            .offset(x = gapOffsetDp)
                            .width(gapWidthDp)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onAction(StudioAction.CreateMidiClip(track.id, gapStart)) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add clip",
                            tint = NjMuted2.copy(alpha = 0.25f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
    } // CompositionLocalProvider
}

/**
 * Compact drum track lane in the timeline. Shows colored clip blocks with
 * small step indicators. Each clip is a separate block positioned at its
 * offset. Width is computed from pattern length and BPM.
 * Long-press a clip to drag-to-reposition it on the timeline.
 */
@Composable
private fun DrumTrackLane(
    track: TrackEntity,
    trackColor: Color,
    msPerDp: Float,
    bpm: Double,
    pattern: DrumPatternUiState?,
    clipDragState: ClipDragState? = null,
    expandedClipState: ExpandedClipState? = null,
    timelineWidth: Dp,
    laneHeight: Dp,
    effectivelyMuted: Boolean,
    onAction: (StudioAction) -> Unit = {},
    timeSignatureNumerator: Int = 4,
    timeSignatureDenominator: Int = 4
) {
    val density = LocalDensity.current
    val beatsPerBar = timeSignatureNumerator
    val bgAlpha = if (effectivelyMuted) 0.25f else 0.5f

    // Build a map: drumNote -> rowIndex for per-instrument rendering
    val noteToRowIndex = remember {
        GM_DRUM_ROWS.withIndex().associate { (idx, row) -> row.note to idx }
    }

    // Use clips if available, otherwise fall back to single clip at track offset
    val clips = pattern?.clips ?: emptyList()
    val clipOffsets = if (clips.isNotEmpty()) {
        clips
    } else {
        listOf(DrumClipUiState(clipId = -1, offsetMs = track.offsetMs))
    }

    // Faster long-press for clip drag -- 200ms instead of the default 400ms
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
        clipOffsets.forEach { clip ->
            // Per-clip pattern data
            val clipStepsPerBar = clip.stepsPerBar
            val clipBars = clip.bars
            val clipTotalSteps = clipStepsPerBar * clipBars
            val clipStepsPerBeat = if (beatsPerBar > 0) clipStepsPerBar / beatsPerBar else 4
            val clipPatternDurationMs = com.example.nightjar.audio.MusicalTimeConverter
                .msPerMeasure(bpm, timeSignatureNumerator, timeSignatureDenominator) * clipBars
            val clipWidthDp = (clipPatternDurationMs / msPerDp).dp.coerceAtLeast(40.dp)

            val clipStepHits = remember(clip.steps) {
                val map = mutableMapOf<Int, MutableList<Int>>()
                clip.steps.forEach { step ->
                    val rowIdx = noteToRowIndex[step.drumNote] ?: return@forEach
                    map.getOrPut(step.stepIndex) { mutableListOf() }.add(rowIdx)
                }
                map
            }

            val isDragging = clipDragState?.trackId == track.id &&
                    clipDragState.clipId == clip.clipId
            val isExpanded = expandedClipState?.trackId == track.id &&
                    expandedClipState.clipId == clip.clipId
            val effectiveOffsetMs = if (isDragging) {
                clipDragState!!.previewOffsetMs
            } else {
                clip.offsetMs
            }
            val offsetDp = (effectiveOffsetMs / msPerDp).dp

            var dragAccumulatedPx by remember { mutableFloatStateOf(0f) }

            Box(
                modifier = Modifier
                    .offset(x = offsetDp)
                    .width(clipWidthDp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(NjStudioLane.copy(alpha = if (isDragging) 0.8f else bgAlpha))
                    .then(
                        if (isDragging) Modifier
                            .graphicsLayer { shadowElevation = 8f }
                            .alpha(0.85f)
                        else Modifier
                    )
                    .pointerInput(track.id, clip.clipId, clip.offsetMs, msPerDp) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val longPress = awaitLongPressOrCancellation(down.id)
                            if (longPress != null) {
                                dragAccumulatedPx = 0f
                                onAction(StudioAction.StartDragClip(track.id, clip.clipId))
                                val completed = drag(longPress.id) { change ->
                                    val delta = change.positionChange()
                                    change.consume()
                                    dragAccumulatedPx += delta.x
                                    val deltaDp = dragAccumulatedPx / density.density
                                    val newOffsetMs = (clip.offsetMs + (deltaDp * msPerDp).toLong())
                                        .coerceAtLeast(0L)
                                    onAction(StudioAction.UpdateDragClip(newOffsetMs))
                                }
                                if (completed) {
                                    val deltaDp = dragAccumulatedPx / density.density
                                    val newOffsetMs = (clip.offsetMs + (deltaDp * msPerDp).toLong())
                                        .coerceAtLeast(0L)
                                    onAction(
                                        StudioAction.FinishDragClip(track.id, clip.clipId, newOffsetMs)
                                    )
                                } else {
                                    onAction(StudioAction.CancelDragClip)
                                }
                                dragAccumulatedPx = 0f
                            } else {
                                // Short tap -- only if finger didn't travel (wasn't a scroll)
                                val upChange = currentEvent.changes.firstOrNull { it.id == down.id }
                                val dist = upChange?.let { (it.position - down.position).getDistance() } ?: 0f
                                if (dist < viewConfiguration.touchSlop) {
                                    onAction(StudioAction.TapClip(track.id, clip.clipId, "drum"))
                                }
                            }
                        }
                    }
                    .padding(vertical = 4.dp)
            ) {
                FlippableClip(
                    isFlipped = isExpanded,
                    modifier = Modifier.matchParentSize(),
                    front = {
                        // Mini step grid -- per-instrument colored dots
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val canvasW = size.width
                            val canvasH = size.height
                            val numRows = GM_DRUM_ROWS.size
                            val stepW = canvasW / clipTotalSteps.coerceAtLeast(1)
                            val rowH = canvasH / numRows
                            val dotRadius = minOf(rowH, stepW) * 0.35f

                            // Draw active hits as colored dots
                            for ((stepIdx, rowIndices) in clipStepHits) {
                                if (stepIdx >= clipTotalSteps) continue
                                val cx = stepIdx * stepW + stepW / 2f
                                rowIndices.forEach { rowIdx ->
                                    val cy = rowIdx * rowH + rowH / 2f
                                    val dotColor = if (effectivelyMuted) {
                                        DRUM_ROW_COLORS[rowIdx].copy(alpha = 0.3f)
                                    } else {
                                        DRUM_ROW_COLORS[rowIdx].copy(alpha = 0.9f)
                                    }
                                    drawCircle(
                                        color = dotColor,
                                        radius = dotRadius,
                                        center = Offset(cx, cy)
                                    )
                                }
                            }

                            // Beat dividers (every stepsPerBeat steps)
                            val dividerStep = clipStepsPerBeat.coerceAtLeast(1)
                            for (s in 0..clipTotalSteps step dividerStep) {
                                if (s == 0) continue
                                val x = s * stepW
                                val isBar = s % clipStepsPerBar == 0
                                drawLine(
                                    color = NjStudioWaveform.copy(
                                        alpha = if (isBar) 0.3f else 0.12f
                                    ),
                                    start = Offset(x, 0f),
                                    end = Offset(x, canvasH),
                                    strokeWidth = if (isBar) 1.dp.toPx() else 0.5.dp.toPx()
                                )
                            }
                        }
                    },
                    back = {
                        ClipActionButtons(
                            onDuplicate = { onAction(StudioAction.DuplicateClip(track.id, clip.clipId)) },
                            onDelete = { onAction(StudioAction.DeleteClip(track.id, clip.clipId)) },
                            onEdit = { onAction(StudioAction.OpenDrumEditor(track.id, clip.clipId)) },
                            onDismiss = { onAction(StudioAction.DismissClipPanel) }
                        )
                    }
                )
            }
        }

        // "+" buttons in gaps between clips
        if (clips.isNotEmpty()) {
            val clipBounds = clipOffsets.map { clip ->
                val dur = com.example.nightjar.audio.MusicalTimeConverter
                    .msPerMeasure(bpm, timeSignatureNumerator, timeSignatureDenominator) * clip.bars
                clip.offsetMs to clip.offsetMs + dur.toLong()
            }
            val timelineEndMs = (with(density) { timelineWidth.toPx() } * msPerDp / density.density).toLong()
            val gaps = computeGaps(clipBounds, timelineEndMs)
            val minGapDp = 40.dp
            for ((gapStart, gapEnd) in gaps) {
                val gapWidthDp = ((gapEnd - gapStart) / msPerDp).dp
                if (gapWidthDp >= minGapDp) {
                    val gapOffsetDp = (gapStart / msPerDp).dp
                    Box(
                        modifier = Modifier
                            .offset(x = gapOffsetDp)
                            .width(gapWidthDp)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onAction(StudioAction.CreateDrumClip(track.id, gapStart)) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add clip",
                            tint = NjMuted2.copy(alpha = 0.25f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
    } // CompositionLocalProvider
}

/**
 * Compute gaps between clips where a "+" button can be placed.
 * Returns a list of (gapStartMs, gapEndMs) pairs.
 */
private fun computeGaps(
    clipBounds: List<Pair<Long, Long>>,
    timelineEndMs: Long
): List<Pair<Long, Long>> {
    val sorted = clipBounds.sortedBy { it.first }
    val gaps = mutableListOf<Pair<Long, Long>>()
    var cursor = 0L
    for ((start, end) in sorted) {
        if (start > cursor) gaps.add(cursor to start)
        cursor = maxOf(cursor, end)
    }
    if (cursor < timelineEndMs) gaps.add(cursor to timelineEndMs)
    return gaps
}

/**
 * X-axis card-flip animation wrapper. Front content rotates away (top
 * tilts back), revealing icon action buttons on the back face.
 */
@Composable
private fun FlippableClip(
    isFlipped: Boolean,
    modifier: Modifier = Modifier,
    front: @Composable () -> Unit,
    back: @Composable () -> Unit
) {
    val angle by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(300),
        label = "clipFlip"
    )
    Box(
        modifier = modifier.graphicsLayer {
            rotationX = angle
            cameraDistance = 12f * density
        }
    ) {
        if (angle < 90f) {
            front()
        } else {
            Box(Modifier.graphicsLayer { rotationX = 180f }) {
                back()
            }
        }
    }
}

/**
 * Icon-only action buttons shown on the back face of a flipped clip.
 * Duplicate (amber), optional Edit (silver), Delete (red).
 */
@Composable
private fun ClipActionButtons(
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NjButton(
            text = "",
            icon = Icons.Filled.ContentCopy,
            onClick = { onDuplicate(); onDismiss() },
            textColor = NjStudioAccent.copy(alpha = 0.9f)
        )
        Spacer(Modifier.width(12.dp))
        if (onEdit != null) {
            NjButton(
                text = "",
                icon = Icons.Filled.Edit,
                onClick = { onEdit(); onDismiss() },
                textColor = NjStudioWaveform.copy(alpha = 0.9f)
            )
            Spacer(Modifier.width(12.dp))
        }
        NjButton(
            text = "",
            icon = Icons.Filled.Delete,
            onClick = { onDelete(); onDismiss() },
            textColor = NjError.copy(alpha = 0.9f)
        )
    }
}

/**
 * Subtle vertical grid lines at beat boundaries, drawn behind track content.
 * Only visible when snap-to-grid is enabled.
 */
@Composable
private fun BeatGridOverlay(
    totalDurationMs: Long,
    msPerDp: Float,
    bpm: Double,
    timeSignatureNumerator: Int,
    timeSignatureDenominator: Int,
    gridResolution: Int = 16,
    timelineWidth: Dp,
    laneHeight: Dp
) {
    val subBeatGridColor = Color.White.copy(alpha = 0.025f)
    val gridColor = Color.White.copy(alpha = 0.04f)
    val measureGridColor = Color.White.copy(alpha = 0.08f)

    Canvas(
        modifier = Modifier
            .width(timelineWidth)
            .height(laneHeight)
    ) {
        val beatMs = MusicalTimeConverter.msPerBeat(bpm, timeSignatureDenominator)
        val measureMs = MusicalTimeConverter.msPerMeasure(
            bpm, timeSignatureNumerator, timeSignatureDenominator
        )
        if (beatMs <= 0.0) return@Canvas

        fun msToX(ms: Double): Float = ((ms / msPerDp) * density).toFloat()

        val beatPx = msToX(beatMs)
        if (beatPx < 4f) return@Canvas // Too dense, skip

        // Draw beat and measure lines
        var ms = 0.0
        while (ms <= totalDurationMs) {
            val x = msToX(ms)
            if (x > size.width) break

            val isMeasure = measureMs > 0 && (ms % measureMs).let {
                it < 1.0 || (measureMs - it) < 1.0
            }

            drawLine(
                color = if (isMeasure) measureGridColor else gridColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 0.5.dp.toPx()
            )

            ms += beatMs
        }

        // Draw sub-beat grid lines at grid resolution
        val gridStepMs = MusicalTimeConverter.msPerGridStep(bpm, gridResolution, timeSignatureDenominator)
        if (gridStepMs > 0.0 && gridStepMs < beatMs) {
            val gridStepPx = msToX(gridStepMs)
            if (gridStepPx >= 4f) {
                var stepMs = 0.0
                while (stepMs <= totalDurationMs) {
                    // Skip positions on beat boundaries (already drawn)
                    val beatFrac = stepMs / beatMs
                    val isBeat = (beatFrac - beatFrac.toLong()).let { it < 0.01 || it > 0.99 }
                    if (!isBeat) {
                        val x = msToX(stepMs)
                        if (x > size.width) break
                        drawLine(
                            color = subBeatGridColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 0.5.dp.toPx()
                        )
                    }
                    stepMs += gridStepMs
                }
            }
        }
    }
}

/** Visual trim-handle indicator on the left or right edge of a track. */
@Composable
private fun TrimHandle(modifier: Modifier = Modifier) {
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

/** Drag mode for the ruler gesture layer. */
private enum class RulerDragMode { SCRUB, ADJUST_LOOP_START, ADJUST_LOOP_END }

/**
 * Ruler-height gesture layer for scrub and loop-handle interactions.
 * Confined to [RULER_HEIGHT] so it never overlaps track lanes.
 *
 * Default gesture is **scrub/seek**. Only when the touch starts within
 * 32dp of an existing loop handle does it enter handle-adjust mode.
 */
@Composable
private fun RulerGestureLayer(
    loopStartMs: Long?,
    loopEndMs: Long?,
    isRecording: Boolean,
    msPerDp: Float,
    totalDurationMs: Long,
    timelineWidth: Dp,
    onScrub: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    onAction: (StudioAction) -> Unit
) {
    val density = LocalDensity.current
    val currentStartMs by rememberUpdatedState(loopStartMs)
    val currentEndMs by rememberUpdatedState(loopEndMs)
    val currentTotalMs by rememberUpdatedState(totalDurationMs)
    val currentIsRecording by rememberUpdatedState(isRecording)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubFinished by rememberUpdatedState(onScrubFinished)
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
                                RulerDragMode.ADJUST_LOOP_START else RulerDragMode.ADJUST_LOOP_END
                        }
                        nearStart -> RulerDragMode.ADJUST_LOOP_START
                        nearEnd -> RulerDragMode.ADJUST_LOOP_END
                        else -> RulerDragMode.SCRUB
                    }

                    if (dragMode == RulerDragMode.SCRUB) {
                        // Disable scrub during recording
                        if (currentIsRecording) return@awaitEachGesture

                        // Immediate seek on touch down
                        val tapMs = pxToMs(touchX)
                        currentOnScrub(tapMs)

                        var lastScrubMs = tapMs
                        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        }
                        if (slop != null) {
                            horizontalDrag(slop.id) { change ->
                                change.consume()
                                val fingerMs = pxToMs(change.position.x)
                                lastScrubMs = fingerMs
                                currentOnScrub(fingerMs)
                            }
                        }
                        // Commit seek on release (tap or drag end)
                        currentOnScrubFinished(lastScrubMs)
                    } else {
                        // Loop handle adjustment (same as before)
                        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        }
                        if (slop != null) {
                            horizontalDrag(slop.id) { change ->
                                change.consume()
                                val fingerMs = pxToMs(change.position.x)
                                when (dragMode) {
                                    RulerDragMode.ADJUST_LOOP_START ->
                                        currentOnAction(StudioAction.UpdateLoopRegionStart(fingerMs))
                                    RulerDragMode.ADJUST_LOOP_END ->
                                        currentOnAction(StudioAction.UpdateLoopRegionEnd(fingerMs))
                                    else -> {}
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
                .background(NjSurface2.copy(alpha = 0.5f))
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
            .background(NjSurface2.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        NjButton(
            text = "Rename",
            icon = Icons.Filled.Edit,
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
        NjButton(
            text = "Delete",
            icon = Icons.Filled.Delete,
            onClick = {
                onAction(StudioAction.RequestDeleteTake(take.id, take.trackId))
            },
            textColor = NjError.copy(alpha = 0.7f)
        )
    }
}

// ── Live recording composables ──────────────────────────────────────────

/**
 * Full-height phantom track row shown when recording with no armed track.
 * Disappears once recording stops and the real track is saved.
 */
@Composable
private fun RecordingTrackRow(
    liveAmplitudes: FloatArray,
    recordingStartGlobalMs: Long,
    recordingElapsedMs: Long,
    globalPositionMs: Long,
    msPerDp: Float,
    timelineWidthDp: Dp,
    scrollState: ScrollState
) {
    Row(Modifier.fillMaxWidth()) {
        RecordingHeader(
            label = "New Track",
            elapsedMs = recordingElapsedMs,
            height = TRACK_LANE_HEIGHT
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState)
        ) {
            Box(
                modifier = Modifier
                    .width(timelineWidthDp)
                    .height(TRACK_LANE_HEIGHT)
            ) {
                RecordingWaveformBlock(
                    liveAmplitudes = liveAmplitudes,
                    recordingStartGlobalMs = recordingStartGlobalMs,
                    globalPositionMs = globalPositionMs,
                    msPerDp = msPerDp,
                    height = TRACK_LANE_HEIGHT
                )
            }
        }
    }
}

/**
 * Take-height recording row shown below the armed track during recording.
 */
@Composable
private fun RecordingLaneRow(
    liveAmplitudes: FloatArray,
    recordingStartGlobalMs: Long,
    recordingElapsedMs: Long,
    globalPositionMs: Long,
    msPerDp: Float,
    timelineWidthDp: Dp,
    scrollState: ScrollState
) {
    Row(Modifier.fillMaxWidth()) {
        RecordingHeader(
            label = "Recording",
            elapsedMs = recordingElapsedMs,
            height = TAKE_ROW_HEIGHT
        )

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
                RecordingWaveformBlock(
                    liveAmplitudes = liveAmplitudes,
                    recordingStartGlobalMs = recordingStartGlobalMs,
                    globalPositionMs = globalPositionMs,
                    msPerDp = msPerDp,
                    height = TAKE_ROW_HEIGHT
                )
            }
        }
    }
}

/** Coral-bordered header for a recording row, showing label + elapsed time. */
@Composable
private fun RecordingHeader(
    label: String,
    elapsedMs: Long,
    height: Dp
) {
    val totalSeconds = elapsedMs / 1000
    val min = totalSeconds / 60
    val sec = totalSeconds % 60

    Column(
        modifier = Modifier
            .width(HEADER_WIDTH)
            .height(height)
            .drawBehind {
                drawLine(
                    color = NjRecordCoral.copy(alpha = 0.6f),
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NjRecordCoral.copy(alpha = 0.85f),
            maxLines = 1
        )
        Text(
            text = "%d:%02d".format(min, sec),
            style = MaterialTheme.typography.labelSmall,
            color = NjRecordCoral.copy(alpha = 0.5f)
        )
    }
}

/**
 * Positioned live waveform block on the timeline. Starts at
 * [recordingStartGlobalMs] and grows rightward, width derived from
 * [globalPositionMs] so it stays perfectly in sync with the playhead.
 *
 * Renders amplitudes directly (not via [NjLiveWaveform]) because the
 * Studio waveform must always fill 100% of the block width. The block
 * is already sized to match elapsed recording time, so every amplitude
 * sample stretches edge-to-edge. [NjLiveWaveform] uses a proportional
 * grow mode that would leave empty space on the right.
 */
@Composable
private fun RecordingWaveformBlock(
    liveAmplitudes: FloatArray,
    recordingStartGlobalMs: Long,
    globalPositionMs: Long,
    msPerDp: Float,
    height: Dp
) {
    val offsetDp = (recordingStartGlobalMs / msPerDp).dp
    val widthMs = (globalPositionMs - recordingStartGlobalMs).coerceAtLeast(0L)
    val widthDp = (widthMs / msPerDp).dp.coerceAtLeast(4.dp)
    val waveformColor = NjRecordCoral.copy(alpha = 0.6f)
    val minBarFraction = 0.05f

    Box(
        modifier = Modifier
            .offset(x = offsetDp)
            .width(widthDp)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(NjStudioLane.copy(alpha = 0.45f))
            .padding(vertical = 2.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height - 4.dp)
        ) {
            val amps = liveAmplitudes
            if (amps.isEmpty()) return@Canvas

            val canvasW = size.width
            val canvasH = size.height
            val centerY = canvasH / 2f
            val count = amps.size
            val divisor = (count - 1).coerceAtLeast(1)

            val path = Path()

            // Top edge -- left to right
            for (i in 0 until count) {
                val amp = amps[i].coerceIn(minBarFraction, 1f)
                val px = canvasW * i / divisor
                val y = centerY - amp * centerY
                if (i == 0) path.moveTo(px, y) else path.lineTo(px, y)
            }

            // Bottom edge -- right to left (mirrored envelope)
            for (i in count - 1 downTo 0) {
                val amp = amps[i].coerceIn(minBarFraction, 1f)
                val px = canvasW * i / divisor
                val y = centerY + amp * centerY
                path.lineTo(px, y)
            }

            path.close()
            drawPath(path, color = waveformColor)

            // Leading-edge glow
            val glowWidth = 6.dp.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, waveformColor.copy(alpha = 0.35f)),
                    startX = (canvasW - glowWidth).coerceAtLeast(0f),
                    endX = canvasW
                ),
                topLeft = Offset((canvasW - glowWidth).coerceAtLeast(0f), 0f),
                size = androidx.compose.ui.geometry.Size(glowWidth, canvasH)
            )
        }
    }
}
