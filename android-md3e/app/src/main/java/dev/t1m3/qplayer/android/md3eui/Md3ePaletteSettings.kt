package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.t1m3.qplayer.color.MaterialArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PaletteSwatch(val colors: List<Color>)

@Composable
private fun rememberPaletteSwatches(runtime: Md3eRuntime, variants: List<Pair<Int, Int>>): List<PaletteSwatch> {
    val seed = if (runtime.settings.bool("monet")) runtime.playback.coverSeed else "#6750a4"
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val swatches by produceState(emptyList<PaletteSwatch>(), seed, dark, variants) {
        value = withContext(Dispatchers.Default) {
            variants.map { (style, preset) ->
                val scheme = MaterialArtworkPalette.fromSeed(seed, dark, style, preset)
                PaletteSwatch(listOf(Color(scheme.primary), Color(scheme.primaryContainer),
                    Color(scheme.secondaryContainer), Color(scheme.tertiaryContainer)))
            }
        }
    }
    return swatches
}

@Composable
internal fun SonicPaletteStylePicker(runtime: Md3eRuntime, labels: List<String>) {
    val selected = runtime.settings.intOf("paletteStyle")
    val preset = runtime.settings.intOf("palettePreset")
    val swatches = rememberPaletteSwatches(runtime, labels.indices.map { it to preset })
    FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            PaletteOption(label, selected == index, swatches.getOrNull(index), Modifier.weight(1f)) {
                runtime.updateSetting("paletteStyle", index)
            }
        }
    }
}

@Composable
internal fun SonicPalettePresetPicker(runtime: Md3eRuntime, labels: List<String>) {
    val selected = runtime.settings.intOf("palettePreset")
    val style = runtime.settings.intOf("paletteStyle")
    val swatches = rememberPaletteSwatches(runtime, labels.indices.map { style to it })
    FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            PaletteOption(label, selected == index, swatches.getOrNull(index), Modifier.weight(1f)) {
                runtime.updateSetting("palettePreset", index)
            }
        }
    }
}

@Composable
private fun PaletteOption(label: String, selected: Boolean, swatch: PaletteSwatch?,
    modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.secondaryContainer else colors.surfaceContainer,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        border = BorderStroke(1.dp, if (selected) colors.outline else colors.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (swatch?.colors ?: listOf(colors.primary, colors.primaryContainer,
                    colors.secondaryContainer, colors.tertiaryContainer)).forEach { color ->
                    Box(Modifier.weight(1f).height(16.dp).background(color, CircleShape))
                }
                if (selected) Icon(Md3eIcons.CheckCircle, "已选中", Modifier.size(16.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
