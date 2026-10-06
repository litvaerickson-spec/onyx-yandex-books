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
    private int paddingBottomPx = 6;

    // Резерв высоты под верхний колонтитул (название текущей главы)
    private int headerReservedHeightPx = 26;

    // Резерв высоты под нижний колонтитул (номер страницы, глава, глобальный процент),
    // исключающий физическое наложение текста книги на строку статуса
    private int footerReservedHeightPx = 20;

    private boolean isHyphenationEnabled = true;
    private boolean isJustifyEnabled = true;
    private boolean isBoldText = true; // Высокий контраст для E-Ink Carta
    private String fontFamily = "serif"; // serif, sans-serif, monospace
    private String contrastMode = "high"; // high, normal
    private String verticalMarginMode = "normal"; // small, normal, large
    private String customFontPath = null;
    private boolean isNightMode = false;
    private int epdFullRefreshInterval = 8; // Полный сброс артефактов E-Ink каждые 8 страниц

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

    public boolean isJustifyEnabled() { return isJustifyEnabled; }
    public void setJustifyEnabled(boolean justifyEnabled) { isJustifyEnabled = justifyEnabled; }

    public boolean isBoldText() { return isBoldText; }
    public void setBoldText(boolean boldText) { isBoldText = boldText; }

    public String getFontFamily() { return fontFamily != null ? fontFamily : "serif"; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }

    public String getContrastMode() { return contrastMode != null ? contrastMode : "high"; }
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
}
