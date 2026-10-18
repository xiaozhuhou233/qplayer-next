package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.audio.dsp.PitchShifter;
import dev.t1m3.qplayer.audio.automix.AutoMixLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Director {
    private static final int HISTORY_LIMIT = 64;

    public static final long PREPARE_MARGIN_SECS = 180L;

    private volatile long prepareMarginSecs = PREPARE_MARGIN_SECS;

    private Long introRemainingAtTransitionStart;

    long lastHandoverUnusedFrames;

    private long transitionRenderedIntro;

    long lastHandoverDeltaFrames;

    long lastHandoverPositionDeltaFrames;

    private Long outroRemainingAtTransitionStart;

    private boolean sawRemoteNext;

    public static final long REMOTE_PREPARE_MARGIN_SECS = 90L;

    public static boolean isRemoteSource(String source) {
        if (source == null) {
            return false;
        }
        String s = source.trim().toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    public void setPrepareMarginSecs(long secs) {
        this.prepareMarginSecs = secs > 0L ? secs : PREPARE_MARGIN_SECS;
    }

    public long prepareMarginSecs() {
        long base = prepareMarginSecs;
        return isRemoteSource(queuedNextPath()) ? Math.max(base, REMOTE_PREPARE_MARGIN_SECS) : base;
    }

    static final String HEAVY_OPEN = "Open";

    static final String HEAVY_SEEK = "Seek";

    static final String HEAVY_PITCH_SHIFTER = "PitchShifter";

    static final class HeavyRecord {
        final String kind;

        final String thread;

        final String source;

        HeavyRecord(String kind, String thread, String source) {
            this.kind = kind;
            this.thread = thread;
            this.source = source;
        }

        @Override
        public String toString() {
            return "HeavyRecord{kind=" + kind + ", thread=" + thread + ", source=" + source + '}';
        }
    }

    static List<HeavyRecord> heavyProbe;

    private static void recordHeavy(String kind, String source) {
        List<HeavyRecord> log = heavyProbe;
        if (log == null) {
            return;
        }
        synchronized (log) {
            log.add(new HeavyRecord(kind, Thread.currentThread().getName(), source));
        }
    }

    private static Deck openDeckWithHeaders(
            String source, int outRate, int outChannels, HeadersLookup lookup) {
        recordHeavy(HEAVY_OPEN, source);
        return Deck.open(source, headersOf(lookup, source), outRate, outChannels);
    }

    private static void installMaterialHook(Deck deck, Transition engine, int rate) {
        if (deck == null || engine == null || rate <= 0) {
            return;
        }
        try {
            Transition inner = engine;
            if (inner instanceof VocalSeparationTransition) {
                Transition wrapped = ((VocalSeparationTransition) inner).inner();
                if (wrapped != null) {
                    inner = wrapped;
                }
            }
            if (!(inner instanceof AutoCueTransition)) {
                return;
            }
            AutoCueTransition cue = (AutoCueTransition) inner;
            WindowTicket ticket = cue.windowTicket();
            if (ticket == null || !ticket.doesAnything()) {
                return;
            }
            deck.setMaterialHook(new WindowMaterialHook(ticket, cue.installedVocalMode(), rate));
        } catch (RuntimeException e) {
        }
    }

    private static Deck openDeck(String source, int outRate, int outChannels) {
        return openDeckWithHeaders(source, outRate, outChannels, null);
    }

    private static PitchShifter idlePitchShifter(int outRate, int outChannels) {
        recordHeavy(HEAVY_PITCH_SHIFTER, "");
        return Deck.idlePitchShifter(outRate, outChannels);
    }

    private final int outRate;

    private final int outChannels;

    private long window;

    private long triggerLead = -1L;

    private long lastTransitionFrames;

    private boolean triggerReported;

    private boolean triggerMissReported;

    private int prepareRetries = 0;

    private static final int MAX_PREPARE_RETRIES = 1;

    private Transition transition;

    private final TransitionFactory factory;

    private Deck current;

    private Deck next;

    private final ArrayDeque<String> queue;

    private final List<Skipped> skipped;

    private final List<String> history;

    private long transitionPos;

    private boolean transitioning;

    private boolean prepared;

    private PreparedTransition ready;

    private PrepJob prep;

    private long generation;

    private TransitionPlan activePlan;

    private boolean spatialEnabled;

    private dev.t1m3.qplayer.audio.studio.StudioProcessor studio;

    private final AudioBuf curBuf;

    private final AudioBuf nxtBuf;

    private final AudioBuf renderA;

    private final AudioBuf renderB;

    private final AudioBuf carryBuf;

    public static final class Skipped {
        public final String source;

        public final String why;

        Skipped(String source, String why) {
            this.source = source;
            this.why = why;
        }

        @Override
        public String toString() {
            return "Skipped{source='" + source + "', why='" + why + "'}";
        }
    }

    private static final class PrepJob {
        final BlockingQueue<PreparedTransition> rx;

        final long generation;

        final AtomicBoolean busy;

        PrepJob(BlockingQueue<PreparedTransition> rx, long generation, AtomicBoolean busy) {
            this.rx = rx;
            this.generation = generation;
            this.busy = busy;
        }
    }

    private static final class PreparedTransition {
        final Transition engine;

        final TransitionPlan plan;

        final String preparedIntro;

        final Deck deck;

        final List<String> took;

        final List<Skipped> recorded;

        final PitchShifter outroPitch;

        PreparedTransition(
                Transition engine,
                TransitionPlan plan,
                String preparedIntro,
                Deck deck,
                List<String> took,
                List<Skipped> recorded,
                PitchShifter outroPitch) {
            this.engine = engine;
            this.plan = plan;
            this.preparedIntro = preparedIntro;
            this.deck = deck;
            this.took = took;
            this.recorded = recorded;
            this.outroPitch = outroPitch;
        }
    }

    public interface HeadersLookup {
        java.util.Map<String, String> headersFor(String source);
    }

    private HeadersLookup headersLookup;

    public void setHeadersLookup(HeadersLookup lookup) {
        this.headersLookup = lookup;
    }

    private java.util.Map<String, String> headersFor(String source) {
        return headersOf(headersLookup, source);
    }

    private static java.util.Map<String, String> headersOf(HeadersLookup lookup, String source) {
        if (lookup == null || source == null) {
            return java.util.Collections.emptyMap();
        }
        java.util.Map<String, String> h = lookup.headersFor(source);
        return h == null ? java.util.Collections.<String, String>emptyMap() : h;
    }

    private static final class PreparedDeck {
        final Deck deck;

        final List<String> took;

        final List<Skipped> recorded;

        PreparedDeck(Deck deck, List<String> took, List<Skipped> recorded) {
            this.deck = deck;
            this.took = took;
            this.recorded = recorded;
        }
    }

    public Director(List<String> tracks, int outRate, int outChannels, TransitionFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException(
                    "transition factory must not be null: the mode must have exactly one source of"
                        + " truth");
        }
        this.outRate = outRate;
        this.outChannels = outChannels;
        Transition engine = factory.create();
        this.transition = engine;
        this.factory = factory;
        this.window = engine.windowFrames(outRate);
        this.triggerLead = this.window;
        int cap = Deck.RENDER_FRAMES;
        ChannelLayout layout = ChannelLayout.of(outChannels);

        Iterator<String> it = tracks.iterator();
        List<Skipped> skipLog = new ArrayList<Skipped>();

        Deck opened = null;
        while (it.hasNext()) {
            String p = it.next();
            try {
                opened = openDeckWithHeaders(p, outRate, outChannels, headersLookup);
                break;
            } catch (AudioException e) {
                skipLog.add(new Skipped(p, e.getMessage()));
            }
        }
        if (opened == null) {
            throw AudioException.config("no playable file in the queue");
        }

        this.current = opened;
        this.next = null;
        this.queue = new ArrayDeque<String>();
        while (it.hasNext()) {
            this.queue.addLast(it.next());
        }
        this.skipped = skipLog;
        this.history = new ArrayList<String>();
        this.transitionPos = 0L;
        this.transitioning = false;
        this.prepared = false;
        this.ready = null;
        this.prep = null;
        this.generation = 0L;
        this.activePlan = TransitionPlan.NONE;
        this.spatialEnabled = false;
        this.curBuf = new AudioBuf(layout, Math.max(cap, 2 * Deck.RENDER_FRAMES));
        this.nxtBuf = new AudioBuf(layout, Math.max(cap, 2 * Deck.RENDER_FRAMES));
        this.renderA = new AudioBuf(layout, Deck.RENDER_FRAMES);
        this.renderB = new AudioBuf(layout, Deck.RENDER_FRAMES);
        this.carryBuf = new AudioBuf(layout, 2 * Deck.RENDER_FRAMES);
    }

    public int outRate() {
        return outRate;
    }

    public long lastTransitionFrames() {
        return lastTransitionFrames;
    }

    public long windowFrames() {
        return window;
    }

    public long triggerLeadFrames() {
        return triggerLead();
    }

    public float windowSecs() {
        return (float) window / (float) Math.max(outRate, 1);
    }

    public TrackSpec currentSpec() {
        return current.spec();
    }

    public String currentPath() {
        return current.source();
    }

    public String nextPath() {
        Deck d = next;
        return d == null ? null : d.source();
    }

    public TrackSpec nextSpec() {
        Deck d = next;
        return d == null ? null : d.spec();
    }

    public String queuedNextPath() {
        return queue.peekFirst();
    }

    public boolean replaceQueued(String source) {
        if (source == null || source.isEmpty() || transitioning) {
            return false;
        }
        if (source.equals(queue.peekFirst())) {
            return true;
        }
        queue.clear();
        queue.addLast(source);
        if (isRemoteSource(source) && prepareMarginSecs < REMOTE_PREPARE_MARGIN_SECS) {
            AutoMixLog.debug(
                    "[qplayer-engine] automix: next track is a remote source (not yet on disk),"
                        + " prepare margin raised to {} s (the setting is {} s) — remote read time"
                        + " cannot be estimated",
                    Long.valueOf(REMOTE_PREPARE_MARGIN_SECS),
                    Long.valueOf(prepareMarginSecs));
        } else if (!isRemoteSource(source) && sawRemoteNext) {
            AutoMixLog.debug(
                    "[qplayer-engine] automix: next track is now local ({}), prepare margin back to"
                        + " the setting {} s",
                    source,
                    Long.valueOf(prepareMarginSecs));
        }
        sawRemoteNext = isRemoteSource(source);
        prepareRetries = 0;
        resetPreparation();
        return true;
    }

    public boolean collectPreparation() {
        pollPreparation();
        PreparedTransition p = ready;
        return p != null && p.deck != null;
    }

    public double transitionEntrySecs() {
        return activePlan.skipSecs;
    }

    public double transitionDroppedTailSecs() {
        return activePlan.outroTailSecs;
    }

    public String transitionName() {
        return transition.name();
    }

    public String transitionNote() {
        if (activePlan.note.isEmpty()) {
            PreparedTransition p = ready;
            return p == null ? "" : p.plan.note;
        }
        return activePlan.note;
    }

    public long currentFrames() {
        return current.outFrames();
    }

    public double outroLoopLeadInSecs() {
        Transition t = transition;
        return t == null ? 0.0 : t.outroLoopLeadInSecs();
    }

    public long nextFrames() {
        Deck d = next;
        return d == null ? -1L : d.outFrames();
    }

    public int queued() {
        return queue.size();
    }

    public List<Skipped> skipped() {
        return Collections.unmodifiableList(skipped);
    }

    public boolean inTransition() {
        return transitioning;
    }

    public Double transitionLeadInSecs() {
        int rate = Math.max(outRate, 1);
        if (transitioning) {
            long left = Math.max(0L, Math.max(window, 1L) - transitionPos);
            return Double.valueOf(-(double) left / (double) rate);
        }
        if (ready == null) {
            return null;
        }
        Long rem = current.remainingFrames();
        if (rem == null) {
            return null;
        }
        return Double.valueOf((double) (rem.longValue() - triggerLead()) / (double) rate);
    }

    public Float transitionProgress() {
        if (!transitioning) {
            return null;
        }
        long w = Math.max(window, 1L);
        float p = (float) transitionPos / (float) w;
        if (p < 0f) {
            p = 0f;
        } else if (p > 1f) {
            p = 1f;
        }
        return Float.valueOf(p);
    }

    public PathState pathState() {
        if (transitioning) {
            return PathState.TRANSITIONING;
        }
        if (studio != null && studio.active()) {
            return PathState.PROCESSED;
        }
        if (current.isPassthrough() && current.gainIsUnity()) {
            return PathState.BIT_PERFECT;
        }
        return PathState.PROCESSED;
    }

    public void setGainDb(float db) {
        current.setGainDb(db);
    }

    public void setSpatial(boolean enabled) {
        this.spatialEnabled = enabled;
        this.current.setSpatial(enabled);
        Deck n = next;
        if (n != null) {
            n.setSpatial(enabled);
        }
    }

    public void setStudio(dev.t1m3.qplayer.audio.studio.StudioProcessor value) {
        this.studio = value;
    }

    public dev.t1m3.qplayer.audio.studio.StudioProcessor studio() {
        return studio;
    }

    public boolean spatialEnabled() {
        return spatialEnabled;
    }

    public double seek(double secs) {
        if (transitioning) {
            finishTransition();
        }
        resetPreparation();
        clearPlaybackBuffers();
        return current.seek(secs);
    }

    public boolean skipNext() {
        if (transitioning) {
            finishTransition();
            return true;
        }

        rememberCurrent();
        Deck deck = openNext();
        if (deck == null) {
            return false;
        }
        Deck old = current;
        current = deck;
        retire(old);
        clearPlaybackBuffers();
        resetPreparation();
        return true;
    }

    public boolean skipPrev() {
        if (transitioning) {
            finishTransition();
        }

        if (history.isEmpty()) {
            return false;
        }
        String prev = history.remove(history.size() - 1);

        String cur = currentPath();
        queue.addFirst(cur);

        try {
            Deck deck = openDeckWithHeaders(prev, outRate, outChannels, headersLookup);
            deck.setSpatial(spatialEnabled);
            Deck old = current;
            current = deck;
            retire(old);
            clearPlaybackBuffers();
            resetPreparation();
            return true;
        } catch (AudioException e) {
            skipped.add(new Skipped(prev, e.getMessage()));
            return false;
        }
    }

    private void rememberCurrent() {
        String p = currentPath();
        if (history.isEmpty() || !history.get(history.size() - 1).equals(p)) {
            history.add(p);
            if (history.size() > HISTORY_LIMIT) {
                history.remove(0);
            }
        }
    }

    public boolean reprepare() {
        if (transitioning) {
            return false;
        }
        if (!prepared && prep == null) {
            return false;
        }
        resetPreparation();
        return true;
    }

    private void resetPreparation() {
        prepared = false;
        PreparedTransition p = ready;
        if (p != null) {
            retire(p.deck);
            ready = null;
        }
        generation = generation + 1L;
    }

    private void clearPlaybackBuffers() {
        curBuf.clear();
        nxtBuf.clear();
        carryBuf.clear();
    }

    public int pump(AudioBuf out) {
        int capacity = out.capacityFrames();
        if (capacity == 0) {
            out.setFrames(0);
            return 0;
        }

        pollPreparation();

        if (shouldPrepare()) {
            startPreparation();
        }

        if (!transitioning && shouldBeginTransition()) {
            if (!triggerReported) {
                triggerReported = true;
                Long left = current.remainingFrames();
                logBeginOverlap(left, ready);
            }
            beginTransition();
        } else if (!transitioning) {
            triggerReported = false;
        }

        int n = transitioning ? renderTransition(out, transitionPos) : renderCurrent(out);
        if (n > 0) {
            return n;
        }

        if (transitioning) {
            finishTransition();
            return renderCurrent(out);
        }
        return 0;
    }

    private boolean shouldPrepare() {
        if (window == 0 || queue.isEmpty() || prepared) {
            return false;
        }
        if (prep != null) {
            return false;
        }
        return currentPath() != null;
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    private void startPreparation() {
        prepared = true;

        String outro = currentPath();
        String intro = queue.peekFirst();
        if (intro == null) {
            return;
        }

        TransitionFactory f = factory;
        int rate = outRate;
        int channels = outChannels;
        boolean spatial = spatialEnabled;
        long gen = generation;

        List<String> workerQueue = new ArrayList<String>(queue);

        AtomicBoolean busy = new AtomicBoolean(true);
        BlockingQueue<PreparedTransition> rx = new ArrayBlockingQueue<PreparedTransition>(1);
        PrepareWorker worker =
                new PrepareWorker(
                        f,
                        outro,
                        intro,
                        rate,
                        channels,
                        spatial,
                        workerQueue,
                        busy,
                        rx,
                        headersLookup);

        Thread t = new Thread(worker, "sonalis-prepare");
        try {
            t.start();
            prep = new PrepJob(rx, gen, busy);
        } catch (Throwable e) {
            prep = null;
        }
    }

    private static String chainOf(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        int guard = 0;
        while (t != null && guard++ < 8) {
            sb.append(t.getClass().getName());
            if (t.getMessage() != null) {
                sb.append('(').append(t.getMessage()).append(')');
            }
            t = t.getCause();
            if (t != null) {
                sb.append(" ⟵ ");
            }
        }
        if (e instanceof LinkageError && e.getMessage() != null) {
            String name = e.getMessage().replace('/', '.');
            try {
                Class.forName(name, false, Director.class.getClassLoader());
                sb.append(
                        " ; ⚠️ this class **is** on the classpath ⇒ what is missing is another"
                            + " class it depends on ✗");
            } catch (Throwable ignored) {
                sb.append(
                        " ; this class **is not** on the classpath ⇒ the artifact really is missing"
                            + " it ✗");
            }
        }
        return sb.toString();
    }

    private static final class PrepareWorker implements Runnable {
        private final TransitionFactory factory;
        private final String outro;
        private final String intro;
        private final int rate;
        private final int channels;
        private final boolean spatial;
        private final List<String> workerQueue;
        private final AtomicBoolean busy;
        private final BlockingQueue<PreparedTransition> rx;
        private final HeadersLookup headersLookup;

        PrepareWorker(
                TransitionFactory factory,
                String outro,
                String intro,
                int rate,
                int channels,
                boolean spatial,
                List<String> workerQueue,
                AtomicBoolean busy,
                BlockingQueue<PreparedTransition> rx,
                HeadersLookup headersLookup) {
            this.headersLookup = headersLookup;
            this.factory = factory;
            this.outro = outro;
            this.intro = intro;
            this.rate = rate;
            this.channels = channels;
            this.spatial = spatial;
            this.workerQueue = workerQueue;
            this.busy = busy;
            this.rx = rx;
        }

        @Override
        public void run() {
            try {
                work();
            } catch (LinkageError e) {
                AutoMixLog.warn(
                        "[qplayer-engine] prepare thread: class load failed ⇒ no preparation result"
                            + " this time, playback continues as usual (the next transition will"
                            + " retry). diagnostic chain: {}",
                        chainOf(e));
            } finally {
                busy.set(false);
                PrepareStage.clear();
            }
        }

        private void work() {
            PrepareStage.mark("create transition instance");
            Transition engine = factory.create();
            PrepareStage.mark(
                    "analysis (read material / measure tempo / detect key / scan envelopes /"
                        + " separate stems)");
            TransitionPlan plan =
                    engine.prepare(
                            outro,
                            intro,
                            rate,
                            headersOf(headersLookup, outro),
                            headersOf(headersLookup, intro));
            PrepareStage.mark("open the intro deck");

            PreparedDeck pd = openPreparedDeck(workerQueue, rate, channels, spatial, headersLookup);
            Deck deck = pd.deck;
            List<Skipped> recorded = pd.recorded;

            if (deck != null && !deck.source().equals(intro)) {
                plan = noCuePlan();
            }

            if (deck != null && plan.skipSecs > 0.0) {
                recordHeavy(HEAVY_SEEK, deck.source());
                try {
                    deck.seek(plan.skipSecs);
                } catch (AudioException e) {
                    recorded.add(
                            new Skipped(
                                    deck.source(), "beat-match seek failed: " + e.getMessage()));
                }
            }

            PitchShifter outroPitch =
                    Math.abs(plan.pitch.outro) > 1e-6f ? idlePitchShifter(rate, channels) : null;

            if (deck != null && Math.abs(engine.pitchAt(0.0f).intro) > 1e-6f) {
                deck.engagePitch();
            }

            installMaterialHook(deck, engine, deck.inRate());
            rx.offer(
                    new PreparedTransition(
                            engine, plan, intro, deck, pd.took, recorded, outroPitch));
        }
    }

    private void pollPreparation() {
        if (transitioning) {
            return;
        }

        PrepJob job = prep;
        if (job == null) {
            return;
        }
        if (job.busy.get()) {
            return;
        }

        prep = null;
        if (job.generation != generation) {
            PreparedTransition stale = job.rx.poll();
            if (stale != null) {
                retire(stale.deck);
            }
            String cur = currentPath();
            String next = queuedNextPath();
            AutoMixLog.info(
                    "[qplayer-engine] automix: this preparation is stale (track changed/skipped),"
                        + " result discarded · current {} · queue head {}",
                    cur == null ? "(none)" : cur,
                    next == null ? "(none)" : next);
            AutoMixLog.debug(
                    "[qplayer-engine] automix: the discarded preparation{}",
                    stale == null
                            ? "(the thread left no result)"
                            : "(that plan was computed for " + stale.preparedIntro + ")");
            return;
        }

        PreparedTransition p = job.rx.poll();
        if (p != null) {
            long w = p.engine.windowFrames(outRate);
            if (w > 0) {
                window = w;
            }
            long tailFrames = (long) Math.round(p.plan.outroTailSecs * outRate);
            triggerLead = tailFrames > 0L ? saturatingAdd(window, tailFrames) : window;
            AutoMixLog.info(
                    "[qplayer-engine] transition loaded: {} → {}; overlap {}s, starts with {}s left"
                        + " on A, B enters at {}s",
                    current.source(),
                    p.deck == null ? "(open failed)" : p.deck.source(),
                    secs(window),
                    secs(triggerLead),
                    secs(p.plan.skipSecs));
            if (p.deck == null) {
                allowPrepareRetry(
                        "this preparation has no installable intro track (deck won't open)");
                return;
            }
            ready = p;
            return;
        }
        allowPrepareRetry("this preparation left no result (prepare threw / stream open failed)");
    }

    private boolean shouldBeginTransition() {
        if (window == 0 || queue.isEmpty()) {
            return false;
        }
        Long rem = current.remainingFrames();
        if (rem == null) {
            return false;
        }
        return rem.longValue() <= triggerLead();
    }

    private String secs(long frames) {
        return String.format(
                java.util.Locale.ROOT, "%.1f", (double) frames / (double) Math.max(outRate, 1));
    }

    private String secs(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.1f", seconds);
    }

    private long triggerLead() {
        return triggerLead >= 0L ? triggerLead : window;
    }

    private volatile String skipReason;

    private void noteSkip(String reason) {
        skipReason = reason;
    }

    public String skipReason() {
        return skipReason;
    }

    private boolean allowPrepareRetry(String why) {
        noteSkip(why);
        if (prepareRetries >= MAX_PREPARE_RETRIES) {
            AutoMixLog.info(
                    "[qplayer-engine] automix: {} → already retried {} times, no more retries this"
                        + " time",
                    why,
                    Integer.valueOf(prepareRetries));
            return false;
        }
        prepareRetries++;
        prepared = false;
        AutoMixLog.info(
                "[qplayer-engine] automix: {} → discarded, allowing one retry (margin {} s)",
                why,
                Long.valueOf(prepareMarginSecs));
        return true;
    }

    private void reportTriggerMiss() {
        if (triggerMissReported) {
            return;
        }
        triggerMissReported = true;
        String why;
        if (prep != null) {
            why =
                    "prepare thread still running (analysis/stream open unfinished, stuck at: "
                            + PrepareStage.current()
                            + ") → the result missed the trigger point";
        } else if (ready == null && prepared) {
            why =
                    "prepare thread finished but left no installable result (no intro track opens,"
                        + " or prepare threw)";
        } else {
            why = "no preparation was ever started";
        }
        AutoMixLog.warn(
                "[qplayer-engine] automix: reached the trigger point with no usable preparation →"
                    + " not starting a transition this time ({}) · current {} · queue head {} ·"
                    + " margin {} s · trigger lead {} s",
                why,
                currentPath(),
                String.valueOf(queuedNextPath()),
                Long.valueOf(prepareMarginSecs),
                fmtSecs((double) triggerLead() / Math.max(1, outRate)));
        noteSkip(why);
    }

    private static String fmtSecs(double secs) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(secs));
    }

    private void beginTransition() {
        PreparedTransition preparedResult = ready;
        if (preparedResult == null) {
            reportTriggerMiss();
            return;
        }
        ready = null;
        triggerMissReported = false;

        Transition engine = preparedResult.engine;
        TransitionPlan plan = preparedResult.plan;
        String preparedIntro = preparedResult.preparedIntro;
        Deck deck = preparedResult.deck;
        List<String> took = preparedResult.took;
        List<Skipped> recorded = preparedResult.recorded;
        PitchShifter outroPitch = preparedResult.outroPitch;

        if (deck == null) {
            return;
        }

        if (!queueStartsWith(took)) {
            retire(deck);
            return;
        }
        for (int i = 0; i < took.size(); i++) {
            queue.pollFirst();
        }
        skipped.addAll(recorded);

        transition = engine;

        deck.setSpatial(spatialEnabled);

        if (!preparedIntro.equals(deck.source())) {
            plan = noCuePlan();
        }

        if (outroPitch != null) {
            current.engagePitchWith(outroPitch);
        }

        activePlan = plan;
        next = deck;
        transitionPos = 0L;
        transitioning = true;
        lastHandoverUnusedFrames = 0L;
        transitionRenderedIntro = 0L;
        introRemainingAtTransitionStart = deck == null ? null : deck.remainingFrames();
        outroRemainingAtTransitionStart = current == null ? null : current.remainingFrames();
        noteSkip(null);
    }

    private void logBeginOverlap(Long left, PreparedTransition ready) {
        Object[] args = {
            left == null ? "?" : secs(left.longValue()),
            secs(triggerLead()),
            ready == null ? "?" : secs(ready.plan.skipSecs),
            ready == null ? "no (this transition is postponed to a hard cut at track end)" : "yes"
        };
        String msg =
                "[qplayer-engine] overlap begins: A has {}s left (trigger {}s), B enters at {}s,"
                    + " ready={}";
        if (ready == null) {
            AutoMixLog.info(msg, args);
        } else {
            AutoMixLog.debug(msg, args);
        }
    }

    private void logHandoverPosition(Long framesBeforeHandover) {
        if (framesBeforeHandover == null) {
            return;
        }
        long before = framesBeforeHandover.longValue();
        long after = currentFrames();
        long delta = after - before;
        lastHandoverPositionDeltaFrames = delta;
        if (delta == 0L && !AutoMixLog.verbose()) {
            return;
        }
        String line =
                String.format(
                        java.util.Locale.ROOT,
                        "handover position: before %d frames (%.3f s) · after %d frames (%.3f s) ·"
                            + " delta %+d frames (%+d ms)",
                        Long.valueOf(before),
                        Double.valueOf(before / (double) Math.max(1, outRate)),
                        Long.valueOf(after),
                        Double.valueOf(after / (double) Math.max(1, outRate)),
                        Long.valueOf(delta),
                        Long.valueOf(delta * 1000L / Math.max(1, outRate)));
        if (delta != 0L) {
            AutoMixLog.warn(
                    "[qplayer-engine] {} — position went back/forward at the handover, the UI will"
                        + " jump; please report this line along with it",
                    line);
        } else {
            AutoMixLog.debug("[qplayer-engine] {}", line);
        }
    }

    private void logHandoverContinuity(Deck nextDeck) {
        int rate = Math.max(1, outRate);
        long produced = transitionPos;
        String introPart;
        Long start = introRemainingAtTransitionStart;
        Long end = nextDeck == null ? null : nextDeck.remainingFrames();
        if (start == null || end == null) {
            introPart = "B advanced unknown (this side's length is unknown)";
        } else {
            long advanced = start.longValue() - end.longValue();
            long pending = carryBuf.frames();
            lastHandoverUnusedFrames = pending;
            lastHandoverDeltaFrames = advanced - pending - produced;
            long deltaMs = lastHandoverDeltaFrames * 1000L / rate;
            introPart =
                    String.format(
                            java.util.Locale.ROOT,
                            "B advanced %.3f s (material advanced %.3f s%s, delta %+d ms%s)",
                            Double.valueOf((double) advanced / rate),
                            Double.valueOf((double) produced / rate),
                            pending > 0
                                    ? String.format(
                                            java.util.Locale.ROOT,
                                            ", of which %d frames stay buffered for the next block",
                                            Long.valueOf(pending))
                                    : "",
                            Long.valueOf(deltaMs),
                            deltaMs == 0L ? "" : " ← these two disagree, the handover will jump");
        }
        String outroPart = "";
        Long oStart = outroRemainingAtTransitionStart;
        if (oStart != null) {
            long tailFrames =
                    (long) Math.round(activePlan == null ? 0.0 : activePlan.outroTailSecs * rate);
            long left = oStart.longValue() - produced;
            outroPart =
                    String.format(
                            java.util.Locale.ROOT,
                            " · A has %.3f s left when it ends (tail dropped %.3f s)",
                            Double.valueOf((double) left / rate),
                            Double.valueOf((double) tailFrames / rate));
        }
        if (introRemainingAtTransitionStart != null
                && end != null
                && Math.abs(lastHandoverDeltaFrames) > 2L) {
            AutoMixLog.warn(
                    "[qplayer-engine] handover continuity: {} · window advanced {} frames{} ——"
                        + " intro deck and material advancement disagree, the handover will jump;"
                        + " please report this line along with it",
                    introPart,
                    Long.valueOf(produced),
                    outroPart);
        } else {
            AutoMixLog.debug(
                    "[qplayer-engine] handover continuity: {} · window advanced {} frames{}",
                    introPart,
                    Long.valueOf(produced),
                    outroPart);
        }
    }

    private boolean queueStartsWith(List<String> prefix) {
        if (prefix.size() > queue.size()) {
            return false;
        }
        int i = 0;
        for (String p : queue) {
            if (i >= prefix.size()) {
                break;
            }
            if (!prefix.get(i).equals(p)) {
                return false;
            }
            i++;
        }
        return i == prefix.size();
    }

    private int renderCurrent(AudioBuf out) {
        int n = carryBuf.frames() > 0 ? renderCarryThenDeck(out) : renderCurrentDeck(out);
        if (n > 0) {
            return n;
        }
        if (!current.isDrained()) {
            skipped.add(
                    new Skipped(
                            current.source(),
                            "render kept returning empty frames but the decoder is not drained,"
                                + " treating as end"));
        }
        rememberCurrent();
        resetPreparation();
        Deck deck = openNext();
        if (deck == null) {
            return 0;
        }
        Deck old = current;
        current = deck;
        retire(old);
        clearPlaybackBuffers();
        return renderCurrentDeck(out);
    }

    private int renderCurrentDeck(AudioBuf out) {
        int n = current.render(out);
        int tries = 0;
        while (n == 0 && !current.isDrained() && tries < 256) {
            n = current.render(out);
            tries++;
        }
        if (n > 0 && studio != null) {
            studio.processDeckA(out);
        }
        return n;
    }

    private void ensureFrames(Deck deck, AudioBuf buf, AudioBuf scratch, int want, boolean deckA) {
        int guard = 0;
        while (buf.frames() < want && guard++ < 4) {
            int got = deck.render(scratch);
            if (got <= 0) {
                break;
            }
            if (studio != null) {
                if (deckA) {
                    studio.processDeckA(scratch);
                } else {
                    studio.processDeckB(scratch);
                }
            }
            if (buf.append(scratch) <= 0) {
                break;
            }
        }
    }

    private int renderCarryThenDeck(AudioBuf out) {
        out.clear();
        int moved = out.append(carryBuf);
        dropFront(carryBuf, moved);
        if (out.frames() >= out.capacityFrames()) {
            return out.frames();
        }
        int got = renderCurrentDeck(renderA);
        if (got <= 0) {
            return out.frames();
        }
        int room = out.capacityFrames() - out.frames();
        if (got <= room) {
            out.append(renderA);
        } else {
            renderA.setFrames(room);
            out.append(renderA);
            renderA.setFrames(got);
            dropFront(renderA, room);
            carryBuf.clear();
            carryBuf.append(renderA);
        }
        return out.frames();
    }

    private static void dropFront(AudioBuf buf, int frames) {
        int drop = Math.min(Math.max(0, frames), buf.frames());
        if (drop <= 0) {
            return;
        }
        int keep = buf.frames() - drop;
        if (keep > 0) {
            float[] data = buf.backingArray();
            int stride = buf.capacityFrames();
            for (int c = 0; c < buf.channels(); c++) {
                System.arraycopy(data, c * stride + drop, data, c * stride, keep);
            }
        }
        buf.setFrames(keep);
    }

    private int renderTransition(AudioBuf out, long pos) {
        long windowLocal = Math.max(window, 1L);
        float progress = (float) pos / (float) windowLocal;
        if (progress < 0f) {
            progress = 0f;
        } else if (progress > 1f) {
            progress = 1f;
        }

        PitchPlan pitch = transition.pitchAt(progress);
        current.setPitch(pitch.outro);
        Deck nextDeck = next;
        if (nextDeck != null) {
            nextDeck.setPitch(pitch.intro);
        }

        ensureFrames(current, curBuf, renderA, out.capacityFrames(), true);

        boolean outroGone = curBuf.frames() == 0;
        float introStartAt = activePlan.introStartAt;
        int renderedIntro = 0;
        if (progress >= introStartAt || outroGone) {
            if (nextDeck != null) {
                ensureFrames(nextDeck, nxtBuf, renderB, out.capacityFrames(), false);
                renderedIntro = nxtBuf.frames();
                transitionRenderedIntro += renderedIntro;
            }
        } else {
            nxtBuf.setFrames(0);
        }

        int n = transition.render(out, curBuf, nxtBuf, progress);
        dropFront(curBuf, n);
        dropFront(nxtBuf, n);

        long newPos = pos + (long) n;
        transitionPos = newPos;
        if (newPos >= windowLocal) {
            finishTransition();
        }
        return n;
    }

    private void finishTransition() {
        rememberCurrent();

        Deck nextDeck = next;
        Long framesBeforeHandover = nextDeck == null ? null : Long.valueOf(nextDeck.outFrames());
        curBuf.clear();
        carryBuf.clear();
        if (nxtBuf.frames() > 0) {
            carryBuf.append(nxtBuf);
            nxtBuf.clear();
        }
        next = null;
        if (nextDeck != null) {
            Deck old = current;
            current = nextDeck;
            retire(old);
        }
        transitioning = false;
        logHandoverContinuity(nextDeck);
        logHandoverPosition(framesBeforeHandover);
        lastTransitionFrames = transitionPos;
        transitionPos = 0L;
        activePlan = TransitionPlan.NONE;
        triggerLead = window;
        resetPreparation();
    }

    private Deck openNext() {
        String p;
        while ((p = queue.pollFirst()) != null) {
            try {
                Deck d = openDeckWithHeaders(p, outRate, outChannels, headersLookup);
                d.setSpatial(spatialEnabled);
                return d;
            } catch (AudioException e) {
                skipped.add(new Skipped(p, e.getMessage()));
            }
        }
        return null;
    }

    private static void retire(Deck d) {
        if (d != null) {
            d.close();
        }
    }

    private static TransitionPlan noCuePlan() {
        return new TransitionPlan(
                0.0, PitchPlan.ZERO, 0.0f, "queue head won't open, no beat match this time");
    }

    private static PreparedDeck openPreparedDeck(
            List<String> queue,
            int outRate,
            int outChannels,
            boolean spatial,
            HeadersLookup lookup) {
        List<String> took = new ArrayList<String>();
        List<Skipped> recorded = new ArrayList<Skipped>();
        for (int i = 0; i < queue.size(); i++) {
            String p = queue.get(i);
            took.add(p);
            try {
                Deck d = openDeckWithHeaders(p, outRate, outChannels, lookup);
                d.setSpatial(spatial);
                return new PreparedDeck(d, took, recorded);
            } catch (AudioException e) {
                recorded.add(new Skipped(p, e.getMessage()));
            }
        }
        return new PreparedDeck(null, took, recorded);
    }

    @Override
    public String toString() {
        return "Director { out_rate: "
                + outRate
                + ", out_channels: "
                + outChannels
                + ", window: "
                + window
                + ", transition: \""
                + transition.name()
                + "\""
                + ", current: "
                + current
                + ", queue_len: "
                + queue.size()
                + ", prepared: "
                + prepared
                + ", ready: "
                + (ready != null)
                + ", prep_in_flight: "
                + (prep != null)
                + ", in_transition: "
                + transitioning
                + ", .. }";
    }
}
