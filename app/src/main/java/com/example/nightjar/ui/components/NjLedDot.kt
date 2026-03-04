package com.example.nightjar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjMuted2

/**
 * Small LED status dot -- lit (filled + glow) or unlit (outline ring).
 *
 * Replaces NjStarburst as the favorite/status indicator across the app.
 * Lit state shows a filled circle with a subtle radial glow like a
 * hardware status LED. Unlit shows a faint ring (empty LED housing).
 */
@Composable
fun NjLedDot(
    isLit: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    litColor: Color = NjAccent,
    unlitColor: Color = NjMuted2
) {
    Canvas(modifier = modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = this.size.minDimension / 2f

        if (isLit) {
            // Subtle radial glow behind the dot
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        litColor.copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * 2.2f
                ),
                radius = radius * 2.2f,
                center = center
            )
            // Filled LED
            drawCircle(
                color = litColor,
                radius = radius,
                center = center
            )
        } else {
            // Empty LED housing -- faint outline ring
            drawCircle(
                color = unlitColor.copy(alpha = 0.5f),
                radius = radius - 0.5.dp.toPx(),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}
