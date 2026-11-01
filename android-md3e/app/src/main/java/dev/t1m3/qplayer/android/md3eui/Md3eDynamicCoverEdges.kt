package dev.t1m3.qplayer.android.md3eui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Blend one live video recording from sharp centre to blurred, transparent edges. */
@Composable
internal fun Modifier.dynamicCoverSoftEdges(): Modifier {
    // RenderEffect is unavailable before Android 12; fading into the 64px ambient
    // backdrop still avoids a hard edge without copying frames to the CPU.
    if (Build.VERSION.SDK_INT < 31) return graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        clip = true
    }.drawWithCache {
        val fade = dynamicCoverEdgeMask(size.height, 96.dp.toPx(), 160.dp.toPx(), true)
        onDrawWithContent { drawContent(); drawRect(fade, blendMode = BlendMode.DstIn) }
    }

    val source = rememberGraphicsLayer()
    val soft = rememberGraphicsLayer()
    val sharp = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { 24.dp.toPx() }
    val blur = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
    SideEffect {
        soft.renderEffect = blur
        sharp.compositingStrategy = LayerCompositingStrategy.Offscreen
    }
    return graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        clip = true
    }.drawWithCache {
        val sharpMask = dynamicCoverEdgeMask(size.height, 96.dp.toPx(), 160.dp.toPx(), false)
        val fade = dynamicCoverEdgeMask(size.height, 96.dp.toPx(), 160.dp.toPx(), true)
        onDrawWithContent {
            // These are GPU RenderNodes referencing the same TextureView, not
            // extra players, bitmap screenshots or independently timed frames.
            source.record { this@onDrawWithContent.drawContent() }
            soft.record { drawLayer(source) }
            sharp.record {
                drawLayer(source)
                drawRect(sharpMask, blendMode = BlendMode.DstIn)
            }
            drawLayer(soft)
            drawLayer(sharp)
            // Fade the blurred extremities into the ambient behind the video.
            drawRect(fade, blendMode = BlendMode.DstIn)
        }
    }
}

/** Smoothstep samples avoid visible steps or a seam where the clear centre starts. */
private fun dynamicCoverEdgeMask(height: Float, topPixels: Float, bottomPixels: Float,
    fadeOnly: Boolean): Brush {
    val safeHeight = height.coerceAtLeast(1f)
    val top = minOf(topPixels, safeHeight * .22f) * if (fadeOnly) .6f else 1f
    val bottom = minOf(bottomPixels, safeHeight * .30f) * if (fadeOnly) .6f else 1f
    val stops = ArrayList<Pair<Float, Color>>(18)
    for (step in 0..8) {
        val t = step / 8f
        val alpha = t * t * (3f - 2f * t)
        stops.add(top / safeHeight * t to Color.White.copy(alpha = alpha))
    }
    for (step in 0..8) {
        val t = step / 8f
        val alpha = 1f - t * t * (3f - 2f * t)
        stops.add(1f - bottom / safeHeight + bottom / safeHeight * t to Color.White.copy(alpha = alpha))
    }
    return Brush.verticalGradient(*stops.toTypedArray())
}
