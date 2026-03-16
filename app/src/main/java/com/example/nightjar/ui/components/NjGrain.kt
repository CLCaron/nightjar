package com.example.nightjar.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.random.Random

/**
 * Draws fine static noise over any surface for a matte texture feel.
 *
 * Generates a small [ImageBitmap] (64x64 pixels of warm-tinted random
 * alpha values) once, then tiles it across the surface. GPU-friendly since
 * no per-frame random generation occurs. The noise is tinted gold-cream
 * (NjStardust) so it reinforces warmth rather than pushing toward gray.
 *
 * @param alpha Intensity of the noise overlay. 0.04-0.06 is subliminal.
 * @param tintColor Base RGB color for noise pixels. Default is warm gold-cream.
 */
fun Modifier.njGrain(
    alpha: Float = 0.06f,
    tintColor: Long = 0x00ECE0D4  // NjStardust RGB without alpha
): Modifier = composed {
    val noiseBitmap = remember(alpha, tintColor) {
        createNoiseBitmap(size = 64, alpha = alpha, tintRgb = tintColor)
    }

    drawWithContent {
        drawContent()

        val bw = noiseBitmap.width
        val bh = noiseBitmap.height
        val w = size.width.toInt()
        val h = size.height.toInt()

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                drawImage(
                    image = noiseBitmap,
                    dstOffset = IntOffset(x, y),
                    dstSize = IntSize(
                        minOf(bw, w - x),
                        minOf(bh, h - y)
                    ),
                    blendMode = BlendMode.SrcOver
                )
                x += bw
            }
            y += bh
        }
    }
}

private fun createNoiseBitmap(size: Int, alpha: Float, tintRgb: Long): ImageBitmap {
    val pixels = IntArray(size * size)
    val rng = Random(42) // deterministic seed -- no flicker on recomposition
    val rgb = (tintRgb and 0x00FFFFFF).toInt()

    for (i in pixels.indices) {
        val a = (rng.nextFloat() * alpha * 255).toInt().coerceIn(0, 255)
        pixels[i] = (a shl 24) or rgb
    }

    val androidBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    androidBitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return androidBitmap.asImageBitmap()
}
