package dev.t1m3.qplayer.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ⚠️ Round 6's second pass: the recede WAITS for the row that replaces it.
 *
 * <p>The device run of {@code AGUDO MAGKLCO -> Lose My Mind} is the whole reason this exists. Its
 * plan was right (the units defect is gone) and its only failing clause was the pulse: "10 of 15
 * beats carry an attack, longest gap 2832ms of a 566ms period". The arithmetic of that hole is the
 * incoming's own intro — {@code Lose My Mind}'s first ~3 s of head carry no drums at all (its
 * per-beat peaks run −8 −10 −20 −34 −38 −42 −52 −51 −37 −54 −58 −53 −57 −56 −54 −51 before the kit
 * returns), so once A's drums had faded there was no kit in either backing: the design's hold put
 * A's drums out over [5 260, 7 440) ms of the file, A's last carried attack is at 5 583, the
 * incoming's kit returns at 8 415, and 8 415 − 5 583 = 2 832 ms, the hole the acceptance measured.
 *
 * <p>So the hold is keyed off the incoming's material ({@link StemFusion#rowStartMs} on its own
 * separated head, which the renderer already has), and a pair whose incoming rows never arrive
 * inside the passage it can afford is refused <em>by that name</em> rather than accepted and
 * measured as a pulse hole.
 */
public class StemFusionIncomingWaitTest {

    private static final int RATE = 44_100;
    private static final double BEAT_MS = 545d;
    private static final long BAR_MS = 2_180L;

    /** The device pair's numbers: AGUDO (566.4 ms beats) as the outgoing, Lose My Mind (545 ms) as
     *  the incoming, a 15 s blend, the deck starting at 240 ms of the incoming's file. */
    private static StemFusion.Input input(StemFusion.IncomingOn incoming, long removalMs) {
        return input(incoming, 15_000L, removalMs);
    }

    /** The device pair's own numbers, with the blend a case needs: the junction's aim is
     *  {@code aDur - 250 - blendMs}, so a wider passage needs a blend that still puts a bar line of
     *  the outgoing's grid inside the search band the separation budget leaves. */
    private static StemFusion.Input input(StemFusion.IncomingOn incoming, long blendMs,
                                          long removalMs) {
        double[] aBars = new double[60];
        for (int i = 0; i < aBars.length; i++) aBars[i] = 1_185.6d + i * 2_265.6d;
        double[] bBars = new double[9];
        for (int i = 0; i < bBars.length; i++) bBars[i] = 900d + i * BAR_MS;
        return new StemFusion.Input(96_816L, blendMs, removalMs, 240L, 566.4d, 0d, BEAT_MS, 355d,
                BEAT_MS / 566.4d, aBars, bBars, StemFusion.NO_VOICE_MEASUREMENT,
                StemFusion.NO_GROOVE_MEASUREMENT, StemFusion.NO_BODY_MEASUREMENT, -1L, incoming);
    }

    /** The device's own measurement: the incoming's drums are not playing until 8 415 ms of its
     *  file, its low end from the first beat. */
    private static final StemFusion.IncomingOn DEVICE = (row, fromMs, toMs) ->
            row == StemGesture.Stem.DRUMS.row() ? 8_415L : 240L;

    @Test
    public void theHoldWaitsForTheIncomingRowsAndTheGestureKeepsItsShape() {
        StemFusion.Plan plain = StemFusion.plan(input(StemFusion.NO_INCOMING_MEASUREMENT, 15_240L));
        StemFusion.Plan waited = StemFusion.plan(input(DEVICE, 15_240L));
        assertTrue(plain.describe(), plain.valid);
        assertTrue(waited.describe(), waited.valid);
        // Without the measurement the design's own hold is untouched, bit for bit: that is what
        // every caller that does not measure still gets.
        assertFalse(plain.holdForIncoming);
        assertEquals(2L * BAR_MS, plain.holdEndMs - plain.entryMs);
        // With it, the hold grows to the step the incoming's own kit arrives in: 900 + 4*2180 =
        // 9 620 ms ≥ 8 415, and the shape after it is the same recede.
        assertTrue(waited.describe(), waited.holdForIncoming);
        assertEquals(8_415L, waited.incomingDrumsMs);
        assertEquals(240L, waited.incomingBassMs);
        assertEquals("the hold is four steps", 4L * BAR_MS, waited.holdEndMs - waited.entryMs);
        assertTrue("and it covers the incoming's kit: " + waited.describe(),
                waited.holdEndMs >= waited.incomingDrumsMs);
        assertEquals("the drums' fade is still one step", BAR_MS,
                waited.drumsEndMs - waited.holdEndMs);
        assertEquals("the low end's is still two", 2L * BAR_MS,
                waited.lowEndEndMs - waited.holdEndMs);
        assertEquals("and the rise is one step, ending where the recede starts", BAR_MS,
                waited.arriveEndMs - waited.arriveStartMs);
        assertEquals(waited.holdEndMs, waited.arriveEndMs);
        assertEquals("the window is the whole gesture", 6L * BAR_MS, waited.windowMs);
        assertEquals(waited.lowEndEndMs, waited.fusionEndMs);
        assertTrue("and the passage still fits the incoming's vocal-free window",
                waited.fusionEndMs <= 15_240L);
        assertTrue("the plan says what it waited for: " + waited.describe(),
                waited.describe().contains("the hold is 8720ms because the incoming's own drums"
                        + " are not playing until 8415ms"));
        // The bands' recede is still monotone and still reaches the floor on its own step.
        for (StemGesture.Stem row : new StemGesture.Stem[]{StemGesture.Stem.DRUMS,
                StemGesture.Stem.BASS, StemGesture.Stem.OTHER}) {
            double previous = Double.MAX_VALUE;
            for (long at = waited.entryMs; at <= waited.fusionEndMs; at += 17L) {
                double g = StemFusion.gainAt(waited, true, row, at);
                assertTrue(row + " must never rise again", g <= previous + 1e-9);
                previous = g;
            }
            assertEquals(row + " is at its own level through the hold",
                    row == StemGesture.Stem.OTHER ? Math.pow(10d, StemFusion.A_OTHER_DB / 20d) : 1d,
                    StemFusion.gainAt(waited, true, row, waited.holdEndMs), 1e-9);
        }
    }

    @Test
    public void theWaitItselfIsPlacedOnTheStepTheIncomingArrivesIn() {
        // A kit that arrives one beat later than the design's hold still costs a whole step: the
        // instants are the table's own lines, so the wait is rounded up, never down.
        StemFusion.Plan plan = StemFusion.plan(input((row, fromMs, toMs) ->
                row == StemGesture.Stem.DRUMS.row() ? 4_700L : 240L, 15_240L));
        assertTrue(plan.describe(), plan.valid);
        assertEquals("4 700ms is inside the second step, so the hold is three", 3L * BAR_MS,
                plan.holdEndMs - plan.entryMs);
        StemFusion.Plan same = StemFusion.plan(input((row, fromMs, toMs) ->
                row == StemGesture.Stem.DRUMS.row() ? 4_360L : 240L, 15_240L));
        assertEquals("a kit already there at the hold's own end costs nothing", 2L * BAR_MS,
                same.holdEndMs - same.entryMs);
        assertFalse(same.holdForIncoming);
    }

    /**
     * ⚠️ The other half of the coordinator's rule: when the incoming's rows never arrive inside the
     * passage the pair can afford, the fusion is refused with <em>that</em> reason — not accepted
     * and measured as a pulse hole. The passage is bounded by the incoming's own vocal-free window
     * and by the separation cap (the take has to exist in the outgoing's file), so a kit that turns
     * up later than both is a pair no recede can hand the pulse to.
     */
    @Test
    public void anIncomingRowThatNeverArrivesInThePassageIsRefusedByName() {
        // A row that is not playing anywhere the passage can reach: the first clause, and it says
        // which row and how long the passage is.
        StemFusion.Plan never = StemFusion.plan(input((row, fromMs, toMs) ->
                row == StemGesture.Stem.DRUMS.row() ? -1L : 240L, 15_240L));
        assertFalse(never.describe(), never.valid);
        assertTrue(never.reason, never.reason.contains("the incoming's own drums do not start"
                + " playing inside the"));
        assertTrue("and it says what the passage could afford: " + never.reason,
                never.reason.contains("steps of 2180.0ms"));
        assertTrue("and the reason is named, not left to the acceptance: " + never.reason,
                never.reason.contains("hole in the passage's pulse"));
        // A row that arrives after the passage has already run out — the second clause, with the
        // measured instant in it.
        StemFusion.Plan tooLate = StemFusion.plan(input((row, fromMs, toMs) ->
                row == StemGesture.Stem.DRUMS.row() ? toMs + 1L : 240L, 15_240L));
        assertFalse(tooLate.describe(), tooLate.valid);
        assertTrue(tooLate.reason, tooLate.reason.contains("only start playing at"));
        assertTrue("and the wait is what does not fit: " + tooLate.reason,
                tooLate.reason.contains("the wait does not fit"));
        // And the affordable passage is what decides: with a removal window long enough for the
        // seventh step, the same late incoming arrives and the plan is made — the wait is a
        // preference the window can pay for, not a refusal by reflex.
        StemFusion.Plan fits = StemFusion.plan(input((row, fromMs, toMs) ->
                row == StemGesture.Stem.DRUMS.row() ? 240L + 5L * BAR_MS : 240L, 16_271L,
                20_000L));
        assertTrue(fits.describe(), fits.valid);
        assertEquals(5L * BAR_MS, fits.holdEndMs - fits.entryMs);
        assertTrue(fits.holdForIncoming);
    }

    /**
     * {@link StemFusion#rowStartMs} itself, on the incoming's own shape: the device's track is a kit
     * that hits twice, leaves for seven seconds, and returns — and only the return is "playing".
     */
    @Test
    public void theRowStartMeasurementNeedsARowThatStays() {
        double sec = 14d;
        float[][] twoHitsThenKit = drumsWithIntro(sec);
        long start = StemFusion.rowStartMs(twoHitsThenKit, RATE, 240L, 14_000L, BEAT_MS);
        assertTrue("the kit's return is at 8 415ms, not the intro's two hits: " + start,
                Math.abs(start - 8_415L) <= BEAT_MS * 2L);
        // A row that plays from the first beat answers straight away.
        float[][] always = kicks(sec, 240L, 14_000L, 0.5d);
        assertEquals("a row playing from the start waits for nothing",
                Math.abs(StemFusion.rowStartMs(always, RATE, 240L, 14_000L, BEAT_MS) - 240L)
                        <= BEAT_MS * 2L, true);
        // A row with nothing in it, and a row whose whole window is under the floor, are both -1.
        assertEquals(-1L, StemFusion.rowStartMs(new float[2][(int) (sec * RATE)], RATE, 0L,
                (long) (sec * 1000d), BEAT_MS));
        float[][] whisper = kicks(sec, 240L, 14_000L, 1e-4d);
        assertEquals(-1L, StemFusion.rowStartMs(whisper, RATE, 240L, (long) (sec * 1000d),
                BEAT_MS));
        assertEquals("no beat grid, no answer", -1L,
                StemFusion.rowStartMs(always, RATE, 240L, 14_000L, 0d));
    }

    /**
     * ⚠️ The other rule the passage's own span fights with, on the device's own numbers:
     * {@code squabble up -> AGUDO} is refused in the plan with
     * "no line of the outgoing's grid is inside the 12000ms the passage can be taken from (the
     * search covers 429ms back and 428ms forward of 142742 outside it)". The pair: a 2 305 ms bar
     * (576.3 ms beats) against AGUDO's 566.4 ms (1.75% out of it, inside LOCK_TOLERANCE, so the
     * unison cap of 12 000 applies), a 15 s blend, a 157 992 ms outgoing — i.e. the target is
     * 142 742 ms, the design's own 4-step gesture needs 9 142 ms of the outgoing's file, and what
     * is left of the cap is a ±429 ms band while the outgoing's bar lines are 2 305 ms apart. No
     * line, and a pair nobody's rule has anything against: the passage and the search's OWN band
     * come out of the same cap, so the gesture gives — the low end's fade first, still a fade.
     */
    @Test
    public void theGestureGivesWhenTheSearchBandIsWhatThePassageSqueezed() {
        StemFusion.Plan plan = StemFusion.plan(squabbleInput());
        assertTrue(plan.describe(), plan.valid);
        assertEquals("the low end's fade is one step, not two", Math.round(plan.barStepMs),
                plan.lowEndEndMs - plan.holdEndMs);
        assertEquals("and the hold is the design's own two", 2L * Math.round(plan.barStepMs),
                plan.holdEndMs - plan.entryMs);
        assertTrue("the log says what the gesture gave up: " + plan.describe(),
                plan.describe().contains("its low end's fade is ONE step"));
        // The junction is a line of the outgoing's own grid inside the band the shortened gesture
        // leaves, and the passage is still three steps of the incoming's.
        assertEquals("the passage is three steps of the incoming's grid", 3d,
                plan.windowMs / plan.barStepMs, 0.01d);
        // A pair whose bar is wider than every affordable band is still refused — by the search's
        // own message, which now names the shortened gesture.
        StemFusion.Plan tooWide = StemFusion.plan(wideBarInput());
        assertFalse(tooWide.describe(), tooWide.valid);
        assertTrue(tooWide.reason, tooWide.reason.contains("no line of the outgoing's grid is"
                + " inside"));
        assertTrue("and it says the gesture was already at its shortest: " + tooWide.reason,
                tooWide.reason.contains("already shortened to its shortest"));
    }

    /** The device's own failing pair, in milliseconds: a 2 305.2 ms bar against AGUDO's 566.4 ms
     *  (locked, so the unison cap of 12 000 applies), a 15 s blend on a 157 992 ms outgoing. */
    private static StemFusion.Input squabbleInput() {
        double[] aBars = new double[80];
        for (int i = 0; i < aBars.length; i++) aBars[i] = 1_185.6d + i * 2_305.2d;
        double[] bBars = new double[12];
        for (int i = 0; i < bBars.length; i++) bBars[i] = 900d + i * 2_265.6d;
        return new StemFusion.Input(157_992L, 15_000L, 15_240L, 240L, 576.3d, 0d, 566.4d, 355d, 1d,
                aBars, bBars, StemFusion.NO_VOICE_MEASUREMENT,
                StemFusion.NO_GROOVE_MEASUREMENT, StemFusion.NO_BODY_MEASUREMENT, -1L);
    }

    /** A pair whose outgoing bar (4 531 ms) is wider than any band a 12 s cap can leave: even the
     *  shortest gesture cannot put a line of its grid in the search. */
    private static StemFusion.Input wideBarInput() {
        double[] aBars = new double[40];
        for (int i = 0; i < aBars.length; i++) aBars[i] = 1_185.6d + i * 4_531.2d;
        double[] bBars = new double[12];
        for (int i = 0; i < bBars.length; i++) bBars[i] = 900d + i * 4_531.2d;
        return new StemFusion.Input(120_000L, 15_000L, 16_000L, 240L, 1_132.8d, 0d, 1_132.8d, 355d,
                1d, aBars, bBars, StemFusion.NO_VOICE_MEASUREMENT,
                StemFusion.NO_GROOVE_MEASUREMENT, StemFusion.NO_BODY_MEASUREMENT, -1L);
    }

    /**
     * ⚠️ <b>The measurement's own limit, pinned so nobody re-claims a fix it cannot deliver.</b>
     * The device's pair is refused on a pulse hole of 2 832 ms, and the rule above was expected to
     * close it by waiting for Lose My Mind's kit to return. It cannot: that row's own separated
     * beats, measured ({@code -8 -10 -20 -34 -38 -42 -51 -51 -37 -54 -53 -53 -57 -54 -51 -49 -5
     * -10 -18 -30} dBFS), span <b>52 dB</b> and it opens on two loud hits, so its loud tenth is the
     * intro's level in any window short of the kit's return and the rule answers "playing from the
     * first beat" at every head length from 15 s to 45 s. No margin separates the opening hit
     * (−8) from the kit's ordinary beats (−18, −30, −55). So the wait does not engage on this stem,
     * the pair's fate is the acceptance's pulse clause (which measures the produced passage and is
     * reliable there), and {@link StemFusion#rowHitShare} is what says so in the log.
     */
    @Test
    public void aStemTooDynamicToReadSaysSoInsteadOfAnswering() {
        // The measured series, rebuilt as a row: an opening hit pair, seven seconds of nothing, then
        // the kit as the harness measured it.
        float[][] measured = fromPeaks(20d, new double[]{-8, -10, -20, -34, -38, -42, -51, -51, -37,
                -54, -53, -53, -57, -54, -51, -49, -5, -10, -18, -30});
        long start = StemFusion.rowStartMs(measured, RATE, 240L, 15_000L, BEAT_MS);
        double share = StemFusion.rowHitShare(measured, RATE, 240L, 15_000L, BEAT_MS);
        System.out.println("the measured Lose My Mind row: the rule says " + start + "ms, its hit"
                + " share is " + String.format(java.util.Locale.US, "%.2f", share));
        assertEquals("the rule reads the opening hits, not the kit (the kit is at ~8700ms)",
                0L, start);
        assertTrue("and the share is what says the reading is not evidence (" + share + ")",
                share < StemFusion.INCOMING_TRUST_SHARE);
        // A row a listener would call playing answers 240 ms with a share of ~1, which is the
        // contrast that makes the diagnostic worth printing.
        float[][] clean = kicks(14d, 240L, 15_000L, 0.5d);
        // The answer is a BEAT's own instant, so a hit inside the very first beat answers 0: the
        // plan rounds it up to a whole step of the table anyway.
        assertEquals(0L, StemFusion.rowStartMs(clean, RATE, 240L, 15_000L, BEAT_MS));
        assertTrue("a kit on every beat is all hits",
                StemFusion.rowHitShare(clean, RATE, 240L, 15_000L, BEAT_MS) > 0.9d);
    }

    /** A row whose beats carry the given peaks in dBFS (one per beat, from 0), for pinning a
     *  measured series without the audio. */
    private static float[][] fromPeaks(double seconds, double[] peakDb) {
        float[][] out = new float[2][(int) (seconds * RATE)];
        for (int k = 0; k < peakDb.length; k++) {
            int from = (int) Math.round(k * BEAT_MS * RATE / 1000d);
            double amp = Math.pow(10d, peakDb[k] / 20d);
            for (int i = from; i < Math.min(out[0].length, from + RATE / 8); i++) {
                double envelope = Math.exp(-(i - from) / (RATE * 0.05d));
                double value = amp * envelope * Math.sin(2d * Math.PI * 60d * (i - from) / RATE);
                out[0][i] = (float) value;
                out[1][i] = (float) value;
            }
        }
        return out;
    }

    /**
     * ⚠️ <b>The loop's lever: {@code Input.extraHoldSteps}.</b> The measurement cannot read every
     * stem (see the test above), so the renderer does not ask it to: it extends the hold one step at
     * a time and lets the acceptance judge each attempt (the loop is in the renderer; the planner's
     * half is this knob). The steps are counted like the design's own, so every clause applies to
     * them — which is what makes the worst case (no extension is kept) today's behaviour.
     */
    @Test
    public void theCallerCanExtendTheHoldOneStepAtATime() {
        StemFusion.Plan base = StemFusion.plan(input(StemFusion.NO_INCOMING_MEASUREMENT, 15_240L));
        assertTrue(base.describe(), base.valid);
        long step = Math.round(base.barStepMs);
        assertEquals("the design's own hold", 2L * step, base.holdEndMs - base.entryMs);
        for (int extra = 1; extra <= StemFusion.FUSION_WAIT_EXTRA_STEPS; extra++) {
            StemFusion.Plan more = StemFusion.plan(withExtra(extra, 15_240L));
            assertTrue(more.describe(), more.valid);
            assertEquals("the caller's step is one more step of the same recede",
                    (2L + extra) * step, more.holdEndMs - more.entryMs);
            assertEquals("and the shape after it is unchanged", step,
                    more.drumsEndMs - more.holdEndMs);
            assertEquals("the low end's two as well", 2L * step, more.lowEndEndMs - more.holdEndMs);
            assertEquals("the passage grows with it", (4L + extra) * step, more.fusionEndMs
                    - more.entryMs);
        }
        // A step the incoming's own vocal-free window cannot pay for is refused by the same clause
        // the design's steps are — the loop stops extending there rather than breaking a rule.
        StemFusion.Plan tooFar = StemFusion.plan(withExtra(StemFusion.FUSION_WAIT_EXTRA_STEPS + 3,
                15_240L));
        assertFalse(tooFar.describe(), tooFar.valid);
        assertTrue(tooFar.reason, tooFar.reason.contains("the incoming's vocals are out for")
                || tooFar.reason.contains("one render may separate"));
        // And the wait still cannot be shortened away by the band rule: the caller's steps are the
        // floor for the shortening, exactly as the measurement's own are.
        StemFusion.Plan withWait = StemFusion.plan(withExtra(1, 15_240L, DEVICE));
        assertTrue(withWait.describe(), withWait.valid);
        assertTrue("the wait's own step (4) is at least the caller's (3)",
                withWait.holdEndMs - withWait.entryMs >= 4L * step);
    }

    /**
     * The loop's own rule, pinned: a passing measurement beats any failing one, and between two that
     * fail the smaller hole wins — so a longer hold that does not close the hole is never kept
     * just for being later (the renderer keeps the best and, if none passes, discards the fusion
     * exactly as it does today).
     */
    @Test
    public void theLoopKeepsTheBestPulseAndAPassingOneWins() {
        StemFusion.Report hole = pulse(false, 3_965d);
        StemFusion.Report smaller = pulse(false, 2_832d);
        StemFusion.Report passing = pulse(true, 0d);
        assertTrue(StemFusion.betterPulse(passing, hole));
        assertFalse(StemFusion.betterPulse(hole, passing));
        assertTrue("between two that fail, the smaller hole wins", StemFusion.betterPulse(smaller,
                hole));
        assertFalse(StemFusion.betterPulse(hole, smaller));
        assertFalse("a tie is not an improvement", StemFusion.betterPulse(hole, hole));
        assertTrue("and the first candidate is always the best so far",
                StemFusion.betterPulse(hole, null));
        assertFalse("nothing beats nothing", StemFusion.betterPulse(null, hole));
    }

    private static StemFusion.Report pulse(boolean acceptable, double gapMs) {
        StemFusion.Report report = new StemFusion.Report();
        report.acceptable = acceptable;
        report.longestGapMs = gapMs;
        report.judgedPeriodMs = 566.4d;
        report.beats = 15;
        report.beatsWithAttack = acceptable ? 15 : 10;
        return report;
    }

    private static StemFusion.Input withExtra(int extra, long removalMs) {
        return withExtra(extra, removalMs, StemFusion.NO_INCOMING_MEASUREMENT);
    }

    private static StemFusion.Input withExtra(int extra, long removalMs,
                                             StemFusion.IncomingOn incoming) {
        double[] aBars = new double[60];
        for (int i = 0; i < aBars.length; i++) aBars[i] = 1_185.6d + i * 2_265.6d;
        double[] bBars = new double[9];
        for (int i = 0; i < bBars.length; i++) bBars[i] = 900d + i * BAR_MS;
        // ⚠️ The deck's ratio IS the bar ratio on this pair: the device's own coherent render
        // ("the carry stretched by the bar ratio", x0.965722) has MixNaturaliser stretching the
        // incoming, and the deck at anything else is the incoherence `plan` refuses (see
        // StemFusionTest.theDecksRatioAndTheCarrysRatioMustAgree).
        return new StemFusion.Input(96_816L, 15_000L, removalMs, 240L, 566.4d, 0d, BEAT_MS, 355d,
                BEAT_MS / 566.4d, aBars, bBars, StemFusion.NO_VOICE_MEASUREMENT,
                StemFusion.NO_GROOVE_MEASUREMENT, StemFusion.NO_BODY_MEASUREMENT, -1L, incoming,
                extra);
    }

    /** A kit on every beat of the window from {@code fromMs}, at {@code amp}. */
    private static float[][] kicks(double sec, long fromMs, long toMs, double amp) {
        float[][] out = new float[2][(int) (sec * RATE)];
        long step = Math.round(BEAT_MS);
        for (long at = fromMs; at + 40L < toMs; at += step) {
            int from = (int) (at * RATE / 1000L);
            for (int i = from; i < Math.min(out[0].length, from + RATE / 8); i++) {
                double envelope = Math.exp(-(i - from) / (RATE * 0.03d));
                double value = amp * envelope * Math.sin(2d * Math.PI * 60d * (i - from) / RATE);
                out[0][i] = (float) value;
                out[1][i] = (float) value;
            }
        }
        return out;
    }

    /** The device's own shape: two hits at the start, seven seconds of nothing (well under the
     *  margin), then the kit on every beat — so the answer has to be the kit's return. */
    private static float[][] drumsWithIntro(double sec) {
        float[][] out = new float[2][(int) (sec * RATE)];
        addHits(out, 240L, 2, 0.35d);
        addHits(out, 8_415L, (int) ((14_000L - 8_415L) / Math.round(BEAT_MS)), 0.35d);
        return out;
    }

    private static void addHits(float[][] into, long fromMs, int count, double amp) {
        long step = Math.round(BEAT_MS);
        for (int k = 0; k < count; k++) {
            int from = (int) ((fromMs + k * step) * RATE / 1000L);
            if (from >= into[0].length) return;
            for (int i = from; i < Math.min(into[0].length, from + RATE / 8); i++) {
                double envelope = Math.exp(-(i - from) / (RATE * 0.03d));
                double value = amp * envelope * Math.sin(2d * Math.PI * 60d * (i - from) / RATE);
                into[0][i] = (float) value;
                into[1][i] = (float) value;
            }
        }
    }
}
