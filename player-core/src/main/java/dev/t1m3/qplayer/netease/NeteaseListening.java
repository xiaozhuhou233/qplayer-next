package dev.t1m3.qplayer.netease;

import com.google.gson.*;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import java.io.IOException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Cloud listening statistics. Unknown is -1, distinct from a reported zero.
 * totalDuration is seconds; playDuration and durationDetails.duration are minutes.
 * No statistic is inferred from track lengths or the truncated recent-song list. */
public final class NeteaseListening {
    private NeteaseListening() {}

    public static final class Period {
        public final String startDate, endDate;
        /** Zero means the current realtime period; history uses the last day's midnight. */
        public final long endTime;
        public Period(LocalDate start, LocalDate end, boolean current) {
            startDate = start.toString(); endDate = end.toString();
            endTime = current ? 0 : end.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        }
    }

    public static Period period(String type, int offset, LocalDate today) {
        if (offset > 0) throw new IllegalArgumentException("Future listening period");
        LocalDate start, end;
        switch (type) {
            case "week":
                // NetEase reports run Sunday through Saturday, including at a year boundary.
                start = today.minusDays(today.getDayOfWeek().getValue() % 7).plusWeeks(offset);
                end = start.plusDays(6);
                break;
            case "month":
                start = today.withDayOfMonth(1).plusMonths(offset);
                end = start.withDayOfMonth(start.lengthOfMonth());
                break;
            case "year":
                // Annual reports are available only for complete years.
                start = LocalDate.of(today.getYear() - 1 + offset, 1, 1);
                end = start.withMonth(12).withDayOfMonth(31);
                break;
            default: throw new IllegalArgumentException("Invalid listening period");
        }
        return new Period(start, end, offset == 0 && !"year".equals(type));
    }

    public static final class Day {
        public final String date;
        public final long seconds;
        public Day(String date, long seconds) { this.date = date; this.seconds = seconds; }
    }
    public static final class Report {
        public final long seconds;
        public final List<Day> days;
        public final String startDate, endDate;
        public final List<SongPlay> songs;
        public final List<ArtistPlay> artists;
        public final List<GenreShare> genres;
        public Report(long seconds, List<Day> days, String startDate, String endDate,
                List<SongPlay> songs, List<ArtistPlay> artists, List<GenreShare> genres) {
            this.seconds = seconds; this.days = Collections.unmodifiableList(days);
            this.startDate = startDate; this.endDate = endDate;
            this.songs = Collections.unmodifiableList(songs);
            this.artists = Collections.unmodifiableList(artists);
            this.genres = Collections.unmodifiableList(genres);
        }
    }
    public static final class ArtistPlay {
        public final long id, count;
        public final String name, cover;
        public ArtistPlay(long id, String name, String cover, long count) {
            this.id = id; this.name = name; this.cover = cover; this.count = count;
        }
    }
    public static final class GenreShare {
        public final String name;
        public final double percent;
        public GenreShare(String name, double percent) { this.name = name; this.percent = percent; }
    }
    public static final class SongPlay {
        public final NeteaseSong song;
        public final long count;
        public final long playedAt;
        public final String device;
        public final dev.t1m3.qplayer.model.Track localTrack;
        public SongPlay(NeteaseSong song, long count, long playedAt, String device) {
            this(song, count, playedAt, device, null);
        }
        public SongPlay(NeteaseSong song, long count, long playedAt, String device, dev.t1m3.qplayer.model.Track localTrack) {
            this.song = song; this.count = count; this.playedAt = playedAt; this.device = device;
            this.localTrack = localTrack;
        }
    }

    static JsonObject data(JsonObject root) throws IOException {
        long code = number(root, "code");
        if (code != -1 && code != 200) {
            if (code == 301) throw new IOException("登录已失效，请重新登录网易云音乐");
            String message = text(root, "message");
            if (message.isEmpty()) message = text(root, "msg");
            throw new IOException("网易云暂未提供此项数据（" + code + "）"
                    + (message.isEmpty() ? "" : "：" + message));
        }
        if (!root.has("data") || !root.get("data").isJsonObject())
            throw new IOException("网易云暂未返回统计数据，可能受账号权限限制");
        return root.getAsJsonObject("data");
    }

    public static long parseTotal(JsonObject root) throws IOException {
        long seconds = number(data(root), "totalDuration");
        if (seconds < 0) throw new IOException("网易云未返回总收听时长，可能需要会员权限");
        return seconds;
    }

    public static Report parseReport(JsonObject root) throws IOException {
        JsonObject data = data(root);
        JsonObject block = object(data, "listenTimeDistributionBlock");
        long seconds = block == null ? -1 : minutes(block, "playDuration");
        TreeMap<String, Long> days = new TreeMap<>();
        JsonArray rows = block == null ? null : array(block, "durationDetails");
        int year = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).get(Calendar.YEAR);
        String start = date(data.get("startTime"), year);
        if (!start.isEmpty()) year = Integer.parseInt(start.substring(0, 4));
        String end = date(data.get("endTime"), year);
        if (rows != null) for (JsonElement el : rows) {
            if (!el.isJsonObject()) continue;
            JsonObject row = el.getAsJsonObject();
            String date = date(row.get("period"), year);
            if (!start.isEmpty() && !end.isEmpty() && date.compareTo(start) < 0
                    && !end.substring(0, 4).equals(start.substring(0, 4))) {
                date = date(row.get("period"), Integer.parseInt(end.substring(0, 4)));
            }
            long value = minutes(row, "duration");
            if (date.isEmpty() || value < 0) continue;
            // Duplicate date rows are not additional listening sessions.
            days.put(date, Math.max(days.containsKey(date) ? days.get(date) : 0, value));
        }
        List<Day> result = new ArrayList<>();
        for (Map.Entry<String, Long> day : days.entrySet()) result.add(new Day(day.getKey(), day.getValue()));
        List<SongPlay> songs = new ArrayList<>();
        JsonArray sections = sections(data, "topSongBlock");
        if (sections != null) {
            JsonObject rankData = new JsonObject(); rankData.add("songItems", sections);
            JsonObject rank = new JsonObject(); rank.add("data", rankData);
            songs.addAll(parseRank(rank));
        }
        List<ArtistPlay> artists = new ArrayList<>();
        JsonArray artistRows = sections(data, "topArtistBlock");
        if (artistRows != null) for (JsonElement el : artistRows) {
            if (!el.isJsonObject()) continue;
            JsonObject row = el.getAsJsonObject();
            String name = text(row, "artistName");
            if (name.isEmpty()) name = text(row, "name");
            if (name.isEmpty()) continue;
            long id = number(row, "artistId");
            if (id < 0) id = number(row, "id");
            artists.add(new ArtistPlay(id, name, text(row, "picUrl"), number(row, "playCount")));
        }
        // Optional blocks differ across report versions. Read only named blocks;
        // never recursively reinterpret a song's genre or count as a period total.
        List<GenreShare> genres = new ArrayList<>();
        JsonArray genreRows = sections(data, "musicStyleBlock");
        if (genreRows == null) genreRows = sections(data, "topStyleBlock");
        if (genreRows == null) genreRows = sections(data, "styleDistributionBlock");
        if (genreRows != null) for (JsonElement el : genreRows) {
            if (!el.isJsonObject()) continue;
            JsonObject row = el.getAsJsonObject();
            String name = text(row, "styleName");
            if (name.isEmpty()) name = text(row, "name");
            double percent = percentage(row);
            if (!name.isEmpty()) genres.add(new GenreShare(name, percent));
        }
        if (block == null && sections == null && artistRows == null && genreRows == null)
            throw new IOException("本期暂无听歌报告");
        return new Report(seconds, result, start, end, songs, artists, genres);
    }

    public static List<SongPlay> parseRank(JsonObject root) throws IOException {
        JsonArray rows = array(data(root), "songItems");
        if (rows == null) throw new IOException("网易云暂未返回歌曲排行");
        List<SongPlay> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (JsonElement el : rows) {
            if (!el.isJsonObject()) continue;
            JsonObject row = el.getAsJsonObject();
            long id = number(row, "songId");
            String name = text(row, "songName");
            if (id <= 0) id = number(row, "id");
            if (name.isEmpty()) name = text(row, "name");
            if (id <= 0 || name.isEmpty() || !seen.add(id)) continue;
            NeteaseSong song = new NeteaseSong();
            song.id = id; song.name = name; song.album = text(row, "albumName");
            song.coverUrl = text(row, "picUrl"); song.coverThumbPath = NeteaseClient.thumbUrl(song.coverUrl);
            JsonArray artists = array(row, "artists");
            List<String> names = new ArrayList<>();
            if (artists != null) for (JsonElement artist : artists) {
                String artistName = artist.isJsonObject() ? text(artist.getAsJsonObject(), "name")
                        : artist.isJsonPrimitive() ? artist.getAsString() : "";
                if (!artistName.isEmpty()) names.add(artistName);
            }
            song.artist = String.join(" / ", names);
            result.add(new SongPlay(song, number(row, "playCount"), -1, ""));
        }
        return Collections.unmodifiableList(result);
    }

    public static List<SongPlay> parseRecent(JsonObject root) throws IOException {
        JsonArray rows = array(data(root), "list");
        if (rows == null) throw new IOException("网易云暂未返回最近播放记录");
        List<SongPlay> result = new ArrayList<>();
        for (JsonElement el : rows) {
            if (!el.isJsonObject()) continue;
            JsonObject row = el.getAsJsonObject();
            if (!"SONG".equals(text(row, "resourceType"))) continue;
            JsonObject rawSong = object(row, "data");
            if (rawSong == null) continue;
            try {
                NeteaseSong song = NeteaseClient.parseSong(rawSong);
                if (song.id <= 0 || song.name == null) continue;
                JsonObject terminal = object(row, "multiTerminalInfo");
                String device = terminal == null ? "" : text(terminal, "name");
                if (device.isEmpty() && terminal != null) device = text(terminal, "deviceName");
                result.add(new SongPlay(song, -1, number(row, "playTime"), device));
            } catch (RuntimeException ignored) { /* A malformed row must not hide the history. */ }
        }
        result.sort((a, b) -> Long.compare(b.playedAt, a.playedAt));
        return Collections.unmodifiableList(result);
    }

    private static long minutes(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            if (value == null || !value.isJsonPrimitive()) return -1;
            double minutes = value.getAsDouble();
            return Double.isFinite(minutes) && minutes >= 0 && minutes <= Long.MAX_VALUE / 60d
                    ? Math.round(minutes * 60d) : -1;
        } catch (RuntimeException ignored) { return -1; }
    }
    private static JsonArray sections(JsonObject data, String name) {
        JsonObject block = object(data, name);
        if (block == null) return null;
        JsonArray sections = array(block, "sections");
        return sections != null ? sections : array(block, "list");
    }
    private static double percentage(JsonObject row) {
        String value = text(row, "percentage");
        if (value.isEmpty()) value = text(row, "percent");
        boolean ratio = false;
        if (value.isEmpty()) { value = text(row, "ratio"); ratio = true; }
        try {
            boolean hasUnit = value.endsWith("%");
            double number = Double.parseDouble(value.replace("%", ""));
            if (ratio && !hasUnit) number *= 100;
            return Double.isFinite(number) && number >= 0 && number <= 100 ? number : -1;
        } catch (RuntimeException ignored) { return -1; }
    }
    static long number(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            if (value == null || !value.isJsonPrimitive()) return -1;
            double number = value.getAsDouble();
            return Double.isFinite(number) && number >= 0 && number <= Long.MAX_VALUE ? (long) number : -1;
        } catch (RuntimeException ignored) { return -1; }
    }
    private static String text(JsonObject object, String key) {
        JsonElement el = object.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
    }
    private static JsonObject object(JsonObject parent, String key) {
        JsonElement el = parent.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }
    private static JsonArray array(JsonObject parent, String key) {
        JsonElement el = parent.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }
    private static String date(JsonElement element, int year) {
        if (element == null || !element.isJsonPrimitive()) return "";
        String raw = element.getAsString().trim();
        if (raw.matches("[0-9]{10}|[0-9]{13}")) {
            try {
                long value = Long.parseLong(raw);
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
                format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
                return format.format(new Date(raw.length() == 10 ? value * 1000 : value));
            } catch (RuntimeException ignored) { return ""; }
        }
        raw = raw.replace('.', '-').replace('/', '-').replace('年', '-').replace('月', '-').replace("日", "");
        String pattern;
        if (raw.matches("[0-9]{8}")) pattern = "yyyyMMdd";
        else if (raw.matches("[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}")) pattern = "yyyy-M-d";
        else if (raw.matches("[0-9]{1,2}-[0-9]{1,2}")) { raw = year + "-" + raw; pattern = "yyyy-M-d"; }
        else return "";
        SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.ROOT);
        parser.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date parsed = parser.parse(raw, position);
        return parsed != null && position.getIndex() == raw.length()
                ? new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(parsed) : "";
    }
}
