package dev.t1m3.qplayer.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The transport half of the cloud ambience bed: one POST of an OpenAI-compatible
 * {@code /chat/completions} document that carries audio both ways, and nothing else.
 *
 * <p>Its shape is {@link AiClient}'s, deliberately — the same {@link HttpURLConnection}, the same
 * {@code Authorization: Bearer} header, the same "read the error stream and throw with what the
 * service said" contract — so the app has one way of talking to a JSON service and not two. The
 * JSON itself is built and read by
 * {@link dev.t1m3.qplayer.audio.AceStepBed}, which is where the request's meaning lives.
 *
 * <p><b>Timeouts, and why they are these.</b> The cloud sits behind Cloudflare, whose origin read
 * timeout is 100 s: a call that works takes 21–34 s, and one that does not is a 504 at about 100 s.
 * So the read timeout is set just past that — a minute and fifty — which means a long call still
 * ends in Cloudflare's own answer rather than in a socket that is closed under it. Nothing here is
 * on a playback path: the caller runs this on its own thread and treats every failure as "there is
 * no bed for this pair".
 *
 * <p><b>The key.</b> Passed in, put in one header, and never logged, never written, never part of a
 * file name. There is no method here that returns it and no field that holds a copy of the body.
 */
public final class AceStepClient {

    /** How long the connection may take to open. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 20_000;

    /** How long the answer may take. Past Cloudflare's own 100 s origin read timeout on purpose:
     *  the 504 is a more useful thing to log than a socket we closed ourselves. */
    public static final int DEFAULT_READ_TIMEOUT_MS = 110_000;

    /** How much of a failure's body is kept for the message. A 4xx that echoes the request would
     *  otherwise put megabytes of base64 into a log line. */
    private static final int MAX_ERROR_CHARS = 1_500;

    private final String url;
    private final String apiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public AceStepClient(String url, String apiKey) {
        this(url, apiKey, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    public AceStepClient(String url, String apiKey, int connectTimeoutMs, int readTimeoutMs) {
        this.url = url == null ? "" : url;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.connectTimeoutMs = Math.max(1_000, connectTimeoutMs);
        this.readTimeoutMs = Math.max(1_000, readTimeoutMs);
    }

    /** Whether this client has anywhere to post to. */
    public boolean configured() {
        return !url.isEmpty();
    }

    /**
     * One request, answered with the response body or an {@link IOException} naming the status and
     * what the service said. The body is written with a fixed content length rather than buffered a
     * second time: it carries the whole buffer as base64, several megabytes of it.
     */
    public String post(String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("Content-Type", "application/json");
            // The answer is base64 text; asked for uncompressed because this connection does not
            // decompress anything for us, and a service that compressed anyway would be unreadable.
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (!apiKey.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = read(in);
            if (code < 200 || code >= 300) {
                throw new IOException("ACE-Step 请求失败（HTTP " + code + "）：" + abbreviate(response));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] buffer = new byte[1 << 16];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        while (flat.contains("  ")) flat = flat.replace("  ", " ");
        return flat.length() > MAX_ERROR_CHARS ? flat.substring(0, MAX_ERROR_CHARS) + "…" : flat;
    }
}
