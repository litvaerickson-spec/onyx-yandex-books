package com.onyx.yandexbooks.core.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.Log;

/**
 * Помощник динамического масштабирования интерфейса и шрифтов приложения (UI & Font Scale).
 * Позволяет адаптировать размеры кнопок, вкладок, списков и текстов для экранов Onyx Boox Darwin.
 */
public class UiScaleHelper {

    private static final String TAG = "UiScaleHelper";

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
     * Ограничение масштаба безопасным диапазоном (от 85% до 180%).
     */
    public static float sanitizeScale(float scaleFactor) {
        if (scaleFactor < 0.85f) return 1.0f;
        if (scaleFactor > 1.80f) return 1.80f;
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
                cfg.fontScale = sBaseFontScale * effective;
                res.updateConfiguration(cfg, dm);
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
}
