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
import java.util.ArrayList;
import java.util.List;

/**
 * 联网检索，两个后端，一个接口。
 *
 * <p>用户 2026-10-18：「deepseek api 支持联网搜索了，所以你让 ai 如果碰到需要联网的问题就去联网」.
 * DeepSeek 的搜索<b>不</b>是 chat 请求里的一个开关，而是一个独立的工具接口
 * （{@code POST /v1/tools/search}，见 api-docs.deepseek.com/zh-cn/guides/search）：
 * 我们先用用户的同一个 Key 去搜，再把结果作为「参考资料」塞进 AI 的提示词。所以这里的
 * 两种后端只是入口不同，交给模型的东西完全一样：
 *
 * <ul>
 *   <li><b>DeepSeek</b>（{@code /tools/search}）：同一个 API Key、同一个地址前缀。
 *       响应里每条有 {@code title/url/summary/content}，{@code summary} 就是给模型读的长摘要。</li>
 *   <li><b>Tavily 兼容</b>（原有的 {@link WebSearchClient}）：用户自填的地址与 Key。</li>
 * </ul>
 *
 * <p>选哪一个只看用户填的「搜索 API 地址」：空、或指向 deepseek.com，就走 DeepSeek；
 * 其它地址走 Tavily 兼容。两者都没有可用的 Key 时返回空串 —— 调用方据此降级为
 * 「用模型自己的知识回答」，而不是整个请求失败。
 *
 * <p>另一条约定：<b>一次失败不等于失败</b>。搜索是锦上添花，任何异常都吞掉并返回空串，
 * 由调用方决定是否提示；生成歌单这件事本身不能被搜索拖垮。
 */
public final class WebLookup {

    /** 搜索请求里带多少条结果：够模型挑，又不会把提示词撑长。 */
    private static final int RESULTS = 5;

    private WebLookup() {}

    /**
     * {@code true} 当搜索地址应当理解为 DeepSeek 的搜索工具 —— 空地址（跟随 AI 自己的地址）
     * 或明确指向 deepseek.com。
     */
    public static boolean isDeepSeekSearch(String searchUrl) {
        if (searchUrl == null || searchUrl.trim().isEmpty()) return true;
        String s = searchUrl.trim().toLowerCase(java.util.Locale.ROOT);
        return s.contains("deepseek.com") || s.contains("/tools/search");
    }

    /**
     * 用给定的 Key 检索 {@code query}，返回已经排好版、可以直接拼进提示词的纯文本。
     * 任何失败都返回空串（见类注释）。空串 = 「没有检索到资料」，不是错误。
     */
    public static String search(String searchUrl, String searchKey, String aiBaseUrl, String aiApiKey,
                                String query, int timeoutMs) {
        if (query == null || query.trim().isEmpty()) return "";
        int timeout = Math.max(5_000, timeoutMs);
        try {
            if (isDeepSeekSearch(searchUrl)) {
                String key = searchKey != null && !searchKey.trim().isEmpty() ? searchKey : aiApiKey;
                if (key == null || key.trim().isEmpty()) return "";
                String base = AiEndpoint.chatCompletions(aiBaseUrl);          // …/chat/completions
                int at = base.lastIndexOf("/chat/completions");
                base = at > 0 ? base.substring(0, at) : base;
                return deepSeek(base + "/tools/search", key.trim(), query.trim(), timeout);
            }
            if (searchKey == null || searchKey.trim().isEmpty()) return "";
            return new WebSearchClient(searchUrl, searchKey, timeout).search(query.trim(), RESULTS);
        } catch (Throwable ignored) {
            // A search that fails must never take the generation down with it.
            return "";
        }
    }

    private static String deepSeek(String endpoint, String key, String query, int timeoutMs) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.addProperty("count", RESULTS);
        // The long summary is what a model reads; the full page text would bloat
        // the prompt for little gain.
        body.addProperty("need_content", false);
        byte[] bytes = new Gson().toJson(body).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        int code = c.getResponseCode();
        String response = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code < 200 || code >= 300) return "";
        JsonObject root = JsonParser.parseString(response).getAsJsonObject();
        JsonArray results = root.getAsJsonArray("results");
        if (results == null || results.size() == 0) return "";
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (JsonElement element : results) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            String title = string(row, "title");
            String url = string(row, "url");
            String summary = string(row, "summary");
            if (summary.isEmpty()) summary = string(row, "snippet");
            if (title.isEmpty() && summary.isEmpty()) continue;
            out.append(++index).append(". ").append(title);
            if (!url.isEmpty()) out.append("（").append(url).append("）");
            out.append('\n');
            if (!summary.isEmpty()) out.append(summary).append('\n');
            if (index >= RESULTS) break;
        }
        return out.toString().trim();
    }

    private static String string(JsonObject row, String key) {
        JsonElement el = row.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString().trim() : "";
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) >= 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 需要联网的问题：「最新 / 新歌 / 榜单 / 2026 / 现在流行…」这类模型知识可能过期的询问。 */
    public static boolean wantsFreshness(String request) {
        if (request == null) return false;
        String s = request.toLowerCase(java.util.Locale.ROOT);
        String[] cues = { "最新", "最近", "今年", "本月", "本周", "这周", "现在", "当前", "近期",
                "新歌", "新专辑", "新发行", "刚发", "刚出", "刚上", "刚发行", "榜单", "排行", "热歌", "热榜",
                "流行", "火", "爆", "trending", "new release", "latest" };
        for (String cue : cues) if (s.contains(cue)) return true;
        // A year in the request usually means "as of now".
        return s.matches(".*\\b20(2[4-9]|3\\d)\\b.*");
    }

    /** 检索词：把请求本身加上「歌单」，再补上「新歌 最新」让搜索引擎偏向时效。 */
    public static List<String> queriesFor(String request, List<String> historySample) {
        List<String> out = new ArrayList<>();
        String base = request == null ? "" : request.trim();
        if (!base.isEmpty()) {
            out.add(base + " 歌单 新歌 最新");
            out.add(base + " 热门歌曲 2026");
        }
        // The taste sample's most-played artist is the "what is new from them" lead.
        if (historySample != null) {
            for (String row : historySample) {
                int dash = row == null ? -1 : row.indexOf(" - ");
                if (dash > 0 && dash + 3 < row.length()) {
                    out.add(row.substring(dash + 3).trim() + " 新歌");
                    break;
                }
            }
        }
        return out;
    }
}
