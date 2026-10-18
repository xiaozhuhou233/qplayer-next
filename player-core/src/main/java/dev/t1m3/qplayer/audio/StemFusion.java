package dev.t1m3.qplayer.audio;

import java.util.Locale;

/**
 * The fusion: both tracks' backgrounds combined into ONE passage, three bars long, written into
 * the incoming track's own file — round 18's replacement for the fade a two-deck blend is.
 *
 * <p><b>What it is for.</b> A blend of two records reads as a fade in the middle whatever the
 * gain curves do, because the outgoing track's <em>rhythm</em> leaves with it (the low end is
 * handed over part way through, and its body ramps away over the next seconds). The fusion stops
 * fading altogether: the outgoing track's own bars continue inside the incoming's file, and each
 * of its elements is removed by an <b>80 ms splice on a bar line</b> — never by a level ramp over
 * a whole track — while the incoming's own elements arrive on the same lines, one bar apart.
 * Nothing audible about the transition therefore sits on a fade curve.
 *
 * <pre>
 * | file window        | A drums | A bass | A other | B drums | B bass | B other |
 * | [entry, swap)      | unity   | unity  | -12 dB  | 0       | 0      | silence -> unity |
 * | [swap, bass)       | 0       | unity  | -12 dB  | unity   | 0      | unity           |
 * | [bass, fusionEnd)  | 0       | 0      | 0       | unity   | unity  | unity           |
 * </pre>
 *
 * <p>Every `unity`/`0` pair above is one {@link #CUT_MS} equal-power splice on the bar line, and
 * each one is counted and reported ({@link Report#splices}). <b>The one exception is the
 * incoming's own bed ({@code other}), which fades in over {@link #BED_FADE_BARS} bar — one whole
 * bar of its own grid, equal-power, from {@code entry} to unity.</b> The user asked for exactly
 * that after hearing the first prototype (「新歌的进入请用淡入效果」), and it is also what keeps the
 * level the listener is at continuous while the outgoing track's voice leaves: a bed arriving over
 * the same bar the voice departs is what masks it. The incoming's drums and low end still swap
 * <em>by cut</em> on their own lines — that is the hand-over's signature, and a faded drum swap
 * would smear the pulse. Outside the window the file is exactly what it is without a fusion: the
 * incoming's backing at unity under its own vocal gate. The outgoing's <b>vocal row is never
 * carried</b> — the whole point of the window is that neither track sings over the other.
 *
 * <p><b>The second half of the junction's contract is the peak.</b> The outgoing's own master
 * already peaks at about full scale, so any added bed is over it — a static divisor over the head
 * is not acceptable (measured on real material: −4.55 dB over the whole head, which the listener
 * heard as a 卡顿 at the junction). The head therefore carries a peak limiter ({@link
 * DjEdit.Limiter}: 1 ms attack, 150 ms release, no makeup gain), and the render measures its own
 * <b>step</b>: the fusion's first {@link #STEP_WINDOW_MS} RMS against the outgoing master's last
 * {@link #STEP_WINDOW_MS}, which has to be within {@link #JUNCTION_STEP_MAX_DB}. A fusion that
 * cannot sit at the outgoing's own level is refused rather than played as a drop.
 *
 * <p><b>Where the material comes from.</b> The outgoing deck is cut on a bar line of its own grid
 * ({@code junctionMs}), so the passage the file carries is the music that <em>would have played
 * next</em> — decoded when the outgoing deck dies and carried forward inside the incoming's file,
 * resampled by {@code speed} so that playback at {@code speed} returns it to the outgoing track's
 * own tempo and pitch (the same bargain {@link StemBridge} makes for its two bars of bass).
 *
 * <p>Pure arithmetic on separated stems: no platform, no files, no model — so what a render will
 * do is a unit test, and the acceptance numbers ({@link #measure}) are the same arithmetic the
 * render reports. This is the round-17 {@link StemBridge} pattern applied to the whole backing
 * rather than to the bass alone; it reuses that class's measurement primitives
 * ({@link StemBridge#levelDb}, {@link StemBridge#frameLevelsDb}, {@link StemBridge#median},
 * {@link StemBridge#alignmentAtZeroLag}) so "quiet", "present" and "the voice" mean the same
 * thing in both places.
 *
 * <p><b>Why the spliced gesture is not {@link StemGesture}'s.</b> {@code StemGesture} carries the
 * ported Folia grammar — a <em>takeover</em> scored by listening rounds, whose cut is
 * {@link StemGesture#CUT_SEC} = 500 ms and whose shapes are the harness's own. The fusion's
 * gesture is a different thing (three fixed bars, 80 ms splices, per-row silence) and its shape is
 * fixed by the spec, so it is written here; what is reused is {@link StemGesture#Stem} (the model's
 * row order), {@link StemGesture#barLines} (via the caller's own bar-line computation) and
 * {@link StemGesture#CUT_SEC}'s sibling idea of an equal-power splice.
 */
public final class StemFusion {

    /** How many bars of the incoming's grid the fusion lasts — the window the whole table lives
     *  in. Three, because the table has three states (both backings, the drums swapped, the low
     *  end swapped) and each one needs a bar to be heard as a state rather than as an edit. */
    public static final int FUSION_BARS = 4;

    /** How many steps of the table the outgoing's rows hold at <b>unity</b> before they start to
     *  recede (round 6). The hold is what keeps the file continuous with the outgoing's live deck
     *  for the whole of the deck-level handover — the boundary's own changeover is about a bar (see
     *  {@code FadeCurve.JUNCTION_XFADE_MS}) — so the two fades do not stack and A is never heard
     *  being cut. The full gesture is {@link #A_HOLD_STEPS} plus the low end's own fade; a pair
     *  whose bar is so long that four steps do not fit the separation budget gets one step of hold
     *  (still: A recedes, never cuts), which is what {@link #plan} decides. */
    public static final int A_HOLD_STEPS = 2;

    /** How many steps of the table the outgoing's <b>drums</b> take to fade to the floor (round 6:
     *  「可以加淡出」 — a fade is allowed, a cut is not). One step: a drum fade shorter than a bar
     *  still reads as the drummer stopping at a phrase's end rather than as a switch. */
    public static final int A_DRUMS_FADE_STEPS = 1;

    /** How many steps the outgoing's <b>low end and melodic row</b> take to fade to the floor. Two,
     *  because the low end is what carries the groove across the handover and it is the part the
     *  incoming's own low end rises underneath. */
    public static final int A_LOW_END_FADE_STEPS = 2;

    /** How many steps the incoming's <b>drums and low end</b> take to rise to unity (round 6: the
     *  outgoing's departure is what must not be a stop; the incoming's arrival is a rise because a
     *  step into an otherwise continuous backing is a click). One step, starting where the
     *  outgoing's hold ends, so both backings are near unity across that whole step. */
    public static final int B_ARRIVAL_FADE_STEPS = 1;

    /** How many steps before the hold's end the incoming's backing starts to rise (round 28).
     *
     *  <p>The rise still <em>ends</em> on the hold's own end — that instant, and every level the
     *  table writes around it, are unchanged; this constant only decides how much of the passage
     *  the second track is audible for on the way up. It was one step until round 28, which on the
     *  grown passages (round 18/24) left the second track at the floor for most of a 12 s passage
     *  and brought it in only in its last bar — the listener's 「由小变大时不要小声太久，尽快做到背景音与
     *  前一首融合」. {@link #B_ARRIVAL_FADE_STEPS} is consequently the span of a one-step hold, which
     *  is the shape it documents.
     *
     *  <p>⚠️ Round 28's first version set this to 2 and the listener answered 「你走了个极端，出曲现在太慢了
     *  ……现在有点长过头了，有点抢调」: an equal-power rise over two steps puts the incoming track at
     *  −3 dB a whole step before the hand-over, so the two backings — each in its own key — are both
     *  loudly present for twice as long, which is heard as the outgoing track hanging around and as
     *  the two tonalities fighting (抢调). Reverted to one step, i.e. round 21's own shape.
     *
     *  <p><b>What the real answer is, and why it is not a number here.</b> The listener wants both
     *  things at once: the incoming <em>audible</em> early (no long silence) and <em>unity only</em>
     *  at the hand-over (no long clash). One rise cannot do both — an audible early level on a single
     *  monotone rise is a loud early level. It needs a <b>two-stage</b> arrival: quick up to a bed
     *  (the −10 dB the decks' own DJ_BLEND already uses for exactly this "淡一点，要不抢了" reason) and
     *  then to unity on the hold's end. That is one more instant in the arrival table (a
     *  {@code arriveBedMs} beside {@link Plan#arriveStartMs}), which is a change to this file, DjEdit
     *  and the report — the next round's job, not a constant to nudge. */
    public static final int B_ARRIVAL_LEAD_STEPS = 1;

    /** How many steps a <b>SLAM</b> lasts: <b>three</b> (one until the listening that made this
     *  {@link #RULE_VERSION} 10). A pair whose grids are in no relation has no common bar to lay
     *  three states of a table on, so the slam gets the one gesture that needs no beat matching at
     *  all — the outgoing's rows play for {@code SLAM_STEPS} bars of the incoming's own grid and
     *  everything changes hands on that bar line, which is where the incoming's grid is solid. The
     *  one cost of no relation is that this cut lands wherever it lands in the outgoing's own bar;
     *  that is what a DJ's slam mix does, and it is why the gesture is still a cut and not a fade.
     *
     *  <p><b>Why three — the listening that decided it.</b> One bar was never a length anybody chose:
     *  it was the shortest passage the arithmetic allowed, and what the user asked for at the end of
     *  round 30 is that the slam be <em>listened to as a length</em>. Four renders of the same pair at
     *  <b>1 / 2 / 3 / 4 bars</b> of the incoming's grid — the incoming's bar is <b>2 043.94 ms</b>, so
     *  <b>2.1 / 4.2 / 6.2 / 8.3 s</b> — and the user chose the <b>3-bar</b> render, 6.2 s. The plan
     *  that render comes from is the one number applied everywhere: the window is
     *  {@code 3 * 2 043.94 + 80 = 6 212 ms}, which is what the harness's own mirror
     *  ({@code fusion/round6.py}, whose {@code SLAM_STEPS} is this constant) prints for the chosen
     *  variant, and it passes that mirror's own clauses.
     *
     *  <p>⚠️ <b>And it is not the upper end of what would work — 5 and 6 bars are REFUSED</b>, by the
     *  arithmetic pass, before anything is rendered: "no line of the outgoing's grid inside the ...ms
     *  the passage can be taken from", because the passage and the junction search's own band are
     *  paid for out of the same cost bound ({@link #FUSION_TAIL_MAX_MS}). So 3 sits below a real
     *  ceiling rather than being an arbitrary middle. On the pair that was listened to (a
     *  2 043.94 ms bar) that bound is exactly what refuses 5 bars
     *  ({@code 5*2 043.94 + 80 + 2*1 000 = 12 300 > 12 000}) while 4 passes ({@code 10 256}) —
     *  which is the ceiling the renders found, reproduced by the arithmetic here.
     *
     *  <p><b>What this costs, said plainly:</b> the passage is now three bars of the incoming's grid
     *  out of a {@link #FUSION_TAIL_MAX_MS} budget shared with the separation's own leads, so a slam
     *  on a much slower incoming than the listened one is refused by {@link #refusal}'s cost clause
     *  where a one-bar slam used to fit — the bound bites at a bar wider than
     *  {@code (12 000 - 80 - 2 000) / 3 ≈ 3.3 s} (about 73 BPM at 4 beats a bar). Such a pair keeps
     *  today's plain edit and is refused before anything is decoded; that is the same arithmetic pass
     *  that refuses 5 and 6 bars, not a second rule.
     *
     *  <p><b>Everything on the slam path follows from this one number</b> — that is the property the
     *  length change had to preserve, and each of these is the same {@code SLAM_STEPS}:
     *  <ul>
     *    <li>the hold, i.e. the line everything changes hands on:
     *        {@code entry + SLAM_STEPS * barStep} (<b>swap</b> / <b>bass</b>);</li>
     *    <li>the file's window: {@code SLAM_STEPS * stepMs + CUT_MS}, so the {@link #CUT_MS} splice
     *        that ends on the line is <em>inside</em> the window {@code gainAt} guards
     *        ({@code fusionEndMs} = {@code swap + CUT_MS});</li>
     *    <li>the material A's own rows occupy, taken from A's file: {@code sourceSpanMs} =
     *        {@code ceil((SLAM_STEPS * stepMs + CUT_MS) / stretch)} — A's rows really do span three
     *        bars plus the splice;</li>
     *    <li>the pre-decode clause in {@link #refusal} that decides whether the pair may be attempted
     *        at all, and the voice floor the coupling reads the file's own gesture with
     *        ({@link #voiceFloorMs}, whose hold argument is this constant on a slam).</li>
     *  </ul>
     *
      * ⚠️ <b>A SLAM cuts, and it is the only gesture that does — on the user's own instruction.</b>
      * Round 5's listening produced two sentences and they are about different paths: for the
      * unrelated-tempo pairs the user asked for the join itself (「速度无关的也要接，不要淡入淡出」 — "no
      * fading in and out"), and round 6's listening ruled against cuts for the FUSION path (「不要让它
      * 戛然而止」 — do not stop it dead). A fusion can fade because the two grids are related and
      * a bar is a musical span to fade over; a slam's grids are unrelated, so there IS no such span —
      * any length would be a made-up time, which is what a fade would be too. What a slam owes the
      * ear is a hand-over that does not click, and that is the {@link #CUT_MS} equal-power
      * cross-fade on the line: measured on the device's own render, the sample-to-sample jump across
      * the line is 0.0913 with the splice against the signal's own 99.9th percentile of 0.2648
      * (0.3x, no dip), where an instantaneous cut reads 0.3227 (1.2x). So: keep the cut, run the
      * splice.
      *
      * <p>And the window is those {@code SLAM_STEPS} steps PLUS the splice — `fusionEndMs` is where A
      * is exactly gone, i.e. `swap + CUT_MS` — because the file has to contain the hand-over it is
      * named for. Without the extra 80 ms `gainAt`'s own guard sent the splice's samples to the floor
      * and the outgoing's rows fell unity → exactly 0 in one sample. The window is read as the max of
      * the two ways of writing the same span so that the rounding of a bar line can never leave the
      * splice's last sample outside it (see `plan`).
     */
    public static final int SLAM_STEPS = 3;

    /** How long each unity/0 change takes. 80 ms: long enough not to click, short enough that the
     *  change is heard as an edit on the line rather than as a fade — the whole point of the
     *  round. Deliberately NOT {@link StemGesture#CUT_SEC} (500 ms), which is Folia's cut. */
    public static final long CUT_MS = 80L;

    /** The most of the outgoing track that may be separated for one fusion, ms — the separation
     *  window, which is the material the carry needs ({@link #sourceSpanMs}) plus the lead the
     *  take's own alignment wants.
     *
     *  <p><b>12 000 until round 18, now 20 000 — the number {@link #RELATIVE_TAIL_MAX_MS} has used
     *  since round 5.</b> The passage is now as long as the listener's own window can pay for (see
     *  the growth in {@code plan}) instead of the table's fixed three or four steps, and the cap is
     *  also what the junction search's own band is paid out of — so a longer passage under a 12 s cap
     *  did not just shorten the mix, it <b>narrowed the search onto the outgoing track's own fade</b>
     *  and made the planner refuse pairs it used to fuse (measured: {@code
     *  aPassageInsideAFadeIsNotUsedIfAnEarlierLineHasBody} went from choosing the earlier line with
     *  body to refusing outright, because the only line left in the band was the fade). The two
     *  quantities are one budget, so the budget is what moved.
     *
     *  <p>What it costs: the audio through the model grows linearly with the window (a 17 s passage is
     *  ~2.6x the separation of a 6.5 s one, i.e. on the order of +20 to +50 s of render CPU), and the
     *  render runs minutes ahead of its boundary, which is why the relative family has been allowed
     *  this much since round 5. The device log is where the real number belongs. */
    public static final long FUSION_TAIL_MAX_MS = 20_000L;

    /**
     * How much of the cap's increase {@link #FUSION_TAIL_MAX_MS} bought goes to the search's band
     * rather than to the mix, ms (round 18).
     *
     * <p>The passage and the junction search's reach are paid for out of one budget ({@code cap}):
     * the take's own span comes off it, and what is left is the band the junction is chosen from
     * ({@code budget = cap − sourceSpan − 2·slack}). The design has always let the <em>passage</em>
     * give when the band is too small to hold a line ({@code shortenedForBand}), which is the
     * priority this constant keeps: a mix is only worth having if there is somewhere good to cut it.
     *
     * <p>⚠️ The growth is bounded by {@code cap − SEARCH_BAND_MS} for that reason, and the number was
     * measured rather than chosen. With the whole increase reserved (8 000) a unison pair's mix did
     * not grow at all — {@code maxStepsFor} then reports the old budget back, and the canonical
     * fixture came up at its pre-round-18 four steps — while with none reserved the growth ate the
     * band down to where it started and a fixture refused a pair it used to fuse. Half the increase is
     * the split that serves both: on the pair the listener reported ({@code Fortnight -> Passionfruit},
     * a 112 BPM incoming, a 24 000 ms ramp) the mix goes from 6.5 s to 12.9 s. */
    public static final long SEARCH_BAND_MS = 4_000L;

    /** The same cap for a <b>relative</b> pair (round 5, revised), ms: 20 000.
     *
     *  <p>Why it is not the 12 s the unison case uses: a relative pair's step is `p` bars of the
     *  incoming's grid, so exactly the pairs this family was widened for — the 2:1 / 1:2 octaves,
     *  the largest family in the user's library after the unison — have steps of 3.75 s and a
     *  four-step recede that needs about 15 s of the outgoing's own file. Measured on the harness's
     *  own grids: {@code owa -> paradise} (476.6 vs 937.6 ms beats, a 1:2 relation 1.67% out) has a
     *  step of 3750.2 ms and a stretch of 0.9836, so the passage A's rows need is
     *  {@code ceil((4*3750.2 + 80)/0.9836) = 15 333 ms} of A's file, and the separation window with
     *  {@link #A_TAIL_SLACK_MS} of lead on either side is <b>17 333 ms</b> — the 12 000 ms cap
     *  refused it on the cost clause alone. That bound exists to stop a render becoming a minute of
     *  separation, not to exclude the largest family of pairs there is: the render runs minutes ahead
     *  of its boundary, so the window is affordable. What it costs, in render seconds: the audio
     *  through the model for that pair grows from 12 000 ms to 17 333 ms (<b>1.44x</b>), and the model
     *  runs at roughly 1.5-3x real time on the reference device (12 s of window cost 24-77 s of CPU
     *  in the harness), i.e. on the order of <b>+8 to +16 s</b> of render CPU. The unison case keeps
     *  12 000 exactly (and so does a slam, which has no relation to justify the space). */
    public static final long RELATIVE_TAIL_MAX_MS = 20_000L;

    /** Which cap a plan is measured against: {@link #RELATIVE_TAIL_MAX_MS} for a relative
     *  (non-unison) relation, {@link #FUSION_TAIL_MAX_MS} otherwise.
     *
     *  <p>⚠️ Round 18: the two numbers are the same (20 000) since the passage follows the window the
     *  listener set, so this branch no longer changes the answer — it is kept because the two
     *  <em>families</em> are still different quantities (a relative pair's step is {@code p} bars
     *  where a unison pair's is one), and the log lines that name the cap name the family with it. */
    public static long tailMaxMs(Relation relation) {
        return relation != null && !relation.locked() ? RELATIVE_TAIL_MAX_MS : FUSION_TAIL_MAX_MS;
    }

    /**
     * The version of the fusion's own arithmetic, and it is part of every DJ edit's cache key.
     *
     * <p>A finished edit on disk is played rather than re-rendered (that is the contract the whole
     * cache exists for), so without this a pair refused — or fused — under one rule set would
     * <b>shadow</b> the same pair under the next one: the boundary would keep playing the old file
     * and never ask again. That is not hypothetical. On the device the pair {@code AGUDO -> Lose My
     * Mind} was refused by the entry search while the renderer handed the incoming's bar lines over
     * in the wrong unit (seconds for milliseconds); a plain edit was written; the beat probe then
     * healed the incoming's grid, and the boundary kept playing that plain file — the pair only
     * fused-attempted after the file was deleted by hand. The version is in the key rather than in
     * the file name's suffix because it has to be invisible to a lookup:
     * {@code DjEditBaseName(key#r<version>)} finds nothing where the old file sits, the render runs
     * once more, and the cache's own cap evicts the old name.
     *
     * <p>Bump it whenever a change here alters what a render produces (not for a comment): the cost
     * is one re-render per edit, in the pre-cache lane, where there are minutes of margin.
     *
     * <p>⚠️ <b>THE STANDING RULE: any change to the planner's arithmetic, to the acceptance's
     * clauses, or to the constants that feed them is a bump of this number.</b> Not just the
     * "important" changes — this is a checklist item, because the failure mode is silent and has
     * now cost three device runs. A user's word for it:
     *
     * <ul>
     *   <li><b>2 → 3</b> — the wait's retry loop made "the acceptance refused this pair" a
     *       measurement of one bounded rule set rather than a verdict on the pair, so the
     *       {@code -x5} files written before the loop had to be retired. The device's
     *       {@code 942288643-v18340-x5-r2.m4a} hid a fusion until it was deleted by hand.</li>
     *   <li><b>3 → 4</b> — the SLAM path's three fixes (its instants from the slam instead of
     *       the fusion shape, an empty span reading as unknown rather than digital silence, and the
     *       splice's window grown by {@code CUT_MS}) changed what a render produces for every
     *       unrelated-tempo pair. The renderer was changed and the version was not, so the device's
     *       {@code 1153167801-b3939-v16203-x5-r3.m4a} — a plain refusal written by the pre-slam code
     *       and stamped with the then-current version — was played as today's edit and
     *       <b>the render for the asked direction never ran at all</b>.</li>
     *   <li><b>4 → 5</b> — round 20 changed the <em>gate</em> every render writes: the incoming
     *       track's voice is held out for the stretch of the blend in which the outgoing track can
     *       still be heard ({@link DjEdit#vocalOutMs}, 75.2% of a DJ-shape blend) rather than for
     *       the whole blend, so every file written before it carries a {@code -v} that is ~4 s late
     *       on a 17 s setting. The version is in every edit's key — not only a fusion's — so this
     *       bump is what makes those files re-render instead of being played with the old window:
     *       a listener's 过渡时长 would otherwise look like it was still being over-cut on exactly
     *       the pairs they had already heard.</li>
     *   <li><b>5 → 6</b> — round 6's third pass: the intro skip's landing is content-driven
     *       ({@link #VOCAL_SKIP_MIN_BARS} bars of the incoming track's own grid instead of a flat
     *       8 000 ms, and the window the incoming's voice is out for as its ceiling), so pairs whose
     *       incoming voice comes in after one or two bars now start the deck later than they did,
     *       and pairs whose voice comes in so late that a landing cannot pay for the passage now
     *       KEEP their fusion instead of losing it to a refused landing. Both are audible
     *       differences; a file written before this bump is played with the old entry.</li>
     *   <li><b>8 → 9</b> — round 30 changed the <em>gate</em> again, and this time in the direction
     *       the listener asked for: the incoming's voice arrives {@link #VOICE_GATE_LEAD_BARS} bar
     *       of the outgoing's grid before the departure it had been sitting on, floored where the
     *       file's own gesture has taken the outgoing's rows {@link #VOICE_GATE_FLOOR_DB} under
     *       unity, and the departure itself is read at {@link #OUTGOING_EXIT_DB} = 12 dB rather
     *       than 20. Every file written before it carries a {@code -v} that is the old, later gate
     *       (and an {@code -e} chosen to match it), so a listener would hear the very complaint
     *       「歌词要稍微早一点」 again on exactly the pairs they had already heard.</li>
     *   <li><b>9 → 10</b> — the SLAM path's own length, and this one is a <em>listening</em> rather
     *       than a defect: {@link #SLAM_STEPS} 1 → 3, so the outgoing's rows play for three bars of
     *       the incoming's own grid instead of one before everything changes hands. Every
     *       unrelated-tempo pair's edit is a different file (a 3-bar passage, 6 212 ms on the pair
     *       that was listened to), so a file written before this bump would be played as today's
     *       slam and the listener would hear the length they rejected — the one-bar hand-over.</li>
     *   <li><b>10 → 11</b> — {@link #vocalStartMs} reads the incoming's voice against the vocal
     *       row's OWN <b>body</b> ({@link #VOICE_ARRIVAL_DB}) instead of against the absolute floor
     *       the renderer hands in, so a separated row that carries a faint bleed from its first
     *       sample no longer reports its voice as already singing in the first window. On the pair
     *       that was listened to ({@code unhappy}, a 12 s blend: the row's body −13.6, its bleed
     *       −25.4, its voice from 17 s) the measurement moves from 0 ms to 17 000 ms, and with it
     *       the landing the plan derives — the last bar line the window can afford instead of the
     *       track's own beginning, which is the eleven seconds of voice-less intro the listener
     *       heard as 「不像过渡」. Every file written before this bump carries the incoming deck at
     *       the old measurement and is played at it — the version is in every edit's key, not only a
     *       fusion's, so this is what makes those files re-render rather than keep playing the entry
     *       this round moved.</li>
     *   <li><b>11 → 12</b> — the material {@link #vocalStartMs} is fed now reaches a late arrival.
     *       Round 32's rule change above was necessary but not sufficient: the renderer separated a
     *       head of {@code removalMs + returnSpanMs} — 16 044 ms on the listened pair — and that
     *       track's voice arrives at <b>16–18 s</b> of its own file, so the row the rule measured
     *       held the bleed and the eleven-second hole and nothing else. Its body
     *       ({@code bodyLevelDb}, the 90th-percentile window) was therefore the bleed, and the
     *       answer stayed <b>0</b>: the landing the listener picked was still unreachable.
     *       {@link #arrivalProbeMs} lengthens the separated-and-measured span to
     *       {@link #ARRIVAL_PROBE_MS} (19 000 ms — measured, see that constant: 18 000 does not
     *       clear it, because the arrival has to fall inside the probe's <em>last tenth</em> for the
     *       body to be the voice rather than the bleed). The file's own length and encode are
     *       unchanged. Every edit written before this bump was planned from the truncated row, so
     *       each one has to be planned again — including the ones written minutes ago under 11.</li>
     *   <li><b>12 → 13</b> — the landing's own <b>window</b> is bounded and must not sit in the
     *       incoming's own quiet intro, and this is the rule the user asked for after listening to
     *       round 32's two landings: 「落点寻找不要太靠后或者太靠前，尽量在前奏找完，一开始就是词的那就过
     *       几个小节找，总之不要把小半个歌跳过」, and then, of the deeper one:「上一轮的 34800 很完美」.
     *       Rounds 25–32 put the landing on the coupling — {@code firstVocal - goneFile} — and bounded
     *       it only by what the window could pay for; but the {@code firstVocalMs} that number is
     *       measured from is the <b>sustained</b> arrival (16–18 s on the pair the user listens to, see
     *       the 11 → 12 entry above), so a long track's ceiling left the deck starting deep in the song
     *       with the incoming's own lines held out over the whole stretch — the 「跳过了很多词」 the user
     *       heard, in the other direction. Three clauses now, in this order:
     *
     *       <ul>
     *         <li>the landing is clamped into <b>two bars</b> of the incoming's own grid when the track
     *         is already singing at its own content start ({@link #ENTRY_WORDS_BARS}, tested over
     *         {@link #ENTRY_AT_ONCE_BARS} bars) — 「一开始就是词的那就过几个小节找」;</li>
     *         <li>and it may never start more than {@link #ENTRY_MAX_MS} past that content start — the
     *         "don't skip half the song" guard, which the user's own 过渡时长 still bounds from
     *         below (the ceiling is unchanged, so no pair can plan what its blend cannot pay for);</li>
     *         <li>and it may not sit where the incoming's own groove is silent ({@link
     *         Input#incomingGroove}): that is the clause the 34 800 ms landing the user called perfect
     *         is made of. Measured on that pair, the incoming's drums and low end are −70…−87 dBFS for
     *         its first 19 s, so the shallow landing gave the passage a second deck that was not
     *         playing — the fusion's second half was one deck fading out.</li>
     *       </ul>
     *
     *       <b>The fusion itself is untouched</b> — the gesture's shape, the gate, the departure and
     *       the stretch are the same numbers; only which bar line the deck starts on moved. The
     *       floor gives way to the cap so that no pair which fused before refuses now. Every edit
     *       written before this bump is planned again (the version is in the edit's own key), and the
     *       renderer's separated head already reaches as far as the landing can
     *       ({@code headMs = removalMs + span}), so a deeper landing costs no extra separation.</li>
     *   <li><b>13 → 16</b> — <b>the passage is as long as the pair can afford, not the table's fixed
     *       three or four steps.</b> The user, hearing a boundary: 「我认为你只需要让混音段长一点他可能就能
     *       自然过渡到下一首歌了，目前感觉是过渡了一半」. Measured on their own pair, straight out of the
     *       device log ({@code Fortnight -> Passionfruit}, a SLAM, {@code -e4272-j204141-f10778}): the
     *       fused passage is {@code fusionEnd − entry} = <b>6 506 ms inside a ramp they had set to
     *       24 000 ms</b> — three bars of a 112 BPM incoming, because the length never followed that
     *       setting at all. {@link #SLAM_STEPS} is now a FLOOR and the passage grows into the window
     *       the pair already pays for ({@link #maxStepsFor}: the smaller of the separation cap and the
     *       stretch the incoming's own vocals are held out for), one step at a time against
     *       {@link #bandHasLine}, with {@link #FUSION_TAIL_MAX_MS} raised 12 000 → 20 000 and
     *       {@link #SEARCH_BAND_MS} reserving half of that increase for the junction search's own
     *       band. <b>Only the length moves</b>: the recede's shape, the landing's rules and the
     *       deck-level curves are untouched, and a SLAM keeps its 80 ms splice — the user ruled a
     *       recede out for it (「退场可能导致淡入淡出」).
     *
     *       <p>⚠️ Two things this round knowingly trades, both measured and both recorded here so
     *       they can be re-derived with the length: the passage and the search's band are one budget
     *       (a growth that ignores {@link #SEARCH_BAND_MS} makes the planner refuse pairs it used to
     *       fuse), and the landing's ceiling is {@code contentStart + removalMs − window}, so a longer
     *       passage pushes the deck back toward the incoming's own start — the fixture
     *       {@code theLandingIsTheOneThatPutsTheVoiceWhereTheOutgoingLeft} reads 0 where it read
     *       4 000, which is round 13's 「不要把小半个歌跳过」 in the opposite direction. This build is
     *       shipped to be LISTENED to, with its fixture expectations not yet retuned (15 of 48 in
     *       {@code StemFusionTest} pin the old, fixed length).</li>
     *   <li><b>16 → 17</b> — <b>the generated bed is asked for a TRANSITION instead of a flat pad, and
     *       it is 12 dB under the passage instead of 18.</b> Both are the user's own report
     *       (「融合段很明显是生成的那段出了问题……与后面的歌曲压根没有关联，能不能让生成段更符合过渡逻辑」) and both
     *       are in the request rather than in the mixing: {@code AceStepBed.CAPTION} used to ask for "a
     *       sustained, even harmonic pad … no lead line, no vocals, no beat of its own", i.e. for exactly
     *       the texture that belongs to neither track, and {@code StemBed.BED_LEVEL_DB} was 18 — the
     *       value 12 had been asked for by ear in the round that was later rolled back. The caption now
     *       has a direction (it begins in the first track's key and mood and its later part already
     *       belongs to the second, so the join reads as the second track beginning) while KEEPING the
     *       continuity clauses that fixed the sparse-junk failure the pad caption replaced, and it still
     *       forbids a drum beat of its own (the passage already carries the outgoing's kit). ⚠️ The two
     *       ride in one build with {@code 13 → 16} above, so a listener cannot attribute the difference
     *       between them; they are both "make the generated segment work", and the bed's level is the
     *       one with a measured cost (the sum now reaches the limiter on a dense passage — the
     *       {@code aBedThatWouldPushTheSumOverFullScale} fixture had to be given 6 dB more room).</li>
     *   <li><b>17 → 18</b> — <b>the bed's caption says what the segment is FOR, after the second
     *       listening round.</b> Round 18's wording asked the first part to "belong to the first track:
     *       the same key, the same mood", and the user heard exactly that — a continuation. Their
     *       correction is the requirement in one sentence: 「你不要让她延续前一首歌的歌曲做续写，而是做收尾
     *       然后变到第二首」. The caption now asks for the first track's own <em>ending</em> (resolving and
     *       winding down, "not more of the same music and not a continuation of it"), then the turn, then
     *       the <em>second</em> track's beginning in its key and harmony; the continuity clauses (no
     *       silence, no thinning at the join) and "no drum beat of its own" are kept from both earlier
     *       attempts. Only the request text changed — the level, the geometry, the gates and everything
     *       upstream are untouched — but the take the model returns is different audio, so the cached
     *       edits are re-rendered once.</li>
     *   <li><b>18 → 19</b> — <b>the bed's caption asks for the two tracks' TUNES fused, not for a pad
     *       with no melody.</b> The user's requirement, in their own words: 「不要只是淡入淡出式的结合，
     *       生成物要是两首歌的曲调融合在一起丝滑过渡而不是中间接一个由小声到大声淡入」. Every earlier
     *       version of the caption carried "it is a pad and not a melody … no melody of its own", which
     *       asked the model for exactly the level-ramped material that is being objected to; the melody
     *       clause is gone and the ask is a melodic hand-over (the first track's tune, then the second's
     *       over it, then only the second's). Kept: no fade-in from quiet and no swell, continuous and
     *       even, instrumental (「过渡里尽量没人声」), and no drum beat of its own.</li>
     *   <li><b>19 → 20</b> — <b>the model's job is a percussion layer and nothing else.</b> The user's
     *       requirement: 「现在不让ai生成任何旋律了只打鼓点做背景帮助融合」. Every earlier wording asked for
     *       music — a pad, a transition, a fusion of the two tunes — and the melodic ones were rejected by
     *       ear outright; the caption now asks for a steady drum groove as a background under the passage
     *       and forbids melody, harmony, bass and pads explicitly. The app's own acceptance is what makes
     *       "steady" part of the requirement rather than a preference: measured on the listener's pair, a
     *       steady groove took 100.0% occupancy and a "minimal drum backing" 57.7% (refused, "a few hits
     *       over near-silence"), so the gate is left alone and the wording carries the load.</li>
     *   <li><b>20 → 21</b> — <b>the passage's growth is bounded by the LANDING, and the voice comes back
     *       the moment the fusion is over.</b> The listener, in one breath: 「融合段可以长一点，尽量用背景音融，
     *       融完立马接人声不要耽搁」 — and one round earlier 「尽量找到最末尾最开头这种淡入淡出的效果」 (the
     *       landing must not be pushed to the incoming's own start). The growth now stops at whichever
     *       comes first, the junction search's band or the landing's own floor: {@code entry} clamps the
     *       landing to {@code removalMs - windowMs}, so growing the window lowers that ceiling until it
     *       falls under {@link #ENTRY_WORDS_BARS} bars — which is the fixture that read 0 instead of
     *       4 000 before this clause. And the vocal gate is clamped to the passage's own end
     *       ({@code landing + windowMs}), so 「融完立马接人声」 is a property of the arithmetic rather than a
     *       hope about where the curve snaps.</li>
     *   <li><b>21 → 22</b> — <b>the incoming's backing starts rising two steps before the hold ends
     *       instead of one.</b> The listener: 「现在让第二首歌进入的声音逐渐变大，由小变大时不要小声太久，
     *       尽快做到背景音与前一首融合，期间请你不要放人声」. The rise's own end is untouched (it is still the
     *       hold's end, where the outgoing begins to recede, and every level the table writes around
     *       it is unchanged), so this is purely "one bar less of nothing": {@link
     *       #B_ARRIVAL_LEAD_STEPS} decides it, and on the grown 12 s passages the second track is
     *       now audible from the third bar instead of only the last. The file's audio changes, so the
     *       version is bumped and cached edits are re-rendered once. The other half of that same
     *       sentence — 「期间请你不要放人声」 — is NOT in this file: the incoming's voice is gated by the
     *       table (see {@link Plan#voiceGateMs}) and always was, while the OUTGOING track's own voice
     *       is never carried here (see {@code carriedOf}) and comes from its live deck, which this
     *       planner cannot touch. See AI_HANDOFF's round 28.</li>
     *   <li><b>23 → 24</b> — not this file's arithmetic: the cloud bed's own envelope grew to two
     *       bars at each end ({@code StemBed.BED_FADE_BARS}), the listener's 「ai 垫层的收尾要淡出，最好再
     *       有个淡入」. Bumped here only because the written file changes.</li>
     *   <li><b>24 → 25</b> — <b>the junction search offers its whole band, and the render picks the
     *       fusion that scores best.</b> The listener's instruction, verbatim: 「重新寻找最佳落点，多取
     *       几个进行本地快速融合，融合出来进行评分，取不同融合中的最高分应用」. {@link #plan(Input, int)}
     *       hands out the band's lines best-preference-first (index 0 is bit-for-bit the old answer),
     *       the renderer renders a fast fusion per candidate (make-up 0, a report that stops at the
     *       junction's step — the round-33 probe), scores each on the step plus whether the incoming's
     *       rows arrive under the table (the clause that refused the listener's Shape of You pair
     *       four times in one render), and spends the full report on the winner only. The acceptance
     *       itself is untouched: it still judges the rendered passage that gets written.</li>
     *   <li><b>22 → 23</b> — <b>21 → 22 was an extreme and is undone here.</b> The listener: 「你走了个
     *       极端，出曲现在太慢了……现在有点长过头了，有点抢调」. Two steps of equal-power rise put the
     *       incoming track at −3 dB a whole step before the hand-over, so both backings — in their own
     *       keys — were loudly present for twice as long: the outgoing track sounded like it hung
     *       around (「出曲太慢」) and the two tonalities fought (「抢调」). {@link #B_ARRIVAL_LEAD_STEPS}
     *       is back to one step, which is round 21's own file; the version is bumped rather than
     *       restored because a file named {@code -r22} may be either shape. The listener's original
     *       「不要小声太久」 is NOT answered by any single-step number — see that constant's own note:
     *       it needs a two-stage arrival (a bed, then unity), which is the next round's change.</li>
     * </ul>
     *
     * <p>The price is one re-render per pair, once, in the pre-lane where there are minutes of
     * margin — which is a great deal cheaper than a run spent on an edit the current rules would
     * not have written. A version bump is how the old file stops being found at all (the version is
     * part of the edit's cache key, see {@code PlayerController.djEditKey}), and
     * {@code PlayerController.staleGridRefusal} treats one that is found anyway as stale by its own
     * name.
     */
    public static final int RULE_VERSION = 25;

    /**
     * How many steps of the gesture the pair can afford in all: {@code steps} with
     * {@code ceil((steps*stepMs + CUT)/stretch) + 2*slack <= cap} and
     * {@code contentStart + steps*stepMs <= removalMs} (the fusion has to stay inside the window the
     * incoming's vocals are out for — {@link #plan}'s own clause, asked here in the same terms so
     * that the wait for the incoming's rows cannot push the passage past it).
     */
    private static int maxStepsFor(double stepMs, double stretch, long cap, long removalMs,
                                   long contentStartMs) {
        double ratio = stretch > 0d ? stretch : 1d;
        long byCap = (long) Math.floor(((cap - 2L * A_TAIL_SLACK_MS) * ratio - CUT_MS) / stepMs);
        long byVocals = Math.floorDiv(removalMs - contentStartMs,
                Math.max(1L, Math.round(stepMs)));
        return (int) Math.max(1L, Math.min(byCap, byVocals));
    }

    /**
     * Whether the junction search would have a candidate at all with a passage of {@code steps}:
     * the band {@link #bandFor} leaves, split the way {@link #plan} splits it, with at least one of
     * the outgoing's own bar lines inside it. Cheap (a scan of the grid), and it is the question
     * that decides whether the gesture has to give — see {@code plan}'s own note.
     */
    private static boolean bandHasLine(Input in, long target, long cap, int steps, double stepMs,
                                       double stretch) {
        long budget = bandFor(steps, stepMs, stretch, cap);
        if (budget < 0L || in.aBarLinesMs == null) return false;
        long forward = Math.min(Math.round(JUNCTION_SEARCH_BARS * in.aBeatMs * BEATS_PER_BAR),
                Math.max(0L, budget / 2L));
        long back = Math.min(QUIET_SEARCH_MAX_MS, Math.max(0L, budget - forward));
        double from = target - back;
        double to = target + forward;
        for (double line : in.aBarLinesMs) {
            if (line >= from && line <= to) return true;
        }
        return false;
    }

    /**
     * The room the junction search has left with a passage of {@code steps} steps, ms:
     * {@code cap - sourceSpan - 2*slack}, which is what {@link #plan} splits between the search's
     * backward and forward reach. The same arithmetic {@link #maxStepsFor} inverts, and it is the
     * number the gesture has to keep at least one of the outgoing's own bars wide — see
     * {@code plan}'s own note.
     */
    private static long bandFor(int steps, double stepMs, double stretch, long cap) {
        long sourceSpan = (long) Math.ceil((steps * stepMs + CUT_MS)
                / (stretch > 0d ? stretch : 1d));
        return cap - sourceSpan - 2L * A_TAIL_SLACK_MS;
    }

    /**
     * The shape one pair's own step leaves room for: {@code {holdSteps, lowEndFadeSteps}} of the
     * round-6 gesture, ms of the outgoing's file being the budget.
     *
     * <p>The preferred shape is {@link #A_HOLD_STEPS} of hold at unity and {@link
     * #A_LOW_END_FADE_STEPS} of low end fade, which is the 4-bar passage with a full step of
     * coexistence the design asks for. A pair whose step is long enough that the whole shape does
     * not fit {@code cap} gets ONE step of hold — still a recede, and the coexistence survives in
     * time even when it loses a step — and if even that does not fit, the low end's fade shortens
     * to one step as well. Never zero: the gesture is a fade, not a refusal.
     *
     * <p>Both {@link #plan} and {@link #refusal} decide with this one call, because they were
     * written apart: the clause kept the full shape's arithmetic after the planner learned to
     * shorten, and then refused pairs (the 4 s-bar family) the planner fused.
     */
    private static int[] shapeFor(double stepMs, double stretch, long cap) {
        double ratio = stretch > 0d ? stretch : 1d;
        int hold = A_HOLD_STEPS;
        int lowEnd = A_LOW_END_FADE_STEPS;
        if ((long) Math.ceil((hold + lowEnd) * stepMs / ratio) + 2L * A_TAIL_SLACK_MS > cap) {
            hold = 1;
            if ((long) Math.ceil((hold + lowEnd) * stepMs / ratio) + 2L * A_TAIL_SLACK_MS > cap) {
                lowEnd = 1;
            }
        }
        return new int[]{hold, lowEnd};
    }

    /** The worst tempo error the two grids may have and still be fused, as a fraction of the
     *  incoming's beat <em>as heard</em>: {@code |aPeriod - bPeriod/speed| / (bPeriod/speed)}.
     *
     *  <p>This is the clause that makes "cut on the bar line" mean something. Without the lock the
     *  two grids slide against each other by one beat-period error per beat, so every bar-line cut
     *  lands off the beat — measured on the prototype: a 2:1 pair at a 2% error slides 63 ms by
     *  {@code swapMs} and 188 ms by {@code fusionEndMs}, which is a whole off-beat edit. 2% is the
     *  number the measurement supports; a pair that does not lock gets <b>no fusion</b> and today's
     *  edit, however tempting its material looks. */
    public static final double LOCK_TOLERANCE = 0.02d;

    /** The incoming's own backing outside the fusion window: unity. The edit is the master there,
     *  so the bed it plays over is the track as it is. */
    public static final double BED_DB = 0d;

    /** The outgoing's melodic row inside the fusion: -9 dB. It is the one carried row that is not
     *  an element of the groove, so it sits under the two backings instead of competing with
     *  them — and it is the first thing dropped if it turns out to be the outgoing's voice.
     *
     *  <p>⚠️ -9 and not -12 (round 3). The listening report is what put it back: on the pair the
     *  user heard, the junction's own bar is a pad-only breakdown of the outgoing track, so the
     *  melodic row is the <em>only</em> thing the fusion carries there, and taking another 3 dB off
     *  it put ~3 dB more into the junction's step — half of the 4 dB of that step which the table
     *  itself owned. The junction search below is what stops that situation arising at all (it
     *  prefers a bar line where the groove is playing); this is what the level should be when the
     *  melodic row does have to carry a passage on its own. */
    public static final double A_OTHER_DB = -9d;

    /**
     * The most the fusion's outgoing-side material may be lifted, dB (round 3).
     *
     * <p><b>Why a make-up gain exists at all.</b> The fusion deliberately removes the outgoing
     * track's <em>voice</em> — that is the user's rule for the whole transition (「避免过渡时出现人声」)
     * — and on a passage where the voice was the energy, the instrumental that is left is quieter
     * than the level the listener was just hearing. Removing the voice must not be heard as the
     * <em>music</em> dropping: a step of several dB at a bar line is exactly the 卡顿 the user
     * reported, and it is the level the listener is on, not the material, that the ear notices.
     * So the renderer measures the step it already reports ({@link #STEP_WINDOW_MS} of the fusion
     * against {@link #STEP_WINDOW_MS} of the outgoing's own master) and lifts <b>the outgoing's
     * carried rows only</b> by that difference — never the incoming's rows, and never anything
     * outside the fusion window — before the head's limiter, so what the limiter holds is the
     * passage at the level it is actually played at.
     *
     * <p>Bounded because the number is measured on material, not designed: an unbounded make-up
     * would let a fusion whose junction is nearly silent be amplified into a passage that is
     * nothing like either track. {@link #makeupDb} clamps it, and the acceptance then re-checks
     * the step clause on the result as it always did — a fusion whose junction needs more than
     * this is refused rather than played as a drop.
     */
    public static final double MAKEUP_MAX_DB = 8d;

    /**
     * The make-up gain one measured junction step asks for, dB: the reduction the step measured,
     * and nothing when the fusion is already at (or above) the outgoing's own level.
     *
     * <p>Only ever a lift: a fusion that arrives <em>louder</em> than the level the listener was
     * at is a swell, not a drop, and the answer to it is the level table (or the limiter), not a
     * reciprocal gain that would pull the outgoing's own material down. Clamped to
     * {@link #MAKEUP_MAX_DB}.
     *
     * @param stepDb the measured step ({@link Report#junctionStepDb}), or NaN when it could not be
     *               measured — which asks for no make-up, because there is nothing to answer
     */
    public static double makeupDb(double stepDb) {
        if (Double.isNaN(stepDb) || stepDb >= 0d) return 0d;
        return Math.min(MAKEUP_MAX_DB, -stepDb);
    }

    /** The outgoing's carried rows with a uniform gain on them — the make-up gain, applied before
     *  the head is rendered (and therefore before its limiter). Every row in {@code carried} is
     *  the outgoing's (see {@link #carry}), so a uniform gain here is A's rows and only A's. */
    public static float[][][] applyMakeup(float[][][] carried, double makeupDb) {
        if (carried == null || makeupDb == 0d) return carried;
        double gain = linear(makeupDb);
        float[][][] out = new float[carried.length][][];
        for (int row = 0; row < carried.length; row++) {
            if (carried[row] == null) continue;
            out[row] = new float[carried[row].length][];
            for (int ch = 0; ch < carried[row].length; ch++) {
                if (carried[row][ch] == null) continue;
                out[row][ch] = new float[carried[row][ch].length];
                for (int i = 0; i < carried[row][ch].length; i++) {
                    out[row][ch][i] = (float) (gain * carried[row][ch][i]);
                }
            }
        }
        return out;
    }

    /** The incoming's own melodic bed inside the fusion: unity, which is where it arrives.
     *  Its drums and bass are silent until their own swaps, so the bed is what the incoming
     *  contributes during the first two bars — and it is the thing that keeps the listener's sense
     *  of level continuous while the outgoing track's voice leaves. */
    public static final double B_BED_DB = 0d;
    
    /** How long the incoming's bed takes to arrive, in bars of the incoming's own grid: one —
     *  the bar the outgoing's drums leave on. Equal-power (the arriving side of a splice), from
     *  {@link Plan#entryMs} to unity. */
    public static final int BED_FADE_BARS = 1;

    /** The window the junction's step is measured over, ms, and the most it may be, dB.
     *
     *  <p>⚠️ <b>500 ms, and it no longer contains the deck-level change-over.</b> That change-over
     *  has grown twice since this window was chosen: it was an 80 ms splice, then 300 ms (round 3,
     *  {@code FadeCurve.FUSION}), and it is now {@code FadeCurve.JUNCTION_XFADE_MS = 2000} — two
     *  seconds of equal-gain hand-over between the outgoing deck and this file (`1ae22e9`). No
     *  500 ms window can contain that, and the two numbers are held together from the other side
     *  instead: {@link #A_HOLD_STEPS} keeps the file's outgoing rows at <b>unity</b> for two steps
     *  of the table, which is the stretch over which the boundary is fading the outgoing deck out —
     *  the file plays A's own material at full level through the whole hand-over, so the deck's own
     *  fade is the only one the listener hears across it and nothing is doubled. (Degenerate case
     *  worth knowing: a bar of about 2.8 s or more makes two steps ≈ the whole window, so on such a
     *  pair the hold is most of the passage and the recede happens inside what is left — see the
     *  adaptive hold in {@link #plan}.)
     *
     *  <p>3.5 dB is what the measurement of the real prototype's junction leaves room for: a
     *  clamped-at-the-bus head measured −3.06 dB there (and clipped 1352 samples), a statically
     *  divided one −6.80 dB, so the limiter has to land inside 3.5 and the clause is what refuses a
     *  pairing it cannot hold. */
    public static final long STEP_WINDOW_MS = 500L;
    public static final double JUNCTION_STEP_MAX_DB = 3.5d;

    /** The window the "the carry is still there where the gesture takes over" clause is measured
     *  over, ms: the stretch of the outgoing's material immediately before {@code bassMs} (which
     *  round 6 made the instant the outgoing's rows <em>start</em> to recede, not the instant they
     *  are gone). A take that ended early (the round-18 defect the material window's derivation
     *  fixes) shows up as silence exactly here, where every other level clause is measured over a
     *  span long enough to average it away. The stretch of the fade after it is
     *  {@link #carriedBassFadeDb}'s own clause — see {@link #CARRY_FADE_TOLERANCE_DB}. */
    public static final long CARRY_END_WINDOW_MS = 500L;

    /** The RMS an equal-power fade leaves of its own material, dB: a cosine decline from unity to
     *  the floor over its span has {@code mean(cos^2) = 1/2}, so a row that fades its own material
     *  measures 3.01 dB under the source. Exact, which is what makes the fade's presence
     *  measurable — see {@link #CARRY_FADE_TOLERANCE_DB}. */
    public static final double FADE_RMS_DB = 3.0103d;

    /** How much further under that line the carried low end's own fade may sit and still be the
     *  material it claims, dB.
     *
     *  <p>Round 6's gesture fades A's rows instead of cutting them, which puts the round-18 defect
     *  (a take shorter than the gesture needs) somewhere the absolute clauses cannot see it: inside
     *  the fade the rendered row is falling by the table's own design, so measured against the
     *  source it reads as a shape rather than as a hole. Measured on real material the fade sits
     *  within about a decibel of {@link #FADE_RMS_DB} (it is analytic, and the low end's own level
     *  barely moves over four seconds), so the tolerance only has to cover the material's own
     *  variation between the two spans; 4 dB leaves it that room and still catches a take that ends
     *  inside the fade (which reads tens of dB under, not four). */
    public static final double CARRY_FADE_TOLERANCE_DB = 4d;

    /** Beats to a bar — the same assumption every bar-line use in this codebase makes. */
    public static final int BEATS_PER_BAR = DjEdit.BEATS_PER_BAR;

    /** How far the outgoing deck is cut back from the end of its file before the bar line is
     *  chosen: {@code aDur - CUT_BACK_MS - blendMs}. 250 ms is the boundary's own tail guard
     *  (the ramp has to finish inside the track), so the junction is the last bar line that can
     *  still carry a whole blend of tail behind it. */
    public static final long CUT_BACK_MS = 250L;

    /** The lead on either side of the junction target that the separated material carries, ms —
     *  the slack the take's own alignment wants: the junction is a bar line of the outgoing's own
     *  grid within ±1 bar of the target and cannot be known before that grid is measured, so the
     *  window a render separates has to be allowed to start before the target and to run past the
     *  passage's own end. 1000 ms is more than one beat of any grid the cost bound admits (a bar
     *  of at most 2.8 s has beats of at most 700 ms), so the junction's own bar is always inside
     *  the material — which is what the "is its voice quiet here" preference is asked about. */
    public static final long A_TAIL_SLACK_MS = 1_000L;

    /**
     * How much of the outgoing track's OWN file the carry needs, ms.
     *
     * <p>Derived from the gesture and not from a bar count: the table keeps A's material in the
     * file over {@code [entryMs, fusionEndMs + CUT_MS]} — the last row reaches the floor at
     * {@code fusionEndMs} and the tail that carries it is {@link #CUT_MS} long — which is
     * {@code FUSION_BARS} steps of the incoming's file, i.e. {@code FUSION_BARS*barStep/stretch} ms
     * of the outgoing's own (the deck plays the file {@code speed} times as fast, so material
     * stretched by {@code speed} comes out at the outgoing's tempo). Rounding up so the tail is
     * covered.
     *
     * <p>⚠️ The spec used to say {@code 3*aBar}, on the assumption that the lock holds. On a pair
     * whose grids are not in a 1:1 relation that is not the same number: measured on real material
     * it was 1.8 s <em>short</em> on one pairing — A's bed would have died on a hard cut the table
     * never names, which is exactly the unplanned edit this transition may not have — and 2 s too
     * generous on another. {@code bassMs} is the instant that matters, so it is what this is
     * computed from; {@link #materialWindowMs} adds the lead, and the acceptance asserts the
     * carried rows are still there right up to {@code bassMs}.
     */
    public static long sourceSpanMs(double bBeatMs, double speed) {
        return sourceSpanFor(bBeatMs * BEATS_PER_BAR, speed);
    }

    /** The material one take needs from the outgoing's own file, ms: the whole gesture ({@link
     *  #FUSION_BARS} steps of the table, {@code barStepMs} each) plus the last tail, divided by the
     *  ratio the material is read at ({@code stretch}). For a relation 1 pair that is
     *  {@code (4*bBar + CUT)/speed} — round 18's own number, twice over, because round 6's recede
     *  lasts four steps where the splices took two — and for a relative one {@code barStepMs} is
     *  {@code p} bars of the incoming's and {@code stretch} is what makes the outgoing's bar exactly
     *  {@code p/q} of it.
     *
     *  <p>This is the <b>over-estimate</b>: a plan whose step is long enough shortens its own hold
     *  (see {@link #shapeFor}), and {@link Plan#sourceSpanMs} is the number the take really needs.
     *  Callers that size a decode rather than a separation (see {@link #probeWindow}) want this one,
     *  which is why it is the full shape rather than whatever the cap would allow. */
    public static long sourceSpanFor(double barStepMs, double stretch) {
        if (!(barStepMs > 0d)) return 0L;
        double ratio = stretch > 0d ? stretch : 1d;
        return (long) Math.ceil((FUSION_BARS * barStepMs + CUT_MS) / ratio);
    }

    /**
     * The relation two grids are in, snapped to {@link #RELATIONS} within {@link
     * #RELATION_TOLERANCE}, or null when they are in none.
     *
     * <p>The unison case ({@code p = q = 1}) is round 18's: the deck's own ratio is the relation's
     * residual and the carry is stretched by it, so a pair whose grids hold keeps its sample-exact
     * carry and its {@link #LOCK_TOLERANCE} of 2%. A relative pair plays at the deck's ratio too
     * ({@link Relation#stretch} for it is the deck's own {@code speed}, which is 1.0 for a pair the
     * tempo lock does not touch) and its residual is absorbed by the carry's stretch instead.
     */
    public static Relation relationOf(double aBeatMs, double bBeatMs, double speed) {
        if (!(aBeatMs > 0d) || !(bBeatMs > 0d)) return null;
        double ratio = aBeatMs / bBeatMs;
        Relation best = null;
        for (int[] relation : RELATIONS) {
            int p = relation[0];
            int q = relation[1];
            double target = p / (double) q;
            double error = Math.abs(ratio - target) / target;
            if (best == null || error < best.error) best = new Relation(p, q, error, speed);
        }
        if (best == null || best.error > RELATION_TOLERANCE + 1e-12) return null;
        // A pair within LOCK_TOLERANCE of the unison is the pair round 18 fused, and it keeps that
        // case's bargain exactly (the deck's own ratio as the carry's stretch, LOCK_TOLERANCE's own
        // report); a pair outside it but inside RELATION_TOLERANCE is a relative whose residual the
        // stretch absorbs, and one outside every relation is a SLAM.
        return best;
    }

    /** A tempo relation the two grids are in: {@code p} bars of the incoming's grid are {@code q}
     *  bars of the outgoing's, so one step of the fusion's table is {@code p*bBar}. */
    public static final class Relation {
        public final int p;
        public final int q;
        /** How far the measured ratio sits from {@code p/q}, as a fraction. */
        public final double error;

        Relation(int p, int q, double error, double speed) {
            this.p = p;
            this.q = q;
            this.error = error;
            this.speed = speed > 0d ? speed : 1d;
        }

        private final double speed;

        /** Whether this is the unison relation as round 18 knew it: the same bar count AND the
         *  grids inside {@link #LOCK_TOLERANCE}. Only this case keeps the deck's own ratio as its
         *  carry's stretch, which is what makes it bit-for-bit round 18's; a unison pair further out
         *  than that is a relative whose stretch absorbs the difference like any other. */
        public boolean locked() {
            return p == q && error <= LOCK_TOLERANCE + 1e-12;
        }

        /** How much of the incoming's file one step of the table is, ms. */
        public double barStepMs(double bBeatMs) {
            return p * bBeatMs * BEATS_PER_BAR;
        }

        /** The ratio the outgoing's carried material is read at: {@code p} bars of the incoming's
         *  over {@code q} of the outgoing's. It is {@code 1} by construction when the two bars are
         *  equal, and for a relative pair it absorbs the relation's own residual ({@link #error}) so
         *  that the outgoing's bar boundaries land on the swap lines. */
        public double stretch(double aBeatMs, double bBeatMs) {
            double aBar = aBeatMs * BEATS_PER_BAR;
            double bBar = bBeatMs * BEATS_PER_BAR;
            if (!(aBar > 0d) || !(bBar > 0d)) return speed;
            double natural = barStepMs(bBeatMs) / (q * aBar);
            if (locked()) {
                // Round 18's bargain, exactly: the carry is pre-lengthened by the ratio the deck
                // itself plays the file at, so playback returns it to the outgoing's tempo and
                // pitch. (Taking the natural ratio here would nudge a unison pair's carried rows by
                // up to LOCK_TOLERANCE — 0.84% on the huai->owa pair — which round 18 does not.)
                return speed;
            }
            return natural > 0d ? natural : 1d;
        }

        /** One line for the log. */
        public String describe() {
            return String.format(Locale.US, "%d:%d (%.2f%% out of it, the carry stretched by the"
                    + " bar ratio)", p, q, error * 100d);
        }
    }

    /** The smallest window one render has to SEPARATE for a fusion, ms: {@link #sourceSpanMs}'s
     *  material plus {@link #A_TAIL_SLACK_MS} of lead on either side of the target, which is what
     *  the take's alignment needs. The plan's own window adds the junction search's band on top of
     *  it (see {@link #JUNCTION_SEARCH_BARS}) and is capped at {@link #FUSION_TAIL_MAX_MS}; the
     *  validity clause refuses a pair whose carry alone does not fit inside that cap, because no
     *  window could hold it however narrow the search. */
    public static long materialWindowMs(double bBeatMs, double speed) {
        return sourceSpanMs(bBeatMs, speed) + 2L * A_TAIL_SLACK_MS;
    }

    /** How far the two grids are apart, as a fraction of the incoming's beat <em>as heard</em>:
     *  {@code |aPeriod - bPeriod/speed| / (bPeriod/speed)}. 0 when the tempo lock is exact. */
    public static double lockError(double aBeatMs, double bBeatMs, double speed) {
        double heardB = bBeatMs / (speed > 0d ? speed : 1d);
        if (!(heardB > 0d)) return Double.MAX_VALUE;
        return Math.abs(aBeatMs - heardB) / heardB;
    }

    /** Whether the two grids are actually locked, and the bar lines will not slide against each
     *  other over the passage's three bars: {@link #LOCK_TOLERANCE}. */
    public static boolean locked(double aBeatMs, double bBeatMs, double speed) {
        return lockError(aBeatMs, bBeatMs, speed) <= LOCK_TOLERANCE;
    }

    /** How much past the passage the decode for the junction estimate reaches, ms: the estimate
     *  is over the mix's low band and wants a little material on either side of the grid it is
     *  looking for, which is cheap (a decode) where the separation is not. */
    public static final long A_TAIL_LEAD_MS = 1_000L;

    /** The worst phase match the fusion still accepts, ms. Past it the entry falls back to the
     *  first bar line at or after the content start rather than being placed on a match that is
     *  not one. */
    public static final long PHASE_MATCH_MS = 120L;

    // --- round 5: relatives, the outgoing's body, and the incoming's intro ---------------------

    /**
     * The tempo relations two tracks may be in and still be fused, as {@code {p, q}} — the ratio
     * families {@link BeatProfile#relatedTempo} and {@code IncomingMix.MAX_SPEED_STEP} already use:
     * unisons, octaves, the third, the fourth, and 3:2.
     *
     * <p><b>Why.</b> {@link #LOCK_TOLERANCE} alone refused every pair whose periods are not equal —
     * measured on real material, {@code audio_owa} at 125.9 BPM against {@code audio_paradise} at
     * 64.0 BPM is 49% out on the beat period and was refused, although their <em>bar</em> grids
     * coincide exactly (one bar of paradise is two of owa). The user asked for the widening
     * (「融合过渡太少」), and the arithmetic was always there: what has to line up is the bar line
     * the cuts land on, not the beat.
     *
     * <p>{@code p} is the integer one <em>incoming</em> bar is multiplied by to reach the common
     * line — the swaps are at {@code entry + k*p*bBar} — and {@code q} the same for one outgoing
     * bar, so one step of the table is the smallest interval that is a whole number of both
     * grids' bars: {@code p} bars of the incoming's = {@code q} bars of the outgoing's. The pair
     * above is {@code {1, 2}}: one step is one bar of the incoming's (the slow) track and two of
     * the outgoing's, so the outgoing's own bar boundaries land on every swap line.
     */
    public static final int[][] RELATIONS = {{1, 1}, {2, 1}, {1, 2}, {3, 1}, {1, 3}, {4, 1}, {1, 4},
            {3, 2}, {2, 3}};

    /** How far from one of {@link #RELATIONS} the measured ratio may sit and still count as that
     *  relation, as a fraction of the relation — the same 8% {@code IncomingMix.MAX_SPEED_STEP}
     *  clamps the incoming deck's own stretch by. The residual is absorbed by the carry's stretch
     *  ({@link Relation#stretch}), not left to slide: the outgoing's material is read at a ratio
     *  that makes its own bar exactly {@code p/q} of the incoming's, so its bar boundaries land on
     *  the swap lines and the ≤ 8% comes out as a small tempo nudge of the carried rows (reported
     *  in the plan's own evidence). The relation 1 case keeps {@link #LOCK_TOLERANCE} (2%) and the
     *  deck's own ratio as its stretch, which is what keeps it bit-for-bit round 18's. */
    public static final double RELATION_TOLERANCE = 0.08d;

    /** How far behind the junction target the search may reach, ms, for a bar line the outgoing
     *  track is still <em>playing</em> on — the answer to 「如果一首歌结尾有十秒声音很小的部分智能
     *  过渡貌似会给他融合进来，导致听感平淡」 (a track whose own last seconds are a fade must not
     *  have the fusion sit in it). The junction prefers an earlier line over refusing; the refusal
     *  only stands when nothing in this reach has body level. */
    public static final long QUIET_SEARCH_MAX_MS = 12_000L;

    /** The most the passage's own level may sit below the outgoing track's <b>body</b> — the level
     *  it plays at when it is playing — and still be a bar line a fusion may be cut on, dB.
     *
     *  <p>Measured on the outgoing's separated backing (its drums, bass and melodic row: what a
     *  fusion can carry) as the loud tenth of {@link #BODY_WINDOW_MS} windows — the body over the
     *  material the render holds, the passage over the candidate's own span. The four tracks of the
     *  device run, body against a passage at the very end of the file: {@code 1460801818} −18.0 vs
     *  −23.8 (<b>5.8 dB</b>), {@code 34364062} −8.6 vs −10.6 (<b>2.0</b>),
     *  {@code 1410815174} −21.8 vs −69.9 (<b>48.1</b>), {@code 2700280437} (Hurt You) −7.8 vs −37.3
     *  (<b>29.5</b>). The gap is 5.8 → 29.5 dB, so anything in 7–9 separates every measured case;
     *  <b>7</b> is the tightest end of that gap, which is the side that keeps a passage that is
     *  merely a quieter chorus (a few dB) fusable. Too loose delivers the flatness the user
     *  reported; too tight costs a fusion that the round-17 edit would then play instead. */
    public static final double QUIET_PASSAGE_DB = 7d;

    /** How long one window of the body/passage measurement is, ms. A second rather than the whole
     *  window because the window is what may BE the fade: a fade ten seconds long drags any average
     *  down with it, while the loudest second of the same material is what the track sounded like
     *  when it was still playing. */
    public static final long BODY_WINDOW_MS = 1_000L;

    /** How much material behind the junction target the outgoing's body is measured over, ms. A
     *  long reach on purpose: a fade fills the seconds immediately before the passage, so a
     *  reference read there measures the fade and calls it the body. 30 s is what the renderer's
     *  decode window can hold (see {@code AndroidStemEditRenderer.probeWindow}). */
    public static final long QUIET_BODY_REACH_MS = 30_000L;

    /** How far under its own level inside the passage the outgoing track's <b>carried rows</b> have
     *  to be for the passage to say it has left, dB — the user's 「当前一首歌到达声音很小的部分」,
     *  measured off the outgoing's own separated material instead of guessed from the shape.
     *
     *  <p><b>Why relative, and why 12 dB.</b> The same number answers the same question on the
     *  incoming side already ({@link #INCOMING_ON_DB}: a row is <em>playing</em> while its beats are
     *  within 20 dB of its own loud tenth), so "the rows have left" is that test read backwards, on
     *  the other track, with the same instrument ({@link #bodyLevelDb}: the loud tenth of the
     *  {@link #BODY_WINDOW_MS} windows). Relative to the passage's own body and not to an absolute
     *  floor because a master's level is the track's own: this library's separated outgoing rows sit
     *  ~20 dB under full scale in a loud passage and −70 in a quiet one, so an absolute floor calls
     *  a quiet track "gone" from its first sample.
     *
     *  <p>⚠️ <b>The level itself is the round-30 tuning, and it is the user's own complaint that
     *  moved it: 20 → 12 dB.</b> 20 dB is the level at which a row has <em>stopped</em> — the
     *  incoming's own "is it playing" test read backwards — and read as an <em>arrival</em> it put
     *  the incoming track's voice far too late: 「我要的是有伴奏接入过渡，然后过渡一段时间开始放歌词，
     *  只不过歌词要稍微早一点」. What the user points at is 「前一首歌到达声音很小的部分」 — the
     *  outgoing track already well under its own body, not yet silent — and 12 dB is that reading of
     *  the same instrument. It is <em>softer, never unguarded</em>: the lead's own bound
     *  ({@link #VOICE_GATE_FLOOR_DB}) is what promises the voice does not arrive while the outgoing
     *  is still at or near the level the listener was on, and it is measured on the file's own
     *  gesture, so it is a bound and not a hope.
     *
     *  <p>The fallback when the envelope cannot be read is the gesture's own end ({@link
     *  #NO_EXIT_MEASUREMENT}): a passage whose rows never get quiet inside it (a steady last
     *  section, which is what the user's own pair measures) is one the recede itself takes out, and
     *  the recede reaches the floor at {@link Plan#fusionEndMs} by construction. That case is not
     *  left alone any more — {@link #VOICE_GATE_LEAD_BARS} leads the answer — but it is still said
     *  in the log, and both numbers are reported per pair. */
    public static final double OUTGOING_EXIT_DB = 12d;

    /** How long the outgoing's rows have to STAY at {@link #OUTGOING_EXIT_DB} under their own body
     *  before that is a departure, ms — one second, the same rule {@link #vocalStartMs} uses on the
     *  incoming's voice (a span whose <em>median</em> is past the floor, never a single frame).
     *  A between-phrase gap in the outgoing's own material is a dip, not an exit, and a rule that
     *  read the first quiet frame would put the incoming's voice in over the next line of the
     *  outgoing track's own singing. */
    public static final long OUTGOING_EXIT_SUSTAIN_MS = 1_000L;

    /** How far <b>earlier</b> than the measured departure the incoming's own voice may arrive, in
     *  bars of the OUTGOING track's own grid — the second half of round 30's tuning, and the half
     *  that answers the pairs {@link #OUTGOING_EXIT_DB} cannot: a passage whose rows never fall
     *  even 12 dB under its own body has no measured departure inside it at all, so the earlier
     *  arrival the user asked for (「歌词要稍微早一点」) has to come from the gesture's own shape
     *  rather than from the material's envelope.
     *
     *  <p><b>Why one bar, and why in the outgoing track's grid.</b> A bar is the unit every other
     *  musical decision in this file is made in (the intro skip is a bar, the runway is a bar, the
     *  swaps are bar lines), it is what 「稍微早一点」 means musically rather than as a stopwatch, and
     *  it is the outgoing track's own bar because the instant it leads is the outgoing track's own
     *  departure. It is read as {@code 4 * aBeatMs} of the outgoing's measured grid, so a slow pair
     *  leads by more milliseconds than a fast one and the lead is the same amount of music.
     *
     *  <p>⚠️ <b>The lead is bounded, and the bound is the invariant this file exists to keep</b>:
     *  it may never reach back past the instant the FILE's own gesture has taken the outgoing's
     *  carried rows {@link #VOICE_GATE_FLOOR_DB} under unity (see {@link #voiceFloorMs}). While the
     *  rows are at or near unity the listener is still on the outgoing track's own material, so the
     *  incoming's voice would arrive over a second voice's worth of music; past that instant the
     *  outgoing is already on its way out and the arrival the user described — 「当过渡的混音效果逐渐
     *  减弱…时，就接入歌词」 — is exactly what happens. */
    public static final int VOICE_GATE_LEAD_BARS = 1;

    /** The level the file's own recede must have taken the outgoing's carried rows below before the
     *  voice may arrive, dB under unity — the bound on {@link #VOICE_GATE_LEAD_BARS}, and the
     *  reason the lead cannot stack two voices.
     *
     *  <p>Unity is the right reference here and the passage's own body is not: inside the hold the
     *  FILE plays the outgoing track's rows at unity, which is the level the listener was already
     *  on when the junction arrived (that is the whole reason the gesture holds rather than cuts —
     *  round 6, 「不要让它戛然而止」), and the recede's own gain is the one thing about "is the
     *  outgoing still clearly audible" that is a fact of this file rather than a property of the
     *  track's own material. 6 dB is the same "clearly audible" line the rest of this codebase draws
     *  ({@code FadeCurve.bothAudibleMs} counts both tracks audible while they are within 6 dB of
     *  each other), and it lands at two thirds of the recede's equal-power decline
     *  ({@link #voiceGateFloorFraction}) — so a one-bar lead is usually allowed and a lead into the
     *  hold never is. */
    public static final double VOICE_GATE_FLOOR_DB = 6d;

    /** The fraction of one equal-power decline (see {@link #fade}) at which a row is
     *  {@link #VOICE_GATE_FLOOR_DB} under its own start: {@code cos(f*pi/2) = 10^(-dB/20)}. A
     *  function rather than a constant so the dB is the only number a later reader tunes, and the
     *  shape is the one the render really applies. */
    static double voiceGateFloorFraction() {
        return 2d / Math.PI * Math.acos(1d / Math.pow(10d, VOICE_GATE_FLOOR_DB / 20d));
    }

    /**
     * Where the file's own gesture has taken the outgoing's carried rows {@link
     * #VOICE_GATE_FLOOR_DB} under unity, as an offset from the entry in the file's own timeline, ms
     * — the floor {@link #entry} clamps its lead against, and the second of the two bounds there.
     *
     * <p>The subject is the slowest row to leave, its low end (it recedes over {@code
     * lowEndFadeSteps} steps where the drums take one), and the shape is the one the render applies
     * ({@link #fade}'s cosine for a decline to the floor): a bound read off the longest-lived row is
     * the <em>conservative</em> one — the sum of the rows is quieter than any one of them, so a
     * bound that holds for the low end holds for what the listener hears.
     *
     * <p>And it is never earlier than the deck-level hand-over: the outgoing deck itself is taken
     * from unity to exactly zero over {@link FadeCurve#JUNCTION_XFADE_MS} at the top of the ramp, so
     * the outgoing track's own <b>voice</b> — which this file never carries — is gone at that
     * instant whatever the rows are doing. The floor is the later of the two, which is what makes
     * "the outgoing is not still clearly audible" true for both of the things the listener hears as
     * the outgoing track: the live deck and the carried rows.
     *
     * @param holdSteps      steps the rows hold at unity ({@link #A_HOLD_STEPS}, or
     *                       {@link #SLAM_STEPS} on a slam)
     * @param fadeSteps      steps the low end takes to reach the floor ({@code lowEndFadeSteps};
     *                       a slam's splice is its own answer, so its caller passes 0)
     * @param barStepMs      one step of the gesture, ms
     */
    public static long voiceFloorMs(int holdSteps, int fadeSteps, long barStepMs) {
        double at = (Math.max(0, holdSteps) + voiceGateFloorFraction() * Math.max(0, fadeSteps))
                * Math.max(0L, barStepMs);
        return Math.max(FadeCurve.JUNCTION_XFADE_MS, Math.round(at));
    }

    /** How much of the incoming's own intro there has to be before the deck starts later instead
     *  of at the content start, in <b>bars of the incoming's own grid</b> (round 6's third pass;
     *  the user's 「让 ai 决定过渡时…接在第二首歌的哪里，不如直接跳过一些小节到人声部分」).
     *
     *  <p>⚠️ <b>Why a bar count and no longer round 5's flat 8 000 ms.</b> The complaint is about a
     *  <em>few bars</em> of instrumental before the voice, and 8 000 ms is four bars of a 120 BPM
     *  record (three and a half at 100, five and a half at 168) — a threshold in milliseconds is a
     *  different musical rule on every track. What the skip is really about is the runway the
     *  landing keeps: the voice has to be far enough in that a deck can still start a bar before
     *  it, which is one bar of that track's own grid at any tempo.
     *
     *  <p><b>Measured over the library the harness has decoded</b> ({@code fusion/intro.py}: the
     *  app's own {@link #vocalStartMs} rule — 25 ms frames, a 1 000 ms median above −50 dBFS — on
     *  the same separation the render makes, over each track's first 20 s): of 17 tracks, <b>12</b>
     *  sing at once ({@code audio_owa} 0, {@code audio_losemymind} 0, {@code audio_unhappy} 0,
     *  {@code audio_heard} 0, {@code audio_squabble} 0, {@code audio_agudo} 0, {@code audio_pianzhi}
     *  0, {@code audio_zhenhai} 0, {@code audio_huai} 100, {@code audio_inmyhead} 750, {@code
     *  audio_obsessed} 1 200, {@code audio_violet} 2 150 ms — the two over one bar are 0.36 and
     *  1.07 bars of their own grids, so neither is an intro) and <b>5</b> have a real one
     *  ({@code audio_hurtyou} 9 225, {@code audio_roundtown} 12 100, {@code audio_coldplay} 13 175,
     *  {@code audio_outoflove} 13 175, {@code audio_paradise} 14 175 ms). <b>Nothing in the library
     *  sits between 2.2 s and 9.2 s</b>, so on this material one bar and 8 000 ms choose the same
     *  five tracks — the difference is that the bar count is the rule the user asked for and cannot
     *  be tripped by a tempo. */
    public static final int VOCAL_SKIP_MIN_BARS = 1;

    /** How many common-grid bars of runway the deck keeps before the incoming's first vocal, when
     *  its intro is skipped: the deck starts a bar or two early so the voice lands on a beat of a
     *  track that is already playing, never after the voice itself. */
    public static final int VOCAL_SKIP_RUNWAY_BARS = 1;

    /** How far into the incoming track the deck may start at the <b>latest</b>, ms — the absolute cap
     *  on the landing's own window (round 33).
     *
     *  <p>The user's rule, after listening to the rounds that put the landing on the coupling:
     *  「落点寻找不要太靠后或者太靠前，尽量在前奏找完…总之不要把小半个歌跳过」, and then, after hearing
     *  the landing this round makes reachable: 「上一轮的 34800 很完美」 — 34 800 ms of a 187 s track, so
     *  the cap has to sit well past four bars and short of the song. 40 000 ms.
     *
     *  <p><b>It is not a second budget.</b> The user's own 过渡时长 is still what pays for the
     *  landing ({@code ceiling}, the window the incoming's vocals are out for — the same clause the
     *  plan asks as {@code fusionEnd <= removalMs}), so this cap only ever binds when their own
     *  setting already reaches that far. What it stops is a landing that skips most of a record on a
     *  pair whose blend is long: "half the song" is the thing they named as the failure. */
    public static final long ENTRY_MAX_MS = 40_000L;

    /** How many bars of the incoming's own grid a track that is <b>already singing at its content
     *  start</b> skips before the deck starts — the floor on the landing's window (round 33).
     *
     *  <p>「一开始就是词的那就过几个小节找」. Two bars, and what those bars cost is not their vocals
     *  (the gate holds the incoming's own singing out over the passage on <em>any</em> landing) but
     *  where the deck's own material comes from: two bars in rather than the track's first sample.
     *  On the pair the user listens to this is the landing they picked by ear before this round
     *  existed — {@code unhappy}'s 4 450 ms is its own second bar line plus the grid's phase. */
    public static final int ENTRY_WORDS_BARS = 2;

    /** How close to the content start the incoming's own voice has to be measured for its track to
     *  count as "singing at the start" — {@link #ENTRY_WORDS_BARS}'s own test, in bars of the
     *  incoming's grid.
     *
     *  <p>Two bars and not one: of the tracks that sing at once, two are measured just over a bar in
     *  ({@code audio_violet} 2 150 ms = 1.07 bars) and they are intros in name only — the count of
     *  tracks whose voice arrives between 2.2 s and 9.2 s is <b>zero</b> (see
     *  {@link #VOCAL_SKIP_MIN_BARS}). */
    public static final int ENTRY_AT_ONCE_BARS = 2;

    /** How far either side of the junction target the search may look, in bars of the outgoing's
     *  own grid (round 3: it was ±1 bar, i.e. "the nearest line, with a ±1 s preference").
     *
     *  <p>Widened because of what the user heard: on the pair that was listened to, the nearest
     *  line to the target is a pad-only breakdown of the outgoing track (its own drums −40 dBFS,
     *  its low end −83) — nothing of that track's groove is there to carry, so the fusion's first
     *  bar could only ever be its pad and the incoming's bed, several dB under the level the
     *  listener was on. Two bars either way is what it takes to reach back into a bar where the
     *  outgoing was still playing.
     *
     *  <p>A junction this far from the target changes the ramp by that much — the ramp is
     *  {@code aDur − 250 − junctionMs} — which is acceptable and is logged; and the band is paid
     *  for out of {@link #FUSION_TAIL_MAX_MS}, so a slow track's search is narrower rather than
     *  its separation being longer. */
    public static final int JUNCTION_SEARCH_BARS = 2;

    /** The floor a bar's own low end has to be at, dBFS, for that bar to count as carrying the
     *  outgoing track's groove — the median of the 25 ms frames of its separated drums + bass over
     *  a whole beat.
     *
     *  <p>−25 dBFS, and the number is measured rather than chosen: on the pair the user listened
     *  to, the four bar lines within the search band read <b>−14.2 / −16.9 / −20.0</b> where the
     *  track's groove is playing, <b>−29.6 / −35.7 / −41.5</b> at the junction the ear caught (a
     *  pad-only breakdown), and −60.7 / −79.8 / −82.9 two bars later (silence). The floor sits in
     *  the 10 dB gap between "playing" and "the tail of something that has stopped": a first
     *  attempt at −45 dBFS called the breakdown's tail "playing" and so never reordered anything,
     *  which is the trap this constant's own doc is here to keep a later reader out of. */
    public static final double GROOVE_FLOOR_DBFS = -25d;

    /** How far above the window's own median a frame has to be to count as an <b>attack</b> in the
     *  pulse clause, dB.
     *
     *  <p>⚠️ Round 4 measured this clause on a pair a real device refused — {@code 1460801818 ->
     *  34364062}, whose only failing clause was this one ("the passage's own pulse has a 1676.0ms
     *  hole with a 419.0ms period") — and the verdict was <b>right</b>, which is why the clause is
     *  unchanged. The numbers, from the device's own audio, per beat of the outgoing's separated
     *  rows over the passage the table carries: its drums peak at <b>−28.2 / −24.6 dBFS</b> on the
     *  first two beats (42 dB above their own median, −70.6) and then read <b>−71.7 / −74.7 /
     *  −65.1 / −69.8 / −71.6 / −66.5 / −66.4</b> for the rest — the drums hit twice and stop; its
     *  bass is at the floor for the whole passage (−70.5 peak, −79.7 median); the incoming's own
     *  drums are at −62 dBFS in its intro and its bass is a sustained line (−13 dBFS with per-beat
     *  peaks 0.5–2 dB above their own medians). So the passage has two beats of rhythm and then
     *  ~2 s with none in either backing, which is exactly the hazard this clause exists for (an
     *  outgoing whose rhythm has stopped), and the 5-beat hole it reports is the material's.
     *
     *  <p>What the calibration <em>did</em> show is that the floor is measured against the whole
     *  window's mixture, so a sparse outgoing bar is judged against the incoming's louder later
     *  material. Under a per-source floor (each source's rhythm rows against their own median,
     *  either counting as an attack) the same material still leaves a 2-beat hole — 841 ms against
     *  the 673 ms this clause allows — so the verdict is the same either way and the change was not
     *  made. A reader changing this should start from those two numbers. */
    public static final double PULSE_ATTACK_DB = 6d;

    /**
     * The outgoing track's own <b>body</b> — the level it plays at when it is playing — and how far
     * below it the passage a fusion would carry sits (round 5, {@link #QUIET_PASSAGE_DB}).
     *
     * <p>One instrument for both sides ({@link #bodyLevelDb}): the loud tenth of the
     * {@link #BODY_WINDOW_MS} windows of the outgoing track's own <em>carried rows</em> — its
     * drums, its bass and its melodic row, i.e. what a fusion can put in the file, never its voice
     * — over the body's reach for one and over the passage itself for the other. Comparing a
     * percentile of the same signal against a percentile of the same signal is what makes the
     * difference mean "this passage is quieter than this track" rather than "these two windows were
     * measured differently".
     */
    public interface BodyLevel {
        /** The outgoing's body level, dBFS, or NaN when it could not be measured. */
        double bodyDb();

        /** How far below {@link #bodyDb()} the outgoing's own material over the passage starting at
         *  {@code atMs} of its own file sits, dB — negative when the passage is above the body.
         *  NaN when either side is unmeasured. */
        double passageDropDb(long atMs, long passageMs);

        /** Whether that passage is a bar line a fusion may be cut on: within
         *  {@link #QUIET_PASSAGE_DB} of the body. <b>True when nothing was measured</b> — an
         *  unmeasured clause is not a refusal. */
        default boolean atBodyLevel(long atMs, long passageMs) {
            double drop = passageDropDb(atMs, passageMs);
            return !(drop > QUIET_PASSAGE_DB);
        }

        /** True when the body itself was measured at all. */
        default boolean measured() {
            return !Double.isNaN(bodyDb());
        }
    }

    /** No measurement: every bar line is usable and the ranking is round 3's and round 4's — the
     *  preference declining, which is what a caller with no decode gets. */
    public static final BodyLevel NO_BODY_MEASUREMENT = new BodyLevel() {
        @Override
        public double bodyDb() {
            return Double.NaN;
        }

        @Override
        public double passageDropDb(long atMs, long passageMs) {
            return Double.NaN;
        }
    };

    /**
     * A {@link BodyLevel} over the outgoing track's own master, {@code [ch][sample]} at {@code rate}
     * starting at {@code startMs} of the outgoing's file — the decoded window the renderer already
     * holds, so the clause costs no separation.
     *
     * <p>The body's reach is {@code [max(0, target - QUIET_BODY_REACH_MS), target)} and a passage is
     * the material from a candidate bar line for {@code passageMs} — the same {@link #bodyLevelDb}
     * both times. {@link #NO_BODY_MEASUREMENT} when the signal is empty or the reach is not inside
     * it: a body measured over material the decode does not hold would be a number nobody measured.
     *
     * @param pcm        the outgoing track's decoded master
     * @param rate       its sample rate
     * @param startMs    where that material starts in the outgoing's own file, ms
     * @param targetMs   the junction target ({@code aDur - CUT_BACK_MS - blendMs})
     * @param probeEndMs where the decoded window ends in the outgoing's own file, ms
     */
    public static BodyLevel bodyLevelOf(float[][] pcm, int rate, long startMs, long targetMs,
                                        long probeEndMs) {
        if (pcm == null || pcm.length == 0 || pcm[0] == null || !(rate > 0)) {
            return NO_BODY_MEASUREMENT;
        }
        long bodyFrom = Math.max(startMs, targetMs - QUIET_BODY_REACH_MS);
        long bodyTo = Math.max(bodyFrom, Math.min(targetMs, probeEndMs));
        if (bodyFrom < startMs || bodyTo - bodyFrom < BODY_WINDOW_MS) return NO_BODY_MEASUREMENT;
        final float[][] music = carriedRows(pcm);
        if (music == null) return NO_BODY_MEASUREMENT;
        final double body = bodyLevelDb(music, rate, startMs, bodyFrom, bodyTo - bodyFrom);
        if (Double.isNaN(body)) return NO_BODY_MEASUREMENT;
        final long from = startMs;
        return new BodyLevel() {
            @Override
            public double bodyDb() {
                return body;
            }

            @Override
            public double passageDropDb(long atMs, long passageMs) {
                double passage = bodyLevelDb(music, rate, from, atMs, passageMs);
                return Double.isNaN(passage) ? Double.NaN : body - passage;
            }
        };
    }

    /** The outgoing's carried rows ({@code drums}, {@code bass}, {@code other}) summed — the
     *  material a fusion can actually put in the file, and the subject of every level question this
     *  class asks about the outgoing track. */
    public static float[][] carriedRows(float[][] pcm) {
        if (pcm == null || pcm.length == 0 || pcm[0] == null) return null;
        return new float[][]{pcm[0].clone(), pcm.length > 1 && pcm[1] != null ? pcm[1].clone()
                : pcm[0].clone()};
    }

    /**
     * The loud tenth of the {@link #BODY_WINDOW_MS} windows of a signal over
     * {@code [fromMs, fromMs + spanMs)} of the track it starts at {@code startMs}, dBFS — the one
     * number both halves of the quiet-passage clause are measured in. NaN when that span holds no
     * full window.
     */
    public static double bodyLevelDb(float[][] pcm, int rate, long startMs, long fromMs,
                                     long spanMs) {
        if (pcm == null || pcm.length == 0 || pcm[0] == null || !(rate > 0) || !(spanMs > 0L)) {
            return Double.NaN;
        }
        int from = (int) Math.round((fromMs - startMs) * rate / 1000d);
        int span = (int) Math.round(spanMs * rate / 1000d);
        int window = (int) Math.round(BODY_WINDOW_MS * rate / 1000d);
        int length = pcm[0].length;
        if (from < 0 || span < window || from + span > length) return Double.NaN;
        int count = span / window;
        double[] levels = new double[count];
        for (int w = 0; w < count; w++) {
            double energy = 0d;
            int samples = 0;
            for (float[] channel : pcm) {
                if (channel == null) continue;
                for (int i = from + w * window; i < from + (w + 1) * window; i++) {
                    energy += channel[i] * (double) channel[i];
                    samples++;
                }
            }
            levels[w] = samples == 0 ? -240d
                    : 20d * Math.log10(Math.sqrt(energy / samples));
        }
        java.util.Arrays.sort(levels);
        return DjEdit.db(Math.pow(10d, levels[Math.min(count - 1, (int) (count * 0.9d))] / 20d));
    }

    /**
     * When the outgoing track <b>leaves its own passage</b> — the instant the fusion's landing and
     * its vocal gate are both written around (round 25).
     *
     * <p>The question is asked of the outgoing's own <em>separated carried rows</em> over the
     * passage the table carries, in the outgoing's own file milliseconds: the material's own
     * envelope, never the gesture's applied gains. Measuring the gesture would be circular — the
     * recede reaches the floor at {@link Plan#fusionEndMs} by construction, so it would answer
     * "the outgoing left at the gesture's own end" for every pair and the coupling below would
     * reproduce the fixed window it replaces. The material's envelope answers what the user asked
     * for: a track whose own passage goes quiet (a breakdown, a fade, a drop-out) leaves early, and
     * the incoming's voice comes in then.
     */
    public interface OutgoingExit {
        /** The instant, in the OUTGOING's own file ms, at which its carried rows have left the
         *  passage that starts at {@code fromMs} and runs {@code spanMs} — or {@code fromMs +
         *  spanMs} when they never do inside it (the gesture's own end is then the answer), or a
         *  negative value when the passage is not inside the material this measurement holds. */
        long goneAtMs(long fromMs, long spanMs);

        /**
         * The same envelope read at another level under the passage's own body, dB — the second
         * reading round 30 needs: {@link #OUTGOING_EXIT_DB} is where the incoming's voice is meant
         * to <em>arrive</em> (the user's 「前一首歌到达声音很小的部分」), and
         * {@link #VOICE_GATE_FLOOR_DB} is the level the lead that arrival takes may not reach back
         * past ("the outgoing is still clearly audible there"). Both are the same rule — a
         * sustained fall under the passage's own loud tenth ({@link #bodyLevelDb}) — so a caller
         * cannot get one and not the other, which is the point of asking it here.
         *
         * <p>Same three answers as {@link #goneAtMs(long, long)}: an instant, the passage's own end
         * when the level is never reached inside it, or negative when the passage is not inside the
         * material.
         */
        long goneAtMs(long fromMs, long spanMs, double underBodyDb);

        /** Whether the passage's own envelope could be read at all. False makes {@link
         *  #goneAtMs} the gesture's own end, which is the fallback the log names. */
        default boolean measured() {
            return false;
        }
    }

    /** The shape's own answer, for a caller with no separated material: the outgoing's rows are
     *  taken to the floor by the gesture's own recede, which ends at the passage's end — at every
     *  level, because a material nobody separated cannot have an earlier one. */
    public static final OutgoingExit NO_EXIT_MEASUREMENT = new OutgoingExit() {
        @Override
        public long goneAtMs(long fromMs, long spanMs) {
            return fromMs + Math.max(0L, spanMs);
        }

        @Override
        public long goneAtMs(long fromMs, long spanMs, double underBodyDb) {
            return goneAtMs(fromMs, spanMs);
        }
    };

    /**
     * A {@link OutgoingExit} over the outgoing track's own separated rows, {@code [row][ch][sample]}
     * at {@code rate} starting at {@code startMs} of the outgoing's file — the tail the renderer has
     * already separated for the fusion, so the measurement costs no separation.
     *
     * <p>The subject is {@link #carriedRows}: the rows a fusion can put in the file (drums, low end
     * and the melodic row), never the outgoing's voice — the voice is not carried, so it is not what
     * the listener hears leave. The body the rows are compared against is the passage's own loud
     * tenth ({@link #bodyLevelDb}), which is the same instrument {@link #QUIET_PASSAGE_DB} and
     * {@link #INCOMING_ON_DB} use.
     *
     * @param stems   the separated tail, rows in {@link StemGesture.Stem}'s own order
     * @param rate    its sample rate
     * @param startMs where that material starts in the outgoing's own file, ms
     */
    public static OutgoingExit exitOf(float[][][] stems, int rate, long startMs) {
        final float[][] music = carriedOf(stems);
        if (music == null || !(rate > 0)) return NO_EXIT_MEASUREMENT;
        return new OutgoingExit() {
            /** Frame levels, dBFS, of the rows over the widest span asked for so far. */
            private double[] levels;
            private long measuredSpanMs = -1L;

            @Override
            public boolean measured() {
                return true;
            }

            @Override
            public long goneAtMs(long fromMs, long spanMs) {
                return gone(fromMs, spanMs, OUTGOING_EXIT_DB);
            }

            @Override
            public long goneAtMs(long fromMs, long spanMs, double underBodyDb) {
                return gone(fromMs, spanMs, underBodyDb);
            }

            /** One reading of the same envelope at any level under the passage's own body — the
             *  round-25 rule verbatim, with the level as the parameter round 30 made it. */
            private long gone(long fromMs, long spanMs, double underBodyDb) {
                if (fromMs < startMs || !(spanMs > 0L)) return -1L;
                long endMs = fromMs + spanMs;
                if (!levels(fromMs, spanMs)) return -1L;
                double body = bodyLevelDb(music, rate, startMs, fromMs, spanMs);
                if (Double.isNaN(body)) return -1L;
                double floor = body - underBodyDb;
                int frameMs = frameMs();
                int sustain = (int) Math.max(1L, Math.round(OUTGOING_EXIT_SUSTAIN_MS / frameMs));
                int frames = levels.length;
                for (int i = 0; i + sustain <= frames; i++) {
                    // A window is quiet when its MEDIAN frame is under the floor: one quiet frame
                    // is a between-phrase gap, and the median is the same shape `vocalStartMs`
                    // reads the incoming's own voice with.
                    if (median(levels, i, sustain) > floor) continue;
                    // ⚠️ And the answer is the FALL, not the window that confirmed it: a median over
                    // a second is quiet from the moment most of that second is, which is up to half a
                    // second before the rows actually go. The first frame at or after the confirming
                    // window whose own level is under the floor is the instant the coupling wants,
                    // and the window is what makes it a departure rather than a dip.
                    int at = i;
                    while (at < frames && levels[at] > floor) at++;
                    return fromMs + (long) at * frameMs;
                }
                // It never went quiet inside the passage: the gesture's own end is the answer, and
                // the gate below then keeps the voice where the table has always put it.
                return endMs;
            }

            /** Whether the frames needed for {@code fromMs..fromMs+spanMs} are cached. */
            private boolean levels(long fromMs, long spanMs) {
                if (measuredSpanMs >= spanMs && levels != null) return true;
                int span = (int) Math.round(spanMs * rate / 1000d);
                int from = (int) Math.round((fromMs - startMs) * rate / 1000d);
                if (from < 0 || span <= 0 || from + span > music[0].length) return false;
                float[][] slice = new float[music.length][span];
                for (int ch = 0; ch < music.length; ch++) {
                    System.arraycopy(music[ch], from, slice[ch], 0, span);
                }
                levels = StemBridge.frameLevelsDb(slice, rate, spanMs / 1000d);
                measuredSpanMs = spanMs;
                return levels != null && levels.length > 0;
            }
        };
    }

    /** One 25 ms frame in milliseconds, the unit {@link StemBridge#frameLevelsDb} answers in. */
    private static int frameMs() {
        return Math.max(1, (int) Math.round(StemBridge.FRAME_SEC * 1000d));
    }

    /** The median of {@code count} frames from {@code at} — the reading the sustain rule is judged
     *  on. */
    private static double median(double[] levels, int at, int count) {
        return StemBridge.median(java.util.Arrays.copyOfRange(levels, at, at + count));
    }

    /** A separated tail's {@link #carriedRows}: drums, low end and the melodic row summed, which is
     *  what a fusion can put in the file. Null when there is nothing to sum. */
    public static float[][] carriedOf(float[][][] stems) {
        if (stems == null || stems.length == 0) return null;
        int rows = Math.max(StemGesture.Stem.DRUMS.row(), StemGesture.Stem.BASS.row()) + 1;
        rows = Math.max(rows, StemGesture.Stem.OTHER.row() + 1);
        if (stems.length < rows || stems[StemGesture.Stem.DRUMS.row()] == null) return null;
        int channels = stems[StemGesture.Stem.DRUMS.row()].length;
        int length = stems[StemGesture.Stem.DRUMS.row()][0] == null
                ? 0 : stems[StemGesture.Stem.DRUMS.row()][0].length;
        if (channels <= 0 || length <= 0) return null;
        float[][] out = new float[channels][length];
        for (int stem = 0; stem < rows; stem++) {
            if (stem == StemGesture.Stem.VOCALS.row() || stems[stem] == null) continue;
            for (int ch = 0; ch < channels && ch < stems[stem].length; ch++) {
                if (stems[stem][ch] == null) continue;
                int n = Math.min(length, stems[stem][ch].length);
                for (int i = 0; i < n; i++) out[ch][i] += stems[stem][ch][i];
            }
        }
        return out;
    }

    /**
     * The level the junction's step is measured against, dBFS: the outgoing master's own last
     * {@link #STEP_WINDOW_MS}, <b>clamped up to the body</b> — a fading tail may not be the
     * reference a fusion is levelled to (round 5, {@link #QUIET_PASSAGE_DB}).
     *
     * <p>The clamp is what stops the make-up gain from "answering" a fade: with the tail as the
     * reference the step is near zero for any passage, so a fusion written inside a ten-second fade
     * passed the step clause by being as quiet as the fade — exactly the flat transition the user
     * reported. Clamped, the reference is {@code body - QUIET_PASSAGE_DB}: the passage is lifted
     * towards the track's own body by the make-up gain (bounded by {@link #MAKEUP_MAX_DB}), and one
     * that cannot reach it is refused by the step clause as it always could be. Measured on the
     * device's tracks, the master's last 500 ms sits <b>-53.5 / -11.1 / -95.2 / -90.1 dBFS</b>
     * against bodies of -18.0 / -8.6 / -21.8 / -7.8: tens of dB, on exactly the fades.
     */
    /** The level of a signal over a window, dBFS — the renderer needs the same instrument the
     *  clause uses to log what the reference clamp did ({@link StemBridge#levelDb}). */
    public static double levelOf(float[][] pcm, int rate, double windowSec) {
        return StemBridge.levelDb(pcm, rate, windowSec);
    }

    public static double referenceDb(float[][] outgoingMaster, int rate, double bodyDb) {
        double master = outgoingMaster == null || outgoingMaster.length == 0
                ? Double.NaN
                : StemBridge.levelDb(outgoingMaster, rate, STEP_WINDOW_MS / 1000d);
        if (Double.isNaN(bodyDb)) return master;
        if (Double.isNaN(master)) return bodyDb - QUIET_PASSAGE_DB;
        return Math.max(master, bodyDb - QUIET_PASSAGE_DB);
    }

    /**
     * Where the incoming track's voice first comes in, ms of its own file, or -1 when it never does
     * inside the material — the measurement the intro skip is written in (round 5, {@link
     * #VOCAL_SKIP_MIN_MS}).
     *
     * <p>Not the first frame above the floor: a breath, a bleed or a stray consonant is one frame,
     * and skipping an intro on the strength of one is worse than not skipping. The answer is the
     * first {@link #VOCAL_SUSTAIN_MS} of the vocal row whose <em>median</em> frame is above it — a
     * sustained voice, which is what a voice arriving is.
     *
     * <p>⚠️ <b>And the floor it has to clear is the row's own, since round 32</b> — the later of
     * the {@code floorDbfs} the caller hands in and the row's body less {@link #VOICE_ARRIVAL_DB}.
     * The absolute floor the renderer passes ({@code DjEdit.SILENT_FRAME_DBFS}, −50) is a
     * <em>lower bound</em> here and not the test: a separated vocal row's bleed is tens of dB above
     * it (the pair that was listened to carries −25 dBFS from about 75 ms on), so on its own it
     * cannot tell a bleed from a voice. That constant's own note has the measurement.
     */
    public static long vocalStartMs(float[][] vocals, int rate, long startMs, double floorDbfs) {
        if (vocals == null || vocals.length == 0 || vocals[0] == null || !(rate > 0)) return -1L;
        double[] levels = StemBridge.frameLevelsDb(vocals, rate, vocals[0].length / (double) rate);
        int frameMs = Math.max(1, (int) Math.round(StemBridge.FRAME_SEC * 1000d));
        int frames = (int) Math.max(1L, Math.round(VOCAL_SUSTAIN_MS / frameMs));
        // The caller's floor stays the floor when the row has no body to be read against (a row
        // shorter than one BODY_WINDOW_MS): `bodyLevelDb` answers NaN and the rule is the one this
        // method always applied (see VOICE_ARRIVAL_DB's own note for the other cases).
        double bodyDb = rowBodyDb(vocals, rate, startMs);
        double floor = Double.isNaN(bodyDb) ? floorDbfs
                : Math.max(floorDbfs, bodyDb - VOICE_ARRIVAL_DB);
        for (int i = 0; i + frames <= levels.length; i++) {
            double median = StemBridge.median(java.util.Arrays.copyOfRange(levels, i, i + frames));
            if (median > floor) return startMs + (long) i * frameMs;
        }
        return -1L;
    }

    /** How long a stretch of the incoming's vocal row has to be above the floor before it counts as
     *  the voice arriving, ms (see {@link #vocalStartMs}). */
    public static final long VOCAL_SUSTAIN_MS = 1_000L;

    /**
     * The level of the vocal row's own <b>body</b>, dBFS — {@link #bodyLevelDb} over the whole of
     * the material handed to {@link #vocalStartMs}, which is the same instrument and the same
     * {@link #BODY_WINDOW_MS} windows the outgoing's body is measured with ({@link #bodyLevelOf}:
     * the loud tenth of its one-second windows). NaN when the row holds no full window, which
     * leaves the caller's own floor as the answer.
     *
     * <p>The span is the shortest channel, the same bound the frame scan above uses — a channel
     * shorter than the first one would otherwise be read past its end by the energy sum.
     */
    private static double rowBodyDb(float[][] vocals, int rate, long startMs) {
        int samples = vocals[0].length;
        for (float[] channel : vocals) {
            if (channel != null) samples = Math.min(samples, channel.length);
        }
        return bodyLevelDb(vocals, rate, startMs, startMs, samples * 1000L / rate);
    }

    /**
     * How far under the row's OWN body the {@link #VOCAL_SUSTAIN_MS} median of a vocal row may sit
     * and still count as the voice arriving, dB — the second half of {@link #vocalStartMs}, and the
     * half that makes its answer a measurement of the voice rather than of a bleed (round 32).
     *
     * <p><b>Why the floor has to be relative, and it is measured.</b> The incoming track of the
     * pair that was listened to ({@code unhappy}, a 12 s blend) separates with a <b>−25 dBFS
     * presence in its vocal row from about 75 ms on</b> — separation bleed from the backing rather
     * than singing — while its voice really arrives at 16-17 s. The median of each 1 s window of its
     * 25 ms frames: <b>0-1 s −25.39, 1-2 s −27.28, 2-3 s −29.80, 3-4 s −37.95, 4-15 s −71 … −75</b>
     * (the voice genuinely absent for eleven seconds), <b>16-17 s −24.66, 17-18 s −13.57, 18-19 s
     * −14.30</b>. The absolute floor the renderer hands in ({@code DjEdit.SILENT_FRAME_DBFS} = −50)
     * sits 25 dB under that bleed, so the FIRST window already cleared it, {@link #vocalStartMs}
     * answered <b>0 ms</b>, the deck started at the track's own beginning and the rendered
     * transition ran into eleven seconds of voice-less intro — which is the 「不像过渡」 the listener
     * reported. No absolute floor can answer a row whose own bleed is louder than it, so the
     * reference has to be the row's own body: the instrument the rest of this file already reads
     * levels with ({@link #bodyLevelDb}, the loud tenth of the {@link #BODY_WINDOW_MS} windows, as
     * {@link #QUIET_PASSAGE_DB}, {@link #OUTGOING_EXIT_DB} and {@link #INCOMING_ON_DB} all are).
     *
     * <p><b>Why 6.</b> The bleed sits at −25.4 and the body at −13.6 … −14.5 (the sustained level
     * from 17 s on) — <b>11 dB apart</b> — so 6 dB separates them with margin, while a passage that
     * is merely a quieter verse stays an arrival (the same 6 dB "clearly audible" line
     * {@link #VOICE_GATE_FLOOR_DB} draws). On this track the first window whose 1 s median clears
     * {@code body − 6 = −19.6} is <b>17-18 s</b> (the 16-17 s window at −24.66, still the bleed,
     * does not), so the answer becomes <b>17 000 ms</b> instead of 0.
     *
     * <p>⚠️ <b>Never lower this to make anything pass.</b> This number IS the separation between a
     * bleed and a voice: below it the bleed's own windows qualify again, which is the defect the
     * constant exists for, and the deck goes back to the track's own beginning on exactly the pairs
     * the round is about.
     *
     * <p>⚠️ <b>Two things it cannot do, and both are the honest limits of the rule.</b> (1) A row
     * with <em>no</em> voice at all measures its own bleed as its body, so the floor sits 6 dB under
     * the bleed and the first window still clears it: the answer stays {@code startMs}, exactly
     * where the absolute floor put it — that case acquires no arrival it does not have, and must
     * not get worse (the row a separation really returns for the track above is one of these if the
     * material stops before the voice: the renderer's separated head is
     * {@code removalMs + returnSpanMs} = 16 044 ms for it, and its arrival begins at 16 s, so the
     * head holds the bleed and not one second of the body). (2) The body is measured over the
     * material <em>handed in</em>: a row that ends before the voice comes in has no body to be
     * relative to and is answered as the absolute floor answered it.
     */
    public static final double VOICE_ARRIVAL_DB = 6d;

    /**
     * How long the material a renderer hands to {@link #vocalStartMs} has to reach, ms — the span
     * the arrival has to be <em>inside</em> for the rule above to be able to see it at all
     * (round 33).
     *
     * <p><b>Why a constant, and the measurement.</b> {@link #VOICE_ARRIVAL_DB}'s own note names the
     * pair this is about and the renderer's separated head for it: {@code removalMs +
     * returnSpanMs} = <b>16 044 ms</b> for a 12 s blend on a 510.986 ms grid — and that track's
     * voice genuinely arrives at <b>16-18 s</b> of its own file (per 1 s medians of its vocal row:
     * 4-15 s is −71 … −75 dBFS, 16-17 s is −24.66, 17-18 s is −13.57, 18 s on is −13.6 … −14.5). So
     * the head the rule was reading <em>ended before the arrival</em>: all sixteen of its one-second
     * windows were the faint separation bleed or the eleven-second hole, its body was measured as
     * the <em>bleed</em> (−27.3 dBFS), the floor went 6 dB under that (−33.3), and the very first
     * window (−25.4) cleared it — {@link #vocalStartMs} answered <b>0</b> and the deck started at
     * the track's own beginning with eleven seconds of voice-less intro in front of it. The rule was
     * right and the material was short of the fact it is about (「AI 推断每首歌的过渡落点」).
     *
     * <p>⚠️ <b>Why 19 000 and not the "one second past the arrival" that 18 000 looks like.</b>
     * Because the floor is read against the row's own body, the material has to be long enough for
     * that body to be <em>found</em>: {@link #bodyLevelDb} answers the window at the 90th percentile
     * of its {@link #BODY_WINDOW_MS} windows, so the arrival has to sit inside the material's last
     * tenth. Measured on this pair's own table (1 s windows: −25.39, −27.28, −29.80, −37.95, twelve
     * at −71 … −75, −24.66, −13.57, −14.30), as the material handed in grows:
     * <pre>
     *   15 000 - 18 500 ms   body = −25.40 or −24.66 (a bleed window)   floor −31.4 / −30.7   → 0
     *   19 000 - 26 000 ms   body = −13.60 (the voice's own second)     floor −19.6          → 16 475
     * </pre>
     * At 18 000 ms the 90th-percentile index is still the 16-17 s window — the twelve-second hole
     * leaves one window too few above it — and the answer is <b>still 0</b>: that span pays for the
     * material and buys nothing. 19 000 ms is the shortest span on which this pair's own arithmetic
     * answers its arrival, and the answer does not move again from there to 26 000 (measured in
     * 500 ms steps). What the rule needs is the arrival inside the last tenth of what it is given,
     * which is what makes a fixed constant right for the pair this round is about — and a per-track
     * answer (separate until the body is the body) a later round's question.
     *
     * <p><b>What the value buys, and what it must not be lowered to.</b> The answer only has to
     * clear the plan's own ceiling to place the landing: on this pair that ceiling is
     * {@code removalMs - windowMs} = 12 000 − 6 212 = <b>5 788 ms</b>, and every arrival past it
     * lands on the same line — <b>4 450</b>, which skips 4 088 ms of the incoming's intro, the
     * figure the listener chose. ⚠️ 18 000 does not clear it on this pair (it answers 0, the very
     * defect this round exists to fix), and where the threshold sits is the percentile rule's own
     * arithmetic rather than a margin that may be trimmed back for cost.
     *
     * <p>⚠️ <b>What this is not.</b> It is not a measurement rule and it changes no threshold — the
     * answer {@link #vocalStartMs} gives on the same row is the same answer, it is simply given a row
     * that contains what it is looking for. Everything that builds the <em>file</em> keeps using the
     * head it always used; only the arrival's own row is this long (see
     * {@code AndroidStemEditRenderer}).
     */
    public static final long ARRIVAL_PROBE_MS = 19_000L;

    /**
     * The span a renderer has to separate for the arrival of the incoming track's voice to be inside
     * the material {@link #vocalStartMs} measures, ms: {@code headMs} — the render's own head, which
     * the file it writes is built from — extended to {@link #ARRIVAL_PROBE_MS}, and never past the
     * track's own end.
     *
     * <p>Three properties, and all three are the point of the function:
     * <ul>
     *   <li><b>It never SHORTENS anything.</b> A head already at or past {@code ARRIVAL_PROBE_MS} is
     *   returned unchanged, so a request whose material already reached the arrival separates exactly
     *   the material it separated before (bit for bit: same window, same model input, same file);</li>
     *   <li><b>a short track is not asked for material it does not have.</b> {@code fileDurationMs}
     *   bounds the probe (a 9 s track is separated to its own 9 s, and its head is untouched
     *   whatever that is) — and a duration the container does not give ({@code <= 0}) leaves the
     *   probe whole, because a decode simply stops at the end of the file either way;</li>
     *   <li><b>the head is a FLOOR and the probe is the extension.</b> The answer is
     *   {@code max(headMs, min(ARRIVAL_PROBE_MS, fileDurationMs))}, so no request is ever refused or
     *   shortened by this round, and the extra material separated is bounded by
     *   {@code ARRIVAL_PROBE_MS - headMs} (the measured pair: 16 044 → 19 000 ms, <b>+18.4%</b> —
     *   the price of the arrival being measurable at all, see {@link #ARRIVAL_PROBE_MS}).
     * </ul>
     */
    public static long arrivalProbeMs(long headMs, double fileDurationMs) {
        long probe = fileDurationMs > 0d
                ? Math.min(ARRIVAL_PROBE_MS, Math.round(fileDurationMs))
                : ARRIVAL_PROBE_MS;
        return Math.max(headMs, probe);
    }

    /**
     * Where the incoming track's own <b>rhythm</b> row starts playing, ms of its own file, or -1
     * when it is not playing anywhere in {@code [fromMs, toMs)}.
     *
     * <p>Round 6's second pass, and its subject is the passage's pulse rather than the entry: a row
     * of the outgoing's may not be faded before the incoming's <em>same</em> row is playing, or the
     * passage hands the pulse to nobody. The device's own pair shows what that sounds like —
     * {@code Lose My Mind -> AGUDO} put A's drums out at 5260 ms while the incoming's own first
     * ~3 s carry no drums at all (its head is voice and pad), and the acceptance measured a 2832 ms
     * hole in the passage's pulse, which is five beats of the incoming's own 566 ms grid.
     *
     * <p>Measured on the incoming's own separated head — the rows the renderer already has in hand
     * for the arrival side — and against the row's <em>own</em> loud level rather than an absolute
     * one: a kick drum's peak is what marks a beat, and {@link #INCOMING_ON_DB} under the row's own
     * loud tenth is "this row is playing", whatever the track's overall level is. One beat wide, so
     * a row that plays on the beat is found on the beat.
     *
     *  <p>⚠️ <b>What this measurement CANNOT do, measured, and why its reading has to be
     *  checked.</b> On the device pair's incoming ({@code Lose My Mind}'s own separated drums, the
     *  harness's 45 s head, per-beat peaks in dBFS): {@code -8 -10 -20 -34 -38 -42 -51 -51 -37 -54
     *  -53 -53 -57 -54 -51 -49 -5 -10 -18 -30} — a <b>52 dB</b> spread from its loudest beat to its
     *  quietest, and it OPENS on two loud hits. Its loud tenth is therefore the intro's own level in
     *  any window short of the kit's return (measured: −10.4 dBFS over a 15 s head, −9.7 over 19 s,
     *  −17.6 over 30 s, −1.0 over 45 s), and no margin separates the opening hit (−8) from the
     *  kit's ordinary beats (−18, −30, −55): at 12, 18 and 24 dB under its own loud tenth the
     *  row's hit share never exceeds 0.38 on any beat, and at every head length from 15 s to 45 s
     *  this method answers <b>0 ms</b> — "playing from the first beat". The device's log said exactly
     *  that, and it is right: an earlier figure of 8 415 ms for this row came from a scratch mirror
     *  of this rule and is not reproducible from the same data (13 calibrations over four head
     *  lengths), so it must not be relied on. <b>What follows:</b> the wait only ever HOLDS A's rows
     *  longer and can never create a hole, but on a stem like this one it does not engage, and the
     *  pair's fate is the acceptance's pulse clause — which is measured on the produced passage and
     *  is reliable there. {@link #rowHitShare} is how a caller sees that the reading is not
     *  evidence: below {@link #INCOMING_TRUST_SHARE} the row cannot be told from its own intro.
     *
     *  <p>The reference is the row's own loud tenth over the whole array, and
     *  {@link #INCOMING_SUSTAIN_BEATS} beats in a row are required, so a single hit is not a row
     *  playing. A row whose loud tenth is under {@link #INCOMING_ROW_FLOOR_DBFS} is absent and
     *  answers -1.
     */
    public static long rowStartMs(float[][] pcm, int rate, long fromMs, long toMs, double beatMs) {
        java.util.List<Double> peaks = beatPeaks(pcm, rate, toMs, beatMs);
        if (peaks == null || peaks.size() < INCOMING_SUSTAIN_BEATS) return -1L;
        int step = Math.max(1, (int) Math.round(beatMs * rate / 1000d));
        int from = (int) Math.max(0L, Math.round(fromMs * rate / 1000d));
        int to = Math.min(pcm[0].length, (int) Math.round(toMs * rate / 1000d));
        if (to - from < step || to < 0 || from > pcm[0].length) return -1L;
        double loudDb = loudTenthDb(peaks);
        if (loudDb < INCOMING_ROW_FLOOR_DBFS) return -1L;
        double floor = loudDb - INCOMING_ON_DB;
        // The search is the caller's window: the reference is the row's, the question is this
        // stretch of it.
        int firstBeat = Math.max(0, from / step);
        int lastBeat = Math.min(peaks.size(), Math.max(firstBeat + 1, to / step + 1));
        int run = 0;
        for (int i = firstBeat; i < lastBeat; i++) {
            run = 20d * Math.log10(Math.max(1e-9d, peaks.get(i))) >= floor ? run + 1 : 0;
            if (run >= INCOMING_SUSTAIN_BEATS) {
                // The row's run started `INCOMING_SUSTAIN_BEATS` beats ago: the row is playing from
                // there, which is the instant a recede may start on.
                int first = i - INCOMING_SUSTAIN_BEATS + 1;
                return (long) Math.round(first * step * 1000d / (double) rate);
            }
        }
        return -1L;
    }

    /**
     * The share of a window's beats that carry a hit of a row, at {@link #rowStartMs}'s own
     * reference: the figure that says whether {@link #rowStartMs} could read the row at all, and the
     * one to print beside its answer.
     *
     * <p>Measured: a kit that plays on its beats (the device's {@code squabble up}, its beats
     * −0.1…−7.9 dBFS) answers ~1.0; {@code Lose My Mind}'s own separated drums — a 52 dB spread
     * between its beats, an intro that opens on two loud hits — never exceed 0.38 at any margin,
     * and its {@link #rowStartMs} answer is 0 ms at every head length. Under
     * {@link #INCOMING_TRUST_SHARE} the row cannot be told from its own intro, so the wait (which
     * exists to hold A's rows for it) has nothing to key on and the pair is the acceptance's.
     */
    public static double rowHitShare(float[][] pcm, int rate, long fromMs, long toMs,
                                     double beatMs) {
        java.util.List<Double> peaks = beatPeaks(pcm, rate, toMs, beatMs);
        if (peaks == null || peaks.isEmpty()) return 0d;
        double floor = loudTenthDb(peaks) - INCOMING_ON_DB;
        int first = Math.max(0, (int) (fromMs / Math.max(1d, beatMs)));
        int hits = 0;
        int beats = 0;
        for (int i = first; i < peaks.size(); i++) {
            beats++;
            if (20d * Math.log10(Math.max(1e-9d, peaks.get(i))) >= floor) hits++;
        }
        return beats == 0 ? 0d : hits / (double) beats;
    }

    /** The share below which {@link #rowStartMs}'s own answer is not evidence (see
     *  {@link #rowHitShare}): a row that plays on half its beats is one a listener would call
     *  playing, and under that what the measurement reads may be an intro's two hits. */
    public static final double INCOMING_TRUST_SHARE = 0.5d;

    /**
     * How many extra steps of hold the renderer's retry loop may ask for, in all: two.
     *
     * <p>What the loop is for, in one line: on a stem whose rows cannot be read (see
     * {@link #rowStartMs}) the measurement cannot ask for the wait, so the renderer extends the hold
     * itself, one step at a time, and keeps the extension only if {@link #measure} — the judge that
     * is reliable, because it measures the passage that was produced — says the pulse hole closed.
     * Two steps is the bound the cost allows: each one is a step of the passage, so it is a step of
     * the outgoing's file that has to be separated (on the device's pairs a 2 180-2 305 ms step,
     * i.e. a few seconds of audio and roughly 1.5-3x that in model time), and it is spent only on a
     * fusion the acceptance would otherwise have refused outright. The measurement's own
     * {@link #rowHitShare} stays as the cheap predictor that usually saves the loop a pass.
     */
    public static final int FUSION_WAIT_EXTRA_STEPS = 2;

    /** Which of two measured passages is better, the rule the retry loop keeps its best by: one
     *  that passes beats any that does not, and between two that fail the smaller hole wins — a
     *  longer hold that does not close the hole is not an improvement, and the loop must not prefer
     *  it just for being later. A null candidate is never better. */
    public static boolean betterPulse(Report candidate, Report best) {
        if (candidate == null) return false;
        if (best == null) return true;
        if (candidate.acceptable != best.acceptable) return candidate.acceptable;
        return candidate.longestGapMs < best.longestGapMs;
    }

    /** One row's per-beat peaks (the beat's own quarter-frames, so a hit between samples is still
     *  found), over the array's own timeline up to {@code toMs}; null when there is nothing to
     *  measure. The one implementation {@link #rowStartMs} and {@link #rowHitShare} share, so the
     *  answer and its reliability cannot be about different peaks. */
    private static java.util.List<Double> beatPeaks(float[][] pcm, int rate, long toMs,
                                                    double beatMs) {
        if (pcm == null || pcm.length == 0 || pcm[0] == null || !(beatMs > 0d) || !(rate > 0)) {
            return null;
        }
        int step = Math.max(1, (int) Math.round(beatMs * rate / 1000d));
        int frame = Math.max(1, step / 4);
        int upto = (int) Math.min(pcm[0].length, Math.round(toMs * rate / 1000d));
        java.util.ArrayList<Double> peaks = new java.util.ArrayList<>();
        for (int at = 0; at + step <= upto; at += step) {
            double peak = 0d;
            for (int ch = 0; ch < pcm.length; ch++) {
                if (pcm[ch] == null) continue;
                int end = Math.min(pcm[ch].length, at + step);
                for (int i = at; i < end; i += frame) {
                    double window = 0d;
                    for (int j = i; j < Math.min(i + frame, end); j++) {
                        window = Math.max(window, Math.abs(pcm[ch][j]));
                    }
                    peak = Math.max(peak, window);
                }
            }
            peaks.add(peak);
        }
        return peaks;
    }

    /** A row's own loud tenth over peaks it was given, dBFS — the measure StemBridge's level clauses
     *  use: for a drum row "a beat with the kit on it", for a bass row its note. */
    private static double loudTenthDb(java.util.List<Double> peaks) {
        double[] sorted = new double[peaks.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = peaks.get(i);
        java.util.Arrays.sort(sorted);
        double loud = sorted[(int) Math.min(sorted.length - 1L,
                Math.round(0.9d * (sorted.length - 1)))];
        return 20d * Math.log10(Math.max(1e-9d, loud));
    }

    /** How far under a row's OWN loud tenth a beat may sit and still count as the row playing, dB
     *  (see {@link #rowStartMs}).
     *
     *  <p>20 and not a tight 3: this is asked of a row over a window that may be an intro, and the
     *  question is "is the kit on", not "is this the loudest beat of the row". Measured on the
     *  device pair's incoming, {@code Lose My Mind}'s own separated head — its loud beat is
     *  −0.1 dBFS, its intro's beats sit −34…−58 dBFS and its kit −5…−30 — a 12 dB margin called the
     *  intro's two opening hits "playing" (they are −8 and −10), a 20 dB margin calls the kit's
     *  return (−5, −10, −18) playing and the intro's beats not, and its sustained low end
     *  (−9…−18, its loud tenth −3.9) playing from the first beat either way. Both directions of the
     *  error are visible and both are survivable: a row called playing too early leaves the
     *  acceptance's pulse clause to measure the hole (which is what this measurement exists to
     *  prevent), and a row called playing too late only holds A's row longer — and if it never gets
     *  there inside the passage, the pair is refused BY NAME rather than accepted and measured. */
    public static final double INCOMING_ON_DB = 20d;

    /** How many beats in a row a row has to be above {@link #INCOMING_ON_DB} before
     *  {@link #rowStartMs} calls it playing, so a single hit in an otherwise empty intro is not an
     *  arrival (see that method's own measurement). Three beats is the same order as
     *  {@link #VOCAL_SUSTAIN_MS} of the voice. */
    public static final int INCOMING_SUSTAIN_BEATS = 3;

    /** The floor a separated row's own loud tenth has to clear for {@link #rowStartMs} to answer at
     *  all, dBFS. Under it the row is absent — an intro with no kit in it — and every beat is "as
     *  loud as the loudest", which would make the first beat look like the row arriving. */
    public static final double INCOMING_ROW_FLOOR_DBFS = -55d;

    /**
     * Where the incoming track's own rhythm rows start playing, as the planner asks for them —
     * {@link #rowStartMs} behind an interface, because the planner's only business is whether the
     * row that replaces A's is on yet.
     */
    @FunctionalInterface
    public interface IncomingOn {
        /** The first instant at or after {@code fromMs} where the row is playing, ms of the
         *  incoming's own file, or -1 when it does not play inside {@code [fromMs, toMs)}. */
        long firstPlayingMs(int row, long fromMs, long toMs);
    }

    /** No measurement was made: the recede then keeps the design's own instants, which is
     *  bit-for-bit the behaviour before round 6's second pass — what every caller that does not
     *  measure (and every test that is about something else) gets. */
    public static final IncomingOn NO_INCOMING_MEASUREMENT = (row, fromMs, toMs) -> -1L;

    /** The floor a decoded window's low band has to be above for its downbeat to be estimated at
     *  all, dBFS. Under it there is nothing for the estimate to find, and the four candidate
     *  phases score the same nothing. */
    public static final double LOW_BAND_FLOOR_DBFS = -60d;

    /** How far below the material it was taken from a carried row may sit, dB. The same 3 dB
     *  {@link StemBridge} uses, so "the carry is the material" means one thing in both places. */
    public static final double CARRY_TOLERANCE_DB = StemBridge.CARRY_TOLERANCE_DB;

    /** How much of the outgoing's own vocal stem a carried row may be, at zero lag: above this
     *  what was carried is the outgoing's voice, which is the one thing no passage may contain. */
    public static final double VOICE_CARRY_LIMIT = StemBridge.VOICE_CARRY_LIMIT;

    /** The floor a stem row has to be under to count as absent, dBFS — {@link
     *  DjEdit#SILENT_FRAME_DBFS} again, so "silent" means the same everywhere. */
    public static final double SILENT_DBFS = DjEdit.SILENT_FRAME_DBFS;

    /** The longest run of clamped samples the fusion may contain, ms: a splice may land on a
     *  peak, but a passage that is clipping for longer than this is a passage mixed too loud. */
    public static final double CLIP_RUN_MAX_MS = 2d;

    /** The largest share of the fusion window's sample pairs the clamp may have touched. */
    public static final double CLIP_SHARE_MAX = 0.01d;

    private StemFusion() {}

    // --- the outgoing's voice, where the junction is chosen -------------------

    /** Whether the outgoing track's voice is quiet over some whole beat at a position — the test
     *  the junction preference is written in ("the outgoing's voice should stop between phrases
     *  rather than mid-word"). */
    public interface VocalQuiet {
        /** True when a whole beat of {@code beatMs} inside {@code [atMs - beatMs, atMs + beatMs)}
         *  has the outgoing's voice at the floor. */
        boolean quietBeatAround(long atMs, double beatMs);
    }

    /** No measurement: nothing is preferred, the nearest bar line wins. */
    public static final VocalQuiet NO_VOICE_MEASUREMENT = (atMs, beatMs) -> false;

    /**
     * Whether the outgoing track's own <b>groove</b> — its separated drums and low end — is
     * playing around a position: the junction preference that decides whether a bar line is one
     * the fusion can carry the outgoing track's rhythm out of.
     *
     * <p>The pair the user heard is the reason this exists: its nearest bar line to the target is
     * a breakdown, so the fusion carried a pad where a groove should have been and the junction
     * measured −7 dB. A junction on a bar with a groove is the whole difference between "the
     * outgoing track hands over mid-song" and "the outgoing track's music stops and something
     * else starts".
     */
    public interface Groove {
        /** True when a whole beat of {@code beatMs} inside
         *  {@code [atMs - beatMs, atMs + 2*beatMs)} has the outgoing's drums + low end above the
         *  floor. */
        boolean presentAround(long atMs, double beatMs);
    }

    /** No measurement: nothing is preferred, and the quiet-vocal preference (then the nearest
     *  line) decides — which is what the junction search did before round 3. */
    public static final Groove NO_GROOVE_MEASUREMENT = (atMs, beatMs) -> false;

    /**
     * A {@link Groove} over the outgoing's separated drums and bass: the per-frame levels of their
     * sum, in the stem's own timeline — the same instrument {@link #quietnessOf} uses on the vocal
     * row, asked about the low end instead of the voice.
     *
     * <p>Both rows are summed rather than judged separately: a bar is one the fusion can carry the
     * outgoing's rhythm out of if the kick <em>or</em> the bass line is there (a breakdown often
     * keeps one), and a bar where neither is playing is the case this is looking for. The measure
     * is the <em>median</em> frame of a whole beat rather than its mean or its peak: the peak of a
     * kick-less bar can still be an attack from something else, and the mean of a sparse kick
     * pattern is the level between the kicks.
     *
     * @param drums     the outgoing's separated drum stem, {@code [channel][sample]}
     * @param bass      the outgoing's separated low end
     * @param rate      their sample rate
     * @param startMs   where those stems start in the outgoing track's own file, ms
     * @param floorDbfs the level a beat of the two rows has to sit above
     */
    public static Groove grooveOf(float[][] drums, float[][] bass, int rate, long startMs,
                                  double floorDbfs) {
        float[][] kit = add(drums, bass);
        if (kit == null || kit.length == 0 || kit[0] == null) return NO_GROOVE_MEASUREMENT;
        double[] levels = StemBridge.frameLevelsDb(kit, rate, kit[0].length / (double) rate);
        int frameMs = Math.max(1, (int) Math.round(StemBridge.FRAME_SEC * 1000d));
        return (atMs, beatMs) -> {
            if (levels.length == 0 || !(beatMs > 0d)) return false;
            int frames = (int) Math.max(1L, Math.round(beatMs / frameMs));
            // The beat that ends at `at`, the one that starts at it, and the one after it: a
            // groove is heard across a bar line, and a bar whose own first beat is a rest still
            // has the rest of itself.
            for (long start = atMs - Math.round(beatMs); start <= atMs + Math.round(beatMs);
                 start += frameMs) {
                int from = (int) Math.round((start - startMs) / (double) frameMs);
                if (from < 0 || from + frames > levels.length) continue;
                double[] beat = new double[frames];
                System.arraycopy(levels, from, beat, 0, frames);
                if (StemBridge.median(beat) > floorDbfs) return true;
            }
            return false;
        };
    }

    /**
     * A {@link VocalQuiet} over a separated vocal row: the per-frame levels of the stem, in the
     * stem's own timeline.
     *
     * @param vocals    the outgoing's separated vocal stem, {@code [channel][sample]}
     * @param rate      its sample rate
     * @param startMs   where that stem starts in the outgoing track's own file, ms
     * @param floorDbfs the level a frame has to be at or below to count as the voice being quiet
     */
    public static VocalQuiet quietnessOf(float[][] vocals, int rate, long startMs,
                                         double floorDbfs) {
        double[] levels = StemBridge.frameLevelsDb(vocals, rate,
                vocals != null && vocals.length > 0 && vocals[0] != null
                        ? vocals[0].length / (double) rate : 0d);
        int frameMs = Math.max(1, (int) Math.round(StemBridge.FRAME_SEC * 1000d));
        return (atMs, beatMs) -> {
            if (levels.length == 0 || !(beatMs > 0d)) return false;
            long beats = Math.max(1L, Math.round(beatMs / frameMs));
            // The beat that ends at `at` covers [at - beatMs, at); the one that starts at it
            // covers [at, at + beatMs). Both are tried: the cut is heard on both sides of itself
            // (the outgoing deck's last moment before it, the carried passage after it), and the
            // spec asks for "at least one beat" of quiet.
            for (long start = atMs - Math.round(beatMs); start <= atMs; start += frameMs) {
                int from = (int) Math.round((start - startMs) / (double) frameMs);
                if (from < 0 || from + beats > levels.length) continue;
                boolean quiet = true;
                for (int f = 0; f < beats; f++) {
                    if (levels[from + f] > floorDbfs) {
                        quiet = false;
                        break;
                    }
                }
                if (quiet) return true;
            }
            return false;
        };
    }

    /**
     * Whether the outgoing's melodic row, over the passage the fusion would carry, IS the
     * outgoing's voice — the measurement behind dropping the melodic carry.
     *
     * <p>Measured as the correlation with the separated vocal row at zero lag over the very
     * material that would be carried, not as a level: the outgoing track's voice is all over the
     * bars the material comes from, so a level says nothing, while a melodic row that is the
     * voice aligns with it almost exactly. The same instrument and the same limit {@link
     * StemBridge} uses for its carried bass ({@link StemBridge#VOICE_CARRY_LIMIT}).
     */
    public static double voiceAlignment(float[][] outgoingOther, float[][] outgoingVocals,
                                        int rate, long fromMs, long spanMs) {
        int from = (int) Math.max(0L, Math.round(fromMs * rate / 1000d));
        int frames = (int) Math.round(spanMs * rate / 1000d);
        float[][] other = slice(outgoingOther, from, frames);
        float[][] vocals = slice(outgoingVocals, from, frames);
        return Math.abs(StemBridge.alignmentAtZeroLag(other, vocals, rate, spanMs / 1000d, null));
    }

    // --- the plan -------------------------------------------------------------

    /** Everything the planner needs. The two bar-line arrays are ABSOLUTE file times (ms) of
     *  each track's own grid, computed by the caller from separated material — never the beat
     *  grid's phase, which is a different question and wrong three times in four
     *  ({@link StemGesture#barLines}). */
    public static final class Input {
        /** The outgoing track's file duration, probed by the renderer. */
        public final long aDurMs;
        /** The boundary's blend length at request time. */
        public final long blendMs;
        /** How long the incoming plays with its vocals out (the user's blend plus the head the
         *  deck skips before it). */
        public final long removalMs;
        /** Where the incoming deck would start playing without a fusion. */
        public final long contentStartMs;
        public final double aBeatMs;
        public final double aPhaseMs;
        public final double bBeatMs;
        public final double bPhaseMs;
        /** The ratio the incoming deck plays the file at. */
        public final double speed;
        /** The outgoing's bar lines, absolute ms, from its separated material. */
        public final double[] aBarLinesMs;
        /** The incoming's bar lines, absolute ms, from its separated head. */
        public final double[] bBarLinesMs;
        /** The outgoing's separated voice, for the junction preference; never null. */
        public final VocalQuiet quiet;
        /** The outgoing's separated drums + low end, for the junction preference; never null. */
        public final Groove groove;
        /** The outgoing's own body level and the drop of a passage below it, for the quiet-passage
         *  clause (round 5); never null, and {@link #NO_BODY_MEASUREMENT} when unmeasured. */
        public final BodyLevel body;
        /** Where the incoming track's voice first comes in, ms of its own file, or -1 when unknown
         *  — the measurement the intro skip is written in (round 5). */
        public final long firstVocalMs;
        /** Where the incoming track's own rhythm rows start playing, or
         *  {@link #NO_INCOMING_MEASUREMENT} — the measurement round 6's second pass added: the
         *  recede waits for the row that replaces A's (see {@link #rowStartMs}). */
        public final IncomingOn incoming;
        /**
         * Extra steps of hold at unity the CALLER wants, on top of whatever the design and the
         * measurement ask for (0 by default). The knob the renderer's retry loop turns: the hold is
         * the only lever this file has over whether the passage's pulse survives the incoming's
         * intro, and on a stem whose rows cannot be read (see {@link #rowStartMs}) the measurement
         * cannot ask for the extension itself — so the caller extends it one step at a time and
         * lets {@link #measure} judge each result, which is the reliable judge.
         *
         * <p>Never a way to break a clause: the steps are counted like the design's own, so a hold
         * the budget or the incoming's vocal-free window cannot pay for is refused by the same
         * messages ({@link #plan}'s own), and the renderer's loop simply stops extending when a plan
         * comes back invalid.
         */
        public final int extraHoldSteps;

        /**
         * When the outgoing's own rows leave the passage, measured off the outgoing's separated
         * material (round 25) — the one number the fusion's landing ({@link #entryMs}) and its
         * vocal gate ({@link Plan#voiceGateMs}) are both written around. {@link
         * #NO_EXIT_MEASUREMENT} for a caller that has no separation (every fixture that only asks
         * about the arithmetic), which answers the gesture's own end and reproduces the round-6
         * behaviour bit for bit.
         */
        public final OutgoingExit exit;

        /**
         * Whether the <b>incoming</b> track's own groove — its separated drums and low end — is
         * playing around a position in its file (round 33). The same instrument {@link #groove}
         * applies to the outgoing, asked of the other deck, and the clause it answers is the one the
         * listener's ear reported as 「34800 很完美」: a landing inside the incoming's own quiet
         * intro hands the passage a near-empty second track — measured on the pair under test,
         * {@code audio_huai}'s drums and low end sit at <b>−70 … −87 dBFS for its first 19 s</b>
         * (its section before them carries only the melodic row, −24…−32 dBFS) — so the fusion's
         * second half is one deck fading out instead of two songs crossing over. {@link
         * #NO_GROOVE_MEASUREMENT} (the default, and every fixture that only asks about the
         * arithmetic) keeps the landing where the coupling alone puts it, which is the behaviour
         * before this round.
         */
        public final Groove incomingGroove;

        public Input(long aDurMs, long blendMs, long removalMs, long contentStartMs,
                     double aBeatMs, double aPhaseMs, double bBeatMs, double bPhaseMs, double speed,
                     double[] aBarLinesMs, double[] bBarLinesMs, VocalQuiet quiet) {
            this(aDurMs, blendMs, removalMs, contentStartMs, aBeatMs, aPhaseMs, bBeatMs, bPhaseMs,
                    speed, aBarLinesMs, bBarLinesMs, quiet, NO_GROOVE_MEASUREMENT);
        }

        public Input(long aDurMs, long blendMs, long removalMs, long contentStartMs,
                     double aBeatMs, double aPhaseMs, double bBeatMs, double bPhaseMs, double speed,
                     double[] aBarLinesMs, double[] bBarLinesMs, VocalQuiet quiet, Groove groove) {
            this(aDurMs, blendMs, removalMs, contentStartMs, aBeatMs, aPhaseMs, bBeatMs, bPhaseMs,
                    speed, aBarLinesMs, bBarLinesMs, quiet, groove, NO_BODY_MEASUREMENT, -1L);
        }

        public Input(long aDurMs, long blendMs, long removalMs, long contentStartMs,
                     double aBeatMs, double aPhaseMs, double bBeatMs, double bPhaseMs, double speed,
                     double[] aBarLinesMs, double[] bBarLinesMs, VocalQuiet quiet, Groove groove,
                     BodyLevel body, long firstVocalMs) {
            this(aDurMs, blendMs, removalMs, contentStartMs, aBeatMs, aPhaseMs, bBeatMs, bPhaseMs,
                    speed, aBarLinesMs, bBarLinesMs, quiet, groove, body, firstVocalMs,
                    NO_INCOMING_MEASUREMENT);
        }

        public Input(long aDurMs, long blendMs, long removalMs, long contentStartMs,
                     double aBeatMs, double aPhaseMs, double bBeatMs, double bPhaseMs, double speed,
                     double[] aBarLinesMs, double[] bBarLinesMs, VocalQuiet quiet, Groove groove,
                     BodyLevel body, long firstVocalMs, IncomingOn incoming) {
            this(aDurMs, blendMs, removalMs, contentStartMs, aBeatMs, aPhaseMs, bBeatMs, bPhaseMs,
                    speed, aBarLinesMs, bBarLinesMs, quiet, groove, body, firstVocalMs, incoming, 0);
        }

        public Input(long aDurMs, long blendMs, long removalMs, long contentStartMs,
                     double aBeatMs, double aPhaseMs, double bBeatMs, double bPhaseMs, double speed,
                     double[] aBarLinesMs, double[] bBarLinesMs, VocalQuiet quiet, Groove groove,
                     BodyLevel body, long firstVocalMs, IncomingOn incoming, int extraHoldSteps) {
            this(aDurMs, blendMs, removalMs, contentStartMs, aBeatMs, aPhaseMs, bBeatMs, bPhaseMs,
                    speed, aBarLinesMs, bBarLinesMs, quiet, groove, body, firstVocalMs, incoming,
                    extraHoldSteps, NO_EXIT_MEASUREMENT);
        }

        public Input(long aDurMs, long blendMs, long removalMs, long contentStartMs,
                     double aBeatMs, double aPhaseMs, double bBeatMs, double bPhaseMs, double speed,
                     double[] aBarLinesMs, double[] bBarLinesMs, VocalQuiet quiet, Groove groove,
                     BodyLevel body, long firstVocalMs, IncomingOn incoming, int extraHoldSteps,
                     OutgoingExit exit) {
            this(aDurMs, blendMs, removalMs, contentStartMs, aBeatMs, aPhaseMs, bBeatMs, bPhaseMs,
                    speed, aBarLinesMs, bBarLinesMs, quiet, groove, body, firstVocalMs, incoming,
                    extraHoldSteps, exit, NO_GROOVE_MEASUREMENT);
        }

        /** Round 33: the same input with the <b>incoming's</b> own groove measured — see {@link
         *  #incomingGroove}. The renderer's own call sites use this one; every fixture that only asks
         *  about the arithmetic keeps the overload above and the pre-round-33 landing. */
        public Input(long aDurMs, long blendMs, long removalMs, long contentStartMs,
                     double aBeatMs, double aPhaseMs, double bBeatMs, double bPhaseMs, double speed,
                     double[] aBarLinesMs, double[] bBarLinesMs, VocalQuiet quiet, Groove groove,
                     BodyLevel body, long firstVocalMs, IncomingOn incoming, int extraHoldSteps,
                     OutgoingExit exit, Groove incomingGroove) {
            this.exit = exit == null ? NO_EXIT_MEASUREMENT : exit;
            this.incomingGroove = incomingGroove == null ? NO_GROOVE_MEASUREMENT : incomingGroove;
            this.aDurMs = aDurMs;
            this.blendMs = blendMs;
            this.removalMs = removalMs;
            this.contentStartMs = contentStartMs;
            this.aBeatMs = aBeatMs;
            this.aPhaseMs = aPhaseMs;
            this.bBeatMs = bBeatMs;
            this.bPhaseMs = bPhaseMs;
            this.speed = speed > 0d ? speed : 1d;
            this.aBarLinesMs = aBarLinesMs;
            this.bBarLinesMs = bBarLinesMs;
            this.quiet = quiet == null ? NO_VOICE_MEASUREMENT : quiet;
            this.groove = groove == null ? NO_GROOVE_MEASUREMENT : groove;
            this.body = body == null ? NO_BODY_MEASUREMENT : body;
            this.incoming = incoming == null ? NO_INCOMING_MEASUREMENT : incoming;
            this.extraHoldSteps = Math.max(0, extraHoldSteps);
            this.firstVocalMs = firstVocalMs;
        }
    }

    /**
     * What the landing and the vocal gate were both derived from: <b>one measured instant</b> — when
     * the outgoing track's own carried rows left the passage ({@link Input#exit}) — and the numbers
     * that follow from it. The user's rule, in their own words: 「ai 推断每首歌的过渡落点 … 当前一首歌
     * 到达声音很小的部分时，就接入歌词，此过程可以让 ai 判断」.
     *
     * <p>Read the two misses as the honest report of a coupling that could not be met in full: at
     * most one of {@link #residualGapMs} (the incoming's voice is that much later than the departure
     * — its own material simply is not there yet) and {@link #heldMs} (the landing this window can
     * afford puts the voice before the departure, so that much of the incoming's own singing is held
     * out rather than stacked on the outgoing's material) is non-zero, and both are zero exactly
     * when the coupling was met.
     *
     * <p>⚠️ <b>Round 30: the departure is still the anchor and is no longer the instant the voice
     * arrives.</b> The listener, after the round-25 build: 「歌词要稍微早一点」. The gate is now the
     * departure led by {@link #VOICE_GATE_LEAD_BARS} bars of the outgoing's grid and floored where
     * the file's own gesture has taken the outgoing's rows {@link #VOICE_GATE_FLOOR_DB} under unity
     * ({@link #ledFromMs} and {@link #floorMs} carry both, and {@link #departureInFileMs} is the
     * "before" of this round on every pair). The landing moves with it — one number still decides
     * both, which is the round-25 rule that the user's own pair proved.
     */
    public static final class Coupling {
        /** The outgoing's own file ms at which its carried rows left the passage. */
        public final long departureMs;
        /** The same instant as an offset from {@link Plan#entryMs}, in the incoming's file ms. */
        public final long departureInFileMs;
        /** True when {@link #departureMs} is the passage's own measured envelope, false when it is
         *  the gesture's own end (the fallback the log names). */
        public final boolean measured;
        /** The instant in the incoming's file at which its voice is back at <b>exactly unity</b> —
         *  the gate the render bakes in, and the number the boundary reads its own vocal return
         *  from.
         *
         *  <p>⚠️ <b>Round 30 moved it earlier on purpose, and the invariant survived the move.</b>
         *  Round 25 put it at the departure to the millisecond ("never before the departure: no two
         *  voices"); the listener's own answer to that build is 「歌词要稍微早一点」, so it is now the
         *  departure <em>led</em> by {@link #VOICE_GATE_LEAD_BARS} bars of the outgoing's grid and
         *  <em>floored</em> at {@link #floorMs} — the instant the file's own gesture has already
         *  taken the outgoing's carried rows {@link #VOICE_GATE_FLOOR_DB} under unity, and never
         *  earlier than the deck-level hand-over that removes the outgoing track's own voice. So the
         *  promise is unchanged in content ("the outgoing is not still clearly audible when the
         *  incoming's voice begins") and earlier in time, which is exactly what was asked for. */
        public final long voiceGateMs;
        /** The incoming's voice is heard this much later than the departure, ms. */
        public final long residualGapMs;
        /** The incoming's own singing is held out for this much because its voice arrives before
         *  the departure at the only landings this pair's window can afford, ms. */
        public final long heldMs;
        /** What round 21's rule would have taken for this pair, ms, or -1 when it would take none.
         *  Reported as the "before" of the coupling and read by nothing else. */
        public final long uncoupledEntryMs;
        /** Round 30: where the lead alone would have put the departure in the incoming's file, ms
         *  from {@link Plan#entryMs} — {@link #VOICE_GATE_LEAD_BARS} bars of the outgoing's grid
         *  before the measured departure ({@link #departureInFileMs}, which is where the voice
         *  arrived before this round). -1 when the plan has no landing. */
        public final long ledFromMs;
        /** Round 30: the file's own bound that lead was clamped against, ms from the entry
         *  ({@link #voiceFloorMs}) — the instant the gesture has taken the outgoing's carried rows
         *  {@link #VOICE_GATE_FLOOR_DB} under unity, or the deck-level hand-over, whichever is
         *  later. The gate is never earlier than it. -1 when the plan has no landing. */
        public final long floorMs;

        Coupling(long departureMs, long departureInFileMs, boolean measured, long voiceGateMs,
                 long residualGapMs, long heldMs, long uncoupledEntryMs) {
            this(departureMs, departureInFileMs, measured, voiceGateMs, residualGapMs, heldMs,
                    uncoupledEntryMs, -1L, -1L);
        }

        Coupling(long departureMs, long departureInFileMs, boolean measured, long voiceGateMs,
                 long residualGapMs, long heldMs, long uncoupledEntryMs, long ledFromMs,
                 long floorMs) {
            this.departureMs = departureMs;
            this.departureInFileMs = departureInFileMs;
            this.measured = measured;
            this.voiceGateMs = voiceGateMs;
            this.residualGapMs = residualGapMs;
            this.heldMs = heldMs;
            this.uncoupledEntryMs = uncoupledEntryMs;
            this.ledFromMs = ledFromMs;
            this.floorMs = floorMs;
        }

        /** No measurement and nothing derived: an invalid plan's own answer. */
        static final Coupling NONE = new Coupling(-1L, -1L, false, -1L, 0L, 0L, -1L);

        /** The coupling's own evidence, in one sentence: the measured instant, how it was measured,
         *  and where the incoming's voice actually lands against it. */
        public String describe(long entryMs, long firstVocalMs, long removalMs, long windowMs) {
            if (departureMs < 0L) return "no departure was measured for this pair";
            StringBuilder sb = new StringBuilder(240);
            sb.append(String.format(Locale.US,
                    "the outgoing's own rows leave the passage at %dms of its own file, %s",
                    departureMs, !measured
                            ? "NOT measured, so the gesture's own end is the answer"
                            : departureInFileMs >= windowMs
                                    ? String.format(Locale.US, "measured on the rows themselves —"
                                            + " they never get %.0f dB under the passage's own body"
                                            + " inside it, so the gesture's own end is the answer and"
                                            + " it is the same instant", OUTGOING_EXIT_DB)
                                    : String.format(Locale.US, "measured on the rows themselves (a"
                                            + " full second %.0f dB under the passage's own body)",
                                            OUTGOING_EXIT_DB)));
            sb.append(String.format(Locale.US, " — %dms into this file from the entry at %dms",
                    departureInFileMs, entryMs));
            if (firstVocalMs > 0L) {
                long arrival = entryMs + departureInFileMs;
                sb.append(String.format(Locale.US,
                        "; the incoming's own voice starts at %dms of its file, i.e. %dms %s the"
                                + " departure",
                        firstVocalMs, Math.abs(firstVocalMs - arrival),
                        firstVocalMs >= arrival ? "after" : "before"));
            }
            if (residualGapMs > 0L) {
                sb.append(String.format(Locale.US, ", and it is first HEARD %dms later than the"
                        + " instant this round places the voice on (the coupling's own landing is not"
                        + " one this window can afford)", residualGapMs));
            } else if (heldMs > 0L) {
                sb.append(String.format(Locale.US, ", and the landing this window can afford puts it"
                        + " there that much EARLY, so the gate holds %dms of its own singing out"
                        + " rather than stacking two voices", heldMs));
            } else if (firstVocalMs > 0L) {
                sb.append(", which is the departure to the millisecond (the landing was chosen so)");
            }
            if (voiceGateMs > 0L) {
                sb.append(String.format(Locale.US, "; its voice is back at unity by %dms of this"
                                + " file (the window alone would have held it out to %dms)",
                        voiceGateMs, removalMs));
            }
            if (ledFromMs >= 0L && floorMs >= 0L) {
                sb.append(String.format(Locale.US, "; the voice's lead this round (%d bar%s of the"
                                + " outgoing's own grid): the departure is %dms into this file as the"
                                + " material reads it, one bar earlier is %dms, and the bound on that"
                                + " lead is %dms — the material's own %.0f dB reading (%.0f dB under"
                                + " the passage's body) when it has one inside the passage, and"
                                + " otherwise the file's own gesture at %.0f dB under unity — so the"
                                + " voice is placed %dms after the deck starts, %dms earlier than"
                                + " the departure the material measured",
                        VOICE_GATE_LEAD_BARS, VOICE_GATE_LEAD_BARS == 1 ? "" : "s",
                        departureInFileMs, ledFromMs, floorMs, VOICE_GATE_FLOOR_DB, OUTGOING_EXIT_DB,
                        VOICE_GATE_FLOOR_DB, Math.max(ledFromMs, floorMs),
                        departureInFileMs - Math.max(ledFromMs, floorMs)));
            }
            if (uncoupledEntryMs > 0L) {
                sb.append(String.format(Locale.US, "; the landing the round-21 rule would have taken"
                        + " on this pair was %dms", uncoupledEntryMs));
            }
            return sb.toString();
        }
    }

    /** What a fusion would be, and whether it fits: the anchors the render bakes into the file
     *  name, plus where the outgoing's tail has to be separated for it. */
    public static final class Plan {
        /** True when every validity clause held — otherwise nothing about this plan may be used
         *  and the render is exactly today's edit. */
        public final boolean valid;
        /** Why not, for the log. Empty when {@link #valid}. */
        public final String reason;

        /** True when this is a <b>SLAM</b> (round 5): the two grids are in no relation, so the
         *  passage is ONE bar of the incoming's grid and every element changes hands on the same
         *  line — the gesture a DJ's slam mix makes, needing no beat matching at all. On a slam
         *  {@link #swapMs} and {@link #bassMs} are the same instant and {@link #windowMs} is one
         *  step; its name carries the same {@code -e/-j/-f} markers as a fusion's, and the signature
         *  a reader can rely on is {@code fusionEndMs - entryMs} being one bar instead of three. */
        public final boolean slam;

        /** One bar of each grid, ms. */
        public final double aBarMs;
        public final double bBarMs;
        /** One step of the table: {@code p} bars of the incoming's grid for a relation, one bar for
         *  a slam — the interval every element swap lands on. */
        public final double barStepMs;
        /** The ratio the outgoing's carried material is read at (see {@link Relation#stretch}); for
         *  a slam and a unison pair it is the deck's own {@link #speed}. */
        public final double stretch;
        /** The relation the two grids are in, or null for a slam. */
        public final Relation relation;

        /** The outgoing's bar line its deck is cut on, its own file ms. */
        public final long junctionMs;
        /** The incoming's bar line its deck starts playing on — where the fusion begins, its own
         *  file ms. */
        public final long entryMs;
        /** The line everything changes hands on: {@code entryMs + holdSteps*barStep} for a fusion,
         *  and {@code entryMs + }{@link #SLAM_STEPS}{@code *barStep} for a slam (whose hold IS the
         *  slam's length). The outgoing's drums out and the incoming's in. */
        public final long swapMs;
        /** {@code entryMs + 2*barStep} on a fusion: the low end changes hands. The same instant as
         *  {@link #swapMs} on a slam. */
        public final long bassMs;
        /** {@code entryMs + windowMs}: the last of the outgoing's material is gone — on a slam, where
         *  the {@link #CUT_MS} splice that ends on {@link #swapMs} ends. */
        public final long fusionEndMs;
        /** Where the incoming's own bed has arrived at unity: {@code entryMs + BED_FADE_BARS} bars of
         *  the incoming's own grid — a different instant from the outgoing's hold for every gesture
         *  except a one-bar slam (see the constructor), and so NOT {@link #swapMs} on this round's
         *  three-bar slam. */
        public final long bedFadeMs;

        /** The fusion window's length in the file, ms ({@code 3} steps of the table, or
         *  {@link #SLAM_STEPS} on a slam — plus the splice for a slam). */
        public final long windowMs;
        /** The outgoing's own timeline the carried passage covers, ms
         *  ({@code steps*barStep/stretch + CUT/stretch}), rounded up: the material A's rows occupy
         *  in the file, from the junction to its last cut. */
        public final long sourceSpanMs;

        /** Where the outgoing's material is taken from in its own file (= {@link #junctionMs}). */
        public final long sourceFromMs;
        /** Where the outgoing's tail has to be separated from, ms, and how much of it: the search
         *  band's own lead on either side of the target plus the whole passage. In the plan's own
         *  timeline this is the window for one {@code separateTail} call. */
        public final long materialFromMs;
        public final long materialWindowMs;
        /** How far forward of the target the junction was searched, ms ({@link
         *  #JUNCTION_SEARCH_BARS} bars of the outgoing's grid, narrowed to what
         *  {@link #FUSION_TAIL_MAX_MS} had left) and how far back ({@link #QUIET_SEARCH_MAX_MS},
         *  narrowed the same way) for a line the outgoing is still playing on. */
        public final long searchBandMs;
        public final long searchBackMs;
        /** {@code junctionMs} minus the target ({@code aDur − 250 − blendMs}), ms: negative when
         *  the deck is cut earlier than distance alone would put it, which lengthens the ramp by
         *  the same amount. Reported, never a requirement. */
        public final long junctionShiftMs;

        /** The ratio the incoming deck plays this file at — and, for a unison pair and a slam, the
         *  ratio the outgoing's carried material was stretched by on the way in, which is the same
         *  number by construction (see {@link #carried}): playback at this ratio returns the carry
         *  to the outgoing track's own tempo and pitch.
         *
         *  <p>A field because it is what a log line has to quote, and quoting something else is
         *  how the round-3 line came to print a ratio of 1.4649 for a pair whose deck played at
         *  x1.0: {@code windowMs / sourceSpanMs} is <em>three bars of the incoming's grid over two
         *  bars plus a splice of the outgoing's</em>, a bar-count ratio that is above 1 on every
         *  pair and means nothing. */
        public final double speed;

        /** How far the two grids are apart as heard, as a fraction of the incoming's beat
         *  ({@link #lockError}); at most {@link #LOCK_TOLERANCE} on a unison pair, and on a
         *  relative one the grid is one the two share ({@link #relation}). */
        public final double lockError;

        /** The junction's own bar was at least a beat without the outgoing's voice. */
        public final boolean quietAtJunction;
        /** The junction's own bar had the outgoing's drums or low end playing — the preference that
         *  decides whether the fusion carries a groove or a pad. */
        public final boolean grooveAtJunction;
        /** Round 5: the junction has the outgoing track's own body level (within
         *  {@link #QUIET_PASSAGE_DB}), so the passage is the track playing rather than its fade.
         *  False when the body was measured and no line in the band had it — which refuses. */
        public final boolean bodyLevelAtJunction;
        /** True when the body/passage measurement was made at all; false makes the clause decline
         *  (every line usable), which is what a caller with no decode gets. */
        public final boolean bodyMeasured;
        /** The outgoing's body level and the chosen passage's own level, dBFS (NaN unmeasured), and
         *  how far the passage sits below the body, dB. */
        public final double bodyLevelDb;
        public final float junctionPassageLevelDb;
        public final float junctionPassageDropDb;
        /** The entry was the phase-matched bar line rather than the fallback. */
        public final boolean phaseMatched;
        /** The entry's lines came from the incoming's <b>beat grid</b> rather than from the bar
         *  lines the caller measured ({@link #entryLines}): the beats are measured, the bar
         *  grouping is a guess. Reported so a caller whose own lines were unusable — empty, short,
         *  or in the wrong unit — finds out from the log rather than from a refusal. */
        public final boolean entryFromBeatGrid;
        /** The worst wall-clock phase difference at the seam, ms. */
        public final double phaseErrorMs;
        /** How much of the incoming's intro the deck skips, ms — how much later than its own
         *  content start its first sample is, 0 when it starts where it always did (round 5's
         *  「词接词」, with {@link #firstVocalMs} it was skipped for). */
        public final long skippedIntroMs;
        /** Where the incoming's voice first comes in, ms of its own file, or -1 when unknown. */
        public final long firstVocalMs;
        /** Round 25: the outgoing's own departure inside this passage, and everything the landing
         *  and the vocal gate were derived from it — see {@link Coupling}. Never null.
         *
         *  <p>⚠️ It replaces round 21's {@code declinedIntroMs}, which was the landing the intro
         *  skip wanted but could not take. The coupling has no declined landing: a landing the
         *  window cannot pay for is <em>pulled back</em> to the last one it can afford and the
         *  residual gap is reported, because the alternative (giving up the skip entirely) is the
         *  long instrumental-with-the-words-held-out the user's own report is about. */
        public final Coupling coupling;

        /** How long the incoming track plays with its vocals out — the window a fusion has to fit
         *  inside, and what the ceiling on a landing is measured with. Carried here for the log. */
        public final long removalMs;

        /** Round 6's gesture instants, in the file's own timeline: the outgoing's rows hold at
         *  unity until {@link #holdEndMs}, its drums reach the floor at {@link #drumsEndMs} and its
         *  low end (with its melodic row) at {@link #lowEndEndMs} = {@link #fusionEndMs}; the
         *  incoming's drums and low end rise from {@link #arriveStartMs} (its own drums' swap) to
         *  unity at {@link #arriveEndMs}. All equal-power, all monotone. */
        public final long holdEndMs;
        public final long drumsEndMs;
        public final long lowEndEndMs;
        public final long arriveStartMs;
        public final long arriveEndMs;
        /** Round 6's second pass: true when the hold at unity was extended because the incoming's
         *  own rhythm rows were not playing yet — the recede waits for the row that replaces it
         *  (see {@link #rowStartMs}). */
        public final boolean holdForIncoming;
        /** The measured instants behind {@link #holdForIncoming}, ms of the incoming's own file
         *  (-1 when nothing was measured), and the passage's own end for scale. */
        public final long incomingDrumsMs;
        public final long incomingBassMs;

        Plan(boolean valid, String reason, boolean slam, double aBarMs, double bBarMs,
             double barStepMs, double stretch, Relation relation, long junctionMs, long entryMs,
             long swapMs, long bassMs, long fusionEndMs, long windowMs, long sourceSpanMs,
             long materialFromMs, long materialWindowMs, long searchBandMs, long searchBackMs,
             long junctionShiftMs, double lockError, double speed, boolean quietAtJunction,
             boolean grooveAtJunction, boolean bodyLevelAtJunction, boolean bodyMeasured,
             double bodyLevelDb, double passageLevelDb, double passageDropDb, boolean phaseMatched,
             double phaseErrorMs, long skippedIntroMs, long firstVocalMs, long holdEndMs,
             long drumsEndMs, long lowEndEndMs, long arriveStartMs, long arriveEndMs,
             boolean entryFromBeatGrid, boolean holdForIncoming, long incomingDrumsMs,
             long incomingBassMs, Coupling coupling, long removalMs) {
            this.valid = valid;
            this.reason = reason == null ? "" : reason;
            this.slam = slam;
            this.aBarMs = aBarMs;
            this.bBarMs = bBarMs;
            this.barStepMs = barStepMs;
            this.stretch = stretch > 0d ? stretch : 1d;
            this.relation = relation;
            this.junctionMs = junctionMs;
            this.entryMs = entryMs;
            this.swapMs = swapMs;
            this.bassMs = bassMs;
            this.fusionEndMs = fusionEndMs;
            // The bed's rise is one bar of the INCOMING's own grid (BED_FADE_BARS), which is a
            // different instant from the outgoing's hold for every gesture except a one-step slam.
            this.bedFadeMs = valid && bBarMs > 0d
                    ? entryMs + (long) BED_FADE_BARS * Math.round(bBarMs) : -1L;
            this.windowMs = windowMs;
            this.sourceSpanMs = sourceSpanMs;
            this.sourceFromMs = junctionMs;
            this.materialFromMs = materialFromMs;
            this.materialWindowMs = materialWindowMs;
            this.searchBandMs = searchBandMs;
            this.searchBackMs = searchBackMs;
            this.junctionShiftMs = junctionShiftMs;
            this.lockError = lockError;
            this.speed = speed > 0d ? speed : 1d;
            this.quietAtJunction = quietAtJunction;
            this.grooveAtJunction = grooveAtJunction;
            this.bodyLevelAtJunction = bodyLevelAtJunction;
            this.bodyMeasured = bodyMeasured;
            this.bodyLevelDb = bodyLevelDb;
            this.junctionPassageLevelDb = (float) passageLevelDb;
            this.junctionPassageDropDb = (float) passageDropDb;
            this.phaseMatched = phaseMatched;
            this.phaseErrorMs = phaseErrorMs;
            this.skippedIntroMs = skippedIntroMs;
            this.firstVocalMs = firstVocalMs;
            this.coupling = coupling == null ? Coupling.NONE : coupling;
            this.removalMs = removalMs;
            this.holdEndMs = holdEndMs;
            this.drumsEndMs = drumsEndMs;
            this.lowEndEndMs = lowEndEndMs;
            this.arriveStartMs = arriveStartMs;
            this.arriveEndMs = arriveEndMs;
            this.entryFromBeatGrid = entryFromBeatGrid;
            this.holdForIncoming = holdForIncoming;
            this.incomingDrumsMs = incomingDrumsMs;
            this.incomingBassMs = incomingBassMs;
        }

        /** How many 80 ms splices the table contains: three at {@link #swapMs} (the outgoing's
         *  drums out and the incoming's in, one pair) and three at {@link #bassMs} — which on a slam
         *  is the same instant, so a slam's six splices are all on its one line. The incoming's bed
         *  is deliberately NOT one of them — it is the one arrival the design ramps, over
         *  {@code BED_FADE_BARS} bar, and it is counted separately in {@link #describe()}. */
        public int splices() {
            return slam ? 2 * 3 : 0;
        }

        /** The outgoing's own recede, ms, as the report quotes it: [the hold at unity, the drums'
         *  fade, the low end's fade]. Round 6 replaced the 80 ms splices with these (see
         *  {@link #A_HOLD_STEPS}); a slam keeps its one cut line and reports none of them. */
        public long[] recedeMs() {
            return slam ? new long[0]
                    : new long[]{holdEndMs - entryMs, drumsEndMs - holdEndMs, lowEndEndMs - holdEndMs};
        }

        /** The evidence, in one line. */
        public String describe() {
            if (!valid) return "no fusion (" + reason + ")";
            long[] recede = recedeMs();
            String shapes = slam
                    ? "A's rows cut on its one line (a slam is the cut gesture)"
                    : String.format(Locale.US, "A recedes, never cut: %dms at unity, then its drums"
                            + " fade over %dms and its low end and melodic row over %dms; the"
                            + " incoming's drums and low end rise over %dms from %dms%s",
                    recede.length > 0 ? recede[0] : 0L, recede.length > 1 ? recede[1] : 0L,
                    recede.length > 2 ? recede[2] : 0L, arriveEndMs - arriveStartMs, arriveStartMs,
                    // ⚠️ A one-step low end fade is not the design's shape: it is what the gesture
                    // gave up so the junction search could keep a band at least as wide as one of
                    // the outgoing's own bars (see `plan`). Said out loud, because the listener
                    // hears it and a log that does not mention it reads as the design's own.
                    !slam && lowEndEndMs - holdEndMs < (long) A_LOW_END_FADE_STEPS
                            * Math.max(1L, Math.round(barStepMs))
                            ? " (its low end's fade is ONE step: the passage and the junction"
                                    + " search's own band are paid for out of the same cost cap)"
                            : "");
            String head = slam
                    ? String.format(Locale.US, "SLAM (the grids are in no relation, so nothing is"
                            + " beat-matched: %d bars of the incoming's grid, every element changing"
                            + " hands on the same line at %dms)", SLAM_STEPS, swapMs)
                    : String.format(Locale.US, "fusion %s: drums swap at %dms, low end at %dms, all"
                            + " A gone at %dms (%dms = %d steps of %.0fms)",
                    relation == null ? "(no relation)" : relation.describe(), swapMs, bassMs,
                    fusionEndMs, windowMs, slam ? SLAM_STEPS : FUSION_BARS, barStepMs);
            return String.format(Locale.US,
                    "%s; %s; the outgoing deck is cut on its bar line at %dms (%+dms from the"
                            + " distance alone; the search"
                            + " covered %dms back and %dms forward), B starts at %dms%s (%.0f ms of"
                            + " wall-clock phase difference %s)%s%s; B's bed fades in over one bar, from"
                            + " %dms to unity at %dms; the junction's bar is %s%s; the deck plays"
                            + " this file at x%.4f and the outgoing's carry was read at x%.4f inside"
                            + " it, so %dms of A taken from %dms fills %.0fms of the file's %dms"
                            + " window, separated over %dms from %dms; %s",
                    head, shapes, junctionMs, junctionShiftMs, searchBackMs, searchBandMs, entryMs,
                    skippedIntroMs > 0L ? String.format(Locale.US, " (skipping %dms of its intro:"
                            + " its voice first comes in at %dms of its file, %dms %s the deck"
                            + " starts)", skippedIntroMs, firstVocalMs,
                            Math.abs(firstVocalMs - entryMs),
                            // ⚠️ Round 25: the landing can be LATER than the incoming's own voice's
                            // onset (the coupling's target was below the track's own start, so the
                            // deck starts on its first grid line and the gate holds the voice) —
                            // "after" with a negative number is not a sentence.
                            firstVocalMs >= entryMs ? "after" : "BEFORE")
                            : firstVocalMs > 0L ? String.format(Locale.US, " (its voice first comes"
                            + " in at %dms of its file and this landing keeps its intro: the"
                            + " coupling's own landing would be %dms, and %s)",
                            firstVocalMs, firstVocalMs - coupling.departureInFileMs,
                            coupling.departureInFileMs > firstVocalMs
                                    ? "the window this pair can afford cannot start a track earlier"
                                            + " than it does"
                                    : "the runway before its voice is what is left of it")
                            : "",
                    phaseErrorMs, phaseMatched ? "matched to A's grid"
                            : "NOT matched (the plain bar line)",
                    holdForIncoming
                            ? String.format(Locale.US, ", and its rows wait: the hold is %dms because"
                                    + " the incoming's own drums are not playing until %dms and its"
                                    + " low end until %dms of ITS file (a recede that started before"
                                    + " them would hand the pulse to nobody)",
                            recede.length > 0 ? recede[0] : 0L, incomingDrumsMs, incomingBassMs)
                            : "",
                    entryFromBeatGrid ? " [⚠️ from the incoming's BEAT grid: the bar lines it came"
                            + " with held none in the entry's window, so the beats are measured and"
                            + " the bar grouping is a guess]" : "",
                    entryMs, bedFadeMs,
                    bodyMeasured
                            ? String.format(Locale.US, "at the track's own body level (%.2f dBFS"
                                    + " against a body of %.2f)", junctionPassageLevelDb, bodyLevelDb)
                            : "not measured against a body",
                    grooveAtJunction ? " and carrying its groove" : "",
                    speed, stretch, sourceSpanMs, sourceFromMs, sourceSpanMs * stretch, windowMs,
                    materialWindowMs, materialFromMs, coupling.describe(entryMs, firstVocalMs,
                            removalMs, windowMs));
        }
    }

    private static Plan invalid(String reason, double aBarMs, double bBarMs) {
        return invalid(reason, aBarMs, bBarMs, Double.NaN);
    }

    private static Plan invalid(String reason, double aBarMs, double bBarMs, double lockError) {
        return new Plan(false, reason, false, aBarMs, bBarMs, bBarMs, 1d, null, -1L, -1L, -1L, -1L,
                -1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, lockError, 1d, false, false, false, false,
                Double.NaN, Double.NaN, Double.NaN, false, Double.NaN, 0L, -1L, -1L, -1L, -1L, -1L,
                -1L, false, false, -1L, -1L, Coupling.NONE, 0L);
    }

    /**
     * Whether a fusion is worth decoding the outgoing track's tail for, and where to look.
     *
     * <p>The plan's own anchors need the outgoing's bar lines, and those come from its own audio
     * (the low-end extension every bar-line use in this codebase has instead of a structure pass).
     * What they do <em>not</em> need is the separated stems: the mix's own low band answers the
     * question for a decode, and a decode costs a fraction of a separation. Getting the junction
     * this way is what keeps the window that <em>is</em> separated — the expensive half — at
     * {@code sourceSpan + A_TAIL_SLACK_MS}, the size the spec's validity clause is written for,
     * instead of growing it by a bar of uncertainty for every pair.
     *
     * <p>A false answer means the fusion is not attempted at all and the render is today's edit.
     */
    public static boolean possible(long aDurMs, long blendMs, long removalMs, long contentStartMs,
                                   double aBeatMs, double bBeatMs, double speed) {
        return refusal(aDurMs, blendMs, removalMs, contentStartMs, aBeatMs, bBeatMs, speed) == null;
    }

    /**
     * The clause a fusion is refused by before anything is decoded, or null when one is worth
     * decoding the outgoing track's tail for.
     *
     * <p>The reason is the log's, and it names the clause: "the two grids cannot carry one" is not
     * something a later reader can act on, where "the two grids do not lock (49.2% out where the
     * tolerance is 2%)" is.
     *
     * <p>Every clause here is decidable from the request alone, which is which ones they are: the
     * two grids, the tempo lock, the cost bound on the window that would be separated, and the
     * outgoing file being long enough for a junction to exist in it. The ones that need the
     * incoming's own start or its content (where the deck begins, whether the whole fusion fits
     * inside the vocal-free window) are {@link #plan}'s.
     */
    public static String refusal(long aDurMs, long blendMs, long removalMs, long contentStartMs,
                                 double aBeatMs, double bBeatMs, double speed) {
        if (!(aBeatMs > 0d)) return "the outgoing track has no beat grid";
        if (!(bBeatMs > 0d)) return "the incoming track has no beat grid";
        if (!(speed > 0d)) return "the ratio the incoming deck plays at is not known";
        if (!(aDurMs > 0d)) return "the outgoing track's length could not be read";
        if (!(blendMs > 0L)) return "the boundary's blend length is not known";
        if (contentStartMs < 0L) return "the incoming deck's own start is not known";
        // ⚠️ Round 5: a pair in no relation is no longer refused — it gets a SLAM ({@link
        // #SLAM_STEPS} bars of the
        // incoming's grid, no beat matching), so this clause only refuses the pair whose grids
        // cannot be read at all.
        Relation relation = relationOf(aBeatMs, bBeatMs, speed);
        double stepMs = relation == null ? bBeatMs * BEATS_PER_BAR : relation.barStepMs(bBeatMs);
        double stretch = relation == null ? speed : relation.stretch(aBeatMs, bBeatMs);
        long cap = tailMaxMs(relation);
        // ⚠️ The shape the pair's own step leaves room for, not the full gesture's three steps:
        // {@link #plan} shortens the gesture when the step is long (see {@link #shapeFor}), so a
        // clause written with the full shape's arithmetic would refuse pairs the planner fuses — a
        // 4 s bar does exactly that: the full gesture needs 18 s of A's file (over the 12 s cap) and
        // the planner answers with one step of hold and a one-step low end fade, 8080 ms, which
        // fits. The two numbers are the same call so they cannot drift apart again.
        int[] shape = shapeFor(stepMs, stretch, cap);
        int steps = relation == null ? SLAM_STEPS : shape[0] + shape[1];
        long window = (long) Math.ceil((steps * stepMs + CUT_MS)
                / (stretch > 0d ? stretch : 1d)) + 2L * A_TAIL_SLACK_MS;
        if (!(window <= cap)) {
            return String.format(Locale.US,
                    "the passage is %.1fms of the incoming's file (%d steps of %.1fms), so the"
                            + " material A's rows need from A is %dms — over the %.0fms one render"
                            + " may separate before the junction search's own band is paid for",
                    steps * stepMs, steps, stepMs, window, (double) cap);
        }
        if (!(Math.round(aDurMs - CUT_BACK_MS - blendMs) > 0L)) {
            return String.format(Locale.US,
                    "the outgoing track ends %dms in, so no bar line of it is a whole blend back"
                            + " from its own end",
                    Math.max(0L, Math.round(aDurMs - CUT_BACK_MS)));
        }
        return null;
    }

    /**
     * A track's own bar lines from its <b>beat grid</b>: {@code phase + k*beatsPerBar*period},
     * absolute ms, over {@code [fromMs, fromMs + windowMs]}.
     *
     * <p>The fallback for a track whose <em>separated low end</em> has nothing to measure a downbeat
     * from — a real one arrived with a device run: {@code 1410815174}'s head has a loud tenth of
     * <b>−68.3 dBFS</b> on its separated bass (under the {@link #LOW_BAND_FLOOR_DBFS} floor this
     * class's own estimate uses), i.e. its first 18 s have no low end at all, so a downbeat
     * estimated from it would be one of four coin flips and every bar line the renderer places from
     * it is a guess dressed as a measurement.
     *
     * <p>The bar <em>grouping</em> is still a guess here ({@link StemGesture#barLines}'s own note:
     * a bar line spaced a bar apart from the beat phase is wrong three times in four) — but the
     * grid's phase and period are <em>measured</em>, so the lines land on the track's beats, which
     * is what the fusion's cuts have to land on (the phase match to the outgoing's grid is asked
     * about the beat phase, not about the bar). A caller that has a measured downbeat should use
     * it; this is what a caller does when it has not.
     */
    public static double[] barLinesOfBeatGrid(double beatMs, double phaseMs, double fromMs,
                                              double windowMs) {
        if (!(beatMs > 0d) || !(windowMs > 0d)) return new double[0];
        double barMs = beatMs * BEATS_PER_BAR;
        double at = phaseMs + Math.floor((fromMs - phaseMs) / barMs) * barMs;
        while (at < fromMs) at += barMs;
        java.util.ArrayList<Double> lines = new java.util.ArrayList<>();
        for (; at <= fromMs + windowMs; at += barMs) lines.add(at);
        double[] out = new double[lines.size()];
        for (int i = 0; i < out.length; i++) out[i] = lines.get(i);
        return out;
    }

    /**
     * The window of the outgoing track to decode for the junction estimate, ms:
     * {@code {fromMs, windowMs}}. Wide enough that whichever bar line the junction turns out to
     * be — the search covers {@link #JUNCTION_SEARCH_BARS} bars either way from the target — the
     * passage that starts there is inside the decoded material. A decode is cheap where a
     * separation is not, so this errs wide.
     */
    public static long[] probeWindow(long aDurMs, double aBeatMs, double bBeatMs, double speed,
                                     long blendMs) {
        double aBar = aBeatMs * BEATS_PER_BAR;
        double band = JUNCTION_SEARCH_BARS * aBar;
        long span = sourceSpanMs(bBeatMs, speed);
        long target = Math.round(aDurMs - CUT_BACK_MS - blendMs);
        long from = Math.max(0L, Math.round(target - band - A_TAIL_SLACK_MS));
        long to = Math.min(aDurMs, Math.round(target + band + span + A_TAIL_LEAD_MS));
        return new long[]{from, Math.max(0L, to - from)};
    }

    /**
     * The window of the outgoing track that has to be SEPARATED for one fusion, ms:
     * {@code {fromMs, windowMs}} — {@link #A_TAIL_SLACK_MS} of lead on either side of the target
     * plus the whole passage. The lead is what the two uncertainties need: the take has to start
     * exactly on the junction, and the junction is a bar line within ±1 bar of the target, so a
     * window that began at the target could miss the material the junction needs by up to a bar.
     * By {@link #plan}'s own cost clause this is at most {@link #FUSION_TAIL_MAX_MS}.
     */
    public static long[] materialWindow(Plan plan) {
        if (plan == null || !plan.valid) return new long[]{0L, 0L};
        return new long[]{plan.materialFromMs, plan.materialWindowMs};
    }

    /**
     * The outgoing track's bar lines, ABSOLUTE ms, from the low band of its <em>decoded</em>
     * audio — the same estimator the rest of this codebase uses ({@link
     * StemGesture#downbeatOffsetSec} over a low-end envelope, then {@link StemGesture#barLines}),
     * fed with the mix's own low end rather than with a separated bass stem.
     *
     * <p>Fed that way for one reason: the separated stems are what this estimate decides the
     * window for. Decoding the outgoing's tail is cheap (a tenth of a second of CPU for the
     * fifteen seconds of audio this looks at), separating it is not (seconds), and the separation
     * has to be told <em>where</em> to run — so the question "where are the outgoing track's bar
     * lines" has to be answerable from a decode. A one-pole low pass is what stands in for the
     * model here; the kick and the bass are what a downbeat is audible on and they are what is
     * under the filter. A heuristic, like every downbeat estimate on this platform, and it is used
     * for exactly one thing: which line of the outgoing's grid its deck is cut on.
     *
     * @param decoded the outgoing's decoded audio over at least a few bars, {@code [ch][sample]}
     * @param rate    its sample rate
     * @param fromMs  where that decoded material starts in the outgoing's own file, ms
     */
    public static double[] barLinesOfLowBand(float[][] decoded, int rate, long fromMs,
                                             double beatMs, double phaseMs) {
        if (decoded == null || decoded.length == 0 || decoded[0] == null || !(beatMs > 0d)) {
            return new double[0];
        }
        float[] envelope = lowBandEnvelope(decoded, rate);
        if (envelope.length < StemGesture.SUSTAIN_CELLS * 2) return new double[0];
        // A window with no low end gives no downbeat: the estimator would otherwise pick one of
        // the four candidate phases on the strength of nothing, and a junction on a coin flip is
        // worse than no fusion (which is today's edit). Asked of the window's loudest tenth rather
        // than of its median: on an ordinary record the kicks are sparse, and a median of the low
        // band is the level between them.
        if (loudTenthDb(envelope) < LOW_BAND_FLOOR_DBFS) return new double[0];
        double fromSec = fromMs / 1000d;
        double windowSec = decoded[0].length / (double) rate;
        Double downbeat = StemGesture.downbeatOffsetSec(envelope, fromSec, StemGesture.CELL_SEC,
                phaseMs / 1000d, beatMs / 1000d, BEATS_PER_BAR);
        if (downbeat == null || Double.isNaN(downbeat)) return new double[0];
        double[] relative = StemGesture.barLines(60d / (beatMs / 1000d), downbeat, BEATS_PER_BAR,
                fromSec, windowSec);
        double[] out = new double[relative.length];
        for (int i = 0; i < out.length; i++) out[i] = fromMs + relative[i] * 1000d;
        return out;
    }

    /** The level the loudest tenth of a window's low-band cells sit above, dBFS — "is there a low
     *  end in this window at all", asked so that a sparse kick pattern does not read as silence.
     *  Public because the renderer asks the same question about the <em>incoming</em> track's head
     *  before it trusts a downbeat estimated from it (see {@link #barLinesOfBeatGrid}). */
    public static double loudTenthDb(float[] envelope) {
        if (envelope == null || envelope.length == 0) return -240d;
        float[] sorted = envelope.clone();
        java.util.Arrays.sort(sorted);
        return DjEdit.db(sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.9d))]);
    }

    /** One-pole low-passed cell RMS of a decoded window, at {@link StemGesture#CELL_SEC} cells:
     *  the low end a downbeat is audible on, without the model. */
    public static float[] lowBandEnvelope(float[][] pcm, int rate) {
        if (pcm == null || pcm.length == 0 || pcm[0] == null) return new float[0];
        int cell = Math.max(1, (int) Math.round(StemGesture.CELL_SEC * rate));
        int length = pcm[0].length;
        int cells = length / cell;
        if (cells <= 0) return new float[0];
        // 150 Hz, the top of what a kick and a bass line have to themselves on a pop record.
        double k = Math.min(1d, 2d * Math.PI * 150d / rate);
        float[] out = new float[cells];
        double state = 0d;
        for (int c = 0; c < cells; c++) {
            double energy = 0d;
            for (int i = c * cell; i < (c + 1) * cell; i++) {
                double frame = 0d;
                for (float[] channel : pcm) {
                    if (i < channel.length) frame += channel[i];
                }
                frame /= pcm.length;
                state += (frame - state) * k;
                energy += state * state;
            }
            out[c] = (float) Math.sqrt(energy / cell);
        }
        return out;
    }

    /**
     * The plan: the anchors, the validity verdict, and where to take the outgoing's material from.
     *
     * <p>The validity list is the spec's own, in its own order — a grid for each track, a known
     * blend, a passage that fits the cost bound, a junction inside the outgoing's file, a known
     * content start, and a whole fusion inside the vocal-free window — plus two the spec leaves
     * to the renderer, both of which only ever <em>refuse</em> a fusion:
     * <ul>
     *   <li>the outgoing's file has to contain the whole passage that would be carried — its deck
     *       is cut at the junction, so material past the end of its file does not exist; and</li>
     *   <li>the window that gets SEPARATED has to fit {@link #FUSION_TAIL_MAX_MS}. That window is
     *       the passage plus {@link #A_TAIL_SLACK_MS} of lead on either side, and the spec's own
     *       clause ({@code 3*aBar + 1000 <= FUSION_TAIL_MAX_MS}) is what that arithmetic comes to
     *       when the tempo lock makes the passage exactly three bars of the outgoing's grid. The
     *       two leads are why this clause, not the spec's, is the operative one: the junction is a
     *       bar line within ±1 bar of the target and cannot be known before the grid is measured,
     *       so a window that began at the target could miss the material the junction needs. The
     *       cost of that honesty is the tempo floor — a passage plus two leads is 11 s at a 3 s
     *       bar, i.e. about 80 BPM in 4/4, where the spec's clause alone would allow 72 BPM.</li>
     * </ul>
     */
    public static Plan plan(Input in) {
        return plan(in, 0);
    }

    /**
     * The plan for the {@code candidateIndex}-th best junction of the search band (round 37).
     *
     * <p>Index 0 is exactly what {@link #plan(Input)} always answered — the same choice, by the same
     * preference (a line the outgoing is playing on, then one with groove, then a quiet beat, then
     * the nearest) — so every existing caller, fixture and refusal is bit-for-bit what it was. Index
     * 1 and up are the band's NEXT lines in that same preference order, for the renderer to render
     * and score against each other: the listener's own instruction was that the search should not
     * bet the whole render on one line — 「重新寻找最佳落点，多取几个进行本地快速融合，融合出来进行评分，取不同融合中的
     * 最高分应用」. A plan is still a plan: every clause, the budget, the coupling and the acceptance
     * apply to a candidate exactly as they do to the first answer, and an index past the band's end
     * is refused with the band's own message plus how many candidates it held.
     */
    public static Plan plan(Input in, int candidateIndex) {
        double aBar = in.aBeatMs * BEATS_PER_BAR;
        double bBar = in.bBeatMs * BEATS_PER_BAR;
        double lock = lockError(in.aBeatMs, in.bBeatMs, in.speed);
        long target = Math.round(in.aDurMs - CUT_BACK_MS - in.blendMs);

        if (!(in.aBeatMs > 0d) || in.aBarLinesMs == null || in.aBarLinesMs.length == 0) {
            return invalid("no bar grid for the outgoing track", aBar, bBar);
        }
        // ⚠️ The incoming's LINES are optional, its BEAT GRID is not: those lines are a measurement of
        // its separated head, which can be short, can be empty, and has been handed over in the wrong
        // unit at least once (see {@link #entryLines}); the beat grid is what the relation and the
        // phase match are read from, and the entry falls back to lines built out of it.
        if (!(in.bBeatMs > 0d)) {
            return invalid("no beat grid for the incoming track", aBar, bBar);
        }
        if (!(in.blendMs > 0L)) {
            return invalid("the boundary's blend length is not known", aBar, bBar);
        }
        // ⚠️ Round 5: the tempo relation, and the SLAM for a pair in none. A unison pair is the
        // lock clause's (LOCK_TOLERANCE, the deck's own ratio as the carry's stretch — bit-for-bit
        // round 18's); a relative pair is one whose BAR grids coincide and whose residual the
        // carry's stretch absorbs; a pair in no relation at all is still fused — as a SLAM, a
        // one-bar cut-based passage needing no beat matching at all, which is what the user asked
        // for in as many words (「速度无关的也要接，不要淡入淡出」).
        Relation relation = relationOf(in.aBeatMs, in.bBeatMs, in.speed);
        if (!(in.speed > 0d)) {
            return invalid("the ratio the incoming deck plays at is not known", aBar, bBar, lock);
        }
        boolean slam = relation == null;
        // One step of the table: `p` bars of the incoming's grid for a relation, one bar for a
        // slam (the only line of the incoming's own grid the gesture needs), and the gesture lasts
        // FUSION_BARS of them (SLAM_STEPS for a slam).
        double stepMs = slam ? bBar : relation.barStepMs(in.bBeatMs);
        double stretch = slam ? in.speed : relation.stretch(in.aBeatMs, in.bBeatMs);
        // ⚠️ THE CARRY'S RATIO AND THE DECK'S RATIO ARE ONE DECISION, NOT TWO. `stretch` is what A's
        // material is pre-lengthened by INSIDE the file, and it is the bar ratio for every relation
        // — that is what puts A's bar boundaries on the incoming's grid, which is what the relation
        // is FOR. `speed` is what the deck plays the file at, and it is the listener's conversion
        // back: A's carry is heard at A's own tempo only when the two agree. A pair whose deck
        // ratio is not the carry's ratio (an unlocked relation 1 with a profile too weak for
        // MixNaturaliser to stretch the incoming, or a platform that refuses the tempo) is
        // therefore not a fusion at all, whichever way it is written: with the bar ratio the
        // outgoing's material is played at a tempo that is not its own (3.9% and 0.66 semitones on
        // the device's AGUDO -> Lose My Mind, whose deck ratio is 1.0 while its bar ratio is
        // 0.9622), and with the deck's ratio its bar boundaries slide against the incoming's beats
        // (0.34 s over the four-step passage) — one detuned, one off the grid. It is refused here,
        // with both numbers in the reason, because the fix is upstream: a profile the deck may
        // stretch, or a pair whose grids really do hold. A LOCKED relation cannot reach this clause
        // — its stretch IS the deck's ratio, bit for bit round 18 — and a relative pair's bar ratio
        // is within the relation's own tolerance of it (measured: 1.6% on the 1:2 pair, 1.7% on the
        // 1:1-unlocked one), so nothing that fuses today changes.
        double carryError = Math.abs(stretch - in.speed) / Math.max(1e-9d, stretch);
        if (carryError > LOCK_TOLERANCE + 1e-12) {
            return invalid(String.format(Locale.US,
                    "the deck plays this file at x%.4f while the outgoing's material has to be read"
                            + " at x%.4f to land on the incoming's own grid (%.2f%% apart, where the"
                            + " lock's own tolerance is %.0f%%): at the deck's ratio the carry's bar"
                            + " boundaries slide against the incoming's beats, and at the carry's"
                            + " ratio the outgoing's material is played at a tempo that is not its"
                            + " own — either way it is not a fusion, and the answer is a profile the"
                            + " deck may stretch (or a pair whose grids hold)",
                    in.speed, stretch, carryError * 100d, LOCK_TOLERANCE * 100d), aBar, bBar, lock);
        }
        // ⚠️ Round 6's shape, and the budget decides how much of it fits: the full gesture is a
        // hold at unity plus the low end's own fade, and all of it has to be separated out of the
        // outgoing's own file like anything else.
        //
        // ⚠️ The 1.33 steps above is a bound on the AUDIBLE coexistence, not the number itself.
        // Measured on the device's own material (AGUDO -> Lose My Mind, the incoming's deck starting
        // at 900 ms, one step 2 188 ms, hold 2 — `fusion/coexist.py`, the same instrument the
        // prototype used, each row against its own frame level):
        //   • the whole backing (drums + low end + bed): 1750 ms in all, longest run 530 ms         //     — the prototype's own 1750/450, reproduced to the millisecond of total;
        //   • the bed alone: 1530 / 220 ms, the low end alone: 220 / 80 ms, the drums alone:
        //     0 / 0 ms (the incoming's kit is not playing until ~8 4xx ms, which is past the window
        //     the drums' own row gets) — so what the listener hears as the coexistence is carried by
        //     the bed and the low end, and the drums' row contributes nothing on that pair.
        // A longer hold is not the lever it looks like: one more step measured 2070 ms total and a
        // SHORTER longest run (490 ms), i.e. ~0.3 s more in all and no more continuity, because the
        // limit is the incoming's own material rather than the gesture. A pair whose step is so long that the full shape
        // does not fit gets ONE step of hold rather than no fusion at all — still a recede.
        long cap = slam ? FUSION_TAIL_MAX_MS : tailMaxMs(relation);
        // ⚠️ The shape, from the one call the pre-decode clause uses too (see {@link #shapeFor}).
        int[] shape = shapeFor(stepMs, stretch, cap);
        int holdSteps = shape[0];
        int lowEndFadeSteps = shape[1];
        // The caller's own steps (round 6, sixth pass: the renderer's loop asks for one more step at
        // a time and lets the acceptance judge each result). Counted exactly like the design's, so
        // the budget and the vocal window bound them by the same arithmetic.
        holdSteps += in.extraHoldSteps;
        int shortestHoldForCaller = holdSteps;
        // ⚠️ Round 6's second pass: the recede WAITS for the row that replaces it. A row of A's may
        // not start to fade before the incoming's own row of the same kind is playing, or the passage
        // hands the pulse to nobody — the device's own pair faded A's drums at 5260ms into a track
        // whose first ~3 s have no drums, and the acceptance measured a 2832ms hole in the pulse.
        // The hold grows in whole steps, so the gesture's shape (hold, then the drums' one-step fade,
        // then the low end's two) is unchanged: it is the same recede, later. A pair whose incoming
        // rows do not start playing inside the passage this pair can afford is refused BY THIS NAME,
        // rather than accepted and measured as a hole.
        long incomingDrumsMs = -1L;
        long incomingBassMs = -1L;
        boolean holdForIncoming = false;
        // What the incoming's own rows ask the hold to be, in steps, whether or not the design's
        // hold already covers it: the shortening below may not go under it.
        int holdForIncomingSteps = 0;
        if (!slam && in.incoming != NO_INCOMING_MEASUREMENT) {
            int barSteps = Math.max(1, Math.round((float) stepMs));
            int maxSteps = maxStepsFor(stepMs, stretch, cap, in.removalMs, in.contentStartMs);
            long reach = in.contentStartMs + (long) maxSteps * barSteps;
            incomingDrumsMs = in.incoming.firstPlayingMs(StemGesture.Stem.DRUMS.row(),
                    in.contentStartMs, reach);
            incomingBassMs = in.incoming.firstPlayingMs(StemGesture.Stem.BASS.row(),
                    in.contentStartMs, reach);
            long late = Math.max(incomingDrumsMs, incomingBassMs);
            if (incomingDrumsMs < 0L || incomingBassMs < 0L) {
                return invalid(String.format(Locale.US,
                        "the incoming's own %s do not start playing inside the %dms passage this"
                                + " pair can afford (from the incoming's own %dms, the passage holds"
                                + " %d steps of %.1fms and then needs %d more for A's low end's own"
                                + " fade): A's rows would fade before the rows that replace them are"
                                + " on, which is a hole in the passage's pulse rather than a"
                                + " hand-over — measured on the same pair as the incoming's own"
                                + " separated rows, and the reason is named rather than left to the"
                                + " acceptance",
                        incomingDrumsMs < 0L ? "drums" : "low end", (long) maxSteps * barSteps,
                        in.contentStartMs, maxSteps, stepMs, lowEndFadeSteps), aBar, bBar, lock);
            }
            // ⚠️ Anchored at the incoming's own content start, not at the entry: this decision is
            // made before the entry (it feeds the take's own span, which feeds the junction search),
            // and the entry is a common-grid line within two steps of that start — so the hold can
            // end up one or two steps LATER than the row's own instant needs. That side of the error
            // is the safe one: A's row is never faded before the row that replaces it is on, which
            // is the whole rule, and the cost is a step of A's drums and low end holding on.
            int needed = (int) Math.ceil((late - in.contentStartMs) / (double) barSteps);
            holdForIncomingSteps = needed;
            if (needed + lowEndFadeSteps > maxSteps) {
                return invalid(String.format(Locale.US,
                        "the incoming's own %s only start playing at %dms of its file, and the"
                                + " passage this pair can afford holds %d steps of %.1fms (from its"
                                + " own %dms) with %d more needed for A's low end's fade — the wait"
                                + " does not fit, and a recede that starts without them is the hole"
                                + " in the passage's pulse this clause exists to refuse",
                        incomingDrumsMs > incomingBassMs ? "drums" : "low end", late,
                        maxSteps, stepMs, in.contentStartMs, lowEndFadeSteps), aBar, bBar, lock);
            }
            if (needed > holdSteps) {
                holdSteps = needed;
                holdForIncoming = true;
            }
        }
        // ⚠️ And here the two rules that share the cap have to agree about the same milliseconds:
        // the passage is paid for out of it, and so is the junction search's own band. On the
        // device's `squabble up -> AGUDO` (a 2 305 ms bar, a locked pair, the 12 000 ms cap) the
        // design's own 4-step gesture left a ±429 ms band while the outgoing's bar lines are
        // 2 305 ms apart — no line in it — and the plan died with "no line of the outgoing's grid is
        // inside the 12000ms the passage can be taken from", which is two of this file's own rules
        // refusing a pair neither of them has anything against. So the gesture gives: the low
        // end's fade shortens to one step first (still a fade; the coexistence stays a step and a
        // third), then the hold — never below the wait the incoming's own rows ask for — and only a
        // pair whose bar is wider than every affordable band is refused, by the search's own
        // message, which now says the gesture was already at its shortest.
        int shortestHold = Math.max(shortestHoldForCaller,
                Math.max(1, holdForIncomingSteps));
        boolean shortenedForBand = false;
        if (!slam) {
            // The question is the direct one — would the search have a candidate at all — and not
            // "is the band a whole bar wide": a band narrower than a bar still holds a line
            // whenever one happens to sit in it (the canonical 500 ms fixture leaves 1 920 ms of
            // band on 2 000 ms bars with a line 250 ms from the target, which is the plan the
            // design intends, and a blanket "band >= bar" rule broke it).
            while (!bandHasLine(in, target, cap, holdSteps + lowEndFadeSteps, stepMs, stretch)
                    && (lowEndFadeSteps > 1 || holdSteps > shortestHold)) {
                if (lowEndFadeSteps > 1) {
                    lowEndFadeSteps = 1;
                } else {
                    holdSteps--;
                }
                shortenedForBand = true;
            }
            // The flag means "the gesture could not be made to find a line", which is what the
            // search's refusal has to say — a pair already at its shortest says it too.
            shortenedForBand |= !bandHasLine(in, target, cap, holdSteps + lowEndFadeSteps, stepMs,
                    stretch);
        }
        // ⚠️ Round 18: **the passage is as LONG as the pair can afford, not the fixed three or four
        // steps of the table's own shape.** The user, after listening to a boundary on the device:
        // 「我认为你只需要让混音段长一点他可能就能自然过渡到下一首歌了，目前感觉是过渡了一半」 — and the
        // numbers agree with the ear: the edit was a SLAM (`-e4272-j204141-f10778`), i.e.
        // {@link #SLAM_STEPS} bars of a 112 BPM incoming = 6 506 ms, inside a ramp the listener had set
        // to 24 000 ms. The passage's length never followed that setting, which is what "only a few
        // seconds, and it feels half done" is. **这只动长度**: the shape of the recede, the landing's
        // rules and the deck-level curves are all untouched, and the SLAM keeps its own 80 ms splice
        // (the user ruled a recede out for it in as many words: 「退场可能导致淡入淡出」).
        //
        // <p>What it grows into is not a new number: it is the window this pair ALREADY pays for.
        // {@link #maxStepsFor} is the same arithmetic the wait and the cost clause use — the smaller
        // of the separation budget ({@code cap}) and the stretch the incoming's own vocals are held
        // out for ({@link Input#removalMs}, which the renderer takes from the listener's 过渡时长) —
        // and until this round it was used only as a CEILING that could shorten the shape. Now it is
        // the length, with the shape's own steps as the floor.
        //
        // <p>⚠️ And it is grown only out of the room the cap has BESIDES the search's own band — see
        // {@link #SEARCH_BAND_MS}, which is reserved first. A growth that ate the band was measured
        // and rejected: it narrowed the search onto the outgoing track's own fade and made the
        // planner refuse a pair it used to fuse (the fixture
        // {@code aPassageInsideAFadeIsNotUsedIfAnEarlierLineHasBody} went from choosing the earlier
        // line with body to refusing outright). The two quantities are one budget, so the budget is
        // asked twice — once for the whole cap, once for the cap with the band reserved — and the
        // smaller answer is what the passage may be. The one-step-at-a-time test below is still asked
        // on top, because a band that HAS room can still have no line of A's grid in it.
        //
        // <p>⚠️ KNOWN, AND IN THIS BUILD ON PURPOSE: a longer passage raises the floor on the landing
        // ({@code ceiling = contentStart + removalMs − window}), so on some pairs the deck is pushed
        // back toward the incoming's own start — the fixture
        // {@code theLandingIsTheOneThatPutsTheVoiceWhereTheOutgoingLeft} reads 0 where it read 4 000,
        // which is round 13's 「不要把小半个歌跳过」 in the opposite direction. That trade is the reason
        // this round is being listened to before it is retuned: the passage's length, the search's
        // band and the landing's ceiling are three exits of one budget.
        int roomSteps = Math.min(
                maxStepsFor(stepMs, stretch, cap, in.removalMs, in.contentStartMs),
                maxStepsFor(stepMs, stretch, cap - SEARCH_BAND_MS, in.removalMs, in.contentStartMs))
                + in.extraHoldSteps;
        // ⚠️ Round 24: the growth stops at whichever comes FIRST — the search's band (above) or the
        // landing's own floor (here). The listener's two requirements in one breath: 「融合段可以长一点」
        // and 「尽量避免找在最末尾最开头这种淡入淡出的效果」 — the passage may be long, but not so long that
        // the incoming deck has to start inside its own opening bars.
        //
        // <p>Why the window is what pushes the landing: {@code entry} clamps the landing to
        // {@code ceiling = in.removalMs - windowMs}, so every step the passage grows lowers that ceiling
        // by a step, and once it falls under the floor (a track already singing at the top of its file
        // must still start {@link #ENTRY_WORDS_BARS} bars in) the deck is dragged to the file's own
        // start. Measured before this clause: the fixture
        // {@code theLandingIsTheOneThatPutsTheVoiceWhereTheOutgoingLeft} read a landing of 0 where the
        // design's answer is 4 000, and the listener heard exactly that as a fade-in. The floor is
        // recomputed here in {@code entry}'s own terms rather than passed, because the growth runs before
        // the entry does — the two are the same two numbers.
        long landingGrid = Math.max(1L, Math.round(stepMs));
        long landingFloor = Math.max(in.contentStartMs,
                in.firstVocalMs - in.contentStartMs <= (long) ENTRY_AT_ONCE_BARS * landingGrid
                        ? in.contentStartMs + (long) ENTRY_WORDS_BARS * landingGrid
                        : in.contentStartMs);
        int passageSteps = slam ? SLAM_STEPS : holdSteps + lowEndFadeSteps;
        for (int candidate = passageSteps + 1; candidate <= roomSteps; candidate++) {
            long candidateWindow = slam
                    ? Math.max(Math.round(candidate * stepMs + CUT_MS),
                            (long) candidate * Math.round(stepMs) + CUT_MS)
                    : Math.round(candidate * stepMs);
            if (in.removalMs - candidateWindow < landingFloor) break;
            if (!bandHasLine(in, target, cap, candidate, stepMs, stretch)) break;
            passageSteps = candidate;
        }
        if (!slam) holdSteps = passageSteps - lowEndFadeSteps;
        int steps = passageSteps;
        // How many steps of the table carry the outgoing's own rows: all of them, for a slam (its
        // one cut is the window's own end) and for a fusion too (its rows reach the floor ON the
        // window's last step, so the material has to last that far).
        int cutSteps = steps;
        // ⚠️ A SLAM's window is its SLAM_STEPS bars PLUS the splice: every element changes hands on
        // the line at `swapMs`, and the 80 ms splice that de-clicks that hand-over runs from the line
        // to {@code swap + CUT_MS}. When the window ended ON the line (it did until round 6's tenth
        // pass) the splice had nowhere to happen: `gainAt`'s own guard sent [swap, swap + CUT) to the
        // floor, so the outgoing's rows fell from unity to exactly 0 in one sample — the
        // instantaneous cut the user ruled out (「不要让它戛然而止」), measured on the device's own render as
        // a 0.3227 sample-to-sample jump against the signal's own 99.9th percentile of 0.2648 (1.2x).
        // With the splice inside the window the same line reads 0.0913 (0.3x) and no dip. The take
        // grows by the same CUT_MS (see sourceSpan, which has always counted it).
        //
        // The max is the two ways of writing that one span, and it is the SLAM's because the line is a
        // bar LINE of the incoming's own grid — `entry + SLAM_STEPS * round(stepMs)`, an integer
        // multiple of the grid it lands on — while the window is measured in the step's own
        // (possibly fractional) milliseconds. At SLAM_STEPS = 1 the two roundings agreed by
        // construction; at three they can differ by a millisecond, and a window a millisecond short
        // of `swap + CUT_MS` is the sampled defect above, not a rounding detail.
        long windowMs = slam
                ? Math.max(Math.round(steps * stepMs + CUT_MS),
                        (long) steps * Math.round(stepMs) + CUT_MS)
                : Math.round(steps * stepMs);
        // The material the take needs: the rows are cut at `swapMs` (which for a slam is the line
        // that hands over, one splice before the window's own end) plus the splice, in the outgoing's
        // own file.
        long sourceSpan = (long) Math.ceil((cutSteps * stepMs + CUT_MS)
                / (stretch > 0d ? stretch : 1d));
        // ⚠️ The window the render will separate, paid for out of the cost bound:
        // QUIET_SEARCH_MAX_MS back of the target (the quiet-passage search) and
        // JUNCTION_SEARCH_BARS bars forward of it (the groove preference), plus the lead the take's
        // alignment wants. A slow track's search is shorter rather than its separation longer — the
        // budget is the clause.
        long budget = cap - sourceSpan - 2L * A_TAIL_SLACK_MS;
        if (budget < 0L) {
            return invalid(String.format(Locale.US,
                    "the passage is %.1fms of the incoming's file and the material A's rows need"
                            + " from A is %dms, so the window to separate is at least %dms — over"
                            + " the %.0fms one render may separate",
                    steps * stepMs, sourceSpan, sourceSpan + 2L * A_TAIL_SLACK_MS, (double) cap),
                    aBar, bBar, lock);
        }
        long forward = Math.min(Math.round(JUNCTION_SEARCH_BARS * aBar), Math.max(0L, budget / 2L));
        long back = Math.min(QUIET_SEARCH_MAX_MS, Math.max(0L, budget - forward));
        long materialFrom = Math.max(0L, target - back - A_TAIL_SLACK_MS);
        long materialWindow = sourceSpan + back + forward + 2L * A_TAIL_SLACK_MS;
        if (in.contentStartMs < 0L) {
            return invalid("the incoming deck's own start is not known", aBar, bBar, lock);
        }

        // The junction: the bar line the deck is cut on, searched from `back` before the target to
        // `forward` after it and preferred in the design's order — first a line the outgoing track
        // is still PLAYING on (a track whose own last seconds are a fade must not have the fusion
        // sit in that fade), then one where its groove is playing, then one where its voice is quiet
        // for a beat, then the nearest. A line whose passage does not fit the material window is not
        // a candidate at all.
        java.util.List<Choice> choices = nearestBars(in.aBarLinesMs, target, aBar, materialFrom,
                materialFrom + materialWindow, sourceSpan, back, forward, in.quiet, in.groove,
                in.body);
        if (choices == null || choices.isEmpty() || candidateIndex >= choices.size()) {
            return invalid(String.format(Locale.US,
                    "no line of the outgoing's grid is inside the %dms the passage can be taken"
                            + " from (the search covers %dms back and %dms forward of %d outside"
                            + " it)%s%s",
                    materialWindow, back, forward, target,
                    shortenedForBand
                            ? String.format(Locale.US, ", with the gesture already shortened to its"
                                    + " shortest (%d steps of %.1fms: the incoming's own rows"
                                    + " %s and the low end's fade is one step) because the passage"
                                    + " and the search's own band are paid for out of the same"
                                    + " %.0fms cap",
                            steps, stepMs,
                            holdForIncomingSteps > 0 ? "hold A's rows at unity until they arrive"
                                    : "need no wait",
                            (double) cap)
                            : "",
                    choices != null && candidateIndex > 0 && candidateIndex >= choices.size()
                            ? String.format(Locale.US,
                                    " (the band held %d candidate%s; candidate %d was asked for)",
                                    choices.size(), choices.size() == 1 ? "" : "s", candidateIndex)
                            : ""), aBar, bBar, lock);
        }
        Choice choice = choices.get(candidateIndex);
        if (choice.measured && choice.usable == 0) {
            return invalid(String.format(Locale.US,
                    "every bar line of the outgoing's grid the search reached (from %dms back of"
                            + " the target to %dms forward of it) is a passage more than %.1f dB"
                            + " below the track's own body: the body is %.2f dBFS and the best line"
                            + " in the band (%dms, %+dms from the target) carries a passage of"
                            + " %.2f dBFS, %+.2f dB down — that passage is the track's own fade, and"
                            + " fusing it is the flatness this clause exists for",
                    back, forward, QUIET_PASSAGE_DB, choice.bodyDb, choice.bestAt,
                    choice.bestAt - target, choice.bestAtLevel, choice.bestAtDrop), aBar, bBar,
                    lock);
        }
        long junction = choice.atMs;
        if (junction + sourceSpan > in.aDurMs) {
            return invalid(String.format(Locale.US,
                    "the outgoing file ends %dms into the %dms the fusion's carried rows need from"
                            + " it (they are cut on the bar line at %d of its own file and run to"
                            + " its own low end's last cut)",
                    in.aDurMs - junction, sourceSpan, junction), aBar, bBar, lock);
        }

        // The entry: the landing the coupling asks for — the incoming's own bar line at or below the
        // point that puts its first voice where the outgoing's rows have left ({@link #entry}) —
        // with the phase match choosing among the lines either side of it. `windowMs` and
        // `sourceSpan` are handed over because the landing's own ceiling and the departure's own
        // reading are both written in them (see `entry`).
        Landing landing = entry(in, junction, stepMs, slam ? 1 : relation.p, windowMs, sourceSpan,
                stretch, voiceFloorMs(slam ? steps : holdSteps, slam ? 0 : lowEndFadeSteps,
                        Math.round(stepMs)));
        long entry = landing.entryMs;
        if (entry < 0L) {
            // ⚠️ The refusal says WHAT it looked at, because the version that did not cost a device
            // run: the renderer's own line reported nine bar lines for the incoming track and this
            // one reported none, and neither line said where those lines were or in what unit.
            return invalid("no bar line of the incoming track inside its own two-bar window"
                    + entryWhy(in, stepMs), aBar, bBar, lock);
        }
        long fusionEnd = entry + windowMs;
        if (fusionEnd > in.removalMs) {
            return invalid(String.format(Locale.US,
                    "the fusion would run %dms past the %dms the incoming's vocals are out for",
                    fusionEnd - in.removalMs, in.removalMs), aBar, bBar, lock);
        }
        long barStep = Math.round(stepMs);
        // The gesture's own instants (round 6): the incoming's drums and low end rise over one step
        // starting where the outgoing's hold ends, and the outgoing's rows recede from that same
        // instant — its drums over one step, its low end and its melodic row over two.
        long holdEnd = entry + (long) holdSteps * barStep;
        // ⚠️ The incoming arrives UNDER the outgoing's hold, not after it: its rise starts one step
        // before the hold ends, so it reaches unity exactly where the outgoing starts to recede. That
        // is what gives the passage its coexistence — the TABLE'S OWN GAINS are within 6 dB of their
        // levels for 1.33 steps (an identity of the spans, not a measurement of the audio) — which is
        // the bound the user asked to
        // keep (rule 3 of round 6: the fade must not dominate). With a one-step hold the rise starts
        // at the entry itself.
        //
        // ⚠️ A SLAM's hold is its own passage — {@link #SLAM_STEPS} steps of the incoming's own grid
        // as a FLOOR (round 18: it grows with the window the pair pays for, see the growth above), and
        // it is that count everywhere on this path and nowhere written out as a literal: `slamHold`
        // below is the one spot the line is computed, and the arrival, the swap, the bass and the
        // window's own end are all read off it. The window contains `slamHold + CUT_MS` by
        // construction (see `windowMs`), which is the property the sampled defect at the end of round
        // 6 bought: the splice's last sample is inside the file.
        long slamHold = entry + (long) steps * barStep;
        // ⚠️ Round 28: 「由小变大时不要小声太久，尽快做到背景音与前一首融合」 — the incoming's backing now
        // starts rising {@link #B_ARRIVAL_LEAD_STEPS} steps before the hold ends instead of one, and
        // therefore takes that many steps longer to get there. The arrival's END is untouched: it is
        // still the hold's own end, which is the instant the outgoing starts to recede and the
        // instant the table's final levels — and the acceptance and coexistence bound measured on
        // them — are written around. What changes is that the second track's backing is *audible*
        // for a bar more of the passage instead of sitting at the floor until the last moment, which
        // is what "fuses on the backing early" means in this file's own numbers: same destination,
        // same levels, one bar less of nothing.
        long arriveStart = slam ? slamHold
                : entry + (long) Math.max(0, holdSteps - B_ARRIVAL_LEAD_STEPS) * barStep;
        long arriveEnd = slam ? slamHold + CUT_MS
                : entry + (long) holdSteps * barStep;
        // ⚠️ A SLAM's instants come from the SLAM's shape, not the fusion's. `shapeFor` hands every
        // pair a two-step hold, and on a slam that put `holdEnd`/`arriveEnd` at entry + 2 steps while
        // the window is the slam's own hold plus the splice — so the "after their swap" slice began
        // past the window's end, held 0 samples, and {@link StemBridge#levelDb} answered −240 for it:
        // every slam on the device was refused with "the incoming's own drums never arrive (-240.0
        // dBFS after their swap)" while the same slice read at the slam's own instants holds the
        // incoming's real kit at −10.3 dBFS. A slam's rows are at unity to its line, the incoming's
        // arrive at it over the splice, and all of A is gone where the splice ends.
        long drumsEnd = entry + (long) (holdSteps + A_DRUMS_FADE_STEPS) * barStep;
        long lowEndEnd = entry + (long) (holdSteps + lowEndFadeSteps) * barStep;
        long swap = slam ? slamHold : holdEnd;
        long bass = slam ? swap : holdEnd;
        long drawEnd = slam ? slamHold + CUT_MS : lowEndEnd;
        if (slam) {
            holdEnd = swap;
            drumsEnd = drawEnd;
            lowEndEnd = drawEnd;
        }
        return new Plan(true, "", slam, aBar, bBar, stepMs, stretch, relation, junction, entry, swap,
                bass, drawEnd, windowMs, sourceSpan, materialFrom, materialWindow, forward, back,
                junction - target, lock, in.speed, choice.quiet, choice.groove, choice.bodyAtLine,
                choice.measured, choice.bodyDb, choice.passageDb, choice.dropDb,
                landing.phaseMatched, landing.phaseErrorMs, landing.skippedIntroMs, in.firstVocalMs,
                holdEnd, drumsEnd, lowEndEnd, arriveStart, arriveEnd, landing.entryFromBeatGrid,
                holdForIncoming, incomingDrumsMs, incomingBassMs,
                new Coupling(landing.departureMs, landing.departureInFileMs, landing.exitMeasured,
                        landing.voiceGateMs, landing.residualGapMs, landing.heldMs,
                        landing.uncoupledEntryMs, landing.ledFromMs, landing.floorMs),
                in.removalMs);
    }

    /** The lock clause's own words, so {@link #refusal} and {@link #plan} refuse a pair for the
     *  same stated reason. */
    private static String lockReason(double aBeatMs, double bBeatMs, double speed) {
        return String.format(Locale.US,
                "the two grids do not lock: the outgoing's beat is %.1fms and the incoming's"
                        + " %.1fms played at x%.4f is %.1fms as heard, %.1f%% out where the"
                        + " tolerance is %.0f%% — the bar lines would slide against each other and"
                        + " every cut would land off the beat",
                aBeatMs, bBeatMs, speed, bBeatMs / speed, lockError(aBeatMs, bBeatMs, speed) * 100d,
                LOCK_TOLERANCE * 100d);
    }

    /**
     * The bar line the deck is cut on: the best of the lines within
     * {@link #JUNCTION_SEARCH_BARS} bars of the target, ranked by what the bar itself offers.
     *
     * <p>The ranking is the design's, in its own order: a bar where the outgoing's <b>groove</b> is
     * playing wins over one where it is not (the fusion carries the outgoing's rhythm out of the
     * handover — the pair the user heard had no groove at the nearest line and the junction
     * measured −7 dB); among lines of equal groove, one whose <b>voice is quiet</b> for a beat wins
     * (the cut should land between phrases rather than mid-word); and among those, the nearest to
     * the target. A line whose passage falls outside {@code [fitsFrom, fitsTo]} is not a candidate
     * at all — that is the material the render will have — and neither is one outside the band.
     *
     * @param quietOut   single-element out: whether the chosen line had a quiet beat
     * @param grooveOut  single-element out: whether the chosen line had the groove playing
     */
    private static Choice nearestBar(double[] bars, long target, double aBarMs, long fitsFrom,
                                     long fitsTo, long spanMs, long backMs, long forwardMs,
                                     VocalQuiet quiet, Groove groove, BodyLevel body) {
        java.util.List<Choice> all = nearestBars(bars, target, aBarMs, fitsFrom, fitsTo, spanMs,
                backMs, forwardMs, quiet, groove, body);
        return all == null || all.isEmpty() ? null : all.get(0);
    }

    /**
     * Every junction the band offers, best preference first (round 37).
     *
     * <p>The order is the one {@link #nearestBar} has always scored by — a line the outgoing track
     * is playing on, then one with groove on it, then a quiet beat, then the rest, nearer lines
     * winning ties — so element 0 is the answer the single-candidate search gave, and the renderer's
     * candidate loop renders elements 1 and 2 as alternative fusions to score against it. The
     * band-wide fields ({@code usable}, {@code measured}, {@code bodyDb}, {@code bestAt…}) are
     * properties of the whole band and are set on every element alike.
     */
    private static java.util.List<Choice> nearestBars(double[] bars, long target, double aBarMs,
                                                      long fitsFrom, long fitsTo, long spanMs,
                                                      long backMs, long forwardMs,
                                                      VocalQuiet quiet, Groove groove,
                                                      BodyLevel body) {
        boolean measured = body != null && body.measured();
        double bodyDb = body == null ? Double.NaN : body.bodyDb();
        int usable = 0;
        long bestAt = -1L;
        double bestAtLevel = Double.NaN;
        double bestAtDrop = Double.NaN;
        java.util.List<RankedChoice> ranked = new java.util.ArrayList<RankedChoice>();
        for (double bar : bars) {
            long at = Math.round(bar);
            double offset = at - target;
            if (offset < -(double) backMs - 1e-6d || offset > forwardMs + 1e-6d) continue;
            if (at < fitsFrom || at + spanMs > fitsTo) continue;
            double drop = body == null ? Double.NaN : body.passageDropDb(at, spanMs);
            double level = Double.isNaN(drop) ? Double.NaN : bodyDb - drop;
            boolean atBody = body == null || Double.isNaN(drop) || drop <= QUIET_PASSAGE_DB;
            if (atBody) usable++;
            if (Double.isNaN(bestAtDrop) || (!Double.isNaN(drop) && drop < bestAtDrop)) {
                bestAt = at;
                bestAtLevel = level;
                bestAtDrop = drop;
            }
            boolean hasGroove = groove.presentAround(at, aBarMs / BEATS_PER_BAR);
            boolean isQuiet = quiet.quietBeatAround(at, aBarMs / BEATS_PER_BAR);
            int rank = (atBody ? 0 : 3) + (hasGroove ? 0 : (isQuiet ? 1 : 2));
            ranked.add(new RankedChoice(
                    new Choice(at, isQuiet, hasGroove, atBody, level, drop), rank,
                    Math.abs(offset)));
        }
        if (ranked.isEmpty()) return null;
        ranked.sort((x, y) -> x.rank != y.rank ? Integer.compare(x.rank, y.rank)
                : Double.compare(x.distance, y.distance));
        java.util.List<Choice> choices = new java.util.ArrayList<Choice>(ranked.size());
        for (RankedChoice r : ranked) choices.add(r.choice);
        // The band-wide facts, on every element: they describe the band, not a line.
        for (Choice c : choices) {
            c.usable = usable;
            c.measured = measured;
            c.bodyDb = bodyDb;
            c.bestAt = bestAt;
            c.bestAtLevel = bestAtLevel;
            c.bestAtDrop = bestAtDrop;
        }
        return choices;
    }

    /** One band line and the two numbers its place in the preference order is decided by — a
     *  holder for the sort, thrown away as soon as the list is ordered. */
    private static final class RankedChoice {
        final Choice choice;
        final int rank;
        final double distance;

        RankedChoice(Choice choice, int rank, double distance) {
            this.choice = choice;
            this.rank = rank;
            this.distance = distance;
        }
    }

    /** One candidate's choice: where it is, what its own bar offered, and the numbers the refusal
     *  quotes when nothing in the band had the outgoing track's body level. */
    private static final class Choice {
        final long atMs;
        final boolean quiet;
        final boolean groove;
        /** The chosen line's passage has the outgoing's body level (true when unmeasured). */
        final boolean bodyAtLine;
        /** The chosen line's passage level, dBFS, and its drop below the body, dB (NaN unmeasured). */
        final double passageDb;
        final double dropDb;
        int usable;
        boolean measured;
        double bodyDb = Double.NaN;
        /** The band's own best line (the smallest drop): where, its level and its drop. */
        long bestAt = -1L;
        double bestAtLevel = Double.NaN;
        double bestAtDrop = Double.NaN;

        Choice(long atMs, boolean quiet, boolean groove, boolean bodyAtLine, double passageDb,
               double dropDb) {
            this.atMs = atMs;
            this.quiet = quiet;
            this.groove = groove;
            this.bodyAtLine = bodyAtLine;
            this.passageDb = passageDb;
            this.dropDb = dropDb;
        }
    }

    /**
     * The incoming's bar line the deck starts on, and how good the phase match is.
     *
     * <p>Both phases are read as the wall-clock time from the nearest beat of their own grid, and
     * they are compared as a <em>circular</em> distance: they are phases of a periodic grid, and
     * the plain difference of two phases 10 ms and 490 ms apart in a 500 ms period is 480 ms by
     * subtraction and 20 ms by what it means. That distinction only shows at all when the tempo
     * lock is off (with {@code speed = periodB/periodA} every bar line of the incoming's grid
     * has the same phase, so every candidate ties and the first one wins either way).
     *
     * <p><b>Round 25 — the landing and the gate are one number.</b> The user: 「ai 推断每首歌的过渡
     * 落点 … 当过渡的混音效果逐渐减弱（前一首歌到达声音很小的部分）时，就接入歌词」. The number is
     * {@link Input#exit}: the instant the outgoing's own separated rows leave the passage. Two things
     * follow from it, and they are the same number read twice —
     *
     * <ul>
     *   <li>the <b>landing</b>: the deck starts late enough that the incoming's own first sustained
     *   voice reaches its arrival exactly there, i.e. {@code firstVocal - goneFile} (the departure's
     *   own offset in this file). A landing is only skipped to when the coupling asks for one, and
     *   how much of the intro that is falls out of the measurement; when the coupling's own landing
     *   is before the track's content start the deck starts where the track does and the incoming's
     *   voice is simply later than the departure (a gap, logged);</li>
     *   <li>the <b>gate</b> ({@link Coupling#voiceGateMs}): the instant the incoming's voice is back
     *   at unity, which is the departure's own instant in this file — never before it, which is the
     *   no-two-voices invariant, so a landing the window cannot afford makes the gate HOLD the voice
     *   instead of stacking it ({@link Coupling#heldMs}).</li>
     * </ul>
     *
     * <p>What this replaces, and why both directions were wrong: the gate used to be an <b>absolute
     * file position</b> (the window's own end, or {@link DjEdit#vocalOutMs} of the ramp) while the
     * landing was chosen by the voice alone. Moving the deck later without moving that gate brings
     * the voice back earlier in the ramp, so a skipped pair had the voice over the outgoing's own
     * material (the user's 「改完后…在过渡时就播放了人声」); leaving it there for a pair whose landing
     * cannot move holds the voice out through its own first lines (「过渡接很长的伴奏，跳过了很多词」).
     * One measured instant answers both, and it is measured off the material rather than taken from
     * a shape's own fraction.
     *
     * @param floorMs  the file's own bound on the lead, ms from the entry ({@link #voiceFloorMs}):
     *                 where the gesture has taken the outgoing's carried rows
     *                 {@link #VOICE_GATE_FLOOR_DB} under unity. Used when the material itself offers
     *                 no reading at that level inside the passage; when it does, that reading is the
     *                 bound instead (see the body). Round 30; the number is the caller's because the
     *                 gesture's own shape is (the hold and the low end's fade steps are the caller's
     *                 own choice).
     * @return see {@link Landing}
     */
    private static Landing entry(Input in, long junctionMs, double stepMs, int p, long windowMs,
                                 long sourceSpanMs, double stretch, long floorMs) {
        // The departure FIRST, because the candidate lines have to reach the landing it asks for:
        // the outgoing's own rows leaving the passage, in its own file ms, and the same instant as an
        // offset in the incoming's file (`stretch` is the ratio the carry was read at, which is what
        // places A's material from the junction on `entry`). The level it is read at is
        // OUTGOING_EXIT_DB (round 30: 12 dB, "well under its own body" rather than "stopped"), and
        // the two round-30 knobs both act on it below: a lead of VOICE_GATE_LEAD_BARS bars of the
        // OUTGOING's grid, floored by the file's own gesture.
        long departureMs = in.exit.goneAtMs(junctionMs, sourceSpanMs);
        boolean exitMeasured = in.exit.measured() && departureMs >= junctionMs;
        if (!exitMeasured) {
            // The fallback, named in the log: the gesture's own recede takes the rows to the floor
            // exactly at the passage's end, so that is what a pair with no measured envelope answers
            // — and it is bit-for-bit the round-6 behaviour for every caller without a separation.
            departureMs = junctionMs + Math.max(0L, Math.round(windowMs / Math.max(1e-9d, stretch)));
        }
        double ratio = stretch > 0d ? stretch : 1d;
        // ⚠️ Round 30, and this is the whole of the earlier voice. The measured (or fallback)
        // departure is led by VOICE_GATE_LEAD_BARS bars of the outgoing's own grid, read in the
        // OUTGOING's milliseconds because that is whose departure it is, and then mapped into this
        // file by the same ratio the carry was read at — the lead has to move the same number the
        // departure moves in, or the two would not be one instant any more. The result is then
        // clamped by the bound, which is one question asked of whichever signal can answer it:
        //   • the material, when its own envelope has a reading at VOICE_GATE_FLOOR_DB (the instant
        //     it is 6 dB under the passage's own body, i.e. no longer clearly audible): the lead may
        //     not reach back past it. This is the case that keeps a real breakdown where round 25
        //     put it — a passage whose music genuinely falls away early has its voice early, and the
        //     lead is a nudge inside that fall, not a licence to move it;
        //   • the FILE's own gesture, when the material never falls even that far inside the passage
        //     (the user's own pair: a steady last section, so the material offers no instant at all
        //     and the departure is the gesture's own end): there the only thing that can say "the
        //     outgoing is still at the level the listener is on" is the file's own recede
        //     ({@link #voiceFloorMs}), and it is what bounds the lead.
        // Both are never earlier than the deck-level hand-over, which is where the outgoing track's
        // own (never carried) voice is gone by construction.
        long leadOutgoingMs = Math.round(VOICE_GATE_LEAD_BARS * BEATS_PER_BAR * in.aBeatMs);
        long ledFromJunction = Math.max(0L, departureMs - leadOutgoingMs - junctionMs);
        long ledFile = Math.min(windowMs, Math.max(0L, Math.round(ledFromJunction * ratio)));
        long spanEnd = junctionMs + Math.max(0L, sourceSpanMs);
        long guardMs = in.exit.goneAtMs(junctionMs, sourceSpanMs, VOICE_GATE_FLOOR_DB);
        boolean guardRead = in.exit.measured() && guardMs >= junctionMs && guardMs < spanEnd;
        long floorFile = Math.max(FadeCurve.JUNCTION_XFADE_MS, guardRead
                ? Math.min(windowMs, Math.max(0L, Math.round((guardMs - junctionMs) * ratio)))
                : floorMs);
        long goneFile = Math.max(ledFile, floorFile);
        // The departure as the MATERIAL reads it, offset from the deck's start — what the log
        // reports ("its rows leave the passage at Xms of its own file, Yms into this one"), and the
        // number round 25 put the voice on. `goneFile` above is where this round places the voice
        // instead, which differs from it by exactly the lead and the bound.
        long rawFile = Math.min(windowMs, Math.max(0L, Math.round((departureMs - junctionMs) * ratio)));
        long gridStep = Math.max(1L, Math.round(stepMs));
        if (in.firstVocalMs < 0L) {
            // ⚠️ The incoming's own voice is not known — no separation of its head, or nothing
            // sustained measured in it — so there is no arrival for the coupling to place. The deck
            // then starts where round 18 put it (the phase-matched line inside its own two-step
            // window, and NO intro skip: a skip nobody measured a reason for is exactly the long
            // instrumental-with-the-words-held the coupling exists to stop), while the gate still
            // goes to the departure, which is measured. Reported as such, and `skippedIntroMs` is 0
            // because nothing about the intro was decided.
            double[] lines = entryLines(in, gridStep, p, in.contentStartMs + 2L * gridStep);
            long[] plain = search(in, junctionMs, gridStep, in.contentStartMs,
                    in.contentStartMs + 2L * gridStep, 0L, lines, lines != in.bBarLinesMs);
            long gate = plain[0] < 0L ? -1L : Math.min(plain[0] + goneFile, in.removalMs);
            return new Landing(plain[0], plain[1] == 1L, plain[2] / 1000d, 0L, -1L, departureMs,
                    rawFile, gate, 0L, 0L, exitMeasured, lines != in.bBarLinesMs, ledFile,
                    floorFile);
        }
        // The coupling's own landing, and the three things that can keep it from being taken. Each
        // clamp is a different report: the runway keeps the voice inside a track that is already
        // playing, the content start is as early as this track can be started, and the ceiling is
        // what the window the incoming's voice is held out for can pay for (the same clause the plan
        // asks as `fusionEnd <= removalMs`, asked here so that a landing the window cannot afford is
        // pulled back to the last one it can rather than making the whole plan invalid).
        long ceiling = in.removalMs - windowMs;
        long runway = (long) VOCAL_SKIP_RUNWAY_BARS * gridStep;
        long target = in.firstVocalMs - goneFile;
        target = Math.min(target, in.firstVocalMs - runway);
        target = Math.max(target, in.contentStartMs);
        target = Math.min(target, ceiling);
        // Round 33 — the landing's own WINDOW, which is a different question from the coupling's
        // landing above. See ENTRY_MAX_MS / ENTRY_WORDS_BARS / ENTRY_AT_ONCE_BARS for the user's rule
        // (「不要太靠后或者太靠前…尽力在前奏找完…不要把小半个歌跳过」) and the numbers behind the cap and
        // the two bars. Two details of the shape, both deliberate: `cap` is never below the content
        // start (so a track whose own start is already deep is not made worse), and it is still
        // bounded by `ceiling` — the user's own 过渡时长 is what pays for a landing, so nothing here
        // can plan a deeper one than their setting can afford. The floor GIVES WAY to the cap rather
        // than the other way round: a pair whose ceiling cannot pay for even the floor lands where it
        // always did instead of refusing the fusion outright.
        long cap = Math.max(in.contentStartMs,
                Math.min(ceiling, in.contentStartMs + ENTRY_MAX_MS));
        long floor = Math.max(in.contentStartMs,
                Math.min(in.firstVocalMs - in.contentStartMs <= (long) ENTRY_AT_ONCE_BARS * gridStep
                                ? in.contentStartMs + (long) ENTRY_WORDS_BARS * gridStep
                                : in.contentStartMs,
                        cap));
        target = Math.min(Math.max(target, floor), cap);
        // The lines the deck may start on: the COMMON grid's — every `p` bars of the incoming's own
        // grid (every bar for a unison pair and a slam, which is bit-for-bit round 18's and round 4's
        // candidate set), spaced `stepMs` apart — reaching the coupling's own target, because that is
        // where the landing is (round 21 reached the incoming's voice instead, and the two are not
        // the same place once the departure is measured).
        long to = Math.max(in.contentStartMs + 2L * gridStep, target + gridStep);
        // Round 33's groove clause walks FORWARD from the target, so the candidates have to reach as
        // far as that walk may go.
        to = Math.max(to, cap + gridStep);
        double[] lines = entryLines(in, gridStep, p, to);
        boolean fromBeatGrid = lines != in.bBarLinesMs;
        long[] plain = search(in, junctionMs, gridStep, in.contentStartMs,
                in.contentStartMs + 2L * gridStep, 0L, lines, fromBeatGrid);
        if (lines == null || lines.length == 0) {
            // Nothing to land on at all: the plan refuses below with its own message about the
            // window, the array and the beat grid, and none of the coupling's numbers exist.
            return new Landing(plain[0], false, 0d, 0L, -1L, -1L, -1L, -1L, 0L, 0L, false, false,
                    ledFile, floorFile);
        }
        long line = -1L;
        for (double bar : lines) {
            long at = Math.round(bar);
            // A line below the incoming's own content start is not a landing: the deck cannot start
            // where this file does not.
            if (at < in.contentStartMs || at > target) continue;
            if (line < 0L || at > line) line = at;
        }
        // ⚠️ Round 33's other half, and the clause the listener's ear asked for by name
        // (「上一轮的 34800 很完美」): the landing may not sit in the incoming's own quiet intro.
        // What that file was, measured: the pair's incoming track has its own drums and low end at
        // −70…−87 dBFS for its first 19 s (the section before them carries the melodic row alone,
        // −24…−32 dBFS), so a landing inside it hands the passage a second deck that is not playing —
        // the fusion's second half is one deck fading out, which is the 「不像过渡」 the same listener
        // reported two rounds earlier. The instrument is the junction's own
        // ({@link #GROOVE_FLOOR_DBFS}, {@link #grooveOf}), asked of the other deck, and it is skipped
        // entirely when the caller measured no incoming groove (every fixture that only asks about
        // the arithmetic, which is what keeps those landings where they were).
        //
        // The walk is forward only and bounded by `cap`: a landing EARLIER than the target is never
        // taken here, because the outgoing's departure is what the target is measured from, and this
        // clause is about the incoming's own silence rather than about the coupling.
        if (line >= 0L && in.incomingGroove != NO_GROOVE_MEASUREMENT
                && !in.incomingGroove.presentAround(line, in.bBeatMs)) {
            long moved = -1L;
            for (double bar : lines) {
                long at = Math.round(bar);
                if (at <= line || at > cap) continue;
                if (in.incomingGroove.presentAround(at, in.bBeatMs)) {
                    moved = at;
                    break;
                }
            }
            if (moved > 0L) line = moved;
        }
        // ⚠️ No line of the grid at or below the target means the coupling's own landing is EARLIER
        // than this track's first line, and starting off the grid is not a fusion — the entry is a
        // bar line of the incoming's grid, an invariant every round of this file has kept. The
        // design's own answer for such a pair is the plain entry (the phase-matched line in its own
        // two-step window), and the residual is then reported by the two miss numbers rather than by
        // a deck started mid-bar.
        if (line < 0L) line = plain[0];
        // ⚠️ The phase match is a PREFERENCE among the lines the coupling allows, never a licence to
        // miss the coupling — and a TIE is not a preference at all. Round 18's `search` answers the
        // FIRST line of its window, which is right for the plain entry (the line a runway before the
        // voice) and wrong here: on a locked pair every candidate has the same phase, the phase
        // criterion says nothing, and the line that leaves no residual gap is the latest one at or
        // below the target. So the candidates are walked from the earliest to the latest and the
        // latest line of the smallest error wins; when no line matches the outgoing's phase within
        // {@link #PHASE_MATCH_MS}, the coupling's own line stands and the entry is reported as NOT
        // matched, which is what it is.
        long from = Math.max(in.contentStartMs, line - runway);
        long landing = line;
        double bestError = Double.MAX_VALUE;
        for (double bar : lines) {
            long at = Math.round(bar);
            if (at < from || at > line) continue;
            double error = landingPhaseError(in, junctionMs, at);
            if (error <= bestError) {
                bestError = error;
                landing = at;
            }
        }
        boolean matched = bestError <= PHASE_MATCH_MS;
        if (!matched) {
            landing = line;
            bestError = landingPhaseError(in, junctionMs, line);
        }
        // What the round-21 rule would have taken on the same pair — the "before" the log reports (a
        // line a runway before the incoming's own first voice). Nothing else reads it.
        long uncoupled = -1L;
        boolean lateVoice = in.firstVocalMs >= 0L
                && in.firstVocalMs - in.contentStartMs >= introSkipMinMs(in, gridStep);
        if (lateVoice) {
            long voiceLine = -1L;
            for (double bar : lines) {
                long at = Math.round(bar);
                if (at > in.firstVocalMs) continue;
                if (voiceLine < 0L || at > voiceLine) voiceLine = at;
            }
            if (voiceLine > in.contentStartMs) {
                long start = Math.max(in.contentStartMs, voiceLine - runway);
                long[] skip = search(in, junctionMs, gridStep, start, voiceLine + 1L, in.firstVocalMs,
                        lines, fromBeatGrid);
                if (skip[0] > in.contentStartMs) uncoupled = skip[0];
            }
        }
        // The gate: the departure's own instant in this file, and never before the incoming's own
        // voice's arrival — a landing the coupling could not have puts the voice earlier than the
        // departure, and the invariant is that it is then HELD rather than stacked on the outgoing's
        // own material. Capped at the window's end, which is the last instant this file's head is
        // separated at (a voice whose own first line is past it is not gated by this file at all and
        // arrives at its own position, by which time the outgoing is long gone either way).
        //
        // ⚠️ Round 24, and this is the listener's own sentence: 「融完立马接人声不要耽搁」 — the moment the
        // fusion is over, the voice comes back, with nothing added on top. The clamp to
        // {@code landing + windowMs} is what makes that a property of the code rather than a hope: the
        // passage's end is where the outgoing's own rows have all reached the floor, so it is the last
        // instant the voice may still be held, and every later one (a bar line the curve might have
        // snapped to, a margin added after that) would be the delay being complained about. The two
        // clamps are the same instant by construction for most pairs — {@code goneFile} never exceeds
        // {@code windowMs} — and saying it here is what keeps it true if either number moves.
        long departureAt = landing + goneFile;
        long voiceAt = Math.max(0L, in.firstVocalMs);
        long gate = Math.min(Math.min(Math.max(departureAt, voiceAt), in.removalMs),
                landing + windowMs);
        double speed = in.speed > 0d ? in.speed : 1d;
        // The two directions of the same miss, both in the ramp's own wall clock: how much later
        // than the departure the voice is first heard (a gap of no voice — the material is simply
        // not there yet), and how much of the incoming's own singing the gate had to hold out.
        long residualGap = Math.round((in.firstVocalMs - departureAt) / speed);
        long held = Math.round((departureAt - voiceAt) / speed);
        long skipped = Math.max(0L, landing - in.contentStartMs);
        return new Landing(landing, matched, bestError, skipped, uncoupled, departureMs, rawFile,
                gate, Math.max(0L, residualGap), Math.max(0L, held), exitMeasured, fromBeatGrid,
                ledFile, floorFile);
    }

    /** The wall-clock phase error of a candidate landing against the outgoing's phase at the
     *  junction, ms — {@link #phaseWall} and {@link #phaseDistance} on the beat the two grids share,
     *  which is exactly what {@link #search} compares. */
    private static double landingPhaseError(Input in, long junctionMs, long atMs) {
        double sharedBeat = Math.min(in.aBeatMs, in.bBeatMs);
        double phaseA = phaseWall(junctionMs - in.aPhaseMs, sharedBeat);
        double phaseB = phaseWall(atMs - in.bPhaseMs, sharedBeat) / in.speed;
        return phaseDistance(phaseA, phaseB, sharedBeat);
    }

    /**
     * One landing, with everything the coupling derived from the outgoing's departure on it.
     *
     * <p>Where the numbers live: {@link #entryMs} is the deck's start, {@link #departureMs} is the
     * outgoing's own file instant it was placed around, {@link #departureInFileMs} is that instant
     * as an offset from the entry (the file's own timeline), {@link #voiceGateMs} is where the
     * incoming's voice is back at unity, and the two misses are {@link #residualGapMs} (the voice
     * is that much later than the departure) and {@link #heldMs} (the landing the window could
     * afford puts the voice earlier than the departure, so much of its own singing is held).
     * At most one of the two is non-zero, and both are zero exactly when the coupling was met.
     *
     * <p>Round 30's two knobs are reported beside them, because the gate's own ms is the number the
     * listener hears and the difference between the departure and it is these: {@link #ledFromMs} is
     * where the lead alone would have put it ({@link #VOICE_GATE_LEAD_BARS} bars of the outgoing's
     * grid before the departure) and {@link #floorMs} is the file's own bound
     * ({@link #voiceFloorMs}). {@link #departureInFileMs} is therefore the "before" of this round
     * on every pair: it is where the voice used to arrive.
     */
    private static final class Landing {
        final long entryMs;
        final boolean phaseMatched;
        final double phaseErrorMs;
        final long skippedIntroMs;
        final long uncoupledEntryMs;
        final long departureMs;
        final long departureInFileMs;
        final long voiceGateMs;
        final long residualGapMs;
        final long heldMs;
        final boolean exitMeasured;
        final boolean entryFromBeatGrid;
        /** Where the lead alone would have put the departure in this file, ms from the entry. */
        final long ledFromMs;
        /** The file's own bound on that lead, ms from the entry — the gesture's own
         *  {@link #VOICE_GATE_FLOOR_DB} dB point, or the deck-level hand-over, whichever is later. */
        final long floorMs;

        Landing(long entryMs, boolean phaseMatched, double phaseErrorMs, long skippedIntroMs,
                long uncoupledEntryMs, long departureMs, long departureInFileMs, long voiceGateMs,
                long residualGapMs, long heldMs, boolean exitMeasured, boolean entryFromBeatGrid,
                long ledFromMs, long floorMs) {
            this.entryMs = entryMs;
            this.phaseMatched = phaseMatched;
            this.phaseErrorMs = phaseErrorMs;
            this.skippedIntroMs = skippedIntroMs;
            this.uncoupledEntryMs = uncoupledEntryMs;
            this.departureMs = departureMs;
            this.departureInFileMs = departureInFileMs;
            this.voiceGateMs = voiceGateMs;
            this.residualGapMs = residualGapMs;
            this.heldMs = heldMs;
            this.exitMeasured = exitMeasured;
            this.entryFromBeatGrid = entryFromBeatGrid;
            this.ledFromMs = ledFromMs;
            this.floorMs = floorMs;
        }
    }

    /** The least intro worth skipping, ms: {@link #VOCAL_SKIP_MIN_BARS} bars of the incoming
     *  track's own grid — never less than one line of the common grid the landing moves by, so a
     *  pair whose relation makes a step several bars wide cannot be "skipped" by less than its own
     *  quantisation. */
    private static long introSkipMinMs(Input in, long gridStep) {
        if (!(in.bBeatMs > 0d)) return gridStep;
        return Math.max(gridStep, Math.round(VOCAL_SKIP_MIN_BARS * BEATS_PER_BAR * in.bBeatMs));
    }

    /**
     * The bar lines the entry search may use, ms: the ones the caller measured when they hold a
     * line where the deck can start, and otherwise the common grid built from the incoming's own
     * <b>beat</b> grid ({@code phase + k * p * bBar}).
     *
     * <p>Why there is a fallback at all: the caller's lines are a measurement of the incoming's
     * separated <em>head</em>, and three things make that measurement hold nothing in the entry
     * window while the beat grid — which every fusion needs anyway ({@link #LOCK_TOLERANCE}) — is
     * exact. A head shorter than {@code contentStart + 2} bars (a decode that stopped early, which
     * this app has produced: its own beat probe was once cut short by a deadline); a downbeat
     * estimate that landed at the far end of the head; or an array that crossed a unit boundary
     * (seconds read as ms — the device's {@code AGUDO -> Lose My Mind} held nine lines at
     * 0.900…18.340 and the fusion read them as 0.9…18.34 ms, which is outside every window that
     * starts at a content start of 240). The last one is a bug in a caller and is fixed there; this
     * is the answer that keeps a wrong-united array from costing a fusion, and it is <em>said
     * out loud</em>: the plan reports {@link Plan#entryFromBeatGrid}, and the log then says the
     * beats are measured and the bar grouping is a guess ({@link #barLinesOfBeatGrid}'s own note).
     */
    private static double[] entryLines(Input in, long gridStep, int p, long to) {
        double[] given = in.bBarLinesMs;
        if (given != null) {
            for (double bar : given) {
                long at = Math.round(bar);
                if (at >= in.contentStartMs && at < to) return given;
            }
        }
        if (!(in.bBeatMs > 0d) || p < 1) return given;
        // `p` bars of the incoming's own grid is one step of the common grid, and
        // barLinesOfBeatGrid lays a line every `4 * beatMs`, so handing it `p` beats gives exactly
        // the common grid's lines — over the two-step window, and over the intro skip's own reach
        // when the voice comes late (the fallback must serve both or it would answer one of them).
        return barLinesOfBeatGrid(p * in.bBeatMs, in.bPhaseMs, in.contentStartMs - gridStep,
                Math.max(gridStep, to - in.contentStartMs + 2L * gridStep));
    }

    /** Why the entry search found nothing, for the refusal's own log line: the window, what the
     *  caller's array held, and that the beat grid gave nothing either. A refusal that does not say
     *  this is the one that cost a device run — the renderer's line said nine bar lines and the
     *  planner said none, and nothing in either line said where they were or in what unit. */
    private static String entryWhy(Input in, double stepMs) {
        long gridStep = Math.max(1L, Math.round(stepMs));
        double[] lines = in.bBarLinesMs;
        String held = lines == null ? "no array at all"
                : lines.length == 0 ? "an empty array"
                : String.format(Locale.US, "%d of them, from %.3f to %.3f", lines.length,
                        lines[0], lines[lines.length - 1]);
        return String.format(Locale.US, ": the window is [%d, %d) ms (the incoming's own content"
                        + " start plus two steps of %.0fms), the bar lines it came with are %s, and"
                        + " its beat grid (%.1fms beats, %.1fms phase) gave no line in it either",
                in.contentStartMs, in.contentStartMs + 2L * gridStep, stepMs, held, in.bBeatMs,
                in.bPhaseMs);
    }

    /** The entry search itself: the candidate line in {@code [from, to)} whose beat phase, on the
     *  grid the two tracks share, lands closest to the outgoing's at the junction. */
    private static long[] search(Input in, long junctionMs, long gridStep, long from, long to,
                                 long firstVocalMs, double[] lines, boolean fromBeatGrid) {
        long first = -1L;
        long best = -1L;
        double bestError = Double.MAX_VALUE;
        // The modulus the two phases are compared on: the coarsest interval that is a whole number
        // of both grids' beats — `1` on a unison pair (round 18's own comparison), and the shared
        // grid's beat on a relative one, where the outgoing's odd beats fall BETWEEN the incoming's
        // and comparing them modulo one incoming beat would report a half-beat error for two grids
        // that in fact coincide (round 5).
        double sharedBeat = Math.min(in.aBeatMs, in.bBeatMs);
        double phaseA = phaseWall(junctionMs - in.aPhaseMs, sharedBeat);
        if (lines != null) {
            for (double bar : lines) {
                long at = Math.round(bar);
                if (at < from || at >= to) continue;
                if (firstVocalMs > 0L && at > firstVocalMs) continue;
                if (first < 0L || at < first) first = at;
                double phaseB = phaseWall(at - in.bPhaseMs, sharedBeat) / in.speed;
                double error = phaseDistance(phaseA, phaseB, sharedBeat);
                if (error < bestError) {
                    bestError = error;
                    best = at;
                }
            }
        }
        long grid = fromBeatGrid ? 1L : 0L;
        if (best < 0L) return new long[]{-1L, 0L, 0L, 0L, grid};
        if (bestError > PHASE_MATCH_MS) {
            // The fallback is the fallback, and it is logged as one: a match that is not a match
            // would put the two grids together by a number nobody measured.
            return new long[]{first, 0L, Math.round(bestError * 1000d), 0L, grid};
        }
        return new long[]{best, 1L, Math.round(bestError * 1000d), 0L, grid};
    }

    /** The distance from the nearest beat of a grid, ms, in {@code [0, period)}. */
    static double phaseWall(double deltaMs, double periodMs) {
        if (!(periodMs > 0d)) return 0d;
        double at = deltaMs % periodMs;
        return at < 0d ? at + periodMs : at;
    }

    /** The circular distance between two phases of the same period, ms. */
    static double phaseDistance(double a, double b, double periodMs) {
        double raw = Math.abs(a - b);
        if (!(periodMs > 0d)) return raw;
        return Math.min(raw, periodMs - raw);
    }

    // --- the gesture ----------------------------------------------------------

    /** Amplitude of {@code db} dBFS. */
    static double linear(double db) {
        return Math.pow(10d, db / 20d);
    }

    /**
     * One of the table's changes: the gain at {@code tMs} of a row that is at {@code before}
     * before {@code atMs} and {@code after} from {@code atMs + CUT_MS} on.
     *
     * <p>Equal-power where it can be (0→1 is a sine, 1→0 its cosine), and the same 80 ms shape
     * between two non-zero levels. Every change in the table goes through here, which is what
     * makes "the splices are counted and reported" a fact rather than a hope.
     */
    static double splice(double tMs, long atMs, double before, double after) {
        return splice(tMs, atMs, CUT_MS, before, after);
    }

    /**
     * The same change over an arbitrary duration — the two shapes the table uses, in one place:
     * the {@link #CUT_MS} splice (an edit on the bar line) and the incoming's bed arriving over
     * {@link #BED_FADE_BARS} bar (the one deliberately ramped arrival, {@link #bedGain}).
     *
     * <p>Equal-power in both directions, which is what makes the two sides of a hand-over sum to a
     * constant power rather than to a dip: the arriving row is a sine over the width, the leaving
     * one its cosine.
     */
    static double splice(double tMs, long atMs, double widthMs, double before, double after) {
        if (tMs < atMs) return before;
        double frac = widthMs > 0d ? (tMs - atMs) / widthMs : 1d;
        if (frac >= 1d) return after;
        double shaped;
        if (before == 0d) shaped = after * Math.sin(frac * Math.PI / 2d);
        else if (after == 0d) shaped = before * Math.cos(frac * Math.PI / 2d);
        else shaped = before + (after - before) * Math.sin(frac * Math.PI / 2d);
        return shaped;
    }

    /**
     * The incoming's own bed at a position in the file: silent at {@link Plan#entryMs}, arriving
     * equal-power over {@link #BED_FADE_BARS} bar of its own grid, then at {@link #B_BED_DB}
     * (unity) for the rest of the window.
     *
     * <p>This is the table's one fade, and it is the user's own request (「新歌的进入请用淡入效果」):
     * the bed is what the incoming contributes while the outgoing's voice leaves, and a bed that
     * arrived by cut would put a step where the ear is listening for continuity. On a one-bar hold
     * the bar it takes IS the bar the outgoing's drums leave on, so the two events are heard as one;
     * on this round's {@link #SLAM_STEPS}-bar slam the bed is up a bar before the hand-over, and that
     * is what carries the ear through it (the bed is the incoming's own content, and its drums and low
     * end are what arrive on the line — see `gainAt`'s slam branch).
     */
    static double bedGain(Plan plan, double fileMs) {
        if (plan == null || !plan.valid) return linear(B_BED_DB);
        double width = plan.bedFadeMs - plan.entryMs;
        if (!(width > 0d)) return linear(B_BED_DB);
        return splice(fileMs, plan.entryMs, width, 0d, linear(B_BED_DB));
    }

    /**
     * The gain of one row, one of the two sources, at a position in the file — the table.
     *
     * <p>Outside the fusion window every one of the incoming's own rows is at {@link #BED_DB}
     * (unity — today's content, the file being the master there) and every carried row is silent.
     */
    public static double gainAt(Plan plan, boolean fromOutgoing, StemGesture.Stem row,
                                double fileMs) {
        if (plan == null || !plan.valid) return fromOutgoing ? 0d : linear(BED_DB);
        if (fileMs < plan.entryMs || fileMs >= plan.fusionEndMs) {
            return fromOutgoing ? 0d : linear(BED_DB);
        }
        double other = linear(A_OTHER_DB);
        double unity = linear(BED_DB);
        if (plan.slam) {
            // A SLAM is the one gesture that still cuts, and deliberately: with no relation to hang
            // a bar on, the element change on its single line IS the gesture (the user's
            // 「速度无关的也要接」 asks for it, and a DJ's slam mix is a cut).
            switch (row) {
                case DRUMS:
                case BASS:
                    return fromOutgoing
                            ? splice(fileMs, plan.swapMs, unity, 0d)
                            : splice(fileMs, plan.swapMs, 0d, unity);
                case OTHER:
                    return fromOutgoing ? splice(fileMs, plan.swapMs, other, 0d)
                            : bedGain(plan, fileMs);
                default:
                    return fromOutgoing ? 0d : unity;
            }
        }
        // ⚠️ Round 6, from listening: A RECEDES, IT DOES NOT CUT. The user, after hearing the
        // splices: 「你似乎给前一首歌强制停止了，不要这么干，让它放完并不要让它戛然而止，可以加淡出，但不要抢整体效果」
        // — do not force-stop the outgoing track, let it play out; an 80 ms splice on a bar line is
        // exactly the "stopped" they heard. So the outgoing's rows hold at unity through
        // {@link #A_HOLD_STEPS} steps and then fade out over their own spans — the drums over one
        // step, the low end and the melodic row over two, equal-power and monotone, so no element
        // comes back and the sum only ever falls. The hold is what keeps the FILE continuous with
        // A's live deck for the whole of the deck-level handover (which the boundary stretch to
        // about a bar), so the two fades do not stack.
        switch (row) {
            case DRUMS:
                return fromOutgoing
                        ? fade(fileMs, plan.holdEndMs, plan.drumsEndMs, unity, 0d)
                        : fadeIn(fileMs, plan.arriveStartMs, plan.arriveEndMs, unity);
            case BASS:
                return fromOutgoing
                        ? fade(fileMs, plan.holdEndMs, plan.lowEndEndMs, unity, 0d)
                        : fadeIn(fileMs, plan.arriveStartMs, plan.arriveEndMs, unity);
            case OTHER:
                // A's melodic row recedes with the low end (they are the same instrument group);
                // B's bed still arrives over its own first bar (see bedGain).
                return fromOutgoing ? fade(fileMs, plan.holdEndMs, plan.lowEndEndMs, other, 0d)
                        : bedGain(plan, fileMs);
            default:
                // The outgoing's vocal row is never carried, and the incoming's is the edit's own
                // gate — see incomingGains.
                return fromOutgoing ? 0d : unity;
        }
    }

    /** A row that holds {@code from} until {@code fromMs}, fades equal-power to {@code to} at
     *  {@code toMs}, and is {@code to} for the rest of the window — the leaving side of round 6's
     *  gesture ({@link #gainAt}). Chosen over a splice because a splice is a stop: one step for the
     *  drums and two for the low end and the melodic row (see {@link #A_HOLD_STEPS}). */
    static double fade(double tMs, long fromMs, long toMs, double from, double to) {
        if (tMs <= fromMs) return from;
        if (tMs >= toMs) return to;
        double frac = toMs > fromMs ? (tMs - fromMs) / (double) (toMs - fromMs) : 1d;
        if (to == 0d) {
            // A decline to the floor is the COSINE of the arrival's sine: at the half-way point each
            // side is at sin(pi/4) = cos(pi/4), so the two sides of a hand-over sum to a constant
            // power. (A sine from 1 to 0 would sit at 0.29 half way — the wrong shape, and the
            // measurement of the fixture is what caught it.)
            return from * Math.cos(frac * Math.PI / 2d);
        }
        return from + (to - from) * Math.sin(frac * Math.PI / 2d);
    }

    /** The arriving side of the same shape: silence before {@code fromMs}, {@code level} from
     *  {@code toMs} — round 6's incoming, which arrives by a rise rather than by a cut. (The
     *  outgoing's departure is what must not be a stop; a step into an otherwise continuous
     *  backing is a click, so the arrival is a rise too, over the incoming's own bar.) */
    static double fadeIn(double tMs, long fromMs, long toMs, double level) {
        if (tMs <= fromMs) return 0d;
        if (tMs >= toMs) return level;
        double frac = toMs > fromMs ? (tMs - fromMs) / (double) (toMs - fromMs) : 1d;
        return level * Math.sin(frac * Math.PI / 2d);
    }

    /** The incoming's own rows: the fusion's table, and today's vocal gate on the voice. */
    public static DjEdit.RowGain incomingGains(Plan plan, DjEdit.Plan edit) {
        return (row, atSec) -> fileGain(plan, edit, false, row, true, atSec * 1000d);
    }

    /** The carried rows' own gains over the same timeline. */
    public static DjEdit.RowGain carriedGains(Plan plan, boolean withMelody) {
        return (row, atSec) -> fileGain(plan, null, true, row, withMelody, atSec * 1000d);
    }

    /**
     * The table evaluated on the FILE's own timeline — the one definition both the render and the
     * measurement use, because both of them need "what is this row at this moment" and there is no
     * second answer.
     *
     * @param edit the edit's vocal plan, for the incoming's voice; null is only ever passed for a
     *             carried row, whose voice is silent by construction
     */
    private static double fileGain(Plan plan, DjEdit.Plan edit, boolean fromOutgoing,
                                   StemGesture.Stem row, boolean withMelody, double fileMs) {
        if (fromOutgoing) {
            if (row == StemGesture.Stem.VOCALS) return 0d;
            if (row == StemGesture.Stem.OTHER && !withMelody) return 0d;
            return gainAt(plan, true, row, fileMs);
        }
        if (row == StemGesture.Stem.VOCALS) {
            return edit != null ? edit.vocalGainAt(fileMs / 1000d) : 0d;
        }
        return gainAt(plan, false, row, fileMs);
    }

    /**
     * The material the fusion carries: the outgoing's {@code drums}, {@code bass} and (unless it
     * has been dropped) {@code other} rows, taken from its own file at the junction, pre-lengthened
     * by {@code speed} and clipped to the fusion window.
     *
     * <p>Pre-lengthening is the whole reason the passage keeps the outgoing track's tempo: the
     * incoming deck plays this file {@code speed} times as fast, so material stretched by exactly
     * {@code speed} on the way in comes out at its own tempo and its own pitch — the same bargain
     * {@link StemBridge} makes for its carried bass, and exact when {@code speed == 1} (a pair
     * whose grids already hold, which is the ordinary case).
     *
     * <p>The outgoing's vocal row is never returned: {@code vocals} is absent from the result
     * whatever the caller asks for. What comes back is the material <em>as taken</em>; the table's
     * gains go on it with {@link #gate}, which is what the render mixes in and what the
     * measurement measures.
     *
     * @param outgoingStems  the outgoing's separated stems over the window that was decoded,
     *                       {@code [row][channel][sample]}
     * @param stemsStartMs   where that separation starts in the outgoing's own file, ms
     * @return {@code [row][channel][windowFrames]}, with absent rows empty
     */
    public static float[][][] carry(float[][][] outgoingStems, int rate, long stemsStartMs,
                                    Plan plan, double speed, boolean withMelody) {
        int rows = StemGesture.Stem.ALL.length;
        float[][][] out = new float[rows][][];
        if (plan == null || !plan.valid || outgoingStems == null) return out;
        int frames = (int) Math.round(plan.windowMs * rate / 1000d);
        int from = (int) Math.round((plan.sourceFromMs - stemsStartMs) * rate / 1000d);
        int span = (int) Math.round(plan.sourceSpanMs * rate / 1000d);
        for (StemGesture.Stem row : StemGesture.Stem.ALL) {
            if (row == StemGesture.Stem.VOCALS) continue;
            if (row == StemGesture.Stem.OTHER && !withMelody) continue;
            float[][] source = outgoingStems[row.row()];
            if (source == null || source.length == 0) continue;
            out[row.row()] = carried(source, from, span, speed, frames);
        }
        return out;
    }

    /**
     * One row's carried material: {@code span} samples of the source from {@code from},
     * pre-lengthened by {@code ratio} and clipped to {@code frames} (the window's own length).
     *
     * <p>The interpolator is {@link StemBridge}'s ({@code AndroidStemEditRenderer.resample}'s
     * relative), with the sense the words demand: the source is <em>read</em> {@code 1/ratio}
     * samples per output sample, so {@code ratio > 1} makes the material longer and its pitch
     * lower — and playing it back {@code ratio} times faster makes both exactly what they were.
     * {@link StemBridge#stretch} is the same arithmetic on the legacy bridge's carry, and the two
     * are pinned together by the tests.
     *
     * <p>The read stops at {@code from + span}, not at the end of the source array: {@code span}
     * is the material A's rows actually occupy in the file ({@link Plan#sourceSpanMs}), so nothing
     * from past its own last cut can leak into the passage — the table's gains would zero it
     * anyway, and a take that reached for it would be a take nobody measured.
     */
    static float[][] carried(float[][] source, int from, int span, double ratio, int frames) {
        int channels = source.length;
        float[][] out = new float[channels][frames];
        double step = ratio > 0d ? 1d / ratio : 1d;
        for (int ch = 0; ch < channels; ch++) {
            float[] src = source[ch];
            if (src == null) continue;
            int end = Math.max(0, Math.min(src.length, from + span));
            for (int i = 0; i < frames; i++) {
                double at = from + i * step;
                int lo = (int) at;
                if (lo >= end || lo < 0) break;
                int hi = Math.min(lo + 1, end - 1);
                double frac = at - lo;
                out[ch][i] = (float) (src[lo] * (1d - frac) + src[hi] * frac);
            }
        }
        return out;
    }

    /** A slice of one signal starting at {@code from}, {@code frames} long (silence past its
     *  end): the reference a carried row is measured against. */
    static float[][] slice(float[][] pcm, int from, int frames) {
        if (pcm == null) return null;
        float[][] out = new float[pcm.length][frames];
        for (int ch = 0; ch < pcm.length; ch++) {
            float[] src = pcm[ch];
            int copy = src == null ? 0
                    : Math.max(0, Math.min(frames, src.length - Math.max(0, from)));
            if (copy > 0) System.arraycopy(src, Math.max(0, from), out[ch], 0, copy);
        }
        return out;
    }

    /** Two signals summed; the shorter one is silence past its end. */
    static float[][] add(float[][] a, float[][] b) {
        return a == null ? b : b == null ? a : StemBridge.add(a, b);
    }

    // --- the acceptance measurement -------------------------------------------

    /** The material one fusion's acceptance is measured on: what went into the file and what it
     *  was taken from. Every array is over the fusion window ({@link Material#frames} samples at
     *  {@link Material#rate}) unless its own note says otherwise. */
    public static final class Material {
        /** Ⓜ Round 33: this material was built only to solve the make-up gain, so its report stops
         *  after the junction's step — see {@link #measure}. Set by the renderer on the probe pass
         *  and false everywhere else, so every other caller and every fixture measures exactly what
         *  it always measured.
         *
         *  <p>Why it is worth a flag: the device's own timing line put the report at 18–25 s of a
         *  20–24 s render (the carried rows' assembly 0 ms, the head's own render 1.3–1.9 s), and
         *  {@code attemptFusion} renders the passage TWICE per attempt — once at make-up 0 only to
         *  learn the step, then with the solved make-up whose report the acceptance reads. On the
         *  probe pass the other twenty-odd measurements exist to be thrown away. */
        public boolean stepOnly;

        public final int rate;
        /** The fusion window's length, samples. */
        public final int frames;
        /** The ratio the incoming deck plays this file at — the ratio the carried material is
         *  pre-lengthened by, so {@code 1/speed} is how source samples map to file frames. */
        public final double speed;
        /** The make-up gain already applied to the outgoing's carried rows, dB (0 when none was
         *  needed). It is part of how the material was placed, so the row clauses are measured
         *  against the source lifted by it — see {@link StemFusion#makeupDb}. */
        public final double makeupDb;
        /** The outgoing's carried rows AS PLACED (the table applied — see {@link StemFusion#gate}):
         *  {@code [row][channel][frames]}. Rows that were not carried are empty. */
        public final float[][][] carried;
        /** The same span of the outgoing's own stems, unstretched and ungated — the reference
         *  every level clause is against. */
        public final float[][][] carriedSource;
        /** The incoming's head rows over the window, ungated. */
        public final float[][][] incoming;
        /** The incoming's vocal stem under the edit's own gate over the window. */
        public final float[][][] incomingVocals;
        /** The outgoing's separated vocal stem over the carried span — what must NOT have come
         *  along. */
        public final float[][] outgoingVocals;
        /** The head AS RENDERED (the limiter's own output), over the window: the only thing the
         *  listener's first {@link #STEP_WINDOW_MS} actually is. Null when the caller has no
         *  rendered head — and then the junction's step cannot be asserted and is refused. */
        public final float[][] head;
        /** The OUTGOING's own master over the {@link #STEP_WINDOW_MS} before
         *  {@link Plan#junctionMs} of its own file — the level the junction has to sit at. Null
         *  when the caller has not decoded it, with the same consequence as {@link #head}. */
        public final float[][] outgoingMaster;
        /** The level the step clause is measured against, dBFS, or NaN to use the master's own
         *  level ({@link #outgoingMaster}).
         *
         *  <p>Round 5: the renderer clamps it up to the outgoing track's body
         *  ({@link StemFusion#referenceDb}) so that a fading tail cannot be the reference a fusion
         *  is levelled to — with the tail as the reference the step is near zero for any passage,
         *  and a passage written inside a ten-second fade passed this clause by being as quiet as
         *  the fade, which is the flat transition the user reported. */
        public final double referenceDb;
        /** How much of the window the incoming's own voice is held at <b>exactly zero</b> by the
         *  gate this render wrote, ms — round 25.
         *
         *  <p>⚠️ The voice clause is measured over THIS span and not over the whole window, because
         *  the coupling puts the voice back at the outgoing's own departure, which is at or before
         *  the window's end by construction: judged over the whole window, every coupled fusion
         *  would be refused for carrying the voice the design just brought back in. The plan's own
         *  gate minus its own return ramp is the span, so what the clause still judges is exactly
         *  what it always judged — is the passage instrumental while the outgoing can be heard.
         *
         *  <p>{@link #UNKNOWN_VOICE_SPAN} (-1) means the caller said nothing, and then the span is
         *  the whole window, which is round 18's own reading and what every fixture that predates
         *  the coupling keeps. A span of exactly 0 is a window in which the gate holds nothing at
         *  zero, and the reading is then "not measured" rather than "silence". */
        public final long voiceHeldMs;

        /** Where {@link #voiceHeldMs} was not given: the whole window is the span. */
        public static final long UNKNOWN_VOICE_SPAN = -1L;

        public Material(int rate, int frames, double speed, double makeupDb, float[][][] carried,
                        float[][][] carriedSource, float[][][] incoming,
                        float[][][] incomingVocals, float[][] outgoingVocals, float[][] head,
                        float[][] outgoingMaster) {
            this(rate, frames, speed, makeupDb, carried, carriedSource, incoming, incomingVocals,
                    outgoingVocals, head, outgoingMaster, Double.NaN, UNKNOWN_VOICE_SPAN);
        }

        public Material(int rate, int frames, double speed, double makeupDb, float[][][] carried,
                        float[][][] carriedSource, float[][][] incoming,
                        float[][][] incomingVocals, float[][] outgoingVocals, float[][] head,
                        float[][] outgoingMaster, double referenceDb) {
            this(rate, frames, speed, makeupDb, carried, carriedSource, incoming, incomingVocals,
                    outgoingVocals, head, outgoingMaster, referenceDb, UNKNOWN_VOICE_SPAN);
        }

        public Material(int rate, int frames, double speed, double makeupDb, float[][][] carried,
                        float[][][] carriedSource, float[][][] incoming,
                        float[][][] incomingVocals, float[][] outgoingVocals, float[][] head,
                        float[][] outgoingMaster, double referenceDb, long voiceHeldMs) {
            this.rate = rate;
            this.frames = frames;
            this.speed = speed > 0d ? speed : 1d;
            this.makeupDb = makeupDb;
            this.carried = carried;
            this.carriedSource = carriedSource;
            this.incoming = incoming;
            this.incomingVocals = incomingVocals;
            this.outgoingVocals = outgoingVocals;
            this.head = head;
            this.outgoingMaster = outgoingMaster;
            this.referenceDb = referenceDb;
            this.voiceHeldMs = voiceHeldMs;
        }

        public Material(int rate, int frames, double speed, float[][][] carried,
                        float[][][] carriedSource, float[][][] incoming,
                        float[][][] incomingVocals, float[][] outgoingVocals, float[][] head,
                        float[][] outgoingMaster) {
            this(rate, frames, speed, 0d, carried, carriedSource, incoming, incomingVocals,
                    outgoingVocals, head, outgoingMaster);
        }

        /** The material without a rendered head or the outgoing's own master: every clause below
         *  is measured, and the junction's step is refused for want of a measurement (see
         *  {@link #head}). */
        public Material(int rate, int frames, float[][][] carried, float[][][] carriedSource,
                        float[][][] incoming, float[][][] incomingVocals,
                        float[][] outgoingVocals) {
            this(rate, frames, 1d, 0d, carried, carriedSource, incoming, incomingVocals,
                    outgoingVocals, null, null);
        }
    }

    /** What one fusion measured, and the verdict taken from it — the same shape as
     *  {@link StemBridge.Report}, because it is the same kind of statement. */
    public static final class Report {
        /** dBFS of each carried row as it will be heard, inside the part of the window where the
         *  row is on. */
        public double carriedDrumsDb;
        public double carriedBassDb;
        public double carriedMelodyDb;
        /** dBFS of the material each row was taken from — the level the carry preserves. */
        public double sourceDrumsDb;
        public double sourceBassDb;
        public double sourceMelodyDb;
        /** How much of the outgoing's vocal stem each carried row is, at zero lag. Never carried,
         *  so these must stay at the floor; the melodic row is the one that is dropped rather
         *  than fatal when it is not. */
        public double voiceAlignmentDrums;
        public double voiceAlignmentBass;
        public double voiceAlignmentMelody;
        /** dBFS of the incoming's own drums before their swap (must be at the floor) and inside
         *  the window after it (must be present). */
        public double incomingDrumsBeforeDb;
        public double incomingDrumsAfterDb;
        /** The same for the low end, which swaps a bar later. */
        public double incomingBassBeforeDb;
        public double incomingBassAfterDb;
        /** dBFS of the incoming's voice under the edit's own gate, over the whole fusion window:
         *  the user's own rule, measured rather than assumed. Must be at the floor. */
        public double incomingVocalDb;
        /** dBFS of the carried low end over the {@link #CARRY_END_WINDOW_MS} before its own cut,
         *  and of the material it was taken from over the same span of the outgoing's own file:
         *  the measurement that says the carry is still there <em>up to {@code bassMs}</em> — which
         *  is the instant the take's length is derived from. */
        public double carriedBassEndDb;
        public double sourceBassEndDb;
        /** dBFS of the carried low end over its own FADE — the stretch from {@code bassMs} to
         *  {@code fusionEndMs}, where the table takes it to the floor — and of the material it was
         *  taken from over the same span: the measurement that says the fade is fading material and
         *  not a hole the take left behind (see {@link #CARRY_FADE_TOLERANCE_DB}). NaN when the
         *  gesture has no fade (a slam) or the window is too short to hold one. */
        public double carriedBassFadeDb = Double.NaN;
        public double sourceBassFadeDb = Double.NaN;
        /** dBFS of the outgoing's own master over the {@link #STEP_WINDOW_MS} before the junction,
         *  and of the rendered head's first {@link #STEP_WINDOW_MS}: the junction's step is the
         *  difference, and it has to be inside {@link #JUNCTION_STEP_MAX_DB}. */
        public double outgoingMasterDb;
        /** The level the step was actually measured against (the master's own, or the caller's
         *  body-clamped reference) and how far that clamp lifted it, dB. */
        public double referenceDb;
        public double referenceClampedDb;
        public double fusionHeadDb;
        public double junctionStepDb;
        /** The make-up gain that was applied to the outgoing's carried rows, dB (0 when none) —
         *  the reduction the step measured on the render BEFORE it, lifted and clamped by
         *  {@link StemFusion#makeupDb}. Reported so the level the passage sits at is explainable
         *  from the log: this is the app answering the removal of the outgoing's voice. */
        public double makeupDb;
        /** True when both sides of the step were actually measured (both arrays were handed in). */
        public boolean stepMeasured;
        /** The pitch the carried material sits at INSIDE the file, semitones (0 when no tempo was
         *  applied): the plain resample's own artefact. The deck plays the file at exactly that
         *  ratio, so what the listener hears is the outgoing track's own pitch again. */
        public double pitchOffsetSemitones;
        /** How many (channel, sample) pairs the clamp touched inside the window, and the longest
         *  run of frames it was at full scale for. */
        public int clippedPairs;
        /** How many pairs the window itself holds, for the share. */
        public int pairs;
        public double clippedShare;
        public double longestClipRunMs;
        /** Beats of the slower of the two grids that carry an attack in the passage's own rhythm
         *  rows (both backings' drums and bass), over all of them; the gap is the pulse's
         *  continuity through the splices. */
        public int beatsWithAttack;
        public int beats;
        public double longestGapMs;
        public double judgedPeriodMs;
        /** The outgoing's melodic carry was dropped because it was its voice. */
        public boolean melodyDropped;
        /** True when every clause of the acceptance holds. */
        public boolean acceptable;
        /** Which clause did not, when one did not. */
        public String failures = "";

        /** The pulse clause's own number, in one line: what the retry loop is judged by, so every
         *  log about it quotes the same measurement the acceptance does. */
        public String pulseLine() {
            return String.format(Locale.US, "pulse: %d of %d beats carry an attack, longest gap"
                            + " %.0fms of a %.0fms period", beatsWithAttack, beats, longestGapMs,
                    judgedPeriodMs);
        }

        /** The evidence, in one line. */
        public String describe() {
            return String.format(Locale.US,
                    "fusion measured: carried drums %.1f / bass %.1f / melodic %.1f dBFS against"
                            + " their sources %.1f / %.1f / %.1f; the low end still there where the"
                            + " gesture takes over (%.1f vs %.1f dBFS in the last %.0fms) and its own"
                            + " fade fading material (%.1f vs %.1f dBFS over the fade, where the"
                            + " table's own equal-power fade is %+.2f dB and the make-up %+.2f); the"
                            + " outgoing's voice in"
                            + " them: %.2f / %.2f / %.2f; the incoming's own drums %.1f dBFS before"
                            + " their swap and %.1f after, its low end %.1f and %.1f, its voice %.1f"
                            + " (the floor is %.0f); the junction: the outgoing master's last %.0fms"
                            + " %.1f dBFS against the fusion's first %.0fms %.1f dBFS = %+.2f dB"
                            + " (the most is %.1f); pitch inside the file %+.2f semitones; the"
                            + " outgoing's rows lifted %+.2f dB to sit there; clipped %d"
                            + " of %d samples (%.2f%%, longest run %.0fms); pulse: %d of %d beats"
                            + " carry an attack, longest gap %.0fms of a %.0fms period%s",
                    carriedDrumsDb, carriedBassDb, carriedMelodyDb, sourceDrumsDb, sourceBassDb,
                    sourceMelodyDb, carriedBassEndDb, sourceBassEndDb,
                    (double) CARRY_END_WINDOW_MS, carriedBassFadeDb, sourceBassFadeDb,
                    -FADE_RMS_DB, makeupDb,
                    voiceAlignmentDrums, voiceAlignmentBass,
                    voiceAlignmentMelody, incomingDrumsBeforeDb, incomingDrumsAfterDb,
                    incomingBassBeforeDb, incomingBassAfterDb, incomingVocalDb, SILENT_DBFS,
                    (double) STEP_WINDOW_MS, outgoingMasterDb, (double) STEP_WINDOW_MS,
                    fusionHeadDb, junctionStepDb, JUNCTION_STEP_MAX_DB, pitchOffsetSemitones,
                    makeupDb, clippedPairs, pairs, clippedShare, longestClipRunMs, beatsWithAttack,
                    beats, longestGapMs, judgedPeriodMs,
                    melodyDropped ? "; the melodic carry was dropped (it measured as the outgoing's"
                            + " voice)" : "")
                    + (acceptable ? "" : " -- NOT ACCEPTABLE: " + failures);
        }
    }

    /**
     * Measure one rendered fusion.
     *
     * <p>The clauses, and why each of them is the one that matters here:
     * <ol>
     *   <li><b>The carried rows are there and are the material they claim.</b> Each row, inside
     *       the part of the window where the table has it on, must measure within {@link
     *       #CARRY_TOLERANCE_DB} of its own source level under the table's gain — no more than
     *       3 dB below it (a carry that lost its groove) and no more than 3 dB above the row's
     *       own source (a carry summed twice, or the wrong row placed). The carried low end is
     *       additionally asked about the {@link #CARRY_END_WINDOW_MS} <em>right before the gesture
     *       takes over</em>: a take that is shorter than the gesture needs goes silent exactly
     *       there (that was round 18's defect — A's bed dying on a hard cut the table never
     *       names), and a clause measured over the whole of [entry, bassMs) would average it
     *       away. Round 6 added the matching clause for the fade itself, where the same defect
     *       now hides: {@link #carriedBassFadeDb} against the source under the table's own
     *       equal-power fade ({@link #FADE_RMS_DB}, tolerance {@link
     *       #CARRY_FADE_TOLERANCE_DB}).</li>
     *   <li><b>The outgoing's vocal content did not come along.</b> Each carried row is compared
     *       with the outgoing's separated vocal stem at zero lag: a row that is that voice is
     *       fatal for the drums and the bass and <em>drops the melodic carry</em> for {@code
     *       other} — the design's stated degradation, which keeps the groove and loses the
     *       pad.</li>
     *   <li><b>The incoming's drums are silent before their swap and present after</b> (and the
     *       same for its low end a bar later) — the table's own claim, measured rather than
     *       asserted.</li>
     *   <li><b>The junction sits at the outgoing's own level.</b> The rendered head's first {@link
     *       #STEP_WINDOW_MS} against the outgoing master's last {@link #STEP_WINDOW_MS}, within
     *       {@link #JUNCTION_STEP_MAX_DB}. This is the clause the round-18 prototype's junction
     *       failed on real material (the static divisor's −4.10 dB, which the listener heard as a
     *       卡顿), and the reason the head carries a limiter and the bed arrives over a bar.</li>
     *   <li><b>No long clipping.</b> The sum of two backings can exceed full scale; a rare
     *       clamped peak is a splice landing on one, but a run or a share past the limits means
     *       the passage is mixed too loud to be the master.</li>
     *   <li><b>The pulse holds.</b> Attacks on the slower grid, judged on the passage's own
     *       rhythm rows (both backings' drums and bass, as the table places them) rather than on
     *       the whole mix: the melodic beds are deliberately present the whole time and would
     *       hide a hole in the groove. This is the clause that catches an outgoing ending with no
     *       rhythm in it — a fade, a break, a spoken outro — which is the same hazard {@link
     *       StemBridge#measure} exists for.</li>
     * </ol>
     *
     * @param plan   the fusion
     * @param edit   the edit's own vocal plan, for the incoming's gate
     * @param guard  the clamp count over the window (see {@link DjEdit.ClipGuard}), or null
     */
    public static Report measure(Plan plan, DjEdit.Plan edit, Material m, boolean withMelody,
                                 double beatSecA, double beatSecB, DjEdit.ClipGuard guard) {
        Report r = new Report();
        r.melodyDropped = !withMelody;
        if (plan == null || !plan.valid || m == null) {
            r.acceptable = false;
            r.failures = "there is no fusion to measure";
            return r;
        }
        int rate = m.rate;
        // ⚠️ Round 6: the spans the row clauses are measured over follow the new shape. The
        // outgoing's rows hold at unity until its hold ends (which is `bassMs`, the instant the low
        // end starts to recede), and the incoming's rows are absent only until their own rise BEGINS
        // — which is one step BEFORE the hold ends, because that is what gives the passage its
        // coexistence. Measuring "the incoming's drums are silent before their swap" over
        // [entry, swapMs) would fail for every fusion now that the arrival starts under the hold,
        // which is exactly what the fixture run caught.
        long swapIn = plan.holdEndMs > 0L ? plan.holdEndMs - plan.entryMs : plan.swapMs - plan.entryMs;
        long bassIn = swapIn;
        // Where A's rows have all reached the floor (the low end's fade's end, which is the
        // gesture's end): the fade the clause below measures is [bassIn, carryEndIn).
        long carryEndIn = plan.fusionEndMs > 0L ? plan.fusionEndMs - plan.entryMs : bassIn;
        long arriveIn = plan.arriveStartMs > 0L ? plan.arriveStartMs - plan.entryMs : swapIn;
        long arrivedIn = plan.arriveEndMs > 0L ? plan.arriveEndMs - plan.entryMs : swapIn;
        // The material's own end, ms: the window the render holds, which is where every span stops.
        long endMs = Math.round(m.frames * 1000d / rate);
        double windowSec = m.frames / (double) rate;

        r.carriedDrumsDb = StemBridge.levelDb(carried(m, StemGesture.Stem.DRUMS.row()), rate,
                swapIn / 1000d);
        r.carriedBassDb = StemBridge.levelDb(carried(m, StemGesture.Stem.BASS.row()), rate,
                bassIn / 1000d);
        r.sourceDrumsDb = StemBridge.levelDb(source(m, StemGesture.Stem.DRUMS.row()), rate,
                swapIn / 1000d);
        r.sourceBassDb = StemBridge.levelDb(source(m, StemGesture.Stem.BASS.row()), rate,
                bassIn / 1000d);
        // ⚠️ The pitch the carried material sits at INSIDE the file is the resample's own ratio
        // (`speed`), not `windowMs/sourceSpanMs`: the span the take needs is drawn from bassMs, so
        // the two are not the same number (see sourceSpanMs). The deck plays the file at the same
        // ratio, which is what hands the listener the outgoing track's own pitch again.
        r.pitchOffsetSemitones = Math.abs(m.speed - 1d) > 1e-9d
                ? 12d * (Math.log(m.speed) / Math.log(2d)) : 0d;

        // The carry right up to its own last cut: the stretch of the outgoing's material
        // immediately before bassMs, against the same stretch of its own file. This is the clause
        // a take that is too short fails, and it fails here rather than nowhere (see
        // CARRY_END_WINDOW_MS).
        int endFrom = (int) Math.round(framewise(Math.max(0L, bassIn - CARRY_END_WINDOW_MS), rate));
        int endTo = (int) Math.round(framewise(bassIn, rate));
        int endFrames = Math.max(0, endTo - endFrom);
        float[][] bassEnd = slice(carried(m, StemGesture.Stem.BASS.row()), endFrom, endFrames);
        float[][] bassEndSource = slice(source(m, StemGesture.Stem.BASS.row()),
                (int) Math.round(endFrom / m.speed), (int) Math.round(endFrames / m.speed));
        r.carriedBassEndDb = StemBridge.levelDb(bassEnd, rate, endFrames / (double) rate);
        r.sourceBassEndDb = StemBridge.levelDb(bassEndSource, rate,
                Math.round(endFrames / m.speed) / (double) rate);

        // ⚠️ Round 6: the rows FADE instead of cutting, so the clause above — the last
        // CARRY_END_WINDOW_MS before the gesture takes over — is no longer the whole story: inside
        // the fade the rendered row is falling by the table's own design, so an absolute level
        // against the source reads a shape, not a hole, and a take that ended in the middle of the
        // fade would fade silence with every clause still green. The fade's own gain is analytic
        // (equal-power: the mean of cos^2 over the span is 1/2, i.e. FADE_RMS_DB under the
        // material), so the material's presence INSIDE it is measurable — that is this measurement.
        int fadeFrom = (int) Math.round(framewise(Math.max(0L, bassIn), rate));
        int fadeFrames = plan.slam ? 0
                : Math.max(0, (int) Math.round(framewise(carryEndIn, rate)) - fadeFrom);
        if (fadeFrames > 0) {
            float[][] bassFade = slice(carried(m, StemGesture.Stem.BASS.row()), fadeFrom,
                    fadeFrames);
            float[][] bassFadeSource = slice(source(m, StemGesture.Stem.BASS.row()),
                    (int) Math.round(fadeFrom / m.speed), (int) Math.round(fadeFrames / m.speed));
            r.carriedBassFadeDb = StemBridge.levelDb(bassFade, rate, fadeFrames / (double) rate);
            r.sourceBassFadeDb = StemBridge.levelDb(bassFadeSource, rate,
                    Math.round(fadeFrames / m.speed) / (double) rate);
        }

        // The junction's step: the level the listener was already at (the outgoing's own master,
        // its last STEP_WINDOW_MS) against the level the fusion arrives at (the rendered head's
        // first STEP_WINDOW_MS). Measured on the head AS RENDERED — the limiter included — because
        // that is what is in the file.
        int stepFrames = (int) Math.round(framewise(STEP_WINDOW_MS, rate));
        int headFrames = Math.min(stepFrames, m.frames);
        int masterFrames = m.outgoingMaster == null || m.outgoingMaster.length == 0
                ? 0 : Math.min(stepFrames, m.outgoingMaster[0] == null ? 0
                        : m.outgoingMaster[0].length);
        if (m.head != null && m.head[0] != null && masterFrames > 0 && headFrames > 0) {
            float[][] headStart = slice(m.head, 0, headFrames);
            r.fusionHeadDb = StemBridge.levelDb(headStart, rate, headFrames / (double) rate);
            r.outgoingMasterDb = StemBridge.levelDb(m.outgoingMaster, rate,
                    masterFrames / (double) rate);
            // Round 5: the reference the step is measured against — the master's own level, or the
            // caller's (the renderer clamps it up to the outgoing's body, see referenceDb).
            r.referenceDb = Double.isNaN(m.referenceDb) ? r.outgoingMasterDb : m.referenceDb;
            r.referenceClampedDb = r.referenceDb - r.outgoingMasterDb;
            r.junctionStepDb = r.fusionHeadDb - r.referenceDb;
            r.stepMeasured = true;
        }
        // Ⓜ Round 33: the probe pass stops here. Everything below measures the passage the make-up
        // is applied to — the carried rows' levels, the voices, the pulse, the fade — and on this
        // pass none of it can change the one number the caller wants ({@link #junctionStepDb}, and
        // through it {@link #makeupDb}), because the step is read off {@code m.head} and
        // {@code m.outgoingMaster} alone. The whole of the probe's cost was below this line.
        if (m.stepOnly) return r;

        // The voice question is asked over the part of the window where the row is audible: a row
        // that is silent for the second half of the passage would otherwise "align" less with the
        // voice than the material it is made of does.
        r.voiceAlignmentDrums = Math.abs(StemBridge.alignmentAtZeroLag(
                carried(m, StemGesture.Stem.DRUMS.row()), m.outgoingVocals, rate,
                swapIn / 1000d, null));
        r.voiceAlignmentBass = Math.abs(StemBridge.alignmentAtZeroLag(
                carried(m, StemGesture.Stem.BASS.row()), m.outgoingVocals, rate,
                bassIn / 1000d, null));

        float[][] melody = carried(m, StemGesture.Stem.OTHER.row());
        boolean hasMelody = withMelody && melody.length > 0 && melody[0] != null;
        if (hasMelody) {
            r.carriedMelodyDb = StemBridge.levelDb(melody, rate, bassIn / 1000d);
            r.sourceMelodyDb = StemBridge.levelDb(source(m, StemGesture.Stem.OTHER.row()), rate,
                    bassIn / 1000d);
            r.voiceAlignmentMelody = Math.abs(StemBridge.alignmentAtZeroLag(melody,
                    m.outgoingVocals, rate, bassIn / 1000d, null));
        }

        // The incoming's own rows, as the table places them.
        float[][] inDrums = gated(m.incoming, StemGesture.Stem.DRUMS, plan, edit, rate, m.frames);
        float[][] inBass = gated(m.incoming, StemGesture.Stem.BASS, plan, edit, rate, m.frames);
        // ⚠️ AN EMPTY SPAN IS "UNKNOWN", NOT "DIGITAL SILENCE" (round 6, tenth pass). A clause must
        // never fail because the window it was handed holds no samples: on a SLAM the incoming's
        // rows arrive ON the line that ends its window, so there is nothing after them to measure
        // inside the file — the body's own audio continues there, and the file is one continuous
        // source by construction — and the old arithmetic read that as −240 dBFS and refused every
        // slam with "the incoming's own drums never arrive". The spans are therefore measured in
        // frames first, and a span that is empty reports NaN, which every clause below treats as
        // "not measured" rather than as a floor.
        r.incomingDrumsBeforeDb = levelOfSpan(inDrums, rate, 0L, arriveIn, m.frames);
        r.incomingDrumsAfterDb = levelOfSpan(inDrums, rate, arrivedIn, endMs, m.frames);
        r.incomingBassBeforeDb = levelOfSpan(inBass, rate, 0L, arriveIn, m.frames);
        r.incomingBassAfterDb = levelOfSpan(inBass, rate, arrivedIn, endMs, m.frames);
        r.incomingVocalDb = m.voiceHeldMs == 0L ? Double.NaN
                : StemBridge.levelDb(m.incomingVocals != null
                        && StemGesture.Stem.VOCALS.row() < m.incomingVocals.length
                        ? m.incomingVocals[StemGesture.Stem.VOCALS.row()] : new float[0][], rate,
                        // ⚠️ Round 25: the span is the stretch the gate holds the voice at exactly
                        // zero, not the window's own end — see Material#voiceHeldMs. Judged over the
                        // whole window this clause would refuse every coupled fusion for carrying the
                        // voice the coupling just brought back in at the departure.
                        m.voiceHeldMs > 0L ? Math.min(m.voiceHeldMs, endMs) / 1000d : windowSec);

        // The pulse, on the passage's own rhythm rows: what the file adds of the outgoing's
        // groove, plus the incoming's own under the table.
        float[][] rhythm = add(add(carried(m, StemGesture.Stem.DRUMS.row()),
                        carried(m, StemGesture.Stem.BASS.row())),
                add(inDrums, inBass));
        double period = plan.slam ? 0d
                : Math.max(beatSecA > 0d ? beatSecA : 0d, beatSecB > 0d ? beatSecB : 0d);
        r.judgedPeriodMs = period * 1000d;
        if (period > 0d) {
            double[] levels = StemBridge.frameLevelsDb(rhythm, rate, windowSec);
            double median = StemBridge.median(levels);
            // ⚠️ A SLAM's gesture is a cut, not a recede, so there is no passage pulse to
        // judge: the clause is skipped for it (round 5) and the carried-row and voice clauses stand.
        int perBeat = Math.max(1, (int) Math.round(period / StemBridge.FRAME_SEC));
            r.beats = Math.max(1, levels.length / perBeat);
            int attacks = 0;
            double longest = 0d;
            double run = 0d;
            for (int b = 0; b < r.beats; b++) {
                boolean attack = false;
                for (int f = b * perBeat; f < Math.min(levels.length, (b + 1) * perBeat); f++) {
                    if (levels[f] > median + PULSE_ATTACK_DB) {
                        attack = true;
                        break;
                    }
                }
                if (attack) {
                    attacks++;
                    run = 0d;
                } else {
                    run += period;
                    longest = Math.max(longest, run);
                }
            }
            r.beatsWithAttack = attacks;
            r.longestGapMs = longest * 1000d;
        }

        if (guard != null) {
            r.clippedPairs = guard.clipped;
            r.pairs = guard.pairs;
            r.clippedShare = guard.share();
            r.longestClipRunMs = guard.longestRunMs();
        }

        StringBuilder bad = new StringBuilder();
        r.makeupDb = m.makeupDb;
        if (!(r.carriedDrumsDb > -60d)) bad.append("the carried drums are silent; ");
        if (!(r.carriedBassDb > -60d)) bad.append("the carried bass is silent; ");
        rowClause(bad, "drums", r.carriedDrumsDb, r.sourceDrumsDb, 0d, m.makeupDb);
        rowClause(bad, "bass", r.carriedBassDb, r.sourceBassDb, 0d, m.makeupDb);
        if (!(r.carriedBassEndDb > r.sourceBassEndDb + m.makeupDb - CARRY_TOLERANCE_DB)) {
            bad.append("the carried low end is not there up to its own cut (")
                    .append(fmt(r.carriedBassEndDb)).append(" dBFS over the last ")
                    .append(CARRY_END_WINDOW_MS).append("ms before it, against ")
                    .append(fmt(r.sourceBassEndDb)).append(" in the material it was taken from")
                    .append(m.makeupDb == 0d ? "" : ", lifted " + fmt(m.makeupDb) + " dB")
                    .append("); ");
        }
        if (hasMelody) rowClause(bad, "the melodic carry", r.carriedMelodyDb, r.sourceMelodyDb,
                A_OTHER_DB, m.makeupDb);
        // ⚠️ Round 6's clause, and the one the fade needed: the carried low end's own fade has to be
        // fading material. The table's fade is equal-power, so the row must measure FADE_RMS_DB under
        // its source (plus the make-up) and no further — a take that ended inside the fade fades
        // silence and reads tens of dB under that line, which is round 18's defect in its round-6
        // costume. A slam has no fade and is skipped (NaN).
        if (!Double.isNaN(r.carriedBassFadeDb)
                && !(r.carriedBassFadeDb > r.sourceBassFadeDb + m.makeupDb - FADE_RMS_DB
                        - CARRY_FADE_TOLERANCE_DB)) {
            bad.append("the carried low end fades a hole (")
                    .append(fmt(r.carriedBassFadeDb)).append(" dBFS over its own fade, where its")
                    .append(" material measures ").append(fmt(r.sourceBassFadeDb))
                    .append(" and the table's equal-power fade leaves ")
                    .append(fmt(-FADE_RMS_DB)).append(" dB under that");
            if (m.makeupDb != 0d) bad.append(", lifted ").append(fmt(m.makeupDb)).append(" dB");
            bad.append("); ");
        }
        if (r.voiceAlignmentDrums > VOICE_CARRY_LIMIT) {
            bad.append("the carried drums are the outgoing's voice (they align ")
                    .append(fmt(r.voiceAlignmentDrums)).append(" with its vocal stem); ");
        }
        if (r.voiceAlignmentBass > VOICE_CARRY_LIMIT) {
            bad.append("the carried bass is the outgoing's voice (it aligns ")
                    .append(fmt(r.voiceAlignmentBass)).append(" with its vocal stem); ");
        }
        if (hasMelody && r.voiceAlignmentMelody > VOICE_CARRY_LIMIT) {
            bad.append("the melodic carry is the outgoing's voice (it aligns ")
                    .append(fmt(r.voiceAlignmentMelody)).append(" with its vocal stem); ");
        }
        if (!Double.isNaN(r.incomingDrumsBeforeDb) && r.incomingDrumsBeforeDb > SILENT_DBFS) {
            bad.append("the incoming's own drums are not silent before their swap (")
                    .append(fmt(r.incomingDrumsBeforeDb)).append(" dBFS); ");
        }
        if (!Double.isNaN(r.incomingDrumsAfterDb) && !(r.incomingDrumsAfterDb > SILENT_DBFS)) {
            bad.append("the incoming's own drums never arrive (")
                    .append(fmt(r.incomingDrumsAfterDb)).append(" dBFS after their swap); ");
        }
        if (!Double.isNaN(r.incomingBassBeforeDb) && r.incomingBassBeforeDb > SILENT_DBFS) {
            bad.append("the incoming's own low end is not silent before its swap (")
                    .append(fmt(r.incomingBassBeforeDb)).append(" dBFS); ");
        }
        if (!Double.isNaN(r.incomingBassAfterDb) && !(r.incomingBassAfterDb > SILENT_DBFS)) {
            bad.append("the incoming's own low end never arrives (")
                    .append(fmt(r.incomingBassAfterDb)).append(" dBFS after its swap); ");
        }
        if (r.incomingVocalDb > SILENT_DBFS) {
            bad.append("the incoming's voice is in the window (")
                    .append(fmt(r.incomingVocalDb)).append(" dBFS under the edit's own gate); ");
        }
        // ⚠️ The junction's step. This is the clause the round-18 prototype failed on real
        // material (measured −4.10 dB where the static divisor it used held the head down), and
        // the reason the head carries a limiter: a fusion that cannot sit at the level the
        // listener is already at is a drop, and a drop is what the whole round exists to remove.
        if (!r.stepMeasured) {
            bad.append("the junction's step could not be measured (the outgoing master's last ")
                    .append(STEP_WINDOW_MS).append("ms and the fusion's first ")
                    .append(STEP_WINDOW_MS).append("ms were both needed); ");
        } else if (Math.abs(r.junctionStepDb) > JUNCTION_STEP_MAX_DB) {
            bad.append("the fusion's first ").append(STEP_WINDOW_MS).append("ms sits ")
                    .append(String.format(Locale.US, "%+.1f", r.junctionStepDb))
                    .append(" dB from the outgoing master's last ").append(STEP_WINDOW_MS)
                    .append("ms (").append(fmt(r.fusionHeadDb)).append(" vs ")
                    .append(fmt(r.outgoingMasterDb)).append(" dBFS, the most is ")
                    .append(fmt(JUNCTION_STEP_MAX_DB)).append(" dB); ");
        }
        if (r.longestClipRunMs > CLIP_RUN_MAX_MS) {
            bad.append("the passage clips for ").append(fmt(r.longestClipRunMs))
                    .append("ms at a stretch; ");
        }
        if (r.clippedShare > CLIP_SHARE_MAX) {
            bad.append(fmt(r.clippedShare * 100d)).append("% of its samples are clamped; ");
        }
        if (period > 0d && r.longestGapMs > 1.6d * r.judgedPeriodMs) {
            bad.append("the passage's own pulse has a ").append(fmt(r.longestGapMs))
                    .append("ms hole with a ").append(fmt(r.judgedPeriodMs)).append("ms period; ");
        }
        r.acceptable = bad.length() == 0;
        r.failures = bad.toString();
        return r;
    }

    /**
     * One row's level over the span {@code [fromMs, endMs)} of a material window, dBFS — or NaN when
     * the span holds no samples at all.
     *
     * <p>The distinction matters exactly once and it cost every slam on the device: a span that
     * starts at or past the window's end used to be measured anyway, and {@link
     * StemBridge#levelDb} answers −240 dBFS for an empty slice, which reads as "digital silence" in
     * every clause that consumes it. "There is nothing to measure here" and "there is silence here"
     * are different claims, and only the second one may refuse a fusion.
     */
    private static double levelOfSpan(float[][] pcm, int rate, long fromMs, long toMs, int limit) {
        if (pcm == null || pcm.length == 0) return Double.NaN;
        int from = (int) Math.round(fromMs * rate / 1000d);
        int to = Math.min(limit, (int) Math.round(toMs * rate / 1000d));
        int frames = to - from;
        if (frames <= 0 || from >= limit) return Double.NaN;
        float[][] span = slice(pcm, from, frames);
        if (span == null || span.length == 0 || span[0] == null || span[0].length == 0) {
            return Double.NaN;
        }
        return StemBridge.levelDb(span, rate, span[0].length / (double) rate);
    }

    /** One row's level clause: not more than {@link #CARRY_TOLERANCE_DB} below where the table
     *  (and the make-up gain, if one was applied) puts it, and never above the material it was
     *  taken from by more than the make-up plus that tolerance. */
    private static void rowClause(StringBuilder bad, String what, double carriedDb, double sourceDb,
                                  double designedDb, double makeupDb) {
        double placed = sourceDb + designedDb + makeupDb;
        if (!(carriedDb > placed - CARRY_TOLERANCE_DB)) {
            bad.append("the ").append(what).append(" is more than ")
                    .append(fmt(CARRY_TOLERANCE_DB)).append(" dB below where the table puts it (")
                    .append(fmt(carriedDb)).append(" vs ").append(fmt(placed))
                    .append(" dBFS); ");
        }
        if (carriedDb > sourceDb + makeupDb + CARRY_TOLERANCE_DB) {
            bad.append("the ").append(what).append(" is more than ")
                    .append(fmt(CARRY_TOLERANCE_DB)).append(" dB above the material it was taken"
                            + " from (").append(fmt(carriedDb)).append(" vs ")
                    .append(fmt(sourceDb + makeupDb)).append(" dBFS); ");
        }
    }

    private static double framewise(long ms, int rate) {
        return ms * rate / 1000d;
    }

    private static float[][] carried(Material m, int row) {
        return m.carried != null && row < m.carried.length && m.carried[row] != null
                ? m.carried[row] : new float[0][];
    }

    private static float[][] source(Material m, int row) {
        return m.carriedSource != null && row < m.carriedSource.length
                && m.carriedSource[row] != null ? m.carriedSource[row] : new float[0][];
    }

    /** Ⓜ Round 37: one of the incoming's rows AS THE FILE WILL CARRY IT — the table applied, the
     *  result window-relative — for the renderer's candidate scoring. The report measures these same
     *  rows for its "the incoming's own drums/low end never arrive" clauses; the scorer needs the
     *  same material before choosing which candidate to spend the full report on. */
    public static float[][] gatedIncomingRow(float[][][] stems, StemGesture.Stem row, Plan plan,
                                             DjEdit.Plan edit, int rate, int frames) {
        return gated(stems, row, plan, edit, rate, frames);
    }

    /** One of the incoming's rows with the fusion's table applied — what the row actually is in
     *  the file. The material is window-relative, and the table is not: the window starts at
     *  {@code entryMs} of the file. */
    private static float[][] gated(float[][][] stems, StemGesture.Stem row, Plan plan,
                                   DjEdit.Plan edit, int rate, int frames) {
        float[][] source = stems != null && row.row() < stems.length ? stems[row.row()] : null;
        float[][] out = new float[source == null ? 2 : source.length][frames];
        if (source == null) return out;
        for (int i = 0; i < frames; i++) {
            double fileMs = plan.entryMs + i * 1000d / rate;
            double g = fileGain(plan, edit, false, row, true, fileMs);
            if (g == 0d) continue;
            for (int ch = 0; ch < source.length; ch++) {
                if (i < source[ch].length) out[ch][i] = (float) (g * source[ch][i]);
            }
        }
        return out;
    }

    /**
     * The carried material with the table's own gains applied — what the file ADDS, row by row.
     *
     * <p>{@link #carry} returns the outgoing's material as it was taken; this is the same material
     * as the table places it (the outgoing's drums silent from the drums' swap on, its melodic row
     * at {@link #A_OTHER_DB} until the low end changes hands, and so on). The render mixes in what
     * this returns, and the measurement measures what this returns, so "the row's level" means the
     * same thing in both places.
     */
    public static float[][][] gate(float[][][] carried, Plan plan, boolean withMelody, int rate) {
        float[][][] out = new float[carried == null ? 0 : carried.length][][];
        if (carried == null) return out;
        for (int row = 0; row < carried.length; row++) {
            if (carried[row] == null || carried[row].length == 0) continue;
            StemGesture.Stem stem = stemOf(row);
            out[row] = stem == null ? null : gated(carried[row], plan, rate, carried[row][0].length,
                    stem, withMelody);
        }
        return out;
    }

    /** The stem of a model row index, or null for a row index the model does not have. */
    private static StemGesture.Stem stemOf(int row) {
        for (StemGesture.Stem stem : StemGesture.Stem.ALL) {
            if (stem.row() == row) return stem;
        }
        return null;
    }

    /** One carried row with its own gain applied, on the file's own timeline (the material is
     *  window-relative and the window starts at {@code entryMs}). */
    private static float[][] gated(float[][] row, Plan plan, int rate, int frames,
                                   StemGesture.Stem stem, boolean withMelody) {
        float[][] out = new float[row.length][frames];
        for (int i = 0; i < frames; i++) {
            double fileMs = plan.entryMs + i * 1000d / rate;
            double g = fileGain(plan, null, true, stem, withMelody, fileMs);
            if (g == 0d) continue;
            for (int ch = 0; ch < row.length; ch++) {
                if (i < row[ch].length) out[ch][i] = (float) (g * row[ch][i]);
            }
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }
}
