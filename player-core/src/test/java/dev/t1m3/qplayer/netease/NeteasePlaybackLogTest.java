package dev.t1m3.qplayer.netease;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class NeteasePlaybackLogTest {
    @Test public void startAndFinishUseDistinctEventsAndPreserveSource() {
        JsonObject start = event(NeteasePlaybackLog.body(123, 456, 0, "", true));
        assertEquals("startplay", start.get("action").getAsString());
        JsonObject data = start.getAsJsonObject("json");
        assertEquals(123, data.get("id").getAsLong());
        assertEquals("1", data.get("mainsiteWeb").getAsString());
        assertEquals("id=456", data.get("content").getAsString());
        assertFalse(data.has("time"));
        JsonObject finish = event(NeteasePlaybackLog.body(123, 456, 37, "ui", false));
        assertEquals("play", finish.get("action").getAsString());
        data = finish.getAsJsonObject("json");
        assertEquals(37, data.get("time").getAsLong());
        assertEquals("456", data.get("sourceId").getAsString());
        assertEquals("ui", data.get("end").getAsString());
        assertEquals("playend", event(NeteasePlaybackLog.body(123, 0, 37, "playend", false))
                .getAsJsonObject("json").get("end").getAsString());
    }

    @Test public void reportingIdentityDoesNotReplaceAuthentication() {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("MUSIC_U", "test-session"); header.put("deviceId", "stable-device");
        header.put("os", "iPhone OS");
        NeteasePlaybackLog.applyIdentity(header);
        assertEquals("osx", header.get("os"));
        assertEquals("test-session", header.get("MUSIC_U"));
        assertEquals("stable-device", header.get("deviceId"));
        assertEquals("https://clientlog.music.163.com", NeteasePlaybackLog.BASE);
        assertEquals(NeteaseApi.Transport.EAPI, NeteaseApi.WEBLOG.transport);
    }

    @Test public void rejectionIsNotSilentlyTreatedAsSuccessfulReporting() throws Exception {
        NeteasePlaybackLog.validate(JsonParser.parseString("{code:200,data:'success'}").getAsJsonObject());
        for (String response : new String[]{"{code:301}", "{code:403}", "{code:500}", "{}"}) {
            try { NeteasePlaybackLog.validate(JsonParser.parseString(response).getAsJsonObject()); fail(response); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("未接受")); }
        }
    }

    private static JsonObject event(Map<String, Object> body) {
        return JsonParser.parseString((String) body.get("logs")).getAsJsonArray().get(0).getAsJsonObject();
    }
}
