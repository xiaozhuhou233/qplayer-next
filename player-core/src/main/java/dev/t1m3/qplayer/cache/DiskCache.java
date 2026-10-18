package dev.t1m3.qplayer.cache;

import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Unified disk cache for audio files, lyrics and cover images.
 * <p>
 * Seven sub-directories under {@code AppDirs.cacheBase()/cache/}:
 * {@code audio/}, {@code lyric/}, {@code image/}, {@code thumb64/},
 * {@code silence/}, {@code transition/}, {@code beat/}.
 * <p>
 * LRU eviction by last-modified time: after every write the total size is
 * checked against {@link #maxSizeBytes} and oldest files are deleted until
 * the limit is satisfied.  Callers should use the typed helper methods
 * ({@link #cacheAudio}, {@link #cacheLyric}, {@link #cacheImage}) which
 * touch the file on read (via {@link #getAudio}, etc.) so that actively-used
 * entries survive eviction.
 */
public final class DiskCache {

    /** Not final: {@link #setBaseDir} lets the desktop settings page repoint the
     *  cache root at runtime, which a compile-time constant couldn't support. */
    private volatile String baseDir = AppDirs.cacheDir().toString();

    /** Sub-directory names. */
    public static final String AUDIO   = "audio";
    public static final String LYRIC   = "lyric";
    public static final String IMAGE   = "image";
    /** Offline-playlist-browsing thumbnails (64x64), kept separate from the
     *  general {@link #IMAGE} cache: capped by file *count*
     *  ({@link #THUMB64_MAX_COUNT}), not the byte-size budget the other three
     *  sub-caches share, since a meaningful byte budget for images this tiny
     *  would be a near-unlimited file count anyway. */
    public static final String THUMB64 = "thumb64";
    /** Per-track silence measurements (two ints: leading and trailing silence in
     *  ms) for {@code TransitionKind.SILENCE_TRIM}. Its own sub-directory because
     *  it is not media at all — a few bytes describing a decoded window — and
     *  because a measurement outlives every other entry for the same track: it
     *  stays valid as long as the audio itself does, while the audio file may be
     *  evicted and re-downloaded underneath it. */
    public static final String SILENCE = "silence";
    /** Per-<em>pair</em> transition decisions (kind + overlap + curve, eight bytes)
     *  for the AI-backed {@code TransitionChooser}: the same two tracks are asked
     *  about once, and every later play of that pair — this session or the next,
     *  online or offline — is answered from here instead of from a network round
     *  trip. A decision is only as good as the metadata it was made from, which is
     *  why the key is the pair's identity rather than the tracks' contents.
     *  Capped by file *count* ({@link #TRANSITION_MAX_COUNT}): the entries are a
     *  handful of bytes each, so the byte budget the media sub-caches share would
     *  never evict them before the directory itself became a problem. */
    public static final String TRANSITION = "transition";
    /** Per-track beat grids (tempo, phase, confidence — twelve bytes) for the
     *  overlap alignment: the incoming track starts on one of its own beats and the
     *  outgoing track's ramp starts on one of its, so the two grids meet instead of
     *  overlapping arbitrarily. Same reasoning as {@link #SILENCE} — a measurement
     *  outlives the audio file it was taken from, and it costs a decode to make — but
     *  its own sub-directory because it is a different measurement of the same track
     *  and nothing may confuse the two. Capped by file count
     *  ({@link #BEAT_MAX_COUNT}), like the pair decisions: twelve bytes never
     *  approach the byte budget, so the only thing that can grow is the count. */
    public static final String BEAT = "beat";
    /** One pre-rendered blend WINDOW per pair — the Folia stem gesture applied to both
     *  tracks' separated stems and mixed down to a single audio file (sixteen-bit stereo,
     *  2.6 MB for a fifteen-second window). Derived media rather than a measurement, so it
     *  is the one sub-cache here that both costs real bytes and saves real work: making it
     *  is two separations (a minute of CPU and 860 MB of peak memory on the target device),
     *  and playing it back is a plain file. Capped by file count
     *  ({@link #BLEND_MAX_COUNT}) AND counted in {@link #totalSize()}, because unlike the
     *  twelve-byte entries next door this one can actually fill a disk. */
    public static final String BLEND = "blend";
    /** One DJ edit per (track, blend length): the incoming track's own audio with the
     *  vocals taken out of its first N ms and handed back on a bar line, written as one
     *  playable file because that is what makes the incoming deck able to play the whole
     *  thing — before the blend, through the promotion, and to the end of the track —
     *  without ever changing source.
     *
     *  <p>Its own sub-directory rather than a second name in {@link #BLEND}: these are
     *  minutes of encoded audio (~5 MB each) instead of a fifteen-second window, they are
     *  keyed by a track and a length rather than by a pair, and they are read by the
     *  playback path — so they get their own, much smaller count cap. Counted in
     *  {@link #totalSize()} like {@link #BLEND}, because they can actually fill a disk. */
    public static final String DJEDIT = "djedit";

    /** Oldest files (by lastModified) are deleted once the count exceeds this,
     *  every time a new one is cached — see {@link #cacheThumb64}. */
    private static final int THUMB64_MAX_COUNT = 128;

    /** Same idea for {@link #TRANSITION}: a pair decision is eight bytes, so the
     *  only thing that can grow unboundedly is the number of them. Two thousand
     *  pairs is far more than any queue walks through, and deleting the oldest
     *  costs nothing but one more question to the model. */
    private static final int TRANSITION_MAX_COUNT = 2_000;

    /** Same idea for {@link #BEAT}: one grid per track, twelve bytes each. */
    private static final int BEAT_MAX_COUNT = 2_000;

    /** {@link #BLEND}: a render is ~2.6 MB, so twenty of them is ~53 MB — the count is
     *  what keeps the sub-cache from growing without bound, and the byte budget the media
     *  sub-caches share is what keeps it from evicting the audio out from under a queue. */
    private static final int BLEND_MAX_COUNT = 20;

    /** {@link #DJEDIT}: four edits is already ~20 MB of encoded audio and covers the
     *  only two boundaries that can be in flight (this one and the next), so the count is
     *  what keeps it bounded. Eviction is by age, like every other count-capped
     *  sub-cache — an edit for a pair the queue has left is the one to lose. */
    private static final int DJEDIT_MAX_COUNT = 4;

    private volatile long maxSizeBytes;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
            audioPins = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object[] audioLocks = new Object[16];

    private Object audioLock(String path) {
        int slot = (path.hashCode() & Integer.MAX_VALUE) % audioLocks.length;
        synchronized (audioLocks) {
            if (audioLocks[slot] == null) audioLocks[slot] = new Object();
            return audioLocks[slot];
        }
    }

    /** A source being analysed cannot be evicted underneath the decoder. */
    public final class AudioLease implements AutoCloseable {
        public final String path;
        private boolean closed;
        private AudioLease(String path) {
            this.path = path;
            audioPins.compute(new File(path).getAbsolutePath(), (key, count) -> {
                if (count == null) count = new java.util.concurrent.atomic.AtomicInteger();
                count.incrementAndGet(); return count;
            });
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            audioPins.computeIfPresent(new File(path).getAbsolutePath(), (key, count) -> count.decrementAndGet() <= 0 ? null : count);
        }
    }

    /** Download once, atomically publish, and pin until AutoMix has finished reading. */
    public AudioLease prepareAudio(String url, long songId, String sourceKey,
            java.util.function.BooleanSupplier valid) throws java.io.IOException {
        String path = songId != 0 ? audioPath(songId)
                : new File(new File(baseDir, AUDIO), "source-" + java.util.UUID.nameUUIDFromBytes(
                        sourceKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".audio").getPath();
        synchronized (audioLock(path)) {
            checkAudioJob(valid);
            AudioLease lease = new AudioLease(path);
            Path target = Paths.get(path), pending = null;
            HttpURLConnection connection = null;
            boolean success = false;
            try {
                if (Files.isRegularFile(target) && Files.size(target) > 0) {
                    touch(path); success = true; return lease;
                }
                ensureParent(path);
                pending = Files.createTempFile(target.getParent(), "precache-", ".part");
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "qplayer/1.0");
                int response = connection.getResponseCode();
                if (response != 200) throw new java.io.IOException("音频下载失败 HTTP " + response);
                String type = connection.getContentType();
                if (type != null && (type.contains("text/") || type.contains("json")))
                    throw new java.io.IOException("音源返回了非音频内容");
                long expected = connection.getContentLengthLong();
                long budget = maxSizeBytes > 0 ? Math.min(maxSizeBytes, 256L * 1024 * 1024) : 256L * 1024 * 1024;
                if (expected > budget) throw new java.io.IOException("下一首超过音频缓存上限");
                long bytes = 0;
                try (InputStream in = connection.getInputStream();
                     java.io.OutputStream out = Files.newOutputStream(pending)) {
                    byte[] block = new byte[32768];
                    int n;
                    while ((n = in.read(block)) != -1) {
                        checkAudioJob(valid);
                        bytes += n;
                        if (bytes > budget) throw new java.io.IOException("下一首超过音频缓存上限");
                        out.write(block, 0, n);
                    }
                }
                checkAudioJob(valid);
                if (bytes == 0 || (expected >= 0 && bytes != expected))
                    throw new java.io.IOException("音频未完整下载");
                StorageFiles.replace(pending, target);
                evictIfNeeded();
                success = true;
                return lease;
            } finally {
                if (connection != null) connection.disconnect();
                if (!success) lease.close();
                if (pending != null) Files.deleteIfExists(pending);
            }
        }
    }

    private static void checkAudioJob(java.util.function.BooleanSupplier valid) throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || !valid.getAsBoolean())
            throw new java.io.InterruptedIOException("音频预缓存已取消");
    }

    public DiskCache(long maxSizeMB) {
        this.maxSizeBytes = maxSizeMB * 1024L * 1024L;
    }

    /**
     * Set the budget. The walk that enforces it is deliberately <em>not</em> run here:
     * the caller schedules it (see {@link #evictIfOverBudget}), because the one caller
     * is the settings store applying 最大缓存 as it loads — which is inside the app's
     * first frames — and the walk is a stat per cached file (several hundred on a used
     * phone, across nine sub-directories) followed, when the budget is exceeded, by a
     * sort of every file and a delete for each one that has to go.
     */
    public void setMaxSizeMB(long mb) {
        this.maxSizeBytes = mb * 1024L * 1024L;
    }

    /** Enforce the budget now, on the calling thread. Everything that writes into the
     *  cache calls this from its own worker; the settings path hands it to one (see
     *  {@code PlayerController.setCacheMaxSizeMB}). */
    public void evictIfOverBudget() {
        evictIfNeeded();
    }

    public long getMaxSizeMB() {
        return maxSizeBytes / (1024L * 1024L);
    }

    /** Repoint the cache root (e.g. the desktop "custom cache location" setting).
     *  Does not move existing files — the caller decides whether to migrate or
     *  just let the old location go stale. */
    public void setBaseDir(String dir) {
        if (dir == null || dir.trim().isEmpty()) return;
        this.baseDir = Paths.get(dir, "cache").toString();
    }

    public String baseDir() {
        return baseDir;
    }

    // ---- path helpers ----------------------------------------------------

    /** Resolve cache file for an audio track keyed by netease song id. */
    public String audioPath(long neteaseId) {
        if (neteaseId <= 0) return null;
        return baseDir + "/" + AUDIO + "/" + neteaseId + ".cache";
    }

    /** Resolve cache file for AMLL TTML lyrics keyed by song id. */
    public String lyricPath(long songId) {
        if (songId <= 0) return null;
        return baseDir + "/" + LYRIC + "/" + songId + ".ttml";
    }

    /** Resolve cache file for Netease's own lyric payload (serialized YRC/LRC). */
    public String neteaseLyricPath(long songId) {
        if (songId <= 0) return null;
        return baseDir + "/" + LYRIC + "/" + songId + ".nlrc";
    }

    /** Resolve cache file for a cover image keyed by url hash. */
    public String imagePath(String url) {
        if (url == null || url.isEmpty()) return null;
        return baseDir + "/" + IMAGE + "/" + Math.abs(url.hashCode()) + ".img";
    }

    /** Resolve cache file for a 64x64 offline-playlist thumbnail, keyed by
     *  url hash (the url is expected to already carry its own size param,
     *  e.g. {@code ?param=64y64} — same convention as {@link #imagePath}). */
    public String thumb64Path(String url) {
        if (url == null || url.isEmpty()) return null;
        return baseDir + "/" + THUMB64 + "/" + Math.abs(url.hashCode()) + ".img";
    }

    /** Resolve cache file for a track's beat grid, keyed by the same track key the
     *  silence measurement and the pair decisions use (song id / custom id / source
     *  string) — one track, one name, in every cache that describes it. */
    public String beatPath(String key) {
        if (key == null || key.isEmpty()) return null;
        return baseDir + "/" + BEAT + "/" + Math.abs(key.hashCode()) + ".bpm";
    }

    /** Resolve cache file for one pair's pre-rendered blend window (see {@link #BLEND}),
     *  keyed by the same pair key the transition decision uses. */
    public String blendPath(String key) {
        if (key == null || key.isEmpty()) return null;
        return baseDir + "/" + BLEND + "/" + Math.abs(key.hashCode()) + ".blend";
    }

    /** Resolve cache file for one track's DJ edit (see {@link #DJEDIT}), keyed by the
     *  track's own cache key joined with the removal window it was rendered for (see
     *  {@code PlayerController.djEditPath}) — a longer story than the other keys, so it
     *  is hashed like they are. The extension is {@code .m4a} on purpose: the file is
     *  AAC in an MP4 and the platform picks its extractor partly by extension, so a name
     *  that lies about the container would be the one thing that stops it playing. */
    public String djEditPath(String key) {
        String base = djEditBaseName(key);
        if (base == null) return null;
        return baseDir + "/" + DJEDIT + "/" + base + ".m4a";
    }

    /** The name one DJ edit's file is built from, without suffix or extension, or null for a
     *  key that cannot name one.
     *
     * <p>The renderer — not this class — appends the suffix and the extension, because what the
     * suffix says is what the render <em>learned</em>: where the bridge it built starts, and
     * where the track's vocals come back (see {@code StemEditRenderer.Result}). A later process
     * that looks an edit up by key therefore reads those two times back out of the directory
     * listing, which is why the base name has to be derivable in both places. */
    public String djEditBaseName(String key) {
        return key == null || key.isEmpty() ? null : String.valueOf(Math.abs(key.hashCode()));
    }

    /** The directory the DJ edits live in, so a caller that has to find one by name prefix can
     *  list it. */
    public String djEditDir() {
        return baseDir + "/" + DJEDIT;
    }

    /** Every file name in the DJ-edit directory (empty when there is none). */
    public String[] djEditNames() {
        String[] names = new File(baseDir, DJEDIT).list();
        return names != null ? names : new String[0];
    }

    /** Resolve cache file for a track's silence measurement, keyed by the
     *  track's own cache key (song id / custom id / source string — the caller
     *  builds it, see PlayerController's silenceKey). Hashed like
     *  {@link #imagePath}, since a local file path is a legal key too. */
    public String silencePath(String key) {
        if (key == null || key.isEmpty()) return null;
        return baseDir + "/" + SILENCE + "/" + Math.abs(key.hashCode()) + ".sil";
    }

    /** Resolve cache file for one pair's transition decision, keyed by the pair's
     *  own key ({@code outgoingKey + ">" + incomingKey} — see
     *  {@code TransitionPlan.pairKey}). Hashed like {@link #silencePath}, for the
     *  same reason: a local file path is a legal half of a key. */
    public String transitionPath(String key) {
        if (key == null || key.isEmpty()) return null;
        return baseDir + "/" + TRANSITION + "/" + Math.abs(key.hashCode()) + ".trn";
    }

    // ---- existence check -------------------------------------------------

    public boolean hasAudio(long neteaseId) {
        String p = audioPath(neteaseId);
        return p != null && new File(p).exists();
    }

    /** Delete a single cached audio file (cached-songs list right-click menu). */
    public boolean deleteAudio(long neteaseId) {
        String p = audioPath(neteaseId);
        if (p == null) return false;
        File f = new File(p);
        return f.exists() && f.delete();
    }

    public boolean hasLyric(long songId) {
        String p = lyricPath(songId);
        return p != null && new File(p).exists();
    }

    public boolean hasImage(String url) {
        String p = imagePath(url);
        return p != null && new File(p).exists();
    }

    public boolean hasThumb64(String url) {
        String p = thumb64Path(url);
        return p != null && new File(p).exists();
    }

    public boolean hasBeat(String key) {
        String p = beatPath(key);
        return p != null && new File(p).exists();
    }

    public boolean hasSilence(String key) {
        String p = silencePath(key);
        return p != null && new File(p).exists();
    }

    public boolean hasTransition(String key) {
        String p = transitionPath(key);
        return p != null && new File(p).exists();
    }

    // ---- read (touches lastModified for LRU) ------------------------------

    /**
     * Return the cached audio file path, touching its timestamp so it
     * survives LRU eviction. Returns null if not cached.
     */
    public String getAudio(long neteaseId) {
        String p = audioPath(neteaseId);
        return touch(p);
    }

    /** Return the cached AMLL TTML lyric file path, or null. */
    public String getLyric(long songId) {
        String p = lyricPath(songId);
        return touch(p);
    }

    /** Return the cached Netease lyric payload file path, or null. */
    public String getNeteaseLyric(long songId) {
        String p = neteaseLyricPath(songId);
        return touch(p);
    }

    /** Return the cached image file path, or null. */
    public String getImage(String url) {
        String p = imagePath(url);
        return touch(p);
    }

    /** Return the cached 64x64 thumbnail file path, or null. */
    public String getThumb64(String url) {
        String p = thumb64Path(url);
        return touch(p);
    }

    /** Return the cached beat-grid file path, or null. */
    public String getBeat(String key) {
        String p = beatPath(key);
        return touch(p);
    }

    /** Return the cached silence-measurement file path, or null. */
    public String getSilence(String key) {
        String p = silencePath(key);
        return touch(p);
    }

    /** Return the cached pair-decision file path, or null. */
    public String getTransition(String key) {
        String p = transitionPath(key);
        return touch(p);
    }

    // ---- write (download to cache) ---------------------------------------

    /**
     * Download an HTTP URL straight to the audio cache file.
     * Non-fatal: logs and cleans up on error.
     */
    public void cacheAudio(String url, long neteaseId) {
        String path = audioPath(neteaseId);
        synchronized (audioLock(path)) {
            if (getAudio(neteaseId) == null) downloadToFile(url, path);
        }
    }

    /** Write raw bytes to the AMLL TTML lyric cache file. */
    public void cacheLyric(byte[] data, long songId) {
        String path = lyricPath(songId);
        writeBytes(data, path);
    }

    /** Write the serialized Netease lyric payload to its cache file. */
    public void cacheNeteaseLyric(byte[] data, long songId) {
        String path = neteaseLyricPath(songId);
        writeBytes(data, path);
    }

    /** Download an HTTP URL to the image cache file. */
    public void cacheImage(String url) {
        String path = imagePath(url);
        downloadToFile(url, path);
    }

    /** Download an HTTP URL to the 64x64 thumbnail cache file, then evict the
     *  oldest thumbnails (by lastModified) past {@link #THUMB64_MAX_COUNT} —
     *  a file *count* cap, independent of the byte-size budget the other
     *  three sub-caches share via {@link #evictIfNeeded}. */
    public void cacheThumb64(String url) {
        String path = thumb64Path(url);
        downloadToFile(url, path, true);
        evictThumb64IfOverCount();
    }

    /** Write a track's beat grid (see {@link #BEAT}), then evict the oldest grids
     *  past {@link #BEAT_MAX_COUNT} — a count cap, like the pair decisions', for the
     *  same reason. */
    public void cacheBeat(String key, byte[] data) {
        writeBytes(data, beatPath(key));
        evictCountCapped(BEAT, BEAT_MAX_COUNT, "beat");
    }

    /** Write a track's silence measurement (see {@link #SILENCE}). Tiny, but it
     *  saves a decode window per boundary, and the same measurement is read back
     *  for as long as the audio exists. */
    public void cacheSilence(String key, byte[] data) {
        writeBytes(data, silencePath(key));
    }

    /** Write one pair's transition decision (see {@link #TRANSITION}), then evict
     *  the oldest decisions past {@link #TRANSITION_MAX_COUNT} — a count cap, like
     *  the thumbnails': eight bytes per entry never approaches the byte budget, so
     *  the file count is the only thing worth bounding. */
    public void cacheTransition(String key, byte[] data) {
        writeBytes(data, transitionPath(key));
        evictCountCapped(TRANSITION, TRANSITION_MAX_COUNT, "transition");
    }

    /** Write one pair's pre-rendered blend window (see {@link #BLEND}), then evict the
     *  oldest renders past {@link #BLEND_MAX_COUNT}. Bytes, not just a count: a render is
     *  three orders of magnitude bigger than the other derived entries. */
    public void cacheBlend(String key, byte[] data) {
        writeBytes(data, blendPath(key));
        evictCountCapped(BLEND, BLEND_MAX_COUNT, "blend");
    }

    /** Apply {@link #DJEDIT}'s count cap. The file itself is written by the host's stem
     *  renderer (a muxer needs the path, not a byte array), so the eviction cannot ride
     *  along with the write the way {@link #cacheBlend} does — the caller says when a
     *  render finished instead. */
    public void evictDjEdits() {
        evictCountCapped(DJEDIT, DJEDIT_MAX_COUNT, "djedit");
    }

    // ---- size & cleanup ---------------------------------------------------

    /** Total bytes used by every cache sub-directory, the DJ edits included — they are
     *  the largest derived files this app writes and the byte budget the media
     *  sub-caches share is the only thing standing between them and a full disk. */
    public long totalSize() {
        long total = 0;
        for (String sub : new String[]{AUDIO, LYRIC, IMAGE, THUMB64, SILENCE, TRANSITION, BEAT,
                BLEND, DJEDIT}) {
            total += dirSize(new File(baseDir, sub));
        }
        return total;
    }

    /** Delete all cached files. */
    public void clearAll() {
        for (String sub : new String[]{AUDIO, LYRIC, IMAGE, THUMB64, SILENCE, TRANSITION, BEAT,
                BLEND, DJEDIT}) {
            deleteRecursive(new File(baseDir, sub));
        }
    }

    /** Delete all cached files of one type. */
    public void clearType(String type) {
        deleteRecursive(new File(baseDir, type));
    }

    // ---- internals --------------------------------------------------------

    private String touch(String path) {
        if (path == null) return null;
        File f = new File(path);
        if (!f.exists()) return null;
        f.setLastModified(System.currentTimeMillis());
        return path;
    }

    private void writeBytes(byte[] data, String path) {
        if (data == null || path == null) return;
        try {
            StorageFiles.writeBytesAtomic(Paths.get(path), data);
            Logger.info("disk cache written: {} ({} B)", fileName(path), data.length);
        } catch (Throwable e) {
            Logger.warn("disk cache write failed: {}", e.getMessage());
        }
        evictIfNeeded();
    }

    private void downloadToFile(String url, String path) {
        downloadToFile(url, path, false);
    }

    /** {@code quiet} suppresses the per-file success line: warming a playlist's
     *  thumbnails queues one download per track, which drowned the log (a few
     *  hundred lines per playlist opened). Failures are still logged. */
    private void downloadToFile(String url, String path, boolean quiet) {
        if (url == null || path == null) return;
        ensureParent(path);
        HttpURLConnection c = null;
        Path target = Paths.get(path);
        Path pending = StorageFiles.pendingPath(target);
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "qplayer/1.0");
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(pending.toFile())) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            StorageFiles.replace(pending, target);
            if (!quiet) {
                Logger.info("disk cache downloaded: {} ({} B)", fileName(path), new File(path).length());
            }
        } catch (Throwable e) {
            Logger.warn("disk cache download failed: {}", e.getMessage());
            try { Files.deleteIfExists(pending); } catch (Throwable ignored) {}
        } finally {
            if (c != null) c.disconnect();
        }
        evictIfNeeded();
    }

    /**
     * If total cache size exceeds {@link #maxSizeBytes}, delete the
     * least-recently-used files (oldest lastModified) until under limit.
     */
    private void evictIfNeeded() {
        long limit = maxSizeBytes;
        if (limit <= 0) return; // 0 = unlimited
        long total = totalSize();
        if (total <= limit) return;

        // Collect all cache files across all sub-dirs. SILENCE (and its tiny
        // relatives TRANSITION and BEAT) is in the list even though it can never make
        // eviction work: leaving an undeletable sub-cache counted would mean the loop
        // below deletes every other file and still never reaches the limit.
        File[] dirs = {new File(baseDir, AUDIO), new File(baseDir, LYRIC),
                new File(baseDir, IMAGE), new File(baseDir, SILENCE),
                new File(baseDir, TRANSITION), new File(baseDir, BEAT),
                new File(baseDir, BLEND), new File(baseDir, DJEDIT)};
        java.util.List<File> files = new java.util.ArrayList<>();
        for (File dir : dirs) {
            if (dir.isDirectory()) {
                File[] children = dir.listFiles();
                if (children != null) files.addAll(Arrays.asList(children));
            }
        }
        // Sort by lastModified ascending (oldest first).
        files.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        for (File f : files) {
            if (total <= limit) break;
            if (audioPins.containsKey(f.getAbsolutePath()) || f.getName().endsWith(".part")) continue;
            long sz = f.length();
            if (f.delete()) {
                total -= sz;
                Logger.info("disk cache evicted: {}", f.getName());
            }
        }
    }

    /** Count (not byte-size) cap on {@link #THUMB64}: delete the oldest files
     *  once there are more than {@link #THUMB64_MAX_COUNT}. */
    private void evictThumb64IfOverCount() {
        File dir = new File(baseDir, THUMB64);
        File[] files = dir.listFiles();
        if (files == null || files.length <= THUMB64_MAX_COUNT) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int overBy = files.length - THUMB64_MAX_COUNT;
        // Unlogged: this runs after every warmed thumbnail, so once the cache is at
        // its cap it fires on each one -- a line here is pure noise, not a signal.
        for (int i = 0; i < overBy; i++) {
            files[i].delete();
        }
    }

    /** Count (not byte-size) cap on a sub-cache whose entries are a handful of
     *  bytes: delete the oldest files once there are more than {@code maxCount}.
     *  Logged, unlike the thumbnail trim: these entries are written once per track
     *  or pair rather than once per playlist row, so a line here is information. */
    private void evictCountCapped(String subDir, int maxCount, String label) {
        File dir = new File(baseDir, subDir);
        File[] files = dir.listFiles();
        if (files == null || files.length <= maxCount) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int overBy = files.length - maxCount;
        for (int i = 0; i < overBy; i++) {
            if (files[i].delete()) Logger.info("disk cache evicted ({}): {}", label, files[i].getName());
        }
    }

    private static void ensureParent(String path) {
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
    }

    private static long dirSize(File dir) {
        if (!dir.isDirectory()) return 0;
        long total = 0;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) total += f.length();
        }
        return total;
    }

    private static void deleteRecursive(File dir) {
        if (!dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) f.delete();
        }
        dir.delete();
    }

    private static String fileName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
