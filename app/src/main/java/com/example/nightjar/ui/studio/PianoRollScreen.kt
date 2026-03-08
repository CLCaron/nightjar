package com.example.nightjar.ui.studio

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipPrevious
import com.example.nightjar.ui.components.NjIcons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.nightjar.audio.MusicalTimeConverter
import com.example.nightjar.data.db.entity.MidiNoteEntity
import com.example.nightjar.ui.components.NjButton
import com.example.nightjar.ui.theme.NjBg
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjOnBg
import com.example.nightjar.ui.theme.NjOutline
import com.example.nightjar.ui.theme.NjStudioAccent
import com.example.nightjar.ui.theme.NjStudioGreen
import com.example.nightjar.ui.theme.NjSurface
import com.example.nightjar.ui.theme.NjTrackColors
import kotlin.math.abs

/** Height of each semitone row in dp. */
private const val ROW_HEIGHT_DP = 20f
/** Width of the piano keys panel in dp. */
private const val KEYS_WIDTH_DP = 48f
/** Pixels per millisecond at default zoom. */
private const val PX_PER_MS = 0.2f
/** Total visible range: MIDI notes 0-127 */
private const val TOTAL_NOTES = 128
/** Default starting octave scroll position (middle C area). */
private const val DEFAULT_SCROLL_NOTE = 48

/** Touch zone width for detecting resize drag on a note's right edge. */
private val EDGE_TOUCH_ZONE = 16.dp

/** Fast long-press threshold in ms (matches Timeline). */
private const val FAST_LONG_PRESS_MS = 200L
/** Double-tap detection window in ms. */
private const val DOUBLE_TAP_TIMEOUT_MS = 300L

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val BLACK_KEYS = setOf(1, 3, 6, 8, 10) // indices within octave

/** Local preview state for an in-progress note drag (move or resize). */
private data class NoteDragPreview(
    val noteId: Long,
    val previewStartMs: Long,
    val previewDurationMs: Long,
    val previewPitch: Int
)

/** Records the last tap for double-tap detection across gesture cycles. */
private data class TapInfo(
    val timeMs: Long,
    val position: Offset,
    val noteId: Long?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PianoRollScreen(
    onBack: () -> Unit,
    viewModel: PianoRollViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val density = LocalDensity.current

    val rowHeightPx = with(density) { ROW_HEIGHT_DP.dp.toPx() }
    val totalGridHeight = (TOTAL_NOTES * ROW_HEIGHT_DP).dp

    // Compute grid width from content (BPM-aware)
    val measureMs = MusicalTimeConverter.msPerMeasure(
        state.bpm, state.timeSignatureNumerator, state.timeSignatureDenominator
    ).toLong().coerceAtLeast(1L)
    val paddingMs = measureMs * 4          // 4 measures of empty space after last note
    val minContentMs = measureMs * 16      // always show at least 16 measures
    val maxNoteEndMs = state.notes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
    val contentMs = maxOf(maxNoteEndMs + paddingMs, state.totalDurationMs + paddingMs, minContentMs)
    val gridWidthDp = (contentMs * PX_PER_MS).dp

    val verticalScrollState = rememberScrollState(
        (DEFAULT_SCROLL_NOTE * ROW_HEIGHT_DP * density.density).toInt()
    )
    val horizontalScrollState = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()

    // Track color for notes
    val noteColor = NjTrackColors[0] // Will be parameterized by track sortIndex

    val view = LocalView.current

    // Drag preview state (local to composable, not in ViewModel)
    var dragPreview by remember { mutableStateOf<NoteDragPreview?>(null) }

    // Double-tap detection state (persists across gesture cycles)
    var lastTapInfo by remember { mutableStateOf<TapInfo?>(null) }

    // Faster long-press for note drag -- 200ms instead of the default 400ms
    val baseViewConfig = LocalViewConfiguration.current
    val fastViewConfig = remember(baseViewConfig) {
        object : ViewConfiguration by baseViewConfig {
            override val longPressTimeoutMillis: Long get() = FAST_LONG_PRESS_MS
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NjBg)
            .statusBarsPadding()
    ) {
        // Toolbar
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = state.trackName.ifEmpty { "Piano Roll" },
                        style = MaterialTheme.typography.titleSmall,
                        color = NjOnBg
                    )
                    if (state.instrumentName.isNotEmpty()) {
                        Text(
                            text = state.instrumentName,
                            style = MaterialTheme.typography.labelSmall,
                            color = NjMuted2
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = NjOnBg
                    )
                }
            },
            actions = {
                NjButton(
                    text = "Snap",
                    onClick = { viewModel.onAction(PianoRollAction.ToggleSnap) },
                    isActive = state.isSnapEnabled,
                    ledColor = NjStudioAccent
                )
                Spacer(Modifier.width(4.dp))
                NjButton(
                    text = "Restart",
                    icon = Icons.Filled.SkipPrevious,
                    onClick = { viewModel.onAction(PianoRollAction.SeekTo(0L)) },
                    textColor = NjStudioGreen.copy(alpha = 0.5f),
                )
                Spacer(Modifier.width(4.dp))
                NjButton(
                    text = "Play",
                    icon = NjIcons.PlayPause,
                    onClick = {
                        if (state.isPlaying) viewModel.onAction(PianoRollAction.Pause)
                        else viewModel.onAction(PianoRollAction.Play)
                    },
                    isActive = state.isPlaying,
                    ledColor = NjStudioGreen
                )
                Spacer(Modifier.width(8.dp))
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = NjSurface
            )
        )

        // Piano keys + Grid
        Row(modifier = Modifier.fillMaxSize()) {
            // Piano keys column (scrolls vertically with the grid)
            Box(
                modifier = Modifier
                    .width(KEYS_WIDTH_DP.dp)
                    .fillMaxHeight()
                    .verticalScroll(verticalScrollState)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(KEYS_WIDTH_DP.dp)
                        .height(totalGridHeight)
                ) {
                    drawPianoKeys(rowHeightPx, textMeasurer)
                }
            }

            // Grid canvas (scrolls both X and Y)
            CompositionLocalProvider(LocalViewConfiguration provides fastViewConfig) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(verticalScrollState)
                        .horizontalScroll(horizontalScrollState)
                ) {
                    Canvas(
                        modifier = Modifier
                            .width(gridWidthDp)
                            .height(totalGridHeight)
                            .pointerInput(state.notes, state.isSnapEnabled, state.bpm) {
                                val pxPerMs = PX_PER_MS * density.density
                                val edgeZonePx = EDGE_TOUCH_ZONE.toPx()

                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    // DON'T consume down -- lets scroll handle swipes

                                    // Check edge hit FIRST (extends past the note boundary)
                                    val edgeNote = findNoteEdgeAt(
                                        down.position, state.notes, rowHeightPx, pxPerMs, edgeZonePx
                                    )
                                    // Then check body hit (strict bounds)
                                    val hitNote = edgeNote
                                        ?: findNoteAt(down.position, state.notes, rowHeightPx, pxPerMs)

                                    if (edgeNote != null) {
                                        // ── RIGHT EDGE: hold to resize ──
                                        val longPress = awaitLongPressOrCancellation(down.id)
                                        if (longPress != null) {
                                            longPress.consume()
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            handleResizeDrag(
                                                edgeNote, longPress.id, pxPerMs, rowHeightPx,
                                                state.isSnapEnabled, state.bpm,
                                                state.timeSignatureDenominator,
                                                scrollX = { horizontalScrollState.value },
                                                onPreview = { dragPreview = it },
                                                onCommit = { noteId, newDurationMs ->
                                                    dragPreview = null
                                                    viewModel.onAction(
                                                        PianoRollAction.ResizeNote(noteId, newDurationMs)
                                                    )
                                                },
                                                onCancel = { dragPreview = null }
                                            )
                                        } else {
                                            // Tap on edge = select the note
                                            val fingerLifted = currentEvent.changes
                                                .none { it.id == down.id && it.pressed }
                                            if (fingerLifted) {
                                                viewModel.onAction(
                                                    PianoRollAction.SelectNote(edgeNote.id)
                                                )
                                                lastTapInfo = TapInfo(
                                                    System.currentTimeMillis(), down.position, edgeNote.id
                                                )
                                            }
                                        }
                                    } else if (hitNote != null) {
                                        // ── NOTE BODY: hold to move, tap to select, double-tap to delete ──
                                        val longPress = awaitLongPressOrCancellation(down.id)
                                        if (longPress != null) {
                                            longPress.consume()
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            handleMoveDrag(
                                                hitNote, longPress.id, pxPerMs, rowHeightPx,
                                                state.isSnapEnabled, state.bpm,
                                                state.timeSignatureDenominator,
                                                scrollX = { horizontalScrollState.value },
                                                scrollY = { verticalScrollState.value },
                                                onPreview = { dragPreview = it },
                                                onPitchCrossed = { pitch ->
                                                    viewModel.onAction(
                                                        PianoRollAction.PreviewPitch(pitch)
                                                    )
                                                },
                                                onCommit = { noteId, newStartMs, newPitch ->
                                                    dragPreview = null
                                                    viewModel.onAction(
                                                        PianoRollAction.MoveNote(noteId, newStartMs)
                                                    )
                                                    if (newPitch != hitNote.pitch) {
                                                        viewModel.onAction(
                                                            PianoRollAction.ChangeNotePitch(noteId, newPitch)
                                                        )
                                                    }
                                                },
                                                onCancel = { dragPreview = null }
                                            )
                                        } else {
                                            val fingerLifted = currentEvent.changes
                                                .none { it.id == down.id && it.pressed }
                                            if (fingerLifted) {
                                                val now = System.currentTimeMillis()
                                                val prev = lastTapInfo
                                                val isDoubleTap = prev != null &&
                                                    prev.noteId == hitNote.id &&
                                                    (now - prev.timeMs) < DOUBLE_TAP_TIMEOUT_MS
                                                if (isDoubleTap) {
                                                    viewModel.onAction(
                                                        PianoRollAction.DeleteNote(hitNote.id)
                                                    )
                                                    lastTapInfo = null
                                                } else {
                                                    viewModel.onAction(
                                                        PianoRollAction.SelectNote(hitNote.id)
                                                    )
                                                    lastTapInfo = TapInfo(now, down.position, hitNote.id)
                                                }
                                            }
                                        }
                                    } else {
                                        // ── EMPTY CELL: tap to place note ──
                                        val longPress = awaitLongPressOrCancellation(down.id)
                                        if (longPress == null) {
                                            val fingerLifted = currentEvent.changes
                                                .none { it.id == down.id && it.pressed }
                                            if (fingerLifted) {
                                                val pitch = TOTAL_NOTES - 1 -
                                                    (down.position.y / rowHeightPx).toInt()
                                                val tapMs = (down.position.x / pxPerMs).toLong()
                                                val snapMs = if (state.isSnapEnabled) {
                                                    MusicalTimeConverter.snapToBeat(
                                                        tapMs, state.bpm,
                                                        state.timeSignatureDenominator
                                                    )
                                                } else tapMs
                                                val beatMs = (60_000.0 / state.bpm).toLong()
                                                viewModel.onAction(
                                                    PianoRollAction.PlaceNote(
                                                        pitch = pitch.coerceIn(0, 127),
                                                        startMs = snapMs.coerceAtLeast(0L),
                                                        durationMs = beatMs
                                                    )
                                                )
                                                lastTapInfo = null
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        drawGrid(
                            rowHeightPx = rowHeightPx,
                            pxPerMs = PX_PER_MS * density.density,
                            notes = state.notes,
                            selectedNoteId = state.selectedNoteId,
                            noteColor = noteColor,
                            positionMs = state.positionMs,
                            isPlaying = state.isPlaying,
                            bpm = state.bpm,
                            beatsPerBar = state.timeSignatureNumerator,
                            contentMs = contentMs,
                            dragPreview = dragPreview
                        )
                    }
                }
            }
        }
    }
}

/** Draw the piano key strip on the left side. */
private fun DrawScope.drawPianoKeys(
    rowHeightPx: Float,
    textMeasurer: TextMeasurer
) {
    val width = size.width

    for (note in 0 until TOTAL_NOTES) {
        val displayNote = TOTAL_NOTES - 1 - note
        val y = note * rowHeightPx
        val octaveIndex = displayNote % 12
        val isBlack = octaveIndex in BLACK_KEYS

        // Key background
        val keyColor = if (isBlack) Color(0xFF1A1520) else Color(0xFF2A2530)
        drawRect(
            color = keyColor,
            topLeft = Offset(0f, y),
            size = Size(width, rowHeightPx)
        )

        // Separator
        drawLine(
            color = NjOutline,
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 0.5f
        )

        // Label for C notes
        if (octaveIndex == 0) {
            val octave = displayNote / 12 - 1
            val label = "C$octave"
            val result = textMeasurer.measure(
                text = label,
                style = TextStyle(color = NjOnBg, fontSize = 10.sp)
            )
            drawText(
                textLayoutResult = result,
                topLeft = Offset(4f, y + (rowHeightPx - result.size.height) / 2f)
            )
        }
    }
}

/** Draw the note grid, beat lines, notes, and playhead. */
private fun DrawScope.drawGrid(
    rowHeightPx: Float,
    pxPerMs: Float,
    notes: List<MidiNoteEntity>,
    selectedNoteId: Long?,
    noteColor: Color,
    positionMs: Long,
    isPlaying: Boolean,
    bpm: Double,
    beatsPerBar: Int,
    contentMs: Long,
    dragPreview: NoteDragPreview? = null
) {
    val totalHeight = TOTAL_NOTES * rowHeightPx
    val beatMs = 60_000.0 / bpm

    // Row backgrounds (alternate for black/white keys)
    for (note in 0 until TOTAL_NOTES) {
        val displayNote = TOTAL_NOTES - 1 - note
        val y = note * rowHeightPx
        val octaveIndex = displayNote % 12
        val isBlack = octaveIndex in BLACK_KEYS

        if (isBlack) {
            drawRect(
                color = Color(0xFF0D0B14),
                topLeft = Offset(0f, y),
                size = Size(size.width, rowHeightPx)
            )
        }

        // Row separator
        drawLine(
            color = NjOutline.copy(alpha = 0.3f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 0.5f
        )
    }

    // Beat and measure grid lines
    var timeMs = 0.0
    var beatCount = 0
    while (timeMs < contentMs) {
        val x = (timeMs * pxPerMs).toFloat()
        val isBar = beatCount % beatsPerBar == 0
        val alpha = if (isBar) 0.4f else 0.15f
        val strokeWidth = if (isBar) 1.5f else 0.5f

        drawLine(
            color = NjMuted2.copy(alpha = alpha),
            start = Offset(x, 0f),
            end = Offset(x, totalHeight),
            strokeWidth = strokeWidth
        )

        timeMs += beatMs
        beatCount++
    }

    // Draw notes
    for (note in notes) {
        val isDragging = dragPreview != null && note.id == dragPreview.noteId
        val drawPitch = if (isDragging) dragPreview!!.previewPitch else note.pitch
        val drawStartMs = if (isDragging) dragPreview!!.previewStartMs else note.startMs
        val drawDurationMs = if (isDragging) dragPreview!!.previewDurationMs else note.durationMs

        val rowIndex = TOTAL_NOTES - 1 - drawPitch
        val y = rowIndex * rowHeightPx + 1f
        val x = drawStartMs * pxPerMs
        val w = (drawDurationMs * pxPerMs).coerceAtLeast(4f)
        val h = rowHeightPx - 2f

        val isSelected = note.id == selectedNoteId
        val color = when {
            isDragging -> noteColor // full brightness during drag
            isSelected -> noteColor
            else -> noteColor.copy(alpha = 0.75f)
        }
        val cornerRadius = CornerRadius(3f, 3f)

        drawRoundRect(
            color = color,
            topLeft = Offset(x, y),
            size = Size(w, h),
            cornerRadius = cornerRadius
        )

        // Selection or drag border
        if (isSelected || isDragging) {
            drawRoundRect(
                color = if (isDragging) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.5f),
                topLeft = Offset(x, y),
                size = Size(w, h),
                cornerRadius = cornerRadius,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isDragging) 2f else 1.5f)
            )
        }
    }

    // Playhead
    if (isPlaying || positionMs > 0) {
        val playheadX = positionMs * pxPerMs
        drawLine(
            color = NjStudioAccent,
            start = Offset(playheadX, 0f),
            end = Offset(playheadX, totalHeight),
            strokeWidth = 2f
        )
    }
}

/** Find a note whose right edge is near the touch position (extends past the note boundary). */
private fun findNoteEdgeAt(
    position: Offset,
    notes: List<MidiNoteEntity>,
    rowHeightPx: Float,
    pxPerMs: Float,
    edgeZonePx: Float
): MidiNoteEntity? {
    val pitch = TOTAL_NOTES - 1 - (position.y / rowHeightPx).toInt()
    return notes.find { note ->
        note.pitch == pitch &&
            abs(position.x - (note.startMs + note.durationMs) * pxPerMs) < edgeZonePx
    }
}

/** Find the note (if any) at a given canvas position (strict body hit). */
private fun findNoteAt(
    position: Offset,
    notes: List<MidiNoteEntity>,
    rowHeightPx: Float,
    pxPerMs: Float
): MidiNoteEntity? {
    val pitch = TOTAL_NOTES - 1 - (position.y / rowHeightPx).toInt()
    val tapMs = (position.x / pxPerMs).toLong()
    return notes.find { note ->
        note.pitch == pitch &&
            tapMs >= note.startMs &&
            tapMs <= note.startMs + note.durationMs
    }
}

/**
 * Handle resize drag on a note's right edge.
 * Called AFTER long-press is confirmed -- only handles the drag phase.
 *
 * Uses absolute position tracking (canvas position + scroll offset) instead of
 * accumulating [positionChange] deltas. The scroll containers on the parent Box
 * move the canvas under the finger during the long-press wait, which neutralizes
 * relative deltas. Absolute coordinates are invariant to scroll movement.
 */
private suspend fun AwaitPointerEventScope.handleResizeDrag(
    note: MidiNoteEntity,
    pointerId: PointerId,
    pxPerMs: Float,
    rowHeightPx: Float,
    isSnapEnabled: Boolean,
    bpm: Double,
    timeSignatureDenominator: Int,
    scrollX: () -> Int,
    onPreview: (NoteDragPreview) -> Unit,
    onCommit: (noteId: Long, newDurationMs: Long) -> Unit,
    onCancel: () -> Unit
) {
    onPreview(
        NoteDragPreview(note.id, note.startMs, note.durationMs, note.pitch)
    )

    // Track absolute position (canvas position + scroll offset) to stay
    // invariant when the scroll container moves the canvas under the finger.
    var startAbsX: Float? = null
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
        val accumulatedPx = change.position.x + scrollX() - startAbsX
        val deltaMs = (accumulatedPx / pxPerMs).toLong()
        val newDurationMs = (note.durationMs + deltaMs).coerceAtLeast(50L)
        onPreview(
            NoteDragPreview(note.id, note.startMs, newDurationMs, note.pitch)
        )
    }

    if (completed && startAbsX != null) {
        val lastEvent = currentEvent.changes.firstOrNull { it.id == pointerId }
        val finalAbsX = if (lastEvent != null) lastEvent.position.x + scrollX() else startAbsX
        val accumulatedPx = finalAbsX - startAbsX
        val deltaMs = (accumulatedPx / pxPerMs).toLong()
        var finalDurationMs = (note.durationMs + deltaMs).coerceAtLeast(50L)
        if (isSnapEnabled) {
            val snappedEndMs = MusicalTimeConverter.snapToBeat(
                note.startMs + finalDurationMs, bpm, timeSignatureDenominator
            )
            finalDurationMs = (snappedEndMs - note.startMs).coerceAtLeast(50L)
        }
        onCommit(note.id, finalDurationMs)
    } else {
        onCancel()
    }
}

/**
 * Handle move drag on a note body (time + pitch).
 * Called AFTER long-press is confirmed -- only handles the drag phase.
 *
 * Uses absolute position tracking for the same reason as [handleResizeDrag]:
 * scroll containers move the canvas under the finger, neutralizing relative deltas.
 */
private suspend fun AwaitPointerEventScope.handleMoveDrag(
    note: MidiNoteEntity,
    pointerId: PointerId,
    pxPerMs: Float,
    rowHeightPx: Float,
    isSnapEnabled: Boolean,
    bpm: Double,
    timeSignatureDenominator: Int,
    scrollX: () -> Int,
    scrollY: () -> Int,
    onPreview: (NoteDragPreview) -> Unit,
    onPitchCrossed: (Int) -> Unit,
    onCommit: (noteId: Long, newStartMs: Long, newPitch: Int) -> Unit,
    onCancel: () -> Unit
) {
    var lastPreviewPitch = note.pitch

    onPreview(
        NoteDragPreview(note.id, note.startMs, note.durationMs, note.pitch)
    )

    // Track absolute position (canvas position + scroll offset) to stay
    // invariant when the scroll container moves the canvas under the finger.
    var startAbsX: Float? = null
    var startAbsY: Float? = null
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

        val deltaMs = (totalDx / pxPerMs).toLong()
        val newStartMs = (note.startMs + deltaMs).coerceAtLeast(0L)
        val pitchDelta = -(totalDy / rowHeightPx).toInt()
        val newPitch = (note.pitch + pitchDelta).coerceIn(0, 127)

        onPreview(
            NoteDragPreview(note.id, newStartMs, note.durationMs, newPitch)
        )

        if (newPitch != lastPreviewPitch) {
            onPitchCrossed(newPitch)
            lastPreviewPitch = newPitch
        }
    }

    if (completed && startAbsX != null) {
        val lastEvent = currentEvent.changes.firstOrNull { it.id == pointerId }
        val finalAbsX = if (lastEvent != null) lastEvent.position.x + scrollX() else startAbsX
        val finalAbsY = if (lastEvent != null) lastEvent.position.y + scrollY() else startAbsY!!
        val totalDx = finalAbsX - startAbsX
        val totalDy = finalAbsY - startAbsY!!

        val deltaMs = (totalDx / pxPerMs).toLong()
        var finalStartMs = (note.startMs + deltaMs).coerceAtLeast(0L)
        val pitchDelta = -(totalDy / rowHeightPx).toInt()
        val finalPitch = (note.pitch + pitchDelta).coerceIn(0, 127)

        if (isSnapEnabled) {
            finalStartMs = MusicalTimeConverter.snapToBeat(
                finalStartMs, bpm, timeSignatureDenominator
            )
        }
        onCommit(note.id, finalStartMs, finalPitch)
    } else {
        onCancel()
    }
}
