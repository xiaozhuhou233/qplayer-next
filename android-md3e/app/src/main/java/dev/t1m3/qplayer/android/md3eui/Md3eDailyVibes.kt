package dev.t1m3.qplayer.android.md3eui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.t1m3.qplayer.ai.AiClient
import dev.t1m3.qplayer.ai.AiEndpoint
import dev.t1m3.qplayer.ai.AiReference
import dev.t1m3.qplayer.ai.DailyVibeCuration
import dev.t1m3.qplayer.ai.DailyMusicContext
import dev.t1m3.qplayer.ai.WebLookup
import org.json.JSONObject
import dev.t1m3.qplayer.bridge.PlayerController
import dev.t1m3.qplayer.netease.NeteaseClient
import dev.t1m3.qplayer.netease.dto.NeteaseSong
import dev.t1m3.qplayer.settings.SettingsCore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Random

internal data class DailyVibeState(
    val configured: Boolean = false,
    val day: String = "",
    val playlists: List<DailyVibeCuration.Playlist> = emptyList(),
    val loading: Boolean = false,
    val error: String = "",
    val progress: String = "等待生成今日歌单",
    val completedCount: Int = 0,
    val editingSlot: Int? = null,
    val savingSlot: Int? = null,
    val actionMessage: String = "",
    val actionError: String = "",
    val actionSlot: Int? = null,
    val liveStatus: String = "",
)

/** Process-owned job survives route changes and never borrows the active DJ request. */
internal class Md3eDailyVibes(context: Context, private val controller: PlayerController,
    private val settings: SettingsCore) {
    private val application = context.applicationContext
    val timed = Md3eTimedVibe(application, controller, settings)
    val playlists: List<DailyVibeCuration.Playlist> get() = state.playlists + listOfNotNull(timed.playlist)
    private val prefs = context.applicationContext.getSharedPreferences("md3e_daily_vibes", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var identity = ""
    private var checkedIdentity = ""
    private var generation = 0L
    private var accountId = 0L
    private var pendingEdit: Pair<Int, String>? = null
    var state by mutableStateOf(DailyVibeState())
        private set

    fun ensure(retry: Boolean = false) = start(manual = retry, fresh = false)

    fun regenerate() = start(manual = true, fresh = true)

    fun revise(slot: Int, requirements: String): Boolean {
        if (slot == 3 && requirements.isNotBlank() && !timed.loading && timed.playlist != null) {
            timed.ensure(manual = true, requirements = requirements); return true
        }
        if (requirements.isBlank() || job?.isActive == true || state.savingSlot != null || state.day != LocalDate.now().toString()
            || state.playlists.none { it.slot == slot }) return false
        start(manual = true, fresh = false, edit = slot to requirements.trim())
        return job?.isActive == true
    }

    /** Wait for this exact revision, not a later refresh/account's result. */
    suspend fun reviseForConversation(original: DailyVibeCuration.Playlist, day: String,
        requirements: String): DailyVibeCuration.Playlist {
        if (original.slot == 3) return timed.reviseForConversation(original, requirements)
        if (state.day != day || state.playlists.none { it === original })
            throw java.io.IOException("这组歌单已更新，请返回主页重新长按打开")
        if (!revise(original.slot, requirements))
            throw java.io.IOException("歌单正在处理，请完成后重试")
        val run = generation
        val owner = controller.aiRecommendationAccountId()
        val result = snapshotFlow { state }.first { !it.loading || generation != run || it.day != day }
        if (generation != run || owner != controller.aiRecommendationAccountId() || result.day != day)
            throw java.io.IOException("账号或今日歌单已切换，请重新打开")
        if (result.error.isNotBlank()) throw java.io.IOException(result.error)
        return result.playlists.firstOrNull { it.slot == original.slot && it !== original }
            ?: throw java.io.IOException("修改未完成，原歌单已保留")
    }

    fun retry() {
        val edit = pendingEdit
        if (edit != null) start(manual = true, fresh = false, edit = edit)
        else ensure(retry = true)
    }

    private fun catalog(slot: Int = -1): DailyVibeCuration.Catalog = if (slot == 3) timed.catalog() else DailyVibeCuration.Catalog().apply {
        day = state.day; owner = identity.substringAfter(':')
        playlists = state.playlists.toMutableList()
    }

    private fun persist(catalog: DailyVibeCuration.Catalog): Boolean {
        if (catalog.playlists.any { it.slot == 3 }) return timed.persist(catalog)
        if (!prefs.edit().putString("catalog", DailyVibeCuration.encode(catalog)).commit()) return false
        state = state.copy(playlists = catalog.playlists.toList(), completedCount = catalog.playlists.size)
        return true
    }

    fun updateSongs(original: DailyVibeCuration.Playlist, songs: List<NeteaseSong>): Boolean {
        if (state.loading || state.savingSlot != null || playlists.none { it === original }
            || (original.slot != 3 && state.day != LocalDate.now().toString()) || accountId != controller.aiRecommendationAccountId()) return false
        val updated = DailyVibeCuration.withSongs(catalog(original.slot), original.slot, songs)
        if (!persist(updated)) { state = state.copy(actionError = "无法保存歌曲修改，请重试", actionSlot = original.slot); return false }
        state = state.copy(actionMessage = "歌单已更新", actionError = "", actionSlot = original.slot)
        return true
    }

    fun forgetSavedPlaylist(id: Long) {
        timed.playlist?.takeIf { it.savedPlaylistId == id }?.let { playlist ->
            val snapshot = DailyVibeCuration.withSongs(timed.catalog(), 3, playlist.songs)
            snapshot.playlists.single().apply { savedPlaylistId = 0; savedSongIds = mutableListOf() }
            timed.persist(snapshot)
        }
        val current = catalog()
        var updated = current
        for (playlist in current.playlists.filter { it.savedPlaylistId == id }) {
            updated = DailyVibeCuration.withSongs(updated, playlist.slot, playlist.songs)
            updated.playlists.first { it.slot == playlist.slot }.apply {
                savedPlaylistId = 0; savedSongIds = mutableListOf()
            }
        }
        if (updated !== current) persist(updated)
    }

    /** Explicitly save/sync an edited snapshot; never changes the currently playing queue. */
    fun saveToNetease(original: DailyVibeCuration.Playlist, name: String) {
        if (controller.loggedIn.peek() != true) { state = state.copy(actionError = "请先登录网易云音乐", actionSlot = original.slot); return }
        if (accountId != controller.aiRecommendationAccountId()) {
            ensure(); return
        }
        if (state.loading || state.savingSlot != null
            || playlists.none { it === original } || name.isBlank()) return
        val run = generation
        val account = controller.aiRecommendationAccountId()
        state = state.copy(savingSlot = original.slot, actionMessage = "正在保存到网易云…", actionError = "", actionSlot = original.slot)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    fun checkAccount() {
                        ensureActive()
                        if (generation != run || account != controller.aiRecommendationAccountId()
                            || controller.loggedIn.peek() != true) throw CancellationException("账号或歌单已切换")
                    }
                    checkAccount()
                    val api = NeteaseClient.INSTANCE
                    var id = original.savedPlaylistId
                    val created = id == 0L
                    if (created) {
                        id = api.createPlaylist(name.trim(), false)
                        if (id == 0L) throw java.io.IOException("网易云未能创建歌单，请重试")
                        checkAccount()
                        withContext(Dispatchers.Main) {
                            // Persist the created id before adding tracks: retry fills the same
                            // playlist rather than creating another empty one after a failure.
                            val saved = DailyVibeCuration.withSongs(catalog(original.slot), original.slot, original.songs)
                            saved.playlists.first { it.slot == original.slot }.apply {
                                savedPlaylistId = id; this.name = name.trim()
                            }
                            if (!persist(saved)) throw java.io.IOException("歌单已创建（$id），本地记录保存失败")
                        }
                    }
                    checkAccount()
                    val desired = original.songs.map { it.id }
                    val existing = if (created) emptyList() else api.playlistTrackIds(id)
                    checkAccount()
                    if (!api.manipulatePlaylistTracks(id, desired.filter { it !in existing }, true))
                        throw java.io.IOException("部分歌曲未能添加，请重试同步")
                    checkAccount()
                    if (!api.manipulatePlaylistTracks(id, existing.filter { it !in desired }, false))
                        throw java.io.IOException("部分歌曲未能移除，请重试同步")
                    checkAccount()
                    if (!api.reorderPlaylistTracks(id, desired))
                        throw java.io.IOException("歌曲已保存，但顺序同步失败，请重试")
                    checkAccount()
                    if (api.playlistTrackIds(id) != desired)
                        throw java.io.IOException("网易云尚未确认全部歌曲和顺序，请稍后重试同步")
                    checkAccount()
                    withContext(Dispatchers.Main) {
                        val saved = DailyVibeCuration.withSongs(catalog(original.slot), original.slot, original.songs)
                        saved.playlists.first { it.slot == original.slot }.apply {
                            savedPlaylistId = id; savedSongIds = desired.toMutableList()
                        }
                        if (!persist(saved)) throw java.io.IOException("网易云已保存，本地记录保存失败")
                        state = state.copy(actionMessage = "已保存到网易云「我创建的」歌单", actionError = "")
                        controller.loadMyPlaylists()
                    }
                }
            } catch (cancelled: CancellationException) {
                if (generation == run) state = state.copy(actionMessage = "", actionError = "账号或歌单已切换，保存已停止")
            } catch (error: Exception) {
                if (generation == run) {
                    state = state.copy(actionMessage = "", actionError = error.message ?: "保存失败，请重试")
                    controller.loadMyPlaylists()
                }
            } finally {
                if (generation == run) state = state.copy(savingSlot = null)
            }
        }
    }

    private fun start(manual: Boolean, fresh: Boolean, edit: Pair<Int, String>? = null) {
        if (state.savingSlot != null) return
        val base = settings.str("aiBaseUrl").trim()
        val key = settings.str("aiApiKey").trim()
        val model = settings.str("aiModel").trim()
        if (base.isBlank() || key.isBlank() || model.isBlank()) {
            generation++; job?.cancel(); identity = ""; checkedIdentity = ""; pendingEdit = null; state = DailyVibeState()
            return
        }
        val day = LocalDate.now().toString()
        // No keys, cookies or raw account data are persisted in this cache identity.
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("v2-personal-20-30\n$base\n$key\n$model\n${controller.loggedIn.peek()}\n${controller.aiRecommendationAccountId()}".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val currentIdentity = "$day:$fingerprint"
        if (identity != currentIdentity) {
            job?.cancel(); job = null; identity = currentIdentity
            accountId = controller.aiRecommendationAccountId()
            pendingEdit = null
            state = DailyVibeState(configured = true, day = day)
        }
        if (job?.isActive == true || (!fresh && edit == null && state.playlists.size == 3)) return
        // Home checks may run every minute, but a completed/failed daily check
        // must not restart loading or issue another automatic request.
        if (!manual && checkedIdentity == currentIdentity) return
        checkedIdentity = currentIdentity
        pendingEdit = edit
        val history = controller.recentSongs.peek().orEmpty().toList()
        val loggedIn = controller.loggedIn.peek() == true
        val timeout = settings.intOf("aiTimeoutMs").let { if (it > 0) it else 60_000 }
        val run = ++generation
        fun report(message: String, completed: Int) {
            scope.launch {
                if (identity == currentIdentity && generation == run)
                    state = state.copy(progress = message, completedCount = completed)
            }
        }
        suspend fun publish(catalog: DailyVibeCuration.Catalog, message: String) {
            val completed = catalog.playlists.sortedBy { it.slot }
            withContext(Dispatchers.Main) {
                if (identity == currentIdentity && generation == run)
                    state = state.copy(playlists = completed, completedCount = completed.size, progress = message)
            }
        }
        job = scope.launch {
            state = if (edit != null) state.copy(loading = true, error = "", editingSlot = edit.first,
                progress = "正在按要求修改歌单…")
            else if (fresh) DailyVibeState(configured = true, day = day, loading = true,
                progress = "正在重新生成今日歌单…")
            else state.copy(loading = true, error = "", editingSlot = null, progress = "正在检查今日歌单…")
            try {
                withContext(Dispatchers.IO) {
                    val catalog = if (fresh) DailyVibeCuration.Catalog().apply { this.day = day; owner = fingerprint }
                    else DailyVibeCuration.restore(prefs.getString("catalog", "").orEmpty(), day, fingerprint)
                    // Restore completed groups even when today's automatic attempt already ran.
                    // Keep the published original identity on a failed edit, so retry
                    // can safely refer to the same attachment instead of a restored clone.
                    if (edit == null) publish(catalog, "已完成 ${catalog.playlists.size}/3 组")
                    if (edit == null && DailyVibeCuration.isComplete(catalog)) {
                        prefs.edit().putBoolean("attempted:$day", true).apply()
                        publish(catalog, "今日三组歌单已准备好")
                        return@withContext
                    }
                    // Persist a calendar day, so failures, app restarts and AI
                    // configuration changes never become repeated automatic runs.
                    // The old attempt identity also counts on upgrade.
                    val attemptedDay = prefs.getString("attemptDay", null)
                        ?: prefs.getString("attempt", "").orEmpty().substringBefore(':')
                    if (!manual && (prefs.getBoolean("attempted:$day", false) || attemptedDay == day)) {
                        withContext(Dispatchers.Main) { state = state.copy(
                            progress = "今日已自动生成过，已准备 ${catalog.playlists.size}/3 组",
                            error = if (prefs.getString("attempt", "") == currentIdentity)
                                prefs.getString("lastError", "").orEmpty() else "") }
                        return@withContext
                    }
                    ensureActive()
                    val attempt = prefs.edit().putBoolean("attempted:$day", true)
                        .putString("attemptDay", day).putString("attempt", currentIdentity)
                        .remove("attemptTime").remove("lastError")
                    if (fresh) attempt.remove("catalog")
                    // Save the quota before any network request, including a
                    // manual first run today, to survive a process restart.
                    if (!attempt.commit()) throw java.io.IOException("无法保存今日生成记录，请重试")
                    ensureActive()
                    report("正在读取听歌历史和收藏样本…", catalog.playlists.size)
                    val netease = NeteaseClient.INSTANCE
                    val random = Random()
                    val recent = history.ifEmpty {
                        if (loggedIn) runCatching { netease.recentPlayed(AiReference.HISTORY_POOL) }.getOrDefault(emptyList()) else emptyList()
                    }
                    val samples = StringBuilder()
                    val historySample = AiReference.representativeSongs(recent, AiReference.HISTORY_SAMPLE, random)
                    if (historySample.isNotEmpty()) samples.append("听歌历史样本：\n").append(historySample.joinToString("\n")).append('\n')
                    samples.append(runCatching { controller.aiPersonalCollectionReferences() }.getOrDefault(""))
                    ensureActive()
                    if (samples.isEmpty()) samples.append("暂无可用的个人样本，不虚构偏好；先策划三种不同的音乐方向。")
                    val client = AiClient(base, key, model, timeout)
                    val resolver = controller.aiRecommendationResolver { ensureActive() }
                    if (edit != null) {
                        var requests = 0
                        val updated = DailyVibeCuration.revise(catalog, edit.first, edit.second, samples.toString(),
                            { request, references, count ->
                                ensureActive()
                                requests++
                                report("正在修改第 ${edit.first + 1} 组歌单（$requests/3）…", catalog.playlists.size)
                                client.generatePlaylist("", request, count, "", references)
                            }, resolver, { matched, target ->
                                report("修改第 ${edit.first + 1} 组：已匹配 $matched/$target 首", catalog.playlists.size)
                            })
                        ensureActive()
                        if (!prefs.edit().putString("catalog", DailyVibeCuration.encode(updated)).remove("lastError").commit())
                            throw java.io.IOException("修改结果保存失败，请重试")
                        withContext(Dispatchers.Main) {
                            if (identity == currentIdentity && generation == run) {
                                pendingEdit = null
                                state = state.copy(playlists = updated.playlists.toList(), completedCount = updated.playlists.size,
                                    editingSlot = null, error = "", progress = "歌单已按要求修改")
                            }
                        }
                        return@withContext
                    }
                    val failures = mutableListOf<String>()
                    for (slot in 0..2) {
                        ensureActive()
                        if (catalog.playlists.any { it.slot == slot }) continue
                        try {
                            val webContext = if (slot == 2 && settings.bool("aiDailyContextEnabled")) {
                                report("正在查询今日天气与音乐事件…", catalog.playlists.size)
                                // Obtain evidence once per generation; reuse for matching/retries, never reuse
                                // legacy whole-day raw snippets or persist location coordinates.
                                val material = Md3eLiveEvidence.collect(application, settings, "今日天气与音乐事件",
                                    recent.map { it.artist.orEmpty() }.distinct().take(3), daily = true)
                                ensureActive()
                                val facts = JSONObject(material)
                                val weather = facts.getJSONObject("weather")
                                val city = weather.optString("place")
                                withContext(Dispatchers.Main) {
                                    if (identity == currentIdentity && generation == run)
                                        state = state.copy(liveStatus = DailyMusicContext.status(material))
                                }
                                DailyMusicContext.instructions(LocalDate.parse(day), city, material)
                            } else ""
                            var requests = 0
                            val playlist = DailyVibeCuration.generate(slot, samples.toString(), catalog.playlists,
                                { request, references, count ->
                                    ensureActive()
                                    requests++
                                    report(if (requests == 1) "正在策划第 ${slot + 1}/3 组歌单…"
                                        else "正在重试或补齐第 ${slot + 1}/3 组（$requests/3）…", catalog.playlists.size)
                                    client.generatePlaylist("", request, count, webContext, references)
                                }, resolver, { matched, target ->
                                    report("第 ${slot + 1}/3 组：已在网易云匹配 $matched/$target 首", catalog.playlists.size)
                                })
                            ensureActive()
                            catalog.playlists.add(playlist)
                            catalog.playlists.sortBy { it.slot }
                            prefs.edit().putString("catalog", DailyVibeCuration.encode(catalog)).apply()
                            publish(catalog, "第 ${slot + 1} 组已完成，已准备 ${catalog.playlists.size}/3 组")
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) {
                            failures += "第 ${slot + 1} 组：${describeFailure(error, key)}"
                            // A persistent transport failure affects all groups.
                            // Stop this run, preserve completed groups and await a manual retry.
                            if (AiEndpoint.isConnectionFailure(error)) break
                        }
                    }
                    if (DailyVibeCuration.isComplete(catalog)) withContext(Dispatchers.Main) {
                        state = state.copy(playlists = catalog.playlists.toList(), completedCount = 3,
                            progress = "今日三组歌单已准备好", error = "")
                        prefs.edit().remove("lastError").apply()
                    }
                    if (failures.isNotEmpty()) withContext(Dispatchers.Main) {
                        val error = failures.joinToString("\n")
                        prefs.edit().putString("lastError", error).apply()
                        state = state.copy(error = error, completedCount = catalog.playlists.size,
                            progress = "已完成 ${catalog.playlists.size}/3 组，剩余歌单需要重试")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (identity == currentIdentity && generation == run) {
                    val message = describeFailure(error, key)
                    prefs.edit().putString("lastError", message).apply()
                    state = state.copy(error = message,
                        progress = if (edit != null) "修改未完成，原歌单已保留" else "今日歌单生成未完成")
                }
            } finally {
                if (identity == currentIdentity && generation == run) state = state.copy(loading = false)
            }
        }
    }

    private fun describeFailure(error: Exception, key: String): String {
        AiEndpoint.connectionFailureMessage(error).takeIf { it.isNotBlank() }?.let { return it }
        return (error.message ?: "暂时无法生成，请检查网络与 AI 配置").replace(key, "[已隐藏]").take(200)
    }
}
