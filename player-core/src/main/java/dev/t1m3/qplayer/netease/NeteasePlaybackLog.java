package dev.t1m3.qplayer.netease;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** The clientlog EAPI protocol used by api-enhanced's current scrobble module. */
final class NeteasePlaybackLog {
    static final String BASE = "https://clientlog.music.163.com";
    static final String USER_AGENT = "NeteaseMusic/3.1.10.5100 (Macintosh; macOS 15.5)";

    static Map<String, Object> body(long songId, long sourceId, long seconds, String end, boolean start) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", songId);
        payload.addProperty("type", "song");
        payload.addProperty("mainsite", "1");
        payload.addProperty("mainsiteWeb", "1");
        payload.addProperty("content", "id=" + Math.max(0, sourceId));
        if (!start) {
            payload.addProperty("wifi", 0);
            payload.addProperty("download", 0);
            payload.addProperty("time", Math.max(0, seconds));
            payload.addProperty("end", "playend".equals(end) ? "playend" : "ui");
            payload.addProperty("source", "list");
            payload.addProperty("sourceId", Long.toString(Math.max(0, sourceId)));
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("action", start ? "startplay" : "play");
        entry.add("json", payload);
        JsonArray logs = new JsonArray(); logs.add(entry);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("logs", logs.toString());
        return body;
    }

    static void applyIdentity(Map<String, Object> header) {
        // Request-local identity only: never change the account's stored cookies.
        header.put("os", "osx");
        header.put("osver", "15.5");
        header.put("appver", "3.1.10.5100");
        header.put("channel", "netease");
    }

    static void validate(JsonObject response) throws IOException {
        if (response == null || NeteaseListening.number(response, "code") != 200)
            throw new IOException("网易云未接受播放上报（" + (response == null ? -1 : NeteaseListening.number(response, "code")) + "）");
        // 200 acknowledges ingestion only. It is not proof that cloud statistics changed.
    }
}
