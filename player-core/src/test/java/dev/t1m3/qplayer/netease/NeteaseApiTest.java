package dev.t1m3.qplayer.netease;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards the api-enhanced endpoint/transport mapping used by QPlayer. */
public class NeteaseApiTest {

    @Test
    public void searchUsesCurrentCloudSearchDefinition() {
        assertEndpoint(NeteaseApi.CLOUD_SEARCH,
                "/api/cloudsearch/pc", NeteaseApi.Transport.EAPI);
        assertFalse(NeteaseApi.CLOUD_SEARCH.checkToken);
    }

    @Test
    public void catalogMatchesEnhancedTransportDefinitions() {
        assertEndpoint(NeteaseApi.WEBLOG, "/api/feedback/weblog", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.LISTEN_REPORT, "/api/content/activity/listen/data/report", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.SONG_URL_V1,
                "/api/song/enhance/player/url/v1", NeteaseApi.Transport.XEAPI);
        assertEndpoint(NeteaseApi.HOT_SEARCH_DETAIL,
                "/api/hotsearchlist/get", NeteaseApi.Transport.WEAPI);
        assertEndpoint(NeteaseApi.PERSONALIZED_PLAYLIST,
                "/api/personalized/playlist", NeteaseApi.Transport.WEAPI);
        assertEndpoint(NeteaseApi.PLAYLIST_DETAIL,
                "/api/v6/playlist/detail", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.SONG_DETAIL,
                "/api/v3/song/detail", NeteaseApi.Transport.WEAPI);
        assertEndpoint(NeteaseApi.MV_URL,
                "/api/song/enhance/play/mv/url", NeteaseApi.Transport.WEAPI);
        assertEndpoint(NeteaseApi.LYRIC_NEW,
                "/api/song/lyric/v1", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.PLAYLIST_TRACKS,
                "/api/playlist/manipulate/tracks", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.LIKE_LIST,
                "/api/song/like/get", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.RECOMMEND_SONGS,
                "/api/v3/discovery/recommend/songs", NeteaseApi.Transport.WEAPI);
        assertEndpoint(NeteaseApi.HOMEPAGE_BLOCK_PAGE,
                "/api/homepage/block/page", NeteaseApi.Transport.WEAPI);
        assertEndpoint(NeteaseApi.PERSONAL_FM,
                "/api/v1/radio/get", NeteaseApi.Transport.WEAPI);
        assertEndpoint(NeteaseApi.PERSONAL_FM_TRASH,
                "/api/radio/trash/add", NeteaseApi.Transport.WEAPI);
        assertEndpoint(NeteaseApi.PLAYMODE_INTELLIGENCE_LIST,
                "/api/playmode/intelligence/list", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.LISTEN_TOGETHER_ROOM_CREATE,
                "/api/listen/together/room/create", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.LISTEN_TOGETHER_STATUS,
                "/api/listen/together/status/get", NeteaseApi.Transport.WEAPI);
        assertEndpoint(NeteaseApi.LISTEN_TOGETHER_ROOM_CHECK,
                "/api/listen/together/room/check", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.LISTEN_TOGETHER_INVITATION_ACCEPT,
                "/api/listen/together/play/invitation/accept", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.LISTEN_TOGETHER_SYNC_PLAYLIST_GET,
                "/api/listen/together/sync/playlist/get", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.LISTEN_TOGETHER_SYNC_LIST_REPORT,
                "/api/listen/together/sync/list/command/report", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.LISTEN_TOGETHER_PLAY_COMMAND_REPORT,
                "/api/listen/together/play/command/report", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.LISTEN_TOGETHER_HEARTBEAT,
                "/api/listen/together/heartbeat", NeteaseApi.Transport.EAPI);
        assertEndpoint(NeteaseApi.LISTEN_TOGETHER_END,
                "/api/listen/together/end/v2", NeteaseApi.Transport.EAPI);
        assertTrue(NeteaseApi.PLAYLIST_SUBSCRIBE.checkToken);
        assertTrue(NeteaseApi.PLAYLIST_UNSUBSCRIBE.checkToken);
        assertTrue(NeteaseApi.QR_LOGIN_KEY.loginFlow);
        assertTrue(NeteaseApi.QR_LOGIN_CHECK.loginFlow);
        assertEquals("/api/v1/user/detail/1234", NeteaseApi.userDetail(1234L).path);
        assertEquals("/api/v1/artist/6452", NeteaseApi.artistDetail(6452L).path);
        assertEquals(NeteaseApi.Transport.WEAPI, NeteaseApi.artistDetail(6452L).transport);
        assertEquals("/api/artist/albums/6452", NeteaseApi.artistAlbums(6452L).path);
        assertEquals(NeteaseApi.Transport.WEAPI, NeteaseApi.artistAlbums(6452L).transport);
        assertEquals("/api/v1/album/32311", NeteaseApi.albumDetail(32311L).path);
        assertEquals(NeteaseApi.Transport.WEAPI, NeteaseApi.albumDetail(32311L).transport);
    }

    private static void assertEndpoint(NeteaseApi.Endpoint endpoint, String path,
            NeteaseApi.Transport transport) {
        assertEquals(path, endpoint.path);
        assertEquals(transport, endpoint.transport);
    }
}
