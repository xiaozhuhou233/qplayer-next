package dev.t1m3.qplayer.audio;

/**
 * Renders one pre-mixed blend window from two separated windows and a gesture plan.
 *
 * <p>The arithmetic of <em>when</em> each stem changes hands lives in {@link StemGesture};
 * this is the part that applies it: per-stem gain curves, the outgoing pad's high-pass
 * sweep, the shared bus, and the limiter that keeps the sum of two decks inside full scale.
 * Platform-free and deterministic, so a render can be checked in plain Java — which is what
 * the Phase C acceptance run does (see AI_HANDOFF §7).
 *
 * <p>Folia's graph is the reference ({@code crossfadeGraph.ts}): each deck is four stem
 * sources into four gain nodes (the two curves), the outgoing {@code other} gains a biquad
 * sweep on its way, both decks sum at one bus, and the bus is a soft limiter. Nothing here
 * is invented except the order of a block-wise filter recompute.
 */
public final class StemBlendRenderer {

    private StemBlendRenderer() {}

    /**
     * Where the soft limiter stops being a straight line, and how far past full scale it can
     * see.
     *
     * <p>0.95 is -0.45 dBFS: a master that fits inside full scale passes through all but
     * untouched, and the shaping is spent on what does not fit. 4 is +12 dB of visible range,
     * well past anything two records have been measured to reach together; beyond it a
     * WaveShaper clamps to the end of its curve, the hard clip this exists to avoid.
     */
    public static final double LIMIT_KNEE = 0.95;
    public static final double LIMIT_RANGE = 4;

    /**
     * The bus shaper, as {@code crossfadeGraph.ts} writes it into a WaveShaper curve.
     *
     * <p>Memoryless on purpose (its oversampling is 'none'), because oversampling resamples
     * everything passing through, including the straight-line region that is most of every
     * window and has to stay exactly straight.
     */
    public static double softLimit(double x) {
        double level = x < 0 ? -x : x;
        if (level <= LIMIT_KNEE) return x;
        double room = 1 - LIMIT_KNEE;
        double held = LIMIT_KNEE + room * Math.tanh((level - LIMIT_KNEE) / room);
        return x < 0 ? -held : held;
    }

    /**
     * How far a stem overshoots full scale, or 1 — the divisor Folia stores a window under.
     *
     * <p>{@code other} is a difference of four signals and the master itself sits at full scale
     * on a modern pop record, so samples past ±1 are ordinary here rather than exceptional.
     * Dividing by the peak costs one float per stem and uses a sixteen-bit format as meant: full
     * scale means the loudest sample there is, not an arbitrary 1.0.
     *
     * <p>Note what this divisor is and is not: it is applied on the way INTO storage and
     * multiplied back on the way out, so it cannot change what a blend sounds like — the stems
     * still meet the master at unity. What keeps the SUM of two decks inside full scale is
     * {@link #softLimit}, one node later.
     */
    public static double peakOf(float[] left, float[] right) {
        double peak = 1;
        for (int i = 0; i < left.length; i++) {
            double l = Math.abs(left[i]);
            if (l > peak) peak = l;
            double r = Math.abs(right[i]);
            if (r > peak) peak = r;
        }
        return peak;
    }

    /** What a render cost and what it did: the numbers the acceptance run reports. */
    public static final class Stats {
        /** The rendered window, [channel][sample]. */
        public float[][] pcm;
        /** The peak sample, before the limiter, in absolute units (1.0 = full scale). */
        public double peakBeforeBus;
        /** How many samples the sum went past full scale by, before the limiter. */
        public int aboveFullScale;
        /** How many (channel, sample) pairs the limiter touched at all. */
        public int limited;
        /** The largest gain reduction the limiter applied, in dB. */
        public double maxReductionDb;
        /** The same window with only the VOCAL stems rendered: the voice content, exactly. */
        public float[][] voiceOnly;
        /** What the limiter saw, sample by sample, for the splice checks. */
        public double[] busMax;
    }

    /**
     * Renders the blend.
     *
     * @param outgoing stems of the outgoing TAIL, [stemRow][channel][sample], already at unity
     *                 against their own master
     * @param incoming stems of the incoming HEAD, same layout
     * @param plan     the gesture
     * @param sweep    whether the outgoing pad's high-pass sweep is applied (Folia applies it;
     *                 a test that wants the bare all-unity invariant turns it off)
     */
    public static Stats render(float[][][] outgoing, float[][][] incoming, int sampleRate,
                               double windowSec, StemGesture.StemHandover plan, boolean sweep) {
        int channels = outgoing[0].length;
        int length = outgoing[0][0].length;
        float[][] outCurves = StemGesture.outgoingCurves(windowSec, plan);
        float[][] inCurves = StemGesture.incomingCurves(windowSec, plan);

        Stats stats = new Stats();
        stats.pcm = new float[channels][length];
        stats.voiceOnly = new float[channels][length];
        stats.busMax = new double[length];

        // The outgoing pad's high-pass, one biquad per channel, coefficients recomputed per block
        // (Folia schedules the same filter on a BiquadFilterNode; a block-wise recompute is the
        // standard offline equivalent, and 64 samples is well inside a millisecond of its curve).
        Biquad[] sweepFilter = sweep ? new Biquad[channels] : null;
        if (sweep) {
            for (int ch = 0; ch < channels; ch++) sweepFilter[ch] = new Biquad();
        }
        int sweepEnd = (int) Math.round(windowSec * StemGesture.OTHER_SWEEP_END * sampleRate);
        double sweepFrom = StemGesture.OTHER_SWEEP_HZ[0];
        double sweepTo = StemGesture.OTHER_SWEEP_HZ[1];

        double peakBefore = 0;
        int above = 0, limited = 0;
        double maxReduction = 0;

        for (int i = 0; i < length; i++) {
            double t = i / (double) sampleRate;
            if (sweep && i < sweepEnd && i % 64 == 0) {
                double frac = sweepEnd > 0 ? i / (double) sweepEnd : 1;
                double hz = sweepFrom + (sweepTo - sweepFrom) * frac;
                for (Biquad filter : sweepFilter) filter.highPass(hz, sampleRate);
            }
            for (int ch = 0; ch < channels; ch++) {
                double sum = 0;
                double voice = 0;
                for (StemGesture.Stem stem : StemGesture.Stem.ALL) {
                    int row = stem.row();
                    double outGain = StemGesture.curveAt(outCurves[row], t, windowSec);
                    double inGain = StemGesture.curveAt(inCurves[row], t, windowSec);
                    double outSample = outGain * outgoing[row][ch][i];
                    if (sweepFilter != null && stem == StemGesture.Stem.OTHER) {
                        outSample = sweepFilter[ch].step(outSample);
                    }
                    double inSample = inGain * incoming[row][ch][i];
                    sum += outSample + inSample;
                    if (stem == StemGesture.Stem.VOCALS) voice = outSample + inSample;
                }
                stats.voiceOnly[ch][i] = (float) voice;
                double magnitude = Math.abs(sum);
                if (magnitude > peakBefore) peakBefore = magnitude;
                if (magnitude > 1) above++;
                double limited2 = softLimit(sum);
                if (limited2 != sum) {
                    limited++;
                    double reduction = 20 * Math.log10(Math.max(Math.abs(limited2 / sum), 1e-12));
                    if (reduction < maxReduction) maxReduction = reduction;
                }
                stats.pcm[ch][i] = (float) limited2;
                if (ch == 0) stats.busMax[i] = sum;
            }
        }
        stats.peakBeforeBus = peakBefore;
        stats.aboveFullScale = above;
        stats.limited = limited;
        stats.maxReductionDb = maxReduction;
        return stats;
    }

    /**
     * One channel of an RBJ high-pass, with the coefficients recomputed when the cutoff moves.
     *
     * <p>Direct form 1 with the state carried across coefficient changes, which is what a Web
     * Audio BiquadFilterNode does with scheduled automation.
     */
    static final class Biquad {
        private double b0 = 1, b1, b2, a1, a2;
        private double x1, x2, y1, y2;

        /** Recomputes the coefficients for a high-pass at {@code hz} with Q = 1/sqrt(2). */
        void highPass(double hz, int sampleRate) {
            double nyquist = sampleRate / 2.0;
            double f = Math.min(Math.max(hz, 1), nyquist * 0.99);
            double w0 = 2 * Math.PI * f / sampleRate;
            double cos = Math.cos(w0);
            double alpha = Math.sin(w0) / (2 * (1 / Math.sqrt(2)));
            double a0 = 1 + alpha;
            b0 = (1 + cos) / 2 / a0;
            b1 = -(1 + cos) / a0;
            b2 = (1 + cos) / 2 / a0;
            a1 = -2 * cos / a0;
            a2 = (1 - alpha) / a0;
        }

        double step(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }
}
