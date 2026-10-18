package dev.t1m3.qplayer.netease.dto;

/**
 * Netease album descriptor — what the album detail page (and an artist's
 * album list) renders.
 */
public class NeteaseAlbum {
    public long id;
    public String name;
    /** Square cover URL (CDN). Renderer fetches bytes lazily. */
    public String coverUrl;
    /** CDN thumbnail URL (coverUrl + ?param=128y128) for QML Image.source. */
    public String coverThumbPath;
    /** Release date, ms since epoch (field "publishTime"). 0 if unknown. */
    public long publishTime;
    public String description;
    public String artistName;
    /** Id of the album's (primary) artist — lets the album page link back to it. */
    public long artistId;
    public int trackCount;
}
