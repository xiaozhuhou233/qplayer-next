package dev.t1m3.qplayer.lyric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Vocal groups share a scroll anchor, but every track retains its own clock.
 * This supplements the ui.zip presentation with QPlayer's TTML/LYS channels.
 */
public final class ReferenceLyricTimeline {
    private ReferenceLyricTimeline() {}
    public static boolean background(LyricLine line) {
        return line.vocalChannel == LyricLine.VocalChannel.BACKGROUND ||
                line.vocalChannel == LyricLine.VocalChannel.BACKGROUND_LEFT ||
                line.vocalChannel == LyricLine.VocalChannel.BACKGROUND_RIGHT;
    }
    public static boolean right(LyricLine line) {
        return line.vocalChannel == LyricLine.VocalChannel.DUET_RIGHT || line.vocalChannel == LyricLine.VocalChannel.BACKGROUND_RIGHT;
    }
    public static final class Track {
        public final LyricLine line;
        public final long start, end;
        Track(LyricLine line, long start, long end) { this.line = line; this.start = start; this.end = end; }
    }
    public static final class Row {
        public final long start, end;
        public final List<Track> tracks;
        public boolean instrumental() { return tracks.isEmpty(); }
        Row(long start, long end, List<Track> tracks) {
            this.start = start; this.end = end; this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
        }
    }
    public static List<Row> build(List<LyricLine> source) {
        List<LyricLine> ordered = new ArrayList<>();
        for (LyricLine l : source) if (l != null && !l.syllables.isEmpty() && !l.text().trim().isEmpty()) ordered.add(l);
        ordered.sort(Comparator.comparingLong(LyricLine::startMs));
        List<List<LyricLine>> groups = new ArrayList<>();
        // Build primary groups first: a background voice can start before its
        // lead or continue past another phrase without losing its TTML parent.
        for (LyricLine l : ordered) {
            if (background(l)) continue;
            List<LyricLine> group = null;
            if (!groups.isEmpty()) {
                List<LyricLine> last = groups.get(groups.size() - 1);
                LyricLine main = last.get(0);
                if (Math.abs(main.startMs() - l.startMs()) <= 80 && right(main) != right(l)) group = last;
            }
            if (group == null) { group = new ArrayList<>(); groups.add(group); }
            group.add(l);
        }
        for (LyricLine l : ordered) {
            if (!background(l)) continue;
            List<LyricLine> group = null;
            if (l.leadStartMs >= 0) {
                for (List<LyricLine> candidate : groups) for (LyricLine main : candidate) {
                    if (!background(main) && main.startMs() == l.leadStartMs &&
                            (l.vocalChannel == LyricLine.VocalChannel.BACKGROUND || right(l) == right(main))) group = candidate;
                }
            }
            // Prefer the last overlapping primary on the same duet side.
            for (int i = groups.size() - 1; group == null && i >= 0; i--) {
                List<LyricLine> candidate = groups.get(i);
                boolean matches = false;
                for (LyricLine main : candidate) {
                    if (!background(main) && main.startMs() <= l.startMs() &&
                            l.startMs() <= Math.max(end(main), main.startMs() + 960) + 250 &&
                            (l.vocalChannel == LyricLine.VocalChannel.BACKGROUND || right(l) == right(main))) matches = true;
                }
                if (matches) {
                    group = candidate; break;
                }
            }
            if (group == null) { group = new ArrayList<>(); groups.add(group); }
            group.add(l);
        }
        groups.sort(Comparator.comparingLong(ReferenceLyricTimeline::start));
        List<Row> rows = new ArrayList<>();
        long vocalEnd = 0;
        for (int i = 0; i < groups.size(); i++) {
            List<LyricLine> group = groups.get(i);
            long start = Math.max(start(group), 0), rowEnd = start + 1;
            long nextStart = i + 1 < groups.size() ? start(groups.get(i + 1)) : start + 4000;
            List<Track> tracks = new ArrayList<>();
            for (LyricLine l : group) {
                long s = Math.max(l.startMs(), 0), e = end(l);
                if (e <= s) e = nextStart > s ? nextStart : s + 4000;
                tracks.add(new Track(l, s, Math.max(e, s + 1)));
                rowEnd = Math.max(rowEnd, e);
            }
            if (start - vocalEnd > 7000) rows.add(new Row(vocalEnd + 500, start, Collections.emptyList()));
            rows.add(new Row(start, rowEnd, tracks));
            vocalEnd = Math.max(vocalEnd, rowEnd);
        }
        return Collections.unmodifiableList(rows);
    }
    private static long end(LyricLine line) {
        long end = line.startMs();
        for (Syllable s : line.syllables) if (s.durationMs > 0) end = Math.max(end, s.endMs());
        return end;
    }
    private static long start(List<LyricLine> lines) {
        long start = Long.MAX_VALUE;
        for (LyricLine l : lines) start = Math.min(start, l.startMs());
        return start;
    }
}
