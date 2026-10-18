package dev.t1m3.qplayer.android.md3eui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.t1m3.qplayer.netease.NeteaseClient
import dev.t1m3.qplayer.netease.NeteaseDiscovery
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist
import kotlinx.coroutines.*

internal data class SonicGenreState(
    val categories: List<NeteaseDiscovery.Category> = emptyList(),
    val categoryLoading: Boolean = false, val categoryError: String = "",
    val selected: String = "", val playlists: List<NeteasePlaylist> = emptyList(),
    val loading: Boolean = false, val error: String = "", val more: Boolean = false,
    val offset: Int = 0,
)

/** Process-owned cache survives page navigation; generation cancellation prevents stale responses. */
internal class SonicGenreBrowser {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pageJob: Job? = null
    private val cache = linkedMapOf<String, SonicGenreState>()
    var state by mutableStateOf(SonicGenreState())
        private set

    fun ensureCategories(retry: Boolean = false) {
        if (state.categoryLoading || state.categories.isNotEmpty() || (!retry && state.categoryError.isNotEmpty())) return
        state = state.copy(categoryLoading = true, categoryError = "")
        scope.launch {
            try {
                val categories = withContext(Dispatchers.IO) { NeteaseClient.INSTANCE.playlistCategories() }
                state = state.copy(categories = categories, categoryLoading = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = state.copy(categoryLoading = false, categoryError = e.message ?: "音乐分类加载失败") }
        }
    }

    fun closeCategory() { pageJob?.cancel(); state = state.copy(selected = "", loading = false) }

    fun select(name: String) {
        pageJob?.cancel()
        val saved = cache[name]
        state = state.copy(selected = name, playlists = saved?.playlists.orEmpty(), more = saved?.more ?: false,
            offset = saved?.offset ?: 0, loading = false, error = "")
        if (saved == null) loadMore()
    }

    fun loadMore() {
        if (state.loading || state.selected.isEmpty()) return
        val category = state.selected
        val offset = state.offset
        state = state.copy(loading = true, error = "")
        pageJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { NeteaseClient.INSTANCE.categoryPlaylists(category, offset, 24) }
                ensureActive()
                if (state.selected != category) return@launch
                state = state.copy(playlists = (state.playlists + page.playlists).distinctBy { it.id },
                    loading = false, more = page.more, offset = page.nextOffset)
                cache[category] = state
                while (cache.size > 8) cache.remove(cache.keys.first())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (state.selected == category) state = state.copy(loading = false, error = e.message ?: "分类歌单加载失败")
            }
        }
    }
}

/** Sonic Tonal Explore's two-column 115dp genre cards, now driven by NetEase's catalogue. */
@Composable
internal fun SonicExploreGenres(browser: SonicGenreBrowser, onOpenPlaylist: (Long) -> Unit) {
    val state = browser.state
    var group by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(browser) { browser.ensureCategories() }
    BackHandler(state.selected.isNotEmpty()) { browser.closeCategory() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.selected.isEmpty()) {
            SonicSectionTitle("Browse Genres", "浏览音乐类别 · 网易云音乐")
            when {
                state.categoryLoading -> SonicStatus("正在加载音乐分类…", true)
                state.categoryError.isNotEmpty() -> SonicStatus(state.categoryError, false, "重试") { browser.ensureCategories(true) }
                else -> {
                    val groups = remember(state.categories) { state.categories.map { it.group }.distinct() }
                    val active = group.takeIf { it in groups }
                        ?: state.categories.firstOrNull { it.groupId == 1 }?.group ?: groups.firstOrNull().orEmpty()
                    SonicChips(groups, groups.indexOf(active).coerceAtLeast(0)) { group = groups[it] }
                    state.categories.filter { it.group == active }.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { category -> SonicGenreCard(category, Modifier.weight(1f)) { browser.select(category.name) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = browser::closeCategory) { Icon(Md3eIcons.Back, "返回音乐分类") }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(state.selected, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("网易云音乐 · 热门歌单", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.playlists.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { playlist ->
                        Md3eCollectionContainer("cover:playlist:${playlist.id}", Modifier.weight(1f), corner = 16.dp) {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onOpenPlaylist(playlist.id) }
                                .background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Artwork(playlist.coverThumbPath ?: playlist.coverUrl.orEmpty(), Modifier.fillMaxWidth().aspectRatio(1f)
                                    .md3eSharedCover("cover:playlist:${playlist.id}"), corner = 12.dp)
                                Text(playlist.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 2,
                                    minLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${playlist.trackCount} 首歌曲", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            when {
                state.loading -> SonicStatus("正在加载${state.selected}歌单…", true)
                state.error.isNotEmpty() -> SonicStatus(state.error, false, "重试", browser::loadMore)
                state.playlists.isEmpty() -> SonicStatus("该类别暂时没有歌单")
                state.more -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = browser::loadMore) { Text("加载更多歌单") }
                }
            }
        }
    }
}

@Composable
private fun SonicGenreCard(category: NeteaseDiscovery.Category, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pair = colors.surfaceContainerHigh to colors.onSurface
    Card(modifier.height(115.dp).clickable(onClick = onClick), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = pair.first, contentColor = pair.second)) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.size(72.dp).align(Alignment.BottomEnd).offset(10.dp, 10.dp)
                .background(pair.second.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (category.hot) Md3eIcons.AutoAwesome else SonicIcons.Equalizer, null, Modifier.size(24.dp), tint = pair.second)
            }
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(category.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (category.hot) "${category.group} · 热门" else category.group,
                    style = MaterialTheme.typography.labelSmall, color = pair.second.copy(alpha = .85f))
            }
        }
    }
}
