// iOS chrome restored from android-shell's pre-refactor implementation.
package dev.t1m3.qplayer.android.md3eui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.t1m3.qplayer.android.ui.*

internal val LocalIosPageBackdrop = staticCompositionLocalOf<Backdrop?> { null }
// Pixel drawing and change subscriptions stay live; only adaptive colours use
// a version advanced once on page entry. Never capture the page into a bitmap.
internal class IosDockMaterialBackdrop(
    private val source: com.kyant.backdrop.backdrops.LayerBackdrop,
) : Backdrop by source {
    private var pageRevision = 0L
    override val samplingRevision: Long
        get() = if (source.samplingRevision > 0L && source.layerCoordinates?.isAttached == true) pageRevision else 0L
    fun refreshMaterial() { pageRevision++ }
}
internal val LocalIosDockBackdrop = staticCompositionLocalOf<IosDockMaterialBackdrop?> { null }

@Composable
internal fun RefreshIosDockMaterial(page: Any, settled: Boolean, enabled: Boolean) {
    val source = LocalIosPageBackdrop.current as? com.kyant.backdrop.backdrops.LayerBackdrop
    val target = LocalIosDockBackdrop.current
    val density = LocalDensity.current
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val theme = MaterialTheme.colorScheme.background
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(page, settled, enabled, source, target, density, direction, theme,
        configuration.screenWidthDp, configuration.screenHeightDp) {
        if (!enabled || !settled || source == null || target == null) return@LaunchedEffect
        kotlinx.coroutines.delay(100)
        source.invalidate()
        withFrameNanos { }
        withFrameNanos { }
        repeat(3) {
            if (source.layerCoordinates?.isAttached == true && source.samplingRevision > 0L) {
                target.refreshMaterial()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(80)
        }
    }
}

/** Part of the scrollable content, never a permanent viewport inset. */
@Composable
internal fun iosPageTopPadding() = if (LocalIosDesign.current)
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp else 0.dp

@Stable
internal class IosPageScrollState {
    var actionsVisible by mutableStateOf(true)
    var titleVisible by mutableStateOf(false)
    var homeCollapsed by mutableStateOf(false)
}

/** Original floating chrome thresholds; never consumes a page's scroll. */
@Composable
internal fun rememberIosPageScroll(route: Any, home: Boolean): Pair<IosPageScrollState, NestedScrollConnection> {
    val state = remember(route) { IosPageScrollState() }
    val density = LocalDensity.current
    val backdrop = LocalIosPageBackdrop.current
    val connection = remember(state, density, backdrop, home) {
        val actionThreshold = with(density) { 10.dp.toPx() }
        val collapseThreshold = with(density) { 24.dp.toPx() }
        val titleThreshold = with(density) { 60.dp.toPx() }
        object : NestedScrollConnection {
            private var actionDistance = 0f
            private var collapseDistance = 0f
            private var titleDistance = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                val dy = available.y
                if (actionDistance * dy < 0f) actionDistance = 0f
                actionDistance += dy
                if (kotlin.math.abs(actionDistance) >= actionThreshold) {
                    state.actionsVisible = actionDistance > 0f
                    actionDistance = 0f
                }
                if (home) {
                    if (collapseDistance * dy < 0f) collapseDistance = 0f
                    collapseDistance += dy
                    if (kotlin.math.abs(collapseDistance) >= collapseThreshold) {
                        state.homeCollapsed = collapseDistance < 0f
                        collapseDistance = 0f
                    }
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                titleDistance = (titleDistance - consumed.y).coerceAtLeast(0f)
                state.titleVisible = titleDistance >= titleThreshold
                if (consumed.y != 0f) (backdrop as? com.kyant.backdrop.backdrops.LayerBackdrop)?.invalidate()
                return Offset.Zero
            }
        }
    }
    return state to connection
}

@Composable
internal fun Md3eIosChromeHost(enabled: Boolean, motionActive: Boolean, refraction: Boolean = true,
    content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val backdrop = rememberLayerBackdrop { drawRect(scheme.background); drawContent() }
    val dockBackdrop = remember(backdrop) { IosDockMaterialBackdrop(backdrop) }
    CompositionLocalProvider(
        dev.t1m3.qplayer.android.ui.LocalIosDesign provides enabled,
        LocalGlassRefraction provides refraction,
        LocalGlassDark provides (scheme.background.luminance() < .5f),
        LocalGlassTint provides scheme.background,
        LocalGlassLuminance provides scheme.background.luminance(),
        LocalIosPageBackdrop provides backdrop,
        LocalIosDockBackdrop provides dockBackdrop,
        // Page controls are recorded inside backdrop. Sampling that same layer
        // would create a RenderNode cycle and crash Android's RenderThread.
        // Only sibling floating chrome may consume the recorded page layer.
        LocalIosTopBarBackdrop provides null,
        LocalGlassMotionActive provides motionActive,
    ) { QPlayerDialogBackdropHost(enabled, content) }
}

@Composable
internal fun Md3eIosMiniPlayer(runtime: Md3eRuntime, onOpen: () -> Unit,
    expansion: Md3ePlayerExpansionState, compact: () -> Float, modifier: Modifier) {
    val backdrop = LocalIosDockBackdrop.current ?: return
    val adaptive = rememberRegionAdaptiveGlass(backdrop)
    val highlight = rememberIosGlassHighlight()
    val ink = adaptive.contentColor
    val state = runtime.playback
    val enabled by remember(compact) { derivedStateOf { compact() < .01f } }
    val view = LocalView.current
    val openInteraction = remember { MutableInteractionSource() }
    val rendering = LocalMd3eRenderingActive.current && !LocalMd3eMotionActive.current && !Md3eLowSpecMode.current
    fun haptic(action: () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        action()
    }
    Surface(modifier.fillMaxWidth().layout { measurable, constraints ->
        val h = (48f - 4f * compact()).dp.roundToPx()
        val child = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(child.width, child.height) { child.placeRelative(0, 0) }
    }.md3ePlayerExpansionSource(expansion)
        .then(iosLiquidGlassModifier(backdrop, LocalGlassDark.current, interaction = highlight, adaptive = adaptive, strongRefraction = true))
        .clickable(interactionSource = openInteraction, indication = null) { haptic(onOpen) },
        shape = CircleShape, tonalElevation = 0.dp, shadowElevation = 0.dp, color = Color.Transparent) {
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).graphicsLayer {
                val scale = 1f - compact() / 16f; scaleX = scale; scaleY = scale
            }.onGloballyPositioned { expansion.miniDiscBounds = it.boundsInRoot() }) {
                if (runtime.experimentalAiDjWaiting) Md3eAiMascot(Modifier.fillMaxSize(), runtime.experimentalAiDjStage)
                else Box(Modifier.fillMaxSize().graphicsLayer {
                    alpha = if (expansion.active && expansion.detailShowsDisc) 0f else 1f
                }) {
                    if (Md3eLowSpecMode.current) Artwork(state.cover, Modifier.fillMaxSize(), corner = 8.dp)
                    else AnimatedContent(targetState = state.cover,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                        label = "ios_mini_cover_transition") { cover ->
                        Artwork(cover, Modifier.fillMaxSize(), corner = 8.dp)
                    }
                }
            }
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(if (runtime.experimentalAiDjWaiting) runtime.experimentalAiDjMessage else state.title.ifBlank { "未播放" },
                    maxLines = 1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink,
                    modifier = Modifier.graphicsLayer().basicMarquee(
                        iterations = if (rendering) Int.MAX_VALUE else 0, initialDelayMillis = 600))
                if (!runtime.experimentalAiDjWaiting) Text(state.artist, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = ink.copy(alpha = .65f))
            }
            Spacer(Modifier.width(5.dp))
            if (!runtime.experimentalAiDjWaiting && !runtime.transportState.privateFm) IosMiniTransportSlot(compact, enabled) {
                IconButton(onClick = { haptic { runtime.previous() } }, enabled = enabled, modifier = Modifier.size(40.dp)) {
                Icon(Md3eIcons.SkipPrevious, "上一首", Modifier.size(24.dp), tint = ink)
                }
            }
            IconButton(onClick = { haptic { runtime.toggle() } }, modifier = Modifier.size(40.dp)) {
                Icon(if (runtime.transportState.playing) Md3eIcons.Pause else Md3eIcons.PlayArrow,
                    if (runtime.transportState.playing) "暂停" else "播放", Modifier.size(26.dp), tint = ink)
            }
            if (!runtime.experimentalAiDjWaiting) IosMiniTransportSlot(compact, enabled) {
                IconButton(onClick = { haptic { runtime.next() } }, enabled = enabled, modifier = Modifier.size(40.dp)) {
                Icon(Md3eIcons.SkipNext, "下一首", Modifier.size(24.dp), tint = ink)
                }
            }
        }
    }
}

// Original CompactMiniTransportSlot: animate both width and opacity with the dock's clock.
@Composable
private fun IosMiniTransportSlot(progress: () -> Float, enabled: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.layout { measurable, constraints ->
        val side = 40.dp.roundToPx()
        val child = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(side, side))
        val width = (side * (1f - progress().coerceIn(0f, 1f))).toInt()
        layout(width, child.height) { child.placeRelative(0, 0) }
    }.graphicsLayer {
        clip = true
        alpha = (1f - progress() * 3f).coerceAtLeast(0f)
    }.then(if (!enabled) Modifier.clearAndSetSemantics {} else Modifier)) { content() }
}
