package com.example.nightjar.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom icon set for Nightjar.
 */
object NjIcons {
    /**
     * Combined play-pause icon: a play triangle on the left
     * and two pause bars on the right, merged into a single symbol.
     */
    val PlayPause: ImageVector by lazy {
        ImageVector.Builder(
            name = "PlayPause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Play triangle (left side)
            path(fill = SolidColor(Color.Black)) {
                moveTo(2f, 4f)
                lineTo(11f, 12f)
                lineTo(2f, 20f)
                close()
            }
            // Pause bar 1 (right side)
            path(fill = SolidColor(Color.Black)) {
                moveTo(14f, 4f)
                lineTo(17f, 4f)
                lineTo(17f, 20f)
                lineTo(14f, 20f)
                close()
            }
            // Pause bar 2 (right side)
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 4f)
                lineTo(22f, 4f)
                lineTo(22f, 20f)
                lineTo(19f, 20f)
                close()
            }
        }.build()
    }

    /**
     * Classic pendulum metronome: outlined trapezoidal body, solid pendulum
     * arm, outlined weight circle, and a center vertical scale with hashmarks.
     */
    val Metronome: ImageVector by lazy {
        ImageVector.Builder(
            name = "Metronome",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            val stroke = SolidColor(Color.Black)
            val sw = 1.6f

            // Body outline (trapezoid: narrow top, wide base)
            path(
                fill = null,
                stroke = stroke,
                strokeLineWidth = sw
            ) {
                moveTo(8f, 22f)
                lineTo(16f, 22f)
                lineTo(14f, 6f)
                lineTo(10f, 6f)
                close()
            }
            // Center vertical scale line
            path(
                fill = null,
                stroke = stroke,
                strokeLineWidth = sw * 0.7f
            ) {
                moveTo(12f, 8f)
                lineTo(12f, 20f)
            }
            // Hashmarks on scale (short horizontal ticks)
            path(
                fill = null,
                stroke = stroke,
                strokeLineWidth = sw * 0.6f
            ) {
                // 4 evenly spaced ticks
                moveTo(10.8f, 11f); lineTo(13.2f, 11f)
                moveTo(10.6f, 14f); lineTo(13.4f, 14f)
                moveTo(10.4f, 17f); lineTo(13.6f, 17f)
                moveTo(10.2f, 20f); lineTo(13.8f, 20f)
            }
            // Pendulum arm (solid, angled from pivot to upper-left)
            path(fill = SolidColor(Color.Black)) {
                moveTo(11.5f, 8f)
                lineTo(12.5f, 8f)
                lineTo(8f, 2f)
                lineTo(7f, 2.7f)
                close()
            }
            // Pendulum weight (outline circle at tip of arm)
            path(
                fill = null,
                stroke = stroke,
                strokeLineWidth = sw
            ) {
                // Circle at (7.5, 2.35), radius ~1.5
                moveTo(9f, 2.35f)
                curveTo(9f, 1.52f, 8.33f, 0.85f, 7.5f, 0.85f)
                curveTo(6.67f, 0.85f, 6f, 1.52f, 6f, 2.35f)
                curveTo(6f, 3.18f, 6.67f, 3.85f, 7.5f, 3.85f)
                curveTo(8.33f, 3.85f, 9f, 3.18f, 9f, 2.35f)
                close()
            }
        }.build()
    }
}
