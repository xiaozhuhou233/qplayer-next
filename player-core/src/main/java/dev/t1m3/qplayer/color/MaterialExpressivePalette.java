package dev.t1m3.qplayer.color;

import dev.t1m3.qplayer.color.material.dynamiccolor.DynamicScheme;
import dev.t1m3.qplayer.color.material.hct.Hct;
import dev.t1m3.qplayer.color.material.scheme.SchemeExpressive;

/** Google Material Color Utilities' Expressive scheme at standard contrast. */
public final class MaterialExpressivePalette {
    public static final int FALLBACK_SEED = 0xff6750a4;

    private MaterialExpressivePalette() {}

    public static DynamicScheme fromSeed(String hex, boolean dark) {
        return new SchemeExpressive(Hct.fromInt(parseSeed(hex)), dark, 0.0);
    }

    // Cover extraction publishes opaque #RRGGBB. Bad/missing metadata must not crash the UI.
    public static int parseSeed(String hex) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) return FALLBACK_SEED;
        return (int) (0xff000000L | Long.parseLong(hex.substring(1), 16));
    }
}
