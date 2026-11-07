package com.shieldchild.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.shieldchild.app.ShieldApplication

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The parent's theme choice; a state so changing it in Settings recolours the open screen. */
object ThemeState {
    val mode = mutableStateOf(
        runCatching { ThemeMode.valueOf(ShieldApplication.instance.preferencesRepository.getThemeMode()) }
            .getOrDefault(ThemeMode.SYSTEM)
    )

    fun set(mode: ThemeMode) {
        ShieldApplication.instance.preferencesRepository.setThemeMode(mode.name)
        this.mode.value = mode
    }

    /** Wallpaper colours (Material You); only Android 12+ has them. */
    val isDynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamic = mutableStateOf(ShieldApplication.instance.preferencesRepository.isDynamicColor())

    fun setDynamic(enabled: Boolean) {
        ShieldApplication.instance.preferencesRepository.setDynamicColor(enabled)
        dynamic.value = enabled
    }
}

@Composable
fun isAppInDarkTheme(): Boolean = when (ThemeState.mode.value) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Material colours derived from the Shield tokens, so stock components (dialogs, switches, fields) match. */
private fun materialScheme(c: ShieldColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.primary, onPrimary = c.onPrimary,
        primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
        secondaryContainer = c.primaryContainer, onSecondaryContainer = c.onPrimaryContainer,
        background = c.canvas, onBackground = c.ink,
        surface = c.card, onSurface = c.ink, surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.inkMuted,
        surfaceContainerLowest = c.card, surfaceContainerLow = c.card, surfaceContainer = c.card,
        surfaceContainerHigh = c.card, surfaceContainerHighest = c.surfaceVariant,
        outline = c.outline, outlineVariant = c.line,
        error = c.error, errorContainer = c.errorContainer
    )
} else {
    lightColorScheme(
        primary = c.primary, onPrimary = c.onPrimary,
        primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
        secondaryContainer = c.primaryContainer, onSecondaryContainer = c.onPrimaryContainer,
        background = c.canvas, onBackground = c.ink,
        surface = c.card, onSurface = c.ink, surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.inkMuted,
        surfaceContainerLowest = c.card, surfaceContainerLow = c.card, surfaceContainer = c.card,
        surfaceContainerHigh = c.card, surfaceContainerHighest = c.surfaceVariant,
        outline = c.outline, outlineVariant = c.line,
        error = c.error, errorContainer = c.errorContainer
    )
}

/** Shield tokens taken from the wallpaper scheme. Warning stays ours so instant lock always reads as a warning. */
private fun fromDynamic(s: ColorScheme, base: ShieldColors) = base.copy(
    canvas = s.surfaceContainerLow, card = if (base.isDark) s.surfaceContainer else s.surfaceContainerLowest,
    cardSubtle = s.surfaceContainerLow, surfaceVariant = s.surfaceContainerHighest, line = s.outlineVariant,
    primary = s.primary, onPrimary = s.onPrimary,
    primaryContainer = s.primaryContainer, onPrimaryContainer = s.onPrimaryContainer,
    ink = s.onSurface, inkMuted = s.onSurfaceVariant, outline = s.outline,
    error = s.error, errorContainer = s.errorContainer
)

@Composable
fun ShieldChildTheme(
    darkTheme: Boolean = isAppInDarkTheme(),
    content: @Composable () -> Unit
) {
    val base = if (darkTheme) DarkShieldColors else LightShieldColors
    val context = LocalContext.current
    val colors = if (ThemeState.dynamic.value && ThemeState.isDynamicSupported) {
        fromDynamic(if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context), base)
    } else {
        base
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insets = WindowCompat.getInsetsController(window, view)
                insets.isAppearanceLightStatusBars = !darkTheme
                insets.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalShieldColors provides colors) {
        MaterialTheme(
            colorScheme = materialScheme(colors),
            typography = Typography,
            content = content
        )
    }
}
