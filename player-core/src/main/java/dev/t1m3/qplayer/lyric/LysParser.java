package dev.t1m3.qplayer.lyric;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyricify Syllable (.lys) parser. Each line is:
 *
 * <pre>{@code
 * [propertyN]text(startMs,durationMs)text(startMs,durationMs)...
 * }</pre>
 *
 * The leading [N] sets the vocal channel (see {@link LyricLine.VocalChannel}).
 * Each {@code (startMs,durationMs)} timing follows the syllable text it
 * describes (i.e. timing is suffix-style, the opposite of the more common
 * prefix-style LRC convention).
 */
public final class LysParser {

    private static final Pattern PROPERTY = Pattern.compile("^\\[(\\d+)]");
    /**
     * Captures one (text, start, duration) triple. The text run is greedy up
     * to the next opening paren; the timing block is the canonical
     * {@code (digits,digits)}.
     */
    private static final Pattern SYLLABLE = Pattern.compile("([^()]*?)\\((\\d+),(\\d+)\\)");

    private LysParser() {}

    public static List<LyricLine> parse(String content) {
        List<LyricLine> out = new ArrayList<>();
        if (content == null) return out;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            LyricLine ll = new LyricLine();
            int from = 0;
            Matcher prop = PROPERTY.matcher(line);
            if (prop.find()) {
                ll.vocalChannel = LyricLine.VocalChannel.fromProperty(
                        Integer.parseInt(prop.group(1)));
                from = prop.end();
            }

            Matcher m = SYLLABLE.matcher(line);
            m.region(from, line.length());
            while (m.find()) {
                String text = m.group(1);
                long start = Long.parseLong(m.group(2));
                long dur = Long.parseLong(m.group(3));
                if (text == null) text = "";
                ll.syllables.add(new Syllable(text, start, dur));
            }
            if (!ll.syllables.isEmpty()) out.add(ll);
        }
        out.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return out;
    }
}
