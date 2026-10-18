package dev.t1m3.qplayer.resources;

import org.junit.Test;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public class CompressedResourcesTest {

    @Test
    public void loadsCompressedSiblingWhenPlainResourceIsMissing() throws Exception {
        byte[] expected = "QPlayer bundled font".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = xz(expected);

        byte[] actual = CompressedResources.load(
                name -> "fonts/example.otf.xz".equals(name) ? compressed : null,
                "fonts/example.otf");

        assertArrayEquals(expected, actual);
    }

    @Test
    public void prefersPlainResourceForBackwardCompatibility() {
        byte[] expected = {1, 2, 3};
        byte[] actual = CompressedResources.load(
                name -> "font.otf".equals(name) ? expected : new byte[]{9},
                "font.otf");
        assertArrayEquals(expected, actual);
    }

    @Test
    public void rejectsInvalidCompressedResource() {
        assertNull(CompressedResources.load(
                name -> name.endsWith(".xz") ? new byte[]{1, 2, 3} : null,
                "font.otf"));
    }

    private static byte[] xz(byte[] input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XZOutputStream out = new XZOutputStream(bytes, new LZMA2Options(1))) {
            out.write(input);
        }
        return bytes.toByteArray();
    }
}
