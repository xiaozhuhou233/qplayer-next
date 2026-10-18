package dev.t1m3.qplayer.settings;

/**
 * Where setting values are persisted. The only part of settings that has to
 * differ per platform: desktop writes a JSON file, Android writes
 * SharedPreferences. Everything else — the catalog, the value cache, the side
 * effects, the UI — is shared.
 */
public interface SettingsStore {

    boolean getBool(String key, boolean def);

    int getInt(String key, int def);

    String getString(String key, String def);

    /** Whether the key has ever been written; drives one-time migrations. */
    boolean has(String key);

    void putBool(String key, boolean value);

    void putInt(String key, int value);

    void putString(String key, String value);
}
