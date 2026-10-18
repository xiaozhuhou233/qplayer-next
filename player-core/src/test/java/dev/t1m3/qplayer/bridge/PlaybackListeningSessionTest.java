package dev.t1m3.qplayer.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackListeningSessionTest {
    @Test public void restoredPositionDoesNotCountAsHeardTimeAndStartIsOnce() {
        PlaybackListeningSession session = new PlaybackListeningSession();
        assertTrue(session.start(0, 120_000, true));
        assertFalse(session.start(50, 120_050, true));
        session.sample(2000, 122_000, true, false);
        assertEquals(2, session.seconds());
    }

    @Test public void pauseAndBufferingDoNotIncreaseDuration() {
        PlaybackListeningSession session = new PlaybackListeningSession();
        session.start(0, 0, true);
        session.sample(3000, 3000, false, false);
        session.sample(60_000, 3000, false, false);
        session.sample(60_001, 3000, true, false);
        session.sample(62_001, 5000, true, false);
        session.sample(90_001, 5000, true, false);
        assertEquals(5, session.seconds());
    }

    @Test public void forwardsAndBackwardsSeeksDoNotForgeListeningTime() {
        PlaybackListeningSession session = new PlaybackListeningSession();
        session.start(0, 0, true);
        session.sample(3000, 3000, true, false);
        session.seek(3000, 120_000);
        session.sample(4000, 120_000, true, true);
        session.sample(5000, 120_000, true, false);
        session.sample(7000, 122_000, true, false);
        session.seek(7000, 0);
        session.sample(8000, 0, true, false);
        session.sample(10_000, 2000, true, false);
        assertEquals(7, session.seconds());
    }

    @Test public void unexpectedBackendJumpsAndCallbacksBeforeStartAreNotCounted() {
        PlaybackListeningSession session = new PlaybackListeningSession();
        session.sample(1000, 1000, true, false);
        assertEquals(0, session.seconds());
        session.start(1000, 1000, true);
        session.sample(2000, 180_000, true, false);
        session.sample(3000, 181_000, true, false);
        session.sample(4000, 0, true, false);
        session.sample(5000, 1000, true, false);
        assertEquals(2, session.seconds());
    }
}
