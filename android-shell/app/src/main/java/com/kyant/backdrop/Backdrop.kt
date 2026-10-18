// QPlayer Android compatibility adaptation, 2026-09-28. Original: Copyright 2025 Kyant, Apache-2.0.
package com.kyant.backdrop

import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density

interface Backdrop {

    val isCoordinatesDependent: Boolean

    /** Version used for adaptive colour sampling, independent of live pixel redraws. */
    val samplingRevision: Long? get() = null

    /** Subscribe to recorded pixels changing without a layout or composition change. */
    fun observeChanges(onChanged: () -> Unit): () -> Unit = {}

    fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)? = null
    )
}
