package dev.t1m3.qplayer.ai;

import com.google.gson.Gson;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Bounded metadata lookups shared by DJ and daily curation; never changes playback. */
public final class AiSongMatcher implements AutoCloseable {
    private static final int PARALLELISM = 4;
    private static final Gson GSON = new Gson();
    private static final long CACHE_TTL = TimeUnit.MINUTES.toNanos(30);
    private final DailyVibeCuration.Resolver lookup;
    private final ExecutorService workers = Executors.newFixedThreadPool(PARALLELISM, work -> {
        Thread thread = new Thread(work, "qplayer-ai-match");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, CachedSong> cache = new LinkedHashMap<String, CachedSong>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CachedSong> entry) { return size() > 512; }
    };

    public AiSongMatcher(DailyVibeCuration.Resolver lookup) { this.lookup = lookup; }

    /** Seed from actual history/collection metadata, so familiar songs need no search. */
    public void remember(Iterable<NeteaseSong> songs) {
        if (songs == null) return;
        for (NeteaseSong song : songs) {
            if (song == null || song.id <= 0 || song.name == null || song.artist == null
                    || song.name.trim().isEmpty() || song.artist.trim().isEmpty()) continue;
            put(key(song.name, song.artist), song);
        }
    }

    public NeteaseSong resolve(String title, String artist) throws Exception {
        String key = key(title, artist);
        synchronized (cache) {
            CachedSong cached = cache.get(key);
            if (cached != null && System.nanoTime() - cached.time < CACHE_TTL) return copy(cached.song);
            cache.remove(key);
        }
        NeteaseSong song = lookup.resolve(title, artist);
        if (song != null && song.id > 0) put(key, song);
        return song;
    }

    private void put(String key, NeteaseSong song) {
        NeteaseSong metadata = copy(song);
        metadata.recommendReason = "";
        synchronized (cache) { cache.put(key, new CachedSong(metadata)); }
    }

    /** A four-row lookahead overlaps network waits while accepting results in curated order. */
    public List<NeteaseSong> match(List<AiPlaylistResult.Song> rows, int target, Set<Long> excluded,
            DailyVibeCuration.Progress progress, Runnable check) throws Exception {
        List<AiPlaylistResult.Song> unique = unique(rows);
        List<NeteaseSong> matched = new ArrayList<>();
        Set<Long> used = new HashSet<>(excluded);
        ArrayDeque<Future<NeteaseSong>> pending = new ArrayDeque<>();
        int submitted = 0, accepted = 0;
        try {
            while (accepted < unique.size() && matched.size() < target) {
                check(check);
                while (submitted < unique.size() && pending.size() < Math.min(PARALLELISM, target - matched.size())) {
                    AiPlaylistResult.Song row = unique.get(submitted++);
                    pending.add(workers.submit(() -> {
                        check(check);
                        return lookupRow(row, this::resolve);
                    }));
                }
                NeteaseSong song;
                Future<NeteaseSong> first = pending.getFirst();
                try { song = first.get(); }
                catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw new IllegalStateException(cause);
                }
                pending.removeFirst();
                AiPlaylistResult.Song row = unique.get(accepted++);
                check(check);
                accept(matched, used, song, row, target, progress);
            }
            return matched;
        } finally {
            for (Future<?> task : pending) task.cancel(true);
        }
    }

    /** Default for callers with their own resolver; shares filtering/reason semantics. */
    static List<NeteaseSong> sequential(List<AiPlaylistResult.Song> rows, int target, Set<Long> excluded,
            DailyVibeCuration.Resolver resolver, DailyVibeCuration.Progress progress) throws Exception {
        List<NeteaseSong> matched = new ArrayList<>();
        Set<Long> used = new HashSet<>(excluded);
        for (AiPlaylistResult.Song row : unique(rows)) {
            if (matched.size() >= target) break;
            check(null);
            accept(matched, used, lookupRow(row, resolver), row, target, progress);
        }
        return matched;
    }

    private static NeteaseSong lookupRow(AiPlaylistResult.Song row, DailyVibeCuration.Resolver resolver) throws Exception {
        try { return resolver.resolve(row.title, row.artist); }
        catch (IOException failure) {
            check(null);
            if (AiEndpoint.isConnectionFailure(failure)) throw failure;
            return null;
        }
    }

    private static void accept(List<NeteaseSong> matched, Set<Long> used, NeteaseSong song,
            AiPlaylistResult.Song row, int target, DailyVibeCuration.Progress progress) {
        if (song == null || song.id <= 0 || !used.add(song.id)) return;
        NeteaseSong metadata = copy(song);
        metadata.recommendReason = row.reason == null ? "" : row.reason.trim();
        matched.add(metadata);
        progress.matched(matched.size(), target);
    }

    private static List<AiPlaylistResult.Song> unique(List<AiPlaylistResult.Song> rows) {
        if (rows == null) return Collections.emptyList();
        List<AiPlaylistResult.Song> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AiPlaylistResult.Song row : rows) {
            if (row != null && row.title != null && row.artist != null && seen.add(key(row.title, row.artist)))
                unique.add(row);
        }
        return unique;
    }

    private static String key(String title, String artist) {
        return title.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + '\n'
                + artist.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static void check(Runnable check) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (check != null) check.run();
    }

    private static NeteaseSong copy(NeteaseSong song) { return GSON.fromJson(GSON.toJson(song), NeteaseSong.class); }
    private static final class CachedSong {
        final NeteaseSong song;
        final long time = System.nanoTime();
        CachedSong(NeteaseSong song) { this.song = song; }
    }
    @Override public void close() { workers.shutdownNow(); }
}
