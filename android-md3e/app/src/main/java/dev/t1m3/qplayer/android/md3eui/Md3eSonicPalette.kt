package dev.t1m3.qplayer.android.md3eui

import android.util.LruCache
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import dev.t1m3.qplayer.android.graphics.AndroidColorExtractor
import dev.t1m3.qplayer.color.MaterialArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberSonicColorScheme(seed: String, dark: Boolean, enabled: Boolean,
    animate: Boolean, paused: Boolean, style: Int = 0, preset: Int = 0): ColorScheme = key(dark) {
    // Compute HCT/contrast roles once per cover, away from the main/render threads.
    // Cancelling produceState prevents an earlier song from publishing over a newer one.
    val effectiveSeed = MaterialArtworkPalette.seedForStyle(
        if (enabled) seed else "#6750a4", style, preset).ifBlank { "#6750a4" }
    val target by produceState(initialValue = sonicColorScheme(dark), effectiveSeed, style, preset) {
        value = withContext(Dispatchers.Default) { cachedSonicColorScheme(effectiveSeed, dark, style, preset) }
    }
    if (animate) rememberAnimatedMd3eColorScheme(target, paused) else target
}

internal val LocalSonicPaletteStyle = staticCompositionLocalOf { 0 }
internal val LocalSonicPalettePreset = staticCompositionLocalOf { 0 }
internal val LocalSonicArtworkEnabled = staticCompositionLocalOf { true }

private val sonicSchemeCache = LruCache<String, ColorScheme>(192)
private val sonicArtworkSeedCache = LruCache<String, String>(96)

private fun cachedSonicColorScheme(seed: String, dark: Boolean, style: Int, preset: Int): ColorScheme {
    val cacheKey = "$seed:$dark:$style"
    return sonicSchemeCache.get(cacheKey) ?: materialArtworkColorScheme(seed, dark, style, preset).also {
        sonicSchemeCache.put(cacheKey, it)
    }
}

/** A page/card owns its artwork seed; changing the playing song cannot recolor it. */
@Composable
internal fun rememberSonicArtworkColorScheme(artwork: String): ColorScheme {
    val style = LocalSonicPaletteStyle.current
    val preset = LocalSonicPalettePreset.current
    val static = MaterialArtworkPalette.isStaticStyle(style)
    val enabled = LocalSonicArtworkEnabled.current
    // Static themes never enqueue an artwork request or run the image color extractor.
    val source = remember(artwork, static, enabled) { if (static || !enabled) "" else artworkRequestSource(artwork) }
    val context = LocalContext.current.applicationContext
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return key(source, dark, style, preset, enabled) {
        val cachedSeed = remember {
            if (static) MaterialArtworkPalette.presetHex(preset)
            else if (!enabled) "#6750a4" else sonicArtworkSeedCache.get(source)
        }
        val initial = remember { cachedSeed?.let { sonicSchemeCache.get("$it:$dark:$style") } ?: sonicColorScheme(dark) }
        val colors by produceState(initialValue = initial) {
            val seed = cachedSeed ?: if (source.isNotBlank()) {
                val bitmap = awaitArtworkBitmap(context, source)
                withContext(Dispatchers.Default) { AndroidColorExtractor().dominantHex(bitmap) }
                    ?.also { sonicArtworkSeedCache.put(source, it) }
            } else null
            value = withContext(Dispatchers.Default) {
                cachedSonicColorScheme(seed ?: "#6750a4", dark, style, preset)
            }
        }
        colors
    }
}

/** Null inherits the song theme; an unavailable page image uses the neutral fallback. */
@Composable
internal fun SonicArtworkTheme(artwork: String?, fallback: ColorScheme? = null, content: @Composable () -> Unit) {
    // Keep the content at the same composition position when entering/leaving a detail.
    val colors = if (artwork != null) rememberSonicArtworkColorScheme(artwork)
        else fallback ?: MaterialTheme.colorScheme
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
    }
}

/** Every role is resolved by Google's DynamicScheme, including all on/container pairs. */
internal fun materialArtworkColorScheme(seed: String, dark: Boolean, style: Int = 0, preset: Int = 0): ColorScheme {
    val scheme = MaterialArtworkPalette.fromSeed(seed, dark, style, preset)
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
