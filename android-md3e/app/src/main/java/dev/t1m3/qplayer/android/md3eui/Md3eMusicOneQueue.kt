// Adapted from MusicOne PlayerQueueSheet.kt, PlayerPanels.kt and QueuePlayingIndicator.kt.
// Copyright (c) 2026 ShuyunR, MIT. See assets/licenses/musicone-LICENSE.txt.
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.t1m3.qplayer.android.md3eui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import dev.t1m3.qplayer.android.ui.LocalIosControls
import dev.t1m3.qplayer.model.Track
import kotlinx.coroutines.isActive
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sin

private val MusicOneQueueBackground = Color(0xFF242629)
private val MusicOneQueueTypography = Typography()
private val MusicOneQueueShapes = Shapes()
private val MusicOneQueueColors = darkColorScheme(
    primary = Color(0xFFE3E3E3), onPrimary = Color(0xFF202124),
    primaryContainer = Color(0xFF353739), onPrimaryContainer = Color(0xFFF1F1F1),
    secondary = Color(0xFFCECECE), onSecondary = Color(0xFF202124),
    secondaryContainer = Color(0xFF353739), onSecondaryContainer = Color(0xFFF1F1F1),
    tertiary = Color(0xFFCECECE), onTertiary = Color(0xFF202124),
    tertiaryContainer = Color(0xFF353739), onTertiaryContainer = Color(0xFFF1F1F1),
    background = Color(0xFF121314), onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF121314), onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF292B2D), onSurfaceVariant = Color(0xFFBCC0C3),
    surfaceContainerLowest = Color(0xFF0D0E0F), surfaceContainerLow = Color(0xFF191A1B),
    surfaceContainer = Color(0xFF202123), surfaceContainerHigh = Color(0xFF292B2D),
    surfaceContainerHighest = Color(0xFF343638),
    outline = Color(0xFF858A8E), outlineVariant = Color(0xFF424548), surfaceTint = Color.Transparent,
)

/** MusicOne's queue sheet is shared by all designs; the engine and timer remain QPlayer's. */
@Composable
internal fun Md3eQueueScreen(runtime: Md3eRuntime, onBack: () -> Unit, timer: Md3eSleepTimerState) {
    val state = runtime.playback
    val queue = runtime.queue
    val entryIds = remember(runtime) { AtomicLong() }
    var entries by remember(runtime) {
        mutableStateOf(queue.map { QueueEntry(entryIds.getAndIncrement(), it) })
    }
    var playingEntryId by remember(runtime) { mutableStateOf(entries.getOrNull(state.queueIndex)?.id) }
    LaunchedEffect(queue, state.queueIndex) {
        entries = reconcileQueueEntries(entries, queue, entryIds)
        playingEntryId = entries.getOrNull(state.queueIndex)?.id
    }
    fun moveEntry(id: Long, target: Long) {
        val from = entries.indexOfFirst { it.id == id }
        val to = entries.indexOfFirst { it.id == target }
        if (from < 0 || to < 0 || from == to) return
        runtime.controller.moveInQueue(from, to)
        entries = entries.toMutableList().apply { add(to, removeAt(from)) }
    }
    val list = rememberLazyListState()
    var initiallyPositioned by remember { mutableStateOf(false) }
    LaunchedEffect(entries.isEmpty(), state.queueIndex) {
        if (!initiallyPositioned && entries.isNotEmpty() && state.queueIndex in entries.indices) {
            list.scrollToItem(state.queueIndex)
            initiallyPositioned = true
        }
    }
    val reorder = rememberReorderableLazyListState(list, onMove = { from, to ->
        val fromId = from.key as? Long
        val toId = to.key as? Long
        if (fromId != null && toId != null) moveEntry(fromId, toId)
    })
    var reordering by remember { mutableStateOf(false) }
    var openMenu by remember { mutableStateOf<Long?>(null) }
    // The list owns its scroll/fling even at its first/last item. In particular,
    // reorder auto-scroll must not hand its remaining delta/velocity to the sheet.
    // The header outside this connection remains available for sheet dismissal.
    val listScrollBoundary = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
                Offset(0f, available.y)
            override suspend fun onPostFling(consumed: Velocity, available: Velocity) =
                Velocity(0f, available.y)
        }
    }
    LaunchedEffect(entries, openMenu) {
        if (openMenu != null && entries.none { it.id == openMenu }) openMenu = null
    }
    val view = LocalView.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    MaterialTheme(colorScheme = MusicOneQueueColors, typography = MusicOneQueueTypography, shapes = MusicOneQueueShapes) {
    CompositionLocalProvider(LocalContentColor provides Color.White, LocalIosControls provides false) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = maxHeight * .8f
        ModalBottomSheet(onDismissRequest = onBack, sheetState = sheet,
            sheetGesturesEnabled = !reordering && openMenu == null,
            dragHandle = null, containerColor = MusicOneQueueBackground, contentColor = Color.White) {
            Column(Modifier.fillMaxWidth().height(height).background(MusicOneQueueBackground).padding(top = 18.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(4.dp)
                    .background(Color.White.copy(alpha = .28f), RoundedCornerShape(2.dp)))
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("播放列表", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                Text("长按歌曲或拖动右侧手柄调整顺序", color = Color.White.copy(alpha = .45f),
                    fontSize = 11.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                if (runtime.bili.queueVideo) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("RELATED" to "相关推荐", "COLLECTION" to "当前合集").forEach { (mode, label) ->
                            FilterChip(selected = runtime.bili.queueMode == mode,
                                onClick = { runtime.play { switchBiliQueueMode(mode) } },
                                enabled = !runtime.bili.queueSwitching, modifier = Modifier.weight(1f),
                                label = { Text(label) }, leadingIcon = if (runtime.bili.queueSwitching && runtime.bili.queueTargetMode == mode) ({
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                }) else null)
                        }
                    }
                }
                if (entries.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("播放列表中还没有歌曲", color = Color.White.copy(alpha = .55f))
                } else LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)
                    .nestedScroll(listScrollBoundary), state = list,
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                        ReorderableItem(reorder, key = entry.id, animateItemModifier = Modifier.animateItem()) { dragging ->
                            val scale by animateFloatAsState(if (dragging) 1.02f else 1f,
                                spring(stiffness = 400f), label = "queue drag scale")
                            MusicOneQueueRow(index, entry.track, current = entry.id == playingEntryId,
                                playing = state.playing, dragging = dragging,
                                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                                    .longPressDraggableHandle(
                                    interactionSource = remember { MutableInteractionSource() },
                                    onDragStarted = {
                                        openMenu = null; reordering = true
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    }, onDragStopped = { reordering = false }),
                                dragModifier = Modifier.draggableHandle(
                                    interactionSource = remember { MutableInteractionSource() },
                                    onDragStarted = {
                                        openMenu = null; reordering = true
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    }, onDragStopped = { reordering = false }),
                                menuExpanded = openMenu == entry.id,
                                onMenu = { openMenu = entry.id }, onDismissMenu = { openMenu = null },
                                onPlay = {
                                    val slot = entries.indexOfFirst { it.id == entry.id }
                                    if (slot >= 0) runtime.play { playQueueIndex(slot) }
                                },
                                onEnqueue = { runtime.controller.enqueueTrack(entry.track) },
                                canMoveUp = index > 0, canMoveDown = index < entries.lastIndex,
                                onMove = { direction ->
                                    val slot = entries.indexOfFirst { it.id == entry.id }
                                    val target = if (slot >= 0) entries.getOrNull(slot + direction) else null
                                    if (target != null) moveEntry(entry.id, target.id)
                                },
                                onRemove = {
                                    val slot = entries.indexOfFirst { it.id == entry.id }
                                    if (slot >= 0) {
                                        runtime.controller.removeFromQueue(slot)
                                        entries = entries.filterNot { it.id == entry.id }
                                    }
                                })
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = .08f))
                // Root-owned countdown survives sheet dismissal and design switches.
                Md3eSleepTimerControl(timer, Modifier.padding(top = 6.dp, bottom = 8.dp))
            }
        }
    }
    }
    }
}

@Composable
private fun MusicOneQueueRow(index: Int, track: Track, current: Boolean, playing: Boolean, dragging: Boolean,
    modifier: Modifier, dragModifier: Modifier, menuExpanded: Boolean, onMenu: () -> Unit,
    onDismissMenu: () -> Unit, onPlay: () -> Unit, onEnqueue: () -> Unit,
    canMoveUp: Boolean, canMoveDown: Boolean, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    Row(modifier.fillMaxWidth().background(
        if (current || dragging) Color.White.copy(alpha = .1f) else Color.Transparent, RoundedCornerShape(14.dp))
        .clickable(onClick = onPlay)
        .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", color = Color.White.copy(alpha = .45f), fontSize = 11.sp, modifier = Modifier.width(28.dp))
        Artwork(track.coverThumbPath ?: track.coverUrl.orEmpty(),
            Modifier.size(46.dp), corner = 16.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title.orEmpty(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist.orEmpty(), color = Color.White.copy(alpha = .55f), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.DragHandle, contentDescription = "拖动调整歌曲顺序",
            tint = Color.White.copy(alpha = .5f), modifier = dragModifier.size(40.dp).padding(9.dp).semantics {
                customActions = listOf(
                    CustomAccessibilityAction("向上移动") { if (canMoveUp) onMove(-1); canMoveUp },
                    CustomAccessibilityAction("向下移动") { if (canMoveDown) onMove(1); canMoveDown })
            })
        AnimatedVisibility(current,
            enter = fadeIn(tween(220)) + expandHorizontally(tween(260)),
            exit = fadeOut(tween(180)) + shrinkHorizontally(tween(220))) {
            MusicOneQueuePlayingIndicator(playing)
        }
        Box {
            IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreHoriz, "更多", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu,
                shape = RoundedCornerShape(28.dp), containerColor = MusicOneQueueColors.surfaceContainer) {
                DropdownMenuItem(text = { Text("添加到播放列表") }, onClick = { onDismissMenu(); onEnqueue() })
                DropdownMenuItem(text = { Text("向上移动") }, enabled = canMoveUp, onClick = { onDismissMenu(); onMove(-1) })
                DropdownMenuItem(text = { Text("向下移动") }, enabled = canMoveDown, onClick = { onDismissMenu(); onMove(1) })
                DropdownMenuItem(text = { Text("从播放列表移除") }, onClick = { onDismissMenu(); onRemove() })
            }
        }
    }
}

/** MusicOne's three soft bars; pause settles them and reduced motion uses a still mark. */
@Composable
private fun MusicOneQueuePlayingIndicator(playing: Boolean) {
    val phase = remember { Animatable(0f) }
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, tween(240), label = "queue volume settle")
    val reduce = !claudeMotionEnabled()
    val active = LocalMd3eRenderingActive.current && !LocalMd3eMotionActive.current
    LaunchedEffect(playing, reduce, active) {
        if (playing && !reduce && active) while (isActive) {
            phase.snapTo(0f)
            phase.animateTo(6.283185f, tween(1200, easing = LinearEasing))
        }
    }
    Canvas(Modifier.size(22.dp).semantics { contentDescription = if (playing) "正在播放" else "已暂停" }) {
        repeat(3) { index ->
            val wave = if (reduce) .55f else (sin(phase.value + index * 2f) + 1f) * .5f
            val height = size.height * (.18f + .72f * wave * amplitude)
            drawRoundRect(Color.White, Offset(index * size.width / 3f, (size.height - height) / 2f),
                Size(size.width / 5f, height), CornerRadius(2.dp.toPx()))
        }
    }
}

private data class QueueEntry(val id: Long, val track: Track)

private fun reconcileQueueEntries(previous: List<QueueEntry>, tracks: List<Track>, ids: AtomicLong): List<QueueEntry> {
    val remaining = previous.toMutableList()
    return tracks.map { track ->
        val existing = remaining.indexOfFirst { it.track === track }
        if (existing >= 0) remaining.removeAt(existing) else QueueEntry(ids.getAndIncrement(), track)
    }
}
