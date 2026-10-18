package dev.t1m3.qplayer.android.md3eui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.drop
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Right-hand history drawer; the conversation's own text/layout remains left-to-right. */
@Composable
internal fun AiConversationHistoryHost(state: Md3eAiConversationState, ink: Color, paper: Color,
    content: @Composable () -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.checkpoint()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); state.checkpoint() }
    }
    LaunchedEffect(state.historyOpen) {
        if (state.historyOpen) {
            focus.clearFocus()
            keyboard?.hide()
            drawer.open()
        } else drawer.close()
    }
    LaunchedEffect(drawer) {
        snapshotFlow { drawer.currentValue }.drop(1).collect {
            if (it == DrawerValue.Closed) state.historyOpen = false
        }
    }
    BackHandler(drawer.isOpen) { state.historyOpen = false }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen,
            scrimColor = Color.Black.copy(alpha = .32f),
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, LocalContentColor provides ink) {
                    ModalDrawerSheet(Modifier.fillMaxWidth(.88f).widthIn(max = 360.dp),
                        drawerShape = RoundedCornerShape(28.dp), drawerContainerColor = paper.copy(alpha = 1f),
                        drawerContentColor = ink) {
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("对话历史", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { state.historyOpen = false }) { Icon(Md3eIcons.Close, "关闭历史") }
                        }
                        FilledTonalButton(onClick = { state.newConversation(); state.historyOpen = false },
                            enabled = !state.busy && !state.historyLoading,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            shape = CircleShape) { Text("新对话") }
                        if (state.busy) Text("正在生成，完成后可切换对话",
                            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = .65f))
                        if (state.historyError.isNotEmpty()) Text(state.historyError,
                            Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        if (state.historyError.isNotEmpty() && !state.historyLoading)
                            TextButton(onClick = state::retryHistoryLoad, modifier = Modifier.padding(horizontal = 16.dp)) { Text("重试读取历史") }
                        when {
                            state.historyLoading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Md3eLoadingIndicator(Modifier.size(36.dp))
                            }
                            state.sessions.isEmpty() -> Text("对话会自动保存在这里",
                                Modifier.padding(24.dp), color = ink.copy(alpha = .65f),
                                style = MaterialTheme.typography.bodyMedium)
                            else -> LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(state.sessions, key = { it.id }) { session ->
                                    val selected = state.conversationId == session.id
                                    Surface(onClick = { state.restore(session); state.historyOpen = false },
                                        enabled = !state.busy,
                                        shape = RoundedCornerShape(24.dp),
                                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                        contentColor = ink) {
                                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(session.title.ifBlank { "音乐对话" }, maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                                Text(remember(session.updatedAt) {
                                                    DateTimeFormatter.ofPattern("MM月dd日 HH:mm").withZone(ZoneId.systemDefault())
                                                        .format(Instant.ofEpochMilli(session.updatedAt))
                                                }, style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = .55f))
                                            }
                                            IconButton(onClick = { state.deleteHistory(session.id) }, enabled = !state.busy) {
                                                Icon(Md3eIcons.Delete, "删除此对话", Modifier.size(20.dp), tint = ink.copy(alpha = .6f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(Modifier.fillMaxSize()) {
                    content()
                    // Reserve the edge only; ordinary horizontal gestures within messages stay untouched.
                    Box(Modifier.align(Alignment.CenterEnd).width(24.dp).fillMaxHeight()
                        .pointerInput(state) {
                            var distance = 0f
                            detectHorizontalDragGestures(onDragStart = { distance = 0f },
                                onDragEnd = { if (distance < -40.dp.toPx()) state.historyOpen = true },
                                onHorizontalDrag = { change, delta ->
                                    distance += delta
                                    change.consume()
                                })
                        })
                }
            }
        }
    }
}
