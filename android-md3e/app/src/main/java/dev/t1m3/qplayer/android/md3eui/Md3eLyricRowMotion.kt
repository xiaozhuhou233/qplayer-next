package dev.t1m3.qplayer.android.md3eui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.t1m3.qplayer.lyric.ReferenceLyricMotion
import dev.t1m3.qplayer.lyric.QPlayerLyricScrollMotion

internal class LyricRowMotion(active: Boolean) {
    val offset = mutableFloatStateOf(0f)
    var scrollShift by mutableFloatStateOf(0f)
    val visualOffset: Float get() = scrollShift + offset.value
    val positionSettled: Boolean get() = positionSpring.arrived()
    val scale = mutableFloatStateOf(if (active) 1f else .98f)
    val alpha = mutableFloatStateOf(if (active) .85f else .175f)
    val blur = mutableFloatStateOf(0f)
    private val positionSpring = QPlayerLyricScrollMotion.Spring(0f)
    private val scaleSpring = ReferenceLyricMotion.Spring(scale.floatValue.toDouble())
    private var positionGoal = 0.0
    private var scaleGoal = scale.floatValue.toDouble()
    private var positionSpringEnabled = true
    private var alphaStart = alpha.floatValue
    private var alphaTarget = alphaStart
    private var blurStart = 0f
    private var blurTarget = 0f
    private var fadeElapsed = .12

    fun snap(active: Boolean, blurRadius: Float = 0f) {
        positionSpring.reset(-scrollShift)
        scaleSpring.reset(if (active) 1.0 else .98)
        positionGoal = positionSpring.value().toDouble()
        scaleGoal = scaleSpring.value
        offset.floatValue = -scrollShift
        scale.floatValue = scaleSpring.value.toFloat()
        alphaStart = if (active) .85f else .175f
        alphaTarget = alphaStart; alpha.floatValue = alphaStart
        blurStart = blurRadius; blurTarget = blurRadius; blur.floatValue = blurRadius
        fadeElapsed = .12
    }

    fun retarget(active: Boolean, physics: ReferenceLyricMotion.Physics,
        positionDelay: Double, scaleDelay: Double, blurRadius: Float, viewportDelta: Float = 0f,
        interlude: Boolean = false, seeking: Boolean = false, springEnabled: Boolean = true) {
        val nextPosition = -scrollShift.toDouble() - viewportDelta
        // Overlapping voices update selection independently of the scroll
        // anchor. An unchanged target must not restart its cascade or spring.
        if (kotlin.math.abs(nextPosition - positionGoal) > .001 || positionSpringEnabled != springEnabled) {
            positionGoal = nextPosition
            positionSpringEnabled = springEnabled
            // The position solver advances at .85x. Convert the shared wall-time
            // delay so position and scale start the same cascade beat.
            positionSpring.retarget(nextPosition.toFloat(), positionDelay * .85, interlude, seeking, springEnabled)
        }
        val nextScale = if (active) 1.0 else .98
        if (nextScale != scaleGoal) {
            scaleGoal = nextScale
            scaleSpring.retarget(nextScale, physics, scaleDelay)
        }
        val nextAlpha = if (active) .85f else .175f
        if (nextAlpha != alphaTarget || blurRadius != blurTarget) {
            alphaStart = alpha.floatValue; alphaTarget = nextAlpha
            blurStart = blur.floatValue; blurTarget = blurRadius; fadeElapsed = 0.0
        }
    }

    fun tick(dt: Double) {
        // Keep the imported solver/cascade; give row travel ~18% more time,
        // including seeks, instead of compressing short phrases into a snap.
        offset.floatValue = positionSpring.update(dt * .85).toFloat()
        scale.floatValue = scaleSpring.update(dt).toFloat()
        fadeElapsed += dt
        val eased = ReferenceLyricMotion.bezier(fadeElapsed / .12, .33, 0.0, .20, .10).toFloat()
        alpha.floatValue = alphaStart + (alphaTarget - alphaStart) * eased
        blur.floatValue = blurStart + (blurTarget - blurStart) * eased
    }

    fun settle() {
        positionGoal = -scrollShift.toDouble()
        positionSpring.reset(positionGoal.toFloat()); offset.floatValue = -scrollShift
    }

    fun freeze() {
        positionSpring.reset(offset.floatValue)
        scaleSpring.reset(scale.floatValue.toDouble())
        positionGoal = positionSpring.value().toDouble()
        scaleGoal = scaleSpring.value
    }
}


/** No observer can see compensation without its corresponding list movement. */
internal fun compensateLyricScroll(motions: List<LyricRowMotion>, scroll: () -> Float): Float =
    Snapshot.withMutableSnapshot {
        val consumed = scroll()
        motions.forEach { it.scrollShift += consumed }
        consumed
    }
