package dev.t1m3.qplayer.android.md3eui

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val AiConversationShape = RoundedCornerShape(24.dp)
private val AiConversationEase = CubicBezierEasing(.22f, .8f, .2f, 1f)

/** Native adaptation of sonic-tonal's GeminiAiScreen; the configured provider remains unchanged. */
@Composable
internal fun Md3eAiConversationPage(runtime: Md3eRuntime, state: Md3eAiConversationState,
    onBack: () -> Unit, onPlay: (AiConversationPlaylist, Rect, GraphicsLayer) -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val ink = if (dark) Color(0xFFF1EFFC) else Color(0xFF28233D)
    val paper = if (dark) Color(0xE62B2A40) else Color.White.copy(alpha = .87f)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = key(state.conversationId) { rememberLazyListState() }
    val density = LocalDensity.current
    var composerHeight by remember { mutableStateOf(88.dp) }
    val reduced = Md3eLowSpecMode.current || LocalMd3eReducedEffects.current
    val last = state.messages.lastOrNull()
    LaunchedEffect(state.focusRevision) {
        withFrameNanos { }
        focus.requestFocus()
        keyboard?.show()
    }
    LaunchedEffect(state.conversationId, state.messages.size, last?.sentences?.size, last?.playlist, last?.loading) {
        val nearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
            it >= listState.layoutInfo.totalItemsCount - 2
        } ?: true
        if (!listState.isScrollInProgress && nearBottom && state.messages.isNotEmpty()) {
            withFrameNanos { }
            if (reduced) listState.scrollToItem(state.messages.lastIndex)
            else listState.animateScrollToItem(state.messages.lastIndex)
        }
    }
    fun send() {
        if (state.draft.isNotBlank() && !state.busy) {
            keyboard?.hide(); focusManager.clearFocus()
            state.submit()
        }
    }
    AiConversationHistoryHost(state, ink, paper) {
    CompositionLocalProvider(LocalContentColor provides ink) {
    Box(Modifier.fillMaxSize().pointerInput(focusManager, keyboard) {
        detectTapGestures(onTap = { focusManager.clearFocus(); keyboard?.hide() })
    }) {
        Md3eAiFluidBackground(dark, LocalMd3eRenderingActive.current, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(onClick = { keyboard?.hide(); onBack() },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = paper, contentColor = ink)) {
                    Icon(Md3eIcons.Back, "返回")
                }
                Text("AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Surface(color = paper.copy(alpha = .55f), shape = CircleShape, contentColor = ink) {
                    Text("音乐助手", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = {
                    keyboard?.hide(); focusManager.clearFocus(); state.historyOpen = true
                }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = paper, contentColor = ink)) {
                    Icon(Md3eIcons.History, "对话历史")
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = composerHeight + 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)) {
                if (state.messages.isEmpty()) item(key = "welcome") {
                    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(if (state.attachment == null) "想听什么？" else "一起调整这组歌单", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(if (state.attachment == null) "聊聊音乐，或为此刻选一组歌" else "发送你的要求，修改后会同步到首页",
                            style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = .7f))
                        if (state.attachment == null) state.suggestions.forEach { suggestion ->
                            SuggestionChip(onClick = { state.draft = suggestion; focus.requestFocus(); keyboard?.show() },
                                label = { Text(suggestion, color = ink) }, shape = CircleShape,
                                colors = SuggestionChipDefaults.suggestionChipColors(containerColor = paper.copy(alpha = .55f)), border = null)
                        }
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.user) Alignment.End else Alignment.Start) {
                        if (message.user) {
                            Surface(Modifier.widthIn(max = 380.dp).fillMaxWidth(.9f), shape = AiConversationShape,
                                color = if (dark) Color(0xFF48405E) else Color(0xFFDFD8F3), contentColor = ink) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(message.text, style = MaterialTheme.typography.bodyLarge)
                                    message.playlist?.let { AiConversationSentPlaylist(it) }
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Qplayer", style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.height(10.dp))
                            Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                message.sentences.forEachIndexed { index, sentence ->
                                    key(index) { AiSentence(sentence, reduced, ink) }
                                }
                                if (message.loading) Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Md3eLoadingIndicator(Modifier.size(36.dp), color = if (dark) Color(0xFFCFBCFF) else Color(0xFF7661B3))
                                    Text(message.progress, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = .65f))
                                }
                                message.playlist?.let { playlist ->
                                    AiConversationResult(playlist, paper, ink, reduced, onPlay = { bounds, layer ->
                                        keyboard?.hide(); focusManager.clearFocus()
                                        onPlay(playlist, bounds, layer)
                                    })
                                }
                                if (message === state.messages.lastOrNull() && !message.loading && message.error.isBlank()) {
                                    state.suggestions.forEach { suggestion ->
                                        SuggestionChip(onClick = {
                                            state.draft = suggestion; focus.requestFocus(); keyboard?.show()
                                        }, label = { Text(suggestion, color = ink) }, shape = CircleShape,
                                            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = paper.copy(alpha = .55f)),
                                            border = null)
                                    }
                                }
                                if (message.error.isNotBlank()) Surface(color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = AiConversationShape) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(message.error, style = MaterialTheme.typography.bodyMedium)
                                        if (message === state.messages.lastOrNull()) TextButton(onClick = state::retry, enabled = !state.busy) { Text("重试") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
                .padding(horizontal = 16.dp).padding(bottom = 14.dp)) {
                state.attachment?.let { attached ->
                    Surface(color = paper, shape = AiConversationShape, contentColor = ink) {
                        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { AiConversationAttachmentPreview(attached.snapshot, ink) }
                            IconButton(onClick = { state.attachment = null }) { Icon(Md3eIcons.Close, "移除附带歌单") }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Surface(Modifier.fillMaxWidth().shadow(12.dp, AiConversationShape, clip = false), shape = AiConversationShape,
                    color = paper, contentColor = ink) {
                    Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicTextField(state.draft, { state.draft = it }, Modifier.weight(1f).heightIn(min = 44.dp, max = 120.dp)
                            .focusRequester(focus), textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
                            cursorBrush = SolidColor(ink), maxLines = 5,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }), decorationBox = { field ->
                                Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                                    if (state.draft.isEmpty()) Text(if (state.historyLoading) "正在读取对话…" else if (state.busy) "可以继续写，完成后发送" else "说说你想听的音乐…",
                                        style = MaterialTheme.typography.bodyLarge, color = ink.copy(alpha = .5f))
                                    field()
                                }
                            })
                        FilledIconButton(onClick = { send() }, enabled = state.draft.isNotBlank() && !state.busy && !state.historyLoading,
                            modifier = Modifier.size(44.dp), colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (dark) Color(0xFFB6E4EE) else Color(0xFFB4E6F6), contentColor = Color(0xFF243F4A))) {
                            Icon(Icons.AutoMirrored.Filled.Send, "发送", Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
    }
    }
}
}

@Composable
private fun AiConversationSentPlaylist(playlist: AiConversationPlaylist) {
    val cover = playlist.songs.firstOrNull()?.let { it.coverUrl ?: it.coverThumbPath }.orEmpty()
    Box(Modifier.fillMaxWidth().height(178.dp).clip(AiConversationShape)) {
        Artwork(cover, Modifier.fillMaxSize(), corner = 0.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .86f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(playlist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${playlist.songs.size} 首 · 修改这组歌单", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .8f))
        }
    }
}

@Composable
private fun AiSentence(text: String, reduced: Boolean, ink: Color) {
    var visible by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(visible, enter = if (reduced) EnterTransition.None else
        fadeIn(tween(260, easing = AiConversationEase)) + slideInVertically(tween(300, easing = AiConversationEase)) { it / 4 }) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = ink)
    }
}

@Composable
private fun AiConversationAttachmentPreview(playlist: AiConversationPlaylist, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val cover = playlist.songs.firstOrNull()?.let { it.coverThumbPath ?: it.coverUrl }.orEmpty()
        Artwork(cover, Modifier.size(46.dp), corner = 12.dp)
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.titleSmall, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${playlist.songs.size} 首 · AI 歌单", style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = .65f))
        }
    }
}

@Composable
private fun AiConversationResult(playlist: AiConversationPlaylist, paper: Color, ink: Color, reduced: Boolean,
    onPlay: (Rect, GraphicsLayer) -> Unit) {
    var visible by remember { mutableStateOf(reduced) }
    var expanded by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val layer = rememberGraphicsLayer()
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(visible, enter = if (reduced) EnterTransition.None else
        fadeIn(tween(280)) + slideInVertically(tween(380, easing = AiConversationEase)) { it / 8 }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val cover = playlist.songs.firstOrNull()?.let { it.coverUrl ?: it.coverThumbPath }.orEmpty()
            val accent = rememberSonicCardAccent(cover)
            Surface(onClick = { if (bounds.width > 0) onPlay(bounds, layer) },
                Modifier.fillMaxWidth().heightIn(min = 260.dp).aspectRatio(1.05f)
                    .onGloballyPositioned { bounds = it.boundsInRoot() }
                    .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawContent() },
                shape = AiConversationShape, color = accent.fill, shadowElevation = 3.dp) {
                Box(Modifier.fillMaxSize()) {
                    Artwork(cover, Modifier.fillMaxSize(), corner = 0.dp)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,
                        Color.Black.copy(alpha = .14f), Color.Black.copy(alpha = .92f)))))
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("AI 播放列表 · ${playlist.songs.size} 首", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .8f))
                        Text(playlist.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                            color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(Modifier.size(40.dp), shape = CircleShape, color = accent.fill, contentColor = accent.ink) {
                                Box(contentAlignment = Alignment.Center) { Icon(Md3eIcons.PlayArrow, null) }
                            }
                            Text("开始播放", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        }
                    }
                }
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起歌曲与推荐原因" else "查看 ${playlist.songs.size} 首歌曲与推荐原因", color = ink)
            }
            AnimatedVisibility(expanded) {
                Surface(color = paper, shape = AiConversationShape, contentColor = ink) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        playlist.songs.forEachIndexed { index, song ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${index + 1}. ${song.name} · ${song.artist}", style = MaterialTheme.typography.titleSmall)
                                if (!song.recommendReason.isNullOrBlank()) Text(song.recommendReason,
                                    style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = .7f))
                            }
                        }
                    }
                }
            }
        }
    }
}
