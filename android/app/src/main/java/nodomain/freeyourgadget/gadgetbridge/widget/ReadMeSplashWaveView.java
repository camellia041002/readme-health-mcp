package nodomain.freeyourgadget.gadgetbridge.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import nodomain.freeyourgadget.gadgetbridge.R;

/** Draws the ReadMe launcher-icon rhythm progressively without moving completed segments. */
public class ReadMeSplashWaveView extends View {
    private static final float[][] POINTS = {
            {0.00f, 0.56f},
            {0.24f, 0.56f},
            {0.35f, 0.38f},
            {0.46f, 0.86f},
            {0.59f, 0.08f},
            {0.71f, 0.56f},
            {1.00f, 0.56f}
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress;

    public ReadMeSplashWaveView(Context context) {
        this(context, null);
    }

    public ReadMeSplashWaveView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(getResources().getDisplayMetrics().density * 2.5f);
        paint.setColor(getResources().getColor(R.color.accent, context.getTheme()));
    }

    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (progress <= 0f || getWidth() == 0 || getHeight() == 0) {
            return;
        }

        final float horizontalInset = getResources().getDisplayMetrics().density * 2f;
        final float usableWidth = getWidth() - horizontalInset * 2f;
        final float usableHeight = getHeight();
        final float[] segmentLengths = new float[POINTS.length - 1];
        float totalLength = 0f;
        for (int i = 0; i < POINTS.length - 1; i++) {
            float dx = (POINTS[i + 1][0] - POINTS[i][0]) * usableWidth;
            float dy = (POINTS[i + 1][1] - POINTS[i][1]) * usableHeight;
            segmentLengths[i] = (float) Math.hypot(dx, dy);
            totalLength += segmentLengths[i];
        }

        float remaining = totalLength * progress;
        Path path = new Path();
        float x = horizontalInset + POINTS[0][0] * usableWidth;
        float y = POINTS[0][1] * usableHeight;
        path.moveTo(x, y);

        for (int i = 0; i < segmentLengths.length && remaining > 0f; i++) {
            float nextX = horizontalInset + POINTS[i + 1][0] * usableWidth;
            float nextY = POINTS[i + 1][1] * usableHeight;
            if (remaining >= segmentLengths[i]) {
                path.lineTo(nextX, nextY);
                remaining -= segmentLengths[i];
            } else {
                float portion = remaining / segmentLengths[i];
                path.lineTo(x + (nextX - x) * portion, y + (nextY - y) * portion);
                remaining = 0f;
            }
            x = nextX;
            y = nextY;
        }
        canvas.drawPath(path, paint);
    }
}
