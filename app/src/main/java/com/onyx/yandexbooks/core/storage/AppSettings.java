package com.onyx.yandexbooks.core.storage;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Пользовательские настройки приложения (выбор ридера по умолчанию, режим полей).
 */
public class AppSettings {

    private static final String PREF_NAME = "onyx_reader_settings";

    public static final String KEY_READER_MODE = "reader_mode";
    public static final String READER_MODE_ONYX = "onyx";
    public static final String READER_MODE_LITE = "lite";
    public static final String READER_MODE_NEOREADER = "neoreader";
    public static final String READER_MODE_ALREADER = "alreader";
    public static final String READER_MODE_CHOOSER = "chooser";

    public static final String KEY_TAP_ZONE_MODE = "tap_zone_mode";
    public static final String TAP_ZONE_STANDARD = "standard";
    public static final String TAP_ZONE_LEFT_HANDED = "left_handed";
    public static final String TAP_ZONE_TOP_BACK_BOTTOM_FWD = "top_back_bottom_fwd";
    public static final String TAP_ZONE_CUSTOM = "custom_3x3";

    public static final String KEY_CUSTOM_TAP_ZONES = "custom_tap_zones";
    public static final String TAP_ACTION_PREV = "prev";
    public static final String TAP_ACTION_NEXT = "next";
    public static final String TAP_ACTION_MENU = "menu";
    public static final String TAP_ACTION_TOC = "toc";
    public static final String TAP_ACTION_BOOKMARK = "bookmark";
    public static final String TAP_ACTION_REFRESH = "refresh";
    public static final String TAP_ACTION_NONE = "none";
    public static final String DEFAULT_CUSTOM_TAP_ZONES =
            "prev,menu,next,prev,menu,next,prev,menu,next";

    public static final String KEY_FULLSCREEN_READER = "fullscreen_reader";

    public static final String KEY_MARGIN_MODE = "margin_mode";
    public static final String MARGIN_EXTRA_NARROW = "extra_narrow"; // 8px
    public static final String MARGIN_NARROW = "narrow";             // 18px
    public static final String MARGIN_MEDIUM = "medium";             // 32px
    public static final String MARGIN_WIDE = "wide";                 // 48px

    public static final String KEY_ORIENTATION = "reader_orientation";
    public static final String ORIENTATION_PORTRAIT = "portrait";
    public static final String ORIENTATION_LANDSCAPE = "landscape";
    public static final String ORIENTATION_REVERSE_LANDSCAPE = "reverse_landscape";

    public static final String KEY_UI_SCALE = "app_ui_scale";
    public static final float UI_SCALE_DEFAULT = 1.0f;      // 100%
    public static final float UI_SCALE_MEDIUM = 1.15f;      // 115%
    public static final float UI_SCALE_LARGE = 1.30f;       // 130%
    public static final float UI_SCALE_EXTRA_LARGE = 1.45f; // 145%
    public static final float UI_SCALE_MAX = 1.60f;         // 160%
    public static final float UI_SCALE_180 = 1.80f;         // 180% (Планшеты 10.3")
    public static final float UI_SCALE_200 = 2.00f;         // 200% (Максимум для 10.3" 300 PPI)

    public static final String KEY_PANEL_SIZE_MODE = "panel_size_mode";
    public static final String PANEL_SIZE_COMPACT = "compact";
    public static final String PANEL_SIZE_COMFORTABLE = "comfortable";
    public static final String PANEL_SIZE_LARGE = "large";
    public static final String PANEL_SIZE_XLARGE = "xlarge";

    public static final String KEY_CARD_DENSITY_MODE = "card_density_mode";
    public static final String CARD_DENSITY_COMPACT = "compact";
    public static final String CARD_DENSITY_NORMAL = "normal";
    public static final String CARD_DENSITY_LARGE = "large";

    public static final String KEY_FOOTER_OFFSET_DP = "reader_footer_offset_dp";
    public static final int DEFAULT_FOOTER_OFFSET_DP = 16;
    public static final int MIN_FOOTER_OFFSET_DP = 6;
    public static final int MAX_FOOTER_OFFSET_DP = 48;

    private static final String KEY_QUOTES_PURGED_V201 = "quotes_purged_v201";
    private static final String KEY_DITHER_DEFAULT_V203_MIGRATED = "dither_default_v203_migrated";

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_DITHER_DEFAULT_V203_MIGRATED)) {
            prefs.edit()
                    .putBoolean(KEY_DITHER_COVERS, false)
                    .putBoolean(KEY_DITHER_DEFAULT_V203_MIGRATED, true)
                    .apply();
        }
    }

    /**
     * Режим ридера по умолчанию: "onyx" (системный Onyx NeoReader/AlReader) или "lite" (встроенный).
     * По умолчанию включена системная Onyx, так как она максимально адаптирована к Darwin.
     */
    public String getReaderMode() {
        return prefs.getString(KEY_READER_MODE, READER_MODE_ONYX);
    }

    public void setReaderMode(String mode) {
        prefs.edit().putString(KEY_READER_MODE, mode).apply();
    }

    public boolean isOnyxReaderPreferred() {
        String mode = getReaderMode();
        return !READER_MODE_LITE.equalsIgnoreCase(mode);
    }

    public String getTapZoneMode() {
        return prefs.getString(KEY_TAP_ZONE_MODE, TAP_ZONE_STANDARD);
    }

    public void setTapZoneMode(String mode) {
        prefs.edit().putString(KEY_TAP_ZONE_MODE, mode != null ? mode : TAP_ZONE_STANDARD).apply();
    }

    public String getCustomTapZonesRaw() {
        return prefs.getString(KEY_CUSTOM_TAP_ZONES, DEFAULT_CUSTOM_TAP_ZONES);
    }

    public void setCustomTapZonesRaw(String raw) {
        prefs.edit().putString(KEY_CUSTOM_TAP_ZONES, (raw != null && !raw.trim().isEmpty()) ? raw : DEFAULT_CUSTOM_TAP_ZONES).apply();
    }

    public String[] getCustomTapZones() {
        return parseCustomTapZones(getCustomTapZonesRaw());
    }

    public void setCustomTapZones(String[] zones) {
        if (zones == null || zones.length < 9) {
            setCustomTapZonesRaw(DEFAULT_CUSTOM_TAP_ZONES);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            if (i > 0) sb.append(",");
            String a = zones[i];
            sb.append((a != null && !a.trim().isEmpty()) ? a.trim() : TAP_ACTION_NONE);
        }
        setCustomTapZonesRaw(sb.toString());
    }

    public static String[] parseCustomTapZones(String raw) {
        String[] result = new String[]{
                TAP_ACTION_PREV, TAP_ACTION_MENU, TAP_ACTION_NEXT,
                TAP_ACTION_PREV, TAP_ACTION_MENU, TAP_ACTION_NEXT,
                TAP_ACTION_PREV, TAP_ACTION_MENU, TAP_ACTION_NEXT
        };
        if (raw == null || raw.trim().isEmpty()) return result;
        String[] parts = raw.split(",");
        for (int i = 0; i < Math.min(9, parts.length); i++) {
            String p = parts[i] != null ? parts[i].trim() : "";
            if (!p.isEmpty()) result[i] = p;
        }
        return result;
    }

    public boolean isFullscreenReader() {
        return prefs.getBoolean(KEY_FULLSCREEN_READER, false);
    }

    public void setFullscreenReader(boolean fullscreen) {
        prefs.edit().putBoolean(KEY_FULLSCREEN_READER, fullscreen).apply();
    }

    public String getMarginMode() {
        return prefs.getString(KEY_MARGIN_MODE, MARGIN_NARROW);
    }

    public void setMarginMode(String mode) {
        prefs.edit().putString(KEY_MARGIN_MODE, mode).apply();
    }

    public int getMarginPaddingPx() {
        String mode = getMarginMode();
        if (MARGIN_EXTRA_NARROW.equalsIgnoreCase(mode)) {
            return 8;
        } else if (MARGIN_MEDIUM.equalsIgnoreCase(mode)) {
            return 32;
        } else if (MARGIN_WIDE.equalsIgnoreCase(mode)) {
            return 48;
        } else {
            return 18; // Узкие по умолчанию
        }
    }

    public String getReaderOrientation() {
        return prefs.getString(KEY_ORIENTATION, ORIENTATION_PORTRAIT);
    }

    public void setReaderOrientation(String orientation) {
        prefs.edit().putString(KEY_ORIENTATION, orientation).apply();
    }

    public static final String KEY_FONT_SIZE = "font_size_sp";
    public static final String KEY_FONT_FAMILY = "font_family";
    public static final String KEY_LINE_SPACING = "line_spacing_mult";
    public static final String KEY_PARAGRAPH_INDENT = "paragraph_indent_px";
    public static final String KEY_HYPHENATION = "hyphenation_enabled";
    public static final String KEY_BOLD_TEXT = "bold_text_enabled";
    public static final String KEY_CONTRAST_MODE = "contrast_mode";
    public static final String KEY_VERTICAL_MARGIN = "vertical_margin_mode";
    public static final String KEY_NIGHT_MODE = "night_mode_enabled";
    public static final String KEY_DITHER_COVERS = "dither_covers_enabled";
    public static final String KEY_EDGE_BRIGHTNESS_SWIPE = "edge_brightness_swipe";
    public static final String KEY_AUDIO_SYNC_ENABLED = "audio_sync_enabled";
    public static final String KEY_USE_EPUB_FONTS = "use_epub_fonts";
    public static final String KEY_TEXT_ALIGN_MODE = "text_align_mode";
    public static final String TEXT_ALIGN_JUSTIFY = "justify";
    public static final String TEXT_ALIGN_LEFT = "left";
    public static final String TEXT_ALIGN_BOOK = "book";

    public int getFontSizeSp() { return prefs.getInt(KEY_FONT_SIZE, 18); }
    public void setFontSizeSp(int size) { prefs.edit().putInt(KEY_FONT_SIZE, size).apply(); }

    public String getFontFamily() { return prefs.getString(KEY_FONT_FAMILY, "literata"); }
    public void setFontFamily(String family) { prefs.edit().putString(KEY_FONT_FAMILY, family).apply(); }

    public float getLineSpacingMultiplier() { return prefs.getFloat(KEY_LINE_SPACING, 1.25f); }
    public void setLineSpacingMultiplier(float mult) { prefs.edit().putFloat(KEY_LINE_SPACING, mult).apply(); }

    public int getParagraphIndentPx() { return prefs.getInt(KEY_PARAGRAPH_INDENT, 20); }
    public void setParagraphIndentPx(int px) { prefs.edit().putInt(KEY_PARAGRAPH_INDENT, px).apply(); }

    public boolean isHyphenationEnabled() { return prefs.getBoolean(KEY_HYPHENATION, true); }
    public void setHyphenationEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_HYPHENATION, enabled).apply(); }

    public boolean isBoldText() { return prefs.getBoolean(KEY_BOLD_TEXT, false); }
    public void setBoldText(boolean bold) { prefs.edit().putBoolean(KEY_BOLD_TEXT, bold).apply(); }

    public String getContrastMode() { return prefs.getString(KEY_CONTRAST_MODE, "normal"); }
    public void setContrastMode(String mode) { prefs.edit().putString(KEY_CONTRAST_MODE, mode).apply(); }

    public String getVerticalMarginMode() { return prefs.getString(KEY_VERTICAL_MARGIN, "normal"); }
    public void setVerticalMarginMode(String mode) { prefs.edit().putString(KEY_VERTICAL_MARGIN, mode).apply(); }

    public boolean isNightMode() { return prefs.getBoolean(KEY_NIGHT_MODE, false); }
    public void setNightMode(boolean enabled) { prefs.edit().putBoolean(KEY_NIGHT_MODE, enabled).apply(); }

    public boolean isDitherCoversEnabled() { return prefs.getBoolean(KEY_DITHER_COVERS, false); }
    public void setDitherCoversEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_DITHER_COVERS, enabled).apply(); }

    public boolean isEdgeBrightnessSwipeEnabled() { return prefs.getBoolean(KEY_EDGE_BRIGHTNESS_SWIPE, true); }
    public void setEdgeBrightnessSwipeEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_EDGE_BRIGHTNESS_SWIPE, enabled).apply(); }

    public boolean isAudioSyncEnabled() { return prefs.getBoolean(KEY_AUDIO_SYNC_ENABLED, true); }
    public void setAudioSyncEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_AUDIO_SYNC_ENABLED, enabled).apply(); }

    public boolean isUseEpubFonts() { return prefs.getBoolean(KEY_USE_EPUB_FONTS, false); }
    public void setUseEpubFonts(boolean enabled) { prefs.edit().putBoolean(KEY_USE_EPUB_FONTS, enabled).apply(); }

    public String getTextAlignMode() {
        String val = prefs.getString(KEY_TEXT_ALIGN_MODE, TEXT_ALIGN_JUSTIFY);
        if (TEXT_ALIGN_LEFT.equalsIgnoreCase(val)) return TEXT_ALIGN_LEFT;
        if (TEXT_ALIGN_BOOK.equalsIgnoreCase(val)) return TEXT_ALIGN_BOOK;
        return TEXT_ALIGN_JUSTIFY;
    }

    public void setTextAlignMode(String mode) {
        String safe = TEXT_ALIGN_JUSTIFY;
        if (TEXT_ALIGN_LEFT.equalsIgnoreCase(mode)) safe = TEXT_ALIGN_LEFT;
        else if (TEXT_ALIGN_BOOK.equalsIgnoreCase(mode)) safe = TEXT_ALIGN_BOOK;
        prefs.edit().putString(KEY_TEXT_ALIGN_MODE, safe).apply();
    }

    public int getEdgeFrontlightCold() { return clampBrightness(prefs.getInt("edge_frontlight_cold", 50)); }
    public void setEdgeFrontlightCold(int level) { prefs.edit().putInt("edge_frontlight_cold", clampBrightness(level)).apply(); }

    public int getEdgeFrontlightWarm() { return clampBrightness(prefs.getInt("edge_frontlight_warm", 50)); }
    public void setEdgeFrontlightWarm(int level) { prefs.edit().putInt("edge_frontlight_warm", clampBrightness(level)).apply(); }

    public static int clampBrightness(int level) {
        if (level < 0) return 0;
        if (level > 100) return 100;
        return level;
    }

    public float getUiScale() {
        return prefs.getFloat(KEY_UI_SCALE, UI_SCALE_DEFAULT);
    }

    public void setUiScale(float scale) {
        prefs.edit().putFloat(KEY_UI_SCALE, scale).apply();
    }

    public String getPanelSizeMode() {
        return prefs.getString(KEY_PANEL_SIZE_MODE, PANEL_SIZE_COMFORTABLE);
    }

    public void setPanelSizeMode(String mode) {
        String safe = (mode != null) ? mode : PANEL_SIZE_COMFORTABLE;
        prefs.edit().putString(KEY_PANEL_SIZE_MODE, safe).apply();
    }

    public String getCardDensityMode() {
        return prefs.getString(KEY_CARD_DENSITY_MODE, CARD_DENSITY_NORMAL);
    }

    public void setCardDensityMode(String mode) {
        String safe = (mode != null) ? mode : CARD_DENSITY_NORMAL;
        prefs.edit().putString(KEY_CARD_DENSITY_MODE, safe).apply();
    }

    public int getReaderFooterOffsetDp() {
        return clampFooterOffsetDp(prefs.getInt(KEY_FOOTER_OFFSET_DP, DEFAULT_FOOTER_OFFSET_DP));
    }

    public void setReaderFooterOffsetDp(int offsetDp) {
        prefs.edit().putInt(KEY_FOOTER_OFFSET_DP, clampFooterOffsetDp(offsetDp)).apply();
    }

    public static int clampFooterOffsetDp(int offsetDp) {
        if (offsetDp < MIN_FOOTER_OFFSET_DP) return MIN_FOOTER_OFFSET_DP;
        if (offsetDp > MAX_FOOTER_OFFSET_DP) return MAX_FOOTER_OFFSET_DP;
        return offsetDp;
    }

    public int getInt(String key, int defValue) { return prefs.getInt(key, defValue); }
    public void putInt(String key, int value) { prefs.edit().putInt(key, value).apply(); }

    public boolean isQuotesPurgedV201() {
        return prefs.getBoolean(KEY_QUOTES_PURGED_V201, false);
    }

    public void setQuotesPurgedV201(boolean purged) {
        prefs.edit().putBoolean(KEY_QUOTES_PURGED_V201, purged).apply();
    }
}
