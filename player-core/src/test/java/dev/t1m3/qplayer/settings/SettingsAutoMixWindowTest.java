package dev.t1m3.qplayer.settings;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SettingsAutoMixWindowTest {
    @Test public void durationEditPinsAndPersistsWindowBeforeNotifyingConsumers() {
        MemoryStore store = new MemoryStore();
        store.putBool("autoMixForceWindow", false);
        SettingsCore settings = load(store);
        assertEquals(false, settings.value("autoMixForceWindow"));
        AtomicInteger notifications = new AtomicInteger();
        settings.onChange("autoMixWindow", value -> {
            assertEquals(12, value);
            assertEquals(true, settings.value("autoMixForceWindow"));
            assertEquals(true, store.data.get("autoMixForceWindow"));
            notifications.incrementAndGet();
        });
        settings.onChange("autoMixForceWindow", value -> {
            assertEquals(true, value);
            assertEquals(12, settings.value("autoMixWindow"));
            notifications.incrementAndGet();
        });
        settings.setValue("autoMixWindow", 12L);
        assertEquals(2, notifications.get());
        SettingsCore restored = load(store);
        assertEquals(12, restored.value("autoMixWindow"));
        assertEquals(true, restored.value("autoMixForceWindow"));
    }

    @Test public void committingSameSecondsAlsoSelectsFixedWindow() {
        MemoryStore store = new MemoryStore();
        store.putBool("autoMixForceWindow", false);
        SettingsCore settings = load(store);
        settings.setValue("autoMixWindow", 6);
        assertEquals(true, settings.value("autoMixForceWindow"));
        assertEquals(true, load(store).value("autoMixForceWindow"));
    }

    @Test public void automaticWindowRemainsAnExplicitPersistedChoice() {
        MemoryStore store = new MemoryStore();
        SettingsCore settings = load(store);
        assertEquals(true, settings.value("autoMixForceWindow"));
        settings.setValue("autoMixForceWindow", false);
        settings.setValue("autoMixStyle", 4);
        settings.setValue("autoMixScale", 150);
        assertEquals(false, load(store).value("autoMixForceWindow"));
        settings.setValue("autoMixWindow", "invalid");
        assertEquals(false, settings.value("autoMixForceWindow"));
    }

    private static SettingsCore load(MemoryStore store) {
        SettingsCore settings = new SettingsCore();
        settings.setNativeFontRendererEnabled(false);
        settings.load(store, SettingsCatalog.ANDROID);
        return settings;
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String,Object> data = new HashMap<>();
        public boolean getBool(String key, boolean def) { return (Boolean) data.getOrDefault(key, def); }
        public int getInt(String key, int def) { return (Integer) data.getOrDefault(key, def); }
        public String getString(String key, String def) { return (String) data.getOrDefault(key, def); }
        public boolean has(String key) { return data.containsKey(key); }
        public void putBool(String key, boolean value) { data.put(key, value); }
        public void putInt(String key, int value) { data.put(key, value); }
        public void putString(String key, String value) { data.put(key, value); }
    }
}
