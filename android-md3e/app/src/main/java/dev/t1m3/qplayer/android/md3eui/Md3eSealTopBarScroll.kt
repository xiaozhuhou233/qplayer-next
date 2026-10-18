package dev.t1m3.qplayer.android.md3eui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

// Adapted from Seal DownloadPageV2/TopBarNestedScrollConnection.kt.
internal class Md3eSealTopBarScroll(
    private val maxOffset: Float,
    private val flingAnimationSpec: DecayAnimationSpec<Float>,
    private val offset: () -> Float,
    private val onOffsetUpdate: (Float) -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        if (delta < 0f) {
            val previousOffset = offset()
            if (previousOffset >= 0f) {
                val newOffset = (previousOffset + delta).coerceIn(0f, maxOffset)
                onOffsetUpdate(newOffset)
                return Offset(0f, newOffset - previousOffset)
            }
        }
        return super.onPreScroll(available, source)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        val previousOffset = offset()
        if (delta < 0f || consumed.y < 0f) {
            if (previousOffset >= 0f) {
                val newOffset = (previousOffset + consumed.y).coerceIn(0f, maxOffset)
                onOffsetUpdate(newOffset)
                return Offset(0f, newOffset - previousOffset)
            }
        }
        if (delta > 0f) {
            val newOffset = (previousOffset + delta).coerceIn(0f, maxOffset)
            onOffsetUpdate(newOffset)
            return Offset(0f, newOffset - previousOffset)
        }
        return super.onPostScroll(consumed, available, source)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        super.onPostFling(consumed, available) + settleAppBar(available.y)

    private suspend fun settleAppBar(velocity: Float): Velocity {
        if (offset() < 0.01f) return Velocity.Zero
        var remainingVelocity = velocity
        if (abs(velocity) > 1f) {
            var lastValue = 0f
            AnimationState(initialValue = 0f, initialVelocity = velocity).animateDecay(flingAnimationSpec) {
                val delta = value - lastValue
                val previousOffset = offset()
                val newOffset = (previousOffset + delta).coerceIn(0f, maxOffset)
                onOffsetUpdate(newOffset)
                val consumedDelta = abs(newOffset - previousOffset)
                lastValue = value
                remainingVelocity = this.velocity
                if (abs(delta - consumedDelta) > 0.5f) cancelAnimation()
            }
        }
        return Velocity(0f, remainingVelocity)
    }
}
