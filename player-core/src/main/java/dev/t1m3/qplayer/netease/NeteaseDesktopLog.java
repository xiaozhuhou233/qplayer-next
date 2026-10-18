package dev.t1m3.qplayer.netease;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/** Native NCBL v3 desktop reporting, following api-enhanced/util/ncbl.js (MIT).
 * Authentication stays in memory and is only sent to NetEase's HTTPS endpoint. */
final class NeteaseDesktopLog {
    static final String UPLOAD = "https://clientlog3.music.163.com/api/clientlog/encrypt/upload?multiupload=true";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigInteger MODULUS = new BigInteger("fd90bd466ff9bc8a3fec2fbcf263b90d5c564879fa5d7aab89b31c1d5cb4139d", 16);
    private final Map<String, String> identity = new LinkedHashMap<>();

    NeteaseDesktopLog(Map<String, String> cookies) {
        String[] fields = {"JSESSIONID-WYYY", "MUSIC_U", "NMTID", "__csrf", "_ntes_nuid", "clientSign"};
        for (String key : fields) identity.put(key, cookies.getOrDefault(key, ""));
        identity.put("WEVNSM", value(cookies, "WEVNSM", "1.0.0"));
        identity.put("WNMCID", value(cookies, "WNMCID", UUID.randomUUID().toString().substring(0, 6) + "." + System.currentTimeMillis() + ".01.0"));
        identity.put("_iuqxldmzr_", "33");
        identity.put("_ntes_nnid", value(cookies, "_ntes_nnid", ","));
        identity.put("appver", "3.1.35.205293");
        identity.put("channel", "netease");
        identity.put("deviceId", value(cookies, "deviceId", value(cookies, "sDeviceId", "")));
        identity.put("mode", value(cookies, "mode", ""));
        identity.put("ntes_kaola_ad", "1");
        identity.put("os", "pc");
        identity.put("osver", "Microsoft-Windows-10-Professional-build-19045-64bit");
    }

    private static String value(Map<String, String> map, String key, String fallback) {
        String v = map.get(key); return v == null || v.isEmpty() ? fallback : v;
    }

    JsonObject metadata() {
        JsonObject meta = new JsonObject();
        identity.forEach(meta::addProperty);
        return meta;
    }

    String cookieHeader() {
        StringBuilder out = new StringBuilder();
        identity.forEach((k, v) -> out.append(k).append('=').append(v.replace("\r", "").replace("\n", "")).append("; "));
        return out.append("__remember_me=true").toString();
    }

    static JsonObject event(long songId, long sourceId, long durationSeconds, long heard, boolean start, String vip) {
        JsonObject data = new JsonObject();
        data.addProperty("mode", "circulation"); data.addProperty("download", 0);
        data.addProperty("alg", ""); data.addProperty("status", "front");
        data.addProperty("id", Long.toString(songId)); data.addProperty("type", "song");
        data.addProperty("is_listentogether", 0); data.addProperty("source", "list");
        data.addProperty("is_heart", 0); data.addProperty("resource_ratio", "");
        data.addProperty("resource_time", Math.max(0, durationSeconds));
        data.addProperty("musiceffect_id", start ? "" : "1001"); data.addProperty("app_mode", start ? 2 : 1);
        data.addProperty("bitrate", 320); data.addProperty("bitrate_level", "exhigh");
        long source = sourceId > 0 ? sourceId : songId;
        long now = System.currentTimeMillis();
        String prefix = "[F:63][" + now + "#" + (start ? "933" : "616") + "#3.1.35#205293#c9156c3][e][2]";
        data.addProperty("_addrefer", prefix + (start
                ? "[23][cell_pc_songlist_song:2|page_pc_songlist_songflow|page_mine_like_music][" + songId + ":song:x:x|:::|" + source + ":list::]"
                : "[92][btn_pc_cover_play|cell_pc_songlist_song:6|page_pc_songlist_songflow|page_mine_like_music][:::|" + songId + ":song:x:x|:::|" + source + ":list::]"));
        JsonArray refs = new JsonArray();
        if (start) {
            refs.add("[F:26][s][18][_ai]"); refs.add("[F:26][s][12][_ai]");
            refs.add(prefix + "[8][cell_pc_main_tab_entrance:6|page_pc_main_tab][我喜欢的音乐:spm::|:::]");
            refs.add("[F:26][s][5][_ai]"); refs.add("[F:26][s][0][_ai]");
        } else for (int n : new int[]{87, 81, 75, 69, 63}) refs.add("[F:26][s][" + n + "][_ai]");
        data.add("_multirefers", refs); data.addProperty("vipType", vip == null ? "" : vip);
        data.addProperty("fee", start ? 1 : 8); data.addProperty("file", 4); data.addProperty("rightSource", 0);
        data.addProperty("sourceId", source); data.addProperty("sourcetype", "track");
        data.addProperty("libra_abt", ""); data.addProperty("channel", "netease"); data.addProperty("curStartChannel", "");
        if (!start) {
            long actual = Math.max(0, heard);
            // Do not invent full-song listening from the playback position or duration.
            data.addProperty("time", actual); data.addProperty("realtime", actual);
            data.addProperty("lyriceffect", "default"); data.addProperty("displayMode", "classic");
            data.addProperty("end", "interrupt");
        }
        return data;
    }

    void upload(JsonObject event, boolean start) throws IOException {
        String record = (System.currentTimeMillis() / 1000) + "\u0001" + (start ? "_plv" : "_pld") + "\u0001" + event;
        byte[] payload = encrypt(metadata().toString(), record);
        String boundary = UUID.randomUUID().toString().replace("-", "");
        String filename = "op_" + (10000 + RANDOM.nextInt(90000)) + "_0_" + (1L + (RANDOM.nextInt() & 0xffffffffL));
        byte[] body = multipart(payload, boundary, filename);
        HttpURLConnection connection = (HttpURLConnection) new URL(UPLOAD).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Referer", "https://music.163.com/di");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.35");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.8");
            connection.setRequestProperty("Cookie", cookieHeader());
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); }
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("NCBL HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                byte[] buf = new byte[2048]; int count;
                while ((count = input.read(buf)) != -1) {
                    if (response.size() + count > 65536) throw new IOException("NCBL response too large");
                    response.write(buf, 0, count);
                }
                validate(JsonParser.parseString(new String(response.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject(), filename);
            } catch (RuntimeException invalid) { throw new IOException("NCBL invalid response"); }
        } finally { connection.disconnect(); }
    }

    static void validate(JsonObject response, String filename) throws IOException {
        if (response != null && NeteaseListening.number(response, "code") == 200
                && response.has("data") && response.get("data").isJsonObject()) {
            JsonObject data = response.getAsJsonObject("data");
            if (data.has("successfiles") && data.get("successfiles").isJsonArray()) {
                for (com.google.gson.JsonElement file : data.getAsJsonArray("successfiles"))
                    if (file.isJsonPrimitive() && filename.equals(file.getAsString())) return;
            }
        }
        throw new IOException("NCBL upload not acknowledged");
    }

    static byte[] multipart(byte[] payload, String boundary, String filename) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: multipart/form-data\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(payload); out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    static byte[] encrypt(String meta, String record) throws IOException {
        byte[] key = new byte[32], uuid = new byte[16]; RANDOM.nextBytes(key); RANDOM.nextBytes(uuid);
        if ((key[0] & 255) >= 0xa3) key[0] = (byte) 0xa2;
        uuid[6] = (byte) ((uuid[6] & 15) | 0x40); uuid[8] = (byte) ((uuid[8] & 63) | 0x80);
        return encrypt(meta, record, key, uuid, RANDOM.nextInt(65536));
    }

    static byte[] wrap(byte[] key) {
        byte[] raw = new BigInteger(1, key).modPow(BigInteger.valueOf(65537), MODULUS).toByteArray();
        byte[] out = new byte[32];
        int count = Math.min(32, raw.length); System.arraycopy(raw, raw.length - count, out, 32 - count, count);
        return out;
    }

    static byte[] encrypt(String meta, String record, byte[] key, byte[] uuid, int base) throws IOException {
        byte[] wrapped = wrap(key), nonce = Arrays.copyOf(uuid, 12);
        int counter = ByteBuffer.wrap(uuid).order(ByteOrder.LITTLE_ENDIAN).getInt(12) >>> 2;
        byte[] metaCipher = chacha(wrapped, counter, nonce, meta.getBytes(StandardCharsets.UTF_8));
        if (metaCipher.length > 65461) throw new IOException("NCBL metadata too large");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(compressed)) { zip.write(record.getBytes(StandardCharsets.UTF_8)); }
        byte[] body = compressed.toByteArray();
        int frames = (body.length + 32767) / 32768;
        ByteBuffer out = ByteBuffer.allocate(74 + metaCipher.length + body.length + 6 * frames).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[]{'N', 'C', 'B', 'L'}).putInt(3).putShort((short) (74 + metaCipher.length));
        out.put(uuid).put(wrapped).putInt(base).putInt(base + frames - 1).putInt(body.length + 6 * frames);
        out.putShort((short) 0x4343).putShort((short) metaCipher.length).put(metaCipher);
        for (int offset = 0, seq = base; offset < body.length; offset += 32768, seq++) {
            byte[] cipher = chacha(key, counter, nonce, Arrays.copyOfRange(body, offset, Math.min(body.length, offset + 32768)));
            out.putShort((short) cipher.length).putInt(seq).put(cipher);
        }
        return out.array();
    }

    // Pure Java IETF ChaCha20 works on Android versions without a ChaCha20 JCE provider.
    static byte[] chacha(byte[] key, int counter, byte[] nonce, byte[] input) {
        ByteBuffer k = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN), n = ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN);
        int[] state = {0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
                k.getInt(), k.getInt(), k.getInt(), k.getInt(), k.getInt(), k.getInt(), k.getInt(), k.getInt(),
                counter, n.getInt(), n.getInt(), n.getInt()};
        byte[] out = new byte[input.length];
        for (int offset = 0; offset < input.length; offset += 64) {
            int[] work = state.clone();
            for (int round = 0; round < 10; round++) {
                quarter(work, 0, 4, 8, 12); quarter(work, 1, 5, 9, 13); quarter(work, 2, 6, 10, 14); quarter(work, 3, 7, 11, 15);
                quarter(work, 0, 5, 10, 15); quarter(work, 1, 6, 11, 12); quarter(work, 2, 7, 8, 13); quarter(work, 3, 4, 9, 14);
            }
            ByteBuffer stream = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 16; i++) stream.putInt(work[i] + state[i]);
            for (int i = 0; i < Math.min(64, input.length - offset); i++) out[offset + i] = (byte) (input[offset + i] ^ stream.array()[i]);
            state[12]++;
        }
        return out;
    }

    private static void quarter(int[] s, int a, int b, int c, int d) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] ^ s[a], 16);
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] ^ s[c], 12);
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] ^ s[a], 8);
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] ^ s[c], 7);
    }
}
