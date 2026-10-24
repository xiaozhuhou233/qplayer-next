package dev.t1m3.qplayer.android.md3eui

import dev.t1m3.qplayer.android.md3eui.Md3eAdaptiveSheet as ModalBottomSheet
import dev.t1m3.qplayer.android.md3eui.Md3eAdaptiveButton as Button

import dev.t1m3.qplayer.android.ui.IosAwareAlertDialog as AlertDialog
import dev.t1m3.qplayer.android.ui.IosAwareIconButton as IconButton
import dev.t1m3.qplayer.android.ui.IosAwareTextButton as TextButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import dev.t1m3.qplayer.ai.DailyVibeCuration
import dev.t1m3.qplayer.netease.NeteaseClient
import dev.t1m3.qplayer.netease.dto.NeteaseSong
import kotlinx.coroutines.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun DailyVibeShelf(runtime: Md3eRuntime, onLogin: () -> Unit = { runtime.notice = "请先在“我的”登录网易云音乐" },
    onOpen: (DailyVibeCuration.Playlist) -> Unit) {
    val state = runtime.dailyVibes.state
    val conversation = LocalMd3eAiConversation.current
    var editingSlot by remember(state.day) { mutableStateOf<Int?>(null) }
    var requirements by remember(state.day) { mutableStateOf("") }
    var submitError by remember(state.day) { mutableStateOf("") }
    var savingSlot by remember(state.day) { mutableStateOf<Int?>(null) }
    if (!state.configured) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 推荐歌单", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { runtime.dailyVibes.regenerate() }, enabled = !state.loading && state.savingSlot == null) {
                Icon(Md3eIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("重新生成")
            }
        }
        if (state.liveStatus.isNotBlank()) Text(state.liveStatus,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val timed = runtime.dailyVibes.timed
        if (timed.loading) Text(timed.message, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (timed.error.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(timed.error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { timed.ensure(manual = true) }, enabled = !timed.loading) { Text("重试时段歌单") }
        }
        if (state.loading || state.error.isNotBlank() || state.playlists.size != 3) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(state.progress, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(if (state.editingSlot != null) "每组 20–30 首 · 修改完成后替换当前歌单"
                        else "已完成 ${state.completedCount}/3 组 · 每组 20–30 首 · 生成一组即显示一组",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.error.isNotBlank()) Text(state.error, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    if (!state.loading) TextButton(onClick = { runtime.dailyVibes.retry() }) {
                        Text(if (state.editingSlot != null) "重试修改"
                            else if (state.error.isBlank()) "开始生成" else "重试剩余歌单")
                    }
                }
            }
        }
        if (runtime.dailyVibes.playlists.isNotEmpty()) {
        if (LocalSonicDesign.current) {
            SonicVibeAccordion(runtime, onOpen, onEdit = { playlist ->
                if (conversation != null) conversation.show(playlist)
                else if (!state.loading) { editingSlot = playlist.slot; requirements = ""; submitError = "" }
            }, onSave = { playlist ->
                if (!runtime.home.loggedIn) onLogin() else savingSlot = playlist.slot
            })
        } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(runtime.dailyVibes.playlists, key = { _, it -> it.slot }) { index, playlist ->
                // Only the artwork has rounded clipping: titles and descriptions keep their full bounds.
                Column(Modifier.width(164.dp).claudeClickable(onClick = { onOpen(playlist) },
                    onLongClickLabel = "输入要求修改这组 AI 歌单", onLongClick = {
                        if (conversation != null) conversation.show(playlist)
                        else if (!state.loading) {
                            editingSlot = playlist.slot
                            requirements = ""
                            submitError = ""
                        }
                    }),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val cover = playlist.songs.firstOrNull()?.let { it.coverThumbPath ?: it.coverUrl }.orEmpty()
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        Artwork(cover, Modifier.fillMaxSize(), corner = 20.dp)
                        Surface(Modifier.align(Alignment.BottomStart).padding(10.dp), shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .9f)) {
                            Text("${playlist.songs.size} 首", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(playlist.name, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(playlist.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        }
        }
        if (LocalSonicDesign.current && state.actionSlot != null) {
            if (state.actionError.isNotBlank()) Text(state.actionError, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            else if (state.actionMessage.isNotBlank()) Text(state.actionMessage, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    runtime.dailyVibes.playlists.firstOrNull { it.slot == savingSlot }?.let { playlist ->
        AlertDialog(onDismissRequest = { savingSlot = null }, title = { Text("收藏为网易云歌单") },
            text = { Text("将「${playlist.name}」的 ${playlist.songs.size} 首歌曲保存到网易云「我创建的」歌单。") },
            confirmButton = { TextButton(onClick = {
                savingSlot = null
                runtime.dailyVibes.saveToNetease(playlist, playlist.name)
            }, enabled = !state.loading && state.savingSlot == null) { Text("收藏") } },
            dismissButton = { TextButton(onClick = { savingSlot = null }) { Text("取消") } })
    }
    runtime.dailyVibes.playlists.firstOrNull { it.slot == editingSlot }?.let { playlist ->
        AlertDialog(onDismissRequest = { editingSlot = null },
            title = { Text("修改 AI 歌单") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                    Text("输入你的要求，AI 会基于这组歌单调整选曲。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(requirements, { requirements = it; submitError = "" },
                        modifier = Modifier.fillMaxWidth(), label = { Text("修改要求") },
                        placeholder = { Text("例如：保留轻柔氛围，多一些女声，减少重复歌手") },
                        minLines = 3, maxLines = 6, isError = submitError.isNotBlank())
                    if (submitError.isNotBlank()) Text(submitError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = requirements.isNotBlank() && !state.loading, onClick = {
                    if (runtime.dailyVibes.revise(playlist.slot, requirements)) editingSlot = null
                    else submitError = "暂时无法修改，请等待当前生成完成或重新打开歌单。"
                }) { Text("修改这组歌单") }
            },
            dismissButton = { TextButton(onClick = { editingSlot = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun DailyVibeDetails(runtime: Md3eRuntime, initial: DailyVibeCuration.Playlist, onDismiss: () -> Unit,
    onLogin: () -> Unit, onEnqueue: (NeteaseSong) -> Unit, onPlay: (List<NeteaseSong>, Int) -> Unit) {
    val playlist = runtime.dailyVibes.playlists.firstOrNull { it.slot == initial.slot } ?: initial
    val cover = playlist.songs.firstOrNull()?.let { it.coverThumbPath ?: it.coverUrl }.orEmpty()
    SonicArtworkTheme(if (LocalSonicDesign.current && runtime.settings.bool("monet")) cover else null) {
        DailyVibeDetailsContent(runtime, initial, onDismiss, onLogin, onEnqueue, onPlay)
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun DailyVibeDetailsContent(runtime: Md3eRuntime, initial: DailyVibeCuration.Playlist, onDismiss: () -> Unit,
    onLogin: () -> Unit, onEnqueue: (NeteaseSong) -> Unit, onPlay: (List<NeteaseSong>, Int) -> Unit) {
    val state = runtime.dailyVibes.state
    val playlist = runtime.dailyVibes.playlists.firstOrNull { it.slot == initial.slot } ?: initial
    val busy = state.loading || state.savingSlot != null
    var editing by remember(initial.slot, state.day) { mutableStateOf(false) }
    var draft by remember(initial.slot, state.day) { mutableStateOf(playlist.songs.toList()) }
    var editOriginal by remember(initial.slot, state.day) { mutableStateOf(playlist) }
    var adding by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf(playlist.name) }
    var discard by remember { mutableStateOf(false) }
    var closeAfterDiscard by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf("") }
    val songs = (if (editing) draft else playlist.songs).distinctBy { it.id }
    fun requestClose() {
        if (editing && draft.map { it.id } != editOriginal.songs.map { it.id }) {
            closeAfterDiscard = true; discard = true
        } else onDismiss()
    }
    fun move(fromId: Long, toId: Long) {
        if (!editing || busy) return
        val from = draft.indexOfFirst { it.id == fromId }
        val to = draft.indexOfFirst { it.id == toId }
        if (from >= 0 && to >= 0 && from != to)
            draft = draft.toMutableList().apply { add(to, removeAt(from)) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { value ->
        if (value == SheetValue.Hidden && editing && draft.map { it.id } != editOriginal.songs.map { it.id }) {
            closeAfterDiscard = true; discard = true; false
        } else true
    })
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState, onMove = { from, to ->
        val fromId = from.key as? Long
        val toId = to.key as? Long
        if (fromId != null && toId != null) move(fromId, toId)
    })
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val safeInsets = WindowInsets.safeDrawing
    val safeHeight = with(density) {
        (screenHeight - safeInsets.getTop(this).toDp() - safeInsets.getBottom(this).toDp()).coerceAtLeast(1.dp)
    }
    // Bound the actual list viewport. A weighted child inside the sheet's
    // wrap-content/inset columns can be measured taller than its visible window.
    val contentHeight = (safeHeight * .9f - 56.dp).coerceAtLeast(1.dp)
    // Keep unconsumed list drags/flings inside the content. The sheet's handle
    // remains outside this boundary and can still drag the window to dismiss.
    val contentScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
                Offset(0f, available.y)

            override suspend fun onPostFling(consumed: Velocity, available: Velocity) =
                Velocity(0f, available.y)
        }
    }
    val ios = LocalIosDesign.current
    ModalBottomSheet(onDismissRequest = ::requestClose, sheetState = sheetState) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = contentHeight).nestedScroll(contentScroll), state = listState,
            contentPadding = PaddingValues(start = if (ios) 0.dp else 24.dp,
                end = if (ios) 0.dp else 24.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "playlist_header") {
                Column {
                    Text(playlist.name, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(playlist.summary, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onPlay(playlist.songs, 0) }, enabled = !editing && songs.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()) {
                        Text("播放 · ${songs.size} 首")
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editing) {
                            TextButton(onClick = { adding = true }, enabled = !busy) { Text("添加歌曲") }
                            TextButton(onClick = {
                                if (runtime.dailyVibes.updateSongs(editOriginal, draft)) { editing = false; editError = "" }
                                else editError = "暂时无法保存，请等待生成完成；若歌单已被替换，请重新打开编辑。"
                            }, enabled = !busy) { Text("完成") }
                            TextButton(onClick = {
                                if (draft.map { it.id } == editOriginal.songs.map { it.id }) editing = false
                                else { closeAfterDiscard = false; discard = true }
                            }) { Text("取消") }
                        } else {
                            TextButton(onClick = {
                                draft = playlist.songs.toList(); editOriginal = playlist; editing = true; editError = ""
                            }, enabled = !busy) { Text("编辑歌曲") }
                            val synced = playlist.savedPlaylistId > 0 && playlist.savedSongIds == songs.map { it.id }
                            TextButton(onClick = {
                                saveName = playlist.name; saving = true
                            }, enabled = !busy && !synced) {
                                Text(if (synced) "已收藏" else if (playlist.savedPlaylistId > 0) "同步到歌单" else "收藏为歌单")
                            }
                        }
                    }
                    if (editing) Text("拖动右侧手柄调整顺序，点击删除移除歌曲；完成后保存修改。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.savingSlot == playlist.slot) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.actionSlot == playlist.slot && state.actionMessage.isNotBlank())
                        Text(state.actionMessage, style = MaterialTheme.typography.bodySmall)
                    if (state.actionSlot == playlist.slot && state.actionError.isNotBlank())
                        Text(state.actionError, color = MaterialTheme.colorScheme.error)
                    if (editError.isNotBlank()) Text(editError, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (songs.isEmpty()) item { Text("歌单还没有歌曲，点击“添加歌曲”开始整理。", Modifier.padding(vertical = 20.dp)) }
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                ReorderableItem(reorderState, key = song.id,
                    animateItemModifier = Modifier.animateItem(placementSpec = ClaudeOneTake.snappy())) { _ ->
                val action = if (editing) Modifier else Modifier.songQueueMenu(song.name.orEmpty(),
                    { onPlay(playlist.songs, index) }, { onEnqueue(song) })
                Row(Modifier.fillMaxWidth().then(action).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(song.coverThumbPath ?: song.coverUrl.orEmpty(), Modifier.size(48.dp), corner = 12.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index + 1}. ${song.name.orEmpty()}", fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.artist.orEmpty(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!song.recommendReason.isNullOrBlank()) Text("推荐原因：${song.recommendReason}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (editing) {
                        IconButton(onClick = { draft = draft.filterNot { it.id == song.id } }, enabled = !busy) {
                            Icon(Md3eIcons.Delete, "移除 ${song.name.orEmpty()}")
                        }
                        Icon(Md3eIcons.DragHandle, "拖动调整歌曲顺序",
                            modifier = Modifier.size(40.dp).draggableHandle(enabled = !busy,
                                interactionSource = remember { MutableInteractionSource() }).semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction("向上移动") {
                                        draft.getOrNull(index - 1)?.let { move(song.id, it.id); true } ?: false
                                    }, CustomAccessibilityAction("向下移动") {
                                        draft.getOrNull(index + 1)?.let { move(song.id, it.id); true } ?: false
                                    })
                            }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                }
            }
        }
    }
    if (adding) DailyVibeAddSongs(runtime, draft.map { it.id }.toSet(), onDismiss = { adding = false }) { song ->
        if (draft.none { it.id == song.id }) draft = draft + song
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
        text = { Text("本次增删和排序尚未保存。") },
        confirmButton = { TextButton(onClick = {
            discard = false; editing = false
            if (closeAfterDiscard) onDismiss()
        }) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    if (saving) AlertDialog(onDismissRequest = { saving = false },
        title = { Text(if (playlist.savedPlaylistId > 0) "同步到网易云歌单" else "收藏为网易云歌单") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (playlist.savedPlaylistId > 0) "将当前 ${songs.size} 首歌曲和排列顺序同步到之前收藏的歌单。"
                else "将当前 ${songs.size} 首歌曲按顺序保存到网易云「我创建的」歌单。")
            if (playlist.savedPlaylistId == 0L) OutlinedTextField(saveName, { saveName = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("歌单名称") }, singleLine = true)
            if (!runtime.home.loggedIn) Text("请先登录网易云音乐。", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = saveName.isNotBlank(), onClick = {
            saving = false
            if (!runtime.home.loggedIn) onLogin() else runtime.dailyVibes.saveToNetease(playlist, saveName)
        }) { Text(if (runtime.home.loggedIn) "保存" else "去登录") } },
        dismissButton = { TextButton(onClick = { saving = false }) { Text("取消") } })
}

/** Dedicated picker: searches/playlist reads do not change the app route or AI DJ queue. */
@Composable
private fun DailyVibeAddSongs(runtime: Md3eRuntime, added: Set<Long>, onDismiss: () -> Unit,
    onAdd: (NeteaseSong) -> Unit) {
    var query by remember { mutableStateOf("") }
    var source by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<dev.t1m3.qplayer.netease.dto.NeteasePlaylist?>(null) }
    var results by remember { mutableStateOf<List<NeteaseSong>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var requestGeneration by remember { mutableLongStateOf(0) }
    val recent = runtime.controller.recentSongs.peek().orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(source) { if (source == 1) runtime.loadMyPlaylists() }
    LaunchedEffect(query, source, selected?.id, page) {
        val request = ++requestGeneration
        val sourceTab = source
        val keyword = query.trim()
        val playlistId = selected?.id
        val pageIndex = page
        error = ""
        if (source == 1 && selected == null) { results = emptyList(); loading = false; return@LaunchedEffect }
        if (source == 0 && query.isBlank()) {
            results = (recent + runtime.home.daily).distinctBy { it.id }; loading = false; hasMore = false
            return@LaunchedEffect
        }
        loading = true
        try {
            if (sourceTab == 0) delay(350)
            val batch = withContext(Dispatchers.IO) {
                if (sourceTab == 1) NeteaseClient.INSTANCE.playlistTracks(playlistId!!, 10000)
                else NeteaseClient.INSTANCE.searchSongs(keyword, 30, pageIndex * 30)
            }
            ensureActive()
            results = if (sourceTab == 0 && pageIndex > 0) (results + batch).distinctBy { it.id } else batch.distinctBy { it.id }
            hasMore = sourceTab == 0 && batch.size == 30
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { error = failure.message ?: "歌曲加载失败，请重试"
        } finally { if (request == requestGeneration) loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加歌曲") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .6f).dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = source == 0, onClick = { source = 0; selected = null; page = 0 }, label = { Text("搜索 / 最近") })
                    FilterChip(selected = source == 1, onClick = { source = 1; selected = null; page = 0 }, label = { Text("我的歌单") })
                }
                if (source == 0) OutlinedTextField(query, { query = it; page = 0; results = emptyList() },
                    label = { Text("歌名或歌手") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                else if (selected != null) TextButton(onClick = { selected = null }) { Text("‹ ${selected!!.name.orEmpty()} · 返回歌单列表") }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.weight(1f, fill = false), state = listState) {
                    if (source == 1 && selected == null) {
                        if (!runtime.home.loggedIn) item { Text("登录网易云后可从歌单添加；也可以直接搜索歌曲。") }
                        items(runtime.playlists.mine, key = { it.id }) { p ->
                            ListItem(headlineContent = { Text(p.name.orEmpty()) }, supportingContent = { Text("${p.trackCount} 首") },
                                modifier = Modifier.claudeClickable { selected = p; results = emptyList() })
                        }
                    } else {
                        if (!loading && results.isEmpty() && error.isBlank()) item { Text("没有找到歌曲，换个关键词试试。") }
                        items(results, key = { it.id }) { song ->
                            ListItem(headlineContent = { Text(song.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(song.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { TextButton(enabled = song.id !in added, onClick = { onAdd(song) }) {
                                    Text(if (song.id in added) "已添加" else "添加")
                                } })
                        }
                        if (hasMore) item { TextButton(onClick = { page++ }, enabled = !loading) { Text("加载更多") } }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}




