package com.example.nightjar.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Piano
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nightjar.ui.components.NjLedDot
import com.example.nightjar.ui.components.NjRecessedPanel
import com.example.nightjar.ui.theme.LocalNjColors
import com.example.nightjar.ui.theme.NjAmber
import com.example.nightjar.ui.theme.NjBg
import com.example.nightjar.ui.theme.NjMuted
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjOnBg
import com.example.nightjar.ui.theme.NjOutline
import com.example.nightjar.ui.theme.NjStarlight
import com.example.nightjar.ui.theme.NjSurface

/** SavedStateHandle keys for passing the picker selection back to StudioScreen. */
object InstrumentPickerKeys {
    const val TRACK_ID = "instrument_picker_track_id"
    const val PROGRAM = "instrument_picker_program"
}

/**
 * Full-screen MIDI instrument picker. Hardware patch-browser aesthetic:
 * vertical category sidebar on the left, scrollable card grid on the right,
 * LCD readout strips above and below.
 *
 * Tap a card = preview + commit. Selection is reported up via [onProgramSelected]
 * (which writes to the parent destination's savedStateHandle); StudioScreen
 * dispatches the actual SetMidiInstrument action when it sees the change.
 */
@Composable
fun InstrumentPickerScreen(
    onBack: () -> Unit,
    onProgramSelected: (trackId: Long, program: Int) -> Unit,
    vm: InstrumentPickerViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NjBg)
            .navigationBarsPadding()
    ) {
        PickerHeader(
            trackName = state.trackName,
            trackIndex = state.trackIndex,
            onBack = onBack
        )

        Spacer(Modifier.height(8.dp))

        SelectedPatchReadout(
            program = state.selectedProgram,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.weight(1f)) {
            CategorySidebar(
                selected = state.selectedCategory,
                onSelect = vm::selectCategory
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(curatedPatchesIn(state.selectedCategory), key = { it.program }) { patch ->
                    PatchCard(
                        patch = patch,
                        isSelected = patch.program == state.selectedProgram,
                        onClick = {
                            vm.selectProgram(patch.program)
                            onProgramSelected(state.trackId, patch.program)
                        }
                    )
                }
            }
        }

    }
}

@Composable
private fun PickerHeader(
    trackName: String,
    trackIndex: Int,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = NjStarlight.copy(alpha = 0.7f),
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onBack)
                .padding(2.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Instrument",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(2.dp))
            val subtitle = if (trackName.isNotBlank()) {
                "${trackName.uppercase()} · TRACK $trackIndex"
            } else {
                "TRACK $trackIndex"
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = NjMuted,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun SelectedPatchReadout(
    program: Int,
    modifier: Modifier = Modifier
) {
    val patch = curatedPatchFor(program)
    val number = patch?.position ?: program
    val code = patch?.code ?: "PROG $program"
    val descriptor = patch?.descriptor.orEmpty()

    NjRecessedPanel(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "BANK A · %03d".format(number),
                style = MaterialTheme.typography.labelSmall,
                color = NjMuted,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(4.dp))
            val readout = if (descriptor.isNotEmpty()) {
                "$code  ·  ${descriptor.uppercase()}"
            } else {
                code
            }
            Text(
                text = readout,
                style = MaterialTheme.typography.titleMedium,
                color = NjAmber,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun CategorySidebar(
    selected: PatchCategory,
    onSelect: (PatchCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(72.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        PatchCategory.entries.forEach { category ->
            CategoryTab(
                category = category,
                isSelected = category == selected,
                onClick = { onSelect(category) }
            )
        }
    }
}

@Composable
private fun CategoryTab(
    category: PatchCategory,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val pressedBody = LocalNjColors.current.pressedBody
    val tint = if (isSelected) NjAmber else NjMuted
    val labelColor = if (isSelected) NjOnBg else NjMuted
    val bgColor = if (isSelected) pressedBody else NjBg
    val shape = RoundedCornerShape(4.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor)
            .drawWithContent {
                drawContent()
                if (isSelected) {
                    val sw = 1.dp.toPx()
                    // Latched bevel: dark top + left, light bottom + right
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
                }
            }
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CategoryIcon(category, tint)
        Text(
            text = category.label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun CategoryIcon(category: PatchCategory, tint: androidx.compose.ui.graphics.Color) {
    val size = 22.dp
    when (category) {
        PatchCategory.KEYS -> Icon(Icons.Outlined.Piano, null, Modifier.size(size), tint = tint)
        PatchCategory.PAD  -> Icon(Icons.Outlined.GraphicEq, null, Modifier.size(size), tint = tint)
        PatchCategory.LEAD -> Icon(Icons.Outlined.Bolt, null, Modifier.size(size), tint = tint)
        PatchCategory.FX   -> Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(size), tint = tint)
        PatchCategory.OTHER -> Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(size), tint = tint)
        PatchCategory.STR   -> StringsIconShape(tint, size)
        PatchCategory.BRASS -> BrassIconShape(tint, size)
        PatchCategory.BASS  -> BassIconShape(tint, size)
        PatchCategory.DRUM  -> DrumIconShape(tint, size)
    }
}

@Composable
private fun StringsIconShape(tint: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val stroke = 1.5.dp.toPx()
        val pad = this.size.width * 0.18f
        val innerW = this.size.width - 2 * pad
        val gap = innerW / 3f
        for (i in 0..3) {
            val x = pad + i * gap
            drawLine(
                color = tint,
                start = androidx.compose.ui.geometry.Offset(x, pad),
                end = androidx.compose.ui.geometry.Offset(x, this.size.height - pad),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}

@Composable
private fun BrassIconShape(tint: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val stroke = 1.5.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val pad = w * 0.18f
        // Trumpet bell: flared trapezoid opening to the right
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(pad, h * 0.42f)
            lineTo(w - pad, pad)
            lineTo(w - pad, h - pad)
            lineTo(pad, h * 0.58f)
            close()
        }
        drawPath(
            path = path,
            color = tint,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        )
    }
}

@Composable
private fun BassIconShape(tint: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val stroke = 2.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val pad = w * 0.15f
        val cy = h / 2f
        val amp = h * 0.32f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(pad, cy)
            quadraticTo(w * 0.32f, cy - amp, w * 0.5f, cy)
            quadraticTo(w * 0.68f, cy + amp, w - pad, cy)
        }
        drawPath(
            path = path,
            color = tint,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
    }
}

@Composable
private fun DrumIconShape(tint: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val stroke = 1.5.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val pad = w * 0.18f
        val drumW = w - 2 * pad
        val drumH = h * 0.46f
        val topY = h * 0.28f
        // Drum head ellipse (top oval)
        drawOval(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(pad, topY),
            size = androidx.compose.ui.geometry.Size(drumW, drumH * 0.4f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )
        // Side walls of the drum body
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(pad, topY + drumH * 0.2f),
            end = androidx.compose.ui.geometry.Offset(pad, topY + drumH * 0.85f),
            strokeWidth = stroke
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w - pad, topY + drumH * 0.2f),
            end = androidx.compose.ui.geometry.Offset(w - pad, topY + drumH * 0.85f),
            strokeWidth = stroke
        )
        // Bottom curve
        val bottomPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(pad, topY + drumH * 0.85f)
            quadraticTo(w / 2f, topY + drumH * 1.15f, w - pad, topY + drumH * 0.85f)
        }
        drawPath(
            path = bottomPath,
            color = tint,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )
    }
}

@Composable
private fun PatchCard(
    patch: CuratedPatch,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val pressedBody = LocalNjColors.current.pressedBody
    val borderColor = if (isSelected) NjAmber else NjOutline
    val borderWidth = if (isSelected) 1.5.dp else 1.dp
    val bgColor = if (isSelected) pressedBody else NjSurface
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(shape)
            .background(bgColor)
            .drawWithContent {
                drawContent()
                val sw = 1.dp.toPx()
                if (isSelected) {
                    // Latched: dark top + left, light bottom + right (set into the body)
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
                    // Raised: light top + left, dark bottom + right
                    drawLine(
                        Color.White.copy(alpha = 0.06f),
                        Offset(0f, sw / 2),
                        Offset(size.width, sw / 2),
                        sw
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.04f),
                        Offset(sw / 2, 0f),
                        Offset(sw / 2, size.height),
                        sw
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.30f),
                        Offset(0f, size.height - sw / 2),
                        Offset(size.width, size.height - sw / 2),
                        sw
                    )
                    drawLine(
                        Color.Black.copy(alpha = 0.16f),
                        Offset(size.width - sw / 2, 0f),
                        Offset(size.width - sw / 2, size.height),
                        sw
                    )
                }
            }
            .border(borderWidth, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // Number, top-left
        Text(
            text = "%03d".format(patch.position),
            style = MaterialTheme.typography.labelSmall,
            color = NjMuted2,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.TopStart)
        )

        // LED status dot, top-right (lit only when selected)
        if (isSelected) {
            NjLedDot(
                isLit = true,
                litColor = NjAmber,
                size = 8.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 1.dp, end = 1.dp)
            )
        }

        // Code + descriptor, vertically centered
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(top = 6.dp)
        ) {
            Text(
                text = patch.code,
                style = MaterialTheme.typography.titleMedium,
                color = NjOnBg,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = patch.descriptor,
                style = MaterialTheme.typography.bodySmall,
                color = NjMuted,
                fontSize = 11.sp
            )
        }

    }
}

