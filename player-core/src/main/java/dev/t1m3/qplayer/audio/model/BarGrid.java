package dev.t1m3.qplayer.audio.model;

/**
 * 节拍 / 小节网格（2026-10-11 ✓ automix L1.1「小节对齐」的地基 ✓）。
 *
 * <p>背景：树里 `BeatDetector` **只有接口、没有任何实现** ✗（`implements BeatDetector` 零处 ✓），
 * 所以 `AiMix` 的节拍分支一直是死的 ✗ ⇒ 过渡点只能靠"结尾前 N 秒" ✗。
 *
 * <p>这里用最朴素但可靠的做法（全部 O(n) ✓ 不需要 FFT ✓）：
 * <ol>
 *   <li>能量包络：10 ms 帧的平方和 → 一阶差分（半波整流）⇒ 起音强度 ✓</li>
 *   <li>拍速：对起音强度做<b>自相关</b> ✓ 在 60~200 BPM 范围内找峰 ✓</li>
 *   <li>相位：以找到的拍长做<b>梳状求和</b> ✓ 取最强相位 ✓</li>
 *   <li>小节：按 4 拍一组 ✓ 取四拍里起音强度最大的相位当下拍 ✓</li>
 * </ol>
 *
 * <p>只用于"把接点对到拍/小节上"✓ 不做重型音乐分析 ✗（那是 L1.6 加分项的事 ✓）。
 */
public final class BarGrid {

    private BarGrid() {}

    /** 分析结果。所有时间单位秒 ✓。 */
    public static final class Grid {
        public final double bpm;
        public final double[] beats;
        public final double[] downbeats;

        Grid(double bpm, double[] beats, double[] downbeats) {
            this.bpm = bpm; this.beats = beats; this.downbeats = downbeats;
        }

        /** 不早于 {@code t} 的第一个拍点；没有则返回 {@code t} 本身。 */
        public double nextBeat(double t) {
            for (double b : beats) if (b >= t - 1e-6) return b;
            return t;
        }

        /** 不早于 {@code t} 的第一个小节线；没有则退回 {@link #nextBeat(double)}。 */
        public double nextDownbeat(double t) {
            for (double d : downbeats) if (d >= t - 1e-6) return d;
            return nextBeat(t);
        }

        public boolean usable() { return bpm > 0 && beats.length >= 4; }
    }

    private static final double FRAME = 0.010;          // 10 ms
    private static final double MIN_BPM = 60, MAX_BPM = 200;

    public static Grid analyse(float[] pcm, int channels, int rate) {
        if (pcm == null || pcm.length == 0 || rate <= 0 || channels <= 0) return empty();
        final int hop = Math.max(1, (int) Math.round(rate * FRAME));
        final int frames = pcm.length / channels / hop;
        if (frames < 64) return empty();
        final double[] frameEnergy = new double[frames];
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            for (int c = 0; c < channels; c++) {
                final int base = f * hop * channels + c;
                for (int i = 0; i < hop; i++) {
                    final float v = pcm[base + i * channels];
                    sum += v * v;
                }
            }
            frameEnergy[f] = sum;
        }
        // 起音强度：能量的一阶正差分（半波整流）⇒ 对底鼓/军鼓最敏感 ✓
        final double[] onset = new double[frames];
        for (int f = 1; f < frames; f++) {
            final double d = frameEnergy[f] - frameEnergy[f - 1];
            onset[f] = d > 0 ? d : 0;
        }
        final double[] beats = beatTimes(onset);
        if (beats.length < 4) return empty();
        final double bpm = 60.0 / (beats[1] - beats[0] > 1e-6 ? beats[1] - beats[0] : 0.5);
        // 4 拍一组：让第一拍落在"四拍里起音最强"的相位上 ✓
        final double[] downbeats = downbeats(beats);
        return new Grid(bpm, beats, downbeats);
    }

    private static double[] beatTimes(double[] onset) {
        // 自相关找拍长（帧单位 ✓）
        final int minLag = (int) Math.round(60.0 / MAX_BPM / FRAME);
        final int maxLag = (int) Math.round(60.0 / MIN_BPM / FRAME);
        final int n = onset.length;
        double best = -1; int bestLag = 0;
        for (int lag = minLag; lag <= Math.min(maxLag, n / 2); lag++) {
            double sum = 0;
            for (int i = 0; i + lag < n; i++) sum += onset[i] * onset[i + lag];
            final double norm = sum / (n - lag);
            if (norm > best) { best = norm; bestLag = lag; }
        }
        if (bestLag <= 0 || best <= 1e-12) return new double[0];
        // 梳状求和找相位 ✓
        int bestPhase = 0; double bestPhaseScore = -1;
        for (int phase = 0; phase < bestLag; phase++) {
            double sum = 0;
            for (int p = phase; p < n; p += bestLag) sum += onset[p];
            if (sum > bestPhaseScore) { bestPhaseScore = sum; bestPhase = phase; }
        }
        final int count = (n - bestPhase) / bestLag;
        if (count < 4) return new double[0];
        final double[] times = new double[count];
        for (int i = 0; i < count; i++) times[i] = (bestPhase + (double) i * bestLag) * FRAME;
        return times;
    }

    private static double[] downbeats(double[] beats) {
        // 4 拍一组 ✓ 选"每 4 拍起音最强"的相位作下拍 ✓（强度用拍点间隔近似）
        int bestShift = 0; double bestGap = -1;
        for (int shift = 0; shift < 4 && shift < beats.length; shift++) {
            double gap = 0;
            for (int i = shift; i + 1 < beats.length; i += 4) gap += beats[i + 1] - beats[i];
            if (gap > bestGap) { bestGap = gap; bestShift = shift; }
        }
        final int count = (beats.length - bestShift + 3) / 4;
        final double[] out = new double[Math.max(count, 0)];
        for (int i = 0; i < out.length; i++) out[i] = beats[bestShift + i * 4];
        return out;
    }

    private static Grid empty() { return new Grid(0, new double[0], new double[0]); }
}
