package dev.t1m3.qplayer.android.md3eui

import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class AiDjMascotStage(val key: String, val title: String, val caption: String) {
    Home("home", "AI DJ", ""),
    Random("random", "让下一首，有点意外", "正在挑选不同风格的音乐 · 每组 3–6 首"),
    Searching("searching", "好音乐，正在靠近", "正在寻找并匹配可以播放的歌曲"),
    Ready("ready", "下一组，准备好了", "稍后自动播放 · 先听听这次推荐的理由"),
    Error("error", "再找一次好音乐", "暂时没有找到足够的歌曲，可以重试")
}

internal fun aiDjMascotStage(message: String, progress: String): AiDjMascotStage = when {
    message.contains("已准备好") -> AiDjMascotStage.Ready
    message.contains("暂未找到") -> AiDjMascotStage.Error
    progress.contains("搜索") || progress.contains("联网查询") -> AiDjMascotStage.Searching
    else -> AiDjMascotStage.Random
}

// Freeze only the clicked frame for the existing OneTake carry surface.
internal class AiMascotCapture {
    var view: WebView? = null
    fun capture(): Bitmap? = view?.let { view ->
        if (view.width <= 0 || view.height <= 0) return@let null
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
    }
}

// Retain the source document across the mini-player/detail handoff. The original
// engine is embedded verbatim, so WebView needs no file/network subresource load.
private object MascotDocument {
    @Volatile private var cached: String? = null
    fun load(context: android.content.Context): String = cached ?: synchronized(this) {
        cached ?: run {
            val html = context.assets.open("grok-mascot/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val engine = context.assets.open("grok-mascot/engine.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
            require(html.contains("<script src=\"engine.js\"></script>"))
            html.replace("<script src=\"engine.js\"></script>", "<script>$engine</script>").also { cached = it }
        }
    }
}

/** Local SVG character engine from the user's grok-icon-study archive. */
@Composable
internal fun Md3eAiMascot(modifier: Modifier = Modifier,
    stage: AiDjMascotStage = AiDjMascotStage.Searching,
    capture: AiMascotCapture? = null, running: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val document by produceState<String?>(null, context) {
        value = withContext(Dispatchers.IO) { MascotDocument.load(context) }
    }
    val active = LocalMd3eRenderingActive.current && running
    // Frame-budget fallback is for expensive page effects, not this tiny SVG.
    // That flag latches for the session and used to freeze every mascot forever.
    val reduced = Md3eLowSpecMode.current || !android.animation.ValueAnimator.areAnimatorsEnabled()
    val dark = androidx.compose.material3.MaterialTheme.colorScheme.background.let {
        (it.red + it.green + it.blue) < 1.5f
    }
    val latestActive by rememberUpdatedState(active)
    val latestReduced by rememberUpdatedState(reduced)
    val latestDark by rememberUpdatedState(dark)
    val latestStage by rememberUpdatedState(stage)
    var ready by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(document, webView) {
        val view = webView ?: return@LaunchedEffect
        val html = document ?: return@LaunchedEffect
        ready = false
        view.loadDataWithBaseURL("https://mascot.qplayer.invalid/", html, "text/html", "UTF-8", null)
    }
    LaunchedEffect(active, reduced, dark, stage, ready, webView) {
        // Let the local page initialize before a shared-element transition pauses it.
        if (ready) {
            if (active) webView?.onResume()
            webView?.evaluateJavascript("updateMascot($active,$reduced,$dark,'${stage.key}');", null)
            if (!active) webView?.onPause()
        }
    }
    Box(modifier) {
    AndroidView(factory = { context ->
        WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            // Keep Chromium's accelerated SVG renderer and pre-rasterize the small
            // view before Compose's OneTake clip/alpha reveals it.
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            // android_asset is accessible independently of general filesystem access.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.textZoom = 100
            settings.offscreenPreRaster = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                        Log.e("Md3eAiMascot", "${message.message()} (${message.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return true
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    Log.e("Md3eAiMascot", "Local asset failed: ${request.url}: ${error.description}")
                }
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("typeof updateMascot === 'function'") { value ->
                        if (webView !== view) return@evaluateJavascript
                        if (value == "true") {
                            // A visual-state callback is useful for screenshots, but
                            // must not gate resuming an initially clipped/hidden view.
                            ready = true
                            view.evaluateJavascript("updateMascot($latestActive,$latestReduced,$latestDark,'${latestStage.key}');", null)
                            view.postInvalidateOnAnimation()
                            view.evaluateJavascript("JSON.stringify({ready:document.documentElement.dataset.mascotReady,width:innerWidth,height:innerHeight,svg:document.getElementById('mascot').getBoundingClientRect().width})") { state ->
                                Log.i("Md3eAiMascot", "Original engine ready; view=${view.width}x${view.height}; hardware=${view.isHardwareAccelerated}; page=$state")
                            }
                        } else {
                            Log.e("Md3eAiMascot", "Original mascot engine did not initialize")
                        }
                    }
                }
            }
            webView = this
            capture?.view = this
        }
    }, onRelease = { view ->
        view.stopLoading(); view.destroy()
        if (webView === view) { ready = false; webView = null }
        if (capture?.view === view) capture.view = null
    }, modifier = Modifier.matchParentSize())
    // Decorative web content must never steal a mini-player tap or AI long press.
    Box(Modifier.matchParentSize().pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent() }
    })
    }
}
