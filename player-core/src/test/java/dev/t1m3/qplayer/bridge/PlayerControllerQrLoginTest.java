package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Cancel queued login work without issuing requests or reviving closed dialogs. */
public class PlayerControllerQrLoginTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test public void closingBeforeQrFetchStartsDiscardsTheQueuedFetchAndUiReset() throws Exception {
        try (Fixture f = new Fixture(folder.newFolder().toPath())) {
            f.controller.qrImage.set(Arrays.asList(Arrays.asList(true)));
            f.controller.qrStatus.set(802);
            f.controller.startQrLogin();
            f.controller.cancelQrLogin();
            f.drainWorker();
            assertTrue(f.controller.qrImage.peek().isEmpty());
            assertEquals(Integer.valueOf(0), f.controller.qrStatus.peek());
            assertEquals("", f.controller.qrLoginError.peek());
            assertNull(field(f.controller, "pendingUnikey").get(f.controller));
        }
    }

    @Test public void queuedPollsAreSingleFlightAndCannotPublishAfterClosing() throws Exception {
        try (Fixture f = new Fixture(folder.newFolder().toPath())) {
            field(f.controller, "pendingUnikey").set(f.controller, "test-only-key");
            f.controller.pollQrLogin();
            assertTrue(field(f.controller, "qrPollInFlight").getBoolean(f.controller));
            f.controller.pollQrLogin();
            f.controller.cancelQrLogin();
            assertFalse(field(f.controller, "qrPollInFlight").getBoolean(f.controller));
            f.drainWorker();
            assertEquals(Integer.valueOf(0), f.controller.qrStatus.peek());
            assertEquals("", f.controller.qrLoginError.peek());
            assertNull(field(f.controller, "pendingUnikey").get(f.controller));
        }
    }

    @Test public void closingBiliDialogBeforeFetchStartsDoesNotReviveTheQr() throws Exception {
        try (Fixture f = new Fixture(folder.newFolder().toPath())) {
            f.controller.startBiliLogin();
            f.controller.cancelBiliLogin();
            f.drainWorker();
            assertEquals(Integer.valueOf(0), f.controller.biliQrStatus.peek());
            assertEquals("", f.controller.biliQrUrl.peek());
            assertEquals("", f.controller.biliError.peek());
        }
    }

    private static Field field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Fixture implements AutoCloseable {
        final String oldBase = AppDirs.base(), oldCache = AppDirs.cacheBase();
        final CountDownLatch release = new CountDownLatch(1);
        final PlayerController controller;
        final ExecutorService worker;
        final Future<?> blocked;

        Fixture(Path root) throws Exception {
            AppDirs.setBase(root.toString());
            AppDirs.setCacheBase(root.resolve("cache").toString());
            controller = new PlayerController(new Backend(), track -> {}, NeteaseClient.INSTANCE);
            worker = (ExecutorService) field(controller, "worker").get(controller);
            CountDownLatch started = new CountDownLatch(1);
            blocked = worker.submit(() -> {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            controller.pump();
        }

        void drainWorker() throws Exception {
            release.countDown();
            blocked.get(3, TimeUnit.SECONDS);
            worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
            controller.pump();
        }

        @Override public void close() {
            release.countDown();
            try { controller.shutdown(); }
            finally { AppDirs.setBase(oldBase); AppDirs.setCacheBase(oldCache); }
        }
    }

    private static final class Backend implements AudioBackend {
        @Override public void play(String source, long startMs) {}
        @Override public void pause() {}
        @Override public void resume() {}
        @Override public void seek(long ms) {}
        @Override public boolean isPlaying() { return false; }
        @Override public long position() { return 0; }
        @Override public long duration() { return 0; }
        @Override public void setVolume(float volume) {}
        @Override public void setOnComplete(Runnable callback) {}
        @Override public void release() {}
    }
}
