package com.example.nightjar.ui.studio

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightjar.audio.MusicalTimeConverter
import com.example.nightjar.data.db.entity.MidiNoteEntity
import com.example.nightjar.ui.components.NjButton
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjAmber
import com.example.nightjar.ui.theme.NjPanelInset
import com.example.nightjar.ui.theme.NjSurface
import com.example.nightjar.ui.theme.NjTrackColors
import kotlinx.coroutines.launch

/** Row height per semitone. */
private val ROW_HEIGHT = 18.dp
/** 2 octaves visible at a time. */
private const val VISIBLE_PITCHES = 24
/** Total MIDI pitches (0-127). The grid renders all of them; only a
 *  2-octave slice is visible at any time, controlled by an animated
 *  vertical offset. */
private const val TOTAL_PITCHES = 128
/** Width of the octave button column. */
private val BUTTON_COL_WIDTH = 32.dp
/** Width of key labels. */
private val KEY_LABEL_WIDTH = 36.dp
/** Right-edge zone (extends past the note) where a long-press triggers
 *  a resize drag instead of a move. Makes the resize handle reachable on
 *  short notes without eating area that should pan the timeline. */
private val EDGE_TOUCH_ZONE = 16.dp
/** Tap-vs-double-tap threshold. */
private const val DOUBLE_TAP_TIMEOUT_MS = 400L

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val BLACK_KEY_INDICES = setOf(1, 3, 6, 8, 10)

/** Notes in the selected clip render at full saturation. */
private const val SELECTED_CLIP_ALPHA = 1f
/** Notes in background (non-selected) clips dim to this alpha. */
private const val BACKGROUND_CLIP_ALPHA = 0.4f
/** Fill alpha for the selected clip's window rectangle. */
private const val SELECTED_WINDOW_FILL_ALPHA = 0.12f
/** Fill alpha for non-selected clip window rectangles. */
private const val BACKGROUND_WINDOW_FILL_ALPHA = 0.06f
/** Darken overlay for gap regions (no clip covers this timeline range). */
private const val GAP_DARKEN_ALPHA = 0.22f

/** Hit-test result: the note and the clip it was visually hit in. */
private data class NoteHit(
    val note: MidiNoteEntity,
    val hitClipOffsetMs: Long
)

/** Live drag preview applied on top of the stored note positions during
 *  a move or resize drag. Cleared on commit / cancel. */
private data class DragPreview(
    val noteId: Long,
    val isResize: Boolean = false,
    val deltaMs: Long = 0L,
    val deltaPitch: Int = 0,
    val deltaDurationMs: Long = 0L
)

/**
 * Inline piano roll editor for the MidiTrackDrawer.
 *
 * Renders the entire MIDI track in absolute timeline-ms coordinates so the
 * user can see and edit notes across all clips without re-selecting from the
 * timeline. Clip windows are drawn as tinted backgrounds (selected brighter,
 * others muted) with boundary lines; gap regions between clips are darkened
 * at rest so the user can see non-writeable territory before tapping. Notes
 * in the selected clip render at full saturation; notes in other clips
 * render dimmed.
 *
 * Vertically the canvas covers the full 128-pitch MIDI range; a clipping
 * Box exposes only the 2-octave visible window, and an animated y-offset
 * positions the desired octaves inside that window. Octave-up/down flips
 * translate the canvas smoothly so notes that remain visible physically
 * slide instead of disappearing off-screen and coming back.
 *
 * ## Gestures (mirrors the full-screen piano roll)
 * - Down events are NOT consumed, so the wrapping `horizontalScroll` can
 *   claim quick swipes for timeline pan.
 * - Long-press on a note body or right-edge starts a move/resize drag.
 *   The drag uses absolute position tracking (canvas-x + scroll offset)
 *   so the canvas can pan during the drag without corrupting the delta.
 * - Quick tap on a note toggles selection; double-tap deletes.
 * - Tap on empty cell inside a clip places a note there.
 * - Tap in a gap region between clips flashes the gap (no placement).
 */
@Composable
fun MiniPianoRoll(
    trackState: MidiTrackUiState,
    trackId: Long,
    trackIndex: Int,
    bpm: Double,
    timeSignatureNumerator: Int,
    timeSignatureDenominator: Int,
    isSnapEnabled: Boolean,
    gridResolution: Int,
    globalPositionMs: Long,
    isPlaying: Boolean,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val textMeasurer = rememberTextMeasurer()
    val miniRollMuted2 = NjMuted2
    val panelInset = NjPanelInset
    val surfaceColor = NjSurface

    val clips = trackState.clips
    val selectedClipId = trackState.selectedClipId
    val offsetByClipId = remember(clips) { clips.associate { it.clipId to it.offsetMs } }

    // Live state references for the gesture handler. Wrapping in
    // rememberUpdatedState lets the long-running pointerInput coroutine
    // read fresh values without re-launching when notes update.
    val clipsState = rememberUpdatedState(clips)
    val offsetByClipIdState = rememberUpdatedState(offsetByClipId)

    var currentOctave by remember { mutableIntStateOf(3) }

    LaunchedEffect(Unit) {
        val allPitches = clips.flatMap { it.notes }.map { it.pitch }
        if (allPitches.isNotEmpty()) {
            val avg = allPitches.average().toInt()
            currentOctave = (avg / 12 - 2).coerceIn(0, 7)
        }
    }

    val visibleGridHeight = ROW_HEIGHT * VISIBLE_PITCHES
    val totalGridHeight = ROW_HEIGHT * TOTAL_PITCHES

    val msPerBeat = MusicalTimeConverter.msPerBeat(bpm, timeSignatureDenominator)
    val msPerMeasure = MusicalTimeConverter.msPerMeasure(bpm, timeSignatureNumerator, timeSignatureDenominator)
    val gridStepMs = MusicalTimeConverter.msPerGridStep(bpm, gridResolution, timeSignatureDenominator)

    val minDurationMs = (msPerMeasure * 2).toLong()
    val trackEndMs = clips.maxOfOrNull { it.offsetMs + it.effectiveLengthMs } ?: 0L
    val totalMs = maxOf(trackEndMs + msPerMeasure.toLong(), minDurationMs)

    val trackColor = NjTrackColors[trackIndex % NjTrackColors.size]
    val amberBoundary = NjAmber
    val rowHeightPx = with(density) { ROW_HEIGHT.toPx() }
    val pxPerMs = with(density) { 0.15f * this.density }
    val edgeZonePx = with(density) { EDGE_TOUCH_ZONE.toPx() }
    val canvasWidthPx = (totalMs * pxPerMs).coerceAtLeast(with(density) { 200.dp.toPx() })
    val canvasWidthDp = with(density) { (canvasWidthPx / this.density).dp }

    var selectedNoteId by remember { mutableStateOf<Long?>(null) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }
    var lastTapNoteId by remember { mutableStateOf<Long?>(null) }
    var dragPreview by remember { mutableStateOf<DragPreview?>(null) }

    val scope = rememberCoroutineScope()
    val gapFlickerAlpha = remember { Animatable(0f) }
    var gapFlickerRange by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    var stickyDuration by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(gridResolution) { stickyDuration = null }

    val labelStyle = remember { TextStyle(fontSize = 8.sp, color = Color.White.copy(alpha = 0.6f)) }
    val pitchLabels: Map<Int, TextLayoutResult> = remember(textMeasurer, labelStyle) {
        (0 until TOTAL_PITCHES).associateWith { pitch ->
            val noteName = NOTE_NAMES[pitch % 12]
            val label = if (noteName == "C") "C${pitch / 12 - 1}" else noteName
            textMeasurer.measure(label, labelStyle)
        }
    }

    val lowPitch = (currentOctave + 1) * 12
    val highPitchLimit = lowPitch + VISIBLE_PITCHES - 1
    val allNotes = remember(clips) { clips.flatMap { it.notes } }
    val notesAbove = allNotes.count { it.pitch > highPitchLimit }
    val notesBelow = allNotes.count { it.pitch < lowPitch }

    val highestVisiblePitch = highPitchLimit
    val topRowIndex = (TOTAL_PITCHES - 1) - highestVisiblePitch
    val targetVScrollPx = (topRowIndex * rowHeightPx).toInt()

    val vScrollState = rememberScrollState(initial = targetVScrollPx)

    LaunchedEffect(targetVScrollPx) {
        vScrollState.animateScrollTo(targetVScrollPx, animationSpec = tween(220))
    }

    val hScrollState = rememberScrollState()

    LaunchedEffect(selectedClipId) {
        val target = clips.find { it.clipId == selectedClipId } ?: clips.firstOrNull()
        if (target != null) {
            val centerMs = target.offsetMs + target.effectiveLengthMs / 2
            val centerPx = (centerMs * pxPerMs).toInt()
            hScrollState.animateScrollTo((centerPx - 120).coerceAtLeast(0))
        }
    }

    Row(modifier = modifier.fillMaxWidth().height(visibleGridHeight)) {
        Column(
            modifier = Modifier
                .width(BUTTON_COL_WIDTH)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NjButton(
                text = "",
                icon = Icons.Filled.KeyboardArrowUp,
                onClick = { currentOctave = (currentOctave + 1).coerceAtMost(7) },
                textColor = if (notesAbove > 0) NjAmber else NjMuted2
            )
            NjButton(
                text = "",
                icon = Icons.Filled.KeyboardArrowDown,
                onClick = { currentOctave = (currentOctave - 1).coerceAtLeast(0) },
                textColor = if (notesBelow > 0) NjAmber else NjMuted2
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(vScrollState, enabled = false)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(KEY_LABEL_WIDTH)
                        .height(totalGridHeight)
                        .background(panelInset)
                ) {
                    for (pitch in 0 until TOTAL_PITCHES) {
                        val rowIdx = (TOTAL_PITCHES - 1) - pitch
                        val y = rowIdx * rowHeightPx
                        val isBlack = (pitch % 12) in BLACK_KEY_INDICES

                        if (isBlack) {
                            drawRect(
                                color = surfaceColor,
                                topLeft = Offset(0f, y),
                                size = Size(size.width, rowHeightPx)
                            )
                        }

                        val result = pitchLabels[pitch]
                        if (result != null) {
                            drawText(
                                textLayoutResult = result,
                                topLeft = Offset(
                                    (size.width - result.size.width) / 2f,
                                    y + (rowHeightPx - result.size.height) / 2f
                                )
                            )
                        }

                        val isC = (pitch % 12) == 0
                        drawLine(
                            color = miniRollMuted2.copy(alpha = if (isC) 0.6f else 0.3f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = if (isC) 1f else 0.5f
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(hScrollState)
                ) {
                    Canvas(
                        modifier = Modifier
                            .width(canvasWidthDp)
                            .height(totalGridHeight)
                            .background(panelInset)
                            .pointerInput(
                                trackId, selectedClipId,
                                pxPerMs, rowHeightPx, edgeZonePx,
                                isSnapEnabled, gridStepMs, msPerBeat,
                                bpm, gridResolution, timeSignatureDenominator
                            ) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    // DON'T consume down -- horizontalScroll
                                    // needs the first crack at quick swipes.
                                    val downPos = down.position
                                    val downTime = System.currentTimeMillis()

                                    val edgeHit = findEdgeHitAbs(
                                        downPos, clipsState.value, pxPerMs, rowHeightPx, edgeZonePx
                                    )
                                    val bodyHit = if (edgeHit == null) {
                                        findBodyHitAbs(downPos, clipsState.value, pxPerMs, rowHeightPx)
                                    } else null

                                    when {
                                        edgeHit != null -> {
                                            val longPress = awaitLongPressOrCancellation(down.id)
                                            if (longPress != null) {
                                                longPress.consume()
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                // Immediate visual latch — render the note as
                                                // "grabbed" the moment long-press fires, so the
                                                // haptic and the pressed-in visual land together
                                                // instead of waiting for the first finger move.
                                                dragPreview = DragPreview(
                                                    noteId = edgeHit.note.id, isResize = true
                                                )
                                                val ownerOffset = offsetByClipIdState.value[edgeHit.note.clipId] ?: 0L
                                                handleResizeDragInline(
                                                    anchor = edgeHit.note,
                                                    pointerId = longPress.id,
                                                    pxPerMs = pxPerMs,
                                                    isSnapEnabled = isSnapEnabled,
                                                    bpm = bpm,
                                                    gridResolution = gridResolution,
                                                    timeSignatureDenominator = timeSignatureDenominator,
                                                    gridStepMs = gridStepMs,
                                                    ownerOffset = ownerOffset,
                                                    scrollX = { hScrollState.value },
                                                    onPreview = { delta ->
                                                        dragPreview = DragPreview(
                                                            noteId = edgeHit.note.id,
                                                            isResize = true,
                                                            deltaDurationMs = delta
                                                        )
                                                    },
                                                    onCommit = { delta ->
                                                        dragPreview = null
                                                        val newDuration =
                                                            (edgeHit.note.durationMs + delta).coerceAtLeast(50L)
                                                        onAction(
                                                            StudioAction.InlineResizeNote(
                                                                trackId, edgeHit.note.id, newDuration
                                                            )
                                                        )
                                                        stickyDuration = newDuration
                                                    },
                                                    onCancel = { dragPreview = null }
                                                )
                                            } else {
                                                handleNoteTap(
                                                    note = edgeHit.note,
                                                    downId = down.id,
                                                    downTime = downTime,
                                                    lastTapNoteId = lastTapNoteId,
                                                    lastTapTimeMs = lastTapTimeMs,
                                                    onSelect = { selectedNoteId = it },
                                                    onDoubleTapDelete = {
                                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                        onAction(StudioAction.InlineDeleteNote(trackId, it))
                                                    },
                                                    onUpdateLastTap = { id, ms ->
                                                        lastTapNoteId = id
                                                        lastTapTimeMs = ms
                                                    }
                                                )
                                            }
                                        }
                                        bodyHit != null -> {
                                            val longPress = awaitLongPressOrCancellation(down.id)
                                            if (longPress != null) {
                                                longPress.consume()
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                // Immediate visual latch — see the matching
                                                // comment in the edge branch above.
                                                dragPreview = DragPreview(noteId = bodyHit.note.id)
                                                val ownerOffset = offsetByClipIdState.value[bodyHit.note.clipId] ?: 0L
                                                handleMoveDragInline(
                                                    anchor = bodyHit.note,
                                                    pointerId = longPress.id,
                                                    pxPerMs = pxPerMs,
                                                    rowHeightPx = rowHeightPx,
                                                    isSnapEnabled = isSnapEnabled,
                                                    bpm = bpm,
                                                    gridResolution = gridResolution,
                                                    timeSignatureDenominator = timeSignatureDenominator,
                                                    gridStepMs = gridStepMs,
                                                    ownerOffset = ownerOffset,
                                                    scrollX = { hScrollState.value },
                                                    scrollY = { vScrollState.value },
                                                    onPreview = { dMs, dPitch ->
                                                        dragPreview = DragPreview(
                                                            noteId = bodyHit.note.id,
                                                            deltaMs = dMs,
                                                            deltaPitch = dPitch
                                                        )
                                                    },
                                                    onCommit = { dMs, dPitch ->
                                                        dragPreview = null
                                                        val newRawStart =
                                                            (bodyHit.note.startMs + dMs).coerceAtLeast(0L)
                                                        val newPitch =
                                                            (bodyHit.note.pitch + dPitch).coerceIn(0, TOTAL_PITCHES - 1)
                                                        onAction(
                                                            StudioAction.InlineMoveNote(
                                                                trackId, bodyHit.note.id, newRawStart, newPitch
                                                            )
                                                        )
                                                    },
                                                    onCancel = { dragPreview = null }
                                                )
                                            } else {
                                                handleNoteTap(
                                                    note = bodyHit.note,
                                                    downId = down.id,
                                                    downTime = downTime,
                                                    lastTapNoteId = lastTapNoteId,
                                                    lastTapTimeMs = lastTapTimeMs,
                                                    onSelect = { selectedNoteId = it },
                                                    onDoubleTapDelete = {
                                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                        onAction(StudioAction.InlineDeleteNote(trackId, it))
                                                    },
                                                    onUpdateLastTap = { id, ms ->
                                                        lastTapNoteId = id
                                                        lastTapTimeMs = ms
                                                    }
                                                )
                                            }
                                        }
                                        else -> {
                                            // Empty area: long-press is unused; on
                                            // cancellation, if the finger lifted
                                            // without moving past slop, treat as a
                                            // tap to place. Otherwise the parent
                                            // horizontalScroll has already claimed
                                            // the gesture.
                                            val longPress = awaitLongPressOrCancellation(down.id)
                                            if (longPress == null) {
                                                val fingerLifted = currentEvent.changes
                                                    .none { it.id == down.id && it.pressed }
                                                if (fingerLifted) {
                                                    handleEmptyTap(
                                                        downPos = downPos,
                                                        clips = clipsState.value,
                                                        pxPerMs = pxPerMs,
                                                        rowHeightPx = rowHeightPx,
                                                        bpm = bpm,
                                                        isSnapEnabled = isSnapEnabled,
                                                        gridResolution = gridResolution,
                                                        timeSignatureDenominator = timeSignatureDenominator,
                                                        gridStepMs = gridStepMs,
                                                        msPerBeat = msPerBeat,
                                                        stickyDuration = stickyDuration,
                                                        totalMs = totalMs,
                                                        onPlace = { clipId, pitch, startMs, durationMs ->
                                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                            onAction(
                                                                StudioAction.InlinePlaceNote(
                                                                    trackId, clipId, pitch, startMs, durationMs
                                                                )
                                                            )
                                                        },
                                                        onGapTap = { range ->
                                                            gapFlickerRange = range
                                                            scope.launch {
                                                                gapFlickerAlpha.snapTo(1f)
                                                                gapFlickerAlpha.animateTo(0f, tween(160))
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        drawMiniRollGrid(
                            rowHeightPx = rowHeightPx,
                            totalMs = totalMs,
                            pxPerMs = pxPerMs,
                            msPerBeat = msPerBeat,
                            msPerMeasure = msPerMeasure,
                            gridStepMs = gridStepMs,
                            isSnapEnabled = isSnapEnabled,
                            muted2Color = miniRollMuted2,
                            blackKeyBgColor = surfaceColor
                        )

                        drawGapRegions(clips, totalMs, pxPerMs)

                        drawClipWindows(
                            clips = clips,
                            selectedClipId = selectedClipId,
                            pxPerMs = pxPerMs,
                            trackColor = trackColor,
                            boundaryColor = amberBoundary
                        )

                        val bevelW = 3f
                        val cr = CornerRadius(3f, 3f)
                        for (clip in clips) {
                            val isSelectedClip = clip.clipId == selectedClipId
                            val noteAlpha = if (isSelectedClip) SELECTED_CLIP_ALPHA else BACKGROUND_CLIP_ALPHA
                            val clipEndPx = (clip.offsetMs + clip.effectiveLengthMs) * pxPerMs

                            for (note in clip.notes) {
                                if (note.startMs >= clip.effectiveLengthMs) continue

                                val preview = dragPreview
                                val isPreview = preview != null && preview.noteId == note.id
                                val effectiveStartMs = if (isPreview && !preview!!.isResize) {
                                    (note.startMs + preview.deltaMs).coerceAtLeast(0L)
                                } else note.startMs
                                val effectivePitch = if (isPreview && !preview!!.isResize) {
                                    (note.pitch + preview.deltaPitch).coerceIn(0, TOTAL_PITCHES - 1)
                                } else note.pitch
                                val effectiveDurationMs = if (isPreview && preview!!.isResize) {
                                    (note.durationMs + preview.deltaDurationMs).coerceAtLeast(50L)
                                } else note.durationMs

                                val rowIdx = (TOTAL_PITCHES - 1) - effectivePitch
                                if (rowIdx < 0 || rowIdx >= TOTAL_PITCHES) continue

                                val absStartMs = clip.offsetMs + effectiveStartMs
                                val x = absStartMs * pxPerMs
                                val rawW = (effectiveDurationMs * pxPerMs).coerceAtLeast(6f)
                                val w = (minOf(x + rawW, clipEndPx) - x).coerceAtLeast(1f)
                                val ny = rowIdx * rowHeightPx + 1f
                                val h = rowHeightPx - 2f

                                val isSelectedNote =
                                    (note.id == selectedNoteId && isSelectedClip) || isPreview
                                val fill = trackColor.copy(alpha = noteAlpha)

                                if (isSelectedNote) {
                                    drawRoundRect(fill, Offset(x, ny), Size(w, h), cr)
                                    drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(x, ny), Size(w, h), cr)
                                    drawLine(Color.Black.copy(alpha = 0.7f * noteAlpha), Offset(x, ny), Offset(x + w, ny), bevelW)
                                    drawLine(Color.Black.copy(alpha = 0.5f * noteAlpha), Offset(x, ny), Offset(x, ny + h), bevelW)
                                    drawLine(Color.White.copy(alpha = 0.15f * noteAlpha), Offset(x, ny + h), Offset(x + w, ny + h), bevelW)
                                    drawLine(Color.White.copy(alpha = 0.1f * noteAlpha), Offset(x + w, ny), Offset(x + w, ny + h), bevelW)
                                } else {
                                    drawRoundRect(fill, Offset(x, ny), Size(w, h), cr)
                                    drawLine(Color.White.copy(alpha = 0.5f * noteAlpha), Offset(x, ny), Offset(x + w, ny), bevelW)
                                    drawLine(Color.White.copy(alpha = 0.3f * noteAlpha), Offset(x, ny), Offset(x, ny + h), bevelW)
                                    drawLine(Color.Black.copy(alpha = 0.6f * noteAlpha), Offset(x, ny + h), Offset(x + w, ny + h), bevelW)
                                    drawLine(Color.Black.copy(alpha = 0.4f * noteAlpha), Offset(x + w, ny), Offset(x + w, ny + h), bevelW)
                                }

                                if (isPlaying) {
                                    val noteEndCappedMs = minOf(
                                        effectiveStartMs + effectiveDurationMs,
                                        clip.effectiveLengthMs
                                    )
                                    val absEndMs = clip.offsetMs + noteEndCappedMs
                                    if (globalPositionMs in absStartMs until absEndMs) {
                                        drawRoundRect(
                                            color = amberBoundary.copy(alpha = 0.55f * noteAlpha),
                                            topLeft = Offset(x, ny),
                                            size = Size(w, h),
                                            cornerRadius = cr
                                        )
                                    }
                                }
                            }
                        }

                        val flickerAlpha = gapFlickerAlpha.value
                        val flickerRange = gapFlickerRange
                        if (flickerAlpha > 0f && flickerRange != null) {
                            val (s, e) = flickerRange
                            drawRect(
                                color = amberBoundary.copy(alpha = 0.35f * flickerAlpha),
                                topLeft = Offset(s * pxPerMs, 0f),
                                size = Size((e - s) * pxPerMs, size.height)
                            )
                        }

                        if (isPlaying) {
                            val playheadX = globalPositionMs * pxPerMs
                            if (playheadX in 0f..size.width) {
                                val trailLenPx = 260f * pxPerMs
                                val trailStartX = (playheadX - trailLenPx).coerceAtLeast(0f)
                                val trailW = playheadX - trailStartX
                                if (trailW > 0f) {
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            colorStops = arrayOf(
                                                0f to amberBoundary.copy(alpha = 0f),
                                                1f to amberBoundary.copy(alpha = 0.32f)
                                            ),
                                            startX = trailStartX,
                                            endX = playheadX
                                        ),
                                        topLeft = Offset(trailStartX, 0f),
                                        size = Size(trailW, size.height)
                                    )
                                }
                                drawLine(
                                    color = amberBoundary.copy(alpha = 0.95f),
                                    start = Offset(playheadX, 0f),
                                    end = Offset(playheadX, size.height),
                                    strokeWidth = 1.5f
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Resize-drag loop. Called after long-press is confirmed. Tracks
 * absolute X (canvas-x + scroll offset) so the drag delta stays correct
 * even if the canvas pans during the drag.
 */
private suspend fun AwaitPointerEventScope.handleResizeDragInline(
    anchor: MidiNoteEntity,
    pointerId: PointerId,
    pxPerMs: Float,
    isSnapEnabled: Boolean,
    bpm: Double,
    gridResolution: Int,
    timeSignatureDenominator: Int,
    gridStepMs: Double,
    ownerOffset: Long,
    scrollX: () -> Int,
    onPreview: (deltaDurationMs: Long) -> Unit,
    onCommit: (deltaDurationMs: Long) -> Unit,
    onCancel: () -> Unit
) {
    var startAbsX: Float? = null
    var lastDelta = 0L
    var completed = false

    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
        if (startAbsX == null) startAbsX = change.position.x + scrollX()
        if (!change.pressed) {
            completed = true
            change.consume()
            break
        }
        change.consume()
        val totalDx = change.position.x + scrollX() - startAbsX
        val rawDelta = (totalDx / pxPerMs).toLong()
        val snappedDelta = if (isSnapEnabled && gridStepMs > 0) {
            val anchorEndAbs = ownerOffset + anchor.startMs + anchor.durationMs
            val newEndAbs = anchorEndAbs + rawDelta
            val snappedEndAbs = MusicalTimeConverter.snapToGrid(
                newEndAbs, bpm, gridResolution, timeSignatureDenominator
            )
            snappedEndAbs - anchorEndAbs
        } else rawDelta
        lastDelta = snappedDelta
        onPreview(snappedDelta)
    }

    if (completed) onCommit(lastDelta) else onCancel()
}

/**
 * Move-drag loop. Like resize but tracks both axes. The drag is gated on
 * the source clip's offset (the note's `clipId` always points to the
 * source row under the linked-clip model), so dragging an instance's
 * visual still produces the correct delta when applied to the shared
 * underlying note.
 */
private suspend fun AwaitPointerEventScope.handleMoveDragInline(
    anchor: MidiNoteEntity,
    pointerId: PointerId,
    pxPerMs: Float,
    rowHeightPx: Float,
    isSnapEnabled: Boolean,
    bpm: Double,
    gridResolution: Int,
    timeSignatureDenominator: Int,
    gridStepMs: Double,
    ownerOffset: Long,
    scrollX: () -> Int,
    scrollY: () -> Int,
    onPreview: (deltaMs: Long, deltaPitch: Int) -> Unit,
    onCommit: (deltaMs: Long, deltaPitch: Int) -> Unit,
    onCancel: () -> Unit
) {
    var startAbsX: Float? = null
    var startAbsY: Float? = null
    var lastDeltaMs = 0L
    var lastDeltaPitch = 0
    var completed = false

    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
        if (startAbsX == null) {
            startAbsX = change.position.x + scrollX()
            startAbsY = change.position.y + scrollY()
        }
        if (!change.pressed) {
            completed = true
            change.consume()
            break
        }
        change.consume()
        val totalDx = change.position.x + scrollX() - startAbsX
        val totalDy = change.position.y + scrollY() - startAbsY!!

        val rawDeltaMs = (totalDx / pxPerMs).toLong()
        val snappedDeltaMs = if (isSnapEnabled && gridStepMs > 0) {
            val anchorAbs = ownerOffset + anchor.startMs
            val snappedStartAbs = MusicalTimeConverter.snapToGrid(
                anchorAbs + rawDeltaMs, bpm, gridResolution, timeSignatureDenominator
            )
            snappedStartAbs - anchorAbs
        } else rawDeltaMs

        val deltaPitch = -(totalDy / rowHeightPx).toInt()
        val clampedNewPitch = (anchor.pitch + deltaPitch).coerceIn(0, TOTAL_PITCHES - 1)
        val clampedDeltaPitch = clampedNewPitch - anchor.pitch

        lastDeltaMs = snappedDeltaMs
        lastDeltaPitch = clampedDeltaPitch
        onPreview(snappedDeltaMs, clampedDeltaPitch)
    }

    if (completed) onCommit(lastDeltaMs, lastDeltaPitch) else onCancel()
}

private fun handleNoteTap(
    note: MidiNoteEntity,
    downId: PointerId,
    downTime: Long,
    lastTapNoteId: Long?,
    lastTapTimeMs: Long,
    onSelect: (Long?) -> Unit,
    onDoubleTapDelete: (Long) -> Unit,
    onUpdateLastTap: (Long?, Long) -> Unit
) {
    if (lastTapNoteId == note.id && downTime - lastTapTimeMs < DOUBLE_TAP_TIMEOUT_MS) {
        onDoubleTapDelete(note.id)
        onUpdateLastTap(null, 0L)
    } else {
        onSelect(note.id)
        onUpdateLastTap(note.id, downTime)
    }
}

private fun handleEmptyTap(
    downPos: Offset,
    clips: List<MidiClipUiState>,
    pxPerMs: Float,
    rowHeightPx: Float,
    bpm: Double,
    isSnapEnabled: Boolean,
    gridResolution: Int,
    timeSignatureDenominator: Int,
    gridStepMs: Double,
    msPerBeat: Double,
    stickyDuration: Long?,
    totalMs: Long,
    onPlace: (clipId: Long, pitch: Int, startMs: Long, durationMs: Long) -> Unit,
    onGapTap: (Pair<Long, Long>) -> Unit
) {
    val tappedAbsMs = (downPos.x / pxPerMs).toLong()
    val tappedPitch = (TOTAL_PITCHES - 1) - (downPos.y / rowHeightPx).toInt()
    val snappedAbsMs = if (isSnapEnabled && gridStepMs > 0) {
        MusicalTimeConverter.snapToGrid(
            tappedAbsMs, bpm, gridResolution, timeSignatureDenominator
        )
    } else tappedAbsMs

    val containingClip = clips.firstOrNull { c ->
        snappedAbsMs >= c.offsetMs && snappedAbsMs < c.offsetMs + c.effectiveLengthMs
    }
    if (containingClip != null) {
        val clipRelativeMs = (snappedAbsMs - containingClip.offsetMs).coerceAtLeast(0L)
        val noteDuration = stickyDuration
            ?: if (gridStepMs > 0) gridStepMs.toLong() else msPerBeat.toLong()
        onPlace(
            containingClip.clipId,
            tappedPitch.coerceIn(0, TOTAL_PITCHES - 1),
            clipRelativeMs,
            noteDuration.coerceAtLeast(50)
        )
    } else {
        onGapTap(findEnclosingGap(snappedAbsMs, clips, totalMs))
    }
}

private fun DrawScope.drawGapRegions(
    clips: List<MidiClipUiState>,
    totalMs: Long,
    pxPerMs: Float
) {
    val sortedClips = clips.sortedBy { it.offsetMs }
    var gapStart = 0L
    val gapColor = Color.Black.copy(alpha = GAP_DARKEN_ALPHA)
    for (clip in sortedClips) {
        if (gapStart < clip.offsetMs) {
            drawRect(
                color = gapColor,
                topLeft = Offset(gapStart * pxPerMs, 0f),
                size = Size((clip.offsetMs - gapStart) * pxPerMs, size.height)
            )
        }
        gapStart = maxOf(gapStart, clip.offsetMs + clip.effectiveLengthMs)
    }
    if (gapStart < totalMs) {
        drawRect(
            color = gapColor,
            topLeft = Offset(gapStart * pxPerMs, 0f),
            size = Size((totalMs - gapStart) * pxPerMs, size.height)
        )
    }
}

private fun DrawScope.drawClipWindows(
    clips: List<MidiClipUiState>,
    selectedClipId: Long?,
    pxPerMs: Float,
    trackColor: Color,
    boundaryColor: Color
) {
    for (clip in clips) {
        val isSelected = clip.clipId == selectedClipId
        val x0 = clip.offsetMs * pxPerMs
        val xEnd = (clip.offsetMs + clip.effectiveLengthMs) * pxPerMs
        val fillAlpha = if (isSelected) SELECTED_WINDOW_FILL_ALPHA else BACKGROUND_WINDOW_FILL_ALPHA
        drawRect(
            color = trackColor.copy(alpha = fillAlpha),
            topLeft = Offset(x0, 0f),
            size = Size(xEnd - x0, size.height)
        )
        val boundaryAlpha = if (isSelected) 0.7f else 0.28f
        val boundaryWidth = if (isSelected) 1.5f else 0.75f
        val boundary = boundaryColor.copy(alpha = boundaryAlpha)
        drawLine(boundary, Offset(x0, 0f), Offset(x0, size.height), boundaryWidth)
        drawLine(boundary, Offset(xEnd, 0f), Offset(xEnd, size.height), boundaryWidth)
    }
}

private fun DrawScope.drawMiniRollGrid(
    rowHeightPx: Float,
    totalMs: Long,
    pxPerMs: Float,
    msPerBeat: Double,
    msPerMeasure: Double,
    gridStepMs: Double,
    isSnapEnabled: Boolean,
    muted2Color: Color,
    blackKeyBgColor: Color
) {
    for (pitch in 0 until TOTAL_PITCHES) {
        val rowIdx = (TOTAL_PITCHES - 1) - pitch
        val y = rowIdx * rowHeightPx
        val isBlack = (pitch % 12) in BLACK_KEY_INDICES

        if (isBlack) {
            drawRect(
                color = blackKeyBgColor,
                topLeft = Offset(0f, y),
                size = Size(size.width, rowHeightPx)
            )
        }

        val isC = (pitch % 12) == 0
        drawLine(
            color = muted2Color.copy(alpha = if (isC) 0.6f else 0.3f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = if (isC) 1f else 0.5f
        )
    }

    if (msPerMeasure > 0 && msPerBeat > 0) {
        var ms = 0.0
        while (ms < totalMs) {
            val x = (ms * pxPerMs).toFloat()
            val isMeasure = ms % msPerMeasure < 0.5
            val isBeat = ms % msPerBeat < 0.5

            val alpha: Float
            val width: Float
            when {
                isMeasure -> { alpha = 0.5f; width = 1.5f }
                isBeat -> { alpha = 0.3f; width = 1f }
                else -> { alpha = 0.2f; width = 0.5f }
            }
            drawLine(
                color = muted2Color.copy(alpha = alpha),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = width
            )

            ms += if (isSnapEnabled && gridStepMs > 0) gridStepMs else msPerBeat
        }
    }
}

/**
 * Right-edge hit test in absolute timeline coords. Returns a hit if the
 * tap is within [edgeZonePx] of a note's right edge (extending past the
 * end of the note for a more reachable resize handle on short notes).
 * Edge zone is clamped to the note's own start so a tiny note doesn't
 * have its body swallowed by the edge zone.
 */
private fun findEdgeHitAbs(
    position: Offset,
    clips: List<MidiClipUiState>,
    pxPerMs: Float,
    rowHeightPx: Float,
    edgeZonePx: Float
): NoteHit? {
    val pitch = (TOTAL_PITCHES - 1) - (position.y / rowHeightPx).toInt()
    for (clip in clips) {
        for (note in clip.notes) {
            if (note.pitch != pitch) continue
            if (note.startMs >= clip.effectiveLengthMs) continue
            val absStart = clip.offsetMs + note.startMs
            val startPx = absStart * pxPerMs
            val endPx = (absStart + note.durationMs) * pxPerMs
            val edgeStart = (endPx - edgeZonePx).coerceAtLeast(startPx)
            if (position.x in edgeStart..(endPx + edgeZonePx)) {
                return NoteHit(note, clip.offsetMs)
            }
        }
    }
    return null
}

/**
 * Body hit test in absolute timeline coords. Strict bounds — does NOT
 * extend past the note. Edge hits go through [findEdgeHitAbs] first so
 * the edge zone wins in the overlap region.
 */
private fun findBodyHitAbs(
    position: Offset,
    clips: List<MidiClipUiState>,
    pxPerMs: Float,
    rowHeightPx: Float
): NoteHit? {
    val pitch = (TOTAL_PITCHES - 1) - (position.y / rowHeightPx).toInt()
    val tapMs = (position.x / pxPerMs).toLong()
    for (clip in clips) {
        for (note in clip.notes) {
            if (note.pitch != pitch) continue
            if (note.startMs >= clip.effectiveLengthMs) continue
            val absStart = clip.offsetMs + note.startMs
            val absEnd = absStart + note.durationMs
            if (tapMs in absStart..absEnd) {
                return NoteHit(note, clip.offsetMs)
            }
        }
    }
    return null
}

/**
 * Resolve the gap region that encloses [absMs]. Returns (gapStartMs,
 * gapEndMs) where the range is bounded by neighboring clip edges (or by 0
 * and [totalMs] at the extremes). Called only when no clip contains the
 * position, so the range is guaranteed to be non-empty.
 */
private fun findEnclosingGap(
    absMs: Long,
    clips: List<MidiClipUiState>,
    totalMs: Long
): Pair<Long, Long> {
    val sorted = clips.sortedBy { it.offsetMs }
    var gapStart = 0L
    for (clip in sorted) {
        if (absMs < clip.offsetMs) {
            return gapStart to clip.offsetMs
        }
        gapStart = maxOf(gapStart, clip.offsetMs + clip.effectiveLengthMs)
    }
    return gapStart to totalMs
}
