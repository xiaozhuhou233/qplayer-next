package dev.t1m3.qplayer.lyric;

import java.util.ArrayList;
import java.util.List;

public class LyricLine {

    /**
     * Lyricify Syllable's propertyN encodes vocal channel. The values match
     * the LYS spec; LRC parsers always yield MAIN. The renderer reads this to
     * decide horizontal alignment (duet) and dim style (background).
     */
    public enum VocalChannel {
        MAIN, BACKGROUND, DUET_LEFT, DUET_RIGHT, BACKGROUND_LEFT, BACKGROUND_RIGHT;

        /** Map LYS propertyN to channel. 0/1 → MAIN per the spec. */
        public static VocalChannel fromProperty(int n) {
            switch (n) {
                case 2:
                    return BACKGROUND;
                case 3:
                    return DUET_LEFT;
                case 4:
                    return DUET_RIGHT;
                case 5:
                    return BACKGROUND_LEFT;
                case 6:
                    return BACKGROUND_RIGHT;
                default:
                    return MAIN;
            }
        }
    }

    public final List<Syllable> syllables = new ArrayList<>();
    public VocalChannel vocalChannel = VocalChannel.MAIN;
    /** Sidecar lines for the same time range. Null if absent. */
    public String translation;
    public String romaji;

    public long startMs() {
        return syllables.isEmpty() ? 0L : syllables.get(0).startMs;
    }

    public long endMs() {
        return syllables.isEmpty() ? 0L : syllables.get(syllables.size() - 1).endMs();
    }

    /** Concatenation of all syllable texts. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Syllable s : syllables) sb.append(s.text);
        return sb.toString();
    }
}
