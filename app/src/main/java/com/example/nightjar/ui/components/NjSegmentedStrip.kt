package com.example.nightjar.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightjar.ui.theme.IbmPlexMono
import com.example.nightjar.ui.theme.NjAmber
import com.example.nightjar.ui.theme.NjBg
import com.example.nightjar.ui.theme.NjMuted
import com.example.nightjar.ui.theme.NjOutline
import com.example.nightjar.ui.theme.NjPanelInset

/**
 * Segmented "pill rocker" strip. A single recessed pill split into N tappable
 * segments that share one container. Active segment shows an inset/pressed
 * body with the value text glowing in amber; inactive segments are flush
 * with the pill body and muted. Adjacent segments share a 1dp seam, no
 * outer padding between them.
 *
 * Use this for small, equal-weight discrete choices where a row of separate
 * NjButtons would let labels wrap or sizes drift (e.g. grid resolution
 * 1/4 1/8 1/16 1/32 -- "1/16" wrapped to two lines as a free-standing chip).
 */
@Composable
fun <T> NjSegmentedStrip(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp
) {
    val view = LocalView.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(NjPanelInset)
            .drawWithContent {
                drawContent()
                val sw = 1.dp.toPx()
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
                    Color.White.copy(alpha = 0.05f),
                    Offset(0f, size.height - sw / 2),
                    Offset(size.width, size.height - sw / 2),
                    sw
                )
                drawLine(
                    Color.White.copy(alpha = 0.03f),
                    Offset(size.width - sw / 2, 0f),
                    Offset(size.width - sw / 2, size.height),
                    sw
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        options.forEachIndexed { idx, option ->
            val isActive = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isActive) NjBg.copy(alpha = 0.7f) else Color.Transparent)
                    .clickable {
                        if (option != selected) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            onSelect(option)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option),
                    fontFamily = IbmPlexMono,
                    fontSize = 11.sp,
                    color = if (isActive) NjAmber.copy(alpha = 0.9f) else NjMuted,
                    letterSpacing = 0.4.sp,
                    maxLines = 1
                )
            }
            if (idx < options.size - 1) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(NjOutline.copy(alpha = 0.4f))
                )
            }
        }
    }
}
