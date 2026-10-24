package dev.t1m3.qplayer.ai;

import com.google.gson.Gson;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.util.Logger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Independent home recommendations: no playback controller or collection writes. */
public final class DailyVibeCuration {
    public static final int MIN_SONGS = 20;
    public static final int MAX_SONGS = 30;
    public static final String PERSONA = "你是“Vibe-Engine”，一位世界级的音乐策展人和声音心流大师。"
            + "你的目标是构建无缝、高保真的听觉体验。请优先考虑和声兼容性、符合逻辑的 BPM 过渡，"
            + "并在熟悉的挚爱与富有品味、逻辑驱动的发现之间取得精妙平衡。";
    private static final String[] ANGLES = {
            "从历史与收藏中选择一种常听的曲风，围绕它策划连贯的音乐旅程。",
            "从喜欢的音乐人出发，延伸到不同作者的相邻风格；与今天已有歌单形成区别。",
            "从历史与收藏中发掘另一种情绪、节奏或流派，串联熟悉作品与有品味的新发现。"
    };
    private static final Gson GSON = new Gson();

    public static final class Playlist {
        public int slot;
        public String name = "";
        public String summary = "";
        public List<String> suggestions = new ArrayList<>();
        public List<NeteaseSong> songs = new ArrayList<>();
        /** Generated groups keep the 20–30 rule; explicit user edits may have any size. */
        public boolean userEdited;
        public long savedPlaylistId;
        public List<Long> savedSongIds = new ArrayList<>();
    }
    public static final class Catalog {
        public String day = "";
        public String owner = "";
        public List<Playlist> playlists = new ArrayList<>();
    }
    public interface Recommender {
        AiPlaylistResult recommend(String request, String references, int count) throws Exception;
    }
    public interface Resolver {
        NeteaseSong resolve(String title, String artist) throws Exception;
        default List<NeteaseSong> resolveBatch(List<AiPlaylistResult.Song> songs, int target,
                Set<Long> excluded, Progress progress) throws Exception {
            return AiSongMatcher.sequential(songs, target, excluded, this, progress);
        }
    }
    public interface Progress {
        void matched(int count, int target);
    }

    public static Playlist generate(int slot, String references, List<Playlist> existing,
            Recommender recommender, Resolver resolver) throws Exception {
        return generate(slot, references, existing, recommender, resolver, (count, target) -> { });
    }

    public static Playlist generate(int slot, String references, List<Playlist> existing,
            Recommender recommender, Resolver resolver, Progress progress) throws Exception {
        return generate(slot, references, existing, recommender, resolver, progress, null, "");
    }

    public static Playlist generateTimed(String theme, String references, Recommender recommender,
            Resolver resolver, Progress progress) throws Exception {
        return generate(3, references, java.util.Collections.emptyList(), recommender, resolver, progress, null, theme);
    }

    /** Replace exactly one complete group, without mutating the published catalog on failure. */
    public static Catalog revise(Catalog catalog, int slot, String requirements, String references,
            Recommender recommender, Resolver resolver, Progress progress) throws Exception {
        if (!hasValidGroups(catalog) || catalog.playlists.isEmpty())
            throw new IllegalArgumentException("请先生成一组今日歌单");
        if (requirements == null || requirements.trim().isEmpty()) throw new IllegalArgumentException("请输入修改要求");
        Playlist original = catalog.playlists.stream().filter(p -> p.slot == slot).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("这组歌单已失效，请重新打开"));
        Playlist replacement = generate(slot, references, catalog.playlists, recommender, resolver, progress,
                original, requirements.trim());
        replacement.savedPlaylistId = original.savedPlaylistId;
        replacement.savedSongIds = original.savedSongIds == null ? new ArrayList<>() : new ArrayList<>(original.savedSongIds);
        Catalog updated = new Catalog(); updated.day = catalog.day; updated.owner = catalog.owner;
        for (Playlist playlist : catalog.playlists) updated.playlists.add(playlist.slot == slot ? replacement : playlist);
        return updated;
    }

    private static Playlist generate(int slot, String references, List<Playlist> existing,
            Recommender recommender, Resolver resolver, Progress progress, Playlist original,
            String requirements) throws Exception {
        if (slot < 0 || slot > 3) throw new IllegalArgumentException("Unknown daily playlist slot");
        int minimum = slot == 3 ? 10 : MIN_SONGS;
        int target = slot == 3 ? 10 : java.util.concurrent.ThreadLocalRandom.current().nextInt(MIN_SONGS, MAX_SONGS + 1);
        // Output schema, search policy and recovery are owned by the same
        // AiClient prompt as AI DJ. This request contains only curation preferences.
        StringBuilder request = new StringBuilder("策展偏好：").append(PERSONA).append("\n")
                .append("把相近曲风放在同一歌单，按听感连贯排序。")
                .append(original == null ? (slot == 3 ? requirements : ANGLES[slot]) : "以当前歌单为基础，优先执行下面的用户修改要求，原来的默认方向不限制本次修改。")
                .append('\n');
        if (original != null) {
            request.append("当前歌单：").append(original.name).append(" · ").append(original.summary).append('\n');
            for (NeteaseSong song : original.songs) request.append(song.name).append(" — ").append(song.artist).append("；");
            request.append("\n用户修改要求（优先执行）：").append(requirements)
                    .append("\n保留符合要求的原曲，替换其余歌曲。JSON 另加 suggestions 字符串数组，")
                    .append("用用户口吻给出2至3条贴合修改结果、可继续发送的简短建议。\n");
        }
        Set<Long> used = new HashSet<>();
        if (existing != null) for (Playlist playlist : existing) {
            if (playlist.slot == slot) continue;
            request.append("今天已有：").append(playlist.name).append(" · ").append(playlist.summary).append('\n');
            for (NeteaseSong song : playlist.songs) {
                used.add(song.id);
                request.append(song.name).append(" — ").append(song.artist).append("；");
            }
        }
        request.append("避免与已有歌单重复歌曲和主题；相邻风格可以重叠，不必为区分主题偏离个人偏好。");
        Playlist playlist = new Playlist();
        playlist.slot = slot;
        Set<String> pairs = new HashSet<>();
        IOException lastFailure = null;
        boolean receivedPlaylist = false;
        // Failed/duplicate matches are replaced by a bounded continuation in the same style.
        for (int attempt = 0; attempt < 3; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String followUp = "";
            int count = target;
            if (!playlist.songs.isEmpty()) {
                count = Math.max(5, target - playlist.songs.size());
                followUp = "\n继续同一歌单「" + playlist.name + "」：" + playlist.summary
                        + "。部分歌曲在网易云未匹配成功，请仅补充 " + count
                        + " 首，延续已匹配歌曲的曲风和能量；仍以个人偏好为主，不要再次推荐这些已尝试歌曲："
                        + String.join("；", pairs);
            }
            AiPlaylistResult result;
            long aiStart = System.nanoTime();
            try {
                result = recommender.recommend(request + followUp, references, count);
            } catch (IOException failure) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (!AiEndpoint.isConnectionFailure(failure) && !(failure instanceof AiClient.ResponseFormatException))
                    throw failure;
                lastFailure = failure;
                if (attempt < 2) Thread.sleep(400L * (attempt + 1));
                continue;
            }
            progress.matched(playlist.songs.size(), target);
            if (!receivedPlaylist) {
                playlist.name = result.playlistName == null || result.playlistName.trim().isEmpty()
                        ? "今日心流 " + (slot + 1) : result.playlistName.trim();
                playlist.summary = result.summary == null ? "" : result.summary.trim();
                playlist.suggestions = new ArrayList<>(result.suggestions);
                receivedPlaylist = true;
            }
            List<AiPlaylistResult.Song> candidates = new ArrayList<>();
            for (AiPlaylistResult.Song row : result.songs)
                if (pairs.add(row.title + '\n' + row.artist)) candidates.add(row);
            int alreadyMatched = playlist.songs.size();
            long matchStart = System.nanoTime();
            List<NeteaseSong> matched = resolver.resolveBatch(candidates, target - alreadyMatched, used,
                    (countMatched, ignored) -> progress.matched(alreadyMatched + countMatched, target));
            Logger.info("ai daily group {}: generated in {} ms — matched {} track(s) in {} ms",
                    slot + 1, (matchStart - aiStart) / 1_000_000L, matched.size(),
                    (System.nanoTime() - matchStart) / 1_000_000L);
            playlist.songs.addAll(matched);
            for (NeteaseSong song : matched) used.add(song.id);
            if (playlist.songs.size() >= minimum) return playlist;
        }
        if (lastFailure != null) throw lastFailure;
        throw new IOException("这组暂未匹配到至少 " + minimum + " 首网易云歌曲");
    }

    public static Catalog restore(String json, String day, String owner) {
        Catalog empty = new Catalog(); empty.day = day; empty.owner = owner;
        try {
            Catalog stored = GSON.fromJson(json, Catalog.class);
            if (stored == null || !day.equals(stored.day) || !owner.equals(stored.owner)
                    || stored.playlists == null) return empty;
            Set<Integer> slots = new HashSet<>();
            for (Playlist playlist : stored.playlists) {
                if (playlist == null || playlist.slot < 0 || playlist.slot > 3 || playlist.songs == null
                        || (!playlist.userEdited && !validSize(playlist))
                        || slots.contains(playlist.slot)) continue;
                if (playlist.songs.stream().anyMatch(song -> song == null || song.id <= 0)) continue;
                Set<Long> ids = new HashSet<>();
                if (playlist.songs.stream().anyMatch(song -> !ids.add(song.id))) continue;
                slots.add(playlist.slot);
                empty.playlists.add(playlist);
            }
        } catch (RuntimeException ignored) { }
        return empty;
    }

    public static String encode(Catalog catalog) { return GSON.toJson(catalog); }

    /** Copy on edit, so published UI/playing queues never mutate underneath a reader. */
    public static Catalog withSongs(Catalog catalog, int slot, List<NeteaseSong> songs) {
        if (!hasValidGroups(catalog) || songs == null) throw new IllegalArgumentException("歌单已失效");
        Set<Long> ids = new HashSet<>();
        for (NeteaseSong song : songs)
            if (song == null || song.id <= 0 || !ids.add(song.id))
                throw new IllegalArgumentException("歌曲无效或重复");
        Playlist original = catalog.playlists.stream().filter(p -> p.slot == slot).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("歌单已失效"));
        Playlist edited = new Playlist();
        edited.slot = slot; edited.name = original.name; edited.summary = original.summary;
        edited.suggestions = original.suggestions == null ? new ArrayList<>() : new ArrayList<>(original.suggestions);
        edited.songs = new ArrayList<>(songs); edited.userEdited = true;
        edited.savedPlaylistId = original.savedPlaylistId;
        edited.savedSongIds = original.savedSongIds == null ? new ArrayList<>() : new ArrayList<>(original.savedSongIds);
        Catalog result = new Catalog(); result.day = catalog.day; result.owner = catalog.owner;
        for (Playlist p : catalog.playlists) result.playlists.add(p.slot == slot ? edited : p);
        return result;
    }

    /** Completion stops automatic generation; each finished group can be shown independently. */
    public static boolean isComplete(Catalog catalog) {
        return hasValidGroups(catalog) && catalog.playlists.stream().filter(p -> p.slot < 3).count() == 3;
    }

    private static boolean hasValidGroups(Catalog catalog) {
        if (catalog == null || catalog.playlists == null || catalog.playlists.size() > 4) return false;
        Set<Integer> slots = new HashSet<>();
        for (Playlist playlist : catalog.playlists) {
            if (playlist == null || playlist.slot < 0 || playlist.slot > 3 || !slots.add(playlist.slot)
                    || playlist.songs == null || (!playlist.userEdited && !validSize(playlist))) return false;
            Set<Long> ids = new HashSet<>();
            for (NeteaseSong song : playlist.songs)
                if (song == null || song.id <= 0 || !ids.add(song.id)) return false;
        }
        return true;
    }
    private static boolean validSize(Playlist playlist) {
        return playlist.slot == 3 ? playlist.songs.size() == 10
                : playlist.songs.size() >= MIN_SONGS && playlist.songs.size() <= MAX_SONGS;
    }
    private DailyVibeCuration() {}
}
