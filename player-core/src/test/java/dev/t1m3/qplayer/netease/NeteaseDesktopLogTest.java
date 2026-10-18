package dev.t1m3.qplayer.netease;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class NeteaseDesktopLogTest {
    @Test public void chachaMatchesRfc8439BlockVector() {
        byte[] key = new byte[32]; for (int i = 0; i < 32; i++) key[i] = (byte) i;
        byte[] nonce = hex("000000090000004a00000000");
        assertArrayEquals(hex("10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e"
                + "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e"),
                NeteaseDesktopLog.chacha(key, 1, nonce, new byte[64]));
    }

    @Test public void multiFrameEnvelopeRestoresMetadataAndCompressedRecord() throws Exception {
        byte[] key = new byte[32], uuid = new byte[16], noise = new byte[100000];
        new Random(52).nextBytes(key); key[0] = 1;
        new Random(53).nextBytes(uuid); new Random(54).nextBytes(noise);
        String meta = "{\"MUSIC_U\":\"test+token==\",\"os\":\"pc\"}";
        String record = "123456\u0001_pld\u0001" + Base64.getEncoder().encodeToString(noise);
        byte[] encoded = NeteaseDesktopLog.encrypt(meta, record, key, uuid, 42);
        ByteBuffer h = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("NCBL", new String(encoded, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(3, h.getInt(4));
        int bodyOffset = h.getShort(8) & 65535;
        assertArrayEquals(uuid, Arrays.copyOfRange(encoded, 10, 26));
        assertArrayEquals(NeteaseDesktopLog.wrap(key), Arrays.copyOfRange(encoded, 26, 58));
        assertEquals(encoded.length - bodyOffset, h.getInt(66));
        assertEquals(0x4343, h.getShort(70) & 65535);
        int metaSize = h.getShort(72) & 65535;
        assertEquals(bodyOffset, 74 + metaSize);
        byte[] nonce = Arrays.copyOf(uuid, 12);
        int counter = ByteBuffer.wrap(uuid).order(ByteOrder.LITTLE_ENDIAN).getInt(12) >>> 2;
        assertEquals(meta, new String(NeteaseDesktopLog.chacha(NeteaseDesktopLog.wrap(key), counter, nonce,
                Arrays.copyOfRange(encoded, 74, bodyOffset)), StandardCharsets.UTF_8));
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        int pos = bodyOffset, seq = 42;
        while (pos < encoded.length) {
            int count = h.getShort(pos) & 65535;
            assertTrue(count <= 32768); assertEquals(seq++, h.getInt(pos + 2));
            compressed.write(NeteaseDesktopLog.chacha(key, counter, nonce, Arrays.copyOfRange(encoded, pos + 6, pos + 6 + count)));
            pos += 6 + count;
        }
        assertTrue(seq > 43); assertEquals(42, h.getInt(58)); assertEquals(seq - 1, h.getInt(62));
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            byte[] buf = new byte[1024]; int count; while ((count = gzip.read(buf)) != -1) raw.write(buf, 0, count);
        }
        assertEquals(record, new String(raw.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test public void rawAuthenticationIsPreservedAndIdentityDoesNotMutateCookieJar() {
        Map<String, String> cookies = new HashMap<>();
        cookies.put("MUSIC_U", "opaque+token%2B=="); cookies.put("os", "iPhone OS"); cookies.put("appver", "9.1.65");
        cookies.put("deviceId", "stable-device");
        NeteaseDesktopLog log = new NeteaseDesktopLog(cookies);
        assertEquals("opaque+token%2B==", log.metadata().get("MUSIC_U").getAsString());
        assertEquals("pc", log.metadata().get("os").getAsString());
        assertEquals("stable-device", log.metadata().get("deviceId").getAsString());
        assertTrue(log.cookieHeader().contains("MUSIC_U=opaque+token%2B==;"));
        assertEquals("iPhone OS", cookies.get("os")); assertEquals("9.1.65", cookies.get("appver"));
    }

    @Test public void uploadRequiresAcknowledgementForThisExactFile() throws Exception {
        NeteaseDesktopLog.validate(JsonParser.parseString("{code:200,data:{successfiles:['op_test']}}").getAsJsonObject(), "op_test");
        for (String json : new String[]{"{code:200}", "{code:200,data:{successfiles:['different']}}", "{code:403}", "{}"}) {
            try { NeteaseDesktopLog.validate(JsonParser.parseString(json).getAsJsonObject(), "op_test"); fail(json); }
            catch (IOException expected) { }
        }
    }

    @Test public void payloadKeepsActualHeardTimeSeparateFromSongDuration() {
        JsonObject start = NeteaseDesktopLog.event(123, 456, 200, 0, true, "11");
        assertFalse(start.has("time")); assertEquals(200, start.get("resource_time").getAsLong());
        JsonObject end = NeteaseDesktopLog.event(123, 456, 200, 37, false, "11");
        assertEquals(37, end.get("time").getAsLong()); assertEquals(37, end.get("realtime").getAsLong());
        assertEquals(200, end.get("resource_time").getAsLong()); assertEquals(456, end.get("sourceId").getAsLong());
        assertEquals(123, NeteaseDesktopLog.event(123, 0, 200, 37, false, "").get("sourceId").getAsLong());
    }

    @Test public void multipartContainsBinaryDataAndTerminatingBoundary() throws Exception {
        byte[] payload = new byte[]{0, 1, (byte) 255};
        byte[] body = NeteaseDesktopLog.multipart(payload, "boundary", "op_file");
        String view = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(view.startsWith("--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"op_file\""));
        assertTrue(view.endsWith("\r\n--boundary--\r\n"));
        int offset = view.indexOf("\r\n\r\n") + 4;
        assertArrayEquals(payload, Arrays.copyOfRange(body, offset, offset + payload.length));
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
