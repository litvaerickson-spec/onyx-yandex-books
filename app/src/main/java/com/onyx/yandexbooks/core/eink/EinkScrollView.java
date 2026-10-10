package com.onyx.yandexbooks.core.eink;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/**
 * Специализированный ScrollView для экранов E-Ink (Onyx Boox Darwin 1 / 3 / 5 / 6).
 *
 * Решает критическую проблему Android 4.2.2 (API 17, Onyx Boox Darwin 1) при
 * android:hardwareAccelerated="false", когда после прокрутки (mScrollY > 0)
 * стандартный View.invalidate() вычисляет грязный прямоугольник со смещением (-mScrollX, -mScrollY),
 * из-за чего родительский контейнер обрезает область перерисовки до верхнего левого угла,
 * а остальная часть экрана превращается в «мешанину» из старых и новых кадров.
 */
public class EinkScrollView extends ScrollView {

    private static final long EPD_REFRESH_DEBOUNCE_MS = 180L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable fullRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            EpdController.requestFullRefresh(getContext(), EinkScrollView.this);
        }
    };

    public EinkScrollView(Context context) {
        super(context);
        init();
    }

    public EinkScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EinkScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setVerticalFadingEdgeEnabled(false);
        setHorizontalFadingEdgeEnabled(false);
        setDrawingCacheEnabled(false);
        setSmoothScrollingEnabled(false);
    }

    @Override
    public void invalidate() {
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            int sx = getScrollX();
            int sy = getScrollY();
            super.invalidate(sx, sy, sx + w, sy + h);
        } else {
            super.invalidate();
        }
    }

    @Override
    public void invalidate(Rect dirty) {
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            int sx = getScrollX();
            int sy = getScrollY();
            super.invalidate(sx, sy, sx + w, sy + h);
        } else {
            super.invalidate(dirty);
        }
    }

    @Override
    public void invalidate(int l, int t, int r, int b) {
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            int sx = getScrollX();
            int sy = getScrollY();
            super.invalidate(sx, sy, sx + w, sy + h);
        } else {
            super.invalidate(l, t, r, b);
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        EpdController.invalidateViewTree(this);
        scheduleEpdRefresh();
    }

    @Override
    protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY);
        EpdController.invalidateViewTree(this);
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        EpdController.invalidateViewTree(this);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean handled = super.onTouchEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            EpdController.invalidateViewTree(this);
            scheduleEpdRefresh();
        }
        return handled;
    }

    /**
     * Дискретное перелистывание содержимого (по 85% высоты видимой области)
     * для аппаратных кнопок листания на ридерах Onyx Boox без промежуточных кадров прокрутки.
     */
    public boolean pageScrollEink(boolean down) {
        if (getChildCount() == 0) return false;
        View child = getChildAt(0);
        if (child == null) return false;

        int viewHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        int contentHeight = child.getHeight();
        if (viewHeight <= 0 || contentHeight <= viewHeight) {
            return false;
        }

        int maxScrollY = Math.max(0, contentHeight - viewHeight);
        int currentY = getScrollY();
        int step = Math.max(1, (int) (viewHeight * 0.85f));
        int targetY = down ? Math.min(maxScrollY, currentY + step) : Math.max(0, currentY - step);

        if (targetY == currentY) {
            return false;
        }

        scrollTo(0, targetY);
        EpdController.requestFullRefresh(getContext(), this);
        return true;
    }

    private void scheduleEpdRefresh() {
        mainHandler.removeCallbacks(fullRefreshRunnable);
        mainHandler.postDelayed(fullRefreshRunnable, EPD_REFRESH_DEBOUNCE_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        mainHandler.removeCallbacks(fullRefreshRunnable);
        super.onDetachedFromWindow();
    }
}
