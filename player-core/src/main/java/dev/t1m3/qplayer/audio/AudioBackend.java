package dev.t1m3.qplayer.audio;

/**
 * Platform audio playback primitive. Plays one source at a time and signals
 * completion; playlist advancement is the controller's job, not the backend's.
 *
 * <p>Desktop implements this over {@code javax.sound.sampled} + SPI decoders;
 * Android over {@code android.media.MediaPlayer}. Neither type leaks into
 * player-core, which only depends on this interface.
 *
 * <p>All methods are safe to call from the controller / UI thread; the impl
 * serialises onto its own audio thread as needed.
 */
public interface AudioBackend {

    default boolean supportsAutoMix() { return false; }
    default String autoMixSource() { return null; }

    /** On success, retain an independent render handle and release it after playback/cancel.
     *  The caller keeps its own handle so a pause or seek can reuse the completed file. */
    default boolean prepareAutoMix(dev.t1m3.qplayer.audio.automix.AutoMixRender render) {
        return false;
    }

    /**
     * Begin playing {@code source} (a local file path or an {@code http(s)}
     * URL) from {@code startMs}. Replaces any current playback. Idempotent
     * enough to double as "restart": calling with the same source reopens it.
     */
    void play(String source, long startMs);

    /** Pause without releasing the source. */
    void pause();

    /** Resume after {@link #pause()}. No-op if nothing is loaded. */
    void resume();

    boolean isPlaying();

    /** Absolute seek within the current source. */
    void seek(long ms);

    /** Current play head in milliseconds (song time, seek-relative). */
    long position();

    /** Duration of the current source in ms, or 0 if unknown. */
    long duration();

    /** Linear gain 0.0–1.0. */
    void setVolume(float volume);

    /**
     * Callback invoked when the current source plays through to its end
     * (not on pause/seek/manual replace). May fire on the audio thread —
     * the controller marshals to the render thread itself.
     */
    void setOnComplete(Runnable callback);

    /**
     * Callback invoked when playback actually begins (e.g. after an async prepare),
     * so a media session can re-baseline its reported position. May fire on the
     * audio thread. Default no-op for backends that don't need it.
     */
    default void setOnStarted(Runnable callback) { }

    /**
     * Callbacks for playback paused / resumed by the backend itself (not the user) —
     * e.g. losing or regaining audio focus on a phone call. Let the controller keep
     * its intended-play state and the media session in sync. Default no-op.
     */
    default void setOnPaused(Runnable callback) { }

    default void setOnResumed(Runnable callback) { }

    /**
     * True while another app's audio focus has playback paused on purpose — the loss
     * paused us and nothing has started playing since. The controller reads it before
     * a boundary it advances by itself: an automatic switch must not restart playback
     * (and take audio focus back from the app that just asked for it) behind the
     * user's back. Cleared by regaining focus or by the app starting something the
     * user asked for. Platforms without audio focus answer false. Default false.
     */
    default boolean pausedByAudioFocusLoss() { return false; }

    /**
     * Drop any pending "resume when focus comes back" intent: this is a pause the
     * user asked for, so a later {@code AUDIOFOCUS_GAIN} must leave the app paused.
     * Called at the moment the user's pause is decided rather than when the pause
     * reaches the backend, so a fade-out that is still running cannot lose the race
     * and resume into the user's pause. Default no-op.
     */
    default void cancelAutoResume() { }

    /**
     * Callback invoked when the backend encounters a playback error (e.g.
     * MediaPlayer error, invalid source). May fire on the audio thread.
     * Default no-op.
     */
    default void setOnError(Runnable callback) { }

    // --- Transitions between two tracks -------------------------------------
    // Optional: a backend that can run a second player lets the controller end a
    // track by overlapping it with the next one (or by holding it parked until a
    // seam) instead of hard-cutting to it. Every method below defaults to "not
    // supported", which is exactly the signal the controller needs to keep using
    // its ordinary switch — so a backend with no second player (desktop) needs no
    // change and can never end up half-ramped.

    /**
     * Prepare {@code source} on a second player as the next track: seeked to
     * {@code startMs}, prepared and already playing at volume 0, so
     * {@link #beginCrossfade} fades in something that is already rolling. Must not
     * disturb the audible player, its surface, or the audio focus.
     *
     * @return false when the source cannot be opened; the caller then has no
     *         incoming track and must switch tracks the ordinary way.
     */
    default boolean prepareIncoming(String source, long startMs) { return false; }

    /**
     * Same, with a say in whether the prepared player starts rolling immediately.
     *
     * <p>{@code startMuted} true is the overlap case above. False parks it: seeked
     * to {@code startMs} and silent, but not running, so its play head stays
     * exactly where it was put until {@link #beginCrossfade} starts it. A trim
     * needs that — a player that has been rolling for the eight seconds spent
     * waiting is eight seconds into the song by the time the seam arrives, which
     * is the one thing the trim is trying to place exactly.
     *
     * @return false when the source cannot be opened or the backend has no second
     *         player.
     */
    default boolean prepareIncoming(String source, long startMs, boolean startMuted) {
        return prepareIncoming(source, startMs);
    }

    /**
     * Same, with the mix the incoming player should be prepared for: the tempo it is
     * pulled to, the semitones it is transposed by, and when the low end hands over
     * (see {@link IncomingMix}, {@link MixNaturaliser}).
     *
     * <p>It travels with the prepare rather than with the ramp because the player is
     * prepared asynchronously and the instruction is attached to it, not to the gain
     * ramp. A backend applies what it can and reports what it applied through
     * {@link #incomingMix()}, so the caller learns the answer from the backend rather
     * than from an optimistically returned flag.
     *
     * <p>⚠️ A player prepared this way must not be left rolling: the caller parks it at
     * an offset its overlap is supposed to make audible <em>first</em>, and a player
     * that rolls from the moment it prepares silently eats that much of the next track.
     * A backend whose platform starts a player as a side effect of applying the mix
     * (Android's {@code setPlaybackParams} does) is responsible for putting it back
     * before the prepare callback returns. The mix is a set of corrections applied to
     * the incoming track for the length of the overlap — never a condition on the blend
     * happening, and never anything the audible track hears.
     *
     * @return false when the source cannot be opened or the backend has no second
     *         player — never because of the mix, which is best-effort by definition.
     */
    default boolean prepareIncoming(String source, long startMs, boolean startMuted,
                                    IncomingMix mix) {
        return prepareIncoming(source, startMs, startMuted);
    }

    /**
     * The mix the incoming player is ACTUALLY running with, or {@link IncomingMix#IDENTITY}
     * when it is prepared and nothing (or nothing of what was requested) could be
     * applied — or <b>null</b> while that is not yet known, i.e. no incoming player or
     * one that has not finished preparing.
     *
     * <p>Null is a third answer on purpose. Preparing is asynchronous, so a caller that
     * asks at the moment it armed would otherwise read "nothing was applied" from a
     * player that was simply not open yet and plan a boundary around a mix that is
     * about to arrive. A backend with no second player (the desktop) answers null for
     * ever, which the caller reads as "keep waiting" and then reaches through its own
     * guards — never as a refusal it has to act on.
     */
    default IncomingMix incomingMix() {
        return null;
    }

    /**
     * Ramp the audible player down and the incoming one up over {@code ms}, then
     * promote the incoming player to be the audible one and release the outgoing
     * one. The crossfade-complete callback fires once that swap has happened.
     *
     * @return false when there is nothing prepared to promote, or the audible
     *         player cannot be swapped out. Playback is then left untouched: the
     *         caller must fall back to its ordinary switch rather than advance.
     */
    default boolean beginCrossfade(long ms) { return false; }

    /**
     * Same, along a chosen gain curve. {@code LINEAR} is what P1 shipped;
     * {@code EQUAL_POWER} holds the summed power flat, which is the fix for a mid
     * overlap dip. A backend that only knows the plain ramp may implement
     * {@link #beginCrossfade(long)} instead — this default then defers to it and
     * the curve is simply ignored.
     */
    default boolean beginCrossfade(long ms, FadeCurve curve) { return beginCrossfade(ms); }

    /** Start the prepared render's dry prefix muted, then hand over at its audio-clock cue. */
    default boolean beginAutoMix() { return beginCrossfade(96, FadeCurve.LINEAR); }

    /** True once the prepared AutoMix deck has begun warming or handing over. */
    default boolean autoMixInProgress() { return false; }

    /** The current source still reports its old position during an asynchronous seek. */
    default boolean isSeeking() { return false; }

    /**
     * Drop a prepared incoming player and restore the audible player's normal
     * volume. Called when a transition is abandoned, and safe when none exists.
     */
    default void cancelIncoming() { }

    /**
     * How far the prepared incoming track has already played, or -1 when there is
     * no incoming player or it never started rolling.
     *
     * <p>This is the amount of the NEXT track a listener has already heard: the
     * incoming player is started (silently, then up the ramp) for the whole
     * overlap, so this is the position that track has to be resumed from whenever
     * the transition is given up and the ordinary switch takes the boundary
     * instead. Resuming from 0 there replays the part that was already audible.
     */
    default long incomingPosition() { return -1L; }

    /**
     * The length of the source the prepared incoming player has open, ms, or -1 when there is no
     * incoming player, it has not finished preparing, or this platform cannot say.
     *
     * <p>⚠️ <b>This exists to prove the incoming deck was handed the RIGHT file.</b> A rendered DJ
     * edit is the incoming track's <em>whole</em> audio, and the boundary finds it by name (an
     * {@code abs(hash(key))} string in a directory listing), so a file belonging to another track
     * would be played as this one: the listener hears a different song emerge from a transition
     * that otherwise sounds normal, and nothing in the controller can tell the difference — only
     * the platform can read a file's length. The renderer writes the track it was asked for, so the
     * two lengths have to agree within a second or so, and a caller that compares them can refuse
     * the file before a single gain is written.
     *
     * <p>-1 is "no answer", deliberately not 0: a caller has to be able to tell a platform that
     * cannot measure (the desktop host, a test's fake) from a file that measures as empty, because
     * only the second one is evidence about the file.
     */
    default long incomingDuration() { return -1L; }

    /**
     * The {@link #incomingPosition()} of the incoming player the backend last
     * DROPPED while it was rolling, or -1 if that never happened.
     *
     * <p>Needed because a backend-driven abort (an incoming error, a focus loss, a
     * pause, a seek) releases its player before the controller is told, so by then
     * {@link #incomingPosition()} has nothing left to read.
     */
    default long droppedIncomingPosition() { return -1L; }

    /**
     * Called (possibly on the audio thread) once the promoted track is the audible
     * one, so the controller can republish title/artist/cover/queue slot as if it
     * had started that track. Default no-op.
     */
    default void setOnCrossfadeComplete(Runnable callback) { }

    /**
     * Called when a transition the controller armed could not continue — the
     * incoming player errored, or playback was paused, seeked or focus-lost
     * mid-overlap. The audible player is back to normal and the caller must take
     * its ordinary path. Default no-op.
     */
    default void setOnCrossfadeAbandoned(Runnable callback) { }

    /** Stop playback and free native resources. */
    void release();
}
