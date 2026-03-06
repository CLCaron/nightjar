package com.example.nightjar.ui.studio

import com.example.nightjar.ui.components.NjButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.example.nightjar.data.db.entity.TrackEntity
import com.example.nightjar.ui.components.NjKnob
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjError
import com.example.nightjar.ui.theme.NjMuted
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjStudioAccent
import com.example.nightjar.ui.theme.NjStudioTeal
import com.example.nightjar.ui.theme.NjStudioYellow
import com.example.nightjar.ui.theme.NjSurface2

private val NARROW_BREAKPOINT = 320.dp

/**
 * Inline track drawer for MIDI instrument tracks.
 *
 * Shows: Volume knob, Solo/Mute toggles, instrument name + change button,
 * edit notes button (opens piano roll), rename, delete.
 * No arm toggle (MIDI tracks are edited via piano roll, not recorded live).
 */
@Composable
fun MidiTrackDrawer(
    track: TrackEntity,
    isSoloed: Boolean,
    instrumentName: String,
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
            .background(NjSurface2)
            .padding(horizontal = 12.dp)
    ) {
        val isNarrow = maxWidth < NARROW_BREAKPOINT

        if (isNarrow) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: Volume + S/M toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NjKnob(
                        value = track.volume,
                        onValueChange = { vol ->
                            onAction(StudioAction.SetTrackVolume(track.id, vol))
                        },
                        knobSize = 36.dp,
                        label = "${(track.volume * 100).toInt()}%"
                    )
                    Spacer(Modifier.width(12.dp))
                    ToggleButtons(track, isSoloed, onAction)
                }

                // Row 2: Instrument + Edit + Rename + Delete
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = instrumentName,
                        style = MaterialTheme.typography.labelSmall,
                        color = NjMuted,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    ActionButtons(track, onAction)
                }
            }
        } else {
            // Wide single-row layout
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NjKnob(
                    value = track.volume,
                    onValueChange = { vol ->
                        onAction(StudioAction.SetTrackVolume(track.id, vol))
                    },
                    knobSize = 36.dp,
                    label = "${(track.volume * 100).toInt()}%"
                )
                Spacer(Modifier.width(12.dp))
                ToggleButtons(track, isSoloed, onAction)
                Spacer(Modifier.width(8.dp))

                Text(
                    text = instrumentName,
                    style = MaterialTheme.typography.labelSmall,
                    color = NjMuted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(4.dp))
                ActionButtons(track, onAction)
            }
        }
    }
}

@Composable
private fun ToggleButtons(
    track: TrackEntity,
    isSoloed: Boolean,
    onAction: (StudioAction) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NjButton(
            text = "S",
            onClick = { onAction(StudioAction.ToggleSolo(track.id)) },
            isActive = isSoloed,
            ledColor = NjStudioTeal
        )
        NjButton(
            text = "M",
            onClick = { onAction(StudioAction.SetTrackMuted(track.id, !track.isMuted)) },
            isActive = track.isMuted,
            ledColor = NjStudioYellow
        )
    }
}

@Composable
private fun ActionButtons(
    track: TrackEntity,
    onAction: (StudioAction) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NjButton(
            text = "Inst",
            onClick = { onAction(StudioAction.ShowInstrumentPicker(track.id)) },
            ledColor = NjAccent
        )
        NjButton(
            text = "Edit",
            onClick = { onAction(StudioAction.OpenPianoRoll(track.id)) },
            ledColor = NjAccent
        )
        NjButton(
            text = "Ren",
            onClick = {
                onAction(StudioAction.RequestRenameTrack(track.id, track.displayName))
            }
        )
        NjButton(
            text = "Del",
            onClick = { onAction(StudioAction.ConfirmDeleteTrack(track.id)) },
            ledColor = NjError
        )
    }
}
