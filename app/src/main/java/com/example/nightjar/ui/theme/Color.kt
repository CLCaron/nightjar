package com.example.nightjar.ui.theme

import androidx.compose.ui.graphics.Color

// ── Nightjar color palette — late-night zen, midnight moon ─────
val NjBg = Color(0xFF090E1C)          // warm near-black
val NjSurface = Color(0xFF0E1420)          // base surface
val NjSurface2 = Color(0xFF141A28)          // elevated surface

val NjPrimary = Color(0xFF4A6A8F)          // deep steel blue — buttons, accents
val NjPrimary2 = Color(0xFF5C7DA0)          // muted steel blue — secondary accent

val NjAccent = Color(0xFFC9A96E)          // muted gold — record button, active states

val NjOnBg = Color(0xFFDEE2ED)          // warm off-white text
val NjOnSurface = Color(0xFFDEE2ED)
val NjMuted = Color(0xFF8F98AE)          // secondary text
val NjMuted2 = Color(0xFF5E6678)          // disabled/tertiary

val NjMidnight = Color(0xFF080F21)          // deep midnight blue — studio track lanes
val NjMidnight2 = Color(0xFF131720)          // slightly lifted midnight — track block bg
val NjStarlight = Color(0xFF8493C8)          // silver-blue — waveforms, subtle highlights

val NjStardust = Color(0xFFE2E0EE)          // bright silver-white — starfield base

val NjOutline = Color(0xFF1A2235)          // subtle borders
val NjError = Color(0xFFD4727A)          // muted rose

// ── Studio warm variants — indoor midnight ──────────────────────
// Same darkness, but the blues shift toward indigo/warm gray.
// Like stepping from the open night sky into a dimly lit room.
val NjStudioBg = Color(0xFF0F0D18)          // NjBg warmed — faint indigo
val NjStudioSurface = Color(0xFF16131E)     // NjSurface warmed
val NjStudioSurface2 = Color(0xFF1C1824)    // NjSurface2 warmed — drawer, panels
val NjStudioLane = Color(0xFF1A161E)        // NjMidnight2 warmed — track lanes
val NjStudioOutline = Color(0xFF231E2C)     // NjOutline warmed — borders
val NjStudioAccent = Color(0xFFBE7B4A)      // warm amber-orange — playhead, Loop LED
val NjStudioGreen = Color(0xFF7DA87A)       // muted sage green — Play LED
val NjStudioTeal = Color(0xFF5EA8A3)        // dusty teal — Solo LED
val NjStudioWaveform = Color(0xFF9E9488)    // warm silver — trim handles, inactive UI

// ── Per-track waveform colors — muted warm tones ─────────────────
// Enough saturation to distinguish tracks at a glance, but still
// low enough to feel lo-fi / late-night. Cycles by track sortIndex.
val NjTrackColors = listOf(
    Color(0xFFC48560),    // dusty coral-orange
    Color(0xFF8B7EC8),    // muted lavender
    Color(0xFFCB6B6B),    // soft brick red
    Color(0xFF6A9E8F),    // sage green
    Color(0xFFB89B5A),    // faded gold
    Color(0xFF7A9FC4),    // dusty steel blue
)