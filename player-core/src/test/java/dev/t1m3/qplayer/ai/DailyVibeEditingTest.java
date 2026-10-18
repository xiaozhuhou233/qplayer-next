package dev.t1m3.qplayer.ai;

import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DailyVibeEditingTest {
    private NeteaseSong song(long id) {
        NeteaseSong s = new NeteaseSong(); s.id = id; s.name = "Song " + id;
        s.recommendReason = "Reason " + id; return s;
    }
    private DailyVibeCuration.Catalog catalog() {
        DailyVibeCuration.Catalog c = new DailyVibeCuration.Catalog(); c.day = "2026-10-12"; c.owner = "account";
        for (int slot = 0; slot < 3; slot++) {
            DailyVibeCuration.Playlist p = new DailyVibeCuration.Playlist();
            p.slot = slot; p.name = "Playlist " + slot; p.summary = "Summary";
            for (long id = slot * 20L + 1; id <= slot * 20L + 20; id++) p.songs.add(song(id));
            c.playlists.add(p);
        }
        return c;
    }

    @Test public void removeAddAndReorderSurviveRestartWithoutMutatingThePlayingSnapshot() {
        DailyVibeCuration.Catalog original = catalog();
        DailyVibeCuration.Playlist source = original.playlists.get(0);
        source.savedPlaylistId = 789; source.savedSongIds = Arrays.asList(1L, 2L);
        List<NeteaseSong> songs = new ArrayList<>(Arrays.asList(source.songs.get(1), song(99), source.songs.get(0)));
        DailyVibeCuration.Catalog updated = DailyVibeCuration.withSongs(original, 0, songs);
        songs.clear();
        assertEquals(20, source.songs.size());
        assertSame(original.playlists.get(1), updated.playlists.get(1));
        DailyVibeCuration.Catalog restored = DailyVibeCuration.restore(DailyVibeCuration.encode(updated), original.day, original.owner);
        assertTrue(DailyVibeCuration.isComplete(restored));
        DailyVibeCuration.Playlist edited = restored.playlists.get(0);
        assertEquals(Arrays.asList(2L, 99L, 1L), ids(edited));
        assertEquals("Reason 2", edited.songs.get(0).recommendReason);
        assertEquals(789, edited.savedPlaylistId);
        assertEquals(Arrays.asList(1L, 2L), edited.savedSongIds);
        assertTrue(edited.userEdited);
    }

    @Test public void userCanEmptyOrExpandAGeneratedPlaylistWithoutStartingAutomaticGenerationAgain() {
        DailyVibeCuration.Catalog c = catalog();
        c = DailyVibeCuration.withSongs(c, 0, Collections.emptyList());
        List<NeteaseSong> large = new ArrayList<>();
        for (long id = 100; id < 150; id++) large.add(song(id));
        c = DailyVibeCuration.withSongs(c, 1, large);
        DailyVibeCuration.Catalog restored = DailyVibeCuration.restore(DailyVibeCuration.encode(c), c.day, c.owner);
        assertTrue(DailyVibeCuration.isComplete(restored));
        assertEquals(0, restored.playlists.get(0).songs.size());
        assertEquals(50, restored.playlists.get(1).songs.size());
        assertTrue(DailyVibeCuration.restore(DailyVibeCuration.encode(c), "tomorrow", c.owner).playlists.isEmpty());
        assertTrue(DailyVibeCuration.restore(DailyVibeCuration.encode(c), c.day, "other account").playlists.isEmpty());
    }

    @Test public void duplicateSongsAreRejectedWithoutChangingTheOriginal() {
        DailyVibeCuration.Catalog c = catalog();
        try { DailyVibeCuration.withSongs(c, 0, Arrays.asList(song(1), song(1))); fail(); }
        catch (IllegalArgumentException expected) { }
        assertEquals(20, c.playlists.get(0).songs.size());
    }

    private List<Long> ids(DailyVibeCuration.Playlist playlist) {
        List<Long> ids = new ArrayList<>();
        for (NeteaseSong song : playlist.songs) ids.add(song.id);
        return ids;
    }
}
