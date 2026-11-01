package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;
import java.lang.reflect.Method;
import java.util.Collections;
import static org.junit.Assert.*;

public class AutoCueCacheIdentityTest {
    private String key(String source, String account) throws Exception {
        Method method = AutoCue.class.getDeclaredMethod("cacheKey", String.class,
                java.util.Map.class, double.class, boolean.class, boolean.class);
        method.setAccessible(true);
        return (String) method.invoke(null, source,
                Collections.singletonMap("Authorization", account), 240d, false, false);
    }

    @Test public void renewedSignatureReusesRemoteScan() throws Exception {
        assertEquals(key("https://cdn.example/song/12.mp3?token=old&expires=1", "a"),
                key("https://cdn.example/song/12.mp3?token=new&expires=2", "a"));
    }

    @Test public void contentParametersAndAccountsCannotShareAScan() throws Exception {
        assertNotEquals(key("https://api.example/song?id=12", "a"),
                key("https://api.example/song?id=13", "a"));
        assertNotEquals(key("https://api.example/song?id=12&quality=low", "a"),
                key("https://api.example/song?id=12&quality=high", "a"));
        assertNotEquals(key("https://api.example/song?id=12", "a"),
                key("https://api.example/song?id=12", "b"));
    }

    @Test public void localQuestionMarkAndUnknownQueryRetainIdentity() {
        assertEquals("song?take=1.wav", AutoCue.stableScanSource("song?take=1.wav"));
        assertEquals("https://api.example/song?version=2", AutoCue.stableScanSource("https://api.example/song?version=2"));
    }
}
