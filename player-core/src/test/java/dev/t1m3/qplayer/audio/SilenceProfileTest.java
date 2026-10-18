package dev.t1m3.qplayer.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The plain-ending arithmetic and the disk form it travels in.
 *
 * <p>This is the only evidence the app has for starting a blend EARLIER than its
 * nominal overlap, so what it must do is stated here in numbers rather than
 * argued about: an ending that still attacks, still has a voice in it, or is still
 * at the track's own body level measures as nothing, and only an ending that is
 * genuinely doing nothing is reported as plain.
 *
 * <p>Synthetic envelopes, so every answer is known in advance — the same reason
 * {@code BeatAnalysisAgreementTest} is written this way.
 */
public class SilenceProfileTest {

    private static final long BLOCK_MS = 20L;

    /** {@code seconds} of level {@code value} per block. */
    private static double[] steady(int blocks, double value) {
        double[] out = new double[blocks];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static double[] noPresence(int blocks) {
        return steady(blocks, 0.05d);
    }

    private static int blocks(long ms) {
        return (int) (ms / BLOCK_MS);
    }

    /** A twenty-second window whose last stretch is a fade to silence: the whole
     *  fade is plain, measured back from the end. This is the shape most of this
     *  app's library actually ends with (measured: Runaway fades from its body level
     *  to silence, with no attack at all in the last twenty seconds). */
    @Test
    public void aFadingEndingIsPlain() {
        double[] levels = steady(blocks(20_000), 0.30d);
        int from = blocks(6_000);
        for (int i = from; i < levels.length; i++) {
            double t = (double) (i - from) / (levels.length - from);
            levels[i] = 0.30d * (1d - t) + 0.001d * t;
        }
        long plain = SilenceProfile.plainTailMsOf(levels, noPresence(levels.length), BLOCK_MS);
        assertTrue("a fade to silence must be plain end-to-end, got " + plain, plain >= 19_000L);
    }

    /** A steady, quiet ending measures as plain even when the whole window is that
     *  quiet: the absolute level line is what carries this case. */
    @Test
    public void aSteadyQuietEndingIsPlain() {
        double[] levels = steady(blocks(20_000), 0.02d);
        assertEquals(20_000L,
                SilenceProfile.plainTailMsOf(levels, noPresence(levels.length), BLOCK_MS));
    }

    /** Silence at the end of a track whose last twenty seconds still had hits in
     *  them: only the silence is plain — the run has to stop where the music was
     *  still being played. */
    @Test
    public void trailingSilenceIsPlain() {
        double[] levels = steady(blocks(20_000), 0.12d);
        for (int i = 0; i < blocks(16_000); i += blocks(200)) levels[i] = 0.30d;  // hits
        for (int i = blocks(16_000); i < levels.length; i++) levels[i] = 0d;
        long plain = SilenceProfile.plainTailMsOf(levels, noPresence(levels.length), BLOCK_MS);
        // The four seconds of silence, plus the one 200 ms gap the last hit left
        // before them (the run stops at the rise into a hit, not at the hit itself).
        assertTrue("got " + plain, plain >= 4_000L && plain <= 4_400L);
    }

    /** A window where nothing at all happens — a pad, no hits, no voice — is plain
     *  from end to end, loud or not. Deliberate: the loudness gate only refuses an
     *  ending that is the loudest part of its own window (see above), because on this
     *  app's library every ending that is quiet in absolute terms is short, and what
     *  actually separates "an ending" from "still playing" is whether anything is
     *  happening in it. */
    @Test
    public void aWindowWithNothingHappeningIsPlainThroughout() {
        double[] levels = steady(blocks(20_000), 0.30d);
        assertEquals(20_000L,
                SilenceProfile.plainTailMsOf(levels, noPresence(levels.length), BLOCK_MS));
    }

    /** A track still building to its end is not a plain ending: what is left of it is
     *  a climax the overlap would put the next track on top of, and the build test is
     *  what refuses it. Nothing about its first half was loud, so only the ending's
     *  own rise can be the reason. */
    @Test
    public void aBuildTowardsTheEndIsNotPlain() {
        double[] levels = steady(blocks(20_000), 0.10d);
        for (int i = blocks(6_000); i < levels.length; i++) {
            double t = (double) (i - blocks(6_000)) / (levels.length - blocks(6_000));
            levels[i] = 0.10d + 0.40d * t;          // a build to the file's end
        }
        long plain = SilenceProfile.plainTailMsOf(levels, noPresence(levels.length), BLOCK_MS);
        assertTrue("a build must not read as a plain ending, got " + plain, plain < 6_000L);
    }

    /** A near-silent block cannot be "dominated by a voice": its band share is the
     *  ratio of two nearly-zero numbers and reads as anything at all. Measured on a
     *  real fade, one such block cut an otherwise voice-free ending to under two
     *  seconds, so the voice test only applies above VOICE_FLOOR. */
    @Test
    public void nearSilentBlocksAreNotTestedForAVoice() {
        double[] levels = steady(blocks(20_000), 0.20d);
        for (int i = blocks(18_000); i < levels.length; i++) levels[i] = 0.0005d;
        double[] noisy = noPresence(levels.length);
        for (int i = blocks(18_000); i < noisy.length; i++) noisy[i] = 0.95d;  // meaningless
        long plain = SilenceProfile.plainTailMsOf(levels, noisy, BLOCK_MS);
        assertTrue("a silent tail is not a voice, got " + plain, plain >= 19_000L);
    }

    /** A kick or a snare in the ending stops the run there, and the last twenty
     *  seconds of a four-on-the-floor outro are not a plain ending. */
    @Test
    public void anEndingThatStillAttacksIsNotPlain() {
        double[] levels = steady(blocks(20_000), 0.02d);
        // A hit every second, over the whole window.
        for (int i = blocks(1_000); i < levels.length; i += blocks(1_000)) {
            levels[i] = 0.20d;
        }
        assertEquals(0L,
                SilenceProfile.plainTailMsOf(levels, noPresence(levels.length), BLOCK_MS));
        // The same ending with the hits stopping 8 s before the end: plain for the
        // 8 s that are left, and not for the part that was still being drummed on.
        double[] without = levels.clone();
        for (int i = blocks(12_000); i < without.length; i++) without[i] = 0.02d;
        long plain = SilenceProfile.plainTailMsOf(without, noPresence(without.length), BLOCK_MS);
        assertTrue("the drum-free tail must be plain, got " + plain, plain >= 8_000L);
        assertTrue("the drummed-on part must not be, got " + plain, plain < 12_000L);
    }

    /** A voice or a lead instrument dominating the ending is what the vocal-band
     *  share is for: same level, same absence of attacks, but not plain. The line is
     *  loose (PLAIN_PRESENT_MAX), so it takes an unmistakable case — which is the
     *  measured top of this app's library (an EDM lead held to the last bar at 0.85). */
    @Test
    public void anEndingDominatedByAVoiceIsNotPlain() {
        double[] levels = steady(blocks(20_000), 0.02d);
        double[] loudVoice = steady(levels.length, 0.85d);
        assertEquals(0L,
                SilenceProfile.plainTailMsOf(levels, loudVoice, BLOCK_MS));
        // …and a voice that joins only for the last 5 s is enough to refuse the
        // whole ending, because the run has to reach the file's own end: the blend
        // ends there, so a voice in the last five seconds IS in the blend whatever
        // the twenty seconds before it looked like.
        double[] joining = noPresence(levels.length);
        for (int i = blocks(15_000); i < joining.length; i++) joining[i] = 0.85d;
        assertEquals(0L, SilenceProfile.plainTailMsOf(levels, joining, BLOCK_MS));
    }

    /** A breath is not an ending: anything under MIN_PLAIN_TAIL_MS reports as zero,
     *  so nothing downstream has to remember the floor. */
    @Test
    public void aVeryShortQuietRunIsReportedAsNothing() {
        double[] levels = steady(blocks(4_000), 0.12d);
        for (int i = 0; i < blocks(2_800); i += blocks(200)) levels[i] = 0.30d;  // hits
        for (int i = levels.length - blocks(1_200); i < levels.length; i++) levels[i] = 0.01d;
        assertEquals(0L,
                SilenceProfile.plainTailMsOf(levels, noPresence(levels.length), BLOCK_MS));
    }

    /** Attacks are counted for the log, out of silence only. */
    @Test
    public void attacksAreCounted() {
        double[] levels = steady(200, 0.02d);
        levels[50] = 0.20d;
        levels[100] = 0.21d;
        levels[150] = 0.02d;          // no rise at all
        assertEquals(2, SilenceProfile.attacksOf(levels));
        assertEquals(0, SilenceProfile.attacksOf(steady(200, 0d)));
    }

    /** The disk form keeps all four numbers, and a file from the older layout is
     *  refused rather than read: a version-1 file has no plain ending in it, and
     *  reading its offsets as one would be trusting a number that means something
     *  else. */
    @Test
    public void theDiskFormRoundTripsAndRefusesTheOlderLayout() {
        SilenceProfile written = new SilenceProfile(1_480L, 2_600L, 17_400L, 61);
        SilenceProfile read = SilenceProfile.fromBytes(written.toBytes());
        assertNotNull(read);
        assertEquals(1_480L, read.headMs());
        assertEquals(2_600L, read.tailMs());
        assertEquals(17_400L, read.plainTailMs());
        assertEquals(61, read.tailAttacks());
        assertNull("eight bytes is the version-1 layout", SilenceProfile.fromBytes(new byte[8]));
        byte[] older = written.toBytes();
        older[0] = 2;   // a version-2 file: same numbers, earlier thresholds
        assertNull("a version-2 profile may not be read back", SilenceProfile.fromBytes(older));
        assertNull(SilenceProfile.fromBytes(null));
        assertNull(SilenceProfile.fromBytes(new byte[17]));
    }

    /** A profile with no measurement is the answer that changes nothing. */
    @Test
    public void aProfileWithoutAPlainEndingSaysSo() {
        SilenceProfile bare = new SilenceProfile(0L, 3_000L);
        assertEquals(0L, bare.plainTailMs());
        assertEquals(0, bare.tailAttacks());
    }
}
