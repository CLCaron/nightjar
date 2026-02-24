package com.example.nightjar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
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
 * @param onScrub        Called during a horizontal drag with the fraction (0–1). Null disables scrubbing.
 * @param onScrubFinished Called when the drag ends with the final fraction (0–1). Null disables scrubbing.
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
    playheadColor: Color = NjAccent,
    onScrub: ((Float) -> Unit)? = null,
    onScrubFinished: ((Float) -> Unit)? = null
) {
    var amplitudes by remember(audioFile.absolutePath) {
        mutableStateOf<FloatArray?>(null)
    }

    LaunchedEffect(audioFile.absolutePath) {
        amplitudes = extractWaveform(audioFile, WAVEFORM_SAMPLE_COUNT)
    }

    NjWaveformContent(
        amps = amplitudes,
        modifier = modifier,
        height = height,
        barColor = barColor,
        minBarFraction = minBarFraction,
        startFraction = startFraction,
        endFraction = endFraction,
        progressFraction = progressFraction,
        playheadColor = playheadColor,
        onScrub = onScrub,
        onScrubFinished = onScrubFinished
    )
}

/**
 * Renders a pre-computed waveform from [amplitudes]. Use this overload when
 * amplitudes are composited from multiple tracks by the ViewModel rather
 * than extracted from a single file.
 */
@Composable
fun NjWaveform(
    amplitudes: FloatArray,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    barColor: Color = NjStarlight.copy(alpha = 0.65f),
    minBarFraction: Float = 0.05f,
    startFraction: Float = 0f,
    endFraction: Float = 1f,
    progressFraction: Float = -1f,
    playheadColor: Color = NjAccent,
    onScrub: ((Float) -> Unit)? = null,
    onScrubFinished: ((Float) -> Unit)? = null
) {
    NjWaveformContent(
        amps = amplitudes,
        modifier = modifier,
        height = height,
        barColor = barColor,
        minBarFraction = minBarFraction,
        startFraction = startFraction,
        endFraction = endFraction,
        progressFraction = progressFraction,
        playheadColor = playheadColor,
        onScrub = onScrub,
        onScrubFinished = onScrubFinished
    )
}

/**
 * Shared rendering logic for both the file-based and pre-computed overloads.
 */
@Composable
private fun NjWaveformContent(
    amps: FloatArray?,
    modifier: Modifier,
    height: Dp,
    barColor: Color,
    minBarFraction: Float,
    startFraction: Float,
    endFraction: Float,
    progressFraction: Float,
    playheadColor: Color,
    onScrub: ((Float) -> Unit)?,
    onScrubFinished: ((Float) -> Unit)?
) {
    var lastScrubFraction by remember { mutableFloatStateOf(0f) }

    val scrubModifier = if (onScrub != null || onScrubFinished != null) {
        Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    val w = size.width.toFloat()
                    if (w > 0f) {
                        val frac = (offset.x / w).coerceIn(0f, 1f)
                        lastScrubFraction = frac
                        onScrub?.invoke(frac)
                    }
                },
                onHorizontalDrag = { change, _ ->
                    change.consume()
                    val w = size.width.toFloat()
                    if (w > 0f) {
                        val frac = (change.position.x / w).coerceIn(0f, 1f)
                        lastScrubFraction = frac
                        onScrub?.invoke(frac)
                    }
                },
                onDragEnd = { onScrubFinished?.invoke(lastScrubFraction) },
                onDragCancel = { onScrubFinished?.invoke(lastScrubFraction) }
            )
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .height(height)
            .then(scrubModifier)
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
