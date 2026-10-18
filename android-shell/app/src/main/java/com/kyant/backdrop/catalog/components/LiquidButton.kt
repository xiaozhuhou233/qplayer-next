// Adapted for QPlayer Android, Copyright 2025 Kyant, Apache-2.0.
package com.kyant.backdrop.catalog.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import dev.t1m3.qplayer.android.ui.rememberIosControlGlassHighlight
import dev.t1m3.qplayer.android.ui.drawControlGlassSurface
import dev.t1m3.qplayer.android.ui.drawBiliPaiReadabilityScrim
import dev.t1m3.qplayer.android.ui.controlGlassShadow
import dev.t1m3.qplayer.android.ui.controlGlassEffects
import dev.t1m3.qplayer.android.ui.rememberIosAdaptiveGlass
import dev.t1m3.qplayer.android.ui.LocalGlassContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.shape.CircleShape
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit
) {
    val refraction = dev.t1m3.qplayer.android.ui.LocalGlassRefraction.current
    // Share the navigation material, adapting ink and optics to the local backdrop.
    val dark = dev.t1m3.qplayer.android.ui.LocalGlassDark.current
    val adaptive = rememberIosAdaptiveGlass(backdrop)
    val glassHighlight = rememberIosControlGlassHighlight()
    val animationScope = rememberCoroutineScope()

    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope
        )
    }

    DisposableEffect(interactiveHighlight) { onDispose { interactiveHighlight.cancel() } }
    Row(
        modifier
            .then(adaptive.modifier)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    controlGlassEffects(
                        adaptive = adaptive,
                        refraction = refraction,
                    )
                },
                layerBlock = if (isInteractive) {
                    {
                        val width = size.width
                        val height = size.height

                        val progress = interactiveHighlight.pressProgress
                        val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)

                        val maxOffset = size.minDimension
                        val initialDerivative = 0.05f
                        val offset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                        val maxDragScale = 4f.dp.toPx() / size.height
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX =
                            scale +
                                    maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                                    (width / height).fastCoerceAtMost(1f)
                        scaleY =
                            scale +
                                    maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                                    (height / width).fastCoerceAtMost(1f)
                    }
                } else {
                    null
                },
                // Ⓜ The app's shared shadow, so this button reads as the same material
                // as the bar and the round buttons.
                onDrawBackdrop = { drawBackdrop ->
                    drawBackdrop()
                },
                shadow = { controlGlassShadow(dark, adaptive) },
                highlight = { glassHighlight.value },
                onDrawSurface = {
                    if (tint.isSpecified) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.75f))
                    } else if (surfaceColor.isSpecified) {
                        drawRect(surfaceColor)
                    } else {
                        drawControlGlassSurface(dark, adaptive)
                    }
                }
            )
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = null,
                indication = if (isInteractive) null else LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
            .then(
                if (isInteractive) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            )
            .height(48f.dp)
            .padding(horizontal = 16f.dp),
        horizontalArrangement = Arrangement.spacedBy(8f.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (contentColor.isSpecified) contentColor else adaptive.contentColor
        CompositionLocalProvider(LocalContentColor provides ink,
            LocalGlassContentColor provides ink) {
            androidx.compose.material3.ProvideTextStyle(
                androidx.compose.material3.LocalTextStyle.current.copy(color = ink)) { content() }
        }
    }
}
