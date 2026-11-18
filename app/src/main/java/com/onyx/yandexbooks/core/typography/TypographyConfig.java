package com.onyx.yandexbooks.core.typography;

import java.io.Serializable;

/**
 * Конфигурация параметров верстки и типографики в стиле AlReader/Onyx NeoReader.
 * Настроена специально под дисплеи E-Ink Carta с комфортными физическими отступами
 * и глубоким черным контрастом.
 */
public class TypographyConfig implements Serializable {

    private int fontSizeSp = 18;
    private float lineSpacingMultiplier = 1.25f;
    private int paragraphIndentPx = 20;

    // Оптимальные компактные отступы для E-Ink Carta экрана Onyx Boox Darwin (758x1024)
    private int paddingLeftPx = 18;
    private int paddingRightPx = 18;
    private int paddingTopPx = 12;
    private int paddingBottomPx = 16;

    // Отступ нижнего колонтитула (часы, заряд батареи, страница) от физической рамки экрана в dp
    private int footerBottomOffsetDp = 16;

    // Резерв высоты под верхний колонтитул (название текущей главы)
    private int headerReservedHeightPx = 26;

    // Резерв высоты под нижний колонтитул (номер страницы, глава, глобальный процент),
    // исключающий физическое наложение текста книги на строку статуса
    private int footerReservedHeightPx = 26;

    private boolean isHyphenationEnabled = true;
    private boolean isJustifyEnabled = true;
    private String textAlignMode = "justify"; // justify, left, book
    private boolean isBoldText = false; // Натуральная четкая отрисовка без искусственного утолщения
    private String fontFamily = "literata"; // literata, charis_sil, pt_serif, pt_sans, serif, sans-serif, monospace
    private String contrastMode = "normal"; // normal, high
    private String verticalMarginMode = "normal"; // small, normal, large
    private String customFontPath = null;
    private boolean isNightMode = false;
    private int epdFullRefreshInterval = 8; // Полный сброс артефактов E-Ink каждые 8 страниц
    private String tapZoneMode = "standard"; // standard, left_handed, top_back_bottom_fwd, custom_3x3
    private String[] customTapZones = new String[]{
            "prev", "menu", "next",
            "prev", "menu", "next",
            "prev", "menu", "next"
    };
    private boolean useEpubFonts = false; // Использовать встроенные в EPUB шрифты (гибрид v1.7.0)
    private float densityScale = 1.0f; // Масштаб экрана для согласованного красного абзаца на 300 DPI

    public TypographyConfig() {}

    public int getFontSizeSp() { return fontSizeSp; }
    public void setFontSizeSp(int fontSizeSp) { this.fontSizeSp = Math.max(12, Math.min(fontSizeSp, 48)); }

    public float getLineSpacingMultiplier() { return lineSpacingMultiplier; }
    public void setLineSpacingMultiplier(float multiplier) { this.lineSpacingMultiplier = Math.max(0.9f, Math.min(multiplier, 2.0f)); }

    public int getParagraphIndentPx() { return paragraphIndentPx; }
    public void setParagraphIndentPx(int indentPx) { this.paragraphIndentPx = indentPx; }

    public int getPaddingLeftPx() { return paddingLeftPx; }
    public void setPaddingLeftPx(int paddingLeftPx) { this.paddingLeftPx = paddingLeftPx; }

    public int getPaddingRightPx() { return paddingRightPx; }
    public void setPaddingRightPx(int paddingRightPx) { this.paddingRightPx = paddingRightPx; }

    public int getPaddingTopPx() { return paddingTopPx; }
    public void setPaddingTopPx(int paddingTopPx) { this.paddingTopPx = paddingTopPx; }

    public int getPaddingBottomPx() { return paddingBottomPx; }
    public void setPaddingBottomPx(int paddingBottomPx) { this.paddingBottomPx = paddingBottomPx; }

    public int getHeaderReservedHeightPx() { return headerReservedHeightPx; }
    public void setHeaderReservedHeightPx(int headerReservedHeightPx) { this.headerReservedHeightPx = headerReservedHeightPx; }

    public int getFooterReservedHeightPx() { return footerReservedHeightPx; }
    public void setFooterReservedHeightPx(int footerReservedHeightPx) { this.footerReservedHeightPx = footerReservedHeightPx; }

    public boolean isHyphenationEnabled() { return isHyphenationEnabled; }
    public void setHyphenationEnabled(boolean hyphenationEnabled) { isHyphenationEnabled = hyphenationEnabled; }

    public boolean isJustifyEnabled() { return isJustifyEnabled && !"left".equalsIgnoreCase(textAlignMode); }
    public void setJustifyEnabled(boolean justifyEnabled) {
        isJustifyEnabled = justifyEnabled;
        if (!justifyEnabled) {
            this.textAlignMode = "left";
        } else if ("left".equalsIgnoreCase(this.textAlignMode)) {
            this.textAlignMode = "justify";
        }
    }

    public String getTextAlignMode() { return textAlignMode != null ? textAlignMode : "justify"; }
    public void setTextAlignMode(String mode) {
        if ("left".equalsIgnoreCase(mode)) {
            this.textAlignMode = "left";
            this.isJustifyEnabled = false;
        } else if ("book".equalsIgnoreCase(mode)) {
            this.textAlignMode = "book";
            this.isJustifyEnabled = true;
        } else {
            this.textAlignMode = "justify";
            this.isJustifyEnabled = true;
        }
    }

    public boolean isBoldText() { return isBoldText; }
    public void setBoldText(boolean boldText) { isBoldText = boldText; }

    public String getFontFamily() { return fontFamily != null ? fontFamily : "literata"; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }

    public String getContrastMode() { return contrastMode != null ? contrastMode : "normal"; }
    public void setContrastMode(String contrastMode) { this.contrastMode = contrastMode; }

    public String getVerticalMarginMode() { return verticalMarginMode != null ? verticalMarginMode : "normal"; }
    public void setVerticalMarginMode(String mode) {
        this.verticalMarginMode = mode;
        if ("small".equalsIgnoreCase(mode)) {
            setPaddingTopPx(8);
            setPaddingBottomPx(4);
        } else if ("large".equalsIgnoreCase(mode)) {
            setPaddingTopPx(18);
            setPaddingBottomPx(12);
        } else {
            setPaddingTopPx(12);
            setPaddingBottomPx(6);
        }
    }

    public String getCustomFontPath() { return customFontPath; }
    public void setCustomFontPath(String customFontPath) { this.customFontPath = customFontPath; }

    public boolean isNightMode() { return isNightMode; }
    public void setNightMode(boolean nightMode) { this.isNightMode = nightMode; }

    public int getEpdFullRefreshInterval() { return epdFullRefreshInterval; }
    public void setEpdFullRefreshInterval(int epdFullRefreshInterval) { this.epdFullRefreshInterval = epdFullRefreshInterval; }

    public int getFooterBottomOffsetDp() { return footerBottomOffsetDp; }
    public void setFooterBottomOffsetDp(int dp) {
        if (dp < 6) dp = 6;
        if (dp > 48) dp = 48;
        this.footerBottomOffsetDp = dp;
    }

    public String getTapZoneMode() { return tapZoneMode != null ? tapZoneMode : "standard"; }
    public void setTapZoneMode(String tapZoneMode) { this.tapZoneMode = tapZoneMode != null ? tapZoneMode : "standard"; }

    public String[] getCustomTapZones() { return customTapZones; }
    public void setCustomTapZones(String[] zones) {
        if (zones != null && zones.length >= 9) {
            String[] copy = new String[9];
            for (int i = 0; i < 9; i++) {
                copy[i] = (zones[i] != null && !zones[i].isEmpty()) ? zones[i] : "none";
            }
            this.customTapZones = copy;
        }
    }

    public String getCustomTapActionAt(int row, int col) {
        int r = Math.max(0, Math.min(2, row));
        int c = Math.max(0, Math.min(2, col));
        int idx = r * 3 + c;
        if (customTapZones != null && idx < customTapZones.length && customTapZones[idx] != null) {
            return customTapZones[idx];
        }
        return "none";
    }

    public boolean isUseEpubFonts() { return useEpubFonts; }
    public void setUseEpubFonts(boolean useEpubFonts) { this.useEpubFonts = useEpubFonts; }

    public float getDensityScale() { return densityScale; }
    public void setDensityScale(float densityScale) { this.densityScale = Math.max(0.75f, Math.min(densityScale, 4.0f)); }

    /**
     * Эффективный красной строки отступ в физических пикселях с учётом DPI экрана,
     * чтобы 20-32 sp выглядели чётко на экранах 300 DPI (Darwin 6/7) и оставались предсказуемыми на 160 DPI.
     */
    public int getEffectiveParagraphIndentPx() {
        if (paragraphIndentPx <= 0) return 0;
        float mult = Math.max(1.0f, densityScale * 0.85f);
        return Math.round(paragraphIndentPx * mult);
    }
}
