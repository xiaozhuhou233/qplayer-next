package dev.t1m3.qplayer.android.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import dev.t1m3.qplayer.android.ui.motion.EmphasizeEasing
import kotlin.math.roundToInt

/** Scroll only invalidates player measurement and chrome layers, not page composition. */
@Composable
internal fun IosPlayerDock(
    collapsed: Boolean,
    hasTrack: Boolean,
    destination: IosNavigationDestination,
    showLocal: Boolean,
    dark: Boolean,
    backdrop: Backdrop,
    playerExpansion: PlayerExpansionState,
    onDestination: (IosNavigationDestination) -> Unit,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    player: @Composable (Modifier, () -> Float) -> Unit,
) {
    val view = LocalView.current
    val collapse = remember { Animatable(0f) }
    val landing = remember { Animatable(0f) }
    val progress = remember(collapse) { { collapse.value } }
    val holdDock = playerExpansion.active
    // Ⓜ Collapse is motion for the glass inside this dock: while the bar and the
    // miniplayer reshape, the glass sampling pauses (LocalGlassMotionActive), so no
    // GPU readback or effect rebuild lands in the middle of the animation.
    var collapsing by remember { mutableStateOf(false) }
    LaunchedEffect(collapsed, hasTrack, holdDock) {
        if (holdDock) return@LaunchedEffect
        collapsing = true
        try {
            landing.snapTo(0f)
            collapse.animateTo(if (collapsed && hasTrack) 1f else 0f,
                tween(420, easing = EmphasizeEasing))
            if (collapsed && hasTrack) landing.animateTo(0f,
                spring(dampingRatio = 0.65f, stiffness = 380f), initialVelocity = 35f)
        } finally {
            collapsing = false
        }
    }
    // Threshold state changes once, instead of recomposing glass and all children
    // sixty times a second alongside a moving LazyColumn.
    val navVisible by remember { derivedStateOf { collapse.value < 0.999f } }
    val homeVisible by remember { derivedStateOf { collapse.value > 0.001f } }
    val expandedControls by remember { derivedStateOf { collapse.value < 0.01f } }
    // Keep one stable full-width bottom scrim behind the chrome. It must not be
    // scaled with the collapsing controls: scaling this separate layer made its
    // edge move away from the actual glass plates and looked like a detached
    // block during scrolling and route transitions.
    CompositionLocalProvider(LocalGlassMotionActive provides (LocalGlassMotionActive.current || collapsing)) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(if (hasTrack) 132.dp else 80.dp)
                .drawWithCache {
                    val shadow = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.54f to Color.Black.copy(alpha = if (dark) 0.018f else 0.025f),
                        1f to Color.Black.copy(alpha = if (dark) 0.17f else 0.14f)
                    )
                    onDrawBehind { drawRect(shadow) }
                }
        )
        // Reference geometry: 48dp player / 8dp gap / 64dp navigation. The
        // horizontal and vertical insets live here, outside the full-screen
        // shadow, so they cannot shift the collapse coordinate system.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
        ) {
        // The inset belongs to the outer box. This inner box is the only
        // coordinate system used by the MiniPlayer and the bottom tabs.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(if (hasTrack) 120.dp else 64.dp)
        ) {
        // Each component samples its own bounds, as in the adaptive APK demo.
        val navWidth = (maxWidth - 72.dp).coerceAtLeast(64.dp)
        if (navVisible) IosDesignNavigation(
            destination, showLocal, dark, backdrop,
            onDestination = { if (collapse.value < 0.1f) onDestination(it) },
            modifier = Modifier.align(Alignment.BottomStart).playerChromeExit(playerExpansion)
                .requiredWidth(navWidth)
                .then(if (!expandedControls) Modifier.clearAndSetSemantics {} else Modifier)
                .graphicsLayer {
                    // Ⓜ `clip = false`: this layer is the collapse animation's, and its default
                    // would cut the glass's own shadow off at the node's rectangle — which is
                    // exactly the square shadow the listener saw while the bar was shrinking.
                    clip = false
                    val p = collapse.value
                    val button = (64f - 20f * p).dp.toPx()
                    transformOrigin = TransformOrigin(0f, 1f)
                    scaleX = 1f + (button / navWidth.toPx() - 1f) * p
                    scaleY = 1f - p * (20f / 64f)
                    alpha = 1f - p
                }, includeSearch = false,
        )
        if (homeVisible) Box(Modifier.align(Alignment.BottomStart).playerChromeExit(playerExpansion)
            .graphicsLayer {
                alpha = collapse.value
                // Match the compact MiniPlayer baseline. The player is lifted
                // by 6dp to clear the navigation inset, so both side controls
                // must travel with the same visual row.
                translationY = -6.dp.toPx() * collapse.value
            }) {
            IosLiquidSearchButton(backdrop, dark, true, {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onHome()
            }, icon = Icons.Default.Home, contentDescription = "展开主页导航",
                diameter = 64.dp, compactProgress = progress)
        }
        Box(Modifier.align(Alignment.BottomEnd).playerChromeExit(playerExpansion)
            .graphicsLayer {
                val p = collapse.value
                translationX = -4.dp.toPx() * p
                translationY = -6.dp.toPx() * p
            }) {
            IosLiquidSearchButton(backdrop, dark, destination == IosNavigationDestination.SEARCH, {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onSearch()
            }, diameter = 64.dp, compactProgress = progress)
        }
        if (hasTrack) {
            // Fixed-size parent avoids relaying every frame into Scaffold/page
            // measurement. Only this player's small subtree changes width.
            Layout(modifier = Modifier.fillMaxSize(), content = {
                player(Modifier.graphicsLayer {
                    translationY = landing.value.dp.toPx()
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    scaleY = 1f - landing.value * 0.005f
                }, progress)
            }) { measurables, constraints ->
                val p = collapse.value.coerceIn(0f, 1f)
                // At 393dp wide, the compact dock matches the reference: 44dp
                // circles, a 233dp capsule, and 16dp / 12dp side gaps.
                val expandedWidth = constraints.maxWidth.toFloat()
                val buttonSlot = 44.dp.toPx()
                val leftGap = 16.dp.toPx()
                val rightGap = 12.dp.toPx()
                val searchInset = 4.dp.toPx()
                val collapsedVisualWidth =
                    (constraints.maxWidth.toFloat() - 2f * buttonSlot -
                        leftGap - rightGap - searchInset).coerceAtLeast(1f)
                val collapsedWidth = collapsedVisualWidth.coerceAtMost(expandedWidth)
                val width = (expandedWidth * (1f - p) + collapsedWidth * p).roundToInt()
                    .coerceAtLeast(1)
                val expandedHeight = 48.dp.toPx()
                val collapsedHeight = 44.dp.toPx()
                val playerHeight = expandedHeight * (1f - p) + collapsedHeight * p
                val height = playerHeight.roundToInt()              // 48dp → 44dp
                val child = measurables.single().measure(Constraints.fixed(width, height))
                val expandedCenter = 24.dp.toPx()
                // All compact controls share this bottom edge inside the dock.
                // The outer box already owns the navigation inset and 12dp gap;
                // moving below this edge cancels that gap and escapes its bounds.
                // Keep the collapsed row a little higher inside the navigation
                // safe area while preserving the expanded dock geometry.
                val collapsedCenter = constraints.maxHeight.toFloat() -
                    collapsedHeight / 2f - 6.dp.toPx()
                val center = expandedCenter * (1f - p) + collapsedCenter * p
                val y = (center - playerHeight / 2f).roundToInt()
                val x = ((constraints.maxWidth - width) * 0.5f).roundToInt()
                layout(constraints.maxWidth, constraints.maxHeight) {
                    child.placeRelative(x, y)
                }
            }
        }
        }
        }
    }
    }
}
