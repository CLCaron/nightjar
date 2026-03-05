package com.example.nightjar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.NjPanelInset

/**
 * Recessed display panel -- like a VU meter or LCD window on hardware.
 *
 * A clipped box with a dark inset background and permanent bevel shadows
 * (dark top/left, subtle light bottom/right) that make it look pressed
 * into the device body. Used for waveform displays and similar readouts.
 */
@Composable
fun NjRecessedPanel(
    modifier: Modifier = Modifier,
    backgroundColor: Color = NjPanelInset,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .drawWithContent {
                drawContent()

                val sw = 1.dp.toPx()

                // Dark top + left edges (inset shadow)
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
                // Subtle light bottom + right (rim catch)
                drawLine(
                    Color.White.copy(alpha = 0.05f),
                    Offset(0f, size.height - sw / 2),
                    Offset(size.width, size.height - sw / 2),
                    sw
                )
                drawLine(
                    Color.White.copy(alpha = 0.03f),
                    Offset(size.width - sw / 2, 0f),
                    Offset(size.width - sw / 2, size.height),
                    sw
                )
            }
            .padding(12.dp)
    ) {
        content()
    }
}
