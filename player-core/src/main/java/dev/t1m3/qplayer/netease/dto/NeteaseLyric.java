package dev.t1m3.qplayer.netease.dto;

/**
 * Raw text payloads returned by Netease's {@code /song/lyric} endpoint.
 *
 * <p>Field semantics (mirroring SPlayer's usage):
 * <ul>
 *   <li>{@link #lrc} — standard line-level LRC, always populated when
 *       Netease has lyrics for the song.</li>
 *   <li>{@link #yrc} — syllable-level YRC; present for songs Netease has
 *       run through Yī Yán (逐字) processing. Use in preference to
 *       {@link #lrc} when available for AMLL-style per-syllable rendering.</li>
 *   <li>{@link #tlyric} — translation (LRC format).</li>
 *   <li>{@link #romalrc} — romanisation / pinyin (LRC format).</li>
 * </ul>
 *
 * <p>{@code null} when Netease has no such payload for the song.
 */
public class NeteaseLyric {
    public String lrc;
    public String yrc;
    public String tlyric;
    public String romalrc;
    /** Local freshness marker; zero in older caches. Not a lyric timestamp. */
    public long fetchedAtMs;

    public boolean isEmpty() {
        return (lrc == null || lrc.isEmpty())
            && (yrc == null || yrc.isEmpty());
    }
}
