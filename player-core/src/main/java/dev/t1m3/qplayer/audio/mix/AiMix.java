package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.dsp.Biquad;
import dev.t1m3.qplayer.audio.dsp.Delay;
import dev.t1m3.qplayer.audio.dsp.Reverb;
import dev.t1m3.qplayer.audio.dsp.SplitLowHigh;
import dev.t1m3.qplayer.audio.model.BeatDetector;
import dev.t1m3.qplayer.audio.model.BeatModels;
import dev.t1m3.qplayer.audio.model.MelParams;
import dev.t1m3.qplayer.audio.automix.AutoMixLog;

import java.nio.FloatBuffer;
import java.util.Locale;

public final class AiMix implements Transition {
    private static volatile boolean modelMissingReported;

    private static final float FRAC_PI_2 = (float) (Math.PI / 2.0);

    private static final java.util.concurrent.ExecutorService READ_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(
                    new java.util.concurrent.ThreadFactory() {
                        private final java.util.concurrent.atomic.AtomicInteger n =
                                new java.util.concurrent.atomic.AtomicInteger();

                        @Override
                        public Thread newThread(Runnable r) {
                            Thread th =
                                    new Thread(r, "qplayer-automix-read-in-" + n.getAndIncrement());
                            th.setDaemon(true);
                            return th;
                        }
                    });

    private MixStyle style;

    private final java.util.function.DoubleSupplier harmonicSecs;

    private long fixedWindowFrames, maxPlaybackWindowFrames = Long.MAX_VALUE;
    private boolean playbackAutoCue = true;

    public void playbackWindowPolicy(long fixedFrames, long maxFrames, boolean autoCue) {
        fixedWindowFrames = fixedFrames;
        maxPlaybackWindowFrames = maxFrames;
        playbackAutoCue = autoCue;
    }

    private Transition harmonicBlend;

    private TransitionPlan harmonicPlan;

    private AutoCue.Cue harmonicCue;

    private AutoCue.Cue autoCue;

    private static final class ModeHolder {
        TransitionPlan plan;
    }

    private final int rate;

    private final int channels;

    long window;

    private Analysis.Tempo outroTempo;

    private Analysis.Tempo introTempo;

    private double outroTailSecs;

    private int introRate;

    float cutAt;

    private Float cutForcedAt;

    private String autoNote;

    private TempoSource outroSource;

    private TempoSource introSource;

    private final MixTuning tuning;

    private final LevelMatcher levelMatcher;

    private final LowDucker lowDucker;

    private final SplitLowHigh[] fromSplit;

    private final SplitLowHigh[] toSplit;

    private final SplitLowHigh[] toSplit2;

    private static final MixStyle[] ROUTE_CANDIDATES = {
        MixStyle.HIPHOP_LOOPING,
        MixStyle.HIPHOP_DELAY_OUT,
        MixStyle.FILTER_EXPANSION,
        MixStyle.POP_DELAY_OUT,
        MixStyle.POP_REVERB,
        MixStyle.FILTER_HIGH_TO_LOW,
        MixStyle.FILTER_LONG_HIGH_TO_LOW,
        MixStyle.FILTER_SHORT_HIGH_TO_LOW,
        MixStyle.FILTER_SOFT_REVERB,
        MixStyle.NO_VOCAL_CLASH,
        MixStyle.BASS_SWAP,
        MixStyle.CROSSFADE_OVERLAP_REVERB,
        MixStyle.CROSSFADE_RINGOUT_REVERB,
        MixStyle.FILTER_LONG_OUT_SHORT_IN,
        MixStyle.FILTER_DYNAMIC_IN,
        MixStyle.FILTER_LONG_OUT_SHORT_IN_DELAY,
        MixStyle.FILTER_LONG_OUT_SHORT_IN_REVERB,
        MixStyle.DANCE_ANY_KEY,
        MixStyle.DANCE_REPEATER,
        MixStyle.CROSSFADE_LONG,
        MixStyle.CROSSFADE_OVERLAP,
        MixStyle.CROSSFADE_RINGOUT,
        MixStyle.PLAIN_BLEND,
        MixStyle.TAIL_RIDE,
        MixStyle.GAPLESS,
        MixStyle.SPLICE,
        MixStyle.BEAT_CUT,
        MixStyle.SHOW_MODE,
        MixStyle.LEARNED_EQ,
    };

    private MaterialProfile autoProfile;

    private Analysis.Tempo lastTempoOut;

    private Analysis.Tempo lastTempoIn;

    public MaterialProfile autoProfile() {
        return autoProfile;
    }

    public Analysis.Tempo tempoOut() {
        return lastTempoOut;
    }

    public Analysis.Tempo tempoIn() {
        return lastTempoIn;
    }

    private static final float MAX_MODEL_BPM_GAP = 0.20f;

    private static Analysis.Tempo saneTempo(Analysis.Tempo model, Analysis.Tempo hard) {
        if (model == null || hard == null || model.bpm <= 0f || hard.bpm <= 0f) {
            return model;
        }
        float gap = Math.abs(model.bpm - hard.bpm) / Math.max((model.bpm + hard.bpm) * 0.5f, 1.0f);
        return gap > MAX_MODEL_BPM_GAP ? hard : model;
    }

    public String autoNote() {
        return autoNote;
    }

    private EqCurve learnedEq;

    private final float[] eqGains = new float[3];

    private final Biquad[] fromHp;

    private final Biquad[] toLp;

    private final Biquad[] sweepOut;

    private final Reverb reverb;

    private final Delay delay;
    private final boolean delayRoute;
    private static final float DELAY_DAMPING = 0.35f;
    private double beatSecs = 0.5;
    private final boolean asymRoute;
    private final boolean reverbRoute;
    private float sweepHz = -1f;

    private final dev.t1m3.qplayer.audio.core.AudioBuf spaceScratch;

    static final float SPACE_MAX_SHIFT = 0.4f;

    static final double SPACE_MONO_RATIO = 0.02;

    public AiMix(MixStyle style, int rate, int channels) {
        this(style, rate, channels, MixTuning.defaults());
    }

    public AiMix(MixStyle style, int rate, int channels, MixTuning tuning) {
        this(style, rate, channels, tuning, null);
    }

    public AiMix(
            MixStyle style,
            int rate,
            int channels,
            MixTuning tuning,
            java.util.function.DoubleSupplier harmonicSecs) {
        int r = Math.max(rate, 1);
        int ch = Math.max(channels, 1);
        this.harmonicSecs = harmonicSecs;
        this.style = style;
        this.rate = r;
        this.channels = ch;
        this.tuning = new MixTuning(tuning);
        this.levelMatcher = new LevelMatcher(r);
        this.lowDucker =
                this.tuning.lowDuck > 0.0f ? new LowDucker(r, ch, this.tuning.crossoverHz) : null;
        this.spaceScratch =
                this.tuning.spaceWidth > 0.0f
                        ? new dev.t1m3.qplayer.audio.core.AudioBuf(
                                dev.t1m3.qplayer.audio.core.ChannelLayout.of(ch),
                                2 * Deck.RENDER_FRAMES)
                        : null;
        this.window = Automix.fallbackWindowWith(style, r, tuning.windowScale);
        this.outroTempo = null;
        this.introTempo = null;
        this.outroTailSecs = 0.0;
        this.introRate = r;
        this.cutAt = 0.0f;
        this.cutForcedAt = null;
        this.autoNote = null;
        this.outroSource = TempoSource.COMPUTED;
        this.introSource = TempoSource.COMPUTED;
        this.fromSplit = new SplitLowHigh[ch];
        this.toSplit = new SplitLowHigh[ch];
        this.toSplit2 = new SplitLowHigh[ch];
        for (int i = 0; i < ch; i++) {
            this.fromSplit[i] = new SplitLowHigh(r, tuning.crossoverHz);
            this.toSplit[i] = new SplitLowHigh(r, tuning.crossoverHz);
            this.toSplit2[i] = new SplitLowHigh(r, EQ_MID_HZ);
        }
        this.fromHp = new Biquad[ch];
        this.toLp = new Biquad[ch];
        this.reverbRoute =
                style == MixStyle.CROSSFADE_OVERLAP_REVERB
                        || style == MixStyle.CROSSFADE_RINGOUT_REVERB
                        || style == MixStyle.POP_REVERB
                        || style == MixStyle.FILTER_SOFT_REVERB
                        || style == MixStyle.FILTER_LONG_OUT_SHORT_IN_REVERB;
        this.asymRoute =
                style == MixStyle.FILTER_LONG_OUT_SHORT_IN
                        || style == MixStyle.FILTER_LONG_OUT_SHORT_IN_DELAY
                        || style == MixStyle.FILTER_LONG_OUT_SHORT_IN_REVERB
                        || style == MixStyle.FILTER_DYNAMIC_IN;
        this.delayRoute =
                style == MixStyle.HIPHOP_DELAY_OUT
                        || style == MixStyle.POP_DELAY_OUT
                        || style == MixStyle.FILTER_LONG_OUT_SHORT_IN_DELAY;
        boolean delayOn = delayRoute && tuning.delaySend > 0f && tuning.delayWet > 0f;
        this.delay = delayOn ? new Delay(rate, ch, tuning.delayFeedback, DELAY_DAMPING) : null;
        boolean reverbOn = reverbRoute && tuning.reverbSend > 0f && tuning.reverbWet > 0f;
        this.reverb =
                reverbOn
                        ? new Reverb(rate, ch, tuning.reverbDecaySecs, tuning.reverbDamping)
                        : null;
        boolean sweepOn =
                tuning.filterSweepFromHz > 0f
                        && tuning.filterSweepToHz > 0f
                        && tuning.filterSweepFromHz > tuning.filterSweepToHz;
        this.sweepOut = sweepOn ? new Biquad[ch] : null;
        if (sweepOn) {
            for (int i = 0; i < ch; i++) {
                this.sweepOut[i] =
                        Biquad.lowPass(rate, tuning.filterSweepFromHz, tuning.filterSweepQ);
            }
        }
        for (int i = 0; i < ch; i++) {
            this.fromHp[i] = Biquad.highPass(r, tuning.showHpFrom, 0.707f);
            this.toLp[i] = Biquad.lowPass(r, tuning.showLpFrom, 0.707f);
        }
    }

    public static AiMix withTuning(MixStyle style, int rate, int channels, MixTuning tuning) {
        return new AiMix(style, rate, channels, tuning);
    }

    public MixTuning tuning() {
        return new MixTuning(tuning);
    }

    public boolean tuningForStyleLoop() {
        return tuning.styleLoopEnabled;
    }

    public MixStyle style() {
        return style;
    }

    public int channels() {
        return channels;
    }

    public int rate() {
        return rate;
    }

    public long window() {
        return window;
    }

    public Float outroBpm() {
        return outroTempo == null ? null : Float.valueOf(outroTempo.bpm);
    }

    public Float introBpm() {
        return introTempo == null ? null : Float.valueOf(introTempo.bpm);
    }

    public void reset() {
        if (levelMatcher != null) {
            levelMatcher.reset();
        }
        if (lowDucker != null) {
            lowDucker.reset();
        }
        for (SplitLowHigh s : fromSplit) {
            s.reset();
        }
        for (SplitLowHigh s : toSplit) {
            s.reset();
        }
        for (SplitLowHigh s : toSplit2) {
            s.reset();
        }
        learnedEq = null;
        for (Biquad f : fromHp) {
            f.reset();
        }
        for (Biquad f : toLp) {
            f.reset();
        }
        if (sweepOut != null) {
            for (Biquad f : sweepOut) {
                f.reset();
            }
            sweepHz = -1f;
        }
        if (reverb != null) {
            reverb.reset();
        }
        if (delay != null) {
            delay.reset();
        }
    }

    @Override
    public long windowFrames(int sampleRate) {
        return window;
    }

    private static volatile int probeMuteSide = 0;

    private double carveHzA = 0.0;
    private double carveHzB = 0.0;
    private float carveGainDb = 0f;
    private int carveVersion = 0;
    private double[] carveHz;
    private float[] carveGain;
    private Biquad[] carveA;
    private Biquad[] carveB;
    private int carveBuiltVersion = -1;
    private long carveWindowFrames;

    public void setCarveWindowFrames(long frames) {
        carveWindowFrames = Math.max(0, frames);
    }

    public void setCarveBands(double hzA, double hzB, float gainDb) {
        carveHzA = hzA;
        carveHzB = hzB;
        carveGainDb = gainDb;
        carveHz = null;
        carveGain = null;
        carveVersion++;
    }

    public void setCarvePlan(double[] hz, float[] gainDb) {
        carveHz = hz == null ? null : hz.clone();
        carveGain = gainDb == null ? null : gainDb.clone();
        carveHzA = 0.0;
        carveHzB = 0.0;
        carveGainDb = -1f;
        carveVersion++;
    }

    public static void setProbeMuteSide(int side) {
        probeMuteSide = side;
    }

    @Override
    public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
        ensureCarve();
        if (carveA != null && from != null) {
            applyCarve(carveA, from, progress, false);
        }
        if (carveB != null && to != null) {
            applyCarve(carveB, to, progress, true);
        }
        int mute = probeMuteSide;
        if (mute == 1 && from != null) {
            zeroFrames(from);
        } else if (mute == 2 && to != null) {
            zeroFrames(to);
        }
        int produced = renderBase(out, from, to, progress);
        if (reverb != null && produced > 0) {
            applyReverb(out, from, produced);
        }
        if (delay != null && produced > 0) {
            applyDelay(out, from, produced);
        }
        return produced;
    }

    private void ensureCarve() {
        if (carveBuiltVersion == carveVersion) {
            return;
        }
        carveBuiltVersion = carveVersion;
        carveA = null;
        carveB = null;
        double[] planHz = carveHz;
        float[] planGain = carveGain;
        if (planHz != null && planGain != null) {
            int n = Math.min(planHz.length, planGain.length);
            int used = 0;
            for (int i = 0; i < n; i++) {
                if (planHz[i] > 0.0 && planGain[i] < 0f) {
                    used++;
                }
            }
            if (used > 0) {
                carveA = new Biquad[channels * used];
                int at = 0;
                for (int c = 0; c < channels; c++) {
                    for (int i = 0; i < n; i++) {
                        if (planHz[i] > 0.0 && planGain[i] < 0f) {
                            carveA[at++] =
                                    Biquad.peaking(rate, (float) planHz[i], 1.2f, planGain[i]);
                        }
                    }
                }
            }
        } else if (carveGainDb < 0f) {
            if (carveHzA > 0.0) {
                carveA = new Biquad[channels];
                for (int i = 0; i < channels; i++) {
                    carveA[i] = Biquad.peaking(rate, (float) carveHzA, 1.2f, carveGainDb);
                }
            }
            if (carveHzB > 0.0) {
                carveB = new Biquad[channels];
                for (int i = 0; i < channels; i++) {
                    carveB[i] = Biquad.peaking(rate, (float) carveHzB, 1.2f, carveGainDb);
                }
            }
        }
    }

    private void applyCarve(Biquad[] chain, AudioBuf buf, float progress, boolean incoming) {
        float[] back = buf.backingArray();
        int n = buf.frames();
        int chs = buf.channels();
        int per = chain.length / Math.max(1, chs);
        long duration = Math.max(1, carveWindowFrames > 0 ? carveWindowFrames : window);
        double seconds = duration / (double) Math.max(1, rate);
        for (int c = 0; c < chs; c++) {
            int base = buf.channelBase(c);
            for (int i = 0; i < n; i++) {
                float dry = back[base + i];
                float filtered = dry;
                for (int f = 0; f < per; f++)
                    filtered = chain[c * per + f].process(filtered);
                float p = progress + (float) ((i + .5 - n * .5) / duration);
                float wet = HarmonicBlend.entryCurve(incoming ? 1 - p : p, seconds);
                // Keep the filters running even at zero wet, so their state is warm.
                back[base + i] = dry + (filtered - dry) * wet;
            }
        }
    }

    private static void zeroFrames(AudioBuf buf) {
        float[] back = buf.backingArray();
        int n = buf.frames();
        for (int c = 0; c < buf.channels(); c++) {
            int base = buf.channelBase(c);
            for (int i = 0; i < n; i++) {
                back[base + i] = 0f;
            }
        }
    }

    private void applyReverb(AudioBuf out, AudioBuf from, int n) {
        int chs = Math.min(out.channels(), from.channels());
        float send = tuning.reverbSend;
        float wet = tuning.reverbWet;
        for (int ch = 0; ch < chs; ch++) {
            java.nio.FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                float r = reverb.process(ch, sampleOf(from, ch, i) * send);
                o.put(i, o.get(i) + r * wet);
            }
        }
    }

    private void applyDelay(AudioBuf out, AudioBuf from, int n) {
        long frames = Math.round(beatSecs * tuning.delayBeats * (double) rate);
        delay.setDelayFrames(frames);
        int chs = Math.min(out.channels(), from.channels());
        float send = tuning.delaySend;
        float wet = tuning.delayWet;
        for (int ch = 0; ch < chs; ch++) {
            java.nio.FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                float d = delay.process(ch, sampleOf(from, ch, i) * send);
                o.put(i, o.get(i) + d * wet);
            }
        }
    }

    Mixer.Gains gains(float p) {
        float s = tuning.shortInFraction;
        if (!asymRoute || s <= 0f || s >= 1f) {
            return Mixer.equalPowerGains(p);
        }
        double thA = (Math.PI / 2.0) * Math.max(0.0, Math.min(1.0, (double) p));
        double thB = (Math.PI / 2.0) * Math.min(1.0, (double) p / Math.max(0.05, (double) s));
        return new Mixer.Gains((float) Math.cos(thA), (float) Math.sin(thB));
    }

    private int renderBase(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
        float p0 = Automix.clampF32(progress, 0.0f, 1.0f);
        float spaceWidth = tuning == null ? 0.0f : tuning.spaceWidth;
        boolean fits = spaceScratch != null && from.frames() <= spaceScratch.capacityFrames();
        if (spaceWidth > 0.0f
                && fits
                && style != MixStyle.BEAT_CUT
                && from.channels() >= 2
                && from.frames() > 0
                && !isEssentiallyMono(from)) {
            float curve = spaceWidth * (float) Math.sin(Math.PI * (double) p0);
            if (curve > 1e-4f) {
                float widen = curve * SPACE_MAX_SHIFT;
                Mixer.copyWithWidth(
                        from,
                        spaceScratch,
                        Math.min(from.frames(), spaceScratch.capacityFrames()),
                        1.0f - widen,
                        1.0f + widen);
                from = spaceScratch;
            }
        }
        Transition harmonic = harmonicBlend;
        if (harmonic != null) {
            return harmonic.render(out, from, to, progress);
        }
        float p = Automix.clampF32(progress, 0.0f, 1.0f);
        int n = Math.min(out.capacityFrames(), Math.max(from.frames(), to.frames()));
        if (n == 0) {
            out.setFrames(0);
            return 0;
        }

        switch (style) {
            case PLAIN_BLEND:
            case AUTO:
                return renderPlainBlend(out, from, to, p);
            case BEAT_CUT:
                return renderBeatCut(out, from, to, p);
            case BASS_SWAP:
                return renderBassSwap(out, from, to, n, p);
            case TAIL_RIDE:
                return renderTailRide(out, from, to, p);
            case SHOW_MODE:
                return renderShowMode(out, from, to, n, p);
            case GAPLESS:
            case SPLICE:
                return renderBeatCut(out, from, to, p);
            case CROSSFADE_LONG:
            case CROSSFADE_OVERLAP:
            case CROSSFADE_OVERLAP_REVERB:
            case NO_VOCAL_CLASH:
            case POP_REVERB:
                return renderPlainBlend(out, from, to, p);
            case CROSSFADE_RINGOUT:
            case CROSSFADE_RINGOUT_REVERB:
            case HIPHOP_DELAY_OUT:
            case POP_DELAY_OUT:
                return renderTailRide(out, from, to, p);
            case FILTER_HIGH_TO_LOW:
            case FILTER_LONG_HIGH_TO_LOW:
            case FILTER_SHORT_HIGH_TO_LOW:
            case FILTER_SOFT_REVERB:
                return renderBassSwap(out, from, to, n, p, true);
            case FILTER_EXPANSION:
            case FILTER_LONG_OUT_SHORT_IN:
            case FILTER_DYNAMIC_IN:
            case FILTER_LONG_OUT_SHORT_IN_DELAY:
            case FILTER_LONG_OUT_SHORT_IN_REVERB:
            case DANCE_ANY_KEY:
                return learnedEq == null
                        ? renderPlainBlend(out, from, to, p)
                        : renderLearnedEq(out, from, to, n, p);
            case HIPHOP_LOOPING:
            case DANCE_REPEATER:
                return renderPlainBlend(out, from, to, p);
            case LEARNED_EQ:
                return learnedEq == null
                        ? renderPlainBlend(out, from, to, p)
                        : renderLearnedEq(out, from, to, n, p);
            default:
                throw new IllegalStateException("unknown transition: " + style);
        }
    }

    @Override
    public String name() {
        return harmonicBlend != null ? style.label() + " -> harmonic blend" : style.label();
    }

    @Override
    public boolean rewritesSamples() {
        return harmonicBlend != null || style.rewritesSamples();
    }

    @Override
    public PitchPlan pitchAt(float progress) {
        Transition harmonic = harmonicBlend;
        return harmonic != null ? harmonic.pitchAt(progress) : PitchPlan.ZERO;
    }

    @Override
    public PitchPlan pitchAt(float progress, double windowSecs) {
        Transition harmonic = harmonicBlend;
        return harmonic != null ? harmonic.pitchAt(progress, windowSecs) : PitchPlan.ZERO;
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
        window = Automix.fallbackWindowWith(style, this.rate, tuning.windowScale);
        window = Math.min(maxPlaybackWindowFrames, fixedWindowFrames > 0 ? fixedWindowFrames : window);
        carveWindowFrames = 0;
        harmonicBlend = null;
        harmonicPlan = null;
        harmonicCue = null;
        autoCue = null;
        outroTempo = null;
        introTempo = null;
        outroTailSecs = 0.0;
        introRate = this.rate;
        cutAt = 0.0f;
        cutForcedAt = null;
        autoNote = null;
        outroSource = TempoSource.COMPUTED;
        introSource = TempoSource.COMPUTED;
        reset();

        final Automix.Mono[] reads = new Automix.Mono[2];
        final AudioException[] readErrors = new AudioException[2];
        java.util.concurrent.Future<?> incoming =
                READ_POOL.submit(
                        new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    reads[1] =
                                            Automix.readMono(
                                                    intro,
                                                    introHeaders,
                                                    false,
                                                    Automix.ANALYSIS_SECS);
                                } catch (AudioException e) {
                                    readErrors[1] = e;
                                }
                            }
                        });
        try {
            reads[0] = Automix.readMono(outro, outroHeaders, true, Automix.ANALYSIS_SECS);
        } catch (AudioException e) {
            readErrors[0] = e;
        }
        try {
            incoming.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause);
        }
        String readFailure = readFailureNote(reads[0], readErrors[0], reads[1], readErrors[1]);
        if (readFailure != null) {
            return failurePlan(readFailure);
        }
        Automix.Mono tail = reads[0];
        Automix.Mono head = reads[1];

        float[] tailPcm = tail.pcm;
        int tailRate = tail.rate;
        Double outroDurOpt = tail.durationSecs;
        introRate = head.rate;
        outroTailSecs = (double) tailPcm.length / (double) Math.max(tailRate, 1);
        double outroDur = outroDurOpt == null ? 0.0 : outroDurOpt.doubleValue();

        Budget budget = new Budget();
        TempoResultHolder rOut = tempoOf(tailPcm, tailRate, budget);
        TempoResultHolder rIn = tempoOf(head.pcm, head.rate, budget);
        Analysis.Tempo tOut = rOut.tempo;
        Analysis.Tempo tIn = rIn.tempo;

        AutoCue.Envelope outEnv = null;
        AutoCue.Envelope inEnv = null;
        try {
            outEnv = AutoCue.scan(outro, outroHeaders);
            inEnv = AutoCue.scanIntro(intro, introHeaders);
        } catch (RuntimeException e) {
            outEnv = null;
            inEnv = null;
        }
        Analysis.Tempo sanOut = saneTempo(tOut, outEnv == null ? null : outEnv.tempo);
        Analysis.Tempo sanIn = saneTempo(tIn, inEnv == null ? null : inEnv.tempo);
        outroSource = rOut.source;
        introSource = rIn.source;
        outroTempo = tOut;
        introTempo = tIn;

        boolean autoPick = style == MixStyle.AUTO;
        Harmonic.Match harmony = null;
        if (autoPick) {
            harmony = Harmonic.match(tailPcm, tailRate, head.pcm, head.rate);
            MaterialProfile profile =
                    MaterialProfile.of(
                            tailPcm,
                            head.pcm,
                            tailPcm.length / (double) Math.max(1, tailRate),
                            head.pcm.length / (double) Math.max(1, head.rate),
                            tailRate);
            Chooser.AutoChoice choice;
            if (RouteScore.enabled()) {
                RouteGates.Signals sig = new RouteGates.Signals();
                sig.tempoDiff =
                        sanOut == null || sanIn == null
                                ? null
                                : Double.valueOf(
                                        Math.abs(sanOut.bpm - sanIn.bpm)
                                                / Math.max((sanOut.bpm + sanIn.bpm) * 0.5f, 1.0f));
                sig.tailDecayDbPerSec = Double.valueOf(profile.tailDecayDbPerSec());
                sig.trailingSpanSecs = Double.valueOf(trailingSpanSecs(outEnv));
                float[] stereoLeft = null;
                float[] stereoRight = null;
                int stereoFrames = 0;
                try {
                    int want =
                            (int)
                                    Math.round(
                                            Math.min(
                                                    1.0,
                                                    Math.max(
                                                            0.2,
                                                            tailPcm.length
                                                                    / (double)
                                                                            Math.max(
                                                                                    1, tailRate))));
                    float[][] st =
                            Automix.readStereoWindow(
                                    outro,
                                    outroHeaders,
                                    Math.max(
                                            0.0,
                                            tail.durationSecs == null
                                                    ? 0.0
                                                    : tail.durationSecs.doubleValue() - want),
                                    want,
                                    tailRate);
                    if (st != null && st.length >= 2) {
                        stereoLeft = st[0];
                        stereoRight = st[1];
                        stereoFrames = want;
                    }
                } catch (RuntimeException e) {
                    stereoFrames = 0;
                }
                double centre = RouteGates.centreRatio(stereoLeft, stereoRight, stereoFrames);
                boolean centreReliable = RouteGates.vocalPresenceIsReliable(centre);
                if (centreReliable) {
                    sig.vocalOverlapSecs = Double.valueOf(1.0);
                    sig.vocalOverlapReliable = Boolean.TRUE;
                    double kindWindow = Math.max(0.5, profile.overlapSecs());
                    double kindCut = Math.max(0.0, outroDur - kindWindow);
                    double overlapStrength =
                            AutoCue.vocalOverlapStrength(outEnv, kindCut, inEnv, 0.0, kindWindow);
                    if (!Double.isNaN(overlapStrength)) {
                        sig.vocalOverlapStrength = Double.valueOf(overlapStrength);
                    }
                    int kindA = AutoCue.vocalKind(outEnv, kindCut, kindCut + kindWindow);
                    int kindB = AutoCue.vocalKind(inEnv, 0.0, kindWindow);
                    int kind =
                            kindA >= 0 && kindB >= 0
                                    ? (overlapStrengthOfSide(outEnv, kindCut, kindWindow)
                                                    >= overlapStrengthOfSide(inEnv, 0.0, kindWindow)
                                            ? kindA
                                            : kindB)
                                    : Math.max(kindA, kindB);
                    if (kind >= 0) {
                        sig.vocalKind = Integer.valueOf(kind);
                    }
                } else {
                    sig.vocalOverlapSecs =
                            Double.valueOf(estimateVocalOverlapSecs(tailPcm, tailRate));
                    sig.vocalOverlapReliable = Boolean.FALSE;
                }
                sig.incomingQuieter = Boolean.valueOf(incomingIsQuieter(tailPcm, head, tailRate));
                if (outEnv != null
                        && outEnv.tempo != null
                        && outEnv.tempo.periodFrames > 0
                        && outEnv.beatRate > 0) {
                    double b = outEnv.tempo.periodFrames / (double) outEnv.beatRate;
                    if (b > 0.05 && b < 5.0) {
                        this.beatSecs = b;
                    }
                }
                double beatsA = beatCount(profile.overlapSecs(), outEnv);
                double beatsB = beatCount(profile.overlapSecs(), inEnv);
                sig.outroBeats = Double.valueOf(beatsA);
                sig.introBeats = Double.valueOf(beatsB);
                sig.matchingBars = Boolean.valueOf(Math.abs(beatsA - beatsB) <= 1.0);
                sig.bpmA = sanOut == null ? null : Double.valueOf(sanOut.bpm);
                sig.bpmB = sanIn == null ? null : Double.valueOf(sanIn.bpm);
                sig.keyScore = harmony == null ? null : Double.valueOf(harmony.plainScore);
                sig.lowClash = Double.valueOf(profile.lowClash());
                sig.dropReliable = Double.valueOf(profile.dropReliable());
                sig.overlapSecs = Double.valueOf(profile.overlapSecs());
                sig.buildUp = Boolean.valueOf(profile.buildUp());
                float bpmForGenre = sanOut == null ? 0f : sanOut.bpm;
                sig.outgoingLoudnessSignificant =
                        Boolean.valueOf(RouteGates.outgoingLoudnessSignificant(tailPcm, tailRate));
                if (outEnv != null && inEnv != null) {
                    double melodyWindow = Math.max(0.5, profile.overlapSecs());
                    double melodyCut = Math.max(0.0, outroDur - melodyWindow);
                    sig.melody =
                            AutoCue.melodyOrNull(
                                    AutoCue.melodyMatch(
                                            outEnv, melodyCut, inEnv, 0.0, melodyWindow));
                }
                sig.genres =
                        EqFeatureExtractor.genreScores(
                                bpmForGenre,
                                profile.onset(),
                                profile.lowRatio(),
                                Math.max(0f, 1f - profile.lowRatio()));
                java.util.List<MixStyle> styles = new java.util.ArrayList<MixStyle>();
                java.util.List<java.util.List<Double>> sets =
                        new java.util.ArrayList<java.util.List<Double>>();
                java.util.List<Double> pos = new java.util.ArrayList<Double>();
                for (MixStyle cand : ROUTE_CANDIDATES) {
                    java.util.List<Double> f = RouteGates.factorsFor(cand, sig);
                    if (f == null) {
                        continue;
                    }
                    styles.add(cand);
                    sets.add(f);
                    pos.add(Double.valueOf(0.0));
                }
                MixStyle pick = RouteScore.best(styles, sets, pos);
                String kernelNote =
                        "auto: score kernel (base × product of factors + 0.001·D) picked "
                                + (pick == null
                                        ? "**no candidate passed** ("
                                                + styles.size()
                                                + "/"
                                                + ROUTE_CANDIDATES.length
                                                + " candidates cleared the gates ⇒ falling back to"
                                                + " legacy chooser)"
                                        : pick.label())
                                + ", melody "
                                + (sig.melody == null
                                        ? "not measured (neutral)"
                                        : String.format(
                                                java.util.Locale.ROOT,
                                                "%.3f",
                                                sig.melody.doubleValue()))
                                + ", vocal overlap "
                                + (sig.vocalOverlapReliable == Boolean.TRUE
                                        ? "reliable"
                                        : "unreliable");
                choice =
                        pick == null
                                ? withKernelNote(
                                        Chooser.choose(
                                                sanOut, sanIn, harmony, tuning, profile, 8.0),
                                        kernelNote)
                                : new Chooser.AutoChoice(
                                        pick,
                                        "auto: score kernel (base × product of factors + 0.001·D)"
                                            + " picked "
                                                + pick.label()
                                                + ", melody "
                                                + (sig.melody == null
                                                        ? "not measured (neutral)"
                                                        : String.format(
                                                                java.util.Locale.ROOT,
                                                                "%.3f",
                                                                sig.melody.doubleValue()))
                                                + ", vocal overlap "
                                                + (sig.vocalOverlapReliable == Boolean.TRUE
                                                        ? "reliable"
                                                        : "unreliable")
                                                + " (entry basis)");
            } else {
                choice = Chooser.choose(sanOut, sanIn, harmony, tuning, profile, 8.0);
            }
            style = choice.style;
            this.autoProfile = profile;
            this.lastTempoOut = tOut;
            this.lastTempoIn = tIn;
            autoNote = choice.why;
        }

        long fallbackFrames = Automix.fallbackWindowWith(style, this.rate, tuning.windowScale);

        long idealFrames;
        if (tOut != null && style.beats() > 0.0f) {
            double period = (double) tOut.periodFrames / (double) Math.max(tailRate, 1);
            double alias =
                    sanOut == null || sanIn == null
                            ? 0.0
                            : RouteGates.tempoAlias(sanOut.bpm, sanIn.bpm);
            double beats =
                    RouteGates.snapBeatsToQuantum((double) style.beats(), alias)
                            * (double) tuning.windowScale;
            double want = Math.round(beats * period * (double) this.rate);
            idealFrames = Math.max((long) want, 1L);
        } else {
            idealFrames = fallbackFrames;
        }

        idealFrames =
                Automix.limitWindowWith(idealFrames, outroDur, this.rate, tuning.maxWindowFraction);
        idealFrames = Math.min(maxPlaybackWindowFrames,
                fixedWindowFrames > 0 ? fixedWindowFrames : idealFrames);
        if (tOut != null) {
            double periodSecs = (double) tOut.periodFrames / (double) Math.max(tailRate, 1);
            double need = (double) idealFrames / (double) this.rate + periodSecs + 1.0;
            if (periodSecs > 1e-3 && need > outroTailSecs + 1e-3 && need <= outroDur) {
                try {
                    Automix.Mono again = Automix.readMono(outro, outroHeaders, true, need);
                    tailPcm = again.pcm;
                    outroTailSecs = (double) tailPcm.length / (double) Math.max(again.rate, 1);
                    TempoResultHolder againTempo = tempoOf(tailPcm, tailRate, budget);
                    tOut = againTempo.tempo;
                    outroSource = againTempo.source;
                    outroTempo = tOut;
                } catch (AudioException e) {
                }
            }
        }

        long alignedWindow = idealFrames;
        if (tOut != null) {
            double fileRate = (double) Math.max(tailRate, 1);
            double period = (double) tOut.periodFrames / fileRate;
            double phase = (double) tOut.phaseFrames / fileRate;
            double tailDur = outroTailSecs;
            double w0 = (double) idealFrames / (double) this.rate;

            if (period > 1e-3 && tailDur > w0) {
                double target = tailDur - w0;
                double k = Math.floor((target - phase) / period);
                double start = phase + k * period;
                if (start >= 0.0 && start < tailDur) {
                    long aligned = Math.round((tailDur - start) * (double) this.rate);
                    long maxOk = idealFrames + (long) (period * (double) this.rate) + 1L;
                    if (aligned >= idealFrames && aligned <= maxOk) {
                        alignedWindow = aligned;
                    }
                }
            }
        }
        long styleWindow = alignedWindow;
        styleWindow = Math.min(maxPlaybackWindowFrames,
                fixedWindowFrames > 0 ? fixedWindowFrames : styleWindow);
        window = styleWindow;
        if (Boolean.getBoolean("qplayer.trace.window")) {
            System.out.println(
                    String.format(
                            java.util.Locale.ROOT,
                            "[window-diag] ideal=%.2fs aligned=%.2fs style=%.2fs mode=%s",
                            idealFrames / (double) this.rate,
                            alignedWindow / (double) this.rate,
                            styleWindow / (double) this.rate,
                            String.valueOf(style)));
        }

        if (autoPick && harmonicBlend == null && playbackAutoCue && fixedWindowFrames == 0) {
            java.util.function.DoubleSupplier supplier = harmonicSecs;
            double nominal = supplier == null ? 6.0 : supplier.getAsDouble();
            if (!(nominal > 0.0)) {
                nominal = 6.0;
            }
            double baselineSecs = (double) styleWindow / (double) Math.max(1, this.rate);
            try {
                if (outEnv == null) {
                    outEnv = AutoCue.scan(outro, outroHeaders);
                }
                if (inEnv == null) {
                    inEnv = AutoCue.scanIntro(intro, introHeaders);
                }
                dev.t1m3.qplayer.audio.mix.TempoRamp ramp =
                        AutoCueTransition.rampFor(outEnv, inEnv, nominal);
                double rampCap = ramp != null && ramp.active ? ramp.windowSecs : 0.0;
                double[] withBaseline =
                        rampCap > 0.0
                                ? AutoCueTransition.windowCandidates(rampCap, true)
                                : AutoCueTransition.windowCandidates(nominal, true);
                for (int i = 0; i < withBaseline.length; i++)
                    withBaseline[i] = Math.min(withBaseline[i], maxPlaybackWindowFrames / (double) this.rate);
                AutoCue.PlanChoice decided =
                        AutoCue.comparePlans(
                                outEnv,
                                inEnv,
                                baselineSecs,
                                withBaseline,
                                harmony == null ? 0.0 : (double) harmony.plainScore,
                                harmony != null && harmony.shouldShift);
                if (decided != null) {
                    autoNote = autoNote + "; " + decided.why;
                    if (!decided.harmonic && decided.cue != null) {
                        autoCue = decided.cue;
                        autoNote =
                                autoNote
                                        + "; using auto cue (outgoing "
                                        + String.format(
                                                java.util.Locale.ROOT,
                                                "%.3f",
                                                decided.cue.outroCutSecs)
                                        + " s · incoming "
                                        + String.format(
                                                java.util.Locale.ROOT,
                                                "%.3f",
                                                decided.cue.introEntrySecs)
                                        + " s)";
                    }
                    if (decided.harmonic && decided.windowSecs > 0.0) {
                        ModeHolder prepared = new ModeHolder();
                        harmonicBlend =
                                new HarmonicBlend(
                                        this.rate, this.channels, (float) decided.windowSecs);
                        prepared.plan =
                                harmonicBlend.prepare(
                                        outro, intro, this.rate, outroHeaders, introHeaders);
                        harmonicPlan = prepared.plan;
                        harmonicCue = decided.cue;
                        window = Math.max(1L, Math.round(decided.windowSecs * this.rate));
                        autoNote = autoNote + "; " + decided.note;
                    }
                }
            } catch (RuntimeException e) {
                AutoMixLog.debug(
                        "[qplayer-engine] AI plan comparison skipped ({}), keeping transition"
                            + " decision: {}",
                        e.toString(),
                        style.label());
            }
        }

        long cutFrames = Math.round(tuning.cutFadeSecs * (float) this.rate);
        long leadFrames = Math.round(tuning.cutLeadSecs * (float) this.rate);
        long cutPos = cutFrames + leadFrames;
        if (style == MixStyle.BEAT_CUT && window > cutPos) {
            cutAt = Automix.clampF32((float) (window - cutPos) / (float) window, 0.0f, 1.0f);
        } else {
            cutAt = 0.0f;
        }

        float introStartAt = style == MixStyle.BEAT_CUT ? cutAt : 0.0f;

        double skipSecs = 0.0;
        if (tIn != null) {
            double skip = (double) tIn.phaseFrames / (double) Math.max(head.rate, 1);
            if (skip > 1e-4) {
                skipSecs = skip;
            }
        }

        if (harmonicBlend != null && harmonicPlan != null) {
            double entry = harmonicCue == null ? harmonicPlan.skipSecs : harmonicCue.introEntrySecs;
            double droppedTail =
                    harmonicCue == null ? harmonicPlan.outroTailSecs : harmonicCue.tailDropSecs;
            return new TransitionPlan(
                    entry,
                    harmonicPlan.pitch,
                    harmonicPlan.introStartAt,
                    describePlan(),
                    droppedTail);
        }
        double entrySecs = autoCue != null ? autoCue.introEntrySecs : skipSecs;
        double droppedTail = outroTailSecs;
        if (autoCue != null && outroDur > 0.0) {
            double windowSecsHere = window / (double) Math.max(1, this.rate);
            droppedTail = Math.max(0.0, outroDur - (autoCue.outroCutSecs + windowSecsHere));
        }
        if (!playbackAutoCue) { entrySecs = 0; droppedTail = 0; }
        return new TransitionPlan(
                entrySecs, PitchPlan.ZERO, introStartAt, describePlan(), droppedTail);
    }

    private enum TempoSource {
        MODEL("model"),

        COMPUTED("computed");

        private final String label;

        TempoSource(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private static final class Budget {
        private double left = BeatModels.BUDGET_SECS;

        boolean spent() {
            return left <= 0.0;
        }

        void charge(long elapsedNanos) {
            left -= (double) elapsedNanos / 1e9;
        }
    }

    private TempoResultHolder tempoOf(float[] pcm, int fileRate, Budget budget) {
        String reason = null;
        if (!BeatModels.isInstalled()) {
            reason = "host installed no inference backend";
        } else if (budget.spent()) {
            reason = "model budget exhausted";
        }

        if (reason == null) {
            long t0 = System.nanoTime();
            BeatDetector det = null;
            try {
                det = BeatModels.openPreferred();
                MelParams mp = det.melParams();
                float[] mono = BeatModels.resampleMono(pcm, fileRate, mp.sampleRate);
                float[] beats = det.beats(mono);
                Analysis.Tempo tempo = Analysis.tempoFromBeats(beats, fileRate);
                if (tempo == null) {
                    reason = "model gave no usable beats (fewer than two beats)";
                } else {
                    budget.charge(System.nanoTime() - t0);
                    AutoMixLog.debug(
                            "AI mode beat match: {} gave {} beats, {}",
                            det.label(),
                            Integer.valueOf(beats.length),
                            tempo.describe());
                    return new TempoResultHolder(tempo, TempoSource.MODEL);
                }
            } catch (RuntimeException e) {
                reason = e.getClass().getSimpleName() + ": " + briefMessage(e);
            } finally {
                if (det != null) {
                    det.close();
                }
                if (reason != null) {
                    budget.charge(System.nanoTime() - t0);
                }
            }
        }

        if (reason != null) {
            if (modelMissingReported) {
                AutoMixLog.debug(
                        "AI mode beat match: model still unavailable ({}), this side falls back to"
                            + " computed",
                        reason);
            } else {
                modelMissingReported = true;
                AutoMixLog.warn(
                        "AI mode beat match: model unavailable ({}), this side falls back to"
                            + " computed (this line is logged once per process; later transitions"
                            + " still compute as usual)",
                        reason);
            }
        }
        Analysis.Tempo tracked = Analysis.tempoFromSpectralFluxTracked(pcm, fileRate);
        if (tracked != null) {
            return new TempoResultHolder(tracked, TempoSource.COMPUTED);
        }
        AutoMixLog.debug(
                "AI mode beat match: new pipeline gave no verdict ({}), falling back to the old RMS"
                    + " difference path",
                BeatTracker.lastRejectReason());
        return new TempoResultHolder(Analysis.analyze(pcm, fileRate), TempoSource.COMPUTED);
    }

    static String readFailureNote(
            Automix.Mono tail,
            AudioException tailError,
            Automix.Mono head,
            AudioException headError) {
        if (tailError != null) {
            return "outgoing track analysis failed ("
                    + tailError.getMessage()
                    + "), falling back to fixed window";
        }
        if (headError != null) {
            return "incoming track analysis failed ("
                    + headError.getMessage()
                    + "), falling back to fixed window";
        }
        if (tail == null || head == null) {
            return "read interrupted (prepare thread interrupted), falling back to fixed window";
        }
        return null;
    }

    private static final class TempoResultHolder {
        final Analysis.Tempo tempo;

        final TempoSource source;

        TempoResultHolder(Analysis.Tempo tempo, TempoSource source) {
            this.tempo = tempo;
            this.source = source;
        }
    }

    private static String briefMessage(Throwable e) {
        String s = e.getMessage();
        if (s == null) {
            return e.toString();
        }
        int nl = s.indexOf('\n');
        String first = (nl >= 0 ? s.substring(0, nl) : s).trim();
        return first.length() > 200 ? first.substring(0, 200) + "…" : first;
    }

    private TransitionPlan failurePlan(String note) {
        return new TransitionPlan(0.0, PitchPlan.ZERO, 0.0f, note);
    }

    private String describePlan() {
        String bpm;
        if (outroTempo != null && introTempo != null) {
            bpm =
                    String.format(
                            Locale.ROOT,
                            "%.1f BPM (%s) → %.1f BPM (%s)",
                            outroTempo.bpm,
                            outroSource.label(),
                            introTempo.bpm,
                            introSource.label());
        } else if (outroTempo != null) {
            bpm =
                    String.format(
                            Locale.ROOT,
                            "outgoing %.1f BPM (%s), incoming undecided",
                            outroTempo.bpm,
                            outroSource.label());
        } else if (introTempo != null) {
            bpm =
                    String.format(
                            Locale.ROOT,
                            "outgoing undecided, incoming %.1f BPM (%s)",
                            introTempo.bpm,
                            introSource.label());
        } else {
            bpm = "no beat detected, blending with fixed window";
        }
        double secs = (double) window / (double) rate;
        String body =
                harmonicBlend != null
                        ? String.format(
                                Locale.ROOT,
                                "waveform overlap (picked by AI comparison, window %.1f s)",
                                Double.valueOf(secs))
                        : String.format(
                                Locale.ROOT,
                                "%s (%s, window %.1f s)",
                                style.label(),
                                bpm,
                                Double.valueOf(secs));
        if (autoNote != null) {
            return autoNote
                    + "; "
                    + (harmonicBlend != null ? "actually running " : "selected ")
                    + body;
        }
        return body;
    }

    private int renderBeatCut(AudioBuf out, AudioBuf from, AudioBuf to, float p) {
        int n = Math.min(out.capacityFrames(), Math.max(from.frames(), to.frames()));
        if (n == 0) {
            out.setFrames(0);
            return 0;
        }
        float cut = cutAt;
        float perFrame = 1.0f / (float) Math.max(window, 1L);

        Float forcedFrom;
        if (from.frames() == 0) {
            if (cutForcedAt == null) {
                cutForcedAt = Float.valueOf(p);
            }
            forcedFrom = cutForcedAt;
        } else {
            cutForcedAt = null;
            forcedFrom = null;
        }

        out.setFrames(n);
        for (int ch = 0; ch < out.channels(); ch++) {
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                float pi = Automix.minF32(p + (float) i * perFrame, 1.0f);
                if (forcedFrom != null) {
                    float start = forcedFrom.floatValue();
                    float span =
                            Automix.minF32(
                                    tuning.cutFadeSecs
                                            * (float) rate
                                            / (float) Math.max(window, 1L),
                                    Automix.maxF32(1.0f - start, 1e-4f));
                    float local = Automix.clampF32((pi - start) / span, 0.0f, 1.0f);
                    Mixer.Gains g = Mixer.equalPowerGains(local);
                    o.put(i, sampleOf(from, ch, i) * g.from + sampleOf(to, ch, i) * g.to);
                } else if (pi < cut) {
                    o.put(i, sampleOf(from, ch, i));
                } else {
                    float local =
                            cut >= 1.0f
                                    ? 1.0f
                                    : Automix.clampF32((pi - cut) / (1.0f - cut), 0.0f, 1.0f);
                    Mixer.Gains g = Mixer.equalPowerGains(local);
                    o.put(i, sampleOf(from, ch, i) * g.from + sampleOf(to, ch, i) * g.to);
                }
            }
        }
        return n;
    }

    private static final float HF_STAGGER = 0.35f;

    private int renderBassSwap(AudioBuf out, AudioBuf from, AudioBuf to, int n, float p) {
        return renderBassSwap(out, from, to, n, p, false);
    }

    private int renderBassSwap(
            AudioBuf out, AudioBuf from, AudioBuf to, int n, float p, boolean sweep) {
        if (sweep && sweepOut != null) {
            double k = Math.max(0.0, Math.min(1.0, (double) p));
            float hz =
                    (float)
                            (tuning.filterSweepFromHz
                                    * Math.pow(
                                            tuning.filterSweepToHz
                                                    / (double) tuning.filterSweepFromHz,
                                            k));
            if (sweepHz < 0f || Math.abs(hz - sweepHz) > sweepHz * 0.005f) {
                sweepHz = hz;
                for (Biquad f : sweepOut) {
                    f.setLowPass(rate, hz, tuning.filterSweepQ);
                }
            }
        }
        float together = Automix.clampF32(tuning.bassSwapTogether, 0.0f, 0.9f);
        float hfF;
        float hfT;
        float lfF;
        float lfT;
        if (together <= 0.0f) {
            float split = Automix.clampF32(tuning.bassSwapSplit, 0.0f, 0.99f);
            if (split <= 0.0f) {
                hfF = 0.0f;
                hfT = 1.0f;
            } else {
                Mixer.Gains g = Mixer.equalPowerGains(Automix.minF32(p / split, 1.0f));
                hfF = g.from;
                hfT = g.to;
            }
            Mixer.Gains g =
                    Mixer.equalPowerGains(
                            Automix.clampF32((p - split) / (1.0f - split), 0.0f, 1.0f));
            lfF = g.from;
            lfT = g.to;
        } else {
            float arc = (1.0f - together) * 0.5f;
            float theta = Automix.togetherAngle(p, together);
            float thetaLf;
            if (p <= arc) {
                thetaLf = 0.0f;
            } else if (p >= arc + together) {
                thetaLf = FRAC_PI_2;
            } else {
                thetaLf = ((p - arc) / together) * FRAC_PI_2;
            }
            float hfSplit = Automix.clampF32(tuning.bassSwapSplit, 0.0f, 0.99f);
            if (hfSplit <= 0.0f || HF_STAGGER <= 0.0f) {
                hfF = (float) Math.cos((double) theta);
                hfT = (float) Math.sin((double) theta);
            } else {
                Mixer.Gains hg = Mixer.equalPowerGains(Automix.minF32(p / hfSplit, 1.0f));
                float c = HF_STAGGER;
                hfF = (1.0f - c) * (float) Math.cos((double) theta) + c * hg.from;
                hfT = (1.0f - c) * (float) Math.sin((double) theta) + c * hg.to;
            }
            lfF = (float) Math.cos((double) thetaLf);
            lfT = (float) Math.sin((double) thetaLf);
        }

        int chs = Math.min(out.channels(), fromSplit.length);
        out.setFrames(n);
        float[] split = new float[2];
        for (int ch = 0; ch < chs; ch++) {
            SplitLowHigh fs = fromSplit[ch];
            SplitLowHigh ts = toSplit[ch];
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                float aRaw = sampleOf(from, ch, i);
                if (sweep && sweepOut != null) {
                    aRaw = sweepOut[ch].process(aRaw);
                }
                fs.processInto(aRaw, split);
                float flo = split[0];
                float fhi = split[1];
                ts.processInto(sampleOf(to, ch, i), split);
                float tlo = split[0];
                float thi = split[1];
                o.put(i, flo * lfF + tlo * lfT + fhi * hfF + thi * hfT);
            }
        }
        for (int ch = chs; ch < out.channels(); ch++) {
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                o.put(i, 0.0f);
            }
        }
        return n;
    }

    private int renderPlainBlend(AudioBuf out, AudioBuf from, AudioBuf to, float p) {
        Mixer.Gains g = gains(p);
        return Mixer.mixTwoMatched(
                out,
                from,
                g.from,
                to,
                g.to,
                levelMatcher,
                blendCurve(p) * tuning.levelMatch,
                lowDucker,
                blendCurve(p) * tuning.lowDuck);
    }

    private int renderLearnedEq(AudioBuf out, AudioBuf from, AudioBuf to, int n, float p) {
        Mixer.Gains g = gains(p);
        learnedEq.gainsAt(p, eqGains);
        int chs = Math.min(out.channels(), toSplit.length);
        out.setFrames(n);
        float[] split = new float[2];
        float[] split2 = new float[2];
        for (int ch = 0; ch < chs; ch++) {
            SplitLowHigh ts = toSplit[ch];
            SplitLowHigh ts2 = toSplit2[ch];
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                float a = sampleOf(from, ch, i) * g.from;
                ts.processInto(sampleOf(to, ch, i), split);
                ts2.processInto(split[1], split2);
                float b =
                        split[0] * eqGains[0] * g.to
                                + split2[0] * eqGains[1] * g.to
                                + split2[1] * eqGains[2] * g.to;
                o.put(i, a + b);
            }
        }
        for (int ch = chs; ch < out.channels(); ch++) {
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                o.put(i, 0.0f);
            }
        }
        return n;
    }

    public void setLearnedEqCurve(EqCurve curve) {
        this.learnedEq = curve;
    }

    static final float EQ_MID_HZ = 2000f;

    private static boolean isEssentiallyMono(AudioBuf from) {
        int n = Math.min(from.frames(), 2 * Deck.RENDER_FRAMES);
        double midEnergy = 0.0;
        double sideEnergy = 0.0;
        FloatBuffer l = from.channel(0);
        FloatBuffer r = from.channel(1);
        for (int i = 0; i < n; i++) {
            double lv = l.get(i);
            double rv = r.get(i);
            double mid = (lv + rv) * 0.5;
            double side = (lv - rv) * 0.5;
            midEnergy += mid * mid;
            sideEnergy += side * side;
        }
        double total = midEnergy + sideEnergy;
        return total <= 0.0 || sideEnergy < SPACE_MONO_RATIO * total;
    }

    private static float blendCurve(float p) {
        if (p <= 0.0f || p >= 1.0f) {
            return 0.0f;
        }
        return (float) Math.sin(Math.PI * (double) p);
    }

    private int renderTailRide(AudioBuf out, AudioBuf from, AudioBuf to, float p) {
        float gf =
                (float)
                        Math.sqrt(
                                (double)
                                        Automix.maxF32(
                                                1.0f
                                                        - (float)
                                                                Math.pow(
                                                                        (double) p,
                                                                        (double)
                                                                                tuning.tailRideOutroPower),
                                                0.0f));
        float gt = (float) Math.pow((double) p, (double) tuning.tailRideIntroPower);
        return Mixer.mixTwoMatched(
                out,
                from,
                gf,
                to,
                gt,
                levelMatcher,
                blendCurve(p) * tuning.levelMatch,
                lowDucker,
                blendCurve(p) * tuning.lowDuck);
    }

    private int renderShowMode(AudioBuf out, AudioBuf from, AudioBuf to, int n, float p) {
        MixTuning t = tuning;
        float hp = t.showHpFrom + (t.showHpTo - t.showHpFrom) * (p * p);
        float lp = t.showLpFrom + (t.showLpTo - t.showLpFrom) * (p * p * p);
        for (Biquad f : fromHp) {
            f.updateHighPass(rate, hp, 0.707f);
        }
        for (Biquad f : toLp) {
            f.updateLowPass(rate, lp, 0.707f);
        }

        float gf =
                (float)
                        Math.pow(
                                (double) Automix.maxF32(1.0f - p, 0.0f),
                                (double) t.showOutroGainPower);
        float gt = (float) Math.pow((double) p, (double) t.showIntroGainPower);

        int chs = Math.min(out.channels(), fromHp.length);
        out.setFrames(n);
        for (int ch = 0; ch < chs; ch++) {
            Biquad fh = fromHp[ch];
            Biquad tl = toLp[ch];
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                float a = fh.process(sampleOf(from, ch, i)) * gf;
                float b = tl.process(sampleOf(to, ch, i)) * gt;
                o.put(i, a + b);
            }
        }
        for (int ch = chs; ch < out.channels(); ch++) {
            FloatBuffer o = out.channelWriter(ch);
            for (int i = 0; i < n; i++) {
                o.put(i, 0.0f);
            }
        }
        return n;
    }

    private static float sampleOf(AudioBuf buf, int ch, int i) {
        if (i < buf.frames() && ch < buf.channels()) {
            return buf.channel(ch).get(i);
        }
        return 0.0f;
    }

    @Override
    public String toString() {
        return "AiMix { style: \""
                + style.specName()
                + "\", rate: "
                + rate
                + ", window: "
                + window
                + ", outro_bpm: "
                + optionDebug(outroBpm())
                + ", intro_bpm: "
                + optionDebug(introBpm())
                + ", .. }";
    }

    private static String optionDebug(Float v) {
        return v == null ? "None" : "Some(" + v + ")";
    }

    private static Chooser.AutoChoice withKernelNote(Chooser.AutoChoice legacy, String kernelNote) {
        if (legacy == null) {
            return null;
        }
        String why =
                legacy.why == null || legacy.why.isEmpty()
                        ? kernelNote
                        : legacy.why + " (" + kernelNote + ")";
        return new Chooser.AutoChoice(legacy.style, why);
    }

    private static double trailingSpanSecs(AutoCue.Envelope env) {
        if (env == null || env.rms == null || env.rms.length == 0) {
            return 0.0;
        }
        float peak = 0f;
        for (float v : env.rms) {
            peak = Math.max(peak, v);
        }
        if (peak <= 0f) {
            return 0.0;
        }
        float floor = peak * 0.1f;
        int last = -1;
        for (int i = 0; i < env.rms.length; i++) {
            if (env.rms[i] >= floor) {
                last = i;
            }
        }
        if (last < 0) {
            return 0.0;
        }
        double hop = 0.02;
        return Math.max(0.0, (env.rms.length - 1 - last) * hop);
    }

    private static boolean incomingIsQuieter(float[] tailPcm, Automix.Mono head, int rate) {
        if (tailPcm == null || head == null || head.pcm == null || rate <= 0) {
            return false;
        }
        double a = dev.t1m3.qplayer.audio.dsp.Lufs.integrated(tailPcm, null, rate);
        double b = dev.t1m3.qplayer.audio.dsp.Lufs.integrated(head.pcm, null, head.rate);
        return !Double.isInfinite(a) && !Double.isInfinite(b) && b < a;
    }

    private static double overlapStrengthOfSide(
            AutoCue.Envelope env, double fromSecs, double windowSecs) {
        double[] strength = AutoCue.vocalStrength(env);
        if (strength == null) {
            return -1.0;
        }
        int from = (int) Math.floor(fromSecs / AutoCue.HOP_SECS);
        int n = (int) Math.round(windowSecs / AutoCue.HOP_SECS);
        double best = 0.0;
        for (int k = 0; k < n; k++) {
            int i = from + k;
            if (i >= 0 && i < strength.length) {
                best = Math.max(best, strength[i]);
            }
        }
        return best;
    }

    private static double beatCount(double overlapSecs, AutoCue.Envelope env) {
        if (env == null || env.tempo == null || env.tempo.periodFrames <= 0 || env.beatRate <= 0) {
            return 0.0;
        }
        double beatSecs = env.tempo.periodFrames / (double) env.beatRate;
        if (beatSecs <= 0) {
            return 0.0;
        }
        double beats = overlapSecs / beatSecs;
        double wholeBarBeats = Math.floor(beats / 4.0) * 4.0;
        return Math.max(0.0, wholeBarBeats);
    }

    private static double estimateVocalOverlapSecs(float[] mono, int rate) {
        if (mono == null || mono.length == 0 || rate <= 0) {
            return 0.0;
        }
        float peak = 0f;
        for (float v : mono) {
            peak = Math.max(peak, Math.abs(v));
        }
        if (peak <= 0f) {
            return 0.0;
        }
        float floor = peak * 0.1f;
        int loud = 0;
        for (float v : mono) {
            if (Math.abs(v) >= floor) {
                loud++;
            }
        }
        return loud / (double) rate;
    }
}
