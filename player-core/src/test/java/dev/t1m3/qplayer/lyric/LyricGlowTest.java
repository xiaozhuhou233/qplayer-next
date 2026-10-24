package dev.t1m3.qplayer.lyric;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricGlowTest {
    @Test public void shortSyllablesHaveVisibleGlowWithoutLongEnglishWordRequirement() {
        assertTrue(LyricGlow.opacity(1060, 1000, 1120) > .99f);
        assertTrue(LyricGlow.opacity(1110, 1000, 1120) > .99f);
        assertEquals(0f, LyricGlow.opacity(999, 1000, 1120), 0f);
    }
    @Test public void attackAndReleaseAreContinuousAndPauseOrSeekIsDeterministic() {
        float previous = 0;
        for (long p = 1000; p <= 1100; p++) {
            float a = LyricGlow.opacity(p, 1000, 2000);
            assertTrue(a >= previous); assertTrue(a - previous < .02f); previous = a;
        }
        previous = 1;
        for (long p = 2000; p <= 2160; p++) {
            float a = LyricGlow.opacity(p, 1000, 2000);
            assertTrue(a <= previous); assertTrue(previous - a < .02f); previous = a;
        }
        assertEquals(LyricGlow.opacity(1050, 1000, 2000), LyricGlow.opacity(1050, 1000, 2000), 0f);
        assertEquals(0f, LyricGlow.opacity(2161, 1000, 2000), 0f);
        assertEquals(0f, LyricGlow.opacity(1050, 1100, 1100), 0f);
    }
}
