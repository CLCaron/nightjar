package com.example.nightjar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.nightjar.ui.theme.NjMidnight2
import com.example.nightjar.ui.theme.NjMuted2
import com.example.nightjar.ui.theme.NjOutline
import kotlin.math.cos
import kotlin.math.sin

private const val SWEEP_DEGREES = 270f
private const val START_ANGLE = -135f // 7 o'clock (0° = 12 o'clock, CW positive)
private const val RIDGE_COUNT = 36
private const val DETENT_COUNT = 20

/**
 * Rotary knob control with ridged edges — retro hardware aesthetic.
 *
 * Gesture: vertical drag (up = increase, down = decrease).
 * Haptic detents at every 5% of the range for a tactile "clicking" feel,
 * like the ridged knobs on a MIDI controller.
 */
@Composable
fun NjKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    knobSize: Dp = 44.dp,
    label: String? = null
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val dragSensitivityPx = with(density) { 200.dp.toPx() }

    // rememberUpdatedState so the pointerInput lambda always reads
    // the latest value/callback without restarting the gesture detector.
    val currentValue = rememberUpdatedState(value)
    val currentOnValueChange = rememberUpdatedState(onValueChange)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            modifier = Modifier
                .size(knobSize)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput

                    // Local to the gesture scope — survives across drag events
                    // within a single gesture, reset on each new drag.
                    var startValue = 0f
                    var accumulated = 0f
                    var lastDetent = 0

                    detectVerticalDragGestures(
                        onDragStart = {
                            startValue = currentValue.value
                            accumulated = 0f
                            lastDetent = (startValue * DETENT_COUNT).toInt()
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            accumulated += -dragAmount
                            val newValue = (startValue + accumulated / dragSensitivityPx)
                                .coerceIn(0f, 1f)

                            // Haptic detent — fires each time we cross a 5% boundary
                            val newDetent = (newValue * DETENT_COUNT).toInt()
                            if (newDetent != lastDetent) {
                                lastDetent = newDetent
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }

                            currentOnValueChange.value(newValue)
                        }
                    )
                }
        ) {
            val canvasSize = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            val bodyRadius = canvasSize * 0.38f
            val ridgeInner = bodyRadius - 1.dp.toPx()
            val ridgeOuter = canvasSize * 0.48f
            val indicatorStart = bodyRadius * 0.25f
            val indicatorEnd = bodyRadius * 0.72f
            val ridgeStroke = 1.5f.dp.toPx()
            val indicatorStroke = 2.dp.toPx()

            val currentAngle = START_ANGLE + SWEEP_DEGREES * value.coerceIn(0f, 1f)

            // --- Ridges (knurled edge) ---
            val ridgeColor = if (enabled) NjMuted2.copy(alpha = 0.45f)
                else NjMuted2.copy(alpha = 0.2f)

            for (i in 0 until RIDGE_COUNT) {
                val angle = (360f / RIDGE_COUNT) * i
                val rad = Math.toRadians(angle.toDouble())
                val sinA = sin(rad).toFloat()
                val cosA = cos(rad).toFloat()
                drawLine(
                    color = ridgeColor,
                    start = Offset(
                        center.x + ridgeInner * sinA,
                        center.y - ridgeInner * cosA
                    ),
                    end = Offset(
                        center.x + ridgeOuter * sinA,
                        center.y - ridgeOuter * cosA
                    ),
                    strokeWidth = ridgeStroke
                )
            }

            // --- Knob body ---
            drawCircle(
                color = NjMidnight2,
                radius = bodyRadius,
                center = center
            )

            // --- Border ring ---
            drawCircle(
                color = NjOutline,
                radius = bodyRadius,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            // --- Indicator line ---
            val indicatorRad = Math.toRadians(currentAngle.toDouble())
            val sinI = sin(indicatorRad).toFloat()
            val cosI = cos(indicatorRad).toFloat()

            drawLine(
                color = if (enabled) Color.White.copy(alpha = 0.85f)
                    else Color.White.copy(alpha = 0.3f),
                start = Offset(
                    center.x + indicatorStart * sinI,
                    center.y - indicatorStart * cosI
                ),
                end = Offset(
                    center.x + indicatorEnd * sinI,
                    center.y - indicatorEnd * cosI
                ),
                strokeWidth = indicatorStroke,
                cap = StrokeCap.Round
            )
        }

        if (label != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}
