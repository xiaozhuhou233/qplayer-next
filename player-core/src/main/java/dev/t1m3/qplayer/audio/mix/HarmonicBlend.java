package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;

public final class HarmonicBlend implements Transition {
    private final int rate;

    private final int channels;

    private final long window;

    float shift;

    float score;

    public HarmonicBlend(int rate, int channels, float seconds) {
        int r = Math.max(rate, 1);
        this.rate = r;
        this.channels = Math.max(channels, 1);
        this.window = Math.round(Math.max(seconds, 0.0f) * (float) r);
        this.shift = 0.0f;
        this.score = 0.0f;
    }

    public float shift() {
        return shift;
    }

    public static final float PITCH_GLIDE_SECS = 2.5f;

    public static final boolean TRANSPOSITION_ENABLED = true;

    public float score() {
        return score;
    }

    public int channels() {
        return channels;
    }

    public int rate() {
        return rate;
    }

    @Override
    public long windowFrames(int sampleRate) {
        return window;
    }

    @Override
    public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
        Mixer.Gains g = Mixer.equalPowerGains(Automix.clampF32(progress, 0.0f, 1.0f));
        return Mixer.mixTwo(out, from, g.from, to, g.to);
    }

    @Override
    public String name() {
        return "harmonic blend";
    }

    @Override
    public boolean rewritesSamples() {
        return true;
    }

    @Override
    public TransitionPlan prepare(String outro, String intro, int rate) {
        return prepare(outro, intro, rate, null, null);
    }

    @Override
    public TransitionPlan prepare(
            String outro,
            String intro,
            int rate,
            java.util.Map<String, String> outroHeaders,
            java.util.Map<String, String> introHeaders) {
        shift = 0.0f;
        score = 0.0f;

        Automix.Mono tail;
        try {
            tail = Automix.readMono(outro, outroHeaders, true, Harmonic.WINDOW_SECS);
        } catch (AudioException e) {
            return failurePlan(
                    "outro analysis failed (" + e.getMessage() + "), skipping transposition");
        }
        Automix.Mono head;
        try {
            head = Automix.readMono(intro, introHeaders, false, Harmonic.WINDOW_SECS);
        } catch (AudioException e) {
            return failurePlan(
                    "intro analysis failed (" + e.getMessage() + "), skipping transposition");
        }

        Harmonic.Match m = Harmonic.match(tail.pcm, tail.rate, head.pcm, head.rate);
        PitchPlan pitch = PitchPlan.ZERO;
        String note;
        if (m != null) {
            score = Automix.maxF32(m.bestScore, m.plainScore);
            if (m.shouldShift && TRANSPOSITION_ENABLED) {
                shift = (float) m.semitones;
                pitch = new PitchPlan(shift, 0.0f);
            }
            note = intervalNote(m);
        } else {
            note = "waveform overlap · no tonal information in material, using plain blend";
        }
        return new TransitionPlan(0.0, pitch, 0.0f, note);
    }

    @Override
    public PitchPlan pitchAt(float progress) {
        return pitchAt(progress, window / (double) Math.max(1, rate));
    }

    @Override
    public PitchPlan pitchAt(float progress, double windowSecs) {
        if (Math.abs(shift) < 1e-6f) {
            return PitchPlan.ZERO;
        }
        float half = shift / 2.0f;
        float f = pitchCurve(progress, windowSecs);
        if (Math.abs(half) > MAX_SIDE_SHIFT) {
            return new PitchPlan(shift * f, 0.0f);
        }
        return new PitchPlan(half * f, -half * f);
    }

    static final float MAX_SIDE_SHIFT = 3.0f;

    static float pitchCurve(float progress, double windowSecs) {
        if (!(windowSecs > 0) || !Double.isFinite(windowSecs)) return 0;
        return Math.min(entryCurve(progress, windowSecs), entryCurve(1.0f - progress, windowSecs));
    }

    // Share the same clock for pitch and complementary EQ. An outgoing cut enters
    // gradually; an incoming cut leaves gradually before the original-song tail.
    static float entryCurve(float progress, double windowSecs) {
        if (!(windowSecs > 0) || !Double.isFinite(windowSecs)) return 0;
        float ramp = (float) Math.min(0.5, PITCH_GLIDE_SECS / windowSecs);
        return smooth(progress / ramp);
    }

    private static float smooth(float x) {
        float v = Math.max(0.0f, Math.min(1.0f, x));
        return v * v * (3.0f - 2.0f * v);
    }

    static String intervalNote(Harmonic.Match m) {
        if (!TRANSPOSITION_ENABLED) {
            return "waveform overlap · "
                    + m.describe()
                    + "; transposition is disabled at this tier";
        }
        if (!m.shouldShift) {
            return "waveform overlap · " + m.describe();
        }
        return "waveform overlap · "
                + m.describe()
                + "; pitch shift applied to both instrumentals only (A and B vocals untouched)";
    }

    private TransitionPlan failurePlan(String note) {
        return new TransitionPlan(0.0, PitchPlan.ZERO, 0.0f, note);
    }

    @Override
    public String toString() {
        return "HarmonicBlend { rate: "
                + rate
                + ", channels: "
                + channels
                + ", window: "
                + window
                + ", shift: "
                + shift
                + ", score: "
                + score
                + " }";
    }
}
