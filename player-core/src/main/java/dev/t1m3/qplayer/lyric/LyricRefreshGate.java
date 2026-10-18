package dev.t1m3.qplayer.lyric;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded per-song refresh deduplication. Failure is not a permanent session
 * ban: replay can retry after a short cooldown. Never invoked per frame. */
public final class LyricRefreshGate {
    private final Set<Long> running = new HashSet<>();
    private final Map<Long, Long> retryAfter = new LinkedHashMap<Long, Long>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Long> entry) {
            return size() > 256;
        }
    };

    public synchronized boolean begin(long songId, long nowMs) {
        Long next = retryAfter.get(songId);
        if (running.contains(songId) || (next != null && nowMs < next)) return false;
        running.add(songId);
        return true;
    }

    public synchronized void finish(long songId, long nowMs, boolean complete) {
        retryAfter.put(songId, nowMs + (complete ? 600_000L : 30_000L));
        running.remove(songId);
    }
}
