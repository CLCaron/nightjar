package com.example.nightjar.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.components.collectIsPressedWithMinDuration
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjStudioAccent

// Pressed body — slightly darker than NjStudioSurface2 (0xFF1C1824),
// so active toggles look recessed into the surface.
private val PressedBodyColor = Color(0xFF12101A)

/**
 * Hardware-style button for Studio actions.
 *
 * Two visual modes controlled by [ledColor]:
 *
 * **Toggle (ledColor set):** Matches the Solo/Mute [DrawerToggleButton] DNA.
 * When [isActive], shows a dark recessed body with the text "lighting up"
 * via a soft LED glow — frosted plastic aesthetic. When inactive, a standard
 * raised muted surface.
 *
 * **Momentary (ledColor null):** Rectangular push button with tactile press
 * feedback. [isActive] tints the body with [activeAccent]. Used for Clear,
 * Delete, and other one-shot or stateful momentary actions.
 *
 * @param ledColor  When non-null, enables toggle mode with this LED color.
 * @param shape     Corner shape — override for pill-pair grouping.
 */
@Composable
fun NjStudioButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    activeAccent: Color = NjStudioAccent,
    textColor: Color? = null,
    ledColor: Color? = null,
    shape: Shape = RoundedCornerShape(4.dp)
) {
    if (ledColor != null) {
        ToggleModeButton(text, onClick, modifier, isActive, ledColor, shape)
    } else {
        MomentaryModeButton(text, onClick, modifier, isActive, activeAccent, textColor, shape)
    }
}

/** Toggle mode — DrawerToggleButton-style pressed-in/raised visual with LED glow. */
@Composable
private fun ToggleModeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    isActive: Boolean,
    ledColor: Color,
    shape: Shape
) {
    val bgColor = if (isActive) PressedBodyColor else NjMuted2.copy(alpha = 0.12f)
    val fgColor = if (isActive) ledColor else NjMuted2

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .drawWithContent {
                val sw = 1.dp.toPx()

                // LED glow behind text when active — concentrated around the
                // text center, fades before reaching the button edges.
                if (isActive) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to ledColor.copy(alpha = 0.20f),
                                0.4f to ledColor.copy(alpha = 0.06f),
                                1.0f to Color.Transparent
                            ),
                            center = Offset(size.width / 2, size.height / 2),
                            radius = size.width * 0.45f
                        )
                    )
                }

                drawContent()

                // Bevel edges
                if (isActive) {
                    // Pressed in: dark top + left = inner shadow
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
                } else {
                    // Raised: light top, dark bottom
                    drawLine(
                        Color.White.copy(alpha = 0.07f),
                        Offset(0f, sw / 2),
                        Offset(size.width, sw / 2),
                        sw
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.35f),
                        Offset(0f, size.height - sw / 2),
                        Offset(size.width, size.height - sw / 2),
                        sw
                    )
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = fgColor
        )
    }
}

/** Momentary mode — tactile push button with press feedback. */
@Composable
private fun MomentaryModeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    isActive: Boolean,
    activeAccent: Color,
    textColor: Color?,
    shape: Shape
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedWithMinDuration()

    val bgColor = when {
        isActive -> activeAccent.copy(alpha = 0.15f)
        else -> NjMuted2.copy(alpha = 0.12f)
    }

    val fgColor = textColor ?: when {
        isActive -> activeAccent
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .drawWithContent {
                drawContent()

                val sw = 1.dp.toPx()

                if (isPressed) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.18f),
                            0.35f to Color.Transparent
                        )
                    )
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.12f),
                            0.25f to Color.Transparent
                        )
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.40f),
                        Offset(0f, sw / 2),
                        Offset(size.width, sw / 2),
                        sw * 1.5f
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.20f),
                        Offset(sw / 2, 0f),
                        Offset(sw / 2, size.height),
                        sw
                    )
                } else {
                    drawLine(
                        Color.White.copy(alpha = 0.07f),
                        Offset(0f, sw / 2),
                        Offset(size.width, sw / 2),
                        sw
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.35f),
                        Offset(0f, size.height - sw / 2),
                        Offset(size.width, size.height - sw / 2),
                        sw
                    )
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = fgColor
        )
    }
}
