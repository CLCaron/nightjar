package com.example.nightjar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.nightjar.audio.extractWaveform
import java.io.File

/**
 * Loads waveform data from [audioFile] off the main thread and renders it
 * as a horizontal bar waveform on a [Canvas].
 *
 * @param audioFile  The audio file to extract waveform from.
 * @param modifier   Layout modifier (should supply width; height defaults to [height]).
 * @param height     The composable height.
 * @param barColor   Color of the waveform bars.
 * @param bars       Number of bars to render.
 * @param barWidthDp Width of each bar.
 * @param gapDp      Gap between bars.
 * @param minBarFraction Minimum bar height as a fraction of total height (so silent
 *                       sections still show a thin line).
 */
@Composable
fun NjWaveform(
    audioFile: File,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    barColor: Color = MaterialTheme.colorScheme.primary,
    bars: Int = 120,
    barWidthDp: Dp = 2.dp,
    gapDp: Dp = 1.dp,
    minBarFraction: Float = 0.05f,
    progressFraction: Float = -1f,
    playheadColor: Color = Color.White
) {
    var amplitudes by remember(audioFile.absolutePath) {
        mutableStateOf<FloatArray?>(null)
    }

    LaunchedEffect(audioFile.absolutePath) {
        amplitudes = extractWaveform(audioFile, bars)
    }

    val amps = amplitudes

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (amps == null || amps.isEmpty()) return@Canvas

        val canvasW = size.width
        val canvasH = size.height

        val barWidth = barWidthDp.toPx()
        val gap = gapDp.toPx()
        val step = barWidth + gap

        // How many bars actually fit in the available width.
        val visibleBars = ((canvasW + gap) / step).toInt().coerceAtMost(amps.size)
        if (visibleBars <= 0) return@Canvas

        // Re-sample if the number of decoded bars differs from visible count.
        val totalWidth = visibleBars * step - gap
        val startX = (canvasW - totalWidth) / 2f  // center the waveform

        val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)

        for (i in 0 until visibleBars) {
            // Map visible bar index back to amplitude array.
            val ampIndex = (i.toLong() * amps.size / visibleBars).toInt()
                .coerceIn(0, amps.lastIndex)
            val amp = amps[ampIndex].coerceIn(minBarFraction, 1f)

            val barH = amp * canvasH
            val x = startX + i * step
            val y = (canvasH - barH) / 2f  // vertically centered

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barH),
                cornerRadius = cornerRadius
            )
        }

        // Draw playhead
        if (progressFraction in 0f..1f && totalWidth > 0f) {
            val playheadX = startX + totalWidth * progressFraction
            val strokeWidth = 2.dp.toPx()
            drawLine(
                color = playheadColor,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, canvasH),
                strokeWidth = strokeWidth
            )
        }
    }
}
