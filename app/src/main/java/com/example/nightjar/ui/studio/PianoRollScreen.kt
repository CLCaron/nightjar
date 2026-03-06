package com.example.nightjar.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import com.example.nightjar.ui.theme.NjPanelInset
import com.example.nightjar.ui.theme.NjStudioAccent
import com.example.nightjar.ui.theme.NjStudioGreen
import com.example.nightjar.ui.theme.NjSurface
import com.example.nightjar.ui.theme.NjSurface2
import com.example.nightjar.ui.theme.NjTrackColors

/** Height of each semitone row in dp. */
private const val ROW_HEIGHT_DP = 14f
/** Width of the piano keys panel in dp. */
private const val KEYS_WIDTH_DP = 48f
/** Pixels per millisecond at default zoom. */
private const val PX_PER_MS = 0.15f
/** Total visible range: MIDI notes 0-127 */
private const val TOTAL_NOTES = 128
/** Default starting octave scroll position (middle C area). */
private const val DEFAULT_SCROLL_NOTE = 48

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val BLACK_KEYS = setOf(1, 3, 6, 8, 10) // indices within octave

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PianoRollScreen(
    onBack: () -> Unit,
    viewModel: PianoRollViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val density = LocalDensity.current

    val rowHeightPx = with(density) { ROW_HEIGHT_DP.dp.toPx() }
    val keysWidthPx = with(density) { KEYS_WIDTH_DP.dp.toPx() }
    val totalGridHeight = (TOTAL_NOTES * ROW_HEIGHT_DP).dp

    // Time conversion (MusicalTimeConverter is a stateless object, no construction needed)

    // Compute grid width from content
    val maxNoteEndMs = state.notes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
    val contentMs = maxOf(maxNoteEndMs + 4000L, state.totalDurationMs + 4000L, 16000L)
    val gridWidthDp = (contentMs * PX_PER_MS / density.density).dp

    val verticalScrollState = rememberScrollState(
        (DEFAULT_SCROLL_NOTE * ROW_HEIGHT_DP * density.density).toInt()
    )
    val horizontalScrollState = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()

    // Track color for notes
    val noteColor = NjTrackColors[0] // Will be parameterized by track sortIndex

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
                    text = if (state.isSnapEnabled) "Snap" else "Free",
                    onClick = { viewModel.onAction(PianoRollAction.ToggleSnap) },
                    isActive = state.isSnapEnabled,
                    ledColor = NjStudioAccent
                )
                Spacer(Modifier.width(4.dp))
                NjButton(
                    text = if (state.isPlaying) "Stop" else "Play",
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
                            detectTapGestures(
                                onTap = { offset ->
                                    // Determine pitch and time from tap position
                                    val pitch = TOTAL_NOTES - 1 - (offset.y / rowHeightPx).toInt()
                                    val tapMs = (offset.x / (PX_PER_MS * density.density)).toLong()

                                    // Check if tapping on existing note
                                    val existingNote = state.notes.find { note ->
                                        note.pitch == pitch &&
                                            tapMs >= note.startMs &&
                                            tapMs <= note.startMs + note.durationMs
                                    }

                                    if (existingNote != null) {
                                        viewModel.onAction(PianoRollAction.SelectNote(existingNote.id))
                                    } else {
                                        // Place new note
                                        val snapMs = if (state.isSnapEnabled) {
                                            MusicalTimeConverter.snapToBeat(
                                                tapMs,
                                                state.bpm,
                                                state.timeSignatureDenominator
                                            )
                                        } else tapMs

                                        // Default duration: 1 beat
                                        val beatMs = (60_000.0 / state.bpm).toLong()
                                        viewModel.onAction(
                                            PianoRollAction.PlaceNote(
                                                pitch = pitch.coerceIn(0, 127),
                                                startMs = snapMs.coerceAtLeast(0L),
                                                durationMs = beatMs
                                            )
                                        )
                                    }
                                },
                                onLongPress = { offset ->
                                    val pitch = TOTAL_NOTES - 1 - (offset.y / rowHeightPx).toInt()
                                    val tapMs = (offset.x / (PX_PER_MS * density.density)).toLong()

                                    val existingNote = state.notes.find { note ->
                                        note.pitch == pitch &&
                                            tapMs >= note.startMs &&
                                            tapMs <= note.startMs + note.durationMs
                                    }
                                    if (existingNote != null) {
                                        viewModel.onAction(PianoRollAction.DeleteNote(existingNote.id))
                                    }
                                }
                            )
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
                        contentMs = contentMs
                    )
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
                style = TextStyle(color = NjOnBg, fontSize = 8.sp)
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
    contentMs: Long
) {
    val totalHeight = TOTAL_NOTES * rowHeightPx
    val beatMs = 60_000.0 / bpm
    val barMs = beatMs * beatsPerBar

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
        val rowIndex = TOTAL_NOTES - 1 - note.pitch
        val y = rowIndex * rowHeightPx + 1f
        val x = note.startMs * pxPerMs
        val w = (note.durationMs * pxPerMs).coerceAtLeast(4f)
        val h = rowHeightPx - 2f

        val isSelected = note.id == selectedNoteId
        val color = if (isSelected) noteColor else noteColor.copy(alpha = 0.75f)
        val cornerRadius = CornerRadius(3f, 3f)

        drawRoundRect(
            color = color,
            topLeft = Offset(x, y),
            size = Size(w, h),
            cornerRadius = cornerRadius
        )

        // Selection border
        if (isSelected) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.5f),
                topLeft = Offset(x, y),
                size = Size(w, h),
                cornerRadius = cornerRadius,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
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
