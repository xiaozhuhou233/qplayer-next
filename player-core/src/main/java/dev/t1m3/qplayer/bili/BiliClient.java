package dev.t1m3.qplayer.bili;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import dev.t1m3.qplayer.util.Logger;

/**
 * Bilibili API client, modelled on PiliPlusX's {@code lib/http} layer.
 *
 * <p>Everything the phone-facing endpoints need:
 * <ul>
 *   <li><b>wbi signing</b> — {@code /x/web-interface/nav} hands out two key fragments;
 *       reordering their bytes through the fixed permutation table yields the mixin
 *       key that {@code w_rid} is hashed with. Search and playurl both require it.</li>
 *   <li><b>TV QR login</b> — the h5 QR flow needs the web risk-control dance, so
 *       third-party clients use {@code /x/passport-tv-login/…} instead: the app shows
 *       a QR, the phone app scans it, and the poll returns SESSDATA/bili_jct cookies.</li>
 *   <li><b>search</b>, <b>multi-part lists</b> and <b>playurl</b> — the last one asks
 *       for {@code fnval=1}, i.e. a progressive FLV with the audio already muxed in,
 *       which is what the platform player can actually open (no DASH muxer here).</li>
 * </ul>
 */
public class BiliClient {

    private static final Gson GSON = new Gson();
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com/";

    /** The published TV client credentials every third-party client signs its QR
     *  requests with (PiliPlusX carries the same pair in lib/http/constants.dart). */
    private static final String TV_APPKEY = "4409e2ce8ffd12b8";
    private static final String TV_APPSEC = "59b43e04ad6965f34319062b478f83dd";

    /** PiliPlusX's mixin permutation, applied to the two key fragments from nav. */
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private final int timeoutMs;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private String mixinKey;
    private long mixinKeyAt;

    public BiliClient(int timeoutMs) {
        this.timeoutMs = Math.max(5_000, timeoutMs);
    }

    // ---------------------------------------------------------------- session

    public boolean isLoggedIn() {
        return cookies.containsKey("SESSDATA") || cookies.containsKey("access_token");
    }

    public String cookieHeader() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }

    /** Restore a previously persisted cookie header ("k=v; k=v"). */
    public void setCookieHeader(String header) {
        cookies.clear();
        if (header == null) return;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) cookies.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
    }

    public void logout() {
        cookies.clear();
    }

    /** Validate a private candidate before replacing an existing account during import. */
    public boolean verifyLogin() throws IOException {
        if (!isLoggedIn()) return false;
        JsonObject response = request("https://api.bilibili.com/x/web-interface/nav", null, false);
        JsonObject data = response.has("data") && response.get("data").isJsonObject()
                ? response.getAsJsonObject("data") : null;
        return data != null && data.has("isLogin") && data.get("isLogin").getAsBoolean();
    }

    private void absorbCookies(HttpURLConnection c) {
        List<String> raw = c.getHeaderFields().get("Set-Cookie");
        if (raw == null) {
            raw = c.getHeaderFields().get("set-cookie");
        }
        if (raw == null) return;
        for (String cookie : raw) {
            int eq = cookie.indexOf('=');
            int semi = cookie.indexOf(';');
            if (eq <= 0) continue;
            String key = cookie.substring(0, eq).trim();
            String value = cookie.substring(eq + 1, semi > eq ? semi : cookie.length()).trim();
            if ("access_token".equals(key) && "deleted".equals(value)) continue;
            cookies.put(key, value);
        }
    }

    // ------------------------------------------------------------- transport

    private JsonObject request(String url, Map<String, Object> query, boolean signed)
            throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        if (query != null) params.putAll(query);
        if (signed) {
            params.put("wts", System.currentTimeMillis() / 1000L);
            params.put("w_rid", wbiSign(params));
        }
        StringBuilder full = new StringBuilder(url);
        char sep = '?';
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() == null) continue;
            full.append(sep).append(encode(e.getKey())).append('=').append(encode(String.valueOf(e.getValue())));
            sep = '&';
        }
        HttpURLConnection c = (HttpURLConnection) new URL(full.toString()).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", signed || isLoggedIn() ? REFERER : "https://www.bilibili.com");
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setRequestProperty("Accept-Encoding", "gzip, deflate");
        if (isLoggedIn()) c.setRequestProperty("Cookie", cookieHeader());
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : decompress(c, c.getInputStream());
        String body = read(in);
        if (code < 200 || code >= 300) {
            throw new IOException("bili HTTP " + code + ": " + truncate(body));
        }
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        int apiCode = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : 0;
        if (apiCode != 0) {
            String message = obj.has("message") ? obj.get("message").getAsString() : ("code " + apiCode);
            throw new IOException("bili " + apiCode + ": " + message);
        }
        return obj;
    }

    private JsonObject post(String url, Map<String, Object> form) throws IOException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, Object> e : form.entrySet()) {
            if (e.getValue() == null) continue;
            if (body.length() > 0) body.append('&');
            body.append(encode(e.getKey())).append('=').append(encode(String.valueOf(e.getValue())));
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", REFERER);
        // Must mirror request(): without the session cookie a write goes out anonymous
        // and the server rejects it, which looked like favouriting silently doing
        // nothing. The csrf field in the body is tied to this same session.
        if (isLoggedIn()) c.setRequestProperty("Cookie", cookieHeader());
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : decompress(c, c.getInputStream());
        String text = read(in);
        if (code < 200 || code >= 300) throw new IOException("bili HTTP " + code + ": " + truncate(text));
        JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
        int apiCode = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : 0;
        if (apiCode != 0) {
            String message = obj.has("message") ? obj.get("message").getAsString() : ("code " + apiCode);
            throw new IOException("bili " + apiCode + ": " + message);
        }
        return obj;
    }

    private static InputStream decompress(HttpURLConnection c, InputStream raw) throws IOException {
        String enc = c.getContentEncoding();
        if (enc == null) return raw;
        if (enc.contains("gzip")) return new GZIPInputStream(raw);
        if (enc.contains("deflate")) return new InflaterInputStream(raw);
        return raw;
    }

    // ------------------------------------------------------------------ wbi

    private String mixinKey() throws IOException {
        if (mixinKey != null && System.currentTimeMillis() - mixinKeyAt < 3_600_000L) return mixinKey;
        JsonObject nav = request("https://api.bilibili.com/x/web-interface/nav", null, false);
        if (!nav.has("data") || !nav.get("data").isJsonObject()) {
            throw new IOException("bili nav returned no data");
        }
        JsonObject img = nav.getAsJsonObject("data").getAsJsonObject("wbi_img");
        String imgUrl = img.get("img_url").getAsString();
        String subUrl = img.get("sub_url").getAsString();
        String raw = stem(imgUrl) + stem(subUrl);
        StringBuilder out = new StringBuilder();
        for (int index : MIXIN_KEY_ENC_TAB) {
            if (index < raw.length()) out.append(raw.charAt(index));
        }
        mixinKey = out.substring(0, Math.min(32, out.length()));
        mixinKeyAt = System.currentTimeMillis();
        return mixinKey;
    }

    private static String stem(String url) {
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String wbiSign(Map<String, Object> params) throws IOException {
        TreeMap<String, Object> sorted = new TreeMap<>(params);
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (e.getValue() == null) continue;
            if (query.length() > 0) query.append('&');
            query.append(encode(e.getKey())).append('=').append(encode(String.valueOf(e.getValue())));
        }
        return md5(query + mixinKey());
    }

    private static String encode(String value) {
        try {
            // Bilibili's own wbi spec filters these five characters after encoding.
            return URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("!", "%21")
                    .replace("'", "%27")
                    .replace("(", "%28")
                    .replace(")", "%29")
                    .replace("*", "%2A");
        } catch (Exception e) {
            return value;
        }
    }

    private static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String tvSign(Map<String, Object> params) {
        TreeMap<String, Object> sorted = new TreeMap<>(params);
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (e.getValue() == null) continue;
            if (query.length() > 0) query.append('&');
            query.append(e.getKey()).append('=').append(e.getValue());
        }
        return md5(query + TV_APPSEC);
    }

    // ------------------------------------------------------------- TV 扫码登录

    /** One QR code plus the id the poll needs. */
    public static final class QrCode {
        public final String url;
        public final String authCode;
        QrCode(String url, String authCode) {
            this.url = url;
            this.authCode = authCode;
        }
    }

    /** Ask for a login QR. The phone's bilibili app scans the {@link QrCode#url}. */
    public QrCode requestLoginQr() throws IOException {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("appkey", TV_APPKEY);
        form.put("local_id", "0");
        long ts = System.currentTimeMillis() / 1000L;
        form.put("ts", String.valueOf(ts));
        // The signature covers appkey/local_id/ts only — appsec never travels.
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("appkey", TV_APPKEY);
        signed.put("local_id", "0");
        signed.put("ts", String.valueOf(ts));
        form.put("sign", tvSign(signed));
        JsonObject obj = post(
                "https://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code", form);
        JsonObject data = obj.getAsJsonObject("data");
        return new QrCode(data.get("url").getAsString(), data.get("auth_code").getAsString());
    }

    /** Poll one QR. Returns 0 = waiting, 1 = scanned (confirm on the phone),
     *  2 = logged in (cookies absorbed), 3 = expired/refused. */
    public int pollLoginQr(String authCode) throws IOException {
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("appkey", TV_APPKEY);
        signed.put("local_id", "0");
        signed.put("auth_code", authCode);
        long ts = System.currentTimeMillis() / 1000L;
        signed.put("ts", String.valueOf(ts));
        Map<String, Object> form = new LinkedHashMap<>(signed);
        form.put("sign", tvSign(signed));
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://passport.bilibili.com/x/passport-tv-login/qrcode/poll").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", UA);
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, Object> e : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(encode(e.getKey())).append('=').append(encode(String.valueOf(e.getValue())));
        }
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        absorbCookies(c);
        String text = read(c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream());
        JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
        int code = obj.has("code") ? obj.get("code").getAsInt() : -1;
        switch (code) {
            case 0:
                JsonObject data = obj.getAsJsonObject("data");
                if (data != null && data.has("cookie_info")) {
                    JsonObject info = data.getAsJsonObject("cookie_info");
                    JsonArray list = info.getAsJsonArray("cookies");
                    for (JsonElement el : list) {
                        JsonObject ck = el.getAsJsonObject();
                        cookies.put(ck.get("name").getAsString(), ck.get("value").getAsString());
                    }
                }
                return 2;
            case 86038:
                return 3;
            case 86090:
                return 1;
            default:
                return 0;
        }
    }

    // --------------------------------------------------------------- 搜索

    /** One video search result, flattened the way the list rows need it. */
    public static final class BiliVideo {
        public String bvid = "";
        public long aid;
        public long cid;
        public String title = "";
        public String author = "";
        public String coverUrl = "";
        public String durationText = "";
        public long durationSeconds;
        public long playCount;
        public long danmakuCount;
    }

    /** {@code /x/web-interface/wbi/search/type?search_type=video}. */
    public List<BiliVideo> searchVideo(String keyword, int page) throws IOException {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("search_type", "video");
        q.put("keyword", keyword);
        q.put("page", String.valueOf(Math.max(1, page)));
        q.put("page_size", "20");
        JsonObject obj = request("https://api.bilibili.com/x/web-interface/wbi/search/type", q, true);
        List<BiliVideo> out = new ArrayList<>();
        if (!obj.has("data") || !obj.get("data").isJsonObject()) return out;
        JsonObject data = obj.getAsJsonObject("data");
        if (!data.has("result") || !data.get("result").isJsonArray()) return out;
        for (JsonElement el : data.getAsJsonArray("result")) {
            if (!el.isJsonObject()) continue;
            out.add(parseVideo(el.getAsJsonObject()));
        }
        return out;
    }

    private static BiliVideo parseVideo(JsonObject o) {
        BiliVideo v = new BiliVideo();
        v.bvid = string(o, "bvid");
        v.aid = number(o, "aid");
        v.title = stripTags(string(o, "title"));
        v.author = string(o, "author");
        v.coverUrl = https(string(o, "pic"));
        v.durationText = string(o, "duration");
        v.durationSeconds = parseDuration(v.durationText);
        v.playCount = number(o, "play");
        v.danmakuCount = number(o, "video_review");
        return v;
    }

    /** The video page's recommendations, in server ranking order. Endpoint/schema
     *  verified against PiliPlus lib/http/video.dart (relatedVideoList). */
    public List<BiliVideo> relatedVideos(String bvid) throws IOException {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("bvid", bvid);
        return parseRelatedVideos(request(
                "https://api.bilibili.com/x/web-interface/archive/related", q, false), bvid);
    }

    static List<BiliVideo> parseRelatedVideos(JsonObject response, String seedBvid) {
        Map<String, BiliVideo> videos = new LinkedHashMap<>();
        for (JsonElement entry : array(response, "data")) {
            if (!entry.isJsonObject()) continue;
            JsonObject item = entry.getAsJsonObject();
            if (number(item, "state") < 0L) continue;
            BiliVideo video = parseArchiveVideo(item);
            if (video.bvid.isEmpty() || video.bvid.equals(seedBvid)) continue;
            if (!videos.containsKey(video.bvid)) videos.put(video.bvid, video);
        }
        return new ArrayList<>(videos.values());
    }

    private static BiliVideo parseArchiveVideo(JsonObject item) {
        BiliVideo video = new BiliVideo();
        video.bvid = string(item, "bvid");
        video.aid = number(item, "aid");
        video.cid = number(item, "cid");
        video.title = stripTags(string(item, "title"));
        video.coverUrl = https(string(item, "pic"));
        video.author = string(object(item, "owner"), "name");
        if (video.author.isEmpty()) video.author = string(object(item, "author"), "name");
        video.durationSeconds = durationSeconds(item, "duration");
        video.playCount = number(object(item, "stat"), "view");
        video.danmakuCount = number(object(item, "stat"), "danmaku");
        return video;
    }

    public static final class BiliVideoDetails {
        public BiliVideo video;
        public final List<BiliPart> parts = new ArrayList<>();
        public final List<BiliVideo> episodes = new ArrayList<>();
    }

    /** View supplies both pages (multi-P) and ugc_season.sections[].episodes
     *  (different BV videos in one collection). A pagelist alone misses the latter. */
    public BiliVideoDetails videoDetails(String bvid) throws IOException {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("bvid", bvid);
        JsonObject response = request("https://api.bilibili.com/x/web-interface/view", q, false);
        if (!response.has("data") || !response.get("data").isJsonObject()) {
            throw new IOException("B站视频详情为空");
        }
        return parseVideoDetails(response.getAsJsonObject("data"), bvid);
    }

    static BiliVideoDetails parseVideoDetails(JsonObject data, String bvid) {
        BiliVideoDetails details = new BiliVideoDetails();
        details.video = parseArchiveVideo(data);
        if (details.video.bvid.isEmpty()) details.video.bvid = bvid;
        for (JsonElement entry : array(data, "pages")) {
            if (!entry.isJsonObject()) continue;
            JsonObject page = entry.getAsJsonObject();
            BiliPart part = new BiliPart();
            part.cid = number(page, "cid");
            if (part.cid <= 0L) continue;
            part.title = string(page, "part");
            part.durationSeconds = durationSeconds(page, "duration");
            details.parts.add(part);
        }
        if (details.video.cid <= 0L && !details.parts.isEmpty()) {
            details.video.cid = details.parts.get(0).cid;
        }
        Map<String, BiliVideo> episodes = new LinkedHashMap<>();
        for (JsonElement section : array(object(data, "ugc_season"), "sections")) {
            if (!section.isJsonObject()) continue;
            for (JsonElement entry : array(section.getAsJsonObject(), "episodes")) {
                if (!entry.isJsonObject()) continue;
                JsonObject episode = entry.getAsJsonObject();
                JsonObject archive = object(episode, "arc");
                if (number(archive, "state") < 0L) continue;
                BiliVideo video = parseArchiveVideo(archive);
                video.bvid = string(episode, "bvid");
                if (video.bvid.isEmpty()) continue;
                video.cid = number(episode, "cid");
                if (video.cid <= 0L) video.cid = number(object(episode, "page"), "cid");
                if (video.aid <= 0L) video.aid = number(episode, "aid");
                if (video.title.isEmpty()) video.title = stripTags(string(episode, "title"));
                if (video.author.isEmpty()) video.author = details.video.author;
                if (video.coverUrl.isEmpty()) video.coverUrl = details.video.coverUrl;
                if (video.durationSeconds <= 0L) {
                    video.durationSeconds = durationSeconds(object(episode, "page"), "duration");
                }
                String key = video.bvid + ":" + video.cid;
                if (!episodes.containsKey(key)) episodes.put(key, video);
            }
        }
        details.episodes.addAll(episodes.values());
        return details;
    }

    // ------------------------------------------------------- 分P / 合集 / 取流

    /** One part of a multi-part video (P1, P2 …) or one item of a collection. */
    public static final class BiliPart {
        public long cid;
        public String title = "";
        public long durationSeconds;
    }

    /** {@code /x/player/pagelist} — the P-list the previous/next buttons walk. */
    public List<BiliPart> parts(String bvid) throws IOException {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("bvid", bvid);
        JsonObject obj = request("https://api.bilibili.com/x/player/pagelist", q, false);
        List<BiliPart> out = new ArrayList<>();
        if (!obj.has("data") || !obj.get("data").isJsonArray()) return out;
        for (JsonElement el : obj.getAsJsonArray("data")) {
            if (!el.isJsonObject()) continue;
            JsonObject p = el.getAsJsonObject();
            BiliPart part = new BiliPart();
            part.cid = number(p, "cid");
            part.title = string(p, "part");
            part.durationSeconds = number(p, "duration");
            out.add(part);
        }
        return out;
    }

    /** One UP-authored chapter ({@code view_points} on the player page): a named
     *  range inside a single video part. */
    public static final class BiliChapter {
        public long fromMs;
        public long toMs;
        public String title = "";
    }

    /**
     * {@code /x/player/wbi/v2} — the UP's chapter markers for one part. Returns an
     * empty list for the many videos that have none, and for any failure: chapters
     * are decoration on the progress bar, never a reason to fail a play. Called
     * unsigned — this endpoint answers without the wbi signature (verified against
     * {@code code:0} responses), unlike playurl/search.
     */
    public List<BiliChapter> chapters(String bvid, long cid) throws IOException {
        if (bvid == null || bvid.isEmpty() || cid == 0L) return new ArrayList<>();
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("bvid", bvid);
        q.put("cid", String.valueOf(cid));
        JsonObject obj = request("https://api.bilibili.com/x/player/wbi/v2", q, false);
        List<BiliChapter> out = new ArrayList<>();
        if (!obj.has("data") || !obj.get("data").isJsonObject()) return out;
        JsonObject data = obj.getAsJsonObject("data");
        if (!data.has("view_points") || !data.get("view_points").isJsonArray()) return out;
        for (JsonElement el : data.getAsJsonArray("view_points")) {
            if (!el.isJsonObject()) continue;
            JsonObject p = el.getAsJsonObject();
            BiliChapter c = new BiliChapter();
            // from/to are seconds; read as doubles so a fractional value is not
            // truncated to the wrong second.
            c.fromMs = Math.round(decimal(p, "from") * 1000d);
            c.toMs = Math.round(decimal(p, "to") * 1000d);
            c.title = string(p, "content").trim();
            out.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------ favourites

    /** One of the logged-in user's favourite folders. */
    public static final class BiliFavFolder {
        /** The folder id — what {@code resource/list} wants as {@code media_id}. */
        public long mediaId;
        public String title = "";
        public int mediaCount;
        /** True when this folder already holds the video a picker was opened for;
         *  only meaningful on the {@code rid}/{@code type} variant of the listing. */
        public boolean containsItem;
        /** {@code attr} bit 0: 1 = private. */
        public boolean privateFolder;
    }

    /** One video inside a favourite folder, enough to queue and play it. */
    public static final class BiliFavItem {
        public String bvid = "";
        public long avid;
        public String title = "";
        public String author = "";
        public String coverUrl = "";
        public long durationSeconds;
        /** First part's cid, straight off {@code ugc.first_cid} — so queueing a folder
         *  needs no per-video pagelist call. */
        public long firstCid;
    }

    /** The logged-in mid, straight off the cookie bilibili puts it in. */
    public long selfMid() {
        return parseLong(cookies.get("DedeUserID"));
    }

    /** The CSRF token the write endpoints require; it is the {@code bili_jct} cookie and
     *  travels as an ordinary form field. */
    private String csrf() {
        String token = cookies.get("bili_jct");
        return token == null ? "" : token;
    }

    public boolean canWrite() {
        return !csrf().isEmpty() && selfMid() != 0L;
    }

    /**
     * {@code /x/v3/fav/folder/created/list-all} — the user's own folders.
     *
     * <p>Pass {@code rid} (an avid) with {@code type=2} to have each folder carry
     * whether it already contains that video, which is what a folder picker needs.
     *
     * <p>Careful: this endpoint answers {@code code:0} with {@code data:null} — not an
     * error — when the caller is not logged in or {@code up_mid} is not self, so a
     * "no folders" result has to be told apart from a silent failure.
     */
    public List<BiliFavFolder> favFolders(long rid) throws IOException {
        long mid = selfMid();
        if (mid == 0L) return new ArrayList<>();
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("up_mid", String.valueOf(mid));
        if (rid > 0L) {
            q.put("rid", String.valueOf(rid));
            q.put("type", "2");
        }
        JsonObject obj = request("https://api.bilibili.com/x/v3/fav/folder/created/list-all", q, false);
        List<BiliFavFolder> out = new ArrayList<>();
        if (!obj.has("data") || !obj.get("data").isJsonObject()) return out;
        JsonObject data = obj.getAsJsonObject("data");
        if (!data.has("list") || !data.get("list").isJsonArray()) return out;
        for (JsonElement el : data.getAsJsonArray("list")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            BiliFavFolder f = new BiliFavFolder();
            f.mediaId = number(o, "id");
            f.title = string(o, "title");
            f.mediaCount = (int) number(o, "media_count");
            f.containsItem = number(o, "fav_state") == 1L;
            f.privateFolder = (number(o, "attr") & 1L) == 1L;
            out.add(f);
        }
        return out;
    }

    /**
     * {@code /x/v3/fav/resource/list} — one page of a folder's videos.
     *
     * <p>{@code ps} is required and the API rejects anything above 40, so it is clamped
     * here rather than at each call site.
     */
    public List<BiliFavItem> favItems(long mediaId, int page, int ps) throws IOException {
        if (mediaId == 0L) return new ArrayList<>();
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("media_id", String.valueOf(mediaId));
        q.put("pn", String.valueOf(Math.max(1, page)));
        q.put("ps", String.valueOf(Math.min(Math.max(1, ps), 40)));
        q.put("platform", "web");
        JsonObject obj = request("https://api.bilibili.com/x/v3/fav/resource/list", q, false);
        List<BiliFavItem> out = new ArrayList<>();
        if (!obj.has("data") || !obj.get("data").isJsonObject()) return out;
        JsonObject data = obj.getAsJsonObject("data");
        // An empty folder reports medias as null rather than an empty array.
        if (!data.has("medias") || !data.get("medias").isJsonArray()) return out;
        for (JsonElement el : data.getAsJsonArray("medias")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            BiliFavItem item = new BiliFavItem();
            item.avid = number(o, "id");
            item.bvid = string(o, "bvid");
            if (item.bvid.isEmpty()) item.bvid = string(o, "bv_id");
            item.title = string(o, "title");
            item.coverUrl = https(string(o, "cover"));
            item.durationSeconds = number(o, "duration");
            if (o.has("upper") && o.get("upper").isJsonObject()) {
                item.author = string(o.getAsJsonObject("upper"), "name");
            }
            if (o.has("ugc") && o.get("ugc").isJsonObject()) {
                item.firstCid = number(o.getAsJsonObject("ugc"), "first_cid");
            }
            if (!item.bvid.isEmpty()) out.add(item);
        }
        return out;
    }

    /** True when this folder has another page after {@code page}. */
    public boolean favHasMore(long mediaId, int page, int ps) throws IOException {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("media_id", String.valueOf(mediaId));
        q.put("pn", String.valueOf(Math.max(1, page)));
        q.put("ps", String.valueOf(Math.min(Math.max(1, ps), 40)));
        q.put("platform", "web");
        JsonObject obj = request("https://api.bilibili.com/x/v3/fav/resource/list", q, false);
        if (!obj.has("data") || !obj.get("data").isJsonObject()) return false;
        JsonObject data = obj.getAsJsonObject("data");
        return data.has("has_more") && data.get("has_more").getAsBoolean();
    }

    /** The numeric avid for a bvid — what {@code batch-deal} wants in {@code
     *  resources}. Queue tracks carry the bvid, so favouriting one needs this hop. */
    public long videoAid(String bvid) throws IOException {
        if (bvid == null || bvid.isEmpty()) return 0L;
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("bvid", bvid);
        JsonObject obj = request("https://api.bilibili.com/x/web-interface/view", q, false);
        if (!obj.has("data") || !obj.get("data").isJsonObject()) return 0L;
        return number(obj.getAsJsonObject("data"), "aid");
    }

    /**
     * {@code /x/v3/fav/resource/batch-deal} (POST) — add and remove the video from
     * folders in one call. {@code resources} carries {@code <avid>:2} (2 = UGC video);
     * the folder ids are comma-separated. Needs the {@code csrf} form field, which is
     * why this only works for a logged-in session.
     */
    public void favDeal(long avid, List<Long> addMediaIds, List<Long> delMediaIds) throws IOException {
        if (avid == 0L) return;
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("resources", avid + ":2");
        form.put("add_media_ids", joinIds(addMediaIds));
        form.put("del_media_ids", joinIds(delMediaIds));
        form.put("csrf", csrf());
        post("https://api.bilibili.com/x/v3/fav/resource/batch-deal", form);
    }

    /** Joins a snapshot of ids, or "" — copied because the callers pass live lists. */
    private static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (id == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    private static long parseLong(String value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * {@code /x/player/wbi/playurl} with {@code fnval=1}: a progressive stream with the
     * audio already muxed in, which is the only shape the platform media player can
     * open without a DASH muxer.
     */
    public String progressiveUrl(String bvid, long cid, int quality) throws IOException {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("bvid", bvid);
        q.put("cid", String.valueOf(cid));
        q.put("qn", String.valueOf(quality <= 0 ? 64 : quality));
        q.put("fnval", "1");
        q.put("fnver", "0");
        q.put("fourk", "1");
        JsonObject obj = request("https://api.bilibili.com/x/player/wbi/playurl", q, true);
        if (!obj.has("data") || !obj.get("data").isJsonObject()) return null;
        JsonObject data = obj.getAsJsonObject("data");
        if (data.has("durl") && data.get("durl").isJsonArray()) {
            JsonArray arr = data.getAsJsonArray("durl");
            if (arr.size() > 0) {
                JsonObject first = arr.get(0).getAsJsonObject();
                String base = string(first, "url");
                if (!base.isEmpty()) return base;
            }
        }
        // Fall back to the DASH video track: video only, but at least it plays.
        if (data.has("dash") && data.get("dash").isJsonObject()) {
            JsonObject video = data.getAsJsonObject("dash").getAsJsonArray("video").get(0)
                    .getAsJsonObject();
            String url = string(video, "baseUrl");
            if (url.isEmpty()) url = string(video, "base_url");
            return url.isEmpty() ? null : url;
        }
        return null;
    }

    // --------------------------------------------------------------- helpers

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static long durationSeconds(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return 0L;
        try {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                return value.getAsLong();
            }
            return parseDuration(value.getAsString());
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long number(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return 0L;
        try {
            return o.get(key).getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static double decimal(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return 0d;
        try {
            return o.get(key).getAsDouble();
        } catch (RuntimeException e) {
            return 0d;
        }
    }

    /** Search titles arrive with {@code <em class="keyword">} highlighting. */
    private static String stripTags(String value) {
        return value == null ? "" : value.replaceAll("<[^>]*>", "");
    }

    /** Search covers are protocol-relative ({@code //i0.hdslb.com/…}). */
    private static String https(String url) {
        if (url == null || url.isEmpty()) return "";
        return url.startsWith("//") ? "https:" + url : url;
    }

    private static long parseDuration(String text) {
        if (text == null || text.isEmpty()) return 0L;
        String[] parts = text.split(":");
        long seconds = 0L;
        for (String part : parts) {
            try {
                seconds = seconds * 60L + Long.parseLong(part.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return seconds;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = input.read(buf)) >= 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    /** Unused today, kept for the danmaku stage: fetch one segment's raw bytes. */
    public byte[] danmakuSegment(long cid, int segmentIndex) throws IOException {
        String url = "https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid=" + cid
                + "&segment_index=" + Math.max(1, segmentIndex);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", REFERER);
        if (isLoggedIn()) c.setRequestProperty("Cookie", cookieHeader());
        if (c.getResponseCode() >= 400) {
            Logger.warn("bili danmaku segment failed: HTTP {}", c.getResponseCode());
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static final List<String> UNUSED = Collections.emptyList();
}
