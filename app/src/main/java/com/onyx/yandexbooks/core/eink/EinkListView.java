package com.onyx.yandexbooks.core.eink;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;

/**
 * Специализированный ListView для экранов E-Ink (Onyx Boox Darwin 1 / 3 / 5 / 6).
 *
 * Решает критическую проблему Android 4.2.2 (API 17, Onyx Boox Darwin 1, Rockchip RK3026)
 * при отключенном аппаратном ускорении (hardwareAccelerated=false), когда при прокрутке списка пальцем
 * стандартный AbsListView выполняет частичную перерисовку тайлов (грязных прямоугольников),
 * из-за чего контроллер дисплея обновляет только верхний левый угол, а остальная часть экрана
 * превращается в «мешанину» из накладывающихся кадров.
 *
 * Гарантирует:
 * 1. Принудительную полноэкранную инвалидацию корневого окна при любых вызовах invalidate().
 * 2. Отключение bitmap scrolling cache, overscroll-анимаций и градиентов затухания.
 * 3. Автоматический полноэкранный EPD-сброс по окончании жеста прокрутки (ACTION_UP / ACTION_CANCEL)
 *    и при остановке прокрутки.
 * 4. Поддержку дискретного постраничного листания по аппаратным клавишам без промежуточных кадров.
 */
public class EinkListView extends ListView {

    private static final long EPD_REFRESH_DEBOUNCE_MS = 160L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable fullRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            View target = getRootView() != null ? getRootView() : EinkListView.this;
            EpdController.requestFullRefresh(getContext(), target);
        }
    };

    public EinkListView(Context context) {
        super(context);
        init();
    }

    public EinkListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EinkListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setCacheColorHint(Color.WHITE);
        setScrollingCacheEnabled(false);
        setDrawingCacheEnabled(false);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setVerticalFadingEdgeEnabled(false);
        setHorizontalFadingEdgeEnabled(false);
        setFastScrollEnabled(false);
        setSmoothScrollbarEnabled(false);
    }

    @Override
    public void invalidate() {
        View root = getRootView();
        if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
            root.invalidate(0, 0, root.getWidth(), root.getHeight());
        } else {
            super.invalidate();
        }
    }

    @Override
    public void invalidate(Rect dirty) {
        View root = getRootView();
        if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
            root.invalidate(0, 0, root.getWidth(), root.getHeight());
        } else {
            super.invalidate(dirty);
        }
    }

    @Override
    public void invalidate(int l, int t, int r, int b) {
        View root = getRootView();
        if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
            root.invalidate(0, 0, root.getWidth(), root.getHeight());
        } else {
            super.invalidate(l, t, r, b);
        }
    }

    @Override
    public void invalidateViews() {
        View root = getRootView();
        if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
            root.invalidate(0, 0, root.getWidth(), root.getHeight());
        } else {
            super.invalidateViews();
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        EpdController.invalidateViewTree(this);
        scheduleEpdRefresh();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean handled = super.onTouchEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            EpdController.invalidateViewTree(this);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            EpdController.invalidateViewTree(this);
            scheduleEpdRefresh();
        }
        return handled;
    }

    private void scheduleEpdRefresh() {
        mainHandler.removeCallbacks(fullRefreshRunnable);
        mainHandler.postDelayed(fullRefreshRunnable, EPD_REFRESH_DEBOUNCE_MS);
    }

    /**
     * Дискретное постраничное листание списка для аппаратных кнопок листания на E-Ink ридерах.
     */
    public boolean pageScrollEink(boolean down) {
        int count = getCount();
        if (count == 0) return false;

        int first = getFirstVisiblePosition();
        int last = getLastVisiblePosition();
        int childCount = getChildCount();
        int listTop = getPaddingTop();
        int listBottom = getHeight() - getPaddingBottom();
        View firstChild = childCount > 0 ? getChildAt(0) : null;
        View lastChild = childCount > 0 ? getChildAt(childCount - 1) : null;

        int target;
        if (down) {
            boolean atBottom = (last >= count - 1) && (lastChild == null || lastChild.getBottom() <= listBottom);
            if (atBottom) {
                return false;
            }
            if (lastChild != null && lastChild.getBottom() > listBottom && last > first) {
                // Частично обрезанная нижняя карточка становится первой сверху на новой странице
                target = last;
            } else {
                target = Math.min(count - 1, last + 1);
            }
            if (target == first && first < count - 1) {
                target = first + 1;
            }
            if (target == first && lastChild != null && lastChild.getBottom() <= listBottom) {
                return false;
            }
        } else {
            boolean atTop = (first <= 0) && (firstChild == null || firstChild.getTop() >= listTop);
            if (atTop) {
                return false;
            }
            if (first == 0 && firstChild != null && firstChild.getTop() < listTop) {
                target = 0;
            } else {
                int viewportH = Math.max(1, listBottom - listTop);
                int visibleSpan = (firstChild != null && lastChild != null)
                        ? Math.max(1, lastChild.getBottom() - firstChild.getTop())
                        : viewportH;
                int avgItemH = Math.max(1, visibleSpan / Math.max(1, childCount));
                int step = Math.max(1, viewportH / avgItemH);
                target = Math.max(0, first - step);
                if (target == first && first > 0) {
                    target = first - 1;
                }
            }
        }

        setSelectionFromTop(target, listTop);
        EpdController.invalidateViewTree(this);
        scheduleEpdRefresh();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        mainHandler.removeCallbacks(fullRefreshRunnable);
        super.onDetachedFromWindow();
    }
}
