@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.t1m3.qplayer.android.md3eui
import androidx.compose.foundation.*
import dev.t1m3.qplayer.android.md3eui.claudeClickable as clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.t1m3.qplayer.netease.dto.*
import java.time.LocalTime

// Section head used by the playlists/detail pages. Unchanged on purpose: those
// pages are outside this redesign and still call it.
@Composable
internal fun ClaudeSectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(7.dp))
            Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ClaudeUnderline(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.width(52.dp).height(5.dp)) {
        val path = Path().apply {
            moveTo(0f, size.height / 2)
            quadraticTo(size.width * .25f, 0f, size.width * .5f, size.height * .6f)
            quadraticTo(size.width * .75f, size.height, size.width, size.height * .3f)
        }
        drawPath(path, color.copy(alpha = .75f), style = Stroke(1.5.dp.toPx()))
    }
}

// Handwriting is reserved for a song's own recommendation reason — a human
// annotation in the margin. Never for interface chrome.
@Composable
internal fun ClaudeBadge(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
        .border(.6.dp, MaterialTheme.colorScheme.primary.copy(alpha = .25f), RoundedCornerShape(4.dp))
        .padding(horizontal = 7.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, fontFamily = RecommendationHandwriting,
        color = MaterialTheme.colorScheme.onPrimaryContainer)
}

/** Shelf head for the home screen: a rule, the name, and the count on the right.
 *  The rule carries the division, so no squiggle and no accent dot. */
@Composable
internal fun ClaudeShelfHeader(title: String, count: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, Modifier.weight(1f), fontFamily = ClaudeSerif,
                fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            if (count != null) Text(count, fontFamily = ClaudeMono, fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun greetingFor(hour: Int): String = when {
    hour < 6 -> "夜深了"
    hour < 11 -> "早上好"
    hour < 14 -> "中午好"
    hour < 18 -> "下午好"
    else -> "晚上好"
}

/** The hero: one block holding the greeting, today's phrase, and the ask.
 *  The greeting is the only thing at display scale on this screen. */
@Composable
internal fun ClaudeOpening(
    name: String,
    dailyPhrase: String,
    aiInput: @Composable () -> Unit,
    onPrivateFm: () -> Unit,
) {
    val hour = remember { LocalTime.now().hour }
    val roam = LocalClaudePrivateRoam.current
    var roamBounds by remember { mutableStateOf(Rect.Zero) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            if (name.isBlank()) greetingFor(hour) else "${greetingFor(hour)}，$name",
            fontFamily = ClaudeSerif, fontSize = 36.sp, lineHeight = 44.sp,
            letterSpacing = (-0.4).sp, fontWeight = FontWeight.Normal,
        )
        Text(dailyPhrase, fontFamily = ClaudeSerif, fontStyle = FontStyle.Italic,
            fontSize = 15.sp, lineHeight = 24.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        aiInput()
        // Private FM is a real destination, so it gets a full-width row under a
        // rule instead of a small text link: serif name, sans explanation.
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .onGloballyPositioned { roamBounds = it.boundsInRoot() }
                .clickable {
                    roam?.source = roamBounds
                    onPrivateFm()
                }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("私人 FM", fontFamily = ClaudeSerif, fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp)
                    Text("由网易云按你的口味续播", fontFamily = ClaudeSans, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("开始听", fontFamily = ClaudeSans, fontWeight = FontWeight.Medium,
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

internal fun claudeDuration(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/** A playlist is a discrete object, so it keeps a card: cover, name, length.
 *  Nothing is painted on top of the artwork. */
@Composable
internal fun ClaudePlaylistCard(playlist: NeteasePlaylist, onClick: () -> Unit) {
    Md3eCollectionContainer("cover:playlist:${playlist.id}", corner = 10.dp) {
        Column(Modifier.width(150.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
            Artwork(playlist.coverThumbPath ?: playlist.coverUrl.orEmpty(),
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .md3eSharedCover("cover:playlist:${playlist.id}"), corner = 10.dp)
            Text(playlist.name ?: "未命名歌单", Modifier.padding(top = 9.dp),
                fontFamily = ClaudeSerif, fontSize = 14.sp, lineHeight = 19.sp,
                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${playlist.trackCount} 首", Modifier.padding(top = 4.dp),
                fontFamily = ClaudeMono, fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Songs are prose, not objects: a plain list separated by rules, with no card
 *  around each row and no index. Tap plays, long-press enqueues. */
@Composable
internal fun ClaudeSongList(
    title: String,
    songs: List<NeteaseSong>,
    play: (Int) -> Unit,
    enqueue: (NeteaseSong) -> Unit,
) {
    if (songs.isEmpty()) return
    var expanded by remember(songs) { mutableStateOf(false) }
    val shown = if (expanded) songs else songs.take(4)
    Column {
        ClaudeShelfHeader(title, "${songs.size} 首")
        Spacer(Modifier.height(4.dp))
        shown.forEachIndexed { index, song ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .songQueueMenu(song.name.orEmpty(), { play(index) }, { enqueue(song) })
                .padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.coverThumbPath ?: song.coverUrl.orEmpty(),
                    Modifier.size(46.dp), corner = 6.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(song.name.orEmpty(), Modifier.weight(1f, fill = false),
                            fontFamily = ClaudeSerif, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val length = claudeDuration(song.durationMs)
                        if (length.isNotBlank()) Text(length, Modifier.padding(start = 8.dp),
                            fontFamily = ClaudeMono, fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(song.artist.orEmpty(), Modifier.padding(top = 2.dp),
                        fontFamily = ClaudeSans, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // The only handwriting on this screen, and only when the API
                    // actually supplied a reason — never an invented label.
                    val reason = song.recommendReason.orEmpty().ifBlank { song.genre.orEmpty() }.trim()
                    if (reason.isNotBlank()) Text(reason, Modifier.padding(top = 5.dp),
                        fontFamily = RecommendationHandwriting, fontSize = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (index < shown.lastIndex) HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
        }
        if (songs.size > 4) Text(
            if (expanded) "收起" else "展开全部 ${songs.size} 首",
            Modifier.padding(top = 10.dp).clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }.padding(vertical = 4.dp, horizontal = 2.dp),
            fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary)
    }
}

/** New releases are the quietest row: bare cover art, no card and no border.
 *  Browsing, not acting — so it carries no play affordance of its own. */
@Composable
internal fun ClaudeAlbumCover(album: NeteaseAlbum, onOpen: () -> Unit) {
    Md3eCollectionContainer("cover:album:${album.id}", corner = 8.dp) {
        Column(Modifier.width(110.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen)) {
            Artwork(album.coverThumbPath ?: album.coverUrl.orEmpty(),
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .md3eSharedCover("cover:album:${album.id}"), corner = 8.dp)
            Text(album.name ?: "未知专辑", Modifier.padding(top = 8.dp),
                fontFamily = ClaudeSerif, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artistName.orEmpty(), Modifier.padding(top = 2.dp),
                fontFamily = ClaudeSans, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
