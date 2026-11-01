package dev.t1m3.qplayer.android.md3eui

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** One quarter-resolution blur pass. The sharp video never passes through it. */
@Composable
internal fun Modifier.dynamicCoverSoftEdges(enabled: Boolean, coverFraction: Float): Modifier {
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density).toFloat()
    val clipped = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; clip = true }
    // Keep only the inexpensive scrims when disabled; allocate no reflection/blur RenderNodes.
    if (!enabled) return drawWithCache {
        val topShadow = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .64f),
            .25f to Color.Black.copy(alpha = .46f),
            .65f to Color.Black.copy(alpha = .16f),
            1f to Color.Transparent,
            endY = minOf(size.height * .24f, topInset + 112.dp.toPx()))
        val shade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f)),
            startY = size.height * coverFraction, endY = size.height)
        onDrawWithContent { drawContent(); drawRect(topShadow); drawRect(shade) }
    }
    val lead = with(density) { 28.dp.toPx() }
    val tail = with(density) { 120.dp.toPx() }
    val padding = with(density) { 96.dp.toPx() }
    val reduction = 4f
    val source = rememberGraphicsLayer()
    val scene = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val masked = rememberGraphicsLayer()
    val effect = remember(density) {
        // Previously the deepest pass was 28dp. One 56dp kernel replaces both old passes.
        val radius = with(density) { 56.dp.toPx() } / reduction
        BlurEffect(radius, radius, TileMode.Clamp)
    }
    SideEffect {
        if (Build.VERSION.SDK_INT >= 31) {
            blurred.renderEffect = effect
            masked.compositingStrategy = LayerCompositingStrategy.Offscreen
        }
    }
    return clipped.drawWithCache {
        val bottom = (size.height * coverFraction).coerceIn(0f, size.height)
        val start = (bottom - lead).coerceAtLeast(0f)
        val end = minOf(size.height, bottom + tail)
        val stripTop = (start - padding).coerceAtLeast(0f)
        val stripSize = IntSize(ceil(size.width / reduction).toInt().coerceAtLeast(1),
            ceil((size.height - stripTop) / reduction).toInt().coerceAtLeast(1))
        val stops = (0..64).map { step ->
            val fraction = step / 64f
            val y = stripTop + fraction * stripSize.height * reduction
            val t = ((y - start) / (end - start).coerceAtLeast(1f)).coerceIn(0f, 1f)
            // Zero first/second derivatives at either end avoid a visible blur boundary.
            val alpha = t * t * t * (t * (t * 6f - 15f) + 10f)
            fraction to Color.White.copy(alpha = alpha.coerceIn(0f, 1f))
        }
        val mask = Brush.verticalGradient(*stops.toTypedArray(), endY = stripSize.height.toFloat())
        val topShadow = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .64f),
            .25f to Color.Black.copy(alpha = .46f),
            .65f to Color.Black.copy(alpha = .16f),
            1f to Color.Transparent,
            endY = minOf(size.height * .24f, topInset + 112.dp.toPx()))
        val shade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f)),
            startY = bottom, endY = size.height)
        onDrawWithContent {
            source.record { this@onDrawWithContent.drawContent() }
            scene.record {
                drawLayer(source)
                clipRect(top = bottom) {
                    scale(1f, -1f, pivot = Offset(0f, bottom)) { drawLayer(source) }
                }
            }
            drawLayer(scene)
            if (Build.VERSION.SDK_INT >= 31) {
                blurred.record(size = stripSize) {
                    scale(1f / reduction, 1f / reduction, pivot = Offset.Zero) {
                        translate(top = -stripTop) { drawLayer(scene) }
                    }
                }
                masked.record(size = stripSize) {
                    drawLayer(blurred)
                    drawRect(mask, blendMode = BlendMode.DstIn)
                }
                translate(top = stripTop) {
                    scale(reduction, reduction, pivot = Offset.Zero) { drawLayer(masked) }
                }
            }
            drawRect(topShadow)
            drawRect(shade)
        }
    }
}
