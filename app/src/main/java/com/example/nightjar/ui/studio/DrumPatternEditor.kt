package com.example.nightjar.ui.studio

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjOutline
import com.example.nightjar.ui.theme.NjStudioLane

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

// Per-instrument accent colors for active step cells (internal for timeline mini-grid reuse)
val DRUM_ROW_COLORS = listOf(
    Color(0xFFCCB35A),  // Crash  -- warm yellow
    Color(0xFF5EA8A3),  // Ride   -- teal
    Color(0xFFBE7B4A),  // OH     -- amber
    Color(0xFFBE7B4A),  // CH     -- amber
    Color(0xFF6A9E8F),  // HiTom  -- sage
    Color(0xFF6A9E8F),  // MdTom  -- sage
    Color(0xFF6A9E8F),  // LoTom  -- sage
    Color(0xFF8B7EC8),  // Clap   -- lavender
    Color(0xFFC48560),  // Snare  -- coral
    Color(0xFFCB6B6B)   // Kick   -- brick
)

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

    Row(modifier = modifier.padding(vertical = 4.dp)) {
        // Instrument labels (fixed left column)
        Column(
            modifier = Modifier.width(LABEL_WIDTH),
            verticalArrangement = Arrangement.spacedBy(CELL_GAP)
        ) {
            GM_DRUM_ROWS.forEachIndexed { _, row ->
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

        // Scrollable step grid
        Row(
            modifier = Modifier.horizontalScroll(scrollState)
        ) {
            for (step in 0 until totalSteps) {
                val isBeatStart = stepsPerBeat > 0 && step % stepsPerBeat == 0
                val isBarStart = step % pattern.stepsPerBar == 0

                Column(
                    verticalArrangement = Arrangement.spacedBy(CELL_GAP)
                ) {
                    GM_DRUM_ROWS.forEachIndexed { rowIndex, drumRow ->
                        val isActive = (step to drumRow.note) in activeSteps
                        val cellColor = if (isActive) {
                            DRUM_ROW_COLORS[rowIndex].copy(alpha = 0.8f)
                        } else {
                            val bgAlpha = when {
                                isBarStart -> 0.18f
                                isBeatStart -> 0.12f
                                else -> 0.07f
                            }
                            NjStudioLane.copy(alpha = bgAlpha)
                        }

                        Box(
                            modifier = Modifier
                                .size(CELL_SIZE)
                                .padding(start = if (isBeatStart && step > 0) 2.dp else 0.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .border(
                                    width = 0.5.dp,
                                    color = NjOutline.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(3.dp)
                                )
                                .background(cellColor)
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
