package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

internal data class Md3eArtistCredit(val name: String, val id: Long)

/** Names use U+0001 so slashes and commas inside an artist name stay intact. */
internal fun md3ePlayingArtists(state: PlaybackState): List<Md3eArtistCredit> {
    val names = if (state.artistNamesCsv.isNotBlank()) state.artistNamesCsv.split('\u0001')
        else state.artist.split(" / ")
    val ids = if (state.artistIdsCsv.isNotBlank()) {
        state.artistIdsCsv.split(',').map { it.trim().toLongOrNull() ?: 0L }
    } else listOf(state.artistId)
    return names.mapIndexedNotNull { index, value ->
        value.trim().takeIf(String::isNotEmpty)?.let {
            Md3eArtistCredit(it, ids.getOrNull(index) ?: 0L)
        }
    }
}

@Composable
internal fun Md3ePlayingArtistLinks(
    state: PlaybackState,
    onOpenArtist: (Long) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    centered: Boolean = true,
) {
    val artists = remember(state.artist, state.artistId, state.artistIdsCsv, state.artistNamesCsv) {
        md3ePlayingArtists(state)
    }
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically) {
        artists.forEachIndexed { index, artist ->
            if (index > 0) Text(" / ", fontSize = fontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(artist.name, fontSize = fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, modifier = Modifier.clickable(enabled = artist.id != 0L) {
                    onOpenArtist(artist.id)
                })
        }
    }
}
