package dev.t1m3.qplayer.listening;

import com.google.gson.*;
import java.util.*;

/** Accept classification labels only for the exact requested songs. */
public final class ListeningGenreLabels {
    private ListeningGenreLabels() { }
    public static Map<String, String> parse(String raw, Set<String> requested) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) return result;
        int start = raw.indexOf('{'), end = raw.lastIndexOf('}');
        if (start < 0 || end < start) return result;
        try {
            JsonObject root = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
            JsonArray rows = root.getAsJsonArray("genres");
            if (rows == null) return result;
            for (JsonElement element : rows) {
                if (!element.isJsonArray()) continue;
                JsonArray row = element.getAsJsonArray();
                if (row.size() != 2 || !row.get(0).isJsonPrimitive() || !row.get(1).isJsonPrimitive()) continue;
                String key = row.get(0).getAsString(), label = row.get(1).getAsString().trim();
                if (!requested.contains(key) || label.isEmpty() || label.length() > 40 || label.contains("\n")) continue;
                if (label.equals("未知") || label.equals("未分类") || label.equalsIgnoreCase("unknown")) continue;
                if (label.equalsIgnoreCase("rnb") || label.equalsIgnoreCase("r&b") || label.equals("节奏布鲁斯")) label = "R&B";
                result.putIfAbsent(key, label);
            }
        } catch (RuntimeException ignored) { /* An unusable AI response never changes measured statistics. */ }
        return result;
    }
}
