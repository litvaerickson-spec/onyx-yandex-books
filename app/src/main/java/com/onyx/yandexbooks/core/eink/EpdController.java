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
     * Проверка, запущено ли приложение на устройстве производителя Onyx Boox.
     */
    public static boolean isOnyxDevice() {
        try {
            String m = android.os.Build.MANUFACTURER != null ? android.os.Build.MANUFACTURER.toLowerCase() : "";
            String b = android.os.Build.BRAND != null ? android.os.Build.BRAND.toLowerCase() : "";
            String f = android.os.Build.FINGERPRINT != null ? android.os.Build.FINGERPRINT.toLowerCase() : "";
            return m.contains("onyx") || b.contains("onyx") || f.contains("onyx");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Включение аппаратного режима Regal (устраняет артефакты E-Ink без мигания экрана).
     */
    public static void enableRegalMode(View view) {
        if (!isOnyxDevice()) return;
        try {
            for (String className : EPD_CONTROLLER_CLASS_NAMES) {
                try {
                    Class<?> clazz = Class.forName(className);
                    Method m = clazz.getMethod("enableRegal", boolean.class);
                    m.invoke(null, true);
                    try {
                        Method appScope = clazz.getMethod("applyAppScopeUpdate", boolean.class);
                        appScope.invoke(null, true);
                    } catch (Throwable ignored) {}
                    Log.d(TAG, "Onyx EpdController.enableRegal(true) activated via " + className);
                    return;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Прямой вызов одиночного глубокого обновления экрана (GC) через официальный Onyx SDK.
     */
    public static void applyGCOnce(View view) {
        if (!isOnyxDevice()) return;
        try {
            for (String className : EPD_CONTROLLER_CLASS_NAMES) {
                try {
                    Class<?> clazz = Class.forName(className);
                    if (view != null) {
                        try {
                            Method m = clazz.getMethod("applyGCOnce", View.class);
                            m.invoke(null, view);
                            Log.d(TAG, "Onyx applyGCOnce(view) executed");
                            return;
                        } catch (NoSuchMethodException ignored) {}
                    }
                    Method mNoArg = clazz.getMethod("applyGCOnce");
                    mNoArg.invoke(null);
                    Log.d(TAG, "Onyx applyGCOnce() executed");
                    return;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static final int ONYX_LIGHT_TYPE_FL = 1;
    private static final int ONYX_LIGHT_TYPE_CTM_WARM = 2;
    private static final int ONYX_LIGHT_TYPE_CTM_COLD = 3;
    private static final int ONYX_LIGHT_TYPE_CTM_ALL = 4;
    private static final int ONYX_LIGHT_TYPE_TEMP = 6;
    private static final int ONYX_LIGHT_TYPE_CTM_BR = 7;

    /**
     * Считывает текущие уровни подсветки (0..100%) с аппаратного контроллера Onyx, если доступно.
     * Возвращает массив [coldPercent, warmPercent] или null при недоступности.
     */
    public static int[] readFrontlightPercent(Context context) {
        if (!isOnyxDevice() || context == null) return null;
        try {
            Class<?> dc = Class.forName("android.onyx.hardware.DeviceController");
            Method mCheckCtm = findMethodSafe(dc, "checkCTM");
            Method mHasFl = findMethodSafe(dc, "hasFLBrightness", Context.class);
            Method mHasCtm = findMethodSafe(dc, "hasCTMBrightness", Context.class);
            Method mGetVal = findMethodSafe(dc, "getLightValue", int.class);
            if (mGetVal == null) mGetVal = findMethodSafe(dc, "getLightValues", int.class);
            Method mGetMax = findMethodSafe(dc, "getMaxLightValue", int.class);
            if (mGetMax == null) mGetMax = findMethodSafe(dc, "getMaxLightValues", int.class);

            boolean isCtm = mCheckCtm != null && Boolean.TRUE.equals(mCheckCtm.invoke(dc));
            int maxTemp = getIntSafe(mGetMax, dc, ONYX_LIGHT_TYPE_TEMP, 0);
            int maxWarm = getIntSafe(mGetMax, dc, ONYX_LIGHT_TYPE_CTM_WARM, 0);
            boolean hasCtm = mHasCtm != null && Boolean.TRUE.equals(mHasCtm.invoke(dc, context));
            boolean hasFl = mHasFl != null && Boolean.TRUE.equals(mHasFl.invoke(dc, context));

            if (isCtm || maxTemp > 0) {
                int maxBr = Math.max(1, getIntSafe(mGetMax, dc, ONYX_LIGHT_TYPE_CTM_BR, 255));
                int maxT = Math.max(1, maxTemp > 0 ? maxTemp : 255);
                int curBr = getIntSafe(mGetVal, dc, ONYX_LIGHT_TYPE_CTM_BR, -1);
                int curT = getIntSafe(mGetVal, dc, ONYX_LIGHT_TYPE_TEMP, -1);
                if (curBr >= 0 && curT >= 0) {
                    return new int[]{
                            Math.max(0, Math.min(100, Math.round(curBr * 100f / maxBr))),
                            Math.max(0, Math.min(100, Math.round(curT * 100f / maxT)))
                    };
                }
            } else if (hasCtm || maxWarm > 0) {
                int maxC = Math.max(1, getIntSafe(mGetMax, dc, ONYX_LIGHT_TYPE_CTM_COLD, 255));
                int maxW = Math.max(1, maxWarm > 0 ? maxWarm : 255);
                int curC = getIntSafe(mGetVal, dc, ONYX_LIGHT_TYPE_CTM_COLD, -1);
                int curW = getIntSafe(mGetVal, dc, ONYX_LIGHT_TYPE_CTM_WARM, -1);
                if (curC >= 0 && curW >= 0) {
                    return new int[]{
                            Math.max(0, Math.min(100, Math.round(curC * 100f / maxC))),
                            Math.max(0, Math.min(100, Math.round(curW * 100f / maxW)))
                    };
                }
            } else if (hasFl) {
                int maxF = Math.max(1, getIntSafe(mGetMax, dc, ONYX_LIGHT_TYPE_FL, 255));
                int curF = getIntSafe(mGetVal, dc, ONYX_LIGHT_TYPE_FL, -1);
                if (curF >= 0) {
                    int pct = Math.max(0, Math.min(100, Math.round(curF * 100f / maxF)));
                    return new int[]{pct, pct};
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Установка цветовой температуры и яркости подсветки (CTM: холодный / теплый свет).
     * Поддерживает legacy-контроллер Onyx Darwin 1–6 (android.onyx.hardware.DeviceController),
     * современный BrightnessController SDK, sysfs (/sys/class/backlight/...) и системные настройки.
     *
     * @return true, если удалось применить через аппаратный контроллер или sysfs.
     */
    public static boolean setFrontlight(Context context, int coldPercent, int warmPercent, boolean warmWasEdited) {
        if (context == null) return false;
        int cold = Math.max(0, Math.min(100, coldPercent));
        int warm = Math.max(0, Math.min(100, warmPercent));

        // 1. Классический SDK Onyx Boox (Darwin 3/5/6/7, Monte Cristo, Android 4.2–9): android.onyx.hardware.DeviceController
        if (tryOnyxDeviceControllerFrontlight(context, cold, warm, warmWasEdited)) {
            return true;
        }

        // 2. Современный Onyx SDK: com.onyx.android.sdk.api.device.brightness.BrightnessController
        if (tryOnyxModernBrightnessController(context, cold, warm)) {
            return true;
        }

        // 3. Прямой доступ через sysfs (/sys/class/backlight/...) на ядрах Rockchip / Qualcomm Onyx
        if (trySysfsFrontlight(cold, warm, warmWasEdited)) {
            return true;
        }

        return false;
    }

    public static void setFrontlight(Context context, int cold, int warm) {
        setFrontlight(context, cold, warm, true);
    }

    private static boolean tryOnyxDeviceControllerFrontlight(Context context, int coldPercent, int warmPercent, boolean warmWasEdited) {
        try {
            Class<?> dc = Class.forName("android.onyx.hardware.DeviceController");
            Method mSetVal = findMethodSafe(dc, "setLightValue", int.class, int.class);
            if (mSetVal == null) mSetVal = findMethodSafe(dc, "setLightValues", int.class, int.class);
            if (mSetVal == null) return false;

            Method mCheckCtm = findMethodSafe(dc, "checkCTM");
            Method mHasFl = findMethodSafe(dc, "hasFLBrightness", Context.class);
            Method mHasCtm = findMethodSafe(dc, "hasCTMBrightness", Context.class);
            Method mGetMax = findMethodSafe(dc, "getMaxLightValue", int.class);
            if (mGetMax == null) mGetMax = findMethodSafe(dc, "getMaxLightValues", int.class);
            Method mOpenFl = findMethodSafe(dc, "openFrontLight", int.class);
            Method mCloseFl = findMethodSafe(dc, "closeFrontLight", int.class);
            Method mIsOn = findMethodSafe(dc, "isLightOn", Context.class, int.class);
            if (mIsOn == null) mIsOn = findMethodSafe(dc, "isLightOn", int.class);

            boolean isCtm = mCheckCtm != null && Boolean.TRUE.equals(mCheckCtm.invoke(dc));
            int maxTemp = getIntSafe(mGetMax, dc, ONYX_LIGHT_TYPE_TEMP, 0);
            int maxWarm = getIntSafe(mGetMax, dc, ONYX_LIGHT_TYPE_CTM_WARM, 0);
            boolean hasCtm = mHasCtm != null && Boolean.TRUE.equals(mHasCtm.invoke(dc, context));
            boolean hasFl = mHasFl != null && Boolean.TRUE.equals(mHasFl.invoke(dc, context));

            if (isCtm || maxTemp > 0) {
                int maxBr = Math.max(1, getIntSafe(mGetMax, dc, ONYX_LIGHT_TYPE_CTM_BR, 255));
                int maxT = Math.max(1, maxTemp > 0 ? maxTemp : 255);
                int brVal = Math.round((coldPercent * maxBr) / 100f);
                int tempVal = Math.round((warmPercent * maxT) / 100f);
                if (brVal > 0 || tempVal > 0) {
                    ensureDeviceChannelOpen(context, dc, mIsOn, mOpenFl, ONYX_LIGHT_TYPE_CTM_ALL);
                }
                mSetVal.invoke(dc, ONYX_LIGHT_TYPE_CTM_BR, brVal);
                mSetVal.invoke(dc, ONYX_LIGHT_TYPE_TEMP, tempVal);
                if (brVal == 0 && mCloseFl != null) {
                    try { mCloseFl.invoke(dc, ONYX_LIGHT_TYPE_CTM_ALL); } catch (Throwable ignored) {}
                }
                Log.d(TAG, "Onyx DeviceController CTM set: br=" + brVal + "/" + maxBr + ", temp=" + tempVal + "/" + maxT);
                return true;
            } else if (hasCtm || maxWarm > 0) {
                int maxC = Math.max(1, getIntSafe(mGetMax, dc, ONYX_LIGHT_TYPE_CTM_COLD, 255));
                int maxW = Math.max(1, maxWarm > 0 ? maxWarm : 255);
                int cVal = Math.round((coldPercent * maxC) / 100f);
                int wVal = Math.round((warmPercent * maxW) / 100f);
                if (cVal > 0) ensureDeviceChannelOpen(context, dc, mIsOn, mOpenFl, ONYX_LIGHT_TYPE_CTM_COLD);
                if (wVal > 0) ensureDeviceChannelOpen(context, dc, mIsOn, mOpenFl, ONYX_LIGHT_TYPE_CTM_WARM);
                mSetVal.invoke(dc, ONYX_LIGHT_TYPE_CTM_COLD, cVal);
                mSetVal.invoke(dc, ONYX_LIGHT_TYPE_CTM_WARM, wVal);
                if (cVal == 0 && mCloseFl != null) {
                    try { mCloseFl.invoke(dc, ONYX_LIGHT_TYPE_CTM_COLD); } catch (Throwable ignored) {}
                }
                if (wVal == 0 && mCloseFl != null) {
                    try { mCloseFl.invoke(dc, ONYX_LIGHT_TYPE_CTM_WARM); } catch (Throwable ignored) {}
                }
                Log.d(TAG, "Onyx DeviceController WARM_AND_COLD set: cold=" + cVal + ", warm=" + wVal);
                return true;
            } else if (hasFl || getIntSafe(mGetMax, dc, ONYX_LIGHT_TYPE_FL, 0) > 0) {
                int maxF = Math.max(1, getIntSafe(mGetMax, dc, ONYX_LIGHT_TYPE_FL, 255));
                int targetPct = warmWasEdited ? warmPercent : coldPercent;
                int fVal = Math.round((targetPct * maxF) / 100f);
                if (fVal > 0) {
                    ensureDeviceChannelOpen(context, dc, mIsOn, mOpenFl, ONYX_LIGHT_TYPE_FL);
                }
                mSetVal.invoke(dc, ONYX_LIGHT_TYPE_FL, fVal);
                if (fVal == 0 && mCloseFl != null) {
                    try { mCloseFl.invoke(dc, ONYX_LIGHT_TYPE_FL); } catch (Throwable ignored) {}
                }
                Log.d(TAG, "Onyx DeviceController FL set: val=" + fVal + "/" + maxF);
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "DeviceController frontlight failed: " + t.getMessage());
        }
        return false;
    }

    private static void ensureDeviceChannelOpen(Context context, Class<?> dc, Method mIsOn, Method mOpenFl, int channel) {
        if (mOpenFl == null) return;
        try {
            boolean isOn = false;
            if (mIsOn != null) {
                Object res = (mIsOn.getParameterTypes().length == 2)
                        ? mIsOn.invoke(dc, context, channel)
                        : mIsOn.invoke(dc, channel);
                isOn = Boolean.TRUE.equals(res);
            }
            if (!isOn) {
                mOpenFl.invoke(dc, channel);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean tryOnyxModernBrightnessController(Context context, int coldPercent, int warmPercent) {
        try {
            Class<?> brightClazz = Class.forName("com.onyx.android.sdk.api.device.brightness.BrightnessController");
            Class<?> typeClazz = Class.forName("com.onyx.android.sdk.api.device.brightness.BrightnessType");
            Object coldType = null;
            Object warmType = null;
            Object flType = null;
            for (Object c : typeClazz.getEnumConstants()) {
                String name = c.toString();
                if ("Cold".equalsIgnoreCase(name) || "CTM_COLD".equalsIgnoreCase(name)) coldType = c;
                if ("Warm".equalsIgnoreCase(name) || "CTM_WARM".equalsIgnoreCase(name)) warmType = c;
                if ("FL".equalsIgnoreCase(name) || "FRONTLIGHT".equalsIgnoreCase(name)) flType = c;
            }
            Method setBrightness = brightClazz.getMethod("setBrightness", Context.class, int.class, typeClazz);
            boolean applied = false;
            if (coldType != null) {
                setBrightness.invoke(null, context, coldPercent, coldType);
                applied = true;
            }
            if (warmType != null) {
                setBrightness.invoke(null, context, warmPercent, warmType);
                applied = true;
            }
            if (!applied && flType != null) {
                setBrightness.invoke(null, context, Math.max(coldPercent, warmPercent), flType);
                applied = true;
            }
            if (applied) {
                Log.d(TAG, "Onyx BrightnessController set: cold=" + coldPercent + ", warm=" + warmPercent);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean trySysfsFrontlight(int coldPercent, int warmPercent, boolean warmWasEdited) {
        boolean anyWritten = false;
        java.io.File whiteFile = new java.io.File("/sys/class/backlight/white/brightness");
        java.io.File warmFile = new java.io.File("/sys/class/backlight/warm/brightness");
        if (whiteFile.exists() && whiteFile.canWrite()) {
            if (writeIntToFile(whiteFile, Math.round(coldPercent * 255f / 100f))) anyWritten = true;
        }
        if (warmFile.exists() && warmFile.canWrite()) {
            if (writeIntToFile(warmFile, Math.round(warmPercent * 255f / 100f))) anyWritten = true;
        }
        if (anyWritten) return true;

        java.io.File rkFile = new java.io.File("/sys/class/backlight/rk28_bl/brightness");
        if (rkFile.exists() && rkFile.canWrite()) {
            int val = Math.round(((warmWasEdited ? warmPercent : coldPercent) * 255f) / 100f);
            return writeIntToFile(rkFile, val);
        }
        return false;
    }

    private static boolean writeIntToFile(java.io.File file, int value) {
        java.io.FileWriter fw = null;
        try {
            fw = new java.io.FileWriter(file);
            fw.write(String.valueOf(value));
            fw.flush();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (fw != null) try { fw.close(); } catch (Throwable ignored) {}
        }
    }

    private static Method findMethodSafe(Class<?> clazz, String name, Class<?>... params) {
        try {
            return clazz.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int getIntSafe(Method m, Object receiver, int arg, int fallback) {
        if (m == null) return fallback;
        try {
            Object res = m.invoke(receiver, arg);
            if (res instanceof Number) return ((Number) res).intValue();
        } catch (Throwable ignored) {}
        return fallback;
    }

    /**
     * Запрос аппаратного полного обновления дисплея E-Ink (GC16 Full Refresh).
     */
    public static void requestFullRefresh(Context context, View view) {
        if (view == null && context instanceof android.app.Activity) {
            try {
                android.view.Window w = ((android.app.Activity) context).getWindow();
                if (w != null) {
                    view = w.getDecorView();
                }
            } catch (Throwable ignored) {}
        }

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
        configureEinkListView(context, listView, null);
    }

    public static void configureEinkListView(final Context context, final AbsListView listView,
                                             final AbsListView.OnScrollListener chainedListener) {
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
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                        View target = view.getRootView() != null ? view.getRootView() : view;
                        requestFullRefresh(context, target);
                    } else {
                        invalidateViewTree(view);
                    }
                    if (chainedListener != null) {
                        chainedListener.onScrollStateChanged(view, scrollState);
                    }
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                    invalidateViewTree(view);
                    if (chainedListener != null) {
                        chainedListener.onScroll(view, firstVisibleItem, visibleItemCount, totalItemCount);
                    }
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
                        if ("EPD_FULL".equalsIgnoreCase(name) || "EPD_GC16".equalsIgnoreCase(name)
                                || "FULL".equalsIgnoreCase(name) || "GC16".equalsIgnoreCase(name)) {
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
        if (view == null) return false;
        for (String className : EPD_CONTROLLER_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(className);
                Method refreshViewEnum = null;
                Method invalidateViewEnum = null;
                Method refreshViewString = null;
                Method refreshViewInt = null;
                Method refreshViewOnly = null;

                for (Method m : clazz.getMethods()) {
                    String name = m.getName();
                    Class<?>[] params = m.getParameterTypes();
                    if ("refreshScreen".equals(name)) {
                        if (params.length == 2 && params[0].isAssignableFrom(View.class)) {
                            if (params[1].isEnum()) {
                                refreshViewEnum = m;
                            } else if (params[1] == String.class) {
                                refreshViewString = m;
                            } else if (params[1] == int.class) {
                                refreshViewInt = m;
                            }
                        } else if (params.length == 1 && params[0].isAssignableFrom(View.class)) {
                            refreshViewOnly = m;
                        }
                    } else if ("invalidate".equals(name)
                            && params.length == 2 && params[0].isAssignableFrom(View.class) && params[1].isEnum()) {
                        invalidateViewEnum = m;
                    }
                }

                if (refreshViewEnum != null) {
                    Object enumConst = findGcEnumConstant(refreshViewEnum.getParameterTypes()[1]);
                    if (enumConst != null) {
                        refreshViewEnum.invoke(null, view, enumConst);
                        Log.d(TAG, "Triggered " + className + ".refreshScreen(View, " + enumConst + ")");
                        return true;
                    }
                }
                if (invalidateViewEnum != null) {
                    Object enumConst = findGcEnumConstant(invalidateViewEnum.getParameterTypes()[1]);
                    if (enumConst != null) {
                        invalidateViewEnum.invoke(null, view, enumConst);
                        Log.d(TAG, "Triggered " + className + ".invalidate(View, " + enumConst + ")");
                        return true;
                    }
                }
                if (refreshViewString != null) {
                    refreshViewString.invoke(null, view, "GC");
                    Log.d(TAG, "Triggered " + className + ".refreshScreen(View, 'GC')");
                    return true;
                }
                if (refreshViewInt != null) {
                    refreshViewInt.invoke(null, view, 1);
                    Log.d(TAG, "Triggered " + className + ".refreshScreen(View, 1)");
                    return true;
                }
                if (refreshViewOnly != null) {
                    refreshViewOnly.invoke(null, view);
                    Log.d(TAG, "Triggered " + className + ".refreshScreen(View)");
                    return true;
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
                    if ("GC".equalsIgnoreCase(name) || "GC16".equalsIgnoreCase(name)
                            || "CLEAR".equalsIgnoreCase(name) || "FULL".equalsIgnoreCase(name)) {
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

