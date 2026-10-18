package dev.t1m3.qplayer.audio;

import org.junit.Test;

import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The key estimator against material whose answer is known.
 *
 * <p>This is the only part of the mix that has ground truth: a C major triad IS
 * C major, and a chroma-distance threshold can be calibrated against material
 * that was written in two known keys. The synthesised signals are additive tones
 * with a full harmonic series (a sawtooth-like spectrum, which is what real
 * instruments and voices actually have — and what makes key finding hard, because
 * a note's third and fifth harmonics put energy on the fifth and the major third
 * as well as on its own pitch class).
 *
 * <p>What is asserted is the accuracy the gate needs, not perfection: the caller
 * only ever acts on an estimate above {@link KeyProfile#MIN_STRENGTH}, and the
 * measured numbers are printed so the thresholds can be argued from them rather
 * than from taste.
 */
public class KeyAnalysisTest {

    /** The rate the Android profiler decimates to, so the estimator faces the
     *  frequency resolution it has on the device (2.7 Hz per bin at 4096 samples). */
    private static final double RATE = 11025d;
    private static final double CHORD_SECONDS = 2d;

    /** The MIDI note a progression's first chord is rooted on: C4. The chords are
     *  voiced above it and the bass two octaves below it, which is where the notes of
     *  a real recording sit (the estimator is only handed 55 Hz and up). */
    private static final int ROOT = 60;

    @Test
    public void findsTheTonicAndModeOfEveryMajorKey() {
        int hits = 0;
        for (int tonic = 0; tonic < 12; tonic++) {
            // I - V - vi - IV, the most common loop in popular music, twice.
            int[] degrees = {0, 7, 9, 5, 0, 7, 9, 5};
            boolean[] minorThird = {false, false, true, false, false, false, true, false};
            double[] audio = progression(ROOT + tonic, degrees, minorThird, true);
            KeyProfile key = KeyAnalysis.analyse(audio, RATE);
            assertNotNull("no key for " + KeyProfile.noteName(tonic) + " major", key);
            boolean ok = key.tonic() == tonic && key.major();
            if (ok) hits++;
            System.out.println(String.format(Locale.US, "major %-2s -> %-16s %s  (strength %.2f)",
                    KeyProfile.noteName(tonic), key.label(),
                    ok ? "ok" : "MISS", key.strength()));
        }
        System.out.println("major keys found: " + hits + "/12");
        assertEquals("every major progression must be found at its own tonic", 12, hits);
    }

    @Test
    public void findsTheTonicOfEveryMinorKeyWhenTheLeadingToneIsThere() {
        // i - V - i - VI with the V major: the raised leading tone it contains is the
        // one note that tells a minor key apart from the major key with the same
        // notes, which is exactly why real minor-key music uses it.
        int hits = 0;
        for (int tonic = 0; tonic < 12; tonic++) {
            int[] degrees = {0, 7, 0, 8, 0, 7, 0, 8};
            boolean[] minorThird = {true, false, true, false, true, false, true, false};
            double[] audio = progression(ROOT + tonic, degrees, minorThird, true);
            KeyProfile key = KeyAnalysis.analyse(audio, RATE);
            assertNotNull("no key for " + KeyProfile.noteName(tonic) + " minor", key);
            boolean ok = key.tonic() == tonic && !key.major();
            if (ok) hits++;
            System.out.println(String.format(Locale.US, "minor %-2s -> %-16s %s  (strength %.2f)",
                    KeyProfile.noteName(tonic), key.label(),
                    ok ? "ok" : "MISS", key.strength()));
        }
        System.out.println("minor keys (leading tone present) found: " + hits + "/12");
        assertEquals("every minor loop with its leading tone must be found", 12, hits);
    }

    /**
     * The honest negative this estimator has, kept as an assertion so it can never be
     * mistaken for an accident: a NATURAL minor loop (i - VI - III - VII) has exactly
     * the notes of its relative major and no leading tone to tell them apart, so any
     * pitch-class profile reads it as that major key. Measured below.
     *
     * <p>Not a defect to fix here — the difference between the two is emphasis, which
     * is what a listener uses and this estimator does not model. What matters for the
     * mix is that both readings are the SAME Camelot number
     * ({@link KeyProfile#camelotCompatible}), so whichever one comes back, the shift
     * chosen from it is the same shift; and the shift is chosen from the profile
     * distance rather than from the name in any case.
     */
    @Test
    public void aNaturalMinorLoopIsReadAsItsRelativeMajor() {
        int relativeMajor = 0;
        for (int tonic = 0; tonic < 12; tonic++) {
            int[] degrees = {0, 8, 3, 10, 0, 8, 3, 10};
            boolean[] minorThird = {true, false, false, false, true, false, false, false};
            double[] audio = progression(ROOT + tonic, degrees, minorThird, true);
            KeyProfile key = KeyAnalysis.analyse(audio, RATE);
            assertNotNull(key);
            boolean asRelativeMajor = key.tonic() == (tonic + 3) % 12 && key.major();
            boolean asTheMinor = key.tonic() == tonic && !key.major();
            System.out.println(String.format(Locale.US, "natural minor %-2s -> %-16s %s",
                    KeyProfile.noteName(tonic), key.label(),
                    asRelativeMajor ? "as its relative major" : asTheMinor ? "as the minor" : "MISS"));
            // Either reading is defendable; being a different key is not.
            assertTrue("wrong key for " + KeyProfile.noteName(tonic) + " minor: " + key,
                    asRelativeMajor || asTheMinor);
            if (asRelativeMajor) relativeMajor++;
        }
        System.out.println("natural minor loops read as their relative major: "
                + relativeMajor + "/12 (same Camelot number either way)");
    }

    @Test
    public void aFourthChordLoopIsReadAsTheKeyItsTonicSuggests() {
        // C - F - G - C: unambiguous, no chord borrowed from the relative minor.
        double[] audio = progression(ROOT, new int[]{0, 5, 7, 0}, new boolean[4], true);
        KeyProfile key = KeyAnalysis.analyse(audio, RATE);
        assertNotNull(key);
        System.out.println("I-IV-V-I in C -> " + key);
        assertEquals(0, key.tonic());
        assertTrue(key.major());
        assertTrue("strength " + key.strength(), key.trustworthy());
    }

    @Test
    public void transposingTheMaterialTransposesTheKey() {
        for (int shift : new int[]{1, 2, 3, 5, 7, 10}) {
            double[] audio = progression(ROOT + shift, new int[]{0, 5, 7, 0}, new boolean[4], true);
            KeyProfile key = KeyAnalysis.analyse(audio, RATE);
            assertNotNull(key);
            System.out.println("I-IV-V-I up " + shift + " semitones -> " + key);
            assertEquals(shift, key.tonic());
        }
    }

    @Test
    public void materialWithNoKeyIsRefused() {
        Random random = new Random(7);
        double[] noise = new double[(int) (RATE * 8d)];
        for (int i = 0; i < noise.length; i++) noise[i] = random.nextGaussian() * 0.3d;
        KeyProfile fromNoise = KeyAnalysis.analyse(noise, RATE);
        System.out.println("white noise -> " + fromNoise);
        assertTrue("noise must not be trusted", fromNoise == null || !fromNoise.trustworthy());

        double[] silence = new double[(int) (RATE * 8d)];
        assertNull(KeyAnalysis.analyse(silence, RATE));

        // A single sine has a pitch but no key: with one pitch class carrying
        // everything, every rotation of a template fits it equally well, so the
        // margin that is the confidence collapses. This is the estimator's most
        // important negative: a test tone is not a tonic.
        double[] sine = new double[(int) (RATE * 8d)];
        for (int i = 0; i < sine.length; i++) {
            sine[i] = 0.6d * Math.sin(2d * Math.PI * 440d * i / RATE);
        }
        KeyProfile fromSine = KeyAnalysis.analyse(sine, RATE);
        System.out.println("440Hz sine -> " + fromSine);
        assertTrue("a single tone must not be trusted as a key",
                fromSine == null || !fromSine.trustworthy());

        // Too little audio to average over.
        assertNull(KeyAnalysis.analyse(new double[(int) (RATE * 1d)], RATE));
    }

    @Test
    public void aBassHeavyMixStillReadsTheKey() {
        // The realistic case: the key is carried by chords, a loud bass plays the
        // root an octave or two down, and a noise burst lands on every offbeat.
        Random random = new Random(11);
        int[] degrees = {0, 5, 7, 0, 0, 5, 7, 0};
        double[] audio = progression(ROOT, degrees, new boolean[8], false);
        int perChord = (int) (RATE * CHORD_SECONDS);
        for (int i = 0; i < audio.length; i++) {
            double t = i / RATE;
            // A kick on every beat, an offbeat hat, and a bass an octave below.
            double beat = t % 0.5d;
            if (beat < 0.06d) audio[i] += 0.7d * Math.sin(2d * Math.PI * 55d * i / RATE);
            if (beat > 0.25d && beat < 0.27d) audio[i] += random.nextGaussian() * 0.25d;
            int chord = Math.min(degrees.length - 1, i / perChord);
            audio[i] += 0.8d * timbre((440d * Math.pow(2d, (ROOT + degrees[chord] - 24 - 69) / 12d) * i / RATE) % 1d);
        }
        KeyProfile key = KeyAnalysis.analyse(audio, RATE);
        System.out.println("bass- and drum-heavy I-IV-V-I in C -> " + key);
        assertNotNull(key);
        assertEquals("the bass and the drums must not move the key off C", 0, key.tonic());
    }

    /**
     * The second condition the gate applies, and the case that forced it: noise with
     * a fixed spectral envelope — a tilt and two formants, which is what speech,
     * applause or a cymbal wash look like to a pitch-class profile. Its chroma still
     * correlates with the templates well enough to name a key (it scored above half
     * this app's real library on the margin alone), but the profile is flat: almost
     * nothing on the winner's own triad.
     *
     * <p>Both halves are asserted, because the point is that neither number refuses
     * it by itself: this material is the reason {@link KeyProfile#MIN_TRIAD} exists
     * beside {@link KeyProfile#MIN_STRENGTH}.
     */
    @Test
    public void materialWithNoTonalConcentrationIsRefusedEvenWhenTheMarginIsHigh() {
        Random random = new Random(5);
        double[] audio = new double[(int) (RATE * 12d)];
        double lp1 = 0d;
        double lp2 = 0d;
        for (int i = 0; i < audio.length; i++) {
            double white = random.nextGaussian() * 0.3d;
            lp1 = lp1 * 0.7d + white * 0.3d;
            lp2 = lp2 * 0.95d + lp1 * 0.05d;
            double env = 0.5d + 0.5d * Math.sin(2d * Math.PI * 3.1d * i / RATE);
            audio[i] = (lp1 * 0.6d + lp2 * 0.8d) * env;
        }
        KeyProfile key = KeyAnalysis.analyse(audio, RATE);
        assertNotNull(key);
        System.out.println(String.format(Locale.US,
                "spectrally shaped noise -> %s  strength %.3f (gate %.2f), triad %.3f (gate %.2f)",
                key.label(), key.strength(), KeyProfile.MIN_STRENGTH, key.triadWeight(),
                KeyProfile.MIN_TRIAD));
        assertTrue("the margin alone does not refuse this; the triad must: " + key.label(),
                key.triadWeight() < KeyProfile.MIN_TRIAD);
        assertTrue("and the gate must be closed: " + key.label(), !key.trustworthy());
    }

    /**
     * The reason the strength is no longer the margin to the plain runner-up, stated
     * as a measurement: a natural-minor loop is read as its relative major (the
     * familiar honest ambiguity) and the strength must not care, because those two
     * readings are the same Camelot number and {@code MixNaturaliser} mixes them
     * either way. Under the old measure this exact material scored in the same band as white
     * noise.
     */
    @Test
    public void theAmbiguityBetweenRelativeKeysDoesNotCostConfidence() {
        double[] audio = progression(ROOT + 9, new int[]{0, 8, 3, 10, 0, 8, 3, 10},
                new boolean[]{true, false, false, false, true, false, false, false}, true);
        KeyProfile key = KeyAnalysis.analyse(audio, RATE);
        assertNotNull(key);
        System.out.println("natural minor loop -> " + key);
        assertTrue("a real key must clear both gates: " + key.label(), key.trustworthy());
        // The margin has to be over a key that is NOT in this neighbourhood, so the
        // mode-ambiguous reading cannot be what it measures. Measured 0.54: the loop's
        // own relative major is the plain runner-up and would have put this in the same
        // band as white noise under the measure this replaced.
        assertTrue("strength " + key.strength(), key.strength() > 0.45f);
    }

    // --- signal construction --------------------------------------------------

    /** One cycle of the timbre every note is played with: a harmonic series to the
     *  eighth partial with amplitude 1/h (a sawtooth, what a bowed string or a
     *  voice actually looks like). Built once and read with linear interpolation
     *  rather than summed per sample — eight sines per note per sample is a minute
     *  of CPU for a test that is about the answer, not the synthesis. */
    private static final int TABLE = 8192;
    private static final double[] TIMBRE = buildTimbre();

    private static double[] buildTimbre() {
        double[] table = new double[TABLE + 1];
        for (int i = 0; i < TABLE; i++) {
            double sum = 0d;
            for (int h = 1; h <= 8; h++) sum += Math.sin(2d * Math.PI * h * i / TABLE) / h;
            table[i] = sum;
        }
        table[TABLE] = table[0];
        return table;
    }

    private static double timbre(double phase) {
        double at = phase * TABLE;
        int i = (int) at;
        double frac = at - i;
        return TIMBRE[i] + (TIMBRE[i + 1] - TIMBRE[i]) * frac;
    }

    /** A loop of triads, one chord per {@link #CHORD_SECONDS}, with the chord's root
     *  doubled an octave down when {@code withBass}. {@code rootMidi} is the MIDI note
     *  of the first chord's root and {@code degrees} are the chords' roots in
     *  semitones from it. */
    private static double[] progression(int rootMidi, int[] degrees, boolean[] minorThird,
                                        boolean withBass) {
        int perChord = (int) (RATE * CHORD_SECONDS);
        double[] out = new double[perChord * degrees.length];
        for (int c = 0; c < degrees.length; c++) {
            int root = rootMidi + degrees[c];
            int[] notes = withBass
                    ? new int[]{root - 24, root, root + (minorThird[c] ? 3 : 4), root + 7}
                    : new int[]{root, root + (minorThird[c] ? 3 : 4), root + 7};
            double[] gains = withBass
                    ? new double[]{0.9d, 1d, 0.8d, 0.7d}
                    : new double[]{1d, 0.8d, 0.7d};
            double[] phase = new double[notes.length];
            double[] step = new double[notes.length];
            for (int k = 0; k < notes.length; k++) {
                step[k] = 440d * Math.pow(2d, (notes[k] - 69) / 12d) / RATE;
            }
            for (int i = 0; i < perChord; i++) {
                double sample = 0d;
                for (int k = 0; k < notes.length; k++) {
                    sample += gains[k] * timbre(phase[k]);
                    phase[k] += step[k];
                    if (phase[k] >= 1d) phase[k] -= 1d;
                }
                // A slow attack and release so the chords do not click.
                double at = (double) i / perChord;
                double envelope = Math.min(1d, Math.min(at / 0.05d, (1d - at) / 0.05d));
                out[c * perChord + i] = sample * 0.12d * Math.max(0d, envelope);
            }
        }
        return out;
    }
}
