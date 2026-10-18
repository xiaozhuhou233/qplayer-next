package dev.t1m3.qplayer.android.md3eui

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Latest visual request only; playback commands are never queued by this model. */
internal class TransportPulse {
    companion object {
        const val PHASE_DURATION_MS = 260L
        const val TOTAL_DURATION_MS = 2 * PHASE_DURATION_MS
    }
    var button = 0
        private set
    private var pending = 0
    private var startedAt = 0L

    fun request(id: Int, now: Long): Boolean {
        require(id == -1 || id == 2 || id == 1)
        if (button != 0) { pending = id; return false }
        button = id
        startedAt = now
        return true
    }

    fun finish(now: Long): Int {
        button = pending
        pending = 0
        startedAt = now
        return button
    }

    fun clear() { button = 0; pending = 0 }

    fun fraction(now: Long): Float {
        if (button == 0) return 0f
        val elapsed = (now - startedAt).coerceAtLeast(0)
        if (elapsed >= TOTAL_DURATION_MS) return 0f
        val returning = elapsed >= PHASE_DURATION_MS
        val phase = if (returning) elapsed - PHASE_DURATION_MS else elapsed
        // Match the same normalized XML spring at the faster phase duration.
        val t = phase * (0.720 / PHASE_DURATION_MS)
        val frequency = sqrt(200.0)
        val damped = frequency * sqrt(.75)
        val spring = 1 - exp(-.5 * frequency * t) *
            (cos(damped * t) + .5 * frequency / damped * sin(damped * t))
        return (if (returning) 1 - spring else spring).toFloat()
    }

    fun bounds(width: Float, fraction: Float): List<Pair<Float, Float>> {
        val gap = width * (6f / 300f)
        val base = (width - 2 * gap) / 3
        val weights = intArrayOf(-1, 2, 1).map { if (it == button) 1.1f else .65f }
        var left = 0f
        return weights.map { weight ->
            val target = if (button == 0) base else (width - 2 * gap) * weight / 2.4f
            val size = base + (target - base) * fraction
            (left to left + size).also { left += size + gap }
        }
    }
}
