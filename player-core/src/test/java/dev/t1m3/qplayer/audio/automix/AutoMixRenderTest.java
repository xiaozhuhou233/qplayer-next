package dev.t1m3.qplayer.audio.automix;

import dev.t1m3.qplayer.audio.core.*;
import dev.t1m3.qplayer.audio.decode.*;
import dev.t1m3.qplayer.audio.mix.TuningException;
import dev.t1m3.qplayer.audio.mix.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AutoMixRenderTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
    @Before public void decoder() {
        Decoder.install((source, headers) -> {
            opened.incrementAndGet();
            return new Tone(source.equals("A") ? 8000 : 11025, source.equals("A") ? 1 : 2, closed);
        });
    }
    @After public void reset() { Decoder.install(null); }
    private AutoMixConfig config(int mode) {
        return new AutoMixConfig(true, mode, 1, 2, true, 50, 100, false, "");
    }
    @Test public void realEngineRendersResampledStereoAndContinuousNextSong() throws Exception {
        AutoMixRender r = AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                config(0), temp.getRoot().toPath(), () -> true);
        byte[] data = Files.readAllBytes(r.file);
        ByteBuffer wav = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(data, 0, 4, "US-ASCII"));
        assertEquals(44100, wav.getInt(24));
        assertEquals(2, wav.getShort(22));
        assertEquals(data.length - 44, wav.getInt(40));
        long expectedMs = r.durationMs - r.entryMs + r.leadInMs;
        assertEquals(expectedMs, (data.length - 44) * 1000L / (44100 * 4), 3);
        assertTrue(r.overlapMs > 0);
        assertTrue(r.exitMs < 20000);
        assertTrue(r.exitMs >= Math.ceil(20000 * AutoCue.MIN_PLAYED_FRACTION));
        // The last second is B's unbroken tail at its original amplitude, not silence.
        double power = 0;
        for (int offset = data.length - 44100 * 4; offset < data.length; offset += 4) {
            double sample = wav.getShort(offset) / 32768d;
            power += sample * sample;
            assertEquals(wav.getShort(offset), wav.getShort(offset + 2));
        }
        assertEquals(.2 / Math.sqrt(2), Math.sqrt(power / 44100), .015);
        assertEquals(opened.get(), closed.get());
        r.delete(); assertFalse(Files.exists(r.file));
    }
    @Test public void cancellationDeletesPartialOutputAndClosesDecoders() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        try {
            AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(), config(3),
                    temp.getRoot().toPath(), () -> checks.incrementAndGet() < 8);
            fail("expected cancellation");
        } catch (InterruptedIOException expected) { }
        assertEquals(opened.get(), closed.get());
        try (java.util.stream.Stream<Path> files = Files.list(temp.getRoot().toPath())) {
            assertEquals(0, files.count());
        }
    }
    @Test public void incomingPitchWarmupPreservesLosslessTailLength() throws Exception {
        Decoder.install((source, headers) -> new Tone(44100, 2, closed));
        Transition pitch = new Transition() {
            public long windowFrames(int rate) { return 2L * rate; }
            public String name() { return "incoming pitch tail regression"; }
            public boolean rewritesSamples() { return true; }
            public TransitionPlan prepare(String a, String b, int rate, Map<String,String> ah, Map<String,String> bh) {
                return new TransitionPlan(7.9270068027210865, new PitchPlan(0, -2), 0, "");
            }
            public PitchPlan pitchAt(float p) { return new PitchPlan(0, -2 * (1-p)); }
            public int render(AudioBuf out, AudioBuf a, AudioBuf b, float p) { return Mixer.mixTwo(out,a,0,b,1); }
        };
        AutoMixRender r = AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                pitch, temp.getRoot().toPath(), () -> true);
        try {
            assertEquals(Math.round((20 - 7.9270068027210865 + 3) * 44100), (Files.size(r.file)-44)/4, 2);
        } finally { r.delete(); }
    }
    @Test public void compressedNextSongEndsAtDecodedEosWithoutDiscardingRenderOrAddingSilence() throws Exception {
        for (int rate : new int[] {44100, 48000}) {
            Decoder.install((source, headers) -> {
                opened.incrementAndGet();
                return new Tone(rate, 2, closed, source.equals("B") ? 2112 : 0, "audio/mp4a-latm", false);
            });
            AutoMixRender r = AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                    new AutoMixConfig(true, 0, 0, 2, true, 50, 100, false, "", false),
                    temp.getRoot().toPath(), () -> true);
            try {
                long actualFrames = (Files.size(r.file) - 44) / 4;
                long expectedFrames = Math.round((20 - 2112d / rate) * 44100) + 3 * 44100;
                assertEquals(expectedFrames, actualFrames, 2);
                ByteBuffer wav = ByteBuffer.wrap(Files.readAllBytes(r.file)).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(.2 / Math.sqrt(2), rms(wav, (int) actualFrames - 441, 441), .025);
            } finally { r.delete(); }
        }
        assertEquals(opened.get(), closed.get());
    }
    @Test public void compressedOutgoingPaddingDoesNotInvalidateTailMix() throws Exception {
        Decoder.install((source, headers) -> {
            opened.incrementAndGet();
            return new Tone(44100, 2, closed, source.equals("A") ? 1152 : 0, "audio/mpeg", false);
        });
        AutoMixRender r = AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                new AutoMixConfig(true, 0, 0, 2, true, 50, 100, false, "", false),
                temp.getRoot().toPath(), () -> true);
        try {
            assertEquals(18000, r.exitMs);
            assertEquals(2000, r.overlapMs);
            assertEquals(23 * 44100, (Files.size(r.file) - 44) / 4);
        } finally { r.delete(); }
        assertEquals(opened.get(), closed.get());
    }
    @Test public void trulyTruncatedOrLosslessSourcesStillFailAndDeletePartialRender() throws Exception {
        for (boolean lossless : new boolean[] {false, true}) {
            Decoder.install((source, headers) -> {
                opened.incrementAndGet();
                return new Tone(44100, 2, closed, source.equals("B") ? (lossless ? 2112 : 88200) : 0,
                        lossless ? "audio/flac" : "audio/mpeg", lossless);
            });
            try {
                AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                        new AutoMixConfig(true, 0, 0, 2, true, 50, 100, false, "", false),
                        temp.getRoot().toPath(), () -> true);
                fail("Truncated audio must not become a successful render");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("ended before declared duration"));
            }
            try (java.util.stream.Stream<Path> files = Files.list(temp.getRoot().toPath())) {
                assertEquals(0, files.count());
            }
        }
        assertEquals(opened.get(), closed.get());
    }
    @Test public void timelineUsesOriginalSongAndClampsEnd() {
        AutoMixRender r = new AutoMixRender(temp.getRoot().toPath(), "original", 8000, 2000, 20000, 6000);
        assertEquals(2000, r.songPosition(0));
        assertEquals(9500, r.songPosition(7500));
        assertEquals(20000, r.songPosition(25000));
    }
    @Test public void dryPrefixIsCurrentSongOnlyAndDoesNotCountAsNextSongTime() throws Exception {
        AutoMixRender r = AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                config(0), temp.getRoot().toPath(), () -> true);
        AutoMixRender retained = r.retain();
        try {
            assertEquals(3000, r.leadInMs);
            assertEquals(r.leadInMs, retained.leadInMs);
            assertEquals(r.entryMs, r.songPosition(0));
            assertEquals(r.entryMs, r.songPosition(2999));
            assertEquals(r.entryMs + 500, r.songPosition(3500));
            ByteBuffer wav = ByteBuffer.wrap(Files.readAllBytes(r.file)).order(ByteOrder.LITTLE_ENDIAN);
            int frames = (int) (r.leadInMs * 44100 / 1000);
            long sourceFrame = Math.round((r.exitMs - r.leadInMs) * 8000d / 1000);
            // The beginning is A's original 220Hz waveform, not silence or B's entry.
            for (int frame = 0; frame < frames; frame += 441) {
                double expected = .2 * Math.sin(2 * Math.PI * 220 * (sourceFrame / 8000d + frame / 44100d));
                assertEquals(expected, wav.getShort(44 + frame * 4) / 32768d, .012);
            }
        } finally { retained.delete(); r.delete(); }
    }
    @Test public void disablingAutoCuePreservesIntroAndTailInEveryMixMode() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            AutoMixConfig settings = new AutoMixConfig(true, mode, 0, 2, true, 50, 100, false, "", false);
            AutoMixRender r = AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                    settings, temp.getRoot().toPath(), () -> true);
            assertEquals(0, r.entryMs);
            assertEquals(20000, r.exitMs + r.overlapMs, 2);
            r.delete();
        }
    }
    @Test public void harmonicAndDefaultAiModesProducePlayableContinuations() throws Exception {
        for (int mode : new int[] {1, 2}) {
            AutoMixConfig settings = new AutoMixConfig(true, mode, 0, 6, false, 50, 100, true, "");
            AutoMixRender r = AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                    settings, temp.getRoot().toPath(), () -> true);
            assertTrue(r.overlapMs > 0);
            assertTrue(Files.size(r.file) > 44);
            assertTrue(r.entryMs < r.durationMs);
            assertTrue("mode " + mode + " must play at least 5/7 of A",
                    r.exitMs >= Math.ceil(20000 * AutoCue.MIN_PLAYED_FRACTION));
            r.delete();
        }
        assertEquals(opened.get(), closed.get());
    }
    @Test public void illegalPreparedCueNeverSilentlyReplacesTheConfiguredEffect() throws Exception {
        try {
            AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                    earlyEngine(2, 17), temp.getRoot().toPath(), () -> true);
            fail("An illegal engine plan must not become a generic crossfade");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("five sevenths"));
        }
        assertEquals(opened.get(), closed.get());
    }
    @Test public void oversizedWindowShrinksToFitAfterFiveSeventhsAndStillRenders() throws Exception {
        AutoMixRender r = AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                new AutoMixConfig(true, 0, 0, 15, true, 50, 100, false, "", false),
                temp.getRoot().toPath(), () -> true);
        try {
            assertTrue(r.exitMs >= Math.ceil(20000 * AutoCue.MIN_PLAYED_FRACTION));
            assertEquals(20000, r.exitMs + r.overlapMs, 1);
            assertTrue(r.overlapMs > 0);
            assertEquals(0, r.entryMs);
            assertTrue(Files.size(r.file) > 44);
        } finally { r.delete(); }
        assertEquals(opened.get(), closed.get());
    }
    @Test public void fixedWindowIsHonoredInAllThreeModesWithAndWithoutAutoCue() throws Exception {
        for (int mode = 0; mode < 3; mode++) for (boolean cue : new boolean[] {false, true}) {
            AutoMixRender r = AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                    new AutoMixConfig(true, mode, 0, 3, true, 50, 200, true, "", cue),
                    temp.getRoot().toPath(), () -> true);
            try {
                assertEquals("mode=" + mode + ", cue=" + cue, 3000, r.overlapMs);
                assertTrue(r.exitMs >= Math.ceil(20000 * AutoCue.MIN_PLAYED_FRACTION));
                assertEquals(r.durationMs - r.entryMs + r.leadInMs,
                        (Files.size(r.file) - 44) * 1000 / (44100 * 4), 2);
            } finally { r.delete(); }
        }
    }
    @Test public void twentySecondWindowMixesAcrossTheWholeIntervalInEveryBlendMode() throws Exception {
        Decoder.install((source, headers) -> new Tone(8000, 1, closed, 0, "tone", true,
                140, source.equals("A") ? 220 : 440));
        for (int mode = 0; mode < 3; mode++) for (boolean cue : new boolean[] {false, true}) {
            AutoMixRender r = AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                    new AutoMixConfig(true, mode, 1, 20, true, 50, 200, true, "", cue),
                    temp.getRoot().toPath(), () -> true);
            try {
                assertEquals("mode=" + mode + ", cue=" + cue, 20000, r.overlapMs);
                assertTrue(r.exitMs >= 100000);
                ByteBuffer wav = ByteBuffer.wrap(Files.readAllBytes(r.file)).order(ByteOrder.LITTLE_ENDIAN);
                // Measure each source's frequency in the finished PCM: B must
                // already be audible after five seconds, and A must remain in
                // the blend at fifteen seconds, rather than a six-second cut.
                int first = (int) ((r.leadInMs + 5000) * 44100 / 1000);
                int late = (int) ((r.leadInMs + 15000) * 44100 / 1000);
                assertTrue("incoming missing early: mode=" + mode + ", cue=" + cue,
                        toneAmplitude(wav, first, 4410, 440) > .03);
                assertTrue("outgoing ended early: mode=" + mode + ", cue=" + cue,
                        toneAmplitude(wav, late, 4410, 220) > .03);
            } finally { r.delete(); }
        }
    }

    private static double toneAmplitude(ByteBuffer wav, int first, int count, double hz) {
        double sin = 0, cos = 0;
        for (int i = 0; i < count; i++) {
            double sample = wav.getShort(44 + (first + i) * 4) / 32768d;
            double phase = 2 * Math.PI * hz * i / 44100;
            sin += sample * Math.sin(phase);
            cos += sample * Math.cos(phase);
        }
        return 2 * Math.hypot(sin, cos) / count;
    }
    @Test public void internalSegmentBoundariesAndFinalOverlapBlockDoNotSkipIncomingSamples() throws Exception {
        Decoder.install((source, headers) -> new Tone(44100, 2, closed));
        AtomicInteger calls = new AtomicInteger();
        Transition segmented = new Transition() {
            public long windowFrames(int rate) { return 8L * rate + 317; }
            public int renderChunkFrames(int requested) { return Math.min(137, requested); }
            public boolean suppliesOutgoingAudio() { return true; }
            public double outgoingStartSecs(double duration, TransitionPlan plan, int rate) { return 18; }
            public String name() { return "segmented material regression"; }
            public boolean rewritesSamples() { return true; }
            public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
                calls.incrementAndGet();
                return Mixer.mixTwo(out, from, 0, to, 1);
            }
        };
        AutoMixRender r = AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                segmented, temp.getRoot().toPath(), () -> true);
        try {
            assertEquals(18000, r.exitMs);
            assertTrue(calls.get() > 2000);
            ByteBuffer wav = ByteBuffer.wrap(Files.readAllBytes(r.file)).order(ByteOrder.LITTLE_ENDIAN);
            long lead = r.leadInMs * 44100 / 1000;
            for (int frame = 0; frame < 20 * 44100; frame += 97) {
                double expected = .2 * Math.sin(2 * Math.PI * 220 * frame / 44100d);
                assertEquals("sample " + frame, expected,
                        wav.getShort((int) (44 + (lead + frame) * 4)) / 32768d, .0001);
            }
        } finally { r.delete(); }
    }
    @Test public void fixedWindowPreservesSelectedAiStylesInsteadOfUsingTheirFallbackSeconds() throws Exception {
        for (int style : new int[] {1, 2, 3, 4, 5, 12, 23}) {
            AutoMixRender r = AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                    new AutoMixConfig(true, 2, style, 2, true, 75, 200, true, ""),
                    temp.getRoot().toPath(), () -> true);
            try {
                assertEquals("style=" + style, 2000, r.overlapMs);
                assertTrue(r.exitMs >= 14286);
            } finally { r.delete(); }
        }
    }
    private static Transition earlyEngine(int seconds, double tail) {
        return new Transition() {
            public long windowFrames(int rate) { return rate * (long) seconds; }
            public String name() { return "early cue regression"; }
            public boolean rewritesSamples() { return true; }
            public TransitionPlan prepare(String a, String b, int rate, Map<String,String> ah, Map<String,String> bh) {
                return new TransitionPlan(2, PitchPlan.ZERO, 0, "", tail);
            }
            public int render(AudioBuf out, AudioBuf from, AudioBuf to, float p) {
                throw new AssertionError("The engine prepared for an early cue must not render stale slices");
            }
        };
    }
    @Test(expected = TuningException.class) public void invalidAdvancedSettingIsRejected() {
        new AutoMixConfig(true, 2, 0, 6, false, 50, 100, true, "not_a_parameter=1");
    }
    @Test public void staleSessionCleanupOnlyRemovesOwnedWaves() throws Exception {
        Path directory = temp.getRoot().toPath();
        Files.write(directory.resolve("mix-stale.wav"), new byte[1]);
        Files.write(directory.resolve("song.wav"), new byte[1]);
        AutoMixRender.cleanPreviousSession(directory);
        assertFalse(Files.exists(directory.resolve("mix-stale.wav")));
        assertTrue(Files.exists(directory.resolve("song.wav")));
    }
    @Test public void pitchGlideHasNoColdStartDipAndStillLowersPitch() throws Exception {
        Decoder.install((source, headers) -> new Tone(44100, 2, closed));
        Transition glide = new Transition() {
            public long windowFrames(int rate) { return rate * 2L; }
            public String name() { return "pitch glide regression"; }
            public boolean rewritesSamples() { return true; }
            public TransitionPlan prepare(String a, String b, int rate, Map<String,String> ah, Map<String,String> bh) {
                return new TransitionPlan(2, new PitchPlan(-4, 0), 0, "");
            }
            public PitchPlan pitchAt(float p) {
                float t = Math.min(1, Math.max(0, p * 4));
                float returning = Math.min(1, Math.max(0, (1 - p) * 4));
                return new PitchPlan(-4 * t * t * (3 - 2 * t)
                        * returning * returning * (3 - 2 * returning), 0);
            }
            public int render(AudioBuf out, AudioBuf from, AudioBuf to, float p) {
                return Mixer.mixTwo(out, from, 1, to, 0);
            }
        };
        AutoMixRender r = AutoMixRender.prepare("A", "B", Collections.emptyMap(), Collections.emptyMap(),
                glide, temp.getRoot().toPath(), () -> true);
        try {
            byte[] data = Files.readAllBytes(r.file);
            ByteBuffer wav = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int lead = (int) (r.leadInMs * 44100 / 1000);
            // The first 10ms used to fall almost silent while the FFT window filled.
            assertEquals(.2 / Math.sqrt(2), rms(wav, lead, 441), .025);
            for (int first = 0; first < 22050; first += 882)
                assertTrue("glide must not insert a silent dip at " + first,
                        rms(wav, lead + first, 882) > .075);
            // Real DSP must still transpose the 220Hz tone by four semitones.
            int crossings = 0, start = lead + 26460, end = start + 22050;
            for (int frame = start + 1; frame < end; frame++)
                if (wav.getShort(44 + (frame - 1) * 4) <= 0 && wav.getShort(44 + frame * 4) > 0)
                    crossings++;
            assertEquals(220 * Math.pow(2, -4d / 12), crossings * 2d, 4);
            assertEquals(18000 + r.leadInMs, (data.length - 44) * 1000L / (44100 * 4), 2);
            assertEquals(.2 / Math.sqrt(2), rms(wav, (data.length - 44) / 4 - 44100, 44100), .015);
        } finally { r.delete(); }
    }

    private static double rms(ByteBuffer wav, int first, int count) {
        double sum = 0;
        for (int frame = first; frame < first + count; frame++) {
            double sample = wav.getShort(44 + frame * 4) / 32768d;
            sum += sample * sample;
        }
        return Math.sqrt(sum / count);
    }

    @Test public void parkedAndActivePlayersReleaseIndependentHandles() throws Exception {
        Path file = temp.newFile("mix-owned.wav").toPath();
        AutoMixRender cached = new AutoMixRender(file, "cached-source", 8000, 2000, 20000, 6000);
        AutoMixRender controller = cached.withOriginal("original-stream");
        cached.delete();
        AutoMixRender parked = controller.retain();
        parked.delete(); parked.delete();
        assertTrue(Files.exists(file));
        AutoMixRender active = controller.retain();
        controller.delete(); controller.delete();
        assertEquals("original-stream", active.original);
        assertEquals(2000, active.songPosition(0));
        assertTrue(Files.exists(file));
        active.delete();
        assertFalse(Files.exists(file));
    }
    @Test(expected = IllegalStateException.class) public void releasedRenderCannotBeRearmed() throws Exception {
        AutoMixRender r = new AutoMixRender(temp.newFile("mix-released.wav").toPath(), "B",
                8000, 2000, 20000, 6000);
        r.delete();
        r.retain();
    }
    private static final class Tone implements Decoder {
        final int rate, channels; final AtomicInteger closed;
        final long actualFrames;
        final String codec;
        final boolean lossless;
        final int seconds;
        final double frequency;
        long frame;
        Tone(int rate, int channels, AtomicInteger closed) {
            this(rate, channels, closed, 0, "tone", true);
        }
        Tone(int rate, int channels, AtomicInteger closed, int trimmed, String codec, boolean lossless) {
            this(rate, channels, closed, trimmed, codec, lossless, 20, 220);
        }
        Tone(int rate, int channels, AtomicInteger closed, int trimmed, String codec, boolean lossless,
                int seconds, double frequency) {
            this.rate = rate; this.channels = channels; this.closed = closed;
            this.seconds = seconds; this.frequency = frequency;
            this.actualFrames = rate * (long) seconds - trimmed;
            this.codec = codec; this.lossless = lossless;
        }
        public TrackSpec spec() { return new TrackSpec(codec, rate, ChannelLayout.of(channels),
                16, rate * (long) seconds, lossless); }
        public long framesDecoded() { return frame; }
        public boolean isDrained() { return frame >= actualFrames; }
        public double seek(double seconds) { frame = Math.round(seconds * rate); return frame / (double) rate; }
        public int decodeInto(AudioBuf out) {
            int n = (int) Math.min(out.capacityFrames(), Math.max(0, actualFrames - frame));
            for (int c = 0; c < channels; c++) for (int i = 0; i < n; i++)
                out.backingArray()[out.channelBase(c) + i] = (float) (.2 * Math.sin(2 * Math.PI * frequency * (frame + i) / rate));
            out.setFrames(n); frame += n; return n;
        }
        public void close() { closed.incrementAndGet(); }
    }
}
