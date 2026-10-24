package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.t1m3.qplayer.android.md3eui.sonic.ArtistCloudShape
import dev.t1m3.qplayer.android.md3eui.sonic.SyneFontFamily
import dev.t1m3.qplayer.netease.dto.NeteaseSong

/** The reference's Artist Spotlight composition, with real artist songs and albums. */
@Composable
internal fun SonicArtistDetail(state: ArtistDetailState, playback: PlaybackState?, onAlbum: (Long) -> Unit,
    onPlay: PlayAction, onEnqueue: (NeteaseSong) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    var biography by remember(state.id) { mutableStateOf(false) }
    val silhouetteSeed = rememberSaveable(state.id) { kotlin.random.Random.nextInt() }
    val flower = remember(silhouetteSeed) { ArtistCloudShape(silhouetteSeed) }
    val heroImage = state.header.ifBlank { state.cover }
    Box(modifier.background(colors.surface)) {
        Box(Modifier.fillMaxWidth().height(380.dp).background(Brush.radialGradient(
            listOf(colors.primaryContainer.copy(alpha = .25f), colors.surface.copy(alpha = 0f)))))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 10.dp,
            bottom = 28.dp + LocalMd3eDockInset.current), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("artist_hero") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.widthIn(max = 440.dp).fillMaxWidth().aspectRatio(1.75f).shadow(8.dp, flower).clip(flower)
                        .background(colors.primaryContainer),
                        contentAlignment = Alignment.Center) {
                        if (heroImage.isNotBlank()) Artwork(heroImage, Modifier.fillMaxSize(), corner = 0.dp,
                            requestSize = 1024, alignment = BiasAlignment(0f, -.35f))
                        else Icon(Md3eIcons.Person, null, Modifier.size(88.dp), tint = colors.onPrimaryContainer)
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = CircleShape, color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(6.dp).background(colors.onSecondaryContainer, CircleShape))
                            Text("ARTIST SPOTLIGHT", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SonicDisplayText(state.name.ifBlank { "Artist" }, sonicArtistFace(state.name),
                        Modifier.fillMaxWidth(), maxSize = 48.sp, minSize = 22.sp, maxLines = 3,
                        color = colors.primary, centered = true)
                    Text(if (state.loading) "正在整理歌手作品…" else "${state.songs.size} 首热门歌曲 · ${state.albums.size} 张专辑",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SonicArtistPuck(SonicIcons.Shuffle, "随机播放", "SHUFFLE", state.songs.isNotEmpty()) {
                            onPlay { playArtistSong(state.songs.indices.random()) }
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = { onPlay { playArtistSong(0) } }, enabled = state.songs.isNotEmpty(), shape = CircleShape,
                                modifier = Modifier.fillMaxWidth().height(54.dp).shadow(8.dp, CircleShape)) {
                                Icon(Md3eIcons.PlayArrow, "播放歌手歌曲", Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                                Text("PLAY", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                            Text("播放", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                                color = colors.onSurfaceVariant)
                        }
                        SonicArtistPuck(SonicIcons.Info, "歌手简介", "ABOUT", state.description.isNotBlank()) { biography = true }
                    }
                }
            }
            if (state.albums.isNotEmpty()) {
                item("curated_heading") {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp)) {
                        Text("CURATED SERIES", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp, color = colors.primary)
                        Text("Immersive Spotlight", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
                item("curated_albums") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(state.albums.distinctBy { it.id }, key = { it.id }) { album ->
                            Md3eCollectionContainer("cover:album:${album.id}", corner = 24.dp) {
                                Card(Modifier.width(280.dp).height(220.dp).md3eSharedCover("cover:album:${album.id}")
                                    .clickable { onAlbum(album.id) },
                                    shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)) {
                                    Box(Modifier.fillMaxSize()) {
                                        Artwork(album.coverThumbPath ?: album.coverUrl.orEmpty(), Modifier.fillMaxSize(), corner = 24.dp)
                                        Box(Modifier.fillMaxSize().background(colors.surface.copy(alpha = .84f)))
                                        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                            SonicTag("ARTIST COLLECTION", colors.secondaryContainer, colors.onSecondaryContainer)
                                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("THE ALBUM", fontFamily = SyneFontFamily, fontSize = 12.sp, letterSpacing = 4.sp,
                                                    color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
                                                Text(album.name.orEmpty(), fontFamily = SyneFontFamily, fontSize = 28.sp, lineHeight = 32.sp,
                                                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(album.artistName.orEmpty().ifBlank { state.name }, Modifier.weight(1f),
                                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Icon(Md3eIcons.ChevronRight, null, Modifier.size(18.dp), tint = colors.primary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.songs.isNotEmpty()) {
                item("popular_heading") {
                    Text("Popular Tracks", Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                itemsIndexed(state.songs, key = { index, song -> "artist:$index:${song.id}" }) { index, song ->
                    SonicArtistTrack(song, index + 1, playback?.songId == song.id && song.id > 0,
                        playback?.playing == true, { onEnqueue(song) }) { onPlay { playArtistSong(index) } }
                }
            }
            if (state.loading) item("loading") { SonicStatus("正在加载歌手…", true) }
            if (!state.loading && state.songs.isEmpty() && state.albums.isEmpty()) item("empty") { SonicStatus("暂时没有歌手作品") }
        }
    }
    if (biography) AlertDialog(onDismissRequest = { biography = false },
        title = { Text("${state.name} · 简介") },
        text = { LazyColumn(Modifier.heightIn(max = 380.dp)) { item { Text(state.description, style = MaterialTheme.typography.bodyMedium) } } },
        confirmButton = { TextButton(onClick = { biography = false }) { Text("关闭") } })
}

@Composable
private fun SonicArtistPuck(icon: ImageVector, description: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(54.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
            Icon(icon, description, Modifier.size(24.dp))
        }
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SonicArtistTrack(song: NeteaseSong, number: Int, current: Boolean, playing: Boolean,
    onEnqueue: () -> Unit, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(12.dp))
        .background(if (current) colors.primaryContainer else colors.surfaceContainerHigh.copy(alpha = .45f))
        .songQueueMenu(song.name.orEmpty(), onPlay, onEnqueue).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val ink = if (current) colors.onPrimaryContainer else colors.onSurface
        Text(number.toString(), Modifier.widthIn(min = 16.dp), style = MaterialTheme.typography.titleSmall,
            color = if (current) ink else colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Artwork(song.coverThumbPath ?: song.coverUrl.orEmpty(), Modifier.size(42.dp), corner = 8.dp)
        Column(Modifier.weight(1f)) {
            Text(song.name.orEmpty(), style = MaterialTheme.typography.titleMedium, color = ink,
                fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val duration = if (song.durationMs > 0) " · ${song.durationMs / 60000}:${(song.durationMs / 1000 % 60).toString().padStart(2, '0')}" else ""
            Text(song.artist.orEmpty() + duration, style = MaterialTheme.typography.bodySmall,
                color = if (current) ink.copy(alpha = .8f) else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
            Icon(if (current && playing) SonicIcons.Equalizer else Md3eIcons.PlayArrow, "播放歌曲", Modifier.size(20.dp), tint = ink)
        }
        IconButton(onClick = onEnqueue, modifier = Modifier.size(30.dp)) {
            Icon(SonicIcons.Plus, "添加到播放队列", Modifier.size(18.dp), tint = ink)
        }
    }
}
