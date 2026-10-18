package dev.t1m3.qplayer.settings;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsEqualizerTest {
    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> data = new HashMap<>();
        public boolean getBool(String k, boolean d) { return data.containsKey(k) ? (Boolean) data.get(k) : d; }
        public int getInt(String k, int d) { return data.containsKey(k) ? (Integer) data.get(k) : d; }
        public String getString(String k, String d) { return data.containsKey(k) ? (String) data.get(k) : d; }
        public boolean has(String k) { return data.containsKey(k); }
        public void putBool(String k, boolean v) { data.put(k, v); }
        public void putInt(String k, int v) { data.put(k, v); }
        public void putString(String k, String v) { data.put(k, v); }
    }

    private SettingsCore load(MemoryStore store) {
        SettingsCore core = new SettingsCore();
        core.setNativeFontRendererEnabled(false);
        core.load(store, SettingsCatalog.ANDROID);
        return core;
    }

    @Test public void sliderAndPresetValuesReachBackendReadsAndSurviveRestart() {
        MemoryStore store = new MemoryStore();
        SettingsCore core = load(store);
        assertTrue("Frequency levels must be registered", core.has("eqBands"));
        assertTrue("Bass strength must be registered", core.has("eqBass"));
        core.put("eqEnabled", true);
        core.put("eqBands", "900,600,150,-200,-300");
        core.put("eqBass", "700");
        assertEquals("900,600,150,-200,-300", core.str("eqBands"));
        assertEquals("700", core.str("eqBass"));
        SettingsCore restored = load(store);
        assertTrue(restored.bool("eqEnabled"));
        assertEquals(core.str("eqBands"), restored.str("eqBands"));
        assertEquals("700", restored.str("eqBass"));
        restored.put("eqEnabled", false);
        assertEquals("900,600,150,-200,-300", restored.str("eqBands"));
        restored.put("eqBands", "");
        restored.put("eqBass", "0");
        SettingsCore reset = load(store);
        assertEquals("", reset.str("eqBands"));
        assertEquals("0", reset.str("eqBass"));
    }

    @Test public void savedBandStorageDoesNotCreateExtraSettingsRows() {
        SettingsCore core = load(new MemoryStore());
        assertEquals("", core.str("eqBands"));
        assertEquals("0", core.str("eqBass"));
        for (SettingSpec row : core.rows(SettingsCatalog.SOUND)) {
            assertNotEquals("eqBands", row.key);
            assertNotEquals("eqBass", row.key);
        }
    }
}
