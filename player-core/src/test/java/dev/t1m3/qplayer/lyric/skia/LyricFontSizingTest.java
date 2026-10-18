package dev.t1m3.qplayer.lyric.skia;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LyricFontSizingTest {

    @Test
    public void everySecondarySizeScalesFromItsParent() {
        LyricFontSizing.Sizes defaultSizes = LyricFontSizing.fromMain(28f);
        LyricFontSizing.Sizes largeSizes = LyricFontSizing.fromMain(40f);

        assertEquals(28f, defaultSizes.main, 0f);
        assertEquals(14f, defaultSizes.mainSubline, 0f);
        assertEquals(19.6f, defaultSizes.background, 0.001f);
        assertEquals(9.8f, defaultSizes.backgroundSubline, 0.001f);

        assertEquals(40f / 28f,
                largeSizes.mainSubline / defaultSizes.mainSubline, 0.001f);
        assertEquals(40f / 28f,
                largeSizes.background / defaultSizes.background, 0.001f);
        assertEquals(40f / 28f,
                largeSizes.backgroundSubline / defaultSizes.backgroundSubline, 0.001f);
    }

    @Test
    public void backgroundSublineUsesBackgroundAsItsParent() {
        LyricFontSizing.Sizes sizes = LyricFontSizing.fromMain(32f);

        assertEquals(LyricFontSizing.SUBLINE_TO_PARENT_RATIO,
                sizes.mainSubline / sizes.main, 0f);
        assertEquals(LyricFontSizing.BACKGROUND_TO_MAIN_RATIO,
                sizes.background / sizes.main, 0f);
        assertEquals(LyricFontSizing.SUBLINE_TO_PARENT_RATIO,
                sizes.backgroundSubline / sizes.background, 0f);
    }
}
