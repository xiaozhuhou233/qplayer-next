package dev.t1m3.qplayer.audio.automix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.mix.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real EQ output: the first block must retain the original level, not jump to -8dB. */
public class AiMixEnvelopeTest {
    private static final int RATE = 44100, BLOCK = 512;

    private AiMix mix(double seconds, boolean multiband) {
        AiMix mix = new AiMix(MixStyle.PLAIN_BLEND, RATE, 2,
                MixTuning.parse("width=0,level_match=0,low_duck=0"));
        mix.setCarveWindowFrames(Math.round(seconds * RATE));
        if (multiband) mix.setCarvePlan(new double[] {700, 1000, 1400}, new float[] {-4, -8, -4});
        else mix.setCarveBands(1000, 1000, -8);
        return mix;
    }

    private double[] render(AiMix mix, double seconds, boolean incoming) {
        int total = (int) Math.round(seconds * RATE);
        AudioBuf a = new AudioBuf(ChannelLayout.of(2), BLOCK);
        AudioBuf b = new AudioBuf(ChannelLayout.of(2), BLOCK);
        AudioBuf out = new AudioBuf(ChannelLayout.of(2), BLOCK);
        double[] levels = new double[(total + BLOCK - 1) / BLOCK];
        for (int start = 0; start < total; start += BLOCK) {
            int n = Math.min(BLOCK, total - start);
            double input = 0;
            for (int i = 0; i < n; i++) {
                float tone = (float) (.4 * Math.sin(2 * Math.PI * 1000 * (start + i) / RATE));
                input += tone * tone;
                for (int c = 0; c < 2; c++) {
                    a.backingArray()[a.channelBase(c) + i] = incoming ? 0 : tone;
                    b.backingArray()[b.channelBase(c) + i] = incoming ? tone : 0;
                }
            }
            a.setFrames(n); b.setFrames(n);
            float p = (float) ((start + n * .5) / total);
            mix.render(out, a, b, p);
            double power = 0;
            for (int i = 0; i < n; i++) {
                float v = out.backingArray()[i];
                power += v * v;
            }
            double fade = incoming ? Math.sin(p * Math.PI / 2) : Math.cos(p * Math.PI / 2);
            levels[start / BLOCK] = Math.sqrt(power / input) / fade;
        }
        return levels;
    }

    @Test public void outgoingGetsQuieterOverTheSameTwoAndAHalfSecondsAsPitch() {
        double[] levels = render(mix(6, false), 6, false);
        assertEquals(1, levels[0], .002);
        assertTrue(levels[(int) (.5 * RATE / BLOCK)] > .9);
        assertEquals((1 + Math.pow(10, -8d / 20)) / 2,
                levels[(int) (1.25 * RATE / BLOCK)], .015);
        assertEquals(Math.pow(10, -8d / 20), levels[(int) (2.5 * RATE / BLOCK)], .003);
        for (int i = 1; i < 2.5 * RATE / BLOCK; i++)
            assertTrue("no volume step at block " + i,
                    Math.abs(20 * Math.log10(levels[i] / levels[i - 1])) < .15);
    }

    @Test public void incomingReturnsToOriginalLevelBeforeItsContinuousTail() {
        double[] levels = render(mix(6, false), 6, true);
        assertEquals(Math.pow(10, -8d / 20), levels[(int) (3 * RATE / BLOCK)], .003);
        assertEquals((1 + Math.pow(10, -8d / 20)) / 2,
                levels[(int) (4.75 * RATE / BLOCK)], .015);
        assertEquals(1, levels[levels.length - 1], .002);
    }

    @Test public void multibandCarveAndShortFinalWindowsAlsoStartAtOriginalLevel() {
        double[] shortWindow = render(mix(2, true), 2, false);
        assertEquals(1, shortWindow[0], .002);
        assertTrue(shortWindow[(int) (.5 * RATE / BLOCK)] < .8);
        assertTrue(shortWindow[(int) (1 * RATE / BLOCK)] < .4);
        for (int i = 1; i < RATE / BLOCK; i++)
            assertTrue("multiband volume changes gradually",
                    Math.abs(20 * Math.log10(shortWindow[i] / shortWindow[i - 1])) < .5);
    }
}
