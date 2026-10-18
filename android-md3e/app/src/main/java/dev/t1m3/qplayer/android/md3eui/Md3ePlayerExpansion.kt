package dev.t1m3.qplayer.android.md3eui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

@Stable
internal class Md3ePlayerExpansionState(val miniLayer: GraphicsLayer) {
    private val defaultProgress = Animatable(0f)
    var iosMotion by mutableStateOf<PageMotion?>(null)
    val progress get() = iosMotion?.progress ?: defaultProgress
    val fraction get() = pixelDragFraction ?: iosMotion?.value ?: progress.value
    val settle = Animatable(0f)
    var expanded by mutableStateOf(false)
    var miniBounds by mutableStateOf(Rect.Zero)
    var miniDiscBounds by mutableStateOf(Rect.Zero)
    var flightDiscBounds by mutableStateOf(Rect.Zero)
    var targetDiscBounds by mutableStateOf(Rect.Zero)
    var closingDiscBounds by mutableStateOf(Rect.Zero)
    var detailShowsDisc by mutableStateOf(true)
    var immersiveVideo by mutableStateOf(false)
    var flightBounds by mutableStateOf(Rect.Zero)
    var hostOrigin by mutableStateOf(Offset.Zero)
    var hostSize by mutableStateOf(IntSize.Zero)
    var refreshSnapshot by mutableStateOf(false)
    var hasSnapshot by mutableStateOf(false)
    var conversationLayer by mutableStateOf<GraphicsLayer?>(null)
    val flightLayer get() = conversationLayer ?: miniLayer
    var pixelDragFraction by mutableStateOf<Float?>(null)
    var pixelReleaseVelocity by mutableStateOf(0f)
    var pixelSettleRevision by mutableStateOf(0)
    val active: Boolean by derivedStateOf { expanded || pixelDragFraction != null || progress.value > 0f || iosMotion?.mounted == true }

    val moving: Boolean by derivedStateOf {
        pixelDragFraction != null || iosMotion?.let { it.moving || it.phase == MotionPhase.PREPARING } == true ||
            progress.isRunning || settle.isRunning ||
            (expanded && progress.value < 1f) || (!expanded && progress.value > 0f)
    }

    fun prepareOpen() {
        val returningFromCard = conversationLayer != null
        conversationLayer = null
        if (!active || returningFromCard) {
            flightBounds = miniBounds
            flightDiscBounds = miniDiscBounds
        }
        refreshSnapshot = true
        expanded = true
    }

    fun prepareConversationOpen(bounds: Rect, layer: GraphicsLayer) {
        if (active || bounds.width <= 0f || bounds.height <= 0f) return
        flightBounds = bounds
        conversationLayer = layer
        hasSnapshot = true
        refreshSnapshot = false
        expanded = true
    }

    fun returnToMiniSource() {
        if (conversationLayer == null) return
        conversationLayer = null
        flightBounds = miniBounds
        refreshSnapshot = true
    }

    fun source(size: Size, density: Float): Rect {
        // Navigation can move the dock while detail is open. Land on its current bounds.
        val source = if (!expanded && conversationLayer == null && miniBounds.width > 0f) miniBounds else flightBounds
        if (source.width > 0f && source.height > 0f) {
            val width = source.width.coerceAtMost(size.width)
            val height = source.height.coerceAtMost(size.height)
            val left = (source.left - hostOrigin.x).coerceIn(0f, (size.width - width).coerceAtLeast(0f))
            val top = (source.top - hostOrigin.y).coerceIn(0f, (size.height - height).coerceAtLeast(0f))
            return Rect(left, top, left + width, top + height)
        }
        val inset = (20f * density).coerceAtMost(size.width / 4f)
        val bottom = (size.height - 84f * density).coerceAtLeast(0f)
        return Rect(inset, (bottom - 52f * density).coerceAtLeast(0f), size.width - inset, bottom)
    }

    fun bounds(size: Size, density: Float): Rect {
        val start = source(size, density)
        val p = fraction
        return Rect(lerp(start.left, 0f, p), lerp(start.top, 0f, p),
            lerp(start.right, size.width, p), lerp(start.bottom, size.height, p))
    }
}

@Composable
internal fun rememberMd3ePlayerExpansionState(): Md3ePlayerExpansionState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Md3ePlayerExpansionState(layer) }
}

internal fun Modifier.md3ePlayerExpansionSource(state: Md3ePlayerExpansionState): Modifier =
    onGloballyPositioned { coordinates ->
        state.miniBounds = coordinates.boundsInRoot()
    }.drawWithContent {
        if (state.refreshSnapshot && state.conversationLayer == null) {
            state.miniLayer.record { this@drawWithContent.drawContent() }
            state.hasSnapshot = true
            state.refreshSnapshot = false
        }
        if (!state.active) drawContent()
    }

internal fun Modifier.md3ePlayerChromeExit(state: Md3ePlayerExpansionState): Modifier = graphicsLayer {
    val p = state.fraction
    translationY = (size.height + 40.dp.toPx()) * p
    alpha = 1f - p
}

private data class Md3ePlayerRevealShape(val bounds: Rect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(bounds, CornerRadius(radius)))
}

@Composable
internal fun Md3ePlayerExpansionHost(
    expanded: Boolean,
    state: Md3ePlayerExpansionState,
    durationMillis: Int,
    simpleSlide: Boolean = false,
    claude: Boolean = false,
    cover: String = "",
    showDisc: Boolean = true,
    base: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    if (LocalSonicDesign.current) {
        SonicPixelPlayerExpansionHost(expanded, state, durationMillis, showDisc, base, detail)
        return
    }
    if (simpleSlide) {
        LaunchedEffect(state) {
            state.expanded = false
            state.progress.snapTo(0f)
            state.settle.snapTo(0f)
        }
        Box(Modifier.fillMaxSize()) {
            base()
            AnimatedVisibility(
                visible = expanded,
                enter = if (durationMillis == 0) androidx.compose.animation.EnterTransition.None else
                    slideInVertically(tween(250, easing = playerArtworkEasing), initialOffsetY = { it }) +
                        fadeIn(tween(250, easing = playerArtworkEasing)),
                exit = if (durationMillis == 0) androidx.compose.animation.ExitTransition.None else
                    slideOutVertically(tween(250, easing = playerArtworkEasing), targetOffsetY = { it }) +
                        fadeOut(tween(250, easing = playerArtworkEasing)),
            ) { detail() }
        }
        return
    }

    val ios = LocalIosDesign.current
    val musicMotion = rememberPageMotion(enterAnimation = PlayerEnterAnimation,
        exitAnimation = PlayerReturnAnimation, dragAnimation = PlayerGestureSettlementAnimation,
        immediateGestureHandoff = true)
    SideEffect {
        state.iosMotion = if (ios) musicMotion else null
        if (ios) {
            musicMotion.reduceMotion = durationMillis == 0
            musicMotion.updateHost(Rect(state.hostOrigin, Size(state.hostSize.width.toFloat(), state.hostSize.height.toFloat())))
            if (state.miniDiscBounds.width > 0f) musicMotion.sources["cover"] = MotionAnchor(state.miniDiscBounds, 8f)
            if (state.targetDiscBounds.width > 0f) musicMotion.targets["cover"] = MotionAnchor(state.targetDiscBounds, 12f)
            musicMotion.waitForTarget = state.detailShowsDisc
            musicMotion.beforeShownHandoff = ::settlePlaylistCoverHandoff
        }
    }
    val density = LocalDensity.current
    val miniColor = MaterialTheme.colorScheme.surfaceContainerLow
    val playerColor = if (state.immersiveVideo) Color.Black else MaterialTheme.colorScheme.background
    val rendering = LocalMd3eRenderingActive.current
    LaunchedEffect(expanded, durationMillis, claude, ios) {
        state.settle.snapTo(0f)
        if (expanded) {
            if (!state.expanded) state.prepareOpen()
        } else {
            if (state.active) {
                state.refreshSnapshot = true
                state.closingDiscBounds = state.targetDiscBounds
            }
            state.expanded = false
        }
        if (ios) { musicMotion.request(expanded); return@LaunchedEffect }
        val target = if (expanded) 1f else 0f
        if (state.fraction == target) {
            // A full-screen modal may have completed the opening itself.
        } else if (durationMillis == 0) {
            state.progress.snapTo(if (expanded) 1f else 0f)
        } else {
            state.progress.animateTo(if (expanded) 1f else 0f,
                tween(if (claude) { if (expanded) ClaudeOneTake.CARRY else ClaudeOneTake.TRAVEL } else durationMillis,
                    easing = playerArtworkEasing))
            if (expanded && !claude) state.settle.animateTo(0f,
                spring(dampingRatio = .68f, stiffness = 500f), initialVelocity = 38f)
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { state.hostSize = it }
        .onGloballyPositioned { state.hostOrigin = it.positionInRoot() }) {
        Box(Modifier.fillMaxSize()
            .claudeTransitionBackdrop {
                // One geometric clock for opening, closing and reversals; both endpoints are clear.
                val p = state.fraction.coerceIn(0f, 1f)
                if (claude && durationMillis > 0) ClaudeOneTake.ExpoOut.transform(2f * minOf(p, 1f - p)) else 0f
            }
            .then(if (expanded || state.active) Modifier.clearAndSetSemantics {} else Modifier)) {
            val baseRendering by remember(state, rendering) {
                derivedStateOf { rendering && (state.refreshSnapshot || !state.active) }
            }
            CompositionLocalProvider(LocalMd3eRenderingActive provides baseRendering) { base() }
        }
        if (expanded || state.active) {
            Box(Modifier.fillMaxSize()
                .graphicsLayer {
                    val p = state.fraction
                    shape = if (p >= 1f) androidx.compose.ui.graphics.RectangleShape else
                        Md3ePlayerRevealShape(state.bounds(size, density.density),
                            (if (claude) minOf(32.dp.toPx(), state.source(size, density.density).height * .5f)
                                else state.source(size, density.density).height * .5f) * (1f - p))
                    clip = true
                    if (claude) shadowElevation = 4.dp.toPx() * (1f - p)
                    val settlePx = state.settle.value.dp.toPx()
                    transformOrigin = TransformOrigin(.5f, 1f)
                    scaleX = 1f + settlePx / size.width.coerceAtLeast(1f)
                    scaleY = 1f + settlePx / size.height.coerceAtLeast(1f)
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                        } while (event.changes.any { it.pressed })
                    }
                }) {
                if (claude) Box(Modifier.fillMaxSize().drawBehind {
                    drawRect(androidx.compose.ui.graphics.lerp(miniColor, playerColor, state.fraction))
                })
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val bounds = state.bounds(size, density.density)
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = if (claude) 0f else bounds.left
                    translationY = if (claude) 0f else bounds.top
                    scaleX = if (claude) 1f else if (size.width > 0f) bounds.width / size.width else 1f
                    scaleY = if (claude) 1f else if (size.height > 0f) bounds.height / size.height else 1f
                    alpha = if (ios) contentReveal(state.fraction) else if (claude) ((state.fraction - .35f) / .65f).coerceIn(0f, 1f) else ((state.fraction - .06f) / .48f).coerceIn(0f, 1f)
                }) {
                    CompositionLocalProvider(LocalMd3eRenderingActive provides (rendering && !state.moving)) { detail() }
                }
                val sourceWidth = state.flightBounds.width
                val sourceHeight = state.flightBounds.height
                if (showDisc && state.hasSnapshot && sourceWidth > 0f && sourceHeight > 0f) {
                    Box(Modifier.size(with(density) { sourceWidth.toDp() }, with(density) { sourceHeight.toDp() })
                        .clearAndSetSemantics {}
                        .graphicsLayer {
                            val hostSize = Size(state.hostSize.width.toFloat(), state.hostSize.height.toFloat())
                            val bounds = state.bounds(hostSize, density.density)
                            transformOrigin = TransformOrigin(0f, 0f)
                            translationX = bounds.left
                            scaleX = if (claude) 1f + (bounds.width / sourceWidth - 1f) * .18f else bounds.width / sourceWidth
                            scaleY = scaleX
                            translationY = bounds.center.y - sourceHeight * scaleY * .5f
                            // Claude's labels begin returning at 70% expansion, well before the final landing.
                            alpha = if (claude) ((.70f - state.fraction) / .70f).coerceIn(0f, 1f)
                                else (1f - (state.fraction - .06f) / .48f).coerceIn(0f, 1f)
                        }.drawWithContent { drawLayer(state.miniLayer) })
                }
            }
            if (showDisc && claude && state.moving && state.miniDiscBounds.width > 0f) {
                val mini = if (state.expanded && state.flightDiscBounds.width > 0f) state.flightDiscBounds
                    else state.miniDiscBounds
                val detailBounds = if (!state.expanded && state.closingDiscBounds.width > 0f) state.closingDiscBounds
                    else state.targetDiscBounds
                val start = (if (ios && musicMotion.hasSharedCover) musicMotion.sourceSnapshot["cover"]!!.bounds else mini).translate(-state.hostOrigin)
                val endBounds = if (ios && musicMotion.hasSharedCover) musicMotion.targetSnapshot["cover"]!!.bounds else detailBounds
                val end = if (endBounds.width > 0f) endBounds.translate(-state.hostOrigin) else
                    Rect(state.hostSize.width * .45f, state.hostSize.height * .24f,
                        state.hostSize.width * .9f, state.hostSize.height * .24f + state.hostSize.width * .45f)
                // Keep one full-resolution layer. Only its transform changes each frame;
                // remeasuring/resizing the artwork used to round the final landing twice.
                val layerEdge = maxOf(start.width, start.height, end.width, end.height)
                val edge = with(density) { layerEdge.toDp() }
                val frameColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)
                ClaudePlayerArtwork(cover, Modifier.size(edge).graphicsLayer {
                    val p = state.fraction.coerceIn(0f, 1f)
                    val bounds = playerArtworkFlight(start, end, p)
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = bounds.left
                    translationY = bounds.top
                    scaleX = bounds.width / size.width
                    scaleY = bounds.height / size.height
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(
                        (lerp((if (ios) 6 else 7).dp.toPx(), (if (ios) 12 else 24).dp.toPx(), p) / scaleX.coerceAtLeast(.001f)).toDp())
                    clip = true
                    shadowElevation = lerp(1.dp.toPx(), 8.dp.toPx(), p) / scaleX.coerceAtLeast(.001f)
                    alpha = if (state.detailShowsDisc) 1f else 1f - p
                }.drawWithContent {
                    drawContent()
                    val p = state.fraction.coerceIn(0f, 1f)
                    val scale = (playerArtworkFlight(start, end, p).width / size.width).coerceAtLeast(.001f)
                    drawRoundRect(frameColor, cornerRadius = CornerRadius(lerp((if (ios) 8 else 7).dp.toPx(), (if (ios) 12 else 24).dp.toPx(), p) / scale),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(.7.dp.toPx() / scale))
                }.clearAndSetSemantics {}, corner = 0.dp, elevation = 0.dp, decorated = false)
            }
        }
    }
}
