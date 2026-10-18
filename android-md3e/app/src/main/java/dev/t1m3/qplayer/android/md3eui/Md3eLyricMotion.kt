package dev.t1m3.qplayer.android.md3eui

import kotlin.math.PI
import kotlin.math.exp

/** The row keeps its raised baseline after the active line advances. */
internal fun md3eLyricRowLift(position: Long, start: Long): Float {
    val p = ((position - start + 450L) / 600f).coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

internal fun md3eGlyphLift(position: Long, start: Long, end: Long): Float {
    if (position <= start) return 0f
    val response = ((end - start) / 1000.0).coerceIn(.45, 3.0) * 1.25
    val elapsedMs = position - start
    // Once the response has settled, avoid exp() for every glyph on every
    // frame. Long lyric lines otherwise spend a surprising amount of CPU in
    // this curve even though their lift is already at its final value.
    if (elapsedMs.toDouble() >= response * 3000.0) return 2f
    val elapsed = (elapsedMs / 1000.0).coerceAtMost(response * 3.0)
    val phase = 2.0 * PI * elapsed / response
    return (2.0 * (1.0 - (1.0 + phase) * exp(-phase))).toFloat()
}

