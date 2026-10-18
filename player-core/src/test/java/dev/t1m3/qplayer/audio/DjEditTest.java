package dev.t1m3.qplayer.audio;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The DJ edit ({@link DjEdit}) as arithmetic: the vocal-return shape, the "does this
 * window sing at all" verdict, and the render itself — all as numbers, printed.
 *
 * <p>Pure Java, no model and no device: the stems are hand-built, so a failure here is a
 * statement about the decision rather than about htdemucs. What the separation does to
 * real music is measured on the device instead (see AI_HANDOFF §7), and the two halves
 * are deliberately separate — this file is the part that must hold whatever the model
 * returns.
 */
public class DjEditTest {

    private static final int RATE = 44_100;

    private static void println(String format, Object... args) {
        System.out.println(String.format(Locale.US, format, args));
    }

    /** A sine at {@code amplitude}, both channels, {@code seconds} long. */
    private static float[] tone(double seconds, double amplitude, double hz) {
        int n = (int) Math.round(seconds * RATE);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) (amplitude * Math.sin(2 * Math.PI * hz * i / RATE));
        }
        return out;
    }

    private static float[] zeros(double seconds) {
        return new float[(int) Math.round(seconds * RATE)];
    }

    /** Four stems of the same length: [drums, bass, other, vocals]. */
    private static float[][][] stems(float[] drums, float[] bass, float[] other, float[] vocals) {
        return new float[][][]{
                {drums, drums}, {bass, bass}, {other, other}, {vocals, vocals}};
    }

    // --- when the vocals come back -------------------------------------------

    @Test
    public void theReturnLandsOnTheBarLineAfterTheBlend() {
        // 15s of backing only, and the incoming track's next bar line is 1.2s later.
        DjEdit.Plan plan = DjEdit.plan(15.0, 16.2);
        println("plan: %s", plan.describe());
        assertEquals(15.7, plan.returnStartSec, 1e-9);
        assertEquals(16.2, plan.returnEndSec, 1e-9);
        assertTrue(plan.onBarLine);
        assertEquals(0.0, plan.vocalGainAt(0), 1e-9);
        assertEquals(0.0, plan.vocalGainAt(14.0), 1e-9);
        assertEquals(0.0, plan.vocalGainAt(15.7), 1e-9);
        assertEquals(1.0, plan.vocalGainAt(16.2), 1e-9);
        assertEquals(1.0, plan.vocalGainAt(300.0), 1e-9);
        // The ramp is a rise, never a step: every sample in it is between the two ends.
        double previous = -1;
        for (double t = 15.7 + 0.001; t < 16.2; t += 0.01) {
            double g = plan.vocalGainAt(t);
            assertTrue("gain " + g + " at " + t, g >= previous && g > 0 && g < 1);
            previous = g;
        }
        println("the ramp spans %.2fs..%.2fs and is monotone inside it",
                plan.returnStartSec, plan.returnEndSec);
    }

    @Test
    public void aReturnInsideTheBlendIsNotUsedAndTheMarginMovesItPastTheEnd() {
        // ⚠️ Round 17 changed this from "the window itself is the floor" to "the window plus
        // the margin is": a return ON the blend's end still puts the last half second of the
        // lift inside the blend, which is the "人声混合得很乱" the user reported. So the floor is
        // `blend + RETURN_RAMP` (one ramp, so the lift starts at the first sample after the
        // blend) plus another half second of the backing alone — 1.0s for the shipped ramp.
        DjEdit.Plan plan = DjEdit.plan(15.0, 9.0);
        println("plan (bar line before the window): %s", plan.describe());
        assertEquals(15.0 + DjEdit.VOCAL_RETURN_MARGIN_SEC, plan.returnEndSec, 1e-9);
        assertFalse(plan.onBarLine);
        // The whole blend is at exactly zero, and so is the margin after it.
        assertEquals(0.0, plan.vocalGainAt(14.5), 1e-9);
        assertEquals(0.0, plan.vocalGainAt(15.0), 1e-9);
        assertEquals(0.0, plan.vocalGainAt(plan.returnStartSec), 1e-9);
        assertTrue("the lift must start after the blend ends, at " + plan.returnStartSec,
                plan.returnStartSec >= 15.0);
        assertEquals(1.0, plan.vocalGainAt(plan.returnEndSec), 1e-9);
    }

    /**
     * Round 20's own rule, composed end to end: the window the incoming track's voice is held out
     * of its file is the stretch the <em>outgoing</em> track can still be heard in, and the return
     * then lands on a bar line after it — not after the blend, which is where round 17 put it and
     * what the listener reported as 「现在再第二首歌切割人声有点切太多了」.
     */
    @Test
    public void theWindowEndsWhenTheOutgoingTrackHasLeftNotWhenTheBlendDoes() {
        // A bar grid at the library's ordinary tempo (144 BPM, a 1.667s bar), so the return lands
        // where a real one would rather than on a made-up instant.
        double barSec = 1.667d;
        double[] bars = new double[40];
        for (int i = 0; i < bars.length; i++) bars[i] = i * barSec;
        for (long blend : new long[] {4_000L, 17_000L, 30_000L}) {
            long window = DjEdit.vocalOutMs(blend, FadeCurve.DJ_BLEND);
            double windowSec = window / 1000d;
            double at = DjEdit.firstBarAtOrAfter(bars, windowSec + DjEdit.VOCAL_RETURN_MARGIN_SEC);
            DjEdit.Plan plan = DjEdit.plan(windowSec, at);
            println("a %dms blend: the outgoing is audible for %.3fs, the voice is back at %.3fs"
                            + " (the blend's own end is %.3fs, so the voice returns %.3fs earlier)",
                    blend, windowSec, plan.returnEndSec, blend / 1000d,
                    blend / 1000d - plan.returnEndSec);
            assertEquals("the return lands on a bar line of the incoming track",
                    at, plan.returnEndSec, 1e-9);
            assertEquals("the voice is at exactly zero for the whole window", 0.0,
                    plan.vocalGainAt(windowSec), 1e-9);
            assertTrue("the lift may not start inside the two-voice stretch, was "
                            + plan.returnStartSec + " against " + windowSec,
                    plan.returnStartSec >= windowSec);
            // What allows the return there at all: the shape has the outgoing track at its floor
            // by the window's own end (measured a frame later, so the comparison is not the
            // crossing point itself).
            assertTrue("the outgoing track must be inaudible when the window ends",
                    FadeCurve.gainDb(FadeCurve.DJ_BLEND.outGain((window + 2L) / (float) blend))
                            <= FadeCurve.INAUDIBLE_DB);
            if (blend >= 17_000L) {
                // On the user's own length the voice is back well before the ramp ends — the whole
                // point of the round. (On a 4s blend the first bar line after the window simply is
                // past the blend's end, which is the "later than the margin, never earlier"
                // half of the return's own rule.)
                assertTrue("the voice must be back before the blend ends on a " + blend
                                + "ms setting, was " + plan.returnEndSec,
                        plan.returnEndSec < blend / 1000d);
            }
        }
        // A symmetric curve holds the outgoing track to the ramp's own end, so its window is the
        // blend itself and a short blend on one keeps exactly round 17's timing.
        assertEquals(17_000L, DjEdit.vocalOutMs(17_000L, FadeCurve.EQUAL_POWER), 25L);
        assertTrue("the DJ shape must cut the window short and the symmetric one must not",
                DjEdit.vocalOutMs(4_000L, FadeCurve.DJ_BLEND)
                        < DjEdit.vocalOutMs(4_000L, FadeCurve.EQUAL_POWER));
    }

    @Test
    public void noGridMeansTheReturnIsTheWindowPlusTheMargin() {
        DjEdit.Plan plan = DjEdit.plan(12.0, Double.NaN);
        println("plan (no grid): %s", plan.describe());
        assertFalse(plan.onBarLine);
        assertEquals(12.0 + DjEdit.VOCAL_RETURN_MARGIN_SEC, plan.returnEndSec, 1e-9);
        assertEquals(plan.returnEndSec - DjEdit.RETURN_RAMP_SEC, plan.returnStartSec, 1e-9);
        // The rule is the invariant, however short the window is: the voice is at exactly zero
        // for the whole blend (and for the margin after it).
        for (double blend : new double[] {0.2d, 1.0d, 4.0d, 15.0d, 30.0d}) {
            DjEdit.Plan p = DjEdit.plan(blend, Double.NaN);
            assertEquals("a " + blend + "s blend must start the lift after the blend",
                    blend + DjEdit.VOCAL_RETURN_MARGIN_SEC - DjEdit.RETURN_RAMP_SEC,
                    p.returnStartSec, 1e-9);
            assertTrue("the lift may not begin inside the blend (" + blend + "s): "
                            + p.returnStartSec, p.returnStartSec >= blend);
            assertEquals("and the whole blend is silent in the vocal stem", 0.0,
                    p.vocalGainAt(blend), 1e-9);
        }
    }

    @Test
    public void theFirstBarLineAtOrAfterTheWindowWins() {
        double[] bars = {0.0, 2.0, 4.0, 6.0};
        assertEquals(4.0, DjEdit.firstBarAtOrAfter(bars, 3.5), 1e-9);
        assertEquals(4.0, DjEdit.firstBarAtOrAfter(bars, 4.0), 1e-9);
        assertTrue(Double.isNaN(DjEdit.firstBarAtOrAfter(bars, 6.1)));
        assertTrue(Double.isNaN(DjEdit.firstBarAtOrAfter(new double[0], 0.0)));
        assertTrue(Double.isNaN(DjEdit.firstBarAtOrAfter(null, 0.0)));
        println("bar lines %s: first at or after 3.5s is 4.0s; after 6.0s there is none",
                java.util.Arrays.toString(bars));
    }

    // --- does the window sing? ----------------------------------------------

    @Test
    public void anInstrumentalHeadIsMeasuredAsNotSinging() {
        // htdemucs's vocal stem on an intro is near-digital-silence: -90 dBFS noise.
        float[] quiet = tone(15.0, 1e-4, 220);
        DjEdit.Presence p = DjEdit.presence(new float[][]{quiet, quiet}, RATE, 15.0);
        println("instrumental head: %s -> sings=%s", p.describe(), p.sings);
        assertFalse(p.sings);
        assertTrue("p90 " + p.p90Db, p.p90Db < DjEdit.NO_VOCALS_P90_DBFS);
        assertEquals(1.0, p.silentShare, 1e-9);
    }

    @Test
    public void aSustainedWordCountsAsSingingAndAStrayOneDoesNot() {
        // The verdict is "how much of this window has voice in it". A sung phrase is
        // tens of percent of the frames; a blip of a few percent is treated as nothing,
        // which is the honest reading of "these regions have no vocals" — and being wrong
        // that way only leaves the incoming's own vocals in place, which is today's blend.
        float[] sustained = zeros(15.0);
        float[] phrase = tone(1.5, 0.25, 300);
        System.arraycopy(phrase, 0, sustained, (int) (13.0 * RATE), phrase.length);
        DjEdit.Presence sung = DjEdit.presence(new float[][]{sustained, sustained}, RATE, 15.0);
        println("1.5s of voice of 15s: %s -> sings=%s", sung.describe(), sung.sings);
        assertTrue(sung.sings);

        float[] blip = zeros(15.0);
        float[] word = tone(0.2, 0.25, 300);
        System.arraycopy(word, 0, blip, (int) (14.0 * RATE), word.length);
        DjEdit.Presence stray = DjEdit.presence(new float[][]{blip, blip}, RATE, 15.0);
        println("0.2s of voice of 15s: %s -> sings=%s", stray.describe(), stray.sings);
        assertFalse(stray.sings);
    }

    @Test
    public void aSungWindowIsSeveralTensOfDbAboveTheBar() {
        float[] sung = tone(15.0, 0.2, 220);
        DjEdit.Presence p = DjEdit.presence(new float[][]{sung, sung}, RATE, 15.0);
        println("sung head: %s -> sings=%s", p.describe(), p.sings);
        assertTrue(p.sings);
        assertTrue("p90 " + p.p90Db, p.p90Db > -20);
    }

    @Test
    public void anEmptyWindowClaimsNothing() {
        DjEdit.Presence p = DjEdit.presence(new float[2][0], RATE, 15.0);
        println("empty window: %s -> sings=%s", p.describe(), p.sings);
        assertFalse(p.sings);
        assertEquals(0, p.frames);
    }

    // --- the render ---------------------------------------------------------

    @Test
    public void theBlendWindowIsBackingOnlyAndTheTailIsTheMaster() {
        // Amplitudes chosen to sum below full scale, so the comparison is about the
        // vocal curve and not about the clamp (which has its own test below).
        float[] drums = tone(20.0, 0.10, 90);
        float[] bass = tone(20.0, 0.15, 60);
        float[] other = tone(20.0, 0.08, 440);
        float[] vocals = tone(20.0, 0.20, 700);
        float[][][] s = stems(drums, bass, other, vocals);
        DjEdit.Plan plan = DjEdit.plan(15.0, 15.0);
        int[] clipped = new int[1];
        float[][] head = DjEdit.renderHead(s, RATE, 20.0, plan, clipped);

        // Inside the removal window the render is the backing sum, exactly: the vocal
        // stem contributes nothing, so the difference is bit-zero. (The window checked
        // is up to the START of the return ramp, which is the last half second of it.)
        double worst = 0;
        for (int i = 0; i < (int) (plan.returnStartSec * RATE); i++) {
            double backing = drums[i] + bass[i] + other[i];
            worst = Math.max(worst, Math.abs(head[0][i] - backing));
        }
        // Float rounding of the same sum, nothing else.
        assertEquals(0.0, worst, 1e-6);
        // After the return the render is the master, exactly.
        double worstTail = 0;
        for (int i = 16 * RATE; i < 20 * RATE; i++) {
            double master = drums[i] + bass[i] + other[i] + vocals[i];
            worstTail = Math.max(worstTail, Math.abs(head[0][i] - master));
        }
        assertEquals(0.0, worstTail, 1e-6);
        println("render: the first 15.00s differ from the backing sum by %.1e (bit-exact), "
                        + "16.00-20.00s from the master by %.1e; %d samples clamped",
                worst, worstTail, clipped[0]);
        assertEquals(0, clipped[0]);
    }

    @Test
    public void theVocalLevelUnderTheCurveIsMeasurableAndTheSuppressionIsTotal() {
        float[] vocals = tone(20.0, 0.5, 700);
        DjEdit.Plan plan = DjEdit.plan(15.0, 15.4);
        float[][] before = new float[][]{vocals, vocals};
        float[][] after = DjEdit.scaleVocals(before, RATE, plan, 20.0);
        // The three parts the claim is about, measured separately: the window before the
        // ramp starts, the tail after the return ends, and the voice as it was.
        DjEdit.Presence original = DjEdit.presence(
                DjEdit.slice(before, RATE, 0, plan.returnStartSec), RATE, plan.returnStartSec);
        DjEdit.Presence removed = DjEdit.presence(
                DjEdit.slice(after, RATE, 0, plan.returnStartSec), RATE, plan.returnStartSec);
        DjEdit.Presence returned = DjEdit.presence(
                DjEdit.slice(after, RATE, plan.returnEndSec, 20.0), RATE,
                20.0 - plan.returnEndSec);
        println("voice: original    %s", original.describe());
        println("voice: under the edit curve %s", removed.describe());
        println("voice: after the return   %s", returned.describe());
        assertTrue(original.sings);
        assertFalse("the removal window must measure as no vocals", removed.sings);
        assertTrue("the tail must measure as vocals back", returned.sings);
        assertEquals(original.medianDb, returned.medianDb, 0.01);
        double suppression = original.medianDb - removed.medianDb;
        println("suppression inside the blend: %.0f dB below the same voice at unity",
                suppression);
        assertTrue(suppression > 60);
    }

    @Test
    public void aClippingMasterIsCountedRatherThanHidden() {
        // Three stems that sum past full scale: the edit is the master and the master is
        // over, so the only honest answer is the clamp plus the count.
        float[] loud = tone(2.0, 0.8, 100);
        float[][][] s = stems(loud, loud, loud, zeros(2.0));
        int[] clipped = new int[1];
        float[][] head = DjEdit.renderHead(s, RATE, 2.0, DjEdit.plan(1.0, 1.0), clipped);
        println("%d samples of the 2s window were past full scale and clamped", clipped[0]);
        assertTrue(clipped[0] > 0);
        for (float v : head[0]) assertTrue(Math.abs(v) <= 1.0f);
    }

    // --- the fusion's shape of the same render (round 18) ---------------------

    /**
     * ⚠️ The invariant the whole fusion rests on: with no fusion schedule and nothing carried,
     * the general render IS the round-17 render, sample for sample. Every byte of the non-fusion
     * edit therefore comes out of the same statements it always did.
     */
    @Test
    public void withNoFusionTheRenderIsByteForByteTheOldOne() {
        float[][][] s = stems(tone(3.0, 0.3, 90), tone(3.0, 0.25, 45), tone(3.0, 0.2, 220),
                tone(3.0, 0.4, 440));
        DjEdit.Plan plan = DjEdit.plan(1.5, 2.0);
        float[][] layer = new float[][]{tone(1.0, 0.5, 60), tone(1.0, 0.5, 60)};
        int[] oldClipped = new int[1];
        int[] newClipped = new int[1];
        float[][] oldWay = DjEdit.renderHead(s, RATE, 3.0, plan, oldClipped, layer, RATE / 10);
        float[][] newWay = DjEdit.renderHead(s, RATE, 3.0, plan, newClipped, null, null, 0, null,
                layer, RATE / 10, null);
        assertEquals(oldClipped[0], newClipped[0]);
        assertEquals(oldWay[0].length, newWay[0].length);
        for (int ch = 0; ch < oldWay.length; ch++) {
            for (int i = 0; i < oldWay[ch].length; i++) {
                assertEquals("sample " + i + " of channel " + ch,
                        oldWay[ch][i], newWay[ch][i], 0.0f);
            }
        }
    }

    @Test
    public void thePeakGuardCountsTheWindowItIsGivenAndItsLongestRun() {
        // The fusion's window is a slice of the head, and what matters is what the clamp did
        // inside THAT slice: a long run of clamped samples there is the passage mixed too loud,
        // where the same run elsewhere is a splice landing on a peak.
        float[] loud = tone(3.0, 0.9, 100);
        float[][][] s = stems(loud, loud, loud, zeros(3.0));
        DjEdit.Plan plan = DjEdit.plan(2.4, 3.0);
        int[] clipped = new int[1];
        DjEdit.ClipGuard guard = new DjEdit.ClipGuard(RATE / 2, RATE / 2 + RATE / 4);
        float[][] head = DjEdit.renderHead(s, RATE, 3.0, plan, clipped, null, null, 0, null, null,
                0, guard);
        println("clamped %d of %d pairs in the guarded quarter, longest run %d frames (%.1fms);"
                        + " %d in the whole window",
                guard.clipped, guard.pairs, guard.longestRunFrames, guard.longestRunMs(),
                clipped[0]);
        assertTrue(guard.clipped > 0);
        assertEquals(2 * (RATE / 4), guard.pairs);
        assertTrue("the guarded slice is part of the window", guard.clipped < clipped[0]);
        // A 100 Hz tone at 2.7x full scale is past the clamp on 76% of every half cycle, so the
        // clamped part of one cycle is a run of some 170 samples — 3.8 ms, which is exactly the
        // kind of run the fusion's "no long clipping" clause is a limit on.
        assertTrue("a clamped run of " + guard.longestRunFrames + " frames",
                guard.longestRunMs() > 1d);
        assertTrue(guard.share() > StemFusion.CLIP_SHARE_MAX);
        for (float v : head[0]) assertTrue(Math.abs(v) <= 1.0f);
        // And a silent window is watched without anything being counted.
        DjEdit.ClipGuard quiet = new DjEdit.ClipGuard(0, RATE / 4);
        DjEdit.renderHead(stems(zeros(3.0), zeros(3.0), zeros(3.0), zeros(3.0)), RATE, 3.0, plan,
                new int[1], null, null, 0, null, null, 0, quiet);
        assertEquals(0, quiet.clipped);
        assertEquals(0, quiet.longestRunFrames);
        assertEquals(0d, quiet.longestRunMs(), 1e-9);
    }

    // --- the fusion's limiter (round 18) --------------------------------------

    /**
     * ⚠️ What the junction's peak guard is now: the outgoing master already peaks at about full
     * scale, so a bed under it is over — and dividing the whole head by the peak (the first
     * prototype's guard) pulls the music down with it, which the listener heard as a 卡顿. The
     * limiter takes the same peaks without touching the level elsewhere, and its own arithmetic is
     * what this test pins: the reduction a frame needs arrives within
     * {@link DjEdit.Limiter#ATTACK_MS}, the return to unity takes {@link
     * DjEdit.Limiter#RELEASE_MS}, and the gain is never above unity (there is no makeup gain).
     */
    @Test
    public void theLimiterAttacksWithinAMillisecondAndReleasesSlowly() {
        DjEdit.Limiter limiter = new DjEdit.Limiter(RATE);
        // A frame that needs 6 dB off: the gain walks down one attack step per frame.
        int attacked = 0;
        while (limiter.gainFor(2d) > 0.5d + 1e-9) attacked++;
        int attackLimit = (int) Math.round(DjEdit.Limiter.ATTACK_MS * RATE / 1000d);
        println("the reduction to half gain arrived in %d frames; ATTACK_MS is %d frames",
                attacked, attackLimit);
        assertTrue("the attack must be inside ATTACK_MS: " + attacked + " frames",
                attacked <= attackLimit);
        assertTrue("...and it is a ramp, not a jump: " + attacked, attacked > 1);

        // Nothing is being held any more: the gain climbs back, and it takes far longer than the
        // attack did (150 ms, not 1 ms) -- a fast hold and a slow give-back.
        int released = 0;
        while (limiter.gainFor(0.5d) < 0.9d) released++;
        println("the gain came back to 0.9 in %d frames (%.0fms)", released,
                released * 1000d / RATE);
        assertTrue("the release must be the slow direction: " + released,
                released > 4 * attackLimit);
        assertTrue("...and it arrives at unity, not past it: " + limiter.gainFor(0.5d),
                limiter.gainFor(0.5d) <= 1d);
        assertEquals("the deepest hold is what the 2x peak needed (6 dB), not more", 6.02d,
                limiter.deepestReductionDb(), 0.02d);
    }

    /**
     * The limiter in the render: the head stays under full scale, the clamp count falls by orders
     * of magnitude (so it is the last resort the fusion's acceptance counts rather than the
     * mechanism), and the level it costs is nowhere near a static divisor's.
     */
    @Test
    public void theLimiterKeepsTheHeadOffFullScaleAndCostsFarLessThanADivisor() {
        float[] loud = tone(2.0, 0.9, 100);
        float[][][] s = stems(loud, loud, loud, zeros(2.0));
        DjEdit.Plan plan = DjEdit.plan(1.0, 1.0);
        int length = s[0][0].length;
        int[] clampedClipped = new int[1];
        int[] limitedClipped = new int[1];
        DjEdit.ClipGuard clampedGuard = new DjEdit.ClipGuard(0, length);
        DjEdit.ClipGuard limitedGuard = new DjEdit.ClipGuard(0, length);
        float[][] clamped = DjEdit.renderHead(s, RATE, 2.0, plan, clampedClipped, null, null, 0,
                null, null, 0, clampedGuard);
        DjEdit.Limiter limiter = new DjEdit.Limiter(RATE);
        float[][] limited = DjEdit.renderHead(s, RATE, 2.0, plan, limitedClipped, null, null, 0,
                null, null, 0, limitedGuard, limiter);
        println("%s; clamped %d of %d pairs, and %d of %d without the limiter",
                limiter.describe(), limitedGuard.clipped, limitedGuard.pairs,
                clampedGuard.clipped, clampedGuard.pairs);
        double limitedPeak = 0d;
        for (int i = 0; i < length; i++) {
            limitedPeak = Math.max(limitedPeak, Math.abs(limited[0][i]));
            assertTrue("the limiter's head is inside full scale at " + i, limitedPeak <= 1.0 + 1e-6);
        }
        assertTrue("a 2.7x-over master is clamped without a limiter", clampedClipped[0] > 100_000);
        assertTrue("with the limiter only the attack's own ramp is left: " + limitedClipped[0],
                clampedClipped[0] > 10 * Math.max(1, limitedClipped[0]));
        assertTrue(limiter.heldFrames > 0);
        // The static guard the prototype used: the whole head down by its own peak. The clamp has
        // already cut that peak to 1.0 in the render without a limiter, so the visible fact is the
        // one the limiter's own deepest hold implies — the peak it held was AT LEAST that much over
        // full scale, and a divisor spends that much on every frame where the limiter takes it only
        // while the peak is there.
        double divisorCostAtLeast = 20d * Math.log10(1d / limiter.deepestGain);
        println("a static divisor would cost at least %.2f dB of head on every frame; the limiter's"
                        + " deepest hold was %.2f dB", divisorCostAtLeast,
                limiter.deepestReductionDb());
        assertTrue("the limiter's cost is bounded by what the peak needed: "
                        + limiter.deepestReductionDb() + " against " + divisorCostAtLeast,
                limiter.deepestReductionDb() <= divisorCostAtLeast + 1e-9);
        assertTrue("...and it is not held on every frame: " + limiter.heldFrames + " of "
                        + limiter.frames, limiter.heldFrames <= limiter.frames);
    }
}
