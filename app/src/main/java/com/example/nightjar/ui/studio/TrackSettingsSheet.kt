package com.example.nightjar.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjStarlight

/** Bottom sheet with volume slider and mute toggle for a single track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSettingsSheet(
    track: TrackEntity,
    onAction: (StudioAction) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = track.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )

            // Volume
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Volume",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )

                VolumeSlider(
                    volume = track.volume,
                    onVolumeChange = { vol ->
                        onAction(StudioAction.SetTrackVolume(track.id, vol))
                    },
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = "${(track.volume * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            // Mute toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Switch,
                        onClick = {
                            onAction(StudioAction.SetTrackMuted(track.id, !track.isMuted))
                        }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Mute",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                Text(
                    text = if (track.isMuted) "Muted" else "Active",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (track.isMuted) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    } else {
                        NjStarlight.copy(alpha = 0.7f)
                    }
                )
            }

            // Delete
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onDismiss()
                            onAction(StudioAction.ConfirmDeleteTrack(track.id))
                        }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Delete track",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Thin-line volume slider matching the scrubber's visual style. */
@Composable
private fun VolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasWidth by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .height(32.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        canvasWidth = size.width.toFloat()
                        if (canvasWidth > 0f) {
                            onVolumeChange((offset.x / canvasWidth).coerceIn(0f, 1f))
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (canvasWidth > 0f) {
                            onVolumeChange(
                                (change.position.x / canvasWidth).coerceIn(0f, 1f)
                            )
                        }
                    },
                    onDragEnd = {},
                    onDragCancel = {}
                )
            }
    ) {
        canvasWidth = size.width
        val centerY = size.height / 2f
        val trackHeight = 2.dp.toPx()
        val thumbRadius = 5.dp.toPx()
        val thumbX = size.width * volume.coerceIn(0f, 1f)

        // Inactive track
        drawLine(
            color = NjStarlight.copy(alpha = 0.15f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = trackHeight
        )

        // Active track
        if (thumbX > 0f) {
            drawLine(
                color = NjAccent.copy(alpha = 0.6f),
                start = Offset(0f, centerY),
                end = Offset(thumbX, centerY),
                strokeWidth = trackHeight
            )
        }

        // Thumb
        drawCircle(
            color = NjAccent,
            radius = thumbRadius,
            center = Offset(thumbX, centerY)
        )
    }
}
