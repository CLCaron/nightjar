package com.example.nightjar.ui.studio

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.ui.components.NjKnob
import com.example.nightjar.ui.components.collectIsPressedWithMinDuration
import com.example.nightjar.ui.theme.NjStudioAccent
import com.example.nightjar.ui.theme.NjStudioTeal
import com.example.nightjar.ui.theme.NjError
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjStudioSurface2
import com.example.nightjar.ui.theme.NjStudioYellow

// Pressed-in body — slightly darker than NjStudioSurface2 (0xFF1C1824).
private val PressedBodyColor = Color(0xFF12101A)

// Raised body — semi-transparent muted surface.
private val RaisedBodyColor = NjMuted2.copy(alpha = 0.12f)

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
    val goldBorderColor = NjStudioAccent.copy(alpha = 0.5f)

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
            .background(NjStudioSurface2)
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
                ledColor = NjStudioTeal,
                onClick = { onAction(StudioAction.ToggleSolo(track.id)) }
            )

            // Mute toggle
            DrawerToggleButton(
                label = "M",
                isActive = track.isMuted,
                ledColor = NjStudioYellow,
                onClick = {
                    onAction(StudioAction.SetTrackMuted(track.id, !track.isMuted))
                }
            )

            // Spacer to push delete to the right
            Box(Modifier.weight(1f))

            // Delete button
            NjStudioButton(
                text = "Delete",
                onClick = { onAction(StudioAction.ConfirmDeleteTrack(track.id)) },
                textColor = NjError.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Hardware-style toggle button with a "clicked in" visual when active.
 *
 * Two states: raised (inactive) and pressed-in (active or finger down).
 * Haptic click on press and release. LED glow when active.
 */
@Composable
private fun DrawerToggleButton(
    label: String,
    isActive: Boolean,
    ledColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val view = LocalView.current
    val fingerDown by interactionSource.collectIsPressedWithMinDuration()

    val visuallyPressed = isActive || fingerDown
    val bgColor = if (visuallyPressed) PressedBodyColor else RaisedBodyColor
    val textColor = if (isActive) ledColor else NjMuted2

    // Haptics — fire on raw press/release events.
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

    Box(
        modifier = Modifier
            .size(36.dp)
            .offset(y = if (visuallyPressed) 1.dp else 0.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .drawWithContent {
                drawContent()

                val sw = 1.dp.toPx()

                if (visuallyPressed) {
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
            ),
        contentAlignment = Alignment.Center
    ) {
        val glowShadow = if (isActive) {
            Shadow(
                color = ledColor.copy(alpha = 0.8f),
                offset = Offset.Zero,
                blurRadius = 8f
            )
        } else {
            Shadow(
                color = Color.White.copy(alpha = 0.35f),
                offset = Offset.Zero,
                blurRadius = 6f
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(shadow = glowShadow),
            color = textColor
        )
    }
}
