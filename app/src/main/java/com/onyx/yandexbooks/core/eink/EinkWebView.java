package com.onyx.yandexbooks.core.eink;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;

/**
 * Специализированный WebView для экранов E-Ink (Onyx Boox Darwin 1 / 3 / 5 / 6).
 *
 * Исправляет баг Android 4.2.2 (API 17, Onyx Boox Darwin 1) с частичной перерисовкой
 * только верхнего левого угла при прокрутке WebView (mScrollY > 0 / mScrollX > 0)
 * в режиме android:hardwareAccelerated="false" (WebViewClassic software tile rendering).
 */
public class EinkWebView extends WebView {

    private static final long EPD_REFRESH_DEBOUNCE_MS = 220L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable fullRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            EpdController.requestFullRefresh(getContext(), EinkWebView.this);
        }
    };

    public EinkWebView(Context context) {
        super(context);
        init();
    }

    public EinkWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EinkWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setVerticalFadingEdgeEnabled(false);
        setHorizontalFadingEdgeEnabled(false);
        setDrawingCacheEnabled(false);
        setBackgroundColor(Color.WHITE);
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
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            EpdController.invalidateViewTree(this);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            EpdController.invalidateViewTree(this);
            scheduleEpdRefresh();
        }
        return handled;
    }

    /**
     * Дискретное перелистывание страницы WebView аппаратными кнопками ридера.
     */
    public boolean pageScrollEink(boolean down) {
        int viewHeight = getHeight();
        if (viewHeight <= 0) return false;
        int step = Math.max(1, (int) (viewHeight * 0.75f));
        int currentY = getScrollY();
        int targetY = down ? currentY + step : Math.max(0, currentY - step);
        scrollTo(getScrollX(), targetY);
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
