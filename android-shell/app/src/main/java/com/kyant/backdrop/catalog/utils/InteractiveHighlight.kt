// QPlayer Android compatibility adaptation, 2026-09-28. Original: Copyright 2025 Kyant, Apache-2.0.
package com.kyant.backdrop.catalog.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {

    private val pressProgressAnimationSpec =
        spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec =
        spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    private var pointerPosition by mutableStateOf(Offset.Zero)
    private var pressed by mutableStateOf(false)
    private var attached by mutableStateOf(false)
    private var returnPending by mutableStateOf(false)
    private var pressJob: Job? = null
    private var returnJob: Job? = null
    private val visiblePosition: Offset
        get() = if (pressed || returnPending) pointerPosition else positionAnimation.value
    val pressProgress: Float get() = if (attached) pressProgressAnimation.value else 0f
    val offset: Offset get() = if (attached) visiblePosition - startPosition else Offset.Zero

    private val shader =
        if (isRuntimeShaderSupported()) {
            RuntimeShader(
                """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""
            )
        } else {
            null
        }
    // Reuse the shader brush. Pointer moves invalidate drawing, not composition,
    // and no longer allocate an animation coroutine for each touch sample.
    private val shaderBrush = shader?.let { ShaderBrush(it.asComposeShader()) }

    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgress.coerceIn(0f, 1f)
            if (progress > 0f) {
                if (shader != null) {
                    drawRect(
                        Color.White.copy(0.08f * progress),
                        blendMode = BlendMode.Plus
                    )
                    shader.apply {
                        val position = position(size, visiblePosition)
                        setFloatUniform("size", size.width, size.height)
                        setColorUniform("color", Color.White.copy(0.15f * progress))
                        setFloatUniform("radius", size.minDimension * 1.5f)
                        setFloatUniform(
                            "position",
                            position.x.fastCoerceIn(0f, size.width),
                            position.y.fastCoerceIn(0f, size.height)
                        )
                    }
                    drawRect(
                        shaderBrush!!,
                        blendMode = BlendMode.Plus
                    )
                } else {
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.White.copy(0.22f * progress), Color.Transparent),
                            center = position(size, visiblePosition),
                            radius = (size.minDimension * 1.5f).coerceAtLeast(1f)
                        ),
                        blendMode = BlendMode.Plus
                    )
                }
            }

            drawContent()
        }

    val gestureModifier: Modifier =
        Modifier.pointerInput(this) {
            pressProgressAnimation.snapTo(0f)
            attached = true
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(false, PointerEventPass.Initial)
                    pressJob?.cancel()
                    returnJob?.cancel()
                    startPosition = down.position
                    pointerPosition = down.position
                    returnPending = false
                    pressed = true
                    pressJob = animationScope.launch {
                        pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec)
                    }
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            pointerPosition = pointer.position
                        } while (pointer.pressed)
                    } finally {
                        release()
                    }
                }
            } finally {
                cancel()
            }
        }

    /** Never launch a return animation from disposal; release owned work immediately. */
    fun cancel() {
        attached = false
        pressed = false
        returnPending = false
        pressJob?.cancel(); returnJob?.cancel()
        pressJob = null; returnJob = null
    }

    private fun release() {
        if (!pressed) return
        val releasePosition = pointerPosition
        pressed = false
        returnPending = true
        pressJob?.cancel()
        returnJob?.cancel()
        pressJob = animationScope.launch {
            pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec)
        }
        returnJob = animationScope.launch {
            positionAnimation.snapTo(releasePosition)
            returnPending = false
            positionAnimation.animateTo(startPosition, positionAnimationSpec)
        }
    }
}
