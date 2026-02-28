package com.example.nightjar.ui.theme

import androidx.compose.ui.graphics.Color

// ── Nightjar color palette — warm plum midnight, pocket recorder ───
val NjBg = Color(0xFF110C14)          // deep plum near-black
val NjSurface = Color(0xFF18121A)          // plum-tinted surface
val NjSurface2 = Color(0xFF1E1720)          // elevated plum surface

val NjPrimary = Color(0xFF7A6388)          // dusty mauve — buttons, accents
val NjPrimary2 = Color(0xFF8B7498)          // lighter mauve — secondary accent

val NjAccent = Color(0xFFC9A96E)          // muted gold — active states

val NjOnBg = Color(0xFFE6E0E0)          // warm off-white text (pink-tinted)
val NjOnSurface = Color(0xFFE6E0E0)
val NjMuted = Color(0xFF9A8E98)          // warm gray-mauve — secondary text
val NjMuted2 = Color(0xFF6B5F6A)          // plum-gray — disabled/tertiary

val NjMidnight = Color(0xFF0C0910)          // deep plum-black — track lanes
val NjMidnight2 = Color(0xFF14101A)          // lifted plum-black — track block bg
val NjStarlight = Color(0xFF9E8CB0)         // warm lilac — waveforms, subtle highlights

val NjStardust = Color(0xFFECE0D4)          // warm gold-cream — starfield base

val NjOutline = Color(0xFF261D28)          // plum-tinted borders

val NjRecordCoral = Color(0xFFC46050)      // warm muted coral — record button LED
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
val NjStudioGreen = Color(0xFF7DA87A)       // muted sage green -- Play LED
val NjStudioTeal = Color(0xFF5EA8A3)        // dusty teal -- Solo LED
val NjStudioYellow = Color(0xFFCCB35A)      // warm muted yellow -- Mute LED
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