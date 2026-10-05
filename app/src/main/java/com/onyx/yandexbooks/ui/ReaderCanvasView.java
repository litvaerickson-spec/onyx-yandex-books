package com.onyx.yandexbooks.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.onyx.yandexbooks.core.typography.TextPaginator;
import com.onyx.yandexbooks.core.typography.TypographyConfig;

import java.io.File;

/**
 * Высокопроизводительный нативный компонент отрисовки книги на E-Ink дисплее.
 * Отрисовывает страницы напрямую на Canvas в software-режиме для глубокого черного контраста.
 */
public class ReaderCanvasView extends View {

    public interface OnReaderInteractionListener {
        void onPageForward();
        void onPageBackward();
        void onCenterTap();
    }

    private Paint textPaint;
    private Paint footerPaint;
    private Paint headerPaint;
    private TypographyConfig config;
    private TextPaginator.Page currentPage;
    private int globalPageIndex = 1;
    private int totalBookPages = 1;
    private int totalPages = 1;
    private String chapterTitle = "";
    private int currentChapterIndex = 0;
    private int totalChapters = 1;
    private double globalPercent = 0.0;
    private OnReaderInteractionListener interactionListener;

    public ReaderCanvasView(Context context) {
        super(context);
        init();
    }

    public ReaderCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Отключаем аппаратное ускорение для избежания размытия субпиксельного сглаживания на E-Ink
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        config = new TypographyConfig();

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(spToPx(config.getFontSizeSp()));
        textPaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        textPaint.setSubpixelText(false);
        textPaint.setDither(false);
        textPaint.setFakeBoldText(config.isBoldText());

        headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerPaint.setColor(Color.BLACK);
        headerPaint.setTextSize(spToPx(11));
        headerPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        headerPaint.setSubpixelText(false);

        footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerPaint.setColor(Color.BLACK); // Насыщенный черный цвет для E-Ink
        footerPaint.setTextSize(spToPx(11));
        footerPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        footerPaint.setSubpixelText(false);
    }

    public Paint getTextPaint() {
        return textPaint;
    }

    public void setInteractionListener(OnReaderInteractionListener listener) {
        this.interactionListener = listener;
    }

    public void setTypographyConfig(TypographyConfig config) {
        this.config = config;
        textPaint.setTextSize(spToPx(config.getFontSizeSp()));
        textPaint.setFakeBoldText(config.isBoldText());

        Typeface baseTf = Typeface.SERIF;
        if ("sans-serif".equalsIgnoreCase(config.getFontFamily())) {
            baseTf = Typeface.SANS_SERIF;
        } else if ("monospace".equalsIgnoreCase(config.getFontFamily())) {
            baseTf = Typeface.MONOSPACE;
        }
        textPaint.setTypeface(baseTf);

        if ("high".equalsIgnoreCase(config.getContrastMode())) {
            textPaint.setStrokeWidth(0.5f);
            textPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        } else {
            textPaint.setStyle(Paint.Style.FILL);
        }

        if (config.getCustomFontPath() != null) {
            File fontFile = new File(config.getCustomFontPath());
            if (fontFile.exists()) {
                try {
                    Typeface tf = Typeface.createFromFile(fontFile);
                    textPaint.setTypeface(tf);
                } catch (Exception ignored) {}
            }
        }
        invalidate();
    }

    public void setPage(TextPaginator.Page page, int totalPages, String chapterTitle) {
        setPage(page, (page != null ? page.pageIndex + 1 : 1), Math.max(1, totalPages), chapterTitle, 0, 1, 0.0);
    }

    public void setPage(TextPaginator.Page page, int totalPages, String chapterTitle, int currentChapterIndex, int totalChapters, double globalPercent) {
        setPage(page, (page != null ? page.pageIndex + 1 : 1), Math.max(1, totalPages), chapterTitle, currentChapterIndex, totalChapters, globalPercent);
    }

    public void setPage(TextPaginator.Page page, int globalPageIndex, int totalBookPages, String chapterTitle, int currentChapterIndex, int totalChapters, double globalPercent) {
        this.currentPage = page;
        this.globalPageIndex = Math.max(1, globalPageIndex);
        this.totalBookPages = Math.max(1, totalBookPages);
        this.totalPages = Math.max(1, totalBookPages);
        this.chapterTitle = chapterTitle != null ? chapterTitle : "";
        this.currentChapterIndex = currentChapterIndex;
        this.totalChapters = Math.max(1, totalChapters);
        this.globalPercent = Math.max(0.0, Math.min(100.0, globalPercent));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Чисто белый фон для E-Ink Carta
        canvas.drawColor(Color.WHITE);

        if (currentPage == null || currentPage.lines.isEmpty()) {
            return;
        }

        // 1. Верхний колонтитул: Название текущей главы
        float headerY = config.getPaddingTopPx() + 16;
        if (chapterTitle != null && !chapterTitle.trim().isEmpty()) {
            String titleText = chapterTitle.trim();
            float maxHeaderWidth = getWidth() - config.getPaddingLeftPx() - config.getPaddingRightPx();
            while (titleText.length() > 3 && headerPaint.measureText(titleText + "…") > maxHeaderWidth) {
                titleText = titleText.substring(0, titleText.length() - 1);
            }
            if (titleText.length() < chapterTitle.trim().length()) {
                titleText += "…";
            }
            canvas.drawText(titleText, config.getPaddingLeftPx(), headerY, headerPaint);
        }

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float fontHeight = fm.bottom - fm.top;
        float lineHeight = fontHeight * config.getLineSpacingMultiplier();

        float startX = config.getPaddingLeftPx();
        float currentY = config.getPaddingTopPx() + config.getHeaderReservedHeightPx() - fm.top;
        float maxAllowedX = getWidth() - config.getPaddingRightPx();
        float maxAllowedTextBottom = getHeight() - config.getPaddingBottomPx() - config.getFooterReservedHeightPx();

        // Отрисовка строк текущей страницы
        for (TextPaginator.Line line : currentPage.lines) {
            if (currentY + fm.bottom > maxAllowedTextBottom + 6) {
                break; // 100% математическая защита: текст физически не может наехать на колонтитул
            }

            if (line.text.isEmpty()) {
                currentY += lineHeight * 0.6f; // Компактный отступ между авторскими смысловыми секциями
                continue;
            }

            String drawText = line.text;
            if (drawText.indexOf('\uFFFC') >= 0 || drawText.indexOf('\uFFFD') >= 0) {
                drawText = drawText.replace("\uFFFC", "").replace("\uFFFD", "");
            }

            float lineX = startX + (line.isParagraphStart ? config.getParagraphIndentPx() : 0);

            if (config.isJustifyEnabled() && line.wordSpacing > 0 && !line.isLastLineOfParagraph) {
                // Отрисовка с выравниванием по ширине (Justify)
                String[] words = drawText.split(" ");
                float wordX = lineX;
                for (int i = 0; i < words.length; i++) {
                    canvas.drawText(words[i], wordX, currentY, textPaint);
                    wordX += textPaint.measureText(words[i]) + textPaint.measureText(" ") + line.wordSpacing;
                    if (wordX > maxAllowedX) {
                        break;
                    }
                }
            } else {
                // Стандартная отрисовка влево с соблюдением правого поля
                canvas.drawText(drawText, lineX, currentY, textPaint);
            }

            currentY += lineHeight;
        }

        // 2. Нижний колонтитул: Сквозная нумерация от всей книги и процент
        float footerY = getHeight() - 8;
        String pageInfo = "Стр. " + globalPageIndex + " из " + totalBookPages + String.format(java.util.Locale.getDefault(), " (%.0f%%)", globalPercent);
        float pageInfoWidth = footerPaint.measureText(pageInfo);

        // Страница и процент справа
        canvas.drawText(pageInfo, getWidth() - config.getPaddingRightPx() - pageInfoWidth, footerY, footerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float x = event.getX();
            float width = getWidth();

            if (interactionListener != null) {
                if (x < width * 0.3f) {
                    // Левая треть экрана: назад
                    interactionListener.onPageBackward();
                } else if (x > width * 0.7f) {
                    // Правая треть экрана: вперед
                    interactionListener.onPageForward();
                } else {
                    // Центральная область: меню
                    interactionListener.onCenterTap();
                }
            }
            return true;
        }
        return true;
    }

    private float spToPx(int sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }
}
