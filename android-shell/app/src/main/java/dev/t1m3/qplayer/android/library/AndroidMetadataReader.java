package dev.t1m3.qplayer.android.library;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.model.Track;

/**
 * {@link MetadataReader} over {@code android.media.MediaMetadataRetriever}.
 * Best-effort: leaves the caller's filename defaults on any failure.
 *
 * <p>Reads through the track's {@code content://} URI when present: under Scoped
 * Storage (Android 13+) a bare {@code setDataSource(filePath)} on shared storage
 * is denied (EACCES), which silently dropped embedded tags and cover art.
 */
public final class AndroidMetadataReader implements MetadataReader {

    private final Context appContext;

    public AndroidMetadataReader(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    @Override
    public void read(Track track) {
        if (track.filePath == null && track.contentUri == null) return;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if (track.contentUri != null) {
                r.setDataSource(appContext, Uri.parse(track.contentUri));
            } else {
                r.setDataSource(track.filePath);
            }
            String title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (title != null && !title.isEmpty()) track.title = title;
            String artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (artist != null) track.artist = artist;
            String album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            if (album != null) track.album = album;
            String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) {
                try {
                    track.durationMs = Long.parseLong(dur);
                } catch (NumberFormatException ignored) {
                }
            }
            byte[] art = r.getEmbeddedPicture();
            if (art != null) track.coverBytes = art;
        } catch (Throwable ignored) {
            // Unreadable / unsupported — keep filename defaults.
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
