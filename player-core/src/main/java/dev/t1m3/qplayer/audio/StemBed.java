package dev.t1m3.qplayer.audio;

import java.util.Arrays;
import java.util.Locale;

/**
 * The cloud bed: the passage the music model generated for a fusion pair, turned into an
 * ambience/texture bed to be summed UNDER the project's own passage rather than played as the
 * passage itself — one bar of fade at each end of the incoming track's own grid, RMS-normalised to
 * {@link #BED_LEVEL_DB} below the passage it goes under, and therefore silent at both of its own
 * edges.
 *
 * <p><b>Why its ends are zero.</b> That is the load-bearing part of the design and the reason this
 * class exists at all. The gain envelope is exactly 0 at the bed's first and last sample — this
 * method applies it, the caller does not — so whatever the model's material is worth, summing the
 * bed at {@code entryMs} cannot put a level step at either seam of the passage. A bed that is zero
 * at both ends is a bed whose level problem is designed out rather than fought: the seams are the
 * only places the cloud's own level could be heard at all.
 *
 * <p><b>The bed exists because the model's passage cannot be the passage.</b> Measured on real
 * material: the model's output opens 23–56 dB below the outgoing track, it cannot be repaired with
 * gain because the server peak-normalises to −1 dBFS so the passage gets whatever it gets, and its
 * crest factor is about 20 dB against the songs' 10–12. Under the project's own mixed passage the
 * same material is an asset: its sparseness is the texture, and the level it sits at is one this
 * class chooses rather than one the cloud chose.
 *
 * <p><b>RMS, not peak.</b> The normalisation is by the held part's own RMS, because of that crest
 * factor: peak-normalising would leave the bed ducking in and out as its few events came and went.
 * And the material has to have a <i>body</i> at all, which is two questions and not one — the same
 * {@link #ENVELOPE_MS} ms RMS envelope the model's failures are measured with refuses material
 * that is flat, under {@link #MIN_BED_SPREAD_DB} of peak-to-trough range (a −55 dBFS wash measured
 * 6.8 dB on a real run), and material that is mostly empty, under {@link #MIN_BED_OCCUPANCY} of its
 * frames near its own body level (the model's material a listener heard and called "only a few drum
 * hits, a bit of a cop-out" measured 26.9 dB of range and 52.3% occupancy, where real music is
 * 84–100%). Peak-to-trough range alone cannot separate those two: a handful of hits over
 * near-silence <i>maximises</i> it, so the flat floor has to be low and the occupancy does the work.
 *
 * <p>What the bed cannot do is sit above full scale: past the normalisation it is bounded by the
 * fusion's own limiter ({@link DjEdit.Limiter} — 1 ms attack, 150 ms release, no makeup gain) and
 * the clamp that limiter feeds, so a peakier-than-designed bed is held rather than refused, and
 * both counts are stated in the note the caller logs.
 *
 * <p>Pure arithmetic on samples: no platform, no files, no model — so "is this bed usable" is a
 * unit test, and the numbers the caller logs are the same numbers the tests assert.
 */
public final class StemBed {

    /** The bed sits this far under the passage it goes under, dB.
     *
     *  <p>18 was the first value, "to be tuned by ear". It was, and the ear said 12: the user asked
     *  for exactly that in the round that made the bed more obvious (「智能过渡请继续让 acestep 生成的过渡段
     *  更明显」), and that change was lost when the rounds around it were rolled back — so it is here
     *  again. 12 is the measured pair: {@code AceStepBed.DEFAULT_UNDER_DB} and its ceiling both read
     *  this, and 12 dB under a passage is a layer you can hear doing its job instead of one you have to
     *  be told about. */
    public static final double BED_LEVEL_DB = 12.0d;

    /** A bed whose envelope is flatter than this is a drone, not texture, and is refused, dB of
     *  peak-to-trough range over {@link #ENVELOPE_MS} ms frames. It was 12 until a listener heard
     *  what 12 let through — of the material this clause accepted, with 29 dB of range, they said
     *  it was "only a few drum hits, a bit of a cop-out" — and the reason a high value is backwards
     *  here is that range alone cannot tell a bed from a scattering of hits: a few loud hits over
     *  near-silence <i>maximise</i> peak-to-trough range, so a strict floor rewards exactly the
     *  sparseness {@link #MIN_BED_OCCUPANCY} exists to refuse. What is left is the one job this
     *  clause is good at, refusing a flat drone: the real failure was a flat −55 dBFS wash, and it
     *  measured 6.8 dB of range (its synthetic stand-in measures about 2 dB).
     *
     *  <p>⚠️ The real wash's 6.8 dB and this 6 dB floor are 0.8 dB apart, and the occupancy clause
     *  cannot back this one up — a flat wash is 100% occupied, so nothing else refuses it. The two
     *  clauses are not each other's backup: occupancy catches sparseness, spread catches flatness. */
    public static final double MIN_BED_SPREAD_DB = 6.0d;

    /** The share of {@link #ENVELOPE_MS} frames that must be no more than {@link
     *  #OCCUPANCY_BAND_DB} dB <i>under</i> the material's own body level, or the material is
     *  refused as a few hits rather than a bed. <b>This is the clause that catches sparseness</b>,
     *  and it is here because the spread floor cannot: it was a listener hearing what 12 dB of
     *  peak-to-trough range let through — material that was 26.9 dB of range over only 52.3%
     *  occupied frames, "only a few drum hits, a bit of a cop-out" — that showed range is the wrong
     *  reading, since the sparsest material measures the widest range of all.
     *
     *  <p>Measured on 250 ms RMS frames; <i>occupied</i> is the share of frames within {@link
     *  #OCCUPANCY_BAND_DB} dB of the material's own body level, over the span the bed is made of,
     *  all channels together:
     *
     *  <table border="1">
     *    <caption>the material this floor is set from</caption>
     *    <tr><th>material</th><th>occupied</th><th>peak-to-trough</th></tr>
     *    <tr><td>A coldplay, 60 s of the real song</td><td>100.0%</td><td>—</td></tr>
     *    <tr><td>B unhappy, 60 s of the real song</td><td>92.8%</td><td>—</td></tr>
     *    <tr><td>the local fusion's own passage (2.1 s)</td><td>100.0%</td><td>—</td></tr>
     *    <tr><td>A's own tail in the same buffer (real music)</td><td>84.4%</td><td>24.8 dB</td></tr>
     *    <tr><td>model output, an 8 s window</td><td>52.3%</td><td>26.9 dB</td></tr>
     *    <tr><td>the same run's 8 s window, other take</td><td>75.0%</td><td>38.7 dB</td></tr>
     *    <tr><td>model output, a 4 s window (the one dense run)</td><td>93.8%</td><td>12.8 dB</td></tr>
     *    <tr><td>model output, a 2.124 s window</td><td>100% but body level −64.4 dBFS</td>
     *        <td>1.4 dB</td></tr>
     *  </table>
     *
     *  <p>0.80 sits between the nearest accepted material (84.4%, real music's own tail) and the
     *  nearest refused (75.0%), and both of those are high-range material — which is the point: the
     *  spread reading does not separate them, this one does. The last row is the other failure and
     *  is not this clause's to catch: a flat window is 100% occupied, and {@link
     *  #MIN_BED_SPREAD_DB} is what refuses it. */
    public static final double MIN_BED_OCCUPANCY = 0.80d;

    /** How far under the material's own body level a frame may sit and still count as occupied,
     *  dB. Read <b>one-sided on purpose</b>: the material <i>above</i> the body is the events the
     *  bed is made of, and it is the frames <i>below</i> it that are the material's absence. A
     *  two-sided band would count a dense passage's own loud frames as unoccupied, and would refuse
     *  every material whose peak stands far over its RMS — which is exactly the material the
     *  limiter behind this class exists for, and the case
     *  {@code aBedThatWouldClipIsLimitedRatherThanRefused} covers: that material is a 3 ms burst
     *  over a floor, so it stands about 35 dB over its own RMS and its event frames are far above
     *  its body, and it has to read as dense (it is) rather than as sparse. */
    public static final double OCCUPANCY_BAND_DB = 6.0d;

    /** The material's own body level is the median of the {@link #ENVELOPE_MS} ms frames no more
     *  than this far under the loudest frame, dB: the level the material actually holds at, rather
     *  than the level of its loudest hit. A window and not every frame, because a floor further
     *  under the hits than this reads as <i>fully occupied</i> with no window at all: the floor is
     *  most of the frames, so the median lands in it, and every hit above it then counts as the
     *  material being there. Frames past the window are not part of the body at all — what is left
     *  is the material around its loudest moments, which is what a body is. */
    public static final double BODY_WINDOW_DB = 30.0d;

    /** The fade-in and fade-out at each end <b>wanted</b>, in bars of the incoming track's own
     *  grid. Wanted, not guaranteed: see {@link #MIN_FADE_MS}.
     *
     *  <p>Ⓐ Round 30: <b>two</b> bars, and it is the listener's own report that moved it. The
     *  envelope has been at both ends since this class existed (see the class's own note: the bed
     *  is zero at its first and last sample by construction), but at one bar of the incoming
     *  track's grid — 1.6 s on the pair that was listened to, at 149 BPM — a percussive bed's
     *  ending still arrives as an ending rather than as a leave-taking: 「我说的是 ai 垫层的收尾要
     *  淡出，最好再有个淡入」. Two bars is 3.2 s on the same grid, which is a musical phrase rather
     *  than a fade-out effect, and it is the same envelope at the start, so the bed now also
     *  arrives over two bars instead of one. The {@code frames / 3} cap below is unchanged, so a
     *  passage too short for two bars at each end still keeps a hold to normalise over and still
     *  falls back to the shorter fade rather than being refused. */
    public static final int BED_FADE_BARS = 2;

    /** The shortest fade that is still a fade and not a click, ms. The wanted
     *  {@link #BED_FADE_BARS} is capped so the window keeps a hold for the RMS to be measured
     *  over, and a window too short for even this much fade at each end is refused. */
    public static final double MIN_FADE_MS = 50.0d;

    /** The frame the material's own envelope is measured in, ms: the reading both {@link
     *  #MIN_BED_SPREAD_DB} and {@link #MIN_BED_OCCUPANCY} are judged on — the same 250 ms RMS
     *  envelope the model's level failures were measured with, not the 25 ms one
     *  {@link StemBridge#frameLevelsDb} answers in. */
    public static final long ENVELOPE_MS = 250L;

    /** How many channels the bed has — the edit file's own two. */
    static final int CHANNELS = 2;

    private StemBed() {
    }

    /** One prepared bed: the material to sum into the edit file, or the reason there is none. */
    public static final class Bed {
        /** The bed, {@code [1][2][frames]} — ONE row, two channels, exactly the window's frames —
         *  or null when {@link #usable} is false. */
        public float[][][] pcm;
        /** True when {@link #pcm} is there and the caller may sum it. */
        public boolean usable;
        /** The human-readable line for the caller's log: either the numbers the bed was made with
         *  (the envelope spread, the occupancy and the gain applied) or the clause that refused
         *  it. */
        public String note = "";
        /** Peak-to-trough range of the material's {@link #ENVELOPE_MS} ms RMS envelope, dB. */
        public double spreadDb;
        /** The material's own body level, dBFS: the median of those frames no more than {@link
         *  #BODY_WINDOW_DB} dB under the loudest of them — the level the material holds at, which
         *  is what the occupancy is measured against. */
        public double bodyDb;
        /** The share of those frames no more than {@link #OCCUPANCY_BAND_DB} dB under
         *  {@link #bodyDb}: 1.0 is a bed, about 0.5 is a few hits over near-silence. */
        public double occupancy;
        /** The gain the material was scaled by to reach the target level, dB. */
        public double gainDb;
        /** The material's held part as it measured before the gain, dBFS. */
        public double materialDb;
        /** The bed's held part as measured back out of {@link #pcm}, dBFS. The target is
         *  {@code levelDb - BED_LEVEL_DB}; the limiter is the only thing that can move it. */
        public double bedLevelDb;
        /** The bed's own peak after normalising and before the limiter: above 1.0 is "the limiter
         *  had to work", and it is the peak the limiter was engaged on. */
        public double unboundedPeak;
        /** True when the limiter was engaged (the bed's own peak was past full scale). */
        public boolean limited;
        /** How many (channel, sample) pairs the clamp behind the limiter had to touch. */
        public int clippedPairs;
        /** How many frames the bed holds. */
        public int frames;
        /** How many frames each fade is — one bar of the incoming's grid, in this rate's frames. */
        public int fadeFrames;
    }

    /**
     * Prepare one bed from the model's material.
     *
     * <p>The material is the generated passage at the track's own rate with the server's
     * normalisation already undone by the caller, so its level is arbitrary and this method's job is
     * to make that irrelevant: the held part comes out at {@code levelDb - }{@link #BED_LEVEL_DB}
     * and the ends come out at exactly zero.
     *
     * @param material {@code [channels][frames]} of the generated passage; the first two rows are
     *                 the bed's two channels (a mono input is used for both), anything past them is
     *                 ignored
     * @param rate     the sample rate of {@code material}, which the bed is written at
     * @param windowMs the passage window the bed has to fill, ms
     * @param barMs    one bar of the incoming track's own grid, ms — one of these is the fade at
     *                 each end
     * @param levelDb  the level of the passage the bed will go under, dBFS; the bed's held part
     *                 lands {@link #BED_LEVEL_DB} under it
     * @return the bed, or the refusal — never null, and check {@link Bed#usable}
     */
    public static Bed prepare(float[][] material, int rate, long windowMs, long barMs,
                              double levelDb) {
        Bed bed = new Bed();
        if (rate <= 0 || windowMs <= 0L) {
            return refuse(bed, "a " + windowMs + " ms window at " + rate);
        }
        int frames = (int) Math.round(rate * (windowMs / 1000d));
        if (frames <= 0) return refuse(bed, "a " + windowMs + " ms window at " + rate);
        if (material == null || material.length == 0 || material[0] == null) {
            return refuse(bed, "empty material");
        }
        int available = -1;
        for (float[] row : material) {
            if (row == null) return refuse(bed, "empty material");
            available = available < 0 ? row.length : Math.min(available, row.length);
        }
        if (available < frames) {
            return refuse(bed, String.format(Locale.US, "the material is %d frames, shorter than"
                    + " the %d the %d ms window needs", available, frames, windowMs));
        }

        // The two fades, in this rate's frames: BED_FADE_BARS bars of the incoming's own grid at
        // each end of the window, CAPPED at a third of the window so a hold is always left for the
        // RMS to be measured over.
        //
        // ⚠️ The cap is not hypothetical, it is the common case. Measured on a real slam pair
        // (coldplay -> unhappy): the window is 2124 ms against a 2043.94 ms bar, so a bar of fade
        // at each end wants 4088 ms of a 2124 ms window. Without the cap every slam would be
        // refused -- and slam is most of this library (532 of the reachable pairs). What the
        // design needs is not literally "one bar", it is "exactly zero at both ends with a hold
        // between", and a shortened fade keeps that exactly.
        if (barMs <= 0L) return refuse(bed, "a " + barMs + " ms bar gives no fade to hang the bed on");
        int wanted = (int) Math.round(rate * (BED_FADE_BARS * (double) barMs) / 1000d);
        if (wanted <= 0) return refuse(bed, "a " + barMs + " ms bar leaves no fade");
        int fadeFrames = Math.min(wanted, frames / 3);
        if (fadeFrames < Math.max(1, (int) Math.round(rate * (MIN_FADE_MS / 1000d)))) {
            return refuse(bed, String.format(Locale.US, "the %d ms window is too short for even a"
                    + " %.0f ms fade at each end with a hold between them", windowMs, MIN_FADE_MS));
        }
        bed.frames = frames;
        bed.fadeFrames = fadeFrames;

        // The first two rows are the bed's channels (a mono input feeds both).
        float[][] rows = new float[CHANNELS][];
        for (int ch = 0; ch < CHANNELS; ch++) {
            rows[ch] = material[Math.min(ch, material.length - 1)];
        }
        boolean any = false;
        for (float[] row : rows) {
            for (int i = 0; i < frames && !any; i++) any = row[i] != 0f;
            if (any) break;
        }
        if (!any) return refuse(bed, "the material is all zero");

        // Is it texture or a wash? Measured over the span the bed is actually made of, in
        // ENVELOPE_MS frames — the same reading the model's own failures were measured with.
        //
        // TWO clauses, because there are two opposite failures and one reading cannot catch both.
        // Flat is a drone — near-constant noise, which is what the spread floor is for. But a few
        // loud hits over near-silence MAXIMISE peak-to-trough range, so the spread floor is the one
        // reading a scattering of hits passes most easily: that is the material the listener heard
        // as "only a few drum hits, a bit of a cop-out", and the occupancy is what refuses it. The
        // spread clause stays first only so a window too short to hold one frame is refused as it
        // always was, rather than by whichever clause got there first.
        double[] levels = envelopeDb(rows, rate, frames);
        double loudest = -240d;
        double quietest = -240d;
        for (int f = 0; f < levels.length; f++) {
            if (f == 0 || levels[f] > loudest) loudest = levels[f];
            if (f == 0 || levels[f] < quietest) quietest = levels[f];
        }
        bed.spreadDb = levels.length == 0 ? 0d : loudest - quietest;
        if (bed.spreadDb < MIN_BED_SPREAD_DB) {
            return refuse(bed, String.format(Locale.US, "the envelope spread is %.1f dB over %d x"
                            + " %d ms frames, under the %.0f dB floor — a drone, not texture",
                    bed.spreadDb, levels.length, ENVELOPE_MS, MIN_BED_SPREAD_DB));
        }

        // The same frames, against the material's own body level: how much of the span the bed is
        // made of has material in it at all, rather than a few hits with near-silence between them.
        bed.bodyDb = levels.length == 0 ? 0d : bodyDb(levels);
        int occupied = 0;
        for (int f = 0; f < levels.length; f++) {
            if (levels[f] >= bed.bodyDb - OCCUPANCY_BAND_DB) occupied++;
        }
        bed.occupancy = levels.length == 0 ? 0d : occupied / (double) levels.length;
        if (bed.occupancy < MIN_BED_OCCUPANCY) {
            return refuse(bed, String.format(Locale.US, "the occupancy is %.1f%% of the %d x %d ms"
                            + " frames no more than %.0f dB under the %.1f dBFS body level, under"
                            + " the %.0f%% floor — a few hits over near-silence, not a bed (the"
                            + " envelope spread is %.1f dB, over the %.0f dB floor, so the spread"
                            + " clause alone would have accepted it)",
                    bed.occupancy * 100d, levels.length, ENVELOPE_MS, OCCUPANCY_BAND_DB, bed.bodyDb,
                    MIN_BED_OCCUPANCY * 100d, bed.spreadDb, MIN_BED_SPREAD_DB));
        }

        // The gain, from the held part alone: the fades are at 0 and 1 of the envelope, so what the
        // passage's own level is compared against is the hold.
        int held = frames - 2 * fadeFrames;
        double energy = 0d;
        for (float[] row : rows) {
            for (int i = fadeFrames; i < fadeFrames + held; i++) energy += row[i] * (double) row[i];
        }
        double heldRms = Math.sqrt(energy / (held * (double) rows.length));
        bed.materialDb = DjEdit.db(heldRms);
        if (!(heldRms > 0d)) return refuse(bed, "the held part is silent (nothing to normalise)");
        double targetDb = levelDb - BED_LEVEL_DB;
        double gain = StemFusion.linear(targetDb) / heldRms;
        bed.gainDb = DjEdit.db(gain);

        // Normalise, then fade — the envelope is what makes both seams zero, and it is applied here
        // because the caller has no reason to know the shape.
        float[][] scaled = new float[CHANNELS][frames];
        double peak = 0d;
        for (int ch = 0; ch < CHANNELS; ch++) {
            for (int i = 0; i < frames; i++) {
                double v = gain * rows[ch][i] * envelope(i, frames, fadeFrames);
                scaled[ch][i] = (float) v;
                peak = Math.max(peak, Math.abs(v));
            }
        }
        bed.unboundedPeak = peak;

        // Over full scale, the fusion's own limiter rather than a divisor or a second limiter: it
        // takes the peaks without pulling the bed's own body down with them. Anything the 1 ms
        // attack cannot catch is hard-clamped and counted, exactly as DjEdit.renderHead does.
        DjEdit.Limiter limiter = peak > DjEdit.Limiter.CEILING ? new DjEdit.Limiter(rate) : null;
        float[][][] pcm = new float[1][CHANNELS][frames];
        int clipped = 0;
        for (int i = 0; i < frames; i++) {
            double framePeak = 0d;
            for (int ch = 0; ch < CHANNELS; ch++) {
                framePeak = Math.max(framePeak, Math.abs(scaled[ch][i]));
            }
            double g = limiter == null ? 1d : limiter.gainFor(framePeak);
            for (int ch = 0; ch < CHANNELS; ch++) {
                double v = scaled[ch][i] * g;
                if (v > DjEdit.Limiter.CEILING) {
                    v = DjEdit.Limiter.CEILING;
                    clipped++;
                } else if (v < -DjEdit.Limiter.CEILING) {
                    v = -DjEdit.Limiter.CEILING;
                    clipped++;
                }
                pcm[0][ch][i] = (float) v;
            }
        }
        bed.pcm = pcm;
        bed.limited = limiter != null;
        bed.clippedPairs = clipped;
        bed.bedLevelDb = heldDb(pcm, fadeFrames, held);
        bed.usable = true;
        bed.note = String.format(Locale.US,
                "cloud bed: %d frames (%.0f ms), %d frames of fade at each end (%.0f ms of a %.0f ms"
                        + " bar); the envelope spread is %.1f dB over %d x %d ms frames (the floor"
                        + " is %.0f); the occupancy is %.1f%% of them no more than %.0f dB under the"
                        + " %.1f dBFS body level (the floor is %.0f%%); normalised %+.1f dB — the"
                        + " material's held part measured %.1f dBFS and the bed sits at %.1f dBFS,"
                        + " %.0f dB under the passage%s",
                frames, (double) windowMs, fadeFrames, 1000d * fadeFrames / rate, (double) barMs,
                bed.spreadDb, levels.length, ENVELOPE_MS, MIN_BED_SPREAD_DB, bed.occupancy * 100d,
                OCCUPANCY_BAND_DB, bed.bodyDb, MIN_BED_OCCUPANCY * 100d, bed.gainDb,
                bed.materialDb, bed.bedLevelDb, BED_LEVEL_DB,
                limiter == null ? "" : String.format(Locale.US, "; its own peak %.2f was over full"
                                + " scale, so the limiter (attack %.0f ms, release %.0f ms, no"
                                + " makeup gain) held %d of %d frames, deepest %.2f dB below unity,"
                                + " and %d samples were clamped at full scale",
                        bed.unboundedPeak, DjEdit.Limiter.ATTACK_MS, DjEdit.Limiter.RELEASE_MS,
                        limiter.heldFrames, limiter.frames, limiter.deepestReductionDb(), clipped));
        return bed;
    }

    /**
     * The bed's own gain envelope at frame {@code i} of {@code frames}: <b>exactly 0 at both
     * ends</b>, rising over the first {@code fadeFrames} frames, 1 for the hold, and falling over
     * the last {@code fadeFrames}.
     *
     * <p>The rise is {@link StemGesture#rise} — the project's own shape — read from whichever end
     * the frame is nearest, which is what makes the two ends symmetric and the first and last
     * samples exactly zero ({@code sin(0)} is exactly 0, where the equal-power complement's
     * {@code cos(pi/2)} is not). Nothing sums against the bed's own fade, so which of the two
     * equal-power shapes it uses cannot be heard; that its ends are zero can.
     */
    static double envelope(int i, int frames, int fadeFrames) {
        if (fadeFrames <= 0 || i < 0 || i >= frames) return 0d;
        if (i < fadeFrames) return StemGesture.rise(0d, fadeFrames, i);
        int out = frames - 1 - i;
        if (out < fadeFrames) return StemGesture.rise(0d, fadeFrames, out);
        return 1d;
    }

    /** The RMS of a bed's held part (the stretch between the two fades), dBFS. */
    static double heldDb(float[][][] pcm, int fadeFrames, int held) {
        double energy = 0d;
        int samples = 0;
        for (float[][] row : pcm) {
            for (float[] ch : row) {
                for (int i = fadeFrames; i < fadeFrames + held && i < ch.length; i++) {
                    energy += ch[i] * (double) ch[i];
                    samples++;
                }
            }
        }
        return samples == 0 ? -240d : DjEdit.db(Math.sqrt(energy / samples));
    }

    /**
     * The material's RMS envelope, dBFS, in {@link #ENVELOPE_MS} frames — {@link
     * StemBridge#frameLevelsDb}'s reading at the coarser frame {@link #MIN_BED_SPREAD_DB} and
     * {@link #MIN_BED_OCCUPANCY} are both stated in, over the span the bed is made of and all of
     * its channels together.
     */
    private static double[] envelopeDb(float[][] pcm, int rate, int frames) {
        int frameLen = Math.max(1, (int) Math.round(rate * (ENVELOPE_MS / 1000d)));
        int count = frames / frameLen;
        double[] out = new double[count];
        for (int f = 0; f < count; f++) {
            double energy = 0d;
            for (float[] row : pcm) {
                for (int i = f * frameLen; i < (f + 1) * frameLen; i++) {
                    energy += row[i] * (double) row[i];
                }
            }
            out[f] = DjEdit.db(Math.sqrt(energy / (frameLen * (double) pcm.length)));
        }
        return out;
    }

    /**
     * The material's own body level, dBFS: the median of {@code levels}' members no more than {@link
     * #BODY_WINDOW_DB} dB under the loudest of them, so that one loud hit does not stand in for a
     * body the material does not have.
     *
     * <p>The median of an even count is the mean of its two middle values, which is what keeps a
     * material whose frames fall into two groups — the events, and the material between them —
     * measurable: with the count split evenly, the mean of the middle two is the one reading that
     * belongs to neither group alone, and a body level that landed on the events instead would
     * report the material between them as absent however continuous it is.
     */
    private static double bodyDb(double[] levels) {
        double loudest = -240d;
        for (int f = 0; f < levels.length; f++) {
            if (levels[f] > loudest) loudest = levels[f];
        }
        double[] body = new double[levels.length];
        int count = 0;
        for (int f = 0; f < levels.length; f++) {
            if (levels[f] >= loudest - BODY_WINDOW_DB) body[count++] = levels[f];
        }
        if (count == 0) return loudest;      // the loudest frame is always its own candidate
        Arrays.sort(body, 0, count);
        return count % 2 == 1
                ? body[count / 2]
                : 0.5d * (body[count / 2 - 1] + body[count / 2]);
    }

    /** No bed, and the clause that refused it — the one place a refusal is written. */
    private static Bed refuse(Bed bed, String why) {
        bed.pcm = null;
        bed.usable = false;
        bed.note = "cloud bed refused: " + why;
        return bed;
    }
}
