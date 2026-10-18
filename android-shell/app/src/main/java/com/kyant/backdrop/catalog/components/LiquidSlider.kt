// QPlayer adaptation of Kyant Backdrop visuals, Copyright 2025 Kyant, Apache-2.0.
package com.kyant.backdrop.catalog.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import dev.t1m3.qplayer.android.ui.controlGlassEffects
import dev.t1m3.qplayer.android.ui.rememberIosAdaptiveGlass
import dev.t1m3.qplayer.android.ui.rememberIosControlGlassHighlight
import dev.t1m3.qplayer.android.ui.controlGlassShadow
import dev.t1m3.qplayer.android.ui.drawControlGlassSurface
import dev.t1m3.qplayer.android.ui.LocalGlassRefraction

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    dark: Boolean = isSystemInDarkTheme(),
    steps: Int = 0,
    onValueChangeFinished: () -> Unit = {},
) {
    val refraction = LocalGlassRefraction.current
    val interactions = remember { MutableInteractionSource() }
    val dragged by interactions.collectIsDraggedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val press = animateFloatAsState(if (dragged || pressed) 1f else 0f, tween(180), label = "slider_glass_press")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val accent = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)
    val trackColor = if (dark) Color(0xFF787880).copy(alpha = 0.36f) else Color(0xFF787878).copy(alpha = 0.2f)
    // The official slider owns touch slop, local drag accumulation, consumption,
    // cancellation, keyboard/accessibility and RTL. Never feed an animated thumb
    // position or an asynchronous parent value back into incremental drag deltas.
    Slider(
        value = value().coerceIn(valueRange), onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().height(48.dp),
        valueRange = valueRange, steps = steps, interactionSource = interactions,
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            val adaptive = rememberIosAdaptiveGlass(backdrop)
            val glassHighlight = rememberIosControlGlassHighlight()
            Box(Modifier.size(40.dp, 24.dp).then(adaptive.modifier).drawBackdrop(
                backdrop = backdrop, shape = { CircleShape },
                effects = {
                    controlGlassEffects(adaptive, refraction)
                },
                highlight = { glassHighlight.value },
                shadow = { controlGlassShadow(dark, adaptive) },
                onDrawSurface = { drawControlGlassSurface(dark, adaptive) },
                layerBlock = {
                    val scale = if (refraction) 1f + 0.25f * press.value else 1f
                    scaleX = scale; scaleY = scale
                },
                innerShadow = null,
            ))
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val fraction = ((state.value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                val start = Offset(if (rtl) size.width else 0f, size.height / 2f)
                val end = Offset(if (rtl) 0f else size.width, size.height / 2f)
                drawLine(trackColor, start, end, size.height, StrokeCap.Round)
                if (fraction > 0f) drawLine(accent, start,
                    Offset(start.x + (end.x - start.x) * fraction, start.y), size.height, StrokeCap.Round)
            }
        }
    )
}
