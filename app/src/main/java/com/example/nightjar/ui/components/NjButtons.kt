package com.example.nightjar.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.example.nightjar.ui.components.collectIsPressedWithMinDuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Filled button with the app's primary color. The default call-to-action style. */
@Composable
fun NjPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fullWidth: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
    containerColor: Color? = null,
    contentColor: Color? = null
) {
    val bg = containerColor ?: MaterialTheme.colorScheme.primary
    val fg = contentColor ?: MaterialTheme.colorScheme.onPrimary
    NjButtonBase(onClick, modifier, fullWidth, enabled, minHeight, bg, fg) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Beveled button for secondary actions (e.g. "Open Library", "Share"). */
@Composable
fun NjSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fullWidth: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp
) {
    NjButtonBase(
        onClick, modifier, fullWidth, enabled, minHeight,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Error-colored button for destructive actions (e.g. "Delete"). */
@Composable
fun NjDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fullWidth: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp
) {
    NjButtonBase(
        onClick, modifier, fullWidth, enabled, minHeight,
        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
        contentColor = MaterialTheme.colorScheme.error
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun NjButtonBase(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fullWidth: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
    containerColor: Color,
    contentColor: Color,
    content: @Composable () -> Unit
) {
    val bg = if (enabled) containerColor else containerColor.copy(alpha = 0.38f)
    val fg = if (enabled) contentColor else contentColor.copy(alpha = 0.38f)
    val base = if (fullWidth) Modifier.fillMaxWidth() else Modifier
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedWithMinDuration()

    CompositionLocalProvider(LocalContentColor provides fg) {
        Row(
            modifier = base
                .then(modifier)
                .heightIn(min = minHeight)
                .background(bg)
                .njBevel(isPressed)
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                )
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}
