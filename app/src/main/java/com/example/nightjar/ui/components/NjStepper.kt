package com.example.nightjar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightjar.ui.theme.IbmPlexMono
import com.example.nightjar.ui.theme.NjAmber
import com.example.nightjar.ui.theme.NjMuted
import com.example.nightjar.ui.theme.NjPanelInset

/**
 * Hardware-style stepper: two pressable arrow buttons flanking a two-line
 * LCD readout. The LCD shows a small caption label on top (e.g. "CHORD",
 * "BANK A · 008") and a large value below (e.g. "TRIAD", "JAZZ ORGAN").
 *
 * Cycles through the [options] list; wraps cyclically at both ends so a
 * tap on the right arrow past the last item returns to the first.
 *
 * Used for small-set value picking where a knob would feel sluggish but
 * a chip strip would take too much horizontal space (3-128 items).
 */
@Composable
fun <T> NjStepper(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: String,
    valueText: (T) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp
) {
    fun stepBy(direction: Int) {
        if (options.isEmpty()) return
        val idx = options.indexOf(selected).coerceAtLeast(0)
        val n = options.size
        val nextIdx = ((idx + direction) % n + n) % n
        if (nextIdx != idx) onSelect(options[nextIdx])
    }

    Row(
        modifier = modifier.height(height),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NjButton(
            text = "",
            icon = Icons.Filled.KeyboardArrowLeft,
            onClick = { stepBy(-1) },
            modifier = Modifier
                .fillMaxHeight()
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(NjPanelInset)
                .drawWithContent {
                    drawContent()
                    val sw = 1.dp.toPx()
                    drawLine(
                        Color.Black.copy(alpha = 0.45f),
                        Offset(0f, sw / 2),
                        Offset(size.width, sw / 2),
                        sw * 1.5f
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.25f),
                        Offset(sw / 2, 0f),
                        Offset(sw / 2, size.height),
                        sw
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.05f),
                        Offset(0f, size.height - sw / 2),
                        Offset(size.width, size.height - sw / 2),
                        sw
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.03f),
                        Offset(size.width - sw / 2, 0f),
                        Offset(size.width - sw / 2, size.height),
                        sw
                    )
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    fontFamily = IbmPlexMono,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    color = NjMuted,
                    letterSpacing = 0.7.sp,
                    maxLines = 1
                )
                Text(
                    text = valueText(selected),
                    fontFamily = IbmPlexMono,
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                    color = NjAmber.copy(alpha = 0.9f),
                    letterSpacing = 0.5.sp,
                    maxLines = 1
                )
            }
        }
        NjButton(
            text = "",
            icon = Icons.Filled.KeyboardArrowRight,
            onClick = { stepBy(+1) },
            modifier = Modifier
                .fillMaxHeight()
        )
    }
}
