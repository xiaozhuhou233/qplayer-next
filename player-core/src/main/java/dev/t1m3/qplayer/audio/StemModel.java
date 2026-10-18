package dev.t1m3.qplayer.audio;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The separation model the DJ edit is made with: which files are acceptable, how a
 * file is recognised as one of them, and the session configuration that was measured
 * on the reference device.
 *
 * <p>Nothing here touches a file or an ONNX runtime — it is the manifest, so it can be
 * read (and tested) without a device. The host finds a candidate under its own private
 * storage — copying it out of the APK's own {@code assets/models/} first when there is
 * none, which is how a model travels inside an APK — hashes it, and asks
 * {@link #recognise} whether the three facts agree; a file
 * the manifest does not recognise is refused and the feature stays inert, which is the
 * whole safety story of this path: an app that cannot prove which weights it loaded
 * must never feed audio through them.
 *
 * <p>⚠️ The two hashes below are the values <em>measured</em> on the reference device and
 * on {@code D:\qplayer-dev\htdemucs\}, not the values an upstream repository advertises:
 * the quarter model is a locally produced derivative (Folia's segment-halved export with
 * the segment halved once more — see {@code AI_HANDOFF} §7, round 6/7) and one handoff
 * line recorded a stale hash for it ({@code e4481383…}, from before it was regenerated).
 * The value here is the one the device actually holds; {@code StemModelTest} pins it.
 *
 * <p>The session configuration is not a taste either: it is what round 6 measured on the
 * Redmi K20 Pro. The CPU arena allocator being off is the one Java-side lever that moves
 * the peak (2146 MB → 1225 MB for the half model, bit-identical output), four threads is
 * the fastest of 1/2/4/8 (8 is slower — big/little scheduling), and NNAPI is excluded
 * outright: it loads, runs 8.5x slower and returns audio that is +15.9 dB wrong.
 */
public final class StemModel {

    /** One acceptable weights file. */
    public static final class Candidate {
        /** File name the host looks for. */
        public final String fileName;
        public final long bytes;
        /** Lower-case hex sha256, as {@code sha256sum} prints it. */
        public final String sha256;
        /** The model's segment length in samples. The host still queries the graph itself
         *  and uses what the graph says — this is what that answer should be, so a
         *  disagreement can be logged rather than silently changing how the window is
         *  chunked. 0 = not pinned. */
        public final long segmentSamples;

        Candidate(String fileName, long bytes, String sha256, long segmentSamples) {
            this.fileName = fileName;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.segmentSamples = segmentSamples;
        }

        @Override
        public String toString() {
            return fileName + " (" + bytes + " bytes, sha256 " + sha256 + ")";
        }
    }

    /** The quarter model: 1.950 s segments, the one the recommended configuration uses.
     *  Every measured column is better than the half model's — 860 MB against 1329 MB of
     *  OS peak over a 30 s window, 32.2 s against 36.8 s of wall clock — and the four
     *  stems still sum to the mix to within -32.2 dB. */
    public static final Candidate QUARTER = new Candidate(
            "htdemucs-quarter.onnx", 97_978_156L,
            "427b9588287d85d78f212d9f6f4acbc42b626e28ef9d2d948fed78311f4aec20",
            86_016L);

    /** The half model Folia ships: 3.901 s segments. Accepted as well, so a user who
     *  already has this file does not have to produce the quarter one — it costs about
     *  55% more peak memory and a little more wall clock for the same audio. */
    public static final Candidate HALF = new Candidate(
            "htdemucs.onnx", 108_644_650L,
            "099b5be76c1f6922124d07f850250f39d1f33f254a0b8cc90f4ec0dfd0912329",
            172_032L);

    /** Looked for in this order: the cheaper model first. */
    private static final List<Candidate> CANDIDATES =
            Collections.unmodifiableList(Arrays.asList(QUARTER, HALF));

    /** Intra-op threads. Four is the measured optimum on the reference device: 1 → 46.1 s,
     *  2 → 26.8 s, 4 → 18.1 s, 8 → 23.1 s for the same 15 s window, and memory does not
     *  depend on it at all (the peak is one segment's activations, allocated once). */
    public static final int INTRA_OP_THREADS = 4;

    /** Whether ORT may keep a CPU arena allocator per session. Off: the single biggest
     *  lever the Java API has (2146 MB → 1225 MB, byte-identical output), because the
     *  arena is what holds the peak after the run instead of handing it back. */
    public static final boolean CPU_ARENA_ALLOCATOR = false;

    /** The rate the model was trained for. A window fed at any other rate is the same
     *  music at the wrong tempo and the wrong pitch, and every time in the plan lands in
     *  the wrong place — this library holds both 44.1 and 48 kHz files, so it can never
     *  be assumed. */
    public static final int MODEL_RATE = 44_100;

    public static List<Candidate> candidates() {
        return CANDIDATES;
    }

    /**
     * The candidate whose name, byte count and digest all agree, or null — in which case
     * the caller must refuse the file and leave the stem path off. All three facts are
     * required: the name alone is a claim by whoever put the file there, the size alone
     * cannot tell a truncated download from a correct one, and the digest is over the
     * bytes that will actually be executed.
     */
    public static Candidate recognise(String fileName, long bytes, String sha256Hex) {
        if (fileName == null || sha256Hex == null) return null;
        for (Candidate c : CANDIDATES) {
            if (c.fileName.equals(fileName) && c.bytes == bytes
                    && c.sha256.equalsIgnoreCase(sha256Hex.trim())) {
                return c;
            }
        }
        return null;
    }

    /** Lower-case hex of a digest, the form {@code sha256sum} prints and the form the
     *  manifest above is written in. */
    public static String hex(byte[] digest) {
        if (digest == null) return null;
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** sha256 of a file, streamed, or null when it cannot be read. */
    public static String sha256(java.io.File file) {
        if (file == null || !file.isFile()) return null;
        java.io.InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new java.io.BufferedInputStream(new java.io.FileInputStream(file), 1 << 16);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Throwable e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** Every acceptable file, as one line, for the log line a refusal writes. */
    public static String manifest() {
        StringBuilder sb = new StringBuilder();
        for (Candidate c : CANDIDATES) {
            if (sb.length() > 0) sb.append(" or ");
            sb.append(c.fileName).append(" (").append(c.bytes).append(" bytes, sha256 ")
              .append(c.sha256).append(')');
        }
        return sb.toString();
    }

    private StemModel() {}
}
