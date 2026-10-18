@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.t1m3.qplayer.android.md3eui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// Adapted from PixelPlayer UnifiedPlayerSheetV2, SheetVisualState and FullPlayerVisualState.
// Copyright (c) 2026 Theo Vilardo. See assets/licenses/PixelPlayer-LICENSE.txt.
internal fun pixelMiniAlpha(fraction: Float) = (1f - fraction * 2f).coerceIn(0f, 1f)
internal fun pixelDetailAlpha(fraction: Float) = ((fraction - .25f) / .75f).coerceIn(0f, 1f)

/** The sheet grows; the full-screen content keeps its final layout throughout the motion. */
@Composable
internal fun SonicPixelPlayerExpansionHost(expanded: Boolean, state: Md3ePlayerExpansionState,
    durationMillis: Int, showMini: Boolean, base: @Composable () -> Unit, detail: @Composable () -> Unit) {
    val density = LocalDensity.current
    val rendering = LocalMd3eRenderingActive.current
    val dragging = state.pixelDragFraction != null
    SideEffect { state.iosMotion = null }
    LaunchedEffect(dragging) { if (dragging) state.progress.stop() }
    LaunchedEffect(expanded, state.pixelSettleRevision, durationMillis) {
        val drag = state.pixelDragFraction
        if (drag != null) {
            state.progress.snapTo(drag)
            state.pixelDragFraction = null
        }
        // `expanded` participates in the derived active flag; use the previous
        // endpoint so the first frame still captures the mini surface.
        if (expanded && !state.expanded) state.prepareOpen()
        if (!expanded && state.active && state.conversationLayer == null) state.refreshSnapshot = true
        state.expanded = expanded
        val target = if (expanded) 1f else 0f
        if (durationMillis == 0) state.progress.snapTo(target)
        else state.progress.animateTo(target, tween(
            durationMillis = (durationMillis * kotlin.math.abs(target - state.progress.value)).toInt().coerceAtLeast(80),
            easing = CubicBezierEasing(.22f, .8f, .2f, 1f)))
        state.pixelReleaseVelocity = 0f
        if (!expanded) state.conversationLayer = null
    }
    val miniColor = MaterialTheme.colorScheme.surfaceContainerLow
    val detailColor = if (state.immersiveVideo) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    Box(Modifier.fillMaxSize().onSizeChanged { state.hostSize = it }
        .onGloballyPositioned { state.hostOrigin = it.positionInRoot() }) {
        val baseRendering by remember(state, rendering) {
            derivedStateOf { rendering && (state.refreshSnapshot || !state.active) }
        }
        Box(Modifier.fillMaxSize().then(if (expanded || state.active) Modifier.clearAndSetSemantics {} else Modifier)) {
            CompositionLocalProvider(LocalMd3eRenderingActive provides baseRendering) { base() }
        }
        if (expanded || state.active) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val p = state.fraction.coerceIn(0f, 1f)
                val bounds = state.bounds(size, density.density)
                shape = PixelSheetShape(bounds, 24.dp.toPx() * (1f - p))
                clip = true
                transformOrigin = TransformOrigin(.5f, 1f)
            }) {
                // One continuously painted surface prevents flashes between the two content layers.
                Box(Modifier.fillMaxSize().drawWithContent {
                    drawRect(lerp(miniColor, detailColor, state.fraction.coerceIn(0f, 1f)))
                })
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val p = state.fraction.coerceIn(0f, 1f)
                    alpha = pixelDetailAlpha(p)
                    translationY = state.bounds(size, density.density).top + 24.dp.toPx() * (1f - alpha)
                }) {
                    CompositionLocalProvider(LocalMd3eRenderingActive provides (rendering && !state.moving)) { detail() }
                }
                val sourceWidth = state.flightBounds.width
                val sourceHeight = state.flightBounds.height
                if (showMini && state.hasSnapshot && sourceWidth > 0f && sourceHeight > 0f) {
                    Box(Modifier.size(with(density) { sourceWidth.toDp() }, with(density) { sourceHeight.toDp() })
                        .clearAndSetSemantics {}.graphicsLayer {
                            val host = Size(state.hostSize.width.toFloat(), state.hostSize.height.toFloat())
                            val bounds = state.bounds(host, density.density)
                            translationX = bounds.center.x - sourceWidth / 2f
                            translationY = bounds.top
                            if (state.conversationLayer != null) {
                                transformOrigin = TransformOrigin(.5f, 0f)
                                scaleX = bounds.width / sourceWidth
                                scaleY = scaleX
                            }
                            alpha = pixelMiniAlpha(state.fraction)
                        }.drawWithContent { drawLayer(state.flightLayer) })
                }
            }
        }
    }
}

private data class PixelSheetShape(val bounds: Rect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(bounds, CornerRadius(radius)))
}
