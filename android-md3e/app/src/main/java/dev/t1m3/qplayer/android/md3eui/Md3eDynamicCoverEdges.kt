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

/** Live reflection with blur confined to a quarter-resolution lower strip. */
@Composable
internal fun Modifier.dynamicCoverSoftEdges(): Modifier {
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density).toFloat() + with(density) { 8.dp.toPx() }
    val inset = with(density) { 8.dp.toPx() }
    val lead = with(density) { 16.dp.toPx() }
    val tail = with(density) { 96.dp.toPx() }
    val padding = with(density) { 32.dp.toPx() }
    val reduction = 4f
    val source = rememberGraphicsLayer()
    val scene = rememberGraphicsLayer()
    val light = rememberGraphicsLayer()
    val deep = rememberGraphicsLayer()
    val lightMasked = rememberGraphicsLayer()
    val deepMasked = rememberGraphicsLayer()
    val effects = remember(density) { listOf(8.dp, 28.dp).map {
        // The output is enlarged by four, so scale the kernel down with it.
        val radius = with(density) { it.toPx() } / reduction
        BlurEffect(radius, radius, TileMode.Clamp)
    } }
    SideEffect {
        if (Build.VERSION.SDK_INT >= 31) {
            light.renderEffect = effects[0]
            deep.renderEffect = effects[1]
            lightMasked.compositingStrategy = LayerCompositingStrategy.Offscreen
            deepMasked.compositingStrategy = LayerCompositingStrategy.Offscreen
        }
    }
    return graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        clip = true
    }.drawWithCache {
        // Match the unchanged 72%-height card and bottom padding. Start only
        // 16dp before its lower edge instead of blurring 64dp of the cover.
        val bottom = (size.height * .72f - inset).coerceIn(top + 1f, size.height)
        val start = bottom - minOf(lead, (bottom - top) * .04f)
        val end = minOf(size.height, bottom + tail)
        val stripTop = (start - padding).coerceAtLeast(0f)
        val stripSize = IntSize(ceil(size.width / reduction).toInt().coerceAtLeast(1),
            ceil((size.height - stripTop) / reduction).toInt().coerceAtLeast(1))
        val masks = (0..1).map { level ->
            val stops = (0..64).map { step ->
                val fraction = step / 64f
                val y = stripTop + fraction * stripSize.height * reduction
                val progress = ((y - start) / (end - start).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val t = (progress * 2f - level).coerceIn(0f, 1f)
                fraction to Color.White.copy(alpha = t * t * (3f - 2f * t))
            }
            Brush.verticalGradient(*stops.toTypedArray(), endY = stripSize.height.toFloat())
        }
        val topShadow = Brush.verticalGradient(
            0f to Color.Black,
            .25f to Color.Black.copy(alpha = .78f),
            .65f to Color.Black.copy(alpha = .30f),
            1f to Color.Transparent,
            endY = minOf(size.height * .24f, top + 112.dp.toPx()))
        val shade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f)),
            startY = bottom, endY = size.height)
        onDrawWithContent {
            // Preserve moving video and its reflection with one recording. The
            // sharp main video never goes through a blur or reduced-size layer.
            source.record { this@onDrawWithContent.drawContent() }
            scene.record {
                drawLayer(source)
                clipRect(top = bottom) {
                    scale(1f, -1f, pivot = Offset(0f, bottom)) { drawLayer(source) }
                }
            }
            drawLayer(scene)
            if (Build.VERSION.SDK_INT >= 31) {
                fun renderStrip(blurred: androidx.compose.ui.graphics.layer.GraphicsLayer,
                    masked: androidx.compose.ui.graphics.layer.GraphicsLayer, mask: Brush) {
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
                renderStrip(light, lightMasked, masks[0])
                renderStrip(deep, deepMasked, masks[1])
            }
            drawRect(topShadow)
            drawRect(shade)
        }
    }
}
