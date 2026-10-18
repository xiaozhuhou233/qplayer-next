package dev.t1m3.qplayer.netease;

import java.io.IOException;

/** Single reporting-lane state: pauses flush deltas, resumes do not replay past listening. */
final class NeteaseReportProgress {
    interface Sink { void send(long heardSeconds, boolean start) throws IOException; }
    private final Sink sink;
    private boolean open;
    private long acknowledged;

    NeteaseReportProgress(Sink sink) { this.sink = sink; }

    void started() throws IOException {
        if (!open) { sink.send(0, true); open = true; }
    }

    void checkpoint(long cumulativeSeconds) throws IOException {
        long delta = cumulativeSeconds - acknowledged;
        if (delta < 3) return;
        started();
        sink.send(delta, false);
        acknowledged = cumulativeSeconds;
        open = false;
    }
}
