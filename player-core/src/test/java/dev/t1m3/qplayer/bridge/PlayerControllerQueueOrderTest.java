package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.customapi.CustomApiConfig;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.Syllable;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertArrayEquals;

/** Queue edits change future playback order without reopening the audible source. */
public class PlayerControllerQueueOrderTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void currentSongCanMoveForwardAndBackwardWithoutRestarting() throws Exception {
        try (Fixture f = fixture(null)) {
            Track a = local("a"), b = local("b"), c = local("c"), d = local("d");
            f.enqueue(a, b, c, d);
            f.startLocal(1);

            f.controller.moveInQueue(1, 3);
            assertOrder(f.controller, a, c, d, b);
            assertCurrent(f, b, 3);

            f.controller.moveInQueue(3, 0);
            assertOrder(f.controller, b, a, c, d);
            assertCurrent(f, b, 0);
            f.assertUninterrupted("b.mp3");
        }
    }

    @Test
    public void movingOtherSongsAcrossCurrentSlotKeepsCurrentIdentity() throws Exception {
        try (Fixture f = fixture(null)) {
            Track a = local("a"), b = local("b"), c = local("c"), d = local("d");
            f.enqueue(a, b, c, d);
            f.startLocal(1);

            f.controller.moveInQueue(0, 3);
            assertOrder(f.controller, b, c, d, a);
            assertCurrent(f, b, 0);

            f.controller.moveInQueue(3, 0);
            assertOrder(f.controller, a, b, c, d);
            assertCurrent(f, b, 1);

            f.controller.moveInQueue(2, 3); // moving wholly after current does not shift it
            assertOrder(f.controller, a, b, d, c);
            assertCurrent(f, b, 1);
            f.assertUninterrupted("b.mp3");
        }
    }

    @Test
    public void duplicateSongOccurrencesRetainThePlayingSlot() throws Exception {
        try (Fixture f = fixture(null)) {
            Track repeated = local("same"), sameMetadata = local("same"), other = local("other");
            // The same Track reference can appear twice as well as a separate
            // descriptor with identical source/title. Neither is a unique song key.
            f.enqueue(repeated, repeated, sameMetadata, other);
            f.startLocal(1);

            f.controller.moveInQueue(1, 3);
            assertOrder(f.controller, repeated, sameMetadata, other, repeated);
            assertCurrent(f, repeated, 3);

            f.controller.moveInQueue(0, 3);
            assertOrder(f.controller, sameMetadata, other, repeated, repeated);
            assertCurrent(f, repeated, 2); // the earlier occurrence passed the playing one
            f.assertUninterrupted("same.mp3");
        }
    }

    @Test
    public void biliPartsSharingTitleAndVideoKeepCurrentPartWhenReordered() throws Exception {
        String queue = "{\"playIndex\":1,\"positionMs\":37000,\"playMode\":0,\"tracks\":["
                + "{\"source\":\"BILI\",\"title\":\"same title\",\"biliBvid\":\"BV1test\",\"biliCid\":11,\"durationMs\":120000},"
                + "{\"source\":\"BILI\",\"title\":\"same title\",\"biliBvid\":\"BV1test\",\"biliCid\":22,\"durationMs\":120000},"
                + "{\"source\":\"BILI\",\"title\":\"same title\",\"biliBvid\":\"BV1test\",\"biliCid\":33,\"durationMs\":120000}]}";
        try (Fixture f = fixture(queue)) {
            List<Track> original = f.controller.queueTracks.peek();
            Track first = original.get(0), current = original.get(1), last = original.get(2);
            // Restore provides the real BILI queue/index without resolving any URL.
            // Seed the backend as an already-open video; reordering must never
            // request another source or reset that video's playhead.
            f.backend.play("video-part-22", 37000L);
            f.backend.transportCalls = 0;

            f.controller.moveInQueue(1, 0);
            assertOrder(f.controller, current, first, last);
            assertCurrent(f, current, 0);
            assertEquals(22L, f.controller.currentTrack().biliCid);

            f.controller.moveInQueue(2, 0);
            assertOrder(f.controller, last, current, first);
            assertCurrent(f, current, 1);
            f.assertUninterrupted("video-part-22");
        }
    }

    @Test
    public void invalidAndUnchangedMovesLeaveQueueAndPlaybackUntouched() throws Exception {
        try (Fixture f = fixture(null)) {
            Track a = local("a"), b = local("b");
            f.enqueue(a, b);
            f.startLocal(1);
            List<Track> published = f.controller.queueTracks.peek();
            for (int[] move : new int[][] {{-1, 0}, {0, -1}, {2, 0}, {0, 2}, {1, 1},
                    {Integer.MAX_VALUE, 0}, {0, Integer.MIN_VALUE}}) {
                f.controller.moveInQueue(move[0], move[1]);
                assertSame("invalid moves do not republish a changed queue", published,
                        f.controller.queueTracks.peek());
                assertOrder(f.controller, a, b);
                assertCurrent(f, b, 1);
            }
            f.assertUninterrupted("b.mp3");
        }
    }

    @Test
    public void emptyQueueRejectsMovesWithoutTouchingBackend() throws Exception {
        try (Fixture f = fixture(null)) {
            f.controller.moveInQueue(0, 0);
            f.controller.moveInQueue(-1, 1);
            assertTrue(f.controller.queueTracks.peek().isEmpty());
            assertEquals(-1, (int) f.controller.index.peek());
            assertEquals(0, f.backend.playCalls);
            assertEquals(0, f.backend.transportCalls);
        }
    }

    @Test
    public void restoredResumePositionFollowsCurrentSongToItsNewSlot() throws Exception {
        String queue = "{\"playIndex\":1,\"positionMs\":37000,\"tracks\":["
                + "{\"source\":\"LOCAL\",\"title\":\"a\",\"filePath\":\"a.mp3\"},"
                + "{\"source\":\"LOCAL\",\"title\":\"b\",\"filePath\":\"b.mp3\"}]}";
        try (Fixture f = fixture(queue)) {
            f.controller.moveInQueue(1, 0);
            f.controller.playQueueIndex(0);
            f.controller.pump();
            assertEquals("b.mp3", f.backend.source);
            assertEquals(37000L, f.backend.position());
            assertEquals(1, f.backend.playCalls);
        }
    }

    @Test
    public void resolvedSourceCompletesAfterCurrentSongMovesToAnotherSlot() throws Exception {
        try (Fixture f = fixture(null); LocalApi api = new LocalApi()) {
            BlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
            f.controller.setMainExecutor(main::add);
            f.controller.setCustomApiConfig(api.config());
            Track pending = custom("pending"), other = local("other");
            f.enqueue(pending, other);
            f.controller.playQueueIndex(0);
            Runnable resolved = main.poll(3L, TimeUnit.SECONDS);
            assertNotNull("the real resolver must have completed", resolved);
            assertEquals(0, f.backend.playCalls);

            f.controller.moveInQueue(0, 1);
            resolved.run();
            f.controller.pump();

            assertCurrent(f, pending, 1);
            assertEquals(1, f.backend.playCalls);
            assertEquals("resolved-pending", f.backend.source);
            assertEquals("resolved-pending", pending.streamUrl);
            assertTrue(f.backend.isPlaying());
            assertEquals(false, f.controller.loading.peek());
        }
    }

    @Test
    public void lateResolvedSourceCannotReplaceANewerSelectionOfTheSameOccurrence() throws Exception {
        try (Fixture f = fixture(null); LocalApi api = new LocalApi()) {
            BlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
            f.controller.setMainExecutor(main::add);
            f.controller.setCustomApiConfig(api.config());
            Track a = custom("pending"), b = local("b");
            f.enqueue(a, b);
            f.controller.playQueueIndex(0);
            Runnable oldResolved = main.poll(3L, TimeUnit.SECONDS);
            assertNotNull(oldResolved);

            f.controller.playQueueIndex(1);
            a.streamUrl = "new-selection-of-a";
            f.controller.playQueueIndex(0); // A -> B -> the same A object and index
            f.controller.pump();
            int plays = f.backend.playCalls;
            f.backend.position = 37000L;
            oldResolved.run();
            f.controller.pump();

            assertCurrent(f, a, 0);
            assertEquals(plays, f.backend.playCalls);
            assertEquals("new-selection-of-a", f.backend.source);
            assertEquals("new-selection-of-a", a.streamUrl);
            assertEquals(37000L, f.backend.position());
        }
    }

    @Test
    public void failedResolutionSkipsTheMovedOccurrenceAtItsNewIndex() throws Exception {
        try (Fixture f = fixture(null); LocalApi api = new LocalApi(false)) {
            BlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
            f.controller.setMainExecutor(main::add);
            f.controller.setCustomApiConfig(api.config());
            Track pending = custom("pending"), other = local("other");
            f.enqueue(pending, other);
            f.controller.playQueueIndex(0);
            Runnable failed = main.poll(3L, TimeUnit.SECONDS);
            assertNotNull(failed);

            f.controller.moveInQueue(0, 1);
            failed.run();
            f.controller.pump();

            assertCurrent(f, other, 0);
            assertEquals(1, f.backend.playCalls);
            assertEquals("other.mp3", f.backend.source);
            assertEquals(false, f.controller.loading.peek());
        }
    }

    @Test
    public void pendingCoverAndLyricsPublishAfterReorderingCurrentSong() throws Exception {
        try (Fixture f = fixture(null)) {
            Track a = local("a"), b = local("b");
            f.enqueue(a, b);
            f.startLocal(0);
            a.customId = "cached-a";
            List<LyricLine> lyrics = cachedLyrics(f, "cached-a", "current lyrics");
            a.coverBytes = new byte[] {3, 4, 5};
            invoke(f.controller, "updateCover", new Class<?>[] {Track.class, int.class, long.class},
                    a, 0, revision(f.controller));
            invoke(f.controller, "loadCustomLyrics", new Class<?>[] {Track.class, int.class}, a, 0);

            f.controller.moveInQueue(0, 1);
            f.controller.pump();

            assertCurrent(f, a, 1);
            assertArrayEquals(a.coverBytes, f.controller.coverBytes.peek());
            assertSame(lyrics, f.controller.lyrics.peek());
            assertEquals(false, f.controller.lyricsLoading.peek());
            f.assertUninterrupted("a.mp3");
        }
    }

    @Test
    public void lateCoverAndLyricsCannotOverwriteANewerSelectionOfTheSameOccurrence() throws Exception {
        try (Fixture f = fixture(null)) {
            Track a = local("a"), b = local("b");
            f.enqueue(a, b);
            f.startLocal(0);
            a.customId = "cached-a";
            cachedLyrics(f, "cached-a", "obsolete lyrics");
            a.coverBytes = new byte[] {1};
            invoke(f.controller, "updateCover", new Class<?>[] {Track.class, int.class, long.class},
                    a, 0, revision(f.controller));
            Runnable oldCover = takeUiCallback(f.controller);
            invoke(f.controller, "loadCustomLyrics", new Class<?>[] {Track.class, int.class}, a, 0);
            Runnable oldLyrics = takeUiCallback(f.controller);

            f.controller.playQueueIndex(1);
            a.coverBytes = new byte[] {2};
            f.controller.playQueueIndex(0);
            f.controller.pump();
            oldCover.run();
            oldLyrics.run();

            assertCurrent(f, a, 0);
            assertArrayEquals(new byte[] {2}, f.controller.coverBytes.peek());
            assertTrue("old generation's lyrics must remain discarded", f.controller.lyrics.peek().isEmpty());
        }
    }

    @Test
    public void neteaseLyricRequestFollowsCurrentOccurrenceAcrossReordering() throws Exception {
        try (Fixture f = fixture(null)) {
            Track a = local("a"), b = local("b");
            f.enqueue(a, b);
            f.startLocal(0);
            a.neteaseId = 42L;
            LyricLine line = new LyricLine();
            line.syllables.add(new Syllable("one ", 0L, 1000L));
            line.syllables.add(new Syllable("two", 1000L, 1000L));
            List<LyricLine> timed = Arrays.asList(line);
            @SuppressWarnings("unchecked") Map<Long, List<LyricLine>> memory =
                    (Map<Long, List<LyricLine>>) field(f.controller, "lyricMem");
            memory.put(42L, timed); // full word timing prevents network refresh
            invoke(f.controller, "loadNeteaseLyrics", new Class<?>[] {Track.class, int.class}, a, 0);
            Runnable pendingLyrics = takeUiCallback(f.controller);
            f.controller.moveInQueue(0, 1);
            pendingLyrics.run();
            assertSame(timed, f.controller.lyrics.peek());
            assertCurrent(f, a, 1);

            invoke(f.controller, "loadNeteaseLyrics", new Class<?>[] {Track.class, int.class}, a, 1);
            Runnable olderSelection = takeUiCallback(f.controller);
            f.controller.playQueueIndex(0);
            f.controller.playQueueIndex(1);
            f.controller.pump();
            olderSelection.run();
            assertTrue(f.controller.lyrics.peek().isEmpty());
        }
    }

    private static Track custom(String id) {
        Track track = local(id);
        track.source = Track.Source.CUSTOM_API;
        track.customId = id;
        return track;
    }

    private static long revision(PlayerController controller) throws Exception {
        return ((AtomicLong) field(controller, "coverRevision")).get();
    }

    private static Object field(PlayerController controller, String name) throws Exception {
        Field field = PlayerController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private static void invoke(PlayerController controller, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = PlayerController.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(controller, args);
    }

    private static Runnable takeUiCallback(PlayerController controller) throws Exception {
        @SuppressWarnings("unchecked") Queue<Runnable> callbacks = (Queue<Runnable>) field(controller, "uiQueue");
        Runnable callback = callbacks.poll();
        assertNotNull("expected one deferred UI result", callback);
        return callback;
    }

    private static List<LyricLine> cachedLyrics(Fixture f, String id, String text) throws Exception {
        LyricLine line = new LyricLine();
        line.syllables.add(new Syllable(text, 0L, 1000L));
        List<LyricLine> lines = Arrays.asList(line);
        @SuppressWarnings("unchecked") Map<String, List<LyricLine>> memory =
                (Map<String, List<LyricLine>>) field(f.controller, "customLyricMem");
        memory.put(id, lines);
        return lines;
    }

    private static final class LocalApi implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        LocalApi() throws Exception { this(true); }
        LocalApi(boolean returnsUrl) throws Exception {
            server.createContext("/resolve", exchange -> {
                byte[] body = (returnsUrl ? "{\"url\":\"resolved-pending\"}" : "{}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        }
        CustomApiConfig config() {
            CustomApiConfig cfg = new CustomApiConfig();
            cfg.enabled = true;
            cfg.searchUrl = "unused-{keyword}";
            cfg.searchListPath = "list";
            cfg.idPath = "id";
            cfg.namePath = "name";
            cfg.urlUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/resolve?id={id}";
            cfg.urlResultPath = "url";
            return cfg;
        }
        @Override public void close() { server.stop(0); }
    }

    private Fixture fixture(String restoredQueue) throws Exception {
        return new Fixture(temporaryFolder.newFolder().toPath(), restoredQueue);
    }

    private static Track local(String name) {
        Track track = new Track();
        track.title = name;
        track.filePath = name + ".mp3";
        track.durationMs = 120000L;
        return track;
    }

    private static void assertOrder(PlayerController controller, Track... expected) {
        List<Track> actual = controller.queueTracks.peek();
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertSame("queue occurrence " + i, expected[i], actual.get(i));
        }
    }

    private static void assertCurrent(Fixture f, Track track, int slot) {
        assertSame(track, f.controller.currentTrack());
        assertEquals(slot, (int) f.controller.index.peek());
    }

    private static final class Fixture implements AutoCloseable {
        final String oldBase = AppDirs.base();
        final String oldCache = AppDirs.cacheBase();
        final FakeAudioBackend backend = new FakeAudioBackend();
        final PlayerController controller;

        Fixture(Path base, String queue) throws Exception {
            if (queue != null) Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            try {
                controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
                controller.pump();
            } catch (Exception | Error failure) {
                AppDirs.setBase(oldBase);
                AppDirs.setCacheBase(oldCache);
                throw failure;
            }
        }

        void enqueue(Track... tracks) {
            Arrays.stream(tracks).forEach(controller::enqueueTrack);
        }

        void startLocal(int index) {
            controller.playQueueIndex(index);
            controller.pump();
            backend.position = 37000L;
            backend.transportCalls = 0;
            assertEquals(1, backend.playCalls);
        }

        void assertUninterrupted(String source) {
            assertEquals(1, backend.playCalls);
            assertEquals(source, backend.source);
            assertEquals(37000L, backend.position());
            assertTrue(backend.isPlaying());
            assertEquals("reordering must not pause, resume or seek", 0, backend.transportCalls);
        }

        @Override public void close() {
            try { controller.shutdown(); }
            finally {
                AppDirs.setBase(oldBase);
                AppDirs.setCacheBase(oldCache);
            }
        }
    }

    private static final class FakeAudioBackend implements AudioBackend {
        String source;
        long position;
        boolean playing;
        int playCalls, transportCalls;
        Runnable onStarted;
        @Override public void play(String source, long startMs) {
            this.source = source;
            position = startMs;
            playing = true;
            playCalls++;
            if (onStarted != null) onStarted.run();
        }
        @Override public void pause() { playing = false; transportCalls++; }
        @Override public void resume() { playing = true; transportCalls++; }
        @Override public void seek(long ms) { position = ms; transportCalls++; }
        @Override public boolean isPlaying() { return playing; }
        @Override public long position() { return position; }
        @Override public long duration() { return 120000L; }
        @Override public void setVolume(float volume) { }
        @Override public void setOnComplete(Runnable callback) { }
        @Override public void setOnStarted(Runnable callback) { onStarted = callback; }
        @Override public void release() { playing = false; }
    }
}
