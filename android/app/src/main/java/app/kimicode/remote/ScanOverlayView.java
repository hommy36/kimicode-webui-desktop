package app.kimicode.remote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
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
        // 遮罩跟随系统主题：深色透黑，浅色透白
        scrimPaint.setColor(isNight() ? SCRIM : 0x99FFFFFF);
        // 离屏层：先画遮罩再挖空扫描框
        int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
        canvas.drawRoundRect(frame, dp(16), dp(16), clearPaint);
        canvas.restoreToCount(layer);

        // 四角圆角括号：弧线过渡，与挖空圆角同半径，契合整体圆角风格
        float arm = dp(22);
        float rr = dp(16);
        float l = frame.left, t = frame.top, r = frame.right, b = frame.bottom;
        Path p = new Path();
        p.moveTo(l, t + arm); p.lineTo(l, t + rr);
        p.quadTo(l, t, l + rr, t); p.lineTo(l + arm, t);
        p.moveTo(r - arm, t); p.lineTo(r - rr, t);
        p.quadTo(r, t, r, t + rr); p.lineTo(r, t + arm);
        p.moveTo(l, b - arm); p.lineTo(l, b - rr);
        p.quadTo(l, b, l + rr, b); p.lineTo(l + arm, b);
        p.moveTo(r - arm, b); p.lineTo(r - rr, b);
        p.quadTo(r, b, r, b - rr); p.lineTo(r, b - arm);
        canvas.drawPath(p, accentPaint);

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
