package dev.t1m3.qplayer.netease.dto;

/**
 * Netease artist descriptor — what the artist detail page renders (avatar,
 * name, bio) above its hot songs and albums.
 */
public class NeteaseArtist {
    public long id;
    public String name;
    /** Avatar URL (CDN). Renderer fetches bytes lazily. */
    public String coverUrl;
    /** The big header image the artist uploaded to their NetEase page (the
     *  artist detail payload's {@code cover}), used as the page's hero backdrop. */
    public String headerUrl;
    /** CDN thumbnail URL (coverUrl + ?param=128y128) for QML Image.source. */
    public String coverThumbPath;
    public String briefDesc;
    public int albumSize;
    public int musicSize;
}
