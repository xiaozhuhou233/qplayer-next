/**
 * SongChecklistWriting.kt —— Jetpack Compose 版"钢笔逐行书写清单"动画
 *
 * 对应 song-checklist.svg(纯 Compose Canvas 实现,零资源文件、零第三方依赖):
 *   钢笔从右下静置位抬起,落到纸上从左到右逐行"写"出六条横线(标题 + 五行),
 *   每行写到前 30% 时该行的复选框与字迹墨点浮现;全部写完回到静置位,
 *   内容淡出后开始下一轮循环,默认 10s 一轮。
 *
 * 实现要点(与 SVG 版严格同源):
 *   · 画面在 1024×1024 的设计坐标系里绘制,按 Canvas 尺寸等比缩放;
 *   · 六条横线是三次贝塞尔曲线,初始化时预计算"等弧长"采样点表,
 *     书写进度 f → 查表即得笔尖坐标(与 SVG 生成脚本同一套采样数学),
 *     墨迹 = 采样折线段(96 段 + 圆角连接,视觉与曲线无差);
 *   · 钢笔的局部原点就是笔尖,`translate(笔尖) + rotate(角度)` 即笔的位姿,
 *     书写时带 ±1.5° 的手腕微摆;移动段用 easeInOutCubic 缓动;
 *   · 单一时间轴驱动全部元素,不需要多个 animate*AsState 互相配合。
 *
 * 使用步骤:
 *  1. 把本文件复制进 app 模块,package 改成你自己的包名。
 *  2. 无需任何资源与额外依赖(Canvas / PathParser 均来自 Compose 自带)。
 *  3. 在任意 Composable 中调用(见文件末尾的用法示例与 Preview)。
 */
package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/* ───────────────── 设计稿数据(1024×1024,与 song-checklist.svg 同源) ───────────────── */

/** 纸张(手绘圆角方块) */
private const val PAPER_PATH =
    "M224 267 C333 252 442 267 540 258 C623 251 699 257 754 271 C772 275 779 291 775 314 " +
        "C765 385 777 450 769 516 C762 586 777 658 765 722 C762 741 748 750 727 747 " +
        "C633 738 532 754 438 744 C358 736 290 753 233 740 C214 736 207 722 211 701 " +
        "C223 631 207 567 216 502 C225 429 210 358 215 294 C216 280 216 272 224 267 Z"

/** 一条待书写的横线:贝塞尔控制点、线宽、书写时间窗(占一轮动画的比例) */
private class InkLine(
    val d: String,
    val width: Float,
    val p0: FloatArray, val c1: FloatArray, val c2: FloatArray, val p3: FloatArray,
    val from: Float, val to: Float,
) {
    val sx: Float get() = p0[0]   // 起笔点
    val sy: Float get() = p0[1]
    val ex: Float get() = p3[0]   // 收笔点
    val ey: Float get() = p3[1]
}

private val LINES = listOf(
    InkLine("M299 319 C408 301 597 313 719 302", 17f,
        floatArrayOf(299f, 319f), floatArrayOf(408f, 301f),
        floatArrayOf(597f, 313f), floatArrayOf(719f, 302f), 0.080f, 0.220f), // 标题
    InkLine("M304 392 C430 382 587 389 681 379", 13f,
        floatArrayOf(304f, 392f), floatArrayOf(430f, 382f),
        floatArrayOf(587f, 389f), floatArrayOf(681f, 379f), 0.270f, 0.360f),
    InkLine("M302 472 C410 461 546 469 645 460", 13f,
        floatArrayOf(302f, 472f), floatArrayOf(410f, 461f),
        floatArrayOf(546f, 469f), floatArrayOf(645f, 460f), 0.405f, 0.495f),
    InkLine("M307 553 C397 545 516 550 612 541", 13f,
        floatArrayOf(307f, 553f), floatArrayOf(397f, 545f),
        floatArrayOf(516f, 550f), floatArrayOf(612f, 541f), 0.540f, 0.630f),
    InkLine("M306 632 C397 624 504 631 584 623", 13f,
        floatArrayOf(306f, 632f), floatArrayOf(397f, 624f),
        floatArrayOf(504f, 631f), floatArrayOf(584f, 623f), 0.675f, 0.765f),
    InkLine("M308 711 C384 704 463 709 534 700", 12f,
        floatArrayOf(308f, 711f), floatArrayOf(384f, 704f),
        floatArrayOf(463f, 709f), floatArrayOf(534f, 700f), 0.810f, 0.895f),
)

/** 行 2~6 的复选框勾(随行浮现,不由笔单独打勾) */
private val CHECK_PATHS = listOf(
    "M245 390 L258 404 L279 373",
    "M245 470 L258 484 L279 453",
    "M245 550 L258 564 L279 533",
    "M245 629 L258 643 L279 612",
    "M245 708 L258 722 L279 691",
)

/** 行 2~6 的"字迹"墨点(x, y) */
private val BLOTS = listOf(
    floatArrayOf(493f, 377f), floatArrayOf(516f, 458f), floatArrayOf(473f, 539f),
    floatArrayOf(501f, 621f), floatArrayOf(461f, 698f),
)

/** 四个装饰圆点(x, y, r),带呼吸闪烁 */
private val DOTS = listOf(
    floatArrayOf(829f, 464f, 8f), floatArrayOf(185f, 577f, 7f),
    floatArrayOf(818f, 725f, 6f), floatArrayOf(222f, 286f, 6f),
)
private const val PULSE_PERIOD_MS = 3400f
private val DOT_PHASES = floatArrayOf(0f, 0.9f, 1.8f, 2.6f) // 闪烁错峰(秒),与 SVG 一致

/** 钢笔:笔尖在局部原点、笔身沿 +X 方向;(线宽, 路径)列表;气孔是 r=5 的圆环 */
private val PEN_PATHS = listOf(
    15f to "M 2 -2.5 C 18 -11 40 -14.5 68 -15.5 C 120 -17 250 -18.5 330 -16.5", // 笔身上缘
    15f to "M 2 2.5 C 18 11 40 14.5 68 15.5 C 120 17 250 18.5 330 16.5",        // 笔身下缘
    15f to "M 330 -16.5 C 341 -10 341 10 330 16.5",                             // 笔尾圆头
    13f to "M 56 -15.2 C 66 -7 66 7 56 15.2",                                   // 笔尖肩线
    13f to "M 118 -16.2 C 127 -7 127 7 118 16.2",                               // 握位环
    13f to "M 296 -17.3 C 305 -7 305 7 296 17.3",                               // 笔帽箍
    9f  to "M 8.5 0 L 33 0",                                                    // 笔缝
)
private const val PEN_HOLE_X = 41f
private const val PEN_HOLE_R = 5f
private const val PEN_HOLE_STROKE = 8f

/** 笔的位姿:笔尖坐标 + 笔身角度(度,负值 = 指向右上方) */
private class PenPose(val x: Float, val y: Float, val angleDeg: Float)

private const val WRITE_ANGLE = -58f                 // 落笔书写角度
private val REST_POSE = PenPose(612f, 648f, -66f)    // 右下静置位

/** 收笔回位结束点 = 内容开始淡出的时间点(占一轮的比例) */
private const val RETURN_END = 0.955f
private const val FADE_OUT_END = 0.995f

/** animate = false(静态图)时定格的时刻:笔刚归位、内容全部可见、尚未淡出 */
private const val STATIC_T_FRACTION = 0.955f

/** 每条线的等弧长采样段数(96 段折线 + 圆角连接,视觉与贝塞尔曲线无差) */
private const val SAMPLES = 96

/* ───────────────────────────── 预计算(初始化一次) ───────────────────────────── */

/** 三次贝塞尔在参数 t 处的坐标 */
private fun cubic(
    p0: FloatArray, c1: FloatArray, c2: FloatArray, p1: FloatArray, t: Float,
): Offset {
    val u = 1f - t
    val w0 = u * u * u
    val w1 = 3f * u * u * t
    val w2 = 3f * u * t * t
    val w3 = t * t * t
    return Offset(
        w0 * p0[0] + w1 * c1[0] + w2 * c2[0] + w3 * p1[0],
        w0 * p0[1] + w1 * c1[1] + w2 * c2[1] + w3 * p1[1],
    )
}

/** 一条横线的运行时数据:解析后的 Path + 等弧长采样点表 */
private class LineData(line: InkLine) {
    val path: Path = PathParser().parsePathString(line.d).toPath()
    private val xs = FloatArray(SAMPLES + 1)
    private val ys = FloatArray(SAMPLES + 1)

    init {
        // 先按参数 t 均匀采 512 个点求累计弧长,再重采样成等弧长的 SAMPLES+1 个点,
        // 这样"书写进度 f"与"笔尖走过的路程"严格线性,墨迹永远跟住笔尖。
        val n = 512
        val px = FloatArray(n + 1)
        val py = FloatArray(n + 1)
        val cum = FloatArray(n + 1)
        for (k in 0..n) {
            val p = cubic(line.p0, line.c1, line.c2, line.p3, k.toFloat() / n)
            px[k] = p.x
            py[k] = p.y
            if (k > 0) cum[k] = cum[k - 1] + hypot(px[k] - px[k - 1], py[k] - py[k - 1])
        }
        val total = cum[n]
        var j = 0
        for (k in 0..SAMPLES) {
            val target = total * k / SAMPLES
            while (j < n && cum[j + 1] < target) j++
            val seg = cum[j + 1] - cum[j]
            val f = if (seg <= 0f) 0f else (target - cum[j]) / seg
            xs[k] = px[j] + (px[j + 1] - px[j]) * f
            ys[k] = py[j] + (py[j + 1] - py[j]) * f
        }
    }

    /** 书写进度 f(0..1)处的笔尖坐标 = 墨迹末端 */
    fun tipAt(f: Float): Offset {
        val pos = (f * SAMPLES).coerceIn(0f, SAMPLES.toFloat())
        val k = pos.toInt().coerceAtMost(SAMPLES - 1)
        val local = pos - k
        return Offset(
            xs[k] + (xs[k + 1] - xs[k]) * local,
            ys[k] + (ys[k + 1] - ys[k]) * local,
        )
    }

    /** 把 0..f 段墨迹写入 dst(折线;圆角连接下与曲线视觉无差) */
    fun buildInk(f: Float, dst: Path) {
        dst.reset()
        dst.moveTo(xs[0], ys[0])
        val maxK = (f * SAMPLES).toInt().coerceIn(0, SAMPLES)
        for (k in 1..maxK) dst.lineTo(xs[k], ys[k])
        if (maxK < SAMPLES && f > 0f) {
            val local = f * SAMPLES - maxK
            dst.lineTo(
                xs[maxK] + (xs[maxK + 1] - xs[maxK]) * local,
                ys[maxK] + (ys[maxK + 1] - ys[maxK]) * local,
            )
        }
    }
}

private class PenPath(val width: Float, val path: Path)

/** 全部静态图形,解析一次反复使用 */
// Static paths and arc-length tables are remembered by the component.

private class ChecklistArt {
    val paper: Path = PathParser().parsePathString(PAPER_PATH).toPath()
    val lines: List<LineData> = LINES.map { LineData(it) }
    val checks: List<Path> = CHECK_PATHS.map { PathParser().parsePathString(it).toPath() }
    val penPaths: List<PenPath> =
        PEN_PATHS.map { (width, d) -> PenPath(width, PathParser().parsePathString(d).toPath()) }
}

/* ─────────────────────────────── 时间轴 → 状态 ─────────────────────────────── */

/** 笔的位姿:落笔前/收笔后的移动用 easeInOutCubic,书写段严格跟随墨迹末端 */
private fun penPose(t: Float, art: ChecklistArt): PenPose {
    // 起笔:静置位 → 第一行行首
    if (t <= LINES[0].from) {
        val u = easeInOutCubic((t / LINES[0].from).coerceIn(0f, 1f))
        return lerpPose(REST_POSE, PenPose(LINES[0].sx, LINES[0].sy, WRITE_ANGLE), u)
    }
    for (i in LINES.indices) {
        val line = LINES[i]
        if (t <= line.to) { // 书写中:笔尖 = 墨迹末端,带 ±1.5° 手腕微摆
            val p = ((t - line.from) / (line.to - line.from)).coerceIn(0f, 1f)
            val tip = art.lines[i].tipAt(p)
            val wobble = (sin(p * 2.0 * Math.PI) * 1.5).toFloat()
            return PenPose(tip.x, tip.y, WRITE_ANGLE + wobble)
        }
        if (i < LINES.lastIndex) {
            val next = LINES[i + 1]
            if (t < next.from) { // 移动到下一行行首
                val u = easeInOutCubic(((t - line.to) / (next.from - line.to)).coerceIn(0f, 1f))
                return lerpPose(
                    PenPose(line.ex, line.ey, WRITE_ANGLE),
                    PenPose(next.sx, next.sy, WRITE_ANGLE),
                    u,
                )
            }
        }
    }
    // 收笔回静置位(到 RETURN_END 后保持)
    val last = LINES.last()
    val u = easeInOutCubic(((t - last.to) / (RETURN_END - last.to)).coerceIn(0f, 1f))
    return lerpPose(PenPose(last.ex, last.ey, WRITE_ANGLE), REST_POSE, u)
}

private fun lerpPose(a: PenPose, b: PenPose, u: Float): PenPose = PenPose(
    a.x + (b.x - a.x) * u,
    a.y + (b.y - a.y) * u,
    a.angleDeg + (b.angleDeg - a.angleDeg) * u,
)

private fun easeInOutCubic(u: Float): Float {
    val w = -2f * u + 2f
    return if (u < 0.5f) 4f * u * u * u else 1f - w * w * w / 2f
}

/** 第 i 条线的书写进度 0..1 */
private fun lineProgress(t: Float, i: Int): Float {
    val line = LINES[i]
    return when {
        t <= line.from -> 0f
        t >= line.to -> 1f
        else -> (t - line.from) / (line.to - line.from)
    }
}

/** 第 i 行(i ≥ 1)复选框/墨点的浮现进度:写该行的前 30% 期间淡入 */
private fun fxAlpha(t: Float, i: Int): Float {
    val line = LINES[i]
    return ((t - line.from) / ((line.to - line.from) * 0.3f)).coerceIn(0f, 1f)
}

/** 全部书写内容的整体透明度(循环结尾淡出) */
private fun contentAlpha(t: Float): Float = when {
    t < RETURN_END -> 1f
    t >= FADE_OUT_END -> 0f
    else -> 1f - (t - RETURN_END) / (FADE_OUT_END - RETURN_END)
}

/** 装饰圆点的呼吸闪烁(与 SVG 的 pulse/delay 对应) */
private fun pulseAlpha(tMs: Float, i: Int): Float {
    val phase = (tMs + DOT_PHASES[i] * 1000f) / PULSE_PERIOD_MS
    return 1f - 0.6f * (0.5f + 0.5f * cos(2.0 * Math.PI * phase).toFloat())
}

/* ─────────────────────────────── 绘制(1024 设计坐标) ─────────────────────────────── */

private fun DrawScope.drawScene(
    tMs: Float,
    cycleMs: Float,
    ink: Color,
    paper: Color,
    backdrop: Color?,
    inkScratch: Path,
    art: ChecklistArt,
    pulseTimeMs: Float,
) {
    val t = (tMs / cycleMs).coerceIn(0f, 1f)
    if (backdrop != null) drawRect(backdrop, topLeft = Offset.Zero, size = Size(1024f, 1024f))
    drawPath(art.paper, paper)

    val cAlpha = contentAlpha(t)
    for (i in LINES.indices) {
        val progress = lineProgress(t, i)
        if (progress > 0f) { // 墨迹:写完用原贝塞尔 Path,未写完用折线段
            val style = Stroke(LINES[i].width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (progress >= 1f) {
                drawPath(art.lines[i].path, ink, alpha = cAlpha, style = style)
            } else {
                inkScratch.reset()
                art.lines[i].buildInk(progress, inkScratch)
                drawPath(inkScratch, ink, alpha = cAlpha, style = style)
            }
        }
        if (i >= 1) { // 行 2~6:复选框 + 字迹随书写浮现
            val a = fxAlpha(t, i) * cAlpha
            if (a > 0f) {
                drawPath(
                    art.checks[i - 1], ink, alpha = a,
                    style = Stroke(10f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                val blot = BLOTS[i - 1]
                rotate(-15f, pivot = Offset(blot[0], blot[1])) {
                    drawOval(
                        ink, alpha = a,
                        topLeft = Offset(blot[0] - 10f, blot[1] - 7f),
                        size = Size(20f, 14f),
                    )
                }
            }
        }
    }

    for (d in DOTS.indices) {
        drawCircle(
            ink, radius = DOTS[d][2],
            center = Offset(DOTS[d][0], DOTS[d][1]),
            alpha = pulseAlpha(pulseTimeMs, d),
        )
    }

    // 钢笔:局部原点 = 笔尖,先摆角度再落到笔尖坐标
    val pose = penPose(t, art)
    translate(pose.x, pose.y) {
        rotate(pose.angleDeg, pivot = Offset.Zero) {
            for (pen in art.penPaths) {
                drawPath(
                    pen.path, ink,
                    style = Stroke(pen.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            drawCircle(
                ink, radius = PEN_HOLE_R, center = Offset(PEN_HOLE_X, 0f),
                style = Stroke(PEN_HOLE_STROKE),
            )
        }
    }
}

/* ─────────────────────────────────── 对外组件 ─────────────────────────────────── */

/**
 * "钢笔写清单"动画组件。画布恒为 1:1,内容按 1024×1024 设计稿等比缩放。
 *
 * @param inkColor       线条/钢笔/文字颜色
 * @param paperColor     便签纸颜色
 * @param backdropColor  画布底色;传 null 则透明(透出下层背景)
 * @param cycleMillis    一轮循环时长
 * @param animate        false 时定格为"全部写完、笔已归位"的静态图
 */
@Composable
fun SongChecklistWriting(
    modifier: Modifier = Modifier,
    inkColor: Color = Color(0xFF141413),
    paperColor: Color = Color(0xFFE8B95E),
    backdropColor: Color? = Color(0xFFF2F0EC),
    cycleMillis: Int = 10_000,
    animate: Boolean = true,
) {
    require(cycleMillis > 0) { "cycleMillis must be positive" }
    val running = animate && LocalMd3eRenderingActive.current &&
        !LocalMd3eMotionActive.current && !Md3eLowSpecMode.current && !LocalMd3eReducedEffects.current
    val art = remember { ChecklistArt() }
    var timeMs by remember(cycleMillis, running) {
        mutableStateOf(if (running) 0f else cycleMillis * STATIC_T_FRACTION)
    }
    LaunchedEffect(cycleMillis, running) {
        if (!running) {
            timeMs = cycleMillis * STATIC_T_FRACTION
        } else {
            val start = withFrameNanos { it } // 对齐到下一帧,避免首帧时间抖动
            while (true) {
                withFrameNanos { now ->
                    timeMs = (now - start) / 1_000_000f
                }
            }
        }
    }
    val inkScratch = remember { Path() } // 复用的墨迹临时 Path,避免每帧分配
    Canvas(modifier.aspectRatio(1f)) {
        val scale = size.minDimension / 1024f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            val cycleTimeMs = if (running) timeMs % cycleMillis else cycleMillis * STATIC_T_FRACTION
            drawScene(cycleTimeMs, cycleMillis.toFloat(), inkColor, paperColor, backdropColor, inkScratch, art, timeMs)
        }
    }
}

/* ─── 用法示例 + 预览 ───
@Preview(showBackground = true, widthDp = 320, heightDp = 320)
@Composable
fun SongChecklistDemo() {
    SongChecklistWriting(
        modifier = Modifier.fillMaxSize(),
        // cycleMillis = 10_000,   // 一轮 10 秒
        // animate = false,        // 需要静态图时改为 false
    )
}
*/
