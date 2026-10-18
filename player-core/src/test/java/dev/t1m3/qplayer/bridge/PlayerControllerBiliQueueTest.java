package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.bili.BiliClient;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

public class PlayerControllerBiliQueueTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void experimentalDjPrefetchesOnFirstSongWithoutPausingOrChangingQueue() throws Exception {
        try (Fixture f = fixture()) {
            f.controller.playBiliCollection(video("first-song", 1));
            f.until(() -> f.backend.plays == 1);
            java.lang.reflect.Field mode = PlayerController.class.getDeclaredField("experimentalAiDjMode");
            mode.setAccessible(true); mode.setBoolean(f.controller, true);
            f.controller.experimentalAiDjActive.set(true);
            java.lang.reflect.Method continueBatch = PlayerController.class.getDeclaredMethod("maybeContinueAiPlaylist");
            continueBatch.setAccessible(true);
            // No configured endpoint, so no external AI request is made.
            continueBatch.invoke(f.controller);
            java.lang.reflect.Field started = PlayerController.class.getDeclaredField("experimentalAiDjPrefetchStarted");
            started.setAccessible(true);
            assertTrue(started.getBoolean(f.controller));
            assertTrue(f.backend.playing);
            assertFalse(f.controller.experimentalAiDjWaiting.peek());
            assertEquals(1, f.backend.plays);
            assertEquals("first-song", f.controller.currentTrack().biliBvid);
            continueBatch.invoke(f.controller);
            assertEquals(1, f.backend.plays);
        }
    }

    @Test
    public void experimentalDjBuffersNextBatchAndShowsReasonForFiveSecondsAtBoundary() throws Exception {
        try (Fixture f = fixture()) {
            f.controller.playBiliCollection(video("playing-now", 1));
            f.until(() -> f.backend.plays == 1);
            java.lang.reflect.Field mode = PlayerController.class.getDeclaredField("experimentalAiDjMode");
            mode.setAccessible(true); mode.setBoolean(f.controller, true);
            java.lang.reflect.Field generation = PlayerController.class.getDeclaredField("experimentalAiDjGeneration");
            generation.setAccessible(true); generation.setLong(f.controller, 42L);
            f.controller.experimentalAiDjActive.set(true);
            Class<?> pendingType = Class.forName("dev.t1m3.qplayer.bridge.PlayerController$PendingAiPlayback");
            java.lang.reflect.Constructor<?> constructor = pendingType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            List<dev.t1m3.qplayer.netease.dto.NeteaseSong> songs = new ArrayList<>();
            for (int i = 0; i < 8; i++) songs.add(new dev.t1m3.qplayer.netease.dto.NeteaseSong());
            java.lang.reflect.Field pending = PlayerController.class.getDeclaredField("pendingAiPlayback");
            pending.setAccessible(true); pending.set(f.controller, constructor.newInstance(42L, songs, null));
            f.controller.aiSummary.set("这一组从轻松的旋律走向活力节奏");
            f.controller.aiDetails.set("1. 晴天 — 周杰伦\n推荐原因：延续华语旋律偏好");
            java.lang.reflect.Method finish = PlayerController.class.getDeclaredMethod("finishExperimentalAiDjBatch", long.class);
            finish.setAccessible(true); finish.invoke(f.controller, 42L);
            assertEquals(1, f.backend.plays);
            assertFalse(f.controller.experimentalAiDjWaiting.peek());
            assertEquals("playing-now", f.controller.currentTrack().biliBvid);
            java.lang.reflect.Field readyField = PlayerController.class.getDeclaredField("experimentalAiDjReady");
            readyField.setAccessible(true);
            java.lang.reflect.Field songsField = pendingType.getDeclaredField("songs");
            songsField.setAccessible(true);
            assertEquals(6, ((List<?>) songsField.get(readyField.get(f.controller))).size());
            f.backend.complete.run();
            f.until(() -> f.controller.experimentalAiDjWaiting.peek());
            assertTrue(f.controller.experimentalAiDjReason.peek().startsWith("这一组从轻松的旋律走向活力节奏"));
            assertTrue(f.controller.experimentalAiDjReason.peek().contains("晴天 — 周杰伦"));
            assertTrue(f.controller.experimentalAiDjReason.peek().contains("推荐原因：延续华语旋律偏好"));
            java.lang.reflect.Field deadline = PlayerController.class.getDeclaredField("experimentalAiDjTransitionDeadline");
            deadline.setAccessible(true);
            assertTrue(deadline.getLong(f.controller) - System.nanoTime() > TimeUnit.SECONDS.toNanos(4));
            f.controller.pump();
            assertEquals(1, f.backend.plays);
            f.controller.stopExperimentalAiDj();
            deadline.setLong(f.controller, 0L);
            f.controller.pump();
            assertNull(readyField.get(f.controller));
            assertEquals(1, f.backend.plays);
            assertFalse(f.controller.experimentalAiDjWaiting.peek());
        }
    }

    @Test
    public void experimentalDjDoesNotReplayFinishedBatchOrResumeOldTrackWhileWaiting() throws Exception {
        try (Fixture f = fixture()) {
            f.controller.playBiliCollection(video("batch-last", 1));
            f.until(() -> f.backend.plays == 1);
            java.lang.reflect.Field mode = PlayerController.class.getDeclaredField("experimentalAiDjMode");
            mode.setAccessible(true);
            mode.setBoolean(f.controller, true);
            f.controller.experimentalAiDjActive.set(true);
            // No configured endpoint: exercise the boundary without a network request.
            f.backend.complete.run();
            f.until(() -> f.controller.experimentalAiDjWaiting.peek());
            assertEquals(1, f.backend.plays);
            assertFalse(f.backend.playing);
            f.controller.mediaResume();
            f.controller.pump();
            assertFalse(f.backend.playing);
            assertEquals(1, f.backend.plays);
            // A manual queue choice starts that song without disabling DJ mode.
            f.controller.playQueueIndex(0);
            f.until(() -> f.backend.plays == 2);
            assertTrue(f.controller.experimentalAiDjActive.peek());
            assertFalse(f.controller.experimentalAiDjWaiting.peek());
        }
    }

    @Test
    public void experimentalDjSurvivesQueueReplacementAndIgnoresOldBatch() throws Exception {
        try (Fixture f = fixture()) {
            f.controller.playBiliCollection(video("old-batch", 1));
            f.until(() -> f.backend.plays == 1);
            java.lang.reflect.Field mode = PlayerController.class.getDeclaredField("experimentalAiDjMode");
            mode.setAccessible(true); mode.setBoolean(f.controller, true);
            f.controller.experimentalAiDjActive.set(true);
            f.controller.experimentalAiDjWaiting.set(true);
            java.lang.reflect.Field deadline = PlayerController.class.getDeclaredField("experimentalAiDjTransitionDeadline");
            deadline.setAccessible(true); deadline.setLong(f.controller, 1L);
            Track replacement = new Track();
            replacement.source = Track.Source.BILI; replacement.biliBvid = "manual-choice";
            replacement.biliCid = 2; replacement.title = "manual-choice";
            f.replaceQueue(Collections.singletonList(replacement));
            f.until(() -> f.backend.plays == 2);
            assertTrue(f.controller.experimentalAiDjActive.peek());
            assertFalse(f.controller.experimentalAiDjWaiting.peek());
            assertEquals(-1L, deadline.getLong(f.controller));
            assertEquals("manual-choice", f.controller.currentTrack().biliBvid);
            assertTrue(f.backend.playing);
            f.controller.stopExperimentalAiDj();
            assertFalse(f.controller.experimentalAiDjActive.peek());
        }
    }

    @Test
    public void cancellingForegroundPreviewRestoresExperimentalDjGenerationOwnership() throws Exception {
        try (Fixture f = fixture()) {
            java.lang.reflect.Field mode = PlayerController.class.getDeclaredField("experimentalAiDjMode");
            mode.setAccessible(true); mode.setBoolean(f.controller, true);
            java.lang.reflect.Field suspended = PlayerController.class.getDeclaredField("experimentalAiDjSuspended");
            suspended.setAccessible(true); suspended.setBoolean(f.controller, true);
            java.lang.reflect.Field started = PlayerController.class.getDeclaredField("experimentalAiDjPrefetchStarted");
            started.setAccessible(true); started.setBoolean(f.controller, false);
            f.controller.experimentalAiDjActive.set(true);
            f.controller.experimentalAiDjWaiting.set(true);
            // Unconfigured experimental endpoint prevents external requests.
            f.controller.cancelAiPlaylistGeneration();
            assertFalse(suspended.getBoolean(f.controller));
            assertTrue(started.getBoolean(f.controller));
            assertTrue(f.controller.experimentalAiDjActive.peek());
            assertTrue(f.controller.experimentalAiDjWaiting.peek());
        }
    }

    @Test
    public void experimentalDjRejectsShortBatchAndIgnoresResultAfterExit() throws Exception {
        try (Fixture f = fixture()) {
            java.lang.reflect.Field mode = PlayerController.class.getDeclaredField("experimentalAiDjMode");
            mode.setAccessible(true); mode.setBoolean(f.controller, true);
            java.lang.reflect.Field generation = PlayerController.class.getDeclaredField("experimentalAiDjGeneration");
            generation.setAccessible(true); generation.setLong(f.controller, 12L);
            java.lang.reflect.Field retries = PlayerController.class.getDeclaredField("experimentalAiDjRetries");
            retries.setAccessible(true); retries.setInt(f.controller, 2);
            Class<?> pendingType = Class.forName("dev.t1m3.qplayer.bridge.PlayerController$PendingAiPlayback");
            java.lang.reflect.Constructor<?> constructor = pendingType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            java.util.List<dev.t1m3.qplayer.netease.dto.NeteaseSong> shortBatch = Arrays.asList(
                    new dev.t1m3.qplayer.netease.dto.NeteaseSong(),
                    new dev.t1m3.qplayer.netease.dto.NeteaseSong(),
                    new dev.t1m3.qplayer.netease.dto.NeteaseSong(),
                    new dev.t1m3.qplayer.netease.dto.NeteaseSong());
            java.lang.reflect.Field pending = PlayerController.class.getDeclaredField("pendingAiPlayback");
            pending.setAccessible(true);
            pending.set(f.controller, constructor.newInstance(12L, shortBatch, null));
            f.controller.experimentalAiDjActive.set(true);
            f.controller.experimentalAiDjWaiting.set(true);
            java.lang.reflect.Method finish = PlayerController.class.getDeclaredMethod("finishExperimentalAiDjBatch", long.class);
            finish.setAccessible(true);
            finish.invoke(f.controller, 12L);
            assertTrue(f.controller.experimentalAiDjWaiting.peek());
            assertTrue(f.controller.experimentalAiDjMessage.peek().contains("至少五首"));
            assertEquals(0, f.backend.plays);
            f.controller.stopExperimentalAiDj();
            finish.invoke(f.controller, 12L);
            assertFalse(f.controller.experimentalAiDjActive.peek());
            assertFalse(f.controller.experimentalAiDjWaiting.peek());
            assertEquals(0, f.backend.plays);
        }
    }

    @Test
    public void ordinaryVideoStartsBeforeRelatedAndAppendsWithoutRestartingOrResuming() throws Exception {
        try (Fixture f = fixture()) {
            f.bili.relatedGate = new CountDownLatch(1);
            f.bili.related.put("seed", Arrays.asList(video("seed", 1), video("next", 0),
                    video("next", 2), video("last", 3)));
            f.controller.playBiliCollection(video("seed", 1));
            f.until(() -> f.backend.plays == 1 && f.bili.relatedCalls.get() == 1);
            assertQueue(f, "seed");
            Track seed = f.controller.currentTrack();
            f.backend.position = 27000L;
            f.controller.mediaPause();
            Track manual = new Track();
            manual.title = "manual";
            manual.filePath = "manual.mp3";
            f.controller.enqueueTrack(manual);
            f.bili.relatedGate.countDown();
            f.until(() -> f.controller.queueTracks.peek().size() == 4);
            assertSame(seed, f.controller.currentTrack());
            assertEquals(1, f.backend.plays);
            assertEquals(27000L, f.backend.position);
            assertFalse(f.backend.playing);
            assertSame(manual, f.controller.queueTracks.peek().get(1));
            assertEquals("next", f.controller.queueTracks.peek().get(2).biliBvid);
            assertEquals("last", f.controller.queueTracks.peek().get(3).biliBvid);
            f.controller.saveSessionState();
            assertTrue(new String(Files.readAllBytes(AppDirs.stateFile("queue.json")),
                    StandardCharsets.UTF_8).contains("last"));

            // A recommendation without a cid is resolved only when it is played.
            f.controller.playQueueIndex(2);
            f.until(() -> f.backend.plays == 2);
            assertEquals("next:99", f.backend.source);
            assertEquals(1, f.bili.partCalls.get());
            f.backend.complete.run();
            f.until(() -> f.backend.plays == 3);
            assertEquals("last:3", f.backend.source);
        }
    }

    @Test
    public void multipartQueueKeepsPartOrderAndNeverRequestsRelated() throws Exception {
        try (Fixture f = fixture()) {
            BiliClient.BiliVideoDetails details = details("multi", 11);
            details.parts.add(part(11, "first"));
            details.parts.add(part(22, "second"));
            details.episodes.add(video("other-collection-video", 33));
            f.bili.details.put("multi", details);
            f.controller.playBiliCollection(video("multi", 11));
            f.until(() -> f.backend.plays == 1);
            assertEquals(2, f.controller.queueTracks.peek().size());
            assertEquals("multi · first", f.controller.queueTracks.peek().get(0).title);
            assertEquals(22L, f.controller.queueTracks.peek().get(1).biliCid);
            f.backend.complete.run();
            f.until(() -> f.backend.plays == 2);
            assertEquals("multi:22", f.backend.source);
            assertEquals(0, f.bili.relatedCalls.get());
        }
    }

    @Test
    public void ugcCollectionStartsClickedEpisodeAndKeepsCollectionQueue() throws Exception {
        try (Fixture f = fixture()) {
            BiliClient.BiliVideoDetails details = details("middle", 2);
            details.parts.add(part(2, "single page"));
            details.episodes.addAll(Arrays.asList(video("first", 1), video("middle", 2), video("last", 3)));
            f.bili.details.put("middle", details);
            f.controller.playBiliCollection(video("middle", 2));
            f.until(() -> f.backend.plays == 1);
            assertQueue(f, "first", "middle", "last");
            assertEquals("middle:2", f.backend.source);
            f.controller.next();
            f.until(() -> f.backend.plays == 2);
            assertEquals("last:3", f.backend.source);
            assertEquals(0, f.bili.relatedCalls.get());
        }
    }

    @Test
    public void switchingBiliQueueSourceKeepsCurrentStreamPositionAndPauseState() throws Exception {
        try (Fixture f = fixture()) {
            BiliClient.BiliVideoDetails details = details("middle", 2);
            details.parts.add(part(1, "first"));
            details.parts.add(part(2, "middle"));
            details.parts.add(part(3, "last"));
            details.episodes.addAll(Arrays.asList(video("first", 1), video("middle", 2), video("last", 3)));
            f.bili.details.put("middle", details);
            f.bili.related.put("middle", Arrays.asList(video("related", 4), video("middle", 2)));
            f.controller.playBiliCollection(video("middle", 2));
            f.until(() -> f.backend.plays == 1);
            Track playing = f.controller.currentTrack();
            f.backend.position = 42000L;
            f.controller.mediaPause();

            f.bili.detailsFailure = true;
            f.controller.switchBiliQueueMode("RELATED");
            f.until(() -> "RELATED".equals(f.controller.biliQueueMode.peek())
                    && !Boolean.TRUE.equals(f.controller.biliQueueSwitching.peek()));
            assertSame(playing, f.controller.currentTrack());
            assertQueue(f, "middle", "related");
            assertEquals(42000L, f.backend.position);
            assertFalse(f.backend.playing);
            assertEquals(1, f.backend.plays);

            f.bili.detailsFailure = false;
            f.controller.switchBiliQueueMode("COLLECTION");
            f.until(() -> "COLLECTION".equals(f.controller.biliQueueMode.peek())
                    && !Boolean.TRUE.equals(f.controller.biliQueueSwitching.peek()));
            assertSame(playing, f.controller.currentTrack());
            assertQueue(f, "first", "middle", "last");
            assertEquals(1, f.controller.index.peek().intValue());
            assertEquals(42000L, f.backend.position);
            assertFalse(f.backend.playing);
            assertEquals(1, f.backend.plays);
        }
    }

    @Test
    public void staleDetailsCannotReplaceANewerVideo() throws Exception {
        try (Fixture f = fixture()) {
            f.bili.blockedDetail = "old";
            f.bili.detailGate = new CountDownLatch(1);
            f.controller.playBiliCollection(video("old", 1));
            assertTrue(f.bili.detailEntered.await(3L, TimeUnit.SECONDS));
            f.controller.playBiliCollection(video("new", 2));
            f.until(() -> f.backend.plays == 1);
            assertEquals("new:2", f.backend.source);
            f.bili.detailGate.countDown();
            f.flushWorkers();
            assertQueue(f, "new");
            assertEquals(1, f.backend.plays);
        }
    }

    @Test
    public void newTapCancelsAQueueStartAlreadyWaitingOnMainThread() throws Exception {
        try (Fixture f = fixture()) {
            f.controller.playBiliCollection(video("old", 1));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (f.controller.queueTracks.peek().isEmpty() && System.nanoTime() < deadline) {
                Runnable task = f.main.poll();
                if (task != null) task.run();
                else Thread.sleep(5L);
            }
            assertQueue(f, "old");
            assertEquals(0, f.backend.plays); // playQueue's start is still posted.
            f.controller.playBiliCollection(video("new", 2));
            f.until(() -> f.backend.plays == 1);
            f.flushWorkers();
            assertQueue(f, "new");
            assertEquals("new:2", f.backend.source);
            assertEquals(1, f.backend.plays);
        }
    }

    @Test
    public void lateRecommendationsCannotAppendToNewQueueEvenForSameBvid() throws Exception {
        try (Fixture f = fixture()) {
            f.bili.relatedGate = new CountDownLatch(1);
            f.bili.related.put("seed", Collections.singletonList(video("obsolete", 9)));
            f.controller.playBiliCollection(video("seed", 1));
            f.until(() -> f.backend.plays == 1 && f.bili.relatedCalls.get() == 1);
            Track replacement = new Track();
            replacement.source = Track.Source.BILI;
            replacement.biliBvid = "seed";
            replacement.biliCid = 8;
            replacement.title = "different queue";
            f.replaceQueue(Collections.singletonList(replacement));
            f.until(() -> f.backend.plays == 2);
            f.bili.relatedGate.countDown();
            f.flushWorkers();
            assertSame(replacement, f.controller.currentTrack());
            assertQueue(f, "seed");
            assertEquals("seed:8", f.backend.source);
        }
    }

    @Test
    public void recommendationFailureAndEmptyResultLeaveCurrentPlaybackIntact() throws Exception {
        try (Fixture f = fixture()) {
            f.bili.relatedFailure = true;
            f.controller.playBiliCollection(video("seed", 1));
            f.until(() -> f.backend.plays == 1 && f.bili.relatedCalls.get() == 1);
            f.flushWorkers();
            assertQueue(f, "seed");
            assertTrue(f.backend.playing);
            f.bili.relatedFailure = false;
            f.controller.playBiliCollection(video("empty", 2));
            f.until(() -> f.backend.plays == 2 && f.bili.relatedCalls.get() == 2);
            f.flushWorkers();
            assertQueue(f, "empty");
            assertTrue(f.backend.playing);
        }
    }

    @Test
    public void failedDetailsFallBackToPartsWithoutGuessingRelatedQueue() throws Exception {
        try (Fixture f = fixture()) {
            f.bili.detailsFailure = true;
            f.controller.playBiliCollection(video("seed", 1));
            f.until(() -> f.backend.plays == 1);
            assertQueue(f, "seed");
            assertEquals("seed:99", f.backend.source);
            assertEquals(0, f.bili.relatedCalls.get());
        }
    }

    @Test
    public void bluetoothPauseCancelsPendingOpenAndFavoriteFolderKeepsItsOwnQueue() throws Exception {
        try (Fixture f = fixture()) {
            f.bili.blockedDetail = "pending";
            f.bili.detailGate = new CountDownLatch(1);
            f.controller.playBiliCollection(video("pending", 1));
            assertTrue(f.bili.detailEntered.await(3L, TimeUnit.SECONDS));
            f.controller.mediaPauseForDisconnectedOutput();
            f.bili.detailGate.countDown();
            f.flushWorkers();
            assertEquals(0, f.backend.plays);
            assertTrue(f.controller.queueTracks.peek().isEmpty());

            BiliClient.BiliFavItem first = new BiliClient.BiliFavItem();
            first.bvid = "favorite1"; first.firstCid = 10;
            BiliClient.BiliFavItem second = new BiliClient.BiliFavItem();
            second.bvid = "favorite2"; second.firstCid = 20;
            f.controller.biliFavItems.set(Arrays.asList(first, second));
            f.controller.playBiliFavItemAt(1);
            f.until(() -> f.backend.plays == 1);
            assertQueue(f, "favorite1", "favorite2");
            assertEquals("favorite2:20", f.backend.source);
            assertEquals(0, f.bili.relatedCalls.get());
        }
    }

    @Test
    public void experimentalDjAcceptsFiveSongsWithoutInterruptingTheCurrentTrack() throws Exception {
        try (Fixture f = fixture()) {
            f.controller.playBiliCollection(video("current", 1));
            f.until(() -> f.backend.plays == 1);
            java.lang.reflect.Field mode = PlayerController.class.getDeclaredField("experimentalAiDjMode");
            mode.setAccessible(true); mode.setBoolean(f.controller, true);
            java.lang.reflect.Field generation = PlayerController.class.getDeclaredField("experimentalAiDjGeneration");
            generation.setAccessible(true); generation.setLong(f.controller, 42L);
            Class<?> pendingType = Class.forName("dev.t1m3.qplayer.bridge.PlayerController$PendingAiPlayback");
            java.lang.reflect.Constructor<?> constructor = pendingType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            List<dev.t1m3.qplayer.netease.dto.NeteaseSong> songs = new ArrayList<>();
            for (int i = 0; i < 5; i++) songs.add(new dev.t1m3.qplayer.netease.dto.NeteaseSong());
            java.lang.reflect.Field pending = PlayerController.class.getDeclaredField("pendingAiPlayback");
            pending.setAccessible(true); pending.set(f.controller, constructor.newInstance(42L, songs, null));
            java.lang.reflect.Method finish = PlayerController.class.getDeclaredMethod("finishExperimentalAiDjBatch", long.class);
            finish.setAccessible(true); finish.invoke(f.controller, 42L);
            java.lang.reflect.Field ready = PlayerController.class.getDeclaredField("experimentalAiDjReady");
            ready.setAccessible(true); assertNotNull(ready.get(f.controller));
            assertEquals(1, f.backend.plays);
            assertEquals("current", f.controller.currentTrack().biliBvid);
        }
    }

    @Test
    public void enqueueFromAllSourcesAppendsWithoutRestartingPlaybackOrExitingDj() throws Exception {
        try (Fixture f = fixture()) {
            f.controller.playBiliCollection(video("current", 1));
            f.until(() -> f.backend.plays == 1);
            java.lang.reflect.Field mode = PlayerController.class.getDeclaredField("experimentalAiDjMode");
            mode.setAccessible(true); mode.setBoolean(f.controller, true);
            f.controller.experimentalAiDjActive.set(true);
            f.backend.position = 27000L;
            f.controller.mediaPause();
            Track current = f.controller.currentTrack();
            dev.t1m3.qplayer.netease.dto.NeteaseSong song = new dev.t1m3.qplayer.netease.dto.NeteaseSong();
            song.id = 123L; song.name = "NetEase song";
            f.controller.enqueueNeteaseSong(song);
            Track local = new Track(); local.filePath = "local.mp3"; local.title = "Local song";
            f.controller.enqueueTrack(local);
            f.controller.enqueueBiliVideo(video("video", 22L));
            BiliClient.BiliFavItem favorite = new BiliClient.BiliFavItem();
            favorite.bvid = "favorite"; favorite.firstCid = 33L; favorite.title = "Favorite video";
            f.controller.enqueueBiliFavorite(favorite);
            f.drain();
            List<Track> queue = f.controller.queueTracks.peek();
            assertEquals(5, queue.size());
            assertSame(current, f.controller.currentTrack());
            assertEquals(123L, queue.get(1).neteaseId);
            assertSame(local, queue.get(2));
            assertEquals(22L, queue.get(3).biliCid);
            assertEquals(33L, queue.get(4).biliCid);
            assertEquals(1, f.backend.plays);
            assertEquals(27000L, f.backend.position);
            assertFalse(f.backend.playing);
            assertTrue(f.controller.experimentalAiDjActive.peek());
        }
    }

    @Test
    public void aiPreviewBypassesBusyLibraryWorkerAndReusesHistoryMetadata() throws Exception {
        CountDownLatch releaseLibrary = new CountDownLatch(1), libraryBusy = new CountDownLatch(1);
        com.sun.net.httpserver.HttpServer gateway = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/chat/completions", exchange -> {
            try (java.io.InputStream input = exchange.getRequestBody()) {
                byte[] buffer = new byte[2048]; while (input.read(buffer) >= 0) { }
            }
            com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
            for (int id = 1; id <= 5; id++) {
                com.google.gson.JsonObject row = new com.google.gson.JsonObject();
                row.addProperty("title", "歌曲" + id); row.addProperty("artist", "作者");
                row.addProperty("reason", "符合收藏" + id); rows.add(row);
            }
            com.google.gson.JsonObject playlist = new com.google.gson.JsonObject(); playlist.add("songs", rows);
            com.google.gson.JsonObject message = new com.google.gson.JsonObject(); message.addProperty("content", playlist.toString());
            com.google.gson.JsonObject choice = new com.google.gson.JsonObject(); choice.add("message", message);
            com.google.gson.JsonArray choices = new com.google.gson.JsonArray(); choices.add(choice);
            com.google.gson.JsonObject response = new com.google.gson.JsonObject(); response.add("choices", choices);
            byte[] bytes = response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
            exchange.close();
        });
        gateway.start();
        try (Fixture f = fixture()) {
            java.lang.reflect.Field field = PlayerController.class.getDeclaredField("worker");
            field.setAccessible(true);
            ((java.util.concurrent.ExecutorService) field.get(f.controller)).submit(() -> {
                libraryBusy.countDown();
                try { releaseLibrary.await(); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            });
            assertTrue(libraryBusy.await(5, TimeUnit.SECONDS));
            List<dev.t1m3.qplayer.netease.dto.NeteaseSong> history = new ArrayList<>();
            for (int id = 1; id <= 5; id++) {
                dev.t1m3.qplayer.netease.dto.NeteaseSong song = new dev.t1m3.qplayer.netease.dto.NeteaseSong();
                song.id = id; song.name = "歌曲" + id; song.artist = "作者"; history.add(song);
            }
            f.controller.recentSongs.set(history);
            f.controller.prepareAiPlaylist("http://127.0.0.1:" + gateway.getAddress().getPort(), "", "test",
                    "根据历史推荐", 5, false, false, true);
            f.until(() -> !f.controller.aiLoading.peek());
            assertEquals("AI runs while the general worker is still occupied", 1L, releaseLibrary.getCount());
            assertEquals("", f.controller.aiError.peek());
            assertEquals(5, f.controller.aiSongs.peek().size());
            assertEquals("符合收藏1", f.controller.aiSongs.peek().get(0).recommendReason);
            assertEquals(0, f.backend.plays);
            f.controller.cancelAiPlaylistGeneration();
        } finally {
            releaseLibrary.countDown();
            gateway.stop(0);
        }
    }

    private Fixture fixture() throws Exception { return new Fixture(temporaryFolder.newFolder().toPath()); }

    private static BiliClient.BiliVideo video(String bvid, long cid) {
        BiliClient.BiliVideo video = new BiliClient.BiliVideo();
        video.bvid = bvid; video.cid = cid; video.title = bvid; video.durationSeconds = 120L;
        return video;
    }

    private static BiliClient.BiliVideoDetails details(String bvid, long cid) {
        BiliClient.BiliVideoDetails details = new BiliClient.BiliVideoDetails();
        details.video = video(bvid, cid);
        return details;
    }

    private static BiliClient.BiliPart part(long cid, String title) {
        BiliClient.BiliPart part = new BiliClient.BiliPart();
        part.cid = cid; part.title = title; part.durationSeconds = 120L;
        return part;
    }

    private static void assertQueue(Fixture f, String... ids) {
        List<String> actual = new ArrayList<>();
        for (Track track : f.controller.queueTracks.peek()) actual.add(track.biliBvid);
        assertEquals(Arrays.asList(ids), actual);
    }

    private static final class Fixture implements AutoCloseable {
        final String oldBase = AppDirs.base(), oldCache = AppDirs.cacheBase();
        final Path base;
        final FakeBili bili = new FakeBili();
        final Backend backend = new Backend();
        final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        final PlayerController controller;
        Fixture(Path base) {
            this.base = base;
            AppDirs.setBase(base.toString());
            AppDirs.setCacheBase(base.resolve("cache").toString());
            controller = new PlayerController(backend, track -> { }, NeteaseClient.INSTANCE, bili);
            controller.setMainExecutor(main::add);
            controller.setFadeEnabled(false);
        }
        void drain() {
            for (Runnable task; (task = main.poll()) != null;) task.run();
            controller.pump();
        }
        void until(BooleanSupplier condition) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            do { drain(); if (condition.getAsBoolean()) return; Thread.sleep(5L); }
            while (System.nanoTime() < end);
            fail("Playback condition timed out");
        }
        void flushWorkers() throws Exception {
            java.lang.reflect.Field field = PlayerController.class.getDeclaredField("biliQueueWorker");
            field.setAccessible(true);
            ThreadPoolExecutor worker = (ThreadPoolExecutor) field.get(controller);
            until(() -> worker.getActiveCount() == 0 && worker.getQueue().isEmpty());
            drain();
        }
        void replaceQueue(List<Track> tracks) throws Exception {
            java.lang.reflect.Method method = PlayerController.class.getDeclaredMethod("playQueue", List.class, int.class);
            method.setAccessible(true);
            method.invoke(controller, tracks, 0);
        }
        @Override public void close() {
            if (bili.relatedGate != null) bili.relatedGate.countDown();
            if (bili.detailGate != null) bili.detailGate.countDown();
            controller.shutdown();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    private static final class FakeBili extends BiliClient {
        final Map<String, BiliVideoDetails> details = new ConcurrentHashMap<>();
        final Map<String, List<BiliVideo>> related = new ConcurrentHashMap<>();
        final AtomicInteger relatedCalls = new AtomicInteger(), partCalls = new AtomicInteger();
        final CountDownLatch detailEntered = new CountDownLatch(1);
        volatile CountDownLatch relatedGate, detailGate;
        volatile String blockedDetail;
        volatile boolean detailsFailure, relatedFailure;
        FakeBili() { super(5000); }
        @Override public BiliVideoDetails videoDetails(String bvid) throws IOException {
            if (bvid.equals(blockedDetail)) { detailEntered.countDown(); await(detailGate); }
            if (detailsFailure) throw new IOException("detail unavailable");
            BiliVideoDetails result = details.get(bvid);
            return result != null ? result : details(bvid, bvid.equals("seed") ? 1L : 2L);
        }
        @Override public List<BiliVideo> relatedVideos(String bvid) throws IOException {
            relatedCalls.incrementAndGet();
            await(relatedGate);
            if (relatedFailure) throw new IOException("recommendations unavailable");
            return related.getOrDefault(bvid, Collections.emptyList());
        }
        @Override public List<BiliPart> parts(String bvid) {
            partCalls.incrementAndGet();
            return Collections.singletonList(part(99L, "resolved"));
        }
        @Override public String progressiveUrl(String bvid, long cid, int quality) { return bvid + ":" + cid; }
        @Override public List<BiliChapter> chapters(String bvid, long cid) { return Collections.emptyList(); }
        private static void await(CountDownLatch latch) throws IOException {
            if (latch == null) return;
            try {
                if (!latch.await(5L, TimeUnit.SECONDS)) throw new IOException("fixture gate timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
    }

    private static final class Backend implements AudioBackend {
        volatile int plays;
        volatile String source;
        volatile boolean playing;
        volatile long position;
        Runnable complete, started;
        @Override public void play(String source, long startMs) {
            this.source = source; position = startMs; playing = true; plays++;
            if (started != null) started.run();
        }
        @Override public void pause() { playing = false; }
        @Override public void resume() { playing = true; }
        @Override public boolean isPlaying() { return playing; }
        @Override public void seek(long ms) { position = ms; }
        @Override public long position() { return position; }
        @Override public long duration() { return 120000L; }
        @Override public void setVolume(float value) { }
        @Override public void setOnComplete(Runnable callback) { complete = callback; }
        @Override public void setOnStarted(Runnable callback) { started = callback; }
        @Override public void release() { playing = false; }
    }
}
