package dev.t1m3.qplayer.audio;

import java.nio.ByteBuffer;

/**
 * A track's beat grid, measured from its own audio by a {@link BeatProfiler}: how
 * fast it moves ({@code bpm} / {@code periodMs}), where its first beat sits in the
 * file ({@code firstBeatMs}), and how much that measurement can be trusted
 * ({@code confidence} — whether the analysed window agrees with itself; the older
 * peak-prominence reading travels along as {@code prominence} and is logged next to
 * it, see {@link #MIN_CONFIDENCE}).
 *
 * <p>This is what lets two tracks' musical grids meet instead of just overlapping
 * in time (see {@code PlayerController}'s overlap alignment): the incoming track
 * starts on one of its own beats, and the outgoing track's ramp starts on one of
 * its own, so the two grids are lined up at the moment they are mixed rather than
 * wherever the file boundaries happened to fall.
 *
 * <p>The grid is a single <em>period</em> plus a <em>phase</em>: real music has
 * bars, sections and tempo changes, none of which this describes. That is
 * deliberate — nothing on this platform measures structure, and a constant period
 * is enough to answer the only two questions the overlap asks ("where is the
 * nearest beat at or after time T" / "is the grid trustworthy"), while a wrong
 * answer stays bounded by {@link #confidence} and by the caller's shift cap.
 *
 * <p>Immutable, and cached per track (see {@code DiskCache}'s beat sub-cache)
 * because the measurement costs a decode while the answer never changes for an
 * unchanged file.
 */
public final class BeatProfile {

    /**
     * The lowest confidence the alignment is allowed to trust. Below this the
     * measurement is reported and logged but never acted on: an autocorrelation
     * peak this weak is what ambient, classical and speech produce, and a wrong
     * BPM acted on is worse than no BPM at all (the whole point of a gate is that
     * the bad case — no beat to find — must not become a bad transition).
     *
     * <p>What the number is compared against is {@link #confidence()}, which is
     * <em>not</em> a peak's prominence any more: it is how much the analysed window
     * agrees with itself when it is cut into segments and each is asked for its own
     * period and phase (see {@code BeatAnalysis#windowAgreement}). The prominence
     * reading is still measured, still travels with the grid ({@link #prominence()})
     * and is still in every log line next to this one, but it is the binding
     * constraint this metric replaced: on a real library it refused roughly half of
     * it — 0.12-0.34 for tracks whose grids are audibly steady — because densely
     * produced music has onsets at every subdivision and a perfectly steady grid's
     * peak therefore stands above very little.
     *
     * <p>Calibrated by measurement rather than taste: a click track at any tempo in
     * range scores a prominence of 0.9-1.0, a 4/4 pattern with different sounds on
     * different beats 0.35-0.7, and material with no beat — speech over a pad, an
     * ambient drone — stays under 0.25; the agreement reading separates the same
     * material with a much wider gap, because a steady grid agrees with itself
     * (measured at 0.6-1.0) while material whose "tempo" is an artefact of one
     * window does not (measured at 0-0.2). It is deliberately the *lower* edge of the
     * useful range: the cost of refusing a real grid is that a transition is not
     * aligned (today's behaviour), while the cost of trusting a wrong one is two
     * songs that do not line up while believing they do.
     */
    public static final float MIN_CONFIDENCE = 0.35f;

    /** The value {@link #prominence()} has when a reading was never taken (a grid
     *  built by hand, an older caller) — reported as "not measured" rather than 0. */
    public static final float NO_PROMINENCE = -1f;

    private final double bpm;
    private final long firstBeatMs;
    private final float confidence;
    /** The reading {@link #confidence()} replaced, for the log only: how far the
     *  chosen lag's correlation stood above the lags within half a period of it.
     *  Kept on every measured grid while the new gate is calibrated, so the two can
     *  be compared on real material; nothing acts on it. */
    private final float prominence;
    /** The key measured from the same decode, or null when there is none (older
     *  cache, a track with no tonal content, a host with no key estimator). See
     *  {@link KeyProfile}: the key is what the mix's pitch shift is chosen from. */
    private final KeyProfile key;

    /**
     * @param bpm          beats per minute (the grid's speed)
     * @param firstBeatMs  the first beat's offset in the file, ms — the phase; every
     *                     beat is {@code firstBeatMs + k * periodMs()}
     * @param confidence   0..1, straight from the estimator
     */
    public BeatProfile(double bpm, long firstBeatMs, float confidence) {
        this(bpm, firstBeatMs, confidence, null);
    }

    /** Same, with the key measured from the same window of audio. */
    public BeatProfile(double bpm, long firstBeatMs, float confidence, KeyProfile key) {
        this(bpm, firstBeatMs, confidence, NO_PROMINENCE, key);
    }

    /** Same, with the older prominence reading the estimator still measures. */
    public BeatProfile(double bpm, long firstBeatMs, float confidence, float prominence,
                       KeyProfile key) {
        this.bpm = bpm;
        this.firstBeatMs = firstBeatMs;
        this.confidence = Math.max(0f, Math.min(1f, confidence));
        this.prominence = prominence;
        this.key = key;
    }

    /** The key measured alongside the grid, or null when there is none. */
    public KeyProfile key() {
        return key;
    }

    /** The same grid with a key attached — what the host's profiler does when it
     *  measures both from one decode. */
    public BeatProfile withKey(KeyProfile measured) {
        return new BeatProfile(bpm, firstBeatMs, confidence, prominence, measured);
    }

    /** Beats per minute, from the autocorrelation's refined peak. */
    public double bpm() {
        return bpm;
    }

    /** One beat, ms — the same fact as {@link #bpm()}, in the unit the overlap
     *  arithmetic works in. */
    public double periodMs() {
        return 60_000.0 / bpm;
    }

    /** Offset of the first beat in the track's own timeline, ms. */
    public long firstBeatMs() {
        return firstBeatMs;
    }

    /** 0..1; see {@link #MIN_CONFIDENCE}. */
    public float confidence() {
        return confidence;
    }

    /** The reading {@link #confidence()} replaced, or {@link #NO_PROMINENCE} when it
     *  was never taken. Logged next to the gating number so the two can be compared
     *  on real material; nothing acts on it. */
    public float prominence() {
        return prominence;
    }

    /** Whether a prominence reading travelled with this grid. */
    public boolean hasProminence() {
        return prominence >= 0f;
    }

    /** The gating number, rounded for a log line. */
    public String confidenceText() {
        return String.format(java.util.Locale.US, "%.2f", confidence);
    }

    /** The older reading as one token for a log line — "prom 0.29", or null when it
     *  was never measured (a grid from a host that does not measure it). */
    public String prominenceText() {
        if (!hasProminence()) return null;
        return String.format(java.util.Locale.US, "prom %.2f", prominence);
    }

    /** Whether this grid may be acted on at all. */
    public boolean trustworthy() {
        return confidence >= MIN_CONFIDENCE;
    }

    /** The first beat at or after {@code tMs} (the grid extends in both
     *  directions, so a time before the first measured beat is answered too). */
    public long beatAtOrAfter(long tMs) {
        long period = Math.max(1L, Math.round(periodMs()));
        long delta = tMs - firstBeatMs;
        if (delta <= 0L) return firstBeatMs;
        long beats = (delta + period - 1L) / period;     // ceiling
        return firstBeatMs + beats * period;
    }

    /** How far {@code tMs} is from the next beat, ms — 0 when it is on one. */
    public long shiftToBeatMs(long tMs) {
        return Math.max(0L, beatAtOrAfter(tMs) - tMs);
    }

    /**
     * The last beat of this grid at or before {@code tMs}, never below 0 — the mirror of
     * {@link #beatAtOrAfter}, and where a change that has to be hidden behind a transient is
     * placed (round 13 puts the incoming track's pitch step on one: the DJ edit has that
     * deck's vocals out, so the loudest thing under the step is the drumkit, and a beat is
     * where the drumkit is loudest).
     *
     * <p>Deliberately "at or before" rather than "nearest": the caller is a deadline
     * (identity has to be reached by a named position), and a beat later than it would
     * violate the deadline rather than merely be less well hidden.
     */
    public long beatAtOrBefore(long tMs) {
        long period = Math.max(1L, Math.round(periodMs()));
        long delta = tMs - firstBeatMs;
        if (delta <= 0L) return Math.max(0L, firstBeatMs);
        return firstBeatMs + (delta / period) * period;
    }

    /**
     * The overlap length nearest {@code wantedMs} at which an overlap ends on one of
     * this track's beats.
     *
     * <p>An overlap's ramp <em>starts</em> {@code durMs - tailMs - overlap} into the
     * outgoing track (the tail is the part the ramp ends early by, so the promotion
     * lands before the track's own completion callback). For that instant to be a
     * beat, the overlap has to satisfy {@code overlap ≡ durMs - tailMs - firstBeat
     * (mod period)} — so the candidates are that residue plus any whole number of
     * beats, and the answer is the candidate nearest {@code wantedMs}. "Nearest"
     * bounds the move: the ramp can only shift by half a beat, which is what keeps a
     * wrong grid from dragging a four-second overlap somewhere else.
     *
     * @return the snapped length, or -1 when no whole beat fits at all (less than one
     *         period of room, or a {@code wantedMs} under one beat).
     */
    public long snapOverlapMs(long wantedMs, long durMs, long tailMs) {
        long period = Math.max(1L, Math.round(periodMs()));
        if (wantedMs < period) return -1L;
        long residue = Math.floorMod(durMs - tailMs - firstBeatMs, period);
        long beats = Math.round((double) (wantedMs - residue) / period);
        long snapped = residue + beats * period;
        if (snapped < period) return -1L;
        return snapped;
    }

    /**
     * Whether two grids are close enough that aligning them says something true.
     *
     * <p>Defined by what the listener hears rather than by a bare tempo threshold:
     * both grids are put on a beat at the start of the overlap, so the error the
     * overlap accumulates is {@code overlapsBeats * |periodA - periodB|} — the two
     * grids drift and the phase between them slips. Compatibility is that drift
     * staying under half a beat <em>of the incoming track</em> for the whole
     * overlap, i.e. the two grids may lean on each other but never cross over (no
     * beat of one ever lands nearer the other's offbeat than to its beat).
     *
     * <p>This makes long overlaps strictly harder than short ones, which is
     * musically right and is the honest answer for this phase: without tempo
     * matching (a later phase), a 15 s overlap needs the two tempos within about
     * 1.5% of each other, so a long pick whose grids disagree is skipped rather
     * than forced (the log says which).
     */
    public static boolean gridsCompatible(BeatProfile a, BeatProfile b, long overlapMs) {
        if (a == null || b == null || overlapMs <= 0L) return false;
        double periodA = a.periodMs();
        double periodB = b.periodMs();
        if (periodA <= 0d || periodB <= 0d) return false;
        double beats = overlapMs / periodA;
        double drift = beats * Math.abs(periodA - periodB);
        return drift <= periodB * 0.5d;
    }

    /** How far the two grids slide apart over {@code overlapMs}, ms — what
     *  {@link #gridsCompatible} compares against half a beat, and what the log
     *  reports when it refuses. */
    public static long gridDriftMs(BeatProfile a, BeatProfile b, long overlapMs) {
        if (a == null || b == null || overlapMs <= 0L) return 0L;
        double beats = overlapMs / a.periodMs();
        return Math.round(beats * Math.abs(a.periodMs() - b.periodMs()));
    }

    /**
     * How much two measured tempos may differ, as a fraction, and still be the same groove:
     * used between a tempo and its half, double, third, three halves and two thirds.
     *
     * <p>It is {@link IncomingMix#MAX_SPEED_STEP} exactly, and that is not a coincidence: it is
     * the same tolerance asked a different question. That one asks "can the incoming deck be
     * stretched onto this grid?", which is a statement about the correction the app is willing
     * to apply; this one asks "are these two the same rhythm?", which is a statement about what
     * a listener hears. They agree on 8% because a groove pulled further than that is no longer
     * the same groove.
     */
    public static final double RELATIVE_TEMPO_TOLERANCE = IncomingMix.MAX_SPEED_STEP;

    /**
     * Whether two measured tempos are the <em>same groove</em>: the same beat, half or double
     * it, a third of it, three halves or two thirds — each within
     * {@link #RELATIVE_TEMPO_TOLERANCE}.
     *
     * <p>⚠️ Why this exists. The estimator reports the tempo of a 30 s window, and half of this
     * library is reported at half or double its nominal tempo (round 8 measured it: 71.6 for a
     * true 143, 64.0 for 128 — the same grid read an octave apart, and both readings are correct
     * for what they describe). A pair read at 123.1 and 61.0 is therefore <em>the same beat</em>,
     * and anything that treats "the ratio is not 1.00" as a clash calls a pair that mixes
     * perfectly unmixable — the round-17 kind-distribution harness found exactly that pair
     * (x2.0170, reported as a rhythm clash) before this method existed.
     *
     * <p>It is deliberately NOT a stretch: nothing here re-interprets a half-tempo reading as a
     * double-time one when deciding whether to pull the incoming deck (round 4 chose not to, and
     * round 10 deleted that stretch altogether). This answers a question about the
     * <em>listener</em>, not about the player: two grooves an octave apart do not fight, so they
     * are not evidence for playing the two tracks one after the other.
     */
    public static boolean relatedTempo(BeatProfile a, BeatProfile b) {
        if (a == null || b == null) return false;
        double periodA = a.periodMs();
        double periodB = b.periodMs();
        if (!(periodA > 0d) || !(periodB > 0d)) return false;
        return nearRelativeTempo(periodB / periodA);
    }

    /** The relationships {@link #relatedTempo} accepts, given as {@code periodB / periodA}:
     *  B's beat twice as slow as A's is 2, twice as fast is 0.5. */
    private static final double[] TEMPO_RELATIVES = {1d, 2d, 0.5d, 3d, 1d / 3d, 1.5d, 2d / 3d};

    /** {@link #relatedTempo}'s arithmetic on the ratio alone, so a test can sweep it. */
    public static boolean nearRelativeTempo(double ratio) {
        if (!(ratio > 0d)) return false;
        for (double relative : TEMPO_RELATIVES) {
            if (Math.abs(ratio - relative) <= RELATIVE_TEMPO_TOLERANCE * relative) return true;
        }
        return false;
    }

    // --- disk form ----------------------------------------------------------

    /** Version byte of {@link #toBytes()}: bump it when the layout changes, so an
     *  older cache file is ignored instead of misread. Version 2 appended the key
     *  to the grid; version 3 appended the prominence reading next to the gating
     *  confidence; version 4 is the same layout written by the estimator that checks
     *  the chosen period against its finer harmonic relatives (a 2:3 mis-lock is no
     *  longer reported); version 5 is again the same layout, written by the key
     *  estimator that folds raw magnitudes instead of log-compressed ones and gates
     *  on the margin over the keys a track could not be mixed with. Versions 1-4 are
     *  deliberately NOT read any more: 1 and 2 carry something else in the gating
     *  field, 3 is the <em>same</em> field holding an older answer — a grid that is a
     *  2:3 coarsening of the track's real beat, i.e. beats between the music's beats,
     *  which no alignment may be built on — and 4 carries a pitch-class profile folded
     *  from log-compressed magnitudes, which sits at 0.066-0.097 per bin against a
     *  uniform 0.083 and on this library's 36 cached tracks named the wrong key on 16
     *  of them. A cache file is a decode's worth of work per track; the cost of
     *  discarding the old ones once is one re-measure per track, and the first play of
     *  that track pays it off the playback path.
     *
     *  <p><b>Version 6 is the same layout, and the first bump that is not about the
     *  estimator's arithmetic.</b> The profiler now refuses to answer with a pass its own
     *  deadline truncated — the starvation flag lives in the decode, in the platform half, and
     *  is not something a stored grid can carry. A 5 file cannot therefore be told from one
     *  whose numbers came out of a decode that died after four seconds, and that is exactly the
     *  failure this bump pays for: on this device one track sat at confidence <b>0.19</b> (under
     *  {@link #MIN_CONFIDENCE}) from a pass starved while DJ edits were rendering, and the cache
     *  made that reading permanent — the pair it belonged to could not be faded, fused or
     *  aligned for as long as the file existed, and the head window that would have measured it
     *  properly was never asked again. So the old files are discarded and every track is
     *  re-measured, which is the same price this field has always charged for a measurement
     *  whose meaning changed. */
    private static final byte VERSION = 6;

    /** Twelve bytes plus the key when there is one and the prominence when it was
     *  measured (two bytes): version, a reserved byte, the gating confidence in
     *  thousandths, the tempo in hundredths of a BPM, the first beat in ms, the
     *  prominence in thousandths ({@link #NO_PROMINENCE} written as 0xFFFF), and then
     *  {@link KeyProfile#toBytes()} when a key was measured. The BPM rather than the
     *  period is what is stored because the period is derived from it
     *  ({@link #periodMs()}) — one number, one rounding error, and hundredths of a
     *  BPM is finer than any alignment needs (0.04 ms of period at 120 BPM, well
     *  under a millisecond of drift over a whole overlap). */
    public byte[] toBytes() {
        byte[] keyBytes = key != null ? key.toBytes() : null;
        ByteBuffer buf = ByteBuffer.allocate(14 + (keyBytes != null ? keyBytes.length : 0));
        buf.put(VERSION);
        buf.put((byte) 0);
        buf.putShort((short) Math.round(confidence * 1000f));
        buf.putInt((int) Math.round(bpm * 100d));
        buf.putInt((int) Math.max(0L, Math.min(Integer.MAX_VALUE, firstBeatMs)));
        buf.putShort(hasProminence() ? (short) Math.round(prominence * 1000f) : (short) -1);
        if (keyBytes != null) buf.put(keyBytes);
        return buf.array();
    }

    /** Inverse of {@link #toBytes()}; null when the bytes are not one (a cache file
     *  from an older layout, a truncated write, a tempo outside the range this
     *  estimator can produce). A null answer means "no grid", and the caller then
     *  behaves exactly as it did before any of this existed — a cache file may
     *  never be the reason a transition is wrong. */
    public static BeatProfile fromBytes(byte[] data) {
        if (data == null || (data.length != 14 && data.length != 29)) return null;
        ByteBuffer buf = ByteBuffer.wrap(data);
        byte version = buf.get();
        if (version != VERSION) return null;
        buf.get();                                  // reserved
        int confMilli = buf.getShort() & 0xFFFF;
        int bpmHundredths = buf.getInt();
        int firstBeatMs = buf.getInt();
        short promMilli = buf.getShort();
        if (bpmHundredths <= 0 || firstBeatMs < 0) return null;
        double bpm = bpmHundredths / 100d;
        if (bpm < 20d || bpm > 400d) return null;
        float prominence = promMilli < 0 ? NO_PROMINENCE : (promMilli & 0xFFFF) / 1000f;
        KeyProfile key = data.length == 29 ? KeyProfile.fromBytes(data, 14) : null;
        return new BeatProfile(bpm, firstBeatMs, confMilli / 1000f, prominence, key);
    }

    /** "97.0BPM/0.81 (prom 0.74)" — one token pair for the boundary's log line. The
     *  older prominence reading rides along in brackets while the agreement gate is
     *  calibrated: the two have to be comparable per track from one session's log. */
    public String label() {
        String prom = prominenceText();
        return String.format(java.util.Locale.US, "%.1fBPM/%.2f", bpm, confidence)
                + (prom != null ? " (" + prom + ")" : "");
    }

    /** "128.2BPM/0.72 C major/0.41" — the grid and, when it was measured, the
     *  key, for the boundary's log line. */
    public String mixLabel() {
        return key == null ? label() : label() + " " + key.label();
    }

    @Override
    public String toString() {
        return "BeatProfile{" + label() + (key != null ? ", " + key : "")
                + ", first beat=" + firstBeatMs
                + "ms, period=" + Math.round(periodMs()) + "ms}";
    }
}
