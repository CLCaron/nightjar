package com.example.nightjar.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.example.nightjar.ui.components.collectIsPressedWithMinDuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Tag chip with an optional remove glyph (✕). Used in the Overview tag list. */
@Composable
fun NjTagChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showRemoveGlyph: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedWithMinDuration()
    val container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    val content = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
    val glyph = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    Row(
        modifier = modifier
            .background(container)
            .njBevel(isPressed)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = content
        )

        if (showRemoveGlyph) {
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelLarge,
                color = glyph
            )
        }
    }
}

/** Selectable filter chip. Highlighted when [selected] is true. Used in the Library tag/sort bars. */
@Composable
fun NjSelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedWithMinDuration()

    val container = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    val content = if (selected) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    }

    Row(
        modifier = modifier
            .background(container)
            .njBevel(isPressed)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = content
        )
    }
}
