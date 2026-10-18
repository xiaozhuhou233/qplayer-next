package dev.t1m3.qplayer.customapi;

/**
 * User-configured adapter for a self-hosted third-party music API (e.g. a
 * QQ音乐API / 歌曲宝API style reverse proxy). Rather than hard-coding any one
 * project's request/response shape, the user supplies a URL template per
 * endpoint plus a JSON dot-path for every field to extract — see {@link JsonPath}
 * for the path syntax.
 */
public final class CustomApiConfig {

    public boolean enabled;

    /** Search endpoint URL; must contain the literal {@code {keyword}} placeholder. */
    public String searchUrl;
    /** Dot-path (from the response root) to the array of result objects. */
    public String searchListPath;
    /** Dot-path (from a result object) to its id. */
    public String idPath;
    /** Dot-path (from a result object) to its title. */
    public String namePath;
    /** Optional dot-path to the artist name(s); supports {@code a[].b} to join an array. */
    public String artistPath;
    /** Optional dot-path to the album name. */
    public String albumPath;
    /** Optional dot-path to a cover image URL. */
    public String coverPath;
    /** Optional dot-path to the track length, in whole seconds (not ms) — matches
     *  the common convention among the simple JSON music APIs this adapter
     *  targets. Left unset, the progress bar just shows no duration. */
    public String durationPath;

    /** Playback-url endpoint URL; must contain the literal {@code {id}} placeholder. */
    public String urlUrl;
    /** Dot-path (from the response root) to the resolved playback URL string. */
    public String urlResultPath;

    /** Optional lyric endpoint URL; must contain the literal {@code {id}} placeholder.
     *  Left unset, custom-API tracks simply play without lyrics. */
    public String lyricUrl;
    /** Dot-path (from the response root) to the lyric text (plain LRC — timestamped
     *  lines like {@code [00:12.34]text}). */
    public String lyricResultPath;

    /** Optional extra request headers, {@code Key: Value} pairs separated by
     *  {@code ;} — a single-line QML TextField can't carry embedded newlines, so
     *  this deliberately isn't one-per-line. */
    public String extraHeaders;

    /** True once the fields required to actually issue a search+resolve are present. */
    public boolean isUsable() {
        return enabled
                && notBlank(searchUrl) && notBlank(searchListPath)
                && notBlank(idPath) && notBlank(namePath)
                && notBlank(urlUrl) && notBlank(urlResultPath);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
