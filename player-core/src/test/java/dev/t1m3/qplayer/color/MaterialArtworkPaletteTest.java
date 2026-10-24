package dev.t1m3.qplayer.color;

import dev.t1m3.qplayer.color.material.dynamiccolor.DynamicScheme;
import dev.t1m3.qplayer.color.material.hct.Hct;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaterialArtworkPaletteTest {
    @Test public void primaryTracksArtworkHueUsingOfficialTonalSpotPalettes() {
        for (boolean dark : new boolean[] {false, true}) {
            for (String seed : new String[] {"#e53935", "#1565c0", "#008577", "#ffb300"}) {
                DynamicScheme s = MaterialArtworkPalette.fromSeed(seed, dark);
                double hue = Hct.fromInt(MaterialExpressivePalette.parseSeed(seed)).getHue();
                assertEquals(hue, s.primaryPalette.getHue(), .001);
                assertEquals(hue, s.secondaryPalette.getHue(), .001);
                assertEquals((hue + 60) % 360, s.tertiaryPalette.getHue(), .001);
                assertEquals(36.0, s.primaryPalette.getChroma(), .001);
                assertEquals(16.0, s.secondaryPalette.getChroma(), .001);
                assertEquals(24.0, s.tertiaryPalette.getChroma(), .001);
            }
        }
    }

    @Test public void surfaceRolesHaveOrderedElevationAndReadableText() {
        for (boolean dark : new boolean[] {false, true}) {
            for (String seed : new String[] {"#e53935", "#1565c0", "#008577", "#777777"}) {
                DynamicScheme s = MaterialArtworkPalette.fromSeed(seed, dark);
                int[] surfaces = {s.getSurfaceContainerLowest(), s.getSurfaceContainerLow(),
                    s.getSurfaceContainer(), s.getSurfaceContainerHigh(), s.getSurfaceContainerHighest()};
                double previous = dark ? 0 : 101;
                for (int surface : surfaces) {
                    double tone = Hct.fromInt(surface).getTone();
                    assertTrue(dark ? tone >= previous : tone <= previous);
                    assertTrue(contrast(s.getOnSurface(), surface) >= 4.5);
                    assertTrue(contrast(s.getOnSurfaceVariant(), surface) >= 4.5);
                    previous = tone;
                }
                assertTrue(contrast(s.getInverseOnSurface(), s.getInverseSurface()) >= 4.5);
            }
        }
    }

    @Test public void staticStylesIgnoreArtworkAndUseSelectedPreset() {
        for (int style : new int[] {MaterialArtworkPalette.RAINBOW, MaterialArtworkPalette.FRUIT_SALAD}) {
            assertTrue(MaterialArtworkPalette.isStaticStyle(style));
            for (boolean dark : new boolean[] {false, true}) {
                for (int preset = 0; preset < MaterialArtworkPalette.presetCount(); preset++) {
                    DynamicScheme a = MaterialArtworkPalette.fromSeed("#ff0000", dark, style, preset);
                    DynamicScheme b = MaterialArtworkPalette.fromSeed("#0000ff", dark, style, preset);
                    assertEquals(a.sourceColorArgb, b.sourceColorArgb);
                    assertEquals(MaterialExpressivePalette.parseSeed(MaterialArtworkPalette.presetHex(preset)), a.sourceColorArgb);
                    assertEquals(a.getPrimary(), b.getPrimary());
                    assertEquals(a.getSurface(), b.getSurface());
                }
                assertNotEquals(MaterialArtworkPalette.fromSeed("", dark, style, 0).getPrimary(),
                        MaterialArtworkPalette.fromSeed("", dark, style, 1).getPrimary());
            }
        }
        for (int style : new int[] {0, 1, 2, 4}) {
            assertFalse(MaterialArtworkPalette.isStaticStyle(style));
            assertNotEquals(MaterialArtworkPalette.fromSeed("#ff0000", false, style, 0).getPrimary(),
                    MaterialArtworkPalette.fromSeed("#0000ff", false, style, 0).getPrimary());
        }
    }

    @Test public void sixStylesUseDistinctOfficialAlgorithmsAndPreserveExistingIndices() {
        dev.t1m3.qplayer.color.material.dynamiccolor.Variant[] variants = {
            dev.t1m3.qplayer.color.material.dynamiccolor.Variant.TONAL_SPOT,
            dev.t1m3.qplayer.color.material.dynamiccolor.Variant.VIBRANT,
            dev.t1m3.qplayer.color.material.dynamiccolor.Variant.EXPRESSIVE,
            dev.t1m3.qplayer.color.material.dynamiccolor.Variant.FRUIT_SALAD,
            dev.t1m3.qplayer.color.material.dynamiccolor.Variant.NEUTRAL,
            dev.t1m3.qplayer.color.material.dynamiccolor.Variant.RAINBOW
        };
        for (int style = 0; style < 6; style++) {
            DynamicScheme scheme = MaterialArtworkPalette.fromSeed("#1565c0", false, style, 0);
            assertEquals(variants[style], scheme.variant);
        }
    }

    @Test public void handlesMissingOrMalformedCoverSeed() {
        assertEquals(MaterialExpressivePalette.FALLBACK_SEED, MaterialExpressivePalette.parseSeed(null));
        for (String invalid : new String[] {"", "oops", "#123", "#12345678", "#gg88ee"}) {
            assertEquals(invalid, MaterialExpressivePalette.FALLBACK_SEED, MaterialExpressivePalette.parseSeed(invalid));
        }
        assertEquals(0xff00a4c8, MaterialExpressivePalette.parseSeed("#00A4c8"));
    }

    @Test public void coverChangesUpdateAccentsAndSurfaceWithoutChangingBrightnessMode() {
        DynamicScheme red = MaterialArtworkPalette.fromSeed("#e53935", false);
        DynamicScheme blue = MaterialArtworkPalette.fromSeed("#1565c0", false);
        assertNotEquals(red.getPrimary(), blue.getPrimary());
        assertNotEquals(red.getSecondaryContainer(), blue.getSecondaryContainer());
        assertNotEquals(red.getSurface(), blue.getSurface());
        for (String seed : new String[] {"#e53935", "#1565c0", "#777777", "#000000", "#ffffff"}) {
            assertTrue(Hct.fromInt(MaterialArtworkPalette.fromSeed(seed, false).getSurface()).getTone() > 95);
            assertTrue(Hct.fromInt(MaterialArtworkPalette.fromSeed(seed, true).getSurface()).getTone() < 10);
        }
    }

    @Test public void materialTextPairsRemainReadableAcrossCoverHuesAndBrightness() {
        // Dense hue samples plus neutral, very dark and very bright cover seeds.
        int[] extras = {0xff000000, 0xffffffff, 0xff777777, 0xff010102, 0xfffefdfb};
        for (int style = 0; style < 6; style++) {
            for (boolean dark : new boolean[] {false, true}) {
            int samples = MaterialArtworkPalette.isStaticStyle(style) ? MaterialArtworkPalette.presetCount() : 77;
            for (int i = 0; i < samples; i++) {
                int argb = i < 72 ? Hct.from(i * 5.0, 80, 50).toInt() : extras[i - 72];
                String seed = String.format("#%06x", argb & 0xffffff);
                DynamicScheme s = MaterialArtworkPalette.fromSeed(seed, dark, style, i);
                int[][] pairs = {
                    {s.getOnSurface(), s.getSurface()},
                    {s.getOnBackground(), s.getBackground()},
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
                    {s.getOnPrimaryFixedVariant(), s.getPrimaryFixedDim()},
                    {s.getOnSecondaryFixedVariant(), s.getSecondaryFixedDim()},
                    {s.getOnTertiaryFixedVariant(), s.getTertiaryFixedDim()},
                };
                for (int[] pair : pairs) {
                    assertTrue(seed + " dark=" + dark + " contrast=" + contrast(pair[0], pair[1]), contrast(pair[0], pair[1]) >= 4.45);
                    assertEquals(0xff, pair[0] >>> 24);
                    assertEquals(0xff, pair[1] >>> 24);
                }
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
