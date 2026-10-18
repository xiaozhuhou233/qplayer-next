package dev.t1m3.qplayer.android.md3eui

internal fun mvRetryDelayMs(attempt: Int): Long = when (attempt) {
    0 -> 2_000L
    1 -> 5_000L
    2 -> 15_000L
    else -> 30_000L
}

internal data class MvFrameFill(val width: Float, val height: Float)

/** TextureView transform values for a centered, uniform crop of the useful frame.
 *
 * TextureView first stretches its buffer to the view bounds. These values undo that
 * stretch and then apply one uniform scale to the decoded frame. The translation
 * keeps the detected encoded bars outside the viewport while centering the useful
 * picture. A tiny overscan absorbs decoder rounding seams at every edge.
 */
internal data class MvFrameTransform(
    val scaleX: Float, val scaleY: Float, val translateX: Float, val translateY: Float,
)

internal const val MV_MAX_PAN_FRACTION = .18f

internal data class MvLetterbox(
    val top: Float = 0f, val bottom: Float = 0f,
    val left: Float = 0f, val right: Float = 0f,
)

/** Inspect the decoded buffer, before page cropping/shading. Dark MVs need only a small
 * patch of real picture detail, not a mostly bright central third, to identify bars. */
internal fun mvLetterbox(pixels: IntArray, width: Int, height: Int): MvLetterbox {
    if (width < 8 || height < 16 || pixels.size != width * height) return MvLetterbox()
    fun level(p: Int) = maxOf((p shr 16) and 255, (p shr 8) and 255, p and 255)
    fun blackRow(y: Int) = (0 until width).count { level(pixels[y * width + it]) <= 14 } >= width * .98f
    fun blackColumn(x: Int) = (0 until height).count { level(pixels[it * width + x]) <= 14 } >= height * .98f
    val yLimit = (height * .40f).toInt()
    val xLimit = (width * .40f).toInt()
    val top = (0 until yLimit).takeWhile(::blackRow).size
    val bottom = (0 until yLimit).takeWhile { blackRow(height - 1 - it) }.size
    val left = (0 until xLimit).takeWhile(::blackColumn).size
    val right = (0 until xLimit).takeWhile { blackColumn(width - 1 - it) }.size
    if (top >= yLimit || bottom >= yLimit || left >= xLimit || right >= xLimit) return MvLetterbox()
    if (top + bottom + left + right == 0) return MvLetterbox()
    var detail = 0
    for (y in top until height - bottom) for (x in left until width - right) {
        if (level(pixels[y * width + x]) > 40) detail++
    }
    // Refuse fades/solid black frames; retain dim scenes with a small visible subject.
    if (detail < (width - left - right) * (height - top - bottom) * .02f) return MvLetterbox()
    return MvLetterbox(top.toFloat() / height, bottom.toFloat() / height,
        left.toFloat() / width, right.toFloat() / width)
}

/** Fill the page without stretching; the View clips excess equally around the source center. */
internal fun mvFillFrame(videoWidth: Int, videoHeight: Int, viewWidth: Int, viewHeight: Int): MvFrameFill? {
    if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
    val scale = maxOf(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
    return MvFrameFill(videoWidth * scale, videoHeight * scale)
}

internal fun mvFrameTransform(
    videoWidth: Int, videoHeight: Int, viewWidth: Int, viewHeight: Int,
    letterbox: MvLetterbox = MvLetterbox(), overscan: Float = 1.01f, panFraction: Float = 0f,
): MvFrameTransform? {
    if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
    val top = letterbox.top.coerceIn(0f, .45f)
    val bottom = letterbox.bottom.coerceIn(0f, .45f)
    val left = letterbox.left.coerceIn(0f, .45f)
    val right = letterbox.right.coerceIn(0f, .45f)
    val usefulWidth = videoWidth * (1f - left - right).coerceAtLeast(.10f)
    val usefulFraction = (1f - top - bottom).coerceAtLeast(.10f)
    val usefulHeight = videoHeight * usefulFraction
    val scale = maxOf(viewWidth.toFloat() / usefulWidth, viewHeight.toFloat() / usefulHeight) * overscan.coerceAtLeast(1f)
    val scaledWidth = videoWidth * scale
    val scaledHeight = videoHeight * scale
    val usefulScaledHeight = usefulHeight * scale
    // Pan only within the decoded useful frame; never expose a blank edge.
    val panLimit = minOf(viewHeight * MV_MAX_PAN_FRACTION, ((usefulScaledHeight - viewHeight) / 2f).coerceAtLeast(0f))
    val pan = (panFraction.coerceIn(-MV_MAX_PAN_FRACTION, MV_MAX_PAN_FRACTION) * viewHeight).coerceIn(-panLimit, panLimit)
    return MvFrameTransform(
        scaleX = scaledWidth / viewWidth,
        scaleY = scaledHeight / viewHeight,
        translateX = (viewWidth - usefulWidth * scale) / 2f - left * scaledWidth,
        translateY = (viewHeight - usefulScaledHeight) / 2f - top * scaledHeight + pan,
    )
}

/** Long, eased fade over a full-page video; the controls never sit on a hard black edge. */
internal fun mvShadeAlpha(fraction: Float, titleFraction: Float, controlsFraction: Float): Float {
    val start = minOf(.38f, (titleFraction - .25f).coerceAtLeast(.20f))
    val end = maxOf(.92f, controlsFraction + .14f).coerceAtMost(1f)
    val t = ((fraction - start) / (end - start)).coerceIn(0f, 1f)
    val eased = t * t * (3f - 2f * t)
    return .99f * eased
}

internal data class MvClipWindow(val startMs: Int, val endMs: Int)

/** A bounded central excerpt avoids the opening/closing titles; short MVs remain valid. */
internal fun mvClipWindow(durationMs: Int): MvClipWindow {
    val duration = durationMs.coerceAtLeast(0)
    val length = minOf(24_000, duration)
    val start = (duration - length) / 2
    return MvClipWindow(start, start + length)
}

/** Seek completion must respect the LATEST music state, rather than auto-starting a loop. */
internal class MvClipPlayback {
    var requestedPlaying = false
    var prepared = false
        private set
    var seeking = false
        private set
    var window = MvClipWindow(0, 0)
        private set
    val shouldPlay: Boolean get() = requestedPlaying && prepared && !seeking

    fun prepare(durationMs: Int) {
        window = mvClipWindow(durationMs)
        prepared = true
        seeking = true
    }

    fun seekComplete() { seeking = false }

    fun beginLoop(positionMs: Int): Boolean {
        if (!shouldPlay || window.endMs <= window.startMs || positionMs < window.endMs) return false
        seeking = true
        return true
    }

    fun reset() { prepared = false; seeking = false }
}
