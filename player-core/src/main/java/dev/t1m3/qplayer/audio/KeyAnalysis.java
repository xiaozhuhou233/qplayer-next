package dev.t1m3.qplayer.audio;

/**
 * The key estimator, as plain arithmetic on mono samples: no platform types, no
 * dependencies, nothing that needs a device. The same split as
 * {@link BeatAnalysis}, for the same reason — decoding is platform code, but the
 * part that can be <em>wrong</em> is this, so it is kept runnable on a desktop
 * JVM against signals whose answer is known (a C major triad has to come back as
 * C major, or as nothing at all) instead of being judged by whether its output
 * looks plausible on a phone.
 *
 * <p>The method, in the order the code runs it:
 * <ol>
 *   <li><b>A spectrum per frame.</b> Short frames (about 370 ms, half-overlapping)
 *       are windowed and transformed by a plain radix-2 FFT. An FFT rather than a
 *       bank of tuned filters because the pitch classes are what is wanted, not
 *       particular frequencies: every bin is folded onto its nearest semitone
 *       anyway, so the whole spectrum is useful at once and one transform per
 *       frame is cheaper than the hundred-odd resonators a filter bank would
 *       need.</li>
 *   <li><b>Folding to twelve bins.</b> Every spectral bin between
 *       {@link #MIN_HZ} and {@link #MAX_HZ} is log-compressed and added to the
 *       pitch class of the semitone it is nearest to — an octave-independent
 *       profile, which is what a key is. The log compression is what keeps a loud
 *       bass note from being the whole profile; the upper limit is where a
 *       recording's spectrum stops describing its harmony and starts describing
 *       its cymbals.</li>
 *   <li><b>The key, by template correlation.</b> The profile is correlated with
 *       the twenty-four rotations of two well-known probe-tone profiles (one
 *       major, one minor — {@link #MAJOR_TEMPLATE}) and the best rotation wins.
 *       Those profiles are what listeners actually rate notes as, so this is the
 *       classical key-finding method and not a home-made rule.</li>
 *   <li><b>Confidence, from the margin over the keys it could not be mixed
 *       with.</b> A recording with a real tonal centre correlates clearly better
 *       with one key than with a key a DJ would never put next to it; one without
 *       (noise, speech, a drum loop) has every key within noise of every other. The
 *       strength is that margin, scaled so that the estimator's own measured
 *       separation lands in 0..1 — see {@code KeyAnalysisTest} for the numbers. The
 *       runner-up that is <em>not</em> counted is the relative major/minor and the
 *       two fifths: those are the same harmonic neighbourhood and
 *       {@link KeyProfile#camelotCompatible} mixes them anyway, so a profile that
 *       cannot tell A minor from C major is not a profile that cannot tell one key
 *       from another.</li>
 * </ol>
 *
 * <p>The key <em>name</em> is deliberately only half of the answer: real music is
 * often ambiguous about it (a loop of Am-F-C-G is C major and A minor at once),
 * so the caller is handed the whole twelve-bin profile as well and decides what
 * to shift from the profile's distance rather than from the name (see
 * {@link KeyProfile#distance}). Nothing here measures modulation, mode mixture or
 * a section structure: it is one profile and one name for a whole track, and the
 * caller bounds what a wrong one can do.
 */
public final class KeyAnalysis {

    /** The lowest and highest frequencies the profile is folded from, Hz. The
     *  bottom is A1 — below it a phone's own bass and the file's rumble say more
     *  about the recording than about the key — and the top is 2 kHz, above which
     *  the harmonics of high notes outweigh the notes themselves and cymbals
     *  start contributing every pitch class equally. */
    public static final double MIN_HZ = 55.0;
    public static final double MAX_HZ = 2000.0;

    /** Frame length and hop, samples. 4096 at the profiler's ~11 kHz is 372 ms —
     *  long enough that adjacent semitones in the bass (2.4 Hz apart at 55 Hz)
     *  are inside one main lobe rather than smeared across several, short enough
     *  that a track with a chord per bar still gets several frames per chord. */
    private static final int FRAME = 4096;
    private static final int HOP = 2048;

    /** How much audio is needed before a profile means anything: below this the
     *  transform has too few frames to average over and a single chord decides
     *  the answer. */
    public static final double MIN_WINDOW_MS = 3_000.0;

    /** The correlation margin that counts as full confidence, so that
     *  {@link KeyProfile#strength()} lands in 0..1. Unchanged from the previous
     *  calibration, which is what keeps {@link KeyProfile#MIN_STRENGTH} — the number
     *  every log line and every comment quotes — meaning the same thing: on this
     *  measure a strength of 0.25 is a margin of 0.0875 over the best key the winner
     *  could not be mixed with, and everything above 0.35 (the median real track) is
     *  reported as 1.0.
     *
     *  <p>The measure behind the constant was recalibrated against real music rather
     *  than synthesised triads, because the synth-only calibration is what broke the
     *  previous version: on 36 cached windows of the app's own library the margin over
     *  the best <em>unrelated</em> key runs 0.012-1.54 with a median of 0.44, and 24 of
     *  the 36 clear 0.25 — where the previous measure passed 8 of the same 36 and
     *  named 16 of them A minor. Material with no key stays below: white noise 0.09, a
     *  bare sine 0.00, a pitchless drum loop 0.19. The one class the margin alone does
     *  not refuse is noise with a fixed spectral envelope (0.47), which is what
     *  {@link KeyProfile#MIN_TRIAD} is for. */
    private static final double MARGIN_FOR_FULL_CONFIDENCE = 0.35d;

    /** Krumhansl-Kessler probe-tone profiles, rotated to C. What listeners rate
     *  the notes of a key as, normalised so the tonic is the highest. These are
     *  the published values; the estimator is a correlation against them, so
     *  every threshold above is about the margin between two of these, not about
     *  their absolute scale. */
    private static final double[] MAJOR_TEMPLATE =
            {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] MINOR_TEMPLATE =
            {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    private KeyAnalysis() {}

    /**
     * Estimate the key of one track.
     *
     * @param mono       the (downmixed, downsampled) samples the beat grid was
     *                   estimated from — the same window of the same track, so one
     *                   decode answers both questions
     * @param sampleRate the rate they were sampled at
     * @return the key, or null when there was nothing to measure (too little
     *         audio, digital silence, no tonal content)
     */
    public static KeyProfile analyse(double[] mono, double sampleRate) {
        if (mono == null || sampleRate <= 0d) return null;
        int frames = (mono.length - FRAME) / HOP + 1;
        if (frames < 1) return null;
        if ((double) mono.length / sampleRate * 1000d < MIN_WINDOW_MS) return null;

        double[] window = hann(FRAME);
        double[] real = new double[FRAME];
        double[] imag = new double[FRAME];
        double[] chroma = new double[12];
        double total = 0d;
        for (int f = 0; f < frames; f++) {
            int base = f * HOP;
            for (int i = 0; i < FRAME; i++) {
                real[i] = mono[base + i] * window[i];
                imag[i] = 0d;
            }
            fft(real, imag);
            for (int bin = 1; bin < FRAME / 2; bin++) {
                double hz = bin * sampleRate / FRAME;
                if (hz < MIN_HZ || hz > MAX_HZ) continue;
                double mag = Math.hypot(real[bin], imag[bin]);
                if (!(mag > 0d)) continue;
                // No compression: the bins are *added*, and adding a compressed
                // magnitude over the ~400 bins of a five-octave band piles a nearly
                // constant contribution onto every pitch class — which is what a flat
                // profile is. Measured on the app's own cache: the profile that came
                // out of `log1p` sat at 0.066-0.097 per bin against a uniform 0.083,
                // i.e. it carried almost no key at all (a C major triad scored 0.42
                // per *synthesised* triad and 0.26 on real music, and 16 of 36 real
                // tracks were reported as A minor). The loudness bias the logarithm
                // was there to remove is handled where it belongs, by the taper below
                // and by the normalisation.
                double level = mag * weight(hz);
                // Which pitch class this bin is. The nearest semitone, not the
                // nearest template frequency: a bin that sits between two notes
                // belongs to whichever is closer, and the window's own leakage has
                // already spread it over both.
                int midi = (int) Math.round(69d + 12d * log2(hz / 440d));
                chroma[Math.floorMod(midi, 12)] += level;
                total += level;
            }
        }
        if (!(total > 0d)) return null;

        // The profile as a distribution (so the distance metric later has a unit),
        // mean-removed for the correlations below. The mean has to be taken AFTER the
        // normalisation: subtracting a mean of raw log levels from a normalised
        // profile leaves a vector that is a constant plus rounding error, and every
        // template then correlates with it equally — which reads as "no key is
        // better than any other" while still naming one, i.e. a confident-looking
        // answer with no evidence behind it.
        for (int i = 0; i < 12; i++) chroma[i] /= total;
        double mean = 0d;
        for (int i = 0; i < 12; i++) mean += chroma[i];
        mean /= 12d;
        double[] centred = new double[12];
        double norm = 0d;
        for (int i = 0; i < 12; i++) {
            centred[i] = chroma[i] - mean;
            norm += centred[i] * centred[i];
        }
        if (!(norm > 0d)) return null;

        double[] correlation = new double[24];
        for (int tonic = 0; tonic < 12; tonic++) {
            correlation[2 * tonic] = correlation(centred, centeredTemplate(MAJOR_TEMPLATE, tonic), norm);
            correlation[2 * tonic + 1] = correlation(centred, centeredTemplate(MINOR_TEMPLATE, tonic), norm);
        }
        int best = 0;
        for (int i = 1; i < 24; i++) {
            if (correlation[i] > correlation[best]) best = i;
        }
        int bestTonic = best / 2;
        boolean bestMajor = best % 2 == 0;

        // The strength is the margin over the best key this one could NOT be mixed
        // with. Counting the plain runner-up instead — which is what this used to do —
        // measures how well the profile separates a key from its own relative
        // major/minor and its two fifths, and those are exactly the readings that mean
        // the same thing to a mix: measured on 36 real tracks the plain runner-up was
        // one of those neighbours 24 times, so the old number was mostly a reading of
        // mode ambiguity and was 0.02-0.24 on material whose key was plain. A profile
        // that cannot decide between A minor and C major can still be certain the track
        // is not in F# major, and it is F# major that would sanction a wrong shift.
        int wheel = KeyProfile.camelotNumber(bestTonic, bestMajor);
        double bestFar = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 24; i++) {
            int step = Math.abs(wheel - KeyProfile.camelotNumber(i / 2, i % 2 == 0));
            if (step == 0 || step == 1 || step == 11) continue;
            if (correlation[i] > bestFar) bestFar = correlation[i];
        }
        if (bestFar == Double.NEGATIVE_INFINITY) bestFar = 0d;
        double margin = Math.max(0d, correlation[best] - bestFar);
        float strength = (float) Math.min(1d, margin / MARGIN_FOR_FULL_CONFIDENCE);
        return new KeyProfile(bestTonic, bestMajor, strength, chroma);
    }

    /** One Hann window, so a frame's edges do not spray energy over every pitch
     *  class (a rectangular window's sidelobes are what "everything correlates
     *  with everything" looks like in a chroma). */
    private static double[] hann(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = 0.5d - 0.5d * Math.cos(2d * Math.PI * i / (n - 1d));
        }
        return w;
    }

    /**
     * How much a frequency counts towards the profile.
     *
     * <p>Full weight below {@link #FULL_WEIGHT_HZ} and tapering to
     * {@link #HIGH_WEIGHT} at {@link #MAX_HZ}. This is not cosmetic: above a few
     * hundred hertz most of the energy in a recording is the *harmonics* of the
     * notes below it, and a note's harmonics are not its pitch class — the third
     * harmonic is a fifth above it and the fifth harmonic a major third, so a
     * profile built from the whole spectrum measures the key of a chord's harmonics
     * as much as the key of its notes. Measured on synthetic triads with a full
     * harmonic series: without the taper, C major (0.815) and E minor (0.813) come
     * within 0.002 of each other, because a C major triad's harmonics really do
     * spell an E minor chord. The roots of a chord are in the bass, and that is the
     * range this keeps.
     */
    private static double weight(double hz) {
        if (hz <= FULL_WEIGHT_HZ) return 1d;
        double t = Math.min(1d, log2(hz / FULL_WEIGHT_HZ) / log2(MAX_HZ / FULL_WEIGHT_HZ));
        return 1d - t * (1d - HIGH_WEIGHT);
    }

    /** Below this every frequency counts equally, Hz. */
    private static final double FULL_WEIGHT_HZ = 400d;
    /** The weight at {@link #MAX_HZ}, so the top of the analysed band still
     *  contributes but cannot dominate. */
    private static final double HIGH_WEIGHT = 0.25d;

    private static double log2(double v) {
        return Math.log(v) / Math.log(2d);
    }

    /** The template rotated so index 0 is its tonic, mean-removed. */
    private static double[] centeredTemplate(double[] template, int tonic) {
        double[] out = new double[12];
        double mean = 0d;
        for (int i = 0; i < 12; i++) {
            out[i] = template[Math.floorMod(i - tonic, 12)];
            mean += out[i];
        }
        mean /= 12d;
        for (int i = 0; i < 12; i++) out[i] -= mean;
        return out;
    }

    /** Pearson correlation of a mean-removed profile with a mean-removed template,
     *  where the profile's own norm is already known. */
    private static double correlation(double[] centred, double[] template, double norm) {
        double dot = 0d;
        double tNorm = 0d;
        for (int i = 0; i < 12; i++) {
            dot += centred[i] * template[i];
            tNorm += template[i] * template[i];
        }
        if (!(tNorm > 0d) || !(norm > 0d)) return 0d;
        return dot / Math.sqrt(norm * tNorm);
    }

    /**
     * In-place iterative radix-2 FFT. Hand-written because the analysis must run
     * on a desktop JVM as well as a phone and this app takes no dependencies, and
     * short because a power-of-two transform is twenty lines of bit-reversal and
     * butterflies. {@code FRAME} is a power of two by construction; the twiddles
     * are computed per stage rather than tabulated, which costs a few hundred
     * cosines per frame and keeps the class free of state.
     */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        // Bit-reversal permutation.
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2d * Math.PI / len;
            double wRe = Math.cos(angle);
            double wIm = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double curRe = 1d;
                double curIm = 0d;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    double vRe = re[b] * curRe - im[b] * curIm;
                    double vIm = re[b] * curIm + im[b] * curRe;
                    re[b] = re[a] - vRe;
                    im[b] = im[a] - vIm;
                    re[a] += vRe;
                    im[a] += vIm;
                    double nextRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nextRe;
                }
            }
        }
    }
}
