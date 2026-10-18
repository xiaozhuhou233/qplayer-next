// Adapted from AndroidLiquidGlass-android AdaptiveLuminanceGlassContent.kt.
// Copyright 2025 Kyant, Apache-2.0. QPlayer: extracted pure functions for regression tests.
package dev.t1m3.qplayer.android.ui

import kotlin.math.sign

internal const val GLASS_SAMPLE_SIDE = 5
// Match the APK's 25-pixel grid while bounding GPU readback to a thumbnail.
internal const val GLASS_SAMPLE_MAX_SIDE = 5
// APK: finish the luminance tween before sampling again; ink animates alongside it.
internal const val GLASS_COLOR_DURATION_MS = 1000
internal const val GLASS_INK_DURATION_MS = 1000

/** Five-by-five centre samples without allocating a second resized bitmap. */
internal fun sampledGlassLuminance(pixels: IntArray, width: Int, height: Int): Float? {
    if (width < 1 || height < 1 || width > pixels.size / height) return null
    var total = 0.0
    var count = 0
    for (y in 0 until GLASS_SAMPLE_SIDE) {
        val py = ((y + 0.5f) * height / GLASS_SAMPLE_SIDE).toInt().coerceAtMost(height - 1)
        for (x in 0 until GLASS_SAMPLE_SIDE) {
            val px = ((x + 0.5f) * width / GLASS_SAMPLE_SIDE).toInt().coerceAtMost(width - 1)
            val pixel = pixels[py * width + px]
            total += androidGlassLuminance((pixel shr 16 and 255) / 255f,
                (pixel shr 8 and 255) / 255f, (pixel and 255) / 255f)
            count++
        }
    }
    return if (count == 0) null else (total / count).toFloat()
}

// Deliberately use the reference's encoded RGB weights, NOT Color.luminance()
// (which linearizes sRGB and would change the reference's 0.5 threshold).
internal fun androidGlassLuminance(red: Float, green: Float, blue: Float): Float =
    (0.2126 * red + 0.7152 * green + 0.0722 * blue).toFloat().coerceIn(0f, 1f)

internal fun androidGlassUsesDarkInk(luminance: Float): Boolean = luminance > 0.5f

/** The APK adaptive demo has no additional white surface fill. */
@Suppress("UNUSED_PARAMETER")
internal fun androidGlassWhiteVeil(luminance: Float): Float = 0f

// The original adaptive material has neither an inner ring nor extra fill light.
@Suppress("UNUSED_PARAMETER")
internal fun androidGlassInnerRingAlpha(luminance: Float): Float = 0f

@Suppress("UNUSED_PARAMETER")
internal fun androidGlassInnerLightAlpha(luminance: Float): Float = 0f

internal data class AndroidGlassOptics(
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val blurDp: Float,
)

internal fun androidGlassOptics(luminance: Float): AndroidGlassOptics {
    val measured = if (luminance.isFinite()) luminance.coerceIn(0f, 1f) else 0.5f
    val l = (measured * 2f - 1f).let { sign(it) * it * it }
    fun lerp(start: Float, end: Float, amount: Float) = (1f - amount) * start + amount * end
    // Verified against base(1).apk u3.c case 0 and w3.a.b, SHA256 75560a09...:
    // intentionally restore the APK's white endpoint (contrast 0, brightness .5).
    // The user explicitly chose this over QPlayer's former white-texture guard.
    return AndroidGlassOptics(
        brightness = if (l > 0f) lerp(0.1f, 0.5f, l) else lerp(0.1f, -0.2f, -l),
        contrast = if (l > 0f) lerp(1f, 0f, l) else 1f,
        saturation = 1.5f,
        blurDp = if (l > 0f) lerp(8f, 16f, l) else lerp(8f, 2f, -l),
    )
}
