package com.shieldchild.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ShieldChild design tokens ("Warm Guardian" green). Screens read colours from
 * [Shield.colors] so every screen follows light/dark mode; never hard-code hex in UI code.
 */
@Immutable
data class ShieldColors(
    val canvas: Color,             // screen background
    val card: Color,               // cards and dialogs
    val cardSubtle: Color,         // rows inside a card, disabled items
    val surfaceVariant: Color,     // icon badges, input fields, chips
    val line: Color,               // borders and dividers
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val ink: Color,                // main text
    val inkMuted: Color,           // secondary text
    val outline: Color,            // outlined buttons, unchecked switches
    val error: Color,
    val errorContainer: Color,
    val warning: Color,            // instant lock
    val warningContainer: Color,
    val onWarningContainer: Color,
    val isDark: Boolean
)

val LightShieldColors = ShieldColors(
    canvas = Color(0xFFF5F6F1),
    card = Color(0xFFFFFFFF),
    cardSubtle = Color(0xFFF7F8F5),
    surfaceVariant = Color(0xFFEEF1EA),
    line = Color(0xFFE3E7DF),
    primary = Color(0xFF1E5B3E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEBDD),
    onPrimaryContainer = Color(0xFF123D28),
    ink = Color(0xFF17201A),
    inkMuted = Color(0xFF4B5750),
    outline = Color(0xFF6F7A73),
    error = Color(0xFF8C2118),
    errorContainer = Color(0xFFFBE3E0),
    warning = Color(0xFF8F5200),
    warningContainer = Color(0xFFFCEBCB),
    onWarningContainer = Color(0xFF4A2D00),
    isDark = false
)

val DarkShieldColors = ShieldColors(
    canvas = Color(0xFF0F1411),
    card = Color(0xFF1A211C),
    cardSubtle = Color(0xFF151B17),
    surfaceVariant = Color(0xFF26302A),
    line = Color(0xFF2F3A33),
    primary = Color(0xFF8FD3AE),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF1E4A33),
    onPrimaryContainer = Color(0xFFB8EFCB),
    ink = Color(0xFFE3E8E3),
    inkMuted = Color(0xFFB4BEB7),
    outline = Color(0xFF8A958D),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5C1A14),
    warning = Color(0xFFFFB95C),
    warningContainer = Color(0xFF3F2A00),
    onWarningContainer = Color(0xFFFFDDB3),
    isDark = true
)

val LocalShieldColors = staticCompositionLocalOf { LightShieldColors }

object Shield {
    val colors: ShieldColors
        @Composable @ReadOnlyComposable get() = LocalShieldColors.current
}
