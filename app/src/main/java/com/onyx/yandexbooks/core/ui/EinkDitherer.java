package com.onyx.yandexbooks.core.ui;

import android.graphics.Bitmap;

/**
 * Алгоритм дизеринга Флойда — Стейнберга для 16-градационных E-Ink экранов (Carta / Pearl).
 * Устраняет грубые цветовые «ступеньки» (бандинг) на обложках и иллюстрациях без лишних
 * аллокаций памяти на старом Dalvik VM (Android 4.2–4.4).
 */
public final class EinkDitherer {

    private EinkDitherer() {}

    /**
     * Применяет диффузию ошибки Флойда — Стейнберга с квантованием в 16 уровней серого (0..255 с шагом 17).
     * Возвращает новый оптимизированный Bitmap или исходный при ошибке / невалидных параметрах.
     */
    public static Bitmap applyFloydSteinbergDither(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 1 || height <= 1) {
            return source;
        }
        // Защита от OOM для сверхкрупных текстур на Darwin 1 (512 МБ RAM)
        if ((long) width * (long) height > 2000000L) {
            return source;
        }

        try {
            int total = width * height;
            int[] pixels = new int[total];
            int[] luma = new int[total];
            source.getPixels(pixels, 0, width, 0, 0, width, height);

            for (int i = 0; i < total; i++) {
                int c = pixels[i];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                luma[i] = (77 * r + 150 * g + 29 * b) >> 8;
            }

            for (int y = 0; y < height; y++) {
                int rowOffset = y * width;
                for (int x = 0; x < width; x++) {
                    int idx = rowOffset + x;
                    int oldVal = luma[idx];
                    if (oldVal < 0) oldVal = 0;
                    else if (oldVal > 255) oldVal = 255;

                    int level = (oldVal + 8) / 17;
                    if (level < 0) level = 0;
                    else if (level > 15) level = 15;
                    int newVal = level * 17;

                    int alpha = pixels[idx] & 0xFF000000;
                    pixels[idx] = alpha | (newVal << 16) | (newVal << 8) | newVal;

                    int err = oldVal - newVal;
                    if (err == 0) continue;

                    if (x + 1 < width) {
                        luma[idx + 1] += (err * 7) >> 4;
                    }
                    if (y + 1 < height) {
                        int nextRow = idx + width;
                        if (x > 0) {
                            luma[nextRow - 1] += (err * 3) >> 4;
                        }
                        luma[nextRow] += (err * 5) >> 4;
                        if (x + 1 < width) {
                            luma[nextRow + 1] += err >> 4;
                        }
                    }
                }
            }

            Bitmap.Config outConfig = source.hasAlpha() ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            return Bitmap.createBitmap(pixels, width, height, outConfig);
        } catch (Throwable ignored) {
            return source;
        }
    }
}
