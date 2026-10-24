package dev.t1m3.qplayer.android.md3eui

import android.content.Context
import dev.t1m3.qplayer.ai.DailyMusicContext
import dev.t1m3.qplayer.ai.WebEvidence
import dev.t1m3.qplayer.ai.WebLookup
import dev.t1m3.qplayer.ai.WeatherFacts
import dev.t1m3.qplayer.settings.SettingsCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZonedDateTime

/** No raw search pages, personal samples or coordinates in this evidence envelope. */
internal object Md3eLiveEvidence {
    suspend fun collect(context: Context, settings: SettingsCore, request: String,
        artists: List<String> = emptyList(), daily: Boolean = false): String = coroutineScope {
        val now = ZonedDateTime.now()
        val weatherRequested = daily || WebEvidence.weatherRequest(request)
        val weather = async {
            if (weatherRequested) Md3eWeatherLocation.weather(context, now, settings.str("aiWeatherFallbackCity"))
            else WeatherFacts.unavailable("本次未请求天气", now)
        }
        val news = async(Dispatchers.IO) {
            val queries = if (daily || weatherRequested) DailyMusicContext.queries(now.toLocalDate(), "", artists)
                else WebLookup.newsQueries(request, now.toLocalDate())
            // A weather-only request needs no unrelated music-news search.
            if (!daily && weatherRequested && !request.contains("新闻") && !request.contains("事件"))
                WebEvidence.organize(emptyList(), request, now.toLocalDate(), now.zone, null)
            else coroutineScope {
                suspend fun search(query: String) = withContext(Dispatchers.IO) {
                    WebLookup.verified(settings.str("aiWebSearchUrl"), settings.str("aiWebSearchKey"),
                        settings.str("aiBaseUrl"), settings.str("aiApiKey"), query, 8000, now.toLocalDate(), now.zone)
                }
                val blocks = queries.map { query -> async { search(query) } }.awaitAll().toMutableList()
                // No recent news about a favorite artist is normal. Try a broader trusted music query once.
                if (daily && artists.isNotEmpty()) {
                    val broad = DailyMusicContext.queries(now.toLocalDate(), "", emptyList())
                    val fallback = blocks.mapIndexedNotNull { index, block ->
                        val data = JSONObject(block)
                        broad[index].takeIf { data.optString("status") == "no_usable_sources" && it != queries[index] }
                    }
                    blocks += fallback.map { query -> async { search(query) } }.awaitAll()
                }
                WebEvidence.merge(blocks, now.toLocalDate(), now.zone)
            }
        }
        val evidence = JSONObject().put("as_of", now.toString()).put("timezone", now.zone.id)
            .put("weather", JSONObject(weather.await())).put("music_news", JSONObject(news.await()))
            .put("usage", "天气只适用于 place 所标位置；用户指定其他地点时不可套用。个人历史收藏另列，不是实时事实。新闻仅原文日期与来源检查，不代表独立多源证实。缺哪项只说明哪项，不否定其他有效资料。")
        evidence.put("availability", DailyMusicContext.status(evidence.toString())).toString()
    }
}
