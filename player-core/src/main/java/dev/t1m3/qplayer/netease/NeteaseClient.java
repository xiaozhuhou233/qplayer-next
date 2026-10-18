package dev.t1m3.qplayer.netease;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.CredentialCipher;
import dev.t1m3.qplayer.store.CredentialKeyProtection;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.netease.dto.NeteaseAlbum;
import dev.t1m3.qplayer.netease.dto.NeteaseArtist;
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.util.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java port of the NetEase cloud-music client. Endpoint paths and transports
 * are selected centrally by {@link NeteaseApi}; feature wrappers never choose
 * weapi/eapi/xeapi themselves.
 *
 * <p>No external HTTP dependency on purpose — we stay on {@code HttpURLConnection}
 * so this works on mc 1.8.9 (Java 8) without adding jars to the standalone
 * classloader.
 */
public final class NeteaseClient {

    /** Singleton — one cookie jar per Haedus install. */
    public static final NeteaseClient INSTANCE = new NeteaseClient();

    private static final String BASE = "https://music.163.com";
    /** Unencrypted mobile API host (xeapi key registration only). */
    private static final String API_BASE = "https://interface.music.163.com";
    /** Mobile API host the official apps hit for eapi-encrypted endpoints. */
    private static final String EAPI_BASE = "https://interfacepc.music.163.com";
    /** Newest mobile host: risk-controlled writes (playlist subscribe, ...) the
     *  official Android app encrypts with "xeapi". */
    private static final String XEAPI_BASE = "https://interface3.music.163.com";
    /** Android client UA for the xeapi path — matches the api-enhanced reference
     *  config that's verified to clear risk control (code 200) on these endpoints. */
    private static final String ANDROID_UA =
            "NeteaseMusic/9.1.65.240927161425(9001065);Dalvik/2.1.0"
          + " (Linux; U; Android 14; 23013RK75C Build/UKQ1.230804.001)";
    private static final String XEAPI_APPVER = "9.1.65";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
          + " (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    /** User-Agent for the mobile (eapi) endpoints — matches osMap.iphone in the reference. */
    private static final String EAPI_UA = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)";
    /** APP_CONF.checkToken from NeteaseCloudMusicApi — the anti-cheat token the subscribe
     *  endpoint requires (sent both in the body and as the X-antiCheatToken header). */
    private static final String CHECK_TOKEN =
            "9ca17ae2e6ffcda170e2e6ee8af14fbabdb988f225b3868eb2c15a879b9a83d274a790ac8ff54a"
          + "97b889d5d42af0feaec3b92af58cff99c470a7eafd88f75e839a9ea7c14e909da883e83fb692a3"
          + "abdb6b92adee9e";
    /** A stable mainland-China client IP sent as X-Real-IP to relax risk control. */
    private static final String REAL_IP = randomChinaIp();

    private final Gson gson = new Gson();
    private final Map<String, String> cookies = new ConcurrentHashMap<>();
    /** Account-wide because a new PlaybackReport is built on every playAt(). */
    private final Map<Long, Long> lastPldSentAt = new ConcurrentHashMap<>();
    private final Path cookieFile;
    private final Path legacyCookieFile;
    private final CredentialCipher cookieCipher;

    /** Registered xeapi session (anti-crawler key exchange). Lazily fetched,
     *  cached for the process: publicKey (base64 X25519), version, sk. */
    private volatile String xeapiPubKey;
    private volatile String xeapiVersion;
    private volatile String xeapiSk;

    /** Sink for netease's own failure reasons (privacy, risk control, ...) so the UI can
     *  surface them as a toast. Set by the controller. */
    public interface ErrorListener {
        void onError(String message);
    }

    public enum CredentialEvent {
        ENCRYPTED,
        KEYSTORE_FALLBACK,
        KEYSTORE_READ_FAILED
    }

    public interface CredentialListener {
        void onCredentialEvent(CredentialEvent event);
    }

    private final ErrorNotices errorNotices = new ErrorNotices();
    private final Object credentialEventLock = new Object();
    private final List<CredentialEvent> pendingCredentialEvents = new ArrayList<>();
    private volatile CredentialListener credentialListener;
    private boolean credentialUnlockPending;
    private boolean persistedLoginCredential;
    private boolean fallbackNoticeSent;
    public void setErrorListener(ErrorListener l) {
        errorNotices.listener = l;
    }

    /** Background shelves use their own error state, not the foreground toast. */
    public void withoutErrorNotices(Runnable work) {
        errorNotices.withoutNotices(work);
    }

    static final class ErrorNotices {
        volatile ErrorListener listener;
        private final ThreadLocal<Boolean> background = new ThreadLocal<>();
        private String lastMessage;
        private long lastAt;

        void withoutNotices(Runnable work) {
            Boolean previous = background.get();
            background.set(Boolean.TRUE);
            try {
                work.run();
            } finally {
                if (previous == null) background.remove();
                else background.set(previous);
            }
        }

        synchronized void report(String message) {
            if (Boolean.TRUE.equals(background.get()) || message == null || message.isEmpty()) return;
            ErrorListener sink = listener;
            if (sink == null) return;
            long now = System.currentTimeMillis();
            if (message.equals(lastMessage) && now - lastAt < 2500) return;
            lastMessage = message;
            lastAt = now;
            sink.onError(message);
        }
    }

    /**
     * Credential loading happens in this singleton's constructor, before the UI
     * controller exists. Preserve those startup notices and flush them when the
     * controller attaches instead of losing a key-store failure in early startup.
     */
    public void setCredentialListener(CredentialListener listener) {
        List<CredentialEvent> queued;
        synchronized (credentialEventLock) {
            credentialListener = listener;
            if (listener == null || pendingCredentialEvents.isEmpty()) return;
            queued = new ArrayList<>(pendingCredentialEvents);
            pendingCredentialEvents.clear();
        }
        for (CredentialEvent event : queued) listener.onCredentialEvent(event);
    }

    /** Consume only after the server has confirmed that restored cookies logged in. */
    public boolean consumeCredentialUnlock() {
        synchronized (credentialEventLock) {
            boolean value = credentialUnlockPending;
            credentialUnlockPending = false;
            return value;
        }
    }

    private void markCredentialUnlockPending() {
        synchronized (credentialEventLock) {
            credentialUnlockPending = true;
        }
    }

    private void emitCredentialEvent(CredentialEvent event) {
        CredentialListener listener;
        synchronized (credentialEventLock) {
            listener = credentialListener;
            if (listener == null) {
                pendingCredentialEvents.add(event);
                return;
            }
        }
        listener.onCredentialEvent(event);
    }

    // Forward a server failure reason, collapsing duplicates fired within a short window
    // (one user action often hits playlist/detail twice, so a private playlist would
    // otherwise toast the same line twice).
    private void reportError(String message) {
        errorNotices.report(message);
    }

    private static String neteaseMessage(JsonObject obj) {
        for (String key : new String[]{"message", "msg"}) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                String s = obj.get(key).getAsString();
                if (s != null && !s.isEmpty()) return s;
            }
        }
        return null;
    }

    private NeteaseClient() {
        cookieFile = AppDirs.credentialsFile("netease-cookies.enc");
        legacyCookieFile = AppDirs.credentialsFile("netease-cookies.json");
        cookieCipher = new CredentialCipher(
                AppDirs.credentialsFile("credential-encryption.key"),
                CredentialKeyProtection.current());
        loadCookies(false);
    }

    /**
     * POST to {@code /weapi/<path>?csrf_token=<csrf>}. {@code json} is the
     * pre-encrypted JSON body the endpoint expects; we'll auto-inject
     * {@code csrf_token} from the cookie jar if the caller didn't supply
     * one. Returns the raw response body as UTF-8 text — caller parses.
     */
    private synchronized String weapiCall(String path, Map<String, Object> json) throws IOException {
        return weapiCall(path, json, cookies, true);
    }

    /**
     * WEAPI transport against an explicit cookie jar. Login imports use a
     * private candidate jar so an invalid browser cookie can never replace the
     * currently working session before the server has accepted it.
     */
    private String weapiCall(String path, Map<String, Object> json,
            Map<String, String> cookieJar, boolean persistSetCookies) throws IOException {
        String csrf = cookieJar.getOrDefault("__csrf", "");
        Map<String, Object> body = json == null ? new HashMap<String, Object>() : new HashMap<String, Object>(json);
        if (!body.containsKey("csrf_token")) body.put("csrf_token", csrf);

        String bodyJson = gson.toJson(body);
        Map<String, String> encrypted;
        try {
            encrypted = NeteaseCrypto.weapi(bodyJson);
        } catch (Exception e) {
            throw new IOException("weapi crypto failed: " + e.getMessage(), e);
        }

        String urlStr = BASE + "/weapi/" + path
                + "?csrf_token=" + urlEnc(csrf);
        String form = formEncode(encrypted);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", BASE);
            conn.setRequestProperty("Origin", BASE);
            // A mainland-China client IP relaxes Netease's risk control (the "当前环境
            // 异常" / 524 rejection on sensitive ops like favoriting).
            conn.setRequestProperty("X-Real-IP", REAL_IP);
            conn.setRequestProperty("X-Forwarded-For", REAL_IP);
            String cookieHeader = cookieHeader(cookieJar);
            if (!cookieHeader.isEmpty()) conn.setRequestProperty("Cookie", cookieHeader);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String resp;
            try {
                resp = readAll(is);
            } finally {
                if (is != null) { try { is.close(); } catch (IOException ignored) {} }
            }
            boolean cookieChanged = captureSetCookiesInto(
                    cookieJar, conn.getHeaderFields().get("Set-Cookie"));
            if (cookieChanged && persistSetCookies) saveCookies();

            if (code >= 400) {
                Logger.warn("Netease HTTP {} on /weapi/{}: {}", code, path, truncate(resp, 200));
                throw new IOException("HTTP " + code + ": " + truncate(resp, 200));
            }
            return resp;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * POST to the mobile {@code /eapi/<path>} host. {@code apiPath} is the
     * {@code /api/...} signing path the body is signed against (the request goes
     * to {@code /eapi/...} with the leading {@code /api} stripped). A device
     * {@code header} object is injected into the body and mirrored into the
     * Cookie header, exactly like the official apps. When {@code checkToken} is
     * set the anti-cheat token rides along in the header — required by
     * {@code playlist/subscribe}. Returns the raw response body as UTF-8.
     */
    private synchronized String eapiCall(String apiPath, Map<String, Object> json, boolean checkToken)
            throws IOException {
        ensureDeviceCookies();
        return eapiCall(apiPath, json, checkToken, cookies, true);
    }

    /** Search-only requests use a session snapshot and cannot write back stale login cookies. */
    private String eapiSearchCall(String apiPath, Map<String, Object> json) throws IOException {
        Map<String, String> session;
        synchronized (this) {
            ensureDeviceCookies();
            session = new LinkedHashMap<>(cookies);
        }
        return eapiCall(apiPath, json, false, session, false);
    }

    private String eapiCall(String apiPath, Map<String, Object> json, boolean checkToken,
            Map<String, String> session, boolean persistSetCookies) throws IOException {
        return eapiCall(apiPath, json, checkToken, session, persistSetCookies, false);
    }

    private String eapiCall(String apiPath, Map<String, Object> json, boolean checkToken,
            Map<String, String> session, boolean persistSetCookies, boolean clientLog) throws IOException {
        String csrf = session.getOrDefault("__csrf", "");
        String musicU = session.getOrDefault("MUSIC_U", "");

        // Mobile-client header: signed into the body AND sent as the Cookie. The
        // device fields mimic the iPhone client (matching EAPI_UA).
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("osver", "16.2");
        header.put("deviceId", session.getOrDefault("deviceId", ""));
        header.put("os", "iPhone OS");
        header.put("appver", "9.0.90");
        header.put("versioncode", "140");
        header.put("mobilename", "");
        header.put("buildver", Long.toString(System.currentTimeMillis() / 1000L));
        header.put("resolution", "1920x1080");
        header.put("__csrf", csrf);
        header.put("channel", "distribution");
        header.put("requestId", System.currentTimeMillis() + "_" + String.format(java.util.Locale.ROOT,
                "%04d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1000)));
        // api-enhanced puts the anti-cheat token in the header object, which becomes the
        // Cookie (request.js). NOTE: this is a STATIC, shared token — the official client
        // generates a valid device/session-bound one via its anti-cheat SDK, which we
        // can't replicate, so subscribe is inherently rate-limited under repeated use.
        if (checkToken) header.put("X-antiCheatToken", CHECK_TOKEN);
        if (!musicU.isEmpty()) header.put("MUSIC_U", musicU);
        if (clientLog) NeteasePlaybackLog.applyIdentity(header);

        Map<String, Object> body = json == null
                ? new HashMap<String, Object>() : new HashMap<String, Object>(json);
        body.put("header", header);

        String bodyJson = gson.toJson(body);
        Map<String, String> encrypted;
        try {
            encrypted = NeteaseCrypto.eapi(apiPath, bodyJson);
        } catch (Exception e) {
            throw new IOException("eapi crypto failed: " + e.getMessage(), e);
        }

        String tail = apiPath.startsWith("/api/") ? apiPath.substring(5) : apiPath;
        String urlStr = (clientLog ? NeteasePlaybackLog.BASE : EAPI_BASE) + "/eapi/" + tail;
        String form = formEncode(encrypted);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", clientLog ? NeteasePlaybackLog.USER_AGENT : EAPI_UA);
            conn.setRequestProperty("Referer", BASE);
            conn.setRequestProperty("X-Real-IP", REAL_IP);
            conn.setRequestProperty("X-Forwarded-For", REAL_IP);
            conn.setRequestProperty("Cookie", headerCookie(header));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String resp;
            try {
                resp = readAll(is);
            } finally {
                if (is != null) { try { is.close(); } catch (IOException ignored) {} }
            }
            if (persistSetCookies) captureSetCookies(conn.getHeaderFields().get("Set-Cookie"));

            if (code >= 400) {
                Logger.warn("Netease HTTP {} on /eapi/{}: {}", code, tail, truncate(resp, 200));
                throw new IOException("HTTP " + code + ": " + truncate(resp, 200));
            }
            return resp;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * POST to the newest mobile {@code /xeapi/<path>} host. {@code apiPath} is the
     * {@code /api/...} path the body is built against (request goes to
     * {@code /xeapi/...} with the leading {@code /api} stripped). {@code formBody} is
     * the URL-encoded request payload (e.g. {@code "id=123456"}). A one-off
     * anti-crawler session key is registered on first use ({@link #ensureXeapiSession})
     * and the request is encrypted into the {@code B}/{@code S}/{@code R} form fields;
     * the response is AES-decrypted back to JSON. Returns the JSON response text.
     */
    private synchronized String xeapiCall(String apiPath, String formBody) throws IOException {
        ensureDeviceCookies();
        ensureXeapiSession();
        String deviceId = cookies.getOrDefault("deviceId", "");
        String musicU = cookies.getOrDefault("MUSIC_U", "");

        Map<String, String> bsr;
        try {
            bsr = NeteaseCrypto.xeapi(formBody, xeapiPubKey, xeapiVersion, xeapiSk, "android");
        } catch (Exception e) {
            throw new IOException("xeapi crypto failed: " + e.getMessage(), e);
        }

        String tail = apiPath.startsWith("/api/") ? apiPath.substring(5) : apiPath;
        String urlStr = XEAPI_BASE + "/xeapi/" + tail;
        String form = formEncode(bsr);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");
            conn.setRequestProperty("User-Agent", ANDROID_UA);
            conn.setRequestProperty("X-Client-Enc-State", "ENCRYPTED");
            conn.setRequestProperty("x-aeapi", "true");
            conn.setRequestProperty("x-deviceid", deviceId);
            conn.setRequestProperty("x-os", "android");
            conn.setRequestProperty("x-osver", "16");
            conn.setRequestProperty("x-appver", XEAPI_APPVER);
            conn.setRequestProperty("x-sdeviceid", deviceId);
            conn.setRequestProperty("x-buildver", Long.toString(System.currentTimeMillis() / 1000L));
            if (!musicU.isEmpty()) conn.setRequestProperty("x-music-u", musicU);
            conn.setRequestProperty("X-Real-IP", REAL_IP);
            conn.setRequestProperty("X-Forwarded-For", REAL_IP);
            conn.setRequestProperty("Cookie", xeapiCookieHeader());

            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            byte[] raw;
            try {
                raw = readAllBytes(is);
            } finally {
                if (is != null) { try { is.close(); } catch (IOException ignored) {} }
            }
            captureSetCookies(conn.getHeaderFields().get("Set-Cookie"));

            if (code >= 400) {
                Logger.warn("Netease HTTP {} on /xeapi/{}: {} bytes", code, tail, raw.length);
                throw new IOException("HTTP " + code);
            }
            try {
                return NeteaseCrypto.xeapiResDecrypt(raw);
            } catch (Exception e) {
                throw new IOException("xeapi response decrypt failed: " + e.getMessage(), e);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * The only request entry point used by feature wrappers. The endpoint
     * catalogue owns the canonical path, encryption transport, anti-cheat
     * requirement and login-flow response semantics.
     */
    private JsonObject apiJson(NeteaseApi.Endpoint endpoint, Map<String, Object> body)
            throws IOException {
        return apiJson(endpoint, body, true);
    }

    private JsonObject apiJson(NeteaseApi.Endpoint endpoint, Map<String, Object> body,
            boolean reportErrors) throws IOException {
        return apiJson(endpoint, body, reportErrors, false);
    }

    private JsonObject apiJson(NeteaseApi.Endpoint endpoint, Map<String, Object> body,
            boolean reportErrors, boolean parallelSearch) throws IOException {
        if (parallelSearch && endpoint != NeteaseApi.CLOUD_SEARCH)
            throw new IllegalArgumentException("Only catalogue searches may bypass the session transport lock");
        String response;
        switch (endpoint.transport) {
            case WEAPI:
                response = weapiCall(endpoint.weapiPath(), body);
                break;
            case EAPI:
                response = parallelSearch ? eapiSearchCall(endpoint.path, body)
                        : eapiCall(endpoint.path, body, endpoint.checkToken);
                break;
            case XEAPI:
                response = xeapiCall(endpoint.path, xeapiForm(body));
                break;
            default:
                throw new IOException("endpoint requires a specialised direct request: " + endpoint.path);
        }

        JsonElement element = new JsonParser().parse(response);
        if (!element.isJsonObject()) {
            throw new IOException("not a JSON object from " + endpoint.path + ": "
                    + truncate(response, 200));
        }
        JsonObject object = element.getAsJsonObject();
        int code = object.has("code") && !object.get("code").isJsonNull()
                ? object.get("code").getAsInt() : 200;
        if (code != 200 && !endpoint.loginFlow) {
            // Include endpoint identity so an optional failure can be diagnosed
            // without exposing request bodies, account cookies or credentials.
            Logger.warn("Netease API {} code {}: {}", endpoint.path, code, neteaseMessage(object));
        }
        if (parallelSearch && code != 200)
            throw new IOException("网易云搜索失败（" + code + "）：" + neteaseMessage(object));
        if (code == 301 && isLoggedIn() && !endpoint.loginFlow) {
            Logger.warn("Netease: session expired (code 301) on {} — clearing cookies",
                    endpoint.path);
            clearCookies();
        } else if (code != 200 && reportErrors && !endpoint.loginFlow) {
            reportError(neteaseMessage(object));
        }
        return object;
    }

    private static String xeapiForm(Map<String, Object> body) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (body != null) {
            for (Map.Entry<String, Object> entry : body.entrySet()) {
                fields.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return formEncode(fields);
    }

    /**
     * Register a fresh xeapi anti-crawler session key (X25519 public key + sk +
     * version) via {@code /api/gorilla/anti/crawler/security/key/get}. Anonymous —
     * needs only a stable deviceId, no login. Cached for the process; a failure
     * leaves the cache empty so the next call retries.
     */
    private synchronized void ensureXeapiSession() throws IOException {
        if (xeapiPubKey != null && xeapiSk != null) return;
        String deviceId = cookies.getOrDefault("deviceId", "");
        String nonce = randomDigits(16);
        String ts = Long.toString(System.currentTimeMillis());
        String sig;
        try {
            sig = NeteaseCrypto.xeapiSign(ts, nonce);
        } catch (Exception e) {
            throw new IOException("xeapiSign failed: " + e.getMessage(), e);
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("appVersion", XEAPI_APPVER);
        form.put("currentKeyVersion", "");
        form.put("deviceId", deviceId);
        form.put("nonce", nonce);
        form.put("os", "android");
        form.put("requestType", "active");
        form.put("signature", sig);
        form.put("t1", "");
        form.put("t2", "");
        form.put("timestamp", ts);
        form.put("uid", "");

        String urlStr = API_BASE + NeteaseApi.XEAPI_SECURITY_KEY.path;
        String body = formEncode(form);
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        String resp;
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", ANDROID_UA);
            conn.setRequestProperty("Cookie", "deviceId=" + urlEnc(deviceId));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            try {
                resp = readAll(is);
            } finally {
                if (is != null) { try { is.close(); } catch (IOException ignored) {} }
            }
            if (code >= 400) throw new IOException("HTTP " + code + ": " + truncate(resp, 200));
        } finally {
            conn.disconnect();
        }

        JsonObject obj = new JsonParser().parse(resp).getAsJsonObject();
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : 0;
        if (code != 200 || !obj.has("data") || !obj.get("data").isJsonObject()) {
            throw new IOException("xeapi key registration failed: " + truncate(resp, 200));
        }
        JsonObject data = obj.getAsJsonObject("data");
        if (!data.has("encryptedData") || data.get("encryptedData").isJsonNull()) {
            throw new IOException("xeapi key registration missing encryptedData");
        }
        String pkJson;
        try {
            pkJson = NeteaseCrypto.xeapiDecryptPublicKey(data.get("encryptedData").getAsString());
        } catch (Exception e) {
            throw new IOException("xeapi publicKey decrypt failed: " + e.getMessage(), e);
        }
        JsonObject pk = new JsonParser().parse(pkJson).getAsJsonObject();
        if (!pk.has("publicKey") || !pk.has("sk") || pk.get("publicKey").isJsonNull()
                || pk.get("sk").isJsonNull()) {
            throw new IOException("xeapi publicKey response missing publicKey/sk: " + truncate(pkJson, 200));
        }
        xeapiPubKey = pk.get("publicKey").getAsString();
        xeapiSk = pk.get("sk").getAsString();
        xeapiVersion = pk.has("version") && !pk.get("version").isJsonNull()
                ? pk.get("version").getAsString() : "";
        Logger.info("Netease: registered xeapi session key (version {})", xeapiVersion);
    }

    /** Cookie header for the xeapi path — mirrors {@link #cookieHeader()} but with
     *  the Android device identity that matches the {@code x-os}/{@code x-appver}
     *  headers (a mismatch itself trips risk control). */
    private String xeapiCookieHeader() {
        ensureDeviceCookies();
        Map<String, String> all = new LinkedHashMap<>(cookies);
        all.put("__remember_me", "true");
        all.put("ntes_kaola_ad", "1");
        all.put("_ntes_nnid", all.getOrDefault("_ntes_nuid", "") + "," + System.currentTimeMillis());
        all.put("WNMCID", wnmcid());
        all.put("WEVNSM", "1.0.0");
        all.put("NMTID", randomHex(16));
        all.put("os", "android");
        all.put("osver", "16");
        all.put("appver", XEAPI_APPVER);
        all.put("channel", "xiaomi");
        all.put("deviceId", cookies.getOrDefault("deviceId", ""));
        all.put("sDeviceId", cookies.getOrDefault("deviceId", ""));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String randomDigits(int n) {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder s = new StringBuilder(n);
        for (int i = 0; i < n; i++) s.append((char) ('0' + r.nextInt(10)));
        return s.toString();
    }

    /** Cookie header built from the eapi {@code header} object. Values are written
     *  verbatim (like {@link #cookieHeader()} — MUSIC_U arrives pre-encoded from
     *  Set-Cookie and must not be re-encoded), with only bare spaces escaped so
     *  HttpURLConnection accepts the header (e.g. os "iPhone OS"). */
    private static String headerCookie(Map<String, Object> header) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : header.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            String val = String.valueOf(e.getValue()).replace(" ", "%20");
            sb.append(e.getKey()).append('=').append(val);
        }
        return sb.toString();
    }

    // ---- Endpoint wrappers (Step 1 verification only — full set in Step 3) ----

    /**
     * Fetch a CDN url for the given song id. {@code level} is one of
     * {@code standard / higher / exhigh / lossless / hires / jyeffect / sky / jymaster}.
     * Returns null if the song is blocked/VIP-only and we don't have a
     * subscription cookie.
     */
    public String songUrl(long songId, String level) throws IOException {
        UrlInfo info = songUrlInfo(songId, level);
        return info == null ? null : info.url;
    }

    /** Official-url result with trial detection. {@link #url} is the CDN url (null
     *  if blocked/VIP/login-required); {@link #trial} is true when the only url
     *  netease returned is a {@code freeTrialInfo} preview clip, which callers
     *  may want to replace via an unblock source before settling for it. */
    public static final class UrlInfo {
        public final String url;
        public final boolean trial;
        public UrlInfo(String url, boolean trial) {
            this.url = url;
            this.trial = trial;
        }
    }

    /** Like {@link #songUrl} but also reports whether the returned url is a
     *  trial-only preview ({@code freeTrialInfo != null}). */
    public UrlInfo songUrlInfo(long songId, String level) throws IOException {
        if (songId <= 0L) return null;
        Map<String, Object> body = new HashMap<>();
        body.put("ids", "[" + songId + "]");
        body.put("level", level == null ? "standard" : level);
        body.put("encodeType", "flac");
        if ("sky".equals(level)) body.put("immerseType", "c51");
        // The playback caller owns recovery through another source and reports
        // an error only if the complete playback attempt actually fails.
        JsonObject obj = apiJson(NeteaseApi.SONG_URL_V1, body, false);
        if (!obj.has("data") || !obj.get("data").isJsonArray()) return null;
        if (obj.get("data").getAsJsonArray().size() == 0) return null;
        JsonElement first = obj.get("data").getAsJsonArray().get(0);
        if (!first.isJsonObject()) return null;
        JsonObject song = first.getAsJsonObject();
        JsonElement url = song.get("url");
        if (url == null || url.isJsonNull()) return null;
        boolean trial = song.has("freeTrialInfo") && !song.get("freeTrialInfo").isJsonNull();
        return new UrlInfo(url.getAsString(), trial);
    }

    // ---- Discovery / search / playlist (N5) ----

    /**
     * Hot search keywords for the search page (shown when input is empty).
     * Returns a list of keyword strings ordered by popularity.
     */
    public List<String> searchHot() throws IOException {
        // NeteaseCloudMusicApi uses /hotsearchlist/get for the hot-search list; the
        // older search/hot/detail returns nothing now. Both shape data[].searchWord.
        JsonObject obj = apiJson(NeteaseApi.HOT_SEARCH_DETAIL,
                new HashMap<String, Object>());
        List<String> out = new ArrayList<>();
        if (obj.has("data") && obj.get("data").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("data")) {
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                if (item.has("searchWord") && !item.get("searchWord").isJsonNull()) {
                    out.add(item.get("searchWord").getAsString());
                }
            }
        }
        if (out.isEmpty()) {
            Logger.warn("hot search returned empty: {}", truncate(obj.toString(), 200));
        }
        return out;
    }

    /**
     * Hot / personalised playlist grid for the home page. The
     * {@code /personalized/playlist} endpoint returns public picks even
     * without a logged-in cookie.
     */
    public List<NeteasePlaylist> personalizedPlaylists(int limit) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("limit", limit);
        body.put("offset", 0);
        body.put("total", true);
        body.put("n", 1000);
        JsonObject obj = apiJson(NeteaseApi.PERSONALIZED_PLAYLIST, body);
        ensureOk(obj, "获取推荐歌单失败");
        List<NeteasePlaylist> out = new ArrayList<>();
        if (obj.has("result") && obj.get("result").isJsonArray()) {
            // Cap to the requested limit: without a logged-in cookie the endpoint
            // ignores `limit` and can return the full pool (hundreds+), which the
            // home grid then instantiates as one PlaylistCard each -> OOM. We asked
            // for `limit`; never build more than that regardless of what comes back.
            for (JsonElement el : obj.getAsJsonArray("result")) {
                if (!el.isJsonObject()) continue;
                if (out.size() >= limit) break;
                out.add(parsePlaylist(el.getAsJsonObject()));
            }
        }
        return out;
    }

    /** api-enhanced playlist_catlist: names and groups come from NetEase. */
    public List<NeteaseDiscovery.Category> playlistCategories() throws IOException {
        JsonObject response = apiJson(NeteaseApi.PLAYLIST_CATALOGUE, Collections.emptyMap());
        ensureOk(response, "获取音乐分类失败");
        return NeteaseDiscovery.parseCategories(response);
    }

    /** api-enhanced top_playlist: actual playlists for the selected category. */
    public NeteaseDiscovery.PlaylistPage categoryPlaylists(String category, int offset, int limit) throws IOException {
        Map<String, Object> body = NeteaseDiscovery.playlistBody(category, offset, limit);
        JsonObject response = apiJson(NeteaseApi.CATEGORY_PLAYLISTS, body);
        ensureOk(response, "获取分类歌单失败");
        return NeteaseDiscovery.parsePlaylists(response, Math.max(0, offset), ((Number) body.get("limit")).intValue());
    }

    /** A real discovery block, retaining its server title and playlist identity. */
    public static final class HomeSongSection {
        public final String id;
        public final String title;
        public final long playlistId;
        public final List<NeteaseSong> songs = new ArrayList<>();

        public HomeSongSection(String id, String title, long playlistId) {
            this.id = id;
            this.title = title;
            this.playlistId = playlistId;
        }
    }

    public static final class HomePlaylistSection {
        public final String id;
        public final String title;
        public final List<NeteasePlaylist> playlists = new ArrayList<>();

        public HomePlaylistSection(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    public static final class HomeFeed {
        public final List<HomeSongSection> sections = new ArrayList<>();
        public final List<HomePlaylistSection> playlistSections = new ArrayList<>();
        public final List<NeteaseAlbum> albums = new ArrayList<>();
    }

    public HomeFeed homepageFeed() throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("refresh", false);
        body.put("cursor", "");
        JsonObject root = apiJson(NeteaseApi.HOMEPAGE_BLOCK_PAGE, body);
        ensureOk(root, "获取首页推荐失败");
        return parseHomeFeed(root);
    }

    /** Parser kept separate from I/O so malformed or reordered blocks can be tested. */
    static HomeFeed parseHomeFeed(JsonObject root) {
        HomeFeed out = new HomeFeed();
        Map<Long, NeteaseAlbum> albums = new LinkedHashMap<>();
        java.util.Set<Long> radarPlaylists = new java.util.HashSet<>();
        java.util.Set<String> sectionIds = new java.util.HashSet<>();
        java.util.Set<String> playlistSectionIds = new java.util.HashSet<>();
        int inspectedBlocks = 0;
        for (JsonElement blockElement : homeArray(homeObject(root, "data"), "blocks")) {
            if (++inspectedBlocks > 40) break;
            if (!blockElement.isJsonObject()) continue;
            JsonObject block = blockElement.getAsJsonObject();
            String code = stringValue(block, "blockCode");
            String title = homeTitle(block, true);
            boolean radar = (code + title).toUpperCase(java.util.Locale.ROOT).contains("RADAR")
                    || title.contains("雷达");
            Map<Long, NeteaseSong> songs = new LinkedHashMap<>();
            Map<Long, NeteasePlaylist> playlists = new LinkedHashMap<>();
            for (JsonElement creativeElement : homeArray(block, "creatives")) {
                if (!creativeElement.isJsonObject()) continue;
                JsonObject creative = creativeElement.getAsJsonObject();
                for (JsonElement resourceElement : homeArray(creative, "resources")) {
                    if (!resourceElement.isJsonObject()) continue;
                    JsonObject resource = resourceElement.getAsJsonObject();
                    String type = stringValue(resource, "resourceType").toUpperCase(java.util.Locale.ROOT);
                    JsonObject ext = homeObject(resource, "resourceExtInfo");
                    try {
                        if (resource.has("valid") && !resource.get("valid").isJsonNull()
                                && !resource.get("valid").getAsBoolean()) continue;
                        if ((type.equals("SONG") || type.isEmpty()) && songs.size() < 30) {
                            NeteaseSong song = parseSong(homeObject(ext, "songData"));
                            if (song.recommendReason.isEmpty()) {
                                song.recommendReason = stringValue(homeObject(homeObject(resource, "uiElement"), "subTitle"), "title");
                            }
                            if (song.id > 0L && song.name != null && !song.name.trim().isEmpty()) {
                                if (song.coverUrl == null || song.coverUrl.isEmpty())
                                    song.coverUrl = stringValue(homeObject(homeObject(resource, "uiElement"), "image"), "imageUrl");
                                song.coverThumbPath = thumbUrl(song.coverUrl);
                                songs.putIfAbsent(song.id, song);
                            }
                        }
                        if (type.equals("ALBUM") && albums.size() < 12) {
                            JsonObject albumJson = homeObject(ext, "album");
                            if (albumJson.size() == 0) albumJson = homeObject(ext, "albumData");
                            if (albumJson.size() == 0) albumJson = ext;
                            NeteaseAlbum album = parseAlbum(albumJson);
                            if (album.id <= 0L) album.id = homeResourceId(resource);
                            if (album.name == null || album.name.isEmpty()) album.name = homeTitle(resource, false);
                            if (album.coverUrl == null || album.coverUrl.isEmpty())
                                album.coverUrl = stringValue(homeObject(homeObject(resource, "uiElement"), "image"), "imageUrl");
                            if (album.artistName == null || album.artistName.isEmpty()) {
                                JsonArray artists = homeArray(ext, "artists");
                                if (artists.size() > 0 && artists.get(0).isJsonObject())
                                    album.artistName = stringValue(artists.get(0).getAsJsonObject(), "name");
                            }
                            album.coverThumbPath = thumbUrl(album.coverUrl);
                            if (album.id > 0L && !album.name.trim().isEmpty()) albums.putIfAbsent(album.id, album);
                        }
                        // Keep ALL real playlist shelves (genre, mood, curated,
                        // Radar, etc.), using the server's own names and IDs.
                        if (type.equals("PLAYLIST") && playlists.size() < 12) {
                            JsonObject playlistJson = homeObject(ext, "playlist");
                            if (playlistJson.size() == 0) playlistJson = homeObject(ext, "playlistData");
                            NeteasePlaylist playlist = parsePlaylist(playlistJson);
                            if (playlist.id <= 0L) playlist.id = homeResourceId(resource);
                            if (playlist.name == null || playlist.name.trim().isEmpty())
                                playlist.name = homeTitle(resource, false);
                            if (playlist.name.isEmpty()) playlist.name = homeTitle(creative, false);
                            if (playlist.coverUrl == null || playlist.coverUrl.isEmpty())
                                playlist.coverUrl = stringValue(homeObject(homeObject(resource, "uiElement"), "image"), "imageUrl");
                            playlist.coverThumbPath = thumbUrl(playlist.coverUrl);
                            if (playlist.id > 0L && !playlist.name.trim().isEmpty())
                                playlists.putIfAbsent(playlist.id, playlist);
                        }
                        // Radar often arrives as playlist resources, not songData.
                        // Keep only real server-provided playlist IDs; fetch a bounded preview later.
                        String name = homeTitle(resource, false);
                        if (type.equals("PLAYLIST") && (radar || name.contains("雷达"))
                                && radarPlaylists.size() < 3 && out.sections.size() < 12) {
                            long id = homeResourceId(resource);
                            if (id > 0L && radarPlaylists.add(id)) {
                                if (name.isEmpty()) name = homeTitle(creative, false);
                                if (name.isEmpty()) name = title;
                                out.sections.add(new HomeSongSection("radar_playlist_" + id,
                                        name.isEmpty() ? "雷达推荐" : name, id));
                            }
                        }
                    } catch (RuntimeException malformedResource) {
                        // One ad/unsupported resource must not discard the rest of the homepage.
                    }
                }
            }
            if (!playlists.isEmpty() && out.playlistSections.size() < 12) {
                String id = (code.isEmpty() ? "playlists" : code) + "_" + playlists.keySet().iterator().next();
                if (playlistSectionIds.add(id)) {
                    HomePlaylistSection section = new HomePlaylistSection(id,
                            title.isEmpty() ? "发现歌单" : title);
                    section.playlists.addAll(playlists.values());
                    out.playlistSections.add(section);
                }
            }
            if (!songs.isEmpty() && out.sections.size() < 12) {
                String id = code.isEmpty() ? "songs_" + songs.keySet().iterator().next() : code;
                if (sectionIds.add(id)) {
                    HomeSongSection section = new HomeSongSection(id, title.isEmpty() ? "为你推荐" : title, 0L);
                    section.songs.addAll(songs.values());
                    out.sections.add(section);
                }
            }
        }
        out.albums.addAll(albums.values());
        return out;
    }

    /** Only the first 12 songs, never the expensive full playlist used by its detail page. */
    public List<NeteaseSong> homepagePlaylistPreview(long playlistId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("id", playlistId);
        body.put("n", 12);
        body.put("s", 0);
        JsonObject response = apiJson(NeteaseApi.PLAYLIST_DETAIL, body);
        ensureOk(response, "获取雷达歌曲失败");
        JsonObject playlist = homeObject(response, "playlist");
        List<NeteaseSong> songs = new ArrayList<>();
        for (JsonElement element : homeArray(playlist, "tracks")) {
            if (songs.size() >= 12) break;
            if (!element.isJsonObject()) continue;
            try {
                NeteaseSong song = parseSong(element.getAsJsonObject());
                if (song.id > 0L && song.name != null) songs.add(song);
            } catch (RuntimeException ignored) { }
        }
        if (!songs.isEmpty()) return songs;
        List<Long> ids = new ArrayList<>();
        for (JsonElement element : homeArray(playlist, "trackIds")) {
            if (ids.size() >= 12) break;
            if (!element.isJsonObject()) continue;
            try {
                long id = Long.parseLong(stringValue(element.getAsJsonObject(), "id"));
                if (id > 0L && !ids.contains(id)) ids.add(id);
            } catch (NumberFormatException ignored) { }
        }
        // Preserve the playlist's order even if song/detail returns a different order.
        Map<Long, NeteaseSong> details = new HashMap<>();
        for (NeteaseSong song : songDetails(ids)) details.put(song.id, song);
        for (long id : ids) if (details.containsKey(id)) songs.add(details.get(id));
        return songs;
    }

    private static JsonObject homeObject(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray homeArray(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static long homeResourceId(JsonObject resource) {
        try { return Long.parseLong(stringValue(resource, "resourceId")); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private static String homeTitle(JsonObject object, boolean section) {
        JsonObject ui = homeObject(object, "uiElement");
        String title = stringValue(homeObject(ui, section ? "subTitle" : "mainTitle"), "title");
        return title.isEmpty() ? stringValue(homeObject(ui, section ? "mainTitle" : "subTitle"), "title") : title;
    }

    private static String stringValue(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : "";
    }

    /**
     * Search songs by keyword (type=1). Pagination via offset/limit. Uses the
     * current enhanced API definition ({@code /api/cloudsearch/pc} over eapi)
     * rather than the legacy web-search endpoint, whose result set can drift
     * away from the submitted keyword.
     */
    public static final class SongSearchPage {
        public final List<NeteaseSong> songs;
        /** Total number reported by cloudsearch, or -1 when the server omitted it. */
        public final int total;
        /** Raw rows consumed by this page, before QPlayer's junk-row filter. */
        public final int consumed;

        SongSearchPage(List<NeteaseSong> songs, int total, int consumed) {
            this.songs = songs;
            this.total = total;
            this.consumed = consumed;
        }

        public boolean hasMore(int offset, int requestedLimit) {
            int next = offset + consumed;
            return total >= 0 ? next < total : consumed >= requestedLimit;
        }
    }

    public SongSearchPage searchSongsPage(String keyword, int limit, int offset) throws IOException {
        return searchSongsPage(keyword, limit, offset, false);
    }

    /** AI lookups overlap network waits without serialising playback/collection writes. */
    public List<NeteaseSong> searchAiSongs(String keyword, int limit) throws IOException {
        return searchSongsPage(keyword, limit, 0, true).songs;
    }

    private SongSearchPage searchSongsPage(String keyword, int limit, int offset, boolean parallelSearch) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("s", keyword);
        body.put("type", 1);
        body.put("limit", limit);
        body.put("offset", offset);
        body.put("total", true);
        JsonObject obj = apiJson(NeteaseApi.CLOUD_SEARCH, body, true, parallelSearch);
        List<NeteaseSong> out = new ArrayList<>();
        int total = -1;
        int consumed = 0;
        if (obj.has("result") && obj.get("result").isJsonObject()) {
            JsonObject result = obj.getAsJsonObject("result");
            if (result.has("songCount") && !result.get("songCount").isJsonNull()) {
                total = result.get("songCount").getAsInt();
            }
            if (result.has("songs") && result.get("songs").isJsonArray()) {
                JsonArray songs = result.getAsJsonArray("songs");
                consumed = songs.size();
                for (JsonElement el : songs) {
                    if (!el.isJsonObject()) continue;
                    out.add(parseSong(el.getAsJsonObject()));
                }
            }
        }
        return new SongSearchPage(filterJunkNumericNames(out, keyword), total, consumed);
    }

    /** Album search — cloudsearch {@code type: 10}. Single page, no offset
     *  support: SearchPage's album filter doesn't paginate (unlike song search). */
    public List<NeteaseAlbum> searchAlbums(String keyword, int limit) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("s", keyword);
        body.put("type", 10);
        body.put("limit", limit);
        body.put("offset", 0);
        JsonObject obj = apiJson(NeteaseApi.CLOUD_SEARCH, body);
        List<NeteaseAlbum> out = new ArrayList<>();
        if (obj.has("result") && obj.get("result").isJsonObject()) {
            JsonObject result = obj.getAsJsonObject("result");
            if (result.has("albums") && result.get("albums").isJsonArray()) {
                for (JsonElement el : result.getAsJsonArray("albums")) {
                    if (el.isJsonObject()) out.add(parseAlbum(el.getAsJsonObject()));
                }
            }
        }
        return out;
    }

    /**
     * Ⓜ The freshest releases across all artists ({@code /album/newest}) — the home page's extra
     * album shelf. The response's {@code data} array carries the same album objects every other
     * album list here parses, so {@link #parseAlbum} handles it unchanged.
     */
    public List<NeteaseAlbum> newAlbums(int limit) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("limit", limit);
        JsonObject obj = apiJson(NeteaseApi.albumNewest(), body);
        List<NeteaseAlbum> out = new ArrayList<>();
        if (obj.has("data") && obj.get("data").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("data")) {
                if (el.isJsonObject()) out.add(parseAlbum(el.getAsJsonObject()));
            }
        }
        return out;
    }

    /** Artist search — cloudsearch {@code type: 100}. Single page, same as
     *  {@link #searchAlbums}. */
    public List<NeteaseArtist> searchArtists(String keyword, int limit) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("s", keyword);
        body.put("type", 100);
        body.put("limit", limit);
        body.put("offset", 0);
        JsonObject obj = apiJson(NeteaseApi.CLOUD_SEARCH, body);
        List<NeteaseArtist> out = new ArrayList<>();
        if (obj.has("result") && obj.get("result").isJsonObject()) {
            JsonObject result = obj.getAsJsonObject("result");
            if (result.has("artists") && result.get("artists").isJsonArray()) {
                for (JsonElement el : result.getAsJsonArray("artists")) {
                    if (el.isJsonObject()) out.add(parseArtist(el.getAsJsonObject()));
                }
            }
        }
        return out;
    }

    public List<NeteaseSong> searchSongs(String keyword, int limit, int offset) throws IOException {
        return searchSongsPage(keyword, limit, offset).songs;
    }

    /** Search can include low-quality UGC tracks whose {@code name} is literally
     *  just a number (e.g. a DJ/sample-pack
     *  index) — real, playable songs server-side, just noise for a text keyword
     *  search. Drop them, unless the user's own keyword is itself numeric (so
     *  searching "67" still finds a song actually named "67"). */
    private static List<NeteaseSong> filterJunkNumericNames(List<NeteaseSong> songs, String keyword) {
        if (keyword != null && keyword.trim().matches("\\d+")) return songs;
        List<NeteaseSong> out = new ArrayList<>(songs.size());
        for (NeteaseSong s : songs) {
            if (s.name != null && s.name.trim().matches("\\d+")) continue;
            out.add(s);
        }
        return out;
    }

    /** Full playlist (metadata + first ~1000 track stubs). */
    public NeteasePlaylist playlistDetail(long playlistId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("id", playlistId);
        body.put("n", 1000);
        body.put("s", 8);
        JsonObject obj = apiJson(NeteaseApi.PLAYLIST_DETAIL, body);
        if (!obj.has("playlist") || !obj.get("playlist").isJsonObject()) return null;
        return parsePlaylist(obj.getAsJsonObject("playlist"));
    }

    /**
     * All tracks in a playlist. Netease returns track stubs in the playlist
     * detail's {@code trackIds} array; this method calls {@code /v3/song/detail}
     * to resolve them into full {@link NeteaseSong}s in chunks of up to
     * {@code limit} ids per request.
     */
    public List<NeteaseSong> playlistTracks(long playlistId, int limit) throws IOException {
        Map<String, Object> detailBody = new HashMap<>();
        detailBody.put("id", playlistId);
        // Match NeteaseCloudMusicApi's playlist/track/all: n=100000 forces the full
        // trackIds[] (a smaller n left another user's red-heart playlist's trackIds
        // empty), s=8 is the collector count the endpoint expects.
        detailBody.put("n", 100000);
        detailBody.put("s", 8);
        JsonObject detail = apiJson(NeteaseApi.PLAYLIST_DETAIL, detailBody);
        List<NeteaseSong> out = new ArrayList<>();
        // No playlist object => netease refused it (e.g. creator set it private);
        // apiJson has already surfaced the reason as a toast, so return empty here.
        if (!detail.has("playlist") || !detail.get("playlist").isJsonObject()) return out;
        JsonObject pl = detail.getAsJsonObject("playlist");
        // Newer responses populate tracks[] directly when n is small enough. Require it
        // to be NON-EMPTY: another user's "喜欢的音乐" (red-heart) playlist comes back with
        // tracks:[] but a full trackIds[], and taking the empty fast path returned an
        // empty list (the playlist opened blank) instead of resolving the ids below.
        if (pl.has("tracks") && pl.get("tracks").isJsonArray()
                && pl.getAsJsonArray("tracks").size() > 0) {
            JsonArray arr = pl.getAsJsonArray("tracks");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                out.add(parseSong(el.getAsJsonObject()));
            }
            return out;
        }
        // Otherwise iterate trackIds, batch-resolve via /v3/song/detail.
        if (!pl.has("trackIds") || !pl.get("trackIds").isJsonArray()) return out;
        StringBuilder ids = new StringBuilder("[");
        int n = 0;
        for (JsonElement el : pl.getAsJsonArray("trackIds")) {
            if (n >= limit) break;
            JsonObject ti = el.getAsJsonObject();
            JsonElement idEl = ti.get("id");
            if (idEl == null || idEl.isJsonNull()) continue;
            if (n > 0) ids.append(',');
            ids.append("{\"id\":").append(idEl.getAsLong()).append('}');
            n++;
        }
        ids.append(']');
        Map<String, Object> songBody = new HashMap<>();
        songBody.put("c", ids.toString());
        JsonObject songResp = apiJson(NeteaseApi.SONG_DETAIL, songBody);
        if (songResp.has("songs") && songResp.get("songs").isJsonArray()) {
            for (JsonElement el : songResp.getAsJsonArray("songs")) {
                if (!el.isJsonObject()) continue;
                out.add(parseSong(el.getAsJsonObject()));
            }
        }
        return out;
    }

    /** Lightweight taste read, separate from opening a complete playlist. */
    public List<NeteaseSong> playlistTasteSample(long playlistId, int limit, java.util.Random random)
            throws IOException {
        if (limit <= 0) return Collections.emptyList();
        Map<String, Object> body = new HashMap<>();
        body.put("id", playlistId); body.put("n", limit); body.put("s", 0);
        JsonObject detail = apiJson(NeteaseApi.PLAYLIST_DETAIL, body);
        if (!detail.has("playlist") || !detail.get("playlist").isJsonObject())
            return Collections.emptyList();
        JsonObject playlist = detail.getAsJsonObject("playlist");
        List<NeteaseSong> tracks = parseTasteTracks(playlist, limit, random);
        if (!tracks.isEmpty()) return tracks;
        // Some liked playlists expose IDs only. Resolve a small random batch,
        // rather than fetching hundreds of details to discard most of them.
        List<Long> ids = tasteTrackIds(playlist, limit, random);
        // A few liked playlists hide IDs when n is small. Retry only that case;
        // even then song/detail resolves at most the small sample batch.
        if (ids.isEmpty() && playlist.has("trackCount") && playlist.get("trackCount").isJsonPrimitive()
                && playlist.get("trackCount").getAsInt() > 0) {
            body.put("n", 100000);
            detail = apiJson(NeteaseApi.PLAYLIST_DETAIL, body);
            if (detail.has("playlist") && detail.get("playlist").isJsonObject()) {
                playlist = detail.getAsJsonObject("playlist");
                tracks = parseTasteTracks(playlist, limit, random);
                if (!tracks.isEmpty()) return tracks;
                ids = tasteTrackIds(playlist, limit, random);
            }
        }
        if (ids.isEmpty()) return tracks;
        JsonArray rows = new JsonArray();
        for (long id : ids) { JsonObject row = new JsonObject(); row.addProperty("id", id); rows.add(row); }
        Map<String, Object> songBody = new HashMap<>();
        songBody.put("c", rows.toString());
        JsonObject response = apiJson(NeteaseApi.SONG_DETAIL, songBody);
        if (response.has("songs")) {
            JsonObject wrapper = new JsonObject(); wrapper.add("tracks", response.get("songs"));
            tracks = parseTasteTracks(wrapper, limit, random);
        }
        return tracks;
    }

    static List<NeteaseSong> parseTasteTracks(JsonObject playlist, int limit, java.util.Random random) {
        List<JsonObject> rows = new ArrayList<>();
        if (playlist.has("tracks") && playlist.get("tracks").isJsonArray())
            for (JsonElement row : playlist.getAsJsonArray("tracks"))
                if (row.isJsonObject()) rows.add(row.getAsJsonObject());
        Collections.shuffle(rows, random);
        List<NeteaseSong> songs = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (JsonObject row : rows) {
            if (songs.size() >= limit) break;
            NeteaseSong song = parseSong(row);
            if (song.id > 0 && song.name != null && !song.name.trim().isEmpty() && seen.add(song.id)) songs.add(song);
        }
        return songs;
    }

    static List<Long> tasteTrackIds(JsonObject playlist, int limit, java.util.Random random) {
        java.util.Set<Long> unique = new java.util.LinkedHashSet<>();
        if (playlist.has("trackIds") && playlist.get("trackIds").isJsonArray())
            for (JsonElement row : playlist.getAsJsonArray("trackIds")) {
                if (!row.isJsonObject()) continue;
                JsonElement id = row.getAsJsonObject().get("id");
                if (id != null && id.isJsonPrimitive()) {
                    try { if (id.getAsLong() > 0) unique.add(id.getAsLong()); }
                    catch (NumberFormatException ignored) { }
                }
            }
        List<Long> ids = new ArrayList<>(unique);
        Collections.shuffle(ids, random);
        return new ArrayList<>(ids.subList(0, Math.min(Math.max(0, limit), ids.size())));
    }

    /** Common JSON decode for /cloudsearch songs[] / /song/detail songs[] / playlist tracks[]. */
    static NeteaseSong parseSong(JsonObject s) {
        NeteaseSong out = new NeteaseSong();
        if (s.has("id") && !s.get("id").isJsonNull()) out.id = s.get("id").getAsLong();
        if (s.has("name") && !s.get("name").isJsonNull()) out.name = s.get("name").getAsString();
        out.recommendReason = stringValue(s, "reason");
        if (out.recommendReason.isEmpty()) out.recommendReason = stringValue(s, "recommendReason");
        out.genre = stringValue(s, "genre");
        if (s.has("dt"))   out.durationMs = s.get("dt").getAsLong();
        else if (s.has("duration")) out.durationMs = s.get("duration").getAsLong();
        if (s.has("fee"))  out.fee = s.get("fee").getAsInt() == 1;
        // Artists array: "ar" (new schema) or "artists" (old).
        JsonArray ar = s.has("ar") && s.get("ar").isJsonArray()
                ? s.getAsJsonArray("ar")
                : (s.has("artists") && s.get("artists").isJsonArray() ? s.getAsJsonArray("artists") : null);
        if (ar != null) {
            StringBuilder names = new StringBuilder();
            StringBuilder idsCsv = new StringBuilder();
            StringBuilder namesCsv = new StringBuilder();
            for (JsonElement ae : ar) {
                if (!ae.isJsonObject()) continue;
                JsonObject ao = ae.getAsJsonObject();
                if (!ao.has("name") || ao.get("name").isJsonNull()) continue;
                if (names.length() > 0) names.append(" / ");
                String artistName = ao.get("name").getAsString();
                names.append(artistName);
                long refId = ao.has("id") && !ao.get("id").isJsonNull() ? ao.get("id").getAsLong() : 0L;
                if (idsCsv.length() > 0) {
                    idsCsv.append(',');
                    namesCsv.append((char) 1);
                }
                idsCsv.append(refId);
                namesCsv.append(artistName);
            }
            if (names.length() > 0) out.artist = names.toString();
            out.artistIdsCsv = idsCsv.toString();
            out.artistNamesCsv = namesCsv.toString();
            // First-listed artist's id, so the UI can open their artist page.
            if (ar.size() > 0 && ar.get(0).isJsonObject()) {
                JsonObject fo = ar.get(0).getAsJsonObject();
                if (fo.has("id") && !fo.get("id").isJsonNull()) out.artistId = fo.get("id").getAsLong();
            }
        }
        // Album object: "al" (new schema) or "album" (old).
        JsonObject al = s.has("al") && s.get("al").isJsonObject()
                ? s.getAsJsonObject("al")
                : (s.has("album") && s.get("album").isJsonObject() ? s.getAsJsonObject("album") : null);
        if (al != null) {
            if (al.has("name") && !al.get("name").isJsonNull()) out.album = al.get("name").getAsString();
            if (al.has("picUrl") && !al.get("picUrl").isJsonNull()) out.coverUrl = al.get("picUrl").getAsString();
            if (al.has("id") && !al.get("id").isJsonNull()) out.albumId = al.get("id").getAsLong();
        }
        out.coverThumbPath = thumbUrl(out.coverUrl);
        return out;
    }

    /** CDN thumbnail URL (128×128) for a cover, or null. Used as a list-row
     *  Image.source so every list shows album art, not just search results. */
    public static String thumbUrl(String coverUrl) {
        if (coverUrl == null || coverUrl.isEmpty()) return null;
        return coverUrl + (coverUrl.contains("?") ? "&" : "?") + "param=128y128";
    }

    static NeteasePlaylist parsePlaylist(JsonObject p) {
        NeteasePlaylist out = new NeteasePlaylist();
        if (p.has("id") && !p.get("id").isJsonNull()) out.id = p.get("id").getAsLong();
        if (p.has("name") && !p.get("name").isJsonNull()) out.name = p.get("name").getAsString();
        if (p.has("picUrl")) out.coverUrl = p.get("picUrl").getAsString();
        else if (p.has("coverImgUrl")) out.coverUrl = p.get("coverImgUrl").getAsString();
        if (p.has("trackCount")) out.trackCount = p.get("trackCount").getAsInt();
        if (p.has("playCount")) out.playCount = p.get("playCount").getAsLong();
        if (p.has("description") && !p.get("description").isJsonNull())
            out.description = p.get("description").getAsString();
        if (p.has("creator") && p.get("creator").isJsonObject()) {
            JsonObject c = p.getAsJsonObject("creator");
            if (c.has("nickname") && !c.get("nickname").isJsonNull())
                out.creatorNickname = c.get("nickname").getAsString();
            if (c.has("userId") && !c.get("userId").isJsonNull())
                out.creatorUid = c.get("userId").getAsLong();
        }
        if (p.has("subscribed") && !p.get("subscribed").isJsonNull())
            out.subscribed = p.get("subscribed").getAsBoolean();
        return out;
    }

    /** An artist's profile plus a snapshot of their most-played tracks, both
     *  returned in a single {@code /artists} response. */
    public static final class ArtistPage {
        public final NeteaseArtist artist;
        public final List<NeteaseSong> hotSongs;

        ArtistPage(NeteaseArtist artist, List<NeteaseSong> hotSongs) {
            this.artist = artist;
            this.hotSongs = hotSongs;
        }
    }

    /** Artist profile (name/avatar/bio) + their hot songs. */
    public ArtistPage artistDetail(long artistId) throws IOException {
        JsonObject obj = apiJson(NeteaseApi.artistDetail(artistId), new HashMap<String, Object>());
        NeteaseArtist artist =
                obj.has("artist") && obj.get("artist").isJsonObject()
                        ? parseArtist(obj.getAsJsonObject("artist")) : null;
        List<NeteaseSong> hotSongs = new ArrayList<>();
        if (obj.has("hotSongs") && obj.get("hotSongs").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("hotSongs")) {
                if (el.isJsonObject()) hotSongs.add(parseSong(el.getAsJsonObject()));
            }
        }
        return new ArtistPage(artist, hotSongs);
    }

    /** An artist's albums (newest first, as the server orders them). */
    public List<NeteaseAlbum> artistAlbums(long artistId, int limit) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("limit", limit);
        body.put("offset", 0);
        body.put("total", true);
        JsonObject obj = apiJson(NeteaseApi.artistAlbums(artistId), body);
        List<NeteaseAlbum> out = new ArrayList<>();
        if (obj.has("hotAlbums") && obj.get("hotAlbums").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("hotAlbums")) {
                if (el.isJsonObject()) out.add(parseAlbum(el.getAsJsonObject()));
            }
        }
        return out;
    }

    /** An album's profile (name/cover/artist) plus its full tracklist, both
     *  returned in a single {@code /album} response. */
    public static final class AlbumPage {
        public final NeteaseAlbum album;
        public final List<NeteaseSong> songs;

        AlbumPage(NeteaseAlbum album, List<NeteaseSong> songs) {
            this.album = album;
            this.songs = songs;
        }
    }

    public AlbumPage albumDetail(long albumId) throws IOException {
        JsonObject obj = apiJson(NeteaseApi.albumDetail(albumId), new HashMap<String, Object>());
        NeteaseAlbum album =
                obj.has("album") && obj.get("album").isJsonObject()
                        ? parseAlbum(obj.getAsJsonObject("album")) : null;
        List<NeteaseSong> songs = new ArrayList<>();
        if (obj.has("songs") && obj.get("songs").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("songs")) {
                if (el.isJsonObject()) songs.add(parseSong(el.getAsJsonObject()));
            }
        }
        return new AlbumPage(album, songs);
    }

    private static NeteaseArtist parseArtist(JsonObject a) {
        NeteaseArtist out = new NeteaseArtist();
        if (a.has("id") && !a.get("id").isJsonNull()) out.id = a.get("id").getAsLong();
        if (a.has("name") && !a.get("name").isJsonNull()) out.name = a.get("name").getAsString();
        if (a.has("picUrl") && !a.get("picUrl").isJsonNull()) out.coverUrl = a.get("picUrl").getAsString();
        else if (a.has("img1v1Url") && !a.get("img1v1Url").isJsonNull())
            out.coverUrl = a.get("img1v1Url").getAsString();
        out.coverThumbPath = thumbUrl(out.coverUrl);
        if (a.has("cover") && !a.get("cover").isJsonNull()) out.headerUrl = a.get("cover").getAsString();
        if (a.has("briefDesc") && !a.get("briefDesc").isJsonNull()) out.briefDesc = a.get("briefDesc").getAsString();
        if (a.has("albumSize") && !a.get("albumSize").isJsonNull()) out.albumSize = a.get("albumSize").getAsInt();
        if (a.has("musicSize") && !a.get("musicSize").isJsonNull()) out.musicSize = a.get("musicSize").getAsInt();
        return out;
    }

    private static NeteaseAlbum parseAlbum(JsonObject a) {
        NeteaseAlbum out = new NeteaseAlbum();
        if (a.has("id") && !a.get("id").isJsonNull()) out.id = a.get("id").getAsLong();
        if (a.has("name") && !a.get("name").isJsonNull()) out.name = a.get("name").getAsString();
        if (a.has("picUrl") && !a.get("picUrl").isJsonNull()) out.coverUrl = a.get("picUrl").getAsString();
        out.coverThumbPath = thumbUrl(out.coverUrl);
        if (a.has("publishTime") && !a.get("publishTime").isJsonNull())
            out.publishTime = a.get("publishTime").getAsLong();
        if (a.has("description") && !a.get("description").isJsonNull())
            out.description = a.get("description").getAsString();
        if (a.has("size") && !a.get("size").isJsonNull()) out.trackCount = a.get("size").getAsInt();
        if (a.has("artist") && a.get("artist").isJsonObject()) {
            JsonObject ar = a.getAsJsonObject("artist");
            if (ar.has("id") && !ar.get("id").isJsonNull()) out.artistId = ar.get("id").getAsLong();
            if (ar.has("name") && !ar.get("name").isJsonNull()) out.artistName = ar.get("name").getAsString();
        }
        return out;
    }

    /**
     * Collect (subscribe) or un-collect (unsubscribe) a playlist for the signed-in user.
     * True when code == 200.
     *
     * <p>Both directions follow api-enhanced's eapi definition with checkToken
     * enabled; subscribe additionally carries the token value in its body.
     */
    public boolean playlistSubscribe(long playlistId, boolean subscribe) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", playlistId);
        if (subscribe) body.put("checkToken", CHECK_TOKEN);
        JsonObject obj = apiJson(subscribe
                ? NeteaseApi.PLAYLIST_SUBSCRIBE
                : NeteaseApi.PLAYLIST_UNSUBSCRIBE, body);
        return obj.has("code") && !obj.get("code").isJsonNull() && obj.get("code").getAsInt() == 200;
    }

    /**
     * Follow or unfollow an artist. True when code == 200.
     *
     * <p>The artist counterpart of {@link #playlistSubscribe}: api-enhanced's
     * {@code artist_sub}/{@code artist_unsub} use the same eapi transport with
     * checkToken, and the follow direction carries the token value in the body.
     * {@code artistIds} is the JSON-array form the endpoint expects alongside the
     * single {@code artistId}.
     */
    public boolean artistFollow(long artistId, boolean follow) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artistId", artistId);
        body.put("artistIds", "[" + artistId + "]");
        if (follow) body.put("checkToken", CHECK_TOKEN);
        JsonObject obj = apiJson(follow
                ? NeteaseApi.ARTIST_SUBSCRIBE
                : NeteaseApi.ARTIST_UNSUBSCRIBE, body);
        return obj.has("code") && !obj.get("code").isJsonNull() && obj.get("code").getAsInt() == 200;
    }

    /** Ids of the artists the signed-in user follows (first {@code limit}).
     *  Returns an empty list when the account is not signed in or the API
     *  refuses — callers treat that as "nothing known to be followed". */
    public List<Long> followedArtistIds(int limit) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("limit", limit);
        body.put("offset", 0);
        JsonObject obj = apiJson(NeteaseApi.ARTIST_SUBLIST, body);
        JsonArray artists = null;
        if (obj.has("artists") && obj.get("artists").isJsonArray()) {
            artists = obj.getAsJsonArray("artists");
        } else if (obj.has("data") && obj.get("data").isJsonObject()
                && obj.getAsJsonObject("data").has("artists")
                && obj.getAsJsonObject("data").get("artists").isJsonArray()) {
            artists = obj.getAsJsonObject("data").getAsJsonArray("artists");
        }
        List<Long> out = new ArrayList<>();
        if (artists == null) return out;
        for (JsonElement el : artists) {
            if (!el.isJsonObject()) continue;
            JsonObject a = el.getAsJsonObject();
            if (a.has("id") && !a.get("id").isJsonNull()) out.add(a.get("id").getAsLong());
        }
        return out;
    }

    /**
     * Fetch full metadata for a single song. Used as a cover-URL fallback
     * when a search row omits {@code picUrl}; {@code /v3/song/detail} carries it.
     * Returns {@code null} if the song is missing or the API blocks us.
     */
    public NeteaseSong songDetail(long songId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("c", "[{\"id\":" + songId + "}]");
        JsonObject obj = apiJson(NeteaseApi.SONG_DETAIL, body);
        if (!obj.has("songs") || !obj.get("songs").isJsonArray()) return null;
        JsonArray arr = obj.getAsJsonArray("songs");
        if (arr.size() == 0 || !arr.get(0).isJsonObject()) return null;
        return parseSong(arr.get(0).getAsJsonObject());
    }

    /**
     * Batch-fetch full metadata for multiple songs. Always includes
     * {@code picUrl} even when the search API omits it.
     */
    public List<NeteaseSong> songDetails(List<Long> ids) throws IOException {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(ids.get(i)).append('}');
        }
        sb.append(']');
        Map<String, Object> body = new HashMap<>();
        body.put("c", sb.toString());
        JsonObject obj = apiJson(NeteaseApi.SONG_DETAIL, body);
        List<NeteaseSong> out = new ArrayList<>();
        if (!obj.has("songs") || !obj.get("songs").isJsonArray()) return out;
        for (JsonElement el : obj.getAsJsonArray("songs")) {
            if (!el.isJsonObject()) continue;
            out.add(parseSong(el.getAsJsonObject()));
        }
        return out;
    }

    /** Optional, silent background lookup. No title search: only the song's own MV. */
    public String songMvUrl(long songId) throws IOException {
        if (songId <= 0) return "";
        // These optional reads must not hold the session monitor while music
        // URL resolution/login waits. Discard response cookies from this snapshot.
        Map<String, String> session = new LinkedHashMap<>(cookies);
        Map<String, Object> body = new HashMap<>();
        body.put("c", "[{\"id\":" + songId + "}]");
        JsonObject detail = mvBackgroundJson(NeteaseApi.SONG_DETAIL, body, session);
        long mvId = songMvId(detail, songId);
        if (mvId <= 0) return "";
        body.clear();
        body.put("id", mvId);
        for (int resolution : new int[]{1080, 720}) {
            body.put("r", resolution);
            String url = mvPlaybackUrl(mvBackgroundJson(NeteaseApi.MV_URL, body, session));
            if (!url.isEmpty()) return url;
        }
        return "";
    }

    private JsonObject mvBackgroundJson(NeteaseApi.Endpoint endpoint, Map<String, Object> body,
            Map<String, String> session) throws IOException {
        if (endpoint != NeteaseApi.SONG_DETAIL && endpoint != NeteaseApi.MV_URL)
            throw new IllegalArgumentException("Only MV background reads may bypass the session lock");
        JsonElement result = JsonParser.parseString(weapiCall(endpoint.weapiPath(), body, session, false));
        if (!result.isJsonObject()) throw new IOException("MV background response unavailable");
        return result.getAsJsonObject();
    }

    static long songMvId(JsonObject detail, long songId) throws IOException {
        if (number(detail, "code", 200) != 200)
            throw new IOException("MV song detail unavailable");
        JsonArray songs = array(detail, "songs");
        if (songs != null) for (JsonElement row : songs) {
            if (!row.isJsonObject()) continue;
            JsonObject song = row.getAsJsonObject();
            if (number(song, "id", 0) == songId)
                return Math.max(0, number(song, "mv", number(song, "mvid", 0)));
        }
        throw new IOException("MV song detail missing requested song");
    }

    static String mvPlaybackUrl(JsonObject response) throws IOException {
        if (number(response, "code", 200) != 200)
            throw new IOException("MV URL unavailable");
        JsonObject data = object(response, "data");
        if (data == null || number(data, "code", 200) != 200) return "";
        String url = string(data, "url").trim();
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        try {
            java.net.URI uri = java.net.URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? url : "";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    // ---- User account / library (N6) ----

    /**
     * Resolve the logged-in user's uid via {@code /w/nuser/account/get}. The
     * weapi endpoint returns {@code {profile: {userId: ...}}} when MUSIC_U
     * cookie is valid, or {@code profile=null} when the session expired.
     * Returns 0 when not logged in.
     */
    public long loginUid() throws IOException {
        if (!isLoggedIn()) return 0L;
        JsonObject obj = apiJson(NeteaseApi.LOGIN_STATUS, new HashMap<String, Object>());
        return profileUid(obj);
    }

    /**
     * Validate and persist a Cookie header copied from the official NetEase
     * website (or exported by an embedded system WebView).
     *
     * <p>The import is transactional: validation runs against a private cookie
     * jar, the current session remains untouched on any parsing/network/login
     * failure, and the in-memory jar is rolled back if encrypted persistence
     * fails. The cookie value itself is never included in logs or exceptions.
     *
     * @return the authenticated account uid
     * @throws IllegalArgumentException when the header is malformed or lacks
     *         the {@code MUSIC_U} login credential
     * @throws IOException when the credential is expired, rejected, or cannot
     *         be persisted securely
     */
    public synchronized long importLoginCookies(String rawCookieHeader) throws IOException {
        Map<String, String> imported = parseCookieHeader(rawCookieHeader);
        String musicU = imported.get("MUSIC_U");
        if (musicU == null || musicU.trim().isEmpty()) {
            throw new IllegalArgumentException("Cookie 中缺少 MUSIC_U 登录凭据");
        }

        Map<String, String> previous = new LinkedHashMap<>(cookies);
        Map<String, String> candidate = new LinkedHashMap<>();
        preserveDeviceCookie(previous, candidate, "_ntes_nuid");
        preserveDeviceCookie(previous, candidate, "deviceId");
        candidate.putAll(imported);
        if (!candidate.containsKey("_ntes_nuid")) {
            candidate.put("_ntes_nuid", randomHex(32));
        }
        if (!candidate.containsKey("deviceId")) {
            candidate.put("deviceId", randomHex(16));
        }

        String response = weapiCall(NeteaseApi.LOGIN_STATUS.weapiPath(),
                new HashMap<String, Object>(), candidate, false);
        JsonElement element = new JsonParser().parse(response);
        if (!element.isJsonObject()) {
            throw new IOException("登录验证返回了无效响应");
        }
        long uid = profileUid(element.getAsJsonObject());
        if (uid <= 0L) {
            throw new IOException("Cookie 已失效或未完成登录");
        }

        cookies.clear();
        cookies.putAll(candidate);
        if (!saveCookies()) {
            cookies.clear();
            cookies.putAll(previous);
            throw new IOException("无法安全保存登录凭据");
        }
        return uid;
    }

    private static void preserveDeviceCookie(Map<String, String> source,
            Map<String, String> target, String name) {
        String value = source.get(name);
        if (value != null && !value.isEmpty()) target.put(name, value);
    }

    private static long profileUid(JsonObject obj) {
        if (obj == null || !obj.has("profile") || obj.get("profile").isJsonNull()
                || !obj.get("profile").isJsonObject()) return 0L;
        JsonObject profile = obj.getAsJsonObject("profile");
        if (!profile.has("userId") || profile.get("userId").isJsonNull()) return 0L;
        return profile.get("userId").getAsLong();
    }

    /** Package-visible for parser tests; returned map preserves header order. */
    static Map<String, String> parseCookieHeader(String rawCookieHeader) {
        if (rawCookieHeader == null) {
            throw new IllegalArgumentException("Cookie 不能为空");
        }
        if (rawCookieHeader.length() > 65_536) {
            throw new IllegalArgumentException("Cookie 内容过长");
        }
        String raw = rawCookieHeader.trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Cookie 不能为空");

        // Also accept a complete request-header block copied from browser devtools,
        // but deliberately extract only its Cookie line.
        if (raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {
            String found = null;
            for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
                String trimmed = line.trim();
                if (trimmed.regionMatches(true, 0, "Cookie:", 0, 7)) {
                    if (found != null) {
                        throw new IllegalArgumentException("检测到多个 Cookie 请求头");
                    }
                    found = trimmed.substring(7).trim();
                }
            }
            if (found == null) {
                throw new IllegalArgumentException("未找到 Cookie 请求头");
            }
            raw = found;
        } else if (raw.regionMatches(true, 0, "Cookie:", 0, 7)) {
            raw = raw.substring(7).trim();
        }

        Map<String, String> parsed = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            String pair = part.trim();
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                if (pair.equalsIgnoreCase("Secure") || pair.equalsIgnoreCase("HttpOnly")) {
                    continue;
                }
                throw new IllegalArgumentException("Cookie 格式无效");
            }
            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            if (!isCookieName(name) || containsControlCharacter(value)) {
                throw new IllegalArgumentException("Cookie 格式无效");
            }
            parsed.put(name, value);
        }
        if (parsed.isEmpty()) throw new IllegalArgumentException("Cookie 不能为空");
        return parsed;
    }

    private static boolean isCookieName(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            if (c <= 0x20 || c >= 0x7f || "()<>@,;:\\\"/[]?={}".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    /**
     * All playlists owned + subscribed by the given user. Netease guarantees
     * that the first playlist (index 0) when {@code uid} matches the logged-
     * in user is the special "我喜欢的音乐" list. Subsequent entries split
     * between user-created and user-subscribed — distinguish via the
     * {@code creator.userId == uid} test on the caller side if you need to.
     */
    public List<NeteasePlaylist> userPlaylists(long uid, int limit) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("uid", uid);
        body.put("limit", limit);
        body.put("offset", 0);
        body.put("includeVideo", true);
        JsonObject obj = apiJson(NeteaseApi.USER_PLAYLIST, body);
        List<NeteasePlaylist> out = new ArrayList<>();
        if (obj.has("playlist") && obj.get("playlist").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("playlist")) {
                if (!el.isJsonObject()) continue;
                NeteasePlaylist pl = parsePlaylist(el.getAsJsonObject());
                // Track creator uid so the caller can split owned vs subscribed.
                JsonObject p = el.getAsJsonObject();
                if (p.has("creator") && p.get("creator").isJsonObject()) {
                    JsonObject c = p.getAsJsonObject("creator");
                    if (c.has("userId") && !c.get("userId").isJsonNull()) {
                        pl.creatorUid = c.get("userId").getAsLong();
                    }
                }
                out.add(pl);
            }
        }
        return out;
    }

    public long listeningTotalSeconds() throws IOException {
        return NeteaseListening.parseTotal(listeningJson(NeteaseApi.LISTEN_TOTAL, Collections.emptyMap()));
    }

    public NeteaseListening.Report listeningReport(String period) throws IOException {
        return NeteaseListening.parseReport(listeningJson(NeteaseApi.LISTEN_REALTIME, listeningPeriod(period)));
    }

    /** Historical reports take the last day's midnight in the requested period. */
    public NeteaseListening.Report listeningDetailedReport(String period, long endTime) throws IOException {
        if (!"week".equals(period) && !"month".equals(period) && !"year".equals(period))
            throw new IllegalArgumentException("Invalid report period");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", period);
        if (endTime > 0) body.put("endTime", endTime);
        return NeteaseListening.parseReport(listeningJson(NeteaseApi.LISTEN_REPORT, body));
    }

    public List<NeteaseListening.SongPlay> listeningRank(String period, long endTime) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>(listeningPeriod(period));
        if (endTime > 0) body.put("endTime", endTime);
        return NeteaseListening.parseRank(listeningJson(NeteaseApi.LISTEN_SONG_RANK, body));
    }

    public List<NeteaseListening.SongPlay> listeningRank(String period) throws IOException {
        return NeteaseListening.parseRank(listeningJson(NeteaseApi.LISTEN_SONG_RANK, listeningPeriod(period)));
    }

    public List<NeteaseListening.SongPlay> listeningFootprints() throws IOException {
        return NeteaseListening.parseRecent(listeningJson(NeteaseApi.RECENT_SONGS,
                Collections.<String, Object>singletonMap("limit", 300)));
    }

    private static Map<String, Object> listeningPeriod(String period) {
        if (!"week".equals(period) && !"month".equals(period))
            throw new IllegalArgumentException("Listening period must be week or month");
        return Collections.<String, Object>singletonMap("type", period);
    }

    /** Optional read-only statistics do not hold the playback transport lock during I/O,
     * persist stale cookies, clear a new login, or show unrelated global error toasts. */
    private JsonObject listeningJson(NeteaseApi.Endpoint endpoint, Map<String, Object> body) throws IOException {
        Map<String, String> session;
        synchronized (this) {
            if (!isLoggedIn()) throw new IOException("请先登录网易云音乐");
            ensureDeviceCookies();
            session = new LinkedHashMap<>(cookies);
        }
        String response = endpoint.transport == NeteaseApi.Transport.WEAPI
                ? weapiCall(endpoint.weapiPath(), body, session, false)
                : eapiCall(endpoint.path, body, false, session, false);
        if (!java.util.Objects.equals(session.get("MUSIC_U"), cookies.get("MUSIC_U")))
            throw new IOException("账号已切换，请刷新听歌足迹");
        try {
            JsonElement root = JsonParser.parseString(response);
            if (!root.isJsonObject()) throw new IOException("网易云返回了无法识别的数据");
            return root.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            throw new IOException("网易云返回了无法识别的数据", e);
        }
    }

    /**
     * True recently-played song list, newest first. {@code /weapi/v1/play/record}
     * (formerly used here) is actually "听歌排行" — an all-time/weekly play-count
     * ranking, not a chronological history; its own reference-implementation
     * source comment says so verbatim, and in practice it barely moves from a
     * handful of casual plays. {@code /weapi/play-record/song/list} is the real
     * one: each entry carries a genuine {@code playTime} epoch-ms timestamp and
     * {@code multiTerminalInfo} naming the device that played it, confirmed
     * against this account's actual mobile-app history. Uses the logged-in
     * cookie's identity implicitly — no uid parameter, unlike the old endpoint.
     *
     * <p>Playback reports use a separate clientlog EAPI transport. A successful
     * ingestion response is not proof that this account's cloud statistics updated;
     * the server owns eligibility and propagation delay.
     */
    public List<NeteaseSong> recentPlayed(int limit) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("limit", limit);
        JsonObject obj = apiJson(NeteaseApi.RECENT_SONGS, body);
        List<NeteaseSong> out = new ArrayList<>();
        if (!obj.has("data") || !obj.get("data").isJsonObject()) return out;
        JsonObject data = obj.getAsJsonObject("data");
        if (!data.has("list") || !data.get("list").isJsonArray()) return out;
        for (JsonElement el : data.getAsJsonArray("list")) {
            if (!el.isJsonObject()) continue;
            JsonObject row = el.getAsJsonObject();
            String type = row.has("resourceType") && !row.get("resourceType").isJsonNull()
                    ? row.get("resourceType").getAsString() : "";
            if (!"SONG".equals(type)) continue;
            if (!row.has("data") || !row.get("data").isJsonObject()) continue;
            out.add(parseSong(row.getAsJsonObject("data")));
        }
        return out;
    }

    /** A play owns a cookie snapshot, so queued reports cannot migrate to a new account. */
    public PlaybackReport newPlaybackReport(long songId, long sourceId) {
        return newPlaybackReport(songId, sourceId, 0);
    }

    public PlaybackReport newPlaybackReport(long songId, long sourceId, long durationMs) {
        if (songId <= 0 || !isLoggedIn()) return null;
        // Device cookies were created on login/load; do not perform disk IO on the audio lane.
        return new PlaybackReport(songId, sourceId, durationMs, new LinkedHashMap<>(cookies));
    }

    public final class PlaybackReport {
        private final long songId;
        private final Map<String, String> session;
        private final NeteaseReportProgress progress;
        private final long pldSentAt;
        private volatile long durationSeconds;
        private PlaybackReport(long songId, long sourceId, long durationMs, Map<String, String> session) {
            this.songId = songId; this.session = session;
            durationSeconds = Math.max(0, durationMs / 1000);
            // A manual replay of a track that was already measured must not inherit a
            // report lane that believes the play is still open; it would swallow the
            // new play's PLD and leave only a PLV without its matching end.
            pldSentAt = lastPldSentAt.getOrDefault(songId, 0L);
            if (pldSentAt != 0L) lastPldSentAt.remove(songId);
            NeteaseDesktopLog desktop = new NeteaseDesktopLog(session);
            progress = new NeteaseReportProgress((heard, start) -> {
                // Check immediately before each request, including the PLD after a slow PLV.
                if (!java.util.Objects.equals(session.get("MUSIC_U"), cookies.get("MUSIC_U")))
                    throw new IOException("account changed");
                long began = System.currentTimeMillis();
                desktop.upload(NeteaseDesktopLog.event(songId, sourceId, durationSeconds, heard, start,
                        session.get("vipType")), start);
                if (!start) lastPldSentAt.put(songId, System.currentTimeMillis());
                Logger.info("netease NCBL {} file accepted, song={}, heard={}s, {}ms (cloud history may lag)",
                        start ? "PLV" : "PLD", songId, heard, System.currentTimeMillis() - began);
            });
        }
        public void duration(long millis) { if (millis > 0) durationSeconds = millis / 1000; }
        public long songId() { return songId; }
        /** The newest PLD this account sent for the song, 0 when none. */
        public long pldSentAt() { return pldSentAt; }
        public void started() { report(0, true); }
        public void finished(long seconds, String end) { report(seconds, false); }
        private void report(long seconds, boolean start) {
            if (!java.util.Objects.equals(session.get("MUSIC_U"), cookies.get("MUSIC_U"))) return;
            try {
                if (start) progress.started(); else progress.checkpoint(seconds);
            } catch (Exception e) {
                // The message can name only a transport status, never a server body.
                Logger.warn("netease NCBL {} failed, song={}, error={}: {}",
                        start ? "PLV" : "PLD", songId, e.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /** Compatibility entry point. Real playback sends startplay at the audible start. */
    public void scrobble(long songId, long sourceId, long seconds, String end) {
        PlaybackReport report = newPlaybackReport(songId, sourceId);
        if (report == null || seconds < 3) return;
        report.started(); report.finished(seconds, end);
    }

    // ---- Lyrics ----

    /**
     * Fetch lyric payloads for a song. Calls Netease's {@code /song/lyric/v1}
     * (the same endpoint SPlayer uses) requesting LRC + YRC + translation +
     * romanisation in one round trip. Throws if the API call fails;
     * an empty {@link dev.t1m3.qplayer.netease.dto.NeteaseLyric}
     * means "the song has no Netease-side lyrics".
     */
    public dev.t1m3.qplayer.netease.dto.NeteaseLyric lyric(long songId) throws IOException {
        if (songId <= 0L) throw new IOException("无效的歌词歌曲 ID");
        Map<String, Object> body = new HashMap<>();
        body.put("id", songId);
        body.put("cp", false);
        body.put("lv", 0);
        body.put("kv", 0);
        body.put("tv", 0);
        body.put("rv", 0);
        body.put("yv", 0);
        body.put("ytv", 0);
        body.put("yrv", 0);
        // Lyrics are loaded for the current, adjacent and queued songs. A failed
        // response must remain retryable, never become a cached 'no lyrics'.
        JsonObject obj = apiJson(NeteaseApi.LYRIC_NEW, body, false);
        ensureOk(obj, "获取歌词失败");
        dev.t1m3.qplayer.netease.dto.NeteaseLyric out =
                new dev.t1m3.qplayer.netease.dto.NeteaseLyric();
        out.lrc     = extractLyricField(obj, "lrc");
        out.yrc     = extractLyricField(obj, "yrc");
        out.tlyric  = extractLyricField(obj, "tlyric");
        out.romalrc = extractLyricField(obj, "romalrc");
        return out;
    }

    /** Each lyric flavour comes back as {@code {"lyric": "...", "version": N}}. */
    private static String extractLyricField(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonObject()) return null;
        JsonObject node = root.getAsJsonObject(key);
        if (!node.has("lyric") || node.get("lyric").isJsonNull()) return null;
        String s = node.get("lyric").getAsString();
        return s.isEmpty() ? null : s;
    }

    // ---- Like / unlike, liked-list, recommendations, user detail (N8) ----

    /**
     * Toggle "liked" state for a song (heart icon in mini-player). Persists
     * server-side into the user's "我喜欢的音乐" playlist. Returns true if
     * the call succeeded ({@code code == 200}), false otherwise. The
     * response also carries {@code playlistId} of 我喜欢的, but callers
     * usually only care about the success flag.
     */
    public boolean like(long songId, boolean isLike) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("trackId", songId);
        body.put("like", isLike);
        body.put("alg", "itembased");
        body.put("time", "3");
        // Quiet: toggleLike still has the playlist fallback (setFavorite), so an
        // intermediate risk-control failure must not surface as the final error.
        JsonObject obj = apiJson(NeteaseApi.LIKE, body, false);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code != 200) {
            Logger.warn("netease radio/like failed for {} (like={}): {}",
                    songId, isLike, truncate(obj.toString(), 300));
        }
        return code == 200;
    }

    /** The user's "我喜欢的音乐" playlist id (their first owned playlist), or 0. */
    public long favoritePlaylistId(long uid) throws IOException {
        if (uid == 0L) return 0L;
        for (NeteasePlaylist p : userPlaylists(uid, 1000)) {
            if (p.creatorUid == uid) return p.id;
        }
        return 0L;
    }

    /** Add/remove a track to the "我喜欢的音乐" playlist. The reliable favorite path
     *  when {@link #like} hits risk control (code 524, "当前环境异常"). */
    public boolean setFavorite(long uid, long songId, boolean add) throws IOException {
        long pid = favoritePlaylistId(uid);
        if (pid == 0L) return false;
        return manipulatePlaylistTracks(pid, songId, add);
    }

    /**
     * Add or remove a single track to/from an arbitrary playlist owned by the
     * user via {@code playlist/manipulate/tracks}. Handles the server's code-512
     * quirk: it rejects a trackIds list that (after its own de-dup against the
     * playlist) collapses to one entry, so on 512 we retry with the id doubled —
     * the same workaround api-enhanced uses.
     */
    public boolean manipulatePlaylistTracks(long pid, long songId, boolean add) throws IOException {
        JsonObject obj = manipulateTracksOnce(pid, "[" + songId + "]", add);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code == 512) {
            obj = manipulateTracksOnce(pid, "[" + songId + "," + songId + "]", add);
            code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        }
        if (code != 200) {
            Logger.warn("playlist/manipulate/tracks failed pid={} track={} (add={}): {}",
                    pid, songId, add, truncate(obj.toString(), 300));
        }
        return code == 200;
    }

    /** Batch editing keeps collection fast even for a full AI playlist. */
    public boolean manipulatePlaylistTracks(long pid, List<Long> songIds, boolean add) throws IOException {
        if (songIds.isEmpty()) return true;
        List<Long> ids = new ArrayList<>(new java.util.LinkedHashSet<>(songIds));
        JsonObject obj = manipulateTracksOnce(pid, new Gson().toJson(ids), add);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code == 512) {
            // NetEase may de-duplicate an otherwise valid batch to one track.
            for (long id : ids) if (!manipulatePlaylistTracks(pid, id, add)) return false;
            return true;
        }
        return code == 200;
    }

    public boolean reorderPlaylistTracks(long playlistId, List<Long> songIds) throws IOException {
        if (songIds.isEmpty()) return true;
        Map<String, Object> body = new HashMap<>();
        body.put("pid", playlistId);
        body.put("trackIds", new Gson().toJson(songIds));
        body.put("op", "update");
        JsonObject obj = apiJson(NeteaseApi.PLAYLIST_TRACKS, body);
        return obj.has("code") && !obj.get("code").isJsonNull() && obj.get("code").getAsInt() == 200;
    }

    /** Full authoritative ids for editing; a failed read must never look like an empty list. */
    public List<Long> playlistTrackIds(long playlistId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("id", playlistId); body.put("n", 100000); body.put("s", 8);
        return parsePlaylistTrackIds(apiJson(NeteaseApi.PLAYLIST_DETAIL, body));
    }

    static List<Long> parsePlaylistTrackIds(JsonObject response) throws IOException {
        if (!response.has("code") || response.get("code").isJsonNull() || response.get("code").getAsInt() != 200
                || !response.has("playlist") || !response.get("playlist").isJsonObject())
            throw new IOException("无法读取网易云歌单，请确认歌单仍存在且账号有权限");
        JsonObject playlist = response.getAsJsonObject("playlist");
        if (!playlist.has("trackIds") || !playlist.get("trackIds").isJsonArray())
            throw new IOException("网易云未返回完整歌曲列表，请重试");
        List<Long> ids = new ArrayList<>();
        for (JsonElement row : playlist.getAsJsonArray("trackIds")) {
            if (!row.isJsonObject() || !row.getAsJsonObject().has("id"))
                throw new IOException("网易云歌曲列表不完整，请重试");
            long id = row.getAsJsonObject().get("id").getAsLong();
            if (id <= 0 || ids.contains(id)) throw new IOException("网易云歌曲列表无效，请重试");
            ids.add(id);
        }
        return ids;
    }

    private JsonObject manipulateTracksOnce(long pid, String trackIdsJson, boolean add) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("op", add ? "add" : "del");
        body.put("pid", pid);
        body.put("trackIds", trackIdsJson);
        body.put("imme", "true");
        // Quiet: manipulatePlaylistTracks retries on code 512, and setFavorite uses this
        // as the fallback tier of toggleLike — an intermediate failure must not toast.
        return apiJson(NeteaseApi.PLAYLIST_TRACKS, body, false);
    }

    /**
     * Create a new playlist. {@code privacy} true makes it a "隐私歌单" (10),
     * otherwise a normal public playlist (0). Returns the new playlist id, or
     * {@code 0} on failure.
     */
    public long createPlaylist(String name, boolean privacy) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("privacy", privacy ? "10" : "0");
        body.put("type", "NORMAL");
        JsonObject obj = apiJson(NeteaseApi.PLAYLIST_CREATE, body);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code != 200) return 0L;
        if (obj.has("id") && !obj.get("id").isJsonNull()) return obj.get("id").getAsLong();
        if (obj.has("playlist") && obj.getAsJsonObject("playlist").has("id")) {
            return obj.getAsJsonObject("playlist").get("id").getAsLong();
        }
        return 0L;
    }

    /** Delete a playlist owned by the user via {@code playlist/remove}. */
    public boolean deletePlaylist(long playlistId) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("ids", "[" + playlistId + "]");
        JsonObject obj = apiJson(NeteaseApi.PLAYLIST_DELETE, body);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code != 200) {
            Logger.warn("playlist/remove failed id={}: {}", playlistId, truncate(obj.toString(), 300));
        }
        return code == 200;
    }

    /**
     * Upload an image to netease's NOS (object storage) and return its image id,
     * for use with {@link #updatePlaylistCover}. Two steps, mirroring the official
     * web client: {@code nos/token/alloc} (weapi-encrypted, like every other call
     * here) hands back an upload token + object key, then the raw image bytes are
     * POSTed straight to the NOS host with that token — unlike every other write in
     * this client, that second request is neither weapi- nor eapi-encrypted, just
     * an authenticated binary upload. Returns {@code 0} on any failure.
     */
    public long uploadImage(byte[] imageBytes, String filename) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) return 0L;
        Map<String, Object> body = new HashMap<>();
        body.put("bucket", "yyimgs");
        body.put("ext", "jpg");
        body.put("filename", filename == null || filename.isEmpty() ? "cover.jpg" : filename);
        body.put("local", false);
        body.put("nos_product", 0);
        body.put("return_body", "{\"code\":200,\"size\":\"$(ObjectSize)\"}");
        body.put("type", "other");
        JsonObject obj = apiJson(NeteaseApi.NOS_TOKEN_ALLOC, body);
        if (!obj.has("result") || !obj.get("result").isJsonObject()) {
            Logger.warn("nos/token/alloc failed: {}", truncate(obj.toString(), 300));
            return 0L;
        }
        JsonObject result = obj.getAsJsonObject("result");
        if (!result.has("objectKey") || !result.has("token") || !result.has("docId")) {
            Logger.warn("nos/token/alloc missing fields: {}", truncate(result.toString(), 300));
            return 0L;
        }
        String objectKey = result.get("objectKey").getAsString();
        String token = result.get("token").getAsString();
        long docId = result.get("docId").getAsLong();

        String uploadUrl = "https://nosup-hz1.127.net/yyimgs/" + objectKey
                + "?offset=0&complete=true&version=1.0";
        HttpURLConnection c = (HttpURLConnection) new URL(uploadUrl).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("x-nos-token", token);
            c.setRequestProperty("Content-Type", "image/jpeg");
            try (OutputStream os = c.getOutputStream()) {
                os.write(imageBytes);
            }
            int status = c.getResponseCode();
            if (status / 100 != 2) {
                Logger.warn("NOS image upload failed: HTTP {}", status);
                return 0L;
            }
        } finally {
            c.disconnect();
        }
        return docId;
    }

    /** Bind a previously-uploaded image (see {@link #uploadImage}) as a playlist's cover. */
    public boolean updatePlaylistCover(long playlistId, long coverImgId) throws IOException {
        if (playlistId <= 0 || coverImgId <= 0) return false;
        Map<String, Object> body = new HashMap<>();
        body.put("id", playlistId);
        body.put("coverImgId", coverImgId);
        JsonObject obj = apiJson(NeteaseApi.PLAYLIST_COVER_UPDATE, body);
        int code = obj.has("code") && !obj.get("code").isJsonNull() ? obj.get("code").getAsInt() : -1;
        if (code != 200) {
            Logger.warn("playlist/cover/update failed id={}: {}", playlistId, truncate(obj.toString(), 300));
        }
        return code == 200;
    }

    /**
     * Fetch the set of song IDs the user has marked as "liked". Used to
     * paint the heart icon on track rows + mini-player without one
     * request per row. Empty set when not logged in.
     */
    public java.util.Set<Long> likedSongIds(long uid) throws IOException {
        if (uid == 0L) return java.util.Collections.emptySet();
        Map<String, Object> body = new HashMap<>();
        body.put("uid", uid);
        JsonObject obj = apiJson(NeteaseApi.LIKE_LIST, body);
        java.util.Set<Long> out = new java.util.HashSet<>();
        if (obj.has("ids") && obj.get("ids").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("ids")) {
                if (el.isJsonPrimitive()) out.add(el.getAsLong());
            }
        }
        return out;
    }

    /**
     * Daily personalised recommendations (the "每日推荐" feed). Up to ~30
     * tracks tied to the user's listening history; refreshes server-side
     * once a day. Returns an empty list when not logged in.
     */
    public List<NeteaseSong> recommendSongs() throws IOException {
        if (!isLoggedIn()) return java.util.Collections.emptyList();
        JsonObject obj = apiJson(NeteaseApi.RECOMMEND_SONGS,
                new HashMap<String, Object>());
        ensureOk(obj, "获取每日推荐失败");
        List<NeteaseSong> out = new ArrayList<>();
        // Newer schema: data.dailySongs[]. Legacy: recommend[].
        JsonArray arr = null;
        if (obj.has("data") && obj.get("data").isJsonObject()) {
            JsonObject data = obj.getAsJsonObject("data");
            if (data.has("dailySongs") && data.get("dailySongs").isJsonArray()) {
                arr = data.getAsJsonArray("dailySongs");
            }
        }
        if (arr == null && obj.has("recommend") && obj.get("recommend").isJsonArray()) {
            arr = obj.getAsJsonArray("recommend");
        }
        if (arr != null) {
            for (JsonElement el : arr) {
                if (el.isJsonObject()) out.add(parseSong(el.getAsJsonObject()));
            }
        }
        Map<Long, String> reasons = new HashMap<>();
        for (JsonElement element : homeArray(homeObject(obj, "data"), "recommendReasons")) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            try {
                long id = Long.parseLong(stringValue(row, "songId"));
                String reason = stringValue(row, "reason");
                if (!reason.isEmpty()) reasons.put(id, reason);
            } catch (NumberFormatException ignored) { }
        }
        for (NeteaseSong song : out) {
            if (song.recommendReason.isEmpty()) song.recommendReason = reasons.getOrDefault(song.id, "");
        }
        return out;
    }

    /**
     * Fetch NetEase's Personal FM stream (shown as "私人漫游" in the app).
     * This is the real mobile endpoint, not the third-party {@code /personal_fm}
     * proxy route. The response contains complete song objects in {@code data}.
     */
    public List<NeteaseSong> personalFm() throws IOException {
        return personalFm(3);
    }

    /** Fetch the next server-side Personal FM recommendation batch. */
    public List<NeteaseSong> personalFm(int limit) throws IOException {
        if (!isLoggedIn()) return java.util.Collections.emptyList();
        Map<String, Object> body = new LinkedHashMap<>();
        // DEFAULT is NetEase's algorithm-driven Personal FM stream. Keep this
        // explicit so a server-side default change cannot turn this into a
        // generic recommendation list.
        body.put("mode", "DEFAULT");
        body.put("subMode", "");
        body.put("limit", Math.max(1, Math.min(limit, 50)));
        JsonObject obj = apiJson(NeteaseApi.PERSONAL_FM, body);
        ensureOk(obj, "获取私人漫游失败");
        JsonArray arr = null;
        if (obj.has("data") && obj.get("data").isJsonArray()) {
            arr = obj.getAsJsonArray("data");
        } else if (obj.has("result") && obj.get("result").isJsonArray()) {
            arr = obj.getAsJsonArray("result");
        }
        if (arr == null) return Collections.emptyList();

        List<NeteaseSong> out = new ArrayList<>();
        for (JsonElement element : arr) {
            if (!element.isJsonObject()) continue;
            NeteaseSong song = parseSong(element.getAsJsonObject());
            if (song.id != 0L) out.add(song);
        }
        return out;
    }

    /** Mark a skipped Personal FM track as not interesting to NetEase. */
    public void personalFmTrash(long songId, long listenedSeconds) throws IOException {
        if (!isLoggedIn() || songId == 0L) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("songId", songId);
        body.put("alg", "RT");
        body.put("time", Math.max(0L, listenedSeconds));
        JsonObject obj = apiJson(NeteaseApi.PERSONAL_FM_TRASH, body);
        ensureOk(obj, "跳过私人漫游歌曲失败");
    }

    /**
     * NetEase's "心动模式" continuation for a song inside a playlist. The API
     * sometimes embeds complete {@code songInfo} objects and sometimes returns
     * only IDs; normalize both shapes to full songs while preserving server order.
     */
    public List<NeteaseSong> intelligenceSongs(long songId, long playlistId,
            long startSongId) throws IOException {
        if (!isLoggedIn() || songId == 0L || playlistId == 0L) {
            return Collections.emptyList();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("songId", songId);
        body.put("type", "fromPlayOne");
        body.put("playlistId", playlistId);
        body.put("startMusicId", startSongId == 0L ? songId : startSongId);
        body.put("count", 1);
        JsonObject obj = apiJson(NeteaseApi.PLAYMODE_INTELLIGENCE_LIST, body);
        ensureOk(obj, "获取心动推荐失败");

        List<NeteaseSong> embedded = new ArrayList<>();
        List<Long> orderedIds = new ArrayList<>();
        if (obj.has("data") && obj.get("data").isJsonArray()) {
            for (JsonElement element : obj.getAsJsonArray("data")) {
                if (!element.isJsonObject()) continue;
                JsonObject row = element.getAsJsonObject();
                JsonObject song = row.has("songInfo") && row.get("songInfo").isJsonObject()
                        ? row.getAsJsonObject("songInfo") : null;
                if (song != null) {
                    NeteaseSong parsed = parseSong(song);
                    if (parsed.id != 0L) {
                        embedded.add(parsed);
                        orderedIds.add(parsed.id);
                    }
                } else if (row.has("id") && !row.get("id").isJsonNull()) {
                    orderedIds.add(row.get("id").getAsLong());
                }
            }
        }
        if (orderedIds.isEmpty()) return Collections.emptyList();
        if (embedded.size() == orderedIds.size()) return embedded;

        List<NeteaseSong> details = songDetails(orderedIds);
        Map<Long, NeteaseSong> byId = new HashMap<>();
        for (NeteaseSong song : details) byId.put(song.id, song);
        List<NeteaseSong> ordered = new ArrayList<>();
        for (Long id : orderedIds) {
            NeteaseSong song = byId.get(id);
            if (song != null) ordered.add(song);
        }
        return ordered;
    }

    // ---- Listen Together -------------------------------------------------

    public static final class TogetherUser {
        public long userId;
        public String nickname = "";
        public String avatarUrl = "";
    }

    public static final class TogetherRoom {
        public String roomId = "";
        public long creatorId;
        public long effectiveDurationMs;
        public final List<TogetherUser> users = new ArrayList<>();
    }

    public static final class TogetherStatus {
        public boolean inRoom;
        public String status = "";
        public TogetherRoom room;
    }

    public static final class TogetherCommand {
        public long userId;
        public String commandType = "";
        public long formerSongId;
        public long targetSongId;
        public long progressMs;
        public String playStatus = "";
        public long serverSeq;

        public boolean shouldPlay() {
            if ("PAUSE".equalsIgnoreCase(commandType)
                    || "PAUSE".equalsIgnoreCase(playStatus)) return false;
            return "PLAY".equalsIgnoreCase(commandType)
                    || "GOTO".equalsIgnoreCase(commandType)
                    || "NEXT".equalsIgnoreCase(commandType)
                    || "PREV".equalsIgnoreCase(commandType)
                    || "PLAY".equalsIgnoreCase(playStatus)
                    || "PLAYING".equalsIgnoreCase(playStatus);
        }
    }

    public static final class TogetherSnapshot {
        public final List<Long> songIds = new ArrayList<>();
        public final List<Map<String, Object>> versions = new ArrayList<>();
        public TogetherCommand command;
    }

    public TogetherRoom createTogetherRoom() throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refer", "songplay_more");
        JsonObject obj = apiJson(NeteaseApi.LISTEN_TOGETHER_ROOM_CREATE, body);
        ensureOk(obj, "创建一起听房间失败");
        TogetherRoom room = parseRoomFromResponse(obj);
        if (room == null || room.roomId.isEmpty()) throw new IOException("创建房间未返回 roomId");
        return room;
    }

    public TogetherStatus togetherStatus() throws IOException {
        JsonObject obj = apiJson(NeteaseApi.LISTEN_TOGETHER_STATUS,
                new LinkedHashMap<String, Object>());
        ensureOk(obj, "获取一起听状态失败");
        TogetherStatus out = new TogetherStatus();
        JsonObject data = object(obj, "data");
        if (data == null) return out;
        out.inRoom = bool(data, "inRoom", false);
        out.status = string(data, "status");
        out.room = parseRoom(object(data, "roomInfo"));
        return out;
    }

    public boolean togetherRoomJoinable(String roomId) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        JsonObject obj = apiJson(NeteaseApi.LISTEN_TOGETHER_ROOM_CHECK, body);
        ensureOk(obj, "检查一起听房间失败");
        JsonObject data = object(obj, "data");
        return data != null && bool(data, "joinable", false);
    }

    public TogetherRoom acceptTogetherInvitation(String roomId, long inviterId)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refer", "inbox_invite");
        body.put("roomId", roomId);
        body.put("inviterId", inviterId);
        JsonObject obj = apiJson(NeteaseApi.LISTEN_TOGETHER_INVITATION_ACCEPT, body);
        ensureOk(obj, "加入一起听房间失败");
        TogetherRoom room = parseRoomFromResponse(obj);
        if (room == null) {
            room = new TogetherRoom();
            room.roomId = roomId;
        }
        return room;
    }

    public TogetherSnapshot togetherSnapshot(String roomId) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        JsonObject obj = apiJson(NeteaseApi.LISTEN_TOGETHER_SYNC_PLAYLIST_GET, body);
        ensureOk(obj, "同步一起听状态失败");
        TogetherSnapshot out = new TogetherSnapshot();
        JsonObject data = object(obj, "data");
        if (data == null) return out;
        JsonObject playlist = object(data, "playlist");
        if (playlist != null) {
            String mode = string(playlist, "playMode");
            boolean random = mode.toUpperCase(java.util.Locale.ROOT).contains("RANDOM")
                    || mode.toUpperCase(java.util.Locale.ROOT).contains("SHUFFLE");
            JsonObject list = object(playlist, random ? "randomList" : "displayList");
            if (list == null) list = object(playlist, "displayList");
            JsonArray result = list == null ? null : array(list, "result");
            if (result != null) {
                for (JsonElement element : result) {
                    try { out.songIds.add(element.getAsLong()); } catch (Throwable ignored) { }
                }
            }
            JsonArray versions = array(playlist, "version");
            if (versions != null) {
                for (JsonElement element : versions) {
                    if (!element.isJsonObject()) continue;
                    JsonObject version = element.getAsJsonObject();
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("userId", number(version, "userId", 0L));
                    value.put("version", number(version, "version", 0L));
                    out.versions.add(value);
                }
            }
        }
        JsonObject command = object(data, "playCommand");
        if (command == null) command = object(data, "commandInfo");
        if (command != null) {
            TogetherCommand parsed = new TogetherCommand();
            parsed.userId = number(command, "userId", 0L);
            parsed.commandType = string(command, "commandType");
            parsed.formerSongId = number(command, "formerSongId", 0L);
            parsed.targetSongId = number(command, "targetSongId", 0L);
            parsed.progressMs = number(command, "progress", 0L);
            parsed.playStatus = string(command, "playStatus");
            parsed.serverSeq = number(command, "serverSeq", 0L);
            out.command = parsed;
        }
        return out;
    }

    public void reportTogetherPlaylist(String roomId, long userId, long version,
            List<Long> songIds) throws IOException {
        List<String> ids = new ArrayList<>();
        if (songIds != null) for (Long id : songIds) if (id != null && id != 0L) ids.add(String.valueOf(id));
        Map<String, Object> versionRow = new LinkedHashMap<>();
        versionRow.put("userId", userId);
        versionRow.put("version", version);
        Map<String, Object> playlist = new LinkedHashMap<>();
        playlist.put("commandType", "REPLACE");
        playlist.put("version", Collections.singletonList(versionRow));
        playlist.put("anchorSongId", "");
        playlist.put("anchorPosition", -1);
        playlist.put("randomList", ids);
        playlist.put("displayList", ids);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        body.put("playlistParam", gson.toJson(playlist));
        ensureOk(apiJson(NeteaseApi.LISTEN_TOGETHER_SYNC_LIST_REPORT, body),
                "上报一起听队列失败");
    }

    public void reportTogetherCommand(String roomId, String commandType, long progressMs,
            boolean playing, long formerSongId, long targetSongId, long clientSeq)
            throws IOException {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("commandType", commandType);
        command.put("progress", Math.max(0L, progressMs));
        command.put("playStatus", playing ? "PLAY" : "PAUSE");
        command.put("formerSongId", String.valueOf(formerSongId));
        command.put("targetSongId", String.valueOf(targetSongId));
        command.put("clientSeq", clientSeq);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        body.put("commandInfo", gson.toJson(command));
        ensureOk(apiJson(NeteaseApi.LISTEN_TOGETHER_PLAY_COMMAND_REPORT, body),
                "上报一起听播放状态失败");
    }

    public void togetherHeartbeat(String roomId, long songId, boolean playing,
            long progressMs) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        body.put("songId", songId);
        body.put("playStatus", playing ? "PLAY" : "PAUSE");
        body.put("progress", Math.max(0L, progressMs));
        ensureOk(apiJson(NeteaseApi.LISTEN_TOGETHER_HEARTBEAT, body),
                "一起听心跳失败");
    }

    public void endTogetherRoom(String roomId) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roomId", roomId);
        ensureOk(apiJson(NeteaseApi.LISTEN_TOGETHER_END, body), "退出一起听失败");
    }

    private TogetherRoom parseRoomFromResponse(JsonObject response) {
        JsonObject data = object(response, "data");
        if (data == null) return null;
        JsonObject roomInfo = object(data, "roomInfo");
        return parseRoom(roomInfo != null ? roomInfo : data);
    }

    private static TogetherRoom parseRoom(JsonObject room) {
        if (room == null) return null;
        TogetherRoom out = new TogetherRoom();
        out.roomId = string(room, "roomId");
        out.creatorId = number(room, "creatorId", 0L);
        out.effectiveDurationMs = number(room, "effectiveDurationMs", 0L);
        JsonArray users = array(room, "roomUsers");
        if (users == null) users = array(room, "users");
        if (users != null) {
            for (JsonElement element : users) {
                if (!element.isJsonObject()) continue;
                JsonObject value = element.getAsJsonObject();
                TogetherUser user = new TogetherUser();
                user.userId = number(value, "userId", 0L);
                user.nickname = string(value, "nickname");
                user.avatarUrl = string(value, "avatarUrl");
                out.users.add(user);
            }
        }
        return out;
    }

    private static void ensureOk(JsonObject object, String fallback) throws IOException {
        int code = object != null ? (int) number(object, "code", 200L) : -1;
        if (code == 200) return;
        String message = object == null ? "" : neteaseMessage(object);
        throw new IOException(message == null || message.isEmpty() ? fallback + " (" + code + ")" : message);
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : null;
    }

    private static JsonArray array(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonArray()
                ? parent.getAsJsonArray(key) : null;
    }

    private static String string(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) return "";
        try { return parent.get(key).getAsString(); } catch (Throwable ignored) { return ""; }
    }

    private static long number(JsonObject parent, String key, long fallback) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) return fallback;
        try { return parent.get(key).getAsLong(); } catch (Throwable ignored) { return fallback; }
    }

    private static boolean bool(JsonObject parent, String key, boolean fallback) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) return fallback;
        try { return parent.get(key).getAsBoolean(); } catch (Throwable ignored) { return fallback; }
    }

    /**
     * Full profile of the given uid — used for the sidebar account
     * display. Returns {@code null} when the API blocks us or uid is 0.
     */
    public dev.t1m3.qplayer.netease.dto.NeteaseUser userDetail(long uid) throws IOException {
        if (uid == 0L) return null;
        Map<String, Object> body = new HashMap<>();
        JsonObject obj = apiJson(NeteaseApi.userDetail(uid), body);
        if (!obj.has("profile") || !obj.get("profile").isJsonObject()) return null;
        JsonObject p = obj.getAsJsonObject("profile");
        dev.t1m3.qplayer.netease.dto.NeteaseUser out =
                new dev.t1m3.qplayer.netease.dto.NeteaseUser();
        out.uid = uid;
        if (p.has("nickname") && !p.get("nickname").isJsonNull())
            out.nickname = p.get("nickname").getAsString();
        if (p.has("avatarUrl") && !p.get("avatarUrl").isJsonNull())
            out.avatarUrl = p.get("avatarUrl").getAsString();
        if (p.has("vipType")) out.vipType = p.get("vipType").getAsInt();
        if (p.has("signature") && !p.get("signature").isJsonNull())
            out.signature = p.get("signature").getAsString();
        if (obj.has("level")) out.level = obj.get("level").getAsInt();
        return out;
    }

    /** Best-effort server-side logout. Local cookies are wiped regardless. */
    public void logout() {
        try {
            apiJson(NeteaseApi.LOGOUT, new HashMap<String, Object>(), false);
        } catch (Throwable e) {
            // Server may 301; that's fine, we clear locally anyway.
        }
        clearCookies();
    }

    // ---- QR login (N2) ----

    /**
     * Step 1 of QR login: ask the server for a fresh unikey. The user
     * encodes {@link #qrLoginContent(String)} into a QR image and scans
     * it with the Netease mobile app.
     */
    public String qrLoginKey() throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("type", 3);
        JsonObject obj = apiJson(NeteaseApi.QR_LOGIN_KEY, body, false);
        if (!obj.has("unikey") || obj.get("unikey").isJsonNull()) {
            throw new IOException("qrLoginKey: no unikey in response: " + obj);
        }
        return obj.get("unikey").getAsString();
    }

    /**
     * The URL/text payload that the QR image must encode. Netease's
     * official client recognises this exact format; SPlayer / Binaryify
     * both use {@code /login/qr/create} to wrap this in a base64 PNG,
     * but it's just the same string under the hood and we can render
     * the QR ourselves later (saves a round-trip).
     */
    public String qrLoginContent(String unikey) {
        return "https://music.163.com/login?codekey=" + unikey;
    }

    /**
     * Render the QR for the given unikey as a square module matrix
     * ({@code true} = dark). Encoded locally via ZXing — netease's own
     * {@code /weapi/login/qrcode/get} now 404s, and SPlayer / Binaryify
     * generate the QR client-side from {@link #qrLoginContent(String)}.
     *
     * <p>Returns a {@code boolean[size][size]} grid the host renders itself
     * (QML draws rects, no platform image encoder needed), or {@code null}
     * if ZXing fails. The matrix is unpadded — callers add quiet-zone margin.
     */
    public boolean[][] qrMatrix(String unikey) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix bits = new QRCodeWriter().encode(
                    qrLoginContent(unikey), BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = bits.getWidth();
            int h = bits.getHeight();
            boolean[][] out = new boolean[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out[y][x] = bits.get(x, y);
                }
            }
            return out;
        } catch (Throwable e) {
            Logger.warn("qrMatrix failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Step 2: poll for QR scan status. Returns the netease status code:
     * <ul>
     *   <li>800 — key expired (need a new {@link #qrLoginKey()})</li>
     *   <li>801 — waiting for user to scan</li>
     *   <li>802 — scanned, waiting for user to confirm</li>
     *   <li>803 — success; Set-Cookie has captured MUSIC_U etc.</li>
     * </ul>
     */
    public int qrLoginCheck(String unikey) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("key", unikey);
        body.put("type", 3);
        JsonObject obj = apiJson(NeteaseApi.QR_LOGIN_CHECK, body, false);
        if (!obj.has("code")) {
            throw new IOException("qrLoginCheck: no code in response: " + obj);
        }
        return obj.get("code").getAsInt();
    }

    // ---- Cookie persistence ----

    public boolean hasCookie(String name) {
        return cookies.containsKey(name);
    }

    public boolean isLoggedIn() {
        return cookies.containsKey("MUSIC_U");
    }

    /** Explicit, user-initiated encrypted account transfer only. Never log this header. */
    public synchronized String exportLoginCookies() {
        return isLoggedIn() ? cookieHeader(new LinkedHashMap<>(cookies)) : "";
    }

    public synchronized void clearCookies() {
        cookies.clear();
        persistedLoginCredential = false;
        fallbackNoticeSent = false;
        synchronized (credentialEventLock) {
            credentialUnlockPending = false;
        }
        try { Files.deleteIfExists(cookieFile); } catch (IOException ignored) {}
        try { Files.deleteIfExists(legacyCookieFile); } catch (IOException ignored) {}
    }

    /** Retry a locked system credential store from a worker thread. */
    public boolean retryCredentialLoad() {
        return loadCookies(true);
    }

    /** Permanently switch this profile to owner-only key storage after unlock failed. */
    public synchronized boolean fallbackUnreadableCredentials() {
        try {
            cookieCipher.forceOwnerOnlyFallback();
            clearCookies();
            fallbackNoticeSent = true;
            return true;
        } catch (IOException e) {
            Logger.warn("Netease: credential fallback failed: {}", e.getMessage());
            emitCredentialEvent(CredentialEvent.KEYSTORE_READ_FAILED);
            return false;
        }
    }

    /** Discard unreadable credentials but keep system-store protection for a new login. */
    public synchronized boolean resetUnreadableCredentialsForPlatformLogin() {
        try {
            // Never discard the old encrypted login until its platform store has
            // answered successfully. A locked KWallet therefore returns the user
            // to the choice dialog with every file still intact.
            cookieCipher.verifyPlatformProtectionAvailable();
            cookieCipher.resetForPlatformProtection();
            clearCookies();
            fallbackNoticeSent = false;
            return true;
        } catch (Exception e) {
            Logger.warn("Netease: credential reset for encrypted login failed: {}",
                    e.getMessage());
            return false;
        }
    }

    public boolean usesOwnerOnlyCredentialProtection() {
        return cookieCipher.usesOwnerOnlyProtection();
    }

    /** Migrate the current local data key back into the system credential store. */
    public synchronized boolean enableSystemCredentialProtection() {
        try {
            cookieCipher.enablePlatformProtectionInteractively();
            fallbackNoticeSent = false;
            emitCredentialEvent(CredentialEvent.ENCRYPTED);
            return true;
        } catch (Exception e) {
            Logger.warn("Netease: enabling system credential protection failed: {}",
                    e.getMessage());
            fallbackNoticeSent = true;
            emitCredentialEvent(CredentialEvent.KEYSTORE_FALLBACK);
            return false;
        }
    }

    private String cookieHeader() {
        ensureDeviceCookies();
        return cookieHeader(cookies);
    }

    private String cookieHeader(Map<String, String> sourceCookies) {
        Map<String, String> all = new LinkedHashMap<>(sourceCookies);
        // Device-identity flags a real client sends. Without them Netease's risk
        // control rejects sensitive ops (favoriting) with code 524 "当前环境异常".
        all.put("__remember_me", "true");
        all.put("ntes_kaola_ad", "1");
        all.put("_ntes_nnid", all.getOrDefault("_ntes_nuid", "") + "," + System.currentTimeMillis());
        all.put("WNMCID", wnmcid());
        all.put("WEVNSM", "1.0.0");
        all.put("NMTID", randomHex(16));
        all.put("os", "pc");
        all.put("osver", "Microsoft-Windows-10-Professional-build-19045-64bit");
        all.put("appver", "3.1.17.204416");
        all.put("channel", "netease");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Persist a stable deviceId + _ntes_nuid so the client fingerprint doesn't change
     *  between requests (a moving fingerprint itself trips risk control). */
    private void ensureDeviceCookies() {
        boolean changed = false;
        if (!cookies.containsKey("_ntes_nuid")) { cookies.put("_ntes_nuid", randomHex(32)); changed = true; }
        if (!cookies.containsKey("deviceId")) { cookies.put("deviceId", randomHex(16)); changed = true; }
        if (changed) saveCookies();
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(b);
        StringBuilder s = new StringBuilder(bytes * 2);
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 0xf, 16));
            s.append(Character.forDigit(x & 0xf, 16));
        }
        return s.toString();
    }

    private static String wnmcid() {
        StringBuilder s = new StringBuilder();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) s.append((char) ('a' + r.nextInt(26)));
        return s.append('.').append(System.currentTimeMillis()).append(".01.0").toString();
    }

    /** A random IP in a mainland-China allocation (first octets from CN ranges). */
    private static String randomChinaIp() {
        int[] firsts = {36, 39, 42, 58, 59, 60, 101, 106, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 175, 180, 182, 183, 202, 203, 210, 211, 218, 219, 220, 221, 222, 223};
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        int a = firsts[r.nextInt(firsts.length)];
        return a + "." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }

    private void captureSetCookies(List<String> setCookies) {
        if (captureSetCookiesInto(cookies, setCookies)) saveCookies();
    }

    private static boolean captureSetCookiesInto(Map<String, String> target,
            List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return false;
        boolean changed = false;
        for (String sc : setCookies) {
            int eq = sc.indexOf('=');
            int semi = sc.indexOf(';');
            if (eq <= 0) continue;
            String name = sc.substring(0, eq).trim();
            String val = sc.substring(eq + 1, semi > eq ? semi : sc.length()).trim();
            // Skip Set-Cookie attribute lines (Domain, Path, etc).
            if (name.equalsIgnoreCase("domain") || name.equalsIgnoreCase("path")
                    || name.equalsIgnoreCase("expires") || name.equalsIgnoreCase("max-age")
                    || name.equalsIgnoreCase("samesite")) continue;
            String prev = target.put(name, val);
            if (prev == null || !prev.equals(val)) changed = true;
        }
        return changed;
    }

    private synchronized boolean loadCookies(boolean interactive) {
        boolean encrypted = Files.exists(cookieFile);
        Path source = encrypted ? cookieFile : legacyCookieFile;
        if (!Files.exists(source)) return false;
        cookies.clear();
        persistedLoginCredential = false;
        synchronized (credentialEventLock) {
            credentialUnlockPending = false;
        }
        try {
            String txt = encrypted
                    ? new String(interactive
                            ? cookieCipher.decryptInteractively(Files.readAllBytes(cookieFile))
                            : cookieCipher.decrypt(Files.readAllBytes(cookieFile)),
                            StandardCharsets.UTF_8)
                    : StorageFiles.readUtf8(legacyCookieFile);
            if (txt == null || txt.trim().isEmpty()) return false;
            JsonElement el = new JsonParser().parse(txt);
            if (!el.isJsonObject()) throw new IOException("cookie payload is not an object");
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    cookies.put(e.getKey(), e.getValue().getAsString());
                }
            }
            if (!encrypted) {
                // Write and authenticate the encrypted copy before removing the only
                // plaintext source. A failed migration therefore remains recoverable.
                if (!saveCookies()) {
                    Logger.warn("Netease: encrypted cookie migration failed; plaintext retained");
                    return false;
                }
                Files.deleteIfExists(legacyCookieFile);
                Logger.info("Netease: migrated plaintext cookie store to encrypted storage");
            } else {
                persistedLoginCredential = hasLoginCredential();
                if (persistedLoginCredential) {
                    CredentialCipher.KeyAccess access = cookieCipher.lastKeyAccess();
                    if (access == CredentialCipher.KeyAccess.PLATFORM_READ) {
                        markCredentialUnlockPending();
                    } else if (access == CredentialCipher.KeyAccess.PLATFORM_MIGRATED) {
                        emitCredentialEvent(CredentialEvent.ENCRYPTED);
                    } else if (access == CredentialCipher.KeyAccess.OWNER_ONLY_FALLBACK) {
                        fallbackNoticeSent = true;
                        emitCredentialEvent(CredentialEvent.KEYSTORE_FALLBACK);
                    }
                }
            }
            Logger.info("Netease: loaded {} cookies from encrypted storage", cookies.size());
            return true;
        } catch (Exception e) {
            cookies.clear();
            Logger.warn("Netease: cookie load failed: {}", e.getMessage());
            if (encrypted) emitCredentialEvent(CredentialEvent.KEYSTORE_READ_FAILED);
            return false;
        }
    }

    private synchronized boolean saveCookies() {
        try {
            Map<String, Object> out = new LinkedHashMap<>(cookies);
            byte[] plaintext = gson.toJson(out).getBytes(StandardCharsets.UTF_8);
            final byte[] encrypted;
            try {
                encrypted = cookieCipher.encrypt(plaintext);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
            StorageFiles.writeBytesAtomic(cookieFile, encrypted);
            StorageFiles.restrictOwnerOnly(cookieFile);
            boolean hasCredential = hasLoginCredential();
            if (hasCredential) {
                boolean newlyPersisted = !persistedLoginCredential;
                persistedLoginCredential = true;
                CredentialCipher.KeyAccess access = cookieCipher.lastKeyAccess();
                if (access == CredentialCipher.KeyAccess.OWNER_ONLY_FALLBACK) {
                    if (!fallbackNoticeSent) {
                        fallbackNoticeSent = true;
                        emitCredentialEvent(CredentialEvent.KEYSTORE_FALLBACK);
                    }
                } else if (newlyPersisted) {
                    emitCredentialEvent(CredentialEvent.ENCRYPTED);
                }
            } else {
                persistedLoginCredential = false;
            }
            return true;
        } catch (Exception e) {
            Logger.warn("Netease: cookie save failed: {}", e.getMessage());
            if (hasLoginCredential()) {
                emitCredentialEvent(CredentialEvent.KEYSTORE_READ_FAILED);
            }
            return false;
        }
    }

    private boolean hasLoginCredential() {
        String musicU = cookies.get("MUSIC_U");
        return musicU != null && !musicU.isEmpty();
    }

    // ---- HTTP helpers ----

    private static String formEncode(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(urlEnc(e.getKey())).append('=').append(urlEnc(e.getValue()));
        }
        return sb.toString();
    }

    private static String urlEnc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readAll(InputStream is) throws IOException {
        return new String(readAllBytes(is), StandardCharsets.UTF_8);
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        if (is == null) return new byte[0];
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
