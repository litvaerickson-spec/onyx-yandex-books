package com.onyx.yandexbooks.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
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

    public interface ImageLoader {
        Bitmap loadImage(String imagePath, int reqWidth, int reqHeight);
        Bitmap loadCover(int reqWidth, int reqHeight);
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
    public interface OnCanvasSizeChangeListener {
        void onCanvasSizeChanged(int width, int height);
    }

    private ImageLoader imageLoader;
    private OnCanvasSizeChangeListener sizeChangeListener;

    // Координаты и время для распознавания жестов свайпа и тапов
    private float touchDownX = 0f;
    private float touchDownY = 0f;
    private long touchDownTime = 0L;

    public ReaderCanvasView(Context context) {
        super(context);
        init();
    }

    public void setOnCanvasSizeChangeListener(OnCanvasSizeChangeListener listener) {
        this.sizeChangeListener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
            if (sizeChangeListener != null) {
                sizeChangeListener.onCanvasSizeChanged(w, h);
            }
        }
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

        boolean isNight = config != null && config.isNightMode();
        int textColor = isNight ? Color.WHITE : Color.BLACK;
        textPaint.setColor(textColor);
        headerPaint.setColor(textColor);
        footerPaint.setColor(textColor);

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

    public void setImageLoader(ImageLoader loader) {
        this.imageLoader = loader;
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

        boolean isNight = config != null && config.isNightMode();
        int bgColor = isNight ? Color.BLACK : Color.WHITE;
        int textColor = isNight ? Color.WHITE : Color.BLACK;

        // Фон для E-Ink Carta (Чистый белый или глубокий черный для ночи)
        canvas.drawColor(bgColor);
        textPaint.setColor(textColor);
        headerPaint.setColor(textColor);
        footerPaint.setColor(textColor);

        if (currentPage == null) {
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

        boolean isImagePage = false;
        String imgPath = null;
        if (currentPage.lines != null && !currentPage.lines.isEmpty()) {
            String firstLine = currentPage.lines.get(0).text.trim();
            if (firstLine.startsWith("[IMG:") && firstLine.endsWith("]")) {
                isImagePage = true;
                imgPath = firstLine.substring(5, firstLine.length() - 1).trim();
            }
        }

        if (isImagePage && imgPath != null) {
            float availW = getWidth() - config.getPaddingLeftPx() - config.getPaddingRightPx();
            float availH = getHeight() - config.getPaddingTopPx() - config.getHeaderReservedHeightPx() - config.getPaddingBottomPx() - config.getFooterReservedHeightPx();
            Bitmap bmp = (imageLoader != null) ? imageLoader.loadImage(imgPath, (int) availW, (int) availH) : null;
            if (bmp != null && !bmp.isRecycled()) {
                float bw = bmp.getWidth();
                float bh = bmp.getHeight();
                float scale = Math.min(availW / bw, availH / bh);
                if (scale > 1.0f) scale = 1.0f; // Не масштабируем мелкие иконки больше 100%
                float destW = bw * scale;
                float destH = bh * scale;
                float destX = config.getPaddingLeftPx() + (availW - destW) / 2f;
                float destY = config.getPaddingTopPx() + config.getHeaderReservedHeightPx() + (availH - destH) / 2f;
                Rect srcRect = new Rect(0, 0, (int) bw, (int) bh);
                RectF dstRect = new RectF(destX, destY, destX + destW, destY + destH);
                Paint imgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(bmp, srcRect, dstRect, imgPaint);
            } else {
                // Fallback: аккуратная плашка иллюстрации
                String label = "Иллюстрация";
                float textW = textPaint.measureText(label);
                float textX = Math.max(config.getPaddingLeftPx(), (getWidth() - textW) / 2f);
                float textY = getHeight() / 2f;
                canvas.drawText(label, textX, textY, textPaint);
            }
        } else if (currentPage.lines == null || currentPage.lines.isEmpty()) {
            Bitmap coverBmp = (imageLoader != null && globalPageIndex <= 1) ? imageLoader.loadCover((int) (getWidth() - config.getPaddingLeftPx() - config.getPaddingRightPx()), (int) (getHeight() - config.getPaddingTopPx() - config.getPaddingBottomPx())) : null;
            if (coverBmp != null && !coverBmp.isRecycled()) {
                float availW = getWidth() - config.getPaddingLeftPx() - config.getPaddingRightPx();
                float availH = getHeight() - config.getPaddingTopPx() - config.getHeaderReservedHeightPx() - config.getPaddingBottomPx() - config.getFooterReservedHeightPx();
                float bw = coverBmp.getWidth();
                float bh = coverBmp.getHeight();
                float scale = Math.min(availW / bw, availH / bh);
                if (scale > 1.0f) scale = 1.0f;
                float destW = bw * scale;
                float destH = bh * scale;
                float destX = config.getPaddingLeftPx() + (availW - destW) / 2f;
                float destY = config.getPaddingTopPx() + config.getHeaderReservedHeightPx() + (availH - destH) / 2f;
                Rect srcRect = new Rect(0, 0, (int) bw, (int) bh);
                RectF dstRect = new RectF(destX, destY, destX + destW, destY + destH);
                Paint imgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(coverBmp, srcRect, dstRect, imgPaint);
            } else if (chapterTitle != null && !chapterTitle.trim().isEmpty()) {
                float textW = textPaint.measureText(chapterTitle.trim());
                float textX = Math.max(config.getPaddingLeftPx(), (getWidth() - textW) / 2f);
                float textY = getHeight() / 2f;
                canvas.drawText(chapterTitle.trim(), textX, textY, textPaint);
            }
        } else {
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float fontHeight = fm.bottom - fm.top;
            float lineHeight = fontHeight * config.getLineSpacingMultiplier();

            float startX = config.getPaddingLeftPx();
            float currentY = config.getPaddingTopPx() + config.getHeaderReservedHeightPx() - fm.top;
            float maxAllowedX = getWidth() - config.getPaddingRightPx();
            float screenLimitY = getHeight() - config.getPaddingBottomPx() - config.getFooterReservedHeightPx();

            // Отрисовка строк текущей страницы (строки уже гарантированно рассчитаны TextPaginator)
            for (TextPaginator.Line line : currentPage.lines) {
                if (currentY + fm.bottom > screenLimitY + 4) {
                    break; // Предотвращаем выход за пределы отведенной области
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
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchDownTime = System.currentTimeMillis();
                return true;

            case MotionEvent.ACTION_UP:
                float upX = event.getX();
                float upY = event.getY();
                float deltaX = upX - touchDownX;
                float deltaY = upY - touchDownY;
                long duration = System.currentTimeMillis() - touchDownTime;
                float width = getWidth();

                if (interactionListener != null) {
                    // 1. Определение горизонтального свайпа (смещение >= 35px, горизонталь преобладает над вертикалью)
                    if (Math.abs(deltaX) >= 35 && Math.abs(deltaX) > Math.abs(deltaY) * 1.2f) {
                        if (deltaX < 0) {
                            // Свайп справа налево -> Следующая страница (Вперед)
                            interactionListener.onPageForward();
                        } else {
                            // Свайп слева направо -> Предыдущая страница (Назад)
                            interactionListener.onPageBackward();
                        }
                    } else if (duration < 700) {
                        // 2. Дискретный тап по зонам (даже при мелком дрожании пальца на E-Ink тачскрине)
                        if (upX < width * 0.30f) {
                            // Левые 30% экрана: Назад
                            interactionListener.onPageBackward();
                        } else if (upX > width * 0.70f) {
                            // Правые 30% экрана: Вперед
                            interactionListener.onPageForward();
                        } else {
                            // Центральные 40% экрана: Меню ридера
                            interactionListener.onCenterTap();
                        }
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
