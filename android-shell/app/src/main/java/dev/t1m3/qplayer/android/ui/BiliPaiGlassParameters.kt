// BiliPai-main: LiquidGlassTuning.kt, FloatingBottomBarGeometry.kt,
// FloatingDockChrome.kt and SettingsManager.kt. Default BALANCED / progress=0.5.
// Optics retain the upstream defaults; the rim is softened for QPlayer.
// See docs/bilipai-glass-migration.md for the original material baseline.
package dev.t1m3.qplayer.android.ui

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal object BiliPaiGlassParameters {
    const val progress = 0.5f
    const val blurDp = 4f
    const val brightness = 0f
    const val contrast = 1f
    const val saturation = 1.5f
    const val surfaceAlpha = 0.40f
    const val darkSurfaceArgb = 0xFF242424
    const val shellHeightDp = 64f
    const val shellLensHeightDp = 24f
    const val shellLensAmountDp = 24f
    const val shellChromaticAberration = 0f
    const val pressBloomDp = 16f
    const val indicatorLensHeightDp = 10f
    const val indicatorLensAmountDp = 14f
    const val indicatorChromaticAberration = 0.5f
    const val innerShadowRadiusDp = 8f
    const val innerShadowAlpha = 0.15f
    const val outerShadowRadiusDp = 10f
    const val outerShadowLightAlpha = 0.1f
    const val outerShadowDarkAlpha = 0.2f
    const val outerShadowOffsetDp = 0f
    // Keep the moving dual-light rim, but avoid an opaque-looking white outline.
    const val shellHighlightAlpha = 0.55f
    const val indicatorHighlightAlpha = 0.75f
    const val highlightWidthDp = 1f
    const val highlightStrokeAlpha = 0.08f
    const val highlightInnerBlurDp = 2f
    const val primaryLightIntensity = 1f
    const val secondaryLightIntensity = 0.4f
    const val sensorSmoothing = 0.15f
    const val gravityQuantizeDegrees = 3f
    const val inkAnimationMillis = 240
    const val contentReadability = 0.62f
    const val contentDistortion = 0.45f
    const val chromaticControl = 0.56f
    const val readabilityAdaptive = false // BiliPai default: STABLE, not ADAPTIVE.

    val readabilityProtection = contentReadability * contentReadability *
        contentReadability * contentReadability
    val contentReadabilityBoost = 0.6f * readabilityProtection
    val readabilityScrimAlpha = contentReadabilityBoost *
        (0.12f + readabilityProtection * 0.22f)

    fun geometryScale(heightDp: Float): Float =
        if (heightDp <= 0f) 0f else (heightDp / shellHeightDp).coerceIn(0f, 1f)

    fun effectPaddingDp(heightDp: Float): Float =
        (shellLensAmountDp + pressBloomDp) * geometryScale(heightDp)

    fun indicatorHighlightWidthDp(widthDp: Float, heightDp: Float): Float {
        if (widthDp <= 0f || heightDp <= 0f) return 1f
        val extra = ((widthDp / heightDp - 1.35f) / 8f).coerceIn(0f, 1f)
        return 1f + extra * (2f - 1f)
    }

    // FloatingDockChrome.kt: quantizeGravityHighlightDirection, unchanged.
    fun gravityDirection(x: Float, y: Float): Pair<Float, Float> {
        val magnitudeSquared = x * x + y * y
        if (magnitudeSquared <= 0.01f) return 0f to -1f
        val invMagnitude = 1f / sqrt(magnitudeSquared)
        val nx = x * invMagnitude
        val ny = y * invMagnitude
        val stepRad = (gravityQuantizeDegrees * PI / 180.0).toFloat()
        val quantized = (atan2(ny, nx) / stepRad).roundToInt() * stepRad
        return cos(quantized) to sin(quantized)
    }
}
