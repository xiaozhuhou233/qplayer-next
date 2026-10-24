package dev.t1m3.qplayer.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebEvidenceTest {
    @Test public void englishAndExplicitArticleMetadataDatesAreSupported() {
        for (String prefix : new String[]{"Published on October 21, 2026\n", "Published: 21 Oct 2026\n",
                "<meta content=\"2026-10-21T03:00:00Z\" property=\"article:published_time\">",
                "<script type=\"application/ld+json\">{\"datePublished\":\"2026-10-21T03:00:00Z\"}</script>"}) {
            JsonObject result = organize("今日 Coldplay", source("https://www.grammy.com/news/test", "",
                    prefix + "\nColdplay will hold a listening event for their new album next month; it is a preview, not a release."));
            assertEquals(prefix, 1, result.getAsJsonArray("news").size());
        }
    }
    @Test public void eventDateOrModifiedDateAloneDoesNotCertifyPublication() {
        String body = "<script>{\"dateModified\":\"2026-10-21\"}</script>\n"
                + "Coldplay announced a future concert. Event date: October 21, 2026; no publication date is available.";
        JsonObject result = organize("今日 Coldplay", source("https://www.grammy.com/news/test", "", body));
        assertEquals(0, result.getAsJsonArray("news").size());
        assertEquals(1, result.get("invalid_date").getAsInt());
    }
    @Test public void missingBodiesAndDatesHaveSeparateDiagnostics() {
        JsonObject result = organize("今日 Coldplay", source("https://www.grammy.com/news/empty", "2026-10-21", ""),
                source("https://www.grammy.com/news/undated", "", "Coldplay will hold a new concert next month, with the lineup yet to be announced."));
        assertEquals(1, result.get("missing_body").getAsInt()); assertEquals(1, result.get("invalid_date").getAsInt());
    }
    private final LocalDate day = LocalDate.of(2026, 10, 21);
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");
    private final String article = "发布时间：2026-10-21\nColdplay 宣布下个月举行新专辑试听活动，目前只是预告，并未正式发布该专辑。";
    private WebEvidence.Source source(String url, String date, String body) {
        return new WebEvidence.Source("Coldplay 新专辑", url, date, "不要使用未经核对的摘要", body, 5);
    }
    private JsonObject organize(String request, WebEvidence.Source... sources) {
        return JsonParser.parseString(WebEvidence.organize(Arrays.asList(sources), request, day, zone, null)).getAsJsonObject();
    }
    @Test public void todayRequestExcludesOldFutureUndatedAndSummaryOnlySources() {
        JsonObject result = organize("今天 Coldplay 新专辑", source("https://www.news.cn/old", "2026-10-19", article),
                source("https://www.news.cn/future", "2026-10-22", article),
                source("https://www.news.cn/undated", "", "Coldplay 宣布下个月举行新专辑试听活动，目前只是预告，并未正式发布该专辑。"),
                source("https://www.news.cn/summary", "2026-10-21", ""));
        assertEquals(0, result.getAsJsonArray("news").size());
        assertFalse(result.toString().contains("不要使用未经核对的摘要"));
    }
    @Test public void originalPublicationDateOverridesFreshSearchMetadata() {
        JsonObject result = organize("今日 Coldplay", source("https://www.news.cn/a", "2026-10-21", article.replace("2026-10-21", "2026-10-19")));
        assertEquals(0, result.getAsJsonArray("news").size());
    }
    @Test public void utcPublicationIsConvertedToLocalDate() {
        assertEquals(day, WebEvidence.date("2026-10-20T18:00:00Z", zone));
    }
    @Test public void duplicateUrlsAreCollapsedAndEvidenceRetainsDatesSourcesAndQualification() {
        JsonObject result = organize("今天 Coldplay", source("https://www.news.cn/a?utm_source=x", "2026-10-21", article),
                source("https://www.news.cn/a#top", "2026-10-21", article));
        assertEquals(1, result.getAsJsonArray("news").size());
        JsonObject row = result.getAsJsonArray("news").get(0).getAsJsonObject();
        assertEquals("2026-10-21", row.get("published_on").getAsString());
        assertTrue(row.get("excerpt").getAsString().contains("只是预告"));
        assertTrue(row.get("check").getAsString().contains("未作独立多源证实"));
    }
    @Test public void weatherArticlesCannotCertifyCurrentWeather() {
        assertEquals(0, organize("今天天气", source("https://weather.example/a", "2026-10-21", article)).getAsJsonArray("news").size());
    }
    @Test public void fetchFailureDoesNotPreventOtherUsableEvidence() {
        String result = WebEvidence.organize(Arrays.asList(source("https://www.news.cn/fail", "2026-10-21", ""),
                source("https://www.news.cn/good", "2026-10-21", article)), "今日 Coldplay", day, zone,
                url -> { throw new java.io.IOException("offline"); });
        assertEquals(1, JsonParser.parseString(result).getAsJsonObject().getAsJsonArray("news").size());
    }
    @Test public void excerptsRemoveAdsLinksImagesAndScripts() {
        String excerpt = WebEvidence.excerpt("<script>Coldplay track user secret code</script>\n"
                + "相关推荐 Coldplay 点击登录并订阅邮件后获取更多消息。\n"
                + "![cover](https://image.example/a)\n[Coldplay](https://artist.example) 宣布下个月举行新专辑试听活动，目前只是预告，并未正式发布该专辑。", "Coldplay", "新专辑");
        assertTrue(excerpt.contains("试听活动")); assertFalse(excerpt.contains("secret"));
        assertFalse(excerpt.contains("https://")); assertFalse(excerpt.contains("相关推荐"));
    }
    @Test public void dateFiltersSurviveLongRequests() {
        String query = WebLookup.limitDatedQuery("今天 " + String.join("", Collections.nCopies(150, "音乐")), day);
        assertTrue(query.startsWith("after:2026-10-20 before:2026-10-22"));
        assertEquals(128, query.codePointCount(0, query.length()));
    }
    @Test public void dateNavigationAndRepeatedParagraphsAreNotMusicEvidence() {
        String body = "2026-10-21 2026年度列表 今日栏目档案索引 更新时间相关链接。\n"
                + "Coldplay 新专辑将于下个月举办试听活动，尚未发行，只是音乐人的新计划。\n"
                + "Coldplay 新专辑将于下个月举办试听活动，尚未发行，只是音乐人的新计划。";
        String excerpt = WebEvidence.excerpt(body, "after:2026-10-14 before:2026-10-22 Coldplay 新专辑", "Coldplay 新专辑");
        assertFalse(excerpt.contains("档案索引"));
        assertEquals(excerpt.indexOf("Coldplay"), excerpt.lastIndexOf("Coldplay"));
        assertTrue(excerpt.contains("尚未发行"));
    }
}
