package dev.t1m3.qplayer.audio;

/**
 * The bridge: a short passage of the OUTGOING track's low end carried forward under the
 * INCOMING track's own head, on the incoming's own bar grid.
 *
 * <p><b>What it is for.</b> A blend of two records is a fade in the middle whatever the gain
 * curve does, because the outgoing track's <em>rhythm</em> leaves with it: the low end is handed
 * over part way through the overlap ({@code PlayerController.BASS_SWAP_AT}), and from that
 * instant its kick is gone while its body fades over the next seconds. Nothing on the outgoing
 * side can fix that — it is one stream, and its low end has already been taken away by the one
 * knob there is. So the fix belongs on the other side of the seam: the outgoing track's bass,
 * cut out of its own deck, carried forward <em>inside the incoming track's file</em>, and played
 * by the deck that is still there. The passage where it is carried is what this class computes,
 * and it is the one place the two songs' material is genuinely combined rather than merely
 * overlapped.
 *
 * <p>That placement is forced by one hazard: <b>the incoming deck's file is heard at the same
 * time as the outgoing deck's own tail</b>, so anything carried forward that the outgoing deck is
 * still playing sums with itself — a coherent doubling, or a comb filter if the two copies
 * landed within a fraction of a millisecond of each other (they never do sample-exactly: the
 * incoming player's start latency is 123-822 ms, so the truthful worst case is a rhythmic flam,
 * which is worse). The bridge therefore starts at the instant the outgoing deck <em>gives its
 * low end up</em> — when a bridge exists the mix's low-end hand-over is moved to the bridge's
 * own start — and it carries exactly the band that hand-over takes away. Nothing is duplicated
 * because the outgoing deck has already stopped playing it.
 *
 * <p><b>What is deliberately not carried, with the arithmetic that excluded it.</b>
 * <ul>
 *   <li><b>The outgoing's drums.</b> The platform's per-player equalizer is a five-band graphic
 *       EQ and the hand-over can only take the band below 200 Hz, so a carried drum stem would
 *       double its snare and hats against the outgoing deck at whatever gain its fade curve still
 *       has there — measured, -2.7 dB of the outgoing's own level at 72% of the blend and
 *       -8.7 dB at 90%, i.e. an audible coherent copy. Excluded.</li>
 *   <li><b>The outgoing's melodic content ({@code other}).</b> The same arithmetic, and it
 *       cannot be gated: the low band is the only band the hand-over frees.</li>
 *   <li><b>The outgoing's vocals.</b> Never, in any passage: the DJ edit's removal window exists
 *       so that the two songs do not sing over each other.</li>
 * </ul>
 *
 * <p>Pure arithmetic on separated stems — no platform, no files — so what a render will do is a
 * unit test and the acceptance numbers ({@link #measure}) are the same arithmetic the render
 * reports.
 */
public final class StemBridge {

    /** How many bars of the incoming's grid the outgoing's low end is carried for. Two bars is
     *  the shortest passage that reads as a passage rather than as an edit: long enough to hear
     *  the outgoing track's groove continuing, short enough that the bridge is still sitting on
     *  the incoming track's own head rather than replacing it. */
    public static final int BARS = 2;

    /** Beats to a bar — the same assumption every other bar-line use in this codebase makes. */
    public static final int BEATS_PER_BAR = DjEdit.BEATS_PER_BAR;

    /** The fraction of the blend the low-end hand-over sits at, copied from
     *  {@code PlayerController.BASS_SWAP_AT}: the earliest the bridge may start. Duplicated
     *  rather than imported because the controller depends on this class and not the other way
     *  round; {@code StemBridgeTest} pins the two numbers together.
     *
     *  <p>0.15 since round 17 (it was 0.6): the hand-over belongs where the outgoing track's
     *  gain curve crosses the incoming track's (t = 0.14 with round 17's shape), which is the
     *  only place the low band's level survives the step. See {@code BASS_SWAP_AT} for the
     *  measurement. The bridge follows it because the bridge <em>is</em> the hand-over when it
     *  exists: the low end is carried forward from exactly the instant the outgoing deck gives
     *  its own up. */
    public static final double SWAP_AT = 0.15d;

    /** How much source material past the bridge's own length is taken, in bars. The outgoing's
     *  grid and the incoming's are usually not the same length, so a carried passage of exactly
     *  {@link #BARS} outgoing bars can be shorter than the window it is laid in; one extra bar is
     *  clipped away by {@link #layer}, and without it a slower outgoing track would end its
     *  carried groove before the bridge did. */
    public static final int SOURCE_SLACK_BARS = 1;

    /** A frame of the low-band attack detector, seconds — the same 25 ms the vocal-presence
     *  measurement uses, so "there is something here" means the same thing throughout. */
    public static final double FRAME_SEC = DjEdit.FRAME_SEC;

    /** The floor a vocal stem has to be under to count as absent, dBFS — the same floor the
     *  vocal-presence measurement uses. */
    public static final double VOCAL_FLOOR_DBFS = DjEdit.SILENT_FRAME_DBFS;

    /** How far below the material it was taken from the carried layer may sit and still count as
     *  a carry rather than a loss, dB. */
    public static final double CARRY_TOLERANCE_DB = 3d;

    /** How well the carried layer has to line up with the material it was taken from, as the
     *  normalised correlation at zero lag. Below this, what was added is not the material it
     *  claims to be — which is the only way "a second copy" can actually arise on this path. */
    public static final double ALIGNMENT_FLOOR = 0.9d;

    /** How far the carried layer's correlation peak may sit from zero lag, samples. A copy that
     *  sums in late would move it. */
    public static final int ALIGNMENT_LAG_LIMIT = 2;

    /** How much of the outgoing's own vocal stem the carried layer may be, at zero lag: the layer
     *  is only ever that track's bass stem, so anything above this means something else got in. */
    public static final double VOICE_CARRY_LIMIT = 0.5d;

    /** How far above the louder of the two contributions the sum may measure in any block, dB,
     *  before it counts as a coherent doubling. Two comparable copies of the same material sum to
     *  about +6 dB; one copy over the other's material stays near the louder one. */
    public static final double DOUBLING_LIMIT_DB = 3.5d;

    /** How far below the carried layer's own median a beat may sit and still count as having the
     *  low end <em>in</em> it, dB — the clause is about a hole in the low end, not about a kick on
     *  every beat.
     *
     *  <p>⚠️ It was an <em>attack</em> test until round 4 ("a frame more than {@code
     *  DOUBLING_LIMIT_DB}-ish above the window's median"), and a real device run showed what that
     *  measures: on the pair {@code 34364062 -> 1410815174} the carried layer is the outgoing's own
     *  last bars of bass, −14.1 dBFS, one copy with its source (alignment 1.0, no doubling), and
     *  its per-beat peaks are only <b>+0.5 / +1.8 / +2.0 / +3.3 / +0.5 / +0.5 / +0.7 / +1.0 dB</b>
     *  above their own beat medians — a legato bass line, which is what a bass line usually is. A
     *  test that needs +6 dB counted <b>0 of 7 beats</b> and refused a bridge that is exactly what
     *  the bridge is for (the outgoing track's own low end continuing under the incoming's head).
     *  The question this clause actually asks is whether the low end is <em>there</em>, so it is
     *  asked as presence: a beat whose own level is more than this far under the layer's median is
     *  a hole, and a hole is what the clause refuses.
     *
     *  <p>20 dB, and not a number tuned to that one line: the same material's own beats vary by
     *  ~10 dB between them (a bass line changing register), so any threshold in that range would be
     *  a fit to one track, while the thing the clause must catch — the outgoing's last bars having
     *  no bass in them at all, so the two bars under the incoming's head are near-silence — is a
     *  dropout of tens of dB, and every beat of it reads at the floor.
     *
     *  <p>The measurement is each beat's own <em>peak</em> against the <em>source's</em> typical
     *  beat peak (see {@link Report#medianLevel}): the peak because a bass line that is a burst per
     *  beat has a silent median inside every beat, and the source because a hole large enough to
     *  matter would otherwise pull the layer's own reference into itself and the test would read
     *  the bridge as present throughout. */
    public static final double PULSE_HOLE_DB = 20d;

    private StemBridge() {}

    /** Where the bridge goes in the incoming track's own file, and whether it fits there. */
    public static final class Plan {
        /** First sample of the bridge, seconds into the incoming's file. */
        public final double startSec;
        /** One past its last sample. */
        public final double endSec;
        /** One bar of the incoming's grid, seconds. */
        public final double barSec;
        /** True when {@link #startSec} is a real bar line rather than the plain placement. */
        public final boolean onBarLine;
        /** True when the bridge fits inside the vocal-removal window <em>and</em> inside the head
         *  that was actually separated. */
        public final boolean fits;
        /** Why not, when it does not — for the log. */
        public final String reason;

        Plan(double startSec, double endSec, double barSec, boolean onBarLine, boolean fits,
             String reason) {
            this.startSec = startSec;
            this.endSec = endSec;
            this.barSec = barSec;
            this.onBarLine = onBarLine;
            this.fits = fits;
            this.reason = reason;
        }

        public double lengthSec() {
            return Math.max(0d, endSec - startSec);
        }

        /** The instant the low end changes hands, seconds into the incoming's file. The swap IS
         *  the bridge's start: see the class note. */
        public double swapSec() {
            return startSec;
        }

        /** What the render did, in one line. */
        public String describe() {
            if (!fits) return "no bridge (" + reason + ")";
            return String.format(java.util.Locale.US,
                    "bridge at %.3f-%.3fs of the incoming file (%d bars of %.3fs, %s; the low end"
                            + " changes hands at its own start, %.3fs)",
                    startSec, endSec, BARS, barSec,
                    onBarLine ? "a line of the incoming track's bar grid" : "the plain placement",
                    swapSec());
        }
    }

    /**
     * Place the bridge in the incoming track's head.
     *
     * <p>The earliest bar line of the incoming's grid at or after {@link #SWAP_AT} of the removal
     * window — a line from the <em>downbeat</em> offset, never from the beat grid's phase, which
     * is a different question and wrong three times in four (see {@link StemGesture#barLines}).
     * Earliest, because that is what makes the carried low end arrive exactly when the outgoing
     * deck's own low end leaves: the controller moves the hand-over to this very instant when a
     * bridge exists, so this is not an approximation of the swap — it is the swap.
     *
     * <p>{@code removalSec} is what the DJ edit removes the incoming's vocals for, and the bridge
     * has to fit inside it: a bridge that ran past the removal window would have the incoming's
     * voice inside it, which is the one thing no passage of this edit may contain.
     *
     * @param incomingBarLines bar lines of the incoming's head, seconds, from
     *                         {@link StemGesture#barLines}
     * @param barSec           one bar of the incoming's grid, seconds
     * @param removalSec       the incoming's vocal-removal window, seconds
     * @param windowSec        the separated head's length, seconds
     */
    public static Plan plan(double[] incomingBarLines, double barSec, double removalSec,
                            double windowSec) {
        double floor = removalSec * SWAP_AT;
        double start = Double.NaN;
        if (incomingBarLines != null) {
            for (double at : incomingBarLines) {
                if (at >= floor && (Double.isNaN(start) || at < start)) start = at;
            }
        }
        boolean onBarLine = !Double.isNaN(start);
        if (!onBarLine) {
            // No grid: the placement falls back to the plain fraction of the removal window.
            // Still a bridge — the low end still has somewhere to be carried — it just is not on a
            // line nobody measured.
            start = floor;
        }
        if (!(barSec > 0d)) {
            return new Plan(start, start, 0d, onBarLine, false, "no bar length was measured");
        }
        double end = start + BARS * barSec;
        if (!(start > 0d)) {
            return new Plan(start, end, barSec, onBarLine, false,
                    "the placement lands at the very start of the file");
        }
        if (end > removalSec) {
            return new Plan(start, end, barSec, onBarLine, false, String.format(
                    java.util.Locale.US, "it would run past the %.3fs the incoming's vocals are out"
                            + " for (ending at %.3fs instead)", removalSec, end));
        }
        if (end > windowSec) {
            return new Plan(start, end, barSec, onBarLine, false, String.format(
                    java.util.Locale.US, "it would run past the %.3fs of head that was separated"
                            + " (ending at %.3fs instead)", windowSec, end));
        }
        return new Plan(start, end, barSec, onBarLine, true, "");
    }

    /**
     * The material the bridge adds: the outgoing track's {@code bass} stem, taken from its own
     * last whole bars, stretched so that it is <em>heard</em> at the outgoing track's own tempo,
     * and clipped to the bridge's window.
     *
     * <p><b>Why the outgoing's last bars.</b> The carried passage has to be the groove the
     * listener is hearing the outgoing track leave on, so it is taken from the end of that track
     * — its own last whole bars, anchored on a line of its own bar grid (found with the same
     * downbeat estimate the vocal return uses, from the separated bass).
     *
     * <p><b>Why it is stretched.</b> The incoming deck plays this file at {@code speed}
     * ({@code periodB/periodA} — the tempo lock of {@link MixNaturaliser}), so everything inside
     * it is heard that much faster. Carrying the outgoing's bass at unity would carry it at the
     * wrong tempo and the "continuation" would be a flam. Stretching it by {@code speed} inside
     * the file makes it heard at the outgoing track's own tempo <em>and its own pitch</em>: the
     * plain resample drops the pitch by {@code speed} (which is what {@link
     * Report#pitchOffsetSemitones} records about the material <em>as written</em>) and the deck's
     * own playback rate puts it back, exactly, because the two ratios are the same number.
     *
     * <p>⚠️ <b>The stretch lengthens; it does not shorten</b> ({@link #stretch}'s ratio is the
     * factor the signal is lengthened by, and the two must agree or the carry is heard at
     * {@code speed²}). Reading the material <em>faster</em> on the way in — which this method did
     * until round 18, against its own javadoc — would be the flam this stretch exists to remove,
     * doubled: at the ±8% the tempo lock allows, a carried bar would land up to 16.6% early and
     * up to 1.3 semitones sharp. No device has ever played a bridge (see AI_HANDOFF §零: the one
     * bridge that was ever rendered was never played), so nothing about the legacy path is
     * preserved by keeping the old sense — the change is named here rather than made silently.
     *
     * <p>When no tempo was applied ({@code speed == 1.0} — a pair whose grids already hold, the
     * ordinary case) the carried bass is sample-exact either way.
     *
     * @param outgoingBass  the outgoing tail's bass stem, {@code [channel][sample]}
     * @param rate          its sample rate
     * @param plan          where the bridge goes
     * @param speed         the ratio the incoming deck plays at
     * @param sourceFromSec where in {@code outgoingBass} the outgoing's last whole bars start
     * @return {@code [channel][sample]} material to ADD to the incoming's head at
     *         {@code plan.startSec}, or null when there is nothing to add
     */
    public static float[][] layer(float[][] outgoingBass, int rate, Plan plan, double speed,
                                  double sourceFromSec) {
        if (plan == null || !plan.fits || outgoingBass == null || outgoingBass.length == 0
                || outgoingBass[0] == null) {
            return null;
        }
        int from = Math.max(0, (int) Math.round(sourceFromSec * rate));
        int sourceFrames = Math.max(0, outgoingBass[0].length - from);
        if (sourceFrames <= 0) return null;
        float[][] stretched = stretch(outgoingBass, from, sourceFrames, speed);
        int windowFrames = (int) Math.round(plan.lengthSec() * rate);
        if (windowFrames <= 0) return null;
        int channels = outgoingBass.length;
        float[][] out = new float[channels][windowFrames];
        int copy = Math.min(stretched[0].length, windowFrames);
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(stretched[ch], 0, out[ch], 0, copy);
        }
        return out;
    }

    /**
     * A slice of one signal, resampled by {@code ratio} — the linear interpolator
     * {@code AndroidStemEditRenderer} uses for its rate conversion, with a fractional ratio.
     *
     * <p>A plain resample: it changes the tempo and the pitch together, which is what is wanted
     * here (the carried bass has to be heard at the outgoing track's tempo, and the pitch error
     * that buys <em>inside the file</em> is reported rather than hidden). {@code ratio} is the
     * factor the signal is <em>lengthened</em> by: 1.0752 stretches it by 7.5% and drops it by
     * 1.3 semitones — and the deck, which plays this file at that same 1.0752, hands the material
     * to the listener at its own tempo and its own pitch again.
     *
     * <p>Reads {@code frames} samples of the source from the first one at or after {@code from} and
     * returns {@code frames * ratio} of them (the interpolator runs out of source at the end of the
     * array, so a take that reaches the source's own end is simply shorter). The sense is
     * {@code StemFusion.carried}'s, which is the same bargain the fusion's carry makes; the two
     * are pinned together by {@code StemBridgeTest.aStretchedCarryLengthensByTheSpeed...} and
     * {@code StemFusionTest}.
     */
    static float[][] stretch(float[][] pcm, int from, int frames, double ratio) {
        int channels = pcm.length;
        double r = ratio > 0d ? ratio : 1d;
        int target = Math.max(1, (int) Math.floor(frames * r));
        float[][] out = new float[channels][target];
        for (int ch = 0; ch < channels; ch++) {
            float[] src = pcm[ch];
            for (int i = 0; i < target; i++) {
                double at = from + i / r;
                int lo = (int) at;
                if (lo >= src.length) continue;
                int hi = Math.min(lo + 1, src.length - 1);
                double frac = at - lo;
                out[ch][i] = (float) (src[lo] * (1 - frac) + src[hi] * frac);
            }
        }
        return out;
    }

    /**
     * Where in the outgoing's separated tail its carried passage is taken from: far enough back
     * from its last bar line that the take is {@link #BARS} + {@link #SOURCE_SLACK_BARS} whole
     * bars ending on a line of its own grid.
     */
    public static double sourceFromSec(double[] outgoingBarLines, double barSec) {
        if (outgoingBarLines == null || outgoingBarLines.length == 0 || !(barSec > 0d)) return 0d;
        double last = outgoingBarLines[0];
        for (double at : outgoingBarLines) {
            if (at > last) last = at;
        }
        return Math.max(0d, last - (BARS + SOURCE_SLACK_BARS - 1) * barSec);
    }

    // --- what the render did, measured ---------------------------------------

    /**
     * The acceptance numbers for one rendered bridge, measured on the material that went into the
     * file.
     *
     * <p>This is the answer to the acceptance clause that has to be measured rather than
     * asserted, and it is measurable here for a reason the previous round recorded as a negative:
     * on mixed program audio "is there a beat in this window" has no honest proxy
     * ({@link BlendPulse}'s own note lists three attempts that failed), but this is not program
     * audio — it is the stems that were placed, whose grid and level are known, so the question
     * becomes arithmetic.
     */
    public static final class Report {
        /** dBFS of the carried layer inside the bridge — the outgoing's contribution. */
        public double carriedDb;
        /** dBFS of the outgoing's bass in the material it was taken from: the level the carry
         *  preserves. A carry quieter than its source is a carry that lost the groove. */
        public double sourceDb;
        /** dBFS of the incoming's own head inside the bridge — the incoming's contribution. */
        public double incomingDb;
        /** dBFS of the incoming's vocal stem under the edit's own gain, inside the bridge. Must be
         *  at the floor: the bridge lives inside the removal window. */
        public double incomingVocalDb;
        /** How much of the OUTGOING's vocal stem the carried layer is, at zero lag. Never
         *  carried, so this must be near zero — a level check would say nothing, because the
         *  outgoing track's voice is all over the bars the material is taken from. */
        public double outgoingVocalAlignment;
        /** The carried layer's normalised correlation with the material it was taken from, at zero
         *  lag: 1.0 means "this is that material, in phase". */
        public double alignment;
        /** Where that correlation peaks, samples — 0 when the layer is in phase with its source. */
        public int alignmentLagSamples;
        /** The largest amount by which the sum measured above the louder of its two contributions
         *  in any 100 ms block of the bridge, dB: the doubling measurement. */
        public double doublingDb;
        /** The pitch the carried layer sits at INSIDE the file, semitones (0 when no tempo was
         *  applied). It is the plain resample's own artefact and it is exactly what the deck's
         *  playback rate puts back: the file is played at the ratio the material was stretched by,
         *  so what the listener hears is the outgoing track's own pitch. Reported rather than
         *  hidden, which is all the number has ever been for. */
        public double pitchOffsetSemitones;
        /** Beats of the slower of the two grids the carried low end is <em>in</em> (its own level
         *  within {@link #PULSE_HOLE_DB} of the layer's median), over all of them. */
        public int beatsWithLowEnd;
        public int beats;
        /** The longest stretch of the bridge the carried low end has left for, ms — the carry's
         *  continuity, on material whose period is known. That, and not a kick on every beat, is
         *  what this clause refuses: see {@link #PULSE_HOLE_DB}. */
        public double longestGapMs;
        /** The period the gap was judged against: the slower of the two grids. */
        public double judgedPeriodMs;
        /** The level the carried material's typical beat reaches, dBFS: the median of the SOURCE's
         *  own per-beat peaks, floored at {@link #VOCAL_FLOOR_DBFS} — what the carry claims to be,
         *  and the reference the per-beat presence test is measured against. */
        public double medianLevel;
        /** True when every clause of the acceptance holds. */
        public boolean acceptable;
        /** Which clause did not, when one did not. */
        public String failures = "";

        /** The evidence, in one line. */
        public String describe() {
            return String.format(java.util.Locale.US,
                    "bridge measured: carried %.1f dBFS, its source %.1f dBFS (the incoming's own"
                            + " head %.1f dBFS under it); vocals: incoming %.1f, outgoing %.1f"
                            + " dBFS; one copy (carries %.3f of its source, peaking at lag %d);"
                            + " no doubling (the sum is %+.1f dB above the louder contribution at"
                            + " worst); pitch artefact %+.2f semitones; the low end: %d of %d beats"
                            + " have it, longest gap %.0fms of a %.0fms period",
                    carriedDb, sourceDb, incomingDb, incomingVocalDb, outgoingVocalAlignment,
                    alignment, alignmentLagSamples, doublingDb, pitchOffsetSemitones,
                    beatsWithLowEnd, beats, longestGapMs, judgedPeriodMs)
                    + (acceptable ? "" : " -- NOT ACCEPTABLE: " + failures);
        }
    }

    /** Frame levels of a signal, dBFS, over a window. */
    static double[] frameLevelsDb(float[][] pcm, int rate, double windowSec) {
        if (pcm == null || pcm.length == 0 || pcm[0] == null) return new double[0];
        int frameLen = Math.max(1, (int) Math.round(rate * FRAME_SEC));
        int total = (int) Math.round(rate * Math.max(0d, windowSec));
        for (float[] ch : pcm) total = Math.min(total, ch.length);
        if (total <= 0) return new double[0];
        int count = total / frameLen;
        double[] out = new double[count];
        for (int f = 0; f < count; f++) {
            double energy = 0;
            for (float[] ch : pcm) {
                for (int i = f * frameLen; i < (f + 1) * frameLen; i++) {
                    energy += ch[i] * ch[i];
                }
            }
            out[f] = DjEdit.db(Math.sqrt(energy / (frameLen * (double) pcm.length)));
        }
        return out;
    }

    /** RMS of a signal over the first {@code windowSec}, dBFS. */
    static double levelDb(float[][] pcm, int rate, double windowSec) {
        if (pcm == null || pcm.length == 0 || pcm[0] == null) return -240d;
        int limit = (int) Math.round(rate * Math.max(0d, windowSec));
        double energy = 0;
        int samples = 0;
        for (float[] ch : pcm) {
            int end = Math.min(limit, ch.length);
            for (int i = 0; i < end; i++) {
                energy += ch[i] * ch[i];
                samples++;
            }
        }
        return samples == 0 ? -240d : DjEdit.db(Math.sqrt(energy / samples));
    }

    /** The loudest frame of one beat, dBFS — what a beat has of the low end at all, whether the
     *  line is a burst per beat or a legato note. {@code -240} when the range is empty. */
    static double peak(double[] levels, int from, int to) {
        if (levels == null || from >= to || from < 0) return -240d;
        double peak = -240d;
        for (int i = from; i < Math.min(to, levels.length); i++) peak = Math.max(peak, levels[i]);
        return peak;
    }

    /** Every beat's own peak, dBFS — the shape of a material's low end, one entry per beat. */
    static double[] beatPeaks(double[] levels, int perBeat) {
        if (levels == null || levels.length == 0 || perBeat < 1) return new double[0];
        int beats = Math.max(1, levels.length / perBeat);
        double[] out = new double[beats];
        for (int b = 0; b < beats; b++) {
            out[b] = peak(levels, b * perBeat, Math.min(levels.length, (b + 1) * perBeat));
        }
        return out;
    }

    /** The median of a frame-level array, dBFS. */
    static double median(double[] levels) {
        if (levels == null || levels.length == 0) return -240d;
        double[] sorted = levels.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    /**
     * Measure one rendered bridge.
     *
     * @param carried        the carried layer as placed in the incoming's file (what
     *                       {@link #layer} returned)
     * @param carriedSource  the material it was taken from, unstretched, same length — the
     *                       reference the comb test and the level check are against
     * @param incomingHead   the incoming's own head as it will be written, over the bridge window
     * @param incomingVocals the incoming's vocal stem under the edit's gain, over the window
     * @param outgoingVocals the outgoing tail's vocal stem, over the window
     * @param speed          the ratio the incoming deck plays at (for the pitch artefact)
     * @param plan           the placement
     * @param beatSecIn      the incoming's beat period, seconds (0 when unknown)
     * @param beatSecOut     the outgoing's beat period, seconds (0 when unknown)
     */
    public static Report measure(float[][] carried, float[][] carriedSource,
                                 float[][] incomingHead, float[][] incomingVocals,
                                 float[][] outgoingVocals, int rate, Plan plan, double speed,
                                 double beatSecIn, double beatSecOut) {
        Report r = new Report();
        double windowSec = plan != null && plan.fits ? plan.lengthSec() : 0d;
        r.carriedDb = levelDb(carried, rate, windowSec);
        r.sourceDb = levelDb(carriedSource, rate, windowSec);
        r.incomingDb = levelDb(incomingHead, rate, windowSec);
        r.incomingVocalDb = levelDb(incomingVocals, rate, windowSec);
        int[] voiceLag = new int[1];
        r.outgoingVocalAlignment = Math.abs(
                alignmentAtZeroLag(carried, outgoingVocals, rate, windowSec, voiceLag));
        r.pitchOffsetSemitones = speed > 0d && Math.abs(speed - 1d) > 1e-9d
                ? 12d * (Math.log(speed) / Math.log(2d)) : 0d;
        int[] lag = new int[1];
        r.alignment = alignmentAtZeroLag(carried, carriedSource, rate, windowSec, lag);
        r.alignmentLagSamples = lag[0];
        // The carried low end's own continuity, per beat — the clause's subject is the LAYER (is
        // the low end still there), so that is what it measures, in its own frames and against its
        // own level: see PULSE_HOLE_DB for the pair that showed an attack test cannot be that
        // question (a legato bass line has no attack on any beat, and is not a hole).
        double period = Math.max(beatSecIn > 0d ? beatSecIn : 0d, beatSecOut > 0d ? beatSecOut : 0d);
        r.judgedPeriodMs = period * 1000d;
        if (period > 0d) {
            double[] levels = frameLevelsDb(carried, rate, windowSec);
            int perBeat = Math.max(1, (int) Math.round(period / FRAME_SEC));
            r.beats = Math.max(1, levels.length / perBeat);
            // The reference is the MATERIAL's own typical beat — the median of the SOURCE's per-beat
            // peaks — and not anything the layer reports about itself: a hole big enough to matter
            // drags the layer's own median into the hole, and a test against that reads the whole
            // bridge as present (measured: silencing the middle half of a four-second layer does
            // exactly that). The floor keeps a source that is itself near-silence — the ending the
            // bridge must not be built on — from making the test a tautology.
            r.medianLevel = Math.max(
                    median(beatPeaks(frameLevelsDb(carriedSource, rate, windowSec), perBeat)),
                    VOCAL_FLOOR_DBFS);
            int present = 0;
            double longest = 0d;
            double run = 0d;
            for (int b = 0; b < r.beats; b++) {
                // A beat's own peak, not its median: a low line that is a burst per beat (the
                // ordinary bass line) has a silent median inside every beat, and a legato line has a
                // flat one — the peak is what both of them have in common, and what a beat the low
                // end has left has none of.
                double beatPeak = peak(levels, b * perBeat,
                        Math.min(levels.length, (b + 1) * perBeat));
                boolean here = beatPeak > r.medianLevel - PULSE_HOLE_DB;
                if (here) {
                    present++;
                    run = 0d;
                } else {
                    run += period;
                    longest = Math.max(longest, run);
                }
            }
            r.beatsWithLowEnd = present;
            r.longestGapMs = longest * 1000d;
        }
        StringBuilder bad = new StringBuilder();
        if (!(r.carriedDb > r.sourceDb - CARRY_TOLERANCE_DB)) {
            bad.append("the carried layer is more than ").append((int) CARRY_TOLERANCE_DB)
                    .append(" dB below the material it was taken from (")
                    .append(fmt(r.carriedDb)).append(" vs ").append(fmt(r.sourceDb))
                    .append(" dBFS); ");
        }
        if (r.carriedDb < -60d) bad.append("the carried layer is silent; ");
        if (r.incomingDb < -60d) bad.append("the incoming's own head is silent under it; ");
        if (r.incomingVocalDb > VOCAL_FLOOR_DBFS) {
            bad.append("the incoming's vocals are in the bridge (")
                    .append(fmt(r.incomingVocalDb)).append(" dBFS); ");
        }
        if (r.outgoingVocalAlignment > VOICE_CARRY_LIMIT) {
            bad.append("the carried material is the outgoing's voice (it aligns ")
                    .append(fmt(r.outgoingVocalAlignment)).append(" with its vocal stem); ");
        }
        if (r.alignment < ALIGNMENT_FLOOR) {
            bad.append("the carried layer is not the material it was taken from (carries only ")
                    .append(fmt(r.alignment)).append(" of it); ");
        }
        if (Math.abs(r.alignmentLagSamples) > ALIGNMENT_LAG_LIMIT) {
            bad.append("the carried layer is not in phase with its source (its correlation peaks ")
                    .append(r.alignmentLagSamples).append(" samples away); ");
        }
        r.doublingDb = worstDoublingDb(carried, incomingHead, rate, windowSec);
        if (r.doublingDb > DOUBLING_LIMIT_DB) {
            bad.append("the two contributions double where they overlap (the sum is ")
                    .append(fmt(r.doublingDb)).append(" dB above the louder of them); ");
        }
        if (period > 0d && r.longestGapMs > 1.6d * r.judgedPeriodMs) {
            bad.append("the carried low end has a ").append(fmt(r.longestGapMs)).append("ms hole"
                    + " with a ").append(fmt(r.judgedPeriodMs)).append("ms period (")
                    .append(r.beatsWithLowEnd).append(" of ").append(r.beats)
                    .append(" beats have it); ");
        }
        r.acceptable = bad.length() == 0;
        r.failures = bad.toString();
        return r;
    }

    /** Two decimals for the log. */
    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    /** Two signals summed; the shorter one is silence past its end. */
    static float[][] add(float[][] a, float[][] b) {
        if (a == null) return b;
        if (b == null) return a;
        int channels = Math.max(a.length, b.length);
        int length = 0;
        for (int ch = 0; ch < channels; ch++) {
            length = Math.max(length, Math.max(len(a, ch), len(b, ch)));
        }
        float[][] out = new float[channels][length];
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < length; i++) {
                out[ch][i] = at(a, ch, i) + at(b, ch, i);
            }
        }
        return out;
    }

    private static int len(float[][] pcm, int ch) {
        return pcm != null && ch < pcm.length && pcm[ch] != null ? pcm[ch].length : 0;
    }

    private static float at(float[][] pcm, int ch, int i) {
        return pcm != null && ch < pcm.length && pcm[ch] != null && i < pcm[ch].length
                ? pcm[ch][i] : 0f;
    }

    /**
     * How much of the material the carried layer "is", at zero lag, and where its correlation
     * with that material actually peaks.
     *
     * <p>The honest form of the comb test for this design. A second copy of the same material
     * summed in late would move the correlation's peak off zero lag, and a copy that was summed
     * in at unity would leave the peak at zero lower than one; a single copy in phase measures 1.0
     * at lag 0 and peaks there. What this cannot do is prove that no *periodic* second copy
     * exists, and that ambiguity is irreducible: a copy delayed by exactly the material's own beat
     * is the material's own periodicity, and no signal-domain test separates them. The design does
     * not rely on the measurement for that — the outgoing deck's low band is cut at the bridge's
     * own start, so there is only ever one copy in the room (see the class note).
     *
     * @param peakLag single-element out: the lag the correlation peaks at, samples
     */
    static double alignmentAtZeroLag(float[][] carried, float[][] source, int rate,
                                     double windowSec, int[] peakLag) {
        if (peakLag != null && peakLag.length > 0) peakLag[0] = 0;
        if (carried == null || source == null || carried.length == 0) return 0d;
        int length = (int) Math.round(rate * Math.max(0d, windowSec));
        length = Math.min(length, Math.min(len(carried, 0), len(source, 0)));
        int maxLag = Math.min(length / 4, (int) Math.round(0.05d * rate));
        if (length <= 0 || maxLag <= 1) return 0d;
        double peak = 0d;
        double atZero = 0d;
        int bestLag = 0;
        for (int lag = -maxLag; lag <= maxLag; lag++) {
            double dot = 0d;
            double energyA = 0d;
            double energyB = 0d;
            for (int i = 0; i < length; i++) {
                int j = i + lag;
                if (j < 0 || j >= length) continue;
                double a = carried[0][i];
                double b = source[0][j];
                dot += a * b;
                energyA += a * a;
                energyB += b * b;
            }
            if (energyA <= 1e-12d || energyB <= 1e-12d) continue;
            double normalised = dot / Math.sqrt(energyA * energyB);
            if (lag == 0) atZero = normalised;
            if (Math.abs(normalised) > Math.abs(peak)) {
                peak = normalised;
                bestLag = lag;
            }
        }
        if (peakLag != null && peakLag.length > 0) peakLag[0] = bestLag;
        return atZero;
    }

    /**
     * The doubling measurement: over every 100 ms block of the bridge, how far the SUM measures
     * above the louder of its two contributions. Two comparable copies of the same material sum to
     * about +6 dB; material laid over different material stays near the louder one.
     *
     * <p>This is the clause the placement exists for, and it is measurable against the material the
     * render has. The outgoing deck's own copy of this material is not in the file being measured
     * (it is on the other deck, at a gain and in a band this render cannot know) — what is measured
     * is what the FILE does, i.e. whether the bridge laid its material on top of material the
     * incoming deck is already playing in the same band.
     */
    static double worstDoublingDb(float[][] carried, float[][] other, int rate, double windowSec) {
        if (carried == null || other == null) return 0d;
        int block = Math.max(1, (int) Math.round(0.1d * rate));
        int length = (int) Math.round(rate * Math.max(0d, windowSec));
        length = Math.min(length, Math.min(len(carried, 0), len(other, 0)));
        float[][] sum = add(carried, other);
        double worst = 0d;
        for (int from = 0; from + block <= length; from += block) {
            double a = windowLevelDb(carried, from, block);
            double b = windowLevelDb(other, from, block);
            double louder = Math.max(a, b);
            if (louder <= -60d) continue;
            worst = Math.max(worst, windowLevelDb(sum, from, block) - louder);
        }
        return worst;
    }

    /** RMS of one window of a signal, dBFS. */
    private static double windowLevelDb(float[][] pcm, int from, int frames) {
        double energy = 0;
        int samples = 0;
        for (float[] ch : pcm) {
            int end = Math.min(from + frames, ch == null ? 0 : ch.length);
            for (int i = from; i < end; i++) {
                energy += ch[i] * ch[i];
                samples++;
            }
        }
        return samples == 0 ? -240d : DjEdit.db(Math.sqrt(energy / samples));
    }
}
