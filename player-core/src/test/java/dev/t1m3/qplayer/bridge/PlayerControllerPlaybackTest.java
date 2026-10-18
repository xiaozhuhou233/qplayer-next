package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.BeatProfile;
import dev.t1m3.qplayer.audio.FadeCurve;
import dev.t1m3.qplayer.audio.IncomingMix;
import dev.t1m3.qplayer.audio.SilenceProfile;
import dev.t1m3.qplayer.audio.StemEditRenderer;
import dev.t1m3.qplayer.customapi.CustomSong;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.util.Logger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayerControllerPlaybackTest {
    @Test
    public void aiPreviewKeepsCurrentQueueUntilConfirmedAndCannotStartTwice() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("ai-confirm").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            java.util.Queue<Runnable> main = new java.util.concurrent.ConcurrentLinkedQueue<>();
            controller.setMainExecutor(main::add);
            java.lang.reflect.Method playQueue = PlayerController.class.getDeclaredMethod(
                    "playQueue", java.util.List.class, int.class);
            playQueue.setAccessible(true);
            Track current = new Track();
            current.title = "current music";
            current.filePath = base.resolve("current.mp3").toString();
            playQueue.invoke(controller, java.util.Collections.singletonList(current), 0);
            main.remove().run();
            assertEquals(1, backend.playCalls);

            java.lang.reflect.Field generationField = PlayerController.class.getDeclaredField("aiRequestGeneration");
            generationField.setAccessible(true);
            java.util.concurrent.atomic.AtomicLong generation =
                    (java.util.concurrent.atomic.AtomicLong) generationField.get(controller);
            long request = generation.incrementAndGet();
            Class<?> continuationClass = Class.forName(PlayerController.class.getName() + "$AiContinuation");
            java.lang.reflect.Constructor<?> constructor = continuationClass.getDeclaredConstructor(
                    String.class, String.class, String.class, String.class, int.class, boolean.class);
            constructor.setAccessible(true);
            Object continuation = constructor.newInstance("https://example.invalid", "", "model", "request", 10, false);
            java.lang.reflect.Method finish = PlayerController.class.getDeclaredMethod("finishAiPlaylist",
                    long.class, java.util.List.class, String.class, continuationClass, boolean.class);
            finish.setAccessible(true);
            NeteaseSong song = new NeteaseSong();
            song.id = 456L;
            song.name = "preview song";
            controller.aiLoading.set(false);
            finish.invoke(controller, request, java.util.Collections.singletonList(song), "preview", continuation, true);
            assertEquals(456L, controller.aiSongs.peek().get(0).id);
            assertEquals(current, controller.queueTracks.peek().get(0));
            assertEquals(1, backend.playCalls);
            assertFalse(controller.confirmAiPlaylistPlayback(request - 1));
            int pendingBeforeConfirmation = main.size();
            assertTrue(controller.confirmAiPlaylistPlayback(request));
            assertEquals(456L, controller.queueTracks.peek().get(0).neteaseId);
            assertEquals(pendingBeforeConfirmation + 1, main.size());
            assertFalse(controller.confirmAiPlaylistPlayback(request));
            assertEquals(pendingBeforeConfirmation + 1, main.size());
            java.lang.reflect.Field remembered = PlayerController.class.getDeclaredField("aiContinuation");
            remembered.setAccessible(true);
            assertEquals(continuation, remembered.get(controller));

            // Cancelling a displayed result (including during text reveal) invalidates it.
            request = generation.incrementAndGet();
            finish.invoke(controller, request, java.util.Collections.singletonList(song), "cancelled", continuation, true);
            controller.cancelAiPlaylistGeneration();
            assertFalse(controller.confirmAiPlaylistPlayback(request));
            // A late response from that request must not publish another playable preview.
            finish.invoke(controller, request, java.util.Collections.singletonList(song), "stale", continuation, true);
            assertFalse(controller.confirmAiPlaylistPlayback(request));
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }


    @Test
    public void cancelledAiCallbacksCannotReplaceQueueOrFinishANewerRequest() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("ai-cancel").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);
            java.lang.reflect.Field pendingUi = PlayerController.class.getDeclaredField("uiQueue");
            pendingUi.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Queue<Runnable> main = (java.util.Queue<Runnable>) pendingUi.get(controller);
            main.clear();
            java.lang.reflect.Field generation = PlayerController.class.getDeclaredField("aiRequestGeneration");
            generation.setAccessible(true);
            java.util.concurrent.atomic.AtomicLong token = (java.util.concurrent.atomic.AtomicLong) generation.get(controller);
            java.lang.reflect.Method post = PlayerController.class.getDeclaredMethod("postAiGeneration", long.class, Runnable.class);
            post.setAccessible(true);
            java.lang.reflect.Field taskField = PlayerController.class.getDeclaredField("aiRequestTask");
            taskField.setAccessible(true);
            java.util.concurrent.FutureTask<Void> request = new java.util.concurrent.FutureTask<>(() -> null);
            taskField.set(controller, request);
            controller.aiLoading.set(true);
            java.util.concurrent.atomic.AtomicInteger queueWrites = new java.util.concurrent.atomic.AtomicInteger();
            final PlayerController current = controller;
            post.invoke(controller, token.get(), (Runnable) queueWrites::incrementAndGet);
            post.invoke(controller, token.get(), (Runnable) () -> {
                current.aiLoading.set(false);
                current.aiError.set("obsolete failure");
            });
            controller.cancelAiPlaylistGeneration();
            assertFalse(controller.aiLoading.peek());
            assertTrue(request.isCancelled());
            // A fresh request can start before the old network response is drained.
            token.incrementAndGet();
            controller.aiLoading.set(true);
            while (!main.isEmpty()) main.remove().run();
            assertEquals(0, queueWrites.get());
            assertTrue(controller.aiLoading.peek());
            assertEquals("", controller.aiError.peek());
            post.invoke(controller, token.get(), (Runnable) queueWrites::incrementAndGet);
            main.remove().run();
            assertEquals(1, queueWrites.get());
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }




    @Test
    public void rapidQueueSelectionsOnlyStartTheNewestSong() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("rapid-selection").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            java.util.Queue<Runnable> main = new java.util.concurrent.ConcurrentLinkedQueue<>();
            controller.setMainExecutor(main::add);
            java.lang.reflect.Method playQueue = PlayerController.class.getDeclaredMethod(
                    "playQueue", java.util.List.class, int.class);
            playQueue.setAccessible(true);
            Track last = null;
            for (int i = 0; i < 100; i++) {
                Track song = new Track();
                song.title = "song-" + i;
                song.filePath = base.resolve("song-" + i + ".mp3").toString();
                playQueue.invoke(controller, java.util.Collections.singletonList(song), 0);
                last = song;
            }
            // Drain superseded main-thread selections before allowing the newest
            // one to run: none may open the latest queue at an obsolete index.
            for (int i = 0; i < 99; i++) main.remove().run();
            assertEquals(0, backend.playCalls);
            main.remove().run();
            assertEquals(1, backend.playCalls);
            assertEquals(last.filePath, backend.lastSource);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    private static final long ASYNC_FADE_TIMEOUT_MS = 3000L;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void lateAiContinuationCannotAppendToAReplacedQueue() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("ai-continuation").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);

            Class<?> continuationType = Class.forName(PlayerController.class.getName() + "$AiContinuation");
            java.lang.reflect.Constructor<?> constructor = continuationType.getDeclaredConstructor(
                    String.class, String.class, String.class, String.class, int.class, boolean.class);
            constructor.setAccessible(true);
            Object oldRequest = constructor.newInstance("url", "key", "model", "request", 10, false);
            Object newRequest = constructor.newInstance("url", "key", "model", "request", 10, false);
            java.lang.reflect.Field continuation = PlayerController.class.getDeclaredField("aiContinuation");
            java.lang.reflect.Field origin = PlayerController.class.getDeclaredField("queueOriginKind");
            java.lang.reflect.Field inFlight = PlayerController.class.getDeclaredField("aiContinuationInFlight");
            continuation.setAccessible(true);
            origin.setAccessible(true);
            inFlight.setAccessible(true);
            java.lang.reflect.Method setOrigin = PlayerController.class.getDeclaredMethod(
                    "setQueueOrigin", PlayerController.QueueOrigin.class);
            setOrigin.setAccessible(true);
            java.lang.reflect.Method append = PlayerController.class.getDeclaredMethod(
                    "appendAiBatchIfCurrent", java.util.List.class, String.class, continuationType);
            append.setAccessible(true);

            continuation.set(controller, oldRequest);
            origin.set(controller, PlayerController.QueueOrigin.AI);
            inFlight.setBoolean(controller, true);
            setOrigin.invoke(controller, PlayerController.QueueOrigin.USER);
            assertFalse(inFlight.getBoolean(controller));

            NeteaseSong song = new NeteaseSong();
            song.id = 456L;
            song.name = "new batch";
            continuation.set(controller, newRequest);
            origin.set(controller, PlayerController.QueueOrigin.AI);
            append.invoke(controller, Arrays.asList(song), "old", oldRequest);
            assertTrue(controller.queueTracks.peek().isEmpty());
            append.invoke(controller, Arrays.asList(song), "new", newRequest);
            assertEquals(456L, controller.queueTracks.peek().get(0).neteaseId);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void togetherQueueReplacementNeverFallsBackToTheOldNumericIndex() {
        // A reordered queue keeps the currently audible song by id, even though it
        // moved from index 1 to index 0.
        assertEquals(20L, PlayerController.togetherReplacementTarget(
                Arrays.asList(20L, 30L, 40L), 20L, 0L));

        // A matching GOTO is authoritative once its target is in the new queue.
        assertEquals(40L, PlayerController.togetherReplacementTarget(
                Arrays.asList(20L, 30L, 40L), 20L, 40L));

        // The new queue arrived before its GOTO. Defer instead of playing whatever
        // happens to occupy the previous numeric index and echoing that mistake.
        assertEquals(0L, PlayerController.togetherReplacementTarget(
                Arrays.asList(30L, 40L, 50L), 20L, 0L));
        assertEquals(0L, PlayerController.togetherReplacementTarget(
                Arrays.asList(30L, 40L, 50L), 20L, 20L));
    }

    @Test
    public void togetherRemoteCommandNeverReplaysAnOlderSnapshot() {
        assertTrue(PlayerController.isNewTogetherRemoteCommand(
                200L, "goto:new", 100L, "goto:old", -1L, ""));
        assertFalse(PlayerController.isNewTogetherRemoteCommand(
                99L, "goto:older", 100L, "goto:old", -1L, ""));

        // A command already queued on the main thread is part of the ordering floor;
        // a replica returning an intermediate snapshot must not enqueue behind it.
        assertFalse(PlayerController.isNewTogetherRemoteCommand(
                150L, "goto:middle", 100L, "goto:old", 200L, "goto:new"));
        assertFalse(PlayerController.isNewTogetherRemoteCommand(
                200L, "goto:new", 100L, "goto:old", 200L, "goto:new"));

        // Millisecond timestamps can collide; a distinct command observed at the
        // same sequence is still allowed, while exact signatures are deduplicated.
        assertTrue(PlayerController.isNewTogetherRemoteCommand(
                200L, "pause:new", 200L, "goto:new", -1L, ""));
    }

    @Test
    public void togetherLiveTrackSwitchCannotReuseTheOutgoingPosition() {
        NeteaseClient.TogetherCommand command = new NeteaseClient.TogetherCommand();
        command.commandType = "GOTO";
        command.progressMs = 198000L;

        // A live remote switch starts the replacement at zero even if the server
        // snapshot still carries the previous track's 3:18 play head.
        assertEquals(0L, PlayerController.togetherAppliedProgress(
                command, false, true, 197500L));

        // Joining an existing room must still synchronize to its current position.
        assertEquals(198000L, PlayerController.togetherAppliedProgress(
                command, true, true, 0L));

        // Same-track GOTO and explicit PROGRESS commands retain seek semantics.
        assertEquals(198000L, PlayerController.togetherAppliedProgress(
                command, false, false, 0L));
        command.commandType = "PROGRESS";
        assertEquals(198000L, PlayerController.togetherAppliedProgress(
                command, false, true, 0L));
    }

    @Test
    public void togetherNaturalAdvanceElectsOneStableLeader() {
        NeteaseClient.TogetherRoom room = new NeteaseClient.TogetherRoom();
        room.creatorId = 42L;
        NeteaseClient.TogetherUser peer = new NeteaseClient.TogetherUser();
        peer.userId = 17L;
        room.users.add(peer);

        // The creator remains authoritative even when it is not the smallest id.
        assertEquals(42L, PlayerController.togetherLeaderId(room, 17L));
        assertTrue(PlayerController.shouldWaitForTogetherLeader(true, 17L, 42L));
        assertFalse(PlayerController.shouldWaitForTogetherLeader(true, 42L, 42L));

        // Partial legacy room payloads without creatorId still elect the same
        // participant on both clients through a deterministic smallest-id fallback.
        room.creatorId = 0L;
        assertEquals(17L, PlayerController.togetherLeaderId(room, 42L));
        assertEquals(17L, PlayerController.togetherLeaderId(room, 17L));
        assertFalse(PlayerController.shouldWaitForTogetherLeader(false, 42L, 17L));
    }

    @Test
    public void togetherTrackSwitcherTakesControlFromTheCreator() {
        NeteaseClient.TogetherCommand command = new NeteaseClient.TogetherCommand();
        command.userId = 17L;
        command.commandType = "GOTO";
        assertEquals(17L, PlayerController.togetherLeaderAfterRemoteCommand(42L, command));

        // Timeline and transport changes do not affect who owns natural advance.
        command.commandType = "PROGRESS";
        assertEquals(42L, PlayerController.togetherLeaderAfterRemoteCommand(42L, command));
        command.commandType = "PAUSE";
        assertEquals(42L, PlayerController.togetherLeaderAfterRemoteCommand(42L, command));

        // NEXT/PREV are track selections too, including commands from official
        // clients that do not encode them as GOTO.
        command.commandType = "NEXT";
        assertEquals(17L, PlayerController.togetherLeaderAfterRemoteCommand(42L, command));
    }

    @Test
    public void togetherRiskControlUsesBoundedExponentialBackoff() {
        assertEquals(30_000L, PlayerController.togetherRateLimitBackoffMs(1));
        assertEquals(60_000L, PlayerController.togetherRateLimitBackoffMs(2));
        assertEquals(120_000L, PlayerController.togetherRateLimitBackoffMs(3));
        assertEquals(120_000L, PlayerController.togetherRateLimitBackoffMs(20));
        assertFalse(PlayerController.togetherRateLimitShouldPause(3));
        assertTrue(PlayerController.togetherRateLimitShouldPause(4));
    }

    @Test
    public void togetherRiskControlRecognizesServerAndTransportFailures() {
        assertTrue(PlayerController.isTogetherRateLimited(
                new java.io.IOException("操作频繁，请稍后再试")));
        assertTrue(PlayerController.isTogetherRateLimited(
                new RuntimeException("wrapper",
                        new java.io.IOException("HTTP 429: Too Many Requests"))));
        assertTrue(PlayerController.isTogetherRateLimited(
                new java.io.IOException("rate limit exceeded")));

        assertFalse(PlayerController.isTogetherRateLimited(
                new java.io.IOException("连接超时，请稍后再试")));
        assertFalse(PlayerController.isTogetherRateLimited(
                new java.io.IOException("同步一起听状态失败")));
    }

    @Test
    public void selectingTrackAfterSessionRestoreDoesNotReplayItOnResume() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("state").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":42000,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"restored\",\"durationMs\":120000,\"filePath\":\"restored.mp3\"},"
                    + "{\"source\":\"LOCAL\",\"title\":\"selected\",\"durationMs\":120000,\"filePath\":\"selected.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);

            // Choosing a queue entry is a real playAt() before the restored entry's
            // play button has consumed needsReplay.
            controller.playQueueIndex(1);
            assertEquals(1, backend.playCalls);

            controller.toggle(); // pause
            controller.toggle(); // resume must use the already-loaded backend

            assertEquals(1, backend.playCalls);
            assertEquals(1, backend.resumeCalls);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void restoredCurrentTrackIsLikeableBeforePlaybackStarts() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("restore-likeable").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            String queue = "{\"playIndex\":0,\"positionMs\":42000,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"neteaseId\":123,\"title\":\"restored\","
                    + "\"durationMs\":120000,\"filePath\":\"restored.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.pump();

            assertTrue(controller.currentLikeable.peek());
            assertFalse(controller.currentLiked.peek());
            assertEquals(0, backend.playCalls);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void mediaSessionControlsUseTheSameFadeAsManualToggle() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("media-pause").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);
            backend.fireStarted();
            waitForVolume(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);

            // The MediaSession intent flips immediately (state must be reported right
            // away), but the notification/lock-screen/dynamic-island pause now rides
            // the same ramp-down as a manual toggle() pause instead of cutting audio.
            int pausesBeforeMediaCommand = backend.pauseCalls;
            controller.mediaPause();
            assertFalse(controller.isPlaying());
            assertEquals(pausesBeforeMediaCommand, backend.pauseCalls);
            assertTrue(backend.playing);

            waitForVolume(backend, 0f, ASYNC_FADE_TIMEOUT_MS);
            // The final fade tick publishes zero volume immediately before it
            // invokes the deferred pause callback. Wait for that second observable
            // state as well instead of racing the two adjacent worker operations.
            waitForPauseCalls(backend, pausesBeforeMediaCommand + 1,
                    ASYNC_FADE_TIMEOUT_MS);
            assertEquals(pausesBeforeMediaCommand + 1, backend.pauseCalls);
            assertFalse(backend.playing);

            // Resuming from the notification fades back in from silence, symmetric
            // with the pause.
            int resumesBeforeMediaCommand = backend.resumeCalls;
            controller.mediaResume();
            assertTrue(controller.isPlaying());
            assertEquals(resumesBeforeMediaCommand + 1, backend.resumeCalls);
            assertTrue(backend.playing);
            assertEquals(0f, backend.volume, 0.05f);

            waitForVolume(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);
            assertEquals(resumesBeforeMediaCommand + 1, backend.resumeCalls);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void disconnectedOutputStopsAnInFlightPauseFadeAndWaitsForManualResume() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("output-disconnect").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));
            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);
            backend.fireStarted();
            waitForVolume(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);

            // A normal pause flips intent before the backend has stopped. Output
            // removal must still silence/pause it immediately, without that guard.
            controller.mediaPause();
            assertFalse(controller.isPlaying());
            assertTrue(backend.playing);
            int pauses = backend.pauseCalls;
            int resumes = backend.resumeCalls;
            int autoResumesCancelled = backend.cancelAutoResumeCalls;
            controller.mediaPauseForDisconnectedOutput();
            assertFalse(backend.playing);
            assertFalse(controller.isPlaying());
            assertEquals(pauses + 1, backend.pauseCalls);
            assertEquals(0f, backend.volume, 0.001f);

            // Bluetooth removal and noisy broadcasts can both arrive for one
            // disconnect, including after focus has already paused the backend.
            controller.mediaPauseForDisconnectedOutput();
            assertFalse(backend.playing);
            assertEquals(resumes, backend.resumeCalls);
            assertEquals(autoResumesCancelled + 2, backend.cancelAutoResumeCalls);

            controller.mediaResume();
            assertTrue(controller.isPlaying());
            assertTrue(backend.playing);
            waitForVolume(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);
            // The superseded 900 ms pause must not land after the manual resume.
            Thread.sleep(1000L);
            assertTrue(backend.playing);
            assertEquals(pauses + 2, backend.pauseCalls);
            assertEquals(resumes + 1, backend.resumeCalls);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void disconnectedOutputRejectsALateBiliResolutionUntilManualResume() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("output-disconnect-resolving").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));
            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.playQueueIndex(0);
            // No onStarted yet: the source is still resolving/preparing.
            Track track = controller.currentTrack();
            java.lang.reflect.Field revisionField = PlayerController.class.getDeclaredField("coverRevision");
            revisionField.setAccessible(true);
            long oldRequest = ((java.util.concurrent.atomic.AtomicLong) revisionField.get(controller)).get();
            int plays = backend.playCalls;
            controller.mediaPauseForDisconnectedOutput();
            assertFalse(backend.playing);

            java.lang.reflect.Method resolved = PlayerController.class.getDeclaredMethod("startBiliStream",
                    Track.class, String.class, int.class, long.class, long.class);
            resolved.setAccessible(true);
            resolved.invoke(controller, track, "https://example.invalid/stale-bili.mp4", 0, 0L, oldRequest);
            assertEquals(plays, backend.playCalls);
            assertFalse(controller.isPlaying());

            controller.mediaResume();
            assertEquals(plays + 1, backend.playCalls);
            assertTrue(backend.playing);
            backend.fireStarted();
            assertTrue(controller.isPlaying());
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void mediaResumeDuringPauseFadePicksUpTheRampWithoutDoubleResume() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("media-pause-resume-race").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);
            backend.fireStarted();
            waitForVolume(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);

            // playAt() itself unconditionally pauses the backend once up front (there
            // is nothing playing yet to fade), so the "no extra pause" baseline is
            // captured here, not literal 0.
            int pausesBeforeMediaCommand = backend.pauseCalls;
            controller.mediaPause();
            Thread.sleep(120L); // catch the fade-out mid-ramp, before it reaches silence
            assertTrue(backend.volume < 0.8f);
            assertTrue(backend.playing); // the deferred backend.pause() hasn't landed yet

            int resumesBeforeMediaCommand = backend.resumeCalls;
            controller.mediaResume();
            assertTrue(controller.isPlaying());
            // Backend was never actually paused -- pick the ramp back up instead of
            // issuing a redundant resume().
            assertEquals(resumesBeforeMediaCommand, backend.resumeCalls);
            assertTrue(backend.playing);

            waitForVolume(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);
            // The superseded pause's deferred completion must not fire late and
            // pause the backend out from under the resume.
            Thread.sleep(1000L);
            assertTrue(backend.playing);
            assertEquals(pausesBeforeMediaCommand, backend.pauseCalls);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void lyricClockWaitsForAudioAndFollowsRealPauseState() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("lyric-clock").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);

            // play() only means the decoder was asked to start. Lyrics must remain at
            // the baseline until the backend confirms that audio is actually audible.
            backend.position = 800L;
            assertFalse(controller.isLyricClockRunning());
            assertEquals(0L, controller.lyricClockPosition());

            backend.fireStarted();
            assertTrue(controller.isLyricClockRunning());

            backend.position = 3210L;
            controller.toggle();
            // The pause button starts an audible fade-out. The backend is still
            // playing during that tail, so lyrics must continue with it.
            assertTrue(controller.isLyricClockRunning());
            assertEquals(3210L, controller.lyricClockPosition());

            backend.position = 3500L;
            assertEquals(3500L, controller.lyricClockPosition());

            // Once the fade completes and the backend really pauses, both clocks
            // stop at the same position. Resume therefore has no catch-up jump.
            backend.pause();
            assertFalse(controller.isLyricClockRunning());
            backend.position = 3500L;
            assertEquals(3500L, controller.lyricClockPosition());

            controller.toggle();
            assertTrue(controller.isLyricClockRunning());
            assertEquals(3500L, controller.lyricClockPosition());
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void fadeFinishesWithoutRenderPumpAndPreservesUserVolume() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("fade-clock").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setVolume(0.6f);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);
            backend.fireStarted();

            // Deliberately never call controller.pump(): a hidden/destroyed window
            // must not strand playback at the first quiet fade sample.
            waitForVolume(backend, 0.6f, ASYNC_FADE_TIMEOUT_MS);
            assertEquals(0.6f, backend.volume, 0.02f);
            assertTrue(backend.volumeWrites > 2);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void replacingTrackCancelsOutgoingFadeCompletion() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("fade-switch").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"one\",\"durationMs\":120000,\"filePath\":\"one.mp3\"},"
                    + "{\"source\":\"LOCAL\",\"title\":\"two\",\"durationMs\":120000,\"filePath\":\"two.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);
            backend.fireStarted();

            controller.next();
            int pausesAfterReplacement = backend.pauseCalls;
            // The second source is intentionally left in its loading window. The old
            // track's delayed fade completion must not pause this new request later.
            Thread.sleep(1050L);
            assertEquals(pausesAfterReplacement, backend.pauseCalls);
            assertTrue(backend.playing);
            assertEquals(0.8f, backend.volume, 0.001f);

            backend.fireStarted();
            assertEquals(0.8f, backend.volume, 0.001f);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void seekingAwayFromNaturalEndFadeRestoresFullGain() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("fade-end-seek").toPath();
            AppDirs.setBase(base.toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"title\":\"track\",\"durationMs\":120000,\"filePath\":\"track.mp3\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            FakeAudioBackend backend = new FakeAudioBackend();
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
            controller.setFadeEnabled(true);
            controller.playQueueIndex(0);
            backend.fireStarted();
            waitForVolume(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);

            backend.position = 119500L;
            controller.pump();
            // The fade worker can start late on a busy CI runner. Wait for an
            // observable fade sample instead of assuming one landed within 120 ms.
            waitForVolumeBelow(backend, 0.8f, ASYNC_FADE_TIMEOUT_MS);
            assertTrue(backend.volume < 0.8f);

            controller.seek(1000L);
            assertEquals(0.8f, backend.volume, 0.001f);
            Thread.sleep(550L); // the cancelled old end fade would have reached zero
            assertEquals(0.8f, backend.volume, 0.001f);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void unifiedSearchRowsKeepSourceMenuIdentity() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("search-menu").toPath();
            AppDirs.setBase(base.toString());
            controller = new PlayerController(new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);

            NeteaseSong netease = new NeteaseSong();
            netease.id = 42L;
            netease.name = "network";
            netease.artistId = 7L;
            netease.artistIdsCsv = "7,8";
            netease.artistNamesCsv = "first\u0001second";
            Track local = new Track();
            local.title = "local";
            local.filePath = "/music/local.flac";
            CustomSong custom = new CustomSong();
            custom.id = "external-7";
            custom.name = "custom";

            controller.searchResults.set(Arrays.asList(netease));
            controller.localSearchResults.set(Arrays.asList(local));
            controller.customSearchResults.set(Arrays.asList(custom));
            controller.rebuildSearchRows();

            java.util.List<SearchRow> rows = controller.searchRows.peek();
            assertEquals(3, rows.size());
            assertTrue(rows.get(0).menuEnabled);
            assertEquals(42L, rows.get(0).id);
            assertEquals(7L, rows.get(0).artistId);
            assertEquals("7,8", rows.get(0).artistIdsCsv);
            assertEquals("first\u0001second", rows.get(0).artistNamesCsv);
            assertTrue(rows.get(1).menuEnabled);
            assertEquals("/music/local.flac", rows.get(1).filePath);
            assertTrue(rows.get(2).menuEnabled);
            assertEquals("external-7", rows.get(2).customId);

            controller.addCustomApiToCustomPlaylist("external-7");
            assertTrue(controller.isCustomApiInCustomPlaylist("external-7"));
            assertEquals(Track.Source.CUSTOM_API,
                    controller.customPlaylistTracks.peek().get(0).source);
            controller.removeCustomApiFromCustomPlaylist("external-7");
            assertFalse(controller.isCustomApiInCustomPlaylist("external-7"));
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void convertsLegacyTextSearchHistoryToVersionedJson() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("search-history").toPath();
            AppDirs.setBase(base.toString());
            Path legacy = base.resolve("search_history.txt");
            Files.write(legacy, " first \nsecond\nfirst\n".getBytes(StandardCharsets.UTF_8));

            controller = new PlayerController(new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);
            long deadline = System.currentTimeMillis() + 2000L;
            while (controller.searchHistory.peek().size() < 2
                    && System.currentTimeMillis() < deadline) {
                controller.pump();
                Thread.sleep(10L);
            }
            controller.pump();

            assertEquals(Arrays.asList("first", "second"), controller.searchHistory.peek());
            Path json = AppDirs.stateFile("search-history.json");
            assertTrue(Files.isRegularFile(json));
            String saved = new String(Files.readAllBytes(json), StandardCharsets.UTF_8);
            assertTrue(saved.contains("\"version\":1"));
            assertTrue(saved.contains("\"items\":[\"first\",\"second\"]"));
            assertFalse(Files.exists(legacy));
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void changingSearchTextImmediatelyDropsPreviousMixedSourceRows() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("search-generation").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);

            NeteaseSong netease = new NeteaseSong();
            netease.id = 1L;
            Track local = new Track();
            local.filePath = "/music/old.flac";
            CustomSong custom = new CustomSong();
            custom.id = "old-custom";
            controller.searchResults.set(Arrays.asList(netease));
            controller.localSearchResults.set(Arrays.asList(local));
            controller.customSearchResults.set(Arrays.asList(custom));
            controller.rebuildSearchRows();
            assertEquals(3, controller.searchRows.peek().size());

            controller.prepareSearch("new keyword");

            assertTrue(controller.searchResults.peek().isEmpty());
            assertTrue(controller.localSearchResults.peek().isEmpty());
            assertTrue(controller.customSearchResults.peek().isEmpty());
            assertTrue(controller.searchRows.peek().isEmpty());
            assertEquals(Integer.valueOf(0), controller.resultCount.peek());
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    /**
     * Round 19 at the boundary: <b>the incoming track's own rendered edit decides the kind</b>,
     * and the decision is taken from the file the deck will really play.
     *
     * <p>The pair here is the one round 17 answers {@link TransitionKind#FADE_OUT_IN} for — two
     * measured grids that cannot be brought together (120 against 145BPM is x1.2083, outside the
     * x1.08 clamp, and not a harmonic relative of it), i.e. the pair the user hears as 「淡入淡出」.
     * The two halves of the test differ in exactly one thing: whether the fusion edit is on disk
     * at the name the boundary looks the edit up by. With it, the boundary must take the
     * overlapping branch — which is also the only branch that plays that file at all
     * ({@code resolveIncomingSource} asks {@code transitionKind.overlapping()}), and the decision
     * line prints a curve only for one. Without it, nothing about the answer may change.
     */
    @Test
    public void aFusionEditDecidesTheKindForAPairTheTempoRuleWouldFade() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        try {
            String withEdit = decisionLineForBoundary(true);
            // The decision's own kind token, not the whole line: round 30's reason text quotes the
            // sequential fade by name (it is the answer the rule overrides), so a substring test over
            // the line would read its own explanation as a decision.
            assertTrue("with a fusion edit the pair must not be faded out and in: " + withEdit,
                    withEdit.contains("slot 0 -> 1: CROSSFADE"));
            assertTrue("the decision line must name the passage it was decided from, and say that"
                            + " it won over the pair's own tempo rule (round 30's wording, which"
                            + " covers the slam for the same bit — see"
                            + " HeuristicTransitionChooser.aSlamEditDecidesTheKindForAPairInNoRelationAtAll): "
                            + withEdit,
                    withEdit.contains("STEM PASSAGE") && withEdit.contains("THE EDIT WON"));
            assertTrue("the curve is printed only for an OVERLAPPING kind, which is the arming"
                            + " gate's own condition (resolveIncomingSource): " + withEdit,
                    withEdit.contains("curve=" + FadeCurve.FUSION));
            assertTrue("the overlap is a blend, not a seam: " + withEdit,
                    withEdit.contains("重叠=") && !withEdit.contains("重叠=none"));

            String noEdit = decisionLineForBoundary(false);
            assertTrue("with no rendered edit the same pair is played one after the other: "
                    + noEdit, noEdit.contains("FADE_OUT_IN"));
            assertFalse("and nothing about that answer mentions the fusion path: " + noEdit,
                    noEdit.contains("FUSION"));
            System.out.println("fusion edit on disk:  " + withEdit);
            System.out.println("no edit at all:       " + noEdit);
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    /**
     * Round 20, report two, first half: <b>the user's 过渡时长 governs an ordinary blend even when
     * there is no rendered edit for the incoming track.</b>
     *
     * <p>Until this round a boundary with no edit was cut to a fixed 8 000 ms ({@code DEGRADED}),
     * whatever the setting said, because the incoming deck then plays its track's own master and its
     * voice is in the blend. That cap was written against the pre-round-17 shape, which held the
     * outgoing track at its own level until three quarters of the window; the shape that ships takes
     * it 10 dB down within three tenths and to the floor by 75.2%, so the cap no longer buys what it
     * was written for — and the listener's 「过渡长短并没有按照设置中的滑动条来」 is what it cost.
     * The note stays; the length is the setting's.
     */
    @Test
    public void anUneditedBlendKeepsTheUsersLengthAndSaysSo() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        try {
            String line = decisionLineForOrdinaryPair(false, -1L);
            System.out.println("no edit for the incoming track: " + line);
            assertTrue("the boundary must still blend: " + line, line.contains("CROSSFADE"));
            assertTrue("the blend must be the user's own length, not the old 8000ms cap: " + line,
                    line.contains("重叠=long " + ORDINARY_BLEND_MS + "ms"));
            assertTrue("... and the old cap must be gone from the line: " + line,
                    !line.contains("重叠=medium 8000ms"));
            assertTrue("the decision must still say the incoming track's voice is in it: " + line,
                    line.contains("DEGRADED: no DJ edit"));
            assertTrue("... and must name what the shape does about it: " + line,
                    line.contains("10dB down"));
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    /**
     * Round 20, report two, second half: <b>a trim stands only when the outgoing track's measured
     * dead air is longer than the stretch of the user's blend the DJ shape spends at the floor.</b>
     * The two device numbers, on one pair and one setting:
     * <ul>
     *   <li>a 3 140 ms tail against a 17 s 过渡时长: the shape is at its floor from 12 785 ms of the
     *       ramp, so the tail sits inside the 4 215 ms it has already spent leaving — a blend never
     *       reaches that silence, and trimming there spends the user's setting on a silence nobody
     *       would have heard;</li>
     *   <li>a 5 300 ms tail against the same setting: the outgoing track's music stops while the
     *       shape is still at −10…−45 dB, so the trim is doing its job and the seam stays.</li>
     * </ul>
     */
    @Test
    public void aTrimStandsOnlyWhenABlendWouldRunIntoTheDeadAir() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        try {
            String shortTail = decisionLineForOrdinaryPair(false, 3_140L);
            System.out.println("3140ms of tail silence, a " + ORDINARY_BLEND_MS + "ms blend: "
                    + shortTail);
            assertTrue("a tail inside the shape's own exit must not buy a 250ms seam: " + shortTail,
                    shortTail.contains("CROSSFADE"));
            assertTrue("... and the line must say why the trim was not taken: " + shortTail,
                    shortTail.contains("does not apply to this setting"));

            String longTail = decisionLineForOrdinaryPair(false, 5_300L);
            System.out.println("5300ms of tail silence, a " + ORDINARY_BLEND_MS + "ms blend: "
                    + longTail);
            assertTrue("a tail longer than the shape's own exit is exactly what the trim is for: "
                    + longTail, longTail.contains("SILENCE_TRIM"));
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    /** The 过渡时长 these tests set and assert against, in one place so a change to it cannot make
     *  an assertion vacuous without a compile-visible edit here. 17 s is the setting the listener's
     *  report was written against. */
    private static final long ORDINARY_BLEND_MS = 17_000L;

    /**
     * One decision line for an ORDINARY pair — two grids the tempo lock can bring together, both
     * tracks long enough that neither the short-track rule nor the length rules touch them — with
     * no rendered edit, optionally with the outgoing track measured to end in {@code tailMs} of
     * silence. Everything else is the scaffolding {@link #decisionLineForBoundary} uses: real
     * controller methods, a fake audio backend, and a stem renderer that renders nothing.
     */
    private String decisionLineForOrdinaryPair(boolean withEdit, long outgoingTailMs)
            throws Exception {
        Path base = temporaryFolder.newFolder("ordinary-" + outgoingTailMs + "-" + withEdit)
                .toPath();
        AppDirs.setBase(base.toString());
        AppDirs.setCacheBase(base.resolve("cache").toString());
        Files.write(base.resolve("queue.json"), ("{\"playIndex\":0,\"positionMs\":0,"
                + "\"playMode\":0,\"tracks\":["
                + "{\"source\":\"NETEASE\",\"neteaseId\":11,\"title\":\"outgoing\","
                + "\"durationMs\":150000},"
                + "{\"source\":\"NETEASE\",\"neteaseId\":22,\"title\":\"incoming\","
                + "\"durationMs\":150000}]}").getBytes(StandardCharsets.UTF_8));
        Logger.clear();
        FakeAudioBackend backend = new FakeAudioBackend();
        PlayerController controller =
                new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
        try {
            controller.setStemEditRenderer(new FakeStemEditRenderer());
            controller.setBlendDurationMs(ORDINARY_BLEND_MS);
            // 120 against 121BPM: the lock pulls the second onto the first, so this pair is the
            // ordinary overlapping one (the clashing 120/145 pair is the other test's business).
            writeBeatProfile(controller, 11L, 120.0d);
            writeBeatProfile(controller, 22L, 121.0d);
            writeCachedAudio(controller, 11L);
            writeCachedAudio(controller, 22L);
            if (outgoingTailMs >= 0L) writeSilenceProfile(controller, 11L, outgoingTailMs);
            controller.playQueueIndex(0);
            // Inside the decision lead, with room for the longest plan (the lead is ~45s).
            backend.position = backend.duration() - 40_000L;
            controller.pump();
            StringBuilder lines = new StringBuilder();
            for (String line : Logger.snapshot()) {
                if (line.contains("transition: slot 0 -> 1:")) lines.append(line).append('\n');
            }
            assertTrue("no boundary was decided at all — this test's setup is wrong, not the rule: "
                    + Logger.snapshot(), lines.length() > 0);
            return lines.toString();
        } finally {
            controller.shutdown();
        }
    }

    /** The outgoing track's measured ending: {@code tailMs} of silence and nothing else measured
     *  (no head silence, no plain stretch), so neither the deck's entry nor the plain-ending rule
     *  moves the blend these tests are about. */
    private static void writeSilenceProfile(PlayerController controller, long neteaseId,
                                            long tailMs) throws Exception {
        File file = new File(controller.diskCache.silencePath("n" + neteaseId));
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), new SilenceProfile(0L, tailMs, 0L, 0).toBytes());
    }

    /**
     * The first of the three edit-identity guards, and the one that needs no platform: the base a
     * pair looks its edit up by is {@code abs(hashCode)} as DIGITS, the lookup is a directory scan,
     * and a plain prefix test therefore hands a pair whose hash is a prefix of another pair's the
     * OTHER pair's file — which, since an edit is the incoming track's whole audio, is the reported
     * 「到B的时候变成其他歌了」 heard as a different song.
     */
    @Test
    public void aFileNameIsThisPairsEditOnlyWhenTheHashEndsThere() {
        assertTrue(PlayerController.djEditNameBelongsTo("123456", "123456-v16000.m4a"));
        assertTrue(PlayerController.djEditNameBelongsTo("123456", "123456-e1-j2-f3.m4a"));
        assertTrue("the bare name is the plain edit's own",
                PlayerController.djEditNameBelongsTo("123456", "123456"));
        assertFalse("a LONGER hash that begins with these digits is another pair's file",
                PlayerController.djEditNameBelongsTo("123456", "1234567-v16000.m4a"));
        assertFalse(PlayerController.djEditNameBelongsTo("123456", "123456.m4a"));
        assertFalse(PlayerController.djEditNameBelongsTo("123456", "12345-v16000.m4a"));
        assertFalse(PlayerController.djEditNameBelongsTo("123456", "654321-v16000.m4a"));
        assertFalse(PlayerController.djEditNameBelongsTo(null, "123-v16000.m4a"));
        assertFalse(PlayerController.djEditNameBelongsTo("123", null));
    }

    /**
     * The second guard: the times an edit's name carries are positions in the files of the pair it
     * was rendered for — the incoming's, except the junction, which is the OUTGOING track's own bar
     * line. A number at or past the end of the file it belongs to says the edit describes other
     * tracks.
     *
     * <p>⚠️ The last two assertions are the device's own failure: a slam edit for a 98.2 s incoming
     * and a 179.9 s outgoing carries {@code -j164257}, which is a perfectly ordinary position in
     * the outgoing's file and impossible in the incoming's. Judged as one timeline it reads as "a
     * file for another track" and the edit is thrown away — which is exactly what happened to the
     * first stem edit this project ever wrote.
     */
    @Test
    public void anEditsAnchorsHaveToFitInsideTheFilesTheyBelongTo() {
        Track incoming = new Track();
        incoming.source = Track.Source.NETEASE;
        incoming.neteaseId = 22L;
        incoming.title = "incoming";
        incoming.durationMs = 98_200L;
        Track outgoing = new Track();
        outgoing.source = Track.Source.NETEASE;
        outgoing.neteaseId = 11L;
        outgoing.title = "outgoing";
        outgoing.durationMs = 179_885L;
        assertTrue("a slam edit of this pair is inside both files (the device's own numbers)",
                PlayerController.editAnchorsFitDuration(incoming, outgoing, -1L, 16_203L, 1_895L,
                        164_257L, 4_019L));
        assertTrue("the same junction with the outgoing's length unknown checks nothing",
                PlayerController.editAnchorsFitDuration(incoming, null, -1L, 16_203L, 1_895L,
                        164_257L, 4_019L));
        assertTrue("a plain edit carries no anchors at all (-1), which is not a mismatch",
                PlayerController.editAnchorsFitDuration(incoming, outgoing, -1L, -1L, -1L, -1L, -1L));
        assertFalse("a vocal return past the INCOMING's end belongs to a longer track",
                PlayerController.editAnchorsFitDuration(incoming, outgoing, -1L, 130_000L, -1L, -1L,
                        -1L));
        assertFalse("an entry past the incoming's end too",
                PlayerController.editAnchorsFitDuration(incoming, outgoing, -1L, -1L, 98_200L, -1L,
                        -1L));
        assertFalse("and a junction past the OUTGOING's end is not this pair's either",
                PlayerController.editAnchorsFitDuration(incoming, outgoing, -1L, -1L, -1L, 179_885L,
                        -1L));
        Track unmeasured = new Track();
        unmeasured.title = "no length known";
        assertTrue("a track whose length nobody knows checks nothing (the platform's own reading of"
                        + " the armed file is the decisive guard)",
                PlayerController.editAnchorsFitDuration(unmeasured, unmeasured, -1L, 999_999L, -1L,
                        -1L, -1L));
    }

    /**
     * The decisive guard, end to end through the running controller: the incoming deck is armed with
     * a rendered edit, the platform reports the armed file's length, and a file that is not this
     * track's is refused <b>before a single gain is written</b> — no ramp, the incoming player
     * dropped, and the boundary given up so the ordinary switch opens the right track.
     */
    @Test
    public void anEditThatIsNotThisTracksAudioIsRefusedBeforeTheRamp() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        try {
            String wrong = boundaryWithFusionEdit(60_000L, 10_000L);
            assertTrue("the wrong file must be named with both numbers: " + wrong,
                    wrong.contains("WRONG FILE") && wrong.contains("60000")
                            && wrong.contains("120000"));
            assertTrue("... and the boundary given up rather than faded: " + wrong,
                    wrong.contains("so this boundary takes the ordinary switch"));
            assertFalse("... with no gain written at all: " + wrong, wrong.contains("ramping"));

            String right = boundaryWithFusionEdit(120_000L, 10_000L);
            assertTrue("a file that IS this track's audio passes: " + right,
                    right.contains("the edit-identity guard passed"));
            assertTrue("... and the ramp runs: " + right, right.contains("ramping"));
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    /**
     * One boundary, driven to the ramp, with the incoming deck armed from the pair's fusion edit and
     * the fake platform reporting {@code armedDurationMs} for the file it opened. Returns the whole
     * log so a test can assert on what was said (and on what was not: no ramp line means no gain was
     * ever handed to the backend).
     */
    private String boundaryWithFusionEdit(long armedDurationMs, long fromEndMs) throws Exception {
        Path base = temporaryFolder.newFolder("identity-" + armedDurationMs + "-" + fromEndMs).toPath();
        AppDirs.setBase(base.toString());
        AppDirs.setCacheBase(base.resolve("cache").toString());
        Files.write(base.resolve("queue.json"), ("{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,"
                + "\"tracks\":["
                + "{\"source\":\"NETEASE\",\"neteaseId\":11,\"title\":\"outgoing\","
                + "\"durationMs\":120000},"
                + "{\"source\":\"NETEASE\",\"neteaseId\":22,\"title\":\"incoming\","
                + "\"durationMs\":120000}]}").getBytes(StandardCharsets.UTF_8));
        Logger.clear();
        FakeAudioBackend backend = new FakeAudioBackend();
        PlayerController controller =
                new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
        try {
            controller.setStemEditRenderer(new FakeStemEditRenderer());
            writeBeatProfile(controller, 11L, 120.0d);
            writeBeatProfile(controller, 22L, 145.0d);
            writeCachedAudio(controller, 11L);
            writeCachedAudio(controller, 22L);
            writeFusionEdit(controller, 11L, 22L);
            controller.playQueueIndex(0);
            backend.prepareIncomingOk = true;
            backend.incomingDurationMs = armedDurationMs;
            backend.position = backend.duration() - fromEndMs;
            // ⚠️ Wait for the LAST line of the sequence the case is about, not the first: Logger
            // drains on its own thread, so a snapshot taken the instant a line appears can be
            // missing the line that was written immediately after it (the give-up that follows the
            // refusal), which is exactly what a test asserts on.
            String wanted = armedDurationMs == backend.duration()
                    ? "ramping" : "the ordinary switch";
            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline) {
                controller.pump();
                boolean done = false;
                for (String line : Logger.snapshot()) {
                    if (line.contains(wanted)) done = true;
                }
                if (done) break;
                Thread.sleep(20L);
            }
            StringBuilder all = new StringBuilder();
            for (String line : Logger.snapshot()) all.append(line).append('\n');
            return all.toString();
        } finally {
            controller.shutdown();
        }
    }

    /**
     * One boundary's decision line, decided by the running controller, with or without a rendered
     * FUSION edit for the incoming track — the whole path the change lives on: the controller
     * stats the edit ({@code noteWithoutEdit} already did, at this same instant), hands the fact to
     * the chooser, and the chooser answers the kind.
     *
     * <p>The two tracks have measured grids that clash, their audio is "on disk" so the play path
     * serves it from the cache and never resolves anything, and the boundary is put inside the
     * decision window by moving the fake backend's position. Everything is a real method on the
     * controller; nothing is stubbed except the audio backend and the (absent) stem renderer.
     */
    private String decisionLineForBoundary(boolean withFusionEdit) throws Exception {
        Path base = temporaryFolder.newFolder(withFusionEdit ? "fusion-edit" : "no-edit").toPath();
        AppDirs.setBase(base.toString());
        AppDirs.setCacheBase(base.resolve("cache").toString());
        Files.write(base.resolve("queue.json"), ("{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,"
                + "\"tracks\":["
                + "{\"source\":\"NETEASE\",\"neteaseId\":11,\"title\":\"outgoing\","
                + "\"durationMs\":120000},"
                + "{\"source\":\"NETEASE\",\"neteaseId\":22,\"title\":\"incoming\","
                + "\"durationMs\":120000}]}").getBytes(StandardCharsets.UTF_8));
        Logger.clear();
        FakeAudioBackend backend = new FakeAudioBackend();
        PlayerController controller =
                new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE);
        try {
            controller.setStemEditRenderer(new FakeStemEditRenderer());
            writeBeatProfile(controller, 11L, 120.0d);
            writeBeatProfile(controller, 22L, 145.0d);
            writeCachedAudio(controller, 11L);
            writeCachedAudio(controller, 22L);
            if (withFusionEdit) writeFusionEdit(controller, 11L, 22L);
            controller.playQueueIndex(0);
            // Inside the decision lead, with room for the longest plan (the lead is ~45s).
            backend.position = backend.duration() - 20_000L;
            controller.pump();
            StringBuilder lines = new StringBuilder();
            for (String line : Logger.snapshot()) {
                if (line.contains("transition: slot 0 -> 1:")) lines.append(line).append('\n');
            }
            assertTrue("no boundary was decided at all — the test's setup is wrong, not the"
                    + " rule: " + Logger.snapshot(), lines.length() > 0);
            return lines.toString();
        } finally {
            controller.shutdown();
        }
    }

    /** A measured grid for one track, written where {@code beatProfileOf} reads it. */
    private static void writeBeatProfile(PlayerController controller, long neteaseId, double bpm)
            throws Exception {
        File file = new File(controller.diskCache.beatPath("n" + neteaseId));
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), new BeatProfile(bpm, 0L, 0.8f).toBytes());
    }

    /** The track's audio in the cache, so {@code playAt} plays it from disk and the test never
     *  resolves anything (a NETEASE track is the only kind a transition may overlap). */
    private static void writeCachedAudio(PlayerController controller, long neteaseId)
            throws Exception {
        File file = new File(controller.diskCache.audioPath(neteaseId));
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), new byte[4096]);
    }

    /**
     * The render the boundary is supposed to obey: a fusion edit at the name it looks one up by
     * — the incoming track's key, the window it was rendered for, the outgoing track's key — with
     * the three anchors the render baked into the name ({@code -e} entry, {@code -j} junction,
     * {@code -f} fusion end; {@code PlayerController.EditRef.isFusion}).
     */
    private static void writeFusionEdit(PlayerController controller, long outgoingId,
                                        long incomingId) throws Exception {
        // ⚠️ The key the boundary looks an edit up by carries the fusion's own RULE_VERSION (see
        // PlayerController.djEditKey): a file written under an older rule set is invisible on
        // purpose, so a test that means to be found has to write the versioned name.
        String key = PlayerController.djEditKey("n" + incomingId + "@" + controller
                .blendDurationMs() + "|n" + outgoingId);
        File dir = new File(controller.diskCache.djEditDir());
        dir.mkdirs();
        Files.write(new File(dir, controller.diskCache.djEditBaseName(key)
                + "-v16000-e1200-j100000-f106000.m4a").toPath(), new byte[]{0, 1, 2, 3});
        // ⚠️ And the file a re-render leaves BESIDE it: the plain edit the same pair used to have
        // (this is what a pair re-rendered from a plain edit into a fusion one looks like on disk —
        // the renderer writes a different NAME, it does not overwrite). The lookup must prefer the
        // fusion one whichever order the directory lists them in.
        Files.write(new File(dir, controller.diskCache.djEditBaseName(key)
                + "-x1-v16000.m4a").toPath(), new byte[]{0, 1, 2, 3});
    }

    /** No stem path in a unit test's host: it can render nothing, which is the ordinary "there is
     *  no edit for this pair" case (a {@code null} answer, see {@code StemEditRenderer.render}). */
    private static final class FakeStemEditRenderer implements StemEditRenderer {
        @Override public boolean available() {
            return true;
        }

        @Override public Result render(Request request) {
            return null;
        }
    }

    private static final class FakeAudioBackend implements AudioBackend {
        // volatile: written on the fade-tick worker thread, read from the test
        // thread. playCalls/pauseCalls/resumeCalls used to be plain ints -- a
        // volatile write to `volume` earlier in the same tick (see
        // applyEffectiveVolume) gives no JMM guarantee that a later plain write on
        // that same thread (this pauseCalls++) is visible yet, so a test polling
        // only `volume` via waitForVolume could observe silence before the
        // deferred pause() had actually landed.
        volatile int playCalls;
        volatile String lastSource;
        volatile int pauseCalls;
        volatile int resumeCalls;
        volatile int cancelAutoResumeCalls;
        volatile boolean playing;
        long position;
        Runnable onStarted;
        volatile float volume = 0.8f;
        volatile int volumeWrites;
        /** Whether a second player can be prepared at all; false is the interface's own default, so
         *  every test that does not set this behaves exactly as before the edit-identity work. */
        volatile boolean prepareIncomingOk;
        /** What {@link #incomingDuration()} answers: -1 (the default) is "this platform cannot
         *  measure", which is what the guard is documented to skip on. A test that means to catch a
         *  wrong file sets it to the length the fake's prepared player reports. */
        volatile long incomingDurationMs = -1L;
        /** How many times the ramp was handed to the backend — the guard test asserts this stays 0
         *  when the armed file is refused. */
        volatile int crossfadeCalls;

        @Override public void play(String source, long startMs) {
            lastSource = source;
            playCalls++;
            position = startMs;
            playing = true;
        }

        @Override public void pause() { pauseCalls++; playing = false; }
        @Override public void cancelAutoResume() { cancelAutoResumeCalls++; }

        @Override public void resume() {
            resumeCalls++;
            playing = true;
        }

        @Override public boolean isPlaying() { return playing; }
        @Override public void seek(long ms) { position = ms; }
        @Override public long position() { return position; }
        @Override public long duration() { return 120000L; }
        @Override public void setVolume(float volume) {
            this.volume = volume;
            volumeWrites++;
        }
        @Override public void setOnComplete(Runnable callback) { }
        @Override public void setOnStarted(Runnable callback) { onStarted = callback; }
        @Override public void release() { playing = false; }

        /** Only when a test asks for it: the arm's own "the backend accepted the source" answer. */
        @Override public boolean prepareIncoming(String source, long startMs, boolean startMuted,
                                                 IncomingMix mix) {
            return prepareIncomingOk;
        }

        /** What a real platform reads off the file it opened; -1 here means "cannot measure". */
        @Override public long incomingDuration() { return incomingDurationMs; }

        @Override public boolean beginCrossfade(long ms, FadeCurve curve) {
            crossfadeCalls++;
            return true;
        }

        void fireStarted() {
            if (onStarted != null) onStarted.run();
        }
    }

    private static void waitForVolume(FakeAudioBackend backend, float target,
                                      long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (Math.abs(backend.volume - target) > 0.001f
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }

    private static void waitForVolumeBelow(FakeAudioBackend backend, float upperBound,
                                           long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (backend.volume >= upperBound && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }

    @Test
    public void restoredQueueKeepsAllArtistCredits() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        PlayerController controller = null;
        try {
            Path base = temporaryFolder.newFolder("restore-artist-credits").toPath();
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            String queue = "{\"playIndex\":0,\"positionMs\":0,\"playMode\":0,\"tracks\":["
                    + "{\"source\":\"LOCAL\",\"neteaseId\":42,\"title\":\"song\","
                    + "\"artist\":\"first / second\",\"artistId\":7,"
                    + "\"artistIdsCsv\":\"7,8\",\"artistNamesCsv\":\"first\\u0001second\","
                    + "\"durationMs\":120000,\"filePath\":\"song.flac\"}]}";
            Files.write(base.resolve("queue.json"), queue.getBytes(StandardCharsets.UTF_8));

            controller = new PlayerController(
                    new FakeAudioBackend(), track -> { }, NeteaseClient.INSTANCE);
            controller.pump();

            Track restored = controller.queueTracks.peek().get(0);
            assertEquals(7L, restored.artistId);
            assertEquals("7,8", restored.artistIdsCsv);
            assertEquals("first\u0001second", restored.artistNamesCsv);
        } finally {
            if (controller != null) controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    /**
     * ⚠️ Round 6's shadowing fix, as a decision table. A plain DJ edit on disk is played rather than
     * re-rendered — the contract the whole edit cache exists for — and the device paid for it: the
     * pair {@code AGUDO -> Lose My Mind} was refused by the entry search while the renderer handed
     * the incoming's bar lines over in the wrong unit, its plain edit was written, the beat probe
     * then healed the incoming's grid, and the boundary kept playing that plain file. The pair only
     * fused-attempted after the file was deleted by hand.
     *
     * <p>The renderer records WHY it is plain ({@code -x<code>} in the name, see
     * {@code AndroidStemEditRenderer}'s WHY_*): 1 and 2 are the missing-grid refusals, which a later
     * profile supplies. Those two are re-rendered, and nothing else is — a refusal the files
     * themselves decided answers the same way on a re-render, so re-rendering it would be a render
     * per boundary for nothing.
     */
    @Test
    public void aPlainEditIsReRenderedOnlyWhenItsRefusalWasAMissingGrid() {
        String base = "1234";
        // The names carry the CURRENT rule version: without it every file is stale by definition
        // (see anAcceptanceRefusalFromAnOlderRuleSetIsStale), which is what makes these cases about
        // the grid codes at all.
        String v = "-r" + dev.t1m3.qplayer.audio.StemFusion.RULE_VERSION;
        BeatProfile known = new BeatProfile(120d, 0L, 0.8f);
        assertEquals("the incoming's grid was missing and it is here now", 1,
                PlayerController.staleGridRefusal(base + "-x1-v16000" + v + ".m4a", base, known,
                        known));
        assertEquals("and not when it is still missing", 0,
                PlayerController.staleGridRefusal(base + "-x1-v16000" + v + ".m4a", base, null,
                        known));
        assertEquals("the outgoing's grid was missing and it is here now", 2,
                PlayerController.staleGridRefusal(base + "-x2-v16000" + v + ".m4a", base, known,
                        known));
        assertEquals("and not when it is still missing", 0,
                PlayerController.staleGridRefusal(base + "-x2-v16000" + v + ".m4a", base, known,
                        null));
        assertEquals("the pre-decode clause for another reason: the files decide that, not a grid",
                0, PlayerController.staleGridRefusal(base + "-x3-v16000" + v + ".m4a", base, known,
                        known));
        assertEquals("the plan refused: the material decides that", 0,
                PlayerController.staleGridRefusal(base + "-x4-v16000" + v + ".m4a", base, known,
                        known));
        assertEquals("the render's own acceptance refused, under THIS rule set: the loop is"
                        + " deterministic, so that verdict stands until the rule changes", 0,
                PlayerController.staleGridRefusal(base + "-x5-v16000" + v + ".m4a", base, known,
                        known));
        assertEquals("a FUSION edit has nothing to re-decide", 0,
                PlayerController.staleGridRefusal(
                        base + "-v16000-e1200-j100000-f106000" + v + ".m4a", base, known, known));
        assertEquals("an edit that never asked to fuse stands", 0,
                PlayerController.staleGridRefusal(base + "-v16000" + v + ".m4a", base, known,
                        known));
        assertEquals("and an old plain edit (no marker at all) is stale by definition",
                PlayerController.STALE_LEGACY,
                PlayerController.staleGridRefusal(base + ".m4a", base, known, known));
    }

    /** The fusion's own rule version is part of the cache key, so an edit rendered under an older
     *  rule set is invisible to the lookup and gets rendered once more — that is what retires the
     *  device's plain file (see {@link dev.t1m3.qplayer.audio.StemFusion#RULE_VERSION}). */
    @Test
    public void theFusionRuleVersionIsPartOfTheEditKey() {
        String key = "n22@15000|n11";
        assertFalse("the versioned key must not hash to the unversioned name",
                PlayerController.djEditKey(key).equals(key));
        assertTrue(PlayerController.djEditKey(key),
                PlayerController.djEditKey(key).endsWith("#r"
                        + dev.t1m3.qplayer.audio.StemFusion.RULE_VERSION));
        assertEquals("and it is stable for one rule set",
                PlayerController.djEditKey(key), PlayerController.djEditKey(key));
    }

    /**
     * ⚠️ And the belt that catches an edit whose name predates the version: the device had
     * {@code 1071493184-v17045.m4a} — no {@code -xN}, no version marker — and `requestStemEdit`
     * treated it as today's finished edit, so no render was ever attempted for that direction until
     * the file was deleted by hand. A name with no {@code -r<current>} is stale by definition.
     */
    /**
     * ⚠️ And the case that cost two manual deletions: a file the render's own acceptance refused
     * ({@code -x5}), stamped with an OLDER rule version. `staleGridRefusal` keeps codes 3/4/5 —
     * within a version, because the loop is deterministic and bounded, so the same inputs give the
     * same attempts — and it is the version that makes such a file re-renderable, because with the
     * wait's retry loop an acceptance refusal is a measurement of one bounded set of attempts, not a
     * property of the pair. The device's own name is the fixture: a later rule set may pass where
     * that one could not, so it must not be able to hide the render.
     */
    @Test
    public void anAcceptanceRefusalFromAnOlderRuleSetIsStale() {
        String base = "942288643";
        String older = "-v18340-x5-r" + (dev.t1m3.qplayer.audio.StemFusion.RULE_VERSION - 1);
        String current = "-v18340-x5-r" + dev.t1m3.qplayer.audio.StemFusion.RULE_VERSION;
        BeatProfile known = new BeatProfile(120d, 0L, 0.8f);
        assertEquals("the device's own file: refused by an older rule set, so it must re-render",
                PlayerController.STALE_LEGACY,
                PlayerController.staleGridRefusal(base + older + ".m4a", base, known, known));
        assertEquals("while the same refusal under THIS rule set stands: the loop is deterministic,"
                        + " so re-rendering it every boundary would be a render for nothing",
                0, PlayerController.staleGridRefusal(base + current + ".m4a", base, known, known));
        assertEquals("nor is an old PLAIN edit without any marker a verdict",
                PlayerController.STALE_LEGACY,
                PlayerController.staleGridRefusal("1071493184-v17045.m4a", "1071493184", known,
                        known));
        // The grid refusals keep their own meaning, under the current version.
        assertEquals("the incoming's grid was missing and it is measured now", 1,
                PlayerController.staleGridRefusal(base + "-x1" + current + ".m4a", base, known,
                        known));
        assertEquals("and the outgoing's", 2,
                PlayerController.staleGridRefusal(base + "-x2" + current + ".m4a", base, known,
                        known));
    }

    @Test
    public void anEditWithoutTheRuleVersionIsStale() {
        assertTrue(PlayerController.carriesRuleVersion(
                "1234-v16000-r" + dev.t1m3.qplayer.audio.StemFusion.RULE_VERSION + ".m4a"));
        assertFalse("a legacy name has no marker at all",
                PlayerController.carriesRuleVersion("1071493184-v17045.m4a"));
        assertFalse("and neither has a name from an older rule set",
                PlayerController.carriesRuleVersion("1234-v16000-r1.m4a"));
    }

    private static void waitForPauseCalls(FakeAudioBackend backend, int target,
                                          long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (backend.pauseCalls < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }
}
