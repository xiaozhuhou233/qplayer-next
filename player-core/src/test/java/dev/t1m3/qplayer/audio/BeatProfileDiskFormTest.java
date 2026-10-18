package dev.t1m3.qplayer.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The disk form of a measured track: the grid, the two confidence readings and the
 * key travel together in one cache entry (they come out of one decode), so the
 * layout has to survive a round trip — and a file from a layout whose single
 * confidence number meant something else has to be refused rather than misread.
 */
public class BeatProfileDiskFormTest {

    @Test
    public void gridAndKeySurviveARoundTrip() {
        KeyProfile key = new KeyProfile(9, false, 0.62f, chroma());
        BeatProfile original = new BeatProfile(128.05d, 137L, 0.73f, 0.41f, key);
        BeatProfile read = BeatProfile.fromBytes(original.toBytes());
        assertNotNull(read);
        assertEquals(original.bpm(), read.bpm(), 1e-9d);
        assertEquals(original.firstBeatMs(), read.firstBeatMs());
        assertEquals(original.confidence(), read.confidence(), 1e-6f);
        assertEquals("the older reading travels with the grid too", original.prominence(),
                read.prominence(), 1e-6f);
        assertNotNull(read.key());
        assertEquals(9, read.key().tonic());
        assertTrue(!read.key().major());
        assertEquals(0.62f, read.key().strength(), 0.01f);
        // The profile is what the shift is chosen from, so it has to come back
        // unchanged to within a byte's worth of 255.
        double[] before = key.chroma();
        double[] after = read.key().chroma();
        double worst = 0d;
        for (int i = 0; i < 12; i++) worst = Math.max(worst, Math.abs(before[i] - after[i]));
        System.out.println("chroma round-trip worst error: " + worst);
        assertTrue("profile drifted by " + worst, worst < 0.01d);
        // ... and the shift the profile would choose is the same one. The consumer of
        // it is MixNaturaliser, whose tempo/key judgement is a naturaliser rather than
        // a gate now — so what is worth asserting is that the profile survives the byte
        // quantisation well enough to give the same answer about the same two keys.
        assertEquals(bestShift(key.chroma(), key.chroma()),
                bestShift(read.key().chroma(), read.key().chroma()));
        assertEquals(29, original.toBytes().length);
    }

    @Test
    public void aGridWithNoKeyIsStillReadBackAndSaysWhenTheOlderReadingIsMissing() {
        BeatProfile original = new BeatProfile(96.5d, 20L, 0.81f);
        assertEquals(14, original.toBytes().length);
        assertTrue(!original.hasProminence());
        assertNull("no reading, so the log says so rather than printing 0",
                original.prominenceText());
        BeatProfile read = BeatProfile.fromBytes(original.toBytes());
        assertNotNull(read);
        assertNull("no key measured, so the bytes carry none", read.key());
        assertEquals(96.5d, read.bpm(), 1e-9d);
        assertTrue("and the absence survives the round trip", !read.hasProminence());
    }

    @Test
    public void theLayoutsWhoseNumberMeantSomethingElseAreRefused() {
        // Exactly what version 1 wrote: version, reserved, confidence, BPM, first
        // beat. That single number was the peak's prominence, so reading it into the
        // field the gate now compares would trust or refuse a grid for the wrong
        // reason — the one failure a gate may not have. The same goes for version 2
        // (the same number plus a key). Both are refused, and the track is measured
        // again: a decode off the playback path, against a boundary aligned wrongly.
        java.nio.ByteBuffer v1 = java.nio.ByteBuffer.allocate(12);
        v1.put((byte) 1);
        v1.put((byte) 0);
        v1.putShort((short) 810);
        v1.putInt(9650);
        v1.putInt(20);
        assertNull(BeatProfile.fromBytes(v1.array()));

        java.nio.ByteBuffer v2 = java.nio.ByteBuffer.allocate(27);
        v2.put((byte) 2);
        v2.put((byte) 0);
        v2.putShort((short) 810);
        v2.putInt(9650);
        v2.putInt(20);
        v2.put(new KeyProfile(0, true, 0.9f, chroma()).toBytes());
        assertNull(BeatProfile.fromBytes(v2.array()));

        // Version 3 has the same layout as the current one, so its numbers still mean
        // what they say — and that is exactly why it has to be refused too: the tempo
        // in it was chosen by the estimator without the harmonic-relation check, so on
        // a track whose accent pattern repeats every one and a half beats it holds a
        // 2:3 coarsening of the real grid — a beat between the music's beats, which no
        // alignment may be built on. Measured on one library: 72.7 written for a 109 BPM
        // track and 83.8 for a 126 BPM one, and both were read back and aligned to. A
        // cache file is one decode, off the playback path.
        java.nio.ByteBuffer v3 = java.nio.ByteBuffer.allocate(29);
        v3.put((byte) 3);
        v3.put((byte) 0);
        v3.putShort((short) 250);
        v3.putInt(7270);
        v3.putInt(7);
        v3.putShort((short) 380);
        v3.put(new KeyProfile(0, true, 0.9f, chroma()).toBytes());
        assertNull(BeatProfile.fromBytes(v3.array()));

        // Version 5 is the same layout as the current one too, written by the estimator that
        // is still in use — and it is refused for a reason that is not arithmetic at all: the
        // profiler used to analyse and cache a pass its own deadline had truncated, and a
        // starvation artefact cannot be told from a measurement once it is a file. The bytes
        // below are exactly that case, the one found on the device: 0.19 written for a track
        // whose head window reads nothing at all and whose 15 s window reads 0.71, i.e. a grid
        // under MIN_CONFIDENCE that the cache made permanent for the pair it belonged to. A
        // re-measure is one decode off the playback path; believing a starved grid is a
        // transition built on a beat the music does not have.
        java.nio.ByteBuffer v5 = java.nio.ByteBuffer.allocate(29);
        v5.put((byte) 5);
        v5.put((byte) 0);
        v5.putShort((short) 190);
        v5.putInt(9650);
        v5.putInt(20);
        v5.putShort((short) 380);
        v5.put(new KeyProfile(0, true, 0.9f, chroma()).toBytes());
        assertNull(BeatProfile.fromBytes(v5.array()));
    }

    @Test
    public void nonsenseBytesAreRefusedRatherThanMisread() {
        assertNull(BeatProfile.fromBytes(null));
        assertNull(BeatProfile.fromBytes(new byte[3]));
        assertNull(BeatProfile.fromBytes(new byte[29]));               // version 0
        byte[] wrongVersion = new BeatProfile(120d, 5L, 0.9f).toBytes();
        wrongVersion[0] = 7;
        assertNull(BeatProfile.fromBytes(wrongVersion));
    }

    private static double[] chroma() {
        double[] c = {0.15, 0.03, 0.09, 0.02, 0.12, 0.07, 0.03, 0.14, 0.04, 0.18, 0.03, 0.10};
        return c;
    }

    /** The semitone shift (within the ±2 the old mix allowed) that puts {@code b}
     *  closest to {@code a} — the arithmetic a key-aware consumer would repeat, done
     *  here from raw chroma so it does not depend on any decision class. */
    private static int bestShift(double[] a, double[] b) {
        int best = 0;
        double bestDistance = KeyProfile.distance(a, b);
        for (int n = 1; n <= 2; n++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                double distance = KeyProfile.distance(a, KeyProfile.rotated(b, n * sign));
                if (distance < bestDistance - 1e-9d) {
                    bestDistance = distance;
                    best = n * sign;
                }
            }
        }
        return best;
    }
}
