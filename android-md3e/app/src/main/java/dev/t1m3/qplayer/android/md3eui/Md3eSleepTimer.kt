package dev.t1m3.qplayer.android.md3eui

import dev.t1m3.qplayer.android.ui.IosAwareSlider as Slider

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Ⓜ 定时停止播放（老版本的「定时播放」）：用户 2026-10-07 要求把它加回 MD3E。
// 行为逐条照搬老 app（android-shell 的 SleepTimerControl）：
//   · 滑条 0–120 分钟，0 = 关闭；
//   · 滑完停手 SLEEP_SETTLE_MS 之后才开始倒数（拖动过程中不断重新计时，
//     所以「设置完就立刻开始走」而不是跟着手指走）；
//   · 到点只「暂停」，绝不盲 toggle()——用户手动暂停后到点不能反而开始播放。
// 与老版的一处刻意差异：状态放在 Md3eApp 的根组合里，**离开队列页也会继续倒数**
// （老版把状态放在队列页内，翻页就丢——睡眠定时器不该这样）。
private const val SLEEP_SETTLE_MS = 3_500L

/** The timer's state, held by the root composition so it survives page changes. */
internal class Md3eSleepTimerState {
    var minutes by mutableIntStateOf(0)
    var remaining by mutableLongStateOf(0L)
    /** False while the countdown still waits for the user to finish sliding. */
    var armed by mutableStateOf(false)
}

@Composable
internal fun rememberMd3eSleepTimerState(): Md3eSleepTimerState =
    remember { Md3eSleepTimerState() }

/** The countdown itself: restarted by every slider move, settled, then ticked. */
@Composable
internal fun Md3eSleepCountdown(runtime: Md3eRuntime, timer: Md3eSleepTimerState) {
    val minutes = timer.minutes
    LaunchedEffect(minutes) {
        if (minutes <= 0) {
            timer.remaining = 0L
            timer.armed = false
            return@LaunchedEffect
        }
        // Show the chosen duration immediately, but hold the countdown until the
        // slider has been left alone — every move changes minutes and restarts
        // this effect, re-arming the settle delay.
        timer.remaining = minutes * 60L
        timer.armed = false
        delay(SLEEP_SETTLE_MS)
        timer.armed = true
        var left = timer.remaining
        while (left > 0L) {
            timer.remaining = left
            delay(1000L)
            left--
        }
        // 「到时自动暂停」必须是暂停：用户在此之前已手动暂停时，盲 toggle() 会开始播放。
        val playing = runCatching { runtime.controller.isPlaying() }.getOrDefault(false)
        if (playing) runtime.toggle()
        timer.minutes = 0
        timer.remaining = 0L
        timer.armed = false
    }
}

/** The control the queue page shows: a slider plus a one-line status. */
@Composable
internal fun Md3eSleepTimerControl(timer: Md3eSleepTimerState,
    modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            when {
                timer.minutes == 0 -> "定时播放：未开启"
                !timer.armed -> "定时播放：已设置 ${timer.minutes} 分钟，滑动结束后开始计时…"
                else -> "定时播放：${timer.remaining / 60}:${(timer.remaining % 60).toString().padStart(2, '0')} 后暂停"
            },
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
        Slider(
            value = timer.minutes.toFloat(),
            onValueChange = { value -> timer.minutes = value.roundToInt().coerceIn(0, 120) },
            valueRange = 0f..120f,
            steps = 119,
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        Text(
            if (timer.minutes == 0) "滑动设置 1–120 分钟"
            else if (!timer.armed) "停手 ${SLEEP_SETTLE_MS / 1000} 秒后开始倒计时"
            else "已设置 ${timer.minutes} 分钟，到时自动暂停播放",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
