package dev.t1m3.qplayer.audio.automix;

import dev.t1m3.qplayer.audio.core.*;
import dev.t1m3.qplayer.audio.decode.*;
import dev.t1m3.qplayer.audio.mix.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.*;

/**
 * **真实歌曲**的过渡示例（2026-10-11 ✓ 用户：「换真实歌曲走 我 music 文件夹下全都是」✓）。
 *
 * <p>直接读 16-bit PCM WAV（`/tmp/amx/A.wav`、`B.wav`，由 music 文件夹里的 mp3 转出 ✓）
 * 走**真实渲染管线** ✓ 输出三种模式的过渡到桌面 ✓。
 */
public class RealSongDemoGenerator {

    private static final class Wav implements Decoder {
        final AudioInputStream in;
        final short[] pcm;                 // 交错立体声
        final long frames;
        final int rate, channels;
        long frame;
        final AtomicInteger closed = new AtomicInteger();

        Wav(Path path) throws Exception {
            in = AudioSystem.getAudioInputStream(path.toFile());
            AudioFormat f = in.getFormat();
            rate = (int) f.getSampleRate();
            channels = f.getChannels();
            byte[] all = in.readAllBytes();
            pcm = new short[all.length / 2];
            for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((all[i * 2 + 1] << 8) | (all[i * 2] & 255));
            frames = pcm.length / channels;
        }

        public TrackSpec spec() {
            return new TrackSpec("audio/wav", rate, ChannelLayout.of(channels), 16, frames, true);
        }
        public long framesDecoded() { return frame; }
        public boolean isDrained() { return frame >= frames; }
        public double seek(double seconds) { frame = Math.round(seconds * rate); return frame / (double) rate; }

        public int decodeInto(AudioBuf out) {
            int n = (int) Math.min(out.capacityFrames(), Math.max(0, frames - frame));
            for (int c = 0; c < channels; c++) {
                for (int i = 0; i < n; i++) {
                    out.backingArray()[out.channelBase(c) + i] =
                            pcm[(int) ((frame + i) * channels + c)] / 32768f;
                }
            }
            out.setFrames(n);
            frame += n;
            return n;
        }
        public void close() { closed.incrementAndGet(); try { in.close(); } catch (IOException ignored) { } }
    }

    private static void render(int mode, String name, Path a, Path b, Path output) throws Exception {
        Decoder.install((source, headers) -> {
            try {
                return new Wav("B".equals(source) ? b : a);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Path dir = Files.createTempDirectory("real-demo");
        AutoMixRender r = AutoMixRender.prepare("A", "B",
                Collections.emptyMap(), Collections.emptyMap(),
                new AutoMixConfig(true, mode, 1, 6, false, 50, 100, false, "", true),
                dir, () -> true);
        Path out = output.resolve(name);
        try {
        Files.copy(r.file, out, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("[REAL] " + name + " exitMs=" + r.exitMs + " entryMs=" + r.entryMs
                + " overlapMs=" + r.overlapMs + " leadInMs=" + r.leadInMs);
        System.out.println("[REAL] 文件大小 " + Files.size(out) / 1024 / 1024 + " MB");
        } finally { r.delete(); Files.deleteIfExists(dir); }
    }

    /** Manual tool: args are outgoing PCM WAV, incoming PCM WAV, output directory. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: outgoing.wav incoming.wav output-dir");
        Path output = Paths.get(args[2]);
        Files.createDirectories(output);
        try {
            render(0, "real-automix-crossfade.wav", Paths.get(args[0]), Paths.get(args[1]), output);
            render(2, "real-automix-aimix.wav", Paths.get(args[0]), Paths.get(args[1]), output);
            render(1, "real-automix-harmonic.wav", Paths.get(args[0]), Paths.get(args[1]), output);
        } finally { Decoder.install(null); }
    }
}
