package dev.t1m3.qplayer.lyric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class QPlayerLyricScrollMotionTest {
    @Test public void matchesUserArchiveAt60_90_120Hz() throws Exception {
        JsonObject golden = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream(
            "/lyric/qplayer-scroll-golden.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        for (com.google.gson.JsonElement item : golden.getAsJsonArray("scenarios")) {
            JsonObject scenario = item.getAsJsonObject();
            int hz = scenario.get("hz").getAsInt(), frame = 0;
            QPlayerLyricScrollMotion.Spring spring = new QPlayerLyricScrollMotion.Spring(0);
            spring.retarget(-135, 0, false, false);
            for (com.google.gson.JsonElement point : scenario.getAsJsonArray("samples")) {
                JsonObject sample = point.getAsJsonObject();
                while (frame < sample.get("frame").getAsInt()) { spring.update(1.0 / hz); frame++; }
                assertEquals(sample.get("value").getAsFloat(), spring.value(), .00001f);
            }
        }
    }
    @Test public void leadingRowsMoveImmediatelyAndOnlyTrailingRowsWait() {
        assertEquals(0, QPlayerLyricScrollMotion.cascadeDelay(4, 1, 1, false), 0);
        assertEquals(0, QPlayerLyricScrollMotion.cascadeDelay(4, 4, 1, false), 0);
        assertEquals(.05, QPlayerLyricScrollMotion.cascadeDelay(4, 5, 1, false), 1e-9);
        assertEquals(.05 + .05 / 1.05, QPlayerLyricScrollMotion.cascadeDelay(4, 6, 1, false), 1e-9);
        assertEquals(0, QPlayerLyricScrollMotion.cascadeDelay(4, 7, -1, false), 0);
        assertEquals(.05, QPlayerLyricScrollMotion.cascadeDelay(4, 3, -1, false), 1e-9);
        assertEquals(0, QPlayerLyricScrollMotion.cascadeDelay(4, 10, 1, true), 0);
    }
    @Test public void repeatedTargetsNeverRestartCascade() {
        QPlayerLyricScrollMotion.Spring one = new QPlayerLyricScrollMotion.Spring(0);
        QPlayerLyricScrollMotion.Spring repeated = new QPlayerLyricScrollMotion.Spring(0);
        one.retarget(-80, .15, false, false);
        repeated.retarget(-80, .15, false, false);
        for (int i = 0; i < 240; i++) {
            repeated.retarget(-80, .15, false, false);
            assertEquals(one.update(1.0 / 120), repeated.update(1.0 / 120), 0);
        }
        assertTrue(repeated.arrived());
    }
    @Test public void stalledFrameUsesArchive50MsCap() {
        QPlayerLyricScrollMotion.Spring slow = new QPlayerLyricScrollMotion.Spring(0);
        QPlayerLyricScrollMotion.Spring cap = new QPlayerLyricScrollMotion.Spring(0);
        slow.retarget(-80, 0, false, false); cap.retarget(-80, 0, false, false);
        assertEquals(cap.update(.05), slow.update(.5), 0);
    }
    @Test public void retargetAndSeekPreserveCurrentPosition() {
        QPlayerLyricScrollMotion.Spring spring = new QPlayerLyricScrollMotion.Spring(0);
        spring.retarget(-80, 0, false, false);
        for (int i = 0; i < 15; i++) spring.update(1.0 / 120);
        float before = spring.value();
        spring.retarget(-150, 0, false, false);
        assertEquals(before, spring.value(), 0);
        assertTrue(spring.update(1.0 / 120) < before);
        before = spring.value(); spring.retarget(50, 0, false, true);
        assertEquals(before, spring.value(), 0);
        for (int i = 0; i < 600; i++) spring.update(1.0 / 120);
        assertEquals(50, spring.value(), .001f);
    }
}
