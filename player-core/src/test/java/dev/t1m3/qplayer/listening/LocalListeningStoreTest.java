package dev.t1m3.qplayer.listening;

import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseListening;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LocalListeningStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path folder() throws Exception { return temp.newFolder().toPath(); }
    private static long at(String time) { return LocalDateTime.parse(time).atZone(LocalListeningStore.ZONE).toInstant().toEpochMilli(); }
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    private static Track song(long id) {
        Track track = new Track(); track.source = Track.Source.NETEASE; track.neteaseId = id;
        track.title = "Song " + id; track.artist = "Artist"; track.durationMs = 240000; return track;
    }
    @Test public void cumulativeCheckpointsPauseSeekAndFinalizationCountOnePlay() throws Exception {
        LocalListeningStore store = new LocalListeningStore(folder()); Track track = song(1);
        long start = at("2026-10-18T12:00:00");
        store.checkpoint("same", track, start, start + 5000, 5000);
        store.checkpoint("same", track, start, start + 600000, 5000); // paused ten minutes, no new audio
        store.checkpoint("same", track, start, start + 605000, 10000); // seek-relative position never enters store
        store.checkpoint("same", track, start, start + 605000, 10000);
        LocalListeningStore.Snapshot snapshot = store.snapshot("month", 0, TODAY);
        assertEquals(10, snapshot.totalSeconds); assertEquals(1, snapshot.report.songs.get(0).count);
        assertEquals(1, snapshot.recent.size()); assertEquals(start, snapshot.recent.get(0).playedAt);
    }
    @Test public void replayIsAnotherPlayAndMillisecondsAreNotRoundedPerCheckpoint() throws Exception {
        LocalListeningStore store = new LocalListeningStore(folder()); long start = at("2026-10-18T12:00:00");
        store.checkpoint("first", song(1), start, start + 650, 650);
        store.checkpoint("first", song(1), start, start + 1300, 1300);
        store.checkpoint("second", song(1), start + 2000, start + 2700, 700);
        LocalListeningStore.Snapshot value = store.snapshot("month", 0, TODAY);
        assertEquals(2, value.totalSeconds); assertEquals(2, value.report.songs.get(0).count); assertEquals(2, value.recent.size());
    }
    @Test public void restartRetainsTimeCountsRecentHistoryAndCheckpointIdempotency() throws Exception {
        Path path = folder(); long start = at("2026-10-18T12:00:00");
        new LocalListeningStore(path).checkpoint("same", song(42), start, start + 12345, 12345);
        LocalListeningStore reopened = new LocalListeningStore(path);
        reopened.checkpoint("same", song(42), start, start + 12345, 12345);
        LocalListeningStore.Snapshot value = reopened.snapshot("year", 0, TODAY);
        assertEquals(12, value.totalSeconds); assertEquals(12, value.report.seconds); assertEquals(1, value.report.songs.get(0).count);
        assertEquals(42, value.recent.get(0).song.id); assertEquals("2026-01-01", value.report.startDate);
    }
    @Test public void midnightAndMonthBoundarySplitHeardTimeWithoutDuplicatingPlay() throws Exception {
        Path path = folder(); LocalListeningStore store = new LocalListeningStore(path);
        long start = at("2026-09-30T23:59:57");
        store.checkpoint("night", song(1), start, start + 8000, 8000);
        LocalListeningStore.Snapshot value = new LocalListeningStore(path).snapshot("month", 0, TODAY);
        assertEquals(8, value.totalSeconds); assertEquals(5, value.report.seconds); assertEquals(3, value.previous.seconds);
        assertEquals(1, value.report.songs.size());
        assertEquals(1, value.report.songs.get(0).count + value.previous.songs.get(0).count);
        assertEquals(1, value.recent.size());
    }
    @Test public void allSourcesHaveDistinctStableIdentitiesAndCanBeReplayed() throws Exception {
        LocalListeningStore store = new LocalListeningStore(folder()); long now = at("2026-10-18T12:00:00");
        List<Track> tracks = new ArrayList<>(); tracks.add(song(1));
        Track local = song(0); local.source = Track.Source.LOCAL; local.contentUri = "content://audio/7"; tracks.add(local);
        Track bili = song(0); bili.source = Track.Source.BILI; bili.biliBvid = "BV-test"; bili.biliCid = 7; tracks.add(bili);
        Track custom = song(0); custom.source = Track.Source.CUSTOM_API; custom.customId = "7"; tracks.add(custom);
        for (Track track : tracks) store.checkpoint(LocalListeningStore.key(track), track, now, now + 2000, 2000);
        LocalListeningStore.Snapshot value = store.snapshot("month", 0, TODAY);
        assertEquals(8, value.totalSeconds); assertEquals(4, value.report.songs.size());
        Set<Long> ids = new HashSet<>(); for (NeteaseListening.SongPlay row : value.report.songs) { ids.add(row.song.id); assertNotNull(row.localTrack); }
        assertEquals(4, ids.size());
    }
    @Test public void recentLimitDoesNotTruncateLifetimeTotalsOrRankings() throws Exception {
        LocalListeningStore store = new LocalListeningStore(folder()); long now = at("2026-10-18T12:00:00");
        for (int i = 0; i < 305; i++) store.checkpoint("play" + i, song(1), now + i * 1000, now + (i + 1) * 1000, 1000);
        LocalListeningStore.Snapshot value = store.snapshot("month", 0, TODAY);
        assertEquals(305, value.totalSeconds); assertEquals(305, value.report.songs.get(0).count); assertEquals(300, value.recent.size());
        assertEquals(now + 304000, value.recent.get(0).playedAt);
    }
    @Test public void genresAreWeightedByHeardTimeAndAiCannotChangeMeasuredNumbers() throws Exception {
        Path path = folder(); LocalListeningStore store = new LocalListeningStore(path); long now = at("2026-10-18T12:00:00");
        Track first = song(1), second = song(2); first.genre = "摇滚";
        store.checkpoint("a", first, now, now + 3000, 3000); store.checkpoint("b", second, now, now + 1000, 1000);
        List<LocalListeningStore.GenreSample> samples = store.genreSamples("month", 0, TODAY, now);
        assertEquals(1, samples.size()); assertEquals("netease:2", samples.get(0).key);
        assertTrue(store.genreSamples("month", 0, TODAY, now + 1).isEmpty());
        Map<String,String> labels = new HashMap<>(); labels.put("netease:2", "流行"); labels.put("invented", "伪造");
        store.saveGenres(labels, Collections.singleton("netease:2"));
        LocalListeningStore.Snapshot value = new LocalListeningStore(path).snapshot("month", 0, TODAY);
        assertEquals(4, value.totalSeconds); assertEquals(2, value.report.songs.size()); assertEquals(2, value.report.genres.size());
        assertEquals("摇滚", value.report.genres.get(0).name); assertEquals(75d, value.report.genres.get(0).percent, .001);
        assertTrue(new LocalListeningStore(path).genreSamples("month", 0, TODAY, now + 1000000).isEmpty());
    }
    @Test public void multipleCreditedArtistsAreAggregatedLocally() throws Exception {
        LocalListeningStore store = new LocalListeningStore(folder()); long now = at("2026-10-18T12:00:00");
        Track track = song(1); track.artistNamesCsv = "A\u0001B\u0001A";
        store.checkpoint("one", track, now, now + 2000, 2000);
        LocalListeningStore.Snapshot value = store.snapshot("month", 0, TODAY);
        assertEquals(2, value.report.artists.size()); assertEquals(1, value.report.artists.get(0).count);
    }
    @Test public void metadataDoesNotPersistCredentialsAudioOrExpiringPlaybackUrls() throws Exception {
        Path path = folder(); LocalListeningStore store = new LocalListeningStore(path); Track track = song(1);
        track.streamUrl = "https://cdn.invalid/secret-token"; track.coverBytes = new byte[]{1,2,3}; track.embeddedLyricText = "large lyric payload";
        long now = at("2026-10-18T12:00:00"); store.checkpoint("one", track, now, now + 1000, 1000);
        String json = new String(Files.readAllBytes(path.resolve("2026-10.json")), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(json.contains("secret-token")); assertFalse(json.contains("large lyric payload")); assertFalse(json.contains("coverBytes"));
        assertNull(store.snapshot("month", 0, TODAY).recent.get(0).localTrack.streamUrl);
    }
    @Test public void emptyOfflineLedgerHasAllCalendarDaysButNoFabricatedMusic() throws Exception {
        LocalListeningStore.Snapshot value = new LocalListeningStore(folder()).snapshot("month", 0, TODAY);
        assertEquals(0, value.totalSeconds); assertEquals(18, value.report.days.size()); assertTrue(value.report.songs.isEmpty());
        assertTrue(value.report.artists.isEmpty()); assertTrue(value.report.genres.isEmpty()); assertTrue(value.recent.isEmpty());
    }
}
