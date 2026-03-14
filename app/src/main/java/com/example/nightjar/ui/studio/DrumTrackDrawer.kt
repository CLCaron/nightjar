package com.example.nightjar.ui.studio

import com.example.nightjar.ui.components.NjButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.example.nightjar.ui.theme.NjError
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjStudioAccent
import com.example.nightjar.ui.theme.NjSurface2
import com.example.nightjar.ui.theme.NjStudioTeal
import com.example.nightjar.ui.theme.NjStudioYellow

private val NARROW_BREAKPOINT = 320.dp

/**
 * Inline track drawer for drum tracks. Shows volume/solo/mute controls,
 * bar count controls, and the drum pattern editor grid.
 *
 * Responsive layout: single row on wide screens, two rows on narrow.
 */
@Composable
fun DrumTrackDrawer(
    track: TrackEntity,
    isSoloed: Boolean,
    pattern: DrumPatternUiState?,
    bpm: Double,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier,
    beatsPerBar: Int = 4,
    timeSignatureNumerator: Int = 4,
    timeSignatureDenominator: Int = 4
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
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val isNarrow = maxWidth < NARROW_BREAKPOINT

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isNarrow) {
                // Row 1: Volume + S/M toggles + Bar count
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
                    }

                    if (pattern != null) {
                        Spacer(Modifier.width(12.dp))

                        // Resolution picker
                        val resPresets = listOf(8, 16, 32)
                        val currentRes = if (timeSignatureNumerator > 0) {
                            (pattern.stepsPerBar * timeSignatureDenominator) / timeSignatureNumerator
                        } else 16
                        val resIndex = resPresets.indexOf(currentRes)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            NjButton(
                                text = "-",
                                onClick = {
                                    val prevIdx = (resIndex - 1).coerceAtLeast(0)
                                    if (prevIdx != resIndex) {
                                        onAction(StudioAction.SetPatternResolution(track.id, resPresets[prevIdx]))
                                    }
                                },
                                textColor = if (resIndex > 0) {
                                    NjStudioAccent.copy(alpha = 0.7f)
                                } else {
                                    NjMuted2.copy(alpha = 0.3f)
                                }
                            )
                            Text(
                                text = "1/$currentRes",
                                style = MaterialTheme.typography.labelMedium,
                                color = NjStudioAccent.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            NjButton(
                                text = "+",
                                onClick = {
                                    val nextIdx = (resIndex + 1).coerceAtMost(resPresets.size - 1)
                                    if (nextIdx != resIndex) {
                                        onAction(StudioAction.SetPatternResolution(track.id, resPresets[nextIdx]))
                                    }
                                },
                                textColor = if (resIndex < resPresets.size - 1) {
                                    NjStudioAccent.copy(alpha = 0.7f)
                                } else {
                                    NjMuted2.copy(alpha = 0.3f)
                                }
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        // Bar count controls
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            NjButton(
                                text = "-",
                                onClick = {
                                    if (pattern.bars > 1) {
                                        onAction(StudioAction.SetPatternBars(track.id, pattern.bars - 1))
                                    }
                                },
                                textColor = if (pattern.bars > 1) {
                                    NjStudioAccent.copy(alpha = 0.7f)
                                } else {
                                    NjMuted2.copy(alpha = 0.3f)
                                }
                            )
                            Text(
                                text = "${pattern.bars} bar${if (pattern.bars > 1) "s" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                color = NjStudioAccent.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            NjButton(
                                text = "+",
                                onClick = {
                                    if (pattern.bars < 8) {
                                        onAction(StudioAction.SetPatternBars(track.id, pattern.bars + 1))
                                    }
                                },
                                textColor = if (pattern.bars < 8) {
                                    NjStudioAccent.copy(alpha = 0.7f)
                                } else {
                                    NjMuted2.copy(alpha = 0.3f)
                                }
                            )
                        }
                    }
                }

                // Row 2: Edit + Rename + Delete (right-aligned)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NjButton(
                        text = "Edit",
                        icon = Icons.Filled.Edit,
                        onClick = {
                            onAction(StudioAction.OpenDrumEditor(track.id))
                        },
                        textColor = NjStudioAccent.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.width(8.dp))
                    NjButton(
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
                    NjButton(
                        text = "Delete",
                        icon = Icons.Filled.Delete,
                        onClick = {
                            onAction(StudioAction.ConfirmDeleteTrack(track.id))
                        },
                        textColor = NjError.copy(alpha = 0.7f)
                    )
                }
            } else {
                // Wide: single row
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
                    }

                    Spacer(Modifier.width(12.dp))

                    // Resolution + Bar count controls
                    if (pattern != null) {
                        // Resolution picker
                        val resPresets = listOf(8, 16, 32)
                        val currentRes = if (timeSignatureNumerator > 0) {
                            (pattern.stepsPerBar * timeSignatureDenominator) / timeSignatureNumerator
                        } else 16
                        val resIndex = resPresets.indexOf(currentRes)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            NjButton(
                                text = "-",
                                onClick = {
                                    val prevIdx = (resIndex - 1).coerceAtLeast(0)
                                    if (prevIdx != resIndex) {
                                        onAction(StudioAction.SetPatternResolution(track.id, resPresets[prevIdx]))
                                    }
                                },
                                textColor = if (resIndex > 0) {
                                    NjStudioAccent.copy(alpha = 0.7f)
                                } else {
                                    NjMuted2.copy(alpha = 0.3f)
                                }
                            )
                            Text(
                                text = "1/$currentRes",
                                style = MaterialTheme.typography.labelMedium,
                                color = NjStudioAccent.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            NjButton(
                                text = "+",
                                onClick = {
                                    val nextIdx = (resIndex + 1).coerceAtMost(resPresets.size - 1)
                                    if (nextIdx != resIndex) {
                                        onAction(StudioAction.SetPatternResolution(track.id, resPresets[nextIdx]))
                                    }
                                },
                                textColor = if (resIndex < resPresets.size - 1) {
                                    NjStudioAccent.copy(alpha = 0.7f)
                                } else {
                                    NjMuted2.copy(alpha = 0.3f)
                                }
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        // Bar count controls
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            NjButton(
                                text = "-",
                                onClick = {
                                    if (pattern.bars > 1) {
                                        onAction(StudioAction.SetPatternBars(track.id, pattern.bars - 1))
                                    }
                                },
                                textColor = if (pattern.bars > 1) {
                                    NjStudioAccent.copy(alpha = 0.7f)
                                } else {
                                    NjMuted2.copy(alpha = 0.3f)
                                }
                            )
                            Text(
                                text = "${pattern.bars} bar${if (pattern.bars > 1) "s" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                color = NjStudioAccent.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            NjButton(
                                text = "+",
                                onClick = {
                                    if (pattern.bars < 8) {
                                        onAction(StudioAction.SetPatternBars(track.id, pattern.bars + 1))
                                    }
                                },
                                textColor = if (pattern.bars < 8) {
                                    NjStudioAccent.copy(alpha = 0.7f)
                                } else {
                                    NjMuted2.copy(alpha = 0.3f)
                                }
                            )
                        }

                        Spacer(Modifier.width(8.dp))
                    }

                    // Push action buttons to the right
                    Box(Modifier.weight(1f))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NjButton(
                            text = "Edit",
                            icon = Icons.Filled.Edit,
                            onClick = {
                                onAction(StudioAction.OpenDrumEditor(track.id))
                            },
                            textColor = NjStudioAccent.copy(alpha = 0.8f)
                        )
                        NjButton(
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
                        NjButton(
                            text = "Delete",
                            icon = Icons.Filled.Delete,
                            onClick = {
                                onAction(StudioAction.ConfirmDeleteTrack(track.id))
                            },
                            textColor = NjError.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Pattern editor grid
            if (pattern != null) {
                DrumPatternEditor(
                    trackId = track.id,
                    pattern = pattern,
                    onAction = onAction,
                    beatsPerBar = beatsPerBar
                )
            }
        }
    }
}
