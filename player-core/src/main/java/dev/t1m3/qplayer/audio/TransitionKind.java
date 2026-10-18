package dev.t1m3.qplayer.audio;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The catalog of ways one track can give way to the next. Every value names a
 * different thing the player can actually do at a track boundary, and the last
 * section of each value's doc says what it needs before it can be carried out —
 * the controller checks that before arming anything, and abandons the transition
 * (to {@link #CUT}) whenever it does not hold. A transition that cannot be
 * performed must never cost playback.
 *
 * <p>The labels are the Chinese strings the settings UI shows; they live here
 * rather than in the settings catalog so a new kind cannot be added without also
 * naming it for the user.
 *
 * <p>Nothing in this file analyses audio: the kinds only describe shapes of
 * overlap. Which one a boundary gets is {@link TransitionChooser}'s decision.
 */
public enum TransitionKind {

    /**
     * The historical behaviour: the outgoing track plays to its end and the next
     * one starts from nothing. No overlap, no second player, no gain writing —
     * this is the universal safe fallback every other kind degrades to, and the
     * only kind that cannot fail.
     *
     * <p>Needs no overlap window and no prepared incoming track. With the separate
     * 「淡入淡出」 setting on, the ordinary end-of-track fade still applies: CUT
     * means "no transition of its own", not "override the user's other choice".
     */
    CUT("硬切", 0L),

    /**
     * Both tracks audible at once while the outgoing ramps down and the incoming
     * ramps up — the P1 overlap. How long that overlap lasts is not part of this
     * value: it travels in the {@link TransitionPlan} ({@link TransitionPlan#OVERLAP_SHORT_MS}
     * / {@code MEDIUM} / {@code LONG}), because the same pair is a seam at four
     * seconds and a mix at fifteen. The gain curve is chosen separately too
     * ({@link FadeCurve}): LINEAR is what P1 shipped and dips roughly 3 dB in the
     * middle of a long overlap; EQUAL_POWER holds the summed power flat.
     *
     * <p>Needs a {@link TransitionPlan#OVERLAP_LONG_MS long} overlap window at
     * least: the incoming source must resolve, prepare and be placed before the
     * overlap starts, both sides must be ordinary streams, and this is the kind the
     * ordinary default overlap belongs to — fifteen seconds, or the whole measured
     * plain ending when that is longer. A chooser that wants less than that is
     * asking for a shorter mix, which is why it has to name it.
     */
    CROSSFADE("交叉淡化", TransitionPlan.OVERLAP_LONG_MS),

    /**
     * The same overlap, short: about a second of it. For a track whose own tail
     * is a real part of the arrangement (or a short track, where 5 s would eat a
     * noticeable fraction of the song) a long ramp is heard as the song being
     * taken away, while a short one is heard as a seam. Unlike {@link #CROSSFADE}
     * this is a fixed length by definition — it is the answer for "fade, but keep
     * it brief", so its plan default is the second it names.
     *
     * <p>Needs a 1 s overlap window; both sides must be ordinary streams.
     */
    QUICK_FADE("快速淡化", 1_000L),

    /**
     * Sequential, with no overlap at all: the outgoing track ramps down to
     * silence and only then does the next one start, ramping up from silence.
     * Two songs are never mixed, so nothing can clash rhythmically or tonally —
     * the safe middle ground when the metadata says nothing about the pair.
     *
     * <p>Needs no overlap window, no second player and no incoming url: it writes
     * the ordinary gain ramp, so it works for any source the ordinary path can
     * play at all.
     */
    FADE_OUT_IN("淡出淡入", 0L),

    /**
     * Trim the silence: measure how much silence the outgoing track trails and
     * how much the incoming one leads with, then put the seam where the two
     * tracks' <em>content</em> meets, instead of at the outgoing file's end. The
     * trailing silence of the outgoing track is never played and the incoming's
     * leading silence is skipped, so the two songs butt together with no silent
     * gap and (unlike {@link #CROSSFADE}) never overlap in content.
     *
     * <p>Needs both measurements ({@link SilenceProfiler}) plus a short ramp — a
     * couple of hundred milliseconds, not a musical overlap — and a parked,
     * already-prepared incoming player. Without a profiler, or when a measurement
     * does not arrive in time, it is not performed at all and the boundary falls
     * back to {@link #CUT}: the point of this kind is <em>not</em> to mix the two
     * tracks, so "mix them after all" would contradict the reason it was chosen.
     */
    SILENCE_TRIM("静音裁切", 250L);

    /** The order the settings row cycles through: index 0 there is 自动 (ask the
     *  chooser), so index n maps to {@code CHOICES.get(n - 1)}. */
    public static final List<TransitionKind> CHOICES = Collections.unmodifiableList(
            Arrays.asList(CUT, CROSSFADE, QUICK_FADE, FADE_OUT_IN, SILENCE_TRIM));

    /** How much of the outgoing track this kind needs to itself, in ms; 0 for the
     *  kinds made of ordinary playback. */
    private final long overlapMs;
    private final String label;

    TransitionKind(String label, long overlapMs) {
        this.label = label;
        this.overlapMs = overlapMs;
    }

    /** Chinese name for the settings UI. */
    public String label() {
        return label;
    }

    /** The overlap window this kind needs, ms (0 when it needs none). */
    public long overlapMs() {
        return overlapMs;
    }

    /** True when the two tracks are audible at the same time, which is the group
     *  whose members ramp against each other and can therefore dip in the middle
     *  (see {@link FadeCurve}). */
    public boolean overlapping() {
        return this == CROSSFADE || this == QUICK_FADE;
    }

    /** True when carrying this kind out needs a second prepared player at all.
     *  {@link #SILENCE_TRIM} does, but not to overlap anything: it holds the next
     *  track parked at its own start until the seam. */
    public boolean needsSecondPlayer() {
        return overlapping() || this == SILENCE_TRIM;
    }
}
