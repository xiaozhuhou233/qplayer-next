package dev.t1m3.qplayer.audio;

import dev.t1m3.qplayer.model.Track;

/**
 * The default {@link TransitionChooser}: a fixed, explainable rule set over
 * metadata, no network and no randomness, so the same two tracks always get the
 * same transition and every choice can be argued for in one sentence.
 *
 * <p>The rules are ordered from "cannot be performed" to "what the pair itself
 * suggests", and every one of them may only return a kind the boundary can
 * actually perform — an answer that would need a second player for a BILI link
 * or a local file is downgraded here, not discovered later.
 *
 * <p>⚠️ <b>The default answer is an overlap.</b> Only four things are allowed to
 * refuse one, and all four are physical (a side that cannot go on a second player,
 * a boundary that has already arrived, a length nobody knows, and — for the shape
 * of the overlap, not for whether there is one — a track that is too short for it).
 * Nothing about tempo, key or a beat grid can refuse anything here: those only
 * decide whether the overlap can additionally be <em>aligned</em>, which is the
 * controller's business after this answer is given. An earlier round answered
 * {@link TransitionKind#SILENCE_TRIM} (a 250 ms seam) for every pair of long
 * tracks, which is the least audible transition there is — and because that is the
 * answer an ordinary library of long tracks got for every boundary, the whole
 * feature was heard as "nothing happened".
 *
 * <p>{@link TransitionKind#SILENCE_TRIM} is now chosen only on evidence: when the
 * outgoing track has actually been <em>measured</em> to trail more than
 * {@link #TRIM_TAIL_MIN_MS} of silence. There is nothing to overlap there — an
 * eight-second ramp would spend most of itself fading music into silence — so butting
 * the two contents together is demonstrably better, and the measurement that says so
 * is already in hand. Without that measurement the answer is the plain overlap.
 *
 * <p>⚠️ <b>Round 19: one rule is not about the pair at all — the incoming track's own
 * rendered edit.</b> When that edit is a <em>stem passage</em>
 * ({@link TransitionContext#incomingEditIsStemPassage()} — a fusion for a related pair, a slam for
 * one in no relation at all), the transition is already inside the file the incoming deck will
 * play, so the answer is {@link TransitionKind#CROSSFADE} whatever the pair's numbers say — that
 * is the rule at the top of {@link #choose}, and it is the one case where a measured clash does
 * <em>not</em> buy a sequential fade: there is no second tempo or key to clash with, because the
 * file carries both tracks' material itself. ⚠️ <b>Round 30 makes that rule cover the slam
 * explicitly</b>, because the pairs a slam exists for (unrelated tempo, clashing keys) are exactly
 * the pairs rule 7 answers {@link TransitionKind#FADE_OUT_IN} for — so before it, a rendered slam
 * was planned, written and then never played: the kind was not overlapping, so the controller never
 * armed the file and the plain fade ran instead (「速度无关的也要接，不要淡入淡出」 was answered by the
 * renderer and thrown away by this chooser).
 *
 * <p>To be replaced by an AI implementation later through
 * {@code PlayerController.setTransitionChooser(...)}; this class stays as the
 * fallback and as the definition of "sensible defaults" that implementation has
 * to beat.
 */
public final class HeuristicTransitionChooser implements TransitionChooser {

    /** Below this a track counts as short: a fifteen-second overlap is a real
     *  fraction of it,
     *  and a short track's tail is usually the whole arrangement rather than an
     *  outro to talk over. Short pairs still overlap — just briefly. */
    public static final long SHORT_TRACK_MS = 90_000L;

    /** With less than this left, no transition fits: the overlap would have to
     *  start in the past, and the ordinary cut is what the listener is about to
     *  hear anyway. */
    public static final long MIN_REMAINING_MS = 2_000L;

    /**
     * How much measured trailing silence makes a trim the better answer.
     *
     * <p>A trim's whole case is the outgoing track's dead air: the seam is placed at
     * the end of its <em>content</em>, so the silence is never played and (unlike any
     * overlap) the incoming track's own head silence is skipped too. Below this the
     * tail is short enough that overlapping it is the thing that sounds like a mix,
     * so the default overlap wins; above it, an eight-second ramp would be seven
     * seconds of fading nothing.
     *
     * <p>Measured, never assumed: the value comes from the silence profiler's cache
     * ({@link TransitionContext#outgoingTailSilenceMs()}), and an unmeasured track
     * simply does not get the trim. 1200 ms is comfortably past what a normal
     * master's own decay or reverb tail looks like to a -46 dBFS detector (the
     * threshold the profiler uses), so a track only qualifies when its file really
     * does run on after the music.
     */
    public static final long TRIM_TAIL_MIN_MS = 1_200L;

    @Override
    public TransitionKind choose(TransitionContext ctx) {
        if (ctx == null) return TransitionKind.CUT;
        // 1. Both ends have to be ordinary streams. This is the one rule that is
        //    about capability rather than taste: BILI and LOCAL cannot be put on a
        //    second player (a short-lived link whose picture belongs to the active
        //    player; a filesystem decoder for no gain), so anything overlapping is
        //    off the table.
        if (!ctx.outgoingStreamable() || !ctx.incomingStreamable()) return TransitionKind.CUT;
        // 2. Too close to the end for any transition to be prepared in time.
        if (ctx.remainingMs() < MIN_REMAINING_MS) return TransitionKind.CUT;
        // 3. A length nobody knows is a length no transition can plan against —
        //    and an unknown length is exactly the track whose own completion
        //    callback is most likely to arrive while a ramp is still running. The
        //    historical hard cut is the only honest answer.
        if (!ctx.hasBothLengths()) return TransitionKind.CUT;
        // 4. ⚠️ Round 19, sharpened by round 30: an existing stem passage decides the kind — a
        //    FUSION (`-e/-j/-f` from a related pair) or a SLAM (the same three markers from a pair
        //    whose grids are in NO relation: one bar, every element changing hands on one line).
        //    The incoming track's own rendered file does not carry just that track — it carries a
        //    passage the renderer built out of BOTH backgrounds, spliced at the outgoing track's own
        //    junction bar line and the incoming file's own entry (`StemFusion`), so the transition is
        //    already inside the file and the two live decks only hand over once (a
        //    JUNCTION_XFADE_MS equal-gain fade, at that bar line — the file carries the outgoing
        //    track's own material across the whole of it, so that deck plays out instead of being
        //    stopped). Nothing this chooser could add from the pair's numbers is worth anything
        //    there:
        //
        //      • an overlap is what plays that file at all — the controller only gives it
        //        to the incoming deck when the kind is overlapping (`resolveIncomingSource`),
        //        and a non-overlapping kind therefore throws the whole rendered gesture away
        //        and plays the track's own master instead (FADE_OUT_IN, SILENCE_TRIM);
        //      • the pair's tempo judgement does not apply: the file is ONE source carrying
        //        both tempos' material, so there is no second grid left to clash with — and a
        //        slam exists *because* the pair has no relation at all (rule 7 below would
        //        refuse exactly that pair), so a rule that read only the fusion case would
        //        throw away the gesture on every unrelated-tempo pair, which is the population
        //        the slam was built for (「速度无关的也要接，不要淡入淡出」). This is the whole of
        //        round 30's Change 2: **any existing stem edit for the incoming decides an
        //        overlapping kind**, whatever the pair's own measurement says.
        //
        //    This is the boundary's own rule, and the evidence is the file: the controller
        //    stats it at exactly this instant for its own reasons (`noteWithoutEdit`), with the
        //    same lookup the arm will use, so the kind is decided from the thing that will
        //    really be played rather than from a measurement that predicts it.
        //
        //    It is placed after the three capability rules above and before every shape rule:
        //    a boundary that cannot be armed at all (an unstreamable side, no room left, a
        //    length nobody knows) is still CUT, and the user's own 过渡方式 never reaches this
        //    method (the controller answers a forced kind itself).
        if (ctx.incomingEditIsStemPassage()) {
            return TransitionKind.CROSSFADE;
        }
        // 5. The outgoing track is measured to end in silence: there is nothing to
        //    overlap, so trim the dead air instead (see TRIM_TAIL_MIN_MS). This is
        //    the only case where a seam beats an overlap, and it is the only reason
        //    SILENCE_TRIM is ever the automatic answer. Evidence first: a measurement
        //    about THIS boundary outweighs the length heuristic below, so it comes
        //    before it (round 17 — the order was the other way round, which meant a
        //    short track with a measured silent tail got a quick fade into nothing).
        if (ctx.outgoingTailSilenceMs() >= TRIM_TAIL_MIN_MS) {
            return TransitionKind.SILENCE_TRIM;
        }
        // 6. Either side short: fade, but briefly. An 8 s overlap would eat a
        //    noticeable fraction of a song that is only a minute or two long — and a
        //    short overlap is also the least damaging answer for a pair whose material
        //    clashes (see rule 7), because there is barely a stretch where the two are
        //    audible together at all.
        if (ctx.outgoingDurationMs() < SHORT_TRACK_MS
                || ctx.incomingDurationMs() < SHORT_TRACK_MS) {
            return TransitionKind.QUICK_FADE;
        }
        // 7. The pair's own material is measured to overlap badly — their keys clash, or
        //    their tempos cannot be brought onto one grid — so a blend would be two
        //    keys or two tempos at once for its whole length. Play them one after the
        //    other instead: FADE_OUT_IN ramps the outgoing track out, starts the next
        //    one from silence and never has both audible, which is the one arrangement
        //    nothing about the pair can ruin. See PairFit.overlapsBadly for why "we
        //    measured something" is not the trigger — only a measured clash is.
        if (ctx.pairFit().overlapsBadly()) {
            return TransitionKind.FADE_OUT_IN;
        }
        // 8. Everything else: the plain overlap. Two ordinary streams with room
        //    ahead of them are exactly the case the whole feature exists for, and a
        //    cut here would be a blend given up for no reason at all. The length is
        //    the kind's default (the setting's own 过渡时长, default 15 s — raised
        //    further by the controller when the outgoing track's own ending is
        //    measured to be plain) and the curve is named with it (see plan()): a
        //    symmetric ramp over a window this long spends most of itself with one
        //    track alone, which is heard as a fade rather than as a mix.
        return TransitionKind.CROSSFADE;
    }

    /**
     * The same answer with the curve a plain overlap wants, and with the reason this
     * pair got what it got (the chooser labels the branch it took, which is the one
     * thing a decision needs to be auditable from the log).
     *
     * <p>A CROSSFADE over its default (long, 15 s) overlap is long enough that a
     * symmetric pair — linear or equal power — is heard as a fade: the two levels are
     * only comparable near the middle of the window, so about two fifths of it has
     * both tracks audible and the rest is one track rising or the other one falling.
     * That branch therefore names {@link FadeCurve#DJ_BLEND}, the staged shape whose
     * measured both-audible share is about two thirds of the window (see
     * {@link FadeCurve#bothAudibleMs(long)}). A forced kind or an AI answer that names
     * its own curve still goes through untouched, and the plan carries whichever was
     * chosen so the boundary's log line prints it.
     *
     * <p>The one other curve this class ever names is {@link FadeCurve#FUSION}, for a
     * boundary whose edit is a fusion: there the two decks change over once and are
     * constant either side of it, so the DJ shape would describe a ramp that is not going
     * to happen. The kind is the same plain overlap; only the reason and the shape differ.
     */
    @Override
    public TransitionPlan plan(TransitionContext ctx) {
        TransitionKind kind = choose(ctx);
        if (kind == TransitionKind.CROSSFADE && ctx != null && ctx.incomingEditIsStemPassage()) {
            // A stem passage's own shape, not the DJ blend: the file already carries the
            // transition, so the two live decks only have to hand over once — a linear
            // (equal-gain) FadeCurve.JUNCTION_XFADE_MS fade at the junction, then both constant
            // (see FadeCurve.FUSION). The file is a FUSION for a related pair and a SLAM for one in
            // no relation at all; both play the same way (the deck starts on the edit's own `-e`,
            // the outgoing deck is cut on its `-j`), which is why one rule answers both. The length
            // is the kind's default, which the controller then raises to the 过渡时长 the render's
            // own window was cut for; naming the curve here means the boundary's decision line
            // prints 融合 instead of the DJ shape it will not use (the ramp swaps in FUSION for a
            // stem passage either way, and that is the one place the two numbers could disagree).
            //
            // ⚠️ The sentence says THE EDIT WON: round 30's whole point is that this fact outranks
            // the pair's own tempo and key measurement (rule 7), which for an unrelated-tempo pair
            // — every slam's population — is FADE_OUT_IN. A reader of a device log must be able to
            // see which of the two decided, in one line, without the file.
            return TransitionPlan.of(kind, TransitionPlan.defaultOverlapMs(kind),
                    FadeCurve.FUSION, "rule: THE EDIT WON — the incoming track's rendered edit is a"
                            + " STEM PASSAGE (a fusion for a related pair, a slam for a pair in no"
                            + " relation at all: the renderer writes both with `-e`/`-j` in the file"
                            + " name), one passage cut out of BOTH tracks and anchored on the outgoing"
                            + " track's own junction bar line and the incoming file's own entry — so"
                            + " the transition is inside the incoming deck's file: the outgoing deck"
                            + " is cut on that bar line and the two decks change over once in "
                            + FadeCurve.JUNCTION_XFADE_MS + "ms (linear, equal gain — the two are the"
                            + " same material there). No tempo or key judgement applies: the file is"
                            + " one source carrying both backgrounds, and a slam exists precisely for"
                            + " the unrelated-tempo pairs this chooser's own PairFit would otherwise"
                            + " answer FADE_OUT_IN for. This kind is also what makes the deck play"
                            + " that file at all: a non-overlapping one would play the track's own"
                            + " master instead and throw the gesture away");
        }
        if (kind == TransitionKind.CROSSFADE) {
            return TransitionPlan.of(kind, TransitionPlan.defaultOverlapMs(kind),
                    FadeCurve.DJ_BLEND,
                    "rule: two ordinary streams with room ahead, so the ordinary "
                            + (TransitionPlan.OVERLAP_LONG_MS / 1000L) + "s overlap"
                            + " (DJ 式: a symmetric ramp this long is heard as a fade — both"
                            + " tracks are only within 6dB of each other for about two fifths"
                            + " of it)");
        }
        if (kind == TransitionKind.SILENCE_TRIM) {
            return TransitionPlan.of(kind, TransitionPlan.defaultOverlapMs(kind), null,
                    "rule: the outgoing track is measured to end in "
                            + (ctx == null ? 0L : ctx.outgoingTailSilenceMs())
                            + "ms of silence (>= " + TRIM_TAIL_MIN_MS + "ms), so the seam is"
                            + " trimmed rather than ramped into it");
        }
        if (kind == TransitionKind.FADE_OUT_IN) {
            return TransitionPlan.of(kind, TransitionPlan.defaultOverlapMs(kind), null,
                    "rule: " + (ctx == null ? "no context"
                            : "the pair's own material overlaps badly — " + ctx.pairFit()
                                    + " — so the two are played one after the other rather"
                                    + " than together (nothing is mixed, so nothing about the"
                                    + " pair can clash)"));
        }
        return TransitionPlan.of(kind, TransitionPlan.defaultOverlapMs(kind), null,
                "rule: " + cutOrShortReason(ctx, kind));
    }

    /** One clause naming why the first rules refused an overlap, for the log. */
    private static String cutOrShortReason(TransitionContext ctx, TransitionKind kind) {
        if (ctx == null) return "no context";
        if (!ctx.outgoingStreamable() || !ctx.incomingStreamable()) {
            return "one side cannot be streamed on a second player";
        }
        if (ctx.remainingMs() < MIN_REMAINING_MS) return "too late in the track";
        if (!ctx.hasBothLengths()) return "a length nobody knows";
        if (kind == TransitionKind.QUICK_FADE) {
            long shorter = Math.min(ctx.outgoingDurationMs(), ctx.incomingDurationMs());
            return "one of the two tracks is only " + (shorter / 1000L) + "s long (under "
                    + (SHORT_TRACK_MS / 1000L) + "s), so the overlap is kept short";
        }
        return "no reason recorded";
    }
}
