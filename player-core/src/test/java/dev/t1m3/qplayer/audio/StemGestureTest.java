package dev.t1m3.qplayer.audio;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The stem gesture ({@link StemGesture}) as arithmetic: every rule the Folia listening
 * rounds settled is asserted here as a number, and the numbers are printed.
 *
 * <p>Pure Java on purpose — no model, no device, no audio: the inputs are hand-built
 * envelopes, so a failure is a statement about the DECISION rather than about htdemucs.
 * The separation itself has its own check (the four rows must sum back to the mix), and
 * the row order is verified on the device — see AI_HANDOFF §7.
 */
public class StemGestureTest {

    private static final double CELL = StemGesture.CELL_SEC;

    /** A cell-quantised envelope of {@code seconds}, all at {@code level}. */
    private static float[] flat(double seconds, double level) {
        int cells = (int) Math.round(seconds / CELL);
        float[] out = new float[cells];
        java.util.Arrays.fill(out, (float) level);
        return out;
    }

    private static void set(float[] envelope, double fromSec, double toSec, double level) {
        int from = (int) Math.round(fromSec / CELL);
        int to = (int) Math.round(toSec / CELL);
        for (int i = from; i < to && i < envelope.length; i++) envelope[i] = (float) level;
    }

    private static void println(String format, Object... args) {
        System.out.println(String.format(Locale.US, format, args));
    }

    // --- the shapes ----------------------------------------------------------

    /**
     * The falling shape is NOT the equal-power complement of the rise, and the 1.6 dB
     * midpoint dip is the measurement that pins it.
     *
     * <p>For two uncorrelated signals the power at the midpoint is 0.5 for a linear pair
     * (-3.0 dB), 1.0 for a true equal-power pair (0 dB), and 0.697 for Folia's pair. It was
     * part of what every listening round heard, and the crossings it is used for are six
     * milliseconds long, so "correcting" it would be a different gesture.
     */
    @Test
    public void theFallingShapeIsDeliberatelyNotTheEqualPowerComplement() {
        double rise = StemGesture.rise(0, 1, 0.5);
        double fall = StemGesture.fall(0, 1, 0.5);
        double power = rise * rise + fall * fall;
        double dipDb = 10 * Math.log10(power);
        println("midpoint: rise=%.4f fall=%.4f -> power sum %.4f (%.2f dB); linear would be %.2f dB, equal power %.2f dB",
                rise, fall, power, dipDb, 10 * Math.log10(0.5), 0.0);
        assertEquals(-1.6, dipDb, 0.05);
        assertTrue(rise > fall);
        // The ends are exact, which is what a sampled curve and a splice both need.
        assertEquals(0, StemGesture.rise(0, 1, 0), 1e-9);
        assertEquals(1, StemGesture.rise(0, 1, 1), 1e-9);
        assertEquals(1, StemGesture.fall(0, 1, 0), 1e-9);
        assertEquals(0, StemGesture.fall(0, 1, 1), 1e-9);   // cos(pi/2), which is 6e-17 not 0
    }

    @Test
    public void aCurveIsSampledAtTwoHundredPointsPerSecond() {
        float[] curve = StemGesture.curveOf(15, new StemGesture.CurveShape() {
            @Override public double at(double t) { return t / 15; }
        });
        assertEquals(3000, curve.length);
        assertEquals(0, curve[0], 1e-6);
        assertEquals(1, curve[curve.length - 1], 1e-6);
        assertEquals(2, StemGesture.curveOf(0.001, new StemGesture.CurveShape() {
            @Override public double at(double t) { return 0; }
        }).length);
        // Evaluated at the curve's own sample times, exactly.
        double half = StemGesture.curveAt(curve, 7.5, 15);
        println("curve(15s) has %d points; value at 7.5s = %.6f", curve.length, half);
        assertEquals(0.5, half, 1e-5);
    }

    // --- rule 1: the order of the handovers ---------------------------------

    /**
     * Drums swap first over a 6ms cut on a real bar line; bass follows one bar later.
     */
    @Test
    public void theDrumsSwapOnABarLineAndTheBassFollowsOneBarLater() {
        double bpm = 83.3, bar = 4 * 60 / bpm;                  // 2.881s
        double window = 15;
        double[] bars = StemGesture.barLines(bpm, 0.0, 4, 100.0, window);
        println("bar=%.3fs bars in the window: %s", bar, java.util.Arrays.toString(bars));
        StemHandoverQuiet handover = plan(window, bpm, bar, bars, quietVoice(window));
        println("swap=%.3fs (bar line=%s) bass=%.3fs vocalIn=%.3fs due=%.3fs exit=%s gap=%.3fs",
                handover.plan.swap, handover.plan.onBarLine, handover.plan.bassAt,
                handover.plan.vocalIn, handover.plan.dueAt, handover.plan.exit, handover.plan.vocalGap());

        assertTrue(handover.plan.onBarLine);
        assertTrue("the swap is one of the window's bar lines",
                contains(bars, handover.plan.swap));
        assertEquals(bar, handover.plan.bassAt - handover.plan.swap, 1e-6);
        // The swap is the bar line nearest the target, and the target is the fraction unless the
        // tail bound below bites first.
        double target = Math.max(0.42 * window, window - (1 + StemGesture.MAX_TAIL_BARS) * bar);
        assertEquals(target, handover.plan.swap, bar / 2 + 1e-6);
    }

    /**
     * The tail bound: a window long enough for the fraction to leave more than two bars
     * after the handover moves the handover LATER rather than shortening the blend.
     */
    @Test
    public void aLongWindowSpendsItsExtraTimeBeforeTheHandover() {
        double bpm = 120, bar = 2.0, window = 30;
        double[] bars = StemGesture.barLines(bpm, 0.0, 4, 0.0, window);
        StemHandoverQuiet handover = plan(window, bpm, bar, bars, quietVoice(window));
        double tail = window - handover.plan.swap;
        println("window=%.0fs bar=%.1fs: fraction would be %.2fs, swap is %.2fs, tail %.2fs = %.2f bars",
                window, bar, 0.42 * window, handover.plan.swap, tail, tail / bar);
        assertEquals(window - (1 + StemGesture.MAX_TAIL_BARS) * bar, handover.plan.swap, 1e-6);
        assertTrue(tail <= (1 + StemGesture.MAX_TAIL_BARS) * bar + 1e-6);
        // and the gesture itself is unchanged: bass one bar after the swap, the incoming voice
        // one of ITS bars after the swap.
        assertEquals(bar, handover.plan.bassAt - handover.plan.swap, 1e-6);
        assertEquals(handover.plan.dueAt, handover.plan.swap + bar, 1e-6);
    }

    /**
     * The incoming `other` bed arrives BEFORE its own drums, so the incoming track is already
     * present as atmosphere when the beat changes hands.
     *
     * <p>Note the sample grid: a curve is 200 points per second, so a 6ms edge is about one
     * sample wide and the SAMPLED curve cannot be exact at the swap — the shape assertion is
     * therefore made on {@code rise} itself, and the sampled curve is only required to have
     * crossed within a sample. Folia's own scheduling interpolates between these points, so the
     * edge that actually plays is quantised to the same 5ms grid.
     */
    @Test
    public void theIncomingBedArrivesBeforeItsOwnDrums() {
        double bpm = 83.3, bar = 4 * 60 / bpm, window = 15;
        double[] bars = StemGesture.barLines(bpm, 0.0, 4, 0.0, window);
        StemHandoverQuiet handover = plan(window, bpm, bar, bars, quietVoice(window));
        float[][] in = StemGesture.incomingCurves(window, handover.plan);
        float[] other = in[StemGesture.Stem.OTHER.row()];
        float[] drums = in[StemGesture.Stem.DRUMS.row()];
        double swap = handover.plan.swap;
        println("incoming: other=1 from %.3fs (swap %.3fs), drums 0->1 across the 6ms edge; "
                + "sampled at %.1f points/s so the edge is 1 sample wide",
                swap - StemGesture.INCOMING_OTHER_LEAD_SEC, swap, (double) StemGesture.CURVE_RATE);
        // The shape, exactly.
        assertEquals(0, StemGesture.rise(swap, swap + StemGesture.SWAP_EDGE_SEC, swap), 0);
        assertEquals(1, StemGesture.rise(swap, swap + StemGesture.SWAP_EDGE_SEC,
                swap + StemGesture.SWAP_EDGE_SEC), 1e-9);
        // And what the deck is actually given.
        // The curve's own sample grid is 5ms, so "exactly at the start" is only exact between
        // samples; a quarter of a second clear of it is unambiguous.
        assertEquals(0, StemGesture.curveAt(other, swap - 1.25, window), 1e-9);
        assertEquals(1, StemGesture.curveAt(other, swap, window), 1e-6);
        assertTrue("the drums must not be in yet where the bed is fully in",
                StemGesture.curveAt(drums, swap, window) < 0.2);
        assertEquals(1, StemGesture.curveAt(drums, swap + 0.02, window), 1e-6);
        // The bed arrives 1.2s of window before the swap, not with it.
        assertEquals(0.2588, StemGesture.curveAt(other, swap - 1.0, window), 5e-3);
    }

    private static boolean contains(double[] values, double value) {
        for (double v : values) if (Math.abs(v - value) < 1e-9) return true;
        return false;
    }

    // --- rule 2/3: the exit --------------------------------------------------

    /**
     * Among the moments quiet enough to cut in, the LAST is taken, not the quietest. An early
     * rest that is deeper loses to a later one that is shallower-but-still-under-threshold,
     * because choosing the earlier one deletes every word sung in between.
     */
    @Test
    public void theExitTakesTheLastRestNotTheQuietest() {
        double window = 15;
        float[] mix = flat(window, 0.3);
        float[] vocals = flat(window, 0.3);                     // singing throughout...
        set(vocals, 2.0, 2.9, 0.0003);                          // a deep rest: -60 dB under the mix
        set(vocals, 5.5, 6.1, 0.009);                           // a shallow one: -30.5 dB, still a rest
        StemGesture.VocalExit exit = StemGesture.planVocalExit(vocals, mix, 9.0, 1.0, CELL, true);
        println("deep rest at 2.00s (-60dB), shallow at 5.50s (-30.5dB) -> exit %s", exit);
        assertEquals(StemGesture.ExitKind.REST, exit.kind);
        assertEquals(5.6, exit.from, 1e-6);                     // cell-quantised, not 5.50
        assertTrue("the deeper early rest must lose to the later one", exit.from > 4.0);
        // Graded by depth: a -30.5 dB rest gets a longer cut than a -60 dB one.
        assertTrue(exit.to - exit.from > StemGesture.CUT_SEC);
        assertTrue(exit.to - exit.from <= StemGesture.SOFT_EXIT_SEC + 1e-6);

        // And the deep one alone keeps the harness's half second.
        float[] onlyDeep = flat(window, 0.3);
        set(onlyDeep, 2.0, 2.9, 0.0003);
        StemGesture.VocalExit deep = StemGesture.planVocalExit(onlyDeep, mix, 9.0, 1.0, CELL, true);
        println("deep rest alone -> exit %s", deep);
        assertEquals(StemGesture.CUT_SEC, deep.to - deep.from, 1e-6);
    }

    /**
     * Nothing quiet enough anywhere: the voice RECEDES, and the recede starts at the swap
     * rather than at the window's start — the do-not-stack constraint does not exist before
     * the incoming voice is due, so the outgoing track keeps its voice until then.
     */
    @Test
    public void aRecedeStartsAtTheSwapNotTheWindowStart() {
        double bpm = 83.3, bar = 4 * 60 / bpm, window = 15;
        double[] bars = StemGesture.barLines(bpm, 0.0, 4, 0.0, window);
        StemHandoverQuiet handover = plan(window, bpm, bar, bars, loudVoice(window));
        println("no rest anywhere -> exit %s (swap %.3fs)", handover.plan.exit, handover.plan.swap);
        assertEquals(StemGesture.ExitKind.RECEDE, handover.plan.exit.kind);
        assertEquals(handover.plan.swap, handover.plan.exit.from, 1e-6);
        assertTrue(handover.plan.exit.to - handover.plan.exit.from >= StemGesture.MIN_RECEDE_SEC - 1e-6);

        // A window too short to hold a one-second recede from the swap: the fade is squeezed
        // to the floor rather than starting later (the end is what is pinned).
        StemHandoverQuiet shortPlan = plan(6.0, bpm, bar, bars, loudVoice(6.0));
        println("6s window -> exit %s (swap %.3fs)", shortPlan.plan.exit, shortPlan.plan.swap);
        assertEquals(StemGesture.ExitKind.RECEDE, shortPlan.plan.exit.kind);
        // The start is the earlier of the swap and "the deadline less the floor": the END is what
        // is pinned, so a short window moves the start back rather than shortening the fade.
        assertEquals(Math.min(shortPlan.plan.swap,
                        shortPlan.plan.exit.to - StemGesture.MIN_RECEDE_SEC),
                shortPlan.plan.exit.from, 1e-6);
        assertTrue(shortPlan.plan.exit.to - shortPlan.plan.exit.from >= StemGesture.MIN_RECEDE_SEC - 1e-6);
    }

    /**
     * The exit search is FREE, never snapped to the beat grid: a track whose rest is barely
     * half a second long gives up 16 dB when the search is snapped (measured in Folia's own
     * round ten), so the port takes the moment the envelope actually holds.
     */
    @Test
    public void theExitSearchIsFreeRatherThanSnapped() {
        double window = 15;
        float[] mix = flat(window, 0.3);
        float[] vocals = flat(window, 0.3);
        set(vocals, 4.35, 4.85, 0.0003);                        // a half-second rest off the grid
        StemGesture.VocalExit exit = StemGesture.planVocalExit(vocals, mix, 9.0, 1.0, CELL, true);
        println("rest at 4.35s (a 0.4s beat grid has no line there) -> exit %s", exit);
        assertEquals(4.35, exit.from, 1e-6);
        assertEquals(-60, exit.loudDb, 0.5);
    }

    /**
     * A held note still sounding at the deadline is ridden to its release, and the incoming
     * voice waits one of its own bars for it — the only deliberate breach of the
     * do-not-stack rule, because a held vowel is not delivering words.
     */
    @Test
    public void aHeldNoteIsRiddenOutAndTheEntryWaitsForIt() {
        double bpm = 83.3, bar = 4 * 60 / bpm, window = 15;
        double[] bars = StemGesture.barLines(bpm, 0.0, 4, 0.0, window);
        float[] mix = flat(window, 0.3);
        float[] vocals = flat(window, 0.0005);                  // not singing...
        set(vocals, 6.0, 12.0, 0.4);                            // ...until a note is held 6-12s
        StemGesture.VocalSustain held = StemGesture.findSustain(vocals, mix, CELL);
        StemHandoverQuiet handover = plan(window, bpm, bar, bars, vocals);
        println("held note: %s; exit %s; due %.3fs -> vocalIn %.3fs",
                held, handover.plan.exit, handover.plan.dueAt, handover.plan.vocalIn);
        assertNotNull(held);
        assertEquals(6.0, held.from, 1e-6);
        assertEquals(12.0, held.to, 1e-6);
        assertEquals(StemGesture.ExitKind.RELEASE, handover.plan.exit.kind);
        assertEquals(held.to, handover.plan.exit.from, 1e-6);
        // One incoming bar before the release, because the release is later than the due entry.
        assertEquals(Math.min(handover.plan.exit.from - bar, window - 0.6), handover.plan.vocalIn, 1e-6);
        assertTrue(handover.plan.vocalIn >= handover.plan.dueAt);
        // A voice that never lets go inside the window has no release to ride to: it has to give
        // the deck back something, and the window's end is not a release.
        float[] neverReleases = flat(window, 0.4);
        assertNotNull(StemGesture.findSustain(neverReleases, mix, CELL));
        assertEquals(window, StemGesture.findSustain(neverReleases, mix, CELL).to, 1e-6);
        StemGesture.VocalExit noRelease = StemGesture.planVocalExit(neverReleases, mix,
                handover.plan.dueAt + StemGesture.CUT_SEC, 1.0, CELL, true);
        println("a voice still holding at the window's end -> %s", noRelease);
        assertEquals(StemGesture.ExitKind.RECEDE, noRelease.kind);
        // ...and the key-clash refusal turns the ride off for a note that IS ridable.
        StemGesture.VocalExit refused = StemGesture.planVocalExit(vocals, mix,
                handover.plan.dueAt + StemGesture.CUT_SEC, 1.0, CELL, false);
        println("same window with a known key clash -> %s", refused);
        assertFalse(refused.kind == StemGesture.ExitKind.RELEASE);
    }

    @Test
    public void aSustainIsARunThatHoldsItsBandForAtLeastOnePointTwoSeconds() {
        float[] mix = flat(15, 0.3);
        // A 0.6s swell is a syllable, not a hold.
        float[] syllable = flat(15, 0.001);
        set(syllable, 4.0, 4.6, 0.4);
        assertNull(StemGesture.findSustain(syllable, mix, CELL));
        // 1.2s of steady level is a hold; the note ends where it decays 7 dB under its peak.
        float[] note = flat(15, 0.001);
        set(note, 4.0, 6.0, 0.4);
        StemGesture.VocalSustain held = StemGesture.findSustain(note, mix, CELL);
        println("1.2s hold at 4.00-6.00s (+2.5dB over the mix) -> %s", held);
        assertNotNull(held);
        assertEquals(4.0, held.from, 1e-6);
        assertEquals(6.05, held.to, CELL + 1e-6);               // the cell that fails the band test
        // The LAST hold in the window is the one that is reported.
        float[] two = flat(15, 0.001);
        set(two, 2.0, 4.0, 0.4);
        set(two, 9.0, 11.0, 0.4);
        StemGesture.VocalSustain last = StemGesture.findSustain(two, mix, CELL);
        println("two holds (2-4s, 9-11s) -> %s", last);
        assertEquals(9.0, last.from, 1e-6);
    }

    // --- rule 4: the two voices must not stack ------------------------------

    /**
     * The vocal gap: the incoming voice arrives while the outgoing one is still leaving, so
     * the handover is a pass rather than a hole. Folia measured every blend that sounded
     * right at about -0.5s and the one the listener flagged at +13.8s.
     */
    @Test
    public void theIncomingVoiceArrivesWhileTheOutgoingOneIsStillLeaving() {
        double bpm = 83.3, bar = 4 * 60 / bpm, window = 15;
        double[] bars = StemGesture.barLines(bpm, 0.0, 4, 0.0, window);
        StemHandoverQuiet handover = plan(window, bpm, bar, bars, quietVoice(window));
        double gap = handover.plan.vocalGap();
        println("vocalIn %.3fs, exit ends %.3fs -> gap %+.3fs", handover.plan.vocalIn,
                handover.plan.exit.to, gap);
        assertTrue("both voices must cross, never a hole", gap <= 0);
        assertTrue("and no more than half a second of both", gap >= -StemGesture.CUT_SEC - 1e-6);
    }

    // --- rule 5: the all-unity invariant ------------------------------------

    /**
     * Every outgoing curve starts at unity, and every incoming curve reaches unity before the
     * window's tail guard. That is the whole reason the deck can hand its stems back to the
     * full master over an 8ms splice at both ends: at the outgoing splice the four stems sum
     * to exactly the master, and at the incoming hand-back they do too.
     */
    @Test
    public void everyCurveStartsOrEndsAtUnity() {
        double bpm = 83.3, bar = 4 * 60 / bpm, window = 15;
        double[] bars = StemGesture.barLines(bpm, 0.0, 4, 0.0, window);
        StemHandoverQuiet handover = plan(window, bpm, bar, bars, quietVoice(window));
        float[][] out = StemGesture.outgoingCurves(window, handover.plan);
        float[][] in = StemGesture.incomingCurves(window, handover.plan);
        StringBuilder outAtZero = new StringBuilder();
        StringBuilder inAtEnd = new StringBuilder();
        for (StemGesture.Stem stem : StemGesture.Stem.ALL) {
            double atStart = StemGesture.curveAt(out[stem.row()], 0, window);
            double atGuard = StemGesture.curveAt(in[stem.row()], window - 0.4, window);
            outAtZero.append(outAtZero.length() == 0 ? "" : " ")
                    .append(stem.name().toLowerCase(Locale.US)).append('=')
                    .append(String.format(Locale.US, "%.4f", atStart));
            inAtEnd.append(inAtEnd.length() == 0 ? "" : " ")
                    .append(stem.name().toLowerCase(Locale.US)).append('=')
                    .append(String.format(Locale.US, "%.4f", atGuard));
            assertEquals("outgoing " + stem + " must be at unity where the deck splices in",
                    1, atStart, 1e-6);
            assertEquals("incoming " + stem + " must be at unity before the window's hand-back",
                    1, atGuard, 1e-6);
        }
        println("outgoing curves at t=0: %s", outAtZero);
        println("incoming curves at %.2fs (window %.0fs): %s", window - 0.4, window, inAtEnd);
        // Every outgoing curve has to END in silence, or the outgoing track would still be
        // playing under the next one when the window closes.
        for (StemGesture.Stem stem : StemGesture.Stem.ALL) {
            assertEquals(stem.name(), 0, StemGesture.curveAt(out[stem.row()], window, window), 1e-6);
        }
    }

    // --- rule 6: bar lines ---------------------------------------------------

    /**
     * Bar lines come from a DOWNBEAT offset plus beatsPerBar. Our profiler exposes the phase
     * of the BEAT grid ({@code BeatProfile.firstBeatMs}), which is a different question: a bar
     * line spaced a bar apart from the beat phase is one three times in four. Feeding the beat
     * phase in as a downbeat is therefore what this test exists to catch.
     */
    @Test
    public void barLinesComeFromADownbeatOffsetNotTheBeatPhase() {
        double bpm = 120, beat = 0.5, bar = 2.0;
        // Beat phase 0.1s (beats at 0.1, 0.6, 1.1 ...) and a downbeat that is the THIRD beat.
        double downbeat = 1.1;
        double[] lines = StemGesture.barLines(bpm, downbeat, 4, 0.0, 10.0);
        println("beats at %.2f+k*%.2f, downbeat %.2f -> bars %s", 0.1, beat, downbeat,
                java.util.Arrays.toString(lines));
        for (double at : lines) {
            double barsOff = (at - downbeat) / bar;
            assertEquals("every line is a downbeat", 0, Math.abs(barsOff - Math.round(barsOff)), 1e-6);
        }
        // Spaced a BAR apart, and off the beat phase by a whole number of beats: built from the
        // beat phase instead, the same track would get lines at 0.1, 2.1, 4.1 ... — a bar line
        // three times in four in the wrong place, which is exactly what Folia's comment warns of.
        assertEquals(beat * 4, lines[1] - lines[0], 1e-9);
        assertEquals(1.0, lines[0] - 0.1, 1e-9);
        // No downbeat offset means no bar lines at all — and the swap lands on the fraction.
        assertEquals(0, StemGesture.barLines(bpm, null, 4, 0.0, 10.0).length);
        StemHandoverQuiet noisy = plan(15, bpm, bar, StemGesture.barLines(bpm, null, 4, 0.0, 15),
                quietVoice(15));
        println("no bar lines -> swap %.3fs on the fraction (barLine=%s)", noisy.plan.swap,
                noisy.plan.onBarLine);
        assertFalse(noisy.plan.onBarLine);
        // The fraction, unless the tail bound pushes it later — here it does (15 - 3 bars = 9s).
        assertEquals(Math.max(0.42 * 15, 15 - (1 + StemGesture.MAX_TAIL_BARS) * bar),
                noisy.plan.swap, 1e-6);
    }

    /**
     * The downbeat estimate (an EXTENSION, not part of the port): with the kick on the bar's
     * first beat, the estimator must find that beat even though the profiler only reports the
     * beat grid's phase.
     */
    @Test
    public void theDownbeatEstimateFindsTheKick() {
        double beat = 0.5, bar = 2.0, beatPhase = 0.13;         // beats at 0.13, 0.63, 1.13 ...
        double trueDownbeat = beatPhase + 2 * beat;             // the bar starts on the 3rd beat
        float[] low = new float[(int) Math.round(60 / CELL)];
        for (int cell = 0; cell < low.length; cell++) {
            double t = cell * CELL;
            double into = ((t - trueDownbeat) % bar + bar) % bar;
            low[cell] = into < 0.1 ? 1f : 0.05f;                 // a kick on the downbeat only
        }
        double found = StemGesture.downbeatOffsetSec(low, 0, CELL, beatPhase, beat, 4);
        double folded = ((found % bar) + bar) % bar;
        double expected = ((trueDownbeat % bar) + bar) % bar;
        println("kick on the downbeat at %.2f (bar %.1f) -> estimate %.3f (folded %.3f, expected %.3f)",
                trueDownbeat, bar, found, folded, expected);
        assertEquals(expected, folded, 1e-6);
        assertTrue(Double.isNaN(StemGesture.downbeatOffsetSec(null, 0, CELL, beatPhase, beat, 4)));
    }

    // --- envelopes, and the two measurements that feed the plan -------------

    @Test
    public void theEnvelopeIsMonoSummedAndCellQuantised() {
        int rate = 1000;
        int n = 500;
        float[] left = new float[n];
        float[] right = new float[n];
        java.util.Arrays.fill(left, 1f);
        java.util.Arrays.fill(right, -1f);
        float[] envelope = StemGesture.envelopeOf(new float[][]{left, right}, rate, 0.05);
        println("mono sum of +1/-1 over %d samples -> %d cells of %.4f", n, envelope.length, envelope[0]);
        assertEquals(10, envelope.length);
        assertEquals(0, envelope[0], 1e-6);                     // the channels cancel exactly
        assertEquals(envelope.length, StemGesture.envelopeOf(new float[][]{left, left}, rate, 0.05).length);
        assertEquals(1, StemGesture.envelopeOf(new float[][]{left, left}, rate, 0.05)[0], 1e-6);
        assertEquals(2.5, StemGesture.median(new float[]{1, 2, 3, 4}), 0);
        assertEquals(3, StemGesture.median(new float[]{1, 3, 5}), 0);
    }

    /**
     * {@code singsInWindow} is a yes/no whose NEGATIVE is the usable half, and it has to be
     * measured against the whole separated head rather than the window itself: a quiet
     * incoming intro drags a window-local median down until separation bleed clears it.
     */
    @Test
    public void whetherTheIncomingTrackSingsIsMeasuredAgainstTheWholeHead() {
        float[] head = flat(30, 0.3);                           // the track's own level
        float[] silentVocal = flat(5, 0.0005);                  // bleed only
        assertFalse(StemGesture.singsInWindow(silentVocal, head));
        float[] oneLoudCell = flat(5, 0.0005);
        oneLoudCell[40] = 0.4f;                                 // a hat filed under vocals
        assertFalse("one loud cell is not singing", StemGesture.singsInWindow(oneLoudCell, head));
        float[] fiveCells = flat(5, 0.0005);
        set(fiveCells, 2.0, 2.25, 0.4);
        assertTrue(StemGesture.singsInWindow(fiveCells, head));
        // A quiet window against its own median would have passed the bleed; against the head
        // it does not. This is the 15-of-24-transitions bug in one assertion.
        assertTrue(StemGesture.singsInWindow(silentVocal, flat(5, 0.0005)));
        println("bleed at %.4f vs a head med 0.30 -> false; against the quiet window's own median -> true",
                silentVocal[0]);
    }

    @Test
    public void theLastVocalMomentIsWhereTheVoiceStopsNotWhereTheLyricsDo() {
        float[] mix = flat(15, 0.3);
        float[] vocals = flat(15, 0.0005);
        set(vocals, 1.0, 7.0, 0.3);
        double last = StemGesture.lastVocalMoment(vocals, mix, CELL);
        println("voice sounding 1.00-7.00s -> last vocal moment %.2fs (end + %.2fs)",
                last, StemGesture.VOCAL_TAIL_SEC);
        assertEquals(7.0 + StemGesture.VOCAL_TAIL_SEC, last, CELL + 1e-6);
        assertTrue(Double.isNaN(StemGesture.lastVocalMoment(flat(15, 0.0005), mix, CELL)));
    }

    // --- the window nobody is waiting for -----------------------------------

    /**
     * When the incoming track does not sing inside the window, every deadline dissolves: the
     * outgoing voice keeps the window instead of being faded for a voice that never arrives
     * (Folia measured 压音 on one real transition for exactly this).
     */
    @Test
    public void aWindowNobodySingsInDissolvesTheDeadline() {
        double bpm = 83.3, bar = 4 * 60 / bpm, window = 15;
        double[] bars = StemGesture.barLines(bpm, 0.0, 4, 0.0, window);
        StemHandoverQuiet waiting = plan(window, bpm, bar, bars, quietVoice(window));
        StemHandoverQuiet nobody = plan(window, bpm, bar, bars, quietVoice(window), Boolean.FALSE);
        println("incoming sings -> exit %s, vocalIn %.3fs", waiting.plan.exit, waiting.plan.vocalIn);
        println("nobody waiting -> exit %s, vocalIn %.3fs, gap %+.3fs",
                nobody.plan.exit, nobody.plan.vocalIn, nobody.plan.vocalGap());
        assertEquals(window, nobody.plan.vocalIn, 1e-6);
        assertTrue(nobody.plan.exit.to > waiting.plan.exit.to);
        assertTrue(nobody.plan.exit.to <= window + 1e-6);
    }

    // --- helpers -------------------------------------------------------------

    /**
     * An outgoing window whose voice stops before the blend and does not come back — the
     * commonest real shape, because the window is placed against where she stops singing. The
     * rest runs to the deadline, which is what puts {@link StemHandover#vocalGap()} at -0.5s.
     */
    private static float[] quietVoice(double window) {
        float[] vocals = flat(window, 0.3);
        set(vocals, window * 0.42, window, 0.0003);
        return vocals;
    }

    /** An outgoing window whose voice never gets quiet enough to cut in. */
    private static float[] loudVoice(double window) {
        return flat(window, 0.3);
    }

    /** One handover, with the mix it was planned against, for the printing tests. */
    private static final class StemHandoverQuiet {
        final StemGesture.StemHandover plan;
        final float[] mix;

        StemHandoverQuiet(StemGesture.StemHandover plan, float[] mix) {
            this.plan = plan;
            this.mix = mix;
        }
    }

    private static StemHandoverQuiet plan(double window, double bpm, double bar, double[] bars,
                                          float[] vocals) {
        return plan(window, bpm, bar, bars, vocals, null);
    }

    private static StemHandoverQuiet plan(double window, double bpm, double bar, double[] bars,
                                          float[] vocals, Boolean incomingSings) {
        float[] mix = flat(window, 0.3);
        StemGesture.StemHandover handover = StemGesture.planStemHandover(
                window, bar, bar, bars, vocals, mix, Boolean.FALSE, incomingSings, CELL);
        return new StemHandoverQuiet(handover, mix);
    }
}
