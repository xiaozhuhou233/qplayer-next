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
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.TileMode
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
import dev.t1m3.qplayer.lyric.LyricGlow
import dev.t1m3.qplayer.lyric.ReferenceLyricMotion
import dev.t1m3.qplayer.lyric.ReferenceLyricTimeline
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private const val LYRIC_ANCHOR = .35f
private val DefaultLyricTypeface = FontFamily(Font(R.font.google_sans_flex_bold, FontWeight.Bold))
private val LyricTypeface: FontFamily
    @Composable get() = if (LocalClaudeDesign.current) ClaudeSerif else DefaultLyricTypeface

private typealias DisplayLyricRow = dev.t1m3.qplayer.lyric.ReferenceLyricTimeline.Row
private typealias LyricTrack = dev.t1m3.qplayer.lyric.ReferenceLyricTimeline.Track

@Composable
internal fun Md3eLyricColumn(runtime: Md3eRuntime, modifier: Modifier = Modifier) {
    val settingsRevision = runtime.settingsRevision
    val state = runtime.playback
    val lyrics = runtime.lyricState
    val rows = remember(state.songId, lyrics.revision, lyrics.lines) {
        ReferenceLyricTimeline.build(lyrics.lines)
    }
    val rendering = LocalMd3eRenderingActive.current && !LocalMd3eMotionActive.current
    val indexLookup = remember(rows) {
        Md3eLyricIndex(rows.map { it.start }.toLongArray(), rows.map { it.end }.toLongArray())
    }
    val timed = remember(lyrics.lines) { lyrics.lines.any(LyricTiming::hasWordTiming) }
    val latest by rememberUpdatedState(state)
    val latestOffset by rememberUpdatedState(lyrics.offsetMs)
    val smooth = remember(state.songId) { mutableLongStateOf(state.position - lyrics.offsetMs) }
    val clock = remember(state.songId) { Md3eLyricClock() }
    val listState = rememberLazyListState()
    val motions = remember(state.songId, lyrics.revision, rows) { mutableMapOf<Int, LyricRowMotion>() }
    var pointerHeld by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var resumeFollow by remember { mutableStateOf(false) }
    var manualResumeDelay by remember { mutableLongStateOf(1500L) }
    val lowSpec = runtime.settings.bool("lowSpecMode")
    val springEnabled = runtime.settings.bool("lyricSpring") && !lowSpec
    val edgeBlur = runtime.settings.bool("lyricEdgeBlur") && !lowSpec

    var pendingViewportDelta by remember(state.songId, lyrics.revision) { mutableFloatStateOf(0f) }
    LaunchedEffect(pointerHeld) {
        if (pointerHeld) {
            browsing = true; resumeFollow = true
            pendingViewportDelta = 0f
            motions.values.forEach { it.freeze() }
        } else if (browsing) {
            delay(manualResumeDelay)
            snapshotFlow { listState.isScrollInProgress }.first { !it }
            browsing = false
        }
    }
    val active by remember(indexLookup, smooth) { derivedStateOf { indexLookup.at(smooth.longValue) } }
    // Opposite voices may sing simultaneously while the earlier line remains
    // the scroll anchor. Background voices live inside that anchor's row.
    val secondActive by remember(rows, smooth, active) {
        derivedStateOf {
            val next = active + 1
            if (next in rows.indices && !rows[next].instrumental() &&
                smooth.longValue >= rows[next].start && smooth.longValue < rows[next].end) next else -1
        }
    }
    var previous by remember(state.songId, lyrics.revision) { mutableIntStateOf(-1) }
    var previousSeek by remember(state.songId, lyrics.revision) { mutableStateOf(state.seekRevision) }

    BoxWithConstraints(modifier) {
        val viewportHeight = with(LocalDensity.current) { maxHeight.toPx() }
        val centerPadding = maxHeight + (maxHeight * .42f).coerceAtLeast(32.dp)
        // Row springs use frame time; syllables use the actual audio clock. Pausing
        // cannot advance karaoke or make a later transition appear to be missed.
        LaunchedEffect(state.songId, rendering, motions, viewportHeight) {
            if (!rendering) return@LaunchedEffect
            var lastFrame = 0L
            while (true) withFrameNanos { frame ->
                val current = latest
                smooth.longValue = clock.positionAt(
                    current.position - latestOffset, current.sampledAtNanos, current.playing,
                    current.playbackRevision, frame, current.seekRevision)
                val dt = if (lastFrame == 0L) 0.0 else ((frame - lastFrame) / 1e9).coerceIn(0.0, .1)
                lastFrame = frame
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                    motions.values.forEach { it.tick(dt) }
                    if (!browsing && !pointerHeld) pendingViewportDelta = rebaseLyricViewport(
                        pendingViewportDelta, viewportHeight, motions.values) {
                        listState.scrollLyricPixels(pendingViewportDelta)
                    }
                }
            }
        }

        val fontSize = runtime.settings.intOf("lyricFontSize").coerceIn(14, 40)
        val spacing = (runtime.settings.intOf("lyricLineSpacing").coerceIn(100, 250) * 25f / 200f)
            .dp.coerceAtLeast(10.dp)
        LaunchedEffect(state.songId, lyrics.revision, active, secondActive, state.seekRevision, springEnabled, browsing) {
            if (browsing) return@LaunchedEffect
            val index = active.coerceAtLeast(0)
            if (index !in rows.indices) return@LaunchedEffect
            val seeking = previousSeek != state.seekRevision
            val returning = resumeFollow
            resumeFollow = false
            val old = previous
            previous = index
            previousSeek = state.seekRevision
            val visible = listState.layoutInfo.visibleItemsInfo
            val target = visible.firstOrNull { it.index == index }
            if (old < 0 || target == null || lowSpec) {
                pendingViewportDelta = 0f
                listState.scrollToItem(index)
                withFrameNanos { }
                listState.centerLyricRow(index, viewportHeight)
                pendingViewportDelta = 0f
                motions.forEach { (i, motion) ->
                    val selected = i == index || i == secondActive
                    motion.snap(selected, if (selected || rows[i].instrumental()) 0f else
                        ReferenceLyricMotion.blurRadius(validLyricDistance(rows, index, i)).toFloat())
                }
                return@LaunchedEffect
            }
            val anchor = listState.layoutInfo.let { info ->
                info.viewportStartOffset + viewportHeight * (1f + LYRIC_ANCHOR)
            }
            val delta = if (old == index && !seeking && !returning) pendingViewportDelta else target.offset + target.size / 2f - anchor
            val clipStart = listState.layoutInfo.viewportStartOffset + viewportHeight
            val first = visible.firstOrNull {
                val motion = motions[it.index]
                it.offset + (motion?.visualOffset ?: 0f) +
                    it.size * (motion?.scale?.floatValue ?: 1f) > clipStart
            }?.index ?: old
            val affected = (minOf(first, index) - 2).coerceAtLeast(0)..
                (maxOf(visible.lastOrNull()?.index ?: index, index) + 2).coerceAtMost(rows.lastIndex)
            affected.forEach { i -> motions.getOrPut(i) { LyricRowMotion(i == old) } }
            pendingViewportDelta = delta
            val row = rows[index]
            val prior = rows.getOrNull(index - 1)
            val gap = ((row.start - (prior?.end ?: row.start)).coerceAtLeast(0L)) / 1000.0
            val remaining = (row.end - smooth.longValue) / 1000.0 - .50
            val physics = if (springEnabled) ReferenceLyricMotion.lineSpring(timed, gap, remaining,
                seeking || returning, abs(index - old) > 3) else ReferenceLyricMotion.criticalEnvelope(.32)
            // Retarget in place. Cancelling an effect must never snap the old
            // cascade to its endpoint when another short phrase starts.
            motions.forEach { (i, motion) ->
                val delay = md3eLyricCascadeDelay(first, i, validLyricDistance(rows, first, i),
                    !springEnabled || seeking || returning || i !in affected)
                val selected = i == index || i == secondActive
                val blur = if (selected || rows[i].instrumental()) 0f else
                    ReferenceLyricMotion.blurRadius(validLyricDistance(rows, index, i)).toFloat()
                motion.retarget(selected, physics, delay, delay, blur, delta,
                    row.instrumental(), seeking, springEnabled)
            }
        }
        val density = LocalDensity.current.density
        val blurEffects = remember(density) {
            arrayOfNulls<androidx.compose.ui.graphics.RenderEffect>(61).also { effects ->
                for (i in 1..60) {
                    val radius = i * .1f * density
                    effects[i] = androidx.compose.ui.graphics.BlurEffect(radius, radius, TileMode.Decal)
                }
            }
        }
        LyricOverscan(Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, .11f to Color.Black,
                    .83f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var dragged = false
                    pointerHeld = true
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }) dragged = true
                        }
                        while (event.changes.any { it.pressed })
                    } finally {
                        manualResumeDelay = if (dragged) 1500L else 100L
                        pointerHeld = false
                    }
                }
            }) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = centerPadding),
                verticalArrangement = Arrangement.spacedBy(spacing)) {
                itemsIndexed(rows, key = { index, row -> "lyric_${row.start}_${row.end}_$index" }) { index, row ->
                    val motion = remember(state.songId, lyrics.revision, index) {
                        motions.getOrPut(index) { LyricRowMotion(index == active) }
                    }
                    DisposableEffect(motions, index, motion) {
                        motions[index] = motion
                        onDispose { if (motions[index] === motion) motions.remove(index) }
                    }
                    if (row.instrumental()) Md3eLyricIndicator(row, smooth, fontSize, motion)
                    else {
                        val selected = index == active || index == secondActive
                        Column(Modifier.fillMaxWidth().graphicsLayer {
                            translationY = motion.visualOffset
                            val scale = if (runtime.settings.bool("lyricScale")) motion.scale.floatValue else 1f
                            scaleX = scale; scaleY = scale
                            alpha = if (browsing) maxOf(motion.alpha.floatValue, .55f) else motion.alpha.floatValue
                            val blurIndex = if (browsing || !edgeBlur) 0 else
                                (motion.blur.floatValue * 10f).toInt().coerceIn(0, 60)
                            renderEffect = blurEffects[blurIndex]
                            transformOrigin = TransformOrigin(if (row.tracks.all { ReferenceLyricTimeline.right(it.line) }) 1f else 0f, 0f)
                        }) {
                            row.tracks.forEach { track ->
                                Md3eLyricText(track, smooth, selected, fontSize, runtime) {
                                    runtime.seekDisplayed(state, track.start)
                                }
                            }
                        }
                    }
                }
                if (rows.isEmpty()) item {
                    Text("暂无歌词", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Keep adjacent rows placed while their layers travel, without laying out the
 * lazy list on each animation frame. The parent still clips and handles touch
 * within the actual visible viewport. */
@Composable
private fun LyricOverscan(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val height = constraints.maxHeight
        val child = measurables.single().measure(
            constraints.copy(minHeight = height * 3, maxHeight = height * 3))
        layout(constraints.maxWidth, height) { child.place(0, -height) }
    }
}

private fun validLyricDistance(rows: List<DisplayLyricRow>, a: Int, b: Int): Int =
    if (a < 0 || b < 0 || a == b) 0 else ((minOf(a, b) + 1)..maxOf(a, b)).count {
        it in rows.indices && !rows[it].instrumental()
    }

/** Compensate the pixels actually placed by LazyColumn, including its integer
 * rounding, rather than the fractional distance consumed by its scroll state. */
private fun LazyListState.scrollLyricPixels(delta: Float): Float {
    val before = layoutInfo.visibleItemsInfo.associate { it.index to it.offset }
    val consumed = dispatchRawDelta(delta)
    val shared = layoutInfo.visibleItemsInfo.firstOrNull { it.index in before }
    return if (shared != null) (before.getValue(shared.index) - shared.offset).toFloat()
        else kotlin.math.round(consumed)
}

private suspend fun LazyListState.centerLyricRow(index: Int, viewportHeight: Float) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val anchor = layoutInfo.viewportStartOffset +
        viewportHeight * (1f + LYRIC_ANCHOR)
    scrollBy(item.offset + item.size / 2f - anchor)
}

@Composable
private fun Md3eLyricIndicator(row: DisplayLyricRow, position: State<Long>, fontSize: Int, motion: LyricRowMotion) {
    val sample = remember(row) { ReferenceLyricMotion.BreakSample() }
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height((fontSize * 1.5f).dp).graphicsLayer {
        translationY = motion.visualOffset
    }.padding(horizontal = 8.dp)) {
        sample.update(row.start / 1000.0, row.end / 1000.0, position.value / 1000.0)
        val factor = fontSize / 48f
        val radius = 6.dp.toPx() * factor
        val step = 20.dp.toPx() * factor
        repeat(3) { index ->
            drawCircle(color.copy(alpha = sample.dots[index].toFloat()),
                radius * sample.scale.toFloat(), Offset(radius * 1.3f + step * index, size.height / 2))
        }
    }
}

private data class TimedGlyph(val text: String, val group: Int, val groupStart: Long, val groupEnd: Long,
    val sweepEnd: Long,
    val animatedIndex: Int, val animatedCount: Int, val cjk: Boolean, val longAscii: Boolean)

private fun lyricGlyphs(line: LyricLine, lineEnd: Long): List<TimedGlyph> {
    data class Group(var text: String, val start: Long, val end: Long)
    val groups = ArrayList<Group>()
    line.syllables.forEachIndexed { index, s ->
        val text = s.text.orEmpty()
        if (text.isEmpty()) return@forEachIndexed
        val end = if (s.durationMs > 0) s.endMs() else
            line.syllables.getOrNull(index + 1)?.startMs?.takeIf { it > s.startMs } ?: lineEnd
        val last = groups.lastOrNull()
        if (last != null && ((last.start == s.startMs && last.end == end) ||
                (s.durationMs <= 0 && text.isTrailingLyricPunctuation()))) last.text += text
        else groups += Group(text, s.startMs, end.coerceAtLeast(s.startMs + 1))
    }
    val cjk = line.text().any { it.code in 0x2E80..0x9FFF || it.code in 0xAC00..0xD7AF || it.code in 0xF900..0xFAFF }
    val breaker = android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
    return groups.flatMapIndexed { index, group ->
        breaker.setText(group.text)
        val pieces = ArrayList<String>()
        var start = breaker.first()
        var end = breaker.next()
        while (end != android.icu.text.BreakIterator.DONE) {
            pieces += group.text.substring(start, end); start = end; end = breaker.next()
        }
        val count = pieces.count { it.any { c -> !c.isWhitespace() } }.coerceAtLeast(1)
        var animatedIndex = 0
        val longAscii = ReferenceLyricMotion.longAscii(group.text, (group.end - group.start) / 1000.0)
        val sweepEnd = LyricTiming.continuousSweepEndMs(group.start, group.end,
            groups.getOrNull(index + 1)?.start ?: group.end)
        pieces.map { text ->
            val i = if (text.all(Char::isWhitespace)) -1 else animatedIndex++
            TimedGlyph(text, index, group.start, group.end, sweepEnd, i, count, cjk, longAscii)
        }
    }
}

private fun String.isTrailingLyricPunctuation(): Boolean = isNotEmpty() && all {
    it.isWhitespace() || it in "，。！？、；：,.!?;:…—~～）】》」』〕〉］}”’\"'"
}

@Composable
private fun Md3eLyricText(track: LyricTrack, position: State<Long>, focused: Boolean,
    fontSize: Int, runtime: Md3eRuntime, onClick: () -> Unit) {
    val line = track.line
    val background = ReferenceLyricTimeline.background(line)
    val right = ReferenceLyricTimeline.right(line)
    val size = fontSize * if (background) .70f else 1f
    val weight = when (runtime.settings.intOf("lyricFontWeight").coerceIn(0, 3)) {
        0 -> FontWeight.Normal; 1 -> FontWeight.Medium; 2 -> FontWeight.Bold; else -> FontWeight.ExtraBold
    }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < .5f
    val mainColor = if (runtime.settings.bool("lyricMd3Color")) scheme.primary
        else if (dark) Color.White else scheme.onSurface
    val idleColor = if (dark) Color(0xFFD3DAF0).copy(alpha = .58f) else scheme.onSurface.copy(alpha = .58f)
    val glyphs = remember(line, track.end) { lyricGlyphs(line, track.end) }
    val lowSpec = runtime.settings.bool("lowSpecMode")
    val sweep = !lowSpec && LyricTiming.hasWordTiming(line)
    val springEnabled = runtime.settings.bool("lyricSpring") && !lowSpec
    val layerPosition = remember(position, track.start, track.end) {
        derivedStateOf { position.value.coerceIn(track.start - 450, track.end + 500) }
    }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by androidx.compose.animation.core.animateFloatAsState(if (pressed) .95f else 1f,
        spring(dampingRatio = 1f, stiffness = 400f), label = "lyric_press")
    Column(Modifier.fillMaxWidth().clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .graphicsLayer {
            val now = layerPosition.value
            val wholeLift = if (!sweep && springEnabled) md3eLyricRowLift(now, track.start) * 2.dp.toPx() else 0f
            translationY = -wholeLift
            val specialScale = if (background) .90f + .10f * md3eLyricRowLift(now, track.start) else 1f
            scaleX = specialScale * pressScale; scaleY = specialScale * pressScale
            alpha = if (background && focused) .50f / .85f else 1f
            transformOrigin = TransformOrigin(if (right) 1f else 0f, 1f)
        }.padding(horizontal = 8.dp, vertical = if (background) 2.dp else 6.dp),
        horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
        Box(Modifier.fillMaxWidth()) {
            Md3eLyricGlyphs(glyphs, position, focused, sweep, springEnabled, size,
                size * 1.25f, weight, idleColor, mainColor, right,
                runtime.settings.bool("lyricGlow") && !lowSpec, runtime.playback.seekRevision)
            if (focused && !lowSpec && !LocalMd3eReducedEffects.current &&
                LocalMd3eRenderingActive.current && !LocalMd3eMotionActive.current && runtime.settings.bool("lyricParticles")) {
                Md3eLyricParticles(position, track.start, track.end, scheme.primary, Modifier.matchParentSize())
            }
        }
        listOf(line.romaji, line.translation).filterNotNull().filter(String::isNotBlank).distinct().forEach {
            Text(it.trim(), fontSize = (size * 26f / 48f).sp, lineHeight = (size * .70f).sp,
                fontWeight = weight, fontFamily = LyricTypeface,
                color = if (dark) Color(0xFFD5DCF2).copy(alpha = .30f) else scheme.onSurface.copy(alpha = .55f),
                textAlign = if (right) TextAlign.Right else TextAlign.Left,
                modifier = Modifier.fillMaxWidth().padding(top = (size * .16f).dp))
        }
    }
}

private data class Md3eGlyphPlacement(val x: Float, val y: Float, val width: Float,
    val offsetInGroup: Float, val bottom: Float, val baseline: Float, val firstLine: Boolean, val lastLine: Boolean)

@Composable
private fun Md3eLyricGlyphs(glyphs: List<TimedGlyph>, position: State<Long>, focused: Boolean,
    sweep: Boolean, lift: Boolean, fontSize: Float, lineHeight: Float, weight: FontWeight,
    idle: Color, active: Color, right: Boolean, glow: Boolean, seekRevision: Long) {
    if (glyphs.isEmpty()) return
    val typeface = LyricTypeface
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val density = LocalDensity.current
    val filled by remember(position, focused, sweep, glyphs) {
        derivedStateOf { !sweep && (focused && position.value >= glyphs.first().groupStart || position.value >= glyphs.maxOf { it.groupEnd }) }
    }
    val wordMotions = remember(glyphs, seekRevision) {
        glyphs.distinctBy { it.group }.map { glyph ->
            Md3eLyricWordMotion(glyph.groupStart, glyph.groupEnd, glyph.longAscii)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val text = remember(glyphs) { glyphs.joinToString("") { it.text } }
        val style = remember(fontSize, lineHeight, weight, right, typeface) {
            TextStyle(fontSize = fontSize.sp, lineHeight = lineHeight.sp, fontWeight = weight,
                fontFamily = typeface, textAlign = if (right) TextAlign.Right else TextAlign.Left)
        }
        val layout = remember(text, style, widthPx, measurer) {
            measurer.measure(text, style, constraints = Constraints(maxWidth = widthPx))
        }
        val rangeStart = remember(glyphs) { glyphs.minOf { it.groupStart } }
        val rangeEnd = remember(glyphs) { glyphs.maxOf { it.groupEnd } }
        val settledAt = rangeEnd + if (lift && sweep) 10000 else if (glow) 160 else 0
        val drawPosition = remember(position, rangeStart, settledAt) {
            derivedStateOf { position.value.coerceIn(rangeStart - 1, settledAt) }
        }
        val placements = remember(glyphs, layout) {
            var offset = 0
            val groupCursor = FloatArray(glyphs.maxOf { it.group } + 1)
            glyphs.map { glyph ->
                val start = offset
                val end = offset + glyph.text.length
                val probe = start.coerceAtMost((text.length - 1).coerceAtLeast(0))
                val line = layout.getLineForOffset(probe)
                val startX = layout.getHorizontalPosition(start, true)
                val endLine = layout.getLineForOffset(end.coerceAtMost((text.length - 1).coerceAtLeast(0)))
                val endX = if (endLine != line) layout.getLineRight(line) else layout.getHorizontalPosition(end, true)
                val width = abs(endX - startX)
                val groupOffset = groupCursor[glyph.group]
                groupCursor[glyph.group] += width
                offset = end
                Md3eGlyphPlacement(minOf(startX, endX), layout.getLineTop(line), width,
                    groupOffset, layout.getLineBottom(line), layout.getLineBaseline(line),
                    line == 0, line == layout.lineCount - 1)
            }
        }
        val groupWidths = remember(glyphs, placements) {
            FloatArray(glyphs.maxOf { it.group } + 1).also { widths ->
                glyphs.forEachIndexed { i, g -> widths[g.group] += placements[i].width }
            }
        }
        val inkPadding = with(density) { (fontSize.sp.toPx() * .35f + 8.dp.toPx()).toInt() }
        val raster = remember(layout, inkPadding, active, idle) {
            LyricGlyphRaster(layout.size.width, layout.size.height, inkPadding,
                active.toArgb(), idle.toArgb()) { canvas ->
                layout.multiParagraph.paint(androidx.compose.ui.graphics.Canvas(canvas), Color.White)
            }
        }
        val feather = with(density) { (40.dp.toPx() * fontSize / 48f).coerceAtLeast(1f) }
        val glowRaster = remember(layout, inkPadding, active, idle, glow) {
            if (!glow) null else LyricGlyphRaster(layout.size.width, layout.size.height, inkPadding,
                active.copy(alpha = .45f).toArgb(), Color.Transparent.toArgb()) { canvas ->
                layout.multiParagraph.paint(androidx.compose.ui.graphics.Canvas(canvas), Color.White,
                    shadow = androidx.compose.ui.graphics.Shadow(Color.White, Offset.Zero,
                        with(density) { 7.dp.toPx() }))
            }
        }
        Box(Modifier.fillMaxWidth().height(with(density) { layout.size.height.toDp() }).drawWithContent {
            val now = drawPosition.value
            val canvas = drawContext.canvas.nativeCanvas
            if (!sweep || now < rangeStart) {
                if (glow && filled) glowRaster?.drawSolid(canvas, active.copy(alpha = .45f).toArgb())
                raster.drawSolid(canvas, (if (filled) active else idle).toArgb())
            } else {
                if (lift) wordMotions.forEach { it.sample(now) }
                glyphs.forEachIndexed { i, glyph ->
                    val p = placements[i]
                    val motion = wordMotions[glyph.group]
                    if (p.width < .001f) return@forEachIndexed
                    val progress = ((now - glyph.groupStart).toFloat() /
                        (glyph.sweepEnd - glyph.groupStart).coerceAtLeast(1)).coerceIn(0f, 1f)
                    val start = p.x + lyricHighlightStart(groupWidths[glyph.group], progress,
                        feather, p.offsetInGroup)
                    // Every timed word, including long ASCII words excluded
                    // from the pulse effect, rises to the same final height.
                    // Use audio time so seeking/pausing cannot restart it.
                    val raised = if (lift) motion.raised * density.density else 0f
                    val scale = if (lift) motion.scale else 1f
                    val x = if (lift && glyph.animatedIndex >= 0)
                        (glyph.animatedIndex - (glyph.animatedCount - 1) * .5f) * motion.spread *
                            groupWidths[glyph.group] / glyph.animatedCount else 0f
                    canvas.save()
                    // Keep the actual text baseline as the scale pivot; the
                    // smooth word lift settles uniformly instead of sticking
                    // at different heights for long and short words.
                    canvas.translate(x, -raised)
                    canvas.scale(scale, scale, p.x + p.width / 2, p.baseline)
                    canvas.clipRect(p.x, if (p.firstLine) -inkPadding.toFloat() else p.y,
                        p.x + p.width, if (p.lastLine) layout.size.height + inkPadding.toFloat() else p.bottom)
                    raster.drawGradient(canvas, start, feather)
                    if (glow) {
                        val a = LyricGlow.opacity(now, glyph.groupStart, glyph.sweepEnd)
                        if (a > .001f) glowRaster?.drawGradient(canvas, start, feather, a)
                    }
                    canvas.restore()
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
