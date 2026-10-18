package dev.t1m3.qplayer.audio.automix;

/** Audio-clock gate for the silent dry prefix; independent of Android's player API. */
public final class AutoMixHandoff {
    public static final long WAIT = -1, FADE = -2, ABORT = -3;
    public static final long FADE_MS = 96;
    /** Tolerate one background controller interval only when clocks are aligned. */
    public static final long LATE_TOLERANCE_MS = 250;
    private final long cueMs, leadMs;
    private long previousMs;
    private long lastAdvanceMs;
    private boolean awaitingSeek;
    private long seekAheadMs;
    private int seeks;
    private int movingSamples;

    /** Start partway through the dry prefix when a scrub leaves less warmup time. */
    public long initialSeek(long outgoingMs) {
        long expected = leadMs + outgoingMs - cueMs;
        if (expected <= 0 || expected >= leadMs - 180) return WAIT;
        awaitingSeek = true;
        seeks++;
        movingSamples = 0;
        return expected;
    }

    public AutoMixHandoff(long cueMs, long leadMs, long startedMs) {
        this.cueMs = cueMs; this.leadMs = leadMs;
    }

    /** Nonnegative result means seek the muted prefix. Other results are commands. */
    public long sample(long nowMs, long outgoingMs, long incomingMs) {
        long expected = leadMs + outgoingMs - cueMs;
        // Only song progress can pass the cue. Wall time is used below solely
        // to verify that the incoming decoder's clock is advancing.
        if (expected > leadMs + LATE_TOLERANCE_MS) return ABORT;
        if (awaitingSeek) return WAIT;
        if (incomingMs > previousMs) { movingSamples++; lastAdvanceMs = nowMs; }
        previousMs = incomingMs;
        // isPlaying alone does not prove that the audio output has started.
        if (movingSamples < 2 || nowMs - lastAdvanceMs > 100) return WAIT;
        long drift = incomingMs - expected;
        if (Math.abs(drift) > 80) {
            // A seek lands asynchronously. Its measured cost must be included in the
            // next target, otherwise every "correction" leaves the same clock lag.
            long compensation = seeks == 0 ? 0 : Math.max(0, seekAheadMs - drift);
            long target = expected + compensation;
            if (expected < 0 || target > leadMs - 180 || seeks >= 4) return ABORT;
            awaitingSeek = true;
            seekAheadMs = compensation;
            seeks++;
            movingSamples = 0;
            return target;
        }
        if (expected >= leadMs - FADE_MS) {
            return FADE;
        }
        return WAIT;
    }

    public void seekComplete(long nowMs, long actualMs) {
        if (!awaitingSeek) return;
        lastAdvanceMs = nowMs;
        awaitingSeek = false;
        previousMs = actualMs;
        movingSamples = 0;
    }
}
