package dev.t1m3.qplayer.netease.dto;

/**
 * Netease playlist descriptor — what the home page / search results /
 * collection grids render. Just the fields the UI needs; full track
 * list is fetched separately via {@code /playlist/track/all}.
 */
public class NeteasePlaylist {
    public long id;
    public String name;
    /** Square cover URL (CDN). Renderer fetches bytes lazily. */
    public String coverUrl;
    /** CDN thumbnail URL (coverUrl + ?param=512y512) for QML Image.source.
     *  Set by the controller after playlists are loaded. */
    public String coverThumbPath;
    public int trackCount;
    public long playCount;
    public String description;
    public String creatorNickname;
    /** uid of the playlist creator — used to split owned vs subscribed in /user/playlist. */
    public long creatorUid;
    /** Whether the signed-in user created this playlist (vs merely subscribed to it).
     *  Set by the controller in loadMyPlaylists; gates "添加到歌单" (you can only add
     *  tracks to your own playlists). */
    public boolean owned;
    /** Whether the signed-in user has collected (subscribed to) this playlist. Populated
     *  by playlist/detail; meaningless for the user's own playlists. */
    public boolean subscribed;
}
