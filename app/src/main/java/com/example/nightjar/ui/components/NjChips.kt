package com.example.nightjar.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Pill-shaped tag chip with an optional remove glyph (✕). Used in the Overview tag list. */
@Composable
fun NjTagChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showRemoveGlyph: Boolean = true
) {
    val container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    val border = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val content = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
    val glyph = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge
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
}

/** Selectable filter chip. Highlighted when [selected] is true. Used in the Library tag/sort bars. */
@Composable
fun NjSelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    val border = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
    }

    val content = if (selected) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    }

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, border)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
