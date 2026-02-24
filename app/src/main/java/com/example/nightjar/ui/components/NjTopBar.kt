package com.example.nightjar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.NjStarlight

/** Top bar with optional back chevron, title, and trailing action slot. */
@Composable
fun NjTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                BackChevron(onClick = onBack)
                Spacer(Modifier.width(12.dp))
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f)
            )

            if (trailing != null) {
                Box(contentAlignment = Alignment.CenterEnd) {
                    trailing()
                }
            }
        }

        // Gradient fade divider
        Spacer(Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
        ) {
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        NjStarlight.copy(alpha = 0.12f),
                        NjStarlight.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    startX = 0f,
                    endX = size.width
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = size.height
            )
        }
    }
}

/**
 * Soft rounded chevron pointing left — the Nightjar back button.
 *
 * Two lines meeting at a rounded point, drawn with [StrokeCap.Round]
 * and [StrokeJoin.Round] for a gentle, hand-crafted feel. Starlight
 * silver-blue at a warm alpha pairs with the Josefin Sans titles.
 */
@Composable
private fun BackChevron(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp)) {
            val color = NjStarlight.copy(alpha = 0.7f)
            val strokePx = 2.dp.toPx()

            val cx = size.width * 0.42f   // tip sits slightly left of center
            val cy = size.height / 2f
            val armX = size.width * 0.82f  // right end of both arms
            val armY = size.height * 0.38f // vertical spread from center

            // Upper arm: top-right → center-left
            drawLine(
                color = color,
                start = Offset(armX, cy - armY),
                end = Offset(cx, cy),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
            // Lower arm: center-left → bottom-right
            drawLine(
                color = color,
                start = Offset(cx, cy),
                end = Offset(armX, cy + armY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
        }
    }
}
