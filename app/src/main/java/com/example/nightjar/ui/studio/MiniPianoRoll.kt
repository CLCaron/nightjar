package com.example.nightjar.ui.studio

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightjar.audio.MusicalTimeConverter
import com.example.nightjar.data.db.entity.MidiNoteEntity
import com.example.nightjar.ui.components.NjButton
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjOutline
import com.example.nightjar.ui.theme.NjStudioAccent
import com.example.nightjar.ui.theme.NjStudioLane
import com.example.nightjar.ui.theme.NjTrackColors
import kotlin.math.abs

/** Row height per semitone. */
private val ROW_HEIGHT = 18.dp
/** 2 octaves visible at a time. */
private const val VISIBLE_PITCHES = 24
/** Width of the octave button column. */
private val BUTTON_COL_WIDTH = 32.dp
/** Width of key labels. */
private val KEY_LABEL_WIDTH = 36.dp

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val BLACK_KEY_INDICES = setOf(1, 3, 6, 8, 10)

/**
 * Inline piano roll editor for MIDI clips within the MidiTrackDrawer.
 *
 * Shows 2 octaves (24 semitones) at 14dp per row with NjButton octave
 * arrows and a slide animation when switching octaves.
 */
@Composable
fun MiniPianoRoll(
    clip: MidiClipUiState,
    trackId: Long,
    trackIndex: Int,
    bpm: Double,
    timeSignatureNumerator: Int,
    timeSignatureDenominator: Int,
    isSnapEnabled: Boolean,
    gridResolution: Int,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val textMeasurer = rememberTextMeasurer()

    // Current base octave (MIDI octave, where octave 4 = C4 = MIDI 60).
    // Shows 2 octaves: currentOctave and currentOctave+1.
    var currentOctave by remember(clip.clipId) { mutableIntStateOf(3) }

    // Auto-center on the octave containing notes when clip is first opened
    LaunchedEffect(clip.clipId) {
        if (clip.notes.isNotEmpty()) {
            val avgPitch = clip.notes.map { it.pitch }.average().toInt()
            // Center the 2-octave window on the average pitch
            currentOctave = (avgPitch / 12 - 2).coerceIn(0, 7)
        }
    }

    val gridHeight = ROW_HEIGHT * VISIBLE_PITCHES

    // Musical time helpers
    val msPerBeat = MusicalTimeConverter.msPerBeat(bpm, timeSignatureDenominator)
    val msPerMeasure = MusicalTimeConverter.msPerMeasure(bpm, timeSignatureNumerator, timeSignatureDenominator)
    val gridStepMs = MusicalTimeConverter.msPerGridStep(bpm, gridResolution, timeSignatureDenominator)

    // Canvas width: at least 2 measures, or fit content
    val contentEndMs = clip.notes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
    val minDurationMs = (msPerMeasure * 2).toLong()
    val totalMs = maxOf(contentEndMs + msPerMeasure.toLong(), minDurationMs)

    val trackColor = NjTrackColors[trackIndex % NjTrackColors.size]
    val rowHeightPx = with(density) { ROW_HEIGHT.toPx() }
    val pxPerMs = with(density) { 0.15f * this.density }
    val canvasWidthPx = (totalMs * pxPerMs).coerceAtLeast(with(density) { 200.dp.toPx() })
    val canvasWidthDp = with(density) { (canvasWidthPx / this.density).dp }

    // Gesture state (shared across octave transitions)
    var selectedNoteId by remember { mutableStateOf<Long?>(null) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }
    var lastTapNoteId by remember { mutableStateOf<Long?>(null) }

    // Notes outside visible range (for button indicators)
    val lowPitch = (currentOctave + 1) * 12
    val highPitch = lowPitch + VISIBLE_PITCHES - 1
    val notesAbove = clip.notes.count { it.pitch > highPitch }
    val notesBelow = clip.notes.count { it.pitch < lowPitch }

    Row(modifier = modifier.fillMaxWidth().height(gridHeight)) {
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
                textColor = if (notesAbove > 0) NjStudioAccent else NjMuted2
            )
            NjButton(
                text = "",
                icon = Icons.Filled.KeyboardArrowDown,
                onClick = { currentOctave = (currentOctave - 1).coerceAtLeast(0) },
                textColor = if (notesBelow > 0) NjStudioAccent else NjMuted2
            )
        }

        // Animated grid area: key labels + note grid slide together
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
        ) {
            AnimatedContent(
                targetState = currentOctave,
                transitionSpec = {
                    if (targetState > initialState) {
                        // Going up: new content slides in from top
                        slideInVertically { -it } togetherWith slideOutVertically { it }
                    } else {
                        // Going down: new content slides in from bottom
                        slideInVertically { it } togetherWith slideOutVertically { -it }
                    }
                },
                label = "octave_slide"
            ) { octave ->
                val low = (octave + 1) * 12
                val high = low + VISIBLE_PITCHES - 1
                val pitchRange = low..high

                val hScrollState = rememberScrollState()

                Row(Modifier.fillMaxWidth().height(gridHeight)) {
                    // Key labels
                    Canvas(
                        modifier = Modifier
                            .width(KEY_LABEL_WIDTH)
                            .fillMaxHeight()
                    ) {
                        val labelStyle = TextStyle(fontSize = 8.sp, color = Color.White.copy(alpha = 0.6f))
                        for (i in 0 until VISIBLE_PITCHES) {
                            val pitch = high - i
                            val y = i * rowHeightPx
                            val noteIndex = pitch % 12
                            val noteName = NOTE_NAMES[noteIndex]
                            val isBlack = noteIndex in BLACK_KEY_INDICES

                            if (isBlack) {
                                drawRect(
                                    color = Color.White.copy(alpha = 0.04f),
                                    topLeft = Offset(0f, y),
                                    size = Size(size.width, rowHeightPx)
                                )
                            }

                            val label = if (noteName == "C") "C${pitch / 12 - 1}" else noteName
                            val result = textMeasurer.measure(label, labelStyle)
                            drawText(
                                textLayoutResult = result,
                                topLeft = Offset(
                                    (size.width - result.size.width) / 2f,
                                    y + (rowHeightPx - result.size.height) / 2f
                                )
                            )

                            drawLine(
                                color = NjOutline.copy(alpha = 0.3f),
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 0.5f
                            )
                        }
                    }

                    // Horizontally scrollable grid + notes
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(hScrollState)
                    ) {
                        Canvas(
                            modifier = Modifier
                                .width(canvasWidthDp)
                                .height(gridHeight)
                                .background(NjStudioLane.copy(alpha = 0.5f))
                                .pointerInput(clip, pitchRange, pxPerMs, rowHeightPx, isSnapEnabled, gridStepMs, msPerBeat) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val downPos = down.position
                                        val downTime = System.currentTimeMillis()
                                        val scrollAtDown = hScrollState.value

                                        val hitNote = hitTestNote(
                                            downPos.x, downPos.y, clip.notes,
                                            pxPerMs, rowHeightPx, pitchRange
                                        )
                                        val hitEdge = if (hitNote != null) {
                                            isNearRightEdge(downPos.x, hitNote, pxPerMs)
                                        } else false

                                        // Consume down on note hits so horizontal scroll
                                        // can't steal resize/move drags
                                        if (hitNote != null) {
                                            down.consume()
                                        }

                                        val isDoubleTap = hitNote != null &&
                                            hitNote.id == lastTapNoteId &&
                                            (downTime - lastTapTimeMs) < 400

                                        if (isDoubleTap && hitNote != null) {
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            onAction(StudioAction.InlineDeleteNote(trackId, hitNote.id))
                                            lastTapNoteId = null
                                            lastTapTimeMs = 0
                                            return@awaitEachGesture
                                        }

                                        var moved = false
                                        var lastPos = downPos
                                        val touchSlop = viewConfiguration.touchSlop

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                            if (!change.pressed) {
                                                // Check if scroll moved (canvas shifts under
                                                // the finger so pointer coords don't change)
                                                val scrolled = hScrollState.value != scrollAtDown

                                                change.consume()
                                                if (!moved && !scrolled) {
                                                    if (hitNote != null) {
                                                        selectedNoteId = hitNote.id
                                                    } else {
                                                        val tappedMs = (downPos.x / pxPerMs).toLong()
                                                        val tappedPitch = high - (downPos.y / rowHeightPx).toInt()
                                                        val snappedMs = if (isSnapEnabled && gridStepMs > 0) {
                                                            MusicalTimeConverter.snapToGrid(tappedMs, bpm, gridResolution, timeSignatureDenominator)
                                                        } else tappedMs
                                                        val noteDuration = if (gridStepMs > 0) gridStepMs.toLong() else msPerBeat.toLong()
                                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                        onAction(
                                                            StudioAction.InlinePlaceNote(
                                                                trackId, clip.clipId,
                                                                tappedPitch.coerceIn(pitchRange),
                                                                snappedMs.coerceAtLeast(0),
                                                                noteDuration.coerceAtLeast(50)
                                                            )
                                                        )
                                                    }
                                                    lastTapNoteId = hitNote?.id
                                                    lastTapTimeMs = downTime
                                                }
                                                break
                                            }

                                            val delta = change.position - lastPos
                                            if (!moved && (abs(delta.x) > touchSlop || abs(delta.y) > touchSlop)) {
                                                moved = true
                                                if (hitNote != null) {
                                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                }
                                            }

                                            if (moved && hitNote != null) {
                                                change.consume()
                                                lastPos = change.position

                                                if (hitEdge) {
                                                    val newEndMs = (change.position.x / pxPerMs).toLong()
                                                    val newDuration = (newEndMs - hitNote.startMs).coerceAtLeast(50)
                                                    val snappedDuration = if (isSnapEnabled && gridStepMs > 0) {
                                                        val snappedEnd = MusicalTimeConverter.snapToGrid(
                                                            hitNote.startMs + newDuration, bpm, gridResolution, timeSignatureDenominator
                                                        )
                                                        (snappedEnd - hitNote.startMs).coerceAtLeast(gridStepMs.toLong().coerceAtLeast(50))
                                                    } else newDuration
                                                    onAction(StudioAction.InlineResizeNote(trackId, hitNote.id, snappedDuration))
                                                } else {
                                                    val newMs = (change.position.x / pxPerMs - hitNote.durationMs / 2f / 1f).toLong()
                                                    val newPitch = high - (change.position.y / rowHeightPx).toInt()
                                                    val snappedMs = if (isSnapEnabled && gridStepMs > 0) {
                                                        MusicalTimeConverter.snapToGrid(newMs, bpm, gridResolution, timeSignatureDenominator)
                                                    } else newMs
                                                    onAction(
                                                        StudioAction.InlineMoveNote(
                                                            trackId, hitNote.id,
                                                            snappedMs.coerceAtLeast(0),
                                                            newPitch.coerceIn(pitchRange)
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                        ) {
                            drawMiniRollGrid(
                                highPitch = high,
                                rowHeightPx = rowHeightPx,
                                totalMs = totalMs,
                                pxPerMs = pxPerMs,
                                msPerBeat = msPerBeat,
                                msPerMeasure = msPerMeasure,
                                gridStepMs = gridStepMs,
                                isSnapEnabled = isSnapEnabled
                            )

                            for (note in clip.notes) {
                                val pitchIndex = high - note.pitch
                                if (pitchIndex < 0 || pitchIndex >= VISIBLE_PITCHES) continue

                                val x = note.startMs * pxPerMs
                                val y = pitchIndex * rowHeightPx
                                val w = (note.durationMs * pxPerMs).coerceAtLeast(6f)

                                val isSelected = note.id == selectedNoteId
                                val noteColor = if (isSelected) trackColor else trackColor.copy(alpha = 0.7f)

                                drawRoundRect(
                                    color = noteColor,
                                    topLeft = Offset(x, y + 1f),
                                    size = Size(w, rowHeightPx - 2f),
                                    cornerRadius = CornerRadius(3f, 3f)
                                )

                                if (isSelected) {
                                    drawRoundRect(
                                        color = NjStudioAccent,
                                        topLeft = Offset(x, y + 1f),
                                        size = Size(w, rowHeightPx - 2f),
                                        cornerRadius = CornerRadius(3f, 3f),
                                        style = Stroke(width = 1.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawMiniRollGrid(
    highPitch: Int,
    rowHeightPx: Float,
    totalMs: Long,
    pxPerMs: Float,
    msPerBeat: Double,
    msPerMeasure: Double,
    gridStepMs: Double,
    isSnapEnabled: Boolean
) {
    val gridColor = NjOutline.copy(alpha = 0.15f)
    val beatColor = NjOutline.copy(alpha = 0.25f)
    val measureColor = NjOutline.copy(alpha = 0.4f)

    for (i in 0 until VISIBLE_PITCHES) {
        val pitch = highPitch - i
        val y = i * rowHeightPx
        val isBlack = (pitch % 12) in BLACK_KEY_INDICES

        if (isBlack) {
            drawRect(
                color = Color.White.copy(alpha = 0.03f),
                topLeft = Offset(0f, y),
                size = Size(size.width, rowHeightPx)
            )
        }

        // Stronger line at C notes (octave boundaries)
        val isC = (pitch % 12) == 0
        drawLine(
            color = if (isC) NjOutline.copy(alpha = 0.5f) else gridColor,
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

            val color = when {
                isMeasure -> measureColor
                isBeat -> beatColor
                else -> gridColor
            }
            val width = if (isMeasure) 1.5f else 0.5f

            drawLine(
                color = color,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = width
            )

            ms += if (isSnapEnabled && gridStepMs > 0) gridStepMs else msPerBeat
        }
    }
}

private fun hitTestNote(
    x: Float,
    y: Float,
    notes: List<MidiNoteEntity>,
    pxPerMs: Float,
    rowHeightPx: Float,
    pitchRange: IntRange
): MidiNoteEntity? {
    for (note in notes) {
        val pitchIndex = pitchRange.last - note.pitch
        if (pitchIndex < 0 || pitchIndex >= VISIBLE_PITCHES) continue

        val noteX = note.startMs * pxPerMs
        val noteY = pitchIndex * rowHeightPx
        val noteW = (note.durationMs * pxPerMs).coerceAtLeast(12f)

        if (x >= noteX && x <= noteX + noteW &&
            y >= noteY && y <= noteY + rowHeightPx
        ) {
            return note
        }
    }
    return null
}

private fun isNearRightEdge(
    x: Float,
    note: MidiNoteEntity,
    pxPerMs: Float
): Boolean {
    val noteStart = note.startMs * pxPerMs
    val noteEnd = (note.startMs + note.durationMs) * pxPerMs
    val midpoint = (noteStart + noteEnd) / 2f
    // Right half of the note = resize zone
    return x >= midpoint && x <= noteEnd + 12f
}
