package dev.t1m3.qplayer.audio;

/** One interruption may resume for five minutes; callers supply a monotonic clock. */
public final class AudioFocusResumeWindow {
    public static final long TIMEOUT_MS = 5 * 60 * 1000L;
    private long interruptedAtMs = -1L;

    /** Repeated loss notifications never extend the original interruption. */
    public void onLoss(long nowMs, boolean playbackRequested, boolean mayResume) {
        if (!mayResume) cancel();
        else if (!isPending() && playbackRequested) interruptedAtMs = nowMs;
    }

    /** Consume the intent even when expired: a later gain cannot revive it. */
    public boolean onGain(long nowMs) {
        long elapsed = nowMs - interruptedAtMs;
        boolean resume = isPending() && elapsed >= 0 && elapsed <= TIMEOUT_MS;
        cancel();
        return resume;
    }

    public boolean isPending() { return interruptedAtMs >= 0L; }

    /** Manual pause/play, stopping or replacing the source cancels the old intent. */
    public void cancel() { interruptedAtMs = -1L; }
}
