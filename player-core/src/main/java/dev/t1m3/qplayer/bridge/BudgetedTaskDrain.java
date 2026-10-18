package dev.t1m3.qplayer.bridge;

import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Cooperative FIFO draining: a producer cannot monopolize a host frame. */
final class BudgetedTaskDrain {
    private BudgetedTaskDrain() { }

    static void drain(Queue<Runnable> tasks, long budgetNanos, int maxTasks,
                      LongSupplier clock, Consumer<Throwable> onFailure) {
        if (budgetNanos <= 0 || maxTasks <= 0) throw new IllegalArgumentException("Positive pump budget required");
        long start = clock.getAsLong();
        int completed = 0;
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable failure) {
                onFailure.accept(failure);
            }
            // Always make progress, including when the first task exceeds budget.
            if (++completed >= maxTasks || clock.getAsLong() - start >= budgetNanos) break;
        }
    }
}
