package dev.t1m3.qplayer.lyric;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class LyricParserTimingTest {
    private static final String YRC =
            "[10000,2300](10000,100,0)快(10250,1800,0)慢(12050,250,0)走";

    @Test public void extraLrcLinesMustNotDiscardUnequalWordDurations() {
        String lrc = "[00:00.00]credit one\n[00:01.00]credit two\n"
                + "[00:02.00]credit three\n[00:03.00]credit four\n"
                + "[00:09.80]快慢走\n[00:12.50]\n[00:20.00]later";
        List<LyricLine> lines = LyricParser.fromNeteaseStrings(YRC, lrc, null, null);
        LyricLine timed = lines.stream().filter(l -> l.text().equals("快慢走")).findFirst().get();
        assertEquals(1L, lines.stream().filter(l -> l.text().equals("快慢走")).count());
        assertEquals(3, timed.syllables.size());
        assertEquals(10000L, timed.syllables.get(0).startMs);
        assertEquals(100L, timed.syllables.get(0).durationMs);
        assertEquals(10250L, timed.syllables.get(1).startMs);
        assertEquals(1800L, timed.syllables.get(1).durationMs);
        assertEquals(12050L, timed.syllables.get(2).startMs);
        assertEquals(250L, timed.syllables.get(2).durationMs);
        assertTrue(lines.stream().anyMatch(l -> l.text().equals("later")));
        assertFalse(lines.stream().anyMatch(l -> l.text().trim().isEmpty()));
    }

    @Test public void lrcLineBreaksDoNotReplacePartOfATimedPhrase() {
        List<LyricLine> lines = LyricParser.fromNeteaseStrings(YRC,
                "[00:10.00]快\n[00:10.25]慢\n[00:12.05]走", null, null);
        assertEquals(1, lines.size());
        assertEquals(3, lines.get(0).syllables.size());
    }

    @Test public void partialYrcStillGetsMissingLrcMiddleAndTail() {
        List<LyricLine> lines = LyricParser.fromNeteaseStrings(
                YRC + "\n[30000,500](30000,500,0)end",
                "[00:10.00]快慢走\n[00:20.00]middle\n[00:30.00]end\n[00:40.00]tail",
                null, null);
        assertEquals(4, lines.size());
        assertEquals(3, lines.get(0).syllables.size());
        assertEquals("middle", lines.get(1).text());
        assertEquals("end", lines.get(2).text());
        assertEquals("tail", lines.get(3).text());
    }

    @Test public void invalidYrcFallsBackToLrc() {
        List<LyricLine> lines = LyricParser.fromNeteaseStrings("invalid",
                "[00:01.00]line", null, null);
        assertEquals("line", lines.get(0).text());
    }

    @Test public void sameStartDifferentDurationsStillCountAsWordTiming() {
        LyricLine line = new LyricLine();
        line.syllables.add(new Syllable("Ni", 1000L, 180L));
        line.syllables.add(new Syllable("a", 1000L, 320L));
        assertTrue(LyricTiming.hasWordTiming(line));
    }

    @Test public void driftedStampCannotDuplicateWordTiming() {
        // LRC marks the line's musical entry 900ms before the first sung word.
        String yrc = "[30900,2100](30900,900,0)Shape (31800,600,0)of (32400,600,0)You";
        String lrc = "[00:30.00]Shape of You\n[00:52.00]oh I";
        List<LyricLine> lines = LyricParser.fromNeteaseStrings(yrc, lrc, null, null);
        assertEquals(1, LyricTiming.wordTimedLines(lines));
        assertEquals(1, lines.stream().filter(l -> l.text().contains("Shape")).count());
        assertEquals(2, lines.size());
    }

    @Test public void typographyOnlyDriftIsStillCovered() {
        // Full-width glyphs, casing and punctuation differ; the stamp is 4.4s
        // ahead of the first sung word. Normalized text still matches YRC.
        String yrc = "[30900,2100](30900,900,0)Shape (31800,600,0)of (32400,600,0)You";
        String lrc = "[00:26.50]Ｓｈａｐｅ ｏｆ ｙｏｕ！";
        List<LyricLine> lines = LyricParser.fromNeteaseStrings(yrc, lrc, null, null);
        assertEquals(1, lines.size());
        assertEquals(1, LyricTiming.wordTimedLines(lines));
        assertEquals(3, lines.get(0).syllables.size());
    }
}
