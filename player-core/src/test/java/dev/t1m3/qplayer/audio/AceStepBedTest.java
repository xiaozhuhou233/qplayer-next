package dev.t1m3.qplayer.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Random;

/**
 * The cloud call ({@link AceStepBed}) as arithmetic and bytes: the geometry the request is built
 * on, the buffer and its WAV, the body the cloud is actually sent, the answer's own format, the
 * server's normalisation measured off a preserved region and divided out again, the repainted span
 * cut out with a couple of frames to spare, and the level the bed is placed at.
 *
 * <p>Every test here is synthetic and deterministic: the "cloud" is this file's own resampling and
 * scaling of the material that was sent, so a number asserted below is a statement about the
 * decision rather than about a model run. What the real service returns is a device measurement (it
 * costs a request, and the numbers real runs produced are in {@link StemBed}'s javadoc); what has
 * to hold whatever it returns is here.
 */
public class AceStepBedTest {

    private static final int SENT_RATE = 44_100;
    private static final int CLOUD_RATE = 48_000;

    /** The pair the design was measured on: the passage's window is three bars of the incoming's
     *  grid plus the 80 ms splice, its landing is the incoming's own 362 ms, the junction is where
     *  the outgoing's deck is cut, and the passage hands back to the incoming at 6574 ms — the app's
     *  own numbers for coldplay → unhappy (see the PC demo, {@code harness/acestep/bed_pad_demo.py},
     *  whose {@code B_FROM_MS} is this {@code fusionEndMs}). */
    private static final long WINDOW_MS = 6_212L;
    private static final long JUNCTION_MS = 167_046L;
    private static final long FUSION_END_MS = 6_574L;
    private static final long A_DURATION_MS = 179_900L;
    private static final long B_DURATION_MS = 98_100L;

    /** One bar of the incoming track's own grid, ms — what {@link StemBed} wants for its fades. */
    private static final long BAR_MS = 2_044L;

    private static void println(String format, Object... args) {
        System.out.println(String.format(Locale.US, format, args));
    }

    /** {@code assertTrue(why, condition)} in the order this file reads it. */
    private static void ok(boolean condition, String why) {
        assertTrue(why, condition);
    }

    /**
     * Deterministic material with a body: a continuous bed under a burst that decays on every beat,
     * plus a noise floor beneath both — events with material between them, which is what real music
     * measures 84–100% occupied as and therefore what {@link StemBed}'s clause accepts.
     *
     * <p>⚠️ The two noise terms are low-passed ({@link #lowpass}) and their level restored, and that
     * is not cosmetic: this file's synthetic "cloud" resamples with the app's own linear
     * interpolator, which droops a few tenths of a dB on wideband material — enough to shift a gain
     * measurement by more than the tolerance below. A band-limited material measures the arithmetic
     * instead of the test's own resampler.
     */
    private static float[][] music(double seconds, int rate, double peak, double floor, long seed) {
        int frames = (int) Math.round(seconds * rate);
        int period = (int) Math.round(0.5d * rate);
        int burst = (int) Math.round(0.12d * rate);
        float[][] out = new float[2][frames];
        for (int ch = 0; ch < 2; ch++) {
            Random random = new Random(seed + ch);
            float[] bed = new float[frames];
            float[] hiss = new float[frames];
            for (int i = 0; i < frames; i++) {
                bed[i] = (float) (random.nextDouble() * 2d - 1d);
                hiss[i] = (float) (random.nextDouble() * 2d - 1d);
            }
            bed = lowpass(bed);
            hiss = lowpass(hiss);
            for (int i = 0; i < frames; i++) {
                out[ch][i] = (float) (floor * hiss[i] + 0.15d * peak * bed[i]);
            }
            for (int at = 0; at + burst < frames; at += period) {
                for (int i = 0; i < burst; i++) {
                    double env = Math.exp(-3d * i / (double) burst);
                    out[ch][at + i] += (float) (peak * env
                            * Math.sin(2d * Math.PI * 330d * i / rate));
                }
            }
        }
        return out;
    }

    /** An 8-sample moving average with its own level restored, so the material keeps its RMS and
     *  loses only the high frequencies a linear resampler would eat. */
    private static float[] lowpass(float[] pcm) {
        int taps = 8;
        float[] out = new float[pcm.length];
        double sum = 0d;
        for (int i = 0; i < pcm.length; i++) {
            sum += pcm[i];
            if (i >= taps) sum -= pcm[i - taps];
            out[i] = (float) (sum / taps * Math.sqrt(taps));
        }
        return out;
    }

    private static float[][] music(double seconds, int rate, double peak, long seed) {
        return music(seconds, rate, peak, 0.01d, seed);
    }

    /** Digital silence, as the buffer's own gap is. */
    private static float[][] silence(long ms, int rate) {
        return new float[2][AceStepBed.frames(ms, rate)];
    }

    private static float[][] scaled(float[][] pcm, double gain) {
        float[][] out = new float[pcm.length][pcm[0].length];
        for (int ch = 0; ch < pcm.length; ch++) {
            for (int i = 0; i < out[ch].length; i++) out[ch][i] = (float) (gain * pcm[ch][i]);
        }
        return out;
    }

    /** Linear resampling, the instrument the renderer uses (and the one the PC demo used). */
    private static float[][] resample(float[][] pcm, int fromRate, int toRate) {
        if (fromRate == toRate) return pcm;
        int target = (int) Math.floor(pcm[0].length * (double) toRate / fromRate);
        float[][] out = new float[pcm.length][target];
        double step = fromRate / (double) toRate;
        for (int i = 0; i < target; i++) {
            double at = i * step;
            int lo = (int) at;
            int hi = Math.min(lo + 1, pcm[0].length - 1);
            double frac = at - lo;
            for (int ch = 0; ch < pcm.length; ch++) {
                out[ch][i] = (float) (pcm[ch][lo] * (1 - frac) + pcm[ch][hi] * frac);
            }
        }
        return out;
    }

    /** What the cloud does to a buffer: to 48 kHz, the whole file scaled by the server's own peak
     *  normalisation, and the repainted window replaced by material of its own. */
    private static float[][] asTheCloudWould(float[][] sent, int sentRate, double gainDb,
                                             float[][] generated, long tailMs) {
        float[][] out = scaled(resample(sent, sentRate, CLOUD_RATE),
                Math.pow(10d, gainDb / 20d));
        float[][] gen = resample(generated, sentRate, CLOUD_RATE);
        int from = AceStepBed.frames(tailMs, CLOUD_RATE);
        for (int i = 0; i < gen[0].length && from + i < out[0].length; i++) {
            for (int ch = 0; ch < out.length; ch++) out[ch][from + i] = gen[ch][i];
        }
        return out;
    }

    /** One returned buffer as the decoder would hand it over. */
    private static AceStepBed.Wav returned(float[][] pcm) {
        AceStepBed.Wav wav = new AceStepBed.Wav();
        wav.ok = true;
        wav.format = "wav";
        wav.rate = CLOUD_RATE;
        wav.channels = 2;
        wav.pcm = pcm;
        wav.frames = pcm[0].length;
        return wav;
    }

    private static AceStepBed.Geometry thePairsGeometry() {
        AceStepBed.Geometry g = AceStepBed.geometry(JUNCTION_MS, FUSION_END_MS, WINDOW_MS,
                A_DURATION_MS, B_DURATION_MS, SENT_RATE);
        ok(g.valid, g.why);
        return g;
    }

    private static float[][] thePairsTail() {
        return music(12d, SENT_RATE, 0.6d, 3L);
    }

    private static float[][] thePairsHead() {
        return music(12d, SENT_RATE, 0.5d, 5L);
    }

    // --- the geometry --------------------------------------------------------

    @Test
    public void theRightContextStartsWhereThePassageHandsBackAndTheSilenceIsTheWindow() {
        AceStepBed.Geometry g = thePairsGeometry();
        println("%s", g.describe());

        // the window IS the silence, to the millisecond — the one number the design says must not be
        // approximated
        assertEquals(WINDOW_MS, g.gapMs);
        assertEquals(WINDOW_MS, (g.repaintEndSec() - g.repaintStartSec()) * 1000d, 1e-9d);

        // the left context ends on the junction; the right one starts at the passage's own hand-back
        assertEquals(AceStepBed.CONTEXT_MS, g.tailMs);
        assertEquals(JUNCTION_MS - AceStepBed.CONTEXT_MS, g.aFromMs);
        assertEquals(JUNCTION_MS, g.aFromMs + g.tailMs);
        assertEquals(FUSION_END_MS, g.bFromMs);
        // ⚠️ and NOT the incoming's offset 0 — the correction round 32 made
        assertFalse("the right context was taken from the incoming's own start", g.bFromMs == 0L);
        assertEquals(AceStepBed.CONTEXT_MS, g.headMs);

        // the repaint window covers exactly the silence we inserted, in seconds from the buffer
        assertEquals(g.tailMs / 1000d, g.repaintStartSec(), 1e-9d);
        assertEquals((g.tailMs + g.gapMs) / 1000d, g.repaintEndSec(), 1e-9d);
        assertEquals(12.0d, g.repaintStartSec(), 1e-9d);
        assertEquals(18.212d, g.repaintEndSec(), 1e-6d);
        assertEquals(g.tailMs + g.gapMs + g.headMs, g.bufferMs());
        assertEquals(AceStepBed.frames(WINDOW_MS, SENT_RATE), g.windowFrames());
    }

    @Test
    public void aNeighbourTooShortToConditionOnIsRefusedBeforeTheCall() {
        // the junction is too early in the outgoing's file for a tail to be taken
        AceStepBed.Geometry early = AceStepBed.geometry(1_000L, FUSION_END_MS, WINDOW_MS,
                A_DURATION_MS, B_DURATION_MS, SENT_RATE);
        println("%s", early.why);
        assertFalse(early.valid);
        assertTrue(early.why, early.why.contains("1000ms of the outgoing's file"));

        // the passage hands back too near the incoming's end
        AceStepBed.Geometry late = AceStepBed.geometry(JUNCTION_MS, B_DURATION_MS - 800L, WINDOW_MS,
                A_DURATION_MS, B_DURATION_MS, SENT_RATE);
        println("%s", late.why);
        assertFalse(late.valid);
        assertTrue(late.why, late.why.contains("under the 2000ms a context needs"));

        // and neither file's length being known is a refusal of its own
        assertFalse(AceStepBed.geometry(JUNCTION_MS, FUSION_END_MS, WINDOW_MS, 0L, B_DURATION_MS,
                SENT_RATE).valid);
        assertFalse(AceStepBed.geometry(JUNCTION_MS, FUSION_END_MS, WINDOW_MS, A_DURATION_MS, 0L,
                SENT_RATE).valid);

        // a short file caps the context rather than refusing, as long as enough is left
        AceStepBed.Geometry shortTail = AceStepBed.geometry(5_000L, FUSION_END_MS, WINDOW_MS,
                A_DURATION_MS, B_DURATION_MS, SENT_RATE);
        ok(shortTail.valid, shortTail.why);
        assertEquals(5_000L, shortTail.tailMs);
        assertEquals(0L, shortTail.aFromMs);
        assertEquals(5_000L, shortTail.aFromMs + shortTail.tailMs);
    }

    // --- the buffer and the body --------------------------------------------

    @Test
    public void theBodyIsTheRepaintRequestAndItsBufferDecodesBackToWhatWasSent() {
        AceStepBed.Geometry g = thePairsGeometry();
        float[][] tail = thePairsTail();
        float[][] head = thePairsHead();
        float[][] buffer = AceStepBed.assemble(tail, head, g.windowFrames());
        assertEquals(AceStepBed.frames(g.bufferMs(), SENT_RATE), buffer[0].length);

        // the gap is DIGITAL silence and the two contexts sit where they should
        double middle = 0d;
        for (int i = 0; i < g.windowFrames(); i++) {
            middle = Math.max(middle, Math.abs(buffer[0][tail[0].length + i]));
        }
        assertEquals(0d, middle, 0d);
        assertEquals(tail[0][0], buffer[0][0], 0d);
        assertEquals(head[0][0], buffer[0][tail[0].length + g.windowFrames()], 0d);

        byte[] wav = AceStepBed.wav(buffer, SENT_RATE);
        AceStepBed.Config cfg = AceStepBed.config(true, "https://api.acemusic.ai", "k",
                "acestep-v1.5-turbo", 18d);
        String body = AceStepBed.body(cfg, g, wav);

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("repaint", root.get("task_type").getAsString());
        assertFalse(root.get("stream").getAsBoolean());
        assertFalse(root.get("use_cot_caption").getAsBoolean());
        assertFalse(root.get("use_cot_language").getAsBoolean());
        // ⚠️ explicit, both of them: the server's defaults regenerate the whole file
        assertEquals(12.0d, root.get("repainting_start").getAsDouble(), 1e-9d);
        assertEquals(18.212d, root.get("repainting_end").getAsDouble(), 1e-6d);

        JsonObject audioConfig = root.getAsJsonObject("audio_config");
        assertEquals("wav", audioConfig.get("format").getAsString());
        assertTrue(audioConfig.get("instrumental").getAsBoolean());
        assertEquals(g.bufferMs() / 1000d, audioConfig.get("duration").getAsDouble(), 0.1d);

        // the model id is namespaced: the bare configured name is the spelling the cloud rejects
        assertEquals("acemusic/acestep-v1.5-turbo", root.get("model").getAsString());
        assertEquals("acemusic/acestep-v15-turbo", AceStepBed.config(true, "u", "k",
                "acemusic/acestep-v15-turbo", 18d).modelId());

        JsonArray content = root.getAsJsonArray("messages").get(0).getAsJsonObject()
                .getAsJsonArray("content");
        String text = content.get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text, text.contains("<prompt>"));
        // ⚠️ Round 19: the ask is a FUNCTION, and the two listening rounds are both pinned here. The
        // user's second report is the sharpest statement of it — 「不要让她延续前一首歌的歌曲做续写，
        // 而是做收尾然后变到第二首」 — so "ending, not a continuation" and "the second track's beginning"
        // are asserted as clauses of their own, and the continuity clauses from the first fix stay:
        // dropping any one of the four is a different request, not a rewording.
        // ⚠️ Round 23 changed the job again, and this one is the requirement the listener settled on:
        // percussion only, no melody at all, steady (the app's own occupancy clause refuses a sparse take —
        // measured: a steady groove 100.0%, "a minimal drum backing" 57.7% and refused), and no fade or
        // swell. Every earlier wording is asserted GONE, because each was a different job.
        assertTrue(text, text.contains("A drum layer and NOTHING else"));
        assertTrue(text, text.contains("Percussion only: no melody, no harmony"));
        assertTrue(text, text.contains("even and constant from start to finish"));
        assertTrue(text, text.contains("never fades in, never swells"));
        assertFalse(text.contains("Continue this audio seamlessly"));
        assertFalse(text.contains("FUSES the two tracks' tunes"));
        assertTrue(text, text.contains("[Instrumental]"));
        assertEquals("input_audio", content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("wav", content.get(1).getAsJsonObject().getAsJsonObject("input_audio")
                .get("format").getAsString());

        // and the buffer inside it is the one we assembled, sample for sample
        String sent64 = content.get(1).getAsJsonObject().getAsJsonObject("input_audio")
                .get("data").getAsString();
        AceStepBed.Wav decoded = AceStepBed.wav(Base64.getDecoder().decode(sent64));
        ok(decoded.ok, decoded.why);
        println("%s", decoded.describe());
        assertEquals(SENT_RATE, decoded.rate);
        assertEquals(2, decoded.channels);
        assertEquals(buffer[0].length, decoded.frames);
        double worst = 0d;
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < buffer[ch].length; i++) {
                worst = Math.max(worst, Math.abs(buffer[ch][i] - decoded.pcm[ch][i]));
            }
        }
        println("the buffer survives the WAV round trip to %.2e (16-bit quantisation is 3.05e-5)",
                worst);
        ok(worst < 4e-5d, "the buffer did not survive its own WAV encoding: " + worst);
    }

    @Test
    public void theEndpointTakesTheConfiguredAddressWithOrWithoutItsVersionPart() {
        assertEquals("https://api.acemusic.ai/v1/chat/completions",
                AceStepBed.config(true, "https://api.acemusic.ai", "k", "m", 18d).url());
        assertEquals("https://api.acemusic.ai/v1/chat/completions",
                AceStepBed.config(true, "https://api.acemusic.ai/", "k", "m", 18d).url());
        assertEquals("http://127.0.0.1:8002/v1/chat/completions",
                AceStepBed.config(true, "http://127.0.0.1:8002/v1", "k", "m", 18d).url());

        // a key is never in the description of the settings, only whether one is there
        AceStepBed.Config cfg = AceStepBed.config(true, "https://api.acemusic.ai", "sk-secret",
                "acestep-v1.5-turbo", 18d);
        ok(cfg.configured(), cfg.describe());
        println("%s", cfg.describe());
        assertFalse(cfg.describe(), cfg.describe().contains("sk-secret"));
        assertTrue(cfg.describe(), cfg.describe().contains("key set"));

        // every one of the four is required, and the off switch is one of them
        assertFalse(AceStepBed.config(false, "https://api.acemusic.ai", "sk-secret", "m", 18d)
                .configured());
        assertFalse(AceStepBed.config(true, "", "sk-secret", "m", 18d).configured());
        assertFalse(AceStepBed.config(true, "https://api.acemusic.ai", "", "m", 18d).configured());
        assertFalse(AceStepBed.config(true, "https://api.acemusic.ai", "sk-secret", "", 18d)
                .configured());
        assertFalse(AceStepBed.OFF.configured());
    }

    // --- what came back ------------------------------------------------------

    @Test
    public void theServerNormalisationIsMeasuredOnAPreservedRegionAndDividedOutAgain() {
        AceStepBed.Geometry g = thePairsGeometry();
        float[][] sent = AceStepBed.assemble(thePairsTail(), thePairsHead(), g.windowFrames());
        float[][] generated = music(WINDOW_MS / 1000d, SENT_RATE, 0.2d, 13L);

        // the server peak-normalises the WHOLE file to -1 dBFS: on this material -1.00 dB
        double serverDb = -1.0d;
        AceStepBed.Wav wav = returned(asTheCloudWould(sent, SENT_RATE, serverDb, generated,
                g.tailMs));

        assertNull(AceStepBed.lengthCheck(wav, g));
        AceStepBed.Gain gain = AceStepBed.gain(wav, sent, SENT_RATE, g);
        println("%s", gain.describe());
        ok(gain.measured, gain.why);
        assertEquals("the outgoing's tail", gain.from);
        println("measured %+.3f dB against the %+.3f dB applied", gain.db, serverDb);
        // ⚠️ 0.1 dB, not 0.01: this synthetic cloud resamples with the app's own linear
        // interpolator, which leaves a few hundredths of a dB on material this wide. What the
        // tolerance has to catch is a measurement taken off the wrong side, a wrong region or the
        // wrong signal — errors of decibels, not of hundredths.
        assertEquals(serverDb, gain.db, 0.1d);

        // dividing it out restores the preserved region to the bytes we sent — measured over the
        // stretch of the tail that is clear of the repaint seam on one side and of the file's start
        // on the other, and against the material at unity (the server's own gain is applied to
        // everything it returns, so what is compared here is the buffer against itself undivided)
        long regionFrom = g.tailMs - AceStepBed.SEAM_INSET_MS - 4_000L;
        long regionTo = g.tailMs - AceStepBed.SEAM_INSET_MS;
        float[][] atUnity = resample(sent, SENT_RATE, CLOUD_RATE);
        double untouched = spread(wav.pcm, atUnity, regionFrom, regionTo);
        AceStepBed.invert(wav.pcm, gain);
        double residual = spread(wav.pcm, atUnity, regionFrom, regionTo);
        double peak = peakOf(atUnity, regionFrom, regionTo);
        println("the preserved tail differs from what we sent by %.4f before the inversion (the"
                        + " server's %.2f dB) and %.5f after — %.2f%% of its own %.3f peak",
                untouched, serverDb, residual, 100d * residual / peak, peak);
        // within a hundredth of the material's own amplitude: the inversion undoes the gain the
        // measurement found, so what is left is the measurement's own error (a few hundredths of a
        // dB) times the material, not the server's decibel
        ok(residual < 0.01d * peak, "the inversion left " + residual + " of the server's gain in"
                + " the buffer against a peak of " + peak);
        ok(untouched > 10d * residual, "the inversion did not undo the gain: " + untouched + " -> "
                + residual);

        // and the span is the generated window, cut with slack and no more — at unity, because the
        // inversion above undid the server's gain on the WHOLE buffer and the generated window was
        // scaled by it too
        float[][] span = AceStepBed.span(wav, g);
        assertNotNull(span);
        int want = AceStepBed.frames(g.gapMs, CLOUD_RATE);
        assertEquals(want + AceStepBed.SLACK_FRAMES, span[0].length);
        float[][] up = scaled(resample(generated, SENT_RATE, CLOUD_RATE),
                Math.pow(10d, -gain.db / 20d));
        double spanWorst = 0d;
        for (int i = 0; i < Math.min(up[0].length, span[0].length); i++) {
            spanWorst = Math.max(spanWorst, Math.abs(up[0][i] - span[0][i]));
        }
        println("the span is the repainted window to %.2e (it starts %dms into the buffer)",
                spanWorst, g.tailMs);
        ok(spanWorst < 1e-6d, "the span is not the repainted window: " + spanWorst);

        // and the material handed to StemBed is long enough for the window after the retiming —
        // which is what SLACK_FRAMES exists for
        float[][] material = resample(span, CLOUD_RATE, SENT_RATE);
        println("the material retimed to %dHz is %d frames against the window's %d",
                SENT_RATE, material[0].length, g.windowFrames());
        ok(material[0].length >= g.windowFrames(), "the material is short of the window by "
                + (g.windowFrames() - material[0].length) + " frames, so StemBed would refuse it");
    }

    @Test
    public void aFadeOutOnTheLeftIsMeasuredOnTheIncomingSideInstead() {
        AceStepBed.Geometry g = thePairsGeometry();
        // 18 of 22 tracks in this library end in a fade to digital silence: the left region is then
        // no material at all, and the measurement has to come from the incoming's head
        float[][] sent = AceStepBed.assemble(silence(12_000L, SENT_RATE), thePairsHead(),
                g.windowFrames());
        AceStepBed.Wav wav = returned(asTheCloudWould(sent, SENT_RATE, -1.0d,
                music(WINDOW_MS / 1000d, SENT_RATE, 0.2d, 17L), g.tailMs));

        AceStepBed.Gain gain = AceStepBed.gain(wav, sent, SENT_RATE, g);
        println("%s", gain.describe());
        ok(gain.measured, gain.why);
        assertEquals("the incoming's head", gain.from);
        assertEquals(-1.0d, gain.db, 0.1d);

        // and when NEITHER side has material, the gain is refused rather than taken from noise
        float[][] bothSilent = AceStepBed.assemble(silence(12_000L, SENT_RATE),
                silence(12_000L, SENT_RATE), g.windowFrames());
        AceStepBed.Gain refused = AceStepBed.gain(wav, bothSilent, SENT_RATE, g);
        println("%s", refused.describe());
        assertFalse(refused.measured);
        assertTrue(refused.why, refused.why.contains("too quiet"));
    }

    @Test
    public void anAnswerToADifferentRequestIsRefusedBeforeAnythingIsMeasured() {
        AceStepBed.Geometry g = thePairsGeometry();
        AceStepBed.Wav shortBuffer = returned(music(20d, CLOUD_RATE, 0.5d, 3L));
        String why = AceStepBed.lengthCheck(shortBuffer, g);
        println("%s", why);
        assertNotNull(why);
        assertTrue(why, why.contains("where 30212ms was sent"));

        // and the two failures that skip the check entirely
        AceStepBed.Wav empty = new AceStepBed.Wav();
        assertNotNull(AceStepBed.lengthCheck(empty, g));
        assertNull(AceStepBed.span(empty, g));
        assertNull(AceStepBed.span(returned(new float[2][10]), g));
    }

    // --- the format the bytes really are -------------------------------------

    @Test
    public void theReturnedBytesAreSniffedAndTheDataUrlsLabelIsIgnored() {
        // what the server really sends when the request asked for wav, behind a data URL that
        // claims audio/mpeg
        byte[] wav = AceStepBed.wav(music(1d, CLOUD_RATE, 0.4d, 3L), CLOUD_RATE);
        assertEquals("wav", AceStepBed.formatOf(wav));
        AceStepBed.Wav decoded = AceStepBed.wav(wav);
        ok(decoded.ok, decoded.why);
        assertEquals(CLOUD_RATE, decoded.rate);
        assertEquals(2, decoded.channels);

        // a chunk in front of the audio (a LIST tag is ordinary) is walked over, not assumed away
        byte[] withList = new byte[wav.length + 14];
        System.arraycopy(wav, 0, withList, 0, 36);
        putAscii(withList, 36, "LIST");
        putInt(withList, 40, 6);
        putAscii(withList, 44, "INFO");
        putShort(withList, 48, 0);
        System.arraycopy(wav, 36, withList, 50, wav.length - 36);
        putInt(withList, 4, withList.length - 8);
        AceStepBed.Wav walked = AceStepBed.wav(withList);
        ok(walked.ok, walked.why);
        assertEquals(decoded.frames, walked.frames);
        assertEquals(decoded.pcm[0][1234], walked.pcm[0][1234], 0d);

        // anything that is not wav is refused by name: this project has no decoder for it, and the
        // data URL's own mime says nothing about the bytes
        byte[] mp3 = new byte[64];
        putAscii(mp3, 0, "ID3");
        assertEquals("mp3", AceStepBed.formatOf(mp3));
        AceStepBed.Wav refused = AceStepBed.wav(mp3);
        assertFalse(refused.ok);
        println("%s", refused.why);
        assertTrue(refused.why, refused.why.contains("mp3"));

        byte[] flac = new byte[64];
        putAscii(flac, 0, "fLaC");
        assertEquals("flac", AceStepBed.formatOf(flac));
        assertFalse(AceStepBed.wav(flac).ok);
        assertFalse(AceStepBed.wav(new byte[] {1, 2, 3}).ok);
        assertEquals("unknown", AceStepBed.formatOf(null));

        // a truncated answer is refused rather than partly decoded
        byte[] truncated = Arrays.copyOf(wav, 60);
        AceStepBed.Wav cut = AceStepBed.wav(truncated);
        assertFalse(cut.ok);
        println("%s", cut.why);
    }

    private static void putAscii(byte[] out, int at, String text) {
        for (int i = 0; i < text.length(); i++) out[at + i] = (byte) text.charAt(i);
    }

    private static void putInt(byte[] out, int at, int value) {
        out[at] = (byte) (value & 0xFF);
        out[at + 1] = (byte) ((value >> 8) & 0xFF);
        out[at + 2] = (byte) ((value >> 16) & 0xFF);
        out[at + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void putShort(byte[] out, int at, int value) {
        out[at] = (byte) (value & 0xFF);
        out[at + 1] = (byte) ((value >> 8) & 0xFF);
    }

    @Test
    public void theAudioOutOfTheAnswerIsTheDataUrlAndNothingElse() {
        String audio = Base64.getEncoder().encodeToString(
                AceStepBed.wav(music(1d, CLOUD_RATE, 0.3d, 3L), CLOUD_RATE));
        String answer = "{\"choices\":[{\"message\":{\"content\":\"here you go\",\"audio\":"
                + "[{\"audio_url\":{\"url\":\"data:audio/mpeg;base64," + audio + "\"}}]}}]}";
        AceStepBed.Reply reply = AceStepBed.reply(answer);
        ok(reply.ok, reply.why);
        assertEquals("here you go", reply.text);
        assertEquals("wav", AceStepBed.formatOf(reply.bytes));

        // every shape of "no audio" is a refusal with a reason, never an exception out of here
        assertFalse(AceStepBed.reply(null).ok);
        assertFalse(AceStepBed.reply("").ok);
        assertFalse(AceStepBed.reply("not json").ok);
        assertFalse(AceStepBed.reply("{\"choices\":[]}").ok);
        assertFalse(AceStepBed.reply("{\"choices\":[{\"message\":{}}]}").ok);
        assertFalse(AceStepBed.reply("{\"choices\":[{\"message\":{\"audio\":[]}}]}").ok);
        assertFalse(AceStepBed.reply(
                "{\"choices\":[{\"message\":{\"audio\":[{\"audio_url\":{}}]}}]}").ok);
        AceStepBed.Reply noData = AceStepBed.reply(
                "{\"choices\":[{\"message\":{\"audio\":[{\"audio_url\":{\"url\":\"https://x\"}}]}}]}");
        assertFalse(noData.ok);
        println("%s", noData.why);
        AceStepBed.Reply notBase64 = AceStepBed.reply("{\"choices\":[{\"message\":{\"audio\":"
                + "[{\"audio_url\":{\"url\":\"data:a/b;base64,****\"}}]}}]}");
        assertFalse(notBase64.ok);
        println("%s", notBase64.why);
    }

    // --- the level the bed lands at ------------------------------------------

    @Test
    public void theBedIsPlacedUnderThePassageAndTheKnobIsWhatMovesIt() {
        AceStepBed.Geometry g = thePairsGeometry();
        float[][] sent = AceStepBed.assemble(thePairsTail(), thePairsHead(), g.windowFrames());
        AceStepBed.Wav wav = returned(asTheCloudWould(sent, SENT_RATE, -1.0d,
                music(WINDOW_MS / 1000d, SENT_RATE, 0.25d, 19L), g.tailMs));
        AceStepBed.Gain gain = AceStepBed.gain(wav, sent, SENT_RATE, g);
        ok(gain.measured, gain.why);
        AceStepBed.invert(wav.pcm, gain);
        float[][] material = resample(AceStepBed.span(wav, g), CLOUD_RATE, SENT_RATE);

        // the passage's own level, as the renderer measures it off the rendered head
        double passageDb = -12.58d;
        // the two ends of the range, read off the constants: the bed's own level moved in round 18
        // (18 -> 12 dB under the passage), and a fixture that spells the old pair out refuses its own
        // subject the moment the knob moves again.
        for (double underDb : new double[] {StemBed.BED_LEVEL_DB, AceStepBed.MIN_UNDER_DB}) {
            // ⚠️ the real window against the real bar: 6212 ms of a 2043.94 ms bar, so the fades are
            // capped at a third of the window instead of being a whole bar each (see StemBed)
            StemBed.Bed bed = StemBed.prepare(material, SENT_RATE, WINDOW_MS, BAR_MS,
                    AceStepBed.bedLevelDb(passageDb, underDb));
            println("under %.0f dB: %s", underDb, bed.note);
            ok(bed.usable, bed.note);
            assertEquals(passageDb - underDb, bed.bedLevelDb, 0.5d);
            // the ends are exactly zero, which is what protects both seams
            assertEquals(0.0f, bed.pcm[0][0][0], 0.0f);
            assertEquals(0.0f, bed.pcm[0][1][bed.frames - 1], 0.0f);
        }
        // and the mapping itself: a shallower bed is asked for by a HIGHER passage level
        assertEquals(passageDb, AceStepBed.bedLevelDb(passageDb, StemBed.BED_LEVEL_DB), 1e-9d);
        assertEquals(passageDb + (StemBed.BED_LEVEL_DB - 12d),
                AceStepBed.bedLevelDb(passageDb, 12d), 1e-9d);
        // out of range is clamped, not refused: the knob decides a level, and a level past the end
        // of the range is the level at the end of it
        assertEquals(passageDb, AceStepBed.bedLevelDb(passageDb, 99d), 1e-9d);
        assertEquals(passageDb + (StemBed.BED_LEVEL_DB - AceStepBed.MIN_UNDER_DB),
                AceStepBed.bedLevelDb(passageDb, 1d), 1e-9d);
    }

    @Test
    public void aBedThatWouldPushTheSumOverFullScaleIsNotPlacedAtAll() {
        // a passage at full scale, which is what the fusion's own limiter allows, and a bed under it
        float[][] passage = music(WINDOW_MS / 1000d + 1d, SENT_RATE, 1.0d, 21L);
        float[][] material = music(WINDOW_MS / 1000d, SENT_RATE, 0.25d, 23L);
        StemBed.Bed bed = StemBed.prepare(material, SENT_RATE, WINDOW_MS, BAR_MS,
                AceStepBed.bedLevelDb(-6d, 18d));
        ok(bed.usable, bed.note);

        int at = AceStepBed.frames(500L, SENT_RATE);
        double peak = AceStepBed.peakWithBed(passage, bed.pcm, at);
        println("the sum's own peak is %.4f over a passage whose own peak is %.4f",
                peak, loudest(passage, at, bed.frames));
        ok(peak > DjEdit.Limiter.CEILING,
                "this material is supposed to leave no room under it: " + peak);

        // a passage with room under it: the bed is summed by its own samples, and the seams do not
        // move because the bed's ends are exactly zero. ⚠️ 0.1 and not 0.2 (round 18): the bed is 6 dB
        // louder than this fixture was written for, so the room has to come out of the passage — which
        // is the trade the level change made, stated as a fixture rather than as a comment.
        float[][] quiet = scaled(passage, 0.1d);
        double quietPeak = AceStepBed.peakWithBed(quiet, bed.pcm, at);
        println("with the passage 14 dB down the sum peaks at %.4f (the ceiling is %.0f)",
                quietPeak, DjEdit.Limiter.CEILING);
        ok(quietPeak <= DjEdit.Limiter.CEILING, "the bed should fit under this passage");
        float[] before = Arrays.copyOf(quiet[0], quiet[0].length);
        AceStepBed.addBed(quiet, bed.pcm, at);
        assertEquals(before[at - 1], quiet[0][at - 1], 0d);
        assertEquals(before[at + bed.frames], quiet[0][at + bed.frames], 0d);
        assertEquals(before[at + 1_000] + bed.pcm[0][0][1_000], quiet[0][at + 1_000], 1e-7d);
        println("the loudest sample the bed adds is %.5f", loudest(bed.pcm[0], 0, bed.frames));
        ok(loudest(bed.pcm[0], 0, bed.frames) > 0d, "the bed added nothing at all");
    }

    /** The loudest sample of a region of one signal. */
    private static double loudest(float[][] pcm, int from, int frames) {
        double peak = 0d;
        for (float[] row : pcm) {
            for (int i = from; i < from + frames && i < row.length; i++) {
                peak = Math.max(peak, Math.abs(row[i]));
            }
        }
        return peak;
    }

    /** The worst sample-to-sample difference between two signals over one stretch of time (which is
     *  inside both of them, by construction in this file). */
    private static double spread(float[][] a, float[][] b, long fromMs, long toMs) {
        int from = AceStepBed.frames(fromMs, CLOUD_RATE);
        int to = Math.min(a[0].length, AceStepBed.frames(toMs, CLOUD_RATE));
        double worst = 0d;
        for (int ch = 0; ch < Math.min(a.length, b.length); ch++) {
            for (int i = from; i < to; i++) worst = Math.max(worst, Math.abs(a[ch][i] - b[ch][i]));
        }
        return worst;
    }

    /** The loudest sample of one signal over a stretch of time. */
    private static double peakOf(float[][] pcm, long fromMs, long toMs) {
        int from = AceStepBed.frames(fromMs, CLOUD_RATE);
        int to = Math.min(pcm[0].length, AceStepBed.frames(toMs, CLOUD_RATE));
        double peak = 0d;
        for (float[] row : pcm) {
            for (int i = from; i < to; i++) peak = Math.max(peak, Math.abs(row[i]));
        }
        return peak;
    }
}
