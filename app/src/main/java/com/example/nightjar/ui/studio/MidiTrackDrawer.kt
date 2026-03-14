package com.example.nightjar.ui.studio

import com.example.nightjar.ui.components.NjButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Piano
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

/**
 * Inline track drawer for MIDI instrument tracks.
 *
 * Two-row control layout plus an inline MiniPianoRoll for the selected clip.
 *   Row 1: Volume knob, S/M toggles, Inst button, Edit button
 *   Row 2: Instrument name, Rename button, Delete button
 *   Row 3: MiniPianoRoll (edge-to-edge, no horizontal padding)
 */
@Composable
fun MidiTrackDrawer(
    track: TrackEntity,
    trackIndex: Int,
    isSoloed: Boolean,
    midiState: MidiTrackUiState?,
    bpm: Double,
    timeSignatureNumerator: Int,
    timeSignatureDenominator: Int,
    isSnapEnabled: Boolean,
    gridResolution: Int,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val goldBorderColor = NjStudioAccent.copy(alpha = 0.5f)
    val instrumentName = midiState?.instrumentName ?: "Unknown"

    Column(
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
    ) {
        // Controls area (with horizontal padding)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Row 1: Volume + S/M toggles + Inst/Edit
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

                Spacer(Modifier.width(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NjButton(
                        text = "Inst",
                        icon = Icons.Filled.Piano,
                        onClick = { onAction(StudioAction.ShowInstrumentPicker(track.id)) },
                        textColor = NjAccent
                    )
                    NjButton(
                        text = "Edit",
                        icon = Icons.Filled.MusicNote,
                        onClick = { onAction(StudioAction.OpenPianoRoll(track.id)) },
                        textColor = NjAccent
                    )
                }
            }

            // Row 2: Instrument name + Rename/Delete
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
                NjButton(
                    text = "Ren",
                    icon = Icons.Filled.Edit,
                    onClick = {
                        onAction(StudioAction.RequestRenameTrack(track.id, track.displayName))
                    }
                )
                NjButton(
                    text = "Del",
                    icon = Icons.Filled.Delete,
                    onClick = { onAction(StudioAction.ConfirmDeleteTrack(track.id)) },
                    textColor = NjError
                )
            }
        }

        // MiniPianoRoll: full width, no horizontal padding
        val selectedClip = midiState?.clips?.find { it.clipId == midiState.selectedClipId }
        if (selectedClip != null) {
            MiniPianoRoll(
                clip = selectedClip,
                trackId = track.id,
                trackIndex = trackIndex,
                bpm = bpm,
                timeSignatureNumerator = timeSignatureNumerator,
                timeSignatureDenominator = timeSignatureDenominator,
                isSnapEnabled = isSnapEnabled,
                gridResolution = gridResolution,
                onAction = onAction
            )
        } else if (midiState != null && midiState.clips.isNotEmpty()) {
            Text(
                text = "Tap a clip on the timeline to edit",
                style = MaterialTheme.typography.labelSmall,
                color = NjMuted2,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}
