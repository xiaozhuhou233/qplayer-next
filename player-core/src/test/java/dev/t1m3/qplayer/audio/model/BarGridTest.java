package dev.t1m3.qplayer.audio.model;

import static org.junit.Assert.*;
import org.junit.Test;

/** 节拍/小节网格的判据（2026-10-11 ✓ automix L1.1 的地基 ✓）。 */
public class BarGridTest {

    private static float[] kicks(double bpm, double seconds, int rate) {
        int n = (int) (seconds * rate);
        float[] pcm = new float[n * 2];
        double beat = 60.0 / bpm;
        for (int i = 0; i < n; i++) {
            double t = i / (double) rate;
            double since = t - Math.floor(t / beat) * beat;
            double v = Math.exp(-since * 30) * Math.sin(2 * Math.PI * 60 * since) * .8;
            pcm[i * 2] = (float) v;
            pcm[i * 2 + 1] = (float) v;
        }
        return pcm;
    }

    @Test public void measuresTheTempoOfASteadyGroove() {
        BarGrid.Grid g = BarGrid.analyse(kicks(120, 12, 44100), 2, 44100);
        assertTrue("应当能量出拍速（实际 " + g.bpm + "）", g.usable());
        assertEquals("120 BPM 误差应在 3% 内（实际 " + g.bpm + "）", 120, g.bpm, 3.6);
    }

    @Test public void findsTheTempoOfAFasterGrooveToo() {
        BarGrid.Grid g = BarGrid.analyse(kicks(128, 12, 44100), 2, 44100);
        assertTrue(g.usable());
        assertEquals("128 BPM 误差应在 3% 内（实际 " + g.bpm + "）", 128, g.bpm, 3.84);
    }

    @Test public void nextDownbeatNeverGoesBackwards() {
        BarGrid.Grid g = BarGrid.analyse(kicks(120, 12, 44100), 2, 44100);
        assertTrue(g.usable());
        double t = 3.0;
        double d = g.nextDownbeat(t);
        assertTrue("小节线不能早于询问点（" + d + " vs " + t + "）", d >= t - 1e-6);
        assertTrue("小节线必须在后 4 拍内", d <= t + 2.0 + 1e-6);
    }

    @Test public void silenceYieldsNoGrid() {
        assertFalse(BarGrid.analyse(new float[44100 * 2], 2, 44100).usable());
    }
}
