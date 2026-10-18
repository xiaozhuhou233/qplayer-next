package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.*;
import dev.t1m3.qplayer.audio.automix.*;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class PlayerControllerAutoMixTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String oldBase, oldCache;
    private PlayerController controller;
    private Backend backend;
    private Track a, b;
    private AutoMixRender render;
    @Before public void setup() throws Exception {
        oldBase = AppDirs.base(); oldCache = AppDirs.cacheBase();
        AppDirs.setBase(temp.getRoot().toString()); AppDirs.setCacheBase(temp.getRoot().toString());
        backend = new Backend();
        controller = new PlayerController(backend, track -> { });
        controller.setMainExecutor(Runnable::run);
        controller.setFadeEnabled(false);
        controller.setAutoMixConfig(config(true));
        a = track("a"); b = track("b");
        @SuppressWarnings("unchecked") List<Track> queue = (List<Track>) field("queue").get(controller);
        queue.add(a); queue.add(b);
        field("playIndex").setInt(controller, 0);
        field("playingIntent").setBoolean(controller, true);
        field("autoMixFrom").set(controller, a); field("autoMixTo").set(controller, b);
        Path file = temp.newFile("mix-ready.wav").toPath();
        render = new AutoMixRender(file, "b", 14000, 2000, 20000, 6000);
        field("autoMixReady").set(controller, render);
    }
    @After public void close() {
        controller.shutdown(); AppDirs.setBase(oldBase); AppDirs.setCacheBase(oldCache);
    }
    private AutoMixConfig config(boolean enabled) {
        return new AutoMixConfig(enabled, 2, 0, 6, false, 50, 100, true, "");
    }
    private Track track(String name) {
        Track t = new Track(); t.filePath = temp.getRoot() + "/" + name + ".wav";
        t.title = name; t.artist = "test"; t.durationMs = 20000; return t;
    }
    private Field field(String name) throws Exception {
        Field f = PlayerController.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private void tick() throws Exception {
        Method m = PlayerController.class.getDeclaredMethod("tickAutoMix"); m.setAccessible(true); m.invoke(controller);
    }
    @Test public void promotionPublishesNextSongWithoutReopeningOrSeekingIt() throws Exception {
        backend.position = 10000; tick();
        assertEquals(1, backend.prepares);
        backend.position = 14000; tick();
        assertSame(b, controller.currentTrack());
        assertEquals(0, backend.plays);
        assertEquals(0, backend.seeks);
        assertEquals(2000, backend.position());
        assertTrue(Files.exists(render.file));
        backend.release();
        assertFalse(Files.exists(render.file));
    }
    @Test public void missedCueDoesNotReplayOutgoingTail() throws Exception {
        backend.position = 15000; tick();
        assertEquals(0, backend.prepares);
        assertEquals(0, backend.plays);
        assertSame(a, controller.currentTrack());
        assertTrue(Files.exists(render.file));
        assertEquals("已错过融合点位 · 将正常切歌", controller.autoMixStatus.peek());
    }
    @Test public void delayedPumpDoesNotCancelWarmupStartedByPreparedBackend() throws Exception {
        backend.position = 10000; tick();
        // The native parked deck reached its warmup window before the next UI pump.
        backend.warming = true;
        backend.position = 14500; tick();
        assertNotNull(backend.pending);
        assertTrue(field("crossfadeRunning").getBoolean(controller));
        backend.beginCrossfade(96, FadeCurve.LINEAR);
        assertSame(b, controller.currentTrack());
        assertEquals(0, backend.plays);
        assertEquals(0, backend.seeks);
    }
    @Test public void nativePromotionBetweenPumpsStillUpdatesQueueWithoutReopening() throws Exception {
        backend.position = 10000; tick();
        assertFalse(field("crossfadeRunning").getBoolean(controller));
        backend.warming = true;
        backend.beginCrossfade(96, FadeCurve.LINEAR);
        assertSame(b, controller.currentTrack());
        assertEquals(2000, backend.position());
        assertEquals(0, backend.plays);
        assertEquals(0, backend.seeks);
    }
    @Test public void queuedOldPromotionCannotAdvanceNewlyArmedBoundaryAfterSeek() throws Exception {
        backend.position = 10000; tick();
        List<Runnable> callbacks = new ArrayList<>();
        controller.setMainExecutor(callbacks::add);
        backend.promoted.run();
        controller.setMainExecutor(Runnable::run);
        controller.seek(11000); tick();
        for (Runnable callback : callbacks) callback.run();
        assertSame(a, controller.currentTrack());
        assertNotNull(backend.pending);
        assertTrue(field("autoMixHanded").getBoolean(controller));
    }
    @Test public void dryPrefixWarmsBeforeCueWithoutAdvancingQueueAndCanBeCancelled() throws Exception {
        render.delete();
        render = new AutoMixRender(temp.newFile("mix-warm.wav").toPath(), "b", 14000, 2000, 20000, 6000, 1500);
        field("autoMixReady").set(controller, render);
        backend.position = 10000; tick();
        backend.position = 12499; tick();
        assertFalse(backend.warming);
        backend.position = 12500; tick();
        assertTrue(backend.warming);
        assertSame(a, controller.currentTrack());
        assertEquals(0, backend.plays);
        controller.mediaPause();
        assertTrue(Files.exists(render.file));
        assertSame(render, field("autoMixReady").get(controller));
        backend.position = 11000;
        controller.mediaResume(); tick();
        assertEquals(2, backend.prepares);
        backend.position = 12500; tick();
        assertTrue(backend.warming);
        backend.beginCrossfade(96, FadeCurve.LINEAR);
        assertSame(b, controller.currentTrack());
        assertEquals(0, backend.plays);
    }
    @Test public void stalePreparationStatusCannotLeakIntoNewTrack() throws Exception {
        long oldGeneration = field("autoMixGeneration").getLong(controller);
        controller.setAutoMixConfig(config(false));
        Method publish = PlayerController.class.getDeclaredMethod("publishAutoMixStatus", long.class, String.class);
        publish.setAccessible(true);
        publish.invoke(controller, oldGeneration, "本地融合制作成功 · old");
        assertEquals("", controller.autoMixStatus.peek());
    }
    @Test public void failureReasonUsesNestedDecoderCauseWithoutLeakingSourcePaths() {
        assertEquals("音频解码超时", PlayerController.autoMixFailureReason(
                new java.io.IOException("Cannot decode /private/source", new IllegalStateException("AutoMix decoder timed out"))));
        assertEquals("下一首解码长度不足", PlayerController.autoMixFailureReason(
                new java.io.IOException("Incoming audio ended before declared duration: 5000 frames missing")));
        String unknown = PlayerController.autoMixFailureReason(new java.io.IOException("/private/source", new NullPointerException("token=secret")));
        assertTrue(unknown.contains("NullPointerException"));
        assertFalse(unknown.contains("secret"));
        assertFalse(unknown.contains("/private"));
    }
    @Test public void disableCancelsParkedFileWithoutInterruptingCurrentSong() throws Exception {
        backend.position = 10000; tick();
        controller.setAutoMixConfig(config(false));
        assertFalse(Files.exists(render.file));
        assertTrue(backend.playing);
        assertSame(a, controller.currentTrack());
        assertEquals(0, backend.plays);
    }
    @Test public void videoDoesNotStartMix() throws Exception {
        a.source = Track.Source.BILI;
        backend.position = 14000; tick();
        assertEquals(0, backend.prepares);
    }
    @Test public void seekRetainsCompletedMixAndUsesRequestedSongTime() throws Exception {
        backend.position = 10000; tick();
        long generation = field("autoMixGeneration").getLong(controller);
        controller.seek(2500);
        assertTrue(Files.exists(render.file));
        assertEquals(2500, backend.position());
        assertEquals(1, backend.seeks);
        assertSame(a, controller.currentTrack());
        assertSame(a, field("autoMixFrom").get(controller));
        assertSame(render, field("autoMixReady").get(controller));
        assertEquals(generation, field("autoMixGeneration").getLong(controller));
        tick();
        assertEquals(1, backend.prepares);
        backend.position = 10000; tick();
        assertEquals(2, backend.prepares);
        assertEquals(render.file, backend.pending.file);
        backend.position = 14000; tick();
        assertSame(b, controller.currentTrack());
        assertEquals(0, backend.plays);
    }
    @Test public void seekPastCueSkipsMixEvenWithinPumpToleranceAndCanReturnBeforeCue() throws Exception {
        backend.position = 10000; tick();
        controller.mediaSeek(14001); tick();
        assertNull(backend.pending);
        assertSame(a, controller.currentTrack());
        assertTrue(Files.exists(render.file));
        assertSame(render, field("autoMixReady").get(controller));
        // MediaPlayer may briefly report the old position before its async seek settles.
        backend.position = 10000; tick();
        assertNull(backend.pending);
        controller.mediaSeek(12000); tick();
        assertEquals(2, backend.prepares);
        assertEquals("本地融合制作成功 · b", controller.autoMixStatus.peek());
        backend.position = 14000; tick();
        assertSame(b, controller.currentTrack());
    }
    @Test public void missedCueKeepsRenderForSeekingBack() throws Exception {
        backend.position = 10000; tick();
        backend.position = 15000; tick();
        assertNull(backend.pending);
        assertTrue(Files.exists(render.file));
        controller.seek(10000); tick();
        assertEquals(2, backend.prepares);
        assertEquals(render.file, backend.pending.file);
    }
    @Test public void completedRenderShowsSongTimeCountdownWhichStopsWhilePaused() throws Exception {
        backend.position = 2500; tick();
        assertEquals(Long.valueOf(12), controller.autoMixRemainingSeconds.peek());
        backend.position = 4000; tick();
        assertEquals(Long.valueOf(10), controller.autoMixRemainingSeconds.peek());
        controller.mediaPause();
        for (int i = 0; i < 10; i++) tick();
        assertEquals(Long.valueOf(10), controller.autoMixRemainingSeconds.peek());
        assertEquals("本地融合制作成功 · b", controller.autoMixStatus.peek());
        assertSame(render, field("autoMixReady").get(controller));
        controller.mediaResume();
        backend.position = 6000; tick();
        assertEquals(Long.valueOf(8), controller.autoMixRemainingSeconds.peek());
        controller.setAutoMixConfig(config(false));
        assertEquals(Long.valueOf(-1), controller.autoMixRemainingSeconds.peek());
    }
    @Test public void pausedSeekBackClearsMissedStatusEvenBeforeNativeSeekCompletes() throws Exception {
        backend.position = 15000; tick();
        assertEquals("已错过融合点位 · 将正常切歌", controller.autoMixStatus.peek());
        controller.mediaPause();
        backend.asynchronousSeek = true;
        controller.mediaSeek(2000); tick();
        assertEquals(15000, backend.position());
        assertEquals("本地融合制作成功 · b", controller.autoMixStatus.peek());
        assertEquals(Long.valueOf(12), controller.autoMixRemainingSeconds.peek());
        assertEquals(0, backend.prepares);
        backend.position = 2000; backend.asynchronousSeek = false;
        tick();
        assertEquals(Long.valueOf(12), controller.autoMixRemainingSeconds.peek());
        controller.mediaResume(); backend.position = 10000; tick();
        assertNotNull(backend.pending);
    }
    @Test public void pausedSeekPastCueHidesCountdownAndSeekingBackRestoresIt() throws Exception {
        backend.position = 2000; tick();
        controller.mediaPause();
        controller.seek(14001); tick();
        assertEquals("已错过融合点位 · 将正常切歌", controller.autoMixStatus.peek());
        assertEquals(Long.valueOf(-1), controller.autoMixRemainingSeconds.peek());
        controller.seek(1000); tick();
        assertEquals("本地融合制作成功 · b", controller.autoMixStatus.peek());
        assertEquals(Long.valueOf(13), controller.autoMixRemainingSeconds.peek());
        assertSame(render, field("autoMixReady").get(controller));
    }
    @Test public void scrubBeforeCuePreparesDuringNativeSeekAndWarmsWithoutWaitingForUi() throws Exception {
        render.delete();
        render = new AutoMixRender(temp.newFile("seek-warm.wav").toPath(), "b", 14000, 2000, 20000, 6000, 3000);
        field("autoMixReady").set(controller, render);
        backend.position = 16000;
        backend.asynchronousSeek = true;
        backend.playing = false; // Native output can pause temporarily while seeking.
        controller.seek(12000);
        assertNotNull("Prepare the cached WAV before the asynchronous seek completes", backend.pending);
        assertFalse("Old clock must not start the warm deck", backend.warming);
        assertEquals("本地融合制作成功 · b", controller.autoMixStatus.peek());
        assertEquals(Long.valueOf(2), controller.autoMixRemainingSeconds.peek());
        backend.position = 12000;
        backend.asynchronousSeek = false;
        backend.playing = true;
        tick();
        assertTrue(backend.warming);
        assertSame(render, field("autoMixReady").get(controller));
    }
    @Test public void pauseIntentCancelsWarmupBeforeItsFadeFinishes() throws Exception {
        render.delete();
        render = new AutoMixRender(temp.newFile("mix-pause-fade.wav").toPath(), "b", 14000, 2000, 20000, 6000, 1500);
        field("autoMixReady").set(controller, render);
        backend.position = 12500; tick();
        assertTrue(backend.warming);
        controller.setFadeEnabled(true);
        controller.mediaPause();
        assertTrue("Audible pause fade has not finished", backend.isPlaying());
        assertNull("No muted deck may keep running for the pause", backend.pending);
        tick();
        assertNull(backend.pending);
        assertSame(render, field("autoMixReady").get(controller));
    }
    @Test public void repeatedPauseAndSeekReuseOneRenderAndReleaseOnDisable() throws Exception {
        long generation = field("autoMixGeneration").getLong(controller);
        for (int i = 0; i < 3; i++) {
            controller.seek(11000); tick();
            controller.mediaPause(); tick();
            assertNull(backend.pending);
            assertTrue(Files.exists(render.file));
            assertSame(render, field("autoMixReady").get(controller));
            assertEquals(generation, field("autoMixGeneration").getLong(controller));
            controller.mediaResume(); tick();
            assertNotNull(backend.pending);
            assertEquals(render.file, backend.pending.file);
        }
        controller.setAutoMixConfig(config(false));
        assertFalse(Files.exists(render.file));
    }
    @Test public void seekAndPauseDoNotCancelInProgressRenderJob() throws Exception {
        field("autoMixReady").set(controller, null);
        render.delete();
        java.util.concurrent.FutureTask<Void> job = new java.util.concurrent.FutureTask<>(() -> null);
        field("autoMixJob").set(controller, job);
        long generation = field("autoMixGeneration").getLong(controller);
        controller.seek(15000);
        controller.mediaPause();
        assertFalse(job.isCancelled());
        assertEquals(generation, field("autoMixGeneration").getLong(controller));
        // Completion after seeking must respect the requested position, not restart A's tail.
        render = new AutoMixRender(temp.newFile("mix-late.wav").toPath(), "b", 14000, 2000, 20000, 6000);
        field("autoMixReady").set(controller, render);
        backend.resume(); tick();
        assertEquals(0, backend.prepares);
        assertTrue(Files.exists(render.file));
    }
    @Test public void delayedAbandonCannotCancelNewlyArmedMixAfterSeek() throws Exception {
        backend.position = 10000; tick();
        List<Runnable> callbacks = new ArrayList<>();
        controller.setMainExecutor(callbacks::add);
        backend.pause();
        controller.setMainExecutor(Runnable::run);
        controller.seek(11000);
        backend.resume(); tick();
        for (Runnable callback : callbacks) callback.run();
        assertTrue(field("autoMixHanded").getBoolean(controller));
        assertNotNull(backend.pending);
        backend.position = 14000; tick();
        assertSame(b, controller.currentTrack());
    }
    @Test public void aiDjBatchBoundaryDoesNotStartMix() throws Exception {
        field("experimentalAiDjMode").setBoolean(controller, true);
        field("playIndex").setInt(controller, 1);
        backend.position = 14000; tick();
        assertEquals(0, backend.prepares);
        assertSame(b, controller.currentTrack());
    }
    private static class Backend implements AudioBackend {
        long position; int prepares, plays, seeks; boolean playing = true, warming, asynchronousSeek;
        AutoMixRender pending, active; Runnable promoted, abandoned;
        public boolean supportsAutoMix() { return true; }
        public boolean autoMixInProgress() { return warming; }
        public boolean isSeeking() { return asynchronousSeek; }
        public boolean prepareAutoMix(AutoMixRender r) { cancelIncoming(); pending = r.retain(); prepares++; return true; }
        public IncomingMix incomingMix() { return pending == null ? null : IncomingMix.IDENTITY; }
        public boolean beginAutoMix() {
            if (pending.leadInMs == 0) return beginCrossfade(96, FadeCurve.LINEAR);
            warming = true; return true;
        }
        public boolean beginCrossfade(long ms, FadeCurve curve) {
            if (active != null) active.delete();
            active = pending; pending = null; position = active.entryMs; promoted.run(); return true;
        }
        public void cancelIncoming() { if (pending != null) pending.delete(); pending = null; warming = false; }
        public void setOnCrossfadeComplete(Runnable callback) { promoted = callback; }
        public void setOnCrossfadeAbandoned(Runnable callback) { abandoned = callback; }
        public void play(String source, long ms) { plays++; position = ms; }
        public void pause() {
            playing = false;
            boolean had = pending != null;
            cancelIncoming();
            if (had) abandoned.run();
        }
        public void resume() { playing = true; }
        public boolean isPlaying() { return playing; }
        public void seek(long ms) { seeks++; if (!asynchronousSeek) position = ms; }
        public long position() { return position; }
        public long duration() { return 20000; }
        public void setVolume(float volume) { }
        public void setOnComplete(Runnable callback) { }
        public void release() { cancelIncoming(); if (active != null) active.delete(); active = null; }
    }
}
