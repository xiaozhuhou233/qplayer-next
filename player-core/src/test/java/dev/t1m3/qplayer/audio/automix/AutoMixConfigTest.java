package dev.t1m3.qplayer.audio.automix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.mix.*;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoMixConfigTest {
    private AutoMixConfig config(boolean pitch) {
        return new AutoMixConfig(true, 2, 0, 6, false, 50, 100, true, "", true, pitch);
    }
    @Test public void pitchSwitchInvalidatesRenderSnapshotAndDefaultsToEnabled() {
        AutoMixConfig legacy = new AutoMixConfig(true, 2, 0, 6, false, 50, 100, true, "");
        assertEquals(config(true), legacy);
        assertNotEquals(config(false), legacy);
        assertNotEquals(config(false).hashCode(), legacy.hashCode());
    }
    @Test public void disablingPitchKeepsCueAndStyleButRemovesBothDeckTranspositions() throws Exception {
        double[] duckRanges = {1, 2};
        float[] duckCurve = {.2f, .8f};
        Transition base = new Transition() {
            public long windowFrames(int rate) { return rate * 6L; }
            public int render(AudioBuf out, AudioBuf from, AudioBuf to, float p) { return 23; }
            public String name() { return "test mix"; }
            public boolean rewritesSamples() { return true; }
            public PitchPlan pitchAt(float p) { return new PitchPlan(-3 * p, 2 * p); }
            public TransitionPlan prepare(String a, String b, int rate) {
                return new TransitionPlan(12, new PitchPlan(-3, 2), .25f, "test", 4, duckRanges, duckCurve);
            }
            public TransitionPlan prepare(String a, String b, int rate,
                    java.util.Map<String,String> ah, java.util.Map<String,String> bh) {
                return prepare(a, b, rate);
            }
        };
        Method control = AutoMixConfig.class.getDeclaredMethod("controlPitch", Transition.class);
        control.setAccessible(true);
        assertSame(base, control.invoke(config(true), base));
        Transition disabled = (Transition) control.invoke(config(false), base);
        TransitionPlan plan = disabled.prepare("A", "B", 44100);
        assertEquals(12, plan.skipSecs, 0);
        assertEquals(4, plan.outroTailSecs, 0);
        assertEquals(.25f, plan.introStartAt, 0);
        assertSame(duckRanges, plan.vocalDuckRanges);
        assertSame(duckCurve, plan.vocalDuckCurve);
        assertTrue(plan.pitch.isZero());
        for (float progress : new float[] {0, .2f, .5f, .9f, 1}) {
            assertTrue(disabled.pitchAt(progress).isZero());
            assertTrue(disabled.pitchAt(progress, 10).isZero());
        }
        assertEquals(base.windowFrames(44100), disabled.windowFrames(44100));
        assertEquals(23, disabled.render(null, null, null, .5f));
    }
}
