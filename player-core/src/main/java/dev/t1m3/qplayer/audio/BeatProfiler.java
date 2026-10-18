package dev.t1m3.qplayer.audio;

/**
 * Measures a track's beat grid (tempo and phase), so a transition can put the two
 * tracks' beats on top of each other instead of overlapping them arbitrarily.
 *
 * <p>The same seam as {@link SilenceProfiler} and for the same reason: decoding
 * audio is platform-specific ({@code MediaExtractor} + {@code MediaCodec} on
 * Android, nothing equivalent on the desktop host), so the estimation is asked for
 * through this interface and the controller never touches a decoder.
 *
 * <p>Implementations must be safe to call from a worker thread, must bound both the
 * audio they read and the wall-clock time they take, and must return null rather
 * than hang, throw or guess. A track with no beat to find (ambient, classical,
 * speech) is the normal case, not a failure: everything that wants a grid falls
 * back to what it did before this existed, and nothing about the call is allowed
 * to affect playback.
 */
public interface BeatProfiler {

    /**
     * Measure the beat grid of {@code source} (a local path or an {@code http(s)}
     * url).
     *
     * @param source         the audio to decode
     * @param durationMsHint the track's length when the caller already knows it
     *                       (0 = unknown). Accepted for the same shape as
     *                       {@link SilenceProfiler#probe} but not used by the
     *                       measurement: a grid is estimated from the track's head,
     *                       where every tempo in range has dozens of beats, so a
     *                       length adds nothing to it.
     * @return the grid, or null when it could not be measured.
     */
    BeatProfile probe(String source, long durationMsHint);
}
