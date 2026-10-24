package dev.t1m3.qplayer.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChinaWeatherFactsTest {
    @Test public void recentCrossMidnightObservationKeepsItsActualDate() {
        ZonedDateTime midnight = ZonedDateTime.parse("2026-10-22T00:15:00+08:00[Asia/Shanghai]");
        String input = page("杭州").replace("202610211200", "202610212300").replace("\"12\"", "\"23\"");
        JsonObject facts = object(ChinaWeatherFacts.parseChinaWeather(input, "101210101", "浙江", "杭州", midnight));
        assertEquals("date_checked", facts.get("status").getAsString());
        assertEquals("2026-10-21", facts.get("valid_on").getAsString());
        assertEquals("unavailable", object(ChinaWeatherFacts.parseChinaWeather(input.replace("202610212300", "202610202300"),
                "101210101", "浙江", "杭州", midnight)).get("status").getAsString());
    }
    @Test public void missingPrimaryProvinceStillTriesNmc() {
        String result = ChinaWeatherFacts.lookup("浙江省", "杭州市", "", now, 10000, (url, timeout) -> {
            if (url.endsWith("/china.html")) return "{}";
            if (url.equals("https://www.nmc.cn/rest/province")) return "[{\"code\":\"AZJ\",\"name\":\"浙江省\"}]";
            if (url.endsWith("/rest/province/AZJ")) return "[{\"code\":\"HIieJ\",\"city\":\"杭州\"}]";
            if (url.endsWith("/rest/real/HIieJ")) return nmc("杭州");
            throw new java.io.IOException("Unexpected URL");
        });
        assertEquals("中央气象台", object(result).get("source").getAsString());
    }
    @Test public void primaryTimeoutLeavesBudgetForFallback() {
        String result = ChinaWeatherFacts.lookup("浙江省", "杭州市", "", now, 1000, (url, timeout) -> {
            if (url.contains("weather.com.cn")) { Thread.sleep(timeout + 10L); throw new java.net.SocketTimeoutException(); }
            if (url.endsWith("/rest/province")) return "[{\"code\":\"AZJ\",\"name\":\"浙江省\"}]";
            if (url.endsWith("/rest/province/AZJ")) return "[{\"code\":\"HIieJ\",\"city\":\"杭州\"}]";
            return nmc("杭州");
        });
        assertEquals("中央气象台", object(result).get("source").getAsString());
    }
    private final ZonedDateTime now = ZonedDateTime.parse("2026-10-21T12:30:00+08:00[Asia/Shanghai]");
    private String page(String place) {
        return "<script>var observe24h_data = {\"od\":{\"od0\":\"202610211200\",\"od1\":\"" + place
                + "\",\"od2\":[{\"od21\":\"12\",\"od22\":\"20.4\"},{\"od21\":\"11\",\"od22\":\"9999\"}]}};</script>"
                + "<input id=\"fc_3h_internal_update_time\" value=\"2026-10-21 08:00\"/>"
                + "<script>var hour3data={\"1d\":[\"21日11时,d01,多云,19℃\",\"21日14时,d01,晴,22℃\"]};</script>";
    }
    private String nmc(String place) {
        return "{\"station\":{\"province\":\"浙江省\",\"city\":\"" + place + "\",\"url\":\"/publish/forecast/AZJ/hangzhou.html\"},"
                + "\"publish_time\":\"2026-10-21 12:15\",\"weather\":{\"temperature\":21.2,\"info\":\"晴\"}}";
    }
    private JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    @Test public void officialObservationAndFutureForecastAreKeptSeparate() {
        JsonObject facts = object(ChinaWeatherFacts.parseChinaWeather(page("杭州"), "101210101", "浙江省", "杭州市", now));
        assertEquals("date_checked", facts.get("status").getAsString());
        assertEquals("中国天气网", facts.get("source").getAsString());
        assertEquals(20.4, facts.get("temperature_c").getAsDouble(), .01);
        assertEquals("forecast", facts.get("condition_kind").getAsString());
        assertEquals("2026-10-21T14:00", facts.get("forecast_valid_at").getAsString());
        assertTrue(facts.get("condition").getAsString().contains("未来3小时预报"));
        assertFalse(facts.toString().contains("latitude"));
    }
    @Test public void oldDatesStaleHoursInvalidTemperatureAndWrongCityAreRejected() {
        for (String input : new String[]{page("杭州").replace("202610211200", "202610191200"),
                page("杭州").replace("202610211200", "202610210800"), page("杭州").replace("20.4", "9999"),
                page("北京"), page("杭州").replace("\"12\"", "\"10\""), "<html>10月21日 今天20度，未提供年份</html>"}) {
            JsonObject facts = object(ChinaWeatherFacts.parseChinaWeather(input, "101210101", "浙江省", "杭州", now));
            assertEquals("unavailable", facts.get("status").getAsString()); assertFalse(facts.has("temperature_c"));
        }
    }
    @Test public void staleForecastDoesNotInvalidateFreshObservationOrMasqueradeAsCurrentCondition() {
        String input = page("杭州").replace("2026-10-21 08:00", "2026-10-19 08:00");
        JsonObject facts = object(ChinaWeatherFacts.parseChinaWeather(input, "101210101", "浙江", "杭州", now));
        assertEquals("date_checked", facts.get("status").getAsString()); assertFalse(facts.has("condition"));
    }
    @Test public void forecastResolvesMonthBoundaryFromFullIssueDate() {
        String input = page("杭州").replace("202610211200", "202610312300").replace("\"12\"", "\"23\"")
                .replace("2026-10-21 08:00", "2026-10-31 20:00").replace("21日14时", "1日02时");
        ZonedDateTime endOfMonth = ZonedDateTime.parse("2026-10-31T23:30:00+08:00[Asia/Shanghai]");
        JsonObject facts = object(ChinaWeatherFacts.parseChinaWeather(input, "101210101", "浙江", "杭州", endOfMonth));
        assertEquals("2026-11-01T02:00", facts.get("forecast_valid_at").getAsString());
    }
    @Test public void cityAndDistrictIdsComeFromOfficialCatalogAndNeverFromGuessing() {
        List<String> urls = new ArrayList<>();
        String result = ChinaWeatherFacts.lookup("浙江省", "杭州市", "余杭区", now, 10000, (url, timeout) -> {
            urls.add(url);
            if (url.endsWith("/china.html")) return "{\"10121\":\"浙江\"}";
            if (url.endsWith("/provshi/10121.html")) return "{\"01\":\"杭州\"}";
            if (url.endsWith("/station/1012101.html")) return "{\"01\":\"杭州\",\"06\":\"余杭\"}";
            if (url.endsWith("/weather1d/101210106.shtml")) return page("余杭");
            throw new java.io.IOException("Unexpected URL");
        });
        assertEquals("date_checked", object(result).get("status").getAsString());
        assertEquals(4, urls.size()); assertTrue(object(result).get("place").getAsString().contains("余杭"));
    }
    @Test public void municipalityCodeOrderingUsesOfficialCountyMapping() {
        String result = ChinaWeatherFacts.lookup("北京市", "北京市", "海淀区", now, 10000, (url, timeout) -> {
            if (url.endsWith("/china.html")) return "{\"10101\":\"北京\"}";
            if (url.endsWith("/provshi/10101.html")) return "{\"00\":\"北京\"}";
            if (url.endsWith("/station/1010100.html")) return "{\"01\":\"北京\",\"02\":\"海淀\"}";
            if (url.endsWith("/weather1d/101010200.shtml")) return page("海淀");
            throw new java.io.IOException("Unexpected municipality ID");
        });
        assertEquals("date_checked", object(result).get("status").getAsString());
    }
    @Test public void fallbackUsesNmcCatalogAndActualStationData() {
        String result = ChinaWeatherFacts.lookup("浙江省", "杭州市", "上城区", now, 10000, (url, timeout) -> {
            if (url.equals("https://www.nmc.cn/rest/province")) return "[{\"code\":\"AZJ\",\"name\":\"浙江省\"}]";
            if (url.endsWith("/rest/province/AZJ")) return "[{\"code\":\"HIieJ\",\"city\":\"杭州\"}]";
            if (url.endsWith("/rest/real/HIieJ")) return nmc("杭州");
            throw new java.io.IOException("Primary unavailable");
        });
        JsonObject facts = object(result); assertEquals("中央气象台", facts.get("source").getAsString());
        assertEquals("observation", facts.get("condition_kind").getAsString()); assertEquals("晴", facts.get("condition").getAsString());
    }
    @Test public void nmcRejectsOldDataWrongProvinceAndSentinelValues() {
        for (String input : new String[]{nmc("杭州").replace("2026-10-21", "2026-10-19"), nmc("北京"),
                nmc("杭州").replace("浙江省", "江苏省"), nmc("杭州").replace("21.2", "9999.0"), "{\"station\":\"\",\"weather\":\"\"}"})
            assertEquals("unavailable", object(ChinaWeatherFacts.parseNmc(input, "浙江", "杭州", now)).get("status").getAsString());
    }
    @Test public void unavailableOfficialSourcesDoNotFallBackToPostsOrInventLocations() {
        String result = ChinaWeatherFacts.lookup("浙江省", "不存在的市", "", now, 10000, (url, timeout) -> { throw new java.io.IOException("Offline"); });
        assertEquals("unavailable", object(result).get("status").getAsString());
        assertFalse(object(result).has("temperature_c"));
    }
}
