package dev.t1m3.qplayer.ai;

import java.net.URI;
import java.util.Locale;

/** Explicit editorial/first-party sources. Search-engine authority scores do not override this policy. */
public final class TrustedSources {
    private TrustedSources() {}
    private static final String[][] OFFICIAL = {
            {"grammy.com", "Recording Academy / GRAMMY"}, {"latingrammy.com", "Latin Recording Academy"},
            {"universalmusic.com", "Universal Music Group"}, {"sonymusic.com", "Sony Music"},
            {"wmg.com", "Warner Music Group"}, {"warnermusic.com", "Warner Music"},
            {"umusic.com", "Universal Music"}
    };
    private static final String[][] MEDIA = {
            {"news.cn", "新华网"}, {"xinhuanet.com", "新华网"}, {"people.com.cn", "人民网"},
            {"cctv.com", "央视网"}, {"cnr.cn", "央广网"}, {"chinanews.com.cn", "中国新闻网"},
            {"chinanews.com", "中国新闻网"}, {"reuters.com", "Reuters"}, {"apnews.com", "Associated Press"},
            {"bbc.com", "BBC"}, {"bbc.co.uk", "BBC"}, {"npr.org", "NPR"},
            {"billboard.com", "Billboard"}, {"rollingstone.com", "Rolling Stone"}, {"pitchfork.com", "Pitchfork"}
    };
    private static String[] match(String url) {
        try {
            if (WebEvidence.canonicalUrl(url).isEmpty()) return null;
            URI uri = URI.create(url);
            if (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = (uri.getPath() == null ? "" : uri.getPath()).toLowerCase(Locale.ROOT);
            // Exclude community sections even if hosted on an otherwise editorial domain.
            if ((host + path).matches(".*(?:forum|bbs|comment|community|/users?/|/blogs?/|/posts?/|/threads?/).*")) return null;
            if (path.isEmpty() || path.equals("/") || path.matches("/(news|music|culture|entertainment|charts)/?")) return null;
            for (String[] item : OFFICIAL) if (host.equals(item[0]) || host.endsWith("." + item[0]))
                return new String[]{item[1], "official", "2"};
            for (String[] item : MEDIA) if (host.equals(item[0]) || host.endsWith("." + item[0]))
                return new String[]{item[1], "editorial", "1"};
        } catch (Exception ignored) {}
        return null;
    }
    public static int rank(String url) { String[] source = match(url); return source == null ? 0 : Integer.parseInt(source[2]); }
    public static String name(String url) { String[] source = match(url); return source == null ? "" : source[0]; }
    public static String kind(String url) { String[] source = match(url); return source == null ? "" : source[1]; }
}
