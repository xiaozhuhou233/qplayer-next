package dev.t1m3.qplayer.lyric;

import com.google.gson.*;
import org.junit.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ReferenceLyricMotionTest {
    private JsonObject golden() {
        return JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream(
                "/lyric/ui-reference-golden.json"), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static double n(JsonObject o, String key) { return o.get(key).getAsDouble(); }
    @Test public void gapAndShortPhrasePhysicsMatchSuppliedJavascript() {
        for (JsonElement e : golden().getAsJsonArray("rows")) {
            JsonObject o = e.getAsJsonObject();
            ReferenceLyricMotion.Physics p = ReferenceLyricMotion.lineSpring(o.get("timed").getAsBoolean(),
                    n(o,"gap"), n(o,"remaining"), false, false);
            assertEquals(n(o,"dampingRatio"), p.dampingRatio, .000001);
            assertEquals(n(o,"response"), p.response, .000001);
        }
    }
    @Test public void rapidPhraseRetargetKeepsPositionAndVelocityLikeReference() {
        ReferenceLyricMotion.Spring s = new ReferenceLyricMotion.Spring(120);
        s.retarget(0, ReferenceLyricMotion.lineSpring(true, .35, 2, false, false), 0);
        JsonArray samples = golden().getAsJsonArray("springs");
        int sample = 0;
        for (int frame = 0; frame < 120; frame++) {
            if (frame == 13) s.retarget(-75, ReferenceLyricMotion.lineSpring(true, .7, 2, false, false), 0);
            if (frame == 27) s.retarget(-160, ReferenceLyricMotion.criticalEnvelope(.3), 0);
            s.update(1.0 / 120);
            if (sample < samples.size() && frame == samples.get(sample).getAsJsonObject().get("frame").getAsInt()) {
                JsonObject o = samples.get(sample++).getAsJsonObject();
                assertEquals(n(o,"value"), s.value, .00001);
                assertEquals(n(o,"velocity"), s.velocity, .0001);
            }
        }
        assertEquals(samples.size(), sample);
    }
    @Test public void delayedCascadeContinuesOldSpringUntilNewTargetStarts() {
        ReferenceLyricMotion.Physics p = new ReferenceLyricMotion.Physics(.9, .48);
        ReferenceLyricMotion.Spring a = new ReferenceLyricMotion.Spring(100), b = new ReferenceLyricMotion.Spring(100);
        a.retarget(0, p, 0); b.retarget(0, p, 0);
        a.update(.1); b.update(.1);
        double before = a.value;
        a.retarget(-100, p, .1);
        a.update(.05); b.update(.05);
        assertEquals(b.value, a.value, .000001);
        assertTrue(a.value < before);
        a.update(.10); b.update(.05); b.retarget(-100, p, 0); b.update(.05);
        assertEquals(b.value, a.value, .000001);
        assertEquals(b.velocity, a.velocity, .000001);
    }
    @Test public void wordLiftScaleAndTailMatchReferenceAt120Hz() {
        JsonArray samples = golden().getAsJsonArray("words");
        int sample = 0;
        for (boolean cjk : new boolean[]{false, true}) for (double duration : new double[]{.25, 1.5, 4}) {
            ReferenceLyricMotion.WordGlyph m = new ReferenceLyricMotion.WordGlyph(1, 4, duration, cjk);
            for (int frame = 0; frame <= 360; frame++) {
                m.update(frame / 120.0 - .1, 1.0 / 120, 2);
                JsonObject o = sample < samples.size() ? samples.get(sample).getAsJsonObject() : null;
                if (o != null && o.get("cjk").getAsBoolean() == cjk && n(o,"duration") == duration && n(o,"frame") == frame) {
                    sample++;
                    assertEquals(n(o,"pulse"), m.pulse, .000001);
                    assertEquals(n(o,"scale"), m.scale, .000001);
                    assertEquals(n(o,"xOffset"), m.xOffset, .000001);
                    assertEquals(n(o,"yLift"), m.yLift, .000001);
                }
            }
        }
        assertEquals(samples.size(), sample);
    }
    @Test public void openingMidSyllableDoesNotReplayTheEmphasis() {
        ReferenceLyricMotion.WordGlyph m = new ReferenceLyricMotion.WordGlyph(0, 4, 2, true);
        m.update(1.5, .016, 2);
        assertEquals(1, m.scale, 0); assertEquals(0, m.pulse, 0); assertEquals(2, m.yLift, 0);
        m.update(-.1, .016, 2);
        assertEquals(0, m.yLift, 0);
    }
    @Test public void interludeDotsCountdownAndCollapseMatchReference() {
        ReferenceLyricMotion.BreakSample s = new ReferenceLyricMotion.BreakSample();
        for (JsonElement e : golden().getAsJsonArray("breaks")) {
            JsonObject o = e.getAsJsonObject(); s.update(.5, 10, n(o,"time"));
            assertEquals(n(o,"scale"), s.scale, .000001);
            for (int i = 0; i < 3; i++) assertEquals(o.getAsJsonArray("dots").get(i).getAsDouble(), s.dots[i], .000001);
        }
    }
    @Test public void longAsciiUsesGlowAndSpecialRowsDoNotAddCascadeDelay() {
        assertTrue(ReferenceLyricMotion.longAscii("beautiful", 1));
        assertFalse(ReferenceLyricMotion.longAscii("beautiful", .9));
        assertFalse(ReferenceLyricMotion.longAscii("美丽的世界", 2));
        assertEquals(.10, ReferenceLyricMotion.cascadeDelay(3, false), .000001);
        assertEquals(0, ReferenceLyricMotion.cascadeDelay(3, true), 0);
        assertEquals(6, ReferenceLyricMotion.blurRadius(20), 0);
    }
}
