package dev.t1m3.qplayer.android.md3eui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import dev.t1m3.qplayer.color.MaterialExpressivePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberSonicColorScheme(seed: String, dark: Boolean, enabled: Boolean,
    animate: Boolean, paused: Boolean): ColorScheme = key(dark) {
    // Compute HCT/contrast roles once per cover, away from the main/render threads.
    // Cancelling produceState prevents an earlier song from publishing over a newer one.
    val target by produceState(initialValue = sonicColorScheme(dark), seed, enabled) {
        value = if (enabled && seed.isNotBlank()) withContext(Dispatchers.Default) {
            // Cover accents remain dynamic; page surfaces share a quiet, stable base.
            val base = sonicColorScheme(dark)
            materialExpressiveColorScheme(seed, dark).copy(
                background = base.background, onBackground = base.onBackground,
                surface = base.surface, onSurface = base.onSurface,
                surfaceVariant = base.surfaceVariant, onSurfaceVariant = base.onSurfaceVariant,
                surfaceBright = base.surfaceBright, surfaceDim = base.surfaceDim,
                surfaceContainerLowest = base.surfaceContainerLowest,
                surfaceContainerLow = base.surfaceContainerLow, surfaceContainer = base.surfaceContainer,
                surfaceContainerHigh = base.surfaceContainerHigh, surfaceContainerHighest = base.surfaceContainerHighest,
                outline = base.outline, outlineVariant = base.outlineVariant,
                inverseSurface = base.inverseSurface, inverseOnSurface = base.inverseOnSurface,
            )
        } else sonicColorScheme(dark)
    }
    if (animate) rememberAnimatedMd3eColorScheme(target, paused) else target
}

/** Every role is resolved by Google's DynamicScheme, including all on/container pairs. */
internal fun materialExpressiveColorScheme(seed: String, dark: Boolean): ColorScheme {
    val scheme = MaterialExpressivePalette.fromSeed(seed, dark)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(scheme.primary), onPrimary = Color(scheme.onPrimary),
        primaryContainer = Color(scheme.primaryContainer), onPrimaryContainer = Color(scheme.onPrimaryContainer),
        inversePrimary = Color(scheme.inversePrimary),
        secondary = Color(scheme.secondary), onSecondary = Color(scheme.onSecondary),
        secondaryContainer = Color(scheme.secondaryContainer), onSecondaryContainer = Color(scheme.onSecondaryContainer),
        tertiary = Color(scheme.tertiary), onTertiary = Color(scheme.onTertiary),
        tertiaryContainer = Color(scheme.tertiaryContainer), onTertiaryContainer = Color(scheme.onTertiaryContainer),
        background = Color(scheme.background), onBackground = Color(scheme.onBackground),
        surface = Color(scheme.surface), onSurface = Color(scheme.onSurface),
        surfaceVariant = Color(scheme.surfaceVariant), onSurfaceVariant = Color(scheme.onSurfaceVariant),
        surfaceTint = Color(scheme.surfaceTint),
        inverseSurface = Color(scheme.inverseSurface), inverseOnSurface = Color(scheme.inverseOnSurface),
        error = Color(scheme.error), onError = Color(scheme.onError),
        errorContainer = Color(scheme.errorContainer), onErrorContainer = Color(scheme.onErrorContainer),
        outline = Color(scheme.outline), outlineVariant = Color(scheme.outlineVariant), scrim = Color(scheme.scrim),
        surfaceBright = Color(scheme.surfaceBright), surfaceDim = Color(scheme.surfaceDim),
        surfaceContainerLowest = Color(scheme.surfaceContainerLowest), surfaceContainerLow = Color(scheme.surfaceContainerLow),
        surfaceContainer = Color(scheme.surfaceContainer), surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
        surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
        primaryFixed = Color(scheme.primaryFixed), primaryFixedDim = Color(scheme.primaryFixedDim),
        onPrimaryFixed = Color(scheme.onPrimaryFixed), onPrimaryFixedVariant = Color(scheme.onPrimaryFixedVariant),
        secondaryFixed = Color(scheme.secondaryFixed), secondaryFixedDim = Color(scheme.secondaryFixedDim),
        onSecondaryFixed = Color(scheme.onSecondaryFixed), onSecondaryFixedVariant = Color(scheme.onSecondaryFixedVariant),
        tertiaryFixed = Color(scheme.tertiaryFixed), tertiaryFixedDim = Color(scheme.tertiaryFixedDim),
        onTertiaryFixed = Color(scheme.onTertiaryFixed), onTertiaryFixedVariant = Color(scheme.onTertiaryFixedVariant),
    )
}
