package dev.t1m3.qplayer.android.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop

internal val LocalPlayerGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalIosTopBarBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Record only the background, never these controls, to avoid glass feedback. */
@Composable
private fun PlayerGlass(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val backdrop = LocalPlayerGlassBackdrop.current ?: LocalIosTopBarBackdrop.current
    val highlight = rememberIosGlassHighlight()
    if (backdrop != null) {
        IosLiquidGlass(
            backdrop = backdrop,
            dark = LocalGlassDark.current,
            modifier = modifier,
            interaction = highlight,
            content = content,
        )
    } else {
        Box(modifier.clip(CircleShape).background(iosGlassSurface(LocalGlassDark.current,
            LocalGlassTint.current, LocalGlassLuminance.current))) {
            content()
        }
    }
}

@Composable
internal fun IosTopBarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.size(44.dp)
) {
    PlayerGlass(modifier) {
        PlayerAction(icon, label, onClick, Modifier.fillMaxSize(), enabled = enabled,
            tint = adaptiveGlassInk())
    }
}

@Composable
internal fun IosFloatingPageActions(
    canGoBack: Boolean,
    onBack: () -> Unit,
    onRecognize: (() -> Unit)?,
    onQueue: () -> Unit,
    onSettings: () -> Unit,
    onAccount: () -> Unit,
    loggedIn: Boolean,
    visible: Boolean = true,
    /** Ⓜ The listener: the row normally collapses to just the queue button, and expands on the
     *  home page or on a long-press of that button, animated. Home passes true. */
    expanded: Boolean = false,
    /** What resets a long-press expansion — the route, so moving on re-collapses the row. */
    routeKey: Any? = null,
    /** Whether page content has scrolled under the floating controls' scrim. */
    titleVisible: Boolean = false,
    modifier: Modifier = Modifier
) {
    var longPressed by remember(routeKey) { androidx.compose.runtime.mutableStateOf(false) }
    val showActions = expanded || longPressed
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                // Retain the full-width scrim, without a floating page title.
                val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val scrimAlpha by animateFloatAsState(
                    if (titleVisible) 1f else 0f, tween(220), label = "ios_top_scrim")
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(topInset + 64.dp)
                        .drawWithCache {
                            val gradient = Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.16f),
                                0.7f to Color.Black.copy(alpha = 0.05f),
                                1f to Color.Transparent
                            )
                            onDrawBehind { drawRect(gradient, alpha = scrimAlpha) }
                        }
                )
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (canGoBack) {
                        // Fixed leading slot restores the return control to the same
                        // center line as the right-hand capsule.
                        PlayerGlass(Modifier.size(44.dp)) {
                            PlayerAction(Icons.Default.ChevronLeft, "返回", onBack, Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    PlayerGlass(Modifier.height(44.dp)) {
                        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Ⓜ These three ride their own visibility animation, so the capsule's
                            // width shrinks to the queue button and grows back — expand and
                            // collapse are one animated reflow rather than an instant swap.
                            AnimatedVisibility(
                                visible = showActions,
                                enter = expandHorizontally() + fadeIn(),
                                exit = shrinkHorizontally() + fadeOut()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (onRecognize != null) PlayerAction(Icons.Default.GraphicEq, "听歌识曲", onRecognize)
                                    PlayerAction(Icons.Default.Settings, "设置", onSettings)
                                    PlayerAction(if (loggedIn) Icons.Default.AccountCircle else Icons.Default.Login,
                                        "账户", onAccount)
                                }
                            }
                            PlayerAction(Icons.Default.QueueMusic, "播放队列", onQueue,
                                onLongClick = { longPressed = true })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerAction(
    icon: ImageVector, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier.size(44.dp), iconSize: Dp = 23.dp,
    enabled: Boolean = true, selected: Boolean = false, onLongClick: (() -> Unit)? = null,
    tint: Color = adaptiveGlassInk()
) {
    val view = LocalView.current
    Box(modifier.clip(CircleShape)
        .then(if (LocalGlassContentColor.current == null) rememberIosGlassInteraction() else Modifier)
        .combinedClickable(
            enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onClick() },
            onLongClick = onLongClick?.let { action -> {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); action()
            } }
        ), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(iconSize), tint = tint.copy(alpha =
            if (!enabled) .3f else if (selected) 1f else .75f))
        if (selected) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
            .size(3.dp).clip(CircleShape).background(tint))
    }
}

@Composable
internal fun IosPlayerHeader(
    activeTab: Int, video: Boolean, onTab: (Int) -> Unit, onClose: () -> Unit,
    onShare: (() -> Unit)?, onQueue: (() -> Unit)?, modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerGlass(Modifier.size(44.dp)) {
            PlayerAction(Icons.Default.KeyboardArrowDown, "返回", onClose)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (!video) PlayerGlass(Modifier.height(44.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxSize().padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("播放", "歌词").forEachIndexed { index, title ->
                        IosPlayerTab(title, index == activeTab, Modifier.weight(1f)) { onTab(index) }
                    }
                }
            }
        }
        PlayerGlass(Modifier.height(44.dp)) {
            Row {
                if (onShare != null) PlayerAction(Icons.Default.Share, "分享", onShare)
                if (onQueue != null) PlayerAction(Icons.Default.QueueMusic, "播放队列", onQueue)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IosPlayerTab(title: String, selected: Boolean, modifier: Modifier, click: () -> Unit) {
    val view = LocalView.current
    val ink = adaptiveGlassInk()
    Box(modifier.fillMaxHeight().clip(CircleShape)
        .background(if (selected) ink.copy(alpha = .1f) else Color.Transparent)
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); click() }),
        contentAlignment = Alignment.Center) {
        Text(title, color = ink.copy(alpha = if (selected) 1f else .65f),
            fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
internal fun IosPlayerTransport(
    playing: Boolean, previousEnabled: Boolean, playMode: Int, liked: Boolean,
    previous: () -> Unit, toggle: () -> Unit, next: () -> Unit,
    shuffle: () -> Unit, repeat: () -> Unit, favorite: () -> Unit, favoriteLongPress: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        IosPlayerTransportButtons(playing, previousEnabled, previous, toggle, next)
        Spacer(Modifier.height(14.dp))
        PlayerGlass(Modifier.width(240.dp).height(52.dp)) {
            Row(Modifier.fillMaxSize().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerAction(Icons.Default.Shuffle, "随机", shuffle, Modifier.weight(1f).fillMaxHeight(),
                    enabled = previousEnabled, selected = playMode == 1)
                Box(Modifier.width(1.dp).height(16.dp).background(adaptiveGlassInk().copy(alpha = .12f)))
                PlayerAction(if (playMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "循环", repeat, Modifier.weight(1f).fillMaxHeight(), selected = playMode == 2)
                Box(Modifier.width(1.dp).height(16.dp).background(adaptiveGlassInk().copy(alpha = .12f)))
                PlayerAction(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    "收藏", favorite, Modifier.weight(1f).fillMaxHeight(), selected = liked, onLongClick = favoriteLongPress)
            }
        }
    }
}

/** The same iOS transport row is used over MV backgrounds in every visual mode. */
@Composable
internal fun IosPlayerTransportButtons(
    playing: Boolean, previousEnabled: Boolean,
    previous: () -> Unit, toggle: () -> Unit, next: () -> Unit,
    hasTrack: Boolean = true, tint: Color = adaptiveGlassInk()
) {
    Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        PlayerAction(Icons.Default.SkipPrevious, "上一首", previous, Modifier.size(68.dp), 42.dp,
            enabled = previousEnabled && hasTrack, tint = tint)
        PlayerAction(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            if (playing) "暂停" else "播放", toggle, Modifier.size(80.dp), 56.dp,
            enabled = hasTrack, tint = tint)
        PlayerAction(Icons.Default.SkipNext, "下一首", next, Modifier.size(68.dp), 42.dp,
            enabled = hasTrack, tint = tint)
    }
}
