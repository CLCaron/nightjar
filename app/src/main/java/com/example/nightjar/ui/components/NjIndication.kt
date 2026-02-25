package com.example.nightjar.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.NjPrimary
import com.example.nightjar.ui.theme.NjStarlight
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Star-touch indication ───────────────────────────────────────────────

/**
 * Soft radial glow at the press point — replaces Material ripple.
 * Press expands ~250 ms, release fades ~350 ms.
 * Provide as [LocalIndication] in the theme.
 */
object NjStarTouchIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): Modifier.Node =
        StarTouchNode(interactionSource)

    override fun hashCode(): Int = -1
    override fun equals(other: Any?) = other === this
}

private class StarTouchNode(
    private val interactionSource: InteractionSource
) : Modifier.Node(), DrawModifierNode {

    private var touchCenter = Offset.Zero
    private var radiusProgress = 0f
    private var alphaProgress = 0f
    private var radiusJob: Job? = null
    private var alphaJob: Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        touchCenter = interaction.pressPosition
                        radiusJob?.cancel()
                        alphaJob?.cancel()
                        radiusProgress = 0f
                        radiusJob = coroutineScope.launch {
                            animate(
                                0f, 1f,
                                animationSpec = tween(250, easing = FastOutSlowInEasing)
                            ) { v, _ ->
                                radiusProgress = v
                                invalidateDraw()
                            }
                        }
                        alphaJob = coroutineScope.launch {
                            animate(
                                alphaProgress, 1f,
                                animationSpec = tween(200)
                            ) { v, _ ->
                                alphaProgress = v
                                invalidateDraw()
                            }
                        }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        radiusJob?.cancel()
                        alphaJob?.cancel()
                        alphaJob = coroutineScope.launch {
                            animate(
                                alphaProgress, 0f,
                                animationSpec = tween(250)
                            ) { v, _ ->
                                alphaProgress = v
                                invalidateDraw()
                            }
                            radiusProgress = 0f
                        }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (alphaProgress > 0.001f) {
            val radius = 64.dp.toPx() * radiusProgress
            if (radius > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NjPrimary.copy(alpha = 0.08f * alphaProgress),
                            Color.Transparent
                        ),
                        center = touchCenter,
                        radius = radius.coerceAtLeast(0.01f)
                    ),
                    radius = radius,
                    center = touchCenter
                )
            }
        }
    }
}

// ── Minimum-duration pressed state ──────────────────────────────────────

/**
 * Like [androidx.compose.foundation.interaction.collectIsPressedAsState]
 * but guarantees the pressed visual stays for at least [minVisibleMs]
 * after the finger lifts. Without this, a quick tap (~80 ms) can
 * complete before Compose even renders the pressed frame.
 */
@Composable
fun InteractionSource.collectIsPressedWithMinDuration(
    minVisibleMs: Long = 100L
): State<Boolean> {
    val isPressed = remember { mutableStateOf(false) }

    LaunchedEffect(this) {
        var pressTimeNanos = 0L
        var releaseJob: Job? = null

        interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    releaseJob?.cancel()
                    releaseJob = null
                    pressTimeNanos = System.nanoTime()
                    isPressed.value = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    val elapsedMs = (System.nanoTime() - pressTimeNanos) / 1_000_000
                    val remaining = minVisibleMs - elapsedMs
                    if (remaining > 0) {
                        releaseJob = launch {
                            delay(remaining)
                            isPressed.value = false
                        }
                    } else {
                        isPressed.value = false
                    }
                }
            }
        }
    }

    return isPressed
}

// ── Bevel modifier ──────────────────────────────────────────────────────

/**
 * Raised bevel — bright top/left, dark bottom/right.
 * When [isPressed] is true the bevel inverts and inset shadow
 * gradients appear from the top and left edges, making the
 * surface look physically pushed in like a hardware key.
 */
fun Modifier.njBevel(
    isPressed: Boolean = false,
    highlight: Color = NjStarlight.copy(alpha = 0.30f),
    shadow: Color = Color.Black.copy(alpha = 0.50f)
): Modifier = drawWithContent {
    drawContent()

    val s = 1.dp.toPx()

    if (isPressed) {
        // Inset shadow gradients — light blocked by the rim above and left
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.22f),
                0.30f to Color.Transparent
            )
        )
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.15f),
                0.25f to Color.Transparent
            )
        )
    }

    // Bevel edge lines
    val top = if (isPressed) shadow else highlight
    val bot = if (isPressed) highlight else shadow
    // Top + left
    drawLine(top, Offset(0f, s / 2), Offset(size.width, s / 2), s)
    drawLine(top, Offset(s / 2, 0f), Offset(s / 2, size.height), s)
    // Bottom + right
    drawLine(bot, Offset(0f, size.height - s / 2), Offset(size.width, size.height - s / 2), s)
    drawLine(bot, Offset(size.width - s / 2, 0f), Offset(size.width - s / 2, size.height), s)
}
