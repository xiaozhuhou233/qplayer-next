package dev.t1m3.qplayer.library;

import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LibraryCacheMigrationTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void remapsPathsStoredInsideMovedLocalLibraryIndex() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        try {
            Path base = temporaryFolder.newFolder("local-cache-migration").toPath();
            Path legacy = base.resolve("local-cache");
            Path oldCover = legacy.resolve("covers/art.img");
            Files.createDirectories(oldCover.getParent());
            Files.write(oldCover, new byte[]{1});
            String json = "{\"version\":3,\"entries\":[{"
                    + "\"filePath\":\"song.mp3\","
                    + "\"title\":\"song\","
                    + "\"coverLocalPath\":" + quote(oldCover.toString()) + ","
                    + "\"coverThumbPath\":" + quote(oldCover.toString())
                    + "}]}";
            Files.write(legacy.resolve("library.json"), json.getBytes(StandardCharsets.UTF_8));

            AppDirs.setBase(base.toString());
            LibraryCache cache = new LibraryCache();
            Map<String, Track> loaded = cache.load();

            Path migratedCover = AppDirs.localCacheDir().resolve("covers/art.img");
            assertTrue(Files.isRegularFile(migratedCover));
            assertFalse(Files.exists(legacy));
            assertEquals(migratedCover.toString(), loaded.get("song.mp3").coverLocalPath);
            assertEquals(migratedCover.toString(), loaded.get("song.mp3").coverThumbPath);
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
