package dev.t1m3.qplayer.android.md3eui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

internal val LocalPlayerMvBackdrop = staticCompositionLocalOf { false }
internal class MvBackdropLayout {
    var rootTop by mutableFloatStateOf(0f)
    var sceneBottom by mutableFloatStateOf(0f)
    var controlsTop by mutableFloatStateOf(0f)
}
internal val LocalPlayerMvLayout = staticCompositionLocalOf<MvBackdropLayout?> { null }

@Composable
internal fun Modifier.playerMvAnchor(scene: Boolean = false): Modifier {
    val layout = LocalPlayerMvLayout.current ?: return this
    return onGloballyPositioned {
        val top = it.boundsInRoot().top - layout.rootTop
        if (scene) layout.sceneBottom = top else layout.controlsTop = top
    }
}

/** Soft light falloff underneath controls, without introducing another visible panel. */
@Composable
internal fun Modifier.playerControlShadow(): Modifier {
    if (Md3eLowSpecMode.current || LocalPlayerMvBackdrop.current) return this
    val opacity = .14f
    return drawWithCache {
        val center = Offset(size.width / 2f, size.height * .60f + 6.dp.toPx())
        val radius = (size.width * .48f).coerceAtLeast(1f)
        val shade = Brush.radialGradient(listOf(Color.Black.copy(alpha = opacity),
            Color.Black.copy(alpha = opacity * .35f), Color.Transparent), center, radius)
        onDrawBehind {
            withTransform({ scale(1f, .22f, center) }) { drawCircle(shade, radius, center) }
        }
    }
}
