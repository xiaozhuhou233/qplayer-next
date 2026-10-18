package dev.t1m3.qplayer.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The harmonic-relation check in the period choice, from the side a synthetic signal can
 * answer: what it must <em>not</em> do.
 *
 * <p>The check exists for one failure mode of autocorrelation, and it is a failure mode
 * synthetic material does not have. On real material whose groove is dotted or compound the
 * onset envelope really is more periodic at one and a half beats than at the beat — measured
 * on one library, a 109 BPM track's strongest lag sat at 72.7 BPM and a 126 BPM track's at
 * 83.8, both exactly 2:3 — while the attacks still sit on the finer grid (that grid collects
 * 39% more onset energy over a 30 s window). The argument for the check, and the numbers
 * behind its two thresholds, live in {@code BeatAnalysis#finerBeat} and in the round that
 * introduced it; they cannot be reproduced here, because a click track with anything put
 * between its beats collects 0.30-0.50 of the coarser grid's per-pulse energy at the finer
 * one, and the threshold is 0.8.
 *
 * <p>So this is the other half of the acceptance, and the half a unit test can hold: on
 * material whose answer is known, nothing moved. Every case below came back with exactly the
 * tempo it came back with before the check existed — including the three a "prefer the finest
 * supported grid" rule would be most likely to break: a click track with a soft attack
 * between every pair of beats (a 2:3 relation present in the signal), one with off-beat
 * eighths, and one whose beats are a second apart with nothing between them (a genuine
 * half-time grid, which must not be read at double).
 */
public class BeatAnalysisHarmonicRelationTest {

    private static final int RATE = 11_025;
    private static final double SECONDS = 30d;

    /** The tempo the whole thing is calibrated against: it must survive the check. */
    @Test
    public void everyClickTempoComesBackUnchanged() {
        double[] tempos = {55, 70, 90, 109, 120, 128, 145, 160, 175, 190};
        for (double bpm : tempos) {
            BeatProfile p = BeatAnalysis.analyse(clicks(bpm, 1d), RATE, 0L);
            assertNotNull(bpm + "BPM click track: no grid", p);
            assertEquals(bpm + "BPM click track", bpm, p.bpm(), 0.5d);
            assertTrue(bpm + "BPM click track: not trustworthy: " + p, p.trustworthy());
        }
    }

    /** A 2:3 relation that is really in the signal: a soft attack two thirds of the way
     *  through every beat. The finer grid is "supported" enough to be seen, and not nearly
     *  enough to be the beat — the finer reading here must lose. */
    @Test
    public void attacksAtTwoThirdsOfABeatDoNotBecomeTheBeat() {
        for (double bpm : new double[]{90, 100, 120}) {
            double[] mono = clicks(bpm, 1d);
            between(mono, bpm, 1d / 3d, 0.35d);
            between(mono, bpm, 2d / 3d, 0.35d);
            BeatProfile p = BeatAnalysis.analyse(mono, RATE, 0L);
            assertNotNull(bpm + "BPM with triplet in-betweens: no grid", p);
            assertEquals(bpm + "BPM with triplet in-betweens", bpm, p.bpm(), 0.5d);
        }
    }

    /** Off-beat eighths, the ordinary dense arrangement. The eighth-note grid is half a
     *  period away; the beat stays the beat. */
    @Test
    public void offBeatEighthsDoNotBecomeTheBeat() {
        for (double bpm : new double[]{90, 128, 145}) {
            double[] mono = clicks(bpm, 1d);
            between(mono, bpm, 0.5d, 0.35d);
            BeatProfile p = BeatAnalysis.analyse(mono, RATE, 0L);
            assertNotNull(bpm + "BPM with off-beat hats: no grid", p);
            assertEquals(bpm + "BPM with off-beat hats", bpm, p.bpm(), 0.5d);
        }
    }

    /** Clicks a second apart and nothing in between: a genuine slow grid, which must stay
     *  the slow grid. (Doubling it would be the check reading "the between-beats are empty"
     *  as "the beats are somewhere else".) */
    @Test
    public void aSparseGridIsNotDoubled() {
        BeatProfile p = BeatAnalysis.analyse(clicks(60d, 1d), RATE, 0L);
        assertNotNull(p);
        assertEquals("60BPM clicks (a second apart, nothing between)", 60d, p.bpm(), 0.5d);
    }

    // --- signals ------------------------------------------------------------

    private static double[] clicks(double bpm, double gain) {
        double[] out = new double[(int) (RATE * SECONDS)];
        addClicks(out, 0d, bpm, gain);
        return out;
    }

    private static void addClicks(double[] out, double fromSec, double bpm, double gain) {
        double periodSec = 60d / bpm;
        double tail = Math.exp(-1d / (0.012d * RATE));      // ~12ms decay
        for (double at = fromSec; at < SECONDS; at += periodSec) {
            int start = (int) Math.round(at * RATE);
            double level = gain;
            for (int i = start; i < out.length && i < start + 400; i++) {
                out[i] += level;
                level *= tail;
            }
        }
    }

    /** A short, quieter attack every {@code fraction} of a beat — the "something between
     *  the beats" the check has to weigh. */
    private static void between(double[] out, double bpm, double fraction, double gain) {
        double periodSec = 60d / bpm;
        double tail = Math.exp(-1d / (0.006d * RATE));
        for (double at = fraction * periodSec; at < SECONDS; at += periodSec) {
            int start = (int) Math.round(at * RATE);
            double level = gain;
            for (int i = start; i < out.length && i < start + 200; i++) {
                out[i] += level;
                level *= tail;
            }
        }
    }
}
