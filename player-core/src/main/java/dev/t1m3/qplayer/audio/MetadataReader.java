package dev.t1m3.qplayer.audio;

import dev.t1m3.qplayer.model.Track;

/**
 * Reads tags (title/artist/album), duration and embedded cover from a LOCAL
 * track's {@code filePath} into the {@link Track}. Platform-specific: desktop
 * uses jaudiotagger, Android uses {@code MediaMetadataRetriever}.
 *
 * <p>Best-effort — implementations must leave the caller's filename-derived
 * defaults intact on any read failure rather than throwing.
 */
public interface MetadataReader {

    void read(Track track);
}
