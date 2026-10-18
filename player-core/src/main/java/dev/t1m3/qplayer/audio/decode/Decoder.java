package dev.t1m3.qplayer.audio.decode;

import dev.t1m3.qplayer.audio.core.AudioBuf;

public interface Decoder extends AutoCloseable {
    TrackSpec spec();

    long framesDecoded();

    boolean isDrained();

    double seek(double secs);

    int decodeInto(AudioBuf out);

    @Override
    void close();

    interface Opener {
        Decoder open(String source, java.util.Map<String, String> headers);
    }

    static void install(Opener opener) {
        Holder.opener = opener;
    }

    static Decoder open(String source) {
        return open(source, java.util.Collections.<String, String>emptyMap());
    }

    static Decoder open(String source, java.util.Map<String, String> headers) {
        Opener opener = Holder.opener;
        if (opener == null) {
            throw new IllegalStateException(
                    "no decoder factory installed: the host must call Decoder.install(...) at"
                        + " startup");
        }
        return opener.open(
                source,
                headers == null ? java.util.Collections.<String, String>emptyMap() : headers);
    }

    final class Holder {
        static volatile Opener opener;

        private Holder() {}
    }
}
