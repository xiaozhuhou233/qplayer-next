package dev.t1m3.qplayer.audio;

/**
 * A track's estimated key: which pitch class the music sits on ({@code tonic}),
 * whether it is major or minor, how sure the estimate is ({@code strength}), and
 * the twelve-bin pitch-class profile it was read from ({@code chroma}).
 *
 * <p>This is what lets a transition do more than align the beats: two tracks in
 * clashing keys mixed together is the one thing harmonic mixing exists to avoid,
 * and a key estimate plus a pitch-class profile is enough to answer the two
 * questions that matter — "how far apart are these two harmonically" and "would
 * moving one of them by a semitone or two make it better".
 *
 * <p>The key <em>name</em> is the part to be careful with. It is a single
 * answer to a question real music mostly does not have one answer for (a loop of
 * Am-F-C-G is as much C major as A minor), so it is reported and logged but
 * never acted on alone: the shift is chosen from the profile distance below,
 * with the name only able to confirm or veto a candidate (see
 * {@link #camelotCompatible}). The profile is the measurement; the name is a
 * summary of it.
 *
 * <p>Immutable, and cached per track with the beat grid it was measured beside
 * (see {@link BeatProfile}'s disk form), because both come out of the same
 * decode.
 */
public final class KeyProfile {

    /** Below this {@link #strength()} the estimate is reported and logged but
     *  never acted on: a wrong key acted on detunes a track against the music
     *  the listener knows, which is worse than the clash it was meant to fix.
     *  {@link #trustworthy()} applies {@link #MIN_TRIAD} as well.
     *
     *  <p>The number is unchanged from the calibration that produced it; what
     *  changed is the measure behind it, which is now the margin over the best key
     *  the winner could <em>not</em> be mixed with ({@link KeyAnalysis}), scaled so
     *  that this reads directly as a correlation margin. Measured on 36 cached
     *  windows of the app's own library: 0.012-1.54, median 0.44, and 24 of the 36
     *  clear 0.25 — where the previous measure passed 8 of the same 36 and named 16
     *  of them A minor. Material with no key stays below: white noise 0.09, a bare
     *  sine 0.00, a pitchless drum loop 0.19. The one class of material the margin
     *  alone does not refuse is noise with a fixed spectral envelope (0.47), which
     *  is what {@link #MIN_TRIAD} is for.
     *
     *  <p>It is deliberately the *lower* edge — of what this measure can separate,
     *  not of the whole spread — for the same reason {@link #MIN_CONFIDENCE} is:
     *  the cost of refusing a real key is that a pair is not transposed (today's
     *  behaviour), while the cost of trusting a wrong one is a track that sounds
     *  out of tune. The remaining protection against a wrong-but-confident key is
     *  in the shift decision itself ({@code MixNaturaliser}: the improvement has to
     *  be real, and the whole-key relationship has to hold). */
    public static final float MIN_STRENGTH = 0.25f;

    /**
     * The weight the winner's own tonic, third and fifth must carry between them
     * for the estimate to be acted on. A profile spread evenly over the twelve
     * pitch classes puts 0.25 there; a profile that really sits on a key puts
     * much more.
     *
     * <p>This is the second, independent condition {@link #trustworthy()} applies,
     * and it is here because the first one alone does not separate real music from
     * material with no key at all. Measured on the app's own 36-track cache: the
     * margin the first condition reads is 0.19-1.5 across real tracks but one
     * constructed negative — noise with a fixed spectral envelope, which is what
     * speech and applause look like to a pitch-class profile — scored 0.47, above
     * much of the library. Its profile was flat: 0.270 on the winner's triad
     * against a uniform 0.25, where the real tracks ran 0.26-0.41. Neither number
     * separates on its own; together they do, and both negatives (that noise, and
     * a pitchless drum loop at 0.192/0.272) fall outside the gate while 21 of the
     * 36 real tracks stay inside it.
     *
     * <p>The threshold is the recorded gap's edge rather than its middle, for the
     * same asymmetric reason as {@link #MIN_STRENGTH}: refusing a real key costs a
     * pair its transposition (today's behaviour), while trusting a wrong one
     * detunes a track the listener knows.
     */
    public static final float MIN_TRIAD = 0.28f;

    private final int tonic;
    private final boolean major;
    private final float strength;
    private final double[] chroma;

    /**
     * @param tonic    the key's tonic as a pitch class, 0 = C … 11 = B
     * @param major    true for a major key, false for minor
     * @param strength 0..1, the estimator's own confidence in this answer
     * @param chroma   the twelve-bin pitch-class profile, normalised to sum 1
     */
    public KeyProfile(int tonic, boolean major, float strength, double[] chroma) {
        this.tonic = Math.floorMod(tonic, 12);
        this.major = major;
        this.strength = Math.max(0f, Math.min(1f, strength));
        this.chroma = normalise(chroma);
    }

    /** The tonic as a pitch class: 0 = C. */
    public int tonic() {
        return tonic;
    }

    public boolean major() {
        return major;
    }

    /** 0..1; see {@link #MIN_STRENGTH}. */
    public float strength() {
        return strength;
    }

    /** Whether the key name may be used to sanction a shift at all. Two independent
     *  conditions, both of which the cache form carries: the margin over the keys
     *  this one could not be mixed with ({@link #strength()}) and the weight the
     *  winner's own triad carries ({@link #triadWeight()}). */
    public boolean trustworthy() {
        return strength >= MIN_STRENGTH && triadWeight() >= MIN_TRIAD;
    }

    /**
     * How much of the profile sits on this key's own tonic, third and fifth —
     * 0.25 when the profile says nothing (the twelve pitch classes equally
     * weighted). Derived from {@link #chroma()} rather than stored, so it is exact
     * for a fresh measurement and to within a byte's worth of 255 for one read back
     * from disk ({@link #toBytes()} keeps the profile's shape, which is all this
     * reads).
     */
    public double triadWeight() {
        int third = major ? 4 : 3;
        return chroma[tonic] + chroma[(tonic + third) % 12] + chroma[(tonic + 7) % 12];
    }

    /** The twelve-bin profile, summing to 1. Never null; a defensive copy. */
    public double[] chroma() {
        double[] copy = new double[12];
        System.arraycopy(chroma, 0, copy, 0, 12);
        return copy;
    }

    /** "C major/0.62 (triad 0.34)" — one token for the boundary's log line. The
     *  triad is printed beside the strength because a gate that can refuse on it
     *  has to be diagnosable from the same line, and the two are separate
     *  conditions: "the margin says this is the key" and "the profile really sits
     *  on it". */
    public String label() {
        return String.format(java.util.Locale.US, "%s %s/%.2f (triad %.2f)", noteName(tonic),
                major ? "major" : "minor", strength, triadWeight());
    }

    /** "C" / "C#" / "A" — the pitch class's name. */
    public static String noteName(int pitchClass) {
        return PITCH_NAMES[Math.floorMod(pitchClass, 12)];
    }

    private static final String[] PITCH_NAMES =
            {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    // --- the two metrics the shift decision is made from ----------------------

    /**
     * How far apart two profiles are, 0 (the same shape) to 1 (nothing in
     * common): the total-variation distance of the two distributions, i.e. half
     * the sum of the per-pitch-class differences of two profiles that each sum
     * to one. Chosen over a correlation because it has a unit that can be argued
     * about ("this much of the profile's weight sits somewhere else"), because it
     * is bounded, and because it is symmetric — the decision reads both
     * directions of a pair.
     */
    public static double distance(double[] a, double[] b) {
        if (a == null || b == null || a.length != 12 || b.length != 12) return 1d;
        double sum = 0d;
        for (int i = 0; i < 12; i++) sum += Math.abs(a[i] - b[i]);
        return Math.max(0d, Math.min(1d, sum / 2d));
    }

    /**
     * {@code chroma} with every pitch class moved up by {@code semitones} — what a
     * track's profile becomes when it is played a semitone or two higher.
     *
     * <p>Direction matters: shifting the audio up moves the energy the profile
     * found at C to C#, so the answer at index {@code i} is the original's value
     * at {@code i - semitones}.
     */
    public static double[] rotated(double[] chroma, int semitones) {
        double[] out = new double[12];
        if (chroma == null || chroma.length != 12) return out;
        for (int i = 0; i < 12; i++) {
            out[i] = chroma[Math.floorMod(i - semitones, 12)];
        }
        return out;
    }

    /** This key moved up by {@code semitones} (same mode) — the key a track would
     *  be in if its pitch were shifted. */
    public KeyProfile shifted(int semitones) {
        return new KeyProfile(tonic + semitones, major, strength, rotated(chroma, semitones));
    }

    // --- Camelot compatibility -------------------------------------------------

    /**
     * The Camelot wheel position of a key, 1..12, as the number DJs mix by: 8A is
     * A minor and 8B its relative major C, 9A the fifth above (E minor) and 7A
     * the fifth below (D minor). Two keys are neighbours on the wheel exactly when
     * they are the same tonic, a relative major/minor, or a fifth apart — the
     * three relationships that share enough notes to be mixed.
     */
    public static int camelotNumber(int tonic, boolean major) {
        // Counted from 8A = A minor: a key shares its number with its relative
        // major/minor (C major's relative minor is A, three semitones DOWN), and
        // moving a fifth up (7 semitones) moves the wheel by one. Multiplying the
        // semitone distance from A by 7 is exactly that indexing.
        int minorTonic = Math.floorMod(major ? tonic - 3 : tonic, 12);
        int number = Math.floorMod((minorTonic - 9) * 7, 12) + 8;
        return number > 12 ? number - 12 : number;
    }

    /** The wheel's letter: major keys are the B row, minor the A row. */
    public static char camelotLetter(boolean major) {
        return major ? 'B' : 'A';
    }

    /** "8A" / "12B" — the wheel position, for the log. */
    public static String camelotLabel(int tonic, boolean major) {
        return camelotNumber(tonic, major) + String.valueOf(camelotLetter(major));
    }

    /**
     * Whether these two keys are the ones a DJ would mix, allowing {@code other}
     * to have been moved by {@code semitones} first: the same wheel number (the
     * same key, or the relative major/minor) or one step away (a fifth apart,
     * either direction). This is the rule that keeps a mis-estimated key from
     * detuning a track: the shift has to be sanctioned by a relationship that
     * holds between whole keys, not only by a profile that got closer.
     */
    public boolean camelotCompatible(KeyProfile other, int semitones) {
        if (other == null) return false;
        KeyProfile moved = other.shifted(semitones);
        int mine = camelotNumber(tonic, major);
        int theirs = camelotNumber(moved.tonic, moved.major);
        int step = Math.abs(mine - theirs);
        return step == 0 || step == 1 || step == 11;
    }

    // --- disk form ------------------------------------------------------------

    /**
     * Twelve bytes: the tonic and the mode, the strength in hundredths, and the
     * profile quantised to bytes of its own maximum. The profile is what the
     * shift decision uses, so it travels with the key rather than being
     * recomputed — and 8 bits per bin is far finer than any of these thresholds
     * (the distance metric's smallest useful step is ~0.05 of the whole
     * distribution, while one byte of a normalised bin is 0.004).
     */
    public byte[] toBytes() {
        byte[] out = new byte[15];
        out[0] = (byte) tonic;
        out[1] = (byte) (major ? 1 : 0);
        out[2] = (byte) Math.round(strength * 100f);
        double max = 0d;
        for (double v : chroma) max = Math.max(max, v);
        for (int i = 0; i < 12; i++) {
            out[3 + i] = (byte) (max > 0d ? Math.round(chroma[i] / max * 255d) : 0);
        }
        return out;
    }

    /** Inverse of {@link #toBytes()}; null when the bytes are not one. */
    public static KeyProfile fromBytes(byte[] data, int offset) {
        if (data == null || data.length < offset + 15) return null;
        int tonic = data[offset] & 0xFF;
        int mode = data[offset + 1] & 0xFF;
        int strength = data[offset + 2] & 0xFF;
        if (tonic > 11 || mode > 1) return null;
        double[] chroma = new double[12];
        double sum = 0d;
        for (int i = 0; i < 12; i++) {
            chroma[i] = (data[offset + 3 + i] & 0xFF) / 255d;
            sum += chroma[i];
        }
        if (!(sum > 0d)) return null;
        for (int i = 0; i < 12; i++) chroma[i] /= sum;
        return new KeyProfile(tonic, mode == 1, strength / 100f, chroma);
    }

    private static double[] normalise(double[] chroma) {
        double[] out = new double[12];
        if (chroma == null) return out;
        double sum = 0d;
        for (int i = 0; i < 12 && i < chroma.length; i++) {
            out[i] = Math.max(0d, chroma[i]);
            sum += out[i];
        }
        if (sum <= 0d) return out;
        for (int i = 0; i < 12; i++) out[i] /= sum;
        return out;
    }

    @Override
    public String toString() {
        return "KeyProfile{" + label() + ", camelot " + camelotLabel(tonic, major) + "}";
    }
}
