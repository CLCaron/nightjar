package com.example.nightjar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.NjAmber
import com.example.nightjar.ui.theme.NjOutline

/**
 * Multi-segment pill rocker. Each segment is a real [NjButton] with full
 * mechanical-toggle behavior (raised body / pressed-in body / LED amber
 * glow on the active segment). Adjacent segments share a 1dp seam in the
 * outline color, and the leftmost / rightmost segments take on the pill's
 * rounded corners while inner segments are square -- so the whole row
 * reads as a single hardware control with one selected button pressed in.
 *
 * Used for small equal-weight discrete choices (grid resolution, etc.)
 * where a row of free-standing NjButtons would float apart and a custom
 * "background-tint only" segmented strip would look flat and unmechanical.
 */
@Composable
fun <T> NjSegmentedStrip(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp
) {
    if (options.isEmpty()) return
    Row(
        modifier = modifier.heightIn(min = height)
    ) {
        options.forEachIndexed { idx, option ->
            val shape: Shape = when {
                options.size == 1 -> RoundedCornerShape(6.dp)
                idx == 0 -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                idx == options.size - 1 -> RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                else -> RoundedCornerShape(0.dp)
            }
            Box(modifier = Modifier.weight(1f)) {
                NjButton(
                    text = label(option),
                    onClick = { if (option != selected) onSelect(option) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height),
                    isActive = option == selected,
                    ledColor = NjAmber,
                    shape = shape
                )
            }
            if (idx < options.size - 1) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(height)
                        .background(NjOutline)
                )
            }
        }
    }
}
