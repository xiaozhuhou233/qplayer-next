package dev.t1m3.qplayer.android.md3eui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.t1m3.qplayer.lyric.LyricLine
import dev.t1m3.qplayer.lyric.LyricTiming
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

private const val LYRIC_ANCHOR = .35f
private val DefaultLyricTypeface = FontFamily(Font(R.font.google_sans_flex_bold, FontWeight.Bold))
private val LyricTypeface: FontFamily
    @Composable get() = if (LocalClaudeDesign.current) ClaudeSerif else DefaultLyricTypeface
private val lyricOpacityEasing = CubicBezierEasing(.33f, 0f, .20f, .10f)

private sealed interface DisplayLyricRow {
    val start: Long
    val end: Long
    data class Intro(override val end: Long) : DisplayLyricRow { override val start = 0L }
    data class Line(val lyric: LyricLine, override val start: Long, override val end: Long) : DisplayLyricRow
    data class Break(override val start: Long, override val end: Long) : DisplayLyricRow
}


@Composable
internal fun Md3eLyricColumn(runtime: Md3eRuntime, modifier: Modifier = Modifier) {
    val state = runtime.playback
    val lyrics = runtime.lyricState
    val rows = remember(state.songId, lyrics.revision, lyrics.lines) { displayLyricRows(lyrics.lines) }
    val rendering = LocalMd3eRenderingActive.current && !LocalMd3eMotionActive.current
    val indexLookup = remember(rows) {
        Md3eLyricIndex(rows.map { it.start }.toLongArray(), rows.map { it.end }.toLongArray())
    }
    val timed = remember(lyrics.lines) { lyrics.lines.any { line -> line.syllables.any { it.durationMs > 0 } } }
    val latest by rememberUpdatedState(state)
    val latestOffset by rememberUpdatedState(lyrics.offsetMs)
    val smooth = remember(state.songId) { mutableLongStateOf(state.position - lyrics.offsetMs) }
    val clock = remember(state.songId) { Md3eLyricClock() }
    val listState = rememberLazyListState()
    var pointerHeld by remember { mutableStateOf(false) }
    val lowSpec = runtime.settings.bool("lowSpecMode")
    val claude = LocalClaudeDesign.current
    val springEnabled = runtime.settings.bool("lyricSpring") && !lowSpec
    // Playback samples are frame-rate data. Keying this effect by position
    // restarts the coroutine on every sample while paused/playing metadata is
    // unchanged, which is particularly expensive on low-end devices.
    LaunchedEffect(state.songId, state.playing, state.seekRevision, state.playbackRevision, lyrics.offsetMs, rendering) {
        if (rendering && !state.playing) smooth.longValue = state.position - lyrics.offsetMs
    }
    LaunchedEffect(state.songId, state.playing, lyrics.offsetMs, rendering) {
        if (rendering && state.playing) {
            while (true) {
                withFrameNanos { frameNanos ->
                    val current = latest
                    smooth.longValue = clock.positionAt(
                        current.position - latestOffset, current.sampledAtNanos,
                        current.playing, current.playbackRevision, frameNanos,
                        current.seekRevision)
                }
            }
        }
    }
    val active by remember(indexLookup, smooth) {
        derivedStateOf { indexLookup.at(smooth.longValue) }
    }
    val initial = active.takeIf { it in rows.indices } ?: rows.indices.firstOrNull() ?: -1
    val motions = remember(state.songId, lyrics.revision, rows.size) {
        mutableMapOf<Int, LyricRowMotion>()
    }
    var previous by remember(state.songId, lyrics.revision) { mutableIntStateOf(-1) }

    BoxWithConstraints(modifier) {
        val centerPadding = (maxHeight * .42f).coerceAtLeast(32.dp)
        val fontSize = runtime.settings.intOf("lyricFontSize").coerceIn(14, 40)
        val spacing = (runtime.settings.intOf("lyricLineSpacing").coerceIn(100, 250) * 24f / 200f)
            .dp.coerceAtLeast(10.dp)
        LaunchedEffect(state.songId, lyrics.revision, active, springEnabled, pointerHeld) {
            if (pointerHeld) return@LaunchedEffect
            runLyricScroll(motions) {
                val index = initial
                if (index !in rows.indices || index == previous) return@runLyricScroll
                val old = previous
                val visible = listState.layoutInfo.visibleItemsInfo
                val target = visible.firstOrNull { it.index == index }
                if (old < 0 || target == null || abs(index - old) > 4 || lowSpec) {
                    listState.scrollToItem(index)
                    withFrameNanos { }
                    listState.centerLyricRow(index)
                    coroutineScope {
                        motions.toMap().forEach { (rowIndex, motion) ->
                            launch {
                                motion.offset.snapTo(-motion.scrollShift)
                                motion.scale.snapTo(if (rowIndex == index) 1f else .98f)
                                motion.alpha.snapTo(if (rowIndex == index) .85f else .175f)
                            }
                        }
                    }
                    previous = index
                    return@runLyricScroll
                }
                val anchor = listState.layoutInfo.let { info ->
                    info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) * LYRIC_ANCHOR
                }
                val delta = target.offset + target.size / 2f - anchor
                val first = visible.firstOrNull()?.index ?: old
                val affected = (minOf(first, index) - 2).coerceAtLeast(0)..
                    (maxOf(visible.lastOrNull()?.index ?: index, index) + 2).coerceAtMost(rows.lastIndex)
                // ScrollScope.scrollBy is synchronous. Commit layout movement and
                // all row compensation together, with no suspend/snapTo between them.
                affected.forEach { rowIndex ->
                    motions.getOrPut(rowIndex) { LyricRowMotion(rowIndex == old) }
                }
                listState.scroll {
                    compensateLyricScroll(affected.map { motions.getValue(it) }) { scrollBy(delta) }
                }
                val row = rows[index]
                val prior = rows.getOrNull((index - 1).coerceAtLeast(0))
                val gap = ((row.start - (prior?.end ?: row.start)).coerceAtLeast(0L)) / 1000f
                val gapAmount = ((gap - .20f) / .55f).coerceIn(0f, 1f)
                var damping = if (timed) .90f - .12f * gapAmount else .90f
                var stiffness = if (timed) {
                    val response = .48f + .27f * gapAmount
                    (2f * PI.toFloat() / response).let { it * it }
                } else 100f
                val remaining = (row.end - smooth.longValue) / 1000f - .50f
                if (remaining < .60f) {
                    damping = 1f
                    stiffness = (4.60517f / .30f).let { it * it }
                } else {
                    val envelope = 4.60517f / (sqrt(stiffness) * damping.coerceIn(.10f, 1f))
                    if (remaining < .80f && envelope - remaining < -.05f) {
                        damping = 1f
                        stiffness = (4.60517f / maxOf(remaining - .40f, .30f)).let { it * it }
                    }
                }
                val rowSpring = if (claude) ClaudeOneTake.snappy<Float>() else spring<Float>(dampingRatio = damping, stiffness = stiffness)
                coroutineScope {
                    motions.toMap().forEach { (rowIndex, motion) ->
                        val alpha = if (rowIndex == index) .85f else .175f
                        val scale = if (rowIndex == index) 1f else .98f
                        if (rowIndex !in affected) {
                            motion.offset.snapTo(-motion.scrollShift)
                            motion.alpha.snapTo(alpha)
                            motion.scale.snapTo(scale)
                            return@forEach
                        }
                        launch { motion.alpha.animateTo(alpha, tween(120, easing = lyricOpacityEasing)) }
                        val delayMs = if (springEnabled)
                            (abs(rowIndex - first) - 1).coerceAtLeast(0) * 50L else 0L
                        launch {
                            if (delayMs > 0) delay(delayMs)
                            motion.offset.animateTo(-motion.scrollShift,
                                if (springEnabled) rowSpring else tween(320, easing = FastOutSlowInEasing))
                        }
                        launch {
                            if (delayMs > 0) delay(delayMs)
                            motion.scale.animateTo(scale,
                                if (springEnabled) rowSpring else tween(320, easing = FastOutSlowInEasing))
                        }
                    }
                }
                previous = index
            }
        }
        LazyColumn(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pointerHeld = true
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                } finally { pointerHeld = false }
            }
        }, state = listState,
            contentPadding = PaddingValues(vertical = centerPadding),
            verticalArrangement = Arrangement.spacedBy(spacing)) {
            itemsIndexed(rows, key = { index, row -> "lyric_${row.start}_${row.end}_$index" }) { index, row ->
                val motion = remember(state.songId, lyrics.revision, index) {
                    motions.getOrPut(index) { LyricRowMotion(index == active) }
                }
                DisposableEffect(state.songId, lyrics.revision, index, motion) {
                    motions[index] = motion
                    onDispose { if (motions[index] === motion) motions.remove(index) }
                }
                when (row) {
                    is DisplayLyricRow.Intro -> Md3eLyricIndicator(row, smooth, state.playing,
                        index == active, fontSize, motion, false, null)
                    is DisplayLyricRow.Break -> Md3eLyricIndicator(row, smooth, state.playing,
                        index == active, fontSize, motion, true) {
                        runtime.seekDisplayed(state, row.start)
                    }
                    is DisplayLyricRow.Line -> Md3eLyricText(row, smooth, index, active, motion,
                        fontSize, runtime) { runtime.seekDisplayed(state, row.start) }
                }
            }
            if (rows.isEmpty()) item {
                Text("暂无歌词", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private suspend fun LazyListState.centerLyricRow(index: Int) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val anchor = layoutInfo.viewportStartOffset +
        (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) * LYRIC_ANCHOR
    scrollBy(item.offset + item.size / 2f - anchor)
}

private fun displayLyricRows(lines: List<LyricLine>): List<DisplayLyricRow> {
    if (lines.isEmpty()) return emptyList()
    val result = ArrayList<DisplayLyricRow>(lines.size + 4)
    if (lines.first().startMs() >= 2000L) result += DisplayLyricRow.Intro(lines.first().startMs())
    var previousEnd = 0L
    lines.forEachIndexed { index, line ->
        val start = line.startMs().coerceAtLeast(0L)
        if (index > 0 && start - previousEnd > 7000L) {
            val breakStart = previousEnd + 500L
            val previous = result.lastOrNull() as? DisplayLyricRow.Line
            if (previous != null && previous.end > breakStart)
                result[result.lastIndex] = previous.copy(end = breakStart)
            result += DisplayLyricRow.Break(breakStart, start)
        }
        val explicitEnd = line.endMs().takeIf { it > start }
        val end = explicitEnd ?: lines.getOrNull(index + 1)?.startMs() ?: (start + 960L)
        result += DisplayLyricRow.Line(line, start, end.coerceAtLeast(start + 1L))
        previousEnd = explicitEnd ?: (start + 960L)
    }
    return result
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Md3eLyricIndicator(row: DisplayLyricRow, position: State<Long>, playing: Boolean,
    active: Boolean, fontSize: Int, motion: LyricRowMotion, compact: Boolean, onClick: (() -> Unit)?) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary)
    val duration = (row.end - row.start).coerceAtLeast(1L)
    val thirdWindow = maxOf(duration / 3, 2000L)
    val thirdStart = (duration - thirdWindow).coerceAtLeast(0L)
    // Quantize the state read.  The playback clock ticks at frame rate, while
    // this indicator only changes at three boundaries.  A derived state keeps
    // the whole row from recomposing on every tick.
    val beat by remember(row.start, row.end, thirdStart) {
        derivedStateOf {
            val elapsed = position.value - row.start
            when {
                elapsed < 0 -> -1
                elapsed >= duration -> 3
                elapsed >= thirdStart -> 2
                elapsed >= thirdStart / 2 -> 1
                else -> 0
            }
        }
    }
    val size = with(LocalDensity.current) { (fontSize * if (compact) 1.04f else 1.6f).sp.toDp() }
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .graphicsLayer {
            translationY = motion.visualOffset
            scaleX = motion.scale.value; scaleY = motion.scale.value; alpha = motion.alpha.value
            transformOrigin = TransformOrigin(0f, .5f)
        }.padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(size * .25f),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            if (active && playing && beat in 0..2)
                Md3eLoadingIndicator(Modifier.size(size), color = if (index < beat) colors[index]
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))
            else Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                Box(Modifier.size(size * .52f).background(
                    if (index < beat) colors[index] else MaterialTheme.colorScheme.onSurface.copy(alpha = .12f),
                    CircleShape))
            }
        }
        if (compact) Text("间奏", fontSize = (fontSize * .7f).sp,
            fontWeight = FontWeight.Bold, fontFamily = LyricTypeface,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class TimedGlyph(val text: String, val start: Long, val end: Long,
    val group: Int, val groupStart: Long, val groupEnd: Long)

private fun lyricGlyphs(line: LyricLine, lineEnd: Long): List<TimedGlyph> {
    val result = ArrayList<TimedGlyph>()
    data class TimedGroup(var text: String, val start: Long, val end: Long)
    val groups = ArrayList<TimedGroup>()
    line.syllables.forEachIndexed { index, syllable ->
        val text = syllable.text.orEmpty()
        if (text.isEmpty()) return@forEachIndexed
        val start = syllable.startMs
        val end = if (syllable.durationMs > 0) syllable.endMs() else
            line.syllables.getOrNull(index + 1)?.startMs?.takeIf { it > start } ?: lineEnd
        if (syllable.durationMs <= 0 && text.isTrailingLyricPunctuation() && groups.isNotEmpty()) {
            groups.last().text += text
        } else groups += TimedGroup(text, start, end.coerceAtLeast(start + 1L))
    }
    groups.forEachIndexed { index, group ->
        val text = group.text
        val start = group.start
        val end = group.end
        var cursor = 0
        val pieces = ArrayList<String>()
        while (cursor < text.length) {
            val length = if (cursor + 1 < text.length && text[cursor].isHighSurrogate() &&
                text[cursor + 1].isLowSurrogate()) 2 else 1
            pieces += text.substring(cursor, cursor + length)
            cursor += length
        }
        val span = (end - start).coerceAtLeast(1L)
        pieces.forEachIndexed { pieceIndex, piece ->
            result += TimedGlyph(piece, start + span * pieceIndex / pieces.size,
                start + span * (pieceIndex + 1) / pieces.size, index, start, end)
        }
    }
    return result
}

private fun String.isTrailingLyricPunctuation(): Boolean = isNotEmpty() && all {
    it.isWhitespace() || it in "，。！？、；：,.!?;:…—~～）】》」』〕〉］}”’\"'"
}

// Source lyric fade and background-vocal pop curves.
private fun lyricActiveK(position: Long, start: Long, end: Long): Float {
    fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    if (position < start - 450L) return 0f
    if (position < start + 150L) return smooth((position - start + 450L) / 600f)
    if (position < end + 100L) return 1f
    return 1f - smooth((position - end - 100L) / 350f)
}

private fun lyricBackgroundScale(position: Long, start: Long, end: Long): Float {
    val popStart = start + 150L
    if (position < popStart) return 0f
    if (position < popStart + 460L) {
        val shifted = ((position - popStart) / 460f).coerceIn(0f, 1f) - 1f
        return 1f + 2.7f * shifted * shifted * shifted + 1.7f * shifted * shifted
    }
    if (position < end) return 1f
    val out = ((position - end) / 280f).coerceIn(0f, 1f)
    return 1f - out * out * (3f - 2f * out)
}

@Composable
private fun Md3eLyricText(row: DisplayLyricRow.Line, position: State<Long>, index: Int,
    active: Int, motion: LyricRowMotion, fontSize: Int, runtime: Md3eRuntime, onClick: () -> Unit) {
    val line = row.lyric
    val background = line.vocalChannel == LyricLine.VocalChannel.BACKGROUND ||
        line.vocalChannel == LyricLine.VocalChannel.BACKGROUND_LEFT ||
        line.vocalChannel == LyricLine.VocalChannel.BACKGROUND_RIGHT
    val right = line.vocalChannel == LyricLine.VocalChannel.DUET_RIGHT ||
        line.vocalChannel == LyricLine.VocalChannel.BACKGROUND_RIGHT
    val size = fontSize * if (background) .7f else 1f
    val fontWeight = when (runtime.settings.intOf("lyricFontWeight").coerceIn(0, 3)) {
        0 -> FontWeight.Thin; 1 -> FontWeight.Light; 2 -> FontWeight.Normal; else -> FontWeight.Medium
    }
    val mainColor = if (runtime.settings.bool("lyricMd3Color")) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val glyphs = remember(line, row.end) { lyricGlyphs(line, row.end) }
    val focused = index == active
    val played = active >= 0 && index < active
    val lowSpec = runtime.settings.bool("lowSpecMode")
    // Plain LRC follows the whole-line highlight/lift path even when linear
    // animation is enabled; inferred character timings are not word adaptation.
    val useSweep = !lowSpec && LyricTiming.hasWordTiming(line)
    val textScale = if (runtime.settings.bool("lyricScale") && !background) 1.12f else 1f
    val springEnabled = runtime.settings.bool("lyricSpring") && !lowSpec
    // Outside the pop/fade interval, layer parameters are constant. Keep those
    // rows out of the 120 Hz clock instead of updating every visible RenderNode.
    val layerPosition = remember(position, row.start, row.end) {
        derivedStateOf { position.value.coerceIn(row.start - 450L, row.end + 450L) }
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .graphicsLayer {
            // This is the same source curve as the old spring target, evaluated
            // in the layer phase. It avoids one coroutine and an Animatable per
            // visible lyric row, while retaining the lift envelope.
            // Hold the lift after singing. Releasing it at line end moved the
            // previous row DOWN while the cascade was still moving it upward.
            val lift = if (springEnabled) md3eLyricRowLift(layerPosition.value, row.start) else 0f
            translationY = motion.visualOffset - lift * 2.5.dp.toPx()
            val scale = if (background) lyricBackgroundScale(layerPosition.value, row.start, row.end) else 1f
            scaleX = motion.scale.value * scale
            scaleY = motion.scale.value * scale
            alpha = if (background) .18f + .52f * lyricActiveK(layerPosition.value, row.start, row.end)
                else motion.alpha.value
            transformOrigin = TransformOrigin(if (right) 1f else 0f, .5f)
        }.padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
        BoxWithConstraints {
            val textWidth = maxWidth / textScale
            Box(Modifier.width(textWidth)) {
            Md3eLyricGlyphs(glyphs, position, played || (focused && !useSweep),
                useSweep, springEnabled, size * textScale,
                maxOf(size * runtime.settings.intOf("lyricLineSpacing").coerceIn(100, 250) / 100f,
                    size * 1.2f), fontWeight, idleColor, mainColor, right)
            if (focused && !lowSpec && !LocalMd3eReducedEffects.current &&
                LocalMd3eRenderingActive.current && !LocalMd3eMotionActive.current &&
                runtime.settings.bool("lyricParticles")) {
                Md3eLyricParticles(position, row.start, row.end, MaterialTheme.colorScheme.primary,
                    Modifier.matchParentSize())
            }
            }
        }
        line.romaji?.takeIf { it.isNotBlank() }?.let {
            Text(it.trim(), fontSize = (size * .5f).sp, lineHeight = (size * .55f).sp,
                fontWeight = fontWeight, fontFamily = LyricTypeface, color = idleColor.copy(alpha = .75f),
                textAlign = if (right) TextAlign.Right else TextAlign.Left,
                modifier = Modifier.fillMaxWidth())
        }
        line.translation?.takeIf { it.isNotBlank() }?.let {
            Text(it.trim(), fontSize = (size * .5f).sp, lineHeight = (size * .55f).sp,
                fontWeight = fontWeight, fontFamily = LyricTypeface, color = idleColor.copy(alpha = .75f),
                textAlign = if (right) TextAlign.Right else TextAlign.Left,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

private data class Md3eGlyphPlacement(val x: Float, val y: Float, val width: Float,
    val offsetInGroup: Float, val bottom: Float)

@Composable
private fun Md3eLyricGlyphs(glyphs: List<TimedGlyph>, position: State<Long>,
    filled: Boolean, sweep: Boolean, lift: Boolean, fontSize: Float, lineHeight: Float,
    weight: FontWeight, idle: Color, active: Color, right: Boolean) {
    if (glyphs.isEmpty()) return
    val lyricTypeface = LyricTypeface
    val textMeasurer = rememberTextMeasurer(cacheSize = 16)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val fullText = remember(glyphs) { glyphs.joinToString("") { it.text } }
        val style = remember(fontSize, lineHeight, weight, right, lyricTypeface) {
            TextStyle(fontSize = fontSize.sp, lineHeight = lineHeight.sp, fontWeight = weight, fontFamily = lyricTypeface,
                textAlign = if (right) TextAlign.Right else TextAlign.Left)
        }
        val textLayout = remember(fullText, style, widthPx, textMeasurer) {
            textMeasurer.measure(fullText, style, constraints = Constraints(maxWidth = widthPx))
        }
        val rangeStart = remember(glyphs) { glyphs.minOf { it.groupStart } }
        val rangeEnd = remember(glyphs) { glyphs.maxOf { it.groupEnd } }
        val settledAt = remember(glyphs, lift, sweep, rangeStart, rangeEnd) {
            if (!lift) rangeEnd else if (!sweep)
                rangeStart + kotlin.math.ceil(((rangeEnd - rangeStart) / 1000.0)
                    .coerceIn(.45, 3.0) * 1.25 * 3000.0).toLong()
            else maxOf(rangeEnd, glyphs.maxOf { glyph ->
                glyph.start + kotlin.math.ceil(((glyph.end - glyph.start) / 1000.0)
                    .coerceIn(.45, 3.0) * 1.25 * 3000.0).toLong()
            })
        }
        val drawPosition = remember(position, rangeStart, settledAt) {
            derivedStateOf { position.value.coerceIn(rangeStart - 1L, settledAt) }
        }
        val placements = remember(glyphs, textLayout) {
            var offset = 0
            val textLength = textLayout.layoutInput.text.length
            val groupCursor = FloatArray((glyphs.maxOfOrNull { it.group } ?: -1) + 1)
            glyphs.map { glyph ->
                val start = offset.coerceIn(0, textLength)
                val end = (offset + glyph.text.length).coerceIn(start, textLength)
                val probe = start.coerceAtMost((textLength - 1).coerceAtLeast(0))
                val lineIndex = textLayout.getLineForOffset(probe)
                val startX = textLayout.getHorizontalPosition(start, true)
                val endLine = textLayout.getLineForOffset(
                    end.coerceAtMost((textLength - 1).coerceAtLeast(0)))
                val endX = if (endLine != lineIndex) textLayout.getLineRight(lineIndex)
                    else textLayout.getHorizontalPosition(end, true)
                val advance = abs(endX - startX)
                val groupOffset = groupCursor[glyph.group]
                if (glyph.text.any { !it.isWhitespace() }) groupCursor[glyph.group] += advance
                offset = end
                Md3eGlyphPlacement(minOf(startX, endX), textLayout.getLineTop(lineIndex),
                    if (glyph.text.all { it.isWhitespace() }) 0f else advance,
                    groupOffset, textLayout.getLineBottom(lineIndex))
            }
        }
        val groupWidths = remember(glyphs, placements) {
            FloatArray((glyphs.maxOfOrNull { it.group } ?: -1) + 1).also { widths ->
                glyphs.forEachIndexed { index, glyph -> widths[glyph.group] += placements[index].width }
            }
        }
        val groupStarts = remember(groupWidths) {
            FloatArray(groupWidths.size).also { starts ->
                for (index in 1 until starts.size) starts[index] = starts[index - 1] + groupWidths[index - 1]
            }
        }
        Box(Modifier.fillMaxWidth().height(with(density) { textLayout.size.height.toDp() })
            .drawWithCache {
                // Record shaped text only when font/width/colors change, not at
                // every lyric frame. Both sweep and settled states reuse these
                // SAME paragraphs, so kerning/baselines cannot jump at the end.
                val idleLayer = obtainGraphicsLayer().apply {
                    record(size = textLayout.size) { drawText(textLayout, color = idle) }
                }
                val activeLayer = obtainGraphicsLayer().apply {
                    record(size = textLayout.size) { drawText(textLayout, color = active) }
                }
                onDrawBehind {
                    val now = drawPosition.value
                    val canvas = drawContext.canvas
                    if (!sweep || now <= rangeStart || now >= settledAt) {
                        val raised = if (!lift) 0f else if (sweep && now >= settledAt) 2f
                            else if (!sweep) md3eGlyphLift(now, rangeStart, rangeEnd) else 0f
                        canvas.save()
                        canvas.translate(0f, -raised * density.density)
                        drawLayer(if (filled || now >= rangeEnd) activeLayer else idleLayer)
                        canvas.restore()
                    } else {
                        val currentGroup = glyphs.lastOrNull { now >= it.groupStart }
                        val sungWidth = if (currentGroup == null) 0f else {
                            val progress = ((now - currentGroup.groupStart).toFloat() /
                                (currentGroup.groupEnd - currentGroup.groupStart).coerceAtLeast(1L)).coerceIn(0f, 1f)
                            groupStarts[currentGroup.group] + groupWidths[currentGroup.group] * progress
                        }
                        glyphs.forEachIndexed { index, glyph ->
                            val placement = placements[index]
                            if (placement.width <= .001f) return@forEachIndexed
                            val progress = if (filled) 1f else
                                ((sungWidth - groupStarts[glyph.group] - placement.offsetInGroup) /
                                    placement.width).coerceIn(0f, 1f)
                            val raised = if (lift) md3eGlyphLift(now, glyph.start, glyph.end) else 0f
                            canvas.save()
                            canvas.translate(0f, -raised * density.density)
                            canvas.clipRect(placement.x, placement.y,
                                placement.x + placement.width, placement.bottom)
                            if (progress < .999f) drawLayer(idleLayer)
                            if (progress > .001f) {
                                canvas.clipRect(placement.x, placement.y,
                                    placement.x + placement.width * progress, placement.bottom)
                                drawLayer(activeLayer)
                            }
                            canvas.restore()
                        }
                    }
                }
            })
    }
}


@Composable
private fun Md3eLyricParticles(position: State<Long>, start: Long, end: Long,
    color: Color, modifier: Modifier = Modifier) {
    val phase = androidx.compose.animation.core.rememberInfiniteTransition(label = "lyric_particles")
        .animateFloat(0f, (2f * PI).toFloat(),
            androidx.compose.animation.core.infiniteRepeatable(tween(1800,
                easing = androidx.compose.animation.core.LinearEasing)), label = "particle_phase")
    Canvas(modifier) {
        // Read the animated values inside draw. This invalidates only the draw
        // pass; the lyric row composition is not rebuilt at 60 Hz.
        val progress = ((position.value - start).toFloat() /
            (end - start).coerceAtLeast(1L)).coerceIn(0f, 1f)
        val phaseValue = phase.value
        val edge = 12f * density
        val center = (size.width * progress).coerceIn(edge, (size.width - edge).coerceAtLeast(edge))
        repeat(24) { index ->
            val orbit = phaseValue + index * 37f
            val spread = (5f + (index % 4) * 2f) * density
            val convergence = .25f + .75f * progress
            val x = center + sin(orbit) * spread * (1f - convergence)
            val y = size.height / 2f + cos(orbit * 1.31f) * spread * .32f * (1f - convergence)
            val point = Offset(x, y)
            val alpha = (.32f + .58f * progress).coerceIn(0f, .92f)
            drawCircle(color.copy(alpha = alpha * .20f), (3.8f + index % 3 * .8f) * density,
                point)
            drawCircle(color.copy(alpha = alpha), (1.35f + index % 3 * .45f) * density,
                point)
        }
    }
}

private class Md3eLyricClock {
    private var initialized = false
    private var lastSampleNanos = 0L
    private var lastSamplePosition = 0L
    private var lastAdvanceNanos = 0L
    private var lastFrameNanos = 0L
    private var revision = 0L
    private var seekRevision = 0L
    private var rateBaseNanos = 0L
    private var rateBasePosition = 0L
    private var rate = 1.0
    private var rendered = 0.0

    fun positionAt(positionMs: Long, sampleNanos: Long, running: Boolean,
        playbackRevision: Long, frameNanos: Long, seekRevision: Long): Long {
        if (sampleNanos <= 0L) return positionMs
        val age = ((frameNanos - sampleNanos) / 1_000_000.0).coerceAtLeast(0.0)
        val newSample = sampleNanos != lastSampleNanos
        val sampleDelta = (sampleNanos - lastSampleNanos) / 1_000_000.0
        val discontinuity = initialized && newSample && (
            positionMs < lastSamplePosition - 60L ||
                abs(positionMs - lastSamplePosition - sampleDelta * rate) > 300.0)
        if (!initialized || !running || playbackRevision != revision || this.seekRevision != seekRevision ||
            (newSample && sampleDelta > 500.0) || sampleNanos < lastSampleNanos || discontinuity) {
            initialized = true
            lastSampleNanos = sampleNanos
            lastSamplePosition = positionMs
            lastAdvanceNanos = sampleNanos
            lastFrameNanos = frameNanos
            revision = playbackRevision
            this.seekRevision = seekRevision
            rateBaseNanos = sampleNanos
            rateBasePosition = positionMs
            rate = 1.0
            rendered = positionMs + if (running) age.coerceAtMost(150.0) else 0.0
            return rendered.toLong()
        }
        if (newSample) {
            val advanced = positionMs > lastSamplePosition
            val advanceGap = (sampleNanos - lastAdvanceNanos) / 1_000_000.0
            val rateWindow = (sampleNanos - rateBaseNanos) / 1_000_000.0
            if (advanced && advanceGap <= 350.0 && rateWindow in 200.0..500.0) {
                val measured = ((positionMs - rateBasePosition) / rateWindow).coerceIn(0.0, 4.0)
                rate += (measured - rate) * .35
            }
            if (rateWindow >= 200.0) {
                rateBaseNanos = sampleNanos
                rateBasePosition = positionMs
            }
            if (advanced) lastAdvanceNanos = sampleNanos
            lastSampleNanos = sampleNanos
            lastSamplePosition = positionMs
        }
        val frameMs = ((frameNanos - lastFrameNanos) / 1_000_000.0).coerceIn(0.0, 100.0)
        lastFrameNanos = frameNanos
        val stalled = age > 180.0 || frameNanos - lastAdvanceNanos > 180_000_000L
        val effectiveRate = if (stalled) 0.0 else rate
        val target = positionMs + age.coerceAtMost(150.0) * effectiveRate
        val predicted = rendered + frameMs * effectiveRate
        val error = target - predicted
        val corrected = if (abs(error) > 300.0) target else predicted + error * (1.0 - exp(-frameMs / 70.0))
        rendered = maxOf(rendered, minOf(corrected, positionMs + 150.0 * rate))
        return rendered.toLong()
    }
}

private const val MD3E_LYRIC_BACKDROP_SHADER = """
uniform shader artwork;
uniform float2 resolution;
uniform float2 artworkSize;
uniform float time;
uniform float darkOverlay;

half3 sampleArtwork(float2 coordinate) {
    return artwork.eval(coordinate * artworkSize).rgb;
}

half4 layer(float2 uv, float2 center, float size, float angle, float scrim) {
    float2 position = uv - center;
    float c = cos(angle);
    float s = sin(angle);
    float2 rotated = float2(position.x * c - position.y * s,
                            position.x * s + position.y * c);
    if (abs(rotated.x) > size * 0.5 || abs(rotated.y) > size * 0.5) {
        return half4(0.0);
    }
    half3 color = sampleArtwork(rotated / size - 0.5);
    color = mix(color, half3(0.0), half(scrim));
    color = mix(half3(0.5), color, half(1.08));
    return half4(color, 1.0);
}

half4 main(float2 position) {
    float2 coordinate = position / resolution;
    float2 centered = (coordinate - 0.5) * 2.0;
    centered += float2(sin(centered.y * 2.0 + time * 0.19),
                       cos(centered.x * 2.0 - time * 0.16)) * 0.026;
    if (resolution.x >= resolution.y) {
        centered.y *= resolution.y / resolution.x;
    } else {
        centered.x *= resolution.x / resolution.y;
    }
    float angle0 = time * 6.2831853 / 120.0;
    float angle1 = time * 6.2831853 / 70.0;
    float angle2 = time * 6.2831853 / 90.0 + 3.1415926;
    half4 first = layer(centered, float2(0.0), 2.8, angle0, 0.35);
    half4 second = layer(centered, float2(-0.25, 0.15), 1.4, angle2, 0.3652);
    half4 third = layer(centered,
        float2(0.1 + cos(-angle1 * 0.5), sin(-angle1 * 0.5)),
        1.4, angle1, 0.3576);
    half3 color = first.rgb;
    color = mix(color, second.rgb, second.a);
    color = mix(color, third.rgb, third.a);
    color = mix(color, half3(1.0), half(0.095));
    return half4(color * half(1.0 - darkOverlay), 1.0);
}
"""

@Composable
internal fun Md3eLyricDynamicBackdrop(runtime: Md3eRuntime, cover: String,
    modifier: Modifier = Modifier) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (runtime.settings.intOf("darkMode")) {
        1 -> false; 2 -> true; else -> systemDark
    }
    val fallback = if (dark) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.primaryContainer
    if (runtime.settings.bool("lowSpecMode") || cover.isBlank()) {
        Box(modifier.background(fallback))
        return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Box(modifier.background(fallback)) {
            Md3eSoftArtworkBackdrop(cover, Modifier.fillMaxSize(), 128.dp)
            Box(Modifier.fillMaxSize().background(fallback.copy(alpha = if (dark) .39f else .10f)))
        }
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val artwork by produceState<Bitmap?>(null, cover) {
        value = awaitArtworkBitmap(context, artworkRequestSource(cover))
    }
    val rendering = LocalMd3eRenderingActive.current && !LocalMd3eMotionActive.current &&
        !LocalMd3eReducedEffects.current
    // Render the soft shader at quarter width/height (1/16 pixel work),
    // then let the compositor scale its cached layer at display vsync.
    BoxWithConstraints(modifier.background(fallback)) {
        AndroidView(factory = { Md3eLyricBackdropView(it) },
            modifier = Modifier.size(maxWidth / 4, maxHeight / 4).graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = 4f; scaleY = 4f
            }, update = { it.update(artwork, dark, rendering) })
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class Md3eLyricBackdropView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shader = runCatching { RuntimeShader(MD3E_LYRIC_BACKDROP_SHADER) }.getOrNull()
    private var artwork: Bitmap? = null
    private var reducedArtwork: Bitmap? = null
    private var darkOverlay = -1f
    private var running = false
    private var framePending = false
    private val startedAt = SystemClock.uptimeMillis()
    private val nextFrame = Runnable {
        framePending = false
        if (canAnimate()) invalidate()
    }

    init {
        // Quarter-resolution layer: 36 px gives twice the previous blur radius.
        setRenderEffect(RenderEffect.createBlurEffect(36f, 36f, Shader.TileMode.MIRROR))
    }

    fun update(image: Bitmap?, dark: Boolean, active: Boolean) {
        var changed = false
        if (artwork !== image) {
            artwork = image
            reducedArtwork?.recycle()
            reducedArtwork = image?.let { source ->
                Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also { target ->
                    AndroidCanvas(target).drawBitmap(source, null,
                        Rect(0, 0, target.width, target.height), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
            reducedArtwork?.let { bitmap ->
                shader?.setInputShader("artwork",
                    BitmapShader(bitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR))
                shader?.setFloatUniform("artworkSize", bitmap.width.toFloat(), bitmap.height.toFloat())
            }
            changed = true
        }
        val overlay = if (dark) .39f else .10f
        if (overlay != darkOverlay) {
            darkOverlay = overlay
            shader?.setFloatUniform("darkOverlay", overlay)
            changed = true
        }
        running = active && image != null
        if (!running) {
            removeCallbacks(nextFrame)
            framePending = false
        }
        if (changed) invalidate()
        scheduleFrame()
    }

    private fun canAnimate() = running && isAttachedToWindow && isShown &&
        windowVisibility == VISIBLE && shader != null && reducedArtwork != null

    private fun scheduleFrame() {
        if (canAnimate() && !framePending) {
            framePending = true
            // Keep shader motion on the display's vsync. The previous 50 ms
            // timer made the backdrop visibly judder at 20 Hz and still paid
            // the same invalidation cost when a frame was actually rendered.
            postOnAnimation(nextFrame)
        }
    }

    override fun onDraw(canvas: AndroidCanvas) {
        super.onDraw(canvas)
        if (shader != null && reducedArtwork != null && width > 0 && height > 0) {
            shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
            shader.setFloatUniform("time", (SystemClock.uptimeMillis() - startedAt) * (.13f / 60f))
            paint.shader = shader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            scheduleFrame()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleFrame()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) scheduleFrame() else {
            removeCallbacks(nextFrame)
            framePending = false
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(nextFrame)
        framePending = false
        running = false
        reducedArtwork?.recycle()
        reducedArtwork = null
        artwork = null
        super.onDetachedFromWindow()
    }
}
