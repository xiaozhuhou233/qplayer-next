package dev.t1m3.qplayer.cache;

import dev.t1m3.qplayer.store.AppDirs;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PlaylistMembershipTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void deletedOrUnsubscribedPlaylistDoesNotReappearFromOfflineCache() throws Exception {
        String oldBase = AppDirs.base(), oldCache = AppDirs.cacheBase();
        try {
            AppDirs.setBase(temp.newFolder("base").toString());
            AppDirs.setCacheBase(temp.newFolder("cache").toString());
            PlaylistCacheIndex index = new PlaylistCacheIndex();
            index.upsert(1, "Keep", "", 0, null, true);
            index.upsert(2, "Remove", "", 0, null, true);
            index.setMineOrder(Arrays.asList(1L, 2L)); index.save();
            index.removeFromMine(2); index.save();
            PlaylistCacheIndex restored = new PlaylistCacheIndex(); restored.load();
            assertEquals(1, restored.mineSnapshot().size());
            assertEquals(1, restored.mineSnapshot().get(0).id);
            assertNotNull("Browsing cache may remain after unsubscription", restored.get(2));
            assertFalse(restored.get(2).mine);
        } finally { AppDirs.setBase(oldBase); AppDirs.setCacheBase(oldCache); }
    }
}
