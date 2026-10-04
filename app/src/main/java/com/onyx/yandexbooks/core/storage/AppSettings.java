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
}
