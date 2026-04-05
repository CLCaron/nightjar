package com.example.nightjar.ui.studio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightjar.audio.MusicalTimeConverter
import com.example.nightjar.ui.theme.NjAmber
import com.example.nightjar.ui.theme.NjDrumRowColors
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjSurface
import com.example.nightjar.ui.theme.NjSurface2
import kotlinx.coroutines.launch

/** GM drum instruments shown in the pattern editor, ordered top to bottom. */
val GM_DRUM_ROWS = listOf(
    GmDrumRow(49, "Crash"),
    GmDrumRow(51, "Ride"),
    GmDrumRow(46, "OH"),
    GmDrumRow(42, "CH"),
    GmDrumRow(50, "HiTom"),
    GmDrumRow(48, "MdTom"),
    GmDrumRow(45, "LoTom"),
    GmDrumRow(39, "Clap"),
    GmDrumRow(38, "Snare"),
    GmDrumRow(36, "Kick")
)

data class GmDrumRow(val note: Int, val label: String)

private val CELL_SIZE = 28.dp
private val CELL_GAP = 2.dp
private val LABEL_WIDTH = 44.dp

/**
 * Compact inline drum pattern editor. Renders a grid of tappable cells:
 * rows = GM drum instruments, columns = steps in the pattern.
 *
 * Beat boundaries (every 4 steps) are visually grouped with subtle
 * background shading. The grid scrolls horizontally for patterns > 16 steps.
 */
@Composable
fun DrumPatternEditor(
    trackId: Long,
    pattern: DrumPatternUiState,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier,
    beatsPerBar: Int = 4,
    bpm: Double = 120.0,
    timeSignatureDenominator: Int = 4,
    globalPositionMs: Long = 0L,
    isPlaying: Boolean = false,
    viewResolution: Int = 0
) {
    // Compute stride: viewResolution controls visible columns, storage may be finer
    val viewStepsPerBar = if (viewResolution > 0 && timeSignatureDenominator > 0 && beatsPerBar > 0) {
        MusicalTimeConverter.stepsPerBar(viewResolution, beatsPerBar, timeSignatureDenominator)
    } else {
        pattern.stepsPerBar
    }
    val stride = (pattern.stepsPerBar / viewStepsPerBar).coerceAtLeast(1)
    val viewTotalSteps = viewStepsPerBar * pattern.bars
    val stepsPerBeat = if (beatsPerBar > 0) viewStepsPerBar / beatsPerBar else 4
    val activeSteps = remember(pattern.steps) {
        pattern.steps.map { (it.stepIndex to it.drumNote) }.toSet()
    }

    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    // Hoist theme colors for use in drawBehind (non-composable DrawScope)
    val drumRowColors = NjDrumRowColors
    val downbeatColor = NjSurface2  // first step of beat -- lighter, stands out
    val offbeatColor = NjSurface    // remaining steps -- darker base
    val amberColor = NjAmber

    // --- Playhead step computation ---
    val msPerBar = remember(bpm, beatsPerBar, timeSignatureDenominator) {
        MusicalTimeConverter.msPerMeasure(bpm, beatsPerBar, timeSignatureDenominator)
    }
    val currentPlayheadStep: Int? = if (
        !isPlaying || msPerBar <= 0.0 || viewTotalSteps <= 0 || viewStepsPerBar <= 0
    ) {
        null
    } else {
        val msPerViewStep = msPerBar / viewStepsPerBar
        val clipDurationMs = msPerBar * pattern.bars
        if (clipDurationMs <= 0.0 || msPerViewStep <= 0.0) null
        else {
            val clipOffsetMs = pattern.selectedClip?.offsetMs ?: 0L
            val localMs = globalPositionMs - clipOffsetMs
            // Only show sweep when the playhead is actually inside this clip
            if (localMs < 0 || localMs >= clipDurationMs.toLong()) null
            else (localMs.toDouble() / msPerViewStep).toInt().coerceIn(0, viewTotalSteps - 1)
        }
    }

    // --- Glow map with fade trail ---
    val glowMap = remember { mutableStateMapOf<Int, Animatable<Float, AnimationVector1D>>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPlayheadStep) {
        if (currentPlayheadStep != null) {
            val anim = glowMap.getOrPut(currentPlayheadStep) { Animatable(0f) }
            anim.snapTo(1f)
            val departing = glowMap.keys.filter { it != currentPlayheadStep }
            for (step in departing) {
                val a = glowMap[step] ?: continue
                scope.launch {
                    a.animateTo(0f, tween(220))
                    if (a.value == 0f) glowMap.remove(step)
                }
            }
        } else {
            val all = glowMap.keys.toList()
            for (step in all) {
                val a = glowMap[step] ?: continue
                scope.launch {
                    a.animateTo(0f, tween(220))
                    glowMap.remove(step)
                }
            }
        }
    }

    // --- Auto-scroll: continuous 60fps follow, pinned ~1/5 from left edge ---
    val currentGlobalMs = rememberUpdatedState(globalPositionMs)
    val currentIsPlaying = rememberUpdatedState(isPlaying)

    LaunchedEffect(pattern.selectedClip?.clipId, stepsPerBeat, msPerBar, pattern.bars, viewStepsPerBar) {
        if (stepsPerBeat <= 0 || msPerBar <= 0.0) return@LaunchedEffect

        val cellSizePx = with(density) { CELL_SIZE.toPx() }
        val cellGapPx = with(density) { CELL_GAP.toPx() }
        val beatGapPx = with(density) { 3.dp.toPx() }
        val beatWidthPx = stepsPerBeat * cellSizePx + (stepsPerBeat - 1) * cellGapPx
        val totalBeatsForScroll = viewTotalSteps / stepsPerBeat
        val totalContentWidth = totalBeatsForScroll * (beatWidthPx + beatGapPx) - beatGapPx
        val clipOffsetMs = pattern.selectedClip?.offsetMs ?: 0L
        val clipDurationMs = (msPerBar * pattern.bars).toLong()

        while (true) {
            delay(16L)
            if (!currentIsPlaying.value || scrollState.maxValue <= 0) continue

            val posMs = currentGlobalMs.value
            val localMs = posMs - clipOffsetMs
            if (localMs < 0 || localMs >= clipDurationMs) continue

            // Continuous sub-step position in view space for smooth scrolling
            val msPerViewStep = msPerBar / viewStepsPerBar
            val fractionalStep = localMs.toDouble() / msPerViewStep
            val beat = (fractionalStep / stepsPerBeat).toInt()
            val posInBeat = fractionalStep - beat * stepsPerBeat
            val stepX = beat * (beatWidthPx + beatGapPx) + posInBeat.toFloat() * (cellSizePx + cellGapPx)

            val viewportWidth = (totalContentWidth - scrollState.maxValue).coerceAtLeast(cellSizePx)
            val anchorOffset = viewportWidth / 5f
            val scrollTarget = (stepX - anchorOffset).toInt().coerceIn(0, scrollState.maxValue)
            scrollState.scrollTo(scrollTarget)
        }
    }

    Row(modifier = modifier.padding(vertical = 4.dp)) {
        // Instrument labels (fixed left column) with header spacer
        Column(
            modifier = Modifier.width(LABEL_WIDTH)
        ) {
            // Header spacer to align with beat numbers
            Box(
                modifier = Modifier
                    .height(16.dp)
                    .width(LABEL_WIDTH)
            )
            Spacer(Modifier.height(CELL_GAP))
            GM_DRUM_ROWS.forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(CELL_GAP))
                Box(
                    modifier = Modifier
                        .height(CELL_SIZE)
                        .width(LABEL_WIDTH),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = NjMuted2.copy(alpha = 0.75f),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
        }

        // Scrollable step grid grouped by beat
        val totalBeats = if (stepsPerBeat > 0) viewTotalSteps / stepsPerBeat else viewTotalSteps
        Row(
            modifier = Modifier.horizontalScroll(scrollState)
        ) {
            for (beatIndex in 0 until totalBeats) {
                // Beat gap between groups
                if (beatIndex > 0) {
                    Spacer(Modifier.width(3.dp))
                }

                // Each beat is a column: header + step cells
                Column(
                    horizontalAlignment = Alignment.Start
                ) {
                    // Beat number header left-aligned over the downbeat
                    val beatInBar = (beatIndex % beatsPerBar) + 1
                    Text(
                        text = "$beatInBar",
                        fontSize = 10.sp,
                        color = NjAmber.copy(alpha = 0.5f),
                        modifier = Modifier.height(16.dp).padding(start = 2.dp)
                    )
                    Spacer(Modifier.height(CELL_GAP))

                    // Steps within this beat as a row of cell columns
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(CELL_GAP)
                    ) {
                        val firstViewStep = beatIndex * stepsPerBeat
                        for (s in 0 until stepsPerBeat) {
                            val viewStep = firstViewStep + s
                            if (viewStep >= viewTotalSteps) break
                            val realStep = viewStep * stride
                            val isDownbeat = s == 0

                            Column(
                                verticalArrangement = Arrangement.spacedBy(CELL_GAP)
                            ) {
                                GM_DRUM_ROWS.forEachIndexed { rowIndex, drumRow ->
                                    val isActive = (realStep to drumRow.note) in activeSteps
                                    val instrumentColor = drumRowColors[rowIndex]
                                    val fillColor = if (isActive) {
                                        instrumentColor.copy(alpha = 0.85f)
                                    } else if (isDownbeat) {
                                        downbeatColor
                                    } else {
                                        offbeatColor
                                    }

                                    val cellGlow = glowMap[viewStep]?.value ?: 0f
                                    val firingScale = if (isActive && cellGlow > 0f) {
                                        1f + cellGlow * 0.12f
                                    } else 1f

                                    Box(
                                        modifier = Modifier
                                            .size(CELL_SIZE)
                                            .graphicsLayer {
                                                scaleX = firingScale
                                                scaleY = firingScale
                                            }
                                            .clip(RoundedCornerShape(3.dp))
                                            .drawBehind {
                                                val cr = CornerRadius(3.dp.toPx())
                                                val glow = cellGlow

                                                // Main fill (brighten active cells when firing)
                                                val baseFill = if (isActive && glow > 0f) {
                                                    instrumentColor
                                                } else {
                                                    fillColor
                                                }
                                                drawRoundRect(color = baseFill, cornerRadius = cr)

                                                // Glow overlay
                                                if (glow > 0f) {
                                                    if (isActive) {
                                                        // Amber radial bloom for firing cells
                                                        drawCircle(
                                                            brush = Brush.radialGradient(
                                                                colors = listOf(
                                                                    amberColor.copy(alpha = glow * 0.7f),
                                                                    Color.Transparent
                                                                )
                                                            ),
                                                            radius = size.maxDimension * 1.0f,
                                                            center = center
                                                        )
                                                        // White hot-spot center flash
                                                        drawCircle(
                                                            brush = Brush.radialGradient(
                                                                colors = listOf(
                                                                    Color.White.copy(alpha = glow * 0.35f),
                                                                    Color.Transparent
                                                                )
                                                            ),
                                                            radius = size.minDimension * 0.4f,
                                                            center = center
                                                        )
                                                    } else {
                                                        // Subtle amber wash on inactive cells
                                                        drawRoundRect(
                                                            color = amberColor.copy(alpha = glow * 0.18f),
                                                            cornerRadius = cr
                                                        )
                                                    }
                                                }

                                                // Bevel edges
                                                val bw = 1.dp.toPx()
                                                if (isActive) {
                                                    drawLine(Color.Black.copy(alpha = 0.3f), Offset(bw, bw), Offset(size.width - bw, bw), bw)
                                                    drawLine(Color.Black.copy(alpha = 0.25f), Offset(bw, bw), Offset(bw, size.height - bw), bw)
                                                    drawLine(Color.White.copy(alpha = 0.08f), Offset(bw, size.height - bw), Offset(size.width - bw, size.height - bw), bw)
                                                    drawLine(Color.White.copy(alpha = 0.06f), Offset(size.width - bw, bw), Offset(size.width - bw, size.height - bw), bw)
                                                } else {
                                                    drawLine(Color.White.copy(alpha = 0.12f), Offset(bw, bw), Offset(size.width - bw, bw), bw)
                                                    drawLine(Color.White.copy(alpha = 0.08f), Offset(bw, bw), Offset(bw, size.height - bw), bw)
                                                    drawLine(Color.Black.copy(alpha = 0.25f), Offset(bw, size.height - bw), Offset(size.width - bw, size.height - bw), bw)
                                                    drawLine(Color.Black.copy(alpha = 0.2f), Offset(size.width - bw, bw), Offset(size.width - bw, size.height - bw), bw)
                                                }
                                            }
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                onAction(
                                                    StudioAction.ToggleDrumStep(
                                                        trackId = trackId,
                                                        stepIndex = realStep,
                                                        drumNote = drumRow.note
                                                    )
                                                )
                                            }
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
