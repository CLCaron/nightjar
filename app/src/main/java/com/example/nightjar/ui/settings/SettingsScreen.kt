package com.example.nightjar.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.nightjar.audio.ThemePreferences
import com.example.nightjar.ui.components.NjLedDot
import com.example.nightjar.ui.components.njGrain
import com.example.nightjar.ui.components.PressedBodyColor
import com.example.nightjar.ui.components.RaisedBodyColor
import com.example.nightjar.ui.components.collectIsPressedWithMinDuration
import com.example.nightjar.ui.theme.NjAccent
import com.example.nightjar.ui.theme.NjBg
import com.example.nightjar.ui.theme.NjColors
import com.example.nightjar.ui.theme.NjMuted
import com.example.nightjar.ui.theme.IndigoPalette
import com.example.nightjar.ui.theme.LemonCakePalette
import com.example.nightjar.ui.theme.WarmPlumPalette

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onThemeChanged: (String) -> Unit
) {
    val vm: SettingsViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NjBg)
            .njGrain(alpha = 0.015f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            com.example.nightjar.ui.components.NjTopBar(
                title = "Settings",
                onBack = onBack
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "THEME",
                style = MaterialTheme.typography.labelMedium,
                color = NjMuted,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ThemeOptionCard(
                    label = "Indigo",
                    palette = IndigoPalette,
                    isSelected = state.themeKey == ThemePreferences.DEFAULT_THEME,
                    onClick = {
                        vm.onAction(SettingsAction.SetTheme(ThemePreferences.DEFAULT_THEME))
                        onThemeChanged(ThemePreferences.DEFAULT_THEME)
                    }
                )

                ThemeOptionCard(
                    label = "Warm Plum",
                    palette = WarmPlumPalette,
                    isSelected = state.themeKey == ThemePreferences.WARM_PLUM,
                    onClick = {
                        vm.onAction(SettingsAction.SetTheme(ThemePreferences.WARM_PLUM))
                        onThemeChanged(ThemePreferences.WARM_PLUM)
                    }
                )

                ThemeOptionCard(
                    label = "Lemon Cake",
                    palette = LemonCakePalette,
                    isSelected = state.themeKey == ThemePreferences.LEMON_CAKE,
                    onClick = {
                        vm.onAction(SettingsAction.SetTheme(ThemePreferences.LEMON_CAKE))
                        onThemeChanged(ThemePreferences.LEMON_CAKE)
                    }
                )
            }
        }
    }
}

/** Tappable card showing theme name, color swatch dots, and a selection LED. */
@Composable
private fun ThemeOptionCard(
    label: String,
    palette: NjColors,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedWithMinDuration()
    val shape = RoundedCornerShape(4.dp)

    val bgColor = when {
        isPressed -> PressedBodyColor
        else -> RaisedBodyColor
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor)
            .njGrain(alpha = 0.04f)
            .drawWithContent {
                drawContent()
                val sw = 1.dp.toPx()
                if (isPressed) {
                    drawLine(Color.Black.copy(alpha = 0.45f), Offset(0f, sw / 2), Offset(size.width, sw / 2), sw * 1.5f)
                    drawLine(Color.Black.copy(alpha = 0.25f), Offset(sw / 2, 0f), Offset(sw / 2, size.height), sw)
                    drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, size.height - sw / 2), Offset(size.width, size.height - sw / 2), sw)
                    drawLine(Color.White.copy(alpha = 0.04f), Offset(size.width - sw / 2, 0f), Offset(size.width - sw / 2, size.height), sw)
                } else {
                    drawLine(Color.White.copy(alpha = 0.09f), Offset(0f, sw / 2), Offset(size.width, sw / 2), sw)
                    drawLine(Color.White.copy(alpha = 0.05f), Offset(sw / 2, 0f), Offset(sw / 2, size.height), sw)
                    drawLine(Color.Black.copy(alpha = 0.35f), Offset(0f, size.height - sw / 2), Offset(size.width, size.height - sw / 2), sw)
                    drawLine(Color.Black.copy(alpha = 0.18f), Offset(size.width - sw / 2, 0f), Offset(size.width - sw / 2, size.height), sw)
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection LED
        NjLedDot(
            isLit = isSelected,
            size = 6.dp,
            litColor = NjAccent
        )

        Spacer(Modifier.width(12.dp))

        // Theme name
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (isSelected) 0.9f else 0.6f
            ),
            modifier = Modifier.weight(1f)
        )

        // Color swatch dots -- show key palette colors at a glance
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SwatchDot(palette.bg)
            SwatchDot(palette.surface2)
            SwatchDot(palette.amber)
            SwatchDot(palette.accent)
            SwatchDot(palette.metronomeLed)
        }
    }
}

@Composable
private fun SwatchDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}
