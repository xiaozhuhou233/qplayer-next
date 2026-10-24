package dev.t1m3.qplayer.color;

import dev.t1m3.qplayer.color.material.dynamiccolor.DynamicScheme;
import dev.t1m3.qplayer.color.material.hct.Hct;
import dev.t1m3.qplayer.color.material.scheme.*;

/** Official Material 3 styles. The original four preference indices remain compatible. */
public final class MaterialArtworkPalette {
    public static final int TONAL_SPOT = 0, VIBRANT = 1, EXPRESSIVE = 2,
            FRUIT_SALAD = 3, SPRITZ = 4, RAINBOW = 5;
    private static final String[] PRESETS = {
            "#6750a4", "#1b6ef3", "#008577", "#5d7e33",
            "#b85b16", "#bc4055", "#98458b", "#60727a"
    };

    private MaterialArtworkPalette() {}

    public static boolean isStaticStyle(int style) {
        return style == RAINBOW || style == FRUIT_SALAD;
    }

    public static int presetCount() { return PRESETS.length; }
    public static String presetHex(int preset) {
        return PRESETS[Math.max(0, Math.min(PRESETS.length - 1, preset))];
    }

    /** Static styles must ignore ALL artwork, including page/card covers and artist images. */
    public static String seedForStyle(String artworkSeed, int style, int preset) {
        return isStaticStyle(style) ? presetHex(preset) : artworkSeed;
    }

    public static DynamicScheme fromSeed(String hex, boolean dark) {
        return fromSeed(hex, dark, TONAL_SPOT, 0);
    }

    public static DynamicScheme fromSeed(String hex, boolean dark, int style, int preset) {
        Hct source = Hct.fromInt(MaterialExpressivePalette.parseSeed(seedForStyle(hex, style, preset)));
        switch (style) {
            case VIBRANT: return new SchemeVibrant(source, dark, 0.0);
            case EXPRESSIVE: return new SchemeExpressive(source, dark, 0.0);
            case FRUIT_SALAD: return new SchemeFruitSalad(source, dark, 0.0);
            // MCU calls Android's SPRITZ style Neutral: low-chroma primary/secondary/tertiary.
            case SPRITZ: return new SchemeNeutral(source, dark, 0.0);
            case RAINBOW: return new SchemeRainbow(source, dark, 0.0);
            default: return new SchemeTonalSpot(source, dark, 0.0);
        }
    }
}
