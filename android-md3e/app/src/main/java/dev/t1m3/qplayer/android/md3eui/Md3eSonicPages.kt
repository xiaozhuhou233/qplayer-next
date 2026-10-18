@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.t1m3.qplayer.android.md3eui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.t1m3.qplayer.ai.DailyVibeCuration
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist
import dev.t1m3.qplayer.netease.dto.NeteaseSong
import dev.t1m3.qplayer.settings.SettingsCatalog
import kotlinx.coroutines.delay

/** Sonic Tonal's native layout, backed by QPlayer's existing account and player. */
@Composable
internal fun SonicHomePage(runtime: Md3eRuntime, onLogin: () -> Unit, onPlay: PlayAction,
    onOpenPlaylist: (Long) -> Unit, onSearch: () -> Unit, onRecognize: () -> Unit,
    onOpenAlbum: (Long) -> Unit, timer: Md3eSleepTimerState,
    modifier: Modifier = Modifier) {
    val state = runtime.home
    val recentMotion = remember { SongSwipeMotion() }
    val dailyMotion = remember { SongSwipeMotion() }
    val sectionMotions = remember(state.sections.map { it.id }) {
        state.sections.associate { it.id to SongSwipeMotion() }
    }
    var selectedVibe by remember { mutableStateOf<DailyVibeCuration.Playlist?>(null) }
    var collectPlaylist by remember { mutableStateOf<NeteasePlaylist?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val roam = LocalClaudePrivateRoam.current
    LaunchedEffect(runtime.settingsRevision, state.loggedIn, state.userName, runtime.uiVisible) {
        while (true) {
            if (runtime.uiVisible) runtime.dailyVibes.ensure()
            delay(60_000)
        }
    }
    val playlists = remember(state.playlists, state.playlistSections) {
        (state.playlists + state.playlistSections.flatMap { it.playlists }).distinctBy { it.id }
    }
    LazyColumn(modifier, contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp + LocalMd3eDockInset.current)) {
        item("search") { SonicHomeSearch(onSearch, onRecognize) }
        item("filters") {
            SonicChips(listOf("为你推荐", "私人漫游", "AI 心流"), filter) {
                if (it == 1) { if (state.loggedIn) roam?.open() else onLogin() }
                else filter = it
            }
        }
        if (filter != 2) {
            item("editors_title") {
                Box(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                    SonicSectionTitle("Editor's Choice", "推荐歌单", "Explore", onSearch)
                }
            }
            if (playlists.isNotEmpty()) item("spotlight") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(playlists.take(10), key = { _, p -> p.id }) { index, p ->
                        Md3eCollectionContainer("cover:playlist:${p.id}", corner = 24.dp) {
                            SonicFeatureCard(p.name.orEmpty(), p.creatorNickname.orEmpty().ifBlank { "为你精选" },
                                p.description.orEmpty().ifBlank { "${p.trackCount} 首歌曲 · 网易云音乐" },
                                p.coverThumbPath ?: p.coverUrl.orEmpty(), index, "精选歌单",
                                onOpen = { onOpenPlaylist(p.id) }, onPlay = { onPlay { playPlaylist(p.id) } },
                                coverKey = "cover:playlist:${p.id}",
                                saved = p.owned || p.subscribed || runtime.playlists.mine.any { it.id == p.id },
                                onSave = { if (!state.loggedIn) onLogin() else collectPlaylist = p })
                        }
                    }
                }
            } else item("home_status") {
                SonicStatus(if (state.loading) "正在加载精选歌单…" else state.error.ifBlank { "暂时没有推荐歌单" },
                    state.loading, "刷新") { runtime.controller.loadHome() }
            }
        }
        if (filter == 0 && state.recent.isNotEmpty()) {
            item("recent_title") {
                Box(Modifier.padding(top = 24.dp, bottom = 4.dp)) { SonicSectionTitle("Recently Played", "再听一次，让心情继续") }
            }
            itemsIndexed(state.recent.take(3), key = { i, s -> "recent:$i:${s.id}" }) { index, song ->
                SonicGroupedSongRow(index, minOf(3, state.recent.size), recentMotion) {
                    SonicRecentRow(song, { runtime.controller.enqueueNeteaseSong(song) }) { onPlay { playRecentSong(index) } }
                }
            }
        }
        item("ai_input") {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 28.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!runtime.dailyVibes.state.configured) Text("AI 推荐", style = MaterialTheme.typography.titleMedium)
                ClaudeAiDjInput(runtime)
                if (runtime.dailyVibes.state.configured) {
                    DailyVibeShelf(runtime, onLogin = onLogin) { selectedVibe = it }
                }
            }
        }
        if (filter != 2 && state.daily.isNotEmpty()) {
            item("daily_title") {
                Box(Modifier.padding(top = 20.dp, bottom = 4.dp)) {
                    SonicSectionTitle("Daily Rotation", "今天值得循环的声音", "播放全部", { onPlay { playRecommendation(0) } })
                }
            }
            itemsIndexed(state.daily.take(8), key = { i, s -> "daily:$i:${s.id}" }) { index, song ->
                SonicGroupedSongRow(index, minOf(8, state.daily.size), dailyMotion) {
                    SonicSongRow(song, index + 1, { runtime.controller.enqueueNeteaseSong(song) }) { onPlay { playRecommendation(index) } }
                }
            }
        }
        if (filter == 0 && state.albums.isNotEmpty()) {
            item("new_releases_title") {
                Box(Modifier.padding(top = 24.dp, bottom = 4.dp)) {
                    SonicSectionTitle("New Releases", "为你精选的专辑", "Explore", onSearch)
                }
            }
            item("new_releases") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.albums.distinctBy { it.id }.take(12), key = { it.id }) { album ->
                        Md3eCollectionContainer("cover:album:${album.id}", corner = 16.dp) {
                            Column(Modifier.width(140.dp).clickable { onOpenAlbum(album.id) }) {
                                Box(Modifier.size(140.dp)) {
                                    Artwork(album.coverThumbPath ?: album.coverUrl.orEmpty(), Modifier.fillMaxSize().md3eSharedCover("cover:album:${album.id}"), corner = 16.dp)
                                    Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(34.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Md3eIcons.PlayArrow, "查看专辑", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(album.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(album.artistName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (filter == 0) state.sections.take(3).forEach { section ->
            item("section:${section.id}") {
                Box(Modifier.padding(top = 24.dp, bottom = 4.dp)) { SonicSectionTitle(section.title.orEmpty()) }
            }
            itemsIndexed(section.songs.take(5), key = { i, s -> "section:${section.id}:$i:${s.id}" }) { index, song ->
                SonicGroupedSongRow(index, minOf(5, section.songs.size), sectionMotions.getValue(section.id)) {
                    SonicSongRow(song, index + 1, { runtime.controller.enqueueNeteaseSong(song) }) {
                        onPlay { playHomeRecommendation(section.id, song.id) }
                    }
                }
            }
        }
        item("sleep_timer") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .6f))) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Md3eIcons.Headphones, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("定时关闭", style = MaterialTheme.typography.titleMedium)
                        Text(if (timer.minutes > 0) "${timer.remaining / 60} 分钟后停止播放" else "伴着音乐，慢慢入眠", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { timer.minutes = if (timer.minutes > 0) 0 else 30 }, shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp)) { Text(if (timer.minutes > 0) "关闭" else "30 分钟", fontSize = 12.sp) }
                }
            }
        }
    }
    selectedVibe?.let { playlist ->
        DailyVibeDetails(runtime, playlist, { selectedVibe = null }, onLogin, runtime.controller::enqueueNeteaseSong) { songs, index ->
            onPlay { playAiDailySongs(songs, index) }
        }
    }
    collectPlaylist?.let { playlist ->
        AlertDialog(onDismissRequest = { collectPlaylist = null }, title = { Text("收藏歌单") },
            text = { Text("将「${playlist.name.orEmpty()}」收藏到网易云账号。") },
            confirmButton = { TextButton(onClick = { collectPlaylist = null; runtime.controller.collectPlaylist(playlist.id) }) { Text("收藏") } },
            dismissButton = { TextButton(onClick = { collectPlaylist = null }) { Text("取消") } })
    }
}

// Large outer corners and quiet inner seams make each recommendation section one group.
private val LocalSonicSongGroupShape = staticCompositionLocalOf<RoundedCornerShape?> { null }

@Composable
private fun SonicGroupedSongRow(index: Int, count: Int, motion: SongSwipeMotion, content: @Composable () -> Unit) {
    val reduced = Md3eLowSpecMode.current
    val distance = abs(index - motion.row)
    val following = motion.row >= 0 && distance in 1..3 && !reduced
    val attenuation = if (following) (if (index > motion.row) .22f else .10f) / distance else 0f
    val follow = animateFloatAsState(motion.displacement * attenuation,
        spring(dampingRatio = .8f, stiffness = 380f), label = "group swipe wave")
    val shape = RoundedCornerShape(
        topStart = if (index == 0) 22.dp else 3.dp,
        topEnd = if (index == 0) 22.dp else 3.dp,
        bottomStart = if (index == count - 1) 22.dp else 3.dp,
        bottomEnd = if (index == count - 1) 22.dp else 3.dp,
    )
    CompositionLocalProvider(LocalSongSwipeMotion provides motion, LocalSongSwipeIndex provides index,
        LocalSonicSongGroupShape provides shape) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(vertical = 1.dp)
            .graphicsLayer {
                translationX = follow.value
                // The lower rows lift gently into the pull and settle in sequence.
                translationY = -abs(follow.value) * .10f
            }) { content() }
    }
}

@Composable
private fun SonicRecentRow(song: NeteaseSong, onEnqueue: () -> Unit, onPlay: () -> Unit) {
    val groupShape = LocalSonicSongGroupShape.current
    Row(Modifier.fillMaxWidth().padding(horizontal = if (groupShape != null) 0.dp else 16.dp)
        .songQueueMenu(song.name.orEmpty(), onPlay, onEnqueue)
        .clip(groupShape ?: RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Artwork(song.coverThumbPath ?: song.coverUrl.orEmpty(), Modifier.size(46.dp), corner = 8.dp)
        Column(Modifier.weight(1f)) {
            Text(song.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (song.durationMs > 0) Text("${song.durationMs / 60000}:${(song.durationMs / 1000 % 60).toString().padStart(2, '0')}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onEnqueue, modifier = Modifier.size(32.dp)) { Icon(SonicIcons.Plus, "添加到播放队列", Modifier.size(18.dp)) }
    }
}

@Composable
private fun SonicHomeSearch(onSearch: () -> Unit, onRecognize: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onSearch),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Md3eIcons.Search, null, Modifier.size(22.dp))
                Text("搜索歌曲、歌手、专辑…", style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
            }
            IconButton(onClick = onRecognize, modifier = Modifier.size(36.dp)) { Icon(Md3eIcons.Headphones, "听歌识曲", Modifier.size(20.dp)) }
            Surface(onClick = onSearch, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Icon(SonicIcons.Tune, "搜索筛选", Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
internal fun SonicFeatureCard(title: String, curator: String, subtitle: String, cover: String, index: Int,
    tag: String, onOpen: () -> Unit, onPlay: () -> Unit, onLongClick: (() -> Unit)? = null, coverKey: String? = null,
    onSave: () -> Unit = onOpen, saved: Boolean = false) {
    val accent = rememberSonicCardAccent(cover)
    // Keep the scrim, labels and artwork in the same shared layer on return.
    Card(Modifier.width(280.dp).height(400.dp)
        .then(if (coverKey != null) Modifier.md3eSharedCover(coverKey) else Modifier)
        .shadow(2.dp, RoundedCornerShape(30.dp))
        .combinedClickable(onClick = onOpen, onLongClick = onLongClick), shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = accent.fill, contentColor = Color.White)) {
        Box(Modifier.fillMaxSize()) {
            Artwork(cover, Modifier.fillMaxSize(), corner = 0.dp, containerColor = accent.fill)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                Color.Black.copy(alpha = .04f), Color.Black.copy(alpha = .06f),
                Color.Black.copy(alpha = .66f), Color.Black.copy(alpha = .96f)))))
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                SonicTag(tag.uppercase(), accent.fill, accent.ink)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(curator, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .85f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .75f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    SonicPlaylistActions(accent, onPlay, onSave, saved = saved, saveEnabled = !saved)
                }
            }
        }
    }
}

@Composable
internal fun SonicTag(text: String, background: Color, ink: Color) {
    Surface(shape = CircleShape, color = background, contentColor = ink) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
    }
}

@Composable
internal fun SonicSongRow(song: NeteaseSong, number: Int?, onEnqueue: () -> Unit, onPlay: () -> Unit) {
    val groupShape = LocalSonicSongGroupShape.current
    Row(Modifier.fillMaxWidth().padding(horizontal = if (groupShape != null) 0.dp else 16.dp)
        .songQueueMenu(song.name.orEmpty(), onPlay, onEnqueue)
        .clip(groupShape ?: RoundedCornerShape(14.dp))
        .then(if (groupShape != null) Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow) else Modifier)
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (number != null) Text(number.toString().padStart(2, '0'), Modifier.width(24.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Artwork(song.coverThumbPath ?: song.coverUrl.orEmpty(), Modifier.size(48.dp), corner = 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(song.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onEnqueue, modifier = Modifier.size(36.dp)) { Icon(SonicIcons.Plus, "添加到播放队列", Modifier.size(18.dp)) }
    }
}

@Composable
internal fun SonicStatus(text: String, loading: Boolean = false, action: String = "", onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (!loading && action.isNotBlank()) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun SonicLibraryPage(state: PlaylistState, onLogin: () -> Unit, onRefresh: () -> Unit,
    onOpen: (Long) -> Unit, onLongClick: (NeteasePlaylist) -> Unit, onPlayPlaylist: (Long) -> Unit,
    onLocal: () -> Unit, onCreate: () -> Unit, modifier: Modifier) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val shown = remember(state.mine, filter) { state.mine.filter { when (filter) { 1 -> it.owned; 2 -> !it.owned; else -> true } } }
    val floatingDockInset = LocalMd3eFloatingDockInset.current
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 88.dp + LocalMd3eDockInset.current),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("chips") { SonicChips(listOf("全部歌单", "我创建的", "我收藏的", "已下载"), filter) {
                if (it == 3) onLocal() else filter = it
            } }
            if (!state.loggedIn) item("login") { SonicStatus("登录后同步你的网易云歌单", action = "登录网易云音乐", onAction = onLogin) }
            else {
                item("spotlight_title") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("精选歌单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp)); Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        Spacer(Modifier.weight(1f)); Text("滑动探索", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item("spotlights") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(shown.take(6), key = { _, p -> p.id }) { index, p ->
                            SonicLibrarySpotlight(p, index, { onOpen(p.id) }, { onLongClick(p) }, { onPlayPlaylist(p.id) })
                        }
                    }
                }
                item("shortcuts") {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SonicLibraryShortcut("我创建的", "${state.mine.count { it.owned }} 个歌单", SonicIcons.Heart,
                            Modifier.weight(1f)) { filter = 1 }
                        SonicLibraryShortcut("本地音乐", "下载与本地文件", SonicIcons.Download, Modifier.weight(1f), onLocal)
                    }
                }
                item("all_heading") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("全部歌单", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("${shown.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.size(36.dp)) { Icon(Md3eIcons.Refresh, "刷新歌单", Modifier.size(20.dp)) }
                    }
                }
                if (state.loading) item("loading") { SonicStatus("正在同步歌单…", true) }
                if (shown.isEmpty() && !state.loading) item("empty") { SonicStatus("这里还没有歌单", action = "新建歌单", onAction = onCreate) }
                items(shown, key = { "playlist:${it.id}" }) { p ->
                    Md3eCollectionContainer("cover:playlist:${p.id}", Modifier.padding(horizontal = 16.dp), corner = 14.dp) {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .45f))
                            .combinedClickable(onClick = { onOpen(p.id) }, onLongClick = { onLongClick(p) }).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Artwork(p.coverThumbPath ?: p.coverUrl.orEmpty(), Modifier.size(52.dp).md3eSharedCover("cover:playlist:${p.id}"), corner = 10.dp)
                            Column(Modifier.weight(1f)) {
                                Text(p.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${p.trackCount} 首 · ${p.creatorNickname.orEmpty()}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onPlayPlaylist(p.id) }, modifier = Modifier.size(36.dp)) { Icon(Md3eIcons.PlayArrow, "播放歌单", Modifier.size(22.dp)) }
                            IconButton(onClick = { onLongClick(p) }, modifier = Modifier.size(32.dp)) { Icon(SonicIcons.More, "歌单选项", Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }
        if (state.loggedIn) FloatingActionButton(onClick = onCreate, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 16.dp)
                .offset { androidx.compose.ui.unit.IntOffset(0, -floatingDockInset.value.roundToPx()) }) {
            Icon(SonicIcons.Plus, "新建歌单")
        }
    }
}

@Composable
private fun SonicLibrarySpotlight(p: NeteasePlaylist, index: Int, onOpen: () -> Unit, onLongClick: () -> Unit, onPlay: () -> Unit) {
    val lightCard = index % 2 == 1
    val ink = if (lightCard) MaterialTheme.colorScheme.onSurface else Color.White
    Card(Modifier.width(300.dp).height(310.dp).shadow(6.dp, RoundedCornerShape(28.dp))
        .combinedClickable(onClick = onOpen, onLongClick = onLongClick), shape = RoundedCornerShape(28.dp)) {
        Box(Modifier.fillMaxSize()) {
            Artwork(p.coverThumbPath ?: p.coverUrl.orEmpty(), Modifier.fillMaxSize(), corner = 28.dp)
            Box(Modifier.fillMaxSize().background(if (lightCard) MaterialTheme.colorScheme.surface.copy(alpha = .85f) else Color(0xD91D1B20)))
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SonicTag(if (p.owned) "YOUR COLLECTION" else "CURATED SPOTLIGHT", ink.copy(alpha = .15f), ink)
                    IconButton(onClick = onLongClick, modifier = Modifier.size(32.dp).background(ink.copy(alpha = .15f), CircleShape)) {
                        Icon(SonicIcons.Bookmark, "歌单选项", Modifier.size(18.dp), tint = ink)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.name.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(p.description.orEmpty().ifBlank { "推荐歌单" }, style = MaterialTheme.typography.bodySmall,
                        color = ink.copy(alpha = .75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.trackCount} 首 · ${p.creatorNickname.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = .9f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onPlay, modifier = Modifier.weight(1f).height(44.dp), shape = CircleShape) {
                            Icon(Md3eIcons.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("播放")
                        }
                        IconButton(onClick = onOpen, modifier = Modifier.size(44.dp).background(ink.copy(alpha = .15f), CircleShape)) {
                            Icon(Md3eIcons.ChevronRight, "查看歌单", tint = ink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SonicLibraryShortcut(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .5f)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun SonicPlaylistDetail(state: PlaylistState, id: Long, onRefresh: () -> Unit, onPlay: PlayAction,
    onEnqueue: (NeteaseSong) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp + LocalMd3eDockInset.current),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item("hero") {
            SonicCollectionHero(state.title.ifBlank { "正在加载歌单" }, state.cover, "cover:playlist:$id",
                "CURATED COLLECTION", "${state.tracks.size} 首歌曲 · 网易云音乐", state.tracks.isNotEmpty(),
                onPlay = { onPlay { playPlaylistTrack(0) } }) {
                    FilledTonalIconButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.size(48.dp)) { Icon(Md3eIcons.Refresh, "刷新歌单") }
                    if (!state.owned) FilledTonalIconButton(onClick = { onPlay { togglePlaylistSubscribe() } }, modifier = Modifier.size(48.dp)) {
                        Icon(if (state.subscribed) Md3eIcons.CheckCircle else SonicIcons.Bookmark, if (state.subscribed) "取消收藏" else "收藏歌单")
                    }
            }
        }
        item("tracks_title") { SonicSectionTitle("Tracklist", "按住歌曲可添加到播放队列") }
        if (state.loading) item("loading") { SonicStatus("正在加载歌曲…", true) }
        if (!state.loading && state.tracks.isEmpty()) item("empty") { SonicStatus("歌单中还没有歌曲") }
        itemsIndexed(state.tracks, key = { i, s -> "$i:${s.id}" }) { index, song ->
            SonicSongRow(song, index + 1, { onEnqueue(song) }) { onPlay { playPlaylistTrack(index) } }
        }
    }
}

/** Album and playlist details deliberately share one hero and track-row layout. */
@Composable
private fun SonicCollectionHero(title: String, cover: String, coverKey: String, tag: String,
    subtitle: String, enabled: Boolean, onPlay: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SonicTag(tag, colors.surfaceContainerHigh, colors.onSurfaceVariant)
        Artwork(cover, Modifier.widthIn(max = 360.dp).fillMaxWidth(.84f).aspectRatio(1f)
            .shadow(12.dp, RoundedCornerShape(28.dp)).md3eSharedCover(coverKey), corner = 28.dp)
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPlay, enabled = enabled, shape = CircleShape, modifier = Modifier.height(48.dp)) {
                Icon(Md3eIcons.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("播放全部")
            }
            actions()
        }
    }
}

@Composable
internal fun SonicAlbumDetail(state: AlbumDetailState, id: Long, onPlay: PlayAction,
    onEnqueue: (NeteaseSong) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp + LocalMd3eDockInset.current),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item("hero") {
            SonicCollectionHero(state.name.ifBlank { "正在加载专辑" }, state.cover, "cover:album:$id",
                "ALBUM COLLECTION", listOf(state.artistName, "${state.tracks.size} 首歌曲 · 网易云音乐")
                    .filter { it.isNotBlank() }.joinToString(" · "), state.tracks.isNotEmpty(),
                onPlay = { onPlay { playAlbumTrack(0) } })
        }
        item("tracks_title") { SonicSectionTitle("Tracklist", "按住歌曲可添加到播放队列") }
        if (state.loading) item("loading") { SonicStatus("正在加载歌曲…", true) }
        if (!state.loading && state.tracks.isEmpty()) item("empty") { SonicStatus("专辑中还没有歌曲") }
        itemsIndexed(state.tracks, key = { i, s -> "$i:${s.id}" }) { index, song ->
            SonicSongRow(song, index + 1, { onEnqueue(song) }) { onPlay { playAlbumTrack(index) } }
        }
    }
}

@Composable
internal fun SonicProfilePage(runtime: Md3eRuntime, onLogin: () -> Unit, onBiliLogin: () -> Unit,
    onLibrary: () -> Unit, onLocal: () -> Unit, onQueue: () -> Unit, onListening: () -> Unit, modifier: Modifier) {
    val categories = remember { runtime.settings.categories().filter { runtime.settings.rows(it).any(::supportedSetting) } }
    var category by rememberSaveable { mutableStateOf(SettingsCatalog.APPEARANCE) }
    val revision = runtime.settingsRevision
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp + LocalMd3eDockInset.current),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("profile") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(106.dp), contentAlignment = Alignment.Center) {
                    Surface(onClick = onLogin, modifier = Modifier.size(96.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Box(contentAlignment = Alignment.Center) { Icon(Md3eIcons.Person, "网易云账号", Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary) }
                    }
                    Surface(onClick = onLogin, shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp).align(Alignment.BottomEnd)) {
                        Box(contentAlignment = Alignment.Center) { Icon(if (runtime.home.loggedIn) Md3eIcons.CheckCircle else SonicIcons.Plus, "登录与账号", Modifier.size(16.dp)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(runtime.home.userName.ifBlank { "我的" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (runtime.home.loggedIn) "与网易云音乐账号同步" else "登录查看收藏", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                Surface(onClick = onLogin, color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                    Text(if (runtime.home.loggedIn) "网易云音乐 · 已连接" else "连接网易云音乐", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(20.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .6f)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        SonicStat("${runtime.playlists.mine.size}", "收藏歌单", onLibrary)
                        SonicStat("${runtime.local.tracks.size}", "本地歌曲", onLocal)
                        SonicStat("${runtime.queue.size}", "队列歌曲", onQueue)
                    }
                }
            }
        }
        item("accounts") {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SonicLibraryShortcut("我的歌单", "创建与收藏", Md3eIcons.Library, Modifier.weight(1f), onLibrary)
                SonicLibraryShortcut("B站账号", if (runtime.bili.loggedIn) "已连接" else "登录与收藏", Md3eIcons.Video, Modifier.weight(1f), onBiliLogin)
            }
        }
        item("listening") {
            SonicLibraryShortcut("听歌足迹", "收听时长 · 每日图表 · 循环排行", Md3eIcons.MusicNote,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp), onListening)
        }
        item("settings_title") { SonicSectionTitle("设置", "外观、播放与账号") }
        item("settings_categories") { SonicChips(categories, categories.indexOf(category).coerceAtLeast(0)) { category = categories[it] } }
        if (category == SettingsCatalog.APPEARANCE) item("design") {
            Box(Modifier.padding(horizontal = 16.dp)) { Md3eDesignModeSelector(runtime) }
        }
        items(runtime.settings.rows(category).filter { supportedSetting(it) && it.key !in setOf("claudeDesign", "iosDesign", "sonicDesign") }, key = { "setting:${it.key}" }) { spec ->
            Box(Modifier.padding(horizontal = 16.dp)) { Md3eSettingRow(spec, runtime, revision) }
        }
    }
}

@Composable
private fun SonicStat(value: String, label: String, onClick: () -> Unit = {}) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SonicExploreRecommendations(runtime: Md3eRuntime, onOpenPlaylist: (Long) -> Unit, onPlay: PlayAction) {
    val playlists = remember(runtime.home.playlists, runtime.home.playlistSections) {
        (runtime.home.playlists + runtime.home.playlistSections.flatMap { it.playlists }).distinctBy { it.id }.take(6)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (runtime.sonicGenres.state.selected.isNotEmpty()) {
            SonicExploreGenres(runtime.sonicGenres, onOpenPlaylist)
            return@Column
        }
        if (playlists.isNotEmpty()) {
            SonicSectionTitle("Curated Weekly", "每周推荐")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(playlists, key = { _, p -> p.id }) { index, p ->
                    val color = MaterialTheme.colorScheme.surfaceContainerHigh
                    val ink = MaterialTheme.colorScheme.onSurface
                    val button = MaterialTheme.colorScheme.primaryContainer
                    val buttonInk = MaterialTheme.colorScheme.onPrimaryContainer
                    Md3eCollectionContainer("cover:playlist:${p.id}", corner = 28.dp) {
                    Card(Modifier.width(300.dp).height(200.dp).shadow(4.dp, RoundedCornerShape(28.dp)).clickable { onOpenPlaylist(p.id) },
                        shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = color)) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.size(170.dp).align(Alignment.BottomEnd).offset(30.dp, 30.dp).background(Color.White.copy(alpha = .1f), CircleShape),
                                contentAlignment = Alignment.Center) {
                                Artwork(p.coverThumbPath ?: p.coverUrl.orEmpty(), Modifier.size(110.dp)
                                    .md3eSharedCover("cover:playlist:${p.id}"), corner = 55.dp)
                            }
                            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SonicTag("WEEKLY SELECTION", ink.copy(alpha = .15f), ink)
                                    Text(p.name.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                        color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${p.trackCount} 首 · 精选歌单", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = ink)
                                    FilledIconButton(onClick = { onPlay { playPlaylist(p.id) } }, modifier = Modifier.size(48.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = button, contentColor = buttonInk)) {
                                        Icon(Md3eIcons.PlayArrow, "播放歌单", Modifier.size(26.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        val artists = remember(runtime.home.daily, runtime.home.sections) {
            (runtime.home.daily + runtime.home.sections.flatMap { it.songs }).filter { it.artistId > 0 }.distinctBy { it.artistId }.take(10)
        }
        if (artists.isNotEmpty()) {
            SonicSectionTitle("Artist Spotlight", "熟悉的声音，也有新的发现")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(artists, key = { it.artistId }) { song ->
                    Column(Modifier.width(84.dp).clickable { runtime.openArtist(song.artistId) }, horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(76.dp).background(MaterialTheme.colorScheme.secondaryContainer,
                            dev.t1m3.qplayer.android.md3eui.sonic.ScallopedCloudShape()), contentAlignment = Alignment.Center) {
                            // Song covers are not artist portraits; use the reference's organic monogram instead.
                            Text(song.artist.orEmpty().take(1), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(song.artist.orEmpty(), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        SonicExploreGenres(runtime.sonicGenres, onOpenPlaylist)
    }
}
