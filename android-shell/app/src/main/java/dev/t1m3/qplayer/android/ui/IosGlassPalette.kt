// BiliPai-main default BALANCED glass. Values traced to the shipped rendering path.
// See docs/bilipai-glass-migration.md; no AdaptiveLuminanceGlass APK filters here.
package dev.t1m3.qplayer.android.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.biliPaiLens
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.effects.apkAdaptiveLens
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.shadow.Shadow

private val BiliPaiLightShadow = Shadow(
    radius = BiliPaiGlassParameters.outerShadowRadiusDp.dp,
    offset = DpOffset.Zero,
    color = Color.Black,
    alpha = BiliPaiGlassParameters.outerShadowLightAlpha,
)
private val BiliPaiDarkShadow = BiliPaiLightShadow.copy(
    alpha = BiliPaiGlassParameters.outerShadowDarkAlpha,
)
internal fun iosGlassShadow(dark: Boolean): Shadow =
    if (dark) BiliPaiDarkShadow else BiliPaiLightShadow

// Artwork tint/luminance animate during shared-page transitions. Static locals
// invalidate the provider's entire page tree on every sample, including lists
// and shared-element measurement. Track reads so only glass consumers update.
internal val LocalGlassTint = compositionLocalOf { Color(0xFF808080) }
internal val LocalGlassLuminance = compositionLocalOf { 0.5f }
internal val LocalGlassMotionActive = compositionLocalOf { false }
internal val LocalGlassDark = staticCompositionLocalOf { true }
internal fun glassContentColor(luminance: Float): Color =
    if (luminance > 0.5f) Color.Black else Color.White

@Suppress("UNUSED_PARAMETER")
internal fun iosGlassSurface(
    dark: Boolean,
    tint: Color,
    sampledLuminance: Float = 0.5f,
): Color = (if (dark) Color(BiliPaiGlassParameters.darkSurfaceArgb) else Color.White)
    .copy(alpha = .5f)

internal fun DrawScope.drawBiliPaiReadabilityScrim(dark: Boolean) {
    drawRect((if (dark) Color.Black else Color.White)
        .copy(alpha = BiliPaiGlassParameters.readabilityScrimAlpha))
}

internal fun DrawScope.drawBiliPaiGlassSurface(dark: Boolean) {
    drawRect(iosGlassSurface(dark, Color.Unspecified))
}

/** BiliPai FloatingBottomBar shell: vibrancy -> 4dp blur -> scaled 24/24 lens. */
@Suppress("UNUSED_PARAMETER")
internal fun BackdropEffectScope.apiGlassEffects(
    luminance: Float,
    refraction: Boolean,
    refractionProgress: Float = 1f,
    shellHeightDp: Float = size.height / density,
    blurDp: Float = BiliPaiGlassParameters.blurDp,
    strongRefraction: Boolean = false,
) {
    vibrancy()
    blur(4f * density)
    if (refraction) {
        if (strongRefraction) apkAdaptiveLens(refractionProgress)
        else lens(16f * density * refractionProgress, 32f * density * refractionProgress)
    }
}

/** The moving indicator refracts captured shell/content; it must not filter it twice. */
internal fun BackdropEffectScope.biliPaiIndicatorEffects(
    refraction: Boolean,
    progress: Float,
    shellHeightDp: Float = BiliPaiGlassParameters.shellHeightDp,
) {
    if (!refraction) return
    lens(16f * density * progress, 32f * density * progress)
}
