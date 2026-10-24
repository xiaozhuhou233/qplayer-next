package dev.t1m3.qplayer.android.md3eui

import android.os.Build
import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.animation.core.PathEasing
import dev.t1m3.qplayer.settings.SettingsCatalog
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Mirrors the legacy SealMotion navigation recipes and SharedPageMotion scope.
private val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
internal const val Md3eChromeMotionMillis = 420
internal val Md3eChromeEasing = CubicBezierEasing(.4f, 0f, .2f, 1f)
private val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
private val emphasize = PathEasing(Path().apply {
    moveTo(0f, 0f)
    cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
    cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
})

@OptIn(ExperimentalSharedTransitionApi::class)
private val LocalPageSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
private val LocalPageAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
// The clicked key changes on navigation. Track readers rather than invalidating
// the entire outgoing home/list subtree with a static CompositionLocal.
private val LocalActiveCoverKey = compositionLocalOf<String?> { null }
private val LocalLowSpecMode = staticCompositionLocalOf { false }

@OptIn(ExperimentalSharedTransitionApi::class)
internal val Md3eSharedScope get() = LocalPageSharedScope
internal val Md3eAnimatedScope get() = LocalPageAnimatedScope
internal val Md3eActiveCoverKey get() = LocalActiveCoverKey
internal val Md3eLowSpecMode get() = LocalLowSpecMode

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.md3eSharedCover(key: String): Modifier {
    if (LocalLowSpecMode.current) return this
    if (LocalIosDesign.current) return iosArtworkAnchor(key)
    val claude = LocalClaudeDesign.current
    val shared = LocalPageSharedScope.current ?: return this
    val animated = LocalPageAnimatedScope.current ?: return this
    return with(shared) {
        val state = rememberSharedContentState(key)
        // Freeze endpoint layout and scale its recorded layer. Remeasuring the
        // Surface/Image at every intermediate bound also rebuilt its round clip.
        sharedBounds(
            sharedContentState = state,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(
                androidx.compose.ui.layout.ContentScale.Crop),
            animatedVisibilityScope = animated,
            boundsTransform = { _, _ -> tween(if (claude) ClaudeOneTake.COLLECTION else 400, easing = if (claude) ClaudeOneTake.ExpoOut else emphasizedDecelerate) },
            zIndexInOverlay = 1f,
        ).graphicsLayer {
            // Only the incoming endpoint paints the matched image. Both endpoint
            // layouts stay registered for cold taps and rapid reversal.
            alpha = if (isTransitionActive && state.isMatchFound &&
                animated.transition.targetState != EnterExitState.Visible) 0f else 1f
        }
    }
}

// Register both endpoints before navigation, so a cold first tap has source bounds.
// Only the empty background layer morphs; the LazyColumn is never scaled or clipped.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Md3eCollectionContainer(
    key: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 0.dp,
    detail: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (LocalIosDesign.current) { IosCollectionContainer(key, detail && key != null, modifier, content); return }
    val claude = LocalClaudeDesign.current
    val shared = LocalPageSharedScope.current
    val animated = LocalPageAnimatedScope.current
    if (key == null || LocalLowSpecMode.current || shared == null || animated == null) {
        Box(modifier) { content() }
        return
    }
    val sonic = LocalSonicDesign.current
    val color = MaterialTheme.colorScheme.background
    val activeKey = LocalActiveCoverKey.current
    val participant = detail || activeKey == key
    if (!participant) {
        Box(modifier) { content() }
        return
    }
    val radius = animated.transition.animateDp(
        transitionSpec = { tween(if (claude) ClaudeOneTake.COLLECTION else 400, easing = if (claude) ClaudeOneTake.ExpoOut else emphasizedDecelerate) },
        label = "collection_corner",
    ) {
        if (detail) { if (it == EnterExitState.Visible) 0.dp else 16.dp }
        else { if (it == EnterExitState.Visible) corner else 0.dp }
    }
    val contentAlpha = animated.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible)
                tween(if (claude) ClaudeOneTake.COLLECTION else 400, easing = if (claude) ClaudeOneTake.ExpoOut else emphasizedDecelerate)
            else tween(120)
        },
        label = "collection_content",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val collectionState = with(shared) { rememberSharedContentState("collection:$key") }
    // Reuse one path in draw phase; the content reveal uses the actual shared bounds,
    // rather than a separate clock or a full-page scaling/re-measuring animation.
    val clipPath = remember { Path() }
    val overlayClip = remember(radius, corner) {
        object : SharedTransitionScope.OverlayClip {
            override fun getClipPath(
                sharedContentState: SharedTransitionScope.SharedContentState,
                bounds: androidx.compose.ui.geometry.Rect,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                density: androidx.compose.ui.unit.Density,
            ): Path {
                val r = with(density) { radius.value.toPx() }
                clipPath.reset()
                clipPath.addRoundRect(androidx.compose.ui.geometry.RoundRect(bounds, CornerRadius(r, r)))
                return clipPath
            }
        }
    }
    // The shared background owns travel only; the settled detail always paints
    // its own base even if a retained shared RenderNode is no longer available.
    Box(modifier.drawBehind { if (detail && !shared.isTransitionActive) drawRect(color) }) {
        Box(with(shared) {
            Modifier.matchParentSize().sharedElement(
                sharedContentState = collectionState,
                animatedVisibilityScope = animated,
                boundsTransform = { _, _ -> tween(if (claude) ClaudeOneTake.COLLECTION else 400, easing = if (claude) ClaudeOneTake.ExpoOut else emphasizedDecelerate) },
                zIndexInOverlay = 0f,
                clipInOverlayDuringTransition = overlayClip,
            )
        }.drawBehind {
            val r = radius.value.toPx()
            drawRoundRect(color, cornerRadius = CornerRadius(r, r))
        })
        // On return, the incoming card's labels must also render above the shrinking
        // background. Otherwise they stay occluded until the cover lands at 400 ms.
        // An overlay owns a temporary GraphicsLayer. Detach its modifier after
        // handoff so returning pages/cards draw their normal Surface backgrounds
        // again instead of retaining a RenderNode from the departed route.
        val reveal = if (shared.isTransitionActive && collectionState.isMatchFound) with(shared) {
            Modifier.renderInSharedTransitionScopeOverlay(
                renderInOverlay = { isTransitionActive && collectionState.isMatchFound },
                zIndexInOverlay = .5f,
                clipInOverlayDuringTransition = { _, _ -> collectionState.clipPathInOverlay },
            )
        } else Modifier
        val opacity = Modifier.graphicsLayer {
            compositingStrategy = if (sonic) androidx.compose.ui.graphics.CompositingStrategy.Auto
                else androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            alpha = if (!shared.isTransitionActive ||
                (sonic && !detail && animated.transition.targetState == EnterExitState.Visible) ||
                (claude && animated.transition.targetState == EnterExitState.Visible)) 1f else contentAlpha.value
        }
        Box(reveal.then(opacity)) { content() }
    }
}

internal object Md3eMotion {
    private const val enterDuration = 400
    private const val exitDuration = 200
    private const val axisDuration = 300

    fun lowSpecPage(forward: Boolean): ContentTransform {
        val direction = if (forward) 1 else -1
        return (slideInHorizontally(tween(220), initialOffsetX = { direction * it / 12 }) +
            fadeIn(tween(180))) togetherWith
            (slideOutHorizontally(tween(180), targetOffsetX = { -direction * it / 12 }) +
                fadeOut(tween(140)))
    }

    fun page(forward: Boolean, preset: Int): ContentTransform {
        if (preset == SettingsCatalog.PAGE_TRANSITION_NONE)
            return EnterTransition.None togetherWith ExitTransition.None
        if (preset == SettingsCatalog.PAGE_TRANSITION_FADE) return fade()
        if (preset == SettingsCatalog.PAGE_TRANSITION_SLIDE_VERTICAL) {
            val direction = if (forward) 1 else -1
            return Md3eSealSharedAxis.y({ direction * it / 10 }, { -direction * it / 10 })
        }
        if (preset == SettingsCatalog.PAGE_TRANSITION_ZOOM) {
            return if (forward)
                (scaleIn(tween(350, easing = emphasizedDecelerate), initialScale = 0.80f) +
                    fadeIn(tween(220))) togetherWith
                    (scaleOut(tween(350, easing = emphasizedAccelerate), targetScale = 1.15f) +
                        fadeOut(tween(220)))
            else
                (scaleIn(tween(350, easing = emphasizedDecelerate), initialScale = 1.15f) +
                    fadeIn(tween(220))) togetherWith
                    (scaleOut(tween(350, easing = emphasizedAccelerate), targetScale = 0.80f) +
                        fadeOut(tween(220)))
        }
        return sealDetail(forward)
    }

    fun sealDetail(forward: Boolean): ContentTransform {
        if (forward) return Md3eSealSharedAxis.xIn({ (it * if (Build.VERSION.SDK_INT >= 34) 0.15f else 0.10f).toInt() }) togetherWith
            Md3eSealSharedAxis.xOut({ -(it * 0.10f).toInt() })
        val incoming = Md3eSealSharedAxis.xIn({ -(it * 0.10f).toInt() })
        val outgoing = Md3eSealSharedAxis.xOut({ (it * 0.10f).toInt() })
        return if (Build.VERSION.SDK_INT >= 34)
            (incoming + scaleIn(tween(350, easing = emphasizedDecelerate), initialScale = 0.9f)) togetherWith
                (outgoing + scaleOut(tween(350, easing = emphasizedAccelerate), targetScale = 0.9f))
        else incoming togetherWith outgoing
    }

    // Container bounds carry the motion; pages must not slide or scale underneath.
    fun collectionPage(): ContentTransform =
        EnterTransition.None togetherWith ExitTransition.None

    fun sharedPage(forward: Boolean): ContentTransform {
        val duration = axisDuration + 80
        return if (forward)
            (scaleIn(tween(duration, easing = emphasizedDecelerate), initialScale = 0.92f) +
                fadeIn(tween(240))) togetherWith
                (scaleOut(tween(duration, easing = emphasizedAccelerate), targetScale = 1.06f) +
                    fadeOut(tween(240)))
        else
            (scaleIn(tween(duration, easing = emphasizedDecelerate), initialScale = 1.06f) +
                fadeIn(tween(240))) togetherWith
                (scaleOut(tween(duration, easing = emphasizedAccelerate), targetScale = 0.92f) +
                    fadeOut(tween(240)))
    }

    fun tab(forward: Boolean): ContentTransform {
        val direction = if (forward) 1 else -1
        return Md3eSealSharedAxis.x({ direction * it / 4 }, { -direction * it / 4 })
    }

    // Seal AnimatedComposable.kt routes its task list and log through these recipes.
    fun variant(forward: Boolean): ContentTransform = if (forward)
        (slideInHorizontally(tween(enterDuration, easing = emphasize), initialOffsetX = { (it * 0.10f).toInt() }) +
            fadeIn(tween(exitDuration))) togetherWith fadeOut(tween(exitDuration))
    else fadeIn(tween(exitDuration)) togetherWith
        (slideOutHorizontally(tween(enterDuration, easing = emphasize), targetOffsetX = { (it * 0.10f).toInt() }) +
            fadeOut(tween(exitDuration)))

    fun vertical(forward: Boolean): ContentTransform = if (forward)
        (slideInVertically(tween(enterDuration, easing = emphasize), initialOffsetY = { it }) + fadeIn()) togetherWith
            slideOutVertically()
    else slideInVertically() togetherWith
        (slideOutVertically(tween(enterDuration, easing = emphasize), targetOffsetY = { it }) + fadeOut())

    fun fade(): ContentTransform = fadeIn(tween(exitDuration)) togetherWith fadeOut(tween(exitDuration))
    fun sheetIn(): EnterTransition = slideInVertically(tween(enterDuration, easing = emphasize), initialOffsetY = { it }) + fadeIn(tween(exitDuration))
    fun sheetOut(): ExitTransition = slideOutVertically(tween(enterDuration, easing = emphasize), targetOffsetY = { it }) + fadeOut(tween(exitDuration))

}

@Composable
internal fun rememberMd3eColorScheme(
    seed: String,
    dark: Boolean,
    enabled: Boolean,
    paletteStyle: Int,
    paletteChroma: Int,
    animate: Boolean,
    paused: Boolean,
): ColorScheme {
    val target = remember(seed, dark, enabled, paletteStyle, paletteChroma) {
        val parsed = if (enabled) runCatching { Color(AndroidColor.parseColor(seed)) }.getOrNull() else null
        legacyColorScheme(parsed ?: Color(0xFF6750A4), dark, paletteStyle, paletteChroma)
    }
    // A light/dark switch must replace both the background and foreground roles
    // together. Crossfading them passes through illegible mid-tone combinations.
    return if (animate) key(dark) { rememberAnimatedMd3eColorScheme(target, paused) } else target
}

// The legacy 700 ms theme clock keeps every M3 role in phase, including on rapid cover changes.
@Composable
internal fun rememberAnimatedMd3eColorScheme(target: ColorScheme, paused: Boolean): ColorScheme {
    val state = remember { SeekableTransitionState(target) }
    val transition = rememberTransition(state, label = "qplayer_theme")
    LaunchedEffect(target, paused) { if (!paused) state.animateTo(target) }
    return target.copy(
        primary = transition.themeColor("primary") { it.primary },
        onPrimary = transition.themeColor("onPrimary") { it.onPrimary },
        primaryContainer = transition.themeColor("primaryContainer") { it.primaryContainer },
        onPrimaryContainer = transition.themeColor("onPrimaryContainer") { it.onPrimaryContainer },
        inversePrimary = transition.themeColor("inversePrimary") { it.inversePrimary },
        secondary = transition.themeColor("secondary") { it.secondary },
        onSecondary = transition.themeColor("onSecondary") { it.onSecondary },
        secondaryContainer = transition.themeColor("secondaryContainer") { it.secondaryContainer },
        onSecondaryContainer = transition.themeColor("onSecondaryContainer") { it.onSecondaryContainer },
        tertiary = transition.themeColor("tertiary") { it.tertiary },
        onTertiary = transition.themeColor("onTertiary") { it.onTertiary },
        tertiaryContainer = transition.themeColor("tertiaryContainer") { it.tertiaryContainer },
        onTertiaryContainer = transition.themeColor("onTertiaryContainer") { it.onTertiaryContainer },
        background = transition.themeColor("background") { it.background },
        onBackground = transition.themeColor("onBackground") { it.onBackground },
        surface = transition.themeColor("surface") { it.surface },
        onSurface = transition.themeColor("onSurface") { it.onSurface },
        surfaceVariant = transition.themeColor("surfaceVariant") { it.surfaceVariant },
        onSurfaceVariant = transition.themeColor("onSurfaceVariant") { it.onSurfaceVariant },
        surfaceTint = transition.themeColor("surfaceTint") { it.surfaceTint },
        inverseSurface = transition.themeColor("inverseSurface") { it.inverseSurface },
        inverseOnSurface = transition.themeColor("inverseOnSurface") { it.inverseOnSurface },
        error = transition.themeColor("error") { it.error },
        onError = transition.themeColor("onError") { it.onError },
        errorContainer = transition.themeColor("errorContainer") { it.errorContainer },
        onErrorContainer = transition.themeColor("onErrorContainer") { it.onErrorContainer },
        outline = transition.themeColor("outline") { it.outline },
        outlineVariant = transition.themeColor("outlineVariant") { it.outlineVariant },
        scrim = transition.themeColor("scrim") { it.scrim },
        surfaceBright = transition.themeColor("surfaceBright") { it.surfaceBright },
        surfaceDim = transition.themeColor("surfaceDim") { it.surfaceDim },
        surfaceContainer = transition.themeColor("surfaceContainer") { it.surfaceContainer },
        surfaceContainerHigh = transition.themeColor("surfaceContainerHigh") { it.surfaceContainerHigh },
        surfaceContainerHighest = transition.themeColor("surfaceContainerHighest") { it.surfaceContainerHighest },
        surfaceContainerLow = transition.themeColor("surfaceContainerLow") { it.surfaceContainerLow },
        surfaceContainerLowest = transition.themeColor("surfaceContainerLowest") { it.surfaceContainerLowest },
        primaryFixed = transition.themeColor("primaryFixed") { it.primaryFixed },
        primaryFixedDim = transition.themeColor("primaryFixedDim") { it.primaryFixedDim },
        onPrimaryFixed = transition.themeColor("onPrimaryFixed") { it.onPrimaryFixed },
        onPrimaryFixedVariant = transition.themeColor("onPrimaryFixedVariant") { it.onPrimaryFixedVariant },
        secondaryFixed = transition.themeColor("secondaryFixed") { it.secondaryFixed },
        secondaryFixedDim = transition.themeColor("secondaryFixedDim") { it.secondaryFixedDim },
        onSecondaryFixed = transition.themeColor("onSecondaryFixed") { it.onSecondaryFixed },
        onSecondaryFixedVariant = transition.themeColor("onSecondaryFixedVariant") { it.onSecondaryFixedVariant },
        tertiaryFixed = transition.themeColor("tertiaryFixed") { it.tertiaryFixed },
        tertiaryFixedDim = transition.themeColor("tertiaryFixedDim") { it.tertiaryFixedDim },
        onTertiaryFixed = transition.themeColor("onTertiaryFixed") { it.onTertiaryFixed },
        onTertiaryFixedVariant = transition.themeColor("onTertiaryFixedVariant") { it.onTertiaryFixedVariant },
    )
}

@Composable
private fun Transition<ColorScheme>.themeColor(name: String, color: (ColorScheme) -> Color): Color {
    val animated by animateColor(
        transitionSpec = { tween(durationMillis = 700, easing = FastOutSlowInEasing) },
        label = name,
    ) { color(it) }
    return animated
}

// CIELAB/LCh tones are copied from the legacy shell's Material You palette builder.
private const val WP_X = 0.95047f
private const val WP_Z = 1.08883f

private fun linearizeSrgb(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

private fun delinearizeSrgb(c: Float): Float =
    if (c <= 0.0031308f) c * 12.92f
    else (1.055 * Math.pow(c.toDouble(), 1.0 / 2.4) - 0.055).toFloat().coerceIn(0f, 1f)

private fun rgbToXyz(r: Float, g: Float, b: Float): FloatArray {
    val rl = linearizeSrgb(r)
    val gl = linearizeSrgb(g)
    val bl = linearizeSrgb(b)
    return floatArrayOf(
        0.4124564f * rl + 0.3575761f * gl + 0.1804375f * bl,
        0.2126729f * rl + 0.7151522f * gl + 0.0721750f * bl,
        0.0193339f * rl + 0.1191920f * gl + 0.9503041f * bl,
    )
}

private fun xyzToRgb(x: Float, y: Float, z: Float): FloatArray? {
    val r = 3.2404542f * x - 1.5371385f * y - 0.4985314f * z
    val g = -0.9692660f * x + 1.8760108f * y + 0.0415560f * z
    val b = 0.0556434f * x - 0.2040259f * y + 1.0572252f * z
    if (r < -0.001f || r > 1.001f || g < -0.001f || g > 1.001f || b < -0.001f || b > 1.001f) return null
    return floatArrayOf(
        delinearizeSrgb(r.coerceIn(0f, 1f)),
        delinearizeSrgb(g.coerceIn(0f, 1f)),
        delinearizeSrgb(b.coerceIn(0f, 1f)),
    )
}

private fun labF(t: Float): Float =
    if (t > 216f / 24389f) Math.cbrt(t.toDouble()).toFloat() else (24389f / 27f * t + 16f) / 116f

private fun labFInv(t: Float): Float {
    val t3 = t * t * t
    return if (t3 > 216f / 24389f) t3 else (116f * t - 16f) * 27f / 24389f
}

private fun xyzToLab(x: Float, y: Float, z: Float): FloatArray {
    val fx = labF(x / WP_X)
    val fy = labF(y)
    val fz = labF(z / WP_Z)
    return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
}

private fun labToXyz(l: Float, a: Float, b: Float): FloatArray {
    val fy = (l + 16f) / 116f
    val fx = fy + a / 500f
    val fz = fy - b / 200f
    return floatArrayOf(labFInv(fx) * WP_X, labFInv(fy), labFInv(fz) * WP_Z)
}

private fun seedLch(seed: Color): FloatArray {
    val xyz = rgbToXyz(seed.red, seed.green, seed.blue)
    val lab = xyzToLab(xyz[0], xyz[1], xyz[2])
    var h = Math.toDegrees(atan2(lab[2], lab[1]).toDouble()).toFloat()
    if (h < 0f) h += 360f
    return floatArrayOf(lab[0], sqrt(lab[1] * lab[1] + lab[2] * lab[2]), h)
}

private fun lchColor(tone: Float, chroma: Float, hue: Float): Color {
    val hRad = Math.toRadians(hue.toDouble())
    fun at(c: Float): Color? {
        val xyz = labToXyz(tone, (c * cos(hRad)).toFloat(), (c * sin(hRad)).toFloat())
        val rgb = xyzToRgb(xyz[0], xyz[1], xyz[2]) ?: return null
        return Color(red = rgb[0], green = rgb[1], blue = rgb[2])
    }
    at(chroma)?.let { return it }
    var lo = 0f
    var hi = chroma
    repeat(16) {
        val mid = (lo + hi) / 2f
        if (at(mid) != null) lo = mid else hi = mid
    }
    return at(lo) ?: Color(0xFF808080)
}

private fun legacyColorScheme(seed: Color, dark: Boolean, paletteStyle: Int, paletteChroma: Int): ColorScheme {
    val hue = seedLch(seed)[2]
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV((seed.red * 255f).toInt(), (seed.green * 255f).toInt(), (seed.blue * 255f).toInt(), hsv)
    val neutralCover = hsv[1] < 0.08f
    val hue2 = when (paletteStyle) { 2 -> (hue + 30f) % 360f; 3 -> (hue + 18f) % 360f; else -> hue }
    val hue3 = when (paletteStyle) { 1 -> (hue + 90f) % 360f; 2 -> (hue + 60f) % 360f; 3 -> (hue + 120f) % 360f; else -> (hue + 60f) % 360f }
    val boost = when (paletteStyle) { 0 -> 0.85f; 1 -> 1.25f; 2 -> 1.05f; else -> 1.15f } * (0.75f + paletteChroma * 0.25f)
    fun chroma(value: Float) = if (neutralCover) 0f else value
    fun p(tone: Float) = lchColor(tone, chroma(48f * boost), hue)
    fun s(tone: Float) = lchColor(tone, chroma((if (paletteStyle == 3) 32f else 24f) * boost), hue2)
    fun tr(tone: Float) = lchColor(tone, chroma((if (paletteStyle == 1) 40f else 32f) * boost), hue3)
    fun n(tone: Float) = lchColor(tone, chroma((if (paletteStyle == 2) 10f else 8f) * boost), hue)
    fun nv(tone: Float) = lchColor(tone, chroma(12f * boost), hue)
    return if (dark) darkColorScheme(
        primary = p(80f), onPrimary = p(20f), primaryContainer = p(30f), onPrimaryContainer = p(90f),
        secondary = s(80f), onSecondary = s(20f), secondaryContainer = s(30f), onSecondaryContainer = s(90f),
        tertiary = tr(80f), onTertiary = tr(20f), tertiaryContainer = tr(30f), onTertiaryContainer = tr(90f),
        background = n(6f), onBackground = nv(90f), surface = n(6f), onSurface = nv(90f),
        surfaceVariant = nv(30f), onSurfaceVariant = nv(80f),
        surfaceContainerLowest = n(4f), surfaceContainerLow = n(10f), surfaceContainer = n(12f),
        surfaceContainerHigh = n(17f), surfaceContainerHighest = n(22f),
        surfaceBright = n(24f), surfaceDim = n(6f), surfaceTint = p(80f),
        inverseSurface = n(90f), inverseOnSurface = nv(20f), inversePrimary = p(40f),
        primaryFixed = p(90f), primaryFixedDim = p(80f), onPrimaryFixed = p(10f), onPrimaryFixedVariant = p(30f),
        secondaryFixed = s(90f), secondaryFixedDim = s(80f), onSecondaryFixed = s(10f), onSecondaryFixedVariant = s(30f),
        tertiaryFixed = tr(90f), tertiaryFixedDim = tr(80f), onTertiaryFixed = tr(10f), onTertiaryFixedVariant = tr(30f),
        outline = nv(60f), outlineVariant = nv(30f),
    ) else lightColorScheme(
        primary = p(40f), onPrimary = p(100f), primaryContainer = p(90f), onPrimaryContainer = p(10f),
        secondary = s(40f), onSecondary = s(100f), secondaryContainer = s(90f), onSecondaryContainer = s(10f),
        tertiary = tr(40f), onTertiary = tr(100f), tertiaryContainer = tr(90f), onTertiaryContainer = tr(10f),
        background = n(98f), onBackground = nv(10f), surface = n(98f), onSurface = nv(10f),
        surfaceVariant = nv(90f), onSurfaceVariant = nv(30f),
        surfaceContainerLowest = n(100f), surfaceContainerLow = n(96f), surfaceContainer = n(94f),
        surfaceContainerHigh = n(92f), surfaceContainerHighest = n(90f),
        surfaceBright = n(98f), surfaceDim = n(87f), surfaceTint = p(40f),
        inverseSurface = n(20f), inverseOnSurface = nv(95f), inversePrimary = p(80f),
        primaryFixed = p(90f), primaryFixedDim = p(80f), onPrimaryFixed = p(10f), onPrimaryFixedVariant = p(30f),
        secondaryFixed = s(90f), secondaryFixedDim = s(80f), onSecondaryFixed = s(10f), onSecondaryFixedVariant = s(30f),
        tertiaryFixed = tr(90f), tertiaryFixedDim = tr(80f), onTertiaryFixed = tr(10f), onTertiaryFixedVariant = tr(30f),
        outline = nv(50f), outlineVariant = nv(80f),
    )
}
