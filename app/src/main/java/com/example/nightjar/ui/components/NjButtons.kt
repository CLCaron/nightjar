package com.example.nightjar.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Filled button with the app's primary color. The default call-to-action style. */
@Composable
fun NjPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fullWidth: Boolean = true,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
    colors: ButtonColors? = null
) {
    val base = if (fullWidth) Modifier.fillMaxWidth() else Modifier
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = base
            .then(modifier)
            .heightIn(min = minHeight),
        shape = RoundedCornerShape(14.dp),
        colors = colors ?: ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Outlined button for secondary actions (e.g. "Open Library", "Share"). */
@Composable
fun NjSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fullWidth: Boolean = true,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp
) {
    val base = if (fullWidth) Modifier.fillMaxWidth() else Modifier
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = base
            .then(modifier)
            .heightIn(min = minHeight),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
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
    fullWidth: Boolean = true,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp
) {
    val base = if (fullWidth) Modifier.fillMaxWidth() else Modifier
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = base
            .then(modifier)
            .heightIn(min = minHeight),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
            contentColor = MaterialTheme.colorScheme.error
        )
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
