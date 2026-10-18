// QPlayer Android compatibility adaptation, 2026-09-28. Original: Copyright 2025 Kyant, Apache-2.0.
package com.kyant.backdrop.effects

import androidx.annotation.FloatRange
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceAtMost
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.internal.RoundedRectRefractionShaderString
import com.kyant.backdrop.internal.ApkAdaptiveRefractionShaderString
import com.kyant.backdrop.internal.RoundedRectRefractionWithDispersionShaderString
import com.kyant.backdrop.internal.RuntimeShaderEffect
import com.kyant.backdrop.isRuntimeShaderSupported

/** Catalog APK adaptive material: 24dp bevel, half-short-side displacement,
 * depth enabled, no chromatic dispersion and no additional central dome.
 * Progress only preserves QPlayer's existing press/release transitions.
 */
internal fun BackdropEffectScope.apkAdaptiveLens(progress: Float = 1f) {
    if (!isRuntimeShaderSupported()) return
    if (!progress.isFinite() || !size.minDimension.isFinite() || size.minDimension <= 0f) return
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f) return
    val height = APK_ADAPTIVE_REFRACTION_HEIGHT_DP * density * p
    val amount = size.minDimension * APK_ADAPTIVE_REFRACTION_SIZE_FRACTION * p
    if (padding > 0f) padding = (padding - height).fastCoerceAtLeast(0f)
    val radii = cornerRadii ?: throwUnsupportedSDFException()
    val shader = obtainRuntimeShader("ApkAdaptiveRefraction", ApkAdaptiveRefractionShaderString)
    shader.apply {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("offset", -padding, -padding)
        setFloatUniform("cornerRadii", radii)
        setFloatUniform("refractionHeight", height)
        setFloatUniform("refractionAmount", -amount)
        setFloatUniform("depthEffect", 1f)
    }
    effect(RuntimeShaderEffect(shader, "content"))
}

/**
 * [centerConvexity] adds a shallow central dome to the existing edge refraction.
 * Zero preserves the reference's edge-only lens. It deforms only the backdrop,
 * not foreground content, and uses the same shader pass and texture samples.
 * Edge width/displacement are size-bounded to avoid turning small controls into
 * an inverted mirror. The cap's finite slope keeps the inner/outer joins stable.
 */
fun BackdropEffectScope.lens(
    @FloatRange(from = 0.0) refractionHeight: Float,
    @FloatRange(from = 0.0) refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Boolean = false,
    @FloatRange(from = 0.0, to = 0.15) centerConvexity: Float = 0f,
) {
    if (!isRuntimeShaderSupported()) return
    val bevelHeight = glassBevelHeight(refractionHeight, size.minDimension)
    val bevelDisplacement = glassBevelDisplacement(refractionAmount, bevelHeight)
    if (bevelHeight <= 0f || bevelDisplacement <= 0f) return

    if (padding > 0f) {
        padding = (padding - bevelHeight).fastCoerceAtLeast(0f)
    }

    val cornerRadii = cornerRadii
    val effect =
        if (cornerRadii != null) {
            val shader =
                if (!chromaticAberration) {
                    obtainRuntimeShader(
                        "Refraction",
                        RoundedRectRefractionShaderString
                    )
                } else {
                    obtainRuntimeShader(
                        "RefractionWithDispersion",
                        RoundedRectRefractionWithDispersionShaderString
                    )
                }
            shader.apply {
                setFloatUniform("size", size.width, size.height)
                setFloatUniform("offset", -padding, -padding)
                setFloatUniform("cornerRadii", cornerRadii)
                setFloatUniform("refractionHeight", bevelHeight)
                setFloatUniform("refractionAmount", -bevelDisplacement)
                setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
                setFloatUniform("centerConvexity",
                    if (centerConvexity.isFinite()) centerConvexity.coerceIn(0f, GLASS_MAX_CENTER_CONVEXITY) else 0f)
                if (chromaticAberration) {
                    setFloatUniform("chromaticAberration", 1f)
                }
            }
            RuntimeShaderEffect(shader, "content")
        } else {
            throwUnsupportedSDFException()
        }
    effect(effect)
}

private val BackdropEffectScope.cornerRadii: FloatArray?
    get() = when (val shape = shape) {
        is AbsoluteRoundedCornerShape -> {
            val size = size
            val maxRadius = size.minDimension / 2f
            val topLeft = shape.topStart.toPx(size, this)
            val topRight = shape.topEnd.toPx(size, this)
            val bottomRight = shape.bottomEnd.toPx(size, this)
            val bottomLeft = shape.bottomStart.toPx(size, this)
            floatArrayOf(
                topLeft.fastCoerceAtMost(maxRadius),
                topRight.fastCoerceAtMost(maxRadius),
                bottomRight.fastCoerceAtMost(maxRadius),
                bottomLeft.fastCoerceAtMost(maxRadius)
            )
        }

        is CornerBasedShape -> {
            val size = size
            val maxRadius = size.minDimension / 2f
            val isLtr = layoutDirection == LayoutDirection.Ltr
            val topLeft =
                if (isLtr) shape.topStart.toPx(size, this)
                else shape.topEnd.toPx(size, this)
            val topRight =
                if (isLtr) shape.topEnd.toPx(size, this)
                else shape.topStart.toPx(size, this)
            val bottomRight =
                if (isLtr) shape.bottomEnd.toPx(size, this)
                else shape.bottomStart.toPx(size, this)
            val bottomLeft =
                if (isLtr) shape.bottomStart.toPx(size, this)
                else shape.bottomEnd.toPx(size, this)
            floatArrayOf(
                topLeft.fastCoerceAtMost(maxRadius),
                topRight.fastCoerceAtMost(maxRadius),
                bottomRight.fastCoerceAtMost(maxRadius),
                bottomLeft.fastCoerceAtMost(maxRadius)
            )
        }

        else -> null
    }

private fun throwUnsupportedSDFException(): Nothing {
    throw UnsupportedOperationException(
        "Only RoundedRectangularShape or CornerBasedShape is supported in lens effects."
    )
}
