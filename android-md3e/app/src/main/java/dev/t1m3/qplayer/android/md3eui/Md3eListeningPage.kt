package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.t1m3.qplayer.netease.NeteaseClient
import dev.t1m3.qplayer.netease.NeteaseListening
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private data class ListeningSummary(
    val total: Long = -1,
    val recent: List<NeteaseListening.SongPlay> = emptyList(),
    val pending: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
)
private data class ListeningPeriodState(
    val report: NeteaseListening.Report? = null,
    val live: NeteaseListening.Report? = null,
    val previous: NeteaseListening.Report? = null,
    val rank: List<NeteaseListening.SongPlay> = emptyList(),
    val pending: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
)

/** Independent, cancellable reads: changing periods never blocks playback or publishes an old response. */
@Composable
internal fun Md3eListeningPage(runtime: Md3eRuntime, onLogin: () -> Unit, onPlay: PlayAction, modifier: Modifier) {
    val loggedIn = runtime.home.loggedIn
    val revision = runtime.neteaseLogin.successRevision
    var refresh by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var offset by rememberSaveable(tab) { mutableIntStateOf(0) }
    val type = listOf("week", "month", "year", "recent")[tab]
    val today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
    val period = remember(type, offset, today) { NeteaseListening.period(if (tab == 3) "month" else type, offset, today) }
    var summary by remember(loggedIn, revision) { mutableStateOf(ListeningSummary()) }
    // Each selection owns its State object. In-flight callbacks never write into another period.
    val periodKey = "$type:${period.startDate}:${period.endDate}:$refresh"
    val periodState = remember(loggedIn, revision, periodKey) {
        mutableStateOf(ListeningPeriodState(pending = if (loggedIn && tab != 3) setOf("report") else emptySet()))
    }
    val state = periodState.value
    val client = NeteaseClient.INSTANCE
    LaunchedEffect(loggedIn, revision, refresh, today) {
        summary = ListeningSummary()
        if (!loggedIn) return@LaunchedEffect
        summary = summary.copy(pending = setOf("total", "recent"))
        suspend fun <T> part(key: String, read: () -> T, apply: (T) -> Unit) {
            try {
                val value = withContext(Dispatchers.IO) { read() }
                ensureActive(); apply(value)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { summary = summary.copy(errors = summary.errors + (key to (e.message ?: "读取失败，请重试"))) }
            finally { if (isActive) summary = summary.copy(pending = summary.pending - key) }
        }
        coroutineScope {
            launch { part("total", client::listeningTotalSeconds) { summary = summary.copy(total = it) } }
            launch { part("recent", client::listeningFootprints) { summary = summary.copy(recent = it) } }
        }
    }
    LaunchedEffect(periodState) {
        periodState.value = ListeningPeriodState()
        if (!loggedIn || tab == 3) return@LaunchedEffect
        val current = period.endTime == 0L
        val keys = mutableSetOf("report")
        if (current) keys += "live"
        if (tab != 2) keys += "rank"
        // Only completed periods are comparable; do not compare a partial month to a full one.
        if (!current) keys += "previous"
        periodState.value = periodState.value.copy(pending = keys)
        suspend fun <T> part(key: String, read: () -> T, apply: (T) -> Unit) {
            try {
                val value = withContext(Dispatchers.IO) { read() }
                ensureActive(); apply(value)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { periodState.value = periodState.value.copy(errors = periodState.value.errors + (key to (e.message ?: "读取失败，请重试"))) }
            finally { if (isActive) periodState.value = periodState.value.copy(pending = periodState.value.pending - key) }
        }
        coroutineScope {
            launch { part("report", { client.listeningDetailedReport(type, period.endTime) }) { periodState.value = periodState.value.copy(report = it) } }
            if (current) launch { part("live", { client.listeningReport(type) }) { periodState.value = periodState.value.copy(live = it) } }
            if (tab != 2) launch { part("rank", { client.listeningRank(type, period.endTime) }) { periodState.value = periodState.value.copy(rank = it) } }
            if (!current) launch {
                val previous = NeteaseListening.period(type, offset - 1, today)
                part("previous", { client.listeningDetailedReport(type, previous.endTime) }) { periodState.value = periodState.value.copy(previous = it) }
            }
        }
    }
    val report = state.report ?: state.live
    val seconds = state.live?.seconds?.takeIf { it >= 0 } ?: report?.seconds ?: -1
    val days = state.live?.days?.takeIf { it.isNotEmpty() } ?: report?.days.orEmpty()
    val songs = if (tab == 3) summary.recent else state.rank.ifEmpty { report?.songs.orEmpty() }
    val loading = if (tab == 3) "recent" in summary.pending else state.pending.any { it != "previous" }
    val ios = runtime.settings.bool("iosDesign")
    LazyColumn(modifier.background(MaterialTheme.colorScheme.background)
        .then(if (ios) Modifier.statusBarsPadding() else Modifier),
        contentPadding = PaddingValues(top = if (ios) 72.dp else 8.dp, bottom = 28.dp + LocalMd3eDockInset.current),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("header") {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("听歌足迹", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("网易云听歌统计", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = { refresh++ }, enabled = loggedIn && summary.pending.isEmpty() && state.pending.isEmpty(),
                    contentPadding = PaddingValues(horizontal = 16.dp)) { Text("刷新") }
            }
        }
        if (!loggedIn) {
            item("login") { SonicStatus("登录后查看听歌足迹", action = "登录网易云", onAction = onLogin) }
        } else {
            item("total") {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("累计收听", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(if ("total" in summary.pending) "读取中…" else listeningDuration(summary.total),
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        summary.errors["total"]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item("tabs") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("周", "月", "年", "最近").forEachIndexed { index, label ->
                        FilterChip(selected = tab == index, onClick = { tab = index }, modifier = Modifier.weight(1f),
                            label = { Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) })
                    }
                }
            }
            if (tab != 3) {
                item("period") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { offset-- }) { Icon(Md3eIcons.Back, "上一期") }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            val start = LocalDate.parse(period.startDate)
                            val end = LocalDate.parse(period.endDate)
                            Text(when (tab) {
                                1 -> "${start.year} 年 ${start.monthValue} 月"
                                2 -> "${start.year} 年"
                                else -> "${start.format(DateTimeFormatter.ofPattern("M.d"))} — ${end.format(DateTimeFormatter.ofPattern("M.d"))}"
                            }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (tab == 2 && offset == 0) Text("最近完整年度", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { offset++ }, enabled = offset < 0) { Icon(Md3eIcons.ChevronRight, "下一期") }
                    }
                }
                item("calendar") {
                    ListeningCard {
                        Text("本期收听", style = MaterialTheme.typography.titleMedium)
                        Text(listeningDuration(seconds), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        if (days.isNotEmpty()) {
                            Text("有收听记录 ${days.count { it.seconds > 0 }} 天", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (tab == 1) ListeningCalendar(days, LocalDate.parse(period.startDate)) else ListeningDailyChart(days)
                        } else if (loading) ListeningLoading()
                        else ListeningMessage(state.errors["live"] ?: state.errors["report"] ?: "本期暂无逐日时长") { refresh++ }
                        val previous = state.previous?.seconds ?: -1
                        if (seconds >= 0 && previous >= 0) {
                            val diff = seconds - previous
                            Text(if (diff == 0L) "与上期相同" else "较上期${if (diff > 0) "增加" else "减少"} ${listeningDuration(kotlin.math.abs(diff))}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                item("wall:$periodKey") {
                    when {
                        songs.isNotEmpty() -> key(periodKey) { ListeningRecordWall(songs, runtime, onPlay) }
                        loading -> ListeningCard {
                            Text("唱片墙", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            ListeningLoading()
                        }
                    }
                }
                item("rank_title") { ListeningSectionTitle("歌曲排行", "本期收听排行 · ${songs.size} 首") }
            } else {
                item("recent_title") { ListeningSectionTitle("最近播放", "最近 300 条云端记录") }
            }
            when {
                songs.isEmpty() && loading -> item("loading") { ListeningLoading(Modifier.padding(24.dp)) }
                songs.isEmpty() -> item("empty") {
                    Box(Modifier.padding(horizontal = 24.dp)) {
                        ListeningMessage(if (tab == 3) summary.errors["recent"] ?: "暂无播放记录"
                            else state.errors["rank"] ?: state.errors["report"] ?: "本期暂无歌曲排行") { refresh++ }
                    }
                }
                else -> itemsIndexed(songs, key = { index, row -> "$type:$offset:${row.song.id}:${row.playedAt}:$index" }) { index, row ->
                    Column {
                        if (tab == 3 && listeningDate(row.playedAt) != songs.getOrNull(index - 1)?.let { listeningDate(it.playedAt) }) {
                            Text(listeningDate(row.playedAt), Modifier.padding(start = 24.dp, top = 8.dp, bottom = 10.dp),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (tab != 3 && index == 0) ListeningTopSong(row, runtime, onPlay)
                        else ListeningSongRow(row, if (tab == 3) null else index + 1, tab == 3,
                            onEnqueue = { runtime.controller.enqueueListeningTrack(row) },
                            onPlay = { onPlay { playListeningTrack(row) } })
                    }
                }
            }
            if (tab != 3) {
                item("artists") {
                    ListeningCard {
                        Text("常听歌手", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (report?.artists.isNullOrEmpty()) {
                            Text(if ("report" in state.pending) "读取中…" else "本期暂无歌手统计", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else report!!.artists.take(10).forEachIndexed { index, artist ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${index + 1}", Modifier.width(20.dp), color = MaterialTheme.colorScheme.primary)
                                Artwork(artist.cover, Modifier.size(48.dp), corner = 24.dp)
                                Text(artist.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall)
                                if (artist.count >= 0) Text("${artist.count} 次", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                item("genres") {
                    ListeningCard {
                        Text("曲风", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (report?.genres.isNullOrEmpty()) {
                            Text(if ("report" in state.pending) "读取中…" else "本期暂无曲风统计", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else report!!.genres.forEach { genre ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(genre.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (genre.percent >= 0) Text("${String.format(Locale.CHINA, "%.0f", genre.percent)}%", style = MaterialTheme.typography.labelLarge)
                            }
                            if (genre.percent >= 0) LinearProgressIndicator(progress = { (genre.percent / 100).toFloat() },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                        }
                    }
                }
            }
            item("source") {
                Text("数据来自网易云音乐，可能延迟；部分统计需会员。",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ListeningCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun ListeningSectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ListeningCalendar(days: List<NeteaseListening.Day>, month: LocalDate) {
    val values = remember(days) { days.associate { it.date to it.seconds } }
    var selected by remember(days, month) { mutableStateOf(days.lastOrNull()?.date.orEmpty()) }
    val max = days.maxOf { it.seconds }.coerceAtLeast(1)
    val leading = month.dayOfWeek.value - 1
    val cells = ((leading + month.lengthOfMonth() + 6) / 7) * 7
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        (0 until cells).chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { cell ->
                    val number = cell - leading + 1
                    if (number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).aspectRatio(1f))
                    else {
                        val date = month.withDayOfMonth(number).toString()
                        val seconds = values[date]
                        val active = selected == date
                        val bg = when {
                            active -> MaterialTheme.colorScheme.primary
                            seconds != null && seconds > 0 -> MaterialTheme.colorScheme.primary.copy(alpha = .12f + .45f * seconds.toFloat() / max)
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(bg)
                            .clickable { selected = date }.semantics {
                                contentDescription = "$date，${if (seconds == null) "暂无数据" else listeningDuration(seconds)}"
                            }, contentAlignment = Alignment.Center) {
                            Text("$number", style = MaterialTheme.typography.labelLarge,
                                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        Text(if (selected.isEmpty()) "点击日期查看时长" else "$selected · ${listeningDuration(values[selected] ?: -1)}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ListeningRecordWall(songs: List<NeteaseListening.SongPlay>, runtime: Md3eRuntime, onPlay: PlayAction) {
    val records = songs.distinctBy { it.song.id }.take(20)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("唱片墙", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                records.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        row.forEach { record ->
                            val cover = record.song.coverThumbPath ?: record.song.coverUrl.orEmpty()
                            key(record.song.id, cover) {
                            Artwork(cover,
                                Modifier.weight(1f).aspectRatio(1f).songQueueMenu(record.song.name.orEmpty(),
                                    { onPlay { playListeningTrack(record) } },
                                    { runtime.controller.enqueueListeningTrack(record) }), corner = 0.dp)
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f).aspectRatio(1f)) }
                    }
                }
            }
            // Small bottom overlay leaves the artwork and its touch targets visible.
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(76.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .82f)))))
            Text("本期排行 · ${records.size} 首", Modifier.align(Alignment.BottomEnd).padding(16.dp),
                color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ListeningTopSong(row: NeteaseListening.SongPlay, runtime: Md3eRuntime, onPlay: PlayAction) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.songQueueMenu(row.song.name.orEmpty(), { onPlay { playListeningTrack(row) } },
            { runtime.controller.enqueueListeningTrack(row) }).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("TOP 1", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(row.song.name.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(row.song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (row.count >= 0) Text("收听 ${row.count} 次", style = MaterialTheme.typography.labelLarge)
            }
            Artwork(row.song.coverThumbPath ?: row.song.coverUrl.orEmpty(), Modifier.size(96.dp), corner = 20.dp)
        }
    }
}

@Composable
private fun ListeningDailyChart(days: List<NeteaseListening.Day>) {
    var selected by remember(days) { mutableStateOf(days.last().date) }
    val day = days.firstOrNull { it.date == selected } ?: days.last()
    val maximum = days.maxOf { it.seconds }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${day.date}  ·  ${listeningDuration(day.seconds)}", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom) {
            days.forEach { point ->
                val active = selected == point.date
                Column(Modifier.width(36.dp).clip(RoundedCornerShape(12.dp)).clickable { selected = point.date }
                    .semantics { contentDescription = "${point.date}，${listeningDuration(point.seconds)}" }
                    .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.height(120.dp).width(24.dp), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().height(if (point.seconds == 0L) 2.dp else
                            (6f + 114f * point.seconds.toFloat() / maximum).dp).clip(RoundedCornerShape(10.dp))
                            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = .35f)))
                    }
                    Text(point.date.takeLast(5), style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ListeningSongRow(row: NeteaseListening.SongPlay, number: Int?, recent: Boolean, onEnqueue: () -> Unit, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
        .songQueueMenu(row.song.name.orEmpty(), onPlay, onEnqueue).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (number != null) Text(number.toString().padStart(2, '0'), Modifier.width(24.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Artwork(row.song.coverThumbPath ?: row.song.coverUrl.orEmpty(), Modifier.size(48.dp), corner = 12.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(row.song.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(row.song.artist.orEmpty().ifBlank { row.song.album.orEmpty() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (recent && row.device.isNotBlank()) Text(row.device, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (recent) listeningTime(row.playedAt) else if (row.count >= 0) "${row.count} 次" else "—",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ListeningLoading(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Text("读取中…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ListeningMessage(message: String, onRetry: () -> Unit) {
    Column {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

internal fun listeningDuration(seconds: Long): String = when {
    seconds < 0 -> "暂无数据"
    seconds == 0L -> "0 分钟"
    seconds < 60 -> "不足 1 分钟"
    seconds < 3600 -> "${seconds / 60} 分钟"
    seconds % 3600 < 60 -> "${seconds / 3600} 小时"
    else -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分"
}
private fun listeningDate(time: Long): String = if (time > 0) SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(time)) else "时间未提供"
private fun listeningTime(time: Long): String = if (time > 0) SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(time)) else "—"
