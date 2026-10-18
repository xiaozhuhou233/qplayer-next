package dev.t1m3.qplayer.audio.automix;

import dev.t1m3.qplayer.audio.core.*;
import dev.t1m3.qplayer.audio.mix.*;
import dev.t1m3.qplayer.audio.dsp.PitchShifter;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Original AutoMix DSP, streamed into a bounded temporary WAV, including B's continuation. */
public final class AutoMixRender {
    public static final int RATE = 44100;
    private static final int BLOCK_FRAMES = 512;
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    public final Path file;
    public final String original;
    public final long exitMs, entryMs, durationMs, overlapMs, leadInMs;
    private final FileOwner owner;
    private boolean released;

    private static final class FileOwner {
        int references = 1;
    }

    public AutoMixRender(Path file, String original, long exitMs, long entryMs,
                         long durationMs, long overlapMs) {
        this(file, original, exitMs, entryMs, durationMs, overlapMs, 0);
    }
    public AutoMixRender(Path file, String original, long exitMs, long entryMs,
                         long durationMs, long overlapMs, long leadInMs) {
        this(file, original, exitMs, entryMs, durationMs, overlapMs, leadInMs, new FileOwner());
    }
    private AutoMixRender(Path file, String original, long exitMs, long entryMs,
                          long durationMs, long overlapMs, long leadInMs, FileOwner owner) {
        this.file = file; this.original = original; this.exitMs = exitMs;
        this.entryMs = entryMs; this.durationMs = durationMs; this.overlapMs = overlapMs;
        this.leadInMs = Math.max(0, leadInMs);
        this.owner = owner;
    }
    public long songPosition(long fileMs) { return Math.min(durationMs, entryMs + Math.max(0, fileMs - leadInMs)); }
    /** Independent ownership handle: cancelling a parked player must not delete the reusable render. */
    public AutoMixRender retain() { return withOriginal(original); }
    public AutoMixRender withOriginal(String source) {
        synchronized (owner) {
            if (released) throw new IllegalStateException("AutoMix render already released");
            owner.references++;
            return new AutoMixRender(file, source, exitMs, entryMs, durationMs, overlapMs, leadInMs, owner);
        }
    }
    /** Release this handle once; the last owner removes the temporary file. */
    public void delete() {
        synchronized (owner) {
            if (released) return;
            released = true;
            if (--owner.references == 0)
                try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        }
    }

    /** Call once at process startup, before any playback/render is created. */
    public static void cleanPreviousSession(Path directory) {
        if (!Files.isDirectory(directory)) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "mix-*.wav")) {
            for (Path entry : entries) if (Files.isRegularFile(entry)) Files.deleteIfExists(entry);
        } catch (IOException ignored) { }
    }

    public static AutoMixRender prepare(String a, String b, Map<String,String> ah,
                                        Map<String,String> bh, AutoMixConfig config,
                                        Path directory, BooleanSupplier valid) throws IOException {
        check(valid);
        Transition engine = config.engine(RATE);
        return prepare(a, b, ah, bh, engine, directory, valid);
    }

    static AutoMixRender prepare(String a, String b, Map<String,String> ah, Map<String,String> bh,
                                 Transition engine, Path directory, BooleanSupplier valid) throws IOException {
        check(valid);
        TransitionPlan plan = engine.prepare(a, b, RATE, ah, bh);
        check(valid);
        Deck from = Deck.open(a, ah, RATE, 2, BLOCK_FRAMES);
        Deck to = null;
        Path file = null;
        boolean success = false;
        try {
            to = Deck.open(b, bh, RATE, 2, BLOCK_FRAMES);
            Double ad = from.spec().durationSecs(), bd = to.spec().durationSecs();
            if (ad == null || bd == null || ad <= 0 || bd <= 0)
                throw new IOException("AutoMix requires known audio durations");
            long window = engine.windowFrames(RATE);
            double entry = Math.max(0, plan.skipSecs);
            double exit = engine.outgoingStartSecs(ad, plan, RATE);
            double earliest = AutoCue.earliestCutSecs(ad);
            if (exit + 0.000001 < earliest)
                throw new IOException("AutoMix plan starts before five sevenths of the outgoing song");
            if (entry + window / (double) RATE >= bd
                    || (!engine.suppliesOutgoingAudio() && exit + window / (double) RATE > ad + 0.001))
                throw new IOException("AutoMix cue exceeds track length");
            // The dry prefix lets Android warm its audio output before the real cue.
            // It carries A only: no next-song audio is consumed during this warmup.
            long leadFrames = Math.min(RATE * 3L, (long) Math.floor(exit * RATE));
            if (((bd - entry) * RATE + leadFrames) * 4 > MAX_BYTES)
                throw new IOException("AutoMix continuation exceeds cache budget");
            boolean pitchFrom = false, pitchTo = false;
            // Check the complete automation: a zero pitch at p=0 may glide later.
            for (long at = 0; at < window; at += BLOCK_FRAMES) {
                PitchPlan pitch = engine.pitchAt((float) Math.min(1d,
                        (at + BLOCK_FRAMES * .5) / Math.max(1L, window)), window / (double) RATE);
                pitchFrom |= Math.abs(pitch.outro) > 1e-6f;
                pitchTo |= Math.abs(pitch.intro) > 1e-6f;
                if (pitchFrom && pitchTo) break;
            }
            positionAndWarmPitch(from, exit, pitchFrom, valid);
            positionAndWarmPitch(to, entry, pitchTo, valid);
            Files.createDirectories(directory);
            file = Files.createTempFile(directory, "mix-", ".wav");
            AudioBuf ab = new AudioBuf(ChannelLayout.of(2), BLOCK_FRAMES);
            AudioBuf bb = new AudioBuf(ChannelLayout.of(2), BLOCK_FRAMES);
            AudioBuf out = new AudioBuf(ChannelLayout.of(2), BLOCK_FRAMES);
            DeckReader outgoing = new DeckReader(from), incoming = new DeckReader(to);
            long continuationFrames = Math.round((bd - entry) * RATE);
            long incomingRounding = roundingFrames(to);
            long incomingTolerance = endToleranceFrames(to);
            long outgoingTolerance = endToleranceFrames(from);
            long done = 0, frames = 0;
            try (RandomAccessFile wav = new RandomAccessFile(file.toFile(), "rw")) {
                wav.write(new byte[44]);
                if (leadFrames > 0) {
                    Deck dry = Deck.open(a, ah, RATE, 2, BLOCK_FRAMES);
                    try {
                        dry.seek(exit - leadFrames / (double) RATE);
                        long remaining = leadFrames;
                        while (remaining > 0) {
                            check(valid);
                            int n = (int) Math.min(remaining, dry.render(ab));
                            if (n <= 0) throw new IOException("Missing AutoMix warmup prefix");
                            write(wav, ab, 0, n);
                            remaining -= n;
                        }
                    } finally { dry.close(); }
                    frames = leadFrames;
                }
                while (frames - leadFrames < continuationFrames) {
                    check(valid);
                    int want = (int) Math.min(BLOCK_FRAMES, continuationFrames - (frames - leadFrames));
                    if (done < window)
                        want = engine.renderChunkFrames((int) Math.min(want, window - done));
                    if (want <= 0) throw new IOException("Invalid AutoMix material boundary");
                    float progress = (float) Math.min(1d,
                            (done + want * .5) / Math.max(1L, window));
                    PitchPlan pitch = window > done ? engine.pitchAt(progress, window / (double) RATE) : PitchPlan.ZERO;
                    from.setPitch(pitch.outro);
                    to.setPitch(pitch.intro);
                    int n = incoming.read(bb, want);
                    boolean incomingEnd = n < want;
                    if (n != want) {
                        // MediaCodec can trim encoder delay/padding although the
                        // container duration still counts those compressed frames.
                        // Keep a complete mix and the real B tail; do not discard
                        // the whole WAV or pad the continuation with silence.
                        long missing = continuationFrames - (frames - leadFrames) - n;
                        if (missing > incomingTolerance
                                || (done + n < window && missing > incomingRounding))
                            throw new IOException("Incoming audio ended before declared duration: "
                                    + missing + " frames missing, " + engine.name() + ", entry=" + entry);
                        if (missing <= incomingRounding) { padEnd(bb, n, want); n = want; }
                    }
                    int mix = (int) Math.min(n, Math.max(0L, window - done));
                    if (mix > 0) {
                        int got = outgoing.read(ab, mix);
                        if (got < mix) {
                            if (!engine.suppliesOutgoingAudio()
                                    && window - done - got > outgoingTolerance)
                                throw new IOException("Outgoing audio ended before AutoMix cue");
                            padEnd(ab, got, mix);
                        }
                        ab.setFrames(mix); bb.setFrames(mix);
                        int produced = engine.render(out, ab, bb, progress);
                        if (produced != mix) throw new IOException("Incomplete AutoMix block");
                        write(wav, out, 0, mix);
                        done += mix;
                    }
                    bb.setFrames(n);
                    if (mix < n) write(wav, bb, mix, n - mix);
                    frames += n;
                    if (frames * 4 > MAX_BYTES) throw new IOException("AutoMix cache limit");
                    if (incomingEnd) break;
                }
                if (frames - leadFrames < window || frames == leadFrames)
                    throw new IOException("Empty AutoMix render");
                wav.seek(0);
                wav.writeBytes("RIFF"); le32(wav, frames * 4 + 36); wav.writeBytes("WAVEfmt ");
                le32(wav, 16); le16(wav, 1); le16(wav, 2); le32(wav, RATE);
                le32(wav, RATE * 4); le16(wav, 4); le16(wav, 16);
                wav.writeBytes("data"); le32(wav, frames * 4);
            }
            check(valid);
            AutoMixRender result = new AutoMixRender(file, b, Math.round(exit * 1000),
                    Math.round(entry * 1000), Math.round(bd * 1000), window * 1000 / RATE,
                    Math.round(leadFrames * 1000d / RATE));
            success = true;
            return result;
        } finally {
            from.close();
            if (to != null) to.close();
            if (!success && file != null) Files.deleteIfExists(file);
        }
    }
    private static long roundingFrames(Deck deck) {
        return (long) Math.ceil(RATE / (double) deck.inRate()) + 2;
    }
    private static long endToleranceFrames(Deck deck) {
        // A few compressed codec frames cover MP3/AAC priming and end padding.
        // Lossless streams retain the sample-exact check. This is not permission
        // to accept a truncated download or an incomplete incoming mix window.
        return deck.spec().lossless ? roundingFrames(deck)
                : (long) Math.ceil(4096d * RATE / deck.inRate()) + roundingFrames(deck);
    }
    private static void padEnd(AudioBuf buffer, int from, int to) {
        for (int c = 0; c < buffer.channels(); c++) java.util.Arrays.fill(buffer.backingArray(),
                buffer.channelBase(c) + from, buffer.channelBase(c) + to, 0f);
        buffer.setFrames(to);
    }
    /** Preserve unconsumed samples at overlap and segmented-material boundaries. */
    private static final class DeckReader {
        final Deck deck;
        final AudioBuf buffered = new AudioBuf(ChannelLayout.of(2), BLOCK_FRAMES);
        int cursor;
        DeckReader(Deck deck) { this.deck = deck; }
        int read(AudioBuf destination, int requested) {
            int copied = 0;
            while (copied < requested) {
                if (cursor >= buffered.frames()) {
                    if (deck.render(buffered) <= 0) break;
                    cursor = 0;
                }
                int count = Math.min(requested - copied, buffered.frames() - cursor);
                for (int c = 0; c < 2; c++) System.arraycopy(buffered.backingArray(),
                        buffered.channelBase(c) + cursor, destination.backingArray(),
                        destination.channelBase(c) + copied, count);
                cursor += count;
                copied += count;
            }
            destination.setFrames(copied);
            return copied;
        }
    }
    private static void positionAndWarmPitch(Deck deck, double cue, boolean needed,
                                            BooleanSupplier valid) throws IOException {
        if (!needed) { deck.seek(cue); return; }
        // Feed real audio before the cue at unity. A cold FFT/overlap-add window would
        // otherwise start the WAV almost silent and sound like a stall before the glide.
        int warmFrames = Math.min(PitchShifter.grainForRate(RATE) * 2,
                (int) Math.min(Integer.MAX_VALUE, Math.floor(cue * RATE / BLOCK_FRAMES) * BLOCK_FRAMES));
        deck.seek(Math.max(0, cue - warmFrames / (double) RATE));
        deck.engagePitch();
        AudioBuf scratch = new AudioBuf(ChannelLayout.of(2), BLOCK_FRAMES);
        for (int at = 0; at < warmFrames; at += BLOCK_FRAMES) {
            check(valid);
            if (deck.render(scratch) != BLOCK_FRAMES)
                throw new IOException("Outgoing audio ended during pitch warmup");
        }
    }
    private static void check(BooleanSupplier valid) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || !valid.getAsBoolean())
            throw new InterruptedIOException("AutoMix cancelled");
    }
    private static void write(RandomAccessFile f, AudioBuf b, int at, int n) throws IOException {
        byte[] pcm = new byte[n * 4];
        for (int i = 0; i < n; i++) for (int c = 0; c < 2; c++) {
            float v = b.backingArray()[b.channelBase(c) + at + i];
            int sample = Math.round(Math.max(-1f, Math.min(1f, Float.isFinite(v) ? v : 0)) * 32767f);
            int offset = i * 4 + c * 2;
            pcm[offset] = (byte) sample; pcm[offset + 1] = (byte) (sample >> 8);
        }
        f.write(pcm);
    }
    private static void le16(RandomAccessFile f, int v) throws IOException { f.write(v); f.write(v >> 8); }
    private static void le32(RandomAccessFile f, long v) throws IOException {
        le16(f, (int) v); le16(f, (int) (v >> 16));
    }
}
