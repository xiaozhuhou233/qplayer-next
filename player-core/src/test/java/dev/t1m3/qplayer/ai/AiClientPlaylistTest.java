package dev.t1m3.qplayer.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class AiClientPlaylistTest {
    @Test
    public void compactPlaylistRetainsReasonAndAcceptsUsefulResultsWithoutExactCount() throws Exception {
        try (Gateway gateway = new Gateway(false,
                "{\"name\":\"晚风\",\"summary\":\"从轻松旋律走向活力节奏\","
                        + "\"songs\":[[\"晴天\",\"周杰伦\",\"延续你常听的华语旋律，适合轻松时刻\"],"
                        + "[\"Yellow\",\"Coldplay\",\"温暖的吉他氛围呼应本次轻松要求\"]]}")) {
            AiPlaylistResult result = gateway.client().generatePlaylist("我喜欢的音乐：晴天 - 周杰伦",
                    "搜索最新的轻松歌曲", 6, "来源：官方音乐新闻 · 2026-10-21",
                    "我的听歌历史：晴天 - 周杰伦\n上一张歌单：Yellow - Coldplay");
            assertEquals(2, result.songs.size());
            assertEquals("晚风", result.playlistName);
            assertEquals("从轻松旋律走向活力节奏", result.summary);
            assertEquals("Coldplay", result.songs.get(1).artist);
            assertEquals("延续你常听的华语旋律，适合轻松时刻", result.songs.get(0).reason);
            assertEquals("温暖的吉他氛围呼应本次轻松要求", result.songs.get(1).reason);
            assertEquals("one response is sufficient", 1, gateway.requests.size());
            JsonObject request = gateway.requests.get(0);
            String system = request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            String user = request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
            assertTrue(system.contains("联网由应用检索"));
            assertTrue(system.contains("不虚构天气"));
            assertTrue(system.contains("以它们为主要选曲依据"));
            assertTrue(system.contains("大部分歌曲贴合历史与收藏"));
            assertTrue(system.contains("仅保留少量"));
            assertTrue(system.contains("不编造精确数值"));
            assertTrue(system.contains("缺少这些数据不妨碍选曲"));
            assertTrue(system.contains("应用负责搜索匹配"));
            assertTrue("prefer rows without repeated object keys", system.contains("[\"歌名\",\"歌手\",\"推荐原因\"]"));
            assertTrue("preserve the user's musical intent", user.contains("搜索最新的轻松歌曲"));
            assertTrue(user.contains("我的听歌历史"));
            assertTrue(user.contains("我喜欢的音乐"));
            assertTrue(user.contains("上一张歌单"));
            assertTrue(user.contains("来源：官方音乐新闻 · 2026-10-21"));
            assertFalse(user.contains("null"));
            assertNoTokenLimit(request);
            assertFalse(request.has("reasoning_effort"));
            assertFalse(request.has("tools"));
        }
    }

    @Test
    public void personalContextIsReusableBeforeVaryingRequestsWithoutDroppingSamples() throws Exception {
        String collections = "收藏歌单「最爱」：晴天 — 周杰伦\n收藏歌单「吉他」：Yellow — Coldplay";
        String history = "我的听歌历史：Blue — Joni Mitchell";
        try (Gateway gateway = new Gateway(false, "{\"songs\":[[\"晴天\",\"周杰伦\",\"贴合收藏\"]]}")) {
            AiClient client = gateway.client();
            client.generatePlaylist(collections, "轻柔", 5, "", history);
            client.generatePlaylist(collections, "更有活力", 30, "", history);
            client.generatePlaylist(collections, "继续播放", 6, "", history + "\n上一张歌单：晴天 — 周杰伦");
            assertEquals(3, gateway.requests.size());
            String prefix = null;
            for (JsonObject request : gateway.requests) {
                assertEquals(gateway.requests.get(0).getAsJsonArray("messages").get(0),
                        request.getAsJsonArray("messages").get(0));
                String user = request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
                assertTrue(user.contains(collections));
                assertTrue(user.contains(history));
                assertTrue(user.indexOf(history) < user.indexOf("本次音乐需求"));
                if (prefix == null) prefix = user.substring(0, user.indexOf("本次音乐需求"));
                else assertTrue(user.startsWith(prefix.trim()));
                assertNoTokenLimit(request);
            }
            String changed = gateway.requests.get(1).getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
            assertTrue(changed.contains("更有活力"));
            assertTrue(changed.contains("30 首"));
        }
    }

    @Test
    public void truncatedCompactRowsRetainCompleteSongReasonsWithoutAnotherCall() throws Exception {
        try (Gateway gateway = new Gateway(false,
                "{\"name\":\"夜色\",\"songs\":[[\"晴天\",\"周杰伦\",\"贴合 {温暖} 旋律\"],"
                        + "[\"Yellow\",\"Coldplay\",\"熟悉的吉他\"],[\"未完成")) {
            AiPlaylistResult result = gateway.client().generatePlaylist("收藏与历史", "轻松", 25);
            assertEquals(1, gateway.requests.size());
            assertEquals(2, result.songs.size());
            assertEquals("晴天", result.songs.get(0).title);
            assertEquals("贴合 {温暖} 旋律", result.songs.get(0).reason);
            assertEquals("熟悉的吉他", result.songs.get(1).reason);
        }
    }

    @Test
    public void legacyPairArraysStillProducePlayableRecommendations() throws Exception {
        try (Gateway gateway = new Gateway(false,
                "{\"name\":\"旧格式\",\"songs\":[[\"晴天\",\"周杰伦\"],[\"Yellow\",\"Coldplay\",null]]}")) {
            AiPlaylistResult result = gateway.client().generatePlaylist("", "轻松", 3);
            assertEquals(2, result.songs.size());
            assertEquals("晴天", result.songs.get(0).title);
            assertEquals("", result.songs.get(0).reason);
            assertEquals("", result.songs.get(1).reason);
            assertEquals(1, gateway.requests.size());
        }
    }

    @Test
    public void unsupportedJsonModeStillAcceptsLegacySongsAndKeepsSearchPolicy() throws Exception {
        try (Gateway gateway = new Gateway(true,
                "```json\n{\"playlistName\":\"熟悉的声音\",\"songs\":[{\"title\":\"晴天\","
                        + "\"artist\":\"周杰伦\",\"reason\":\"轻松\"}]}\n```")) {
            AiPlaylistResult result = gateway.client().generatePlaylist("晴天 - 周杰伦", "R and B", 3);
            assertEquals("晴天", result.songs.get(0).title);
            assertEquals("轻松", result.songs.get(0).reason);
            assertEquals(2, gateway.requests.size());
            assertTrue(gateway.requests.get(0).has("response_format"));
            assertFalse(gateway.requests.get(1).has("response_format"));
            assertEquals(gateway.requests.get(0).get("messages"), gateway.requests.get(1).get("messages"));
            assertNoTokenLimit(gateway.requests.get(0));
            assertNoTokenLimit(gateway.requests.get(1));
            assertTrue(gateway.requests.get(1).get("messages").toString().contains("R&B"));
        }
    }

    @Test
    public void unsupportedJsonModeIsRememberedForSubsequentGroupsAndRetries() throws Exception {
        try (Gateway gateway = new Gateway(true, "{\"songs\":[[\"晴天\",\"周杰伦\",\"贴合收藏\"]]}")) {
            AiClient client = gateway.client();
            client.generatePlaylist("收藏", "轻柔", 25);
            client.generatePlaylist("收藏", "摇滚", 25);
            assertEquals("Only the first group pays for a rejected JSON hint", 3, gateway.requests.size());
            assertTrue(gateway.requests.get(0).has("response_format"));
            assertFalse(gateway.requests.get(1).has("response_format"));
            assertFalse(gateway.requests.get(2).has("response_format"));
        }
    }

    @Test
    public void recoveryReselectsSongsWithOriginalRequestHistoryAndCollections() throws Exception {
        try (Gateway gateway = new Gateway(false,
                "{\"name\":\"策划方案\",\"summary\":\"缺少精确 BPM 数据，无法提供歌曲\",\"songs\":[]}",
                "{\"name\":\"温暖旋律\",\"summary\":\"以个人偏好选曲\",\"songs\":["
                        + "{\"title\":\"晴天\",\"artist\":\"周杰伦\",\"reason\":\"延续收藏中的华语旋律\"},"
                        + "{\"title\":\"Yellow\",\"artist\":\"Coldplay\",\"reason\":\"贴合历史中的吉他氛围\"}]}")) {
            AiPlaylistResult result = gateway.client().generatePlaylist("收藏样本：晴天 - 周杰伦",
                    "只创建第 2 组温暖歌曲", 25, "", "听歌历史：Yellow - Coldplay");
            assertEquals(2, gateway.requests.size());
            assertEquals(2, result.songs.size());
            assertEquals("晴天", result.songs.get(0).title);
            assertEquals("延续收藏中的华语旋律", result.songs.get(0).reason);
            JsonObject recovery = gateway.requests.get(1);
            String system = recovery.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            String user = recovery.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
            assertTrue(system.contains("每次只返回当前一组歌单"));
            assertTrue(system.contains("title、artist、reason"));
            assertTrue(system.contains("重新按原始需求和个人样本选曲"));
            assertTrue(system.contains("联网由应用检索"));
            assertTrue(user.contains("只创建第 2 组温暖歌曲"));
            assertTrue(user.contains("收藏样本：晴天 - 周杰伦"));
            assertTrue(user.contains("听歌历史：Yellow - Coldplay"));
            assertTrue(user.contains("25 首"));
            assertTrue(user.contains("缺少精确 BPM 数据"));
            assertNoTokenLimit(recovery);
        }
    }

    @Test
    public void emptyResponsesStopAfterOneRecoveryWithoutInventingSongs() throws Exception {
        try (Gateway gateway = new Gateway(false, "{\"songs\":[]}")) {
            try {
                gateway.client().generatePlaylist("收藏样本", "轻松", 25);
                fail("Two empty responses must remain an explicit failure");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("格式恢复仍未成功"));
            }
            assertEquals(2, gateway.requests.size());
        }
    }

    @Test
    public void wrappedTracksAndArtistArraysAreAcceptedWithoutAnotherModelCall() throws Exception {
        try (Gateway gateway = new Gateway(false,
                "{\"data\":{\"playlist\":{\"playlist_name\":\"晚风\",\"tracks\":["
                        + "{\"song_name\":\"晴天\",\"artists\":[{\"name\":\"周杰伦\"}],\"recommendation_reason\":\"贴合收藏旋律\"},"
                        + "{\"song_title\":\"Yellow\",\"artist_name\":\"Coldplay\",\"推荐原因\":\"熟悉的吉他氛围\"}]}}}")) {
            AiPlaylistResult result = gateway.client().generatePlaylist("收藏与历史", "轻松", 25);
            assertEquals(1, gateway.requests.size());
            assertEquals("晚风", result.playlistName);
            assertEquals(2, result.songs.size());
            assertEquals("周杰伦", result.songs.get(0).artist);
            assertEquals("贴合收藏旋律", result.songs.get(0).reason);
            assertEquals("熟悉的吉他氛围", result.songs.get(1).reason);
        }
    }

    @Test
    public void rootArrayWithInvalidRowsKeepsOnlyActualSongs() throws Exception {
        try (Gateway gateway = new Gateway(false,
                "[null,{\"title\":null,\"artist\":\"未知\"},{\"title\":\"晴天\",\"artist\":[\"周杰伦\"]},"
                        + "{\"title\":\"Yellow\",\"artist\":{\"name\":\"Coldplay\"},\"reason\":\"吉他\"}]")) {
            AiPlaylistResult result = gateway.client().generatePlaylist("", "轻松", 25);
            assertEquals(1, gateway.requests.size());
            assertEquals(2, result.songs.size());
            assertEquals("晴天", result.songs.get(0).title);
            assertEquals("Coldplay", result.songs.get(1).artist);
        }
    }

    @Test
    public void preambleBracesDoNotCorruptJsonOrQuotedBracesInReasons() throws Exception {
        try (Gateway gateway = new Gateway(false,
                "说明 {not valid json}。\n```json\n{\"name\":\"夜色\",\"songs\":["
                        + "{\"title\":\"晴天\",\"artist\":\"周杰伦\",\"reason\":\"延续 {温暖} 与 \\\"轻松\\\" 氛围\"}]}\n```")) {
            AiPlaylistResult result = gateway.client().generatePlaylist("", "轻松", 25);
            assertEquals(1, gateway.requests.size());
            assertEquals("夜色", result.playlistName);
            assertEquals("延续 {温暖} 与 \"轻松\" 氛围", result.songs.get(0).reason);
        }
    }

    @Test
    public void truncatedOutputRetainsCompleteSongsWithoutInventingTheIncompleteOne() throws Exception {
        StringBuilder content = new StringBuilder("{\"name\":\"夜色\",\"songs\":[");
        for (int i = 1; i <= 20; i++) content.append("{\"title\":\"曲目").append(i)
                .append("\",\"artist\":\"作者\",\"reason\":\"贴合收藏\"},");
        content.append("{\"title\":\"未完成");
        try (Gateway gateway = new Gateway(false, content.toString())) {
            AiPlaylistResult result = gateway.client().generatePlaylist("收藏与历史", "轻松", 25);
            assertEquals(1, gateway.requests.size());
            assertEquals(20, result.songs.size());
            assertEquals("曲目20", result.songs.get(19).title);
            assertEquals("贴合收藏", result.songs.get(19).reason);
        }
    }

    @Test
    public void dailyCurationUsesExactlyTheAiDjSystemPromptAndPlayableSongContract() throws Exception {
        com.google.gson.JsonArray songs = new com.google.gson.JsonArray();
        for (int i = 1; i <= 30; i++) {
            JsonObject song = new JsonObject(); song.addProperty("title", Integer.toString(i));
            song.addProperty("artist", "作者"); song.addProperty("reason", "贴合收藏中的旋律"); songs.add(song);
        }
        JsonObject answer = new JsonObject(); answer.addProperty("name", "熟悉的旋律"); answer.add("songs", songs);
        try (Gateway gateway = new Gateway(false, answer.toString())) {
            AiClient client = gateway.client();
            DailyVibeCuration.Playlist daily = DailyVibeCuration.generate(0, "听歌历史与收藏样本",
                    java.util.Collections.emptyList(),
                    (request, references, count) -> client.generatePlaylist("", request, count, "", references),
                    (title, artist) -> {
                        dev.t1m3.qplayer.netease.dto.NeteaseSong song = new dev.t1m3.qplayer.netease.dto.NeteaseSong();
                        song.id = Long.parseLong(title); song.name = title; song.artist = artist; return song;
                    });
            client.generatePlaylist("", "按我的偏好随机推荐", 5, "", "听歌历史与收藏样本");
            assertEquals(2, gateway.requests.size());
            assertEquals(gateway.requests.get(0).getAsJsonArray("messages").get(0),
                    gateway.requests.get(1).getAsJsonArray("messages").get(0));
            assertEquals("熟悉的旋律", daily.name);
            assertTrue(daily.songs.size() >= 20 && daily.songs.size() <= 30);
            assertEquals("贴合收藏中的旋律", daily.songs.get(0).recommendReason);
        }
    }

    @Test
    public void blankApiContentHasARecoverableErrorInsteadOfNullOrJsonCrash() throws Exception {
        try (Gateway gateway = new Gateway(false, "")) {
            try { gateway.client().generatePlaylist("", "轻松", 25); fail("Must reject blank content"); }
            catch (AiClient.ResponseFormatException expected) { assertTrue(expected.getMessage().contains("空白内容")); }
            assertEquals(1, gateway.requests.size());
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void everyChatEntryPointLeavesTokenBudgetToTheProvider() throws Exception {
        try (Gateway gateway = new Gateway(false, "回答")) {
            AiClient client = gateway.client();
            assertEquals("回答", client.chat("返回 JSON", "需求"));
            assertEquals("回答", client.chatPlain("一句话", "需求"));
            assertEquals("回答", client.chatPlain("一句话", "需求", 32));
            assertEquals(3, gateway.requests.size());
            for (JsonObject request : gateway.requests) assertNoTokenLimit(request);
        }
    }

    private static void assertNoTokenLimit(JsonObject request) {
        assertFalse("Do not send an application output ceiling", request.has("max_tokens"));
        assertFalse(request.has("max_completion_tokens"));
        assertFalse(request.has("max_output_tokens"));
    }

    private static final class Gateway implements AutoCloseable {
        final List<JsonObject> requests = new ArrayList<>();
        final HttpServer server;
        Gateway(boolean rejectJson, String... contents) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                ByteArrayOutputStream input = new ByteArrayOutputStream();
                try (InputStream stream = exchange.getRequestBody()) {
                    byte[] bytes = new byte[4096];
                    for (int n; (n = stream.read(bytes)) >= 0;) input.write(bytes, 0, n);
                }
                requests.add(JsonParser.parseString(new String(input.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject());
                boolean reject = rejectJson && requests.size() == 1;
                JsonObject message = new JsonObject();
                message.addProperty("content", contents[Math.min(requests.size() - 1, contents.length - 1)]);
                JsonObject choice = new JsonObject(); choice.add("message", message);
                com.google.gson.JsonArray choices = new com.google.gson.JsonArray(); choices.add(choice);
                JsonObject response = new JsonObject(); response.add("choices", choices);
                byte[] body = (reject ? "{\"error\":{\"message\":\"response_format unsupported\"}}"
                        : response.toString()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reject ? 400 : 200, body.length);
                try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        }
        AiClient client() { return new AiClient("http://127.0.0.1:" + server.getAddress().getPort(), "", "deepseek-chat", 5000); }
        @Override public void close() { server.stop(0); }
    }
}
