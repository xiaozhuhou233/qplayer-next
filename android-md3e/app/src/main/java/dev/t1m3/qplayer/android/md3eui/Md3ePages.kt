@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.t1m3.qplayer.android.md3eui

import dev.t1m3.qplayer.android.md3eui.Md3eAdaptiveButton as Button

import dev.t1m3.qplayer.android.ui.IosAwareAlertDialog as AlertDialog
import dev.t1m3.qplayer.android.ui.IosAwareIconButton as IconButton
import dev.t1m3.qplayer.android.ui.IosAwareTextButton as TextButton

import dev.t1m3.qplayer.android.md3eui.claudeClickable as clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.t1m3.qplayer.model.Track
import dev.t1m3.qplayer.netease.dto.NeteaseAlbum
import dev.t1m3.qplayer.netease.dto.NeteaseArtist
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist
import dev.t1m3.qplayer.netease.dto.NeteaseSong

@Composable
internal fun Md3eNavigationBar(selected: String, showLocalTab: Boolean = true,
    includeSystemInset: Boolean = true,
    onSelect: (String) -> Unit) {
    if (LocalSonicDesign.current) { SonicNavigationBar(selected, onSelect); return }
    val systemInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barInsets = if (includeSystemInset) WindowInsets.navigationBars else WindowInsets(0, 0, 0, 0)
    if (LocalClaudeDesign.current) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            HorizontalDivider(thickness = .8.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().windowInsetsPadding(barInsets).padding(horizontal = 16.dp, vertical = 4.dp)) {
                listOf(Triple("home", Md3eIcons.Home, "主页"),
                    Triple("playlists", Md3eIcons.Playlist, "歌单"),
                    Triple("local", Md3eIcons.Library, "本地"),
                    Triple("search", Md3eIcons.Search, "搜索"))
                    .filter { it.first != "local" || showLocalTab }.forEach { (route, icon, title) ->
                        val active = route == selected
                        val lift by androidx.compose.animation.core.animateFloatAsState(if (active) -2f else 0f, ClaudeOneTake.bouncy(), label = "tab landing")
                        val ink = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                            .clickable(role = Role.Tab) { onSelect(route) }.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(icon, null, Modifier.size(23.dp).graphicsLayer { translationY = lift.dp.toPx() }, tint = ink)
                            Text(title, color = ink, style = MaterialTheme.typography.labelMedium)
                        }
                    }
            }
        }
        return
    }
    NavigationBar(modifier = Modifier.height((80.dp - if (includeSystemInset) 0.dp else systemInset).coerceAtLeast(0.dp)),
        windowInsets = barInsets,
        containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        listOf(
            Triple("home", Md3eIcons.Home, "主页"),
            Triple("playlists", Md3eIcons.Playlist, "歌单"),
            Triple("local", Md3eIcons.Library, "本地"),
            Triple("search", Md3eIcons.Search, "搜索"),
        ).filter { (route, _, _) -> route != "local" || showLocalTab }
            .forEach { (route, icon, label) ->
            NavigationBarItem(selected = selected == route, onClick = { onSelect(route) },
                icon = { Icon(icon, label) }, label = { Text(label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}

@Composable
internal fun Md3ePlaylistsPage(state: PlaylistState, onLogin: () -> Unit, onRefresh: () -> Unit,
    onOpen: (Long) -> Unit, onDelete: (NeteasePlaylist) -> Unit, canDelete: (Long) -> Boolean,
    modifier: Modifier = Modifier, onPlayPlaylist: (Long) -> Unit = onOpen,
    onLocal: () -> Unit = {}, onCreate: (String) -> Unit = {}) {
    var selected by remember { mutableStateOf<NeteasePlaylist?>(null) }
    selected?.let { p ->
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text(if (!p.owned) "取消收藏歌单？" else if (canDelete(p.id)) "删除歌单？" else "歌单选项") },
            text = { Text(if (p.owned && !canDelete(p.id)) "「${p.name.orEmpty()}」是系统默认或暂不可删除的歌单。"
                else if (p.owned) "从网易云账号删除「${p.name.orEmpty()}」？歌单删除后无法恢复，歌曲本身不会被删除。"
                else "取消收藏「${p.name.orEmpty()}」？原作者的歌单会保留。") },
            confirmButton = {
                if (!p.owned || canDelete(p.id)) TextButton(onClick = { selected = null; onDelete(p) }) {
                    Text(if (p.owned) "删除歌单" else "取消收藏", color = MaterialTheme.colorScheme.error)
                } else TextButton(onClick = { selected = null }) { Text("知道了") }
            }, dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } })
    }
    if (LocalSonicDesign.current) {
        var creating by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("新建歌单") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("歌单名称") }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()); creating = false; name = "" }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } })
        SonicLibraryPage(state, onLogin, onRefresh, onOpen, { selected = it }, onPlayPlaylist, onLocal, { creating = true }, modifier)
        return
    }
    if (LocalIosDesign.current) {
        IosPlaylistsPage(state, onLogin, onRefresh, onOpen, { selected = it }, modifier)
        return
    }
    if (LocalClaudeDesign.current) {
        ClaudePlaylistsPage(state, onLogin, onRefresh, onOpen, { selected = it }, modifier)
        return
    }
    var category by rememberSaveable { mutableIntStateOf(0) }
    val shown = state.mine.filter { if (category == 0) it.owned else !it.owned }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp + LocalMd3eDockInset.current)) {
        item { PageHeading("我的歌单", "网易云音乐", onRefresh, enabled = state.loggedIn) }
        if (!state.loggedIn) {
            item {
                EmptyPage("登录后查看创建和收藏的歌单") {
                    Button(onClick = onLogin) { Icon(Md3eIcons.Person, null); Spacer(Modifier.width(8.dp)); Text("登录网易云音乐") }
                }
            }
        } else {
            item {
                PrimaryTabRow(selectedTabIndex = category) {
                    Tab(selected = category == 0, onClick = { category = 0 }, text = { Text("我创建的") })
                    Tab(selected = category == 1, onClick = { category = 1 }, text = { Text("我收藏的") })
                }
            }
            if (shown.isEmpty()) item { EmptyPage(if (category == 0) "还没有创建的歌单" else "还没有收藏的歌单") }
            items(shown, key = { it.id }) { playlist -> PlaylistRow(playlist, { selected = playlist }) { onOpen(playlist.id) } }
        }
    }
}

@Composable
private fun ClaudePlaylistsPage(state: PlaylistState, onLogin: () -> Unit, onRefresh: () -> Unit,
    onOpen: (Long) -> Unit, onLongClick: (NeteasePlaylist) -> Unit, modifier: Modifier) {
    var category by rememberSaveable { mutableIntStateOf(0) }
    val shown = state.mine.filter { if (category == 0) it.owned else !it.owned }
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp)) {
        item {
            ClaudeSectionTitle("我的歌单", "收藏、整理，再慢慢听")
            ClaudeUnderline(Modifier.padding(top = 8.dp, bottom = 18.dp))
            ClaudeBadge(if (state.loggedIn) "网易云音乐 · 已登录" else "网易云音乐 · 未登录")
            Spacer(Modifier.height(18.dp))
        }
        if (!state.loggedIn) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("登录后查看你的歌单", fontFamily = ClaudeSerif, fontSize = 20.sp)
                Text("创建和收藏的音乐会在这里出现。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
                Button(onClick = onLogin, modifier = Modifier.padding(top = 18.dp)) { Text("登录网易云音乐") }
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = category == 0, onClick = { category = 0 }, label = { Text("我创建的") })
                    FilterChip(selected = category == 1, onClick = { category = 1 }, label = { Text("我收藏的") })
                    IconButton(onClick = onRefresh, enabled = !state.loading) { Icon(Md3eIcons.Refresh, "刷新歌单") }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (shown.isEmpty()) item {
                Text(if (category == 0) "还没有创建的歌单" else "还没有收藏的歌单",
                    fontFamily = ClaudeSerif, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 28.dp))
            }
            itemsIndexed(shown, key = { _, it -> it.id }) { index, playlist ->
                Box(Modifier.claudeEntrance(index).animateItem(placementSpec = ClaudeOneTake.snappy())) {
                    ClaudePlaylistRow(playlist, { onLongClick(playlist) }) { onOpen(playlist.id) }
                }
            }
        }
    }
}

@Composable
private fun ClaudePlaylistRow(playlist: NeteasePlaylist, onLongClick: () -> Unit, onOpen: () -> Unit) {
    Md3eCollectionContainer("cover:playlist:${playlist.id}", Modifier.fillMaxWidth(), corner = 8.dp) {
Row(Modifier.fillMaxWidth().clickable(onClick = onOpen, onLongClick = onLongClick, onLongClickLabel = "歌单选项").padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(playlist.coverThumbPath ?: playlist.coverUrl.orEmpty(),
            Modifier.size(62.dp).md3eSharedCover("cover:playlist:${playlist.id}"), corner = 8.dp)
        Column(Modifier.weight(1f)) {
            Text(playlist.name.orEmpty(), fontFamily = ClaudeSerif, fontSize = 16.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${playlist.trackCount} 首歌曲", fontFamily = ClaudeMono, fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Icon(Md3eIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
}

@Composable
private fun PlaylistRow(playlist: NeteasePlaylist, onLongClick: () -> Unit, onOpen: () -> Unit) {
    Md3eCollectionContainer("cover:playlist:${playlist.id}", Modifier.fillMaxWidth(), corner = 16.dp) {
Row(Modifier.fillMaxWidth().clickable(onClick = onOpen, onLongClick = onLongClick, onLongClickLabel = "歌单选项").heightIn(min = 76.dp)
        .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(playlist.coverThumbPath ?: playlist.coverUrl.orEmpty(),
            Modifier.size(60.dp).md3eSharedCover("cover:playlist:${playlist.id}"))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(playlist.name.orEmpty(), style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${playlist.trackCount} 首${playlist.creatorNickname?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Md3eIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }
    HorizontalDivider(Modifier.padding(start = 94.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun Md3ePlaylistDetail(state: PlaylistState, id: Long, onBack: () -> Unit, onRefresh: () -> Unit,
    onPlay: PlayAction, onEnqueue: (NeteaseSong) -> Unit, modifier: Modifier = Modifier) {
    if (LocalSonicDesign.current) { SonicPlaylistDetail(state, id, onRefresh, onPlay, onEnqueue, modifier); return }
    if (LocalIosDesign.current) {
        IosPlaylistDetail(state, id, onRefresh, onPlay, onEnqueue, modifier)
        return
    }
    if (LocalClaudeDesign.current) {
        ClaudePlaylistDetail(state, id, onRefresh, onPlay, onEnqueue, modifier)
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp + LocalMd3eDockInset.current)) {
        item {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Artwork(state.cover, Modifier.size(108.dp).md3eSharedCover("cover:playlist:$id"))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.title.ifBlank { "正在加载歌单" }, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("${state.tracks.size} 首歌曲", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onPlay { playPlaylistTrack(0) } }, enabled = state.tracks.isNotEmpty()) {
                            Icon(Md3eIcons.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("播放全部")
                        }
                        IconButton(onClick = onRefresh, enabled = !state.loading) {
                            Icon(Md3eIcons.Refresh, "刷新歌单")
                        }
                    }
                }
            }
        }
        if (state.loading) item { CenterLoading("正在加载歌单") }
        if (!state.loading && state.tracks.isEmpty()) item { EmptyPage("歌单中还没有歌曲") }
        itemsIndexed(state.tracks, key = { index, song -> "$index-${song.id}" }) { index, song ->
            NeteaseSongRow(song, null, { onEnqueue(song) }) { onPlay { playPlaylistTrack(index) } }
        }
    }
}

@Composable
private fun ClaudePlaylistDetail(state: PlaylistState, id: Long, onRefresh: () -> Unit,
    onPlay: PlayAction, onEnqueue: (NeteaseSong) -> Unit, modifier: Modifier) {
    val songListState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(modifier, state = songListState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        item {
            Column(Modifier.fillMaxWidth().claudeEntrance(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 8.dp)) {
                    Artwork(state.cover, Modifier.fillMaxSize().md3eSharedCover("cover:playlist:$id"), corner = 8.dp)
                    Text("PLAYLIST ARCHIVE", Modifier.align(Alignment.TopStart).padding(start = 10.dp).rotate(-3f)
                        .background(MaterialTheme.colorScheme.background)
                        .border(.8.dp, MaterialTheme.colorScheme.outlineVariant)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                        fontFamily = ClaudeMono, fontSize = 9.sp, letterSpacing = 1.sp)
                }
                Text(state.title.ifBlank { "正在加载歌单" }, style = MaterialTheme.typography.headlineMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(if (state.loading && state.tracks.isEmpty()) "正在整理曲目…" else "${state.tracks.size} 首歌曲",
                    fontFamily = ClaudeMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 10.dp))
                Button(onClick = { onPlay { playPlaylistTrack(0) } }, enabled = state.tracks.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Icon(Md3eIcons.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("播放全部")
                }
            }
            ClaudeUnderline(Modifier.padding(top = 16.dp, bottom = 10.dp))
        }
        if (state.loading) item { CenterLoading("正在加载歌单") }
        if (!state.loading && state.tracks.isEmpty()) item { Text("歌单中还没有歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        itemsIndexed(state.tracks, key = { index, song -> "$index-${song.id}" }) { index, song ->
            Row(Modifier.fillMaxWidth().claudeEntrance(index, replayOnVisit = true,
                songListState = songListState, songKey = "$index-${song.id}")
                .animateItem(placementSpec = ClaudeOneTake.snappy())
                .songQueueMenu(song.name.orEmpty(), { onPlay { playPlaylistTrack(index) } }, { onEnqueue(song) })
                .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.coverThumbPath ?: song.coverUrl.orEmpty(), Modifier.size(48.dp), corner = 6.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.name.orEmpty(), fontFamily = ClaudeSerif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist.orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${index + 1}".padStart(2, '0'), fontFamily = ClaudeMono, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
        }
    }
}

@Composable
internal fun Md3eLocalPage(state: LocalState, onPermission: () -> Unit, onRefresh: () -> Unit,
    onPlay: PlayAction, onEnqueue: (Track) -> Unit, onPlayBili: (Track) -> Unit = {}, modifier: Modifier = Modifier) {
    var category by rememberSaveable { mutableIntStateOf(0) }
    LazyColumn(modifier, contentPadding = PaddingValues(
        top = iosPageTopPadding(),
        bottom = 24.dp + LocalMd3eDockInset.current)) {
        item { PageHeading(if (category == 0) "本地音乐" else "B站缓存",
            "${if (category == 0) state.tracks.size else state.bili.size} 首", onRefresh,
            enabled = category == 0 && state.permissionGranted && !state.scanning) }
        item {
            PrimaryTabRow(selectedTabIndex = category) {
                Tab(selected = category == 0, onClick = { category = 0 }, text = { Text("本地音乐") })
                Tab(selected = category == 1, onClick = { category = 1 }, text = { Text("B站") })
            }
        }
        if (category == 1) {
            if (state.bili.isEmpty()) item { EmptyPage("还没有缓存 B 站视频") }
            itemsIndexed(state.bili, key = { _, track -> "bili-${track.biliBvid}-${track.biliCid}" }) { _, track ->
                LocalSongRow(track, null, { onEnqueue(track) }) { onPlayBili(track) }
            }
            return@LazyColumn
        }
        when {
            !state.permissionGranted -> item {
                EmptyPage("允许访问音频后显示本机歌曲") {
                    Button(onClick = onPermission) { Icon(Md3eIcons.Library, null); Spacer(Modifier.width(8.dp)); Text("允许访问音乐") }
                }
            }
            state.scanning -> item { CenterLoading("正在扫描本地音乐") }
            state.tracks.isEmpty() -> item { EmptyPage(state.error.ifBlank { "没有找到本地歌曲" }) {
                TextButton(onClick = onRefresh) { Text("重新扫描") }
            } }
        }
        if (state.error.isNotBlank() && state.tracks.isNotEmpty()) item {
            Text(state.error, Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        itemsIndexed(state.tracks, key = { index, track -> "$index-${track.contentUri ?: track.filePath}" }) { index, track ->
            LocalSongRow(track, null, { onEnqueue(track) }) { onPlay { play(index) } }
        }
    }
}

@Composable
internal fun Md3eSearchPage(state: SearchState, onSearch: (String, String) -> Unit,
    onLoadMore: () -> Unit, onClearHistory: () -> Unit, onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (Long) -> Unit, onPlay: PlayAction,
    onEnqueueSong: (NeteaseSong) -> Unit, onEnqueueTrack: (Track) -> Unit,
    onEnqueueVideo: (dev.t1m3.qplayer.bili.BiliClient.BiliVideo) -> Unit, modifier: Modifier = Modifier,
    onOpenPlayer: () -> Unit = {}, onBiliLogin: () -> Unit = {}, onBiliFavorites: () -> Unit = {},
    biliLoggedIn: Boolean = false, recognizeOpen: Boolean = false, onRecognize: () -> Unit = {},
    autoBiliQuery: String? = null, onAutoBiliConsumed: () -> Unit = {},
    exploreContent: @Composable () -> Unit = {}, autoFocus: Boolean = false, onAutoFocusConsumed: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    var mode by rememberSaveable { mutableStateOf(state.mode) }
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            listState.scrollToItem(0)
            withFrameNanos { }
            inputFocus.requestFocus()
            keyboard?.show()
            onAutoFocusConsumed()
        }
    }
    // Ⓜ The recognise dialog's 「去B站搜索」: land here with the recognised title
    // already in the B站 tab and searched once.
    LaunchedEffect(autoBiliQuery) {
        val term = autoBiliQuery
        if (!term.isNullOrBlank()) {
            mode = "bili"
            query = term
            onSearch(term, "bili")
            onAutoBiliConsumed()
        }
    }
    val submit = { term: String ->
        query = term
        if (term.isNotBlank()) {
            focusManager.clearFocus()
            keyboard?.hide()
            onSearch(term.trim(), mode)
        }
    }
    val sonic = LocalSonicDesign.current
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(
        top = iosPageTopPadding(),
        bottom = 24.dp + LocalMd3eDockInset.current)) {
        item {
            if (LocalSonicDesign.current) SonicSearchField(query, { query = it }, { submit(query) }, onRecognize,
                Modifier.focusRequester(inputFocus))
            else {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                    singleLine = true, shape = RoundedCornerShape(50),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                    placeholder = { Text(if (mode == "bili") "搜索 B站" else "搜索歌曲、专辑、歌手") },
                    leadingIcon = { Icon(Md3eIcons.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                            Icon(Md3eIcons.Close, "清空搜索")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(query) }))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRecognize) {
                        Text("识曲", fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = { submit(query) }, enabled = query.isNotBlank()) {
                        Icon(Md3eIcons.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("搜索")
                    }
                }
            }
            }
        }
        item {
            val modes = listOf("song" to "歌曲", "album" to "专辑", "artist" to "歌手", "bili" to "B站")
            if (LocalSonicDesign.current) SonicChips(modes.map { it.second }, modes.indexOfFirst { it.first == mode }.coerceAtLeast(0)) { index ->
                mode = modes[index].first
                if (query.isNotBlank()) onSearch(query.trim(), mode)
            } else {
            PrimaryTabRow(selectedTabIndex = modes.indexOfFirst { it.first == mode }.coerceAtLeast(0)) {
                modes.forEach { (value, label) ->
                    Tab(selected = mode == value, onClick = {
                        mode = value
                        if (query.isNotBlank()) onSearch(query.trim(), value)
                    }, text = { Text(label) })
                }
            }
            }
        }
        if (mode == "bili") item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onBiliLogin) { Text(if (biliLoggedIn) "B站账号" else "登录 B站") }
                TextButton(onClick = onBiliFavorites) { Icon(Md3eIcons.Playlist, null, Modifier.size(18.dp)); Text("收藏夹") }
            }
        }
        if (query.isBlank()) {
            if (state.history.isNotEmpty()) {
                item { SearchSuggestions("最近搜索", state.history, onClearHistory) { submit(it) } }
            }
            if (state.hot.isNotEmpty()) item {
                SearchSuggestions("热门搜索", state.hot, null) { submit(it) }
            }
            if (sonic) item("sonic_explore") { exploreContent() }
        } else if (state.query.isNotBlank()) {
            if (state.loading && (mode == "bili" || state.songs.isEmpty() && state.albums.isEmpty() && state.artists.isEmpty())) {
                item { CenterLoading("正在搜索") }
            }
            when (mode) {
                "bili" -> {
                    if (state.videoError.isNotBlank()) item {
                        Text(state.videoError, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                    }
                    itemsIndexed(state.videos, key = { index, video -> "bili-$index-${video.bvid}" }) { _, video ->
                        Md3eBiliVideoRow(video, { onEnqueueVideo(video) }) { onPlay { playBiliCollection(video) }; onOpenPlayer() }
                    }
                }
                "song" -> {
                    // Ⓜ The old app's search layout: matching artists first, songs below.
                    if (state.artists.isNotEmpty()) {
                        item { ListHeading("歌手", state.artists.size) }
                        items(state.artists.take(4), key = { "artist-${it.id}" }) { artist ->
                            ArtistRow(artist) { onOpenArtist(artist.id) }
                        }
                    }
                    if (state.songs.isNotEmpty()) item { ListHeading("网易云歌曲", state.songs.size) }
                    itemsIndexed(state.songs, key = { index, song -> "song-$index-${song.id}" }) { index, song ->
                        NeteaseSongRow(song, index + 1, { onEnqueueSong(song) }) { onPlay { playSearchResult(index) } }
                    }
                    if (state.hasMore) item {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = onLoadMore, enabled = !state.loading) {
                                Text(if (state.loading) "正在加载" else "加载更多")
                            }
                        }
                    }
                    if (state.local.isNotEmpty()) item { ListHeading("本地匹配", state.local.size) }
                    itemsIndexed(state.local, key = { index, track -> "local-$index-${track.contentUri ?: track.filePath}" }) { index, track ->
                        LocalSongRow(track, index + 1, { onEnqueueTrack(track) }) { onPlay { playLocalSearchResult(index) } }
                    }
                }
                "album" -> items(state.albums, key = { it.id }) { album -> AlbumRow(album) { onOpenAlbum(album.id) } }
                "artist" -> items(state.artists, key = { it.id }) { artist -> ArtistRow(artist) { onOpenArtist(artist.id) } }
            }
            val count = when (mode) { "bili" -> state.videos.size; "album" -> state.albums.size; "artist" -> state.artists.size; else -> state.songs.size + state.local.size }
            if (!state.loading && count == 0) item { EmptyPage("没有找到相关结果") }
        }
    }
}

@Composable
private fun SearchSuggestions(title: String, terms: List<String>, onClear: (() -> Unit)?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (onClear != null) IconButton(onClick = onClear) { Icon(Md3eIcons.Delete, "清除搜索历史") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(terms) { term -> SuggestionChip(onClick = { onSelect(term) }, label = { Text(term) }) }
        }
    }
}

@Composable
internal fun Md3eArtistDetail(state: ArtistDetailState, onBack: () -> Unit, onAlbum: (Long) -> Unit,
    onPlay: PlayAction, onEnqueue: (NeteaseSong) -> Unit, modifier: Modifier = Modifier,
    playback: PlaybackState? = null) {
    if (LocalSonicDesign.current) {
        SonicArtistDetail(state, playback, onAlbum, onPlay, onEnqueue, modifier)
        return
    }
    if (LocalIosDesign.current) {
        IosArtistDetail(state, onAlbum, onPlay, onEnqueue, modifier)
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp + LocalMd3eDockInset.current)) {
        item {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Artwork(state.cover, Modifier.size(96.dp))
                Column {
                    Text(state.name.ifBlank { "正在加载歌手" }, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold)
                    if (state.description.isNotBlank()) Text(state.description, maxLines = 3,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.loading) item { CenterLoading("正在加载歌手") }
        if (state.songs.isNotEmpty()) item { ListHeading("热门歌曲", state.songs.size) }
        itemsIndexed(state.songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
            NeteaseSongRow(song, index + 1, { onEnqueue(song) }) { onPlay { playArtistSong(index) } }
        }
        if (state.albums.isNotEmpty()) item { ListHeading("专辑", state.albums.size) }
        items(state.albums, key = { it.id }) { album -> AlbumRow(album) { onAlbum(album.id) } }
        if (!state.loading && state.songs.isEmpty() && state.albums.isEmpty()) item { EmptyPage("暂无歌手内容") }
    }
}

@Composable
internal fun Md3eAlbumDetail(state: AlbumDetailState, id: Long, onBack: () -> Unit,
    onPlay: PlayAction, onEnqueue: (NeteaseSong) -> Unit, modifier: Modifier = Modifier) {
    if (LocalSonicDesign.current) {
        SonicAlbumDetail(state, id, onPlay, onEnqueue, modifier)
        return
    }
    if (LocalClaudeDesign.current) {
        ClaudeAlbumCollection(state, id, onPlay, onEnqueue, modifier)
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(
        top = iosPageTopPadding(), bottom = 24.dp + LocalMd3eDockInset.current)) {
        item {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Artwork(state.cover, Modifier.size(108.dp).md3eSharedCover("cover:album:$id"))
                Column {
                    Text(state.name.ifBlank { "正在加载专辑" }, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text(state.artistName, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.loading) item { CenterLoading("正在加载专辑") }
        itemsIndexed(state.tracks, key = { index, song -> "$index-${song.id}" }) { index, song ->
            NeteaseSongRow(song, index + 1, { onEnqueue(song) }) { onPlay { playAlbumTrack(index) } }
        }
        if (!state.loading && state.tracks.isEmpty()) item { EmptyPage("专辑中没有歌曲") }
    }
}

@Composable
private fun ClaudeAlbumCollection(state: AlbumDetailState, id: Long, onPlay: PlayAction,
    onEnqueue: (NeteaseSong) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.fillMaxWidth().claudeEntrance(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 8.dp)) {
                    Artwork(state.cover, Modifier.fillMaxSize().md3eSharedCover("cover:album:$id"), corner = 8.dp)
                    Text("ALBUM ARCHIVE", Modifier.align(Alignment.TopStart).padding(start = 10.dp).rotate(-3f)
                        .background(MaterialTheme.colorScheme.background)
                        .border(.8.dp, MaterialTheme.colorScheme.outlineVariant)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                        fontFamily = ClaudeMono, fontSize = 9.sp, letterSpacing = 1.sp)
                }
                Text(state.name.ifBlank { "专辑" }, style = MaterialTheme.typography.headlineMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (state.artistName.isNotBlank()) Text(state.artistName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
                Text(if (state.loading && state.tracks.isEmpty()) "正在整理曲目…" else "${state.tracks.size} 首歌曲",
                    fontFamily = ClaudeMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 10.dp))
                Button(onClick = { onPlay { playAlbumTrack(0) } }, enabled = state.tracks.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Icon(Md3eIcons.PlayArrow, null); Spacer(Modifier.width(7.dp)); Text("播放全部")
                }
            }
        }
        if (state.loading && state.tracks.isEmpty()) item {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
        }
        itemsIndexed(state.tracks, key = { index, song -> "${song.id}_$index" }) { index, song ->
            Row(Modifier.claudeEntrance(index).animateItem(placementSpec = ClaudeOneTake.snappy()), verticalAlignment = Alignment.CenterVertically) {
                Text((index + 1).toString().padStart(2, '0'), Modifier.width(25.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.weight(1f).songQueueMenu(song.name.orEmpty(),
                    { onPlay { playAlbumTrack(index) } }, { onEnqueue(song) })
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(song.coverThumbPath ?: song.coverUrl.orEmpty(), Modifier.size(48.dp), corner = 6.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                        Text(song.name.orEmpty(), fontFamily = ClaudeSerif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist.orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(Md3eIcons.Playlist, "加入队列", Modifier.size(20.dp).clickable { onEnqueue(song) },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
        }
        if (!state.loading && state.tracks.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂无专辑歌曲", fontFamily = ClaudeSerif, fontSize = 20.sp)
                Text("专辑内容加载失败或为空", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String, onRefresh: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().claudeEntrance().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRefresh, enabled = enabled) { Icon(Md3eIcons.Refresh, "刷新") }
    }
}

@Composable
private fun DetailBar(title: String, onBack: () -> Unit, onRefresh: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Md3eIcons.Back, "返回") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onRefresh != null) IconButton(onClick = onRefresh) { Icon(Md3eIcons.Refresh, "刷新") }
    }
}

@Composable
private fun ListHeading(title: String, count: Int) {
    Text("$title · $count", Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun NeteaseSongRow(song: NeteaseSong, number: Int?, onEnqueue: () -> Unit, onPlay: () -> Unit) {
    if (LocalSonicDesign.current) { SonicSongRow(song, number, onEnqueue, onPlay); return }
    SongResultRow(song.coverThumbPath ?: song.coverUrl.orEmpty(), song.name.orEmpty(), song.artist.orEmpty(), number, onPlay, onEnqueue)
}

@Composable
private fun LocalSongRow(track: Track, number: Int?, onEnqueue: () -> Unit, onPlay: () -> Unit) {
    SongResultRow(track.coverThumbPath ?: track.coverLocalPath.orEmpty(), track.title.orEmpty(), track.artist.orEmpty(), number, onPlay, onEnqueue)
}

@Composable
private fun SongResultRow(cover: String, title: String, artist: String, number: Int?, onPlay: () -> Unit,
    onEnqueue: () -> Unit) {
    val interaction = Modifier.songQueueMenu(title, onPlay, onEnqueue)
    Row(Modifier.fillMaxWidth().claudeEntrance((number ?: 1) - 1).then(interaction).heightIn(min = 64.dp)
        .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (number != null) Text(number.toString(), Modifier.width(24.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Artwork(cover, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Md3eIcons.PlayArrow, "播放 $title", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AlbumRow(album: NeteaseAlbum, onOpen: () -> Unit) {
    Md3eCollectionContainer("cover:album:${album.id}", Modifier.fillMaxWidth(), corner = 16.dp) {
Row(Modifier.fillMaxWidth().claudeEntrance().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(album.coverThumbPath ?: album.coverUrl.orEmpty(),
            Modifier.size(60.dp).md3eSharedCover("cover:album:${album.id}"))
        Column(Modifier.weight(1f)) {
            Text(album.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artistName.orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Md3eIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }
}

@Composable
private fun ArtistRow(artist: NeteaseArtist, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().claudeEntrance().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(artist.coverThumbPath ?: artist.coverUrl.orEmpty(), Modifier.size(60.dp))
        Text(artist.name.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Md3eIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CenterLoading(text: String) {
    Row(Modifier.fillMaxWidth().padding(28.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        if (LocalClaudeDesign.current) Md3eLoadingIndicator(Modifier.size(24.dp))
        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyPage(message: String, action: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}
