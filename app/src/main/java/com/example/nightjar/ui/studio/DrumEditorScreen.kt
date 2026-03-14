package com.example.nightjar.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.example.nightjar.ui.components.NjButton
import com.example.nightjar.ui.theme.NjBg
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjOnBg
import com.example.nightjar.ui.theme.NjOutline
import com.example.nightjar.ui.theme.NjStudioAccent
import com.example.nightjar.ui.theme.NjStudioGreen

import com.example.nightjar.ui.theme.NjSurface

/** Square cell size in dp (each cell is CELL_SIZE x CELL_SIZE). */
private const val CELL_SIZE_DP = 32f
/** Gap between cells in dp. */
private const val CELL_GAP_DP = 2f
/** Row height = cell + gap. */
private const val ROW_HEIGHT_DP = CELL_SIZE_DP + CELL_GAP_DP
/** Width of the instrument label panel in dp. */
private const val LABELS_WIDTH_DP = 48f
/** Fallback dp-per-ms when no clips exist. */
private const val DEFAULT_PX_PER_MS = 0.2f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrumEditorScreen(
    onBack: () -> Unit,
    viewModel: DrumEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val density = LocalDensity.current

    val rowHeightPx = with(density) { ROW_HEIGHT_DP.dp.toPx() }
    val cellSizePx = with(density) { CELL_SIZE_DP.dp.toPx() }
    val cellGapPx = with(density) { CELL_GAP_DP.dp.toPx() }
    val totalGridHeight = (GM_DRUM_ROWS.size * ROW_HEIGHT_DP).dp

    // Compute grid width from content
    val measureMs = MusicalTimeConverter.msPerMeasure(
        state.bpm, state.timeSignatureNumerator, state.timeSignatureDenominator
    ).toLong().coerceAtLeast(1L)
    val paddingMs = measureMs * 4
    val minContentMs = measureMs * 16
    val maxClipEndMs = state.clips.maxOfOrNull { clip ->
        val clipDurationMs = MusicalTimeConverter.msPerMeasure(
            state.bpm, state.timeSignatureNumerator, state.timeSignatureDenominator
        ) * clip.bars
        clip.offsetMs + clipDurationMs.toLong()
    } ?: 0L
    val contentMs = maxOf(maxClipEndMs + paddingMs, state.totalDurationMs + paddingMs, minContentMs)

    // Compute dp-per-ms so cells are square: step_width_dp = ROW_HEIGHT_DP
    val maxStepsPerBar = state.clips.maxOfOrNull { it.stepsPerBar } ?: 16
    val stepMs = measureMs.toDouble() / maxStepsPerBar
    val pxPerMsDp = if (stepMs > 0) ROW_HEIGHT_DP / stepMs else DEFAULT_PX_PER_MS.toDouble()
    val pxPerMs = (pxPerMsDp * density.density).toFloat()
    val gridWidthDp = (contentMs * pxPerMsDp).toFloat().dp

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NjBg)
            .statusBarsPadding()
    ) {
        // Toolbar
        TopAppBar(
            title = {
                Text(
                    text = "Drum Editor",
                    style = MaterialTheme.typography.titleSmall,
                    color = NjOnBg
                )
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
                    text = "1/${state.gridResolution}",
                    onClick = { viewModel.onAction(DrumEditorAction.CycleGridResolution) },
                    textColor = NjStudioAccent.copy(alpha = 0.8f)
                )
                Spacer(Modifier.width(4.dp))
                NjButton(
                    text = "Snap",
                    onClick = { viewModel.onAction(DrumEditorAction.ToggleSnap) },
                    isActive = state.isSnapEnabled,
                    ledColor = NjStudioAccent
                )
                Spacer(Modifier.width(4.dp))
                NjButton(
                    text = "Restart",
                    icon = Icons.Filled.SkipPrevious,
                    onClick = { viewModel.onAction(DrumEditorAction.SeekTo(0L)) },
                    textColor = NjStudioGreen.copy(alpha = 0.5f),
                )
                Spacer(Modifier.width(4.dp))
                NjButton(
                    text = "Play",
                    icon = NjIcons.PlayPause,
                    onClick = {
                        if (state.isPlaying) viewModel.onAction(DrumEditorAction.Pause)
                        else viewModel.onAction(DrumEditorAction.Play)
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

        // Beat number header row (pinned at top, scrolls horizontally with grid)
        val beatHeaderHeight = 18.dp
        Row {
            Spacer(Modifier.width(LABELS_WIDTH_DP.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(beatHeaderHeight)
                    .horizontalScroll(horizontalScrollState)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(gridWidthDp)
                        .height(beatHeaderHeight)
                ) {
                    drawBeatHeaders(
                        pxPerMs = pxPerMs,
                        bpm = state.bpm,
                        beatsPerBar = state.timeSignatureNumerator,
                        timeSignatureDenominator = state.timeSignatureDenominator,
                        contentMs = contentMs,
                        textMeasurer = textMeasurer
                    )
                }
            }
        }

        // Labels + Grid
        Row(modifier = Modifier.fillMaxSize()) {
            // Instrument labels (scrolls vertically with the grid)
            Box(
                modifier = Modifier
                    .width(LABELS_WIDTH_DP.dp)
                    .fillMaxHeight()
                    .verticalScroll(verticalScrollState)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(LABELS_WIDTH_DP.dp)
                        .height(totalGridHeight)
                ) {
                    drawDrumLabels(rowHeightPx, textMeasurer)
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
                        .pointerInput(state.clips, state.bpm, pxPerMs) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    val tapX = down.position.x
                                    val tapY = down.position.y
                                    val tapMs = (tapX / pxPerMs).toLong()
                                    val rowIndex = (tapY / rowHeightPx).toInt()
                                    if (rowIndex in GM_DRUM_ROWS.indices) {
                                        val drumNote = GM_DRUM_ROWS[rowIndex].note
                                        // Find which clip this tap falls in
                                        val clip = findClipAtMs(state.clips, tapMs, state.bpm,
                                            state.timeSignatureNumerator, state.timeSignatureDenominator)
                                        if (clip != null) {
                                            val localMs = tapMs - clip.offsetMs
                                            val clipDurationMs = MusicalTimeConverter.msPerMeasure(
                                                state.bpm, state.timeSignatureNumerator,
                                                state.timeSignatureDenominator
                                            ) * clip.bars
                                            val stepIndex = if (clipDurationMs > 0) {
                                                ((localMs.toDouble() / clipDurationMs) * clip.totalSteps)
                                                    .toInt().coerceIn(0, clip.totalSteps - 1)
                                            } else 0
                                            viewModel.onAction(
                                                DrumEditorAction.ToggleStep(
                                                    clip.clipId, clip.patternId, stepIndex, drumNote
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    drawDrumGrid(
                        rowHeightPx = rowHeightPx,
                        cellSizePx = cellSizePx,
                        cellGapPx = cellGapPx,
                        pxPerMs = pxPerMs,
                        clips = state.clips,
                        highlightClipId = state.highlightClipId,
                        bpm = state.bpm,
                        beatsPerBar = state.timeSignatureNumerator,
                        timeSignatureNumerator = state.timeSignatureNumerator,
                        timeSignatureDenominator = state.timeSignatureDenominator,
                        contentMs = contentMs,
                        positionMs = state.positionMs,
                        isPlaying = state.isPlaying
                    )
                }
            }
        }
    }
}

/** Find the clip that contains the given ms position. */
private fun findClipAtMs(
    clips: List<DrumEditorClip>,
    ms: Long,
    bpm: Double,
    numerator: Int,
    denominator: Int
): DrumEditorClip? {
    return clips.find { clip ->
        val clipDurationMs = MusicalTimeConverter.msPerMeasure(bpm, numerator, denominator) * clip.bars
        ms >= clip.offsetMs && ms < clip.offsetMs + clipDurationMs.toLong()
    }
}

/** Draw instrument labels on the left side. */
private fun DrawScope.drawDrumLabels(
    rowHeightPx: Float,
    textMeasurer: TextMeasurer
) {
    val width = size.width

    GM_DRUM_ROWS.forEachIndexed { index, row ->
        val y = index * rowHeightPx

        // Row background
        val bgColor = if (index % 2 == 0) Color(0xFF1A1520) else Color(0xFF1E1828)
        drawRect(
            color = bgColor,
            topLeft = Offset(0f, y),
            size = Size(width, rowHeightPx)
        )

        // Separator
        drawLine(
            color = NjOutline.copy(alpha = 0.3f),
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 0.5f
        )

        // Label
        val result = textMeasurer.measure(
            text = row.label,
            style = TextStyle(color = NjMuted2.copy(alpha = 0.8f), fontSize = 10.sp)
        )
        drawText(
            textLayoutResult = result,
            topLeft = Offset(
                width - result.size.width - 4f,
                y + (rowHeightPx - result.size.height) / 2f
            )
        )
    }
}

/** Draw beat number labels in the pinned header row. */
private fun DrawScope.drawBeatHeaders(
    pxPerMs: Float,
    bpm: Double,
    beatsPerBar: Int,
    timeSignatureDenominator: Int,
    contentMs: Long,
    textMeasurer: TextMeasurer
) {
    val beatMs = MusicalTimeConverter.msPerBeat(bpm, timeSignatureDenominator)
    if (beatMs <= 0.0) return

    var beatIdx = 0
    var ms = 0.0
    while (ms <= contentMs) {
        val x = (ms * pxPerMs).toFloat()
        if (x > size.width) break

        val beatInBar = (beatIdx % beatsPerBar) + 1
        val isMeasureStart = beatInBar == 1
        val labelResult = textMeasurer.measure(
            text = "$beatInBar",
            style = TextStyle(
                color = NjStudioAccent.copy(alpha = if (isMeasureStart) 0.6f else 0.35f),
                fontSize = 10.sp
            )
        )
        // Left-aligned over the downbeat column
        drawText(
            textLayoutResult = labelResult,
            topLeft = Offset(x + 3f, (size.height - labelResult.size.height) / 2f)
        )

        ms += beatMs
        beatIdx++
    }
}

/** Draw the full drum grid with all clips, beat lines, step cells, and playhead. */
private fun DrawScope.drawDrumGrid(
    rowHeightPx: Float,
    cellSizePx: Float,
    cellGapPx: Float,
    pxPerMs: Float,
    clips: List<DrumEditorClip>,
    highlightClipId: Long = 0L,
    bpm: Double,
    beatsPerBar: Int,
    timeSignatureNumerator: Int,
    timeSignatureDenominator: Int,
    contentMs: Long,
    positionMs: Long,
    isPlaying: Boolean
) {
    val totalHeight = GM_DRUM_ROWS.size * rowHeightPx
    val numRows = GM_DRUM_ROWS.size
    val beatMs = MusicalTimeConverter.msPerBeat(bpm, timeSignatureDenominator)
    val measureMs = MusicalTimeConverter.msPerMeasure(bpm, timeSignatureNumerator, timeSignatureDenominator)

    // Base background
    drawRect(color = Color(0xFF0D0B14), topLeft = Offset.Zero, size = Size(size.width, totalHeight))

    // Row separators (horizontal lines between instruments)
    for (rowIndex in 0 until numRows) {
        val y = rowIndex * rowHeightPx
        drawLine(
            color = NjOutline.copy(alpha = 0.2f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 0.5f
        )
    }

    // Beat/measure grid lines
    if (beatMs > 0.0 && measureMs > 0.0) {
        var ms = 0.0
        while (ms <= contentMs) {
            val x = (ms * pxPerMs).toFloat()
            if (x > size.width) break

            val isMeasure = (ms % measureMs).let { it < 1.0 || (measureMs - it) < 1.0 }
            val alpha = if (isMeasure) 0.35f else 0.15f
            val strokeWidth = if (isMeasure) 1.5f else 0.5f

            drawLine(
                color = NjMuted2.copy(alpha = alpha),
                start = Offset(x, 0f),
                end = Offset(x, totalHeight),
                strokeWidth = strokeWidth
            )

            ms += beatMs
        }
    }

    // Draw clip regions and step cells
    for (clip in clips) {
        val clipDurationMs = measureMs * clip.bars
        val clipStartPx = clip.offsetMs * pxPerMs
        val clipWidthPx = (clipDurationMs * pxPerMs).toFloat()
        val isHighlighted = highlightClipId != 0L && clip.clipId == highlightClipId

        // Clip boundary lines
        val borderColor = if (isHighlighted) NjStudioAccent.copy(alpha = 0.5f)
            else NjStudioAccent.copy(alpha = 0.25f)
        drawLine(
            color = borderColor,
            start = Offset(clipStartPx, 0f),
            end = Offset(clipStartPx, totalHeight),
            strokeWidth = if (isHighlighted) 2.5f else 1.5f
        )
        val clipEndPx = clipStartPx + clipWidthPx
        drawLine(
            color = if (isHighlighted) NjStudioAccent.copy(alpha = 0.35f)
                else NjStudioAccent.copy(alpha = 0.15f),
            start = Offset(clipEndPx, 0f),
            end = Offset(clipEndPx, totalHeight),
            strokeWidth = if (isHighlighted) 2.5f else 1f
        )

        // Highlight: top and bottom borders for source clip
        if (isHighlighted) {
            drawLine(
                color = NjStudioAccent.copy(alpha = 0.35f),
                start = Offset(clipStartPx, 0f),
                end = Offset(clipEndPx, 0f),
                strokeWidth = 2f
            )
            drawLine(
                color = NjStudioAccent.copy(alpha = 0.35f),
                start = Offset(clipStartPx, totalHeight),
                end = Offset(clipEndPx, totalHeight),
                strokeWidth = 2f
            )
        }

        // Build active steps set for this clip
        val activeSteps = clip.steps.map { it.stepIndex to it.drumNote }.toSet()

        // Draw step cells within the clip (square cells with gaps)
        val stepsPerBeat = if (beatsPerBar > 0) clip.stepsPerBar / beatsPerBar else 4
        val stepWidthPx = clipWidthPx / clip.totalSteps.coerceAtLeast(1)
        val halfGap = cellGapPx / 2f

        // Downbeat vs offbeat cell colors
        val downbeatCellColor = Color(0xFF211C2C) // first step of beat -- lighter
        val offbeatCellColor = Color(0xFF16131E)  // remaining steps -- darker base

        for (step in 0 until clip.totalSteps) {
            val stepInBeat = if (stepsPerBeat > 0) step % stepsPerBeat else step
            val isDownbeat = stepInBeat == 0

            for (rowIndex in 0 until numRows) {
                val drumNote = GM_DRUM_ROWS[rowIndex].note
                val isActive = (step to drumNote) in activeStepsT

                // Center the square cell within the step column and row
                val stepX = clipStartPx + step * stepWidthPx
                val x = stepX + halfGap
                val y = rowIndex * rowHeightPx + halfGap
                val w = cellSizePx
                val h = cellSizePx

                val fillColor = if (isActive) {
                    DRUM_ROW_COLORS[rowIndex].copy(alpha = 0.85f)
                } else if (isDownbeat) {
                    downbeatCellColor
                } else {
                    offbeatCellColor
                }
                val cr = CornerRadius(3f, 3f)
                val bw = 1f

                // Main fill
                drawRoundRect(color = fillColor, topLeft = Offset(x, y), size = Size(w, h), cornerRadius = cr)

                // Bevel edges
                if (isActive) {
                    // Pressed in: dark top/left, light bottom/right
                    drawLine(Color.Black.copy(alpha = 0.3f), Offset(x + bw, y + bw), Offset(x + w - bw, y + bw), bw)
                    drawLine(Color.Black.copy(alpha = 0.25f), Offset(x + bw, y + bw), Offset(x + bw, y + h - bw), bw)
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(x + bw, y + h - bw), Offset(x + w - bw, y + h - bw), bw)
                    drawLine(Color.White.copy(alpha = 0.06f), Offset(x + w - bw, y + bw), Offset(x + w - bw, y + h - bw), bw)
                } else {
                    // Raised: light top/left, dark bottom/right
                    drawLine(Color.White.copy(alpha = 0.12f), Offset(x + bw, y + bw), Offset(x + w - bw, y + bw), bw)
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(x + bw, y + bw), Offset(x + bw, y + h - bw), bw)
                    drawLine(Color.Black.copy(alpha = 0.25f), Offset(x + bw, y + h - bw), Offset(x + w - bw, y + h - bw), bw)
                    drawLine(Color.Black.copy(alpha = 0.2f), Offset(x + w - bw, y + bw), Offset(x + w - bw, y + h - bw), bw)
                }
            }
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
