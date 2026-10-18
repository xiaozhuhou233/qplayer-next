package dev.t1m3.qplayer.netease;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class NeteasePlaylistEditingTest {
    @Test public void authoritativeIdsKeepTheServerOrderIncludingUnavailableSongs() throws Exception {
        assertEquals(Arrays.asList(9L, 2L, 5L), NeteaseClient.parsePlaylistTrackIds(JsonParser.parseString(
                "{\"code\":200,\"playlist\":{\"tracks\":[],\"trackIds\":[{\"id\":9},{\"id\":2},{\"id\":5}]}}").getAsJsonObject()));
        assertEquals(Collections.emptyList(), NeteaseClient.parsePlaylistTrackIds(JsonParser.parseString(
                "{\"code\":200,\"playlist\":{\"trackIds\":[]}}").getAsJsonObject()));
    }

    @Test public void failedOrPartialReadsCannotBeMistakenForAnEmptySavedPlaylist() throws Exception {
        for (String json : Arrays.asList("{\"code\":403}", "{\"code\":200}",
                "{\"code\":200,\"playlist\":{\"tracks\":[]}}",
                "{\"code\":200,\"playlist\":{\"trackIds\":[{\"id\":1},{\"id\":1}]}}")) {
            try { NeteaseClient.parsePlaylistTrackIds(JsonParser.parseString(json).getAsJsonObject()); fail(json); }
            catch (IOException expected) { }
        }
    }
}
