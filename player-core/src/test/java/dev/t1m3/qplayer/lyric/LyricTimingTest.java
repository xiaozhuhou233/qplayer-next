package dev.t1m3.qplayer.lyric;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class LyricTimingTest {
    private List<LyricLine> timed() {
        return YrcParser.parse("[10000,1500](10000,100,0)fast (10100,1200,0)slow (11300,200,0)end");
    }

    @Test public void longerLrcCannotDisplaceWordTiming() {
        List<LyricLine> plain = LrcParser.parse("[00:00.00]credit\n[00:10.00]fast slow end\n"
                + "[00:20.00]tail\n[00:30.00]end");
        List<LyricLine> merged = LyricTiming.prefer(plain, timed());
        assertEquals(1, LyricTiming.wordTimedLines(merged));
        assertTrue(merged.stream().anyMatch(l -> l.text().equals("tail")));
        assertEquals(1, merged.stream().filter(l -> l.text().equals("fast slow end")).count());
        LyricLine line = merged.stream().filter(LyricTiming::hasWordTiming).findFirst().get();
        assertEquals(100, line.syllables.get(0).durationMs);
        assertEquals(1200, line.syllables.get(1).durationMs);
    }

    @Test public void latePlainResultCannotUndoUpgrade() {
        List<LyricLine> current = timed();
        for (int i=0;i<8;i++) {
            current = LyricTiming.prefer(current, LrcParser.parse("[00:10.00]fast slow end"));
            assertEquals(1, LyricTiming.wordTimedLines(current));
            assertEquals(3, current.get(0).syllables.size());
        }
        assertSame(current, LyricTiming.prefer(current, current));
    }

    @Test public void splittingPlainTextIsNotWordTiming() {
        LyricLine fake = new LyricLine();
        fake.syllables.add(new Syllable("one ", 1000, 4000));
        fake.syllables.add(new Syllable("two", 1000, 4000));
        assertFalse(LyricTiming.hasWordTiming(fake));
        assertFalse(LyricTiming.hasWordTiming(LrcParser.parse("[00:01.00]one two").get(0)));
        assertTrue(LyricTiming.hasWordTiming(timed().get(0)));
    }

    @Test public void emptyOrFailedResponseKeepsOfflineLyrics() {
        List<LyricLine> old = timed();
        assertSame(old, LyricTiming.prefer(old, null));
        assertSame(old, LyricTiming.prefer(old, Collections.emptyList()));
    }

    @Test public void oldCacheUpgradesButFreshResponseDoesNotLoop() {
        long now=1_800_000_000_000L;
        assertTrue(LyricTiming.shouldRefreshPlainCache(0, now));
        assertTrue(LyricTiming.shouldRefreshPlainCache(now - 86_400_000L, now));
        assertTrue(LyricTiming.shouldRefreshPlainCache(now - 600_000L, now));
        assertFalse(LyricTiming.shouldRefreshPlainCache(now - 599_999L, now));
        assertFalse(LyricTiming.shouldRefreshPlainCache(now - 1000L, now));
        assertFalse(LyricTiming.shouldRefreshPlainCache(now, now));
        assertTrue(LyricTiming.shouldRefreshPlainCache(now + 1000L, now));
    }

    @Test public void partialTimedCacheStillNeedsUpgrade() {
        List<LyricLine> partial = LyricTiming.prefer(timed(), LrcParser.parse("[00:20.00]missing timing"));
        assertEquals(1, LyricTiming.wordTimedLines(partial));
        assertTrue(LyricTiming.needsTimingUpgrade(partial));
        assertTrue(LyricTiming.needsTimingUpgrade(LrcParser.parse("[00:01.00]whole plain line")));
        assertFalse(LyricTiming.needsTimingUpgrade(timed()));
        assertFalse(LyricTiming.needsTimingUpgrade(Collections.emptyList()));
        assertFalse(LyricTiming.needsTimingUpgrade(LrcParser.parse("[00:01.00]...")));
    }

    @Test public void ordinaryMiddleCannotHideLateTimedUpgrade() {
        List<LyricLine> old = LyricParser.fromNeteaseStrings(
                "[10000,1000](10000,200,0)first (10200,800,0)line\n"
                + "[30000,1000](30000,700,0)last (30700,300,0)line",
                "[00:10.00]first line\n[00:20.00]middle line\n[00:30.00]last line", null, null);
        List<LyricLine> late = YrcParser.parse("[20000,1200](20000,100,0)middle (20100,1100,0)line");
        List<LyricLine> merged = LyricTiming.prefer(old, late);
        assertEquals(3, merged.size());
        assertEquals(3, LyricTiming.wordTimedLines(merged));
        assertEquals(100, merged.get(1).syllables.get(0).durationMs);
        assertEquals(1100, merged.get(1).syllables.get(1).durationMs);
        assertFalse(LyricTiming.needsTimingUpgrade(merged));
        assertEquals(3, LyricTiming.wordTimedLines(LyricTiming.prefer(merged, old)));
        assertEquals(3, LyricTiming.wordTimedLines(LyricTiming.prefer(late, old)));
    }

    @Test public void simultaneousDifferentVocalChannelsArePreserved() {
        List<LyricLine> main = timed();
        List<LyricLine> background = timed();
        background.get(0).vocalChannel = LyricLine.VocalChannel.BACKGROUND;
        List<LyricLine> merged = LyricTiming.prefer(main, background);
        assertEquals(2, merged.size());
        assertEquals(2, LyricTiming.wordTimedLines(merged));
    }
}
