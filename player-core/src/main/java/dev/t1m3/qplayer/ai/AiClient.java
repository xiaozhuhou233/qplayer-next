package dev.t1m3.qplayer.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** OpenAI-compatible chat client; works with DeepSeek, OpenAI, Qwen gateways and custom APIs. */
public final class AiClient {
    public static final class ResponseFormatException extends IOException {
        public ResponseFormatException(String message) { super(message); }
    }
    private static final Gson GSON = new Gson();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutMs;
    private volatile boolean jsonResponseSupported = true;

    public AiClient(String baseUrl, String apiKey, String model, int timeoutMs) {
        // 中转站地址形态不唯一（裸域名 / 带 /v1 / 带尾斜杠 / 直接是完整端点）：
        // 统一由 AiEndpoint 归一，避免拼出连不上的 URL —— 那是「AI 不出结果」的常见真因。
        this.baseUrl = AiEndpoint.normalizeBase(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
        this.timeoutMs = Math.max(5_000, timeoutMs);
    }

    public String chat(String system, String user) throws IOException {
        return chat(system, user, 0.2d);
    }

    /**
     * One turn at a caller-chosen {@code temperature}. The playlist path uses this to run
     * <b>warm</b> ({@link AiReference#temperature}) — the user asked for the generated songs to have
     * randomness (「ai 算法生成的歌曲要有随机性」), and a greedy 0.2 answers the same request with the
     * same list every time. Every other caller keeps {@link #chat(String, String)}, which is the old
     * 0.2: the transition chooser must answer the same way twice for the same pair.
     */
    public String chat(String system, String user, double temperature) throws IOException {
        boolean requestJson = jsonResponseSupported;
        try {
            return chatOnce(system, user, requestJson, temperature);
        } catch (IOException e) {
            // Some OpenAI-compatible gateways reject response_format while
            // accepting the rest of /chat/completions. Retry once without it.
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (requestJson && (message.contains("response_format") || message.contains("json_object")
                    || message.contains("unsupported") || message.contains("不支持"))) {
                jsonResponseSupported = false;
                return chatOnce(system, user, false, temperature);
            }
            throw e;
        }
    }

    /**
     * One turn with no {@code response_format} hint at all, for prompts whose
     * answer is not a JSON document — a single word or line, say. Worth its own
     * entry point rather than a call to {@link #chat}: several providers (DeepSeek
     * among them) reject {@code response_format: json_object} outright unless the
     * prompt happens to contain the word "json", which {@link #chat} can only
     * discover by paying for the rejected request first. Nothing about the rest of
     * the request differs — same model, same temperature, same timeout, same
     * endpoint and key.
     */
    public String chatPlain(String system, String user) throws IOException {
        return chatOnce(system, user, false, 0.2d);
    }

    /**
     * The playlist JSON, tolerant of compact arrays with an optional third reason
     * ({@code {"name":…,"songs":[["title","artist","reason"]]}}) and the older object
     * shape — a model may answer in either, and the older shape costs nothing to accept.
     */
    static AiPlaylistResult parseResult(String raw) {
        AiPlaylistResult recovered = new AiPlaylistResult();
        if (raw == null) return recovered;
        // Parse balanced JSON values independently. A preamble containing braces
        // must not corrupt the final JSON; truncated output can retain complete rows.
        for (int start = 0; start < raw.length(); start++) {
            char first = raw.charAt(start);
            if (first != '{' && first != '[') continue;
            int end = jsonEnd(raw, start);
            if (end < 0) continue;
            try {
                JsonElement value = JsonParser.parseString(raw.substring(start, end + 1));
                AiPlaylistResult playlist = findPlaylist(value, 0);
                if (!playlist.songs.isEmpty()) return playlist;
                AiPlaylistResult.Song song = parseSong(value);
                if (song != null) {
                    recovered.songs.add(song);
                    start = end;
                }
            } catch (RuntimeException ignored) { }
        }
        return recovered;
    }

    private static int jsonEnd(String raw, int start) {
        java.util.ArrayDeque<Character> stack = new java.util.ArrayDeque<>();
        boolean quoted = false, escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') stack.push(c);
            else if (c == '}' || c == ']') {
                if (stack.isEmpty() || stack.pop() != (c == '}' ? '{' : '[')) return -1;
                if (stack.isEmpty()) return i;
            }
        }
        return -1;
    }

    private static AiPlaylistResult findPlaylist(JsonElement value, int depth) {
        AiPlaylistResult result = new AiPlaylistResult();
        if (value == null || depth > 5) return result;
        JsonArray rows = null;
        if (value.isJsonArray()) rows = value.getAsJsonArray();
        else if (value.isJsonObject()) {
            JsonObject obj = value.getAsJsonObject();
            String name = firstPrimitive(obj, "name", "playlistName", "playlist_name", "歌单名", "歌单名称");
            if (!name.isEmpty()) result.playlistName = name;
            result.summary = firstPrimitive(obj, "summary", "description", "推荐理由", "简介");
            JsonElement suggestions = obj.get("suggestions");
            if (suggestions != null && suggestions.isJsonArray()) for (JsonElement suggestion : suggestions.getAsJsonArray()) {
                if (!suggestion.isJsonPrimitive() || !suggestion.getAsJsonPrimitive().isString()) continue;
                String followup = suggestion.getAsString().trim();
                if (!followup.isEmpty() && followup.length() <= 80 && !result.suggestions.contains(followup)) result.suggestions.add(followup);
                if (result.suggestions.size() == 3) break;
            }
            for (String key : new String[] { "songs", "tracks", "歌曲", "曲目" }) {
                if (obj.has(key) && obj.get(key).isJsonArray()) { rows = obj.getAsJsonArray(key); break; }
            }
            if (rows == null) for (String key : new String[] { "playlist", "data", "result", "playlists", "歌单" }) {
                AiPlaylistResult nested = findPlaylist(obj.get(key), depth + 1);
                if (!nested.songs.isEmpty()) return nested;
            }
        }
        if (rows != null) {
            for (JsonElement row : rows) {
                AiPlaylistResult.Song song = parseSong(row);
                if (song != null) result.songs.add(song);
            }
            // Some models wrap the one requested playlist in an array. Keep the
            // first group intact instead of merging several unrelated playlists.
            if (result.songs.isEmpty()) for (JsonElement row : rows) {
                AiPlaylistResult nested = findPlaylist(row, depth + 1);
                if (!nested.songs.isEmpty()) return nested;
            }
        }
        return result;
    }

    private static AiPlaylistResult.Song parseSong(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        AiPlaylistResult.Song song = new AiPlaylistResult.Song();
        if (value.isJsonArray()) {
            JsonArray row = value.getAsJsonArray();
            if (row.size() > 0 && row.get(0).isJsonPrimitive()) song.title = row.get(0).getAsString().trim();
            if (row.size() > 1) song.artist = artistText(row.get(1));
            if (row.size() > 2 && row.get(2).isJsonPrimitive()) song.reason = row.get(2).getAsString().trim();
        } else if (value.isJsonObject()) {
            JsonObject obj = value.getAsJsonObject();
            song.title = firstPrimitive(obj, "title", "歌名", "name", "song", "song_name", "songName", "song_title", "歌曲名", "歌曲名称");
            for (String key : new String[] { "artist", "歌手", "singer", "artists", "by", "artist_name", "artistName" }) {
                song.artist = artistText(obj.get(key));
                if (!song.artist.isEmpty()) break;
            }
            song.reason = firstPrimitive(obj, "reason", "理由", "note", "recommendReason", "recommendation_reason", "推荐原因", "推荐理由");
        } else if (value.isJsonPrimitive()) {
            String text = value.getAsString().trim();
            for (String sep : new String[] { " - ", " – ", " — ", "—", "–", "-", "|", "·" }) {
                int at = text.indexOf(sep);
                if (at > 0 && at + sep.length() < text.length()) {
                    song.title = text.substring(0, at).trim();
                    song.artist = text.substring(at + sep.length()).trim();
                    break;
                }
            }
        }
        return song.title.isEmpty() || song.artist.isEmpty() ? null : song;
    }

    private static String artistText(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString().trim();
        if (value.isJsonObject()) return firstPrimitive(value.getAsJsonObject(), "name", "artist", "歌手");
        if (value.isJsonArray()) {
            java.util.List<String> artists = new java.util.ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) {
                if (item.isJsonArray()) continue;
                String artist = artistText(item);
                if (!artist.isEmpty()) artists.add(artist);
            }
            return String.join(" / ", artists);
        }
        return "";
    }

    /** The first of {@code keys} that the object carries as a string. */
    private static String firstPrimitive(com.google.gson.JsonObject o, String... keys) {
        for (String key : keys) {
            com.google.gson.JsonElement el = o.get(key);
            if (el != null && el.isJsonPrimitive() && !el.getAsString().trim().isEmpty()) return el.getAsString().trim();
        }
        return "";
    }

    /** Compatibility overload; the provider controls output limits for all requests. */
    @Deprecated
    public String chatPlain(String system, String user, int ignoredTokenLimit) throws IOException {
        return chatOnce(system, user, false, 0.7d);
    }

    private String chatOnce(String system, String user, boolean requestJson) throws IOException {
        return chatOnce(system, user, requestJson, 0.2d);
    }

    private String chatOnce(String system, String user, boolean requestJson, double temperature)
            throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("temperature", Math.max(0d, Math.min(2d, temperature)));
        // Omit token-limit fields in normal, fallback and recovery requests.
        // Output length follows the configured provider/model's defaults.
        // Ask OpenAI-compatible providers to enforce a JSON object response.
        // Providers that do not support this field are handled by the caller's
        // Markdown fallback parser.
        if (requestJson) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            root.add("response_format", responseFormat);
        }
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        JsonObject s = new JsonObject(); s.addProperty("role", "system"); s.addProperty("content", system); messages.add(s);
        JsonObject u = new JsonObject(); u.addProperty("role", "user"); u.addProperty("content", user); messages.add(u);
        root.add("messages", messages);
        byte[] body = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(AiEndpoint.chatCompletions(baseUrl)).openConnection();
        try {
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Content-Type", "application/json");
        if (!apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + apiKey);
        try (OutputStream out = c.getOutputStream()) { out.write(body); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String response = read(in);
        if (code < 200 || code >= 300) {
            // 中转站把真实原因写在响应体里（模型名不存在 / 余额不足 / Key 无效），
            // 用 AiEndpoint 统一抽取，别让它丢掉。
            throw new IOException("AI 请求失败：" + AiEndpoint.describeFailure(code, response, ""));
        }
        try {
            JsonObject parsed = JsonParser.parseString(response).getAsJsonObject();
            JsonObject choice = parsed.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonElement content = choice.getAsJsonObject("message").get("content");
            StringBuilder text = new StringBuilder();
            if (content != null && content.isJsonPrimitive()) text.append(content.getAsString());
            else if (content != null && content.isJsonArray()) for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject()) text.append(firstPrimitive(part.getAsJsonObject(), "text"));
            }
            if (text.toString().trim().isEmpty()) {
                String finish = firstPrimitive(choice, "finish_reason");
                throw new ResponseFormatException("length".equals(finish)
                        ? "AI 输出达到长度上限，但未返回歌曲内容，请重试或更换模型"
                        : "AI 服务返回了空白内容，请重试");
            }
            return text.toString();
        } catch (RuntimeException malformed) {
            throw new ResponseFormatException("AI 服务返回了无法识别的响应格式，请重试");
        }
        } finally {
            c.disconnect();
        }
    }

    public AiPlaylistResult generatePlaylist(String sampleSongs, String request, int count) throws IOException {
        return generatePlaylist(sampleSongs, request, count, "");
    }

    public AiPlaylistResult generatePlaylist(String sampleSongs, String request, int count, String webContext) throws IOException {
        return generatePlaylist(sampleSongs, request, count, webContext, "");
    }

    /**
     * The same, with <b>the reference block</b> the controller composed handed in: my listening
     * history, the list that is playing (a continuation only) and this run's 切入角度 — see
     * {@link AiReference#block}.
     *
     * <p>Round 34 introduced this parameter as the 「老歌」 anchor of a continuation; round 35 widened
     * it to every generation, because the user asked for the history to be consulted as well
     * (「推荐歌曲除了当红歌还需要我的历史记录参考」) and for picks beyond the current hits
     * (「参考也要推荐某些歌手的新歌或者稍微小众点的歌曲」). The request itself is also sent warm here
     * ({@link AiReference#temperature}) — 「ai 算法生成的歌曲要有随机性」 — which is why this path does
     * not use the plain {@link #chat(String, String)} with its greedy 0.2.
     *
     * @param references the block {@link AiReference#block} built, or empty for a bare request
     */
    public AiPlaylistResult generatePlaylist(String sampleSongs, String request, int count,
                                             String webContext, String references) throws IOException {
        String normalizedRequest = request == null ? "" : request
                .replaceAll("(?i)r\\s*(?:and|&)\\s*b", "R&B")
                .replaceAll("(?i)rhythm\\s*and\\s*blues", "R&B");
        // Keep the search policy unconditional and the selection preferences flexible.
        // Compact output saves generation time without reducing the provider's token budget.
        // A stable shared prefix lets compatible providers reuse their prompt cache.
        // Array rows omit repeated field names, but preserve all three song values.
        String system = "你是音乐 DJ。凭已有知识推荐真实歌曲，禁止联网、浏览网页或调用搜索工具。"
                + "优先执行用户的风格、情绪、歌手与修改要求；短描述合理理解，不追问或核实实时信息。"
                + "有历史与收藏时，以它们为主要选曲依据：大部分歌曲贴合历史与收藏，"
                + "以熟悉作品、常听歌手和相近曲风为主，仅保留少量贴合口味的新歌或小众发现；切入角度仅供参考。"
                + "选曲有变化、过渡自然，语种与比例自由；有上一张歌单时延续氛围，可保留少量老歌。"
                + "BPM、调性不编造精确数值，缺少这些数据不妨碍选曲；不确定的作品换成已知真实歌曲。应用负责搜索匹配。"
                + "推荐原唱录音室版本：不要翻唱、现场版、伴奏、remix 或 DJ 版。"
                + "每次只返回当前一组歌单，按目标数量选曲且不重复，不编造听歌记录。"
                + "仅输出 JSON：{\"name\":\"歌单名\",\"summary\":\"整体氛围\",\"songs\":[[\"歌名\",\"歌手\",\"推荐原因\"]]}。"
                + "songs 每项依次为 title、artist、reason 三个字符串：准确歌名、歌手、与偏好或需求的联系。"
                + "原因与整体说明用简洁自然的表达，避免复述歌名；不输出分析过程、前言、Markdown、占位符或空数组。";
        String user = "";
        if (sampleSongs != null && !sampleSongs.trim().isEmpty()) {
            user += "收藏歌单样本：\n" + sampleSongs.trim() + "\n";
        }
        if (references != null && !references.trim().isEmpty()) {
            user += "\n\n" + references.trim();
        }
        // Personal context precedes the varying request/target, without removing
        // any history, collection, continuation or user-supplied requirements.
        user += "\n本次音乐需求：\n" + normalizedRequest + "\n目标歌曲数：" + count + " 首。";
        // 2026-10-18：需要实时信息的请求由应用联网检索（WebLookup），结果作为参考资料
        // 附在用户回合里；模型只负责从资料里挑真实歌曲。
        if (webContext != null && !webContext.trim().isEmpty()
                && !webContext.contains("KNOWLEDGE_BASE_ONLY")) {
            user += "\n\n联网资料（应用检索到的实时结果，优先从中挑真实歌名和歌手，"
                    + "不要把网页标题或说明当成歌曲）：\n" + webContext;
        }
        // Warm, and a fresh draw for every attempt: 「ai 算法生成的歌曲要有随机性」. A retry after an
        // unusable answer therefore comes back with different songs rather than the same ones again.
        // Keep the provider's output budget and reasoning settings at their defaults.
        String raw = chat(system, user,
                AiReference.temperature(java.util.concurrent.ThreadLocalRandom.current())).trim();
        String originalResponse = raw;
        AiPlaylistResult result = parseResult(raw);
        if (result.songs.isEmpty()) result = null;
        // Be tolerant of providers that return fewer/more items than requested.
        // The resolver will use every valid item instead of discarding an
        // otherwise useful recommendation set.
        if (result == null || result.songs == null || result.songs.isEmpty()) {
            // Do not spend another request when the model already returned a
            // readable ranked list. This is common for chart questions.
            result = parseRankedMarkdown(originalResponse);
        }
        if (result == null || result.songs == null || result.songs.isEmpty()) {
            // An empty/refusal/plan response contains nothing to extract. Recover
            // using the original request and personal references, not just that failed text.
            String normalizeSystem = system + "本次为格式恢复：上次回答未解析出歌曲。"
                    + "已有具体歌曲时整理为上述 JSON；没有具体歌曲时，重新按原始需求和个人样本选曲。"
                    + "不要只重复方案、空数组或解释。保留可用歌曲的逐曲原因。";
            String normalized = chat(normalizeSystem, user + "\n以下是上次未解析成功的回答，仅供恢复参考：\n" + originalResponse);
            result = parseResult(normalized);
            if (result.songs.isEmpty()) result = null;
            if (result == null || result.songs == null || result.songs.isEmpty()) {
                // The second pass can also ignore JSON mode. Reuse the same
                // local extractor instead of reporting a false empty result.
                result = parseRankedMarkdown(normalized);
            }
        }
        if (result == null || result.songs == null || result.songs.isEmpty())
            throw new ResponseFormatException("AI 未返回可解析的歌名和歌手，格式恢复仍未成功，请重试");
        return result;
    }

    /** Fallback for models that answer chart requests with a readable Markdown
     * ranking instead of the requested JSON, e.g. `1. **Artist** - *Title*`. */
    private static AiPlaylistResult parseRankedMarkdown(String raw) {
        AiPlaylistResult result = new AiPlaylistResult();
        if (raw == null || raw.trim().isEmpty()) return result;
        Pattern p = Pattern.compile("^\\s*(?:\\d+[.)、:]|[-•])\\s+(.+?)\\s+[-–—:]\\s+(.+?)\\s*$");
        for (String line : raw.split("\\r?\\n")) {
            Matcher m = p.matcher(line);
            if (!m.find()) continue;
            AiPlaylistResult.Song song = new AiPlaylistResult.Song();
            song.artist = cleanSongPart(m.group(1), false);
            song.title = cleanSongPart(m.group(2), true);
            if (!song.artist.isEmpty() && !song.title.isEmpty()) {
                song.reason = "榜单推荐";
                result.songs.add(song);
            }
        }
        result.summary = "从 AI 返回的榜单中提取歌曲";
        return result;
    }

    private static String cleanSongPart(String value, boolean title) {
        if (value == null) return "";
        String s = value.replace("**", "").replace("__", "").replace("*", "")
                .replaceAll("\\[([^]]+)\\]\\([^)]*\\)", "$1")
                .trim();
        if (title) {
            s = s.replaceFirst("\\s*[（(](?=.*(?:连续|第\\d+|周|榜|排名|位|week|weeks))[^）)]*[）)]\\s*$", "");
        }
        return s.trim();
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n; while ((n = input.read(b)) >= 0) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
