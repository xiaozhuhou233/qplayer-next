package dev.t1m3.qplayer.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The app's single Backdrop glass surface.
 *
 * Shared BiliPai BALANCED material with its default STABLE readability mode.
 * The backdrop remains live; foreground contrast uses the theme, without readback.
 * The interaction transform, edge highlight, shadow and content stay in one
 * drawBackdrop node. Splitting these into sibling Boxes changes the coordinate
 * space and breaks the effect.
 */
@Composable
internal fun IosLiquidGlass(
    backdrop: Backdrop,
    dark: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    interaction: InteractiveHighlight? = null,
    lite: Boolean = false,
    surfaceTint: Color = Color.Unspecified,
    strongRefraction: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val adaptive = rememberRegionAdaptiveGlass(backdrop)
    Box(
        modifier.then(
            iosLiquidGlassModifier(
                backdrop = backdrop,
                dark = dark,
                shape = shape,
                interaction = interaction,
                adaptive = adaptive,
                lite = lite,
                surfaceTint = surfaceTint,
                strongRefraction = strongRefraction,
            )
        ),
        // Icons share the glass's measured center at every size, including
        // compact dock controls. Box's default TopStart displaces them.
        contentAlignment = Alignment.Center,
    ) {
        val ink = adaptive.contentColor
        androidx.compose.runtime.CompositionLocalProvider(
            LocalGlassContentColor provides ink,
            androidx.compose.material3.LocalContentColor provides ink,
        ) {
            androidx.compose.material3.ProvideTextStyle(
                androidx.compose.material3.LocalTextStyle.current.copy(color = ink)) { content() }
        }
    }
}

/** Apply the complete Backdrop material to a node that also owns its content. */
@Composable
internal fun iosLiquidGlassModifier(
    backdrop: Backdrop,
    dark: Boolean,
    shape: Shape = CircleShape,
    interaction: InteractiveHighlight? = null,
    adaptive: IosAdaptiveGlassState = rememberRegionAdaptiveGlass(backdrop),
    lite: Boolean = false,
    surfaceTint: Color = Color.Unspecified,
    strongRefraction: Boolean = false,
): Modifier {
    val refraction = LocalGlassRefraction.current
    val glassHighlight = if (lite) null else rememberIosControlGlassHighlight()
    return Modifier
        .then(adaptive.modifier)
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                apiGlassEffects(adaptive.luminance, refraction && !lite,
                    strongRefraction = strongRefraction)
            },
            layerBlock = interaction?.let { highlight ->
                {
                    if (size.minDimension > 0f) {
                        val offset = highlight.offset
                        val dragScale = 4.dp.toPx() / size.height.coerceAtLeast(1f)
                        val base = 1f + dragScale * highlight.pressProgress
                        val maxOffset = size.minDimension
                        translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
                        translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)
                        val angle = atan2(offset.y, offset.x)
                        scaleX = base + dragScale *
                            abs(cos(angle) * offset.x / size.maxDimension) *
                            (size.width / size.height).coerceAtMost(1f)
                        scaleY = base + dragScale *
                            abs(sin(angle) * offset.y / size.maxDimension) *
                            (size.height / size.width).coerceAtMost(1f)
                    }
                }
            },
            onDrawBackdrop = { drawBackdrop ->
                drawBackdrop()
            },
            shadow = if (lite) null else ({ controlGlassShadow(dark, adaptive) }),
            innerShadow = null,
            highlight = if (lite) null else ({ glassHighlight?.value }),
            onDrawSurface = {
                if (surfaceTint.isSpecified) {
                    drawRect(surfaceTint, blendMode = BlendMode.Hue)
                    drawRect(surfaceTint.copy(alpha = .75f))
                } else drawControlGlassSurface(dark, adaptive)
            },
        )
        // The glass shadow is outside; pointer highlights must stay inside its contour.
        .clip(shape)
        .then(interaction?.modifier ?: Modifier)
        .then(interaction?.gestureModifier ?: Modifier)
}
