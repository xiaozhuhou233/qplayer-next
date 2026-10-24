package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.t1m3.qplayer.android.ui.IosAwareAlertDialog as AlertDialog
import dev.t1m3.qplayer.android.ui.IosAwareTextButton as TextButton

@Composable
internal fun Md3eWelcomeDialog(onDone: () -> Unit, onConfigureAi: () -> Unit) {
    AlertDialog(onDismissRequest = onDone, title = { Text("开始使用 QPlayer") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            WelcomeHint("AI DJ", "单击吉祥物进入连续推荐，再点一次退出；每批播放时会提前准备下一批。")
            WelcomeHint("音乐助手", "点击 AI 输入框聊天选歌；长按歌单封面可引用并修改。")
            WelcomeHint("AI 配置", "在设置 → AI 填写 API 地址、Key 和模型，启用完整的 AI 推荐与对话功能。")
            WelcomeHint("位置信息", "仅用前台大致位置匹配当地天气，为天气歌单选曲；不保存坐标，不发送给 AI。拒绝授权也能听歌。")
            WelcomeHint("播放与队列", "点击歌曲播放，点击底部播放条展开详情；歌曲左滑加入播放队列。")
        }
    }, confirmButton = { TextButton(onClick = onDone) { Text("开始使用") } },
        dismissButton = { TextButton(onClick = onConfigureAi) { Text("配置 AI") } })
}

@Composable
private fun WelcomeHint(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp))
    }
}
