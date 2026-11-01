package dev.t1m3.qplayer.artwork;

import com.google.gson.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Independent Apple editorial-video protocol helpers; no original app code. */
public final class AppleMotionArtwork {
    private AppleMotionArtwork() {}
    public static final class Cover {
        public final String square, tall, preview, album, artist;
        public final double score;
        public Cover(String square, String tall, String preview, String album, String artist, double score) {
            this.square = square; this.tall = tall; this.preview = preview;
            this.album = album; this.artist = artist; this.score = score;
        }
        public String video() { return tall.isEmpty() ? square : tall; }
        public boolean portrait() { return !tall.isEmpty(); }
    }

    public static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[（(\\[【].*?[）)\\]】]", " ")
                .replaceAll("\\b(?:feat\\.?|ft\\.?|featuring)\\s+.*$", " ")
                .replaceAll("\\b(?:deluxe|expanded|remastered|remaster|edition|anniversary)\\b", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static Set<String> words(String s) {
        Set<String> result = new HashSet<>();
        for (String word : s.split(" ")) {
            if (word.matches("[\\p{IsHan}]{2,}")) {
                for (int i = 0; i < word.length() - 1; i++) result.add(word.substring(i, i + 2));
            } else if (!word.isEmpty()) result.add(word);
        }
        return result;
    }

    public static double similarity(String left, String right) {
        return normalizedSimilarity(normalize(left), normalize(right));
    }

    /** Distribution suffixes are not album names; mathematical titles are. */
    public static String normalizeAlbum(String text) {
        String value = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).trim()
                .replaceAll("[（(\\[【][^）)\\]】]*(?:deluxe|expanded|remaster(?:ed)?|anniversary|edition)[^）)\\]】]*[）)\\]】]", " ")
                .replaceAll("\\s*[-–—:]\\s*(?:single|ep)\\s*$", "").trim();
        switch (value) {
            case "÷": return "divide";
            case "×": case "x": return "multiply";
            case "+": return "plus";
            case "=": return "equals";
            case "-": case "−": return "subtract";
            default:
                // Keep live/acoustic/remix qualifiers, unlike artist normalization.
                return value.replaceAll("\\b(?:deluxe|expanded|remastered|remaster|edition|anniversary)\\b", " ")
                        .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
        }
    }
    public static double albumSimilarity(String left, String right) {
        return normalizedSimilarity(normalizeAlbum(left), normalizeAlbum(right));
    }
    private static double normalizedSimilarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        Set<String> x = words(a), y = words(b), intersection = new HashSet<>(x);
        intersection.retainAll(y); x.addAll(y);
        return x.isEmpty() ? 0 : (double) intersection.size() / x.size();
    }

    public static Cover parseSearch(String json, String album, String artist) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray rows = obj(obj(root, "results"), "albums").getAsJsonArray("data");
        if (rows == null) return null;
        Cover best = null;
        for (JsonElement row : rows) {
            if (!row.isJsonObject()) continue;
            JsonObject attrs = obj(row.getAsJsonObject(), "attributes");
            String name = str(attrs, "name"), author = str(attrs, "artistName");
            double albumScore = albumSimilarity(album, name), artistScore = similarity(artist, author);
            // An exact album name alone must never select a different artist.
            if (albumScore < .55 || artistScore < .45) continue;
            double score = .55 * albumScore + .45 * artistScore;
            JsonObject videos = obj(attrs, "editorialVideo");
            JsonObject square = motion(videos, "motionDetailSquare", "motionSquareVideo1x1");
            JsonObject tall = motion(videos, "motionDetailTall", "motionTallVideo3x4");
            String sq = safeVideo(str(square, "video")), tl = safeVideo(str(tall, "video"));
            if (sq.isEmpty() && tl.isEmpty()) continue;
            String preview = str(obj(tl.isEmpty() ? square : tall, "previewFrame"), "url")
                    .replace("{w}", "720").replace("{h}", tl.isEmpty() ? "720" : "960");
            if (!isAppleHttps(preview)) preview = str(obj(attrs, "artwork"), "url")
                    .replace("{w}", "720").replace("{h}", "720");
            if (!isAppleHttps(preview)) preview = "";
            if (best == null || score > best.score) best = new Cover(sq, tl, preview, name, author, score);
        }
        return best;
    }

    /** IDs only from the same localized artist; callers bound detail requests. */
    public static List<String> candidateAlbumIds(String json, String album, String artist, boolean exactAlbum) {
        JsonArray rows = obj(obj(JsonParser.parseString(json).getAsJsonObject(), "results"), "albums").getAsJsonArray("data");
        List<String> ids = new ArrayList<>();
        if (rows == null) return ids;
        for (JsonElement element : rows) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject(), attrs = obj(row, "attributes");
            if (similarity(artist, str(attrs, "artistName")) < .45) continue;
            if (exactAlbum && albumSimilarity(album, str(attrs, "name")) < .55) continue;
            if (!exactAlbum && obj(attrs, "editorialVideo").size() == 0) continue;
            String id = str(row, "id");
            if (id.matches("[0-9]+") && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private static JsonArray detailRows(String json) {
        JsonArray rows = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("data");
        return rows == null ? new JsonArray() : rows;
    }
    private static String asSearch(JsonArray rows) {
        JsonObject albums = new JsonObject(); albums.add("data", rows);
        JsonObject results = new JsonObject(); results.add("albums", albums);
        JsonObject root = new JsonObject(); root.add("results", results); return root.toString();
    }
    public static Cover parseAlbum(String json, String album, String artist) {
        return parseSearch(asSearch(detailRows(json)), album, artist);
    }
    public static final class CatalogAlbum {
        public final String id, name, recording;
        private final double rank;
        CatalogAlbum(String id, String name, double rank) { this(id, name, "", rank); }
        CatalogAlbum(String id, String name, String recording, double rank) {
            this.id = id; this.name = name; this.recording = recording; this.rank = rank;
        }
    }
    /** Public iTunes metadata supplies Apple's own collection names and IDs.
     * Artist aliases are checked against localized catalog details, never trusted here. */
    public static List<CatalogAlbum> itunesAlbums(String json, String album, String title, String artist) {
        JsonArray rows = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("results");
        List<CatalogAlbum> albums = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (rows == null) return albums;
        for (JsonElement element : rows) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            String name = str(row, "collectionName"), id = str(row, "collectionId");
            if (!id.matches("[0-9]+") || name.isEmpty()) continue;
            if (!normalizeAlbum(title).isEmpty()) {
                if (albumSimilarity(title, str(row, "trackName")) != 1) continue;
            } else if (albumSimilarity(album, name) < .55) continue;
            if (seen.add(id)) albums.add(new CatalogAlbum(id, name,
                    2 * albumSimilarity(album, name) + similarity(artist, str(row, "artistName"))));
        }
        albums.sort((a, b) -> Double.compare(b.rank, a.rank));
        return albums;
    }
    /** Apple Music's own song -> album relationships, independent of source album names. */
    public static List<CatalogAlbum> catalogSongAlbums(String json, String album, String title, String artist) {
        JsonArray rows = obj(obj(JsonParser.parseString(json).getAsJsonObject(), "results"), "songs").getAsJsonArray("data");
        List<CatalogAlbum> albums = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (rows == null) return albums;
        for (JsonElement element : rows) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject(), attrs = obj(row, "attributes");
            String recording = str(attrs, "isrc");
            if (recording.isEmpty() || albumSimilarity(title, str(attrs, "name")) != 1 ||
                    similarity(artist, str(attrs, "artistName")) < .45) continue;
            JsonArray parents = obj(obj(row, "relationships"), "albums").getAsJsonArray("data");
            if (parents == null) continue;
            for (JsonElement parent : parents) {
                if (!parent.isJsonObject()) continue;
                JsonObject data = parent.getAsJsonObject();
                String id = str(data, "id"), name = str(obj(data, "attributes"), "name");
                if (name.isEmpty()) name = str(attrs, "albumName");
                if (id.matches("[0-9]+") && !name.isEmpty() && seen.add(id))
                    albums.add(new CatalogAlbum(id, name, recording, albumSimilarity(album, name)));
            }
        }
        albums.sort((a, b) -> Double.compare(b.rank, a.rank));
        return albums;
    }
    /** Recording identities from the matched single, never from unrelated search results. */
    public static Set<String> matchingTrackIsrcs(String json, String album, String title, String artist) {
        Set<String> ids = new HashSet<>();
        for (JsonElement element : detailRows(json)) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject(), attrs = obj(row, "attributes");
            if (albumSimilarity(album, str(attrs, "name")) < .55 || similarity(artist, str(attrs, "artistName")) < .45) continue;
            JsonArray tracks = obj(obj(row, "relationships"), "tracks").getAsJsonArray("data");
            if (tracks == null) continue;
            for (JsonElement track : tracks) {
                if (!track.isJsonObject()) continue;
                JsonObject a = obj(track.getAsJsonObject(), "attributes");
                String isrc = str(a, "isrc");
                if (albumSimilarity(title, str(a, "name")) == 1 && similarity(artist, str(a, "artistName")) >= .45 && !isrc.isEmpty()) ids.add(isrc);
            }
        }
        return ids;
    }
    /** Single -> full album fallback requires the same track AND recording, not just the artist. */
    public static Cover parseContainingAlbum(String json, String title, String artist, Set<String> isrcs) {
        if (isrcs.isEmpty()) return null;
        for (JsonElement element : detailRows(json)) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject(), attrs = obj(row, "attributes");
            JsonArray tracks = obj(obj(row, "relationships"), "tracks").getAsJsonArray("data");
            if (tracks == null || similarity(artist, str(attrs, "artistName")) < .45) continue;
            for (JsonElement track : tracks) {
                if (!track.isJsonObject()) continue;
                JsonObject a = obj(track.getAsJsonObject(), "attributes");
                if (isrcs.contains(str(a, "isrc")) && albumSimilarity(title, str(a, "name")) == 1 && similarity(artist, str(a, "artistName")) >= .45) {
                    JsonArray single = new JsonArray(); single.add(row);
                    Cover cover = parseSearch(asSearch(single), str(attrs, "name"), artist);
                    if (cover != null) return cover;
                }
            }
        }
        return null;
    }

    private static JsonObject motion(JsonObject parent, String preferred, String fallback) {
        JsonObject first = obj(parent, preferred);
        return safeVideo(str(first, "video")).isEmpty() ? obj(parent, fallback) : first;
    }
    private static JsonObject obj(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
    private static String str(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
    private static String safeVideo(String url) { return isAppleHttps(url) ? url : ""; }

    public static boolean isAppleHttps(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null &&
                    (uri.getPort() == -1 || uri.getPort() == 443) && host != null &&
                    (host.equals("music.apple.com") || host.equals("amp-api.music.apple.com") || host.equals("itunes.apple.com") ||
                     host.endsWith(".mzstatic.com") || host.endsWith(".itunes.apple.com"));
        } catch (RuntimeException bad) { return false; }
    }

    /** Only select the web player's currently valid, public browser token. */
    public static String findWebToken(String text, long nowSeconds) {
        Matcher tokens = Pattern.compile("eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}").matcher(text);
        while (tokens.find()) {
            String token = tokens.group();
            if (tokenExpires(token) > nowSeconds + 3600) return token;
        }
        return "";
    }
    public static long tokenExpires(String token) {
        try {
            JsonObject payload = JsonParser.parseString(new String(Base64.getUrlDecoder()
                    .decode(token.split("\\.")[1]), StandardCharsets.UTF_8)).getAsJsonObject();
            return "AMPWebPlay".equals(str(payload, "iss")) ? payload.get("exp").getAsLong() : 0;
        } catch (RuntimeException bad) { return 0; }
    }

    public static List<String> scriptUrls(String html) {
        List<String> urls = new ArrayList<>();
        Matcher scripts = Pattern.compile("(?:src|href)=[\"']([^\"']+\\.js(?:\\?[^\"']*)?)[\"']").matcher(html);
        while (scripts.find() && urls.size() < 16) {
            String url = URI.create("https://music.apple.com/").resolve(scripts.group(1)).toString();
            if (isAppleHttps(url) && !urls.contains(url)) urls.add(url);
        }
        return urls;
    }

    /** Prefer the largest H.264 rendition under 720px; otherwise the smallest H.264. */
    public static String mediaPlaylist(String master, String base, int maxWidth) {
        String[] lines = master.split("\\r?\\n");
        String best = "", smallest = ""; int bestWidth = -1, smallestWidth = Integer.MAX_VALUE;
        for (int i = 0; i + 1 < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("#EXT-X-STREAM-INF:") || !line.contains("avc1")) continue;
            Matcher resolution = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)").matcher(line);
            if (!resolution.find()) continue;
            int width = Integer.parseInt(resolution.group(1));
            String next = lines[i + 1].trim();
            if (next.isEmpty() || next.startsWith("#")) continue;
            String url = URI.create(base).resolve(next).toString();
            if (!isAppleHttps(url)) continue;
            if (width < smallestWidth) { smallestWidth = width; smallest = url; }
            if (width <= maxWidth && width > bestWidth) { bestWidth = width; best = url; }
        }
        if (best.isEmpty()) best = smallest;
        if (best.isEmpty()) throw new IllegalArgumentException("No H.264 cover rendition");
        return best;
    }

    /** Apple uses one complete fragmented MP4 addressed by HLS byte ranges. */
    public static String singleMp4(String playlist, String base) {
        if (!playlist.contains("#EXT-X-ENDLIST") || playlist.contains("#EXT-X-KEY:"))
            throw new IllegalArgumentException("Unsupported encrypted/live cover");
        Matcher map = Pattern.compile("#EXT-X-MAP:.*?URI=\"([^\"]+)\"").matcher(playlist);
        if (!map.find()) throw new IllegalArgumentException("Missing initialization file");
        String file = URI.create(base).resolve(map.group(1)).toString();
        if (!isAppleHttps(file)) throw new IllegalArgumentException("Unexpected cover host");
        for (String line : playlist.split("\\r?\\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#") && !URI.create(base).resolve(line).toString().equals(file))
                throw new IllegalArgumentException("Cover uses multiple segment files");
        }
        return file;
    }

    /** Rebase fragment decode times by track, preserving the fragment spacing. */
    public static void rebaseMp4(byte[] bytes) {
        List<int[]> decodeTimes = new ArrayList<>();
        scanBoxes(bytes, 0, bytes.length, decodeTimes, 0);
        if (bytes.length < 12 || !type(bytes, 4).equals("ftyp") || decodeTimes.isEmpty())
            throw new IllegalArgumentException("Not a fragmented cover MP4");
        Map<Integer, Long> origins = new HashMap<>();
        for (int[] entry : decodeTimes) {
            long value = number(bytes, entry[0], entry[1]);
            Long old = origins.get(entry[2]);
            origins.put(entry[2], old == null ? value : Math.min(old, value));
        }
        for (int[] entry : decodeTimes) {
            long value = number(bytes, entry[0], entry[1]) - origins.get(entry[2]);
            for (int i = entry[1] - 1; i >= 0; i--) { bytes[entry[0] + i] = (byte) value; value >>>= 8; }
        }
    }
    private static void scanBoxes(byte[] data, int start, int end, List<int[]> out, int depth) {
        if (depth > 3) throw new IllegalArgumentException("MP4 nesting");
        for (int p = start; p < end;) {
            if (end - p < 8) throw new IllegalArgumentException("Truncated MP4");
            long length = number(data, p, 4); int header = 8;
            if (length == 1) { if (end - p < 16) throw new IllegalArgumentException("Truncated MP4"); length = number(data, p + 8, 8); header = 16; }
            if (length == 0) length = end - p;
            if (length < header || length > end - p) throw new IllegalArgumentException("Invalid MP4 box");
            int limit = p + (int) length;
            String type = type(data, p + 4);
            if (type.equals("moof")) scanBoxes(data, p + header, limit, out, depth + 1);
            if (type.equals("traf")) {
                List<int[]> times = new ArrayList<>(); int track = -1;
                for (int c = p + header; c < limit;) {
                    if (limit - c < 8) throw new IllegalArgumentException("Truncated fragment");
                    long size = number(data, c, 4);
                    if (size < 8 || size > limit - c) throw new IllegalArgumentException("Invalid fragment");
                    String child = type(data, c + 4);
                    if (child.equals("tfhd")) {
                        if (size < 16) throw new IllegalArgumentException("Invalid track header");
                        track = (int) number(data, c + 12, 4);
                    } else if (child.equals("tfdt")) {
                        if (size < 16) throw new IllegalArgumentException("Invalid decode time");
                        int version = data[c + 8] & 255;
                        if (version > 1 || size < (version == 1 ? 20 : 16)) throw new IllegalArgumentException("Invalid decode time");
                        times.add(new int[] {c + 12, version == 1 ? 8 : 4, 0});
                    }
                    c += (int) size;
                }
                if (track < 0) throw new IllegalArgumentException("Missing track header");
                for (int[] time : times) { time[2] = track; out.add(time); }
            }
            p = limit;
        }
    }
    private static String type(byte[] bytes, int p) { return new String(bytes, p, 4, StandardCharsets.ISO_8859_1); }
    private static long number(byte[] bytes, int p, int n) {
        long result = 0;
        for (int i = 0; i < n; i++) result = (result << 8) | (bytes[p + i] & 255);
        if (result < 0) throw new IllegalArgumentException("MP4 integer overflow");
        return result;
    }
}
