package dev.t1m3.qplayer.android.md3eui

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot

internal class LyricRowMotion(active: Boolean) {
    val offset = Animatable(0f)
    var scrollShift by mutableFloatStateOf(0f)
    val visualOffset: Float get() = scrollShift + offset.value
    val scale = Animatable(if (active) 1f else .98f)
    val alpha = Animatable(if (active) .85f else .175f)
}


/** No observer can see compensation without its corresponding list movement. */
internal fun compensateLyricScroll(motions: List<LyricRowMotion>, scroll: () -> Float): Float =
    Snapshot.withMutableSnapshot {
        val consumed = scroll()
        motions.forEach { it.scrollShift += consumed }
        consumed
    }
