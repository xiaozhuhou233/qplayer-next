package dev.t1m3.qplayer.settings;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsDesignModeTest {
    private static final class Store implements SettingsStore {
        private final Map<String, Object> values = new HashMap<>();
        public boolean getBool(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public boolean has(String key) { return values.containsKey(key); }
        public void putBool(String key, boolean value) { values.put(key, value); }
        public void putInt(String key, int value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
    }

    private SettingsCore load(Store store) {
        SettingsCore settings = new SettingsCore();
        settings.setNativeFontRendererEnabled(false);
        settings.load(store, SettingsCatalog.ANDROID);
        return settings;
    }

    @Test public void addingMd3ePreservesExistingPreferenceUntilSelected() {
        Store store = new Store();
        SettingsCore settings = load(store);
        assertTrue(settings.bool("claudeDesign"));
        assertFalse(settings.bool("sonicDesign"));
        settings.put("iosDesign", true);
        SettingsCore restored = load(store);
        assertTrue(restored.bool("iosDesign"));
        assertFalse(restored.bool("sonicDesign"));
    }

    @Test public void fourModesRemainExclusiveAndSurviveRestart() {
        Store store = new Store();
        SettingsCore settings = load(store);
        for (String selected : new String[] {"sonicDesign", "iosDesign", "claudeDesign", "sonicDesign"}) {
            settings.put(selected, true);
            settings = load(store);
            for (String key : new String[] {"sonicDesign", "iosDesign", "claudeDesign"}) {
                assertEquals(key + " after selecting " + selected, key.equals(selected), settings.bool(key));
            }
        }
        settings.put("sonicDesign", false);
        settings = load(store);
        assertFalse(settings.bool("sonicDesign"));
        assertFalse(settings.bool("iosDesign"));
        assertFalse(settings.bool("claudeDesign"));
    }
}
