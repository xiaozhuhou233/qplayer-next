// BiliPai FloatingDockChrome.kt + Miuix 5c91d5e5 BloomStroke.
// Keep the original light coordinates, intensities and 3-degree quantization.
package dev.t1m3.qplayer.android.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.highlight.BiliPaiBloomStroke
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.LightPosition
import com.kyant.backdrop.highlight.LightSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal val LocalBiliPaiDeviceTilt = staticCompositionLocalOf<State<DeviceTilt>?> { null }

@Composable
internal fun rememberBiliPaiGlassHighlight(
    indicator: Boolean = false,
    width: Dp = BiliPaiGlassParameters.highlightWidthDp.dp,
): State<Highlight> {
    // Share one sensor registration across the app's glass components/dialogs.
    val tilt = LocalBiliPaiDeviceTilt.current ?: rememberBiliPaiDeviceTilt()
    val quantizedDirection = remember(tilt) {
        derivedStateOf {
            BiliPaiGlassParameters.gravityDirection(tilt.value.gravityX, tilt.value.gravityY)
        }
    }
    val baseStyle = remember {
        BiliPaiBloomStroke(
            color = Color.White.copy(alpha = BiliPaiGlassParameters.highlightStrokeAlpha),
            innerBlurRadius = BiliPaiGlassParameters.highlightInnerBlurDp.dp,
            primaryLight = LightSource(LightPosition(0.5f, -0.3f, -0.05f),
                Color.White, BiliPaiGlassParameters.primaryLightIntensity),
            secondaryLight = LightSource(LightPosition(0.5f, 0.8f, -0.5f),
                Color.White, BiliPaiGlassParameters.secondaryLightIntensity),
            dualPeak = true,
        )
    }
    return remember(baseStyle, indicator, width, quantizedDirection) {
        derivedStateOf {
            val (lx0, ly0) = quantizedDirection.value
            val radians = (if (indicator) 90f else -45f) * PI / 180.0
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()
            val lx = c * lx0 - s * ly0
            val ly = s * lx0 + c * ly0
            Highlight(width = width, blurRadius = 0.dp,
                alpha = if (indicator) BiliPaiGlassParameters.indicatorHighlightAlpha
                    else BiliPaiGlassParameters.shellHighlightAlpha,
                style = baseStyle.copy(primaryLight = baseStyle.primaryLight.copy(
                    position = LightPosition(0.5f + lx, 0.7f + ly, -0.05f))))
        }
    }
}
