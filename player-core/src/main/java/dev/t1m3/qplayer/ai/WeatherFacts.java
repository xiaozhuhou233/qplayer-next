package dev.t1m3.qplayer.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Locale;

/** Structured current-day weather; search-page publication dates are not weather dates.
 * Open-Meteo current conditions are a model estimate, not a station observation.
 */
public final class WeatherFacts {
    private WeatherFacts() {}
    public static String unavailable(String reason, ZonedDateTime now) {
        JsonObject out = new JsonObject(); out.addProperty("status", "unavailable");
        out.addProperty("valid_on", now.toLocalDate().toString()); out.addProperty("reason", reason);
        return out.toString();
    }
    public static String lookup(double latitude, double longitude, String city, ZonedDateTime now, int timeout) {
        try {
            double lat = Math.rint(latitude * 100) / 100, lon = Math.rint(longitude * 100) / 100;
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
                return unavailable("位置不可用", now);
            String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f", lat, lon)
                    + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min"
                    + "&forecast_days=1&timezone=" + URLEncoder.encode(now.getZone().getId(), "UTF-8");
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
                connection.setRequestProperty("Accept", "application/json");
                if (connection.getResponseCode() != 200) return unavailable("天气服务暂不可用", now);
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    for (int n; (n = in.read(buffer)) >= 0;) {
                        out.write(buffer, 0, n); if (out.size() > 100000) return unavailable("天气响应异常", now);
                    }
                    return parse(new String(out.toByteArray(), StandardCharsets.UTF_8), city, now);
                }
            } finally { connection.disconnect(); }
        } catch (Exception ignored) { return unavailable("天气服务未响应", now); }
    }
    public static String parse(String json, String city, ZonedDateTime now) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!now.getZone().getId().equals(root.get("timezone").getAsString())) return unavailable("天气时区不匹配", now);
            JsonObject current = root.getAsJsonObject("current"), daily = root.getAsJsonObject("daily");
            LocalDateTime valid = LocalDateTime.parse(current.get("time").getAsString());
            long age = Duration.between(valid.atZone(now.getZone()), now).toMinutes();
            if (!valid.toLocalDate().equals(now.toLocalDate()) || age < -20 || age > 120)
                return unavailable("已丢弃过期天气（有效时间不是当前两小时内）", now);
            double temperature = current.get("temperature_2m").getAsDouble();
            if (!validTemperature(temperature)) return unavailable("温度数据异常", now);
            JsonObject out = new JsonObject(); out.addProperty("status", "date_checked");
            out.addProperty("place", city == null || city.trim().isEmpty() ? "系统大致位置附近" : city);
            out.addProperty("valid_on", now.toLocalDate().toString()); out.addProperty("updated_at", valid.toString());
            out.addProperty("timezone", now.getZone().getId()); out.addProperty("condition", condition(current.get("weather_code").getAsInt()));
            out.addProperty("temperature_c", temperature);
            JsonArray days = daily.getAsJsonArray("time");
            for (int i = 0; i < days.size(); i++) if (days.get(i).getAsString().equals(now.toLocalDate().toString())) {
                double low = daily.getAsJsonArray("temperature_2m_min").get(i).getAsDouble();
                double high = daily.getAsJsonArray("temperature_2m_max").get(i).getAsDouble();
                if (validTemperature(low) && validTemperature(high) && low <= high) {
                    out.addProperty("today_min_c", low); out.addProperty("today_max_c", high);
                } break;
            }
            out.addProperty("source", "Open-Meteo"); out.addProperty("url", "https://open-meteo.com/");
            out.addProperty("check", "接口有效日期、时区和两小时新鲜度已校验；当前值为天气模型估算，日高低温为预报");
            return out.toString();
        } catch (Exception ignored) { return unavailable("天气响应缺少有效日期或数值", now); }
    }
    private static boolean validTemperature(double value) { return Double.isFinite(value) && value >= -90 && value <= 65; }
    static String condition(int code) {
        if (code == 0) return "晴";
        if (code >= 1 && code <= 3) return "多云或阴";
        if (code == 45 || code == 48) return "雾";
        if (code >= 51 && code <= 57) return "毛毛雨";
        if (code >= 61 && code <= 67) return "雨";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "雪";
        if (code >= 80 && code <= 82) return "阵雨";
        if (code >= 95 && code <= 99) return "雷雨";
        return "天气状况未提供";
    }
}
