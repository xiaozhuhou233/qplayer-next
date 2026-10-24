package dev.t1m3.qplayer.android.md3eui

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.t1m3.qplayer.ai.*
import dev.t1m3.qplayer.bridge.PlayerController
import dev.t1m3.qplayer.netease.dto.NeteaseSong
import dev.t1m3.qplayer.settings.SettingsCore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Random

/** Independent period cache: does not consume or reset the daily three-list quota. */
internal class Md3eTimedVibe(context: Context, private val controller: PlayerController, private val settings: SettingsCore) {
    private val prefs = context.getSharedPreferences("md3e_time_vibe", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var identity = ""
    private var periodKey = ""
    var playlist by mutableStateOf<DailyVibeCuration.Playlist?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set

    fun catalog() = DailyVibeCuration.Catalog().apply {
        day = periodKey; owner = identity; playlists = listOfNotNull(playlist).toMutableList()
    }
    fun persist(catalog: DailyVibeCuration.Catalog): Boolean {
        if (catalog.owner != identity || catalog.day != periodKey) return false
        val replacement = catalog.playlists.singleOrNull { it.slot == 3 } ?: return false
        if (!prefs.edit().putString("catalog", DailyVibeCuration.encode(catalog)).commit()) return false
        playlist = replacement
        return true
    }
    suspend fun reviseForConversation(original: DailyVibeCuration.Playlist, requirements: String): DailyVibeCuration.Playlist {
        if (playlist !== original || loading) throw java.io.IOException("时段歌单已更新或正在处理，请重新引用")
        val owner = identity; val period = periodKey
        ensure(manual = true, requirements = requirements)
        snapshotFlow { loading }.first { !it }
        if (identity != owner || periodKey != period) throw java.io.IOException("时段或账号已切换，请重新引用")
        if (error.isNotBlank()) throw java.io.IOException(error)
        return playlist?.takeIf { it !== original } ?: throw java.io.IOException("修改未完成，原歌单已保留")
    }
    fun ensure(manual: Boolean = false, requirements: String = "") {
        val base = settings.str("aiBaseUrl").trim(); val key = settings.str("aiApiKey").trim()
        val model = settings.str("aiModel").trim()
        if (base.isBlank() || key.isBlank() || model.isBlank()) {
            job?.cancel(); identity = ""; playlist = null; loading = false; return
        }
        val period = TimeVibePeriod.at(LocalDateTime.now())
        val account = controller.aiRecommendationAccountId()
        val loggedIn = controller.loggedIn.peek()
        val owner = MessageDigest.getInstance("SHA-256").digest(
            "$base\n$key\n$model\n$account\n$loggedIn".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val current = "$owner:${period.key}"
        if (identity != owner || periodKey != period.key) {
            job?.cancel(); identity = owner; periodKey = period.key; playlist = null; loading = false; error = ""
            playlist = DailyVibeCuration.restore(prefs.getString("catalog", ""), period.key, owner).playlists.singleOrNull { it.slot == 3 }
        }
        if (job?.isActive == true || !manual && playlist != null) return
        if (!manual && prefs.getString("attempt", "") == current) {
            if (error.isBlank()) error = "本时段已尝试生成，可手动重试"
            return
        }
        val original = playlist
        val timeout = settings.intOf("aiTimeoutMs").takeIf { it > 0 } ?: 60_000
        val history = controller.recentSongs.peek().orEmpty().toList()
        loading = true; error = ""; message = "正在准备${period.name}"
        job = scope.launch {
            try {
                if (!prefs.edit().putString("attempt", current).commit()) throw java.io.IOException("无法保存时段生成记录")
                val result = withContext(Dispatchers.IO) {
                    val references = buildString {
                        appendLine("历史样本：")
                        appendLine(AiReference.representativeSongs(history, 8, Random()).joinToString("\n"))
                        append(runCatching { controller.aiPersonalCollectionReferences() }.getOrDefault("")
                            .lineSequence().take(10).joinToString("\n"))
                    }
                    ensureActive()
                    val client = AiClient(base, key, model, timeout)
                    val recommender = DailyVibeCuration.Recommender { request, samples, count ->
                        ensureActive(); client.generatePlaylist("", request, count, "", samples)
                    }
                    val resolver = controller.aiRecommendationResolver { ensureActive() }
                    val progress = DailyVibeCuration.Progress { count, target ->
                        scope.launch { if (identity == owner && periodKey == period.key) message = "${period.name} · 已匹配 $count/$target 首" }
                    }
                    if (requirements.isNotBlank() && original != null) {
                        DailyVibeCuration.revise(catalog(), 3, requirements, references, recommender, resolver, progress)
                            .playlists.single { it.slot == 3 }
                    } else DailyVibeCuration.generateTimed(period.prompt(), references, recommender, resolver, progress)
                }
                ensureActive()
                if (identity == owner && periodKey == period.key && account == controller.aiRecommendationAccountId()
                    && loggedIn == controller.loggedIn.peek()) {
                    // Recheck the period at publication, including a boundary crossed during generation.
                    if (TimeVibePeriod.at(LocalDateTime.now()).key != period.key) return@launch
                    val catalog = DailyVibeCuration.Catalog().apply {
                        day = period.key; this.owner = owner; playlists = mutableListOf(result)
                    }
                    if (!persist(catalog)) throw java.io.IOException("无法保存时段歌单")
                    message = period.name + " · 已准备好"
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (identity == owner && periodKey == period.key) error =
                    (failure.message ?: "时段歌单生成失败").replace(key, "[已隐藏]").take(180)
            } finally {
                if (identity == owner && periodKey == period.key) loading = false
            }
        }
    }
}

@Composable
internal fun Md3eTimedVibeHost(runtime: Md3eRuntime) {
    val owner = LocalLifecycleOwner.current
    val revision = runtime.settingsRevision
    val account = runtime.controller.aiRecommendationAccountId()
    val loggedIn = runtime.home.loggedIn
    LaunchedEffect(revision, account, loggedIn) {
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) runtime.dailyVibes.timed.ensure()
    }
    DisposableEffect(owner, runtime) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) runtime.dailyVibes.timed.ensure()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
