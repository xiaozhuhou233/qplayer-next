package dev.t1m3.qplayer.store;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppDirsTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void migratesFlatLayoutWithoutCopyingCacheContents() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        try {
            Path base = temporaryFolder.newFolder("legacy-layout").toPath();
            Files.write(base.resolve("settings.json"), "settings".getBytes(StandardCharsets.UTF_8));
            Files.write(base.resolve("queue.json"), "queue".getBytes(StandardCharsets.UTF_8));
            Files.write(base.resolve("netease-cookies.json"), "cookies".getBytes(StandardCharsets.UTF_8));
            Files.write(base.resolve("song_meta_index.json"), "songs".getBytes(StandardCharsets.UTF_8));
            Files.write(base.resolve("instance.port"), "1234".getBytes(StandardCharsets.UTF_8));
            Files.write(base.resolve("search_history.txt"), "legacy".getBytes(StandardCharsets.UTF_8));
            Path oldCover = base.resolve("local-cache/covers/cover.img");
            Files.createDirectories(oldCover.getParent());
            Files.write(oldCover, new byte[]{1, 2, 3});

            AppDirs.setBase(base.toString());
            AppDirs.migrateLegacyLayout();

            assertEquals("settings", StorageFiles.readUtf8(AppDirs.configFile("settings.json")));
            assertEquals("queue", StorageFiles.readUtf8(AppDirs.stateFile("queue.json")));
            assertEquals("cookies", StorageFiles.readUtf8(
                    AppDirs.credentialsFile("netease-cookies.json")));
            assertEquals("songs", StorageFiles.readUtf8(AppDirs.indexFile("songs.json")));
            assertEquals("1234", StorageFiles.readUtf8(AppDirs.runtimeFile("instance.port")));
            assertTrue(Files.isRegularFile(AppDirs.localCacheDir().resolve("covers/cover.img")));

            assertFalse(Files.exists(base.resolve("settings.json")));
            assertFalse(Files.exists(base.resolve("queue.json")));
            assertFalse(Files.exists(base.resolve("local-cache")));
            // TXT needs a format conversion, so AppDirs deliberately leaves it for
            // PlayerController rather than merely renaming invalid text to .json.
            assertTrue(Files.isRegularFile(base.resolve("search_history.txt")));
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void pathLookupAlsoMigratesFilesCreatedAfterSetBase() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        try {
            Path base = temporaryFolder.newFolder("lazy-migration").toPath();
            AppDirs.setBase(base.toString());
            Files.write(base.resolve("queue.json"), "late".getBytes(StandardCharsets.UTF_8));

            Path target = AppDirs.stateFile("queue.json");
            assertEquals("late", StorageFiles.readUtf8(target));
            assertFalse(Files.exists(base.resolve("queue.json")));
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }

    @Test
    public void atomicWriteReplacesContentAndCleansPendingFile() throws Exception {
        Path target = temporaryFolder.newFolder("atomic").toPath().resolve("state/value.json");
        StorageFiles.writeUtf8Atomic(target, "old");
        StorageFiles.writeUtf8Atomic(target, "new");
        assertEquals("new", StorageFiles.readUtf8(target));
        assertFalse(Files.exists(StorageFiles.pendingPath(target)));
    }
}
