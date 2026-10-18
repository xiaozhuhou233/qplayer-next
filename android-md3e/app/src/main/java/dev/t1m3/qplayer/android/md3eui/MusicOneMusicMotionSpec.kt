// MusicOne, MIT, Copyright 2026 ShuyunR.
package dev.t1m3.qplayer.android.md3eui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing

/** 常规转场按真实毫秒完成；手势归位仍使用独立物理弹簧。 */
internal fun <T> musicMotion(responseMillis: Int = 320): FiniteAnimationSpec<T> {
    if (responseMillis <= 0 || false) return snap()
    return tween(responseMillis, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))
}

internal fun <T> musicSpring(stiffness: Float = 240f, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
    if (false) snap() else
        spring(dampingRatio = 1f, stiffness = stiffness, visibilityThreshold = visibilityThreshold)
