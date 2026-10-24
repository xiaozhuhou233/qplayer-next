package dev.t1m3.qplayer.android.md3eui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

// Dynamic memory cache: 1/8th of available heap memory, min 32MB, max 128MB.
private val maxMemoryBytes = (Runtime.getRuntime().maxMemory() / 8).coerceIn(32 * 1024 * 1024, 128 * 1024 * 1024).toInt()

private val artworkCache = object : LruCache<String, ImageBitmap>(maxMemoryBytes) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

private val artworkRequests = Semaphore(12)
private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val artworkInFlight = mutableMapOf<String, Deferred<ImageBitmap?>>()
private var diskCacheDir: File? = null

private fun ensureDiskCacheDir(context: Context): File {
    diskCacheDir?.let { return it }
    val dir = File(context.cacheDir, "artwork_cache_v2")
    if (!dir.exists()) dir.mkdirs()
    diskCacheDir = dir
    return dir
}

private fun getCacheFile(context: Context, source: String): File {
    val dir = ensureDiskCacheDir(context)
    val hash = runCatching {
        val digest = MessageDigest.getInstance("MD5").digest(source.toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    }.getOrElse { source.hashCode().toString(16) }
    return File(dir, hash)
}

@Composable
internal fun Artwork(url: String, modifier: Modifier = Modifier, corner: androidx.compose.ui.unit.Dp = 24.dp,
    contentScale: ContentScale = ContentScale.Crop,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    requestSize: Int = 256,
    alignment: Alignment = Alignment.Center) {
    val source = remember(url, requestSize) { artworkRequestSource(url, requestSize) }
    val context = LocalContext.current.applicationContext

    // Read memory cache before composition.
    val initialBitmap = remember(source) { artworkCache.get(source) }
    var imageBitmap by remember(source) { mutableStateOf(initialBitmap) }
    val wasInMemory = initialBitmap != null

    LaunchedEffect(source) {
        if (source.isNotBlank() && imageBitmap == null) {
            imageBitmap = awaitArtwork(context, source)
        }
    }

    val iosMotion = LocalIosCollectionTransition.current
    val coverKey = modifier.foldIn<String?>(null) { key, element -> if (element is IosCoverIdentity) element.key else key }
    SideEffect {
        if (coverKey != null && imageBitmap != null && iosMotion != null) {
            iosMotion.images[coverKey] = imageBitmap!!
            while (iosMotion.images.size > 48) {
                val stale = iosMotion.images.keys.firstOrNull { it != iosMotion.motion.coverKey } ?: break
                iosMotion.images.remove(stale)
            }
        }
    }

    // Crossfade only if loaded asynchronously, otherwise instant presentation.
    val targetAlpha = if (imageBitmap != null) 1f else 0f
    val imageAlpha = if (wasInMemory || Md3eLowSpecMode.current || !LocalMd3eRenderingActive.current || LocalMd3eMotionActive.current) {
        targetAlpha
    } else {
        animateFloatAsState(
            targetValue = targetAlpha,
            animationSpec = tween(100, easing = if (LocalClaudeDesign.current) ClaudeOneTake.ExpoOut else androidx.compose.animation.core.LinearEasing),
            label = "seal_artwork_crossfade"
        ).value
    }

    Surface(modifier, shape = RoundedCornerShape(corner), color = containerColor) {
        val currentBitmap = imageBitmap
        Box(contentAlignment = Alignment.Center) {
            if (currentBitmap == null) {
                Icon(
                    Md3eIcons.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Image(
                    bitmap = currentBitmap,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = contentScale,
                    alignment = alignment,
                    alpha = imageAlpha
                )
            }
        }
    }
}

/** A list thumbnail and its detail cover share one download and decoded bitmap. */
internal fun artworkRequestSource(source: String, requestSize: Int = 256): String {
    if (!source.startsWith("https://") && !source.startsWith("http://")) return source.removePrefix("file://")
    val uri = runCatching { URI(source) }.getOrNull() ?: return source
    val host = uri.host.orEmpty().lowercase()
    if (host != "music.126.net" && !host.endsWith(".music.126.net")) return source
    val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() && it.substringBefore('=') != "param" }
    val dimension = requestSize.coerceIn(256, 1024)
    val suffix = (query + "param=${dimension}y${dimension}").joinToString("&")
    return "https://${uri.rawAuthority}${uri.rawPath}?$suffix"
}

internal suspend fun awaitArtworkBitmap(context: Context, source: String): Bitmap? {
    return awaitArtwork(context, source)?.asAndroidBitmap()
}

internal suspend fun awaitArtwork(context: Context, source: String): ImageBitmap? {
    artworkCache.get(source)?.let { return it }
    val request = synchronized(artworkInFlight) {
        artworkInFlight[source] ?: artworkScope.async(start = CoroutineStart.LAZY) {
            artworkRequests.withPermit {
                artworkCache.get(source) ?: loadArtwork(context, source)?.also { artworkCache.put(source, it) }
            }
        }.also { deferred ->
            artworkInFlight[source] = deferred
            deferred.invokeOnCompletion {
                synchronized(artworkInFlight) {
                    if (artworkInFlight[source] === deferred) artworkInFlight.remove(source)
                }
            }
        }
    }
    return request.await()
}

private fun loadArtwork(context: Context, source: String): ImageBitmap? {
    if (source.isBlank()) return null
    val maxDimension = if (source.contains("param=1024y1024")) 1024 else 512
    if (!source.startsWith("https://") && !source.startsWith("http://")) {
        val file = File(source.removePrefix("file://"))
        if (!file.isFile) return null
        return decodeBitmapFile(file, maxDimension = maxDimension)
    }

    // Check Disk Cache first
    val cacheFile = getCacheFile(context, source)
    if (cacheFile.isFile && cacheFile.length() > 0) {
        decodeBitmapFile(cacheFile, maxDimension = maxDimension)?.let { return it }
    }

    // Download from Network
    val connection = try { URL(source).openConnection() as HttpURLConnection } catch (_: Exception) { return null }
    return try {
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        connection.inputStream.use { stream ->
            val bytes = stream.readBytesBounded(3 * 1024 * 1024) ?: return null
            // Save to disk cache atomically
            runCatching {
                val temp = File.createTempFile("art_", ".tmp", ensureDiskCacheDir(context))
                FileOutputStream(temp).use { it.write(bytes) }
                if (temp.renameTo(cacheFile)) temp.delete() else cacheFile.writeBytes(bytes)
            }
            decodeByteArray(bytes, maxDimension = maxDimension)
        }
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun decodeBitmapFile(file: File, maxDimension: Int): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > maxDimension) inSampleSize *= 2
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

private fun decodeByteArray(bytes: ByteArray, maxDimension: Int): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > maxDimension) inSampleSize *= 2
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray? {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) return null
        output.write(buffer, 0, count)
    }
}

/** Blurred backgrounds need a soft texture, not a full-resolution offscreen buffer. */
@Composable
internal fun Md3eSoftArtworkBackdrop(
    cover: String,
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = 144.dp
) {
    BoxWithConstraints(modifier.clipToBounds()) {
        Artwork(
            cover,
            Modifier.size(maxWidth / 4, maxHeight / 4).graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = 4f
                scaleY = 4f
            }.blur(radius / 4),
            corner = 0.dp
        )
    }
}
