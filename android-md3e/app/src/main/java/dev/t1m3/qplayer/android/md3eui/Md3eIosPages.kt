@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
// Adapted from MusicOne PlaylistIntroduction, MusicTrackRow and ArtistIntroduction.
// MIT, Copyright (c) 2026 ShuyunR; assets/licenses/musicone-LICENSE.txt.
package dev.t1m3.qplayer.android.md3eui

import dev.t1m3.qplayer.android.md3eui.Md3eAdaptiveMenu as DropdownMenu
import dev.t1m3.qplayer.android.md3eui.Md3eAdaptiveButton as Button
import dev.t1m3.qplayer.android.md3eui.Md3eAdaptiveFilledIconButton as FilledIconButton

import dev.t1m3.qplayer.android.ui.IosAwareAlertDialog as AlertDialog
import dev.t1m3.qplayer.android.ui.IosAwareIconButton as IconButton
import dev.t1m3.qplayer.android.ui.IosAwareTextButton as TextButton
import dev.t1m3.qplayer.android.ui.IosNeutralButton

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.t1m3.qplayer.netease.dto.NeteaseSong
import kotlinx.coroutines.launch

@Composable
internal fun IosPlaylistsPage(state: PlaylistState, onLogin: () -> Unit, onRefresh: () -> Unit,
    onOpen: (Long) -> Unit, onLongClick: (dev.t1m3.qplayer.netease.dto.NeteasePlaylist) -> Unit, modifier: Modifier) {
    var category by rememberSaveable { mutableIntStateOf(0) }
    val playlists = state.mine.filter { if (category == 0) it.owned else !it.owned }
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp,
        iosPageTopPadding() + 20.dp,
        20.dp, LocalMd3eDockInset.current + 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("资料库", fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("我创建的", "我收藏的").forEachIndexed { index, title ->
                    FilterChip(category == index, { category = index }, label = { Text(title) }, shape = CircleShape)
                }
            }
        }
        if (!state.loggedIn) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("登录后查看你的音乐资料库", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onLogin) { Text("登录网易云音乐") }
            }
        } else if (playlists.isEmpty()) item { Text("还没有歌单", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(playlists.chunked(2), key = { it.first().id }) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                pair.forEach { playlist ->
                    Md3eCollectionContainer("cover:playlist:${playlist.id}", Modifier.weight(1f), corner = 16.dp) {
                        Column(Modifier.combinedClickable(onClick = { onOpen(playlist.id) },
                            onLongClick = { onLongClick(playlist) }, onLongClickLabel = "歌单选项")) {
                            Artwork(playlist.coverThumbPath ?: playlist.coverUrl.orEmpty(),
                                Modifier.fillMaxWidth().aspectRatio(1f).md3eSharedCover("cover:playlist:${playlist.id}"), corner = 16.dp)
                            Text(playlist.name.orEmpty(), Modifier.padding(top = 9.dp), fontSize = 16.sp,
                                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${playlist.trackCount} 首歌曲", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun IosPlaylistDetail(state: PlaylistState, id: Long, onRefresh: () -> Unit,
    onPlay: PlayAction, onEnqueue: (NeteaseSong) -> Unit, modifier: Modifier) {
    var query by rememberSaveable(id) { mutableStateOf("") }
    var sort by rememberSaveable(id) { mutableIntStateOf(0) }
    var subscribePrompt by rememberSaveable(id) { mutableStateOf(false) }
    val requestSubscribe = { subscribePrompt = true }
    val indexed = remember(state.tracks, query, sort) {
        val matching = state.tracks.withIndex().filter {
            query.isBlank() || it.value.name.orEmpty().contains(query, true) || it.value.artist.orEmpty().contains(query, true)
        }
        when (sort) { 1 -> matching.sortedBy { it.value.name.orEmpty() }; 2 -> matching.reversed(); else -> matching }
    }
    val scroll = rememberLazyListState()
    IosArtworkTheme(state.cover) { page, accent, _ ->
    Box(modifier.background(page)) {
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalMd3eDockInset.current + 88.dp)) {
            item("hero") {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).combinedClickable(
                    onClick = {}, onLongClickLabel = "收藏歌单", onLongClick = requestSubscribe)) {
                    Artwork(state.cover, Modifier.fillMaxSize().md3eSharedCover("cover:playlist:$id"), corner = 0.dp)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to Color.Transparent, .65f to page.copy(alpha = 0f), 1f to page)))
                }
            }
            item("introduction") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.title.ifBlank { "正在加载歌单" },
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = requestSubscribe,
                            onLongClickLabel = "收藏歌单"),
                        color = accent, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    Text("${state.tracks.size} 首歌曲", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.widthIn(max = 380.dp).fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton(onClick = {
                            if (state.tracks.isNotEmpty()) onPlay { setPlayMode(1); playPlaylistTrack(state.tracks.indices.random()) }
                        }, enabled = state.tracks.isNotEmpty(), modifier = Modifier.size(48.dp)) { Icon(PlayerIcons.Shuffle, "随机播放") }
                        IosNeutralButton(onClick = { indexed.firstOrNull()?.let { first -> onPlay { setPlayMode(0); playPlaylistTrack(first.index) } } },
                            enabled = indexed.isNotEmpty(), modifier = Modifier.weight(1f).height(48.dp), contentColor = page) {
                            Icon(Md3eIcons.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("播放", fontWeight = FontWeight.Bold)
                        }
                        var sorting by remember { mutableStateOf(false) }
                        Box {
                            FilledIconButton(onClick = { sorting = true }, Modifier.size(48.dp)) { Icon(Md3eIcons.Library, "排序") }
                            DropdownMenu(sorting, { sorting = false }) {
                                listOf("原始顺序", "按歌名", "倒序").forEachIndexed { index, label ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { sort = index; sorting = false })
                                }
                            }
                        }
                    }
                }
            }
            stickyHeader("search") {
                OutlinedTextField(query, { query = it }, placeholder = { Text("在歌单中搜索") }, singleLine = true,
                    leadingIcon = { Icon(Md3eIcons.Search, null) }, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().background(page).padding(horizontal = 20.dp, vertical = 8.dp))
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) }
            if (!state.loading && indexed.isEmpty()) item {
                Text(if (query.isBlank()) "歌单中还没有歌曲" else "没有匹配的歌曲", Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(indexed, key = { "${it.index}-${it.value.id}" }) { (originalIndex, song) ->
                IosTrackRow(song, { onPlay { playPlaylistTrack(originalIndex) } }, { onEnqueue(song) })
            }
        }
        if (subscribePrompt) AlertDialog(onDismissRequest = { subscribePrompt = false },
            title = { Text(when { state.owned -> "我的歌单"; state.subscribed -> "取消收藏歌单？"; else -> "收藏歌单？" }) },
            text = { Text(when {
                state.owned -> "「${state.title}」已在你创建的歌单中。"
                !state.loggedIn -> "请先通过右上角账户按钮登录网易云，再收藏「${state.title}」。"
                state.subscribed -> "从你的收藏中移除「${state.title}」？原歌单会保留。"
                else -> "将「${state.title}」添加到网易云账号的收藏歌单？"
            }) },
            confirmButton = { TextButton(onClick = {
                subscribePrompt = false
                if (state.loggedIn && !state.owned) onPlay { togglePlaylistSubscribe() }
            }) { Text(if (state.owned || !state.loggedIn) "知道了" else if (state.subscribed) "取消收藏" else "收藏") } },
            dismissButton = { TextButton(onClick = { subscribePrompt = false }) { Text("关闭") } })
    }
    }
}

@Composable
internal fun IosTrackRow(song: NeteaseSong, onPlay: () -> Unit, onEnqueue: () -> Unit) {
    Row(Modifier.fillMaxWidth().songQueueMenu(song.name.orEmpty(), onPlay, onEnqueue)
        .padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(song.coverThumbPath ?: song.coverUrl.orEmpty(), Modifier.size(64.dp), corner = 16.dp)
        Column(Modifier.weight(1f).padding(horizontal = 13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(song.name.orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(song.artist.orEmpty(), song.album.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("网易云音乐", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onEnqueue) { Icon(Md3eIcons.Playlist, "添加到播放队列", Modifier.size(20.dp)) }
    }
}

@Composable
internal fun IosArtistDetail(state: ArtistDetailState, onAlbum: (Long) -> Unit,
    onPlay: PlayAction, onEnqueue: (NeteaseSong) -> Unit, modifier: Modifier) {
    var tab by rememberSaveable(state.id) { mutableIntStateOf(0) }
    var biography by remember { mutableStateOf(false) }
    IosArtworkTheme(state.cover) { shade, accent, colors ->
        LazyColumn(modifier.background(shade), contentPadding = PaddingValues(
            bottom = LocalMd3eDockInset.current + 24.dp)) {
            item("artist-hero") {
                Box(Modifier.fillMaxWidth().height(480.dp)) {
                    Artwork(state.cover, Modifier.fillMaxSize(), corner = 0.dp)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to Color.Transparent, .25f to shade.copy(alpha = 0f), .55f to shade.copy(alpha = .6f), .85f to shade, 1f to shade)))
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(240.dp).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
                            Text(state.name.ifBlank { "正在加载歌手" }, color = accent, fontSize = 30.sp, lineHeight = 36.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.fillMaxWidth().height(74.dp), contentAlignment = Alignment.Center) {
                            if (state.description.isNotBlank()) Column(Modifier.clickable { biography = true },
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(state.description, color = Color.White.copy(alpha = .86f), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, fontSize = 12.sp, textAlign = TextAlign.Center)
                                Text("简介 ›", color = accent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        IosNeutralButton(onClick = { onPlay { playArtistSong(0) } }, enabled = state.songs.isNotEmpty(),
                            modifier = Modifier.width(132.dp).height(52.dp), contentColor = shade) {
                            Icon(Md3eIcons.PlayArrow, "播放歌手歌曲", Modifier.size(28.dp))
                        }
                    }
                }
            }
            stickyHeader("artist-tabs") {
                TabRow(tab, containerColor = shade, contentColor = accent) {
                    listOf("歌曲 ${state.songs.size}", "专辑 ${state.albums.size}").forEachIndexed { index, label ->
                        Tab(tab == index, onClick = { tab = index }, text = { Text(label) })
                    }
                }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (tab == 0) itemsIndexed(state.songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
                IosTrackRow(song, { onPlay { playArtistSong(index) } }, { onEnqueue(song) })
            } else items(state.albums, key = { it.id }) { album ->
                Row(Modifier.fillMaxWidth().clickable { onAlbum(album.id) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Artwork(album.coverThumbPath ?: album.coverUrl.orEmpty(), Modifier.size(80.dp), corner = 12.dp)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(album.name.orEmpty(), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(album.artistName.orEmpty(), fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                }
            }
            if (!state.loading && (if (tab == 0) state.songs.isEmpty() else state.albums.isEmpty())) item {
                Text("暂无内容", Modifier.padding(24.dp), color = colors.onSurfaceVariant)
            }
        }
        if (biography) AlertDialog(onDismissRequest = { biography = false }, title = { Text("${state.name} · 简介") },
            // The dialog already owns vertical scrolling; nesting an unbounded
            // LazyColumn inside it throws during measurement on opening.
            text = { Text(state.description, fontSize = 15.sp, lineHeight = 23.sp) },
            confirmButton = { TextButton(onClick = { biography = false }) { Text("关闭") } })
    }
}

/** The same artwork palette for both collection pages, including all control roles. */
@Composable
private fun IosArtworkTheme(source: String, content: @Composable (Color, Color, ColorScheme) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cover = remember(source) { artworkRequestSource(source) }
    val appearance by key(cover) { produceState(Color(0xFF283431) to Color(0xFFD9ECE4), cover) {
        awaitArtworkBitmap(context, cover)?.let { bitmap ->
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { iosArtistColors(bitmap) }
        }
    } }
    val (shade, accent) = appearance
    val ink = androidx.compose.ui.graphics.lerp(accent, Color.White, .75f)
    val colors = darkColorScheme(primary = accent, onPrimary = shade,
        secondary = accent, onSecondary = shade, secondaryContainer = shade,
        onSecondaryContainer = accent, background = shade, surface = shade,
        surfaceContainer = androidx.compose.ui.graphics.lerp(shade, accent, .08f),
        surfaceContainerHigh = androidx.compose.ui.graphics.lerp(shade, accent, .12f),
        onSurface = ink, onBackground = ink, onSurfaceVariant = androidx.compose.ui.graphics.lerp(accent, ink, .4f),
        outline = accent.copy(alpha = .5f))
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface,
            LocalTextStyle provides LocalTextStyle.current.copy(color = colors.onSurface),
            dev.t1m3.qplayer.android.ui.LocalGlassDark provides true,
            dev.t1m3.qplayer.android.ui.LocalGlassTint provides shade,
            dev.t1m3.qplayer.android.ui.LocalGlassLuminance provides shade.luminance()) {
            content(shade, accent, colors)
        }
    }
}

/** MusicOne's dominant-hue artist treatment, sampled off the main thread. */
private fun iosArtistColors(bitmap: android.graphics.Bitmap): Pair<Color, Color> {
    val buckets = HashMap<Int, Pair<Float, Int>>()
    val hsv = FloatArray(3)
    for (y in 0 until 24) for (x in 0 until 24) {
        val pixel = bitmap.getPixel(x * bitmap.width / 24, y * bitmap.height / 24)
        android.graphics.Color.colorToHSV(pixel, hsv)
        if (hsv[2] < .08f || hsv[2] > .96f) continue
        val key = ((hsv[0] / 20).toInt() shl 4) + (hsv[1] * 3).toInt()
        buckets[key] = ((buckets[key]?.first ?: 0f) + .3f + hsv[1]) to pixel
    }
    val dominant = buckets.maxByOrNull { it.value.first }?.value?.second ?: return Color(0xFF283431) to Color(0xFFD9ECE4)
    android.graphics.Color.colorToHSV(dominant, hsv)
    val hue = if (hsv[1] < .14f) 200f else hsv[0]
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, (hsv[1] * .7f).coerceIn(.12f, .58f),
        if (hsv[1] < .14f) .14f else .36f))) to Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, .23f, 1f)))
}
