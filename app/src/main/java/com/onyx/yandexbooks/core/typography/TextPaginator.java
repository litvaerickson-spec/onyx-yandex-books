package com.onyx.yandexbooks.core.typography;

import android.graphics.Paint;
import android.graphics.Typeface;

import com.onyx.yandexbooks.core.epub.EpubFormatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Математический пагинатор страниц для E-Ink экрана.
 * Поддерживает гибридное форматирование EPUB (жирный/курсив/подчёркивание/зачёркивание/моноширинный,
 * заголовки h1-h6, выравнивание, цитаты с отбивкой, принудительные разрывы страниц,
 * кликабельные внутренние и внешние ссылки), переносы слов TeX, красную строку
 * и выравнивание по ширине с точной высотой каждой строки.
 */
public class TextPaginator {

    public static final int STYLE_NORMAL = 0;
    public static final int STYLE_BOLD = 1;
    public static final int STYLE_ITALIC = 2;
    public static final int STYLE_UNDERLINE = 4;
    public static final int STYLE_STRIKE = 8;
    public static final int STYLE_MONO = 16;

    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;
    public static final int ALIGN_JUSTIFY = 3;

    private static final Pattern BARE_URL_PATTERN = Pattern.compile("(https?://[^\\s<>()\\[\\]{}\"']+)");

    public static class LineRun {
        public final String text;
        public final int style;
        public final String linkTarget;
        public final String linkLabel;

        public LineRun(String text, int style, String linkTarget, String linkLabel) {
            this.text = text != null ? text : "";
            this.style = style;
            this.linkTarget = linkTarget;
            this.linkLabel = linkLabel;
        }
    }

    public static class Line {
        public final String text;
        public final boolean isParagraphStart;
        public final boolean isLastLineOfParagraph;
        public final float wordSpacing;
        public final int alignment;
        public final float fontScale;
        public final float indentPx;
        public final float leftInsetPx;
        public final boolean drawQuoteBar;
        public final float extraTopPx;
        public final float extraBottomPx;
        public final List<LineRun> runs;

        public Line(String text, boolean isParagraphStart, boolean isLastLineOfParagraph, float wordSpacing) {
            this(text, isParagraphStart, isLastLineOfParagraph, wordSpacing,
                    isLastLineOfParagraph ? ALIGN_LEFT : ALIGN_JUSTIFY,
                    1.0f, 0f, 0f, false, 0f, 0f, null);
        }

        public Line(
                String text,
                boolean isParagraphStart,
                boolean isLastLineOfParagraph,
                float wordSpacing,
                int alignment,
                float fontScale,
                float indentPx,
                float leftInsetPx,
                boolean drawQuoteBar,
                float extraTopPx,
                float extraBottomPx,
                List<LineRun> runs
        ) {
            this.text = text != null ? text : "";
            this.isParagraphStart = isParagraphStart;
            this.isLastLineOfParagraph = isLastLineOfParagraph;
            this.wordSpacing = wordSpacing;
            this.alignment = alignment;
            this.fontScale = fontScale > 0f ? fontScale : 1.0f;
            this.indentPx = indentPx;
            this.leftInsetPx = leftInsetPx;
            this.drawQuoteBar = drawQuoteBar;
            this.extraTopPx = extraTopPx;
            this.extraBottomPx = extraBottomPx;
            this.runs = runs != null ? runs : Collections.singletonList(new LineRun(this.text, STYLE_NORMAL, null, null));
        }
    }

    public static class Page {
        public final int pageIndex;
        public final List<Line> lines;
        public final int startCharOffset;
        public final int endCharOffset;

        public Page(int pageIndex, List<Line> lines, int startCharOffset, int endCharOffset) {
            this.pageIndex = pageIndex;
            this.lines = lines;
            this.startCharOffset = startCharOffset;
            this.endCharOffset = endCharOffset;
        }
    }

    private static final class WordPiece {
        String text;
        final int style;
        final String linkTarget;
        final String linkLabel;

        WordPiece(String text, int style, String linkTarget, String linkLabel) {
            this.text = text;
            this.style = style;
            this.linkTarget = linkTarget;
            this.linkLabel = linkLabel;
        }

        WordPiece copyWithText(String newText) {
            return new WordPiece(newText, style, linkTarget, linkLabel);
        }
    }

    private static final class WordToken {
        final List<WordPiece> pieces = new ArrayList<>();
        final StringBuilder plain = new StringBuilder();

        void addPiece(String txt, int style, String linkTarget, String linkLabel) {
            if (txt == null || txt.isEmpty()) return;
            if (!pieces.isEmpty()) {
                WordPiece prev = pieces.get(pieces.size() - 1);
                boolean sameLink = (prev.linkTarget == null ? linkTarget == null : prev.linkTarget.equals(linkTarget));
                if (prev.style == style && sameLink) {
                    prev.text = prev.text + txt;
                    plain.append(txt);
                    return;
                }
            }
            pieces.add(new WordPiece(txt, style, linkTarget, linkLabel));
            plain.append(txt);
        }

        boolean isEmpty() {
            return plain.length() == 0;
        }

        String getPlainText() {
            return plain.toString();
        }
    }

    private static final class StyleMeasurer implements TeXHyphenator.TextWidthMeasurer {
        private final Paint workPaint;
        private final Typeface baseTypeface;
        private final float baseTextSize;
        private int currentStyle = -1;
        private float currentScale = -1f;

        StyleMeasurer(Paint templatePaint) {
            this.workPaint = new Paint();
            this.baseTextSize = templatePaint.getTextSize();
            this.workPaint.setTextSize(this.baseTextSize);
            Typeface tf = null;
            try {
                tf = templatePaint.getTypeface();
            } catch (Throwable ignored) {}
            this.baseTypeface = tf;
        }

        void prepare(int style, float fontScale) {
            if (style == currentStyle && Math.abs(fontScale - currentScale) < 0.001f) {
                return;
            }
            currentStyle = style;
            currentScale = fontScale;
            workPaint.setTextSize(baseTextSize * fontScale);
            boolean bold = (style & STYLE_BOLD) != 0;
            boolean italic = (style & STYLE_ITALIC) != 0;
            boolean mono = (style & STYLE_MONO) != 0;
            try {
                if (mono) {
                    int tfStyle = (bold && italic) ? Typeface.BOLD_ITALIC : (bold ? Typeface.BOLD : (italic ? Typeface.ITALIC : Typeface.NORMAL));
                    workPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, tfStyle));
                } else if (baseTypeface != null) {
                    int wantStyle = baseTypeface.getStyle();
                    if (bold) wantStyle |= Typeface.BOLD;
                    if (italic) wantStyle |= Typeface.ITALIC;
                    if (wantStyle != baseTypeface.getStyle()) {
                        workPaint.setTypeface(Typeface.create(baseTypeface, wantStyle));
                    } else {
                        workPaint.setTypeface(baseTypeface);
                    }
                } else {
                    int tfStyle = (bold && italic) ? Typeface.BOLD_ITALIC : (bold ? Typeface.BOLD : (italic ? Typeface.ITALIC : Typeface.NORMAL));
                    workPaint.setTypeface(Typeface.defaultFromStyle(tfStyle));
                }
            } catch (Throwable ignored) {}
        }

        float measurePiece(WordPiece piece, float fontScale) {
            prepare(piece.style, fontScale);
            return workPaint.measureText(piece.text);
        }

        float measureWord(WordToken token, float fontScale) {
            float w = 0f;
            for (int i = 0; i < token.pieces.size(); i++) {
                w += measurePiece(token.pieces.get(i), fontScale);
            }
            return w;
        }

        float measureSpace(int baseStyle, float fontScale) {
            prepare(baseStyle, fontScale);
            return workPaint.measureText(" ");
        }

        float measureLineRuns(List<LineRun> runs, float fontScale) {
            float w = 0f;
            for (int i = 0; i < runs.size(); i++) {
                LineRun r = runs.get(i);
                prepare(r.style, fontScale);
                w += workPaint.measureText(r.text);
            }
            return w;
        }

        @Override
        public float measureWidth(String text) {
            return workPaint.measureText(text);
        }
    }

    private final TeXHyphenator hyphenator;

    public TextPaginator() {
        this.hyphenator = TeXHyphenator.getInstance();
    }

    /**
     * Разбивка текста на дискретные страницы под геометрию экрана с сохранением
     * форматирования книги (заголовки, выравнивание, стили, цитаты, ссылки, разрывы).
     */
    public List<Page> paginate(String fullText, int screenWidth, int screenHeight, Paint paint, TypographyConfig config) {
        List<Page> pages = new ArrayList<>();
        if (fullText == null || fullText.isEmpty()) {
            pages.add(new Page(0, new ArrayList<Line>(), 0, 0));
            return pages;
        }

        fullText = fullText.replace("\uFFFC", "").replace("\uFFFD", "").replace("\uFEFF", "");

        float availableWidth = Math.max(40f, screenWidth - config.getPaddingLeftPx() - config.getPaddingRightPx());
        float availableHeight = Math.max(40f, screenHeight - config.getPaddingTopPx() - config.getHeaderReservedHeightPx() - config.getPaddingBottomPx() - config.getFooterReservedHeightPx());

        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseLineHeight = Math.max(10f, (fm.bottom - fm.top) * config.getLineSpacingMultiplier());
        StyleMeasurer measurer = new StyleMeasurer(paint);

        String[] paragraphs = fullText.split("\n", -1);
        List<Line> currentLines = new ArrayList<>();
        float usedHeight = 0f;
        int pageIndex = 0;
        int globalCharOffset = 0;
        int pageStartCharOffset = 0;
        boolean lastWasEmptyLine = false;

        for (int pIdx = 0; pIdx < paragraphs.length; pIdx++) {
            String rawParagraph = paragraphs[pIdx];
            String trimmedRaw = rawParagraph.trim();

            if (EpubFormatting.PAGE_BREAK_TOKEN.equals(trimmedRaw)) {
                if (!currentLines.isEmpty()) {
                    pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                    currentLines.clear();
                    usedHeight = 0f;
                    pageStartCharOffset = globalCharOffset + rawParagraph.length() + 1;
                }
                globalCharOffset += rawParagraph.length() + 1;
                lastWasEmptyLine = false;
                continue;
            }

            if (trimmedRaw.isEmpty()) {
                if (currentLines.isEmpty() || lastWasEmptyLine) {
                    globalCharOffset += 1;
                    continue;
                }
                float emptyH = baseLineHeight * 0.65f;
                if (usedHeight + emptyH > availableHeight) {
                    pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                    currentLines.clear();
                    usedHeight = 0f;
                    pageStartCharOffset = globalCharOffset;
                    globalCharOffset += 1;
                    lastWasEmptyLine = false;
                    continue;
                }
                currentLines.add(new Line("", true, true, 0f, ALIGN_LEFT, 0.65f, 0f, 0f, false, 0f, 0f, null));
                usedHeight += emptyH;
                globalCharOffset += 1;
                lastWasEmptyLine = true;
                continue;
            }

            EpubFormatting.ParsedBlockHeader header = EpubFormatting.parseBlockHeader(rawParagraph);
            String bodyTrimmed = header.bodyText != null ? header.bodyText.trim() : "";

            if (EpubFormatting.PAGE_BREAK_TOKEN.equals(bodyTrimmed) || bodyTrimmed.isEmpty()) {
                if (EpubFormatting.PAGE_BREAK_TOKEN.equals(bodyTrimmed) && !currentLines.isEmpty()) {
                    pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                    currentLines.clear();
                    usedHeight = 0f;
                    pageStartCharOffset = globalCharOffset + rawParagraph.length() + 1;
                }
                globalCharOffset += rawParagraph.length() + 1;
                continue;
            }

            // Отдельная страница для иллюстраций и обложек
            if (bodyTrimmed.startsWith("[IMG:") && bodyTrimmed.endsWith("]")) {
                if (!currentLines.isEmpty()) {
                    pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                    currentLines.clear();
                    usedHeight = 0f;
                    pageStartCharOffset = globalCharOffset;
                }
                List<Line> imgLines = new ArrayList<>();
                imgLines.add(new Line(bodyTrimmed, false, false, 0f));
                pages.add(new Page(pageIndex++, imgLines, globalCharOffset, globalCharOffset + rawParagraph.length()));
                globalCharOffset += rawParagraph.length() + 1;
                pageStartCharOffset = globalCharOffset;
                lastWasEmptyLine = false;
                continue;
            }

            lastWasEmptyLine = false;

            char role = header.role;
            boolean isHeading = (role >= '1' && role <= '6');
            boolean isQuote = (role == 'Q');
            boolean isEpigraph = (role == 'E');
            boolean isNoIndent = (role == 'N') || isHeading || isQuote || isEpigraph || header.align == 'C' || header.align == 'R';

            int alignment = resolveAlignment(header, config);
            float fontScale = resolveFontScale(role);
            int baseBlockStyle = STYLE_NORMAL;
            if (header.blockBold || isHeading) baseBlockStyle |= STYLE_BOLD;
            if (header.blockItalic || isEpigraph) baseBlockStyle |= STYLE_ITALIC;

            float leftInsetPx = 0f;
            if (isQuote) {
                leftInsetPx = Math.min(availableWidth * 0.22f, Math.max(16f, baseLineHeight * 0.9f));
            } else if (isEpigraph) {
                leftInsetPx = Math.min(availableWidth * 0.28f, availableWidth * 0.25f);
            }

            float effectiveAvailableWidth = Math.max(40f, availableWidth - leftInsetPx);
            float firstLineIndent = isNoIndent ? 0f : Math.min(effectiveAvailableWidth * 0.35f, config.getEffectiveParagraphIndentPx());

            float extraTopPx = (isHeading || header.spaceTop || isQuote) ? baseLineHeight * (isHeading ? 0.42f : 0.28f) : 0f;
            float extraBottomPx = (isHeading || header.spaceBottom || isQuote) ? baseLineHeight * (isHeading ? 0.26f : 0.20f) : 0f;
            float thisLineHeight = baseLineHeight * fontScale;

            // Защита от "висячего заголовка" в самом низу страницы
            if (isHeading && !currentLines.isEmpty()) {
                float minRequiredForHeading = extraTopPx + thisLineHeight + extraBottomPx + baseLineHeight * 1.15f;
                if (usedHeight + minRequiredForHeading > availableHeight) {
                    pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                    currentLines.clear();
                    usedHeight = 0f;
                    pageStartCharOffset = globalCharOffset;
                }
            }

            List<WordToken> words = tokenizeStyledWords(header.bodyText, baseBlockStyle);
            if (words.isEmpty()) {
                globalCharOffset += rawParagraph.length() + 1;
                continue;
            }

            float spaceWidth = measurer.measureSpace(baseBlockStyle, fontScale);
            List<WordToken> lineWords = new ArrayList<>();
            boolean isFirstLineOfParagraph = true;
            float activeIndent = firstLineIndent;
            float currentLineWidth = activeIndent;

            for (int w = 0; w < words.size(); w++) {
                WordToken token = words.get(w);
                float wordWidth = measurer.measureWord(token, fontScale);

                if (lineWords.isEmpty()) {
                    if (currentLineWidth + wordWidth <= effectiveAvailableWidth) {
                        lineWords.add(token);
                        currentLineWidth += wordWidth;
                    } else {
                        WordToken splitFirst = null;
                        WordToken splitRest = null;
                        if (config.isHyphenationEnabled() && token.pieces.size() == 1) {
                            WordPiece single = token.pieces.get(0);
                            measurer.prepare(single.style, fontScale);
                            float spaceLeft = effectiveAvailableWidth - currentLineWidth;
                            String[] split = hyphenator.splitWordForWidth(single.text, spaceLeft, measurer);
                            if (split != null) {
                                splitFirst = new WordToken();
                                splitFirst.addPiece(split[0], single.style, single.linkTarget, single.linkLabel);
                                splitRest = new WordToken();
                                splitRest.addPiece(split[1], single.style, single.linkTarget, single.linkLabel);
                            }
                        }
                        if (splitFirst != null) {
                            float topGap = (isFirstLineOfParagraph && !currentLines.isEmpty()) ? extraTopPx : 0f;
                            if (!currentLines.isEmpty() && usedHeight + topGap + thisLineHeight > availableHeight) {
                                pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                                currentLines.clear();
                                usedHeight = 0f;
                                pageStartCharOffset = globalCharOffset;
                                topGap = 0f;
                            }
                            lineWords.add(splitFirst);
                            Line built = buildLine(lineWords, measurer, effectiveAvailableWidth, alignment, fontScale,
                                    isFirstLineOfParagraph, false, isFirstLineOfParagraph ? firstLineIndent : 0f,
                                    leftInsetPx, isQuote, topGap, 0f);
                            currentLines.add(built);
                            usedHeight += topGap + thisLineHeight;
                            isFirstLineOfParagraph = false;
                            lineWords.clear();
                            lineWords.add(splitRest);
                            currentLineWidth = measurer.measureWord(splitRest, fontScale);
                        } else {
                            // Длинное непереносимое слово (например, URL) помещаем на строку как есть
                            lineWords.add(token);
                            currentLineWidth += wordWidth;
                        }
                    }
                } else {
                    float candidateWidth = currentLineWidth + spaceWidth + wordWidth;
                    if (candidateWidth <= effectiveAvailableWidth) {
                        lineWords.add(token);
                        currentLineWidth = candidateWidth;
                    } else {
                        WordToken remainderWord = token;
                        if (config.isHyphenationEnabled() && token.pieces.size() == 1) {
                            float spaceLeft = effectiveAvailableWidth - currentLineWidth - spaceWidth;
                            if (spaceLeft > 0f) {
                                WordPiece single = token.pieces.get(0);
                                measurer.prepare(single.style, fontScale);
                                String[] split = hyphenator.splitWordForWidth(single.text, spaceLeft, measurer);
                                if (split != null) {
                                    WordToken headTok = new WordToken();
                                    headTok.addPiece(split[0], single.style, single.linkTarget, single.linkLabel);
                                    lineWords.add(headTok);
                                    remainderWord = new WordToken();
                                    remainderWord.addPiece(split[1], single.style, single.linkTarget, single.linkLabel);
                                }
                            }
                        }

                        float topGap = (isFirstLineOfParagraph && !currentLines.isEmpty()) ? extraTopPx : 0f;
                        if (!currentLines.isEmpty() && usedHeight + topGap + thisLineHeight > availableHeight) {
                            pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                            currentLines.clear();
                            usedHeight = 0f;
                            pageStartCharOffset = globalCharOffset;
                            topGap = 0f;
                        }

                        Line built = buildLine(lineWords, measurer, effectiveAvailableWidth, alignment, fontScale,
                                isFirstLineOfParagraph, false, isFirstLineOfParagraph ? firstLineIndent : 0f,
                                leftInsetPx, isQuote, topGap, 0f);
                        currentLines.add(built);
                        usedHeight += topGap + thisLineHeight;

                        isFirstLineOfParagraph = false;
                        lineWords.clear();
                        lineWords.add(remainderWord);
                        currentLineWidth = measurer.measureWord(remainderWord, fontScale);
                    }
                }
            }

            if (!lineWords.isEmpty()) {
                float topGap = (isFirstLineOfParagraph && !currentLines.isEmpty()) ? extraTopPx : 0f;
                if (!currentLines.isEmpty() && usedHeight + topGap + thisLineHeight > availableHeight) {
                    pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                    currentLines.clear();
                    usedHeight = 0f;
                    pageStartCharOffset = globalCharOffset;
                    topGap = 0f;
                }
                Line built = buildLine(lineWords, measurer, effectiveAvailableWidth, alignment, fontScale,
                        isFirstLineOfParagraph, true, isFirstLineOfParagraph ? firstLineIndent : 0f,
                        leftInsetPx, isQuote, topGap, extraBottomPx);
                currentLines.add(built);
                usedHeight += topGap + thisLineHeight + extraBottomPx;
            }

            globalCharOffset += rawParagraph.length() + 1;
        }

        if (!currentLines.isEmpty()) {
            pages.add(new Page(pageIndex, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
        }

        if (pages.isEmpty()) {
            pages.add(new Page(0, new ArrayList<Line>(), 0, 0));
        }

        return pages;
    }

    private int resolveAlignment(EpubFormatting.ParsedBlockHeader header, TypographyConfig config) {
        if (header.align == 'C') return ALIGN_CENTER;
        if (header.align == 'R') return ALIGN_RIGHT;
        if (header.align == 'L') return ALIGN_LEFT;
        if (header.role >= '1' && header.role <= '6') return ALIGN_CENTER;
        if (header.role == 'E') return ALIGN_RIGHT;
        if (header.role == 'N') return ALIGN_LEFT;
        return config.isJustifyEnabled() ? ALIGN_JUSTIFY : ALIGN_LEFT;
    }

    private float resolveFontScale(char role) {
        switch (role) {
            case '1': return 1.36f;
            case '2': return 1.24f;
            case '3': return 1.15f;
            case '4': return 1.08f;
            case '5': return 1.04f;
            case '6': return 1.00f;
            case 'Q': return 0.96f;
            case 'E': return 0.95f;
            case 'N': return 0.92f;
            default: return 1.00f;
        }
    }

    private Line buildLine(
            List<WordToken> lineWords,
            StyleMeasurer measurer,
            float effectiveAvailableWidth,
            int alignment,
            float fontScale,
            boolean isParagraphStart,
            boolean isLastLineOfParagraph,
            float indentPx,
            float leftInsetPx,
            boolean drawQuoteBar,
            float extraTopPx,
            float extraBottomPx
    ) {
        StringBuilder plainSb = new StringBuilder();
        List<LineRun> runs = new ArrayList<>();

        for (int i = 0; i < lineWords.size(); i++) {
            WordToken wt = lineWords.get(i);
            if (i > 0) {
                plainSb.append(' ');
                // Если соседние слова относятся к одной и той же ссылке, продлеваем ссылку и на пробел между ними
                String prevLink = !runs.isEmpty() ? runs.get(runs.size() - 1).linkTarget : null;
                String nextLink = !wt.pieces.isEmpty() ? wt.pieces.get(0).linkTarget : null;
                if (prevLink != null && prevLink.equals(nextLink)) {
                    LineRun lastRun = runs.get(runs.size() - 1);
                    runs.set(runs.size() - 1, new LineRun(lastRun.text + " ", lastRun.style, lastRun.linkTarget, lastRun.linkLabel));
                } else {
                    appendRunMerged(runs, " ", runs.isEmpty() ? STYLE_NORMAL : (runs.get(runs.size() - 1).style & ~(STYLE_UNDERLINE | STYLE_STRIKE)), null, null);
                }
            }
            for (int p = 0; p < wt.pieces.size(); p++) {
                WordPiece wp = wt.pieces.get(p);
                plainSb.append(wp.text);
                appendRunMerged(runs, wp.text, wp.style, wp.linkTarget, wp.linkLabel);
            }
        }

        String linePlain = plainSb.toString();
        float wordSpacing = 0f;
        if (alignment == ALIGN_JUSTIFY && !isLastLineOfParagraph && lineWords.size() > 1) {
            float targetWidth = effectiveAvailableWidth - indentPx;
            float actualWidth = measurer.measureLineRuns(runs, fontScale);
            float delta = targetWidth - actualWidth;
            int spaceCount = lineWords.size() - 1;
            if (delta > 0f && delta < targetWidth * 0.45f && spaceCount > 0) {
                wordSpacing = delta / spaceCount;
            }
        }

        return new Line(linePlain, isParagraphStart, isLastLineOfParagraph, wordSpacing,
                alignment, fontScale, indentPx, leftInsetPx, drawQuoteBar, extraTopPx, extraBottomPx, runs);
    }

    private void appendRunMerged(List<LineRun> runs, String text, int style, String linkTarget, String linkLabel) {
        if (text == null || text.isEmpty()) return;
        if (!runs.isEmpty()) {
            LineRun prev = runs.get(runs.size() - 1);
            boolean sameLink = (prev.linkTarget == null ? linkTarget == null : prev.linkTarget.equals(linkTarget));
            if (prev.style == style && sameLink) {
                runs.set(runs.size() - 1, new LineRun(prev.text + text, style, linkTarget, linkLabel != null ? linkLabel : prev.linkLabel));
                return;
            }
        }
        runs.add(new LineRun(text, style, linkTarget, linkLabel));
    }

    private List<WordToken> tokenizeStyledWords(String bodyText, int baseBlockStyle) {
        List<WordToken> tokens = new ArrayList<>();
        if (bodyText == null || bodyText.isEmpty()) return tokens;

        int curStyle = baseBlockStyle;
        String activeLinkTarget = null;
        String activeLinkLabel = null;
        WordToken currentToken = new WordToken();
        StringBuilder segBuf = new StringBuilder();

        int len = bodyText.length();
        for (int i = 0; i < len; i++) {
            char c = bodyText.charAt(i);
            if (c == EpubFormatting.BLK_START || c == EpubFormatting.BLK_END || c == EpubFormatting.PAGE_BREAK_CHAR) {
                continue;
            }
            if (c == EpubFormatting.LINK_START) {
                flushSegmentToToken(currentToken, segBuf, curStyle, activeLinkTarget, activeLinkLabel);
                int sepIdx = bodyText.indexOf(EpubFormatting.LINK_SEP, i + 1);
                if (sepIdx > i) {
                    activeLinkTarget = bodyText.substring(i + 1, sepIdx).trim();
                    int endIdx = bodyText.indexOf(EpubFormatting.LINK_END, sepIdx + 1);
                    if (endIdx > sepIdx) {
                        activeLinkLabel = EpubFormatting.stripFormattingTokens(bodyText.substring(sepIdx + 1, endIdx)).trim();
                    } else {
                        activeLinkLabel = null;
                    }
                    i = sepIdx;
                }
                continue;
            }
            if (c == EpubFormatting.LINK_END) {
                flushSegmentToToken(currentToken, segBuf, curStyle | STYLE_UNDERLINE, activeLinkTarget, activeLinkLabel);
                activeLinkTarget = null;
                activeLinkLabel = null;
                continue;
            }
            if (c >= EpubFormatting.BOLD_ON && c <= EpubFormatting.MONO_OFF) {
                int effectiveStyle = activeLinkTarget != null ? (curStyle | STYLE_UNDERLINE) : curStyle;
                flushSegmentToToken(currentToken, segBuf, effectiveStyle, activeLinkTarget, activeLinkLabel);
                switch (c) {
                    case EpubFormatting.BOLD_ON: curStyle |= STYLE_BOLD; break;
                    case EpubFormatting.BOLD_OFF: curStyle &= ~STYLE_BOLD; if ((baseBlockStyle & STYLE_BOLD) != 0) curStyle |= STYLE_BOLD; break;
                    case EpubFormatting.ITALIC_ON: curStyle |= STYLE_ITALIC; break;
                    case EpubFormatting.ITALIC_OFF: curStyle &= ~STYLE_ITALIC; if ((baseBlockStyle & STYLE_ITALIC) != 0) curStyle |= STYLE_ITALIC; break;
                    case EpubFormatting.UNDERLINE_ON: curStyle |= STYLE_UNDERLINE; break;
                    case EpubFormatting.UNDERLINE_OFF: curStyle &= ~STYLE_UNDERLINE; break;
                    case EpubFormatting.STRIKE_ON: curStyle |= STYLE_STRIKE; break;
                    case EpubFormatting.STRIKE_OFF: curStyle &= ~STYLE_STRIKE; break;
                    case EpubFormatting.MONO_ON: curStyle |= STYLE_MONO; break;
                    case EpubFormatting.MONO_OFF: curStyle &= ~STYLE_MONO; break;
                }
                continue;
            }

            if (Character.isWhitespace(c)) {
                int effectiveStyle = activeLinkTarget != null ? (curStyle | STYLE_UNDERLINE) : curStyle;
                flushSegmentToToken(currentToken, segBuf, effectiveStyle, activeLinkTarget, activeLinkLabel);
                if (!currentToken.isEmpty()) {
                    finalizeAndAddToken(tokens, currentToken);
                    currentToken = new WordToken();
                }
            } else {
                segBuf.append(c);
            }
        }

        int effectiveStyle = activeLinkTarget != null ? (curStyle | STYLE_UNDERLINE) : curStyle;
        flushSegmentToToken(currentToken, segBuf, effectiveStyle, activeLinkTarget, activeLinkLabel);
        if (!currentToken.isEmpty()) {
            finalizeAndAddToken(tokens, currentToken);
        }

        return tokens;
    }

    private void flushSegmentToToken(WordToken token, StringBuilder buf, int style, String linkTarget, String linkLabel) {
        if (buf.length() == 0) return;
        token.addPiece(buf.toString(), style, linkTarget, linkLabel);
        buf.setLength(0);
    }

    private void finalizeAndAddToken(List<WordToken> out, WordToken rawToken) {
        // Автоматически распознаём голые URL (https://...) вне явных <a href> и делаем их кликабельными
        boolean hasLinkAlready = false;
        for (int i = 0; i < rawToken.pieces.size(); i++) {
            if (rawToken.pieces.get(i).linkTarget != null) {
                hasLinkAlready = true;
                break;
            }
        }
        String plain = rawToken.getPlainText();
        if (!hasLinkAlready && (plain.contains("http://") || plain.contains("https://"))) {
            Matcher m = BARE_URL_PATTERN.matcher(plain);
            if (m.find()) {
                WordToken enriched = new WordToken();
                int pos = 0;
                int style = !rawToken.pieces.isEmpty() ? rawToken.pieces.get(0).style : STYLE_NORMAL;
                m.reset();
                while (m.find()) {
                    if (m.start() > pos) {
                        enriched.addPiece(plain.substring(pos, m.start()), style, null, null);
                    }
                    String url = m.group(1);
                    String trailing = "";
                    while (url.length() > 8) {
                        char last = url.charAt(url.length() - 1);
                        if (last == '.' || last == ',' || last == ';' || last == ':' || last == '!' || last == '?' || last == ')' || last == ']' || last == '"' || last == '\'' || last == '\u00BB') {
                            trailing = last + trailing;
                            url = url.substring(0, url.length() - 1);
                        } else {
                            break;
                        }
                    }
                    enriched.addPiece(url, style | STYLE_UNDERLINE, url, url);
                    if (!trailing.isEmpty()) {
                        enriched.addPiece(trailing, style, null, null);
                    }
                    pos = m.end();
                }
                if (pos < plain.length()) {
                    enriched.addPiece(plain.substring(pos), style, null, null);
                }
                out.add(enriched);
                return;
            }
        }
        out.add(rawToken);
    }
}
