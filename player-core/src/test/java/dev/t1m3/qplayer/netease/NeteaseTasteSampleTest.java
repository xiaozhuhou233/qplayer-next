package dev.t1m3.qplayer.netease;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class NeteaseTasteSampleTest {
    @Test public void directlyReturnedTracksAreBoundedEvenWhenServerIgnoresLimit() {
        StringBuilder json = new StringBuilder("{\"tracks\":[");
        for (int i = 1; i <= 200; i++) {
            if (i > 1) json.append(',');
            json.append("{\"id\":").append(i).append(",\"name\":\"曲目").append(i).append("\"}");
        }
        json.append("]}");
        JsonObject playlist = JsonParser.parseString(json.toString()).getAsJsonObject();
        List<NeteaseSong> sample = NeteaseClient.parseTasteTracks(playlist, 32, new Random(1));
        assertEquals(32, sample.size());
        assertTrue(sample.stream().anyMatch(song -> song.id > 32));
        assertEquals(200, playlist.getAsJsonArray("tracks").size());
    }

    @Test public void idOnlyLikedPlaylistsResolveJustASmallDistinctRandomBatch() {
        JsonObject playlist = JsonParser.parseString("{\"tracks\":[],\"trackIds\":["
                + "{\"id\":1},{\"id\":2},{\"id\":3},{\"id\":4},{\"id\":5},"
                + "{\"id\":1},{\"id\":0},{\"id\":null},{\"id\":\"bad\"},null]}").getAsJsonObject();
        assertTrue(NeteaseClient.parseTasteTracks(playlist, 3, new Random(1)).isEmpty());
        List<Long> ids = NeteaseClient.tasteTrackIds(playlist, 3, new Random(1));
        assertEquals(3, ids.size());
        assertEquals(3, new HashSet<>(ids).size());
        assertEquals(ids, NeteaseClient.tasteTrackIds(playlist, 3, new Random(1)));
        assertEquals(5, NeteaseClient.tasteTrackIds(playlist, 32, new Random(1)).size());
        assertTrue(NeteaseClient.tasteTrackIds(playlist, 0, new Random(1)).isEmpty());
    }
}
