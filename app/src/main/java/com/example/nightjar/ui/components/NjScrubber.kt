package com.example.nightjar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Audio playback scrubber with current/total time display.
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

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = safePos.toFloat(),
            onValueChange = { onScrub(it.toLong()) },
            onValueChangeFinished = { onScrubFinished(safePos) },
            valueRange = 0f..safeDuration.toFloat(),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                activeTickColor = MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                inactiveTickColor = MaterialTheme.colorScheme.primary.copy(alpha = 0f)
            )
        )

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
