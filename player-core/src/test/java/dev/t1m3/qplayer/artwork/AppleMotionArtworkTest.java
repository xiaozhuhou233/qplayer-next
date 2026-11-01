package dev.t1m3.qplayer.artwork;

import com.google.gson.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppleMotionArtworkTest {
    private static final String HOST = "https://mvod.itunes.apple.com/cover/";
    private JsonObject album(String name, String artist) {
        JsonObject attrs = new JsonObject();
        attrs.addProperty("name", name); attrs.addProperty("artistName", artist);
        JsonObject video = new JsonObject(); video.addProperty("video", HOST + "master.m3u8");
        JsonObject preview = new JsonObject(); preview.addProperty("url", "https://is1-ssl.mzstatic.com/cover/{w}x{h}bb.jpg");
        video.add("previewFrame", preview);
        JsonObject editorial = new JsonObject(); editorial.add("motionTallVideo3x4", video);
        attrs.add("editorialVideo", editorial);
        JsonObject row = new JsonObject(); row.add("attributes", attrs); return row;
    }
    private String search(JsonObject... rows) {
        JsonArray data = new JsonArray(); for (JsonObject row : rows) data.add(row);
        JsonObject albums = new JsonObject(); albums.add("data", data);
        JsonObject results = new JsonObject(); results.add("albums", albums);
        JsonObject root = new JsonObject(); root.add("results", results); return root.toString();
    }
    @Test public void matchesAlbumAndArtistAndSupportsFallbackMotionKeys() {
        AppleMotionArtwork.Cover cover = AppleMotionArtwork.parseSearch(search(
                album("After Hours", "Another Artist"), album("After Hours (Deluxe)", "The Weeknd")),
                "After Hours", "The Weeknd");
        assertNotNull(cover); assertTrue(cover.portrait()); assertEquals("The Weeknd", cover.artist);
        assertEquals(HOST + "master.m3u8", cover.video());
        assertTrue(cover.preview.endsWith("720x960bb.jpg"));
    }
    @Test public void neverTreatsAnExactAlbumNameAsSufficientForADifferentArtist() {
        assertNull(AppleMotionArtwork.parseSearch(search(album("After Hours", "Other")), "After Hours", "The Weeknd"));
        assertNull(AppleMotionArtwork.parseSearch("{}", "After Hours", "The Weeknd"));
        JsonObject noMotion = album("After Hours", "The Weeknd");
        noMotion.getAsJsonObject("attributes").remove("editorialVideo");
        assertNull(AppleMotionArtwork.parseSearch(search(noMotion), "After Hours", "The Weeknd"));
    }
    @Test public void keepsMeaningfulVersionNamesAndNormalizesCjk() {
        assertTrue(AppleMotionArtwork.similarity("范特西", "范特西 (Remastered)") > .99);
        assertTrue(AppleMotionArtwork.similarity("Live", "Live at Wembley") < .55);
        assertEquals("1989", AppleMotionArtwork.normalize("１９８９ (Deluxe)"));
    }
    @Test public void mathematicalAlbumNamesSurviveAndMatchTheirWordNames() {
        assertNotNull(AppleMotionArtwork.parseSearch(search(album("÷ (Deluxe)", "Ed Sheeran")), "Divide", "Ed Sheeran"));
        assertNotNull(AppleMotionArtwork.parseSearch(search(album("÷", "Ed Sheeran")), "÷", "Ed Sheeran"));
        assertEquals(1, AppleMotionArtwork.albumSimilarity("Multiply", "x (Deluxe Edition)"), 0);
        assertEquals(1, AppleMotionArtwork.albumSimilarity("Subtract", "- (Deluxe)"), 0);
        assertEquals(1, AppleMotionArtwork.albumSimilarity("Plus", "+ (Deluxe Version)"), 0);
        assertEquals(1, AppleMotionArtwork.albumSimilarity("Equals", "="), 0);
        assertNull(AppleMotionArtwork.parseSearch(search(album("÷ (Deluxe)", "Wrong Artist")), "Divide", "Ed Sheeran"));
        assertTrue(AppleMotionArtwork.albumSimilarity("÷", "+-=÷× (Tour Collection)") < .55);
    }
    @Test public void distributionSuffixIsIgnoredButMusicalVersionsAreNot() {
        assertNotNull(AppleMotionArtwork.parseSearch(search(album("Deadman - Single", "蔡徐坤")), "Deadman", "蔡徐坤"));
        assertEquals(1, AppleMotionArtwork.albumSimilarity("Deadman", "Deadman - Single"), 0);
        assertEquals(1, AppleMotionArtwork.albumSimilarity("Example", "Example - EP"), 0);
        assertTrue(AppleMotionArtwork.albumSimilarity("Deadman", "Deadman (Live)") < .55);
        assertTrue(AppleMotionArtwork.albumSimilarity("Deadman", "Deadman (Remix)") < .55);
    }
    private String detail(JsonObject row, String title, String artist, String isrc) {
        JsonObject attributes = new JsonObject();
        attributes.addProperty("name", title); attributes.addProperty("artistName", artist); attributes.addProperty("isrc", isrc);
        JsonObject track = new JsonObject(); track.add("attributes", attributes);
        JsonArray tracks = new JsonArray(); tracks.add(track);
        JsonObject data = new JsonObject(); data.add("data", tracks);
        JsonObject relationships = new JsonObject(); relationships.add("tracks", data); row.add("relationships", relationships);
        JsonArray rows = new JsonArray(); rows.add(row); JsonObject root = new JsonObject(); root.add("data", rows);
        return root.toString();
    }
    @Test public void resolvesOnlyAVerifiedRecordingOnTheParentAlbum() {
        // Apple catalog: single 1812117264 -> KUN 1861576981; both carry this ISRC.
        String single = detail(album("Deadman - Single", "蔡徐坤"), "Deadman", "蔡徐坤", "QM6P42575146");
        java.util.Set<String> recordings = AppleMotionArtwork.matchingTrackIsrcs(single, "Deadman", "Deadman", "蔡徐坤");
        assertEquals(Collections.singleton("QM6P42575146"), recordings);
        String parent = detail(album("KUN", "蔡徐坤"), "Deadman", "蔡徐坤", "QM6P42575146");
        assertNull(AppleMotionArtwork.parseAlbum(parent, "Deadman", "蔡徐坤"));
        assertEquals("KUN", AppleMotionArtwork.parseContainingAlbum(parent, "Deadman", "蔡徐坤", recordings).album);
        assertNull(AppleMotionArtwork.parseContainingAlbum(parent, "Another Song", "蔡徐坤", recordings));
        assertNull(AppleMotionArtwork.parseContainingAlbum(parent, "Deadman", "Another Artist", recordings));
        assertNull(AppleMotionArtwork.parseContainingAlbum(parent, "Deadman", "蔡徐坤", Collections.singleton("DIFFERENT")));
        assertNull(AppleMotionArtwork.parseContainingAlbum(parent, "Deadman", "蔡徐坤", Collections.emptySet()));
        assertTrue(AppleMotionArtwork.matchingTrackIsrcs(single, "Another Album", "Deadman", "蔡徐坤").isEmpty());
    }
    @Test public void detailEnrichmentOnlyUsesMatchingAlbumAndArtistIds() {
        JsonObject right = album("Deadman - Single", "蔡徐坤"); right.addProperty("id", "1812117264");
        JsonObject wrong = album("Deadman", "Another Artist"); wrong.addProperty("id", "99");
        JsonObject parent = album("KUN", "蔡徐坤"); parent.addProperty("id", "1861576981");
        assertEquals(Collections.singletonList("1812117264"), AppleMotionArtwork.candidateAlbumIds(search(wrong, right, parent), "Deadman", "蔡徐坤", true));
        assertFalse(AppleMotionArtwork.candidateAlbumIds(search(wrong, right, parent), "Deadman", "蔡徐坤", false).contains("99"));
    }
    @Test public void mapsPublicAppleSongMetadataToOfficialAlbumsWithoutAcceptingLiveVersions() {
        String json = "{\"results\":[" +
            "{\"collectionId\":1861576981,\"collectionName\":\"KUN\",\"artistName\":\"KUN\",\"trackName\":\"Deadman\"}," +
            "{\"collectionId\":1812117264,\"collectionName\":\"Deadman - Single\",\"artistName\":\"KUN\",\"trackName\":\"Deadman\"}," +
            "{\"collectionId\":6771926310,\"collectionName\":\"Live\",\"artistName\":\"KUN\",\"trackName\":\"Deadman (Live at Apple Music Radio)\"}," +
            "{\"collectionId\":\"../bad\",\"collectionName\":\"Deadman\",\"trackName\":\"Deadman\"}]}";
        java.util.List<AppleMotionArtwork.CatalogAlbum> mapped = AppleMotionArtwork.itunesAlbums(json, "Deadman", "Deadman", "蔡徐坤");
        assertEquals(2, mapped.size()); assertEquals("1812117264", mapped.get(0).id);
        assertEquals("KUN", mapped.get(1).name);
        assertTrue(AppleMotionArtwork.isAppleHttps("https://itunes.apple.com/search?term=Deadman"));
        assertFalse(AppleMotionArtwork.isAppleHttps("https://itunes.apple.com.evil.test/search"));
    }
    @Test public void mapsPublicAppleAlbumMetadataIncludingSymbolTitles() {
        String json = "{\"results\":[{\"collectionId\":1193701079,\"collectionName\":\"÷ (Deluxe)\",\"artistName\":\"Ed Sheeran\"}," +
            "{\"collectionId\":858518077,\"collectionName\":\"x\",\"artistName\":\"Ed Sheeran\"}]}";
        java.util.List<AppleMotionArtwork.CatalogAlbum> mapped = AppleMotionArtwork.itunesAlbums(json, "Divide", "", "Ed Sheeran");
        assertEquals(1, mapped.size()); assertEquals("1193701079", mapped.get(0).id);
        assertEquals("÷ (Deluxe)", mapped.get(0).name);
    }
    private JsonObject song(String title, String artist, String isrc, String albumId, String albumName) {
        JsonObject attributes = new JsonObject(); attributes.addProperty("name", title);
        attributes.addProperty("artistName", artist); attributes.addProperty("isrc", isrc);
        attributes.addProperty("albumName", albumName);
        JsonObject parent = new JsonObject(); parent.addProperty("id", albumId);
        JsonObject parentAttributes = new JsonObject(); parentAttributes.addProperty("name", albumName);
        parent.add("attributes", parentAttributes); JsonArray parents = new JsonArray(); parents.add(parent);
        JsonObject albums = new JsonObject(); albums.add("data", parents);
        JsonObject relationships = new JsonObject(); relationships.add("albums", albums);
        JsonObject row = new JsonObject(); row.add("attributes", attributes); row.add("relationships", relationships);
        return row;
    }
    private String songSearch(JsonObject... rows) {
        JsonArray data = new JsonArray(); for (JsonObject row : rows) data.add(row);
        JsonObject songs = new JsonObject(); songs.add("data", data);
        JsonObject results = new JsonObject(); results.add("songs", songs);
        JsonObject root = new JsonObject(); root.add("results", results); return root.toString();
    }
    @Test public void findsActualAppleAlbumWhenSourceAlbumIsADifferentSingleOrMissing() {
        String json = songSearch(song("Deadman", "蔡徐坤", "QM6P42575146", "1861576981", "KUN"));
        for (String sourceAlbum : new String[] {"网易云独立单曲", ""}) {
            java.util.List<AppleMotionArtwork.CatalogAlbum> mapped = AppleMotionArtwork.catalogSongAlbums(json, sourceAlbum, "Deadman", "蔡徐坤");
            assertEquals(1, mapped.size()); assertEquals("KUN", mapped.get(0).name);
            assertEquals("1861576981", mapped.get(0).id);
            String parent = detail(album("KUN", "蔡徐坤"), "Deadman", "蔡徐坤", "QM6P42575146");
            assertNotNull(AppleMotionArtwork.parseContainingAlbum(parent, "Deadman", "蔡徐坤", Collections.singleton(mapped.get(0).recording)));
        }
    }
    @Test public void rejectsUnverifiedSongsAndPreservesVersionIdentityWhenMappingAlbums() {
        String json = songSearch(
            song("Deadman", "Wrong Artist", "QM6P42575146", "1", "Other"),
            song("Deadman (Live)", "蔡徐坤", "LIVE", "2", "Live"),
            song("Deadman", "蔡徐坤", "", "3", "Unknown Recording"),
            song("Deadman", "蔡徐坤", "QM6P42575146", "../bad", "Invalid"));
        assertTrue(AppleMotionArtwork.catalogSongAlbums(json, "Anything", "Deadman", "蔡徐坤").isEmpty());
    }
    @Test public void fallsBackToOfficialAppleArtworkWhenMotionPreviewIsMissing() {
        JsonObject row = album("KUN", "蔡徐坤"), attributes = row.getAsJsonObject("attributes");
        attributes.getAsJsonObject("editorialVideo").getAsJsonObject("motionTallVideo3x4").remove("previewFrame");
        JsonObject artwork = new JsonObject(); artwork.addProperty("url", "https://is1-ssl.mzstatic.com/album/{w}x{h}bb.jpg");
        attributes.add("artwork", artwork);
        AppleMotionArtwork.Cover cover = AppleMotionArtwork.parseSearch(search(row), "KUN", "蔡徐坤");
        assertEquals("https://is1-ssl.mzstatic.com/album/720x720bb.jpg", cover.preview);
        artwork.addProperty("url", "https://evil.test/image.jpg");
        assertEquals("", AppleMotionArtwork.parseSearch(search(row), "KUN", "蔡徐坤").preview);
    }
    private String token(String issuer, long expiry) {
        String payload = "{\"iss\":\"" + issuer + "\",\"exp\":" + expiry + "}";
        return "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".abcdefghijklmnopqrstuvwxy";
    }
    @Test public void choosesOnlyValidAppleWebTokens() {
        String right = token("AMPWebPlay", 10_000);
        assertEquals(right, AppleMotionArtwork.findWebToken(token("SomeOtherService", 90_000) + " " +
                token("AMPWebPlay", 900) + " " + right, 1_000));
        assertEquals("", AppleMotionArtwork.findWebToken(token("AMPWebPlay", 900), 1000));
        assertEquals(0, AppleMotionArtwork.tokenExpires("broken"));
    }
    @Test public void rejectsForeignHostsAndSelectsH264NotHevc() {
        assertFalse(AppleMotionArtwork.isAppleHttps("https://music.apple.com.evil.test/file"));
        assertFalse(AppleMotionArtwork.isAppleHttps("http://mvod.itunes.apple.com/file"));
        assertFalse(AppleMotionArtwork.isAppleHttps("https://evil.test@music.apple.com/file"));
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1080x1440,CODECS=\"avc1.640028\"\nhigh.m3u8\n" +
                "#EXT-X-STREAM-INF:RESOLUTION=664x886,CODECS=\"avc1.640028\"\nmedium.m3u8\n" +
                "#EXT-X-STREAM-INF:RESOLUTION=720x960,CODECS=\"hvc1\"\nhevc.m3u8\n";
        assertEquals(HOST + "medium.m3u8", AppleMotionArtwork.mediaPlaylist(master, HOST + "master.m3u8", 720));
        assertEquals(HOST + "medium.m3u8", AppleMotionArtwork.mediaPlaylist(master, HOST + "master.m3u8", 100));
    }
    @Test public void validatesSingleFileHlsInsteadOfDownloadingOnlyTheInitializationSegment() {
        String playlist = "#EXTM3U\n#EXT-X-MAP:URI=\"video.mp4\",BYTERANGE=\"100@0\"\n" +
                "#EXTINF:5\n#EXT-X-BYTERANGE:1000@100\nvideo.mp4\n#EXT-X-ENDLIST\n";
        assertEquals(HOST + "video.mp4", AppleMotionArtwork.singleMp4(playlist, HOST + "media.m3u8"));
        try { AppleMotionArtwork.singleMp4(playlist.replace("\nvideo.mp4", "\nsegment.m4s"), HOST + "media.m3u8"); fail(); }
        catch (IllegalArgumentException expected) { }
        try { AppleMotionArtwork.singleMp4(playlist.replace("#EXT-X-ENDLIST", ""), HOST + "media.m3u8"); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    private byte[] box(String type, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length).putInt(8 + payload.length)
                .put(type.getBytes(StandardCharsets.US_ASCII)).put(payload).array();
    }
    private byte[] join(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.write(part, 0, part.length); return output.toByteArray();
    }
    private byte[] fragment(int track, long time, int version) {
        byte[] tfhd = box("tfhd", ByteBuffer.allocate(8).putInt(0).putInt(track).array());
        ByteBuffer timeBytes = ByteBuffer.allocate(version == 1 ? 12 : 8).putInt(version << 24);
        if (version == 1) timeBytes.putLong(time); else timeBytes.putInt((int) time);
        return box("moof", box("traf", join(tfhd, box("tfdt", timeBytes.array()))));
    }
    @Test public void rebases32And64BitFragmentTimesPerTrackPreservingDurations() {
        byte[] header = box("ftyp", new byte[4]);
        byte[] first = fragment(1, 10000, 0), next = fragment(1, 15000, 0), other = fragment(2, 441000, 1);
        byte[] data = join(header, first, next, other);
        AppleMotionArtwork.rebaseMp4(data);
        assertEquals(0, ByteBuffer.wrap(data).getInt(header.length + first.length - 4));
        assertEquals(5000, ByteBuffer.wrap(data).getInt(header.length + first.length + next.length - 4));
        assertEquals(0, ByteBuffer.wrap(data).getLong(data.length - 8));
        assertEquals(header.length + first.length + next.length + other.length, data.length);
    }
    @Test public void malformedMp4IsRejectedBeforeAnyTimestampIsChanged() {
        byte[] data = join(box("ftyp", new byte[4]), fragment(1, 10000, 0), new byte[] {1, 2, 3});
        byte[] original = data.clone();
        try { AppleMotionArtwork.rebaseMp4(data); fail(); } catch (IllegalArgumentException expected) { }
        assertArrayEquals(original, data);
    }
}
