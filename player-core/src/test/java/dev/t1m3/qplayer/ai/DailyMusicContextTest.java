package dev.t1m3.qplayer.ai;

import java.time.LocalDate;
import org.junit.Test;
import static org.junit.Assert.*;

public class DailyMusicContextTest {
    @Test public void weatherSuccessRemainsUsableWhenNewsFails() {
        String material = "{\"weather\":{\"status\":\"date_checked\",\"source\":\"中国天气网\"},"
                + "\"music_news\":{\"status\":\"unavailable\",\"search_status\":\"联网搜索失败 · HTTP 401 · Key 无效\",\"news\":[]}}";
        String status = DailyMusicContext.status(material);
        assertTrue(status.contains("天气已校验")); assertTrue(status.contains("HTTP 401"));
        String instructions = DailyMusicContext.instructions(LocalDate.of(2026, 10, 22), "杭州", material);
        assertTrue(instructions.contains("不因 news 为空而否定天气")); assertTrue(instructions.contains(material));
    }
    @Test public void newsSuccessRemainsUsableWhenLocationFails() {
        String status = DailyMusicContext.status("{\"weather\":{\"status\":\"unavailable\",\"reason\":\"系统无法将位置转为省市\"},"
                + "\"music_news\":{\"status\":\"source_date_checked\",\"news\":[{}]}}");
        assertTrue(status.contains("系统无法将位置转为省市")); assertTrue(status.contains("音乐资讯 1 条"));
        assertFalse(status.contains("暂无可用资料"));
    }
    @Test public void rejectedNewsReportsSpecificFailureInsteadOfPretendingSearchFailed() {
        String status = DailyMusicContext.status("{\"weather\":{\"status\":\"unavailable\",\"reason\":\"无位置\"},"
                + "\"music_news\":{\"status\":\"no_usable_sources\",\"news\":[],\"missing_body\":3,\"invalid_date\":1}}");
        assertTrue(status.contains("正文缺失 3")); assertTrue(status.contains("日期无效 1"));
        assertFalse(status.contains("联网搜索失败"));
    }
}
