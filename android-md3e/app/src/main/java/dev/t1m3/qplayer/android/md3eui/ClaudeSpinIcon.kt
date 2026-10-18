package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A busy-state motor. No independent breathing clock; no frames when hidden/reduced. */
@Composable
internal fun SpinningDeformIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val rotation = rememberClaudeVinylRotation(LocalMd3eRenderingActive.current)
    Image(painter, contentDescription, modifier.size(size).graphicsLayer { rotationZ = rotation.value })
}
