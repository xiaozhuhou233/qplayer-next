@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
)

package dev.t1m3.qplayer.android.md3eui

import dev.t1m3.qplayer.android.md3eui.Md3eAdaptiveButton as Button

import dev.t1m3.qplayer.android.ui.IosAwareAlertDialog as AlertDialog
import dev.t1m3.qplayer.android.ui.IosAwareIconButton as IconButton
import dev.t1m3.qplayer.android.ui.IosAwareTextButton as TextButton

import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.t1m3.qplayer.android.ui.IosPlayerDock
import dev.t1m3.qplayer.android.ui.IosNavigationDestination
import dev.t1m3.qplayer.android.ui.IosTopBarAction
import dev.t1m3.qplayer.android.ui.IosFloatingPageActions
import dev.t1m3.qplayer.android.ui.LocalIosTopBarBackdrop
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import dev.t1m3.qplayer.bridge.PlayerController
import dev.t1m3.qplayer.netease.dto.NeteaseSong
import dev.t1m3.qplayer.settings.SettingsCatalog

internal typealias PlayAction = (PlayerController.() -> Unit) -> Unit

private val Md3eTypography = Typography()

private data class Md3ePageRoute(val tab: String, val detail: String, val id: Long)

@Composable
internal fun Md3eApp(runtime: Md3eRuntime, onDarkAppearance: (Boolean) -> Unit,
    onPlay: PlayAction, onRequestAudioPermission: () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val settingsRevision = runtime.settingsRevision
    Md3ePrewarmMv(runtime)
    SideEffect { runtime.settings.setSystemDark(systemDark) }
    // The existing theme preference remains the authority; system is its default.
    val dark = when (runtime.settings.intOf("darkMode")) { 1 -> false; 2 -> true; else -> systemDark }
    var pageMotionActive by remember { mutableStateOf(false) }
    // 定时停止播放：状态在根组合里，离开队列页也继续倒数（见 Md3eSleepTimer.kt）。
    val sleepTimer = rememberMd3eSleepTimerState()
    Md3eSleepCountdown(runtime, sleepTimer)
    val playerExpansion = rememberMd3ePlayerExpansionState()
    SideEffect { onDarkAppearance(dark || (playerExpansion.active && playerExpansion.immersiveVideo)) }
    val sonicDesign = runtime.settings.bool("sonicDesign")
    val iosDesign = !sonicDesign && runtime.settings.bool("iosDesign")
    val iosCollection = rememberIosCollectionTransition()
    val claudeDesign = !sonicDesign && !iosDesign && runtime.settings.bool("claudeDesign")
    val aiDj = remember { ClaudeAiDjState() }
    val aiConversationScope = rememberCoroutineScope()
    val aiConversation = remember(runtime) { Md3eAiConversationState(runtime, aiConversationScope) }
    val privateRoam = remember { ClaudePrivateRoamState() }
    val dynamicScheme = rememberMd3eColorScheme(
        seed = runtime.playback.coverSeed,
        dark = dark,
        enabled = !sonicDesign && !claudeDesign && !iosDesign && runtime.settings.bool("monet"),
        paletteStyle = runtime.settings.intOf("paletteStyle").coerceIn(0, 3),
        paletteChroma = runtime.settings.intOf("paletteChroma").coerceIn(0, 2),
        animate = !claudeDesign && !runtime.settings.bool(SettingsCatalog.LOW_SPEC_MODE_KEY),
        paused = pageMotionActive || playerExpansion.moving || runtime.transportMotionActive || !runtime.uiVisible,
    )
    val sonicScheme = if (sonicDesign) rememberSonicColorScheme(runtime.playback.coverSeed, dark,
        enabled = runtime.settings.bool("monet"),
        animate = !runtime.settings.bool(SettingsCatalog.LOW_SPEC_MODE_KEY),
        paused = pageMotionActive || playerExpansion.moving || runtime.transportMotionActive || !runtime.uiVisible) else null
    val scheme = when { sonicScheme != null -> sonicScheme; iosDesign -> iosColorScheme(dark); claudeDesign -> claudeColorScheme(dark); else -> dynamicScheme }
    MaterialExpressiveTheme(colorScheme = scheme,
        motionScheme = if (claudeDesign) ClaudeOneTakeScheme else MotionScheme.expressive(),
        typography = when { sonicDesign -> dev.t1m3.qplayer.android.md3eui.sonic.AppTypography; claudeDesign -> ClaudeTypography; else -> Md3eTypography },
        shapes = if (sonicDesign) Shapes() else ClaudeShapes) {
        Md3eIosChromeHost(iosDesign, pageMotionActive || playerExpansion.moving || runtime.transportMotionActive,
            refraction = runtime.settings.bool("iosGlassRefraction")) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface,
            LocalClaudeDesign provides claudeDesign,
            LocalSonicDesign provides sonicDesign,
            LocalSonicDisplayFonts provides runtime.settings.bool("sonicDisplayFonts"),
            LocalIosDesign provides iosDesign,
            LocalIosCollectionTransition provides if (iosDesign) iosCollection else null,
            LocalClaudeAiDj provides aiDj,
            LocalMd3eAiConversation provides aiConversation,
            LocalClaudePrivateRoam provides privateRoam,
            LocalClaudePlayerExpansion provides playerExpansion,
            Md3eLowSpecMode provides runtime.settings.bool(SettingsCatalog.LOW_SPEC_MODE_KEY),
            LocalMd3eReducedEffects provides runtime.reducedRendering,
            LocalMd3eRenderingActive provides runtime.uiVisible,
            LocalMd3eMotionActive provides (pageMotionActive || playerExpansion.moving || runtime.transportMotionActive)) {
        val discRotation = rememberClaudeVinylRotation(runtime.playback.playing && claudeDesign)
        CompositionLocalProvider(LocalClaudeDiscRotation provides discRotation) {
        var tab by rememberSaveable { mutableStateOf("home") }
        var searchInputRequested by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(tab) { if (tab != "search") searchInputRequested = false }
        LaunchedEffect(sonicDesign) { if (!sonicDesign && tab == "profile") tab = "home" }
        var detail by rememberSaveable { mutableStateOf("") }
        var detailId by rememberSaveable { mutableLongStateOf(0L) }
        var biliFolderTitle by rememberSaveable { mutableStateOf("") }
        var biliAccountOpen by rememberSaveable { mutableStateOf(false) }
        var recognizeOpen by rememberSaveable { mutableStateOf(false) }
        var autoBiliQuery by rememberSaveable { mutableStateOf("") }
        var neteaseLoginOpen by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(runtime.home.loggedIn) {
            if (runtime.home.loggedIn) neteaseLoginOpen = false
        }
        var detailStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
        var overlay by rememberSaveable { mutableStateOf("") }
        var queueFromPlayer by rememberSaveable { mutableStateOf(false) }
        var playerOpenLyrics by rememberSaveable { mutableStateOf(false) }
        var navigatingBack by remember { mutableStateOf(false) }
        var activeSharedCoverKey by remember { mutableStateOf<String?>(null) }
        var miniPlayerVisible by remember { mutableStateOf(true) }
        LaunchedEffect(runtime.experimentalAiDjActive, runtime.experimentalAiDjWaiting) {
            if (runtime.experimentalAiDjActive && runtime.experimentalAiDjWaiting) {
                miniPlayerVisible = true
                queueFromPlayer = false
                playerOpenLyrics = false
                overlay = "player"
            }
        }
        val miniScrollConnection = rememberMd3eMiniScrollConnection { miniPlayerVisible = it }
        val route = Md3ePageRoute(tab, detail, detailId)
        val (iosScroll, iosScrollConnection) = rememberIosPageScroll(route, tab == "home" && detail.isEmpty())
        val entranceVisit = remember(route) {
            ClaudeEntranceVisit(returning = navigatingBack,
                collection = detail in setOf("playlist", "album"))
        }
        val pageTransition = updateTransition(route, label = "page_transition")
        val sharedBackdrop = remember { androidx.compose.animation.core.Animatable(0f) }
        val pageTransitionActive = pageTransition.isRunning ||
            pageTransition.currentState != pageTransition.targetState
        SideEffect { pageMotionActive = pageTransitionActive }
        RefreshIosDockMaterial(page = route,
            settled = !pageTransitionActive && !iosCollection.motion.moving,
            enabled = iosDesign)
        val routeStateHolder = rememberSaveableStateHolder()
        val lowSpec = runtime.settings.bool(SettingsCatalog.LOW_SPEC_MODE_KEY)
        val miniDockShown = (miniPlayerVisible || aiDj.shown) && detail != "settings" &&
            (overlay.isEmpty() || playerExpansion.active || overlay == "player")
        val floatingDockInset = androidx.compose.animation.core.animateDpAsState(
            targetValue = when {
                detail == "settings" -> 0.dp
                iosDesign -> if (!runtime.playback.hasTrack) 96.dp
                    else if (iosScroll.homeCollapsed || !miniPlayerVisible || detail.isNotEmpty()) 80.dp else 144.dp
                !runtime.playback.hasTrack || !miniDockShown -> 0.dp
                (claudeDesign || sonicDesign) && !runtime.experimentalAiDjWaiting -> 64.dp
                else -> 76.dp
            },
            animationSpec = tween(
                durationMillis = if (lowSpec) 0 else if (iosDesign) 420
                    else if (miniDockShown) { if (claudeDesign) ClaudeOneTake.CARRY else 400 }
                    else { if (claudeDesign) ClaudeOneTake.TRAVEL else 320 },
                easing = playerArtworkEasing),
            label = "floating controls dock clearance",
        )
        LaunchedEffect(tab, detail, detailId) {
            miniPlayerVisible = !(claudeDesign && detail == "playlist")
        }
        LaunchedEffect(runtime.playback.playbackRevision) {
            if (!(claudeDesign && detail == "playlist")) miniPlayerVisible = true
        }
        val pageTransitionPreset = runtime.settings.intOf(SettingsCatalog.PAGE_TRANSITION_KEY).let {
            if (it < SettingsCatalog.PAGE_TRANSITION_ZOOM || it > SettingsCatalog.PAGE_TRANSITION_NONE)
                SettingsCatalog.PAGE_TRANSITION_ZOOM else it
        }
        fun openDetail(kind: String, id: Long) {
            if (kind == detail && id == detailId) return
            if (aiConversation.open) {
                playerExpansion.returnToMiniSource()
                aiConversation.open = false
            }
            navigatingBack = false
            if (detail.isNotEmpty()) detailStack = detailStack + "$detail:$detailId"
            if (claudeDesign && kind == "playlist") { miniPlayerVisible = false; overlay = "" }
            if (iosDesign && kind in setOf("playlist", "album")) iosCollection.open("cover:$kind:$id")
            detail = kind
            detailId = id
            activeSharedCoverKey = when (kind) {
                "playlist" -> "cover:playlist:$id"
                "album" -> "cover:album:$id"
                else -> null
            }
        }
        var handledNavigationRevision by remember {
            mutableLongStateOf(runtime.controller.pageNavigationRevision.peek())
        }
        LaunchedEffect(runtime.pageNavigation) {
            val request = runtime.pageNavigation
            if (request.revision > handledNavigationRevision) {
                handledNavigationRevision = request.revision
                if (request.id != 0L && (request.target == "album" || request.target == "artist")) {
                    overlay = ""
                    openDetail(request.target, request.id)
                }
            }
        }
        val openVideoPlayer = {
            queueFromPlayer = false
            playerOpenLyrics = false
            overlay = "player"
        }
        LaunchedEffect(overlay) {
            if (overlay != "player") runtime.videoFullscreen = false
        }
        val closeTop = {
            navigatingBack = true
            when {
                overlay == "queue" -> overlay = if (queueFromPlayer) "player" else ""
                overlay.isNotEmpty() -> overlay = ""
                sonicDesign && aiConversation.open -> aiConversation.open = false
                detail.isNotEmpty() -> {
                    if (detail == "playlist" || detail == "album") {
                        activeSharedCoverKey = "cover:$detail:$detailId"
                    }
                    if (iosDesign && detail in setOf("playlist", "album")) iosCollection.close()
                    val previous = detailStack.lastOrNull()
                    detailStack = detailStack.dropLast(1)
                    detail = previous?.substringBeforeLast(':').orEmpty()
                    detailId = previous?.substringAfterLast(':')?.toLongOrNull() ?: 0L
                    // Restore the controller slot when returning to an older detail
                    // of the same kind; outgoing pages keep their own snapshots.
                    when (detail) {
                        "playlist" -> if (runtime.playlists.id != detailId) runtime.openPlaylist(detailId)
                        "album" -> if (runtime.albumDetail.id != detailId) runtime.openAlbum(detailId)
                        "artist" -> if (runtime.artistDetail.id != detailId) runtime.openArtist(detailId)
                    }
                }
                tab != "home" -> tab = "home"
            }
        }
        BackHandler(overlay.isNotEmpty() || (sonicDesign && aiConversation.open) || detail.isNotEmpty() || tab != "home") { closeTop() }
        LaunchedEffect(tab) {
            when (tab) {
                "playlists", "profile" -> runtime.loadMyPlaylists()
                "local" -> if (runtime.local.permissionGranted && runtime.local.tracks.isEmpty()) runtime.scanLocal()
                "search" -> runtime.loadHotSearches()
            }
        }
        val snackbar = remember { SnackbarHostState() }
        val notice = runtime.notice
        LaunchedEffect(notice) {
            if (notice != null) {
                snackbar.showSnackbar(notice, withDismissAction = true)
                if (runtime.notice == notice) runtime.notice = null
            }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (runtime.eqDialogRequested) Md3eEqualizerDialog(runtime,
                onDismiss = { runtime.eqDialogRequested = false })
            Md3ePlayerExpansionHost(
                expanded = overlay == "player" || (overlay == "queue" && queueFromPlayer),
                state = playerExpansion,
                durationMillis = if (lowSpec) 0 else 400,
                claude = claudeDesign || iosDesign,
                cover = runtime.playback.cover,
                showDisc = !runtime.experimentalAiDjWaiting,
                simpleSlide = lowSpec && !claudeDesign,
                base = {
            Box(Modifier.fillMaxSize()) {
            val conversationVisible = sonicDesign && (aiConversation.open || playerExpansion.conversationLayer != null)
            Scaffold(
                modifier = if (conversationVisible) Modifier.clearAndSetSemantics {} else Modifier,
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = if (iosDesign) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
                topBar = {
                    Box(Modifier.claudeTransitionBackdrop { sharedBackdrop.value }) {
                    if (sonicDesign) SonicTopBar(tab, detail, when (detail) {
                        "playlist" -> runtime.playlistFor(detailId).title
                        "album" -> runtime.albumFor(detailId).name
                        "artist" -> runtime.artistFor(detailId).name
                        "settings" -> "设置"
                        "listening" -> "听歌足迹"
                        "biliFolders" -> "B站收藏夹"
                        "biliFolder" -> biliFolderTitle
                        else -> ""
                    }, onBack = closeTop,
                        onQueue = { queueFromPlayer = false; overlay = "queue" },
                        onProfile = { if (detail.isEmpty()) tab = "profile" else openDetail("settings", 0L) },
                        onRecognize = { recognizeOpen = true })
                    else if (!iosDesign) Md3eMainTopBar(tab, detail, when (detail) {
                        "listening" -> "听歌足迹"
                        "playlist" -> runtime.playlistFor(detailId).title
                        "album" -> runtime.albumFor(detailId).name
                        "artist" -> runtime.artistFor(detailId).name
                        "biliFolders" -> "B站收藏夹"
                        "biliFolder" -> biliFolderTitle
                        else -> ""
                    }, runtime.home.loggedIn, runtime.bili.loggedIn, onBack = closeTop,
                        onQueue = { queueFromPlayer = false; overlay = "queue" },
                        onSettings = { openDetail("settings", 0L) },
                        onAccount = { neteaseLoginOpen = true },
                        onBiliAccount = { biliAccountOpen = true })
                    }
                },
                bottomBar = {
                    if (!iosDesign) Box(Modifier.claudeTransitionBackdrop { sharedBackdrop.value }) {
                    // Keep the system inset throughout navigation. Only the tab row's
                    // footprint changes, carrying the dock and its disc in the same layout.
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                        val navMotion = tween<androidx.compose.ui.unit.IntSize>(
                            durationMillis = if (lowSpec) 0 else ClaudeOneTake.TRAVEL,
                            easing = ClaudeOneTake.ExpoOut)
                        AnimatedVisibility(visible = detail.isEmpty(),
                            enter = expandVertically(navMotion, expandFrom = Alignment.Bottom, clip = false),
                            exit = shrinkVertically(navMotion, shrinkTowards = Alignment.Bottom, clip = false)) {
                            Md3eNavigationBar(tab, showLocalTab = runtime.settings.bool("showLocalTab"),
                                includeSystemInset = false) { selected ->
                                if (selected != tab) {
                                    navigatingBack = false
                                    activeSharedCoverKey = null
                                    detail = ""
                                    detailId = 0L
                                    detailStack = emptyList()
                                    tab = selected
                                    miniPlayerVisible = true
                                }
                            }
                        }
                    }
                    }
                },
            ) { insets ->
                Box(Modifier.fillMaxSize().padding(if (iosDesign) PaddingValues(0.dp) else insets)
                    .nestedScroll(if (iosDesign) iosScrollConnection else miniScrollConnection)) {
                SharedTransitionLayout(Modifier.fillMaxSize().then(
                    if (iosDesign) Modifier.layerBackdrop(LocalIosPageBackdrop.current as LayerBackdrop) else Modifier)) {
                    LaunchedEffect(isTransitionActive, pageTransitionActive, route) {
                        if (!isTransitionActive && !pageTransitionActive &&
                            route.detail !in setOf("playlist", "album")) {
                            // A completed return must release the departed collection's
                            // source card instead of keeping it in a shared overlay.
                            activeSharedCoverKey = null
                        }
                    }
                    IosCollectionMotionHost(iosCollection) {
                    ClaudeSharedBackdropFocus(isTransitionActive, route, sharedBackdrop, ClaudeOneTake.COLLECTION)
                    CompositionLocalProvider(
                        Md3eSharedScope provides this,
                        Md3eActiveCoverKey provides activeSharedCoverKey,
                        Md3eLowSpecMode provides lowSpec,
                        LocalMd3eFloatingDockInset provides floatingDockInset,
                        LocalMd3eDockInset provides if (iosDesign && detail != "settings") {
                            if (runtime.playback.hasTrack) 144.dp else 96.dp
                        } else if (runtime.playback.hasTrack && detail != "settings") {
                            68.dp
                        } else 0.dp,
                    ) {
                        pageTransition.AnimatedContent(
                            modifier = Modifier.fillMaxSize().claudeTransitionBackdrop { sharedBackdrop.value },
                            transitionSpec = {
                                val detailInvolved = initialState.detail.isNotEmpty() || targetState.detail.isNotEmpty()
                                when {
                                    iosDesign && activeSharedCoverKey != null ->
                                        (androidx.compose.animation.fadeIn(tween(650), initialAlpha = 1f) togetherWith
                                            androidx.compose.animation.fadeOut(tween(650), targetAlpha = 1f)).apply {
                                            targetContentZIndex = if (navigatingBack) -1f else 1f
                                        }.using(null)
                                    lowSpec -> Md3eMotion.lowSpecPage(!navigatingBack)
                                    claudeDesign && !detailInvolved -> ClaudeOneTake.page(
                                        listOf("home", "playlists", "local", "search").indexOf(targetState.tab) >
                                            listOf("home", "playlists", "local", "search").indexOf(initialState.tab))
                                    activeSharedCoverKey != null &&
                                        (initialState.detail in setOf("playlist", "album") ||
                                            targetState.detail in setOf("playlist", "album")) ->
                                        Md3eMotion.collectionPage().using(null)
                                    claudeDesign -> ClaudeOneTake.page(!navigatingBack)
                                    detailInvolved -> Md3eMotion.sealDetail(!navigatingBack)
                                    else -> Md3eMotion.page(!navigatingBack, pageTransitionPreset)
                                }
                            },
                        ) { visibleRoute ->
                            var visit by remember { mutableStateOf(entranceVisit) }
                            if (visibleRoute == route && visit !== entranceVisit) visit = entranceVisit
                            routeStateHolder.SaveableStateProvider("${visibleRoute.tab}:${visibleRoute.detail}:${visibleRoute.id}") {
                                CompositionLocalProvider(Md3eAnimatedScope provides this, LocalClaudeEntranceVisit provides visit) {
                                    // Each scrolling page owns its initial chrome clearance.
                                    // A fixed outer inset would leave an empty strip after scrolling.
                                    val pageModifier = Modifier.fillMaxSize().then(
                                        if (sonicDesign) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)
                                    val collectionKey = if (visibleRoute.detail in setOf("playlist", "album"))
                                        "cover:${visibleRoute.detail}:${visibleRoute.id}" else null
                                    Md3eCollectionContainer(collectionKey, Modifier.fillMaxSize(), detail = true) {
when {
                                        visibleRoute.detail == "playlist" -> Md3ePlaylistDetail(runtime.playlistFor(visibleRoute.id), visibleRoute.id,
                                            onBack = closeTop,
                                            onRefresh = { runtime.openPlaylist(visibleRoute.id) },
                                            onEnqueue = runtime.controller::enqueueNeteaseSong,
                                            onPlay = onPlay, modifier = pageModifier)
                                        visibleRoute.detail == "album" -> Md3eAlbumDetail(runtime.albumFor(visibleRoute.id), visibleRoute.id,
                                            onBack = closeTop, onPlay = onPlay,
                                            onEnqueue = runtime.controller::enqueueNeteaseSong, modifier = pageModifier)
                                        visibleRoute.detail == "artist" -> Md3eArtistDetail(runtime.artistFor(visibleRoute.id),
                                            onBack = closeTop,
                                            onAlbum = runtime::openAlbum,
                                            onEnqueue = runtime.controller::enqueueNeteaseSong,
                                            onPlay = onPlay, modifier = pageModifier, playback = runtime.playback)
                                        visibleRoute.detail == "settings" -> Md3eSettingsPage(runtime, modifier = pageModifier)
                                        visibleRoute.detail == "listening" -> Md3eListeningPage(runtime,
                                            onLogin = { neteaseLoginOpen = true }, onPlay = onPlay, modifier = pageModifier)
                                        visibleRoute.detail == "biliFolders" -> Md3eBiliFavFoldersPage(runtime,
                                            onLogin = { biliAccountOpen = true },
                                            onOpen = { id, title -> biliFolderTitle = title; openDetail("biliFolder", id) },
                                            modifier = pageModifier, onOpenPlayer = openVideoPlayer)
                                        visibleRoute.detail == "biliFolder" -> Md3eBiliFavItemsPage(runtime,
                                            visibleRoute.id, biliFolderTitle, modifier = pageModifier,
                                            onOpenPlayer = openVideoPlayer)
                                        sonicDesign && visibleRoute.tab == "profile" -> SonicProfilePage(runtime,
                                            onLogin = { neteaseLoginOpen = true }, onBiliLogin = { biliAccountOpen = true },
                                            onLibrary = { tab = "playlists" }, onLocal = { tab = "local" },
                                            onListening = { openDetail("listening", 0L) },
                                            onQueue = { queueFromPlayer = false; overlay = "queue" }, modifier = pageModifier)
                                        sonicDesign && visibleRoute.tab == "home" -> SonicHomePage(runtime, { neteaseLoginOpen = true }, onPlay,
                                            onOpenPlaylist = { id -> runtime.openPlaylist(id); openDetail("playlist", id) },
                                            onSearch = { searchInputRequested = true; tab = "search" }, onRecognize = { recognizeOpen = true },
                                            onOpenAlbum = runtime::openAlbum, timer = sleepTimer, modifier = pageModifier)
                                        visibleRoute.tab == "home" -> Md3eHomePage(runtime, { neteaseLoginOpen = true }, onPlay,
                                            onOpenPlaylist = { id -> runtime.openPlaylist(id); openDetail("playlist", id) },
                                            onOpenAlbum = runtime::openAlbum,
                                            onRefresh = runtime.controller::loadHome, modifier = pageModifier)
                                        visibleRoute.tab == "playlists" -> Md3ePlaylistsPage(runtime.playlists, { neteaseLoginOpen = true },
                                            onRefresh = runtime::loadMyPlaylists,
                                            onOpen = { id -> runtime.openPlaylist(id); openDetail("playlist", id) },
                                            onDelete = { p -> if (p.owned) runtime.controller.deletePlaylist(p.id) {
                                                runtime.dailyVibes.forgetSavedPlaylist(p.id)
                                            }
                                                else runtime.controller.unsubscribePlaylist(p.id) },
                                            canDelete = runtime.controller::canDeletePlaylist,
                                            modifier = pageModifier,
                                            onPlayPlaylist = { id -> onPlay { playPlaylist(id) } },
                                            onLocal = { tab = "local" }, onCreate = runtime.controller::createPlaylist)
                                        visibleRoute.tab == "local" -> Md3eLocalPage(runtime.local, onRequestAudioPermission,
                                            onRefresh = runtime::scanLocal, onPlay = onPlay,
                                            onEnqueue = runtime.controller::enqueueTrack,
                                            onPlayBili = runtime::playCachedBili, modifier = pageModifier)
                                        else -> Md3eSearchPage(runtime.search,
                                            onSearch = runtime::search, onLoadMore = runtime::loadMoreSearch,
                                            onClearHistory = { runtime.controller.clearSearchHistory() },
                                            onOpenAlbum = runtime::openAlbum,
                                            onOpenArtist = runtime::openArtist,
                                            onEnqueueSong = runtime.controller::enqueueNeteaseSong,
                                            onEnqueueTrack = runtime.controller::enqueueTrack,
                                            onEnqueueVideo = runtime.controller::enqueueBiliVideo,
                                            onPlay = onPlay, modifier = pageModifier,
                                            recognizeOpen = recognizeOpen, onRecognize = { recognizeOpen = true },
                                            autoBiliQuery = autoBiliQuery.ifBlank { null },
                                            onAutoBiliConsumed = { autoBiliQuery = "" },
                                            onOpenPlayer = openVideoPlayer,
                                            onBiliLogin = { biliAccountOpen = true },
                                            onBiliFavorites = { openDetail("biliFolders", 0L) },
                                            biliLoggedIn = runtime.bili.loggedIn,
                                            autoFocus = searchInputRequested && visibleRoute == route && !pageMotionActive,
                                            onAutoFocusConsumed = { searchInputRequested = false },
                                            exploreContent = { if (sonicDesign) SonicExploreRecommendations(runtime,
                                                onOpenPlaylist = { id -> runtime.openPlaylist(id); openDetail("playlist", id) }, onPlay = onPlay) })
                                    }
    }
                                }
                            }
                        }
                    }
                }
                }
                if (iosDesign) CompositionLocalProvider(
                    LocalIosTopBarBackdrop provides LocalIosPageBackdrop.current
                ) {
                    // A sibling of the recorded page: never capture controls into their own source.
                    IosFloatingPageActions(
                        canGoBack = detail.isNotEmpty() || tab != "home", onBack = closeTop,
                        onRecognize = { recognizeOpen = true },
                        onQueue = { queueFromPlayer = false; overlay = "queue" },
                        onSettings = { openDetail("settings", 0L) },
                        onAccount = { neteaseLoginOpen = true }, loggedIn = runtime.home.loggedIn,
                        expanded = tab == "home" && detail.isEmpty(), routeKey = route,
                        visible = iosScroll.actionsVisible, titleVisible = iosScroll.titleVisible,
                        modifier = Modifier.align(Alignment.TopCenter).zIndex(4f))
                }
                CompositionLocalProvider(Md3eLowSpecMode provides lowSpec) {
                    if (iosDesign && detail != "settings") {
                        val destination = when (tab) {
                            "playlists" -> IosNavigationDestination.LIBRARY
                            "local" -> IosNavigationDestination.LOCAL
                            "search" -> IosNavigationDestination.SEARCH
                            else -> IosNavigationDestination.HOME
                        }
                        val select: (IosNavigationDestination) -> Unit = { selected ->
                            navigatingBack = false; activeSharedCoverKey = null
                            detail = ""; detailId = 0L; detailStack = emptyList()
                            tab = when (selected) {
                                IosNavigationDestination.LIBRARY -> "playlists"
                                IosNavigationDestination.LOCAL -> "local"
                                IosNavigationDestination.SEARCH -> "search"
                                else -> "home"
                            }
                            miniPlayerVisible = true
                        }
                        CompositionLocalProvider(
                            dev.t1m3.qplayer.android.ui.LocalGlassPerPageSampling provides true,
                            dev.t1m3.qplayer.android.ui.LocalGlassAdaptiveColors provides detail.isNotEmpty()
                        ) {
                        IosPlayerDock(collapsed = iosScroll.homeCollapsed || !miniPlayerVisible || detail.isNotEmpty(),
                            hasTrack = runtime.playback.hasTrack, destination = destination,
                            showLocal = runtime.settings.bool("showLocalTab"), dark = dark,
                            backdrop = requireNotNull(LocalIosDockBackdrop.current), playerExpansion = playerExpansion,
                            onDestination = select, onHome = { select(IosNavigationDestination.HOME) },
                            onSearch = { select(IosNavigationDestination.SEARCH) },
                            player = { dockModifier, compact ->
                                Md3eIosMiniPlayer(runtime, {
                                    playerExpansion.prepareOpen(); queueFromPlayer = false
                                    playerOpenLyrics = false; overlay = "player"
                                }, playerExpansion, compact, dockModifier)
                            })
                        }
                    } else if (!iosDesign) Md3eMiniPlayerDock(runtime,
                        visible = miniDockShown,
                        onOpen = {
                            if (!lowSpec) playerExpansion.prepareOpen()
                            queueFromPlayer = false
                            playerOpenLyrics = false
                            overlay = "player"
                        },
                        onOpenLyrics = {
                            if (!lowSpec) playerExpansion.prepareOpen()
                            queueFromPlayer = false
                            playerOpenLyrics = true
                            overlay = "player"
                        },
                        onLogin = { neteaseLoginOpen = true },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                            .claudeTransitionBackdrop { sharedBackdrop.value },
                        playerExpansion = playerExpansion)
                }
                }
            }
            if (conversationVisible) Md3eAiConversationPage(runtime, aiConversation,
                onBack = { aiConversation.open = false }, onPlay = { playlist, bounds, layer ->
                    if (!lowSpec) playerExpansion.prepareConversationOpen(bounds, layer)
                    onPlay { playAiDailySongs(dev.t1m3.qplayer.ai.AiMusicConversation.snapshot(playlist.songs), 0) }
                    queueFromPlayer = false
                    playerOpenLyrics = false
                    overlay = "player"
                })
            }
                },
                detail = {
                    Md3ePlayerScreen(runtime, onBack = closeTop, initialLyrics = playerOpenLyrics,
                        onQueue = { queueFromPlayer = true; overlay = "queue" },
                        onOpenAlbum = runtime::openAlbum,
                        onOpenArtist = runtime::openArtist)
                },
            )
            // MusicOne's modal sheet owns its reveal/drag/dismiss animation in every design.
            if (overlay == "queue") {
                Md3eQueueScreen(runtime, onBack = closeTop, timer = sleepTimer)
            }
            Md3eVideoLayer(runtime, obscured = overlay != "player" || biliAccountOpen || neteaseLoginOpen || aiDj.shown || privateRoam.shown)
            ClaudeAiDjHost(runtime, aiDj, playerExpansion) { miniPlayerVisible = true }
            ClaudePrivateRoamHost(runtime, privateRoam,
                onStart = { onPlay { startPrivateFm() } },
                onComplete = {
                    // The radio has already expanded to full-screen. Hand off
                    // without starting another flight from the mini-player.
                    playerExpansion.prepareOpen()
                    playerExpansion.hasSnapshot = false
                    playerExpansion.refreshSnapshot = false
                    playerExpansion.progress.snapTo(1f)
                    queueFromPlayer = false
                    playerOpenLyrics = false
                    overlay = "player"
                    privateRoam.shown = false
                },
                detail = {
                    Md3ePlayerScreen(runtime, onBack = closeTop, initialLyrics = false,
                        onQueue = { queueFromPlayer = true; overlay = "queue" },
                        onOpenAlbum = runtime::openAlbum,
                        onOpenArtist = runtime::openArtist)
                })
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = if (overlay.isEmpty()) {
                    (if (detail.isEmpty()) 88.dp else 12.dp) +
                        (if (runtime.playback.hasTrack && miniPlayerVisible && detail != "settings") 68.dp else 0.dp)
                } else 12.dp))
        }
        // 设置 → AI 的「AI 对话测试」：与均衡器同一个挂载位置。
        if (runtime.aiChatProbeRequested) Md3eAiChatProbeDialog(runtime,
            onDismiss = { runtime.aiChatProbeRequested = false })
        // 听歌识曲：识别 → 候选试听 → 就是这首 / 去B站搜索。
        Md3eRecognizeDialogHost(visible = recognizeOpen,
            onDismiss = { recognizeOpen = false },
            onPlayById = { runtime.playById(it) },
            onBiliSearch = { query ->
                recognizeOpen = false
                autoBiliQuery = query
                detail = ""
                tab = "search"
            })
        if (biliAccountOpen) {
            if (runtime.bili.loggedIn) AlertDialog(
                onDismissRequest = { biliAccountOpen = false },
                title = { Text("B站账号") }, text = { Text("已登录哔哩哔哩") },
                confirmButton = { TextButton(onClick = { biliAccountOpen = false; openDetail("biliFolders", 0L) }) { Text("收藏夹") } },
                dismissButton = { TextButton(onClick = { runtime.controller.logoutBili(); biliAccountOpen = false }) { Text("退出登录") } },
            ) else Md3eBiliLoginDialog(runtime) { biliAccountOpen = false }
        }
        if (neteaseLoginOpen) {
            if (runtime.home.loggedIn) AlertDialog(
                onDismissRequest = { neteaseLoginOpen = false },
                title = { Text("网易云音乐账号") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(runtime.home.userName.ifBlank { "已登录网易云音乐" })
                        if (!sonicDesign) TextButton(onClick = { neteaseLoginOpen = false; openDetail("listening", 0L) }) {
                            Text("听歌足迹与收听时长")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { neteaseLoginOpen = false }) { Text("完成") } },
                dismissButton = { TextButton(onClick = { runtime.controller.logout(); neteaseLoginOpen = false }) { Text("退出登录") } },
            ) else Md3eNeteaseLoginDialog(runtime,
                onListening = if (sonicDesign) null else ({ neteaseLoginOpen = false; openDetail("listening", 0L) })) {
                neteaseLoginOpen = false
            }
        }
        }
    }
}
}
}

@Composable
private fun HomeContent(
    state: HomeState, onLogin: () -> Unit, onPlay: PlayAction,
    onOpenPlaylist: (Long) -> Unit, onRefresh: () -> Unit, onEnqueue: (NeteaseSong) -> Unit, modifier: Modifier = Modifier,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("QPLAYER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("推荐", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onRefresh, enabled = !state.loading) {
                    Icon(Md3eIcons.Refresh, "刷新推荐")
                }
            }
        }
        item {
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Md3eIcons.Headphones, contentDescription = null, modifier = Modifier.size(36.dp))
                    Text(if (state.loggedIn) "为你而来的音乐" else "让好音乐，陪你一会儿",
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(if (state.loggedIn) "${state.userName.ifBlank { "网易云用户" }}，今天想听什么？"
                        else "发现网易云推荐。登录后，遇见更懂你的每日歌曲。",
                        style = MaterialTheme.typography.bodyLarge)
                    if (!state.loggedIn) {
                        Button(onClick = onLogin, enabled = !state.loginBusy,
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
                            Icon(Md3eIcons.Person, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.loginBusy) "正在验证登录…" else "登录网易云音乐")
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Md3eIcons.CheckCircle, null, Modifier.size(18.dp))
                            Text("已登录网易云音乐", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        if (state.loginError.isNotBlank()) item {
            StatusCard(state.loginError, "重新登录", onLogin)
        }
        if (state.error.isNotBlank()) item {
            StatusCard(state.error, "重试", onRefresh, enabled = !state.loading)
        }
        if (state.loading) item {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Md3eLoadingIndicator(Modifier.size(36.dp))
                Text("正在寻找好音乐…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.playlists.isNotEmpty()) {
            item { SectionTitle("精选歌单", "从一张歌单开始") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.playlists, key = { it.id }) { playlist ->
                        Card(onClick = { onOpenPlaylist(playlist.id) },
                            modifier = Modifier.width(168.dp), shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Artwork(playlist.coverThumbPath ?: playlist.coverUrl.orEmpty(), Modifier.fillMaxWidth().aspectRatio(1f))
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(playlist.name.orEmpty(), style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Md3eIcons.PlayArrow, null, Modifier.size(18.dp))
                                    Text("播放歌单", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (state.daily.isNotEmpty()) {
            item { SectionTitle("每日推荐", "适合今天的你") }
            items(state.daily.withIndex().toList(), key = { "daily-${it.index}-${it.value.id}" }) { (index, song) ->
                SongRow(song, { onEnqueue(song) }) { onPlay { playRecommendation(index) } }
            }
        }
        state.sections.filter { it.id != "daily" || state.daily.isEmpty() }.forEach { section ->
            item(key = "section-${section.id}") { SectionTitle(section.title, "为你推荐") }
            items(section.songs.withIndex().toList(), key = { "${section.id}-${it.index}-${it.value.id}" }) { (_, song) ->
                SongRow(song, { onEnqueue(song) }) { onPlay { playHomeRecommendation(section.id, song.id) } }
            }
        }
        if (!state.loading && state.daily.isEmpty() && state.sections.isEmpty() && state.playlists.isEmpty()) item {
            StatusCard("暂时没有推荐内容，请检查网络后重试。", "重新加载", onRefresh)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SongRow(song: NeteaseSong, onEnqueue: () -> Unit, onPlay: () -> Unit) {
    Surface(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().songQueueMenu(song.name.orEmpty(), onPlay, onEnqueue),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Artwork(song.coverThumbPath ?: song.coverUrl.orEmpty(), Modifier.size(56.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(song.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Md3eIcons.PlayArrow, "播放 ${song.name.orEmpty()}", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun StatusCard(text: String, action: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick, enabled = enabled) { Text(action) }
        }
    }
}


