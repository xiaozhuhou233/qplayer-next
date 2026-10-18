package dev.t1m3.qplayer.bridge;

/** Measures heard time, never the seek-relative playhead. All clocks are monotonic ms. */
final class PlaybackListeningSession {
    private long lastTime, lastPosition, heardMs;
    private boolean started, playing, seeking;

    synchronized boolean start(long now, long position, boolean audible) {
        if (started) return false;
        started = true;
        lastTime = now; lastPosition = position; playing = audible;
        return true;
    }

    synchronized void sample(long now, long position, boolean audible, boolean isSeeking) {
        if (!started) return;
        long elapsed = Math.max(0, now - lastTime);
        long advance = position - lastPosition;
        // Stationary clocks (pause/buffering), backwards jumps and large forward
        // seeks contribute nothing. Normal progress is capped at real elapsed time.
        if (playing && !seeking && !isSeeking && advance > 0 && advance <= elapsed * 2 + 250)
            heardMs += Math.min(elapsed, advance);
        lastTime = now; lastPosition = position; playing = audible; seeking = isSeeking;
    }

    synchronized void seek(long now, long position) {
        lastTime = now; lastPosition = position; seeking = true;
    }

    synchronized long seconds() { return heardMs / 1000; }
    synchronized long milliseconds() { return heardMs; }
}
