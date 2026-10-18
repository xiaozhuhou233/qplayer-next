package dev.t1m3.qplayer.bridge;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class BudgetedTaskDrainTest {
    @Test public void burstsContinueInFifoOrderAcrossFrames() {
        Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < 100; i++) { final int n = i; queue.add(() -> seen.add(n)); }
        BudgetedTaskDrain.drain(queue, 2_000_000L, 16, () -> 0L, error -> fail());
        assertEquals(16, seen.size());
        assertEquals(84, queue.size());
        while (!queue.isEmpty()) BudgetedTaskDrain.drain(queue, 2_000_000L, 16, () -> 0L, error -> fail());
        for (int i = 0; i < 100; i++) assertEquals(i, seen.get(i).intValue());
    }

    @Test public void elapsedBudgetYieldsAndSlowFirstTaskStillMakesProgress() {
        Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        AtomicLong clock = new AtomicLong();
        queue.add(() -> clock.addAndGet(5_000_000L));
        queue.add(() -> clock.incrementAndGet());
        BudgetedTaskDrain.drain(queue, 2_000_000L, 16, clock::get, error -> fail());
        assertEquals(1, queue.size());
        assertEquals(5_000_000L, clock.get());
        BudgetedTaskDrain.drain(queue, 2_000_000L, 16, clock::get, error -> fail());
        assertTrue(queue.isEmpty());
    }

    @Test public void selfReplenishingProducerCannotMonopolizeFrame() {
        Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        AtomicInteger count = new AtomicInteger();
        Runnable producer = new Runnable() {
            @Override public void run() { count.incrementAndGet(); queue.add(this); }
        };
        queue.add(producer);
        BudgetedTaskDrain.drain(queue, 2_000_000L, 16, () -> 0L, error -> fail());
        assertEquals(16, count.get());
        assertEquals(1, queue.size());
    }

    @Test public void failedCallbackDoesNotLoseFollowingWork() {
        Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        queue.add(() -> { throw new IllegalStateException("test"); });
        queue.add(completed::incrementAndGet);
        BudgetedTaskDrain.drain(queue, 2_000_000L, 1, () -> 0L, error -> failures.incrementAndGet());
        assertEquals(1, failures.get());
        assertEquals(1, queue.size());
        BudgetedTaskDrain.drain(queue, 2_000_000L, 1, () -> 0L, error -> failures.incrementAndGet());
        assertEquals(1, completed.get());
    }

    @Test public void legacyUnboundedModeRetainsFullDrain() {
        Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        AtomicInteger count = new AtomicInteger();
        for (int i = 0; i < 100; i++) queue.add(count::incrementAndGet);
        BudgetedTaskDrain.drain(queue, Long.MAX_VALUE, Integer.MAX_VALUE, () -> 0L, error -> fail());
        assertEquals(100, count.get());
        assertTrue(queue.isEmpty());
    }
}
