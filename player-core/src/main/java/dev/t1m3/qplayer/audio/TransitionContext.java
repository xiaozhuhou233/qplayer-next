package dev.t1m3.qplayer.audio;

import dev.t1m3.qplayer.model.Track;

/**
 * Everything a {@link TransitionChooser} may look at when deciding how one track
 * should give way to the next: the two tracks' metadata, how much of the
 * outgoing track is left, and whether each side can be streamed on a second
 * player at all.
 *
 * <p>Deliberately limited to facts, not to analysis. There are no BPM, key or onset
 * fields here because a chooser cannot ask for something the app does not have:
 * nothing in qplayer estimates those for the boundary's decision (the beat grids
 * are consulted by the controller alone, after the kind is chosen), and a chooser
 * that never sees them cannot pretend to have used them. An AI implementation
 * plugged in later gets exactly this and is expected to decide from the same facts
 * a person reading the queue would have: titles, artists, albums, lengths, what is
 * left of the current track — plus the two silence measurements below, which are
 * the one analysis that does reach the decision, because a trim's whole
 * justification is a number the app has already measured for another reason.
 *
 * <p>Round 19 adds one more fact, and it is not analysis either: whether the incoming
 * track's own rendered edit is a stem passage ({@link #incomingEditIsStemPassage()}). That is a
 * statement about the <em>file</em> the incoming deck will play — the renderer's own
 * answer, read back from its name — and it is the one thing that can settle the kind
 * before any of the pair's numbers are consulted, because a fusion's transition is
 * already inside that file.
 *
 * <p>Immutable: built by the controller per boundary and handed to the chooser.
 */
public final class TransitionContext {

    /** {@link #outgoingTailSilenceMs()} / {@link #incomingHeadSilenceMs()} when the
     *  measurement has not been made (or this host has no profiler at all). */
    public static final long SILENCE_UNKNOWN = -1L;

    /**
     * What the two tracks' own measured material says about setting them on top of each other:
     * whether their keys were measured to fight, whether their tempos can be brought onto one
     * grid, and the measurement behind both.
     *
     * <p>⚠️ <b>Round 17 widens what a chooser may look at, and this is the one addition.</b> The
     * class note above used to say there are no BPM or key fields here "because a chooser cannot
     * ask for something the app does not have" — true when the only analysis was the silence
     * profiler. It has not been true since round 11: every track the app plays is measured for a
     * beat grid and a key ({@code BeatProfiler}/{@code BeatAnalysis}), the results are cached per
     * track, and the controller reads them at the boundary anyway. What was missing was a
     * <em>verdict</em> over the pair, and that is what this is: not the two tracks' numbers, but
     * the two answers a chooser's rule needs — "do their keys clash?" and "can their tempos be
     * locked together?" — each of which is a decision the naturaliser already makes for every
     * boundary.
     *
     * <p>It exists because of what the user asked for: several transition kinds actually being
     * used, chosen by evidence rather than by habit. {@link HeuristicTransitionChooser} answers
     * {@link TransitionKind#FADE_OUT_IN} — play the outgoing track out, then start the next one,
     * nothing mixed at all — for a pair whose material <em>overlaps badly</em>, and that phrase
     * is defined here ({@link #overlapsBadly()}) so the rule and its evidence cannot drift apart.
     */
    public static final class PairFit {

        /** No beat or key measurement for one of the two tracks: nothing is claimed about the
         *  pair, and a rule that wants evidence does not fire. */
        public static final PairFit UNMEASURED = new PairFit(false, false, false, false, 0d, "",
                "neither track's beat grid and key have been measured");

        private final boolean keysMeasured;
        private final boolean keysClash;
        private final boolean temposMeasured;
        private final boolean temposLockable;
        private final double keyDistance;
        private final String note;
        private final String what;

        /**
         * @param keysMeasured   both keys were measured and are trustworthy
         * @param keysClash      measured, and nothing within ±{@link IncomingMix#MAX_SEMITONES}
         *                       brings them together (see {@link MixNaturaliser.Keys#clash()})
         * @param temposMeasured both beat grids exist and are trustworthy
         * @param temposLockable the two grids already hold over the blend, or pulling the
         *                       incoming track's tempo is inside {@link
         *                       IncomingMix#MAX_SPEED_STEP}
         * @param keyDistance    the profile distance as the keys stand (0 when unmeasured)
         * @param what           what was measured, for the log line, never null
         * @param note           the sentence the reason text is built from, never null
         */
        public PairFit(boolean keysMeasured, boolean keysClash, boolean temposMeasured,
                       boolean temposLockable, double keyDistance, String what, String note) {
            this.keysMeasured = keysMeasured;
            this.keysClash = keysClash;
            this.temposMeasured = temposMeasured;
            this.temposLockable = temposLockable;
            this.keyDistance = keyDistance;
            this.note = note == null ? "" : note;
            this.what = what == null ? "" : what;
        }

        /** The keys could be judged and they were measured to fight: the pair would be in two
         *  keys at once for the whole blend. */
        public boolean keysClash() {
            return keysMeasured && keysClash;
        }

        /** Both grids were judged and pulling the incoming track's tempo onto the outgoing
         *  track's would need more than the clamp allows — the two rhythms cannot be held
         *  together, so a blend would be two tempos at once. */
        public boolean rhythmsClash() {
            return temposMeasured && !temposLockable;
        }

        /**
         * The evidence-based answer to "would these two be better one after the other?": their
         * keys fight, or their tempos cannot be locked together (or both). Deliberately <em>not</em>
         * "any measurable difference" — a pair that is merely in different keys the naturaliser
         * can move, or whose grids it can pull, overlaps perfectly well, and answering a
         * sequential fade for every such pair would be the "nothing happened" regression of the
         * earlier rounds in a new coat.
         */
        public boolean overlapsBadly() {
            return keysClash() || rhythmsClash();
        }

        /** The measurement behind {@link #overlapsBadly()}, for the log line. Never null. */
        public String describe() {
            return note;
        }

        /** What was measured, in one clause. Never null. */
        public String what() {
            return what;
        }

        @Override
        public String toString() {
            return "PairFit{" + what + "}";
        }
    }

    private final Track outgoing;
    private final Track incoming;
    private final long remainingMs;
    private final long outgoingDurationMs;
    private final boolean outgoingStreamable;
    private final boolean incomingStreamable;
    private final long outgoingTailSilenceMs;
    private final long incomingHeadSilenceMs;
    private final PairFit pairFit;
    private final boolean incomingEditIsStemPassage;

    /** The context a chooser sees: metadata plus "either side can be streamed on a
     *  second player", with both silence measurements unknown, no pair measurement and
     *  no rendered edit for the incoming track. */
    public TransitionContext(Track outgoing, Track incoming, long remainingMs,
                             long outgoingDurationMs, boolean outgoingStreamable,
                             boolean incomingStreamable) {
        this(outgoing, incoming, remainingMs, outgoingDurationMs, outgoingStreamable,
                incomingStreamable, SILENCE_UNKNOWN, SILENCE_UNKNOWN, PairFit.UNMEASURED,
                false);
    }

    /**
     * Same, with what has already been measured about the two ends.
     *
     * <p>{@code outgoingTailSilenceMs} / {@code incomingHeadSilenceMs} are the
     * silence measurements the controller already keeps per track ({@code
     * SilenceProfile.headMs()}/{@code tailMs()}), or {@link #SILENCE_UNKNOWN}. They
     * are here for one rule only: a seam that skips a measured silence is
     * demonstrably better than an overlap that ramps music into it, and that is the
     * one case where {@link TransitionKind#SILENCE_TRIM} is still chosen over the
     * default {@link TransitionKind#CROSSFADE}. Measured at the decision, never
     * waited for: an unknown measurement is simply not evidence.
     */
    public TransitionContext(Track outgoing, Track incoming, long remainingMs,
                             long outgoingDurationMs, boolean outgoingStreamable,
                             boolean incomingStreamable,
                             long outgoingTailSilenceMs, long incomingHeadSilenceMs) {
        this(outgoing, incoming, remainingMs, outgoingDurationMs, outgoingStreamable,
                incomingStreamable, outgoingTailSilenceMs, incomingHeadSilenceMs,
                PairFit.UNMEASURED, false);
    }

    /**
     * The full context as it was before round 19: the two ends' silence and the pair's own
     * measurement ({@link PairFit}), with no rendered edit for the incoming track — see the
     * constructor below for what a fusion adds and why it is read from the file rather than
     * guessed from the pair.
     */
    public TransitionContext(Track outgoing, Track incoming, long remainingMs,
                             long outgoingDurationMs, boolean outgoingStreamable,
                             boolean incomingStreamable,
                             long outgoingTailSilenceMs, long incomingHeadSilenceMs,
                             PairFit pairFit) {
        this(outgoing, incoming, remainingMs, outgoingDurationMs, outgoingStreamable,
                incomingStreamable, outgoingTailSilenceMs, incomingHeadSilenceMs, pairFit, false);
    }

    /**
     * The full context: the two ends' silence, the pair's own measurement
     * ({@link PairFit}) — see that class for why the second one exists and what it may and may
     * not be used for — and whether the incoming track's own rendered edit is a
     * <b>fusion</b>.
     *
     * <p>⚠️ <b>{@code incomingEditIsStemPassage} is not a guess about the pair: it is the file the
     * incoming deck will actually play.</b> A DJ edit is the incoming track's own audio with its
     * vocals taken out of the front ({@code StemEditRenderer}); the renderer may instead write a
     * <em>fusion</em> — the two tracks' backgrounds spliced into one passage inside that file,
     * anchored on the outgoing track's own junction bar line and the incoming file's own entry
     * ({@link TransitionKind#CROSSFADE}'s path, {@code StemFusion}). When one exists, the
     * transition is <em>already in the file</em>: the two live decks only change over once, at
     * that junction, and nothing about the pair's tempos or keys can be laid on top of it — which
     * is why {@link HeuristicTransitionChooser} answers an overlapping kind for such a boundary
     * whatever its own rules would otherwise have said (a sequential fade or a trim would discard
     * the gesture the render already made, and a trim would not even play the file: it is not an
     * overlapping kind).
     *
     * <p>The fact lives here rather than in the chooser because the chooser is a decision over
     * facts: it may not touch the filesystem, and the only thing that may is the controller, which
     * already stats this pair's edit at exactly this instant for its own reasons
     * ({@code PlayerController.noteWithoutEdit}). The chooser only has to say what it wants the
     * kind to be.
     *
     * <p>False is the whole ordinary world: no renderer at all (a host with no stem code), no
     * model, a render that has not finished, a render that refused to fuse, or a user whose
     * 过渡时长 the file was not rendered for. Every rule below then answers exactly what it always
     * did.
     */
    public TransitionContext(Track outgoing, Track incoming, long remainingMs,
                             long outgoingDurationMs, boolean outgoingStreamable,
                             boolean incomingStreamable,
                             long outgoingTailSilenceMs, long incomingHeadSilenceMs,
                             PairFit pairFit, boolean incomingEditIsStemPassage) {
        this.outgoing = outgoing;
        this.incoming = incoming;
        this.remainingMs = Math.max(0L, remainingMs);
        this.outgoingDurationMs = Math.max(0L, outgoingDurationMs);
        this.outgoingStreamable = outgoingStreamable;
        this.incomingStreamable = incomingStreamable;
        this.outgoingTailSilenceMs = outgoingTailSilenceMs;
        this.incomingHeadSilenceMs = incomingHeadSilenceMs;
        this.pairFit = pairFit == null ? PairFit.UNMEASURED : pairFit;
        this.incomingEditIsStemPassage = incomingEditIsStemPassage;
    }

    /** The track that is playing now. Never null in a boundary the controller
     *  asks about, but a chooser must still tolerate a null {@link #incoming()}. */
    public Track outgoing() {
        return outgoing;
    }

    /** The track the queue would advance to. */
    public Track incoming() {
        return incoming;
    }

    /** How much of the outgoing track is left to play, ms — the whole budget a
     *  transition has to fit into. */
    public long remainingMs() {
        return remainingMs;
    }

    /** The outgoing track's length, ms: its metadata when it has any, otherwise
     *  the live player's own duration. Not an estimate — one of the two is
     *  authoritative for the track that is playing. */
    public long outgoingDurationMs() {
        return outgoingDurationMs;
    }

    /** The incoming track's length, ms, from its metadata only (0 = unknown). */
    public long incomingDurationMs() {
        return incoming != null ? Math.max(0L, incoming.durationMs) : 0L;
    }

    /** Whether the outgoing side is an ordinary stream. A BILI link is short lived
     *  and its picture belongs to the active player; a local file would need a
     *  second decoder for no real gain — neither can be overlapped. */
    public boolean outgoingStreamable() {
        return outgoingStreamable;
    }

    /** Same for the incoming side. */
    public boolean incomingStreamable() {
        return incomingStreamable;
    }

    /** True when both lengths are known, which is the precondition for any claim
     *  about how much of either track a transition would consume. */
    public boolean hasBothLengths() {
        return outgoingDurationMs > 0L && incomingDurationMs() > 0L;
    }

    /** How much silence the outgoing track is measured to trail, ms, or
     *  {@link #SILENCE_UNKNOWN}. */
    public long outgoingTailSilenceMs() {
        return outgoingTailSilenceMs;
    }

    /** How much silence the incoming track is measured to lead with, ms, or
     *  {@link #SILENCE_UNKNOWN}. */
    public long incomingHeadSilenceMs() {
        return incomingHeadSilenceMs;
    }

    /** The pair's own measurement, never null — see {@link PairFit}. */
    public PairFit pairFit() {
        return pairFit;
    }

    /**
     * Whether the incoming track's own rendered edit is a <b>stem passage</b> — the two backgrounds
     * spliced into one passage inside the file, with the outgoing track's junction bar line and the
     * incoming file's own entry baked into its name ({@code StemFusion},
     * {@code PlayerController.djEditFor} / {@code EditRef.isFusion()}).
     *
     * <p>True means the transition this boundary will hear is <em>inside the incoming deck's
     * file</em>, and the two live decks only hand over once: an overlapping kind is then the only
     * honest answer, and it is the only kind that plays that file at all. False means there is
     * nothing special about this pair — see the constructor for the whole list of ordinary cases.
     *
     * <p>⚠️ <b>Both of the passage's shapes count, and they are one fact for one reason (round
     * 30).</b> A <b>fusion</b> ({@link #incomingEditIsFusion()} is the round-19 name this carries) is the
     * three-step gesture for a pair whose grids relate; a <b>SLAM</b> is the one-bar cut for a pair
     * whose grids are in no relation at all — and it is exactly those pairs that
     * {@link HeuristicTransitionChooser}'s own pair measurement answers {@link
     * TransitionKind#FADE_OUT_IN} for (unrelated tempo, clashing keys). The file, however, is the
     * same kind of thing in both cases: the incoming track's own audio carrying the outgoing
     * track's material, with {@code -e} and {@code -j} in its name, and the boundary must start the
     * deck on the entry and cut the outgoing one on the junction. A rule that read the fusion case
     * and not the slam is a rule that throws the very gesture the user asked for away on the pairs
     * it was written for (「速度无关的也要接，不要淡入淡出」).
     *
     * <p>The fact lives here rather than in the chooser because the chooser is a decision over
     * facts: it may not touch the filesystem, and the only thing that may is the controller, which
     * already stats this pair's edit at exactly this instant for its own reasons
     * ({@code PlayerController.noteWithoutEdit}). The chooser only has to say what it wants the
     * kind to be.
     */
    public boolean incomingEditIsFusion() {
        return incomingEditIsStemPassage;
    }

    /** The same fact under the name round 30 gave it (see {@link #incomingEditIsFusion()}): a
     *  rendered passage exists for the incoming track, whether it is a fusion or a slam. Read this
     *  one in new code — the two never disagree, and the older name is kept because every call site
     *  and the whole of round 19's documentation reads it. */
    public boolean incomingEditIsStemPassage() {
        return incomingEditIsStemPassage;
    }

    @Override
    public String toString() {
        return "TransitionContext{" + outgoing + " -> " + incoming
                + ", remaining=" + remainingMs + "ms"
                + ", streamable=" + outgoingStreamable + "/" + incomingStreamable
                + (incomingEditIsStemPassage ? ", incoming edit is a STEM PASSAGE" : "")
                + ", " + pairFit + "}";
    }
}
