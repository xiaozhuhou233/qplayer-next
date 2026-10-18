package dev.t1m3.qplayer.bridge;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class LatestTaskExecutorTest {
    @Test public void burstKeepsOnlyLastWaitingSongAndCancelsDiscardedFutures() throws Exception {
        ThreadPoolExecutor executor = LatestTaskExecutor.create("test-resolve");
        CountDownLatch busy = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger heard = new AtomicInteger(-1);
        List<Future<?>> requests = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) executor.submit(() -> {
                busy.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(busy.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 200; i++) {
                final int song = i;
                requests.add(executor.submit(() -> heard.set(song)));
                assertTrue(executor.getQueue().size() <= 1);
            }
            for (int i = 0; i < 199; i++) assertTrue(requests.get(i).isCancelled());
            release.countDown();
            requests.get(199).get(3, TimeUnit.SECONDS);
            assertEquals(199, heard.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void newestSelectionCanStartWhilePreviousNetworkCallIsBlocked() throws Exception {
        ThreadPoolExecutor executor = LatestTaskExecutor.create("test-resolve");
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                busy.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(busy.await(3, TimeUnit.SECONDS));
            assertEquals("latest", executor.submit(() -> "latest").get(3, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void shutdownRejectsWithoutLeavingAnUnfinishedFuture() {
        ThreadPoolExecutor executor = LatestTaskExecutor.create("test-resolve");
        executor.shutdownNow();
        assertTrue(executor.submit(() -> fail("Closed executor ran a task")).isCancelled());
    }
}
