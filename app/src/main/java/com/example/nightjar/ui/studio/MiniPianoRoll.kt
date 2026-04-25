package com.example.nightjar.ui.studio

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import kotlin.math.abs

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

    // Current base octave (MIDI octave, where octave 4 = C4 = MIDI 60).
    // Shows 2 octaves: currentOctave and currentOctave+1.
    var currentOctave by remember { mutableIntStateOf(3) }

    // Auto-center the octave window on the track's average pitch when the
    // drawer first composes for this track.
    LaunchedEffect(Unit) {
        val allPitches = clips.flatMap { it.notes }.map { it.pitch }
        if (allPitches.isNotEmpty()) {
            val avg = allPitches.average().toInt()
            currentOctave = (avg / 12 - 2).coerceIn(0, 7)
        }
    }

    val visibleGridHeight = ROW_HEIGHT * VISIBLE_PITCHES
    val totalGridHeight = ROW_HEIGHT * TOTAL_PITCHES

    // Musical time helpers
    val msPerBeat = MusicalTimeConverter.msPerBeat(bpm, timeSignatureDenominator)
    val msPerMeasure = MusicalTimeConverter.msPerMeasure(bpm, timeSignatureNumerator, timeSignatureDenominator)
    val gridStepMs = MusicalTimeConverter.msPerGridStep(bpm, gridResolution, timeSignatureDenominator)

    // Canvas spans the whole track horizontally: from 0 to the far edge of
    // the last clip, floored at 2 measures so a single short clip still has
    // breathing room.
    val minDurationMs = (msPerMeasure * 2).toLong()
    val trackEndMs = clips.maxOfOrNull { it.offsetMs + it.effectiveLengthMs } ?: 0L
    val totalMs = maxOf(trackEndMs + msPerMeasure.toLong(), minDurationMs)

    val trackColor = NjTrackColors[trackIndex % NjTrackColors.size]
    val amberBoundary = NjAmber
    val rowHeightPx = with(density) { ROW_HEIGHT.toPx() }
    val pxPerMs = with(density) { 0.15f * this.density }
    val canvasWidthPx = (totalMs * pxPerMs).coerceAtLeast(with(density) { 200.dp.toPx() })
    val canvasWidthDp = with(density) { (canvasWidthPx / this.density).dp }

    // Gesture + selection state
    var selectedNoteId by remember { mutableStateOf<Long?>(null) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }
    var lastTapNoteId by remember { mutableStateOf<Long?>(null) }

    // Gap-reject feedback: when the user taps in a gap region, flash the
    // enclosing gap with a brief amber wash so the rejection is visible.
    val scope = rememberCoroutineScope()
    val gapFlickerAlpha = remember { Animatable(0f) }
    var gapFlickerRange by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    // Sticky note duration: captures last resize for subsequent placements.
    var stickyDuration by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(gridResolution) { stickyDuration = null }

    // Pre-measure all 128 pitch labels so we don't pay layout cost on every
    // frame. Re-runs only if the TextMeasurer changes (e.g., font/density).
    val labelStyle = remember { TextStyle(fontSize = 8.sp, color = Color.White.copy(alpha = 0.6f)) }
    val pitchLabels: Map<Int, TextLayoutResult> = remember(textMeasurer, labelStyle) {
        (0 until TOTAL_PITCHES).associateWith { pitch ->
            val noteName = NOTE_NAMES[pitch % 12]
            val label = if (noteName == "C") "C${pitch / 12 - 1}" else noteName
            textMeasurer.measure(label, labelStyle)
        }
    }

    // Indicator dots on the octave buttons: are there notes outside the
    // currently-visible 2-octave window?
    val lowPitch = (currentOctave + 1) * 12
    val highPitchLimit = lowPitch + VISIBLE_PITCHES - 1
    val allNotes = remember(clips) { clips.flatMap { it.notes } }
    val notesAbove = allNotes.count { it.pitch > highPitchLimit }
    val notesBelow = allNotes.count { it.pitch < lowPitch }

    // Vertical scroll that brings the desired 2-octave window into the
    // viewport. The highest visible pitch sits at the TOP of the window.
    // In canvas coords (pitch 127 at row 0), that pitch is at row index
    // `(TOTAL_PITCHES - 1 - highVisible)`, so the scroll position is that
    // many rows worth of pixels (positive = scrolled down).
    val highestVisiblePitch = highPitchLimit
    val topRowIndex = (TOTAL_PITCHES - 1) - highestVisiblePitch
    val targetVScrollPx = (topRowIndex * rowHeightPx).toInt()

    // Vertical scroll state, initialized at the right octave so the first
    // frame already shows the correct pitches (no warm-up animation).
    val vScrollState = rememberScrollState(initial = targetVScrollPx)

    // Animate to a new octave when the user clicks an arrow button. On
    // first composition target equals initial, so animateScrollTo is a
    // no-op; subsequent currentOctave changes glide over 220ms.
    LaunchedEffect(targetVScrollPx) {
        vScrollState.animateScrollTo(targetVScrollPx, animationSpec = tween(220))
    }

    // Shared horizontal scroll across octave flips so the user's timeline
    // position persists when they page vertically.
    val hScrollState = rememberScrollState()

    // Center the viewport on the selected clip on first open, and animate
    // to a new selection whenever the user taps a different clip on the
    // timeline.
    LaunchedEffect(selectedClipId) {
        val target = clips.find { it.clipId == selectedClipId } ?: clips.firstOrNull()
        if (target != null) {
            val centerMs = target.offsetMs + target.effectiveLengthMs / 2
            val centerPx = (centerMs * pxPerMs).toInt()
            hScrollState.animateScrollTo((centerPx - 120).coerceAtLeast(0))
        }
    }

    Row(modifier = modifier.fillMaxWidth().height(visibleGridHeight)) {
        // Octave buttons column
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

        // Visible 2-octave window — verticalScroll handles the tall grid.
        // Drag-to-scroll is disabled; only programmatic animateScrollTo
        // fires when the octave buttons change `currentOctave`. Notes that
        // remain visible across an octave change physically slide as the
        // scroll animates; notes leaving and entering do so as a side
        // effect of the same scroll.
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
                // Key labels — full pitch range
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

                // Horizontally scrollable note grid — full pitch range
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
                                clips, selectedClipId,
                                pxPerMs, rowHeightPx, isSnapEnabled, gridStepMs, msPerBeat
                            ) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val downPos = down.position
                                    val downTime = System.currentTimeMillis()
                                    val scrollAtDown = hScrollState.value

                                    val hit = hitTestAbsolute(
                                        x = downPos.x,
                                        y = downPos.y,
                                        clips = clips,
                                        pxPerMs = pxPerMs,
                                        rowHeightPx = rowHeightPx
                                    )
                                    val hitEdge = hit?.let {
                                        isNearRightEdgeAbs(downPos.x, it.hitClipOffsetMs, it.note, pxPerMs)
                                    } ?: false

                                    if (hit != null) down.consume()

                                    val isDoubleTap = hit != null &&
                                        hit.note.id == lastTapNoteId &&
                                        (downTime - lastTapTimeMs) < 400

                                    if (isDoubleTap && hit != null) {
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        onAction(StudioAction.InlineDeleteNote(trackId, hit.note.id))
                                        lastTapNoteId = null
                                        lastTapTimeMs = 0
                                        return@awaitEachGesture
                                    }

                                    val initialRawStartMs = hit?.note?.startMs ?: 0L
                                    val initialDurationMs = hit?.note?.durationMs ?: 0L
                                    val initialPitch = hit?.note?.pitch ?: 0

                                    var moved = false
                                    var lastPos = downPos
                                    val touchSlop = viewConfiguration.touchSlop

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                        if (!change.pressed) {
                                            val scrolled = hScrollState.value != scrollAtDown
                                            change.consume()

                                            if (!moved && !scrolled) {
                                                if (hit != null) {
                                                    selectedNoteId = hit.note.id
                                                } else {
                                                    val tappedAbsMs = (downPos.x / pxPerMs).toLong()
                                                    val tappedPitch = (TOTAL_PITCHES - 1) -
                                                        (downPos.y / rowHeightPx).toInt()
                                                    val snappedAbsMs = if (isSnapEnabled && gridStepMs > 0) {
                                                        MusicalTimeConverter.snapToGrid(
                                                            tappedAbsMs, bpm, gridResolution, timeSignatureDenominator
                                                        )
                                                    } else tappedAbsMs

                                                    val containingClip = clips.firstOrNull { c ->
                                                        snappedAbsMs >= c.offsetMs &&
                                                            snappedAbsMs < c.offsetMs + c.effectiveLengthMs
                                                    }
                                                    if (containingClip != null) {
                                                        val clipRelativeMs =
                                                            (snappedAbsMs - containingClip.offsetMs).coerceAtLeast(0L)
                                                        val noteDuration = stickyDuration
                                                            ?: if (gridStepMs > 0) gridStepMs.toLong() else msPerBeat.toLong()
                                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                        onAction(
                                                            StudioAction.InlinePlaceNote(
                                                                trackId, containingClip.clipId,
                                                                tappedPitch.coerceIn(0, TOTAL_PITCHES - 1),
                                                                clipRelativeMs,
                                                                noteDuration.coerceAtLeast(50)
                                                            )
                                                        )
                                                    } else {
                                                        gapFlickerRange = findEnclosingGap(
                                                            snappedAbsMs, clips, totalMs
                                                        )
                                                        scope.launch {
                                                            gapFlickerAlpha.snapTo(1f)
                                                            gapFlickerAlpha.animateTo(0f, tween(160))
                                                        }
                                                    }
                                                }
                                                lastTapNoteId = hit?.note?.id
                                                lastTapTimeMs = downTime
                                            }
                                            break
                                        }

                                        val delta = change.position - lastPos
                                        if (!moved && (abs(delta.x) > touchSlop || abs(delta.y) > touchSlop)) {
                                            moved = true
                                            if (hit != null) {
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            }
                                        }

                                        if (moved && hit != null) {
                                            change.consume()
                                            lastPos = change.position

                                            if (hitEdge) {
                                                val deltaXpx = change.position.x - downPos.x
                                                val deltaMs = (deltaXpx / pxPerMs).toLong()
                                                val newDuration = (initialDurationMs + deltaMs).coerceAtLeast(50L)
                                                val snappedDuration = if (isSnapEnabled && gridStepMs > 0) {
                                                    val ownerOffset = offsetByClipId[hit.note.clipId] ?: 0L
                                                    val newAbsEnd = ownerOffset + initialRawStartMs + newDuration
                                                    val snappedEndAbs = MusicalTimeConverter.snapToGrid(
                                                        newAbsEnd, bpm, gridResolution, timeSignatureDenominator
                                                    )
                                                    (snappedEndAbs - (ownerOffset + initialRawStartMs))
                                                        .coerceAtLeast(gridStepMs.toLong().coerceAtLeast(50L))
                                                } else newDuration
                                                onAction(
                                                    StudioAction.InlineResizeNote(
                                                        trackId, hit.note.id, snappedDuration
                                                    )
                                                )
                                                stickyDuration = snappedDuration
                                            } else {
                                                val deltaXpx = change.position.x - downPos.x
                                                val deltaYpx = change.position.y - downPos.y
                                                val deltaMs = (deltaXpx / pxPerMs).toLong()
                                                val deltaRows = (deltaYpx / rowHeightPx).toInt()

                                                val newRawStart = (initialRawStartMs + deltaMs).coerceAtLeast(0L)
                                                val newPitch = (initialPitch - deltaRows).coerceIn(0, TOTAL_PITCHES - 1)

                                                val ownerOffset = offsetByClipId[hit.note.clipId] ?: 0L
                                                val snappedRaw = if (isSnapEnabled && gridStepMs > 0) {
                                                    val absStart = ownerOffset + newRawStart
                                                    val snappedAbs = MusicalTimeConverter.snapToGrid(
                                                        absStart, bpm, gridResolution, timeSignatureDenominator
                                                    )
                                                    (snappedAbs - ownerOffset).coerceAtLeast(0L)
                                                } else newRawStart

                                                onAction(
                                                    StudioAction.InlineMoveNote(
                                                        trackId, hit.note.id, snappedRaw, newPitch
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        // 1) Grid: row stripes + beat/measure lines, full pitch range.
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

                        // 2) Gap regions: darken non-clip areas at rest.
                        drawGapRegions(clips, totalMs, pxPerMs)

                        // 3) Clip window rectangles + boundary lines.
                        drawClipWindows(
                            clips = clips,
                            selectedClipId = selectedClipId,
                            pxPerMs = pxPerMs,
                            trackColor = trackColor,
                            boundaryColor = amberBoundary
                        )

                        // 4) Notes — render at canvas-y based on pitch directly,
                        // no octave-window filtering.
                        val bevelW = 3f
                        val cr = CornerRadius(3f, 3f)
                        for (clip in clips) {
                            val isSelectedClip = clip.clipId == selectedClipId
                            val noteAlpha = if (isSelectedClip) SELECTED_CLIP_ALPHA else BACKGROUND_CLIP_ALPHA
                            val clipEndPx = (clip.offsetMs + clip.effectiveLengthMs) * pxPerMs

                            for (note in clip.notes) {
                                if (note.startMs >= clip.effectiveLengthMs) continue

                                val rowIdx = (TOTAL_PITCHES - 1) - note.pitch
                                if (rowIdx < 0 || rowIdx >= TOTAL_PITCHES) continue

                                val absStartMs = clip.offsetMs + note.startMs
                                val x = absStartMs * pxPerMs
                                val rawW = (note.durationMs * pxPerMs).coerceAtLeast(6f)
                                val w = (minOf(x + rawW, clipEndPx) - x).coerceAtLeast(1f)
                                val ny = rowIdx * rowHeightPx + 1f
                                val h = rowHeightPx - 2f

                                val isSelectedNote = note.id == selectedNoteId && isSelectedClip
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
                                        note.startMs + note.durationMs,
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

                        // 5) Gap-reject flicker.
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

                        // 6) Playhead sweep with gradient trail.
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
 * Hit-test notes in absolute timeline + canvas-y coordinates. Pitch is
 * derived directly from canvas y (`pitch = (TOTAL_PITCHES - 1) - rowIdx`)
 * so the hit-test is independent of which 2-octave slice is currently
 * visible.
 */
private fun hitTestAbsolute(
    x: Float,
    y: Float,
    clips: List<MidiClipUiState>,
    pxPerMs: Float,
    rowHeightPx: Float
): NoteHit? {
    for (clip in clips) {
        for (note in clip.notes) {
            if (note.startMs >= clip.effectiveLengthMs) continue

            val rowIdx = (TOTAL_PITCHES - 1) - note.pitch
            if (rowIdx < 0 || rowIdx >= TOTAL_PITCHES) continue

            val absStart = clip.offsetMs + note.startMs
            val noteX = absStart * pxPerMs
            val noteY = rowIdx * rowHeightPx
            val noteW = (note.durationMs * pxPerMs).coerceAtLeast(12f)

            if (x >= noteX && x <= noteX + noteW &&
                y >= noteY && y <= noteY + rowHeightPx
            ) {
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

private fun isNearRightEdgeAbs(
    x: Float,
    hitClipOffsetMs: Long,
    note: MidiNoteEntity,
    pxPerMs: Float
): Boolean {
    val absStart = hitClipOffsetMs + note.startMs
    val absEnd = absStart + note.durationMs
    val noteStartPx = absStart * pxPerMs
    val noteEndPx = absEnd * pxPerMs
    val midpoint = (noteStartPx + noteEndPx) / 2f
    return x >= midpoint && x <= noteEndPx + 12f
}
