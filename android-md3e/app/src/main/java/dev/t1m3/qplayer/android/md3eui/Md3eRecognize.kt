// Ⓜ 2026-10-06: the old app's 听歌识曲, migrated to MD3E with the listener's upgrades —
// several candidates after a match（「识别到之后几个歌备选」）, the user previews each one
// in the app and picks（「用户自己试听然后选择」）, and a 「去B站搜索」 action that hands the
// recognised title to the app's Bilibili search. The engine is the old one verbatim:
// AfpFingerprint fingerprints on-device, SongRecognizer listens and asks AudioMatchClient,
// and only the fingerprint ever leaves the phone.
package dev.t1m3.qplayer.android.md3eui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.t1m3.qplayer.android.recognize.AfpFingerprint
import dev.t1m3.qplayer.android.recognize.SongRecognizer
import dev.t1m3.qplayer.netease.AudioMatchClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class RecognizePhase { INTRO, LISTENING, FOUND }

@Composable
internal fun Md3eRecognizeDialogHost(visible: Boolean, onDismiss: () -> Unit,
    onPlayById: (Long) -> Unit, onBiliSearch: (String) -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(RecognizePhase.INTRO) }
    var status by remember { mutableStateOf("") }
    var match by remember { mutableStateOf<AudioMatchClient.Match?>(null) }
    var chosenId by remember { mutableStateOf(0L) }
    var engine by remember { mutableStateOf<AfpFingerprint?>(null) }
    var recognizer by remember { mutableStateOf<SongRecognizer?>(null) }
    var wantStart by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.cancel()
            recognizer = null
            val fp = engine
            engine = null
            if (fp != null) Handler(Looper.getMainLooper()).post { fp.close() }
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) wantStart = true
        else {
            phase = RecognizePhase.INTRO
            status = "没有麦克风权限：在系统设置里允许后再点一次"
        }
    }

    fun stopListening() {
        recognizer?.cancel()
        recognizer = null
    }

    fun beginListening() {
        status = "正在听…把手机靠近音源"
        phase = RecognizePhase.LISTENING
        scope.launch {
            val fp = withContext(Dispatchers.Main) {
                engine ?: AfpFingerprint(context).also { engine = it }
            }
            val listener = object : SongRecognizer.Listener {
                override fun onListening(seconds: Float, note: String?) {
                    scope.launch(Dispatchers.Main) {
                        if (phase == RecognizePhase.LISTENING) {
                            status = note ?: "正在听…%.0f 秒".format(seconds)
                        }
                    }
                }

                override fun onMatching(seconds: Float) {
                    scope.launch(Dispatchers.Main) {
                        if (phase == RecognizePhase.LISTENING) status = "听到了一段，正在识别…"
                    }
                }

                override fun onFound(found: AudioMatchClient.Match) {
                    scope.launch(Dispatchers.Main) {
                        recognizer = null
                        match = found
                        chosenId = 0L
                        phase = RecognizePhase.FOUND
                        status = "识别成功，选一个试听"
                    }
                }

                override fun onFailed(why: String) {
                    scope.launch(Dispatchers.Main) {
                        recognizer = null
                        phase = RecognizePhase.INTRO
                        status = why
                    }
                }
            }
            val rec = SongRecognizer(fp, listener)
            recognizer = rec
            rec.start()
        }
    }

    LaunchedEffect(wantStart) {
        if (wantStart) {
            wantStart = false
            beginListening()
        }
    }

    // The candidate list: the primary match first, then the endpoint's next-best
    // answers, deduplicated by id（「几个歌备选」）.
    val candidates = remember(match) {
        val seen = LinkedHashSet<AudioMatchClient.Match>()
        match?.let { seen.add(it) }
        match?.alternatives?.forEach { alternative ->
            if (seen.none { it.id == alternative.id }) seen.add(alternative)
        }
        seen.toList()
    }

    AlertDialog(
        onDismissRequest = { stopListening(); onDismiss() },
        title = { Text("听歌识曲") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()) {
                Text(
                    "放一段附近的音乐，识别在手机上完成指纹、只把指纹发给网易云，不录音保存，也不上传原始音频。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    when (phase) {
                        RecognizePhase.INTRO -> Unit
                        // Ⓜ The Claude-design spinner replaces the generic loaders
                        // （「把加载动画换成 claude 模式下同款 claude 图标旋转加载动画」）.
                        RecognizePhase.LISTENING, RecognizePhase.FOUND ->
                            SpinningDeformIcon(
                                painter = androidx.compose.ui.res.painterResource(
                                    dev.t1m3.qplayer.android.md3eui.R.drawable.ic_claude_color),
                                contentDescription = null)
                    }
                }
                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
                if (phase == RecognizePhase.FOUND && candidates.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(candidates, key = { it.id }) { candidate ->
                            val chosen = chosenId == candidate.id
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("${candidate.title} — ${candidate.artist}",
                                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium)
                                    if (candidate.album.isNotBlank()) Text(
                                        candidate.album,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = {
                                    // 试听：直接在应用里播放这条候选；不满意就再点下一条。
                                    chosenId = candidate.id
                                    onPlayById(candidate.id)
                                    status = "正在试听：${candidate.title}"
                                }) { Text(if (chosen) "试听中" else "试听") }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        val query = candidates.firstOrNull()?.let { "${it.title} ${it.artist}" }.orEmpty()
                        TextButton(onClick = { onBiliSearch(query.trim()) }) {
                            Text("去B站搜索")
                        }
                        TextButton(onClick = { stopListening(); onDismiss() },
                            enabled = chosenId != 0L) { Text("就是这首") }
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                RecognizePhase.INTRO -> TextButton(onClick = {
                    status = ""
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) wantStart = true
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("开始录音") }

                RecognizePhase.LISTENING -> TextButton(onClick = {
                    stopListening(); phase = RecognizePhase.INTRO; status = "已停止"
                }) { Text("停止") }

                RecognizePhase.FOUND -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = { stopListening(); onDismiss() }) { Text("关闭") }
        }
    )
}

@Composable
private fun ListeningBarsMd3e(color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom) {
        repeat(3) { index ->
            val infinite = rememberInfiniteTransition(label = "recognize_bar_$index")
            val scale by infinite.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(420 + index * 130,
                        easing = androidx.compose.animation.core.LinearEasing)
                ), label = "recognize_bar_scale_$index")
            Box(Modifier.width(6.dp).height(30.dp * scale).background(color,
                androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun MorphLoaderMd3e(color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "recognize_loader")
    val angle by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900,
                easing = androidx.compose.animation.core.LinearEasing)
        ), label = "recognize_loader_angle")
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawArc(color = color, startAngle = angle, sweepAngle = 270f, useCenter = false,
            style = stroke)
    }
}
