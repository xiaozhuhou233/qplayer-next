package dev.t1m3.qplayer.cache;

import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class SongMetaIndexTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void persistedMetadataKeepsEveryArtistCredit() throws Exception {
        String oldBase = AppDirs.base();
        String oldCacheBase = AppDirs.cacheBase();
        try {
            Path base = temporaryFolder.newFolder("song-meta-artists").toPath();
            AppDirs.setBase(base.toString());

            NeteaseSong song = new NeteaseSong();
            song.id = 42L;
            song.name = "song";
            song.artist = "first / second";
            song.artistId = 7L;
            song.artistIdsCsv = "7,8";
            song.artistNamesCsv = "first\u0001second";

            SongMetaIndex written = new SongMetaIndex();
            written.upsert(song);
            written.save();

            SongMetaIndex restored = new SongMetaIndex();
            restored.load();
            NeteaseSong value = restored.all().get(0);
            assertEquals(7L, value.artistId);
            assertEquals("7,8", value.artistIdsCsv);
            assertEquals("first\u0001second", value.artistNamesCsv);
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCacheBase);
        }
    }
}
