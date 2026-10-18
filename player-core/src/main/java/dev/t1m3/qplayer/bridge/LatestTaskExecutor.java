package dev.t1m3.qplayer.bridge;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Two bounded network lanes plus the newest waiting selection. */
final class LatestTaskExecutor {
    private LatestTaskExecutor() { }

    static ThreadPoolExecutor create(String name) {
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), task -> {
                    Thread thread = new Thread(task, name);
                    thread.setDaemon(true);
                    return thread;
                }, (task, executor) -> cancel(task)) {
            @Override public synchronized void execute(Runnable task) {
                if (isShutdown()) {
                    cancel(task);
                    return;
                }
                // Serialize submissions, not network calls. Workers only drain
                // the queue, so the replacement cannot race another producer.
                if (getQueue().remainingCapacity() == 0) cancel(getQueue().poll());
                super.execute(task);
            }
        };
    }

    private static void cancel(Runnable task) {
        if (task instanceof Future<?>) ((Future<?>) task).cancel(false);
    }
}
