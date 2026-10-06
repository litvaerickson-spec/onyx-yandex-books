package com.onyx.yandexbooks.core.typography;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Менеджер и реестр шрифтов, специально оптимизированных для экранов E-Ink Carta.
 * Предоставляет доступ к эталонным ридер-шрифтам (Literata, Charis SIL, PT Serif, PT Sans)
 * и стандартным системным гарнитурам с кэшированием Typeface без утечек памяти на Android 4.4 KitKat.
 */
public class FontHelper {

    private static final String TAG = "FontHelper";

    public static class FontItem {
        public final String id;
        public final String name;
        public final String subtitle;
        public final String sample;
        public final String assetPath;

        public FontItem(String id, String name, String subtitle, String sample, String assetPath) {
            this.id = id;
            this.name = name;
            this.subtitle = subtitle;
            this.sample = sample;
            this.assetPath = assetPath;
        }
    }

    private static final List<FontItem> AVAILABLE_FONTS = new ArrayList<>();
    private static final Map<String, Typeface> TYPEFACE_CACHE = new HashMap<>();

    static {
        AVAILABLE_FONTS.add(new FontItem("literata", "Literata", "Специальный книжный шрифт от Google для E-Ink", "В чащах юга жил-был цитрус? Да! 12345", "fonts/literata.ttf"));
        AVAILABLE_FONTS.add(new FontItem("charis_sil", "Charis SIL", "Эталонный шрифт ридеров KOReader и AlReader", "В чащах юга жил-был цитрус? Да! 12345", "fonts/charis_sil.ttf"));
        AVAILABLE_FONTS.add(new FontItem("pt_serif", "PT Serif", "Классический русский книжный с засечками (ПараТайп)", "В чащах юга жил-был цитрус? Да! 12345", "fonts/pt_serif.ttf"));
        AVAILABLE_FONTS.add(new FontItem("pt_sans", "PT Sans", "Чистый рубленый шрифт без засечек (ПараТайп)", "В чащах юга жил-был цитрус? Да! 12345", "fonts/pt_sans.ttf"));
        AVAILABLE_FONTS.add(new FontItem("serif", "Системный Serif", "Встроенный системный Android-шрифт с засечками", "В чащах юга жил-был цитрус? Да! 12345", null));
        AVAILABLE_FONTS.add(new FontItem("sans-serif", "Системный Sans", "Стандартный интерфейсный системный шрифт", "В чащах юга жил-был цитрус? Да! 12345", null));
        AVAILABLE_FONTS.add(new FontItem("monospace", "Моноширинный", "Фиксированная ширина символов для кода", "В чащах юга жил-был цитрус? Да! 12345", null));
    }

    public static List<FontItem> getAvailableFonts() {
        return Collections.unmodifiableList(AVAILABLE_FONTS);
    }

    public static String getFontDisplayName(String fontId) {
        if (fontId == null) return "Literata";
        for (FontItem item : AVAILABLE_FONTS) {
            if (item.id.equalsIgnoreCase(fontId)) {
                return item.name;
            }
        }
        return "Literata";
    }

    public static synchronized Typeface getTypeface(Context context, String fontId) {
        if (fontId == null || fontId.trim().isEmpty()) {
            fontId = "literata";
        }
        String key = fontId.toLowerCase().trim();
        if (TYPEFACE_CACHE.containsKey(key)) {
            return TYPEFACE_CACHE.get(key);
        }

        Typeface tf = null;
        if ("serif".equals(key)) {
            tf = Typeface.SERIF;
        } else if ("sans-serif".equals(key)) {
            tf = Typeface.SANS_SERIF;
        } else if ("monospace".equals(key)) {
            tf = Typeface.MONOSPACE;
        } else {
            // Ищем шрифт в assets
            for (FontItem item : AVAILABLE_FONTS) {
                if (item.id.equals(key) && item.assetPath != null && context != null) {
                    try {
                        tf = Typeface.createFromAsset(context.getAssets(), item.assetPath);
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to load typeface from asset: " + item.assetPath, t);
                    }
                    break;
                }
            }
            if (tf == null) {
                tf = Typeface.SERIF;
            }
        }

        TYPEFACE_CACHE.put(key, tf);
        return tf;
    }
}
