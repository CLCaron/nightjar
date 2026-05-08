package com.example.nightjar.ui.studio

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
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
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import com.example.nightjar.ui.components.NjIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
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
import com.example.nightjar.ui.components.NjKnob
import com.example.nightjar.ui.components.NjRecessedPanel
import com.example.nightjar.ui.components.NjRotarySelector
import com.example.nightjar.ui.theme.IbmPlexMono
import com.example.nightjar.ui.theme.NjBg
import com.example.nightjar.ui.theme.NjCursorTeal
import com.example.nightjar.ui.theme.NjMuted
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjOnBg
import com.example.nightjar.ui.theme.NjAmber
import com.example.nightjar.ui.theme.NjLedGreen
import com.example.nightjar.ui.theme.NjSurface
import com.example.nightjar.ui.theme.NjLane
import com.example.nightjar.ui.theme.NjPanelInset
import com.example.nightjar.ui.theme.NjError
import com.example.nightjar.ui.theme.NjTrackColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import com.example.nightjar.audio.MusicalScaleHelper
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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

/** Zoom limits for pinch-to-zoom. */
private const val MIN_H_ZOOM = 0.25f
private const val MAX_H_ZOOM = 8.0f
private const val MIN_V_ZOOM = 0.5f
private const val MAX_V_ZOOM = 3.0f
/** Maximum canvas pixel width to prevent GPU texture overflow. */
private const val MAX_CANVAS_PX = 32768f

/** Touch zone width for detecting resize drag on a note's right edge. */
private val EDGE_TOUCH_ZONE = 16.dp

/** Fast long-press threshold in ms (matches Timeline). */
private const val FAST_LONG_PRESS_MS = 200L

/**
 * Fixed envelope height for the per-tab content row in the sub-panel. Sized
 * to the tallest tab (SCALE, two sub-rows: knob unit ~71dp + button row ~56dp
 * + gap + padding). Other tabs center vertically within this height so
 * switching tabs no longer makes the sub-panel jump.
 */
private val SUB_PANEL_CONTENT_HEIGHT = 148.dp

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val BLACK_KEYS = setOf(1, 3, 6, 8, 10) // indices within octave

/** Local preview state for an in-progress note drag (move or resize). */
private data class GroupDragState(
    val noteIds: Set<Long>,       // all notes being dragged
    val anchorNoteId: Long,       // the note the user touched
    val deltaMs: Long = 0L,       // time offset (move mode)
    val deltaPitch: Int = 0,      // pitch offset (move mode)
    val deltaDurationMs: Long = 0L, // duration offset (right-edge resize mode)
    val deltaStartMs: Long = 0L,    // start offset (left-edge resize mode; end anchored)
    val isResize: Boolean = false,
    val isLeftEdgeResize: Boolean = false
)

/** Marquee selection rectangle in canvas-content coordinates. */
private data class MarqueeRect(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)

/** Timeout for double-tap-to-delete detection. */
private const val DOUBLE_TAP_TIMEOUT_MS = 300L

@Composable
fun PianoRollScreen(
    onBack: () -> Unit,
    viewModel: PianoRollViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // Zoom state (view-layer only, not in ViewModel)
    var horizontalZoom by remember { mutableFloatStateOf(1f) }
    var verticalZoom by remember { mutableFloatStateOf(1f) }
    var isPinching by remember { mutableStateOf(false) }

    val rowHeightPx = with(density) { (ROW_HEIGHT_DP * verticalZoom).dp.toPx() }
    val totalGridHeight = (TOTAL_NOTES * ROW_HEIGHT_DP * verticalZoom).dp

    // Compute grid width from content (BPM-aware)
    val measureMs = MusicalTimeConverter.msPerMeasure(
        state.bpm, state.timeSignatureNumerator, state.timeSignatureDenominator
    ).toLong().coerceAtLeast(1L)
    val paddingMs = measureMs * 4          // 4 measures of empty space after last note
    val minContentMs = measureMs * 16      // always show at least 16 measures
    val maxNoteEndMs = state.notes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
    val maxClipEndMs = state.clips.maxOfOrNull { it.endMs } ?: 0L
    val contentMs = maxOf(maxNoteEndMs + paddingMs, maxClipEndMs + paddingMs,
        state.totalDurationMs + paddingMs, minContentMs)

    // Dynamically clamp horizontal zoom so canvas width stays under MAX_CANVAS_PX
    val baseWidthPx = contentMs * PX_PER_MS * density.density
    val effectiveMaxHZoom = if (baseWidthPx > 0f) {
        (MAX_CANVAS_PX / baseWidthPx).coerceIn(MIN_H_ZOOM, MAX_H_ZOOM)
    } else MAX_H_ZOOM
    horizontalZoom = horizontalZoom.coerceAtMost(effectiveMaxHZoom)

    val gridWidthDp = (contentMs * PX_PER_MS * horizontalZoom).dp

    val verticalScrollState = rememberScrollState(
        (DEFAULT_SCROLL_NOTE * ROW_HEIGHT_DP * verticalZoom * density.density).toInt()
    )
    val horizontalScrollState = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()

    // Hoist theme colors for use in non-composable DrawScope functions
    val pianoMuted2 = NjMuted2
    val pianoAmber = NjAmber
    val pianoOnBg = NjOnBg
    val pianoLane = NjLane
    val panelInset = NjPanelInset
    val surfaceColor = NjSurface

    // Track color for notes
    val noteColor = NjTrackColors[state.trackSortIndex % NjTrackColors.size]

    val view = LocalView.current

    // Drag preview state (local to composable, not in ViewModel)
    var dragState by remember { mutableStateOf<GroupDragState?>(null) }
    // Marquee selection rect during box-drag (local; only the result hits the VM)
    var marqueeRect by remember { mutableStateOf<MarqueeRect?>(null) }

    // Double-tap detection state
    var lastTapNoteId by remember { mutableStateOf<Long?>(null) }
    var lastTapTimeMs by remember { mutableStateOf(0L) }

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
        // New captioned top bar: BACK, title (track + KEY/BPM/INSTRUMENT), RESTART, PLAY.
        // Replaces the old TopAppBar. Undo/Redo/Delete migrate to TOOLS panel
        // (phase 4); GridRes + Snap migrate to the bottom-row chips below.
        PianoRollTopBar(
            state = state,
            onBack = onBack,
            onRestart = { viewModel.onAction(PianoRollAction.Restart) },
            onPlayPause = {
                if (state.isPlaying) viewModel.onAction(PianoRollAction.Pause)
                else viewModel.onAction(PianoRollAction.Play)
            }
        )

        // INSTR mode swaps the piano roll for the embedded patch picker.
        // Plain conditional render -- AnimatedContent was causing the
        // weight(1f) slot to collapse to zero height, which is what made
        // the tab bar appear glued to the top bar. We can reintroduce the
        // slide animation later via a different mechanism.
        // INSTR mode swaps the piano roll for the embedded patch picker.
        if (state.activeTab == PianoRollTab.INSTR && !state.isPanelCollapsed) {
            InstrumentPickerEmbedded(
                selectedProgram = state.midiProgram,
                onSelectProgram = { program ->
                    viewModel.onAction(PianoRollAction.SetMidiInstrument(program))
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
        // Diatonic chord reference strip (visible when scale is enabled).
        // Sits just below the top bar so it's glance-able from any sub-panel.
        ChordReferenceStrip(chords = state.diatonicChords)

        // Adaptive timeline ruler: bar/beat labels, selector + loop region,
        // tap to set selector, drag to define/adjust loop. Scrolls with the grid.
        PianoRollTimelineRuler(
            contentMs = contentMs,
            pxPerMs = PX_PER_MS * horizontalZoom * density.density,
            bpm = state.bpm,
            timeSigNum = state.timeSignatureNumerator,
            timeSigDen = state.timeSignatureDenominator,
            gridResolution = state.gridResolution,
            selectorMs = state.selectorMs,
            positionMs = state.positionMs,
            isPlaying = state.isPlaying,
            loopStartMs = state.loopStartMs,
            loopEndMs = state.loopEndMs,
            isLoopEnabled = state.isLoopEnabled,
            horizontalScrollState = horizontalScrollState,
            onSetSelector = { ms -> viewModel.onAction(PianoRollAction.SetSelector(ms)) },
            onSetLoopRegion = { startMs, endMs ->
                viewModel.onAction(PianoRollAction.SetLoopRegion(startMs, endMs))
            }
        )

        // Piano keys + Grid -- weight(1f) so the placeholders below sit at the bottom.
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
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
                    drawPianoKeys(
                        rowHeightPx, textMeasurer, pianoMuted2, pianoOnBg, surfaceColor,
                        isScaleEnabled = state.isScaleEnabled,
                        scaleRoot = state.scaleRoot,
                        scaleType = state.scaleType,
                        scaleHighlightColor = pianoAmber
                    )
                }
            }

            // Grid canvas (scrolls both X and Y)
            CompositionLocalProvider(LocalViewConfiguration provides fastViewConfig) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(effectiveMaxHZoom) {
                            detectPinchZoom(
                                canStart = { dragState == null && marqueeRect == null },
                                onPinchStart = { isPinching = true },
                                onPinchZoom = { scaleX, scaleY, centroidX, centroidY ->
                                    val oldHZoom = horizontalZoom
                                    val oldVZoom = verticalZoom
                                    val newHZoom = (oldHZoom * scaleX).coerceIn(MIN_H_ZOOM, effectiveMaxHZoom)
                                    val newVZoom = (oldVZoom * scaleY).coerceIn(MIN_V_ZOOM, MAX_V_ZOOM)

                                    // Focal-point scroll: keep the content under the pinch center stable
                                    val newHScroll = ((centroidX + horizontalScrollState.value) * (newHZoom / oldHZoom) - centroidX)
                                        .toInt().coerceAtLeast(0)
                                    val newVScroll = ((centroidY + verticalScrollState.value) * (newVZoom / oldVZoom) - centroidY)
                                        .toInt().coerceAtLeast(0)

                                    horizontalZoom = newHZoom
                                    verticalZoom = newVZoom

                                    coroutineScope.launch {
                                        horizontalScrollState.scrollTo(newHScroll)
                                    }
                                    coroutineScope.launch {
                                        verticalScrollState.scrollTo(newVScroll)
                                    }
                                },
                                onPinchEnd = { isPinching = false }
                            )
                        }
                        .verticalScroll(verticalScrollState)
                        .horizontalScroll(horizontalScrollState)
                ) {
                    Canvas(
                        modifier = Modifier
                            .width(gridWidthDp)
                            .height(totalGridHeight)
                            .background(panelInset)
                            .pointerInput(
                                state.notes, state.isSnapEnabled, state.bpm,
                                state.selectedNoteIds, horizontalZoom, verticalZoom,
                                isPinching
                            ) {
                                val pxPerMs = PX_PER_MS * horizontalZoom * density.density
                                val edgeZonePx = EDGE_TOUCH_ZONE.toPx()

                                awaitEachGesture {
                                    if (isPinching) return@awaitEachGesture
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    // DON'T consume down -- lets scroll handle swipes

                                    // Check both edges. For short notes both edge zones may
                                    // overlap; tie-break by proximity to the touch X.
                                    val rightEdgeNote = findNoteEdgeAt(
                                        down.position, state.notes, rowHeightPx, pxPerMs, edgeZonePx
                                    )
                                    val leftEdgeNote = findNoteLeftEdgeAt(
                                        down.position, state.notes, rowHeightPx, pxPerMs, edgeZonePx
                                    )
                                    val (edgeNote, isLeftEdge) = when {
                                        rightEdgeNote != null && leftEdgeNote != null -> {
                                            val rightX = (rightEdgeNote.startMs + rightEdgeNote.durationMs) * pxPerMs
                                            val leftX = leftEdgeNote.startMs * pxPerMs
                                            if (abs(down.position.x - leftX) <= abs(down.position.x - rightX)) {
                                                leftEdgeNote to true
                                            } else {
                                                rightEdgeNote to false
                                            }
                                        }
                                        rightEdgeNote != null -> rightEdgeNote to false
                                        leftEdgeNote != null -> leftEdgeNote to true
                                        else -> null to false
                                    }
                                    // Then check body hit (strict bounds)
                                    val hitNote = edgeNote
                                        ?: findNoteAt(down.position, state.notes, rowHeightPx, pxPerMs)

                                    if (edgeNote != null) {
                                        // ── EDGE: hold to resize ──
                                        // Right edge anchors the start; left edge anchors the end.
                                        val longPress = awaitLongPressOrCancellation(down.id)
                                        if (longPress != null) {
                                            longPress.consume()
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            val dragIds = if (edgeNote.id in state.selectedNoteIds) {
                                                state.selectedNoteIds
                                            } else {
                                                setOf(edgeNote.id)
                                            }
                                            if (isLeftEdge) {
                                                handleLeftEdgeResizeDrag(
                                                    edgeNote, dragIds, longPress.id, pxPerMs,
                                                    state.isSnapEnabled, state.bpm,
                                                    state.gridResolution, state.timeSignatureDenominator,
                                                    scrollX = { horizontalScrollState.value },
                                                    onPreview = { dragState = it },
                                                    onCommit = { noteIds, deltaStartMs ->
                                                        dragState = null
                                                        viewModel.onAction(
                                                            PianoRollAction.ResizeNotesLeftEdge(noteIds, deltaStartMs)
                                                        )
                                                    },
                                                    onCancel = { dragState = null }
                                                )
                                            } else {
                                                handleResizeDrag(
                                                    edgeNote, dragIds, longPress.id, pxPerMs, rowHeightPx,
                                                    state.isSnapEnabled, state.bpm,
                                                    state.gridResolution, state.timeSignatureDenominator,
                                                    scrollX = { horizontalScrollState.value },
                                                    onPreview = { dragState = it },
                                                    onCommit = { noteIds, deltaDurationMs ->
                                                        dragState = null
                                                        viewModel.onAction(
                                                            PianoRollAction.ResizeNotes(noteIds, deltaDurationMs)
                                                        )
                                                    },
                                                    onCancel = { dragState = null }
                                                )
                                            }
                                        } else {
                                            // Tap on edge: ERASE deletes; DRAW/SELECT toggle
                                            // selection (with double-tap-to-delete fallback so
                                            // DRAW users still have a quick delete affordance).
                                            val fingerLifted = currentEvent.changes
                                                .none { it.id == down.id && it.pressed }
                                            if (fingerLifted) {
                                                if (state.editorMode == EditorMode.ERASE) {
                                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                    viewModel.onAction(PianoRollAction.QuickDeleteNote(edgeNote.id))
                                                    lastTapNoteId = null
                                                    lastTapTimeMs = 0L
                                                } else {
                                                    val now = System.currentTimeMillis()
                                                    if (lastTapNoteId == edgeNote.id &&
                                                        now - lastTapTimeMs < DOUBLE_TAP_TIMEOUT_MS
                                                    ) {
                                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                        viewModel.onAction(PianoRollAction.QuickDeleteNote(edgeNote.id))
                                                        lastTapNoteId = null
                                                        lastTapTimeMs = 0L
                                                    } else {
                                                        viewModel.onAction(
                                                            PianoRollAction.ToggleNoteSelection(edgeNote.id)
                                                        )
                                                        lastTapNoteId = edgeNote.id
                                                        lastTapTimeMs = now
                                                    }
                                                }
                                            }
                                        }
                                    } else if (hitNote != null) {
                                        // ── NOTE BODY: hold to move, tap to toggle selection / double-tap to delete ──
                                        val longPress = awaitLongPressOrCancellation(down.id)
                                        if (longPress != null) {
                                            longPress.consume()
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            val dragIds = if (hitNote.id in state.selectedNoteIds) {
                                                state.selectedNoteIds
                                            } else {
                                                setOf(hitNote.id)
                                            }
                                            handleMoveDrag(
                                                hitNote, dragIds, longPress.id, pxPerMs, rowHeightPx,
                                                state.isSnapEnabled, state.bpm,
                                                state.gridResolution, state.timeSignatureDenominator,
                                                scrollX = { horizontalScrollState.value },
                                                scrollY = { verticalScrollState.value },
                                                onPreview = { dragState = it },
                                                onPitchCrossed = { pitch ->
                                                    viewModel.onAction(
                                                        PianoRollAction.PreviewPitch(pitch)
                                                    )
                                                },
                                                onCommit = { noteIds, deltaMs, deltaPitch ->
                                                    dragState = null
                                                    viewModel.onAction(
                                                        PianoRollAction.MoveNotes(noteIds, deltaMs, deltaPitch)
                                                    )
                                                },
                                                onCancel = { dragState = null }
                                            )
                                        } else {
                                            // Tap on body: ERASE deletes; DRAW/SELECT toggle
                                            // selection (with double-tap-to-delete fallback).
                                            val fingerLifted = currentEvent.changes
                                                .none { it.id == down.id && it.pressed }
                                            if (fingerLifted) {
                                                if (state.editorMode == EditorMode.ERASE) {
                                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                    viewModel.onAction(PianoRollAction.QuickDeleteNote(hitNote.id))
                                                    lastTapNoteId = null
                                                    lastTapTimeMs = 0L
                                                } else {
                                                    val now = System.currentTimeMillis()
                                                    if (lastTapNoteId == hitNote.id &&
                                                        now - lastTapTimeMs < DOUBLE_TAP_TIMEOUT_MS
                                                    ) {
                                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                        viewModel.onAction(PianoRollAction.QuickDeleteNote(hitNote.id))
                                                        lastTapNoteId = null
                                                        lastTapTimeMs = 0L
                                                    } else {
                                                        viewModel.onAction(
                                                            PianoRollAction.ToggleNoteSelection(hitNote.id)
                                                        )
                                                        lastTapNoteId = hitNote.id
                                                        lastTapTimeMs = now
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // ── EMPTY CELL ──
                                        // Mode dispatch:
                                        //   DRAW   - long-press → marquee, tap → place / clear
                                        //   SELECT - drag (touch slop) → marquee, tap → clear
                                        //   ERASE  - tap → no-op
                                        val mode = state.editorMode
                                        val startMarquee: Boolean
                                        val drag: androidx.compose.ui.input.pointer.PointerInputChange?
                                        when (mode) {
                                            EditorMode.SELECT -> {
                                                // Drag-from-empty marquees immediately on slop.
                                                // No long-press required -- mode already declares
                                                // "I'm selecting."
                                                drag = awaitTouchSlopOrCancellation(down.id) { c, _ ->
                                                    c.consume()
                                                }
                                                startMarquee = drag != null
                                            }
                                            EditorMode.DRAW -> {
                                                // Long-press to marquee (preserves the existing
                                                // tap-to-place affordance so a quick tap still
                                                // creates a note).
                                                val longPress = awaitLongPressOrCancellation(down.id)
                                                drag = longPress
                                                startMarquee = longPress != null
                                            }
                                            EditorMode.ERASE -> {
                                                drag = null
                                                startMarquee = false
                                            }
                                        }

                                        if (!startMarquee) {
                                            val fingerLifted = currentEvent.changes
                                                .none { it.id == down.id && it.pressed }
                                            if (fingerLifted) {
                                                lastTapNoteId = null
                                                lastTapTimeMs = 0L
                                                if (state.selectedNoteIds.isNotEmpty()) {
                                                    viewModel.onAction(PianoRollAction.ClearSelection)
                                                } else if (mode == EditorMode.DRAW) {
                                                    val pitch = TOTAL_NOTES - 1 -
                                                        (down.position.y / rowHeightPx).toInt()
                                                    val tapMs = (down.position.x / pxPerMs).toLong()
                                                    val snapMs = if (state.isSnapEnabled) {
                                                        MusicalTimeConverter.snapToGrid(
                                                            tapMs, state.bpm,
                                                            state.gridResolution,
                                                            state.timeSignatureDenominator
                                                        )
                                                    } else tapMs
                                                    val gridStepMs = MusicalTimeConverter.msPerGridStep(
                                                        state.bpm, state.gridResolution,
                                                        state.timeSignatureDenominator
                                                    ).toLong().coerceAtLeast(50L)
                                                    val noteDuration = state.stickyNoteDurationMs
                                                        ?: if (state.isSnapEnabled && gridStepMs > 0) gridStepMs
                                                        else (60_000.0 / state.bpm).toLong()
                                                    viewModel.onAction(
                                                        PianoRollAction.PlaceNote(
                                                            pitch = pitch.coerceIn(0, 127),
                                                            startMs = snapMs.coerceAtLeast(0L),
                                                            durationMs = noteDuration
                                                        )
                                                    )
                                                }
                                            }
                                        } else {
                                            // Marquee start. Track absolute (canvas-content)
                                            // coords so the rect stays put under the finger
                                            // when scroll moves the canvas.
                                            drag?.consume()
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            val anchorAbsX = down.position.x + horizontalScrollState.value
                                            val anchorAbsY = down.position.y + verticalScrollState.value
                                            var lastAbsX = anchorAbsX
                                            var lastAbsY = anchorAbsY
                                            marqueeRect = MarqueeRect(
                                                x1 = anchorAbsX,
                                                y1 = anchorAbsY,
                                                x2 = anchorAbsX,
                                                y2 = anchorAbsY
                                            )
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id }
                                                    ?: break
                                                if (!change.pressed) {
                                                    change.consume()
                                                    break
                                                }
                                                change.consume()
                                                lastAbsX = change.position.x + horizontalScrollState.value
                                                lastAbsY = change.position.y + verticalScrollState.value
                                                marqueeRect = MarqueeRect(
                                                    x1 = minOf(anchorAbsX, lastAbsX),
                                                    y1 = minOf(anchorAbsY, lastAbsY),
                                                    x2 = maxOf(anchorAbsX, lastAbsX),
                                                    y2 = maxOf(anchorAbsY, lastAbsY)
                                                )
                                            }
                                            val rect = marqueeRect
                                            marqueeRect = null
                                            val minMovePx = 8.dp.toPx()
                                            val movedEnough = rect != null &&
                                                ((rect.x2 - rect.x1) > minMovePx ||
                                                 (rect.y2 - rect.y1) > minMovePx)
                                            if (rect != null && movedEnough) {
                                                val startMs = (rect.x1 / pxPerMs).toLong()
                                                    .coerceAtLeast(0L)
                                                val endMs = (rect.x2 / pxPerMs).toLong()
                                                    .coerceAtLeast(startMs)
                                                val topPitch = (TOTAL_NOTES - 1 -
                                                    (rect.y1 / rowHeightPx).toInt()).coerceIn(0, 127)
                                                val bottomPitch = (TOTAL_NOTES - 1 -
                                                    (rect.y2 / rowHeightPx).toInt()).coerceIn(0, 127)
                                                val pitchRange = minOf(topPitch, bottomPitch)..
                                                    maxOf(topPitch, bottomPitch)
                                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                viewModel.onAction(
                                                    PianoRollAction.SelectNotesInRect(
                                                        startMs = startMs,
                                                        endMs = endMs,
                                                        pitchRange = pitchRange
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        drawGrid(
                            rowHeightPx = rowHeightPx,
                            pxPerMs = PX_PER_MS * horizontalZoom * density.density,
                            notes = state.notes,
                            clips = state.clips,
                            highlightClipId = state.highlightClipId,
                            selectedNoteIds = state.selectedNoteIds,
                            noteColor = noteColor,
                            positionMs = state.positionMs,
                            isPlaying = state.isPlaying,
                            bpm = state.bpm,
                            beatsPerBar = state.timeSignatureNumerator,
                            gridResolution = state.gridResolution,
                            contentMs = contentMs,
                            dragState = dragState,
                            muted2Color = pianoMuted2,
                            laneColor = pianoLane,
                            amberColor = pianoAmber,
                            blackKeyBgColor = surfaceColor,
                            isScaleEnabled = state.isScaleEnabled,
                            scaleRoot = state.scaleRoot,
                            scaleType = state.scaleType,
                            scaleHighlightColor = pianoAmber,
                            loopStartMs = state.loopStartMs,
                            loopEndMs = state.loopEndMs,
                            isLoopEnabled = state.isLoopEnabled,
                            marqueeRect = marqueeRect
                        )
                    }
                }
            }
        }

        // Velocity strip: read-only display by default; becomes draggable
        // for selected notes when EDIT > VELOC is latched. Scrolls horizontally
        // in lockstep with the grid.
        PianoRollVelocityStrip(
            notes = state.notes,
            selectedNoteIds = state.selectedNoteIds,
            isVelocityEditMode = state.isVelocityEditMode,
            horizontalScrollState = horizontalScrollState,
            pxPerMs = PX_PER_MS * horizontalZoom * density.density,
            contentMs = contentMs,
            trackColor = noteColor,
            onCommitVelocities = { newVelocities ->
                viewModel.onAction(PianoRollAction.SetNoteVelocities(newVelocities))
            }
        )
            }  // end of inner Column inside else branch
        }  // end of else branch

        // Tab bar -- four MODE buttons. Phases 4-6 + 10 fill in their respective
        // sub-panels.
        PianoRollTabBar(
            activeTab = state.activeTab,
            onTabSelect = { viewModel.onAction(PianoRollAction.SwitchTab(it)) }
        )

        // Sub-panel: hidden when INSTR is active (picker fills the area
        // above) or when the user has tapped the active tab a second time
        // to collapse it.
        if (state.activeTab != PianoRollTab.INSTR && !state.isPanelCollapsed) {
            PianoRollSubPanel(
                activeTab = state.activeTab,
                state = state,
                onAction = viewModel::onAction
            )
        }
    }
}

/** Passive diatonic chord reference strip showing roman numerals and chord names. */
@Composable
private fun ChordReferenceStrip(
    chords: List<MusicalScaleHelper.ChordInfo>
) {
    if (chords.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NjSurface)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        for (chord in chords) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = chord.romanNumeral,
                    style = MaterialTheme.typography.labelSmall,
                    color = NjMuted2,
                    fontSize = 10.sp
                )
                Text(
                    text = chord.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = NjOnBg.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ── Drawing functions ───────────────────────────────────────────────

/** Draw the piano key strip on the left side. */
private fun DrawScope.drawPianoKeys(
    rowHeightPx: Float,
    textMeasurer: TextMeasurer,
    muted2Color: Color,
    onBgColor: Color,
    blackKeyBgColor: Color,
    isScaleEnabled: Boolean = false,
    scaleRoot: Int = 0,
    scaleType: MusicalScaleHelper.ScaleType = MusicalScaleHelper.ScaleType.MAJOR,
    scaleHighlightColor: Color = Color.Transparent
) {
    val width = size.width

    for (note in 0 until TOTAL_NOTES) {
        val displayNote = TOTAL_NOTES - 1 - note
        val y = note * rowHeightPx
        val octaveIndex = displayNote % 12
        val isBlack = octaveIndex in BLACK_KEYS

        // Key background -- matches grid row tints (dark=dark, light=light)
        val keyColor = if (isBlack) blackKeyBgColor else Color.Transparent
        drawRect(
            color = keyColor,
            topLeft = Offset(0f, y),
            size = Size(width, rowHeightPx)
        )

        // Scale indicators on piano keys
        if (isScaleEnabled) {
            val isRoot = MusicalScaleHelper.isRoot(displayNote, scaleRoot)
            val isInScale = MusicalScaleHelper.isInScale(displayNote, scaleRoot, scaleType)

            if (isRoot) {
                // Strong full-width tint for root notes
                drawRect(
                    color = scaleHighlightColor.copy(alpha = 0.35f),
                    topLeft = Offset(0f, y),
                    size = Size(width, rowHeightPx)
                )
            } else if (isInScale) {
                // Subtle edge bar for in-scale notes
                drawRect(
                    color = scaleHighlightColor.copy(alpha = 0.25f),
                    topLeft = Offset(width - 5f, y),
                    size = Size(5f, rowHeightPx)
                )
            }
        }

        // Separator -- stronger at C notes (octave boundaries)
        val isC = octaveIndex == 0
        drawLine(
            color = muted2Color.copy(alpha = if (isC) 0.6f else 0.3f),
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = if (isC) 1f else 0.5f
        )

        // Note label for every key (skip when rows are too small to read)
        if (rowHeightPx >= 12f) {
            val noteName = NOTE_NAMES[octaveIndex]
            val label = if (octaveIndex == 0) "C${displayNote / 12 - 1}" else noteName
            val labelColor = if (octaveIndex == 0) onBgColor else muted2Color.copy(alpha = 0.6f)
            val result = textMeasurer.measure(
                text = label,
                style = TextStyle(color = labelColor, fontSize = 10.sp)
            )
            drawText(
                textLayoutResult = result,
                topLeft = Offset(4f, y + (rowHeightPx - result.size.height) / 2f)
            )
        }
    }
}

/** Draw the note grid, beat lines, clip regions, notes, and playhead. */
private fun DrawScope.drawGrid(
    rowHeightPx: Float,
    pxPerMs: Float,
    notes: List<MidiNoteEntity>,
    clips: List<PianoRollClipInfo>,
    highlightClipId: Long = 0L,
    selectedNoteIds: Set<Long>,
    noteColor: Color,
    positionMs: Long,
    isPlaying: Boolean,
    bpm: Double,
    beatsPerBar: Int,
    gridResolution: Int,
    contentMs: Long,
    dragState: GroupDragState? = null,
    muted2Color: Color,
    laneColor: Color,
    amberColor: Color,
    blackKeyBgColor: Color = Color(0xFF14101E),
    isScaleEnabled: Boolean = false,
    scaleRoot: Int = 0,
    scaleType: MusicalScaleHelper.ScaleType = MusicalScaleHelper.ScaleType.MAJOR,
    scaleHighlightColor: Color = Color.Transparent,
    loopStartMs: Long? = null,
    loopEndMs: Long? = null,
    isLoopEnabled: Boolean = false,
    marqueeRect: MarqueeRect? = null
) {
    val totalHeight = TOTAL_NOTES * rowHeightPx
    val beatMs = 60_000.0 / bpm
    // Grid step in ms: subdivides a whole note by gridResolution
    val gridStepMs = MusicalTimeConverter.msPerGridStep(bpm, gridResolution)
    // How many grid steps per beat (e.g. gridResolution=16 in 4/4 -> 4 sub-steps per beat)
    val gridStepsPerBeat = gridResolution / 4

    // Row backgrounds: sharp/natural alternation normally, two-tone in/out when scale is on
    for (note in 0 until TOTAL_NOTES) {
        val displayNote = TOTAL_NOTES - 1 - note
        val y = note * rowHeightPx
        val octaveIndex = displayNote % 12

        if (isScaleEnabled) {
            // Two-tone: in-scale rows stay at base color, out-of-scale rows darken
            val isInScale = MusicalScaleHelper.isInScale(displayNote, scaleRoot, scaleType)
            if (!isInScale) {
                drawRect(
                    color = Color.Black.copy(alpha = 0.35f),
                    topLeft = Offset(0f, y),
                    size = Size(size.width, rowHeightPx)
                )
            }
        } else {
            // Default: black key rows get a darker background
            val isBlack = octaveIndex in BLACK_KEYS
            if (isBlack) {
                drawRect(
                    color = blackKeyBgColor,
                    topLeft = Offset(0f, y),
                    size = Size(size.width, rowHeightPx)
                )
            }
        }

        // Row separator -- stronger at C notes (octave boundaries)
        val isC = octaveIndex == 0
        drawLine(
            color = muted2Color.copy(alpha = if (isC) 0.6f else 0.3f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = if (isC) 1f else 0.5f
        )
    }

    // Loop region: translucent amber fill across the full pitch range so the
    // user can see at a glance where the loop wraps. Sits behind clip regions
    // and notes; alpha is lower when the loop is set-but-disengaged.
    if (loopStartMs != null && loopEndMs != null && loopEndMs > loopStartMs) {
        val loopStartPx = loopStartMs * pxPerMs
        val loopWidthPx = (loopEndMs - loopStartMs) * pxPerMs
        drawRect(
            color = amberColor.copy(alpha = if (isLoopEnabled) 0.08f else 0.04f),
            topLeft = Offset(loopStartPx, 0f),
            size = Size(loopWidthPx, totalHeight)
        )
    }

    // Clip region backgrounds and boundary lines
    for (clip in clips) {
        val clipStartPx = clip.offsetMs * pxPerMs
        val clipWidthPx = (clip.endMs - clip.offsetMs) * pxPerMs
        val isHighlighted = highlightClipId != 0L && clip.clipId == highlightClipId

        // Subtle tinted background for clip region (slightly brighter for highlighted)
        drawRect(
            color = laneColor.copy(alpha = if (isHighlighted) 0.12f else 0.06f),
            topLeft = Offset(clipStartPx, 0f),
            size = Size(clipWidthPx, totalHeight)
        )

        // Clip start boundary
        val borderColor = if (isHighlighted) amberColor.copy(alpha = 0.45f)
            else amberColor.copy(alpha = 0.2f)
        drawLine(
            color = borderColor,
            start = Offset(clipStartPx, 0f),
            end = Offset(clipStartPx, totalHeight),
            strokeWidth = if (isHighlighted) 2.5f else 1.5f
        )

        // Clip end boundary
        val clipEndPx = clipStartPx + clipWidthPx
        drawLine(
            color = if (isHighlighted) amberColor.copy(alpha = 0.3f)
                else amberColor.copy(alpha = 0.1f),
            start = Offset(clipEndPx, 0f),
            end = Offset(clipEndPx, totalHeight),
            strokeWidth = if (isHighlighted) 2.5f else 1f
        )

        // Highlight: top and bottom borders for source clip
        if (isHighlighted) {
            drawLine(
                color = amberColor.copy(alpha = 0.35f),
                start = Offset(clipStartPx, 0f),
                end = Offset(clipEndPx, 0f),
                strokeWidth = 2f
            )
            drawLine(
                color = amberColor.copy(alpha = 0.35f),
                start = Offset(clipStartPx, totalHeight),
                end = Offset(clipEndPx, totalHeight),
                strokeWidth = 2f
            )
        }
    }

    // Grid lines at sub-beat resolution
    if (gridStepMs > 0.0) {
        var stepTimeMs = 0.0
        var gridIndex = 0
        while (stepTimeMs < contentMs) {
            val x = (stepTimeMs * pxPerMs).toFloat()
            // Determine which level this line falls on
            val beatIndex = if (gridStepsPerBeat > 0) gridIndex % gridStepsPerBeat else 0
            val isBeat = beatIndex == 0
            val isBar = isBeat && ((gridIndex / gridStepsPerBeat) % beatsPerBar == 0)

            val alpha: Float
            val strokeWidth: Float
            when {
                isBar -> { alpha = 0.5f; strokeWidth = 1.5f }
                isBeat -> { alpha = 0.3f; strokeWidth = 1f }
                else -> { alpha = 0.2f; strokeWidth = 0.5f }
            }

            drawLine(
                color = muted2Color.copy(alpha = alpha),
                start = Offset(x, 0f),
                end = Offset(x, totalHeight),
                strokeWidth = strokeWidth
            )

            stepTimeMs += gridStepMs
            gridIndex++
        }
    }

    // Draw notes with beveled edges
    val bw = 1f // bevel line width
    for (note in notes) {
        val isDragging = dragState != null && note.id in dragState.noteIds
        val isAnchor = dragState != null && note.id == dragState.anchorNoteId

        val drawPitch: Int
        val drawStartMs: Long
        val drawDurationMs: Long
        if (isDragging) {
            when {
                dragState!!.isLeftEdgeResize -> {
                    drawPitch = note.pitch
                    val capped = dragState.deltaStartMs
                        .coerceAtMost(note.durationMs - 50L)
                        .coerceAtLeast(-note.startMs)
                    drawStartMs = (note.startMs + capped).coerceAtLeast(0L)
                    drawDurationMs = (note.durationMs - capped).coerceAtLeast(50L)
                }
                dragState.isResize -> {
                    drawPitch = note.pitch
                    drawStartMs = note.startMs
                    drawDurationMs = (note.durationMs + dragState.deltaDurationMs).coerceAtLeast(50L)
                }
                else -> {
                    drawPitch = (note.pitch + dragState.deltaPitch).coerceIn(0, 127)
                    drawStartMs = (note.startMs + dragState.deltaMs).coerceAtLeast(0L)
                    drawDurationMs = note.durationMs
                }
            }
        } else {
            drawPitch = note.pitch
            drawStartMs = note.startMs
            drawDurationMs = note.durationMs
        }

        val rowIndex = TOTAL_NOTES - 1 - drawPitch
        val y = rowIndex * rowHeightPx + 1f
        val x = drawStartMs * pxPerMs
        val w = (drawDurationMs * pxPerMs).coerceAtLeast(4f)
        val h = rowHeightPx - 2f

        val isSelected = note.id in selectedNoteIds
        val cornerRadius = CornerRadius(3f, 3f)
        val bevelW = 3f

        if (isSelected || isDragging) {
            // Pressed in: darkened fill + inner shadow
            drawRoundRect(
                color = noteColor,
                topLeft = Offset(x, y),
                size = Size(w, h),
                cornerRadius = cornerRadius
            )
            // Dark overlay to sink the color
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.25f),
                topLeft = Offset(x, y),
                size = Size(w, h),
                cornerRadius = cornerRadius
            )
            // Inner shadow: dark top/left, faint light bottom/right
            drawLine(Color.Black.copy(alpha = 0.7f), Offset(x, y), Offset(x + w, y), bevelW)
            drawLine(Color.Black.copy(alpha = 0.5f), Offset(x, y), Offset(x, y + h), bevelW)
            drawLine(Color.White.copy(alpha = 0.15f), Offset(x, y + h), Offset(x + w, y + h), bevelW)
            drawLine(Color.White.copy(alpha = 0.1f), Offset(x + w, y), Offset(x + w, y + h), bevelW)
        } else {
            // Raised: bright fill + strong highlight/shadow
            drawRoundRect(
                color = noteColor,
                topLeft = Offset(x, y),
                size = Size(w, h),
                cornerRadius = cornerRadius
            )
            drawLine(Color.White.copy(alpha = 0.5f), Offset(x, y), Offset(x + w, y), bevelW)
            drawLine(Color.White.copy(alpha = 0.3f), Offset(x, y), Offset(x, y + h), bevelW)
            drawLine(Color.Black.copy(alpha = 0.6f), Offset(x, y + h), Offset(x + w, y + h), bevelW)
            drawLine(Color.Black.copy(alpha = 0.4f), Offset(x + w, y), Offset(x + w, y + h), bevelW)
        }

        // Drag border -- anchor note gets white outline during active drag
        if (isAnchor) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.6f),
                topLeft = Offset(x, y),
                size = Size(w, h),
                cornerRadius = cornerRadius,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
            )
        }
    }

    // Marquee (box-select) -- dashed amber rectangle while the user drags.
    if (marqueeRect != null) {
        val w = marqueeRect.x2 - marqueeRect.x1
        val h = marqueeRect.y2 - marqueeRect.y1
        if (w > 0f && h > 0f) {
            drawRect(
                color = amberColor.copy(alpha = 0.10f),
                topLeft = Offset(marqueeRect.x1, marqueeRect.y1),
                size = Size(w, h)
            )
            drawRect(
                color = amberColor,
                topLeft = Offset(marqueeRect.x1, marqueeRect.y1),
                size = Size(w, h),
                style = Stroke(
                    width = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                )
            )
        }
    }

    // Playhead
    if (isPlaying || positionMs > 0) {
        val playheadX = positionMs * pxPerMs
        drawLine(
            color = amberColor,
            start = Offset(playheadX, 0f),
            end = Offset(playheadX, totalHeight),
            strokeWidth = 2f
        )
    }
}

/**
 * Find a note whose right-edge resize zone contains the tap.
 *
 * The edge zone is the last [edgeZonePx] pixels **inside** the note body,
 * clamped to the note's start so narrow notes stay grabbable (the whole
 * note becomes the edge zone). Tapping in empty space past the note's end
 * does not match — that previously caused phantom selections when users
 * tapped near, but outside, a note whose right edge was within 16dp.
 */
private fun findNoteEdgeAt(
    position: Offset,
    notes: List<MidiNoteEntity>,
    rowHeightPx: Float,
    pxPerMs: Float,
    edgeZonePx: Float
): MidiNoteEntity? {
    val pitch = TOTAL_NOTES - 1 - (position.y / rowHeightPx).toInt()
    return notes.find { note ->
        if (note.pitch != pitch) return@find false
        val startPx = note.startMs * pxPerMs
        val endPx = (note.startMs + note.durationMs) * pxPerMs
        val edgeStart = (endPx - edgeZonePx).coerceAtLeast(startPx)
        position.x in edgeStart..endPx
    }
}

/**
 * Find a note whose LEFT-edge resize zone contains the tap.
 *
 * The edge zone is the first [edgeZonePx] pixels inside the note body,
 * clamped to the note's end so narrow notes stay grabbable. Symmetric
 * to [findNoteEdgeAt] (right edge). For very short notes both edges
 * overlap; the caller does the tie-break by proximity.
 */
private fun findNoteLeftEdgeAt(
    position: Offset,
    notes: List<MidiNoteEntity>,
    rowHeightPx: Float,
    pxPerMs: Float,
    edgeZonePx: Float
): MidiNoteEntity? {
    val pitch = TOTAL_NOTES - 1 - (position.y / rowHeightPx).toInt()
    return notes.find { note ->
        if (note.pitch != pitch) return@find false
        val startPx = note.startMs * pxPerMs
        val endPx = (note.startMs + note.durationMs) * pxPerMs
        val edgeEnd = (startPx + edgeZonePx).coerceAtMost(endPx)
        position.x in startPx..edgeEnd
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
 * Handle resize drag on a note's right edge (group-aware).
 * Called AFTER long-press is confirmed -- only handles the drag phase.
 *
 * Uses absolute position tracking (canvas position + scroll offset) instead of
 * accumulating [positionChange] deltas. The scroll containers on the parent Box
 * move the canvas under the finger during the long-press wait, which neutralizes
 * relative deltas. Absolute coordinates are invariant to scroll movement.
 */
private suspend fun AwaitPointerEventScope.handleResizeDrag(
    anchorNote: MidiNoteEntity,
    dragNoteIds: Set<Long>,
    pointerId: PointerId,
    pxPerMs: Float,
    rowHeightPx: Float,
    isSnapEnabled: Boolean,
    bpm: Double,
    gridResolution: Int,
    timeSignatureDenominator: Int,
    scrollX: () -> Int,
    onPreview: (GroupDragState) -> Unit,
    onCommit: (noteIds: Set<Long>, deltaDurationMs: Long) -> Unit,
    onCancel: () -> Unit
) {
    onPreview(
        GroupDragState(
            noteIds = dragNoteIds, anchorNoteId = anchorNote.id, isResize = true
        )
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
        // Snap: compute snapped delta from the anchor note
        val rawEndMs = anchorNote.startMs + anchorNote.durationMs + deltaMs
        val snappedDelta = if (isSnapEnabled) {
            val snappedEnd = MusicalTimeConverter.snapToGrid(
                rawEndMs, bpm, gridResolution, timeSignatureDenominator
            )
            snappedEnd - (anchorNote.startMs + anchorNote.durationMs)
        } else deltaMs
        onPreview(
            GroupDragState(
                noteIds = dragNoteIds, anchorNoteId = anchorNote.id,
                deltaDurationMs = snappedDelta, isResize = true
            )
        )
    }

    if (completed && startAbsX != null) {
        val lastEvent = currentEvent.changes.firstOrNull { it.id == pointerId }
        val finalAbsX = if (lastEvent != null) lastEvent.position.x + scrollX() else startAbsX
        val accumulatedPx = finalAbsX - startAbsX
        val deltaMs = (accumulatedPx / pxPerMs).toLong()
        val rawEndMs = anchorNote.startMs + anchorNote.durationMs + deltaMs
        val finalDelta = if (isSnapEnabled) {
            val snappedEnd = MusicalTimeConverter.snapToGrid(
                rawEndMs, bpm, gridResolution, timeSignatureDenominator
            )
            snappedEnd - (anchorNote.startMs + anchorNote.durationMs)
        } else deltaMs
        onCommit(dragNoteIds, finalDelta)
    } else {
        onCancel()
    }
}

/**
 * Handle resize drag on a note's LEFT edge (group-aware).
 *
 * Symmetric to [handleResizeDrag] but tracks deltaStart -- the start
 * moves while the right edge stays anchored. Snap operates on the
 * proposed new start. Same absolute-position pattern to stay invariant
 * to scroll movement during the long-press latch.
 */
private suspend fun AwaitPointerEventScope.handleLeftEdgeResizeDrag(
    anchorNote: MidiNoteEntity,
    dragNoteIds: Set<Long>,
    pointerId: PointerId,
    pxPerMs: Float,
    isSnapEnabled: Boolean,
    bpm: Double,
    gridResolution: Int,
    timeSignatureDenominator: Int,
    scrollX: () -> Int,
    onPreview: (GroupDragState) -> Unit,
    onCommit: (noteIds: Set<Long>, deltaStartMs: Long) -> Unit,
    onCancel: () -> Unit
) {
    onPreview(
        GroupDragState(
            noteIds = dragNoteIds,
            anchorNoteId = anchorNote.id,
            isLeftEdgeResize = true
        )
    )

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
        val rawNewStart = anchorNote.startMs + deltaMs
        val snappedDelta = if (isSnapEnabled) {
            val snappedStart = MusicalTimeConverter.snapToGrid(
                rawNewStart, bpm, gridResolution, timeSignatureDenominator
            )
            snappedStart - anchorNote.startMs
        } else deltaMs
        onPreview(
            GroupDragState(
                noteIds = dragNoteIds,
                anchorNoteId = anchorNote.id,
                deltaStartMs = snappedDelta,
                isLeftEdgeResize = true
            )
        )
    }

    if (completed && startAbsX != null) {
        val lastEvent = currentEvent.changes.firstOrNull { it.id == pointerId }
        val finalAbsX = if (lastEvent != null) lastEvent.position.x + scrollX() else startAbsX
        val accumulatedPx = finalAbsX - startAbsX
        val deltaMs = (accumulatedPx / pxPerMs).toLong()
        val rawNewStart = anchorNote.startMs + deltaMs
        val finalDelta = if (isSnapEnabled) {
            val snappedStart = MusicalTimeConverter.snapToGrid(
                rawNewStart, bpm, gridResolution, timeSignatureDenominator
            )
            snappedStart - anchorNote.startMs
        } else deltaMs
        onCommit(dragNoteIds, finalDelta)
    } else {
        onCancel()
    }
}

/**
 * Handle move drag on a note body (time + pitch, group-aware).
 * Called AFTER long-press is confirmed -- only handles the drag phase.
 *
 * Uses absolute position tracking for the same reason as [handleResizeDrag]:
 * scroll containers move the canvas under the finger, neutralizing relative deltas.
 *
 * Computes deltas from the anchor note only, then the same delta is applied to all
 * notes in [dragNoteIds] via the ViewModel batch action.
 */
private suspend fun AwaitPointerEventScope.handleMoveDrag(
    anchorNote: MidiNoteEntity,
    dragNoteIds: Set<Long>,
    pointerId: PointerId,
    pxPerMs: Float,
    rowHeightPx: Float,
    isSnapEnabled: Boolean,
    bpm: Double,
    gridResolution: Int,
    timeSignatureDenominator: Int,
    scrollX: () -> Int,
    scrollY: () -> Int,
    onPreview: (GroupDragState) -> Unit,
    onPitchCrossed: (Int) -> Unit,
    onCommit: (noteIds: Set<Long>, deltaMs: Long, deltaPitch: Int) -> Unit,
    onCancel: () -> Unit
) {
    var lastPreviewPitch = anchorNote.pitch

    onPreview(
        GroupDragState(noteIds = dragNoteIds, anchorNoteId = anchorNote.id)
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

        // Compute delta from anchor note position
        val rawDeltaMs = (totalDx / pxPerMs).toLong()
        val snappedDeltaMs = if (isSnapEnabled) {
            val snappedStart = MusicalTimeConverter.snapToGrid(
                anchorNote.startMs + rawDeltaMs, bpm, gridResolution, timeSignatureDenominator
            )
            snappedStart - anchorNote.startMs
        } else rawDeltaMs
        val pitchDelta = -(totalDy / rowHeightPx).toInt()

        onPreview(
            GroupDragState(
                noteIds = dragNoteIds, anchorNoteId = anchorNote.id,
                deltaMs = snappedDeltaMs, deltaPitch = pitchDelta
            )
        )

        val newPitch = (anchorNote.pitch + pitchDelta).coerceIn(0, 127)
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

        val rawDeltaMs = (totalDx / pxPerMs).toLong()
        val snappedDeltaMs = if (isSnapEnabled) {
            val snappedStart = MusicalTimeConverter.snapToGrid(
                anchorNote.startMs + rawDeltaMs, bpm, gridResolution, timeSignatureDenominator
            )
            snappedStart - anchorNote.startMs
        } else rawDeltaMs
        val pitchDelta = -(totalDy / rowHeightPx).toInt()

        onCommit(dragNoteIds, snappedDeltaMs, pitchDelta)
    } else {
        onCancel()
    }
}

/**
 * Detect pinch-to-zoom gestures using [PointerEventPass.Initial] so events are
 * intercepted before scroll modifiers. Single-finger events pass through unconsumed
 * so normal scroll/tap/drag work normally.
 *
 * Tracks two specific pointer IDs for reliable direction detection:
 * - **Crossing prevention:** Records the signed finger difference at pinch start.
 *   If the sign flips (fingers crossed), zoom freezes on that axis. On uncross,
 *   the baseline resets to prevent a jump.
 * - **Proportional damping:** Each axis's zoom influence is scaled by how far apart
 *   the fingers are on that axis at pinch start (soft threshold ~48dp). Small initial
 *   spans contribute almost nothing, preventing cross-axis contamination.
 * - **Drain on end:** When the pinch ends (2->1 finger), all remaining pointer events
 *   are consumed until all fingers lift, preventing accidental note placement.
 */
private suspend fun PointerInputScope.detectPinchZoom(
    canStart: () -> Boolean,
    onPinchStart: () -> Unit,
    onPinchZoom: (scaleX: Float, scaleY: Float, centroidX: Float, centroidY: Float) -> Unit,
    onPinchEnd: () -> Unit
) {
    val softThresholdPx = 48.dp.toPx()

    awaitEachGesture {
        // Wait for first finger -- don't consume so scroll works if no second finger arrives
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

        // Tracked pointer IDs for consistent direction detection
        var firstPointerId: PointerId? = null
        var secondPointerId: PointerId? = null
        var initialSignX = 0f      // sign of (first.x - second.x) at pinch start
        var initialSignY = 0f
        var initialSpanX = 0f      // absolute span at pinch start (for damping weight)
        var initialSpanY = 0f
        var prevSpanX = 0f
        var prevSpanY = 0f
        var pinching = false
        var wasCrossedX = false
        var wasCrossedY = false

        while (true) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                if (pinching) onPinchEnd()
                break
            }

            if (pressed.size >= 2) {
                if (!pinching) {
                    if (!canStart()) {
                        // Don't start pinch during an active note drag -- let events pass through
                        continue
                    }
                    pinching = true
                    onPinchStart()

                    // Lock the two pointer IDs we'll track for the rest of this gesture
                    firstPointerId = pressed[0].id
                    secondPointerId = pressed[1].id
                    val p1 = pressed[0].position
                    val p2 = pressed[1].position
                    val dx = p1.x - p2.x
                    val dy = p1.y - p2.y
                    initialSignX = if (dx >= 0f) 1f else -1f
                    initialSignY = if (dy >= 0f) 1f else -1f
                    initialSpanX = abs(dx).coerceAtLeast(1f)
                    initialSpanY = abs(dy).coerceAtLeast(1f)
                    prevSpanX = initialSpanX
                    prevSpanY = initialSpanY

                    event.changes.forEach { it.consume() }
                    continue
                }

                // Find our tracked pointers (ignore any additional fingers)
                val p1 = pressed.find { it.id == firstPointerId }
                val p2 = pressed.find { it.id == secondPointerId }
                if (p1 == null || p2 == null) {
                    // One of our tracked pointers was lost -- end pinch and drain
                    onPinchEnd()
                    event.changes.forEach { it.consume() }
                    while (true) {
                        val drain = awaitPointerEvent(pass = PointerEventPass.Initial)
                        drain.changes.forEach { it.consume() }
                        if (drain.changes.none { it.pressed }) break
                    }
                    break
                }

                val signedX = p1.position.x - p2.position.x
                val signedY = p1.position.y - p2.position.y

                // Crossing detection: sign flip means fingers crossed on that axis
                val crossedX = (signedX * initialSignX) < 0f
                val crossedY = (signedY * initialSignY) < 0f

                // On uncross transition, reset baseline to prevent accumulated jump
                if (wasCrossedX && !crossedX) {
                    prevSpanX = abs(signedX).coerceAtLeast(1f)
                }
                if (wasCrossedY && !crossedY) {
                    prevSpanY = abs(signedY).coerceAtLeast(1f)
                }
                wasCrossedX = crossedX
                wasCrossedY = crossedY

                // When crossed, hold span at previous value so scale = 1.0 (frozen)
                val spanX = if (crossedX) prevSpanX else abs(signedX).coerceAtLeast(1f)
                val spanY = if (crossedY) prevSpanY else abs(signedY).coerceAtLeast(1f)

                val rawScaleX = spanX / prevSpanX
                val rawScaleY = spanY / prevSpanY

                // Proportional damping: small initial spans contribute almost nothing
                val dampX = (initialSpanX / softThresholdPx).coerceIn(0f, 1f)
                val dampY = (initialSpanY / softThresholdPx).coerceIn(0f, 1f)
                val scaleX = 1f + (rawScaleX - 1f) * dampX
                val scaleY = 1f + (rawScaleY - 1f) * dampY

                val centroidX = (p1.position.x + p2.position.x) / 2f
                val centroidY = (p1.position.y + p2.position.y) / 2f

                // Jitter filter: only report changes > 0.5%
                if (abs(scaleX - 1f) > 0.005f || abs(scaleY - 1f) > 0.005f) {
                    onPinchZoom(scaleX, scaleY, centroidX, centroidY)
                    if (!crossedX) prevSpanX = spanX
                    if (!crossedY) prevSpanY = spanY
                }

                event.changes.forEach { it.consume() }
            } else if (pinching) {
                // Went from 2 fingers to 1 -- end pinch and drain until all fingers lift
                onPinchEnd()
                event.changes.forEach { it.consume() }
                while (true) {
                    val drain = awaitPointerEvent(pass = PointerEventPass.Initial)
                    drain.changes.forEach { it.consume() }
                    if (drain.changes.none { it.pressed }) break
                }
                break
            }
            // Single finger, not pinching -- don't consume, let scroll handle it
        }
    }
}

// ── EDIT panel (phase 6) ────────────────────────────────────────────

/**
 * EDIT sub-panel: QUANT, VELOC, SUSTAIN. Three captioned NjButtons.
 *
 * QUANT is a momentary action -- snap selected notes (or all notes if no
 * selection) to the active grid. VELOC is a latching toggle that puts the
 * velocity strip into edit mode. SUSTAIN is reserved (always dim) -- the
 * slot exists so the layout doesn't shift when the feature lands.
 */
@Composable
private fun EditPanelContent(
    state: PianoRollState,
    onAction: (PianoRollAction) -> Unit
) {
    val dimColor = NjMuted2.copy(alpha = 0.3f)
    val hasContent = state.notes.isNotEmpty()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NjButton(
            text = "",
            icon = Icons.Filled.GridOn,
            caption = "QUANT",
            onClick = { onAction(PianoRollAction.QuantizeSelected) },
            textColor = if (hasContent) NjOnBg else dimColor
        )
        NjButton(
            text = "",
            icon = Icons.Filled.BarChart,
            caption = "VELOC",
            onClick = { onAction(PianoRollAction.ToggleVelocityEditMode) },
            isActive = state.isVelocityEditMode,
            ledColor = NjAmber
        )
        NjButton(
            text = "",
            icon = Icons.Filled.AllInclusive,
            caption = "SUSTAIN",
            onClick = { /* reserved -- functional in a follow-up spec */ },
            textColor = dimColor
        )
        Spacer(Modifier.weight(1f))
    }
}

// ── SCALE panel (phase 5) ───────────────────────────────────────────

/**
 * SCALE sub-panel content. Two rows:
 *
 *  Row 1 — primary controls: ROOT knob, SCALE knob (wide LCD spells out
 *          the scale name), CHORD-type rotary selector.
 *  Row 2 — modifiers: HILITE and CHORDS toggles, demoted below the
 *          primary controls because they're modifiers, not core picks.
 *
 * Chord type uses [NjRotarySelector] instead of a knob: only three values
 * (TRIAD / 7TH / 9TH) at present, and a vertical knob made you drag a long
 * way per detent. The rotary scales naturally if more chord types are added.
 */
@Composable
private fun ScalePanelContent(
    state: PianoRollState,
    onAction: (PianoRollAction) -> Unit
) {
    val scaleTypes = remember { MusicalScaleHelper.ScaleType.entries }
    val chordTypes = remember { MusicalScaleHelper.ChordType.entries }

    val rootValue = state.scaleRoot.coerceIn(0, 11) / 11f
    val scaleIndex = scaleTypes.indexOf(state.scaleType).coerceAtLeast(0)
    val scaleValue = if (scaleTypes.size > 1) {
        scaleIndex.toFloat() / (scaleTypes.size - 1)
    } else 0f

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KnobWithReadout(
                label = "ROOT",
                readout = MusicalScaleHelper.NOTE_NAMES[state.scaleRoot.coerceIn(0, 11)],
                value = rootValue,
                onValueChange = { v ->
                    val newRoot = (v * 11f).roundToInt().coerceIn(0, 11)
                    if (newRoot != state.scaleRoot) {
                        onAction(PianoRollAction.SetScaleRoot(newRoot))
                    }
                }
            )
            KnobWithReadout(
                label = "SCALE",
                readout = state.scaleType.displayName.uppercase(),
                value = scaleValue,
                onValueChange = { v ->
                    val maxIndex = scaleTypes.size - 1
                    val newIndex = (v * maxIndex).roundToInt().coerceIn(0, maxIndex)
                    val newType = scaleTypes[newIndex]
                    if (newType != state.scaleType) {
                        onAction(PianoRollAction.SetScaleType(newType))
                    }
                },
                readoutWidth = 84.dp
            )
            Spacer(Modifier.weight(1f))
            NjRotarySelector(
                options = chordTypes,
                selected = state.chordType,
                onSelect = { newType ->
                    if (newType != state.chordType) {
                        onAction(PianoRollAction.SetChordType(newType))
                    }
                },
                label = { it.displayName.uppercase() },
                caption = "CHORD"
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NjButton(
                text = "HILITE",
                onClick = { onAction(PianoRollAction.ToggleScale) },
                isActive = state.isScaleEnabled,
                ledColor = NjAmber
            )
            NjButton(
                text = "CHORDS",
                onClick = { onAction(PianoRollAction.ToggleChordMode) },
                isActive = state.isChordMode,
                ledColor = NjAmber
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Knob with an LCD readout above and a small caption below -- the standard
 * SCALE-panel knob unit. Readout uses IBM Plex Mono in amber, like a
 * Roland faceplate's value window. Caption is muted IBM Plex Mono.
 *
 * [readoutWidth] lets the caller widen the LCD when the value text is
 * longer than the default 44dp can fit (e.g. spelled-out scale names).
 */
@Composable
private fun KnobWithReadout(
    label: String,
    readout: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    readoutWidth: androidx.compose.ui.unit.Dp = 44.dp
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NjRecessedPanel(
            modifier = Modifier.width(readoutWidth)
        ) {
            Text(
                text = readout,
                fontFamily = IbmPlexMono,
                fontSize = 9.sp,
                color = NjAmber.copy(alpha = 0.85f),
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                maxLines = 1
            )
        }
        Spacer(Modifier.height(2.dp))
        NjKnob(
            value = value,
            onValueChange = onValueChange,
            knobSize = 36.dp
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = label,
            fontFamily = IbmPlexMono,
            fontSize = 9.sp,
            color = NjMuted,
            letterSpacing = 0.5.sp
        )
    }
}

// ── TOOLS panel (phase 4) ───────────────────────────────────────────

/**
 * TOOLS sub-panel content: SPLIT, ERASE, COPY, UNDO, REDO. Five captioned
 * NjButtons in a row, each weighted equally so they fill the sub-panel width.
 *
 * Disabled state: each button dims via `textColor` when its action would be
 * a no-op. The button still receives the tap (and the haptic) but the VM
 * handler bails early -- same pattern the old toolbar used for Undo/Redo.
 */
@Composable
private fun ToolsPanelContent(
    state: PianoRollState,
    onAction: (PianoRollAction) -> Unit
) {
    val hasSelection = state.selectedNoteIds.isNotEmpty()
    val dimColor = NjMuted2.copy(alpha = 0.3f)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Row 1 -- editor mode selector. Three latching buttons that
        // disambiguate grid taps and drags. DRAW is the default.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NjButton(
                text = "",
                icon = Icons.Filled.Edit,
                caption = "DRAW",
                onClick = { onAction(PianoRollAction.SetEditorMode(EditorMode.DRAW)) },
                isActive = state.editorMode == EditorMode.DRAW,
                ledColor = NjAmber,
                modifier = Modifier.weight(1f)
            )
            NjButton(
                text = "",
                icon = Icons.Filled.SelectAll,
                caption = "SELECT",
                onClick = { onAction(PianoRollAction.SetEditorMode(EditorMode.SELECT)) },
                isActive = state.editorMode == EditorMode.SELECT,
                ledColor = NjAmber,
                modifier = Modifier.weight(1f)
            )
            NjButton(
                text = "",
                icon = Icons.Filled.Delete,
                caption = "ERASE",
                onClick = { onAction(PianoRollAction.SetEditorMode(EditorMode.ERASE)) },
                isActive = state.editorMode == EditorMode.ERASE,
                ledColor = NjAmber,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.weight(1f))  // keeps row width parity with row 2
        }
        // Row 2 -- one-shot actions.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NjButton(
                text = "",
                icon = Icons.AutoMirrored.Filled.CallSplit,
                caption = "SPLIT",
                onClick = { onAction(PianoRollAction.SplitSelected) },
                textColor = if (hasSelection) NjOnBg else dimColor,
                modifier = Modifier.weight(1f)
            )
            NjButton(
                text = "",
                icon = Icons.Filled.ContentCopy,
                caption = "COPY",
                onClick = { onAction(PianoRollAction.CopySelected) },
                textColor = if (hasSelection) NjOnBg else dimColor,
                modifier = Modifier.weight(1f)
            )
            NjButton(
                text = "",
                icon = Icons.AutoMirrored.Filled.Undo,
                caption = "UNDO",
                onClick = { onAction(PianoRollAction.Undo) },
                textColor = if (state.canUndo) NjOnBg else dimColor,
                modifier = Modifier.weight(1f)
            )
            NjButton(
                text = "",
                icon = Icons.AutoMirrored.Filled.Redo,
                caption = "REDO",
                onClick = { onAction(PianoRollAction.Redo) },
                textColor = if (state.canRedo) NjOnBg else dimColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ── New top bar (phase 2) ───────────────────────────────────────────

/**
 * Captioned top bar for the full-screen piano roll.
 *
 * Layout: BACK on the left, two-line title in the middle (track name on top,
 * KEY · BPM · INSTRUMENT below), RESTART + PLAY on the right. Mirrors a
 * Roland/Korg faceplate -- every control is a labeled hardware button.
 */
@Composable
private fun PianoRollTopBar(
    state: PianoRollState,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onPlayPause: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NjSurface)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NjButton(
            text = "",
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            caption = "BACK",
            onClick = onBack
        )
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = state.trackName.ifEmpty { "Piano Roll" },
                style = MaterialTheme.typography.titleSmall,
                color = NjOnBg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatTopBarSubtitle(state),
                fontFamily = IbmPlexMono,
                fontSize = 10.sp,
                color = NjMuted2,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        NjButton(
            text = "",
            icon = Icons.Filled.SkipPrevious,
            caption = "RESTART",
            onClick = onRestart
        )
        Spacer(Modifier.width(4.dp))
        NjButton(
            text = "",
            icon = NjIcons.PlayPause,
            caption = "PLAY",
            onClick = onPlayPause,
            isActive = state.isPlaying,
            ledColor = NjLedGreen
        )
    }
}

private fun formatTopBarSubtitle(state: PianoRollState): String {
    val key = MusicalScaleHelper.NOTE_NAMES[state.scaleRoot.coerceIn(0, 11)]
    // 3-letter abbreviation of the scale type for the LCD readout aesthetic
    // (e.g. MAJOR -> MAJ, DORIAN -> DOR). Matches the Roland/Korg compact form.
    val scale = state.scaleType.name.take(3)
    val bpm = state.bpm.toInt()
    val instr = state.instrumentName.uppercase().ifEmpty { "—" }
    return "$key $scale · $bpm BPM · $instr"
}

// ── Phase 1 skeleton placeholders ───────────────────────────────────
// These render the new layout shape so the screen reads as the redesign
// in progress. Phases 2-10 progressively replace each with real behavior.

/**
 * Adaptive timeline ruler for the full-screen piano roll.
 *
 * Mirrors Studio's TimeRuler: bar/beat/sub-beat ticks with measure number
 * labels that adapt to zoom -- bar ticks always, beat ticks once each
 * beat is wider than 4px, sub-beat ticks at the active grid resolution
 * once those are visible too.
 *
 * Renders the selector (NjCursorTeal vertical line) and the loop region
 * band (amber fill + endpoint borders + triangle handles) on top of the
 * ticks. The grid playhead extends through the ruler so the user sees
 * the playback position in musical time.
 *
 * Gestures: tap = SetSelector at tap position. Drag from far away = define
 * a new loop region from down position to release position. Drag near an
 * existing loop handle = adjust just that endpoint. The ruler scrolls
 * horizontally in lockstep with the grid via [horizontalScrollState].
 */
@Composable
private fun PianoRollTimelineRuler(
    contentMs: Long,
    pxPerMs: Float,
    bpm: Double,
    timeSigNum: Int,
    timeSigDen: Int,
    gridResolution: Int,
    selectorMs: Long,
    positionMs: Long,
    isPlaying: Boolean,
    loopStartMs: Long?,
    loopEndMs: Long?,
    isLoopEnabled: Boolean,
    horizontalScrollState: ScrollState,
    onSetSelector: (Long) -> Unit,
    onSetLoopRegion: (Long, Long) -> Unit
) {
    val density = LocalDensity.current
    val gridWidthDp = with(density) { (contentMs * pxPerMs).toDp() }
    val textMeasurer = rememberTextMeasurer()
    val rulerHeight = 28.dp
    val view = LocalView.current

    // Hoist composable theme reads -- DrawScope is not composable.
    val tickColor = NjMuted2.copy(alpha = 0.55f)
    val subBeatColor = NjMuted2.copy(alpha = 0.25f)
    val labelColor = NjOnBg.copy(alpha = 0.75f)
    val selectorColor = NjCursorTeal
    val loopColor = NjAmber
    val playheadColor = NjAmber

    // Capture latest values without restarting the pointerInput coroutine on
    // every change. Keying pointerInput on loopStartMs/loopEndMs would cancel
    // the in-flight drag the moment we updated state, which made loop region
    // drags die after a single snap step.
    val currentLoopStart by rememberUpdatedState(loopStartMs)
    val currentLoopEnd by rememberUpdatedState(loopEndMs)
    val currentPxPerMs by rememberUpdatedState(pxPerMs)
    val currentOnSetSelector by rememberUpdatedState(onSetSelector)
    val currentOnSetLoopRegion by rememberUpdatedState(onSetLoopRegion)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rulerHeight)
            .background(NjSurface)
    ) {
        // Empty 48dp column to match the keys column above the grid.
        Box(modifier = Modifier.width(KEYS_WIDTH_DP.dp).fillMaxHeight())

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(NjPanelInset)
                .horizontalScroll(horizontalScrollState)
        ) {
            Canvas(
                modifier = Modifier
                    .width(gridWidthDp)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        val handleHitZonePx = 32.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val touchX = down.position.x
                            val px = currentPxPerMs

                            val startPx = currentLoopStart?.let { it * px }
                            val endPx = currentLoopEnd?.let { it * px }
                            val nearStart = startPx != null &&
                                abs(touchX - startPx) <= handleHitZonePx
                            val nearEnd = endPx != null &&
                                abs(touchX - endPx) <= handleHitZonePx

                            // Pick the closer handle when both are in range.
                            val handleMode: Int = when {
                                nearStart && nearEnd -> {
                                    if (abs(touchX - startPx!!) <= abs(touchX - endPx!!)) 1 else 2
                                }
                                nearStart -> 1
                                nearEnd -> 2
                                else -> 0
                            }

                            // Wait for slop-crossing drag, or release for a tap.
                            val firstDrag = awaitHorizontalTouchSlopOrCancellation(down.id) { c, _ ->
                                c.consume()
                            }
                            if (firstDrag == null) {
                                // Pure tap -- set selector at tap position.
                                if (handleMode == 0) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    val tapMs = (touchX / px).toLong().coerceAtLeast(0L)
                                    currentOnSetSelector(tapMs)
                                }
                                return@awaitEachGesture
                            }

                            // Drag began -- haptic latch matches the existing
                            // long-press feel on note drags.
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                            when (handleMode) {
                                1 -> {
                                    // Drag the loop start handle.
                                    horizontalDrag(firstDrag.id) { change ->
                                        change.consume()
                                        val ms = (change.position.x / currentPxPerMs)
                                            .toLong().coerceAtLeast(0L)
                                        val keepEnd = currentLoopEnd ?: return@horizontalDrag
                                        currentOnSetLoopRegion(ms, keepEnd)
                                    }
                                }
                                2 -> {
                                    // Drag the loop end handle.
                                    horizontalDrag(firstDrag.id) { change ->
                                        change.consume()
                                        val ms = (change.position.x / currentPxPerMs)
                                            .toLong().coerceAtLeast(0L)
                                        val keepStart = currentLoopStart ?: return@horizontalDrag
                                        currentOnSetLoopRegion(keepStart, ms)
                                    }
                                }
                                else -> {
                                    // Define a new loop region from down to finger position.
                                    val anchorMs = (touchX / px).toLong().coerceAtLeast(0L)
                                    horizontalDrag(firstDrag.id) { change ->
                                        change.consume()
                                        val ms = (change.position.x / currentPxPerMs)
                                            .toLong().coerceAtLeast(0L)
                                        currentOnSetLoopRegion(anchorMs, ms)
                                    }
                                }
                            }
                        }
                    }
            ) {
                val rulerHeightPx = size.height
                val tickAreaTop = rulerHeightPx * 0.45f
                val beatTickTop = rulerHeightPx * 0.65f
                val subBeatTickTop = rulerHeightPx * 0.78f

                fun msToX(ms: Double): Float = (ms * pxPerMs).toFloat()

                val beatMs = MusicalTimeConverter.msPerBeat(bpm, timeSigDen)
                val measureMs = MusicalTimeConverter.msPerMeasure(bpm, timeSigNum, timeSigDen)
                if (beatMs <= 0.0 || measureMs <= 0.0) return@Canvas

                val beatPx = (beatMs * pxPerMs).toFloat()
                val showBeatTicks = beatPx >= 4f
                val gridStepMs = MusicalTimeConverter.msPerGridStep(bpm, gridResolution, timeSigDen)
                val gridStepPx = if (gridStepMs > 0.0) (gridStepMs * pxPerMs).toFloat() else 0f
                val showSubBeat = gridStepPx >= 4f && gridStepMs < beatMs

                val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp)

                var ms = 0.0
                var measureNumber = 1
                while (ms <= contentMs) {
                    val x = msToX(ms)
                    if (x > size.width) break

                    // Measure tick (full height of tick area)
                    drawLine(
                        color = tickColor,
                        start = Offset(x, tickAreaTop),
                        end = Offset(x, rulerHeightPx),
                        strokeWidth = 1f
                    )

                    // Measure number label above the tick
                    val label = measureNumber.toString()
                    val measured = textMeasurer.measure(label, labelStyle)
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(x + 3f, tickAreaTop - measured.size.height - 1f)
                    )

                    if (showBeatTicks) {
                        for (beat in 1 until timeSigNum) {
                            val beatX = msToX(ms + beat * beatMs)
                            if (beatX > size.width) break
                            drawLine(
                                color = tickColor.copy(alpha = 0.7f),
                                start = Offset(beatX, beatTickTop),
                                end = Offset(beatX, rulerHeightPx),
                                strokeWidth = 0.5f
                            )
                        }
                        if (showSubBeat) {
                            var stepMs = gridStepMs
                            while (stepMs < measureMs - 0.5) {
                                val frac = stepMs / beatMs
                                val isBeat = frac - frac.toLong() < 0.01 || frac - frac.toLong() > 0.99
                                if (!isBeat) {
                                    val stepX = msToX(ms + stepMs)
                                    if (stepX > size.width) break
                                    drawLine(
                                        color = subBeatColor,
                                        start = Offset(stepX, subBeatTickTop),
                                        end = Offset(stepX, rulerHeightPx),
                                        strokeWidth = 0.5f
                                    )
                                }
                                stepMs += gridStepMs
                            }
                        }
                    }

                    ms += measureMs
                    measureNumber++
                }

                // Loop region: amber fill across the ruler height, endpoint
                // bars at left/right, triangle handles flagging the bracket.
                if (loopStartMs != null && loopEndMs != null && loopEndMs > loopStartMs) {
                    val sX = (loopStartMs * pxPerMs).toFloat()
                    val eX = (loopEndMs * pxPerMs).toFloat()
                    val fillAlpha = if (isLoopEnabled) 0.18f else 0.08f
                    val borderAlpha = if (isLoopEnabled) 0.7f else 0.35f
                    drawRect(
                        color = loopColor.copy(alpha = fillAlpha),
                        topLeft = Offset(sX, 0f),
                        size = Size(eX - sX, rulerHeightPx)
                    )
                    drawLine(
                        color = loopColor.copy(alpha = borderAlpha),
                        start = Offset(sX, 0f),
                        end = Offset(sX, rulerHeightPx),
                        strokeWidth = 2f
                    )
                    drawLine(
                        color = loopColor.copy(alpha = borderAlpha),
                        start = Offset(eX, 0f),
                        end = Offset(eX, rulerHeightPx),
                        strokeWidth = 2f
                    )
                    val triW = 10.dp.toPx()
                    val triH = 8.dp.toPx()
                    drawPath(
                        path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(sX, 0f)
                            lineTo(sX + triW, 0f)
                            lineTo(sX, triH)
                            close()
                        },
                        color = loopColor.copy(alpha = borderAlpha)
                    )
                    drawPath(
                        path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(eX, 0f)
                            lineTo(eX - triW, 0f)
                            lineTo(eX, triH)
                            close()
                        },
                        color = loopColor.copy(alpha = borderAlpha)
                    )
                }

                // Selector line (cursor teal) -- always rendered, even at 0.
                val selectorX = (selectorMs * pxPerMs).toFloat()
                if (selectorX in 0f..size.width) {
                    drawLine(
                        color = selectorColor,
                        start = Offset(selectorX, 0f),
                        end = Offset(selectorX, rulerHeightPx),
                        strokeWidth = 1.5f
                    )
                }

                // Playhead line in the ruler (matches grid playhead).
                if (isPlaying || positionMs > 0) {
                    val playheadX = (positionMs * pxPerMs).toFloat()
                    if (playheadX in 0f..size.width) {
                        drawLine(
                            color = playheadColor,
                            start = Offset(playheadX, 0f),
                            end = Offset(playheadX, rulerHeightPx),
                            strokeWidth = 2f
                        )
                    }
                }
            }
        }
    }
}

/**
 * Velocity strip rendered below the piano roll grid.
 *
 * Read-only by default: bars render at each note's X position with height
 * proportional to velocity (0..1). When [isVelocityEditMode] is latched, the
 * bars for currently-selected notes become draggable -- drag a bar up/down
 * to change velocity. Multi-selected notes track the same delta with
 * independent clamping. Commits to the VM happen on release; live preview
 * runs through local state during the drag.
 *
 * Scrolls horizontally in lockstep with the grid via the shared
 * [horizontalScrollState]. The VELOC label occupies the same 48dp column
 * the piano keys sit in above, keeping bars column-aligned with notes.
 */
@Composable
private fun PianoRollVelocityStrip(
    notes: List<MidiNoteEntity>,
    selectedNoteIds: Set<Long>,
    isVelocityEditMode: Boolean,
    horizontalScrollState: ScrollState,
    pxPerMs: Float,
    contentMs: Long,
    trackColor: Color,
    onCommitVelocities: (Map<Long, Float>) -> Unit
) {
    val density = LocalDensity.current
    val gridWidthDp = with(density) { (contentMs * pxPerMs).toDp() }
    val view = LocalView.current

    // Live preview during drag -- renders before the VM commits on release.
    var previewVelocities by remember { mutableStateOf<Map<Long, Float>>(emptyMap()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(NjSurface)
    ) {
        Box(
            modifier = Modifier
                .width(KEYS_WIDTH_DP.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            val labelColor = if (isVelocityEditMode) {
                NjAmber.copy(alpha = 0.85f)
            } else {
                NjMuted2.copy(alpha = 0.7f)
            }
            Text(
                text = "VELOC",
                fontFamily = IbmPlexMono,
                fontSize = 9.sp,
                color = labelColor,
                letterSpacing = 0.5.sp
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(NjPanelInset)
                .horizontalScroll(horizontalScrollState)
        ) {
            Canvas(
                modifier = Modifier
                    .width(gridWidthDp)
                    .fillMaxHeight()
                    .pointerInput(notes, selectedNoteIds, isVelocityEditMode, pxPerMs) {
                        if (!isVelocityEditMode) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val tapMs = (down.position.x / pxPerMs).toLong()
                            // Find a SELECTED note whose bar contains the touch.
                            val anchor = notes.firstOrNull { n ->
                                n.id in selectedNoteIds &&
                                    tapMs >= n.startMs &&
                                    tapMs <= n.startMs + n.durationMs
                            } ?: return@awaitEachGesture
                            down.consume()
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                            val startVelocities = selectedNoteIds.associateWith { id ->
                                notes.find { it.id == id }?.velocity ?: 0.8f
                            }
                            val anchorStartVelocity = anchor.velocity
                            val startY = down.position.y
                            val stripHeight = size.height.coerceAtLeast(1).toFloat()
                            var lastDelta = 0f
                            var cursorMap: Map<Long, Float> = startVelocities

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                change.consume()
                                val deltaY = change.position.y - startY
                                val rawDeltaVelocity = -deltaY / stripHeight
                                if (rawDeltaVelocity != lastDelta) {
                                    lastDelta = rawDeltaVelocity
                                    cursorMap = startVelocities.mapValues { (_, v) ->
                                        (v + rawDeltaVelocity).coerceIn(0f, 1f)
                                    }
                                    previewVelocities = cursorMap
                                }
                            }
                            // Commit: only if the anchor's velocity actually changed.
                            val anchorFinal = (anchorStartVelocity + lastDelta).coerceIn(0f, 1f)
                            if (anchorFinal != anchorStartVelocity) {
                                onCommitVelocities(cursorMap)
                            }
                            previewVelocities = emptyMap()
                        }
                    }
            ) {
                // Faint baseline so the strip reads as a continuous panel
                // even when there are no notes.
                drawLine(
                    color = trackColor.copy(alpha = 0.15f),
                    start = Offset(0f, size.height - 1f),
                    end = Offset(size.width, size.height - 1f),
                    strokeWidth = 1f
                )

                for (note in notes) {
                    val barX = note.startMs * pxPerMs
                    val barWidth = (note.durationMs * pxPerMs).coerceAtLeast(3f)
                    val effectiveVelocity = previewVelocities[note.id] ?: note.velocity
                    val barHeight = (effectiveVelocity * size.height).coerceAtLeast(2f)
                    val barY = size.height - barHeight
                    val isSelected = note.id in selectedNoteIds
                    val color = when {
                        isVelocityEditMode && !isSelected -> trackColor.copy(alpha = 0.25f)
                        isSelected -> trackColor.copy(alpha = 0.95f)
                        else -> trackColor.copy(alpha = 0.65f)
                    }
                    drawRect(
                        color = color,
                        topLeft = Offset(barX, barY),
                        size = Size(barWidth, barHeight)
                    )
                }
            }
        }
    }
}

/** Tab bar -- four MODE buttons. Functional in phase 1; sub-panels fill in
 *  in phases 4-6 + 10. */
@Composable
private fun PianoRollTabBar(
    activeTab: PianoRollTab,
    onTabSelect: (PianoRollTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NjSurface)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        NjButton(
            text = "",
            icon = Icons.Filled.ContentCut,
            caption = "TOOLS",
            onClick = { onTabSelect(PianoRollTab.TOOLS) },
            isActive = activeTab == PianoRollTab.TOOLS,
            ledColor = NjAmber,
            modifier = Modifier.weight(1f)
        )
        NjButton(
            text = "",
            icon = Icons.Filled.MusicNote,
            caption = "SCALE",
            onClick = { onTabSelect(PianoRollTab.SCALE) },
            isActive = activeTab == PianoRollTab.SCALE,
            ledColor = NjAmber,
            modifier = Modifier.weight(1f)
        )
        NjButton(
            text = "",
            icon = Icons.Filled.Tune,
            caption = "EDIT",
            onClick = { onTabSelect(PianoRollTab.EDIT) },
            isActive = activeTab == PianoRollTab.EDIT,
            ledColor = NjAmber,
            modifier = Modifier.weight(1f)
        )
        NjButton(
            text = "",
            icon = Icons.Filled.Edit,
            caption = "INSTR",
            onClick = { onTabSelect(PianoRollTab.INSTR) },
            isActive = activeTab == PianoRollTab.INSTR,
            ledColor = NjAmber,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Two-row sub-panel under the tab bar.
 *
 * Top row: per-tab controls (placeholders for TOOLS / SCALE / EDIT / INSTR
 * until phases 4-6 + 10 fill them in).
 *
 * Bottom row: persistent grid resolution chips (1/2 1/4 1/8 1/16 1/32) on
 * the left, a thin bevel separator, then the SNAP toggle on the right.
 * Always visible regardless of active tab.
 */
@Composable
private fun PianoRollSubPanel(
    activeTab: PianoRollTab,
    state: PianoRollState,
    onAction: (PianoRollAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NjBg)
    ) {
        // ── Top row: per-tab content ──
        // Fixed height so the sub-panel doesn't jump when switching tabs.
        // Sized to fit the tallest layout (SCALE's two sub-rows). Shorter
        // panels (TOOLS, EDIT) center vertically inside this envelope.
        // INSTR never reaches this row -- the whole sub-panel slides away.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(NjSurface)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            when (activeTab) {
                PianoRollTab.TOOLS -> ToolsPanelContent(state, onAction)
                PianoRollTab.SCALE -> ScalePanelContent(state, onAction)
                PianoRollTab.EDIT -> EditPanelContent(state, onAction)
                PianoRollTab.INSTR -> Unit  // hidden -- sub-panel slides away
            }
        }

        // ── Bottom row: grid resolution rotary + SNAP toggle ──
        // The chips for 1/2 .. 1/32 used to live here, but each chip needed
        // its own LED + label and SNAP got squished off the right edge in
        // portrait. A rotary takes one widget's width, scales to any number
        // of values, and frees room for SNAP to sit comfortably alongside.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NjPanelInset)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NjRotarySelector(
                options = GRID_RESOLUTION_OPTIONS,
                selected = state.gridResolution,
                onSelect = { value ->
                    if (value != state.gridResolution) {
                        onAction(PianoRollAction.SetGridResolution(value))
                    }
                },
                label = { "1/$it" },
                caption = "GRID"
            )
            Spacer(Modifier.weight(1f))
            // Thin bevel separator -- visually groups GRID rotary distinct
            // from SNAP toggle.
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            )
            Spacer(Modifier.width(6.dp))
            NjButton(
                text = "SNAP",
                onClick = { onAction(PianoRollAction.ToggleSnap) },
                isActive = state.isSnapEnabled,
                ledColor = NjAmber
            )
        }
    }
}

private val GRID_RESOLUTION_OPTIONS = listOf(2, 4, 8, 16, 32)
