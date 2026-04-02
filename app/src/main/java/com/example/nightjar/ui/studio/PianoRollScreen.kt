package com.example.nightjar.ui.studio

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.example.nightjar.ui.theme.NjBg
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.Alignment
import com.example.nightjar.audio.MusicalScaleHelper
import kotlin.math.abs
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

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val BLACK_KEYS = setOf(1, 3, 6, 8, 10) // indices within octave

/** Local preview state for an in-progress note drag (move or resize). */
private data class GroupDragState(
    val noteIds: Set<Long>,       // all notes being dragged
    val anchorNoteId: Long,       // the note the user touched
    val deltaMs: Long = 0L,       // time offset (move mode)
    val deltaPitch: Int = 0,      // pitch offset (move mode)
    val deltaDurationMs: Long = 0L, // duration offset (resize mode)
    val isResize: Boolean = false
)

/** Timeout for double-tap-to-delete detection. */
private const val DOUBLE_TAP_TIMEOUT_MS = 300L

@OptIn(ExperimentalMaterial3Api::class)
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
                    text = "",
                    icon = Icons.AutoMirrored.Filled.Undo,
                    onClick = { viewModel.onAction(PianoRollAction.Undo) },
                    textColor = if (state.canUndo) NjOnBg else NjMuted2.copy(alpha = 0.3f)
                )
                Spacer(Modifier.width(2.dp))
                NjButton(
                    text = "",
                    icon = Icons.AutoMirrored.Filled.Redo,
                    onClick = { viewModel.onAction(PianoRollAction.Redo) },
                    textColor = if (state.canRedo) NjOnBg else NjMuted2.copy(alpha = 0.3f)
                )
                Spacer(Modifier.width(2.dp))
                NjButton(
                    text = "",
                    icon = Icons.Filled.Delete,
                    onClick = { viewModel.onAction(PianoRollAction.DeleteSelected) },
                    textColor = if (state.selectedNoteIds.isNotEmpty()) NjError else NjMuted2.copy(alpha = 0.3f)
                )
                Spacer(Modifier.width(8.dp))
                @OptIn(ExperimentalFoundationApi::class)
                Box(
                    modifier = Modifier.combinedClickable(
                        onClick = { viewModel.onAction(PianoRollAction.CycleGridResolution) },
                        onLongClick = {
                            horizontalZoom = 1f
                            verticalZoom = 1f
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                    )
                ) {
                    NjButton(
                        text = "1/${state.gridResolution}",
                        onClick = { viewModel.onAction(PianoRollAction.CycleGridResolution) },
                        textColor = NjAmber.copy(alpha = 0.8f)
                    )
                }
                Spacer(Modifier.width(4.dp))
                NjButton(
                    text = "Snap",
                    onClick = { viewModel.onAction(PianoRollAction.ToggleSnap) },
                    isActive = state.isSnapEnabled,
                    ledColor = NjAmber
                )
                Spacer(Modifier.width(4.dp))
                NjButton(
                    text = "Restart",
                    icon = Icons.Filled.SkipPrevious,
                    onClick = { viewModel.onAction(PianoRollAction.SeekTo(0L)) },
                    textColor = NjLedGreen.copy(alpha = 0.5f),
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
                    ledColor = NjLedGreen
                )
                Spacer(Modifier.width(8.dp))
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = NjSurface
            )
        )

        // Scale & chord controls
        ScaleChordControls(state = state, onAction = viewModel::onAction)

        // Diatonic chord reference strip (visible when scale is enabled)
        ChordReferenceStrip(chords = state.diatonicChords)

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
                                canStart = { dragState == null },
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
                                            val dragIds = if (edgeNote.id in state.selectedNoteIds) {
                                                state.selectedNoteIds
                                            } else {
                                                setOf(edgeNote.id)
                                            }
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
                                        } else {
                                            // Tap on edge = double-tap or toggle selection
                                            val fingerLifted = currentEvent.changes
                                                .none { it.id == down.id && it.pressed }
                                            if (fingerLifted) {
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
                                            val fingerLifted = currentEvent.changes
                                                .none { it.id == down.id && it.pressed }
                                            if (fingerLifted) {
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
                                    } else {
                                        // ── EMPTY CELL: tap to clear selection or place note ──
                                        val longPress = awaitLongPressOrCancellation(down.id)
                                        if (longPress == null) {
                                            val fingerLifted = currentEvent.changes
                                                .none { it.id == down.id && it.pressed }
                                            if (fingerLifted) {
                                                lastTapNoteId = null
                                                lastTapTimeMs = 0L
                                                if (state.selectedNoteIds.isNotEmpty()) {
                                                    viewModel.onAction(PianoRollAction.ClearSelection)
                                                } else {
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
                            scaleHighlightColor = pianoAmber
                        )
                    }
                }
            }
        }
    }
}

// ── Scale & chord controls ──────────────────────────────────────────

@Composable
private fun ScaleChordControls(
    state: PianoRollState,
    onAction: (PianoRollAction) -> Unit
) {
    var scaleDropdownExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NjSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Scale on/off toggle
        NjButton(
            text = "Scale",
            onClick = { onAction(PianoRollAction.ToggleScale) },
            isActive = state.isScaleEnabled,
            ledColor = NjAmber
        )

        // Root note (tap to cycle C → C# → D → ...)
        NjButton(
            text = MusicalScaleHelper.NOTE_NAMES[state.scaleRoot],
            onClick = { onAction(PianoRollAction.SetScaleRoot((state.scaleRoot + 1) % 12)) },
            textColor = NjAmber.copy(alpha = 0.8f)
        )

        // Scale type (tap to open dropdown)
        Box {
            NjButton(
                text = state.scaleType.displayName,
                onClick = { scaleDropdownExpanded = true },
                textColor = NjAmber.copy(alpha = 0.8f)
            )
            DropdownMenu(
                expanded = scaleDropdownExpanded,
                onDismissRequest = { scaleDropdownExpanded = false },
                modifier = Modifier.background(NjSurface)
            ) {
                for (group in MusicalScaleHelper.ScaleGroup.entries) {
                    // Group header
                    Text(
                        text = group.displayName,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = NjMuted2
                    )
                    // Scales in this group
                    for (scale in MusicalScaleHelper.ScaleType.entries.filter { it.group == group }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = scale.displayName,
                                    color = if (scale == state.scaleType) NjAmber else NjOnBg
                                )
                            },
                            onClick = {
                                onAction(PianoRollAction.SetScaleType(scale))
                                scaleDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Chord on/off toggle
        NjButton(
            text = "Chord",
            onClick = { onAction(PianoRollAction.ToggleChordMode) },
            isActive = state.isChordMode,
            ledColor = NjAmber
        )

        // Chord type (tap to cycle Triad → 7th → 9th)
        NjButton(
            text = state.chordType.displayName,
            onClick = { onAction(PianoRollAction.CycleChordType) },
            textColor = NjAmber.copy(alpha = 0.8f)
        )
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
    scaleHighlightColor: Color = Color.Transparent
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
            if (dragState!!.isResize) {
                drawPitch = note.pitch
                drawStartMs = note.startMs
                drawDurationMs = (note.durationMs + dragState.deltaDurationMs).coerceAtLeast(50L)
            } else {
                drawPitch = (note.pitch + dragState.deltaPitch).coerceIn(0, 127)
                drawStartMs = (note.startMs + dragState.deltaMs).coerceAtLeast(0L)
                drawDurationMs = note.durationMs
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
