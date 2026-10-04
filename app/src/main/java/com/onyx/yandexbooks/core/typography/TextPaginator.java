package com.onyx.yandexbooks.core.typography;

import android.graphics.Paint;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Математический пагинатор страниц для E-Ink экрана.
 * Выполняет точную разбивку текста на страницы с учетом переносов слов,
 * красной строки и выравнивания по ширине.
 */
public class TextPaginator {

    public static class Line {
        public final String text;
        public final boolean isParagraphStart;
        public final boolean isLastLineOfParagraph;
        public final float wordSpacing;

        public Line(String text, boolean isParagraphStart, boolean isLastLineOfParagraph, float wordSpacing) {
            this.text = text;
            this.isParagraphStart = isParagraphStart;
            this.isLastLineOfParagraph = isLastLineOfParagraph;
            this.wordSpacing = wordSpacing;
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

    private final TeXHyphenator hyphenator;

    public TextPaginator() {
        this.hyphenator = TeXHyphenator.getInstance();
    }

    /**
     * Разбивка текста на дискретные страницы под геометрию экрана.
     */
    public List<Page> paginate(String fullText, int screenWidth, int screenHeight, Paint paint, TypographyConfig config) {
        List<Page> pages = new ArrayList<>();
        if (fullText == null || fullText.isEmpty()) {
            return pages;
        }

        float availableWidth = screenWidth - config.getPaddingLeftPx() - config.getPaddingRightPx();
        float availableHeight = screenHeight - config.getPaddingTopPx() - config.getPaddingBottomPx();

        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineHeight = (fm.bottom - fm.top) * config.getLineSpacingMultiplier();
        int maxLinesPerPage = Math.max(1, (int) (availableHeight / lineHeight));

        String[] paragraphs = fullText.split("\n");
        List<Line> currentLines = new ArrayList<>();
        int pageIndex = 0;
        int globalCharOffset = 0;
        int pageStartCharOffset = 0;

        TeXHyphenator.TextWidthMeasurer measurer = new TeXHyphenator.TextWidthMeasurer() {
            @Override
            public float measureWidth(String text) {
                return paint.measureText(text);
            }
        };

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                // Пустая строка между абзацами
                if (currentLines.size() + 1 > maxLinesPerPage) {
                    pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                    currentLines.clear();
                    pageStartCharOffset = globalCharOffset;
                }
                currentLines.add(new Line("", true, true, 0));
                globalCharOffset += 1;
                continue;
            }

            String[] words = trimmed.split("\\s+");
            StringBuilder currentLineText = new StringBuilder();
            boolean isFirstLineOfParagraph = true;

            for (int w = 0; w < words.length; w++) {
                String word = words[w];
                float lineIndent = isFirstLineOfParagraph ? config.getParagraphIndentPx() : 0;
                float currentLineWidth = paint.measureText(currentLineText.toString()) + lineIndent;

                String candidate = currentLineText.length() == 0 ? word : currentLineText + " " + word;
                float candidateWidth = paint.measureText(candidate) + lineIndent;

                if (candidateWidth <= availableWidth) {
                    currentLineText = new StringBuilder(candidate);
                } else {
                    // Пробуем перенос слова (Hyphenation)
                    if (config.isHyphenationEnabled()) {
                        float spaceLeft = availableWidth - currentLineWidth - paint.measureText(" ");
                        String[] split = hyphenator.splitWordForWidth(word, spaceLeft, measurer);
                        if (split != null) {
                            if (currentLineText.length() > 0) {
                                currentLineText.append(" ");
                            }
                            currentLineText.append(split[0]);
                            word = split[1]; // остаток слова переходит на следующую строку
                        }
                    }

                    // Добавляем заполненную строку на страницу
                    if (currentLines.size() + 1 > maxLinesPerPage) {
                        pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                        currentLines.clear();
                        pageStartCharOffset = globalCharOffset;
                    }

                    float spacing = calculateJustifySpacing(currentLineText.toString(), availableWidth - lineIndent, paint);
                    currentLines.add(new Line(currentLineText.toString(), isFirstLineOfParagraph, false, spacing));
                    isFirstLineOfParagraph = false;
                    currentLineText = new StringBuilder(word);
                }
            }

            // Добавляем последнюю строку абзаца
            if (currentLineText.length() > 0) {
                if (currentLines.size() + 1 > maxLinesPerPage) {
                    pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                    currentLines.clear();
                    pageStartCharOffset = globalCharOffset;
                }
                // Последняя строка абзаца не выравнивается по ширине
                currentLines.add(new Line(currentLineText.toString(), isFirstLineOfParagraph, true, 0));
            }

            globalCharOffset += paragraph.length() + 1;
        }

        // Финальная страница
        if (!currentLines.isEmpty()) {
            pages.add(new Page(pageIndex, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
        }

        return pages;
    }

    private float calculateJustifySpacing(String lineText, float targetWidth, Paint paint) {
        String[] words = lineText.split(" ");
        if (words.length <= 1) {
            return 0;
        }
        float actualTextWidth = paint.measureText(lineText);
        float delta = targetWidth - actualTextWidth;
        if (delta > 0 && delta < targetWidth * 0.4f) { // Защита от неестественно огромных пробелов
            return delta / (words.length - 1);
        }
        return 0;
    }
}
