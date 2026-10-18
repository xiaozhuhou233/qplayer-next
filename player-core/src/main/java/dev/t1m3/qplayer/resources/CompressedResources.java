package dev.t1m3.qplayer.resources;

import io.github.timer_err.qml4j.render.ResourceLoader;
import org.tukaani.xz.XZInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Loads a regular resource, falling back to an XZ-compressed sibling.
 *
 * <p>The plain path is tried first so development trees and third-party hosts can
 * keep shipping ordinary assets. QPlayer packages its large bundled fonts as
 * {@code .xz}; callers continue requesting the original {@code .otf} path.
 */
public final class CompressedResources {

    // The bundled streams use an 8 MiB dictionary. Keep a defensive ceiling so a
    // corrupt/replaced asset cannot make the decoder reserve unbounded memory.
    private static final int XZ_MEMORY_LIMIT_KIB = 32 * 1024;

    private CompressedResources() {
    }

    public static byte[] load(ResourceLoader resources, String path) {
        if (resources == null || path == null) return null;
        byte[] plain = resources.load(path);
        if (plain != null) return plain;

        byte[] compressed = resources.load(path + ".xz");
        if (compressed == null) return null;
        try (XZInputStream in = new XZInputStream(
                new ByteArrayInputStream(compressed), XZ_MEMORY_LIMIT_KIB);
             ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 2)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }
}
