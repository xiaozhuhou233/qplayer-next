package dev.t1m3.qplayer.audio;

/**
 * Picks the transition for one track boundary. The single seam the feature is
 * built around: everything else knows <em>how</em> to perform a
 * {@link TransitionKind}, this decides <em>which</em>.
 *
 * <p>Two implementations are expected. {@link HeuristicTransitionChooser} is the
 * default and runs entirely on the device with no network. An AI implementation
 * can be plugged in later through
 * {@code PlayerController.setTransitionChooser(...)} with no other change: it
 * gets a {@link TransitionContext} and returns a kind. It is never asked to do
 * audio analysis (it cannot — see the context's doc), only to choose from what
 * the queue already says.
 *
 * <p>Implementations may be called on the render thread, so they must not block:
 * an answer is needed within a frame or two before the boundary arrives. Returning
 * {@link TransitionKind#CUT} is always a valid answer and always works. That is a
 * hard rule for every implementation, including one backed by a network service
 * ({@link AiTransitionChooser}): such an implementation answers from something it
 * already holds, and does its asking somewhere else — a request on this path would
 * be a stalled track boundary, not a slow decision.
 *
 * <p>The controller does not trust the answer blindly: whatever comes back is
 * checked against what this boundary can actually perform (a second player, a
 * resolvable url, enough time left) and the boundary falls back to
 * {@link TransitionKind#CUT} when it cannot.
 */
public interface TransitionChooser {

    /**
     * The full answer for one boundary: the kind, plus the overlap length (and,
     * optionally, the gain curve) it should be performed at. Implement this when
     * the length is part of the decision — a {@link TransitionKind} says "overlap
     * these two", not how long for.
     *
     * <p>The default widens a bare {@link #choose} answer into the kind's own
     * default overlap, so a chooser with an opinion about the kind only keeps
     * working unchanged.
     */
    default TransitionPlan plan(TransitionContext ctx) {
        return TransitionPlan.of(choose(ctx));
    }

    /**
     * @param ctx the two tracks and everything else known about this boundary
     * @return the transition to perform; {@link TransitionKind#CUT} to leave the
     *         boundary to the ordinary hard cut.
     */
    TransitionKind choose(TransitionContext ctx);
}
