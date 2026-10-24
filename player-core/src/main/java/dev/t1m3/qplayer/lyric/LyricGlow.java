package dev.t1m3.qplayer.lyric;

/** Language-independent glow envelope on the same playback clock as karaoke. */
public final class LyricGlow {
    private LyricGlow() {}
    public static float opacity(long position, long start, long end) {
        if (end <= start || position <= start || position >= end + 160L) return 0f;
        float attack = smooth((position - start) / (float) Math.min(100L, Math.max(1L, (end - start) / 2L)));
        float release = smooth((end + 160L - position) / 160f);
        return attack * release;
    }
    private static float smooth(float value) {
        float p = Math.max(0f, Math.min(1f, value));
        return p * p * (3f - 2f * p);
    }
}
