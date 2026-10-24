package dev.t1m3.qplayer.lyric;

import dev.t1m3.qplayer.lyric.skia.SpringAnim;

/** Only the line float-up solver and directional cascade from the user's
 * qplayer-master (1).zip, LyricRenderer.stepLineSpring/cascadeDelayBuf.
 * Text, color, scale, vocal tracks and layout are deliberately not imported. */
public final class QPlayerLyricScrollMotion {
    private QPlayerLyricScrollMotion() {}

    public static double cascadeDelay(int anchor, int row, int direction, boolean disabled) {
        if (disabled) return 0;
        int trailing = direction >= 0 ? row - anchor : anchor - row;
        double delay = 0, step = .05;
        for (int i = 0; i < trailing; i++) { delay += step; step /= 1.05; }
        return delay;
    }

    public static final class Spring {
        private float value, velocity, target;
        private double elapsed, delay, stiffness = 65, damping = Math.sqrt(65) * 1.365;
        private boolean seeking, enabled = true;
        private final SpringAnim seek = new SpringAnim(180, 28);
        private final SpringAnim firm = new SpringAnim(65, 11);

        public Spring(float initial) { reset(initial); }
        public void reset(float position) {
            value = target = position; velocity = 0; elapsed = delay = 0;
            seeking = false; seek.setValue(position); firm.setValue(position);
        }
        public void retarget(float position, double delay, boolean interlude, boolean seeking) {
            retarget(position, delay, interlude, seeking, true);
        }
        public void retarget(float position, double delay, boolean interlude, boolean seeking, boolean enabled) {
            stiffness = interlude ? 55 : 65;
            damping = interlude ? 10.1 : Math.sqrt(65) * 1.365;
            if (Math.abs(position - target) <= .001f && this.seeking == seeking && this.enabled == enabled) return;
            if (seeking) {
                if (!this.seeking) seek.setValue(value);
                seek.setTargetPosition(position);
            }
            if (!enabled) {
                if (this.enabled) firm.setValue(value);
                firm.setTargetPosition(position);
            }
            target = position; this.delay = delay; elapsed = 0;
            this.seeking = seeking; this.enabled = enabled;
        }
        public float update(double dt) {
            dt = Math.max(0, Math.min(.05, dt));
            if (seeking) {
                seek.update(dt); value = (float) seek.getValue(); velocity = (float) seek.getVelocity();
                return value;
            }
            if (!enabled) {
                firm.update(dt); value = (float) firm.getValue(); velocity = (float) firm.getVelocity();
                return value;
            }
            elapsed += dt;
            if (elapsed < delay || dt <= 0) return value;
            // Same substeps, semi-implicit integration, float storage and
            // arrival thresholds as LyricRenderer.stepLineSpring in the zip.
            double position = value, vel = velocity;
            int steps = 1 + (int) (dt / .008);
            double sub = dt / steps;
            for (int s = 0; s < steps; s++) {
                double a = -stiffness * (position - target) - damping * vel;
                vel += a * sub; position += vel * sub;
            }
            if (Math.abs(vel) < .01 && Math.abs(position - target) < .05) {
                position = target; vel = 0;
            }
            value = (float) position; velocity = (float) vel;
            return value;
        }
        public float value() { return value; }
        public boolean arrived() {
            return elapsed >= delay && Math.abs(value - target) < .05 && Math.abs(velocity) < .01;
        }
    }
}
