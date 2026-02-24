package com.example.nightjar.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.NjStardust
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private enum class StarShape { CIRCLE, RAYS, STARBURST }

private data class Star(
    val x: Float,
    val y: Float,
    val radiusDp: Float,
    val alpha: Float,
    val twinkles: Boolean,
    val anchor: Boolean,
    val phase: Float,
    val speed: Float,
    val color: Color,
    val shape: StarShape
)

/** Stars per dp-squared — yields ~1,200 on a Fold 4 cover, ~1,950 unfolded. */
private const val DENSITY_FACTOR = 0.0039f

/** Baseline count used to scale minimum distance proportionally. */
private const val BASE_COUNT = 1200f

/** Minimum distance (normalized 0–1) between stars at the baseline count. */
private const val BASE_MIN_DIST = 0.015f

/**
 * Pre-computed unit-circle vertices for the 8-point starburst shape.
 * 4 cardinal points at distance 1.0, 4 diagonal points at 0.32.
 * Starting at north (-90°), stepping 45° each.
 */
private val STARBURST_UNIT_VERTICES: List<Pair<Float, Float>> = buildList {
    for (i in 0 until 8) {
        val angleDeg = -90f + i * 45f
        val angleRad = Math.toRadians(angleDeg.toDouble())
        val r = if (i % 2 == 0) 1f else 0.32f
        add(Pair((r * cos(angleRad)).toFloat(), (r * sin(angleRad)).toFloat()))
    }
}

/**
 * Subtle starfield background — a dense scatter of tiny dots across the
 * canvas, with a handful that gently twinkle at different rates.
 *
 * Star count scales to the device's screen size so the density feels
 * consistent across phones, tablets, and foldables. Stars regenerate
 * only when the canvas dimensions change (e.g. fold/unfold).
 *
 * Each star gets a slight random hue shift from [NjStardust] — some lean
 * warmer (hint of cream), some cooler (hint of blue) — so the field reads
 * as natural variation rather than a uniform dot pattern.
 *
 * Stars are positioned with minimum-distance rejection sampling so none
 * overlap or clump together. Positions are deterministic (fixed seed)
 * so the layout stays stable across recompositions.
 *
 * Most stars are plain circles. Medium-bright stars get subtle diffraction
 * rays (crosshair lines through center). The brightest anchor stars are
 * rendered as 8-point starbursts matching [NjStarburst]'s shape language.
 *
 * When [isRecording] is true the sky "holds its breath" — most twinkling
 * stars settle to a steady dim glow while a few anchor stars brighten
 * and hold steady, like the clearest night you've ever seen.
 */
@Composable
fun NjStarfield(modifier: Modifier = Modifier, isRecording: Boolean = false) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val stars = remember(canvasSize) {
        if (canvasSize.width == 0 || canvasSize.height == 0) {
            emptyList()
        } else {
            val widthDp = with(density) { canvasSize.width.toDp().value }
            val heightDp = with(density) { canvasSize.height.toDp().value }
            val count = (widthDp * heightDp * DENSITY_FACTOR).toInt().coerceAtLeast(200)
            generateStars(count)
        }
    }

    val transition = rememberInfiniteTransition(label = "starfield")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "starTime"
    )

    // Smoothly transition between idle (0) and recording (1) over 1.5s
    val settle by animateFloatAsState(
        targetValue = if (isRecording) 1f else 0f,
        animationSpec = tween(durationMillis = 1_500),
        label = "settle"
    )

    // Reusable Path for starburst drawing — avoids allocation per frame
    val starburstPath = remember { Path() }

    Canvas(modifier.onSizeChanged { canvasSize = it }) {
        val rayStrokeWidthPx = 0.3.dp.toPx()

        for (star in stars) {
            val a = computeAlpha(star, time, settle)
            val color = star.color.copy(alpha = a)
            val radiusPx = star.radiusDp.dp.toPx()
            val center = Offset(star.x * size.width, star.y * size.height)

            when (star.shape) {
                StarShape.CIRCLE -> {
                    drawCircle(color = color, radius = radiusPx, center = center)
                }

                StarShape.RAYS -> {
                    drawCircle(color = color, radius = radiusPx, center = center)
                    val rayLen = radiusPx * 1.8f
                    // Horizontal ray
                    drawLine(
                        color = color,
                        start = Offset(center.x - rayLen, center.y),
                        end = Offset(center.x + rayLen, center.y),
                        strokeWidth = rayStrokeWidthPx,
                        cap = StrokeCap.Round
                    )
                    // Vertical ray
                    drawLine(
                        color = color,
                        start = Offset(center.x, center.y - rayLen),
                        end = Offset(center.x, center.y + rayLen),
                        strokeWidth = rayStrokeWidthPx,
                        cap = StrokeCap.Round
                    )
                }

                StarShape.STARBURST -> {
                    // Anchor glow during recording — soft radial gradient behind the star
                    if (star.anchor && settle > 0f) {
                        val glowRadius = radiusPx * 1.4f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    star.color.copy(alpha = 0.25f * settle),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = glowRadius
                            ),
                            radius = glowRadius,
                            center = center
                        )
                    }
                    drawStarburst(starburstPath, center, radiusPx, color)
                }
            }
        }
    }
}

/**
 * Draw an 8-point starburst at [center] with [outerRadius], using the
 * pre-computed unit vertices. Resets and reuses [path] to avoid allocation.
 */
private fun DrawScope.drawStarburst(
    path: Path,
    center: Offset,
    outerRadius: Float,
    color: Color
) {
    path.reset()
    STARBURST_UNIT_VERTICES.forEachIndexed { i, (ux, uy) ->
        val px = center.x + ux * outerRadius
        val py = center.y + uy * outerRadius
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color = color, style = Fill)
}

/** Compute the current alpha for a star given animation time and settle progress. */
private fun computeAlpha(star: Star, time: Float, settle: Float): Float {
    return if (star.twinkles) {
        val sine = sin(time * star.speed + star.phase) * 0.5f + 0.5f
        val exp = 5f + settle * 9f
        val t = sine.pow(exp)
        val idleAlpha = 0.03f + star.alpha * t
        val settledAlpha = star.alpha * 0.15f
        idleAlpha + (settledAlpha - idleAlpha) * settle
    } else if (star.anchor) {
        val shimmer = sin(time * 1.5f + star.phase) * 0.12f * settle
        val settledAlpha = 0.55f + shimmer
        star.alpha + (settledAlpha - star.alpha) * settle
    } else {
        star.alpha
    }
}

/**
 * Tint [NjStardust] along a warm–cool axis.
 *
 * [shift] ranges from -1 (cool blue lean) to +1 (warm cream lean).
 * The offset is subtle — max +/-18 on the R/B channels — so stars still
 * read as "white" with natural variation.
 */
private fun tintStardust(shift: Float): Color {
    val r = (NjStardust.red * 255f + shift * 18f).coerceIn(0f, 255f)
    val g = (NjStardust.green * 255f).coerceIn(0f, 255f)
    val b = (NjStardust.blue * 255f - shift * 14f).coerceIn(0f, 255f)
    return Color(r / 255f, g / 255f, b / 255f)
}

/**
 * Generate [targetCount] star positions using rejection sampling to enforce
 * minimum spacing. Coordinates are normalized 0–1. Minimum distance scales
 * inversely with the square root of [targetCount] so higher density still
 * allows stars to spread without excessive rejection.
 */
private fun generateStars(targetCount: Int): List<Star> {
    val rng = Random(42)
    val placed = mutableListOf<Star>()
    val minDist = BASE_MIN_DIST / sqrt(targetCount / BASE_COUNT)
    val minDistSq = minDist * minDist
    var attempts = 0
    val maxAttempts = targetCount * 3

    while (placed.size < targetCount && attempts < maxAttempts) {
        attempts++
        val x = rng.nextFloat()
        val y = rng.nextFloat()

        val tooClose = placed.any { other ->
            val dx = x - other.x
            val dy = y - other.y
            dx * dx + dy * dy < minDistSq
        }
        if (tooClose) continue

        val isLarge = rng.nextFloat() < 0.08f
        val twinkles = rng.nextFloat() < 0.25f
        val isAnchor = isLarge && !twinkles
        val shift = rng.nextFloat() * 2f - 1f

        val shape = when {
            isAnchor -> StarShape.STARBURST
            isLarge -> StarShape.RAYS
            else -> StarShape.CIRCLE
        }

        placed += Star(
            x = x,
            y = y,
            radiusDp = if (isAnchor)
                rng.nextFloat() * 0.3f + 1.1f
            else if (isLarge)
                rng.nextFloat() * 0.4f + 0.6f
            else
                rng.nextFloat() * 0.4f + 0.3f,
            alpha = if (twinkles)
                rng.nextFloat() * 0.25f + 0.45f
            else
                rng.nextFloat() * 0.22f + 0.10f,
            twinkles = twinkles,
            anchor = isAnchor,
            phase = rng.nextFloat() * 2f * PI.toFloat(),
            speed = if (twinkles)
                rng.nextFloat() * 0.6f + 0.5f
            else 1f,
            color = tintStardust(shift),
            shape = shape
        )
    }
    return placed
}
