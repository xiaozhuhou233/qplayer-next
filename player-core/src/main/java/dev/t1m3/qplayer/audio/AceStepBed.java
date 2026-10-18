package dev.t1m3.qplayer.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * The cloud call the ambience bed is made of: the two neighbours assembled into one buffer, the
 * repaint request that asks the music model to fill the silence between them, and the arithmetic
 * that turns the answer back into the material {@link StemBed} places under the passage.
 *
 * <p><b>What the cloud is asked, and why it is asked this way.</b> ACE-Step's {@code repaint} task
 * regenerates one window of a source file and returns the rest of that file's waveform untouched
 * (the server splices the original samples back after VAE decode, with a 25 ms crossfade at each
 * boundary). So the way to get a passage made of <i>both</i> neighbours is to hand the model one
 * buffer that already contains both of them:
 *
 * <pre>
 *     source  = [ A's tail ][ digital silence, gapMs long ][ B's head ]
 *     repaint = [ tailMs, tailMs + gapMs )   — the silence, and nothing else
 * </pre>
 *
 * <p>The window's source latents are replaced by silence (the server's own
 * {@code conditioning_masks}), so what the model is really asked to do is fill a hole that is
 * conditioned on A on its left and B on its right. Two consequences are load-bearing here and both
 * are facts about the server, not about this code:
 *
 * <ul>
 *   <li>{@code repainting_start} and {@code repainting_end} <b>must be sent</b>. Their defaults
 *       ({@code 0.0} and none) regenerate the whole file, which throws both neighbours away.</li>
 *   <li>The window is not something the model can be made to match a level with: the server
 *       peak-normalises the <b>whole returned file</b> to −1 dBFS, so our own preserved samples come
 *       back scaled by a gain that depends on the generated material's peak. {@link #gain} measures
 *       that gain off a region whose bytes we know, and {@link #invert} divides it out. It is
 *       measured, never assumed.</li>
 * </ul>
 *
 * <p><b>Where the two contexts come from, and this is the part round 32 corrected.</b> The passage
 * this bed goes under occupies {@code [entryMs, fusionEndMs)} of the <b>incoming</b> track's own
 * file, so the model's right context has to start at {@code fusionEndMs} — the position the passage
 * hands back to B — and not at B's offset 0. The window's length is therefore exactly
 * {@code windowMs}, and {@link #geometry} refuses rather than approximate: {@code gapMs} must equal
 * the passage window, because a bed made for a different window cannot be summed under this one.
 *
 * <p><b>What the answer is worth.</b> Measured on real runs: the model's material opens 23–56 dB
 * below the outgoing track, its crest factor is about 20 dB against the songs' 10–12, and on the
 * window lengths this app needs it is often too sparse or too flat to be texture at all — that is
 * {@link StemBed}'s acceptance clause's business, and a refusal there is this feature's normal
 * failure. What is <i>this</i> class's business is that all of that is measured and reported rather
 * than assumed: one bad number from the cloud costs one log line, never a wrong sample in a file.
 *
 * <p><b>No platform and no network here.</b> Everything in this file is arithmetic on samples and
 * bytes, so "is this request the one the design says, and does this answer invert correctly" is a
 * unit test. The one thing that is not here is the POST itself ({@link
 * dev.t1m3.qplayer.ai.AceStepClient}, which is {@code HttpURLConnection} and a Bearer header).
 */
public final class AceStepBed {

    /** How much of each neighbour goes into the buffer, ms. The same 12 s the PC probe
     *  ({@code harness/acestep/transition_probe.py}) sends by default and the same the judged
     *  listening samples were made with. */
    public static final long CONTEXT_MS = 12_000L;

    /** The least a neighbour's context may be for the call to be worth making, ms. Under this the
     *  buffer no longer contains enough of that side for the model to be conditioned on it, and a
     *  passage conditioned on almost nothing is not something to spend a request on. */
    public static final long MIN_CONTEXT_MS = 2_000L;

    /** Extra frames of the returned buffer taken beyond the passage window, at the returned rate.
     *
     *  <p>⚠️ Not a detail: the returned material is resampled 48 kHz → the track's rate, and that
     *  conversion can land one frame short of the window ({@code floor} of a fractional frame
     *  count). One frame short is not a rounding error downstream — {@link StemBed#prepare} refuses
     *  material shorter than its window — so the take is cut a couple of frames long and the extra
     *  is never read. */
    public static final int SLACK_FRAMES = 2;

    /** How far from a repaint seam the server's own normalisation is measured, ms. The server
     *  crossfades 25 ms of our own waveform back at each boundary, so the measurement starts well
     *  clear of that. */
    public static final long SEAM_INSET_MS = 200L;

    /** How much of a preserved region is measured for {@link #gain}, ms. */
    public static final long GAIN_PROBE_MS = 1_000L;

    /** The quietest a preserved region may be and still carry a gain measurement, dBFS. Under this
     *  the ratio is noise against noise — and a track that fades to digital silence before the
     *  junction is the common case (18 of 22 in this library), which is why there are two candidate
     *  regions and not one. */
    public static final double MIN_PROBE_DBFS = -60d;

    /** How far the returned buffer's length may differ from the one we sent and still be the answer
     *  to our request, ms. Checked before anything is measured (a buffer cut at the wrong place is
     *  the mistake this project has already shipped once), and generous because the server
     *  resamples our input to 48 kHz on the way in. */
    public static final long DURATION_TOLERANCE_MS = 250L;

    /** The namespace the cloud's model ids carry: {@code GET /v1/models} lists
     *  {@code acemusic/acestep-v1.5-turbo} and nothing else. A configured name without a namespace
     *  is completed with this rather than sent bare, because the bare spelling is the one the
     *  server rejects — and the self-hosted server's own id is namespaced the same way. */
    public static final String MODEL_NAMESPACE = "acemusic/";

    /** The caption — <b>the job description, and the job has changed three times.</b>
     *
     *  <p><b>Rounds 18–22 were wrong jobs.</b> A pad under the passage, then a directional transition, then
     *  a fusion of the two tracks' tunes, then a seam repair — and the listener's verdict on the melodic
     *  ones was 「现在不让ai生成任何旋律了只打鼓点做背景帮助融合」: <b>no melody at all</b>, percussion only, as a
     *  background that helps the fusion. So the ask is a drum layer and nothing else: kick, snare and hats,
     *  steady and continuous, under the music.
     *
     *  <p>⚠️ <b>"Steady" is not a nicety — the app's own acceptance is what makes it a requirement.</b>
     *  {@link StemBed#MIN_BED_OCCUPANCY} was written for the material that measured 52.3% occupancy and was
     *  heard as "a few hits over near-silence, a bit of a cop-out", i.e. a SPARSE take is refused by
     *  design. Measured on the listener's own pair, four drum-only wordings (same geometry, same run):
     *  a steady groove measured <b>100.0%</b> occupancy and the plain "holds one tempo" wording 88.5%,
     *  both accepted; "a minimal drum backing … dry and steady" measured <b>57.7%</b> and was REFUSED with
     *  that same sentence. So the caption must ask for a groove that runs continuously, and the gate stays
     *  as it is — the numbers above are why.
     *
     *  <p>What the request deliberately does NOT carry: any melodic or harmonic content (that is the point
     *  of this round), any tempo or key of either track (the model has their audio and this sentence), and
     *  any favouring of one track's beat — the layer holds one tempo of its own, which is what a DJ's
     *  percussion layer does under a join. */
    public static final String CAPTION =
            "A drum layer and NOTHING else: a steady percussive groove — kick, snare and hi-hat — playing"
            + " continuously under the music across the join. Percussion only: no melody, no harmony, no"
            + " chords, no bass line, no pads, no vocals. The groove is even and constant from start to"
            + " finish, never fades in, never swells, and has no fills or breaks.";

    /** ACE-Step's own sentinel for an instrumental take, sent beside
     *  {@code audio_config.instrumental} because they are two different switches. */
    public static final String INSTRUMENTAL_LYRICS = "[Instrumental]";

    /** The bed's level knob: how far under the passage it is asked to sit, dB. The judged samples
     *  were 18 and 12 dB under; {@link StemBed#BED_LEVEL_DB} is 18, and the setting is what carries
     *  it into the render. */
    public static final double DEFAULT_UNDER_DB = StemBed.BED_LEVEL_DB;

    /** The range the setting is clamped to, dB under the passage. Above {@link StemBed#BED_LEVEL_DB}
     *  the bed is quieter than the first value that was judged; below 6 it is not a bed any more but
     *  a second passage. */
    public static final double MIN_UNDER_DB = 6d;
    public static final double MAX_UNDER_DB = StemBed.BED_LEVEL_DB;

    private AceStepBed() {
    }

    // --- what the user configured -------------------------------------------

    /**
     * The ACE-Step settings, as one value: whether the bed is switched on, where the cloud is, the
     * key, the model, and how far under the passage the bed sits.
     *
     * <p>The key is here and nowhere else. It is never logged ({@link #describe} says whether one is
     * set and nothing more), never part of a file name, and never written anywhere — it comes from
     * the settings store and goes into one Authorization header.
     */
    public static final class Config {
        public final boolean enabled;
        public final String baseUrl;
        public final String apiKey;
        public final String model;
        /** How far under the passage the bed is asked to sit, dB. */
        public final double underDb;

        private Config(boolean enabled, String baseUrl, String apiKey, String model,
                       double underDb) {
            this.enabled = enabled;
            this.baseUrl = trim(baseUrl);
            this.apiKey = trim(apiKey);
            this.model = trim(model);
            this.underDb = Math.max(MIN_UNDER_DB, Math.min(MAX_UNDER_DB, underDb));
        }

        /** Whether there is a call to make at all: the switch, an address, a key and a model. Every
         *  one of the four is required, and a missing one means the render is exactly what it is
         *  without this feature — the same contract the stem path has with a missing model. */
        public boolean configured() {
            return enabled && !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
        }

        /** The endpoint: the configured address, its {@code /v1} added only when it does not
         *  already carry one, then {@code /chat/completions}. */
        public String url() {
            String base = baseUrl;
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            if (!base.endsWith("/v1")) base = base + "/v1";
            return base + "/chat/completions";
        }

        /** The model id as it is sent: the configured name, namespaced when it is bare (see
         *  {@link #MODEL_NAMESPACE}). */
        public String modelId() {
            if (model.isEmpty() || model.contains("/")) return model;
            return MODEL_NAMESPACE + model;
        }

        /** One line for the log: what the request will be made of. The key is stated as present or
         *  absent and never printed. */
        public String describe() {
            return String.format(Locale.US, "ACE-Step %s at %s, model %s, the bed %.0f dB under the"
                            + " passage, key %s",
                    enabled ? "enabled" : "off", baseUrl.isEmpty() ? "(no address)" : baseUrl,
                    modelId().isEmpty() ? "(no model)" : modelId(), underDb,
                    apiKey.isEmpty() ? "NOT set" : "set");
        }
    }

    /** The settings as a value. {@code underDb} outside the range is clamped, not refused: the knob
     *  decides a level, and a level out of range is a level at the end of the range. */
    public static Config config(boolean enabled, String baseUrl, String apiKey, String model,
                                double underDb) {
        return new Config(enabled, baseUrl, apiKey, model, underDb);
    }

    /** The feature off: what a host with no settings hands the renderer. */
    public static final Config OFF = new Config(false, "", "", "", DEFAULT_UNDER_DB);

    // --- the geometry of one call -------------------------------------------

    /**
     * The one buffer the cloud is asked about: how much of each neighbour, how long the silence
     * between them is, and the repaint window that covers exactly that silence.
     *
     * <p>{@code gapMs} is the passage's own window, never a rounded or a nearby value (see the class
     * doc) — a bed built for another window would be summed under this one at the wrong length,
     * which is why {@link #geometry} takes the window as its input and refuses when the neighbours
     * cannot supply the contexts around it.
     */
    public static final class Geometry {
        public final boolean valid;
        /** Why not, for the log. Empty when {@link #valid}. */
        public final String why;
        /** The rate the buffer is assembled at: the incoming file's own, which is also the rate the
         *  two contexts are cut at. */
        public final int rate;
        /** How much of the outgoing track goes in, ms. */
        public final long tailMs;
        /** The silence the model is asked to fill — <b>the passage's own window, to the
         *  millisecond</b>, ms. */
        public final long gapMs;
        /** How much of the incoming track goes in, ms. */
        public final long headMs;
        /** Where the outgoing's context starts in its own file, ms. */
        public final long aFromMs;
        /** Where the incoming's context starts in its own file, ms — the passage's
         *  {@code fusionEndMs}. */
        public final long bFromMs;

        private Geometry(boolean valid, String why, int rate, long tailMs, long gapMs, long headMs,
                         long aFromMs, long bFromMs) {
            this.valid = valid;
            this.why = why == null ? "" : why;
            this.rate = rate;
            this.tailMs = tailMs;
            this.gapMs = gapMs;
            this.headMs = headMs;
            this.aFromMs = aFromMs;
            this.bFromMs = bFromMs;
        }

        /** The frames the passage window holds at this rate — what the bed has to fill. */
        public int windowFrames() {
            return frames(gapMs, rate);
        }

        /** The whole buffer's length, ms. */
        public long bufferMs() {
            return tailMs + gapMs + headMs;
        }

        /** Where the repaint window starts, <b>seconds from the buffer's start</b> — the unit
         *  {@code repainting_start} takes. */
        public double repaintStartSec() {
            return tailMs / 1000d;
        }

        public double repaintEndSec() {
            return (tailMs + gapMs) / 1000d;
        }

        public String describe() {
            return String.format(Locale.US, "the cloud buffer is %dms of the outgoing's tail"
                            + " (from %dms of its file) + %dms of silence (the passage's window,"
                            + " repainted as [%.3f, %.3f)s) + %dms of the incoming's head (from"
                            + " %dms of its file) = %.1fs at %dHz",
                    tailMs, aFromMs, gapMs, repaintStartSec(), repaintEndSec(), headMs, bFromMs,
                    bufferMs() / 1000d, rate);
        }
    }

    /**
     * The geometry for one fusion passage: the outgoing's tail ending on the junction bar line, the
     * silence equal to the passage's window, and the incoming's head starting where the passage
     * hands back to it.
     *
     * <p>Every refusal here is a case where the call would be made with the wrong material in it:
     * a neighbour shorter than {@link #MIN_CONTEXT_MS}, or a duration that is not known at all
     * (which is what a streamed file or a container with no metadata looks like).
     *
     * @param junctionMs         where the outgoing deck is cut, in the <b>outgoing's</b> own file
     * @param fusionEndMs        {@code entryMs + windowMs} — where the incoming's own audio is back,
     *                           in the <b>incoming's</b> own file; the right context starts here
     * @param windowMs           the passage's window, ms; equals the silence, to the millisecond
     * @param outgoingDurationMs the outgoing file's length, ms (0 or less when unknown)
     * @param incomingDurationMs the incoming file's length, ms (0 or less when unknown)
     * @param rate               the rate the buffer is assembled at
     */
    public static Geometry geometry(long junctionMs, long fusionEndMs, long windowMs,
                                    long outgoingDurationMs, long incomingDurationMs, int rate) {
        return geometry(junctionMs, fusionEndMs, windowMs, outgoingDurationMs, incomingDurationMs,
                rate, CONTEXT_MS);
    }

    /**
     * The same geometry with the context length <b>given</b> instead of {@link #CONTEXT_MS} — for the
     * PC bench ({@code BedFetch}) that asks ACE-Step for a take over a <em>short</em> window, which is a
     * different job from the passage-wide bed: a patch that repairs one seam needs only a couple of
     * seconds of audio either side of it, and giving the model twelve would make it describe twelve
     * seconds of music instead of continuing two. {@code contextMs} is clamped to
     * {@code [MIN_CONTEXT_MS, CONTEXT_MS]} so no caller can ask for a context the model cannot use.
     *
     * <p>The app itself never calls this: it asks for the passage-wide bed, which is what
     * {@code CONTEXT_MS} is for.
     */
    public static Geometry geometry(long junctionMs, long fusionEndMs, long windowMs,
                                    long outgoingDurationMs, long incomingDurationMs, int rate,
                                    long contextMs) {
        if (rate <= 0) return invalid("a buffer at " + rate + "Hz");
        if (windowMs <= 0L) return invalid("a " + windowMs + "ms passage window");
        if (junctionMs <= 0L) return invalid("the outgoing deck is cut at " + junctionMs + "ms of"
                + " its own file, which is not a position the outgoing's tail can end on");
        if (fusionEndMs <= 0L) return invalid("the passage hands back to the incoming at "
                + fusionEndMs + "ms of its own file");
        if (outgoingDurationMs <= 0L) {
            return invalid("the outgoing file's length is not known, so no tail window can be"
                    + " placed on its end");
        }
        if (incomingDurationMs <= 0L) {
            return invalid("the incoming file's length is not known, so no head window can be"
                    + " placed after " + fusionEndMs + "ms of it");
        }
        long wanted = Math.max(MIN_CONTEXT_MS, Math.min(CONTEXT_MS, contextMs));
        long tailMs = Math.min(wanted, junctionMs);
        if (tailMs < MIN_CONTEXT_MS) {
            return invalid(String.format(Locale.US, "the junction is at %dms of the outgoing's file,"
                    + " so only %dms of its tail is available — under the %dms a context needs",
                    junctionMs, tailMs, MIN_CONTEXT_MS));
        }
        long available = incomingDurationMs - fusionEndMs;
        long headMs = Math.min(wanted, available);
        if (headMs < MIN_CONTEXT_MS) {
            return invalid(String.format(Locale.US, "the passage hands back to the incoming at %dms"
                            + " of a %dms file, leaving %dms — under the %dms a context needs",
                    fusionEndMs, incomingDurationMs, available, MIN_CONTEXT_MS));
        }
        return new Geometry(true, null, rate, tailMs, windowMs, headMs, junctionMs - tailMs,
                fusionEndMs);
    }

    /** No geometry, and the sentence that says why. */
    private static Geometry invalid(String why) {
        return new Geometry(false, why, 0, 0L, 0L, 0L, 0L, 0L);
    }

    /** ms in frames at a rate. */
    static int frames(long ms, int rate) {
        return (int) Math.round(rate * (ms / 1000d));
    }

    // --- the buffer ---------------------------------------------------------

    /**
     * The one buffer the model is shown: the outgoing's tail, the silence, the incoming's head, in
     * that order — the shape {@code transition_probe.py} builds and the only shape the request's
     * repaint window means anything on.
     *
     * <p>The silence is <b>digital</b> silence and not a low noise floor: it is what the server's
     * own masking replaces the window's source latents with, so what we put there only has to have
     * the right length. Both rows are two channels; a mono context is copied to both.
     */
    public static float[][] assemble(float[][] tail, float[][] head, int gapFrames) {
        int frames = tail[0].length + gapFrames + head[0].length;
        float[][] out = new float[2][frames];
        for (int ch = 0; ch < 2; ch++) {
            float[] from = tail[Math.min(ch, tail.length - 1)];
            System.arraycopy(from, 0, out[ch], 0, from.length);
            float[] to = head[Math.min(ch, head.length - 1)];
            System.arraycopy(to, 0, out[ch], tail[0].length + gapFrames, to.length);
        }
        return out;
    }

    // --- the wire -----------------------------------------------------------

    /**
     * The buffer as a 16-bit PCM WAV, which is what {@code audio_config.format = "wav"} asks the
     * server to answer in.
     *
     * <p>WAV is asked for because the answer is walked by <b>our own</b> decoder (there is no
     * decoder library in this project and no new dependency is allowed), and because the returned
     * data URL always claims {@code audio/mpeg} whatever the bytes are — so the format we can
     * actually read is the one we must request. See {@link #wav(byte[])} for the sniff.
     */
    public static byte[] wav(float[][] pcm, int rate) {
        int channels = Math.min(2, pcm.length);
        int frames = pcm[0].length;
        int dataBytes = frames * channels * 2;
        byte[] out = new byte[44 + dataBytes];
        putAscii(out, 0, "RIFF");
        putInt(out, 4, 36 + dataBytes);
        putAscii(out, 8, "WAVE");
        putAscii(out, 12, "fmt ");
        putInt(out, 16, 16);
        putShort(out, 20, 1);              // PCM
        putShort(out, 22, channels);
        putInt(out, 24, rate);
        putInt(out, 28, rate * channels * 2);
        putShort(out, 32, channels * 2);
        putShort(out, 34, 16);
        putAscii(out, 36, "data");
        putInt(out, 40, dataBytes);
        int at = 44;
        for (int i = 0; i < frames; i++) {
            for (int ch = 0; ch < channels; ch++) {
                double v = pcm[ch][i];
                if (v > 1d) v = 1d;
                else if (v < -1d) v = -1d;
                int s = (int) Math.round(v * 32767d);
                out[at++] = (byte) (s & 0xFF);
                out[at++] = (byte) ((s >> 8) & 0xFF);
            }
        }
        return out;
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

    /**
     * The request body, as the OpenRouter-compatible {@code /v1/chat/completions} document the cloud
     * takes: one text part (the caption and the instrumental sentinel), one {@code input_audio} part
     * (the buffer, base64), and the four fields that decide what the model does with it.
     *
     * <p>Built as Gson nodes rather than with a string template: the buffer is megabytes of base64
     * and the one thing this body must never be is malformed by an escaping bug.
     *
     * <p>No second audio part is sent. The alternative ({@code audio[1]} = the incoming's head as a
     * timbre reference) exists on the PC probe behind {@code --reference}, and the material that was
     * judged by ear was made without it; sending it would also duplicate a third of a payload that
     * is already several megabytes.
     */
    public static String body(Config config, Geometry geometry, byte[] buffer) {
        return body(config, geometry, buffer, CAPTION);
    }

    /**
     * The same request with the caption <b>given</b> rather than taken from {@link #CAPTION} — for the
     * PC bench that iterates on the wording ({@code D:\qplayer-dev\harnessusion\BedFetch.java}),
     * so that a caption can be compared by ear without an APK install and a full re-render per attempt.
     *
     * <p>The app itself never calls this overload: the wording it ships is the constant above, and the
     * point of routing the bench through the same method is that the request it sends is the request
     * the app would send — only the sentence differs. A null or empty caption falls back to
     * {@link #CAPTION} rather than sending an empty prompt, which the server answers with silence.
     */
    public static String body(Config config, Geometry geometry, byte[] buffer, String caption) {
        if (caption == null || caption.isEmpty()) return body(config, geometry, buffer, CAPTION);
        String audio = Base64.getEncoder().encodeToString(buffer);
        JsonObject audioInput = new JsonObject();
        JsonObject inputAudio = new JsonObject();
        inputAudio.addProperty("data", audio);
        inputAudio.addProperty("format", "wav");
        audioInput.addProperty("type", "input_audio");
        audioInput.add("input_audio", inputAudio);

        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", "<prompt>" + caption + "</prompt><lyrics>"
                + INSTRUMENTAL_LYRICS + "</lyrics>");

        JsonArray content = new JsonArray();
        content.add(text);
        content.add(audioInput);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);
        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject audioConfig = new JsonObject();
        audioConfig.addProperty("format", "wav");
        audioConfig.addProperty("instrumental", true);
        // The buffer's own length, as the probe sends it: the model is told how long the file it is
        // filling is, and the answer is the whole file back.
        audioConfig.addProperty("duration", Math.round(geometry.bufferMs() / 100d) / 10d);

        JsonObject root = new JsonObject();
        root.addProperty("model", config.modelId());
        root.add("messages", messages);
        root.addProperty("stream", false);
        root.addProperty("task_type", "repaint");
        // ⚠️ Never omitted. The server's defaults regenerate the whole file, which throws away both
        // neighbours and the point of the call.
        root.addProperty("repainting_start", geometry.repaintStartSec());
        root.addProperty("repainting_end", geometry.repaintEndSec());
        root.add("audio_config", audioConfig);
        // The LM's text work is skipped for repaint anyway; asked off explicitly so the passage is
        // decided by the audio and the caption alone.
        root.addProperty("use_cot_caption", false);
        root.addProperty("use_cot_language", false);
        return root.toString();
    }

    /** One answer from the cloud: the audio's bytes, or why there are none. */
    public static final class Reply {
        public boolean ok;
        /** The audio as the server sent it, format unknown until {@link #wav(byte[])} looks. */
        public byte[] bytes;
        /** Why not, when {@link #ok} is false. */
        public String why = "";
        /** The model's own text, when it sent any — a refusal often explains itself there, and this
         *  is the only place that text is ever seen. */
        public String text = "";
    }

    /**
     * The audio out of one {@code /v1/chat/completions} answer: the base64 data URL in
     * {@code choices[0].message.audio[0].audio_url.url}, and nothing else.
     *
     * <p>The wrapper's own mime ({@code data:audio/mpeg}) is ignored on principle — it is written
     * into the server's response code and says nothing about the bytes inside, which are in the
     * format {@code audio_config.format} asked for.
     */
    public static Reply reply(String json) {
        Reply out = new Reply();
        if (json == null || json.isEmpty()) {
            out.why = "the cloud answered with nothing";
            return out;
        }
        String url;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                out.why = "the cloud's answer has no choices in it";
                return out;
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null) {
                out.why = "the cloud's first choice has no message in it";
                return out;
            }
            if (message.has("content") && !message.get("content").isJsonNull()) {
                out.text = message.get("content").getAsString();
            }
            JsonArray audio = message.getAsJsonArray("audio");
            if (audio == null || audio.size() == 0) {
                out.why = "the cloud's answer carries no audio";
                return out;
            }
            JsonObject audioUrl = audio.get(0).getAsJsonObject().getAsJsonObject("audio_url");
            if (audioUrl == null || !audioUrl.has("url")) {
                out.why = "the cloud's audio part carries no url";
                return out;
            }
            url = audioUrl.get("url").getAsString();
        } catch (Throwable e) {
            out.why = "the cloud's answer could not be read (" + e + ")";
            return out;
        }
        int comma = url.indexOf(',');
        if (!url.startsWith("data:") || comma < 0) {
            out.why = "the cloud's audio part is not a data URL";
            return out;
        }
        try {
            out.bytes = Base64.getDecoder().decode(url.substring(comma + 1).trim());
        } catch (Throwable e) {
            out.why = "the cloud's audio part is not base64 (" + e + ")";
            return out;
        }
        if (out.bytes.length == 0) {
            out.why = "the cloud sent an empty audio part";
            return out;
        }
        out.ok = true;
        return out;
    }

    // --- what came back -----------------------------------------------------

    /** One decoded answer: the samples, and the format they really are. */
    public static final class Wav {
        public boolean ok;
        public String why = "";
        /** What the bytes really are — {@code wav}, {@code flac}, {@code mp3} or {@code unknown} —
         *  read off the header and never off the data URL's label. */
        public String format = "unknown";
        public int rate;
        public int channels;
        /** {@code [channel][frame]} in [-1, 1]. */
        public float[][] pcm;
        /** How many frames per channel. */
        public int frames;

        public String describe() {
            return String.format(Locale.US, "%s, %dHz, %d channel%s, %.3fs",
                    format, rate, channels, channels == 1 ? "" : "s",
                    rate > 0 ? frames / (double) rate : 0d);
        }
    }

    /** The header's verdict on one blob, before anything is decoded: the format the bytes really
     *  are. The data URL's {@code audio/mpeg} label is never consulted — it is hardcoded in the
     *  server and wrong whenever {@code audio_config.format} is not {@code mp3}. */
    public static String formatOf(byte[] bytes) {
        if (bytes == null || bytes.length < 12) return "unknown";
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E') {
            return "wav";
        }
        if (bytes[0] == 'f' && bytes[1] == 'L' && bytes[2] == 'a' && bytes[3] == 'C') return "flac";
        if ((bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3')
                || (bytes[0] == (byte) 0xFF && (bytes[1] & 0xE0) == 0xE0)) return "mp3";
        if (bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S') return "ogg";
        return "unknown";
    }

    /**
     * Decodes a WAV the server sent, by walking its chunks: {@code fmt } for the rate, the channel
     * count and the sample width, {@code data} for the samples, and anything else stepped over.
     *
     * <p>Nothing is assumed about the header's length — a {@code LIST} or {@code fact} chunk in
     * front of the audio is ordinary — and a width this project cannot read is refused by name
     * rather than misread. A format that is not WAV at all (the server answered in the format its
     * {@code audio_config} asked for, or in mp3 if something above us forgot to ask) is refused
     * with the format {@link #formatOf} sniffed, because there is no decoder in this project for
     * anything else and inventing one is not allowed.
     */
    public static Wav wav(byte[] bytes) {
        Wav out = new Wav();
        out.format = formatOf(bytes);
        if (!"wav".equals(out.format)) {
            out.why = "the cloud's answer is " + out.format + ", not the wav the request asked for"
                    + " (the data URL claims audio/mpeg whatever it is, so the header is what this"
                    + " reads)";
            return out;
        }
        int at = 12;
        int rate = 0;
        int channels = 0;
        int bits = 0;
        boolean isFloat = false;
        int dataAt = -1;
        int dataBytes = 0;
        try {
            while (at + 8 <= bytes.length) {
                String id = new String(bytes, at, 4, StandardCharsets.US_ASCII);
                int size = readInt(bytes, at + 4);
                if (size < 0 || at + 8 + size > bytes.length) {
                    // A chunk that claims more than the answer holds: the buffer was cut short, and
                    // half a buffer is not a bed's material. (The same test catches the streaming
                    // length, 0xFFFFFFFF, which reads as -1.)
                    out.why = "the wav's " + id.trim() + " chunk claims " + (size < 0
                            ? "an unbounded number of" : String.valueOf(size)) + " bytes in an answer"
                            + " of " + bytes.length + " — the buffer is truncated";
                    return out;
                }
                if ("fmt ".equals(id)) {
                    if (size < 16) {
                        out.why = "the wav's fmt chunk is only " + size + " bytes";
                        return out;
                    }
                    int tag = readShort(bytes, at + 8);
                    channels = readShort(bytes, at + 10);
                    rate = readInt(bytes, at + 12);
                    bits = readShort(bytes, at + 22);
                    isFloat = tag == 3;
                } else if ("data".equals(id)) {
                    dataAt = at + 8;
                    dataBytes = size;
                }
                at += 8 + size + (size % 2);
            }
        } catch (Throwable e) {
            out.why = "the wav's chunks could not be walked (" + e + ")";
            return out;
        }
        if (dataAt < 0 || dataBytes <= 0) {
            out.why = "the wav has no data chunk";
            return out;
        }
        if (rate <= 0 || channels <= 0) {
            out.why = "the wav's fmt chunk says " + rate + "Hz and " + channels + " channels";
            return out;
        }
        int width = bits / 8;
        if (width != 2 && width != 3 && width != 4) {
            out.why = "the wav is " + bits + "-bit, which this project cannot read (16, 24 and 32"
                    + " bit PCM are what it decodes)";
            return out;
        }
        int use = Math.min(2, channels);
        int frames = dataBytes / (width * channels);
        if (frames <= 0) {
            out.why = "the wav's data chunk holds no whole frame";
            return out;
        }
        float[][] pcm = new float[use][frames];
        for (int i = 0; i < frames; i++) {
            for (int ch = 0; ch < use; ch++) {
                pcm[ch][i] = readSample(bytes, dataAt + (i * channels + ch) * width, width, isFloat);
            }
        }
        out.ok = true;
        out.rate = rate;
        out.channels = channels;
        out.pcm = pcm;
        out.frames = frames;
        return out;
    }

    private static float readSample(byte[] b, int at, int width, boolean isFloat) {
        if (width == 2) {
            short s = (short) ((b[at] & 0xFF) | (b[at + 1] << 8));
            return s / 32768f;
        }
        if (width == 3) {
            int v = (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | (b[at + 2] << 16);
            return (float) (v / 8388608d);
        }
        int v = (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16)
                | (b[at + 3] << 24);
        return isFloat ? Float.intBitsToFloat(v) : (float) (v / 2147483648d);
    }

    private static int readInt(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16)
                | (b[at + 3] << 24);
    }

    private static int readShort(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    // --- the server's own gain ----------------------------------------------

    /** The server's peak normalisation, measured rather than assumed. */
    public static final class Gain {
        public boolean measured;
        public String why = "";
        /** The gain the server applied to the whole file, dB (negative is an attenuation). */
        public double db;
        /** The region's level in what came back, dBFS. */
        public double returnedDb;
        /** The region's level in what we sent, dBFS. */
        public double sentDb;
        /** Which region carried the measurement, for the log. */
        public String from = "";

        public String describe() {
            if (!measured) return "the server's normalisation was not measurable: " + why;
            return String.format(Locale.US, "the server's own peak normalisation measured %+.2f dB"
                            + " on %s (%.1f dBFS returned against the %.1f dBFS we sent) and is"
                            + " divided out",
                    db, from, returnedDb, sentDb);
        }
    }

    /**
     * What the server did to the whole file, off a region whose bytes we know: the outgoing's tail
     * just before the repaint window, or the incoming's head just after it, whichever is louder (a
     * track that fades to digital silence is the common case, and 18 of 22 in this library do — a
     * gain measured on silence is a gain measured on noise).
     *
     * <p>Measured on <b>time</b> and not on samples, because the server resamples our input to
     * 48 kHz on the way in and the returned rate is not the rate we sent; a window in seconds means
     * the same thing on both sides.
     *
     * <p>⚠️ Worth knowing what this number is and is not for: {@link StemBed#prepare} normalises the
     * bed by its own RMS, so the bed's level would come out the same at any input gain. What the
     * inversion buys is that the material handed on is the material as the design describes it —
     * and that the number itself is on the log line, so "the passage came back at an unusable level"
     * is a fact about a run rather than a suspicion about it.
     */
    public static Gain gain(Wav returned, float[][] sent, int sentRate, Geometry geometry) {
        Gain out = new Gain();
        if (returned == null || !returned.ok) {
            out.why = "there is no returned buffer to measure against";
            return out;
        }
        long sentMs = sent[0].length * 1000L / Math.max(1, sentRate);
        long[][] regions = {
            {geometry.repaintStartSec() > 0d ? geometry.tailMs - SEAM_INSET_MS - GAIN_PROBE_MS : 0L,
                    geometry.tailMs - SEAM_INSET_MS, 0L},
            {geometry.tailMs + geometry.gapMs + SEAM_INSET_MS,
                    geometry.tailMs + geometry.gapMs + SEAM_INSET_MS + GAIN_PROBE_MS, 1L},
        };
        String[] names = {"the outgoing's tail", "the incoming's head"};
        double bestDb = -1_000d;
        int best = -1;
        double[] returnedDb = new double[2];
        double[] sentDb = new double[2];
        for (int r = 0; r < regions.length; r++) {
            long from = regions[r][0];
            long to = regions[r][1];
            if (from < 0L || to <= from || to > sentMs) {
                sentDb[r] = Double.NaN;
                returnedDb[r] = Double.NaN;
                continue;
            }
            sentDb[r] = levelOf(sent, sentRate, from, to);
            returnedDb[r] = levelOf(returned.pcm, returned.rate, from, to);
            if (sentDb[r] > bestDb) {
                bestDb = sentDb[r];
                best = r;
            }
        }
        if (best < 0) {
            out.why = "neither of the two preserved regions is inside the buffer we sent";
            return out;
        }
        out.sentDb = sentDb[best];
        out.returnedDb = returnedDb[best];
        out.from = names[best];
        if (!(out.sentDb > MIN_PROBE_DBFS)) {
            out.why = String.format(Locale.US, "%s is %.1f dBFS, under the %.0f dBFS a gain"
                            + " measurement needs (and the incoming's head is %.1f dBFS) — the"
                            + " neighbours' preserved regions are both too quiet to measure the"
                            + " server's normalisation on",
                    out.from, out.sentDb, MIN_PROBE_DBFS,
                    Double.isNaN(sentDb[1 - best]) ? Double.NaN : sentDb[1 - best]);
            return out;
        }
        if (!(out.returnedDb > -240d)) {
            out.why = out.from + " came back silent where we sent material, so the server did not"
                    + " preserve the region this measurement is made on";
            return out;
        }
        out.db = out.returnedDb - out.sentDb;
        out.measured = true;
        return out;
    }

    /** The RMS of a region of a signal given in <b>file time</b>, dBFS. */
    static double levelOf(float[][] pcm, int rate, long fromMs, long toMs) {
        int from = (int) Math.max(0L, Math.round(fromMs * rate / 1000d));
        int to = (int) Math.min(pcm[0].length, Math.round(toMs * rate / 1000d));
        double energy = 0d;
        int count = 0;
        for (float[] row : pcm) {
            for (int i = from; i < to; i++) {
                energy += row[i] * (double) row[i];
                count++;
            }
        }
        if (count == 0) return -240d;
        return DjEdit.db(Math.sqrt(energy / count));
    }

    /** The same signal at unity: every sample divided by the gain the server applied (in place). */
    public static void invert(float[][] pcm, Gain gain) {
        if (gain == null || !gain.measured || Math.abs(gain.db) < 1e-9d) return;
        double factor = Math.pow(10d, -gain.db / 20d);
        for (float[] row : pcm) {
            for (int i = 0; i < row.length; i++) row[i] = (float) (row[i] * factor);
        }
    }

    // --- the take -----------------------------------------------------------

    /** Why the returned buffer is not the answer to the request we made, or null when it is. The
     *  length is checked first and before anything is measured — a buffer cut at the wrong place is
     *  the mistake this project has already shipped once. */
    public static String lengthCheck(Wav returned, Geometry geometry) {
        if (returned == null || !returned.ok) return "there is no returned buffer";
        double sentMs = geometry.bufferMs();
        double gotMs = returned.frames * 1000d / returned.rate;
        if (Math.abs(gotMs - sentMs) > DURATION_TOLERANCE_MS) {
            return String.format(Locale.US, "the cloud returned %.0fms where %.0fms was sent"
                    + " (%.1fms apart, over the %.0fms this will trust) — the answer is to a"
                    + " different request than this one, or it was cut at the wrong place",
                    gotMs, sentMs, Math.abs(gotMs - sentMs), (double) DURATION_TOLERANCE_MS);
        }
        return null;
    }

    /**
     * The repainted span and nothing else: the window the silence was in, as the server returned it.
     *
     * <p>{@link #SLACK_FRAMES} extra frames are taken past the window's end when the buffer has
     * them, because the caller resamples this to the track's rate and that conversion can land a
     * frame short of what the bed has to fill; one frame short is a refusal in {@link
     * StemBed#prepare}, not a rounding detail.
     *
     * <p>Null (with the reason in {@code why}) when the buffer does not reach the window's end — that
     * is a shorter answer than the request asked for, and it is refused rather than zero-padded.
     */
    public static float[][] span(Wav returned, Geometry geometry) {
        if (returned == null || !returned.ok) return null;
        int from = frames(geometry.tailMs, returned.rate);
        int want = frames(geometry.gapMs, returned.rate);
        int to = Math.min(returned.frames, from + want + SLACK_FRAMES);
        if (from >= returned.frames || to - from < want) return null;
        float[][] out = new float[returned.pcm.length][to - from];
        for (int ch = 0; ch < out.length; ch++) {
            System.arraycopy(returned.pcm[ch], from, out[ch], 0, out[ch].length);
        }
        return out;
    }

    // --- the level the bed is placed at -------------------------------------

    /**
     * The {@code levelDb} {@link StemBed#prepare} is handed so that the bed lands {@code underDb}
     * under {@code passageDb}.
     *
     * <p>Two numbers because there are two: {@link StemBed} always places its bed
     * {@link StemBed#BED_LEVEL_DB} under the level it is given, so a shallower bed is asked for by
     * handing it a <b>higher</b> passage level — the same trick the PC demo's {@code bed_pad_demo.py}
     * uses, and the reason this is a named function rather than arithmetic at the call site.
     */
    public static double bedLevelDb(double passageDb, double underDb) {
        double under = Math.max(MIN_UNDER_DB, Math.min(MAX_UNDER_DB, underDb));
        return passageDb - under + StemBed.BED_LEVEL_DB;
    }

    /**
     * The loudest sample the passage would reach with the bed summed into it, without summing it:
     * the acceptance clause's reading, taken before anything is written so a bed that would clip
     * simply is not placed.
     *
     * <p>The bed's own ends are exactly zero (that is the whole design — see {@link StemBed}), so
     * this is about the middle: a passage already at full scale where the bed's own peaks land has
     * no room under it, and the answer there is to leave the bed out rather than to duck the passage
     * or clip the file.
     */
    public static double peakWithBed(float[][] edited, float[][][] bed, int atFrame) {
        double peak = 0d;
        if (edited == null || bed == null || bed.length == 0 || bed[0] == null) return 0d;
        int channels = Math.min(edited.length, bed[0].length);
        int frames = bed[0][0].length;
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < frames; i++) {
                int at = atFrame + i;
                if (at < 0 || at >= edited[ch].length) continue;
                double v = edited[ch][at] + bed[0][ch][i];
                peak = Math.max(peak, Math.abs(v));
            }
        }
        return peak;
    }

    /** Sums a prepared bed into the edit over its own frames (in place). */
    public static void addBed(float[][] edited, float[][][] bed, int atFrame) {
        if (edited == null || bed == null || bed.length == 0 || bed[0] == null) return;
        int channels = Math.min(edited.length, bed[0].length);
        int frames = bed[0][0].length;
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < frames; i++) {
                int at = atFrame + i;
                if (at < 0 || at >= edited[ch].length) continue;
                edited[ch][at] += bed[0][ch][i];
            }
        }
    }

    /** A configuration field that is missing, as the empty string. */
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
