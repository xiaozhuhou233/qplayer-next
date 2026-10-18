package dev.t1m3.qplayer.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The confidence gate, on signals whose answer is known: what it has to accept, and
 * what it has to keep refusing.
 *
 * <p>The gate used to be the autocorrelation peak's prominence, and on a real library
 * that refused roughly half of it — 0.12-0.34 for tracks whose grids are audibly
 * steady — because densely produced music has an onset at every subdivision and a
 * perfectly steady grid's peak therefore stands above very little. It is now how much
 * the analysed window agrees with itself (see {@code BeatAnalysis#windowAgreement}),
 * and both readings are printed by every case here so the two can be compared.
 *
 * <p>The half that matters as much as the pass rate is the refusals: a wrong grid acted
 * on is worse than no grid at all, so the cases below include the two ways a window can
 * fail to hold one grid — a tempo that changes across it, and a phase that only holds
 * for part of it — and neither may come back trustworthy.
 */
public class BeatAnalysisAgreementTest {

    private static final int RATE = 11_025;
    private static final double SECONDS = 30d;

    /** A plain 120 BPM click track is the answer every part of this is calibrated
     *  against: steady, unambiguous, and it must still come back as 120 BPM. */
    @Test
    public void aSteadyClickTrackIsTrusted() {
        double[] mono = clicks(120d, SECONDS, 1d);
        BeatProfile p = BeatAnalysis.analyse(mono, RATE, 0L);
        assertNotNull(p);
        assertEquals(120d, p.bpm(), 0.5d);
        assertTrue("a steady click track has to pass the gate: " + report(p), p.trustworthy());
        System.out.println("steady click 120BPM: " + report(p));
    }

    /** The material this whole change is about: a steady grid under a busy
     *  arrangement — kicks, offbeat hats, a bass line — where every lag of the
     *  envelope correlates with every other. The old reading punished this; the new
     *  one has to accept it, because the grid is exactly as steady as the click
     *  track's. */
    @Test
    public void aSteadyGridUnderADenseArrangementIsTrusted() {
        double[] mono = clicks(128d, SECONDS, 1d);          // kick on every beat
        hats(mono, 128d, 2d, 0.35d);                        // hat on every offbeat
        tone(mono, 128d, 55d);                              // a bass note per beat
        BeatProfile p = BeatAnalysis.analyse(mono, RATE, 0L);
        assertNotNull(p);
        assertEquals(128d, p.bpm(), 1d);
        assertTrue("a steady grid does not stop being steady when the mix is busy: "
                + report(p), p.trustworthy());
        System.out.println("dense 128BPM arrangement: " + report(p));
    }

    /** A tempo that changes across the window: one grid cannot describe it, and the
     *  segments say so. This is the case the agreement reading exists to catch — the
     *  old prominence reading is happy with it, because a click track's peak is sharp
     *  whichever tempo it is at. */
    @Test
    public void aTempoChangeAcrossTheWindowIsRefused() {
        double[] mono = new double[(int) (RATE * SECONDS)];
        addClicks(mono, 0d, 12d, 120d, 1d);
        addClicks(mono, 12d, 12d, 132d, 1d);
        addClicks(mono, 24d, 6d, 144d, 1d);
        BeatProfile p = BeatAnalysis.analyse(mono, RATE, 0L);
        if (p != null) {
            assertTrue("a window that changes tempo must not be trusted: " + report(p),
                    !p.trustworthy());
            System.out.println("120 -> 132 -> 144BPM: " + report(p));
        } else {
            System.out.println("120 -> 132 -> 144BPM: no grid at all");
        }
    }

    /** Two tempos in one window that share no lag the estimator can see (a common
     *  multiple of 120 and 170 BPM is 3.5 s, well past the longest lag it considers):
     *  no grid describes the window, and neither stretch confirms whatever the window
     *  picks. (A 2:3 pair like 120/180 is a different story and not a failure: 1 s is a
     *  multiple of both, so a grid at 60 BPM really is present in both halves — the
     *  metric accepts the sparsest common grid, which is the honest answer.) */
    @Test
    public void twoTemposWithNoCommonGridAreRefused() {
        double[] mono = new double[(int) (RATE * SECONDS)];
        addClicks(mono, 0d, 15d, 120d, 1d);
        addClicks(mono, 15d, 15d, 170d, 1d);
        BeatProfile p = BeatAnalysis.analyse(mono, RATE, 0L);
        if (p != null) {
            assertTrue("a window of two unrelated tempos is not one grid: " + report(p),
                    !p.trustworthy());
            System.out.println("120 then 170BPM: " + report(p));
        } else {
            System.out.println("120 then 170BPM: no grid at all");
        }
    }

    /** A half-beat phase jump in the middle of the window. The period is confirmed
     *  everywhere (support ~1) and the window's own phase is what most of the window
     *  supports, so the grid is still usable — and that is deliberate: an accent-level
     *  phase disagreement is the ambiguity dense real music produces, and refusing it
     *  is what refused half of a real library. What the reading must do is notice it:
     *  the phase half has to come out clearly below a steady track's. */
    @Test
    public void aPhaseJumpIsNoticedWithoutRefusingThePeriod() {
        double[] mono = new double[(int) (RATE * SECONDS)];
        addClicks(mono, 0d, 15d, 120d, 1d);
        // The same period, offset by half a beat, for the second half of the window.
        addClicks(mono, 15d + 0.25d, 15d, 120d, 1d);
        BeatProfile steady = BeatAnalysis.analyse(clicks(120d, SECONDS, 1d), RATE, 0L);
        BeatProfile jumped = BeatAnalysis.analyse(mono, RATE, 0L);
        assertNotNull(jumped);
        assertEquals(120d, jumped.bpm(), 0.5d);
        assertTrue("a half-beat jump must not be read as a different period: " + report(jumped),
                jumped.trustworthy());
        assertTrue("and it has to cost the reading something: steady " + report(steady)
                        + " vs jumped " + report(jumped),
                jumped.confidence() < steady.confidence() - 0.1f);
        System.out.println("120BPM with a half-beat jump: " + report(jumped));
    }

    /** A window whose thirds all put their beats somewhere different: the period is
     *  there (every third correlates at it), but no single phase has the window behind
     *  it, which is what the phase half still has to refuse. */
    @Test
    public void aWindowWhoseThirdsDisagreeAboutThePhaseIsRefused() {
        double[] mono = new double[(int) (RATE * SECONDS)];
        addClicks(mono, 0d, 10d, 120d, 1d);
        addClicks(mono, 10d + 0.20d, 10d, 120d, 1d);       // 0.4 of a beat later
        addClicks(mono, 20d + 0.225d, 10d, 120d, 1d);      // ... and 0.45 again
        BeatProfile p = BeatAnalysis.analyse(mono, RATE, 0L);
        if (p != null) {
            assertTrue("no third of this window agrees with the phase the others pick: "
                    + report(p), !p.trustworthy());
            System.out.println("120BPM, three phases in three thirds: " + report(p));
        } else {
            System.out.println("120BPM, three phases in three thirds: no grid at all");
        }
    }

    /** A click track under a wall of noise: the grid is still there, and the reading
     *  has to survive material this estimate is not flattered by. */
    @Test
    public void aSteadyGridSurvivesNoise() {
        double[] mono = clicks(100d, SECONDS, 1d);
        java.util.Random rng = new java.util.Random(7L);
        for (int i = 0; i < mono.length; i++) mono[i] += rng.nextGaussian() * 0.6d;
        BeatProfile p = BeatAnalysis.analyse(mono, RATE, 0L);
        assertNotNull(p);
        assertEquals(100d, p.bpm(), 1d);
        assertTrue("a click track under noise still has its grid: " + report(p), p.trustworthy());
        System.out.println("100BPM click under noise: " + report(p));
    }

    /** Material with no beat at all must still be given no grid — the gate is looser
     *  than it was, and that must not turn a pad or noise into a tempo. */
    @Test
    public void materialWithoutABeatStillGetsNoGrid() {
        double[] noise = new double[(int) (RATE * SECONDS)];
        java.util.Random rng = new java.util.Random(11L);
        for (int i = 0; i < noise.length; i++) noise[i] = rng.nextGaussian() * 0.5d;
        System.out.println("white noise: " + describe(BeatAnalysis.analyse(noise, RATE, 0L)));
        assertNull(BeatAnalysis.analyse(noise, RATE, 0L));

        // A drone: two slow sines and a bit of wobble, no attack anywhere.
        double[] pad = new double[(int) (RATE * SECONDS)];
        for (int i = 0; i < pad.length; i++) {
            double tSec = i / (double) RATE;
            pad[i] = Math.sin(2 * Math.PI * 110d * tSec) * 0.5d
                    + Math.sin(2 * Math.PI * 164.8d * tSec) * 0.4d
                    + Math.sin(2 * Math.PI * 0.7d * tSec) * 0.05d;
        }
        System.out.println("drone: " + describe(BeatAnalysis.analyse(pad, RATE, 0L)));
        assertNull(BeatAnalysis.analyse(pad, RATE, 0L));

        double[] silence = new double[(int) (RATE * SECONDS)];
        System.out.println("silence: " + describe(BeatAnalysis.analyse(silence, RATE, 0L)));
        assertNull(BeatAnalysis.analyse(silence, RATE, 0L));
    }

    /** A window too short to be cut into segments is judged on the older reading
     *  rather than refused outright: an eight-second decode still has a grid, and the
     *  gate must not turn it into "no beat" for a lack of segments. */
    @Test
    public void aWindowTooShortToSplitKeepsTheOlderReading() {
        double[] mono = clicks(150d, 8d, 1d);
        BeatProfile p = BeatAnalysis.analyse(mono, RATE, 0L);
        assertNotNull(p);
        assertEquals(150d, p.bpm(), 1d);
        System.out.println("8s window, 150BPM: " + report(p));
    }

    // --- signals ------------------------------------------------------------

    /** A click track at {@code bpm} for {@code seconds}, starting at time zero, with
     *  an impulse and a short decay on every beat. */
    private static double[] clicks(double bpm, double seconds, double gain) {
        double[] out = new double[(int) (RATE * seconds)];
        addClicks(out, 0d, seconds, bpm, gain);
        return out;
    }

    private static void addClicks(double[] out, double fromSec, double seconds, double bpm,
                                  double gain) {
        double periodSec = 60d / bpm;
        int end = Math.min(out.length, (int) ((fromSec + seconds) * RATE));
        double tail = Math.exp(-1d / (0.012d * RATE));      // ~12ms decay
        for (double at = fromSec; at < fromSec + seconds; at += periodSec) {
            int start = (int) Math.round(at * RATE);
            double level = gain;
            for (int i = start; i < end && i < start + 400; i++) {
                out[i] += level;
                level *= tail;
            }
        }
    }

    /** Offbeat accents, {@code perBeat} of them per beat. */
    private static void hats(double[] out, double bpm, double perBeat, double gain) {
        double periodSec = 60d / bpm / perBeat;
        java.util.Random rng = new java.util.Random(3L);
        for (double at = periodSec / 2d; at < out.length / (double) RATE; at += periodSec) {
            int start = (int) Math.round(at * RATE);
            double level = gain;
            for (int i = start; i < start + 60 && i < out.length; i++) {
                out[i] += (rng.nextDouble() * 2d - 1d) * level;
                level *= 0.9d;
            }
        }
    }

    /** A low sine per beat, so the low end is busy as well. */
    private static void tone(double[] out, double bpm, double hz) {
        double periodSec = 60d / bpm;
        for (double at = 0d; at < out.length / (double) RATE; at += periodSec) {
            int start = (int) Math.round(at * RATE);
            int len = (int) (periodSec * RATE * 0.8d);
            for (int i = start; i < start + len && i < out.length; i++) {
                double tSec = (i - start) / (double) RATE;
                double envelope = Math.exp(-tSec / (periodSec * 0.35d));
                out[i] += Math.sin(2 * Math.PI * hz * tSec) * 0.5d * envelope;
            }
        }
    }

    private static String report(BeatProfile p) {
        if (p == null) return "no grid";
        return p.label() + (p.trustworthy() ? " TRUSTED" : " refused");
    }

    private static String describe(BeatProfile p) {
        return report(p);
    }
}
