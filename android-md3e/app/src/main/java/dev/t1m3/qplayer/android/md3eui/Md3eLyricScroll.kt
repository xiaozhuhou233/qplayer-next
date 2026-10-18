package dev.t1m3.qplayer.android.md3eui

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun runLyricScroll(motions: Map<Int, LyricRowMotion>, scroll: suspend () -> Unit) {
    try {
        scroll()
    } finally {
        // A gesture can cancel scrollBy after compensation has moved the rows.
        // Animatable.snapTo is cancellable too, so cleanup must outlive that job.
        withContext(NonCancellable) {
            motions.values.toList().forEach { it.offset.snapTo(-it.scrollShift) }
        }
    }
}
