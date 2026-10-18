@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.t1m3.qplayer.android.md3eui

import dev.t1m3.qplayer.android.md3eui.Md3eAdaptiveButton as Button

import dev.t1m3.qplayer.android.ui.IosAwareAlertDialog as AlertDialog
import dev.t1m3.qplayer.android.ui.IosAwareIconButton as IconButton
import dev.t1m3.qplayer.android.ui.IosAwareTextButton as TextButton
import dev.t1m3.qplayer.android.ui.IosAwareSlider as Slider

import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.t1m3.qplayer.netease.dto.NeteaseAlbum
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist
import dev.t1m3.qplayer.netease.dto.NeteaseSong
import dev.t1m3.qplayer.ai.DailyVibeCuration
import kotlinx.coroutines.delay

private const val HOME_HEAD_SHELF = 6
private const val HOME_SHELF_CHUNK = 12
private const val HOME_ALBUM_CHUNK = 12
private const val HOME_PLAYLIST_CHUNK = 10

@Composable
internal fun Md3eMainTopBar(tab: String, detail: String, title: String, loggedIn: Boolean,
    biliLoggedIn: Boolean, onBack: () -> Unit, onQueue: () -> Unit, onSettings: () -> Unit,
    onAccount: () -> Unit, onBiliAccount: () -> Unit) {
    val displayTitle = when (detail) {
        "playlist" -> title.ifBlank { "歌单" }
        "album" -> title.ifBlank { "专辑" }
        "artist" -> title.ifBlank { "歌手" }
        "settings" -> "设置"
        "biliFolders" -> "B站收藏夹"
        "biliFolder" -> title.ifBlank { "B站收藏夹" }
        else -> when (tab) {
            "home" -> "推荐"
            "playlists" -> "我的"
            "local" -> "本地"
            "search" -> "搜索"
            "settings" -> "设置"
            else -> "QPlayer"
        }
    }
    TopAppBar(title = {
        Text(displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        navigationIcon = {
            if (detail.isNotEmpty() || tab == "settings") IconButton(onClick = onBack) {
                Icon(Md3eIcons.Back, "返回")
            }
        },
        actions = {
            IconButton(onClick = onQueue) { Icon(Md3eIcons.Playlist, "播放队列") }
            IconButton(onClick = onSettings) { Icon(Md3eIcons.Settings, "设置") }
            IconButton(onClick = onAccount) {
                Icon(Md3eIcons.Person, if (loggedIn) "账户" else "登录")
            }
            IconButton(onClick = onBiliAccount) {
                Icon(Md3eIcons.Video, if (biliLoggedIn) "B站账户" else "登录 B站")
            }
        })
}

@Composable
internal fun Md3eHomePage(runtime: Md3eRuntime, onLogin: () -> Unit, onPlay: PlayAction,
    onOpenPlaylist: (Long) -> Unit, onOpenAlbum: (Long) -> Unit, onRefresh: () -> Unit,
    modifier: Modifier = Modifier) {
    val state = runtime.home
    var selectedVibe by remember { mutableStateOf<DailyVibeCuration.Playlist?>(null) }
    LaunchedEffect(runtime.settingsRevision, state.loggedIn, state.userName, runtime.uiVisible) {
        while (true) {
            if (runtime.uiVisible) runtime.dailyVibes.ensure()
            delay(60_000)
        }
    }
    val dailyPhrase by rememberMd3eDailyPhrase(runtime)
    val dockInset = LocalMd3eDockInset.current
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // Seal DownloadPageV2 uses a 28 dp compact header spacer.
    val compactWindow = LocalConfiguration.current.screenWidthDp < 600
    val claude = LocalClaudeDesign.current
    val ios = LocalIosDesign.current
    val iosTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp
    val headerHeight = with(density) { when {
        ios -> 0f
        compactWindow && !claude -> 28.dp.toPx()
        else -> 0f
    } }
    var headerOffset by remember(headerHeight) { mutableFloatStateOf(headerHeight) }
    val flingSpec = rememberSplineBasedDecay<Float>()
    val headerScroll = remember(headerHeight, flingSpec) {
        Md3eSealTopBarScroll(headerHeight, flingSpec,
            offset = { headerOffset }, onOffsetUpdate = { headerOffset = it })
    }
    val firstPlaylists = remember(state.playlists, state.playlistSections) {
        state.playlists.ifEmpty { state.playlistSections.firstOrNull()?.playlists.orEmpty() }
    }
    val morePlaylists = remember(state.playlistSections, firstPlaylists) {
        val seen = firstPlaylists.mapTo(mutableSetOf()) { it.id }
        state.playlistSections.mapNotNull { section ->
            section.playlists.filter { seen.add(it.id) }.takeIf { it.isNotEmpty() }?.let { section.title to it }
        }
    }
    val albums = remember(state.albums, state.daily) {
        state.albums.ifEmpty {
            state.daily.asSequence().filter { it.albumId > 0 && !it.album.isNullOrBlank() }
                .distinctBy { it.albumId }.take(12).map { song ->
                    NeteaseAlbum().apply {
                        id = song.albumId; name = song.album; artistName = song.artist
                        coverUrl = song.coverUrl; coverThumbPath = song.coverThumbPath
                    }
                }.toList()
        }
    }
    val songShelves = remember(state.sections) {
        state.sections.flatMap { section ->
            section.songs.chunked(HOME_SHELF_CHUNK).mapIndexed { index, songs -> Triple(section, index, songs) }
        }
    }
    val dailyShelves = remember(state.daily) { state.daily.chunked(HOME_SHELF_CHUNK) }
    val albumShelves = remember(albums) { albums.chunked(HOME_ALBUM_CHUNK) }
    val playlistHead = remember(firstPlaylists) { firstPlaylists.take(HOME_HEAD_SHELF) }
    val playlistTail = remember(firstPlaylists) { firstPlaylists.drop(HOME_HEAD_SHELF).chunked(HOME_PLAYLIST_CHUNK) }
    val privateRoam = LocalClaudePrivateRoam.current
    Column(modifier.nestedScroll(headerScroll)) {
        Md3eHomeHeaderSpacer(offsetProvider = { headerOffset }, headerHeight = headerHeight, divider = !ios)
        LazyColumn(Modifier.weight(1f), state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = if (ios) iosTopInset + 16.dp else 16.dp,
            end = 16.dp, bottom = 16.dp + dockInset),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (claude) item(key = "opening") {
            ClaudeOpening(state.userName, dailyPhrase, aiInput = { ClaudeAiDjInput(runtime) },
                onPrivateFm = { if (state.loggedIn) privateRoam?.open() else onLogin() })
        }
        if (!claude) item(key = "greeting") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.userName.isBlank()) "你好" else "你好，${state.userName}",
                fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                Text(dailyPhrase, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!claude) item(key = "ai") {
            ClaudeAiDjInput(runtime)
        }
        if (runtime.dailyVibes.state.configured) item(key = "daily_ai_vibes") {
            DailyVibeShelf(runtime) { selectedVibe = it }
        }
        if (firstPlaylists.isEmpty() && state.loading) item(key = "playlist_loading") {
            Column(Modifier.claudeEntrance(2), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeTitle("推荐歌单")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (firstPlaylists.isNotEmpty()) item(key = "playlists") {
            HomePlaylistShelf("推荐歌单", playlistHead, onOpenPlaylist)
        }
        dailyShelves.forEachIndexed { shelf, songs ->
            item(key = "daily_$shelf") {
                HomeSongPager(if (shelf == 0) "每日推荐" else "每日推荐 · ${shelf + 1}", songs,
                    { index -> onPlay { playRecommendation(shelf * HOME_SHELF_CHUNK + index) } },
                    { runtime.controller.enqueueNeteaseSong(it) })
            }
        }
        albumShelves.forEachIndexed { shelf, chunk ->
            item(key = "albums_$shelf") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeTitle(if (shelf == 0) "推荐新碟" else "更多新碟")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(chunk, key = { it.id }) { album -> HomeAlbumCard(album) { onOpenAlbum(album.id) } }
                    }
                }
            }
        }
        items(songShelves, key = { "shelf_${it.first.id}_${it.second}" }) { (section, shelf, songs) ->
            HomeSongPager(if (shelf == 0) section.title else "${section.title} · ${shelf + 1}", songs,
                { index -> songs.getOrNull(index)?.let { song -> onPlay { playHomeRecommendation(section.id, song.id) } } },
                { runtime.controller.enqueueNeteaseSong(it) })
        }
        items(morePlaylists, key = { "playlist_section_${it.first}" }) { (title, playlists) ->
            HomePlaylistShelf(title, playlists, onOpenPlaylist)
        }
        playlistTail.forEachIndexed { shelf, chunk ->
            item(key = "playlist_tail_$shelf") {
                HomePlaylistShelf(if (shelf == 0) "更多歌单" else "更多歌单 · ${shelf + 1}", chunk, onOpenPlaylist)
            }
        }
        if (!state.loading && firstPlaylists.isEmpty() && state.daily.isEmpty() &&
            state.sections.isEmpty() && albums.isEmpty()) item(key = "empty") {
            TextButton(onClick = onRefresh) { Text(state.error.ifBlank { "暂无推荐内容，点击刷新" }) }
        }
        if (state.loading && firstPlaylists.isNotEmpty()) item(key = "loading_more") {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (!state.loading && state.error.isNotBlank()) item(key = "retry") {
            TextButton(onClick = onRefresh) { Text(state.error) }
        }
        if (!state.loggedIn && state.loginError.isNotBlank()) item(key = "login") {
            TextButton(onClick = onLogin) { Text(state.loginError) }
        }
    }
    }
    selectedVibe?.let { playlist ->
        DailyVibeDetails(runtime, playlist, onDismiss = { selectedVibe = null }, onLogin = onLogin,
            onEnqueue = runtime.controller::enqueueNeteaseSong) { songs, index ->
            selectedVibe = null
            onPlay { playAiDailySongs(songs, index) }
        }
    }
}

@Composable
private fun HomeTitle(title: String, count: String? = null) {
    if (LocalClaudeDesign.current) ClaudeShelfHeader(title, count)
    else Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun HomePlaylistShelf(title: String, playlists: List<NeteasePlaylist>, onOpen: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeTitle(title, "${playlists.size} 个")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                if (LocalClaudeDesign.current) ClaudePlaylistCard(playlist) { onOpen(playlist.id) }
                else Md3eCollectionContainer("cover:playlist:${playlist.id}", corner = 16.dp) {
Column(Modifier.width(164.dp).clip(RoundedCornerShape(16.dp))
                    .clickable { onOpen(playlist.id) }) {
                    Artwork(playlist.coverThumbPath ?: playlist.coverUrl.orEmpty(),
                        Modifier.fillMaxWidth().aspectRatio(1f).md3eSharedCover("cover:playlist:${playlist.id}"))
                    Text(playlist.name ?: "未命名歌单", Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp),
                        maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text("${playlist.trackCount} 首歌曲", Modifier.padding(10.dp), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
    }
            }
        }
    }
}

@Composable
private fun HomeAlbumCard(album: NeteaseAlbum, onOpen: () -> Unit) {
    if (LocalClaudeDesign.current) { ClaudeAlbumCover(album, onOpen); return }
    Md3eCollectionContainer("cover:album:${album.id}", corner = 22.dp) {
Column(Modifier.width(132.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Artwork(album.coverThumbPath ?: album.coverUrl.orEmpty(),
            Modifier.fillMaxWidth().aspectRatio(1f).md3eSharedCover("cover:album:${album.id}"),
            corner = 24.dp)
        Text(album.name ?: "未知专辑", maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(album.artistName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }
}

@Composable
private fun HomeSongPager(title: String, songs: List<NeteaseSong>, onPlay: (Int) -> Unit,
    onEnqueue: (NeteaseSong) -> Unit) {
    val claude = LocalClaudeDesign.current
    if (claude) { ClaudeSongList(title, songs, onPlay, onEnqueue); return }
    if (songs.isEmpty()) return
    val pageCount = (songs.size + 2) / 3
    val pager = rememberPagerState(pageCount = { pageCount })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { HomeTitle(title) }
            if (pageCount > 1) Text("${pager.currentPage + 1} / $pageCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalPager(pager, modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(end = if (pageCount > 1) 16.dp else 0.dp),
            pageSpacing = 12.dp, key = { page -> "${songs.getOrNull(page * 3)?.id}_$page" }) { page ->
            Surface(color = Color.Transparent) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { slot ->
                    val index = page * 3 + slot
                    val song = songs.getOrNull(index)
                    if (song == null) Spacer(Modifier.height(64.dp)) else {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .songQueueMenu(song.name.orEmpty(), { onPlay(index) }, { onEnqueue(song) })
                            .heightIn(min = 64.dp).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Artwork(song.coverThumbPath ?: song.coverUrl.orEmpty(), Modifier.size(48.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.name ?: "未知歌曲", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist ?: "未知歌手", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun Md3eHomeHeaderSpacer(offsetProvider: () -> Float, headerHeight: Float, divider: Boolean = true) {
    if (headerHeight <= 0f) return
    Column {
        Spacer(Modifier.layout { measurable, constraints ->
            val height = offsetProvider().toInt().coerceAtLeast(0)
            val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        })
        val collapsed by remember(headerHeight) { derivedStateOf { offsetProvider() <= 0.1f } }
        if (collapsed && divider) HorizontalDivider(thickness = Dp.Hairline)
    }
}
