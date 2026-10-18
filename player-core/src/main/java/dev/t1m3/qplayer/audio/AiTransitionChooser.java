package dev.t1m3.qplayer.audio;

import dev.t1m3.qplayer.ai.AiClient;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.util.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * A {@link TransitionChooser} that asks the configured chat model which transition
 * a pair of tracks deserves — and never lets that request touch a track boundary.
 *
 * <h3>What the model is and is not</h3>
 * {@link AiClient} is a <em>text</em> model. It cannot hear either track: it gets
 * no audio, no spectrum, no tempo, no key — only the metadata the queue already
 * shows (title, artist, album, length, source) plus whatever it happens to know
 * about those songs from its training data. Its answer is therefore a suggestion
 * about two <em>names</em>, not an analysis of two recordings, and the prompt says
 * so in as many words. That is why an answer is never required: the boundary works
 * exactly as it did before this class existed when the model is unreachable,
 * unconfigured, slow, or wrong.
 *
 * <p>For the same reason the model's answer can only <em>shape</em> a blend, never
 * take one away: an answer that would end the outgoing track without the next one
 * ever being audible (CUT, FADE_OUT_IN, SILENCE_TRIM) is replaced by the local
 * rules' answer whenever those rules say the pair can be overlapped at all, and the
 * line says so. What the model decides is the kind among the overlapping ones, how
 * long the overlap is, and its curve — all of which are audible, and none of which
 * the rest of the app can check for it. See {@link #plan}.
 *
 * <h3>Why nothing here blocks</h3>
 * {@link #plan} runs on the render pump, in the frame in which a boundary is
 * decided. It may therefore only read: an answer already in {@link #decided}
 * (this session) or in the {@link Memory} cache (a previous one). If neither has
 * it, this returns the {@link #fallback} (the local heuristic) and schedules the
 * ask on {@link #worker} for later — the boundary is never delayed by a network
 * round trip, and a request is never made on the playback path.
 *
 * <p>The ask is scheduled when the pair becomes known (the controller's
 * {@code preloadAdjacent} window, minutes before the boundary), so the answer is
 * normally already waiting; the ask from {@link #plan} is the safety net for a
 * pair nobody prefetched (an app started mid-track, a queue edited between
 * tracks).
 *
 * <h3>Cache</h3>
 * One decision per ordered pair, in {@link Memory} (the {@code transition/}
 * sub-cache of {@code DiskCache}) and in memory for the session. The pair is
 * identified by the same keys the silence cache uses ({@link TransitionPlan#pairKey}),
 * so both describe the same audio the same way. A pair is asked about at most
 * once per session even when the answer was useless ("no opinion"), but a failed
 * or useless answer is deliberately not written to disk: a model that was down
 * for thirty seconds must not be remembered as having nothing to say about a
 * pair for ever.
 */
public final class AiTransitionChooser implements TransitionChooser {

    /** The pair-decision store. Implemented by the controller over
     *  {@code DiskCache}'s {@code transition/} sub-cache; an interface so this
     *  class stays testable (and so a host without a cache can pass a no-op one).
     *  Both methods may be called from the worker thread and from the pump. */
    public interface Memory {
        /** The stored bytes for {@code key}, or null when nothing is stored. */
        byte[] read(String key);

        /** Store {@code data} under {@code key}. Failures are the implementation's
         *  business and must not propagate: a cache that cannot be written is a
         *  slower feature, not a broken one. */
        void write(String key, byte[] data);
    }

    /** The system prompt. Enumerates every kind and both refinements, forbids
     *  prose, and states the one thing that keeps the answer honest: the model has
     *  not heard the audio. */
    private static final String SYSTEM_PROMPT =
            "你是音乐播放器的“切歌过渡”选择器。你听不到音频，只能看到文字元数据（歌名、歌手、专辑、时长、来源），"
            + "外加你本身对这些歌的了解；所以你的判断是建议，不是分析，没有把握就回答 NONE。\n"
            + "可选的过渡方式（KIND，只能选一个）：\n"
            + "CUT：硬切，上一首播完直接下一首，完全不重叠"
            + "（只在两首确实放不到一起时才用；两首普通歌曲之间硬切等于白白放弃一次过渡）\n"
            + "CROSSFADE：交叉淡化，两首同时出声并互相交叉\n"
            + "QUICK_FADE：快速淡化，约 1 秒的交叉\n"
            + "FADE_OUT_IN：先淡出再淡入，两首不同时出声\n"
            + "SILENCE_TRIM：静音裁切，按静音测量把两首的内容直接接在一起\n"
            + "重叠长度（OVERLAP，仅 CROSSFADE 有意义）：SHORT=约4秒，MEDIUM=约8秒，"
            + "LONG=约15秒（同专辑、同风格、电子/说唱等适合长时间混音、你又比较有把握时用）\n"
            + "淡化曲线（CURVE）：DJ_BLEND（DJ 式，默认，长重叠就用它：下一首早早低声铺进来、上一首压住到结尾才退，"
            + "中间大部分时间两首都听得到），EQUAL_POWER（等功率，对称），LINEAR（线性，只适合很短的交叉）\n"
            + "只输出一行，不要解释、不要 Markdown、不要代码围栏，格式严格为：KIND OVERLAP CURVE\n"
            + "示例：CROSSFADE LONG DJ_BLEND\n"
            + "没有把握时只输出：NONE";

    /** How long the reply may be for a usable parse to be attempted. A reply that
     *  is a paragraph is not a decision; the parser still only ever reads its first
     *  tokens, but refusing the obviously chatty answer keeps the contract honest
     *  and the log readable. */
    private static final int MAX_REPLY_CHARS = 240;

    /** How many tokens of the reply are considered. The contract is three; the
     *  slack covers a leading "答案：" or an extra word, nothing more. */
    private static final int MAX_TOKENS = 8;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutMs;
    /** The answer when there is nothing from the model: the local rules, which is
     *  also what the app did before any of this existed. Never null. */
    private final TransitionChooser fallback;
    private final Memory memory;
    private final Executor worker;

    /** Decisions known in this session, by pair key — from the disk cache or from
     *  an answer that has already arrived. */
    private final Map<String, TransitionPlan> decided = new ConcurrentHashMap<>();
    /** Pairs with a request in flight, so prefetch and the boundary's safety net
     *  do not ask about the same pair twice. */
    private final Set<String> asking =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** Pairs already asked about in this session whose answer was unusable, so a
     *  model that cannot help is asked once per pair rather than once per boundary.
     *  In memory only — see the class doc. */
    private final Set<String> noOpinion =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public AiTransitionChooser(String baseUrl, String apiKey, String model, int timeoutMs,
                               TransitionChooser fallback, Memory memory, Executor worker) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model.trim();
        // A chat request for one line, but not a short one in wall-clock terms: the
        // providers this app is used with are reasoning models (measured on a real
        // account: the very same prompt with the very same model answered in 4.5 s
        // once and 13.3 s the next time, because the hidden reasoning is per-request
        // and unbounded). A timeout that only fits the fast case would silently turn
        // every decision into "no opinion" — so this is floored well above that and
        // only capped at a minute. It costs nothing to be generous: the request runs
        // on its own thread, the boundary never waits for it, and the pair is only
        // asked about once.
        this.timeoutMs = clampTimeout(timeoutMs);
        this.fallback = fallback != null ? fallback : new HeuristicTransitionChooser();
        this.memory = memory;
        this.worker = worker != null ? worker : sharedWorker();
    }

    /** The lane asks run on, one for the whole process.
     *
     *  <p>Its own lane rather than the host's: an ask is a blocking HTTP call that
     *  may take its whole timeout, and every lane the controller already owns is
     *  shared with work a listener can feel — a queue save, a scrobble, a search,
     *  and the one that matters most, the silence measurement a trim is waiting for
     *  (that lane has exactly two threads, one per end of a boundary).
     *
     *  <p>One per process rather than one per chooser, and a daemon: a chooser is
     *  rebuilt whenever the AI settings change, and a thread per rebuild would
     *  accumulate idle threads for a feature that asks at most a few questions per
     *  session. Serial is enough because the asks are deduplicated per pair — a
     *  queue cannot produce a backlog of them. */
    private static Executor sharedWorker() {
        Executor existing = SHARED_WORKER;
        if (existing != null) return existing;
        synchronized (AiTransitionChooser.class) {
            if (SHARED_WORKER == null) {
                SHARED_WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "qplayer-ai-transition");
                    t.setDaemon(true);
                    return t;
                });
            }
            return SHARED_WORKER;
        }
    }

    private static volatile Executor SHARED_WORKER;

    /** Whether this chooser is already pointed at exactly this provider, so the
     *  host can re-push its settings on every change without throwing away the
     *  decisions this instance has already collected. */
    public boolean matches(String otherBaseUrl, String otherApiKey, String otherModel,
                           int otherTimeoutMs) {
        return baseUrl.equals(otherBaseUrl == null ? "" : otherBaseUrl.trim())
                && apiKey.equals(otherApiKey == null ? "" : otherApiKey)
                && model.equals(otherModel == null ? "" : otherModel.trim())
                && timeoutMs == clampTimeout(otherTimeoutMs);
    }

    /** The request timeout, floored so a fast model is not the only one that can
     *  answer and capped at a minute so a hung connection cannot hold the pair's
     *  one in-flight slot for ever. */
    private static int clampTimeout(int requested) {
        if (requested <= 0) return 60_000;
        return Math.max(15_000, Math.min(requested, 60_000));
    }

    /** Whether an AI provider is configured at all — the same three values the AI DJ
     *  dialog uses (address, key, model). Without them this chooser behaves exactly
     *  like the fallback, and the controller does not install it in the first place. */
    public boolean configured() {
        return !baseUrl.isEmpty() && !model.isEmpty();
    }

    @Override
    public TransitionKind choose(TransitionContext ctx) {
        return plan(ctx).kind();
    }

    /**
     * The answer for this boundary: the model's cached plan when there is one, the
     * local heuristic otherwise. Must not block — so nothing here resolves
     * anything, and a missing answer schedules an ask instead of waiting for one.
     */
    @Override
    public TransitionPlan plan(TransitionContext ctx) {
        // The local rules' own answer first, always: its first three rules are about
        // what this boundary can DO (both sides streamable, enough time left, both
        // lengths known), and an AI answer must never talk the player into
        // overlapping a BILI link or ramping inside two seconds. Those rules are
        // therefore a gate the model's answer has to live inside, not a choice it
        // gets to override.
        //
        // Taken from plan() and not choose(), because the rules now name a curve with
        // the kind they answer (an ordinary pair gets an equal-power overlap over the
        // medium 8 s), and a fallback that dropped that would silently reinstate the
        // mid-ramp dip for every pair the model had nothing to say about.
        TransitionPlan safe = fallback.plan(ctx);
        if (safe.kind() == TransitionKind.CUT) {
            return safe.withOverlap(safe.overlapMs(), "rule: the pair cannot be overlapped");
        }
        if (!configured() || ctx == null) {
            return safe.withOverlap(safe.overlapMs(), "rule: AI 未配置");
        }
        String key = TransitionPlan.pairKey(ctx.outgoing(), ctx.incoming());
        if (key == null) return safe.withOverlap(safe.overlapMs(), "rule: no stable key");
        TransitionPlan cached = known(key);
        if (cached == null) {
            // Not known yet: ask in the background, and answer with the rules' own
            // plan — an ordinary pair's plain overlap — rather than with nothing. The
            // rules' answer used to be SILENCE_TRIM (a 250 ms seam) for every pair of
            // long tracks, so a model answer that arrived a moment late degraded the
            // boundary to "nothing happened"; the rules no longer answer that way, and
            // this branch deliberately still names the overlap rather than letting the
            // gate below decide it (see the CUT note there).
            prefetch(ctx, "missing at the boundary");
            return safe;
        }
        // A safe kind other than CUT means this boundary can be transitioned at all
        // (both sides streamable, both lengths known, enough time left), so the
        // model's pick is performable here — its length is the only thing that has
        // to be cut down to fit (clamp, which labels what it did). The one check the
        // rules keep is the capability gate above: the model cannot talk the player
        // into overlapping a source that has no second player to open.
        //
        // ⚠️ What the model cannot do either is take the blend away. Three of the five
        // kinds play the outgoing track to its end without the next one ever being
        // audible — CUT, FADE_OUT_IN, and SILENCE_TRIM (whose own failure mode, when a
        // measurement does not arrive in time, is a cut) — and this model has not heard a
        // note of either track: an answer like that is a guess about two names, and the
        // rules' answer for an ordinary pair is the overlap the whole feature exists for.
        // Measured on the device: the model answered CUT for Moonlight -> Lalala and
        // FADE_OUT_IN for Lalala -> Moonlight, the two answers between which a listener
        // hears the difference between "nothing happened" and an eight-second blend.
        // So a non-overlapping answer is replaced by the rules' answer whenever the rules
        // would give an overlapping one — which the gate above guarantees they do — and
        // the line says whose answer was overruled and why. What the model keeps is
        // everything that shapes a blend: the kind among the overlapping ones, its length
        // and its curve. (A pair that genuinely cannot be overlapped was answered CUT by
        // the gate above, so nothing here can talk the player into one; and the trim the
        // rules do answer is backed by a measured tail, which is the only evidence this
        // app has that a seam is better than a blend.)
        if (!cached.kind().overlapping()) {
            String overruled = "AI answered " + cached.kind() + ", but this pair is two"
                    + " streamable tracks with time to spare and the model has not heard"
                    + " either one — a blend given up, so the local rules' answer ("
                    + safe.kind() + ") is used instead";
            Logger.info("transition: AI 过渡决策 answered {} for {}, but this pair is two"
                            + " streamable tracks with time to spare — a blend given up, so the"
                            + " local rules' answer ({}) is used instead",
                    cached.kind(), key, safe.kind());
            return clamp(safe.withOverlap(safe.overlapMs(), overruled), ctx);
        }
        return clamp(cached, ctx);
    }

    /**
     * Cut a plan down to what this boundary can actually hold, labelling the
     * cut-down so the log never shows a shorter ramp than was asked for without
     * saying so. Two things bound an overlap here:
     *
     * <ul>
     *   <li>the time that is left: an overlap longer than the track's own end-cum-
     *       tail cannot be armed at all, so it is capped to what fits;</li>
     *   <li>either track being short: a fifteen-second overlap is a fifth of a
     *       seventy-second song, and a long ramps that eats a short track's own
     *       arrangement is exactly what {@link HeuristicTransitionChooser} avoids
     *       by answering QUICK_FADE. The model's "long" is not allowed to skip that
     *       judgement.</li>
     * </ul>
     */
    private TransitionPlan clamp(TransitionPlan plan, TransitionContext ctx) {
        if (!plan.kind().overlapping()) {
            // The non-overlapping kinds ramp over a fixed, short time of their own
            // — there is no length here to negotiate.
            return plan;
        }
        long overlap = plan.overlapMs();
        long shortest = Math.min(ctx.outgoingDurationMs(), ctx.incomingDurationMs());
        if (shortest > 0L && shortest < HeuristicTransitionChooser.SHORT_TRACK_MS
                && overlap > TransitionPlan.OVERLAP_SHORT_MS) {
            return plan.withOverlap(TransitionPlan.OVERLAP_SHORT_MS,
                    "capped to short: one track is under "
                            + (HeuristicTransitionChooser.SHORT_TRACK_MS / 1000) + "s");
        }
        return plan;
    }

    // --- asking (never on the playback path) --------------------------------

    /** What is already known about a pair: the answer this session collected, or the
     *  one a previous session left on disk. Only reads — never asks, never waits —
     *  so both the pump (deciding) and the worker (about to ask) may call it. */
    private TransitionPlan known(String key) {
        TransitionPlan cached = decided.get(key);
        if (cached != null) return cached;
        if (memory == null) return null;
        byte[] raw = null;
        try {
            raw = memory.read(key);
        } catch (Throwable e) {
            Logger.warn("transition: AI cache read failed for {}: {}", key, e.toString());
        }
        TransitionPlan fromDisk = TransitionPlan.fromBytes(raw, "AI cached");
        if (fromDisk != null) {
            decided.put(key, fromDisk);
            Logger.info("transition: AI decision loaded from cache for {}: {}", key, fromDisk);
        }
        return fromDisk;
    }

    /** Ask about a pair now, unless it is already known or already being asked
     *  about. Returns immediately: the request runs on {@link #worker}. */
    public void prefetch(TransitionContext ctx, String why) {
        if (!configured() || ctx == null || ctx.incoming() == null) return;
        // Nobody may be asked about a pair that cannot be transitioned anyway; the
        // heuristic's capability gate is the authority on that.
        if (!ctx.outgoingStreamable() || !ctx.incomingStreamable() || !ctx.hasBothLengths()) return;
        String key = TransitionPlan.pairKey(ctx.outgoing(), ctx.incoming());
        if (key == null || noOpinion.contains(key)) return;
        // The disk is consulted here too, not only when a boundary asks: a decision
        // that is already cached — from this session or from the last one — is the
        // whole point of the cache, and re-asking a model about a pair it has already
        // answered is exactly what "the same pair is not re-asked" forbids. Offline
        // and repeated playback then keep working with no provider reachable at all.
        if (known(key) != null) return;
        if (!asking.add(key)) return;
        Logger.info("transition: AI 过渡决策 prefetch ({}): {} -> {}",
                why, track(ctx.outgoing()), track(ctx.incoming()));
        final TransitionContext context = ctx;
        final String userPrompt = userPrompt(ctx);
        worker.execute(() -> {
            String raw = null;
            try {
                // chatPlain, not chat: the answer is a line of tokens, not a JSON
                // document, and asking for json_object makes several providers reject
                // the request unless the prompt literally contains the word "json".
                raw = new AiClient(baseUrl, apiKey, model, timeoutMs)
                        .chatPlain(SYSTEM_PROMPT, userPrompt);
            } catch (Throwable e) {
                // Every failure is the same failure: nothing to say about this pair.
                // Playback never learned this request existed.
                Logger.warn("transition: AI 过渡决策 failed for {}: {}", key,
                        e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                asking.remove(key);
            }
            TransitionPlan parsed = parse(raw);
            if (parsed == null) {
                noOpinion.add(key);
                Logger.info("transition: AI 过渡决策 无意见 for {} ({}), 用本地规则",
                        key, abbreviate(raw));
                return;
            }
            decided.put(key, parsed);
            try {
                if (memory != null) memory.write(key, parsed.toBytes());
            } catch (Throwable e) {
                Logger.warn("transition: AI cache write failed for {}: {}", key, e.toString());
            }
            Logger.info("transition: AI 过渡决策 {} -> {}: {} (reply=\"{}\")",
                    track(context.outgoing()), track(context.incoming()), parsed, abbreviate(raw));
        });
    }

    // --- the prompt ---------------------------------------------------------

    /** The user half of the prompt: both tracks' metadata, which is everything the
     *  model is allowed to decide from. Written once, on the caller's thread, so the
     *  worker never touches a {@link Track} that the queue may be mutating. */
    private static String userPrompt(TransitionContext ctx) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("上一首：").append(describe(ctx.outgoing(), ctx.outgoingDurationMs())).append('\n');
        sb.append("下一首：").append(describe(ctx.incoming(), ctx.incomingDurationMs())).append('\n');
        if (ctx.remainingMs() > 0L) {
            sb.append("上一首还剩约 ").append(ctx.remainingMs() / 1000L).append(" 秒\n");
        }
        sb.append("请判断这一对歌适合哪种过渡方式。只输出一行 KIND OVERLAP CURVE。");
        return sb.toString();
    }

    private static String describe(Track t, long durationMs) {
        if (t == null) return "（未知）";
        StringBuilder sb = new StringBuilder(80);
        sb.append(nonEmpty(t.title, "未知歌名"));
        if (nonEmpty(t.artist, "") != null) sb.append(" / ").append(t.artist.trim());
        String album = nonEmpty(t.album, "");
        if (album != null) sb.append(" / 专辑《").append(album).append('》');
        if (durationMs > 0L) {
            sb.append(" / ").append(durationMs / 60_000L).append("分")
              .append((durationMs / 1000L) % 60L).append("秒");
        }
        sb.append(" / 来源 ").append(t.source);
        return sb.toString();
    }

    /** {@code value} when it has content, else {@code fallback}, else null. */
    private static String nonEmpty(String value, String fallback) {
        if (value != null && !value.trim().isEmpty()) return value.trim();
        return fallback.isEmpty() ? null : fallback;
    }

    private static String track(Track t) {
        return t == null ? "?" : (t.title != null ? t.title : "?");
    }

    // --- parsing ------------------------------------------------------------

    /**
     * Turn a reply into a plan, or null for "no opinion" (which is also the answer
     * to every possible failure: an empty reply, a chatty one, an enum this build
     * does not have, the literal {@code NONE}).
     *
     * <p>The contract is one line of three tokens. Only tokens are ever considered,
     * and only the first {@link #MAX_TOKENS} of them: the model's prose can be as
     * long as it likes, but nothing that is not one of our own enum values can
     * reach the state machine, and an unrecognised KIND disqualifies the whole
     * answer rather than becoming a default. An unrecognised OVERLAP or CURVE is
     * only that field's absence (the overlap falls back to the kind's default, the
     * curve to the configured one) — the kind itself is the load-bearing token.
     */
    static TransitionPlan parse(String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.length() > MAX_REPLY_CHARS) return null;
        List<String> tokens = tokens(raw);
        TransitionKind kind = null;
        long overlap = -1L;
        FadeCurve curve = null;
        for (int i = 0; i < tokens.size() && i < MAX_TOKENS; i++) {
            String token = tokens.get(i);
            if (kind == null) {
                TransitionKind k = kindOf(token);
                if (k != null) { kind = k; continue; }
            }
            if (overlap < 0L) {
                long o = overlapOf(token);
                if (o >= 0L) { overlap = o; continue; }
            }
            if (curve == null) {
                FadeCurve c = curveOf(token);
                if (c != null) { curve = c; continue; }
            }
        }
        if (kind == null) return null;
        if (overlap < 0L) overlap = TransitionPlan.defaultOverlapMs(kind);
        return TransitionPlan.of(kind, overlap, curve, "AI");
    }

    /** The reply's tokens, from the first line that has any. The contract is one
     *  line, but a model that opens with a fence or a "答案：" line of its own has
     *  still answered in one — so empty and decoration-only lines are skipped rather
     *  than disqualifying the reply. Within a line, everything that is not a letter,
     *  a digit or an underscore separates tokens, so "CROSSFADE LONG EQUAL_POWER",
     *  "答案:CROSSFADE LONG EQUAL_POWER" and "`CROSSFADE` `LONG`" all tokenise the
     *  same way, and a whole Chinese sentence becomes one long (unrecognised) token
     *  instead of matching something by accident. */
    private static List<String> tokens(String raw) {
        for (String line : raw.trim().split("\\r?\\n")) {
            List<String> out = tokensOfLine(line);
            if (!out.isEmpty()) return out;
        }
        return Collections.emptyList();
    }

    private static List<String> tokensOfLine(String line) {
        List<String> out = new ArrayList<String>(8);
        StringBuilder token = new StringBuilder(16);
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                token.append(Character.toUpperCase(c));
            } else if (token.length() > 0) {
                out.add(token.toString());
                token.setLength(0);
                if (out.size() >= MAX_TOKENS + 2) break;
            }
        }
        if (token.length() > 0) out.add(token.toString());
        return out;
    }

    /** The kind a token names, or null (including for "NONE", which is the model's
     *  way of saying it has no opinion). */
    static TransitionKind kindOf(String token) {
        if (token == null) return null;
        for (TransitionKind k : TransitionKind.CHOICES) {
            if (k.name().equals(token)) return k;
            // The Chinese label the settings UI shows, so a model answering in the
            // app's own language is understood without a translation table.
            if (k.label().equals(token)) return k;
        }
        if ("CROSS_FADE".equals(token) || "CROSS".equals(token) || "XFADE".equals(token)
                || "X_FADE".equals(token) || "XFADER".equals(token)) return TransitionKind.CROSSFADE;
        if ("QUICKFADE".equals(token) || "QUICK".equals(token)) return TransitionKind.QUICK_FADE;
        if ("FADEOUTIN".equals(token) || "FADE_IN_OUT".equals(token)) return TransitionKind.FADE_OUT_IN;
        if ("SILENCETRIM".equals(token) || "TRIM".equals(token) || "SILENCE".equals(token)) {
            return TransitionKind.SILENCE_TRIM;
        }
        return null;
    }

    /** The overlap a token names, or -1. Accepts the tier names, their initials,
     *  the Chinese tier words, and a number in seconds ("15", "15S") or in
     *  milliseconds ("15000"), mapped to the nearest tier: a decision is a length
     *  class, not an exact millisecond count. */
    static long overlapOf(String token) {
        if (token == null) return -1L;
        if ("SHORT".equals(token) || "S".equals(token) || "短".equals(token)) {
            return TransitionPlan.OVERLAP_SHORT_MS;
        }
        if ("MEDIUM".equals(token) || "MED".equals(token) || "M".equals(token)
                || "中".equals(token) || "中等".equals(token) || "DEFAULT".equals(token)) {
            return TransitionPlan.OVERLAP_MEDIUM_MS;
        }
        if ("LONG".equals(token) || "L".equals(token) || "长".equals(token) || "LONGER".equals(token)) {
            return TransitionPlan.OVERLAP_LONG_MS;
        }
        int digits = 0;
        while (digits < token.length() && Character.isDigit(token.charAt(digits))) digits++;
        if (digits == 0) return -1L;
        long value;
        try {
            value = Long.parseLong(token.substring(0, digits));
        } catch (NumberFormatException e) {
            return -1L;
        }
        // "15" is seconds (nobody writes a 15 ms overlap), "15000" is milliseconds.
        long ms = value < 100L ? value * 1000L : value;
        if (ms >= 12_000L) return TransitionPlan.OVERLAP_LONG_MS;
        if (ms >= 6_000L) return TransitionPlan.OVERLAP_MEDIUM_MS;
        if (ms >= 1_000L) return TransitionPlan.OVERLAP_SHORT_MS;
        return -1L;
    }

    /** The curve a token names, or null for "use the configured one". */
    static FadeCurve curveOf(String token) {
        if (token == null) return null;
        for (FadeCurve c : FadeCurve.values()) {
            if (c.name().equals(token) || c.label().equals(token)) return c;
        }
        if ("EQUALPOWER".equals(token) || "EQUAL_POWER_CURVE".equals(token)
                || "EP".equals(token) || "COS".equals(token)) return FadeCurve.EQUAL_POWER;
        if ("LINEAR_CURVE".equals(token) || "LIN".equals(token)) return FadeCurve.LINEAR;
        return null;
    }

    /** The reply, shortened for one log line: newlines and runs of spaces are
     *  collapsed so a model that answered with a paragraph cannot push the rest of
     *  the transition diagnostics off the line. */
    private static String abbreviate(String raw) {
        if (raw == null) return "";
        String flat = raw.replace('\n', ' ').replace('\r', ' ').trim();
        while (flat.contains("  ")) flat = flat.replace("  ", " ");
        return flat.length() > 120 ? flat.substring(0, 120) + "…" : flat;
    }
}
