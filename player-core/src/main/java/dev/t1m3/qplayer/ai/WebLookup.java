package dev.t1m3.qplayer.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Independent DeepSeek Search API, with the existing Tavily-compatible backend.
 * https://api-docs.deepseek.com/zh-cn/guides/search/
 * Failures are observable, but never prevent music recommendations.
 */
public final class WebLookup {
    private static final int RESULTS = 5;
    private WebLookup() {}

    public static final class Result {
        public final String context, status;
        public final int httpStatus;
        public final boolean ok;
        public final List<WebEvidence.Source> sources;
        Result(String context, String status, int httpStatus, boolean ok) {
            this(context, status, httpStatus, ok, java.util.Collections.emptyList());
        }
        Result(String context, String status, int httpStatus, boolean ok, List<WebEvidence.Source> sources) {
            this.context = context; this.status = status; this.httpStatus = httpStatus; this.ok = ok;
            this.sources = java.util.Collections.unmodifiableList(new ArrayList<>(sources));
        }
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static URI uri(String value) {
        try { return new URI(clean(value)); } catch (Exception ignored) { return URI.create(""); }
    }
    private static boolean official(String value) {
        return "api.deepseek.com".equalsIgnoreCase(uri(value).getHost());
    }
    public static boolean isDeepSeekSearch(String searchUrl) {
        String url = clean(searchUrl);
        return url.isEmpty() || official(url) || clean(uri(url).getPath()).endsWith("/tools/search");
    }
    /** Keep explicitly configured backends; upgrade the old keyless Tavily default. */
    public static String searchEndpoint(String searchUrl, String searchKey, String aiBaseUrl) {
        String url = clean(searchUrl);
        boolean oldDefault = (url.equals("https://api.tavily.com/search") || url.equals("https://api.tavily.com/search/"))
                && clean(searchKey).isEmpty();
        if (url.isEmpty() || oldDefault && official(aiBaseUrl))
            return official(aiBaseUrl) ? "https://api.deepseek.com/v1/tools/search" : "";
        if (official(url)) return "https://api.deepseek.com/v1/tools/search";
        return url;
    }
    public static String search(String searchUrl, String searchKey, String aiBaseUrl, String aiApiKey,
            String query, int timeoutMs) {
        LocalDate day = LocalDate.now(); java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        List<String> blocks = new ArrayList<>();
        for (String planned : newsQueries(query, day)) {
            String block = verified(searchUrl, searchKey, aiBaseUrl, aiApiKey, planned, timeoutMs, day, zone);
            blocks.add(block);
            JsonObject result = JsonParser.parseString(block).getAsJsonObject();
            if (result.getAsJsonArray("news").size() > 0 || result.get("status").getAsString().equals("unavailable")) break;
        }
        return WebEvidence.merge(blocks, day, zone);
    }

    /** Re-read at most two pages, then pass only dated, relevant source excerpts. */
    public static String verified(String searchUrl, String searchKey, String aiBaseUrl, String aiApiKey,
            String query, int timeoutMs, LocalDate day, java.time.ZoneId zone) {
        Result result = lookup(searchUrl, searchKey, aiBaseUrl, aiApiKey, query, timeoutMs, true);
        String endpoint = searchEndpoint(searchUrl, searchKey, aiBaseUrl);
        String key = clean(searchKey);
        if (key.isEmpty() && sameOrigin(endpoint, aiBaseUrl)) key = clean(aiApiKey);
        final String token = key;
        WebEvidence.Fetcher fetcher = isDeepSeekSearch(endpoint) && endpoint.endsWith("/tools/search") && !key.isEmpty()
                ? url -> fetch(endpoint.substring(0, endpoint.length() - 6) + "fetch", token, url, Math.min(timeoutMs, 3500)) : null;
        JsonObject organized = JsonParser.parseString(WebEvidence.organize(result.sources, query, day, zone, fetcher)).getAsJsonObject();
        organized.addProperty("search_status", result.status);
        organized.addProperty("status", !result.ok ? "unavailable" : organized.getAsJsonArray("news").size() == 0 ? "no_usable_sources" : "source_date_checked");
        return organized.toString();
    }
    /** Reports the real HTTP outcome, independently of the model's claims. */
    public static Result lookup(String searchUrl, String searchKey, String aiBaseUrl, String aiApiKey,
            String query, int timeoutMs) {
        return lookup(searchUrl, searchKey, aiBaseUrl, aiApiKey, query, timeoutMs, false);
    }
    private static Result lookup(String searchUrl, String searchKey, String aiBaseUrl, String aiApiKey,
            String query, int timeoutMs, boolean needContent) {
        String endpoint = searchEndpoint(searchUrl, searchKey, aiBaseUrl);
        if (endpoint.isEmpty()) return new Result("", "未配置搜索服务；DeepSeek 官方地址可复用 AI Key", 0, false);
        if (clean(query).isEmpty()) return new Result("", "请输入搜索内容", 0, false);
        boolean deepSeek = isDeepSeekSearch(endpoint);
        String key = clean(searchKey);
        // A relay's chat key is never sent to an unrelated search service.
        if (key.isEmpty() && deepSeek && sameOrigin(endpoint, aiBaseUrl)) key = clean(aiApiKey);
        if (key.isEmpty()) return new Result("", "未配置搜索 Key", 0, false);
        try {
            int timeout = Math.max(1000, Math.min(20_000, timeoutMs));
            if (!deepSeek) {
                List<WebEvidence.Source> sources = new WebSearchClient(endpoint, key, timeout).sources(limitQuery(query), RESULTS);
                String context = sources.stream().map(s -> s.title + "\n来源：" + s.url + "\n" + s.summary)
                        .collect(java.util.stream.Collectors.joining("\n\n"));
                return new Result(context, context.isEmpty() ? "搜索成功，暂无结果" : "联网搜索成功", 200, true, sources);
            }
            return deepSeek(endpoint, key, limitQuery(query), timeout, needContent);
        } catch (Exception failure) {
            return new Result("", "搜索未完成（" + failure.getClass().getSimpleName() + "），已使用个人偏好推荐", 0, false);
        }
    }
    private static boolean sameOrigin(String first, String second) {
        URI a = uri(first), b = uri(second);
        return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && clean(a.getScheme()).equalsIgnoreCase(clean(b.getScheme())) && a.getPort() == b.getPort();
    }
    static String limitQuery(String query) {
        String text = clean(query).replaceAll("\\s+", " ");
        return text.codePointCount(0, text.length()) <= 128 ? text
                : text.substring(0, text.offsetByCodePoints(0, 128));
    }
    /** Date filters come first so long requests cannot truncate them away. */
    public static String limitDatedQuery(String request, LocalDate day) {
        boolean today = clean(request).matches("(?is).*(今天|今日|当日|today).*");
        return limitQuery("after:" + day.minusDays(today ? 1 : 7) + " before:" + day.plusDays(1) + " " + clean(request));
    }
    public static List<String> newsQueries(String request, LocalDate day) {
        String topic = clean(request).replaceAll("(?i)(?:-?site|after|before):\\S+|\\bOR\\b", " ").replaceAll("\\s+", " ");
        String dates = "after:" + day.minusDays(topic.matches("(?is).*(今天|今日|当日|today).*") ? 1 : 7) + " before:" + day.plusDays(1) + " ";
        String cn = limitQuery(dates + "site:news.cn OR site:chinanews.com.cn " + topic);
        String global = limitQuery(dates + "site:billboard.com OR site:grammy.com " + topic);
        return topic.matches(".*[\\p{IsHan}].*") ? java.util.Arrays.asList(cn, global) : java.util.Arrays.asList(global, cn);
    }
    private static Result deepSeek(String endpoint, String key, String query, int timeout, boolean needContent) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("query", query); body.addProperty("count", RESULTS);
        body.addProperty("need_content", needContent);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int code = connection.getResponseCode();
            String response = read(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (code != 200) return new Result("", failureStatus(code), code, false);
            JsonArray results = JsonParser.parseString(response).getAsJsonObject().getAsJsonArray("results");
            if (results == null) return new Result("", "搜索响应格式不匹配（缺少 results）", code, false);
            StringBuilder out = new StringBuilder(); int count = 0;
            List<WebEvidence.Source> sources = new ArrayList<>();
            for (JsonElement element : results) {
                if (!element.isJsonObject()) continue;
                JsonObject row = element.getAsJsonObject();
                String title = string(row, "title"), summary = string(row, "summary");
                if (summary.isEmpty()) summary = string(row, "snippet");
                if (title.isEmpty() && summary.isEmpty()) continue;
                out.append(++count).append(". ").append(clip(title, 180)).append('\n');
                out.append("来源：").append(clip(string(row, "url"), 600)).append('\n');
                String date = string(row, "date");
                if (!date.isEmpty()) out.append("发布时间：").append(date).append('\n');
                out.append(clip(summary, 800)).append('\n');
                int authority = -1;
                try { authority = row.get("authority").getAsInt(); } catch (Exception ignored) {}
                sources.add(new WebEvidence.Source(title, string(row, "url"), date, summary,
                        clip(string(row, "content"), 18000), authority));
                if (count >= RESULTS) break;
            }
            return new Result(out.toString().trim(), count == 0 ? "搜索成功，暂无结果" : "联网搜索成功 · " + count + " 条来源", code, true, sources);
        } finally { connection.disconnect(); }
    }
    private static String fetch(String endpoint, String key, String url, int timeout) throws IOException {
        if (WebEvidence.canonicalUrl(url).isEmpty()) return "";
        JsonObject request = new JsonObject(); request.addProperty("url", url);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Math.max(1000, timeout)); connection.setReadTimeout(Math.max(1000, timeout));
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream out = connection.getOutputStream()) { out.write(request.toString().getBytes(StandardCharsets.UTF_8)); }
            if (connection.getResponseCode() != 200) return "";
            return WebEvidence.clip(string(JsonParser.parseString(read(connection.getInputStream())).getAsJsonObject(), "content"), 18000);
        } finally { connection.disconnect(); }
    }
    private static String failureStatus(int code) {
        String reason = code == 401 ? "Key 无效" : code == 402 ? "余额不足" : code == 429 ? "请求过于频繁"
                : code == 404 ? "搜索接口不存在" : "服务暂不可用";
        return "联网搜索失败 · HTTP " + code + " · " + reason;
    }
    private static String clip(String value, int length) { return value.length() <= length ? value : value.substring(0, length); }
    private static String string(JsonObject row, String key) {
        JsonElement value = row.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }
    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0;) {
                out.write(buffer, 0, n);
                if (out.size() > 2_000_000) throw new IOException("Search response too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    public static boolean wantsFreshness(String request) {
        if (request == null) return false;
        String text = request.toLowerCase(Locale.ROOT);
        String[] cues = {"最新", "最近", "今年", "本月", "本周", "这周", "现在", "当前", "近期", "今天", "今日", "天气",
                "新歌", "新专辑", "新发行", "刚发", "刚出", "刚上", "刚发行", "榜单", "排行", "热歌", "热榜",
                "流行", "火", "爆", "音乐事件", "音乐新闻", "trending", "new release", "latest", "weather", "today"};
        for (String cue : cues) if (text.contains(cue)) return true;
        return text.matches(".*\\b20(2[4-9]|3\\d)\\b.*");
    }
    public static List<String> queriesFor(String request, List<String> historySample) {
        List<String> out = new ArrayList<>();
        String base = clean(request);
        if (!base.isEmpty()) out.add(limitQuery(base));
        if (historySample != null) for (String row : historySample) {
            int dash = row == null ? -1 : row.indexOf(" - ");
            if (dash > 0 && dash + 3 < row.length()) {
                out.add(limitQuery(row.substring(dash + 3).trim() + " 新歌 最新 " + LocalDate.now())); break;
            }
        }
        return out;
    }
}
