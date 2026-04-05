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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import com.example.nightjar.ui.theme.NjAmber
import com.example.nightjar.ui.theme.NjDrumRowColors
import com.example.nightjar.ui.theme.NjLane
import com.example.nightjar.ui.theme.NjLedGreen
import com.example.nightjar.ui.theme.NjPanelInset
import com.example.nightjar.ui.theme.NjSurface
import com.example.nightjar.ui.theme.NjSurface2

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

    // Compute dp-per-ms so cells are square at the view resolution
    val viewStepsPerBar = MusicalTimeConverter.stepsPerBar(
        state.viewResolution, state.timeSignatureNumerator, state.timeSignatureDenominator
    )
    val stepMs = measureMs.toDouble() / viewStepsPerBar
    val pxPerMsDp = if (stepMs > 0) ROW_HEIGHT_DP / stepMs else DEFAULT_PX_PER_MS.toDouble()
    val pxPerMs = (pxPerMsDp * density.density).toFloat()
    val gridWidthDp = (contentMs * pxPerMsDp).toFloat().dp

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()

    // Hoist theme colors for use in non-composable DrawScope functions
    val drumOutline = NjOutline
    val drumMuted2 = NjMuted2
    val drumAmber = NjAmber
    val drumRowColors = NjDrumRowColors
    val panelInset = NjPanelInset
    val surfaceColor = NjSurface
    val surface2Color = NjSurface2
    val laneColor = NjLane

    // Auto-scroll: 60fps follow, pinned ~1/5 from left edge
    val currentPositionMs = rememberUpdatedState(state.positionMs)
    val currentIsPlaying = rememberUpdatedState(state.isPlaying)
    val totalContentWidthPx = with(density) { gridWidthDp.toPx() }

    LaunchedEffect(pxPerMs) {
        while (true) {
            delay(16L)
            if (!currentIsPlaying.value) continue
            val maxScroll = horizontalScrollState.maxValue
            if (maxScroll <= 0) continue
            if (horizontalScrollState.isScrollInProgress) continue

            val playheadPx = currentPositionMs.value * pxPerMs
            // viewportWidth = totalContent - maxScroll
            val viewportWidth = (totalContentWidthPx - maxScroll).coerceAtLeast(1f)
            val scrollTarget = (playheadPx - viewportWidth / 5f).toInt()
                .coerceIn(0, maxScroll)
            horizontalScrollState.scrollTo(scrollTarget)
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
                    text = "1/${state.viewResolution}",
                    onClick = { viewModel.onAction(DrumEditorAction.CycleGridResolution) },
                    textColor = NjAmber.copy(alpha = 0.8f)
                )
                Spacer(Modifier.width(4.dp))
                NjButton(
                    text = "Snap",
                    onClick = { viewModel.onAction(DrumEditorAction.ToggleSnap) },
                    isActive = state.isSnapEnabled,
                    ledColor = NjAmber
                )
                Spacer(Modifier.width(4.dp))
                NjButton(
                    text = "Restart",
                    icon = Icons.Filled.SkipPrevious,
                    onClick = { viewModel.onAction(DrumEditorAction.SeekTo(0L)) },
                    textColor = NjLedGreen.copy(alpha = 0.5f),
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
                    ledColor = NjLedGreen
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
                        textMeasurer = textMeasurer,
                        amberColor = drumAmber
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
                    drawDrumLabels(rowHeightPx, textMeasurer, drumOutline, drumMuted2, surfaceColor, laneColor)
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
                        .pointerInput(state.clips, state.bpm, pxPerMs, viewStepsPerBar) {
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
                                            // Map tap to view column, then multiply by stride for real step
                                            val clipViewSteps = viewStepsPerBar * clip.bars
                                            val viewCol = if (clipDurationMs > 0) {
                                                ((localMs.toDouble() / clipDurationMs) * clipViewSteps)
                                                    .toInt().coerceIn(0, clipViewSteps - 1)
                                            } else 0
                                            val stride = (clip.stepsPerBar / viewStepsPerBar).coerceAtLeast(1)
                                            val stepIndex = viewCol * stride
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
                        viewStepsPerBar = viewStepsPerBar,
                        contentMs = contentMs,
                        positionMs = state.positionMs,
                        isPlaying = state.isPlaying,
                        outlineColor = drumOutline,
                        gridLineColor = drumMuted2,
                        amberColor = drumAmber,
                        drumRowColors = drumRowColors,
                        canvasBgColor = panelInset,
                        downbeatCellColor = surface2Color,
                        offbeatCellColor = surfaceColor
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
    textMeasurer: TextMeasurer,
    outlineColor: Color,
    labelColor: Color,
    surfaceColor: Color,
    laneColor: Color
) {
    val width = size.width

    GM_DRUM_ROWS.forEachIndexed { index, row ->
        val y = index * rowHeightPx

        // Row background
        val bgColor = if (index % 2 == 0) laneColor else surfaceColor
        drawRect(
            color = bgColor,
            topLeft = Offset(0f, y),
            size = Size(width, rowHeightPx)
        )

        // Separator
        drawLine(
            color = outlineColor.copy(alpha = 0.3f),
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 0.5f
        )

        // Label
        val result = textMeasurer.measure(
            text = row.label,
            style = TextStyle(color = labelColor.copy(alpha = 0.8f), fontSize = 10.sp)
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
    textMeasurer: TextMeasurer,
    amberColor: Color
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
                color = amberColor.copy(alpha = if (isMeasureStart) 0.6f else 0.35f),
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
    viewStepsPerBar: Int,
    contentMs: Long,
    positionMs: Long,
    isPlaying: Boolean,
    outlineColor: Color,
    gridLineColor: Color,
    amberColor: Color,
    drumRowColors: List<Color>,
    canvasBgColor: Color,
    downbeatCellColor: Color,
    offbeatCellColor: Color
) {
    val totalHeight = GM_DRUM_ROWS.size * rowHeightPx
    val numRows = GM_DRUM_ROWS.size
    val beatMs = MusicalTimeConverter.msPerBeat(bpm, timeSignatureDenominator)
    val measureMs = MusicalTimeConverter.msPerMeasure(bpm, timeSignatureNumerator, timeSignatureDenominator)

    // Base background
    drawRect(color = canvasBgColor, topLeft = Offset.Zero, size = Size(size.width, totalHeight))

    // Row separators (horizontal lines between instruments)
    for (rowIndex in 0 until numRows) {
        val y = rowIndex * rowHeightPx
        drawLine(
            color = outlineColor.copy(alpha = 0.2f),
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
                color = gridLineColor.copy(alpha = alpha),
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
        val borderColor = if (isHighlighted) amberColor.copy(alpha = 0.5f)
            else amberColor.copy(alpha = 0.25f)
        drawLine(
            color = borderColor,
            start = Offset(clipStartPx, 0f),
            end = Offset(clipStartPx, totalHeight),
            strokeWidth = if (isHighlighted) 2.5f else 1.5f
        )
        val clipEndPx = clipStartPx + clipWidthPx
        drawLine(
            color = if (isHighlighted) amberColor.copy(alpha = 0.35f)
                else amberColor.copy(alpha = 0.15f),
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

        // Build active steps set for this clip
        val activeSteps = clip.steps.map { it.stepIndex to it.drumNote }.toSet()

        // Stride-based view: show every Nth step
        val stride = (clip.stepsPerBar / viewStepsPerBar).coerceAtLeast(1)
        val viewTotalSteps = viewStepsPerBar * clip.bars

        // Compute per-clip playhead position for glow (in view-column space)
        val msPerViewStep = if (viewTotalSteps > 0) clipDurationMs / viewTotalSteps else 0.0
        val localMs = positionMs - clip.offsetMs
        val playheadInClip = isPlaying && localMs >= 0 && localMs < clipDurationMs.toLong()
        val currentStepFraction = if (playheadInClip && msPerViewStep > 0.0) {
            localMs.toDouble() / msPerViewStep
        } else -1.0

        // Draw step cells within the clip (square cells with gaps)
        val viewStepsPerBeat = if (beatsPerBar > 0) viewStepsPerBar / beatsPerBar else 4
        val stepWidthPx = clipWidthPx / viewTotalSteps.coerceAtLeast(1)
        val halfGap = cellGapPx / 2f

        for (displayCol in 0 until viewTotalSteps) {
            val realStep = displayCol * stride
            val stepInBeat = if (viewStepsPerBeat > 0) displayCol % viewStepsPerBeat else displayCol
            val isDownbeat = stepInBeat == 0

            // Compute glow intensity for this display column
            val cellGlow = if (currentStepFraction >= 0.0) {
                val dist = currentStepFraction - displayCol.toDouble()
                when {
                    dist >= 0.0 && dist < 1.0 -> 1f          // Current step: full glow
                    dist >= 1.0 && dist < 2.5 -> (1f - ((dist - 1.0) / 1.5).toFloat()).coerceAtLeast(0f) // Trail: fade out
                    else -> 0f
                }
            } else 0f

            for (rowIndex in 0 until numRows) {
                val drumNote = GM_DRUM_ROWS[rowIndex].note
                val isActive = (realStep to drumNote) in activeSteps

                // Center the square cell within the display column and row
                val stepX = clipStartPx + displayCol * stepWidthPx
                val baseX = stepX + halfGap
                val baseY = rowIndex * rowHeightPx + halfGap
                val w = cellSizePx
                val h = cellSizePx

                // Scale punch for active cells under the playhead
                val scaleFactor = if (isActive && cellGlow > 0f) 1f + cellGlow * 0.12f else 1f
                val scaledW = w * scaleFactor
                val scaledH = h * scaleFactor
                val x = baseX - (scaledW - w) / 2f
                val y = baseY - (scaledH - h) / 2f

                val fillColor = if (isActive) {
                    if (cellGlow > 0f) drumRowColors[rowIndex] // Full brightness when firing
                    else drumRowColors[rowIndex].copy(alpha = 0.85f)
                } else if (isDownbeat) {
                    downbeatCellColor
                } else {
                    offbeatCellColor
                }
                val cr = CornerRadius(3.dp.toPx())
                val bw = 1.dp.toPx()

                // Main fill
                drawRoundRect(color = fillColor, topLeft = Offset(x, y), size = Size(scaledW, scaledH), cornerRadius = cr)

                // Bevel edges
                if (isActive) {
                    drawLine(Color.Black.copy(alpha = 0.3f), Offset(x + bw, y + bw), Offset(x + scaledW - bw, y + bw), bw)
                    drawLine(Color.Black.copy(alpha = 0.25f), Offset(x + bw, y + bw), Offset(x + bw, y + scaledH - bw), bw)
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(x + bw, y + scaledH - bw), Offset(x + scaledW - bw, y + scaledH - bw), bw)
                    drawLine(Color.White.copy(alpha = 0.06f), Offset(x + scaledW - bw, y + bw), Offset(x + scaledW - bw, y + scaledH - bw), bw)
                } else {
                    drawLine(Color.White.copy(alpha = 0.12f), Offset(x + bw, y + bw), Offset(x + scaledW - bw, y + bw), bw)
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(x + bw, y + bw), Offset(x + bw, y + scaledH - bw), bw)
                    drawLine(Color.Black.copy(alpha = 0.25f), Offset(x + bw, y + scaledH - bw), Offset(x + scaledW - bw, y + scaledH - bw), bw)
                    drawLine(Color.Black.copy(alpha = 0.2f), Offset(x + scaledW - bw, y + bw), Offset(x + scaledW - bw, y + scaledH - bw), bw)
                }

                // Glow overlay
                if (cellGlow > 0f) {
                    val cx = x + scaledW / 2f
                    val cy = y + scaledH / 2f
                    if (isActive) {
                        // Amber radial bloom
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    amberColor.copy(alpha = cellGlow * 0.7f),
                                    Color.Transparent
                                ),
                                center = Offset(cx, cy),
                                radius = scaledW * 1.0f
                            ),
                            radius = scaledW * 1.0f,
                            center = Offset(cx, cy)
                        )
                        // White hot-spot center flash
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = cellGlow * 0.35f),
                                    Color.Transparent
                                ),
                                center = Offset(cx, cy),
                                radius = scaledW * 0.4f
                            ),
                            radius = scaledW * 0.4f,
                            center = Offset(cx, cy)
                        )
                    } else {
                        // Subtle amber wash on inactive cells
                        drawRoundRect(
                            color = amberColor.copy(alpha = cellGlow * 0.18f),
                            topLeft = Offset(x, y),
                            size = Size(scaledW, scaledH),
                            cornerRadius = cr
                        )
                    }
                }
            }
        }
    }

    // Playhead removed -- sweep glow on cells is the only playback indicator
}
