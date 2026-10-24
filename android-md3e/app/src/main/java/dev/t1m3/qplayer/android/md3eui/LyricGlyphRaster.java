package dev.t1m3.qplayer.android.md3eui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.BitmapShader;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Shader;

/** One padded alpha mask for all colors and animation frames. No cropped
 * RenderNode replay and no paragraph rasterization at highlight boundaries. */
public final class LyricGlyphRaster {
    public interface Rasterizer { void draw(Canvas canvas); }
    private final Bitmap mask;
    private final int padding;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final LinearGradient gradient;
    private final ComposeShader coloredMask;

    public LyricGlyphRaster(int width, int height, int padding, int active, int idle, Rasterizer rasterizer) {
        this.padding = padding;
        mask = Bitmap.createBitmap(Math.max(width, 1) + padding * 2,
            Math.max(height, 1) + padding * 2, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(mask);
        canvas.translate(padding, padding);
        rasterizer.draw(canvas);
        // ALPHA_8 supplies coverage only; the same shader supplies color in
        // every frame, including progress 0 and 1.
        gradient = new LinearGradient(0, 0, 1, 0, active, idle, Shader.TileMode.CLAMP);
        BitmapShader coverage = new BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix origin = new Matrix();
        origin.setTranslate(-padding, -padding);
        coverage.setLocalMatrix(origin);
        coloredMask = new ComposeShader(coverage, gradient, PorterDuff.Mode.SRC_IN);
    }

    public void drawGradient(Canvas canvas, float start, float feather) {
        drawGradient(canvas, start, feather, 1f);
    }

    public void drawGradient(Canvas canvas, float start, float feather, float opacity) {
        matrix.setScale(Math.max(feather, .001f), 1);
        // Explicit shader coordinates avoid ALPHA_8 drawBitmap's different
        // CPU/GPU treatment of bitmap padding (which lit later word initials).
        matrix.postTranslate(start, 0);
        gradient.setLocalMatrix(matrix);
        paint.setShader(coloredMask);
        paint.setAlpha(Math.round(Math.max(0, Math.min(1, opacity)) * 255));
        canvas.drawRect(-padding, -padding, mask.getWidth() - padding, mask.getHeight() - padding, paint);
    }

    public void drawSolid(Canvas canvas, int color) {
        paint.setShader(null);
        paint.setColor(color);
        canvas.drawBitmap(mask, -padding, -padding, paint);
    }
}
