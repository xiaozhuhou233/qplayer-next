package dev.t1m3.qplayer.lyric;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricContinuousSweepTest {
    private float progress(long position, long start, long end, long nextStart) {
        long sweepEnd = LyricTiming.continuousSweepEndMs(start, end, nextStart);
        return Math.max(0f, Math.min(1f, (position - start) / (float) (sweepEnd - start)));
    }

    @Test public void shortWordKeepsMovingAcrossGapInsteadOfFreezingFullyLit() {
        // The provider says 100 ms of singing, followed by a 400 ms gap.
        float previous = -1;
        for (long time = 1000; time < 1500; time += 10) {
            float current = progress(time, 1000, 1100, 1500);
            assertTrue(current > previous);
            assertTrue(current < 1f);
            previous = current;
        }
        assertEquals(.6f, progress(1300, 1000, 1100, 1500), .00001f);
        assertEquals(1f, progress(1500, 1000, 1100, 1500), 0f);
        assertEquals(0f, progress(1500, 1500, 1600, 1700), 0f);
    }

    @Test public void speedAdaptsToEachWordWithoutMovingItsOnset() {
        assertEquals(.5f, progress(1250, 1000, 1100, 1500), 0f);
        assertEquals(.5f, progress(1600, 1500, 1550, 1700), 0f);
        assertEquals(0f, progress(1499, 1500, 1550, 1700), 0f);
    }

    @Test public void contiguousAndOverlappingVocalsRetainSourceDuration() {
        assertEquals(1500, LyricTiming.continuousSweepEndMs(1000, 1500, 1500));
        assertEquals(1800, LyricTiming.continuousSweepEndMs(1000, 1800, 1500));
        assertEquals(1800, LyricTiming.continuousSweepEndMs(1000, 1800, 1000));
        assertEquals(1800, LyricTiming.continuousSweepEndMs(1000, 1800, 900));
    }

    @Test public void finalWordStopsAtItsTailNotDuringFollowingInstrumental() {
        assertEquals(2100, LyricTiming.continuousSweepEndMs(2000, 2100, 2100));
        assertEquals(1f, progress(2100, 2000, 2100, 2100), 0f);
        assertEquals(1f, progress(9000, 2000, 2100, 2100), 0f);
    }

    @Test public void seekingAndPausingUseAudioPositionWithoutAnimationHistory() {
        float first = progress(1300, 1000, 1100, 1500);
        progress(1600, 1000, 1100, 1500);
        assertEquals(first, progress(1300, 1000, 1100, 1500), 0f);
        assertEquals(first, progress(1300, 1000, 1100, 1500), 0f);
        assertEquals(0f, progress(500, 1000, 1100, 1500), 0f);
    }

    @Test public void zeroDurationRemainsFinite() {
        assertEquals(1001, LyricTiming.continuousSweepEndMs(1000, 1000, 1000));
        assertEquals(1500, LyricTiming.continuousSweepEndMs(1000, 1000, 1500));
    }
}
