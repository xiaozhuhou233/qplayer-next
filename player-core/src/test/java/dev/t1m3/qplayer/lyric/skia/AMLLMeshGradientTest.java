package dev.t1m3.qplayer.lyric.skia;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AMLLMeshGradientTest {

    @Test
    public void loadsPresetAndBuildsFiniteTwentyFourSubdivisionMesh() {
        AMLLMeshGradient.Data mesh = AMLLMeshGradient.create();

        assertEquals(mesh.normalizedPoints.length, mesh.textureCoordinates.length);
        int pointCount = mesh.normalizedPoints.length / 2;
        assertTrue(pointCount == 72 * 72
                || pointCount == 96 * 96
                || pointCount == 120 * 120);
        assertTrue(mesh.indices.length > 0);

        for (float value : mesh.normalizedPoints) {
            assertTrue(Float.isFinite(value));
        }
        for (float value : mesh.textureCoordinates) {
            assertTrue(Float.isFinite(value));
            assertTrue(value >= -0.001f && value <= 32.001f);
        }
    }
}
