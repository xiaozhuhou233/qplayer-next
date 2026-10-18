package dev.t1m3.qplayer.android.md3eui

import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

@Stable
internal class ClaudePrivateRoamState {
    var shown by mutableStateOf(false)
    var source by mutableStateOf(Rect.Zero)
    fun open() { shown = true }
}

internal val LocalClaudePrivateRoam = staticCompositionLocalOf<ClaudePrivateRoamState?> { null }

private data class RoamRevealShape(val bounds: Rect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(bounds, CornerRadius(radius)))
}

/** The radio card itself becomes the player; no second mini-player flight. */
@Composable
internal fun ClaudePrivateRoamHost(
    runtime: Md3eRuntime,
    roam: ClaudePrivateRoamState,
    onStart: () -> Unit,
    onComplete: suspend () -> Unit,
    detail: @Composable () -> Unit,
) {
    if (!roam.shown) return
    val animate = claudeMotionEnabled()
    val reveal = remember { Animatable(0f) }
    val expand = remember { Animatable(0f) }
    var opened by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var expanding by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val currentStart by rememberUpdatedState(onStart)
    val currentComplete by rememberUpdatedState(onComplete)
    val density = LocalDensity.current
    val playerColor = MaterialTheme.colorScheme.background
    val orange = Color(0xFFE36038)
    LaunchedEffect(Unit) {
        if (animate) reveal.animateTo(1f, tween(ClaudeOneTake.CARRY, easing = ClaudeOneTake.ExpoOut))
        else reveal.snapTo(1f)
        opened = true
    }
    LaunchedEffect(opened, ready, runtime.uiVisible, closing) {
        val view = webView ?: return@LaunchedEffect
        if (!opened || !ready || !runtime.uiVisible || closing) return@LaunchedEffect
        view.evaluateJavascript("playRadio(${!animate}); document.body.classList.remove('paused');", null)
        var sampled = SystemClock.uptimeMillis()
        while (elapsed < 3_000L) {
            delay(16)
            val now = SystemClock.uptimeMillis()
            elapsed += now - sampled
            sampled = now
        }
        expanding = true
    }
    LaunchedEffect(runtime.uiVisible, webView) {
        webView?.let { view ->
            if (runtime.uiVisible) view.onResume() else {
                view.evaluateJavascript("document.body.classList.add('paused');", null)
                view.onPause()
            }
        }
    }
    LaunchedEffect(expanding) {
        if (!expanding) return@LaunchedEffect
        currentStart()
        if (animate) expand.animateTo(1f, tween(ClaudeOneTake.CARRY, easing = ClaudeOneTake.ExpoOut))
        else expand.snapTo(1f)
        currentComplete()
    }
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        webView?.evaluateJavascript("document.body.classList.add('paused');", null)
        if (animate) reveal.animateTo(0f, tween(ClaudeOneTake.TRAVEL, easing = ClaudeOneTake.ExpoOut))
        roam.shown = false
    }
    BackHandler { if (!expanding) closing = true }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }
        .semantics { paneTitle = "私人漫游" }) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val inset = with(density) { 22.dp.toPx() }
        val cardWidth = (width - inset * 2).coerceAtMost(with(density) { 460.dp.toPx() })
        val cardHeight = (height * .72f).coerceAtMost(with(density) { 610.dp.toPx() })
        val card = Rect((width - cardWidth) / 2f, (height - cardHeight) / 2f,
            (width + cardWidth) / 2f, (height + cardHeight) / 2f)
        val source = if (roam.source.width > 0f) roam.source.translate(-origin) else
            Rect(card.center.x - 24f, card.center.y - 24f, card.center.x + 24f, card.center.y + 24f)
        fun mix(a: Rect, b: Rect, p: Float) = Rect(lerp(a.left, b.left, p), lerp(a.top, b.top, p),
            lerp(a.right, b.right, p), lerp(a.bottom, b.bottom, p))
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value * (1f - expand.value) }
            .background(Color(0xFF29251F).copy(alpha = .24f)).clickable { })
        Box(Modifier.fillMaxSize().graphicsLayer {
            val frame = mix(mix(source, card, reveal.value), Rect(0f, 0f, width, height), expand.value)
            shape = RoamRevealShape(frame, 28.dp.toPx() * (1f - expand.value))
            clip = true
            shadowElevation = 18.dp.toPx() * (1f - expand.value)
        }.background(androidx.compose.ui.graphics.lerp(orange, playerColor, expand.value))) {
            if (expanding) Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = expand.value
            }) {
                CompositionLocalProvider(LocalMd3eMotionActive provides (expand.value < 1f)) { detail() }
            }
            Column(Modifier.width(with(density) { cardWidth.toDp() })
                .height(with(density) { cardHeight.toDp() }).graphicsLayer {
                    val frame = mix(mix(source, card, reveal.value), Rect(0f, 0f, width, height), expand.value)
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = frame.left; translationY = frame.top
                    scaleX = frame.width / cardWidth; scaleY = frame.height / cardHeight
                    alpha = reveal.value * (1f - expand.value)
                }.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("私人漫游",
                    fontFamily = if (LocalIosDesign.current) androidx.compose.ui.text.font.FontFamily.Default else ClaudeSerif,
                    fontSize = 38.sp, color = Color(0xFFFBF1E1))
                AndroidView(factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        settings.textZoom = 100
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                // Commit-visible can precede the inline script or
                                // be skipped while the entrance layer is clipped.
                                view.evaluateJavascript("typeof playRadio === 'function'") { result ->
                                    if (result == "true") ready = true
                                }
                            }
                        }
                        webView = this
                        val html = context.assets.open("private-roam-radio.html")
                            .bufferedReader(Charsets.UTF_8).use { it.readText() }
                        loadDataWithBaseURL("https://radio.qplayer.invalid/", html,
                            "text/html", "UTF-8", null)
                    }
                }, onRelease = { view ->
                    view.stopLoading(); view.destroy()
                    if (webView === view) webView = null
                }, modifier = Modifier.fillMaxWidth().weight(1f))
                Text("让音乐带你去下一站", fontSize = 14.sp, color = Color(0xFFFBF1E1))
            }
        }
    }
}
