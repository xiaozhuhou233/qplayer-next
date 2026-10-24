package dev.t1m3.qplayer.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.ZonedDateTime;
import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherFactsTest {
    private final ZonedDateTime now = ZonedDateTime.parse("2026-10-21T12:30:00+08:00[Asia/Shanghai]");
    private String fixture(String time) {
        return "{\"timezone\":\"Asia/Shanghai\",\"current\":{\"time\":\"" + time
                + "\",\"temperature_2m\":23.5,\"weather_code\":3},\"daily\":{\"time\":[\"2026-10-21\"],"
                + "\"temperature_2m_min\":[18],\"temperature_2m_max\":[26]}}";
    }
    private JsonObject parse(String json) { return JsonParser.parseString(WeatherFacts.parse(json, "杭州", now)).getAsJsonObject(); }
    @Test public void nineteenthCannotBecomeTwentyFirstWeather() {
        JsonObject result = parse(fixture("2026-10-19T12:15"));
        assertEquals("unavailable", result.get("status").getAsString());
        assertFalse(result.has("temperature_c"));
    }
    @Test public void sameDayButStaleOrFutureHoursAreRejected() {
        for (String time : new String[]{"2026-10-21T09:00", "2026-10-21T13:00", "2026-10-22T00:00"})
            assertEquals("unavailable", parse(fixture(time)).get("status").getAsString());
    }
    @Test public void currentWeatherIsLabeledAndContainsNoCoordinates() {
        JsonObject result = parse(fixture("2026-10-21T12:15"));
        assertEquals("date_checked", result.get("status").getAsString());
        assertEquals("2026-10-21", result.get("valid_on").getAsString());
        assertEquals(23.5, result.get("temperature_c").getAsDouble(), 0.01);
        assertEquals(18, result.get("today_min_c").getAsInt());
        assertEquals("Open-Meteo", result.get("source").getAsString());
        assertTrue(result.get("check").getAsString().contains("模型估算"));
        assertFalse(result.toString().contains("latitude")); assertFalse(result.toString().contains("longitude"));
    }
    @Test public void wrongTimezoneNullAndImpossibleTemperaturesCannotBeFacts() {
        for (String json : new String[]{fixture("2026-10-21T12:15").replace("Asia/Shanghai", "UTC"),
                fixture("2026-10-21T12:15").replace("23.5", "null"),
                fixture("2026-10-21T12:15").replace("23.5", "999"), "{}"})
            assertEquals("unavailable", parse(json).get("status").getAsString());
    }
    @Test public void otherDayForecastDoesNotLeakIntoTodayHighAndLow() {
        String json = fixture("2026-10-21T12:15").replace("[\"2026-10-21\"]", "[\"2026-10-19\"]");
        assertFalse(parse(json).has("today_max_c"));
    }
}
