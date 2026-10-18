package dev.t1m3.qplayer.android.md3eui

import android.content.Context
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun PlayerTransportButtons(
    playing: Boolean, hasTrack: Boolean, privateFm: Boolean,
    onPrevious: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit,
    onMotionChanged: (Any, Boolean) -> Unit,
) {
    if (LocalPlayerMvBackdrop.current) {
        dev.t1m3.qplayer.android.ui.IosPlayerTransportButtons(
            playing = playing, previousEnabled = hasTrack && !privateFm,
            previous = onPrevious, toggle = onToggle, next = onNext,
            hasTrack = hasTrack, tint = Color.White)
        return
    }
    if (LocalClaudeDesign.current) {
        ClaudeTransportButtons(playing, hasTrack, privateFm, onPrevious, onToggle, onNext)
        return
    }
    val previous = rememberUpdatedState(onPrevious)
    val toggle = rememberUpdatedState(onToggle)
    val next = rememberUpdatedState(onNext)
    val motion = rememberUpdatedState(onMotionChanged)
    val colors = MaterialTheme.colorScheme
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(80.dp),
        factory = { context -> NativePlayerTransport(context).apply {
            onAction = { when (it) { -1 -> previous.value(); 2 -> toggle.value(); 1 -> next.value() } }
            onMotion = { owner, active -> motion.value(owner, active) }
        } },
        update = { view -> view.update(playing, hasTrack, privateFm,
            colors.primary.toArgb(), colors.secondaryContainer.toArgb(),
            colors.onPrimary.toArgb(), colors.onSecondaryContainer.toArgb(),
            colors.onSurface.copy(alpha = .12f).toArgb()) },
        onRelease = { it.release() },
    )
}

/**
 * Framework AnimatedVectorDrawable runs on RenderThread on API 25+ (minSdk 26).
 * Both halves of each spring are in its native AnimatorSet: the return does not
 * wait for a Handler, a Compose frame or a playback callback to start.
 */
private class NativePlayerTransport(context: Context) : ViewGroup(context) {
    var onAction: (Int) -> Unit = {}
    var onMotion: (Any, Boolean) -> Unit = { _, _ -> }
    private val owner = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val ids = intArrayOf(-1, 2, 1)
    private val fills = Array(3) { image() }
    private val icons = Array(3) { image() }
    private val pauseIcon = image()
    private val hits = Array(3) { TransportHit(context) }
    private val pulse = TransportPulse()
    private var touchButton = 0
    private var touchClick = false
    private var lastPlaying: Boolean? = null
    private var palette: List<Int> = emptyList()
    private var motionActive = false
    // Inflate once during composition, never parse/inflate vectors on a tap.
    private val vectors = TRANSPORT_VECTORS.map { row -> row.map {
        (context.getDrawable(it)!!.mutate() as AnimatedVectorDrawable)
    } }
    private val completion = object : Animatable2.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) {
            // This callback follows the actual native return, including when
            // the main thread was busy. Only then publish the latest song view.
            val pending = pulse.finish(SystemClock.uptimeMillis())
            if (pending != 0) startPulse(pending)
            else { layoutHits(0f); reportMotion(false) }
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        fills.forEach(::addView)
        icons.forEach(::addView)
        addView(pauseIcon)
        // reset() prepares/clones the framework AnimatorSet and its native
        // property holders. Pay this once on entry, never on the first DOWN.
        vectors.forEach { row -> row.forEach { it.reset() }; row[0].registerAnimationCallback(completion) }
        showVectors(1)
        hits.forEachIndexed { index, hit ->
            addView(hit)
            hit.contentDescription = arrayOf("上一首", "播放", "下一首")[index]
            hit.isClickable = true
            hit.isFocusable = true
            hit.setOnClickListener {
                if (!touchClick) requestPulse(ids[index])
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                val action = onAction
                val id = ids[index]
                // postOnAnimation runs before traversal. Posting from it lets
                // the first draw submit the native animation BEFORE main-thread
                // MediaPlayer setup starts, at most one display frame later.
                postOnAnimation { handler.post { action(id) } }
            }
        }
    }

    private fun image() = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun update(playing: Boolean, hasTrack: Boolean, privateFm: Boolean,
        primary: Int, secondary: Int, onPrimary: Int, onSecondary: Int, disabled: Int) {
        hits[0].isEnabled = hasTrack && !privateFm
        hits[1].isEnabled = hasTrack
        hits[2].isEnabled = hasTrack
        hits[1].contentDescription = if (playing) "暂停" else "播放"
        val nextPalette = listOf(primary, secondary, onPrimary, onSecondary, disabled,
            if (privateFm) 1 else 0)
        if (nextPalette != palette) {
            palette = nextPalette
            vectors.forEach { row ->
                row[0].setTint(if (privateFm) disabled else secondary)
                row[1].setTint(primary)
                row[2].setTint(secondary)
                row[3].setTint(onSecondary)
                row[4].setTint(onPrimary)
                row[5].setTint(onSecondary)
                row[6].setTint(onPrimary)
            }
        }
        if (lastPlaying != playing) {
            val snap = lastPlaying == null
            lastPlaying = playing
            hits[1].contentDescription = if (playing) "暂停" else "播放"
            // Icon visibility is independent of the native position springs;
            // updating it cannot replace/reset any animated vector.
            if (snap) {
                icons[1].alpha = if (playing) 0f else 1f
                pauseIcon.alpha = if (playing) 1f else 0f
            } else {
                icons[1].animate().alpha(if (playing) 0f else 1f).setDuration(180).start()
                pauseIcon.animate().alpha(if (playing) 1f else 0f).setDuration(180).start()
            }
        }
    }

    private fun reportMotion(active: Boolean) {
        if (motionActive == active) return
        motionActive = active
        onMotion(owner, active)
    }

    private fun requestPulse(id: Int) {
        if (pulse.request(id, SystemClock.uptimeMillis())) startPulse(id)
    }

    private fun showVectors(index: Int) {
        val row = vectors[index]
        fills.indices.forEach { fills[it].setImageDrawable(row[it]) }
        icons.indices.forEach { icons[it].setImageDrawable(row[it + 3]) }
        pauseIcon.setImageDrawable(row[6])
    }

    private fun startPulse(id: Int) {
        reportMotion(true)
        val index = ids.indexOf(id)
        showVectors(index)
        layoutHits(1f)
        vectors[index].forEach { it.start() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        (fills.toList() + icons.toList() + pauseIcon).forEach {
            it.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
        hits.forEach { it.measure(MeasureSpec.makeMeasureSpec(width / 3, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)) }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        (fills.toList() + icons.toList() + pauseIcon).forEach { it.layout(0, 0, width, height) }
        layoutHits(pulse.fraction(SystemClock.uptimeMillis()))
    }

    private fun layoutHits(fraction: Float) {
        val bounds = pulse.bounds(width.toFloat(), fraction)
        hits.forEachIndexed { index, view ->
            val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - bounds[index].second else bounds[index].first
            val right = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - bounds[index].first else bounds[index].second
            view.layout(left.toInt(), 0, right.toInt(), height)
        }
    }

    private fun buttonAt(x: Float): Int {
        val logical = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - x else x
        val bounds = pulse.bounds(width.toFloat(), pulse.fraction(SystemClock.uptimeMillis()))
        return ids.indices.firstOrNull { logical >= bounds[it].first && logical <= bounds[it].second && hits[it].isEnabled }
            ?.let { ids[it] } ?: 0
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        event.actionMasked == MotionEvent.ACTION_DOWN && buttonAt(event.x) != 0

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchButton = buttonAt(event.x)
                if (touchButton == 0) return false
                requestPulse(touchButton)
                hits[ids.indexOf(touchButton)].isPressed = true
            }
            MotionEvent.ACTION_UP -> {
                if (touchButton != 0) {
                    val hit = hits[ids.indexOf(touchButton)]
                    hit.isPressed = false
                    if (hit.isEnabled && buttonAt(event.x) == touchButton && event.y in 0f..height.toFloat()) {
                        touchClick = true
                        try { hit.performClick() } finally { touchClick = false }
                    }
                    touchButton = 0
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                hits.forEach { it.isPressed = false }
                touchButton = 0
            }
        }
        return true
    }

    fun release() {
        // Remove callbacks before stop(), which ends an AVD and could otherwise
        // dispatch completion for a player that has already left composition.
        vectors.forEach { row -> row[0].unregisterAnimationCallback(completion); row.forEach { it.stop() } }
        icons[1].animate().cancel()
        pauseIcon.animate().cancel()
        pulse.clear()
        touchButton = 0
        reportMotion(false)
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        vectors.forEach { it[0].registerAnimationCallback(completion) }
    }
}

private class TransportHit(context: Context) : View(context) {
    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
}

private val TRANSPORT_VECTORS = arrayOf(
    intArrayOf(R.drawable.md3e_transport_prev_prev_fill_pulse, R.drawable.md3e_transport_prev_play_fill_pulse, R.drawable.md3e_transport_prev_next_fill_pulse, R.drawable.md3e_transport_prev_prev_icon_pulse, R.drawable.md3e_transport_prev_play_icon_pulse, R.drawable.md3e_transport_prev_next_icon_pulse, R.drawable.md3e_transport_prev_pause_icon_pulse),
    intArrayOf(R.drawable.md3e_transport_play_prev_fill_pulse, R.drawable.md3e_transport_play_play_fill_pulse, R.drawable.md3e_transport_play_next_fill_pulse, R.drawable.md3e_transport_play_prev_icon_pulse, R.drawable.md3e_transport_play_play_icon_pulse, R.drawable.md3e_transport_play_next_icon_pulse, R.drawable.md3e_transport_play_pause_icon_pulse),
    intArrayOf(R.drawable.md3e_transport_next_prev_fill_pulse, R.drawable.md3e_transport_next_play_fill_pulse, R.drawable.md3e_transport_next_next_fill_pulse, R.drawable.md3e_transport_next_prev_icon_pulse, R.drawable.md3e_transport_next_play_icon_pulse, R.drawable.md3e_transport_next_next_icon_pulse, R.drawable.md3e_transport_next_pause_icon_pulse)
)
