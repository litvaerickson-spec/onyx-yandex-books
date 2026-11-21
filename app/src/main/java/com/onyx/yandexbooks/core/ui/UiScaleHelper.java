package com.onyx.yandexbooks.core.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.Log;

import java.util.WeakHashMap;

/**
 * Помощник динамического масштабирования интерфейса и шрифтов приложения (UI & Font Scale).
 * Позволяет адаптировать размеры кнопок, вкладок, списков и текстов для экранов Onyx Boox Darwin.
 */
public class UiScaleHelper {

    private static final String TAG = "UiScaleHelper";
    private static final WeakHashMap<android.view.View, Integer> sBaseViewHeights = new WeakHashMap<android.view.View, Integer>();

    private static float sBaseDensity = -1.0f;
    private static float sBaseScaledDensity = -1.0f;
    private static int sBaseDensityDpi = -1;
    private static float sBaseFontScale = 1.0f;

    /**
     * Первичная фиксация эталонных физических параметров экрана до применения пользовательских коэффициентов.
     */
    public static synchronized void init(Context context) {
        if (context == null) return;
        if (sBaseDensity <= 0) {
            Resources res = context.getResources();
            if (res != null) {
                DisplayMetrics dm = res.getDisplayMetrics();
                Configuration cfg = res.getConfiguration();
                if (dm != null) {
                    sBaseDensity = dm.density;
                    sBaseScaledDensity = dm.scaledDensity;
                    sBaseDensityDpi = dm.densityDpi;
                }
                if (cfg != null && cfg.fontScale > 0) {
                    sBaseFontScale = cfg.fontScale;
                }
            }
        }
    }

    /**
     * Ограничение масштаба безопасным диапазоном (от 85% до 200%).
     */
    public static float sanitizeScale(float scaleFactor) {
        if (scaleFactor < 0.85f) return 1.0f;
        if (scaleFactor > 2.00f) return 2.00f;
        return scaleFactor;
    }

    /**
     * Применение масштабирования к ресурсам переданного контекста/активности.
     */
    public static void applyScale(Context context, float scaleFactor) {
        if (context == null) return;
        init(context);
        if (sBaseDensity <= 0) return;

        float effective = sanitizeScale(scaleFactor);
        try {
            Resources res = context.getResources();
            if (res == null) return;
            DisplayMetrics dm = res.getDisplayMetrics();
            Configuration cfg = res.getConfiguration();

            if (dm != null) {
                dm.density = sBaseDensity * effective;
                dm.scaledDensity = sBaseScaledDensity * effective;
                dm.densityDpi = Math.round(sBaseDensityDpi * effective);
            }

            if (cfg != null) {
                if (android.os.Build.VERSION.SDK_INT >= 17) {
                    cfg.densityDpi = Math.round(sBaseDensityDpi * effective);
                    cfg.fontScale = sBaseFontScale;
                } else {
                    cfg.fontScale = sBaseFontScale * effective;
                }
                res.updateConfiguration(cfg, dm);
                if (dm != null) {
                    dm.density = sBaseDensity * effective;
                    dm.scaledDensity = sBaseScaledDensity * effective;
                    dm.densityDpi = Math.round(sBaseDensityDpi * effective);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update configuration scale: " + e.getMessage());
        }
    }

    public static float getBaseDensity() {
        return sBaseDensity > 0 ? sBaseDensity : 1.33f;
    }

    public static float getBaseFontScale() {
        return sBaseFontScale > 0 ? sBaseFontScale : 1.0f;
    }

    /**
     * Обёртка базового контекста (API 17+) для Activity, чтобы система Android
     * не сбрасывала DisplayMetrics/fontScale на 100% при возврате из другой Activity.
     */
    public static Context wrapBaseContext(Context newBase, float scaleFactor) {
        if (newBase == null) return null;
        init(newBase);
        float effective = sanitizeScale(scaleFactor);
        if (Math.abs(effective - 1.0f) < 0.04f) {
            effective = 1.0f;
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 17 && sBaseDensityDpi > 0) {
                Resources res = newBase.getResources();
                if (res != null) {
                    Configuration curCfg = res.getConfiguration();
                    int targetDpi = Math.round(sBaseDensityDpi * effective);
                    if (curCfg.densityDpi != targetDpi || Math.abs(curCfg.fontScale - sBaseFontScale) > 0.01f) {
                        Configuration cfg = new Configuration(curCfg);
                        cfg.densityDpi = targetDpi;
                        cfg.fontScale = sBaseFontScale;
                        return newBase.createConfigurationContext(cfg);
                    }
                    return newBase;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "wrapBaseContext fallback: " + t.getMessage());
        }
        return newBase;
    }

    /**
     * Проверяет, откатила ли или сместила ли система применённый масштаб относительно целевого коэффициента.
     */
    public static boolean hasScaleDrifted(Context context, float targetScale) {
        if (context == null || sBaseDensity <= 0) return false;
        float effective = sanitizeScale(targetScale);
        if (Math.abs(effective - 1.0f) < 0.04f) {
            effective = 1.0f;
        }
        try {
            Resources res = context.getResources();
            if (res == null) return false;
            DisplayMetrics dm = res.getDisplayMetrics();
            float expectedDensity = sBaseDensity * effective;
            boolean densityDrifted = dm != null && Math.abs(dm.density - expectedDensity) > (0.06f * sBaseDensity);
            boolean scaledDrifted = dm != null && Math.abs(dm.scaledDensity - (sBaseScaledDensity * effective)) > (0.08f * sBaseScaledDensity);
            return densityDrifted || scaledDrifted;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Дополнительный обход View-дерева для гарантированного масштабирования высот панелей и кнопок
     * на прошивках Onyx OS с Android 10–14 (Go 10.3, Bigme B6), которые могут кэшировать DisplayMetrics.
     */
    public static void applyViewTreeScale(android.view.View root, float scaleFactor) {
        if (root == null) return;
        float effective = sanitizeScale(scaleFactor);
        if (Math.abs(effective - 1.0f) < 0.04f) {
            effective = 1.0f;
        }
        try {
            scaleViewNodeRecursive(root, effective);
        } catch (Throwable ignored) {}
    }

    private static void scaleViewNodeRecursive(android.view.View view, float factor) {
        if (view == null) return;
        try {
            android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null && lp.height > 0) {
                Integer origHeight;
                synchronized (sBaseViewHeights) {
                    origHeight = sBaseViewHeights.get(view);
                    if (origHeight == null) {
                        origHeight = lp.height;
                        sBaseViewHeights.put(view, origHeight);
                    }
                }
                float boost = (factor > 1.25f) ? (1.0f + (factor - 1.0f) * 0.25f) : 1.0f;
                int desiredHeight = Math.round(origHeight * boost);
                if (lp.height != desiredHeight) {
                    lp.height = desiredHeight;
                    view.setLayoutParams(lp);
                }
            }
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) view;
                int count = vg.getChildCount();
                for (int i = 0; i < count; i++) {
                    scaleViewNodeRecursive(vg.getChildAt(i), factor);
                }
            }
        } catch (Throwable ignored) {}
    }
}
