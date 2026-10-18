@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package dev.t1m3.qplayer.android.md3eui

import dev.t1m3.qplayer.android.ui.IosAwareAlertDialog as AlertDialog
import dev.t1m3.qplayer.android.ui.IosAwareIconButton as IconButton
import dev.t1m3.qplayer.android.ui.IosAwareTextButton as TextButton

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.zIndex
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import dev.t1m3.qplayer.android.md3eui.claudeClickable as clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.t1m3.qplayer.lyric.LyricLine
import kotlin.math.PI
import kotlin.math.sin


private class PlayerCoverTransition(
    val shared: SharedTransitionScope,
    val animated: AnimatedVisibilityScope
)
private val LocalPlayerCoverTransition = staticCompositionLocalOf<PlayerCoverTransition?> { null }

// This scope only joins the two player tabs, independent of page navigation.
@Composable
internal fun Modifier.playerTabCover(restingAngle: Float = 0f): Modifier {
    val transition = LocalPlayerCoverTransition.current ?: return rotate(restingAngle)
    val claude = LocalClaudeDesign.current
    val otherAngle = 0f
    val angle = transition.animated.transition.animateFloat(
        transitionSpec = { tween(if (claude) ClaudeOneTake.PLAYER_TAB else 360, easing = if (claude) ClaudeOneTake.ExpoOut else FastOutSlowInEasing) },
        label = "player_cover_rotation"
    ) { visibility -> if (visibility == EnterExitState.Visible) restingAngle else otherAngle }
    return with(transition.shared) {
        this@playerTabCover.sharedElement(
            rememberSharedContentState("player-tab-cover"),
            animatedVisibilityScope = transition.animated,
            boundsTransform = { _, _ -> tween(if (claude) ClaudeOneTake.PLAYER_TAB else 360, easing = if (claude) ClaudeOneTake.ExpoOut else FastOutSlowInEasing) },
            zIndexInOverlay = 0f
        ).graphicsLayer { rotationZ = angle.value }
    }
}

// Normal zIndex cannot occlude a shared element drawn in the root overlay.
// Lift the entire disc (including its label and shadow) into that SAME overlay,
// above the cover, and restore both to their normal order when it finishes.
@Composable
private fun Modifier.playerTabForeground(): Modifier {
    val transition = LocalPlayerCoverTransition.current ?: return zIndex(2f)
    val claude = LocalClaudeDesign.current
    val opacity = transition.animated.transition.animateFloat(
        transitionSpec = {
            if (claude) tween(ClaudeOneTake.PLAYER_TAB, easing = ClaudeOneTake.ExpoOut)
            else if (targetState == EnterExitState.Visible) tween(360) else tween(220)
        }, label = "vinyl_overlay_opacity"
    ) { visibility -> if (visibility == EnterExitState.Visible) 1f else 0f }
    return with(transition.shared) {
        this@playerTabForeground.zIndex(2f).renderInSharedTransitionScopeOverlay(
            renderInOverlay = { isTransitionActive },
            zIndexInOverlay = 2f
        ).graphicsLayer {
            // The overlay escapes the page's fade layer, so carry its fade here.
            alpha = if (isTransitionActive) opacity.value else 1f
        }
    }
}
@Composable
internal fun Md3ePlayerScreen(runtime: Md3eRuntime, onBack: () -> Unit,
    initialLyrics: Boolean = false, onQueue: () -> Unit,
    onOpenAlbum: (Long) -> Unit = {}, onOpenArtist: (Long) -> Unit = {}) {
    if (runtime.experimentalAiDjWaiting) {
        val expansion = LocalClaudePlayerExpansion.current
        SideEffect { expansion?.detailShowsDisc = false }
        BackHandler(onBack = onBack)
        ExperimentalAiDjPlaceholder(runtime, onBack)
        return
    }
    val state = runtime.playback
    var tab by remember(initialLyrics) { mutableIntStateOf(if (initialLyrics) 1 else 0) }
    var detailHadMv by remember(state.songId) { mutableStateOf(false) }
    val expansion = LocalClaudePlayerExpansion.current
    LaunchedEffect(runtime.bili.playing) { if (runtime.bili.playing) tab = 0 }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val ios = LocalIosDesign.current
    val pixel = LocalSonicDesign.current
    var gestureStart by remember { mutableFloatStateOf(1f) }
    val velocity = remember { androidx.compose.ui.input.pointer.util.VelocityTracker() }
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    val mvLayout = remember { MvBackdropLayout() }
    var mvPan by remember(state.songId) { mutableFloatStateOf(0f) }
    val mvExpected = tab == 0 && mvBackdropExpected(runtime)
    val playerBackground = if (mvExpected) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    Box(Modifier.fillMaxSize()
        .onGloballyPositioned { mvLayout.rootTop = it.boundsInRoot().top }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { dragX = 0f },
                onHorizontalDrag = { change, amount -> change.consume(); dragX += amount },
                onDragEnd = {
                    if (dragX < -72f && !runtime.bili.playing) tab = 1
                    if (dragX > 72f) tab = 0
                    dragX = 0f
                },
                onDragCancel = { dragX = 0f },
            )
        }
        .pointerInput(ios, pixel, expansion) {
            detectVerticalDragGestures(
                onDragStart = {
                    dragY = 0f; velocity.resetTracking()
                    if (ios) expansion?.iosMotion?.let { motion ->
                        gestureStart = motion.value; motion.beginDrag()
                    }
                    if (pixel && expansion != null && expansion.expanded) {
                        gestureStart = expansion.fraction
                        expansion.pixelDragFraction = gestureStart
                    }
                },
                onVerticalDrag = { change, amount ->
                    change.consume(); dragY += amount
                    velocity.addPosition(change.uptimeMillis, change.position)
                    if (ios) expansion?.iosMotion?.dragTo(gestureStart - dragY / size.height.coerceAtLeast(1))
                    if (pixel && expansion != null && expansion.expanded) {
                        val distance = (expansion.miniBounds.top - expansion.hostOrigin.y).coerceAtLeast(1f)
                        expansion.pixelDragFraction = (gestureStart - dragY / distance).coerceIn(0f, 1f)
                    }
                },
                onDragEnd = {
                    val motion = if (ios) expansion?.iosMotion else null
                    if (pixel && expansion != null && expansion.expanded) {
                        val speed = velocity.calculateVelocity().y
                        val dismiss = dragY > 0f && (dragY > 5.dp.toPx() || speed > 150f || expansion.fraction < .5f)
                        val distance = (expansion.miniBounds.top - expansion.hostOrigin.y).coerceAtLeast(1f)
                        expansion.pixelReleaseVelocity = -speed / distance
                        expansion.pixelSettleRevision++
                        if (dismiss) onBack() else if (dragY < -72f) onQueue()
                    } else if (motion != null) {
                        val speed = velocity.calculateVelocity().y
                        val dismiss = dragY > 0f && (motion.value < .7f || (dragY > 32.dp.toPx() && speed > 650.dp.toPx()))
                        motion.releaseDragWithVelocity(-speed / size.height.coerceAtLeast(1))
                        motion.request(!dismiss)
                        if (dismiss) onBack() else if (dragY < -72f) onQueue()
                    } else {
                        if (dragY < -72f) onQueue()
                        if (dragY > 72f) onBack()
                    }
                    dragY = 0f
                },
                onDragCancel = {
                    if (ios) expansion?.iosMotion?.request(true)
                    if (pixel && expansion != null && expansion.expanded) {
                        expansion.pixelReleaseVelocity = 0f
                        expansion.pixelSettleRevision++
                    }
                    dragY = 0f
                },
            )
        }
        .background(playerBackground)) {
        val claude = LocalClaudeDesign.current
        val coverBackground = runtime.settings.bool("lyricCoverBackground") && state.cover.isNotBlank()
        val playerGlassBackdrop = if (ios) rememberLayerBackdrop() else null
        var mvBackground by remember(state.songId) { mutableStateOf(mvExpected) }
        // Match the legacy player: record backgrounds only; glass controls are
        // siblings, so their RenderNodes can never sample themselves.
        Box(Modifier.fillMaxSize().then(
            if (playerGlassBackdrop != null) Modifier.layerBackdrop(playerGlassBackdrop) else Modifier)
            .background(playerBackground)) {
        if (coverBackground) {
            Crossfade(tab, animationSpec = tween(if (claude) ClaudeOneTake.PLAYER_TAB else 360, easing = if (claude) ClaudeOneTake.ExpoOut else FastOutSlowInEasing), label = "player_background") { backgroundTab ->
                if (backgroundTab == 1) {
                    Md3eLyricDynamicBackdrop(runtime, state.cover, Modifier.fillMaxSize())
                } else if (!claude) {
                    Md3eSoftArtworkBackdrop(state.cover, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .76f)))
                }
            }
        }
        if (mvExpected) {
            // This stable layer stays BELOW the video throughout its first-frame fade.
            Md3eSoftArtworkBackdrop(state.cover, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)))
        }
        val mvState = Md3eMvBackdrop(runtime, mvLayout.sceneBottom,
            mvLayout.controlsTop, visible = tab == 0, modifier = Modifier.fillMaxSize(), panFraction = mvPan)
        val hasMvBackground = tab == 0 && mvState.hasVideo
        SideEffect {
            mvBackground = hasMvBackground
            if (tab == 0) detailHadMv = hasMvBackground
            expansion?.immersiveVideo = hasMvBackground
            expansion?.detailShowsDisc = tab == 0 && !runtime.bili.playing && !hasMvBackground
        }
        }
        if (tab == 0 && mvBackground) Box(Modifier.fillMaxWidth().fillMaxHeight(.5f)
            .padding(top = 96.dp).pointerInput(state.songId) {
                detectVerticalDragGestures { change, amount ->
                    change.consume()
                    mvPan = (mvPan + amount / (size.height + 96.dp.toPx()).coerceAtLeast(1f)).coerceIn(-MV_MAX_PAN_FRACTION, MV_MAX_PAN_FRACTION)
                }
            })
        val dynamicLyrics = tab == 1 && coverBackground && !runtime.settings.bool("lowSpecMode")
        val scheme = MaterialTheme.colorScheme
        val displayScheme = if (dynamicLyrics || mvBackground) scheme.copy(
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFDDDDDD),
            onBackground = Color.White,
            background = if (mvBackground) Color.Black else scheme.background,
            surface = if (mvBackground) Color.Black else scheme.surface,
            surfaceContainerHigh = Color(0xD9252830),
        ) else scheme
        MaterialTheme(colorScheme = displayScheme) {
        CompositionLocalProvider(LocalContentColor provides displayScheme.onSurface,
            dev.t1m3.qplayer.android.ui.LocalGlassAdaptiveColors provides false,
            dev.t1m3.qplayer.android.ui.LocalPlayerGlassBackdrop provides playerGlassBackdrop,
            dev.t1m3.qplayer.android.ui.LocalGlassDark provides (mvBackground || dynamicLyrics || dev.t1m3.qplayer.android.ui.LocalGlassDark.current),
            LocalPlayerMvBackdrop provides mvBackground,
            LocalPlayerMvLayout provides (if (tab == 0) mvLayout else null)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (LocalSonicDesign.current) SonicPlayerHeader(tab, onBack, { tab = 0 }, onQueue,
                onShare = { sharePlayerTrack(runtime, context) })
            else if (LocalIosDesign.current) dev.t1m3.qplayer.android.ui.IosPlayerHeader(
                activeTab = tab, video = runtime.bili.playing, onTab = { tab = it },
                onClose = onBack, onShare = null, onQueue = onQueue)
             else Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(PlayerIcons.Back, "返回")
                }
                Spacer(Modifier.weight(1f))
                if (!runtime.bili.playing) Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(3.dp)) {
                        PlayerTab("播放详情", tab == 0) { tab = 0 }
                        PlayerTab("歌词", tab == 1) { tab = 1 }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { sharePlayerTrack(runtime, context) }, modifier = Modifier.size(40.dp)) { Icon(PlayerIcons.Share, "分享") }
                IconButton(onClick = onQueue, enabled = state.queueSize > 0, modifier = Modifier.size(40.dp)) {
                    Icon(PlayerIcons.Queue, "播放队列")
                }
            }
            Spacer(Modifier.height(12.dp))
            SharedTransitionLayout(Modifier.fillMaxWidth().weight(1f)) {
                val coverScope = this
                val sharedBackdrop = remember { androidx.compose.animation.core.Animatable(0f) }
                ClaudeSharedBackdropFocus(isTransitionActive, tab, sharedBackdrop, ClaudeOneTake.PLAYER_TAB)
                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier.fillMaxSize().claudeTransitionBackdrop { sharedBackdrop.value },
                    transitionSpec = {
                        (if (claude) (fadeIn(tween(ClaudeOneTake.PLAYER_TAB, easing = ClaudeOneTake.ExpoOut)) togetherWith
                            fadeOut(tween(ClaudeOneTake.PLAYER_TAB, easing = ClaudeOneTake.ExpoOut)))
                        else (fadeIn(tween(360)) togetherWith fadeOut(tween(220)))).using(null)
                    },
                    label = "player_detail_tab",
                ) { page ->
                    CompositionLocalProvider(LocalPlayerCoverTransition provides
                        PlayerCoverTransition(coverScope, this)) {
                        if (page == 0) PlayerDetailTab(runtime, showArtwork = !(if (tab == 0) mvBackground else detailHadMv), onLyrics = { tab = 1 }, onQueue = onQueue,
                            onOpenAlbum = onOpenAlbum, onOpenArtist = onOpenArtist)
                        else PlayerLyricsTab(runtime, onDetail = { tab = 0 })
                    }
                }
            }
        }
        }
        }
    }
}

@Composable
private fun PlayerTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "player_tab_color",
    )
    Box(
        Modifier.clip(RoundedCornerShape(27.dp)).drawBehind { drawRect(color.value) }.clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sharePlayerTrack(runtime: Md3eRuntime, context: Context) {
    val state = runtime.playback
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (runtime.bili.playing && runtime.bili.bvid.isNotBlank()) {
        clipboard?.setPrimaryClip(ClipData.newPlainText("视频链接", "https://www.bilibili.com/video/${runtime.bili.bvid}"))
    } else if (state.songId == 0L) {
        Toast.makeText(context, "本地歌曲暂时无法生成分享链接", Toast.LENGTH_SHORT).show()
        return
    } else {
        clipboard?.setPrimaryClip(ClipData.newPlainText("歌曲链接", "https://music.163.com/song?id=${state.songId}"))
    }
    Toast.makeText(context, "已复制分享链接", Toast.LENGTH_SHORT).show()
}

@Composable
private fun PlayerDetailTab(runtime: Md3eRuntime, showArtwork: Boolean, onLyrics: () -> Unit, onQueue: () -> Unit,
    onOpenAlbum: (Long) -> Unit, onOpenArtist: (Long) -> Unit) {
    if (LocalSonicDesign.current) {
        SonicPlayerDetail(runtime, showArtwork, onLyrics, onQueue, onOpenAlbum, onOpenArtist)
        return
    }
    if (LocalIosDesign.current) {
        IosPlayerDetail(runtime, showArtwork, onLyrics, onQueue, onOpenAlbum, onOpenArtist)
        return
    }
    if (LocalClaudeDesign.current) {
        ClaudePlayerDetail(runtime, showArtwork, onLyrics, onOpenAlbum, onOpenArtist)
        return
    }
    val state = runtime.playback
    Column(
        Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val coverSize = minOf(maxWidth, maxHeight)
            val scale = animateFloatAsState(if (state.playing) 1f else .95f, tween(260), label = "detail_cover_scale")
            if (runtime.bili.playing && runtime.videoVisible) {
                val aspect = if (runtime.videoHeight > 0) runtime.videoWidth.toFloat() / runtime.videoHeight else 16f / 9f
                val videoWidth = minOf(maxWidth, maxHeight * aspect)
                Md3eVideoSlot(runtime, Modifier.width(videoWidth).height(videoWidth / aspect))
            } else if (showArtwork) Artwork(state.cover, Modifier.size(coverSize).graphicsLayer {
                scaleX = scale.value; scaleY = scale.value
            }.clickable {
                if (runtime.bili.playing) runtime.videoVisible = true else onLyrics()
            })
            if (state.loading) CircularProgressIndicator(Modifier.size(48.dp))
        }
        Column(
            Modifier.fillMaxWidth().height(58.dp).playerMvAnchor(scene = true),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val size = when {
                state.title.length > 48 -> 18.sp
                state.title.length > 30 -> 21.sp
                else -> 24.sp
            }
            Text(state.title, Modifier.fillMaxWidth().height(34.dp)
                .clickable(enabled = state.albumId != 0L || state.album.isNotBlank()) {
                    if (state.albumId != 0L) onOpenAlbum(state.albumId)
                    else runtime.controller.openAlbumByName(state.album)
                }, fontSize = size,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Md3ePlayingArtistLinks(state, onOpenArtist)
        }
        if (!state.hasTrack && !state.loading && runtime.bili.error.isNotBlank()) Text(runtime.bili.error,
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        AutoMixPreparationBadge(state)
        if (runtime.bili.playing) {
            if (runtime.bili.error.isNotBlank()) Text(runtime.bili.error,
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { runtime.videoVisible = !runtime.videoVisible }) {
                    Text(if (runtime.videoVisible) "显示封面" else "显示视频")
                }
                TextButton(onClick = { runtime.videoVisible = true; runtime.videoFullscreen = true }) { Text("全屏") }
                TextButton(onClick = runtime::cacheCurrentBili) { Text("缓存") }
            }
            BiliProgress(runtime)
        } else PlayerProgress(state, wavy = runtime.settings.intOf("lyricProgressStyle") == 0,
            onSeek = { runtime.seekDisplayed(state, it) }, modifier = Modifier.fillMaxWidth())
        PlayerTransport(runtime)
    }
}

@Composable
private fun ClaudePlayerDetail(runtime: Md3eRuntime, showArtwork: Boolean, onLyrics: () -> Unit,
    onOpenAlbum: (Long) -> Unit, onOpenArtist: (Long) -> Unit) {
    val state = runtime.playback
    var biliFavoriteOpen by remember { mutableStateOf(false) }
    var biliLoginOpen by remember { mutableStateOf(false) }
    if (biliFavoriteOpen) Md3eBiliFavPickerDialog(runtime) { biliFavoriteOpen = false }
    if (biliLoginOpen) Md3eBiliLoginDialog(runtime) { biliLoginOpen = false }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxHeight < 540.dp
    val gap = if (compact) 10.dp else 18.dp
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (runtime.bili.playing && runtime.videoVisible) {
                val aspect = if (runtime.videoHeight > 0) runtime.videoWidth.toFloat() / runtime.videoHeight else 16f / 9f
                val width = minOf(maxWidth, maxHeight * aspect)
                Md3eVideoSlot(runtime, Modifier.width(width).height(width / aspect))
            } else if (showArtwork) {
                ClaudeArtworkStage(state.cover,
                    Modifier.fillMaxSize().clickable { if (runtime.bili.playing) runtime.videoVisible = true else onLyrics() })
            }
            if (state.loading) CircularProgressIndicator(Modifier.size(36.dp))
        }
        Spacer(Modifier.height(gap))
        Row(Modifier.fillMaxWidth().playerMvAnchor(scene = true), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title.ifBlank { "尚未播放" }, fontFamily = ClaudeSerif,
                    fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = state.albumId != 0L || state.album.isNotBlank()) {
                        if (state.albumId != 0L) onOpenAlbum(state.albumId)
                        else runtime.controller.openAlbumByName(state.album)
                    })
                Md3ePlayingArtistLinks(state, onOpenArtist,
                    Modifier.padding(vertical = 4.dp), centered = false)
            }
            IconButton(enabled = runtime.bili.playing || state.likeable, onClick = {
                if (runtime.bili.playing) {
                    if (runtime.bili.loggedIn) biliFavoriteOpen = true else biliLoginOpen = true
                } else runtime.likeDisplayed(state)
            }) {
                Icon(if (state.liked) PlayerIcons.Favorite else PlayerIcons.FavoriteBorder,
                    "收藏歌曲", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(gap))
        AutoMixPreparationBadge(state, compact = true)
        if (runtime.bili.playing) BiliProgress(runtime) else PlayerProgress(state,
            wavy = false, onSeek = { runtime.seekDisplayed(state, it) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(gap))
        ClaudePlayerTransport(runtime)
        Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
    }
    }
}

// Adapted from MusicOne PhoneExpandedPlayer, PlayerCoverStage and ImmersivePlaybackControls.
// MIT, Copyright (c) 2026 ShuyunR; assets/licenses/musicone-LICENSE.txt.
@Composable
private fun IosPlayerDetail(runtime: Md3eRuntime, showArtwork: Boolean, onLyrics: () -> Unit,
    onQueue: () -> Unit, onOpenAlbum: (Long) -> Unit, onOpenArtist: (Long) -> Unit) {
    val state = runtime.playback
    val expansion = LocalClaudePlayerExpansion.current
    var biliFavorite by remember { mutableStateOf(false) }
    var biliLogin by remember { mutableStateOf(false) }
    if (biliFavorite) Md3eBiliFavPickerDialog(runtime) { biliFavorite = false }
    if (biliLogin) Md3eBiliLoginDialog(runtime) { biliLogin = false }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 520.dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (runtime.bili.playing && runtime.videoVisible) {
                    val aspect = if (runtime.videoHeight > 0) runtime.videoWidth.toFloat() / runtime.videoHeight else 16f / 9f
                    val width = minOf(maxWidth, maxHeight * aspect)
                    Md3eVideoSlot(runtime, Modifier.width(width).height(width / aspect))
                } else if (showArtwork) {
                    val side = minOf(maxWidth, (maxHeight - 24.dp).coerceAtLeast(1.dp), 440.dp)
                    Box(Modifier.size(side).onGloballyPositioned { expansion?.targetDiscBounds = it.boundsInRoot() }) {
                        ClaudePlayerArtwork(state.cover, Modifier.fillMaxSize().playerTabCover()
                            .graphicsLayer { alpha = if (expansion?.moving == true) 0f else 1f }
                            .clickable { if (runtime.bili.playing) runtime.videoVisible = true else onLyrics() }, corner = 12.dp)
                    }
                }
                if (state.loading) CircularProgressIndicator(Modifier.size(32.dp))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).playerMvAnchor(scene = true),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = state.albumId != 0L) { onOpenAlbum(state.albumId) })
                    Md3ePlayingArtistLinks(state, onOpenArtist, fontSize = 16.sp, centered = false)
                }
                IconButton(onClick = {
                    if (runtime.bili.playing) { if (runtime.bili.loggedIn) biliFavorite = true else biliLogin = true }
                    else runtime.likeDisplayed(state)
                }, enabled = runtime.bili.playing || state.likeable) {
                    Icon(if (state.liked) PlayerIcons.Favorite else PlayerIcons.FavoriteBorder, "收藏歌曲", Modifier.size(24.dp))
                }
            }
            AutoMixPreparationBadge(state, compact = true)
            if (runtime.bili.playing) BiliProgress(runtime) else PlayerProgress(state, false,
                onSeek = { runtime.seekDisplayed(state, it) }, modifier = Modifier.fillMaxWidth())
            dev.t1m3.qplayer.android.ui.IosPlayerTransport(
                playing = runtime.transportState.playing, previousEnabled = runtime.transportState.hasTrack && !runtime.transportState.privateFm,
                playMode = state.playMode, liked = state.liked,
                previous = runtime::previous, toggle = runtime::toggle, next = runtime::next,
                shuffle = { runtime.play { setPlayMode(if (state.playMode == 1) 0 else 1) } },
                repeat = { runtime.play { setPlayMode(if (state.playMode == 2) 0 else 2) } },
                favorite = {
                    if (runtime.bili.playing) { if (runtime.bili.loggedIn) biliFavorite = true else biliLogin = true }
                    else runtime.likeDisplayed(state)
                }, favoriteLongPress = {})
            // The original shuffle / repeat / favorite capsule is the last row.
            // Lyrics and queue remain in the player header.
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        }
    }
}

@Composable
internal fun AutoMixPreparationBadge(state: PlaybackState, compact: Boolean = false) {
    if (state.autoMixStatus.isBlank() || !state.hasTrack) return
    val ready = state.autoMixStatus.startsWith("本地融合制作成功")
    if (compact) {
        var details by remember { mutableStateOf(false) }
        val label = if (ready) {
            if (state.autoMixRemainingSeconds >= 0) "融合已就绪 · ${state.autoMixRemainingSeconds} s"
            else "融合已就绪"
        } else state.autoMixStatus.substringBefore(" · ")
        Text(label, Modifier.fillMaxWidth().clickable { details = true }.padding(vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (details) AlertDialog(onDismissRequest = { details = false },
            title = { Text("本地融合") },
            text = { Text(listOf(state.autoMixStatus, state.autoMixPlanSummary)
                .filter { it.isNotBlank() }.joinToString("\n")) },
            confirmButton = { TextButton(onClick = { details = false }) { Text("关闭") } })
        return
    }

    Surface(
        color = if (ready) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (ready) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.autoMixStatus, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (ready && state.autoMixPlanSummary.isNotBlank()) {
                Text(state.autoMixPlanSummary, style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center)
            }
            if (ready && state.autoMixRemainingSeconds >= 0) {
                Text("距融合点还剩 ${state.autoMixRemainingSeconds} s",
                    style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
    }
}

/** The same uncropped artwork is carried from the dock into the player. */
@Composable
internal fun ClaudePlayerArtwork(cover: String, modifier: Modifier = Modifier,
    corner: androidx.compose.ui.unit.Dp = 24.dp,
    elevation: androidx.compose.ui.unit.Dp = 8.dp, decorated: Boolean = true) {
    val shape = RoundedCornerShape(corner)
    val frame = if (decorated) Modifier.shadow(elevation, shape, clip = false)
        .border(.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f), shape) else Modifier
    Artwork(cover, modifier.then(frame), corner = corner,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        containerColor = Color.Transparent)
}

@Composable
private fun ClaudeArtworkStage(cover: String, modifier: Modifier) {
    val expansion = LocalClaudePlayerExpansion.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val edge = minOf(maxWidth * .88f, maxHeight * .94f, 360.dp)
        Box(Modifier.size(edge)
            .onGloballyPositioned { expansion?.targetDiscBounds = it.boundsInRoot() }) {
            ClaudePlayerArtwork(cover, Modifier.fillMaxSize().playerTabCover()
                .graphicsLayer { alpha = if (expansion?.moving == true) 0f else 1f })
        }
    }
}

@Composable
private fun ClaudePlayerTransport(runtime: Md3eRuntime) {
    val state = runtime.playback
    Row(Modifier.fillMaxWidth().playerControlShadow(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { runtime.play { setPlayMode((state.playMode + 1) % 3) } }) {
            val icon = when (state.playMode) {
                1 -> PlayerIcons.Shuffle
                2 -> PlayerIcons.RepeatOne
                else -> PlayerIcons.Repeat
            }
            Icon(icon, when (state.playMode) { 1 -> "随机播放"; 2 -> "单曲循环"; else -> "列表循环" },
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Box(Modifier.weight(1f)) {
            PlayerTransportButtons(runtime.transportState.playing, runtime.transportState.hasTrack,
                runtime.transportState.privateFm, runtime::previous, runtime::toggle, runtime::next,
                runtime::setTransportMotionActive)
        }
        if (runtime.experimentalAiDjActive) {
            IconButton(onClick = { runtime.play { stopExperimentalAiDj() } }) {
                Icon(Md3eIcons.Close, "退出 AI DJ", modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun PlayerTransport(runtime: Md3eRuntime) {
    val state = runtime.playback
    val context = LocalContext.current
    var biliFavoriteOpen by remember { mutableStateOf(false) }
    var playlistPickOpen by remember { mutableStateOf(false) }
    var biliLoginOpen by remember { mutableStateOf(false) }
    if (biliFavoriteOpen) Md3eBiliFavPickerDialog(runtime) { biliFavoriteOpen = false }
    // The old app's 添加到歌单 dialog: your playlists; picking one adds the
    // current song to it.
    if (playlistPickOpen) AlertDialog(
        onDismissRequest = { playlistPickOpen = false },
        title = { Text("添加到歌单") },
        text = {
            if (runtime.playlists.mine.isEmpty()) {
                Text("还没有自己的歌单（或未登录）", Modifier.padding(16.dp))
            } else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(runtime.playlists.mine, key = { it.id }) { playlist ->
                    Text(playlist.name ?: "未命名歌单",
                        modifier = Modifier.fillMaxWidth().clickable {
                            runtime.controller.addToPlaylist(playlist.id, runtime.playback.songId)
                            Toast.makeText(context, "已添加到：${playlist.name ?: "未命名歌单"}",
                                Toast.LENGTH_SHORT).show()
                            playlistPickOpen = false
                        }.padding(16.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { playlistPickOpen = false }) { Text("取消") } }
    )
    if (biliLoginOpen) Md3eBiliLoginDialog(runtime) { biliLoginOpen = false }
    Column(Modifier.playerControlShadow(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (runtime.experimentalAiDjActive) TextButton(onClick = {
            runtime.play { stopExperimentalAiDj() }
        }) { Text("AI DJ · 实验模式｜退出") }
        PlayerTransportButtons(
            playing = runtime.transportState.playing,
            hasTrack = runtime.transportState.hasTrack,
            privateFm = runtime.transportState.privateFm,
            onPrevious = runtime::previous,
            onToggle = runtime::toggle,
            onNext = runtime::next,
            onMotionChanged = runtime::setTransportMotionActive,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.width(264.dp).height(56.dp).clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerToggle(PlayerIcons.Shuffle, "随机", state.playMode == 1,
                MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary,
                Modifier.weight(1f)) {
                runtime.play { setPlayMode(if (state.playMode == 1) 0 else 1) }
            }
            PlayerToggleDivider()
            PlayerToggle(if (state.playMode == 2) PlayerIcons.RepeatOne else PlayerIcons.Repeat,
                "循环", state.playMode == 2, MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer, Modifier.weight(1f)) {
                runtime.play { setPlayMode(if (state.playMode == 2) 0 else 2) }
            }
            PlayerToggleDivider()
            PlayerToggle(if (state.liked) PlayerIcons.Favorite else PlayerIcons.FavoriteBorder,
                "收藏", state.liked, MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.onTertiary, Modifier.weight(1f),
                enabled = runtime.bili.playing || state.likeable,
                // The old app's long-press heart: pick which of your playlists the
                // current song is collected into.
                onLongClick = {
                    if (!runtime.bili.playing && runtime.playback.songId != 0L
                        && runtime.playlists.loggedIn) playlistPickOpen = true
                }) {
                if (runtime.bili.playing) {
                    if (runtime.bili.loggedIn) biliFavoriteOpen = true else biliLoginOpen = true
                } else runtime.likeDisplayed(state)
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun PlayerToggle(icon: ImageVector, label: String, active: Boolean,
    activeColor: Color, activeContent: Color, modifier: Modifier,
    enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val background = animateColorAsState(
        if (active) activeColor else Color.Transparent,
        label = "player_toggle_background",
    )
    Box(modifier.fillMaxHeight().clip(CircleShape).drawBehind { drawRect(background.value) }
        .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(24.dp),
            tint = if (active) activeContent
                else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlayerToggleDivider() {
    Box(Modifier.width(1.dp).height(24.dp)
        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)))
}

@Composable
private fun PlayerLyricsTab(runtime: Md3eRuntime, onDetail: () -> Unit) {
    if (LocalClaudeDesign.current) {
        ClaudeLyricsTab(runtime, onDetail)
        return
    }
    val state = runtime.playback
    val lyrics = runtime.lyricState
    val coverOnly = lyrics.coverOnly || lyrics.coverModeManual
    var offsetOpen by remember(state.songId) { mutableStateOf(false) }
    var offsetValue by remember(state.songId, lyrics.offsetMs) { mutableFloatStateOf(lyrics.offsetMs.toFloat()) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Artwork(state.cover, Modifier.size(56.dp).clickable(onClick = onDetail))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(state.title, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Md3ePlayingArtistLinks(state, runtime::openArtist, centered = false)
            }
            if (!coverOnly) IconButton(onClick = { runtime.controller.setCoverMode(true) }) {
                Icon(PlayerIcons.Album, "显示封面")
            }
            IconButton(onClick = { offsetOpen = !offsetOpen }) {
                Icon(PlayerIcons.Sync, "歌词偏移")
            }
        }
        if (offsetOpen) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("歌词偏移", fontWeight = FontWeight.Medium)
                        Text(if (offsetValue > 0f) "+${offsetValue.toInt()} ms" else "${offsetValue.toInt()} ms",
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("仅对当前歌曲生效 · 负值提前，正值延后", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = offsetValue.coerceIn(-5000f, 5000f),
                        onValueChange = { offsetValue = (it / 50f).toInt() * 50f },
                        valueRange = -5000f..5000f, steps = 199,
                        onValueChangeFinished = { runtime.controller.setLyricOffset(offsetValue.toInt()) })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("提前 5 秒", fontSize = 11.sp)
                        Text("0", fontSize = 11.sp)
                        Text("延后 5 秒", fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        offsetValue = 0f
                        runtime.controller.resetLyricOffset()
                    }, enabled = offsetValue != 0f, modifier = Modifier.align(Alignment.End)) {
                        Text("重置为 0")
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Crossfade(coverOnly, animationSpec = tween(250), label = "lyric_cover_mode") { showCover ->
                when {
                    lyrics.loading -> CircularProgressIndicator(Modifier.size(48.dp))
                    showCover -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            if (lyrics.lines.isEmpty()) {
                                PlayerCoverPlaceholder()
                            } else {
                                Artwork(state.cover,
                                    Modifier.size(minOf(maxWidth, maxHeight).coerceAtMost(360.dp))
                                        .clickable(enabled = !lyrics.coverOnly) {
                                            runtime.controller.setCoverMode(false)
                                        })
                            }
                        }
                        Text(state.title, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Md3ePlayingArtistLinks(state, runtime::openArtist)
                    }
                    else -> Md3eLyricColumn(runtime)
                }
            }
        }
        // 2026-10-10：用户要求「歌词界面删除滑动条及下面按钮」——进度条与播放键整块移除，
        // 歌词占满余下空间；播放控制回唱片页（点封面）。
    }
}

@Composable
private fun ClaudeLyricsTab(runtime: Md3eRuntime, onDetail: () -> Unit) {
    val state = runtime.playback
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(state.cover, Modifier.size(56.dp).clickable(onClick = onDetail), corner = 6.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(state.title, fontFamily = ClaudeSerif, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Md3ePlayingArtistLinks(state, runtime::openArtist, centered = false)
            }
            IconButton(onClick = onDetail) { Icon(PlayerIcons.Album, "返回唱片") }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (runtime.lyricState.loading) Md3eLoadingIndicator(Modifier.size(36.dp))
            else if (runtime.lyricState.lines.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("此刻，听音乐就好", fontFamily = ClaudeSerif, fontSize = 22.sp)
                    Text("这首歌暂时没有歌词。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
            else Md3eLyricColumn(runtime)
        }
        // 2026-10-10：用户要求「歌词界面删除滑动条及下面按钮」——进度条与播放键移除，
        // 歌词占满余下空间；播放控制回唱片页（点封面 / 返回唱片）。
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerCoverPlaceholder() {
    Md3eLoadingIndicator(Modifier.size(96.dp), contained = true)
}

@Composable
private fun PlayerLyricLines(runtime: Md3eRuntime) {
    val state = runtime.playback
    val lyricState = runtime.lyricState
    val lines = lyricState.lines
    val rows = remember(lyricState.revision, lines) { buildPlayerLyricRows(lines) }
    val listState = rememberLazyListState()
    val positionMs = state.position - lyricState.offsetMs
    val active = remember(rows, positionMs) { playerLyricIndex(rows, positionMs) }
    val fontSize = runtime.settings.intOf("lyricFontSize").coerceIn(14, 40)
    val spacing = (runtime.settings.intOf("lyricLineSpacing").coerceIn(100, 250) * 24f / 200f).dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val centerPadding = (maxHeight * .42f).coerceAtLeast(32.dp)
        LaunchedEffect(state.songId, lyricState.revision, active) {
            if (active >= 0) {
                if (kotlin.math.abs(listState.firstVisibleItemIndex - active) > 4) {
                    listState.scrollToItem(active)
                } else {
                    listState.animateScrollToItem(active)
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(vertical = centerPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.coerceAtLeast(10.dp)),
        ) {
            itemsIndexed(rows, key = { index, row -> "${row.startMs}_$index" }) { index, row ->
                when (row) {
                    is PlayerLyricRow.Line -> PlayerLyricLine(row.lyric, index == active, index, active,
                        positionMs, fontSize, runtime, Modifier.fillMaxWidth()) {
                        runtime.seekDisplayed(state, row.startMs)
                    }
                    is PlayerLyricRow.Intro -> PlayerLyricIndicator(
                        positionMs, row.startMs, row.endMs, index == active,
                        state.playing, fontSize, false)
                    is PlayerLyricRow.Instrumental -> PlayerLyricIndicator(
                        positionMs, row.startMs, row.endMs, index == active,
                        state.playing, fontSize, true) {
                        runtime.seekDisplayed(state, row.startMs)
                    }
                }
            }
            if (rows.isEmpty()) item {
                Text("暂无歌词", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private sealed interface PlayerLyricRow {
    val startMs: Long
    val endMs: Long
    data class Intro(override val endMs: Long) : PlayerLyricRow {
        override val startMs: Long = 0L
    }
    data class Line(val lyric: LyricLine, override val startMs: Long,
        override val endMs: Long) : PlayerLyricRow
    data class Instrumental(override val startMs: Long,
        override val endMs: Long) : PlayerLyricRow
}

private fun buildPlayerLyricRows(lines: List<LyricLine>): List<PlayerLyricRow> {
    if (lines.isEmpty()) return emptyList()
    val result = ArrayList<PlayerLyricRow>(lines.size + 4)
    val first = lines.first().startMs().coerceAtLeast(0L)
    if (first >= 2_000L) result += PlayerLyricRow.Intro(first)
    var previousEnd = 0L
    lines.forEachIndexed { index, line ->
        val start = line.startMs().coerceAtLeast(0L)
        if (index > 0 && start - previousEnd > 7_000L) {
            val breakStart = previousEnd + 500L
            val previousLine = result.lastOrNull() as? PlayerLyricRow.Line
            if (previousLine != null && previousLine.endMs > breakStart) {
                result[result.lastIndex] = previousLine.copy(endMs = breakStart)
            }
            result += PlayerLyricRow.Instrumental(breakStart, start)
        }
        val explicitEnd = line.endMs().takeIf { it > start }
        val displayEnd = explicitEnd ?: lines.getOrNull(index + 1)?.startMs() ?: start + 960L
        result += PlayerLyricRow.Line(line, start, displayEnd.coerceAtLeast(start + 1L))
        previousEnd = explicitEnd ?: start + 960L
    }
    return result
}

private fun playerLyricIndex(rows: List<PlayerLyricRow>, positionMs: Long): Int {
    var latest = -1
    rows.forEachIndexed { index, row ->
        if (positionMs < row.startMs) return latest
        latest = index
        if (positionMs < row.endMs) return index
    }
    return latest
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerLyricIndicator(positionMs: Long, startMs: Long, endMs: Long,
    active: Boolean, playing: Boolean, fontSize: Int, compact: Boolean,
    onClick: (() -> Unit)? = null) {
    val elapsed = (positionMs - startMs).coerceAtLeast(0L)
    val duration = (endMs - startMs).coerceAtLeast(1L)
    val beat = ((elapsed.toFloat() / duration) * 3f).toInt().coerceIn(0, 3)
    val size = (fontSize * if (compact) 1.04f else 1.6f).dp
    val colors = listOf(MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    Row(Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
        .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(size * .25f),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            if (active && playing && index <= beat) {
                Md3eLoadingIndicator(Modifier.size(size), color = colors[index])
            } else {
                Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(size * .52f).clip(CircleShape)
                        .background(if (index < beat) colors[index]
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)))
                }
            }
        }
        if (compact) Text("间奏", fontSize = (fontSize * .7f).sp,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlayerLyricLine(line: LyricLine, focused: Boolean, rowIndex: Int, activeIndex: Int,
    positionMs: Long,
    fontSize: Int, runtime: Md3eRuntime, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val backgroundVocal = line.vocalChannel == LyricLine.VocalChannel.BACKGROUND ||
        line.vocalChannel == LyricLine.VocalChannel.BACKGROUND_LEFT ||
        line.vocalChannel == LyricLine.VocalChannel.BACKGROUND_RIGHT
    val rightAligned = line.vocalChannel == LyricLine.VocalChannel.DUET_RIGHT ||
        line.vocalChannel == LyricLine.VocalChannel.BACKGROUND_RIGHT
    val align = if (rightAligned) TextAlign.Right else TextAlign.Left
    val springEnabled = runtime.settings.bool("lyricSpring")
    val lift = remember(line) { Animatable(0f) }
    LaunchedEffect(activeIndex, springEnabled) {
        if (activeIndex < 0) return@LaunchedEffect
        val distance = kotlin.math.abs(rowIndex - activeIndex)
        if (distance > 4) {
            lift.snapTo(0f)
            return@LaunchedEffect
        }
        lift.snapTo(if (rowIndex >= activeIndex) 16f else -16f)
        kotlinx.coroutines.delay((distance * 50L).coerceAtMost(200L))
        lift.animateTo(0f,
            if (springEnabled) spring(dampingRatio = .82f, stiffness = 100f) else tween(320))
    }
    val scale by animateFloatAsState(
        if (focused && runtime.settings.bool("lyricScale")) 1.12f else .98f,
        if (springEnabled) spring(dampingRatio = .8f, stiffness = 100f) else tween(320),
        label = "lyric_line_scale",
    )
    val alpha by animateFloatAsState(if (focused) .85f else .175f,
        tween(120), label = "lyric_line_opacity")
    val foreground = if (runtime.settings.bool("lyricMd3Color"))
        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val text = remember(line, positionMs, foreground, focused,
        runtime.settings.bool("lyricLinearAnim"), runtime.settings.bool("lyricGlow")) {
        buildAnnotatedString {
            val singleLineTiming = line.syllables.size == 1
            for (syllable in line.syllables) {
                val content = syllable.text
                val timePerChar = if (content.isNotEmpty()) syllable.durationMs / content.length else 0L
                content.forEachIndexed { charIndex, char ->
                    val lit = focused && (!singleLineTiming || runtime.settings.bool("lyricLinearAnim")) &&
                        positionMs >= syllable.startMs + timePerChar * charIndex
                    withStyle(SpanStyle(
                        color = if (lit) foreground else foreground.copy(alpha = if (focused) .58f else 1f),
                        shadow = if (lit && runtime.settings.bool("lyricGlow"))
                            Shadow(foreground.copy(alpha = .55f), Offset.Zero, 12f) else null,
                    )) { append(char) }
                }
            }
        }
    }
    Column(modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        .graphicsLayer {
            scaleX = scale; scaleY = scale; this.alpha = alpha
            translationY = lift.value
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (rightAligned) 1f else 0f, .5f)
        }.clickable(onClick = onClick),
        horizontalAlignment = if (rightAligned) Alignment.End else Alignment.Start) {
        Text(text, Modifier.fillMaxWidth(), fontSize = (if (backgroundVocal) fontSize * .82f else fontSize.toFloat()).sp,
            lineHeight = (fontSize * 1.22f).sp, fontWeight = FontWeight.Bold, textAlign = align,
            style = TextStyle(shadow = if (runtime.settings.bool("lyricShadow"))
                Shadow(Color.Black.copy(alpha = .42f), Offset(0f, 2f), 4f) else null))
        line.translation?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.fillMaxWidth(), fontSize = (fontSize * .52f).sp,
                color = foreground, textAlign = align)
        }
        line.romaji?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.fillMaxWidth(), fontSize = (fontSize * .46f).sp,
                color = foreground, textAlign = align)
        }
    }
}

@Composable
internal fun PlayerProgress(state: PlaybackState, wavy: Boolean,
    onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember(state.songId) { mutableStateOf(false) }
    var fraction by remember(state.songId) { mutableFloatStateOf(0f) }
    val duration = state.duration.coerceAtLeast(1L)
    val rendering = LocalMd3eRenderingActive.current && !LocalMd3eMotionActive.current
    val animated = remember(state.songId) { Animatable(progress(state.position, duration)) }
    LaunchedEffect(state.clock, state.songId, state.playing, duration, state.seekRevision, rendering) {
        if (rendering) snapshotFlow { progress(state.position, duration) }.collectLatest { target ->
            if (state.playing) animated.animateTo(target, tween(100)) else animated.snapTo(target)
        }
    }
    val video = LocalPlayerMvBackdrop.current
    val ios = LocalIosDesign.current
    val primary = if (video) Color.White else if (ios) LocalContentColor.current.copy(alpha = .72f) else MaterialTheme.colorScheme.primary
    val track = if (video) Color.White.copy(alpha = .28f) else MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier.widthIn(max = 600.dp).playerControlShadow().playerMvAnchor()) {
        Box(Modifier.fillMaxWidth().height(32.dp)
            .pointerInput(duration) {
                detectTapGestures { point -> onSeek((duration * (point.x / size.width).coerceIn(0f, 1f)).toLong()) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { point -> dragging = true; fraction = (point.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ -> change.consume(); fraction = (change.position.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { onSeek((duration * fraction).toLong()); dragging = false },
                    onDragCancel = { dragging = false },
                )
            }.drawWithCache {
                val center = size.height / 2f
                val amplitude = if (dragging) 4.dp.toPx() else 2.5.dp.toPx()
                val stroke = Stroke(width = if (dragging) 5.dp.toPx() else 3.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
                val gap = 10.dp.toPx()
                val height = if (ios) 5.dp.toPx() else if (dragging) 14.dp.toPx() else 10.dp.toPx()
                // Geometry changes only with size or drag mode, never with time.
                val wave = Path()
                if (wavy && size.width > 0f) {
                    val step = 2.dp.toPx().coerceAtLeast(1f)
                    wave.moveTo(0f, center)
                    var x = step
                    while (x < size.width) {
                        wave.lineTo(x, center + sin(x / size.width * (4f * PI).toFloat()) * amplitude)
                        x += step
                    }
                    wave.lineTo(size.width, center)
                }
                onDrawBehind {
                    val shown = if (dragging) fraction else animated.value
                    val thumbX = (size.width * shown).coerceIn(gap, (size.width - gap).coerceAtLeast(gap))
                    if (ios) {
                        drawRoundRect(track, topLeft = Offset(0f, center - height / 2f), size = Size(size.width, height), cornerRadius = CornerRadius(height / 2f))
                        drawRoundRect(primary, topLeft = Offset(0f, center - height / 2f), size = Size(size.width * shown, height), cornerRadius = CornerRadius(height / 2f))
                    } else if (wavy) {
                        clipRect(right = (thumbX - gap).coerceAtLeast(0f)) { drawPath(wave, primary, style = stroke) }
                        clipRect(left = (thumbX + gap).coerceAtMost(size.width)) { drawPath(wave, track, style = stroke) }
                    } else {
                        drawRoundRect(primary, topLeft = Offset(0f, center - height / 2f),
                            size = Size((thumbX - gap).coerceAtLeast(0f), height), cornerRadius = CornerRadius(height / 2f))
                        drawRoundRect(track, topLeft = Offset((thumbX + gap).coerceAtMost(size.width), center - height / 2f),
                            size = Size((size.width - thumbX - gap).coerceAtLeast(0f), height), cornerRadius = CornerRadius(height / 2f))
                    }
                    if (!ios && !state.loading) drawRoundRect(primary,
                        topLeft = Offset(thumbX - 2.dp.toPx(), center - if (dragging) 14.dp.toPx() else 11.dp.toPx()),
                        size = Size(4.dp.toPx(), if (dragging) 28.dp.toPx() else 22.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
                }
            })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PlayerElapsedTime(state, if (dragging) (duration * fraction).toLong() else null)
            Text(playerTime(state.duration), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlayerElapsedTime(state: PlaybackState, draggedPosition: Long?) {
    val seconds by remember(state.clock, draggedPosition) {
        derivedStateOf { (draggedPosition ?: state.position) / 1000L }
    }
    Text(playerTime(seconds * 1000L), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun progress(position: Long, duration: Long): Float =
    if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

private fun playerTime(ms: Long): String =
    "${ms.coerceAtLeast(0) / 60000}:${((ms.coerceAtLeast(0) / 1000) % 60).toString().padStart(2, '0')}"

internal object PlayerIcons {
    val Back = icon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8l8,8l1.41,-1.41L7.83,13H20z")
    val Queue = icon("Queue", "M4,6h16v2H4zM4,11h16v2H4zM4,16h10v2H4zM17,15l5,3l-5,3z")
    val Share = icon("Share", "M18,16.08c-.76,0 -1.44,.3 -1.96,.77L8.91,12.7a3.25,3.25 0,0 0,0 -1.4l7.05,-4.11A3,3 0,1 0,15,5c0,.24 .03,.47 .09,.7L8.04,9.81a3,3 0,1 0,0 4.38l7.12,4.16c-.05,.2 -.08,.42 -.08,.65a2.92,2.92 0,1 0,2.92 -2.92z")
    val Album = icon("Album", "M12,2a10,10 0,1 0,0 20a10,10 0,0 0,0 -20zM12,14a2,2 0,1 1,0 -4a2,2 0,0 1,0 4z")
    val Sync = icon("Sync", "M12,4V1L8,5l4,4V6a6,6 0,0 1,5.65 4H19a8,8 0,0 0,-7 -6zM6.35,14H5a8,8 0,0 0,7 6v3l4,-4l-4,-4v3a6,6 0,0 1,-5.65 -4z")
    val Remove = icon("Remove", "M19,13H5v-2h14z")
    val DragHandle = icon("DragHandle", "M4,9h16v2H4zM4,13h16v2H4z")
    val Favorite = icon("Favorite", "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5A5.5,5.5 0,0 1,7.5 3c1.74,0 3.41,.81 4.5,2.09A5.48,5.48 0,0 1,16.5 3A5.5,5.5 0,0 1,22 8.5c0,3.78 -3.4,6.86 -8.55,11.54z")
    val FavoriteBorder = icon("FavoriteBorder", "M16.5,3A5.48,5.48 0,0 0,12 5.09A5.48,5.48 0,0 0,7.5 3A5.5,5.5 0,0 0,2 8.5c0,3.78 3.4,6.86 8.55,11.54L12,21.35l1.45,-1.32C18.6,15.36 22,12.28 22,8.5A5.5,5.5 0,0 0,16.5 3zM12.1,18.55L12,18.65l-0.1,-0.1C7.14,14.24 4,11.39 4,8.5A3.5,3.5 0,0 1,7.5 5c1.35,0 2.66,.87 3.12,2.06h2.77A3.35,3.35 0,0 1,16.5 5A3.5,3.5 0,0 1,20 8.5c0,2.89 -3.14,5.74 -7.9,10.05z")
    val Repeat = icon("Repeat", "M7,7h10V4l4,4l-4,4V9H7a2,2 0,0 0,-2 2v1H3v-1a4,4 0,0 1,4 -4zM17,17H7v3l-4,-4l4,-4v3h10a2,2 0,0 0,2 -2v-1h2v1a4,4 0,0 1,-4 4z")
    val RepeatOne = icon("RepeatOne", "M7,7h10V4l4,4l-4,4V9H7a2,2 0,0 0,-2 2v1H3v-1a4,4 0,0 1,4 -4zM17,17H7v3l-4,-4l4,-4v3h10a2,2 0,0 0,2 -2v-1h2v1a4,4 0,0 1,-4 4zM11,10h2v4h-2z")
    val Shuffle = icon("Shuffle", "M10.6,9.17L5.41,4L4,5.41l5.17,5.17zM14.5,4l2.04,2.04L4,18.59L5.41,20L17.96,7.46L20,9.5V4zM14.83,13.42l-1.41,1.41l3.12,3.13L14.5,20H20v-5.5l-2.04,2.04z")

    private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
