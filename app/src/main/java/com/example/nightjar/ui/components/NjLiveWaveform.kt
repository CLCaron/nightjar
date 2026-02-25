package com.example.nightjar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.NjStarlight

/**
 * Real-time waveform that grows from left to right while recording.
 *
 * Separate from [NjWaveform] because the behaviour is fundamentally different:
 * the waveform starts empty, grows rightward as amplitudes arrive, and
 * downsamples via peak-per-bucket once [maxBars] is exceeded.
 *
 * @param amplitudes     Live amplitude samples (0–1), appended each tick.
 * @param modifier       Layout modifier — should supply width.
 * @param height         Composable height.
 * @param barColor       Fill colour for the waveform body.
 * @param minBarFraction Minimum amplitude fraction so silence still shows a thin line.
 * @param maxBars        Once [amplitudes] exceeds this, downsample to fit full width.
 */
@Composable
fun NjLiveWaveform(
    amplitudes: FloatArray,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    barColor: Color = NjStarlight.copy(alpha = 0.65f),
    minBarFraction: Float = 0.05f,
    maxBars: Int = 200
) {
    Canvas(
        modifier = modifier.height(height)
    ) {
        if (amplitudes.isEmpty()) return@Canvas

        val canvasW = size.width
        val canvasH = size.height
        val centerY = canvasH / 2f

        val count = amplitudes.size
        val growing = count < maxBars

        // How many columns to render and how wide the filled region is
        val columns: Int
        val filledWidth: Float
        if (growing) {
            // Waveform fills proportional width, growing rightward
            columns = count
            filledWidth = canvasW * (count.toFloat() / maxBars)
        } else {
            // Downsampled to fit full width
            columns = maxBars
            filledWidth = canvasW
        }

        val path = Path()

        // Top edge — left to right
        for (x in 0 until columns) {
            val amp = sampleAmplitude(amplitudes, x, columns, count)
                .coerceIn(minBarFraction, 1f)
            val px = filledWidth * x / (columns - 1).coerceAtLeast(1)
            val y = centerY - amp * centerY
            if (x == 0) path.moveTo(px, y) else path.lineTo(px, y)
        }

        // Bottom edge — right to left (mirrored envelope)
        for (x in columns - 1 downTo 0) {
            val amp = sampleAmplitude(amplitudes, x, columns, count)
                .coerceIn(minBarFraction, 1f)
            val px = filledWidth * x / (columns - 1).coerceAtLeast(1)
            val y = centerY + amp * centerY
            path.lineTo(px, y)
        }

        path.close()
        drawPath(path, color = barColor)

        // Leading-edge glow — subtle vertical gradient line at the rightmost point
        val edgeX = filledWidth
        val glowWidth = 6.dp.toPx()
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, barColor.copy(alpha = 0.35f)),
                startX = (edgeX - glowWidth).coerceAtLeast(0f),
                endX = edgeX
            ),
            topLeft = Offset((edgeX - glowWidth).coerceAtLeast(0f), 0f),
            size = androidx.compose.ui.geometry.Size(glowWidth, canvasH)
        )
    }
}

/**
 * Maps a column index to an amplitude value. When [count] <= [columns]
 * (growing phase) each column maps 1:1. When downsampling, each column
 * covers a bucket of source samples and we take the peak.
 */
private fun sampleAmplitude(
    amplitudes: FloatArray,
    columnIndex: Int,
    columns: Int,
    count: Int
): Float {
    if (count <= columns) {
        return amplitudes[columnIndex.coerceIn(0, count - 1)]
    }
    // Downsampling: peak per bucket
    val bucketStart = (columnIndex.toLong() * count / columns).toInt()
    val bucketEnd = (((columnIndex + 1).toLong() * count) / columns).toInt()
        .coerceAtMost(count)
    var peak = 0f
    for (i in bucketStart until bucketEnd) {
        if (amplitudes[i] > peak) peak = amplitudes[i]
    }
    return peak
}
