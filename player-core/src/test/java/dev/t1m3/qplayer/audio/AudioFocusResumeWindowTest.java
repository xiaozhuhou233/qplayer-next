package dev.t1m3.qplayer.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioFocusResumeWindowTest {
    @Test public void shortInterruptionAndExactFiveMinutesResumeOnlyOnce() {
        for (long elapsed : new long[] {0L, 30_000L, 299_999L, 300_000L}) {
            AudioFocusResumeWindow window = new AudioFocusResumeWindow();
            window.onLoss(10L, true, true);
            assertTrue(window.onGain(10L + elapsed));
            assertFalse(window.onGain(10L + elapsed));
        }
    }

    @Test public void longInterruptionStaysPausedEvenAfterMoreGainNotifications() {
        AudioFocusResumeWindow window = new AudioFocusResumeWindow();
        window.onLoss(0L, true, true);
        assertFalse(window.onGain(300_001L));
        assertFalse(window.isPending());
        assertFalse(window.onGain(310_000L));
    }

    @Test public void repeatedLossDoesNotRestartTheFiveMinuteClock() {
        AudioFocusResumeWindow window = new AudioFocusResumeWindow();
        window.onLoss(100L, true, true);
        window.onLoss(290_000L, false, true);
        assertFalse(window.onGain(300_101L));
    }

    @Test public void idleAndUserPausedPlaybackNeverResumeFromFocusEvents() {
        AudioFocusResumeWindow window = new AudioFocusResumeWindow();
        window.onLoss(0L, false, true);
        assertFalse(window.onGain(10L));
        window.onLoss(20L, true, true);
        window.cancel();
        window.onLoss(30L, false, true);
        assertFalse(window.onGain(40L));
    }

    @Test public void permanentLossCancelsAndNewPlaybackGetsItsOwnWindow() {
        AudioFocusResumeWindow window = new AudioFocusResumeWindow();
        window.onLoss(0L, true, true);
        window.onLoss(100L, false, false);
        assertFalse(window.onGain(200L));
        window.onLoss(500_000L, true, true);
        assertTrue(window.onGain(500_100L));
    }

    @Test public void clockMovingBackwardsDoesNotResumePlayback() {
        AudioFocusResumeWindow window = new AudioFocusResumeWindow();
        window.onLoss(100L, true, true);
        assertFalse(window.onGain(99L));
    }
}
