package dev.t1m3.qplayer.android.md3eui

import androidx.compose.runtime.*
import dev.t1m3.qplayer.ai.AiConversationHistory
import dev.t1m3.qplayer.ai.AiClient
import dev.t1m3.qplayer.ai.AiEndpoint
import dev.t1m3.qplayer.ai.AiMusicConversation
import dev.t1m3.qplayer.ai.AiReference
import dev.t1m3.qplayer.ai.DailyVibeCuration
import dev.t1m3.qplayer.netease.dto.NeteaseSong
import kotlinx.coroutines.*
import java.io.IOException
import java.util.Random
import java.util.UUID

internal data class AiConversationPlaylist(val name: String, val summary: String, val songs: List<NeteaseSong>) {
    companion object {
        fun from(playlist: DailyVibeCuration.Playlist) = AiConversationPlaylist(
            playlist.name, playlist.summary, AiMusicConversation.snapshot(playlist.songs))
    }
}

internal class AiConversationMessage(val id: Long, val user: Boolean, val text: String = "",
    playlist: AiConversationPlaylist? = null) {
    var sentences by mutableStateOf(if (user) listOf(text) else emptyList<String>())
    var playlist by mutableStateOf(playlist)
    var loading by mutableStateOf(!user)
    var progress by mutableStateOf("正在选曲")
    var error by mutableStateOf("")
}

internal data class AiDailyAttachment(val day: String, val original: DailyVibeCuration.Playlist,
    val snapshot: AiConversationPlaylist = AiConversationPlaylist.from(original))

/** Root-owned conversation: closing a page never cancels a DJ or an in-flight revision. */
@Stable
internal class Md3eAiConversationState(private val runtime: Md3eRuntime, private val scope: CoroutineScope) {
    var open by mutableStateOf(false)
    var focusRevision by mutableIntStateOf(0)
    var draft by mutableStateOf("")
    var attachment by mutableStateOf<AiDailyAttachment?>(null)
    var busy by mutableStateOf(false)
        private set
    val messages = mutableStateListOf<AiConversationMessage>()
    var historyLoading by mutableStateOf(true)
        private set
    var historyError by mutableStateOf("")
        private set
    var historyOpen by mutableStateOf(false)
    var conversationId by mutableStateOf(UUID.randomUUID().toString())
        private set
    var suggestions by mutableStateOf(emptyList<String>())
        private set
    val sessions = mutableStateListOf<AiConversationHistory.Session>()
    private var owner = runtime.controller.aiRecommendationAccountId()
    // Disk writes outlive the page/root composition and remain ordered with subsequent reads.
    private val storageScope = CoroutineScope(SupervisorJob() + runtime.aiHistoryDispatcher)
    private var generationJob: Job? = null
    private var historyReady = false
    private var nextId = 0L
    private var retryText = ""
    private var retryAttachment: AiDailyAttachment? = null

    init { loadHistory() }

    fun retryHistoryLoad() { if (!historyLoading && !historyReady) loadHistory() }

    private fun loadHistory() {
        val account = owner
        historyLoading = true
        scope.launch {
            try {
                val archive = withContext(runtime.aiHistoryDispatcher) { runtime.aiConversationHistory.load(account) }
                if (owner != account) return@launch
                sessions.clear()
                sessions.addAll(archive.sessions.sortedByDescending { it.updatedAt })
                suggestions = sessions.firstOrNull()?.suggestions.orEmpty()
                historyReady = true
                historyError = ""
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (owner == account) historyError = "对话历史读取失败，原记录已保留"
            } finally { if (owner == account) historyLoading = false }
        }
    }

    private fun ensureAccount() {
        val account = runtime.controller.aiRecommendationAccountId()
        if (account == owner) return
        checkpoint()
        generationJob?.cancel()
        owner = account
        historyReady = false
        historyError = ""
        busy = false
        sessions.clear()
        clearConversation()
        loadHistory()
    }

    private fun clearConversation() {
        conversationId = UUID.randomUUID().toString()
        messages.clear()
        draft = ""
        attachment = null
        retryText = ""
        retryAttachment = null
        suggestions = sessions.firstOrNull()?.suggestions.orEmpty()
    }

    fun newConversation() {
        if (busy || historyLoading) return
        checkpoint()
        clearConversation()
        focusRevision++
    }

    fun restore(session: AiConversationHistory.Session) {
        if (busy || historyLoading || session.id == conversationId) return
        checkpoint()
        conversationId = session.id
        messages.clear()
        messages.addAll(session.messages.map { saved ->
            AiConversationMessage(saved.id, saved.user, saved.text, saved.playlist?.toUi()).apply {
                sentences = saved.sentences.toList()
                loading = false
                error = if (saved.interrupted) "上次生成已中断，可点击重试" else saved.error
            }
        })
        nextId = maxOf(nextId, messages.maxOfOrNull { it.id } ?: 0L)
        draft = session.draft
        suggestions = session.suggestions.toList()
        attachment = null
        // Rebind only the exact same daily playlist. Never overwrite a newer day's/rebuilt group.
        session.attachment?.let { saved ->
            if (runtime.dailyVibes.state.day == session.attachmentDay) {
                runtime.dailyVibes.state.playlists.firstOrNull { current ->
                    current.name == saved.name && current.songs.map { it.id } == saved.songs.map { it.id }
                }?.let { attachment = AiDailyAttachment(session.attachmentDay, it) }
            }
        }
        retryText = messages.lastOrNull { it.user }?.text.orEmpty()
        retryAttachment = attachment
    }

    fun deleteHistory(id: String) {
        if (busy || historyLoading || !historyReady) return
        sessions.removeAll { it.id == id }
        if (conversationId == id) clearConversation()
        saveArchive()
    }

    fun checkpoint() {
        if (!historyReady || owner != runtime.controller.aiRecommendationAccountId() || messages.isEmpty()) return
        val snapshot = AiConversationHistory.Session().apply {
            id = conversationId
            title = this@Md3eAiConversationState.messages.firstOrNull { it.user }?.text.orEmpty().take(40)
            updatedAt = System.currentTimeMillis()
            draft = this@Md3eAiConversationState.draft
            suggestions = this@Md3eAiConversationState.suggestions.toList()
            attachmentDay = this@Md3eAiConversationState.attachment?.day.orEmpty()
            this.attachment = this@Md3eAiConversationState.attachment?.snapshot?.toSaved()
            this.messages = this@Md3eAiConversationState.messages.map { current ->
                AiConversationHistory.Message().apply {
                    id = current.id; user = current.user; text = current.text
                    sentences = current.sentences.toList()
                    playlist = current.playlist?.toSaved()
                    interrupted = current.loading
                    error = current.error
                }
            }
        }
        sessions.removeAll { it.id == snapshot.id }
        sessions.add(0, snapshot)
        saveArchive()
    }

    private fun saveArchive() {
        val account = owner
        val archive = AiConversationHistory.Archive().apply { sessions = this@Md3eAiConversationState.sessions.toList() }
        storageScope.launch {
            try {
                runtime.aiConversationHistory.save(account, AiConversationHistory.encode(archive))
                withContext(Dispatchers.Main) { if (owner == account) historyError = "" }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { if (owner == account) historyError = "历史暂未保存，请检查存储空间" }
            }
        }
    }

    fun show(playlist: DailyVibeCuration.Playlist? = null) {
        ensureAccount()
        if (playlist != null) attachment = AiDailyAttachment(runtime.dailyVibes.state.day, playlist)
        open = true
        focusRevision++
    }

    fun submit() {
        val text = draft.trim()
        ensureAccount()
        if (text.isBlank() || busy || historyLoading || !historyReady) return
        val attached = attachment
        messages += AiConversationMessage(++nextId, true, text, attached?.snapshot)
        draft = ""
        retryText = text; retryAttachment = attached
        run(text, attached)
    }

    fun retry() {
        if (busy || historyLoading || retryText.isBlank()) return
        messages.lastOrNull()?.takeIf { !it.user && it.error.isNotBlank() }?.let { messages.remove(it) }
        run(retryText, retryAttachment)
    }

    private fun run(text: String, attached: AiDailyAttachment?) {
        val reply = AiConversationMessage(++nextId, false)
        messages += reply
        busy = true
        suggestions = emptyList()
        checkpoint()
        val account = runtime.controller.aiRecommendationAccountId()
        val base = runtime.settings.str("aiBaseUrl")
        val key = runtime.settings.str("aiApiKey")
        val model = runtime.settings.str("aiModel")
        val timeout = runtime.settings.intOf("aiTimeoutMs").takeIf { it > 0 } ?: 60_000
        val history = runtime.controller.recentSongs.peek().orEmpty().toList()
        // A compact recent context plus one complete current playlist, not the entire transcript.
        val currentList = messages.asReversed().firstOrNull { it.playlist != null }?.playlist
        val context = buildString {
            messages.dropLast(2).takeLast(4).forEach { message ->
                append(if (message.user) "用户：" else "助手：")
                appendLine((if (message.user) message.text else message.sentences.joinToString("")).take(600))
            }
            currentList?.let { list ->
                appendLine("当前播放列表：${list.name}")
                list.songs.forEach { appendLine("${it.name} — ${it.artist}") }
            }
        }
        val requestOwner = owner
        val requestConversation = conversationId
        generationJob = scope.launch {
            try {
                if (base.isBlank() || key.isBlank() || model.isBlank())
                    throw IOException("请先在设置中配置 AI 地址、Key 和模型")
                if (attached != null) {
                    reply.progress = "正在修改歌单"
                    val revised = runtime.dailyVibes.reviseForConversation(attached.original, attached.day, text)
                    if (account != runtime.controller.aiRecommendationAccountId()) throw IOException("账号已切换，请重新发送")
                    val snapshot = AiConversationPlaylist.from(revised)
                    reveal(reply, revised.summary.ifBlank { "已按你的要求调整选曲。" })
                    reply.playlist = snapshot
                    suggestions = revised.suggestions.orEmpty().toList()
                    // Do not override another playlist attached while this request was running.
                    if (attachment?.original === attached.original)
                        attachment = AiDailyAttachment(attached.day, revised, snapshot)
                } else coroutineScope {
                    val result = withContext(Dispatchers.IO) {
                        val requestJob = currentCoroutineContext()
                        val references = buildString {
                            val samples = AiReference.representativeSongs(history, AiReference.HISTORY_SAMPLE, Random())
                            if (samples.isNotEmpty()) appendLine("听歌历史：\n${samples.joinToString("\n")}")
                            append(runCatching { runtime.controller.aiPersonalCollectionReferences() }.getOrDefault(""))
                        }
                        requestJob.ensureActive()
                        AiMusicConversation.ask(AiClient(base, key, model, timeout), references, context, text)
                    }
                    if (account != runtime.controller.aiRecommendationAccountId()) throw IOException("账号已切换，请重新发送")
                    suggestions = result.suggestions.orEmpty().toList()
                    val revealJob = launch { reveal(reply, result.summary) }
                    if (result.songs.isNotEmpty()) {
                        reply.progress = "正在匹配网易云歌曲"
                        val matched = withContext(Dispatchers.IO) {
                            val requestContext = currentCoroutineContext()
                            val resolver = runtime.controller.aiRecommendationResolver {
                                requestContext.ensureActive()
                                if (account != runtime.controller.aiRecommendationAccountId()) throw IOException("账号已切换")
                            }
                            resolver.resolveBatch(result.songs, result.songs.size, emptySet()) { count, total ->
                                launch(Dispatchers.Main) { reply.progress = "正在匹配歌曲 $count/$total" }
                            }
                        }
                        if (matched.isEmpty()) throw IOException("没有匹配到可播放歌曲，请换个描述重试")
                        if (account != runtime.controller.aiRecommendationAccountId()) throw IOException("账号已切换，请重新发送")
                        revealJob.join()
                        reply.playlist = AiConversationPlaylist(result.playlistName, result.summary, AiMusicConversation.snapshot(matched))
                    } else revealJob.join()
                }
            } catch (cancelled: CancellationException) {
                reply.error = "上次生成已中断，可点击重试"
                throw cancelled
            } catch (error: Exception) {
                val message = AiEndpoint.connectionFailureMessage(error).takeIf { it.isNotBlank() }
                    ?: error.message ?: "生成失败，请重试"
                reply.error = if (key.isBlank()) message.take(200) else message.replace(key, "[已隐藏]").take(200)
            } finally {
                reply.loading = false
                if (owner == requestOwner && conversationId == requestConversation) {
                    busy = false
                    checkpoint()
                }
            }
        }
    }

    private suspend fun reveal(message: AiConversationMessage, text: String) {
        for (sentence in AiMusicConversation.sentences(text)) {
            message.sentences = message.sentences + sentence
            delay((sentence.length * 8L).coerceIn(160, 420))
        }
    }
}

internal val LocalMd3eAiConversation = staticCompositionLocalOf<Md3eAiConversationState?> { null }

private fun AiConversationPlaylist.toSaved() = AiConversationHistory.Playlist().also {
    it.name = name; it.summary = summary; it.songs = AiMusicConversation.snapshot(songs)
}

private fun AiConversationHistory.Playlist.toUi() = AiConversationPlaylist(name, summary, AiMusicConversation.snapshot(songs))
