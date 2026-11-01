package dev.t1m3.qplayer.settings;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsPlayerArtworkTest {
    private static class Store implements SettingsStore {
        final Map<String, Object> data = new HashMap<>();
        public boolean getBool(String key, boolean fallback) { return (Boolean) data.getOrDefault(key, fallback); }
        public int getInt(String key, int fallback) { return (Integer) data.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) data.getOrDefault(key, fallback); }
        public boolean has(String key) { return data.containsKey(key); }
        public void putBool(String key, boolean value) { data.put(key, value); }
        public void putInt(String key, int value) { data.put(key, value); }
        public void putString(String key, String value) { data.put(key, value); }
    }
    private SettingsCore load(Store store) {
        SettingsCore result = new SettingsCore(); result.setNativeFontRendererEnabled(false);
        result.load(store, SettingsCatalog.ANDROID); return result;
    }
    @Test public void migratesBothOldMvStatesOnceAndPreservesNewSelection() {
        for (boolean old : new boolean[] {false, true}) {
            Store store = new Store(); store.putBool(SettingsCatalog.MV_BACKGROUND_KEY, old);
            SettingsCore settings = load(store);
            assertEquals(old ? SettingsCatalog.ARTWORK_MV : SettingsCatalog.ARTWORK_STATIC,
                    settings.intOf(SettingsCatalog.PLAYER_ARTWORK_KEY));
            settings.put(SettingsCatalog.PLAYER_ARTWORK_KEY, SettingsCatalog.ARTWORK_DYNAMIC);
            assertFalse(settings.bool(SettingsCatalog.MV_BACKGROUND_KEY));
            settings = load(store);
            assertEquals(SettingsCatalog.ARTWORK_DYNAMIC, settings.intOf(SettingsCatalog.PLAYER_ARTWORK_KEY));
            settings.put(SettingsCatalog.PLAYER_ARTWORK_KEY, SettingsCatalog.ARTWORK_MV);
            assertTrue(settings.bool(SettingsCatalog.MV_BACKGROUND_KEY));
            settings.put(SettingsCatalog.PLAYER_ARTWORK_KEY, SettingsCatalog.ARTWORK_STATIC);
            assertFalse(settings.bool(SettingsCatalog.MV_BACKGROUND_KEY));
        }
    }
    @Test public void freshInstallUsesStaticArtworkAndOnlyWifiAndShowsOneSelector() {
        SettingsCore settings = load(new Store());
        assertEquals(SettingsCatalog.ARTWORK_STATIC, settings.intOf(SettingsCatalog.PLAYER_ARTWORK_KEY));
        assertEquals(0, settings.intOf(SettingsCatalog.DYNAMIC_COVER_NETWORK_KEY));
        List<SettingSpec> rows = settings.rows(SettingsCatalog.APPEARANCE);
        assertFalse(rows.stream().anyMatch(row -> row.key.equals(SettingsCatalog.MV_BACKGROUND_KEY)));
        assertEquals(3, rows.stream().filter(row -> row.key.equals(SettingsCatalog.PLAYER_ARTWORK_KEY))
                .findFirst().get().options.size());
    }
}
