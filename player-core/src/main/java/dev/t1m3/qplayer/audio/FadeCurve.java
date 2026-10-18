package dev.t1m3.qplayer.audio;

/**
 * The gain shape an overlapping transition ramps along. Two tracks summed at
 * full gain are not a crossfade — they are just loud; the curve is what turns
 * one level going down and another going up into a seam that does not pump.
 *
 * <p>{@code t} is the progress of the overlap, 0 at its start and 1 at the swap
 * point. The two gains are applied to the outgoing and the incoming player
 * respectively; they are not required to sum to 1 (that is the point).
 */
public enum FadeCurve {

    /**
     * Straight lines: {@code 1-t} and {@code t}. What P1 shipped. Two
     * uncorrelated tracks each at half gain produce about half the power of one
     * at full gain, so the middle of the overlap dips — for equal-power content
     * that is 3 dB, which is the first thing to listen for when a long crossfade
     * "drops out" in the middle.
     */
    LINEAR("线性") {
        @Override public float outGain(float t) { return 1f - t; }
        @Override public float inGain(float t) { return t; }
    },

    /**
     * Quarter-circle pair: {@code cos} down, {@code sin} up. Their squares sum to
     * one, so the total power is constant across the whole overlap — the standard
     * "equal power" crossfade, which sounds level where LINEAR sounds like a dip.
     * The cost is that the two tracks briefly sum above either one alone, which is
     * exactly what a crossfade is supposed to do.
     */
    EQUAL_POWER("等功率") {
        @Override public float outGain(float t) { return (float) Math.cos(t * Math.PI / 2.0); }
        @Override public float inGain(float t) { return (float) Math.sin(t * Math.PI / 2.0); }
    },

    /**
     * The staged, DJ-shaped hand-over: the outgoing track is <em>taken down to a low bed
     * early</em> and then <em>leaves</em> it part way through, and the incoming track
     * arrives as a bed under it and grows to its own level by the end of the window.
     * This is the shape that makes a long overlap read as a mix rather than as a fade —
     * and, since round 17, the one that keeps the outgoing track's (unremovable) vocals
     * from being the loudest thing in the blend.
     *
     * <p><b>Why the symmetric curves read as a fade.</b> With {@code cos}/{@code sin}
     * the two levels are only comparable while {@code t} is near the middle: the
     * gains cross at half a period and the stretch where the listener can hear
     * <em>two</em> tracks is about 41% of the window (measured by
     * {@link #bothAudibleMs(long, float)}), split evenly on both sides of the
     * middle. A fifteen-second ramp therefore spends the first third fading one
     * track up and the last third fading the other one out, which the ear reads as
     * "the song changed" — the complaint this curve answers.
     *
     * <p><b>What round 17 changed, and why.</b> Rounds 12–16 held the outgoing track
     * within 3 dB of its own level until three quarters of the window. That maximised
     * the both-audible share (68%) — and it was the wrong target, because the reason
     * the listener hears "two songs fighting" is not that both are audible, it is that
     * both are <em>loud</em>, and the outgoing track's vocals cannot be taken out of it
     * (see {@link #OUT_BED_DB}). The shape is now written in <b>decibels</b>, in three
     * eased stretches: down into a −10 dB bed over the first three tenths, held there
     * until the middle, and gone (six decibels under {@link #INAUDIBLE_DB}) by 82%.
     *
     * <p>Measured over a 15 s ramp ({@code FadeCurveTest} prints the same table; the
     * "before" column is what rounds 12–16 shipped):
     *
     * <pre>
     *                     t=0.25   t=0.50   t=0.75   t=0.90   -30dB at   -60dB at   both6dB
     * before (cos t^2.6)   -0.0dB   -0.3dB   -2.7dB  -17.9dB   14.10s     14.72s       68%
     * after  (this shape)  -9.3dB  -10.0dB  -59.6dB  -66.0dB    9.46s     11.28s       17%
     * </pre>
     *
     * <p>So the outgoing track's vocals are 10 dB down within the first quarter of the
     * blend, 30 dB down by the start of its last third and gone 3.7 s before the blend
     * ends — against "at its own level until three quarters, then gone in a second". The
     * both-audible share falls from 68% to 17%, and that is the point rather than a
     * regression: the earlier number described two tracks at their own level at once.
     *
     * <p>The incoming track is the sine of {@code t^0.45} (see {@link #IN_BED_EXPONENT}):
     * it reaches half its level within the first tenth of the window, is a real bed from
     * there on, and owns the last stretch alone. The two are summed, so the blend's own
     * loudness dips about 1.9 dB at a fifth of the ramp (the outgoing track leaving faster
     * than the incoming one arrives) and is back at one track's level by two thirds of it;
     * the peak every earlier version had — up to +2.3 dB in the middle — is gone (+0.2 dB,
     * a dip in the middle instead).
     */
    DJ_BLEND("DJ 式") {
        /**
         * The level the outgoing track is brought down to, dB below its own full scale, for
         * the body of the blend — the "淡一点，要不抢了" half of the shape.
         *
         * <p>The staged shape used to hold the outgoing track at its own level for most of
         * the window and spend the whole exit at the end (rounds 12–16, {@code cos(t^2.6)}).
         * The measurement that ended it is a listening one: with the outgoing track at unity
         * and the incoming one arriving underneath it, the outgoing track's <em>vocals</em> —
         * which this app cannot remove, because stripping them would mean switching the
         * audible deck's source mid-playback, the mechanism behind the P0 ("playback died
         * after a transition") and the replay incidents — stay the loudest thing in the room
         * for the whole blend, and two sets of vocals at once is the "人声混合得很乱" the
         * user reported as a serious problem. Nothing about the material can fix that from
         * inside the deck; the only lever is level.
         *
         * <p>Ten decibels is a third of the amplitude and about half the loudness: the
         * outgoing track is still plainly there as the bed the incoming one is built over —
         * which the key blend needs, since its ladder moves <em>both</em> decks — while its
         * vocals sit 10 dB under the incoming track's own material instead of on top of it.
         * The cost is measured, not hidden: the two tracks are within 6 dB of each other for
         * about a sixth of the ramp instead of two thirds
         * ({@link #bothAudibleMs(long)} — see the class's table), because a track that is
         * deliberately 10 dB down is not "both tracks at the same level". That is the point
         * of the change: the earlier rounds' 68% was two full-level tracks at once.
         */
        private static final double OUT_BED_DB = -10d;

        /**
         * How much of the ramp is spent stepping down into {@link #OUT_BED_DB}: the first
         * three tenths, with zero slope at both ends so the step is a fade rather than a
         * duck.
         *
         * <p>Not shorter, because a fast drop to the bed is heard as the current song being
         * turned down; not longer, because the whole point is that the outgoing track stops
         * competing early. Three tenths of a 15 s ramp is 4.5 s, which is a bar or two at
         * this library's tempos — the length of an ordinary musical gesture.
         */
        private static final double OUT_BED_ARRIVE = 0.30d;

        /**
         * Where the exit starts, as a share of the ramp: the middle. Everything before it is
         * the bed; everything after it is the outgoing track leaving.
         *
         * <p>This is the "更早" half of the requirement. The round-16 shape began its exit at
         * three quarters of the ramp, and — because it was working from −2.7 dB rather than
         * from the bed — only crossed −30 dB at 94% of the window. This one crosses it at
         * 63% (measured on a 15 s ramp: 9.5 s against 14.1 s) and is gone by 82% rather than
         * at 97%, so the last stretch of the blend belongs to the incoming track alone. Its
         * own vocals are out for that whole stretch (see {@code DjEdit}), so what the
         * listener keeps hearing is the incoming track's backing — and then, after the blend
         * has ended, its voice.
         */
        private static final double OUT_EXIT_START = 0.50d;

        /**
         * Where the exit is over, as a share of the ramp: the level has reached
         * {@link #OUT_EXIT_FLOOR_DB} here and stays there until the held zero.
         *
         * <p>0.82 rather than the round-16 shape's ~0.97: the exit is the same shape but
         * moved forward as a whole, which is what "faster" means where it can be measured —
         * the time the outgoing track spends above −30 dB of its own level. The peak slope
         * is unchanged (a raised cosine over 0.32 of the ramp covering 56 dB: about 17 dB/s
         * on a 15 s blend), so the exit sounds like the round-16 one, just earlier.
         */
        private static final double OUT_EXIT_FLOOR = 0.82d;

        /**
         * The level the exit reaches, dB below full scale: six decibels under
         * {@link #INAUDIBLE_DB}, so "the outgoing track was released at or below the floor"
         * is never a question of the last bit of a logarithm.
         *
         * <p>It is not zero: the shape's promise of an exactly-zero stretch is
         * {@link #OUT_SILENT_TAIL}, which begins later, and a written gain of −66 dB is the
         * same nothing but keeps the trajectory expressible as a number in the log.
         */
        private static final double OUT_EXIT_FLOOR_DB = -66d;

        /**
         * The stretch at the very end of the ramp where this shape is at <b>exactly</b>
         * zero — the guarantee that releasing the outgoing player cannot be heard as a cut.
         *
         * <p>The ramp promotes (and the backend releases the outgoing player) on the first
         * tick at {@code t >= 1}, which can be up to one {@code RAMP_TICK_MS} after the
         * ramp's end and therefore after the last gain write of a player that is being torn
         * down microseconds later. The exit above already asks for −66 dB from 82% on, but
         * "silence at exactly one instant" is a weaker promise than it looks: a tick delayed
         * by a stalled main thread, or a release reached by any path that is not the
         * promotion, would tear the player down at whatever the previous tick set. So the
         * last 1% of the ramp is not a fade but a held zero: the outgoing track has been at
         * exactly zero for at least ({@code OUT_SILENT_TAIL} × ramp − one tick) before
         * anything can release it — 118 ms of a 15 s ramp, 168 ms of a 30 s one.
         *
         * <p>1% is invisible as a discontinuity: the shape is already at −66 dB when the
         * held zero begins, on the far side of {@link #INAUDIBLE_DB}. The backend logs that
         * assertion at every release (see {@code AndroidAudioBackend}'s release line), so a
         * future shape that lost this property would say so instead of only being heard. */
        private static final double OUT_SILENT_TAIL = 0.01d;

        /** How fast the incoming track's bed arrives: the sine of {@code t} raised
         *  to this, so a smaller exponent means a level reached sooner.
         *
         *  <p>0.45 since round 17 (it was 0.55). With the outgoing track now down at
         *  {@link #OUT_BED_DB} for the body of the blend, the incoming track is the only
         *  track at its own level, and the earlier it gets there the smaller the trough
         *  between the two: the summed power dips about 1.9 dB at a fifth of the ramp with
         *  0.45 against about 2.6 dB with 0.55. It also puts the incoming track's backing in
         *  place sooner, which is the other half of "让上一首淡一点，让下一首铺进来". */
        private static final double IN_BED_EXPONENT = 0.45d;

        @Override public float outGain(float t) {
            double x = clamp(t);
            // Held at zero for the last OUT_SILENT_TAIL of the ramp: anything that releases
            // this player from here on is releasing something that is not making a sound.
            if (x >= 1d - OUT_SILENT_TAIL) return 0f;
            return (float) Math.pow(10d, outLevelDb(x) / 20d);
        }

        /**
         * The outgoing track's own level at {@code t}, dB — the shape is defined in dB and
         * converted to a gain, and that is deliberate: what the requirement is written in,
         * what the boundary's log line reports and what "monotone" has to mean are all
         * decibels. A shape defined as a gain has a dB curve whose slope runs away wherever
         * the gain approaches zero, which is how the round-16 exit managed to be both
         * monotone in gain and audible as a cliff.
         *
         * <p>Three stretches, each eased with a raised cosine so there is no step in level
         * <em>or</em> in slope at either end of it: down into the bed, the bed, and the
         * exit. Never increasing — the test walks it at 1/1000 resolution.
         */
        private double outLevelDb(double t) {
            if (t < OUT_BED_ARRIVE) return OUT_BED_DB * ease(t / OUT_BED_ARRIVE);
            if (t <= OUT_EXIT_START) return OUT_BED_DB;
            double u = Math.min(1d, (t - OUT_EXIT_START) / (OUT_EXIT_FLOOR - OUT_EXIT_START));
            return OUT_BED_DB + (OUT_EXIT_FLOOR_DB - OUT_BED_DB) * ease(u);
        }

        /** A raised cosine over 0..1: 0 at both ends, 1 in the middle, zero slope at both
         *  ends, so anything built from it joins its neighbours smoothly. */
        private double ease(double u) {
            return 0.5d * (1d - Math.cos(Math.PI * clamp((float) u)));
        }

        @Override public float inGain(float t) {
            return (float) Math.sin(Math.pow(clamp(t), IN_BED_EXPONENT) * Math.PI / 2.0);
        }

        @Override public float outSilentTail() {
            return (float) OUT_SILENT_TAIL;
        }
    },

    /**
     * The fusion's deck-level hand-over: <b>a linear (equal-gain) fade of
     * {@link #JUNCTION_XFADE_MS} — about one bar of the incoming track — at the top of the ramp,
     * and nothing anywhere else.</b> The outgoing deck falls unity → <em>exactly</em> 0 over that
     * window, the incoming deck rises <em>exactly</em> 0 → unity over the same window, and both
     * are then <b>constant</b> for the rest of the ramp.
     *
     * <p><b>Why this shape exists, and why the other three cannot stand in for it.</b> Every
     * earlier shape is a pair of levels travelling in opposite directions over the whole window,
     * which is a crossfade — and the requirement the fusion answers is that the audible gesture
     * must never read as one. The fusion's file has the outgoing track's own material inside it
     * from the junction on (see {@code StemEditRenderer.Result.isFusion}), so by the time this
     * shape's ramp starts, the transition is <em>already carried</em> by the one source the
     * incoming deck is playing: the live decks no longer have to travel anywhere, they only have
     * to hand over once, at the top. That instant is a bar line — which is why the controller
     * starts this ramp on the outgoing deck's own position rather than on what is left of its
     * file: a hand-over anywhere else would put A's live deck against a file that is not at the
     * same instant, and that is a phase step, not a late blend.
     *
     * <p><b>⚠️ The outgoing track is never force-stopped, and that is the requirement this length
     * answers.</b> The user's own words, after listening to a rendering of the shorter cut:
     * 「你似乎给前一首歌强制停止了，不要这么干，让它放完并不要让它戛然而止，可以加淡出，
     * 但不要抢整体效果」 — <em>you seem to have force-stopped the previous song; do not do that,
     * let it play out and do not let it end abruptly; a fade-out is allowed, but do not let it
     * dominate the whole effect.</em> Both halves of that are properties of this constant and of
     * nothing else: the outgoing deck is <em>released</em> wherever the ramp ends (see
     * {@code AndroidAudioBackend.logOutgoingRelease}), so the only way to keep it from being
     * "stopped" is for its level to have reached zero on its own, musically, well before that
     * instant — and the only way for the fade not to "dominate" is for it to be a small share of
     * the window rather than the window itself.
     *
     * <p><b>Why it is about a bar — {@link #JUNCTION_XFADE_MS} — and no longer 300 ms.</b> The
     * first cut of this shape was an 80 ms equal-power splice (the same 80 ms the fusion's file
     * uses for its bar-line element swaps), on the reasoning that the hand-over should read as a
     * cut; a rendered prototype was listened to and reported as a <b>卡顿</b> exactly at the
     * junction, where the render measured a <b>−2.8…−4.1 dB</b> step and a change of timbre (the
     * live master giving way to the same music rebuilt from separated stems). Three hundred
     * milliseconds turned that step into a slide and was heard as a fade-in instead — but it still
     * ends A on the spot: 300 ms of a 15 s blend is not enough for a phrase to finish. So the
     * window is now one <em>bar</em> of the incoming track at the ramp's own scale. The ramp
     * carries no grid (it is a level curve, not a timeline), which is why this is a constant in the
     * 1.5–2.5 s range rather than a computed bar: 2000 ms covers this library's ordinary tempos
     * (75–160 BPM is a 1.5–3.2 s bar) closely enough for a crossfade whose shape is the thing
     * being heard, and it leaves 13% of a 15 s blend as the hand-over with the remaining 87% at
     * the incoming deck's own level.
     *
     * <p><b>Why the file is continuous with the live deck across the whole window.</b> The window
     * is this long because it <em>can</em> be: the renderer holds the outgoing track's carried
     * rows at unity through it and only lets them recede afterwards ({@code StemFusion}'s own
     * table), so for these two seconds the incoming deck is playing the outgoing track's own
     * material — not a bed, not a shadow of it — and the two decks are carrying the same music.
     * That is what makes the fade a change of source rather than a change of song: at the end of
     * it the listener has lost nothing, because everything the live deck was still going to play
     * is in the file from that same bar line on (the file's copy is cut at exactly this
     * instant — see the controller's position-based trigger).
     *
     * <p><b>Why LINEAR and not EQUAL_POWER — the load-bearing part.</b> The junction is built so
     * the incoming deck starts playing exactly on the outgoing's own bar line, and the file's
     * first bar carries <em>the same material</em> the outgoing deck is playing at that instant
     * (A's own drums, bass and a quiet A melodic over B's bed). The two decks are therefore
     * <em>correlated</em> signals, not independent ones, and the usual reason to prefer equal power
     * — two <em>uncorrelated</em> tracks summed at half gain lose 3 dB of power — does not apply
     * here. It inverts: for correlated (here, literally the same) material the gains sum
     * <em>amplitudes</em>, so an equal-power changeover puts its middle at cos 45° + sin 45° =
     * 1.414 of one deck's level, i.e. <b>+3 dB</b> — a swell in the middle of the hand-over,
     * exactly the 音量跳 the equal-power pair exists to avoid between independent tracks and
     * <em>creates</em> between correlated ones. Equal gain ({@code 1−u} and {@code u}) sums
     * correlated material to one deck's level at every instant, so the music stays flat through
     * the fade — which is the entire point of a hand-over between two copies of the same bar, and
     * the property the harness measures as {@code outGain + inGain = 1} to a float ulp.
     *
     * <p><b>The two gains, exactly.</b> {@code outGain} = 1 at {@code t = 0}, falling linearly to
     * 0 at {@link #JUNCTION_XFADE_MS} into the ramp, then <em>exactly</em> 0 to the end;
     * {@code inGain} = 0 at {@code t = 0}, rising linearly to 1 at the same instant, then exactly
     * 1 to the end. The outgoing deck is consequently <b>not</b> silent for the whole blend — it
     * is audible for the ramp's first 2000 ms by construction, which is the point — but the
     * release assertion is unaffected, because what it needs is not "silent all blend" but "at
     * exactly zero and holding well before anything may release it": this curve reaches exactly
     * zero a bar in and stays there, so {@link #outSilentTail(long)} is {@code rampMs − 2000}
     * (13000 ms of a 15 s ramp, 28000 ms of a 30 s one, 50% of a 4 s one) and
     * {@code AndroidAudioBackend} logs that held stretch on its release line.
     *
     * <p><b>Shorter ramps.</b> The window is a duration, not a share, so a ramp no longer than it
     * gets the whole window as its fade — {@code share = min(1, 2000/rampMs)} — and never runs past
     * the end ({@link #outGain(float, long)}). Realistic fusion ramps are 4–30 s long (the
     * controller's ramp is {@code min(blendMs, remaining − 250)}), where 2000 ms is 7–50% of the
     * window.
     *
     * <p>Not selectable as a 淡化曲线: it is only ever handed to the backend by the controller,
     * for a boundary whose edit is a fusion. Applied to an ordinary boundary — where the incoming
     * deck plays material of its own, uncorrelated with the outgoing's — it would fade the
     * outgoing deck out inside the first 2000 ms and leave the incoming one alone for the rest of
     * the window, i.e. a switch dressed as a blend.
     */
    FUSION("融合") {
        /** The outgoing track's level: unity, a linear fall to nothing, then nothing.
         *  {@code share} is {@link FadeCurve#JUNCTION_XFADE_MS} as a fraction of the ramp, so
         *  {@code x/share} is the fade's own progress {@code u} — the same number {@link #inAt}
         *  rises by, which is what makes the pair equal-gain rather than merely symmetric. (An
         *  instance method, like DJ_BLEND's {@code ease}: an enum constant's body is an inner
         *  class, where static methods are illegal — only its constants may be static.)
         *
         *  <p>The end of the fade is a comparison, not a threshold, so the zero it reaches is
         *  exact rather than "very small": every float {@code t} at or past {@code share} is
         *  exactly 0. The one sample that is not is a sample landing within a float ulp
         *  <em>before</em> it — 2000 ms of a 15 s ramp is {@code 0.13333…}, which no float
         *  represents, and the largest float below it falls in this branch and asks for 6e-8, i.e.
         *  −144 dB. That is the fade not quite having finished, not a level. */
        private float outAt(float t, double share) {
            double x = clamp(t);
            if (x <= 0d) return 1f;
            if (share <= 0d || x >= share) return 0f;
            return (float) (1d - x / share);
        }

        /** The incoming track's level: the mirror image of {@link #outAt} — silence, the same
         *  linear rise, then unity. Adding the two is one at every {@code t}: both are built
         *  from the single value {@code x/share}, so the pair is equal-gain by construction and
         *  a correlated hand-over stays flat (see this constant's doc). */
        private float inAt(float t, double share) {
            double x = clamp(t);
            if (x <= 0d) return 0f;
            if (share <= 0d || x >= share) return 1f;
            return (float) (x / share);
        }

        /** The fade as a fraction of the ramp — about a bar of it — or 1 (the whole window)
         *  when the ramp is not longer than the fade or its length is unknown; never more
         *  than the ramp, so the fade cannot run past the end. */
        private double shareOf(long rampMs) {
            return rampMs > 0L ? Math.min(1d, (double) JUNCTION_XFADE_MS / rampMs) : 1d;
        }

        /**
         * The shape with no length to place the fade in, so the fade IS the window: a plain linear
         * (equal-gain) pair from one track to the other — the same shape as {@link #LINEAR}, which
         * is what a hand-over between correlated decks is when the fade is all the ramp there is.
         *
         * <p>Only ever the answer where a length is genuinely unknown — a caller walking the enum
         * to measure a shape ({@link #bothAudibleMs(long)}, {@code FadeCurveTest}). The ramp
         * itself always has a length, and every path that applies this curve to audio knows it,
         * so the audio always gets the {@link #JUNCTION_XFADE_MS} fade
         * ({@link #outGain(float, long)}).
         */
        @Override public float outGain(float t) {
            return outAt(t, 1d);
        }

        @Override public float outGain(float t, long rampMs) {
            return outAt(t, shareOf(rampMs));
        }

        @Override public float inGain(float t) {
            return inAt(t, 1d);
        }

        @Override public float inGain(float t, long rampMs) {
            return inAt(t, shareOf(rampMs));
        }

        /**
         * Everything after the fade: the outgoing track is at <b>exactly</b> zero from
         * {@link #JUNCTION_XFADE_MS} into the ramp onwards, so the stretch a player may be
         * released inside is the whole ramp bar its first 2000 ms — {@code rampMs − 2000} of it.
         *
         * <p>The only shape whose answer depends on the ramp's length, and the reason
         * {@link #outSilentTail()} alone cannot describe it: "the last 1% of a 15 s ramp" is a
         * share, while "all of it except a bar at the head" is not a share at all.
         *
         * <p>⚠️ The number this returns is the one the release line quotes as "the held zero this
         * curve promises", and it is now a bar shorter than it was at 300 ms of hand-over: on a
         * 15 s ramp 13000 ms instead of 14700, and on a 4 s one 2000 instead of 3700. Still two
         * orders of magnitude more than the write-to-release gap it exists to bound (the ramp
         * promotes within one {@code RAMP_TICK_MS} of its end, tens of milliseconds), which is why
         * the assertion it feeds is unaffected by the length — see {@code logOutgoingRelease}.
         */
        @Override public float outSilentTail(long rampMs) {
            return rampMs > 0L
                    ? (float) Math.max(0d, (rampMs - JUNCTION_XFADE_MS) / (double) rampMs) : 0f;
        }
    };

    /**
     * How long the two decks of a {@link #FUSION} fade across, ms: <b>a linear (equal-gain) fade
     * at the top of the ramp</b> — the outgoing deck unity → 0 and the incoming 0 → unity across
     * this window, both constant outside it.
     *
     * <p>⚠️ <b>About one bar of the incoming track, because the outgoing track may not be
     * force-stopped.</b> The user's correction after listening: 「你似乎给前一首歌强制停止了，
     * 不要这么干，让它放完并不要让它戛然而止，可以加淡出，但不要抢整体效果」 — the previous song
     * must be allowed to play out, its end must not be abrupt, a fade-out is allowed but must not
     * dominate. A 300 ms hand-over satisfied neither half: 300 ms is not enough for a phrase to
     * finish, so A still ended on the spot, and the listener reported it as a stop. The window is
     * therefore a bar's worth of music rather than a splice's worth of milliseconds. The ramp
     * carries no grid (a level curve has no timeline to read a tempo from), so this is a constant
     * in the 1.5–2.5 s range covering this library's ordinary tempos — 75–160 BPM is a 1.5–3.2 s
     * bar — with the file holding the outgoing track's own material at unity across the whole of
     * it (see {@link #FUSION}'s doc: the two decks are carrying the same music here, which is why
     * the fade is a change of source and not a change of song).
     *
     * <p>A duration rather than a share of the window, deliberately — and that is exactly why the
     * fusion needs {@link #outGain(float, long)}: 2000 ms is 13% of a 15 s blend and more than a
     * whole 1.5 s one, so a share that meant "a musical fade" at one blend length would mean "the
     * entire blend" at another. Every caller that applies the curve or reports a number for it
     * has the ramp's real length in hand, so the length is passed rather than guessed.
     *
     * <p>The history, because each step was a listening report and the next reader should be able
     * to tell which one a build is: 80 ms (an equal-power splice, the same 80 ms the fusion's file
     * uses for its bar-line element swaps) was heard as a 卡顿 exactly at the junction, where the
     * render measured a −2.8…−4.1 dB step and a change of timbre; 300 ms (the same linear shape,
     * heard as 「新歌的进入请用淡入效果」 — a fade-in for the new song, which was the request at
     * the time) fixed the step but still ended the outgoing song on the spot, which is the report
     * this number answers. See {@link #FUSION}'s doc for why the fade is <em>linear</em> as well:
     * the two decks carry the same material across it and are therefore correlated, and an
     * equal-power fade would sum them to +3 dB in the middle.
     *
     * <p>Public because the boundary's log line quotes it: "the decks hand over in 2000 ms rather
     * than being cut" is a claim about this number, and a log that spelled the 2000 itself would
     * be able to disagree with the curve it is describing.
     *
     * <p>Ⓐ Round 31: <b>four seconds instead of two, and the listener's ear is the whole reason.</b>
     * 「在过渡段时保留第一首歌的人声渐隐，这样显得不那么突兀」 — what they are describing is exactly
     * what this fade IS, and why its length is the only knob that can answer them: the file the
     * incoming deck plays carries the outgoing track's own material but never its VOICE, so across
     * this fade the two decks are the same backing (equal-gain, so the backing's level is constant
     * by construction) <em>minus</em> the voice, which only the live deck has. The outgoing track's
     * voice therefore leaves over exactly this fade rather than being cut at the junction — and at
     * two seconds a singer stopping is heard as a stop, which is the 「突兀」 they reported. Four
     * seconds is a phrase at this library's tempos. Nothing else moves with it: the two decks are
     * still aligned at the junction and for the whole fade (the file's copy is the same material at
     * the same instant, which is why this is an equal-gain rather than an equal-power hand-over),
     * and {@code StemFusion} does not read this constant at all — the file's own vocal gate is its
     * own arithmetic, so the incoming track's voice still comes back where it did.
     */
    public static final long JUNCTION_XFADE_MS = 4_000L;

    /** How far apart the two tracks may be, dB, and still count as "both audible"
     *  for the measurement below. Six decibels is the usual "clearly quieter but
     *  plainly present" line: quieter than that and a track is heard as part of the
     *  other one's background rather than as a track of its own. */
    public static final float BOTH_AUDIBLE_DB = 6f;

    /**
     * The level, dB below its own full scale, at which a track is treated as silent —
     * the floor a player may be released at.
     *
     * <p>Releasing a player discards whatever is still buffered in it, so a release is a
     * hard cut by construction; the only thing that makes it inaudible is the gain the
     * track was at. -60 dB is a thousandth of full scale, far below the noise floor of
     * anything this app plays and below the smallest step of the 16-bit output path, so a
     * track released at or under it is not "very quiet", it is not there. The backend
     * asserts this at every promotion and logs the numbers it checked, because "the ramp
     * should have faded it out" is exactly the assumption that produced the reported
     * 「上一首歌戛然而止」: the gain a player is released at is the one thing about a blend
     * that cannot be heard afterwards, only seen in the log.
     */
    public static final float INAUDIBLE_DB = -60f;

    /** A gain as dB, with a floor instead of negative infinity: {@link #INAUDIBLE_DB} for
     *  anything at or below it, so the release assertion can print a number. */
    public static double gainDb(float gain) {
        if (gain <= 0f) return INAUDIBLE_DB;
        return 20d * Math.log10(gain);
    }

    private final String label;

    FadeCurve(String label) {
        this.label = label;
    }

    /** {@code t} forced into 0..1, so a caller measuring at, or past, the ends of
     *  the ramp still gets an answer instead of {@code NaN}. */
    private static double clamp(float t) {
        return t <= 0f ? 0d : (t >= 1f ? 1d : t);
    }

    /**
     * How much of a ramp of {@code rampMs} this curve spends with <b>both tracks
     * audible</b> — the two gains within {@link #BOTH_AUDIBLE_DB} of each other.
     *
     * <p>This is the measurable stand-in for "the blend sounds long enough": the
     * complaint a symmetric ramp earns is that the window where the listener can
     * hear two tracks is only about half of it (one track alone at each end is a
     * fade, not a mix), and unlike the overlap length itself this number is a
     * property of the curve. It is reported on the ramp's own log line so a
     * boundary's "feels longer" claim can be checked against a number instead of a
     * memory of the previous build.
     *
     * <p>Sampled rather than integrated in closed form: the two curves have no
     * common primitive once the exponents are in, and a few hundred samples put the
     * resolution far below anything a listener could hear.
     */
    public long bothAudibleMs(long rampMs, float withinDb) {
        if (rampMs <= 0L) return 0L;
        int steps = 400;
        // A gain ratio of `r` is `20*log10(r)` dB, so "within N dB" is a ratio of at
        // most 10^(N/20) — computed once, because the loop below only compares.
        double maxRatio = Math.pow(10d, Math.max(0f, withinDb) / 20d);
        int inside = 0;
        for (int i = 0; i < steps; i++) {
            float t = (i + 0.5f) / steps;             // midpoint of each slice
            // Asked WITH the ramp's length: for every shape written in t alone this is the
            // same number, and for FUSION it is the only way its JUNCTION_XFADE_MS fade can be
            // placed in a window of this length at all — its share of the ramp is not a property
            // of the shape.
            float out = Math.abs(outGain(t, rampMs));
            float in = Math.abs(inGain(t, rampMs));
            float loud = Math.max(out, in);
            float quiet = Math.min(out, in);
            if (loud <= 1e-6f) continue;              // neither is audible at all
            if (quiet <= 1e-6f) continue;
            if (loud / quiet <= maxRatio) inside++;
        }
        return Math.round((double) rampMs * inside / steps);
    }

    /** {@link #bothAudibleMs(long, float)} at the standard 6 dB. */
    public long bothAudibleMs(long rampMs) {
        return bothAudibleMs(rampMs, BOTH_AUDIBLE_DB);
    }

    /** "12200ms of the 15000ms ramp (81%)" — the fragment the ramp's log line
     *  carries, so the shape's one measurable property travels with every boundary. */
    public String bothAudibleText(long rampMs) {
        long ms = bothAudibleMs(rampMs);
        long pct = rampMs > 0L ? Math.round(100d * ms / rampMs) : 0L;
        return ms + "ms of the " + rampMs + "ms ramp (" + pct + "%)";
    }

    /**
     * <b>The instant the outgoing track has left the passage</b>, as a share of the ramp:
     * the first {@code t} at which this shape's outgoing gain is at or below {@code withinDb}
     * dB — the point past which nothing of the outgoing track can be heard any more, however
     * loud it was authored. (On the symmetric shapes that instant is the ramp's own end: their
     * gain passes the floor 0.1% before it, so the answer is 0.999 — a fifteenth of a second of a
     * 15 s blend, and the whole ramp as the window that reads it rounds.)
     *
     * <p><b>Why this is a property of the curve and not a constant.</b> The incoming track's
     * voice is held out of its rendered file for exactly as long as two voices could stack —
     * i.e. as long as the outgoing track can still be heard — and that instant is decided by
     * the shape the boundary ramps along, not by the blend's length. Written as one number it
     * would be wrong for whichever curve it was not measured on: {@link #DJ_BLEND}'s exit is at
     * its floor by 82% of the ramp (so a 17 s blend leaves the outgoing inaudible from 12.8 s),
     * while {@link #LINEAR} and {@link #EQUAL_POWER} hold the outgoing all the way to the last
     * sample (so the voice must stay out for the whole blend). Hard-coding the DJ figure would
     * return the incoming's voice 25% before the end of a symmetric ramp, with the outgoing
     * track still plainly audible underneath it — the defect this measurement exists to avoid,
     * in the other direction.
     *
     * <p>{@link #FUSION} is answered through the ramp-aware {@link #outGain(float, long)}
     * overload: its hand-over is a fixed {@link #JUNCTION_XFADE_MS}, so its share of the ramp
     * depends on the ramp's own length, and the share for a fusion of this length is what comes
     * back.
     *
     * <p>Bisection rather than a closed form: every shape here is monotone non-increasing in
     * {@code t} (which is what makes the search valid, and is asserted by the curve suite), and
     * one method that reads the shape's own gains cannot drift away from the shape the way four
     * hand-derived constants would. Forty halvings resolve the instant far below one sample of
     * any ramp this app builds.
     *
     * @param withinDb the level that counts as gone — {@link #INAUDIBLE_DB} wherever this is
     *                 used as "the voice may come back now"
     * @return 0 when the outgoing is already gone at the ramp's top, 1 when it is still audible
     *         at its end (then the whole ramp is the two-voice stretch)
     */
    public double outLeftAt(long rampMs, float withinDb) {
        if (gainDb(outGain(0f, rampMs)) <= withinDb) return 0d;
        if (gainDb(outGain(1f, rampMs)) > withinDb) return 1d;
        double lo = 0d;
        double hi = 1d;
        for (int i = 0; i < 40; i++) {
            double mid = 0.5d * (lo + hi);
            if (gainDb(outGain((float) mid, rampMs)) <= withinDb) hi = mid; else lo = mid;
        }
        return hi;
    }

    /** {@link #outLeftAt(long, float)} at {@link #INAUDIBLE_DB} — the boundary's own question:
     *  "when has the outgoing track left this passage, as a share of its ramp". */
    public double outLeftAt(long rampMs) {
        return outLeftAt(rampMs, INAUDIBLE_DB);
    }

    /** Chinese name for the settings UI. */
    public String label() {
        return label;
    }

    /** Gain applied to the outgoing track at overlap progress {@code t} (0–1). */
    public abstract float outGain(float t);

    /**
     * The share of the ramp, at its end, over which this shape holds the outgoing track
     * at <b>exactly</b> zero — the stretch a player may be released inside without that
     * release being a sound of its own.
     *
     * <p>Zero for the shapes that fade all the way to the end: their last gain write is
     * silence only at the instant the ramp finishes, which is a weaker promise but an
     * adequate one for a ramp that is a quarter of a second long and is not expected to
     * be anything but a clean edge. {@link #DJ_BLEND} is the shape that needs more than
     * that, being a multi-second exit a listener follows: see its
     * {@code OUT_SILENT_TAIL}, and the backend's release line, which prints this number
     * beside the gain it actually released at.
     */
    public float outSilentTail() {
        return 0f;
    }

    /**
     * The same, for a ramp of {@code rampMs} — see {@link #outGain(float, long)}. The default
     * answers the length-independent value, which is the right answer for every shape whose
     * promise is written as a share of the window; {@link #FUSION} is the one whose promise is
     * written as a number of milliseconds and so cannot be.
     */
    public float outSilentTail(long rampMs) {
        return outSilentTail();
    }

    /**
     * Gain applied to the outgoing track at overlap progress {@code t} (0–1) of a ramp
     * {@code rampMs} long — the same shape as {@link #outGain(float)}, for a caller that knows
     * how long the ramp is.
     *
     * <p>Most shapes are written in terms of {@code t} alone and ignore the length (this default
     * does): a fade is a fade however long its window is. {@link #FUSION} is the exception, and
     * the reason this overload exists — its whole hand-over is a fixed {@link #JUNCTION_XFADE_MS}
     * fade at the top of the ramp, so the share of the ramp it occupies is only knowable from the
     * ramp's real length. The backend has it at every tick ({@code rampDurationNs}), and every
     * measurement that quotes a number for a ramp ({@link #bothAudibleMs(long, float)}) has it in
     * hand, so no caller that applies or reports this curve has to guess.
     */
    public float outGain(float t, long rampMs) {
        return outGain(t);
    }

    /** Gain applied to the incoming track at overlap progress {@code t} (0–1). */
    public abstract float inGain(float t);

    /** {@link #inGain(float)} for a ramp of {@code rampMs} — see {@link #outGain(float, long)}. */
    public float inGain(float t, long rampMs) {
        return inGain(t);
    }
}
