package com.example.nightjar.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.example.nightjar.ui.theme.LocalNjColors
import kotlinx.coroutines.launch

/** Deepest press color -- reads from active palette. */
internal val DeepPressColor: Color
    @Composable @ReadOnlyComposable get() = LocalNjColors.current.deepPress

/**
 * Observable state for a three-state mechanical latching toggle.
 *
 * Depth values:
 * - `0.0` raised (inactive)
 * - `0.5` latched (active, finger up)
 * - `1.0` deep press (finger down)
 *
 * [isVisuallyActive] is true whenever depth > 0.25, suitable for
 * driving LED glow and text color without the 1-frame flicker that
 * plagued previous attempts using `isActive` from ViewModel state.
 */
@Stable
class MechanicalToggleState internal constructor(
    internal val depthAnimatable: Animatable<Float, *>,
    val interactionSource: MutableInteractionSource
) {
    val depth: State<Float> get() = depthAnimatable.asState()
    val isVisuallyActive: Boolean get() = depthAnimatable.value > 0.25f
}

/**
 * Creates and remembers a [MechanicalToggleState] driven by [isActive].
 *
 * The key insight: a local `localActive` boolean flips synchronously
 * inside the interaction collector on Release, so the animation target
 * is never derived from the ViewModel's `isActive` on the release frame
 * (which arrives ~1 recomposition frame late). External state changes
 * (e.g. solo clearing another track) are reconciled in a separate
 * `LaunchedEffect(isActive)`.
 */
@Composable
fun rememberMechanicalToggleState(isActive: Boolean): MechanicalToggleState {
    val interactionSource = remember { MutableInteractionSource() }
    val animatable = remember { Animatable(if (isActive) 0.5f else 0f) }
    val state = remember { MechanicalToggleState(animatable, interactionSource) }

    // Local prediction of toggle state -- flipped synchronously on Release.
    val localActive = remember { mutableStateOf(isActive) }
    val fingerDown = remember { mutableStateOf(false) }

    // Collect press/release/cancel from the shared interaction source.
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    fingerDown.value = true
                    launch { animatable.animateTo(1.0f, tween(60)) }
                }
                is PressInteraction.Release -> {
                    fingerDown.value = false
                    val newActive = !localActive.value
                    localActive.value = newActive
                    launch {
                        animatable.animateTo(
                            if (newActive) 0.5f else 0f,
                            tween(120)
                        )
                    }
                }
                is PressInteraction.Cancel -> {
                    fingerDown.value = false
                    launch {
                        animatable.animateTo(
                            if (localActive.value) 0.5f else 0f,
                            tween(120)
                        )
                    }
                }
            }
        }
    }

    // Reconcile external state changes (e.g. solo cleared by another track).
    LaunchedEffect(isActive) {
        if (isActive != localActive.value) {
            localActive.value = isActive
            if (!fingerDown.value) {
                animatable.snapTo(if (isActive) 0.5f else 0f)
            }
            // If finger IS down, localActive is updated but the release
            // handler will use the new value -- no snap needed.
        }
    }

    return state
}
