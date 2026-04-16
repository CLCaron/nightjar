package com.example.nightjar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.NjAmber
import com.example.nightjar.ui.theme.NjOutline

enum class PillrockerHalfMode { Momentary, Latching }

/**
 * Configuration for one half of an [NjPillrocker].
 *
 * - [mode] = [PillrockerHalfMode.Momentary]: press-and-release; isLatched
 *   is ignored visually.
 * - [mode] = [PillrockerHalfMode.Latching]: visual latch reflects [isLatched],
 *   with the mechanical "pressed-in" LED feel of NjButton toggle mode.
 *
 * [isEnabled] = false renders a dimmed, inert half (no tap). Used for the
 * Confirm half of Split when no valid split line is placed.
 */
data class PillrockerHalf(
    val label: String = "",
    val icon: ImageVector? = null,
    val mode: PillrockerHalfMode = PillrockerHalfMode.Momentary,
    val isLatched: Boolean = false,
    val isEnabled: Boolean = true,
    val ledColor: Color? = null,
    val onTap: () -> Unit = {}
)

/**
 * Two-half hardware-style pillrocker. Left and right halves are independent
 * push zones sharing a single pill silhouette with a 1dp seam between them.
 *
 * Used for:
 *  - Split pillrocker: left = Latching "Split" (mode toggle), right = Momentary
 *    "Confirm" (disabled when no valid split line is placed).
 *  - Duplicate pillrocker: both halves Momentary — left = Unlinked, right = Linked.
 */
@Composable
fun NjPillrocker(
    left: PillrockerHalf,
    right: PillrockerHalf,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 44.dp
) {
    val leftShape: Shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
    val rightShape: Shape = RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
    Row(
        modifier = modifier.heightIn(min = height)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            PillrockerHalfButton(half = left, shape = leftShape)
        }
        // 1dp seam rendered as a thin outline-colored divider.
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxWidth()
                .height(height)
                .background(NjOutline)
        )
        Box(modifier = Modifier.weight(1f)) {
            PillrockerHalfButton(half = right, shape = rightShape)
        }
    }
}

@Composable
private fun PillrockerHalfButton(half: PillrockerHalf, shape: Shape) {
    val dimAlpha = if (half.isEnabled) 1f else 0.45f
    NjButton(
        text = half.label,
        onClick = { if (half.isEnabled) half.onTap() },
        modifier = Modifier.fillMaxWidth().alpha(dimAlpha),
        icon = half.icon,
        isActive = when (half.mode) {
            PillrockerHalfMode.Momentary -> false
            PillrockerHalfMode.Latching -> half.isLatched
        },
        activeAccent = NjAmber,
        ledColor = half.ledColor,
        shape = shape
    )
}
