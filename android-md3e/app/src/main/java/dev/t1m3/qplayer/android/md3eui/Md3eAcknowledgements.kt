package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private data class Acknowledgement(val name: String, val description: String, val url: String)

private val projectAcknowledgements = listOf(
    Acknowledgement("中国天气网", "城市天气实况与逐时预报", "https://www.weather.com.cn/"),
    Acknowledgement("中央气象台", "城市站点天气实况", "https://www.nmc.cn/"),
    Acknowledgement("Open-Meteo", "天气模型与当日预报数据 · CC BY 4.0", "https://open-meteo.com/"),
    Acknowledgement("AndroidLiquidGlass", "液态玻璃效果与 API · iOS mode",
        "https://github.com/Kyant0/AndroidLiquidGlass"),
    Acknowledgement("NetEase API", "网易云音乐接口与请求加密参考",
        "https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced"),
    Acknowledgement("PixelPlayer", "MD3 inspiration · MiniPlayer 动效参考",
        "https://github.com/PixelPlayerHQ/PixelPlayer/releases"),
    Acknowledgement("MusicOne", "Some iOS inspiration",
        "https://github.com/HGODLK/MusicOne/tree/main/"),
    Acknowledgement("PiliPlus", "Bilibili video & MV",
        "https://github.com/bggRGjQaUbCoE/PiliPlus"),
)

private val toolAcknowledgements = listOf(
    Acknowledgement("Google Stitch", "FOR MOST UI DESIGN", "https://stitch.withgoogle.com"),
    Acknowledgement("DeepSeek", "AI development assistance", "https://www.deepseek.com"),
    Acknowledgement("ChatGPT", "AI development assistance", "https://chatgpt.com"),
    Acknowledgement("CLAUDE",
        "FUCK YOU ANTHROPIC BUT ALSO THANKS FOR CLAUDE MODE INSPIRATION",
        "https://claude.ai"),
)

internal fun LazyListScope.md3eAcknowledgements(
    horizontalPadding: Dp = 0.dp,
    onOpenLink: (String) -> Unit,
) {
    listOf("鸣谢" to projectAcknowledgements, "Tools I use" to toolAcknowledgements)
        .forEachIndexed { group, (title, entries) ->
            item("acknowledgements-header-$group") {
                Text(title,
                    Modifier.padding(horizontal = horizontalPadding).padding(top = 12.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            items(entries, key = { "acknowledgement-${it.name}" }) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "打开 ${entry.name}") {
                            onOpenLink(entry.url)
                        }.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.name, style = MaterialTheme.typography.titleSmall)
                            Text(entry.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Md3eIcons.ChevronRight, contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
}
