package dev.t1m3.qplayer.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * The bridge's arithmetic, and the acceptance the round is measured against: the two tracks'
 * contributions present at the designed times, no vocals, one copy of the carried material (no
 * comb), and a pulse with no hole in it — all on known material, which is the whole reason this
 * is testable at all (see {@link StemBridge}'s note on the earlier round's negative result).
 */
public class StemBridgeTest {

    private static final int RATE = 44_100;

    private static void println(String format, Object... args) {
        System.out.println(String.format(java.util.Locale.US, format, args));
    }

    /** {@code assertTrue(why, condition)} in the order this file reads it. */
    private static void ok(boolean condition, String why) {
        assertTrue(why, condition);
    }

    private static void ok(boolean condition) {
        assertTrue(condition);
    }

    /** {@code assertFalse(why, condition)} in the order this file reads it. */
    private static void bad(boolean condition, String why) {
        assertFalse(why, condition);
    }

    private static void bad(boolean condition) {
        assertFalse(condition);
    }

    /** A synthetic "bass" stem: a decaying sine burst at every beat, silence between them. */
    private static float[][] bassline(double beatSec, double windowSec, double hz, double amp) {
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

    /** A synthetic pad: quiet noise-free tone, the incoming head's own material. */
    private static float[][] pad(double windowSec, double hz, double amp) {
        int frames = (int) Math.round(windowSec * RATE);
        float[][] out = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            float v = (float) (amp * Math.sin(2 * Math.PI * hz * i / RATE));
            out[0][i] = v;
            out[1][i] = v;
        }
        return out;
    }

    private static double[] bars(double barSec, double windowSec) {
        java.util.ArrayList<Double> lines = new java.util.ArrayList<>();
        for (double at = 0d; at <= windowSec; at += barSec) lines.add(at);
        double[] out = new double[lines.size()];
        for (int i = 0; i < out.length; i++) out[i] = lines.get(i);
        return out;
    }

    @Test
    public void theBridgeStartsOnTheFirstBarLineAfterTheHandOverWouldHaveHappened() {
        double beatSec = 0.5d;
        double barSec = beatSec * StemBridge.BEATS_PER_BAR;
        double removal = 20d;
        double window = 23d;
        StemBridge.Plan plan = StemBridge.plan(bars(barSec, window), barSec, removal, window);
        ok(plan.fits, plan.reason);
        ok(plan.onBarLine);
        // 0.15 of 20s is 3s; with 2s bars the first line at or after it is 4s.
        assertEquals(4d, plan.startSec, 1e-9);
        assertEquals(2 * barSec, plan.lengthSec(), 1e-9);
        assertEquals(plan.startSec, plan.swapSec(), 1e-9);
        ok(plan.startSec >= removal * StemBridge.SWAP_AT - 1e-9,
                "the bridge may never start before the hand-over's own fraction of the blend");
    }

    @Test
    public void aBridgeThatWouldRunPastTheVocalRemovalIsRefused() {
        double beatSec = 0.5d;
        double barSec = beatSec * StemBridge.BEATS_PER_BAR;
        // ⚠️ Round 17: the floor is 0.15 of the removal window now, so the case that does not
        // fit is a SHORT removal window rather than a long one: with 2s bars, a 5s window puts
        // the first line at or after the floor (0.75s) at 2s and the two-bar bridge would end at
        // 6s — inside the incoming's vocals.
        StemBridge.Plan plan = StemBridge.plan(bars(barSec, 30d), barSec, 5d, 30d);
        bad(plan.fits);
        ok(plan.reason.contains("run past"), plan.reason);
    }

    @Test
    public void aBridgeWithNoGridStillLandsOnThePlainFraction() {
        StemBridge.Plan plan = StemBridge.plan(null, 2d, 20d, 25d);
        ok(plan.fits, plan.reason);
        bad(plan.onBarLine);
        assertEquals(20d * StemBridge.SWAP_AT, plan.startSec, 1e-9);
    }

    @Test
    public void theCarriedLayerKeepsTheSourceLevelAndFillsTheWholeWindow() {
        double beatSec = 0.5d;
        double barSec = beatSec * 4;
        double removal = 20d;
        double headSec = 23d;
        float[][] tail = bassline(beatSec, 12d, 55d, 0.5d);
        StemBridge.Plan plan = StemBridge.plan(bars(barSec, headSec), barSec, removal, headSec);
        ok(plan.fits, plan.reason);
        double fromSec = StemBridge.sourceFromSec(bars(barSec, 12d), barSec);
        float[][] layer = StemBridge.layer(tail, RATE, plan, 1d, fromSec);
        assertNotNull(layer);
        assertEquals((int) Math.round(plan.lengthSec() * RATE), layer[0].length);
        double carried = StemBridge.levelDb(layer, RATE, plan.lengthSec());
        double source = StemBridge.levelDb(tail, RATE, plan.lengthSec());
        ok(carried > source - StemBridge.CARRY_TOLERANCE_DB,
                "carried " + carried + " dBFS vs source " + source + " dBFS");
    }

    @Test
    public void anUnstretchedCarryIsSampleExact() {
        double beatSec = 0.5d;
        float[][] tail = bassline(beatSec, 10d, 60d, 0.4d);
        StemBridge.Plan plan = StemBridge.plan(bars(2d, 20d), 2d, 20d, 22d);
        double fromSec = 4d;
        float[][] layer = StemBridge.layer(tail, RATE, plan, 1d, fromSec);
        assertNotNull(layer);
        int from = (int) Math.round(fromSec * RATE);
        for (int i = 0; i < layer[0].length; i++) {
            assertEquals(tail[0][from + i], layer[0][i], 1e-7f);
        }
    }

    @Test
    public void aStretchedCarryIsHeardAtTheOutgoingTracksOwnTempo() {
        // 0.5s beats, a 6% stretch: the carried material must still put the same number of
        // beats into the same span of heard time, which is what "carried forward" means.
        double beatSec = 0.5d;
        float[][] tail = bassline(beatSec, 12d, 60d, 0.4d);
        double speed = 1.06d;
        StemBridge.Plan plan = StemBridge.plan(bars(2d, 20d), 2d, 20d, 22d);
        double fromSec = 2d;
        float[][] layer = StemBridge.layer(tail, RATE, plan, speed, fromSec);
        assertNotNull(layer);
        int sourceBeats = 0;
        int carriedBeats = 0;
        sourceBeats = countBursts(tail[0], (int) Math.round(fromSec * RATE), layer[0].length);
        carriedBeats = countBursts(layer[0], 0, layer[0].length);
        ok(Math.abs(sourceBeats - carriedBeats) <= 1,
                "source beats " + sourceBeats + " vs carried " + carriedBeats);
        // And the spacing says the same thing exactly: in the FILE the carried beats are a
        // beat-of-the-outgoing times the speed apart (the second burst, which the source puts at
        // 0.5s, lands at 0.53s), so the deck playing the file at that speed hands them back at the
        // outgoing track's own 0.5s. The opposite sense would leave them at 0.472s apart, which is
        // the speed² the round-17 defect played at.
        assertEquals(beatSec * speed, secondBurstAtSec(layer[0]), 0.02d);
    }

    /** The same stems with one signal LENGTHENED by a ratio: the direction
     *  {@link StemBridge#stretch} is documented in, and the one the deck's own playback undoes. */
    @Test
    public void theCarryIsLengthenedByTheSpeedNotShortenedByIt() {
        double beatSec = 0.5d;
        double speed = 1.06d;
        float[][] tail = bassline(beatSec, 12d, 60d, 0.4d);
        float[][] stretched = StemBridge.stretch(tail, 0, RATE, speed);
        // One second of source comes back as 1.06 s, with its beats 6% further apart: lengthened,
        // which is what the doc says and what the fusion's own carry does.
        assertEquals((int) Math.floor(RATE * speed), stretched[0].length);
        assertEquals(beatSec * speed, secondBurstAtSec(stretched[0]), 0.02d);
        assertEquals(RATE, StemBridge.stretch(tail, 0, RATE, 1d)[0].length);
        // The fusion's carry and the bridge's stretch are the same arithmetic: the same source
        // read at the same ratio gives the same length (see StemFusion.carried).
        float[][] fused = StemFusion.carried(tail, 0, RATE, speed, (int) Math.round(RATE * speed));
        assertEquals(stretched[0].length, fused[0].length);
        for (int i = 0; i < Math.min(2000, fused[0].length); i++) {
            assertEquals(stretched[0][i], fused[0][i], 1e-7f);
        }
    }

    /** The onset of the second decaying burst in a signal, seconds — where the material's own beat
     *  is, as the file holds it. */
    private static double secondBurstAtSec(float[] pcm) {
        int frame = (int) Math.round(0.01d * RATE);
        int seen = 0;
        boolean quiet = true;
        for (int i = 0; i + frame < pcm.length; i += frame) {
            double peak = 0;
            for (int j = 0; j < frame; j++) peak = Math.max(peak, Math.abs(pcm[i + j]));
            if (peak > 0.25d && quiet) {
                seen++;
                if (seen == 2) return i / (double) RATE;
            }
            quiet = peak <= 0.02d;
        }
        return -1d;
    }

    /** How many bursts start in a slice: a frame above a quarter of full scale, after silence. */
    private static int countBursts(float[] pcm, int from, int length) {
        int frame = (int) Math.round(0.01d * RATE);
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
    public void aBridgeOfCarriedBassUnderTheIncomingHeadIsAcceptable() {
        double beatSec = 0.5d;
        double barSec = beatSec * 4;
        double windowSec = 22d;
        float[][] tail = bassline(beatSec, 12d, 55d, 0.5d);
        StemBridge.Plan plan = StemBridge.plan(bars(barSec, windowSec), barSec, 20d, windowSec);
        ok(plan.fits, plan.reason);
        double fromSec = StemBridge.sourceFromSec(bars(barSec, 12d), barSec);
        float[][] layer = StemBridge.layer(tail, RATE, plan, 1d, fromSec);
        float[][] source = slice(tail, fromSec, plan.lengthSec());
        float[][] head = pad(plan.lengthSec(), 220d, 0.12d);
        float[][] vocals = new float[2][(int) Math.round(plan.lengthSec() * RATE)];

        StemBridge.Report report = StemBridge.measure(layer, source, head, vocals, vocals, RATE,
                plan, 1d, beatSec, beatSec);
        ok(report.acceptable, report.describe());
        ok(report.beatsWithLowEnd >= report.beats - 1, report.describe());
        assertEquals(0d, report.pitchOffsetSemitones, 1e-9);
        ok(report.alignment >= StemBridge.ALIGNMENT_FLOOR, report.describe());
        assertEquals(0, report.alignmentLagSamples);
        ok(report.doublingDb <= StemBridge.DOUBLING_LIMIT_DB, report.describe());
    }

    @Test
    public void aCarriedLayerThatIsNotTheSourceAtAllIsCaught() {
        double beatSec = 0.5d;
        double barSec = beatSec * 4;
        double windowSec = 22d;
        float[][] tail = bassline(beatSec, 12d, 55d, 0.5d);
        StemBridge.Plan plan = StemBridge.plan(bars(barSec, windowSec), barSec, 20d, windowSec);
        double fromSec = StemBridge.sourceFromSec(bars(barSec, 12d), barSec);
        float[][] source = slice(tail, fromSec, plan.lengthSec());
        float[][] quiet = new float[2][source[0].length];

        // The instrument's own check: material that is not the source cannot pass as it. (A copy
        // summed in late is deliberately NOT tested here — see alignmentAtZeroLag for why a
        // periodic second copy is undecidable in the signal domain, and the class note for why the
        // design does not depend on deciding it.)
        float[][] alien = pad(plan.lengthSec(), 330d, 0.4d);
        StemBridge.Report report = StemBridge.measure(alien, source, quiet, quiet, quiet, RATE, plan,
                1d, beatSec, beatSec);
        bad(report.acceptable, report.describe());
        ok(report.failures.contains("not the material"), report.failures);
        // And the outgoing's voice is never what was carried: a layer that IS that voice is caught
        // by the alignment, not by a level (the outgoing track's voice is all over the bars the
        // material is taken from, so a level would say nothing).
        float[][] voice = pad(plan.lengthSec(), 440d, 0.4d);
        StemBridge.Report voiceCarried = StemBridge.measure(voice, voice, quiet, quiet, voice, RATE,
                plan, 1d, beatSec, beatSec);
        bad(voiceCarried.acceptable, voiceCarried.describe());
        ok(voiceCarried.failures.contains("voice"), voiceCarried.failures);

        // And the doubling clause, which is the one the placement exists for: the same material
        // laid over itself in the same band measures about 6 dB above the louder contribution.
        float[][] layer = StemBridge.layer(tail, RATE, plan, 1d, fromSec);
        StemBridge.Report doubled = StemBridge.measure(layer, source, layer, quiet, quiet, RATE, plan,
                1d, beatSec, beatSec);
        bad(doubled.acceptable, doubled.describe());
        ok(doubled.failures.contains("double"), doubled.failures);
        ok(doubled.doublingDb > 5d, "" + doubled.doublingDb);
    }

    @Test
    public void vocalsInTheBridgeAreCaught() {
        double beatSec = 0.5d;
        double barSec = beatSec * 4;
        double windowSec = 22d;
        float[][] tail = bassline(beatSec, 12d, 55d, 0.5d);
        StemBridge.Plan plan = StemBridge.plan(bars(barSec, windowSec), barSec, 20d, windowSec);
        double fromSec = StemBridge.sourceFromSec(bars(barSec, 12d), barSec);
        float[][] layer = StemBridge.layer(tail, RATE, plan, 1d, fromSec);
        float[][] source = slice(tail, fromSec, plan.lengthSec());
        float[][] head = pad(plan.lengthSec(), 220d, 0.12d);
        float[][] sung = pad(plan.lengthSec(), 440d, 0.2d);

        StemBridge.Report clean = StemBridge.measure(layer, source, head, new float[2][head[0].length],
                new float[2][head[0].length], RATE, plan, 1d, beatSec, beatSec);
        ok(clean.acceptable, clean.describe());
        StemBridge.Report incomingSings = StemBridge.measure(layer, source, head, sung,
                new float[2][head[0].length], RATE, plan, 1d, beatSec, beatSec);
        bad(incomingSings.acceptable);
        ok(incomingSings.failures.contains("incoming's vocals"), incomingSings.failures);
        // The outgoing's voice in the bridge is caught by the ALIGNMENT and not by a level: that
        // track's voice is all over the bars the carried material comes from, so a level would
        // flag every bridge. Here the carried layer IS the vocal stem, which is exactly the thing
        // that must never happen.
        StemBridge.Report outgoingSings = StemBridge.measure(layer, source, head,
                new float[2][head[0].length], layer, RATE, plan, 1d, beatSec, beatSec);
        bad(outgoingSings.acceptable, outgoingSings.describe());
        ok(outgoingSings.failures.contains("voice"), outgoingSings.failures);
    }

    @Test
    public void aHoleInThePulseIsCaught() {
        double beatSec = 0.5d;
        double barSec = beatSec * 4;
        double windowSec = 22d;
        float[][] tail = bassline(beatSec, 12d, 55d, 0.5d);
        StemBridge.Plan plan = StemBridge.plan(bars(barSec, windowSec), barSec, 20d, windowSec);
        double fromSec = StemBridge.sourceFromSec(bars(barSec, 12d), barSec);
        float[][] layer = StemBridge.layer(tail, RATE, plan, 1d, fromSec);
        // Silence the middle bar: the pulse really does stop for two seconds.
        int half = layer[0].length / 2;
        int quarter = layer[0].length / 4;
        for (int ch = 0; ch < 2; ch++) {
            for (int i = half - quarter; i < half + quarter; i++) layer[ch][i] = 0f;
        }
        float[][] source = slice(tail, fromSec, plan.lengthSec());
        float[][] quiet = new float[2][layer[0].length];
        StemBridge.Report report = StemBridge.measure(layer, source, quiet, quiet, quiet, RATE,
                plan, 1d, beatSec, beatSec);
        bad(report.acceptable, report.describe());
        ok(report.failures.contains("hole"), report.failures);
    }

    /**
     * ⚠️ Round 4's calibration, on the material a real device refused: a carried layer that is a
     * <b>legato bass line</b>. The device's own numbers, from its outgoing track 34364062: the
     * carried layer is at −14.1 dBFS (one copy, alignment 1.0, no doubling) and its per-beat peaks
     * sit only +0.5 / +1.8 / +2.0 / +3.3 / +0.5 / +0.5 / +0.7 / +1.0 dB above their own beat
     * medians — so an <em>attack</em> test ("a frame 6 dB above the window's median") counted 0 of 7
     * beats and refused a bridge that is exactly what the bridge is for. The clause asks whether the
     * low end is there; this is what a bass line that is there looks like.
     */
    @Test
    public void aLegatoBassCarryIsNotAHole() {
        double beatSec = 0.5d;
        double barSec = beatSec * 4;
        double windowSec = 22d;
        // A sustained low line with a slow swell: no attack on any beat, i.e. no per-beat peak more
        // than a dB or two above the beat's own level (the device measured +0.5..+3.3 dB).
        int frames = (int) Math.round(12d * RATE);
        float[][] tail = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            double swell = 0.5d + 0.05d * Math.sin(2 * Math.PI * i / (RATE * 1.7d));
            float v = (float) (swell * Math.sin(2 * Math.PI * 55d * i / RATE));
            tail[0][i] = v;
            tail[1][i] = v;
        }
        StemBridge.Plan plan = StemBridge.plan(bars(barSec, windowSec), barSec, 20d, windowSec);
        ok(plan.fits, plan.reason);
        double fromSec = StemBridge.sourceFromSec(bars(barSec, 12d), barSec);
        float[][] layer = StemBridge.layer(tail, RATE, plan, 1d, fromSec);
        float[][] source = slice(tail, fromSec, plan.lengthSec());
        float[][] head = pad(plan.lengthSec(), 220d, 0.12d);
        float[][] quiet = new float[2][layer[0].length];
        StemBridge.Report report = StemBridge.measure(layer, source, head, quiet, quiet, RATE,
                plan, 1d, beatSec, beatSec);
        println("%s", report.describe());
        // Every beat has the low end, and the levels are what the device measured: one copy of a
        // line 20+ dB above the level the clause's own per-beat test would call absent.
        ok(report.beatsWithLowEnd == report.beats, report.describe());
        ok(report.longestGapMs == 0d, report.describe());
        ok(report.medianLevel > -20d, report.describe());
        ok(report.acceptable, report.describe());
        // And the same layer with a real dropout in it is still refused: the clause kept its teeth.
        for (int ch = 0; ch < 2; ch++) {
            int half = layer[0].length / 2;
            for (int i = half - layer[0].length / 8; i < half + layer[0].length / 8; i++) {
                layer[ch][i] = 0f;
            }
        }
        StemBridge.Report holey = StemBridge.measure(layer, source, head, quiet, quiet, RATE,
                plan, 1d, beatSec, beatSec);
        println("%s", holey.describe());
        bad(holey.acceptable, holey.describe());
        ok(holey.failures.contains("hole"), holey.failures);
    }

    private static float[][] slice(float[][] pcm, double fromSec, double lengthSec) {
        int from = (int) Math.round(fromSec * RATE);
        int frames = (int) Math.round(lengthSec * RATE);
        float[][] out = new float[pcm.length][frames];
        for (int ch = 0; ch < pcm.length; ch++) {
            System.arraycopy(pcm[ch], from, out[ch], 0, Math.min(frames, pcm[ch].length - from));
        }
        return out;
    }

    @Test
    public void aPlanThatDoesNotFitCarriesNothing() {
        // A removal window too short for a two-bar bridge at the hand-over's own fraction.
        StemBridge.Plan refused = StemBridge.plan(null, 2d, 4d, 30d);
        bad(refused.fits);
        assertNull(StemBridge.layer(bassline(0.5d, 4d, 55d, 0.5d), RATE, refused, 1d, 0d));
    }
}
