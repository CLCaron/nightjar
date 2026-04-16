package com.example.nightjar.ui.studio

import com.example.nightjar.ui.components.NjButton
import com.example.nightjar.ui.components.PressedBodyColor
import com.example.nightjar.ui.components.RaisedBodyColor
import com.example.nightjar.ui.components.collectIsPressedWithMinDuration
import com.example.nightjar.ui.components.njGrain
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material3.Icon
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.ui.layout.layout
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import android.view.HapticFeedbackConstants
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjCursorTeal
import com.example.nightjar.ui.theme.NjDrumRowColors
import com.example.nightjar.ui.theme.NjGroupColors
import com.example.nightjar.ui.theme.NjRecordCoral
import com.example.nightjar.ui.theme.NjError
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjAmber
import com.example.nightjar.ui.theme.NjLane
import com.example.nightjar.ui.theme.NjSurface2
import com.example.nightjar.ui.theme.NjMuted
import com.example.nightjar.ui.theme.NjOutline
import com.example.nightjar.ui.theme.NjTrackColors
import androidx.compose.foundation.border
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import kotlin.math.abs

// ── Layout constants ────────────────────────────────────────────────────
internal val HEADER_WIDTH = 100.dp
internal val COLOR_TAB_WIDTH = 10.dp
private val TRACK_LANE_HEIGHT = 56.dp
private val RULER_HEIGHT = 28.dp
private val TIMELINE_END_PADDING_DP = 120.dp
private const val MIN_EFFECTIVE_DURATION_MS = 200L
private val TRIM_HANDLE_WIDTH = 10.dp
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
    cursorPositionMs: Long,
    totalDurationMs: Long,
    msPerDp: Float,
    isPlaying: Boolean,
    isRecording: Boolean = false,
    isAddTrackDrawerOpen: Boolean = false,
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
    collapsedHeaderTrackIds: Set<Long> = emptySet(),
    headersCollapsedMode: Boolean = false,
    soloedTrackIds: Set<Long>,
    armedTrackId: Long?,
    audioClips: Map<Long, List<AudioClipUiState>>,
    expandedAudioClipId: Long?,
    audioClipDragState: AudioClipDragState?,
    audioClipTrimState: AudioClipTrimState?,
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
    // Hoisted timeline state — owned by StudioScreen so the pinned ruler overlay
    // can share the same horizontal scroll, follow mode, and layout values.
    scrollState: ScrollState,
    columnWidth: Dp,
    timelineWidthDp: Dp,
    isFollowActive: Boolean,
    followLineXPx: Float,
    onDisengageFollow: () -> Unit,
    modifier: Modifier = Modifier
) {
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

    // Disengage follow only when the user touches scrollable timeline content
    // (ruler or track lanes), not headers, drawers, or other controls.
    val disengageFollowModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            onDisengageFollow()
        }
    }

    // Capture the follow-line color in composable scope so the per-track Canvas
    // DrawScope (non-composable) can use it.
    val followLineColor = NjAmber

    Box(modifier = modifier.fillMaxWidth()) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // ── Ruler row ──────────────────────────────────────────────
        TimelineRulerRow(
            scrollState = scrollState,
            columnWidth = columnWidth,
            headersCollapsedMode = headersCollapsedMode,
            timelineWidthDp = timelineWidthDp,
            totalDurationMs = totalDurationMs,
            cursorPositionMs = cursorPositionMs,
            globalPositionMs = globalPositionMs,
            msPerDp = msPerDp,
            bpm = bpm,
            timeSignatureNumerator = timeSignatureNumerator,
            timeSignatureDenominator = timeSignatureDenominator,
            gridResolution = gridResolution,
            loopStartMs = loopStartMs,
            loopEndMs = loopEndMs,
            isLoopEnabled = isLoopEnabled,
            isRecording = isRecording,
            isFollowActive = isFollowActive,
            followLineXPx = followLineXPx,
            onDisengageFollow = onDisengageFollow,
            onScrub = onScrub,
            onScrubFinished = onScrubFinished,
            onAction = onAction
        )

        // ── Per-track rows with drawer + take slots ─────────────
        tracks.forEachIndexed { index, track ->
            val isSoloed = track.id in soloedTrackIds
            val anySoloed = soloedTrackIds.isNotEmpty()
            val effectivelyMuted = track.isMuted || (anySoloed && !isSoloed)
            val trackColor = NjTrackColors[index % NjTrackColors.size]
            val isArmed = track.id == armedTrackId
            val clips = audioClips[track.id] ?: emptyList()

            // Track row: header + scrollable lane.
            // Box wrapper clips overlay headers that extend left when retracted.
            Box(Modifier.fillMaxWidth().clipToBounds()) {
            Row(Modifier.fillMaxWidth()) {
                TrackHeader(
                    track = track,
                    height = TRACK_LANE_HEIGHT,
                    headerWidth = columnWidth,
                    isExpanded = track.id in expandedTrackIds,
                    isCollapsed = track.id in collapsedHeaderTrackIds,
                    headersCollapsedMode = headersCollapsedMode,
                    isSoloed = isSoloed,
                    isArmed = isArmed,
                    trackColor = trackColor,
                    trackIndex = index,
                    onAction = onAction
                )

                Box(
                    modifier = disengageFollowModifier
                        .weight(1f)
                        .horizontalScroll(scrollState)
                ) {
                    Box(Modifier.width(timelineWidthDp)) {
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
                                AudioTrackLane(
                                    track = track,
                                    trackColor = trackColor,
                                    clips = clips,
                                    msPerDp = msPerDp,
                                    timelineWidth = timelineWidthDp,
                                    laneHeight = TRACK_LANE_HEIGHT,
                                    audioClipDragState = audioClipDragState,
                                    audioClipTrimState = audioClipTrimState,
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

                        if (!isFollowActive) {
                            CursorSegment(
                                cursorPositionMs = cursorPositionMs,
                                msPerDp = msPerDp,
                                height = TRACK_LANE_HEIGHT,
                                showTriangle = false
                            )
                            PlayheadSegment(
                                globalPositionMs = globalPositionMs,
                                msPerDp = msPerDp,
                                height = TRACK_LANE_HEIGHT
                            )
                        }
                    }
                }
            }
            if (isFollowActive) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawLine(
                        color = followLineColor,
                        start = Offset(followLineXPx, 0f),
                        end = Offset(followLineXPx, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
            } // close clipToBounds Box

            // Drawer slot
            AnimatedVisibility(
                visible = track.id in expandedTrackIds,
                enter = drawerEnter,
                exit = drawerExit
            ) {
                val isDrawerAnimating = transition.isRunning
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
                            timeSignatureDenominator = timeSignatureDenominator,
                            globalPositionMs = globalPositionMs,
                            isPlaying = isPlaying,
                            isAnimating = isDrawerAnimating
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
                            onAction = onAction
                        )
                    }
                }
            }

            // Clip-scoped take rows: expand when a multi-take clip is tapped
            val expandedClip = clips.firstOrNull { it.clipId == expandedAudioClipId }
            AnimatedVisibility(
                visible = expandedClip != null && expandedClip.takeCount > 1,
                enter = drawerEnter,
                exit = drawerExit
            ) {
                val clipForTakes = expandedClip ?: return@AnimatedVisibility
                Column {
                    clipForTakes.takes.forEach { take ->
                        AudioClipTakeRow(
                            take = take,
                            clipId = clipForTakes.clipId,
                            trackId = track.id,
                            isActiveTake = take.id == clipForTakes.activeTake?.id,
                            trackColor = trackColor,
                            getAudioFile = getAudioFile,
                            onAction = onAction
                        )
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

        // Inline add-track row (hidden during recording)
        if (!isRecording) {
            AddTrackRow(
                isDrawerOpen = isAddTrackDrawerOpen,
                trackCount = tracks.size,
                onAction = onAction
            )
        }
    }

    }
}

/**
 * The full timeline ruler row: master collapse handle on the left, then the
 * horizontally-scrollable bar/beat ruler with loop overlay, scrub gestures,
 * cursor and playhead indicators, and a follow-mode line overlay.
 *
 * Extracted so [StudioScreen] can render an identical pinned overlay when the
 * user scrolls the in-flow ruler off the top of the viewport. Both call sites
 * share the same horizontal [scrollState] so bar numbers stay in sync.
 */
@Composable
internal fun TimelineRulerRow(
    scrollState: ScrollState,
    columnWidth: Dp,
    headersCollapsedMode: Boolean,
    timelineWidthDp: Dp,
    totalDurationMs: Long,
    cursorPositionMs: Long,
    globalPositionMs: Long,
    msPerDp: Float,
    bpm: Double,
    timeSignatureNumerator: Int,
    timeSignatureDenominator: Int,
    gridResolution: Int,
    loopStartMs: Long?,
    loopEndMs: Long?,
    isLoopEnabled: Boolean,
    isRecording: Boolean,
    isFollowActive: Boolean,
    followLineXPx: Float,
    onDisengageFollow: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val disengageFollowModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            onDisengageFollow()
        }
    }

    // Capture follow-line color in composable scope; DrawScope below is not.
    val followLineColor = NjAmber

    Box(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            MasterCollapseHandle(
                columnWidth = columnWidth,
                anyCollapsed = headersCollapsedMode,
                onToggle = { onAction(StudioAction.ToggleAllTrackHeaders) }
            )

            Box(
                modifier = disengageFollowModifier
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
                        cursorPositionMs = cursorPositionMs,
                        isRecording = isRecording,
                        msPerDp = msPerDp,
                        totalDurationMs = totalDurationMs,
                        timelineWidth = timelineWidthDp,
                        onScrub = onScrub,
                        onScrubFinished = onScrubFinished,
                        onAction = onAction
                    )

                    if (!isFollowActive) {
                        CursorSegment(
                            cursorPositionMs = cursorPositionMs,
                            msPerDp = msPerDp,
                            height = RULER_HEIGHT,
                            showTriangle = true
                        )
                        PlayheadSegment(
                            globalPositionMs = globalPositionMs,
                            msPerDp = msPerDp,
                            height = RULER_HEIGHT
                        )
                    }
                }
            }
        }
        if (isFollowActive) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawLine(
                    color = followLineColor,
                    start = Offset(followLineXPx, 0f),
                    end = Offset(followLineXPx, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
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
private fun AudioTrackLane(
    track: TrackEntity,
    trackColor: Color,
    clips: List<AudioClipUiState>,
    msPerDp: Float,
    timelineWidth: Dp,
    laneHeight: Dp,
    audioClipDragState: AudioClipDragState?,
    audioClipTrimState: AudioClipTrimState?,
    expandedClipState: ExpandedClipState?,
    effectivelyMuted: Boolean,
    getAudioFile: (String) -> File,
    onAction: (StudioAction) -> Unit
) {
    val density = LocalDensity.current

    // Faster long-press for clip drag — 200ms instead of the default 400ms.
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
        if (clips.isEmpty()) {
            // Empty audio track placeholder
            Box(Modifier.fillMaxSize())
        }

        clips.forEach { clip ->
            val activeTake = clip.activeTake ?: return@forEach
            val isDragging = audioClipDragState?.clipId == clip.clipId
            val isTrimming = audioClipTrimState?.clipId == clip.clipId
            val isSelected = expandedClipState?.trackId == track.id &&
                expandedClipState.clipId == clip.clipId
            val isFlipped = isSelected && (expandedClipState?.isFlipped == true)

            val effectiveOffsetMs = if (isDragging) {
                audioClipDragState!!.previewOffsetMs
            } else {
                clip.offsetMs
            }

            val effectiveTrimStartMs = if (isTrimming) {
                audioClipTrimState!!.previewTrimStartMs
            } else {
                activeTake.trimStartMs
            }
            val effectiveTrimEndMs = if (isTrimming) {
                audioClipTrimState!!.previewTrimEndMs
            } else {
                activeTake.trimEndMs
            }

            val effectiveDurationMs = (activeTake.durationMs - effectiveTrimStartMs - effectiveTrimEndMs)
                .coerceAtLeast(MIN_EFFECTIVE_DURATION_MS)

            // During a left trim, shift the block right so the RIGHT edge stays fixed
            val trimDelta = effectiveTrimStartMs - activeTake.trimStartMs
            val visualOffsetMs = effectiveOffsetMs + trimDelta
            val offsetDp = (visualOffsetMs / msPerDp).dp
            val widthDp = (effectiveDurationMs / msPerDp).dp

            val clipMuted = effectivelyMuted || clip.isMuted
            val bgAlpha = when {
                isDragging -> 0.8f
                clipMuted -> 0.3f
                else -> 0.6f
            }

            // Accumulated drag offset in px for the body drag gesture
            var dragAccumulatedPx by remember(clip.clipId) { mutableFloatStateOf(0f) }

            // Waveform block — edges → trim active take, center → long-press drag / short tap
            Box(
                modifier = Modifier
                    .offset(x = offsetDp)
                    .width(widthDp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(NjLane.copy(alpha = bgAlpha))
                    .then(
                        if (isSelected) Modifier.border(1.5.dp, NjAmber.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        else Modifier
                    )
                    .then(
                        if (isDragging) Modifier
                            .graphicsLayer { shadowElevation = 8f }
                            .alpha(0.85f)
                        else Modifier
                    )
                    .pointerInput(clip.clipId, clip.offsetMs, activeTake.trimStartMs, activeTake.trimEndMs, msPerDp) {
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
                                        onAction(StudioAction.StartTrimAudioClip(clip.clipId, track.id, TrimEdge.LEFT))
                                        val completed = horizontalDrag(slop.id) { change ->
                                            val delta = change.positionChange()
                                            change.consume()
                                            trimAccPx += delta.x
                                            val deltaDp = trimAccPx / density.density
                                            val deltaMs = (deltaDp * msPerDp).toLong()
                                            val (newStart, newEnd) = computeClipTrimValues(
                                                TrimEdge.LEFT, activeTake, deltaMs
                                            )
                                            onAction(StudioAction.UpdateTrimAudioClip(newStart, newEnd))
                                        }
                                        val deltaDp = trimAccPx / density.density
                                        val deltaMs = (deltaDp * msPerDp).toLong()
                                        val (newStart, newEnd) = computeClipTrimValues(
                                            TrimEdge.LEFT, activeTake, deltaMs
                                        )
                                        if (completed) {
                                            onAction(StudioAction.FinishTrimAudioClip(
                                                clip.clipId, track.id, newStart, newEnd
                                            ))
                                        } else {
                                            onAction(StudioAction.CancelTrimAudioClip)
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
                                        onAction(StudioAction.StartTrimAudioClip(clip.clipId, track.id, TrimEdge.RIGHT))
                                        val completed = horizontalDrag(slop.id) { change ->
                                            val delta = change.positionChange()
                                            change.consume()
                                            trimAccPx += delta.x
                                            val deltaDp = trimAccPx / density.density
                                            val deltaMs = (deltaDp * msPerDp).toLong()
                                            val (newStart, newEnd) = computeClipTrimValues(
                                                TrimEdge.RIGHT, activeTake, deltaMs
                                            )
                                            onAction(StudioAction.UpdateTrimAudioClip(newStart, newEnd))
                                        }
                                        val deltaDp = trimAccPx / density.density
                                        val deltaMs = (deltaDp * msPerDp).toLong()
                                        val (newStart, newEnd) = computeClipTrimValues(
                                            TrimEdge.RIGHT, activeTake, deltaMs
                                        )
                                        if (completed) {
                                            onAction(StudioAction.FinishTrimAudioClip(
                                                clip.clipId, track.id, newStart, newEnd
                                            ))
                                        } else {
                                            onAction(StudioAction.CancelTrimAudioClip)
                                        }
                                    }
                                }

                                // ── Center — short tap to expand takes, long-press to reposition ──
                                else -> {
                                    val longPress = awaitLongPressOrCancellation(down.id)
                                    if (longPress != null) {
                                        dragAccumulatedPx = 0f
                                        onAction(StudioAction.StartDragAudioClip(track.id, clip.clipId))
                                        val completed = drag(longPress.id) { change ->
                                            val delta = change.positionChange()
                                            change.consume()
                                            dragAccumulatedPx += delta.x
                                            val deltaDp = dragAccumulatedPx / density.density
                                            val newOffsetMs = (clip.offsetMs + (deltaDp * msPerDp).toLong())
                                                .coerceAtLeast(0L)
                                            onAction(StudioAction.UpdateDragAudioClip(newOffsetMs))
                                        }
                                        if (completed) {
                                            val deltaDp = dragAccumulatedPx / density.density
                                            val newOffsetMs = (clip.offsetMs + (deltaDp * msPerDp).toLong())
                                                .coerceAtLeast(0L)
                                            onAction(StudioAction.FinishDragAudioClip(
                                                track.id, clip.clipId, newOffsetMs
                                            ))
                                        } else {
                                            onAction(StudioAction.CancelDragAudioClip)
                                        }
                                        dragAccumulatedPx = 0f
                                    } else {
                                        // Short tap — select / flip clip
                                        val upChange = currentEvent.changes.firstOrNull { it.id == down.id }
                                        val dist = upChange?.let {
                                            (it.position - down.position).getDistance()
                                        } ?: 0f
                                        if (dist < viewConfiguration.touchSlop) {
                                            onAction(StudioAction.TapClip(track.id, clip.clipId, "audio"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .padding(vertical = 4.dp)
            ) {
                val barColor = if (clipMuted) {
                    trackColor.copy(alpha = 0.18f)
                } else {
                    trackColor.copy(alpha = 0.65f)
                }

                // Show only the trimmed portion of the audio
                val startFrac = if (activeTake.durationMs > 0)
                    effectiveTrimStartMs.toFloat() / activeTake.durationMs else 0f
                val endFrac = if (activeTake.durationMs > 0)
                    1f - effectiveTrimEndMs.toFloat() / activeTake.durationMs else 1f

                FlippableClip(
                    isFlipped = isFlipped,
                    modifier = Modifier.matchParentSize(),
                    front = {
                        Box(Modifier.fillMaxSize()) {
                            NjWaveform(
                                audioFile = getAudioFile(activeTake.audioFileName),
                                modifier = Modifier.fillMaxWidth(),
                                barColor = barColor,
                                height = laneHeight - 8.dp,
                                startFraction = startFrac,
                                endFraction = endFrac
                            )

                            // Visual-only trim handles at each edge
                            TrimHandle(modifier = Modifier.align(Alignment.CenterStart))
                            TrimHandle(modifier = Modifier.align(Alignment.CenterEnd))

                            // Take count badge when clip has multiple takes
                            if (clip.takeCount > 1) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .background(
                                            NjAmber.copy(alpha = 0.7f),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "${clip.takeCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Black,
                                        fontSize = 9.sp
                                    )
                                }
                            }

                            // Link indicator: 3dp colored top strip + chain glyph
                            // appears when this clip shares a source with another.
                            ClipLinkIndicator(
                                linkage = LocalAudioClipLinkage.current[clip.clipId],
                                palette = NjGroupColors,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )

                            // Split-mode vertical line overlay (only when this
                            // clip is the split target).
                            ClipSplitLine(
                                clipId = clip.clipId,
                                clipOffsetMs = clip.offsetMs,
                                clipWidthMs = effectiveDurationMs,
                                msPerDp = msPerDp
                            )
                        }
                    },
                    back = {
                        ClipActionButtons(
                            onDuplicate = {
                                // Flip-panel duplicate defaults to unlinked. The
                                // controls-drawer pillrocker exposes both halves.
                                onAction(StudioAction.DuplicateAudioClip(track.id, clip.clipId, linked = false))
                            },
                            onRename = {
                                onAction(
                                    StudioAction.RequestRenameClip(
                                        clipId = clip.clipId,
                                        clipType = "audio",
                                        currentName = clip.displayName
                                    )
                                )
                            },
                            onUnlink = {
                                // Safe no-op for standalone clips.
                                onAction(StudioAction.UnlinkClip(clip.clipId, "audio"))
                            },
                            onDelete = {
                                onAction(StudioAction.DeleteAudioClip(track.id, clip.clipId))
                            },
                            onDismiss = { onAction(StudioAction.DismissClipPanel) }
                        )
                    }
                )
            }
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
            val isSelected = expandedClipState?.trackId == track.id &&
                expandedClipState.clipId == clip.clipId
            val isFlipped = isSelected && (expandedClipState?.isFlipped == true)
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
                        if (isSelected) Modifier.border(1.5.dp, NjAmber.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        else Modifier
                    )
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
                    isFlipped = isFlipped,
                    modifier = Modifier.matchParentSize(),
                    front = {
                        Box(Modifier.fillMaxSize()) {
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
                            ClipLinkIndicator(
                                linkage = LocalMidiClipLinkage.current[clip.clipId],
                                palette = NjGroupColors,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                            ClipSplitLine(
                                clipId = clip.clipId,
                                clipOffsetMs = clip.offsetMs,
                                clipWidthMs = clipDurationMs,
                                msPerDp = msPerDp
                            )
                        }
                    },
                    back = {
                        ClipActionButtons(
                            onDuplicate = { onAction(StudioAction.DuplicateMidiClip(track.id, clip.clipId, linked = false)) },
                            onDelete = { onAction(StudioAction.DeleteMidiClip(track.id, clip.clipId)) },
                            onEdit = { onAction(StudioAction.OpenPianoRoll(track.id, clip.clipId)) },
                            onUnlink = { onAction(StudioAction.UnlinkClip(clip.clipId, "midi")) },
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
    val drumRowColors = NjDrumRowColors

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
            val isSelected = expandedClipState?.trackId == track.id &&
                    expandedClipState.clipId == clip.clipId
            val isFlipped = isSelected && (expandedClipState?.isFlipped == true)
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
                    .background(NjLane.copy(alpha = if (isDragging) 0.8f else bgAlpha))
                    .then(
                        if (isSelected) Modifier.border(1.5.dp, NjAmber.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        else Modifier
                    )
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
                    isFlipped = isFlipped,
                    modifier = Modifier.matchParentSize(),
                    front = {
                      Box(Modifier.fillMaxSize()) {
                        // Mini step grid -- per-instrument colored dots
                        val gridLineColor = NjMuted
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
                                        drumRowColors[rowIdx].copy(alpha = 0.3f)
                                    } else {
                                        drumRowColors[rowIdx].copy(alpha = 0.9f)
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
                                    color = gridLineColor.copy(
                                        alpha = if (isBar) 0.3f else 0.12f
                                    ),
                                    start = Offset(x, 0f),
                                    end = Offset(x, canvasH),
                                    strokeWidth = if (isBar) 1.dp.toPx() else 0.5.dp.toPx()
                                )
                            }
                        }
                        ClipLinkIndicator(
                            linkage = LocalDrumClipLinkage.current[clip.clipId],
                            palette = NjGroupColors,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                        ClipSplitLine(
                            clipId = clip.clipId,
                            clipOffsetMs = clip.offsetMs,
                            clipWidthMs = clipPatternDurationMs.toLong(),
                            msPerDp = msPerDp
                        )
                      }
                    },
                    back = {
                        ClipActionButtons(
                            onDuplicate = { onAction(StudioAction.DuplicateClip(track.id, clip.clipId, linked = false)) },
                            onDelete = { onAction(StudioAction.DeleteClip(track.id, clip.clipId)) },
                            onEdit = { onAction(StudioAction.OpenDrumEditor(track.id, clip.clipId)) },
                            onUnlink = { onAction(StudioAction.UnlinkClip(clip.clipId, "drum")) },
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
 * Split-mode line drawn on top of a clip while the user is positioning a
 * split. Valid position -> amber solid line. Invalid -> faded.
 *
 * [clipOffsetMs] and [clipWidthMs] bound the clip's timeline range so the
 * line can be clamped visually and hidden when the split cursor sits
 * outside the clip.
 */
@Composable
private fun ClipSplitLine(
    clipId: Long,
    clipOffsetMs: Long,
    clipWidthMs: Long,
    msPerDp: Float,
    modifier: Modifier = Modifier
) {
    val split = LocalSplitMode.current
    if (split.clipId != clipId) return
    val posMs = split.positionMs ?: return
    val localMs = posMs - clipOffsetMs
    if (localMs < 0 || localMs > clipWidthMs) return

    val xDp = (localMs / msPerDp).dp
    val color = if (split.valid) NjAmber else NjMuted
    Box(
        modifier = modifier
            .offset(x = xDp - 0.75.dp)
            .width(1.5.dp)
            .fillMaxHeight()
            .background(color.copy(alpha = if (split.valid) 0.9f else 0.5f))
    )
}

/**
 * Link indicator strip drawn across the top edge of a linked clip.
 *
 * Renders a 3dp-tall colored bar + small chain-link glyph when [linkage]
 * reports isLinked. Observes LocalPulseTicks so sibling edits flash the
 * bar via a short LED-style glow (100ms ramp + 150ms settle).
 */
@Composable
private fun ClipLinkIndicator(
    linkage: ClipLinkage?,
    palette: List<Color>,
    modifier: Modifier = Modifier
) {
    if (linkage == null || !linkage.isLinked || palette.isEmpty()) return
    val baseColor = palette[(linkage.groupKey.hashCode() and Int.MAX_VALUE) % palette.size]

    val pulseTicks = LocalPulseTicks.current
    val tick = pulseTicks[linkage.groupKey] ?: 0L
    val glow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(tick) {
        if (tick > 0L) {
            glow.animateTo(1f, androidx.compose.animation.core.tween(100))
            glow.animateTo(0f, androidx.compose.animation.core.tween(150))
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(
                color = androidx.compose.ui.graphics.lerp(
                    baseColor.copy(alpha = 0.9f),
                    baseColor,
                    glow.value
                )
            )
    ) {
        // Chain glyph centered on the strip (scaled down to fit 3dp).
        Icon(
            imageVector = Icons.Filled.Link,
            contentDescription = null,
            tint = baseColor,
            modifier = Modifier
                .align(Alignment.Center)
                .size(12.dp)
                .alpha(0.85f + 0.15f * glow.value)
        )
    }
}

/**
 * Icon-only action buttons shown on the back face of a flipped clip.
 * Duplicate (amber), optional Rename (silver), optional Unlink (accent),
 * Delete (red). Unlink appears only when the clip is part of a linked group.
 */
@Composable
private fun ClipActionButtons(
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onUnlink: (() -> Unit)? = null,
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
            textColor = NjAmber.copy(alpha = 0.9f)
        )
        Spacer(Modifier.width(12.dp))
        if (onEdit != null) {
            NjButton(
                text = "",
                icon = Icons.Filled.Edit,
                onClick = { onEdit(); onDismiss() },
                textColor = NjMuted.copy(alpha = 0.9f)
            )
            Spacer(Modifier.width(12.dp))
        }
        if (onRename != null) {
            NjButton(
                text = "",
                icon = Icons.Filled.DriveFileRenameOutline,
                onClick = { onRename(); onDismiss() },
                textColor = NjMuted.copy(alpha = 0.9f)
            )
            Spacer(Modifier.width(12.dp))
        }
        if (onUnlink != null) {
            NjButton(
                text = "",
                icon = Icons.Filled.LinkOff,
                onClick = { onUnlink(); onDismiss() },
                textColor = NjAccent.copy(alpha = 0.9f)
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

/** Knurled-grip trim handle — horizontal grooves matching the hardware aesthetic. */
@Composable
private fun TrimHandle(modifier: Modifier = Modifier) {
    val bg = NjMuted2.copy(alpha = 0.78f)
    Box(
        modifier = modifier
            .width(TRIM_HANDLE_WIDTH)
            .fillMaxHeight()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .drawWithContent {
                drawContent()
                // Knurled grooves: stacked horizontal ridges, same two-tone
                // channel pattern used by HardwareGroove elsewhere in the app.
                val grooveCount = 5
                val grooveSpacing = 3.dp.toPx()
                val totalHeight = (grooveCount - 1) * grooveSpacing
                val startY = (size.height - totalHeight) / 2
                val sw = 0.5.dp.toPx()
                val inset = 2.5.dp.toPx()

                for (i in 0 until grooveCount) {
                    val y = startY + i * grooveSpacing
                    // Dark shadow (top of groove)
                    drawLine(
                        Color.Black.copy(alpha = 0.50f),
                        Offset(inset, y),
                        Offset(size.width - inset, y),
                        sw
                    )
                    // Light catch (bottom of groove)
                    drawLine(
                        Color.White.copy(alpha = 0.12f),
                        Offset(inset, y + sw),
                        Offset(size.width - inset, y + sw),
                        sw
                    )
                }
            }
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

/** Clamps new trim values for an audio clip's active take. */
private fun computeClipTrimValues(
    edge: TrimEdge,
    take: TakeEntity,
    deltaMs: Long
): Pair<Long, Long> {
    return when (edge) {
        TrimEdge.LEFT -> {
            val maxTrimStart = take.durationMs - take.trimEndMs - MIN_EFFECTIVE_DURATION_MS
            val newStart = (take.trimStartMs + deltaMs)
                .coerceIn(0L, maxTrimStart.coerceAtLeast(0L))
            Pair(newStart, take.trimEndMs)
        }
        TrimEdge.RIGHT -> {
            val maxTrimEnd = take.durationMs - take.trimStartMs - MIN_EFFECTIVE_DURATION_MS
            val newEnd = (take.trimEndMs - deltaMs)
                .coerceIn(0L, maxTrimEnd.coerceAtLeast(0L))
            Pair(take.trimStartMs, newEnd)
        }
    }
}

/**
 * Fixed-width header in the track row.
 *
 * **Normal mode** ([headersCollapsedMode] = false): header fills [headerWidth],
 * content slides left/right within the column. Color tab stays fixed.
 *
 * **Collapsed mode** ([headersCollapsedMode] = true): the entire header
 * (tab + content + background) is one physical unit that slides together.
 * When retracted, only the color tab peeks out from the left edge. When
 * pulled out, the full header overlays the track lane via [zIndex].
 * The parent Row wrapper must have [clipToBounds] so the retracted portion
 * (extending to negative x) is hidden.
 */
@Composable
private fun TrackHeader(
    track: TrackEntity,
    height: Dp,
    headerWidth: Dp,
    isExpanded: Boolean,
    isCollapsed: Boolean,
    headersCollapsedMode: Boolean,
    isSoloed: Boolean,
    isArmed: Boolean = false,
    trackColor: Color,
    trackIndex: Int = 0,
    onAction: (StudioAction) -> Unit
) {
    val density = LocalDensity.current
    val view = LocalView.current

    val tabColor = if (isArmed) {
        NjRecordCoral.copy(alpha = 0.8f)
    } else {
        trackColor.copy(alpha = 0.7f)
    }

    if (headersCollapsedMode) {
        // ── Collapsed mode: background + content slide from behind the tab ──
        // The tab stays fixed at [0, 10dp] as the handle. The background
        // surface + text slide right to reveal (or left to retract).
        // This produces a "pull-out drawer" feel where tab and content look
        // like one connected piece.
        val contentWidthPx = with(density) { (HEADER_WIDTH - COLOR_TAB_WIDTH).toPx() }

        val slideOffset = remember { Animatable(if (isCollapsed) -contentWidthPx else 0f) }

        LaunchedEffect(isCollapsed) {
            delay(trackIndex * 30L)
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            slideOffset.animateTo(
                targetValue = if (isCollapsed) -contentWidthPx else 0f,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = 200f)
            )
        }

        Box(
            modifier = Modifier
                .width(headerWidth)          // narrow layout slot in the Row
                .height(height)
                .zIndex(1f)                  // always on top so collapse animation stays visible
        ) {
            // Background + content panel: slides to reveal from behind the tab.
            // Measured at HEADER_WIDTH (100dp) via custom layout to avoid the
            // auto-centering that requiredWidth applies when exceeding parent
            // constraints. Placed at (0,0) so the panel extends rightward.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .layout { measurable, constraints ->
                        val widthPx = HEADER_WIDTH.roundToPx()
                        val placeable = measurable.measure(
                            constraints.copy(minWidth = widthPx, maxWidth = widthPx)
                        )
                        layout(placeable.width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    }
                    .graphicsLayer { translationX = slideOffset.value }
                    .background(NjSurface2, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                    .drawBehind {
                        // Right-edge shadow for depth
                        drawLine(
                            color = Color.Black.copy(alpha = 0.35f),
                            start = Offset(size.width, 0f),
                            end = Offset(size.width, size.height),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
            ) {
                TrackHeaderContent(
                    track = track,
                    isSoloed = isSoloed,
                    isArmed = isArmed,
                    modifier = Modifier
                        .padding(start = COLOR_TAB_WIDTH)
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = { onAction(StudioAction.OpenTrackSettings(track.id)) }
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Color tab: fixed at [0, 10dp], always visible as the handle
            Box(
                modifier = Modifier
                    .width(COLOR_TAB_WIDTH)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    .background(tabColor)
                    .drawBehind {
                        val bevelX = size.width - 1.dp.toPx()
                        drawLine(
                            color = Color.White.copy(alpha = 0.15f),
                            start = Offset(bevelX, 0f),
                            end = Offset(bevelX, size.height * 0.5f),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = Color.Black.copy(alpha = 0.2f),
                            start = Offset(bevelX, size.height * 0.5f),
                            end = Offset(bevelX, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onAction(StudioAction.ToggleTrackHeaderCollapse(track.id)) }
                    )
            )
        }
    } else {
        // ── Normal mode: content slides within column, tab stays fixed ──
        val contentWidthPx = with(density) { (HEADER_WIDTH - COLOR_TAB_WIDTH).toPx() }
        val slideOffset = remember { Animatable(if (isCollapsed) -contentWidthPx else 0f) }

        LaunchedEffect(isCollapsed) {
            delay(trackIndex * 30L)
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            slideOffset.animateTo(
                targetValue = if (isCollapsed) -contentWidthPx else 0f,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = 200f)
            )
        }

        Box(
            modifier = Modifier
                .width(headerWidth)
                .height(height)
                .clipToBounds()
        ) {
            // Header content — slides left when collapsed
            Box(
                modifier = Modifier
                    .padding(start = COLOR_TAB_WIDTH)
                    .fillMaxHeight()
                    .width(HEADER_WIDTH - COLOR_TAB_WIDTH)
                    .clipToBounds()
            ) {
                Column(
                    modifier = Modifier
                        .graphicsLayer { translationX = slideOffset.value }
                        .width(HEADER_WIDTH - COLOR_TAB_WIDTH)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = { onAction(StudioAction.OpenTrackSettings(track.id)) }
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    TrackHeaderContent(
                        track = track,
                        isSoloed = isSoloed,
                        isArmed = isArmed
                    )
                }
            }

            // Color tab — fixed at left edge, acts as collapse toggle
            Box(
                modifier = Modifier
                    .width(COLOR_TAB_WIDTH)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    .background(tabColor)
                    .drawBehind {
                        val bevelX = size.width - 1.dp.toPx()
                        drawLine(
                            color = Color.White.copy(alpha = 0.15f),
                            start = Offset(bevelX, 0f),
                            end = Offset(bevelX, size.height * 0.5f),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = Color.Black.copy(alpha = 0.2f),
                            start = Offset(bevelX, size.height * 0.5f),
                            end = Offset(bevelX, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onAction(StudioAction.ToggleTrackHeaderCollapse(track.id)) }
                    )
            )
        }
    }
}

/** Shared header text content: track name, duration, status flags. */
@Composable
private fun TrackHeaderContent(
    track: TrackEntity,
    isSoloed: Boolean,
    isArmed: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
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
                    isSoloed -> NjAmber.copy(alpha = 0.7f)
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                }
            )
        }
    }
}

/**
 * Master collapse/expand handle in the ruler row's left column.
 * Double-chevron icon pointing left (collapse) or right (expand).
 */
/**
 * Master collapse/expand handle in the ruler row. Uses the shared [columnWidth]
 * for layout. Visually shows full chevrons only when all headers are expanded;
 * otherwise shows a small tucked chevron at the left edge.
 */
@Composable
private fun MasterCollapseHandle(
    columnWidth: Dp,
    anyCollapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedWithMinDuration()
    val iconAlpha = if (isPressed) 0.8f else 0.5f

    Box(
        modifier = modifier
            .width(columnWidth)
            .height(RULER_HEIGHT)
            .clipToBounds()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle
            ),
        contentAlignment = if (anyCollapsed) Alignment.CenterStart else Alignment.Center
    ) {
        val gripColor = NjOutline.copy(alpha = 0.3f)
        val chevronColor = NjMuted2.copy(alpha = iconAlpha)

        if (!anyCollapsed) {
            // All headers expanded — full chevron with grips (collapse all)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Canvas(Modifier.size(6.dp, 14.dp)) {
                    val lineWidth = 1.dp.toPx()
                    drawLine(gripColor, Offset(0f, 0f), Offset(0f, size.height), lineWidth)
                    drawLine(gripColor, Offset(3.dp.toPx(), 0f), Offset(3.dp.toPx(), size.height), lineWidth)
                }

                Spacer(Modifier.width(3.dp))

                // Left-pointing double chevron (collapse)
                Canvas(Modifier.size(14.dp)) {
                    val strokeW = 1.5.dp.toPx()
                    val midY = size.height / 2f
                    val arrowH = size.height * 0.35f
                    val x1 = size.width * 0.85f
                    val x1Tip = size.width * 0.55f
                    val x2 = size.width * 0.55f
                    val x2Tip = size.width * 0.25f
                    drawLine(chevronColor, Offset(x1, midY - arrowH), Offset(x1Tip, midY), strokeW)
                    drawLine(chevronColor, Offset(x1, midY + arrowH), Offset(x1Tip, midY), strokeW)
                    drawLine(chevronColor, Offset(x2, midY - arrowH), Offset(x2Tip, midY), strokeW)
                    drawLine(chevronColor, Offset(x2, midY + arrowH), Offset(x2Tip, midY), strokeW)
                }

                Spacer(Modifier.width(3.dp))

                Canvas(Modifier.size(6.dp, 14.dp)) {
                    val lineWidth = 1.dp.toPx()
                    drawLine(gripColor, Offset(0f, 0f), Offset(0f, size.height), lineWidth)
                    drawLine(gripColor, Offset(3.dp.toPx(), 0f), Offset(3.dp.toPx(), size.height), lineWidth)
                }
            }
        } else {
            // Some headers collapsed — tucked right-pointing chevron (expand)
            Canvas(Modifier.padding(start = 1.dp).size(8.dp)) {
                val strokeW = 1.dp.toPx()
                val midY = size.height / 2f
                val arrowH = size.height * 0.35f
                val xStart = size.width * 0.1f
                val xTip = size.width * 0.9f
                drawLine(chevronColor, Offset(xStart, midY - arrowH), Offset(xTip, midY), strokeW)
                drawLine(chevronColor, Offset(xStart, midY + arrowH), Offset(xTip, midY), strokeW)
            }
        }
    }
}

// ── Add-track row ──────────────────────────────────────────────────────

/**
 * Inline row at the bottom of the track list.
 *
 * Closed state: a full-width `+` button filling the header slot.
 * Open state: the `+` slides right while Audio / MIDI / Drums buttons
 * slide in from the left, forming one contiguous beveled strip.
 * After a track is created the row plays an entrance animation:
 * icon fades in, then the surface "raises" from pressed to raised.
 */
@Composable
private fun AddTrackRow(
    isDrawerOpen: Boolean,
    trackCount: Int,
    onAction: (StudioAction) -> Unit
) {
    val density = LocalDensity.current

    // ── Entrance animation after a new track is added ──────────────
    // Track count changes => a track was just added. Play raise-up anim.
    val raiseProgress = remember { Animatable(1f) }
    val iconAlpha = remember { Animatable(1f) }
    val prevTrackCount = remember { mutableStateOf(trackCount) }

    LaunchedEffect(trackCount) {
        if (trackCount > prevTrackCount.value) {
            // New track was added — play entrance animation
            raiseProgress.snapTo(0f)
            iconAlpha.snapTo(0f)
            // Fade in icon
            launch { iconAlpha.animateTo(1f, tween(180)) }
            // Slight delay then raise the surface
            delay(80)
            raiseProgress.animateTo(1f, tween(250))
        }
        prevTrackCount.value = trackCount
    }

    // ── Slide animation ────────────────────────────────────────────
    // 0f = closed (+ button at left edge), 1f = open (buttons visible)
    val slideProgress = remember { Animatable(if (isDrawerOpen) 1f else 0f) }

    LaunchedEffect(isDrawerOpen) {
        val target = if (isDrawerOpen) 1f else 0f
        slideProgress.animateTo(
            target,
            spring(
                dampingRatio = 0.85f,
                stiffness = 120f
            )
        )
    }

    // Compute the pixel width of the sliding content (3 buttons + gap)
    val typeButtonWidthDp = TRACK_LANE_HEIGHT // Each button is square
    val typeButtonCount = 3
    val trayGapDp = 10.dp // space between type buttons and + button
    val drawerWidthDp = typeButtonWidthDp * typeButtonCount + trayGapDp
    // Extra width extending off the left screen edge so the tray
    // feels like a physical rail, not a floating panel
    val trayOverhangDp = 48.dp

    val drawerWidthPx = with(density) { drawerWidthDp.toPx() }
    val trayOverhangPx = with(density) { trayOverhangDp.toPx() }

    // The + button stays "pressed in" while the drawer is open
    val plusIsLatched = slideProgress.value > 0.01f

    // Tray colors (hoisted outside draw scope)
    val trayBorder = NjOutline
    val traySurface = NjSurface2

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TRACK_LANE_HEIGHT)
    ) {
        // Clipped container: the tray slides within this
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .clipToBounds()
        ) {
            // The tray — one physical rail holding all buttons.
            // The overhang keeps the left edge off-screen even when
            // fully open, so the tray feels like it extends beyond
            // the device edge rather than floating.
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .offset(
                        x = with(density) {
                            ((-drawerWidthPx * (1f - slideProgress.value)) - trayOverhangPx).toDp()
                        }
                    )
                    .clip(RoundedCornerShape(6.dp))
                    .background(traySurface)
                    .border(1.dp, trayBorder, RoundedCornerShape(6.dp))
                    .drawWithContent {
                        drawContent()
                        // Subtle bevel: light top-left, dark bottom-right
                        val sw = 1.dp.toPx()
                        drawLine(
                            Color.White.copy(alpha = 0.07f),
                            Offset(sw, sw / 2), Offset(size.width - sw, sw / 2), sw
                        )
                        drawLine(
                            Color.White.copy(alpha = 0.04f),
                            Offset(sw / 2, sw), Offset(sw / 2, size.height - sw), sw
                        )
                        drawLine(
                            Color.Black.copy(alpha = 0.25f),
                            Offset(sw, size.height - sw / 2),
                            Offset(size.width - sw, size.height - sw / 2), sw
                        )
                        drawLine(
                            Color.Black.copy(alpha = 0.12f),
                            Offset(size.width - sw / 2, sw),
                            Offset(size.width - sw / 2, size.height - sw), sw
                        )
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left overhang — always off-screen, makes tray feel infinite
                Spacer(Modifier.width(trayOverhangDp))

                // Type buttons (revealed as tray slides open)
                AddTrackTypeButton(
                    icon = Icons.Filled.Mic,
                    label = "Audio",
                    onClick = { onAction(StudioAction.SelectNewTrackType(NewTrackType.AUDIO_RECORDING)) }
                )
                AddTrackTypeButton(
                    icon = Icons.Filled.Piano,
                    label = "MIDI",
                    onClick = { onAction(StudioAction.SelectNewTrackType(NewTrackType.MIDI_INSTRUMENT)) }
                )
                AddTrackTypeButton(
                    icon = Icons.Filled.GridOn,
                    label = "Drums",
                    onClick = { onAction(StudioAction.SelectNewTrackType(NewTrackType.DRUM_SEQUENCER)) }
                )

                // Gap between type buttons and + button
                Spacer(Modifier.width(trayGapDp))

                // The + button (always visible on the tray surface)
                AddTrackPlusButton(
                    isLatched = plusIsLatched,
                    raiseProgress = raiseProgress.value,
                    iconAlpha = iconAlpha.value,
                    onClick = { onAction(StudioAction.ToggleAddTrackDrawer) },
                    modifier = Modifier
                        .width(HEADER_WIDTH)
                        .fillMaxHeight()
                )
            }
        }
    }
}

/** The `+` button that fills the header slot and toggles the drawer. */
@Composable
private fun AddTrackPlusButton(
    isLatched: Boolean,
    raiseProgress: Float,
    iconAlpha: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedWithMinDuration()
    val view = LocalView.current
    val shape = RoundedCornerShape(4.dp)

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

    val raisedBg = RaisedBodyColor
    val pressedBg = PressedBodyColor
    val sunkBg = lerpColor(pressedBg, raisedBg, raiseProgress)
    val bgColor = when {
        isPressed -> pressedBg
        isLatched -> pressedBg
        else -> sunkBg
    }
    val fgColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .njGrain(alpha = 0.04f)
            .drawWithContent {
                drawContent()
                val sw = 1.dp.toPx()
                val isSunk = isPressed || isLatched || raiseProgress < 1f
                drawBevelEdges(sw, isSunk, raiseProgress)
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Add track",
            tint = fgColor,
            modifier = Modifier
                .size(24.dp)
                .alpha(iconAlpha)
        )
    }
}

/** Square beveled button with icon + label for the add-track drawer. */
@Composable
private fun AddTrackTypeButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedWithMinDuration()
    val view = LocalView.current
    val shape = RoundedCornerShape(4.dp)

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

    val bgColor = if (isPressed) PressedBodyColor else RaisedBodyColor
    val fgColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

    Box(
        modifier = Modifier
            .size(TRACK_LANE_HEIGHT)
            .clip(shape)
            .background(bgColor)
            .njGrain(alpha = 0.04f)
            .drawWithContent {
                drawContent()
                val sw = 1.dp.toPx()
                drawBevelEdges(sw, isPressed, if (isPressed) 0f else 1f)
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = fgColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fgColor
            )
        }
    }
}

/**
 * Shared bevel-edge drawing for add-track buttons.
 * [isSunk] = true for pressed/latched state (inner shadow).
 * [raiseFraction] blends between fully sunk (0) and fully raised (1)
 * for the entrance animation.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBevelEdges(
    sw: Float,
    isSunk: Boolean,
    raiseFraction: Float
) {
    if (isSunk) {
        // Inner shadow: dark top + left
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.18f),
                0.35f to Color.Transparent
            )
        )
        drawLine(
            Color.Black.copy(alpha = 0.40f),
            Offset(0f, sw / 2), Offset(size.width, sw / 2), sw * 1.5f
        )
        drawLine(
            Color.Black.copy(alpha = 0.20f),
            Offset(sw / 2, 0f), Offset(sw / 2, size.height), sw
        )
        // Subtle light bottom + right (rim catch)
        drawLine(
            Color.White.copy(alpha = 0.06f),
            Offset(0f, size.height - sw / 2), Offset(size.width, size.height - sw / 2), sw
        )
        drawLine(
            Color.White.copy(alpha = 0.04f),
            Offset(size.width - sw / 2, 0f), Offset(size.width - sw / 2, size.height), sw
        )
    } else {
        // Raised: light top + left, dark bottom + right
        // Blend alpha by raiseFraction for entrance animation
        val topAlpha = 0.09f * raiseFraction
        val leftAlpha = 0.05f * raiseFraction
        val bottomAlpha = 0.35f * raiseFraction
        val rightAlpha = 0.18f * raiseFraction
        drawLine(
            Color.White.copy(alpha = topAlpha),
            Offset(0f, sw / 2), Offset(size.width, sw / 2), sw
        )
        drawLine(
            Color.White.copy(alpha = leftAlpha),
            Offset(sw / 2, 0f), Offset(sw / 2, size.height), sw
        )
        drawLine(
            Color.Black.copy(alpha = bottomAlpha),
            Offset(0f, size.height - sw / 2), Offset(size.width, size.height - sw / 2), sw
        )
        drawLine(
            Color.Black.copy(alpha = rightAlpha),
            Offset(size.width - sw / 2, 0f), Offset(size.width - sw / 2, size.height), sw
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
            .background(NjAmber)
    )
}

/**
 * Cursor segment — teal dashed vertical line with optional downward-pointing
 * triangle at the top (ruler row only). The cursor marks where playback
 * starts and where new recordings land.
 */
@Composable
private fun CursorSegment(
    cursorPositionMs: Long,
    msPerDp: Float,
    height: Dp,
    showTriangle: Boolean
) {
    val offsetDp = (cursorPositionMs / msPerDp).dp
    val cursorColor = NjCursorTeal

    Canvas(
        modifier = Modifier
            .offset(x = offsetDp - 5.dp) // center the 10dp-wide triangle
            .width(10.dp)
            .height(height)
    ) {
        val centerX = size.width / 2f
        val dashEffect = PathEffect.dashPathEffect(
            floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f
        )

        // Dashed vertical line
        drawLine(
            color = cursorColor.copy(alpha = 0.5f),
            start = Offset(centerX, if (showTriangle) 8.dp.toPx() else 0f),
            end = Offset(centerX, size.height),
            strokeWidth = 1.5f.dp.toPx(),
            pathEffect = dashEffect
        )

        // Downward-pointing triangle at top (ruler only)
        if (showTriangle) {
            val triW = 10.dp.toPx()
            val triH = 8.dp.toPx()
            val triPath = Path().apply {
                moveTo(centerX - triW / 2f, 0f)
                lineTo(centerX + triW / 2f, 0f)
                lineTo(centerX, triH)
                close()
            }
            drawPath(triPath, cursorColor.copy(alpha = 0.85f))
        }
    }
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
                .background(NjAmber.copy(alpha = fillAlpha))
        )

        // Left boundary line
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(2.dp)
                .fillMaxHeight()
                .background(NjAmber.copy(alpha = borderAlpha))
        )

        // Right boundary line
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(2.dp)
                .fillMaxHeight()
                .background(NjAmber.copy(alpha = borderAlpha))
        )

        // Triangle handle indicators at the top corners (ruler only)
        if (showHandles) {
            val handleBaseColor = NjAmber
            Canvas(modifier = Modifier.matchParentSize()) {
                val triW = 10.dp.toPx()
                val triH = 8.dp.toPx()
                val handleColor = handleBaseColor.copy(alpha = handleAlpha)

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
private enum class RulerDragMode { SCRUB, DRAG_CURSOR, ADJUST_LOOP_START, ADJUST_LOOP_END }

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
    cursorPositionMs: Long,
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
    val currentCursorMs by rememberUpdatedState(cursorPositionMs)
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
                val cursorHitZonePx = 16.dp.toPx()

                fun pxToMs(px: Float): Long =
                    ((px / density.density) * msPerDp).toLong()
                        .coerceAtLeast(0L)

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

                    // Check cursor proximity
                    val cursorPx = msToPx(currentCursorMs)
                    val nearCursor = abs(touchX - cursorPx) <= cursorHitZonePx

                    val dragMode = when {
                        nearStart && nearEnd -> {
                            if (abs(touchX - startPx) <= abs(touchX - endPx))
                                RulerDragMode.ADJUST_LOOP_START else RulerDragMode.ADJUST_LOOP_END
                        }
                        nearStart -> RulerDragMode.ADJUST_LOOP_START
                        nearEnd -> RulerDragMode.ADJUST_LOOP_END
                        nearCursor -> RulerDragMode.DRAG_CURSOR
                        else -> RulerDragMode.SCRUB
                    }

                    when (dragMode) {
                        RulerDragMode.SCRUB -> {
                            // Disable during recording
                            if (currentIsRecording) return@awaitEachGesture

                            // Tap sets cursor; drag = scrub with seek
                            val tapMs = pxToMs(touchX)

                            var dragged = false
                            var lastMs = tapMs
                            try {
                                val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                                    change.consume()
                                }
                                if (slop != null) {
                                    dragged = true
                                    currentOnScrub(tapMs)
                                    horizontalDrag(slop.id) { change ->
                                        change.consume()
                                        val fingerMs = pxToMs(change.position.x)
                                        lastMs = fingerMs
                                        currentOnScrub(fingerMs)
                                    }
                                }
                            } finally {
                                // Always clear scrubbing state, even if the
                                // gesture coroutine is cancelled mid-drag
                                // (e.g. msPerDp changes, parent recomposes).
                                // Without this, isScrubbing in StudioScreen
                                // stays true forever and the playhead freezes.
                                if (dragged) {
                                    currentOnScrubFinished(lastMs)
                                }
                            }

                            if (!dragged) {
                                // Pure tap -- set cursor position
                                currentOnAction(StudioAction.SetCursorPosition(tapMs))
                            }
                        }

                        RulerDragMode.DRAG_CURSOR -> {
                            if (currentIsRecording) return@awaitEachGesture

                            // Drag the cursor triangle
                            val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                            }
                            if (slop != null) {
                                horizontalDrag(slop.id) { change ->
                                    change.consume()
                                    val fingerMs = pxToMs(change.position.x)
                                    currentOnAction(StudioAction.SetCursorPosition(fingerMs))
                                }
                            } else {
                                // Tap on cursor -- no-op (already at cursor position)
                            }
                        }

                        RulerDragMode.ADJUST_LOOP_START,
                        RulerDragMode.ADJUST_LOOP_END -> {
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
            }
    )
}

/**
 * A single take row in the clip-scoped expansion panel.
 * Shows the take name, waveform thumbnail, and active indicator.
 * Tap to activate (make it the active take), long-press for rename/delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudioClipTakeRow(
    take: TakeEntity,
    clipId: Long,
    trackId: Long,
    isActiveTake: Boolean,
    trackColor: Color,
    getAudioFile: (String) -> File,
    onAction: (StudioAction) -> Unit
) {
    val borderColor = if (isActiveTake) NjAmber.copy(alpha = 0.5f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TAKE_ROW_HEIGHT)
            .drawBehind {
                // Left active indicator
                if (isActiveTake) {
                    drawLine(
                        color = borderColor,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
            .background(NjSurface2.copy(alpha = if (isActiveTake) 0.6f else 0.35f))
            .combinedClickable(
                onClick = {
                    if (!isActiveTake) {
                        onAction(StudioAction.ActivateTake(clipId, take.id, trackId))
                    }
                },
                onLongClick = {
                    onAction(StudioAction.RequestRenameTake(take.id, clipId, take.displayName))
                }
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Take name + active label
        Column(
            modifier = Modifier.width(HEADER_WIDTH - 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = take.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActiveTake) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                },
                maxLines = 1
            )
            if (isActiveTake) {
                Text(
                    text = "Active",
                    style = MaterialTheme.typography.labelSmall,
                    color = NjAmber.copy(alpha = 0.6f)
                )
            }
        }

        // Waveform thumbnail
        Box(
            modifier = Modifier
                .weight(1f)
                .height(TAKE_ROW_HEIGHT - 8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(NjLane.copy(alpha = if (isActiveTake) 0.4f else 0.2f))
                .padding(vertical = 2.dp)
        ) {
            val barColor = if (isActiveTake) {
                trackColor.copy(alpha = 0.5f)
            } else {
                trackColor.copy(alpha = 0.2f)
            }

            NjWaveform(
                audioFile = getAudioFile(take.audioFileName),
                modifier = Modifier.fillMaxWidth(),
                barColor = barColor,
                height = TAKE_ROW_HEIGHT - 12.dp
            )
        }

        // Delete button
        Spacer(Modifier.width(4.dp))
        NjButton(
            text = "Del",
            icon = Icons.Filled.Delete,
            onClick = {
                onAction(StudioAction.RequestDeleteTake(take.id, clipId))
            },
            textColor = NjError.copy(alpha = 0.5f)
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
    val recordCoral = NjRecordCoral

    Column(
        modifier = Modifier
            .width(HEADER_WIDTH)
            .height(height)
            .drawBehind {
                drawLine(
                    color = recordCoral.copy(alpha = 0.6f),
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
            .background(NjLane.copy(alpha = 0.45f))
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
