package dev.t1m3.qplayer.android.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val LocalIosDesign = staticCompositionLocalOf { false }
internal val LocalGlassRefraction = staticCompositionLocalOf { true }

/** Independent of cover seed/Monet: switching back leaves user preferences intact. */
internal fun iosDesignColorScheme(dark: Boolean): ColorScheme {
    val background = if (dark) Color.Black else Color.White
    val foreground = if (dark) Color.White else Color.Black
    val muted = if (dark) Color(0xFFAEAEB2) else Color(0xFF636366)
    val grouped = if (dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val accent = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = Color.White,
        secondary = muted, onSecondary = background,
        tertiary = accent, onTertiary = Color.White,
        primaryContainer = grouped, onPrimaryContainer = foreground,
        secondaryContainer = grouped, onSecondaryContainer = foreground,
        tertiaryContainer = grouped, onTertiaryContainer = foreground,
        background = background, onBackground = foreground,
        surface = background, onSurface = foreground,
        surfaceVariant = grouped, onSurfaceVariant = muted,
        surfaceContainer = background, surfaceContainerLow = grouped,
        surfaceContainerLowest = background, surfaceContainerHigh = grouped,
        surfaceContainerHighest = grouped, surfaceBright = grouped, surfaceDim = background,
        surfaceTint = Color.Transparent, outline = muted,
        outlineVariant = if (dark) Color(0xFF38383A) else Color(0xFFD1D1D6),
        inverseSurface = foreground, inverseOnSurface = background, inversePrimary = accent,
        primaryFixed = grouped, primaryFixedDim = grouped,
        onPrimaryFixed = foreground, onPrimaryFixedVariant = muted,
        secondaryFixed = grouped, secondaryFixedDim = grouped,
        onSecondaryFixed = foreground, onSecondaryFixedVariant = muted,
        tertiaryFixed = grouped, tertiaryFixedDim = grouped,
        onTertiaryFixed = foreground, onTertiaryFixedVariant = muted,
    )
}
