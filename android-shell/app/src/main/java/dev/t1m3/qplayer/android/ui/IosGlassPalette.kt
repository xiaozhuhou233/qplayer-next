// AM++ embedded / AndroidLiquidGlass material values. QPlayer keeps its own routes and sampling.
package dev.t1m3.qplayer.android.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.iosReferenceLens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow

internal val LocalIosGlassBlur = compositionLocalOf { 4f }
internal val LocalGlassTint = compositionLocalOf { Color(0xFF808080) }
internal val LocalGlassLuminance = compositionLocalOf { 0.5f }
internal val LocalGlassMotionActive = compositionLocalOf { false }
internal val LocalGlassDark = staticCompositionLocalOf { true }
internal fun glassContentColor(luminance: Float): Color =
    if (luminance > 0.5f) Color.Black else Color.White

@Suppress("UNUSED_PARAMETER")
internal fun iosGlassShadow(dark: Boolean): Shadow = Shadow.Default

@Suppress("UNUSED_PARAMETER")
internal fun iosGlassSurface(dark: Boolean, tint: Color, sampledLuminance: Float = 0.5f): Color =
    (if (dark) Color(0xFF121212) else Color(0xFFFAFAFA)).copy(alpha = .4f)

internal fun DrawScope.drawIosPanelGlassSurface(dark: Boolean) {
    drawRect(iosGlassSurface(dark, Color.Unspecified))
}

/** AM++ panel optics; the user blur setting deliberately applies to every control. */
@Suppress("UNUSED_PARAMETER")
internal fun BackdropEffectScope.apiGlassEffects(luminance: Float, refraction: Boolean,
    refractionProgress: Float = 1f, shellHeightDp: Float = size.height / density,
    blurDp: Float = 4f, strongRefraction: Boolean = false) {
    vibrancy()
    blur(blurDp.coerceIn(0f, 24f) * density)
    if (!refraction) return
    val progress = refractionProgress.coerceIn(0f, 1f)
    if (strongRefraction) {
        // NativeLiquidButton bounds the 24/24 lens on compact panels.
        val amount = minOf(24f * density, size.minDimension * .375f) * progress
        iosReferenceLens(amount, amount)
    } else iosReferenceLens(12f * density * progress, 24f * density * progress)
}

/** The selection lens samples the already blurred panel, avoiding a second blur. */
internal fun BackdropEffectScope.iosIndicatorEffects(refraction: Boolean, progress: Float) {
    if (!refraction) return
    val p = progress.coerceIn(0f, 1f)
    iosReferenceLens(10f * density * p, 14f * density * p, chromaticAberration = true)
}
