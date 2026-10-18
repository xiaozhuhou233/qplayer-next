package dev.t1m3.qplayer.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class AiMusicConversationTest {
    @Test public void conversationCanReplyWithoutReplacingPlaybackWithAnEmptyPlaylist() throws Exception {
        AiPlaylistResult reply = AiMusicConversation.parseResponse("```json\n{\"summary\":\"这首歌的合成器让氛围更温暖。\",\"songs\":[]}\n```");
        assertFalse(reply.summary.isEmpty());
        assertTrue(reply.songs.isEmpty());
    }

    @Test public void playlistRetainsNameSongsAndReasonsAlongsideConversationText() throws Exception {
        AiPlaylistResult reply = AiMusicConversation.parseResponse("以下是结果 {无效}\n{\"name\":\"夜行\",\"summary\":\"先舒缓，再加入节拍。\",\"songs\":[[\"Yellow\",\"Coldplay\",\"贴合收藏中的吉他氛围\"],{\"title\":\"Heartless\",\"artist\":\"The Weeknd\",\"reason\":\"延续常听歌手\"}]}");
        assertEquals("夜行", reply.playlistName);
        assertEquals(2, reply.songs.size());
        assertEquals("贴合收藏中的吉他氛围", reply.songs.get(0).reason);
        assertEquals("The Weeknd", reply.songs.get(1).artist);
    }

    @Test public void malformedOrTruncatedProtocolIsNotShownAsAssistantText() {
        for (String raw : Arrays.asList("", "just text", "{\"summary\":\"done\",\"songs\":[[\"x\"]]}",
                "{\"summary\":\"done\",\"songs\":[[\"song\",\"artist\"]", "{\"summary\":\"\",\"songs\":[]}")) {
            try { AiMusicConversation.parseResponse(raw); fail("Must reject " + raw); }
            catch (Exception expected) { assertTrue(expected instanceof AiClient.ResponseFormatException); }
        }
    }

    @Test public void quotedBracesDoNotBreakResponseExtraction() throws Exception {
        AiPlaylistResult reply = AiMusicConversation.parseResponse("{\"summary\":\"喜欢 {night} 的感觉？\",\"songs\":[]}");
        assertEquals("喜欢 {night} 的感觉？", reply.summary);
    }

    @Test public void followupsComeFromEachResponseAndMalformedOptionalSuggestionsAreIgnored() throws Exception {
        AiPlaylistResult first = AiMusicConversation.parseResponse(
                "{\"summary\":\"先听爵士。\",\"songs\":[],\"suggestions\":[\"多一点萨克斯\",\"多一点萨克斯\",null,{},9,\"换成现场版\",\"再舒缓一些\",\"第四条\"]}");
        assertEquals(Arrays.asList("多一点萨克斯", "换成现场版", "再舒缓一些"), first.suggestions);
        AiPlaylistResult second = AiMusicConversation.parseResponse(
                "{\"summary\":\"换成摇滚。\",\"songs\":[],\"suggestions\":[\"来点英伦摇滚\"]}");
        assertEquals(Collections.singletonList("来点英伦摇滚"), second.suggestions);
        assertTrue(AiMusicConversation.parseResponse("{\"summary\":\"可以。\",\"songs\":[],\"suggestions\":{}}").suggestions.isEmpty());
    }

    @Test public void oldMessageAndPlaybackCopiesAreIndependentOfFutureEditsAndEnrichment() {
        NeteaseSong original = new NeteaseSong();
        original.id = 12; original.name = "Song"; original.artist = "Artist"; original.albumId = 42;
        original.artistIdsCsv = "1,2"; original.recommendReason = "熟悉的氛围"; original.durationMs = 123456;
        List<NeteaseSong> source = new ArrayList<>(Collections.singletonList(original));
        List<NeteaseSong> sent = AiMusicConversation.snapshot(source);
        List<NeteaseSong> playback = AiMusicConversation.snapshot(sent);
        source.clear(); original.name = "Edited"; playback.get(0).recommendReason = "Changed";
        assertEquals(1, sent.size()); assertEquals("Song", sent.get(0).name);
        assertEquals("熟悉的氛围", sent.get(0).recommendReason);
        assertEquals(42, sent.get(0).albumId); assertEquals("1,2", sent.get(0).artistIdsCsv);
        assertEquals(123456, sent.get(0).durationMs);
    }

    @Test public void sentenceRevealPreservesEnglishTitlesDecimalsAndChineseSentences() {
        assertEquals(Arrays.asList("先听 The Weeknd。", "BPM 约 90.5。", "Next song!", "再慢下来"),
                AiMusicConversation.sentences("先听 The Weeknd。BPM 约 90.5。Next song! 再慢下来"));
    }

    @Test public void requestUsesOneConfiguredCallWithHistoryCollectionsAndFollowupWithoutTokenCeiling() throws Exception {
        List<JsonObject> requests = new ArrayList<>();
        HttpServer gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/chat/completions", exchange -> {
            ByteArrayOutputStream input = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = exchange.getRequestBody().read(buffer)) >= 0;) input.write(buffer, 0, n);
            requests.add(JsonParser.parseString(new String(input.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject());
            JsonObject message = new JsonObject(); message.addProperty("content", "{\"summary\":\"可以，保留柔和氛围。\",\"songs\":[]}");
            JsonObject choice = new JsonObject(); choice.add("message", message);
            com.google.gson.JsonArray choices = new com.google.gson.JsonArray(); choices.add(choice);
            JsonObject response = new JsonObject(); response.add("choices", choices);
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        gateway.start();
        try {
            AiClient client = new AiClient("http://127.0.0.1:" + gateway.getAddress().getPort(), "", "deepseek-chat", 5000);
            AiMusicConversation.ask(client, "听歌历史：A\n收藏：B", "当前歌单：C", "保留氛围，增加女声");
            assertEquals(1, requests.size());
            JsonObject request = requests.get(0);
            String wire = request.toString();
            assertTrue(wire.contains("听歌历史：A")); assertTrue(wire.contains("收藏：B"));
            assertTrue(wire.contains("当前歌单：C")); assertTrue(wire.contains("增加女声"));
            assertTrue(wire.contains("禁止联网"));
            for (String field : Arrays.asList("max_tokens", "max_completion_tokens", "max_output_tokens", "tools")) assertFalse(request.has(field));
        } finally { gateway.stop(0); }
    }
}
