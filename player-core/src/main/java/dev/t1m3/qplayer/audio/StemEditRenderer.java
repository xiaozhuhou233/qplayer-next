package dev.t1m3.qplayer.audio;

import dev.t1m3.qplayer.model.Track;

import java.util.function.BooleanSupplier;

/**
 * The host's stem renderer: the thing that can turn one track's audio file into a
 * {@link DjEdit} file, i.e. the same track with its vocals taken out of the first
 * {@code removalMs} and given back on a bar line.
 *
 * <p>A seam, like {@link SilenceProfiler} and {@link BeatProfiler}: the controller knows
 * <em>when</em> an edit is worth making and <em>where</em> one already exists, and nothing
 * about decoders, ONNX runs or muxers. Without a renderer installed, every path here is
 * exactly today's — the split of "the model is present and verified" that decides whether
 * the feature exists at all lives in the host, because only the host knows where its own
 * private storage is ({@link StemModel} describes what such a file has to be).
 *
 * <p><b>Nothing here may block.</b> {@link #render} starts work and returns; a missing,
 * late or failed render is the normal case and has to be indistinguishable from a build
 * that has no stem code in it at all: the boundary simply blends the way it always did.
 */
public interface StemEditRenderer {

    /** Whether this host can render at all — a model file that
     *  {@link StemModel#recognise} accepted is present. Asked once per session for the
     *  log line; a false answer means the stem path is never entered. */
    boolean available();

    /**
     * Starts one render and reports what it produced, or {@code null} when nothing was written.
     *
     * <p>Called from the preload lane, minutes before the boundary the edit is for; the
     * implementation decides how to run it (this process or another one) but must not perform it
     * on the caller's thread and must abandon it as soon as {@link Request#stillWanted} says so.
     *
     * <p>A file already at the request's own name is not a reason to render: the caller checks
     * that first. A render that fails part way must delete its partial output — the caller
     * treats the presence of a file as "this is a finished edit".
     *
     * <p>⚠️ The path of the finished edit is the renderer's to choose, and the numbers it baked
     * into the audio are part of the name (see {@link Result}). That is deliberate: the boundary
     * that plays the edit has to place its low-end hand-over at the bridge's own start and its
     * pitch rule's deadline before the outgoing track's voice comes back, and both of those are
     * things only the render knows — the stems are gone by the time the boundary runs.
     */
    Result render(Request request);

    /** What one render produced. The renderer names the file, and the name carries the times
     *  the boundary needs; {@link #path} and the parsed fields are the same facts. */
    final class Result {
        /** The finished edit. */
        public final String path;
        /** Where the bridge starts in the incoming track's own file, ms, or -1 for no bridge. */
        public final long bridgeStartMs;
        /** Where the incoming track's vocals are back at unity, ms into its own file, or -1
         *  when the render could not say. */
        public final long vocalReturnEndMs;
        /** A FUSION edit only, else -1: the position in the incoming's own file where the deck
         *  starts playing and the fusion begins — the boundary seeks the deck here instead of
         *  its own beat entry, so the deck provably starts where this render planned it. */
        public final long entryMs;
        /** A FUSION edit only, else -1: the position in the OUTGOING track's own file of the bar
         *  line the outgoing live deck is cut on. The ramp starts when that deck's own position
         *  reaches it, and the file carries the outgoing's material from exactly there. */
        public final long junctionMs;
        /** A FUSION edit only, else -1: the position in the incoming's own file where the last
         *  of the outgoing's material is gone (A's elements leave on bar lines before this). */
        public final long fusionEndMs;
        /** The renderer's own account of what it did, for the caller's log line. */
        public final String note;

        /** Ⓜ Round 34: whether the two backgrounds were fused as a SLAM (both change hands on the
         *  same line, one 80 ms splice, no recede table) rather than by the relation family's own
         *  hand-over. A mutable field rather than a constructor argument, so every caller and every
         *  fixture that builds a {@code Result} keeps compiling and keeps meaning what it meant —
         *  the default is "not a slam". See {@link #isSlam}. */
        public boolean slam;

        public Result(String path, long bridgeStartMs, long vocalReturnEndMs, String note) {
            this(path, bridgeStartMs, vocalReturnEndMs, -1L, -1L, -1L, note);
        }

        public Result(String path, long bridgeStartMs, long vocalReturnEndMs,
                      long entryMs, long junctionMs, long fusionEndMs, String note) {
            this.path = path;
            this.bridgeStartMs = bridgeStartMs;
            this.vocalReturnEndMs = vocalReturnEndMs;
            this.entryMs = entryMs;
            this.junctionMs = junctionMs;
            this.fusionEndMs = fusionEndMs;
            this.note = note == null ? "" : note;
        }

        /** Whether this edit carries the two backgrounds fused, i.e. the boundary must cut the
         *  outgoing deck on {@link #junctionMs} and start the incoming deck on {@link #entryMs}. */
        public boolean isFusion() {
            return junctionMs >= 0L && entryMs >= 0L;
        }

        /** Ⓜ Round 34: whether {@link #isFusion}'s hand-over came from the SLAM path — the one fact
         *  that tells the two products apart for the listener, who asked for exactly that:
         *  「只有真混音才算进去」 (the "strongest mix" hint is a claim about a relation fusion, and a
         *  slam is not one) and, in the same breath, for the detail page to say which of the two a
         *  boundary got. {@link #isFusion} deliberately does not test it: a slam needs the boundary
         *  to cut on the junction and start the deck on the entry exactly as a fusion does. */
        public boolean isSlam() {
            return slam;
        }

        /** The suffix the file name carries — the same facts as the fields above, in the form
         *  a later process can read them back from a directory listing. */
        public static String suffixOf(long bridgeStartMs, long vocalReturnEndMs) {
            return suffixOf(bridgeStartMs, vocalReturnEndMs, -1L, -1L, -1L);
        }

        public static String suffixOf(long bridgeStartMs, long vocalReturnEndMs,
                                      long entryMs, long junctionMs, long fusionEndMs) {
            StringBuilder sb = new StringBuilder();
            if (bridgeStartMs >= 0L) sb.append("-b").append(bridgeStartMs);
            if (vocalReturnEndMs >= 0L) sb.append("-v").append(vocalReturnEndMs);
            if (entryMs >= 0L) sb.append("-e").append(entryMs);
            if (junctionMs >= 0L) sb.append("-j").append(junctionMs);
            if (fusionEndMs >= 0L) sb.append("-f").append(fusionEndMs);
            return sb.toString();
        }

        /** An edit that exists but whose name carried no numbers (a plain round-12 edit, or one
         *  found by key alone): no bridge, and the vocal return is unknown. */
        public static Result unknown(String path) {
            return new Result(path, -1L, -1L, "no bridge times in the file name");
        }
    }

    /** One render: what to read, what to write, and how long the vocals stay out. */
    final class Request {
        /** The audio file to read — the incoming track's own cache file, so a render
         *  costs no network. */
        public final String sourcePath;
        /** Where the finished edit goes, WITHOUT its suffix or extension: the renderer appends
         *  both, because the suffix is what it learns during the render (see {@link Result}). */
        public final String outBasePath;
        /** The incoming track, for the log lines. */
        public final Track track;
        /** How long the incoming track plays with its vocals removed at most: the user's own
         *  blend length (the 过渡时长 setting) plus the head the deck skips, ms — the render's own
         *  window, and the room a fusion has to fit in.
         *
         *  <p>⚠️ Round 20: the gate's own end is {@link #vocalOutMs}, not this. This is the
         *  window the render decodes, separates and writes, and it is deliberately kept at the
         *  blend's own length: a fusion's passage has to fit inside {@code [contentStart, this]},
         *  the incoming's "does its head sing at all" probe reads it, and shrinking it would take
         *  room away from the fusion for no audible gain. See {@link #vocalOutMs}. */
        public final long removalMs;
        /**
         * The last instant at which the incoming track's voice is held at <b>exactly zero</b>,
         * ms into this track's own file — the end of the stretch in which the outgoing track can
         * still be heard, plus the head the incoming deck skips
         * ({@link DjEdit#vocalOutMs} of the boundary's blend, at the file's own origin).
         *
         * <p>Round 20's whole point: the user's rule is 「过渡完再放人声」 — bring the voice after
         * the transition — and the stretch it is about is the one where two voices could stack,
         * which the shipped DJ shape ends at 75.2% of its ramp, not at its end. A 17 s 过渡时长
         * therefore leaves the voice out for 12.8 s of the blend instead of 16.6 s, and the lift
         * lands on the first bar line at or after {@code this + VOCAL_RETURN_MARGIN_MS}.
         *
         * <p>A negative value means "the window's own end" — today's round-17 behaviour, kept for
         * every caller that has no shape to measure (a bare {@code Request} built by a test, or a
         * host whose caller predates this field) and for the renderer's own fusion path, whose
         * file carries the outgoing track's material through the whole passage and whose
         * instrumental passage is the feature ({@code AndroidStemEditRenderer} raises it back to
         * the window when the render turns out to be a fusion).
         */
        public final long vocalOutMs;
        /** The incoming track's beat period, ms, or 0 when no grid was measured — then
         *  there are no bar lines and the vocals come back at the end of the window. */
        public final double beatPeriodMs;
        /** The incoming track's beat-grid phase in the file, ms (only ever used as the
         *  candidate set a downbeat offset is estimated from, never as the bar line
         *  itself). */
        public final double beatPhaseMs;
        /**
         * The OUTGOING track's own audio file, or null — the bridge's other half.
         *
         * <p>{@code StemBridge} carries the outgoing track's low end forward inside the
         * incoming's file, and the only way to have it is to separate the outgoing track's tail
         * too. Null (the track was streamed rather than cached, or there is no outgoing track at
         * all) means no bridge: the edit is the round-12 edit, the vocals come back on a bar
         * line, and the boundary blends exactly as it did before this existed.
         */
        public final String outgoingSourcePath;
        /** The outgoing track's beat period, ms (0 = no grid, and then no bar lines for the
         *  carried passage either). */
        public final double outgoingBeatPeriodMs;
        /** The outgoing track's beat-grid phase in the file, ms. */
        public final double outgoingBeatPhaseMs;
        /**
         * The blend length the boundary will use for this pair, ms — the ramp the incoming deck
         * plays inside ({@code removalMs} minus the incoming's own start position).
         *
         * <p>Only a fusion needs it: the fusion's window is a whole number of the incoming's bars
         * and has to fit inside the blend. A non-positive value means "not known", and then the
         * render is exactly today's edit.
         */
        public final long blendMs;
        /** Where the incoming deck will start playing in its own file today, ms — the reference
         *  the fusion's own {@code entryMs} is chosen around. Negative means "not known". */
        public final long incomingContentStartMs;
        /**
         * The ratio the incoming deck will play at — {@code MixNaturaliser.speed()} for this
         * pair, i.e. what the tempo lock pulls the incoming track by.
         *
         * <p>Needed because everything inside the edit is heard at that ratio, so the carried
         * bass has to be stretched by it to be heard at the outgoing track's own tempo. It is
         * measured at request time (both profiles are cached by then) and the boundary logs what
         * it was; a pair whose grids hold has 1.0 and the carry is sample-exact.
         */
        public final double speed;
        /** Polled during the render; false means the queue moved on and the work is
         *  to be thrown away. Never null. */
        public final BooleanSupplier stillWanted;
        /**
         * The ACE-Step settings, or {@link AceStepBed#OFF} — the cloud ambience bed that may be
         * summed UNDER the passage a fusion renders (see {@code AceStepBed}).
         *
         * <p>Carried on the request rather than read from a settings store inside the renderer, for
         * the same reason every other number here is: a render is a snapshot of the boundary's own
         * decisions, and the host that decides them is the only thing that knows where its settings
         * live. {@link AceStepBed.Config#configured()} is the whole switch — off, or no address, no
         * key or no model, and the render is byte for byte what it is without this field at all.
         *
         * <p>⚠️ The key is in here and it must never come out again: not into a log line, not into
         * the edit's file name, not into anything written to disk. The renderer uses it for one
         * {@code Authorization} header and nothing else.
         */
        public final AceStepBed.Config cloudBed;

        public Request(String sourcePath, String outBasePath, Track track, long removalMs,
                       double beatPeriodMs, double beatPhaseMs, BooleanSupplier stillWanted) {
            this(sourcePath, outBasePath, track, removalMs, beatPeriodMs, beatPhaseMs,
                    null, 0d, 0d, 1d, stillWanted);
        }

        public Request(String sourcePath, String outBasePath, Track track, long removalMs,
                       double beatPeriodMs, double beatPhaseMs, String outgoingSourcePath,
                       double outgoingBeatPeriodMs, double outgoingBeatPhaseMs, double speed,
                       BooleanSupplier stillWanted) {
            this(sourcePath, outBasePath, track, removalMs, beatPeriodMs, beatPhaseMs,
                    outgoingSourcePath, outgoingBeatPeriodMs, outgoingBeatPhaseMs, speed,
                    -1L, -1L, stillWanted);
        }

        public Request(String sourcePath, String outBasePath, Track track, long removalMs,
                       double beatPeriodMs, double beatPhaseMs, String outgoingSourcePath,
                       double outgoingBeatPeriodMs, double outgoingBeatPhaseMs, double speed,
                       long blendMs, long incomingContentStartMs, BooleanSupplier stillWanted) {
            this(sourcePath, outBasePath, track, removalMs, beatPeriodMs, beatPhaseMs,
                    outgoingSourcePath, outgoingBeatPeriodMs, outgoingBeatPhaseMs, speed,
                    blendMs, incomingContentStartMs, -1L, stillWanted);
        }

        public Request(String sourcePath, String outBasePath, Track track, long removalMs,
                       double beatPeriodMs, double beatPhaseMs, String outgoingSourcePath,
                       double outgoingBeatPeriodMs, double outgoingBeatPhaseMs, double speed,
                       long blendMs, long incomingContentStartMs, long vocalOutMs,
                       BooleanSupplier stillWanted) {
            this(sourcePath, outBasePath, track, removalMs, beatPeriodMs, beatPhaseMs,
                    outgoingSourcePath, outgoingBeatPeriodMs, outgoingBeatPhaseMs, speed,
                    blendMs, incomingContentStartMs, vocalOutMs, stillWanted, AceStepBed.OFF);
        }

        public Request(String sourcePath, String outBasePath, Track track, long removalMs,
                       double beatPeriodMs, double beatPhaseMs, String outgoingSourcePath,
                       double outgoingBeatPeriodMs, double outgoingBeatPhaseMs, double speed,
                       long blendMs, long incomingContentStartMs, long vocalOutMs,
                       BooleanSupplier stillWanted, AceStepBed.Config cloudBed) {
            this.sourcePath = sourcePath;
            this.outBasePath = outBasePath;
            this.track = track;
            this.removalMs = removalMs;
            this.vocalOutMs = vocalOutMs > 0L && vocalOutMs < removalMs ? vocalOutMs : removalMs;
            this.beatPeriodMs = beatPeriodMs;
            this.beatPhaseMs = beatPhaseMs;
            this.outgoingSourcePath = outgoingSourcePath;
            this.outgoingBeatPeriodMs = outgoingBeatPeriodMs;
            this.outgoingBeatPhaseMs = outgoingBeatPhaseMs;
            this.speed = speed > 0d ? speed : 1d;
            this.blendMs = blendMs;
            this.incomingContentStartMs = incomingContentStartMs;
            this.stillWanted = stillWanted != null ? stillWanted : () -> true;
            this.cloudBed = cloudBed != null ? cloudBed : AceStepBed.OFF;
        }

        /** Whether this request can carry a bridge at all (the outgoing side is available). */
        public boolean canBridge() {
            return outgoingSourcePath != null && !outgoingSourcePath.isEmpty();
        }

        /** Whether this request can fuse the two backgrounds: the outgoing side is available and
         *  the boundary's own numbers (blend length, incoming start) came with it — without them
         *  the fusion's window and its anchor cannot be placed inside the blend the deck will
         *  really play. */
        public boolean canFuse() {
            return canBridge() && blendMs > 0L && incomingContentStartMs >= 0L;
        }

        public String title() {
            return track != null && track.title != null ? track.title : "?";
        }
    }
}
