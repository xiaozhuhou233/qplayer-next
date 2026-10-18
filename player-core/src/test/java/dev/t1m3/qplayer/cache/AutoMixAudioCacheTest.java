package dev.t1m3.qplayer.cache;

import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AutoMixAudioCacheTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private HttpServer server;
    private DiskCache cache;
    private String url;
    private final AtomicInteger downloads = new AtomicInteger();
    private final byte[] audio = new byte[65536];
    @Before public void setup() throws Exception {
        cache = new DiskCache(8);
        cache.setBaseDir(temp.getRoot().toString());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", exchange -> {
            downloads.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, audio.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) { out.write(audio); }
        });
        server.createContext("/error", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 0); exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @After public void close() { server.stop(0); }
    @Test public void nextTrackIsPublishedToNormalPlaybackCacheAndDownloadedOnce() throws Exception {
        try (DiskCache.AudioLease first = cache.prepareAudio(url + "/audio", 17, "17", () -> true)) {
            assertEquals(cache.audioPath(17), first.path);
            assertArrayEquals(audio, Files.readAllBytes(Paths.get(cache.getAudio(17))));
            try (DiskCache.AudioLease second = cache.prepareAudio(url + "/audio", 17, "17", () -> true)) {
                assertEquals(first.path, second.path);
            }
            cache.cacheAudio(url + "/audio", 17);
            assertEquals(1, downloads.get());
        }
    }
    @Test public void cancelledDownloadDoesNotExposePartialSong() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        try {
            cache.prepareAudio(url + "/audio", 18, "18", () -> checks.incrementAndGet() < 3);
            fail("cancel expected");
        } catch (java.io.InterruptedIOException expected) { }
        assertNull(cache.getAudio(18));
        try (java.util.stream.Stream<Path> files = Files.walk(temp.getRoot().toPath())) {
            assertFalse(files.anyMatch(p -> p.toString().endsWith(".part")));
        }
    }
    @Test public void htmlErrorNeverBecomesPlayableCache() throws Exception {
        try { cache.prepareAudio(url + "/error", 19, "19", () -> true); fail(); }
        catch (java.io.IOException expected) { }
        assertNull(cache.getAudio(19));
    }
    @Test public void activeMixSourceSurvivesEvictionUntilItsLeaseCloses() throws Exception {
        DiskCache.AudioLease source = cache.prepareAudio(url + "/audio", 20, "20", () -> true);
        Files.write(Paths.get(source.path), new byte[2 * 1024 * 1024]);
        cache.setMaxSizeMB(1);
        cache.evictIfOverBudget();
        assertNotNull(cache.getAudio(20));
        source.close();
        cache.setMaxSizeMB(1);
        cache.evictIfOverBudget();
        assertNull(cache.getAudio(20));
    }
}
