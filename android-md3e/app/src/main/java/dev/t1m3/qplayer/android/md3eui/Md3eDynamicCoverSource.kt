package dev.t1m3.qplayer.android.md3eui

import android.content.Context
import dev.t1m3.qplayer.artwork.AppleMotionArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

internal data class DynamicCover(val squareVideoUrl: String?, val tallVideoUrl: String?,
    val preview: String, val album: String = "", val artist: String = "") {
    val preferredVideoUrl: String? get() = tallVideoUrl?.takeIf { it.isNotBlank() }
        ?: squareVideoUrl?.takeIf { it.isNotBlank() }
}

/** Public Apple web catalog, resolved on-device. No Guazi endpoint, account or embedded token. */
internal object Md3eDynamicCoverSource {
    private val lookup = Mutex()
    private const val DAY = 86_400_000L
    private data class Entry(val cover: DynamicCover?, val expires: Long)
    private val memory = object : LinkedHashMap<String, Entry>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 64
    }
    private class HttpFailure(val status: Int) : Exception("Apple artwork HTTP $status")

    suspend fun resolve(context: Context, album: String, artist: String, title: String,
        apiBaseUrl: String, storefront: String, networkAllowed: () -> Boolean): DynamicCover? =
        withContext(Dispatchers.IO) {
        lookup.withLock {
            currentCoroutineContext().ensureActive()
            if (!networkAllowed() || artist.isBlank() || (album.isBlank() && title.isBlank())) return@withLock null
            val directory = File(context.cacheDir, "apple-motion-v1").apply { mkdirs() }
            val key = digest("song-album-v2|$apiBaseUrl|$storefront|${album.trim().lowercase(Locale.ROOT)}|${artist.trim().lowercase(Locale.ROOT)}|${title.trim().lowercase(Locale.ROOT)}")
            val now = System.currentTimeMillis()
            memory[key]?.let { if (now < it.expires) return@withLock it.cover }
            val metadata = File(directory, "$key.json")
            if (metadata.isFile) {
                val entry = runCatching {
                    val root = JSONObject(metadata.readText())
                    Entry(if (root.optBoolean("missing")) null else DynamicCover(
                        root.optString("square").takeIf { it.isNotBlank() },
                        root.optString("tall").takeIf { it.isNotBlank() }, root.optString("preview"),
                        root.optString("album", album), root.optString("artist", artist)), root.getLong("expires"))
                }.getOrNull()
                if (entry != null && now < entry.expires) {
                    memory[key] = entry
                    return@withLock entry.cover
                }
            }
            val cover = if (apiBaseUrl.isNotBlank()) resolveEndpoint(apiBaseUrl, album, artist, storefront, networkAllowed)
                else search(context, album, artist, title, storefront, networkAllowed)?.let {
                    DynamicCover(it.square.takeIf(String::isNotBlank), it.tall.takeIf(String::isNotBlank), it.preview, it.album, it.artist)
                }
            currentCoroutineContext().ensureActive()
            if (!networkAllowed()) return@withLock null
            val expires = now + if (cover == null) DAY else 7 * DAY
            val result = JSONObject().put("missing", cover == null).put("expires", expires)
            cover?.let { result.put("square", it.squareVideoUrl.orEmpty()).put("tall", it.tallVideoUrl.orEmpty())
                .put("preview", it.preview).put("album", it.album).put("artist", it.artist) }
            atomicWrite(metadata, result.toString().toByteArray())
            directory.listFiles().orEmpty().filter { it.extension == "json" && it != metadata }
                .sortedByDescending { it.lastModified() }.drop(127).forEach { it.delete() }
            memory[key] = Entry(cover, expires)
            cover
        }
    }

    /** Optional self-hosted contract; an empty base retains on-device catalog resolution. */
    private suspend fun resolveEndpoint(base: String, album: String, artist: String, storefront: String,
        allowed: () -> Boolean): DynamicCover? {
        val endpoint = URL(base.trim().trimEnd('/') + "/v1/dynamic-cover?album=" + encode(album) +
            "&artist=" + encode(artist) + "&storefront=" + encode(storefront))
        require(endpoint.protocol == "https" && endpoint.userInfo == null) { "HTTPS endpoint required" }
        currentCoroutineContext().ensureActive()
        check(allowed())
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000; readTimeout = 6_000; instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode == 404) return null
            check(connection.responseCode == 200) { "Dynamic cover service unavailable" }
            val body = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive(); check(allowed())
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(body.size() + count <= 512 * 1024)
                    body.write(buffer, 0, count)
                }
            }
            val root = JSONObject(body.toString("UTF-8"))
            fun url(key: String): String? = root.optString(key).takeIf { value ->
                value.isNotBlank() && value != "null" && runCatching {
                    URL(value).let { it.protocol == "https" && it.userInfo == null }
                }.getOrDefault(false)
            }
            val square = url("square_video_url"); val tall = url("tall_video_url")
            if (square == null && tall == null) return null
            val match = root.optJSONObject("matched")
            return DynamicCover(square, tall, url("preview_frame_url").orEmpty(),
                match?.optString("album_name", album) ?: album, match?.optString("artist_name", artist) ?: artist)
        } finally { connection.disconnect() }
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private suspend fun search(context: Context, album: String, artist: String, title: String,
        storefront: String, allowed: () -> Boolean): AppleMotionArtwork.Cover? {
        var bearer = webToken(context, allowed)
        val chineseArtist = artist.any { it in '\u3400'..'\u9fff' }
        val preferred = storefront.lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z]{2}")) } ?: "us"
        val regions = (if (chineseArtist) listOf("cn", preferred, "us", "hk")
            else listOf(preferred, "us", "gb")).distinct().take(3)
        val locale = if (chineseArtist) "&l=zh-Hans-CN" else ""
        suspend fun catalog(path: String): String {
            val url = "https://amp-api.music.apple.com/v1/catalog/$path$locale"
            return try { fetch(url, bearer, allowed = allowed).toString(Charsets.UTF_8) }
            catch (failure: HttpFailure) {
                if (failure.status != 401 && failure.status != 403) throw failure
                bearer = webToken(context, allowed, refresh = true)
                fetch(url, bearer, allowed = allowed).toString(Charsets.UTF_8)
            }
        }
        val details = mutableMapOf<Pair<String, String>, String>()
        suspend fun detail(region: String, id: String): String? {
            val key = region to id
            details[key]?.let { return it }
            return try {
                catalog("$region/albums/$id?extend=editorialVideo&include=tracks&limit%5Btracks%5D=100")
                    .also { details[key] = it }
            } catch (failure: HttpFailure) { if (failure.status == 404) null else throw failure }
        }
        val recordings = mutableSetOf<String>()
        val searched = mutableSetOf<Triple<String, String, String>>()
        val parentSearches = mutableListOf<Pair<String, String>>()
        suspend fun officialAlbum(region: String, mapped: AppleMotionArtwork.CatalogAlbum,
            verifiedArtist: String): AppleMotionArtwork.Cover? {
            val official = mapped.id.takeIf { it.isNotBlank() }?.let { detail(region, it) }
            if (title.isBlank()) return official?.let { AppleMotionArtwork.parseAlbum(it, mapped.name, verifiedArtist) }
            val identities = if (mapped.recording.isNotBlank()) setOf(mapped.recording) else official?.let {
                AppleMotionArtwork.matchingTrackIsrcs(it, mapped.name, title, verifiedArtist)
            }.orEmpty()
            if (identities.isEmpty()) return null
            recordings += identities
            official?.let { AppleMotionArtwork.parseContainingAlbum(it, title, verifiedArtist, identities)?.let { return it } }
            // Search Apple's own album name, never a NetEase single's display name.
            if (!searched.add(Triple(region, mapped.name, verifiedArtist))) return null
            val term = encode("${mapped.name} $verifiedArtist")
            val albums = catalog("$region/search?term=$term&types=albums&limit=15&extend=editorialVideo")
            parentSearches += region to albums
            for (id in AppleMotionArtwork.candidateAlbumIds(albums, mapped.name, verifiedArtist, true).take(3)) {
                val candidate = detail(region, id) ?: continue
                AppleMotionArtwork.parseContainingAlbum(candidate, title, verifiedArtist, identities)?.let { return it }
            }
            return null
        }
        if (title.isNotBlank()) {
            val term = encode("$title $artist")
            // Primary route: verified Apple song -> its canonical album name/ID -> artwork.
            for (region in regions) {
                val songs = try {
                    catalog("$region/search?term=$term&types=songs&limit=15&include%5Bsongs%5D=albums")
                } catch (failure: HttpFailure) { if (failure.status == 404) continue else throw failure }
                for (mapped in AppleMotionArtwork.catalogSongAlbums(songs, album, title, artist).take(4)) {
                    officialAlbum(region, mapped, mapped.artist)?.let { return it }
                }
            }
        }
        // Public iTunes song metadata is a fallback when a regional catalog misses the track.
        try {
            val term = encode("${title.ifBlank { album }} $artist")
            val entity = if (title.isBlank()) "album" else "song"
            val json = fetch("https://itunes.apple.com/search?term=$term&entity=$entity&country=US&limit=15",
                allowed = allowed).toString(Charsets.UTF_8)
            for (mapped in AppleMotionArtwork.itunesAlbums(json, album, title, artist).take(4)) {
                // iTunes aliases are untrusted until the localized album's tracks verify them.
                officialAlbum("us", mapped, artist)?.let { return it }
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            check(allowed()) { "Dynamic cover network policy changed" }
            android.util.Log.w("DynamicCover", "Apple metadata mapping unavailable: ${failure.javaClass.simpleName}")
        }
        if (title.isBlank()) {
            for (region in regions) {
                val query = encode("${AppleMotionArtwork.normalizeAlbum(album)} $artist")
                val albums = catalog("$region/search?term=$query&types=albums&limit=15&extend=editorialVideo")
                AppleMotionArtwork.parseSearch(albums, album, artist)?.let { return it }
                for (id in AppleMotionArtwork.candidateAlbumIds(albums, album, artist, true).take(3)) {
                    detail(region, id)?.let { AppleMotionArtwork.parseAlbum(it, album, artist)?.let { return it } }
                }
            }
        } else if (recordings.isNotEmpty()) {
            // A song may only point to its standalone single. Search the same artist's
            // albums, but accept a parent only when that exact song AND ISRC is present.
            for (region in regions) {
                val query = encode(artist)
                parentSearches += region to catalog("$region/search?term=$query&types=albums&limit=15&extend=editorialVideo")
            }
            val parents = parentSearches.flatMap { (region, json) ->
                AppleMotionArtwork.candidateAlbumIds(json, "", artist, false).map { region to it }
            }.distinct().take(6)
            for ((region, id) in parents) {
                detail(region, id)?.let {
                    AppleMotionArtwork.parseContainingAlbum(it, title, artist, recordings)?.let { return it }
                }
            }
        }
        return null
    }

    private suspend fun webToken(context: Context, allowed: () -> Boolean, refresh: Boolean = false): String {
        val prefs = context.getSharedPreferences("apple-web-artwork", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis() / 1000
        if (!refresh) prefs.getString("token", "").orEmpty().let {
            if (AppleMotionArtwork.tokenExpires(it) > now + 3600) return it
        }
        val page = fetch("https://music.apple.com/us/browse", maxBytes = 4 * 1024 * 1024,
            allowed = allowed).toString(Charsets.UTF_8)
        var token = AppleMotionArtwork.findWebToken(page, now)
        if (token.isEmpty()) {
            val scripts = AppleMotionArtwork.scriptUrls(page).sortedBy { if (it.contains("/index")) 0 else 1 }
            for (script in scripts) {
                val content = fetch(script, maxBytes = 8 * 1024 * 1024, allowed = allowed).toString(Charsets.UTF_8)
                token = AppleMotionArtwork.findWebToken(content, now)
                if (token.isNotEmpty()) break
            }
        }
        check(token.isNotEmpty()) { "Apple web artwork token unavailable" }
        prefs.edit().putString("token", token).apply()
        return token
    }

    private suspend fun fetch(address: String, token: String = "", maxBytes: Int = 2 * 1024 * 1024,
        allowed: () -> Boolean): ByteArray {
        var addressToLoad = address
        val job = currentCoroutineContext()
        repeat(4) {
            job.ensureActive()
            check(allowed()) { "Dynamic cover network policy changed" }
            check(AppleMotionArtwork.isAppleHttps(addressToLoad)) { "Unexpected artwork URL" }
            val connection = (URL(addressToLoad).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36")
                setRequestProperty("Origin", "https://music.apple.com")
                setRequestProperty("Referer", "https://music.apple.com/")
                if (token.isNotBlank() && URL(addressToLoad).host == "amp-api.music.apple.com")
                    setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    addressToLoad = URL(URL(addressToLoad), connection.getHeaderField("Location")
                        ?: error("Missing artwork redirect")).toString()
                } else {
                    if (status != 200) throw HttpFailure(status)
                    check(connection.contentLengthLong <= maxBytes) { "Artwork response too large" }
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            job.ensureActive()
                            check(allowed()) { "Dynamic cover network policy changed" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= maxBytes) { "Artwork response too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                    if (connection.contentLengthLong >= 0)
                        check(output.size().toLong() == connection.contentLengthLong) { "Incomplete artwork response" }
                    return output.toByteArray()
                }
            } finally { connection.disconnect() }
        }
        error("Too many artwork redirects")
    }

    private fun atomicWrite(target: File, data: ByteArray) {
        val temporary = File(target.parentFile, target.name + ".part")
        try {
            temporary.writeBytes(data)
            check(temporary.renameTo(target)) { "Cannot commit artwork cache" }
        } finally { temporary.delete() }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
