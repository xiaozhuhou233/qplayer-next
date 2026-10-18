package dev.t1m3.qplayer.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

/**
 * The cloud bed ({@link StemBed}) as arithmetic: the level it lands at, the fact that both of its
 * ends are exactly zero, the two opposite failures it refuses — a few hits over near-silence, which
 * is the one a listener actually heard, and a flat near-constant wash — and the limiter it is
 * bounded by when the model's material is peakier than the design assumes.
 *
 * <p>Pure Java on deterministic synthetic material — a fixed-seed pulse train over a continuous bed
 * for "music", a fixed-seed pulse train over near-silence for the few hits the listener heard, and a
 * fixed-seed near-constant noise for the model's other real failure — so every number here is a
 * statement about the decision rather than about a model run. What the cloud's material really
 * measures is a device measurement; this file is the part that has to hold whatever it returns.
 */
public class StemBedTest {

    private static final int RATE = 44_100;

    /** The bed under the events in {@link #music}, as a share of the events' peak. The two land
     *  about 8 dB apart, and that is the number both clauses can meet: see {@link #music}. */
    private static final double MUSIC_BED_SHARE = 0.15d;

    private static void println(String format, Object... args) {
        System.out.println(String.format(Locale.US, format, args));
    }

    /** {@code assertTrue(why, condition)} in the order this file reads it. */
    private static void ok(boolean condition, String why) {
        assertTrue(why, condition);
    }

    /**
     * A deterministic stand-in for music: a continuous bed at {@link #MUSIC_BED_SHARE} of the
     * events' peak, with a burst whose own envelope decays on every beat and a low noise floor
     * under both — events with a body between them, which is what real music measures 84–100%
     * occupied as.
     *
     * <p>⚠️ The ~8 dB between the bed and the events is load-bearing, and it is why the bed is here
     * at all. The body level is the median of the envelope, and the frames split evenly between the
     * two (a 120 ms burst every beat against {@link StemBed#ENVELOPE_MS} frames is one frame each),
     * so the body falls midway between them: every frame is then within
     * {@link StemBed#OCCUPANCY_BAND_DB} dB of the body, and the spread is over
     * {@link StemBed#MIN_BED_SPREAD_DB}. Measured: 8.0 dB of spread and 100% occupancy, on every
     * window these tests use. Move the bed down and the events stand too far over it for the frames
     * between to be occupied; move it up and there is no spread left to measure.
     */
    private static float[][] music(double seconds, double beatSec, double peak, double floor) {
        int frames = (int) Math.round(seconds * RATE);
        int period = (int) Math.round(beatSec * RATE);
        int burst = (int) Math.round(0.12d * RATE);
        float[][] out = new float[2][frames];
        for (int ch = 0; ch < 2; ch++) {
            java.util.Random random = new java.util.Random(20260924L + ch);
            for (int i = 0; i < frames; i++) {
                out[ch][i] = (float) (floor * (random.nextDouble() * 2d - 1d)
                        + MUSIC_BED_SHARE * peak * (random.nextDouble() * 2d - 1d));
            }
            for (int at = 0; at + burst < frames; at += period) {
                for (int i = 0; i < burst; i++) {
                    double env = Math.exp(-3d * i / (double) burst);
                    out[ch][at + i] +=
                            (float) (peak * env * Math.sin(2d * Math.PI * 220d * i / RATE));
                }
            }
        }
        return out;
    }

    /**
     * The failure the listener heard, as material: a few bursts over a wide-band near-silence floor
     * with nothing between them. That is the shape that <b>maximises</b> peak-to-trough range — 24.8
     * dB here, over the 12 dB floor this file read against when the material was accepted and over
     * the 6 dB one it reads against now — which is why the spread clause is the clause a scattering
     * of hits passes most easily, and why the material under it could read as "only a few drum hits,
     * a bit of a cop-out".
     */
    private static float[][] sparse(double seconds, double beatSec, double peak, double floor) {
        int frames = (int) Math.round(seconds * RATE);
        int period = (int) Math.round(beatSec * RATE);
        int burst = (int) Math.round(0.12d * RATE);
        float[][] out = new float[2][frames];
        for (int ch = 0; ch < 2; ch++) {
            java.util.Random random = new java.util.Random(20260924L + ch);
            for (int i = 0; i < frames; i++) {
                out[ch][i] = (float) (floor * (random.nextDouble() * 2d - 1d));
            }
            for (int at = 0; at + burst < frames; at += period) {
                for (int i = 0; i < burst; i++) {
                    double env = Math.exp(-3d * i / (double) burst);
                    out[ch][at + i] +=
                            (float) (peak * env * Math.sin(2d * Math.PI * 220d * i / RATE));
                }
            }
        }
        return out;
    }

    /**
     * The model's failure modelled: eight seconds of near-constant −55 dBFS noise with a slow
     * drift, the flat drone that has to be refused. The real run measured 6.8 dB of envelope range
     * against a 6 dB floor — the two are 0.8 dB apart, and the occupancy clause cannot back the
     * spread clause up here (a flat drone is 100% occupied) — while this stand-in measures 2.0 dB,
     * with its drift as the whole of its range.
     */
    private static float[][] wash(double seconds) {
        int frames = (int) Math.round(seconds * RATE);
        double amplitude = Math.pow(10d, -55d / 20d);
        float[][] out = new float[2][frames];
        for (int ch = 0; ch < 2; ch++) {
            java.util.Random random = new java.util.Random(55L + ch);
            for (int i = 0; i < frames; i++) {
                double drift = 1d + 0.12d * Math.sin(2d * Math.PI * 0.7d * i / RATE);
                out[ch][i] = (float) (amplitude * drift * (random.nextDouble() * 2d - 1d));
            }
        }
        return out;
    }

    /** A low noise floor with a few abrupt near-full-scale bursts: material whose normalisation to
     *  the target level is past full scale, i.e. what the limiter is there for.
     *
     *  <p>Sparse in <i>events</i> and dense by the occupancy — the floor holds 14 of the 16 frames
     *  and the body level is read from it, not from the two bursts — which is the pair of properties
     *  the two clauses have to keep apart, and the reason {@link StemBed#OCCUPANCY_BAND_DB} is read
     *  one-sided: this material's peak stands 35 dB over its own RMS, so its event frames are far
     *  above its body, and a band read the other way would refuse it for its own events. Its floor
     *  is 26.4 dB under the bursts of {@link StemBed#BODY_WINDOW_DB}'s 30 dB, which is the whole of
     *  this material's margin and a measurement, not a coin flip: the material is fixed-seed. */
    private static float[][] spiky(double seconds, double everySec, double floor) {
        int frames = (int) Math.round(seconds * RATE);
        int period = (int) Math.round(everySec * RATE);
        int burst = (int) Math.round(0.003d * RATE);
        float[][] out = new float[2][frames];
        for (int ch = 0; ch < 2; ch++) {
            java.util.Random random = new java.util.Random(7L + ch);
            for (int i = 0; i < frames; i++) {
                out[ch][i] = (float) (floor * (random.nextDouble() * 2d - 1d));
            }
            for (int at = 0; at + burst < frames; at += period) {
                for (int i = 0; i < burst; i++) {
                    out[ch][at + i] += (float) (0.95d * (random.nextDouble() * 2d - 1d));
                }
            }
        }
        return out;
    }

    /** The same material at another level, the way the cloud hands it over. */
    private static float[][] scaled(float[][] material, double db) {
        double gain = Math.pow(10d, db / 20d);
        float[][] out = new float[material.length][];
        for (int ch = 0; ch < material.length; ch++) {
            out[ch] = new float[material[ch].length];
            for (int i = 0; i < out[ch].length; i++) {
                out[ch][i] = (float) (gain * material[ch][i]);
            }
        }
        return out;
    }

    /** The RMS of the bed's held part, dBFS, measured here rather than asked of the class. */
    private static double heldDb(float[][][] bed, int fadeFrames) {
        int frames = bed[0][0].length;
        double energy = 0d;
        int samples = 0;
        for (float[] row : bed[0]) {
            for (int i = fadeFrames; i < frames - fadeFrames; i++) {
                energy += row[i] * (double) row[i];
                samples++;
            }
        }
        return 20d * Math.log10(Math.sqrt(energy / samples));
    }

    /** The loudest sample of a bed, absolute. */
    private static double peak(float[][][] bed) {
        double peak = 0d;
        for (float[] row : bed[0]) {
            for (float v : row) peak = Math.max(peak, Math.abs(v));
        }
        return peak;
    }

    // --- the bed itself ------------------------------------------------------

    @Test
    public void theBedSitsUnderThePassageAndIsZeroAtBothSeams() {
        float[][] material = music(8d, 0.5d, 0.5d, 0.01d);
        double levelDb = -6d;
        long windowMs = 4_000L;
        long barMs = 500L;
        StemBed.Bed bed = StemBed.prepare(material, RATE, windowMs, barMs, levelDb);
        println("%s", bed.note);
        ok(bed.usable, bed.note);
        assertNotNull(bed.pcm);

        int frames = (int) Math.round(RATE * windowMs / 1000d);
        int fadeFrames = (int) Math.round(RATE * StemBed.BED_FADE_BARS * (double) barMs / 1000d);
        assertEquals(frames, bed.frames);
        assertEquals(fadeFrames, bed.fadeFrames);
        assertEquals(1, bed.pcm.length);
        assertEquals(2, bed.pcm[0].length);
        assertEquals(frames, bed.pcm[0][0].length);
        assertEquals(frames, bed.pcm[0][1].length);

        // the held part is the passage's own level minus the bed's offset — measured here, and
        // reported by the class
        double held = heldDb(bed.pcm, fadeFrames);
        println("the bed's held part: %.2f dBFS (the class reports %.2f, the target is %.2f)",
                held, bed.bedLevelDb, levelDb - StemBed.BED_LEVEL_DB);
        assertEquals(levelDb - StemBed.BED_LEVEL_DB, held, 0.5d);
        assertEquals(levelDb - StemBed.BED_LEVEL_DB, bed.bedLevelDb, 0.5d);

        // exactly zero at both seams, both channels — the reason the design works
        assertEquals(0.0f, bed.pcm[0][0][0], 0.0f);
        assertEquals(0.0f, bed.pcm[0][1][0], 0.0f);
        assertEquals(0.0f, bed.pcm[0][0][frames - 1], 0.0f);
        assertEquals(0.0f, bed.pcm[0][1][frames - 1], 0.0f);

        // at this level nothing has to be bounded
        assertFalse(bed.note, bed.limited);
        assertEquals(0, bed.clippedPairs);
        assertTrue("the bed's own peak is over full scale: " + bed.unboundedPeak,
                bed.unboundedPeak <= DjEdit.Limiter.CEILING);
        assertTrue(peak(bed.pcm) <= DjEdit.Limiter.CEILING);

        println("the envelope spread measured %.1f dB over the material (the floor is %.0f)",
                bed.spreadDb, StemBed.MIN_BED_SPREAD_DB);
        assertTrue(bed.spreadDb >= StemBed.MIN_BED_SPREAD_DB);

        // and the other reading, over the same frames: dense material has a body, so nearly all of
        // them are near its body level rather than a few hits with near-silence between them
        println("the occupancy measured %.1f%% over the material (the floor is %.0f%%, and the"
                        + " material's own body level is %.1f dBFS)",
                bed.occupancy * 100d, StemBed.MIN_BED_OCCUPANCY * 100d, bed.bodyDb);
        assertTrue("the occupancy is " + bed.occupancy + ", under the "
                        + StemBed.MIN_BED_OCCUPANCY + " floor — this material is supposed to be the"
                        + " dense kind", bed.occupancy >= StemBed.MIN_BED_OCCUPANCY);

        // the note carries the two numbers the caller logs: the spread and the gain applied
        assertTrue(bed.note, bed.note.contains("envelope spread"));
        assertTrue(bed.note, bed.note.contains(String.format(Locale.US, "%+.1f dB", bed.gainDb)));
    }

    // --- the level the cloud handed over is irrelevant ------------------------

    @Test
    public void theSameMaterialMakesTheSameBedAtAnyInputLevel() {
        float[][] base = music(8d, 0.5d, 0.5d, 0.01d);
        StemBed.Bed quiet = StemBed.prepare(scaled(base, -30d), RATE, 4_000L, 500L, -8d);
        StemBed.Bed loud = StemBed.prepare(scaled(base, 30d), RATE, 4_000L, 500L, -8d);
        println("30 dB down: %s", quiet.note);
        println("30 dB up:   %s", loud.note);
        ok(quiet.usable, quiet.note);
        ok(loud.usable, loud.note);
        assertFalse(quiet.note, quiet.limited);
        assertFalse(loud.note, loud.limited);

        // the two gains are 60 dB apart, which is the point: neither the bed nor its level moves
        println("the gains applied: %+.1f dB and %+.1f dB", quiet.gainDb, loud.gainDb);
        assertEquals(60d, quiet.gainDb - loud.gainDb, 0.1d);
        assertEquals(quiet.bedLevelDb, loud.bedLevelDb, 0.5d);
        assertEquals(60d, loud.materialDb - quiet.materialDb, 0.1d);

        int frames = quiet.pcm[0][0].length;
        double worst = 0d;
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < frames; i++) {
                worst = Math.max(worst, Math.abs(quiet.pcm[0][ch][i] - loud.pcm[0][ch][i]));
            }
        }
        println("the two beds differ by at most %.3e", worst);
        assertTrue("the two beds differ by " + worst, worst < 1e-4d);
    }

    // --- what is refused ------------------------------------------------------

    @Test
    public void aFlatWashIsRefusedByTheSpreadClause() {
        StemBed.Bed bed = StemBed.prepare(wash(8d), RATE, 4_000L, 500L, -6d);
        println("%s", bed.note);
        assertFalse(bed.note, bed.usable);
        assertNull(bed.pcm);
        println("the wash measured %.1f dB of envelope spread (the floor is %.0f, the real"
                + " run measured 6.8)", bed.spreadDb, StemBed.MIN_BED_SPREAD_DB);
        assertTrue(bed.spreadDb < StemBed.MIN_BED_SPREAD_DB);
        assertTrue("the wash is flatter than the real failure: " + bed.spreadDb, bed.spreadDb < 8d);
        assertTrue(bed.note, bed.note.contains("envelope spread"));
    }

    /**
     * ⚠️ <b>The regression test for the clause that was backwards.</b> A few hits over near-silence
     * is the material a listener actually heard and called "only a few drum hits, a bit of a
     * cop-out" — and it is the material the spread floor accepted, because a few hits over
     * near-silence is the widest envelope range there is. So this asserts both halves: refused, by
     * the occupancy clause, <i>with its spread over the floor the old reading judged it on</i>. That
     * second assertion is the point of the test: without it, a future reader could think the spread
     * clause refused this material, which is exactly the mistake the clause made.
     */
    @Test
    public void aFewHitsOverNearSilenceIsRefusedByTheOccupancyClause() {
        StemBed.Bed bed = StemBed.prepare(sparse(8d, 0.5d, 0.5d, 0.01d), RATE, 4_000L, 500L, -6d);
        println("%s", bed.note);
        assertFalse(bed.note, bed.usable);
        assertNull(bed.pcm);

        // the refusal names its clause
        assertTrue(bed.note, bed.note.contains("occupancy"));
        assertTrue(bed.note, bed.note.contains("body level"));

        println("the sparse material measured %.1f%% occupancy (the floor is %.0f%%) over a %.1f dB"
                        + " envelope spread (the floor is %.0f) — the listener's own material"
                        + " measured 52.3%% over 26.9 dB",
                bed.occupancy * 100d, StemBed.MIN_BED_OCCUPANCY * 100d, bed.spreadDb,
                StemBed.MIN_BED_SPREAD_DB);
        // about half the span has material in it: one 120 ms burst per beat, and nothing between
        assertEquals(0.5d, bed.occupancy, 0.05d);
        assertTrue("the occupancy is " + bed.occupancy,
                bed.occupancy < StemBed.MIN_BED_OCCUPANCY);

        // ⚠️ THE REGRESSION: the spread clause alone would have ACCEPTED it, and not marginally —
        // the sparsest material is what measures the widest range, so range is the wrong reading for
        // this failure, and the higher the floor the more sparseness it lets through.
        assertTrue("the sparse material's spread is " + bed.spreadDb + " dB, over the "
                        + StemBed.MIN_BED_SPREAD_DB + " dB floor, so the spread clause alone would"
                        + " have accepted it — which is the bug this test exists for",
                bed.spreadDb > StemBed.MIN_BED_SPREAD_DB);
        assertTrue("the material is sparser than the listener's own (26.9 dB): " + bed.spreadDb,
                bed.spreadDb > 20d);
    }

    @Test
    public void silenceAndShortMaterialAreRefused() {
        float[][] zeros = new float[2][(int) Math.round(4d * RATE)];
        StemBed.Bed silent = StemBed.prepare(zeros, RATE, 4_000L, 500L, -6d);
        println("%s", silent.note);
        assertFalse(silent.note, silent.usable);
        assertNull(silent.pcm);
        assertTrue(silent.note, silent.note.contains("all zero"));

        // material shorter than the window needs
        StemBed.Bed short_ = StemBed.prepare(music(1d, 0.5d, 0.5d, 0.01d), RATE, 4_000L, 500L, -6d);
        println("%s", short_.note);
        assertFalse(short_.note, short_.usable);
        assertNull(short_.pcm);
        assertTrue(short_.note, short_.note.contains("shorter than"));

        StemBed.Bed empty = StemBed.prepare(new float[0][], RATE, 4_000L, 500L, -6d);
        println("%s", empty.note);
        assertFalse(empty.usable);
        assertTrue(empty.note, empty.note.contains("empty"));
    }

    @Test
    public void aShortWindowGetsAShorterFadeAndTheHoldIsNeverNegative() {
        float[][] material = music(8d, 0.5d, 0.5d, 0.01d);

        // The wanted fade is one bar (1 s here) and the cap is a third of the window, so on a
        // 2.5 s window the cap is what binds: 833 ms of fade, and the same again of hold.
        long roomyMs = 2_500L;
        int roomyFrames = (int) Math.round(RATE * (roomyMs / 1000d));
        StemBed.Bed roomy = StemBed.prepare(material, RATE, roomyMs, 1_000L, -6d);
        println("%s", roomy.note);
        ok(roomy.usable, roomy.note);
        assertEquals(roomyFrames / 3, roomy.fadeFrames);
        assertTrue("the wanted 1 s fade was not what bound here", roomy.fadeFrames
                < Math.round(RATE * 1.0d));

        // ⚠️ The real case, measured: a slam's window is about ONE bar, so a bar of fade at each
        // end wants twice the window. The fade is shortened instead of the bed being refused --
        // most of this library is slam, and "exactly zero at both ends with a hold between" is the
        // property that matters, not the bar count.
        StemBed.Bed capped = StemBed.prepare(material, RATE, 2_124L, 2_044L, -6d);
        println("%s", capped.note);
        ok(capped.usable, capped.note);
        assertTrue("the fade must be capped below the wanted one bar, was " + capped.fadeFrames,
                capped.fadeFrames < Math.round(RATE * 2.044d));

        // and the invariant the cap exists for: a strictly positive hold, always
        for (long windowMs : new long[] {2_124L, 1_500L, 600L, 200L}) {
            StemBed.Bed bed = StemBed.prepare(music(8d, 0.5d, 0.5d, 0.01d), RATE, windowMs, 2_044L, -6d);
            if (!bed.usable) continue;
            int hold = bed.frames - 2 * bed.fadeFrames;
            assertTrue(String.format("window %d ms: hold %d frames, fade %d", windowMs, hold,
                    bed.fadeFrames), hold > 0);
            assertEquals(0d, StemBed.envelope(0, bed.frames, bed.fadeFrames), 0d);
            assertEquals(0d, StemBed.envelope(bed.frames - 1, bed.frames, bed.fadeFrames), 0d);
        }

        // a window with no room for even a 50 ms fade at each end is refused
        StemBed.Bed tooShort = StemBed.prepare(material, RATE, 100L, 2_044L, -6d);
        println("%s", tooShort.note);
        assertFalse(tooShort.note, tooShort.usable);
        assertNull(tooShort.pcm);
        assertTrue(tooShort.note, tooShort.note.contains("too short"));

        // a bar that rounds to no fade at all cannot give the bed its zero ends
        StemBed.Bed noFade = StemBed.prepare(material, RATE, 4_000L, 0L, -6d);
        println("%s", noFade.note);
        assertFalse(noFade.note, noFade.usable);
        assertTrue(noFade.note, noFade.note.contains("fade"));
    }

    // --- past full scale -------------------------------------------------------

    @Test
    public void aBedThatWouldClipIsLimitedRatherThanRefused() {
        StemBed.Bed bed = StemBed.prepare(spiky(8d, 2d, 0.005d), RATE, 4_000L, 500L, -6d);
        println("%s", bed.note);
        ok(bed.usable, bed.note);
        assertNotNull(bed.pcm);

        // the normalised bed's own peak is past full scale...
        println("the bed's own peak before the limiter: %.2f", bed.unboundedPeak);
        assertTrue("the peak was " + bed.unboundedPeak,
                bed.unboundedPeak > DjEdit.Limiter.CEILING);
        assertTrue(bed.limited);

        // ...and nothing got out: the limiter and its clamp bounded it, at the price of the samples
        // the 1 ms attack could not catch — which the note states
        double output = peak(bed.pcm);
        println("the bed's peak after the limiter: %.4f (clamped pairs %d of %d)",
                output, bed.clippedPairs, 2 * bed.frames);
        assertTrue("the peak came out at " + output, output <= DjEdit.Limiter.CEILING);
        assertTrue(bed.note, bed.note.contains("limiter"));
        assertTrue("the note does not state the clipping: " + bed.note, bed.clippedPairs > 0);
        assertTrue(bed.note, bed.note.contains("clamped"));
    }

    // --- the envelope, which is why the seams are safe -------------------------

    @Test
    public void theEnvelopeIsTwoBarsAtEachEndAndZeroAtTheSeams() {
        int frames = 176_400;      // 4 s at 44.1 kHz
        // Ⓐ Round 30: the listener asked for the bed's ending to be a fade-out (and its start a
        // fade-in), so the wanted envelope is BED_FADE_BARS bars and not one. Derived from the
        // constant rather than written out, because what this fixture is about is the SHAPE
        // (zero at both seams, monotone, symmetric) and the length is the constant's business.
        int fadeFrames = (int) Math.round(RATE * StemBed.BED_FADE_BARS * 500d / 1000d);
        assertEquals(2 * 500d, 1000d * fadeFrames / RATE, 0.05d);

        // exactly zero at the first and the last sample, and rising immediately after each
        assertEquals(0d, StemBed.envelope(0, frames, fadeFrames), 0d);
        assertEquals(0d, StemBed.envelope(frames - 1, frames, fadeFrames), 0d);
        assertTrue(StemBed.envelope(1, frames, fadeFrames) > 0d);
        assertTrue(StemBed.envelope(frames - 2, frames, fadeFrames) > 0d);

        // the hold is the whole middle, at exactly unity
        for (int i = fadeFrames; i <= frames - 1 - fadeFrames; i++) {
            assertEquals(1d, StemBed.envelope(i, frames, fadeFrames), 0d);
        }
        // the two fades are monotone, inside (0, 1), and symmetric about the window
        for (int i = 1; i < fadeFrames; i++) {
            double at = StemBed.envelope(i, frames, fadeFrames);
            assertTrue(at >= StemBed.envelope(i - 1, frames, fadeFrames));
            assertTrue(at < 1d);
        }
        for (int i = 0; i < fadeFrames; i++) {
            assertEquals(StemBed.envelope(i, frames, fadeFrames),
                    StemBed.envelope(frames - 1 - i, frames, fadeFrames), 1e-12d);
        }

        // and that is the envelope the bed was actually made with
        StemBed.Bed bed = StemBed.prepare(music(8d, 0.5d, 0.5d, 0.01d), RATE, 4_000L, 500L, -6d);
        ok(bed.usable, bed.note);
        assertEquals(frames, bed.frames);
        assertEquals(fadeFrames, bed.fadeFrames);
    }
}
