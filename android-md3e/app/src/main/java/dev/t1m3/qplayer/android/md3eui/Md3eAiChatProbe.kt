package dev.t1m3.qplayer.android.md3eui

import dev.t1m3.qplayer.android.ui.IosAwareAlertDialog as AlertDialog
import dev.t1m3.qplayer.android.ui.IosAwareTextButton as TextButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.t1m3.qplayer.ai.AiChatProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ⓜ 设置 → AI 的「AI 对话测试」：输入一句话，直接看当前 API 配置的原始答复。
 *
 * 用户 2026-10-10：「在设置单独给我开一个接入api的聊天ai接口，输入问题然后看到api deep seek 的答复，
 * 我要调试」。这个对话框刻意什么都不加工：
 *   · 一次请求、不重试、不改写问题（`AiChatProbe` 的注释说明了为什么必须是独立通道）；
 *   · 显示**原始** `message.content`——模型自己写的思考、Markdown、多余解释全都照原样给你看；
 *   · 显示 HTTP 状态、耗时、以及实际发出去的模型名——模型名写错是「AI 不出结果」最常见的真因；
 *   · 出错时显示原始响应体（中转站把真实原因写在里面）。
 * 这里只读设置，绝不改动 AI DJ 那条生成路径的任何参数。
 */
@Composable
internal fun Md3eAiChatProbeDialog(runtime: Md3eRuntime, onDismiss: () -> Unit) {
    val settings = runtime.settings
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val base = settings.str("aiBaseUrl")
    val key = settings.str("aiApiKey")
    val model = settings.str("aiModel")
    val timeout = settings.intOf("aiTimeoutMs").takeIf { it > 0 } ?: 60_000

    fun send() {
        val text = question.trim()
        if (text.isEmpty() || running) return
        running = true
        answer = ""
        status = "正在请求…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AiChatProbe.ask(base, key, model, timeout, "", text)
            }
            running = false
            status = buildString {
                append(if (result.ok) "成功" else "失败")
                append(" · ${result.elapsedMs} ms")
                if (result.httpStatus != 0) append(" · HTTP ${result.httpStatus}")
            }
            answer = result.text
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("AI 对话测试", fontWeight = FontWeight.Medium) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("模型：${model.ifBlank { "（未设置）" }}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("地址：${base.ifBlank { "（未设置）" }}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (key.isBlank()) {
                    Text("Key：未设置（请求会被拒绝）", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(question, { question = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入要问的问题，例如：你好 / 推荐一首歌") },
                    minLines = 2, maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }))
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                if (answer.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("原始答复：", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(answer, fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()))
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { send() }, enabled = !running) {
                    Text(if (running) "请求中…" else "发送")
                }
                TextButton(onClick = onDismiss, enabled = !running) { Text("关闭") }
            }
        },
    )
}
