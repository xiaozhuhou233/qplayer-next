package dev.t1m3.qplayer.audio;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The naturaliser: how much of the incoming track's tempo and key is corrected, and —
 * the part this round exists for — that <b>nothing here can refuse a blend</b>.
 *
 * <p>The arithmetic is the same as the earlier round's {@code MixMatch}, which was
 * calibrated against real material; what changed is its role. It used to answer
 * "suitable / not suitable" and a pair it refused fell back to a shorter, un-stretched
 * ramp — a taste measurement promoted to a precondition. It now answers only "what
 * should be applied", with "nothing" being a perfectly ordinary answer that leaves the
 * overlap exactly as it would have been without any of this.
 *
 * <p>Synthetic profiles, so every answer is known in advance — the same reason
 * {@code BeatAnalysisAgreementTest} and {@code KeyAnalysisTest} are written that way.
 */
public class MixNaturaliserTest {

    private static final long OVERLAP_MS = 15_000L;

    // --- synthetic profiles --------------------------------------------------

    /**
     * A grid at {@code bpm} whose beats start at {@code firstBeatMs}. Confidence
     * 0.8 and a prominence reading, so it is {@link BeatProfile#trustworthy()} and
     * looks like something the estimator produced.
     */
    private static BeatProfile grid(double bpm, long firstBeatMs) {
        return new BeatProfile(bpm, firstBeatMs, 0.8f, 0.7f, null);
    }

    /** The same grid with a key measured alongside it. */
    private static BeatProfile grid(double bpm, long firstBeatMs, KeyProfile key) {
        return new BeatProfile(bpm, firstBeatMs, 0.8f, 0.7f, key);
    }

    /**
     * A major key's pitch-class profile: its tonic, third and fifth carry most of the
     * weight, the other nine classes a low floor (real music never has zeros, and a
     * bare triad is not what {@link KeyProfile#distance} was calibrated on). Roughly
     * 0.84 of the profile sits on the triad, well over {@link KeyProfile#MIN_TRIAD}.
     */
    private static KeyProfile majorKey(int tonic) {
        double[] c = new double[12];
        for (int i = 0; i < 12; i++) c[i] = 0.05d;
        c[tonic] += 1.00d;
        c[(tonic + 4) % 12] += 0.70d;
        c[(tonic + 7) % 12] += 0.60d;
        return new KeyProfile(tonic, true, 0.8f, normalize(c));
    }

    /** As above but with one more pitch class barely touched — a profile that is
     *  almost, but not exactly, another one. */
    private static KeyProfile majorKeyPlus(int tonic, int extra, double weight) {
        double[] c = new double[12];
        for (int i = 0; i < 12; i++) c[i] = 0.05d;
        c[tonic] += 1.00d;
        c[(tonic + 4) % 12] += 0.70d;
        c[(tonic + 7) % 12] += 0.60d;
        c[extra % 12] += weight;
        return new KeyProfile(tonic, true, 0.8f, normalize(c));
    }

    private static double[] normalize(double[] c) {
        double sum = 0d;
        for (double v : c) sum += v;
        double[] out = new double[12];
        for (int i = 0; i < 12; i++) out[i] = sum > 0d ? c[i] / sum : 0d;
        return out;
    }

    // --- the tempo -----------------------------------------------------------

    @Test
    public void theIncomingTempoIsPulledOntoTheOutgoingGrid() {
        // 126 into 120: B is 5% fast, so it is played 4.76% slower — inside the clamp,
        // and the whole point of a naturaliser (a 15s overlap's compatibility gate
        // tolerates about 1.6%, so this pair could never be aligned untouched).
        MixNaturaliser nat = MixNaturaliser.between(grid(120d, 0L), grid(126d, 0L), OVERLAP_MS);
        assertEquals(120d / 126d, nat.speed(), 1e-9d);
        assertEquals(0.0476d, 1d - nat.speed(), 1e-4d);
        assertTrue("the note must state the ratio and the BPM it came from: " + nat.note(),
                nat.note().contains("speed x0.9524") && nat.note().contains("126.0->120.0BPM"));
        assertEquals(0, nat.semitones());
    }

    @Test
    public void aStretchedTrackLandsExactlyOnTheOutgoingTracksPeriod() {
        // The claim the whole alignment rests on: after the pull, B's grid and A's grid
        // are the same period, so the phase between them can no longer slip — which is
        // what the compatibility gate is asking about. 24 tempo pairs, one of them a
        // pair that must NOT be stretched (outside the clamp).
        int stretched = 0;
        int leftAlone = 0;
        for (int bpmA = 90; bpmA <= 180; bpmA += 15) {
            for (double factor : new double[] {0.96d, 1.00d, 1.04d}) {
                double bpmB = bpmA * factor;
                BeatProfile a = grid(bpmA, 37L);
                BeatProfile b = grid(bpmB, 210L);
                MixNaturaliser nat = MixNaturaliser.between(a, b, OVERLAP_MS);
                BeatProfile heard = MixNaturaliser.asHeard(b, nat.speed());
                if (nat.hasTempo()) {
                    stretched++;
                    assertEquals("B's period as heard must be A's period exactly", a.periodMs(),
                            heard.periodMs(), 1e-6d);
                    assertTrue("a stretched pair must be compatible over the whole overlap",
                            BeatProfile.gridsCompatible(a, heard, OVERLAP_MS));
                    assertEquals(0L, BeatProfile.gridDriftMs(a, heard, OVERLAP_MS));
                } else {
                    leftAlone++;
                    assertEquals(bpmB, heard.bpm(), 1e-9d);
                }
            }
        }
        assertTrue("most pairs should be inside the clamp", stretched >= 8);
        assertTrue("an already-matching pair should be left alone", leftAlone >= 2);
    }

    @Test
    public void aTempoDifferenceOutsideTheClampIsNotStretchedAndTheBlendStillRuns() {
        // 120 vs 140 needs x0.857 — ±20%, where the time-stretch drops the transients
        // that make a beat sound like a beat. The honest answer is "do not stretch"; it
        // is emphatically NOT "do not blend", which is what the earlier round did.
        MixNaturaliser nat = MixNaturaliser.between(grid(120d, 0L), grid(140d, 0L), OVERLAP_MS);
        assertNotNull(nat);
        assertEquals(1d, nat.speed(), 1e-9d);
        assertEquals(0, nat.semitones());
        assertFalse(nat.appliesSomething());
        assertTrue("the log has to say why nothing was stretched: " + nat.note(),
                nat.note().contains("no tempo") && nat.note().contains("blend runs anyway"));
        // The caller's own instruction is a mix that asks for nothing — not a refusal.
        assertTrue(IncomingMix.of(-1L, nat.speed(), nat.semitones(), nat.note()).isIdentity());
    }

    @Test
    public void aPairWithNoGridsIsLeftAloneRatherThanRefused() {
        MixNaturaliser none = MixNaturaliser.between(null, null, OVERLAP_MS);
        assertEquals(1d, none.speed(), 1e-9d);
        assertEquals(0, none.semitones());
        assertTrue(none.note().contains("no credible grid"));
        // One side missing is the same answer, and it names the side.
        MixNaturaliser half = MixNaturaliser.between(grid(120d, 0L), null, OVERLAP_MS);
        assertEquals(1d, half.speed(), 1e-9d);
        assertTrue(half.note().contains("no credible grid for B"));
        // A grid that is measured but not trustworthy is not acted on either — pulling
        // a real beat onto a grid that is an artefact of one window would move it onto
        // a wrong one.
        BeatProfile weak = new BeatProfile(126d, 0L, 0.10f, 0.2f, null);
        MixNaturaliser lowConfidence =
                MixNaturaliser.between(grid(120d, 0L), weak, OVERLAP_MS);
        assertEquals(1d, lowConfidence.speed(), 1e-9d);
        assertTrue(lowConfidence.note().contains("not trustworthy"));
    }

    // --- the key -------------------------------------------------------------

    @Test
    public void aKeyShiftIsAppliedWhenItMeasuresBetter() {
        // D major into C major: -2 semitones puts D's tonic, third and fifth exactly on
        // C's, and C/D is a whole-key relationship the Camelot rule sanctions.
        MixNaturaliser nat = MixNaturaliser.between(
                grid(120d, 0L, majorKey(0)), grid(120d, 0L, majorKey(2)), OVERLAP_MS);
        assertEquals(-2, nat.semitones());
        assertEquals(Math.pow(2d, -2d / 12d), nat.pitch(), 1e-12d);
        assertTrue("the note must name both keys, the shift and the wheel positions: "
                        + nat.note(),
                nat.note().contains("-2 semitones") && nat.note().contains("(8B)"));
    }

    @Test
    public void keysWithNothingInCommonAreNotTransposedAndTheBlendStillRuns() {
        // C major against F# major: no shift within ±2 puts either on the other, and
        // the answer is "leave it alone" — measured, not refused.
        MixNaturaliser nat = MixNaturaliser.between(
                grid(120d, 0L, majorKey(0)), grid(120d, 0L, majorKey(6)), OVERLAP_MS);
        assertEquals(0, nat.semitones());
        assertTrue("the log has to name the measurement and deny any refusal: " + nat.note(),
                nat.note().contains("no pitch") && nat.note().contains("blend runs anyway"));
    }

    @Test
    public void aShiftThatIsAllowedButWouldNotHelpIsNotApplied() {
        // The same key measured twice with a hair of energy somewhere else: the
        // profiles are already close (which this class's own MAX_KEY_DISTANCE says
        // means "these can be heard together"), and a shift that moves them by less
        // than MIN_KEY_IMPROVEMENT is not evidence about the key. It must NOT become
        // "these keys clash" — that is the measured defect this branch was written for.
        KeyProfile cMajor = majorKey(0);
        KeyProfile cMajorPlusD = majorKeyPlus(0, 2, 0.02d);
        MixNaturaliser nat = MixNaturaliser.between(
                grid(120d, 0L, cMajor), grid(120d, 0L, cMajorPlusD), OVERLAP_MS);
        assertEquals(0, nat.semitones());
        double atZero = KeyProfile.distance(cMajor.chroma(), cMajorPlusD.chroma());
        assertTrue("this pair is close enough that a shift cannot be worth making",
                atZero < 0.55d);
        assertTrue("the note should say the shift was allowed and not worth it: " + nat.note(),
                nat.note().contains("pitch x1.0000") && nat.note().contains("already sit together"));
    }

    @Test
    public void anUnmeasurableKeyIsNamedRatherThanGuessedAt() {
        MixNaturaliser nat = MixNaturaliser.between(
                grid(120d, 0L, majorKey(0)), grid(120d, 0L), OVERLAP_MS);
        assertEquals(0, nat.semitones());
        assertTrue(nat.note().contains("no key measured for B"));
        // And a key that is measured but below the confidence floor is not acted on.
        KeyProfile weak = new KeyProfile(2, true, 0.10f, majorKey(2).chroma());
        MixNaturaliser weakNat = MixNaturaliser.between(
                grid(120d, 0L, majorKey(0)), grid(120d, 0L, weak), OVERLAP_MS);
        assertEquals(0, weakNat.semitones());
        assertTrue(weakNat.note().contains("not trustworthy"));
    }

    // --- the instruction the backend is given ---------------------------------

    @Test
    public void theInstructionCarriesTheRatioTheSemitonesAndTheHandOver() {
        IncomingMix mix = IncomingMix.of(4_700L, 120d / 126d, -2, "measured");
        assertTrue(mix.hasTempo());
        assertEquals(120d / 126d, mix.speed(), 1e-9d);
        assertEquals(-2, mix.semitones());
        assertEquals(4_700L, mix.bassSwapAtMs());
        assertEquals(Math.pow(2d, -2d / 12d), mix.pitch(), 1e-12d);
        assertFalse(mix.isIdentity());
        assertEquals("IncomingMix{x0.9524, -2 semitones, bassSwap=4700ms}", mix.toString());
        // The key of the read-back contract: the backend answers with what it applied,
        // and a platform that ignored the parameters must not look like agreement.
        assertTrue(mix.sameTempoAndPitch(IncomingMix.of(-1L, 120d / 126d, -2, null)));
        assertFalse(mix.sameTempoAndPitch(IncomingMix.of(-1L, 1d, 0, null)));
        assertFalse(mix.sameTempoAndPitch(IncomingMix.of(-1L, 120d / 126d, 1, null)));
        assertFalse(mix.sameTempoAndPitch(null));
    }

    @Test
    public void aMixThatAsksForNothingIsStillTheIdentity() {
        assertTrue(IncomingMix.of(-1L, 1d, 0, "no tempo: ...; no pitch: ...").isIdentity());
        assertTrue(IncomingMix.IDENTITY.isIdentity());
        assertTrue(IncomingMix.bassSwapAt(-1L).isIdentity());
        assertFalse(IncomingMix.bassSwapAt(0L).isIdentity());
        assertEquals("IncomingMix{bassSwap=4700ms}", IncomingMix.bassSwapAt(4_700L).toString());
        // Semitones are clamped to what the platform's shift is allowed to be, even for
        // a hand-built instruction — nothing can ask for a transposition a listener
        // would hear as "the wrong record".
        assertEquals(IncomingMix.MAX_SEMITONES,
                IncomingMix.of(-1L, 1d, 9, null).semitones());
        assertEquals(-IncomingMix.MAX_SEMITONES,
                IncomingMix.of(-1L, 1d, -9, null).semitones());
    }

    @Test
    public void theNoteAlwaysNamesBothHalvesSoNothingCanBeSilentlyOmitted() {
        // Every answer says what was decided about the tempo and about the key, in that
        // order — that is what makes a boundary's single log line readable as a
        // decision rather than as an omission.
        MixNaturaliser nat = MixNaturaliser.between(
                grid(120d, 0L, majorKey(0)), grid(126d, 0L, majorKey(2)), OVERLAP_MS);
        assertTrue(nat.note().startsWith("speed x"));
        assertTrue(nat.tempoNote().startsWith("speed x"));
        assertTrue("the key half has to be about the pitch: " + nat.keyNote(),
                nat.keyNote().contains("pitch x"));
        assertTrue(nat.appliesSomething());
        assertEquals(120d / 126d, nat.speed(), 1e-9d);
    }
}
