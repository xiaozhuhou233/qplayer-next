package dev.t1m3.qplayer.ai;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Device-local time; a night period keeps one identity across midnight. */
public final class TimeVibePeriod {
    public final String key, name, direction;
    private TimeVibePeriod(String key, String name, String direction) {
        this.key = key; this.name = name; this.direction = direction;
    }
    public static TimeVibePeriod at(LocalDateTime now) {
        int hour = now.getHour(); LocalDate day = now.toLocalDate();
        if (hour >= 10 && hour < 14) return new TimeVibePeriod(day + ":10", "午间 · 10 首", "10:00–14:00，明亮舒展、轻快有精神，适合午间活动，避免一味高强度。");
        if (hour >= 14 && hour < 18) return new TimeVibePeriod(day + ":14", "午后 · 10 首", "14:00–18:00，稳定节奏与适度提神，适合专注或午后行走。");
        if (hour >= 18 && hour < 22) return new TimeVibePeriod(day + ":18", "晚间 · 10 首", "18:00–22:00，放松、温暖有律动，适合通勤后与夜晚活动。");
        return new TimeVibePeriod((hour < 10 ? day.minusDays(1) : day) + ":22", "深夜至晨间 · 10 首", "22:00–次日10:00，柔和低刺激、舒缓有层次，适合夜深及清晨，不全是催眠曲。");
    }
    public String prompt() {
        return "时段歌单：" + direction + " 根据个人样本推荐恰好10首，熟悉作品为主，保留少量新发现。"
                + "曲风相近、排序流畅，summary一句话，逐曲reason一句短语，不查询实时天气或新闻。";
    }
}
