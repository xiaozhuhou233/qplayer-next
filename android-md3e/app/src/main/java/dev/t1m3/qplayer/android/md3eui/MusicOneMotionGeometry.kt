// MusicOne, MIT, Copyright 2026 ShuyunR.
package dev.t1m3.qplayer.android.md3eui

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun motionDuration(from: Float, to: Float, fullDuration: Int): Int =
    (abs(to - from).coerceIn(0f, 1f) * fullDuration).roundToInt().coerceAtLeast(1)

internal fun motionLerp(start: Float, end: Float, progress: Float): Float =
    start + (end - start) * progress.coerceIn(0f, 1f)

internal fun motionRect(start: Rect, end: Rect, progress: Float): Rect = Rect(
    motionLerp(start.left, end.left, progress), motionLerp(start.top, end.top, progress),
    motionLerp(start.right, end.right, progress), motionLerp(start.bottom, end.bottom, progress),
)

internal fun anchorFits(anchor: Rect, host: Rect): Boolean =
    anchor.width > 1f && anchor.height > 1f &&
        anchor.left >= host.left - 1f && anchor.top >= host.top - 1f &&
        anchor.right <= host.right + 1f && anchor.bottom <= host.bottom + 1f


internal fun contentReveal(progress: Float): Float = ((progress - .22f) / .78f).coerceIn(0f, 1f)
