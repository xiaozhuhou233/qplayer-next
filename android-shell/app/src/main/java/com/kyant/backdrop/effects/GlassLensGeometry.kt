package com.kyant.backdrop.effects

// base(1).apk u3.c.g case 0: independent of QPlayer's sculpted-lens bounds below.
internal const val APK_ADAPTIVE_REFRACTION_HEIGHT_DP = 24f
internal const val APK_ADAPTIVE_REFRACTION_SIZE_FRACTION = 0.5f

// A shallow circular cap rather than a hemisphere with a vertical tangent.
// F(u) = (1 - sqrt(1 - 0.64u²)) / 0.4, u = 0..1 from inner bevel to rim.
// F'(0) = 0, F'(1) = 8/3. Limiting displacement to 0.32 * bevel width
// leaves a positive normal scale >= 1 - 0.32 * 8/3 = 0.1467 at the rim.
// A slightly wider bevel strengthens thickness without adding a dark outline;
// 0.47 still leaves an unrefracted central gap between opposite edge bands.
// These constants are also embedded in AGSL and exercised by the CPU checks.
internal const val GLASS_CAP_CURVATURE_SQUARED = 0.64f
internal const val GLASS_CAP_PROFILE_NUMERATOR = 1.6f
internal const val GLASS_MAX_BEVEL_FRACTION = 0.47f
internal const val GLASS_MAX_DISPLACEMENT_RATIO = 0.32f
internal const val GLASS_DEPTH_NORMAL_WEIGHT = 0.20f
internal const val GLASS_DISPERSION_SPREAD = 0.12f
internal const val GLASS_MAX_CENTER_CONVEXITY = 0.15f

// Float-only helpers: no per-frame model allocation or new sampling work.
internal fun glassBevelHeight(requested: Float, minDimension: Float): Float {
    if (!requested.isFinite() || !minDimension.isFinite() || requested <= 0f || minDimension <= 0f) return 0f
    return minOf(requested, minDimension * GLASS_MAX_BEVEL_FRACTION)
}

internal fun glassBevelDisplacement(requested: Float, bevelHeight: Float): Float {
    if (!requested.isFinite() || !bevelHeight.isFinite() || requested <= 0f || bevelHeight <= 0f) return 0f
    return minOf(requested, bevelHeight * GLASS_MAX_DISPLACEMENT_RATIO)
}
