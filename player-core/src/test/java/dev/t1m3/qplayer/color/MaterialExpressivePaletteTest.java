package dev.t1m3.qplayer.color;

import dev.t1m3.qplayer.color.material.dynamiccolor.DynamicScheme;
import dev.t1m3.qplayer.color.material.hct.Hct;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaterialExpressivePaletteTest {
    @Test public void matchesGoogleExpressiveStandardContrastReferenceValues() {
        // Official SchemeExpressiveTests.swift at the pinned upstream revision.
        DynamicScheme light = MaterialExpressivePalette.fromSeed("#0000ff", false);
        assertEquals(0xff35855f, light.getPrimaryPaletteKeyColor());
        assertEquals(0xff8c6d8c, light.getSecondaryPaletteKeyColor());
        assertEquals(0xff806ea1, light.getTertiaryPaletteKeyColor());
        assertEquals(0xff146c48, light.getPrimary());
        assertEquals(0xffa2f4c6, light.getPrimaryContainer());
        assertEquals(0xff005234, light.getOnPrimaryContainer());
        assertEquals(0xfffdf7ff, light.getSurface());
        DynamicScheme dark = MaterialExpressivePalette.fromSeed("#0000ff", true);
        assertEquals(0xff87d7ab, dark.getPrimary());
        assertEquals(0xff005234, dark.getPrimaryContainer());
        assertEquals(0xffa2f4c6, dark.getOnPrimaryContainer());
        assertEquals(0xff14121a, dark.getSurface());
    }

    @Test public void handlesMissingOrMalformedCoverSeed() {
        assertEquals(MaterialExpressivePalette.FALLBACK_SEED, MaterialExpressivePalette.parseSeed(null));
        for (String invalid : new String[] {"", "oops", "#123", "#12345678", "#gg88ee"}) {
            assertEquals(invalid, MaterialExpressivePalette.FALLBACK_SEED, MaterialExpressivePalette.parseSeed(invalid));
        }
        assertEquals(0xff00a4c8, MaterialExpressivePalette.parseSeed("#00A4c8"));
    }

    @Test public void coverChangesUpdateAccentsAndSurfaceWithoutChangingBrightnessMode() {
        DynamicScheme red = MaterialExpressivePalette.fromSeed("#e53935", false);
        DynamicScheme blue = MaterialExpressivePalette.fromSeed("#1565c0", false);
        assertNotEquals(red.getPrimary(), blue.getPrimary());
        assertNotEquals(red.getSecondaryContainer(), blue.getSecondaryContainer());
        assertNotEquals(red.getSurface(), blue.getSurface());
        for (String seed : new String[] {"#e53935", "#1565c0", "#777777", "#000000", "#ffffff"}) {
            assertTrue(Hct.fromInt(MaterialExpressivePalette.fromSeed(seed, false).getSurface()).getTone() > 95);
            assertTrue(Hct.fromInt(MaterialExpressivePalette.fromSeed(seed, true).getSurface()).getTone() < 10);
        }
    }

    @Test public void materialTextPairsRemainReadableAcrossCoverHuesAndBrightness() {
        // Dense hue samples plus neutral, very dark and very bright cover seeds.
        int[] extras = {0xff000000, 0xffffffff, 0xff777777, 0xff010102, 0xfffefdfb};
        for (boolean dark : new boolean[] {false, true}) {
            for (int i = 0; i < 77; i++) {
                int argb = i < 72 ? Hct.from(i * 5.0, 80, 50).toInt() : extras[i - 72];
                String seed = String.format("#%06x", argb & 0xffffff);
                DynamicScheme s = MaterialExpressivePalette.fromSeed(seed, dark);
                int[][] pairs = {
                    {s.getOnSurface(), s.getSurface()},
                    {s.getOnSurfaceVariant(), s.getSurfaceContainerHighest()},
                    {s.getOnPrimary(), s.getPrimary()},
                    {s.getOnPrimaryContainer(), s.getPrimaryContainer()},
                    {s.getOnSecondary(), s.getSecondary()},
                    {s.getOnSecondaryContainer(), s.getSecondaryContainer()},
                    {s.getOnTertiary(), s.getTertiary()},
                    {s.getOnTertiaryContainer(), s.getTertiaryContainer()},
                    {s.getOnError(), s.getError()},
                    {s.getOnErrorContainer(), s.getErrorContainer()},
                    {s.getOnPrimaryFixed(), s.getPrimaryFixed()},
                    {s.getOnSecondaryFixed(), s.getSecondaryFixed()},
                    {s.getOnTertiaryFixed(), s.getTertiaryFixed()},
                };
                for (int[] pair : pairs) {
                    assertTrue(seed + " dark=" + dark + " contrast=" + contrast(pair[0], pair[1]), contrast(pair[0], pair[1]) >= 4.45);
                    assertEquals(0xff, pair[0] >>> 24);
                    assertEquals(0xff, pair[1] >>> 24);
                }
            }
        }
    }

    private static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    // Independent WCAG relative luminance; does not use the palette's own contrast solver.
    private static double luminance(int argb) {
        return .2126 * linear((argb >> 16) & 255) + .7152 * linear((argb >> 8) & 255) + .0722 * linear(argb & 255);
    }

    private static double linear(int component) {
        double value = component / 255.0;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }
}
