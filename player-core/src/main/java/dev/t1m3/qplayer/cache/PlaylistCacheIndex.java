package dev.t1m3.qplayer.cache;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted playlist -&gt; song-list snapshot, built up incrementally as the
 * user actually browses their playlists online (same "record what passed
 * through, don't bulk pre-fetch" philosophy as {@link SongMetaIndex}). Lets
 * 我的歌单 and a previously-opened playlist's track list still render with no
 * network; whether a listed track actually *plays* offline still depends on
 * whether its audio was separately disk-cached by {@code cacheAudioAsync}
 * (i.e. actually played at some point), same as offline search.
 *
 * <p>Song entries persist {@code coverUrl} only, never a CDN thumbnail url —
 * useless offline. The 64x64 thumbnail itself is downloaded separately (see
 * {@code PlayerController.cacheThumb64Async}): once for every track in a
 * freshly opened playlist (capped — see {@code PLAYLIST_THUMB_CACHE_LIMIT} —
 * so a 200-track playlist doesn't queue 200 downloads at once), and again as
 * a side effect of a track actually being played, which backfills one for a
 * track that missed the browse-time cap or was cached before this existed.
 */
public final class PlaylistCacheIndex {

    /** One cached playlist. {@code songs} is empty until the playlist has
     *  actually been opened online at least once (loadMyPlaylists only ever
     *  supplies the summary fields). */
    public static final class Cached {
        public long id;
        public String name;
        public String coverUrl;
        public int trackCount;
        public List<NeteaseSong> songs = new ArrayList<>();
        /** True only for playlists that actually appeared in the signed-in user's
         *  own 我的列表 (set from {@code loadMyPlaylists}). A playlist merely opened
         *  from elsewhere (推荐, search, a shared link) still gets upserted here so
         *  its song list/cover survive offline, but must never leak into the
         *  offline substitute for 我的 — see {@code offlineMyPlaylistsFallback}. */
        public boolean mine;
    }

    // Keep every playlist returned by the account query (currently requested
    // with a 1000-item ceiling), plus some room for playlists opened from
    // recommendations/search without evicting the user's own list.
    private static final int MAX_ENTRIES = 1200;

    /** Versioned wrapper; load() still accepts the original bare array format. */
    private static final class State {
        List<Cached> playlists = new ArrayList<>();
        List<Long> mineOrder = new ArrayList<>();
    }

    private final File file = AppDirs.indexFile("playlists.json").toFile();
    private final Gson gson = new Gson();
    private final Map<Long, Cached> byId = Collections.synchronizedMap(
            new LinkedHashMap<Long, Cached>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Cached> e) {
                    return size() > MAX_ENTRIES;
                }
            });
    /** Server order of the signed-in user's playlists, independent of LRU access. */
    private final List<Long> mineOrder = new ArrayList<>();
    private volatile boolean dirty = false;

    public void load() {
        try {
            if (!file.isFile()) return;
            String json = StorageFiles.readUtf8(file.toPath());
            JsonElement root = new JsonParser().parse(json);
            List<Cached> list;
            List<Long> savedOrder = Collections.emptyList();
            if (root.isJsonArray()) {
                // Backward compatibility with the pre-order-cache format.
                Type t = new TypeToken<List<Cached>>() {}.getType();
                list = gson.fromJson(root, t);
            } else {
                State state = gson.fromJson(root, State.class);
                list = state != null && state.playlists != null
                        ? state.playlists : Collections.<Cached>emptyList();
                if (state != null && state.mineOrder != null) savedOrder = state.mineOrder;
            }
            if (list == null) return;
            synchronized (byId) {
                for (Cached e : list) {
                    if (e != null && e.id != 0 && e.name != null) byId.put(e.id, e);
                }
                mineOrder.clear();
                for (Long id : savedOrder) {
                    if (id != null && id != 0 && !mineOrder.contains(id)) mineOrder.add(id);
                }
            }
        } catch (Throwable e) {
            Logger.warn("PlaylistCacheIndex load failed: {}", e.getMessage());
        }
    }

    /** Record/refresh a playlist's summary fields and (optionally) its song
     *  list. Pass {@code songs} null from a summary-only refresh (loadMyPlaylists)
     *  to leave a previously-cached song list untouched. {@code mine} marks this
     *  as one of the signed-in user's own playlists; once set it's sticky (a later
     *  {@code openPlaylist} upsert with {@code mine=false} must not clear it). */
    public void upsert(long id, String name, String coverUrl, int trackCount, List<NeteaseSong> songs, boolean mine) {
        if (id == 0 || name == null || name.isEmpty()) return;
        synchronized (byId) {
            Cached e = byId.get(id);
            if (e == null) {
                e = new Cached();
                e.id = id;
                byId.put(id, e);
            }
            e.name = name;
            e.coverUrl = coverUrl;
            e.trackCount = trackCount;
            e.mine = e.mine || mine;
            if (songs != null) e.songs = stripThumbs(songs);
        }
        dirty = true;
    }

    public Cached get(long id) {
        return byId.get(id);
    }

    /** Remove account membership immediately, even if the following refresh is offline. */
    public void removeFromMine(long id) {
        synchronized (byId) {
            Cached e = byId.get(id);
            if (e != null) e.mine = false;
            mineOrder.remove(Long.valueOf(id));
        }
        dirty = true;
    }

    /** Save the exact order returned by /user/playlist for fast offline startup. */
    public void setMineOrder(List<Long> ids) {
        synchronized (byId) {
            // A successful server refresh is authoritative. Entries removed
            // from the account must not reappear in a later offline snapshot.
            for (Cached e : byId.values()) e.mine = false;
            mineOrder.clear();
            if (ids != null) {
                for (Long id : ids) {
                    if (id != null && id != 0 && !mineOrder.contains(id)) {
                        mineOrder.add(id);
                        Cached e = byId.get(id);
                        if (e != null) e.mine = true;
                    }
                }
            }
        }
        dirty = true;
    }

    /** Cached signed-in playlists in server order, with older caches appended. */
    public List<Cached> mineSnapshot() {
        List<Cached> out = new ArrayList<>();
        synchronized (byId) {
            for (Long id : mineOrder) {
                Cached e = byId.get(id);
                if (e != null && e.mine) out.add(e);
            }
            // A cache from before mineOrder existed has no saved order. Preserve
            // those entries instead of making the first offline launch blank.
            for (Cached e : byId.values()) {
                if (e.mine && !out.contains(e)) out.add(e);
            }
        }
        return out;
    }

    /** Most-recently-touched last (LinkedHashMap access order) — reversed so
     *  the freshest playlists come first. */
    public List<Cached> snapshot() {
        List<Cached> out;
        synchronized (byId) {
            out = new ArrayList<>(byId.values());
        }
        Collections.reverse(out);
        return out;
    }

    public synchronized void save() {
        if (!dirty) return;
        try {
            List<Cached> snap;
            List<Long> order;
            synchronized (byId) {
                snap = new ArrayList<>(byId.values());
                order = new ArrayList<>(mineOrder);
            }
            State state = new State();
            state.playlists = snap;
            state.mineOrder = order;
            StorageFiles.writeUtf8Atomic(file.toPath(), gson.toJson(state));
            dirty = false;
        } catch (Throwable e) {
            Logger.warn("PlaylistCacheIndex save failed: {}", e.getMessage());
        }
    }

    /** Copy each song, dropping the CDN coverThumbPath (only coverUrl is worth
     *  persisting — the thumbnail itself lives in DiskCache, keyed off it). */
    private static List<NeteaseSong> stripThumbs(List<NeteaseSong> songs) {
        List<NeteaseSong> out = new ArrayList<>(songs.size());
        for (NeteaseSong s : songs) {
            NeteaseSong copy = new NeteaseSong();
            copy.id = s.id;
            copy.name = s.name;
            copy.artist = s.artist;
            copy.album = s.album;
            copy.coverUrl = s.coverUrl;
            copy.durationMs = s.durationMs;
            copy.fee = s.fee;
            out.add(copy);
        }
        return out;
    }
}
