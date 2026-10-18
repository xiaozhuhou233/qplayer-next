// AndroidLiquidGlass-android AdaptiveLuminanceGlassContent, Copyright 2025 Kyant,
// Apache-2.0. QPlayer: bounded thumbnails, serialized readback and lifecycle.
package dev.t1m3.qplayer.android.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.apkAdaptiveLens
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val glassReadbackLock = Mutex()
private var lastGlassReadbackMs = 0L
private var smoothedGlassReadbackCostMs = 0f

private suspend fun readGlassSample(glass: RestoredDialogGlassSampler, density: Density,
    direction: LayoutDirection): Float? = glassReadbackLock.withLock {
    val remaining = glassReadbackGapMs(smoothedGlassReadbackCostMs) -
        (SystemClock.uptimeMillis() - lastGlassReadbackMs)
    if (remaining > 0L) delay(remaining)
    withFrameNanos { } // no simultaneous readbacks, even on high-refresh devices
    val started = SystemClock.uptimeMillis()
    lastGlassReadbackMs = started
    try {
        glass.sample(density, direction)
    } finally {
        val elapsed = (SystemClock.uptimeMillis() - started).toFloat()
        smoothedGlassReadbackCostMs = smoothedGlassReadbackCostMs * 0.75f + elapsed * 0.25f
    }
}

@Stable
private class RestoredDialogGlassSampler(
    private val backdrop: Backdrop,
    private val layer: GraphicsLayer,
    initialLuminance: Float,
    private val live: Boolean,
) {
    private val luminanceAnimation = Animatable(initialLuminance)
    private val inkAnimation = Animatable(restoredDialogContentColor(initialLuminance))
    val luminance: Float get() = luminanceAnimation.value
    val contentColor: Color get() = inkAnimation.value
    private var coordinates: LayoutCoordinates? = null
    val modifier = Modifier.onGloballyPositioned { coordinates = it }
    private var reportedFailure = false
    private val pixels = IntArray(GLASS_SAMPLE_SIDE * GLASS_SAMPLE_SIDE)

    fun sampleStamp(): GlassSampleStamp? {
        val target = coordinates?.takeIf { it.isAttached } ?: return null
        val source = backdrop as? LayerBackdrop
        if (backdrop.samplingRevision == 0L) return null
        if (source != null && (source.samplingRevision == 0L || source.layerCoordinates?.isAttached != true)) return null
        val visible = target.boundsInWindow()
        if (visible.width < 1f || visible.height < 1f ||
            !visible.overlaps(target.findRootCoordinates().boundsInWindow())) return null
        val position = target.positionInWindow()
        return GlassSampleStamp(backdrop.samplingRevision,
            position.x.roundToInt(), position.y.roundToInt(), target.size.width, target.size.height)
    }

    suspend fun sample(density: Density, direction: LayoutDirection): Float? {
        val target = coordinates?.takeIf { it.isAttached } ?: return null
        val width = target.size.width.toFloat()
        val height = target.size.height.toFloat()
        if (width < 1f || height < 1f) return null
        try {
            // Capture RAW background even when the glass draw node is idle.
            // A pending-draw flag alone could leave its sampled image stale.
            // The APK averages a 5x5, non-aspect-preserving thumbnail. Record
            // directly at that size rather than read back a full control bitmap.
            layer.record(density, direction, IntSize(GLASS_SAMPLE_SIDE, GLASS_SAMPLE_SIDE)) {
                scale(GLASS_SAMPLE_SIDE / width, GLASS_SAMPLE_SIDE / height, Offset.Zero) {
                    // Keep the original logical bounds for gradients and combined
                    // backdrops (e.g. switch track scaling), even on a 5x5 canvas.
                    val thumbnailSize = drawContext.size
                    drawContext.size = Size(width, height)
                    try {
                        with(backdrop) { drawBackdrop(density, target, null) }
                    } finally {
                        drawContext.size = thumbnailSize
                    }
                }
            }
            return withContext(Dispatchers.IO) {
                val image = layer.toImageBitmap().asAndroidBitmap()
                val software = if (image.config == Bitmap.Config.HARDWARE)
                    image.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null else image
                try {
                    // Reuse one pixel buffer; no per-sample scaled bitmap allocation.
                    software.getPixels(pixels, 0, software.width, 0, 0, software.width, software.height)
                    sampledGlassLuminance(pixels, software.width, software.height)
                } finally {
                    if (software !== image) software.recycle()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!reportedFailure) Log.w("QPlayerGlass", "Local glass sampling unavailable", failure)
            reportedFailure = true
            return null
        }
    }

    suspend fun animateLuminance(value: Float) = luminanceAnimation.animateTo(value,
        tween(if (live) 160 else GLASS_COLOR_DURATION_MS))
    suspend fun animateInk(value: Color) = inkAnimation.animateTo(value,
        tween(if (live) BiliPaiGlassParameters.inkAnimationMillis else GLASS_INK_DURATION_MS))
}

@Composable
private fun rememberRestoredDialogGlassSampler(backdrop: Backdrop, live: Boolean = false): RestoredDialogGlassSampler {
    val layer = rememberGraphicsLayer()
    val initial = if (LocalGlassDark.current) 0f else 1f
    val glass = remember(backdrop, layer, live) { RestoredDialogGlassSampler(backdrop, layer, initial, live) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val tintKey = LocalGlassTint.current
    val fallback by rememberUpdatedState(initial)
    val samplingEnabled = LocalGlassSamplingEnabled.current
    val motion = LocalGlassMotionActive.current
    val perPage = LocalGlassPerPageSampling.current
    val policy = remember(glass, perPage) { GlassSamplingPolicy(perPage) }
    LaunchedEffect(glass, lifecycle, density, direction, tintKey, samplingEnabled, motion, perPage) {
        if (!samplingEnabled || motion) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var inkJob: Job? = null
            var luminanceJob: Job? = null
            var lastInk: Color? = null
            while (isActive) {
                val stamp = glass.sampleStamp()
                if (stamp == null || !policy.shouldSample(SystemClock.uptimeMillis(), stamp)) {
                    delay(GLASS_SAMPLE_POLL_MS)
                    continue
                }
                val measured = readGlassSample(glass, density, direction)
                policy.completed(SystemClock.uptimeMillis(), stamp, measured != null)
                val sample = measured ?: fallback
                // Dialogs retain the original material timing. Live chrome
                // keeps sampling while its foreground/tint transition runs.
                val ink = restoredDialogContentColor(sample)
                if (ink != lastInk) {
                    lastInk = ink
                    inkJob?.cancel()
                    inkJob = launch { glass.animateInk(ink) }
                }
                if (live) {
                    luminanceJob?.cancel()
                    luminanceJob = launch { glass.animateLuminance(sample) }
                } else glass.animateLuminance(sample)
            }
        }
    }
    return glass
}

/** Bounded, lifecycle-aware sampling of the actual region behind live chrome. */
@Composable
internal fun rememberLiveIosGlass(backdrop: Backdrop): IosAdaptiveGlassState {
    val sampler = rememberRestoredDialogGlassSampler(backdrop, live = true)
    return remember(sampler) {
        IosAdaptiveGlassState(themeLuminance = derivedStateOf { sampler.luminance },
            ink = derivedStateOf { sampler.contentColor }, modifier = sampler.modifier)
    }
}

/**
 * Dialog-only restoration from glass-before-bilipai-20261002-163859.
 * False outside IosAwareAlertDialog: the rest of the app keeps BiliPai.
 */
internal val LocalRestoredIosDialogGlass = staticCompositionLocalOf { false }
internal val RestoredIosDialogHighlight = Highlight.Plain
internal val RestoredIosDialogShadow = Shadow(
    radius = 24.dp,
    offset = DpOffset(0.dp, 4.dp),
    color = Color.Black.copy(alpha = 0.10f),
)

private fun restoredDialogContentColor(luminance: Float): Color =
    if (androidGlassUsesDarkInk(luminance)) Color.Black else Color.White

@Composable
internal fun rememberRestoredIosDialogGlass(backdrop: Backdrop): IosAdaptiveGlassState {
    val sampler = rememberRestoredDialogGlassSampler(backdrop)
    return remember(sampler) {
        IosAdaptiveGlassState(
            themeLuminance = derivedStateOf { sampler.luminance },
            ink = derivedStateOf { sampler.contentColor },
            modifier = sampler.modifier,
            restoredDialog = true,
        )
    }
}

@Composable
internal fun rememberIosControlGlassHighlight(): State<Highlight> {
    if (LocalRestoredIosDialogGlass.current) {
        return remember { mutableStateOf(RestoredIosDialogHighlight) }
    }
    return rememberBiliPaiGlassHighlight()
}

internal fun BackdropEffectScope.restoredDialogGlassEffects(
    luminance: Float,
    refraction: Boolean,
    blurDp: Float = androidGlassOptics(luminance).blurDp,
) {
    val optics = androidGlassOptics(luminance)
    colorControls(brightness = optics.brightness, contrast = optics.contrast,
        saturation = optics.saturation)
    blur(blurDp * density)
    if (refraction) apkAdaptiveLens()
}

internal fun BackdropEffectScope.controlGlassEffects(
    adaptive: IosAdaptiveGlassState,
    refraction: Boolean,
    blurDp: Float = androidGlassOptics(adaptive.luminance).blurDp,
) {
    if (adaptive.restoredDialog) restoredDialogGlassEffects(adaptive.luminance, refraction, blurDp)
    else apiGlassEffects(adaptive.luminance, refraction, blurDp = blurDp)
}

internal fun controlGlassShadow(dark: Boolean, adaptive: IosAdaptiveGlassState): Shadow =
    if (adaptive.restoredDialog) RestoredIosDialogShadow else iosGlassShadow(adaptive.luminance < .5f)

internal fun DrawScope.drawControlGlassSurface(dark: Boolean, adaptive: IosAdaptiveGlassState) {
    // The restored dialog material has no extra white/scrim fill.
    if (!adaptive.restoredDialog) drawBiliPaiGlassSurface(adaptive.luminance < .5f)
}
