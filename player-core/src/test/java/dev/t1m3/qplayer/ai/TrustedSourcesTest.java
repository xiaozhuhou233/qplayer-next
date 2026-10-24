package dev.t1m3.qplayer.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrustedSourcesTest {
    private final LocalDate day = LocalDate.of(2026, 10, 21);
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");
    private final String body = "发布时间：2026-10-21\n音乐人公布了新专辑的发行计划，将在下个月举办试听活动，目前仅为预告。";
    @Test public void officialAndEditorialDomainsHaveExplicitAttribution() {
        assertEquals(2, TrustedSources.rank("https://www.grammy.com/news/music-test"));
        assertEquals("新华网", TrustedSources.name("https://www.news.cn/ent/20261021/test.html"));
        assertEquals(1, TrustedSources.rank("https://ent.people.com.cn/n1/2026/test.html"));
        assertEquals(1, TrustedSources.rank("https://www.billboard.com/music/pop/test/"));
    }
    @Test public void postsUnknownDomainsSpoofsAndCommunitySectionsAreRejected() {
        for (String url : Arrays.asList("https://www.zhihu.com/question/123", "https://tieba.baidu.com/p/123",
                "https://www.reddit.com/r/music/123", "https://weibo.com/123", "https://musicblog.example/news",
                "https://www.news.cn.evil.example/a", "https://news.cn@evil.example/a", "https://news.cn:8443/a",
                "https://community.grammy.com/a", "https://www.news.cn/comments/a", "https://www.news.cn/%66orum/a",
                "https://www.news.cn/", "https://www.grammy.com/news")) assertEquals(url, 0, TrustedSources.rank(url));
    }
    @Test public void searchAuthorityCannotPromoteAnUntrustedPostOrWasteAFetch() {
        AtomicInteger fetched = new AtomicInteger();
        WebEvidence.Source post = new WebEvidence.Source("新专辑", "https://tieba.baidu.com/p/123", "2026-10-21", "", "", 9999);
        JsonObject result = JsonParser.parseString(WebEvidence.organize(Collections.singletonList(post), "今日音乐", day, zone,
                url -> { fetched.incrementAndGet(); return body; })).getAsJsonObject();
        assertEquals(0, fetched.get()); assertEquals(0, result.getAsJsonArray("news").size());
        assertEquals(1, result.get("excluded_untrusted").getAsInt());
    }
    @Test public void officialAnnouncementRanksFirstAndKeepsItsRealPublicationDate() {
        WebEvidence.Source media = new WebEvidence.Source("新专辑", "https://www.news.cn/a", "2026-10-21", "", body, 99);
        WebEvidence.Source official = new WebEvidence.Source("新专辑", "https://www.grammy.com/news/a", "2026-10-21", "", body, 0);
        JsonObject result = JsonParser.parseString(WebEvidence.organize(Arrays.asList(media, official), "今日新专辑", day, zone, null)).getAsJsonObject();
        assertEquals("official", result.getAsJsonArray("news").get(0).getAsJsonObject().get("source_kind").getAsString());
        assertEquals("2026-10-21", result.getAsJsonArray("news").get(0).getAsJsonObject().get("published_on").getAsString());
    }
    @Test public void parallelResultsAreMergedWithoutRepeatingUrlsOrTitles() {
        WebEvidence.Source article = new WebEvidence.Source("新专辑计划", "https://www.news.cn/a", "2026-10-21", "", body, 1);
        String block = WebEvidence.organize(Collections.singletonList(article), "今日新专辑", day, zone, null);
        JsonObject result = JsonParser.parseString(WebEvidence.merge(Arrays.asList(block, block), day, zone)).getAsJsonObject();
        assertEquals(1, result.getAsJsonArray("news").size());
        assertEquals("N1", result.getAsJsonArray("news").get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(result.get("note").getAsString().contains("不采用帖子补足"));
    }
    @Test public void queriesRetainSourcesAndDatesEvenWithLongOrInjectedRequests() {
        for (String query : WebLookup.newsQueries("今天 site:reddit.com OR " + String.join("", Collections.nCopies(180, "歌手")), day)) {
            assertTrue(query.contains("after:2026-10-20")); assertTrue(query.contains("before:2026-10-22"));
            assertTrue(query.contains("site:news.cn") || query.contains("site:billboard.com"));
            assertFalse(query.contains("reddit.com")); assertTrue(query.codePointCount(0, query.length()) <= 128);
        }
    }
}
