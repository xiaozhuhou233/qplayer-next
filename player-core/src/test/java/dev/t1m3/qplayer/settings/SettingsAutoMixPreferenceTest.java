package dev.t1m3.qplayer.settings;

import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsAutoMixPreferenceTest {
    @Test public void manualTwentySecondWindowIsPreservedForEveryPreference() {
        for (int mode = 0; mode <= 2; mode++) {
            assertEquals(20, SettingsCore.preferredAutoMixWindow(20, mode, true));
        }
    }

    @Test public void onlyAutomaticWindowUsesThePreferenceWithinBounds() {
        assertEquals(4, SettingsCore.preferredAutoMixWindow(6, 0, false));
        assertEquals(6, SettingsCore.preferredAutoMixWindow(6, 1, false));
        assertEquals(9, SettingsCore.preferredAutoMixWindow(6, 2, false));
        assertEquals(2, SettingsCore.preferredAutoMixWindow(2, 0, false));
        assertEquals(30, SettingsCore.preferredAutoMixWindow(30, 2, false));
    }
}
