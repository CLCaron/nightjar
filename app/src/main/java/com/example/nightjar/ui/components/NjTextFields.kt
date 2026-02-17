package com.example.nightjar.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Styled outlined text field with Nightjar's color scheme and rounded corners. */
@Composable
fun NjTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val shape = RoundedCornerShape(14.dp)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            // Containers
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),

            // Borders
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.20f),

            // Labels
            focusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),

            // Cursor
            cursorColor = MaterialTheme.colorScheme.primary,

            // Focus
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f),

            // Placeholders
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f)
        )
    )
}
