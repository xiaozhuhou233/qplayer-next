package dev.t1m3.qplayer.netease;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class NeteaseDiscoveryTest {
    @Test public void endpointsMatchEnhancedModules() {
        assertEquals("/api/playlist/catalogue", NeteaseApi.PLAYLIST_CATALOGUE.path);
        assertEquals(NeteaseApi.Transport.EAPI, NeteaseApi.PLAYLIST_CATALOGUE.transport);
        assertEquals("/api/playlist/list", NeteaseApi.CATEGORY_PLAYLISTS.path);
        assertEquals(NeteaseApi.Transport.WEAPI, NeteaseApi.CATEGORY_PLAYLISTS.transport);
    }

    @Test public void preservesServerGenresGroupsAndOrderWhileSkippingBrokenRows() throws Exception {
        List<NeteaseDiscovery.Category> rows = NeteaseDiscovery.parseCategories(json(
                "{categories:{'0':'语种','1':'风格'},sub:[{name:'华语',category:0},{name:'电子',category:1,hot:true},"
                + "{name:'电子',category:1},{name:null,category:1},null,{name:'',category:1},{name:'特别风格',category:9}]}"));
        assertEquals(3, rows.size());
        assertEquals("华语", rows.get(0).name);
        assertEquals("语种", rows.get(0).group);
        assertEquals("电子", rows.get(1).name);
        assertEquals("风格", rows.get(1).group);
        assertTrue(rows.get(1).hot);
        assertEquals("其他", rows.get(2).group);
    }

    @Test public void usesExactCategoryNameAndBoundsPageRequest() {
        Map<String, Object> body = NeteaseDiscovery.playlistBody(" R&B/Soul ", -50, 500);
        assertEquals("R&B/Soul", body.get("cat"));
        assertEquals(0, body.get("offset"));
        assertEquals(60, body.get("limit"));
        assertEquals("hot", body.get("order"));
        assertEquals(true, body.get("total"));
        assertEquals("全部", NeteaseDiscovery.playlistBody(null, 0, 24).get("cat"));
    }

    @Test public void rawPageCountAdvancesOffsetDespiteDuplicatesOrInvalidCards() throws Exception {
        NeteaseDiscovery.PlaylistPage page = NeteaseDiscovery.parsePlaylists(json(
                "{playlists:[{id:41,name:'电子精选',coverImgUrl:'https://example.invalid/a.jpg',trackCount:12},"
                + "{id:41,name:'重复'},{id:'broken',name:'坏数据'},null,{id:0,name:'失效'}],more:true}"), 24, 5);
        assertEquals(1, page.playlists.size());
        assertEquals(41, page.playlists.get(0).id);
        assertEquals(29, page.nextOffset);
        assertTrue(page.more);
        assertEquals("https://example.invalid/a.jpg?param=320y320", page.playlists.get(0).coverThumbPath);
    }

    @Test public void emptyLastPageStopsPaginationAndMissingResponseIsAnError() throws Exception {
        NeteaseDiscovery.PlaylistPage page = NeteaseDiscovery.parsePlaylists(json("{playlists:[],more:true}"), 48, 24);
        assertFalse(page.more);
        assertEquals(48, page.nextOffset);
        try { NeteaseDiscovery.parsePlaylists(json("{code:200}"), 0, 24); fail("missing array accepted"); }
        catch (IOException expected) { }
        try { NeteaseDiscovery.parseCategories(json("{sub:[]}")); fail("invented empty catalogue"); }
        catch (IOException expected) { }
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
