// QPlayer Android compatibility adaptation, 2026-09-28. Original: Copyright 2025 Kyant, Apache-2.0.
package com.kyant.backdrop.backdrops

import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.internal.InverseLayerScope

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    val currentDraw = rememberUpdatedState(onDraw)
    // Page/theme updates must not replace the source and reset all glass samplers.
    return remember(graphicsLayer) {
        LayerBackdrop(graphicsLayer) { currentDraw.value(this) }
    }
}

@Stable
class LayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit
) : Backdrop {

    // A draw-only invalidation hook for live glass.  Scrolling content can
    // update its child layers without causing the sibling glass surface to
    // recompose; bumping this token makes LayerBackdropNode record the layer
    // again without rebuilding the page.
    private var invalidationToken by mutableIntStateOf(0)

    fun invalidate() {
        invalidationToken++
    }

    internal fun observeInvalidation() {
        invalidationToken
    }

    internal val recordsFullContent: Boolean get() = onDraw === DefaultOnDraw

    // Read only by the throttled sampler on the main thread. Deliberately NOT
    // Compose state: recording a background must never invalidate its consumers.
    override var samplingRevision: Long = 0L
        private set

    private val consumers = LinkedHashSet<() -> Unit>()
    private var notificationPending = false
    private val notifyConsumers = Choreographer.FrameCallback {
        notificationPending = false
        consumers.toList().forEach { it() }
    }

    override fun observeChanges(onChanged: () -> Unit): () -> Unit {
        consumers.add(onChanged)
        return {
            consumers.remove(onChanged)
            if (consumers.isEmpty() && notificationPending) {
                Choreographer.getInstance().removeFrameCallback(notifyConsumers)
                notificationPending = false
            }
        }
    }

    internal fun markRecordedForSampling() {
        samplingRevision++
        // Coalesce at the frame boundary. No state is written in draw(), and
        // only glass consumers redraw; the source page is never invalidated here.
        if (consumers.isNotEmpty() && !notificationPending) {
            notificationPending = true
            Choreographer.getInstance().postFrameCallback(notifyConsumers)
        }
    }

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
            val offset =
                try {
                    if (layerCoordinates.findRootCoordinates() === coordinates.findRootCoordinates()) {
                        layerCoordinates.localPositionOf(coordinates)
                    } else {
                        // Dialogs have their own window origin; window-relative
                        // positions would sample the wrong part of the page.
                        coordinates.positionOnScreen() - layerCoordinates.positionOnScreen()
                    }
                } catch (_: Exception) {
                    // TODO: outer transformations lead to wrong position calculation
                    coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                }
            translate(-offset.x, -offset.y)
        }) {
            drawLayer(graphicsLayer)
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}
