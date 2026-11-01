package dev.t1m3.qplayer.android.md3eui

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.view.LayoutInflater
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.t1m3.qplayer.settings.SettingsCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal data class DynamicCoverPresentation(
    val cover: DynamicCover? = null, val visible: Boolean = false, val playing: Boolean = false,
    val failedUrls: List<String> = emptyList(), val onVideoError: (String) -> Unit = {},
    val fadeProgress: Float = 0f,
) {
    val hasMotion: Boolean get() = visible && cover?.preferredVideoUrl?.let { it !in failedUrls } == true
}

@Composable
internal fun rememberDynamicPlayerCover(runtime: Md3eRuntime, visible: Boolean): DynamicCoverPresentation {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    var foreground by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val mode = runtime.settings.intOf(SettingsCatalog.DYNAMIC_COVER_NETWORK_KEY)
    val selected = runtime.settings.intOf(SettingsCatalog.PLAYER_ARTWORK_KEY) == SettingsCatalog.ARTWORK_DYNAMIC
    val enabled = selected && !runtime.bili.playing && !runtime.settings.bool(SettingsCatalog.LOW_SPEC_MODE_KEY)
        && runtime.playback.hasTrack && visible
    val manager = remember(context) { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    fun networkKey(): String {
        val active = manager.activeNetwork
        val caps = manager.getNetworkCapabilities(active)
        return "$active|${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}|${caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)}|${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}|${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}"
    }
    var networkSignature by remember(manager) { mutableStateOf(networkKey()) }
    DisposableEffect(manager, enabled) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkSignature = networkKey() }
            override fun onLost(network: Network) { networkSignature = networkKey() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { networkSignature = networkKey() }
        }
        if (enabled) manager.registerDefaultNetworkCallback(callback)
        onDispose { if (enabled) runCatching { manager.unregisterNetworkCallback(callback) } }
    }
    val state = runtime.playback
    val base = runtime.settings.str(SettingsCatalog.DYNAMIC_COVER_API_BASE_KEY).trim()
    val storefront = runtime.settings.str(SettingsCatalog.DYNAMIC_COVER_STOREFRONT_KEY).ifBlank { "us" }
    val allowed = enabled && dynamicCoverNetworkAllowed(manager, mode)
    val scope = rememberCoroutineScope()
    val failedUrls = remember(state.songId) { mutableStateListOf<String>() }
    val controller = remember(context, state.songId, state.album, state.artist, state.title, allowed, mode, networkSignature, base, storefront) { DynamicCoverController(scope) }
    DisposableEffect(controller, state.songId, state.album, state.artist, state.title, allowed, mode, networkSignature, base, storefront) {
        controller.onTrackChanged(state.songId.toString(), state.album, state.artist) {
            if (!allowed) null else Md3eDynamicCoverSource.resolve(context, state.album, state.artist, state.title,
                base, storefront, networkAllowed = { dynamicCoverNetworkAllowed(manager, mode) })
        }
        onDispose { controller.clear() }
    }
    val cover = key(controller) { controller.state.collectAsState().value }
    return DynamicCoverPresentation(cover, enabled, runtime.transportState.playing && foreground && allowed,
        failedUrls, { if (it !in failedUrls) failedUrls.add(it) })
}

internal fun dynamicCoverNetworkAllowed(manager: ConnectivityManager, mode: Int): Boolean {
    val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
    val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    val mobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    return when (mode) { 0 -> wifi; 1 -> mobile; else -> true }
}

/** A track revision, rather than URL equality, owns each resolution. */
internal class DynamicCoverController(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow<DynamicCover?>(null)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var revision = 0L
    fun onTrackChanged(mediaId: String, album: String?, artist: String?, resolve: suspend () -> DynamicCover?) {
        clear()
        if (mediaId.isBlank() || artist.isNullOrBlank()) return
        val requested = revision
        job = scope.launch {
            try {
                val cover = resolve()
                if (requested == revision) mutable.value = cover
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // Do not persist transient failures as a one-day negative result.
                if (requested == revision) mutable.value = null
                android.util.Log.w("DynamicCover", "Resolution failed: ${failure.javaClass.simpleName}")
            }
        }
    }
    fun clear() { revision++; job?.cancel(); job = null; mutable.value = null }
}

internal val LocalDynamicPlayerCover = staticCompositionLocalOf { DynamicCoverPresentation() }

internal data class DynamicCoverTransition(
    val presentation: DynamicCoverPresentation, val progress: Float, val visible: Boolean,
)

/** Retain only the outgoing preview; the actual video always follows the live state. */
@Composable
internal fun rememberDynamicCoverTransition(mediaId: Long,
    live: DynamicCoverPresentation): DynamicCoverTransition {
    val opacity = remember(mediaId) { Animatable(0f) }
    var retained by remember(mediaId) { mutableStateOf<DynamicCover?>(null) }
    val active = live.hasMotion
    LaunchedEffect(mediaId, active, live.cover?.preferredVideoUrl) {
        if (active) retained = live.cover
        opacity.animateTo(if (active) 1f else 0f, tween(400, easing = FastOutSlowInEasing))
        if (!active) retained = null
    }
    val displayed = if (active) live else live.copy(cover = retained,
        visible = retained != null, playing = false, failedUrls = emptyList())
    return DynamicCoverTransition(displayed, opacity.value,
        active || (retained != null && opacity.value > 0f))
}

@Composable
internal fun Modifier.dynamicCoverArtworkFade(): Modifier {
    val progress = LocalDynamicPlayerCover.current.fadeProgress
    return graphicsLayer { alpha = (1f - progress).coerceIn(0f, 1f) }
}

private data class DynamicCoverAmbient(val image: ImageBitmap, val color: Color)

/** A 64px thumbnail and weighted hue vote; no video decoder is used for the background. */
@Composable
internal fun Md3eDynamicCoverBackdrop(presentation: DynamicCoverPresentation, fallback: String,
    modifier: Modifier = Modifier) {
    val motion = presentation.cover?.takeIf { presentation.hasMotion } ?: return
    val source = motion.preview.ifBlank { fallback }
    val context = LocalContext.current.applicationContext
    val ambient = key(source) { produceState<DynamicCoverAmbient?>(null) {
        val bitmap = awaitArtworkBitmap(context, source)
        value = bitmap?.let { original -> withContext(Dispatchers.Default) {
            val small = Bitmap.createScaledBitmap(original, 64, 64, true)
            DynamicCoverAmbient(small.asImageBitmap(), dynamicCoverAmbientColor(small))
        } }
    }.value }
    Box(modifier.background(Color(0xFF1C1B20))) {
        if (ambient == null) Md3eSoftArtworkBackdrop(fallback, Modifier.fillMaxSize(), radius = 24.dp)
        ambient?.let { data ->
            Image(data.image, null, Modifier.fillMaxSize().then(
                if (Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                data.color.copy(alpha = .55f), Color.Black.copy(alpha = .65f)))))
        }
    }
}

private fun dynamicCoverAmbientColor(bitmap: Bitmap): Color {
    val weights = FloatArray(36); val hue = FloatArray(36); val sat = FloatArray(36); val value = FloatArray(36)
    val hsv = FloatArray(3)
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        android.graphics.Color.colorToHSV(bitmap.getPixel(x, y), hsv)
        if (hsv[1] !in .10f.. .92f || hsv[2] !in .06f.. .92f) continue
        var weight = if (y >= bitmap.height * .55f) 1.35f else 1f
        if (hsv[1] < .12f) weight *= .6f
        if (hsv[0] in 5f..50f && hsv[1] in .12f.. .72f && hsv[2] in .25f.. .82f) weight *= .3f
        val bucket = (hsv[0] / 10).toInt().coerceIn(0, 35)
        weights[bucket] += weight; hue[bucket] += hsv[0] * weight
        sat[bucket] += hsv[1] * weight; value[bucket] += hsv[2] * weight
    }
    val best = weights.indices.maxByOrNull { weights[it] } ?: 0
    if (weights[best] == 0f) return Color(0xFF1C1B20)
    val weight = weights[best]
    return Color.hsv(hue[best] / weight, (sat[best] / weight * 1.05f + .02f).coerceIn(.16f, .80f),
        (value[best] / weight).coerceIn(.38f, .72f))
}

/** Page-sized card; the shared backdrop owns its bounds, independently of controls. */
@Composable
internal fun DynamicCoverCard(runtime: Md3eRuntime, modifier: Modifier = Modifier,
    presentation: DynamicCoverPresentation = LocalDynamicPlayerCover.current,
    renderVideo: Boolean = true) {
    val motion = presentation.cover?.takeIf { presentation.hasMotion } ?: return
    val fallback = runtime.playback.cover
    val mode = runtime.settings.intOf(SettingsCatalog.DYNAMIC_COVER_NETWORK_KEY)
    val context = LocalContext.current.applicationContext
    val manager = remember(context) { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    val source = motion?.preview?.ifBlank { fallback } ?: fallback
    val preview = key(source) { produceState<ImageBitmap?>(null) {
        value = awaitArtworkBitmap(context, source)?.asImageBitmap()
    }.value }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val width = (maxWidth / 1.03f).coerceAtLeast(1.dp)
        val height = (maxHeight / 1.03f).coerceAtLeast(1.dp)
        key(runtime.playback.songId, motion?.preferredVideoUrl) {
            Box(Modifier.width(width).height(height)
                .graphicsLayer { scaleX = 1.03f; scaleY = 1.03f }
                .background(Color(0xFF1C1B20))) {
                // The ordinary artwork stays underneath until the Apple preview is available.
                Artwork(fallback, Modifier.fillMaxSize(), corner = 0.dp, contentScale = ContentScale.Crop)
                preview?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                if (renderVideo) motion?.preferredVideoUrl?.takeUnless { it in presentation.failedUrls }?.let { url ->
                    DynamicCoverVideo(url, presentation.playing,
                        allowed = { dynamicCoverNetworkAllowed(manager, mode) },
                        onError = { presentation.onVideoError(url) }, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = .18f), .4f to Color.Black.copy(alpha = .26f),
                    .75f to Color.Black.copy(alpha = .40f), 1f to Color.Black.copy(alpha = .50f))))
            }
        }
    }
}

/** The music engine, session, volume and focus are never touched here. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun DynamicCoverVideo(url: String, playing: Boolean, allowed: () -> Boolean, onError: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestAllowed by rememberUpdatedState(allowed)
    val latestPlaying by rememberUpdatedState(playing)
    val latestError by rememberUpdatedState(onError)
    var firstFrame by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (firstFrame && !failed) 1f else .001f, tween(250), label = "cover_first_frame")
    // Failure removes the whole owner: listener, surface and player are released together.
    if (!failed) {
        val player = remember(url, context) {
            val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(6_000).setReadTimeoutMs(6_000)
            val gated = DataSource.Factory {
                val upstream = http.createDataSource()
                object : DataSource by upstream {
                    override fun open(dataSpec: DataSpec): Long {
                        if (!latestAllowed()) throw IOException("Dynamic cover network disallowed")
                        return upstream.open(dataSpec)
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (!latestAllowed()) throw IOException("Dynamic cover network disallowed")
                        return upstream.read(buffer, offset, length)
                    }
                }
            }
            ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(gated)
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0)))
                .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
                .build().apply {
                    volume = 0f
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                    repeatMode = Player.REPEAT_MODE_ONE
                }
        }
        DisposableEffect(player, lifecycle) {
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame = true }
                override fun onPlayerError(error: PlaybackException) { failed = true; firstFrame = false; latestError() }
            }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> if (latestPlaying && latestAllowed()) player.play()
                    Lifecycle.Event.ON_STOP -> player.pause()
                    else -> Unit
                }
            }
            player.addListener(listener); lifecycle.addObserver(observer)
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = latestPlaying && latestAllowed() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            onDispose {
                lifecycle.removeObserver(observer); player.removeListener(listener); player.release()
            }
        }
        LaunchedEffect(player, playing) {
            player.playWhenReady = playing && latestAllowed() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        AndroidView(factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.view_dynamic_cover, null, false) as PlayerView).apply {
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setEnableComposeSurfaceSyncWorkaround(true)
                this.player = player
            }
        }, update = { it.player = player }, onReset = null, onRelease = { it.player = null },
            modifier = modifier.graphicsLayer { this.alpha = alpha })
    }
}
