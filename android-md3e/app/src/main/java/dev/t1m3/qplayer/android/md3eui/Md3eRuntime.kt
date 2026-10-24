package dev.t1m3.qplayer.android.md3eui

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.core.content.ContextCompat
import dev.t1m3.qplayer.android.library.AndroidMetadataReader
import dev.t1m3.qplayer.android.library.AndroidLibraryScanner
import dev.t1m3.qplayer.android.graphics.AndroidColorExtractor
import dev.t1m3.qplayer.android.playback.AndroidAudioBackend
import dev.t1m3.qplayer.android.playback.PlaybackService
import dev.t1m3.qplayer.android.settings.PrefsSettingsStore
import dev.t1m3.qplayer.bridge.PlayerController
import dev.t1m3.qplayer.bili.BiliClient
import dev.t1m3.qplayer.netease.NeteaseClient
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist
import dev.t1m3.qplayer.netease.dto.NeteaseSong
import dev.t1m3.qplayer.netease.dto.NeteaseAlbum
import dev.t1m3.qplayer.netease.dto.NeteaseArtist
import dev.t1m3.qplayer.model.Track
import dev.t1m3.qplayer.lyric.LyricLine
import dev.t1m3.qplayer.settings.SettingsCatalog
import dev.t1m3.qplayer.settings.SettingsCore
import dev.t1m3.qplayer.util.Logger
import io.github.timer_err.qml4j.engine.binding.Property
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

internal data class HomeState(
    val loading: Boolean = true,
    val error: String = "",
    val daily: List<NeteaseSong> = emptyList(),
    val sections: List<NeteaseClient.HomeSongSection> = emptyList(),
    val playlists: List<NeteasePlaylist> = emptyList(),
    val playlistSections: List<NeteaseClient.HomePlaylistSection> = emptyList(),
    val albums: List<NeteaseAlbum> = emptyList(),
    val loggedIn: Boolean = false,
    val userName: String = "",
    val loginBusy: Boolean = false,
    val loginError: String = "",
    val recent: List<NeteaseSong> = emptyList(),
)

internal data class NeteaseLoginState(
    val qrImage: List<List<Boolean>> = emptyList(),
    val qrStatus: Int = 0,
    val busy: Boolean = false,
    val error: String = "",
    val successRevision: Long = 0L,
)

internal data class LyricState(
    val lines: List<LyricLine> = emptyList(),
    val revision: Long = 0,
    val loading: Boolean = false,
    val coverOnly: Boolean = true,
    val coverModeManual: Boolean = false,
    val offsetMs: Int = 0,
    val index: Int = -1,
)

internal data class PlaybackState(
    val title: String = "还没有播放歌曲",
    val artist: String = "从推荐中选一首，开始听歌",
    val cover: String = "",
    val coverSeed: String = "",
    val playing: Boolean = false,
    val loading: Boolean = false,
    val hasTrack: Boolean = false,
    val queueSize: Int = 0,
    val duration: Long = 0,
    val songId: Long = 0,
    val albumId: Long = 0,
    val artistId: Long = 0,
    val liked: Boolean = false,
    val likeable: Boolean = false,
    val playMode: Int = 0,
    val queueIndex: Int = -1,
    val privateFmMode: Boolean = false,
    val playbackRevision: Long = 0,
    val seekRevision: Long = 0,
    val clock: PlaybackClock = PlaybackClock(),
    val album: String = "",
    val artistIdsCsv: String = "",
    val artistNamesCsv: String = "",
    val autoMixStatus: String = "",
    val autoMixRemainingSeconds: Long = -1,
    val autoMixPlanSummary: String = "",
) {
    val position: Long get() = clock.position
    val sampledAtNanos: Long get() = clock.sampledAtNanos
}

internal data class TransportState(val playing: Boolean = false, val hasTrack: Boolean = false,
    val privateFm: Boolean = false)

internal data class SearchState(
    val query: String = "",
    val mode: String = "song",
    val songs: List<NeteaseSong> = emptyList(),
    val albums: List<NeteaseAlbum> = emptyList(),
    val artists: List<NeteaseArtist> = emptyList(),
    val local: List<Track> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val history: List<String> = emptyList(),
    val hot: List<String> = emptyList(),
    val videos: List<BiliClient.BiliVideo> = emptyList(),
    val videoError: String = "",
)

internal data class PlaylistState(
    val mine: List<NeteasePlaylist> = emptyList(),
    val title: String = "",
    val cover: String = "",
    val tracks: List<NeteaseSong> = emptyList(),
    val loading: Boolean = false,
    val subscribed: Boolean = false,
    val owned: Boolean = false,
    val loggedIn: Boolean = false,
    val id: Long = 0L,
)

internal data class LocalState(
    val tracks: List<Track> = emptyList(),
    val bili: List<Track> = emptyList(),
    val scanning: Boolean = false,
    val error: String = "",
    val permissionGranted: Boolean = false,
)

internal data class ArtistDetailState(
    val name: String = "",
    val cover: String = "",
    val description: String = "",
    val songs: List<NeteaseSong> = emptyList(),
    val albums: List<NeteaseAlbum> = emptyList(),
    val loading: Boolean = false,
    val id: Long = 0L,
    val header: String = "",
)

internal data class AlbumDetailState(
    val name: String = "",
    val cover: String = "",
    val artistName: String = "",
    val tracks: List<NeteaseSong> = emptyList(),
    val loading: Boolean = false,
    val id: Long = 0L,
)

internal data class Md3ePageNavigation(val revision: Long = 0L, val target: String = "", val id: Long = 0L)

/** Process-owned playback; neither backend nor service callbacks retain an Activity. */
internal class Md3eRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    suspend fun liveEvidence(request: String, daily: Boolean = false): String = Md3eLiveEvidence.collect(app, settings, request, daily = daily)
    private val handler = Handler(Looper.getMainLooper())
    val audioBackend = AndroidAudioBackend(app).apply {
        setVideoPlaybackVisible(false)
        setVideoForeground(false)
    }
    val controller = PlayerController(audioBackend, AndroidMetadataReader(app), NeteaseClient.INSTANCE)
    val settings = SettingsCore()
    val dailyVibes = Md3eDailyVibes(app, controller, settings)
    val aiConversationHistory = dev.t1m3.qplayer.ai.AiConversationHistory(java.io.File(app.filesDir, "ai-conversations"))
    val aiHistoryDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val sonicGenres = SonicGenreBrowser()
    var eqDialogRequested by mutableStateOf(false)
    private val scanner = AndroidLibraryScanner(app.contentResolver, AndroidMetadataReader(app))
    private val scanWorker = Executors.newSingleThreadExecutor()
    private val playbackClock = PlaybackClock()
    // AnimatedContent keeps the outgoing route composed while another detail loads.
    // Keep its snapshot separate from the controller's single current-detail slot.
    private val playlistSnapshots = linkedMapOf<Long, PlaylistState>()
    private val albumSnapshots = linkedMapOf<Long, AlbumDetailState>()
    private val artistSnapshots = linkedMapOf<Long, ArtistDetailState>()
    var home by mutableStateOf(HomeState())
        private set
    var neteaseLogin by mutableStateOf(NeteaseLoginState())
        private set
    var playback by mutableStateOf(PlaybackState())
    var experimentalAiDjActive by mutableStateOf(false)
    var experimentalAiDjWaiting by mutableStateOf(false)
    var experimentalAiDjMessage by mutableStateOf("")
    var experimentalAiDjStage by mutableStateOf(AiDjMascotStage.Random)
    var experimentalAiDjReason by mutableStateOf("")
        private set
    var lyricState by mutableStateOf(LyricState())
        private set
    var settingsRevision by mutableStateOf(0)
        private set
    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var search by mutableStateOf(SearchState())
        private set
    var playlists by mutableStateOf(PlaylistState())
        private set
    var local by mutableStateOf(LocalState())
        private set
    var artistDetail by mutableStateOf(ArtistDetailState())
        private set
    var albumDetail by mutableStateOf(AlbumDetailState())
        private set
    var pageNavigation by mutableStateOf(Md3ePageNavigation())
        private set
    var unblockEnabled by mutableStateOf(true)
        private set
    var notice by mutableStateOf<String?>(null)
    /** 设置 → AI 的「AI 对话测试」对话框开关（Md3eAiChatProbe.kt）。 */
    var aiChatProbeRequested by mutableStateOf(false)
    var aiSearchProbeRequested by mutableStateOf(false)
    var weatherLocationRequested by mutableStateOf(false)
    var bili by mutableStateOf(BiliState())
        private set
    val videoSlots = androidx.compose.runtime.mutableStateMapOf<Int, androidx.compose.ui.geometry.Rect>()
    var videoFullscreen by mutableStateOf(false)
    var videoVisible by mutableStateOf(true)
    var videoWidth by mutableStateOf(0)
        private set
    var videoHeight by mutableStateOf(0)
        private set
    private val biliCookies = java.io.File(app.filesDir, "bili_cookies.txt")
    private var lastBiliLoggedIn = false
    var uiVisible by mutableStateOf(false)
        private set
    var transportMotionActive by mutableStateOf(false)
        private set
    var requestedRefreshRate by mutableStateOf(60f)
        private set
    var reducedRendering by mutableStateOf(false)
        private set

    fun updateRenderProfile(refreshRate: Float, reduced: Boolean) {
        requestedRefreshRate = refreshRate
        reducedRendering = reducedRendering || reduced
    }

    var transportState by mutableStateOf(TransportState())
        private set
    private val transportVisualGate = TransportVisualGate()
    private var publishedPlaybackTrack: Track? = null

    fun setTransportMotionActive(owner: Any, active: Boolean) {
        val changed = transportVisualGate.setActive(owner, active)
        if (changed && transportVisualGate.active) {
            // The visual gate closes synchronously, but notify the broad theme/
            // backdrop composition only AFTER the native first frame is sent.
            Choreographer.getInstance().postFrameCallback {
                handler.post { transportMotionActive = transportVisualGate.active }
            }
        } else if (changed) transportMotionActive = false
        if (changed && !transportVisualGate.active) {
            // Read the latest controller state once; never replay intermediate
            // loading/cover/lyric snapshots accumulated during the spring.
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    private var visibleHosts = 0
    private var loadedHotSearches = false
    private var lastLoggedIn = false

    init {
        controller.setMainExecutor { handler.post(it) }
        controller.setColorExtractor(AndroidColorExtractor())
        runCatching { biliCookies.takeIf { it.exists() }?.readText() }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let(controller::restoreBiliSession)
        audioBackend.setVideoSizeListener { width, height -> handler.post {
            videoWidth = width
            videoHeight = height
        } }
        settings.setNativeFontRendererEnabled(false)
        settings.attach(controller)
        settings.registerAction("clearCache", controller::clearDiskCache)
        settings.registerAction("checkUpdate", controller::checkForUpdateManual)
        settings.registerAction("openRepo") {
            controller.openExternalUrl("https://github.com/TIMER-err/qplayer")
        }
        // 保后台（「像用户发送申请保后台」）：打开系统的「忽略电池优化」申请对话框；
        // 状态行读 PowerManager 的真实结果，从系统页返回时（onVisible）会刷新。
        settings.registerInfo("batteryStatus") { batteryOptimizationStatus() }
        settings.registerAction("backgroundKeepAlive") { requestIgnoreBatteryOptimizations() }
        // The log system: export copies the newest session file out, and the
        // flag is applied at startup and on every settings change (idempotent).
        settings.registerAction("logExport") {
            val path = LogCapture.export(app)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(app,
                    if (path != null) "日志已导出：$path"
                    else "没有可导出的日志（先打开记录运行日志）",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        settings.registerInfo("version") { "v${controller.appVersion.peek()}" }
        settings.registerInfo("cacheUsage") { "${controller.cacheSizeMB.peek()} MB" }
        settings.load(PrefsSettingsStore(app), SettingsCatalog.ANDROID)
        LogCapture.setEnabled(app, settings.bool("logCaptureEnabled"))
        // The equalizer re-attaches to every new audio session and follows the
        // 音效 settings live.
        // The EQ lives in the backend: push the stored values once, and it applies
        // them whenever a player session exists.
        pushEqToBackend()
        unblockEnabled = settings.bool("unblock")
        attachService()
        controller.preloadHome()
        controller.refreshBiliCachedSongs()
    }

    private val tick = object : Runnable {
        override fun run() {
            drainCoreQueue()
            publishState()
            // Drain core UI work in the background while the playback service owns the
            // session. No frame loop or Activity reference is needed for audio to advance.
            if (visibleHosts > 0 || PlaybackService.isRunning() || controller.isPlaying()) {
                // Match the legacy lyric clock's 100 ms foreground samples.
                handler.postDelayed(this, if (visibleHosts > 0 && controller.isPlaying()) 100 else 250)
            }
        }
    }

    private var coreDrainScheduled = false
    private var lastSlowPumpLog = 0L
    private var lastSlowSnapshotLog = 0L
    private var lastSnapshotAt = 0L
    private var lastSnapshotVersion = -1L
    private var lastSnapshotPlaybackRevision = -1L
    private var lastSnapshotSeekRevision = -1L
    private var lastSnapshotTransport: TransportState? = null
    private val coreDrainFrame = Choreographer.FrameCallback {
        coreDrainScheduled = false
        drainCoreQueue()
    }
    private val coreDrainWorker = Runnable {
        coreDrainScheduled = false
        drainCoreQueue()
    }

    private fun drainCoreQueue() {
        val start = System.nanoTime()
        Trace.beginSection("QPlayer.corePump")
        val pending = try {
            // At 120Hz this leaves most of the 8.33ms frame for composition/draw.
            controller.pump(2_000_000L, 16)
        } finally { Trace.endSection() }
        val elapsed = System.nanoTime() - start
        if (elapsed > 16_000_000L && start - lastSlowPumpLog > 5_000_000_000L) {
            lastSlowPumpLog = start
            Logger.warn("MD3E slow core pump: {} ms, pending={}", elapsed / 1_000_000L, pending)
        }
        if (pending && !coreDrainScheduled) {
            coreDrainScheduled = true
            if (visibleHosts > 0) Choreographer.getInstance().postFrameCallback(coreDrainFrame)
            else handler.postDelayed(coreDrainWorker, 16L)
        }
    }

    private fun publishState() {
        val loggedIn = controller.loggedIn.peek()
        if (loggedIn && !lastLoggedIn) controller.loadMyPlaylists()
        lastLoggedIn = loggedIn
        val biliLoggedIn = controller.biliLoggedIn.peek()
        if (biliLoggedIn != lastBiliLoggedIn) {
            lastBiliLoggedIn = biliLoggedIn
            val cookies = controller.biliClient().cookieHeader()
            scanWorker.execute { runCatching { biliCookies.writeText(cookies) } }
        }
        // Keep pumping audio/service work in the background without allocating
        // and publishing every page's visual snapshot.
        if (visibleHosts > 0) {
            transportState = TransportState(controller.isPlaying(), controller.currentTrack() != null,
                controller.privateFmActive.peek() == true)
        }
        // Freeze one coherent visual snapshot, including its clock, while
        // transport RenderNodes animate. Audio/service callbacks still pump.
        if (visibleHosts <= 0 || transportVisualGate.active) return
        // The clock is independent of page metadata and continues smoothly.
        playbackClock.publish(controller.mediaSessionPosition().coerceAtLeast(0),
            System.nanoTime(), controller.isPlaying())
        val snapshotStart = System.nanoTime()
        val version = Property.changeVersion()
        val playbackRevision = controller.playbackRevision()
        val seekRevision = controller.seekRevision()
        if (version == lastSnapshotVersion && playbackRevision == lastSnapshotPlaybackRevision &&
            seekRevision == lastSnapshotSeekRevision && transportState == lastSnapshotTransport &&
            controller.currentTrack() === publishedPlaybackTrack &&
            snapshotStart - lastSnapshotAt < 1_000_000_000L) return
        Trace.beginSection("QPlayer.uiSnapshot")
        try {
            Snapshot.withMutableSnapshot {
                home = HomeState(
                    loading = controller.homeLoading.peek(),
                    error = controller.homeFeedError.peek(),
                    daily = controller.recommendations.peek(),
                    sections = controller.homeSongSections.peek(),
                    playlists = controller.recommendPlaylists.peek(),
                    playlistSections = controller.homePlaylistSections.peek(),
                    albums = controller.homeAlbumRecommendations.peek(),
                    loggedIn = controller.loggedIn.peek(),
                    userName = controller.userName.peek(),
                    loginBusy = controller.webLoginBusy.peek(),
                    loginError = controller.webLoginError.peek(),
                    recent = controller.recentSongs.peek(),
                )
                neteaseLogin = NeteaseLoginState(
                    qrImage = controller.qrImage.peek(),
                    qrStatus = controller.qrStatus.peek(),
                    busy = controller.webLoginBusy.peek(),
                    error = controller.qrLoginError.peek(),
                    successRevision = controller.webLoginSuccessRevision.peek(),
                )
                val currentTrack = controller.currentTrack()
                experimentalAiDjActive = controller.experimentalAiDjActive.peek() == true
                experimentalAiDjWaiting = controller.experimentalAiDjWaiting.peek() == true
                experimentalAiDjMessage = controller.experimentalAiDjMessage.peek().orEmpty()
                experimentalAiDjStage = aiDjMascotStage(experimentalAiDjMessage, controller.aiProgress.peek().orEmpty())
                experimentalAiDjReason = controller.experimentalAiDjReason.peek().orEmpty()
                publishedPlaybackTrack = currentTrack
                val hasTrack = currentTrack != null
                val songId = currentTrack?.neteaseId ?: 0L
                val nextPlayback = PlaybackState(
                    controller.title.peek().ifBlank { "还没有播放歌曲" },
                    controller.artist.peek().ifBlank { if (hasTrack) "未知歌手" else "从推荐中选一首，开始听歌" },
                    controller.coverPath.peek().ifBlank { controller.coverUrl.peek() },
                    controller.coverSeed.peek(),
                    controller.isPlaying(), controller.loading.peek(),
                    hasTrack, controller.queueTracks.peek().size,
                    controller.durationMs.peek().coerceAtLeast(0),
                    songId, controller.playingAlbumId.peek(), controller.playingArtistId.peek(),
                    controller.currentLiked.peek(), songId != 0L && loggedIn,
                    controller.playMode.peek(), controller.index.peek(),
                    controller.privateFmActive.peek() == true,
                    controller.playbackRevision(), controller.seekRevision(), playbackClock,
                    currentTrack?.album.orEmpty(), currentTrack?.artistIdsCsv.orEmpty(),
                    currentTrack?.artistNamesCsv.orEmpty(),
                    controller.autoMixStatus.peek().orEmpty(),
                    controller.autoMixRemainingSeconds.peek(),
                    controller.autoMixPlanSummary.peek().orEmpty(),
                )
                // The clock is observable on its own. Avoid invalidating every
                // playback consumer at the sampling cadence when no metadata or
                // control state changed; this keeps the 100 ms pump off the main
                // composition path while lyric/progress draw code still sees time.
                val displayed = if (experimentalAiDjWaiting) nextPlayback.copy(
                    title = experimentalAiDjMessage, artist = "", cover = "", coverSeed = "",
                    playing = false, loading = true, hasTrack = true, duration = 0,
                    songId = 0L, albumId = 0L, artistId = 0L, liked = false, likeable = false,
                    album = "", artistIdsCsv = "", artistNamesCsv = "") else nextPlayback
                if (displayed != playback) playback = displayed
                lyricState = LyricState(
                    lines = controller.lyrics.peek(),
                    revision = controller.lyricsRevision.peek(),
                    loading = controller.lyricsLoading.peek(),
                    coverOnly = controller.lyricsCoverOnly.peek(),
                    coverModeManual = controller.coverModeManual.peek(),
                    offsetMs = controller.lyricOffsetMs.peek(),
                    // Line selection belongs to the visible lyric consumer.
                )
                queue = controller.queueTracks.peek()
                bili = BiliState(
                    loggedIn = controller.biliLoggedIn.peek(),
                    playing = controller.biliPlaying.peek() && !experimentalAiDjWaiting,
                    bvid = controller.currentTrack()?.biliBvid.orEmpty(),
                    queueVideo = controller.currentTrack()?.source == Track.Source.BILI,
                    queueMode = controller.biliQueueMode.peek(),
                    queueTargetMode = controller.biliQueueTargetMode.peek(),
                    queueHasCollection = controller.biliQueueHasCollection.peek(),
                    queueSwitching = controller.biliQueueSwitching.peek(),
                    qrUrl = controller.biliQrUrl.peek(), qrStatus = controller.biliQrStatus.peek(),
                    error = controller.biliError.peek(),
                    chapters = controller.biliChapterMarks.peek(),
                    folders = controller.biliFavFolders.peek(), foldersLoading = controller.biliFavLoading.peek(),
                    favError = controller.biliFavError.peek(), items = controller.biliFavItems.peek(),
                    itemsLoading = controller.biliFavItemsLoading.peek(), folderTitle = controller.biliFavItemsTitle.peek(),
                )
                if (!bili.playing) videoFullscreen = false
                search = search.copy(
                    mode = search.mode, songs = controller.searchResults.peek(),
                    albums = controller.searchAlbumResults.peek(), artists = controller.searchArtistResults.peek(),
                    local = controller.localSearchResults.peek(), loading = controller.searchLoading.peek(),
                    hasMore = controller.searchHasMore.peek(), history = controller.searchHistory.peek(),
                    hot = controller.hotSearches.peek(), videos = controller.biliSearchResults.peek(),
                    videoError = controller.biliError.peek(),
                )
                if (search.mode == "bili") search = search.copy(loading = controller.biliSearchLoading.peek(), hasMore = false)
                val playlistId = controller.openPlaylistId.peek()
                if (playlists.id != playlistId) rememberDetail(playlistSnapshots, playlists.id, playlists)
                val previousPlaylist = playlists.takeIf { it.id == playlistId }
                playlists = PlaylistState(
                    controller.myPlaylists.peek(), controller.playlistTitle.peek().ifBlank { previousPlaylist?.title.orEmpty() },
                    controller.playlistCoverPath.peek().ifBlank { previousPlaylist?.cover.orEmpty() }, controller.playlistTracks.peek(),
                    controller.playlistLoading.peek(), controller.playlistSubscribed.peek(),
                    controller.playlistOwned.peek(), loggedIn, playlistId,
                )
                local = local.copy(tracks = controller.tracks.peek(), bili = controller.biliCachedSongs.peek())
                val artistId = controller.openArtistId.peek()
                if (artistDetail.id != artistId) rememberDetail(artistSnapshots, artistDetail.id, artistDetail)
                val previousArtist = artistDetail.takeIf { it.id == artistId }
                artistDetail = ArtistDetailState(
                    controller.artistName.peek().ifBlank { previousArtist?.name.orEmpty() },
                    controller.artistCoverPath.peek().ifBlank { previousArtist?.cover.orEmpty() },
                    controller.artistBriefDesc.peek(), controller.artistSongs.peek(),
                    controller.artistAlbums.peek(), controller.artistLoading.peek(), artistId,
                    controller.artistHeaderPath.peek().ifBlank { previousArtist?.header.orEmpty() },
                )
                val albumId = controller.openAlbumId.peek()
                if (albumDetail.id != albumId) rememberDetail(albumSnapshots, albumDetail.id, albumDetail)
                val previousAlbum = albumDetail.takeIf { it.id == albumId }
                albumDetail = AlbumDetailState(
                    controller.albumName.peek().ifBlank { previousAlbum?.name.orEmpty() },
                    controller.albumCoverPath.peek().ifBlank { previousAlbum?.cover.orEmpty() },
                    controller.albumArtistName.peek().ifBlank { previousAlbum?.artistName.orEmpty() }, controller.albumTracks.peek(),
                    controller.albumLoading.peek(), albumId,
                )
                val navigationRevision = controller.pageNavigationRevision.peek()
                if (navigationRevision != pageNavigation.revision) {
                    val target = controller.pageNavigationTarget.peek()
                    pageNavigation = Md3ePageNavigation(navigationRevision, target, when (target) {
                        "album" -> controller.openAlbumId.peek()
                        "artist" -> controller.openArtistId.peek()
                        else -> 0L
                    })
                }
                controller.toast.peek().takeIf { it.isNotBlank() }?.let {
                    notice = it
                    controller.toast.set("")
                }
            }
            lastSnapshotAt = snapshotStart
            lastSnapshotVersion = version
            lastSnapshotPlaybackRevision = playbackRevision
            lastSnapshotSeekRevision = seekRevision
            lastSnapshotTransport = transportState
        } finally {
            Trace.endSection()
            val elapsed = System.nanoTime() - snapshotStart
            if (elapsed > 16_000_000L && snapshotStart - lastSlowSnapshotLog > 5_000_000_000L) {
                lastSlowSnapshotLog = snapshotStart
                Logger.warn("MD3E slow snapshot: {} ms", elapsed / 1_000_000L)
            }
        }
    }

    private fun attachService() {
        PlaybackService.attachController(controller) {
            if (controller.isPlaying()) startService()
        }
    }

    private fun startService() {
        // A live service already receives controller changes in-process. Starting
        // it for every transport tap adds an IPC and a duplicate notification.
        if (PlaybackService.isRunning()) return
        try {
            ContextCompat.startForegroundService(app,
                Intent(app, PlaybackService::class.java).setAction(PlaybackService.ACTION_REFRESH))
        } catch (_: IllegalStateException) {
            notice = "后台播放服务未能启动，请返回应用重试"
        } catch (_: SecurityException) {
            notice = "系统未允许启动播放服务，请返回应用重试"
        }
    }

    private fun batteryOptimizationStatus(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "系统无需设置"
        val power = app.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return "无法读取电池优化状态"
        return if (power.isIgnoringBatteryOptimizations(app.packageName)) "已加入电池优化白名单"
        else "未加入白名单，后台播放可能被系统冻结"
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val power = app.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (power != null && power.isIgnoringBatteryOptimizations(app.packageName)) {
            notice = "已加入电池优化白名单，无需重复申请"
            return
        }
        val request = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(request)
        } catch (ignored: Exception) {
            // 少数 ROM 没有这个对话框，退回电池优化列表页。
            try {
                app.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ignored2: Exception) {
                notice = "系统没有提供电池优化设置页面"
            }
        }
    }

    suspend fun importMigratedAccounts(accounts: dev.t1m3.qplayer.store.AccountTransfer.Accounts): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val messages = mutableListOf<String>()
            if (accounts.netease.isNotEmpty()) {
                try {
                    dev.t1m3.qplayer.netease.NeteaseClient.INSTANCE.importLoginCookies(accounts.netease)
                    controller.refreshImportedAccount()
                    messages += "网易云：已验证并导入，正在刷新账号与歌单。"
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { messages += "网易云：未导入。登录可能已过期，或验证/保存失败，原账号保持不变。" }
            }
            if (accounts.bili.isNotEmpty()) {
                try {
                    val candidate = dev.t1m3.qplayer.bili.BiliClient(15_000)
                    candidate.setCookieHeader(accounts.bili)
                    check(candidate.verifyLogin())
                    val verified = candidate.cookieHeader()
                    dev.t1m3.qplayer.store.StorageFiles.writeUtf8Atomic(biliCookies.toPath(), verified, true)
                    controller.restoreBiliSession(verified)
                    messages += "B 站：已验证并导入。"
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { messages += "B 站：未导入。登录可能已过期，或验证/保存失败，原账号保持不变。" }
            }
            messages.joinToString("\n")
        }

    fun onVisible() {
        visibleHosts++
        uiVisible = true
        audioBackend.setVideoForeground(true)
        attachService()
        // 从系统页面（电池优化申请等）返回时刷新设置行的状态文本。
        settingsRevision++
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun onHidden() {
        visibleHosts = (visibleHosts - 1).coerceAtLeast(0)
        uiVisible = visibleHosts > 0
        audioBackend.setVideoForeground(uiVisible)
        if (!uiVisible && coreDrainScheduled) {
            Choreographer.getInstance().removeFrameCallback(coreDrainFrame)
            handler.removeCallbacks(coreDrainWorker)
            coreDrainScheduled = false
            drainCoreQueue()
        }
        controller.saveSessionState()
    }

    fun play(action: PlayerController.() -> Unit) {
        // Promote while the user's tap is in the foreground, before network resolution.
        startService()
        controller.action()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    // A buffered lyric/progress/like view must not act on a different song.
    fun seekDisplayed(state: PlaybackState, position: Long) {
        if (publishedPlaybackTrack != null && publishedPlaybackTrack === controller.currentTrack() &&
            state.playbackRevision == controller.playbackRevision()) play { seek(position) }
    }

    fun likeDisplayed(state: PlaybackState) {
        if (publishedPlaybackTrack != null && publishedPlaybackTrack === controller.currentTrack() &&
            state.playbackRevision == controller.playbackRevision()) play { toggleLike() }
    }

    fun toggle() = play { toggle() }

    /** 听歌识曲的「试听」/「就是这首」：按网易云 id 播放一条候选。 */
    fun playById(id: Long) {
        controller.playNetease(id)
    }
    fun previous() = play { prev() }
    fun next() = play { next() }

    fun updateUnblockEnabled(enabled: Boolean) {
        updateSetting("unblock", enabled)
    }

    fun updateDesignMode(mode: Int) {
        require(mode in 0..3)
        settings.put("claudeDesign", mode == 0)
        settings.put("iosDesign", mode == 2)
        settings.put("sonicDesign", mode == 3)
        settingsRevision++
    }

    /** Parse "eqBands" (CSV of millibels per band). */
    fun eqBandLevels(): List<Int> =
        settings.str("eqBands").takeIf { it.isNotBlank() }
            ?.split(",")?.map { it.trim().toIntOrNull() ?: 0 }.orEmpty()

    fun eqBassStrength(): Int = (settings.str("eqBass").trim().toIntOrNull() ?: 0).coerceIn(0, 1000)

    fun setEqBandLevel(index: Int, millibels: Int) {
        if (index !in 0 until audioBackend.eqBandCount()) return
        val levels = eqBandLevels().toMutableList()
        while (levels.size <= index) levels.add(0)
        val range = audioBackend.eqBandRange()
        levels[index] = millibels.coerceIn(range[0], range[1])
        settings.put("eqBands", levels.joinToString(","))
        pushEqToBackend()
    }

    fun setEqBass(strength: Int) {
        settings.put("eqBass", strength.coerceIn(0, 1000).toString())
        pushEqToBackend()
    }

    fun resetEq() {
        settings.put("eqBands", "")
        settings.put("eqBass", "0")
        pushEqToBackend()
    }

    /** Ⓜ Quick shapes so the EQ can be heard immediately（「这玩意咋调的」）: millibel
     *  levels against a 5-band 60/230/910/3600/14000 layout, clipped per band by the
     *  backend to the device's real range anyway. */
    fun applyEqPreset(which: String) {
        when (which) {
            "bass" -> {
                settings.put("eqBands", "900,600,150,-200,-300")
                settings.put("eqBass", "700")
            }
            "vocal" -> {
                settings.put("eqBands", "-300,-100,500,400,150")
                settings.put("eqBass", "0")
            }
            else -> {
                settings.put("eqBands", "")
                settings.put("eqBass", "0")
            }
        }
        pushEqToBackend()
    }

    private fun pushEqToBackend() {
        audioBackend.setEqualizer(settings.bool("eqEnabled"),
            eqBandLevels().toIntArray(), eqBassStrength())
    }

    fun updateSetting(key: String, value: Any) {
        settings.put(key, value)
        unblockEnabled = settings.bool("unblock")
        settingsRevision++
        LogCapture.setEnabled(app, settings.bool("logCaptureEnabled"))
        if (key == "eqEnabled" || key == "eqBands" || key == "eqBass") {
            pushEqToBackend()
        }
    }

    fun invokeSetting(action: String) {
        settings.invoke(action)
        settingsRevision++
    }

    fun loadHotSearches() {
        if (!loadedHotSearches) {
            loadedHotSearches = true
            controller.loadHotSearches()
        }
    }

    fun search(query: String, mode: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val selectedMode = mode.takeIf { it == "album" || it == "artist" || it == "bili" } ?: "song"
        if (selectedMode != "bili" && controller.searchMode.peek() != selectedMode) controller.setSearchMode(selectedMode)
        search = search.copy(query = trimmed, mode = selectedMode)
        controller.addSearchHistory(trimmed)
        when (selectedMode) {
            "bili" -> controller.searchBili(trimmed)
            "album" -> controller.searchAlbums(trimmed)
            "artist" -> controller.searchArtists(trimmed)
            else -> {
                controller.searchLocal(trimmed)
                controller.search(trimmed)
            }
        }
    }

    fun loadMoreSearch() = controller.loadMoreSearch()
    fun loadMyPlaylists() = controller.loadMyPlaylists()
    fun playlistFor(id: Long): PlaylistState = if (playlists.id == id) playlists
        else playlistSnapshots[id] ?: PlaylistState(id = id, loading = true)

    fun albumFor(id: Long): AlbumDetailState = if (albumDetail.id == id) albumDetail
        else albumSnapshots[id] ?: AlbumDetailState(id = id, loading = true)

    fun artistFor(id: Long): ArtistDetailState = if (artistDetail.id == id) artistDetail
        else artistSnapshots[id] ?: ArtistDetailState(id = id, loading = true)

    fun openPlaylist(id: Long) {
        if (id <= 0) return
        rememberDetail(playlistSnapshots, playlists.id, playlists)
        val previous = playlistSnapshots[id]
        val item = (playlists.mine + home.playlists + home.playlistSections.flatMap { it.playlists }).firstOrNull { it.id == id }
        playlists = PlaylistState(mine = playlists.mine, id = id,
            title = previous?.title?.takeIf { it.isNotBlank() } ?: item?.name.orEmpty(),
            cover = previous?.cover?.takeIf { it.isNotBlank() } ?: item?.let { it.coverThumbPath ?: it.coverUrl }.orEmpty(),
            loading = true, loggedIn = home.loggedIn)
        controller.openPlaylist(id)
    }

    fun openArtist(id: Long) {
        if (id <= 0) return
        rememberDetail(artistSnapshots, artistDetail.id, artistDetail)
        val previous = artistSnapshots[id]
        val item = search.artists.firstOrNull { it.id == id }
        artistDetail = ArtistDetailState(id = id,
            name = previous?.name?.takeIf { it.isNotBlank() } ?: item?.name.orEmpty(),
            cover = previous?.cover?.takeIf { it.isNotBlank() } ?: item?.let { it.coverThumbPath ?: it.coverUrl }.orEmpty(),
            header = previous?.header?.takeIf { it.isNotBlank() } ?: item?.headerUrl.orEmpty(),
            loading = true)
        controller.openArtist(id)
    }

    fun openAlbum(id: Long) {
        if (id <= 0) return
        rememberDetail(albumSnapshots, albumDetail.id, albumDetail)
        val previous = albumSnapshots[id]
        val item = (search.albums + home.albums + artistDetail.albums).firstOrNull { it.id == id }
        val song = (home.daily + home.sections.flatMap { it.songs }).firstOrNull { it.albumId == id }
        albumDetail = AlbumDetailState(id = id,
            name = previous?.name?.takeIf { it.isNotBlank() } ?: item?.name ?: song?.album.orEmpty(),
            cover = previous?.cover?.takeIf { it.isNotBlank() } ?: item?.let { it.coverThumbPath ?: it.coverUrl }
                ?: song?.let { it.coverThumbPath ?: it.coverUrl }.orEmpty(),
            artistName = previous?.artistName?.takeIf { it.isNotBlank() } ?: item?.artistName ?: song?.artist.orEmpty(),
            loading = true)
        controller.openAlbum(id)
    }

    private fun <T> rememberDetail(snapshots: MutableMap<Long, T>, id: Long, state: T) {
        if (id <= 0) return
        snapshots.remove(id)
        snapshots[id] = state
        while (snapshots.size > 8) snapshots.remove(snapshots.keys.first())
    }
    fun cacheCurrentBili() = controller.cacheCurrentBili()
    fun playCachedBili(track: Track) = play { playCachedBili(track) }

    fun setLocalPermission(granted: Boolean) {
        local = local.copy(permissionGranted = granted,
            error = if (granted) "" else "需要媒体读取权限才能查看本地音乐")
    }

    fun scanLocal() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(app, permission) != PackageManager.PERMISSION_GRANTED) {
            setLocalPermission(false)
            return
        }
        if (local.scanning) return
        local = local.copy(scanning = true, error = "", permissionGranted = true)
        scanWorker.execute {
            try {
                val tracks = scanner.scan()
                handler.post {
                    controller.scanTracks(tracks)
                    drainCoreQueue()
                    local = local.copy(scanning = false, tracks = tracks)
                }
            } catch (error: Exception) {
                handler.post { local = local.copy(scanning = false, error = "本地音乐扫描失败，请重试") }
            }
        }
    }

    companion object {
        private var instance: Md3eRuntime? = null
        fun get(context: Context): Md3eRuntime = instance ?: Md3eRuntime(context).also { instance = it }
    }
}
