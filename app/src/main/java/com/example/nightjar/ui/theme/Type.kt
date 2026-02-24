package com.example.nightjar.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.nightjar.R

/**
 * Josefin Sans — geometric, airy, nocturnal.
 *
 * Used across the entire app for visual consistency. The light weight
 * floats against the deep midnight backgrounds like text etched in
 * starlight. Heavier weights (Medium, SemiBold) anchor buttons and
 * labels without breaking the geometric rhythm.
 */
val JosefinSans = FontFamily(
    Font(R.font.josefin_sans, FontWeight.Light),
    Font(R.font.josefin_sans, FontWeight.Normal),
    Font(R.font.josefin_sans, FontWeight.Medium),
    Font(R.font.josefin_sans, FontWeight.SemiBold),
)

val Typography = Typography(
    // ── Display — app name, splash, hero moments ────────────────────
    displayLarge = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Light,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = 1.5.sp
    ),
    displayMedium = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Light,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 1.sp
    ),
    displaySmall = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Light,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.5.sp
    ),

    // ── Headline — section headers, prominent labels ────────────────
    headlineLarge = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.5.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.5.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.25.sp
    ),

    // ── Title — screen titles, top bar, card headers ────────────────
    titleLarge = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.75.sp
    ),
    titleMedium = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.75.sp
    ),
    titleSmall = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp
    ),

    // ── Body — notes, descriptions, flowing text ────────────────────
    bodyLarge = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.3.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp
    ),
    bodySmall = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),

    // ── Label — buttons, chips, captions ────────────────────────────
    labelLarge = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = JosefinSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
)
