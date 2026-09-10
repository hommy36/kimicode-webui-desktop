package app.kimicode.remote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 扫码遮罩：半透明黑底 + 中央透明圆角方框 + 四角括号 + 往返扫描线。
 */
public class ScanOverlayView extends View {

    private static final int ACCENT = 0xFF1A88FF;
    private static final int SCRIM = 0xA0000000;

    private final Paint scrimPaint = new Paint();
    private final Paint clearPaint = new Paint();
    private final Paint accentPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final RectF frame = new RectF();
    private float lineY = -1; // 扫描线当前 y（相对 frame）
    private ValueAnimator animator;

    public ScanOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        scrimPaint.setColor(SCRIM);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        accentPaint.setColor(ACCENT);
        accentPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStrokeWidth(dp(3));
        accentPaint.setStrokeCap(Paint.Cap.ROUND);
        accentPaint.setAntiAlias(true);
        linePaint.setColor(ACCENT);
        linePaint.setStyle(Paint.Style.FILL);
        linePaint.setAntiAlias(true);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float side = Math.min(w, h) * 0.62f;
        float left = (w - side) / 2f;
        float top = (h - side) / 2f - h * 0.06f; // 略偏上，视觉居中
        frame.set(left, top, left + side, top + side);
        startAnim();
    }

    private void startAnim() {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, frame.height());
        animator.setDuration(2200);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            lineY = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 离屏层：先画遮罩再挖空扫描框
        int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
        canvas.drawRoundRect(frame, dp(16), dp(16), clearPaint);
        canvas.restoreToCount(layer);

        // 四角括号
        float arm = dp(22);
        float l = frame.left, t = frame.top, r = frame.right, b = frame.bottom;
        canvas.drawLine(l, t + arm, l, t, accentPaint);
        canvas.drawLine(l, t, l + arm, t, accentPaint);
        canvas.drawLine(r - arm, t, r, t, accentPaint);
        canvas.drawLine(r, t, r, t + arm, accentPaint);
        canvas.drawLine(l, b - arm, l, b, accentPaint);
        canvas.drawLine(l, b, l + arm, b, accentPaint);
        canvas.drawLine(r - arm, b, r, b, accentPaint);
        canvas.drawLine(r, b, r, b - arm, accentPaint);

        // 扫描线
        if (lineY >= 0) {
            float y = frame.top + lineY;
            canvas.drawRoundRect(frame.left + dp(8), y - dp(1),
                    frame.right - dp(8), y + dp(1), dp(1), dp(1), linePaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
