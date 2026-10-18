package dev.t1m3.qplayer.android.md3eui

import androidx.compose.runtime.compositionLocalOf

// Read only at visual consumers. Changing visibility must not invalidate a page
// that has no running visual work, or affect the process-owned audio clock.
internal val LocalMd3eRenderingActive = compositionLocalOf { true }
internal val LocalMd3eMotionActive = compositionLocalOf { false }
// Automatic frame-budget feedback only pauses decorative effects. It never
// changes navigation presets, shared elements, player reveals or lyric physics.
internal val LocalMd3eReducedEffects = compositionLocalOf { false }

/** Keeps the earliest overlapping lyric active, including duet/background rows. */
internal class Md3eLyricIndex(starts: LongArray, ends: LongArray) {
    private val starts = starts.copyOf()
    private val prefixEnds = LongArray(ends.size)

    init {
        require(starts.size == ends.size)
        var end = Long.MIN_VALUE
        ends.forEachIndexed { index, value ->
            end = maxOf(end, value)
            prefixEnds[index] = end
        }
    }

    fun at(position: Long): Int {
        var low = 0
        var high = starts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= position) low = mid + 1 else high = mid
        }
        val last = low - 1
        if (last < 0) return -1
        low = 0
        high = last + 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (prefixEnds[mid] <= position) low = mid + 1 else high = mid
        }
        return if (low <= last) low else last
    }
}
