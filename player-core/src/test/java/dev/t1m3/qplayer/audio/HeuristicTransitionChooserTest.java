package dev.t1m3.qplayer.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.t1m3.qplayer.model.Track;

/**
 * The automatic rules, branch by branch — and, since round 17, the requirement that
 * <b>several kinds are actually used</b>: every kind in the catalog has to be reachable from
 * evidence the app really has, or "the auto path produces CROSSFADE almost always" is a fact
 * about the code rather than a complaint about it.
 *
 * <p>The evidence each branch is reachable from, one line each:
 * <ul>
 *   <li>{@link TransitionKind#CUT} — a side that cannot go on a second player, a boundary that
 *       has already arrived, or a length nobody knows (the safe fallback, unchanged);</li>
 *   <li>{@link TransitionKind#SILENCE_TRIM} — the outgoing track's tail <em>measured</em> to be
 *       silent ({@code SilenceProfile.tailMs()});</li>
 *   <li>{@link TransitionKind#QUICK_FADE} — a track under {@code SHORT_TRACK_MS};</li>
 *   <li>{@link TransitionKind#FADE_OUT_IN} — <b>new in round 17</b>: the pair's own beat grids
 *       and keys measured to clash ({@link TransitionContext.PairFit#overlapsBadly()});</li>
 *   <li>{@link TransitionKind#CROSSFADE} — an ordinary pair, <b>or round 19</b>: the incoming
 *       track's own rendered edit is a fusion, which decides the kind whatever the pair's numbers
 *       say (see {@link #aFusionEditDecidesTheKindEvenWhenTheTemposAreUnrelated}).</li>
 * </ul>
 */
public class HeuristicTransitionChooserTest {

    private static final long LONG_MS = 210_000L;

    private static Track track(String title, long durationMs) {
        Track t = new Track();
        t.source = Track.Source.NETEASE;
        t.neteaseId = Math.abs(title.hashCode()) + 1L;
        t.title = title;
        t.durationMs = durationMs;
        t.albumId = title.hashCode();
        return t;
    }

    /** A tiny builder so each case below reads as the one fact it is about. */
    private static TransitionContext ctx(Track a, Track b, long remaining, boolean streamableA,
                                         boolean streamableB, long tailSilence, long headSilence,
                                         TransitionContext.PairFit fit) {
        return new TransitionContext(a, b, remaining, a.durationMs, streamableA, streamableB,
                tailSilence, headSilence, fit);
    }

    /** The same case with the one round-19 fact set: the incoming track's rendered edit is a
     *  fusion (the file the deck will play carries both backgrounds, cut at the outgoing
     *  track's own junction bar line). */
    private static TransitionContext fusionCtx(Track a, Track b, long remaining, long tailSilence,
                                               TransitionContext.PairFit fit) {
        return new TransitionContext(a, b, remaining, a.durationMs, true, true,
                tailSilence, TransitionContext.SILENCE_UNKNOWN, fit, true);
    }

    /** The pair the device's own profiles produce and the round-17 rule answers
     *  {@link TransitionKind#FADE_OUT_IN} for: 120 against 145BPM is x1.2083, outside the
     *  x1.08 clamp, with a key distance nothing can bring together. */
    private static TransitionContext.PairFit clashingPair() {
        return new TransitionContext.PairFit(true, true, true, false, 0.79d,
                "keys measured and clashing (chroma distance 0.79, best allowed shift +1 reaches"
                        + " 0.71), tempo not lockable (120.0 vs 145.0BPM, x1.2083 (outside the"
                        + " x1.08 clamp))", "");
    }

    @Test
    public void anOrdinaryPairGetsTheLongDjOverlap() {
        Track a = track("a", LONG_MS);
        Track b = track("b", LONG_MS);
        TransitionContext ctx = ctx(a, b, 60_000L, true, true,
                TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                TransitionContext.PairFit.UNMEASURED);
        HeuristicTransitionChooser chooser = new HeuristicTransitionChooser();
        assertEquals(TransitionKind.CROSSFADE, chooser.choose(ctx));
        TransitionPlan plan = chooser.plan(ctx);
        assertEquals(TransitionKind.CROSSFADE, plan.kind());
        assertTrue("the ordinary blend is long: " + plan.overlapMs(), plan.overlapMs() >= 15_000L);
        assertEquals("... and it is named as the DJ shape", FadeCurve.DJ_BLEND,
                plan.curveOr(FadeCurve.EQUAL_POWER));
        System.out.println("ordinary pair: " + plan.kind() + " " + plan.overlapMs() + "ms — "
                + plan.decidedBy());
    }

    @Test
    public void aMeasuredSilentTailGetsATrim() {
        Track a = track("a", LONG_MS);
        Track b = track("b", LONG_MS);
        TransitionContext ctx = ctx(a, b, 60_000L, true, true, 4_600L, 0L,
                TransitionContext.PairFit.UNMEASURED);
        HeuristicTransitionChooser chooser = new HeuristicTransitionChooser();
        assertEquals(TransitionKind.SILENCE_TRIM, chooser.choose(ctx));
        assertTrue(chooser.plan(ctx).decidedBy().contains("measured to end in"));
        System.out.println("measured tail: " + chooser.plan(ctx).decidedBy());
    }

    @Test
    public void aShortTrackGetsTheQuickFade() {
        Track a = track("a", 60_000L);
        Track b = track("b", LONG_MS);
        TransitionContext ctx = ctx(a, b, 30_000L, true, true,
                TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                TransitionContext.PairFit.UNMEASURED);
        HeuristicTransitionChooser chooser = new HeuristicTransitionChooser();
        assertEquals(TransitionKind.QUICK_FADE, chooser.choose(ctx));
        System.out.println("short track: " + chooser.plan(ctx).decidedBy());
    }

    @Test
    public void aPairWhoseMaterialClashesIsPlayedOneAfterTheOther() {
        Track a = track("a", LONG_MS);
        Track b = track("b", LONG_MS);
        // Both measured, and both measured to fight: the keys nothing can bring together
        // (0.79 apart, the best allowed shift only reaching 0.71) and the tempos outside the
        // clamp (120 against 145BPM needs x1.208). Neither number is invented — that is what
        // the controller's own pairFitOf computes from the two cached profiles.
        TransitionContext.PairFit clashing = new TransitionContext.PairFit(true, true, true, false,
                0.79d, "keys measured and clashing (chroma distance 0.79, best allowed shift +1"
                        + " reaches 0.71), tempo not lockable (120.0 vs 145.0BPM, x1.2083 (outside"
                        + " the x1.08 clamp))", "");
        TransitionContext ctx = ctx(a, b, 60_000L, true, true,
                TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN, clashing);
        HeuristicTransitionChooser chooser = new HeuristicTransitionChooser();
        assertEquals(TransitionKind.FADE_OUT_IN, chooser.choose(ctx));
        TransitionPlan plan = chooser.plan(ctx);
        assertEquals(TransitionKind.FADE_OUT_IN, plan.kind());
        assertEquals("a sequential fade needs no overlap at all", 0L, plan.overlapMs());
        assertFalse("... and has no second player to prepare", plan.kind().needsSecondPlayer());
        assertTrue("the reason carries the measurement: " + plan.decidedBy(),
                plan.decidedBy().contains("0.79") && plan.decidedBy().contains("x1.2083"));
        System.out.println("clashing pair: " + plan.kind() + " — " + plan.decidedBy());
    }

    @Test
    public void aMeasuredClashInKeysAloneIsEnoughAndAMeasuredLockIsNot() {
        Track a = track("a", LONG_MS);
        Track b = track("b", LONG_MS);
        HeuristicTransitionChooser chooser = new HeuristicTransitionChooser();
        // Keys clashing, tempos lockable: still "overlaps badly" — the pair would be in two keys
        // for a whole blend, which is the "听着有点割裂" the user reported.
        TransitionContext keysOnly = ctx(a, b, 60_000L, true, true,
                TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                new TransitionContext.PairFit(true, true, true, true, 0.71d, "keys clash", ""));
        assertEquals(TransitionKind.FADE_OUT_IN, chooser.choose(keysOnly));
        // Tempos not lockable, keys fine: same answer, for the rhythm.
        TransitionContext rhythmOnly = ctx(a, b, 60_000L, true, true,
                TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                new TransitionContext.PairFit(true, false, true, false, 0.12d, "tempos clash", ""));
        assertEquals(TransitionKind.FADE_OUT_IN, chooser.choose(rhythmOnly));
        // Measured keys that do NOT clash, and a tempo that can be locked: an ordinary blend.
        TransitionContext fine = ctx(a, b, 60_000L, true, true,
                TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                new TransitionContext.PairFit(true, false, true, true, 0.18d, "keys sit"
                        + " together, tempo lockable", ""));
        assertEquals(TransitionKind.CROSSFADE, chooser.choose(fine));
        // Nothing measured at all: no rule fires, so the blend happens — an unmeasured pair is
        // not evidence of a clash, and treating it as one would turn the whole library into
        // sequential fades the first time it is played.
        TransitionContext unmeasured = ctx(a, b, 60_000L, true, true,
                TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                TransitionContext.PairFit.UNMEASURED);
        assertEquals(TransitionKind.CROSSFADE, chooser.choose(unmeasured));
        assertFalse(TransitionContext.PairFit.UNMEASURED.overlapsBadly());
    }

    @Test
    public void aTempoRelationIsNotAClashAndARandomPairIs() {
        // The numbers the round-17 distribution harness put in front of this rule, from the
        // device's own 36 measured profiles: 123.1 against 61.0 is x2.02 — the same beat read an
        // octave apart (half this library is reported that way; round 8 measured it) — and
        // treating it as a clash is how the rule first answered "these two do not mix" for a pair
        // that mixes perfectly. 71.6 against 61.0 (x1.17) is genuinely unrelated.
        assertTrue("x2 is the same groove", BeatProfile.nearRelativeTempo(2.0170d));
        assertTrue("... and so is x0.5", BeatProfile.nearRelativeTempo(0.4961d));
        assertTrue("... and x1.5 / x2/3 / x3 / x1/3",
                BeatProfile.nearRelativeTempo(1.5d) && BeatProfile.nearRelativeTempo(2d / 3d)
                        && BeatProfile.nearRelativeTempo(3d) && BeatProfile.nearRelativeTempo(1d / 3d));
        assertTrue("the same tempo is the same groove", BeatProfile.nearRelativeTempo(1d));
        assertTrue("... within the tolerance", BeatProfile.nearRelativeTempo(1.05d));
        assertTrue("... and at its edge", BeatProfile.nearRelativeTempo(1.0799d));
        assertFalse("... but not past it", BeatProfile.nearRelativeTempo(1.0801d));
        assertFalse("an unrelated tempo is not related", BeatProfile.nearRelativeTempo(1.1736d));
        assertFalse("... nor is 1.3, which is not a third, a half or a double",
                BeatProfile.nearRelativeTempo(1.3d));
        // The pair rule uses it: 123.1 vs 61.0 (the harness's false positive) is not a clash, so
        // it blends; 71.6 vs 61.0 is, so it is played one after the other.
        Track a = track("a", LONG_MS);
        Track b = track("b", LONG_MS);
        HeuristicTransitionChooser chooser = new HeuristicTransitionChooser();
        TransitionContext octaveApart = ctx(a, b, 60_000L, true, true,
                TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                new TransitionContext.PairFit(true, false, true, true, 0.21d,
                        "tempo x2.0170 (the same groove)", ""));
        assertEquals(TransitionKind.CROSSFADE, chooser.choose(octaveApart));
        TransitionContext unrelated = ctx(a, b, 60_000L, true, true,
                TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                new TransitionContext.PairFit(true, false, true, false, 0.22d,
                        "tempo x1.1736 (unrelated)", ""));
        assertEquals(TransitionKind.FADE_OUT_IN, chooser.choose(unrelated));
    }

    @Test
    public void aMeasuredSilentTailBeatsTheShortTrackRule() {
        // Round 17 swapped these two rules: evidence about THIS boundary (the outgoing track's
        // own measured silence) now outranks the length heuristic. Before, a 60 s track that
        // ends in 5 s of silence got a quick fade into the silence.
        Track a = track("a", 60_000L);
        Track b = track("b", LONG_MS);
        TransitionContext ctx = ctx(a, b, 30_000L, true, true, 5_000L, 0L,
                TransitionContext.PairFit.UNMEASURED);
        HeuristicTransitionChooser chooser = new HeuristicTransitionChooser();
        assertEquals(TransitionKind.SILENCE_TRIM, chooser.choose(ctx));
    }

    @Test
    public void whatCannotBeTransitionedIsStillCut() {
        Track a = track("a", LONG_MS);
        Track b = track("b", LONG_MS);
        HeuristicTransitionChooser chooser = new HeuristicTransitionChooser();
        assertEquals("a BILI or LOCAL side cannot go on a second player",
                TransitionKind.CUT,
                chooser.choose(ctx(a, b, 60_000L, false, true,
                        TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.PairFit.UNMEASURED)));
        assertEquals("a boundary that has arrived",
                TransitionKind.CUT,
                chooser.choose(ctx(a, b, 1_000L, true, true,
                        TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.PairFit.UNMEASURED)));
        Track unknownLength = track("c", 0L);
        assertEquals("a length nobody knows",
                TransitionKind.CUT,
                chooser.choose(ctx(a, unknownLength, 60_000L, true, true,
                        TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.PairFit.UNMEASURED)));
        // ⚠️ Round 19, and the point of this assertion: a FUSION edit may not resurrect a
        // boundary that cannot be armed. The three clauses above are physical — no second
        // player, no room left, no length to plan against — and the file being the transition
        // does not create any of them: the same three contexts, with the fusion fact set, are
        // still CUT (the controller would otherwise arm a plan it cannot perform, which is the
        // one thing a chooser is never allowed to answer).
        assertEquals("a fusion does not make an unstreamable side performable",
                TransitionKind.CUT,
                chooser.choose(new TransitionContext(a, b, 60_000L, a.durationMs, false, true,
                        TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.PairFit.UNMEASURED, true)));
        assertEquals("... nor one whose boundary has already arrived",
                TransitionKind.CUT,
                chooser.choose(fusionCtx(a, b, 1_000L, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.PairFit.UNMEASURED)));
        assertEquals("... nor one whose length nobody knows",
                TransitionKind.CUT,
                chooser.choose(new TransitionContext(a, unknownLength, 60_000L, a.durationMs,
                        true, true, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.SILENCE_UNKNOWN, TransitionContext.PairFit.UNMEASURED,
                        true)));
    }

    /**
     * Round 19's rule, and the whole reason it exists: the incoming track's own rendered file
     * carries the transition, so the transition happens even for a pair the chooser's tempo rule
     * would have answered "do not put these two on top of each other".
     *
     * <p>The pair is the round-17 distribution's own {@code FADE_OUT_IN} case (a measured key
     * clash and tempos outside the clamp) — the pair the user hears as 「淡入淡出」 — and the only
     * difference between the two halves of this test is the one fact
     * {@link TransitionContext#incomingEditIsFusion()}.
     */
    @Test
    public void aFusionEditDecidesTheKindEvenWhenTheTemposAreUnrelated() {
        Track a = track("a", LONG_MS);
        Track b = track("b", LONG_MS);
        HeuristicTransitionChooser chooser = new HeuristicTransitionChooser();
        // The behaviour that must not change: the same pair with no rendered edit.
        TransitionContext plain = ctx(a, b, 60_000L, true, true,
                TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                clashingPair());
        assertEquals(TransitionKind.FADE_OUT_IN, chooser.choose(plain));
        assertEquals("and the sequential fade is what the boundary plays",
                0L, chooser.plan(plain).overlapMs());
        // The same pair whose incoming track's edit is a fusion: an overlap, and specifically
        // the fusion's own shape.
        TransitionContext fused = fusionCtx(a, b, 60_000L, TransitionContext.SILENCE_UNKNOWN,
                clashingPair());
        assertEquals(TransitionKind.CROSSFADE, chooser.choose(fused));
        TransitionPlan plan = chooser.plan(fused);
        assertEquals(TransitionKind.CROSSFADE, plan.kind());
        assertTrue("the kind is an overlapping one, which is what makes the boundary play the"
                        + " edit at all (resolveIncomingSource's arming gate)",
                plan.kind().overlapping());
        assertTrue("... and it needs the second player the fusion edit is prepared on",
                plan.kind().needsSecondPlayer());
        assertTrue("the overlap is the ordinary long one, not a seam: " + plan.overlapMs(),
                plan.overlapMs() >= 15_000L);
        assertEquals("the fusion's own shape, not the DJ blend — the two decks change over once"
                        + " and are constant either side of it",
                FadeCurve.FUSION, plan.curveOr(FadeCurve.EQUAL_POWER));
        assertTrue("the reason says what decided it: " + plan.decidedBy(),
                plan.decidedBy().contains("THE EDIT WON")
                        && plan.decidedBy().contains("STEM PASSAGE")
                        && plan.decidedBy().contains("junction bar line"));
        System.out.println("fusion pair: " + plan.kind() + " " + plan.overlapMs() + "ms — "
                + plan.decidedBy());
    }

    /**
     * The fusion rule is not only about the tempo clash: it comes before every <em>shape</em>
     * rule, because both of those would discard a gesture the render has already made — a trim
     * never plays the file at all (it is not an overlapping kind, so the deck plays the track's
     * own master), and a quick fade would give the file's own two-bar gesture a one-second
     * window.
     */
    @Test
    public void aFusionEditOutranksTheTrimAndTheQuickFade() {
        Track a = track("a", LONG_MS);
        Track b = track("b", LONG_MS);
        HeuristicTransitionChooser chooser = new HeuristicTransitionChooser();
        // A measured silent tail: a trim with no edit...
        assertEquals(TransitionKind.SILENCE_TRIM,
                chooser.choose(ctx(a, b, 60_000L, true, true, 4_600L, 0L,
                        TransitionContext.PairFit.UNMEASURED)));
        // ... and the fusion's overlap when the incoming deck has one.
        TransitionContext fusedTail = fusionCtx(a, b, 60_000L, 4_600L,
                TransitionContext.PairFit.UNMEASURED);
        assertEquals(TransitionKind.CROSSFADE, chooser.choose(fusedTail));
        assertEquals(FadeCurve.FUSION, chooser.plan(fusedTail).curveOr(FadeCurve.LINEAR));
        // A short track: a quick fade with no edit...
        Track shortA = track("short-a", 60_000L);
        assertEquals(TransitionKind.QUICK_FADE,
                chooser.choose(ctx(shortA, b, 30_000L, true, true,
                        TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.PairFit.UNMEASURED)));
        // ... and the fusion's overlap when there is one (the render's own window is the
        // setting's blend length, so the file was cut for a full-length blend either way).
        assertEquals(TransitionKind.CROSSFADE,
                chooser.choose(fusionCtx(shortA, b, 30_000L, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.PairFit.UNMEASURED)));
    }

    /**
     * ⚠️ <b>Round 30: a SLAM counts, and it is the case this rule was written for.</b> The fact the
     * context carries is one bit — round 19 named it after the fusion it was built on — but the
     * renderer writes the same anchors ({@code -e}/{@code -j}/{@code -f}) for a slam, whose own
     * population is a pair in <em>no relation at all</em>: exactly the population rule 7's
     * {@code PairFit} answers {@link TransitionKind#FADE_OUT_IN} for. The two halves of this test are
     * the two halves of that device observation, on one hostile pair:
     *
     * <ul>
     *   <li><b>no edit</b> — the sequential fade stands, decided by the pair's own measurement (the
     *   {@code overlaps badly} line the device log carries), and nothing about round 30 changes
     *   it;</li>
     *   <li><b>the slam's edit on disk</b> — an <em>overlapping</em> kind with the passage's own
     *   curve, which is the whole of what "the file is armed" means on this side of the boundary:
     *   the controller's arming gate is {@code incomingMode == PARKED &&
     *   transitionKind.overlapping()} and then the same {@code djEditFor} lookup that set this fact
     *   ({@code PlayerController.resolveIncomingSource}), so a kind that is not overlapping is a
     *   rendered slam nobody ever plays.</li>
     * </ul>
     */
    @Test
    public void aSlamEditDecidesTheKindForAPairInNoRelationAtAll() {
        Track a = track("a", LONG_MS);
        Track b = track("b", LONG_MS);
        HeuristicTransitionChooser chooser = new HeuristicTransitionChooser();
        TransitionContext.PairFit unrelated = clashingPair();
        // No edit: the pair's own verdict, unchanged by round 30.
        TransitionContext noEdit = ctx(a, b, 60_000L, true, true,
                TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN, unrelated);
        assertEquals(TransitionKind.FADE_OUT_IN, chooser.choose(noEdit));
        assertTrue("and the reason is the pair's own measurement: " + chooser.plan(noEdit),
                chooser.plan(noEdit).decidedBy().contains("overlaps badly"));
        assertFalse("no edit means no stem passage", noEdit.incomingEditIsStemPassage());
        // The same pair whose incoming track's edit is a slam — the same one bit, because the
        // anchors the renderer writes are the same three.
        TransitionContext slam = fusionCtx(a, b, 60_000L, TransitionContext.SILENCE_UNKNOWN,
                unrelated);
        assertTrue("the slam's edit is the stem-passage fact (both shapes carry -e/-j)",
                slam.incomingEditIsStemPassage());
        assertEquals(TransitionKind.CROSSFADE, chooser.choose(slam));
        TransitionPlan plan = chooser.plan(slam);
        assertEquals(TransitionKind.CROSSFADE, plan.kind());
        assertTrue("an overlapping kind is what arms the file at all",
                plan.kind().overlapping());
        assertTrue("and it needs the second player the passage is prepared on",
                plan.kind().needsSecondPlayer());
        assertEquals("the passage's own shape, not the DJ blend", FadeCurve.FUSION,
                plan.curveOr(FadeCurve.DJ_BLEND));
        assertTrue("the line says the edit won, and names both shapes: " + plan.decidedBy(),
                plan.decidedBy().contains("THE EDIT WON") && plan.decidedBy().contains("slam"));
        // The capability rules stay above it: a boundary that cannot be armed at all is still CUT,
        // edit or no edit.
        assertEquals("too late in the track: no transition fits, rendered passage or not",
                TransitionKind.CUT,
                chooser.choose(fusionCtx(a, b, 1_000L, TransitionContext.SILENCE_UNKNOWN,
                        unrelated)));
        assertFalse("and a boundary nobody knows the length of is CUT too",
                chooser.choose(fusionCtx(a, track("unknown", 0L), 60_000L,
                        TransitionContext.SILENCE_UNKNOWN, unrelated)).overlapping());
        System.out.println("slam pair: " + plan.kind() + " " + plan.overlapMs() + "ms — "
                + plan.decidedBy());
    }

    /**
     * The other half of the promise: with no rendered edit — the ordinary world, which is every
     * boundary of a build without a stem model in it — every rule answers exactly what it
     * answered before round 19.
     */
    @Test
    public void withNoRenderedEditEveryRuleAnswersAsBefore() {
        Track a = track("a", LONG_MS);
        Track b = track("b", LONG_MS);
        HeuristicTransitionChooser chooser = new HeuristicTransitionChooser();
        Object[][] cases = {
                {ctx(a, b, 60_000L, true, true, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.SILENCE_UNKNOWN, TransitionContext.PairFit.UNMEASURED),
                    TransitionKind.CROSSFADE},
                {ctx(a, b, 60_000L, true, true, 4_600L, 0L, TransitionContext.PairFit.UNMEASURED),
                    TransitionKind.SILENCE_TRIM},
                {ctx(a, track("short", 40_000L), 20_000L, true, true,
                        TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.PairFit.UNMEASURED), TransitionKind.QUICK_FADE},
                {ctx(a, b, 60_000L, true, true, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.SILENCE_UNKNOWN, clashingPair()),
                    TransitionKind.FADE_OUT_IN},
        };
        for (Object[] c : cases) {
            TransitionContext context = (TransitionContext) c[0];
            assertFalse("a context nobody set the fusion fact on is not a fusion",
                    context.incomingEditIsFusion());
            assertEquals("no edit means the rule that used to answer", c[1], chooser.choose(context));
        }
    }

    @Test
    public void everyKindInTheCatalogIsReachableFromSomeEvidence() {
        // The point of the round: "several kinds are in use" as a property of the rules rather
        // than a hope. Each entry names the evidence that produces it.
        Track a = track("a", LONG_MS);
        Track b = track("b", LONG_MS);
        HeuristicTransitionChooser chooser = new HeuristicTransitionChooser();
        TransitionKind[] reachable = {
                chooser.choose(ctx(a, b, 1_000L, true, true, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.SILENCE_UNKNOWN, TransitionContext.PairFit.UNMEASURED)),
                chooser.choose(ctx(a, b, 60_000L, true, true, 3_000L, 0L,
                        TransitionContext.PairFit.UNMEASURED)),
                chooser.choose(ctx(track("d", 40_000L), b, 20_000L, true, true,
                        TransitionContext.SILENCE_UNKNOWN, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.PairFit.UNMEASURED)),
                chooser.choose(ctx(a, b, 60_000L, true, true, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.SILENCE_UNKNOWN,
                        new TransitionContext.PairFit(true, true, true, false, 0.8d, "clash", ""))),
                chooser.choose(ctx(a, b, 60_000L, true, true, TransitionContext.SILENCE_UNKNOWN,
                        TransitionContext.SILENCE_UNKNOWN, TransitionContext.PairFit.UNMEASURED)),
        };
        for (TransitionKind kind : TransitionKind.CHOICES) {
            boolean found = false;
            for (TransitionKind reachableOne : reachable) {
                if (reachableOne == kind) found = true;
            }
            assertTrue(kind + " is not reachable from any evidence", found);
        }
        System.out.println("all " + TransitionKind.CHOICES.size() + " kinds are reachable from"
                + " evidence the app measures: " + java.util.Arrays.toString(reachable));
    }
}
