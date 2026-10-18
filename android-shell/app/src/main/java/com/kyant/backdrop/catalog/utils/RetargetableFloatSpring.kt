// QPlayer: allocation-free retargeting of the original Compose velocity spring.
package com.kyant.backdrop.catalog.utils

import androidx.compose.animation.core.FloatSpringSpec

/** One state, not one coroutine per frame; keeps velocity continuous on retarget. */
internal class RetargetableFloatSpring(
    dampingRatio: Float,
    stiffness: Float,
    visibilityThreshold: Float,
) {
    private val spec = FloatSpringSpec(dampingRatio, stiffness, visibilityThreshold)
    private var initial = 0f
    private var initialVelocity = 0f
    private var target = 0f
    private var startedAt = 0L
    private var duration = 0L
    var isFinished: Boolean = true
        private set

    fun sample(newTarget: Float, nowNanos: Long): Float {
        require(newTarget.isFinite())
        val elapsed = (nowNanos - startedAt).coerceAtLeast(0L)
        val finished = elapsed >= duration
        val value = if (finished) target else spec.getValueFromNanos(elapsed, initial, target, initialVelocity)
        val velocity = if (finished) 0f else spec.getVelocityFromNanos(elapsed, initial, target, initialVelocity)
        if (newTarget != target) {
            initial = value
            initialVelocity = velocity
            target = newTarget
            startedAt = nowNanos
            duration = spec.getDurationNanos(initial, target, initialVelocity)
            isFinished = duration == 0L
        } else isFinished = finished
        return value
    }
}
