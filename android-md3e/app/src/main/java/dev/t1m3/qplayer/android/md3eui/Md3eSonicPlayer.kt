@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.t1m3.qplayer.android.md3eui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tonal Material dock: one clear primary action and a quiet, inset progress track. */
@Composable
internal fun SonicMiniPlayer(runtime: Md3eRuntime, onOpen: () -> Unit, modifier: Modifier,
    expansion: Md3ePlayerExpansionState?) {
    val state = runtime.playback
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val playCorner by animateDpAsState(if (state.playing) 12.dp else 20.dp,
        tween(if (Md3eLowSpecMode.current) 0 else 220), label = "sonic_mini_play_shape")
    Surface(modifier = modifier.height(52.dp)
        .then(if (expansion != null) Modifier.md3ePlayerExpansionSource(expansion) else Modifier),
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onOpen()
        }, shape = RoundedCornerShape(24.dp),
        color = if (expansion?.active == true) Color.Transparent else colors.surfaceContainerLow,
        border = if (expansion?.active == true) null else BorderStroke(.6.dp, colors.outlineVariant.copy(alpha = .6f)),
        shadowElevation = 0.dp) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Artwork(state.cover, Modifier.size(34.dp)
                    .onGloballyPositioned { expansion?.miniDiscBounds = it.boundsInRoot() },
                    corner = 7.dp, contentScale = ContentScale.Fit, containerColor = Color.Transparent)
                }
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(state.title.ifBlank { "尚未播放" }, fontSize = 14.sp, lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.artist.ifBlank { state.album }, fontSize = 11.sp, lineHeight = 15.sp,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = runtime::toggle, enabled = state.hasTrack,
                    shape = RoundedCornerShape(playCorner), modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary,
                        contentColor = colors.onPrimary)) {
                    Icon(if (state.playing) Md3eIcons.Pause else Md3eIcons.PlayArrow,
                        if (state.playing) "暂停" else "播放", Modifier.size(22.dp))
                }
                IconButton(onClick = { runtime.next() }, enabled = state.hasTrack, modifier = Modifier.size(48.dp)) {
                    Icon(Md3eIcons.SkipNext, "下一首", Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                }
            }
            LinearProgressIndicator(
                progress = { if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = 24.dp).height(1.dp),
                color = colors.primary, trackColor = colors.outlineVariant.copy(alpha = .45f),
                drawStopIndicator = {})
        }
    }
}

/** Only foreground composition lives here; MV rendering and its scrims remain owned by Md3ePlayer. */
@Composable
internal fun SonicPlayerDetail(runtime: Md3eRuntime, showArtwork: Boolean, onLyrics: () -> Unit,
    onQueue: () -> Unit, onOpenAlbum: (Long) -> Unit, onOpenArtist: (Long) -> Unit) {
    val state = runtime.playback
    var biliFavorite by remember { mutableStateOf(false) }
    var biliLogin by remember { mutableStateOf(false) }
    var playlistPicker by remember { mutableStateOf(false) }
    if (biliFavorite) Md3eBiliFavPickerDialog(runtime) { biliFavorite = false }
    if (biliLogin) Md3eBiliLoginDialog(runtime) { biliLogin = false }
    if (playlistPicker) SonicPlayerPlaylistPicker(runtime) { playlistPicker = false }
    val favorite = {
        if (runtime.bili.playing) {
            if (runtime.bili.loggedIn) biliFavorite = true else biliLogin = true
        } else runtime.likeDisplayed(state)
    }
    val collect = {
        if (!runtime.bili.playing && state.songId != 0L && runtime.playlists.loggedIn) {
            runtime.loadMyPlaylists()
            playlistPicker = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 540.dp
        val scroll = maxHeight < 370.dp
        val gap = if (compact) 8.dp else 16.dp
        val scrollState = rememberScrollState()
        Column(Modifier.fillMaxSize().then(if (scroll) Modifier.verticalScroll(scrollState) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.fillMaxWidth()
                .then(if (scroll) Modifier.height(160.dp) else Modifier.weight(1f)),
                contentAlignment = Alignment.Center) {
                if (runtime.bili.playing && runtime.videoVisible) {
                    val aspect = if (runtime.videoHeight > 0) runtime.videoWidth.toFloat() / runtime.videoHeight else 16f / 9f
                    val width = minOf(maxWidth, maxHeight * aspect)
                    Md3eVideoSlot(runtime, Modifier.width(width).height(width / aspect))
                } else if (showArtwork) {
                    val side = minOf(maxWidth * .94f, (maxHeight - 20.dp).coerceAtLeast(1.dp), 360.dp)
                    Artwork(state.cover, Modifier.size(side).playerTabCover()
                        .shadow(12.dp, RoundedCornerShape(28.dp))
                        .clickable { if (runtime.bili.playing) runtime.videoVisible = true else onLyrics() },
                        corner = 28.dp, contentScale = ContentScale.Fit, containerColor = Color.Transparent)
                }
                if (state.loading) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
            }
            Spacer(Modifier.height(gap))
            Row(Modifier.fillMaxWidth().playerMvAnchor(scene = true), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.title.ifBlank { "尚未播放" }, fontSize = if (compact) 23.sp else 27.sp,
                        lineHeight = if (compact) 28.sp else 33.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = state.albumId != 0L || state.album.isNotBlank()) {
                            if (state.albumId != 0L) onOpenAlbum(state.albumId)
                            else runtime.controller.openAlbumByName(state.album)
                        })
                    Md3ePlayingArtistLinks(state, onOpenArtist, fontSize = 14.sp, centered = false)
                }
                Box(Modifier.size(48.dp).clip(CircleShape)
                    .background(if (LocalPlayerMvBackdrop.current) Color.White.copy(alpha = .12f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .combinedClickable(enabled = runtime.bili.playing || state.likeable,
                        onLongClickLabel = "添加到歌单", onLongClick = collect, onClick = favorite),
                    contentAlignment = Alignment.Center) {
                    Icon(if (state.liked) PlayerIcons.Favorite else PlayerIcons.FavoriteBorder,
                        if (state.liked) "取消喜欢；长按添加到歌单" else "喜欢；长按添加到歌单", Modifier.size(22.dp),
                        tint = if (state.liked && !LocalPlayerMvBackdrop.current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(gap))
            AutoMixPreparationBadge(state, compact = true)
            if (runtime.bili.playing && runtime.bili.error.isNotBlank()) Text(runtime.bili.error,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (runtime.bili.playing) BiliProgress(runtime, foreground = if (LocalPlayerMvBackdrop.current) Color.White
                else MaterialTheme.colorScheme.primary)
            else PlayerProgress(state, wavy = runtime.settings.intOf("lyricProgressStyle") == 0,
                onSeek = { runtime.seekDisplayed(state, it) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
            Box(Modifier.fillMaxWidth().widthIn(max = 360.dp).playerControlShadow()) {
                PlayerTransportButtons(runtime.transportState.playing, runtime.transportState.hasTrack,
                    runtime.transportState.privateFm, runtime::previous, runtime::toggle, runtime::next,
                    runtime::setTransportMotionActive)
            }
            Spacer(Modifier.height(if (compact) 6.dp else 14.dp))
            SonicPlayerActions(runtime, onLyrics, onQueue, collect)
            Spacer(Modifier.height(if (compact) 4.dp else 12.dp))
        }
    }
}

@Composable
private fun SonicPlayerActions(runtime: Md3eRuntime, onLyrics: () -> Unit, onQueue: () -> Unit,
    onCollect: () -> Unit) {
    val state = runtime.playback
    var more by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        SonicPlayerAction(PlayerIcons.Shuffle, "随机播放", state.playMode == 1) {
            runtime.play { setPlayMode(if (state.playMode == 1) 0 else 1) }
        }
        SonicPlayerAction(SonicIcons.Lyrics, "歌词", enabled = !runtime.bili.playing, onClick = onLyrics)
        SonicPlayerAction(if (state.playMode == 2) PlayerIcons.RepeatOne else PlayerIcons.Repeat,
            if (state.playMode == 2) "关闭单曲循环" else "单曲循环", state.playMode == 2) {
            runtime.play { setPlayMode(if (state.playMode == 2) 0 else 2) }
        }
        Box {
            SonicPlayerAction(SonicIcons.More, "更多播放操作", onClick = { more = true })
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text("播放队列") }, enabled = state.queueSize > 0,
                    onClick = { more = false; onQueue() })
                if (runtime.bili.playing) {
                    DropdownMenuItem(text = { Text(if (runtime.videoVisible) "显示封面" else "显示视频") },
                        onClick = { more = false; runtime.videoVisible = !runtime.videoVisible })
                    DropdownMenuItem(text = { Text("全屏播放") },
                        onClick = { more = false; runtime.videoVisible = true; runtime.videoFullscreen = true })
                    DropdownMenuItem(text = { Text("缓存视频") }, onClick = { more = false; runtime.cacheCurrentBili() })
                } else {
                    DropdownMenuItem(text = { Text("添加到歌单") },
                        enabled = state.songId != 0L && runtime.playlists.loggedIn,
                        onClick = { more = false; onCollect() })
                }
                if (runtime.experimentalAiDjActive) DropdownMenuItem(text = { Text("退出 AI DJ") },
                    onClick = { more = false; runtime.play { stopExperimentalAiDj() } })
            }
        }
    }
}

@Composable
internal fun SonicPlayerHeader(tab: Int, onBack: () -> Unit, onDetail: () -> Unit,
    onQueue: () -> Unit, onShare: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(80.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) { Icon(PlayerIcons.Back, "收起播放器") }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(if (tab == 0) "正在播放" else "歌词 · 返回播放",
                Modifier.clip(CircleShape).clickable(enabled = tab != 0, onClick = onDetail)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.width(80.dp)) {
            IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) { Icon(PlayerIcons.Share, "分享") }
            IconButton(onClick = onQueue, modifier = Modifier.size(40.dp)) { Icon(PlayerIcons.Queue, "播放队列") }
        }
    }
}

@Composable
private fun SonicPlayerAction(icon: ImageVector, label: String, active: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val video = LocalPlayerMvBackdrop.current
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) {
                if (video) Color.White.copy(alpha = .16f) else colors.primaryContainer
            } else Color.Transparent,
            contentColor = if (active && !video) colors.onPrimaryContainer else colors.onSurfaceVariant)) {
        Icon(icon, label, Modifier.size(21.dp))
    }
}

@Composable
private fun SonicPlayerPlaylistPicker(runtime: Md3eRuntime, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加到歌单") },
        text = {
            if (runtime.playlists.mine.isEmpty()) Text("还没有自己的歌单（或未登录）")
            else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(runtime.playlists.mine, key = { it.id }) { playlist ->
                    Text(playlist.name ?: "未命名歌单", Modifier.fillMaxWidth().clickable {
                        runtime.controller.addToPlaylist(playlist.id, runtime.playback.songId)
                        runtime.notice = "已添加到：${playlist.name ?: "未命名歌单"}"
                        onDismiss()
                    }.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
