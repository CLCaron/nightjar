package com.example.nightjar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = NjPrimary,
    secondary = NjPrimary2,
    tertiary = NjAccent,

    background = NjBg,
    surface = NjSurface,
    surfaceVariant = NjSurface2,

    onPrimary = NjOnSurface,
    onSecondary = NjOnSurface,
    onTertiary = NjBg,
    onBackground = NjOnBg,
    onSurface = NjOnSurface,
    onSurfaceVariant = NjOnSurface,

    outline = NjOutline,
    error = NjError,
    onError = NjBg
)

private val LightColorScheme = lightColorScheme(
    primary = NjPrimary,
    secondary = NjPrimary2,
    tertiary = NjAccent
)

/** Applies the Nightjar Material 3 color scheme and typography. Defaults to dark theme. */
@Composable
fun NightjarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
