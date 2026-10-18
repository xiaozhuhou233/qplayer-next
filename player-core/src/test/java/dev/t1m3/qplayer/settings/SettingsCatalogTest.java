package dev.t1m3.qplayer.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsCatalogTest {
    @Test public void mvBackgroundIsOptInAndAndroidOnly() {
        SettingSpec spec = setting(SettingsCatalog.MV_BACKGROUND_KEY);
        assertEquals(Boolean.FALSE, spec.def);
        assertEquals(SettingSpec.SWITCH, spec.type);
        assertEquals(SettingsCatalog.APPEARANCE, spec.category);
        assertTrue(spec.appliesTo(SettingsCatalog.ANDROID));
        assertFalse(spec.appliesTo(SettingsCatalog.DESKTOP));
    }

    @Test public void autoMixReplacesLegacyControlsAndPreservesMasterPreference() {
        assertEquals("AutoMix", setting(SettingsCatalog.SMART_TRANSITION_KEY).title);
        assertEquals(2, setting("autoMixMode").def);
        assertEquals(6, setting("autoMixWindow").def);
        assertEquals(Boolean.TRUE, setting("autoMixForceWindow").def);
        assertEquals(0, setting("autoMixStyle").def);
        assertEquals(Boolean.TRUE, setting("autoMixAutoCue").def);
        assertEquals(Boolean.TRUE, setting("autoMixPitchEnabled").def);
        assertEquals(SettingSpec.SWITCH, setting("autoMixPitchEnabled").type);
        assertEquals(SettingsCatalog.SMART_TRANSITION_KEY, setting("autoMixPitchEnabled").dependsOn);
        assertEquals("有可能吃掉下一首歌一分钟", setting("autoMixAutoCue").desc);
        assertEquals(Boolean.TRUE, setting("precacheNextTrack").def);
        assertFalse(SettingsCatalog.specs().stream().anyMatch(s ->
                SettingsCatalog.TRANSITION_KIND_KEY.equals(s.key)
                || SettingsCatalog.TRANSITION_BLEND_KEY.equals(s.key)
                || SettingsCatalog.ACE_STEP.equals(s.category)));
        assertEquals(60000, setting("aiTimeoutMs").def);
    }

    @Test
    public void glassRefractionDefaultsOnAndIsAndroidOnly() {
        SettingSpec spec = setting("iosGlassRefraction");
        assertEquals(Boolean.TRUE, spec.def);
        assertEquals(SettingSpec.SWITCH, spec.type);
        assertEquals(SettingsCatalog.APPEARANCE, spec.category);
        assertTrue(spec.appliesTo(SettingsCatalog.ANDROID));
        assertFalse(spec.appliesTo(SettingsCatalog.DESKTOP));
        assertEquals(1L, SettingsCatalog.specs().stream()
                .filter(candidate -> "iosGlassRefraction".equals(candidate.key)).count());
    }

    @Test
    public void iosDesignIsAndroidOnlyAndOptIn() {
        SettingSpec spec = setting("iosDesign");
        assertEquals(Boolean.FALSE, spec.def);
        assertEquals(SettingSpec.SWITCH, spec.type);
        assertEquals("ios design", spec.title);
        assertTrue(spec.appliesTo(SettingsCatalog.ANDROID));
        assertFalse(spec.appliesTo(SettingsCatalog.DESKTOP));
    }

    @Test
    public void lowSpecModeIsAndroidOnlyAndOptIn() {
        SettingSpec spec = setting(SettingsCatalog.LOW_SPEC_MODE_KEY);
        assertEquals(Boolean.FALSE, spec.def);
        assertEquals(SettingSpec.SWITCH, spec.type);
        assertEquals(SettingsCatalog.APPEARANCE, spec.category);
        assertTrue(spec.appliesTo(SettingsCatalog.ANDROID));
        assertFalse(spec.appliesTo(SettingsCatalog.DESKTOP));
    }

    @Test
    public void systemTitleBarIsDesktopOnlyAndDefaultsOff() {
        SettingSpec spec = setting("windowDecorated");

        assertEquals(Boolean.FALSE, spec.def);
        assertTrue(spec.appliesTo(SettingsCatalog.DESKTOP));
        assertFalse(spec.appliesTo(SettingsCatalog.ANDROID));
    }

    @Test
    public void lyricSizingUsesDottedFixedStepSliders() {
        SettingSpec fontSize = setting("lyricFontSize");
        SettingSpec lineSpacing = setting("lyricLineSpacing");

        assertEquals(SettingSpec.SLIDER, fontSize.type);
        assertTrue(fontSize.dots);
        assertEquals(1, fontSize.step);

        assertEquals(SettingSpec.SLIDER, lineSpacing.type);
        assertTrue(lineSpacing.dots);
        assertEquals(5, lineSpacing.step);
    }

    @Test
    public void maximumCacheUsesPlainSlider() {
        SettingSpec cache = setting("maxCacheSizeMB");

        assertEquals(SettingSpec.SLIDER, cache.type);
        assertFalse(cache.dots);
        assertEquals(50, cache.min);
        assertEquals(1024, cache.max);
    }

    @Test
    public void pageTransitionDefaultsToZoomAndOffersAccessibleFallback() {
        SettingSpec transition = setting(SettingsCatalog.PAGE_TRANSITION_KEY);

        assertEquals(SettingSpec.DROPDOWN, transition.type);
        assertEquals(SettingsCatalog.PAGE_TRANSITION_ZOOM, transition.def);
        assertEquals("Zoom In / Out", transition.options.get(0));
        assertTrue(transition.options.contains("无动画"));
        assertTrue(transition.appliesTo(SettingsCatalog.DESKTOP));
        assertTrue(transition.appliesTo(SettingsCatalog.ANDROID));
    }

    private static SettingSpec setting(String key) {
        return SettingsCatalog.specs().stream()
                .filter(candidate -> key.equals(candidate.key))
                .findFirst()
                .orElseThrow(() -> new AssertionError(key + " setting missing"));
    }
}
