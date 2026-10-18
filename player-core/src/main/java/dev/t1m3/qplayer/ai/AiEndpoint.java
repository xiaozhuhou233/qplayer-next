package dev.t1m3.qplayer.ai;

/**
 * One place that turns <b>whatever a relay's address looks like</b> into the URL the app
 * actually posts to.
 *
 * <p>Why this exists: a relay (中转站) is not one shape. People paste, and relays
 * document, at least four forms —
 *
 * <ul>
 *   <li>{@code https://api.deepseek.com} — bare host, no {@code /v1}</li>
 *   <li>{@code https://api.deepseek.com/v1} — the OpenAI-compatible base</li>
 *   <li>{@code https://my-relay.example.com/v1/} — trailing slash</li>
 *   <li>{@code https://my-relay.example.com/v1/chat/completions} — the full endpoint,
 *       which is what a relay's own docs usually print</li>
 * </ul>
 *
 * <p>Appending {@code "/chat/completions"} to each of those literally yields three
 * wrong URLs out of four, and every wrong one looks from inside the app exactly like
 * 「AI 不出结果」 — a connection error or a 404 with no useful message. So the rule is:
 * normalise the address to the base, then append the path once.
 *
 * <p>Also carries the two per-call knobs a relay needs: the timeout (relays are slower
 * than the first-party endpoints, and the settings slider must reach the AI DJ path
 * instead of the hard-coded 60 s it used) and the error text (a relay's own JSON body
 * says *why* — unknown model, no balance — and swallowing it is what makes debugging
 * guesswork).
 */
public final class AiEndpoint {

    private AiEndpoint() {}

    /** Only transport errors are retried; authentication and TLS failures stay explicit. */
    public static boolean isConnectionFailure(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof java.net.UnknownHostException
                    || error instanceof java.net.SocketException
                    || error instanceof java.net.SocketTimeoutException
                    || error instanceof java.io.EOFException) return true;
        }
        return false;
    }

    public static String connectionFailureMessage(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof java.net.UnknownHostException)
                return "域名解析失败，请检查网络、VPN/代理或服务地址后重试";
            if (error instanceof java.net.SocketTimeoutException)
                return "请求超时，请检查网络或在设置中增加 AI 请求超时后重试";
            if (error instanceof java.net.SocketException || error instanceof java.io.EOFException)
                return "网络连接中断，请恢复网络后重试剩余歌单";
        }
        return "";
    }

    /**
     * The URL to POST a chat completion to, from any of the accepted address forms.
     *
     * <p>Already containing {@code /chat/completions} is honoured as-is (minus a trailing
     * slash): relays print that full path in their docs, and re-appending it would 404.
     */
    public static String chatCompletions(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        // A pasted address can carry a fragment or a stray query string; neither belongs
        // in the path we build, and leaving one in produces a URL that cannot connect.
        int cut = base.indexOf('#');
        if (cut >= 0) base = base.substring(0, cut);
        cut = base.indexOf('?');
        if (cut >= 0) base = base.substring(0, cut);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.isEmpty()) return "/chat/completions";
        if (base.endsWith("/chat/completions")) return base;
        // Some relays document the endpoint without its own prefix, e.g. "/v1/chat".
        if (base.endsWith("/chat/completions/")) return base.substring(0, base.length() - 1);
        return base + "/chat/completions";
    }

    /** The base form of an address: no trailing slash, no full endpoint path. */
    public static String normalizeBase(String baseUrl) {
        String url = chatCompletions(baseUrl);
        int at = url.lastIndexOf("/chat/completions");
        return at > 0 ? url.substring(0, at) : url;
    }

    /**
     * The most useful text a failed call can give: the provider's own message when its
     * body carries one, otherwise the raw body, otherwise the exception text.
     *
     * <p>A relay answers a bad model name with {@code {"error":{"message":"…"}}} and a
     * dead key with a 401 body in the same shape; both are far more actionable than
     * "HTTP 401" alone.
     */
    public static String describeFailure(int httpStatus, String body, String fallback) {
        String message = extractErrorMessage(body);
        StringBuilder sb = new StringBuilder();
        if (message != null && !message.isEmpty()) {
            sb.append(message);
        } else if (body != null && !body.trim().isEmpty()) {
            sb.append(body.trim());
        } else if (fallback != null && !fallback.isEmpty()) {
            sb.append(fallback);
        }
        String text = sb.toString();
        if (text.length() > 600) text = text.substring(0, 600) + "…";
        if (httpStatus > 0) return "（HTTP " + httpStatus + "）" + text;
        return text;
    }

    /** {@code error.message} from an OpenAI-style error body, or null. */
    private static String extractErrorMessage(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            com.google.gson.JsonElement error = root.get("error");
            if (error != null && error.isJsonObject()) {
                com.google.gson.JsonElement m = error.getAsJsonObject().get("message");
                if (m != null && m.isJsonPrimitive()) return m.getAsString();
            }
            // Some relays answer a bare {"message":"…"} or {"msg":"…"}.
            for (String key : new String[] { "message", "msg", "detail" }) {
                com.google.gson.JsonElement m = root.get(key);
                if (m != null && m.isJsonPrimitive()) return m.getAsString();
            }
        } catch (RuntimeException ignored) {
            // Not JSON (an HTML error page from a proxy, say) — the caller shows it raw.
        }
        return null;
    }

    /** A timeout that relays can live with: never below 5 s, and 0/absent means the default. */
    public static int timeoutOrDefault(int configuredMs, int defaultMs) {
        return configuredMs > 0 ? Math.max(5_000, configuredMs) : defaultMs;
    }
}
