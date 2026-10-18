package dev.t1m3.qplayer.audio;

import java.util.Locale;

/**
 * What has to be done to the incoming track for the two to sound like a mix rather
 * than like two songs at once: how much its tempo is pulled onto the outgoing
 * track's grid, and how many semitones it is transposed into the outgoing track's
 * harmony.
 *
 * <p><b>This is a naturaliser, not a gate.</b> {@link BeatProfile} alignment can
 * only say <em>when</em> to put two beats together; it cannot help a pair whose
 * beats never meet, and it says nothing about harmony. Pulling the incoming track's
 * tempo by a few percent makes the two grids literally the same grid — which is
 * what lets a fifteen-second overlap be aligned at all, since the compatibility
 * gate tolerates about 1.6% of tempo difference over 15 s and this library's pairs
 * slide 2% and more — and transposing it by a semitone or two puts it in the
 * outgoing track's key. Both are small, pitch-preserving corrections applied to the
 * <em>incoming player only</em> (see {@link IncomingMix}), and both are undone over
 * a few seconds: the tempo once that track is the audible one (the last moment it can
 * be — see {@link IncomingMix}), and the transposition earlier, before that track's
 * vocals arrive, because a transposed vocal must never be heard
 * ({@link #pitchFitsBeforeVocals}).
 *
 * <p>⚠️ What this class can <em>not</em> do is take a blend away. An earlier round
 * had the same arithmetic answering "suitable / not suitable" and let a refused pair
 * fall back to a shorter, un-stretched ramp; that turned a taste measurement into a
 * precondition, which is the thing this round reverses. Every answer below is a
 * ratio and a semitone count, both of which may be "nothing" — the pair is
 * overlapped either way, and the only consequence of a bad measurement is that the
 * correction is not applied. The reasons are still measured and still named, because
 * "nothing was stretched" has to be readable from the log as a decision rather than
 * as an omission (see {@link #note()}).
 *
 * <p>Immutable, and cheap (twelve-bin arithmetic over two cached profiles): built
 * once per boundary, inside the same decision that picks the transition kind.
 */
public final class MixNaturaliser {

    /**
     * How much the profile distance has to improve for a shift to be worth making
     * without a whole-key relationship behind it ({@link KeyProfile#camelotCompatible}).
     *
     * <p>Two songs in the same key still measure a distance of a few tenths —
     * different arrangement, different instrumentation — so "closer" alone proves
     * nothing. A shift has to move the profiles by this much of their whole mass
     * (and, below, by a quarter of the distance that was there) before it is taken
     * as evidence about the key rather than about the two mixes.
     */
    private static final double MIN_KEY_IMPROVEMENT = 0.05d;

    /** And it has to be a real improvement, not a marginal one: the shifted pair
     *  must be at most this fraction of the unshifted distance. */
    private static final double MAX_IMPROVEMENT_RATIO = 0.75d;

    /**
     * The largest profile distance two keys may have and still be treated as "close
     * enough to mix". Above it a shift is applied only if the profiles come
     * dramatically closer; otherwise nothing is transposed. This is a statement about
     * the shift, never about the blend: a pair this far apart is still overlapped, it
     * simply is not transposed into a key the other one is not in.
     */
    private static final double MAX_KEY_DISTANCE = 0.55d;

    /**
     * The margin the user's rule leaves between the incoming track's pitch being its own
     * again and its vocals arriving: <em>the pitch shift must be back at identity by two
     * seconds before the incoming track's vocals appear</em>. Two seconds is not a taste
     * — it is the room a listener needs for the ease-back to be over rather than merely
     * ending: an ease-back that finishes exactly on the vocal is an ease-back the vocal
     * arrives in the middle of.
     */
    public static final long VOCAL_PITCH_MARGIN_MS = 2_000L;

    /**
     * Whether this boundary may be transposed at all.
     *
     * <p>The rule above has to be affordable: the pitch has to be back at the incoming track's
     * own key before its voice arrives, with {@link #VOCAL_PITCH_MARGIN_MS} of margin — and that
     * margin has to exist at all (a deck whose entry is already past its own vocals, or one whose
     * vocals are in the blend's first sample because it is playing the track's master, gets no
     * transposition: see the {@code vocalInMs} note).
     *
     * <p>⚠️ <b>Round 17 dropped the upper bound this gate used to carry</b>
     * ({@code deadlineMs <= overlapMs}). That bound was about the <em>ladder</em>: its steps write
     * the outgoing deck as well as the incoming one, and the outgoing deck is released when the
     * blend ends, so the last step has to land before that. It is now enforced where it belongs
     * and exactly — {@code PlayerController} clamps the ladder's own end to the last beat at or
     * before the promotion, and a ladder with no room for its steps answers
     * {@link KeyGlide#none} and leaves round 13's single step, which writes the incoming deck
     * alone and is therefore safe after the promotion (that deck is the one still playing).
     * Keeping the old bound here instead would have started refusing transpositions for the very
     * pairs round 17 exists to serve: the incoming track's vocals come back a margin <em>after</em>
     * the blend now, so a pair whose voice returns more than two seconds after the blend's end
     * would have had its transposition silently dropped.
     *
     * @param vocalInMs  how far into the blend's own ramp the incoming track's vocals are
     *                   first heard, ms — which is <em>after</em> the blend for a DJ edit
     *                   (round 17) and 0 for a deck playing the track's own master.
     *                   {@code 0} is not "unknown": it is the honest answer to the rule, and
     *                   it refuses every transposition.
     * @param overlapMs the blend this boundary will really run, ms. No longer part of the
     *                   verdict — kept in the signature because every caller has it and the
     *                   sentence a refusal logs is about the blend the pair will play.
     */
    public static boolean pitchFitsBeforeVocals(long vocalInMs, long overlapMs) {
        return vocalInMs - VOCAL_PITCH_MARGIN_MS >= 0L;
    }

    /**
     * Judge one boundary and answer what to apply to the incoming track.
     *
     * <p>{@code overlapMs} is the length the pair will actually be mixed over (the
     * caller knows the plan), and it matters for the tempo: two tempos that hold
     * together for four seconds may not for fifteen, and the answer must be about
     * the mix that is really going to happen.
     *
     * <p>Never null, and never a refusal: a pair with no grid, no key, tempos too far
     * apart or keys with nothing in common gets a mix that applies nothing and says
     * why in {@link #note()}.
     */
    public static MixNaturaliser between(BeatProfile a, BeatProfile b, long overlapMs) {
        double speed = speedFor(a, b, overlapMs);
        String tempoNote = tempoNote(a, b, overlapMs, speed);
        KeyShift shift = keyShift(a, b);
        String keyNote = shift.note(a, b);
        return new MixNaturaliser(speed, shift.semitones, tempoNote + "; " + keyNote);
    }

    /** Nothing applied, for a stated reason — the answer for a boundary that cannot
     *  even be judged (no grids at all), and the shape of every "off" answer. */
    public static MixNaturaliser nothing(String reason) {
        return new MixNaturaliser(1d, 0, reason);
    }

    /**
     * The tempo the incoming track is played at: 1.0 (its own) unless pulling it onto
     * the outgoing track's grid is both measurable and small.
     *
     * <p>The ratio that puts B's beat where A's beat is is {@code periodB / periodA} —
     * B played {@code ratio} times as fast has period {@code periodB / ratio}, which
     * is periodA exactly. Both grids have to be trusted for that to mean anything:
     * pulling a tempo onto a grid that is itself an artefact of one window would move
     * a real beat onto a wrong one. A pair that is already compatible is left alone (a
     * 0.3% stretch is artefacts bought for nothing), and a pair outside
     * {@link IncomingMix#MAX_SPEED_STEP} is left alone too — the blend still runs, it
     * just is not stretched.
     */
    private static double speedFor(BeatProfile a, BeatProfile b, long overlapMs) {
        if (a == null || b == null || !a.trustworthy() || !b.trustworthy()) return 1d;
        if (BeatProfile.gridsCompatible(a, b, overlapMs)) return 1d;
        double ratio = speedFor(a, b);
        return withinClamp(ratio) ? ratio : 1d;
    }

    /** The measurement behind {@link #speedFor}, for the log. Always non-null. */
    private static String tempoNote(BeatProfile a, BeatProfile b, long overlapMs, double speed) {
        if (a == null || b == null) {
            return "no tempo: no credible grid for " + (a == null ? "A" : "B")
                    + ", so nothing is stretched";
        }
        if (!a.trustworthy() || !b.trustworthy()) {
            BeatProfile weak = !a.trustworthy() ? a : b;
            return "no tempo: the grid for " + (!a.trustworthy() ? "A" : "B")
                    + " is not trustworthy (" + weak.confidenceText() + " < "
                    + BeatProfile.MIN_CONFIDENCE + "), so nothing is stretched";
        }
        if (speed != 1d) {
            return String.format(Locale.US,
                    "speed x%.4f on B (%.1f->%.1fBPM, %+.1f%%)",
                    speed, b.bpm(), b.bpm() * speed, (speed - 1d) * 100d);
        }
        if (BeatProfile.gridsCompatible(a, b, overlapMs)) {
            // Already the same grid. The ratio is still named — a boundary has to say
            // what tempo the incoming track ran at, and "1.0000" is an answer, not an
            // omission.
            return "speed x1.0000 on B (tempo already holds: "
                    + Math.round(BeatProfile.gridDriftMs(a, b, overlapMs))
                    + "ms of drift over " + overlapMs + "ms)";
        }
        return String.format(Locale.US,
                "no tempo: %.1f vs %.1fBPM needs x%.3f on B and the clamp is x%.2f, so nothing"
                        + " is stretched (the blend runs anyway, its beats are just not pulled"
                        + " together)",
                a.bpm(), b.bpm(), speedFor(a, b), 1d + IncomingMix.MAX_SPEED_STEP);
    }

    /** The tempo ratio that puts the incoming track's beat where the outgoing
     *  track's beat is. */
    public static double speedFor(BeatProfile a, BeatProfile b) {
        if (a == null || b == null) return 1d;
        double periodA = a.periodMs();
        if (!(periodA > 0d)) return 1d;
        return b.periodMs() / periodA;
    }

    /** Whether a ratio is small enough to be transparent ({@link IncomingMix#MAX_SPEED_STEP}).
     *  The clamp is on the stretch <em>applied to B</em>, not on the BPM difference:
     *  a -7.5% tempo gap needs +8.1% of stretch and is already outside it. */
    public static boolean withinClamp(double ratio) {
        return Math.abs(ratio - 1d) <= IncomingMix.MAX_SPEED_STEP + 1e-9d;
    }

    /**
     * The incoming track's grid as the listener will actually hear it once
     * {@code speed} is applied: its period scales by {@code 1/speed} (playing faster
     * makes beats come sooner) and its phase with it, because every timestamp in the
     * file is reached that much sooner.
     *
     * <p>This is what makes a stretched overlap alignable: fed to
     * {@link BeatProfile#gridsCompatible} it describes a grid that no longer slides
     * against A's, which is exactly the claim the compatibility gate asks about.
     */
    public static BeatProfile asHeard(BeatProfile b, double speed) {
        if (b == null || speed == 1d || !(speed > 0d)) return b;
        return new BeatProfile(b.bpm() * speed, Math.round(b.firstBeatMs() / speed),
                b.confidence(), b.prominence(), b.key());
    }

    private final double speed;
    private final int semitones;
    private final String note;

    private MixNaturaliser(double speed, int semitones, String note) {
        this.speed = speed;
        this.semitones = semitones;
        this.note = note == null ? "" : note;
    }

    /** The speed the incoming track is played at (1.0 = its own tempo). */
    public double speed() {
        return speed;
    }

    /** The key shift applied to the incoming track, semitones (0 = untouched). */
    public int semitones() {
        return semitones;
    }

    /** The pitch ratio {@link #semitones()} means on the platform's own scale. */
    public double pitch() {
        return Math.pow(2d, semitones / 12d);
    }

    /** Whether the incoming track is played at a different tempo (1.0 = its own). */
    public boolean hasTempo() {
        return speed != 1d;
    }

    /** Whether anything is applied at all (a tempo or a key shift). */
    public boolean appliesSomething() {
        return speed != 1d || semitones != 0;
    }

    /**
     * This same measurement with the transposition <em>not</em> applied, and the reason
     * written where the semitones would have been.
     *
     * <p>What it is for is the user's vocal rule: the harmony was measured and a shift
     * was worth making, and the boundary still gets no transposition, because the blend
     * cannot afford to have it back in the track's own key before that track's vocals
     * arrive (see {@link #pitchFitsBeforeVocals}). The tempo is kept — it is what the
     * overlap is aligned on — and the log line keeps the two halves it always has (what
     * was applied to the tempo, and what was applied to the key), so "this pair was not
     * transposed" cannot be read as "this pair had no transposition available".
     *
     * @param why what made the shift unaffordable, with the times; never null.
     */
    public MixNaturaliser onlyTempo(String why) {
        if (semitones == 0) return this;
        return new MixNaturaliser(speed, 0,
                tempoNote() + "; pitch x1.0000 on B (dropped: " + why + ")");
    }

    /** One fragment for the boundary's single log line: what is done to the incoming
     *  track, or the measurement that said nothing should be. Both halves are always
     *  present — a tempo ratio and a semitone count — so "nothing was applied" can
     *  never be confused with "this build has no such thing". */
    public String note() {
        return note;
    }

    /** The tempo half of {@link #note()}, for a caller that prints the two halves in
     *  different fragments. Never null. */
    public String tempoNote() {
        int cut = note.indexOf("; ");
        return cut < 0 ? note : note.substring(0, cut);
    }

    /** The key-shift half of {@link #note()}. Never null. */
    public String keyNote() {
        int cut = note.indexOf("; ");
        return cut < 0 ? note : note.substring(cut + 2);
    }

    @Override
    public String toString() {
        return appliesSomething()
                ? "MixNaturaliser{x" + String.format(Locale.US, "%.4f", speed)
                        + ", " + semitones + " semitones}"
                : "MixNaturaliser{none: " + note + "}";
    }

    // --- the key arithmetic -------------------------------------------------

    /** The judgement about the two keys: the shift to apply (0 = none) and the
     *  measurements the log needs to explain it. */
    private static final class KeyShift {
        final int semitones;
        /** The distance with that shift, and without any. */
        final double distance;
        final double atZero;
        /** The best shift that a whole-key relationship (or a dramatic improvement)
         *  allowed, whether or not it was worth making — the difference between "no
         *  shift was ever available" and "the shift was not worth making". */
        final int allowed;
        final double allowedDistance;
        /** Whether the keys could be judged at all. */
        final boolean measured;

        KeyShift(int semitones, double distance, double atZero, int allowed,
                 double allowedDistance, boolean measured) {
            this.semitones = semitones;
            this.distance = distance;
            this.atZero = atZero;
            this.allowed = allowed;
            this.allowedDistance = allowedDistance;
            this.measured = measured;
        }

        /** This same measurement as the public value a chooser can read (see {@link Keys}). */
        Keys keys() {
            return new Keys(measured, atZero, distance, semitones, allowed, allowedDistance);
        }

        String note(BeatProfile a, BeatProfile b) {
            if (!measured) {
                if (a == null || b == null || a.key() == null || b.key() == null) {
                    return "no pitch: no key measured for "
                            + (a == null || a.key() == null ? "A" : "B");
                }
                boolean aWeak = !a.key().trustworthy();
                return "no pitch: the key for " + (aWeak ? "A" : "B") + " is not trustworthy ("
                        + (aWeak ? a.key() : b.key()).label() + ")";
            }
            if (semitones != 0) {
                KeyProfile kb = b.key();
                KeyProfile moved = kb.shifted(semitones);
                return String.format(Locale.US,
                        "pitch x%.4f on B (%+d semitone%s: %s (%s) -> %s (%s), chroma distance"
                                + " %.2f->%.2f)",
                        Math.pow(2d, semitones / 12d), semitones,
                        Math.abs(semitones) == 1 ? "" : "s",
                        kb.label(), KeyProfile.camelotLabel(kb.tonic(), kb.major()),
                        moved.label(), KeyProfile.camelotLabel(moved.tonic(), moved.major()),
                        atZero, distance);
            }
            if (atZero > MAX_KEY_DISTANCE) {
                return String.format(Locale.US,
                        "no pitch: the keys are %.2f apart%s (nothing is transposed — measured,"
                                + " and the blend runs anyway)",
                        atZero,
                        allowed == 0
                                ? " and no shift within ±" + IncomingMix.MAX_SEMITONES
                                        + " brings them together"
                                : String.format(Locale.US,
                                        " and the best allowed shift, %+d, only reaches %.2f",
                                        allowed, allowedDistance));
            }
            // The shift that is applied is named even when it is none, and an allowed
            // shift that would not have moved the profiles says so: that is the
            // difference between "no shift was ever available" and "the shift was not
            // worth making". A shift that was allowed but does not clear the bar is NOT
            // a clash — the profiles are already closer than MAX_KEY_DISTANCE, which is
            // this class's own statement that the two keys can be heard together.
            return "pitch x1.0000 on B (0 semitones: keys already sit together, "
                    + a.key().label() + " / " + b.key().label()
                    + ", chroma distance " + String.format(Locale.US, "%.2f", atZero)
                    + (allowed == 0 ? "" : String.format(Locale.US,
                            "; %+d was allowed but only reaches %.2f, so nothing is applied",
                            allowed, allowedDistance))
                    + ")";
        }
    }

    /**
     * The pair's key measurement, as a value a caller other than the shift decision can read:
     * whether the keys could be judged at all, how far apart they are as they stand, and the
     * shift (if any) that was sanctioned and worth making.
     *
     * <p>⚠️ Round 17 hoists this out of the private arithmetic because a <em>chooser</em> now has
     * to answer with it: {@link HeuristicTransitionChooser} asks "do these two tracks overlap
     * badly?" — and that question is exactly "their keys clash and their tempos cannot be
     * pulled onto one grid", which is this object's {@link #clash()} plus the tempo half. Before
     * this, the same numbers existed but only the shift decision could see them.
     */
    public static final class Keys {
        /** Whether both keys were measured and are trustworthy — the gate every answer below
         *  is behind. */
        public final boolean measured;
        /** The profile distance as the two keys stand (0 = as close as the measurement gets). */
        public final double distance;
        /** The distance with {@link #shift} applied. */
        public final double distanceAfter;
        /** The shift that was both allowed and worth making, semitones; 0 = none. */
        public final int shift;
        /** The best shift that was allowed at all, whether worth making or not. */
        public final int allowedShift;
        /** Its distance. */
        public final double allowedDistance;

        Keys(boolean measured, double distance, double distanceAfter, int shift,
             int allowedShift, double allowedDistance) {
            this.measured = measured;
            this.distance = distance;
            this.distanceAfter = distanceAfter;
            this.shift = shift;
            this.allowedShift = allowedShift;
            this.allowedDistance = allowedDistance;
        }

        /** The two keys are measured and nothing brings them together: the pair is in two keys
         *  at once, which is a clash rather than a nuance. False when they were not measured at
         *  all — an unmeasured pair is not evidence of a clash. */
        public boolean clash() {
            return measured && shift == 0 && distance > MAX_KEY_DISTANCE;
        }

        /** The shift that is worth making would move the profiles by less than
         *  {@link #MIN_KEY_IMPROVEMENT} — a shift that was available but would not have bought
         *  anything. Named separately from {@link #clash()} because the two log differently:
         *  "the keys already sit together" is not "the keys are too far apart to mix". */
        public boolean shiftNotWorthMaking() {
            return measured && allowedShift != 0 && shift == 0;
        }

        @Override
        public String toString() {
            if (!measured) return "keys{not measured}";
            return String.format(Locale.US, "keys{distance %.2f, shift %+d -> %.2f}",
                    distance, shift, distanceAfter);
        }
    }

    /** This pair's key measurement: see {@link Keys}. Never null. */
    public static Keys keys(BeatProfile a, BeatProfile b) {
        return keyShift(a, b).keys();
    }

    /**
     * Choose the shift, if any. Only within ±{@link IncomingMix#MAX_SEMITONES}, and
     * only when it is both allowed (a whole-key relationship, or an improvement too
     * large to be about the two arrangements) and worth making (a real move of the
     * profiles, and a shifted pair that is genuinely closer than the unshifted one).
     */
    private static KeyShift keyShift(BeatProfile a, BeatProfile b) {
        if (a == null || b == null || a.key() == null || b.key() == null
                || !a.key().trustworthy() || !b.key().trustworthy()) {
            return new KeyShift(0, 1d, 1d, 0, 1d, false);
        }
        double[] chromaA = a.key().chroma();
        double[] chromaB = b.key().chroma();
        double atZero = KeyProfile.distance(chromaA, chromaB);
        int bestShift = 0;
        double bestDistance = atZero;
        for (int n = 0; n <= IncomingMix.MAX_SEMITONES; n++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                if (n == 0 && sign < 0) continue;
                int shift = n * sign;
                double distance = KeyProfile.distance(chromaA, KeyProfile.rotated(chromaB, shift));
                if (!sanctioned(a.key(), b.key(), shift, distance, atZero)) continue;
                if (distance < bestDistance - 1e-9d) {
                    bestDistance = distance;
                    bestShift = shift;
                }
            }
        }
        boolean worthShifting = bestShift != 0
                && atZero - bestDistance >= MIN_KEY_IMPROVEMENT
                && bestDistance <= MAX_KEY_DISTANCE
                && sanctionedByImprovement(bestDistance, atZero);
        if (!worthShifting) {
            return new KeyShift(0, atZero, atZero, bestShift, bestDistance, true);
        }
        return new KeyShift(bestShift, bestDistance, atZero, bestShift, bestDistance, true);
    }

    /**
     * Whether a shift is allowed to be considered at all: either the two keys are
     * whole-key neighbours after it (the rule a DJ mixes by, and the one that cannot
     * be fooled by a profile that merely got closer), or the profiles came so much
     * closer that the shift is evidence about the key rather than about the two
     * arrangements.
     */
    private static boolean sanctioned(KeyProfile a, KeyProfile b, int semitones,
                                      double distance, double atZero) {
        if (a.camelotCompatible(b, semitones)) return true;
        return sanctionedByImprovement(distance, atZero);
    }

    private static boolean sanctionedByImprovement(double distance, double atZero) {
        return distance <= atZero * MAX_IMPROVEMENT_RATIO
                && atZero - distance >= MIN_KEY_IMPROVEMENT;
    }
}
