// Adapted for QPlayer Android, Copyright 2025 Kyant, Apache-2.0.
package com.kyant.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop

@Composable
fun rememberCanvasBackdrop(
    onDraw: DrawScope.() -> Unit
): Backdrop {
    val currentDraw = rememberUpdatedState(onDraw)
    return remember {
        // Keep consumers/sampling alive when the page colour changes. Read the
        // latest draw closure in the draw phase instead of replacing the source.
        CanvasBackdrop { currentDraw.value.invoke(this) }
    }
}

@Immutable
private class CanvasBackdrop(
    val onDraw: DrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        onDraw()
    }
}
