package dev.t1m3.qplayer.android.md3eui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.graphics.Bitmap
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import dev.t1m3.qplayer.netease.NeteaseClient
import dev.t1m3.qplayer.settings.SettingsCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep expiring CDN links only in memory, including a bounded no-MV cache. */
private object MvBackdropSources {
    private data class Entry(val url: String, val expires: Long)
    private val entries = LinkedHashMap<Long, Entry>(32, .75f, true)
    private val lookup = Mutex()
    var revision by mutableIntStateOf(0)
        private set

    fun peek(songId: Long): String? {
        revision
        return synchronized(entries) {
            entries[songId]?.takeIf { it.expires > SystemClock.elapsedRealtime() }?.url
        }
    }

    suspend fun load(songId: Long): String = lookup.withLock {
        peek(songId)?.let { return@withLock it }
        val url = withContext(Dispatchers.IO) { NeteaseClient.INSTANCE.songMvUrl(songId) }
        synchronized(entries) {
            val lifetime = if (url.isEmpty()) 6 * 60 * 60_000L else 10 * 60_000L
            entries[songId] = Entry(url, SystemClock.elapsedRealtime() + lifetime)
            while (entries.size > 48) entries.remove(entries.keys.first())
        }
        revision++
        url
    }

    fun invalidate(songId: Long) = synchronized(entries) { entries.remove(songId); Unit }
}

/** Warm the decoder, never transfer a TextureView's consumer or frame listener. */
private object MvWarmPlayer {
    data class Entry(val url: String, val player: MediaPlayer, var prepared: Boolean = false)
    private var entry: Entry? = null
    private val active = mutableMapOf<MediaPlayer, String>()
    var revision by mutableIntStateOf(0)
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable { clear() }
    fun prepare(context: Context, url: String) {
        if (entry?.url == url || url in active.values) return
        clear()
        val item = Entry(url, MediaPlayer())
        entry = item
        try {
            item.player.apply {
                setVolume(0f, 0f)
                setOnPreparedListener { if (entry === item) item.prepared = true }
                setOnErrorListener { _, _, _ -> if (entry === item) clear(); true }
                setDataSource(context.applicationContext, Uri.parse(url), mapOf("Referer" to "https://music.163.com/"))
                prepareAsync()
            }
            handler.postDelayed(expiry, 90_000L)
        } catch (_: Exception) { clear() }
    }
    fun take(url: String): Entry? {
        val item = entry?.takeIf { it.url == url } ?: return null
        handler.removeCallbacks(expiry)
        entry = null
        return item
    }
    fun attach(url: String, player: MediaPlayer) { active[player] = url; revision++ }
    fun detach(player: MediaPlayer) { if (active.remove(player) != null) revision++ }
    fun clear() {
        handler.removeCallbacks(expiry)
        entry?.player?.apply {
            setOnPreparedListener(null); setOnErrorListener(null); release()
        }
        entry = null
    }
}

@Composable
internal fun Md3ePrewarmMv(runtime: Md3eRuntime) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val songId = runtime.playback.songId
    val enabled = runtime.uiVisible && songId > 0 && !runtime.bili.playing &&
        runtime.settings.bool(SettingsCatalog.MV_BACKGROUND_KEY) && !runtime.settings.bool(SettingsCatalog.LOW_SPEC_MODE_KEY)
    LaunchedEffect(songId, enabled, MvWarmPlayer.revision) {
        if (enabled) try {
            val url = MvBackdropSources.load(songId)
            if (url.isNotEmpty()) MvWarmPlayer.prepare(context, url)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Detail view owns retries and the artwork fallback. */ }
        else if (songId <= 0 || runtime.bili.playing || !runtime.settings.bool(SettingsCatalog.MV_BACKGROUND_KEY) ||
            runtime.settings.bool(SettingsCatalog.LOW_SPEC_MODE_KEY)) MvWarmPlayer.clear()
    }
    DisposableEffect(Unit) { onDispose { MvWarmPlayer.clear() } }
}

internal fun mvBackdropExpected(runtime: Md3eRuntime): Boolean =
    runtime.settings.bool(SettingsCatalog.MV_BACKGROUND_KEY) &&
        !runtime.settings.bool(SettingsCatalog.LOW_SPEC_MODE_KEY) && !runtime.bili.playing &&
        runtime.playback.songId > 0 && MvBackdropSources.peek(runtime.playback.songId) != ""

internal data class MvBackdropState(val hasVideo: Boolean, val ready: Boolean)

/** Expose loading separately from the first frame, without flashing artwork over a known MV. */
@Composable
internal fun Md3eMvBackdrop(runtime: Md3eRuntime, sceneBottom: Float, controlsTop: Float,
    visible: Boolean, modifier: Modifier = Modifier, panFraction: Float = 0f): MvBackdropState {
    val state = runtime.playback
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()?.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle == null || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> foreground = false
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val enabled = runtime.settings.bool(SettingsCatalog.MV_BACKGROUND_KEY) &&
        !runtime.settings.bool(SettingsCatalog.LOW_SPEC_MODE_KEY) && !runtime.bili.playing &&
        state.songId > 0
    if (!enabled) return MvBackdropState(false, false)
    return key(state.songId) {
        var attempt by remember { mutableIntStateOf(0) }
        var retry by remember { mutableStateOf(false) }
        LaunchedEffect(retry) {
            if (retry) {
                // Recover transient URL/decoder/surface failures without reopening the player.
                delay(mvRetryDelayMs(attempt))
                MvBackdropSources.invalidate(state.songId)
                attempt++
                retry = false
            }
        }
        val url by key(attempt) { produceState<String?>(MvBackdropSources.peek(state.songId)) {
            try { value = MvBackdropSources.load(state.songId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.w("MvBackdrop", "MV lookup unavailable for song ${state.songId}: ${failure.javaClass.simpleName}")
                retry = true
            }
        } }
        val source = url
        if (source == null) MvBackdropState(!retry, false)
        else if (source.isEmpty()) MvBackdropState(false, false) else key(source, attempt) {
            val ready = MvVideoLayer(source, runtime.transportState.playing && visible && foreground && runtime.uiVisible, visible,
                sceneBottom, controlsTop, modifier, panFraction) { retry = true }
            MvBackdropState(!retry, ready)
        }
    }
}

@Composable
private fun MvVideoLayer(url: String, playing: Boolean, visible: Boolean, sceneBottom: Float, controlsTop: Float,
    modifier: Modifier, panFraction: Float, onFailure: () -> Unit): Boolean {
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val opacity by animateFloatAsState(if (ready && visible) 1f else 0f, tween(180), label = "mv_background_fade")
    val latestFailure by rememberUpdatedState(onFailure)
    // A fully transparent TextureView layer can skip drawing its first texture update.
    if (!failed) Box(modifier.clipToBounds().graphicsLayer { alpha = opacity.coerceAtLeast(.001f) }) {
        // Let AndroidView own BOTH creation and final release. A remembered, disposed View
        // must never be handed back to a newly attached AndroidView after navigation.
        AndroidView(factory = { context -> MvBackdropView(context, url, { ready = it }, {
            ready = false; failed = true; latestFailure()
        }) }, onReset = null, onRelease = { it.dispose() }, update = {
            it.setPlaying(playing)
            it.setVerticalPan(panFraction)
        }, modifier = Modifier.fillMaxSize())
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val progress = controlsTop.takeIf { it > 0f } ?: (size.height * .73f)
            val title = sceneBottom.takeIf { it > 0f } ?: (size.height * .59f)
            val stops = (0..32).map {
                val fraction = it / 32f
                fraction to Color.Black.copy(alpha = mvShadeAlpha(fraction, title / size.height, progress / size.height))
            }.toTypedArray()
            drawRect(Brush.verticalGradient(*stops))
        }
    }
    return ready
}

/** TextureView stays behind Compose; no SurfaceView overlay or second audio-focus owner. */
private class MvBackdropView(
    context: Context, private val url: String,
    private val onFrame: (Boolean) -> Unit, private val onFailure: () -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var output: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var letterbox = MvLetterbox()
    private var verticalPan = 0f

    fun setVerticalPan(value: Float) {
        val bounded = value.coerceIn(-MV_MAX_PAN_FRACTION, MV_MAX_PAN_FRACTION)
        if (verticalPan == bounded) return
        verticalPan = bounded
        updateVideoTransform()
    }
    private var bordersChecked = false
    private var borderCopyPending = false
    private var borderAttempts = 0
    private var nextBorderCheckAt = 0L
    private val playback = MvClipPlayback()
    private var disposed = false
    private var failed = false
    private var frameSeen = false
    private var previewing = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { fail() }
    private val loopCheck = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (!playback.shouldPlay || disposed || failed) return
            try {
                if (playback.beginLoop(current.currentPosition)) seekToClip(current)
                else handler.postDelayed(this, 60L)
            } catch (_: IllegalStateException) { fail() }
        }
    }

    init {
        isOpaque = false
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = this
    }

    fun setPlaying(value: Boolean) {
        playback.requestedPlaying = value
        syncPlayback()
    }

    private fun syncPlayback() {
        val current = player ?: return
        if (!playback.prepared) return
        handler.removeCallbacks(loopCheck)
        try {
            if (playback.shouldPlay) {
                if (playback.beginLoop(current.currentPosition)) { seekToClip(current); return }
                if (!current.isPlaying) current.start()
                handler.postDelayed(loopCheck, 60L)
            } else if (!playback.seeking && !frameSeen) {
                // A paused song still needs one decoded excerpt frame. Pause immediately
                // after the texture update; this muted preview never starts music.
                previewing = true
                if (!current.isPlaying) current.start()
            } else if (current.isPlaying) current.pause()
        } catch (_: IllegalStateException) { fail() }
    }

    private fun seekToClip(current: MediaPlayer) {
        handler.removeCallbacks(loopCheck)
        if (current.isPlaying) current.pause()
        current.seekTo(playback.window.startMs.toLong(), MediaPlayer.SEEK_CLOSEST_SYNC)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (disposed || failed || player != null) return
        releasePlayer()
        try {
            val warmed = MvWarmPlayer.take(url)
            val current = warmed?.player ?: MediaPlayer()
            player = current
            MvWarmPlayer.attach(url, current)
            output = Surface(texture)
            // Volume is zero BEFORE prepare/start; never request audio focus or a media session.
            current.setVolume(0f, 0f)
            current.setSurface(output)
            current.setOnVideoSizeChangedListener { source, w, h ->
                if (!disposed && !failed && player === source) {
                    updateVideoSize(w, h)
                    revealFrameIfReady()
                }
            }
            current.setOnPreparedListener {
                if (disposed || player !== it) return@setOnPreparedListener
                if (it.duration <= 0) { fail(); return@setOnPreparedListener }
                updateVideoSize(it.videoWidth, it.videoHeight)
                playback.prepare(it.duration)
                it.setVolume(0f, 0f)
                it.isLooping = false
                // Seek before the first reveal; never show an opening/title frame.
                try { seekToClip(it) } catch (_: IllegalStateException) { fail() }
            }
            current.setOnSeekCompleteListener {
                if (disposed || failed || player !== it) return@setOnSeekCompleteListener
                playback.seekComplete()
                revealFrameIfReady()
                syncPlayback()
            }
            current.setOnCompletionListener {
                if (disposed || failed || player !== it) return@setOnCompletionListener
                if (playback.beginLoop(playback.window.endMs)) {
                    try { seekToClip(it) } catch (_: IllegalStateException) { fail() }
                }
            }
            current.setOnInfoListener { source, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && !disposed && player === source) {
                    // Readiness comes from the actual TextureView update, never just
                    // a decoder event that can arrive before its surface is drawn.
                    invalidate()
                }
                false
            }
            current.setOnErrorListener { source, what, extra ->
                if (player === source) {
                    Log.w("MvBackdrop", "MV decoder error $what/$extra")
                    fail()
                }
                true
            }
            if (warmed == null) {
                current.setDataSource(context, Uri.parse(url), mapOf("Referer" to "https://music.163.com/"))
                current.prepareAsync()
            } else if (warmed.prepared) {
                if (current.duration <= 0) { fail(); return }
                updateVideoSize(current.videoWidth, current.videoHeight)
                playback.prepare(current.duration)
                current.isLooping = false
                seekToClip(current)
            }
            handler.postDelayed(timeout, 25_000L)
        } catch (_: Exception) { fail() }
    }

    private fun updateVideoSize(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
        updateVideoTransform()
    }

    private fun updateVideoTransform() {
        val geometry = mvFrameTransform(videoWidth, videoHeight, width, height, letterbox, panFraction = verticalPan) ?: return
        // TextureView initially stretches its buffer to the View. Undo that stretch,
        // then apply one uniform center-crop scale to the useful (non-letterboxed) frame.
        val transform = Matrix()
        transform.setScale(geometry.scaleX, geometry.scaleY)
        transform.postTranslate(geometry.translateX, geometry.translateY)
        setTransform(transform)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateVideoTransform()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        updateVideoTransform()
    }
    private fun revealFrameIfReady() {
        if (frameSeen && !borderCopyPending && (bordersChecked || borderAttempts > 0) && videoWidth > 0 && videoHeight > 0 && playback.prepared && !playback.seeking && !disposed && !failed) {
            handler.removeCallbacks(timeout)
            onFrame(true)
        }
    }

    private fun inspectDecodedBorders() {
        if (bordersChecked || borderCopyPending || videoWidth <= 0 || videoHeight <= 0 ||
            SystemClock.uptimeMillis() < nextBorderCheckAt) return
        val surface = output?.takeIf { it.isValid } ?: return
        borderCopyPending = true
        // Preserve thin encoded borders in ultra-wide sources; this copies the raw
        // decoder surface, so neither the page transform nor shadows affect detection.
        val sampleWidth = 256
        val sampleHeight = 144
        val sample = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, sample, { result ->
                try {
                    if (output !== surface) return@request
                    borderCopyPending = false
                    if (!disposed && !failed && result == PixelCopy.SUCCESS) {
                        val pixels = IntArray(sampleWidth * sampleHeight)
                        sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
                        val detected = mvLetterbox(pixels, sampleWidth, sampleHeight)
                        if (detected != MvLetterbox()) {
                            letterbox = detected
                            bordersChecked = true
                            updateVideoTransform()
                        }
                    }
                    // A dark first frame may hide the border; retry a few frames only.
                    borderAttempts++
                    if (borderAttempts >= 4) bordersChecked = true
                    nextBorderCheckAt = SystemClock.uptimeMillis() + 350L
                    revealFrameIfReady()
                } finally { sample.recycle() }
            }, handler)
        } catch (_: Exception) {
            borderCopyPending = false; bordersChecked = true; sample.recycle()
        }
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (disposed || failed || !playback.prepared || playback.seeking) return
        inspectDecodedBorders()
        frameSeen = true
        revealFrameIfReady()
        if (previewing) { previewing = false; syncPlayback() }
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releasePlayer()
        if (!disposed) onFrame(false)
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Some window reattachments retain an already available SurfaceTexture.
        if (isAvailable) surfaceTexture?.let { onSurfaceTextureAvailable(it, width, height) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    private fun fail() {
        if (disposed || failed) return
        failed = true
        releasePlayer()
        Log.w("MvBackdrop", "MV background unavailable; retaining artwork")
        onFailure()
    }

    private fun releasePlayer() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(loopCheck)
        playback.reset()
        frameSeen = false
        bordersChecked = false; borderAttempts = 0; borderCopyPending = false; nextBorderCheckAt = 0L
        previewing = false
        player?.apply {
            MvWarmPlayer.detach(this)
            setOnPreparedListener(null)
            setOnInfoListener(null)
            setOnSeekCompleteListener(null)
            setOnCompletionListener(null)
            setOnErrorListener(null)
            setOnVideoSizeChangedListener(null)
            release()
        }
        player = null
        videoWidth = 0
        videoHeight = 0
        letterbox = MvLetterbox()
        bordersChecked = false
        output?.release()
        output = null
    }

    fun dispose() {
        disposed = true
        releasePlayer()
        surfaceTextureListener = null
    }
}
