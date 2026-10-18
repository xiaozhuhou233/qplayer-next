package dev.t1m3.qplayer.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * A <b>raw</b> chat call, for the settings page's 「AI 对话测试」.
 *
 * <p>This exists because the AI DJ's generation path hides almost everything a person
 * debugging a gateway needs to see: it takes a playlist prompt, retries three times,
 * and reports one collapsed error. The tester sends <b>exactly one</b> request with the
 * user's own question as the entire conversation, and hands back everything the model
 * actually said — plus the HTTP status and the elapsed milliseconds — so a wrong model
 * name, a rejected field, a truncated budget or a slow relay is visible immediately
 * instead of being guessed at.
 *
 * <p>Deliberately a separate method rather than a flag on {@link AiClient#chat}: the
 * request it sends is the plainest possible one (a single user message, no
 * {@code response_format}, no temperature override beyond the caller's, the model named
 * by the user). Nothing here can change the playlist path — see the note in
 * {@code AiClient#chatOnce} about why that request must stay as it is.
 */
public final class AiChatProbe {

    private static final Gson GSON = new Gson();

    /** Everything the tester can show, including the failure cases. */
    public static final class Result {
        /** The assistant's text, or the error text when the call failed. */
        public String text = "";
        /** HTTP status, or 0 when the request never got a status. */
        public int httpStatus;
        /** Wall-clock milliseconds for the whole call. */
        public long elapsedMs;
        /** True when the model answered; false for any transport/HTTP/parse failure. */
        public boolean ok;
        /** Which model string was actually sent (echoed for the tester's benefit). */
        public String model = "";
    }

    private AiChatProbe() {}

    /**
     * One question, one answer. Never retries and never rewrites the question — that is
     * the point: what comes back is what the gateway does with this exact input.
     *
     * @param system an optional system message; empty sends a bare user turn
     */
    public static Result ask(String baseUrl, String apiKey, String model, int timeoutMs,
                             String system, String question) {
        Result out = new Result();
        out.model = model == null ? "" : model;
        long started = System.currentTimeMillis();
        try {
            JsonObject root = new JsonObject();
            root.addProperty("model", out.model);
            // No temperature: the gateway's own default is what a debugging view should
            // show, and the playlist path's warm draw is irrelevant to a plain question.
            JsonArray messages = new JsonArray();
            if (system != null && !system.trim().isEmpty()) {
                JsonObject s = new JsonObject();
                s.addProperty("role", "system");
                s.addProperty("content", system);
                messages.add(s);
            }
            JsonObject u = new JsonObject();
            u.addProperty("role", "user");
            u.addProperty("content", question == null ? "" : question);
            messages.add(u);
            root.add("messages", messages);

            String endpoint = AiEndpoint.chatCompletions(baseUrl);
            HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
            int timeout = AiEndpoint.timeoutOrDefault(timeoutMs, 60_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            byte[] body = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
            try (OutputStream stream = c.getOutputStream()) {
                stream.write(body);
            }
            out.httpStatus = c.getResponseCode();
            InputStream in = out.httpStatus >= 400 ? c.getErrorStream() : c.getInputStream();
            String response = read(in);
            out.elapsedMs = System.currentTimeMillis() - started;
            if (out.httpStatus < 200 || out.httpStatus >= 300) {
                // The raw body is the most useful thing here: relay gateways put the real
                // reason (unknown model, bad key, unsupported field) in it.
                out.text = AiEndpoint.describeFailure(out.httpStatus, response, "")
                        + "\n\n原始响应：\n" + response;
                out.ok = false;
                return out;
            }
            out.text = extractContent(response);
            out.ok = !out.text.isEmpty();
            if (out.text.isEmpty()) out.text = "（HTTP " + out.httpStatus + "，但响应里没有可读的 message.content）\n\n" + response;
        } catch (IOException e) {
            out.elapsedMs = System.currentTimeMillis() - started;
            out.ok = false;
            out.text = "请求失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (RuntimeException e) {
            out.elapsedMs = System.currentTimeMillis() - started;
            out.ok = false;
            out.text = "解析失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return out;
    }

    /** {@code choices[0].message.content}, or "" when the shape is not that. */
    private static String extractContent(String response) {
        try {
            JsonObject parsed = JsonParser.parseString(response).getAsJsonObject();
            JsonArray choices = parsed.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) return "";
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) return "";
            return message.get("content").getAsString();
        } catch (RuntimeException e) {
            return "";
        }
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
}
