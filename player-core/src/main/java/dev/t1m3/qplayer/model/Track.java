package dev.t1m3.qplayer.model;

/**
 * Flat track descriptor shared between the library, the netease layer, the
 * audio backend and the QML bridge. Kept a primitive/jdk-only POJO so it
 * dexes for Android and the QML bridge can read its fields directly.
 */
public class Track {

    public enum Source { LOCAL, NETEASE, CUSTOM_API, BILI }

    public Source source = Source.LOCAL;

    /** Local file path (LOCAL source); null for NETEASE/CUSTOM_API. */
    public String filePath;
    /** Content URI (LOCAL source on Android 13+). Preferred over filePath because
     *  Scoped Storage blocks direct file-path access to /storage/emulated/0/. */
    public String contentUri;
    /** Bilibili video id (BILI source), e.g. BV1xx411c7mD. */
    public String biliBvid;
    /** Bilibili page id (BILI source): identifies one part of the video/collection. */
    public long biliCid;
    /** Direct HTTP CDN url (NETEASE, CUSTOM_API or BILI source) — fetched lazily. */
    public String streamUrl;
    /** True when {@link #streamUrl} is only a trial/preview clip (not the full
     *  track) — such a clip must never be written to the audio disk cache. */
    public boolean trial;
    /** Netease song id (NETEASE source); 0 for LOCAL/CUSTOM_API. Lets the controller
     *  refetch {@link #streamUrl} when the previous one expires. */
    public long neteaseId;
    /** External id from a user-configured custom API (CUSTOM_API source); null
     *  otherwise. String rather than long since third-party ids aren't guaranteed
     *  numeric — see {@code dev.t1m3.qplayer.customapi}. */
    public String customId;

    public String title;
    public String artist;
    /** Id of the first-listed artist (NETEASE source); 0 for LOCAL/CUSTOM_API or
     *  when absent. Lets the now-playing UI (lyric page) jump to that artist's
     *  page without a network round-trip. */
    public long artistId;
    /** Every credited artist id/name, encoded like NeteaseSong. Keeping these on
     *  Track preserves multi-artist navigation after a song enters the queue or
     *  the custom playlist instead of degrading to the first artist only. */
    public String artistIdsCsv = "";
    public String artistNamesCsv = "";
    public String album;
    /** Optional source metadata; absent genres can be classified separately for local statistics. */
    public String genre = "";
    /** Netease album id; 0 for local/custom tracks or unavailable metadata. */
    public long albumId;
    public long durationMs;

    /** Embedded / sidecar cover bytes (JPEG/PNG), or null. */
    public byte[] coverBytes;
    /** Remote cover URL (NETEASE source); resolved lazily when coverBytes is null. */
    public String coverUrl;
    /** List-row art. NETEASE: a small CDN thumbnail URL. LOCAL: a downscaled thumbnail
     *  cache file (~256px) so scrolling a list never decodes a multi-megapixel embedded
     *  cover per row. Null if none. */
    public String coverThumbPath;
    /** LOCAL full-ish cover cache file (~512px) for the now-playing / lyric view and the
     *  fluid backdrop; null for NETEASE (which uses {@link #coverUrl}). */
    public String coverLocalPath;

    /** Sidecar lyric paths discovered next to a LOCAL file; null if absent. */
    public String lyricFilePath;
    public String translationFilePath;
    public String romajiFilePath;
    /** Embedded lyric text read straight off the file's tags by the platform
     *  MetadataReader (e.g. desktop's jaudiotagger FieldKey.LYRICS, which — unlike
     *  the hand-rolled {@code EmbeddedLyrics} fallback used where no such tag API
     *  exists — also covers OGG/MP4 containers). Same transient-then-cleared
     *  lifecycle as {@link #coverBytes}: LibraryScanner writes it to a cache .lrc
     *  and nulls it out, never persisted on the Track itself. */
    public String embeddedLyricText;

    /** File size / last-modified (LOCAL source) — the library cache's invalidation
     *  key, so an unchanged file skips the expensive tag/cover/lyric re-read. */
    public long fileSize;
    public long fileMtime;

    /** The source string the audio backend should open: content URI, file path, or stream url. */
    public String playable() {
        if (source == Source.NETEASE || source == Source.CUSTOM_API || source == Source.BILI) {
            return streamUrl;
        }
        return contentUri != null ? contentUri : filePath;
    }

    @Override
    public String toString() {
        return (artist != null ? artist : "?") + " — " + (title != null ? title : "?");
    }
}
