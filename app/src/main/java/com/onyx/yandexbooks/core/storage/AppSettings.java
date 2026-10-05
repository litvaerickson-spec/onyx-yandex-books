package com.onyx.yandexbooks.core.storage;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Пользовательские настройки приложения (выбор читалки по умолчанию, режим полей).
 */
public class AppSettings {

    private static final String PREF_NAME = "onyx_reader_settings";

    public static final String KEY_READER_MODE = "reader_mode";
    public static final String READER_MODE_ONYX = "onyx";
    public static final String READER_MODE_LITE = "lite";

    public static final String KEY_MARGIN_MODE = "margin_mode";
    public static final String MARGIN_NARROW = "narrow";   // 18px
    public static final String MARGIN_MEDIUM = "medium";   // 28px
    public static final String MARGIN_WIDE = "wide";       // 42px

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Режим читалки по умолчанию: "onyx" (системная Onyx NeoReader/AlReader) или "lite" (встроенная).
     * По умолчанию включена системная Onyx, так как она максимально адаптирована к Darwin.
     */
    public String getReaderMode() {
        return prefs.getString(KEY_READER_MODE, READER_MODE_ONYX);
    }

    public void setReaderMode(String mode) {
        prefs.edit().putString(KEY_READER_MODE, mode).apply();
    }

    public boolean isOnyxReaderPreferred() {
        return READER_MODE_ONYX.equalsIgnoreCase(getReaderMode());
    }

    public String getMarginMode() {
        return prefs.getString(KEY_MARGIN_MODE, MARGIN_NARROW);
    }

    public void setMarginMode(String mode) {
        prefs.edit().putString(KEY_MARGIN_MODE, mode).apply();
    }

    public int getMarginPaddingPx() {
        String mode = getMarginMode();
        if (MARGIN_MEDIUM.equalsIgnoreCase(mode)) {
            return 28;
        } else if (MARGIN_WIDE.equalsIgnoreCase(mode)) {
            return 42;
        } else {
            return 18; // Узкие по умолчанию
        }
    }

    public static final String KEY_FONT_SIZE = "font_size_sp";
    public static final String KEY_FONT_FAMILY = "font_family";
    public static final String KEY_LINE_SPACING = "line_spacing_mult";
    public static final String KEY_PARAGRAPH_INDENT = "paragraph_indent_px";
    public static final String KEY_HYPHENATION = "hyphenation_enabled";
    public static final String KEY_BOLD_TEXT = "bold_text_enabled";
    public static final String KEY_CONTRAST_MODE = "contrast_mode";
    public static final String KEY_VERTICAL_MARGIN = "vertical_margin_mode";

    public int getFontSizeSp() { return prefs.getInt(KEY_FONT_SIZE, 18); }
    public void setFontSizeSp(int size) { prefs.edit().putInt(KEY_FONT_SIZE, size).apply(); }

    public String getFontFamily() { return prefs.getString(KEY_FONT_FAMILY, "serif"); }
    public void setFontFamily(String family) { prefs.edit().putString(KEY_FONT_FAMILY, family).apply(); }

    public float getLineSpacingMultiplier() { return prefs.getFloat(KEY_LINE_SPACING, 1.25f); }
    public void setLineSpacingMultiplier(float mult) { prefs.edit().putFloat(KEY_LINE_SPACING, mult).apply(); }

    public int getParagraphIndentPx() { return prefs.getInt(KEY_PARAGRAPH_INDENT, 20); }
    public void setParagraphIndentPx(int px) { prefs.edit().putInt(KEY_PARAGRAPH_INDENT, px).apply(); }

    public boolean isHyphenationEnabled() { return prefs.getBoolean(KEY_HYPHENATION, true); }
    public void setHyphenationEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_HYPHENATION, enabled).apply(); }

    public boolean isBoldText() { return prefs.getBoolean(KEY_BOLD_TEXT, true); }
    public void setBoldText(boolean bold) { prefs.edit().putBoolean(KEY_BOLD_TEXT, bold).apply(); }

    public String getContrastMode() { return prefs.getString(KEY_CONTRAST_MODE, "high"); }
    public void setContrastMode(String mode) { prefs.edit().putString(KEY_CONTRAST_MODE, mode).apply(); }

    public String getVerticalMarginMode() { return prefs.getString(KEY_VERTICAL_MARGIN, "normal"); }
    public void setVerticalMarginMode(String mode) { prefs.edit().putString(KEY_VERTICAL_MARGIN, mode).apply(); }

    public int getInt(String key, int defValue) { return prefs.getInt(key, defValue); }
    public void putInt(String key, int value) { prefs.edit().putInt(key, value).apply(); }
}
