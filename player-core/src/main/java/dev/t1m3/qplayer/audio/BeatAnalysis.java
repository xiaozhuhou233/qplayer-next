package dev.t1m3.qplayer.audio;

/**
 * The tempo/beat-grid estimator, as plain arithmetic on mono samples: no platform
 * types, no dependencies, nothing that needs a device. Splitting it out of the
 * Android {@link BeatProfiler} implementation is deliberate — the decoding is
 * platform code, but the part that can be <em>wrong</em> is this, and keeping it
 * plain Java means it can be run on a desktop JVM against signals whose answer is
 * known (a click track at 160 BPM must come back as 160 BPM) instead of being
 * judged by whether its output looks plausible on a phone.
 *
 * <p>The method, in the order the code runs it:
 * <ol>
 *   <li><b>Onset strength envelope.</b> Short frames (40 ms every 10 ms) of mean
 *       square energy, log-compressed, first-differenced and half-wave rectified —
 *       a cheap stand-in for spectral flux that needs no FFT and reacts to exactly
 *       what a beat is: a sudden rise in energy. A local mean is then subtracted
 *       (per ~150 ms) so a quiet passage in a loud track still registers its
 *       attacks, and the result is mean-removed for the correlation below.</li>
 *   <li><b>Period by autocorrelation, at fractional lags.</b> The envelope is
 *       sampled every hop, but a period is not on that grid (a 160 BPM beat is
 *       37.6 hops) — correlating only at whole hops both misses the true peak and
 *       hands the win to its double, which is always closer to a whole number. So
 *       the correlation interpolates the envelope and is evaluated on a fine lag
 *       grid, each candidate scored as the mean of its correlation and those at
 *       two and three times the lag (the "harmonic sum": a click track correlates
 *       just as well at twice its beat period, and the sum is what lets the true,
 *       shorter period win over a half-tempo one).</li>
 *   <li><b>Period selection by the beat-rate heuristic.</b> For perfectly periodic
 *       material the beat and its double are mathematically indistinguishable (the
 *       signal really is periodic at both), so no amount of correlation can choose
 *       between them — a listener chooses, by tapping at the fastest level that
 *       still feels like the pulse. That is the rule here: the <em>shortest</em> lag
 *       that is a local correlation peak and explains the signal within
 *       {@link #BEAT_RATE_TOLERANCE} of the best one wins. It is why a 175 BPM
 *       track is reported at 175 and not at its half.</li>
 *   <li><b>The harmonic-relation check on that choice.</b> Correlation alone has a
 *       second blind spot besides the beat/double ambiguity: a lag that is
 *       <em>three halves</em> of the beat (a 2:3 relation) can correlate higher than
 *       the beat itself, and — measured on a real library — it does so on exactly the
 *       tracks whose groove is dotted or compound (the accent pattern repeats every
 *       one and a half beats). See {@link #finerBeat}: the chosen period is compared
 *       with its finer harmonic relatives (2/3, 1/2, 1/3 of it) and the finest of
 *       them that <em>the attacks support</em> is taken instead, which both undoes the
 *       2:3 mis-lock and keeps a genuine half- or third-time reading (a coarser grid
 *       is only kept when nothing is happening between its beats).</li>
 *   <li><b>Phase by pulse-train correlation.</b> The offset of a pulse train at the
 *       chosen period that collects the most onset energy, i.e. where the beats
 *       actually are in the file. The train uses the fractional period, so it keeps
 *       tracking the beats across the whole window instead of drifting away from
 *       them by half a hop per beat. It is the strongest phase, not necessarily the
 *       bar's downbeat: bars are structure, and nothing here measures structure.</li>
 *   <li><b>Confidence</b> from two things a beat has and a sustained pad does not:
 *       the pulse train is actually where the energy is (the onset energy collected
 *       on the beats versus the window's average), and — the reading the gate uses —
 *       that the window <em>agrees with itself</em>: split into equal segments, does
 *       every one of them answer the same period and the same phase? A steady grid
 *       answers the same from any part of the track, whatever its envelope looks
 *       like; the peak's prominence over the lags around it (the original reading,
 *       still measured and reported) does not, because on densely produced music
 *       every lag is busy and a perfectly steady grid's peak stands above very
 *       little. See {@link #windowAgreement}.</li>
 * </ol>
 *
 * <p>Nothing here is adaptive beyond that, and no attempt is made to follow a
 * tempo change, a ritardando or a bar structure. A constant period is the whole
 * model; the caller bounds what a wrong one can do.
 */
public final class BeatAnalysis {

    /** The tempo range the estimator reports, BPM. The lower bound keeps the
     *  window's beat count high enough to correlate (55 BPM is 27 beats in 30 s);
     *  the upper bound is what stops a track whose audible pulse is the eighth note
     *  from being reported at double tempo — lags shorter than 300 ms are simply
     *  not candidates, so a 128 BPM track with hi-hats on the eighths lands on 128
     *  rather than 256. */
    public static final double MIN_BPM = 55.0;
    public static final double MAX_BPM = 200.0;

    /** How finely the lag grid is sampled, in hops (0.1 hop = 1 ms). Fine enough
     *  that a period is found where it is rather than where the hop grid is, and
     *  cheap enough (a few hundred correlations over a 30 s window, measured at
     *  ~50 ms) to be worth not having a second refinement pass. */
    private static final double LAG_STEP_HOPS = 0.1;

    /** How much worse than the best correlation a lag may be and still be taken as
     *  the beat (the beat-rate heuristic in the class comment). Measured on
     *  synthetic click tracks with the widened envelope below: a beat and its double
     *  or triple come within 1% of each other, while a lag that is *not* a multiple
     *  of the beat stays several times lower — so the tolerance only has to be
     *  tighter than that gap, and 0.9 leaves room for real, less perfectly periodic
     *  material without ever promoting a lag that has no periodicity in it. */
    private static final double BEAT_RATE_TOLERANCE = 0.9d;

    /** One envelope hop / the frame each value is computed over, ms. */
    private static final double HOP_MS = 10.0;
    private static final double FRAME_MS = 40.0;

    /** The window the envelope's local mean is taken over, ms. */
    private static final double WHITEN_MS = 150.0;

    /** The shortest audio a grid is estimated from: below this the correlation has
     *  too few whole beats to be anything but noise. */
    public static final double MIN_WINDOW_MS = 6_000.0;

    /** How much of the envelope the correlation at a lag must still overlap, ms:
     *  a lag that leaves less than this correlates two unrelated halves. */
    private static final double MIN_LAG_OVERLAP_MS = 2_000.0;

    /** Beats the phase search needs before its answer means anything. */
    private static final int MIN_PHASE_BEATS = 4;

    /**
     * The shortest stretch an independent period estimate is taken from, ms.
     *
     * <p>A whole window is at least {@link #MIN_WINDOW_MS}, but the segments the
     * agreement is made of are cut out of it, and a segment is only evidence if it
     * holds enough beats to be correlated: six seconds is 5.5 beats even at
     * {@link #MIN_BPM}, which is the floor the whole-window estimate uses too. A
     * window shorter than two of these has nothing to check itself against and the
     * caller keeps the older prominence reading (see {@link #windowAgreement}).
     */
    static final double MIN_SEGMENT_MS = 6_000.0;

    /**
     * How much of its own best correlation a segment must still reach at the
     * <em>window's</em> period before it counts as confirming the window's grid.
     *
     * <p>This is the tempo half of the agreement, and the reading is deliberately a
     * comparison with the segment's own best rather than with the period the segment
     * would have picked on its own: a ten-second stretch cannot always tell a beat
     * from its double (that is what the whole thirty-second window and the beat-rate
     * rule are for), so asking a segment "what is your tempo?" and insisting it answer
     * the window's is asking it a question it cannot answer — measured on a real
     * library, every densely produced track failed that way, at exactly 0. Two beats
     * of a click track are as periodic as one.
     *
     * <p>What a segment <em>can</em> answer is whether the window's grid is there:
     * correlation at the window's lag, against the best that stretch has to offer. A
     * segment that confirms the grid comes close to its own best (the same peak,
     * measured over a third of the data); one whose beats are somewhere else — a tempo
     * change, a free-tempo passage, a stretch of speech — correlates near zero at a
     * lag that is not its own. This is the same shape of question
     * {@code BeatProfile.gridsCompatible} asks of two tracks, asked of one track's
     * own window.
     *
     * <p>0.35 because that is what the real material left room for, measured on a
     * device over a sample of one library: a track with a steady grid never dropped
     * below 0.62 in any third of its window, while a track whose tempo changes across
     * the window measured <em>negative</em> (-0.07: the window's lag is a valley in a
     * third that moves at another tempo). 0.35 sits inside that gap, on the side that
     * refuses less: the cost of refusing is a boundary that is not aligned (the
     * behaviour before any of this existed), the cost of accepting is two grids that
     * do not hold together — and it is the tempo half that catches those (the same
     * sample: a 97.2 BPM reading of a track that is 145 BPM, a 2:3 error, is refused
     * by this reading while the old prominence metric's 0.21 refused it for the wrong
     * reason).
     */
    private static final double MIN_SEGMENT_SUPPORT = 0.35d;

    /**
     * How far a segment's own beats may sit from where the window's grid puts them,
     * as a fraction of a beat, and still count as supporting the phase the window
     * picked. Four tenths of a beat is between a triplet and a sixteenth away from the
     * grid — past it a stretch is no longer on any of the grid's subdivisions, and
     * that is what a phase disagreement between a track's kick and its hats looks like
     * when it goes too far.
     *
     * <p>Averaged over the segments rather than decided by the worst one, and
     * deliberately on the loose side. An accent-level phase disagreement is what dense
     * real music produces — a third whose strongest onsets are the hi-hats rather than
     * the kick reads its "beats" a sixteenth away from the window's — and that
     * ambiguity says nothing about whether the grid is there: the period is confirmed
     * by every segment (the half above), and the alignment uses ONE phase, the
     * window's own. What this half still catches is a window with no single phase in
     * it (two of three segments a beat's subdivision or more away, each contributing
     * nothing), which no phase choice can rescue.
     *
     * <p>Where this number came from, on one device and one real library (nine
     * tracks, ground truth checked for two): at 0.35, three of the nine were trusted;
     * at 0.4, five; at 0.5, seven. 0.4 is where the phase half still refuses a window
     * whose thirds disagree and stops punishing a track whose third behaves as a
     * slightly different accent pattern.
     */
    private static final double PHASE_SPREAD_TOLERANCE = 0.4d;

    /** The strongest attack a track must contain before any of its correlation
     *  means anything, in the envelope's own (log, self-normalised) unit. An
     *  ordinary onset is several times this; a sustained drone's envelope is
     *  rounding error. Measured: click/pop material lands at 2-3, speech-ish
     *  material at 0.7-0.8, and a pure pad or white noise under 0.11. */
    private static final double MIN_ONSET_PEAK = 0.15d;

    /** How much more onset energy the beats must carry than the window's average
     *  frame, as a fraction of the on-beat level: 0 means "the pulses are exactly
     *  where the energy is not", and this rejects a signal whose "beats" are just
     *  the tallest bumps in a smooth envelope. */
    private static final double MIN_BEAT_CONTRAST = 0.5d;

    /**
     * How much of the chosen grid's per-pulse attack energy a finer harmonic relative
     * has to carry before the finer grid is taken as the beat instead (see
     * {@link #finerBeat}).
     *
     * <p>The reading is "the attacks the finer grid adds are not empty": on a click
     * track the positions between the beats are silent, so halving the period halves
     * the energy collected per pulse, while on material whose groove really moves at
     * the finer level the extra pulses are attacked too and the per-pulse energy barely
     * drops. 0.8 is the boundary between those two: a finer grid has to keep 80% of the
     * chosen one's per-pulse energy, i.e. the between-beat positions have to carry
     * roughly three fifths of a beat's energy (from 1.5·x − 1 = 0.8 at the 2:3
     * relation, and 2·x − 1 = 0.8 when halving).
     *
     * <p>Measured, so the two sides can be told apart: synthetic material sits at
     * 0.30-0.50 whatever is put between the beats (a click track with hats on every
     * eighth, triplets at a third and two thirds, accents every third beat, all of the
     * above with a bass line under it — the loudest is the triplet at 0.50), while the
     * real tracks whose 2:3 mis-lock this exists for measure 0.80-1.27 at the true beat
     * (double take 0.93, OWA OWA 1.02, HEARD OF US 1.23, ON MY WAY 1.27). 0.8 sits in
     * that gap, at the end of it that promotes less: the two real cases nearest it
     * measure 0.798 and 0.799, and the one that would be promoted by admitting them is
     * a half-time grid the gate is happy with today (DAY1, 66.9 BPM) while the other
     * (Better Now at the wrong 97.2) is refused by the gate either way.
     */
    private static final double FINER_GRID_SUPPORT = 0.8d;

    /**
     * How much of the best correlation a finer harmonic relative needs before it is
     * allowed to replace the chosen period.
     *
     * <p>A guard on the other side of {@link #FINER_GRID_SUPPORT}, which on its own asks
     * only about attack energy and would be happy to move a grid onto a lag where the
     * envelope has no periodicity at all (a sustained passage has small rises
     * everywhere, so its per-pulse energy is flat). Measured on the tracks the check is
     * for: the true beat's own correlation sits at 0.5-0.6 of the best, so 0.45 admits
     * them and still refuses a lag that carries attack energy but explains no period.
     */
    private static final double FINER_GRID_PERIODICITY = 0.45d;

    /**
     * How far around a candidate lag the pulse train is allowed to be re-tuned before two
     * grids are compared, as a fraction of the lag.
     *
     * <p>A harmonic relation between two real tracks' periods is approximate — measured on
     * one library, the periods that are 2:3 apart are 0.5% off the exact ratio, because the
     * lag the correlation chose is itself a few tenths of a percent off the music — and the
     * energy a pulse train collects falls off fast as it drifts off the beats: over a 30 s
     * window, 0.5% of the period is 145 ms of accumulated drift and it costs 38% of the
     * collected energy. Comparing the two grids at the exact 2/3 point instead of at each
     * one's own optimum therefore measures the arithmetic rather than the music (measured:
     * it left Better Now, a 145 BPM track read as 97.2, at 0.79 against a threshold of
     * 0.8 — a wrong grid kept by half a percent).
     */
    private static final double FINER_GRID_TUNE = 0.01d;

    private BeatAnalysis() {}

    /**
     * Estimate the grid of one track.
     *
     * @param mono       the (downmixed, downsampled) samples
     * @param sampleRate the rate they were sampled at
     * @param originMs   the file time of {@code mono[0]}, ms — what makes the
     *                   returned phase an offset into the track rather than into
     *                   whatever window was decoded
     * @return the grid, or null when there was nothing to measure (too little
     *         audio, no signal at all, no attack worth calling a beat).
     */
    public static BeatProfile analyse(double[] mono, double sampleRate, long originMs) {
        if (mono == null || mono.length == 0 || sampleRate <= 0d) return null;
        int hop = Math.max(1, (int) Math.round(HOP_MS / 1000d * sampleRate));
        int frame = Math.max(hop + 1, (int) Math.round(FRAME_MS / 1000d * sampleRate));
        if (mono.length <= frame) return null;
        int frames = (mono.length - frame) / hop + 1;
        // The hop's real length, not the 10 ms it was asked for: a sample rate that
        // does not divide evenly would otherwise put every period off by the
        // difference (at 11 kHz a 10 ms hop is 110.25 samples, so 0.25% — which is
        // 4 ms of drift across a 16-beat overlap).
        double hopMs = (double) hop / sampleRate * 1000d;
        double windowMs = (double) frames * hop / sampleRate * 1000d;
        if (windowMs < MIN_WINDOW_MS) return null;

        // 1. Frame energies.
        double[] energy = new double[frames];
        double energySum = 0d;
        for (int i = 0; i < frames; i++) {
            int base = i * hop;
            double sum = 0d;
            for (int j = 0; j < frame; j++) {
                double s = mono[base + j];
                sum += s * s;
            }
            energy[i] = sum / frame;
            energySum += energy[i];
        }
        double meanEnergy = energySum / frames;
        if (!(meanEnergy > 0d)) return null;             // digital silence: no grid

        // Onset strength: the rise in log energy from one frame to the next, floored
        // at zero so a decay is not an onset. Energy is normalised by the track's own
        // mean first, so a quiet track and a loud one produce the same numbers.
        double[] onset = new double[frames];
        for (int i = 1; i < frames; i++) {
            double rise = Math.log1p(energy[i] / meanEnergy) - Math.log1p(energy[i - 1] / meanEnergy);
            onset[i] = Math.max(0d, rise);
        }
        // Adaptive threshold: subtract the onset envelope's own local mean so a
        // sustained loud passage does not bury a quiet one's attacks.
        int whiten = Math.max(1, (int) Math.round(WHITEN_MS / hopMs));
        double[] shaped = new double[frames];
        double shapedSum = 0d;
        double onsetPeak = 0d;
        for (int i = 0; i < frames; i++) {
            int lo = Math.max(0, i - whiten);
            int hi = Math.min(frames - 1, i + whiten);
            double sum = 0d;
            for (int j = lo; j <= hi; j++) sum += onset[j];
            shaped[i] = Math.max(0d, onset[i] - sum / (hi - lo + 1));
            shapedSum += shaped[i];
            onsetPeak = Math.max(onsetPeak, shaped[i]);
        }
        // A track with no attacks at all (a sustained pad, a drone) has an envelope
        // made of rounding error, and every correlation computed from it is
        // meaningless however high it looks: this floor keeps such a track from
        // being given a grid at all.
        if (onsetPeak < MIN_ONSET_PEAK) return null;
        double shapedMean = shapedSum / frames;
        // The envelope the correlation sees is the rectified one widened by one hop
        // either side (a 3-tap [1/4, 1/2, 1/4] filter). An onset is one frame wide,
        // and correlating a spike that narrow at a *fractional* lag is what makes a
        // period's multiples score higher than the period itself (measured on click
        // tracks: up to 12% at the third multiple) — the beat is a ~50 ms event to a
        // listener, so measuring it that way is the honest reading, not a fudge.
        // The phase search below deliberately keeps the sharp envelope: it is looking
        // for *where* the attack is, not how periodic it is.
        double[] widened = new double[frames];
        for (int i = 0; i < frames; i++) {
            double left = shaped[Math.max(0, i - 1)];
            double right = shaped[Math.min(frames - 1, i + 1)];
            widened[i] = 0.25d * left + 0.5d * shaped[i] + 0.25d * right;
        }
        // Mean-removed copy for the correlation (a non-zero mean would dominate it).
        double widenedMean = 0d;
        for (double v : widened) widenedMean += v;
        widenedMean /= frames;
        double[] centred = new double[frames];
        for (int i = 0; i < frames; i++) centred[i] = widened[i] - widenedMean;

        // 2. Harmonic-sum strength over a fine lag grid (see LAG_STEP_HOPS), and 3.
        //    the period that comes out of it (the shortest near-best peak): the same
        //    search the segments below run on their own stretches, so a segment's
        //    answer is directly comparable with the window's.
        Search full = search(centred, 0, frames, hopMs);
        if (full == null) return null;
        // 3. The harmonic-relation check on that choice (see finerBeat).
        int beatIndex = finerBeat(full, shaped, frames, hopMs);
        double periodHops = full.minLag + beatIndex * LAG_STEP_HOPS;
        double periodMs = periodHops * hopMs;
        double bpm = 60_000d / periodMs;
        if (bpm < MIN_BPM - 0.5d || bpm > MAX_BPM + 0.5d) return null;

        // 4. Phase: the pulse train at that (fractional) period that collects the
        //    most onset energy. Half a hop of resolution, which is a 5 ms
        //    quantisation of the phase — well inside the caller's shift cap.
        Pulse windowPhase = phaseOf(shaped, 0, frames, periodHops);
        if (windowPhase == null) return null;
        double bestPhaseOffset = windowPhase.offsetFrames;
        // Where the attack actually is, in frames. An onset is registered on the
        // frame whose *window first contains* it — the energy of frame i is the mean
        // over [i*hop, i*hop+frame) — so the frame's start is up to a whole frame
        // early. Measured on synthetic click tracks, reading it as the frame start
        // puts the grid 33 ms ahead of the music on average; the attack is at the
        // far end of that window, so that is where the frame's onset is dated. (The
        // residual is the hop quantisation and the click's own rise, both under
        // 10 ms.)
        double onsetLeadFrames = (frame - hop * 0.5d) / (double) hop;
        double onBeatMean = windowPhase.sum / windowPhase.beats;
        double beatContrast = onBeatMean > 0d ? 1d - shapedMean / onBeatMean : 0d;
        if (beatContrast < MIN_BEAT_CONTRAST) return null;
        long firstBeatMs = originMs + Math.round((bestPhaseOffset + onsetLeadFrames) * hopMs);
        long periodMsRounded = Math.max(1L, Math.round(periodMs));
        firstBeatMs = Math.floorMod(firstBeatMs, periodMsRounded);

        // 5. Confidence: whether the window agrees with itself (the gate the caller
        //    reads), and the peak's prominence (the reading this replaced, still
        //    reported so the two can be compared on real material). A window too
        //    short to be cut into segments has nothing to check itself against, and
        //    keeps the prominence reading rather than being refused outright.
        float prominence = (float) Math.max(0d, Math.min(1d, full.prominence()));
        double agreement = windowAgreement(centred, shaped, hopMs, periodHops, bestPhaseOffset,
                beatIndex, frames, windowMs);
        float confidence = agreement >= 0d ? (float) Math.max(0d, Math.min(1d, agreement))
                : prominence;

        // The key is measured by the host from the same decode and attached afterwards
        // (see BeatProfile.withKey); the estimator itself never reads samples for it.
        return new BeatProfile(bpm, firstBeatMs, confidence, prominence, null);
    }

    /**
     * The harmonic-relation check: is the period correlation picked really the beat,
     * or a coarsening of it that correlation cannot see past?
     *
     * <p>Autocorrelation has a blind spot beyond the beat/double ambiguity the
     * beat-rate rule handles, and it took real material to find it: a lag of
     * <em>three halves</em> the beat — a 2:3 relation — correlates higher than the beat
     * itself on tracks whose groove is dotted or compound, because that is the period
     * the accent pattern actually repeats at. Measured on one library, a track that is
     * 109 BPM (ground truth) came back as 72.7 and one that is 145 BPM as 97.2, both
     * exactly 2/3, and the check below has to undo that: the grid is then not a coarse
     * version of the beat, it is a grid whose every other beat falls <em>between</em>
     * the music's beats, so aligning two tracks to it aligns them to the wrong place —
     * and no other track can be matched to it either (the mix's tempo ratio comes out
     * 1.5, outside the clamp that decides whether two tracks can be mixed at all).
     *
     * <p>What correlation cannot answer, the attacks can: the finer grid's extra pulses
     * either have attacks on them or they do not. So the chosen period is compared with
     * its finer harmonic relatives — 2/3 first (the dotted case above, and the one this
     * exists for), then 1/2 and 1/3 (which are legitimate readings on their own) — and
     * the finest relative that clears both of these is taken instead:
     * <ul>
     *   <li>its per-pulse attack energy, from the same half-hop phase search the caller
     *       then uses, is at least {@link #FINER_GRID_SUPPORT} of the chosen grid's
     *       (see that constant for why 0.8), and</li>
     *   <li>it carries at least {@link #FINER_GRID_PERIODICITY} of the best
     *       correlation itself, so a flat envelope cannot drag the grid onto a lag with
     *       no period in it.</li>
     * </ul>
     *
     * <p>One step only, from the correlation's own choice, never chained: a relative is
     * measured against the grid that was chosen, not against another relative that was
     * itself promoted. And the tempo range bounds the whole thing — a candidate below
     * {@link #MIN_BPM} is not a faster beat, it is out of the range this estimator
     * reports, so the search stops there (which is also what keeps a 128 BPM track with
     * hats on every eighth from being reported at 256).
     *
     * <p>When nothing clears both tests the chosen period stands: a genuine half-time or
     * third-time reading survives exactly as before, and a grid is never made
     * <em>coarser</em> here (every promotion adds beats; losing them would only lose
     * alignment resolution).
     *
     * @return the index into {@code full.strength} of the period to use — the same one
     *         correlation chose when the check has nothing to say
     */
    private static int finerBeat(Search full, double[] shaped, int frames, double hopMs) {
        double coarseLag = full.lag();
        int coarseCentre = (int) Math.round((coarseLag - full.minLag) / LAG_STEP_HOPS);
        int coarseSpan = tuneSpan(coarseLag);
        double coarseMean = meanOnGrid(shaped, frames,
                full.minLag + bestMeanIndex(shaped, frames, full.minLag, coarseCentre, coarseSpan)
                        * LAG_STEP_HOPS);
        if (!(coarseMean > 0d)) return full.chosen;
        double[] ratios = {2d / 3d, 0.5d, 1d / 3d};
        for (double ratio : ratios) {
            double target = coarseLag * ratio;
            int centre = (int) Math.round((target - full.minLag) / LAG_STEP_HOPS);
            if (centre < 0) break;               // below MIN_BPM: not a beat, out of range
            int span = tuneSpan(target);
            if (peakStrength(full, centre, span) < full.bestStrength * FINER_GRID_PERIODICITY) {
                continue;
            }
            int index = bestMeanIndex(shaped, frames, full.minLag, centre, span);
            double mean = meanOnGrid(shaped, frames, full.minLag + index * LAG_STEP_HOPS);
            if (mean >= coarseMean * FINER_GRID_SUPPORT) return index;
        }
        return full.chosen;
    }

    /** How many lag grid steps {@link #FINER_GRID_TUNE} of {@code lagHops} is — the width of
     *  the neighbourhood a candidate and its attack reading are taken over. */
    private static int tuneSpan(double lagHops) {
        return Math.max(1, (int) Math.round(lagHops * FINER_GRID_TUNE / LAG_STEP_HOPS));
    }

    /** The index within {@code span} of {@code centre} whose pulse train collects the most
     *  onset energy — where a grid's own attack reading is taken (see
     *  {@link #FINER_GRID_TUNE}). */
    private static int bestMeanIndex(double[] shaped, int frames, double minLag, int centre,
                                     int span) {
        int best = centre;
        double bestMean = -1d;
        for (int c = Math.max(0, centre - span); c <= centre + span; c++) {
            double mean = meanOnGrid(shaped, frames, minLag + c * LAG_STEP_HOPS);
            if (mean > bestMean) {
                bestMean = mean;
                best = c;
            }
        }
        return best;
    }

    /** The strongest correlation within {@code span} of {@code centre} — whether that
     *  neighbourhood holds a lag with a period in it at all (see
     *  {@link #FINER_GRID_PERIODICITY}). Read over the neighbourhood rather than at one lag
     *  because the relation between two periods is approximate: measured on a real library,
     *  the true beat of a track whose grid sits 2:3 away fell <em>between</em> two correlation
     *  peaks, so a check at the exact lag refused the grid the attacks all sit on. */
    private static double peakStrength(Search full, int centre, int span) {
        double best = 0d;
        for (int c = Math.max(0, centre - span);
             c <= Math.min(full.strength.length - 1, centre + span); c++) {
            best = Math.max(best, full.strength[c]);
        }
        return best;
    }

    /**
     * The onset energy a grid at {@code lagHops} collects per pulse, at the phase that
     * collects the most — the reading {@link #finerBeat} compares two grids with, and the
     * same one the caller's phase search then uses. The <em>sharp</em> envelope, so a
     * pulse either lands on an attack or it does not.
     *
     * @return 0 when the window is too short to hold pulses at that lag
     */
    private static double meanOnGrid(double[] shaped, int frames, double lagHops) {
        Pulse pulse = phaseOf(shaped, 0, frames, lagHops);
        return pulse == null || pulse.beats <= 0 ? 0d : pulse.sum / pulse.beats;
    }

    /** Where one stretch of the envelope put its beats, and how much onset energy it
     *  collected there. Immutable, built once per search. */
    private static final class Pulse {
        final double offsetFrames;
        final double sum;
        final int beats;

        Pulse(double offsetFrames, double sum, int beats) {
            this.offsetFrames = offsetFrames;
            this.sum = sum;
            this.beats = beats;
        }
    }

    /** One stretch's period search: the strength curve it walked, the candidate it
     *  chose, and the lag grid that curve was indexed on. Immutable. */
    private static final class Search {
        final double[] strength;
        final int chosen;
        final double minLag;
        /** The chosen lag's strength — what a segment has to be compared against
         *  when it is asked whether another lag is supported. */
        final double bestStrength;

        Search(double[] strength, int chosen, double minLag, double bestStrength) {
            this.strength = strength;
            this.chosen = chosen;
            this.minLag = minLag;
            this.bestStrength = bestStrength;
        }

        /** The chosen lag, hops. */
        double lag() {
            return lagOf(chosen, minLag);
        }

        /** The chosen period, ms. */
        double periodMs(double hopMs) {
            return lag() * hopMs;
        }

        /** How far the chosen lag's strength stands above the lowest strength within
         *  half a period of it: the peak's prominence (the confidence reading this
         *  phase replaced, kept in the log while the new one is calibrated). */
        double prominence() {
            double lowest = strength[chosen];
            int halfSpan = Math.max(1, (int) Math.round(lag() / 2d / LAG_STEP_HOPS));
            for (int c = Math.max(0, chosen - halfSpan);
                 c <= Math.min(strength.length - 1, chosen + halfSpan); c++) {
                lowest = Math.min(lowest, strength[c]);
            }
            return strength[chosen] - lowest;
        }
    }

    /**
     * The period a stretch of the envelope moves at: steps 2 and 3 above, on
     * {@code [from, from + length)} of the same (mean-removed, widened) envelope.
     *
     * <p>Split out so the whole window and the segments the agreement is made of are
     * measured by <em>the same</em> rule — a segment's answer is only evidence about
     * the window's if the two were asked the same question. The correlation's own
     * overlap floor is the same absolute one: a lag is only usable if this stretch
     * still has {@link #MIN_LAG_OVERLAP_MS} of envelope to correlate with itself.
     *
     * @return null when this stretch has nothing to say (too short to correlate, no
     *         positive correlation at any lag)
     */
    private static Search search(double[] centred, int from, int length, double hopMs) {
        double minLag = 60_000d / MAX_BPM / hopMs;
        double maxLag = 60_000d / MIN_BPM / hopMs;
        double overlapFloor = MIN_LAG_OVERLAP_MS / hopMs;
        if (length - maxLag < overlapFloor) return null;
        int candidates = (int) ((maxLag - minLag) / LAG_STEP_HOPS) + 1;
        if (candidates < 3) return null;
        double[] strength = new double[candidates];
        for (int c = 0; c < candidates; c++) {
            double lag = minLag + c * LAG_STEP_HOPS;
            double sum = 0d;
            double weightSum = 0d;
            for (int m = 1; m <= 3; m++) {
                double multiple = lag * m;
                if (length - multiple < overlapFloor) continue;
                double r = correlation(centred, from, length, lag * m, overlapFloor);
                if (m > 1 && r <= 0d) continue;          // an absent harmonic adds nothing
                sum += r / m;
                weightSum += 1d / m;
            }
            if (weightSum <= 0d) continue;
            strength[c] = sum / weightSum;
        }
        double bestStrength = Double.NEGATIVE_INFINITY;
        int bestIndex = -1;
        for (int c = 0; c < candidates; c++) {
            if (strength[c] > bestStrength) {
                bestStrength = strength[c];
                bestIndex = c;
            }
        }
        if (bestIndex < 0 || bestStrength <= 0d) return null;
        // The beat is the shortest near-best peak (see the class comment).
        int chosen = -1;
        for (int c = 0; c < candidates; c++) {
            if (!isLocalPeak(strength, c)) continue;
            if (strength[c] < bestStrength * BEAT_RATE_TOLERANCE) continue;
            chosen = c;                                   // the first, i.e. the fastest
            break;
        }
        if (chosen < 0) chosen = bestIndex;
        return new Search(strength, chosen, minLag, bestStrength);
    }

    /**
     * How much the analysis window agrees with itself — the reading the confidence
     * gate is made of.
     *
     * <p>Peak prominence (the reading this replaced) measures how far a track's
     * autocorrelation peak stands above the lags around it. On real, densely produced
     * music every lag is busy — hats, snares, synth stabs are onsets at every
     * subdivision — so a perfectly steady grid's peak stands above very little. On a
     * real library that refused roughly half of it (0.12-0.34, for tracks whose grids
     * are audibly steady) while accepting the same kinds of tracks it rejected.
     *
     * <p>What matters instead is that the measurement holds across the window: the
     * span is cut into equal segments, each is asked for its own period and its own
     * phase with the same rules, and the confidence is how well they answer the same
     * thing. A track with a steady grid answers the same from any part of it,
     * whatever its envelope looks like; a free-tempo passage, a stretch of speech or
     * a segment that hears a different multiple does not.
     *
     * <p>Two parts, multiplied:
     * <ul>
     *   <li><b>tempo</b> — the worst segment's correlation at the window's own period,
     *       against the best that segment has to offer
     *       ({@link #MIN_SEGMENT_SUPPORT}). One segment that does not confirm the
     *       grid is enough to refuse: the period is what the alignment extrapolates
     *       across the track, so a third of the window that does not hold it means the
     *       grid is not a property of the track.</li>
     *   <li><b>phase</b> — how far each segment's own beats sit from where the
     *       window's grid puts them, as a fraction of a beat, over
     *       {@link #PHASE_SPREAD_TOLERANCE}, averaged over the segments: one phase
     *       has to be picked for the whole track (the window's own), and this says
     *       how much of the window supports it. The worst segment does not decide
     *       here — see the tolerance for why an accent-level disagreement is
     *       ambiguity rather than a wrong grid.</li>
     * </ul>
     *
     * <p>A segment that answers nothing at all (no period, or too few beats for a
     * phase) is skipped rather than counted as a disagreement: silence or a spoken
     * intro is not evidence that the track has no beat. Fewer than two segments that
     * answered means there is no agreement to measure, and the caller keeps the
     * prominence reading.
     *
     * @return 0..1, or -1 when the window is too short or too uninformative to be
     *         split into at least two answering segments
     */
    private static double windowAgreement(double[] centred, double[] shaped, double hopMs,
                                          double periodHops, double windowPhaseFrames,
                                          int windowChosen, int frames, double windowMs) {
        int segments = windowMs >= 3d * MIN_SEGMENT_MS ? 3
                : (windowMs >= 2d * MIN_SEGMENT_MS ? 2 : 0);
        if (segments == 0) return -1d;
        double worstSupport = 1d;
        double phaseFitSum = 0d;
        int answered = 0;
        int phaseAnswered = 0;
        for (int s = 0; s < segments; s++) {
            int from = (int) Math.round((double) s * frames / segments);
            int to = (int) Math.round((double) (s + 1) * frames / segments);
            int length = to - from;
            if (length * hopMs < MIN_SEGMENT_MS) continue;     // too short to answer
            Search segment = search(centred, from, length, hopMs);
            if (segment != null) {
                // The window's own lag, read off this segment's strength curve: the
                // lag grid is the same one in both searches (it depends only on the
                // hop and the tempo range), so the window's candidate is an index.
                double support = segmentSupport(segment, windowChosen);
                worstSupport = Math.min(worstSupport, support);
                answered++;
            }
            Pulse phase = phaseOf(shaped, from, length, periodHops);
            if (phase != null) {
                // Where the window's own grid says this segment's beats are, as an
                // offset into the segment: the pulse train is periodic, so the phase
                // carries into every segment by arithmetic rather than by assumption.
                long periodFrames = Math.max(1L, Math.round(periodHops));
                double expected = Math.floorMod(Math.round(windowPhaseFrames - from), periodFrames);
                double delta = Math.abs(phase.offsetFrames - expected);
                double deviation = Math.min(delta, periodFrames - delta) / periodFrames;
                phaseFitSum += Math.max(0d, 1d - deviation / PHASE_SPREAD_TOLERANCE);
                phaseAnswered++;
            }
        }
        if (answered < 2) return -1d;
        double tempoFit = Math.min(1d, Math.max(0d,
                (worstSupport - MIN_SEGMENT_SUPPORT) / (1d - MIN_SEGMENT_SUPPORT)));
        // A window whose segments all answered about the tempo but none about the
        // phase is judged on the tempo alone (the gate is not made harsher for a
        // measurement that was not possible).
        double phaseFit = phaseAnswered > 0 ? phaseFitSum / phaseAnswered : 1d;
        return tempoFit * phaseFit;
    }

    /**
     * How close a lag's ratio to the window's own lag has to be to count as the same music
     * seen through the 2:3 blind spot, and so be excluded from the yardstick a segment is
     * judged against (see {@link #segmentSupport}).
     *
     * <p>Three percent: the relations the check is about are exact arithmetic (three halves,
     * two thirds), while two unrelated tempos in one window are a few tenths of a percent
     * apart at best (measured: the 2:3 pairs on one library sit 0.0-0.5% off the exact ratio,
     * because the correlation's own lag is a fraction of a percent off the music). Anything
     * inside this band is a lag the corrected grid was chosen <em>over</em>, so it cannot also
     * be the thing it is measured against.
     */
    private static final double HARMONIC_RELATION_TOLERANCE = 0.03d;

    /**
     * How well one segment of the window confirms the window's own grid: the correlation it
     * has at that lag, against the best it has to offer — with the 2:3 relatives of that lag
     * excluded from being the yardstick (see {@link #HARMONIC_RELATION_TOLERANCE}).
     *
     * <p>This is where the harmonic-relation check reaches the confidence gate. The window's
     * period is no longer whatever correlation says (see {@link #finerBeat}), but a segment
     * asked the same question separately answers what the whole window used to: on the tracks
     * the check exists for, a third of the window correlates <em>higher</em> at three halves
     * of the true beat than at the beat, because that is where the accent pattern repeats.
     * Left as the yardstick, that one lag takes the support reading of a perfectly steady grid
     * down to 0.18-0.22 — measured on a 109 BPM track whose three thirds agree with the
     * corrected grid's phase to 0.05, 0.06 and 0.11 of a beat, i.e. the grid is more solid
     * across the window than most of the library's, and the reading refused it anyway. Fixing
     * the period and leaving this alone would trade one wrong answer for another.
     *
     * <p>Clamped to 1: with the relatives out of the way a segment can correlate higher at the
     * window's lag than at anything else left, which is full support, not more than full.
     *
     * @return 0..1
     */
    private static double segmentSupport(Search segment, int windowChosen) {
        double own = segment.strength[windowChosen];
        if (!(own > 0d)) return 0d;
        double windowLag = lagOf(windowChosen, segment.minLag);
        double yardstick = 0d;
        for (int c = 0; c < segment.strength.length; c++) {
            double ratio = lagOf(c, segment.minLag) / windowLag;
            if (Math.abs(ratio - 3d / 2d) < HARMONIC_RELATION_TOLERANCE) continue;
            if (Math.abs(ratio - 2d / 3d) < HARMONIC_RELATION_TOLERANCE) continue;
            yardstick = Math.max(yardstick, segment.strength[c]);
        }
        // Nothing unrelated left to compare with: the segment's periodicity is either this
        // grid or nothing, and either way it is not evidence of another one.
        if (!(yardstick > 0d)) return 1d;
        return Math.min(1d, own / yardstick);
    }

    /**
     * The offset within {@code [from, from + length)} of the pulse train at
     * {@code periodHops} that collects the most onset energy — where this stretch's
     * beats actually are. Half a hop of resolution (5 ms at this hop), which is well
     * inside the caller's shift cap.
     *
     * <p>The sharp (not widened) envelope, deliberately: this is looking for
     * <em>where</em> an attack is, not how periodic it is.
     *
     * @return null when this stretch is too short to hold {@link #MIN_PHASE_BEATS}
     *         pulses of that period
     */
    private static Pulse phaseOf(double[] shaped, int from, int length, double periodHops) {
        int periodFrames = Math.max(1, (int) Math.round(periodHops));
        int phaseSteps = periodFrames * 2;
        int bestStep = -1;
        double bestSum = -1d;
        int bestBeats = 0;
        for (int step = 0; step < phaseSteps; step++) {
            double offset = step * 0.5d;
            double sum = 0d;
            int beats = 0;
            for (double at = offset; at < length; at += periodHops) {
                int i = from + (int) Math.round(at);
                if (i >= from + length) break;
                sum += shaped[i];
                beats++;
            }
            if (beats < MIN_PHASE_BEATS) break;
            if (sum > bestSum) {
                bestSum = sum;
                bestStep = step;
                bestBeats = beats;
            }
        }
        if (bestStep < 0 || bestBeats <= 0) return null;
        return new Pulse(bestStep * 0.5d, bestSum, bestBeats);
    }

    /** True when {@code c} is a peak of the strength curve — higher than both
     *  neighbours, or than the one neighbour the grid has at its ends. */
    private static boolean isLocalPeak(double[] strength, int c) {
        boolean left = c == 0 || strength[c] >= strength[c - 1];
        boolean right = c == strength.length - 1 || strength[c] >= strength[c + 1];
        return left && right;
    }

    private static double lagOf(int index, double minLag) {
        return minLag + index * LAG_STEP_HOPS;
    }

    /** Normalised correlation of {@code centred[from .. from+length)} with itself at
     *  a (possibly fractional) lag, linearly interpolating between envelope samples.
     *  Taking the stretch rather than the whole array is what lets one window be
     *  correlated in pieces (see {@link #windowAgreement}). */
    private static double correlation(double[] centred, int from, int length, double lagHops,
                                      double overlapFloor) {
        int lagFloor = (int) Math.floor(lagHops);
        double frac = lagHops - lagFloor;
        int n = length - lagFloor - 1;
        if (n < overlapFloor || n <= 0) return 0d;
        double num = 0d;
        double denA = 0d;
        double denB = 0d;
        for (int i = 0; i < n; i++) {
            double a = centred[from + i];
            double b0 = centred[from + i + lagFloor];
            double b = b0 + (centred[from + i + lagFloor + 1] - b0) * frac;
            num += a * b;
            denA += a * a;
            denB += b * b;
        }
        if (denA <= 0d || denB <= 0d) return 0d;
        return num / Math.sqrt(denA * denB);
    }

}
