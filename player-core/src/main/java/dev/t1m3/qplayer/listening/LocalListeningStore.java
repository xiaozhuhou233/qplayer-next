package dev.t1m3.qplayer.listening;

import com.google.gson.Gson;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseListening;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.store.StorageFiles;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

/** Local, source-independent ledger. Only heard milliseconds enter the ledger.
 * Monthly atomic files keep checkpoints independent of the size of the lifetime history.
 * All methods run on the controller's dedicated disk lane, never its audio/UI lane. */
public final class LocalListeningStore {
    private static final Gson JSON = new Gson();
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Path directory;
    private boolean loaded;
    private final Map<String, Month> months = new TreeMap<>();
    private final LinkedHashMap<String, Cursor> cursors = new LinkedHashMap<>();
    private Map<String, String> genres = new HashMap<>();
    private final Map<String, Long> genreAttempts = new HashMap<>();
    private final Set<String> dirtyMonths = new HashSet<>();

    private static final class Stat {
        long milliseconds, plays, lastPlayed;
    }
    private static final class Recent {
        String session, key;
        long playedAt;
    }
    private static final class Cursor {
        long heardMs, wallTime;
        Cursor(long heardMs, long wallTime) { this.heardMs = heardMs; this.wallTime = wallTime; }
    }
    private static final class Month {
        int version = 1;
        Map<String, Track> tracks = new HashMap<>();
        Map<String, Map<String, Stat>> days = new TreeMap<>();
        List<Recent> recent = new ArrayList<>();
        Map<String, Cursor> cursors = new LinkedHashMap<>();
    }
    public static final class Snapshot {
        public final long totalSeconds;
        public final List<NeteaseListening.SongPlay> recent;
        public final NeteaseListening.Report report, previous;
        Snapshot(long total, List<NeteaseListening.SongPlay> recent, NeteaseListening.Report report, NeteaseListening.Report previous) {
            totalSeconds = total; this.recent = Collections.unmodifiableList(recent); this.report = report; this.previous = previous;
        }
    }
    public static final class GenreSample {
        public final String key, title, artist;
        GenreSample(String key, Track track) { this.key = key; title = text(track.title); artist = text(track.artist); }
    }
    public LocalListeningStore(Path directory) { this.directory = directory; }

    private void load() throws IOException {
        if (loaded) return;
        Files.createDirectories(directory);
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}\\.json"))::iterator) {
                try {
                    Month month = JSON.fromJson(StorageFiles.readUtf8(file), Month.class);
                    if (month == null || month.version != 1 || month.days == null || month.tracks == null)
                        throw new IOException("无效的本地听歌记录：" + file.getFileName());
                    months.put(file.getFileName().toString().substring(0, 7), month);
                    for (Map.Entry<String, Cursor> entry : month.cursors.entrySet()) {
                        Cursor previous = cursors.get(entry.getKey());
                        if (previous == null || previous.heardMs < entry.getValue().heardMs) cursors.put(entry.getKey(), entry.getValue());
                    }
                } catch (RuntimeException e) { throw new IOException("本地听歌记录读取失败：" + file.getFileName(), e); }
            }
        }
        Path cache = directory.resolve("genres.json");
        if (Files.exists(cache)) {
            try {
                com.google.gson.JsonObject values = com.google.gson.JsonParser.parseString(StorageFiles.readUtf8(cache)).getAsJsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> entry : values.entrySet())
                    if (entry.getValue().isJsonPrimitive()) genres.put(entry.getKey(), entry.getValue().getAsString());
            } catch (RuntimeException ignored) { /* Classification is rebuildable; heard time is not. */ }
        }
        loaded = true;
    }

    /** Cumulative checkpoints are idempotent, including a repeated pause/final checkpoint. */
    public synchronized void checkpoint(String session, Track source, long startedAt, long now, long heardMs) throws IOException {
        if (source == null || session == null || heardMs <= 0) return;
        load();
        Cursor previous = cursors.get(session);
        if (previous != null && heardMs <= previous.heardMs) { flush(); return; }
        long delta = heardMs - (previous == null ? 0 : previous.heardMs);
        long from = now - delta;
        if (previous != null) from = Math.max(from, previous.wallTime);
        long span = Math.max(1, now - from);
        long remaining = delta;
        String key = key(source);
        Track track = metadata(source);
        Set<String> dirty = dirtyMonths;
        boolean first = previous == null;
        while (from < now) {
            LocalDate date = Instant.ofEpochMilli(from).atZone(ZONE).toLocalDate();
            long boundary = date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
            long until = Math.min(now, boundary);
            long amount = until == now ? remaining : Math.min(remaining, Math.round(delta * ((until - from) / (double) span)));
            String name = date.toString().substring(0, 7);
            Month month = months.computeIfAbsent(name, unused -> new Month());
            month.tracks.put(key, track);
            Stat value = month.days.computeIfAbsent(date.toString(), unused -> new HashMap<>()).computeIfAbsent(key, unused -> new Stat());
            value.milliseconds += amount;
            value.lastPlayed = Math.max(value.lastPlayed, now);
            if (first) {
                value.plays++;
                Recent row = new Recent(); row.session = session; row.key = key; row.playedAt = startedAt > 0 ? startedAt : from;
                month.recent.add(row);
                month.recent.sort((a, b) -> Long.compare(b.playedAt, a.playedAt));
                if (month.recent.size() > 300) month.recent = new ArrayList<>(month.recent.subList(0, 300));
                first = false;
            }
            dirty.add(name); remaining -= amount; from = until;
        }
        // A wall-clock correction must not discard real monotonic heard time.
        if (dirty.isEmpty()) {
            String day = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate().toString();
            String name = day.substring(0, 7);
            Month month = months.computeIfAbsent(name, unused -> new Month()); month.tracks.put(key, track);
            Stat value = month.days.computeIfAbsent(day, unused -> new HashMap<>()).computeIfAbsent(key, unused -> new Stat());
            value.milliseconds += delta; value.lastPlayed = now;
            dirty.add(name);
        }
        cursors.put(session, new Cursor(heardMs, now));
        // Only recent cursor identities are needed; totals and daily ledgers are never truncated.
        while (cursors.size() > 128) cursors.remove(cursors.keySet().iterator().next());
        flush();
    }

    private void flush() throws IOException {
        for (String name : new ArrayList<>(dirtyMonths)) {
            Month month = months.get(name); month.cursors = new LinkedHashMap<>(cursors);
            StorageFiles.writeUtf8Atomic(directory.resolve(name + ".json"), JSON.toJson(month));
            dirtyMonths.remove(name);
        }
    }

    public static NeteaseListening.Period period(String type, int offset, LocalDate today) {
        if (!"year".equals(type)) return NeteaseListening.period(type, offset, today);
        if (offset > 0) throw new IllegalArgumentException("Future listening period");
        LocalDate start = LocalDate.of(today.getYear() + offset, 1, 1);
        return new NeteaseListening.Period(start, start.withMonth(12).withDayOfMonth(31), offset == 0);
    }

    public synchronized Snapshot snapshot(String type, int offset, LocalDate today) throws IOException {
        load(); flush();
        long total = 0;
        List<NeteaseListening.SongPlay> recent = new ArrayList<>();
        for (Month month : months.values()) {
            for (Map<String, Stat> day : month.days.values()) for (Stat value : day.values()) total += value.milliseconds;
            for (Recent row : month.recent) {
                Track track = month.tracks.get(row.key);
                if (track != null) recent.add(songPlay(track, 1, row.playedAt));
            }
        }
        recent.sort((a, b) -> Long.compare(b.playedAt, a.playedAt));
        if (recent.size() > 300) recent = new ArrayList<>(recent.subList(0, 300));
        return new Snapshot(total / 1000, recent, report(period(type, offset, today), today), report(period(type, offset - 1, today), today));
    }

    private NeteaseListening.Report report(NeteaseListening.Period period, LocalDate today) {
        Map<String, Stat> totals = new HashMap<>(); Map<String, Track> tracks = new HashMap<>();
        List<NeteaseListening.Day> days = new ArrayList<>(); long total = 0;
        LocalDate end = LocalDate.parse(period.endDate); if (end.isAfter(today)) end = today;
        for (LocalDate day = LocalDate.parse(period.startDate); !day.isAfter(end); day = day.plusDays(1)) {
            Month month = months.get(day.toString().substring(0, 7)); long daily = 0;
            Map<String, Stat> rows = month == null ? null : month.days.get(day.toString());
            if (rows != null) for (Map.Entry<String, Stat> row : rows.entrySet()) {
                Stat sum = totals.computeIfAbsent(row.getKey(), unused -> new Stat()), value = row.getValue();
                sum.milliseconds += value.milliseconds; sum.plays += value.plays; sum.lastPlayed = Math.max(sum.lastPlayed, value.lastPlayed);
                daily += value.milliseconds; tracks.put(row.getKey(), month.tracks.get(row.getKey()));
            }
            total += daily; days.add(new NeteaseListening.Day(day.toString(), daily / 1000));
        }
        List<Map.Entry<String, Stat>> ranked = new ArrayList<>(totals.entrySet());
        ranked.sort((a, b) -> { int n = Long.compare(b.getValue().plays, a.getValue().plays);
            if (n == 0) n = Long.compare(b.getValue().milliseconds, a.getValue().milliseconds); return n != 0 ? n : a.getKey().compareTo(b.getKey()); });
        List<NeteaseListening.SongPlay> songs = new ArrayList<>();
        Map<String, NeteaseListening.ArtistPlay> artists = new HashMap<>(); Map<String, Long> weights = new HashMap<>();
        for (Map.Entry<String, Stat> entry : ranked) {
            Track track = tracks.get(entry.getKey()); if (track == null) continue;
            Stat value = entry.getValue(); songs.add(songPlay(track, value.plays, value.lastPlayed));
            String[] names = text(track.artistNamesCsv).isEmpty() ? text(track.artist).split(" / ") : track.artistNamesCsv.split("\u0001");
            Set<String> unique = new HashSet<>();
            for (String name : names) {
                name = name.trim(); if (name.isEmpty() || !unique.add(name)) continue;
                NeteaseListening.ArtistPlay previous = artists.get(name);
                artists.put(name, new NeteaseListening.ArtistPlay(track.artistId, name, cover(track), value.plays + (previous == null ? 0 : previous.count)));
            }
            String genre = text(track.genre).trim(); if (genre.isEmpty()) genre = genres.getOrDefault(entry.getKey(), "未分类");
            weights.put(genre, weights.getOrDefault(genre, 0L) + value.milliseconds);
        }
        List<NeteaseListening.ArtistPlay> artistList = new ArrayList<>(artists.values());
        artistList.sort((a, b) -> { int n = Long.compare(b.count, a.count); return n != 0 ? n : a.name.compareTo(b.name); });
        List<Map.Entry<String, Long>> genreList = new ArrayList<>(weights.entrySet()); genreList.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<NeteaseListening.GenreShare> shares = new ArrayList<>();
        for (Map.Entry<String, Long> entry : genreList) if (total > 0) shares.add(new NeteaseListening.GenreShare(entry.getKey(), 100d * entry.getValue() / total));
        return new NeteaseListening.Report(total / 1000, days, period.startDate, period.endDate, songs, artistList, shares);
    }

    /** A small batch of uncategorized, genuinely heard songs. Cache hits never call AI again. */
    public synchronized List<GenreSample> genreSamples(String type, int offset, LocalDate today, long now) throws IOException {
        load(); List<GenreSample> result = new ArrayList<>();
        for (NeteaseListening.SongPlay row : report(period(type, offset, today), today).songs) {
            Track track = row.localTrack; String key = key(track);
            if (!text(track.genre).isEmpty() || genres.containsKey(key) || now - genreAttempts.getOrDefault(key, 0L) < 600_000L) continue;
            genreAttempts.put(key, now); result.add(new GenreSample(key, track));
            if (result.size() == 24) break;
        }
        return result;
    }
    public synchronized void saveGenres(Map<String, String> labels, Set<String> requested) throws IOException {
        load();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            String label = text(entry.getValue()).trim();
            if (requested.contains(entry.getKey()) && !label.isEmpty() && label.length() <= 40 && !label.equals("未知") && !label.equals("未分类")) genres.put(entry.getKey(), label);
        }
        StorageFiles.writeUtf8Atomic(directory.resolve("genres.json"), JSON.toJson(genres));
    }

    public static String key(Track track) {
        switch (track.source) {
            case NETEASE: return "netease:" + track.neteaseId;
            case BILI: return "bili:" + text(track.biliBvid) + ":" + track.biliCid;
            case CUSTOM_API: return "custom:" + text(track.customId);
            default: return "local:" + (text(track.contentUri).isEmpty() ? text(track.filePath) : track.contentUri);
        }
    }
    /** Store only replayable metadata, never audio bytes, cookies, API keys or expiring CDN URLs. */
    public static Track metadata(Track source) {
        Track t = new Track(); t.source = source.source; t.filePath = source.filePath; t.contentUri = source.contentUri;
        t.biliBvid = source.biliBvid; t.biliCid = source.biliCid; t.neteaseId = source.neteaseId; t.customId = source.customId;
        t.title = source.title; t.artist = source.artist; t.artistId = source.artistId;
        t.artistIdsCsv = source.artistIdsCsv; t.artistNamesCsv = source.artistNamesCsv;
        t.album = source.album; t.albumId = source.albumId; t.genre = source.genre; t.durationMs = source.durationMs;
        t.coverUrl = source.coverUrl; t.coverThumbPath = source.coverThumbPath; t.coverLocalPath = source.coverLocalPath;
        return t;
    }
    private static NeteaseListening.SongPlay songPlay(Track track, long count, long at) {
        NeteaseSong song = new NeteaseSong(); song.id = track.source == Track.Source.NETEASE ? track.neteaseId : 0;
        if (song.id <= 0) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(key(track).getBytes(StandardCharsets.UTF_8));
                long id = 0; for (int i = 0; i < 8; i++) id = (id << 8) | (hash[i] & 255);
                song.id = -(id & Long.MAX_VALUE) - 1;
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        song.name = track.title; song.artist = track.artist; song.artistId = track.artistId; song.artistIdsCsv = track.artistIdsCsv;
        song.artistNamesCsv = track.artistNamesCsv; song.album = track.album; song.albumId = track.albumId;
        song.durationMs = track.durationMs; song.coverUrl = cover(track); song.coverThumbPath = text(track.coverThumbPath).isEmpty() ? song.coverUrl : track.coverThumbPath;
        return new NeteaseListening.SongPlay(song, count, at, "QPlayer · " + (track.source == Track.Source.BILI ? "B站" : track.source == Track.Source.LOCAL ? "本地" : track.source == Track.Source.NETEASE ? "网易云" : "自定义音源"), metadata(track));
    }
    private static String cover(Track track) { return text(track.coverLocalPath).isEmpty() ? text(track.coverUrl) : track.coverLocalPath; }
    private static String text(String value) { return value == null ? "" : value; }
}
