package dev.t1m3.qplayer.netease;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** Offline discovery fixtures: no account, cookie, network, or playback mutation. */
public class NeteaseHomeFeedTest {
    @Test public void preservesRecommendationLabelsAndLeavesMissingLabelsEmpty() {
        JsonObject labelled = song(21);
        JsonObject data = labelled.getAsJsonObject("resourceExtInfo").getAsJsonObject("songData");
        data.addProperty("genre", "民谣");
        data.addProperty("reason", "根据你喜欢的歌推荐");
        JsonObject uiReason = new JsonObject(); uiReason.addProperty("title", "不覆盖歌曲自身理由");
        labelled.getAsJsonObject("uiElement").add("subTitle", uiReason);
        JsonObject resourceLabel = song(22);
        JsonObject reason = new JsonObject(); reason.addProperty("title", "适合午后聆听");
        resourceLabel.getAsJsonObject("uiElement").add("subTitle", reason);
        NeteaseClient.HomeFeed feed = NeteaseClient.parseHomeFeed(root(block("SONGS", "推荐", labelled, resourceLabel, song(23))));
        assertEquals("民谣", feed.sections.get(0).songs.get(0).genre);
        assertEquals("根据你喜欢的歌推荐", feed.sections.get(0).songs.get(0).recommendReason);
        assertEquals("适合午后聆听", feed.sections.get(0).songs.get(1).recommendReason);
        assertEquals("", feed.sections.get(0).songs.get(2).recommendReason);
        assertEquals("", feed.sections.get(0).songs.get(2).genre);
    }

    @Test public void keepsServerSectionsAndSongOrderInsteadOfRelabellingEverythingRadar() {
        JsonObject first = block("STYLE", "适合你的音乐", song(2), song(1), song(2));
        JsonObject second = block("HOMEPAGE_BLOCK_RADAR", "雷达推荐", song(3));
        NeteaseClient.HomeFeed feed = NeteaseClient.parseHomeFeed(root(first, second));
        assertEquals(2, feed.sections.size());
        assertEquals("适合你的音乐", feed.sections.get(0).title);
        assertEquals(2, feed.sections.get(0).songs.size());
        assertEquals(2L, feed.sections.get(0).songs.get(0).id);
        assertEquals(1L, feed.sections.get(0).songs.get(1).id);
        assertEquals("雷达推荐", feed.sections.get(1).title);
    }

    @Test public void radarPlaylistIsARealPreviewRequestNotFakeDailySongs() {
        JsonObject playlist = resource("playlist", "88", "私人雷达");
        NeteaseClient.HomeFeed feed = NeteaseClient.parseHomeFeed(root(
                block("HOMEPAGE_BLOCK_RADAR", "你的雷达歌单", playlist, playlist)));
        assertEquals(1, feed.sections.size());
        assertEquals(88L, feed.sections.get(0).playlistId);
        assertEquals("私人雷达", feed.sections.get(0).title);
        assertTrue(feed.sections.get(0).songs.isEmpty());
    }

    @Test public void readsAlbumUiMetadataAndDoesNotTreatASongAsAnAlbum() {
        JsonObject album = resource("album", "42", "测试专辑");
        JsonObject ext = new JsonObject();
        JsonObject artist = new JsonObject();
        artist.addProperty("name", "测试歌手");
        JsonArray artists = new JsonArray();
        artists.add(artist);
        ext.add("artists", artists);
        album.add("resourceExtInfo", ext);
        NeteaseClient.HomeFeed feed = NeteaseClient.parseHomeFeed(root(
                block("NEW_ALBUM", "新碟", album, album, song(6))));
        assertEquals(1, feed.albums.size());
        assertEquals(42L, feed.albums.get(0).id);
        assertEquals("测试专辑", feed.albums.get(0).name);
        assertEquals("测试歌手", feed.albums.get(0).artistName);
        assertEquals("https://example.invalid/cover.jpg?param=128y128", feed.albums.get(0).coverThumbPath);
    }

    @Test public void skipsMalformedDisabledAndVideoResourcesWithoutDroppingValidSongs() {
        JsonObject malformed = song(7);
        malformed.getAsJsonObject("resourceExtInfo").getAsJsonObject("songData").addProperty("id", "bad");
        JsonObject video = song(8);
        video.addProperty("resourceType", "video");
        JsonObject disabled = song(9);
        disabled.addProperty("valid", false);
        NeteaseClient.HomeFeed feed = NeteaseClient.parseHomeFeed(root(
                block("SONGS", "推荐", malformed, video, disabled, song(10))));
        assertEquals(1, feed.sections.size());
        assertEquals(1, feed.sections.get(0).songs.size());
        assertEquals(10L, feed.sections.get(0).songs.get(0).id);
    }

    @Test public void boundsSongRowsAndNetworkPreviewRequests() {
        JsonObject[] songs = new JsonObject[70];
        for (int i = 0; i < songs.length; i++) songs[i] = song(i + 1);
        JsonObject[] playlists = new JsonObject[8];
        for (int i = 0; i < playlists.length; i++) playlists[i] = resource("playlist", "" + (100 + i), "雷达" + i);
        NeteaseClient.HomeFeed feed = NeteaseClient.parseHomeFeed(root(
                block("SONGS", "歌曲", songs), block("RADAR", "雷达", playlists)));
        assertEquals(30, feed.sections.get(0).songs.size());
        assertEquals(4, feed.sections.size());
    }

    @Test public void emptyOrUnsupportedFeedDoesNotInventContent() {
        assertTrue(NeteaseClient.parseHomeFeed(new JsonObject()).sections.isEmpty());
        assertTrue(NeteaseClient.parseHomeFeed(null).albums.isEmpty());
        assertTrue(NeteaseClient.parseHomeFeed(root(block("ADS", "推广",
                resource("video", "8", "广告")))).sections.isEmpty());
    }

    @Test public void keepsGenrePlaylistShelvesWithoutReplacingThemWithAlbums() {
        JsonObject invalid = resource("playlist", "bad", "无效");
        JsonObject disabled = resource("playlist", "55", "下架");
        disabled.addProperty("valid", false);
        NeteaseClient.HomeFeed feed = NeteaseClient.parseHomeFeed(root(
                block("STYLE_PLAYLIST", "探索曲风", resource("playlist", "41", "R&B精选"),
                        resource("playlist", "41", "重复"), invalid, disabled),
                block("MOOD_PLAYLIST", "此刻心情", resource("playlist", "42", "午后"))));
        assertEquals(2, feed.playlistSections.size());
        assertEquals("探索曲风", feed.playlistSections.get(0).title);
        assertEquals(1, feed.playlistSections.get(0).playlists.size());
        assertEquals(41L, feed.playlistSections.get(0).playlists.get(0).id);
        assertEquals("https://example.invalid/cover.jpg?param=128y128",
                feed.playlistSections.get(0).playlists.get(0).coverThumbPath);
        assertTrue(feed.albums.isEmpty());
        assertTrue(feed.sections.isEmpty());
    }

    @Test public void boundsPlaylistShelvesAndCards() {
        JsonObject[] cards = new JsonObject[25];
        for (int i = 0; i < cards.length; i++) cards[i] = resource("playlist", "" + (i + 1), "歌单" + i);
        JsonObject[] blocks = new JsonObject[20];
        for (int i = 0; i < blocks.length; i++) blocks[i] = block("STYLE_" + i, "曲风" + i, cards);
        NeteaseClient.HomeFeed feed = NeteaseClient.parseHomeFeed(root(blocks));
        assertEquals(12, feed.playlistSections.size());
        assertEquals(12, feed.playlistSections.get(0).playlists.size());
    }

    private static JsonObject song(long id) {
        JsonObject resource = resource("song", "" + id, "歌曲" + id);
        JsonObject song = new JsonObject();
        song.addProperty("id", id);
        song.addProperty("name", "歌曲" + id);
        JsonObject ext = new JsonObject();
        ext.add("songData", song);
        resource.add("resourceExtInfo", ext);
        return resource;
    }

    private static JsonObject resource(String type, String id, String title) {
        JsonObject resource = new JsonObject();
        resource.addProperty("resourceType", type);
        resource.addProperty("resourceId", id);
        JsonObject ui = new JsonObject();
        JsonObject mainTitle = new JsonObject();
        mainTitle.addProperty("title", title);
        ui.add("mainTitle", mainTitle);
        JsonObject image = new JsonObject();
        image.addProperty("imageUrl", "https://example.invalid/cover.jpg");
        ui.add("image", image);
        resource.add("uiElement", ui);
        return resource;
    }

    private static JsonObject block(String code, String title, JsonObject... resources) {
        JsonObject block = new JsonObject();
        block.addProperty("blockCode", code);
        JsonObject ui = new JsonObject();
        JsonObject subTitle = new JsonObject();
        subTitle.addProperty("title", title);
        ui.add("subTitle", subTitle);
        block.add("uiElement", ui);
        JsonArray list = new JsonArray();
        for (JsonObject resource : resources) list.add(resource);
        JsonObject creative = new JsonObject();
        creative.add("resources", list);
        JsonArray creatives = new JsonArray();
        creatives.add(creative);
        block.add("creatives", creatives);
        return block;
    }

    private static JsonObject root(JsonObject... blocks) {
        JsonArray list = new JsonArray();
        for (JsonObject block : blocks) list.add(block);
        JsonObject data = new JsonObject();
        data.add("blocks", list);
        JsonObject root = new JsonObject();
        root.addProperty("code", 200);
        root.add("data", data);
        return root;
    }
}
