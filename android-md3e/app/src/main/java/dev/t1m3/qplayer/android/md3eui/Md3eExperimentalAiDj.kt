package dev.t1m3.qplayer.android.md3eui

import dev.t1m3.qplayer.android.ui.IosAwareTextButton as TextButton

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun ExperimentalAiDjPlaceholder(runtime: Md3eRuntime, onBack: () -> Unit) {
    val stage = runtime.experimentalAiDjStage
    val motion = claudeMotionEnabled()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Spacer(Modifier.weight(1f))
            Text("AI DJ", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text("实验模式", style = MaterialTheme.typography.labelSmall, color = muted)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val mascotSize = minOf(maxWidth, maxHeight * .44f, 320.dp)
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally) {
                // Retain one WebView through all stages; original eyes morph in place.
                Md3eAiMascot(Modifier.size(mascotSize), stage)
                Spacer(Modifier.height(12.dp))
                AnimatedContent(stage, transitionSpec = {
                    fadeIn(tween(if (motion) ClaudeOneTake.PRESS else 0)) togetherWith
                        fadeOut(tween(if (motion) ClaudeOneTake.PRESS else 0))
                }, label = "DJ stage copy") { current ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 86.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(current.title, style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        Text(current.caption, style = MaterialTheme.typography.bodyMedium,
                            color = muted, textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    listOf(AiDjMascotStage.Random to "随机", AiDjMascotStage.Searching to "寻找",
                        AiDjMascotStage.Ready to "准备播放").forEach { (step, label) ->
                        Text(label, style = if (stage == step) MaterialTheme.typography.labelLarge
                            else MaterialTheme.typography.labelMedium,
                            color = if (stage == step) MaterialTheme.colorScheme.onSurface else muted.copy(alpha = .55f))
                    }
                }
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth().height(132.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (runtime.experimentalAiDjReason.isNotBlank()) {
                        Text("这次为什么推荐", style = MaterialTheme.typography.labelMedium, color = muted)
                        Spacer(Modifier.height(10.dp))
                        Text(runtime.experimentalAiDjReason,
                            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            if (stage == AiDjMascotStage.Error) {
                TextButton(onClick = { runtime.play { retryExperimentalAiDj() } }) { Text("重新寻找") }
            }
            TextButton(onClick = { runtime.play { stopExperimentalAiDj() } }) { Text("退出 AI DJ") }
        }
    }
}
