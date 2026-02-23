package com.example.nightjar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjStarlight

/**
 * Custom-drawn audio scrubber — thin track line with a small gold thumb dot.
 *
 * Reports drag position via [onScrub] during the gesture and the final
 * committed position via [onScrubFinished] on release.
 */
@Composable
fun NjScrubber(
    positionMs: Long,
    durationMs: Long,
    onScrub: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
    showTime: Boolean = true
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val safePos = positionMs.coerceIn(0L, safeDuration)
    val fraction = safePos.toFloat() / safeDuration.toFloat()

    val activeColor = NjAccent.copy(alpha = 0.6f)
    val inactiveColor = NjStarlight.copy(alpha = 0.15f)
    val thumbColor = NjAccent

    var canvasWidth by remember { mutableFloatStateOf(0f) }
    var lastScrubMs by remember { mutableLongStateOf(0L) }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(safeDuration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            canvasWidth = size.width.toFloat()
                            if (canvasWidth > 0f) {
                                val frac = (offset.x / canvasWidth).coerceIn(0f, 1f)
                                val posMs = (frac * safeDuration).toLong()
                                lastScrubMs = posMs
                                onScrub(posMs)
                            }
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            if (canvasWidth > 0f) {
                                val frac = (change.position.x / canvasWidth).coerceIn(0f, 1f)
                                val posMs = (frac * safeDuration).toLong()
                                lastScrubMs = posMs
                                onScrub(posMs)
                            }
                        },
                        onDragEnd = {
                            onScrubFinished(lastScrubMs)
                        },
                        onDragCancel = {
                            onScrubFinished(lastScrubMs)
                        }
                    )
                }
        ) {
            canvasWidth = size.width
            val centerY = size.height / 2f
            val trackHeight = 2.dp.toPx()
            val thumbRadius = 5.dp.toPx()
            val thumbX = size.width * fraction

            // Inactive track (full width)
            drawLine(
                color = inactiveColor,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = trackHeight
            )

            // Active track (up to thumb)
            if (thumbX > 0f) {
                drawLine(
                    color = activeColor,
                    start = Offset(0f, centerY),
                    end = Offset(thumbX, centerY),
                    strokeWidth = trackHeight
                )
            }

            // Thumb dot
            drawCircle(
                color = thumbColor,
                radius = thumbRadius,
                center = Offset(thumbX, centerY)
            )
        }

        if (showTime) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatMs(safePos),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                Text(
                    formatMs(safeDuration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
