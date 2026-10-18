package dev.t1m3.qplayer.android.settings;

import android.content.Context;
import android.content.SharedPreferences;

import dev.t1m3.qplayer.settings.SettingsStore;

/**
 * Android half of the settings framework: SharedPreferences under the same keys
 * the desktop writes into its JSON file. Everything else about settings — which
 * ones exist, their defaults, what they do, how they're rendered — lives in
 * {@code player-core}'s {@code dev.t1m3.qplayer.settings}.
 */
public final class PrefsSettingsStore implements SettingsStore {

    private final SharedPreferences prefs;

    public PrefsSettingsStore(Context ctx) {
        this.prefs = ctx.getSharedPreferences("qplayer.settings", Context.MODE_PRIVATE);
    }

    @Override
    public boolean getBool(String key, boolean def) {
        return prefs.getBoolean(key, def);
    }

    @Override
    public int getInt(String key, int def) {
        return prefs.getInt(key, def);
    }

    @Override
    public String getString(String key, String def) {
        return prefs.getString(key, def);
    }

    @Override
    public boolean has(String key) {
        return prefs.contains(key);
    }

    @Override
    public void putBool(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    @Override
    public void putInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    @Override
    public void putString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }
}
