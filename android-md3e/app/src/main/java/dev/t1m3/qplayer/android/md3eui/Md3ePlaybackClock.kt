package dev.t1m3.qplayer.android.md3eui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

@Stable
internal class PlaybackClock {
    var position by mutableLongStateOf(0L)
    var sampledAtNanos by mutableLongStateOf(0L)
    private var wasPlaying = false

    fun publish(positionMs: Long, sampledAt: Long, playing: Boolean) {
        // A stationary paused clock must not invalidate its consumers every tick.
        if (playing || wasPlaying != playing || position != positionMs || sampledAtNanos == 0L) {
            position = positionMs
            sampledAtNanos = sampledAt
        }
        wasPlaying = playing
    }
}
