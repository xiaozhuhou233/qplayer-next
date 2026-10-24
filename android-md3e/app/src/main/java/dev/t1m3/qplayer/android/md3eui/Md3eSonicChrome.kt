package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.t1m3.qplayer.android.md3eui.sonic.*

internal val LocalSonicDesign = staticCompositionLocalOf { false }

private val sonicLightColors by lazy { materialArtworkColorScheme("#6750A4", false) }
private val sonicDarkColors by lazy { materialArtworkColorScheme("#6750A4", true) }

internal fun sonicColorScheme(dark: Boolean): ColorScheme = if (dark) sonicDarkColors else sonicLightColors

internal object SonicIcons {
    val Shuffle = glyph("SonicShuffle", "M16,3h5v5l-1.8,-1.8L14,11.4l-1.4,-1.4l5.2,-5.2zM3,5h3l12,14l1.2,-1.2L21,16v5h-5l1.5,-1.5L5,7H3zM3,17h3l3.6,-4.2l1.3,1.5L7,19H3z")
    val Info = glyph("SonicInfo", "M12,2a10,10 0,1 0,0,20a10,10 0,0 0,0,-20zM11,6h2v2h-2zM11,10h2v8h-2z")
    val Equalizer = glyph("SonicEqualizer", "M4,10h3v10H4zM10.5,4h3v16h-3zM17,7h3v13h-3z")
    val Tune = glyph("SonicTune", "M3,6h10V4h2v2h6v2h-6v2h-2V8H3zM3,16h4v-2h2v2h12v2H9v2H7v-2H3z")
    val Heart = glyph("SonicHeart", "M12,21l-1.45,-1.32C5.4,15 2,11.9 2,8.1A5.5,5.5 0,0 1,12,4.8A5.5,5.5 0,0 1,22,8.1c0,3.8 -3.4,6.9 -8.55,11.58z")
    val Bookmark = glyph("SonicBookmark", "M5,3h14v19l-7,-4l-7,4zM7,5v13.55l5,-2.85l5,2.85V5z")
    val BookmarkFilled = glyph("SonicBookmarkFilled", "M5,3h14v19l-7,-4l-7,4z")
    val More = glyph("SonicMore", "M12,4a2,2 0,1 0,0,4a2,2 0,0 0,0,-4zM12,10a2,2 0,1 0,0,4a2,2 0,0 0,0,-4zM12,16a2,2 0,1 0,0,4a2,2 0,0 0,0,-4z")
    val Lyrics = glyph("SonicLyrics", "M4,3h16v18H4zM6,5v14h12V5zM8,7h8v2H8zM8,11h8v2H8zM8,15h5v2H8z")
    val Download = glyph("SonicDownload", "M11,3h2v9h4l-5,5l-5,-5h4zM4,18h16v3H4z")
    val Plus = glyph("SonicPlus", "M11,4h2v7h7v2h-7v7h-2v-7H4v-2h7z")
    private fun glyph(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()
}

@Composable
internal fun SonicTopBar(tab: String, detail: String, title: String, onBack: () -> Unit,
    onQueue: () -> Unit, onProfile: () -> Unit, onRecognize: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (detail.isNotEmpty() || tab == "local") Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            IconButton(onClick = if (detail.isNotEmpty() || tab == "local") onBack else onRecognize,
                modifier = Modifier.size(38.dp)) {
                Icon(if (detail.isNotEmpty() || tab == "local") Md3eIcons.Back else SonicIcons.Equalizer,
                    if (detail.isNotEmpty() || tab == "local") "返回" else "听歌识曲", Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        val heading = if (detail == "artist") "ARTIST SPOTLIGHT" else if (detail.isNotEmpty())
            title.ifBlank { if (detail == "settings") "设置" else "歌单" }
            else when (tab) { "search" -> "Explore"; "playlists" -> "Library"; "profile" -> "我的"; "local" -> "本地音乐"; else -> "QPlayer" }
        val face = if (detail == "artist") SonicDisplayFace.Unkempt else SonicDisplayFace.CherryBomb
        SonicDisplayText(heading, face, Modifier.weight(1f),
            maxSize = if (detail == "artist") 18.sp else if (detail.isEmpty()) 30.sp else 23.sp,
            minSize = if (detail == "artist") 12.sp else 18.sp,
            color = if (detail.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onQueue, modifier = Modifier.size(44.dp)) {
                    Icon(Md3eIcons.Playlist, "播放队列", Modifier.size(23.dp))
                }
                IconButton(onClick = onProfile, modifier = Modifier.size(44.dp)) {
                    Icon(SonicIcons.Tune, "我的与设置", Modifier.size(23.dp))
                }
            }
        }
    }
}

@Composable
internal fun SonicNavigationBar(selected: String, onSelect: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp, vertical = 6.dp)) {
            listOf(Triple("home", Md3eIcons.Home, "Home"), Triple("search", Md3eIcons.Search, "Explore"),
                Triple("playlists", Md3eIcons.Library, "Library"), Triple("profile", Md3eIcons.Person, "我的"))
                .forEach { (route, icon, text) ->
                    val active = selected == route || (route == "playlists" && selected == "local")
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).clickable { onSelect(route) }
                        .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(56.dp, 30.dp).clip(CircleShape).background(
                            if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                            contentAlignment = Alignment.Center) {
                            Icon(icon, null, Modifier.size(22.dp), tint = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(text, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
        }
    }
}

@Composable
internal fun SonicChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, text ->
            Surface(onClick = { onSelect(index) }, shape = CircleShape,
                color = if (index == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (index == selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
                Text(text, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun SonicSectionTitle(title: String, subtitle: String = "", action: String = "", onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action.isNotBlank()) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(action, style = MaterialTheme.typography.labelMedium)
            Icon(Md3eIcons.ChevronRight, null, Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun SonicSearchField(query: String, onChange: (String) -> Unit, onSubmit: () -> Unit, onRecognize: () -> Unit,
    modifier: Modifier = Modifier) {
    OutlinedTextField(value = query, onValueChange = onChange, modifier = modifier.fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 54.dp), singleLine = true,
        shape = CircleShape, textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text("探索你的下一个声音…", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { IconButton(onClick = onSubmit) { Icon(Md3eIcons.Search, "搜索", Modifier.size(22.dp)) } },
        trailingIcon = {
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (query.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(32.dp)) { Icon(Md3eIcons.Close, "清空搜索", Modifier.size(18.dp)) }
                IconButton(onClick = onRecognize, modifier = Modifier.size(38.dp)) { Icon(Md3eIcons.Headphones, "听歌识曲", Modifier.size(20.dp)) }
                Surface(onClick = onSubmit, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { Icon(SonicIcons.Equalizer, "开始搜索", Modifier.size(20.dp)) }
                }
            }
        }, colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSubmit() }))
}

