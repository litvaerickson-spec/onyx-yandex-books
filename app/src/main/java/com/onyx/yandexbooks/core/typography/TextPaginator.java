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
            pages.add(new Page(0, new ArrayList<Line>(), 0, 0));
            return pages;
        }

        fullText = fullText.replace("\uFFFC", "").replace("\uFFFD", "").replace("\uFEFF", "");

        float availableWidth = screenWidth - config.getPaddingLeftPx() - config.getPaddingRightPx();
        float availableHeight = screenHeight - config.getPaddingTopPx() - config.getHeaderReservedHeightPx() - config.getPaddingBottomPx() - config.getFooterReservedHeightPx();

        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineHeight = (fm.bottom - fm.top) * config.getLineSpacingMultiplier();
        int maxLinesPerPage = Math.max(1, (int) (availableHeight / lineHeight));

        String[] paragraphs = fullText.split("\n");
        List<Line> currentLines = new ArrayList<>();
        int pageIndex = 0;
        int globalCharOffset = 0;
        int pageStartCharOffset = 0;
        float spaceWidth = paint.measureText(" ");
        boolean lastWasEmptyLine = false;

        TeXHyphenator.TextWidthMeasurer measurer = new TeXHyphenator.TextWidthMeasurer() {
            @Override
            public float measureWidth(String text) {
                return paint.measureText(text);
            }
        };

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                // Пустая строка (разделитель секций автора)
                if (currentLines.isEmpty() || lastWasEmptyLine) {
                    globalCharOffset += 1;
                    continue; // Не добавляем пустую строку в начале страницы или две подряд
                }
                if (currentLines.size() + 1 > maxLinesPerPage) {
                    pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                    currentLines.clear();
                    pageStartCharOffset = globalCharOffset;
                    globalCharOffset += 1;
                    lastWasEmptyLine = false;
                    continue;
                }
                currentLines.add(new Line("", true, true, 0));
                globalCharOffset += 1;
                lastWasEmptyLine = true;
                continue;
            }

            // Отдельная страница для иллюстраций и обложек
            if (trimmed.startsWith("[IMG:") && trimmed.endsWith("]")) {
                if (!currentLines.isEmpty()) {
                    pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                    currentLines.clear();
                    pageStartCharOffset = globalCharOffset;
                }
                List<Line> imgLines = new ArrayList<>();
                imgLines.add(new Line(trimmed, false, false, 0));
                pages.add(new Page(pageIndex++, imgLines, globalCharOffset, globalCharOffset + trimmed.length()));
                globalCharOffset += trimmed.length() + 1;
                pageStartCharOffset = globalCharOffset;
                lastWasEmptyLine = false;
                continue;
            }

            lastWasEmptyLine = false;

            String[] words = trimmed.split("\\s+");
            StringBuilder currentLineText = new StringBuilder();
            boolean isFirstLineOfParagraph = true;
            float lineIndent = config.getParagraphIndentPx();
            float currentLineWidth = lineIndent;

            for (int w = 0; w < words.length; w++) {
                String word = words[w];
                float wordWidth = paint.measureText(word);

                if (currentLineText.length() == 0) {
                    if (currentLineWidth + wordWidth <= availableWidth) {
                        currentLineText.append(word);
                        currentLineWidth += wordWidth;
                    } else {
                        // Длинное слово не помещается даже на пустой строке
                        if (config.isHyphenationEnabled()) {
                            float spaceLeft = availableWidth - currentLineWidth;
                            String[] split = hyphenator.splitWordForWidth(word, spaceLeft, measurer);
                            if (split != null) {
                                currentLineText.append(split[0]);
                                word = split[1];
                                wordWidth = paint.measureText(word);
                            }
                        }
                        if (currentLineText.length() == 0) {
                            currentLineText.append(word);
                            currentLineWidth += wordWidth;
                        } else {
                            if (currentLines.size() + 1 > maxLinesPerPage) {
                                pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                                currentLines.clear();
                                pageStartCharOffset = globalCharOffset;
                            }
                            float currentLineIndent = isFirstLineOfParagraph ? lineIndent : 0;
                            float spacing = calculateJustifySpacing(currentLineText.toString(), availableWidth - currentLineIndent, paint);
                            currentLines.add(new Line(currentLineText.toString(), isFirstLineOfParagraph, false, spacing));
                            isFirstLineOfParagraph = false;
                            currentLineText.setLength(0);
                            currentLineText.append(word);
                            currentLineWidth = wordWidth;
                        }
                    }
                } else {
                    float candidateWidth = currentLineWidth + spaceWidth + wordWidth;
                    if (candidateWidth <= availableWidth) {
                        currentLineText.append(' ').append(word);
                        currentLineWidth = candidateWidth;
                    } else {
                        // Слово не помещается в текущую строку, пробуем перенос
                        if (config.isHyphenationEnabled()) {
                            float spaceLeft = availableWidth - currentLineWidth - spaceWidth;
                            if (spaceLeft > 0) {
                                String[] split = hyphenator.splitWordForWidth(word, spaceLeft, measurer);
                                if (split != null) {
                                    currentLineText.append(' ').append(split[0]);
                                    word = split[1];
                                    wordWidth = paint.measureText(word);
                                }
                            }
                        }

                        // Сохраняем заполненную строку на страницу
                        if (currentLines.size() + 1 > maxLinesPerPage) {
                            pages.add(new Page(pageIndex++, new ArrayList<>(currentLines), pageStartCharOffset, globalCharOffset));
                            currentLines.clear();
                            pageStartCharOffset = globalCharOffset;
                        }

                        float currentLineIndent = isFirstLineOfParagraph ? lineIndent : 0;
                        float spacing = calculateJustifySpacing(currentLineText.toString(), availableWidth - currentLineIndent, paint);
                        currentLines.add(new Line(currentLineText.toString(), isFirstLineOfParagraph, false, spacing));
                        isFirstLineOfParagraph = false;
                        currentLineText.setLength(0);
                        currentLineText.append(word);
                        currentLineWidth = wordWidth;
                    }
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

        if (pages.isEmpty()) {
            pages.add(new Page(0, new ArrayList<Line>(), 0, 0));
        }

        return pages;
    }

    private float calculateJustifySpacing(String lineText, float targetWidth, Paint paint) {
        int spaceCount = 0;
        for (int i = 0; i < lineText.length(); i++) {
            if (lineText.charAt(i) == ' ') spaceCount++;
        }
        if (spaceCount == 0) {
            return 0;
        }
        float actualTextWidth = paint.measureText(lineText);
        float delta = targetWidth - actualTextWidth;
        if (delta > 0 && delta < targetWidth * 0.35f) { // Защита от неестественно огромных пробелов
            return delta / spaceCount;
        }
        return 0;
    }
}
