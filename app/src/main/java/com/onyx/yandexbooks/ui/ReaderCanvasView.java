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
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.onyx.yandexbooks.core.api.models.Quote;
import com.onyx.yandexbooks.core.typography.FontHelper;
import com.onyx.yandexbooks.core.typography.TextPaginator;
import com.onyx.yandexbooks.core.typography.TypographyConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private int chapterPageIndex = 1;
    private int chapterTotalPages = 1;
    private String chapterTitle = "";
    private int currentChapterIndex = 0;
    private int totalChapters = 1;
    private double globalPercent = 0.0;
    private String cachedBatteryAndTime = "";
    private long lastStatusUpdateMs = 0L;
    private final java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
    private OnReaderInteractionListener interactionListener;
    public interface OnCanvasSizeChangeListener {
        void onCanvasSizeChanged(int width, int height);
    }

    public interface OnFootnoteTapListener {
        void onFootnoteTap(String marker, String noteText);
    }

    public interface OnLinkTapListener {
        void onLinkTap(String target, String label);
    }

    public interface OnEdgeBrightnessGestureListener {
        void onEdgeBrightnessChanged(boolean isWarmEdge, int newPercent);
    }

    public interface OnExtraTapActionListener {
        void onOpenToc();
        void onToggleBookmark();
        void onForceRefresh();
    }

    public interface OnTextSelectionListener {
        void onTextSelected(String selectedText, int startOffset, int endOffset);
    }

    private static class RenderedWordBox {
        final RectF rect;
        final int startOffset;
        final int endOffset;
        final String text;

        RenderedWordBox(RectF rect, int startOffset, int endOffset, String text) {
            this.rect = rect;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.text = text;
        }
    }

    private static class FootnoteHitbox {
        final RectF bounds;
        final String marker;
        final String noteText;

        FootnoteHitbox(RectF bounds, String marker, String noteText) {
            this.bounds = bounds;
            this.marker = marker;
            this.noteText = noteText;
        }
    }

    private static class LinkHitbox {
        final RectF bounds;
        final String target;
        final String label;

        LinkHitbox(RectF bounds, String target, String label) {
            this.bounds = bounds;
            this.target = target;
            this.label = label;
        }
    }

    private ImageLoader imageLoader;
    private OnCanvasSizeChangeListener sizeChangeListener;
    private OnFootnoteTapListener footnoteTapListener;
    private OnLinkTapListener linkTapListener;
    private OnEdgeBrightnessGestureListener edgeBrightnessListener;
    private OnExtraTapActionListener extraTapActionListener;
    private OnTextSelectionListener textSelectionListener;

    private Map<String, String> footnotesMap;
    private final List<FootnoteHitbox> footnoteHitboxes = new ArrayList<>();
    private final List<LinkHitbox> linkHitboxes = new ArrayList<>();
    private final List<RenderedWordBox> renderedWordBoxes = new ArrayList<>();
    private final List<Quote> chapterQuotes = new ArrayList<>();
    private boolean isSelectingText = false;
    private int selectionAnchorOffset = -1;
    private int selectionFocusOffset = -1;
    private final Paint selectionFillPaint = new Paint();
    private final Paint savedQuoteFillPaint = new Paint();
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (isEdgeBrightnessDragging || textSelectionListener == null || renderedWordBoxes.isEmpty()) {
                return;
            }
            RenderedWordBox hit = findWordBoxAtOrNear(touchDownX, touchDownY, dpToPx(20f));
            if (hit != null) {
                isSelectingText = true;
                selectionAnchorOffset = hit.startOffset;
                selectionFocusOffset = hit.endOffset;
                try {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } catch (Throwable ignored) {}
                invalidate();
            }
        }
    };
    private final Paint runPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint decorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Typeface baseBookTypeface;
    private Typeface epubRegularTf;
    private Typeface epubBoldTf;
    private Typeface epubItalicTf;
    private Typeface epubBoldItalicTf;
    private String epubFontsSignature = "";
    private Typeface customBoldTf;
    private Typeface customItalicTf;
    private Typeface customBoldItalicTf;
    private String customFontsSignature = "";
    private static final Pattern FOOTNOTE_MARKER_PATTERN = Pattern.compile("\\[(\\d{1,4}|\\*{1,3})\\]");

    // Координаты и время для распознавания жестов свайпа, сносок и боковых свайпов подсветки
    private float touchDownX = 0f;
    private float touchDownY = 0f;
    private long touchDownTime = 0L;
    private boolean edgeBrightnessEnabled = true;
    private boolean touchStartedInLeftEdge = false;
    private boolean touchStartedInRightEdge = false;
    private boolean isEdgeBrightnessDragging = false;
    private int warmLightLevel = 50;
    private int coldLightLevel = 50;
    private int dragStartLightLevel = 50;
    private int lastDispatchedLightLevel = -1;
    private String brightnessHudText = null;
    private final Paint brightnessHudBgPaint = new Paint();
    private final Paint brightnessHudBorderPaint = new Paint();
    private final Paint brightnessHudTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint footnoteUnderlinePaint = new Paint();
    private final Runnable hideBrightnessHudRunnable = new Runnable() {
        @Override
        public void run() {
            brightnessHudText = null;
            invalidate();
        }
    };

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
        textPaint.setHinting(Paint.HINTING_ON);
        textPaint.setSubpixelText(false);
        textPaint.setDither(false);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setStrokeWidth(0f);
        textPaint.setFakeBoldText(config.isBoldText());

        headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerPaint.setColor(Color.BLACK);
        headerPaint.setTextSize(spToPx(11));
        headerPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        headerPaint.setHinting(Paint.HINTING_ON);
        headerPaint.setSubpixelText(false);

        footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerPaint.setColor(Color.BLACK); // Насыщенный черный цвет для E-Ink
        footerPaint.setTextSize(spToPx(11));
        footerPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        footerPaint.setHinting(Paint.HINTING_ON);
        footerPaint.setSubpixelText(false);
    }

    public Paint getTextPaint() {
        return textPaint;
    }

    public Paint buildPaintForConfig(TypographyConfig cfg) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setHinting(Paint.HINTING_ON);
        p.setSubpixelText(false);
        p.setDither(false);
        if (cfg == null) {
            p.set(textPaint);
            return p;
        }
        p.setTextSize(spToPx(cfg.getFontSizeSp()));
        p.setFakeBoldText(cfg.isBoldText());
        if ("high".equalsIgnoreCase(cfg.getContrastMode())) {
            p.setStrokeWidth(0.2f);
            p.setStyle(Paint.Style.FILL_AND_STROKE);
        } else {
            p.setStrokeWidth(0f);
            p.setStyle(Paint.Style.FILL);
        }
        Typeface tf;
        if (cfg.isUseEpubFonts() && epubRegularTf != null) {
            tf = epubRegularTf;
        } else {
            tf = FontHelper.getTypeface(getContext(), cfg.getFontFamily());
        }
        if (!cfg.isUseEpubFonts() && cfg.getCustomFontPath() != null) {
            File f = new File(cfg.getCustomFontPath());
            if (f.exists()) {
                try { tf = Typeface.createFromFile(f); } catch (Exception ignored) {}
            }
        }
        p.setTypeface(tf);
        return p;
    }

    public void setInteractionListener(OnReaderInteractionListener listener) {
        this.interactionListener = listener;
    }

    public void setTypographyConfig(TypographyConfig config) {
        this.config = config;
        textPaint.setTextSize(spToPx(config.getFontSizeSp()));
        textPaint.setHinting(Paint.HINTING_ON);
        textPaint.setSubpixelText(false);
        textPaint.setDither(false);
        textPaint.setFakeBoldText(config.isBoldText());

        Typeface baseTf = null;
        if (config.isUseEpubFonts() && epubRegularTf != null) {
            baseTf = epubRegularTf;
        } else {
            String ff = config.getFontFamily();
            baseTf = FontHelper.getTypeface(getContext(), ff);
            if (ff != null && ff.startsWith(FontHelper.CUSTOM_PREFIX)) {
                if (!ff.equals(customFontsSignature)) {
                    customFontsSignature = ff;
                    Map<String, String> sibs = FontHelper.resolveCustomFontFamilyPaths(ff);
                    customBoldTf = loadTypefaceFromPath(sibs.get("bold"));
                    customItalicTf = loadTypefaceFromPath(sibs.get("italic"));
                    customBoldItalicTf = loadTypefaceFromPath(sibs.get("bold_italic"));
                }
            } else {
                customFontsSignature = "";
                customBoldTf = null;
                customItalicTf = null;
                customBoldItalicTf = null;
            }
        }
        textPaint.setTypeface(baseTf);

        if ("high".equalsIgnoreCase(config.getContrastMode())) {
            textPaint.setStrokeWidth(0.2f);
            textPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        } else {
            textPaint.setStrokeWidth(0f);
            textPaint.setStyle(Paint.Style.FILL);
        }

        boolean isNight = config != null && config.isNightMode();
        int textColor = isNight ? Color.WHITE : Color.BLACK;
        textPaint.setColor(textColor);
        headerPaint.setColor(textColor);
        footerPaint.setColor(textColor);

        if (!config.isUseEpubFonts() && config.getCustomFontPath() != null) {
            File fontFile = new File(config.getCustomFontPath());
            if (fontFile.exists()) {
                try {
                    Typeface tf = Typeface.createFromFile(fontFile);
                    textPaint.setTypeface(tf);
                } catch (Exception ignored) {}
            }
        }
        baseBookTypeface = textPaint.getTypeface();
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

    public void setFootnotes(Map<String, String> footnotes) {
        this.footnotesMap = footnotes;
        invalidate();
    }

    public void setChapterQuotes(List<Quote> quotes) {
        this.chapterQuotes.clear();
        if (quotes != null) {
            this.chapterQuotes.addAll(quotes);
        }
        invalidate();
    }

    public void setOnTextSelectionListener(OnTextSelectionListener listener) {
        this.textSelectionListener = listener;
    }

    public void clearSelection() {
        isSelectingText = false;
        selectionAnchorOffset = -1;
        selectionFocusOffset = -1;
        invalidate();
    }

    public void setOnFootnoteTapListener(OnFootnoteTapListener listener) {
        this.footnoteTapListener = listener;
    }

    public void setOnLinkTapListener(OnLinkTapListener listener) {
        this.linkTapListener = listener;
    }

    public void setOnExtraTapActionListener(OnExtraTapActionListener listener) {
        this.extraTapActionListener = listener;
    }

    public void setEpubFontPaths(Map<String, String> fontPaths) {
        String sig = fontPaths != null ? fontPaths.toString() : "";
        if (sig.equals(epubFontsSignature)) {
            return;
        }
        epubFontsSignature = sig;
        epubRegularTf = loadTypefaceFromPath(fontPaths != null ? fontPaths.get("regular") : null);
        epubBoldTf = loadTypefaceFromPath(fontPaths != null ? fontPaths.get("bold") : null);
        epubItalicTf = loadTypefaceFromPath(fontPaths != null ? fontPaths.get("italic") : null);
        epubBoldItalicTf = loadTypefaceFromPath(fontPaths != null ? fontPaths.get("bold_italic") : null);
        if (epubRegularTf == null && epubItalicTf != null) epubRegularTf = epubItalicTf;
        if (epubRegularTf == null && epubBoldTf != null) epubRegularTf = epubBoldTf;
        if (config != null) {
            setTypographyConfig(config);
        }
    }

    private Typeface loadTypefaceFromPath(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            File f = new File(path);
            if (f.exists() && f.length() > 0) {
                return Typeface.createFromFile(f);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public void setOnEdgeBrightnessGestureListener(OnEdgeBrightnessGestureListener listener) {
        this.edgeBrightnessListener = listener;
    }

    public void setEdgeBrightnessEnabled(boolean enabled) {
        this.edgeBrightnessEnabled = enabled;
    }

    public void setInitialFrontlightLevels(int coldLevel, int warmLevel) {
        this.coldLightLevel = Math.max(0, Math.min(100, coldLevel));
        this.warmLightLevel = Math.max(0, Math.min(100, warmLevel));
    }

    public void setPage(TextPaginator.Page page, int globalPageIndex, int totalBookPages, String chapterTitle, int currentChapterIndex, int totalChapters, double globalPercent) {
        setPage(page, globalPageIndex, totalBookPages, chapterTitle, currentChapterIndex, totalChapters, globalPercent,
                page != null ? (page.pageIndex + 1) : 1, Math.max(1, totalBookPages));
    }

    public void setPage(TextPaginator.Page page, int globalPageIndex, int totalBookPages, String chapterTitle, int currentChapterIndex, int totalChapters, double globalPercent, int chapterPageIndex, int chapterTotalPages) {
        this.currentPage = page;
        this.globalPageIndex = Math.max(1, globalPageIndex);
        this.totalBookPages = Math.max(1, totalBookPages);
        this.totalPages = Math.max(1, totalBookPages);
        this.chapterPageIndex = Math.max(1, chapterPageIndex);
        this.chapterTotalPages = Math.max(1, chapterTotalPages);
        this.chapterTitle = chapterTitle != null ? chapterTitle : "";
        this.currentChapterIndex = currentChapterIndex;
        this.totalChapters = Math.max(1, totalChapters);
        this.globalPercent = Math.max(0.0, Math.min(100.0, globalPercent));
        invalidate();
    }

    private String getStatusTimeAndBattery() {
        long now = System.currentTimeMillis();
        if (now - lastStatusUpdateMs > 20000L || cachedBatteryAndTime.isEmpty()) {
            lastStatusUpdateMs = now;
            String timeStr = timeFormat.format(new java.util.Date(now));
            int batPct = -1;
            try {
                android.content.Intent intent = getContext().registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                if (intent != null) {
                    int level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                    if (level >= 0 && scale > 0) {
                        batPct = Math.round((level * 100f) / scale);
                    }
                }
            } catch (Throwable ignored) {}
            if (batPct >= 0) {
                cachedBatteryAndTime = timeStr + " • " + batPct + "%";
            } else {
                cachedBatteryAndTime = timeStr;
            }
        }
        return cachedBatteryAndTime;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        boolean isNight = config != null && config.isNightMode();
        int bgColor = isNight ? Color.BLACK : Color.WHITE;
        int textColor = isNight ? Color.WHITE : Color.BLACK;

        // Фон для E-Ink Carta (Чистый белый или глубокий черный для ночи)
        canvas.drawColor(bgColor);
        footnoteHitboxes.clear();
        linkHitboxes.clear();
        renderedWordBoxes.clear();
        decorPaint.setColor(textColor);
        decorPaint.setStyle(Paint.Style.STROKE);
        textPaint.setColor(textColor);
        headerPaint.setColor(textColor);
        footerPaint.setColor(textColor);
        footnoteUnderlinePaint.setColor(textColor);
        footnoteUnderlinePaint.setStrokeWidth(Math.max(1f, dpToPx(1f)));

        if (currentPage == null) {
            return;
        }

        // 1. Верхний колонтитул: Название текущей главы
        float headerY = config.getPaddingTopPx() + 16;
        if (chapterTitle != null && !chapterTitle.trim().isEmpty()) {
            String fullTitle = chapterTitle.trim();
            String titleText = fullTitle;
            float maxHeaderWidth = getWidth() - config.getPaddingLeftPx() - config.getPaddingRightPx();
            if (headerPaint.measureText(titleText) > maxHeaderWidth) {
                while (titleText.length() > 3 && headerPaint.measureText(titleText + "…") > maxHeaderWidth) {
                    titleText = titleText.substring(0, titleText.length() - 1);
                }
                titleText += "…";
            }
            canvas.drawText(titleText, Math.round(config.getPaddingLeftPx()), Math.round(headerY), headerPaint);
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
            Paint.FontMetrics ffm = footerPaint.getFontMetrics();
            int offsetDp = (config != null) ? config.getFooterBottomOffsetDp() : 16;
            float bottomSafePx = Math.max(dpToPx(offsetDp), config.getPaddingBottomPx());
            float footerY = getHeight() - bottomSafePx - Math.max(0f, ffm.bottom);
            float footerTopLimitY = footerY + ffm.top - dpToPx(6f);
            float screenLimitY = Math.min(
                    getHeight() - config.getPaddingBottomPx() - config.getFooterReservedHeightPx(),
                    footerTopLimitY
            );

            // Отрисовка строк текущей страницы (поддержка гибридного форматирования v1.7.0)
            boolean isFirstRenderedLine = true;
            for (TextPaginator.Line line : currentPage.lines) {
                float scaledLineHeight = lineHeight * line.fontScale;
                if (!isFirstRenderedLine && line.extraTopPx > 0f) {
                    currentY += line.extraTopPx;
                }
                if (currentY + (fm.bottom * line.fontScale) > screenLimitY + 4f) {
                    break; // Предотвращаем выход за пределы отведенной области
                }

                if (line.text.isEmpty()) {
                    currentY += scaledLineHeight;
                    isFirstRenderedLine = false;
                    continue;
                }

                float contentStartX = startX + line.leftInsetPx;
                float contentAvailWidth = Math.max(40f, maxAllowedX - contentStartX);
                float totalRunsWidth = measureRunsWidth(line.runs, line.fontScale);

                float lineX;
                if (line.alignment == TextPaginator.ALIGN_CENTER) {
                    lineX = contentStartX + Math.max(0f, (contentAvailWidth - totalRunsWidth) / 2f);
                } else if (line.alignment == TextPaginator.ALIGN_RIGHT) {
                    lineX = Math.max(contentStartX, maxAllowedX - totalRunsWidth);
                } else {
                    lineX = contentStartX + line.indentPx;
                }

                int drawY = Math.round(currentY);
                if (line.wordBoxes != null && !line.wordBoxes.isEmpty()) {
                    float boxTop = currentY + (fm.ascent * line.fontScale) - dpToPx(2f);
                    float boxBot = currentY + (fm.descent * line.fontScale) + dpToPx(2f);
                    int activeStart = (selectionAnchorOffset >= 0 && selectionFocusOffset >= 0) ? Math.min(selectionAnchorOffset, selectionFocusOffset) : -1;
                    int activeEnd = (selectionAnchorOffset >= 0 && selectionFocusOffset >= 0) ? Math.max(selectionAnchorOffset, selectionFocusOffset) : -1;
                    selectionFillPaint.setStyle(Paint.Style.FILL);
                    selectionFillPaint.setColor(isNight ? Color.rgb(90, 90, 90) : Color.rgb(190, 190, 190));
                    savedQuoteFillPaint.setStyle(Paint.Style.FILL);
                    savedQuoteFillPaint.setColor(isNight ? Color.rgb(52, 52, 52) : Color.rgb(226, 226, 226));

                    for (int wbIdx = 0; wbIdx < line.wordBoxes.size(); wbIdx++) {
                        TextPaginator.WordBox wb = line.wordBoxes.get(wbIdx);
                        RectF wRect = new RectF(lineX + wb.relStartX, boxTop, lineX + wb.relEndX, boxBot);
                        renderedWordBoxes.add(new RenderedWordBox(wRect, wb.startCharOffset, wb.endCharOffset, wb.text));

                        boolean inActiveSel = activeStart >= 0 && wb.startCharOffset < activeEnd && wb.endCharOffset > activeStart;
                        boolean inSavedQuote = !inActiveSel && isRangeInSavedQuotes(wb.startCharOffset, wb.endCharOffset);
                        if (inActiveSel || inSavedQuote) {
                            float drawRight = wRect.right;
                            if (wbIdx + 1 < line.wordBoxes.size()) {
                                TextPaginator.WordBox nextWb = line.wordBoxes.get(wbIdx + 1);
                                boolean nextMatches = inActiveSel
                                        ? (activeStart >= 0 && nextWb.startCharOffset < activeEnd && nextWb.endCharOffset > activeStart)
                                        : isRangeInSavedQuotes(nextWb.startCharOffset, nextWb.endCharOffset);
                                if (nextMatches) {
                                    drawRight = lineX + nextWb.relStartX;
                                }
                            }
                            canvas.drawRect(wRect.left, boxTop, drawRight, boxBot, inActiveSel ? selectionFillPaint : savedQuoteFillPaint);
                            if (inSavedQuote) {
                                decorPaint.setStrokeWidth(Math.max(1.5f, dpToPx(1.5f)));
                                canvas.drawLine(wRect.left, boxBot, drawRight, boxBot, decorPaint);
                            } else {
                                decorPaint.setStrokeWidth(Math.max(1.5f, dpToPx(1.5f)));
                                canvas.drawLine(wRect.left, boxTop, drawRight, boxTop, decorPaint);
                                canvas.drawLine(wRect.left, boxBot, drawRight, boxBot, decorPaint);
                            }
                        }
                    }
                }
                if (line.drawQuoteBar) {
                    decorPaint.setStrokeWidth(Math.max(2f, dpToPx(2f)));
                    float barX = startX + Math.max(4f, line.leftInsetPx * 0.42f);
                    float barTop = currentY + (fm.top * line.fontScale) * 0.85f;
                    float barBottom = currentY + (fm.bottom * line.fontScale) * 0.90f;
                    canvas.drawLine(barX, barTop, barX, barBottom, decorPaint);
                }

                boolean isJustified = (line.alignment == TextPaginator.ALIGN_JUSTIFY)
                        && line.wordSpacing > 0f
                        && !line.isLastLineOfParagraph;

                float curX = lineX;
                for (int rIdx = 0; rIdx < line.runs.size(); rIdx++) {
                    TextPaginator.LineRun run = line.runs.get(rIdx);
                    String runText = run.text;
                    if (runText.indexOf('\uFFFC') >= 0 || runText.indexOf('\uFFFD') >= 0) {
                        runText = runText.replace("\uFFFC", "").replace("\uFFFD", "");
                    }
                    if (runText.isEmpty()) continue;

                    configureRunPaint(run.style, line.fontScale, textColor);
                    Paint.FontMetrics rfm = runPaint.getFontMetrics();
                    float segStartX = curX;

                    if (isJustified && runText.indexOf(' ') >= 0) {
                        int segPos = 0;
                        int rLen = runText.length();
                        while (segPos < rLen) {
                            int spIdx = runText.indexOf(' ', segPos);
                            if (spIdx < 0) {
                                String part = runText.substring(segPos);
                                canvas.drawText(part, Math.round(curX), drawY, runPaint);
                                collectWordFootnoteHitboxes(canvas, part, curX, drawY, runPaint, rfm);
                                curX += runPaint.measureText(part);
                                break;
                            } else {
                                if (spIdx > segPos) {
                                    String part = runText.substring(segPos, spIdx);
                                    canvas.drawText(part, Math.round(curX), drawY, runPaint);
                                    collectWordFootnoteHitboxes(canvas, part, curX, drawY, runPaint, rfm);
                                    curX += runPaint.measureText(part);
                                }
                                curX += runPaint.measureText(" ") + line.wordSpacing;
                                segPos = spIdx + 1;
                            }
                        }
                    } else {
                        canvas.drawText(runText, Math.round(curX), drawY, runPaint);
                        collectWordFootnoteHitboxes(canvas, runText, curX, drawY, runPaint, rfm);
                        curX += runPaint.measureText(runText);
                    }

                    float segEndX = curX;
                    if ((run.style & TextPaginator.STYLE_UNDERLINE) != 0 || run.linkTarget != null) {
                        float uY = drawY + Math.max(1.5f, rfm.descent * 0.45f);
                        decorPaint.setStrokeWidth(Math.max(1f, dpToPx(1f)));
                        canvas.drawLine(segStartX, uY, segEndX, uY, decorPaint);
                    }
                    if ((run.style & TextPaginator.STYLE_STRIKE) != 0) {
                        float sY = drawY + (rfm.ascent * 0.35f);
                        decorPaint.setStrokeWidth(Math.max(1f, dpToPx(1f)));
                        canvas.drawLine(segStartX, sY, segEndX, sY, decorPaint);
                    }
                    if (run.linkTarget != null && !run.linkTarget.trim().isEmpty()) {
                        float pad = dpToPx(4f);
                        linkHitboxes.add(new LinkHitbox(
                                new RectF(segStartX - pad * 0.5f, drawY + rfm.top - pad, segEndX + pad * 0.5f, drawY + rfm.bottom + pad),
                                run.linkTarget,
                                run.linkLabel != null ? run.linkLabel : runText
                        ));
                    }

                    if (curX > maxAllowedX + 12f) {
                        break;
                    }
                }

                currentY += scaledLineHeight + line.extraBottomPx;
                isFirstRenderedLine = false;
            }
        }

        // 2. Нижний колонтитул: Слева — время и заряд батареи; Справа — только сквозная страница всей книги и процент
        Paint.FontMetrics ffm = footerPaint.getFontMetrics();
        int offsetDp = (config != null) ? config.getFooterBottomOffsetDp() : 16;
        float bottomSafePx = Math.max(dpToPx(offsetDp), config != null ? config.getPaddingBottomPx() : 0f);
        float footerY = getHeight() - bottomSafePx - Math.max(0f, ffm.bottom);
        int drawFooterY = Math.round(footerY);

        String leftStatus = getStatusTimeAndBattery();
        if (leftStatus != null && !leftStatus.isEmpty()) {
            canvas.drawText(leftStatus, Math.round(config.getPaddingLeftPx()), drawFooterY, footerPaint);
        }

        String pageInfo = "Стр. " + globalPageIndex + " из " + totalBookPages
                + String.format(java.util.Locale.getDefault(), " (%.0f%%)", globalPercent);
        float pageInfoWidth = footerPaint.measureText(pageInfo);
        float leftWidth = (leftStatus != null ? footerPaint.measureText(leftStatus) : 0f);
        float availFooterW = getWidth() - config.getPaddingLeftPx() - config.getPaddingRightPx();
        if (leftWidth + pageInfoWidth + 16f > availFooterW) {
            // Компактный формат для крупных шрифтов или узких экранов
            pageInfo = globalPageIndex + "/" + totalBookPages
                    + String.format(java.util.Locale.getDefault(), " (%.0f%%)", globalPercent);
            pageInfoWidth = footerPaint.measureText(pageInfo);
        }

        // Страница и процент справа с привязкой к физической сетке пикселей
        int drawFooterX = Math.round(getWidth() - config.getPaddingRightPx() - pageInfoWidth);
        canvas.drawText(pageInfo, drawFooterX, drawFooterY, footerPaint);

        // 3. Временный контрастный HUD подсветки при вертикальном свайпе по краю экрана (без анимации для E-Ink)
        if (brightnessHudText != null && !brightnessHudText.isEmpty()) {
            drawBrightnessHud(canvas, brightnessHudText, isNight);
        }
    }

    private void configureRunPaint(int style, float fontScale, int textColor) {
        runPaint.set(textPaint);
        runPaint.setColor(textColor);
        runPaint.setTextSize(textPaint.getTextSize() * fontScale);

        boolean bold = (style & TextPaginator.STYLE_BOLD) != 0;
        boolean italic = (style & TextPaginator.STYLE_ITALIC) != 0;
        boolean mono = (style & TextPaginator.STYLE_MONO) != 0;

        if (mono) {
            int tfStyle = (bold && italic) ? Typeface.BOLD_ITALIC
                    : (bold ? Typeface.BOLD : (italic ? Typeface.ITALIC : Typeface.NORMAL));
            runPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, tfStyle));
            return;
        }

        boolean useEpub = (config != null && config.isUseEpubFonts());
        if (useEpub) {
            if (bold && italic && epubBoldItalicTf != null) {
                runPaint.setTypeface(epubBoldItalicTf);
                return;
            }
            if (bold && epubBoldTf != null) {
                runPaint.setTypeface(epubBoldTf);
                return;
            }
            if (italic && epubItalicTf != null) {
                runPaint.setTypeface(epubItalicTf);
                return;
            }
            if (epubRegularTf != null) {
                int tfStyle = (bold && italic) ? Typeface.BOLD_ITALIC
                        : (bold ? Typeface.BOLD : (italic ? Typeface.ITALIC : Typeface.NORMAL));
                Typeface derived = Typeface.create(epubRegularTf, tfStyle);
                runPaint.setTypeface(derived);
                if (bold && derived.getStyle() != Typeface.BOLD && derived.getStyle() != Typeface.BOLD_ITALIC) {
                    runPaint.setFakeBoldText(true);
                }
                if (italic && !derived.isItalic()) {
                    runPaint.setTextSkewX(-0.22f);
                }
                return;
            }
        }

        if (!useEpub && customFontsSignature != null && !customFontsSignature.isEmpty()) {
            if (bold && italic && customBoldItalicTf != null) {
                runPaint.setTypeface(customBoldItalicTf);
                return;
            }
            if (bold && customBoldTf != null) {
                runPaint.setTypeface(customBoldTf);
                return;
            }
            if (italic && customItalicTf != null) {
                runPaint.setTypeface(customItalicTf);
                return;
            }
        }

        if (baseBookTypeface != null) {
            int wantStyle = baseBookTypeface.getStyle();
            if (bold) wantStyle |= Typeface.BOLD;
            if (italic) wantStyle |= Typeface.ITALIC;
            if (wantStyle != baseBookTypeface.getStyle()) {
                Typeface derived = Typeface.create(baseBookTypeface, wantStyle);
                runPaint.setTypeface(derived);
                if (bold && !derived.isBold()) {
                    runPaint.setFakeBoldText(true);
                }
                if (italic && !derived.isItalic()) {
                    runPaint.setTextSkewX(-0.22f);
                }
            } else {
                runPaint.setTypeface(baseBookTypeface);
            }
        } else {
            int tfStyle = (bold && italic) ? Typeface.BOLD_ITALIC
                    : (bold ? Typeface.BOLD : (italic ? Typeface.ITALIC : Typeface.NORMAL));
            runPaint.setTypeface(Typeface.defaultFromStyle(tfStyle));
        }
    }

    private float measureRunsWidth(List<TextPaginator.LineRun> runs, float fontScale) {
        if (runs == null || runs.isEmpty()) return 0f;
        int color = textPaint.getColor();
        float total = 0f;
        for (int i = 0; i < runs.size(); i++) {
            TextPaginator.LineRun r = runs.get(i);
            configureRunPaint(r.style, fontScale, color);
            total += runPaint.measureText(r.text);
        }
        return total;
    }

    private String resolveFootnoteText(String marker) {
        if (footnotesMap == null || footnotesMap.isEmpty() || marker == null) {
            return null;
        }
        String scopedKey = "ch:" + currentChapterIndex + "|" + marker;
        String text = footnotesMap.get(scopedKey);
        if (text != null && !text.isEmpty()) {
            return text;
        }
        return footnotesMap.get(marker);
    }

    private void collectWordFootnoteHitboxes(Canvas canvas, String segment, float segmentX, int baselineY, Paint paint, Paint.FontMetrics fm) {
        if (footnotesMap == null || footnotesMap.isEmpty() || segment == null || segment.indexOf('[') < 0) {
            return;
        }
        Matcher m = FOOTNOTE_MARKER_PATTERN.matcher(segment);
        float hitPad = dpToPx(10f);
        float topY = baselineY + fm.ascent - hitPad;
        float bottomY = baselineY + fm.descent + hitPad;
        float underlineY = baselineY + Math.max(2f, dpToPx(2f));
        while (m.find()) {
            String marker = m.group(0);
            String noteText = resolveFootnoteText(marker);
            if (noteText == null || noteText.isEmpty()) continue;

            float preW = paint.measureText(segment, 0, m.start());
            float markerW = paint.measureText(segment, m.start(), m.end());
            float left = segmentX + preW;
            float right = left + markerW;
            canvas.drawLine(left, underlineY, right, underlineY, footnoteUnderlinePaint);
            footnoteHitboxes.add(new FootnoteHitbox(
                    new RectF(left - hitPad, topY, right + hitPad, bottomY),
                    marker,
                    noteText
            ));
        }
    }

    private void drawBrightnessHud(Canvas canvas, String label, boolean isNight) {
        brightnessHudTextPaint.setTextSize(spToPx(14));
        brightnessHudTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        brightnessHudTextPaint.setColor(isNight ? Color.WHITE : Color.BLACK);
        float textW = brightnessHudTextPaint.measureText(label);
        Paint.FontMetrics fm = brightnessHudTextPaint.getFontMetrics();
        float textH = fm.descent - fm.ascent;
        float padH = dpToPx(14f);
        float padV = dpToPx(8f);
        float boxW = textW + padH * 2f;
        float boxH = textH + padV * 2f;
        float left = (getWidth() - boxW) * 0.5f;
        float top = Math.max(dpToPx(28f), (config != null ? config.getPaddingTopPx() + dpToPx(22f) : dpToPx(28f)));
        RectF rect = new RectF(left, top, left + boxW, top + boxH);

        brightnessHudBgPaint.setStyle(Paint.Style.FILL);
        brightnessHudBgPaint.setColor(isNight ? Color.BLACK : Color.WHITE);
        brightnessHudBorderPaint.setStyle(Paint.Style.STROKE);
        brightnessHudBorderPaint.setStrokeWidth(Math.max(2f, dpToPx(1.5f)));
        brightnessHudBorderPaint.setColor(isNight ? Color.WHITE : Color.BLACK);

        canvas.drawRect(rect, brightnessHudBgPaint);
        canvas.drawRect(rect, brightnessHudBorderPaint);
        canvas.drawText(label, left + padH, top + padV - fm.ascent, brightnessHudTextPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float width = getWidth();
        float height = getHeight();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchDownTime = System.currentTimeMillis();
                isEdgeBrightnessDragging = false;
                touchStartedInLeftEdge = edgeBrightnessEnabled && width > 0 && touchDownX <= width * 0.10f;
                touchStartedInRightEdge = edgeBrightnessEnabled && width > 0 && touchDownX >= width * 0.90f;
                dragStartLightLevel = touchStartedInLeftEdge ? warmLightLevel : coldLightLevel;
                lastDispatchedLightLevel = -1;
                removeCallbacks(longPressRunnable);
                if (textSelectionListener != null && !renderedWordBoxes.isEmpty() && !touchStartedInLeftEdge && !touchStartedInRightEdge) {
                    postDelayed(longPressRunnable, 480L);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isSelectingText) {
                    RenderedWordBox hovered = findWordBoxAtOrNear(event.getX(), event.getY(), dpToPx(32f));
                    if (hovered != null) {
                        int newFocus = hovered.startOffset >= selectionAnchorOffset ? hovered.endOffset : hovered.startOffset;
                        if (newFocus != selectionFocusOffset) {
                            selectionFocusOffset = newFocus;
                            invalidate();
                        }
                    }
                    return true;
                }
                if (Math.abs(event.getX() - touchDownX) > dpToPx(14f) || Math.abs(event.getY() - touchDownY) > dpToPx(14f)) {
                    removeCallbacks(longPressRunnable);
                }
                if (edgeBrightnessEnabled && (touchStartedInLeftEdge || touchStartedInRightEdge) && height > 0) {
                    float moveX = event.getX();
                    float moveY = event.getY();
                    float dx = moveX - touchDownX;
                    float dy = moveY - touchDownY;
                    float edgeThresholdPx = Math.max(24f, dpToPx(20f));
                    if (!isEdgeBrightnessDragging) {
                        boolean stillInEdge = touchStartedInLeftEdge
                                ? (moveX <= width * 0.16f)
                                : (moveX >= width * 0.84f);
                        if (stillInEdge && Math.abs(dy) >= edgeThresholdPx && Math.abs(dy) > Math.abs(dx) * 1.8f) {
                            isEdgeBrightnessDragging = true;
                            removeCallbacks(hideBrightnessHudRunnable);
                        }
                    }
                    if (isEdgeBrightnessDragging) {
                        float sensitivityPx = Math.max(dpToPx(180f), height * 0.55f);
                        int deltaPct = Math.round((-dy / sensitivityPx) * 100f);
                        int nextLevel = Math.max(0, Math.min(100, dragStartLightLevel + deltaPct));
                        if (touchStartedInLeftEdge) {
                            warmLightLevel = nextLevel;
                            brightnessHudText = "Тёплый свет: " + nextLevel + "%";
                        } else {
                            coldLightLevel = nextLevel;
                            brightnessHudText = "Холодный свет: " + nextLevel + "%";
                        }
                        if (edgeBrightnessListener != null && (lastDispatchedLightLevel < 0 || Math.abs(nextLevel - lastDispatchedLightLevel) >= 2)) {
                            lastDispatchedLightLevel = nextLevel;
                            edgeBrightnessListener.onEdgeBrightnessChanged(touchStartedInLeftEdge, nextLevel);
                        }
                        invalidate();
                        return true;
                    }
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                if (isSelectingText) {
                    clearSelection();
                }
                if (isEdgeBrightnessDragging) {
                    isEdgeBrightnessDragging = false;
                    postDelayed(hideBrightnessHudRunnable, 1200L);
                }
                return true;

            case MotionEvent.ACTION_UP:
                removeCallbacks(longPressRunnable);
                if (isSelectingText) {
                    isSelectingText = false;
                    dispatchSelectedText();
                    return true;
                }
                float upX = event.getX();
                float upY = event.getY();
                if (isEdgeBrightnessDragging) {
                    isEdgeBrightnessDragging = false;
                    if (edgeBrightnessListener != null) {
                        int finalLevel = touchStartedInLeftEdge ? warmLightLevel : coldLightLevel;
                        edgeBrightnessListener.onEdgeBrightnessChanged(touchStartedInLeftEdge, finalLevel);
                    }
                    postDelayed(hideBrightnessHudRunnable, 1200L);
                    return true;
                }
                float deltaX = upX - touchDownX;
                float deltaY = upY - touchDownY;
                long duration = System.currentTimeMillis() - touchDownTime;

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
                        // 1.1 Проверка попадания в кликабельную сноску или гиперссылку ДО зон перелистывания (не теряя страницу)
                        float tapSlop = dpToPx(16f);
                        if (Math.abs(deltaX) < tapSlop && Math.abs(deltaY) < tapSlop) {
                            if (footnoteTapListener != null) {
                                for (int i = 0; i < footnoteHitboxes.size(); i++) {
                                    FootnoteHitbox hb = footnoteHitboxes.get(i);
                                    if (hb.bounds.contains(upX, upY)) {
                                        footnoteTapListener.onFootnoteTap(hb.marker, hb.noteText);
                                        return true;
                                    }
                                }
                            }
                            if (linkTapListener != null) {
                                for (int i = 0; i < linkHitboxes.size(); i++) {
                                    LinkHitbox lhb = linkHitboxes.get(i);
                                    if (lhb.bounds.contains(upX, upY)) {
                                        linkTapListener.onLinkTap(lhb.target, lhb.label);
                                        return true;
                                    }
                                }
                            }
                        }

                        String tapMode = (config != null) ? config.getTapZoneMode() : "standard";
                        if ("left_handed".equalsIgnoreCase(tapMode)) {
                            // Режим «Для левшей»: левый край делится по вертикали:
                            // верхние 30% и нижние 30% слева -> Вперед; центр слева (40%) -> Назад!
                            if (upX < width * 0.35f) {
                                if (upY < height * 0.30f || upY > height * 0.70f) {
                                    interactionListener.onPageForward();
                                } else {
                                    interactionListener.onPageBackward();
                                }
                            } else if (upX > width * 0.70f) {
                                interactionListener.onPageForward();
                            } else {
                                interactionListener.onCenterTap();
                            }
                        } else if ("top_back_bottom_fwd".equalsIgnoreCase(tapMode)) {
                            // Верхние 30% -> Назад, Нижние 40% -> Вперед, Центр -> Меню
                            if (upY < height * 0.30f) {
                                interactionListener.onPageBackward();
                            } else if (upY > height * 0.60f) {
                                interactionListener.onPageForward();
                            } else {
                                interactionListener.onCenterTap();
                            }
                        } else if ("custom_3x3".equalsIgnoreCase(tapMode) && config != null) {
                            int col = (upX < width * 0.3333f) ? 0 : (upX < width * 0.6667f ? 1 : 2);
                            int row = (upY < height * 0.3333f) ? 0 : (upY < height * 0.6667f ? 1 : 2);
                            String action = config.getCustomTapActionAt(row, col);
                            dispatchCustomTapAction(action);
                        } else {
                            // 2. Стандартный тап по зонам (левые 30% Назад, правые 30% Вперед, центр 40% Меню)
                            if (upX < width * 0.30f) {
                                interactionListener.onPageBackward();
                            } else if (upX > width * 0.70f) {
                                interactionListener.onPageForward();
                            } else {
                                interactionListener.onCenterTap();
                            }
                        }
                    }
                }
                return true;
        }
        return true;
    }

    private void dispatchCustomTapAction(String action) {
        if ("prev".equalsIgnoreCase(action)) {
            if (interactionListener != null) interactionListener.onPageBackward();
        } else if ("next".equalsIgnoreCase(action)) {
            if (interactionListener != null) interactionListener.onPageForward();
        } else if ("menu".equalsIgnoreCase(action)) {
            if (interactionListener != null) interactionListener.onCenterTap();
        } else if ("toc".equalsIgnoreCase(action)) {
            if (extraTapActionListener != null) extraTapActionListener.onOpenToc();
        } else if ("bookmark".equalsIgnoreCase(action)) {
            if (extraTapActionListener != null) extraTapActionListener.onToggleBookmark();
        } else if ("refresh".equalsIgnoreCase(action)) {
            if (extraTapActionListener != null) extraTapActionListener.onForceRefresh();
        }
    }

    private boolean isRangeInSavedQuotes(int startOffset, int endOffset) {
        if (chapterQuotes.isEmpty()) return false;
        for (int i = 0; i < chapterQuotes.size(); i++) {
            Quote q = chapterQuotes.get(i);
            if (q == null) continue;
            if (q.endOffset > startOffset && q.startOffset < endOffset) {
                return true;
            }
        }
        return false;
    }

    private RenderedWordBox findWordBoxAtOrNear(float x, float y, float maxRadiusPx) {
        if (renderedWordBoxes.isEmpty()) return null;
        for (int i = 0; i < renderedWordBoxes.size(); i++) {
            RenderedWordBox b = renderedWordBoxes.get(i);
            if (x >= b.rect.left - dpToPx(3f) && x <= b.rect.right + dpToPx(3f)
                    && y >= b.rect.top - dpToPx(3f) && y <= b.rect.bottom + dpToPx(3f)) {
                return b;
            }
        }
        RenderedWordBox best = null;
        float bestDist = maxRadiusPx * maxRadiusPx;
        for (int i = 0; i < renderedWordBoxes.size(); i++) {
            RenderedWordBox b = renderedWordBoxes.get(i);
            float cy = (b.rect.top + b.rect.bottom) * 0.5f;
            float dy = y - cy;
            float dx = (x < b.rect.left) ? (b.rect.left - x) : ((x > b.rect.right) ? (x - b.rect.right) : 0f);
            float distSq = dx * dx + (dy * dy * 2.2f);
            if (distSq < bestDist) {
                bestDist = distSq;
                best = b;
            }
        }
        return best;
    }

    private void dispatchSelectedText() {
        if (textSelectionListener == null || selectionAnchorOffset < 0 || selectionFocusOffset < 0) {
            clearSelection();
            return;
        }
        int selStart = Math.min(selectionAnchorOffset, selectionFocusOffset);
        int selEnd = Math.max(selectionAnchorOffset, selectionFocusOffset);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < renderedWordBoxes.size(); i++) {
            RenderedWordBox b = renderedWordBoxes.get(i);
            if (b.startOffset < selEnd && b.endOffset > selStart) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(b.text);
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            clearSelection();
            return;
        }
        textSelectionListener.onTextSelected(text, selStart, selEnd);
    }

    private float spToPx(int sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
