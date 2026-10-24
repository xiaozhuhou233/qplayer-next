package dev.t1m3.qplayer.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public first-party weather pages only; no search snippets, guessed station IDs or missing-year dates. */
public final class ChinaWeatherFacts {
    private ChinaWeatherFacts() {}
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final String ROOT = "https://www.weather.com.cn";
    public interface Transport { String get(String url, int timeout) throws Exception; }
    public static String lookup(String province, String city, String district, ZonedDateTime now, int timeout) {
        return lookup(province, city, district, now, timeout, ChinaWeatherFacts::get);
    }
    static String lookup(String province, String city, String district, ZonedDateTime now, int timeout, Transport transport) {
        if (normalize(province).isEmpty() || normalize(city).isEmpty())
            return WeatherFacts.unavailable("未获得可匹配的省市，不推测天气地点", now);
        final long deadline = System.nanoTime() + Math.max(1000, timeout) * 1_000_000L;
        final long primaryDeadline = System.nanoTime() + Math.max(600, timeout * 3L / 5L) * 1_000_000L;
        final boolean[] primary = {true};
        Transport bounded = (url, ignored) -> {
            long remaining = ((primary[0] ? primaryDeadline : deadline) - System.nanoTime()) / 1_000_000L;
            if (remaining < 100) throw new java.io.IOException("Weather deadline");
            return transport.get(url, (int) Math.min(3500, remaining));
        };
        String primaryReason = "请求未完成";
        try {
            JsonObject provinces = object(bounded.get(ROOT + "/data/city3jdata/china.html", timeout));
            String p = pick(provinces, province);
            if (p.isEmpty()) throw new java.io.IOException("未匹配省份");
            JsonObject cities = object(bounded.get(ROOT + "/data/city3jdata/provshi/" + p + ".html", timeout));
            String c = pick(cities, city);
            if (c.isEmpty()) throw new java.io.IOException("No matching city");
            JsonObject stations = object(bounded.get(ROOT + "/data/city3jdata/station/" + p + c + ".html", timeout));
            String s = pick(stations, district);
            if (s.isEmpty()) s = pick(stations, city);
            if (s.isEmpty()) throw new java.io.IOException("No matching station");
            String id = c.equals("00") ? p + s + "00" : p + c + s;
            String expected = stations.get(s).getAsString();
            String page = bounded.get(ROOT + "/weather1d/" + id + ".shtml", timeout);
            String facts = parseChinaWeather(page, id, province, expected, now);
            if (available(facts)) return facts;
            primaryReason = object(facts).get("reason").getAsString();
        } catch (Exception failure) { primaryReason = failure.getClass().getSimpleName(); }
        // Central Meteorological Observatory is the fallback, rather than posts or an unlabeled model.
        primary[0] = false; // Reserve time for fallback even when the primary host stalls.
        try {
            JsonArray provinces = JsonParser.parseString(bounded.get("https://www.nmc.cn/rest/province", timeout)).getAsJsonArray();
            String p = "";
            for (JsonElement item : provinces) if (normalize(item.getAsJsonObject().get("name").getAsString()).equals(normalize(province)))
                p = item.getAsJsonObject().get("code").getAsString();
            if (!p.matches("[A-Za-z0-9]+")) throw new java.io.IOException("No matching province");
            JsonArray stations = JsonParser.parseString(bounded.get("https://www.nmc.cn/rest/province/" + p, timeout)).getAsJsonArray();
            JsonObject selected = null;
            for (String name : new String[]{district, city}) {
                if (normalize(name).isEmpty()) continue;
                for (JsonElement item : stations) {
                    JsonObject station = item.getAsJsonObject();
                    if (normalize(station.get("city").getAsString()).equals(normalize(name))) { selected = station; break; }
                }
                if (selected != null) break;
            }
            if (selected == null) throw new java.io.IOException("No matching station");
            String id = selected.get("code").getAsString();
            if (!id.matches("[A-Za-z0-9]+")) throw new java.io.IOException("Invalid station");
            String facts = parseNmc(bounded.get("https://www.nmc.cn/rest/real/" + id, timeout), province, selected.get("city").getAsString(), now);
            if (available(facts)) return facts;
            return WeatherFacts.unavailable("中国天气网：" + primaryReason + "；中央气象台：" + object(facts).get("reason").getAsString(), now);
        } catch (Exception failure) { return WeatherFacts.unavailable("中国天气网：" + primaryReason + "；中央气象台：" + failure.getClass().getSimpleName(), now); }
    }
    private static boolean available(String facts) { return object(facts).get("status").getAsString().equals("date_checked"); }
    private static JsonObject object(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    static String normalize(String value) {
        return WebEvidence.safe(value).replaceAll("（[^）]*）|\\([^)]*\\)", "")
                .replaceAll("(?:壮族自治区|维吾尔自治区|回族自治区|自治区|特别行政区|省|市|地区|盟|县|区)$", "");
    }
    private static String pick(JsonObject values, String wanted) {
        if (normalize(wanted).isEmpty()) return "";
        for (Map.Entry<String, JsonElement> item : values.entrySet())
            if (item.getKey().matches("\\d+") && normalize(item.getValue().getAsString()).equals(normalize(wanted))) return item.getKey();
        return "";
    }
    public static String parseChinaWeather(String page, String id, String province, String expected, ZonedDateTime now) {
        try {
            if (!id.matches("\\d{9}")) return WeatherFacts.unavailable("天气城市编号不合法", now);
            JsonObject observation = assigned(page, "observe24h_data").getAsJsonObject("od");
            String place = observation.get("od1").getAsString();
            if (!normalize(place).equals(normalize(expected))) return WeatherFacts.unavailable("天气站点不匹配", now);
            LocalDateTime updated = LocalDateTime.parse(observation.get("od0").getAsString(), DateTimeFormatter.ofPattern("uuuuMMddHHmm"));
            JsonObject latest = observation.getAsJsonArray("od2").get(0).getAsJsonObject();
            if (latest.get("od21").getAsInt() != updated.getHour()) return WeatherFacts.unavailable("天气实况时间不一致", now);
            JsonObject facts = current(province + " " + place, updated, latest.get("od22").getAsDouble(), now, "中国天气网", ROOT + "/weather1d/" + id + ".shtml");
            if (!facts.get("status").getAsString().equals("date_checked")) return facts.toString();
            facts.addProperty("observation_kind", "城市站点天气实况");
            // The page supplies observations and forecasts separately; do not call a forecast a current observation.
            try { addForecast(facts, page, now); } catch (Exception ignored) {}
            return facts.toString();
        } catch (Exception ignored) { return WeatherFacts.unavailable("中国天气网数据缺少完整日期或有效实况", now); }
    }
    private static void addForecast(JsonObject facts, String page, ZonedDateTime now) {
        Matcher issue = Pattern.compile("id=[\"']fc_3h_internal_update_time[\"'][^>]*value=[\"']([^\"']+)").matcher(page);
        if (!issue.find()) return;
        LocalDateTime issued = LocalDateTime.parse(issue.group(1), DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm"));
        ZonedDateTime localNow = now.withZoneSameInstant(BEIJING);
        long age = Duration.between(issued.atZone(BEIJING), localNow).toMinutes();
        if (age < -20 || age > 1440) return;
        JsonArray hours = assigned(page, "hour3data").getAsJsonArray("1d");
        for (JsonElement item : hours) {
            String[] row = item.getAsString().split(",");
            if (row.length < 4 || !row[2].matches("[\\p{IsHan}转到有伴局部\\s]{1,25}")) continue;
            Matcher time = Pattern.compile("(\\d{1,2})日(\\d{1,2})时").matcher(row[0]);
            if (!time.matches()) continue;
            for (int offset = 0; offset < 3; offset++) {
                LocalDate date = issued.toLocalDate().plusDays(offset);
                if (date.getDayOfMonth() != Integer.parseInt(time.group(1))) continue;
                LocalDateTime valid = date.atTime(Integer.parseInt(time.group(2)), 0);
                long ahead = Duration.between(localNow, valid.atZone(BEIJING)).toMinutes();
                if (ahead < 0 || ahead > 180) continue;
                facts.addProperty("condition", row[2] + "（未来3小时预报）");
                facts.addProperty("condition_kind", "forecast");
                facts.addProperty("forecast_valid_at", valid.toString());
                facts.addProperty("forecast_issued_at", issued.toString());
                return;
            }
        }
    }
    public static String parseNmc(String json, String province, String expected, ZonedDateTime now) {
        try {
            JsonObject root = object(json), station = root.getAsJsonObject("station"), weather = root.getAsJsonObject("weather");
            if (!normalize(station.get("province").getAsString()).equals(normalize(province))
                    || !normalize(station.get("city").getAsString()).equals(normalize(expected)))
                return WeatherFacts.unavailable("中央气象台站点不匹配", now);
            String path = station.get("url").getAsString();
            if (!path.matches("/publish/forecast/[A-Za-z0-9/_-]+\\.html")) return WeatherFacts.unavailable("天气来源链接异常", now);
            LocalDateTime updated = LocalDateTime.parse(root.get("publish_time").getAsString(), DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm"));
            JsonObject facts = current(province + " " + expected, updated, weather.get("temperature").getAsDouble(), now, "中央气象台", "https://www.nmc.cn" + path);
            String condition = weather.get("info").getAsString();
            if (facts.get("status").getAsString().equals("date_checked") && condition.matches("[\\p{IsHan}\\s]{1,25}")) {
                facts.addProperty("condition", condition); facts.addProperty("condition_kind", "observation");
            }
            return facts.toString();
        } catch (Exception ignored) { return WeatherFacts.unavailable("中央气象台缺少完整日期或有效实况", now); }
    }
    private static JsonObject current(String place, LocalDateTime updated, double temperature, ZonedDateTime now, String source, String url) {
        ZonedDateTime localNow = now.withZoneSameInstant(BEIJING);
        long age = Duration.between(updated.atZone(BEIJING), localNow).toMinutes();
        if (age < -20 || age > 120
                || !Double.isFinite(temperature) || temperature < -90 || temperature > 65)
            return object(WeatherFacts.unavailable("已丢弃过期、未来或异常的权威天气数据", now));
        JsonObject out = new JsonObject(); out.addProperty("status", "date_checked");
        out.addProperty("place", place); out.addProperty("valid_on", updated.toLocalDate().toString());
        out.addProperty("as_of", localNow.toString());
        out.addProperty("updated_at", updated.toString()); out.addProperty("timezone", BEIJING.getId());
        out.addProperty("temperature_c", temperature); out.addProperty("source", source); out.addProperty("url", url);
        out.addProperty("check", "官方城市站点、省市、完整日期及两小时新鲜度已校验；实况与预报分开标注");
        return out;
    }
    /** Extract data literals only, never execute website JavaScript. */
    private static JsonObject assigned(String page, String name) {
        Matcher marker = Pattern.compile("\\bvar\\s+" + Pattern.quote(name) + "\\s*=\\s*\\{").matcher(page);
        if (!marker.find()) throw new IllegalArgumentException("Missing weather data");
        int start = marker.end() - 1, depth = 0; boolean quoted = false, escaped = false;
        for (int i = start; i < page.length(); i++) {
            char c = page.charAt(i);
            if (quoted) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') quoted = false; }
            else if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return object(page.substring(start, i + 1));
        }
        throw new IllegalArgumentException("Incomplete weather data");
    }
    private static String get(String url, int timeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            int connectTimeout = Math.min(1500, Math.max(100, timeout / 3));
            connection.setConnectTimeout(connectTimeout); connection.setReadTimeout(Math.max(100, timeout - connectTimeout)); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "QPlayer/1.0 (weather attribution: weather.com.cn, nmc.cn)");
            connection.setRequestProperty("Accept", "application/json,text/html");
            if (connection.getResponseCode() != 200) throw new java.io.IOException("Weather unavailable");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) >= 0;) { out.write(buffer, 0, n); if (out.size() > 2_000_000) throw new java.io.IOException("Weather too large"); }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
