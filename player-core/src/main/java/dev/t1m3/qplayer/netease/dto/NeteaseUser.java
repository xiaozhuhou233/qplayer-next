package dev.t1m3.qplayer.netease.dto;

/**
 * Profile of the logged-in Netease account. Populated by
 * {@code NeteaseClient.userDetail(uid)}.
 */
public class NeteaseUser {
    public long uid;
    public String nickname;
    /** Square avatar URL (CDN). Renderer fetches bytes lazily. */
    public String avatarUrl;
    /** 0 = free, 10/11 = VIP. */
    public int vipType;
    /** Account level (1-10, roughly), badge-able. */
    public int level;
    public String signature;
}
