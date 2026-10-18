package dev.t1m3.qplayer.android.ui

import kotlin.math.roundToLong

// Ⓜ 2026-10-01, the listener: 「加快玻璃根据背景自适应颜色的速度与采样检测的速度」.
// A changed backdrop is picked up in ~60ms instead of 120ms, the heartbeat that
// catches content redrawing without a stamp change halves, and the poll simply
// bounds how late either of those can land.
internal const val GLASS_SAMPLE_POLL_MS = 24L
internal const val GLASS_SAMPLE_ACTIVE_MS = 60L
internal const val GLASS_SAMPLE_IDLE_MS = 240L

internal data class GlassSampleStamp(
    val revision: Long?,
    val x: Int, val y: Int, val width: Int, val height: Int,
)

/** A cheap revision check is not a GPU readback. Static surfaces retain their
 * sample; the heartbeat also catches child RenderNodes moving without a parent draw. */
internal class GlassSamplingPolicy(private val perPage: Boolean = false) {
    private var lastSucceeded = false
    private var lastStamp: GlassSampleStamp? = null
    private var lastSampleMs = 0L
    private var retryAfterMs = 0L

    fun shouldSample(nowMs: Long, stamp: GlassSampleStamp): Boolean {
        if (nowMs < retryAfterMs) return false
        if (lastStamp == null) return true
        // The material version only advances when entering another page. Geometry,
        // scroll and the idle heartbeat must not trigger another readback.
        if (perPage && lastSucceeded && stamp.revision != null && stamp.revision == lastStamp?.revision) return false
        val interval = if (stamp != lastStamp) GLASS_SAMPLE_ACTIVE_MS else GLASS_SAMPLE_IDLE_MS
        return nowMs - lastSampleMs >= interval
    }

    fun completed(nowMs: Long, stamp: GlassSampleStamp, success: Boolean) {
        lastSucceeded = success
        lastSampleMs = nowMs
        lastStamp = stamp
        retryAfterMs = if (success) 0L else nowMs + 1000L
    }
}

// Shared across all plates, not a separate high-frequency budget per button.
// Slow readback devices automatically get more breathing room.
internal fun glassReadbackGapMs(smoothedCostMs: Float): Long =
    if (smoothedCostMs.isFinite()) (smoothedCostMs * 2.5f).coerceIn(16f, 64f).roundToLong() else 64L
