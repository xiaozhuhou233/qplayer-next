package dev.t1m3.qplayer.audio;

/**
 * The DJ edit made of ONE deck: the incoming track with its vocals taken out of the
 * blend window and handed back on a bar line.
 *
 * <p>This is the whole arithmetic of that edit, platform-free, so what a render will do
 * is checkable in plain Java — and so the two decisions that matter ("does the incoming
 * head sing at all", "where does the voice come back") are made from the same numbers a
 * test can call.
 *
 * <p>Why one deck instead of Folia's two-deck graph: the crossover still happens the way
 * it always did, as two players ramping against each other ({@link FadeCurve.DJ_BLEND}),
 * and the outgoing track is untouched (stripping it would mean switching its audio
 * source mid-playback, which is the mechanism behind the P0 and the replay incidents of
 * earlier rounds). So the only thing this edit changes is what the <em>incoming</em>
 * deck contributes: its backing, and then its vocals, from one file — which is what
 * lets the incoming player open a single source and never change it, before or after
 * the promotion.
 *
 * <p>The gesture does not reuse {@link StemGesture#incomingCurves}: those curves describe
 * a <em>takeover</em> (the incoming pad arrives early, its drums switch on the bar line,
 * its bass a bar later, its voice last). Here the opposite is wanted — the whole backing
 * from the very first sample of the blend, and only the voice held back — so the edit
 * has its own shape, built from the same primitives ({@link StemGesture#rise} over a
 * {@link StemGesture#CUT_SEC} ramp, and {@link StemGesture#barLines} for where it lands).
 */
public final class DjEdit {

    /** The ramp the vocals come back over. Folia's own vocal entrance width — long
     *  enough that the return is a lift rather than a click, short enough to read as
     *  the downbeat it ends on. */
    public static final double RETURN_RAMP_SEC = StemGesture.CUT_SEC;

    /** {@link #RETURN_RAMP_SEC} in the milliseconds the rest of the transition machinery
     *  works in. It is what the pitch rule subtracts from the removal window to find the
     *  beginning of the voice rather than the end of its ramp (see
     *  {@code MixNaturaliser.VOCAL_PITCH_MARGIN_MS} and the controller's pitch rule): the
     *  rule is about the voice <em>arriving</em>, which is when the gain leaves zero. */
    public static final long RETURN_RAMP_MS = Math.round(RETURN_RAMP_SEC * 1000d);

    /**
     * How long after the two-voice stretch is over the voice <em>starts</em> coming back: twice
     * {@link #RETURN_RAMP_SEC}, so the return ramp (half a second) plus half a second of the
     * incoming track's backing alone.
     *
     * <p><b>Round 20, and it is the user's own rule read on the right clock.</b> The rule has
     * always been 「过渡完再放人声」 — bring the vocals after the transition is over. Round 17
     * read "the transition" as <em>the whole blend</em>: the voice was at exactly zero for every
     * millisecond of it, and with a 17 s 过渡时长 that left the incoming track singing nothing
     * for 16.6 s and lifting back at 17.1 s — the listener's 「现在再第二首歌切割人声有点切太多了」.
     * The stretch the rule is about is the one in which two voices could <em>stack</em>, and
     * with the shipped DJ shape that is not the whole ramp: the outgoing track is 10 dB down
     * within the first three tenths of it and at {@link FadeCurve#INAUDIBLE_DB} by
     * {@link FadeCurve#outLeftAt}'s own answer — 75.2% of the ramp, measured on the shape rather
     * than assumed. Past that instant there is no second voice left to stack with, so past it
     * the incoming track may sing (and does, {@link #vocalOutMs} being where the window now
     * ends).
     *
     * <p>The margin is unchanged in shape and in value — one {@link #RETURN_RAMP_SEC} of ramp
     * plus the same half second of slack; only the instant it is added to moved, from "the end of
     * the blend" to "the instant the outgoing left the passage". It is still the smallest margin
     * that can promise the rule: the lift is one ramp long and may not start before the outgoing
     * is gone, so the return has to <em>finish</em> at or after
     * {@code vocalOut + RETURN_RAMP_SEC}, and half a second more is spent at zero so the promise
     * does not rest on a rounding.
     *
     * <p>Before round 17 the window ended <em>at</em> the blend's end and the ramp reached back
     * into its last half second, so the incoming track's voice rose through the end of the
     * blend — the 「人声混合得很乱」 the listener reported, with the outgoing track's own vocals
     * (which this app cannot remove) still at whatever the curve left them at. That stays
     * prevented by construction: the curve is at the inaudible floor before this window ends.
     *
     * <p>⚠️ Where the return actually lands is the renderer's answer: the first bar line of the
     * incoming track at or after {@code vocalOut + this margin}, so it is usually later than the
     * margin and never earlier. See {@code AndroidStemEditRenderer.editPlan}. */
    public static final double VOCAL_RETURN_MARGIN_SEC = 2d * RETURN_RAMP_SEC;

    /** {@link #VOCAL_RETURN_MARGIN_SEC} in milliseconds. */
    public static final long VOCAL_RETURN_MARGIN_MS = Math.round(VOCAL_RETURN_MARGIN_SEC * 1000d);

    /**
     * <b>How much of a blend of {@code blendMs} the outgoing track can still be heard in</b> —
     * the window the incoming track's voice is held out of its rendered file for, in
     * milliseconds of the blend's own ramp.
     *
     * <p>This is the instant the whole vocal window is anchored on, and it is the shape's own
     * answer ({@link FadeCurve#outLeftAt}) rather than a constant, because the answer differs by
     * curve and being wrong is audible in both directions: too long and the incoming track is
     * silent long after the transition is over — the report this rule answers, where a 17 s
     * 过渡时长 put the voice out for 16.6 s and back at 17.1 s — and too short and the voice
     * returns while the outgoing track is still plainly audible underneath it.
     *
     * <p>The numbers it produces on the shipped shapes:
     * <ul>
     *   <li>{@link FadeCurve#DJ_BLEND} — every overlap of
     *       {@link TransitionPlan#OVERLAP_MEDIUM_MS} or more, i.e. every ordinary boundary at the
     *       default 过渡时长: <b>75.2%</b> of the blend. A 4 s blend therefore holds the voice out
     *       for 3.0 s, a 17 s one for 12.8 s and a 30 s one for 22.6 s, and the return ramp then
     *       lands on the first bar line at or after {@code out + VOCAL_RETURN_MARGIN_MS};</li>
     *   <li>{@link FadeCurve#LINEAR} / {@link FadeCurve#EQUAL_POWER}: <b>the whole blend</b>,
     *       to within the ramp's own last thousandth (15 ms of a 15 s blend) — those shapes hold
     *       the outgoing track at its own level until there, so a short blend on a symmetric curve
     *       keeps the window it had before this rule existed;</li>
     *   <li>{@link FadeCurve#FUSION}: {@code JUNCTION_XFADE_MS} of the ramp — but the fusion path
     *       does <b>not</b> use this number. Its file carries the outgoing track's own material
     *       through the whole passage and the passage is instrumental by design (that is the
     *       feature), so a fusion's gate keeps the window's own end; see
     *       {@code StemEditRenderer.Request.vocalOutMs} and {@code AndroidStemEditRenderer}'s
     *       fusion clause.</li>
     * </ul>
     */
    public static long vocalOutMs(long blendMs, FadeCurve curve) {
        if (blendMs <= 0L) return 0L;
        FadeCurve shape = curve != null ? curve : FadeCurve.DJ_BLEND;
        long out = Math.round(shape.outLeftAt(blendMs) * blendMs);
        return Math.max(0L, Math.min(blendMs, out));
    }

    /** A 25 ms frame of the vocal stem this far below full scale counts as "nothing
     *  singing there" (the same floor the round-7 stem measurements used). */
    public static final double SILENT_FRAME_DBFS = -50;

    /** The frame level that 90% of the removal window sits below. Reported as evidence
     *  (round 7 used the same shape of number); the verdict itself is
     *  {@link #SINGING_FRAME_SHARE}. */
    public static final double NO_VOCALS_P90_DBFS = -45;

    /** The share of the window's 25 ms frames that have to be above
     *  {@link #SILENT_FRAME_DBFS} for the window to count as singing.
     *
     * <p>Deliberately conservative at the low end and blind at the high end. A real
     * sung line — even a sparse rap or a sung intro — puts tens of percent of the
     * window's frames above the floor (round 7 measured 56% above it for a section that
     * had no lyrics at all), while a htdemucs vocal stem on an instrumental intro is
     * near-digital-silence and measures ~0%. A stray word or breath worth a few percent
     * of the window is treated as nothing singing: it would contribute almost nothing to
     * the blend, and the cost of being wrong is only that the incoming keeps its vocals —
     * which is exactly today's blend, not a broken one. */
    public static final double SINGING_FRAME_SHARE = 0.05;

    public static final double FRAME_SEC = 0.025;

    /** Beats to a bar for {@link StemGesture#barLines}. There is no structure pass on
     *  this platform (the platform has no such API), so four is the assumption every
     *  other bar-line use in this codebase makes as well. */
    public static final int BEATS_PER_BAR = 4;

    /**
     * When the vocals go back in: the vocal-out window, and the ramp that ends on the first bar
     * line after it.
     *
     * <p>{@code returnEnd} is at or after {@code window + }{@link #VOCAL_RETURN_MARGIN_SEC} and
     * {@code returnStart} follows from it, so the gain is exactly zero while the outgoing track
     * can still be heard and the lift happens entirely after that (round 20 — the window's own
     * end is {@link #vocalOutMs}, the shape's answer, not the blend's end; see the margin's own
     * note). The half second the ramp lasts is the "gradually approach the vocals" half of the
     * feature: the voice is lifted back to unity and lands on the bar line rather than being
     * switched on.
     */
    public static final class Plan {
        /** Where the vocals start coming back, seconds into the window — at or after the
         *  instant the outgoing track left the passage. */
        public final double returnStartSec;
        /** Where they are back at unity — a bar line of the incoming track, at or after
         *  that instant plus the margin. */
        public final double returnEndSec;
        /** True when {@link #returnEndSec} is an actual bar line rather than the plain
         *  end of the vocal-out window plus the margin (no grid, or no measurement). */
        public final boolean onBarLine;

        Plan(double returnStartSec, double returnEndSec, boolean onBarLine) {
            this.returnStartSec = returnStartSec;
            this.returnEndSec = returnEndSec;
            this.onBarLine = onBarLine;
        }

        /** The vocal gain at {@code t} seconds into the window: 0 while the outgoing can be
         *  heard, then the ramp, then 1 for the rest of the track. */
        public double vocalGainAt(double t) {
            if (t <= returnStartSec) return 0;
            if (t >= returnEndSec) return 1;
            return StemGesture.rise(returnStartSec, returnEndSec, t);
        }

        /** One line for the log: the window and when the voice comes back. */
        public String describe() {
            return String.format(java.util.Locale.US,
                    "vocals at exactly zero for the first %.2fs (the blend), then back over"
                            + " %.2fs ending at %.2fs (%s)",
                    returnStartSec, returnEndSec - returnStartSec, returnEndSec,
                    onBarLine ? "a bar line of the incoming track"
                            : "the gate's own instant, not a bar line (the window plus its"
                                    + " margin)");
        }
    }

    /**
     * The plan for one window.
     *
     * @param removalSec    how long the incoming track plays without its vocals — <b>the stretch
     *                      of the blend in which the outgoing track can still be heard</b>
     *                      ({@link #vocalOutMs} of the boundary's blend length, see round 20's
     *                      note on the margin: this is not the blend's own length any more, it is
     *                      the part of it that still carries a second voice)
     * @param returnEndSec  where the vocals are back at unity, in seconds into the
     *                      window: the first bar line at or after
     *                      {@code removalSec + }{@link #VOCAL_RETURN_MARGIN_SEC} when the
     *                      incoming track's grid is known, otherwise that same instant
     *                      itself. Anything EARLIER than the margin is ignored: a return
     *                      inside the two-voice stretch, or in its last instant, is exactly
     *                      what this floor exists to stop — see {@link #firstBarAtOrAfter}.
     */
    public static Plan plan(double removalSec, double returnEndSec) {
        double removal = Math.max(0.05, removalSec);
        double earliest = removal + VOCAL_RETURN_MARGIN_SEC;
        double end = returnEndSec;
        boolean onBar = true;
        if (!(end >= earliest) || Double.isNaN(end)) {
            end = earliest;
            onBar = false;
        }
        return new Plan(end - RETURN_RAMP_SEC, end, onBar);
    }

    /**
     * The plan for a window whose vocals come back at a <b>measured</b> instant — the fusion's own
     * gate (round 25), where the instant is the outgoing track's own departure inside the passage
     * ({@code StemFusion.Plan.coupling.voiceGateMs}) rather than a stretch read off a shape.
     *
     * <p>⚠️ <b>Why the margin is not applied here.</b> {@link #plan}'s floor exists because the plain
     * path's reference is a <em>shape</em>: the stretch in which the outgoing track can still be
     * heard is an estimate, and a lift that ends inside it would stack two voices. On the fusion path
     * the instant is measured off the outgoing's own rows — the user's own 「当过渡的混音效果逐渐减弱…
     * 时，就接入歌词」 — and since round 30 it is that measured instant <em>led</em> by one bar of the
     * outgoing's grid and floored where the file's own gesture has taken the outgoing's carried rows
     * {@code StemFusion.VOICE_GATE_FLOOR_DB} under unity (or, when the material itself falls that far,
     * where the material did): the listener's own answer to round 25's build was 「歌词要稍微早一点」,
     * and the bound is what keeps the earlier voice from landing while the outgoing is still at the
     * level they were on. See {@code StemFusion.VOICE_GATE_LEAD_BARS} and {@code StemFusion.entry}.
     *
     * @param returnEndSec  where the vocals are back at unity, seconds into the window
     * @param onBarLine     whether that instant is a bar line of the incoming track (reported only)
     */
    public static Plan planAt(double returnEndSec, boolean onBarLine) {
        double end = Math.max(0.05, returnEndSec);
        return new Plan(Math.max(0d, end - RETURN_RAMP_SEC), end, onBarLine);
    }

    /**
     * The first bar line at or after {@code atSec}, given the lines {@link
     * StemGesture#barLines} produced for the window, or NaN when there is none.
     *
     * <p>Bar lines come from a downbeat offset plus {@code beatsPerBar} beats, and never
     * from the beat grid's own phase: our {@link BeatProfile} carries the phase of the
     * beat grid ("every beat is firstBeatMs + k * period"), which is a different
     * question — a bar line spaced a bar apart from the beat phase is wrong three times
     * in four (see {@link StemGesture#barLines}). The offset itself is estimated from the
     * separated bass stem's low end ({@link StemGesture#downbeatOffsetSec}), which is the
     * documented extension this platform has instead of a structure pass.
     */
    public static double firstBarAtOrAfter(double[] barLines, double atSec) {
        if (barLines == null) return Double.NaN;
        double best = Double.NaN;
        for (double at : barLines) {
            if (at >= atSec && (Double.isNaN(best) || at < best)) best = at;
        }
        return best;
    }

    // --- does the incoming head sing at all? --------------------------------

    /** What the vocal stem measures over a window, and the verdict taken from it. */
    public static final class Presence {
        /** Frame levels, dBFS, of the two-channel vocal stem. */
        public final double medianDb;
        public final double p90Db;
        public final double peakDb;
        /** Share of frames at or below {@link #SILENT_FRAME_DBFS}. */
        public final double silentShare;
        /** The verdict: the window has real singing in it. */
        public final boolean sings;
        /** How many 25 ms frames the numbers came from. */
        public final int frames;

        Presence(double medianDb, double p90Db, double peakDb, double silentShare,
                 boolean sings, int frames) {
            this.medianDb = medianDb;
            this.p90Db = p90Db;
            this.peakDb = peakDb;
            this.silentShare = silentShare;
            this.sings = sings;
            this.frames = frames;
        }

        /** The evidence, for the log line that records the decision. */
        public String describe() {
            return String.format(java.util.Locale.US,
                    "the vocal stem of the first window measures median %.1f, p90 %.1f, "
                            + "peak %.1f dBFS (%d frames of %dms, %.0f%% at or below %.0f dBFS,"
                            + " %.0f%% above it)",
                    medianDb, p90Db, peakDb, frames, (int) (FRAME_SEC * 1000),
                    silentShare * 100.0, SILENT_FRAME_DBFS, (1 - silentShare) * 100.0);
        }
    }

    /**
     * Measures a separated vocal stem over the first {@code windowSec} seconds.
     *
     * <p>The verdict is the share of frames above {@link #SILENT_FRAME_DBFS} — "how much
     * of this window has voice in it" — rather than a mean or a peak: the mean averages a
     * mostly instrumental window's one word away, and the peak would give the whole
     * window to a single click. See {@link #SINGING_FRAME_SHARE} for why the bar is where
     * it is.
     */
    public static Presence presence(float[][] vocals, int sampleRate, double windowSec) {
        double[] levels = frameLevelsDb(vocals, sampleRate, windowSec);
        if (levels.length == 0) {
            // Nothing to measure: no claim of singing, and the caller skips the stem
            // path — which is the safe direction (today's blend, no edit).
            return new Presence(-240, -240, -240, 1, false, 0);
        }
        double[] sorted = levels.clone();
        java.util.Arrays.sort(sorted);
        double peak = 0;
        int limit = (int) Math.round(sampleRate * Math.max(0, windowSec));
        for (int ch = 0; ch < vocals.length; ch++) {
            int end = Math.min(limit, vocals[ch].length);
            for (int i = 0; i < end; i++) {
                double a = Math.abs(vocals[ch][i]);
                if (a > peak) peak = a;
            }
        }
        int silent = 0;
        for (double level : levels) if (level <= SILENT_FRAME_DBFS) silent++;
        double silentShare = silent / (double) levels.length;
        return new Presence(percentile(sorted, 0.50), percentile(sorted, 0.90), db(peak),
                silentShare, (1 - silentShare) >= SINGING_FRAME_SHARE, levels.length);
    }

    /** Per-frame level of a stereo signal, dBFS, over the first {@code windowSec}. */
    static double[] frameLevelsDb(float[][] pcm, int sampleRate, double windowSec) {
        if (pcm == null || pcm.length == 0 || pcm[0] == null) return new double[0];
        int frameLen = Math.max(1, (int) Math.round(sampleRate * FRAME_SEC));
        int total = Math.min(Math.min(pcm[0].length, pcm.length > 1 ? pcm[1].length : pcm[0].length),
                (int) Math.round(sampleRate * Math.max(0, windowSec)));
        int count = total / frameLen;
        double[] out = new double[count];
        for (int f = 0; f < count; f++) {
            double energy = 0;
            for (int ch = 0; ch < pcm.length; ch++) {
                float[] c = pcm[ch];
                for (int i = f * frameLen; i < (f + 1) * frameLen; i++) {
                    energy += c[i] * c[i];
                }
            }
            double rms = Math.sqrt(energy / (frameLen * (double) pcm.length));
            out[f] = db(rms);
        }
        return out;
    }

    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return -240;
        int index = Math.min(sorted.length - 1,
                (int) Math.round(p * (sorted.length - 1)));
        return sorted[Math.max(0, index)];
    }

    static double db(double ratio) {
        return ratio > 1e-12 ? 20 * Math.log10(ratio) : -240;
    }

    // --- the render ---------------------------------------------------------

    /**
     * Renders the head of the incoming track: every stem at unity except the vocals,
     * which follow {@link Plan#vocalGainAt}.
     *
     * <p>No bus limiter, deliberately. {@link StemBlendRenderer#softLimit} exists because
     * two decks sum at one bus and two masters can exceed full scale together; here there
     * is one deck and the edit has to be the master — a limiter would shape the very
     * signal the "then the master continues" half of this feature promises to leave
     * alone. What replaces it is the hard clamp any 16-bit encoder needs, counted so the
     * log can say whether it ever fired.
     *
     * @param stems     the separated window, {@code [stemRow][channel][sample]}
     *                  ({@link StemGesture.Stem#row}), at {@code sampleRate}
     * @param clipped   single-element out: how many samples the clamp had to touch
     * @return {@code [channel][sample]}, the same length as the input stems
     */
    public static float[][] renderHead(float[][][] stems, int sampleRate, double windowSec,
                                       Plan plan, int[] clipped) {
        return renderHead(stems, sampleRate, windowSec, plan, clipped, null, 0);
    }

    /**
     * The same render with a bridge in it: {@code layer} is added to the window starting at
     * {@code layerStartSample}, before the vocal gain is applied to anything else.
     *
     * <p>The layer is the material {@link StemBridge} carried forward from the outgoing track
     * — its bass, taken from the instant the low-end hand-over takes the outgoing deck's own
     * low end away, so the two never sum (see that class). It is added rather than mixed with
     * a curve of its own because it has one job: be the low end while the outgoing deck has
     * none. Its own length decides where it stops, and nothing past its end is touched.
     *
     * @param layer            {@code [channel][sample]} to add at the bridge's start, or null
     * @param layerStartSample where in the window to add it, samples
     */
    public static float[][] renderHead(float[][][] stems, int sampleRate, double windowSec,
                                       Plan plan, int[] clipped, float[][] layer,
                                       int layerStartSample) {
        return renderHead(stems, sampleRate, windowSec, plan, clipped, null, null, 0, null,
                layer, layerStartSample, null);
    }

    /** The gain of one stem row at one position in the edit's own timeline, seconds. */
    public interface RowGain {
        double gainOf(StemGesture.Stem row, double atSec);
    }

    /**
     * What the head had to clamp, over the part of it that matters.
     *
     * <p>The edit is the master, so nothing limits it (see {@link #renderHead}): samples past
     * full scale are hard-clamped, and the count says whether that ever fired. The window is for
     * the fusion, where two backings sum and "how much of the <em>passage</em> is at full scale"
     * is a different question from "how much of the whole head is" — a run of consecutive clamped
     * samples is a passage mixed too loud, a single one is a splice landing on a peak.
     */
    public static final class ClipGuard {
        /** First and one-past-last sample of the window being watched, in the head's own
         *  samples. */
        public final int fromFrame;
        public final int toFrame;
        /** How many (channel, sample) pairs inside the window the clamp touched. */
        public int clipped;
        /** The longest run of consecutive frames inside the window with a clamped sample. */
        public int longestRunFrames;
        /** How many sample pairs the window holds (channels × frames), for the share. */
        public int pairs;

        public ClipGuard(int fromFrame, int toFrame) {
            this.fromFrame = Math.max(0, fromFrame);
            this.toFrame = Math.max(this.fromFrame, toFrame);
        }

        /** The share of the window's sample pairs that were clamped. */
        public double share() {
            return pairs > 0 ? clipped / (double) pairs : 0d;
        }

        /** The longest clamped run, ms, at the rate the head was rendered at. */
        public double longestRunMs() {
            return longestRunFrames * 1000d / rate;
        }

        /** The run being counted at the frame currently under the loop. */
        private int run;
        private int rate = 1;
    }

    /**
     * The head's peak limiter — the other half of the fusion's junction (round 18).
     *
     * <p><b>Why a limiter and not a divisor.</b> The outgoing track's own master already peaks at
     * about full scale (measured: 1.00204 on one of the prototype's pairs), so <em>any</em> bed
     * added under it is over: the first prototype divided the whole head by the peak it found
     * (1.6878 on that pair, −4.55 dB), and that step — not the music — is what the junction
     * sounded like to the listener (measured −2.81 / −4.10 / −11.46 dB at 100 / 500 / 2000 ms).
     * A limiter takes the same peaks without pulling the music down with them: only the frames
     * that need holding are held, and the level the listener was already at is left alone.
     *
     * <p><b>The numbers.</b> Attack {@link #ATTACK_MS} (the gain can travel its whole range in
     * that time, so no frame waits longer than that for a reduction it needs), release
     * {@link #RELEASE_MS} (a one-pole back to unity, the slow direction), ceiling {@link #CEILING}
     * and <b>no makeup gain</b> — the gain is never above 1, so the limiter can only lower a
     * passage, never raise it. Both channels share one gain (a linked limiter), because a linked
     * gain is what keeps the image still.
     *
     * <p>What it does not do is promise anything about the <em>loudest sample</em>: the attack is a
     * 1 ms ramp, so a peak that arrives inside that ramp is still over full scale by a little, and
     * such samples are hard-clamped by {@link #renderHead} and <b>counted</b> — the "last resort"
     * of the acceptance. A passage that needs many of them is refused by {@link
     * StemFusion#measure}, not hidden by the limiter.
     */
    public static final class Limiter {
        /** How long the gain may take to reach a reduction it needs. */
        public static final double ATTACK_MS = 1d;
        /** The time constant of the gain's return to unity. */
        public static final double RELEASE_MS = 150d;
        /** The ceiling the head is held under: full scale, and there is no makeup gain. */
        public static final double CEILING = 1d;

        /** How much the gain may fall per frame to reach a reduction within {@link #ATTACK_MS}. */
        private final double attackStep;
        /** The one-pole's per-frame coefficient for the {@link #RELEASE_MS} return. */
        private final double releaseStep;
        private double gain = 1d;

        /** Frames the head was held below unity at all. */
        public int heldFrames;
        /** Frames the limiter passed, for the share. */
        public int frames;
        /** The deepest gain it went to — 1.0 when it never held anything. */
        public double deepestGain = 1d;

        public Limiter(int rate) {
            int attackFrames = Math.max(1, (int) Math.round(ATTACK_MS * Math.max(1, rate) / 1000d));
            attackStep = 1d / attackFrames;
            releaseStep = 1d - Math.exp(-1d / Math.max(1d, RELEASE_MS * rate / 1000d));
        }

        /**
         * The gain one frame is rendered at, from the peak of that frame's own channels.
         *
         * <p>A reduction is taken at up to {@link #ATTACK_MS}'s rate down and a return at
         * {@link #RELEASE_MS}'s; a frame that needs no reduction is answered with the gain the
         * release has climbed back to, never with more than unity.
         */
        double gainFor(double peak) {
            frames++;
            double desired = peak > CEILING ? CEILING / peak : 1d;
            if (desired < gain) {
                gain = Math.max(desired, gain - attackStep);
            } else {
                gain = Math.min(1d, gain + (desired - gain) * releaseStep);
            }
            if (gain < 1d) {
                heldFrames++;
                if (gain < deepestGain) deepestGain = gain;
            }
            return gain;
        }

        /** How far the deepest reduction sat below unity, dB (0 when nothing was held). */
        public double deepestReductionDb() {
            return deepestGain >= 1d ? 0d : -20d * Math.log10(deepestGain);
        }

        /** The evidence, for the log line that records the render. */
        public String describe() {
            return String.format(java.util.Locale.US,
                    "the head's peak limiter (attack %.0fms, release %.0fms, no makeup gain) held"
                            + " %d of %d frames, deepest %.2f dB below unity",
                    ATTACK_MS, RELEASE_MS, heldFrames, frames, deepestReductionDb());
        }
    }

    /**
     * The same render with the fusion's own gesture: per-row gains for the incoming's own rows,
     * and the outgoing's carried rows mixed in beside them.
     *
     * <p>This is the round-18 shape. {@code incomingGains} replaces the edit's default (unity
     * except the gate on the voice) with the fusion's table — the incoming's drums and low end
     * silent until their own bar lines, its melodic bed under them — and {@code carried} is the
     * outgoing track's material, placed at {@code carriedStartSample} and given its own table.
     * The voice rule survives untouched: the incoming's vocal row is still
     * {@link Plan#vocalGainAt}, and the carried material is never the outgoing's voice (the
     * caller does not carry it — see {@link StemFusion#carry}).
     *
     * <p>With both schedules null and an empty carry this is <em>exactly</em> the render above,
     * statement for statement, which is the point: the non-fusion edit is unchanged by the
     * fusion's existence.
     *
     * @param incomingGains     per-row gains for the incoming's own stems, or null for the
     *                          default (unity, with the gate on the voice)
     * @param carried           {@code [stemRow][channel][sample]} of the outgoing's material, or
     *                          null; rows that were not carried are empty
     * @param carriedStartSample where {@code carried} starts in the window, samples
     * @param carriedGains      per-row gains for {@code carried}, or null for unity
     * @param guard             counted clamps over a window of the render, or null
     */
    public static float[][] renderHead(float[][][] stems, int sampleRate, double windowSec,
                                       Plan plan, int[] clipped, RowGain incomingGains,
                                       float[][][] carried, int carriedStartSample,
                                       RowGain carriedGains, float[][] layer, int layerStartSample,
                                       ClipGuard guard) {
        return renderHead(stems, sampleRate, windowSec, plan, clipped, incomingGains, carried,
                carriedStartSample, carriedGains, layer, layerStartSample, guard, null);
    }

    /**
     * The same render with the fusion's own peak limiter over it: every frame is summed exactly as
     * above, the frame's own peak is handed to {@link Limiter#gainFor}, and the sum is scaled by
     * the gain that comes back <em>before</em> the clamp — so the clamp counts what is past full
     * scale after the limiter, which is the "last resort" the acceptance measures.
     *
     * <p>{@code limiter == null} is the whole-head render of every other path and is bit-for-bit
     * the statement above it (a gain of exactly 1 scales nothing).
     *
     * @param limiter the fusion's limiter, or null for no limiter at all
     */
    public static float[][] renderHead(float[][][] stems, int sampleRate, double windowSec,
                                       Plan plan, int[] clipped, RowGain incomingGains,
                                       float[][][] carried, int carriedStartSample,
                                       RowGain carriedGains, float[][] layer, int layerStartSample,
                                       ClipGuard guard, Limiter limiter) {
        int channels = stems[0].length;
        int length = stems[0][0].length;
        float[][] out = new float[channels][length];
        int clippedCount = 0;
        double[] sums = new double[channels];
        StemGesture.Stem[] all = StemGesture.Stem.ALL;
        int layerFrames = layer != null && layer.length > 0 && layer[0] != null
                ? layer[0].length : 0;
        if (guard != null) {
            guard.rate = sampleRate;
            guard.pairs = channels * Math.max(0, Math.min(guard.toFrame, length) - guard.fromFrame);
        }
        for (int i = 0; i < length; i++) {
            double t = i / (double) sampleRate;
            double vocalGain = plan.vocalGainAt(t);
            int layerAt = i - layerStartSample;
            boolean fromLayer = layerFrames > 0 && layerAt >= 0 && layerAt < layerFrames;
            boolean watched = guard != null && i >= guard.fromFrame && i < guard.toFrame;
            boolean thisFrameClamped = false;
            int carriedAt = i - carriedStartSample;
            double peak = 0d;
            for (int ch = 0; ch < channels; ch++) {
                double sum = 0;
                for (StemGesture.Stem stem : all) {
                    double gain = incomingGains != null
                            ? incomingGains.gainOf(stem, t)
                            : (stem == StemGesture.Stem.VOCALS ? vocalGain : 1.0);
                    if (gain != 0) sum += gain * stems[stem.row()][ch][i];
                }
                if (carried != null && carriedAt >= 0) {
                    for (StemGesture.Stem stem : all) {
                        int row = stem.row();
                        if (row >= carried.length || carried[row] == null
                                || carried[row].length == 0 || ch >= carried[row].length
                                || carriedAt >= carried[row][ch].length) {
                            continue;
                        }
                        double gain = carriedGains == null ? 1.0 : carriedGains.gainOf(stem, t);
                        if (gain != 0) sum += gain * carried[row][ch][carriedAt];
                    }
                }
                if (fromLayer && ch < layer.length && layer[ch] != null) {
                    sum += layer[ch][layerAt];
                }
                sums[ch] = sum;
                double magnitude = Math.abs(sum);
                if (magnitude > peak) peak = magnitude;
            }
            double limit = limiter == null ? 1d : limiter.gainFor(peak);
            for (int ch = 0; ch < channels; ch++) {
                double sum = sums[ch] * limit;
                if (sum > 1.0) {
                    sum = 1.0;
                    clippedCount++;
                    thisFrameClamped = true;
                } else if (sum < -1.0) {
                    sum = -1.0;
                    clippedCount++;
                    thisFrameClamped = true;
                }
                out[ch][i] = (float) sum;
            }
            if (watched) {
                if (thisFrameClamped) {
                    guard.clipped++;
                    guard.longestRunFrames = Math.max(guard.longestRunFrames, ++guard.run);
                } else {
                    guard.run = 0;
                }
            }
        }
        if (clipped != null && clipped.length > 0) clipped[0] = clippedCount;
        return out;
    }

    /** The vocal stem under the edit's own gain curve — what the voice actually is in
     *  the rendered head, and therefore what the log measures "after" against. */
    public static float[][] scaleVocals(float[][] vocals, int sampleRate, Plan plan,
                                        double windowSec) {
        int channels = vocals.length;
        int length = vocals[0].length;
        float[][] out = new float[channels][length];
        for (int i = 0; i < length; i++) {
            double gain = plan.vocalGainAt(i / (double) sampleRate);
            for (int ch = 0; ch < channels; ch++) out[ch][i] = (float) (gain * vocals[ch][i]);
        }
        return out;
    }

    /**
     * A slice of a signal, so a measurement can be taken of one part of a window — the
     * blend without its return ramp, the tail after it — rather than of the whole window
     * at once. Clamped to what the signal actually has; empty when the range is.
     */
    public static float[][] slice(float[][] pcm, int sampleRate, double fromSec, double toSec) {
        int channels = pcm.length;
        int from = Math.max(0, (int) Math.round(fromSec * sampleRate));
        int to = Math.max(from, (int) Math.round(toSec * sampleRate));
        float[][] out = new float[channels][];
        for (int ch = 0; ch < channels; ch++) {
            int end = Math.min(to, pcm[ch].length);
            out[ch] = end > from ? java.util.Arrays.copyOfRange(pcm[ch], from, end)
                    : new float[0];
        }
        return out;
    }

    private DjEdit() {}
}
