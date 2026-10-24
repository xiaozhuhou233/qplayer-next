package dev.t1m3.qplayer.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic source/date checks and short evidence excerpts, not a claim
 * that fetching a page independently proves every statement on that page. */
public final class WebEvidence {
    private WebEvidence() {}
    public static final class Source {
        public final String title, url, publishedAt, summary, content;
        public final int authority;
        public Source(String title, String url, String publishedAt, String summary, String content, int authority) {
            this.title = safe(title); this.url = safe(url); this.publishedAt = safe(publishedAt);
            this.summary = safe(summary); this.content = safe(content); this.authority = authority;
        }
        public Source withContent(String text) { return new Source(title, url, publishedAt, summary, text, authority); }
    }
    public interface Fetcher { String fetch(String url) throws Exception; }
    static String safe(String text) { return text == null ? "" : text.trim(); }
    static final Pattern DATE = Pattern.compile("(?<!\\d)(20\\d{2})[年/.-](\\d{1,2})[月/.-](\\d{1,2})日?(?!\\d)");
    public static LocalDate date(String raw, ZoneId zone) {
        try { return OffsetDateTime.parse(raw).atZoneSameInstant(zone).toLocalDate(); } catch (Exception ignored) {}
        Matcher match = DATE.matcher(safe(raw));
        if (match.find()) try { return LocalDate.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3))); }
        catch (Exception ignored) {}
        Matcher english = Pattern.compile("(?i)(?:[A-Z]{3,9}\\s+\\d{1,2},?\\s+20\\d{2}|\\d{1,2}\\s+[A-Z]{3,9}\\s+20\\d{2})").matcher(safe(raw));
        if (english.find()) for (String pattern : new String[]{"MMMM d uuuu", "MMM d uuuu", "d MMMM uuuu", "d MMM uuuu"}) {
            try {
                return LocalDate.parse(english.group().replace(",", ""), new java.time.format.DateTimeFormatterBuilder()
                        .parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH));
            } catch (Exception ignored) {}
        }
        return null;
    }
    private static LocalDate publicationDate(String page, ZoneId zone) {
        // Explicit article metadata only; event dates and dateModified are not publication dates.
        Matcher json = Pattern.compile("(?i)[\"']datePublished[\"']\\s*:\\s*[\"']([^\"']+)").matcher(page);
        if (json.find()) { LocalDate value = date(json.group(1), zone); if (value != null) return value; }
        Matcher tags = Pattern.compile("(?is)<meta\\b[^>]*>").matcher(page);
        while (tags.find()) {
            String tag = tags.group();
            if (!tag.matches("(?is).*(?:property|name)\\s*=\\s*[\"'](?:article:published_time|datePublished|publishdate|pubdate)[\"'].*")) continue;
            Matcher content = Pattern.compile("(?i)content\\s*=\\s*[\"']([^\"']+)").matcher(tag);
            if (content.find()) { LocalDate value = date(content.group(1), zone); if (value != null) return value; }
        }
        String text = page.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]+>", " ");
        Matcher marker = Pattern.compile("(?im)(?:发布时间|发布日期|发布于|\\bPublished(?:\\s+(?:on|at))?\\b|^\\s*Date\\s*:)[：:\\s]*([^\\n]{0,90})").matcher(text);
        return marker.find() ? date(marker.group(1), zone) : null;
    }
    public static String canonicalUrl(String raw) {
        try {
            URI uri = URI.create(raw).normalize();
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) return "";
            host = host.toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".local") || host.matches("[0-9.:]+")) return "";
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            String query = uri.getRawQuery();
            if (query != null) query = query.replaceAll("(?i)(^|&)(utm_[^=&]*|spm|from|source)=[^&]*", "").replaceAll("^&+|&+$", "");
            return host + path + (query == null || query.isEmpty() ? "" : "?" + query);
        } catch (Exception ignored) { return ""; }
    }
    public static boolean weatherRequest(String query) {
        String text = safe(query).toLowerCase(Locale.ROOT);
        return text.contains("天气") || text.contains("气温") || text.contains("weather") || text.contains("温度");
    }
    private static boolean todayOnly(String query) {
        return query.contains("今天") || query.contains("今日") || query.contains("当日") || query.toLowerCase(Locale.ROOT).contains("today");
    }

    public static String organize(List<Source> sources, String request, LocalDate today, ZoneId zone, Fetcher fetcher) {
        JsonObject result = new JsonObject(); result.addProperty("as_of", today.toString()); result.addProperty("timezone", zone.getId());
        JsonArray accepted = new JsonArray(); int rejected = 0, attempts = 0; Set<String> seen = new HashSet<>();
        List<Source> ranked = new ArrayList<>(sources);
        ranked.sort((a, b) -> {
            int tier = Integer.compare(TrustedSources.rank(b.url), TrustedSources.rank(a.url));
            return tier != 0 ? tier : Integer.compare(b.authority, a.authority);
        });
        int untrusted = 0, missingBody = 0, invalidDate = 0, unrelated = 0;
        for (Source candidate : ranked) {
            if (TrustedSources.rank(candidate.url) == 0) { rejected++; untrusted++; continue; }
            String key = canonicalUrl(candidate.url);
            if (key.isEmpty() || !seen.add(key)) { rejected++; continue; }
            LocalDate published = date(candidate.publishedAt, zone);
            if (published != null && (published.isAfter(today) || published.isBefore(today.minusDays(todayOnly(request) ? 0 : 7)))) { rejected++; invalidDate++; continue; }
            String page = candidate.content;
            if (page.isEmpty() && fetcher != null && attempts < 2) {
                attempts++;
                try { page = safe(fetcher.fetch(candidate.url)); } catch (Exception ignored) {}
            }
            if (page.isEmpty()) { rejected++; missingBody++; continue; }
            // Search metadata cannot override an explicit article publication date.
            LocalDate pageDate = publicationDate(page, zone);
            if (pageDate != null) published = pageDate;
            if (published == null || published.isAfter(today) || published.isBefore(today.minusDays(todayOnly(request) ? 0 : 7))) { rejected++; invalidDate++; continue; }
            // Never use an article's publication date to certify the date of weather values.
            if (weatherRequest(request)) { rejected++; continue; }
            String excerpt = excerpt(page, request, candidate.title);
            if (excerpt.isEmpty()) { rejected++; unrelated++; continue; }
            JsonObject item = new JsonObject(); item.addProperty("id", "N" + (accepted.size() + 1));
            item.addProperty("title", clip(candidate.title, 140)); item.addProperty("url", candidate.url);
            item.addProperty("published_on", published.toString());
            item.addProperty("source", TrustedSources.name(candidate.url));
            item.addProperty("source_kind", TrustedSources.kind(candidate.url));
            item.addProperty("check", "已回读原文并核对发布日期；事件发生日期仍以正文为准，未作独立多源证实");
            item.addProperty("excerpt", excerpt); accepted.add(item);
            if (accepted.size() == 2) break;
        }
        result.add("news", accepted); result.addProperty("discarded", rejected);
        result.addProperty("excluded_untrusted", untrusted);
        result.addProperty("missing_body", missingBody); result.addProperty("invalid_date", invalidDate);
        result.addProperty("unrelated", unrelated);
        result.addProperty("source_policy", "仅官方公告与正规媒体；排除论坛、社交帖子、评论及无明确来源页面");
        result.addProperty("note", "只使用 news 中的原文片段；发表日期不等于事件发生日，预告不可说成已经发生。相互矛盾则说明争议，不合并成确定事实。");
        return result.toString();
    }

    /** Merge parallel source-constrained searches into one compact, non-repeating evidence set. */
    public static String merge(List<String> blocks, LocalDate day, ZoneId zone) {
        JsonObject out = new JsonObject(); out.addProperty("as_of", day.toString()); out.addProperty("timezone", zone.getId());
        List<JsonObject> rows = new ArrayList<>(); Set<String> statuses = new java.util.LinkedHashSet<>();
        int discarded = 0, untrusted = 0, missingBody = 0, invalidDate = 0, unrelated = 0; boolean anySearch = false;
        for (String block : blocks) try {
            JsonObject evidence = JsonParser.parseString(block).getAsJsonObject();
            String status = evidence.has("status") ? evidence.get("status").getAsString() : "unavailable";
            anySearch |= !status.equals("unavailable");
            if (evidence.has("search_status")) statuses.add(evidence.get("search_status").getAsString());
            if (evidence.has("discarded")) discarded += evidence.get("discarded").getAsInt();
            if (evidence.has("excluded_untrusted")) untrusted += evidence.get("excluded_untrusted").getAsInt();
            if (evidence.has("missing_body")) missingBody += evidence.get("missing_body").getAsInt();
            if (evidence.has("invalid_date")) invalidDate += evidence.get("invalid_date").getAsInt();
            if (evidence.has("unrelated")) unrelated += evidence.get("unrelated").getAsInt();
            for (JsonElement item : evidence.getAsJsonArray("news")) rows.add(item.getAsJsonObject());
        } catch (Exception ignored) {}
        rows.sort((a, b) -> Integer.compare(TrustedSources.rank(b.get("url").getAsString()), TrustedSources.rank(a.get("url").getAsString())));
        JsonArray news = new JsonArray(); Set<String> urls = new HashSet<>(), titles = new HashSet<>();
        for (JsonObject row : rows) {
            String url = row.get("url").getAsString();
            String title = row.get("title").getAsString().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
            if (TrustedSources.rank(url) == 0 || !urls.add(canonicalUrl(url)) || !titles.add(title)) continue;
            JsonObject copy = row.deepCopy(); copy.addProperty("id", "N" + (news.size() + 1)); news.add(copy);
            if (news.size() >= 3) break;
        }
        out.add("news", news); out.addProperty("discarded", discarded); out.addProperty("excluded_untrusted", untrusted);
        out.addProperty("missing_body", missingBody); out.addProperty("invalid_date", invalidDate);
        out.addProperty("unrelated", unrelated);
        out.addProperty("status", news.size() > 0 ? "source_date_checked" : anySearch ? "no_usable_sources" : "unavailable");
        out.addProperty("search_status", String.join("；", statuses));
        out.addProperty("note", "仅官方公告或正规媒体的近期原文；发表日期不等于事件发生日，预告不是已发生，未作独立多源证实。没有合格资料时不采用帖子补足。");
        return out.toString();
    }

    /** Rank paragraphs, then preserve original order. Omit navigation/ads/Markdown links. */
    static String excerpt(String page, String request, String title) {
        String text = page.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1").replaceAll("<[^>]+>", " ");
        String[] paragraphs = text.split("[\\r\\n]+|(?<=[。！？])");
        Set<String> terms = new HashSet<>();
        String topics = (title + " " + request).replaceAll("(?i)(?:after|before|site):\\S+|\\bOR\\b", " ")
                .replaceAll("\\d+", " ");
        Matcher words = Pattern.compile("[\\p{L}]{2,}").matcher(topics);
        while (words.find()) {
            String word = words.group().toLowerCase(Locale.ROOT);
            if (word.length() <= 20 && !word.matches("(?i)after|before|today|最新|最近|今天|今日|新闻|音乐|事件")) terms.add(word);
            if (word.matches(".*[\\p{IsHan}].*")) for (int i = 0; i + 2 <= word.length(); i++) {
                String pair = word.substring(i, i + 2);
                if (!pair.matches("最新|最近|今天|今日|新闻|音乐|事件")) terms.add(pair);
            }
        }
        List<Integer> indices = new ArrayList<>();
        final int[] scores = new int[paragraphs.length];
        Set<String> paragraphsSeen = new HashSet<>();
        for (int i = 0; i < paragraphs.length; i++) {
            String p = paragraphs[i].trim();
            if (p.length() < 20 || p.matches("(?is).*(?:cookie|隐私政策|点击登录|相关推荐|版权声明|订阅邮件|返回首页).*")) continue;
            String lower = p.toLowerCase(Locale.ROOT);
            if (!paragraphsSeen.add(lower.replaceAll("\\s+", ""))) continue;
            for (String term : terms) if (lower.contains(term)) scores[i] += term.length() >= 3 ? 3 : 1;
            if (scores[i] > 0) indices.add(i);
        }
        // Prefer topical paragraphs over date/navigation clutter, then restore reading order.
        indices.sort((a, b) -> Integer.compare(scores[b], scores[a]));
        if (indices.size() > 3) indices = new ArrayList<>(indices.subList(0, 3));
        java.util.Collections.sort(indices);
        StringBuilder out = new StringBuilder();
        for (int index : indices) {
            if (out.length() > 0) out.append('\n');
            out.append(clip(paragraphs[index].trim().replaceAll("\\s+", " "), 240));
            if (out.length() >= 600) break;
        }
        return clip(out.toString(), 650);
    }
    static String clip(String text, int max) { return text.length() <= max ? text : text.substring(0, max); }
}
