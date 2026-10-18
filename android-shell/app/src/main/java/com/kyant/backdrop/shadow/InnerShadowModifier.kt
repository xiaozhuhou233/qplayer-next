// QPlayer Android compatibility adaptation, 2026-09-28. Original: Copyright 2025 Kyant, Apache-2.0.
package com.kyant.backdrop.shadow

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.internal.ShapeProvider
import com.kyant.backdrop.internal.clipOutline
import com.kyant.backdrop.isRenderEffectSupported
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

internal class InnerShadowElement(
    val shapeProvider: ShapeProvider,
    val shadow: () -> InnerShadow?
) : ModifierNodeElement<InnerShadowNode>() {

    override fun create(): InnerShadowNode {
        return InnerShadowNode(shapeProvider, shadow)
    }

    override fun update(node: InnerShadowNode) {
        node.shapeProvider = shapeProvider
        node.shadow = shadow
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "innerShadow"
        properties["shapeProvider"] = shapeProvider
        properties["shadow"] = shadow
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InnerShadowElement) return false

        if (shapeProvider != other.shapeProvider) return false
        if (shadow != other.shadow) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + shadow.hashCode()
        return result
    }
}

internal class InnerShadowNode(
    var shapeProvider: ShapeProvider,
    var shadow: () -> InnerShadow?
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var shadowLayer: GraphicsLayer? = null

    private var clipPath: Path? = null

    private var prevRadius = Float.NaN
    private data class Recording(val size: IntSize, val outline: Outline,
        val x: Float, val y: Float, val color: Color)
    private var recording: Recording? = null

    override fun ContentDrawScope.draw() {
        drawContent()

        if (!isRenderEffectSupported()) return

        val shadow = shadow() ?: return
        if (shadow.alpha <= 0f || shadow.color.alpha <= 0f) return

        val shadowLayer = shadowLayer
        if (shadowLayer != null) {
            val size = size
            val density: Density = this
            val layoutDirection = layoutDirection

            if (size.width <= 0f || size.height <= 0f) return
            val radius = shadow.radius.toPx().coerceAtLeast(0f)
            val offsetX = shadow.offset.x.toPx()
            val offsetY = shadow.offset.y.toPx()
            // Blur the OUTSIDE mask into the shape, then clip to its inside.
            // Filling the shape and clearing that same shape before blurring
            // erased every pixel at zero offset, so a centred inner ring vanished.
            // Three blur radii keep the texture boundary outside the visible rim.
            val padding = ceil(radius * 3f + max(abs(offsetX), abs(offsetY)) + 1f)
            val layerSize = IntSize(
                ceil(size.width + padding * 2f).toInt(),
                ceil(size.height + padding * 2f).toInt(),
            )

            val outline = shapeProvider.shape.createOutline(size, layoutDirection, density)
            val clipPath =
                if (outline is Outline.Rounded) {
                    clipPath ?: Path().also { clipPath = it }
                } else {
                    null
                }

            val key = Recording(layerSize, outline, offsetX, offsetY, shadow.color)

            shadowLayer.alpha = shadow.alpha
            shadowLayer.blendMode = shadow.blendMode
            if (prevRadius != radius) {
                shadowLayer.renderEffect =
                    if (radius > 0f) {
                        BlurEffect(radius, radius, TileMode.Decal)
                    } else {
                        null
                    }
                prevRadius = radius
            }
            if (key != recording) {
                shadowLayer.record(layerSize) {
                    drawRect(shadow.color)
                    val canvas = drawContext.canvas
                    canvas.save()
                    canvas.translate(padding + offsetX, padding + offsetY)
                    canvas.drawOutline(outline, ShadowMaskPaint)
                    canvas.restore()
                }
                recording = key
            }

            val canvas = drawContext.canvas
            canvas.save()
            canvas.clipOutline(outline, clipPath)
            canvas.translate(-padding, -padding)
            drawLayer(shadowLayer)
            canvas.restore()
        }
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        shadowLayer =
            graphicsContext.createGraphicsLayer().apply {
                compositingStrategy = CompositingStrategy.Offscreen
            }
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        shadowLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            shadowLayer = null
            recording = null
            prevRadius = Float.NaN
            clipPath = null
        }
    }

}

private val ShadowMaskPaint = Paint().apply {
    blendMode = BlendMode.Clear
}
