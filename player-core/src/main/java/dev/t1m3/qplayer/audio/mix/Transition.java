package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;

public interface Transition {
    long windowFrames(int sampleRate);

    int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress);

    String name();

    boolean rewritesSamples();

    /** Limit a block at an internal material boundary without consuming either deck ahead. */
    default int renderChunkFrames(int requested) { return requested; }

    default boolean suppliesOutgoingAudio() { return false; }

    /** Some loop paths extend playback beyond A's end; tail subtraction cannot describe them. */
    default double outgoingStartSecs(double durationSecs, TransitionPlan plan, int rate) {
        return Math.max(0, durationSecs - windowFrames(rate) / (double) rate - plan.outroTailSecs);
    }

    default TransitionPlan prepare(String outro, String intro, int rate) {
        return TransitionPlan.NONE;
    }

    default TransitionPlan prepare(
            String outro,
            String intro,
            int rate,
            java.util.Map<String, String> outroHeaders,
            java.util.Map<String, String> introHeaders) {
        return prepare(outro, intro, rate);
    }

    default double outroLoopLeadInSecs() {
        return 0.0;
    }

    default PitchPlan pitchAt(float progress) {
        return PitchPlan.ZERO;
    }

    /** Pitch automation in the final render window, which auto cue may resize. */
    default PitchPlan pitchAt(float progress, double windowSecs) {
        return pitchAt(progress);
    }
}
