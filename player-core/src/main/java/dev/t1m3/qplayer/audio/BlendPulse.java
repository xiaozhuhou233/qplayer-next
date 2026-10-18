package dev.t1m3.qplayer.audio;

/**
 * Whether a blend keeps a beat: the measurement behind "the first track's rhythm must not
 * end early, and the blend must never pass through a beatless stretch".
 *
 * <p>The complaint this answers is a listening one — "it still fades, it does not mix" — and
 * the usual diagnosis is about levels. It is not only about levels. The outgoing track's
 * rhythm is carried by its transients (kick, snare, hats) and the app takes its <em>low
 * end</em> away in one step partway through the ramp (the hand-over, see
 * {@code AndroidAudioBackend.bassSwapNow}): from that instant the outgoing track no longer
 * has its kick, whatever its gain curve says. So this class reports the two things about
 * that which can be measured on the app's own material:
 *
 * <ol>
 *   <li><b>The low end's level through the blend</b>, slice by slice, and how far the
 *       quietest slice falls below the loudest ({@link Result#lowHoleDb}). The hand-over
 *       moves the kick from one deck to the other; if the incoming deck's low end is quieter
 *       than the outgoing deck's was — the ordinary case, since two tracks rarely have bass
 *       at the same level — the blend's bottom drops at that instant, and this is that drop,
 *       measured.</li>
 *   <li><b>The ordering of the two events</b>: when the incoming track's content is up to
 *       within {@link #ESTABLISHED_DB} of its own full level
 *       ({@link Result#incomingEstablishedAt}, read off its gain curve, exact) and when the
 *       outgoing track's low end is taken away ({@link Result#outgoingKickEndsAt}, the
 *       hand-over's own instant, exact). The requirement is the first being at or before the
 *       second, so the low end is never handed to a deck that has not arrived.</li>
 * </ol>
 *
 * <p>Together with {@link FadeCurve#bothAudibleMs} (the arithmetic window in which both
 * tracks are within 6 dB of each other) and {@link Result#maxPowerDb} (what holding both
 * tracks up costs), those are the numbers a curve or a hand-over change is decided on.
 *
 * <p>⚠️ <b>The pulse itself is not measured here, and the attempts are worth recording.</b>
 * Three instruments were tried for "is there a beat in this slice" and none of them
 * separates pulse from no-pulse on this library's real material:
 * <ul>
 *   <li><b>Low-band level ratio</b> (on-beat low-band energy over the slice's own average):
 *       measured on a track with a plain 125 BPM beat, it reads <b>0.54-2.15 at every grid
 *       phase</b> — the low end of real music is a sustained bass with kicks on top, so a
 *       level ratio inside that band barely moves.</li>
 *   <li><b>Onset-envelope share</b> (the whitened onset flux at the grid over the slice's
 *       average): the same track and the same sweep, <b>0.4-1.8</b> — a dense mix has onsets
 *       on every subdivision, so the beat's own onsets are only a modest part of them.</li>
 *   <li><b>On-beat energy against the <em>between</em>-beats energy</b> (the classic
 *       prominence measure): a ratio of two quantities that are both nearly zero whenever
 *       the incoming track's low end is cut, which is exactly the case the hand-over creates;
 *       measured, it placed a "beatless stretch" in the middle of a mix at full level.</li>
 * </ul>
 * The estimator's own comb score does separate them (0.5-0.9 on this material) but needs a
 * thirty-second window, so it cannot localise a two-second stretch inside a blend.
 * Localising the beat means keeping the outgoing track's <em>percussion</em> while its body
 * goes, which needs that track's stems — see {@code AI_HANDOFF} §7, round 12. The unit tests
 * keep the one arrangement where the answer is known synthetically (two tracks kicking on
 * the same grid versus two beds with no attacks), which is what says the instrument would
 * work if the material cooperated.
 *
 * <p>⚠️ <b>The low-end hand-over is modelled, not simulated.</b> {@link #LOW_CORNER_HZ} and
 * {@link #BASS_CUT_DB} stand in for the platform {@code Equalizer} the device applies: a
 * band below 200 Hz cut to the device's minimum gain, on one deck at a time. The device's
 * real response is its own (five bands, its own centre frequencies and Q), so every number
 * here is a comparison between two arrangements on the same material — which is what it is
 * for — and not a prediction of the exact dB a listener hears.
 */
public final class BlendPulse {

    /** Analysis frame, seconds: the same 40 ms the beat estimator uses, because a kick's
     *  attack is what is being looked for and that is the scale it lives on. */
    public static final double FRAME_SEC = 0.04;

    /** Analysis hop, seconds — the resolution of everything below. */
    public static final double HOP_SEC = 0.01;

    /** How long one reported slice is, seconds. Two seconds is about a bar at the tempos this
     *  library holds, which is the shortest stretch a listener would call "the beat went
     *  away". */
    public static final double SLICE_SEC = 2.0;

    /** Where the low end is handed over: the bands below this are the ones the device's
     *  equalizer cuts on whichever deck has just given the bass up. */
    public static final double LOW_CORNER_HZ = 200d;

    /** How far the low end is cut, dB — the device's own minimum band gain, which on the
     *  reference device is about -15 dB ("a full cut rather than a polite one"). */
    public static final double BASS_CUT_DB = -15d;

    /** How close to its own full level the incoming track's content has to be for that
     *  track's rhythm to count as <em>established</em>, dB. Three decibels is the usual
     *  "plainly there" line: at that point its percussion is audible as itself rather than as
     *  a thickening of the outgoing track.
     *
     *  <p>⚠️ This is asked of the incoming track's <b>whole</b> mix, and answered
     *  arithmetically from its own gain curve — deliberately. The low end of the incoming
     *  track is <em>withheld</em> until the hand-over, so a low-band version of this number
     *  could not precede the hand-over by construction and the ordering rule would be
     *  circular; and "is this track's content there" is a statement about the gain, which the
     *  curve knows exactly and which no measurement improves on. */
    public static final double ESTABLISHED_DB = -3d;

    /** Below this a slice counts as "nothing is playing". In the normalised samples this
     *  class is handed, -120 dBFS, which is far below anything a master contains and far
     *  above the arithmetic noise of the filter. It exists because a ratio of two silent
     *  stretches is not a measurement. */
    public static final double SILENCE_FLOOR = 1e-12d;

    private BlendPulse() {}

    /** What a blend measured. */
    public static final class Result {
        /** False when no grid was handed in: nothing about the beat is then reported. */
        public final boolean hasGrid;
        /** Progress of the ramp (0–1) at the middle of each slice. */
        public final double[] sliceProgress;
        /** Per slice: the blended low band's average level, dB relative to the loudest slice
         *  of the window. 0 is the loudest slice; -13 is a fifth of the bottom gone. */
        public final double[] lowLevelDb;
        /** The exact progress at which the outgoing track's low end is taken away — the
         *  hand-over's own instant, not a measurement — or 1.0 when there is no hand-over
         *  (the switch is off, or the device has no equalizer). */
        public final double outgoingKickEndsAt;
        /** The progress at which the incoming track's content is within
         *  {@link #ESTABLISHED_DB} of its own full level — from its gain curve, exactly. */
        public final double incomingEstablishedAt;
        /** How far the low band ever falls below its own loudest slice, dB (≤ 0). A large
         *  negative number is a hole in the low end: the two decks' bass giving out at
         *  different moments, or one track's bass simply being quieter than the other's. */
        public final double lowHoleDb;
        /** The loudest the summed blend gets, dB relative to one track at unity: the cost of
         *  holding both tracks up at once, and the thing that stops this from being solved by
         *  simply raising the outgoing curve. */
        public final double maxPowerDb;
        /** The outgoing track's own level at three quarters of the ramp, dB — one clean
         *  number for "held longer", so a curve change can be described without a table. */
        public final double outgoingAt75PctDb;

        Result(boolean hasGrid, double[] sliceProgress, double[] lowLevelDb,
               double outgoingKickEndsAt, double incomingEstablishedAt, double lowHoleDb,
               double maxPowerDb, double outgoingAt75PctDb) {
            this.hasGrid = hasGrid;
            this.sliceProgress = sliceProgress;
            this.lowLevelDb = lowLevelDb;
            this.outgoingKickEndsAt = outgoingKickEndsAt;
            this.incomingEstablishedAt = incomingEstablishedAt;
            this.lowHoleDb = lowHoleDb;
            this.maxPowerDb = maxPowerDb;
            this.outgoingAt75PctDb = outgoingAt75PctDb;
        }

        /** The one line a measurement is read as. */
        public String describe() {
            String levels = String.format(java.util.Locale.US,
                    "the blend's low band runs %s and its deepest hole is %.1fdB below its"
                            + " loudest slice; the outgoing is at %.1fdB at three quarters and"
                            + " the blend peaks at %.1fdB",
                    series(lowLevelDb), lowHoleDb, outgoingAt75PctDb, maxPowerDb);
            if (!hasGrid) {
                return levels + "; no grid was handed in, so nothing about the beat is being"
                        + " measured";
            }
            return levels + String.format(java.util.Locale.US,
                    "; the outgoing track's low end is taken away at %.0f%% of the ramp and the"
                            + " incoming track is established at %.0f%% (%s)",
                    outgoingKickEndsAt * 100d, incomingEstablishedAt * 100d,
                    incomingEstablishedAt <= outgoingKickEndsAt
                            ? "no gap: the low end changes hands after the new track is in place"
                            : "AFTER the hand-over, so the low end is handed to a deck that has"
                                    + " not arrived yet");
        }

        /** "0.0 -0.2 -1.3 …" — the level column as one short string. */
        private static String series(double[] values) {
            StringBuilder sb = new StringBuilder();
            for (double v : values) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(String.format(java.util.Locale.US, "%.1f", v));
            }
            return sb.toString();
        }
    }

    /** Measures one blend without a grid: the levels, the hand-over and the power cost, and
     *  nothing about the beat. */
    public static Result measure(float[] aTail, float[] bHead, int rate, long rampMs,
                                 FadeCurve curve, long swapAtMs) {
        return measure(aTail, bHead, rate, rampMs, curve, swapAtMs, -1d);
    }

    /**
     * Measures one blend: {@code aTail} and {@code bHead} are the two windows as the decks
     * would play them (the outgoing track's last {@code rampMs}, the incoming track's first
     * {@code rampMs} from its entry), at the rate the measurement wants.
     *
     * @param curve        the gain shape (the app's own {@link FadeCurve}, so a candidate can
     *                     be measured before it is written down as one)
     * @param swapAtMs     when the low end changes hands, ms into the ramp, or {@code -1} for
     *                     "no hand-over" (the switch is off, or the device has no equalizer)
     *                     — which is itself one of the comparisons worth making
     * @param beatPeriodMs the outgoing track's beat period, ms, or {@code -1} for "no grid"
     *                     (then only the levels are reported)
     */
    public static Result measure(float[] aTail, float[] bHead, int rate, long rampMs,
                                 FadeCurve curve, long swapAtMs, double beatPeriodMs) {
        return measure(aTail, bHead, rate, rampMs,
                new Curve() {
                    @Override public double outGain(double t) { return curve.outGain((float) t); }
                    @Override public double inGain(double t) { return curve.inGain((float) t); }
                    @Override public String label() { return curve.name(); }
                }, swapAtMs, beatPeriodMs);
    }

    /**
     * The same for a shape that is not (yet) an enum constant: the two exponents of the
     * staging — the outgoing's hold ({@code cos(t^p · π/2)}) and the incoming's bed
     * ({@code sin(t^q · π/2)}). This is how a candidate is chosen before it is written down
     * as a curve, so the tuning has a measurement behind it rather than a taste.
     */
    public static Result measure(float[] aTail, float[] bHead, int rate, long rampMs,
                                 double outExponent, double inExponent, long swapAtMs,
                                 double beatPeriodMs) {
        final double p = outExponent;
        final double q = inExponent;
        return measure(aTail, bHead, rate, rampMs, new Curve() {
            @Override public double outGain(double t) {
                return Math.cos(Math.pow(clamp(t), p) * Math.PI / 2d);
            }
            @Override public double inGain(double t) {
                return Math.sin(Math.pow(clamp(t), q) * Math.PI / 2d);
            }
            @Override public String label() {
                return String.format(java.util.Locale.US, "cos(t^%.2f)/sin(t^%.2f)", p, q);
            }
        }, swapAtMs, beatPeriodMs);
    }

    /** A gain shape, so a candidate can be measured without being an enum constant. */
    public interface Curve {
        double outGain(double t);
        double inGain(double t);
        String label();
    }

    private static double clamp(double t) {
        return t <= 0d ? 0d : (t >= 1d ? 1d : t);
    }

    // --- the measurement ----------------------------------------------------

    private static Result measure(float[] aTail, float[] bHead, int rate, long rampMs,
                                  Curve curve, long swapAtMs, double beatPeriodMs) {
        int n = Math.round(rampMs * rate / 1000f);
        n = Math.min(n, Math.min(aTail.length, bHead.length));
        boolean hasGrid = beatPeriodMs > 0d;
        // When the incoming track's own content is up to within ESTABLISHED_DB of its full
        // level: read off its gain curve, which is exact, and comparable with the hand-over's
        // own instant. 400 samples puts the resolution far below anything audible.
        double established = 1d;
        double want = Math.pow(10d, ESTABLISHED_DB / 20d);
        for (int i = 0; i <= 400; i++) {
            double t = i / 400d;
            if (curve.inGain(t) >= want) {
                established = t;
                break;
            }
        }
        long sliceMs = Math.max(1L, Math.round(SLICE_SEC * 1000d));
        int slices = (int) Math.max(1L, (rampMs + sliceMs - 1) / sliceMs);
        double[] progress = new double[slices];
        double[] lowLevelDb = new double[slices];
        double kickEnds = swapAtMs > 0L ? Math.min(1d, swapAtMs / (double) rampMs) : 1d;
        if (n < rate / 4) {
            // Less than a quarter of a second is not a blend; an empty answer is better than
            // a number taken from noise.
            for (int s = 0; s < slices; s++) {
                progress[s] = (s + 0.5) / slices;
            }
            return new Result(hasGrid, progress, lowLevelDb, kickEnds, established, 0d, 0d, 0d);
        }
        // The two decks as they sound, sample by sample. Each deck = its low band at the
        // hand-over's gain + the rest of it at the curve's gain, which is what the device
        // plays: the outgoing track's low end is cut once the hand-over has happened, and the
        // incoming track's is cut until it has.
        float[] aLow = lowpass(aTail, rate);
        float[] bLow = lowpass(bHead, rate);
        float[] sumLow = new float[n];
        double lowCutOut = 0d;
        double lowCutIn = swapAtMs >= 0L ? BASS_CUT_DB : 0d;
        long swapFrom = swapAtMs > 0L ? swapAtMs * rate / 1000L : Long.MAX_VALUE;
        double maxPower = 0d;
        for (int i = 0; i < n; i++) {
            double t = n <= 1 ? 0d : i / (double) (n - 1);
            double ga = curve.outGain(t);
            double gb = curve.inGain(t);
            if (swapAtMs >= 0L && i >= swapFrom) {
                lowCutOut = BASS_CUT_DB;
                lowCutIn = 0d;
            }
            sumLow[i] = (float) (ga * aLow[i] * Math.pow(10d, lowCutOut / 20d)
                    + gb * bLow[i] * Math.pow(10d, lowCutIn / 20d));
            // The power check is on the whole mix, not the low band: it is the level the
            // listener gets, and holding both tracks up longer is paid for there.
            double full = ga * aTail[i] + gb * bHead[i];
            double power = full * full;
            if (power > maxPower) maxPower = power;
        }
        double[] powSum = frameEnergy(sumLow, rate);
        double[] levels = new double[slices];
        double bestLevel = 0d;
        for (int s = 0; s < slices; s++) {
            long from = s * sliceMs;
            long to = Math.min(rampMs, from + sliceMs);
            progress[s] = (from + to) / 2d / rampMs;
            levels[s] = mean(powSum, from, to);
            if (levels[s] > bestLevel) bestLevel = levels[s];
        }
        for (int s = 0; s < slices; s++) {
            lowLevelDb[s] = levels[s] > SILENCE_FLOOR
                    ? 10d * Math.log10(levels[s] / Math.max(SILENCE_FLOOR, bestLevel)) : -120d;
        }
        double hole = 0d;
        for (double db : lowLevelDb) {
            if (db < hole) hole = db;
        }
        return new Result(hasGrid, progress, lowLevelDb, kickEnds, established, hole,
                10d * Math.log10(Math.max(1e-9, maxPower)),
                20d * Math.log10(Math.max(1e-9, curve.outGain(0.75d))));
    }

    /** Mean of a per-frame array over a time range: the slice's own average. */
    private static double mean(double[] perFrame, long fromMs, long toMs) {
        if (perFrame.length == 0) return 0d;
        int from = (int) Math.max(0, Math.round(fromMs / 1000d / HOP_SEC));
        int to = (int) Math.min(perFrame.length - 1, Math.round(toMs / 1000d / HOP_SEC));
        if (to < from) return 0d;
        double total = 0;
        for (int i = from; i <= to; i++) total += perFrame[i];
        return total / (to - from + 1);
    }

    /** Per-frame mean-square energy, linear. Everything this class reports is a level, and a
     *  level cannot be read off a compressed envelope: the log compression that makes an
     *  onset envelope legible would report a kick that has been faded 25 dB down as still
     *  comfortably present. */
    private static double[] frameEnergy(float[] mono, int rate) {
        int frame = Math.max(1, (int) Math.round(FRAME_SEC * rate));
        int hop = Math.max(1, (int) Math.round(HOP_SEC * rate));
        int frames = mono.length <= frame ? 0 : (mono.length - frame) / hop + 1;
        double[] out = new double[Math.max(0, frames)];
        for (int f = 0; f < frames; f++) {
            double energy = 0;
            int base = f * hop;
            for (int i = base; i < base + frame; i++) energy += mono[i] * (double) mono[i];
            out[f] = energy / frame;
        }
        return out;
    }

    /** Two cascaded one-pole low-passes at {@link #LOW_CORNER_HZ}: the cheap stand-in for
     *  "the bands the equalizer moves". Two poles put the -3 dB point near the corner and
     *  roll off 12 dB an octave, which is the shape of a band cut rather than a brick wall —
     *  the point is the comparison, not the curve. */
    private static float[] lowpass(float[] x, int rate) {
        float[] out = new float[x.length];
        double rc = 1d / (2d * Math.PI * LOW_CORNER_HZ);
        double dt = 1d / rate;
        double a = dt / (rc + dt);
        double y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            y1 += a * (x[i] - y1);
            y2 += a * (y1 - y2);
            out[i] = (float) y2;
        }
        return out;
    }
}
