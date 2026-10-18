package dev.t1m3.qplayer.lyric;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricRefreshGateTest {
    @Test public void duplicateRequestsAreSuppressedButOtherSongsAreIndependent() {
        LyricRefreshGate gate = new LyricRefreshGate();
        assertTrue(gate.begin(1, 1000));
        assertFalse(gate.begin(1, 2000));
        assertFalse(gate.begin(1, 999999)); // still in flight, regardless of elapsed time
        assertTrue(gate.begin(2, 2000));
    }

    @Test public void failedOrIncompleteRequestCanRetryWithoutRestart() {
        LyricRefreshGate gate = new LyricRefreshGate();
        assertTrue(gate.begin(1, 1000));
        gate.finish(1, 2000, false);
        assertFalse(gate.begin(1, 31999));
        assertTrue(gate.begin(1, 32000));
    }

    @Test public void completeUpgradeIsThrottled() {
        LyricRefreshGate gate = new LyricRefreshGate();
        assertTrue(gate.begin(1, 1000));
        gate.finish(1, 2000, true);
        assertFalse(gate.begin(1, 601999));
        assertTrue(gate.begin(1, 602000));
    }
}
