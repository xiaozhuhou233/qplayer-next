package dev.t1m3.qplayer.android.playback;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.AudioFocusResumeWindow;
import dev.t1m3.qplayer.audio.FadeCurve;
import dev.t1m3.qplayer.audio.IncomingMix;
import dev.t1m3.qplayer.audio.KeyGlide;
import dev.t1m3.qplayer.audio.MixNaturaliser;
import dev.t1m3.qplayer.audio.automix.AutoMixHandoff;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;

/**
 * {@link AudioBackend} over {@code android.media.MediaPlayer}. MediaPlayer
 * decodes local files and {@code http(s)} streams natively (mp3/flac/m4a/ogg),
 * so netease CDN urls and local tracks share one path — no SPI decoders, no
 * {@code javax.sound} (which Android lacks).
 *
 * <p>Prepared asynchronously: {@link #play} kicks off {@code prepareAsync} and
 * starts on the prepared callback, honoring the requested start offset.
 */
public final class AndroidAudioBackend implements AudioBackend {
    private dev.t1m3.qplayer.audio.automix.AutoMixRender activeAutoMix, incomingAutoMix;
    private AutoMixHandoff autoMixHandoff;
    private boolean autoMixAudible;
    private long autoMixArmGeneration;

    @Override public synchronized boolean autoMixInProgress() {
        return incomingAutoMix != null && crossfading;
    }

    @Override public synchronized boolean beginAutoMix() {
        if (autoMixInProgress()) return true;
        if (incomingAutoMix == null || incomingAutoMix.leadInMs == 0)
            return beginCrossfade(AutoMixHandoff.FADE_MS, FadeCurve.LINEAR);
        if (crossfading || incomingPlayer == null || !incomingPrepared
                || player == null || !prepared || !wantPlay) return false;
        final AutoMixHandoff gate = new AutoMixHandoff(incomingAutoMix.exitMs,
                incomingAutoMix.leadInMs, android.os.SystemClock.elapsedRealtime());
        autoMixHandoff = gate;
        autoMixAudible = false;
        crossfading = true;
        long generation = ++rampGeneration;
        try {
            incomingPlayer.setVolume(0f, 0f);
            player.setVolume(baseGain(), baseGain());
            incomingPlayer.setOnSeekCompleteListener(p -> {
                synchronized (AndroidAudioBackend.this) {
                    if (p == incomingPlayer && autoMixHandoff == gate) {
                        gate.seekComplete(android.os.SystemClock.elapsedRealtime(), p.getCurrentPosition());
                        Logger.info("AutoMix: muted prefix seek complete at {}ms", p.getCurrentPosition());
                    }
                }
            });
            // After a scrub into the warmup window, starting the WAV at zero
            // forces a cold output start and another seek before it can align.
            // Seek the muted dry prefix first, using the outgoing audio clock.
            long initialSeek = gate.initialSeek(position());
            if (initialSeek >= 0) incomingPlayer.seekTo(initialSeek, MediaPlayer.SEEK_CLOSEST);
            incomingPlayer.start();
            incomingRolling = true;
            Logger.info("AutoMix: warming muted dry prefix; cue={}ms, lead={}ms",
                    incomingAutoMix.exitMs, incomingAutoMix.leadInMs);
        } catch (Throwable e) {
            Logger.warn("AutoMix: warm start failed ({})", e.toString());
            cancelIncoming();
            return false;
        }
        rampHandler.post(() -> warmAutoMix(generation));
        return true;
    }

    private void warmAutoMix(long generation) {
        boolean abandoned = false;
        synchronized (this) {
            if (!crossfading || generation != rampGeneration || autoMixHandoff == null) return;
            try {
                long fileMs = incomingPlayer.getCurrentPosition();
                long outgoingMs = position();
                long command = autoMixHandoff.sample(android.os.SystemClock.elapsedRealtime(),
                        outgoingMs, fileMs);
                if (command == AutoMixHandoff.ABORT) {
                    Logger.warn("AutoMix: warm clock missed cue; keeping outgoing audio");
                    cancelIncoming();
                    abandoned = true;
                } else if (command == AutoMixHandoff.FADE) {
                    Logger.info("AutoMix: warmed handoff; incoming={}ms, expected={}ms, drift={}ms",
                            fileMs, incomingAutoMix.leadInMs + outgoingMs - incomingAutoMix.exitMs,
                            fileMs - (incomingAutoMix.leadInMs + outgoingMs - incomingAutoMix.exitMs));
                    autoMixHandoff = null;
                    crossfading = false;
                    autoMixAudible = true;
                    if (!beginCrossfade(AutoMixHandoff.FADE_MS, FadeCurve.LINEAR)) {
                        cancelIncoming(); abandoned = true;
                    }
                    if (!abandoned) return;
                } else {
                    player.setVolume(baseGain(), baseGain());
                    if (command >= 0) {
                        incomingPlayer.seekTo(command, MediaPlayer.SEEK_CLOSEST);
                        Logger.info("AutoMix: align muted prefix to {}ms", command);
                    }
                    rampHandler.postDelayed(() -> warmAutoMix(generation), 16);
                }
            } catch (Throwable e) {
                Logger.warn("AutoMix: warm handoff failed ({})", e.toString());
                cancelIncoming(); abandoned = true;
            }
        }
        if (abandoned) fire(onCrossfadeAbandoned);
    }

    @Override public boolean supportsAutoMix() { return true; }
    @Override public synchronized String autoMixSource() {
        return activeAutoMix == null ? source : activeAutoMix.original;
    }

    @Override public synchronized boolean prepareAutoMix(
            dev.t1m3.qplayer.audio.automix.AutoMixRender render) {
        dev.t1m3.qplayer.audio.automix.AutoMixRender retained = render.retain();
        if (!prepareIncoming(render.file.toString(), 0L, false, IncomingMix.IDENTITY)) {
            retained.delete();
            return false;
        }
        incomingAutoMix = retained;
        autoMixAudible = false;
        final long generation = ++autoMixArmGeneration;
        rampHandler.post(() -> armAutoMix(generation));
        return true;
    }

    /** The parked deck follows the outgoing audio clock, even without UI polling. */
    private synchronized void armAutoMix(long generation) {
        if (generation != autoMixArmGeneration || incomingAutoMix == null || crossfading
                || !wantPlay || player == null || !prepared) return;
        if (isSeeking()) {
            rampHandler.postDelayed(() -> armAutoMix(generation), 16);
            return;
        }
        long position = position();
        if (position > incomingAutoMix.exitMs + AutoMixHandoff.LATE_TOLERANCE_MS) return;
        long remaining = incomingAutoMix.exitMs - incomingAutoMix.leadInMs - position;
        if (remaining <= 0 && incomingPrepared && beginAutoMix()) return;
        rampHandler.postDelayed(() -> armAutoMix(generation), Math.max(16, Math.min(100, remaining)));
    }

    private MediaPlayer player;
    private String source;
    private long pendingSeekMs;
    private boolean seeking;

    @Override public synchronized boolean isSeeking() { return seeking; }
    private boolean wantPlay;
    private float volume = 0.8f;
    private boolean prepared;
    private Runnable onComplete;
    private Runnable onStarted;
    private Runnable onPaused;
    private Runnable onResumed;
    private Runnable onError;

    // --- Overlapping crossfade (two players) -------------------------------
    // A second MediaPlayer, prepared and already rolling at volume 0, lets the
    // outgoing track be ramped down while the incoming one ramps up instead of the
    // hard cut between them. It is deliberately NOT "the player": every other
    // method here keeps meaning the audible track, and the video surface never
    // reaches the incoming player — a picture belongs to whatever is on screen,
    // which stays the outgoing track until the ramp has actually finished.

    /** The prepared second player; null unless a transition is in flight. */
    private MediaPlayer incomingPlayer;
    private String incomingSource;
    private boolean incomingPrepared;
    private long incomingSeekMs;
    /** Whether the incoming player was prepared rolling (the overlap case) or
     *  parked at its seek offset until the ramp starts it (SILENCE_TRIM, where the
     *  play head has to stay exactly where it was put while the measurements are
     *  still arriving). */
    private boolean incomingStartMuted = true;
    /** True once the incoming player is actually rolling — for a parked one, only
     *  from the moment {@link #beginCrossfade} starts it. */
    private boolean incomingRolling;
    /** How far the incoming player had played when it was last dropped while it was
     *  rolling. That is the part of the next track a listener has already heard, and
     *  the controller resumes the track from it when the transition is given up
     *  instead of completing (see {@link AudioBackend#droppedIncomingPosition}).
     *  Cleared when a new incoming is prepared: it belongs to the boundary that was
     *  in flight, not to the next one. */
    private long droppedIncomingMs = -1L;
    /** True while the two players are ramped against each other. Blocks
     *  {@link #applyVolume()}: a controller fade tick pushes the user volume
     *  through it, and the last thing such a fade writes is a target of 0 — which
     *  would then be the gain the promoted (audible) player inherits. */
    private boolean crossfading;
    /** The gain shape the running ramp follows (see FadeCurve). */
    private FadeCurve rampCurve = FadeCurve.LINEAR;
    /** Invalidates queued ramp samples: every ramp start/cancel bumps it. */
    private long rampGeneration;
    /** The audio-clock monitor's own generation: bumped whenever the players it watches
     *  stop being the ones it is meant to be watching. */
    private long clockGeneration;
    private long rampStartNs;
    private long rampDurationNs;
    private Runnable onCrossfadeComplete;
    private Runnable onCrossfadeAbandoned;
    /** The ramp runs on the main looper's clock rather than on a frame callback:
     *  a Handler tick still fires with the screen off (the outgoing player holds a
     *  partial wake lock), while a frame-driven ramp would stall exactly when the
     *  overlap matters — right at the end of a track that is finishing in the dark. */
    private final Handler rampHandler = new Handler(Looper.getMainLooper());
    /** ~30 Hz. A gain ramp has nothing to gain from the display's frame rate. */
    private static final long RAMP_TICK_MS = 32L;

    /**
     * How long the promoted track takes to go from the mix's tempo and pitch back to
     * its own.
     *
     * <p>⚠️ <b>Round 13: it is a single call, not a ramp, and this number is no longer how
     * long it takes.</b> The stretch and the transpose were bought for the overlap — they
     * are what made the incoming track's beats land on the outgoing track's and its key sit
     * in the same harmony — and once only one track is playing there is no beat to align
     * and no harmony to clash, so both are undone. That used to be a six-second 10 Hz
     * glide: <em>sixty</em> {@code setPlaybackParams} calls on the player the listener is
     * hearing, each one a chance for the platform to rebuild its audio pipeline. Those
     * calls are where the audible hiccup lives (measured — see AI_HANDOFF round 13), and
     * what replaces them is <b>one</b> write of {@code speed x1 pitch x1} at the promotion:
     * the moment this app is already tearing a player down, and the last moment the
     * correction can be made, because any earlier and the incoming track would come off the
     * outgoing track's grid while both are still sounding, which is the one thing the lock
     * exists for.
     *
     * <p>The number is kept (and still exported as {@link IncomingMix#RESTORE_MS}) because
     * the <em>caller</em> still needs it: a transposition is only applied at all when the
     * blend can afford to have the track back in its own key before that track's vocals
     * arrive, and the controller sizes that with this constant. What it measures now is the
     * room the rule insists on, not the duration of a glide.
     *
     * <p>A pause, a seek, a new track or a release still settles the tempo immediately
     * rather than leaving it pending (see {@link #finishRestoreNow()}).
     */
    private static final long PROMOTION_RESTORE_MS = IncomingMix.RESTORE_MS;

    /** 10 Hz: the pitch's return is checked against the incoming track's own clock, which
     *  only has to be sampled often enough that the step lands inside the rule's own
     *  two-second margin (measured on the device: it lands 13-72 ms late). */
    private static final long RESTORE_TICK_MS = 100L;

    /**
     * Which ease-back this process performs: the single write (false, the shipped answer) or
     * round 12's per-tick glide (true).
     *
     * <p>⚠️ <b>A measurement instrument, not a feature.</b> The claim this round is built on
     * is "a live {@code setPlaybackParams} train is what the listener hears as a hiccup", and
     * the only honest way to test it is to run both shapes on the same device with the same
     * instrumentation — which is what this flag is for. It is read ONCE, from
     * {@code files/legacy-ease-backs} in the app's private storage: the file exists only when
     * a harness pushed it, so an ordinary install never takes this branch, and every process
     * that does says so in the log. Round 13's device runs are the A/B (see AI_HANDOFF).
     */
    private volatile boolean legacyEaseBacks;

    /** Whether the A/B arm was already read (and logged) for this process. */
    private boolean legacyArmChecked;

    // --- The mix the incoming player is prepared with --------------------------
    // What a mix can ask for: the tempo the incoming track is pulled to and the
    // semitones it is transposed by (both naturalisers — the controller computed them
    // from the two tracks' measured grids and keys, and neither of them can refuse the
    // blend), plus the low end changing hands part way through the overlap. The tempo
    // and the pitch go out as one `setPlaybackParams` on the INCOMING player — the
    // platform's own pitch-preserving time stretch (Sonic) with the pitch factor on
    // top — and the audible track is never touched by any of it. They are asked for
    // with the prepare and eased back to identity after the promotion.
    //
    // ⚠️ One platform side effect is load bearing here: `setPlaybackParams` STARTS a
    // player that is merely prepared. The incoming player is parked at the offset the
    // overlap is supposed to make audible FIRST, so it must not be rolling before the
    // ramp starts it — parkIncoming() below asserts that after the mix is applied, and
    // is the reason the listener hears the next track from its very start rather than
    // from wherever a silently-rolling player had got to (that defect was measured:
    // promoted at 19769ms while the overlap had only played 10385ms).

    /** What the incoming player was prepared FOR, and what it actually got. The
     *  request is applied once, when the player has prepared (see
     *  prepareIncoming/onIncomingPrepared) and reported through {@link #incomingMix()}
     *  so the caller never has to assume anything took. */
    private IncomingMix requestedMix = IncomingMix.IDENTITY;
    private IncomingMix appliedMix;
    /** The tempo the promoted track is owed its own back from, and whether it is still
     *  owed: one call at the promotion writes the track's own tempo, and a generation so a
     *  write queued on the main looper can never touch the wrong player. See
     *  {@link #PROMOTION_RESTORE_MS}. */
    private double restoreFromSpeed = 1d;
    private long restoreStartNs;
    private long restoreGeneration;
    /** True while the promoted track is still owed its own tempo back. */
    private boolean restoring;
    /**
     * The user's rule, in the only form the platform can execute it: the pitch goes back
     * to the incoming track's own by the position in ITS OWN FILE that
     * {@link IncomingMix#pitchIdentityAtFileMs()} names.
     *
     * <p><b>One step, not a glide.</b> The return is checked at 10 Hz against the player's
     * own clock and performed in a single {@code setPlaybackParams}; it used to be sixty of
     * them over six seconds, and sixty chances for the platform to rebuild the audio
     * pipeline of the deck the listener is hearing is how this feature produced a hiccup
     * (see AI_HANDOFF round 13). A step is affordable here for a reason that is structural
     * rather than lucky: a transposition is only ever applied to a pair whose incoming deck
     * plays a DJ edit ({@code DjEdit}), and the edit holds that track's vocals out for the
     * whole blend — so at the instant of the step the material under it is the backing, and
     * the controller places the instant two seconds before the voice comes back anyway.
     *
     * <p>Driven by {@code getCurrentPosition()} rather than by the wall clock, and that is
     * the whole point: the vocal entry the controller measured is a position in the same
     * file, so this clock is the one the margin is defined in. A wall-clock schedule would
     * quietly spend the device's start latency (123–822 ms, measured) out of the two
     * seconds of margin — and the one thing the rule says is that the margin is not to be
     * squeezed.
     *
     * <p>{@link #pitchBackGeneration} guards a check already queued on the main looper,
     * like {@link #restoreGeneration} does for the promotion's tempo write.
     */
    private long pitchIdentityAtFileMs = -1L;
    private MediaPlayer pitchBackPlayer;
    private double pitchBackFrom = 1d;
    private double pitchBackSpeed = 1d;
    private long pitchBackGeneration;
    private boolean pitchBackRunning;
    /** Where the player's own clock stood when the pitch reached identity: the number the
     *  log line reports as the proof, and a warning at the promotion if the schedule had
     *  not finished. */
    private long pitchBackDoneAtMs = -1L;

    /**
     * The key blend in flight, when the mix carried one: {@link KeyGlide}'s ladder of a few
     * writes over the blend's modulation section, stepping the INCOMING deck back to its own
     * key and the AUDIBLE one into it, both at the same instants so the two never leave the
     * same key (see that class for why both decks have to move — it is not a preference, it is
     * the only shape that keeps the pair in unison while the tonality travels).
     *
     * <p>It replaces {@link #pitchIdentityAtFileMs}'s single step when it is armed, and the two
     * are mutually exclusive by construction: {@code startPitchBack} picks one. Everything it
     * writes goes through {@link #writeParams}, so the shape of the whole modulation is in the
     * log as a sequence of platform read-backs rather than as a claim.
     *
     * <p>⚠️ <b>The audible deck is touched here, and that has one hard consequence: every path
     * that drops the overlap without promoting it must put that deck's pitch back.</b> A deck
     * left a semitone off its own key keeps playing the rest of the song out of tune, which is
     * worse than anything the transition was for — see {@link #restoreOutgoingPitch}, called
     * from {@link #releaseIncoming} (every abandon) and from the cancel paths.
     */
    private KeyGlide activeGlide;
    /** The deck the ladder is stepping back to its own key (the incoming player), and the deck
     *  it is bending into that key (the audible one), captured when the ladder was armed so a
     *  promotion cannot make either name mean a different player. */
    private MediaPlayer glideInPlayer;
    private MediaPlayer glideOutPlayer;
    private int glideIndex;
    private boolean glideRunning;
    private long glideGeneration;
    /** Whether any step has written to the audible deck: false means there is nothing to put
     *  back, and that is the ordinary case for a mix with no ladder. */
    private boolean glideTouchedOut;
    /** What the platform read back for each step, semitone ratios, so the run's own log can
     *  print the curve that was really applied rather than the one that was asked for. */
    private double[] glideReadBackIn;
    private double[] glideReadBackOut;
    private long glideFirstStamp;
    /** The audible deck's own gain as the ramp last set it (0-1 of its own level). Read by the
     *  glide's per-step log line: the ladder is monotone, so the largest shift on that deck
     *  lands latest, and "how loud is it then" is the number that says how much of the effect
     *  the listener can hear it on. */
    private double lastOutGain = 1d;
    /** The last gain the ramp asked the OUTGOING player for, and when — read by the release
     *  assertion in {@link #promoteIncoming}, which is the one place a player is torn down
     *  while the two decks are still being ramped against each other. Releasing a player
     *  discards whatever it still has buffered, so its gain at that instant IS the sound of
     *  the release: the outgoing track must be at or below {@link FadeCurve#INAUDIBLE_DB}
     *  or the listener hears the previous song stop. Kept as the asked-for value, because
     *  that is all there is — {@code MediaPlayer} has no volume getter. */
    private double rampOutGain = 1d;
    /** {@code System.nanoTime()} of that write, for the "how long before the release was it"
     *  half of the same line. */
    private long rampOutGainNs;
    /** The last tick whose outgoing gain was above zero. The head of the silent stretch the
     *  user's ear is owed before the release is measured from here. A tick that lands on a
     *  refused write does not move it, so the number is a lower bound on the silence. */
    private long rampOutLastAudibleNs;
    /** The last above-zero gain and its dB, printed at the release: "the ramp asked it for X
     *  and it has been at zero since" says more than the zero alone does. */
    private double rampOutLastAudibleGain = 1d;
    /** How many 6 dB steps below unity the tail trace has already reported (see
     *  {@link #traceOutgoingGain}), so one blend's exit costs about ten lines rather than
     *  one per 32 ms tick — and when the previous step was reported, so the line can carry
     *  the gap between steps. */
    private int rampOutTraceStep;
    private long rampOutTraceNs;
    /** When the low end hands over, ms into the ramp (from the applied mix), and
     *  whether it has. A one-shot: the swap happens once, on a beat, and never
     *  again for this overlap. */
    private long bassSwapAtMs = -1L;
    private boolean bassSwapped;
    /** The two players' low-end equalizers, when a mix asked for the hand-over and
     *  the device let us have them. Attached before the ramp (nothing is allocated
     *  while two tracks are being mixed) and released at the promotion, on an abort,
     *  and on release. */
    private Equalizer equalizerOut;
    private Equalizer equalizerIn;
    /** Everything below this counts as "the bass" for the hand-over, Hz. The lowest
     *  band of a device's equalizer is usually 60-120 Hz wide, and the kick's body
     *  and a bassline's fundamental are what has to move. */
    private static final double BASS_SWAP_HZ = 200d;
    /** How far a parked incoming player's play head may sit from the offset it was
     *  put at before that counts as "it moved". Only the cheap check in
     *  parkIncoming; a platform that started the player is caught by isPlaying()
     *  whatever its clock says. */
    private static final long PARK_TOLERANCE_MS = 100L;

    // Audio focus: ONE request for the whole app (the second player of an overlap is
    // an incoming track, not a second listener — it never asks for a focus of its
    // own, see prepareIncoming, so a loss can only ever arrive once).
    //
    // Policy, all four hints, in one place:
    //   LOSS (permanent)          -> pause; the request is forgotten (Android sends
    //                                nothing more to an app that lost focus for good,
    //                                so the next play()/resume() asks again).
    //   LOSS_TRANSIENT            -> pause and come back on AUDIOFOCUS_GAIN.
    //   LOSS_TRANSIENT_CAN_DUCK   -> PAUSE, never duck: mixing two players is exactly
    //                                what the user is asking us not to do, and a
    //                                ducked track is one they then cannot find again.
    //                                Same come-back rule as a transient loss.
    //   GAIN                      -> resume, but only a pause WE made for a loss; a
    //                                pause the user asked for stays a pause. A loss
    //                                lasting over five minutes stays paused too.
    // resumeWindow is that intent, and it is cleared by anything the app or the user
    // decides by hand (pause(), play(), cancelAutoResume()).
    private final AudioManager audioManager;
    private final Context appContext;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus;
    private final AudioFocusResumeWindow resumeWindow = new AudioFocusResumeWindow();
    /** True from the loss that paused us until focus is ours again or the user starts
     *  something by hand: nothing may start playing on its own while it is set
     *  (see pausedByAudioFocusLoss). */
    private boolean pausedByFocus;
    private boolean ducked;
    /** Whether a permanent loss is allowed to come back on AUDIOFOCUS_GAIN.
     *
     *  <p>False, and on this platform it is also moot: a permanent loss takes the
     *  request out of the focus stack (measured on the device — after the other app
     *  released focus the stack was empty and no AUDIOFOCUS_GAIN was ever delivered),
     *  so there is nothing for the app to come back from. Coming back from a permanent
     *  loss is what the user's play is for: the next play()/resume() re-requests focus
     *  (see forgetFocusAfterPermanentLoss). A transient or duck loss is different —
     *  there the app keeps its place in the stack and IS told when focus returns, so
     *  that pause comes back on its own. */
    private static final boolean RESUME_AFTER_PERMANENT_LOSS = false;

    public AndroidAudioBackend(Context ctx) {
        appContext = ctx.getApplicationContext();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Ⓜ Fired on every new MediaPlayer with its audio session id (the MD3E
     *  equalizer attaches its audiofx chain to this). */
    public volatile java.util.function.IntConsumer audioSessionListener;

    // Ⓜ The listener's EQ（「重新写均衡器」）: the backend owns it, because the
    // Equalizer must live with the MediaPlayer it attaches to. One instance per
    // session, created when the player is created and re-applied on prepare;
    // slider movement only pushes band values, never recreates the effect.
    private android.media.audiofx.Equalizer listenerEq;
    private android.media.audiofx.BassBoost listenerBass;
    private int listenerEqSession = 0;
    private volatile boolean eqEnabled = false;
    private volatile int[] eqLevels = new int[0];
    private volatile int eqBassStrength = 0;
    private volatile String eqStatus = "均衡器：未启用";

    /** Apply one complete editor snapshot; never briefly replay the old bands/bass. */
    public synchronized void setEqualizer(boolean enabled, int[] levels, int bassStrength) {
        eqEnabled = enabled;
        eqLevels = levels != null ? levels.clone() : new int[0];
        eqBassStrength = Math.max(0, Math.min(1000, bassStrength));
        applyListenerEq();
    }

    public synchronized void setEqEnabled(boolean on) {
        eqEnabled = on;
        applyListenerEq();
    }

    /** Millibel levels per band; index = band. */
    public synchronized void setEqLevels(int[] levels) {
        eqLevels = levels != null ? levels.clone() : new int[0];
        applyListenerEq();
    }

    public synchronized void setEqBassStrength(int strength) {
        eqBassStrength = Math.max(0, Math.min(1000, strength));
        applyListenerEq();
    }

    /** Human-readable attachment state for the editor dialog. */
    public String eqState() {
        return eqStatus;
    }

    /** Band geometry for the editor; defaults when nothing is attached yet. */
    public int eqBandCount() {
        synchronized (this) {
            try { if (listenerEq != null) return listenerEq.getNumberOfBands(); } catch (Throwable ignored) { }
        }
        return 5;
    }

    public int[] eqBandRange() {
        synchronized (this) {
            try {
                if (listenerEq != null) {
                    short[] range = listenerEq.getBandLevelRange();
                    return new int[] { range[0], range[1] };
                }
            } catch (Throwable ignored) { }
        }
        return new int[] { -1500, 1500 };
    }

    public int[] eqCenterFreqs() {
        synchronized (this) {
            try {
                if (listenerEq != null) {
                    int bands = listenerEq.getNumberOfBands();
                    int[] out = new int[bands];
                    for (int i = 0; i < bands; i++) out[i] = listenerEq.getCenterFreq((short) i);
                    return out;
                }
            } catch (Throwable ignored) { }
        }
        return new int[0];
    }

    private synchronized void applyListenerEq() {
        if (!eqEnabled || player == null || !prepared) {
            releaseListenerEq();
            eqStatus = eqEnabled ? "均衡器：已启用，等待播放" : "均衡器：未启用";
            return;
        }
        final int session;
        try {
            session = player.getAudioSessionId();
        } catch (Throwable failure) {
            eqStatus = "均衡器：会话不可用（" + failure + "）";
            return;
        }
        if (session <= 0) {
            eqStatus = "均衡器：等待有效音频会话";
            return;
        }
        boolean attached = false;
        if (listenerEq == null || listenerEqSession != session) {
            releaseListenerEq();
            try {
                listenerEq = new android.media.audiofx.Equalizer(0, session);
                listenerEqSession = session;
                attached = true;
                final Equalizer effect = listenerEq;
                effect.setControlStatusListener((audioEffect, hasControl) -> {
                    synchronized (AndroidAudioBackend.this) {
                        if (listenerEq != effect) return;
                        if (hasControl) applyListenerEq();
                        else eqStatus = "均衡器：音效控制权暂不可用";
                    }
                });
            } catch (Throwable failure) {
                eqStatus = "均衡器：挂载失败（" + failure + "）";
                Logger.warn("eq attach failed on session {}: {}", session, failure.toString());
                return;
            }
        }
        try {
            if (!listenerEq.hasControl()) {
                eqStatus = "均衡器：音效控制权暂不可用";
                return;
            }
            final short bands = listenerEq.getNumberOfBands();
            final short lower = listenerEq.getBandLevelRange()[0];
            final short upper = listenerEq.getBandLevelRange()[1];
            final int[] levels = eqLevels;
            // Ⓜ 低音 must never be a no-op: BassBoost is unimplemented on plenty of
            // ROMs (getStrengthSupported()==false and setStrength silently does
            // nothing — exactly the listener's 「拉高拉低低音都没感觉」). The slider
            // therefore rides the Equalizer's OWN lowest bands, and BassBoost is only
            // layered on top where the device really implements it.
            final int bassMillibels = (int) Math.round(eqBassStrength / 1000.0 * 1200); // 0..+12 dB
            final StringBuilder boostBands = new StringBuilder();
            int verified = 0;
            for (short b = 0; b < bands; b++) {
                int millibels = b < levels.length ? levels[b] : 0;
                final int centerHz = listenerEq.getCenterFreq(b) / 1000;
                if (bassMillibels > 0 && centerHz <= 300) {
                    millibels += bassMillibels;
                    if (boostBands.length() > 0) boostBands.append("/");
                    boostBands.append(centerHz).append("Hz");
                }
                listenerEq.setBandLevel(b, (short) Math.max(lower, Math.min(upper, millibels)));
                if (listenerEq.getBandLevel(b) == Math.max(lower, Math.min(upper, millibels))) verified++;
            }
            int enableResult = listenerEq.setEnabled(true);
            if (enableResult != android.media.audiofx.AudioEffect.SUCCESS || !listenerEq.getEnabled()) {
                eqStatus = "均衡器：设备未能启用音效（" + enableResult + "）";
                return;
            }
            String bassNote = "";
            if (eqBassStrength > 0) {
                bassNote = "，低音 +" + (bassMillibels / 100.0) + "dB → " + boostBands;
                boolean hardware = false;
                if (listenerBass == null) {
                    try {
                        listenerBass = new android.media.audiofx.BassBoost(0, session);
                    } catch (Throwable ignored) { }
                }
                if (listenerBass != null) {
                    try {
                        hardware = listenerBass.getStrengthSupported();
                        if (hardware) {
                            listenerBass.setStrength((short) eqBassStrength);
                            listenerBass.setEnabled(true);
                        }
                    } catch (Throwable ignored) { }
                }
                bassNote += hardware ? "（含 BassBoost）" : "（本机不支持 BassBoost，已由低频段承担）";
            } else if (listenerBass != null) {
                try { listenerBass.setEnabled(false); } catch (Throwable ignored) { }
            }
            eqStatus = "均衡器：已挂载（" + bands + " 段 " + (lower / 100) + "–" + (upper / 100)
                    + "dB，写入校验 " + verified + "/" + bands + bassNote + "）";
            if (attached) Logger.info("eq attached on session {}: bands={} range=[{},{}] bass={} verified={}",
                    session, bands, lower, upper, eqBassStrength, verified);
        } catch (Throwable failure) {
            eqStatus = "均衡器：应用失败（" + failure + "）";
            Logger.warn("eq apply failed: {}", failure.toString());
        }
    }

    private synchronized void releaseListenerEq() {
        if (listenerEq != null) {
            try { listenerEq.setControlStatusListener(null); } catch (Throwable ignored) { }
            try { if (listenerEq.hasControl()) listenerEq.setEnabled(false); } catch (Throwable ignored) { }
            try { listenerEq.release(); } catch (Throwable ignored) { }
        }
        listenerEq = null;
        try { if (listenerBass != null) { listenerBass.setEnabled(false); listenerBass.release(); } } catch (Throwable ignored) { }
        listenerBass = null;
        listenerEqSession = 0;
    }

    /** The live player's audio session id, or 0 when nothing is prepared. */
    public synchronized int currentAudioSessionId() {
        try {
            return player != null ? player.getAudioSessionId() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    public synchronized void play(String src, long startMs) {
        if (src == null || src.isEmpty()) return;
        releasePlayer();
        source = src;
        pendingSeekMs = Math.max(0L, startMs);
        wantPlay = true;
        prepared = false;
        // The app is starting a source of its own: whatever a focus loss paused is
        // past (and there is nothing left for a later AUDIOFOCUS_GAIN to resume), and
        // the request is re-made if the permanent-loss path dropped it — this is how
        // the app gets a focus of its own back after another app took it for good.
        resumeWindow.cancel();
        pausedByFocus = false;
        requestFocus();

        MediaPlayer mp = new MediaPlayer();
        // Network-backed tracks still need the CPU while the screen is off. Without
        // MediaPlayer's partial wake lock some OEMs suspend the streaming/decoder
        // path, report a MEDIA_ERROR_* and make PlayerController skip to the next
        // track even though the current song has not actually reached its end.
        mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setOnPreparedListener(this::onPrepared);
        mp.setOnSeekCompleteListener(this::onMainSeekComplete);
        mp.setOnCompletionListener(p -> onCompleted(p));
        mp.setOnErrorListener(this::onPlayerError);
        mp.setOnVideoSizeChangedListener(this::onVideoSizeChanged);
        player = mp;
        // A fresh player means a fresh session for the EQ too.
        releaseListenerEq();
        // Ⓜ Session observers (the MD3E equalizer re-attaches here): every new
        // MediaPlayer owns a fresh audio session, so the hook fires per track.
        java.util.function.IntConsumer sessionListener = audioSessionListener;
        if (sessionListener != null) {
            try { sessionListener.accept(mp.getAudioSessionId()); } catch (Throwable ignored) { }
        }
        try {
            // The requested offset is part of the line on purpose: "the track started
            // from its beginning again" is a claim about this number, and without it
            // in the log a re-open at 0 looks exactly like a re-open at 40s.
            Logger.info("MediaPlayer: setDataSource + prepareAsync (startMs={})", pendingSeekMs);
            setDataSource(mp, src);
            mp.prepareAsync();
        } catch (IOException | IllegalStateException | IllegalArgumentException
                 | SecurityException e) {
            // IllegalArgumentException/SecurityException come from the (Context, Uri,
            // headers) overload used for the bili CDN, and from a malformed string
            // source; both are unchecked, so without them here they escape play()
            // and — on the resolve path, which calls it from a deferred main-thread
            // runnable — become an uncaught exception instead of a skipped track.
            Logger.error("MediaPlayer setDataSource failed: {}", e.toString());
            releasePlayer();
        }
    }

    /** A {@code content://} URI must be opened via the {@code (Context, Uri)}
     *  overload — the {@code String} overload has no calling context to resolve it
     *  and prepareAsync then fails async with extra=MEDIA_ERROR_SYSTEM (0x80000000).
     *  http(s) urls and plain file paths take the string overload. */
    private android.view.Surface videoSurface;
    // Retain the view's surface while hidden, but never give it to MediaPlayer.
    // NuPlayer shuts down its video decoder on setSurface(null), leaving audio
    // running, and recreates video at the audio clock when the surface returns.
    private boolean videoPlaybackVisible = true;
    private boolean videoForeground = true;
    private MediaPlayer videoSurfacePlayer;
    private android.view.Surface appliedVideoSurface;
    /** Source picture size as the player last reported it; 0 until a stream reports
     *  one. The UI frames the video box from this so a 4:3 source keeps its 4:3 (and
     *  full-screen fits rather than crops it). */
    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile VideoSizeListener videoSizeListener;

    /** Told the source picture size whenever it becomes known. Fires on the media
     *  thread — hop to the main one before touching UI state. */
    public interface VideoSizeListener {
        void onVideoSize(int width, int height);
    }

    public void setVideoSizeListener(VideoSizeListener l) {
        videoSizeListener = l;
    }

    /** Source picture width/height, or 0 until the stream reports them. */
    public int videoWidth() {
        return videoWidth;
    }

    public int videoHeight() {
        return videoHeight;
    }

    /** Bilibili playback is video: the shell hands the SurfaceView's surface to the
     *  very same MediaPlayer that owns the audio, so picture and sound share one
     *  clock instead of being two decoders that have to be kept in sync.
     *
     *  <p>A recomposed or resized SurfaceView reports the SAME surface again, and
     *  re-attaching it makes the platform re-render the picture — which reads as the
     *  video replaying its last moment. So the player is only ever touched on a real
     *  change. The picture is always fitted into the surface (the player's default
     *  scaling mode), never cropped: the source's own aspect ratio is what the UI
     *  frames, so a 4:3 video has to keep its 4:3. */
    public synchronized void attachVideoSurface(android.view.Surface surface) {
        videoSurface = surface;
        applyVideoSurface();
    }

    /** Whether the UI is actually showing the picture, independently of whether
     *  its parked SurfaceView still exists. Does not pause or reopen the source. */
    public synchronized void setVideoPlaybackVisible(boolean visible) {
        videoPlaybackVisible = visible;
        applyVideoSurface();
    }

    /** Lifecycle gate: a late UI/surface callback cannot enable background video. */
    public synchronized void setVideoForeground(boolean foreground) {
        videoForeground = foreground;
        applyVideoSurface();
    }

    private void applyVideoSurface() {
        MediaPlayer mp = player;
        if (mp == null) return;
        android.view.Surface surface = videoPlaybackVisible && videoForeground
                && videoSurface != null && videoSurface.isValid() ? videoSurface : null;
        if (mp == videoSurfacePlayer && surface == appliedVideoSurface) return;
        try {
            // Recreating the video decoder can rewind the shared A/V clock to a
            // sync frame. Restore the exact position, without reopening playback.
            boolean reconnecting = surface != null && mp == videoSurfacePlayer
                    && prepared && videoWidth > 0;
            long resumePosition = reconnecting ? mp.getCurrentPosition() : 0L;
            mp.setSurface(surface);
            videoSurfacePlayer = mp;
            appliedVideoSurface = surface;
            if (reconnecting) mp.seekTo(resumePosition, MediaPlayer.SEEK_CLOSEST);
        } catch (RuntimeException e) {
            Logger.warn("MediaPlayer: video surface update failed: {}", e.toString());
        }
    }

    /** Hand the surface back only if it is still the live one. A surface being torn
     *  down must never blank a newer one that has already taken over: the outgoing
     *  SurfaceView's {@code surfaceDestroyed} can arrive after the incoming one's
     *  {@code surfaceCreated}, and a blind detach then left the player rendering
     *  nowhere until the next attach — the picture appearing to jump back. */
    public synchronized void detachVideoSurface(android.view.Surface surface) {
        if (surface == null || videoSurface != surface) return;
        videoSurface = null;
        applyVideoSurface();
    }

    private synchronized void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
        if (mp != player || width <= 0 || height <= 0) return;
        videoWidth = width;
        videoHeight = height;
        VideoSizeListener l = videoSizeListener;
        if (l != null) l.onVideoSize(width, height);
    }

    private void setDataSource(MediaPlayer mp, String src) throws IOException {
        if (src.startsWith("content://")) {
            mp.setDataSource(appContext, Uri.parse(src));
        } else if (isBiliCdn(src)) {
            // Bilibili's CDN answers 403 unless the request carries a bilibili
            // Referer, so the bare stream URL never plays — the reference client
            // sends these headers on every media request too.
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Referer", "https://www.bilibili.com/");
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            mp.setDataSource(appContext, Uri.parse(src), headers);
        } else {
            mp.setDataSource(src);
        }
    }

    /** Hosts the bilibili streams are served from (upos mirrors + akamai). */
    private static boolean isBiliCdn(String url) {
        return url != null && (url.contains("bilivideo") || url.contains("bilibili")
                || url.contains("akamaized") || url.contains("biliapi"));
    }

    private synchronized void onPrepared(MediaPlayer preparedPlayer) {
        // releasePlayer() cannot prevent a callback that was already queued on the
        // media thread. Never let an old source start, publish onStarted, or apply its
        // state to the replacement MediaPlayer after a rapid track switch.
        if (player != preparedPlayer) return;

        // The surface may have been attached before prepare finished.
        applyVideoSurface();
        prepared = true;
        applyListenerEq();
        Logger.info("MediaPlayer: prepared, duration={}ms", preparedPlayer.getDuration());
        applyVolume();
        if (pendingSeekMs > 0L) {
            seeking = true;
            preparedPlayer.seekTo(pendingSeekMs, MediaPlayer.SEEK_CLOSEST);
        }
        if (wantPlay) {
            preparedPlayer.start();
            Runnable cb = onStarted;
            if (cb != null) cb.run();
        }
    }

    private synchronized void onMainSeekComplete(MediaPlayer seekedPlayer) {
        if (seekedPlayer == player) seeking = false;
    }

    private synchronized void onCompleted(MediaPlayer completedPlayer) {
        if (player != completedPlayer) return;
        Logger.info("MediaPlayer: completed");
        fire(onComplete);
    }

    private synchronized boolean onPlayerError(MediaPlayer failedPlayer, int what, int extra) {
        if (player != failedPlayer) return true;
        Logger.error("MediaPlayer error: what={} extra={}", what, extra);
        fire(onError);
        // PlayerController's error path retries stale URLs once and advances when
        // that fails. Firing completion too would race a second auto-advance against
        // that recovery and could replace the retried track.
        return true;
    }

    @Override
    public synchronized void pause() {
        // Logged because a pause is the one thing that can end playback without any
        // other trace — "why did it stop by itself?" is otherwise unanswerable, and
        // the position says whether the stop happened where the listener was.
        Logger.info("MediaPlayer: pause at {}ms{}", position(),
                crossfading || incomingPlayer != null ? " (with a transition in flight)" : "");
        wantPlay = false;
        // A pause the APP (or the user) decided is the one thing that cancels a
        // pending focus come-back: "it paused when B站 started, and the user then
        // pressed pause" must not be undone by the next AUDIOFOCUS_GAIN. The focus
        // loss's own pause does not come through here — it pauses the player
        // directly, which is what keeps its flag alive.
        resumeWindow.cancel();
        pausedByFocus = false;
        // A pause in the middle of a post-mix ease-back would leave the track at
        // whatever tempo it had reached and resume it there; the correction is worth
        // more than the smoothness of a ramp that is being interrupted anyway.
        finishRestoreNow();
        // Pausing in the middle of an overlap would leave the incoming player
        // audible while the track it is replacing goes quiet. The transition is
        // dropped instead (the outgoing player is restored first), so the pause
        // still means "everything stops" and the caller's ordinary switch is
        // still available for the end of the track.
        abortCrossfade(true);
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
        }
    }

    @Override
    public synchronized void resume() {
        Logger.info("MediaPlayer: resume at {}ms", position());
        wantPlay = true;
        // The user (or the media session) asked for playback: there is nothing left
        // for a later focus regain to resume, and a loss from before this request no
        // longer describes what the app is doing.
        resumeWindow.cancel();
        pausedByFocus = false;
        requestFocus();
        if (player != null && prepared) {
            player.start();
        }
    }

    @Override
    public synchronized boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    @Override
    public synchronized void seek(long ms) {
        long target = Math.max(0L, ms);
        if (activeAutoMix != null) {
            // Seeking always returns to the untouched song, including its skipped intro.
            String original = activeAutoMix.original;
            boolean resume = wantPlay;
            play(original, target);
            if (!resume) pause();
            return;
        }
        Logger.info("MediaPlayer: seek to {}ms", target);
        // The user is going somewhere else in THIS track: settle any post-mix
        // ease-back first, so what they seek into is the track at its own tempo.
        finishRestoreNow();
        // A seek says the user is staying in THIS track, so an overlap that has
        // already started pulling the incoming one up no longer describes what is
        // about to happen at the end of the song: drop it and fade nothing.
        if (crossfading || incomingPlayer != null) abortCrossfade(true);
        if (player != null && prepared) {
            // SEEK_CLOSEST lands on the exact frame instead of the previous sync frame:
            // a basic seekTo undershoots by up to a keyframe interval, which made a
            // lyric tap (and the progress bar) land just before the target so the
            // PREVIOUS line highlighted.
            seeking = true;
            player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
        } else {
            pendingSeekMs = target;
        }
    }

    @Override
    public synchronized long position() {
        if (player != null && prepared) {
            try {
                long position = player.getCurrentPosition();
                return activeAutoMix == null ? position : activeAutoMix.songPosition(position);
            } catch (IllegalStateException e) {
                return 0L;
            }
        }
        return 0L;
    }

    @Override
    public synchronized long duration() {
        if (activeAutoMix != null) return activeAutoMix.durationMs;
        if (player != null && prepared) {
            try {
                int d = player.getDuration();
                return d > 0 ? d : 0L;
            } catch (IllegalStateException e) {
                return 0L;
            }
        }
        return 0L;
    }

    @Override
    public synchronized void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        applyVolume();
    }

    private void applyVolume() {
        // While the two players are ramped against each other the gain belongs to
        // the ramp: a controller fade (its fade-out runs at the same end-of-track
        // boundary that started this crossfade) would otherwise write its own
        // value — 0 — into the audible player and hand that silence to the track
        // the promotion promotes.
        if (crossfading) return;
        if (player != null && prepared) {
            float effective = baseGain();
            player.setVolume(effective, effective);
        }
    }

    /** The gain the audible player should sit at: the user's volume, ducked when
     *  focus was lost transiently. Both players are ramped against this. */
    private float baseGain() {
        return ducked ? volume * 0.3f : volume;
    }

    private static float clampGain(float gain) {
        return Math.max(0f, Math.min(1f, gain));
    }

    @Override
    public synchronized void setOnStarted(Runnable callback) {
        this.onStarted = callback;
    }

    @Override
    public synchronized void setOnPaused(Runnable callback) {
        this.onPaused = callback;
    }

    @Override
    public synchronized void setOnResumed(Runnable callback) {
        this.onResumed = callback;
    }

    @Override
    public synchronized void setOnError(Runnable callback) {
        this.onError = callback;
    }

    @Override
    public synchronized void setOnComplete(Runnable callback) {
        this.onComplete = callback;
    }

    @Override
    public synchronized void release() {
        releasePlayer();
        abandonFocus();
    }

    // --- Transitions between two players -------------------------------------

    @Override
    public synchronized boolean prepareIncoming(String src, long startMs) {
        return prepareIncoming(src, startMs, true);
    }

    /** Prepare {@code src} as the NEXT track on a second player: seeked to
     *  {@code startMs}, prepared, and — when {@code startMuted} is true — already
     *  playing at volume 0, so the ramp in {@link #beginCrossfade} fades in
     *  something that is really rolling instead of paying for a cold start at the
     *  exact moment the two songs have to overlap.
     *
     *  <p>With {@code startMuted} false the player is parked instead: seeked to
     *  {@code startMs} and silent, but not started, so its play head stays exactly
     *  there until the ramp starts it. A trim places the next track's first audible
     *  sample at the outgoing track's content end, and a player that has been
     *  rolling for the seconds spent waiting would be that far into the song by
     *  then — the one thing that placement cannot tolerate.
     *
     *  <p>Nothing here touches the audible player, its source, its surface or the
     *  audio focus: focus is per-backend and the active track already holds it, so
     *  requesting it again would be a second, pointless request for the very same
     *  playback. The configured listener set is the same one {@link #play} uses, so
     *  the bili CDN headers still apply on the setDataSource path.
     *
     *  @return false when the source cannot even be opened. The caller then has no
     *          incoming track and has to keep the ordinary switch. */
    @Override
    public synchronized boolean prepareIncoming(String src, long startMs, boolean startMuted) {
        return prepareIncoming(src, startMs, startMuted, IncomingMix.IDENTITY);
    }

    /** Prepare {@code src} as the NEXT track on a second player: seeked to
     *  {@code startMs}, prepared, and — when {@code startMuted} is true — already
     *  playing at volume 0, so the ramp in {@link #beginCrossfade} fades in
     *  something that is really rolling instead of paying for a cold start at the
     *  exact moment the two songs have to overlap.
     *
     *  <p>With {@code startMuted} false the player is parked instead: seeked to
     *  {@code startMs} and silent, but not started, so its play head stays exactly
     *  there until the ramp starts it. A trim places the next track's first audible
     *  sample at the outgoing track's content end, and a player that has been
     *  rolling for the seconds spent waiting would be that far into the song by
     *  then — the one thing that placement cannot tolerate.
     *
     *  <p>Nothing here touches the audible player, its source, its surface or the
     *  audio focus: focus is per-backend and the active track already holds it, so
     *  requesting it again would be a second, pointless request for the very same
     *  playback. The configured listener set is the same one {@link #play} uses, so
     *  the bili CDN headers still apply on the setDataSource path.
     *
     *  @return false when the source cannot even be opened. The caller then has no
     *          incoming track and has to keep the ordinary switch. A mix is never a
     *          reason to answer false: what it actually applied is reported through
     *          {@link #incomingMix()}. */
    @Override
    public synchronized boolean prepareIncoming(String src, long startMs, boolean startMuted,
                                                IncomingMix mix) {
        if (src == null || src.isEmpty()) return false;
        cancelIncoming();
        // A previous boundary's incoming may have been dropped while it was already
        // audible; that reading has now been superseded by this new one.
        droppedIncomingMs = -1L;
        incomingSource = src;
        incomingSeekMs = Math.max(0L, startMs);
        incomingStartMuted = startMuted;
        incomingRolling = false;
        // The mix is remembered, not applied here: the player is only in a state that
        // accepts playback parameters once it has prepared, which is the callback
        // below. A platform that refuses them is reported rather than guessed at.
        requestedMix = mix != null ? mix : IncomingMix.IDENTITY;
        MediaPlayer mp = new MediaPlayer();
        mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setOnPreparedListener(this::onIncomingPrepared);
        // Reused on purpose: this player is not "the player" yet, so a completion
        // arriving before the swap is ignored by that method's identity check, and
        // after the swap the same listener is the right one to have.
        mp.setOnCompletionListener(this::onCompleted);
        mp.setOnErrorListener(this::onIncomingError);
        incomingPlayer = mp;
        try {
            Logger.info("MediaPlayer: incoming setDataSource + prepareAsync (startMuted={}{})",
                    startMuted, requestedMix.isIdentity() ? "" : ", " + requestedMix);
            setDataSource(mp, src);
            mp.prepareAsync();
        } catch (IOException | IllegalStateException | IllegalArgumentException
                 | SecurityException e) {
            Logger.error("MediaPlayer incoming setDataSource failed: {}", e.toString());
            releaseIncoming();
            return false;
        }
        return true;
    }

    /** The incoming source is ready: silence it before its first sample is decoded,
     *  put it at the requested offset, apply the mix it was prepared for, and — for
     *  an overlap — get it rolling, so the ramp fades in a stream that is already at
     *  the right place in the song. A parked incoming simply stays there. */
    private synchronized void onIncomingPrepared(MediaPlayer prepared) {
        if (incomingPlayer != prepared) return;
        incomingPrepared = true;
        try {
            prepared.setVolume(0f, 0f);
        } catch (Throwable ignored) { }
        if (incomingSeekMs > 0L) {
            prepared.seekTo(incomingSeekMs, MediaPlayer.SEEK_CLOSEST);
        }
        // The one instruction this boundary has for the incoming player: the low end
        // hands over part way through the overlap. Attached here, before anything is
        // audible, because the equalizer is allocated while a single track is playing.
        applyRequestedMix(prepared);
        if (incomingStartMuted && wantPlay) {
            // ROLLING (unused by the controller today, kept for a trim-style caller):
            // the player runs silently from here so the ramp fades in a moving stream.
            try {
                prepared.start();
                incomingRolling = true;
            } catch (Throwable ignored) { }
        } else {
            // ⚠️ THE INVARIANT. A parked incoming must not have moved: its offset is the
            // sample the overlap is supposed to make audible FIRST, and the ramp is
            // supposed to be the first moment any of it is heard — anything that starts
            // it earlier silently eats that much of the next track (measured on the
            // device: promoted at 19769ms while the overlap had only played 10385ms, so
            // the listener never heard the first nine seconds of the incoming track).
            // And this is the arm that has to hold it now that a mix is applied: the
            // tempo/pitch call above STARTS a prepared player as a side effect, so the
            // player leaves here rolling at full speed and the park below is what puts it
            // back at exactly its offset. The platform is not trusted to keep the promise
            // for free — whatever state the player ends up in, this checks and reports.
            parkIncoming(prepared);
        }
        Logger.info("MediaPlayer: incoming prepared {} duration={}ms",
                incomingStartMuted ? "and rolling muted"
                        : ("parked at " + incomingSeekMs + "ms, nothing of it heard yet"),
                prepared.getDuration());
    }

    /**
     * Put a prepared incoming player that must not be running back at its own offset.
     *
     * <p>See {@link #onIncomingPrepared} for why this matters: the parked player's
     * offset is where the overlap is supposed to begin making it audible, so a player
     * that has been rolling since it was prepared has already skipped that much of the
     * next track by the time the ramp reaches it. Pausing and re-seeking is exactly
     * the operation the whole parked lane exists to make unnecessary, so it is not
     * expected to fire — it is here so that the invariant holds on a platform that
     * starts a prepared player for reasons of its own, rather than being assumed.
     */
    private void parkIncoming(MediaPlayer prepared) {
        long at = -1L;
        boolean wasRolling = false;
        try {
            wasRolling = prepared.isPlaying();
        } catch (Throwable ignored) { }
        try {
            at = prepared.getCurrentPosition();
        } catch (Throwable ignored) { }
        if (wasRolling) {
            try {
                prepared.pause();
            } catch (Throwable ignored) { }
        }
        if (wasRolling || (at >= 0L && Math.abs(at - incomingSeekMs) > PARK_TOLERANCE_MS)) {
            try {
                prepared.seekTo(Math.max(0L, incomingSeekMs), MediaPlayer.SEEK_CLOSEST);
                Logger.warn("MediaPlayer: the parked incoming had started itself (at {}ms, asked for"
                        + " {}ms); paused it and put it back, so the ramp starts it at exactly its"
                        + " own content start and the listener hears the next track from there"
                        + " (a mix's setPlaybackParams is what starts a prepared player — this is"
                        + " the park that undoes it)",
                        at, incomingSeekMs);
            } catch (Throwable e) {
                Logger.warn("MediaPlayer: could not re-park the incoming at {}ms: {}",
                        incomingSeekMs, e.toString());
            }
        }
        incomingRolling = false;
    }

    private synchronized boolean onIncomingError(MediaPlayer failed, int what, int extra) {
        if (incomingPlayer != failed) return true;
        Logger.error("MediaPlayer incoming error: what={} extra={}", what, extra);
        // A transition that lost its incoming track must not be left standing
        // half-applied: drop the player, put the audible one back at its normal
        // level, and tell the caller so the ordinary track switch can take over.
        abortCrossfade(true);
        return true;
    }

    @Override
    public synchronized boolean beginCrossfade(long ms) {
        return beginCrossfade(ms, FadeCurve.LINEAR);
    }

    /**
     * Apply the mix the incoming player was prepared for: first the tempo it is pulled
     * to and the semitones it is transposed by, then the low end changing hands part
     * way through the overlap.
     *
     * <p>The hand-over is an {@code Equalizer} on each player's own audio session
     * ({@code getAudioSessionId()}): the incoming's bass bands start cut, so the
     * outgoing track keeps the foundation of the mix to itself, and the two swap at
     * {@link IncomingMix#bassSwapAtMs()}. Attached here, before the ramp, so nothing
     * is allocated while two tracks are sounding — and released at the promotion.
     *
     * <p>The tempo and the pitch are the outgoing track's own grid and key, pulled onto
     * the incoming track (see {@code MixNaturaliser}) — applied here, before anything is
     * audible, and eased back after the promotion. The equalizer half is best-effort by
     * design: a device with no usable effects plays the same overlap without the
     * hand-over, which is a smaller loss than refusing the blend. Both halves are
     * recorded as what actually took ({@link #appliedMix}) and reported through
     * {@link #incomingMix()} rather than assumed, so the caller can always check — and
     * so a platform that refused the tempo does not leave the caller's handoff
     * arithmetic describing a stretch that never happened.
     */
    private void applyRequestedMix(MediaPlayer in) {
        IncomingMix mix = requestedMix;
        pitchIdentityAtFileMs = -1L;
        if (mix == null || mix.isIdentity()) return;
        // The tempo and the pitch first: they are the correction the whole overlap was
        // planned around (the length, the grid, the offset the track will be promoted
        // at), so what actually took has to be known before anything else is set up.
        // A platform that refuses them leaves the numeric side at its own value and the
        // caller's read-back of incomingMix() then tells it the boundary is running
        // un-stretched instead of it believing a plan that is no longer true.
        if (mix.hasTempo() || mix.semitones() != 0) {
            if (applyTempo(in, mix.speed(), mix.pitch())) {
                appliedMix = mix;
            } else {
                appliedMix = mix.bassSwapAtMs() >= 0L
                        ? IncomingMix.bassSwapAt(mix.bassSwapAtMs()) : IncomingMix.IDENTITY;
            }
        } else {
            appliedMix = mix;
        }
        // The scheduled pitch return, if this mix has one. Taken from the mix that was
        // ASKED for and only kept when the transposition really took, so a platform that
        // refused the parameters cannot leave a schedule running for a pitch that was
        // never applied (see applyTempo).
        pitchIdentityAtFileMs = appliedMix == null ? -1L : appliedMix.pitchIdentityAtFileMs();
        bassSwapAtMs = appliedMix.bassSwapAtMs();
        attachBassSwap(bassSwapAtMs >= 0L);
    }

    /**
     * {@code MediaPlayer.setPlaybackParams}, and then the same values read back.
     *
     * <p>The read-back is the point of the method: "the platform accepted it" is a
     * claim that has to be checkable from a log, because a silently ignored speed
     * would leave a plan that assumes a stretched track describing a track that is not
     * stretched (and the handoff arithmetic for that boundary off by the ratio).
     * {@code getPlaybackParams} throws when nothing has been set, so every failure here
     * answers false and the caller keeps the un-stretched instruction.
     *
     * <p>⚠️ The call is made while the incoming player is parked, and it has one side
     * effect the whole parked lane has to survive: it STARTS the player. That is why
     * {@code onIncomingPrepared} re-parks it immediately afterwards, and why
     * {@code beginCrossfade} re-asserts the same values once the ramp has started it —
     * insurance against a platform that ignores playback parameters applied before
     * playback, and the log line that says the incoming track is running at a ratio.
     */
    private boolean applyTempo(MediaPlayer mp, double speed, double pitch) {
        try {
            long stamp = writeParams(mp, "incoming", "apply-mix", speed, pitch);
            if (stamp < 0L) {
                Logger.warn("MediaPlayer: incoming x{} speed / x{} pitch refused — the pair"
                        + " blends un-stretched", fmt(speed), fmt(pitch));
                return false;
            }
            boolean rolling;
            try {
                rolling = mp.isPlaying();
            } catch (Throwable e) {
                rolling = false;
            }
            Logger.info("MediaPlayer: incoming mix applied to the INCOMING player (speed x{}"
                            + " pitch x{} asked, read back by the write above; the audible track"
                            + " is untouched; it is parked, and {} was started by the call — the"
                            + " park below puts it back)",
                    fmt(speed), fmt(pitch), rolling ? "it" : "nothing");
            return true;
        } catch (Throwable e) {
            Logger.warn("MediaPlayer: incoming mix failed: {}", e.toString());
            return false;
        }
    }

    /**
     * One {@code MediaPlayer.setPlaybackParams} write, and the numbers that make it
     * evidence rather than a claim: which player, why, what was asked, what the platform
     * reports back, how long the call itself took, and where each player's play head was
     * when it happened.
     *
     * <p>⚠️ <b>This method exists because a live {@code setPlaybackParams} is the one call
     * on this path that can make the platform rebuild its audio pipeline, which the
     * listener hears as a hiccup.</b> Every correction this class performs now goes through
     * here, so a log can answer "was a parameter written at the instant the listener heard
     * the gap" instead of "a mix was applied somewhere inside this blend". The read-back is
     * part of the write on purpose (a platform that silently ignores the values would
     * otherwise leave a plan describing a track that is not stretched); the elapsed time of
     * the call is part of the line because a call that takes a frame's worth of time is
     * doing work the audio thread will notice.
     *
     * @return the wall clock the write finished at, or {@code -1} when the platform refused
     *         it.
     */
    private long writeParams(MediaPlayer mp, String who, String reason, double speed,
                             double pitch) {
        if (mp == null) return -1L;
        long before = stamp();
        long posBefore = positionOf(mp);
        Float readSpeed = null;
        Float readPitch = null;
        String failure = null;
        try {
            mp.setPlaybackParams(new PlaybackParams()
                    .setSpeed((float) speed).setPitch((float) pitch));
            PlaybackParams read = mp.getPlaybackParams();
            readSpeed = read.getSpeed();
            readPitch = read.getPitch();
        } catch (Throwable e) {
            failure = e.toString();
        }
        long after = stamp();
        long posAfter = positionOf(mp);
        // Kept for the caller that has to report the curve the platform really took rather than
        // the one it was asked for (the key blend's own summary line).
        lastReadSpeed = readSpeed;
        lastReadPitch = readPitch;
        Logger.info("MediaPlayer: params-write reason={} who={} asked=speed x{} pitch x{}"
                        + " platform=speed x{} pitch x{} took={}ms pos {}->{}ms audible={}ms{}",
                reason, who, fmt(speed), fmt(pitch), fmt(readSpeed), fmt(readPitch),
                after - before, posBefore, posAfter, position(), 
                failure != null ? " REFUSED " + failure : "");
        return failure != null ? -1L : after;
    }

    /** What the last {@link #writeParams} call was told, or null when the platform refused it:
     *  the read-back half of the write, kept for the caller that has to print a curve. */
    private Float lastReadSpeed;
    private Float lastReadPitch;

    /** The monotonic clock every trace line and the audio-clock monitor are compared on
     *  ({@code elapsedRealtime}: uptime, so a clock adjustment cannot look like a stall). */
    private static long stamp() {
        return android.os.SystemClock.elapsedRealtime();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.4f", v);
    }

    private static String fmt(Float v) {
        return v == null ? "-" : fmt(v.doubleValue());
    }

    // --- Back to the track's own tempo and pitch ------------------------------

    /**
     * Put the promoted track back at its own tempo and pitch — in one call.
     *
     * <p>See {@link #PROMOTION_RESTORE_MS} for why this is a step and no longer the
     * six-second sixty-call glide it used to be. Two things are deliberate about the
     * shape:
     *
     * <ul>
     *   <li><b>Nothing is written when there is nothing to undo.</b> A pitch-only or
     *   swap-only boundary ran at {@code speed 1.0} the whole way; writing {@code x1.0} over
     *   {@code x1.0} sixty times was the single most common form of this defect in round
     *   12's device logs (every one of those boundaries logged
     *   "easing ... back to its own tempo and pitch over 6000ms (from x1.0000 / x1.0000)").</li>
     *   <li><b>The write happens before the players are torn down.</b> The promotion that
     *   calls this is about to release the outgoing player and both equalizers; doing the
     *   one parameter write first keeps it attributable in the log, and keeps it off the
     *   heap of work that releasing an effect on the promoted player's own session
     *   already is.</li>
     * </ul>
     *
     * <p>{@code speed} is the ratio the promoted deck has been running at. A value of 1.0
     * (with the pitch already at its own — see {@link #startPitchBack}) is "nothing owed",
     * and the log says so rather than writing the same numbers back.
     */
    private void restoreTempoNow(double speed) {
        restoreFromSpeed = speed;
        restoreStartNs = stamp();
        long generation = ++restoreGeneration;
        MediaPlayer mp = player;
        boolean owed = Math.abs(speed - 1d) > 1e-4d;
        if (legacyEaseBacks()) {
            // The "before" arm: round 12's 10 Hz glide, which ran even when there was nothing
            // to undo — the shape this round's fix is measured against.
            restoring = true;
            Logger.info("MediaPlayer: [A/B arm: the per-tick glide] easing the promoted track"
                    + " back to its own tempo and pitch over {}ms (from x{}), {} per-tick"
                    + " writes", PROMOTION_RESTORE_MS, fmt(speed),
                    PROMOTION_RESTORE_MS / RESTORE_TICK_MS);
            legacyRestoreStep(generation);
            return;
        }
        if (!owed) {
            restoring = false;
            Logger.info("MediaPlayer: the promoted track's tempo is its own already (it ran at"
                    + " x{} through the overlap), so there is nothing to write back — one"
                    + " parameter call fewer on the deck the listener is hearing", fmt(speed));
            return;
        }
        restoring = true;
        long when = writeParams(mp, "audible", "tempo-restore", 1d, 1d);
        restoring = false;
        // The deck the monitor watches is now running at its own rate: without this the next
        // tick would compare a 1.0 advance against the mix's ratio and report a stall that is
        // the correction itself.
        clockInSpeed = 1d;
        if (generation != restoreGeneration) return;
        if (when < 0L) {
            Logger.warn("MediaPlayer: the promoted track is still at x{} — the platform refused"
                    + " the write back to its own tempo", fmt(speed));
        } else {
            Logger.info("MediaPlayer: the promoted track is back at its own tempo and pitch —"
                    + " one write, at the promotion ({}ms of its file), instead of the {}"
                    + " per-tick writes this used to take",
                    positionOf(mp), PROMOTION_RESTORE_MS / RESTORE_TICK_MS);
        }
    }

    /**
     * Settle the tempo NOW, at its own value.
     *
     * <p>Called whenever playback is interrupted (a pause, a seek, a new track, a release):
     * a pending correction must not survive into a resumed track, and a resumed track at
     * the wrong tempo is worse than one correction. Also bumps the generation, so a write
     * already queued on the main looper can never touch the next player.
     */
    private void finishRestoreNow() {
        if (!restoring) return;
        restoring = false;
        restoreGeneration++;
        MediaPlayer mp = player;
        if (mp == null) return;
        if (Math.abs(restoreFromSpeed - 1d) <= 1e-4d) return;
        if (writeParams(mp, "audible", "tempo-restore-early", 1d, 1d) >= 0L) {
            Logger.info("MediaPlayer: tempo and pitch restore finished early (playback changed)");
        }
    }

    // --- the A/B arm (round 12's per-tick glide) ------------------------------

    /** Whether this process runs the measured "before" shape. Read once per process from
     *  {@code files/legacy-ease-backs}: absent (the ordinary case) means the shipped answer —
     *  one write per return — and present means round 12's 10 Hz glide, which is what the
     *  hiccup was measured against. Every read is logged, so a log can never be ambiguous
     *  about which arm produced it. */
    private boolean legacyEaseBacks() {
        if (legacyArmChecked) return legacyEaseBacks;
        legacyArmChecked = true;
        java.io.File marker = new java.io.File(appContext.getFilesDir(), "legacy-ease-backs");
        legacyEaseBacks = marker.isFile();
        Logger.info("MediaPlayer: ease-back arm = {} ({})", legacyEaseBacks ? "LEGACY (10Hz glide)"
                        : "single write", legacyEaseBacks
                        ? marker.getAbsolutePath() + " is present, so this run reproduces round"
                                + " 12's per-tick ramp for the A/B"
                        : "no " + marker.getAbsolutePath() + " marker, so this is the shipped"
                                + " shape: one write per correction");
        return legacyEaseBacks;
    }

    /** Round 12's tempo glide: one write per 100 ms for six seconds. Kept only for the A/B
     *  arm — see {@link #legacyEaseBacks()}. */
    private void legacyRestoreStep(long generation) {
        boolean done = false;
        synchronized (this) {
            if (!restoring || generation != restoreGeneration) return;
            MediaPlayer mp = player;
            if (mp == null) {
                restoring = false;
                return;
            }
            long elapsed = System.nanoTime() - restoreStartNs;
            long durationNs = PROMOTION_RESTORE_MS * 1_000_000L;
            double t = elapsed >= durationNs ? 1d : (double) elapsed / (double) durationNs;
            double speed = restoreFromSpeed + (1d - restoreFromSpeed) * t;
            if (writeParams(mp, "audible", "tempo-restore-tick", speed, 1d) < 0L) {
                restoring = false;
                return;
            }
            clockInSpeed = speed;
            if (t >= 1d) {
                restoring = false;
                done = true;
            }
        }
        if (done) {
            Logger.info("MediaPlayer: the promoted track is back at its own tempo and pitch");
            return;
        }
        try {
            rampHandler.postDelayed(() -> legacyRestoreStep(generation), RESTORE_TICK_MS);
        } catch (Throwable ignored) { }
    }

    /** Round 12's pitch glide: one write per 100 ms over the six seconds before the
     *  deadline, ending at the track's own pitch. The A/B arm's other half. */
    private void legacyPitchBackStep(long generation) {
        boolean finished = false;
        long at;
        synchronized (this) {
            if (!pitchBackRunning || generation != pitchBackGeneration) return;
            MediaPlayer mp = pitchBackPlayer;
            if (mp == null) {
                pitchBackRunning = false;
                return;
            }
            at = positionOf(mp);
            if (at < 0L) {
                pitchBackRunning = false;
                writeParams(mp, "incoming", "pitch-return-no-clock", pitchBackSpeed, 1d);
                return;
            }
            long remaining = pitchIdentityAtFileMs - at;
            double value;
            if (remaining <= 0L) {
                value = 1d;
                pitchBackRunning = false;
                finished = true;
                pitchBackDoneAtMs = at;
            } else if (remaining >= IncomingMix.RESTORE_MS) {
                value = pitchBackFrom;
            } else {
                double t = 1d - (double) remaining / (double) IncomingMix.RESTORE_MS;
                value = pitchBackFrom + (1d - pitchBackFrom) * t;
            }
            if (writeParams(mp, "incoming", "pitch-return-tick", pitchBackSpeed, value) < 0L) {
                pitchBackRunning = false;
                return;
            }
        }
        if (finished) {
            Logger.info("MediaPlayer: the incoming track's pitch is its own again — its own clock"
                            + " says {}ms, the deadline the rule set was {}ms of its file ({}ms"
                            + " of margin before its vocals)",
                    at, pitchIdentityAtFileMs, MixNaturaliser.VOCAL_PITCH_MARGIN_MS);
            return;
        }
        try {
            rampHandler.postDelayed(() -> legacyPitchBackStep(generation), RESTORE_TICK_MS);
        } catch (Throwable ignored) { }
    }

    @Override
    public synchronized IncomingMix incomingMix() {
        // Null while the prepare is still in flight: the mix is applied the moment the
        // player has prepared (see onIncomingPrepared), so before that the honest
        // answer is "not known yet" rather than "nothing was applied".
        if (incomingPlayer == null || !incomingPrepared) return null;
        return appliedMix != null ? appliedMix : IncomingMix.IDENTITY;
    }


    // --- The low-end hand-over (platform Equalizer) ---------------------------

    /** Build the two equalizers and cut the incoming track's low end, so the
     *  outgoing track owns the bass for the first part of the overlap. */
    private void attachBassSwap(boolean wanted) {
        if (!wanted) return;
        equalizerIn = attachEqualizer(incomingPlayer, "incoming");
        equalizerOut = attachEqualizer(player, "outgoing");
        short[] inBands = lowBands(equalizerIn);
        if (inBands.length == 0) {
            // No band below the corner frequency on this device: there is nothing to
            // hand over, so the whole mechanism stands down rather than pretending.
            Logger.info("MediaPlayer: bass swap off (this device's equalizer has no band"
                    + " below {}Hz)", (int) BASS_SWAP_HZ);
            releaseEqualizers();
            bassSwapAtMs = -1L;
            return;
        }
        setLowBands(equalizerIn, inBands, minLevel(equalizerIn));
        Logger.info("MediaPlayer: bass swap armed: the incoming player's low end ({} band(s)"
                + " below {}Hz) starts cut, the hand-over is {}ms into the overlap",
                inBands.length, (int) BASS_SWAP_HZ, bassSwapAtMs);
    }

    private static Equalizer attachEqualizer(MediaPlayer mp, String who) {
        if (mp == null) return null;
        try {
            int session = mp.getAudioSessionId();
            if (session == 0) {
                Logger.warn("MediaPlayer: no audio session for the {} player, no bass swap", who);
                return null;
            }
            Equalizer eq = new Equalizer(0, session);
            eq.setEnabled(true);
            return eq;
        } catch (Throwable e) {
            // Reported, not hidden: on a device where the effect framework refuses
            // the session, the mix is still a tempo/key mix and only loses its bass.
            Logger.warn("MediaPlayer: could not attach an equalizer to the {} player ({}),"
                    + " mixing without the bass swap", who, e.toString());
            return null;
        }
    }

    /** One player's low end, or an empty array when the device's equalizer has no
     *  band below {@link #BASS_SWAP_HZ}. */
    private static short[] lowBands(Equalizer eq) {
        if (eq == null) return new short[0];
        try {
            short count = eq.getNumberOfBands();
            short[] out = new short[count];
            int found = 0;
            for (short b = 0; b < count; b++) {
                int centerMilliHz = eq.getCenterFreq(b);
                if (centerMilliHz > 0 && centerMilliHz / 1000d < BASS_SWAP_HZ) out[found++] = b;
            }
            short[] exact = new short[found];
            System.arraycopy(out, 0, exact, 0, found);
            return exact;
        } catch (Throwable e) {
            Logger.warn("MediaPlayer: equalizer band query failed: {}", e.toString());
            return new short[0];
        }
    }

    /** The device's own lower gain limit, mB — a full cut rather than a polite one,
     *  because a hand-over that is not heard is not a hand-over. */
    private static short minLevel(Equalizer eq) {
        try {
            return eq.getBandLevelRange()[0];
        } catch (Throwable e) {
            return (short) -1500;
        }
    }

    private static void setLowBands(Equalizer eq, short[] bands, short level) {
        if (eq == null || bands.length == 0) return;
        try {
            for (short b : bands) eq.setBandLevel(b, level);
        } catch (Throwable e) {
            Logger.warn("MediaPlayer: equalizer level write failed: {}", e.toString());
        }
    }

    /** The moment a mix was armed for: give the incoming back its low end and take
     *  the outgoing's away. Runs once, from the ramp, on a beat of the outgoing
     *  track — and on a beat of the incoming one too when its tempo was pulled onto
     *  that grid (which is the ordinary case now: see {@code MixNaturaliser}); when it
     *  was not, the incoming track's own foundation simply comes in on A's beat, which
     *  is the most this can be placed by. */
    /** How long the outgoing track's low end takes to leave once its instant has arrived, ms.
     *  2 s — a bar at this library's ordinary tempos, and the same span the fusion's own
     *  hand-over is written in ({@link dev.t1m3.qplayer.audio.FadeCurve#JUNCTION_XFADE_MS}).
     *  It was one write until round 26; see the call site for the listener's report. */
    private static final long BASS_SWAP_FADE_MS = 2_000L;

    /**
     * One tick of the low-end hand-over, {@code u} from 0 (the instant it was armed for, the
     * outgoing track at its own level) to 1 (the incoming track owns the low end).
     *
     * <p>Runs from the ramp's own tick, on the beat the controller picked — a beat of both
     * tracks, since the incoming is running at the outgoing's tempo. Written as a fraction
     * rather than as a start instant because the tick already knows how far past the swap it
     * is, and a {@code bassSwapNow} that had to remember its own beginning would be a second
     * clock to keep in step with this one.
     */
    private void bassSwapLevel(double u) {
        if (equalizerIn == null) return;
        double level = Math.max(0d, Math.min(1d, u));
        setLowBands(equalizerIn, lowBands(equalizerIn), (short) 0);
        short[] outBands = lowBands(equalizerOut);
        short outLevel = (short) Math.round(minLevel(equalizerOut) * level);
        setLowBands(equalizerOut, outBands, outLevel);
        if (level < 1d) return;
        bassSwapped = true;
        // The outgoing track's own low end is what this takes away, and the number is the
        // whole of the hand-over as far as that track is concerned: from this instant until
        // it is released, the song the listener is still following has no bottom to it. Logged
        // with where in the ramp it happened and what level the device's own limit allows,
        // because "the previous song sounds like it ended" has been reported at points where
        // the level was still near full and it was the foundation that had gone.
        Logger.info("MediaPlayer: bass swap done: the incoming track owns the low end now (the"
                        + " outgoing track's {} band(s) below {}Hz are at {}mB from here on,"
                        + " {}ms into a {}ms ramp — its level is unchanged, its bottom is gone;"
                        + " the bottom left over {}ms of fade, not in one write)",
                outBands.length, (int) BASS_SWAP_HZ, outLevel,
                (System.nanoTime() - rampStartNs) / 1000000L, rampDurationNs / 1000000L,
                BASS_SWAP_FADE_MS);
    }

    private void releaseEqualizers() {
        equalizerIn = releaseOneEffect(equalizerIn);
        equalizerOut = releaseOneEffect(equalizerOut);
        bassSwapped = false;
    }

    /** An effect that is not released keeps its engine (and the audio session it was
     *  attached to) alive, and the platform logs it as a leak. */
    private static Equalizer releaseOneEffect(Equalizer eq) {
        if (eq == null) return null;
        try {
            eq.setEnabled(false);
            eq.release();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Ramp the audible player down and the incoming one up over {@code ms} along
     *  {@code curve}, then promote the incoming player and release the outgoing
     *  one.
     *
     *  @return false when there is no prepared incoming player, or the audible one
     *          is not in a state that can be swapped out. Playback is then left
     *          exactly as it was: promoting an unprepared player would leave the
     *          app silent with the queue already advanced, which is strictly worse
     *          than not transitioning at all. */
    @Override
    public synchronized boolean beginCrossfade(long ms, FadeCurve curve) {
        if (crossfading || incomingPlayer == null || !incomingPrepared) return false;
        if (player == null || !prepared || !wantPlay) return false;
        if (incomingAutoMix != null) autoMixAudible = true;
        crossfading = true;
        rampCurve = curve != null ? curve : FadeCurve.LINEAR;
        long generation = ++rampGeneration;
        rampStartNs = System.nanoTime();
        rampDurationNs = Math.max(1L, ms) * 1000000L;
        // The outgoing track's own gain, from the start of the ramp: unity until the first
        // tick says otherwise. Re-armed here so a previous blend's tail can never be the
        // thing a release assertion reports on (see logOutgoingRelease).
        rampOutGain = 1d;
        rampOutLastAudibleGain = 1d;
        rampOutGainNs = rampStartNs;
        rampOutLastAudibleNs = rampStartNs;
        rampOutTraceStep = 0;
        rampOutTraceNs = 0L;
        // The hand-over has to land inside the overlap that is really being run, and
        // the ramp can be shorter than the plan's overlap (a boundary that arrived
        // early caps it, and a refused mix shortens it back to the plan's own
        // length). A swap scheduled after the ramp ends would never fire, and the
        // incoming track would be promoted with its low end still cut — a quarter of
        // the mix left permanently in place. So it is pulled back to just before the
        // promotion, where its effect at worst folds into the promotion itself.
        if (bassSwapAtMs >= 0L) {
            long latest = Math.max(0L, ms - 200L);
            if (bassSwapAtMs > latest) {
                Logger.info("MediaPlayer: bass swap moved from {}ms to {}ms (the ramp is {}ms)",
                        bassSwapAtMs, latest, ms);
                bassSwapAtMs = latest;
            }
        }
        // ⚠️ A parked incoming starts HERE, and only here: this is the moment its
        // offset was computed for, and the first moment any of the next track is
        // allowed to be audible. Everything the listener hears of it during the overlap
        // is `offset .. offset + ms` — nothing skipped, nothing replayed — which is the
        // invariant the controller's handoff and the promotion arithmetic both rest on.
        if (!incomingRolling) {
            try {
                incomingPlayer.start();
                incomingRolling = true;
                // ⚠️ Round 13: this used to WRITE the mix's parameters a second time ("assert
                // the mix now that the player is actually rolling"). It no longer does,
                // because the write is exactly the kind of call that can make the platform
                // rebuild the audio pipeline of the deck that is being faded in — at the most
                // exposed instant there is, the first sample of the blend — and because the
                // reason for it was never observed: onIncomingPrepared applies the mix and
                // READS IT BACK, and a platform that answered the read honestly is not going
                // to have dropped the values in between. What replaces it is the same
                // insurance priced as a read instead of a write: ask the platform what it has
                // before the ramp is left to run on the answer, and repair only on a
                // disagreement (never observed on the reference device; the line says which
                // happened either way).
                verifyMixOnRollingPlayer();
            } catch (Throwable e) {
                Logger.warn("MediaPlayer: parked incoming refused to start: {}", e.toString());
            }
        }
        Logger.info("MediaPlayer: crossfade begin over {}ms ({}{}{})", ms, rampCurve,
                appliedMix != null ? ", " + appliedMix : "",
                incomingRolling ? ", the incoming starts now at its offset" : "");
        // Read (and log) the A/B arm here as well, so a boundary with no correction at all
        // still says which shape produced its log.
        legacyEaseBacks();
        // Starting a parked player and reading parameters can take longer than a short
        // handoff. Its latency must never consume the gain ramp before its first tick.
        rampStartNs = System.nanoTime();
        // From here until well past the promotion, both players' audio clocks are sampled so
        // a gap in what the listener hears can be attributed to an instant: see the class's
        // monitor note.
        startClockMonitor();
        startPitchBack();
        rampStep(generation);
        return true;
    }

    /**
     * Ask the platform what it has, instead of telling it again.
     *
     * <p>The rolling incoming player is the one that was prepared with the mix and read back
     * after the write; this re-reads it a moment later (immediately after {@code start()}),
     * and only writes when the answer disagrees with what was applied. Both outcomes are
     * logged: "the platform still has it" is the ordinary case and the reason no parameter
     * call happens at the start of a blend any more, and "it did not, so it was re-written"
     * is the only evidence that the old unconditional re-assert was ever needed.
     */
    private void verifyMixOnRollingPlayer() {
        if (appliedMix == null || (!appliedMix.hasTempo() && appliedMix.semitones() == 0)) return;
        if (incomingPlayer == null) return;
        Float readSpeed = null;
        Float readPitch = null;
        try {
            PlaybackParams read = incomingPlayer.getPlaybackParams();
            readSpeed = read.getSpeed();
            readPitch = read.getPitch();
        } catch (Throwable e) {
            Logger.warn("MediaPlayer: could not read the rolling incoming player's parameters"
                    + " ({}); leaving them as applied", e.toString());
            return;
        }
        boolean kept = readSpeed != null && readPitch != null
                && Math.abs(readSpeed - appliedMix.speed()) < 1e-3f
                && Math.abs(readPitch - appliedMix.pitch()) < 1e-3f;
        if (kept) {
            Logger.info("MediaPlayer: the rolling incoming player still has the mix the parked"
                            + " one read back (speed x{} pitch x{}) — so no second parameter"
                            + " write at the start of the blend, which is one fewer chance to"
                            + " glitch the deck being faded in",
                    fmt(readSpeed), fmt(readPitch));
            return;
        }
        Logger.warn("MediaPlayer: the rolling incoming player came back with speed x{} pitch x{}"
                        + " where the parked one read back x{} / x{} — re-writing the mix once"
                        + " (this is the case the old unconditional re-assert existed for)",
                fmt(readSpeed), fmt(readPitch), fmt(appliedMix.speed()), fmt(appliedMix.pitch()));
        writeParams(incomingPlayer, "incoming", "repair-after-start",
                appliedMix.speed(), appliedMix.pitch());
    }

    // --- the audio-clock monitor ---------------------------------------------

    /** How much the audio clock may disagree with the wall clock (after the ratio) before it
     *  is reported. A pipeline refresh, an underrun, a stall and a re-buffer all show up as
     *  the play head not advancing while the clock does.
     *
     *  <p>⚠️ <b>Raised from 25 ms to 80 ms in round 14, on round 13's own evidence.</b> This
     *  device's {@code getCurrentPosition()} reports at roughly 30 ms granularity, so every
     *  sample carried ±25-52 ms of quantisation noise: round 13's two arms (same pair, same
     *  ramp) came out at 17 gaps each with the same distribution, which is a measurement of the
     *  probe rather than of the audio. What is unambiguous here is a play head that does not
     *  move AT ALL while wall time does — the frozen case round 13 actually caught (387 gaps,
     *  every one {@code moved=0}, the incoming deck stuck at 40 ms: "the second track never
     *  played"). That case is reported whatever this threshold says, see {@link #checkClock}. */
    private static final long CLOCK_GAP_MS = 80L;

    /** How long a play head has to stay at the SAME position, while the wall clock moves, before
     *  that is called a freeze.
     *
     *  <p>⚠️ <b>A single sample's {@code moved == 0} is not evidence on this device, and round 14's
     *  first device run proved it:</b> the raised drift threshold (see {@link #CLOCK_GAP_MS}) left
     *  36 gap lines whose only mark was a "FROZEN" tag, and the {@code moved} values behind them
     *  were −31…+5 ms per 20 ms sample — a deck that is playing normally, sampled at
     *  {@code getCurrentPosition()}'s own ~30 ms granularity, looks like that. What round 13
     *  actually caught was a position that never changed over HUNDREDS of samples (the deck stuck
     *  at 40 ms for a whole ramp), so the test is a sustained one: the position identical for
     *  this long, reported once per episode rather than once per tick. */
    private static final long CLOCK_FREEZE_MS = 250L;
    /** 20 ms: fine enough that a gap's own width is measured rather than inferred. */
    private static final long CLOCK_TICK_MS = 20L;
    /** How long the monitor keeps watching after the promotion. The overlap's tail plus the
     *  whole window a parameter write can still land in (the promotion's tempo write happens
     *  at the promotion itself), with slack for the promotion's own teardown. */
    private static final long CLOCK_TAIL_MS = 8_000L;

    private MediaPlayer clockIn,
            clockOut;
    private long clockInPos = -1L;
    private long clockOutPos = -1L;
    private long clockLastNs;
    private double clockInSpeed = 1d;
    private long clockStopAtNs;
    private int clockGapsIn;
    private int clockGapsOut;
    private int clockFreezesIn;
    private int clockFreezesOut;
    /** When each deck's position last CHANGED, and whether its current freeze episode has
     *  already been reported (one line per episode, not one per tick). */
    private long clockInStillNs;
    private long clockOutStillNs;
    private boolean clockInFreezeTagged;
    private boolean clockOutFreezeTagged;
    private long clockTicks;

    /**
     * Sample both players' play heads and report every discontinuity — the number the
     * hiccup hypothesis has to be tested against.
     *
     * <p><b>Why this is the honest proxy.</b> The claim under test is "a live
     * {@code setPlaybackParams} makes the platform rebuild its audio pipeline, and the
     * listener hears a gap". A gap in the audio a deck is emitting is exactly a stretch
     * where its play head does not advance while wall time does (the head is driven by the
     * sink's own clock), or a jump where it advances too far. So this samples
     * {@code getCurrentPosition()} at 50 Hz for each player, compares the advance against
     * what the wall clock and the player's ratio predict, and logs each disagreement with
     * its size, its direction and both players' positions. A clean run logs one summary line
     * per second and no gaps; a glitching one logs a gap whose timestamp can be put beside
     * the {@code params-write} lines.
     *
     * <p>It watches the INCOMING player (the one a mix is applied to and the one that
     * carries the transposition and tempo returns) and the audible one (which is where a
     * release or an equalizer write would show). It stops itself {@link #CLOCK_TAIL_MS}
     * after the promotion, and on any cancellation.
     */
    private void startClockMonitor() {
        long generation = ++clockGeneration;
        clockIn = incomingPlayer;
        clockOut = player;
        clockInPos = -1L;
        clockOutPos = -1L;
        clockLastNs = System.nanoTime();
        clockInSpeed = appliedMix != null ? appliedMix.speed() : 1d;
        clockStopAtNs = 0L;                       // set at the promotion
        clockGapsIn = 0;
        clockGapsOut = 0;
        clockFreezesIn = 0;
        clockFreezesOut = 0;
        clockInStillNs = System.nanoTime();
        clockOutStillNs = clockInStillNs;
        clockInFreezeTagged = false;
        clockOutFreezeTagged = false;
        clockTicks = 0;
        Logger.info("MediaPlayer: audio-clock monitor on (50Hz, incoming x{} + the audible"
                + " player): a drift gap is |drift| >= {}ms (this device reports"
                + " getCurrentPosition() in ~30ms steps, so that is above its own granularity),"
                + " and a FREEZE is a play head at the same position for {}ms while the wall"
                + " clock moves — reported once per episode, because a single sample's"
                + " `moved=0` is this device's quantisation and not a stall; so a hiccup can be"
                + " put beside the parameter writes that caused it",
                fmt(clockInSpeed), CLOCK_GAP_MS, CLOCK_FREEZE_MS);
        clockTick(generation);
    }

    /** Called at the promotion: keep watching for the tail window, then stop. */
    private void extendClockMonitor() {
        if (clockStopAtNs == 0L) {
            clockStopAtNs = System.nanoTime() + CLOCK_TAIL_MS * 1_000_000L;
        }
    }

    private void stopClockMonitor(String why) {
        if (clockIn == null && clockOut == null) return;
        clockGeneration++;
        Logger.info("MediaPlayer: audio-clock monitor off ({}): {} gap(s) on the incoming deck,"
                        + " {} on the audible one{}", why, clockGapsIn, clockGapsOut,
                clockFreezesIn + clockFreezesOut > 0
                        ? " — of which FROZEN (no advance at all): " + clockFreezesIn + " on the"
                                + " incoming deck, " + clockFreezesOut + " on the audible one"
                        : " (no frozen samples on either deck)");
        clockIn = null;
        clockOut = null;
        clockStopAtNs = 0L;
    }

    private void clockTick(long generation) {
        long now = System.nanoTime();
        StringBuilder gaps = new StringBuilder();
        boolean summary = false;
        synchronized (this) {
            if (generation != clockGeneration) return;
            long elapsedMs = (now - clockLastNs) / 1_000_000L;
            clockLastNs = now;
            if (elapsedMs <= 0L) elapsedMs = CLOCK_TICK_MS;
            if (!checkClock(clockIn, true, elapsedMs, gaps)) clockInPos = -1L;
            if (!checkClock(clockOut, false, elapsedMs, gaps)) clockOutPos = -1L;
            summary = (++clockTicks % (1_000L / CLOCK_TICK_MS)) == 0;
            if (clockStopAtNs != 0L && now > clockStopAtNs) {
                stopClockMonitor("the promotion's tail window elapsed");
                return;
            }
        }
        if (gaps.length() > 0) {
            Logger.info("MediaPlayer: audio-clock gap{} at {}ms uptime:{}",
                    gaps.indexOf(";") == gaps.lastIndexOf(";") ? "" : "s", stamp(), gaps);
        } else if (summary) {
            Logger.info("MediaPlayer: audio-clock {}({}); {} gap(s) so far{}", 
                    clockInPos < 0L ? "-" : clockInPos + "ms on the incoming deck",
                    clockOutPos < 0L ? "-" : clockOutPos + "ms on the audible one",
                    clockGapsIn + clockGapsOut,
                    clockFreezesIn + clockFreezesOut > 0
                            ? " (" + (clockFreezesIn + clockFreezesOut) + " FROZEN)" : "");
        }
        try {
            rampHandler.postDelayed(() -> clockTick(generation), CLOCK_TICK_MS);
        } catch (Throwable ignored) { }
    }

    /** One player's advance against the wall clock. Updates the cached head and appends a
     *  fragment to {@code gaps} when the two disagree. */
    private boolean checkClock(MediaPlayer mp, boolean incoming, long elapsedMs,
                               StringBuilder gaps) {
        if (mp == null) return false;
        long at = positionOf(mp);
        if (at < 0L) return false;
        long previous = incoming ? clockInPos : clockOutPos;
        double speed = incoming ? clockInSpeed : (restoring ? restoreFromSpeed : 1d);
        if (previous < 0L) {
            if (incoming) clockInPos = at; else clockOutPos = at;
            return true;
        }
        long actual = at - previous;
        long expected = Math.round(elapsedMs * speed);
        long drift = actual - expected;
        if (incoming) clockInPos = at; else clockOutPos = at;
        // Freeze: the position identical for CLOCK_FREEZE_MS of wall clock, reported once per
        // episode. Anything else — including the ±30ms excursions this device's position
        // reporter produces on a deck that is playing perfectly well — is not evidence, and a
        // single `moved=0` sample is explicitly NOT a freeze (see CLOCK_FREEZE_MS).
        long now = System.nanoTime();
        if (actual != 0L) {
            if (incoming) {
                clockInStillNs = now;
                clockInFreezeTagged = false;
            } else {
                clockOutStillNs = now;
                clockOutFreezeTagged = false;
            }
        }
        boolean frozen = false;
        if (actual == 0L) {
            long still = now - (incoming ? clockInStillNs : clockOutStillNs);
            boolean tagged = incoming ? clockInFreezeTagged : clockOutFreezeTagged;
            if (still < CLOCK_FREEZE_MS * 1_000_000L) return true;   // not yet a freeze
            if (tagged) return true;                                 // already reported
            frozen = true;
            if (incoming) {
                clockInFreezeTagged = true;
                clockFreezesIn++;
            } else {
                clockOutFreezeTagged = true;
                clockFreezesOut++;
            }
        } else if (Math.abs(drift) < CLOCK_GAP_MS) {
            return true;
        }
        if (!frozen) {
            if (incoming) clockGapsIn++; else clockGapsOut++;
        }
        gaps.append(' ').append(incoming ? "incoming" : "audible")
            .append(" at=").append(at).append("ms moved=").append(actual)
            .append("ms expected=").append(expected).append("ms over=").append(elapsedMs)
            .append("ms drift=").append(drift).append("ms")
            .append(frozen ? " FROZEN(same position for " + CLOCK_FREEZE_MS + "ms)" : "")
            .append(';');
        return true;
    }

    /**
     * Start the rule's own ease-back, if this mix has one scheduled: the pitch of the
     * incoming track goes back to its own by the position in its file the controller named
     * ({@link IncomingMix#pitchIdentityAtFileMs()}).
     *
     * <p>Started HERE, at the ramp, because this is the moment the incoming player starts
     * moving and therefore the moment its clock starts meaning something. Nothing is
     * scheduled before it: a parked player's position does not advance, and a schedule
     * against a stopped clock would have to fall back on the wall clock, which is the
     * thing this avoids (see the field's own note).
     *
     * <p>A mix with a transposition and no schedule is not started and not warned about:
     * that is a host that pushed the mix itself (a test, the desktop bridge), and its
     * transposition is undone at the promotion as it always was.
     */
    private synchronized void startPitchBack() {
        if (appliedMix == null || appliedMix.semitones() == 0) return;
        if (pitchIdentityAtFileMs < 0L || incomingPlayer == null) return;
        pitchBackPlayer = incomingPlayer;
        pitchBackFrom = appliedMix.pitch();
        pitchBackSpeed = appliedMix.speed();
        pitchBackDoneAtMs = -1L;
        pitchBackRunning = true;
        long generation = ++pitchBackGeneration;
        if (legacyEaseBacks()) {
            Logger.info("MediaPlayer: [A/B arm: the per-tick glide] the transposition on the"
                    + " incoming track ({} semitone(s), pitch x{}) is eased back over {}ms,"
                    + " ending by {}ms of its own file — the incoming is at {}ms now, and that"
                    + " is {} per-tick writes on a deck the listener is hearing",
                    appliedMix.semitones(), fmt(pitchBackFrom), IncomingMix.RESTORE_MS,
                    pitchIdentityAtFileMs, positionOf(incomingPlayer),
                    IncomingMix.RESTORE_MS / RESTORE_TICK_MS);
            legacyPitchBackStep(generation);
            return;
        }
        // ⚠️ Round 14: the return has two shapes and the mix picks which. A mix carrying a
        // {@link KeyGlide} glides — a few writes, on the grid, on BOTH decks, over the blend's
        // whole modulation section — and one that does not keeps round 13's single write at the
        // deadline. Nothing else about this path changed: the clock the schedule runs on is
        // still the incoming player's own position, because that is the clock the vocal entry
        // was measured in.
        KeyGlide glide = appliedMix.keyGlide();
        if (glide != null && glide.isGliding()) {
            startKeyGlide(glide);
            return;
        }
        Logger.info("MediaPlayer: the transposition on the incoming track ({} semitone(s),"
                        + " pitch x{}) is held until {}ms of its own file and then put back in"
                        + " ONE step — the incoming is at {}ms now, and the step lands 2000ms"
                        + " before its vocals. There is no per-tick glide any more: this is a"
                        + " single parameter write on a deck whose DJ edit has the vocals out{}",
                appliedMix.semitones(), fmt(pitchBackFrom), pitchIdentityAtFileMs,
                positionOf(incomingPlayer),
                glide != null ? " (no key blend for this pair: " + glide.note() + ")" : "");
        pitchBackCheck(generation);
    }

    /**
     * Arm the key blend's ladder: the plan {@link KeyGlide} built for this boundary, executed
     * against the incoming player's own clock.
     *
     * <p>The instants are positions in the incoming track's own file, so the deck's own
     * {@code getCurrentPosition()} decides when a step happens — the same clock the controller
     * measured the vocal entry in, and therefore the same clock the rule's two-second margin is
     * defined in. Each step writes BOTH decks at once (the incoming one to its own key, the
     * audible one into it): separately they would drift into two different keys between steps,
     * which is the one thing a parallel move cannot afford.
     */
    private void startKeyGlide(KeyGlide glide) {
        activeGlide = glide;
        glideInPlayer = incomingPlayer;
        glideOutPlayer = player;
        glideIndex = 0;
        glideRunning = true;
        glideTouchedOut = false;
        glideReadBackIn = new double[glide.steps()];
        glideReadBackOut = new double[glide.steps()];
        for (int i = 0; i < glide.steps(); i++) {
            glideReadBackIn[i] = Double.NaN;
            glideReadBackOut[i] = Double.NaN;
        }
        glideFirstStamp = stamp();
        long generation = ++glideGeneration;
        Logger.info("MediaPlayer: key blend armed — {}. The incoming is at {}ms of its own file"
                        + " now, the audible deck at {}ms; every step is written to BOTH decks at"
                        + " the same instant, so the two never leave the same key, and the audible"
                        + " deck's own gain is printed with each step",
                glide.describe(), positionOf(glideInPlayer), positionOf(glideOutPlayer));
        glideCheck(generation);
    }

    /**
     * The ladder's own check: read the incoming deck's clock and take every step whose instant
     * that clock has reached. Polled like the rule's single step (10 Hz — a step therefore lands
     * up to one tick late, which is why the last step is placed at the rule's deadline rather
     * than on it, and why the number it really landed at is logged).
     *
     * <p>A clock that cannot be read ({@code < 0}) takes the step now rather than never: the
     * ladder is a parallel move with a deadline at its end, and finishing late is a rule
     * violation while finishing a tick early is not.
     */
    private void glideCheck(long generation) {
        boolean refused = false;
        synchronized (this) {
            if (!glideRunning || generation != glideGeneration) return;
            KeyGlide glide = activeGlide;
            MediaPlayer in = glideInPlayer;
            if (glide == null || in == null) {
                stopGlide();
                return;
            }
            long at = positionOf(in);
            while (glideRunning && glideIndex < glide.steps()
                    && (at < 0L || at >= glide.atFileMs(glideIndex))) {
                if (!applyGlideStep(glideIndex, at)) {
                    refused = true;
                    break;
                }
                glideIndex++;
                if (glideIndex >= glide.steps()) {
                    glideRunning = false;
                    pitchBackDoneAtMs = at;
                }
            }
        }
        if (refused) {
            abortKeyGlide("the platform refused one of its parameter writes");
            return;
        }
        if (!glideRunning) {
            logGlideDone();
            return;
        }
        try {
            rampHandler.postDelayed(() -> glideCheck(generation), RESTORE_TICK_MS);
        } catch (Throwable ignored) { }
    }

    /**
     * One step of the ladder, on both decks. Called with the lock held.
     *
     * <p>The order is the incoming deck first, and it is deliberate: that write is the one the
     * rule needs (the transposition has to be off before the vocals), so if only one of the two
     * can be made the pair is that much closer to the state that is always safe, and the abort
     * below then completes the job on both.
     *
     * @return false when the platform refused either write.
     */
    private boolean applyGlideStep(int i, long atFileMs) {
        KeyGlide glide = activeGlide;
        double pIn = glide.incomingPitch(i);
        double pOut = glide.outgoingPitch(i);
        long stampIn = writeParams(glideInPlayer, "incoming", "key-glide", pitchBackSpeed, pIn);
        if (stampIn < 0L) {
            Logger.warn("MediaPlayer: the platform refused key-glide step {}/{} on the incoming"
                    + " deck (pitch x{} asked); the ladder is abandoned rather than left half"
                    + " climbed", i + 1, glide.steps(), fmt(pIn));
            return false;
        }
        glideReadBackIn[i] = readBackPitch(pIn);
        double outGain = lastOutGain;
        long stampOut = writeParams(glideOutPlayer, "audible", "key-glide-out", 1d, pOut);
        if (stampOut < 0L) {
            Logger.warn("MediaPlayer: the platform refused key-glide step {}/{} on the AUDIBLE"
                    + " deck (pitch x{} asked) — that deck does not glide in this build, so the"
                    + " ladder cannot keep the two in one key; abandoned", i + 1, glide.steps(),
                    fmt(pOut));
            return false;
        }
        glideTouchedOut = true;
        glideReadBackOut[i] = readBackPitch(pOut);
        Logger.info("MediaPlayer: key-glide step {}/{} at {}ms of the incoming's file (planned"
                        + " for {}ms): incoming {} st, pitch x{} read back x{}; audible {} st,"
                        + " pitch x{} read back x{}; the audible deck's own gain is {} of unity"
                        + " — the largest shift lands on the quietest moment, and the two decks"
                        + " are {} st apart, which is this pair's key distance",
                i + 1, glide.steps(), atFileMs, glide.atFileMs(i),
                fmt(glide.incomingSemitones(i)), fmt(pIn), fmt(lastReadPitchOf(i, true)),
                fmt(glide.outgoingSemitones(i)), fmt(pOut), fmt(lastReadPitchOf(i, false)),
                fmt(outGain),
                fmt(glide.incomingSemitones(i) - glide.outgoingSemitones(i)));
        return true;
    }

    /** The platform's own read-back for step {@code i}, or what was asked for when it would not
     *  answer — never NaN, so a log line can print a number either way. */
    private double lastReadPitchOf(int i, boolean incoming) {
        double[] read = incoming ? glideReadBackIn : glideReadBackOut;
        double value = read != null && i < read.length ? read[i] : Double.NaN;
        return Double.isNaN(value)
                ? (incoming && activeGlide != null ? activeGlide.incomingPitch(i)
                        : activeGlide != null ? activeGlide.outgoingPitch(i) : 1d)
                : value;
    }

    /** The read-back of the write that just happened, as a ratio, or what was asked for when the
     *  platform answered nothing (a refused write is handled by the caller, so this is only ever
     *  the "accepted but silent" case). */
    private double readBackPitch(double asked) {
        Float read = lastReadPitch;
        return read == null || read <= 0f ? asked : read;
    }

    /**
     * The ladder finished: the measured curve, and the two numbers the user's request is about —
     * how far the mix's tonality travelled, and where the incoming deck's clock stood when it
     * got there.
     *
     * <p>Printed from the platform's own read-backs rather than from the plan, because the plan
     * is what was asked for: a platform that accepted every write and every value is the thing
     * this line has to be evidence for.
     */
    private void logGlideDone() {
        KeyGlide glide = activeGlide;
        if (glide == null) return;
        StringBuilder curve = new StringBuilder();
        for (int i = 0; i < glide.steps(); i++) {
            curve.append(' ').append(loc(12d * log2(lastReadPitchOf(i, true)))).append('/')
                    .append(loc(12d * log2(lastReadPitchOf(i, false))));
        }
        Logger.info("MediaPlayer: key blend done — {} write(s) on each deck over {}ms ({}ms"
                        + " wall clock): the incoming deck's offset from its own key went from"
                        + " {} to {} semitones and the audible deck's from {} to {}, so the pair"
                        + " stayed {} semitone(s) apart (in ONE key) at every step while the"
                        + " tonality travelled from the outgoing track's key into the incoming"
                        + " track's own. Platform read-back, incoming/audible semitones per step:{}",
                glide.steps(), glide.sectionFileMs(), stamp() - glideFirstStamp,
                loc(glide.semitones()), "0.00",
                loc(0d), loc(-glide.semitones()), loc((double) glide.semitones()), curve);
        // The rule's own margin, MEASURED rather than asserted: where the deck's clock stood at
        // the last step against the instant the controller scheduled it for. `pitchIdentityAtFileMs`
        // is the last beat at or before the rule's deadline, so the incoming track's vocals are due
        // back at (that + the margin) — which makes the number below a lower bound on the real
        // margin (the beat snap can only have moved the deadline later, never earlier).
        long late = Math.max(0L, pitchBackDoneAtMs - pitchIdentityAtFileMs);
        Logger.info("MediaPlayer: key blend's last step landed at {}ms of the incoming's own"
                        + " file, against the {}ms it was planned for ({}ms late, inside one"
                        + " {}ms poll tick) — so the transposition was off at least {}ms before"
                        + " the incoming track's vocals are due back, where the rule asks for"
                        + " {}ms",
                pitchBackDoneAtMs, glide.atFileMs(glide.steps() - 1), late, RESTORE_TICK_MS,
                Math.max(0L, pitchIdentityAtFileMs + MixNaturaliser.VOCAL_PITCH_MARGIN_MS
                        - pitchBackDoneAtMs),
                MixNaturaliser.VOCAL_PITCH_MARGIN_MS);
        stopGlide();
    }

    /**
     * Abandon the ladder and put BOTH decks back at their own pitch, in one write each.
     *
     * <p>This is the shape every failure takes (see the callers: a refused write, a clock that
     * cannot be read, the promotion arriving early), and it is chosen for the same reason the
     * ladder is parallel in the first place: a half-climbed ladder is a pair sitting in a key
     * that is neither track's, so the only safe direction to finish in is the one the rule
     * already demands — the incoming track's own. What the listener is left with is then
     * exactly the boundary this build would have played without a key blend at all: no
     * transposition, everything else untouched.
     */
    private synchronized void abortKeyGlide(String why) {
        KeyGlide glide = activeGlide;
        if (glide == null && !glideRunning) return;
        int done = glideIndex;
        int steps = glide != null ? glide.steps() : 0;
        MediaPlayer in = glideInPlayer;
        MediaPlayer out = glideOutPlayer;
        boolean touched = glideTouchedOut;
        long at = positionOf(in);
        stopGlide();
        pitchBackRunning = false;
        pitchBackGeneration++;
        if (in != null) {
            writeParams(in, "incoming", "key-glide-abandoned", pitchBackSpeed, 1d);
        }
        if (out != null && touched) {
            writeParams(out, "audible", "key-glide-abandoned", 1d, 1d);
        }
        pitchBackDoneAtMs = at;
        Logger.warn("MediaPlayer: the key blend stopped after {} of {} steps ({}), at {}ms of the"
                        + " incoming's file — both decks are back at their own pitch in one write"
                        + " each, so the boundary is the one this build plays without a key blend"
                        + " and the outgoing track is never left off its own key",
                done, steps, why, at);
    }

    /** Stop the ladder's bookkeeping without writing anything: the caller is doing the writes,
     *  or the players are going away. */
    private void stopGlide() {
        glideRunning = false;
        glideGeneration++;
        activeGlide = null;
        glideInPlayer = null;
        glideOutPlayer = null;
        glideTouchedOut = false;
    }

    /**
     * Put the AUDIBLE deck back at its own pitch if the ladder had already bent it.
     *
     * <p>Called from every path that drops an overlap without promoting it. Without it, a
     * boundary abandoned in the middle of a modulation would leave the track the listener keeps
     * hearing a semitone or two off its own key <em>for the rest of the song</em> — a far worse
     * outcome than the blend being cut short, and the one thing this feature must never be able
     * to do.
     */
    private synchronized void restoreOutgoingPitch(String why) {
        MediaPlayer out = glideOutPlayer;
        boolean touched = glideTouchedOut;
        boolean current = out != null && out == player;
        stopGlide();
        if (out == null) return;
        if (!current) {
            // The deck the ladder bent is no longer the audible one (the backend has replaced
            // it with another source, or a promotion made the incoming player THE player), so
            // its pitch has already gone with it and there is nothing to write.
            Logger.info("MediaPlayer: {} — the deck the key blend had bent is no longer the"
                    + " audible one, so its pitch went with it", why);
            return;
        }
        if (!touched) {
            Logger.info("MediaPlayer: {} — the key blend had not written to the audible deck, so"
                    + " there is nothing to put back", why);
            return;
        }
        boolean ok = writeParams(out, "audible", "key-glide-restore", 1d, 1d) >= 0L;
        Logger.info("MediaPlayer: {} — the audible deck's own pitch is back ({})", why,
                ok ? "one write" : "the platform refused the write, and the deck keeps the pitch"
                        + " the last step gave it");
    }

    private static double log2(double v) {
        return v > 0d ? Math.log(v) / Math.log(2d) : 0d;
    }

    private static String loc(double v) {
        return String.format(java.util.Locale.US, "%+.2f", v);
    }

    /**
     * The rule's own check: read where the incoming track actually is, and put the pitch
     * back the moment that clock reaches the deadline. One write, no ramp — see the field's
     * note for why a step is affordable and {@link #PROMOTION_RESTORE_MS} for what the
     * sixty-call glide cost.
     *
     * <p>The poll is what makes the position the deciding number (10 Hz, so the step lands
     * up to one tick late — measured 13-72 ms on the reference device, well inside the
     * rule's two-second margin). Between the start and the deadline nothing is written at
     * all: the transposition is simply held, which is also the harmonically better answer,
     * since the two tracks are still both audible for most of that time.
     */
    private void pitchBackCheck(long generation) {
        boolean finished = false;
        long at;
        synchronized (this) {
            if (!pitchBackRunning || generation != pitchBackGeneration) return;
            MediaPlayer mp = pitchBackPlayer;
            if (mp == null) {
                pitchBackRunning = false;
                return;
            }
            at = positionOf(mp);
            if (at < 0L || at >= pitchIdentityAtFileMs) {
                // Either there is no clock to schedule against, or the deadline has arrived:
                // both end at the track's own pitch, which is the only direction the rule
                // allows.
                pitchBackRunning = false;
                boolean refused = writeParams(mp, "incoming", "pitch-return",
                        pitchBackSpeed, 1d) < 0L;
                if (refused) {
                    pitchBackRunning = true;          // try again on the next tick
                    Logger.warn("MediaPlayer: could not put the transposition back (the rule's"
                            + " deadline is {}ms of the file, the player is at {}ms); retrying",
                            pitchIdentityAtFileMs, at);
                } else {
                    pitchBackDoneAtMs = at;
                    finished = true;
                }
            }
        }
        if (finished) {
            Logger.info("MediaPlayer: the incoming track's pitch is its own again — its own clock"
                            + " says {}ms, the deadline the rule set was {}ms of its file ({}ms"
                            + " of margin before its vocals, which is what the controller"
                            + " measured the blend against); one write, not {}",
                    at, pitchIdentityAtFileMs, MixNaturaliser.VOCAL_PITCH_MARGIN_MS,
                    IncomingMix.RESTORE_MS / RESTORE_TICK_MS);
            return;
        }
        try {
            rampHandler.postDelayed(() -> pitchBackCheck(generation), RESTORE_TICK_MS);
        } catch (Throwable ignored) { }
    }

    /**
     * The promotion's answer to the rule: the pitch is the track's own from here on.
     *
     * <p>Returns the position the schedule had to be cut short at, or {@code -1} when it had
     * already been met (the normal case — the controller only schedules a transposition when
     * the whole thing fits inside the blend, so it is over before the ramp is). A
     * non-negative answer is a warning: the schedule was wrong somewhere, and the pitch is
     * set to the track's own immediately rather than left transposed, because a transposed
     * vocal must never be heard.
     */
    private synchronized long endPitchBackAtPromotion() {
        if (glideRunning) {
            // The ladder's last step is planned at or before the rule's deadline, and the
            // deadline is inside the blend, so this is an anomaly rather than a case: a step was
            // missed (a refused write, a clock that never advanced, or a ramp shorter than the
            // plan). The incoming deck — which IS the audible one from this line on — goes to
            // the track's own pitch immediately, and the outgoing deck is already released (the
            // promotion released it a few lines above), so its last step's pitch goes with it.
            MediaPlayer mp = glideInPlayer;
            KeyGlide glide = activeGlide;
            long at = positionOf(mp);
            int missed = glide != null ? glide.steps() - glideIndex : 0;
            long plannedLast = glide != null ? glide.atFileMs(glide.steps() - 1) : -1L;
            stopGlide();
            pitchBackRunning = false;
            pitchBackGeneration++;
            if (mp != null) {
                writeParams(mp, "incoming", "pitch-return-at-promotion", pitchBackSpeed, 1d);
            }
            pitchBackDoneAtMs = at;
            Logger.warn("MediaPlayer: the key blend still had {} of its steps to take when the"
                            + " promotion happened (the incoming's own clock said {}ms; the last"
                            + " step was planned for {}ms of its file) — the promoted track is at"
                            + " its own pitch from here, immediately, rather than left" 
                            + " transposed, and the outgoing deck went with the pitch its last"
                            + " step gave it",
                    missed, at, plannedLast);
            return at >= 0L ? at : 0L;
        }
        if (!pitchBackRunning) return -1L;
        pitchBackRunning = false;
        pitchBackGeneration++;
        MediaPlayer mp = pitchBackPlayer;
        long at = positionOf(mp);
        if (mp != null) {
            writeParams(mp, "incoming", "pitch-return-at-promotion", pitchBackSpeed, 1d);
        }
        return at;
    }

    /** A player's own play head in ms, or -1 when it cannot be read. */
    private static long positionOf(MediaPlayer mp) {
        if (mp == null) return -1L;
        try {
            return Math.max(0L, mp.getCurrentPosition());
        } catch (Throwable e) {
            return -1L;
        }
    }

    /** One gain sample of the running ramp. Applies the very first sample
     *  immediately (so the swap never starts from a stale volume), then schedules
     *  itself on the main looper. */
    private void rampStep(long generation) {
        boolean promoted = false;
        boolean abandoned = false;
        boolean stop = false;
        synchronized (this) {
            if (!crossfading || generation != rampGeneration) return;
            MediaPlayer out = player;
            MediaPlayer in = incomingPlayer;
            if (out == null || in == null) {
                // Released from under the ramp (release() / a new play()): there is
                // nothing left to fade, and the caller has already moved on.
                cancelRamp();
                stop = true;
            } else {
                long elapsed = System.nanoTime() - rampStartNs;
                float t = elapsed >= rampDurationNs
                        ? 1f : (float) elapsed / (float) rampDurationNs;
                float base = baseGain();
                // The curve decides how the two levels cross: LINEAR is the plain
                // 1-t / t pair, EQUAL_POWER the cos/sin pair whose sum of squares
                // stays at one (no dip in the middle of the overlap).
                //
                // ⚠️ Asked WITH the ramp's length. Every shape but FUSION is written in terms
                // of `t` alone and answers the same either way; FUSION's whole hand-over is a
                // fixed FadeCurve.JUNCTION_XFADE_MS fade at the TOP of the ramp — linear and
                // equal-gain, because the two decks carry the same material across it — whose
                // share of a window this length is only knowable from the length itself (see
                // FadeCurve.outGain(float, long)). Its outgoing track is at unity for that first
                // window and at exactly zero from the end of it on, held there for the rest of the
                // ramp (rampMs − JUNCTION_XFADE_MS), so the release assertion below is looking at
                // a deck that has been silent for seconds by the time the promotion tears its
                // player down — and at a fade that runs at the top of the ramp rather than across
                // it, which is what keeps the outgoing track from being stopped on the spot.
                long rampMs = rampDurationNs / 1000000L;
                float outGain = base * clampGain(rampCurve.outGain(t, rampMs));
                float inGain = base * clampGain(rampCurve.inGain(t, rampMs));
                // The outgoing track's level of its own (the curve's value, without the user's
                // volume or a duck folded in): this is the number the release assertion and the
                // tail trace below are about, and the only one comparable across boundaries.
                float normalizedOut = outGain / Math.max(1e-6f, base);
                try {
                    out.setVolume(outGain, outGain);
                    in.setVolume(inGain, inGain);
                } catch (Throwable e) {
                    // Reported, not swallowed: a refused write leaves the outgoing track at the
                    // value its last accepted write set, which is exactly the state the release
                    // assertion below cannot see (it holds the value we ASKED for). If this ever
                    // appears next to a release that is not silent, this is the reason.
                    Logger.warn("MediaPlayer: the blend's gain write was refused ({}); the outgoing"
                                    + " track stays at the {} of unity its last accepted write set,"
                                    + " not the {} this tick asked for (t={})",
                            e.toString(), fmt(lastOutGain), fmt(normalizedOut), fmt((double) t));
                }
                // Recorded for the key blend's per-step log line: "the audible deck's own gain
                // when its biggest shift landed". Nothing here reads it back — MediaPlayer has no
                // volume getter, and the gain is ours to know.
                lastOutGain = normalizedOut;
                // ... and for the release assertion, which has to answer "was the outgoing track
                // audible when the promotion tore its player down?" from a log.
                rampOutGain = normalizedOut;
                rampOutGainNs = System.nanoTime();
                if (normalizedOut > 0f) {
                    rampOutLastAudibleGain = normalizedOut;
                    rampOutLastAudibleNs = rampOutGainNs;
                }
                traceOutgoingGain(normalizedOut, t, elapsed);
                // The low end changes hands once, on the beat the controller picked
                // (a beat of both tracks, since the incoming is running at the
                // outgoing's tempo). A level write, so it costs nothing to do it here
                // rather than on its own clock.
                //
                // ⚠️ Round 26: it is a FADE across {@link #BASS_SWAP_FADE_MS}, not one write.
                // The instant is the same beat it always was; what changed is that the outgoing
                // track's bottom now leaves over two seconds instead of in a single sample.
                // The listener's report is the reason and it is about exactly this: 「前一首歌
                // 音量骤降」 — with the swap a step, the song they are still following lost its
                // whole foundation between one 32ms tick and the next, which is heard as the
                // track being cut down rather than as a hand-over. The equalizer is an effect
                // layer, so writing it every tick costs one small IPC and no audio state.
                if (bassSwapAtMs >= 0L && !bassSwapped) {
                    long swapNs = bassSwapAtMs * 1000000L;
                    if (elapsed >= swapNs) {
                        bassSwapLevel(Math.min(1d, (elapsed - swapNs)
                                / (double) (BASS_SWAP_FADE_MS * 1000000L)));
                    }
                }
                if (t >= 1f) {
                    if (promoteIncoming(out, in)) {
                        promoted = true;
                    } else {
                        // Refused BEFORE anything was released: drop the incoming
                        // player, put the outgoing track back at its normal level
                        // (the ramp's last sample left it at silence) and tell the
                        // caller, which reaches the boundary through the ordinary
                        // switch. This is the only way a ramp can end — either the
                        // swap, or the outgoing track still playing.
                        cancelIncoming();
                        abandoned = true;
                    }
                }
            }
        }
        if (promoted) {
            // Outside the lock: the controller republishes the now-playing track
            // and immediately calls back into this backend.
            fire(onCrossfadeComplete);
            return;
        }
        if (abandoned) {
            fire(onCrossfadeAbandoned);
            return;
        }
        if (stop) return;
        try {
            rampHandler.postDelayed(() -> rampStep(generation), RAMP_TICK_MS);
        } catch (Throwable ignored) { }
    }

    /** The outgoing track's own level, once per 6 dB of its exit — the trace that answers
     *  "is the tail a fade or a cliff" from a log instead of from a memory of the sound.
     *
     *  <p>The complaint this exists for is 「上一首歌戛然而止」 — the previous song stopping
     *  abruptly during the blend — and it is a statement about the SHAPE of the last seconds:
     *  a shape that holds the outgoing track at its own level and then falls out of hearing
     *  inside a second reads as a cut, however smooth the arithmetic. One line per 6 dB
     *  (rather than one per 32 ms tick) is enough to see it and cheap enough to leave on: a
     *  15 s blend's exit is about nine lines, and the gap between them is printed so the rate
     *  is readable without a spreadsheet.
     *
     *  <p>Called with the lock held, from the ramp, with the gain the platform was just asked
     *  for — the same value the release assertion reads.
     */
    private void traceOutgoingGain(float gain, float t, long elapsedNs) {
        // 1 = first line below -6 dB, 2 = below -12 dB, ... and Integer.MAX_VALUE for the
        // exact zero of the silent tail, which is a one-shot like the rest.
        int step = gain <= 0f ? Integer.MAX_VALUE : (int) Math.floor(-FadeCurve.gainDb(gain) / 6d);
        if (step <= rampOutTraceStep) return;
        long sinceLast = rampOutTraceNs > 0L ? (elapsedNs - rampOutTraceNs) / 1000000L : -1L;
        rampOutTraceStep = step;
        rampOutTraceNs = elapsedNs;
        if (gain <= 0f) {
            Logger.info("MediaPlayer: the outgoing track is at silence (the curve asks it for"
                            + " exactly zero from here to the end of the ramp) at t={} of the ramp"
                            + " ({}ms of {}ms); the incoming is at {} of unity — the promotion may"
                            + " release the outgoing player from this instant on without a sound",
                    fmt((double) t), elapsedNs / 1000000L, rampDurationNs / 1000000L,
                    fmt(rampCurve.inGain(t, rampDurationNs / 1000000L)));
            return;
        }
        Logger.info("MediaPlayer: the outgoing track is at {} dB ({} of unity) at t={} of the"
                        + " ramp ({}ms of {}ms, {}); the incoming is at {} of unity — {}",
                fmt(FadeCurve.gainDb(gain)), fmt((double) gain), fmt((double) t),
                elapsedNs / 1000000L, rampDurationNs / 1000000L,
                sinceLast >= 0L ? sinceLast + "ms after the previous step" : "the first step",
                fmt(rampCurve.inGain(t, rampDurationNs / 1000000L)), rampCurve);
    }

    /**
     * The promotion's release assertion, logged before the outgoing player is torn down.
     *
     * <p>What it is for: a released {@code MediaPlayer} drops whatever it still had buffered,
     * so the gain the outgoing track was at when the release happens IS the sound of the
     * release — and "the ramp faded it out, so it must be silent" is precisely the assumption
     * behind the reported 「上一首歌戛然而止」. Two numbers make it a fact rather than an
     * assumption: the gain the last tick asked for (and how long before the release it was
     * written), and how long the outgoing track has been at exactly zero. The levels come from
     * the curve, which is where the promise is made: {@link FadeCurve} guarantees the DJ shape
     * is at exactly zero for the last {@code OUT_SILENT_TAIL} of the ramp, so a tick-timing
     * accident cannot release a player that is still audible.
     *
     * <p>A release above the floor is not silently tolerated: it is a WARN that names the
     * defect, because that is the only way a future regression in the curve — or in whatever
     * ends the blend — is visible before it is heard.
     */
    private void logOutgoingRelease() {
        double db = FadeCurve.gainDb((float) rampOutGain);
        long sinceWrite = (System.nanoTime() - rampOutGainNs) / 1000000L;
        long sinceAudible = rampOutLastAudibleNs > 0L
                ? (System.nanoTime() - rampOutLastAudibleNs) / 1000000L : 0L;
        long silentTailMs = Math.round(
                rampCurve.outSilentTail(rampDurationNs / 1000000L) * rampDurationNs / 1000000d);
        if (db <= FadeCurve.INAUDIBLE_DB) {
            Logger.info("MediaPlayer: releasing the outgoing player at silence — the ramp's last"
                            + " write for it was {} of unity ({} dB; the floor a release may happen"
                            + " at is {} dB), written {}ms before the release. The last write above"
                            + " that floor was {} of unity ({} dB), {}ms before it, and this curve"
                            + " ({}) holds the last {}ms of the ramp — {} of it — at exactly zero,"
                            + " so the promotion cannot land on a player that is still being heard",
                    fmt(rampOutGain), fmt(db), fmt((double) FadeCurve.INAUDIBLE_DB), sinceWrite,
                    fmt(rampOutLastAudibleGain),
                    fmt(FadeCurve.gainDb((float) rampOutLastAudibleGain)), sinceAudible,
                    rampCurve, silentTailMs,
                    Math.round(rampCurve.outSilentTail(rampDurationNs / 1000000L) * 100f)
                            + " percent");
            return;
        }
        Logger.warn("MediaPlayer: RELEASING THE OUTGOING PLAYER WHILE IT IS STILL AUDIBLE — the"
                        + " ramp's last write for it was {} of unity ({} dB; the floor a release may"
                        + " happen at is {} dB), written {}ms before the release, {}ms into a {}ms"
                        + " {} ramp. Releasing a player drops what it still had buffered, so this is"
                        + " the 「上一首歌戛然而止」 the curve's exit exists to prevent: the outgoing"
                        + " track is being cut off at an audible level",
                fmt(rampOutGain), fmt(db), fmt((double) FadeCurve.INAUDIBLE_DB), sinceWrite,
                Math.round(rampProgressNow() * rampDurationNs / 1000000d), rampDurationNs / 1000000L,
                rampCurve);
    }

    /** How far into its own ramp the last gain write was, as {@code t} — for the one log line
     *  a release asserts silence. Derived from the same two fields the ramp uses. */
    private double rampProgressNow() {
        long elapsed = System.nanoTime() - rampStartNs;
        return elapsed >= rampDurationNs ? 1d : (double) elapsed / rampDurationNs;
    }

    /** The ramp finished: the incoming player becomes THE player, and the outgoing
     *  one goes away. Called with the lock held, and every field it leaves behind has
     *  to mean the same thing the ordinary prepare path leaves behind, or the next
     *  {@code position()}/{@code duration()}/listener call describes the track that
     *  just went away.
     *
     *  <p>Two things about the ORDER are load bearing:
     *
     *  <ol>
     *  <li>The promoted instance is put in the state {@link #play} leaves behind —
     *  its own listeners, the shared flags pointing at it, nothing pending — and only
     *  then is the OUTGOING player torn down. Nothing here touches a shared flag
     *  after the swap, because after the swap those flags describe the audible
     *  track.</li>
     *  <li>The video surface is handed over LAST, and only to a source that has a
     *  picture, once the outgoing player has already released it. A surface belongs
     *  to one player per media service, and
     *  {@code MediaPlayerService::Client::setVideoSurfaceTexture} answers a failed
     *  connect by RESETTING the player it was asked to attach — attaching the
     *  surface the outgoing player still holds therefore destroys the player that
     *  just took over, leaving a silent player that can never complete and a queue
     *  that never advances. The ordinary path has the same rule: play() releases the
     *  old player before onPrepared attaches the surface to the new one.</li>
     *  </ol>
     *
     *  @return true when the incoming player took over. False means it is not in a
     *          state that can be promoted — in which case nothing at all has been
     *          released and the caller keeps the outgoing track, so a promotion can
     *          never end with nothing playing. */
    private boolean promoteIncoming(MediaPlayer out, MediaPlayer in) {
        if (out == null || in == null) return false;
        // A player the platform has already reset (or one whose start() was refused)
        // is not playing and cannot be promoted: it would be silent, would never fire
        // a completion and could never advance the queue. Checked before the outgoing
        // player is touched, so the currently audible track is still there to keep
        // playing.
        boolean rolling;
        try {
            rolling = in.isPlaying();
        } catch (Throwable e) {
            rolling = false;
        }
        if (!rolling) {
            Logger.warn("MediaPlayer: incoming player is not playing at the end of the ramp,"
                    + " keeping the outgoing track");
            return false;
        }
        // Read before the shared state is rewritten below, and before the player is
        // released: together with the ramp's length this is how much of the incoming
        // track the overlap has already played out loud.
        long startedAtMs = incomingSeekMs;
        crossfading = false;
        player = in;
        source = incomingSource;
        if (activeAutoMix != null) activeAutoMix.delete();
        activeAutoMix = incomingAutoMix;
        incomingAutoMix = null;
        prepared = true;
        // The incoming player was seeked before it started; the play head now
        // belongs to it and nothing is pending for it.
        pendingSeekMs = 0L;
        seeking = false;
        wantPlay = true;
        incomingPlayer = null;
        incomingSource = null;
        incomingPrepared = false;
        incomingSeekMs = 0L;
        incomingRolling = false;
        incomingStartMuted = true;
        // Until here the promoted player carried the incoming-source listeners, and
        // its error one only ever meant "the transition died". It IS the player now,
        // so it has to be recognised by the same guards play() installs, or a
        // completion would not advance the queue and an error would never reach the
        // controller's recovery.
        in.setOnPreparedListener(this::onPrepared);
        in.setOnErrorListener(this::onPlayerError);
        in.setOnCompletionListener(p -> onCompleted(p));
        in.setOnSeekCompleteListener(this::onMainSeekComplete);
        // The promoted player's live position, logged at the swap itself: this is the
        // number the incoming track has already played (silently, then ramped up) and
        // exactly where it has to keep going. A value near the ramp's start instead of
        // its end means the parked player never actually advanced.
        long promotedAt;
        try {
            promotedAt = in.getCurrentPosition();
        } catch (Throwable e) {
            promotedAt = -1L;
        }
        // Two numbers, because what they measure is what the listener has been given of
        // the next track: the promoted player's own clock, and the overlap's count (the
        // offset it was started at plus the ramp). The player is started BY the ramp and
        // is never seeked afterwards, so both describe the same span, and the difference
        // between them is the player's own start latency — measured on the device at
        // 123-362ms across local-cache sources. The player's clock is the honest one
        // (that is the audio it emitted); the overlap's count is the upper bound the
        // controller's reported position is taken from.
        //
        // The "nothing skipped" half of the invariant is the same pair of facts: because
        // nothing starts the incoming before the ramp, `offset .. offset + ramp` is all
        // of the next track that has ever been audible, and the listener got it from its
        // very start — nothing was skipped before the ramp and nothing is replayed after
        // it, because nothing on this path ever seeks.
        long rampPlayedMs = Math.max(0L, rampDurationNs / 1000000L);
        long overlapPlayedMs = startedAtMs + rampPlayedMs;
        Logger.info("MediaPlayer: transition promoted, releasing the outgoing player"
                        + " (the incoming is at {}ms; the overlap heard {}ms = its start {}ms +"
                        + " the {}ms ramp{}{})",
                promotedAt, overlapPlayedMs, startedAtMs, rampPlayedMs,
                appliedMix != null ? " " + appliedMix : "",
                promotedAt >= 0L && promotedAt + 200L < overlapPlayedMs
                        ? " -- its own clock is " + (overlapPlayedMs - promotedAt)
                        + "ms behind the ramp's wall clock (start latency, not a skipped part:"
                        + " nothing is seeked here)"
                        : "");
        // The monitor's "audible" arm has to move with the promotion: the outgoing instance
        // is released a line below, and a released player's position reads 0 — which would
        // look exactly like a stall. The "incoming" arm keeps watching the instance that
        // just became THE player.
        clockOut = null;
        // ... and THEN the outgoing one, with the numbers that say it was not making a sound
        // when it went. Logged immediately before the release, on the same lock, so the two
        // are the same instant as far as a log reader is concerned.
        logOutgoingRelease();
        // ONLY the outgoing player, and only its own listeners: every field above
        // already belongs to the promoted instance.
        releaseOne(out);
        // The equalizers go with the players they were attached to. The promoted
        // track's one is flat by now (the hand-over restored its low end) and the
        // outgoing track is gone, so this changes nothing that can be heard — it is
        // here so the effect engines and the sessions behind them are not leaked.
        releaseEqualizers();
        // Promotion does not fire onPrepared again: bind the EQ to the audible session.
        applyListenerEq();
        // And so does the stretch: the promoted track has been running at A's tempo and
        // in A's key since the ramp began, which was right while both were audible and
        // is artefact now that only one is. Eased back over six seconds, immediately on
        // any interruption (see PROMOTION_RESTORE_MS).
        //
        // The pitch half is already done — the rule had it back at the track's own before
        // the ramp ended (see startPitchBack) — so what this ramps is the tempo, from
        // x{speed} to the track's own, and the pitch is pinned at its own from here.
        if (appliedMix != null && (appliedMix.hasTempo() || appliedMix.semitones() != 0)) {
            long cutShortAt = endPitchBackAtPromotion();
            if (cutShortAt >= 0L) {
                Logger.warn("MediaPlayer: the transposition was still on when the promotion"
                                + " happened (the incoming's own clock said {}ms; the return was"
                                + " scheduled to finish by {}ms of its file) — it is the track's"
                                + " own pitch from here, immediately, rather than left on the one"
                                + " thing the rule forbids",
                        cutShortAt, pitchIdentityAtFileMs);
            } else if (pitchIdentityAtFileMs >= 0L) {
                Logger.info("MediaPlayer: the transposition was already its own pitch at the"
                                + " promotion (reached identity at {}ms of the track's own file,"
                                + " deadline {}ms), so the promotion's single write is the"
                                + " tempo's alone",
                        pitchBackDoneAtMs, pitchIdentityAtFileMs);
            }
            restoreTempoNow(appliedMix.speed());
        }
        // Keep watching both clocks for the window a parameter write can still land in (the
        // tempo write above, and the effects coming off the promoted player's session).
        extendClockMonitor();
        appliedMix = null;
        requestedMix = IncomingMix.IDENTITY;
        bassSwapAtMs = -1L;
        // The picture, if there is one, belongs to whatever is audible NOW — and only
        // a source that has a picture needs it. The queue is free by the time this
        // runs because the outgoing player released it a line earlier; an audio-only
        // promotion never touches the surface at all.
        if (isBiliCdn(source)) applyVideoSurface();
        // Settle exactly on the user's level: the ramp's last sample was already
        // there, and this re-applies the duck factor if focus was lost mid-ramp.
        applyVolume();
        return true;
    }

    /** Abandon the prepared incoming player and put the audible one back at its
     *  normal level — the caller is dropping the overlap, not switching tracks.
     *  Safe to call when no transition exists. */
    public synchronized void cancelIncoming() {
        cancelRamp();
        releaseIncoming();
        applyVolume();
    }

    /** Stop a running ramp without touching either player. A ramp sample already
     *  queued on the main looper finds itself out of generation and does nothing. */
    private void cancelRamp() {
        rampGeneration++;
        crossfading = false;
        autoMixHandoff = null;
    }

    private void releaseIncoming() {
        autoMixArmGeneration++;
        // ⚠️ First, before anything is released: if the key blend had already bent the deck the
        // listener KEEPS hearing, that pitch has to come back (see restoreOutgoingPitch). This
        // method is every path that drops an overlap without promoting it — a pause, a seek, a
        // focus loss, an incoming error, a queue change — and the audible deck is the one player
        // none of them is allowed to leave out of tune.
        restoreOutgoingPitch("the overlap was dropped before the key blend finished");
        // How far it had got, before the player is gone: a rolling incoming that is
        // dropped mid-overlap is the case where the next track has already been partly
        // heard, and the caller has to resume it from there rather than from its
        // beginning. A parked one that never started contributes nothing. The value is
        // the player's own play head, i.e. already in the track's own timeline — the
        // tempo it was stretched to does not enter it (a stretched player's position
        // advances faster than the wall clock, which is exactly what makes it the right
        // resume point).
        if (incomingPlayer != null && incomingRolling
                && (incomingAutoMix == null || autoMixAudible)) {
            try {
                droppedIncomingMs = Math.max(0L,
                        Math.round(incomingPlayer.getCurrentPosition()));
            } catch (Throwable ignored) { }
        }
        releaseOne(incomingPlayer);
        if (incomingAutoMix != null) {
            if (droppedIncomingMs >= 0 && incomingRolling)
                droppedIncomingMs = droppedIncomingMs >= incomingAutoMix.leadInMs
                        ? incomingAutoMix.songPosition(droppedIncomingMs) : -1L;
            incomingAutoMix.delete();
            incomingAutoMix = null;
        }
        incomingPlayer = null;
        // No listener is hearing this boundary any more: the monitor's whole reason to exist
        // (attributing a gap in the overlap to an instant inside it) is over with the overlap,
        // so it stops here rather than sampling a released player.
        stopClockMonitor("the boundary was dropped");
        incomingSource = null;
        incomingPrepared = false;
        incomingSeekMs = 0L;
        incomingRolling = false;
        incomingStartMuted = true;
        // Both equalizers, not just the incoming player's: the hand-over may already
        // have taken the audible track's low end away, and a mix that is dropped
        // half way through must not leave the track the listener is still hearing
        // with its bass cut out. Releasing an effect restores the signal it was
        // shaping, so this IS the restore.
        releaseEqualizers();
        appliedMix = null;
        requestedMix = IncomingMix.IDENTITY;
        bassSwapAtMs = -1L;
        // The scheduled pitch return dies with the player it was written on: a step still
        // queued on the main looper finds itself out of generation and does nothing.
        pitchIdentityAtFileMs = -1L;
        pitchBackRunning = false;
        pitchBackPlayer = null;
        pitchBackGeneration++;
    }

    @Override
    public synchronized long incomingPosition() {
        if (incomingPlayer != null && incomingRolling) {
            try {
                long position = Math.max(0L, incomingPlayer.getCurrentPosition());
                if (incomingAutoMix != null && (!autoMixAudible || position < incomingAutoMix.leadInMs))
                    return -1L;
                return incomingAutoMix == null ? position : incomingAutoMix.songPosition(position);
            } catch (Throwable ignored) { }
        }
        return -1L;
    }

    @Override
    public synchronized long incomingDuration() {
        // ⚠️ The one thing in the whole transition the controller cannot check for itself: what file
        // the incoming deck was actually handed (see AudioBackend.incomingDuration). Readable only
        // on a prepared player, and only this player can be asked — the platform's own number for
        // the source it opened. -1 (not 0) when there is nothing to ask, so a caller can tell "this
        // platform cannot measure" from "this file measures as empty": only the second is evidence
        // about the file.
        if (incomingPlayer == null || !incomingPrepared) return -1L;
        try {
            long ms = incomingPlayer.getDuration();
            return ms > 0L ? ms : -1L;
        } catch (Throwable e) {
            // A platform that throws here has refused to answer; that is not evidence about the
            // file either, and the caller's guard is documented to run only on a real number.
            Logger.info("MediaPlayer: the incoming file's length could not be read ({}), so the"
                    + " edit-identity guard cannot run for this boundary", e.toString());
            return -1L;
        }
    }

    @Override
    public synchronized long droppedIncomingPosition() {
        return droppedIncomingMs;
    }

    /** A transition that cannot be carried through: drop the incoming player,
     *  restore the audible one, and tell the caller (unless it is the caller that
     *  decided this) so it can fall back to the ordinary track switch. */
    private void abortCrossfade(boolean notify) {
        boolean wasTransitioning = crossfading || incomingPlayer != null;
        cancelIncoming();
        if (notify && wasTransitioning) fire(onCrossfadeAbandoned);
    }

    /** Called once the promoted track is the audible one (on the main thread), so
     *  the controller can republish title/artist/cover/queue slot as if it had
     *  started that track itself. */
    public synchronized void setOnCrossfadeComplete(Runnable callback) {
        onCrossfadeComplete = callback;
    }

    /** Called when a transition the caller armed could not continue — the incoming
     *  player errored, or playback was paused/seeked/focus-lost mid-overlap. The
     *  audible player is back at its normal level and the caller must fall back. */
    public synchronized void setOnCrossfadeAbandoned(Runnable callback) {
        onCrossfadeAbandoned = callback;
    }

    // --- Audio focus ------------------------------------------------------

    private void requestFocus() {
        if (hasFocus || audioManager == null) return;
        AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(this::onFocusChange)
                // Ducking is not what qplayer does, and this flag is the whole of it:
                // with `false` the framework answers another app's "may duck" request
                // (what a video player asks for so it can keep its own audio going)
                // by applying its OWN volume shaper to our MediaPlayer — measured on
                // the device: 1.0 -> 0.2, and NO focus callback to us at all. The
                // track kept "playing" under the other app, the media session still
                // said PLAYING, and it never came back to full when the other app left.
                // With `true` the same request arrives as a loss we can act on, so the
                // app pauses (and resumes on the way back) exactly like a transient
                // one, and nothing of ours plays under anything else.
                .setWillPauseWhenDucked(true)
                .build();
        focusRequest = req;
        hasFocus = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!hasFocus) {
            // Only an app holding a transient-exclusive focus can refuse this (a call,
            // a recorder). Playing without focus is what makes a later app's request
            // invisible to us, so it is named here rather than started silently.
            Logger.warn("MediaPlayer: the audio focus request was refused (another app holds it"
                    + " exclusively); playback will wait for the user");
        }
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
        focusRequest = null;
        hasFocus = false;
        ducked = false;
        resumeWindow.cancel();
        pausedByFocus = false;
    }

    /** A permanent loss takes the request with it: Android sends no further focus
     *  callback until the app asks again, so the app would keep playing (and the next
     *  app to take focus would never pause it — the "it stopped reacting after I
     *  resumed it by hand" report). The request object is kept but no longer owned;
     *  the next play()/resume() registers a fresh one. */
    private void forgetFocusAfterPermanentLoss() {
        focusRequest = null;
        hasFocus = false;
    }

    /** See {@link AudioBackend#pausedByAudioFocusLoss()}. */
    @Override
    public synchronized boolean pausedByAudioFocusLoss() {
        return pausedByFocus;
    }

    /** See {@link AudioBackend#cancelAutoResume()}. */
    @Override
    public synchronized void cancelAutoResume() {
        if (resumeWindow.isPending()) {
            Logger.info("MediaPlayer: the user's own pause cancels the pending focus auto-resume");
        }
        resumeWindow.cancel();
        pausedByFocus = false;
    }

    /**
     * One focus loss, whatever the hint: stop everything of ours that is audible,
     * remember whether a regain may resume, and drop the overlap. The three hints
     * differ only in the come-back rule (see the field comment above) — the work is
     * shared so none of them can be the one that forgot to pause.
     *
     * @param hint                 the case, for the log line
     * @param resumeWhenFocusComes true when this pause is ours and focus should bring
     *                             the track back
     * @param keepFocus            false for a permanent loss, where the request is gone
     */
    private void onFocusLoss(String hint, boolean resumeWhenFocusComes, boolean keepFocus) {
        // Read before anything is torn down: these two decide whether the app has to
        // republish a paused state at all.
        boolean wasAudible = player != null && prepared && player.isPlaying();
        boolean wasOverlapping = crossfading || incomingPlayer != null;
        // Capture playback intent before clearing it. Idle/manual pauses cannot
        // arm a resume, and another loss during this interruption cannot reset its clock.
        resumeWindow.onLoss(SystemClock.elapsedRealtime(),
                player != null && (wasAudible || wantPlay), resumeWhenFocusComes);
        Logger.info("MediaPlayer: audio focus {} at {}ms — pausing (audible={}, overlapping={});"
                        + " {}{}",
                hint, position(), wasAudible, wasOverlapping,
                resumeWindow.isPending()
                        ? "auto-resume is armed for AUDIOFOCUS_GAIN within five minutes"
                        : "NO auto-resume",
                keepFocus ? ""
                        : " and the focus request is forgotten, so the next play/resume"
                                + " asks for it again");
        // The play intent goes before the overlap does: dropping the overlap can reach
        // the controller's ordinary track switch (an outgoing track that already ended
        // during the ramp advances the queue), and that switch must find the app
        // already paused — otherwise it starts the next track and takes audio focus
        // back from the app that just asked for it.
        wantPlay = false;
        pausedByFocus = true;
        if (!keepFocus) forgetFocusAfterPermanentLoss();
        // Audio focus is with another app, so nothing here may keep playing — including
        // the incoming player of a running overlap.
        abortCrossfade(true);
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
        }
        if (wasAudible || wasOverlapping) fire(onPaused);
    }

    private synchronized void onFocusChange(int change) {
        // Focus is the one external event that can stop playback without the user
        // touching anything mid-transition (it also drops the overlap, see below), so
        // every case is named in the log — a bare "it paused by itself" report is
        // otherwise undiagnosable.
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // Another app took the focus for good (its own video or music).
                onFocusLoss("LOST (permanent: another app took it)", RESUME_AFTER_PERMANENT_LOSS,
                        false);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Call / brief interruption: pause and come back on regain.
                onFocusLoss("LOST (transient)", true, true);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // We are allowed to duck; we pause instead (documented above), so two
                // players never mix, and come back the same way a transient loss does.
                onFocusLoss("LOST (transient, may duck — qplayer pauses instead of ducking)",
                        true, true);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                // Focus is ours again, so playback may start normally from here on.
                pausedByFocus = false;
                hasFocus = true;
                boolean hadPendingResume = resumeWindow.isPending();
                boolean shouldResume = resumeWindow.onGain(SystemClock.elapsedRealtime());
                Logger.info("MediaPlayer: audio focus gained at {}ms (ducked={}, autoResume={})",
                        position(), ducked, shouldResume);
                if (ducked) {
                    ducked = false;
                    applyVolume();
                }
                if (shouldResume) {
                    if (player != null) {
                        wantPlay = true;
                        if (prepared) {
                            player.start();
                            fire(onResumed);
                        }
                    }
                } else if (hadPendingResume) {
                    Logger.info("MediaPlayer: interruption exceeded five minutes; waiting for manual play");
                } else {
                    Logger.info("MediaPlayer: focus regained, but nothing is resumed: this pause"
                            + " was not made for a focus loss (a pause by the user stays a pause)");
                }
                break;
            default:
                break;
        }
    }

    private static void fire(Runnable r) {
        if (r != null) r.run();
    }

    private void releasePlayer() {
        seeking = false;
        if (activeAutoMix != null) { activeAutoMix.delete(); activeAutoMix = null; }
        // A ramp in flight belongs to the source being replaced, and so does the
        // player it is ramping toward.
        cancelRamp();
        // And so does the tempo/pitch of the track being replaced: whatever the promoted
        // track had already been eased back to is irrelevant now, and the player it is
        // running on is about to be released (a restore step posted to the main looper
        // must not touch the *next* player, which is why the generation is bumped here
        // as well).
        finishRestoreNow();
        releaseListenerEq();
        eqStatus = eqEnabled ? "均衡器：已启用，等待播放" : "均衡器：未启用";
        releaseOne(player);
        player = null;
        videoSurfacePlayer = null;
        appliedVideoSurface = null;
        prepared = false;
        releaseIncoming();
        // The next stream reports its own picture size; until then the UI must not
        // keep framing the box to the previous video's shape.
        videoWidth = 0;
        videoHeight = 0;
        VideoSizeListener sizeListener = videoSizeListener;
        if (sizeListener != null) sizeListener.onVideoSize(0, 0);
    }

    /** Detach the listeners first so callbacks queued by reset/release cannot act
     *  on a subsequently assigned player through the backend's shared fields. */
    private static void releaseOne(MediaPlayer mp) {
        if (mp == null) return;
        try {
            mp.setOnPreparedListener(null);
            mp.setOnCompletionListener(null);
            mp.setOnErrorListener(null);
            mp.reset();
            mp.release();
        } catch (Throwable ignored) {
        }
    }
}
