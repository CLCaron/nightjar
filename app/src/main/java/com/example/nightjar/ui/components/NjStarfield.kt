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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.NjStardust
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private data class Star(
    val x: Float,
    val y: Float,
    val radiusDp: Float,
    val alpha: Float,
    val twinkles: Boolean,
    val anchor: Boolean,
    val phase: Float,
    val speed: Float,
    val color: Color
)

/**
 * Subtle starfield background — tiny dots scattered across the canvas,
 * a handful of which gently twinkle at different rates.
 *
 * Each star gets a slight random hue shift from [NjStardust] — some lean
 * warmer (hint of cream), some cooler (hint of blue) — so the field reads
 * as natural variation rather than a uniform dot pattern.
 *
 * Stars are positioned with minimum-distance rejection sampling so none
 * overlap or clump together. Positions are deterministic (fixed seed)
 * so the layout stays stable across recompositions.
 *
 * When [isRecording] is true the sky "holds its breath" — most twinkling
 * stars settle to a steady dim glow while a few anchor stars brighten
 * and hold steady, like the clearest night you've ever seen.
 */
@Composable
fun NjStarfield(modifier: Modifier = Modifier, isRecording: Boolean = false) {
    val stars = remember { generateStars() }

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

    Canvas(modifier) {
        for (star in stars) {
            val a = if (star.twinkles) {
                val sine = sin(time * star.speed + star.phase) * 0.5f + 0.5f
                // Exponent rises from 5 → 14 as settle increases — narrows
                // the twinkle window until most stars appear still
                val exp = 5f + settle * 9f
                val t = sine.pow(exp)
                // Idle: twinkle normally. Recording: fade toward a dim steady glow
                val idleAlpha = 0.03f + star.alpha * t
                val settledAlpha = star.alpha * 0.15f
                idleAlpha + (settledAlpha - idleAlpha) * settle
            } else if (star.anchor) {
                // Anchor stars: bloom to bright points when recording,
                // with the faintest shimmer — like atmospheric scintillation
                val shimmer = sin(time * 1.5f + star.phase) * 0.12f * settle
                val settledAlpha = 0.55f + shimmer
                star.alpha + (settledAlpha - star.alpha) * settle
            } else {
                star.alpha
            }
            drawCircle(
                color = star.color.copy(alpha = a),
                radius = star.radiusDp.dp.toPx(),
                center = Offset(star.x * size.width, star.y * size.height)
            )
        }
    }
}

/**
 * Tint [NjStardust] along a warm–cool axis.
 *
 * [shift] ranges from -1 (cool blue lean) to +1 (warm cream lean).
 * The offset is subtle — max ±18 on the R/B channels — so stars still
 * read as "white" with natural variation.
 */
private fun tintStardust(shift: Float): Color {
    val r = (NjStardust.red * 255f + shift * 18f).coerceIn(0f, 255f)
    val g = (NjStardust.green * 255f).coerceIn(0f, 255f)
    val b = (NjStardust.blue * 255f - shift * 14f).coerceIn(0f, 255f)
    return Color(r / 255f, g / 255f, b / 255f)
}

/**
 * Generate star positions using rejection sampling to enforce minimum
 * spacing. Coordinates are normalized 0–1.
 */
private fun generateStars(): List<Star> {
    val rng = Random(42)
    val placed = mutableListOf<Star>()
    val minDist = 0.023 // prevents clumping
    var attempts = 0

    while (placed.size < 400 && attempts < 800) {
        attempts++
        val x = rng.nextFloat()
        val y = rng.nextFloat()

        val tooClose = placed.any { other ->
            val dx = x - other.x
            val dy = y - other.y
            sqrt(dx * dx + dy * dy) < minDist
        }
        if (tooClose) continue

        val isLarge = rng.nextFloat() < 0.08f
        val twinkles = rng.nextFloat() < 0.25f
        val isAnchor = isLarge && !twinkles
        // Warm/cool shift: -1 (cool blue) to +1 (warm cream)
        val shift = rng.nextFloat() * 2f - 1f

        placed += Star(
            x = x,
            y = y,
            radiusDp = if (isAnchor)
                rng.nextFloat() * 0.3f + 1.1f             // anchors: 1.1–1.4 dp (stand out)
            else if (isLarge)
                rng.nextFloat() * 0.4f + 0.6f             // ~8%: 0.6–1.0 dp (slightly larger)
            else
                rng.nextFloat() * 0.4f + 0.3f,            // 92%: 0.3–0.7 dp (tiny pinpoints)
            alpha = if (twinkles)
                rng.nextFloat() * 0.25f + 0.45f           // 0.45–0.70 peak for twinklers
            else
                rng.nextFloat() * 0.22f + 0.10f,          // 0.10–0.32 for static stars
            twinkles = twinkles,
            anchor = isAnchor,
            phase = rng.nextFloat() * 2f * PI.toFloat(),
            speed = if (twinkles)
                rng.nextFloat() * 0.6f + 0.5f             // 0.5–1.1x speed variation
            else 1f,
            color = tintStardust(shift)
        )
    }
    return placed
}
