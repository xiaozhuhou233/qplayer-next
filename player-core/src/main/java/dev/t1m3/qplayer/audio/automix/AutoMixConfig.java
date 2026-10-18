package dev.t1m3.qplayer.audio.automix;

import dev.t1m3.qplayer.audio.mix.*;

/** Immutable snapshot: a render never observes half of a settings change. */
public final class AutoMixConfig {
    public final boolean enabled;
    public final int mode, style, seconds, width, scale;
    public final boolean forced, stretch, autoCue, pitchEnabled;
    public final String extra;
    public final MixTuning tuning;

    public AutoMixConfig(boolean enabled, int mode, int style, int seconds,
                         boolean forced, int width, int scale, boolean stretch, String extra) {
        this(enabled, mode, style, seconds, forced, width, scale, stretch, extra, true);
    }

    public AutoMixConfig(boolean enabled, int mode, int style, int seconds,
                         boolean forced, int width, int scale, boolean stretch, String extra, boolean autoCue) {
        this(enabled, mode, style, seconds, forced, width, scale, stretch, extra, autoCue, true);
    }

    public AutoMixConfig(boolean enabled, int mode, int style, int seconds,
                         boolean forced, int width, int scale, boolean stretch, String extra,
                         boolean autoCue, boolean pitchEnabled) {
        this.enabled = enabled;
        this.mode = Math.max(0, Math.min(3, mode));
        this.style = Math.max(0, Math.min(MixStyle.UI_ORDER.length - 1, style));
        this.seconds = Math.max(1, Math.min(30, seconds));
        this.forced = forced;
        this.width = Math.max(0, Math.min(100, width));
        this.scale = Math.max(50, Math.min(200, scale));
        this.stretch = stretch;
        this.autoCue = autoCue;
        this.pitchEnabled = pitchEnabled;
        this.extra = extra == null ? "" : extra.trim();
        tuning = MixTuning.parse(this.extra + (this.extra.isEmpty() ? "" : ",")
                + "width=" + this.width / 100f + ",window_scale=" + this.scale / 100f
                + ",tempo_stretch=" + (stretch ? 1 : 0));
    }

    public Transition engine(int rate) {
        if (mode == 3) return Gapless.INSTANCE;
        Transition base = mode == 0 ? new Crossfade(seconds)
                : mode == 1 ? new HarmonicBlend(rate, 2, seconds)
                : new AiMix(MixStyle.fromUiIndex(style), rate, 2, tuning, () -> (double) seconds);
        boolean search = !forced && mode != 2;
        return controlPitch(new AutoCueTransition(base, rate, 2,
                search ? () -> (double) seconds : null, search, tuning)
                .playbackWindowPolicy(autoCue, forced ? seconds : 0));
    }

    private Transition controlPitch(Transition base) {
        if (pitchEnabled) return base;
        return new Transition() {
            public long windowFrames(int rate) { return base.windowFrames(rate); }
            public int render(dev.t1m3.qplayer.audio.core.AudioBuf out,
                    dev.t1m3.qplayer.audio.core.AudioBuf from,
                    dev.t1m3.qplayer.audio.core.AudioBuf to, float progress) {
                return base.render(out, from, to, progress);
            }
            public String name() { return base.name(); }
            public boolean rewritesSamples() { return base.rewritesSamples(); }
            public int renderChunkFrames(int requested) { return base.renderChunkFrames(requested); }
            public boolean suppliesOutgoingAudio() { return base.suppliesOutgoingAudio(); }
            public double outgoingStartSecs(double duration, TransitionPlan plan, int rate) {
                return base.outgoingStartSecs(duration, plan, rate);
            }
            public double outroLoopLeadInSecs() { return base.outroLoopLeadInSecs(); }
            public PitchPlan pitchAt(float progress) { return PitchPlan.ZERO; }
            public TransitionPlan prepare(String a, String b, int rate) {
                return prepare(a, b, rate, java.util.Collections.emptyMap(), java.util.Collections.emptyMap());
            }
            public TransitionPlan prepare(String a, String b, int rate,
                    java.util.Map<String,String> ah, java.util.Map<String,String> bh) {
                TransitionPlan plan = base.prepare(a, b, rate, ah, bh);
                return new TransitionPlan(plan.skipSecs, PitchPlan.ZERO, plan.introStartAt,
                        plan.note + " · 原调融合", plan.outroTailSecs, plan.vocalDuckRanges, plan.vocalDuckCurve);
            }
        };
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof AutoMixConfig)) return false;
        AutoMixConfig c = (AutoMixConfig) o;
        return enabled == c.enabled && mode == c.mode && style == c.style && seconds == c.seconds
                && forced == c.forced && width == c.width && scale == c.scale
                && stretch == c.stretch && autoCue == c.autoCue && pitchEnabled == c.pitchEnabled && extra.equals(c.extra);
    }
    @Override public int hashCode() { return extra.hashCode() + mode * 31 + seconds + (pitchEnabled ? 1231 : 1237); }
}
