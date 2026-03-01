package com.example.nightjar.ui.studio

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.example.nightjar.ui.theme.NjRecordCoral
import com.example.nightjar.ui.theme.NjStudioAccent
import com.example.nightjar.ui.theme.NjStudioTeal
import com.example.nightjar.ui.theme.NjError
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjStudioSurface2
import com.example.nightjar.ui.theme.NjStudioYellow

// Pressed-in body -- slightly darker than NjStudioSurface2 (0xFF1C1824).
private val PressedBodyColor = Color(0xFF12101A)

// Raised body -- semi-transparent muted surface.
private val RaisedBodyColor = NjMuted2.copy(alpha = 0.12f)

// Width threshold below which the drawer wraps to two rows.
private val NARROW_BREAKPOINT = 320.dp

/**
 * Inline track drawer -- expands directly below a track lane in the timeline.
 *
 * Responsive layout:
 * - Wide: single row  [Volume] [R][S][M][T] ... [Rename] [Delete]
 * - Narrow: two rows.  Top: [Volume] [R][S][M][T]
 *                       Bottom: [Rename] [Delete]
 */
@Composable
fun TrackDrawerPanel(
    track: TrackEntity,
    isSoloed: Boolean,
    isArmed: Boolean,
    hasTakes: Boolean,
    takesExpanded: Boolean,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val goldBorderColor = NjStudioAccent.copy(alpha = 0.5f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = goldBorderColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .background(NjStudioSurface2)
            .padding(horizontal = 12.dp)
    ) {
        val isNarrow = maxWidth < NARROW_BREAKPOINT

        if (isNarrow) {
            // Two-row layout for narrow screens
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: Volume knob + toggle buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    NjKnob(
                        value = track.volume,
                        onValueChange = { vol ->
                            onAction(StudioAction.SetTrackVolume(track.id, vol))
                        },
                        knobSize = 36.dp,
                        label = "${(track.volume * 100).toInt()}%"
                    )

                    Spacer(Modifier.width(4.dp))

                    DrawerToggleButton(
                        label = "R",
                        isActive = isArmed,
                        ledColor = NjRecordCoral,
                        onClick = { onAction(StudioAction.ToggleArm(track.id)) }
                    )
                    DrawerToggleButton(
                        label = "S",
                        isActive = isSoloed,
                        ledColor = NjStudioTeal,
                        onClick = { onAction(StudioAction.ToggleSolo(track.id)) }
                    )
                    DrawerToggleButton(
                        label = "M",
                        isActive = track.isMuted,
                        ledColor = NjStudioYellow,
                        onClick = {
                            onAction(StudioAction.SetTrackMuted(track.id, !track.isMuted))
                        }
                    )
                    DrawerToggleButton(
                        label = "T",
                        isActive = takesExpanded,
                        ledColor = NjStudioAccent,
                        onClick = { onAction(StudioAction.ToggleTakesView(track.id)) }
                    )
                }

                // Row 2: Rename + Delete (text buttons, right-aligned)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NjStudioButton(
                        text = "Rename",
                        onClick = {
                            onAction(
                                StudioAction.RequestRenameTrack(
                                    track.id,
                                    track.displayName
                                )
                            )
                        },
                        textColor = NjMuted2.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(8.dp))
                    NjStudioButton(
                        text = "Delete",
                        onClick = {
                            onAction(StudioAction.ConfirmDeleteTrack(track.id))
                        },
                        textColor = NjError.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            // Single-row layout for wide screens
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                verticalAlignment = Alignment.CenterVertically
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

                Spacer(Modifier.width(12.dp))

                // Toggle buttons -- tight 4dp spacing
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DrawerToggleButton(
                        label = "R",
                        isActive = isArmed,
                        ledColor = NjRecordCoral,
                        onClick = { onAction(StudioAction.ToggleArm(track.id)) }
                    )
                    DrawerToggleButton(
                        label = "S",
                        isActive = isSoloed,
                        ledColor = NjStudioTeal,
                        onClick = { onAction(StudioAction.ToggleSolo(track.id)) }
                    )
                    DrawerToggleButton(
                        label = "M",
                        isActive = track.isMuted,
                        ledColor = NjStudioYellow,
                        onClick = {
                            onAction(StudioAction.SetTrackMuted(track.id, !track.isMuted))
                        }
                    )
                    DrawerToggleButton(
                        label = "T",
                        isActive = takesExpanded,
                        ledColor = NjStudioAccent,
                        onClick = { onAction(StudioAction.ToggleTakesView(track.id)) }
                    )
                }

                // Spacer pushes action buttons to the right
                Box(Modifier.weight(1f))

                // Rename + Delete
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NjStudioButton(
                        text = "Rename",
                        onClick = {
                            onAction(
                                StudioAction.RequestRenameTrack(
                                    track.id,
                                    track.displayName
                                )
                            )
                        },
                        textColor = NjMuted2.copy(alpha = 0.7f)
                    )
                    NjStudioButton(
                        text = "Delete",
                        onClick = {
                            onAction(StudioAction.ConfirmDeleteTrack(track.id))
                        },
                        textColor = NjError.copy(alpha = 0.7f)
                    )
                }
            }
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
fun DrawerToggleButton(
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

    // Haptics -- fire on raw press/release events.
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
