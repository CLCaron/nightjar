package com.example.nightjar.ui.studio

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightjar.ui.theme.NjDrumRowColors
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjAmber
import com.example.nightjar.ui.theme.NjSurface
import com.example.nightjar.ui.theme.NjSurface2

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
    beatsPerBar: Int = 4
) {
    val totalSteps = pattern.totalSteps
    val stepsPerBeat = if (beatsPerBar > 0) pattern.stepsPerBar / beatsPerBar else 4
    val activeSteps = remember(pattern.steps) {
        pattern.steps.map { (it.stepIndex to it.drumNote) }.toSet()
    }

    val scrollState = rememberScrollState()

    // Hoist theme colors for use in drawBehind (non-composable DrawScope)
    val drumRowColors = NjDrumRowColors
    val downbeatColor = NjSurface2  // first step of beat -- lighter, stands out
    val offbeatColor = NjSurface    // remaining steps -- darker base

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
        val totalBeats = if (stepsPerBeat > 0) totalSteps / stepsPerBeat else totalSteps
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
                        val firstStep = beatIndex * stepsPerBeat
                        for (s in 0 until stepsPerBeat) {
                            val step = firstStep + s
                            if (step >= totalSteps) break
                            val isDownbeat = s == 0

                            Column(
                                verticalArrangement = Arrangement.spacedBy(CELL_GAP)
                            ) {
                                GM_DRUM_ROWS.forEachIndexed { rowIndex, drumRow ->
                                    val isActive = (step to drumRow.note) in activeSteps
                                    val fillColor = if (isActive) {
                                        drumRowColors[rowIndex].copy(alpha = 0.85f)
                                    } else if (isDownbeat) {
                                        downbeatColor
                                    } else {
                                        offbeatColor
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(CELL_SIZE)
                                            .clip(RoundedCornerShape(3.dp))
                                            .drawBehind {
                                                val cr = CornerRadius(3.dp.toPx())
                                                // Main fill
                                                drawRoundRect(color = fillColor, cornerRadius = cr)
                                                // Bevel edges
                                                val bw = 1.dp.toPx()
                                                if (isActive) {
                                                    // Pressed in: dark top/left, light bottom/right
                                                    drawLine(Color.Black.copy(alpha = 0.3f), Offset(bw, bw), Offset(size.width - bw, bw), bw)
                                                    drawLine(Color.Black.copy(alpha = 0.25f), Offset(bw, bw), Offset(bw, size.height - bw), bw)
                                                    drawLine(Color.White.copy(alpha = 0.08f), Offset(bw, size.height - bw), Offset(size.width - bw, size.height - bw), bw)
                                                    drawLine(Color.White.copy(alpha = 0.06f), Offset(size.width - bw, bw), Offset(size.width - bw, size.height - bw), bw)
                                                } else {
                                                    // Raised: light top/left, dark bottom/right
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
                                                        stepIndex = step,
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
