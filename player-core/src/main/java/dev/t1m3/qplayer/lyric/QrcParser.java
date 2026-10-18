package dev.t1m3.qplayer.lyric;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ Music QRC parser. Each lyric line is:
 *
 * <pre>{@code
 * [lineStartMs,lineDurationMs]字(start,dur)字(start,dur)...
 * }</pre>
 *
 * The leading {@code [start,duration]} pair anchors the whole line and the
 * per-syllable {@code (start,dur)} pairs follow the syllable text. Identical
 * syntax to {@link LysParser} except for the bracket payload (millisecond
 * line timing instead of a vocal channel property number) — so vocal channel
 * is not encoded; QRC lines all map to {@link LyricLine.VocalChannel#MAIN}.
 */
public final class QrcParser {

    private static final Pattern LINE_HEADER = Pattern.compile("^\\[(\\d+),(\\d+)]");
    private static final Pattern SYLLABLE = Pattern.compile("([^()]*?)\\((\\d+),(\\d+)\\)");

    private QrcParser() {}

    public static List<LyricLine> parse(String content) {
        List<LyricLine> out = new ArrayList<>();
        if (content == null) return out;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            // QRC files often have a leading metadata header block — skip
            // anything that doesn't start with [digits,digits].
            Matcher header = LINE_HEADER.matcher(line);
            if (!header.find()) continue;

            LyricLine ll = new LyricLine();
            Matcher m = SYLLABLE.matcher(line);
            m.region(header.end(), line.length());
            while (m.find()) {
                String text = m.group(1);
                long start = Long.parseLong(m.group(2));
                long dur = Long.parseLong(m.group(3));
                if (text == null) text = "";
                // QRC encodes word separators (spaces) as " (0,0)" tuples.
                // Keep them as their own syllables so they take up real
                // width when the renderer measures advance — but anchor
                // their start time at the PREVIOUS SYLLABLE'S END, not its
                // start: LyricRenderer.computeSweepX reads the next
                // syllable's startMs as the preceding one's sung-fill end,
                // so anchoring at prev.startMs collapsed every real
                // syllable's karaoke fill to computeSweepX's 1ms fallback
                // (sEnd <= sStart) instead of its actual duration, making
                // each character flash fully lit instantly rather than
                // filling smoothly. Anchoring at prev.startMs also avoids
                // pinning LyricLine.startMs() to 0 (which would make every
                // line look permanently active). dur stays 0 so the
                // fade-in alpha curve treats them as already-visible
                // alongside the preceding word.
                if (start == 0L && dur == 0L && !ll.syllables.isEmpty()) {
                    Syllable prev = ll.syllables.get(ll.syllables.size() - 1);
                    ll.syllables.add(new Syllable(text, prev.endMs(), 0L));
                    continue;
                }
                ll.syllables.add(new Syllable(text, start, dur));
            }
            if (!ll.syllables.isEmpty()) out.add(ll);
        }
        out.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return out;
    }
}
