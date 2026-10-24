package dev.t1m3.qplayer.lyric;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ReferenceLyricTimelineTest {
    private LyricLine line(long start, long duration, LyricLine.VocalChannel channel) {
        LyricLine l = new LyricLine(); l.vocalChannel = channel;
        l.syllables.add(new Syllable("voice", start, duration)); return l;
    }
    @Test public void harmonyDoesNotStealAnchorOrCreateAnInstrumentalBreak() {
        LyricLine a = line(1000, 2000, LyricLine.VocalChannel.MAIN);
        LyricLine bg = line(2000, 12000, LyricLine.VocalChannel.BACKGROUND);
        LyricLine b = line(15000, 1000, LyricLine.VocalChannel.MAIN);
        List<ReferenceLyricTimeline.Row> rows = ReferenceLyricTimeline.build(Arrays.asList(a, bg, b));
        assertEquals(2, rows.size()); assertEquals(2, rows.get(0).tracks.size());
        assertEquals(1000, rows.get(0).start); assertEquals(14000, rows.get(0).end);
        assertSame(bg, rows.get(0).tracks.get(1).line);
    }
    @Test public void simultaneousDuetKeepsBothIndependentTracksAndSides() {
        LyricLine left = line(1000, 2200, LyricLine.VocalChannel.DUET_LEFT);
        LyricLine right = line(1050, 3000, LyricLine.VocalChannel.DUET_RIGHT);
        LyricLine bg = line(2100, 1000, LyricLine.VocalChannel.BACKGROUND_RIGHT);
        List<ReferenceLyricTimeline.Row> rows = ReferenceLyricTimeline.build(Arrays.asList(left, right, bg));
        assertEquals(1, rows.size()); assertEquals(3, rows.get(0).tracks.size());
        assertFalse(ReferenceLyricTimeline.right(left)); assertTrue(ReferenceLyricTimeline.right(right));
        assertEquals(4050, rows.get(0).end);
    }
    @Test public void leadingAndMiddleBreaksUseReferenceSevenSecondThreshold() {
        List<ReferenceLyricTimeline.Row> rows = ReferenceLyricTimeline.build(Arrays.asList(
                line(8000, 1000, LyricLine.VocalChannel.MAIN), line(17000, 1000, LyricLine.VocalChannel.MAIN)));
        assertEquals(4, rows.size()); assertTrue(rows.get(0).instrumental());
        assertEquals(500, rows.get(0).start); assertEquals(8000, rows.get(0).end);
        assertTrue(rows.get(2).instrumental()); assertEquals(9500, rows.get(2).start);
    }
    @Test public void plainLinesNeverBecomeFabricatedPerWordLyrics() {
        LyricLine l = LrcParser.parse("[00:01.00]a whole line\n[00:06.00]next").get(0);
        ReferenceLyricTimeline.Row row = ReferenceLyricTimeline.build(Collections.singletonList(l)).get(0);
        assertFalse(LyricTiming.hasWordTiming(row.tracks.get(0).line));
        assertEquals(1, row.tracks.get(0).line.syllables.size());
    }
    @Test public void delayedTrackEndIsMaximumSyllableEndNotLastTokenEnd() {
        LyricLine l = line(1000, 5000, LyricLine.VocalChannel.MAIN);
        l.syllables.add(new Syllable("short overlapping tail", 2000, 300));
        assertEquals(6000, ReferenceLyricTimeline.build(Collections.singletonList(l)).get(0).end);
    }
    @Test public void ttmlHarmonyRetainsItsParentAcrossOverlappingPhrases() {
        List<LyricLine> source = TtmlParser.parse("<tt><body><div>"
                + "<p begin='1s' end='2s'><span begin='1s' end='2s'>first</span>"
                + "<span role='x-bg'><span begin='3s' end='5s'>harmony</span></span></p>"
                + "<p begin='2.5s' end='4s'><span begin='2.5s' end='4s'>second</span></p>"
                + "</div></body></tt>");
        List<ReferenceLyricTimeline.Row> rows = ReferenceLyricTimeline.build(source);
        assertEquals(2, rows.size()); assertEquals(2, rows.get(0).tracks.size());
        assertEquals("harmony", rows.get(0).tracks.get(1).line.text());
        assertEquals(5000, rows.get(0).end);
    }
    @Test public void harmonyCanEnterBeforeTheLeadAndStillShareItsAnchor() {
        LyricLine main = line(2000, 3000, LyricLine.VocalChannel.MAIN);
        LyricLine bg = line(1000, 2000, LyricLine.VocalChannel.BACKGROUND);
        bg.leadStartMs = 2000;
        List<ReferenceLyricTimeline.Row> rows = ReferenceLyricTimeline.build(Arrays.asList(main, bg));
        assertEquals(1, rows.size()); assertEquals(1000, rows.get(0).start);
        assertSame(main, rows.get(0).tracks.get(0).line);
    }
    @Test public void ttmlOffsetAndClockTimestampsRemainEquivalent() {
        assertEquals(1500, TtmlParser.parseClock("1.5s"));
        assertEquals(1500, TtmlParser.parseClock("1500ms"));
        assertEquals(1500, TtmlParser.parseClock("00:01.500"));
        assertEquals(90_000, TtmlParser.parseClock("1.5m"));
        assertEquals(5_400_000, TtmlParser.parseClock("1.5h"));
    }
}
