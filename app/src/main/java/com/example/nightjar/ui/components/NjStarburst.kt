package com.example.nightjar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjStarlight
import kotlin.math.cos
import kotlin.math.sin

/**
 * Four-pointed starburst icon — Nightjar's favorite indicator.
 *
 * Cardinal points are elongated (like a bright star seen through
 * squinted eyes), diagonal points are shorter. When [filled] is true
 * the star is solid gold with a faint radial glow. When false, just
 * a thin starlight outline.
 *
 * @param filled  True for favorited (gold, filled), false for unfavorited (outline).
 * @param size    The composable size. The star is drawn to fill this.
 */
@Composable
fun NjStarburst(
    filled: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f

        // Cardinal (N/S/E/W) points reach the edge; diagonal points are shorter.
        val outerRadius = this.size.minDimension / 2f
        val innerRadius = outerRadius * 0.32f

        // Build an 8-pointed path: 4 long cardinal + 4 short diagonal.
        val path = Path().apply {
            for (i in 0 until 8) {
                val angleDeg = -90f + i * 45f // start at top (north)
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val r = if (i % 2 == 0) outerRadius else innerRadius
                val px = cx + (r * cos(angleRad)).toFloat()
                val py = cy + (r * sin(angleRad)).toFloat()
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }

        if (filled) {
            // Soft radial glow behind the star.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NjAccent.copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = outerRadius * 1.4f
                ),
                radius = outerRadius * 1.4f,
                center = Offset(cx, cy)
            )
            drawPath(path, color = NjAccent, style = Fill)
        } else {
            drawPath(
                path,
                color = NjStarlight.copy(alpha = 0.45f),
                style = Stroke(width = 1.2.dp.toPx())
            )
        }
    }
}
