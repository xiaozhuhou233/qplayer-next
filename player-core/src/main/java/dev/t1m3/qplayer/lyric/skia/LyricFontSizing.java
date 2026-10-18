package dev.t1m3.qplayer.lyric.skia;

/**
 * Font-size relationships shared by lyric renderers.
 *
 * <p>Every secondary size is derived from its immediate parent: background
 * vocals follow the configured main size, and each translation/romaji line
 * follows the main row it belongs to. This keeps the hierarchy intact when the
 * user changes the lyric size instead of leaving secondary text at a fixed px.
 */
final class LyricFontSizing {

    static final float BACKGROUND_TO_MAIN_RATIO = 0.70f;
    static final float SUBLINE_TO_PARENT_RATIO = 0.50f;

    private LyricFontSizing() {}

    static Sizes fromMain(float main) {
        float normalizedMain = Math.max(1f, main);
        float background = normalizedMain * BACKGROUND_TO_MAIN_RATIO;
        return new Sizes(normalizedMain,
                normalizedMain * SUBLINE_TO_PARENT_RATIO,
                background,
                background * SUBLINE_TO_PARENT_RATIO);
    }

    static final class Sizes {
        final float main;
        final float mainSubline;
        final float background;
        final float backgroundSubline;

        private Sizes(float main, float mainSubline,
                      float background, float backgroundSubline) {
            this.main = main;
            this.mainSubline = mainSubline;
            this.background = background;
            this.backgroundSubline = backgroundSubline;
        }
    }
}
