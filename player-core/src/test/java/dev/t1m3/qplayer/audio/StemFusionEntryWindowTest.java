package dev.t1m3.qplayer.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ⚠️ Round 6's device pair, and the defect it exposed in the entry search.
 *
 * <p>The pair is {@code AGUDO MAGKLCO -> Lose My Mind} on the device's own profiles: the outgoing
 * 106.01 BPM (566.4 ms beats), the incoming 110.1 BPM (545 ms beats, first beat 355 ms), a 15 s
 * blend and the incoming deck starting at 240 ms of its own file (its measured silent head). The
 * renderer measured nine bar lines for the incoming's 19 420 ms head off its low end's downbeat of
 * −1 280 ms — so they sit at 900 + k·2180 ms — and the planner refused the pair:
 * "no bar line of the incoming track inside its own two-bar window".
 *
 * <p>Why: those lines were handed over in <b>seconds</b> ({@link StemGesture#barLines} answers in
 * seconds, and every other consumer of that array reads seconds) while every bar line in
 * {@link StemFusion} is absolute ms. Nine lines at 0.900…18.340 are all below the entry window's
 * 240, so the search found nothing to place the deck on. The renderer converts now (its own
 * {@code inMs}), and this class pins both halves of the planner's answer: an array that holds no
 * line in the window is healed by the incoming's own beat grid, and the healing is <em>said out
 * loud</em> in the plan instead of being a silent substitution.
 */
public class StemFusionEntryWindowTest {

    private static final double A_BEAT_MS = 566.4d;
    private static final double B_BEAT_MS = 545d;
    private static final double B_PHASE_MS = 355d;
    private static final long B_BAR_MS = 2_180L;
    private static final long A_DUR_MS = 208_000L;
    private static final long BLEND_MS = 15_000L;
    private static final long CONTENT_START_MS = 240L;
    private static final long REMOVAL_MS = BLEND_MS + CONTENT_START_MS;

    /** The incoming's nine measured lines, ms, as the renderer builds them. */
    private static double[] measuredLines() {
        double[] out = new double[9];
        for (int i = 0; i < out.length; i++) out[i] = 900d + i * B_BAR_MS;
        return out;
    }

    /** The outgoing's own lines from its separated low end (ms, as {@code barLinesOfLowBand}
     *  answers): its downbeat is −1 080 ms, i.e. 1 185.6 ms into its 2 265.6 ms bar. */
    private static double[] outgoingLines() {
        double[] out = new double[90];
        for (int i = 0; i < out.length; i++) out[i] = 1_185.6d + i * 2_265.6d;
        return out;
    }

    private static StemFusion.Plan plan(double[] incomingLines) {
        // speed = 545/566.4: the ratio the deck plays the incoming at, which locks the two grids
        // exactly (the device's own x0.9622 in this direction).
        return StemFusion.plan(new StemFusion.Input(A_DUR_MS, BLEND_MS, REMOVAL_MS,
                CONTENT_START_MS, A_BEAT_MS, 0d, B_BEAT_MS, B_PHASE_MS, B_BEAT_MS / A_BEAT_MS,
                outgoingLines(), incomingLines, StemFusion.NO_VOICE_MEASUREMENT,
                StemFusion.NO_GROOVE_MEASUREMENT, StemFusion.NO_BODY_MEASUREMENT, -1L));
    }

    @Test
    public void theMeasuredLinesAreUsedWhenTheyHoldALineInTheWindow() {
        StemFusion.Plan plan = plan(measuredLines());
        assertTrue(plan.describe(), plan.valid);
        assertFalse("the caller's own lines answered", plan.entryFromBeatGrid);
        assertEquals("the line at 900 ms is the first candidate", 900L, plan.entryMs);
    }

    /**
     * The exact array the device's renderer handed over — the same nine lines in seconds. Every one
     * of them is under the window's own start, so the caller's array holds nothing; the incoming's
     * beat grid does, and the plan says which of the two answered.
     */
    @Test
    public void linesInSecondsAreHealedByTheBeatGridAndThePlanSaysSo() {
        double[] seconds = measuredLines().clone();
        for (int i = 0; i < seconds.length; i++) seconds[i] /= 1000d;
        StemFusion.Plan plan = plan(seconds);
        assertTrue(plan.describe(), plan.valid);
        assertTrue("the entry came from the beat grid", plan.entryFromBeatGrid);
        long entry = plan.entryMs;
        assertEquals("the entry is the beat grid's own line (355 + k*2180)", 0L,
                ((entry - (long) B_PHASE_MS) % B_BAR_MS + B_BAR_MS) % B_BAR_MS);
        assertTrue("and inside the two-bar window (" + entry + "ms)", entry >= CONTENT_START_MS
                && entry < CONTENT_START_MS + 2L * B_BAR_MS);
        assertTrue("the plan says where its entry came from: " + plan.describe(),
                plan.describe().contains("BEAT grid"));
        // ⚠️ And why that matters to the passage rather than to one number: every instant the table
        // places is `entry + k*step`, so an entry on the incoming's own grid is what puts the swaps
        // and the arrival on the incoming's bar lines — the rows the pulse clause measures. An entry
        // 900 ms off (0.900 instead of 900) puts them 900 ms off the beats the material is playing.
        long step = Math.round(plan.barStepMs);
        for (long at : new long[]{plan.arriveStartMs, plan.arriveEndMs, plan.holdEndMs,
                plan.fusionEndMs}) {
            assertEquals("the table's instant at " + at + "ms is on the incoming's own grid", 0L,
                    ((at - (long) B_PHASE_MS) % B_BAR_MS + B_BAR_MS) % B_BAR_MS);
            assertEquals("and on the entry's own step", 0L, (at - plan.entryMs) % step);
        }
    }

    /** The other way this happens on a real track: the head decode stopped before the incoming's
     *  content start plus two bars, so its array is empty. */
    @Test
    public void aShortHeadIsTheSameAnswer() {
        StemFusion.Plan empty = plan(new double[0]);
        assertTrue(empty.describe(), empty.valid);
        assertTrue(empty.entryFromBeatGrid);
        StemFusion.Plan nullLines = plan(null);
        assertTrue(nullLines.describe(), nullLines.valid);
        assertTrue(nullLines.entryFromBeatGrid);
        // And the introspection is not vacuous: a line inside the window is used as it is, even when
        // the array holds only that one.
        StemFusion.Plan one = plan(new double[]{3_300d});
        assertTrue(one.describe(), one.valid);
        assertFalse("the caller's own line answered", one.entryFromBeatGrid);
        assertEquals(3_300L, one.entryMs);
    }
}
