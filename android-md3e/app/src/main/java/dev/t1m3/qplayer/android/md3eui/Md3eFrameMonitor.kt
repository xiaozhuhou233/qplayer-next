package dev.t1m3.qplayer.android.md3eui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window

/** Foreground-only metrics; sampling never posts a task/state update per frame. */
internal class Md3eFrameMonitor(private val activity: Activity, private val runtime: Md3eRuntime) {
    private val main = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    @Suppress("DEPRECATION")
    fun start() {
        if (listener != null) return
        val display = activity.windowManager.defaultDisplay
        val current = display.mode
        val compatible = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val rate = Md3eFrameBudget.preferredRate(compatible.map { it.refreshRate }.toFloatArray())
        val mode = compatible.firstOrNull { kotlin.math.abs(it.refreshRate - rate) < .1f }
        if (mode != null) activity.window.attributes = activity.window.attributes.apply {
            preferredDisplayModeId = mode.modeId
            preferredRefreshRate = mode.refreshRate
        }
        val memory = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        runtime.updateRenderProfile(rate, memory.isLowRamDevice || memory.memoryClass <= 128)
        if (runtime.reducedRendering) return
        val budget = Md3eFrameBudget()
        val fallbackBudget = (1_000_000_000.0 / display.refreshRate.coerceAtLeast(30f)).toLong()
        val thread = HandlerThread("md3e-frame-metrics", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        worker = thread
        val callback = object : Window.OnFrameMetricsAvailableListener {
            override fun onFrameMetricsAvailable(window: Window, metrics: FrameMetrics, dropped: Int) {
                // On API 31+ the actual OS-selected frame deadline takes priority
                // over our requested mode (battery/thermal policy may cap it).
                val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L
                val period = if (deadline in 4_000_000..40_000_000) deadline else fallbackBudget
                if (budget.observe(metrics.getMetric(FrameMetrics.TOTAL_DURATION), period,
                        metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L)) {
                    main.post {
                        if (listener === this && runtime.uiVisible) {
                            runtime.updateRenderProfile(rate, true)
                            stop()
                        }
                    }
                }
            }
        }
        listener = callback
        activity.window.addOnFrameMetricsAvailableListener(callback, Handler(thread.looper))
    }

    fun stop() {
        listener?.let { activity.window.removeOnFrameMetricsAvailableListener(it) }
        listener = null
        worker?.quitSafely()
        worker = null
        // Each resumed window has independent samples; reduced quality stays
        // latched for this session rather than repeatedly changing during motion.
    }
}
