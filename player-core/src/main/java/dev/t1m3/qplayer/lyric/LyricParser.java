package dev.t1m3.qplayer.lyric;

import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Dispatcher: pick the right backend by file extension, read the file with
 * UTF-8 first and fall back to GBK (common encoding for LRC files of Chinese
 * songs). Translation / romaji sidecars are merged into the main line list.
 */
public final class LyricParser {

    private LyricParser() {}

    public static List<LyricLine> parse(String mainPath,
                                        String translationPath,
                                        String romajiPath) {
        List<LyricLine> lines = parseFile(mainPath);
        if (lines.isEmpty()) return lines;
        if (translationPath != null) attachSidecar(lines, translationPath, true);
        if (romajiPath != null) attachSidecar(lines, romajiPath, false);
        return lines;
    }

    /**
     * Compose lyrics from Netease's {@code /song/lyric/v1} text payloads.
     * Keeps YRC over the overlapping parts of LRC when available (YRC is
     * syllable-level and enables AMLL-style per-syllable rendering), then uses
     * non-overlapping LRC lines only to fill missing lyric text.
     */
    public static List<LyricLine> fromNeteaseStrings(String yrc, String lrc,
                                                       String tlyric, String romalrc) {
        List<LyricLine> yrcLines = yrc != null && !yrc.isEmpty()
                ? YrcParser.parse(yrc) : Collections.<LyricLine>emptyList();
        List<LyricLine> lrcLines = lrc != null && !lrc.isEmpty()
                ? LrcParser.parse(lrc) : Collections.<LyricLine>emptyList();
        List<LyricLine> base;
        if (yrcLines.isEmpty() && lrcLines.isEmpty()) {
            return Collections.emptyList();
        }
        if (yrcLines.isEmpty()) {
            base = lrcLines;
        } else if (lrcLines.isEmpty()) {
            base = yrcLines;
        } else {
            base = supplementTimedLines(yrcLines, lrcLines);
        }
        if (base.isEmpty()) return base;
        if (tlyric != null && !tlyric.isEmpty()) attachSidecarContent(base, tlyric, true);
        if (romalrc != null && !romalrc.isEmpty()) attachSidecarContent(base, romalrc, false);
        return base;
    }

    /**
     * Keep every original word timestamp. LRC often has extra credits, blank
     * markers or different line breaks; a larger line count is not evidence
     * that its line-only timing is better than YRC.
     *
     * <p>Ⓜ 2026-10-01: the old test accepted an LRC stamp only inside
     * {@code [start-500, end)} with EXACTLY equal text. Real payloads drift
     * further — LRC marks the line's musical entry seconds before the first
     * sung word, and the two sources differ in punctuation, width and case —
     * so the same line slipped through as a SECOND, plain copy that sorted
     * ahead of its YRC twin. That plain copy is what the renderer then swept
     * at uniform speed: 「部分歌的歌词无法遵循整个句子的进度」.
     *
     * <p>A candidate is now a duplicate when EITHER its stamp falls inside a
     * YRC line's widened window ({@code [start-2500, end+1500)} — stamp drift
     * and trailing instrumentation) OR its normalized text (punctuation,
     * width, case stripped) matches a YRC line starting within 5s — typography
     * drift. A genuine LRC-only line in a gap between YRC lines matches
     * neither and still survives to fill the text. YRC is the superset of the
     * song's sung lines, so biasing toward coverage is the safe direction: a
     * dropped duplicate loses nothing, a kept one visibly breaks the timing.
     */
    static List<LyricLine> supplementTimedLines(List<LyricLine> yrc, List<LyricLine> lrc) {
        List<LyricLine> result = new java.util.ArrayList<>(yrc);
        final long LEAD_MS = 2500L;
        final long TAIL_MS = 1500L;
        final long TEXT_MATCH_MS = 5000L;
        for (LyricLine candidate : lrc) {
            String text = candidate.text().trim();
            if (text.isEmpty()) continue;
            long start = candidate.startMs();
            boolean covered = false;
            for (LyricLine timed : yrc) {
                if (candidate.vocalChannel != timed.vocalChannel) continue;
                if (start >= timed.startMs() - LEAD_MS && start < timed.endMs() + TAIL_MS) {
                    covered = true;
                    break;
                }
                if (Math.abs(start - timed.startMs()) <= TEXT_MATCH_MS
                        && normalizeForMatch(text).equals(normalizeForMatch(timed.text().trim()))) {
                    covered = true;
                    break;
                }
            }
            if (!covered) result.add(candidate);
        }
        result.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return result;
    }

    /** Letters and digits only, case-folded after NFKC width folding: 「我看见：」 vs
     *  「我看见」, "Shape Of You" vs "Shape of You" and full-width variants all
     *  normalize equal. */
    private static String normalizeForMatch(String text) {
        String folded = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(folded.length());
        folded.codePoints()
                .filter(Character::isLetterOrDigit)
                .map(Character::toLowerCase)
                .forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** Same logic as {@link #attachSidecar} but from a raw content string. */
    private static void attachSidecarContent(List<LyricLine> mainLines, String content, boolean isTranslation) {
        List<LyricLine> sidecar = LrcParser.parse(content);
        if (sidecar.isEmpty()) return;
        // YRC can contain a background-vocal line at the same timestamp as the
        // main line. Matching against every line lets a translation drift onto
        // that background line; sidecars belong to the main vocal timeline only.
        List<Integer> mainIndexes = new java.util.ArrayList<>();
        for (int i = 0; i < mainLines.size(); i++) {
            if (mainLines.get(i).vocalChannel == LyricLine.VocalChannel.MAIN) mainIndexes.add(i);
        }
        if (mainIndexes.isEmpty()) return;
        int j = 0;
        for (LyricLine s : sidecar) {
            long start = s.startMs();
            while (j + 1 < mainIndexes.size()
                    && Math.abs(mainLines.get(mainIndexes.get(j + 1)).startMs() - start)
                    < Math.abs(mainLines.get(mainIndexes.get(j)).startMs() - start)) {
                j++;
            }
            String text = s.text();
            int target = mainIndexes.get(j);
            if (isTranslation) mainLines.get(target).translation = text;
            else mainLines.get(target).romaji = text;
        }
    }

    public static List<LyricLine> parseFile(String path) {
        if (path == null) return Collections.emptyList();
        String content = readWithEncodingFallback(path);
        if (content == null) return Collections.emptyList();
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ttml")) return TtmlParser.parse(content);
        if (lower.endsWith(".lys")) return LysParser.parse(content);
        if (lower.endsWith(".qrc")) return QrcParser.parse(content);
        if (lower.endsWith(".yrc")) return YrcParser.parse(content);
        if (lower.endsWith(".eslrc")) return EsLrcParser.parse(content);
        // Default: standard LRC (with optional A2 inline <...> extension).
        return LrcParser.parse(content);
    }

    /**
     * Sidecar is always LRC-style (single line per timestamp). Match each
     * sidecar entry to the nearest main line by start time and attach as
     * translation or romaji.
     */
    private static void attachSidecar(List<LyricLine> mainLines, String path, boolean isTranslation) {
        List<LyricLine> sidecar = parseFile(path);
        if (sidecar.isEmpty()) return;
        int j = 0;
        for (LyricLine s : sidecar) {
            long start = s.startMs();
            // Advance j while the next main line is closer to `start`.
            while (j + 1 < mainLines.size()
                    && Math.abs(mainLines.get(j + 1).startMs() - start)
                    < Math.abs(mainLines.get(j).startMs() - start)) {
                j++;
            }
            String text = s.text();
            if (isTranslation) mainLines.get(j).translation = text;
            else mainLines.get(j).romaji = text;
        }
    }

    private static String readWithEncodingFallback(String path) {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(path));
            // UTF-8 first; fall back to GBK if it produces replacement chars in
            // the result (a quick heuristic that catches mis-decoded CJK).
            String utf8 = new String(bytes, StandardCharsets.UTF_8);
            if (utf8.indexOf('�') < 0) return utf8;
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception e) {
                return utf8;
            }
        } catch (IOException e) {
            Logger.warn("LyricParser: failed to read {}: {}", path, e.getMessage());
            return null;
        }
    }
}
