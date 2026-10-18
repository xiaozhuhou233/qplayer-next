package dev.t1m3.qplayer.settings;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class SettingsComposeFontsTest {
    @Test public void composeSettingsLoadAndPersistFontsWithoutNativeRenderer() {
        SettingsCore settings = new SettingsCore();
        settings.setNativeFontRendererEnabled(false);
        Map<String, Object> data = new HashMap<>();
        SettingsStore store = new SettingsStore() {
            public boolean getBool(String k, boolean d) { return data.containsKey(k) ? (Boolean) data.get(k) : d; }
            public int getInt(String k, int d) { return data.containsKey(k) ? (Integer) data.get(k) : d; }
            public String getString(String k, String d) { return data.containsKey(k) ? (String) data.get(k) : d; }
            public boolean has(String k) { return data.containsKey(k); }
            public void putBool(String k, boolean v) { data.put(k, v); }
            public void putInt(String k, int v) { data.put(k, v); }
            public void putString(String k, String v) { data.put(k, v); }
        };
        settings.load(store, SettingsCatalog.ANDROID);
        assertTrue(settings.availableFontFamilies.peek().isEmpty());
        assertFalse(settings.rows(SettingsCatalog.APPEARANCE).isEmpty());
        settings.setFontSelection("test family");
        assertEquals("test family", settings.fontSelection());
        assertEquals(1, settings.fontFamilyChanged.peek().intValue());
        try {
            settings.setNativeFontRendererEnabled(true);
            fail("Changing renderer after load must be rejected");
        } catch (IllegalStateException expected) { }
    }
}
