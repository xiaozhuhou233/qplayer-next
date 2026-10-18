package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.BeatProfile;
import dev.t1m3.qplayer.audio.SilenceProfile;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PlayerControllerProfileWorkTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();
    private String oldBase;
    private String oldCache;
    private PlayerController controller;
    private Track track;
    private final AtomicInteger silenceCalls = new AtomicInteger();
    private final AtomicInteger beatCalls = new AtomicInteger();

    @Before public void setUp() throws Exception {
        oldBase = AppDirs.base();
        oldCache = AppDirs.cacheBase();
        Path base = temporaryFolder.newFolder("profile-work").toPath();
        AppDirs.setBase(base.toString());
        AppDirs.setCacheBase(base.resolve("cache").toString());
        AudioBackend backend = (AudioBackend) Proxy.newProxyInstance(
                AudioBackend.class.getClassLoader(), new Class<?>[]{AudioBackend.class},
                (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == long.class) return 0L;
                    if (type == int.class) return 0;
                    if (type == float.class) return 1f;
                    return null;
                });
        controller = new PlayerController(backend, item -> { });
        controller.setSilenceProfiler((source, duration) -> {
            silenceCalls.incrementAndGet();
            return new SilenceProfile(50L, 100L);
        });
        controller.setBeatProfiler((source, duration) -> {
            beatCalls.incrementAndGet();
            return new BeatProfile(120d, 0L, 0.9f);
        });
        track = new Track();
        track.source = Track.Source.NETEASE;
        track.neteaseId = 7654321L;
        track.durationMs = 180_000L;
    }

    @After public void tearDown() {
        if (controller != null) controller.shutdown();
        AppDirs.setBase(oldBase);
        AppDirs.setCacheBase(oldCache);
    }

    private ExecutorService worker(String name) throws Exception {
        Field field = PlayerController.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ExecutorService) field.get(controller);
    }

    private void request(String method) throws Exception {
        Method request = PlayerController.class.getDeclaredMethod(method, Track.class, String.class);
        request.setAccessible(true);
        request.invoke(controller, track, "cached-audio.mp3");
    }

    private void drain() throws Exception {
        // Occupy both workers at a barrier, so a sentinel cannot pass a probe
        // that is still running on the pool's other thread.
        CyclicBarrier barrier = new CyclicBarrier(2);
        java.util.concurrent.Callable<Void> waitForPeer = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return null;
        };
        Future<Void> first = worker("probeWorker").submit(waitForPeer);
        Future<Void> second = worker("probeWorker").submit(waitForPeer);
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        worker("beatWorker").submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    @Test public void disabledTransitionsDoNotStartAnyAudioAnalysis() throws Exception {
        controller.setTransitionEnabled(false);
        request("requestSilenceProfile");
        request("requestBeatProfile");
        drain();
        assertEquals(0, silenceCalls.get());
        assertEquals(0, beatCalls.get());
    }

    @Test public void persistentProfilesAvoidDecodingAfterRestart() throws Exception {
        controller.setTransitionEnabled(true);
        String key = "n" + track.neteaseId;
        controller.diskCache.cacheSilence(key, new SilenceProfile(50L, 100L).toBytes());
        controller.diskCache.cacheBeat(key, new BeatProfile(120d, 0L, 0.9f).toBytes());
        assertNotNull(controller.diskCache.getSilence(key));
        request("requestSilenceProfile");
        request("requestBeatProfile");
        drain();
        assertEquals(0, silenceCalls.get());
        assertEquals(0, beatCalls.get());
    }

    @Test public void enabledUncachedProfilesStillRunOnceAndArePersisted() throws Exception {
        controller.setTransitionEnabled(true);
        request("requestSilenceProfile");
        request("requestBeatProfile");
        drain();
        request("requestSilenceProfile");
        request("requestBeatProfile");
        drain();
        assertEquals(1, silenceCalls.get());
        assertEquals(1, beatCalls.get());
        assertNotNull(controller.diskCache.getSilence("n" + track.neteaseId));
        assertNotNull(controller.diskCache.getBeat("n" + track.neteaseId));
    }

    @Test public void queuedAnalysisHonorsTheSwitchBeforeItStarts() throws Exception {
        controller.setTransitionEnabled(true);
        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        worker("probeWorker").submit(blocker);
        worker("probeWorker").submit(blocker);
        worker("beatWorker").submit(blocker);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            request("requestSilenceProfile");
            request("requestBeatProfile");
            controller.setTransitionEnabled(false);
        } finally {
            release.countDown();
        }
        drain();
        assertEquals(0, silenceCalls.get());
        assertEquals(0, beatCalls.get());
        controller.setTransitionEnabled(true);
        request("requestSilenceProfile");
        request("requestBeatProfile");
        drain();
        assertEquals(1, silenceCalls.get());
        assertEquals(1, beatCalls.get());
    }
}
