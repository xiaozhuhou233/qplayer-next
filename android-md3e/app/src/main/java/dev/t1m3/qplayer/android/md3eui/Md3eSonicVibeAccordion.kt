package dev.t1m3.qplayer.android.md3eui

import androidx.compose.animation.core.animateDp
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.t1m3.qplayer.ai.DailyVibeCuration

/** One expanded card and narrow neighbours share a fixed-width viewport throughout the easing. */
@Composable
internal fun SonicVibeAccordion(runtime: Md3eRuntime, onOpen: (DailyVibeCuration.Playlist) -> Unit,
    onEdit: (DailyVibeCuration.Playlist) -> Unit, onSave: (DailyVibeCuration.Playlist) -> Unit) {
    val state = runtime.dailyVibes.state
    val playlists = state.playlists
    if (playlists.isEmpty()) return
    var selectedSlot by rememberSaveable(state.day) { mutableIntStateOf(playlists.first().slot) }
    val activeIndex = playlists.indexOfFirst { it.slot == selectedSlot }.coerceAtLeast(0)
    val transition = updateTransition(activeIndex, label = "sonic_vibe_squeeze")
    val threshold = with(LocalDensity.current) { 36.dp.toPx() }
    val latestIndex by rememberUpdatedState(activeIndex)
    val view = LocalView.current
    val selectIndex by rememberUpdatedState<(Int) -> Unit>({ index ->
        if (index != activeIndex) {
            selectedSlot = playlists[index].slot
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    })
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val gap = 10.dp
        val collapsed = if (maxWidth < 300.dp) 26.dp else 32.dp
        val expanded = maxWidth - (collapsed + gap) * (playlists.size - 1)
        val height = (expanded * 1.48f).coerceIn(330.dp, 460.dp)
        Row(Modifier.fillMaxWidth().height(height).pointerInput(playlists.map { it.slot }, threshold) {
            var travel = 0f
            detectHorizontalDragGestures(
                onDragStart = { travel = 0f }, onDragCancel = { travel = 0f },
                onDragEnd = {
                    val index = when {
                        travel < -threshold -> (latestIndex + 1).coerceAtMost(playlists.lastIndex)
                        travel > threshold -> (latestIndex - 1).coerceAtLeast(0)
                        else -> latestIndex
                    }
                    selectIndex(index)
                    travel = 0f
                },
                onHorizontalDrag = { change, amount -> change.consume(); travel += amount })
        }, horizontalArrangement = Arrangement.spacedBy(gap)) {
            playlists.forEachIndexed { index, playlist ->
                key(playlist.slot) {
                    val width by transition.animateDp(transitionSpec = {
                        tween(520, easing = CubicBezierEasing(.22f, .75f, .15f, 1f))
                    },
                        label = "vibe_width_${playlist.slot}") { if (it == index) expanded else collapsed }
                    val open = activeIndex == index
                    val actualWidth = if (Md3eLowSpecMode.current || !LocalMd3eRenderingActive.current) {
                        if (open) expanded else collapsed
                    } else width
                    val reveal = if (expanded > collapsed) ((actualWidth - collapsed) / (expanded - collapsed)).coerceIn(0f, 1f) else 1f
                    SonicVibePoster(playlist, actualWidth, expanded, open, reveal,
                        busy = state.loading || state.savingSlot != null,
                        saving = state.savingSlot == playlist.slot,
                        onClick = { if (open) onOpen(playlist) else selectIndex(index) },
                        onEdit = { onEdit(playlist) }, onSave = { onSave(playlist) },
                        onPlay = { runtime.play { playAiDailySongs(playlist.songs, 0) } })
                }
            }
        }
    }
    if (playlists.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text("${activeIndex + 1} / ${playlists.size} · 左右滑动或轻点窄卡切换", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SonicVibePoster(playlist: DailyVibeCuration.Playlist, width: Dp, expandedWidth: Dp,
    open: Boolean, reveal: Float, busy: Boolean, saving: Boolean,
    onClick: () -> Unit, onEdit: () -> Unit, onSave: () -> Unit, onPlay: () -> Unit) {
    val cover = playlist.songs.firstOrNull()?.let { it.coverThumbPath ?: it.coverUrl }.orEmpty()
    val accent = rememberSonicCardAccent(cover)
    val saved = playlist.savedPlaylistId > 0 && playlist.savedSongIds == playlist.songs.map { it.id }
    Box(Modifier.width(width).fillMaxHeight().clip(RoundedCornerShape(30.dp))
        .background(accent.fill)
        .combinedClickable(onClickLabel = if (open) "查看歌单" else "展开歌单", onClick = onClick,
            onLongClickLabel = "输入要求修改这组 AI 歌单", onLongClick = if (!busy) onEdit else null)
        .semantics { selected = open; contentDescription = playlist.name }) {
        Artwork(cover, Modifier.fillMaxSize(), corner = 0.dp, containerColor = accent.fill)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal * reveal }
            .background(Brush.verticalGradient(listOf(
                Color.Black.copy(alpha = .06f), Color.Black.copy(alpha = .05f),
                Color.Black.copy(alpha = .65f), Color.Black.copy(alpha = .96f)))))
        // Keep typography and controls at expanded width; only the viewport squeezes.
        // This prevents text reflow and repeated relayout of every line during the squeeze.
        Column(Modifier.align(Alignment.CenterStart).wrapContentWidth(Alignment.Start, unbounded = true)
            .width(expandedWidth).fillMaxHeight()
            .graphicsLayer { alpha = if (open) reveal * reveal else 0f }
            .then(if (open) Modifier else Modifier.clearAndSetSemantics { })
            .padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            SonicTag(if (saving) "正在收藏…" else "AI FLOW · ${playlist.songs.size} 首", accent.fill, accent.ink)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.headlineSmall, color = Color.White,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(playlist.summary, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .8f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                SonicPlaylistActions(accent, onPlay, onSave, label = "播放", saved = saved,
                    enabled = open && playlist.songs.isNotEmpty(), saveEnabled = open && !busy && !saved)
            }
        }
    }
}
