package dev.t1m3.qplayer.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.hostActivity() else null
    else -> null
}

/** One owner per window; update on theme changes and restore after focus/resume. */
@Composable
@Suppress("DEPRECATION")
internal fun SystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.parent as? DialogWindowProvider)?.window
        ?: LocalContext.current.hostActivity()?.window ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(window, view, lifecycle, dark) {
        fun applyAppearance() {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        applyAppearance()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) applyAppearance()
        }
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) applyAppearance()
        }
        val tree = view.viewTreeObserver
        lifecycle.addObserver(observer)
        tree.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            lifecycle.removeObserver(observer)
            if (tree.isAlive) tree.removeOnWindowFocusChangeListener(focusListener)
        }
    }
}

/** The page continues behind the system icons; only a faint fading shadow overlays it. */
@Composable
internal fun StatusBarShadow(
    dark: Boolean,
    modifier: Modifier = Modifier,
    opacity: (() -> Float)? = null,
) {
    val inset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    if (inset <= 0.dp) return
    Box(modifier.fillMaxWidth().height(inset + 12.dp).drawWithCache {
        val gradient = Brush.verticalGradient(
            listOf(Color.Black, Color.Transparent)
        )
        onDrawBehind {
            // Read the player clock in drawing, not composition. No final-frame
            // dark-to-light scrim switch when the retained detail is removed.
            drawRect(gradient, alpha = opacity?.invoke() ?: if (dark) 0.16f else 0.07f)
        }
    })
}

/** Insets belong to page content, so a scrolling list can pass behind the status bar.
 *  Ⓜ In iOS design the floating top row (status bar + 44dp glass buttons + its padding)
 *  overlays every page: pages start BELOW that band — 「整个页面往下空出那一格」 — and
 *  only the page title, fading in once the page scrolls, ever occupies it. */
@Composable
internal fun pageStatusBarInset(): Dp = if (LocalIosDesign.current)
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp else 0.dp

@Composable
internal fun pageContentPadding(
    start: Dp = 0.dp,
    top: Dp = 0.dp,
    end: Dp = 0.dp,
    bottom: Dp = 0.dp,
): PaddingValues = PaddingValues(start, top + pageStatusBarInset(), end, bottom)

@Composable
internal fun pageContentPadding(all: Dp): PaddingValues =
    pageContentPadding(start = all, top = all, end = all, bottom = all)
