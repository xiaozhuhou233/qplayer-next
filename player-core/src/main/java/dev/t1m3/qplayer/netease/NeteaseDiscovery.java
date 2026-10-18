package dev.t1m3.qplayer.netease;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** NetEase playlist catalogue and paginated category browsing; no invented genres. */
public final class NeteaseDiscovery {
    private NeteaseDiscovery() {}

    public static final class Category {
        public final String name;
        public final String group;
        public final int groupId;
        public final boolean hot;
        Category(String name, String group, int groupId, boolean hot) {
            this.name = name; this.group = group; this.groupId = groupId; this.hot = hot;
        }
    }

    public static final class PlaylistPage {
        public final List<NeteasePlaylist> playlists;
        public final int nextOffset;
        public final boolean more;
        PlaylistPage(List<NeteasePlaylist> playlists, int nextOffset, boolean more) {
            this.playlists = Collections.unmodifiableList(playlists);
            this.nextOffset = nextOffset; this.more = more;
        }
    }

    static List<Category> parseCategories(JsonObject root) throws IOException {
        if (root == null || !root.has("sub") || !root.get("sub").isJsonArray())
            throw new IOException("网易云暂未返回音乐分类，请重试");
        JsonObject groups = root.has("categories") && root.get("categories").isJsonObject()
                ? root.getAsJsonObject("categories") : new JsonObject();
        Map<String, Category> result = new LinkedHashMap<>();
        for (JsonElement element : root.getAsJsonArray("sub")) {
            if (!element.isJsonObject()) continue;
            try {
                JsonObject row = element.getAsJsonObject();
                String name = row.get("name").getAsString().trim();
                int groupId = row.get("category").getAsInt();
                String group = groups.has(String.valueOf(groupId))
                        ? groups.get(String.valueOf(groupId)).getAsString().trim() : "其他";
                boolean hot = row.has("hot") && !row.get("hot").isJsonNull() && row.get("hot").getAsBoolean();
                if (!name.isEmpty() && !result.containsKey(name))
                    result.put(name, new Category(name, group.isEmpty() ? "其他" : group, groupId, hot));
            } catch (RuntimeException ignored) { /* A malformed row must not hide the catalogue. */ }
        }
        if (result.isEmpty()) throw new IOException("网易云暂未返回有效音乐分类，请重试");
        return Collections.unmodifiableList(new ArrayList<>(result.values()));
    }

    static Map<String, Object> playlistBody(String category, int offset, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cat", category == null || category.trim().isEmpty() ? "全部" : category.trim());
        body.put("order", "hot");
        body.put("limit", Math.max(1, Math.min(60, limit)));
        body.put("offset", Math.max(0, offset));
        body.put("total", true);
        return body;
    }

    static PlaylistPage parsePlaylists(JsonObject root, int offset, int limit) throws IOException {
        if (root == null || !root.has("playlists") || !root.get("playlists").isJsonArray())
            throw new IOException("网易云暂未返回分类歌单，请重试");
        JsonArray rows = root.getAsJsonArray("playlists");
        Map<Long, NeteasePlaylist> result = new LinkedHashMap<>();
        for (JsonElement element : rows) {
            if (!element.isJsonObject()) continue;
            try {
                NeteasePlaylist playlist = NeteaseClient.parsePlaylist(element.getAsJsonObject());
                if (playlist.id <= 0 || playlist.name == null || playlist.name.trim().isEmpty()) continue;
                if (playlist.coverUrl != null && !playlist.coverUrl.isEmpty())
                    playlist.coverThumbPath = playlist.coverUrl + (playlist.coverUrl.contains("?") ? "&" : "?") + "param=320y320";
                result.putIfAbsent(playlist.id, playlist);
            } catch (RuntimeException ignored) { /* Skip broken cards, preserving server order. */ }
        }
        boolean more = rows.size() >= limit;
        try { if (root.has("more")) more = root.get("more").getAsBoolean(); }
        catch (RuntimeException ignored) { }
        return new PlaylistPage(new ArrayList<>(result.values()), offset + rows.size(), more && rows.size() > 0);
    }
}
