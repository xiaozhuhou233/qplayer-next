package dev.t1m3.qplayer.android.playback;

import java.util.concurrent.Executor;
import java.util.function.Function;

/** One decoded notification cover, one running decode, and one latest request.
 * All slow work runs outside the lock, so transport/main-thread reads never wait
 * for decoding. Older tracks cannot overwrite a newer selection, even A -> B -> A.
 */
final class PlaybackArtworkLoader<T> {
    private final Executor worker;
    private final Executor main;
    private final Function<byte[], T> decode;
    private final Runnable onReady;
    private byte[] requested;
    private T artwork;
    private long revision;
    private boolean draining;
    private boolean closed;

    PlaybackArtworkLoader(Executor worker, Executor main, Function<byte[], T> decode,
                          Runnable onReady) {
        this.worker = worker;
        this.main = main;
        this.decode = decode;
        this.onReady = onReady;
    }

    T get(byte[] bytes) {
        boolean start = false;
        T result;
        synchronized (this) {
            if (closed) return null;
            if (requested != bytes) {
                requested = bytes;
                artwork = null;
                revision++;
                if (bytes != null && !draining) {
                    draining = true;
                    start = true;
                }
            }
            result = artwork;
        }
        if (start) worker.execute(this::drain);
        return result;
    }

    private void drain() {
        while (true) {
            final byte[] bytes;
            final long generation;
            synchronized (this) {
                if (closed || requested == null) {
                    draining = false;
                    return;
                }
                bytes = requested;
                generation = revision;
            }
            T decoded;
            try {
                decoded = decode.apply(bytes);
            } catch (Exception ignored) {
                decoded = null;
            }
            synchronized (this) {
                if (closed) {
                    draining = false;
                    return;
                }
                if (generation != revision) continue;
                artwork = decoded;
                draining = false;
            }
            if (decoded != null) main.execute(() -> {
                synchronized (PlaybackArtworkLoader.this) {
                    if (closed || generation != revision) return;
                }
                onReady.run();
            });
            return;
        }
    }

    synchronized void close() {
        closed = true;
        revision++;
        requested = null;
        artwork = null;
    }
}
