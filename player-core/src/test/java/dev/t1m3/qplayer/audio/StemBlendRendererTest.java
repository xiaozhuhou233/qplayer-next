package dev.t1m3.qplayer.audio;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The renderer ({@link StemBlendRenderer}): the all-unity invariant at both ends of a window,
 * the limiter that keeps the sum of two decks inside full scale, the storage divisor, and the
 * outgoing pad's sweep. Pure Java over hand-built stems, so a failure is a statement about the
 * arithmetic rather than about the model.
 *
 * <p>Everything here is what the Phase C acceptance run measures on the device — see
 * AI_HANDOFF §7. The device run is the evidence; this is the regression test that keeps the
 * evidence reproducible.
 */
public class StemBlendRendererTest {

    private static final int RATE = 44100;
    private static final double WINDOW = 15;

    /** Four "stems" whose sum is a plain signal, so the master is known by construction. */
    private static float[][][] stems(float[] mono, float other) {
        int n = mono.length;
        float[][][] out = new float[4][2][n];
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < n; i++) {
                out[0][ch][i] = mono[i] * 0.25f;             // drums
                out[1][ch][i] = mono[i] * 0.25f;             // bass
                out[2][ch][i] = mono[i] * other;             // other
                out[3][ch][i] = mono[i] * (0.5f - other);    // vocals
            }
        }
        return out;
    }

    private static float[] tone(int n, double hz) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = (float) (0.3 * Math.sin(2 * Math.PI * hz * i / RATE));
        return out;
    }

    private static StemGesture.StemHandover plan(float[] vocals, float[] mix, double window) {
        double bar = 2.0;
        double[] bars = StemGesture.barLines(120, 0.0, 4, 0.0, window);
        return StemGesture.planStemHandover(window, bar, bar, bars, vocals, mix,
                Boolean.FALSE, null, StemGesture.CELL_SEC);
    }

    private static void println(String format, Object... args) {
        System.out.println(String.format(Locale.US, format, args));
    }

    /**
     * The invariant the whole gesture rests on: with every curve at unity the four stems sum to
     * the master, so the deck's 8ms changeover between its element and its stems is a splice and
     * not an edit — at the window's start (the outgoing deck) and at its end (the incoming one).
     *
     * <p>Measured with the sweep OFF, because the outgoing pad deliberately passes a 25 Hz
     * high-pass on its way out; with it on, the same number is whatever that filter does to the
     * pad, which the device run reports separately.
     */
    @Test
    public void atUnityTheFourStemsSumToTheMaster() {
        int n = (int) (WINDOW * RATE);
        float[] master = tone(n, 220);
        float[][][] out = stems(master, 0.2f);
        float[][][] in = stems(master, 0.2f);
        float[] vocals = StemGesture.envelopeOf(new float[][]{out[3][0], out[3][1]}, RATE,
                StemGesture.CELL_SEC);
        float[] mix = StemGesture.envelopeOf(new float[][]{master, master}, RATE,
                StemGesture.CELL_SEC);
        StemGesture.StemHandover handover = plan(vocals, mix, WINDOW);
        StemBlendRenderer.Stats stats =
                StemBlendRenderer.render(out, in, RATE, WINDOW, handover, false);

        double firstError = Math.abs(stats.pcm[0][0] - master[0]);
        double lastError = Math.abs(stats.pcm[0][n - 1] - master[n - 1]);
        println("at t=0 the render is %.3e from the outgoing master's own sample; at %.1fs %.3e",
                firstError, WINDOW, lastError);
        assertEquals(0, firstError, 1e-6);
        assertEquals(0, lastError, 1e-6);
        // And with the outgoing pad's sweep on, the difference is the 25 Hz high-pass — the only
        // thing in the whole path that makes unity not exactly the master.
        StemBlendRenderer.Stats swept =
                StemBlendRenderer.render(out, in, RATE, WINDOW, handover, true);
        double sweptError = 0, energy = 0;
        for (int i = 0; i < 512; i++) {
            sweptError += (swept.pcm[0][i] - master[i]) * (swept.pcm[0][i] - master[i]);
            energy += master[i] * master[i];
        }
        double ratio = Math.sqrt(sweptError / energy);
        println("with the 25 Hz pad sweep on, the first 512 samples differ from the master by %.1f dB",
                20 * Math.log10(Math.max(ratio, 1e-12)));
        assertTrue("the sweep is the only difference, and it is small", ratio < 0.05);
    }

    /**
     * The limiter: two masters summed are the one thing in this path that can pass full scale,
     * so the bus shapes what does not fit instead of clipping it.
     */
    @Test
    public void theBusShapesWhatDoesNotFitFullScale() {
        // Below the knee: exactly transparent.
        assertEquals(0.5, StemBlendRenderer.softLimit(0.5), 0);
        assertEquals(-0.9, StemBlendRenderer.softLimit(-0.9), 0);
        assertEquals(StemBlendRenderer.LIMIT_KNEE, StemBlendRenderer.softLimit(StemBlendRenderer.LIMIT_KNEE), 0);
        // Past it: compressed and continuous, never past 1.0, and monotone.
        double previous = StemBlendRenderer.softLimit(StemBlendRenderer.LIMIT_KNEE);
        for (double x = 1.0; x < 6.0; x += 0.25) {
            double y = StemBlendRenderer.softLimit(x);
            // It ASYMPTOTES to full scale rather than stopping short of it: the curve is
            // knee + room*tanh(...), so a heavy blend peaks at exactly 1.0 (0.0 dBFS) and never
            // past it. The device run measured exactly that on a real pair.
            assertTrue("never past full scale", Math.abs(y) <= 1.0 + 1e-12);
            // Non-decreasing rather than strictly increasing: past about 1.5 the curve has
            // saturated onto full scale to double precision, which is the whole point of it.
            assertTrue("never folds back", y >= previous);
            previous = y;
        }
        println("softLimit: 0.95 -> %.4f, 1.0 -> %.4f, 2.0 -> %.4f, 4.0 -> %.4f, 12.0 -> %.4f",
                StemBlendRenderer.softLimit(0.95), StemBlendRenderer.softLimit(1.0),
                StemBlendRenderer.softLimit(2.0), StemBlendRenderer.softLimit(4.0),
                StemBlendRenderer.softLimit(12.0));

        // A render whose sum goes 2.9 dB past full scale comes out inside it, and says by how
        // much it was pulled down.
        int n = (int) (WINDOW * RATE);
        float[] loud = tone(n, 110);
        for (int i = 0; i < n; i++) loud[i] *= 3.0;             // two decks at once, both loud
        float[][][] out = stems(loud, 0.2f);
        float[][][] in = stems(loud, 0.2f);
        float[] vocals = StemGesture.envelopeOf(new float[][]{out[3][0], out[3][1]}, RATE,
                StemGesture.CELL_SEC);
        float[] mix = StemGesture.envelopeOf(new float[][]{loud, loud}, RATE, StemGesture.CELL_SEC);
        StemGesture.StemHandover handover = plan(vocals, mix, WINDOW);
        StemBlendRenderer.Stats stats =
                StemBlendRenderer.render(out, in, RATE, WINDOW, handover, false);
        double peak = 0;
        for (int ch = 0; ch < 2; ch++) for (float v : stats.pcm[ch]) peak = Math.max(peak, Math.abs(v));
        println("a blend whose sum hits %.1f dBFS: %d of %d samples past full scale, the limiter "
                        + "touched %d, most by %.2f dB; peak after %.1f dBFS",
                20 * Math.log10(stats.peakBeforeBus), stats.aboveFullScale, 2 * n,
                stats.limited, stats.maxReductionDb, 20 * Math.log10(peak));
        assertTrue(stats.peakBeforeBus > 1.0);
        assertTrue(stats.aboveFullScale > 0);
        assertTrue(stats.limited > 0);
        assertTrue("the render itself is inside full scale", peak <= 1.0);
        assertTrue(stats.maxReductionDb < 0);
    }

    /**
     * The per-stem peak divisor is storage, not level: it is divided in and multiplied back out,
     * so a window stored under it plays at the same level it was measured at. What it buys is
     * that a stem whose peak is past full scale still fits a sixteen-bit format.
     */
    @Test
    public void thePerStemDivisorIsStorageNotLevel() {
        float[] left = {0.2f, -1.4f, 0.9f};
        float[] right = {0.1f, 1.35f, -0.8f};
        double peak = StemBlendRenderer.peakOf(left, right);
        println("stem peak 1.40 -> divisor %.2f (Folia's peakOf floors it at 1)", peak);
        assertEquals(1.4, peak, 1e-6);
        // Under 1.0 it stays at 1: dividing a quiet stem UP would amplify its noise floor.
        assertEquals(1.0, StemBlendRenderer.peakOf(new float[]{0.1f}, new float[]{-0.2f}), 0);
    }

    /**
     * The curves themselves: the outgoing voice falls across the exit, the incoming voice rises
     * at its own entry, and the two overlap by half a second rather than either stacking for a
     * second or leaving a hole.
     */
    @Test
    public void theTwoVoicesCrossRatherThanStackOrLeaveAHole() {
        int n = (int) (WINDOW * RATE);
        float[] master = tone(n, 330);
        float[][][] out = stems(master, 0.2f);
        float[][][] in = stems(master, 0.2f);
        float[] vocals = StemGesture.envelopeOf(new float[][]{out[3][0], out[3][1]}, RATE,
                StemGesture.CELL_SEC);
        float[] mix = StemGesture.envelopeOf(new float[][]{master, master}, RATE, StemGesture.CELL_SEC);
        StemGesture.StemHandover handover = plan(vocals, mix, WINDOW);
        float[][] outCurves = StemGesture.outgoingCurves(WINDOW, handover);
        float[][] inCurves = StemGesture.incomingCurves(WINDOW, handover);

        // Where the outgoing voice reaches zero, the incoming one is already at unity: the
        // handover is a pass, and there is never a moment with neither.
        double outAtExit = StemGesture.curveAt(outCurves[3], handover.exit.to, WINDOW);
        double inAtExit = StemGesture.curveAt(inCurves[3], handover.exit.to, WINDOW);
        double outAtEntry = StemGesture.curveAt(outCurves[3], handover.vocalIn, WINDOW);
        double inAtEntry = StemGesture.curveAt(inCurves[3], handover.vocalIn, WINDOW);
        println("at the incoming voice's entry (%.2fs): outgoing %.3f, incoming %.3f; "
                        + "at the outgoing exit's end (%.2fs): outgoing %.3f, incoming %.3f; gap %+.3fs",
                handover.vocalIn, outAtEntry, inAtEntry,
                handover.exit.to, outAtExit, inAtExit, handover.vocalGap());
        assertTrue("no hole: something is always audible", inAtEntry > 0);
        assertTrue(outAtExit < 0.05);
        assertTrue(handover.vocalGap() <= 0 && handover.vocalGap() >= -StemGesture.CUT_SEC - 1e-6);
        // Both decks' vocals are audible together for at most the cut (0.5s), never the whole
        // window: the sum of the two curves never approaches 2.
        double worst = 0;
        for (double t = 0; t <= WINDOW; t += 0.01) {
            worst = Math.max(worst, StemGesture.curveAt(outCurves[3], t, WINDOW)
                    + StemGesture.curveAt(inCurves[3], t, WINDOW));
        }
        println("the two vocal faders sum to at most %.3f (1.0 would be a plain linear crossfade, "
                + "2.0 both at unity)", worst);
        assertTrue(worst < 1.6);
    }
}
