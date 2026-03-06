package com.example.nightjar.ui.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjBg
import com.example.nightjar.ui.theme.NjMuted
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjOnBg
import com.example.nightjar.ui.theme.NjRecordCoral
import com.example.nightjar.ui.theme.NjSurface2

/** A General MIDI instrument with display metadata. */
data class GmInstrument(
    val program: Int,
    val name: String,
    val category: String,
    val isCurated: Boolean = false
)

/** Categories for grouping instruments. */
private val CATEGORY_ORDER = listOf(
    "Piano", "Keys", "Guitar", "Bass", "Strings",
    "Brass", "Woodwind", "Synth", "Pads", "Chromatic Perc", "Organ",
    "Ensemble", "Ethnic", "Percussive", "Sound Effects", "Pipe"
)

/** Complete General MIDI instrument list (programs 0-127). */
val GM_INSTRUMENTS = listOf(
    // Piano (0-7)
    GmInstrument(0, "Acoustic Grand Piano", "Piano", isCurated = true),
    GmInstrument(1, "Bright Acoustic Piano", "Piano"),
    GmInstrument(2, "Electric Grand Piano", "Piano"),
    GmInstrument(3, "Honky-Tonk Piano", "Piano"),
    GmInstrument(4, "Electric Piano 1", "Keys", isCurated = true),
    GmInstrument(5, "Electric Piano 2", "Keys"),
    GmInstrument(6, "Harpsichord", "Keys"),
    GmInstrument(7, "Clavinet", "Keys", isCurated = true),
    // Chromatic Percussion (8-15)
    GmInstrument(8, "Celesta", "Chromatic Perc"),
    GmInstrument(9, "Glockenspiel", "Chromatic Perc"),
    GmInstrument(10, "Music Box", "Chromatic Perc", isCurated = true),
    GmInstrument(11, "Vibraphone", "Chromatic Perc", isCurated = true),
    GmInstrument(12, "Marimba", "Chromatic Perc"),
    GmInstrument(13, "Xylophone", "Chromatic Perc"),
    GmInstrument(14, "Tubular Bells", "Chromatic Perc"),
    GmInstrument(15, "Dulcimer", "Chromatic Perc"),
    // Organ (16-23)
    GmInstrument(16, "Drawbar Organ", "Organ"),
    GmInstrument(17, "Percussive Organ", "Organ"),
    GmInstrument(18, "Rock Organ", "Organ", isCurated = true),
    GmInstrument(19, "Church Organ", "Organ"),
    GmInstrument(20, "Reed Organ", "Organ"),
    GmInstrument(21, "Accordion", "Organ"),
    GmInstrument(22, "Harmonica", "Organ"),
    GmInstrument(23, "Tango Accordion", "Organ"),
    // Guitar (24-31)
    GmInstrument(24, "Acoustic Guitar (Nylon)", "Guitar", isCurated = true),
    GmInstrument(25, "Acoustic Guitar (Steel)", "Guitar", isCurated = true),
    GmInstrument(26, "Electric Guitar (Jazz)", "Guitar"),
    GmInstrument(27, "Electric Guitar (Clean)", "Guitar", isCurated = true),
    GmInstrument(28, "Electric Guitar (Muted)", "Guitar"),
    GmInstrument(29, "Overdriven Guitar", "Guitar", isCurated = true),
    GmInstrument(30, "Distortion Guitar", "Guitar"),
    GmInstrument(31, "Guitar Harmonics", "Guitar"),
    // Bass (32-39)
    GmInstrument(32, "Acoustic Bass", "Bass", isCurated = true),
    GmInstrument(33, "Electric Bass (Finger)", "Bass", isCurated = true),
    GmInstrument(34, "Electric Bass (Pick)", "Bass"),
    GmInstrument(35, "Fretless Bass", "Bass"),
    GmInstrument(36, "Slap Bass 1", "Bass"),
    GmInstrument(37, "Slap Bass 2", "Bass"),
    GmInstrument(38, "Synth Bass 1", "Bass", isCurated = true),
    GmInstrument(39, "Synth Bass 2", "Bass"),
    // Strings (40-47)
    GmInstrument(40, "Violin", "Strings", isCurated = true),
    GmInstrument(41, "Viola", "Strings"),
    GmInstrument(42, "Cello", "Strings", isCurated = true),
    GmInstrument(43, "Contrabass", "Strings"),
    GmInstrument(44, "Tremolo Strings", "Strings"),
    GmInstrument(45, "Pizzicato Strings", "Strings"),
    GmInstrument(46, "Orchestral Harp", "Strings"),
    GmInstrument(47, "Timpani", "Strings"),
    // Ensemble (48-55)
    GmInstrument(48, "String Ensemble 1", "Ensemble", isCurated = true),
    GmInstrument(49, "String Ensemble 2", "Ensemble"),
    GmInstrument(50, "Synth Strings 1", "Ensemble"),
    GmInstrument(51, "Synth Strings 2", "Ensemble"),
    GmInstrument(52, "Choir Aahs", "Ensemble"),
    GmInstrument(53, "Voice Oohs", "Ensemble"),
    GmInstrument(54, "Synth Choir", "Ensemble"),
    GmInstrument(55, "Orchestra Hit", "Ensemble"),
    // Brass (56-63)
    GmInstrument(56, "Trumpet", "Brass", isCurated = true),
    GmInstrument(57, "Trombone", "Brass"),
    GmInstrument(58, "Tuba", "Brass"),
    GmInstrument(59, "Muted Trumpet", "Brass"),
    GmInstrument(60, "French Horn", "Brass"),
    GmInstrument(61, "Brass Section", "Brass", isCurated = true),
    GmInstrument(62, "Synth Brass 1", "Brass"),
    GmInstrument(63, "Synth Brass 2", "Brass"),
    // Reed (64-71)
    GmInstrument(64, "Soprano Sax", "Woodwind"),
    GmInstrument(65, "Alto Sax", "Woodwind", isCurated = true),
    GmInstrument(66, "Tenor Sax", "Woodwind"),
    GmInstrument(67, "Baritone Sax", "Woodwind"),
    GmInstrument(68, "Oboe", "Woodwind"),
    GmInstrument(69, "English Horn", "Woodwind"),
    GmInstrument(70, "Bassoon", "Woodwind"),
    GmInstrument(71, "Clarinet", "Woodwind"),
    // Pipe (72-79)
    GmInstrument(72, "Piccolo", "Pipe"),
    GmInstrument(73, "Flute", "Pipe", isCurated = true),
    GmInstrument(74, "Recorder", "Pipe"),
    GmInstrument(75, "Pan Flute", "Pipe"),
    GmInstrument(76, "Blown Bottle", "Pipe"),
    GmInstrument(77, "Shakuhachi", "Pipe"),
    GmInstrument(78, "Whistle", "Pipe"),
    GmInstrument(79, "Ocarina", "Pipe"),
    // Synth Lead (80-87)
    GmInstrument(80, "Lead 1 (Square)", "Synth", isCurated = true),
    GmInstrument(81, "Lead 2 (Sawtooth)", "Synth", isCurated = true),
    GmInstrument(82, "Lead 3 (Calliope)", "Synth"),
    GmInstrument(83, "Lead 4 (Chiff)", "Synth"),
    GmInstrument(84, "Lead 5 (Charang)", "Synth"),
    GmInstrument(85, "Lead 6 (Voice)", "Synth"),
    GmInstrument(86, "Lead 7 (Fifths)", "Synth"),
    GmInstrument(87, "Lead 8 (Bass + Lead)", "Synth"),
    // Synth Pad (88-95)
    GmInstrument(88, "Pad 1 (New Age)", "Pads", isCurated = true),
    GmInstrument(89, "Pad 2 (Warm)", "Pads", isCurated = true),
    GmInstrument(90, "Pad 3 (Polysynth)", "Pads"),
    GmInstrument(91, "Pad 4 (Choir)", "Pads"),
    GmInstrument(92, "Pad 5 (Bowed)", "Pads"),
    GmInstrument(93, "Pad 6 (Metallic)", "Pads"),
    GmInstrument(94, "Pad 7 (Halo)", "Pads"),
    GmInstrument(95, "Pad 8 (Sweep)", "Pads"),
    // Synth Effects (96-103)
    GmInstrument(96, "FX 1 (Rain)", "Sound Effects"),
    GmInstrument(97, "FX 2 (Soundtrack)", "Sound Effects"),
    GmInstrument(98, "FX 3 (Crystal)", "Sound Effects"),
    GmInstrument(99, "FX 4 (Atmosphere)", "Sound Effects"),
    GmInstrument(100, "FX 5 (Brightness)", "Sound Effects"),
    GmInstrument(101, "FX 6 (Goblins)", "Sound Effects"),
    GmInstrument(102, "FX 7 (Echoes)", "Sound Effects"),
    GmInstrument(103, "FX 8 (Sci-Fi)", "Sound Effects"),
    // Ethnic (104-111)
    GmInstrument(104, "Sitar", "Ethnic"),
    GmInstrument(105, "Banjo", "Ethnic"),
    GmInstrument(106, "Shamisen", "Ethnic"),
    GmInstrument(107, "Koto", "Ethnic"),
    GmInstrument(108, "Kalimba", "Ethnic"),
    GmInstrument(109, "Bagpipe", "Ethnic"),
    GmInstrument(110, "Fiddle", "Ethnic"),
    GmInstrument(111, "Shanai", "Ethnic"),
    // Percussive (112-119)
    GmInstrument(112, "Tinkle Bell", "Percussive"),
    GmInstrument(113, "Agogo", "Percussive"),
    GmInstrument(114, "Steel Drums", "Percussive"),
    GmInstrument(115, "Woodblock", "Percussive"),
    GmInstrument(116, "Taiko Drum", "Percussive"),
    GmInstrument(117, "Melodic Tom", "Percussive"),
    GmInstrument(118, "Synth Drum", "Percussive"),
    GmInstrument(119, "Reverse Cymbal", "Percussive"),
    // Sound Effects (120-127)
    GmInstrument(120, "Guitar Fret Noise", "Sound Effects"),
    GmInstrument(121, "Breath Noise", "Sound Effects"),
    GmInstrument(122, "Seashore", "Sound Effects"),
    GmInstrument(123, "Bird Tweet", "Sound Effects"),
    GmInstrument(124, "Telephone Ring", "Sound Effects"),
    GmInstrument(125, "Helicopter", "Sound Effects"),
    GmInstrument(126, "Applause", "Sound Effects"),
    GmInstrument(127, "Gunshot", "Sound Effects")
)

/** Look up a GM instrument by program number. */
fun gmInstrumentName(program: Int): String =
    GM_INSTRUMENTS.getOrNull(program)?.name ?: "Program $program"

/**
 * Bottom sheet instrument picker with curated grid and expandable full list.
 *
 * Tapping an instrument calls [onPreview] for audition (short note on preview channel)
 * and [onSelect] to confirm the choice.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InstrumentPicker(
    currentProgram: Int,
    onSelect: (Int) -> Unit,
    onPreview: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    var showAllInstruments by remember { mutableStateOf(false) }

    val curatedInstruments = remember { GM_INSTRUMENTS.filter { it.isCurated } }
    val curatedByCategory = remember {
        curatedInstruments.groupBy { it.category }
            .toSortedMap(compareBy { CATEGORY_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NjBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .verticalScroll(scrollState)
        ) {
            Text(
                text = "Choose Instrument",
                style = MaterialTheme.typography.titleMedium,
                color = NjOnBg
            )

            Spacer(Modifier.height(16.dp))

            // Curated grid grouped by category
            curatedByCategory.forEach { (category, instruments) ->
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelSmall,
                    color = NjMuted,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    instruments.forEach { instrument ->
                        InstrumentChip(
                            name = instrument.name,
                            isSelected = instrument.program == currentProgram,
                            onClick = {
                                onPreview(instrument.program)
                                onSelect(instrument.program)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(8.dp))

            // "More Instruments" expandable section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAllInstruments = !showAllInstruments }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "More Instruments",
                    style = MaterialTheme.typography.titleSmall,
                    color = NjAccent
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (showAllInstruments) Icons.Filled.ExpandLess
                    else Icons.Filled.ExpandMore,
                    contentDescription = if (showAllInstruments) "Collapse" else "Expand",
                    tint = NjAccent,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = showAllInstruments) {
                Column {
                    val allByCategory = remember {
                        GM_INSTRUMENTS.groupBy { it.category }
                            .toSortedMap(compareBy {
                                CATEGORY_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE
                            })
                    }

                    allByCategory.forEach { (category, instruments) ->
                        Text(
                            text = category,
                            style = MaterialTheme.typography.labelSmall,
                            color = NjMuted,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )

                        instruments.forEach { instrument ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPreview(instrument.program)
                                        onSelect(instrument.program)
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = instrument.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (instrument.program == currentProgram) NjRecordCoral
                                    else NjOnBg
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InstrumentChip(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) NjRecordCoral.copy(alpha = 0.2f) else NjSurface2
    val textColor = if (isSelected) NjRecordCoral else NjOnBg
    val borderShape = RoundedCornerShape(8.dp)

    Text(
        text = name,
        style = MaterialTheme.typography.bodySmall,
        color = textColor,
        modifier = Modifier
            .clip(borderShape)
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
