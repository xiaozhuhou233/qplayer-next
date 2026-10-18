// Adapted from onetake by Patrick (github.com/feitangyuan), lineage otk-7f3e1c.
// PolyForm Noncommercial 1.0.0; see assets/licenses/onetake-LICENSE.txt.
package dev.t1m3.qplayer.android.md3eui

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.background
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow

internal object ClaudeOneTake {
    val ExpoOut = Easing { u -> when { u <= 0f -> 0f; u >= 1f -> 1f
        else -> (1f - 2f.pow(-10f * u)) / (1f - 2f.pow(-10f)) } }
    const val PRESS = 80
    const val SNAP = 180
    const val TRAVEL = 420
    const val CARRY = 560
    const val COLLECTION = 240
    const val SONG_ENTRY = 120
    const val PLAYER_TAB = 220
    const val VINYL_MOVE = 180
    fun <T> snappy() = spring<T>(dampingRatio = .85f, stiffness = (2 * PI / .42).pow(2).toFloat())
    fun <T> bouncy() = spring<T>(dampingRatio = .7f, stiffness = (2 * PI / .5).pow(2).toFloat())
    fun <T> entrance() = spring<T>(dampingRatio = .62f, stiffness = 18f * 18f)
    fun page(forward: Boolean): ContentTransform {
        val direction = if (forward) 1 else -1
        return (slideInHorizontally(tween(420, easing = ExpoOut)) { direction * it / 8 } + fadeIn(tween(160))) togetherWith
            (slideOutHorizontally(tween(240, easing = ExpoOut)) { -direction * it / 12 } + fadeOut(tween(120)))
    }
    fun sheetIn() = slideInVertically(tween(CARRY, easing = ExpoOut)) { it / 8 } + fadeIn(tween(160))
    fun sheetOut() = slideOutVertically(tween(TRAVEL, easing = ExpoOut)) { it / 12 } + fadeOut(tween(120))
}

@Composable
internal fun claudeMotionEnabled() = !Md3eLowSpecMode.current && !LocalMd3eReducedEffects.current && ValueAnimator.areAnimatorsEnabled()

/** Apply below the shared overlay: moving covers and containers stay sharp. */
internal fun Modifier.claudeTransitionBackdrop(strength: () -> Float): Modifier = composed {
    if (!LocalClaudeDesign.current || Md3eLowSpecMode.current || !ValueAnimator.areAnimatorsEnabled()) return@composed this
    val tint = androidx.compose.material3.MaterialTheme.colorScheme.background
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    graphicsLayer {
        val radius = 14.dp.toPx() * strength().coerceIn(0f, 1f)
        // Clamp edge pixels instead of sampling transparent/black beyond the page.
        renderEffect = if (supportsBlur && radius > .01f) BlurEffect(radius, radius, TileMode.Clamp) else null
    }.drawWithContent {
        drawContent()
        val amount = strength().coerceIn(0f, 1f)
        if (amount > 0f) drawRect(tint.copy(alpha = amount * if (supportsBlur) .12f else .22f))
    }
}

/** One Take focus pulse is bounded by the shared carry, with no navigation delay. */
@Composable
internal fun ClaudeSharedBackdropFocus(active: Boolean, target: Any, focus: Animatable<Float, AnimationVector1D>,
    totalDurationMillis: Int = ClaudeOneTake.CARRY) {
    val enabled = LocalClaudeDesign.current && !Md3eLowSpecMode.current && ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(active, target, enabled, totalDurationMillis) {
        if (active && enabled) {
            // Keep the hold on Compose's animation clock too, so system duration scaling stays in sync.
            val initial = focus.value
            focus.animateTo(0f, keyframes {
                durationMillis = totalDurationMillis
                initial at 0 using ClaudeOneTake.ExpoOut
                1f at minOf(ClaudeOneTake.PRESS, totalDurationMillis / 4)
                1f at (totalDurationMillis - minOf(ClaudeOneTake.SNAP, totalDurationMillis / 3)) using ClaudeOneTake.ExpoOut
                0f at totalDurationMillis
            })
        } else focus.snapTo(0f)
    }
}

/** Per navigation visit, not persisted with the cached list/scroll position. */
internal class ClaudeEntranceVisit(val returning: Boolean = false, val collection: Boolean = false) {
    val shown = mutableSetOf<Int>()
}
internal val LocalClaudeEntranceVisit = staticCompositionLocalOf<ClaudeEntranceVisit?> { null }

/** Only playlist songs opt into visit replay; page/shared chrome keeps its saved entrance. */
internal fun Modifier.claudeEntrance(index: Int = 0, replayOnVisit: Boolean = false,
    songListState: LazyListState? = null, songKey: String? = null): Modifier = composed {
    // Automatic frame-budget reduction must not silently consume this required entry.
    if (!LocalClaudeDesign.current || Md3eLowSpecMode.current || !ValueAnimator.areAnimatorsEnabled()) return@composed this
    val navigation = LocalClaudeEntranceVisit.current
    // Returning pages (including freshly recomposed lazy items) never replay entrances.
    // Collection chrome is already carried by the shared transition, so show it immediately.
    if (navigation?.returning == true || (navigation?.collection == true && !replayOnVisit)) return@composed this
    val visit = if (replayOnVisit) navigation else null
    val page = Md3eAnimatedScope.current
    val leaving = page?.transition?.targetState == EnterExitState.PostExit
    val entry = currentCompositeKeyHash
    var shown by rememberSaveable { mutableStateOf(false) }
    val progress = remember(visit) {
        Animatable(if (if (replayOnVisit) visit?.shown?.contains(entry) == true else shown) 1f else 0f)
    }
    var scrollEntrance by remember(visit) { mutableStateOf(false) }
    val fastSongEntry = replayOnVisit && !scrollEntrance
    val distance = with(LocalDensity.current) { (if (fastSongEntry) 6.dp else 22.dp).toPx() }
    LaunchedEffect(visit, leaving) {
        if (leaving) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        if (progress.value < 1f) {
            if (songListState != null && songKey != null) {
                // Lazy prefetch must not consume a song's entrance before it reaches the viewport.
                snapshotFlow { songListState.layoutInfo.let { layout ->
                    layout.visibleItemsInfo.any { it.key == songKey &&
                        it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset }
                } }.first { it }
                scrollEntrance = songListState.isScrollInProgress || songListState.firstVisibleItemIndex > 0 ||
                    songListState.firstVisibleItemScrollOffset > 0
            }
            visit?.shown?.add(entry)
            shown = true
            if (replayOnVisit) {
                // No mask wait or stagger: ready songs enter while the cover is travelling.
                if (scrollEntrance) progress.animateTo(1f, ClaudeOneTake.entrance())
                else progress.animateTo(1f, tween(ClaudeOneTake.SONG_ENTRY, easing = ClaudeOneTake.ExpoOut))
            } else {
                delay(index.coerceIn(0, 5) * 45L)
                progress.animateTo(1f, ClaudeOneTake.entrance())
            }
        }
    }
    graphicsLayer {
        val k = if (leaving) 1f else progress.value
        alpha = if (fastSongEntry) .65f + .35f * k else (k * 1.5f).coerceIn(0f, 1f)
        translationY = (1f - k) * distance
        scaleX = if (fastSongEntry) 1f else .96f + .04f * k
        scaleY = scaleX
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.claudeClickable(enabled: Boolean = true, onClickLabel: String? = null,
    role: Role? = null, onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null,
    onClick: () -> Unit): Modifier = composed {
    if (!LocalClaudeDesign.current || !claudeMotionEnabled())
        return@composed if (onLongClick == null)
            clickable(enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)
        else combinedClickable(enabled = enabled, onClickLabel = onClickLabel, role = role,
            onLongClick = onLongClick, onLongClickLabel = onLongClickLabel, onClick = onClick)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .96f else 1f,
        if (pressed) tween(ClaudeOneTake.PRESS, easing = ClaudeOneTake.ExpoOut) else ClaudeOneTake.bouncy(), label = "contact spring")
    val contact = graphicsLayer { scaleX = scale.value; scaleY = if (pressed) scale.value * .985f else scale.value }
    if (onLongClick == null) contact.clickable(interactionSource = interaction, indication = null, enabled = enabled,
        onClickLabel = onClickLabel, role = role, onClick = onClick)
    else contact.combinedClickable(interactionSource = interaction, indication = null, enabled = enabled,
        onClickLabel = onClickLabel, role = role, onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel, onClick = onClick)
}

/** One disc/angle survives mini -> player, rather than restarting at the destination. */
@Composable
internal fun rememberClaudeVinylRotation(playing: Boolean): State<Float> {
    val angle = remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(0f) }
    val enabled = claudeMotionEnabled() && LocalMd3eRenderingActive.current
    LaunchedEffect(playing, enabled) {
        if (!enabled) return@LaunchedEffect
        // Keep angular velocity through pause; a never-started disc remains still.
        var last = 0L
        while (isActive && (playing || speed > .02f)) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(.05f)
                    speed += ((if (playing) 45f else 0f) - speed) * (1f - exp(-dt / if (playing) .22f else .3f))
                    angle.floatValue = (angle.floatValue + speed * dt) % 360f
                }
                last = now
            }
        }
    }
    return angle
}

internal val LocalClaudePlayerExpansion = staticCompositionLocalOf<Md3ePlayerExpansionState?> { null }
internal val LocalClaudeDiscRotation = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

@Composable
internal fun ClaudeMovingDisc(cover: String, modifier: Modifier = Modifier) {
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val angle = LocalClaudeDiscRotation.current
    Box(modifier.graphicsLayer { rotationZ = angle.value }, contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(Color(0xFF171715))
            repeat(12) { i -> drawCircle(Color.White.copy(alpha = .08f), r * (.44f + i * .04f), style = Stroke(.6.dp.toPx())) }
            drawCircle(accent, r * .36f)
        }
        Artwork(cover, Modifier.fillMaxSize(.29f), corner = 100.dp)
    }
}

/** Material controls inherit the same named springs, including sliders, switches and sheets. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
internal object ClaudeOneTakeScheme : androidx.compose.material3.MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = ClaudeOneTake.snappy()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = ClaudeOneTake.bouncy()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = tween(ClaudeOneTake.CARRY, easing = ClaudeOneTake.ExpoOut)
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(ClaudeOneTake.SNAP, easing = ClaudeOneTake.ExpoOut)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(ClaudeOneTake.PRESS, easing = ClaudeOneTake.ExpoOut)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(ClaudeOneTake.TRAVEL, easing = ClaudeOneTake.ExpoOut)
}

@Composable
internal fun ClaudeTransportButtons(playing: Boolean, hasTrack: Boolean, privateFm: Boolean,
    previous: () -> Unit, toggle: () -> Unit, next: () -> Unit) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        listOf(-1, 0, 1).forEach { direction ->
            val enabled = hasTrack && (direction != -1 || !privateFm)
            val label = when (direction) { -1 -> "上一首"; 1 -> "下一首"; else -> if (playing) "暂停" else "播放" }
            val icon = when (direction) { -1 -> Md3eIcons.SkipPrevious; 1 -> Md3eIcons.SkipNext
                else -> if (playing) Md3eIcons.Pause else Md3eIcons.PlayArrow }
            Box(Modifier.size(if (direction == 0) 64.dp else 52.dp)
                .claudeClickable(enabled = enabled, role = Role.Button, onClickLabel = label) {
                    // Give contact feedback one frame before MediaPlayer work starts.
                    scope.launch { withFrameNanos { }; when (direction) { -1 -> previous(); 1 -> next(); else -> toggle() } }
                }.background(if (direction == 0) scheme.primary else scheme.secondaryContainer,
                    androidx.compose.foundation.shape.CircleShape), contentAlignment = androidx.compose.ui.Alignment.Center) {
                androidx.compose.material3.Icon(icon, label, Modifier.size(if (direction == 0) 32.dp else 26.dp),
                    tint = (if (direction == 0) scheme.onPrimary else scheme.onSecondaryContainer).copy(alpha = if (enabled) 1f else .38f))
            }
        }
    }
}
