package dev.t1m3.qplayer.audio.automix;

import dev.t1m3.qplayer.audio.mix.*;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

public class HarmonicPitchGlideTest {
    private HarmonicBlend blend(float shift) throws Exception {
        HarmonicBlend blend = new HarmonicBlend(44100, 2, 6);
        Field field = HarmonicBlend.class.getDeclaredField("shift");
        field.setAccessible(true); field.setFloat(blend, shift);
        return blend;
    }
    @Test public void loweringTakesTwoAndAHalfSecondsAndReturnsSmoothly() throws Exception {
        HarmonicBlend blend = blend(-3);
        assertTrue(blend.pitchAt(0).isZero());
        assertTrue(Math.abs(blend.pitchAt(.2f / 6).outro) < .05f);
        assertEquals(-.75f, blend.pitchAt(1.25f / 6).outro, .001f);
        assertEquals(-1.5f, blend.pitchAt(2.5f / 6).outro, .001f);
        assertEquals(-1.5f, blend.pitchAt(3.5f / 6).outro, .001f);
        assertEquals(-.75f, blend.pitchAt(4.75f / 6).outro, .001f);
        assertTrue(blend.pitchAt(1).isZero());
        float previous = 0;
        for (float time = 0; time <= 6; time += .01f) {
            PitchPlan pitch = blend.pitchAt(time / 6);
            assertEquals(-pitch.outro, pitch.intro, .0001f);
            assertTrue("no sudden pitch step", Math.abs(pitch.outro - previous) < .02f);
            previous = pitch.outro;
        }
    }
    @Test public void largeShiftsAlsoGlideAndReturnBeforeTheUnshiftedContinuation() throws Exception {
        HarmonicBlend blend = blend(-8);
        assertTrue(Math.abs(blend.pitchAt(.2f / 6).outro) < .2f);
        assertEquals(-4, blend.pitchAt(1.25f / 6).outro, .001f);
        assertEquals(-8, blend.pitchAt(2.5f / 6).outro, .001f);
        assertEquals(0, blend.pitchAt(.5f).intro, 0);
        assertTrue(blend.pitchAt(1).isZero());
    }
    @Test public void autoCueUsesTheFinalWindowDurationAndShortWindowsRemainContinuous() throws Exception {
        HarmonicBlend blend = blend(-3);
        AutoCueTransition autoCue = new AutoCueTransition(blend, 44100, 2);
        assertEquals(-.75f, autoCue.pitchAt(1.25f / 10, 10).outro, .001f);
        assertEquals(-1.5f, autoCue.pitchAt(2.5f / 10, 10).outro, .001f);
        assertEquals(-.75f, blend.pitchAt(.25f, 2).outro, .001f);
        assertEquals(-1.5f, blend.pitchAt(.5f, 2).outro, .001f);
        assertTrue(blend.pitchAt(1, 2).isZero());
    }
}
