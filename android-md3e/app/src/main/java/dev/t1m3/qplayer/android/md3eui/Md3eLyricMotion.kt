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

/** ui.zip's lift/start/tail relationship, sampled through the existing smooth
 * audio-clock response. One record per timed word keeps its graphemes together;
 * emphasis cannot start a second, independently delayed ascent. */
internal class Md3eLyricWordMotion(private val start: Long, private val end: Long,
    private val glowOnly: Boolean) {
    private val longness = ((end - start) / 1000f).coerceIn(1f, 2f) - 1f
    var raised = 0f
        private set
    var pulse = 0f
        private set
    var scale = 1f
        private set
    var spread = 0f
        private set

    fun sample(position: Long) {
        val rise = md3eGlyphLift(position, start, end)
        val phase = rise * .5f
        // ui.zip combines start * (1 - tail). Bind both to the same smooth
        // phase here: a separate tail timer can finish before a long lift,
        // leaving a second small ascent after the large emphasis has fallen.
        pulse = if (glowOnly) 0f else 4f * phase * (1f - phase)
        raised = rise + 1.5f * pulse * longness
        scale = 1f + .14f * longness * pulse
        spread = .5f * pulse * .14f * longness
    }
}

/** ui.zip starts the wave at the first actually visible line, then propagates
 * downward. Overscan rows above it must not get a mirrored delayed wave. */
internal fun md3eLyricCascadeDelay(firstVisible: Int, row: Int, validDistance: Int, disabled: Boolean): Double =
    if (disabled || row <= firstVisible) 0.0 else
        dev.t1m3.qplayer.lyric.ReferenceLyricMotion.cascadeDelay(validDistance, false)

