package com.example.nightjar.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.ui.components.NjKnob
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjError
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjSurface2

// Pressed body — slightly darker than NjSurface2 (0xFF141A28),
// so the button looks recessed INTO the drawer surface.
private val PressedBodyColor = Color(0xFF0E1420)

/**
 * Inline track drawer — expands directly below a track lane in the timeline.
 * Contains volume knob, solo/mute toggles, and delete button.
 */
@Composable
fun TrackDrawerPanel(
    track: TrackEntity,
    isSoloed: Boolean,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val goldBorderColor = NjAccent.copy(alpha = 0.5f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .drawBehind {
                // 1dp gold top border
                drawLine(
                    color = goldBorderColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .background(NjSurface2)
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Volume knob
            NjKnob(
                value = track.volume,
                onValueChange = { vol ->
                    onAction(StudioAction.SetTrackVolume(track.id, vol))
                },
                knobSize = 36.dp,
                label = "${(track.volume * 100).toInt()}%"
            )

            // Solo toggle
            DrawerToggleButton(
                label = "S",
                isActive = isSoloed,
                ledColor = NjAccent,
                onClick = { onAction(StudioAction.ToggleSolo(track.id)) }
            )

            // Mute toggle
            DrawerToggleButton(
                label = "M",
                isActive = track.isMuted,
                ledColor = NjError,
                onClick = {
                    onAction(StudioAction.SetTrackMuted(track.id, !track.isMuted))
                }
            )

            // Spacer to push delete to the right
            Box(Modifier.weight(1f))

            // Delete button
            Text(
                text = "Delete",
                style = MaterialTheme.typography.labelMedium,
                color = NjError.copy(alpha = 0.7f),
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onAction(StudioAction.ConfirmDeleteTrack(track.id))
                        }
                    )
                    .padding(8.dp)
            )
        }
    }
}

/**
 * Hardware-style toggle button with a "clicked in" visual when active.
 *
 * Inactive (raised): light top edge, dark bottom edge, muted text.
 * Active (pressed in): shifted down 1dp, inner shadow, dark recessed body,
 * and the letter "lights up" with a soft LED glow behind it.
 */
@Composable
private fun DrawerToggleButton(
    label: String,
    isActive: Boolean,
    ledColor: Color,
    onClick: () -> Unit
) {
    val bgColor = if (isActive) PressedBodyColor else NjMuted2.copy(alpha = 0.12f)
    val textColor = if (isActive) ledColor else NjMuted2

    Box(
        modifier = Modifier
            .size(36.dp)
            .offset(y = if (isActive) 1.dp else 0.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .drawWithContent {
                val sw = 1.dp.toPx()

                // LED glow behind text when active
                if (isActive) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ledColor.copy(alpha = 0.15f),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2, size.height / 2),
                            radius = size.minDimension * 0.25f
                        ),
                        center = Offset(size.width / 2, size.height / 2),
                        radius = size.minDimension * 0.25f
                    )
                }

                drawContent()

                // Bevel edges
                if (isActive) {
                    // Pressed in: dark top + left edges = inner shadow
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
                    // Raised: light top edge, dark bottom edge
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
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor
        )
    }
}
