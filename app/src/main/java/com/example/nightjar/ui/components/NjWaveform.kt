package com.example.nightjar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.nightjar.audio.extractWaveform
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjStarlight
import java.io.File

/**
 * Loads waveform data from [audioFile] off the main thread and renders a
 * continuous filled waveform (mirrored envelope around the centre line).
 *
 * The waveform fills its entire available width edge-to-edge so that the
 * shape aligns exactly with the timeline ruler and playhead.
 *
 * @param audioFile      The audio file to extract waveform from.
 * @param modifier       Layout modifier (should supply width; height defaults to [height]).
 * @param height         The composable height.
 * @param barColor       Fill colour for the waveform body.
 * @param minBarFraction Minimum amplitude as a fraction of height (so silent
 *                       sections still show a thin line).
 * @param startFraction  Start of the visible region as a fraction of total audio (0–1).
 * @param endFraction    End of the visible region as a fraction of total audio (0–1).
 */
@Composable
fun NjWaveform(
    audioFile: File,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    barColor: Color = NjStarlight.copy(alpha = 0.65f),
    minBarFraction: Float = 0.05f,
    startFraction: Float = 0f,
    endFraction: Float = 1f,
    progressFraction: Float = -1f,
    playheadColor: Color = NjAccent
) {
    var amplitudes by remember(audioFile.absolutePath) {
        mutableStateOf<FloatArray?>(null)
    }

    LaunchedEffect(audioFile.absolutePath) {
        amplitudes = extractWaveform(audioFile, WAVEFORM_SAMPLE_COUNT)
    }

    val amps = amplitudes

    Canvas(
        modifier = modifier
            .height(height)
    ) {
        if (amps == null || amps.isEmpty()) return@Canvas

        val canvasW = size.width
        val canvasH = size.height
        val centerY = canvasH / 2f

        // Determine the sub-range of the amplitude array to render.
        val rangeStart = (startFraction * amps.size).toInt().coerceIn(0, amps.size)
        val rangeEnd = (endFraction * amps.size).toInt().coerceIn(rangeStart, amps.size)
        val rangeLen = rangeEnd - rangeStart
        if (rangeLen <= 0) return@Canvas

        val columns = canvasW.toInt().coerceAtLeast(1)

        // Build a mirrored envelope path — top edge left-to-right, then
        // bottom edge right-to-left — and fill it in one draw call.
        val path = Path()

        // Top edge
        for (x in 0..columns) {
            val frac = x.toFloat() / columns
            val ampIdx = (rangeStart + (frac * rangeLen).toInt()).coerceIn(0, amps.lastIndex)
            val amp = amps[ampIdx].coerceIn(minBarFraction, 1f)
            val y = centerY - amp * centerY
            if (x == 0) path.moveTo(0f, y) else path.lineTo(x.toFloat(), y)
        }

        // Bottom edge (mirrored, right to left)
        for (x in columns downTo 0) {
            val frac = x.toFloat() / columns
            val ampIdx = (rangeStart + (frac * rangeLen).toInt()).coerceIn(0, amps.lastIndex)
            val amp = amps[ampIdx].coerceIn(minBarFraction, 1f)
            val y = centerY + amp * centerY
            path.lineTo(x.toFloat(), y)
        }

        path.close()
        drawPath(path, color = barColor)

        // Draw playhead
        if (progressFraction in 0f..1f && canvasW > 0f) {
            val playheadX = canvasW * progressFraction
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

/** Number of amplitude samples to extract. Higher = more detail at wide zoom. */
private const val WAVEFORM_SAMPLE_COUNT = 1000
