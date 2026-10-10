package com.onyx.yandexbooks.core.eink;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AbsListView;

import java.lang.reflect.Method;

/**
 * Контроллер экрана E-Ink для устройств Onyx Boox Darwin (1, 3, 5, 6) и всех поколений Onyx.
 * Обеспечивает аппаратный сброс артефактов (ghosting) через специализированные SDK,
 * Rockchip RK3026 View$EINK_MODE, системные интенты и обход бага частичной перерисовки
 * (top-left dirty rect bug при mScrollY > 0) в Android 4.2.2 (API 17) при software rendering.
 */
public class EpdController {

    private static final String TAG = "EpdController";

    private static final String[] ONYX_REFRESH_ACTIONS = new String[] {
            "action.screen.refresh",                         // Rockchip RK3026/RK3128 (Darwin 1, Darwin 3, Darwin 5)
            "android.intent.action.FULL_SCREEN_REFRESH",     // Onyx Boox Android 4.2 / 4.4 standard
            "onyx.intent.action.FULL_SCREEN_REFRESH",        // Onyx Boox alternate intent
            "com.onyx.android.action.SCREEN_REFRESH",        // Onyx Boox SDK broadcast
            "android.intent.action.SCREEN_REFRESH",          // Generic E-Ink framework broadcast
            "com.onyx.intent.action.REFRESH_SCREEN"          // Freescale i.MX6 intent
    };

    private static final String[] EPD_CONTROLLER_CLASS_NAMES = new String[] {
            "com.onyx.android.sdk.device.EpdController",          // Android 4.2 / 4.4 Onyx Darwin legacy SDK
            "com.onyx.android.sdk.api.device.epd.EpdController",  // Android 9+ modern Onyx SDK
            "com.onyx.android.sdk.device.EpdDevice"               // Intermediate Onyx SDK
    };

    /**
     * Запрос аппаратного полного обновления дисплея E-Ink (GC16 Full Refresh).
     */
    public static void requestFullRefresh(Context context, View view) {
        // 1. Попытка вызвать методы Onyx SDK через рефлексию
        tryOnyxSdkRefresh(view);

        // 2. Попытка вызвать нативный Rockchip RK3026 E-Ink метод View.requestEpdMode(EPD_FULL) (Darwin 1)
        tryRockchipViewEpdRefresh(view);

        // 3. Системные широковещательные интенты Onyx Boox (гарантируют вспышку контроллера экрана)
        if (context != null) {
            sendOnyxBroadcasts(context);
        }

        // 4. Принудительная полная инвалидация дерева View и корневого DecorView (обход бага Android 4.2.2)
        if (view != null) {
            invalidateViewTree(view);
        }
    }

    /**
     * Полная инвалидация прокручиваемого контейнера, его дочерних элементов и корневого окна.
     *
     * В Android 4.1/4.2 (API 16/17, Onyx Boox Darwin 1) при отключённом аппаратнам ускорении
     * (android:hardwareAccelerated="false") метод View.invalidate() вычисляет грязный прямоугольник как:
     *   r.set(-mScrollX, -mScrollY, mRight - mLeft - mScrollX, mBottom - mTop - mScrollY)
     * Когда mScrollY > 0 (или mScrollX > 0), родительский ViewGroup.invalidateChildInParent пересекает его
     * с границами [0, 0, width, height], в результате чего в ViewRootImpl.mDirty попадает ТОЛЬКО
     * верхний левый угол [0, 0, width - mScrollX, height - mScrollY], а остальная часть экрана не перерисовывается!
     *
     * Передача компенсированных координат (sx, sy, sx + w, sy + h) и прямая инвалидация DecorView
     * (у которого mScrollX == 0, mScrollY == 0) принудительно расширяет ViewRootImpl.mDirty на весь экран.
     */
    public static void invalidateViewTree(View view) {
        if (view == null) return;
        try {
            int w = view.getWidth();
            int h = view.getHeight();
            int sx = view.getScrollX();
            int sy = view.getScrollY();

            if (w > 0 && h > 0) {
                view.invalidate(sx, sy, sx + w, sy + h);
            } else {
                view.invalidate();
            }

            if (view instanceof ViewGroup) {
                invalidateChildrenRecursive((ViewGroup) view);
            }

            ViewParent parent = view.getParent();
            while (parent != null) {
                if (parent instanceof View) {
                    View pv = (View) parent;
                    int pw = pv.getWidth();
                    int ph = pv.getHeight();
                    int psx = pv.getScrollX();
                    int psy = pv.getScrollY();
                    if (pw > 0 && ph > 0) {
                        pv.invalidate(psx, psy, psx + pw, psy + ph);
                    } else {
                        pv.invalidate();
                    }
                }
                parent = parent.getParent();
            }

            View root = view.getRootView();
            if (root != null) {
                int rw = root.getWidth();
                int rh = root.getHeight();
                if (rw > 0 && rh > 0) {
                    root.invalidate(0, 0, rw, rh);
                } else {
                    root.invalidate();
                }
                root.postInvalidate();
            }
        } catch (Throwable ignored) {}
    }

    private static void invalidateChildrenRecursive(ViewGroup group) {
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (child != null && child.getVisibility() == View.VISIBLE) {
                int cw = child.getWidth();
                int ch = child.getHeight();
                int csx = child.getScrollX();
                int csy = child.getScrollY();
                if (cw > 0 && ch > 0) {
                    child.invalidate(csx, csy, csx + cw, csy + ch);
                } else {
                    child.invalidate();
                }
                if (child instanceof ViewGroup) {
                    invalidateChildrenRecursive((ViewGroup) child);
                }
            }
        }
    }

    /**
     * Настройка ListView для корректной работы на E-Ink экранах (включая Android 4.2.2 Darwin 1).
     * Отключает bitmap scrolling cache, overscroll-эффекты, градиенты затухания и привязывает
     * полную перерисовку дерева при прокрутке.
     */
    public static void configureEinkListView(final Context context, final AbsListView listView) {
        if (listView == null) return;
        try {
            listView.setCacheColorHint(Color.WHITE);
            listView.setScrollingCacheEnabled(false);
            listView.setDrawingCacheEnabled(false);
            listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
            listView.setVerticalFadingEdgeEnabled(false);
            listView.setHorizontalFadingEdgeEnabled(false);
            listView.setFastScrollEnabled(false);
            listView.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    invalidateViewTree(view);
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                        requestFullRefresh(context, view);
                    }
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                    invalidateViewTree(view);
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * Поддержка нативного драйвера Rockchip RK3026 E-Ink в Android 4.2.2 (Onyx Boox Darwin 1),
     * где методы управления контроллером встроены непосредственно в класс android.view.View
     * через enum android.view.View$EINK_MODE (EPD_AUTO, EPD_FULL, EPD_A2, EPD_PART).
     */
    private static boolean tryRockchipViewEpdRefresh(View view) {
        if (view == null) return false;
        try {
            Class<?> einkModeClass = Class.forName("android.view.View$EINK_MODE");
            if (einkModeClass.isEnum()) {
                Object epdFull = null;
                Object[] constants = einkModeClass.getEnumConstants();
                if (constants != null) {
                    for (Object c : constants) {
                        String name = c.toString();
                        if ("EPD_FULL".equalsIgnoreCase(name) || "EPD_GC16".equalsIgnoreCase(name)) {
                            epdFull = c;
                            break;
                        }
                    }
                }
                if (epdFull != null) {
                    Method requestEpdMode = View.class.getMethod("requestEpdMode", einkModeClass);
                    requestEpdMode.invoke(view, epdFull);
                    View root = view.getRootView();
                    if (root != null && root != view) {
                        requestEpdMode.invoke(root, epdFull);
                    }
                    Log.d(TAG, "Triggered Rockchip View.requestEpdMode(" + epdFull + ")");
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean tryOnyxSdkRefresh(View view) {
        for (String className : EPD_CONTROLLER_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(className);

                // А) Поиск метода refreshScreen(View, ...)
                for (Method m : clazz.getMethods()) {
                    if ("refreshScreen".equals(m.getName())) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 1 && params[0].isAssignableFrom(View.class)) {
                            m.invoke(null, view);
                            Log.d(TAG, "Triggered " + className + ".refreshScreen(View)");
                            return true;
                        } else if (params.length == 2 && params[0].isAssignableFrom(View.class)) {
                            // Второй параметр может быть String, int или UpdateMode enum
                            if (params[1] == String.class) {
                                m.invoke(null, view, "GC");
                                Log.d(TAG, "Triggered " + className + ".refreshScreen(View, 'GC')");
                                return true;
                            } else if (params[1].isEnum()) {
                                Object enumConst = findGcEnumConstant(params[1]);
                                if (enumConst != null) {
                                    m.invoke(null, view, enumConst);
                                    Log.d(TAG, "Triggered " + className + ".refreshScreen(View, " + enumConst + ")");
                                    return true;
                                }
                            } else if (params[1] == int.class) {
                                m.invoke(null, view, 1); // 1 = GC mode
                                Log.d(TAG, "Triggered " + className + ".refreshScreen(View, 1)");
                                return true;
                            }
                        }
                    } else if ("invalidate".equals(m.getName())) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 2 && params[0].isAssignableFrom(View.class) && params[1].isEnum()) {
                            Object enumConst = findGcEnumConstant(params[1]);
                            if (enumConst != null) {
                                m.invoke(null, view, enumConst);
                                Log.d(TAG, "Triggered " + className + ".invalidate(View, " + enumConst + ")");
                                return true;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Класс не найден на данном устройстве, пробуем следующий
            }
        }
        return false;
    }

    private static Object findGcEnumConstant(Class<?> enumClass) {
        try {
            Object[] constants = enumClass.getEnumConstants();
            if (constants != null) {
                for (Object c : constants) {
                    String name = c.toString();
                    if ("GC".equalsIgnoreCase(name) || "GC16".equalsIgnoreCase(name) || "CLEAR".equalsIgnoreCase(name)) {
                        return c;
                    }
                }
                if (constants.length > 0) {
                    return constants[0];
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void sendOnyxBroadcasts(Context context) {
        for (String action : ONYX_REFRESH_ACTIONS) {
            try {
                Intent intent = new Intent(action);
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                context.sendBroadcast(intent);
            } catch (Throwable ignored) {}
        }
    }
}

