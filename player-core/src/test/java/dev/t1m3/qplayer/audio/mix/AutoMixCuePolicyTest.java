package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class AutoMixCuePolicyTest {
    private AutoCue.Envelope envelope(double duration, double dropAt) {
        int count = (int) Math.ceil(duration / AutoCue.HOP_SECS);
        float[] rms = new float[count], onset = new float[count];
        Arrays.fill(rms, .05f);
        Arrays.fill(rms, 0, (int) (dropAt / AutoCue.HOP_SECS), .5f);
        return new AutoCue.Envelope(rms, onset, duration);
    }

    @Test public void searchAndRankedCandidatesKeepAtLeastFiveSeventhsOfTheOutgoingSong() {
        AutoCue.Envelope a = envelope(140, 80), b = envelope(140, 100);
        AutoCue.Cue cue = AutoCue.plan(a, b, 6);
        assertNotNull(cue);
        assertTrue(cue.outroCutSecs >= 100);
        AutoCue.Cue[] ranked = AutoCue.topCues(a, b, new double[] {3, 6, 12}, 10);
        assertTrue(ranked.length > 0);
        for (AutoCue.Cue candidate : ranked) assertTrue(candidate.outroCutSecs >= 100);
    }

    @Test public void pinnedPointCannotBypassMinimumPlayTime() {
        AutoCue.Cue cue = AutoCue.planWithLyricSpans(envelope(140, 80), envelope(140, 100),
                new double[] {6}, null, null, AutoCue.Bridge.MEASURE, null, 80);
        assertNull(cue);
    }

    @Test public void structureAlignmentRejectsAnEarlyOutroAndKeepsALateOutro() {
        AutoCue.Envelope early = envelope(140, 80), late = envelope(140, 120);
        assertTrue(Double.isNaN(AutoCue.structureAlignedCut(early, 6, 0)));
        double cut = AutoCue.structureAlignedCut(late, 6, 0);
        assertFalse(Double.isNaN(cut));
        assertTrue(cut >= 100);
        assertTrue(Double.isNaN(AutoCue.structureAlignedCut(early, 6, 0, late, 0)));
    }
}
