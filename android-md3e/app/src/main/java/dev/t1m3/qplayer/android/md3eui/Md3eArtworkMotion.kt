package dev.t1m3.qplayer.android.md3eui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.util.lerp

// One geometric clock: ease into the motion, accelerate in the middle, then settle.
// Shared by the dock, expansion surface and cover; never ease the cover a second time.
internal val playerArtworkEasing = CubicBezierEasing(.65f, 0f, .35f, 1f)

internal fun playerArtworkFlight(mini: Rect, detail: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(lerp(mini.left, detail.left, p), lerp(mini.top, detail.top, p),
        lerp(mini.right, detail.right, p), lerp(mini.bottom, detail.bottom, p))
}
