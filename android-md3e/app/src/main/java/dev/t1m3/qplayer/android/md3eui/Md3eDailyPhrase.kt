package dev.t1m3.qplayer.android.md3eui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.t1m3.qplayer.ai.AiClient
import dev.t1m3.qplayer.netease.NeteaseClient
import dev.t1m3.qplayer.netease.dto.NeteaseSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.LocalDate

private data class DailyPhraseConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val timeoutMs: Int,
)

@Composable
internal fun rememberMd3eDailyPhrase(runtime: Md3eRuntime): State<String> {
    val context = LocalContext.current.applicationContext
    val revision = runtime.settingsRevision
    val settings = runtime.settings
    val config = DailyPhraseConfig(
        settings.bool("aiDailyPhraseEnabled"), settings.str("aiBaseUrl").trim(),
        settings.str("aiApiKey").trim(), settings.str("aiModel").trim(),
        settings.intOf("aiTimeoutMs").coerceIn(10_000, 30_000),
    )
    return produceState(DailyPhraseStore.fallback(LocalDate.now()), context, revision, config) {
        while (true) {
            value = DailyPhraseStore.get(context, config, LocalDate.now(),
                runtime.controller.recentSongs.peek().orEmpty().toList(), runtime.home.loggedIn)
            // Refresh at the next local day even if the home screen stays open overnight.
            delay(60_000)
        }
    }
}

private object DailyPhraseStore {
    private val mutex = Mutex()
    private const val RETRY_DELAY_MS = 15 * 60 * 1000L
    private val localPhrases = listOf(
        "留一点时间，给喜欢的声音。",
        "让今天的心情，找到合拍的旋律。",
        "慢慢听，日常也有值得收藏的片刻。",
        "把耳机戴好，把这一刻留给自己。",
        "给生活按下播放，听见细小的美好。",
        "有些心情，一段旋律就能说清。",
        "愿今天的好歌，陪你多走一段路。",
    )

    fun fallback(day: LocalDate): String = localPhrases[Math.floorMod(day.toEpochDay(), localPhrases.size.toLong()).toInt()]

    suspend fun get(context: Context, config: DailyPhraseConfig, day: LocalDate,
        history: List<NeteaseSong>, loggedIn: Boolean): String = mutex.withLock {
        val fallback = fallback(day)
        if (!config.enabled || config.baseUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            return@withLock fallback
        }
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("md3e_daily_phrase", Context.MODE_PRIVATE)
            val dayKey = day.toString()
            if (prefs.getString("day", "") == dayKey) {
                val cached = prefs.getString("phrases", "").orEmpty().split("\n").mapNotNull(::cleanPhrase)
                if (cached.isNotEmpty()) {
                    val index = prefs.getInt("nextIndex", 0)
                    prefs.edit().putInt("nextIndex", (index + 1) % cached.size).apply()
                    return@withContext cached[index % cached.size]
                }
                cleanPhrase(prefs.getString("phrase", "").orEmpty())?.let { return@withContext it }
            }
            // Only a configuration fingerprint is retained; the key stays in the existing settings store.
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest("${config.baseUrl}\n${config.model}\n${config.apiKey}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val now = System.currentTimeMillis()
            val elapsed = now - prefs.getLong("attemptTime", 0L)
            if (prefs.getString("attemptDay", "") == dayKey &&
                prefs.getString("attemptConfig", "") == fingerprint && elapsed in 0 until RETRY_DELAY_MS) {
                return@withContext fallback
            }
            prefs.edit().putString("attemptDay", dayKey).putString("attemptConfig", fingerprint)
                .putLong("attemptTime", now).apply()
            try {
                val recent = history.ifEmpty {
                    if (loggedIn) runCatching { NeteaseClient.INSTANCE.recentPlayed(12) }.getOrDefault(emptyList())
                    else emptyList()
                }.take(8).mapNotNull { song ->
                    song.name?.takeIf { it.isNotBlank() }?.let { name ->
                        (name + " — " + song.artist.orEmpty()).replace('\n', ' ').take(100)
                    }
                }
                val prompt = "今天是 $dayKey。为今天写一句适合放在首页问候下方的音乐短句。" +
                    if (recent.isEmpty()) "" else "\n最近听过的歌曲，仅作音乐氛围参考：\n" + recent.joinToString("\n")
                val raw = AiClient(config.baseUrl, config.apiKey, config.model, config.timeoutMs).chatPlain(
                    "你为音乐播放器首页写每日音乐寄语。只输出一句原创中文短句，12到30个汉字，温暖自然、克制具体。" +
                        "与听歌或日常片刻有关，不引用现有歌词，不署名，不加引号。" +
                        "不要输出标题、解释、Markdown 或推理过程，不根据听歌记录推断用户的私人经历。", prompt, 192,
                )
                val phrases = raw.lineSequence().mapNotNull(::cleanPhrase).distinct().take(5).toList()
                val phrase = phrases.firstOrNull() ?: return@withContext fallback
                prefs.edit().putString("day", dayKey).putString("phrases", phrases.joinToString("\n"))
                    .putInt("nextIndex", if (phrases.size > 1) 1 else 0).putString("phrase", phrase).apply()
                phrase
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                fallback
            }
        }
    }

    private fun cleanPhrase(raw: String): String? {
        val stripped = raw.replace(Regex("(?is)<think>.*?</think>"), "").trim()
        val phrase = stripped.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()?.trim('"', '“', '”', '「', '」', '『', '』', '\'', '`', '*')?.trim().orEmpty()
        return phrase.takeIf {
            it.length in 6..80 && it.any { char -> char in '\u4e00'..'\u9fff' } &&
                !it.startsWith("<") && !it.startsWith("{") && !it.startsWith("#")
        }
    }
}
