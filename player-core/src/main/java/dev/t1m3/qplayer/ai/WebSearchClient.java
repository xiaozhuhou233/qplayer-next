package dev.t1m3.qplayer.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small Android-compatible Tavily search client. The key is supplied by the user. */
public final class WebSearchClient {
    private static final Gson GSON = new Gson();
    private final String endpoint;
    private final String apiKey;
    private final int timeoutMs;

    public WebSearchClient(String endpoint, String apiKey, int timeoutMs) {
        this.endpoint = endpoint == null || endpoint.trim().isEmpty()
                ? "https://api.tavily.com/search" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeoutMs = Math.max(5_000, timeoutMs);
    }

    public String search(String query, int maxResults) throws IOException {
        if (apiKey.isEmpty() || query == null || query.trim().isEmpty()) return "";
        JsonObject body = new JsonObject();
        body.addProperty("api_key", apiKey);
        body.addProperty("query", query.trim());
        body.addProperty("search_depth", "basic");
        body.addProperty("topic", "general");
        body.addProperty("max_results", Math.max(1, Math.min(10, maxResults)));
        body.addProperty("include_answer", false);
        body.addProperty("include_raw_content", false);
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String response = read(in);
        if (code < 200 || code >= 300) throw new IOException("联网搜索失败（HTTP " + code + "）");
        return formatResults(response);
    }

    private static String formatResults(String response) {
        try {
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            JsonArray results = root.getAsJsonArray("results");
            if (results == null) return "";
            StringBuilder out = new StringBuilder();
            for (JsonElement element : results) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String title = text(item, "title");
                String content = text(item, "content");
                String url = text(item, "url");
                if (!title.isEmpty() || !content.isEmpty()) {
                    out.append("标题：").append(title).append('\n');
                    out.append("摘要：").append(content).append('\n');
                    if (!url.isEmpty()) out.append("来源：").append(url).append('\n');
                    out.append('\n');
                }
            }
            return out.toString().trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) >= 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
