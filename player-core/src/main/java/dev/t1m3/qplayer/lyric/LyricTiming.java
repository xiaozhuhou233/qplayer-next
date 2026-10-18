package dev.t1m3.qplayer.lyric;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

/** Source-time quality, not the number of printable rows or render glyphs. */
public final class LyricTiming {
    private LyricTiming() {}

    public static boolean hasWordTiming(LyricLine line) {
        if (line == null) return false;
        long firstStart = Long.MIN_VALUE;
        long firstEnd = Long.MIN_VALUE;
        int timedSegments = 0;
        for (Syllable s : line.syllables) {
            if (s.durationMs <= 0 || s.text == null || s.text.trim().isEmpty()) continue;
            long end = s.endMs();
            if (firstStart == Long.MIN_VALUE) {
                firstStart = s.startMs;
                firstEnd = end;
            } else if (s.startMs != firstStart || end != firstEnd) {
                // A few YRC payloads quantize adjacent syllables to the same
                // start millisecond. Their durations still carry the actual
                // word timing, so comparing only startMs incorrectly classified
                // the whole line as ordinary LRC.
                return true;
            }
            timedSegments++;
        }
        return timedSegments > 1;
    }

    public static int wordTimedLines(List<LyricLine> lines) {
        int count = 0;
        if (lines != null) for (LyricLine line : lines) if (hasWordTiming(line)) count++;
        return count;
    }

    public static boolean shouldRefreshPlainCache(long fetchedAtMs, long nowMs) {
        return fetchedAtMs <= 0 || fetchedAtMs > nowMs || nowMs - fetchedAtMs >= 600_000L;
    }

    /** One timed row must not make an otherwise line-only cache look complete. */
    public static boolean needsTimingUpgrade(List<LyricLine> lines) {
        if (lines == null || lines.isEmpty()) return false;
        for (LyricLine line : lines) {
            if (line == null || hasWordTiming(line)) continue;
            if (line.text().codePoints().filter(Character::isLetterOrDigit).limit(2).count() >= 2) return true;
        }
        return false;
    }

    /** Never let extra LRC credits or line breaks displace a word-timed source.
     * Still supplement a short timed prefix with the other source's missing rows.
     * First argument wins ties, so already-published timing remains stable.
     */
    public static List<LyricLine> prefer(List<LyricLine> first, List<LyricLine> second) {
        if (first == null || first.isEmpty()) return second == null ? Collections.emptyList() : second;
        if (second == null || second.isEmpty()) return first;
        if (first == second) return first;
        int a = wordTimedLines(first), b = wordTimedLines(second);
        if (a == 0 && b == 0) return first.size() >= second.size() ? first : second;
        List<LyricLine> primary = a >= b ? first : second;
        List<LyricLine> other = a >= b ? second : first;
        List<LyricLine> primaryTimed = new ArrayList<>();
        List<LyricLine> otherTimed = new ArrayList<>();
        for (LyricLine line : primary) if (hasWordTiming(line)) primaryTimed.add(line);
        for (LyricLine line : other) if (hasWordTiming(line)) otherTimed.add(line);
        // Merge timed regions BEFORE filling gaps with ordinary LRC. Otherwise
        // one untimed row in the better source masks the other source's YRC.
        List<LyricLine> timed = LyricParser.supplementTimedLines(primaryTimed, otherTimed);
        return LyricParser.supplementTimedLines(
                LyricParser.supplementTimedLines(timed, primary), other);
    }
}
