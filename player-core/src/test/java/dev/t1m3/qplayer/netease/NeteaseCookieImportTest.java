package dev.t1m3.qplayer.netease;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class NeteaseCookieImportTest {

    @Test
    public void parsesCookieValueWithoutLosingEmbeddedEquals() {
        Map<String, String> parsed = NeteaseClient.parseCookieHeader(
                "MUSIC_U=token==; __csrf=csrf-value; os=pc");

        assertEquals("token==", parsed.get("MUSIC_U"));
        assertEquals("csrf-value", parsed.get("__csrf"));
        assertEquals("pc", parsed.get("os"));
    }

    @Test
    public void acceptsCookiePrefixAndCompleteRequestHeaders() {
        Map<String, String> prefixed = NeteaseClient.parseCookieHeader(
                "Cookie: MUSIC_U=one; __csrf=two");
        assertEquals("one", prefixed.get("MUSIC_U"));

        Map<String, String> request = NeteaseClient.parseCookieHeader(
                "Host: music.163.com\r\nCookie: MUSIC_U=three; __csrf=four\r\nAccept: */*");
        assertEquals("three", request.get("MUSIC_U"));
        assertEquals("four", request.get("__csrf"));
        assertFalse(request.containsKey("Host"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsHeaderInjectionWithoutCookieLine() {
        NeteaseClient.parseCookieHeader("MUSIC_U=secret\r\nX-Injected: true");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidCookieName() {
        NeteaseClient.parseCookieHeader("MUSIC_U=secret; bad name=value");
    }
}
