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
}
