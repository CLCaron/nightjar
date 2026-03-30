package com.example.nightjar.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.LocalNjColors
import com.example.nightjar.ui.theme.NjAmber

// Pressed-in body -- reads from active palette.
internal val PressedBodyColor: Color
    @Composable @ReadOnlyComposable get() = LocalNjColors.current.pressedBody

// Raised body -- reads from active palette.
internal val RaisedBodyColor: Color
    @Composable @ReadOnlyComposable get() = LocalNjColors.current.raisedBody

/**
 * Hardware-style button used across the entire app.
 *
 * Two visual modes controlled by [ledColor]:
 *
 * **Toggle (ledColor set):** When [isActive], shows a dark recessed body
 * with the text "lighting up" via a soft LED glow -- frosted plastic
 * aesthetic. When inactive, a standard raised muted surface.
 *
 * **Momentary (ledColor null):** Rectangular push button with tactile press
 * feedback. [isActive] tints the body with [activeAccent]. Used for Clear,
 * Delete, and other one-shot or stateful momentary actions.
 *
 * @param icon        Optional Material icon. When set, renders an icon with neon
 *                    glow instead of text. Use with empty [text].
 * @param ledColor    When non-null, enables toggle mode with this LED color.
 * @param activeGlow  When true (default), shows full LED glow when pressed in.
 *                    Set false for action buttons (e.g. Clear) that use toggle
 *                    visuals for the rocker effect but aren't status indicators.
 * @param shape       Corner shape -- override for pill-pair grouping.
 */
@Composable
fun NjButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isActive: Boolean = false,
    activeAccent: Color = NjAmber,
    textColor: Color? = null,
    ledColor: Color? = null,
    activeGlow: Boolean = true,
    ledScale: Float = 1f,
    shape: Shape = RoundedCornerShape(2.dp)
) {
    if (ledColor != null) {
        ToggleModeButton(text, onClick, modifier, icon, isActive, ledColor, activeGlow, ledScale, shape, textColor)
    } else {
        MomentaryModeButton(text, onClick, modifier, icon, isActive, activeAccent, textColor, shape)
    }
}

/** Toggle mode -- three-state mechanical latching visual with LED glow. */
@Composable
private fun ToggleModeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    isActive: Boolean,
    ledColor: Color,
    activeGlow: Boolean,
    ledScale: Float,
    shape: Shape,
    inactiveTextColor: Color? = null
) {
    val toggleState = rememberMechanicalToggleState(isActive)
    val depth by toggleState.depth
    val view = LocalView.current

    // Depth-based body color: 0.0 raised -> 0.5 latched -> 1.0 deep press
    val bgColor = when {
        depth > 0.5f -> lerp(PressedBodyColor, DeepPressColor, (depth - 0.5f) * 2f)
        else -> lerp(RaisedBodyColor, PressedBodyColor, depth * 2f)
    }
    val visuallyActive = toggleState.isVisuallyActive
    val fgColor = if (visuallyActive) ledColor else (inactiveTextColor ?: ledColor.copy(alpha = 0.5f))

    // Haptics -- fire on raw press/release events.
    LaunchedEffect(toggleState.interactionSource) {
        toggleState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                is PressInteraction.Release ->
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    Box(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(bgColor)
            .njGrain(alpha = 0.04f)
            .drawWithContent {
                drawContent()

                val sw = 1.dp.toPx()

                if (depth > 0.25f) {
                    // Pressed in: dark top + left (inner shadow),
                    // subtle light bottom + right (rim catch)
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
                        Color.White.copy(alpha = 0.06f),
                        Offset(0f, size.height - sw / 2),
                        Offset(size.width, size.height - sw / 2),
                        sw
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.04f),
                        Offset(size.width - sw / 2, 0f),
                        Offset(size.width - sw / 2, size.height),
                        sw
                    )
                } else {
                    // Raised: light top + left, dark bottom + right
                    drawLine(
                        Color.White.copy(alpha = 0.09f),
                        Offset(0f, sw / 2),
                        Offset(size.width, sw / 2),
                        sw
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.05f),
                        Offset(sw / 2, 0f),
                        Offset(sw / 2, size.height),
                        sw
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.35f),
                        Offset(0f, size.height - sw / 2),
                        Offset(size.width, size.height - sw / 2),
                        sw
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.18f),
                        Offset(size.width - sw / 2, 0f),
                        Offset(size.width - sw / 2, size.height),
                        sw
                    )
                }
            }
            .clickable(
                interactionSource = toggleState.interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = if (icon != null) 8.dp else 14.dp,
                vertical = 8.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        val scaleModifier = if (ledScale != 1f) {
            Modifier.graphicsLayer(scaleX = ledScale, scaleY = ledScale)
        } else Modifier

        if (icon != null) {
            // Neon glow: radial gradient behind the icon
            val glowColor = if (visuallyActive && activeGlow) ledColor else Color.Transparent
            Icon(
                imageVector = icon,
                contentDescription = text.ifEmpty { null },
                tint = fgColor,
                modifier = scaleModifier
                    .size(20.dp)
                    .drawBehind {
                        if (glowColor != Color.Transparent) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        glowColor.copy(alpha = 0.18f),
                                        Color.Transparent
                                    ),
                                    center = center,
                                    radius = 14.dp.toPx()
                                ),
                                radius = 14.dp.toPx(),
                                center = center
                            )
                        }
                    }
            )
        } else {
            // Per-letter glow via text shadow -- backlit lettering effect.
            val glowShadow = when {
                visuallyActive && activeGlow -> Shadow(
                    color = ledColor.copy(alpha = 0.8f),
                    offset = Offset.Zero,
                    blurRadius = 8f
                )
                !visuallyActive -> Shadow(
                    color = Color.White.copy(alpha = 0.35f),
                    offset = Offset.Zero,
                    blurRadius = 6f
                )
                else -> null
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.let { base ->
                    if (glowShadow != null) base.copy(shadow = glowShadow) else base
                },
                color = fgColor,
                modifier = scaleModifier
            )
        }
    }
}

/** Momentary mode -- tactile push button with press feedback. */
@Composable
private fun MomentaryModeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    isActive: Boolean,
    activeAccent: Color,
    textColor: Color?,
    shape: Shape
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedWithMinDuration()
    val view = LocalView.current

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                is PressInteraction.Release ->
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    val bgColor = when {
        isPressed -> PressedBodyColor
        isActive -> activeAccent.copy(alpha = 0.15f)
        else -> RaisedBodyColor
    }

    val fgColor = textColor ?: when {
        isActive -> activeAccent
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    }

    Box(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(bgColor)
            .njGrain(alpha = 0.04f)
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
                    // Dark top + left (inner shadow)
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
                    // Subtle light bottom + right (rim catch)
                    drawLine(
                        Color.White.copy(alpha = 0.06f),
                        Offset(0f, size.height - sw / 2),
                        Offset(size.width, size.height - sw / 2),
                        sw
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.04f),
                        Offset(size.width - sw / 2, 0f),
                        Offset(size.width - sw / 2, size.height),
                        sw
                    )
                } else {
                    // Light top + left, dark bottom + right
                    drawLine(
                        Color.White.copy(alpha = 0.09f),
                        Offset(0f, sw / 2),
                        Offset(size.width, sw / 2),
                        sw
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.05f),
                        Offset(sw / 2, 0f),
                        Offset(sw / 2, size.height),
                        sw
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.35f),
                        Offset(0f, size.height - sw / 2),
                        Offset(size.width, size.height - sw / 2),
                        sw
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.18f),
                        Offset(size.width - sw / 2, 0f),
                        Offset(size.width - sw / 2, size.height),
                        sw
                    )
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = if (icon != null) 8.dp else 14.dp,
                vertical = 8.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            // Neon glow: radial gradient behind the icon -- only when active
            val glowColor = if (isActive) (textColor ?: activeAccent) else Color.Transparent
            Icon(
                imageVector = icon,
                contentDescription = text.ifEmpty { null },
                tint = fgColor,
                modifier = Modifier
                    .size(20.dp)
                    .drawBehind {
                        if (glowColor != Color.Transparent) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        glowColor.copy(alpha = 0.18f),
                                        Color.Transparent
                                    ),
                                    center = center,
                                    radius = 14.dp.toPx()
                                ),
                                radius = 14.dp.toPx(),
                                center = center
                            )
                        }
                    }
            )
        } else {
            val glowShadow = textColor?.let {
                Shadow(
                    color = it.copy(alpha = 0.8f),
                    offset = Offset.Zero,
                    blurRadius = 8f
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.let { base ->
                    if (glowShadow != null) base.copy(shadow = glowShadow) else base
                },
                color = fgColor
            )
        }
    }
}
