package com.example.nightjar.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Nightjar theme-varying color palette ────────────────────────────────

/**
 * All color tokens that vary between themes.
 *
 * Each theme defines its own personality through accent colors, LED tints,
 * track waveform palettes, and drum instrument row colors.
 */
data class NjColors(
    val panelInset: Color,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val lane: Color,
    val outline: Color,
    val onBg: Color,
    val muted: Color,
    val muted2: Color,
    val accent: Color,
    val amber: Color,
    val metronomeLed: Color,
    val raisedBody: Color,
    val pressedBody: Color,
    val deepPress: Color,
    // Accent personality tokens
    val primary: Color,
    val primary2: Color,
    val starlight: Color,
    val starfieldTint: Color,
    val recordCoral: Color,
    val ledGreen: Color,
    val ledTeal: Color,
    val ledYellow: Color,
    val trackColors: List<Color>,
    val drumRowColors: List<Color>,
)

val IndigoPalette = NjColors(
    panelInset = Color(0xFF0A0810),
    bg = Color(0xFF0F0D18),
    surface = Color(0xFF16131E),
    surface2 = Color(0xFF1C1824),
    lane = Color(0xFF1A161E),
    outline = Color(0xFF231E2C),
    onBg = Color(0xFFE6E0E0),
    muted = Color(0xFF9A8E98),
    muted2 = Color(0xFF6B5F6A),
    accent = Color(0xFFC9A96E),
    amber = Color(0xFFBE7B4A),
    metronomeLed = Color(0xFFB0D4F1),
    raisedBody = Color(0xFF6B5F6A).copy(alpha = 0.12f),
    pressedBody = Color(0xFF12101A),
    deepPress = Color(0xFF0A0810),
    // Accent personality -- cool midnight workshop
    primary = Color(0xFF7A6388),
    primary2 = Color(0xFF8B7498),
    starlight = Color(0xFF9E8CB0),
    starfieldTint = Color(0xFFECE0D4),
    recordCoral = Color(0xFFC46050),
    ledGreen = Color(0xFF7DA87A),
    ledTeal = Color(0xFF5EA8A3),
    ledYellow = Color(0xFFCCB35A),
    trackColors = listOf(
        Color(0xFFC48560),    // dusty coral-orange
        Color(0xFF8B7EC8),    // muted lavender
        Color(0xFFCB6B6B),    // soft brick red
        Color(0xFF6A9E8F),    // sage green
        Color(0xFFB89B5A),    // faded gold
        Color(0xFF7A9FC4),    // dusty steel blue
    ),
    drumRowColors = listOf(
        Color(0xFFCCB35A),  // Crash  -- warm yellow
        Color(0xFF5EA8A3),  // Ride   -- teal
        Color(0xFFBE7B4A),  // OH     -- amber
        Color(0xFFBE7B4A),  // CH     -- amber
        Color(0xFF6A9E8F),  // HiTom  -- sage
        Color(0xFF6A9E8F),  // MdTom  -- sage
        Color(0xFF6A9E8F),  // LoTom  -- sage
        Color(0xFF8B7EC8),  // Clap   -- lavender
        Color(0xFFC48560),  // Snare  -- coral
        Color(0xFFCB6B6B),  // Kick   -- brick
    ),
)

val WarmPlumPalette = NjColors(
    panelInset = Color(0xFF0E0A0E),
    bg = Color(0xFF140E12),
    surface = Color(0xFF1A1418),
    surface2 = Color(0xFF201A1E),
    lane = Color(0xFF1E1618),
    outline = Color(0xFF2C1E26),
    onBg = Color(0xFFDDD0C6),
    muted = Color(0xFF9A8890),
    muted2 = Color(0xFF5D3A54),
    accent = Color(0xFFC4A46E),
    amber = Color(0xFFB87858),
    metronomeLed = Color(0xFFD4C0A8),
    raisedBody = Color(0xFF5D3A54).copy(alpha = 0.18f),
    pressedBody = Color(0xFF180E14),
    deepPress = Color(0xFF10080C),
    // Accent personality -- warm candlelit workshop
    primary = Color(0xFF886068),
    primary2 = Color(0xFF987078),
    starlight = Color(0xFFB09088),
    starfieldTint = Color(0xFFECDCD4),
    recordCoral = Color(0xFFC05060),
    ledGreen = Color(0xFF8A9E72),
    ledTeal = Color(0xFFB8886A),
    ledYellow = Color(0xFFC8A858),
    trackColors = listOf(
        Color(0xFFC07860),    // warm terracotta
        Color(0xFFB87888),    // dusty rose
        Color(0xFFC06870),    // muted brick-rose
        Color(0xFF8A9A70),    // warm olive
        Color(0xFFB8905A),    // warm amber
        Color(0xFFA88878),    // dusty copper
    ),
    drumRowColors = listOf(
        Color(0xFFC8A858),  // Crash  -- warm gold
        Color(0xFFB8886A),  // Ride   -- dusty copper
        Color(0xFFB87858),  // OH     -- warm amber
        Color(0xFFB87858),  // CH     -- warm amber
        Color(0xFF8A9A70),  // HiTom  -- warm olive
        Color(0xFF8A9A70),  // MdTom  -- warm olive
        Color(0xFF8A9A70),  // LoTom  -- warm olive
        Color(0xFFB87888),  // Clap   -- dusty rose
        Color(0xFFC07860),  // Snare  -- terracotta
        Color(0xFFC06870),  // Kick   -- brick-rose
    ),
)

val LocalNjColors = staticCompositionLocalOf { IndigoPalette }

// ── Theme-varying tokens -- composable delegates ─────────────────────────
// These read from the active palette via CompositionLocal.
// All existing call sites (Text(color = NjOnBg), Modifier.background(NjBg))
// continue to compile unchanged.

val NjPanelInset: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.panelInset
val NjBg: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.bg
val NjSurface: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.surface
val NjSurface2: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.surface2
val NjLane: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.lane
val NjOutline: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.outline
val NjOnBg: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.onBg
val NjMuted: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.muted
val NjMuted2: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.muted2
val NjAccent: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.accent
val NjAmber: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.amber
val NjMetronomeLed: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.metronomeLed

val NjPrimary: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.primary
val NjPrimary2: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.primary2
val NjStarlight: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.starlight
val NjStarfieldTint: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.starfieldTint
val NjRecordCoral: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.recordCoral
val NjLedGreen: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.ledGreen
val NjLedTeal: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.ledTeal
val NjLedYellow: Color @Composable @ReadOnlyComposable get() = LocalNjColors.current.ledYellow
val NjTrackColors: List<Color> @Composable @ReadOnlyComposable get() = LocalNjColors.current.trackColors
val NjDrumRowColors: List<Color> @Composable @ReadOnlyComposable get() = LocalNjColors.current.drumRowColors

// ── Static tokens -- same in all themes ──────────────────────────────
val NjError = Color(0xFFD4727A)            // muted rose -- errors, destructive actions
