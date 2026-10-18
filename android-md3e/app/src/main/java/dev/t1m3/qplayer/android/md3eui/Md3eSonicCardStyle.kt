package dev.t1m3.qplayer.android.md3eui

import android.util.LruCache
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.t1m3.qplayer.android.graphics.AndroidColorExtractor
import dev.t1m3.qplayer.color.MaterialExpressivePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SonicCardAccent(val fill: Color, val ink: Color)
private val cardSeedCache = LruCache<String, String>(96)
private val cardAccentCache = LruCache<String, SonicCardAccent>(192)

/** Independent cover accent: neither its source nor fallback follows the playing song. */
@Composable
internal fun rememberSonicCardAccent(cover: String): SonicCardAccent {
    val source = remember(cover) { artworkRequestSource(cover) }
    val context = LocalContext.current.applicationContext
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return key(source, dark) {
        val cacheKey = "$source:$dark"
        val fallback = remember(source, dark) { cardAccentCache.get(cacheKey) ?: sonicColorScheme(dark).let {
            SonicCardAccent(it.primaryContainer, it.onPrimaryContainer)
        } }
        val accent by produceState(initialValue = fallback, source, dark) {
            val seed = cardSeedCache.get(source) ?: if (source.isNotBlank()) {
                val bitmap = awaitArtworkBitmap(context, source)
                withContext(Dispatchers.Default) { AndroidColorExtractor().dominantHex(bitmap) }
                    ?.also { cardSeedCache.put(source, it) }
            } else null
            if (seed != null) value = cardAccentCache.get(cacheKey) ?: withContext(Dispatchers.Default) {
                sonicAccent(seed, dark)
            }.also { cardAccentCache.put(cacheKey, it) }
        }
        accent
    }
}

private fun sonicAccent(seed: String, dark: Boolean): SonicCardAccent {
    val palette = MaterialExpressivePalette.fromSeed(seed, dark)
    return SonicCardAccent(Color(palette.primaryContainer), Color(palette.onPrimaryContainer))
}

/** Boxlore's split capsule: large play segment + compact bookmark segment, 3dp seam. */
@Composable
internal fun SonicPlaylistActions(accent: SonicCardAccent, onPlay: () -> Unit, onSave: () -> Unit,
    modifier: Modifier = Modifier, label: String = "播放歌单", saved: Boolean = false,
    enabled: Boolean = true, saveEnabled: Boolean = true) {
    val colors = ButtonDefaults.buttonColors(containerColor = accent.fill, contentColor = accent.ink,
        disabledContainerColor = accent.fill.copy(alpha = .5f), disabledContentColor = accent.ink.copy(alpha = .6f))
    Row(modifier.fillMaxWidth().height(50.dp), horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onPlay, enabled = enabled, modifier = Modifier.weight(1f).fillMaxHeight(),
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 7.dp, bottomEnd = 7.dp),
            colors = colors, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Md3eIcons.PlayArrow, null, Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(onClick = onSave, enabled = enabled && saveEnabled, modifier = Modifier.width(50.dp).fillMaxHeight(),
            shape = RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp, topEnd = 28.dp, bottomEnd = 28.dp),
            colors = colors, contentPadding = PaddingValues(0.dp)) {
            Icon(if (saved) SonicIcons.BookmarkFilled else SonicIcons.Bookmark,
                if (saved) "已收藏歌单" else "收藏歌单", Modifier.size(22.dp))
        }
    }
}
