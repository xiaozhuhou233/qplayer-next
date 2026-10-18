package dev.t1m3.qplayer.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The fusion's arithmetic, and the acceptance the round is measured against: the anchors where the
 * spec puts them, the three-state table with its 80 ms splices, the carried passage at the outgoing
 * track's own tempo, the outgoing's voice left behind, and the pulse holding through the seam.
 *
 * <p>All of it on synthetic stems: the numbers here are the same numbers the render reports, which
 * is the whole reason the planner and the measurement are pure Java (see {@link StemFusion}).
 */
public class StemFusionTest {

    private static final int RATE = 44_100;

    // --- the anchors ---------------------------------------------------------

    /** A grid: bar lines every {@code barMs} from {@code fromMs}, {@code count} of them. */
    private static double[] bars(double barMs, double fromMs, int count) {
        double[] out = new double[count];
        for (int i = 0; i < count; i++) out[i] = fromMs + i * barMs;
        return out;
    }

    private static StemFusion.Input input(long aDurMs, long blendMs, long removalMs,
                                          double aBeatMs, double bBeatMs, double[] aBars,
                                          double[] bBars) {
        return new StemFusion.Input(aDurMs, blendMs, removalMs, removalMs - blendMs,
                aBeatMs, 0d, bBeatMs, 0d, 1d, aBars, bBars, StemFusion.NO_VOICE_MEASUREMENT);
    }

    /**
     * A <b>measured</b> departure for the coupling's fixtures: the outgoing's own rows leave the
     * passage {@code afterJunctionMs} into it. The reading is reported as measured, which is exactly
     * what the log has to tell apart from the gesture's own end ({@link #neverQuiet}).
     */
    private static StemFusion.OutgoingExit exit(long afterJunctionMs) {
        return new StemFusion.OutgoingExit() {
            @Override
            public long goneAtMs(long fromMs, long spanMs) {
                return fromMs + Math.min(Math.max(0L, afterJunctionMs), spanMs);
            }

            @Override
            public long goneAtMs(long fromMs, long spanMs, double underBodyDb) {
                // A cliff: every level is reached at the same instant, which is what a step envelope
                // really is — and is why round 30's lead cannot move a pair whose outgoing material
                // drops rather than fades (see the two-level test below).
                return goneAtMs(fromMs, spanMs);
            }

            @Override
            public boolean measured() {
                return true;
            }
        };
    }

    /**
     * A <b>ramped</b> envelope: a track fading away rather than dropping. The rows are 12 dB under
     * the passage's own body (the arrival's own level, {@link StemFusion#OUTGOING_EXIT_DB}) at
     * {@code departureMs} into the passage, and were already 6 dB under it
     * ({@link StemFusion#VOICE_GATE_FLOOR_DB}, the lead's own bound) at {@code guardMs}.
     */
    private static StemFusion.OutgoingExit ramp(long departureMs, long guardMs) {
        return new StemFusion.OutgoingExit() {
            @Override
            public long goneAtMs(long fromMs, long spanMs) {
                return fromMs + Math.min(Math.max(0L, departureMs), spanMs);
            }

            @Override
            public long goneAtMs(long fromMs, long spanMs, double underBodyDb) {
                long at = underBodyDb <= StemFusion.VOICE_GATE_FLOOR_DB ? guardMs : departureMs;
                return fromMs + Math.min(Math.max(0L, at), spanMs);
            }

            @Override
            public boolean measured() {
                return true;
            }
        };
    }

    /** The same as {@link #exit}, for a passage the rows never get quiet inside: the answer is the
     *  passage's own end — the shape's own answer, but read off measured material. */
    private static StemFusion.OutgoingExit neverQuiet() {
        return exit(Long.MAX_VALUE / 4L);
    }

    /** A plan with one measured departure, on the canonical 120 BPM grids. */
    private static StemFusion.Plan coupled(long removalMs, long firstVocalMs,
                                           StemFusion.OutgoingExit exit) {
        return StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, removalMs, 0L,
                500d, 0d, 500d, 0d, 1d, bars(2000d, 0d, 120), bars(2000d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT, StemFusion.NO_GROOVE_MEASUREMENT,
                StemFusion.NO_BODY_MEASUREMENT, firstVocalMs, StemFusion.NO_INCOMING_MEASUREMENT, 0,
                exit));
    }

    @Test
    public void theAnchorsAreWhereTheSpecPutsThem() {
        // A and B both on a 500 ms beat (120 BPM), so a bar is 2000 ms and the tempo lock is exact
        // (speed 1 — the pair's grids already hold, the ordinary case).
        StemFusion.Plan plan = StemFusion.plan(input(240_000L, 20_000L, 35_000L, 500d, 500d,
                bars(2000d, 0d, 120), bars(2000d, 0d, 120)));
        assertTrue(plan.reason, plan.valid);
        // The target is aDur - 250 - blendMs = 219750; the nearest line of A's grid is 220000.
        assertEquals(220_000L, plan.junctionMs);
        // The content start is removal - blend = 15000; the first B bar line at or after it is
        // 16000, and (the spec's own rule) the deck starts there.
        assertEquals(15_000L, 15_000L);
        assertEquals(16_000L, plan.entryMs);
        // ⚠️ Round 6's instants, not the old splices': the outgoing's rows hold at unity until the
        // hold ends (which is `swapMs` = `bassMs`, where its low end starts to recede), its drums
        // reach the floor one step later, its low end and melodic row two steps later, and the
        // incoming's rise begins one step BEFORE the hold ends — that overlap is the coexistence.
        assertEquals(20_000L, plan.holdEndMs);
        assertEquals(20_000L, plan.swapMs);
        assertEquals(20_000L, plan.bassMs);
        assertEquals(22_000L, plan.drumsEndMs);
        assertEquals(24_000L, plan.lowEndEndMs);
        assertEquals(24_000L, plan.fusionEndMs);
        assertEquals(18_000L, plan.arriveStartMs);
        assertEquals(20_000L, plan.arriveEndMs);
        assertEquals(8_000L, plan.windowMs);
        // ⚠️ The material is drawn from the table's own span, not from a bar count: A's rows live in
        // the file over [entry, lowEndEnd + CUT_MS], i.e. four steps + the tail, 8080 ms of A's own
        // file at speed 1. (The spec's old 3*aBar = 6000 was short of the four-step gesture.)
        assertEquals(8_080L, plan.sourceSpanMs);
        assertEquals(220_000L, plan.sourceFromMs);
        // The material window is that passage plus the junction search's own band (round 3: ±2 bars
        // of the outgoing's grid, narrowed to what the separation budget has left) and the lead the
        // take's alignment wants on either side of the target.
        assertEquals(960L, plan.searchBandMs);
        assertEquals(219_750L - plan.searchBackMs - StemFusion.A_TAIL_SLACK_MS, plan.materialFromMs);
        assertEquals(12_000L, plan.materialWindowMs);
        assertEquals(250L, plan.junctionShiftMs);
        assertTrue(plan.materialWindowMs <= StemFusion.FUSION_TAIL_MAX_MS);
        assertTrue("the fusion stays inside the vocal-free window", plan.fusionEndMs <= 35_000L);
        assertTrue(plan.phaseMatched);
        assertEquals(0d, plan.phaseErrorMs, 1e-9);
        assertEquals(0d, plan.lockError, 1e-9);
        assertEquals("a fusion splices nothing any more", 0, plan.splices());
        assertEquals("[the hold, the drums' fade, the low end's fade]", 3, plan.recedeMs().length);
        // No measurement was handed in, so neither preference moved the junction.
        assertFalse(plan.grooveAtJunction);
        assertFalse(plan.quietAtJunction);
        // The incoming's bed arrives over one bar of its own grid — which is now the same instant
        // its drums and low end begin to rise from, under the outgoing's hold.
        assertEquals(plan.arriveStartMs, plan.bedFadeMs);
        assertEquals(2_000L, plan.bedFadeMs - plan.entryMs);
    }

    @Test
    public void aJunctionWhereTheOutgoingIsNotSingingIsPreferred() {
        // Two lines inside the search band and inside the material window: 219000 (nearest, 750
        // away) and 220500 (750 the other way). The outgoing's voice is measured quiet in the
        // second one's own bar only, so the preference moves the junction onto it -- the spec's
        // "stop between phrases rather than mid-word" -- and the deck is cut 750 ms later than
        // distance alone would put it.
        double[] aBars = {219_000d, 220_500d};
        StemFusion.Input in = new StemFusion.Input(240_000L, 20_000L, 35_000L, 15_000L,
                500d, 0d, 500d, 0d, 1d, aBars, bars(2000d, 0d, 120),
                (atMs, beatMs) -> atMs == 220_500L);
        StemFusion.Plan quiet = StemFusion.plan(in);
        assertTrue(quiet.reason, quiet.valid);
        assertEquals(220_500L, quiet.junctionMs);
        assertTrue(quiet.quietAtJunction);
        assertEquals(750L, quiet.junctionShiftMs);

        // No measurement at all: the nearest line wins, which is the preference declining.
        StemFusion.Plan plain = StemFusion.plan(input(240_000L, 20_000L, 35_000L, 500d, 500d,
                aBars, bars(2000d, 0d, 120)));
        assertTrue(plain.reason, plain.valid);
        assertEquals(219_000L, plain.junctionMs);
        assertFalse(plain.quietAtJunction);

        // A line outside the search band is not a candidate at all, even though the preference
        // would have taken it: 224000 is 4250 ms from the target, past the 960 ms the band reaches
        // forward of it (the round-6 shape spends most of the separation budget on the material).
        StemFusion.Plan tooFar = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L,
                15_000L, 500d, 0d, 500d, 0d, 1d, new double[]{219_000d, 224_000d},
                bars(2000d, 0d, 120), (atMs, beatMs) -> atMs == 224_000L));
        assertTrue(tooFar.reason, tooFar.valid);
        assertEquals(219_000L, tooFar.junctionMs);
        assertFalse(tooFar.quietAtJunction);
    }

    /**
     * ⚠️ Round 3's junction preference, and the reason it exists: the pair the user listened to had
     * its nearest bar line on a pad-only breakdown of the outgoing track, so the fusion carried a
     * pad where a groove should have been and the junction stepped −7 dB. A bar where the outgoing's
     * own drums or low end are playing wins over a nearer one (and over a quieter one) — the fusion
     * then carries the outgoing track's rhythm out of the handover instead of its pad.
     */
    @Test
    public void aJunctionWhereTheOutgoingGrooveIsPlayingWins() {
        double[] aBars = {218_000d, 220_000d};
        // ⚠️ Round 6: the gesture eats the separation budget (4 steps + two leads of A's own file),
        // so the search band is narrow — 960 ms each way on a 500 ms beat, which would leave the
        // groove preference a single candidate to choose from. This fixture uses 200 ms beats so
        // the band is wide enough for the preference to be a choice at all: 1600 ms forward and
        // 5120 ms back, which contains both lines.
        double beat = 200d;
        // The nearest line (220000, 250 ms away) is quiet but has no groove; 218000 is 1750 ms
        // away, loud in the low end, and its voice is not quiet. The groove decides.
        StemFusion.Plan plan = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L,
                15_000L, beat, 0d, beat, 0d, 1d, aBars, bars(800d, 0d, 300),
                (atMs, beatMs) -> atMs == 220_000L, (atMs, beatMs) -> atMs == 218_000d));
        assertTrue(plan.reason, plan.valid);
        assertEquals(218_000L, plan.junctionMs);
        assertTrue(plan.grooveAtJunction);
        assertFalse("the groove beats the quieter line", plan.quietAtJunction);
        assertEquals(-1_750L, plan.junctionShiftMs);

        // Among two lines that both have the groove, the nearer one wins.
        StemFusion.Plan both = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L,
                15_000L, beat, 0d, beat, 0d, 1d, aBars, bars(800d, 0d, 300),
                StemFusion.NO_VOICE_MEASUREMENT, (atMs, beatMs) -> true));
        assertTrue(both.reason, both.valid);
        assertEquals(220_000L, both.junctionMs);
        assertTrue(both.grooveAtJunction);

        // And with no groove anywhere the search falls back to today's choice: the nearest line
        // (the quiet one, when one is quiet), which is what the make-up gain below then answers.
        StemFusion.Plan none = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L,
                15_000L, beat, 0d, beat, 0d, 1d, aBars, bars(800d, 0d, 300),
                (atMs, beatMs) -> atMs == 220_000L));
        assertEquals(220_000L, none.junctionMs);
        assertTrue(none.quietAtJunction);
        assertFalse(none.grooveAtJunction);
    }

    @Test
    public void theJunctionTheDeckIsCutOnIsAlwaysALineOfTheOutgoingGrid() {
        // Every line of every grid is a candidate only within +/-1 bar of the target, and the one
        // chosen is always a line of the grid itself: the boundary cuts the live deck at this
        // position, and a position between two lines is a beat nobody measured.
        double[] aBars = bars(2000d, 125d, 120);
        StemFusion.Plan plan = StemFusion.plan(input(240_000L, 20_000L, 35_000L, 500d, 500d,
                aBars, bars(2000d, 0d, 120)));
        assertTrue(plan.reason, plan.valid);
        boolean onGrid = false;
        for (double at : aBars) if (Math.round(at) == plan.junctionMs) onGrid = true;
        assertTrue("the junction is one of the outgoing track's own bar lines", onGrid);
        // target 219750 +/- 2000: the nearest line of this grid is 220125 (375 away), where the
        // plain 2000 ms grid's would have been 220000.
        assertEquals(220_125L, plan.junctionMs);
    }

    @Test
    public void thePhaseMatchIsReportedAndTheFallbackIsTheSameLine() {
        // The candidates are whole bars of the incoming's grid, so they share a beat phase: the
        // choice among them cannot change the phase, and the rule degenerates to "the first bar
        // line at or after the content start" -- which is the spec's own fallback. What the
        // measurement is still good for is being *reported*: this grid's downbeat sits a quarter
        // beat off the outgoing's, so the seam is a quarter of a beat out and says so.
        double[] bBars = bars(2000d, 250d, 120);
        double[] aBars = bars(2000d, 0d, 120);
        StemFusion.Plan plan = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L,
                15_000L, 500d, 0d, 500d, 0d, 1d, aBars, bBars,
                StemFusion.NO_VOICE_MEASUREMENT));
        assertTrue(plan.reason, plan.valid);
        assertEquals(16_250L, plan.entryMs);
        // A's phase at the junction (220000) is 0; B's at the entry (16250) is 250 ms into its own
        // bar -- half a beat out, which is as much as a whole-bar candidate can be while still
        // being the second half of the bar rather than the first.
        assertEquals(250d, plan.phaseErrorMs, 1e-9);
        assertTrue("a 250 ms match is not a match", !plan.phaseMatched);
        assertEquals(plan.entryMs, 16_250L);
    }

    @Test
    public void thePhaseDistanceIsCircular() {
        // 10 ms and 490 ms in a 500 ms period are 20 ms apart, not 480: the difference of two
        // phases is a distance on a circle, and the plain subtraction is not one.
        assertEquals(20d, StemFusion.phaseDistance(10d, 490d, 500d), 1e-9);
        assertEquals(480d, Math.abs(10d - 490d), 1e-9);
        assertEquals(0d, StemFusion.phaseDistance(0d, 500d, 500d), 1e-9);
        assertEquals(50d, StemFusion.phaseDistance(100d, 50d, 500d), 1e-9);
    }

    @Test
    public void everyValidityClauseRefusesTheFusionOnItsOwn() {
        // A grid, B grid, a known blend, the tempo lock, the cost bound, a junction inside the
        // outgoing's file, a known content start, the fusion inside the vocal-free window -- plus
        // the two the renderer owns (the outgoing's file has to hold the passage, and the window
        // that gets separated has to fit the cost bound). Each one on its own.
        assertTrue(StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L, 15_000L,
                500d, 0d, 500d, 0d, 1d, new double[0], bars(2000d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT)).reason.contains("outgoing track"));
        // ⚠️ Round 6: the incoming's measured LINES are not a clause any more — only its beat grid
        // is. A null (or empty) array is a head decode that stopped early or a downbeat estimate that
        // found nothing, and the entry falls back to lines built from the beat grid itself (see
        // StemFusionEntryWindowTest for the device pair that made this a bug rather than a
        // nicety). What still refuses, and refuses by name, is a track with no grid at all.
        StemFusion.Plan headless = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L,
                15_000L, 500d, 0d, 500d, 0d, 1d, bars(2000d, 0d, 120), null,
                StemFusion.NO_VOICE_MEASUREMENT));
        assertTrue(headless.describe(), headless.valid);
        assertTrue("and the plan says its entry came from the beat grid", headless.entryFromBeatGrid);
        assertTrue(StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L, 15_000L,
                500d, 0d, 0d, 0d, 1d, bars(2000d, 0d, 120), bars(2000d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT)).reason.contains("incoming track"));
        assertTrue(StemFusion.plan(new StemFusion.Input(240_000L, 0L, 35_000L, 15_000L,
                500d, 0d, 500d, 0d, 1d, bars(2000d, 0d, 120), bars(2000d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT)).reason.contains("blend length"));
        // ⚠️ Round 5: a 1000 ms against a 500 ms beat is a 2:1 RELATION now, so nothing refuses it
        // — the deck-level lock is 100% out (asserted below, it is not the gate any more) but the
        // two BAR grids coincide: one bar of the outgoing's grid is two of the incoming's, so every
        // swap still lands on a line of both, which is all the gesture needs.
        StemFusion.Plan twoToOne = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L,
                15_000L, 1000d, 0d, 500d, 0d, 1d, bars(4000d, 0d, 60), bars(2000d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT));
        assertTrue(twoToOne.describe(), twoToOne.valid);
        assertEquals(2, twoToOne.relation.p);
        assertEquals(1, twoToOne.relation.q);
        assertEquals("one step is two bars of the incoming's grid", 4_000d, twoToOne.barStepMs, 1e-6);
        assertEquals("the deck itself is not locked", 1d, StemFusion.lockError(1000d, 500d, 1d),
                1e-9);
        // ...and it is checked before anything is decoded, where it also passes.
        assertNull(StemFusion.refusal(240_000L, 20_000L, 35_000L, 15_000L, 1000d, 500d, 1d));
        assertTrue(StemFusion.possible(240_000L, 20_000L, 35_000L, 15_000L, 1000d, 500d, 1d));
        // A pair in no relation at all (1.2 is 20% from 1 and from 3:2) still refuses nothing: it
        // is the SLAM.
        assertNull(StemFusion.refusal(240_000L, 20_000L, 35_000L, 15_000L, 600d, 500d, 1d));
        // The cost bound, on a pair that DOES lock: two 1500 ms beats are bars of 6 s, so the
        // passage is 12 s of the incoming's file and the material window 14 s of A's.
        assertTrue(StemFusion.plan(new StemFusion.Input(400_000L, 20_000L, 80_000L, 15_000L,
                1500d, 0d, 1500d, 0d, 1d, bars(6000d, 0d, 60), bars(6000d, 0d, 60),
                StemFusion.NO_VOICE_MEASUREMENT)).reason.contains("one render may separate"));
        assertTrue(StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L, -1L,
                500d, 0d, 500d, 0d, 1d, bars(2000d, 0d, 120), bars(2000d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT)).reason.contains("own start is not known"));
        // The outgoing deck is cut at the junction, so material past the end of its file does not
        // exist: a short blend and a long passage is a fusion that would run off the end of A.
        assertTrue(StemFusion.plan(input(100_000L, 4_000L, 20_000L, 500d, 500d,
                bars(2000d, 0d, 60), bars(2000d, 0d, 60))).reason.contains("file ends"));
        // And the whole fusion has to stay inside the window the incoming's vocals are out for.
        // ⚠️ The blend has to be long enough for the carried rows to exist in A's file at all
        // (round 6's gesture takes 8080 ms of it from the junction on, i.e. a blend of about
        // 8.8 s at a 500 ms beat — the measured real pairs' blends are 15 s), so a fixture that is
        // meant to fail on the VOCAL window has to clear that clause: 9 s of blend does, and 9.5 s
        // of removal leaves the fusion's 8 s from a 2 s entry 10 s, half a second too late.
        StemFusion.Plan late = StemFusion.plan(input(200_000L, 9_000L, 9_500L, 500d, 500d,
                bars(2000d, 0d, 120), bars(2000d, 0d, 120)));
        assertFalse(late.reason, late.valid);
        assertTrue(late.reason, late.reason.contains("past the 9500ms"));
        assertTrue(StemFusion.possible(240_000L, 20_000L, 35_000L, 15_000L, 500d, 500d, 1d));
        assertNull(StemFusion.refusal(240_000L, 20_000L, 35_000L, 15_000L, 500d, 500d, 1d));
    }

    /** A pair in no relation at all: 600 ms against 500 ms is 20% from 1 and 40% from 3:2, so
     *  {@link StemFusion#relationOf} answers null and the plan is a SLAM. */
    private static StemFusion.Plan slamFixture() {
        StemFusion.Plan plan = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L,
                15_000L, 600d, 0d, 500d, 0d, 1d, bars(2400d, 0d, 120), bars(2000d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT));
        assertTrue(plan.reason, plan.valid);
        assertTrue(plan.describe(), plan.slam);
        return plan;
    }

    /**
     * ⚠️ <b>A slam's own acceptance, end to end.</b> Nothing in this file ever measured a slam before
     * round 6's tenth pass, which is why two arithmetic defects shipped: the arrival instants came
     * from the FUSION shape's two-step hold while a slam's window is one step plus its splice, so the
     * "after their swap" span began past the window's end, held no samples, and answered −240 dBFS —
     * and every slam on the device was refused with "the incoming's own drums never arrive
     * (-240.0 dBFS after their swap)" while the same slice, read at the slam's own instants, holds the
     * incoming's real kit at −10.3 dBFS. This test fails exactly that way without the fix.
     */
    @Test
    public void aSlamPassesItsOwnAcceptance() {
        StemFusion.Plan plan = slamFixture();
        StemFusion.Report report = run(plan, 1d, true, cleanOutgoing(30d), cleanIncoming(30d),
                0.5d, 0.5d, true);
        println("%s", report.describe());
        assertTrue(report.describe(), report.acceptable);
        assertFalse("the refusal the device saw: " + report.failures,
                report.failures.contains("never arrive"));
        // The arrival span IS empty on a slam — the incoming's rows arrive on the line that ends the
        // file's window, and the body's own audio continues there — so the measurement reports
        // "unknown" rather than the floor, and the clause declines instead of refusing.
        assertTrue("an empty span is not digital silence: " + report.incomingDrumsAfterDb,
                Double.isNaN(report.incomingDrumsAfterDb));
        assertTrue("nor the low end's: " + report.incomingBassAfterDb,
                Double.isNaN(report.incomingBassAfterDb));
        // The rows it CAN measure are the carried ones, and they are the table's.
        assertEquals(report.sourceDrumsDb, report.carriedDrumsDb, 3d);
        assertEquals(report.sourceBassDb, report.carriedBassDb, 3d);
        assertEquals(StemFusion.A_OTHER_DB, report.carriedMelodyDb - report.sourceMelodyDb, 3d);
        // The pulse clause is skipped for a slam by design (its window is one bar and its gesture is a
        // cut), which is why the report says 0 of 0 beats rather than 0 of N.
        assertEquals(0, report.beats);
        assertTrue(report.describe(), report.stepMeasured);
        assertTrue(report.describe(),
                Math.abs(report.junctionStepDb) <= StemFusion.JUNCTION_STEP_MAX_DB);
    }

    /**
     * ⚠️ And the slam's splice RUNS. Its rows fall from unity to the floor over {@link
     * StemFusion#CUT_MS} from the line — an equal-power hand-over, the arrival rising as the
     * departure falls — which needs the window to contain it: `fusionEndMs` is the END of the splice,
     * not the line, so `gainAt`'s own guard lets the splice happen. Before the fix the window ended ON
     * the line, the guard sent [swap, swap + CUT) to the floor, and the outgoing's rows went unity →
     * exactly 0 in one sample: the instantaneous cut the user ruled out (「不要让它戛然而止」),
     * measured on the device's own render as a 0.3227 jump against the signal's own 99.9th percentile
     * of 0.2648 (1.2x) where the spliced line reads 0.0913 (0.3x).
     */
    @Test
    public void theSlamSpliceRunsPastItsOwnLine() {
        StemFusion.Plan plan = slamFixture();
        long swap = plan.swapMs;
        assertEquals("the window contains the splice: fusionEnd is its END",
                StemFusion.CUT_MS, plan.fusionEndMs - swap);
        assertEquals(swap, plan.holdEndMs);
        assertEquals("the incoming arrives over the same splice",
                swap + StemFusion.CUT_MS, plan.arriveEndMs);
        assertEquals("and A's rows are at unity up to the line",
                1d, StemFusion.gainAt(plan, true, StemGesture.Stem.DRUMS, swap), 1e-9);
        assertEquals("half way through the splice they are at cos(pi/4)",
                Math.cos(Math.PI / 4),
                StemFusion.gainAt(plan, true, StemGesture.Stem.DRUMS,
                        swap + StemFusion.CUT_MS / 2), 1e-6);
        assertEquals("and at the floor where it ends", 0d,
                StemFusion.gainAt(plan, true, StemGesture.Stem.DRUMS, swap + StemFusion.CUT_MS),
                1e-9);
        assertEquals("the arriving side mirrors it", 0d,
                StemFusion.gainAt(plan, false, StemGesture.Stem.DRUMS, swap), 1e-9);
        assertEquals(Math.sin(Math.PI / 4),
                StemFusion.gainAt(plan, false, StemGesture.Stem.DRUMS,
                        swap + StemFusion.CUT_MS / 2), 1e-6);
        assertEquals("and is at unity where it ends", 1d,
                StemFusion.gainAt(plan, false, StemGesture.Stem.DRUMS, swap + StemFusion.CUT_MS),
                1e-9);
        // Monotone on both sides, so no element comes back and the sum only falls.
        for (StemGesture.Stem row : new StemGesture.Stem[]{StemGesture.Stem.DRUMS,
                StemGesture.Stem.BASS}) {
            double previous = Double.MAX_VALUE;
            for (long at = plan.entryMs; at <= plan.fusionEndMs; at += 5L) {
                double g = StemFusion.gainAt(plan, true, row, at);
                assertTrue(row + " must never rise again", g <= previous + 1e-9);
                previous = g;
            }
            double rising = -1d;
            for (long at = plan.entryMs; at <= plan.fusionEndMs; at += 5L) {
                double g = StemFusion.gainAt(plan, false, row, at);
                assertTrue(row + " must never fall again", g >= rising - 1e-9);
                rising = g;
            }
        }
    }

    /**
     * ⚠️ <b>The slam's LENGTH, which is a listening decision and not an arithmetic one.</b>
     * {@link StemFusion#SLAM_STEPS} bars of the incoming's own grid — <b>three</b>, since
     * RULE_VERSION 10; one bar until it — and this test pins the whole passage to that single number,
     * because the render the user chose has to be internally consistent and not merely longer: the
     * outgoing's rows are at unity for the WHOLE hold (that is the audible change — at one bar they
     * were already gone a bar before this one), the line everything changes hands on is three bars
     * in, the splice that de-clicks that hand-over ends on the file's own end, and the material the
     * take reads out of A's own file is that same three bars plus the splice.
     *
     * <p>The measurement behind the number: four renders of one pair at 1/2/3/4 bars — 2.1 / 4.2 /
     * 6.2 / 8.3 s on the incoming's own 2 043.94 ms bar — and the user chose 3, i.e. 6.2 s, which is
     * the window here in miniature (3 × 2 000 + 80 = 6 080 ms). 5 and 6 bars are refused outright by
     * the arithmetic — the passage and the junction search's band are paid for out of the same cost
     * bound — so three sits below a real ceiling rather than in the middle of a range that works.
     */
    @Test
    public void aSlamHoldsForThreeBars() {
        StemFusion.Plan plan = slamFixture();
        long bar = Math.round(plan.barStepMs);
        assertEquals("the fixture's own bar", 2_000L, bar);
        assertEquals("and the slam's length is three bars", 3, StemFusion.SLAM_STEPS);
        assertEquals("the line every element changes hands on is three bars in",
                3L * bar, plan.swapMs - plan.entryMs);
        assertEquals("the hold is that line", plan.swapMs, plan.holdEndMs);
        assertEquals("the splice ends on the file's own end",
                3L * bar + StemFusion.CUT_MS, plan.fusionEndMs - plan.entryMs);
        assertEquals("and the window is that same span", plan.fusionEndMs - plan.entryMs,
                plan.windowMs);
        // ⚠️ The material the take reads out of A's own file is that span too — three bars plus the
        // splice — so the render has A's rows for the whole passage it writes, not two bars of them.
        assertEquals("A's rows span the three bars and the splice",
                3L * bar + StemFusion.CUT_MS, plan.sourceSpanMs);
        // The audible difference, in the table's own gains: A is still at unity a full bar into the
        // passage (where the one-bar slam had already handed over), and B's rows are still absent
        // there — the hand-over happens on the line three bars in, and only there.
        assertEquals("A's drums are still at unity a bar into the passage", 1d,
                StemFusion.gainAt(plan, true, StemGesture.Stem.DRUMS, plan.entryMs + bar), 1e-9);
        assertEquals("A's low end too", 1d,
                StemFusion.gainAt(plan, true, StemGesture.Stem.BASS, plan.entryMs + bar), 1e-9);
        assertEquals("and B's own drums have not arrived yet", 0d,
                StemFusion.gainAt(plan, false, StemGesture.Stem.DRUMS, plan.entryMs + bar), 1e-9);
        assertEquals("at the line A is still at unity", 1d,
                StemFusion.gainAt(plan, true, StemGesture.Stem.DRUMS, plan.swapMs), 1e-9);
        assertEquals("two bars in, still unity: this is where the one-bar slam was already silent",
                1d, StemFusion.gainAt(plan, true, StemGesture.Stem.BASS,
                        plan.entryMs + 2L * bar), 1e-9);
        // The bed is the incoming's own content and it has been up since the first bar, which is
        // what carries the ear across the three-bar hold (see `bedGain`).
        assertEquals("B's bed is up a bar in", 1d,
                StemFusion.gainAt(plan, false, StemGesture.Stem.OTHER, plan.bedFadeMs), 1e-9);
    }

    /**
     * ⚠️ <b>The incoming side of the gesture, pinned as numbers.</b> The pending placement change
     * (the junction moves to the outgoing's own file end, so A's own ending is heard instead of a
     * passage cut out of it) touches the A side only: how long the file's copy of A is, and where
     * A's rows stop. B's side — the bed's rise, the drums' and low end's arrival, the window and
     * where the body takes over — is the SAME search and the SAME table it is today, so these
     * numbers must not move: a change to one of them is a change to the incoming track's part of the
     * gesture, which the user has not asked for and did not approve.
     *
     * <p>They are literals on purpose. If a later change makes one of them fail, that change is
     * either the placement (and then B's side has moved with it, which is the bug this pins) or a
     * deliberate new gesture (and then the user's approval is what has to be checked, not this test).
     */
    @Test
    public void theIncomingSidesLinesArePinnedForThePlacementChange() {
        StemFusion.Plan plan = fixture();
        assertEquals("the deck still starts on the incoming's own bar line", 16_000L, plan.entryMs);
        assertEquals("B's bed still rises from the entry over one bar of ITS grid", 18_000L,
                plan.bedFadeMs);
        assertEquals("its drums and low end still arrive one step before the hold's end",
                plan.holdEndMs - Math.round(plan.barStepMs), plan.arriveStartMs);
        assertEquals("and reach unity where the hold does", plan.holdEndMs, plan.arriveEndMs);
        assertEquals("the window is still the table's own span", 8_000L, plan.windowMs);
        assertEquals("and B's rows are at unity where the body's own audio takes over",
                plan.entryMs + plan.windowMs, plan.fusionEndMs);
        assertEquals("the arrival's own length is still one step", Math.round(plan.barStepMs),
                plan.arriveEndMs - plan.arriveStartMs);
    }

    /**
     * The four pairings the round-18 prototype measured on real material, with the numbers the
     * harness's own scan produced (`D:\qplayer-dev\harness\fusion\{grids,qpair-scan}.json`):
     * the grids, the blend and the content start are the harness's, and the verdict is what this
     * planner makes of them.
     *
     * <p>Round 5 lives in the first three of them: two fuse as relations (their bar grids coincide)
     * and one as the SLAM the user asked for, which is the whole point of the relation family — the
     * old candidate clause refused every pair whose beat periods are not equal, and the measurement
     * of the 1260-pair library showed that is the same set as the pairs that fade today. The
     * fourth keeps round 18's bargain exactly, bit for bit.
     */
    @Test
    public void theFourRealPairsVerdicts() {
        // ⚠️ Round 5: three of these four now fuse — that is the whole point of the relation
        // family. The old clause refused every pair whose beat periods are not equal, which the
        // measurement of the 1260-pair library showed is the same set as the pairs that fade today
        // (87.6% of all pairs were refused by it alone).
        //
        // owa -> paradise: 476.6 ms against 937.6 ms — a ratio of 1.9672, 1.67% from the 2:1
        // relation, so the swaps land on one bar of paradise's grid (two of owa's). The prototype
        // rendered this pair (and the user heard its junction); under round 5 it is a FUSION.
        assertRelation(1, 2, 476.598639d, 937.55102d, 1d, 0.0167d, "audio_owa", "audio_paradise");
        // ⚠️ Round 6's cost bound, with the numbers, on that same pair: its step is one bar of the
        // incoming's grid (3750.2 ms) and its carry is read at 0.9836, so A's rows need
        // ceil((4*3750.2 + 80)/0.9836) = 15 333 ms of A's own file and the separation window
        // 17 333 ms with the two leads — over the unison cap (12 000, which refused it) and inside
        // the relative one (20 000, which is why the two caps differ).
        assertEquals("the passage A's rows need from A", 15_333L,
                StemFusion.sourceSpanMs(937.55102d, 0.98359d));
        assertEquals("and the window one render separates, with both leads",
                17_333L, StemFusion.sourceSpanMs(937.55102d, 0.98359d) + 2L * 1_000L);
        assertTrue("which is over the unison cap", 17_333L > StemFusion.FUSION_TAIL_MAX_MS);
        assertTrue("and inside the relative one", 17_333L <= StemFusion.RELATIVE_TAIL_MAX_MS);
        // obsessed -> owa: 838.8 ms against 476.6 ms — 13.6% from the nearest relation (1/2), i.e.
        // in none, so it is the SLAM the user asked for: one bar of the incoming's grid, no beat
        // matching, every element changing hands on the same line.
        assertSlam(838.77551d, 476.598639d, "audio_obsessed", "audio_owa");
        // owa -> audio1: 476.6 ms against 459.6 ms — 3.69% from the unison, inside
        // RELATION_TOLERANCE and outside LOCK_TOLERANCE, so it is a 1:1 RELATIVE whose residual the
        // carry's stretch absorbs (the deck itself plays at x1.0).
        assertRelation(1, 1, 476.598639d, 459.637188d, 1d, 0.0369d, "audio_owa", "audio1");
        // huai -> owa: 472.6 ms against 476.6 ms — 0.84% out, inside LOCK_TOLERANCE, which is the
        // case that keeps round 18's bargain exactly. Its anchors are the prototype's own.
        assertLocked(true, 472.60771d, 476.598639d, 1d, 0.0084d, "audio_huai", "audio_owa");

        StemFusion.Plan plan = StemFusion.plan(new StemFusion.Input(187_241L, 15_000L, 15_000L,
                0L, 472.60771d, 210d, 476.598639d, 12d, 1d,
                bars(1890.43084d, 210d, 100), bars(1906.394556d, 12d, 100),
                StemFusion.NO_VOICE_MEASUREMENT));
        assertTrue(plan.reason, plan.valid);
        // The prototype's own anchors, to the millisecond: the junction is the bar line 248 ms
        // after the target, the deck starts on B's first bar line (12 ms), and all of A is gone
        // four steps into B's file — inside the 15 s the vocals are out for.
        assertEquals(171_991L, Math.round(187_241d - 250d - 15_000d));
        assertEquals(172_239L, plan.junctionMs);
        assertEquals(12L, plan.entryMs);
        long step = Math.round(476.598639d * 4d);
        assertEquals("the hold at unity is two steps", 12L + 2L * step, plan.swapMs);
        assertEquals("the low end starts to recede with the drums' hold over", plan.swapMs,
                plan.bassMs);
        assertEquals("the drums' fade is one step", 12L + 3L * step, plan.drumsEndMs);
        assertEquals("the low end's and the melodic row's fade is two", 12L + 4L * step,
                plan.fusionEndMs);
        assertEquals(Math.round(4d * 1906.394556d), plan.windowMs);
        // The carry's own span: 4 steps of B's grid (7625.6 ms) + the 80 ms tail = 7706 ms of A's
        // file at speed 1. The spec's old 3*aBar (5671 ms) would have been short of the gesture.
        assertEquals(7_706L, plan.sourceSpanMs);
        // Round 3's search band: ±2 bars of A's grid (3780.9 ms) narrowed to the 2294 ms the
        // separation budget has left after the carry, i.e. 1147 ms each way.
        assertEquals(1_147L, plan.searchBandMs);
        assertEquals(1_147L, plan.searchBackMs);
        assertEquals(171_991L - plan.searchBackMs - StemFusion.A_TAIL_SLACK_MS,
                plan.materialFromMs);
        assertEquals("the band spends the whole cap", StemFusion.FUSION_TAIL_MAX_MS,
                plan.materialWindowMs);
        assertEquals(248L, plan.junctionShiftMs);
    }

    /**
     * ⚠️ Round 5's quiet-passage clause, on the shape the user described: a track whose last ten
     * seconds are its own fade. The body level is measured over the material the render holds, and
     * a passage more than {@link StemFusion#QUIET_PASSAGE_DB} under it is not a line a fusion may be
     * cut on — the search looks further BACK first (the earlier line is the one that has the body),
     * and only a band with no body-level line at all refuses.
     */
    @Test
    public void aPassageInsideAFadeIsNotUsedIfAnEarlierLineHasBody() {
        // A's bar lines every 2000 ms from 12 000: 200 000 (the nearest to the target 219 750...
        // no: this fixture's target is 219 750 only for the other inputs). A's grid is built so the
        // target's own line is deep in a fade and the line 6 s earlier (202 000) is the body.
        double[] aBars = bars(800d, 100_000d, 200);
        StemFusion.BodyLevel body = new StemFusion.BodyLevel() {
            @Override
            public double bodyDb() {
                return -8d;
            }

            @Override
            public double passageDropDb(long atMs, long passageMs) {
                // The fade: everything from 218 000 on is 30 dB down, everything before it is 1 dB
                // under the body (so within QUIET_PASSAGE_DB).
                return atMs >= 218_000L ? 30d : 1d;
            }
        };
        StemFusion.Plan plan = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L,
                15_000L, 200d, 0d, 200d, 0d, 1d, aBars, bars(800d, 0d, 300),
                StemFusion.NO_VOICE_MEASUREMENT, StemFusion.NO_GROOVE_MEASUREMENT, body, -1L));
        assertTrue(plan.reason, plan.valid);
        assertTrue("the junction is one the track is still playing on: " + plan.junctionMs,
                plan.junctionMs < 218_000L);
        assertTrue("and the search had to reach back for it: " + plan.junctionShiftMs,
                plan.junctionShiftMs < -1000L);
        assertTrue("and it is at the body level", plan.bodyLevelAtJunction);
        assertTrue(plan.describe(), plan.describe().contains("body level"));

        // The same material with no body-level line anywhere: the clause refuses, and its reason
        // carries the numbers it measured.
        StemFusion.BodyLevel alwaysFading = new StemFusion.BodyLevel() {
            @Override
            public double bodyDb() {
                return -8d;
            }

            @Override
            public double passageDropDb(long atMs, long passageMs) {
                return 30d;
            }
        };
        StemFusion.Plan refused = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L,
                15_000L, 200d, 0d, 200d, 0d, 1d, aBars, bars(800d, 0d, 300),
                StemFusion.NO_VOICE_MEASUREMENT, StemFusion.NO_GROOVE_MEASUREMENT, alwaysFading,
                -1L));
        assertFalse(refused.reason, refused.valid);
        assertTrue(refused.reason, refused.reason.contains("below the track's own body"));
        assertTrue(refused.reason, refused.reason.contains("30.00"));
    }

    /**
     * ⚠️ <b>Round 25's coupling, and the fixture the round is judged on.</b> The landing is not a
     * line a runway before the incoming's voice any more (round 21): it is the line that puts the
     * incoming's own first sustained voice where the outgoing's own rows have left the passage. The
     * outgoing's departure here is the gesture's own end (no separated material is handed in, which
     * is {@link StemFusion#NO_EXIT_MEASUREMENT} — the fallback, and the round-6 behaviour bit for
     * bit), i.e. 8 000 ms into the passage, so the deck starts at 12 125 − 8 000 = 4 125, snapped
     * down to the grid's 4 000.
     *
     * <p>⚠️ What this replaced, and why the old number was a defect: the round-21 landing was
     * 10 000 (the line a runway before the voice) and the voice then arrived 2 125 ms into the ramp
     * while the outgoing's own material runs for 8 000 ms — the incoming singing over the fused
     * passage, which is the user's 「改完后…在过渡时就播放了人声」. The coupling's landing is a real
     * 4 000 ms of intro skipped <em>because the departure asks for it</em>, and the voice lands
     * 125 ms later than the departure (the grid's own quantisation of the coupling's 4 125).
     *
     * <p>And a voice that is there from the start is skipped by round 33's own two bars: the
     * coupling's landing is before the content start at every line this track has, so the floor
     * decides where the deck starts (4 000) and the gate then HOLDS the voice until the outgoing's
     * rows have left — never the other way round.
     */
    @Test
    public void theLandingIsTheOneThatPutsTheVoiceWhereTheOutgoingLeft() {
        StemFusion.Plan skipped = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 40_000L,
                0L, 500d, 0d, 500d, 0d, 1d, bars(2000d, 0d, 120), bars(2000d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT, StemFusion.NO_GROOVE_MEASUREMENT,
                StemFusion.NO_BODY_MEASUREMENT, 12_125L));
        assertTrue(skipped.reason, skipped.valid);
        assertEquals("the coupling's own landing (12 125 − 8 000), snapped to the grid", 4_000L,
                skipped.entryMs);
        assertEquals("so 4 000 ms of intro are never played", 4_000L, skipped.skippedIntroMs);
        assertEquals(12_125L, skipped.firstVocalMs);
        assertFalse("and the departure was the gesture's own end, not a measurement",
                skipped.coupling.measured);
        assertEquals("the departure is the passage's own end, in the outgoing's file",
                skipped.junctionMs + skipped.windowMs, skipped.coupling.departureMs);
        assertEquals("8 000 ms into this file from the entry", 8_000L,
                skipped.coupling.departureInFileMs);
        assertEquals("this round's lead+floor moved the instant the voice is placed on to 6 663"
                + " (one bar before the passage's end, floored by the file's own gesture)",
                6_663L, skipped.coupling.floorMs);
        assertEquals("so the voice is heard 1 462 ms later than that placement (12 125 − 6 663"
                + " − 4 000)", 1_462L, skipped.coupling.residualGapMs);
        assertEquals("and nothing of its own singing had to be held", 0L, skipped.coupling.heldMs);
        assertEquals("the gate is the voice's own arrival", 12_125L,
                skipped.coupling.voiceGateMs);
        assertTrue(plan(skipped), skipped.fusionEndMs <= 40_000L);
        assertTrue(skipped.describe(),
                skipped.describe().contains("skipping 4000ms of its intro"));
        assertTrue(skipped.describe(),
                skipped.describe().contains("8125ms after the deck starts"));
        assertTrue("the coupling's own evidence is in the line: " + skipped.describe(),
                skipped.describe().contains("which is the departure to the millisecond")
                        || skipped.describe().contains("first HEARD 1462ms"));

        // The voice from the start: the coupling's landing is before the track's own start, so
        // round 33's floor decides — two bars in (4 000) — and the voice is HELD from its own first
        // line until the outgoing's rows are gone, never stacked on top of them.
        StemFusion.Plan immediate = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 40_000L,
                0L, 500d, 0d, 500d, 0d, 1d, bars(2000d, 0d, 120), bars(2000d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT, StemFusion.NO_GROOVE_MEASUREMENT,
                StemFusion.NO_BODY_MEASUREMENT, 200L));
        assertTrue(immediate.reason, immediate.valid);
        assertEquals("two bars of the incoming's own grid in (round 33's floor)", 4_000L,
                immediate.entryMs);
        assertEquals("and that skip is reported as the skip it is", 4_000L,
                immediate.skippedIntroMs);
        assertEquals("the gate is the file's own gesture (6 663, round 30's floor) measured from"
                + " that landing, not the voice's own 200", 10_663L, immediate.coupling.voiceGateMs);
        assertEquals("and the incoming's own singing is held for 10 463 ms of it", 10_463L,
                immediate.coupling.heldMs);
        assertEquals(0L, immediate.coupling.residualGapMs);
        assertTrue(immediate.describe(), immediate.describe().contains("gate holds 10463ms of its own"
                + " singing out"));
    }

    /**
     * ⚠️ <b>Round 33: the landing may not sit where the incoming's own groove is silent.</b> The
     * listener's own verdict on the two landings rendered for them is 「上一轮的 34800 很完美」, and the
     * measurement behind that ear is in {@link StemFusion.Input#incomingGroove}: the track's drums and
     * low end sit −70…−87 dBFS through its first 19 s, so a landing inside that stretch hands the
     * passage a second deck that is not playing and the fusion's second half becomes one deck fading
     * out instead of two songs crossing over.
     *
     * <p>The clause is opt-in and forward-only, and both halves are pinned here: with the incoming's
     * groove measured (silent until 10 000 ms — the fixture's own quiet intro) the landing walks
     * forward from the coupling's own two bars to the first line that is playing; with no measurement
     * handed in (every earlier round's fixtures) the landing is bit-for-bit where it was.
     */
    @Test
    public void theLandingLeavesTheIncomingsOwnSilentIntro() {
        StemFusion.Groove silentUntil10s = (atMs, beatMs) -> atMs >= 10_000L;
        StemFusion.Plan moved = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 40_000L,
                0L, 500d, 0d, 500d, 0d, 1d, bars(2000d, 0d, 120), bars(2000d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT, StemFusion.NO_GROOVE_MEASUREMENT,
                StemFusion.NO_BODY_MEASUREMENT, 200L, StemFusion.NO_INCOMING_MEASUREMENT, 0, null,
                silentUntil10s));
        assertTrue(moved.reason, moved.valid);
        assertEquals("the first line its own groove is playing on, not the silent two bars the"
                + " coupling's own landing sits on", 10_000L, moved.entryMs);
        StemFusion.Plan unmeasured = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 40_000L,
                0L, 500d, 0d, 500d, 0d, 1d, bars(2000d, 0d, 120), bars(2000d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT, StemFusion.NO_GROOVE_MEASUREMENT,
                StemFusion.NO_BODY_MEASUREMENT, 200L));
        assertTrue(unmeasured.reason, unmeasured.valid);
        assertEquals("and with no incoming groove measured the landing is where it always was",
                4_000L, unmeasured.entryMs);
    }

    /**
     * ⚠️ <b>The direction the coupling closes, and the fixture the user's own defect report is
     * about.</b> The incoming's voice is already singing two bars in, and the outgoing's own rows
     * play right to the passage's end: at every line this track has, the voice's material is there
     * BEFORE the departure. There is nothing to skip to — the deck cannot be started before its own
     * start, and round 33's floor puts it exactly where that voice is (4 000) — so the answer is the
     * gate: the incoming's own singing is HELD until the outgoing's rows have left — 6 663 ms of it,
     * from the voice's own 4 000 to round 30's bound at 10 663 in its file (the passage's end at
     * 8 000 in round 25; the lead and the gesture's own 6 dB point are what moved it) — rather than
     * stacked on them.
     *
     * <p>⚠️ What the round-21 rule did here is the reject the user heard: the deck started at the
     * line a runway before the voice (2 000), the voice arrived 2 000 ms into the ramp, and the
     * outgoing's own material ran for another 6 000 ms underneath it — the incoming singing over the
     * fused passage. And what the ABSOLUTE gate did (round 20's, at the window's own end) is the
     * other reject: the voice held out for the rest of the window, i.e. its first lines gone for no
     * reason the material gives. One measured instant is the whole difference.
     */
    @Test
    public void aVoiceAlreadySingingWhenTheDepartureComesIsHeldAndNotStacked() {
        StemFusion.Plan held = coupled(20_000L, 4_000L, neverQuiet());
        assertTrue(held.reason, held.valid);
        assertEquals("there is nowhere to skip to: the coupling's landing is before the track's own"
                + " start, so round 33's floor decides", 4_000L, held.entryMs);
        assertEquals("so the departure is as late as the passage makes it", 8_000L,
                held.coupling.departureInFileMs);
        assertEquals("the voice's own arrival (4 000) is EARLY, so the gate holds it to the file's"
                + " own gesture (6 663 — round 30's floor, where the outgoing is 6 dB down),"
                + " measured from the landing", 10_663L, held.coupling.voiceGateMs);
        assertEquals("6 663 ms of the incoming's own singing is held, not stacked", 6_663L,
                held.coupling.heldMs);
        assertEquals("and there is no gap to report in the other direction", 0L,
                held.coupling.residualGapMs);
        // ⚠️ Round 30 replaces round 25's own invariant here, and this line is the whole trade:
        // the gate MAY sit before the departure (that is the earlier voice the listener asked for),
        // and it may never sit before the bound — the instant the file's own gesture has taken the
        // outgoing's carried rows 6 dB under unity. The departure is reported beside it as the
        // material's own reading, which is where the voice used to be.
        assertTrue("the gate is never before the bound: " + plan(held),
                held.coupling.voiceGateMs >= held.entryMs + held.coupling.floorMs);
        assertTrue("and it IS before round 25's instant, on purpose: " + plan(held),
                held.coupling.voiceGateMs < held.entryMs + held.coupling.departureInFileMs);
        // And what round 21 would have taken is still reported, as the "before" of the coupling.
        assertEquals("the line a runway before the voice", 2_000L, held.coupling.uncoupledEntryMs);
    }

    /**
     * ⚠️ <b>The entry is always a bar line of the incoming's grid, even when the coupling's own
     * landing is earlier than the track's first one.</b> The content start is mid-bar here (250 ms
     * into a 2 000 ms bar) and the coupling asks for a landing before the track's own beginning
     * (0 − 8 000): starting off the grid is not a fusion, so the design's own plain entry stands —
     * the last line of the incoming's grid at or below round 33's floor, which is 4 000 here (two
     * bars past the content start is 4 250, and the line below it is 4 000) — and the gate holds the
     * incoming's singing until the departure (4 000 + 6 663 = 10 663).
     */
    @Test
    public void aLandingBeforeTheGridIsThePlainEntryAndNotAStartOffTheBar() {
        StemFusion.Plan plan = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 20_000L,
                250L, 500d, 0d, 500d, 0d, 1d, bars(2000d, 0d, 120), bars(2000d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT, StemFusion.NO_GROOVE_MEASUREMENT,
                StemFusion.NO_BODY_MEASUREMENT, 0L, StemFusion.NO_INCOMING_MEASUREMENT, 0,
                neverQuiet()));
        assertTrue(plan.reason, plan.valid);
        assertEquals("a line of the incoming's grid, not the mid-bar content start", 4_000L,
                plan.entryMs);
        assertEquals("and the gate still holds its singing until the outgoing is on its way out"
                        + " (round 30: the file's own gesture, not the passage's end)", 10_663L,
                plan.coupling.voiceGateMs);
        assertEquals(10_663L, plan.coupling.heldMs);
    }

    /**
     * ⚠️ <b>{@code 0} is a measurement, not "unknown".</b> A track whose voice is in its first
     * sample ({@code vocalStartMs} answers 0 — the device's own {@code unhappy} does) is the case
     * where the coupling has nothing to move: the landing the coupling asks for is before the track
     * does, and round 33's floor is what decides — two bars in. The gate then holds every millisecond
     * of its own singing from the track's first sample to the departure (10 663) rather than stacking
     * it on the outgoing's material, and the plan says so. Reading 0 as "no measurement" — which
     * {@code firstVocalMs > 0} does — would report a hold of 0 for exactly the pair the hold is for,
     * which is why the sentinel is {@code -1} and this fixture pins it.
     */
    @Test
    public void aVoiceInTheFirstSampleIsHeldUntilTheDeparture() {
        StemFusion.Plan held = coupled(20_000L, 0L, neverQuiet());
        assertTrue(held.reason, held.valid);
        assertEquals("the deck starts two bars in (round 33's floor) rather than on the track's"
                + " first sample", 4_000L, held.entryMs);
        assertEquals("and the gate waits for the outgoing's own rows", 10_663L,
                held.coupling.voiceGateMs);
        assertEquals("holding all 10 663 ms of it, from the track's own first sample", 10_663L,
                held.coupling.heldMs);
        assertEquals("which is round 30's lead+floor case: the material never falls, so the FILE's"
                + " own gesture is the bound (see theLedVoiceOnAPassageThatNeverFalls...)",
                6_663L, held.coupling.floorMs);
        assertEquals(0L, held.coupling.residualGapMs);
        assertEquals(0L, held.firstVocalMs);
    }

    /**
     * ⚠️ <b>Round 32: where the voice first comes in is read against the vocal row's OWN body, not
     * against the absolute floor the renderer hands in.</b> The incoming track of the pair under
     * test ({@code unhappy}, a 12 s blend) separates with a <b>−25 dBFS presence in its vocal row
     * from about 75 ms on</b> — separation bleed from the backing rather than singing — while its
     * voice really arrives in the 17 s second. Per 1 s window, the median of its 25 ms frames:
     * <b>0-1 s −25.39, 1-2 s −27.28, 2-3 s −29.80, 3-4 s −37.95, 4-15 s −71 … −75</b> (the voice
     * genuinely absent for eleven seconds), <b>16-17 s −24.66, 17-18 s −13.57, 18-19 s −14.30</b>.
     * The absolute floor the renderer passes ({@code DjEdit.SILENT_FRAME_DBFS} = −50) sits 25 dB
     * under that bleed, so the FIRST window already cleared it and {@code vocalStartMs} answered
     * <b>0</b>: the deck started at the track's own beginning and the rendered transition ran into
     * eleven seconds of voice-less intro — the 「不像过渡」 the listener reported.
     *
     * <p>The fixture is that row in its two levels, the bleed and the body 11.8 dB apart (see
     * {@link #vocalRow}), with the body's own second what the reading has to find. The 17-18 s
     * window is the first whose median is the body's — which is what the measured table's own scan
     * says — because a 1 s median needs the body's level in half the window, and the answer is
     * therefore <b>17 000 ms</b>: the body's own second, not the track's first sample.
     */
    @Test
    public void theArrivalIsReadAgainstTheRowsOwnBodyAndNotTheAbsoluteFloor() {
        float[][] vocals = vocalRow(26d, 17_500L, -25.4d, -13.6d);
        assertTrue("the caller's own floor (−50) is cleared by the very first window ("
                        + String.format(java.util.Locale.US, "%.2f", firstSecondMedianDb(vocals))
                        + " dBFS), which is the answer the absolute rule gave this row: 0",
                firstSecondMedianDb(vocals) > DjEdit.SILENT_FRAME_DBFS);
        assertEquals("and the body's own second is the answer instead", 17_000L,
                StemFusion.vocalStartMs(vocals, RATE, 0L, DjEdit.SILENT_FRAME_DBFS));
    }

    /**
     * ⚠️ <b>The case the new floor must not make worse: a row with no voice at all.</b> A
     * separation whose bleed is all the row has measures its OWN bleed as its body — the loud tenth
     * of its windows <em>is</em> the bleed — so the floor sits {@link StemFusion#VOICE_ARRIVAL_DB}
     * under it and the first window still clears it. The answer stays {@code startMs}, exactly
     * where the absolute floor put it: such a row acquires no arrival it does not have, and the
     * deck's landing on it is unchanged.
     */
    @Test
    public void aRowWithNoVoiceMeasuresItsOwnBleedAndAnswersWhereItAlwaysDid() {
        float[][] bleed = vocalRow(26d, 0L, -25.4d, -25.4d);
        assertEquals(0L, StemFusion.vocalStartMs(bleed, RATE, 0L, DjEdit.SILENT_FRAME_DBFS));
        assertEquals("measured from the caller's own start", 240L,
                StemFusion.vocalStartMs(bleed, RATE, 240L, DjEdit.SILENT_FRAME_DBFS));
    }

    /**
     * ⚠️ <b>Round 33: the landing the listener picked, on the pair's own numbers.</b> The round-32
     * rule change above is what makes the arrival readable at all; this is the same pair asking the
     * PLANNER for the answer that was listened to, with {@code firstVocalMs} at the value the rule
     * now measures ({@code 17 000}). The listener's 「跳过一些小节到人声部分」 is <b>4 088 ms</b> of
     * skipped intro, and 4 088 is not a bar line of this track: its grid is
     * {@code 362 + k x 2 043.944} = <b>362 / 2 406 / 4 450 / 6 494</b>, so the app lands on
     * <b>4 450</b> and skips {@code 4 450 - 362 = 4 088} — the same thing read the other way (「2×2044」).
     *
     * <p>What decides it is the ceiling, not the line search: the coupling's own landing
     * ({@code firstVocal - goneFile}) is clamped to {@code removalMs - windowMs}, and on this pair
     * that is {@code 12 000 - 6 212 = 5 788} — {@link StemFusion#SLAM_STEPS} bars of the incoming's
     * grid plus the splice, because these two grids are in no relation and the pair is a SLAM. The
     * last line at or below 5 788 is 4 450 (the next one, 6 494, does not fit the window), which is
     * why the answer is the LATEST affordable line rather than the 2 406 a bar earlier — the line
     * round 21's own rule (a bar of runway before the voice) would have taken, reported beside it as
     * {@code uncoupledEntryMs}.
     *
     * <p>⚠️ <b>One thing about this landing is fixture-honest and worth stating.</b> 4 450 and
     * 2 406 are the two candidates the phase walk may choose between, and their own phases differ by
     * <b>0.056 ms</b> — the rounding of the incoming's own bar ({@code 2 043.944}) to whole ms, not a
     * phase difference worth preferring either way. So the walk only decides between them by the
     * junction's own phase modulo the beat the grids share: on the outgoing's grid used here
     * (600 ms beats, a junction at 226 800 ms) it lands on the side where the later line wins, and
     * a fixture whose outgoing phase sits in the other half of the beat would answer 2 406 — the
     * landing arithmetic above (the ceiling at 5 788, the last line at or below it) is what this
     * round's own change is about, and it is pinned exactly; the phase walk's own tie-break is
     * upstream of it and is not this round's subject.
     */
    @Test
    public void theArrivalMeasuredOnTheProbeIsTheLandingTheListenerChose() {
        StemFusion.Plan plan = slamOf(12_000L, 17_000L, exit(0L));
        assertTrue(plan.reason, plan.valid);
        assertTrue("the pair's grids are in no relation: " + plan.describe(), plan.slam);
        assertEquals("the passage is the slam's own three bars of 2 043.944ms plus the splice",
                6_212L, plan.windowMs);
        assertEquals("so the ceiling on a landing is the blend less the passage", 5_788L,
                plan.removalMs - plan.windowMs);
        assertEquals("the arrival the probe measures is handed in as measured", 17_000L,
                plan.firstVocalMs);
        assertEquals("the deck starts on the last bar line at or below the ceiling — 4 450, and"
                + " not the 2 406 that is a bar of runway before the voice", 4_450L, plan.entryMs);
        assertEquals("which skips 4 088ms of the incoming's intro", 4_088L, plan.skippedIntroMs);
        assertEquals("and the passage still fits the window the vocals are out for", 10_662L,
                plan.fusionEndMs);
        assertTrue("the landing is the coupling's own, pulled back — not a start off the bar: "
                + plan.describe(), plan.entryMs < 5_788L);
        assertTrue("round 21's own landing is far later, which is the defect this round closes: "
                + plan.coupling.uncoupledEntryMs, plan.coupling.uncoupledEntryMs > plan.entryMs);
    }

    /** The pair rounds 32-33 are about, as the planner sees it: the incoming track's own grid (a
     *  510.986 ms beat, so a bar of 2 043.944 ms, phase 362 ms — the measured track) against an
     *  outgoing track in no relation to it at all (600 ms beats are 17.4% out of 1:1 and 21.7% out of
     *  the next candidate, 3:2 — nothing inside {@link StemFusion#RELATION_TOLERANCE}), which makes
     *  the fusion a SLAM and its window {@link StemFusion#SLAM_STEPS} bars plus the splice — the
     *  6 212 ms of the phone's own render. */
    private static StemFusion.Plan slamOf(long removalMs, long firstVocalMs,
                                          StemFusion.OutgoingExit exit) {
        double bBeat = 510.986d;
        double aBeat = 600d;
        return StemFusion.plan(new StemFusion.Input(240_000L, 12_000L, removalMs, 362L,
                aBeat, 0d, bBeat, 362d, 1d, bars(aBeat * StemFusion.BEATS_PER_BAR, 0d, 200),
                bars(bBeat * StemFusion.BEATS_PER_BAR, 362d, 30),
                StemFusion.NO_VOICE_MEASUREMENT, StemFusion.NO_GROOVE_MEASUREMENT,
                StemFusion.NO_BODY_MEASUREMENT, firstVocalMs, StemFusion.NO_INCOMING_MEASUREMENT, 0,
                exit));
    }

    /**
     * ⚠️ <b>Round 33, from the measurement's own side: one row, three answers, decided by nothing
     * but how much of it the renderer separated — and the threshold is the percentile's, not a
     * margin.</b> The pair's head is 16 044 ms and its voice arrives at 16-18 s, so a render that
     * separates the head and hands <em>that</em> to {@link StemFusion#vocalStartMs} is measuring a
     * row that stops before the fact it is about: the row's own loud tenth is its bleed, the floor
     * lands 6 dB under the bleed, and the first window clears it — the answer is 0 and the deck
     * starts at the track's own beginning (the 「不像过渡」 the listener reported).
     *
     * <p>The two spans on either side of the threshold are pinned because the threshold is not
     * obvious and must not be trimmed back: {@link StemFusion#bodyLevelDb} answers the window at the
     * <em>90th percentile</em> of the material's windows, so the arrival has to sit inside the last
     * tenth of it — at 18 000 ms the body is still the 16-17 s bleed window and the answer is
     * <em>still 0</em>, while at {@link StemFusion#ARRIVAL_PROBE_MS} = 19 000 ms the body is the
     * voice's own second and the answer is the arrival. No threshold moves between the three: the
     * material does.
     */
    @Test
    public void theRowHasToReachTheArrivalForTheArrivalToBeRead() {
        // The measured table's own levels: the bleed from the track's start, the body from 17 s
        // (16-17 s is still the bleed, 17-18 s is −13.57 — the body's first second).
        float[][] row = vocalRow(26d, 17_000L, -25.4d, -13.6d);
        assertEquals("the render's own head (16 044 ms), which ends before the voice: the bleed is"
                + " the row's own body, the floor sits under it, and the FIRST window clears it —"
                + " the defect this round exists to fix (the app landing on 362, not skipping)",
                0L, StemFusion.vocalStartMs(headOf(row, 16_044L), RATE, 0L,
                        DjEdit.SILENT_FRAME_DBFS));
        assertEquals("and 18 000 ms — a second past the entry — is NOT enough either, which is why"
                + " the constant is 19 000: the loud tenth of an 18-window row is still the 16-17 s"
                + " bleed window, so the very first window clears the floor it sets",
                0L, StemFusion.vocalStartMs(headOf(row, 18_000L), RATE, 0L,
                        DjEdit.SILENT_FRAME_DBFS));
        long atProbe = StemFusion.vocalStartMs(headOf(row, StemFusion.ARRIVAL_PROBE_MS), RATE, 0L,
                DjEdit.SILENT_FRAME_DBFS);
        assertTrue("the material the probe separates answers the arrival — " + atProbe + "ms on"
                + " this fixture's own frame grid (the measured row's sliding windows answer"
                + " 16 475): no longer 0, and past the plan's own ceiling of 5 788ms, so the"
                + " landing it produces is the same line whatever the last millisecond is",
                atProbe >= 12_000L);
        assertEquals("the probe is not merely longer than the head: it is what the renderer"
                + " separates instead of it", 19_000L,
                StemFusion.arrivalProbeMs(16_044L, 240_000d));
    }

    /** The first {@code ms} of a row, as the renderer's own window reaches it: what a render that
     *  separated only that much would hand to {@link StemFusion#vocalStartMs}. */
    private static float[][] headOf(float[][] pcm, long ms) {
        int frames = (int) Math.round(ms * RATE / 1000d);
        float[][] out = new float[pcm.length][];
        for (int ch = 0; ch < pcm.length; ch++) {
            out[ch] = java.util.Arrays.copyOf(pcm[ch], Math.min(frames, pcm[ch].length));
        }
        return out;
    }

    /**
     * ⚠️ <b>Round 33's other half, and it is the cheap half: the probe extends a separation and
     * never shortens one.</b> What the renderer separates has to reach
     * {@link StemFusion#ARRIVAL_PROBE_MS} for {@link StemFusion#vocalStartMs} to be able to see a
     * late arrival at all — but only a render whose head is <em>shorter</em> than that pays for it.
     * A head already at or past the constant is returned unchanged, which is the property a render
     * that was already reaching the arrival depends on: the same window, the same model input, the
     * same file, to the byte.
     */
    @Test
    public void theProbeNeverShortensASeparationThatAlreadyReachesTheArrival() {
        long head = 16_044L;
        assertEquals("the measured pair's head is a floor, not the span", 19_000L,
                StemFusion.arrivalProbeMs(head, 240_000d));
        assertEquals("which is the whole of this round's cost on it, as a fraction of the head",
                1.184d, StemFusion.ARRIVAL_PROBE_MS / (double) head, 0.001d);
        assertEquals("a head at the constant is unchanged", StemFusion.ARRIVAL_PROBE_MS,
                StemFusion.arrivalProbeMs(StemFusion.ARRIVAL_PROBE_MS, 240_000d));
        assertEquals("and so is a head past it", 20_000L,
                StemFusion.arrivalProbeMs(20_000L, 240_000d));
        assertEquals("a short track is not asked for material it does not have", 10_000L,
                StemFusion.arrivalProbeMs(10_000L, 10_000d));
        assertEquals("and a head longer than the file is still the head: nothing that used to work"
                + " is refused or shortened", head, StemFusion.arrivalProbeMs(head, 7_000d));
        assertEquals("a duration the container does not give leaves the probe whole — the decode"
                + " stops at the end of the file either way", 19_000L,
                StemFusion.arrivalProbeMs(head, -1d));
    }

    /**
     * ⚠️ <b>The miss the plan is allowed to keep, and it is named.</b> The outgoing's own rows leave
     * the passage 2 000 ms in (a breakdown, a drop-out: the measured departure, and this is the case
     * the user's 「前一首歌到达声音很小的部分」 is about), while the incoming's voice does not start
     * until 15 000 ms of its file. The coupling's own landing (15 000 − 2 000 = 13 000) would run the
     * passage past the window the incoming's voice is held out for (17 000 − 8 000 = 9 000 is the
     * last landing it can pay for), so the landing is pulled back to that one — 8 000, the grid line
     * below it — and the voice arrives 5 000 ms after the outgoing is gone: the residual gap,
     * logged in ms and kept as a fusion rather than given up.
     */
    @Test
    public void aVoiceTheWindowCannotBringForwardLeavesAGapThePlanReports() {
        StemFusion.Plan gap = coupled(17_000L, 15_000L, exit(2_000L));
        assertTrue("the fusion stands: " + gap.reason, gap.valid);
        assertEquals("the measured departure is used, not the passage's end", 2_000L,
                gap.coupling.departureInFileMs);
        assertTrue("and it is reported as measured: " + gap.coupling.describe(gap.entryMs,
                gap.firstVocalMs, gap.removalMs, gap.windowMs), gap.coupling.measured);
        assertEquals("the landing is the last the window can afford", 8_000L, gap.entryMs);
        assertTrue("so the passage still fits the window", gap.fusionEndMs <= gap.removalMs);
        assertEquals(9_000L, gap.removalMs - gap.windowMs);
        assertEquals("the voice arrives 5 000 ms after the outgoing is gone", 5_000L,
                gap.coupling.residualGapMs);
        assertEquals("and nothing is held: the voice is late, not early", 0L, gap.coupling.heldMs);
        assertEquals("the gate is the voice's own arrival, not the window's end", 15_000L,
                gap.coupling.voiceGateMs);
        assertTrue(gap.describe(), gap.describe().contains("first HEARD 5000ms later than the"
                + " instant this round places the voice on"));
    }

    /**
     * ⚠️ Round 25's other direction, on a measured departure: an outgoing whose own passage goes
     * quiet <b>early</b> — a breakdown, a drop-out, the user's 「前一首歌到达声音很小的部分」, read off
     * its own rows rather than guessed from a shape — with an incoming voice that comes in late. The
     * departure is 6 000 ms into the passage, so the coupling's own landing is 12 000 − 6 000 = 6 000,
     * a bar line of this grid, and the voice lands on the departure exactly: no residual gap at all.
     *
     * <p>Two things are reported beside it, and both are the point: the landing IS a skip (6 000 ms
     * of intro are never played — 「跳过一些小节到人声部分」), and round 21's own landing (10 000, the
     * line a runway before the voice) is 4 000 ms later than it — at that landing the voice arrives
     * 2 000 ms into the ramp while the outgoing's material runs for another 4 000 ms, i.e. the
     * incoming singing over the fused passage, which is the defect the coupling closes.
     */
    @Test
    public void aMeasuredEarlyDepartureSkipsTheIntroAndTheVoiceLandsOnIt() {
        StemFusion.Plan plan = coupled(20_000L, 12_000L, exit(6_000L));
        assertTrue(plan.reason, plan.valid);
        assertEquals("the landing the departure asks for (12 000 − 6 000), itself a bar line",
                6_000L, plan.entryMs);
        assertEquals("6 000 ms of intro are never played", 6_000L, plan.skippedIntroMs);
        assertEquals(6_000L, plan.coupling.departureInFileMs);
        assertTrue("the departure is the measured one: " + plan.coupling.describe(plan.entryMs,
                plan.firstVocalMs, plan.removalMs, plan.windowMs), plan.coupling.measured);
        assertEquals("the voice's own arrival IS the departure: no gap", 0L,
                plan.coupling.residualGapMs);
        assertEquals("and nothing had to be held", 0L, plan.coupling.heldMs);
        assertEquals("the gate the render writes is that same instant", 12_000L,
                plan.coupling.voiceGateMs);
        assertEquals("round 21's own landing is reported beside it", 10_000L,
                plan.coupling.uncoupledEntryMs);
        assertTrue("which is 4 000 ms later than the coupling's: " + plan.describe(),
                plan.coupling.uncoupledEntryMs > plan.entryMs);
        assertTrue(plan.describe(), plan.describe().contains("which is the departure to the"
                + " millisecond"));
    }

    /**
     * ⚠️ <b>Round 30's whole ask, on a pair whose material fades.</b> The listener, after the
     * round-25 build (which put the incoming's first line at the outgoing's measured departure to the
     * millisecond): 「歌词要稍微早一点」. The departure is still the anchor — and it is now read at
     * {@link StemFusion#OUTGOING_EXIT_DB} = 12 dB rather than 20, so a passage that fades is read
     * earlier too — but the voice arrives a bar of the <em>outgoing's</em> grid earlier, and the lead
     * is clamped by the material's own {@link StemFusion#VOICE_GATE_FLOOR_DB} reading: the instant
     * the outgoing's rows are 6 dB under the passage's body, i.e. no longer clearly audible.
     *
     * <p>Two things this fixture pins, and they are the invariant: the voice IS earlier than the
     * measured exit ({@code voiceGateMs < departureInFileMs}), and it is never earlier than that
     * bound ({@code voiceGateMs == entryMs + floorMs} here).
     */
    @Test
    public void theLedVoiceArrivesBeforeTheMeasuredExitAndNoEarlierThanTheBound() {
        // The rows are 12 dB under the passage's body 6 000 ms in and were already 6 dB under it at
        // 4 000: a fading track, and the case the lead is for.
        StemFusion.Plan plan = coupled(20_000L, 0L, ramp(6_000L, 4_000L));
        assertTrue(plan.reason, plan.valid);
        assertEquals("the coupling's landing is before the track's own start, so round 33's floor"
                + " decides", 4_000L, plan.entryMs);
        assertEquals("the departure the material reads is 6 000 ms into this file", 6_000L,
                plan.coupling.departureInFileMs);
        assertEquals("one bar of the outgoing's grid (4 x 500 ms) before it", 4_000L,
                plan.coupling.ledFromMs);
        assertEquals("and the material's own 6 dB reading is the bound, at the same instant",
                4_000L, plan.coupling.floorMs);
        assertEquals("so the voice arrives there — 2 000 ms before the measured exit, and 4 000 ms"
                + " into this file", 4_000L, plan.coupling.voiceGateMs - plan.entryMs);
        assertTrue("earlier than the departure: " + (plan.coupling.voiceGateMs - plan.entryMs)
                        + " < " + plan.coupling.departureInFileMs,
                plan.coupling.voiceGateMs - plan.entryMs < plan.coupling.departureInFileMs);
        assertEquals("and never earlier than the bound", 0L,
                plan.coupling.voiceGateMs - plan.entryMs - plan.coupling.floorMs);
        String described = plan.coupling.describe(plan.entryMs, plan.firstVocalMs, plan.removalMs,
                plan.windowMs);
        assertTrue(described, described.contains("one bar earlier is 4000ms"));
        assertTrue(described, described.contains("the voice is placed 4000ms after the deck"
                + " starts"));
    }

    /**
     * ⚠️ <b>The case {@link StemFusion#OUTGOING_EXIT_DB} cannot reach: a passage whose own material
     * never falls.</b> The rows play right through the passage (measured, and the answer is the
     * gesture's own end), so there is no material instant for the voice to be led from — and the
     * listener's own pair is exactly this (a steady last section; the round-25 device run:
     * 「段落里从不到 20 dB 以下 ⇒ 答案＝手势自己的结尾」). The lead is then bounded by the
     * <em>file's</em> own gesture: where the recede has taken the outgoing's carried rows
     * {@link StemFusion#VOICE_GATE_FLOOR_DB} dB under unity, and never earlier than the deck-level
     * hand-over that removes the outgoing track's own (never carried) voice.
     *
     * <p>On this fixture the passage's own end is 8 080 ms of material in an 8 000 ms window, the
     * four-step gesture holds two steps and recedes over two, so 6 dB under unity is 2 + 2/3 steps =
     * 6 663 ms — 1 337 ms earlier than where round 25 put the voice, which is the whole of the
     * improvement on a pair like the user's.
     */
    @Test
    public void aLedVoiceOnAPassageThatNeverFallsIsBoundedByTheFilesOwnGesture() {
        StemFusion.Plan plan = coupled(20_000L, 0L, neverQuiet());
        assertTrue(plan.reason, plan.valid);
        assertEquals("the material never falls, so the answer is the gesture's own end (clamped to"
                + " the window)", 8_000L, plan.coupling.departureInFileMs);
        assertTrue("and it is reported as measured: " + plan.coupling.describe(plan.entryMs,
                plan.firstVocalMs, plan.removalMs, plan.windowMs), plan.coupling.measured);
        assertEquals("one bar of the outgoing's grid before the passage's end", 6_080L,
                plan.coupling.ledFromMs);
        assertEquals("the file's own gesture is the bound", 6_663L, plan.coupling.floorMs);
        assertEquals("two steps of hold plus two thirds of the low end's two-step recede, at 6 dB"
                + " under unity", 6_663L, StemFusion.voiceFloorMs(2, 2, 2_000L));
        assertEquals("so the voice arrives 1 337 ms earlier than the departure", 6_663L,
                plan.coupling.voiceGateMs - plan.entryMs);
        assertTrue("earlier than the departure: " + (plan.coupling.voiceGateMs - plan.entryMs)
                        + " < " + plan.coupling.departureInFileMs,
                plan.coupling.voiceGateMs - plan.entryMs < plan.coupling.departureInFileMs);
        assertTrue("and the bound is never inside the deck-level hand-over",
                plan.coupling.floorMs >= FadeCurve.JUNCTION_XFADE_MS);
        assertEquals(FadeCurve.JUNCTION_XFADE_MS, StemFusion.voiceFloorMs(1, 0, 500L));
        assertTrue(plan.describe(), plan.describe().contains("the voice is placed 6663ms after the"
                + " deck starts"));
    }

    /**
     * The measurement both of round 30's knobs are read off: <b>one rule, one instrument, two
     * readings</b> ({@link StemFusion#OUTGOING_EXIT_DB} where the voice arrives,
     * {@link StemFusion#VOICE_GATE_FLOOR_DB} what the lead may not reach back past). What it pins is
     * that the bound is a <em>reading</em> and not an assumption:
     *
     * <ul>
     *   <li>a fading passage is read earlier at 6 dB than at 12, which is what lets the lead act at
     *   all;</li>
     *   <li>a cliff reaches every level at the same instant, which is why the lead cannot move a
     *   pair whose outgoing material drops rather than fades (the cliff fixtures above still answer
     *   round 25's numbers, and this is the reason);</li>
     *   <li>a passage that never falls answers its own end at both levels, which is the case the
     *   file's own gesture bounds instead.</li>
     * </ul>
     */
    @Test
    public void theEnvelopesTwoLevelsAreOneRuleAndTheSofterOneComesFirst() {
        int span = 8_000;
        int frames = (int) Math.round(span / 1000d * RATE);
        StemFusion.OutgoingExit fading = StemFusion.exitOf(
                fadingRows(frames, 0, 6_000, 0.5, 0.004), RATE, 0L);
        long at12 = fading.goneAtMs(0L, span, StemFusion.OUTGOING_EXIT_DB);
        long at6 = fading.goneAtMs(0L, span, StemFusion.VOICE_GATE_FLOOR_DB);
        assertTrue("a fading passage is read at both levels: 12 dB at " + at12 + "ms, 6 dB at "
                + at6 + "ms", at6 > 0L && at12 > 0L);
        assertTrue("and 6 dB is reached first, so the bound precedes the arrival: " + at6 + " < "
                + at12, at6 < at12);
        assertEquals("the departure is the arrival's own level, read by the same rule", at12,
                fading.goneAtMs(0L, span));
        // A cliff: every level at the same instant.
        StemFusion.OutgoingExit cliff = StemFusion.exitOf(rows(frames, 3_000, 0.5, 0.005, 0.005),
                RATE, 0L);
        assertEquals("a cliff reaches the bound and the arrival at the same instant",
                cliff.goneAtMs(0L, span),
                cliff.goneAtMs(0L, span, StemFusion.VOICE_GATE_FLOOR_DB));
        // Never quiet at either level: the passage's own end, which is what the gesture bounds.
        StemFusion.OutgoingExit flat = StemFusion.exitOf(rows(frames, span, 0.5, 0.5, 0.5), RATE, 0L);
        assertEquals(span, flat.goneAtMs(0L, span, StemFusion.VOICE_GATE_FLOOR_DB));
        // And the shape's own answer when there is no separation at all: the passage's end, at every
        // level, because a material nobody separated cannot have an earlier one.
        assertEquals(4_000L, StemFusion.NO_EXIT_MEASUREMENT.goneAtMs(1_000L, 3_000L,
                StemFusion.VOICE_GATE_FLOOR_DB));
    }

    /** Carried rows that fade linearly in amplitude from {@code loud} at {@code fromMs} to
     *  {@code quiet} at {@code toMs} — a track fading away, which is the case round 30's lead is
     *  for. The voice row follows the same shape (the measurement never reads it: see
     *  {@link #theDepartureIsMeasuredOffTheOutgoingRowsOwnEnvelope}). */
    private static float[][][] fadingRows(int frames, int fromMs, int toMs, double loud,
                                          double quiet) {
        float[][][] stems = new float[4][2][frames];
        int from = (int) Math.round(fromMs / 1000d * RATE);
        int to = (int) Math.round(toMs / 1000d * RATE);
        for (StemGesture.Stem stem : StemGesture.Stem.ALL) {
            for (int ch = 0; ch < 2; ch++) {
                for (int i = 0; i < frames; i++) {
                    double level = quiet;
                    if (i < from) level = loud;
                    else if (i < to) {
                        level = loud + (quiet - loud) * (i - from) / (double) (to - from);
                    }
                    stems[stem.row()][ch][i] = (float) level;
                }
            }
        }
        return stems;
    }

    /**
     * ⚠️ <b>The measurement itself, which is the round's own subject.</b> The departure is read off
     * the outgoing's <em>separated carried rows</em>: drums, low end and the melodic row summed, its
     * own silence measured against its own body ({@link StemFusion#OUTGOING_EXIT_DB} under the loud
     * tenth of the passage's own windows), sustained for {@link StemFusion#OUTGOING_EXIT_SUSTAIN_MS}.
     * Three things the fixture pins:
     *
     * <ul>
     *   <li>the rows going quiet 3 000 ms into the passage answer 3 000 ms (within a frame);</li>
     *   <li>the outgoing's own <b>voice</b> may be as loud as it likes in the passage: it is not
     *   carried, so it is not what the listener hears leave — a measurement over the master would
     *   read the voice and never answer;</li>
     *   <li>rows that never go quiet answer the passage's own end (the gesture's own recede is then
     *   the departure), and a passage the measurement does not hold answers a negative instant,
     *   which is what makes the plan fall back rather than invent a number.</li>
     * </ul>
     */
    @Test
    public void theDepartureIsMeasuredOffTheOutgoingRowsOwnEnvelope() {
        int span = 8_000;
        int frames = (int) Math.round(span / 1000d * RATE);
        float[][][] quietAtThree = rows(frames, 3_000, 0.5, 0.005, 0.5);
        StemFusion.OutgoingExit exit = StemFusion.exitOf(quietAtThree, RATE, 0L);
        assertTrue("the passage's own rows are there to read", exit.measured());
        long gone = exit.goneAtMs(0L, span);
        assertTrue("the rows go quiet 3 000 ms in, read as " + gone + "ms",
                Math.abs(gone - 3_000L) <= 100L);
        // The voice is not part of it: the same passage with a voice at full scale over the whole of
        // it (the third row above is the melodic one, which IS carried, so this is a fresh fixture).
        float[][][] loudVoice = rows(frames, 3_000, 0.5, 0.005, 0.005);
        for (int ch = 0; ch < 2; ch++) {
            java.util.Arrays.fill(loudVoice[StemGesture.Stem.VOCALS.row()][ch], 0.5f);
        }
        assertEquals("the outgoing's own voice is not carried, so it cannot answer for the rows",
                gone, StemFusion.exitOf(loudVoice, RATE, 0L).goneAtMs(0L, span));
        // Rows that never go quiet: the gesture's own end is the departure.
        assertEquals("a passage the rows play right through answers its own end", span,
                StemFusion.exitOf(rows(frames, span, 0.5, 0.5, 0.5), RATE, 0L).goneAtMs(0L, span));
        // A passage the material does not hold: no answer at all.
        assertTrue("a span outside the material is not measured",
                StemFusion.exitOf(quietAtThree, RATE, 0L).goneAtMs(span, span) < 0L);
        // And the fallback names itself in the plan: no measurement at all is the shape's answer.
        StemFusion.OutgoingExit none = StemFusion.NO_EXIT_MEASUREMENT;
        assertFalse(none.measured());
        assertEquals("the shape's own end, in the outgoing's own file", 5_000L,
                none.goneAtMs(1_000L, 4_000L));
    }

    /** Four separated rows at {@code RATE}: the carried ones at {@code loud} until
     *  {@code quietAtMs} and then at {@code quiet}, and the voice row at {@code voice}. */
    private static float[][][] rows(int frames, int quietAtMs, double loud, double quiet,
                                    double voice) {
        float[][][] stems = new float[4][2][frames];
        int at = (int) Math.round(quietAtMs / 1000d * RATE);
        for (StemGesture.Stem stem : StemGesture.Stem.ALL) {
            double level = stem == StemGesture.Stem.VOCALS ? voice : loud;
            for (int ch = 0; ch < 2; ch++) {
                for (int i = 0; i < frames; i++) {
                    stems[stem.row()][ch][i] = (float) (i < at ? level : quiet);
                }
            }
        }
        return stems;
    }

    /** A plan's own evidence, for a message. */
    private static String plan(StemFusion.Plan p) {
        return p.describe();
    }

    /** A pair's relation verdict: {@code p:q} within tolerance, and the plan that comes of it. */
    private static void assertRelation(int p, int q, double aBeatMs, double bBeatMs, double speed,
                                       double expectedError, String a, String b) {
        StemFusion.Relation relation = StemFusion.relationOf(aBeatMs, bBeatMs, speed);
        assertNotNull(a + " -> " + b + " should be a relation", relation);
        assertEquals(p, relation.p);
        assertEquals(q, relation.q);
        assertEquals(a + " -> " + b, expectedError, relation.error, 0.001d);
        assertFalse("a relative pair more than LOCK_TOLERANCE out is not the unison case",
                relation.locked() && expectedError > StemFusion.LOCK_TOLERANCE);
        assertNull("the pre-decode clause accepts a relation too: "
                        + StemFusion.refusal(200_000L, 15_000L, 15_000L, 0L, aBeatMs, bBeatMs, speed),
                StemFusion.refusal(200_000L, 15_000L, 15_000L, 0L, aBeatMs, bBeatMs, speed));
    }

    /** A pair in no relation fuses as a SLAM: {@link StemFusion#SLAM_STEPS} steps of the table, the
     *  swaps on one line. */
    private static void assertSlam(double aBeatMs, double bBeatMs, String a, String b) {
        assertNull(a + " -> " + b + " should be in no relation",
                StemFusion.relationOf(aBeatMs, bBeatMs, 1d));
        StemFusion.Plan plan = StemFusion.plan(new StemFusion.Input(200_000L, 15_000L, 20_000L, 0L,
                aBeatMs, 0d, bBeatMs, 0d, 1d, bars(aBeatMs * 4, 0d, 200), bars(bBeatMs * 4, 0d, 400),
                StemFusion.NO_VOICE_MEASUREMENT));
        assertTrue(plan.reason, plan.valid);
        assertTrue("it is a SLAM", plan.slam);
        // ⚠️ The hold IS the slam's length (round 31 / RULE_VERSION 10: SLAM_STEPS bars, one bar
        // before), and it is a bar LINE of the incoming's own grid — an integer number of its own
        // bars — because that is where its grid is solid.
        long barStep = Math.round(bBeatMs * 4);
        assertEquals("the hold is SLAM_STEPS bars of the incoming's own grid",
                (long) StemFusion.SLAM_STEPS * barStep, plan.swapMs - plan.entryMs);
        // ⚠️ ... PLUS the splice: the elements change hands on the line at `swapMs` and the 80 ms
        // splice that de-clicks that hand-over runs to `swap + CUT`, so the file has to contain it
        // (round 6, tenth pass — without the CUT the guard sent the splice's own samples to the floor
        // and the rows fell unity → 0 in one sample). The window is read as the max of the span's two
        // writings, so it contains the splice exactly and may round a millisecond past it.
        assertTrue("the window contains the splice: fusionEnd >= swap + CUT",
                plan.fusionEndMs - plan.swapMs >= StemFusion.CUT_MS);
        assertTrue("and the two writings of that span agree to the millisecond",
                plan.fusionEndMs - plan.swapMs <= StemFusion.CUT_MS + 1L);
        assertEquals("the window is the passage plus its splice",
                Math.max(Math.round(StemFusion.SLAM_STEPS * (bBeatMs * 4) + StemFusion.CUT_MS),
                        (long) StemFusion.SLAM_STEPS * barStep + StemFusion.CUT_MS),
                plan.windowMs);
        assertEquals("every element changes hands on the same line", plan.swapMs, plan.bassMs);
        assertEquals("the incoming's arrival is that same splice", plan.swapMs,
                plan.arriveStartMs);
        assertEquals(plan.swapMs + StemFusion.CUT_MS, plan.arriveEndMs);
        assertEquals("and the carry's stretch is the deck's own ratio", 1d, plan.stretch, 1e-9);
    }

    /** A pair's lock verdict, named the way the log names it. */
    private static void assertLocked(boolean expected, double aBeatMs, double bBeatMs, double speed,
                                     double expectedError, String a, String b) {
        boolean locked = StemFusion.locked(aBeatMs, bBeatMs, speed);
        assertEquals(a + " -> " + b + ": lock error " + StemFusion.lockError(aBeatMs, bBeatMs, speed)
                + " against the " + StemFusion.LOCK_TOLERANCE + " tolerance",
                expected, locked);
        assertEquals(a + " -> " + b, expectedError, StemFusion.lockError(aBeatMs, bBeatMs, speed),
                Math.max(1e-4d, expectedError * 0.02d));
        String refusal = StemFusion.refusal(200_000L, 15_000L, 15_000L, 0L, aBeatMs, bBeatMs, speed);
        if (expected) {
            assertNull(a + " -> " + b + ": " + refusal, refusal);
        } else {
            assertNotNull(a + " -> " + b + " should be refused", refusal);
            assertTrue(a + " -> " + b + ": " + refusal, refusal.contains("do not lock"));
        }
    }

    // --- the table -----------------------------------------------------------

    private static StemFusion.Plan fixture() {
        StemFusion.Plan plan = StemFusion.plan(input(240_000L, 20_000L, 35_000L, 500d, 500d,
                bars(2000d, 0d, 120), bars(2000d, 0d, 120)));
        assertTrue(plan.reason, plan.valid);
        return plan;
    }

    private static double db(double ratio) {
        return 20 * Math.log10(ratio);
    }

    @Test
    public void theOutgoingRecedesAndTheIncomingRises() {
        // ⚠️ Round 6, from listening: A recedes, it does not cut (the user heard the 80 ms splices
        // as the outgoing track being stopped). A's rows hold at unity through the hold, then fade
        // out over their own spans — the drums over one step, the low end and the melodic row over
        // two — equal-power and monotone. B's drums and low end rise over one step from there.
        StemFusion.Plan plan = fixture();
        long entry = plan.entryMs;
        long step = Math.round(plan.barStepMs);
        long holdEnd = plan.holdEndMs;
        long drumsEnd = plan.drumsEndMs;
        long lowEndEnd = plan.lowEndEndMs;
        double unity = 1d;
        double aOther = Math.pow(10d, StemFusion.A_OTHER_DB / 20d);
        assertEquals("the hold is A_HOLD_STEPS steps", entry + StemFusion.A_HOLD_STEPS * step,
                holdEnd);
        assertEquals("the drums' fade is one step", step, drumsEnd - holdEnd);
        assertEquals("the low end's fade is two", 2 * step, lowEndEnd - holdEnd);
        assertEquals("and the gesture ends where the low end's fade does", lowEndEnd,
                plan.fusionEndMs);
        assertEquals("nothing is spliced in a fusion any more", 0, plan.splices());
        assertEquals(3, plan.recedeMs().length);

        // Unity through the hold: the file is continuous with the outgoing's live deck for the whole
        // of the deck-level handover, which is what keeps the two fades from stacking.
        for (StemGesture.Stem row : new StemGesture.Stem[]{StemGesture.Stem.DRUMS,
                StemGesture.Stem.BASS, StemGesture.Stem.OTHER}) {
            assertEquals(row + " at the entry", row == StemGesture.Stem.OTHER ? aOther : unity,
                    StemFusion.gainAt(plan, true, row, entry), 1e-9);
            assertEquals(row + " still at unity at the hold's end",
                    row == StemGesture.Stem.OTHER ? aOther : unity,
                    StemFusion.gainAt(plan, true, row, holdEnd), 1e-9);
        }
        // Each fade is an equal-power decline — a cosine to the floor — so half way through ITS OWN
        // span each side is at cos(pi/4) of its own level (the drums' span is one step, the low
        // end's and the melodic row's two), and the floor is reached on the step line, never before.
        assertEquals(unity * Math.cos(Math.PI / 4),
                StemFusion.gainAt(plan, true, StemGesture.Stem.DRUMS, holdEnd + step / 2), 1e-9);
        assertEquals(0d, StemFusion.gainAt(plan, true, StemGesture.Stem.DRUMS, drumsEnd), 1e-9);
        assertEquals(unity * Math.cos(Math.PI / 4),
                StemFusion.gainAt(plan, true, StemGesture.Stem.BASS, holdEnd + step), 1e-9);
        assertEquals(0d, StemFusion.gainAt(plan, true, StemGesture.Stem.BASS, lowEndEnd), 1e-9);
        assertEquals(aOther * Math.cos(Math.PI / 4),
                StemFusion.gainAt(plan, true, StemGesture.Stem.OTHER, holdEnd + step), 1e-9);
        // Monotone: no element comes back, ever.
        for (StemGesture.Stem row : new StemGesture.Stem[]{StemGesture.Stem.DRUMS,
                StemGesture.Stem.BASS, StemGesture.Stem.OTHER}) {
            double previous = Double.MAX_VALUE;
            for (long at = entry; at <= plan.fusionEndMs; at += 13L) {
                double g = StemFusion.gainAt(plan, true, row, at);
                assertTrue(row + " must never rise again (" + g + " at " + at + ")", g <= previous + 1e-9);
                previous = g;
            }
        }
        // B's arrival: silence at the hold's end, half way up at the middle of its step, unity from
        // the step after.
        long arriveStart = plan.arriveStartMs;
        long arriveEnd = plan.arriveEndMs;
        assertEquals("B's drums and low end start arriving one step before A's hold ends",
                holdEnd - step, arriveStart);
        assertEquals(unity, StemFusion.gainAt(plan, false, StemGesture.Stem.DRUMS, arriveEnd), 1e-9);
        assertEquals(Math.sin(Math.PI / 4), StemFusion.gainAt(plan, false, StemGesture.Stem.DRUMS,
                (arriveStart + arriveEnd) / 2), 1e-9);
        assertEquals(0d, StemFusion.gainAt(plan, false, StemGesture.Stem.DRUMS, arriveStart), 1e-9);

        // Outside the window: today's content (the incoming's own backing at unity) and nothing of
        // the outgoing's.
        assertEquals(unity, StemFusion.gainAt(plan, false, StemGesture.Stem.DRUMS, entry - 1L), 1e-9);
        assertEquals(0d, StemFusion.gainAt(plan, true, StemGesture.Stem.BASS, entry - 1L), 1e-9);
        assertEquals(unity, StemFusion.gainAt(plan, false, StemGesture.Stem.OTHER, plan.fusionEndMs), 1e-9);
        assertEquals(0d, StemFusion.gainAt(plan, true, StemGesture.Stem.DRUMS, plan.fusionEndMs), 1e-9);
    }

    /** How long a row takes to get within 6 dB of its own level (a rise) or of the floor (a fall),
     *  ms — the measurement the round-6 shapes are chosen by. */
    private static long reachMs(StemFusion.Plan plan, boolean fromOutgoing, StemGesture.Stem row,
                                long fromMs, double target, double tol) {
        for (long at = fromMs; at <= plan.fusionEndMs; at += 5L) {
            if (Math.abs(StemFusion.gainAt(plan, fromOutgoing, row, at) - target) <= tol) {
                return at - fromMs;
            }
        }
        return -1L;
    }

    @Test
    public void theRecedeAndArrivalTimesAreTheOnesTheDesignNames() {
        StemFusion.Plan plan = fixture();
        long step = Math.round(plan.barStepMs);
        // An equal-power decline is a cosine, so -6 dB (half amplitude) is reached TWO THIRDS of
        // the way through its span; an equal-power rise is a sine, so -6 dB comes after ONE THIRD.
        long drumsSix = reachMs(plan, true, StemGesture.Stem.DRUMS, plan.holdEndMs, 0d, 0.5d);
        long lowEndSix = reachMs(plan, true, StemGesture.Stem.BASS, plan.holdEndMs, 0d, 0.5d);
        long arriveSix = reachMs(plan, false, StemGesture.Stem.DRUMS, plan.arriveStartMs, 1d, 0.5d);
        println("A's drums reach -6 dB %dms into their 1-step fade, its low end %dms into its"
                        + " 2-step one; B's drums reach -6 dB %dms into their 1-step rise",
                drumsSix, lowEndSix, arriveSix);
        assertEquals(Math.round(2d / 3d * step), drumsSix, 15L);
        assertEquals("the low end's fade is twice as long, so its -6 dB is twice as far in",
                Math.round(4d / 3d * step), lowEndSix, 15L);
        assertEquals(Math.round(1d / 3d * step), arriveSix, 15L);
        // The coexistence the user asked to keep (rule 3), as the TABLE'S GAINS give it: the stretch
        // over which both backings' gains are within 6 dB of their own levels — B's drums on their
        // way up, A's on their way down — which is 1.33 steps, an identity of the spans. It is a
        // BOUND on what a listener hears, not that number: the prototype's instrument on the device's
        // own material reads 1750 ms in all with a 450 ms longest run (reproduced as 1750/530 by
        // fusion/coexist.py, the same instrument) because the rows' own material — the incoming's kit
        // arriving late, the bed carrying the passage — is what decides the audible part.
        long from = plan.arriveStartMs + arriveSix;
        long to = plan.holdEndMs + drumsSix;
        println("the table's own gain overlap (both backings within 6 dB of their own level): %dms"
                        + " (one step is %dms)", to - from, step);
        assertTrue("both sides are near unity for more than one step: " + (to - from),
                to - from > step);
    }

    /**
     * ⚠️ Round 18's other half of the junction: the incoming's bed arrives over {@link
     * StemFusion#BED_FADE_BARS} bar, equal-power, and it is the one ramp in the table. The user
     * asked for it (「新歌的进入请用淡入效果」) and it is what keeps the level continuous while the
     * outgoing's voice leaves: a bed arriving by cut on the same bar the voice departs is a step
     * where the ear is listening for continuity.
     */
    @Test
    public void theIncomingBedFadesInOverItsFirstBar() {
        StemFusion.Plan plan = fixture();
        long entry = plan.entryMs;
        long swap = plan.swapMs;
        long fade = plan.bedFadeMs - entry;
        assertEquals("one bar of the incoming's grid", Math.round(plan.bBarMs), fade);
        assertEquals(entry + fade, plan.bedFadeMs);
        // Silent at the entry, unity from the fade's end on, and equal-power in between: half way
        // up the bar each side of the hand-over is at sin(pi/4).
        assertEquals(0d, StemFusion.gainAt(plan, false, StemGesture.Stem.OTHER, entry), 1e-9);
        assertEquals(Math.sin(Math.PI / 4), StemFusion.gainAt(plan, false, StemGesture.Stem.OTHER,
                entry + fade / 2), 1e-9);
        assertEquals(1d, StemFusion.gainAt(plan, false, StemGesture.Stem.OTHER,
                entry + fade - 1), 1e-5);
        assertEquals(1d, StemFusion.gainAt(plan, false, StemGesture.Stem.OTHER, plan.bassMs), 1e-9);
        // Monotone over the whole fade, and it never overshoots the unity it arrives at.
        double previous = -1d;
        for (long at = entry; at <= entry + fade; at += 7L) {
            double g = StemFusion.gainAt(plan, false, StemGesture.Stem.OTHER, at);
            assertTrue("gain " + g + " at " + at, g >= previous - 1e-12 && g <= 1d + 1e-12);
            previous = g;
        }
        // ⚠️ Round 6: the incoming's drums and low end are not cut in either — they RISE, over one
        // step, starting one step BEFORE the hold ends (which is the same instant the bed starts
        // from), so both backings are near their own level together for more than a step. Zero
        // exactly at the rise's start, cos/sin-exact in the middle, unity from its end on.
        long arriveStart = plan.arriveStartMs;
        long arriveEnd = plan.arriveEndMs;
        assertEquals(entry + fade, arriveStart);
        assertEquals(arriveStart + Math.round(plan.barStepMs), arriveEnd);
        assertEquals(0d, StemFusion.gainAt(plan, false, StemGesture.Stem.DRUMS, arriveStart), 1e-9);
        assertEquals(Math.sin(Math.PI / 4), StemFusion.gainAt(plan, false, StemGesture.Stem.DRUMS,
                (arriveStart + arriveEnd) / 2L), 1e-9);
        assertEquals(1d, StemFusion.gainAt(plan, false, StemGesture.Stem.DRUMS, arriveEnd), 1e-9);
        assertEquals(1d, StemFusion.gainAt(plan, false, StemGesture.Stem.BASS, arriveEnd), 1e-9);
        assertEquals("A's low end is still at unity where B's drums top out", 1d,
                StemFusion.gainAt(plan, true, StemGesture.Stem.BASS, arriveEnd), 1e-9);
    }

    /**
     * ⚠️ Round 4's log fix, pinned: the line used to print {@code windowMs / sourceSpanMs} as "played
     * back at x…" — three bars of the incoming's grid over two bars plus a splice of the outgoing's,
     * a bar-count ratio that is above 1 on every pair. On the device's pair it printed
     * <b>x1.4649</b> for a deck that played the file at <b>x1.0000</b>. Both numbers are the deck's
     * ratio now, and this pins that they are.
     */
    @Test
    public void theDescribeReportsTheDecksRatioAndNotABarCountRatio() {
        // ⚠️ A 4% bar mismatch with the deck at x1.0 is no longer a plan at all — it is the
        // incoherence `plan` refuses (see theDecksRatioAndTheCarrysRatioMustAgree) — so the pair
        // that exercises the describe line is the COHERENT one: B's beat is 4% longer than A's and
        // the deck plays the file 4% faster, which is what returns the carry to the outgoing's own
        // tempo and pitch.
        StemFusion.Plan plan = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L,
                15_000L, 500d, 0d, 520d, 0d, 1.04d, bars(2000d, 0d, 120), bars(2080d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT));
        assertTrue(plan.reason, plan.valid);
        assertEquals("the deck's own ratio", 1.04d, plan.speed, 1e-9);
        assertEquals("and the carry's — the same number, which is the point of the clause",
                1.04d, plan.stretch, 1e-9);
        assertEquals(0.0385d, plan.relation.error, 0.001d);
        assertTrue(plan.describe(), plan.describe().contains("x1.0400"));
        assertFalse("the bar-count ratio must not be in the line: " + plan.describe(),
                plan.describe().contains("x1.83"));
        // At that ratio the take fills exactly the table's own span plus the tail it is cut on:
        // four steps of 2080 ms at x1.04, which is the window the deck plays.
        assertEquals(plan.windowMs + StemFusion.CUT_MS, plan.sourceSpanMs * plan.stretch, 2.0d);
    }

    /**
     * ⚠️ The coupling, pinned: the deck's ratio and the carry's ratio are ONE decision, and a pair
     * whose deck will not take the ratio the carry needs is not a fusion either way — at the bar
     * ratio the outgoing's material is played at a tempo that is not its own, at the deck's ratio
     * its bar boundaries slide against the incoming's beats. The device's own AGUDO -> Lose My Mind
     * is the case: its bar ratio is 0.9622 while a profile the deck may not stretch leaves the deck
     * at 1.0 (3.93% apart), and both numbers are in the refusal.
     */
    @Test
    public void theDecksRatioAndTheCarrysRatioMustAgree() {
        double aBeat = 566.4d;
        double bBeat = 545d;
        double barRatio = bBeat / aBeat;
        StemFusion.Plan incoherent = StemFusion.plan(new StemFusion.Input(96_816L, 15_000L, 15_240L,
                240L, aBeat, 0d, bBeat, 355d, 1d, bars(2_265.6d, 1_185.6d, 60),
                bars(2_180d, 900d, 9), StemFusion.NO_VOICE_MEASUREMENT));
        assertFalse(incoherent.describe(), incoherent.valid);
        assertTrue(incoherent.reason, incoherent.reason.contains("x1.0000"));
        assertTrue("the carry's own ratio is named too: " + incoherent.reason,
                incoherent.reason.contains(String.format(java.util.Locale.US, "x%.4f", barRatio)));
        assertTrue("and how far apart they are: " + incoherent.reason,
                incoherent.reason.contains(String.format(java.util.Locale.US, "%.2f%%",
                        100d * (1d / barRatio - 1d))));
        assertTrue("and what the answer is: " + incoherent.reason,
                incoherent.reason.contains("not a fusion"));
        // The same pair with the deck at the carry's ratio (the device's coherent render) plans, and
        // the carry is read at exactly the deck's ratio.
        StemFusion.Plan coherent = StemFusion.plan(new StemFusion.Input(96_816L, 15_000L, 15_240L,
                240L, aBeat, 0d, bBeat, 355d, barRatio, bars(2_265.6d, 1_185.6d, 60),
                bars(2_180d, 900d, 9), StemFusion.NO_VOICE_MEASUREMENT));
        assertTrue(coherent.describe(), coherent.valid);
        assertEquals(barRatio, coherent.stretch, 1e-9);
        assertEquals(barRatio, coherent.speed, 1e-9);
    }

    /**
     * The fallback a track with no low end needs (round 4): {@code 1410815174}, from the device run,
     * reads a loud tenth of −68.3 dBFS on its separated head's bass, so a downbeat estimate on it is
     * a coin flip; its bar lines come from the beat grid instead. The beats are then the measured
     * ones and the bar grouping is the guess.
     */
    @Test
    public void aTrackWithNoLowEndGetsItsBarLinesFromItsBeatGrid() {
        double[] grid = StemFusion.barLinesOfBeatGrid(416.734694d, 45d, 0d, 18_800d);
        assertEquals(12, grid.length);
        assertEquals(45d, grid[0], 1e-6);
        assertEquals(45d + 4 * 416.734694d, grid[1], 1e-6);
        // Whatever the phase, a two-bar window holds a line: that is what the entry search needs,
        // and what its refusal ("no bar line of the incoming track inside its own two-bar window")
        // must not be able to say when the track has a grid.
        for (double phase = 0d; phase < 1666.9d; phase += 137d) {
            double[] lines = StemFusion.barLinesOfBeatGrid(416.734694d, phase, 40d, 3334d);
            boolean inside = false;
            for (double at : lines) if (at >= 40d && at < 40d + 3334d) inside = true;
            assertTrue("a line inside [40, 3374) for a phase of " + phase, inside);
        }
        assertEquals(0, StemFusion.barLinesOfBeatGrid(0d, 0d, 0d, 1000d).length);
        assertEquals(0, StemFusion.barLinesOfBeatGrid(400d, 0d, 0d, 0d).length);
    }

    @Test
    public void theOutgoingVoiceIsNeverInTheTable() {
        StemFusion.Plan plan = fixture();
        for (long at = plan.entryMs; at <= plan.fusionEndMs; at += 100L) {
            assertEquals("the outgoing's vocal row is never carried",
                    0d, StemFusion.gainAt(plan, true, StemGesture.Stem.VOCALS, at), 1e-9);
        }
        // And the incoming's own voice is the edit's own gate, untouched by the fusion.
        DjEdit.Plan edit = DjEdit.plan(30d, 33d);
        for (int i = 0; i < 40; i++) {
            double t = i * 1d;
            assertEquals(edit.vocalGainAt(t),
                    StemFusion.incomingGains(plan, edit).gainOf(StemGesture.Stem.VOCALS, t), 1e-12);
        }
    }

    // --- the material --------------------------------------------------------

    /** A synthetic stem: a decaying burst at every beat, silence between them. */
    private static float[][] bursts(double beatSec, double windowSec, double hz, double amp) {
        int frames = (int) Math.round(windowSec * RATE);
        float[][] out = new float[2][frames];
        int period = (int) Math.round(beatSec * RATE);
        int burst = (int) Math.round(0.18d * RATE);
        for (int at = 0; at + burst < frames; at += period) {
            for (int i = 0; i < burst; i++) {
                double decay = Math.exp(-i / (0.06d * RATE));
                float v = (float) (amp * decay * Math.sin(2 * Math.PI * hz * i / RATE));
                out[0][at + i] += v;
                out[1][at + i] += v;
            }
        }
        return out;
    }

    /** A tone, for a pad or a voice. */
    private static float[][] tone(double windowSec, double hz, double amp) {
        int frames = (int) Math.round(windowSec * RATE);
        float[][] out = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            float v = (float) (amp * Math.sin(2 * Math.PI * hz * i / RATE));
            out[0][i] = v;
            out[1][i] = v;
        }
        return out;
    }

    /**
     * A synthetic vocal row of {@code seconds}: a steady level of {@code bleedDb} dBFS until
     * {@code bodyFromMs}, then a steady {@code bodyDb} — the two levels the measured track's own
     * table reads (a bleed the separation left behind, and the body the voice plays at), which is
     * what {@link StemFusion#vocalStartMs} has to tell apart.
     *
     * <p>The step is placed on the function's own {@link StemBridge#FRAME_SEC} frame grid, because
     * that is the grid the median is read on: a step between two frames would make the one mixed
     * frame itself the arrival, and the answer would then depend on where inside the frame the step
     * fell rather than on the arrival the test is about.
     */
    private static float[][] vocalRow(double seconds, long bodyFromMs, double bleedDb,
                                      double bodyDb) {
        int frames = (int) Math.round(seconds * RATE);
        int frameLen = (int) Math.round(RATE * StemBridge.FRAME_SEC);
        int bodyFrom = (int) Math.ceil(bodyFromMs * RATE / 1000d / frameLen) * frameLen;
        float[][] out = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            double level = i < bodyFrom ? bleedDb : bodyDb;
            float value = (float) (Math.pow(10d, level / 20d) * Math.sqrt(2d)
                    * Math.sin(2 * Math.PI * 220d * i / RATE));
            out[0][i] = value;
            out[1][i] = value;
        }
        return out;
    }

    /** The median of the first second's 25 ms frames, dBFS — the reading {@link
     *  StemFusion#vocalStartMs} judged its floor on before round 32, and the one that says why the
     *  absolute floor could not answer a row that carries a bleed. */
    private static double firstSecondMedianDb(float[][] vocals) {
        double[] levels = StemBridge.frameLevelsDb(vocals, RATE,
                vocals[0].length / (double) RATE);
        int frames = (int) Math.round(StemFusion.VOCAL_SUSTAIN_MS
                / (StemBridge.FRAME_SEC * 1000d));
        return StemBridge.median(java.util.Arrays.copyOfRange(levels, 0, frames));
    }

    private static float[][][] stems(float[][] drums, float[][] bass, float[][] other,
                                     float[][] vocals) {
        float[][][] out = new float[4][][];
        out[StemGesture.Stem.DRUMS.row()] = drums;
        out[StemGesture.Stem.BASS.row()] = bass;
        out[StemGesture.Stem.OTHER.row()] = other;
        out[StemGesture.Stem.VOCALS.row()] = vocals;
        return out;
    }

    private static int countBursts(float[] pcm, int frame, int from, int length) {
        int count = 0;
        boolean quiet = true;
        for (int i = 0; i + frame < length && from + i + frame < pcm.length; i += frame) {
            double peak = 0;
            for (int j = 0; j < frame; j++) peak = Math.max(peak, Math.abs(pcm[from + i + j]));
            if (peak > 0.25d && quiet) count++;
            quiet = peak <= 0.02d;
        }
        return count;
    }

    @Test
    public void thePassageIsStretchedByTheSpeedSoPlaybackReturnsItToTheOutgoingTempo() {
        // 0.5s beats of the outgoing track; the incoming deck plays the file 25% fast, so the
        // passage has to be 25% longer on the way in to be heard at the outgoing's own tempo.
        double beatSec = 0.5d;
        double speed = 1.25d;
        StemFusion.Plan plan = fixture();
        float[][][] a = stems(bursts(beatSec, 30d, 1800d, 0.6d), bursts(beatSec, 30d, 55d, 0.6d),
                tone(30d, 220d, 0.1d), tone(30d, 440d, 0.2d));
        // The separated window starts 1000 ms before the junction, as the plan says.
        float[][][] carried = StemFusion.carry(a, RATE, plan.materialFromMs, plan, speed, true);
        assertNotNull(carried[StemGesture.Stem.DRUMS.row()]);
        assertNotNull(carried[StemGesture.Stem.BASS.row()]);
        assertNotNull(carried[StemGesture.Stem.OTHER.row()]);
        assertNull("the outgoing's voice is never carried",
                carried[StemGesture.Stem.VOCALS.row()]);
        int frames = (int) Math.round(plan.windowMs * RATE / 1000d);
        assertEquals(frames, carried[StemGesture.Stem.DRUMS.row()][0].length);
        // In the file the 0.5 s beats are 0.625 s apart; heard at x1.25 they are 0.5 s apart again.
        // ⚠️ Counted over the window's unity stretch (the hold), not over the whole take: the take's
        // last steps are the fade, which takes the bursts below the counting threshold — that is the
        // round-6 gesture working, not a defect.
        int unityFrames = (int) Math.round((plan.holdEndMs - plan.entryMs) * RATE / 1000d);
        int inFile = countBursts(carried[StemGesture.Stem.DRUMS.row()][0],
                (int) Math.round(0.01d * RATE), 0, unityFrames);
        assertEquals((plan.holdEndMs - plan.entryMs) / (beatSec * 1000d * speed), inFile, 1.0d);

        // And with no tempo applied the carry is sample-exact, starting exactly at the junction:
        // the first sample of the passage is A's own sample at the junction.
        float[][][] flat = StemFusion.carry(a, RATE, plan.materialFromMs, plan, 1d, true);
        int takeAt = (int) Math.round((plan.sourceFromMs - plan.materialFromMs) * RATE / 1000d);
        for (int i = 0; i < 1000; i++) {
            assertEquals(a[StemGesture.Stem.BASS.row()][0][takeAt + i],
                    flat[StemGesture.Stem.BASS.row()][0][i], 1e-7f);
        }
    }

    @Test
    public void aMelodicCarryThatIsTheOutgoingVoiceIsCaught() {
        double windowSec = 30d;
        float[][] voice = tone(windowSec, 440d, 0.3d);
        float[][] melodyThatIsTheVoice = tone(windowSec, 440d, 0.29d);
        double same = StemFusion.voiceAlignment(melodyThatIsTheVoice, voice, RATE, 0L, 8_000L);
        assertTrue("a melodic row that is the voice aligns with it: " + same,
                same > StemFusion.VOICE_CARRY_LIMIT);
        // An unrelated pad does not: this is the measurement that decides dropping the melodic
        // carry rather than bailing out of the fusion altogether.
        double unrelated = StemFusion.voiceAlignment(tone(windowSec, 110d, 0.3d), voice, RATE,
                0L, 8_000L);
        assertTrue("an unrelated pad is not the voice: " + unrelated,
                unrelated < StemFusion.VOICE_CARRY_LIMIT);
    }

    // --- the acceptance ------------------------------------------------------

    /** One whole synthetic fusion: the outgoing's material carried into the window, the incoming's
     *  own rows under the table, rendered by {@link DjEdit#renderHead} exactly as the render does —
     *  including, when {@code limiter} is true, the head's own peak limiter.
     *
     *  <p>The outgoing's own master (the level the junction is judged against) is built the same way
     *  the renderer builds it: the sum of all four of A's rows over the {@link
     *  StemFusion#STEP_WINDOW_MS} before the junction, which is the last 500 ms of the
     *  {@link StemFusion#A_TAIL_SLACK_MS} of lead the material window carries. */
    private static StemFusion.Report run(StemFusion.Plan plan, double speed, boolean withMelody,
                                         float[][][] aStems, float[][][] bStems, double aBeatSec,
                                         double bBeatSec, boolean limiter) {
        return render(plan, speed, withMelody, aStems, bStems, aBeatSec, bBeatSec, limiter).report;
    }

    /** One rendered fusion: the report, the head that was rendered (the step's own subject) and
     *  the limiter that was in it — the three things a test about the junction needs together. */
    private static final class Ran {
        final StemFusion.Report report;
        final float[][] head;
        final DjEdit.Limiter limiter;

        Ran(StemFusion.Report report, float[][] head, DjEdit.Limiter limiter) {
            this.report = report;
            this.head = head;
            this.limiter = limiter;
        }
    }

    /**
     * Round 3's two passes, exactly as the renderer makes them: one render with no make-up, the
     * step it measures turned into a make-up gain on the outgoing's carried rows
     * ({@link StemFusion#makeupDb}), and the head rendered again with it.
     */
    private static Ran[] renderWithMakeup(StemFusion.Plan plan, double speed, boolean withMelody,
                                          float[][][] aStems, float[][][] bStems, double aBeatSec,
                                          double bBeatSec, boolean limiter) {
        Ran first = render(plan, speed, withMelody, aStems, bStems, aBeatSec, bBeatSec, limiter);
        double makeup = StemFusion.makeupDb(
                first.report.stepMeasured ? first.report.junctionStepDb : Double.NaN);
        if (!(makeup > 0d)) return new Ran[]{first, first};
        Ran lifted = render(plan, speed, withMelody, aStems, bStems, aBeatSec, bBeatSec, limiter,
                null, makeup);
        return new Ran[]{first, lifted};
    }

    private static Ran render(StemFusion.Plan plan, double speed, boolean withMelody,
                              float[][][] aStems, float[][][] bStems, double aBeatSec,
                              double bBeatSec, boolean limiter0) {
        return render(plan, speed, withMelody, aStems, bStems, aBeatSec, bBeatSec, limiter0, null,
                0d);
    }

    /**
     * The same render with the material it is MEASURED against given separately (the outgoing's
     * own stems as its file holds them, which is what a take that ended early has to be caught
     * against — see {@link #aCarryThatEndsBeforeItsOwnLastCutIsCaught}) and with the make-up gain
     * already applied to the carried rows.
     */
    private static Ran render(StemFusion.Plan plan, double speed, boolean withMelody,
                              float[][][] aStems, float[][][] bStems, double aBeatSec,
                              double bBeatSec, boolean limiter0, float[][][] reference,
                              double makeupDb) {
        float[][][] ref = reference != null ? reference : aStems;
        int frames = (int) Math.round(plan.windowMs * RATE / 1000d);
        float[][][] taken = StemFusion.carry(aStems, RATE, plan.materialFromMs, plan, speed,
                withMelody);
        // The material the file ADDS: the same rows with the table's own gains on them, and the
        // make-up gain the junction's own step asked for (A's rows only — see applyMakeup).
        float[][][] carried = StemFusion.applyMakeup(
                StemFusion.gate(taken, plan, withMelody, RATE), makeupDb);
        float[][][] incoming = new float[4][][];
        for (StemGesture.Stem stem : StemGesture.Stem.ALL) {
            incoming[stem.row()] = sliceFrames(bStems[stem.row()], 0, frames);
        }
        float[][][] carriedSource = new float[4][][];
        for (StemGesture.Stem stem : StemGesture.Stem.ALL) {
            carriedSource[stem.row()] = sliceFrames(ref[stem.row()],
                    (int) Math.round((plan.sourceFromMs - plan.materialFromMs) * RATE / 1000d),
                    (int) Math.round(plan.sourceSpanMs * RATE / 1000d));
        }
        DjEdit.Plan edit = DjEdit.plan(plan.fusionEndMs / 1000d, plan.fusionEndMs / 1000d + 2d);
        int startFrame = (int) Math.round(plan.entryMs * RATE / 1000d);
        // ⚠️ The guard watches the FUSION WINDOW of the head, not its beginning: the file's
        // timeline starts at 0 and the fusion starts at entryMs, so a guard at 0 would be counting
        // the clamps of today's content (the incoming's own backing) instead of the passage's.
        DjEdit.ClipGuard guard = new DjEdit.ClipGuard(startFrame, startFrame + frames);
        float[][][] padded = new float[4][][];
        for (StemGesture.Stem stem : StemGesture.Stem.ALL) {
            padded[stem.row()] = sliceFrames(bStems[stem.row()], 0,
                    frames + (int) Math.round(plan.entryMs * RATE / 1000d));
        }
        DjEdit.Limiter limiter = limiter0 ? new DjEdit.Limiter(RATE) : null;
        float[][] rendered = DjEdit.renderHead(padded, RATE, frames / (double) RATE, edit,
                new int[1], StemFusion.incomingGains(plan, edit), carried, startFrame, null, null,
                0, guard, limiter);
        float[][][] incomingVocals = new float[4][][];
        incomingVocals[StemGesture.Stem.VOCALS.row()] = sliceFrames(
                DjEdit.scaleVocals(bStems[StemGesture.Stem.VOCALS.row()], RATE, edit,
                        (plan.entryMs + plan.windowMs) / 1000d),
                0, frames);
        // The head as rendered, over the window (the step's other half), and the outgoing's own
        // master just before the junction.
        float[][] head = sliceFrames(rendered, startFrame, frames);
        int masterFrames = (int) Math.round(StemFusion.STEP_WINDOW_MS * RATE / 1000d);
        int masterFrom = (int) Math.round((plan.sourceFromMs - plan.materialFromMs
                - StemFusion.STEP_WINDOW_MS) * RATE / 1000d);
        float[][] master = new float[2][masterFrames];
        for (StemGesture.Stem stem : StemGesture.Stem.ALL) {
            float[][] row = sliceFrames(ref[stem.row()], masterFrom, masterFrames);
            for (int ch = 0; ch < master.length; ch++) {
                for (int i = 0; i < masterFrames; i++) master[ch][i] += row[ch][i];
            }
        }
        StemFusion.Material material = new StemFusion.Material(RATE, frames, speed, makeupDb,
                carried, carriedSource, incoming, incomingVocals,
                sliceFrames(ref[StemGesture.Stem.VOCALS.row()],
                        (int) Math.round((plan.sourceFromMs - plan.materialFromMs) * RATE / 1000d),
                        (int) Math.round(plan.sourceSpanMs * RATE / 1000d)),
                head, master);
        assertNotNull(rendered);
        return new Ran(StemFusion.measure(plan, edit, material, withMelody, aBeatSec, bBeatSec,
                guard), head, limiter);
    }

    private static void println(String format, Object... args) {
        System.out.println(String.format(java.util.Locale.US, format, args));
    }

    private static float[][] sliceFrames(float[][] pcm, int from, int frames) {
        float[][] out = new float[pcm.length][frames];
        for (int ch = 0; ch < pcm.length; ch++) {
            int copy = Math.max(0, Math.min(frames, pcm[ch].length - from));
            if (copy > 0) System.arraycopy(pcm[ch], from, out[ch], 0, copy);
        }
        return out;
    }

    private static float[][][] cleanOutgoing(double windowSec) {
        return stems(bursts(0.5d, windowSec, 1800d, 0.5d), bursts(0.5d, windowSec, 55d, 0.5d),
                tone(windowSec, 220d, 0.08d), tone(windowSec, 440d, 0.15d));
    }

    private static float[][][] cleanIncoming(double windowSec) {
        return stems(bursts(0.5d, windowSec, 1600d, 0.45d), bursts(0.5d, windowSec, 60d, 0.45d),
                tone(windowSec, 330d, 0.1d), tone(windowSec, 520d, 0.15d));
    }

    @Test
    public void aFusionOfTwoBackingsIsAcceptable() {
        StemFusion.Plan plan = fixture();
        StemFusion.Report report = run(plan, 1d, true, cleanOutgoing(30d), cleanIncoming(30d),
                0.5d, 0.5d, true);
        assertTrue(report.describe(), report.acceptable);
        // The levels are the table's: the carried rows at the level of the material they came
        // from, and the incoming's own drums at the floor before their swap and present after.
        assertEquals(report.sourceDrumsDb, report.carriedDrumsDb, 3d);
        assertEquals(report.sourceBassDb, report.carriedBassDb, 3d);
        assertEquals(StemFusion.A_OTHER_DB, report.carriedMelodyDb - report.sourceMelodyDb, 3d);
        // ...and the low end is measured where it matters, the last 500 ms before its own cut:
        // the clause a take that ended early would fail.
        assertEquals(report.sourceBassEndDb, report.carriedBassEndDb, 3d);
        assertTrue(report.describe(), report.incomingDrumsBeforeDb <= StemFusion.SILENT_DBFS);
        assertTrue(report.describe(), report.incomingDrumsAfterDb > StemFusion.SILENT_DBFS);
        assertTrue(report.describe(), report.incomingBassBeforeDb <= StemFusion.SILENT_DBFS);
        assertTrue(report.describe(), report.incomingBassAfterDb > StemFusion.SILENT_DBFS);
        // The user's own rule, measured: nothing sings inside the fusion window — not the
        // incoming's voice (the edit's gate holds it at zero there) and not the outgoing's (its
        // vocal row is never carried at all).
        assertTrue(report.describe(), report.incomingVocalDb <= StemFusion.SILENT_DBFS);
        // No voice came along, the pitch is untouched (speed 1), and the passage's own rhythm
        // rows carry a beat on every beat of both grids.
        assertTrue(report.describe(), report.voiceAlignmentDrums < StemFusion.VOICE_CARRY_LIMIT);
        assertTrue(report.describe(), report.voiceAlignmentBass < StemFusion.VOICE_CARRY_LIMIT);
        assertEquals(0d, report.pitchOffsetSemitones, 1e-9);
        assertTrue(report.describe(), report.beatsWithAttack >= report.beats - 1);
        assertTrue(report.describe(), report.clippedShare <= StemFusion.CLIP_SHARE_MAX);
        // The junction: the fusion's first 500 ms sits at the outgoing master's own level, which
        // is the clause the round's whole gesture is measured by.
        assertTrue(report.describe(), report.stepMeasured);
        assertTrue(report.describe(),
                Math.abs(report.junctionStepDb) <= StemFusion.JUNCTION_STEP_MAX_DB);
    }

    @Test
    public void aCarryThatIsSilentIsCaught() {
        // The outgoing's ending has no rhythm in it: the passage's own pulse stops, which is the
        // hazard the bridge's measurement exists for and the reason this one does too.
        StemFusion.Plan plan = fixture();
        float[][][] quiet = cleanOutgoing(30d);
        quiet[StemGesture.Stem.DRUMS.row()] = new float[2][quiet[0][0].length];
        quiet[StemGesture.Stem.BASS.row()] = new float[2][quiet[0][0].length];
        StemFusion.Report report = run(plan, 1d, true, quiet, cleanIncoming(30d), 0.5d, 0.5d,
                true);
        assertFalse(report.describe(), report.acceptable);
        assertTrue(report.failures, report.failures.contains("silent")
                || report.failures.contains("hole"));
    }

    @Test
    public void aCarryThatIsTheOutgoingVoiceIsCaught() {
        StemFusion.Plan plan = fixture();
        float[][][] voiceCarried = cleanOutgoing(30d);
        // What was carried is the outgoing's voice (a loud tone that IS the vocal stem).
        voiceCarried[StemGesture.Stem.DRUMS.row()] = tone(30d, 440d, 0.4d);
        voiceCarried[StemGesture.Stem.VOCALS.row()] = tone(30d, 440d, 0.4d);
        StemFusion.Report report = run(plan, 1d, true, voiceCarried, cleanIncoming(30d), 0.5d,
                0.5d, true);
        assertFalse(report.describe(), report.acceptable);
        assertTrue(report.failures, report.failures.contains("voice"));
    }

    /**
     * ⚠️ The clause the round-18 prototype's junction failed on real material, and the reason
     * {@link DjEdit.Limiter} exists: the outgoing's own master already peaks at about full scale,
     * so a bed under it is over — and a static divisor over the whole head (what the prototype
     * did, measured at −4.55 dB of the head, −6.80 dB of step) pulls the music down with the
     * peaks, which the listener heard as a 卡顿.
     *
     * <p>Synthetic full-scale outgoing tail + bed, measured three ways on the same material:
     * <ul>
     *   <li>limited (what the render does): the level the listener was already at, held, with the
     *       clamp left as a last resort — the step is inside the clause;</li>
     *   <li>clamped at the bus (no limiter at all): the peaks are cut off, so the step stays small
     *       too, but tens of thousands of samples are at full scale, which is the distortion the
     *       limiter is there to avoid;</li>
     *   <li>divided by the head's own peak (the prototype's static guard): the step the user
     *       heard, and worse than the limiter's by the divisor's full amount.</li>
     * </ul>
     */
    @Test
    public void theLimiterHoldsAFullScaleOutgoingTailUnderABed() {
        StemFusion.Plan plan = fixture();
        // A's own rows: a loud body (a sustained low tone) and a kick line that reach full scale
        // together — the master is normalised to 1.0 below, which is what "already at full scale"
        // means — over a pad and a line of voice that are quiet (the junction the spec prefers, a
        // voice between phrases).
        float[][][] a = normalise(stems(tone(30d, 60d, 0.75d), bursts(0.5d, 30d, 55d, 0.6d),
                tone(30d, 220d, 0.1d), tone(30d, 440d, 0.05d)), 1.0d);
        // B's bed, at the level the table arrives at (unity) and loud enough that the two backings
        // together are well over full scale — the case the round is about.
        float[][][] b = stems(bursts(0.5d, 30d, 1600d, 0.4d), bursts(0.5d, 30d, 60d, 0.4d),
                tone(30d, 330d, 0.6d), tone(30d, 520d, 0.05d));

        Ran limited = render(plan, 1d, true, a, b, 0.5d, 0.5d, true);
        Ran clamped = render(plan, 1d, true, a, b, 0.5d, 0.5d, false);
        println("%s", limited.report.describe());
        println("%s", clamped.report.describe());
        println("%s", limited.limiter.describe());
        assertTrue(limited.report.describe(), limited.report.stepMeasured);
        assertTrue("the limited step must be inside the clause: " + limited.report.junctionStepDb,
                Math.abs(limited.report.junctionStepDb) <= StemFusion.JUNCTION_STEP_MAX_DB);
        // The limiter is what keeps the passage off full scale: without it the same material
        // clamps a great many samples, with it only the attack's own 1 ms is left as a last resort.
        assertTrue("clamping must be the last resort: " + limited.report.clippedPairs
                        + " clipped pairs with the limiter, " + clamped.report.clippedPairs
                        + " without",
                clamped.report.clippedPairs > 10 * Math.max(1, limited.report.clippedPairs));
        assertTrue(clamped.report.describe(), clamped.report.clippedPairs > 1000);
        assertTrue(limited.report.describe(),
                limited.report.clippedShare <= StemFusion.CLIP_SHARE_MAX);
        assertTrue(limited.report.describe(),
                limited.report.longestClipRunMs <= StemFusion.CLIP_RUN_MAX_MS);
        assertTrue("the limiter held something: " + limited.limiter.describe(),
                limited.limiter.heldFrames > 0);
        // The counterfactual the design was chosen against: the first prototype's static guard —
        // the whole head divided by its own peak. The clamp has already cut that peak to 1.0 in
        // the render without a limiter, so what is visible is the reduction the limiter's own
        // deepest hold implies: the peak it was holding was AT LEAST that much over full scale,
        // and a divisor spends that amount on every frame of the window where the limiter takes it
        // only while the peak is there. (The prototype's own pair measured −4.55 dB of head,
        // −6.80 dB of step, which is the 卡顿 the clause exists to refuse.)
        double divisorCostAtLeast = 20d * Math.log10(1d / limited.limiter.deepestGain);
        println("a static divisor would cost at least %.2f dB on every frame; the limiter's deepest"
                        + " hold was %.2f dB on the frames that needed it",
                divisorCostAtLeast, limited.limiter.deepestReductionDb());
        assertTrue("the synthetic passage is well over full scale, so the divisor's cost is real: "
                        + divisorCostAtLeast + " dB", divisorCostAtLeast > 2d);
        assertTrue("a divisor's cost is the limiter's deepest hold or more: " + divisorCostAtLeast
                        + " against " + limited.limiter.deepestReductionDb(),
                divisorCostAtLeast >= limited.limiter.deepestReductionDb() - 1e-9);
        assertTrue("...and the limiter does not hold that on every frame: "
                        + limited.limiter.heldFrames + " of " + limited.limiter.frames,
                limited.limiter.heldFrames < limited.limiter.frames);
    }

    /**
     * ⚠️ Round 3's make-up gain, on the case that produced it: a passage where what the fusion
     * carries is quiet relative to the outgoing track's own last half second — because the outgoing
     * track's <em>voice</em> was the energy there, and the fusion removes the voice. The junction
     * must not be heard as a drop: the renderer measures the step and lifts the outgoing's carried
     * rows by it, bounded by {@link StemFusion#MAKEUP_MAX_DB}.
     *
     * <p>What is asserted is the shape of the mechanism, not just that the number improves: the
     * make-up is exactly the step that was measured (clamped), it touches the outgoing's rows and
     * nothing else — the stretch of the head where the outgoing's material is already gone is
     * bit-identical between the two renders — and the clause is re-checked on the result.
     */
    @Test
    public void aQuietCarryIsLiftedByTheStepItMeasured() {
        StemFusion.Plan plan = fixture();
        // A's own rows: a lead voice over a backing that is real but quieter — roughly a 2:1
        // power ratio between what the fusion must throw away (the voice, and most of the melodic
        // row) and what it can carry. That is the passage where the make-up is needed: the fusion
        // arrives a handful of dB under the level the listener was on, which is the 卡顿 the round
        // is about.
        float[][][] a = stems(bursts(0.5d, 30d, 1800d, 0.35d), bursts(0.5d, 30d, 55d, 0.35d),
                tone(30d, 220d, 0.1d), tone(30d, 440d, 0.2d));
        Ran[] passes = renderWithMakeup(plan, 1d, true, a, cleanIncoming(30d), 0.5d, 0.5d, true);
        Ran before = passes[0];
        Ran after = passes[1];
        println("no make-up: %s", before.report.describe());
        println("with make-up: %s", after.report.describe());
        assertTrue(before.report.describe(), before.report.stepMeasured);
        assertTrue("the un-lifted junction is a drop: " + before.report.junctionStepDb,
                before.report.junctionStepDb < -StemFusion.JUNCTION_STEP_MAX_DB);
        double applied = StemFusion.makeupDb(before.report.junctionStepDb);
        assertTrue("the make-up is the measured reduction: " + applied,
                applied > 0d && applied <= StemFusion.MAKEUP_MAX_DB);
        assertEquals("the make-up the render applied", applied, after.report.makeupDb, 1e-9);
        // The lifted junction is closer to the listener's own level — and inside the clause.
        assertTrue("the step after the make-up: " + after.report.junctionStepDb,
                after.report.junctionStepDb > before.report.junctionStepDb);
        assertTrue(after.report.describe(),
                Math.abs(after.report.junctionStepDb) <= StemFusion.JUNCTION_STEP_MAX_DB);
        assertTrue("lifted " + applied + " dB, so most of the loss is recovered: "
                        + before.report.junctionStepDb + " -> " + after.report.junctionStepDb,
                after.report.junctionStepDb >= before.report.junctionStepDb / 2d);
        // The outgoing's rows really are louder in the file, and the incoming's are untouched: the
        // carried drums, which are the loudest carried row while they are on, grew by the make-up.
        double growth = after.report.carriedDrumsDb - before.report.carriedDrumsDb;
        assertEquals("the carried rows were lifted by the make-up", applied, growth, 0.05d);
        // B's rows and everything after the outgoing's material: identical, sample for sample.
        // Measured with the limiter out of the chain, because a limiter carries state: the two
        // renders have different peak histories, so its gain is (correctly) a hair different in
        // the made-up one even where the material is the same. What is being asserted is the
        // material: the make-up scales the outgoing's carried rows and nothing else.
        Ran[] bare = renderWithMakeup(plan, 1d, true, a, cleanIncoming(30d), 0.5d, 0.5d, false);
        int last = after.head[0].length;
        int from = (int) Math.round((plan.lowEndEndMs - plan.entryMs) * RATE / 1000d);
        for (int ch = 0; ch < bare[1].head.length; ch++) {
            for (int i = from; i < last; i++) {
                assertEquals("the make-up must not touch B's rows (sample " + i + ")",
                        bare[0].head[ch][i], bare[1].head[ch][i], 0.0f);
            }
        }
        // And the shape of the make-up itself: a uniform gain on the rows it is given.
        float[][][] carried = new float[StemGesture.Stem.ALL.length][][];
        carried[StemGesture.Stem.DRUMS.row()] = tone(1.0, 100d, 0.5d);
        float[][][] liftedRows = StemFusion.applyMakeup(carried, applied);
        double factor = Math.pow(10d, applied / 20d);
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < carried[0][ch].length; i += 97) {
                assertEquals("applyMakeup is a uniform gain",
                        factor * carried[0][ch][i], liftedRows[0][ch][i], 1e-6f);
            }
        }
        assertNull("a row that was not carried stays absent",
                liftedRows[StemGesture.Stem.VOCALS.row()]);
    }

    /**
     * A passage that needs more than {@link StemFusion#MAKEUP_MAX_DB} is refused: the clamp is
     * there so a junction that is nothing like the outgoing track cannot be amplified into one,
     * and the acceptance clause is what says so.
     */
    @Test
    public void aPassageThatNeedsMoreThanTheMakeUpIsRefused() {
        StemFusion.Plan plan = fixture();
        // 15 dB down or worse: the clamp (8 dB) cannot reach the outgoing's level, and the step
        // clause is what stops the fusion being written as a drop.
        float[][][] a = stems(bursts(0.5d, 30d, 1800d, 0.04d), bursts(0.5d, 30d, 55d, 0.04d),
                tone(30d, 220d, 0.03d), tone(30d, 440d, 0.6d));
        Ran[] passes = renderWithMakeup(plan, 1d, true, a, cleanIncoming(30d), 0.5d, 0.5d, true);
        println("%s", passes[1].report.describe());
        assertEquals(StemFusion.MAKEUP_MAX_DB, passes[1].report.makeupDb, 1e-9);
        assertFalse(passes[1].report.describe(), passes[1].report.acceptable);
        assertTrue(passes[1].report.failures, passes[1].report.failures.contains("sits")
                && passes[1].report.failures.contains("the most is "
                        + StemFusion.JUNCTION_STEP_MAX_DB));
    }

    /** The same stems scaled so that the sum of their rows — the master — peaks at {@code peak}. */
    private static float[][][] normalise(float[][][] aStems, double peak) {
        double worst = 0d;
        int frames = Integer.MAX_VALUE;
        for (StemGesture.Stem stem : StemGesture.Stem.ALL) {
            frames = Math.min(frames, aStems[stem.row()][0].length);
        }
        frames = Math.min(frames, (int) Math.round(8d * RATE));
        for (int i = 0; i < frames; i++) {
            double sum = 0;
            for (StemGesture.Stem stem : StemGesture.Stem.ALL) sum += aStems[stem.row()][0][i];
            worst = Math.max(worst, Math.abs(sum));
        }
        double scale = worst > 0d ? peak / worst : 1d;
        float[][][] out = new float[aStems.length][][];
        for (int row = 0; row < aStems.length; row++) {
            if (aStems[row] == null) continue;
            out[row] = new float[aStems[row].length][];
            for (int ch = 0; ch < aStems[row].length; ch++) {
                out[row][ch] = new float[aStems[row][ch].length];
                for (int i = 0; i < aStems[row][ch].length; i++) {
                    out[row][ch][i] = (float) (scale * aStems[row][ch][i]);
                }
            }
        }
        return out;
    }

    /**
     * ⚠️ The material window's own clause: the take has to be there up to {@code bassMs}. A take
     * one bar short (round 18's {@code 3*aBar} on a pair whose bars are not equal) leaves the last
     * stretch of the carried low end silent while the table still has it on — a hard cut the
     * gesture never names — and the measurement has to say so rather than average it away.
     */
    @Test
    public void aCarryThatEndsBeforeItsOwnLastCutIsCaught() {
        // A slow outgoing track: a 1 s beat, a 4 s bar. The material the gesture needs is the
        // window (see the assertion below), so the report is measured against a source window of
        // that length — but what is carried is only part of it, as a short separation would give.
        // ⚠️ Round 5: a 1000 ms against a 500 ms beat is a 2:1 relation, so this is a plan now (the
        // point of the test is the material its carry needs, not the refusal it used to be).
        StemFusion.Plan plan = StemFusion.plan(new StemFusion.Input(240_000L, 20_000L, 35_000L,
                15_000L, 1000d, 0d, 500d, 0d, 1d, bars(4000d, 0d, 60), bars(2000d, 0d, 120),
                StemFusion.NO_VOICE_MEASUREMENT));
        assertTrue(plan.describe(), plan.valid);
        assertEquals(2, plan.relation.p);
        assertEquals(1, plan.relation.q);
        // The same pair, locked (both grids 1 s beats) is a real fusion: the bass must be in the
        // file right up to the 2 s bar line, and it is.
        StemFusion.Plan locked = StemFusion.plan(new StemFusion.Input(400_000L, 20_000L, 35_000L,
                15_000L, 1000d, 0d, 1000d, 0d, 1d, bars(4000d, 0d, 100), bars(4000d, 0d, 100),
                StemFusion.NO_VOICE_MEASUREMENT));
        assertTrue(locked.describe(), locked.valid);
        // ⚠️ Round 6's adaptive shape, and the reason it exists: a 4 s step cannot afford the full
        // gesture (a hold of 2 steps plus a 2-step low end fade is 16 s of A's file, over the 12 s
        // cap), so this pair gets ONE step of hold and a one-step low end fade. Still a recede —
        // the outgoing's rows fade out, none of them is cut — and the take it needs is 8080 ms.
        assertEquals("one step of hold", 4_000L, locked.holdEndMs - locked.entryMs);
        assertEquals("and the low end's own fade is one step too", 4_000L,
                locked.lowEndEndMs - locked.holdEndMs);
        assertEquals(8_080L, locked.sourceSpanMs);
        assertEquals(8_000L, locked.fusionEndMs - locked.entryMs);

        float[][][] a = cleanOutgoing(30d);
        Ran full = render(locked, 1d, true, a, cleanIncoming(30d), 1d, 1d, true);
        println("%s", full.report.describe());
        assertTrue(full.report.describe(), full.report.stepMeasured);
        assertTrue(full.report.describe(), full.report.acceptable);
        // Now the same render with the take cut short: the second half of A's material is gone, so
        // the outgoing's low end reaches the middle of the gesture and then fades SILENCE — the
        // round-18 defect in its round-6 costume, which is what the fade's own clause catches (a
        // clause on the level before the gesture takes over cannot see inside the fade, because
        // there the row is falling by the table's own design).
        float[][][] short_ = truncateRows(a, locked, 0.5d);
        Ran clipped = render(locked, 1d, true, short_, cleanIncoming(30d), 1d, 1d, true, a, 0d);
        println("%s", clipped.report.describe());
        assertFalse(clipped.report.describe(), clipped.report.acceptable);
        assertTrue(clipped.report.failures,
                clipped.report.failures.contains("fades a hole")
                        || clipped.report.failures.contains("low end is not there"));
        // The clause is measurable rather than vacuous: with the full take it is satisfied.
        assertTrue(full.report.describe(),
                full.report.carriedBassEndDb > full.report.sourceBassEndDb
                        - StemFusion.CARRY_TOLERANCE_DB);
        // And so is the fade's own clause: the full take's fade sits under its source by the
        // table's equal-power FADE_RMS_DB and no more.
        assertTrue(full.report.describe(),
                full.report.carriedBassFadeDb > full.report.sourceBassFadeDb
                        - StemFusion.FADE_RMS_DB - StemFusion.CARRY_FADE_TOLERANCE_DB);
    }

    /** The same stems with every row's material past {@code fraction} of the take's own span
     *  replaced by silence — a separation that stopped early. */
    private static float[][][] truncateRows(float[][][] aStems, StemFusion.Plan plan,
                                            double fraction) {
        float[][][] out = new float[aStems.length][][];
        for (int row = 0; row < aStems.length; row++) {
            if (aStems[row] == null) continue;
            out[row] = new float[aStems[row].length][];
            for (int ch = 0; ch < aStems[row].length; ch++) {
                out[row][ch] = aStems[row][ch].clone();
                int from = (int) Math.round((plan.sourceFromMs - plan.materialFromMs
                        + fraction * plan.sourceSpanMs) * RATE / 1000d);
                for (int i = Math.max(0, from); i < out[row][ch].length; i++) out[row][ch][i] = 0f;
            }
        }
        return out;
    }

    /**
     * A passage mixed too loud is refused — and the clause that catches it is the junction's step,
     * which is the honest answer now that the head carries a limiter: the limiter holds the peaks
     * (so the "no long clipping" clauses are the last resort, not the mechanism), and what is heard
     * instead is the level being pulled well below the one the listener was at. The clamp clauses
     * stay as the backstop they always were.
     */
    @Test
    public void aPassageMixedTooLoudIsRefused() {
        StemFusion.Plan plan = fixture();
        float[][][] loud = stems(bursts(0.5d, 30d, 1800d, 0.95d), bursts(0.5d, 30d, 55d, 0.95d),
                tone(30d, 220d, 0.5d), tone(30d, 440d, 0.1d));
        float[][][] loudIn = stems(bursts(0.5d, 30d, 1600d, 0.95d), bursts(0.5d, 30d, 60d, 0.95d),
                tone(30d, 330d, 0.5d), tone(30d, 520d, 0.1d));
        // No limiter in this one: the hand-over's own raw sum, which is over full scale.
        StemFusion.Report report = run(plan, 1d, true, loud, loudIn, 0.5d, 0.5d, false);
        println("%s", report.describe());
        assertFalse(report.describe(), report.acceptable);
        assertTrue(report.failures, report.failures.contains("sits")
                || report.failures.contains("clamp") || report.failures.contains("clipped"));
        assertTrue(report.clippedPairs > 0);
        // The step clause is what a listener would hear, so it is the one that HAS to be there.
        assertTrue(report.describe(), report.stepMeasured
                && Math.abs(report.junctionStepDb) > StemFusion.JUNCTION_STEP_MAX_DB);
    }

    @Test
    public void aFusionWithNoGridAnywhereCarriesNothing() {
        StemFusion.Plan plan = fixture();
        assertNull(StemFusion.carry(cleanOutgoing(30d), RATE, plan.materialFromMs,
                StemFusion.plan(input(240_000L, 0L, 35_000L, 500d, 500d, bars(2000d, 0d, 120),
                        bars(2000d, 0d, 120))), 1d, true)[0]);
    }

    @Test
    public void theOutgoingGridIsFoundFromTheLowEndOfItsOwnAudio() {
        // The downbeat estimate, on the mix's own low band: a kick on every beat at 120 BPM with
        // the bar's first one louder, on a beat grid whose phase is 125 ms into the file. The
        // beat phase alone can never say WHERE the bar starts (it is one of four answers and the
        // spec's own note says the wrong three are the ordinary case), which is why this is
        // measured from the low end.
        double beatSec = 0.5d;
        double windowSec = 12d;
        long fromMs = 200_000L;
        double beatPhaseMs = 125d;
        int frames = (int) Math.round(windowSec * RATE);
        int period = (int) Math.round(beatSec * RATE);
        int burst = (int) Math.round(0.15d * RATE);
        float[][] mix = new float[2][frames];
        int first = (int) Math.round((beatPhaseMs - fromMs) / 1000d * RATE);
        int beats = 0;
        for (int beat = 0; ; beat++) {
            int at = first + beat * period;
            if (at >= frames) break;
            if (at + burst <= 0) continue;
            beats++;
            double amp = beat % 4 == 0 ? 0.9d : 0.35d;
            for (int i = 0; i < burst; i++) {
                if (at + i < 0 || at + i >= frames) continue;
                double decay = Math.exp(-i / (0.05d * RATE));
                float v = (float) (amp * decay * Math.sin(2 * Math.PI * 80d * i / RATE));
                mix[0][at + i] += v;
                mix[1][at + i] += v;
            }
        }
        assertTrue(beats > 20);
        double[] grid = StemFusion.barLinesOfLowBand(mix, RATE, fromMs, beatSec * 1000d,
                beatPhaseMs);
        assertTrue("the low end gave a grid at all", grid.length > 1);
        // Every line is a bar line of the grid the kick plays: the first beat of every four, at
        // the beat phase, i.e. fromMs + 125 + k*2000.
        for (double at : grid) {
            double into = ((at - fromMs - beatPhaseMs) % 2000d + 2000d) % 2000d;
            assertTrue("a line off the outgoing grid: " + at, into < 25d || into > 1975d);
        }
        // And a grid with no low end at all gives nothing rather than a guess.
        assertEquals(0, StemFusion.barLinesOfLowBand(new float[2][frames], RATE, fromMs,
                beatSec * 1000d, beatPhaseMs).length);
    }
}
