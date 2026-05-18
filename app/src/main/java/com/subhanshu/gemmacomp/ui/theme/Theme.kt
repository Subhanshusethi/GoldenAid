package com.subhanshu.gemmacomp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Medical triage design system.
 * Dark theme optimized for outdoor/field use (high contrast, readable in bright light).
 */

// ── Core palette ──
val TriageDark = Color(0xFF0A0E17)
val TriageSurface = Color(0xFF111827)
val TriageCard = Color(0xFF1A2332)
val TriageBorder = Color(0xFF2A3544)
val TriageAccent = Color(0xFF60A5FA)    // Calm medical blue
val TriageTextPrimary = Color(0xFFF1F5F9)
val TriageTextSecondary = Color(0xFF94A3B8)
val TriageTextMuted = Color(0xFF64748B)

// ── Triage level colors ──
val TriageRed = Color(0xFFEF4444)
val TriageYellow = Color(0xFFFBBF24)
val TriageGreen = Color(0xFF22C55E)
val TriageBlack = Color(0xFF1E1E1E)
val TriageAssessing = Color(0xFF60A5FA)

private val MedicalDarkScheme = darkColorScheme(
    primary = TriageAccent,
    onPrimary = Color.White,
    secondary = Color(0xFF34D399),
    background = TriageDark,
    surface = TriageSurface,
    surfaceVariant = TriageCard,
    onBackground = TriageTextPrimary,
    onSurface = TriageTextPrimary,
    onSurfaceVariant = TriageTextSecondary,
    outline = TriageBorder,
    error = TriageRed,
)

private val MedicalTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
        color = TriageTextPrimary,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        color = TriageTextPrimary,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        color = TriageTextPrimary,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = TriageTextSecondary,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = TriageTextPrimary,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TriageTextSecondary,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp,
        color = TriageTextMuted,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
        color = TriageTextMuted,
    ),
)

@Composable
fun GemmaCompTheme(
    darkTheme: Boolean = true, // Always dark for field use
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MedicalDarkScheme,
        typography = MedicalTypography,
        content = content
    )
}
