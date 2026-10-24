package dev.t1m3.qplayer.lyric;

/** Native port of the lyric physics in the user supplied ui.zip/source_algorithms.js.
 * Units are seconds and logical pixels. Audio position, never an independent
 * countdown, drives word timing. Row springs retain velocity when retargeted.
 */
public final class ReferenceLyricMotion {
    private ReferenceLyricMotion() {}
    private static final double LOG_ONE_PERCENT = -Math.log(.01);
    public static double clamp(double v, double low, double high) { return Math.min(Math.max(v, low), high); }
    public static final class Physics {
        public final double dampingRatio, response;
        public Physics(double dampingRatio, double response) {
            this.dampingRatio = dampingRatio; this.response = Math.max(response, .001);
        }
    }
    public static Physics lineSpring(boolean timed, double gap, double remaining, boolean seeking, boolean largeSeek) {
        if (seeking) return largeSeek ? new Physics(.9, Math.PI * .2) : criticalEnvelope(.10);
        double a = clamp((gap - .20) / .55, 0, 1);
        Physics p = timed ? new Physics(.90 - .12 * a, .48 + .27 * a) : new Physics(.9, Math.PI * .2);
        if (remaining < .60) return criticalEnvelope(.30);
        double oldEnvelope = LOG_ONE_PERCENT / ((Math.PI * 2 / p.response) * clamp(p.dampingRatio, .10, 1));
        if (remaining < .80 && oldEnvelope - remaining < -.05) return criticalEnvelope(Math.max(remaining - .40, .30));
        return p;
    }
    public static Physics criticalEnvelope(double response) { return new Physics(1, Math.PI * 2 * response / LOG_ONE_PERCENT); }
    public static double cascadeDelay(int validDistance, boolean disabled) { return disabled ? 0 : Math.max(validDistance - 1, 0) * .05; }
    public static double blurRadius(int distance) { return distance <= 0 ? 0 : clamp((distance - .25) * 1.25, 0, 6); }

    /** Also handles a pending cascade target without freezing the old spring. */
    public static final class Spring {
        public double value, velocity;
        private double start, target, initialVelocity, elapsed;
        private Physics physics = new Physics(.9, .48);
        private double delay = -1, pendingTarget;
        private Physics pendingPhysics;
        public Spring(double value) { reset(value); }
        public void reset(double value) {
            this.value = start = target = value;
            velocity = initialVelocity = elapsed = 0; delay = -1;
        }
        public boolean atRest(double positionTolerance, double velocityTolerance) {
            return delay < 0 && Math.abs(value - target) <= positionTolerance
                && Math.abs(velocity) <= velocityTolerance;
        }
        public void retarget(double target, Physics physics, double delay) {
            if (delay > 0) {
                this.delay = delay; pendingTarget = target; pendingPhysics = physics;
            } else {
                this.start = value; this.target = target; initialVelocity = velocity;
                elapsed = 0; this.physics = physics; this.delay = -1;
            }
        }
        public double update(double dt) {
            dt = Math.max(dt, 0);
            if (delay >= 0) {
                if (dt < delay) { delay -= dt; return advance(dt); }
                double before = delay;
                advance(before);
                retarget(pendingTarget, pendingPhysics, 0);
                dt -= before;
            }
            return advance(dt);
        }
        private double advance(double dt) {
            elapsed += dt;
            double omega = Math.PI * 2 / physics.response, z = physics.dampingRatio;
            double displacement = start - target, relative;
            if (z < 1) {
                double decay = z * omega, damped = omega * Math.sqrt(1 - z * z);
                double c = (initialVelocity + decay * displacement) / damped;
                double cos = Math.cos(damped * elapsed), sin = Math.sin(damped * elapsed), e = Math.exp(-decay * elapsed);
                relative = e * (displacement * cos + c * sin);
                velocity = e * ((-decay * displacement + damped * c) * cos + (-decay * c - damped * displacement) * sin);
            } else {
                double c = initialVelocity + omega * displacement, e = Math.exp(-omega * elapsed);
                relative = e * (displacement + c * elapsed);
                velocity = e * (c - omega * (displacement + c * elapsed));
            }
            value = target + relative;
            if (Math.abs(value - target) < .00001 && Math.abs(velocity) < .00001) { value = target; velocity = 0; }
            return value;
        }
    }

    public static boolean longAscii(String text, double duration) {
        if (duration < 1 || text.length() <= 7) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= 9 && c <= 13) && (c < 32 || c > 126)) return false;
        }
        return true;
    }
    public static final class WordGlyph {
        private final int index, count;
        private final double duration, startTrigger, tailTrigger;
        private final Spring start = new Spring(0), lift = new Spring(0), tail = new Spring(0);
        private final Physics main, auxiliary;
        private boolean started, tailed, sampled;
        public double pulse, scale = 1, xOffset, yLift;
        public WordGlyph(int index, int count, double duration, boolean cjk) {
            this.index = index; this.count = Math.max(count, 1); this.duration = Math.max(duration, .001);
            double average = this.duration / this.count, factor = cjk ? .8 : .4;
            startTrigger = (index + 1) * Math.min(factor * average, factor);
            tailTrigger = startTrigger + 2 * average;
            main = new Physics(1, Math.min(this.duration, 3));
            auxiliary = new Physics(1, Math.min(this.duration, 3) * 1.25);
        }
        public void reset() { start.reset(0); lift.reset(0); tail.reset(0); started = tailed = sampled = false; }
        public void update(double localTime, double dt, double liftAmount) {
            if (localTime < 0) {
                if (started || tailed) reset();
                sampled = true; pulse = xOffset = yLift = 0; scale = 1; return;
            }
            // Opening lyrics or seeking into a held syllable must not replay its pop.
            if (!sampled && localTime >= tailTrigger) {
                start.reset(1); lift.reset(1); tail.reset(1); started = tailed = true;
            }
            sampled = true;
            if (!started && localTime >= startTrigger) { started = true; start.retarget(1, main, 0); lift.retarget(1, auxiliary, 0); }
            if (!tailed && localTime >= tailTrigger) { tailed = true; tail.retarget(1, auxiliary, 0); }
            double s = start.update(dt), l = lift.update(dt), t = tail.update(dt);
            pulse = s * (1 - t);
            double longness = clamp(duration, 1, 2) - 1, scaleDelta = .14 * longness;
            scale = 1 + scaleDelta * pulse;
            xOffset = (index - (count - 1) * .5) * .5 * pulse * scaleDelta;
            yLift = liftAmount * l + 1.5 * pulse * longness;
        }
    }

    public static final class BreakSample {
        public double scale = 1;
        public final double[] dots = new double[3];
        public void update(double start, double end, double time) {
            if (time < start || time > end) { scale = 1; for (int i = 0; i < 3; i++) dots[i] = 0; return; }
            double duration = Math.max(end - start, .001), scaleDuration = Math.max(duration - .4, .001);
            scale = 1 + .3 * ease((time - start - .2) / scaleDuration);
            double endAlpha = 1, ending = time - (end - 1.8);
            if (ending > 0) {
                if (ending < 1) {
                    double from = 1 + .3 * ease((end - 1.8 - start - .2) / scaleDuration);
                    scale = from + (1.3 - from) * bezier(ending, .25, .10, .25, 1);
                } else scale = 1.3 - 1.1 * ease((ending - 1) / .5);
                endAlpha = 1 - ease((ending - 1) / .3);
            }
            double countdown = start + 1, step = Math.max((end - countdown) / 3, .001), fillDuration = Math.max(step - .1, .001);
            for (int i = 0; i < 3; i++) {
                double entrance = clamp((time - start - i * .06) / .8, 0, 1);
                double fill = clamp((time - countdown - i * step) / fillDuration, 0, 1);
                dots[i] = clamp((.1 + .9 * fill) * entrance * endAlpha, 0, 1);
            }
        }
    }
    public static double ease(double v) { v = clamp(v, 0, 1); return v < .5 ? 2 * v * v : 1 - Math.pow(2 - 2 * v, 2) * .5; }
    public static double bezier(double progress, double x1, double y1, double x2, double y2) {
        double input = clamp(progress, 0, 1), t = input;
        for (int i = 0; i < 8; i++) {
            double u = 1 - t, estimated = 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t;
            double error = estimated - input, derivative = 3 * u * u * x1 + 6 * u * t * (x2 - x1) + 3 * t * t * (1 - x2);
            if (Math.abs(error) < 1e-6 || Math.abs(derivative) < 1e-6) break;
            t -= error / derivative;
        }
        t = clamp(t, 0, 1); double u = 1 - t;
        return 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t;
    }
}
