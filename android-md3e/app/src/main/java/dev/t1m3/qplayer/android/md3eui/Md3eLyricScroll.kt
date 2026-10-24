package dev.t1m3.qplayer.android.md3eui

import kotlin.math.abs

/** Row transforms move first. Recycle the lazy viewport only after the cascade
 * settles, or before continuous short phrases exhaust the overscan buffer. */
internal fun rebaseLyricViewport(pending: Float, viewportHeight: Float,
    motions: Collection<LyricRowMotion>, scroll: () -> Float): Float {
    // Lazy layout places at integer pixels. Keep a subpixel remainder in the
    // layer instead of repeatedly committing an unplaceable half pixel.
    if (abs(pending) <= .51f || viewportHeight <= 0f) return pending
    if (abs(pending) < viewportHeight * .75f && motions.any { !it.positionSettled }) return pending
    return pending - compensateLyricScroll(motions.toList(), scroll)
}
