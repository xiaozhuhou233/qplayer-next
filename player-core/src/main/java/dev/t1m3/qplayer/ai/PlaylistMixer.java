package dev.t1m3.qplayer.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * The AI DJ playlist's own ordering rule: <b>a few Chinese songs, a few English ones, mixed</b>.
 *
 * <p>The user, on the lists the AI returned before this existed: 「几首中文歌几首英文歌这样混着」 — the
 * model happily returns twenty tracks of one language and then twenty of the other, or all of one.
 * The prompt asks for a mix, but a prompt is not a guarantee, so the ordering is also decided
 * <em>here</em>, on the resolved rows, where it can be tested.
 *
 * <p>Two things make this work with no metadata at all: the language of a row is read off its own
 * text ({@link #isChinese} — the library carries no language field anywhere, see {@code Track}), and
 * the interleave is <em>proportional</em> rather than strictly alternating, because a strict
 * alternation cannot express "eight Chinese and two English" without clumping the tail.
 *
 * <p>Rows are never re-ordered inside their own language: the AI's own ranking within a language is
 * the ranking the user asked for, and this class only decides which language comes next.
 *
 * <p>⚠️ <b>Round 35 stopped applying this to the generated list.</b> The same user, after living with
 * it: 「也不用非要是中英文穿插」 — the prompt still asks for a natural mix, but the order the model
 * returned is the order the listener gets ({@code PlayerController} no longer calls
 * {@link #interleave}). This class and its tests are kept deliberately: the rule is one line away from
 * being turned back on, and it is the answer if a language-mixed request is ever ignored again.
 */
public final class PlaylistMixer {

    /** The longest run of one language the mixer will produce when the other one is still available.
     *  Two, because the user asked for them to be mixed rather than blocked, and a run of two is
     *  still audibly a mix while a run of four is not. Runs longer than this can be unavoidable —
     *  eight Chinese and two English have to put at least three Chinese somewhere. */
    public static final int MAX_RUN = 2;

    private PlaylistMixer() {}

    /**
     * Whether a row is Chinese, read off the text that actually exists: any CJK ideograph in the
     * title or the artist.
     *
     * <p>Deliberately not "no Latin characters" — a Chinese track's artist tag is very often a
     * romanisation or an English alias ({@code Jay Chou}, {@code G.E.M.}), and a rule that needs
     * <em>every</em> field to be Chinese would classify those as English and put them in the wrong
     * half of the mix. One ideograph anywhere is the signal.
     */
    public static boolean isChinese(String title, String artist) {
        return hasIdeograph(title) || hasIdeograph(artist);
    }

    private static boolean hasIdeograph(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // CJK Unified Ideographs, Extension A, and the compatibility block — the ranges a
            // Chinese title lives in. Kana and Hangul are NOT included: a Japanese or Korean title
            // is not a Chinese track, and calling it one would skew the mix.
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                    || (c >= 0xF900 && c <= 0xFAFF)) {
                return true;
            }
        }
        return false;
    }

    /** The mix at {@link #MAX_RUN}. */
    public static <T> List<T> interleave(List<T> in, Predicate<T> chinese) {
        return interleave(in, chinese, MAX_RUN);
    }

    /**
     * Reorder {@code in} so the two languages are spread in proportion to how many of each there
     * are, with no more than {@code maxRun} of one in a row while the other is still available.
     *
     * <p>A list that is all one language (or all one language as far as this classifier can tell)
     * comes back exactly as it was: the user asking for Chinese only must get Chinese only, and the
     * prompt's own mixing clause says the same thing.
     *
     * @param chinese which language a row belongs to ({@code true} = Chinese), read off the row
     */
    public static <T> List<T> interleave(List<T> in, Predicate<T> chinese, int maxRun) {
        if (in == null) return Collections.emptyList();
        List<T> copy = new ArrayList<>(in);
        if (copy.size() < 3) return copy;
        int runCap = Math.max(1, maxRun);
        List<T> cn = new ArrayList<>();
        List<T> en = new ArrayList<>();
        for (T row : copy) {
            if (row == null) return copy;                 // nothing to judge; leave the list alone
            if (chinese.test(row)) cn.add(row); else en.add(row);
        }
        if (cn.isEmpty() || en.isEmpty()) return copy;    // one language: the mix has nothing to do
        int cnTotal = cn.size();
        int enTotal = en.size();
        List<T> out = new ArrayList<>(cnTotal + enTotal);
        int i = 0;
        int j = 0;
        int last = -1;      // 0 = Chinese, 1 = English
        int run = 0;
        while (i < cnTotal || j < enTotal) {
            boolean takeCn;
            if (i >= cnTotal) {
                takeCn = false;
            } else if (j >= enTotal) {
                takeCn = true;
            } else if (last == 0 && run >= runCap) {
                takeCn = false;                            // Chinese has had its run; English now
            } else if (last == 1 && run >= runCap) {
                takeCn = true;
            } else {
                // The proportional rule: Chinese is due while its own share of what is still to be
                // placed is at least English's. `(i+1)/cnTotal <= (j+1)/enTotal` rearranged to
                // integers, so three Chinese and nine English come out interleaved rather than
                // blocked.
                takeCn = (i + 1L) * enTotal <= (j + 1L) * cnTotal;
            }
            out.add(takeCn ? cn.get(i++) : en.get(j++));
            int now = takeCn ? 0 : 1;
            run = now == last ? run + 1 : 1;
            last = now;
        }
        return out;
    }

    /** How many rows of each language a list holds — for the log line and for tests. */
    public static <T> int[] counts(List<T> in, Predicate<T> chinese) {
        int cn = 0;
        if (in != null) {
            for (T row : in) if (row != null && chinese.test(row)) cn++;
        }
        return new int[] {cn, (in == null ? 0 : in.size()) - cn};
    }

    /** The languages in the order they appear, e.g. {@code "CN CN EN CN"} — the shortest way to see
     *  what the mix did, used by the tests and by the AI DJ's own progress line. */
    public static <T> String describe(List<T> in, Predicate<T> chinese) {
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            for (T row : in) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(row != null && chinese.test(row) ? "CN" : "EN");
            }
        }
        return sb.toString();
    }
}
