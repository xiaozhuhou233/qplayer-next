package dev.t1m3.qplayer.ai;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import org.junit.Test;
import static org.junit.Assert.*;

public class TimeVibePeriodTest {
    private TimeVibePeriod at(String date) { return TimeVibePeriod.at(LocalDateTime.parse(date)); }
    @Test public void exactBoundariesAndMidnightDoNotGenerateExtraPeriods() {
        assertEquals("2026-10-20:22", at("2026-10-21T09:59:59").key);
        assertEquals("2026-10-21:10", at("2026-10-21T10:00:00").key);
        assertEquals("2026-10-21:10", at("2026-10-21T13:59:59").key);
        assertEquals("2026-10-21:14", at("2026-10-21T14:00:00").key);
        assertEquals("2026-10-21:18", at("2026-10-21T18:00:00").key);
        assertEquals("2026-10-21:22", at("2026-10-21T22:00:00").key);
        assertEquals(at("2026-10-21T22:01:00").key, at("2026-10-22T09:59:00").key);
    }
    @Test public void timedGroupHasExactlyTenResolvedTracksAndSeparateCacheIdentity() throws Exception {
        TimeVibePeriod period = at("2026-10-21T15:00:00");
        DailyVibeCuration.Playlist list = DailyVibeCuration.generateTimed(period.prompt(), "历史与收藏",
                (request, references, count) -> {
                    assertEquals(10, count); assertTrue(request.contains("14:00–18:00"));
                    assertEquals("历史与收藏", references);
                    AiPlaylistResult result = new AiPlaylistResult(); result.playlistName = "午后";
                    for (int id = 1; id <= 15; id++) {
                        AiPlaylistResult.Song song = new AiPlaylistResult.Song(); song.title = "" + id; song.artist = "作者";
                        result.songs.add(song);
                    } return result;
                }, (title, artist) -> {
                    NeteaseSong song = new NeteaseSong(); song.id = Long.parseLong(title); song.name = title; return song;
                }, (matched, target) -> assertEquals(10, target));
        assertEquals(10, list.songs.size()); assertEquals(3, list.slot);
        DailyVibeCuration.Catalog cache = new DailyVibeCuration.Catalog();
        cache.day = period.key; cache.owner = "owner"; cache.playlists.add(list);
        String json = DailyVibeCuration.encode(cache);
        assertEquals(1, DailyVibeCuration.restore(json, period.key, "owner").playlists.size());
        assertEquals(0, DailyVibeCuration.restore(json, "2026-10-21:18", "owner").playlists.size());
        assertEquals(0, DailyVibeCuration.restore(json, period.key, "other-account").playlists.size());
        assertFalse(DailyVibeCuration.isComplete(cache));
    }
    @Test public void dailySearchUsesDatesAndNeverInventsLocationOrWeather() {
        LocalDate date = LocalDate.of(2026, 10, 21);
        assertEquals(2, DailyMusicContext.queries(date, "", Collections.emptyList()).size());
        java.util.List<String> queries = DailyMusicContext.queries(date, "杭州", Arrays.asList("Coldplay", "周杰伦"));
        assertEquals(2, queries.size()); assertFalse(queries.get(0).contains("天气"));
        assertTrue(queries.get(0).contains("site:news.cn")); assertTrue(queries.get(1).contains("site:grammy.com"));
        assertTrue(queries.get(0).contains("周杰伦")); assertFalse(queries.get(0).contains("Coldplay"));
        assertTrue(queries.get(1).contains("Coldplay")); assertFalse(queries.get(1).contains("周杰伦"));
        assertFalse(queries.get(0).contains("演出 获奖"));
        assertTrue(queries.get(0).contains("after:2026-10-14")); assertTrue(queries.get(0).contains("before:2026-10-22"));
        String missing = DailyMusicContext.instructions(date, "", "");
        assertTrue(missing.contains("不推测当地天气")); assertTrue(missing.contains("未获得联网资料"));
    }
}
