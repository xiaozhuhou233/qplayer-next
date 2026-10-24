package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class SonicCardAccent(val fill: Color, val ink: Color)
/** Cards use the same Material roles and cover seed as their destination page. */
@Composable
internal fun rememberSonicCardAccent(cover: String): SonicCardAccent {
    val colors = rememberSonicArtworkColorScheme(cover)
    return SonicCardAccent(colors.primaryContainer, colors.onPrimaryContainer)
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
