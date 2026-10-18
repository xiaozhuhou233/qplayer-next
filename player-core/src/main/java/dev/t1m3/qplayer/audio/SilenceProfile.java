package dev.t1m3.qplayer.audio;

import java.nio.ByteBuffer;

/**
 * What a track's two ends look like, in milliseconds from the file boundaries:
 * {@code headMs} of leading silence, {@code tailMs} of trailing silence, and —
 * over a longer window at the end — how much of that ending is <em>plain</em>
 * ({@link #plainTailMs()}), i.e. how far back from the end the audio stops doing
 * anything.
 *
 * <p>The first two are what {@link TransitionKind#SILENCE_TRIM} is placed with.
 * The third is what lets a blend start EARLIER than its nominal overlap: the
 * length the outgoing track's own ending can be given to the mix before there is
 * anything left to clash with. It is read off the same numbers the profiler
 * already computes for the trim — the block energy envelope of the tail window
 * plus a cheap high-frequency share — so it costs the app nothing it was not
 * already paying for (see {@link #plainTailMsOf}).
 *
 * <p>Measured, never guessed: a profile that was never taken has no plain ending
 * (this class is only ever built by a {@link SilenceProfiler}), and a tail that
 * has anything happening in it measures as zero.
 *
 * <p>Cached per track (see {@code DiskCache}'s silence sub-cache) because the
 * measurement costs a decode while the answer never changes for an unchanged
 * file.
 */
public final class SilenceProfile {

    // --- what counts as "plain", as arithmetic (so it can be tested) --------
    // Every constant here is a reading of the same question: "is the last stretch
    // of this track still doing something?" Nothing below looks at tempo or key —
    // a plain ending is plain precisely because it has neither.

    /** A block counts as an attack when its level rises by this factor over the
     *  block before it. 2.0 is one kick or one syllable; a fade never does it. */
    public static final double ATTACK_RATIO = 2.0d;

    /** Below this level (linear RMS, about -46 dBFS — the profiler's own silence
     *  floor) a rise is not an attack, it is a block of silence breathing. */
    public static final double ATTACK_FLOOR = 0.005d;

    /** How loud the plain ending's blocks may be, relative to the loudest block the
     *  window's own <em>earlier half</em> contains: at most that, i.e. the ending may
     *  not be louder than the loudest thing before it. That is the whole loudness
     *  test, and it exists for exactly one ending: a build. A fade passes it, a
     *  steady ending passes it (equality), and a track still rising towards its last
     *  sample does not — which is the one shape where starting a blend early is
     *  plainly wrong, because there is a climax still to come that the overlap would
     *  cover with the next track.
     *
     *  <p>⚠️ Deliberately weak, and the reason is measured, not assumed. "The ending
     *  is 6 dB under the window's own loudest tenth" was tried first and held for at
     *  most eleven seconds on any of eighteen real endings from this app's library,
     *  and for under three on most — because the tenth that a fade's early part
     *  occupies is inside the ending itself. A within-window level test cannot tell a
     *  sustained outro from a sustained song; what can tell them apart is whether
     *  anything is HAPPENING in the ending (see {@link #ATTACK_RATIO} and the vocal
     *  share below), which is what the other two tests are. */
    public static final double PLAIN_LEVEL_SHARE = 1.0d;

    /** How much of a block's energy may sit in the vocal band (roughly 250 Hz -
     *  3.5 kHz, see the profiler) before the block counts as "someone is still
     *  singing". ⚠️ <b>The loosest-calibrated number in this class</b>, and knowingly
     *  so: it is a coarse band share, not a voice detector, and it is set high
     *  (three quarters of a block's energy) so that it only refuses an ending where
     *  a voice or a lead instrument unmistakably dominates it. Measured medians over
     *  the last two seconds of eighteen real endings span 0.21 to 0.85, with nothing
     *  in that range to mark where "a voice" begins — the endings this refuses are the
     *  top of that range (an EDM track with its lead synth and vocal held to the last
     *  bar), and the rest are admitted with the share printed in the log, so the
     *  reader can see what was admitted rather than having to believe a threshold. */
    public static final double PLAIN_PRESENT_MAX = 0.75d;

    /** Shorter than this, the run is not "a plain ending", it is a breath between
     *  phrases — reported as zero, because nothing decides anything with less. */
    public static final long MIN_PLAIN_TAIL_MS = 2_000L;

    /** Below this level (about -40 dBFS) a block is too quiet for its band share to
     *  mean anything: the ratio of two nearly-zero numbers is noise, and it reads as
     *  anything at all — measured on a real fade, one near-silent block in an
     *  otherwise voice-free ending reported 0.9 and cut the run from twenty seconds
     *  to under two. A block that quiet has no voice in it whatever the division
     *  says, so the voice test only applies to blocks at or above this floor. */
    public static final double VOICE_FLOOR = 0.01d;

    private final long headMs;
    private final long tailMs;
    private final long plainTailMs;
    private final int tailAttacks;

    public SilenceProfile(long headMs, long tailMs) {
        this(headMs, tailMs, 0L, 0);
    }

    public SilenceProfile(long headMs, long tailMs, long plainTailMs, int tailAttacks) {
        this.headMs = Math.max(0L, headMs);
        this.tailMs = Math.max(0L, tailMs);
        this.plainTailMs = Math.max(0L, plainTailMs);
        this.tailAttacks = Math.max(0, tailAttacks);
    }

    /** Silence before the first audible sample. */
    public long headMs() {
        return headMs;
    }

    /** Silence after the last audible sample. */
    public long tailMs() {
        return tailMs;
    }

    /** How much of the track's ending is plain — no attack in it, no voice
     *  dominating it, and not the loudest part of the window it was measured over
     *  — counting back from the end of the file (so the trailing silence above is
     *  part of it). 0 when nothing about the ending is plain. */
    public long plainTailMs() {
        return plainTailMs;
    }

    /** How many attacks the measured tail window contained. Evidence for the
     *  number above: a window with none is a window where nothing happened. */
    public int tailAttacks() {
        return tailAttacks;
    }


    /** Compact form for the disk cache: a version byte and four big-endian ints.
     *
     *  <p>Version 3. Version 1 was eight bytes of head/tail; version 2 added the
     *  plain ending; version 3 changed how a plain ending is <em>decided</em>
     *  (see the constants above, which were retuned against eighteen measured
     *  endings), so a version-2 file holds the same four numbers with different
     *  meanings — the layout did not move, the arithmetic did. Both are refused,
     *  for the same reason the beat cache bumps its version: a measurement whose
     *  thresholds have changed may not be read back as if it had been taken with
     *  the current ones. */
    private static final byte VERSION = 3;

    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(17);
        buf.put(VERSION);
        buf.putInt((int) headMs);
        buf.putInt((int) tailMs);
        buf.putInt((int) Math.min(Integer.MAX_VALUE, plainTailMs));
        buf.putInt(tailAttacks);
        return buf.array();
    }

    /** Inverse of {@link #toBytes()}; null when the bytes are not one (a cache
     *  file from any other layout, a truncated write) — the caller then measures
     *  again rather than working from a number it cannot vouch for. */
    public static SilenceProfile fromBytes(byte[] data) {
        if (data == null || data.length != 17) return null;
        ByteBuffer buf = ByteBuffer.wrap(data);
        if (buf.get() != VERSION) return null;
        return new SilenceProfile(buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt());
    }

    // --- the plain ending, as pure arithmetic -------------------------------

    /**
     * How much of the end of {@code levels} is plain, ms.
     *
     * @param levels   linear RMS per block, ending at the END of the audio (the
     *                 tail window's envelope; the last entry is the last block
     *                 before the file's end)
     * @param presence each block's share of energy in the vocal band, same length
     *                 and alignment as {@code levels} (0..1; a host that cannot
     *                 measure it passes null, and then that test is not applied)
     * @param blockMs  how much audio one block covers
     * @return the length of the trailing run of blocks that is plain, capped at
     *         the window, or 0 when the run is shorter than
     *         {@link #MIN_PLAIN_TAIL_MS}
     *
     * <p>Walking backwards from the end, a block joins the run while all three
     * hold:
     * <ul>
     *   <li><b>it is not an attack</b> — the block after it is not
     *       {@link #ATTACK_RATIO} louder than it (checked in the direction time
     *       flows, so a fade counts as plain and a drum hit does not);</li>
     *   <li><b>it is not louder than what came before it</b> — at most
     *       {@link #PLAIN_LEVEL_SHARE} of the loudest block in the window's earlier
     *       half, which is the build test;</li>
     *   <li><b>nothing is singing in it</b> — its vocal-band share is under
     *       {@link #PLAIN_PRESENT_MAX} (a loose line: see that constant).</li>
     * </ul>
     * The first block that fails ends the run, so this is deliberately a
     * contiguous claim about the track's own ending and nothing else.
     */
    public static long plainTailMsOf(double[] levels, double[] presence, long blockMs) {
        if (levels == null || levels.length == 0 || blockMs <= 0L) return 0L;
        // The loudest block of the window's earlier half: what the ending is allowed
        // to be as loud as (see PLAIN_LEVEL_SHARE).
        double earlyMax = 0d;
        for (int i = 0; i < levels.length / 2; i++) earlyMax = Math.max(earlyMax, levels[i]);
        double cap = earlyMax * PLAIN_LEVEL_SHARE;
        int blocks = 0;
        for (int i = levels.length - 1; i >= 0; i--) {
            if (levels[i] > cap) break;
            if (i + 1 < levels.length
                    && levels[i + 1] > levels[i] * ATTACK_RATIO
                    && levels[i + 1] > ATTACK_FLOOR) {
                break;
            }
            // The voice test, on audible blocks only (see VOICE_FLOOR).
            if (presence != null && i < presence.length && levels[i] >= VOICE_FLOOR
                    && presence[i] > PLAIN_PRESENT_MAX) {
                break;
            }
            blocks++;
        }
        long ms = blocks * blockMs;
        return ms >= MIN_PLAIN_TAIL_MS ? ms : 0L;
    }

    /** How many attacks the window contains: blocks that rise by
     *  {@link #ATTACK_RATIO} over the block before them, out of silence. Evidence
     *  only — nothing decides on this number. */
    public static int attacksOf(double[] levels) {
        if (levels == null) return 0;
        int n = 0;
        for (int i = 1; i < levels.length; i++) {
            if (levels[i] > levels[i - 1] * ATTACK_RATIO && levels[i] > ATTACK_FLOOR) n++;
        }
        return n;
    }

    @Override
    public String toString() {
        return "SilenceProfile{head=" + headMs + "ms, tail=" + tailMs + "ms, plainEnding="
                + plainTailMs + "ms, attacks=" + tailAttacks + "}";
    }
}
