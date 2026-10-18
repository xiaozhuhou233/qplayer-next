package dev.t1m3.qplayer.android.md3eui

/** Detect sustained overload, not a single network/decoder or first-draw stall. */
internal class Md3eFrameBudget {
    private var count = 0
    private var slow = 0
    private var totalRatio = 0.0
    private var overloadedWindows = 0
    var reduced = false
        private set

    fun observe(durationNanos: Long, budgetNanos: Long, firstDraw: Boolean = false): Boolean {
        if (reduced || firstDraw || durationNanos <= 0 || durationNanos > 250_000_000 ||
            budgetNanos !in 4_000_000..40_000_000) return false
        val ratio = durationNanos.toDouble() / budgetNanos
        count++
        totalRatio += ratio.coerceAtMost(4.0)
        if (ratio > 1.5) slow++
        if (count < 60) return false
        val overloaded = slow >= 21 && totalRatio / count > 1.25
        overloadedWindows = if (overloaded) overloadedWindows + 1 else 0
        count = 0
        slow = 0
        totalRatio = 0.0
        if (overloadedWindows < 2) return false
        reduced = true
        return true // Publish one quality change, never per-frame Compose state.
    }

    fun resetWindow() { count = 0; slow = 0; totalRatio = 0.0; overloadedWindows = 0 }

    companion object {
        fun preferredRate(rates: FloatArray): Float {
            val valid = rates.filter { it.isFinite() && it > 0f }
            return valid.filter { it <= 120.1f }.maxOrNull() ?: valid.minOrNull() ?: 60f
        }
    }
}
