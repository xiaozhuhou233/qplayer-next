package dev.t1m3.qplayer.ai;

import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;

public class AiConversationHistoryTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private AiConversationHistory.Archive transcript(String title) {
        AiConversationHistory.Archive archive = new AiConversationHistory.Archive();
        AiConversationHistory.Session session = new AiConversationHistory.Session();
        session.id = "session-1"; session.title = title; session.updatedAt = 123456;
        session.draft = "再加点女声"; session.suggestions.add("换成更轻快的节奏");
        AiConversationHistory.Message message = new AiConversationHistory.Message();
        message.id = 1; message.sentences.add("从你收藏的合成器氛围出发。");
        message.playlist = new AiConversationHistory.Playlist();
        message.playlist.name = "夜行";
        NeteaseSong song = new NeteaseSong();
        song.id = 123; song.name = "Heartless"; song.artist = "The Weeknd";
        song.recommendReason = "延续收藏中的歌手"; song.coverUrl = "https://example.org/cover";
        message.playlist.songs.add(song);
        session.messages.add(message); archive.sessions.add(session);
        return archive;
    }

    @Test public void freshStoreInstanceRestoresMessagesPlayableSongsReasonsDraftAndSuggestions() throws Exception {
        File directory = folder.newFolder();
        new AiConversationHistory(directory).save(1, AiConversationHistory.encode(transcript("夜晚听什么")));
        AiConversationHistory.Session restored = new AiConversationHistory(directory).load(1).sessions.get(0);
        assertEquals("夜晚听什么", restored.title);
        assertEquals("再加点女声", restored.draft);
        assertEquals(Arrays.asList("换成更轻快的节奏"), restored.suggestions);
        assertEquals("从你收藏的合成器氛围出发。", restored.messages.get(0).sentences.get(0));
        NeteaseSong song = restored.messages.get(0).playlist.songs.get(0);
        assertEquals(123, song.id); assertEquals("延续收藏中的歌手", song.recommendReason);
        assertEquals("https://example.org/cover", song.coverUrl);
    }

    @Test public void differentAccountsAndAnonymousHistoryRemainSeparate() throws Exception {
        AiConversationHistory store = new AiConversationHistory(folder.newFolder());
        store.save(0, AiConversationHistory.encode(transcript("匿名")));
        store.save(12, AiConversationHistory.encode(transcript("账号")));
        assertEquals("匿名", store.load(0).sessions.get(0).title);
        assertEquals("账号", store.load(12).sessions.get(0).title);
        assertTrue(store.load(13).sessions.isEmpty());
    }

    @Test public void oldPendingQuotesAreDiscardedButSentCardsAndDraftsSurvive() throws Exception {
        AiConversationHistory store = new AiConversationHistory(folder.newFolder());
        AiConversationHistory.Archive archive = transcript("修改旧卡片");
        AiConversationHistory.Session session = archive.sessions.get(0);
        session.draft = "保留第一首，其他换成女声";
        com.google.gson.JsonObject old = com.google.gson.JsonParser.parseString(AiConversationHistory.encode(archive)).getAsJsonObject();
        com.google.gson.JsonObject oldSession = old.getAsJsonArray("sessions").get(0).getAsJsonObject();
        oldSession.addProperty("attachmentDay", "2026-10-19");
        oldSession.add("attachment", oldSession.getAsJsonArray("messages").get(0).getAsJsonObject().get("playlist").deepCopy());
        store.save(1, AiConversationHistory.encode(AiConversationHistory.decode(old.toString())));
        AiConversationHistory.Session restored = store.load(1).sessions.get(0);
        assertEquals("夜行", restored.messages.get(0).playlist.name);
        assertEquals(123, restored.messages.get(0).playlist.songs.get(0).id);
        assertEquals("保留第一首，其他换成女声", restored.draft);
        com.google.gson.JsonObject saved = com.google.gson.JsonParser.parseString(
                AiConversationHistory.encode(store.load(1))).getAsJsonObject()
                .getAsJsonArray("sessions").get(0).getAsJsonObject();
        assertFalse(saved.has("attachment"));
        assertFalse(saved.has("attachmentDay"));
    }

    @Test public void interruptedWriteCannotReplaceLastCompleteSnapshot() throws Exception {
        File directory = folder.newFolder();
        AiConversationHistory store = new AiConversationHistory(directory);
        store.save(1, AiConversationHistory.encode(transcript("已保存")));
        Files.write(new File(directory, "1.tmp").toPath(), "{".getBytes(StandardCharsets.UTF_8));
        assertEquals("已保存", new AiConversationHistory(directory).load(1).sessions.get(0).title);
        try { store.save(1, "{"); fail("Must reject broken snapshot"); } catch (java.io.IOException expected) { }
        assertEquals("已保存", store.load(1).sessions.get(0).title);
    }

    @Test public void deletingOneConversationPreservesOthersAndPersistsAcrossRestart() throws Exception {
        AiConversationHistory store = new AiConversationHistory(folder.newFolder());
        AiConversationHistory.Archive archive = transcript("第一段");
        AiConversationHistory.Session second = transcript("第二段").sessions.get(0);
        second.id = "session-2"; archive.sessions.add(second);
        store.save(1, AiConversationHistory.encode(archive));
        archive = store.load(1); archive.sessions.remove(0);
        store.save(1, AiConversationHistory.encode(archive));
        assertEquals(1, store.load(1).sessions.size());
        assertEquals("第二段", store.load(1).sessions.get(0).title);
    }

    @Test public void interruptedGenerationAndOldSchemaWithoutOptionalSuggestionsAreReadable() throws Exception {
        AiConversationHistory.Archive archive = transcript("上次生成");
        archive.sessions.get(0).messages.get(0).interrupted = true;
        assertTrue(AiConversationHistory.decode(AiConversationHistory.encode(archive)).sessions.get(0).messages.get(0).interrupted);
        AiConversationHistory.Archive old = AiConversationHistory.decode(
            "{\"version\":1,\"sessions\":[{\"id\":\"old\",\"title\":\"旧记录\",\"messages\":[{\"id\":1,\"user\":true,\"text\":\"hi\"}]}]}");
        assertTrue(old.sessions.get(0).suggestions.isEmpty());
        assertTrue(old.sessions.get(0).messages.get(0).sentences.isEmpty());
    }
}
