package dev.t1m3.qplayer.ai;

import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class AiSongMatcherTest {
    @Test
    public void overlapsFourLookupsButKeepsCuratedOrderAndStopsAtTarget() throws Exception {
        CountDownLatch firstWave = new CountDownLatch(4);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), calls = new AtomicInteger();
        try (AiSongMatcher matcher = new AiSongMatcher((title, artist) -> {
            calls.incrementAndGet();
            int concurrent = active.incrementAndGet();
            peak.updateAndGet(previous -> Math.max(previous, concurrent));
            try {
                firstWave.countDown();
                assertTrue("Matching must overlap network waits", firstWave.await(5, TimeUnit.SECONDS));
                return song(Long.parseLong(title));
            } finally { active.decrementAndGet(); }
        })) {
            List<Integer> progress = new ArrayList<>();
            List<NeteaseSong> result = matcher.match(rows(1, 12), 8, Collections.emptySet(),
                    (matched, target) -> { assertEquals(8, target); progress.add(matched); }, () -> { });
            assertEquals(Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), ids(result));
            assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8), progress);
            assertEquals(4, peak.get());
            assertEquals("No extra searches once the target is covered", 8, calls.get());
            assertEquals("原因8", result.get(7).recommendReason);
        }
    }

    @Test
    public void deduplicatesPairsAndResolvedIdsAndExcludesOtherGroupsWithoutLosingReasons() throws Exception {
        NeteaseSong shared = song(1);
        try (AiSongMatcher matcher = new AiSongMatcher((title, artist) -> {
            if (title.equals("不可用")) throw new IOException("song unavailable");
            return title.equals("别名") || title.equals("1") ? shared : song(Long.parseLong(title));
        })) {
            List<AiPlaylistResult.Song> input = rows(1, 4);
            input.add(1, row(" 1 ", "作者", "重复"));
            input.add(2, row("别名", "作者", "别名原因"));
            input.add(3, row("不可用", "作者", "缺失"));
            List<NeteaseSong> result = matcher.match(input, 3, Collections.singleton(2L), (n, t) -> { }, () -> { });
            assertEquals(Arrays.asList(1L, 3L, 4L), ids(result));
            assertEquals("原因1", result.get(0).recommendReason);
            assertEquals("", shared.recommendReason);
            assertNotSame(shared, result.get(0));
        }
    }

    @Test
    public void familiarMetadataAndSuccessfulLookupsAreReusedWithIndependentReasons() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        try (AiSongMatcher matcher = new AiSongMatcher((title, artist) -> {
            searches.incrementAndGet(); return song(Long.parseLong(title));
        })) {
            NeteaseSong favorite = song(1); favorite.recommendReason = "旧原因";
            matcher.remember(Collections.singleton(favorite));
            List<NeteaseSong> first = matcher.match(rows(1, 2), 2, Collections.emptySet(), (n, t) -> { }, () -> { });
            first.get(0).name = "修改显示内容";
            List<AiPlaylistResult.Song> secondRows = rows(1, 2);
            secondRows.get(0).reason = "新的推荐原因";
            List<NeteaseSong> second = matcher.match(secondRows, 2, Collections.emptySet(), (n, t) -> { }, () -> { });
            assertEquals("1", second.get(0).name);
            assertEquals("新的推荐原因", second.get(0).recommendReason);
            assertEquals("原因1", first.get(0).recommendReason);
            assertEquals("旧原因", favorite.recommendReason);
            assertEquals(1, searches.get());
        }
    }

    @Test
    public void networkFailuresRemainExplicitAndAreNotCached() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        try (AiSongMatcher matcher = new AiSongMatcher((title, artist) -> {
            if (searches.incrementAndGet() == 1) throw new UnknownHostException("offline");
            return song(1);
        })) {
            try {
                matcher.match(rows(1, 1), 1, Collections.emptySet(), (n, t) -> { }, () -> { });
                fail("A transport outage must not become an empty successful batch");
            } catch (UnknownHostException expected) { assertEquals("offline", expected.getMessage()); }
            assertEquals(Collections.singletonList(1L), ids(matcher.match(rows(1, 1), 1,
                    Collections.emptySet(), (n, t) -> { }, () -> { })));
            assertEquals(2, searches.get());
        }
    }

    @Test
    public void cancellingTheCallerCancelsAllPendingLookups() throws Exception {
        CountDownLatch entered = new CountDownLatch(4), interrupted = new CountDownLatch(4);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (AiSongMatcher matcher = new AiSongMatcher((title, artist) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return null; }
            catch (InterruptedException expected) { interrupted.countDown(); throw expected; }
        })) {
            Future<?> request = caller.submit(() -> {
                try { matcher.match(rows(1, 20), 20, Collections.emptySet(), (n, t) -> { }, () -> { }); }
                catch (Exception expected) { assertTrue(expected instanceof InterruptedException); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            request.cancel(true);
            assertTrue("Every outstanding lookup must be cancelled", interrupted.await(5, TimeUnit.SECONDS));
        } finally { caller.shutdownNow(); }
    }

    @Test
    public void fewerThanFourNeededSongsDoNotTriggerFourSearches() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        try (AiSongMatcher matcher = new AiSongMatcher((title, artist) -> {
            searches.incrementAndGet(); return song(Long.parseLong(title));
        })) {
            assertEquals(Arrays.asList(1L, 2L), ids(matcher.match(rows(1, 30), 2,
                    Collections.emptySet(), (n, t) -> { }, () -> { })));
            assertEquals(2, searches.get());
        }
    }

    private static List<AiPlaylistResult.Song> rows(int start, int end) {
        List<AiPlaylistResult.Song> rows = new ArrayList<>();
        for (int id = start; id <= end; id++) rows.add(row(Integer.toString(id), "作者", "原因" + id));
        return rows;
    }
    private static AiPlaylistResult.Song row(String title, String artist, String reason) {
        AiPlaylistResult.Song row = new AiPlaylistResult.Song(); row.title = title; row.artist = artist; row.reason = reason;
        return row;
    }
    private static NeteaseSong song(long id) {
        NeteaseSong song = new NeteaseSong(); song.id = id; song.name = Long.toString(id); song.artist = "作者"; return song;
    }
    private static List<Long> ids(List<NeteaseSong> songs) {
        List<Long> ids = new ArrayList<>(); for (NeteaseSong song : songs) ids.add(song.id); return ids;
    }
}
