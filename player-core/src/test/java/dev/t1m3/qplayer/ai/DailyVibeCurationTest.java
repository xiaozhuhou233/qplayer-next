package dev.t1m3.qplayer.ai;

import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class DailyVibeCurationTest {
    @Test
    public void resolvesTwentyToThirtyRealSongsInCuratedOrderAndPreservesReasons() throws Exception {
        AiPlaylistResult result = recommendations(4, 40);
        result.songs.addAll(0, recommendations("3", "1", "3", "未找到", "2").songs);
        List<NeteaseSong> searchRows = new ArrayList<>();
        List<Integer> progress = new ArrayList<>();
        DailyVibeCuration.Playlist playlist = DailyVibeCuration.generate(0, "历史与收藏样本", Collections.emptyList(),
                (request, references, count) -> {
                    assertTrue(request.contains(DailyVibeCuration.PERSONA));
                    assertTrue(request.contains("把相近曲风放在同一歌单"));
                    assertTrue(request.contains("按听感连贯排序"));
                    assertFalse("Use AI DJ's shared output contract", request.contains("songs"));
                    assertTrue(count >= 20 && count <= 30);
                    assertEquals("历史与收藏样本", references);
                    return result;
                }, (title, artist) -> {
                    if (title.equals("未找到")) return null;
                    NeteaseSong song = song(Long.parseLong(title)); searchRows.add(song); return song;
                }, (matched, target) -> {
                    assertTrue(target >= 20 && target <= 30);
                    progress.add(matched);
                });
        assertEquals(Arrays.asList(3L, 1L, 2L), ids(playlist).subList(0, 3));
        assertTrue(playlist.songs.size() >= 20 && playlist.songs.size() <= 30);
        assertEquals("原因：3", playlist.songs.get(0).recommendReason);
        assertEquals("策展歌单", playlist.name);
        assertEquals("连贯风格", playlist.summary);
        assertEquals(playlist.songs.size(), searchRows.size());
        assertEquals("", searchRows.get(0).recommendReason);
        assertNotSame(searchRows.get(0), playlist.songs.get(0));
        assertEquals(Integer.valueOf(0), progress.get(0));
        assertEquals(Integer.valueOf(playlist.songs.size()), progress.get(progress.size() - 1));
        assertEquals(playlist.songs.size() + 1, progress.size());
    }

    @Test
    public void nextGroupHasAnotherAngleAndExcludesAlreadyResolvedIds() throws Exception {
        DailyVibeCuration.Playlist existing = playlist(0, 1, 20);
        existing.name = "轻柔民谣"; existing.summary = "木吉他与温暖人声";
        DailyVibeCuration.Playlist next = DailyVibeCuration.generate(1, "喜欢的音乐人", Collections.singletonList(existing),
                (request, references, count) -> {
                    assertTrue(request.contains("不同作者"));
                    assertTrue(request.contains("轻柔民谣"));
                    assertTrue(request.contains("木吉他与温暖人声"));
                    assertTrue(request.contains("避免与已有歌单重复歌曲和主题"));
                    AiPlaylistResult result = recommendations(22, 60);
                    result.songs.addAll(0, recommendations("1", "21", "同一首的别名").songs);
                    return result;
                }, (title, artist) -> song(title.equals("同一首的别名") ? 21 : Long.parseLong(title)));
        assertEquals(Arrays.asList(21L, 22L), ids(next).subList(0, 2));
        assertEquals(next.songs.size(), new HashSet<>(ids(next)).size());
        assertTrue(Collections.disjoint(ids(existing), ids(next)));
        assertTrue(next.songs.size() >= 20 && next.songs.size() <= 30);
    }

    @Test
    public void missingMatchesAreFilledInTheSameStyleBeforePublishing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DailyVibeCuration.Playlist playlist = DailyVibeCuration.generate(0, "收藏和历史", Collections.emptyList(),
                (request, references, count) -> {
                    if (calls.incrementAndGet() == 1) return recommendations(1, 19);
                    assertTrue(request.contains("继续同一歌单「策展歌单」"));
                    assertTrue(request.contains("连贯风格"));
                    assertTrue(request.contains("已尝试歌曲"));
                    assertEquals("收藏和历史", references);
                    AiPlaylistResult result = recommendations(20, 50);
                    result.playlistName = "补充响应不应改名";
                    return result;
                }, (title, artist) -> song(Long.parseLong(title)));
        assertEquals(2, calls.get());
        assertTrue(playlist.songs.size() >= 20 && playlist.songs.size() <= 30);
        assertEquals("策展歌单", playlist.name);
        assertEquals(Long.valueOf(20L), ids(playlist).get(19));
    }

    @Test
    public void underTwentyMatchesRemainHiddenAndDoNotRetryForever() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try {
            DailyVibeCuration.generate(2, "", Collections.emptyList(),
                    (request, references, count) -> { calls.incrementAndGet(); return recommendations(1, 19); },
                    (title, artist) -> song(Long.parseLong(title)));
            fail("Must reject fewer than twenty matched songs");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("20 首网易云"));
        }
        assertEquals(3, calls.get());
    }

    @Test
    public void transientAndFormatFailuresRetryWithoutLosingReferencesOrPlaylistMetadata() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DailyVibeCuration.Playlist playlist = DailyVibeCuration.generate(0, "收藏与历史", Collections.emptyList(),
                (request, references, count) -> {
                    assertEquals("收藏与历史", references);
                    assertFalse("A failed answer is not a partially matched playlist", request.contains("继续同一歌单"));
                    int call = calls.incrementAndGet();
                    if (call == 1) throw new java.net.SocketException("Software caused connection abort");
                    if (call == 2) throw new AiClient.ResponseFormatException("empty songs");
                    return recommendations(1, 30);
                }, (title, artist) -> song(Long.parseLong(title)));
        assertEquals(3, calls.get());
        assertEquals("策展歌单", playlist.name);
        assertEquals("连贯风格", playlist.summary);
        assertTrue(playlist.songs.size() >= 20 && playlist.songs.size() <= 30);
    }

    @Test
    public void persistentDnsFailureStopsAfterThreeAttemptsAndPreservesItsCause() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try {
            DailyVibeCuration.generate(0, "", Collections.emptyList(),
                    (request, references, count) -> {
                        calls.incrementAndGet(); throw new java.net.UnknownHostException("api.deepseek.com");
                    }, (title, artist) -> { fail("No songs to resolve"); return null; });
            fail("DNS failure must remain explicit");
        } catch (java.net.UnknownHostException expected) {
            assertEquals("api.deepseek.com", expected.getMessage());
        }
        assertEquals(3, calls.get());
    }

    @Test
    public void permanentServiceErrorsAreNotRetriedAsEmptyPlaylists() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try {
            DailyVibeCuration.generate(0, "", Collections.emptyList(),
                    (request, references, count) -> {
                        calls.incrementAndGet(); throw new IOException("HTTP 401 invalid API key");
                    }, (title, artist) -> null);
            fail("Authentication failure must remain explicit");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("401")); }
        assertEquals(1, calls.get());
    }

    @Test
    public void matchingNetworkFailureIsNotSilentlyDiscardedForEverySong() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        try {
            DailyVibeCuration.generate(0, "", Collections.emptyList(),
                    (request, references, count) -> recommendations(1, 30),
                    (title, artist) -> { searches.incrementAndGet(); throw new java.net.UnknownHostException("music.163.com"); });
            fail("Matching outage must remain explicit");
        } catch (java.net.UnknownHostException expected) {
            assertEquals("music.163.com", expected.getMessage());
        }
        assertEquals(1, searches.get());
    }

    @Test
    public void cacheIsDailyAndAccountScopedAndOnlyACompleteDayCanBePublished() {
        DailyVibeCuration.Catalog catalog = new DailyVibeCuration.Catalog();
        catalog.day = "2026-10-11"; catalog.owner = "account-config-hash";
        catalog.playlists.add(playlist(0, 1, 10));
        catalog.playlists.add(playlist(0, 1, 20));
        catalog.playlists.add(playlist(0, 21, 40));
        catalog.playlists.add(playlist(2, 41, 60));
        String json = DailyVibeCuration.encode(catalog);
        DailyVibeCuration.Catalog restored = DailyVibeCuration.restore(json, catalog.day, catalog.owner);
        assertEquals(2, restored.playlists.size());
        assertEquals(Long.valueOf(1L), ids(restored.playlists.get(0)).get(0));
        assertFalse(DailyVibeCuration.isComplete(restored));
        restored.playlists.add(playlist(1, 21, 40));
        assertTrue(DailyVibeCuration.isComplete(restored));
        assertTrue(DailyVibeCuration.restore(json, "2026-10-12", catalog.owner).playlists.isEmpty());
        assertTrue(DailyVibeCuration.restore(json, catalog.day, "another-account").playlists.isEmpty());
        assertTrue(DailyVibeCuration.restore("{broken", catalog.day, catalog.owner).playlists.isEmpty());
    }

    @Test
    public void cacheRejectsOversizedAndRepeatedSongGroups() {
        DailyVibeCuration.Catalog catalog = new DailyVibeCuration.Catalog();
        catalog.day = "day"; catalog.owner = "owner";
        catalog.playlists.add(playlist(0, 1, 31));
        DailyVibeCuration.Playlist repeated = playlist(1, 1, 19);
        repeated.songs.add(song(1)); catalog.playlists.add(repeated);
        assertTrue(DailyVibeCuration.restore(DailyVibeCuration.encode(catalog), "day", "owner").playlists.isEmpty());
        assertFalse(DailyVibeCuration.isComplete(catalog));
    }

    @Test
    public void revisionReplacesOnlySelectedGroupAndCanKeepItsOriginalSongs() throws Exception {
        DailyVibeCuration.Catalog original = completeCatalog();
        original.playlists.get(1).name = "原来的轻柔歌单";
        original.playlists.get(1).summary = "原来的选曲方向";
        String before = DailyVibeCuration.encode(original);
        DailyVibeCuration.Catalog updated = DailyVibeCuration.revise(original, 1, "多一些女声", "历史和收藏",
                (request, references, count) -> {
                    assertTrue(request.contains("原来的轻柔歌单"));
                    assertTrue(request.contains("原来的选曲方向"));
                    assertTrue(request.contains("曲目21 — 作者"));
                    assertTrue(request.contains("用户修改要求（优先执行）：多一些女声"));
                    assertEquals("历史和收藏", references);
                    AiPlaylistResult result = recommendations(61, 100);
                    result.songs.addAll(0, recommendations("1", "21", "41").songs);
                    result.playlistName = "新的女声歌单";
                    return result;
                }, (title, artist) -> song(Long.parseLong(title)), (matched, target) -> { });
        assertNotSame(original, updated);
        assertEquals(original.day, updated.day);
        assertEquals(original.owner, updated.owner);
        assertSame(original.playlists.get(0), updated.playlists.get(0));
        assertSame(original.playlists.get(2), updated.playlists.get(2));
        DailyVibeCuration.Playlist replacement = updated.playlists.get(1);
        assertEquals(1, replacement.slot);
        assertEquals("新的女声歌单", replacement.name);
        assertEquals(Long.valueOf(21), ids(replacement).get(0));
        assertEquals("原因：21", replacement.songs.get(0).recommendReason);
        assertTrue(Collections.disjoint(ids(original.playlists.get(0)), ids(replacement)));
        assertTrue(Collections.disjoint(ids(original.playlists.get(2)), ids(replacement)));
        assertEquals(before, DailyVibeCuration.encode(original));
        assertTrue(DailyVibeCuration.isComplete(updated));
    }

    @Test
    public void revisionFailureLeavesAllPublishedSongsAndReasonsUntouched() throws Exception {
        DailyVibeCuration.Catalog original = completeCatalog();
        String before = DailyVibeCuration.encode(original);
        AtomicInteger calls = new AtomicInteger();
        try {
            DailyVibeCuration.revise(original, 1, "换成钢琴曲", "收藏",
                    (request, references, count) -> { calls.incrementAndGet(); return recommendations(61, 79); },
                    (title, artist) -> song(Long.parseLong(title)), (matched, target) -> { });
            fail("An incomplete revision must not replace the published group");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("20 首网易云")); }
        assertEquals(3, calls.get());
        assertEquals(before, DailyVibeCuration.encode(original));
        assertTrue(DailyVibeCuration.isComplete(original));
    }

    @Test
    public void revisionKeepsUserRequirementsWhenFillingMissingMatches() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DailyVibeCuration.Catalog updated = DailyVibeCuration.revise(completeCatalog(), 0, "只选器乐", "历史",
                (request, references, count) -> {
                    assertTrue(request.contains("用户修改要求（优先执行）：只选器乐"));
                    assertTrue(request.contains("曲目1 — 作者"));
                    if (calls.incrementAndGet() == 1) return recommendations(61, 79);
                    assertTrue(request.contains("继续同一歌单"));
                    return recommendations(80, 110);
                }, (title, artist) -> song(Long.parseLong(title)), (matched, target) -> { });
        assertEquals(2, calls.get());
        assertTrue(DailyVibeCuration.isComplete(updated));
        assertEquals(Long.valueOf(80), ids(updated.playlists.get(0)).get(19));
    }

    @Test
    public void invalidRevisionNeverCallsTheRecommender() throws Exception {
        for (int slot : new int[] { -1, 0, 3 }) {
            try {
                DailyVibeCuration.revise(completeCatalog(), slot, slot == 0 ? "  " : "换曲风", "",
                        (request, references, count) -> { fail("Invalid edit must not call AI"); return null; },
                        (title, artist) -> null, (matched, target) -> { });
                fail("Invalid edit must be rejected");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test
    public void concurrentBatchResolverStillHonorsGroupExclusionsProgressAndSongLimits() throws Exception {
        DailyVibeCuration.Playlist existing = playlist(0, 1, 20);
        List<Integer> progress = new ArrayList<>();
        try (AiSongMatcher matcher = new AiSongMatcher((title, artist) -> song(Long.parseLong(title)))) {
            DailyVibeCuration.Resolver resolver = new DailyVibeCuration.Resolver() {
                @Override public NeteaseSong resolve(String title, String artist) {
                    fail("Curation should use the batch adapter"); return null;
                }
                @Override public List<NeteaseSong> resolveBatch(List<AiPlaylistResult.Song> rows, int target,
                        java.util.Set<Long> excluded, DailyVibeCuration.Progress callback) throws Exception {
                    return matcher.match(rows, target, excluded, callback, () -> { });
                }
            };
            DailyVibeCuration.Playlist generated = DailyVibeCuration.generate(1, "收藏与历史",
                    Collections.singletonList(existing), (request, references, count) -> recommendations(1, 60),
                    resolver, (matched, target) -> progress.add(matched));
            assertTrue(generated.songs.size() >= 20 && generated.songs.size() <= 30);
            assertTrue(Collections.disjoint(ids(existing), ids(generated)));
            assertEquals(Long.valueOf(21), ids(generated).get(0));
            assertEquals("原因：21", generated.songs.get(0).recommendReason);
            assertEquals(Integer.valueOf(generated.songs.size()), progress.get(progress.size() - 1));
            assertEquals(generated.songs.size() + 1, progress.size());
        }
    }

    @Test
    public void alreadyPublishedSingleGroupCanBeRestoredAndEditedBeforeOtherGroupsExist() throws Exception {
        DailyVibeCuration.Catalog partial = completeCatalog();
        partial.playlists.remove(2);
        partial.playlists.remove(0);
        partial = DailyVibeCuration.restore(DailyVibeCuration.encode(partial), partial.day, partial.owner);
        assertEquals(1, partial.playlists.size());
        assertFalse(DailyVibeCuration.isComplete(partial));
        String before = DailyVibeCuration.encode(partial);
        DailyVibeCuration.Catalog revised = DailyVibeCuration.revise(partial, 1, "更轻柔一些", "历史与收藏",
                (request, references, count) -> recommendations(61, 100),
                (title, artist) -> song(Long.parseLong(title)), (matched, target) -> { });
        assertEquals(1, revised.playlists.size());
        assertEquals(1, revised.playlists.get(0).slot);
        assertEquals(partial.day, revised.day);
        assertEquals(partial.owner, revised.owner);
        assertFalse(DailyVibeCuration.isComplete(revised));
        assertEquals(before, DailyVibeCuration.encode(partial));
        DailyVibeCuration.Catalog restored = DailyVibeCuration.restore(
                DailyVibeCuration.encode(revised), revised.day, revised.owner);
        assertEquals(ids(revised.playlists.get(0)), ids(restored.playlists.get(0)));
        assertTrue(restored.playlists.get(0).songs.size() >= 20);
    }

    private static DailyVibeCuration.Catalog completeCatalog() {
        DailyVibeCuration.Catalog catalog = new DailyVibeCuration.Catalog();
        catalog.day = "2026-10-11"; catalog.owner = "owner";
        for (int slot = 0; slot < 3; slot++) catalog.playlists.add(playlist(slot, slot * 20 + 1, slot * 20 + 20));
        return catalog;
    }

    private static AiPlaylistResult recommendations(int first, int last) {
        List<String> titles = new ArrayList<>();
        for (int id = first; id <= last; id++) titles.add(Integer.toString(id));
        return recommendations(titles.toArray(new String[0]));
    }
    private static AiPlaylistResult recommendations(String... titles) {
        AiPlaylistResult result = new AiPlaylistResult();
        result.playlistName = "策展歌单"; result.summary = "连贯风格";
        for (String title : titles) {
            AiPlaylistResult.Song song = new AiPlaylistResult.Song();
            song.title = title; song.artist = "作者"; song.reason = "原因：" + title;
            result.songs.add(song);
        }
        return result;
    }
    private static NeteaseSong song(long id) {
        NeteaseSong song = new NeteaseSong(); song.id = id; song.name = "曲目" + id; song.artist = "作者"; return song;
    }
    private static DailyVibeCuration.Playlist playlist(int slot, int first, int last) {
        DailyVibeCuration.Playlist playlist = new DailyVibeCuration.Playlist(); playlist.slot = slot;
        for (int id = first; id <= last; id++) playlist.songs.add(song(id));
        return playlist;
    }
    private static List<Long> ids(DailyVibeCuration.Playlist playlist) {
        List<Long> ids = new ArrayList<>(); for (NeteaseSong song : playlist.songs) ids.add(song.id); return ids;
    }
}
