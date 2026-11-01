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

    @Test public void iosGlassBlurIsBoundedAndPersistsIndependentlyOfRefraction() {
        Store store = new Store();
        SettingsCore settings = load(store);
        assertEquals(4, settings.intOf(SettingsCatalog.IOS_GLASS_BLUR_KEY));
        settings.put("iosDesign", true);
        settings.put(SettingsCatalog.IOS_GLASS_BLUR_KEY, 12);
        settings.put("iosGlassRefraction", false);
        settings = load(store);
        assertEquals(12, settings.intOf(SettingsCatalog.IOS_GLASS_BLUR_KEY));
        assertFalse(settings.bool("iosGlassRefraction"));
        settings.put(SettingsCatalog.IOS_GLASS_BLUR_KEY, 99);
        assertEquals(24, load(store).intOf(SettingsCatalog.IOS_GLASS_BLUR_KEY));
        settings.put(SettingsCatalog.IOS_GLASS_BLUR_KEY, -5);
        assertEquals(0, load(store).intOf(SettingsCatalog.IOS_GLASS_BLUR_KEY));
        SettingSpec spec = settings.rows(SettingsCatalog.APPEARANCE).stream()
                .filter(row -> row.key.equals(SettingsCatalog.IOS_GLASS_BLUR_KEY))
                .findFirst().orElseThrow(() -> new AssertionError("Missing glass blur setting"));
        assertEquals(0, spec.min);
        assertEquals(24, spec.max);
        assertEquals("iosDesign", spec.dependsOn);
    }
}
