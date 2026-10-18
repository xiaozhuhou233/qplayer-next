package dev.t1m3.qplayer.android.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

// Kyant LiquidButton's spring/drag transform, applied to the whole player so
// artwork, text and sampled glass move together. Observes, never consumes drag.
@Composable
internal fun rememberIosGlassHighlight(): InteractiveHighlight {
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    DisposableEffect(highlight) { onDispose { highlight.cancel() } }
    return highlight
}

@Composable
internal fun rememberIosGlassInteraction(
    highlight: InteractiveHighlight = rememberIosGlassHighlight(),
    drawHighlight: Boolean = true,
): Modifier {
    return Modifier.graphicsLayer {
        if (size.minDimension > 0f) {
            val offset = highlight.offset
            val dragScale = 4.dp.toPx() / size.height
            val base = 1f + dragScale * highlight.pressProgress
            val maxOffset = size.minDimension
            translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
            translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)
            val angle = atan2(offset.y, offset.x)
            scaleX = base + dragScale * abs(cos(angle) * offset.x / size.maxDimension) *
                (size.width / size.height).coerceAtMost(1f)
            scaleY = base + dragScale * abs(sin(angle) * offset.y / size.maxDimension) *
                (size.height / size.width).coerceAtMost(1f)
        }
    }.clip(CircleShape)
        .then(if (drawHighlight) highlight.modifier else Modifier)
        .then(highlight.gestureModifier)
}
