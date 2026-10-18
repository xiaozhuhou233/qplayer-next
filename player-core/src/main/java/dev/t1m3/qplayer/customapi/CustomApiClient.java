package dev.t1m3.qplayer.customapi;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import dev.t1m3.qplayer.util.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Search + playback-url resolution against a user-configured custom API (see
 * {@link CustomApiConfig}). Stays on {@link HttpURLConnection} (no OkHttp), matching
 * the rest of player-core. Every failure mode (network, JSON, bad user-supplied
 * path) degrades to an empty result / null instead of throwing.
 */
public final class CustomApiClient {

    private CustomApiClient() {}

    /** Search {@code keyword} against {@code cfg.searchUrl}; empty list on any failure
     *  or if {@code cfg} isn't usable. */
    public static List<CustomSong> search(CustomApiConfig cfg, String keyword) {
        if (cfg == null || !cfg.isUsable() || keyword == null) return Collections.emptyList();
        String url = fillTemplate(cfg.searchUrl, "keyword", keyword);
        String body = get(url, parseHeaders(cfg.extraHeaders));
        if (body == null) return Collections.emptyList();
        try {
            JsonElement root = JsonParser.parseString(body);
            JsonElement listEl = JsonPath.resolve(root, cfg.searchListPath);
            if (listEl == null || !listEl.isJsonArray()) return Collections.emptyList();
            List<CustomSong> out = new ArrayList<>();
            for (JsonElement item : listEl.getAsJsonArray()) {
                CustomSong s = new CustomSong();
                s.id = JsonPath.resolveString(item, cfg.idPath);
                s.name = JsonPath.resolveString(item, cfg.namePath);
                s.artist = notBlank(cfg.artistPath) ? JsonPath.resolveString(item, cfg.artistPath) : null;
                s.album = notBlank(cfg.albumPath) ? JsonPath.resolveString(item, cfg.albumPath) : null;
                s.coverUrl = notBlank(cfg.coverPath) ? JsonPath.resolveString(item, cfg.coverPath) : null;
                s.coverThumbPath = s.coverUrl;
                if (notBlank(cfg.durationPath)) {
                    String secStr = JsonPath.resolveString(item, cfg.durationPath);
                    try {
                        if (secStr != null && !secStr.isEmpty()) s.durationMs = Long.parseLong(secStr.trim()) * 1000L;
                    } catch (NumberFormatException ignored) {
                        // Leave durationMs at 0 (unknown) rather than fail the whole row.
                    }
                }
                if (s.id != null && !s.id.isEmpty() && s.name != null && !s.name.isEmpty()) out.add(s);
            }
            return out;
        } catch (Exception e) {
            Logger.warn("custom-api search parse failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Resolve a playback URL for {@code id} against {@code cfg.urlUrl}; null on any
     *  failure or if {@code cfg} isn't usable. */
    public static String resolveUrl(CustomApiConfig cfg, String id) {
        if (cfg == null || !cfg.isUsable() || id == null || id.isEmpty()) return null;
        String url = fillTemplate(cfg.urlUrl, "id", id);
        String body = get(url, parseHeaders(cfg.extraHeaders));
        if (body == null) return null;
        try {
            JsonElement root = JsonParser.parseString(body);
            String resolved = JsonPath.resolveString(root, cfg.urlResultPath);
            return (resolved != null && !resolved.isEmpty()) ? resolved : null;
        } catch (Exception e) {
            Logger.warn("custom-api url parse failed: {}", e.getMessage());
            return null;
        }
    }

    /** Fetch plain LRC lyric text for {@code id} against {@code cfg.lyricUrl}; null
     *  if not configured, on any failure, or if {@code cfg} isn't otherwise usable.
     *  Lyrics are optional even when the rest of the config is set up. */
    public static String resolveLyric(CustomApiConfig cfg, String id) {
        if (cfg == null || !cfg.isUsable() || id == null || id.isEmpty()) return null;
        if (!notBlank(cfg.lyricUrl) || !notBlank(cfg.lyricResultPath)) return null;
        String url = fillTemplate(cfg.lyricUrl, "id", id);
        String body = get(url, parseHeaders(cfg.extraHeaders));
        if (body == null) return null;
        try {
            JsonElement root = JsonParser.parseString(body);
            String lrc = JsonPath.resolveString(root, cfg.lyricResultPath);
            return (lrc != null && !lrc.isEmpty()) ? lrc : null;
        } catch (Exception e) {
            Logger.warn("custom-api lyric parse failed: {}", e.getMessage());
            return null;
        }
    }

    private static String fillTemplate(String template, String key, String value) {
        String encoded;
        try {
            encoded = URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            encoded = value;
        }
        return template.replace("{" + key + "}", encoded);
    }

    private static Map<String, String> parseHeaders(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split(";")) {
            int c = pair.indexOf(':');
            if (c > 0) {
                String k = pair.substring(0, c).trim();
                String v = pair.substring(c + 1).trim();
                if (!k.isEmpty()) out.put(k, v);
            }
        }
        return out;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String get(String url, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (qplayer custom-api client)");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return null;
            String text = readAll(is);
            return (code >= 400) ? null : text;
        } catch (IOException e) {
            Logger.warn("custom-api request failed: {}", e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
