package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The editor behind 设置/音效/「均衡器调节」: one slider per band (applied live
 *  through the backend, persisted per change), a bass slider, a reset — and the
 *  backend's own attachment state on top, so "not working" is visible. */
@Composable
internal fun Md3eEqualizerDialog(runtime: Md3eRuntime, onDismiss: () -> Unit) {
    val backend = runtime.audioBackend
    var status by remember { mutableStateOf(backend.eqState()) }
    LaunchedEffect(backend) {
        while (true) {
            status = backend.eqState()
            delay(500)
        }
    }
    val bandCount = backend.eqBandCount()
    val range = backend.eqBandRange()
    val lower = range.getOrElse(0) { -1500 }
    val upper = range.getOrElse(1) { 1500 }
    val centers = backend.eqCenterFreqs()
    var levels by remember { mutableStateOf(runtime.eqBandLevels()) }
    var enabled by remember { mutableStateOf(runtime.settings.bool("eqEnabled")) }
    var bass by remember { mutableIntStateOf(runtime.eqBassStrength()) }

    fun bandLabel(index: Int): String {
        val milliHz = centers.getOrNull(index) ?: return "频段 ${index + 1}"
        val hz = milliHz / 1000
        return if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("均衡器") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用均衡器", Modifier.weight(1f))
                    Switch(enabled, onCheckedChange = {
                        enabled = it
                        runtime.updateSetting("eqEnabled", it)
                        status = backend.eqState()
                    })
                }
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = if (status.contains("已挂载")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text(
                    "频段向右提升、向左衰减；低音滑杆增强 300Hz 以下频段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (!enabled) {
                    Text("均衡器开关当前是关的：滑杆会保存数值，打开「均衡器」开关后生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                repeat(bandCount) { index ->
                    val value = (levels.getOrNull(index) ?: 0)
                        .coerceIn(lower, upper).toFloat()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(bandLabel(index), Modifier.width(56.dp),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center)
                        Slider(
                            value = value,
                            onValueChange = { newValue ->
                                val millibels = newValue.toInt()
                                levels = List(bandCount) { band ->
                                    if (band == index) millibels else levels.getOrElse(band) { 0 }
                                }
                                runtime.setEqBandLevel(index, millibels)
                                status = backend.eqState()
                            },
                            valueRange = lower.toFloat()..upper.toFloat(),
                            modifier = Modifier.weight(1f))
                        Text("${"%.1f".format(value / 100f)}dB", Modifier.width(56.dp),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.End)
                    }
                }
                Spacer(Modifier.height(10.dp))
                // Quick presets: the fastest way to hear that the EQ is doing
                // something at all.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        runtime.applyEqPreset("bass")
                        levels = runtime.eqBandLevels()
                        bass = runtime.eqBassStrength()
                        status = backend.eqState()
                    }) { Text("低音增强") }
                    TextButton(onClick = {
                        runtime.applyEqPreset("vocal")
                        levels = runtime.eqBandLevels()
                        bass = runtime.eqBassStrength()
                        status = backend.eqState()
                    }) { Text("人声突出") }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("低音", Modifier.width(56.dp),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center)
                    Slider(
                        value = bass.toFloat(),
                        onValueChange = { newValue ->
                            bass = newValue.toInt()
                            runtime.setEqBass(bass)
                            status = backend.eqState()
                        },
                        valueRange = 0f..1000f,
                        modifier = Modifier.weight(1f))
                    Text("${bass * 100 / 1000}%", Modifier.width(56.dp),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.End)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        runtime.resetEq()
                        levels = emptyList()
                        bass = 0
                        status = backend.eqState()
                    }) { Text("全部归零") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}
