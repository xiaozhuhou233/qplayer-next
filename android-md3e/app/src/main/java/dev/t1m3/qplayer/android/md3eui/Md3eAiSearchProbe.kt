package dev.t1m3.qplayer.android.md3eui

import dev.t1m3.qplayer.android.ui.IosAwareAlertDialog as AlertDialog
import dev.t1m3.qplayer.android.ui.IosAwareTextButton as TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.t1m3.qplayer.ai.WebLookup
import dev.t1m3.qplayer.ai.DailyMusicContext
import java.time.LocalDate
import kotlinx.coroutines.*

@Composable
internal fun Md3eAiSearchProbeDialog(runtime: Md3eRuntime, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("音乐新闻 新专辑 ${LocalDate.now()}") }
    var status by remember { mutableStateOf("") }
    var sources by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun test(daily: Boolean) {
        running = true; status = if (daily) "正在检查天气与资讯…" else "正在联网搜索…"; sources = ""
        scope.launch {
            val start = android.os.SystemClock.elapsedRealtime()
            try {
                if (daily) {
                    val material = runtime.liveEvidence("今日天气与音乐事件", daily = true)
                    status = DailyMusicContext.status(material)
                    sources = org.json.JSONObject(material).toString(2)
                } else {
                    val settings = runtime.settings
                    val result = withContext(Dispatchers.IO) {
                        WebLookup.lookup(settings.str("aiWebSearchUrl"), settings.str("aiWebSearchKey"),
                            settings.str("aiBaseUrl"), settings.str("aiApiKey"), query, 12_000)
                    }
                    status = result.status
                    sources = result.context
                }
                status += " · ${android.os.SystemClock.elapsedRealtime() - start} ms"
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { status = "测试未完成：${failure.javaClass.simpleName}"
            } finally { running = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!running) onDismiss() }, title = { Text("联网搜索测试") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("搜索内容") }, modifier = Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sources.isNotEmpty()) Text(sources, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
        }
    }, confirmButton = {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            TextButton(enabled = !running, onClick = { test(true) }) { Text("天气与资讯诊断") }
            TextButton(enabled = !running && query.isNotBlank(), onClick = { test(false) }) { Text("测试搜索") }
        }
    }, dismissButton = { TextButton(enabled = !running, onClick = onDismiss) { Text("关闭") } })
}
