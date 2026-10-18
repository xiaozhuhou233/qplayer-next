@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.t1m3.qplayer.android.md3eui

import dev.t1m3.qplayer.android.md3eui.Md3eAdaptiveButton as Button
import dev.t1m3.qplayer.android.md3eui.Md3eAdaptiveFilledIconButton as FilledIconButton

import dev.t1m3.qplayer.android.ui.IosAwareAlertDialog as AlertDialog
import dev.t1m3.qplayer.android.ui.IosAwareIconButton as IconButton
import dev.t1m3.qplayer.android.ui.IosAwareTextButton as TextButton
import dev.t1m3.qplayer.android.ui.IosAwareSlider as Slider

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Outline
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.t1m3.qplayer.bili.BiliClient

/** Mirrors the original controller's B站 state without creating a second player. */
internal data class BiliState(
    val loggedIn: Boolean = false,
    val playing: Boolean = false,
    val bvid: String = "",
    val queueVideo: Boolean = false,
    val queueMode: String = "RELATED",
    val queueTargetMode: String = "",
    val queueHasCollection: Boolean = false,
    val queueSwitching: Boolean = false,
    val qrUrl: String = "",
    val qrStatus: Int = 0,
    val error: String = "",
    val chapters: List<Float> = emptyList(),
    val folders: List<BiliClient.BiliFavFolder> = emptyList(),
    val foldersLoading: Boolean = false,
    val favError: String = "",
    val items: List<BiliClient.BiliFavItem> = emptyList(),
    val itemsLoading: Boolean = false,
    val folderTitle: String = "",
)

@Composable
internal fun Md3eBiliVideoRow(video: BiliClient.BiliVideo, onEnqueue: () -> Unit, onPlay: () -> Unit) {
    BiliItemRow(video.title, video.author,
        "${video.durationText.ifBlank { biliTime(video.durationSeconds * 1000) }} · ${biliCount(video.playCount)}播放",
        video.coverUrl, onEnqueue, onPlay)
}

@Composable
private fun BiliItemRow(title: String, author: String, description: String, cover: String,
    onEnqueue: () -> Unit, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().songQueueMenu(title, onPlay, onEnqueue).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Artwork(cover, Modifier.width(116.dp).height(72.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(description, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun Md3eBiliLoginDialog(runtime: Md3eRuntime, onDismiss: () -> Unit) {
    val state = runtime.bili
    LaunchedEffect(Unit) { runtime.controller.startBiliLogin() }
    DisposableEffect(Unit) { onDispose { runtime.controller.cancelBiliLogin() } }
    val bitmap = remember(state.qrUrl) {
        state.qrUrl.takeIf { it.isNotBlank() }?.let { url -> runCatching {
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(url,
                com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
            }
            android.graphics.Bitmap.createBitmap(pixels, matrix.width, matrix.height,
                android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("登录哔哩哔哩") }, text = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.qrStatus) {
                3 -> Text("登录成功")
                4 -> Text(state.error.ifBlank { "二维码已过期，请重新获取" }, color = MaterialTheme.colorScheme.error)
                else -> {
                    if (bitmap != null) Image(bitmap, "B站登录二维码", Modifier.size(220.dp))
                    else CircularProgressIndicator()
                    Text(if (state.qrStatus == 2) "已扫码，请在手机上确认" else "用哔哩哔哩 App 扫描二维码")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(if (state.qrStatus == 3) "完成" else "取消") } },
        dismissButton = { if (state.qrStatus == 4) TextButton(onClick = runtime.controller::startBiliLogin) { Text("重新获取") } })
}

@Composable
internal fun Md3eBiliFavFoldersPage(runtime: Md3eRuntime, onLogin: () -> Unit,
    onOpen: (Long, String) -> Unit, modifier: Modifier = Modifier, onOpenPlayer: () -> Unit = {}) {
    val state = runtime.bili
    LaunchedEffect(state.loggedIn) { if (state.loggedIn) runtime.controller.loadBiliFavFolders(0L) }
    LazyColumn(modifier, contentPadding = PaddingValues(top = iosPageTopPadding(), bottom = 24.dp + LocalMd3eDockInset.current)) {
        item {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("B站收藏夹", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { runtime.controller.loadBiliFavFolders(0L) }, enabled = state.loggedIn && !state.foldersLoading) {
                    Icon(Md3eIcons.Refresh, "刷新收藏夹")
                }
            }
        }
        if (!state.loggedIn) item {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("登录后查看 B站收藏夹")
                Button(onClick = onLogin) { Text("登录哔哩哔哩") }
            }
        } else if (state.foldersLoading) item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (state.folders.isEmpty()) item {
            Text(state.favError.ifBlank { "这个账号还没有收藏夹" }, Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(if (state.loggedIn) state.folders else emptyList(), key = { it.mediaId }) { folder ->
            ListItem(headlineContent = { Text(folder.title.ifBlank { "未命名收藏夹" }) },
                supportingContent = { Text("${folder.mediaCount} 个视频${if (folder.privateFolder) " · 私密" else ""}") },
                leadingContent = { Icon(Md3eIcons.Playlist, null) },
                trailingContent = { IconButton(onClick = { runtime.play { playBiliFavFolder(folder) }; onOpenPlayer() }) {
                    Icon(Md3eIcons.PlayArrow, "播放收藏夹")
                } }, modifier = Modifier.clickable { onOpen(folder.mediaId, folder.title) })
        }
    }
}

@Composable
internal fun Md3eBiliFavItemsPage(runtime: Md3eRuntime, id: Long, title: String,
    modifier: Modifier = Modifier, onOpenPlayer: () -> Unit = {}) {
    LaunchedEffect(id) { runtime.controller.loadBiliFavItems(id, title) }
    val state = runtime.bili
    LazyColumn(modifier, contentPadding = PaddingValues(top = iosPageTopPadding(), bottom = 24.dp + LocalMd3eDockInset.current)) {
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.folderTitle.ifBlank { title }, style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { runtime.play { playBiliFavItemAt(0) }; onOpenPlayer() }, enabled = state.items.isNotEmpty()) {
                    Icon(Md3eIcons.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("播放全部 · ${state.items.size} 个视频")
                }
            }
        }
        if (state.itemsLoading) item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        if (!state.itemsLoading && state.items.isEmpty()) item {
            Text(state.favError.ifBlank { "收藏夹中没有视频" }, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        itemsIndexed(state.items, key = { index, item -> "$index-${item.bvid}" }) { index, item ->
            BiliItemRow(item.title, item.author, biliTime(item.durationSeconds * 1000), item.coverUrl,
                onEnqueue = {
                    runtime.controller.enqueueBiliFavorite(item)
                }) {
                runtime.play { playBiliFavItemAt(index) }
                onOpenPlayer()
            }
        }
    }
}

@Composable
internal fun Md3eBiliFavPickerDialog(runtime: Md3eRuntime, onDismiss: () -> Unit) {
    val state = runtime.bili
    LaunchedEffect(state.bvid) { runtime.controller.loadBiliFavFoldersForBvid(state.bvid) }
    val original = state.folders.filter { it.containsItem }.map { it.mediaId }.toSet()
    var selected by remember(original) { mutableStateOf(original) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("收藏到 B站收藏夹") }, text = {
        when {
            state.foldersLoading -> CircularProgressIndicator()
            state.folders.isEmpty() -> Text(state.favError.ifBlank { "没有可用的收藏夹" })
            else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(state.folders, key = { it.mediaId }) { folder ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (folder.mediaId in selected) selected - folder.mediaId else selected + folder.mediaId
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = folder.mediaId in selected, onCheckedChange = null)
                        Text(folder.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }, confirmButton = {
        TextButton(enabled = !state.foldersLoading && state.bvid.isNotBlank(), onClick = {
            val add = (selected - original).toList()
            val remove = (original - selected).toList()
            if (add.isNotEmpty() || remove.isNotEmpty()) runtime.controller.setBiliFavs(state.bvid, add, remove)
            onDismiss()
        }) { Text("确定") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** A slot reports the wanted rectangle; the single root surface never changes owner. */
@Composable
internal fun Md3eVideoSlot(runtime: Md3eRuntime, modifier: Modifier = Modifier, priority: Int = 1) {
    DisposableEffect(runtime, priority) { onDispose { runtime.videoSlots.remove(priority) } }
    Box(modifier.onGloballyPositioned { runtime.videoSlots[priority] = it.boundsInWindow() })
}

/** Keep this as the final, always-composed child of the root Box, including audio pages.
 * Resizing and parking the original SurfaceView reproduces the old player's no-replay
 * handoff between details and immersive fullscreen. A parked view retains its
 * surface for foreground navigation; the backend detaches it in the background. */
@Composable
internal fun Md3eVideoLayer(runtime: Md3eRuntime, obscured: Boolean = false) {
    val density = LocalDensity.current
    val activity = biliActivity(LocalContext.current)
    val fullscreen = runtime.videoFullscreen && runtime.bili.playing && !obscured
    DisposableEffect(runtime) {
        onDispose { runtime.audioBackend.setVideoPlaybackVisible(false) }
    }
    LaunchedEffect(obscured) { if (obscured) runtime.videoFullscreen = false }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BackHandler(fullscreen) { runtime.videoFullscreen = false }
    DisposableEffect(fullscreen, activity) {
        val previousOrientation = activity?.requestedOrientation
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousBarsBehavior = bars?.systemBarsBehavior
        if (fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (fullscreen) {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            if (previousBarsBehavior != null) bars?.systemBarsBehavior = previousBarsBehavior
            if (previousOrientation != null) activity?.requestedOrientation = previousOrientation
        } }
    }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInWindow().topLeft }) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val aspect = if (runtime.videoHeight > 0) runtime.videoWidth.toFloat() / runtime.videoHeight else 16f / 9f
        val slot = runtime.videoSlots.maxByOrNull { it.key }?.value
        val wanted = runtime.uiVisible && runtime.bili.playing && runtime.videoVisible &&
            !obscured && (fullscreen || slot != null)
        // Foreground navigation parks the surface without detaching it: NuPlayer
        // can replay the previous keyframe's audio after setSurface(null)/reattach.
        // `wanted` only controls layout; the lifecycle gate still detaches in background.
        val keepPlaybackSurface = runtime.uiVisible && runtime.bili.playing
        SideEffect { runtime.audioBackend.setVideoPlaybackVisible(keepPlaybackSurface) }
        val target = when {
            !wanted -> Rect(-width - 64f, 0f, -width - 63f, 1f)
            fullscreen -> {
                val availableW = (width - with(density) { 48.dp.toPx() }).coerceAtLeast(1f)
                val pictureW = minOf(availableW, height * aspect)
                val pictureH = pictureW / aspect
                Rect((width - pictureW) / 2f, (height - pictureH) / 2f,
                    (width + pictureW) / 2f, (height + pictureH) / 2f)
            }
            else -> slot!!.translate(-origin)
        }
        if (fullscreen) Box(Modifier.fillMaxSize().background(Color.Black))
        val radius = if (fullscreen) 0f else with(density) { 32.dp.toPx() }
        Box(Modifier.absoluteOffset(with(density) { target.left.toDp() }, with(density) { target.top.toDp() })
            .size(with(density) { target.width.toDp() }, with(density) { target.height.toDp() })
            .then(if (fullscreen || !wanted) Modifier else Modifier.pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { runtime.videoFullscreen = true })
            })) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                FrameLayout(context).apply {
                    val surface = SurfaceView(context)
                    surface.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { runtime.audioBackend.attachVideoSurface(holder.surface) }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            runtime.audioBackend.attachVideoSurface(holder.surface)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) { runtime.audioBackend.detachVideoSurface(holder.surface) }
                    })
                    addView(surface, FrameLayout.LayoutParams(-1, -1))
                }
            }, update = { holder ->
                clipBiliSurface(holder, radius)
                for (index in 0 until holder.childCount) clipBiliSurface(holder.getChildAt(index), radius)
            })
            if (wanted && runtime.playback.loading) CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(48.dp), color = Color.White)
        }
        if (fullscreen) BiliFullscreenControls(runtime)
    }
}

@Composable
private fun BiliFullscreenControls(runtime: Md3eRuntime) {
    var visible by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        detectTapGestures(onTap = { visible = !visible }, onDoubleTap = { runtime.videoFullscreen = false })
    }) {
        if (visible) {
            IconButton(onClick = { runtime.videoFullscreen = false }, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
                Icon(Md3eIcons.Close, "退出全屏", tint = Color.White)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(onClick = runtime::toggle, modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = .18f), contentColor = Color.White)) {
                    Icon(if (runtime.playback.playing) Md3eIcons.Pause else Md3eIcons.PlayArrow, "播放/暂停")
                }
                BiliProgress(runtime, foreground = Color.White)
            }
        }
    }
}

@Composable
internal fun BiliProgress(runtime: Md3eRuntime, foreground: Color = MaterialTheme.colorScheme.primary) {
    val state = runtime.playback
    val duration = state.duration.coerceAtLeast(1L)
    var dragging by remember(state.playbackRevision) { mutableStateOf(false) }
    var fraction by remember(state.playbackRevision) { mutableFloatStateOf(0f) }
    val position = if (dragging) fraction else (state.position.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Slider(value = position, onValueChange = { dragging = true; fraction = it },
                onValueChangeFinished = { runtime.play { seek((duration * fraction).toLong()) }; dragging = false },
                colors = SliderDefaults.colors(thumbColor = foreground, activeTrackColor = foreground,
                    inactiveTrackColor = foreground.copy(alpha = .25f)))
            Canvas(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp)) {
                runtime.bili.chapters.forEach { mark ->
                    val x = size.width * mark.coerceIn(0f, 1f)
                    drawLine(foreground.copy(alpha = .65f), Offset(x, size.height / 2f - 6.dp.toPx()),
                        Offset(x, size.height / 2f + 6.dp.toPx()), 2.dp.toPx())
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(biliTime(if (dragging) (duration * fraction).toLong() else state.position), color = foreground,
                style = MaterialTheme.typography.labelSmall)
            Text(biliTime(state.duration), color = foreground, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private class BiliOutline(var radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, radius) }
}

private fun clipBiliSurface(view: View, radius: Float) {
    val provider = view.outlineProvider as? BiliOutline
    if (provider == null) view.outlineProvider = BiliOutline(radius)
    else if (provider.radius != radius) { provider.radius = radius; view.invalidateOutline() }
    view.clipToOutline = radius > 0f
}

private fun biliActivity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> biliActivity(context.baseContext)
    else -> null
}

private fun biliTime(ms: Long): String = "${ms.coerceAtLeast(0) / 60000}:${((ms.coerceAtLeast(0) / 1000) % 60).toString().padStart(2, '0')}"
private fun biliCount(count: Long): String = if (count >= 10000) "${count / 10000}.${count / 1000 % 10}万" else count.toString()
