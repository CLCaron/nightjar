package com.example.nightjar.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.nightjar.ui.components.NjStarTouchIndication

/** Applies the Nightjar Material 3 color scheme, typography, and custom palette. */
@Composable
fun NightjarTheme(
    palette: NjColors = IndigoPalette,
    content: @Composable () -> Unit
) {
    val colorScheme = if (palette.isDark) darkColorScheme(
        primary = palette.primary,
        secondary = palette.primary2,
        tertiary = palette.accent,
        background = palette.bg,
        surface = palette.surface,
        surfaceVariant = palette.surface2,
        onPrimary = palette.onBg,
        onSecondary = palette.onBg,
        onTertiary = palette.bg,
        onBackground = palette.onBg,
        onSurface = palette.onBg,
        onSurfaceVariant = palette.onBg,
        outline = palette.outline,
        error = NjError,
        onError = palette.bg
    ) else lightColorScheme(
        primary = palette.primary,
        secondary = palette.primary2,
        tertiary = palette.accent,
        background = palette.bg,
        surface = palette.surface,
        surfaceVariant = palette.surface2,
        onPrimary = palette.onBg,
        onSecondary = palette.onBg,
        onTertiary = palette.bg,
        onBackground = palette.onBg,
        onSurface = palette.onBg,
        onSurfaceVariant = palette.onBg,
        outline = palette.outline,
        error = NjError,
        onError = palette.bg
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        CompositionLocalProvider(
            LocalNjColors provides palette,
            LocalIndication provides NjStarTouchIndication,
            content = content
        )
    }
}
