@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.t1m3.qplayer.android.md3eui

import dev.t1m3.qplayer.android.ui.IosAwareAlertDialog as AlertDialog
import dev.t1m3.qplayer.android.ui.IosAwareTextButton as TextButton

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The old player rendered the NetEase QR in its own dialog and polled the
 * controller while that dialog was alive. Keep the same lifecycle in MD3E:
 * closing it cancels only the polling UI, while a successful scan is committed
 * by the old NetEase client's encrypted cookie store. */
@Composable
internal fun Md3eNeteaseLoginDialog(runtime: Md3eRuntime, onListening: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val state = runtime.neteaseLogin
    val initialSuccess = remember { state.successRevision }

    LaunchedEffect(Unit) {
        runtime.controller.clearWebLoginError()
        runtime.controller.startQrLogin()
        while (true) {
            delay(800L)
            runtime.controller.pollQrLogin()
        }
    }
    DisposableEffect(Unit) {
        onDispose { runtime.controller.cancelQrLogin() }
    }
    LaunchedEffect(runtime.home.loggedIn, state.successRevision) {
        if (runtime.home.loggedIn || state.successRevision > initialSuccess) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Md3eIcons.Person, contentDescription = null) },
        title = { Text("登录网易云音乐") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(220.dp)
                        .background(Color.White, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.qrStatus == 800 && state.qrImage.isEmpty()) {
                        Text("二维码获取失败", color = Color.Black)
                    } else if (state.qrImage.isEmpty() || state.qrStatus == 0) {
                        CircularProgressIndicator()
                    } else {
                        NeteaseQrImage(state.qrImage)
                    }
                }
                Text(
                    when (state.qrStatus) {
                        802 -> "已扫码，请在手机上确认"
                        803 -> "登录成功"
                        800 -> if (state.error.isBlank()) "二维码已过期，正在刷新…" else "请刷新二维码重试"
                        0 -> "正在获取二维码…"
                        else -> "请使用网易云音乐 App 扫码登录"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = if (state.qrStatus == 800) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.error.isNotBlank()) {
                    Text(state.error, color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { runtime.controller.startQrLogin() }) { Text("刷新二维码") }
        },
        dismissButton = {
            Row {
                if (onListening != null) TextButton(onClick = onListening) { Text("听歌足迹") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun NeteaseQrImage(matrix: List<List<Boolean>>) {
    val moduleCount = matrix.size
    if (moduleCount == 0) return
    Canvas(Modifier.size(200.dp).background(Color.White)) {
        val cell = size.width / moduleCount.toFloat()
        matrix.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (dark) drawRect(
                    color = Color.Black,
                    topLeft = Offset(x * cell, y * cell),
                    size = Size(cell + .5f, cell + .5f),
                )
            }
        }
    }
}
