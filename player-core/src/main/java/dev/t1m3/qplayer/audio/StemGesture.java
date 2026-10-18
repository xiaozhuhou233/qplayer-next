package dev.t1m3.qplayer.audio;

import java.util.Arrays;

/**
 * Folia's stem gesture, ported as arithmetic: given two windows' envelopes and a bar
 * length it says when each stem changes hands and how the outgoing voice leaves.
 *
 * <p>Source: {@code folia/src/services/automix/stemGesture.ts} (round eleven). This is a
 * PORT, not a design. Every number here was settled there by blind listening tests over
 * eleven rounds and several of them are counter-intuitive enough that they were nearly
 * shipped the other way round; the reasoning is recorded beside each one so a later
 * reader does not "fix" a result. If any of it is ever revisited it needs a listening
 * test with a do-nothing control, not an argument.
 *
 * <p>Deliberately platform-free: no audio nodes, no clock, no Android. The envelopes are
 * {@code float[]}, the times are seconds relative to the window's start. That is what
 * makes the whole thing testable in plain Java — see {@code StemGestureTest}.
 *
 * <p>The six rules a later reader must not break, because they are what the listening
 * rounds settled (each one is explained where it lives):
 * <ol>
 *   <li>drums swap first over a {@value #SWAP_EDGE_SEC}s cut, snapped to a real bar line;
 *       bass follows one bar later; the incoming {@code other} arrives before its own
 *       drums ({@link #INCOMING_OTHER_LEAD_SEC});</li>
 *   <li>the vocal exit is a FREE search — never snapped — with three branches
 *       (rest / recede / release), and among qualifying rests it takes the LAST, not the
 *       quietest;</li>
 *   <li>a recede starts at the swap, not at the window's start;</li>
 *   <li>the two tracks' vocals must not stack: the exit is done {@value #CUT_SEC}s after
 *       the incoming voice arrives (Folia's {@code vocalGap} target is about -0.5s);</li>
 *   <li>every outgoing curve starts at unity, which is what lets the 8ms hand-back to
 *       the full master at the window's end be a splice rather than an edit;</li>
 *   <li>bar lines come from a <em>downbeat offset</em> plus {@code beatsPerBar}, never
 *       from the beat grid's phase. See {@link #barLines} — our profiler exposes the
 *       beat phase ({@code BeatProfile.firstBeatMs}), which is a different question.</li>
 * </ol>
 */
public final class StemGesture {

    private StemGesture() {}

    // --- the four stems, in the model's own row order -------------------------

    /** Stem identity, in the order the model's rows come back.
     *
     *  <p>The row order (drums, bass, other, vocals) was verified on the device rather than
     *  taken on trust: rows 0/1 by the kick band and the zero-crossing rate, rows 2/3 by
     *  which of them goes to digital silence through a passage with no singing (see
     *  AI_HANDOFF §7, Phase A). A wrong order would remove the wrong thing, so it is
     *  measured, not assumed. */
    public enum Stem {
        DRUMS(0), BASS(1), OTHER(2), VOCALS(3);

        private final int row;

        Stem(int row) {
            this.row = row;
        }

        /** Index of this stem in the model's output tensor. */
        public int row() {
            return row;
        }

        public static final Stem[] ALL = {DRUMS, BASS, OTHER, VOCALS};
    }

    // --- constants, each with the reason it has the value it has --------------

    /** Cell length the envelopes are measured at. 50ms, which is what the harness used. */
    public static final double CELL_SEC = 0.05;

    /**
     * How far below the window's own median level the outgoing voice has to get for its
     * exit to be a cut rather than a fade.
     *
     * <p>-30 dB, and the value has a history: an earlier round used -20, classified a
     * -21 dB moment as a rest, and the listener heard the cut anyway. Relative to the
     * MIX's median in that window rather than to full scale, because "quiet" only means
     * anything against what else is playing.
     */
    public static final double REST_DB = -30;

    /** How long the cut takes. Half a second reads as an edit; shorter reads as a dropout. */
    public static final double CUT_SEC = 0.5;

    /** The exit never starts at zero — the first moment of a window is where a splice lands. */
    public static final double EXIT_FLOOR_SEC = 0.05;

    /**
     * Shortest a recede may be squeezed to before it stops being one.
     *
     * <p>A second. Under it the outgoing voice does not recede, it disappears — which is a
     * cut, placed at a moment the rest search has already ruled out as too loud to cut in.
     */
    public static final double MIN_RECEDE_SEC = 1;

    /**
     * Quiet enough that a cut takes nothing with it, so it may be as fast as the harness's.
     *
     * <p>Fifteen dB below the threshold that admits a cut at all. Under this the vocal stem
     * is at the noise floor of the separation rather than at a quiet syllable, which is the
     * case the half-second cut was scored on — both rests measured in a real session sat at
     * -56 and -58 dB.
     */
    public static final double DEEP_REST_DB = -45;

    /** Longest a cut may stretch to when the rest it lands in is only just quiet enough. */
    public static final double SOFT_EXIT_SEC = 1.2;

    /**
     * Shortest a note has to be held before it counts as held rather than merely sung.
     *
     * <p>1.2 seconds. Under it this is a long syllable, and a long syllable is part of a
     * phrase — the phrase is what the rest search is already good at finding the end of.
     * Over it the voice has stopped delivering words and is doing the one thing a fade
     * cannot hide behind.
     */
    public static final double SUSTAIN_MIN_SEC = 1.2;

    /**
     * How far a held note may wander from its own peak and still be the same note.
     *
     * <p>Seven dB. Vibrato and a natural swell live well inside this; a syllable boundary
     * does not, which is what keeps an ordinary sung phrase from reading as a sustain. The
     * note ENDS where it leaves the band, and that moment is the singer letting go — the one
     * place an exit costs nothing.
     */
    public static final double SUSTAIN_FLAT_DB = 7;

    /**
     * How long a run of cells has to stay up before it counts as singing.
     *
     * <p>A quarter second. Separation leaks — a snare tail or a cymbal lands in the vocal
     * stem as a transient a cell or two long — and one loud cell is exactly what that looks
     * like. A sung syllable is longer than this; a leaked hit is not.
     */
    public static final int SUSTAIN_CELLS = 5;

    /**
     * How much past the last sounding cell the voice is called finished.
     *
     * <p>A held note falls under the threshold while it is still decaying, so the cell that
     * fails the test is not the end of the note. Three tenths, and the DIRECTION is the whole
     * point: every error this function can make has to land late. A vocal end reported late
     * puts a handover in the instrumental outro, which is dull; one reported early puts it on
     * top of a singer, which is the defect.
     */
    public static final double VOCAL_TAIL_SEC = 0.3;

    /**
     * Where the drums change hands, as a fraction of the window.
     *
     * <p>Snapped to a real bar line when one is in reach; this is only the target it reaches
     * towards.
     */
    public static final double SWAP_TARGET = 0.42;

    /**
     * How much blend may be left AFTER the low end has changed hands, in bars.
     *
     * <p>{@link #SWAP_TARGET} is a fraction and the gesture that follows it is a fixed size —
     * one bar to the bass, one to the incoming voice — so the part of the blend after the
     * handover grows with the window while the handover itself does not. Past that point the
     * outgoing track has given up its drums, bass and voice; what is left is residue, and
     * residue does not get better with time. Measured across one session: every blend that
     * sounded right left between 0.1 and 1.1 bars behind the handover, and the one that did
     * not left 3.6.
     *
     * <p>Two bars, so no blend that already worked moves by a sample. When the bound bites it
     * moves the whole gesture later rather than shortening the blend — a long overlap then
     * spends its extra time BEFORE the handover, with the outgoing track still leading and the
     * incoming one rising under it, which is what a long mix is supposed to be.
     */
    public static final double MAX_TAIL_BARS = 2;

    /** Room left after the last stem move so nothing is still changing as the window ends. */
    public static final double TAIL_GUARD_SEC = 0.3;

    /**
     * How fast a stem changes hands once its moment comes.
     *
     * <p>Six milliseconds — a swap, not a fade. The harness used exactly this and the effect
     * depends on it: a drum kit that fades over half a second is two drum kits for half a
     * second.
     */
    public static final double SWAP_EDGE_SEC = 0.006;

    /** Where the outgoing {@code other} stem's high-pass starts and ends, in Hz. */
    public static final double[] OTHER_SWEEP_HZ = {25, 2200};

    /** The fraction of the window the sweep runs over. Ends before the stem's own fade does. */
    public static final double OTHER_SWEEP_END = 0.92;

    /** Points per second in a sampled curve. 200 is well inside a millisecond of the ideal shape. */
    public static final int CURVE_RATE = 200;

    /**
     * How long before its own drums the incoming {@code other} bed arrives, in seconds.
     *
     * <p>Arrives BEFORE its own drums, so the incoming track is already present as atmosphere
     * when the beat changes hands. Round ten measured this the hard way: on the one pair where
     * the incoming track was made to crash in with its drums it scored 3.0, against 7.0 for
     * entering quietly.
     */
    public static final double INCOMING_OTHER_LEAD_SEC = 1.2;

    // --- measurements over an envelope ---------------------------------------

    /**
     * Per-cell RMS of one stem over a window.
     *
     * <p>Mono-summed: the question is how loud a stem is, and a voice panned off centre is not
     * quieter.
     *
     * @param channels one array per channel, all the same length
     * @param sampleRate samples per second
     * @param cellSec cell length; {@link #CELL_SEC} unless a test wants another
     */
    public static float[] envelopeOf(float[][] channels, int sampleRate, double cellSec) {
        int cell = Math.max(1, (int) Math.round(cellSec * sampleRate));
        int length = channels.length == 0 || channels[0] == null ? 0 : channels[0].length;
        int cells = length / cell;
        float[] out = new float[cells];
        for (int c = 0; c < cells; c++) {
            double sum = 0;
            for (int i = c * cell; i < (c + 1) * cell; i++) {
                double frame = 0;
                for (float[] channel : channels) frame += channel[i];
                frame /= channels.length;
                sum += frame * frame;
            }
            out[c] = (float) Math.sqrt(sum / cell);
        }
        return out;
    }

    /** The median of an envelope, without disturbing it. Zero for an empty one. */
    public static double median(float[] values) {
        if (values == null || values.length == 0) return 0;
        float[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length >> 1;
        return sorted.length % 2 == 1
                ? sorted[middle]
                : (sorted[middle - 1] + sorted[middle]) / 2d;
    }

    private static double db(double ratio) {
        return 20 * Math.log10(Math.max(ratio, 1e-12));
    }

    // --- the exit ------------------------------------------------------------

    /** A note the outgoing voice is holding: where it starts, where it lets go, how loud. */
    public static final class VocalSustain {
        /** Seconds into the window where the note is first held. */
        public final double from;
        /** Where it lets go — the first moment it has fallen out of its own band. */
        public final double to;
        /** How far the note sits above the mix's median level, in dB. */
        public final double holdDb;

        VocalSustain(double from, double to, double holdDb) {
            this.from = from;
            this.to = to;
            this.holdDb = holdDb;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "held %.2f-%.2fs (%.0fdB over the mix)", from, to, holdDb);
        }
    }

    /** Which branch the exit took. Reported so a log can say WHY a transition sounded as it did. */
    public enum ExitKind { REST, RECEDE, RELEASE }

    /** How the outgoing voice leaves. */
    public static final class VocalExit {
        /** Seconds into the window where the outgoing voice starts leaving. */
        public final double from;
        /** Seconds into the window where it is gone. */
        public final double to;
        public final ExitKind kind;
        /** How loud the quietest reachable half-second was, in dB below the window's median. */
        public final double loudDb;
        /** The held note the voice is on as it leaves, if it is on one. Null when it is not. */
        public final VocalSustain held;

        VocalExit(double from, double to, ExitKind kind, double loudDb, VocalSustain held) {
            this.from = from;
            this.to = to;
            this.kind = kind;
            this.loudDb = loudDb;
            this.held = held;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%s %.2f-%.2fs (quietest half-second %.0fdB under the mix)%s",
                    kind, from, to, loudDb, held != null ? ", " + held : "");
        }
    }

    /**
     * The last note the outgoing voice HOLDS inside the window, or null if it never holds one.
     *
     * <p>Walked forward and kept rather than returned early, because the last one is the one an
     * exit has to deal with. A run opens when the voice comes above the singing floor, tracks
     * its own peak, and closes the moment the level falls more than {@link #SUSTAIN_FLAT_DB}
     * under that peak — which for a held note is the singer letting go, and for a sung phrase is
     * the gap after a syllable. Only runs that lasted {@link #SUSTAIN_MIN_SEC} survive, so the
     * second case falls out on its own.
     *
     * <p>The peak is tracked rather than fixed at the run's first cell so a note that swells
     * stays one note. It only ever rises, which makes the band asymmetric on purpose: a note may
     * grow without limit and is finished the moment it decays past the band. That IS the release.
     */
    public static VocalSustain findSustain(float[] vocals, float[] mix, double cellSec) {
        double reference = Math.max(median(mix), 1e-12);
        double floor = reference * Math.pow(10, REST_DB / 20);
        double band = Math.pow(10, -SUSTAIN_FLAT_DB / 20);
        int need = Math.max(1, (int) Math.round(SUSTAIN_MIN_SEC / cellSec));

        VocalSustain held = null;
        int start = -1;
        double peak = 0;
        // One past the end, so a note still ringing as the window closes is closed by the loop
        // rather than dropped — that case is the whole point and it is the one an early exit loses.
        for (int cell = 0; cell <= vocals.length; cell++) {
            double level = cell < vocals.length ? vocals[cell] : 0;
            if (start >= 0 && level > floor && level >= peak * band) {
                if (level > peak) peak = level;
                continue;
            }
            if (start >= 0 && cell - start >= need) {
                held = new VocalSustain(start * cellSec, cell * cellSec, db(peak / reference));
            }
            start = level > floor ? cell : -1;
            peak = level;
        }
        return held;
    }

    /**
     * How the outgoing voice leaves: cut it in a rest, otherwise let it recede.
     *
     * <p>The winner of Folia's round eleven, and the ONE arm across eight pairs that the
     * listener never once flagged as swallowing a vocal.
     *
     * <p>The search is FREE, not snapped to the beat grid. Snapping was tried because both
     * confirmed wins of an earlier round happened to land on a beat; rendered side by side,
     * snapping found a -12 dB moment where the free search found -28 dB on a track whose rest
     * is barely half a second long. Two post-hoc coincidences against one measured 16 dB loss.
     *
     * @param vocals   the outgoing vocal stem's envelope over the window
     * @param mix      the outgoing mix's envelope over the same window — what "quiet" is
     *                 measured against
     * @param hardEnd  last moment the exit may still be running; past it the incoming voice is
     *                 established
     * @param recedeFrom where a recede begins; only used when there is no rest to cut in
     * @param mayRide  whether a held note may be ridden out over the incoming track at all.
     *                 False only when the two keys are KNOWN to clash — a semitone or a tritone
     *                 apart: a held vowel is a sustained PITCH, and this is the one gesture here
     *                 that puts a bare interval in front of the listener for seconds at a time.
     *                 Only "clashing", never "anything short of compatible": two of four
     *                 transitions in one real session came back "unknown", and what a refusal
     *                 falls back to is a fade across the middle of a held note — the defect this
     *                 branch exists to remove.
     */
    public static VocalExit planVocalExit(float[] vocals, float[] mix, double hardEnd,
                                          double recedeFrom, double cellSec, boolean mayRide) {
        double reference = median(mix);
        int span = Math.max(1, (int) Math.round(CUT_SEC / cellSec));
        // The LOUDEST the voice gets anywhere in the half second, not its average: a cut is only
        // painless if nothing is being cut off, and an average hides a syllable inside a pause.
        final float[] v = vocals;
        // Walked in whole cells rather than by adding cellSec to a running total. The accumulating
        // version drifted — after sixty steps it was a few femtoseconds under the cell it named,
        // invisible until round(at / cellSec) lands one cell early and the search reads a
        // different half second than it reports.
        int firstCell = (int) Math.round(recedeFrom / cellSec);
        int lastCell = (int) Math.floor((hardEnd - CUT_SEC) / cellSec);
        int last = -1;
        double lastValue = Double.POSITIVE_INFINITY;
        int quietest = firstCell;
        double quietestValue = Double.POSITIVE_INFINITY;
        for (int cell = firstCell; cell <= lastCell; cell++) {
            double value = loudAt(v, cell, span, reference);
            if (value < quietestValue) {
                quietest = cell;
                quietestValue = value;
            }
            if (value <= REST_DB) {
                last = cell;
                lastValue = value;
            }
        }
        if (Double.isInfinite(quietestValue)) quietestValue = loudAt(v, firstCell, span, reference);
        // The last painless moment when there is one; otherwise the quietest, which is then only
        // reported — the recede branch below is what actually runs.
        //
        // From `recedeFrom` and the LAST rather than the quietest are both load-bearing. Measured:
        // a 23.5s blend found a genuine -35 dB rest at 5.35s and ended the vocal at 6.33s while
        // the incoming voice was not due until 20.15s — nearly fourteen seconds of two songs
        // playing at once with neither singing. And of the moments that pass, ranking them by
        // depth answers a question already answered: REST_DB is a threshold with a meaning, so
        // every moment under it is equally painless AS AN EDIT; what separates them is what comes
        // AFTER, and choosing an early one deletes every word sung in between.
        int bestCell = last >= 0 ? last : quietest;
        double bestValue = last >= 0 ? lastValue : quietestValue;
        double at = bestCell * cellSec;

        // How long the cut takes, graded by how quiet the moment it lands in actually is.
        // REST_DB is a threshold, and everything on the quiet side of it used to be treated the
        // same — a half-second cut at -56 dB, where there is genuinely nothing to cut off, and the
        // same half-second at -31 dB, where there is. Deep silence keeps the half second; a
        // marginal rest gets up to a second and a bit, still a decision and not a drift.
        double softness = Math.min(1, Math.max(0, (bestValue - DEEP_REST_DB) / (REST_DB - DEEP_REST_DB)));
        double exitSec = CUT_SEC + softness * (SOFT_EXIT_SEC - CUT_SEC);

        VocalSustain held = findSustain(vocals, mix, cellSec);

        // A note still sounding at the deadline is ridden to its release instead of faded across.
        // This branch outranks the other two, and it has to: both of them end the voice somewhere
        // inside the note. The rest search would cut in a quiet moment BEFORE it and take the whole
        // note away; the recede would fade down the middle of it, the one gesture an ear reads as a
        // fader rather than music.
        //
        // Three conditions, and the third keeps this honest: the note has to be sounding at the
        // deadline (from <= hardEnd < to), or it is not the note the exit collides with; it has to
        // RELEASE inside the window (one cell of margin); and riding it must not be refused by a
        // known key clash. What it does NOT ask for is room for a full half-second cut afterwards —
        // that bound cost the very transition this branch was written for (a note releasing at
        // 13.45s in a 13.62s window), so the cut is clamped to the window instead.
        double windowSec = vocals.length * cellSec;
        boolean ridable = mayRide
                && held != null
                && held.from <= hardEnd
                && held.to > hardEnd
                && held.to <= windowSec - cellSec;
        if (ridable) {
            return new VocalExit(held.to, Math.min(held.to + CUT_SEC, windowSec),
                    ExitKind.RELEASE, bestValue, held);
        }

        return last >= 0
                ? new VocalExit(at, Math.min(at + exitSec, hardEnd), ExitKind.REST, bestValue, held)
                // Nowhere quiet to hide, so the voice recedes instead. A fade needs somewhere to
                // hide; where there is none, the ear forgives a decision but not a drift.
                : new VocalExit(recedeFrom, hardEnd, ExitKind.RECEDE, bestValue, held);
    }

    /** The loudest the voice gets across the cell's half-second, in dB under the mix's median. */
    private static double loudAt(float[] vocals, int cell, int span, double reference) {
        double peak = 0;
        for (int c = cell; c < cell + span; c++) {
            if (c >= 0 && c < vocals.length) peak = Math.max(peak, vocals[c]);
        }
        return db(peak / Math.max(reference, 1e-12));
    }

    /**
     * The last moment the outgoing track is still singing, in seconds from the window's start,
     * or {@link Double#NaN} when it never sings inside the window at all — the honest answer
     * rather than zero: "the singing stopped before this window began" is a bound, not a time.
     *
     * <p>This exists because a planner is tempted to ask a LYRIC FILE where a track stops
     * singing. Plain LRC has no end times at all, so a parser invents one — the last line's start
     * plus a flat five seconds — and that number is then the floor under every handover placement.
     * It is not a measurement of anything; a singer who holds past it gets a transition opened on
     * top of her.
     *
     * <p>Same threshold as the exit search, against the same reference, for the same reason.
     */
    public static double lastVocalMoment(float[] vocals, float[] mix, double cellSec) {
        double floor = median(mix) * Math.pow(10, REST_DB / 20);
        // Walked backwards, so the first qualifying run found is the LAST one in the window.
        // `edge` is its right-hand end — the cell the run was entered on — because that is the
        // moment being reported, not the point a quarter second earlier where the evidence became
        // sufficient.
        int run = 0;
        int edge = -1;
        for (int cell = vocals.length - 1; cell >= 0; cell--) {
            if (vocals[cell] <= floor) {
                run = 0;
                continue;
            }
            if (run == 0) edge = cell;
            run++;
            if (run >= SUSTAIN_CELLS) return (edge + 1) * cellSec + VOCAL_TAIL_SEC;
        }
        return Double.NaN;
    }

    /**
     * Whether the INCOMING track sings anywhere inside the window.
     *
     * <p>This used to report WHERE it starts singing, and that number was never once honoured by
     * anything. Across two real sessions it read the separation's own leakage as a singer — a
     * voice at 0.70s, 1.05s and 1.85s against intros running 34, 24 and 23 seconds — so the time
     * is gone and only the verdict is left, the half that survives its own error. A positive means
     * nothing, because bleed clears the floor on most heads. A NEGATIVE means nothing anywhere in
     * the window cleared a floor that bleed usually clears, and that is worth having: it lets the
     * outgoing voice keep a window nobody is waiting for.
     *
     * <p>A run of {@link #SUSTAIN_CELLS} is required rather than one loud cell, so two cells of a
     * hi-hat htdemucs filed under vocals cannot turn a silent intro into a singer.
     *
     * <p><b>{@code reference} is the whole separated head, not this window, and that is the entire
     * difference between this working and not.</b> Shipped against the window's own mix it read
     * the bleed as singing on 15 of 24 real transitions. The floor is relative to the median of
     * what else is playing, and the two ends are not in the same regime: an outgoing tail is
     * full-band music, so the floor lands where a voice lives, while an incoming HEAD window can be
     * nothing but a quiet intro, which drags the median down until the leakage clears it.
     */
    public static boolean singsInWindow(float[] vocals, float[] reference) {
        double floor = median(reference) * Math.pow(10, REST_DB / 20);
        int run = 0;
        for (float vocal : vocals) {
            if (vocal <= floor) {
                run = 0;
                continue;
            }
            run++;
            if (run >= SUSTAIN_CELLS) return true;
        }
        return false;
    }

    // --- the handover --------------------------------------------------------

    /** When each stem changes hands across one window, in seconds from its start. */
    public static final class StemHandover {
        /** Seconds into the window where drums change hands. */
        public final double swap;
        /** Where the bass follows, one bar later. Kept apart on purpose — two rhythm sections
         *  stacked for a bar is muddy, and swapping both at once is a seam. */
        public final double bassAt;
        /** Where the incoming voice arrives. */
        public final double vocalIn;
        /**
         * Where the choreography alone would have put it — reported so a log can say WHETHER the
         * ride moved the entry rather than leaving a reader to re-derive a bar length and subtract.
         * Equal to {@link #vocalIn} on every blend that does not ride a note out.
         */
        public final double dueAt;
        public final VocalExit exit;
        /** Whether the swap landed on a bar line rather than on the plain fraction. */
        public final boolean onBarLine;

        StemHandover(double swap, double bassAt, double vocalIn, double dueAt,
                     VocalExit exit, boolean onBarLine) {
            this.swap = swap;
            this.bassAt = bassAt;
            this.vocalIn = vocalIn;
            this.dueAt = dueAt;
            this.exit = exit;
            this.onBarLine = onBarLine;
        }

        /**
         * How long the blend goes with no lead vocal at all — the outgoing one already gone, the
         * incoming one not yet due.
         *
         * <p>Printed on every transition rather than only when it is bad, because this is the
         * number that was wrong and nothing named it. A blend where the listener heard both tracks
         * lose their vocal is not two edits; it is one HOLE. Measured across one session: every
         * blend that sounded right came out at about -0.5s — the incoming voice arriving while the
         * outgoing one is still leaving, so the handover is a pass — and the one the listener
         * flagged came out at +13.8s.
         */
        public double vocalGap() {
            return vocalIn - exit.to;
        }
    }

    /**
     * When each stem changes hands across one window.
     *
     * <p>Drums swap first and near-instantly, bass follows a bar later, and the incoming voice
     * arrives a bar after the drums.
     *
     * @param windowSec      the blend's length
     * @param outgoingBarSec bar length of the outgoing track; null falls back to a quarter of the
     *                       window
     * @param incomingBarSec bar length of the incoming track; null falls back to the outgoing one
     * @param downbeats      downbeats of the outgoing track inside the window, relative to its
     *                       start. Empty means "we could not tell where the bars are" and the swap
     *                       lands on the plain fraction. See {@link #barLines}.
     * @param keysClash      the two keys are known to clash — see
     *                       {@link #planVocalExit}'s {@code mayRide}
     * @param incomingSings  whether the incoming track sings at all inside this window. FALSE
     *                       removes the outgoing voice's deadline entirely. Null means "assume it
     *                       does", which is what every window did before this existed.
     */
    public static StemHandover planStemHandover(double windowSec, Double outgoingBarSec,
                                                Double incomingBarSec, double[] downbeats,
                                                float[] vocals, float[] mix,
                                                Boolean keysClash, Boolean incomingSings,
                                                double cellSec) {
        double bar = outgoingBarSec != null && outgoingBarSec > 0 ? outgoingBarSec : windowSec / 4;
        // The fraction, unless it would leave more than MAX_TAIL_BARS behind the handover — in
        // which case the handover moves back towards the end until it does not. `max`, so this is
        // inert on every window short enough for the fraction to already land late enough.
        double target = Math.max(SWAP_TARGET * windowSec, windowSec - (1 + MAX_TAIL_BARS) * bar);
        // A bar line if one is in reach, because a handover on the count reads as an edit; the
        // plain fraction otherwise, because a handover somewhere beats no handover.
        double bestAt = 0;
        boolean onBarLine = false;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (double at : downbeats) {
            if (at < 1 || at > windowSec - 1.5) continue;
            double distance = Math.abs(at - target);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestAt = at;
                onBarLine = true;
            }
        }
        double swap = onBarLine ? bestAt : target;
        double bassAt = Math.min(swap + bar, windowSec - TAIL_GUARD_SEC);
        double inBar = incomingBarSec != null && incomingBarSec > 0 ? incomingBarSec : bar;
        // Where the incoming voice is DUE — one of its own bars after the drums change hands.
        //
        // The choreography, and a statement about the gesture rather than the song: the incoming
        // track sings when it sings. A vocal-stem measurement of the real entry was wired in there
        // for one round and is now gone — it read the separation's bleed as singing on 15 of 24
        // real transitions, so it was never once honoured, and a number that cannot be trusted must
        // not quietly be a fader time.
        //
        // And when the incoming track does not sing inside this window at all, there is no entry to
        // be due: `entry` goes to the end of the window and every deadline below dissolves with it.
        // What it used to do was impose a deadline on behalf of a voice that never arrives —
        // measured on a 6.57s window against a track whose first section starts at 8.19s, with the
        // outgoing singer faded from 1.99s to 4.41s and her last held note pushed to silence under
        // a fader with nothing else moving.
        boolean noOneWaiting = Boolean.FALSE.equals(incomingSings);
        double entry = noOneWaiting ? windowSec : Math.min(windowSec - 0.6, swap + inBar);
        // `windowSec - 0.4` says nothing should still be moving as the window closes, a statement
        // about a HANDOVER — the incoming track has the floor by then, so a fader still travelling
        // on the outgoing one is an edit heard on top of it. Where nothing is waiting there is no
        // handover to be late for, and the window's end is the outgoing track's own end.
        double hardEnd = noOneWaiting ? windowSec : Math.min(entry + CUT_SEC, windowSec - 0.4);

        // Where a recede starts, derived rather than pinned to the floor.
        //
        // It used to begin at EXIT_FLOOR_SEC — a constant whose actual meaning is "earliest a CUT
        // may be placed", borrowed for a question it does not answer. The recede serves ONE
        // constraint — do not stack two lead vocals — and that constraint does not exist until
        // `vocalIn`. Before it the outgoing voice is the only voice in the room. `swap` is where it
        // starts instead, because that is the moment the beat changes hands and the listener's
        // attention with it.
        //
        // Floored so it stays a fade: squeezed under a second this reads as a cut, in a place the
        // search has already rejected as too loud to cut in. And floored ONLY there — a second
        // floor of two bars was tried and made the "the voice left too quickly" complaint worse on
        // every window it touched, because a longer fade here is not a slower one (the END is
        // pinned at hardEnd, and `fall` crosses audibility at 74.3% of whatever span it is given).
        double recedeFloor = noOneWaiting ? CUT_SEC : MIN_RECEDE_SEC;
        double holdUntil = noOneWaiting ? hardEnd - recedeFloor : swap;
        double recedeFrom = Math.max(EXIT_FLOOR_SEC, Math.min(holdUntil, hardEnd - recedeFloor));
        VocalExit exit = planVocalExit(vocals, mix, hardEnd, recedeFrom, cellSec,
                !Boolean.TRUE.equals(keysClash));

        // When the outgoing voice rides a note out, the incoming voice waits for the note.
        //
        // The choreography is an ORDER before it is a set of times: drums change hands, bass
        // follows, the incoming voice arrives, the outgoing one leaves. The ride reverses the last
        // two steps — the outgoing voice now leaves at the note's release, which can be many
        // seconds after the incoming voice has come up. Measured on the transition this was
        // reported from: a 10.6s held note released at 13.45s in a 14.2s window with the incoming
        // voice at 7.53s — six and a half seconds of two lead vocals.
        //
        // So the entry moves to one of the incoming track's own bars before the release. `max`, so
        // this only ever DELAYS the entry: pulling it earlier would move blends that already work,
        // and written this way it is inert on all five transitions of one session that rode a note
        // out and moves only the outlier.
        double vocalIn = exit.kind == ExitKind.RELEASE
                ? Math.max(entry, Math.min(exit.from - inBar, windowSec - 0.6))
                : entry;

        return new StemHandover(swap, bassAt, vocalIn, entry, exit, onBarLine);
    }

    // --- the curves ----------------------------------------------------------

    /** Equal-power rise from 0 to 1 across [from, to], evaluated at {@code at}. */
    public static double rise(double from, double to, double at) {
        if (at <= from) return 0;
        if (at >= to) return 1;
        return Math.sin(((at - from) / (to - from)) * (Math.PI / 2));
    }

    /**
     * The falling shape, which is deliberately NOT the equal-power complement of {@link #rise}.
     *
     * <p>It is {@code cos(rise * pi/2)} — the sine curve fed through a second cosine — which is
     * what the harness every listening round was scored from does. The two together dip 1.6 dB at
     * their midpoint rather than holding constant power, and that is not an oversight to correct
     * here: the shape was part of what was heard, the crossings it is used for are six
     * milliseconds long, and changing it would make this a different gesture from the one that was
     * tested.
     */
    public static double fall(double from, double to, double at) {
        return Math.cos(rise(from, to, at) * (Math.PI / 2));
    }

    /** Samples a shape into an array the way Folia's {@code curveOf} does. */
    public static float[] curveOf(double seconds, CurveShape shape) {
        int points = Math.max(2, (int) Math.ceil(seconds * CURVE_RATE));
        float[] out = new float[points];
        for (int i = 0; i < points; i++) {
            out[i] = (float) shape.at((i / (double) (points - 1)) * seconds);
        }
        return out;
    }

    /** A gain shape over a window, as Folia passes a closure to {@code curveOf}. */
    public interface CurveShape {
        double at(double seconds);
    }

    /** Index into the four curve arrays. */
    private static int index(Stem stem) {
        return stem.row();
    }

    /** The gain curve each stem of the outgoing deck follows across the window. */
    public static float[][] outgoingCurves(final double windowSec, final StemHandover plan) {
        float[][] curves = new float[4][];
        curves[index(Stem.VOCALS)] = curveOf(windowSec, new CurveShape() {
            @Override public double at(double t) { return fall(plan.exit.from, plan.exit.to, t); }
        });
        curves[index(Stem.DRUMS)] = curveOf(windowSec, new CurveShape() {
            @Override public double at(double t) { return fall(plan.swap, plan.swap + SWAP_EDGE_SEC, t); }
        });
        curves[index(Stem.BASS)] = curveOf(windowSec, new CurveShape() {
            @Override public double at(double t) { return fall(plan.bassAt, plan.bassAt + SWAP_EDGE_SEC, t); }
        });
        // The pad and guitar bed is the one stem that neither swaps nor cuts: it is thinned from
        // below as it goes, so what is left of the outgoing track under the incoming one is air
        // rather than body. The filter sweep that does the thinning is scheduled separately.
        curves[index(Stem.OTHER)] = curveOf(windowSec, new CurveShape() {
            @Override public double at(double t) { return fall(windowSec * 0.92, windowSec, t); }
        });
        return curves;
    }

    /** And the incoming deck's. Deliberately not the mirror image — see {@link #planStemHandover}. */
    public static float[][] incomingCurves(final double windowSec, final StemHandover plan) {
        float[][] curves = new float[4][];
        curves[index(Stem.VOCALS)] = curveOf(windowSec, new CurveShape() {
            @Override public double at(double t) { return rise(plan.vocalIn, plan.vocalIn + CUT_SEC, t); }
        });
        curves[index(Stem.DRUMS)] = curveOf(windowSec, new CurveShape() {
            @Override public double at(double t) { return rise(plan.swap, plan.swap + SWAP_EDGE_SEC, t); }
        });
        curves[index(Stem.BASS)] = curveOf(windowSec, new CurveShape() {
            @Override public double at(double t) { return rise(plan.bassAt, plan.bassAt + SWAP_EDGE_SEC, t); }
        });
        // Arrives BEFORE its own drums, so the incoming track is already present as atmosphere when
        // the beat changes hands (round ten: 3.0 for crashing in with its drums, 7.0 for entering
        // quietly).
        curves[index(Stem.OTHER)] = curveOf(windowSec, new CurveShape() {
            @Override public double at(double t) {
                return rise(Math.max(0, plan.swap - INCOMING_OTHER_LEAD_SEC), plan.swap, t);
            }
        });
        return curves;
    }

    /** The gain of one sampled curve at a window-relative time, linearly interpolated. */
    public static double curveAt(float[] curve, double at, double windowSec) {
        if (curve.length == 0) return 0;
        if (curve.length == 1) return curve[0];
        double position = (at / windowSec) * (curve.length - 1);
        if (position <= 0) return curve[0];
        if (position >= curve.length - 1) return curve[curve.length - 1];
        int low = (int) Math.floor(position);
        double frac = position - low;
        return curve[low] * (1 - frac) + curve[low + 1] * frac;
    }

    // --- bar lines -----------------------------------------------------------

    /**
     * The bar grid of a track: one line every {@code beatsPerBar} beats, from a DOWNBEAT offset.
     *
     * <p>Folia builds this from the track's tempo, its measured downbeat offset and its
     * {@code beatsPerBar}, and its own source is emphatic about why: the beat grid's offset is a
     * phase in the BEAT grid, so feeding it here would space the lines a bar apart and put them at
     * the wrong place inside the bar — "which looks like a bar line and is one three times in
     * four".
     *
     * <p><b>What our profiler has, and what it does not.</b> {@code BeatProfile} exposes
     * {@code firstBeatMs} — the phase of the BEAT grid: every beat is {@code firstBeatMs + k *
     * periodMs}. It has no downbeat offset, because nothing on this platform measures structure.
     * So the two must not be confused, and this method takes the downbeat offset as a separate
     * argument:
     * <ul>
     *   <li>null → no bar lines at all, and {@link #planStemHandover} lands the swap on the plain
     *       fraction. That is the honest answer rather than a guessed line.</li>
     *   <li>a number → lines at {@code offset + k * period}.</li>
     * </ul>
     *
     * <p>{@link #downbeatOffsetSec} estimates that number from the audio when there is no
     * structure pass to ask: it picks, among the {@code beatsPerBar} beat-grid phases, the one
     * where the low end is loudest. That is an EXTENSION, not part of the port — Folia measures
     * downbeats properly — and it is labelled as such wherever it is used.
     *
     * @return downbeats inside [0, windowSec], relative to the window's start, in seconds
     */
    public static double[] barLines(double bpm, Double downbeatOffsetSec, int beatsPerBar,
                                    double windowStartSec, double windowSec) {
        if (bpm <= 0 || downbeatOffsetSec == null || beatsPerBar < 1) return new double[0];
        double period = (60 / bpm) * beatsPerBar;
        if (!(period > 0)) return new double[0];
        double offset = ((downbeatOffsetSec % period) + period) % period;
        int first = (int) Math.ceil((windowStartSec - offset) / period);
        java.util.ArrayList<Double> lines = new java.util.ArrayList<Double>();
        for (int k = first; ; k++) {
            double at = offset + k * period - windowStartSec;
            if (at > windowSec) break;
            if (at >= 0) lines.add(at);
            if (k - first > 10000) break;                 // a period this small is a broken grid
        }
        double[] out = new double[lines.size()];
        for (int i = 0; i < out.length; i++) out[i] = lines.get(i);
        return out;
    }

    /**
     * ESTIMATE of a downbeat offset, in seconds from the track's start, from the low end.
     *
     * <p>Not part of the port. Folia gets its downbeat offset from a structure pass over the whole
     * track; this platform has none, and the choice is between using the beat grid's phase (which
     * is a different question, and as Folia's own source puts it: a bar line spaced a bar apart
     * from the beat phase "is one three times in four") and measuring the one thing a downbeat
     * actually is — the beat the bar starts on, which is usually the one the kick lands hardest on.
     *
     * <p>So: the candidates are the {@code beatsPerBar} beats of the beat grid that a bar's first
     * beat could be, and the winner is the one whose downbeat position carries the most low-end
     * energy. Cheap (one pass over an envelope per candidate), and honest about what it is — a
     * heuristic that can be wrong on a track whose kick is on 2 and 4.
     *
     * @param lowBandEnvelope per-cell RMS of the low end (the model's {@code bass} stem is the
     *                        cleanest source of it), covering {@code fromSec} onwards
     * @param fromSec         where that envelope starts, seconds from the track's start
     * @param cellSec         the envelope's cell length
     * @param beatPhaseSec    the beat grid's phase ({@code BeatProfile.firstBeatMs() / 1000}) — the
     *                        candidates are offset from it, never it itself
     * @param beatPeriodSec   the beat period from {@code BeatProfile.periodMs()}
     * @param beatsPerBar     beats per bar, 4 unless known otherwise
     * @return offset in seconds from the track's start, or NaN when nothing could be measured
     */
    public static double downbeatOffsetSec(float[] lowBandEnvelope, double fromSec, double cellSec,
                                           double beatPhaseSec, double beatPeriodSec,
                                           int beatsPerBar) {
        if (lowBandEnvelope == null || lowBandEnvelope.length < SUSTAIN_CELLS * 2
                || !(beatPeriodSec > 0) || !(cellSec > 0) || beatsPerBar < 1) {
            return Double.NaN;
        }
        double barSec = beatPeriodSec * beatsPerBar;
        double best = Double.NEGATIVE_INFINITY;
        int bestPhase = 0;
        for (int phase = 0; phase < beatsPerBar; phase++) {
            double candidate = beatPhaseSec + phase * beatPeriodSec;
            double sum = 0;
            for (int cell = 0; cell < lowBandEnvelope.length; cell++) {
                double t = fromSec + cell * cellSec;
                double into = ((t - candidate) % barSec + barSec) % barSec;
                int beat = (int) Math.floor(into / beatPeriodSec + 0.5) % beatsPerBar;
                if (beat != 0) continue;                  // only the bar's first beat is scored
                double value = lowBandEnvelope[cell];
                sum += value * value;
            }
            if (sum > best) {
                best = sum;
                bestPhase = phase;
            }
        }
        // Congruent to the beat grid, placed near the window so the caller's own folding mod the
        // bar period keeps it: the offset is where a downbeat sits, not where the track begins.
        double candidate = beatPhaseSec + bestPhase * beatPeriodSec;
        return candidate + Math.floor((fromSec - candidate) / barSec) * barSec;
    }
}
