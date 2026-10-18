package dev.t1m3.qplayer.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import org.junit.Test;

/**
 * The AI DJ's mixing rule (round 34): 「几首中文歌几首英文歌这样混着」.
 *
 * <p>What these pin, and why each one is here rather than left to the prompt: the classifier has to
 * work on the metadata that actually exists (no language field anywhere in {@code Track}), the mix
 * has to be proportional rather than strictly alternating (a strict alternation cannot hold eight
 * Chinese against two English without clumping), it must never reorder inside a language (the AI's
 * own ranking is the user's), and it must leave a single-language request completely alone (the user
 * asking for Chinese only must get Chinese only).
 */
public class PlaylistMixerTest {

    /** A row carrying only the two fields the classifier reads. */
    private static final class Row {
        final String title;
        final String artist;

        Row(String title, String artist) {
            this.title = title;
            this.artist = artist;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private static final Predicate<Row> CN = r -> PlaylistMixer.isChinese(r.title, r.artist);

    private static Row cn(String title) {
        return new Row(title, "某歌手");
    }

    private static Row en(String title) {
        return new Row(title, "Some Artist");
    }

    @Test
    public void chineseIsAnyIdeographAnywhereInTheRow() {
        assertTrue("a Chinese title", PlaylistMixer.isChinese("晴天", "Some Artist"));
        assertTrue("a Chinese artist under an English title — the case the rule exists for",
                PlaylistMixer.isChinese("Yellow", "周杰伦"));
        assertFalse("both fields Latin", PlaylistMixer.isChinese("Yellow", "Coldplay"));
        assertFalse("kana is not Chinese (and not English either — it simply is not the mix's job)",
                PlaylistMixer.isChinese("ひまわり", "Some Artist"));
        assertFalse("nulls are not Chinese", PlaylistMixer.isChinese(null, null));
    }

    @Test
    public void anEvenMixComesBackAlternating() {
        List<Row> in = Arrays.asList(cn("A"), cn("B"), cn("C"), en("1"), en("2"), en("3"));
        List<Row> out = PlaylistMixer.interleave(in, CN);
        assertEquals("nothing is added or lost", in.size(), out.size());
        assertEquals("and it alternates: " + PlaylistMixer.describe(out, CN), "CN EN CN EN CN EN",
                PlaylistMixer.describe(out, CN));
    }

    @Test
    public void aSkewedMixSpreadsTheSmallLanguageThroughTheList() {
        List<Row> in = new ArrayList<>();
        for (int i = 0; i < 8; i++) in.add(cn("中" + i));
        in.add(en("one"));
        in.add(en("two"));
        List<Row> out = PlaylistMixer.interleave(in, CN);
        int[] counts = PlaylistMixer.counts(out, CN);
        assertEquals("all eight Chinese are still there", 8, counts[0]);
        assertEquals("and both English ones", 2, counts[1]);
        int firstEn = indexOf(out, true);
        int secondEn = indexOf(out, true, firstEn + 1);
        assertTrue("the first English row is not pushed to the end: " + firstEn, firstEn <= 3);
        assertTrue("the second one follows it inside the first two thirds: " + secondEn,
                secondEn <= 6);
        assertTrue("and no run of one language longer than the cap except the tail it forces: "
                        + PlaylistMixer.describe(out, CN),
                longestRun(out, CN) <= 4);
    }

    @Test
    public void theOrderInsideALanguageIsKept() {
        List<Row> in = Arrays.asList(cn("A"), en("1"), cn("B"), en("2"), cn("C"), en("3"));
        List<Row> out = PlaylistMixer.interleave(in, CN);
        assertEquals("Chinese rows keep the AI's own order",
                Arrays.asList("A", "B", "C"), titles(out, true));
        assertEquals("and so do the English ones",
                Arrays.asList("1", "2", "3"), titles(out, false));
    }

    @Test
    public void oneLanguageComesBackExactlyAsItWas() {
        List<Row> in = Arrays.asList(cn("A"), cn("B"), cn("C"), cn("D"));
        List<Row> out = PlaylistMixer.interleave(in, CN);
        assertEquals("a Chinese-only request is not quietly given English rows — and the prompt's"
                + " own clause says the same", titles(out, true), titles(in, true));
        assertEquals(4, out.size());
    }

    @Test
    public void aListTooShortToHaveAMixIsUntouched() {
        List<Row> two = Arrays.asList(cn("A"), en("1"));
        assertEquals(2, PlaylistMixer.interleave(two, CN).size());
        assertTrue(PlaylistMixer.interleave(null, CN).isEmpty());
    }

    @Test
    public void theCapIsRespectedWhileBothLanguagesRemain() {
        List<Row> in = new ArrayList<>();
        for (int i = 0; i < 5; i++) in.add(cn("中" + i));
        for (int i = 0; i < 5; i++) in.add(en("e" + i));
        List<Row> out = PlaylistMixer.interleave(in, CN, 2);
        assertEquals("five and five: " + PlaylistMixer.describe(out, CN), 5, PlaylistMixer.counts(out, CN)[0]);
        assertTrue("no more than two in a row: " + PlaylistMixer.describe(out, CN),
                longestRun(out, CN) <= 2);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static int indexOf(List<Row> rows, boolean chinese) {
        return indexOf(rows, chinese, 0);
    }

    private static int indexOf(List<Row> rows, boolean chinese, int from) {
        for (int i = from; i < rows.size(); i++) {
            if (CN.test(rows.get(i)) == chinese) return i;
        }
        return -1;
    }

    private static int longestRun(List<Row> rows, Predicate<Row> chinese) {
        int best = 0;
        int run = 0;
        boolean last = false;
        for (int i = 0; i < rows.size(); i++) {
            boolean now = chinese.test(rows.get(i));
            run = i > 0 && now == last ? run + 1 : 1;
            last = now;
            best = Math.max(best, run);
        }
        return best;
    }

    private static List<String> titles(List<Row> rows, boolean chinese) {
        List<String> out = new ArrayList<>();
        for (Row r : rows) if (CN.test(r) == chinese) out.add(r.title);
        return out;
    }
}
