package dev.t1m3.qplayer.android.graphics;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import dev.t1m3.qplayer.bridge.ColorExtractor;

/**
 * Picks a vibrant seed color from a cover by histogramming pixels into hue bins
 * weighted by chroma*value, then averaging the heaviest bin. Near-gray, very dark
 * and blown-out pixels are skipped so the seed is a real accent, not the
 * background. Falls back to the overall average when the art has no vibrant color.
 */
public final class AndroidColorExtractor implements ColorExtractor {

    private static final int SAMPLE = 32;
    private static final int HUE_BINS = 24;

    @Override
    public String dominantHex(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) return null;

        // Decode close to the 32px analysis size instead of materializing the
        // complete 512/1024px cover first. On Android the old full decode was the
        // dominant cost (and briefly allocated several MB) even though all but
        // 1024 output pixels were immediately discarded.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, bounds);
        int sampleSize = 1;
        while (bounds.outWidth / (sampleSize * 2) >= SAMPLE
                && bounds.outHeight / (sampleSize * 2) >= SAMPLE) {
            sampleSize *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inSampleSize = sampleSize;
        Bitmap src = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, opts);
        if (src == null) return null;
        try {
            return dominantHex(src);
        } finally {
            src.recycle();
        }
    }

    /** Analyze a shared decoded cover without recycling or modifying the caller's bitmap. */
    public String dominantHex(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        Bitmap small = src.getWidth() == SAMPLE && src.getHeight() == SAMPLE
                ? src : Bitmap.createScaledBitmap(src, SAMPLE, SAMPLE, true);

        try {
            double[] binWeight = new double[HUE_BINS];
            double[] binR = new double[HUE_BINS];
            double[] binG = new double[HUE_BINS];
            double[] binB = new double[HUE_BINS];
            double avgR = 0, avgG = 0, avgB = 0;
            int avgN = 0;

            int[] pixels = new int[SAMPLE * SAMPLE];
            small.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
            float[] hsv = new float[3];
            for (int p : pixels) {
                int r = Color.red(p), g = Color.green(p), b = Color.blue(p);
                avgR += r; avgG += g; avgB += b; avgN++;
                Color.colorToHSV(p, hsv);
                float sat = hsv[1], val = hsv[2];
                if (sat < 0.2f || val < 0.15f || val > 0.95f) continue;
                double w = sat * val;
                int bin = (int) (hsv[0] / 360f * HUE_BINS) % HUE_BINS;
                binWeight[bin] += w;
                binR[bin] += r * w; binG[bin] += g * w; binB[bin] += b * w;
            }

            int best = -1;
            for (int i = 0; i < HUE_BINS; i++) {
                if (best < 0 || binWeight[i] > binWeight[best]) best = i;
            }
            if (best >= 0 && binWeight[best] > 0) {
                double w = binWeight[best];
                return hex((int) (binR[best] / w), (int) (binG[best] / w), (int) (binB[best] / w));
            }
            if (avgN == 0) return null;
            return hex((int) (avgR / avgN), (int) (avgG / avgN), (int) (avgB / avgN));
        } finally {
            if (small != src) small.recycle();
        }
    }

    private static String hex(int r, int g, int b) {
        return String.format("#%02x%02x%02x", clamp(r), clamp(g), clamp(b));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
