package dev.t1m3.qplayer.customapi;

/**
 * Song descriptor decoded from a user-configured custom API's search response.
 * Field names mirror {@link dev.t1m3.qplayer.netease.dto.NeteaseSong} so
 * {@code VirtualSongList.qml} (which reads {@code modelData.name/artist/coverThumbPath})
 * needs no changes to render either list.
 */
public final class CustomSong {
    /** External id from the custom API; string (not long) since third-party ids
     *  aren't guaranteed numeric. */
    public String id;
    public String name;
    public String artist;
    public String album;
    public String coverUrl;
    public String coverThumbPath;
    public long durationMs;
}
