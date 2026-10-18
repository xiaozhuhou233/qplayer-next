package dev.t1m3.qplayer.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The shapes an overlap's gains can follow, and the properties of them that can be
 * measured rather than argued about: how much of the overlap has <em>both</em> tracks
 * audible at once, how far the outgoing track is taken down, and where it leaves.
 *
 * <p>Three rounds of listening reports are encoded here, and they point in different
 * directions on purpose:
 * <ul>
 *   <li>rounds 10–12 — "it still sounds like a fade-in/fade-out rather than a mix": the
 *       symmetric pair keeps the two gains comparable only near the middle (41% of the
 *       window), so the staged shape was built to hold both tracks up;</li>
 *   <li>round 16 — 「上一首歌戛然而止」 (the previous song cut off): holding the outgoing
 *       track at its own level until three quarters and then dropping it is heard as a
 *       cliff, so the exit got a shape of its own;</li>
 *   <li>round 17 — 「让…前一首歌部分淡一点，要不抢了」 and 「过渡部分不要保留非常高亢人声」
 *       (make the previous song quieter so it stops stealing the show; do not keep loud
 *       vocals inside the blend): the outgoing track's vocals cannot be removed from its
 *       stream, so the shape takes the whole track down to a −10 dB bed early and leaves
 *       it earlier. The both-audible share falls from 68% to 17% and that is the point.</li>
 * </ul>
 *
 * <p>The numbers below are printed by {@code Curve17} in the round-17 harness and by
 * running this class; they are the same arithmetic either way.
 */
public class FadeCurveTest {

    /** The ordinary overlap: what every target in this test is measured against. */
    private static final long LONG_MS = 15_000L;

    /** The outgoing track's own level at {@code t}, dB, for the tables below. */
    private static double levelDb(float t) {
        return FadeCurve.gainDb(FadeCurve.DJ_BLEND.outGain(t));
    }

    @Test
    public void theOutgoingTrackIsTakenDownToABedEarlyAndLeavesBeforeTheBlendEnds() {
        // The table the round-17 report is written from. The "before" numbers are what rounds
        // 12–16 shipped (cos(t^2.6) with a quarter-ramp release window): at its own level
        // through the first half, -2.7 dB at three quarters, and then the cliff.
        System.out.println("outgoing level, dB, over a " + LONG_MS + "ms ramp:");
        System.out.printf(java.util.Locale.US, "  t=0.25 %7.2f   (before  -0.00)%n", levelDb(0.25f));
        System.out.printf(java.util.Locale.US, "  t=0.50 %7.2f   (before  -0.30)%n", levelDb(0.5f));
        System.out.printf(java.util.Locale.US, "  t=0.75 %7.2f   (before  -2.66)%n", levelDb(0.75f));
        System.out.printf(java.util.Locale.US, "  t=0.90 %7.2f   (before -17.89)%n", levelDb(0.9f));
        // 1. The bed: the whole point is that the outgoing track stops competing early, and
        //    the requirement is quantified at a quarter of the ramp.
        assertTrue("the outgoing track must already be at its bed by a quarter of the ramp, was "
                        + levelDb(0.25f) + " dB", levelDb(0.25f) <= -9f);
        assertTrue("... and no louder than the bed itself there, was " + levelDb(0.25f) + " dB",
                levelDb(0.25f) <= -10f + 1f);
        // 2. It is a BED, not silence and not its own level: the key blend's ladder moves both
        //    decks, so a bed that is there is what it is for.
        assertTrue("... and still plainly present, was " + levelDb(0.5f) + " dB",
                levelDb(0.5f) >= -14f);
        // 3. Ten decibels down where the old shape was at its own level is the whole change:
        //    this is the number the outgoing track's unremovable vocals sit at.
        assertTrue("the bed must be at least 9 dB under the old shape's level at a half, was "
                        + levelDb(0.5f) + " dB", levelDb(0.5f) <= -9f);
        // 4. The exit is earlier and complete before the end: gone (below the inaudible floor)
        //    by 85% of the ramp, where the old shape was still at -14 dB at 95%.
        assertTrue("the outgoing track must be inaudible by 85% of the ramp, was "
                        + levelDb(0.85f) + " dB", levelDb(0.85f) <= FadeCurve.INAUDIBLE_DB);
        assertTrue("... and well down at three quarters, was " + levelDb(0.75f) + " dB",
                levelDb(0.75f) <= -35f);
        // 5. Monotone in dB — the property the shape is defined in. A gain-domain shape has a
        //    dB curve whose slope runs away wherever the gain approaches zero, which is how the
        //    round-16 exit managed to be monotone and still sound like a cliff.
        double previous = 1d;
        int last = (int) Math.floor(1000d * (1d - FadeCurve.DJ_BLEND.outSilentTail())) - 1;
        for (int i = 0; i <= last; i++) {
            double db = levelDb(i / 1000f);
            assertTrue("the outgoing track's level must never rise (t=" + (i / 1000f) + ": "
                            + previous + " -> " + db + ")", db <= previous + 1e-4);
            previous = db;
        }
    }

    @Test
    public void theOutgoingTrackLeavesFarEarlierThanItUsedTo() {
        // "Earlier and faster" as a number a report can carry: the fraction of the ramp at
        // which the outgoing track crosses each level, this build against rounds 12–16.
        System.out.println("milestone (share of the ramp)   this build   rounds 12-16");
        assertTrue("the outgoing track must reach -30 dB before two thirds of the ramp",
                shareAt(-30d) <= 0.66d);
        assertTrue("... at least a quarter of the ramp earlier than the old shape did",
                shareAt(-30d) <= oldShareAt(-30d) - 0.25d);
        assertTrue("and it must be inaudible with a fifth of the ramp still to go",
                shareAt(FadeCurve.INAUDIBLE_DB) <= 0.80d);
        assertTrue("... which is also earlier than the old shape's own inaudible point",
                shareAt(FadeCurve.INAUDIBLE_DB) <= oldShareAt(FadeCurve.INAUDIBLE_DB) - 0.15d);
    }

    /** The share of a 15 s ramp at which the shipped shape crosses {@code wantDb}. */
    private static double shareAt(double wantDb) {
        return shareAt(wantDb, false);
    }

    /** The same for the shape rounds 12–16 shipped ({@code cos(t^2.6)} with a quarter-ramp
     *  release window). Kept here only so the "earlier" claim is measured against the real
     *  previous shape rather than asserted. */
    private static double oldShareAt(double wantDb) {
        return shareAt(wantDb, true);
    }

    private static double shareAt(double wantDb, boolean old) {
        for (int i = 0; i <= 10_000; i++) {
            float t = i / 10_000f;
            if (FadeCurve.gainDb(old ? oldOutGain(t) : FadeCurve.DJ_BLEND.outGain(t)) <= wantDb) {
                return t;
            }
        }
        return 1d;
    }

    /** Rounds 12–16's outgoing shape, for the before/after comparison only. */
    private static float oldOutGain(float t) {
        if (t >= 0.99f) return 0f;
        double hold = Math.cos(Math.pow(t, 2.6d) * Math.PI / 2d);
        if (t <= 0.75d) return (float) hold;
        double u = (t - 0.75d) / 0.25d;
        return (float) (hold * 0.5d * (1d + Math.cos(Math.PI * u)));
    }

    @Test
    public void theDjShapeEndsInAHeldZeroSoTheReleaseCannotBeHeardAsACut() {
        // A released player drops whatever is still buffered in it, so the gain at the
        // instant of the release IS the sound of the release: the promotion runs on the
        // first tick at t >= 1, up to one ramp tick after the ramp's end, and the outgoing
        // player must have been silent for a stretch before that instant and not merely at
        // it. The curve's own guarantee is the last OUT_SILENT_TAIL of the ramp held at
        // exactly zero, which is what this pins — with the discontinuity at its head on the
        // far side of the inaudible floor, i.e. the step into the held zero is silent too.
        assertTrue("the ramp's end must be silent", FadeCurve.DJ_BLEND.outGain(1f) == 0f);
        assertTrue("... and held there, not just reached",
                FadeCurve.DJ_BLEND.outGain(0.995f) == 0f && FadeCurve.DJ_BLEND.outGain(0.99f) == 0f);
        // Where the held zero starts, the exit was already below the floor — and by a margin,
        // so "at or below -60 dB" is never a question of the last bit of a logarithm.
        float atHead = FadeCurve.DJ_BLEND.outGain(0.9899f);
        assertTrue("the step into the held zero must itself be inaudible, was "
                        + db(atHead), FadeCurve.gainDb(atHead) <= FadeCurve.INAUDIBLE_DB - 5f);
        // No tick of the outgoing track's exit may end up louder than the one before it:
        // a tail that rose anywhere would be a level bump inside the fade.
        float previous = FadeCurve.DJ_BLEND.outGain(0.5f);
        for (int i = 501; i <= 1000; i++) {
            float gain = FadeCurve.DJ_BLEND.outGain(i / 1000f);
            assertTrue("the outgoing track's exit must not rise (t=" + (i / 1000f) + ": "
                            + previous + " -> " + gain + ")", gain <= previous + 1e-6f);
            previous = gain;
        }
        // And the level the promotion's assertion checks is the one the curve promises: the
        // floor is a real floor, not "anything below one".
        assertTrue("the inaudible floor must be an order of magnitude below anything audible",
                FadeCurve.INAUDIBLE_DB <= -60f);
        assertEquals(FadeCurve.INAUDIBLE_DB, (float) FadeCurve.gainDb(0f), 1e-4f);
        // The held stretch the backend prints on its release line is the curve's own
        // number, and it must be the truth about this shape: every sample inside it is
        // exactly zero, the sample just before it is already inaudible, and the shapes
        // that do not claim one do not have one.
        float tail = FadeCurve.DJ_BLEND.outSilentTail();
        assertTrue("the DJ shape holds a stretch of exactly zero at its end, was " + tail,
                tail > 0.005f && tail < 0.05f);
        for (int i = 0; i <= 100; i++) {
            float t = (1f - tail) + tail * i / 100f;
            assertEquals("t=" + t + " is inside the held zero", 0f, FadeCurve.DJ_BLEND.outGain(t), 0f);
        }
        assertTrue("the step into the held zero is inaudible, was "
                        + db(FadeCurve.DJ_BLEND.outGain(1f - tail - 0.001f)),
                FadeCurve.gainDb(FadeCurve.DJ_BLEND.outGain(1f - tail - 0.001f))
                        <= FadeCurve.INAUDIBLE_DB);
        assertEquals("the shapes that fade to the end promise no held stretch",
                0f, FadeCurve.EQUAL_POWER.outSilentTail(), 0f);
        assertEquals(0f, FadeCurve.LINEAR.outSilentTail(), 0f);
    }

    /** A gain as dB, for an assertion message that says how far off it was. */
    private static String db(float gain) {
        return String.format(java.util.Locale.US, "%.1f dB", FadeCurve.gainDb(gain));
    }

    @Test
    public void theDjShapesBothAudibleWindowIsDeliberatelyTheSmallOne() {
        // ⚠️ Round 17 reverses the claim this test used to make. Until round 16 the staged shape
        // won on "both tracks audible at once" (68% against the symmetric pair's 41%) and this
        // test asserted that it must. The measurement was right and the target was wrong: both
        // tracks at their own level is exactly the "人声混合得很乱" the user reported, because the
        // outgoing track's vocals cannot be removed from it. What the shape buys now is the
        // opposite, and the numbers are the ones in its own doc.
        long symmetric = FadeCurve.EQUAL_POWER.bothAudibleMs(LONG_MS);
        long dj = FadeCurve.DJ_BLEND.bothAudibleMs(LONG_MS);
        // The symmetric pair's share is the "feels short" defect written as a number:
        // gains within 6 dB of each other only where cos and sin are within a factor
        // of two, i.e. t in about [0.30, 0.70].
        assertTrue("the symmetric ramp should be both-audible for about two fifths of"
                        + " the overlap, was " + symmetric + "ms of " + LONG_MS + "ms",
                symmetric >= 5_800L && symmetric <= 6_500L);
        // The staged one spends about a sixth of the window with both tracks within 6 dB: the
        // stretch where the outgoing track is still descending into its bed and the incoming
        // one has arrived. Everything after that is one track plus the other one's bed.
        assertTrue("the DJ shape should be both-audible for about a sixth of the overlap, was "
                        + dj + "ms of " + LONG_MS + "ms ("
                        + FadeCurve.DJ_BLEND.bothAudibleText(LONG_MS) + ")",
                dj >= 2_000L && dj <= 3_200L);
        assertTrue("... which is deliberately less than the symmetric pair's share, because"
                        + " 'both audible' now means 'both at a level the listener compares'",
                dj < symmetric);
        // The shape is not a fade, though: the window still exists, and the outgoing track is
        // inside it rather than gone at the start.
        assertTrue("the window must not vanish: " + dj, dj >= 1_500L);
        float out = FadeCurve.DJ_BLEND.outGain(0.2f);
        float in = FadeCurve.DJ_BLEND.inGain(0.2f);
        assertTrue("the two must actually meet near a fifth of the ramp (out=" + out + ", in="
                        + in + ")", Math.abs(20d * Math.log10(out / in)) <= 6d);
    }

    @Test
    public void everyCurveStartsSilentOnTheIncomingSideAndEndsOnIt() {
        for (FadeCurve curve : FadeCurve.values()) {
            assertTrue(curve + " should start with the incoming track silent",
                    curve.inGain(0f) <= 0.02f);
            assertTrue(curve + " should start with the outgoing track at full level",
                    curve.outGain(0f) >= 0.98f);
            assertTrue(curve + " should end with the incoming track at full level",
                    curve.inGain(1f) >= 0.98f);
            assertTrue(curve + " should end with the outgoing track silent",
                    curve.outGain(1f) <= 0.02f);
        }
    }

    @Test
    public void theSummedPowerOfTheBlendNeverLiftsItAndOnlyDipsBriefly() {
        // Two tracks at once used to be louder than one, and the staged shape deliberately paid
        // for its both-audible window in summed power — up to +2.3 dB in the middle, which is
        // the "音量跳" risk. With the outgoing track taken down to a bed the sum no longer
        // exceeds one track's level anywhere (+0.2 dB at the very start, where the outgoing
        // track is still at unity and the incoming one has just begun), and the only shape
        // left to watch is the trough: the outgoing track leaves faster than the incoming one
        // arrives, so the blend dips by about 1.9 dB at a fifth of the ramp.
        double worst = 0d;
        double lowest = Double.MAX_VALUE;
        for (int i = 0; i <= 1000; i++) {
            float t = i / 1000f;
            double out = FadeCurve.DJ_BLEND.outGain(t);
            double in = FadeCurve.DJ_BLEND.inGain(t);
            double power = out * out + in * in;
            worst = Math.max(worst, power);
            lowest = Math.min(lowest, power);
        }
        double peakDb = 10d * Math.log10(worst);
        double troughDb = 10d * Math.log10(Math.max(1e-9, lowest));
        System.out.printf(java.util.Locale.US,
                "summed blend power: peak %+.2f dB, trough %.2f dB%n", peakDb, troughDb);
        assertTrue("the blend must never sum above one track by more than a dB, was "
                + String.format(java.util.Locale.US, "%.2f", peakDb) + "dB", peakDb <= 1.0d);
        assertTrue("... and the trough must stay under 3 dB, so the blend does not dip"
                        + " audibly, was " + String.format(java.util.Locale.US, "%.2f", troughDb)
                        + "dB", troughDb >= -3.0d);
    }

    @Test
    public void aBlendLongEnoughToBeHeardAsAMixIsGivenTheDjShape() {
        TransitionPlan named = TransitionPlan.of(TransitionKind.CROSSFADE,
                TransitionPlan.OVERLAP_LONG_MS, null, "test");
        assertEquals(FadeCurve.DJ_BLEND, named.curveOr(FadeCurve.EQUAL_POWER));
        // A plan that named its own curve still wins, so the AI or the chooser can
        // overrule the shape.
        TransitionPlan explicit = TransitionPlan.of(TransitionKind.CROSSFADE,
                TransitionPlan.OVERLAP_LONG_MS, FadeCurve.EQUAL_POWER, "test");
        assertEquals(FadeCurve.EQUAL_POWER, explicit.curveOr(FadeCurve.DJ_BLEND));
        // A quick fade (one second) has no middle to hold, so the settings decide.
        TransitionPlan quick = TransitionPlan.of(TransitionKind.QUICK_FADE,
                TransitionKind.QUICK_FADE.overlapMs(), null, "test");
        assertEquals(FadeCurve.LINEAR, quick.curveOr(FadeCurve.LINEAR));
    }

    @Test
    public void aRampOfNothingHasNothingBothAudible() {
        assertEquals(0L, FadeCurve.DJ_BLEND.bothAudibleMs(0L));
        assertEquals(0L, FadeCurve.EQUAL_POWER.bothAudibleMs(-5L));
    }

    /**
     * Round 20: when the outgoing track has left the passage, as a share of the ramp. This is the
     * instant the incoming track's rendered file lets its voice back in ({@code DjEdit.vocalOutMs}),
     * so it is the one number that decides whether the listener hears a voice too early (over a
     * still-audible outgoing track) or too late (the 「切割人声有点切太多了」 report).
     */
    @Test
    public void theOutgoingTracksOwnExitIsReadOffEachShape() {
        // The DJ shape: 10 dB down by three tenths and at the -60 dB floor by 75.2% of the ramp —
        // the number the round's device logs print (12427ms of a 16525ms ramp).
        double dj = FadeCurve.DJ_BLEND.outLeftAt(LONG_MS);
        assertEquals("the DJ shape leaves at 75.2% of its ramp", 0.752d, dj, 0.002d);
        assertTrue("... and that is where its own level is at the floor, not before",
                FadeCurve.gainDb(FadeCurve.DJ_BLEND.outGain((float) dj))
                        <= FadeCurve.INAUDIBLE_DB);
        // The symmetric shapes hold the outgoing track at its own level to the ramp's own end —
        // their gain only passes the floor in the ramp's last thousandth (15 ms of a 15 s blend),
        // so the whole ramp is the two-voice stretch and the voice may not come back inside it.
        for (FadeCurve symmetric : new FadeCurve[] {FadeCurve.LINEAR, FadeCurve.EQUAL_POWER}) {
            double at = symmetric.outLeftAt(LONG_MS);
            assertTrue(symmetric + " fades the outgoing track only at the ramp's own end, so its"
                    + " answer is the whole ramp, was " + at, at > 0.998d);
            assertTrue(symmetric + ": the window is the blend itself, to within one 25ms frame",
                    DjEdit.vocalOutMs(LONG_MS, symmetric) >= LONG_MS - 25L);
        }
        // A fusion's hand-over is a duration rather than a share, so its answer moves with the
        // ramp: JUNCTION_XFADE_MS of a 15s blend, and the whole of a ramp no longer than itself.
        // (Round 31 raised that duration from two seconds to four, so that the outgoing track's
        // voice leaves over a phrase instead of a beat — see FadeCurve's own note.)
        assertEquals(FadeCurve.JUNCTION_XFADE_MS / 15_000d, FadeCurve.FUSION.outLeftAt(LONG_MS),
                0.001d);
        // One tick short of the whole ramp, which is the same "to within a sample" reading the two
        // symmetric shapes are held to above: a ramp no longer than the hand-over is entirely it.
        assertTrue("a ramp this short is the fade itself, was "
                        + FadeCurve.FUSION.outLeftAt(FadeCurve.JUNCTION_XFADE_MS),
                FadeCurve.FUSION.outLeftAt(FadeCurve.JUNCTION_XFADE_MS) > 0.998d);
        // And the three lengths the round is judged on, on the shape every ordinary boundary gets:
        // 75.2% of the blend, to the millisecond the search resolves it to.
        assertEquals("a 4s blend", 3_008L, DjEdit.vocalOutMs(4_000L, FadeCurve.DJ_BLEND), 3L);
        assertEquals("the user's 17s blend", 12_785L,
                DjEdit.vocalOutMs(17_000L, FadeCurve.DJ_BLEND), 3L);
        assertEquals("a 30s blend", 22_562L,
                DjEdit.vocalOutMs(30_000L, FadeCurve.DJ_BLEND), 3L);
        // A ramp of nothing has no stretch in it at all.
        assertEquals(0L, DjEdit.vocalOutMs(0L, FadeCurve.DJ_BLEND));
        assertEquals(0L, DjEdit.vocalOutMs(-1L, FadeCurve.DJ_BLEND));
    }

    /** The search {@link FadeCurve#outLeftAt} runs is only valid while the shape is monotone
     *  non-increasing — the property every curve here is documented to have, checked at the
     *  resolution the measurement itself uses so a future shape cannot quietly break it. */
    @Test
    public void everyShapesOutgoingLevelNeverRises() {
        for (FadeCurve curve : FadeCurve.values()) {
            float previous = Float.MAX_VALUE;
            for (int i = 0; i <= 1000; i++) {
                float t = i / 1000f;
                // The raw gain, not its dB: `gainDb` floors at INAUDIBLE_DB, and that floor is a
                // constant a shape still falling to zero would read as a rise.
                float gain = curve.outGain(t, LONG_MS);
                assertTrue(curve + " rose at t=" + t + ": " + previous + " -> " + gain,
                        gain <= previous + 1e-9f);
                previous = gain;
            }
        }
    }
}
