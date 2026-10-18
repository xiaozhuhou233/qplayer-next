package dev.t1m3.qplayer.android.md3eui

import android.content.Context
import android.animation.ValueAnimator
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.isActive
import kotlin.math.*

private class AiFluidMotion {
    var targetX = 0f
    var targetY = 0f
    var offset by mutableStateOf(Offset.Zero)
    var phase by mutableFloatStateOf(0f)
}

/** Fused gyro orientation avoids raw-gyro drift. All animation reads happen in the draw phase. */
@Composable
internal fun Md3eAiFluidBackground(dark: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val motion = remember { AiFluidMotion() }
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val active = enabled && started && !Md3eLowSpecMode.current && !LocalMd3eReducedEffects.current && ValueAnimator.areAnimatorsEnabled()
    DisposableEffect(context, active) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var neutral: Offset? = null
        val matrix = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            @Suppress("DEPRECATION")
            override fun onSensorChanged(event: SensorEvent) {
                val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: 0
                val value = if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR || event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    val (axisX, axisY) = when (rotation) {
                        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                    }
                    SensorManager.remapCoordinateSystem(matrix, axisX, axisY, remapped)
                    SensorManager.getOrientation(remapped, orientation)
                    Offset(orientation[2], orientation[1])
                } else {
                    val x = event.values[0] / SensorManager.GRAVITY_EARTH
                    val y = event.values[1] / SensorManager.GRAVITY_EARTH
                    when (rotation) {
                        Surface.ROTATION_90 -> Offset(-y, x)
                        Surface.ROTATION_180 -> Offset(-x, -y)
                        Surface.ROTATION_270 -> Offset(y, -x)
                        else -> Offset(x, y)
                    }
                }
                if (!value.x.isFinite() || !value.y.isFinite()) return
                val origin = neutral ?: value.also { neutral = it }
                fun delta(a: Float, b: Float) = atan2(sin(a - b), cos(a - b)).coerceIn(-.65f, .65f) / .65f
                motion.targetX = delta(value.x, origin.x)
                motion.targetY = delta(value.y, origin.y)
            }
        }
        if (active && sensor != null) manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager?.unregisterListener(listener); motion.targetX = 0f; motion.targetY = 0f }
    }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var previous = 0L
        while (isActive) withFrameNanos { frame ->
            val dt = if (previous == 0L) 0f else ((frame - previous) / 1_000_000_000f).coerceAtMost(.05f)
            previous = frame
            val follow = 1f - exp(-dt * 3.4f)
            motion.offset += (Offset(motion.targetX, motion.targetY) - motion.offset) * follow
            motion.phase = (motion.phase + dt * .16f) % (2f * PI.toFloat())
        }
    }
    Canvas(modifier) {
        drawRect(if (dark) Color(0xFF141624) else Color(0xFFE9F1FE))
        val p = motion.phase
        val tilt = motion.offset
        fun cloud(color: Color, x: Float, y: Float, radius: Float) {
            val center = Offset(size.width * x, size.height * y)
            drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = color.alpha * .55f), Color.Transparent),
                center, size.maxDimension * radius), size.maxDimension * radius, center)
        }
        cloud(if (dark) Color(0xFF383057) else Color(0xFFDAC7FB),
            .7f + sin(p) * .17f + tilt.x * .22f, .33f + cos(p * 1.4f) * .12f + tilt.y * .18f, .78f)
        cloud(if (dark) Color(0xFF20454D) else Color(0xFFAEE8F5),
            .14f + cos(p * .8f) * .16f - tilt.x * .17f, .78f + sin(p) * .11f - tilt.y * .16f, .61f)
        cloud(if (dark) Color(0xFF4F334C) else Color(0xFFF8DBEB),
            .85f - sin(p * 1.2f) * .2f + tilt.x * .15f, .95f + cos(p) * .1f - tilt.y * .22f, .67f)
        cloud(if (dark) Color(0x553C4C78) else Color(0xAAEBEDFF),
            .2f - tilt.x * .13f, .1f + sin(p * .7f) * .1f + tilt.y * .15f, .65f)
    }
}
