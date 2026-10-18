package dev.t1m3.qplayer.android.md3eui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owned by one list section; motion never leaks between pages or groups. */
@Stable
internal class SongSwipeMotion {
    var row by mutableIntStateOf(-1)
    var displacement by mutableFloatStateOf(0f)
}
internal val LocalSongSwipeMotion = staticCompositionLocalOf<SongSwipeMotion?> { null }
internal val LocalSongSwipeIndex = staticCompositionLocalOf { -1 }

/** Tap plays; a left swipe adds once, then springs back without removing the row. */
@Composable
internal fun Modifier.songQueueMenu(title: String, onPlay: () -> Unit, onEnqueue: () -> Unit): Modifier {
    val enqueue by rememberUpdatedState(onEnqueue)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val group = LocalSongSwipeMotion.current
    val index = LocalSongSwipeIndex.current
    val reduced = Md3eLowSpecMode.current
    val threshold = with(LocalDensity.current) { 76.dp.toPx() }
    val reach = with(LocalDensity.current) { 136.dp.toPx() }
    var displacement by remember(title) { mutableFloatStateOf(0f) }
    var returning by remember(title) { mutableStateOf<Job?>(null) }
    val fill = MaterialTheme.colorScheme.primaryContainer
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    DisposableEffect(title, group, index) {
        onDispose {
            returning?.cancel()
            if (group?.row == index) { group.displacement = 0f; group.row = -1 }
        }
    }
    return this
        .semantics {
            customActions = listOf(CustomAccessibilityAction("添加到播放列表") {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                enqueue(); true
            })
        }
        .pointerInput(title, threshold, group, index, reduced) {
            fun update(value: Float) {
                displacement = value
                if (group?.row == index) group.displacement = value
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var travel = 0f
                // Vertical movement remains owned by the list / enclosing sheet.
                val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                    if (over < 0f) { travel = over; change.consume() }
                }
                if (start != null) {
                    returning?.cancel()
                    if (group != null) group.row = index
                    val velocity = VelocityTracker()
                    velocity.addPosition(start.uptimeMillis, start.position)
                    var armed = false
                    fun drag(delta: Float) {
                        travel = (travel + delta).coerceAtMost(0f)
                        val distance = -travel
                        // Rubber resistance beyond the commit point, never a hard edge.
                        update(-if (distance <= threshold) distance else
                            threshold + (reach - threshold) * (1f - kotlin.math.exp(-(distance - threshold) / reach)))
                        if (!armed && -displacement >= threshold) {
                            armed = true
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        } else if (armed && -displacement < threshold * .84f) armed = false
                    }
                    drag(0f)
                    val released = horizontalDrag(start.id) { change ->
                        velocity.addPosition(change.uptimeMillis, change.position)
                        drag(change.positionChange().x)
                        change.consume()
                    }
                    if (released && armed) enqueue()
                    val releaseVelocity = velocity.calculateVelocity().x.coerceIn(-1800f, 1800f)
                    returning = scope.launch {
                        if (reduced) update(0f) else animate(
                            initialValue = displacement, targetValue = 0f,
                            initialVelocity = releaseVelocity,
                            animationSpec = spring(dampingRatio = .6f, stiffness = 800f),
                        ) { value, _ -> update(value) }
                        if (group?.row == index) group.row = -1
                    }
                }
            }
        }
        .drawBehind {
            val progress = (-displacement / threshold).coerceIn(0f, 1f)
            if (progress > .01f) {
                val center = Offset(size.width - 30.dp.toPx(), size.height / 2)
                val radius = 23.dp.toPx() * (.6f + .4f * progress)
                drawCircle(fill.copy(alpha = progress), radius, center)
                drawCircle(ink.copy(alpha = .08f * progress), radius * (1f + .25f * progress), center)
                val stroke = 2.dp.toPx()
                val unit = 5.dp.toPx()
                for (line in -1..1) drawLine(ink.copy(alpha = progress),
                    center + Offset(-unit * 1.5f, unit * line), center + Offset(unit * .2f, unit * line), stroke, StrokeCap.Round)
                drawLine(ink.copy(alpha = progress), center + Offset(unit * .6f, 0f), center + Offset(unit * 1.8f, 0f), stroke, StrokeCap.Round)
                drawLine(ink.copy(alpha = progress), center + Offset(unit * 1.2f, -unit * .6f), center + Offset(unit * 1.2f, unit * .6f), stroke, StrokeCap.Round)
            }
        }
        .graphicsLayer {
            translationX = displacement
            scaleY = if (reduced) 1f else 1f - .025f * (-displacement / threshold).coerceIn(0f, 1f)
        }
        .claudeClickable(onClick = onPlay)
}
