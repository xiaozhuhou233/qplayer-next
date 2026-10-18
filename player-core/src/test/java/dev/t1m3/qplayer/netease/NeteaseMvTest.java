package dev.t1m3.qplayer.netease;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class NeteaseMvTest {
    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    @Test public void resolvesOnlyTheRequestedSongsMv() throws Exception {
        JsonObject response = json("{\"code\":200,\"songs\":[{\"id\":1,\"mv\":99},"
                + "{\"id\":2,\"mv\":0},{\"id\":3,\"mvid\":88},{\"id\":4,\"mv\":null}]}");
        assertEquals(99, NeteaseClient.songMvId(response, 1));
        assertEquals(0, NeteaseClient.songMvId(response, 2));
        assertEquals(88, NeteaseClient.songMvId(response, 3));
        assertEquals(0, NeteaseClient.songMvId(response, 4));
    }

    @Test public void transientLookupFailureIsNotTreatedAsNoMv() {
        for (String response : new String[]{"{\"code\":503}", "{\"code\":200,\"songs\":[]}",
                "{\"songs\":[{\"id\":2,\"mv\":9}]}"}) {
            try { NeteaseClient.songMvId(json(response), 1); fail("must allow a later retry"); }
            catch (IOException expected) { }
        }
    }

    @Test public void acceptsPlayableCdnUrlAndUpgradesLegacyHttp() throws Exception {
        assertEquals("https://vod.example.com/mv.mp4?token=abc", NeteaseClient.mvPlaybackUrl(
                json("{\"code\":200,\"data\":{\"code\":200,\"url\":\"http://vod.example.com/mv.mp4?token=abc\"}}")));
        assertEquals("https://vod.example.com/mv.mp4", NeteaseClient.mvPlaybackUrl(
                json("{\"data\":{\"url\":\"https://vod.example.com/mv.mp4\"}}")));
    }

    @Test public void unavailableOrInvalidVideoFallsBackToArtwork() throws Exception {
        for (String response : new String[]{"{}", "{\"data\":null}", "{\"data\":{\"url\":null}}",
                "{\"data\":{\"code\":404,\"url\":\"https://vod.example.com/a\"}}",
                "{\"data\":{\"url\":\"file:///tmp/video\"}}", "{\"data\":{\"url\":\"not a url\"}}"})
            assertEquals("", NeteaseClient.mvPlaybackUrl(json(response)));
    }
}
