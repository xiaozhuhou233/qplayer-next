package dev.t1m3.qplayer.audio.automix;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoMixHandoffTest {
    @Test public void scrubIntoPrefixSeeksBeforeColdStartAndAlignsBeforeCue() {
        AutoMixHandoff gate = new AutoMixHandoff(10000, 3000, 0);
        assertEquals(1000, gate.initialSeek(8000));
        assertEquals(AutoMixHandoff.WAIT, gate.sample(100, 8100, 0));
        gate.seekComplete(200, 1000);
        assertEquals(AutoMixHandoff.WAIT, gate.sample(232, 8232, 1032));
        assertEquals(1464, gate.sample(264, 8264, 1064));
        gate.seekComplete(464, 1464);
        gate.sample(496, 8496, 1496);
        assertEquals(AutoMixHandoff.WAIT, gate.sample(528, 8528, 1528));
        assertEquals(AutoMixHandoff.FADE, gate.sample(1910, 9910, 2910));
    }
    @Test public void fasterSeekCanReduceAnOverestimatedTargetLead() {
        AutoMixHandoff gate = new AutoMixHandoff(10000, 3000, 0);
        gate.sample(300, 7300, 30);
        assertEquals(332, gate.sample(332, 7332, 60));
        gate.seekComplete(732, 332);
        gate.sample(764, 7764, 364);
        assertEquals(1196, gate.sample(796, 7796, 396));
        gate.seekComplete(896, 1196); // Target is now 300ms ahead: seek got much faster.
        gate.sample(928, 7928, 1228);
        assertEquals(1060, gate.sample(960, 7960, 1260));
        gate.seekComplete(1060, 1060);
        gate.sample(1092, 8092, 1092);
        assertEquals(AutoMixHandoff.WAIT, gate.sample(1124, 8124, 1124));
        assertEquals(AutoMixHandoff.FADE, gate.sample(2910, 9910, 2910));
    }
    @Test public void asynchronousSeekLatencyIsCompensatedInsteadOfMissingEveryCue() {
        AutoMixHandoff gate = new AutoMixHandoff(10000, 3000, 0);
        assertEquals(AutoMixHandoff.WAIT, gate.sample(300, 7300, 30));
        assertEquals(332, gate.sample(332, 7332, 60));
        gate.seekComplete(532, 332); // Real seek leaves the new clock 200ms behind.
        assertEquals(AutoMixHandoff.WAIT, gate.sample(564, 7564, 364));
        assertEquals(796, gate.sample(596, 7596, 396)); // Compensates the measured 200ms.
        gate.seekComplete(796, 796);
        assertEquals(AutoMixHandoff.WAIT, gate.sample(828, 7828, 828));
        assertEquals(AutoMixHandoff.WAIT, gate.sample(860, 7860, 860));
        assertEquals(AutoMixHandoff.FADE, gate.sample(2910, 9910, 2910));
    }
    @Test public void changingSeekCostCanBeCorrectedAgainWhileThePrefixIsStillMuted() {
        AutoMixHandoff gate = new AutoMixHandoff(10000, 3000, 0);
        gate.sample(300, 7300, 30);
        assertEquals(332, gate.sample(332, 7332, 60));
        gate.seekComplete(532, 332);
        gate.sample(564, 7564, 364);
        assertEquals(796, gate.sample(596, 7596, 396));
        gate.seekComplete(946, 796); // Second seek now costs 350ms.
        gate.sample(978, 7978, 828);
        assertEquals(1360, gate.sample(1010, 8010, 860));
        gate.seekComplete(1360, 1360);
        gate.sample(1392, 8392, 1392);
        assertEquals(AutoMixHandoff.WAIT, gate.sample(1424, 8424, 1424));
        assertEquals(AutoMixHandoff.FADE, gate.sample(2910, 9910, 2910));
    }
    @Test public void compensationIncludesOutputRestartAfterSeekCallback() {
        AutoMixHandoff gate = new AutoMixHandoff(10000, 3000, 0);
        gate.sample(300, 7300, 30);
        assertEquals(332, gate.sample(332, 7332, 60));
        gate.seekComplete(532, 332);
        gate.sample(664, 7664, 364); // Output resumes another 100ms after the callback.
        assertEquals(996, gate.sample(696, 7696, 396));
        gate.seekComplete(896, 996);
        gate.sample(1028, 8028, 1028);
        assertEquals(AutoMixHandoff.WAIT, gate.sample(1060, 8060, 1060));
        assertEquals(AutoMixHandoff.FADE, gate.sample(2910, 9910, 2910));
    }
    @Test public void coldStartStaysMutedThenAlignsOnlyTheDryPrefix() {
        AutoMixHandoff gate = new AutoMixHandoff(10000, 1500, 0);
        assertEquals(AutoMixHandoff.WAIT, gate.sample(240, 8740, 0));
        assertEquals(AutoMixHandoff.WAIT, gate.sample(272, 8772, 32));
        assertEquals(304, gate.sample(304, 8804, 64));
        assertEquals(AutoMixHandoff.WAIT, gate.sample(320, 8820, 304));
        gate.seekComplete(336, 336);
        assertEquals(AutoMixHandoff.WAIT, gate.sample(368, 8868, 368));
        assertEquals(AutoMixHandoff.WAIT, gate.sample(400, 8900, 400));
        assertEquals(AutoMixHandoff.FADE, gate.sample(1420, 9920, 1420));
    }
    @Test public void stalledClockCannotFadeOrSeekLateIntoNextSong() {
        AutoMixHandoff frozen = new AutoMixHandoff(10000, 1500, 0);
        assertEquals(AutoMixHandoff.WAIT, frozen.sample(1400, 9900, 0));
        assertEquals(AutoMixHandoff.ABORT, frozen.sample(1760, 10260, 0));
        AutoMixHandoff late = new AutoMixHandoff(10000, 1500, 0);
        late.sample(1300, 9800, 20);
        assertEquals(AutoMixHandoff.ABORT, late.sample(1330, 9830, 50));
    }
    @Test public void alreadyAlignedClockNeedsNoSeekAndStillWaitsForCue() {
        AutoMixHandoff gate = new AutoMixHandoff(10000, 1500, 0);
        assertEquals(AutoMixHandoff.WAIT, gate.sample(30, 8530, 30));
        assertEquals(AutoMixHandoff.WAIT, gate.sample(60, 8560, 60));
        assertEquals(AutoMixHandoff.FADE, gate.sample(1410, 9910, 1410));
    }
    @Test public void pendingSeekAndNewFreezeKeepOutgoingAudio() {
        AutoMixHandoff gate = new AutoMixHandoff(10000, 1500, 0);
        gate.sample(250, 8750, 30);
        assertEquals(280, gate.sample(280, 8780, 60));
        assertEquals(AutoMixHandoff.WAIT, gate.sample(1420, 9920, 1400));
        gate.seekComplete(1430, 1400);
        assertEquals(AutoMixHandoff.WAIT, gate.sample(1440, 9940, 1400));
        assertEquals(AutoMixHandoff.ABORT, gate.sample(1760, 10260, 1400));
    }

    @Test public void alignedClocksCanHandOverAfterOneDelayedBackgroundTickWithoutSeekingBack() {
        AutoMixHandoff gate = new AutoMixHandoff(10000, 3000, 0);
        assertEquals(AutoMixHandoff.WAIT, gate.sample(2700, 9700, 2700));
        assertEquals(AutoMixHandoff.WAIT, gate.sample(2800, 9800, 2800));
        // Main-looper scheduling skipped the 96ms fade window; both audio decks kept running.
        assertEquals(AutoMixHandoff.FADE, gate.sample(3200, 10200, 3200));
    }

    @Test public void lateMisalignedOrFrozenDeckNeverReplaysTheOutgoingTail() {
        AutoMixHandoff gate = new AutoMixHandoff(10000, 3000, 0);
        gate.sample(2700, 9700, 2700);
        gate.sample(2800, 9800, 2800);
        assertEquals(AutoMixHandoff.ABORT, gate.sample(3200, 10200, 2900));
        AutoMixHandoff alignedButTooLate = new AutoMixHandoff(10000, 3000, 0);
        alignedButTooLate.sample(2700, 9700, 2700);
        alignedButTooLate.sample(2800, 9800, 2800);
        assertEquals(AutoMixHandoff.ABORT, alignedButTooLate.sample(3251, 10251, 3251));
    }

    @Test public void tenMinutesOfPausedSongTimeCannotPassTheCue() {
        AutoMixHandoff gate = new AutoMixHandoff(10000, 3000, 0);
        assertEquals(AutoMixHandoff.WAIT, gate.sample(200, 7200, 200));
        assertEquals(AutoMixHandoff.WAIT, gate.sample(240, 7240, 240));
        assertEquals(AutoMixHandoff.WAIT, gate.sample(600240, 7240, 240));
        assertEquals(AutoMixHandoff.WAIT, gate.sample(600270, 7270, 270));
        assertEquals(AutoMixHandoff.FADE, gate.sample(602910, 9910, 2910));
    }
}
