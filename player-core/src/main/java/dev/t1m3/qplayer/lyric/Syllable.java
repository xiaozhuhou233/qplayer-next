package dev.t1m3.qplayer.lyric;

/**
 * One karaoke-timed segment of a lyric line. For LRC (line-only) a line has a
 * single Syllable covering its entire duration; for LYS/YRC each word or
 * syllable gets its own entry with independent timing.
 */
public class Syllable {
    public final String text;
    /** Absolute start time in ms from track start. */
    public final long startMs;
    /** Duration of this syllable in ms. */
    public final long durationMs;

    public Syllable(String text, long startMs, long durationMs) {
        this.text = text;
        this.startMs = startMs;
        this.durationMs = durationMs;
    }

    public long endMs() {
        return startMs + durationMs;
    }
}
