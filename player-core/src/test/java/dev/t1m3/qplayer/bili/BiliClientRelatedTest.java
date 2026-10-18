package dev.t1m3.qplayer.bili;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class BiliClientRelatedTest {
    @Test
    public void relatedArchivesKeepServerOrderAndTheirOwnMetadata() {
        List<BiliClient.BiliVideo> videos = BiliClient.parseRelatedVideos(json(
                "{\"data\":[null,{\"bvid\":\"seed\"},{\"bvid\":\"removed\",\"state\":-4},"
                + "{\"bvid\":\"second\",\"aid\":2,\"cid\":22,\"title\":\"<em>标题</em>\","
                + "\"pic\":\"//example.invalid/cover.jpg\",\"duration\":125,"
                + "\"owner\":{\"name\":\"UP主\"},\"stat\":{\"view\":1234,\"danmaku\":9}},"
                + "{\"bvid\":\"first\",\"cid\":11,\"duration\":\"1:01\"},"
                + "{\"bvid\":\"second\",\"cid\":99},{\"title\":\"no id\"}]}"), "seed");
        assertEquals(2, videos.size());
        BiliClient.BiliVideo first = videos.get(0);
        assertEquals("second", first.bvid);
        assertEquals(22L, first.cid);
        assertEquals("标题", first.title);
        assertEquals("UP主", first.author);
        assertEquals("https://example.invalid/cover.jpg", first.coverUrl);
        assertEquals(125L, first.durationSeconds);
        assertEquals(1234L, first.playCount);
        assertEquals(9L, first.danmakuCount);
        assertEquals("first", videos.get(1).bvid);
        assertEquals(61L, videos.get(1).durationSeconds);
    }

    @Test
    public void detailDistinguishesMultiPartFromAcrossVideoCollection() {
        BiliClient.BiliVideoDetails details = BiliClient.parseVideoDetails(json(
                "{\"bvid\":\"clicked\",\"owner\":{\"name\":\"author\"},"
                + "\"pages\":[{\"cid\":10,\"part\":\"P1\",\"duration\":90},"
                + "{\"cid\":20,\"part\":\"P2\",\"duration\":120},{}],"
                + "\"ugc_season\":{\"sections\":[{\"episodes\":["
                + "{\"bvid\":\"before\",\"cid\":9,\"arc\":{\"title\":\"episode1\","
                + "\"duration\":30,\"pic\":\"//example.invalid/one.jpg\",\"author\":{\"name\":\"one\"}}},"
                + "{\"bvid\":\"clicked\",\"page\":{\"cid\":10,\"duration\":90},\"title\":\"episode2\"}]},"
                + "{\"episodes\":[{\"bvid\":\"clicked\",\"cid\":10},null,{}]}]}}"), "clicked");
        assertEquals(2, details.parts.size());
        assertEquals(10L, details.video.cid);
        assertEquals("P2", details.parts.get(1).title);
        assertEquals(120L, details.parts.get(1).durationSeconds);
        assertEquals(2, details.episodes.size());
        assertEquals("before", details.episodes.get(0).bvid);
        assertEquals("one", details.episodes.get(0).author);
        assertEquals("episode2", details.episodes.get(1).title);
        assertEquals("author", details.episodes.get(1).author);
        assertEquals(10L, details.episodes.get(1).cid);
        assertEquals(90L, details.episodes.get(1).durationSeconds);
    }

    @Test
    public void missingOptionalDataAndCidDoNotDiscardAnOtherwisePlayableRecommendation() {
        assertTrue(BiliClient.parseRelatedVideos(json("{\"data\":null}"), "seed").isEmpty());
        List<BiliClient.BiliVideo> videos = BiliClient.parseRelatedVideos(json(
                "{\"data\":[{\"bvid\":\"resolve-later\",\"owner\":null,\"stat\":null}]}"), "seed");
        assertEquals(1, videos.size());
        assertEquals(0L, videos.get(0).cid);
        BiliClient.BiliVideoDetails details = BiliClient.parseVideoDetails(json(
                "{\"cid\":7,\"pages\":null,\"ugc_season\":null}"), "fallback");
        assertEquals("fallback", details.video.bvid);
        assertEquals(7L, details.video.cid);
        assertTrue(details.parts.isEmpty());
        assertTrue(details.episodes.isEmpty());
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
