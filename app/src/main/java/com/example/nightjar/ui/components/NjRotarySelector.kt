package com.example.nightjar.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightjar.ui.theme.IbmPlexMono
import com.example.nightjar.ui.theme.NjAmber
import com.example.nightjar.ui.theme.NjMuted
import com.example.nightjar.ui.theme.NjPanelInset

private val LeftChevron: ImageVector = Icons.Filled.KeyboardArrowLeft
private val RightChevron: ImageVector = Icons.Filled.KeyboardArrowRight

private const val DRAG_DETENT_DP = 28f

/**
 * Hardware-style horizontal rotary selector. A single recessed pill containing
 * left chevron, an LCD-style readout, and a right chevron. Tap a chevron to
 * step in that direction; horizontal drag across the widget steps with
 * haptic detents every [DRAG_DETENT_DP] dp. Wraps around at the ends.
 *
 * Used in place of a vertical knob when the value space is small (3-5 items)
 * and a knob's drag distance per detent would feel sluggish.
 */
@Composable
fun <T> NjRotarySelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    caption: String? = null
) {
    val view = LocalView.current
    val density = LocalDensity.current

    val currentOptions by rememberUpdatedState(options)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    fun stepBy(direction: Int) {
        val opts = currentOptions
        if (opts.isEmpty()) return
        val idx = opts.indexOf(currentSelected).coerceAtLeast(0)
        val n = opts.size
        val nextIdx = ((idx + direction) % n + n) % n
        if (nextIdx != idx) {
            currentOnSelect(opts[nextIdx])
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .height(36.dp)
                .widthIn(min = 96.dp)
                .clip(RoundedCornerShape(6.dp))
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
                }
                .pointerInput(Unit) {
                    val detentPx = with(density) { DRAG_DETENT_DP.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val firstDrag = awaitHorizontalTouchSlopOrCancellation(down.id) { c, _ ->
                            c.consume()
                        }
                        if (firstDrag == null) {
                            // Pure tap -- decide which side based on x position.
                            val x = down.position.x
                            val midX = size.width / 2f
                            stepBy(if (x < midX) -1 else +1)
                            return@awaitEachGesture
                        }
                        // Drag -- step at every DRAG_DETENT_DP of accumulated motion.
                        var accum = 0f
                        firstDrag.consume()
                        accum += firstDrag.positionChange().x
                        horizontalDrag(firstDrag.id) { change ->
                            change.consume()
                            accum += change.positionChange().x
                            while (accum >= detentPx) {
                                stepBy(+1)
                                accum -= detentPx
                            }
                            while (accum <= -detentPx) {
                                stepBy(-1)
                                accum += detentPx
                            }
                        }
                    }
                }
        ) {
            Row(
                modifier = Modifier.fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = LeftChevron,
                        contentDescription = "Previous",
                        tint = NjAmber.copy(alpha = 0.75f),
                        modifier = Modifier.padding(2.dp)
                    )
                }
                Box(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(selected),
                        fontFamily = IbmPlexMono,
                        fontSize = 11.sp,
                        color = NjAmber.copy(alpha = 0.9f),
                        letterSpacing = 0.6.sp,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = RightChevron,
                        contentDescription = "Next",
                        tint = NjAmber.copy(alpha = 0.75f),
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = caption,
                fontFamily = IbmPlexMono,
                fontSize = 9.sp,
                color = NjMuted,
                letterSpacing = 0.5.sp
            )
        }
    }
}

