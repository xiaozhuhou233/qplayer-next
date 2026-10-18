package dev.t1m3.qplayer.audio;

/**
 * Measures how much silence a track's own audio starts and ends with, so
 * {@link TransitionKind#SILENCE_TRIM} can put the seam where the two tracks'
 * content meets. Platform-specific by nature (Android decodes a bounded window
 * with {@code MediaExtractor} + {@code MediaCodec}, which the desktop host has no
 * equivalent for), so it is an interface here and the controller only ever uses
 * it through this seam.
 *
 * <p>Implementations must be safe to call from a worker thread, must bound both
 * the audio they read and the wall-clock time they take, and must return null
 * rather than hang, throw or guess: a boundary with no measurement simply does
 * not trim. Nothing about this call is allowed to affect playback.
 */
public interface SilenceProfiler {

    /**
     * Measure both ends of {@code source} (a local path or an {@code http(s)}
     * url).
     *
     * @param source         the audio to decode
     * @param durationMsHint the track's length when the caller already knows it
     *                       (0 = unknown; the container's own duration is used
     *                       instead when it has one)
     * @return the measurement, or null when it could not be had.
     */
    SilenceProfile probe(String source, long durationMsHint);
}
