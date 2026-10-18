package dev.t1m3.qplayer.audio;

import java.util.Locale;

/**
 * The user's key blend: the modulation section of a blend, where the mix's tonality travels
 * from the outgoing track's key into the incoming track's own — audibly, in a few steps on
 * the grid, on <em>both</em> decks at once.
 *
 * <p><b>What it replaces.</b> Until round 14 the incoming track was transposed into the
 * outgoing track's key for the whole blend and then <em>snapped</em> back to its own pitch
 * at one instant before its vocals returned ({@code AndroidAudioBackend}'s one write —
 * round 13's fix for the per-tick glide that produced a hiccup). A snap is the right shape
 * for a correction and the wrong shape for a transition: the listener hears a key change
 * land as an edit rather than as a move. This class turns that one step into a short ladder
 * of steps spread over the blend's own vocals-out window, so the ear hears the key travel.
 *
 * <p><b>Why both decks move, and not just the incoming one.</b> If the two decks are to be
 * heard in the same key at every instant — which is the whole reason the incoming track was
 * transposed in the first place — then their two shifts are not independent. Writing
 * {@code d} for the semitones that put the incoming track (K<sub>b</sub>) in the outgoing
 * track's key (K<sub>a</sub> = K<sub>b</sub> + d), the outgoing deck's shift {@code a(t)}
 * and the incoming deck's {@code b(t)} are in unison exactly when
 * {@code b(t) − a(t) = d}. At the blend's start the pair is {@code a = 0, b = d} (the
 * outgoing track in its own key, the incoming one pulled into it). The incoming track's
 * vocals must arrive in its OWN key — that is the rule this codebase has held since round 12
 * — so the end of the modulation is {@code b = 0}, and then the same equation forces
 * {@code a = −d}: the outgoing deck is a whole key away from where it started. So
 *
 * <ul>
 *   <li><b>both decks move</b> (each by exactly {@code |d|} semitones, in the same
 *   direction, keeping {@code b − a = d} at all times): the <em>tonality of the mix</em>
 *   travels from K<sub>a</sub> to K<sub>b</sub> while the two tracks never fight — this is
 *   the classical "riser into the new key";</li>
 *   <li><b>or neither does</b> ({@code a ≡ 0} forces {@code b ≡ d}: no modulation at all,
 *   which is exactly the behaviour this class replaces);</li>
 *   <li>and the third possibility — the incoming deck alone gliding to its own key while
 *   the outgoing stays put — is <b>not</b> a third shape but the worst of both: the pair is
 *   in unison only at the start and ends a whole key apart, <em>while both are still
 *   audible</em>, i.e. the second half of the blend is deliberately detuned. Today's snap
 *   at least keeps the two in unison for the whole overlap.</li>
 * </ul>
 *
 * <p>That is why {@code semitones != 0} plus a section long enough to glide means both
 * decks are written, and why the log names both curves. The cost is stated rather than
 * hidden: the outgoing deck's own material is transposed too — a sung note in the outgoing
 * tail is heard a semitone or two away from where it was recorded. It is bounded
 * ({@link IncomingMix#MAX_SEMITONES} semitones over many seconds, not an effect) and it is
 * front-loaded against the outgoing track's own gain: the deck is at its quietest when its
 * shift is largest (the ladder is monotone and the DJ curve is already taking that deck
 * down), which is why every step's log line carries the audible deck's gain as well.
 *
 * <p><b>Where the steps land.</b> On the incoming track's own beat grid, a whole number of
 * beats apart, with the last one <em>at or before</em> the instant the pitch rule's deadline
 * falls on ({@code MixNaturaliser.VOCAL_PITCH_MARGIN_MS} before that track's vocals) — the
 * same "the last beat at or before the deadline" round 13 used for its single step, and for
 * the same reason: a beat is where the material the deck is playing (its backing, since the
 * DJ edit holds its vocals out) has its attack, so a parameter change is at its most hidden.
 * The spacing is rounded up to whole bars when the section has room for it, because a
 * modulation that moves every bar reads as a phrase rather than as a rattle. The number of
 * steps is capped ({@link #MAX_STEPS}) so the ladder is a handful of writes rather than a
 * tick loop: {@code MAX_STEPS} writes per deck over a section of seconds, every one of them
 * on an instant the music already emphasises.
 *
 * <p>Immutable, platform-free and tiny: built once per boundary, from profiles the
 * controller already has, and handed to the backend inside the {@link IncomingMix} the
 * incoming player is prepared with.
 */
public final class KeyGlide {

    /**
     * The most writes ONE deck gets over the whole modulation section.
     *
     * <p>Eight since round 17 (it was six): the section grew to the whole blend when the vocal
     * return moved past its end (round 17 — the rule's deadline is no longer capped at the
     * blend, so the ladder can use all of it), and the same travel spread over more, smaller
     * steps is a smoother modulation. It is still a handful of writes — 16 at the very most for
     * a boundary, against round 13's measured 156 for a per-tick glide — and the spacing rule
     * below still rounds up to whole bars, so most pairs keep the count they had.
     */
    public static final int MAX_STEPS = 8;

    /**
     * Fewer steps than this and there is no glide: the boundary keeps round 13's single
     * step at the deadline (the same end state, arrived at by a jump). Two, because a
     * "glide" of one step IS that single step, and this class exists to be the other thing.
     */
    public static final int MIN_STEPS = 2;

    /** A section shorter than this many beats is not a section: no glide, single step. */
    public static final int MIN_SECTION_BEATS = 2;

    /** Beats to a bar, for the "round the spacing up to whole bars" preference. Same
     *  assumption as {@link DjEdit#BEATS_PER_BAR} and every other bar-line use here. */
    public static final int BEATS_PER_BAR = DjEdit.BEATS_PER_BAR;

    /** No glide, and why — the shape that keeps round 13's single step. Never null. */
    public static KeyGlide none(String why) {
        return new KeyGlide(0, -1L, -1L, null, null, null, 0, why == null ? "" : why);
    }

    /**
     * The modulation section for one boundary, or {@link #none} with the measurement that
     * said no.
     *
     * @param semitones the shift the incoming deck is prepared with (what
     *                  {@link IncomingMix#semitones()} is), i.e. the distance between the
     *                  two keys. Zero means the pair is already in one key and there is
     *                  nothing to travel.
     * @param startFileMs where the incoming deck begins, in ITS OWN file — the blend's
     *                  first sample. The deck holds the shift from here.
     * @param endFileMs  the instant the pitch must be the track's own at, in that file: the
     *                  rule's deadline snapped to the last beat at or before it (round 13's
     *                  {@code pitchIdentityAtFileMs}). The last step of the ladder lands
     *                  here, so the rule holds with whatever slack the snap left.
     * @param incomingGrid the incoming track's own beat grid (the deck's clock), or null —
     *                  without it there is no instant to place a step on and the answer is
     *                  {@link #none}.
     */
    public static KeyGlide plan(int semitones, long startFileMs, long endFileMs,
                                BeatProfile incomingGrid) {
        if (semitones == 0) {
            return none("nothing to glide: this pair is not transposed");
        }
        if (incomingGrid == null) {
            return none("no grid for the incoming track, so there is no instant to place a"
                    + " step on: the pitch goes back in the one step it always did");
        }
        if (!incomingGrid.trustworthy()) {
            return none("the incoming track's grid is not trustworthy (" + incomingGrid.confidenceText()
                    + "), so a step has no instant to hide on: the pitch goes back in the one"
                    + " step it always did");
        }
        double beatMs = incomingGrid.periodMs();
        if (!(beatMs > 0d)) {
            return none("the incoming track's grid has no period, so no step can be placed");
        }
        // The grid's own arithmetic is whole milliseconds — {@code BeatProfile.beatAtOrBefore}
        // spaces beats by the ROUNDED period — so every instant here is built the same way and a
        // step lands on the very beat the grid would name, rather than a fraction of one beside
        // it. (The first version of this class rounded the *spacing* instead and drifted off the
        // grid by a quarter of a beat over a section, which its own test caught.)
        long period = Math.max(1L, Math.round(beatMs));
        long spanMs = endFileMs - startFileMs;
        long spanBeats = spanMs / period;
        if (endFileMs <= startFileMs || spanBeats < MIN_SECTION_BEATS) {
            return none(String.format(Locale.US,
                    "the vocals-out window is only %dms of the incoming track's own %sBPM beat"
                            + " (%d beats), which is not a section: the pitch goes back in the"
                            + " one step it always did",
                    Math.max(0L, spanMs), bpmText(incomingGrid), Math.max(0L, spanBeats)));
        }
        // The spacing: as few steps as the cap allows (so the sign of the move is never in
        // doubt), rounded UP to whole bars when that still leaves a glide, because a
        // modulation that moves every bar reads as a phrase and one that moves every five
        // beats reads as an accident.
        int spacing = (int) Math.max(1L, ceilDiv(spanBeats, MAX_STEPS));
        int asBars = ceilDiv(spacing, BEATS_PER_BAR) * BEATS_PER_BAR;
        if (1 + spanBeats / asBars >= MIN_STEPS) {
            spacing = asBars;
        }
        int steps = (int) Math.min(MAX_STEPS, 1L + spanBeats / spacing);
        if (steps < MIN_STEPS) {
            return none(String.format(Locale.US,
                    "the window is %d beats of the incoming track and a step every %d beats"
                            + " leaves only %d of them, so the pitch goes back in the one step"
                            + " it always did",
                    spanBeats, spacing, steps));
        }
        long[] at = new long[steps];
        double[] in = new double[steps];
        double[] out = new double[steps];
        long stepMs = spacing * period;
        for (int i = 0; i < steps; i++) {
            // Backwards from the deadline: the last step is the instant the rule names, and
            // each earlier one is `spacing` beats before it — every instant on the same
            // beat class, which is what makes the ladder a shape rather than a scatter.
            at[i] = endFileMs - (long) (steps - 1 - i) * stepMs;
            double remaining = semitones * (steps - 1 - i) / (double) steps;
            in[i] = remaining;
            // a(t) = b(t) − d: the outgoing deck ends the section in the incoming track's
            // key, having started in its own.
            out[i] = remaining - semitones;
        }
        return new KeyGlide(semitones, startFileMs, endFileMs, at, in, out, stepMs,
                describePlan(semitones, startFileMs, endFileMs, at, in, out, stepMs, spacing,
                        incomingGrid));
    }

    private final int semitones;
    private final long startFileMs;
    private final long endFileMs;
    private final long[] atFileMs;
    private final double[] incomingSemitones;
    private final double[] outgoingSemitones;
    private final long stepMs;
    private final String note;

    private KeyGlide(int semitones, long startFileMs, long endFileMs, long[] atFileMs,
                     double[] incomingSemitones, double[] outgoingSemitones, long stepMs,
                     String note) {
        this.semitones = semitones;
        this.startFileMs = startFileMs;
        this.endFileMs = endFileMs;
        this.atFileMs = atFileMs;
        this.incomingSemitones = incomingSemitones;
        this.outgoingSemitones = outgoingSemitones;
        this.stepMs = stepMs;
        this.note = note == null ? "" : note;
    }

    /** Whether there is a ladder at all. False means the boundary holds round 13's shape:
     *  the shift held, then one step back to the track's own pitch, and the outgoing deck
     *  never touched. */
    public boolean isGliding() {
        return atFileMs != null && atFileMs.length >= MIN_STEPS;
    }

    /** The writes each deck gets over the section (0 when not gliding). */
    public int steps() {
        return atFileMs == null ? 0 : atFileMs.length;
    }

    /** When step {@code i} is written, in the incoming track's own file, ms. */
    public long atFileMs(int i) {
        return atFileMs[i];
    }

    /** The shift the incoming deck carries from step {@code i} on, semitones (the last step
     *  is 0 — the track's own pitch, which is what the rule asks for). */
    public double incomingSemitones(int i) {
        return incomingSemitones[i];
    }

    /** The shift the OUTGOING deck carries from step {@code i} on, semitones: the incoming
     *  deck's own shift minus the key distance, so the two are in unison at every instant
     *  and the pair ends in the incoming track's key (see this class's header). */
    public double outgoingSemitones(int i) {
        return outgoingSemitones[i];
    }

    /** {@link #incomingSemitones(int)} as the ratio the platform takes. */
    public double incomingPitch(int i) {
        return Math.pow(2d, incomingSemitones[i] / 12d);
    }

    /** {@link #outgoingSemitones(int)} as the ratio the platform takes. */
    public double outgoingPitch(int i) {
        return Math.pow(2d, outgoingSemitones[i] / 12d);
    }

    /** The shift the incoming deck holds before the first step: the pair's own
     *  {@link #semitones()}, which the prepare already wrote. */
    public int semitones() {
        return semitones;
    }

    /** How long the modulation is, in the incoming track's own file, ms. */
    public long sectionFileMs() {
        return endFileMs < 0L ? 0L : Math.max(0L, endFileMs - startFileMs);
    }

    /** Where the pitch is the track's own again, in the incoming track's own file, ms —
     *  the rule's deadline, which is also the last step's instant. */
    public long identityAtFileMs() {
        return endFileMs;
    }

    /** The spacing between steps, ms of the incoming track's own file (0 when not gliding). */
    public long stepMs() {
        return stepMs;
    }

    /** The section and the two curves, for the boundary's log line. Never null. */
    public String note() {
        return note;
    }

    /** The section and the two curves as one line — what the backend logs when it arms the
     *  ladder, and what the boundary says in its own mix fragment. Never null. */
    public String describe() {
        return note;
    }

    /**
     * How the pair's tonality <em>converges</em> over this ladder, as a sentence for the
     * boundary's log line — the report the user asked for ("the key distance at the blend's
     * start, middle and end must converge"), measured on the two tracks' own chroma profiles.
     *
     * <p>The number is the profile distance between what the two decks are <em>sounding</em>
     * (the mix's key, which by construction is one key at every instant) and the incoming
     * track's own key. It starts at the pair's raw distance — the incoming track is transposed
     * into the outgoing track's key, so the mix is exactly as far from the incoming track's key
     * as the two tracks' keys are from each other — and ends at the distance between the
     * incoming track's key and itself, which is zero: the modulation is a move from the
     * outgoing track's key to the incoming track's own, and this is that move as a number.
     *
     * <p>⚠️ The steps between the ends are <b>fractional</b> semitones, and a chroma profile is
     * only defined at whole ones, so the middle value is interpolated between the two
     * neighbouring semitone bins and says so. The per-step {@code semitones} in
     * {@link #describe()} are exact and are the primary evidence; this sentence is the
     * convergence the user asked to see.
     *
     * @param outgoingKey the outgoing track's own key profile, or null
     * @param incomingKey the incoming track's own key profile (the destination), or null
     * @return the sentence, or null when this boundary has no ladder or no keys to measure
     */
    public String convergenceNote(KeyProfile outgoingKey, KeyProfile incomingKey) {
        if (!isGliding() || outgoingKey == null || incomingKey == null) return null;
        double[] chromaA = outgoingKey.chroma();
        double[] chromaB = incomingKey.chroma();
        int middle = steps() / 2;
        int last = steps() - 1;
        StringBuilder sb = new StringBuilder(140);
        sb.append(String.format(Locale.US,
                "the mix's key converges on the incoming track's own over the modulation: the"
                        + " profile distance between the two decks' sounding keys and the"
                        + " incoming track's own is %.2f at the blend's start (%+.2f semitones on"
                        + " the incoming deck, %+.2f on the audible one), %.2f at the middle step"
                        + " (%+.2f / %+.2f, interpolated between semitone bins), and %.2f at the"
                        + " last (%+.2f / %+.2f — both decks are in %s by then, and that residual"
                        + " is the closest the two keys come: it is the distance the pair's own"
                        + " two keys have after the shift, not a failure to arrive)",
                distanceAt(chromaA, chromaB, 0d), (double) semitones(), 0d,
                distanceAt(chromaA, chromaB, outgoingSemitones(middle)),
                incomingSemitones(middle), outgoingSemitones(middle),
                distanceAt(chromaA, chromaB, outgoingSemitones(last)),
                incomingSemitones(last), outgoingSemitones(last), incomingKey.label()));
        return sb.toString();
    }

    /** The distance between the mix's key (the outgoing track's material shifted by
     *  {@code outgoingSemitones}) and the incoming track's own key, with the fractional shift
     *  interpolated between the two neighbouring semitone bins. */
    private static double distanceAt(double[] chromaA, double[] chromaB, double outgoingSemitones) {
        double lo = Math.floor(outgoingSemitones);
        double f = outgoingSemitones - lo;
        double[] at = KeyProfile.rotated(chromaA, (int) lo);
        if (f > 1e-9d) {
            double[] next = KeyProfile.rotated(chromaA, (int) lo + 1);
            for (int i = 0; i < at.length; i++) {
                at[i] = at[i] * (1d - f) + next[i] * f;
            }
        }
        return KeyProfile.distance(at, chromaB);
    }

    @Override
    public String toString() {
        return isGliding()
                ? "KeyGlide{" + steps() + " steps over " + sectionFileMs() + "ms, "
                        + cnt("%+d", semitones) + " -> 0 on the incoming, 0 -> "
                        + cnt("%+d", -semitones) + " on the outgoing}"
                : "KeyGlide{none: " + note + "}";
    }

    private static String describePlan(int semitones, long startFileMs, long endFileMs,
                                       long[] at, double[] in, double[] out, long stepMs,
                                       int spacing, BeatProfile grid) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US,
                "the key glides %+d semitone%s over %dms of the incoming track's own file"
                        + " (%sBPM, a step every %d beats = %dms), %d writes on EACH deck:"
                        + " the incoming %s -> 0 (its own key) and the outgoing 0 -> %s, so the"
                        + " two stay %d semitone%s apart in the same key at every instant and"
                        + " the mix's tonality moves from the outgoing track's key into the"
                        + " incoming track's own",
                semitones, Math.abs(semitones) == 1 ? "" : "s", endFileMs - startFileMs,
                bpmText(grid), spacing, stepMs, at.length, loc("%+.2f", (double) semitones),
                loc("%+.2f", (double) -semitones), Math.abs(semitones),
                Math.abs(semitones) == 1 ? "" : "s"));
        sb.append("; steps at");
        for (int i = 0; i < at.length; i++) {
            sb.append(' ').append(at[i]).append("ms=").append(loc("%+.2f", in[i]))
                    .append("st/").append(loc("%+.2f", out[i])).append("st");
        }
        sb.append(" (the last one is the rule's deadline, on the last beat at or before it)");
        return sb.toString();
    }

    private static String bpmText(BeatProfile grid) {
        return grid == null ? "?" : String.format(Locale.US, "%.1f", grid.bpm());
    }

    private static String loc(String fmt, double value) {
        return String.format(Locale.US, fmt, value);
    }

    /** Whole semitones and step counts: {@code %d}, never a floating-point conversion — a
     *  {@code %f} format given an int throws, which is how the first version of this class
     *  failed its own test. */
    private static String cnt(String fmt, int value) {
        return String.format(Locale.US, fmt, value);
    }

    private static long ceilDiv(long a, long b) {
        return b <= 0L ? a : (a + b - 1L) / b;
    }

    private static int ceilDiv(int a, int b) {
        return b <= 0 ? a : (a + b - 1) / b;
    }
}
