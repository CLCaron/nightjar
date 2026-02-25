package com.example.nightjar.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.example.nightjar.ui.components.collectIsPressedWithMinDuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Compact inline action button (e.g. "Favorite", "Studio") with subtle surface styling. */
@Composable
fun NjInlineAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedWithMinDuration()

    val baseAlpha = if (emphasized) 0.12f else 0.10f
    val pressedAlpha = baseAlpha + 0.14f
    val container = if (emphasized) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isPressed) pressedAlpha else baseAlpha)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isPressed) pressedAlpha else baseAlpha)
    }

    val content = if (emphasized) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = content
        )
    }
}
