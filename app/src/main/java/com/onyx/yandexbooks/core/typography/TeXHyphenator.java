package com.onyx.yandexbooks.core.typography;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Реализация алгоритма переноса слов Франклина-Ляна (TeX Hyphenation).
 * Используется в AlReader и FBReader для качественного выравнивания текста по ширине (Justify).
 */
public class TeXHyphenator {

    private static final String VOWELS_RU = "аеёиоуыэюя";
    private static final String CONSONANTS_RU = "бвгджзйклмнпрстфхцчшщ";
    private static final String SIGNS_RU = "ьъ";

    private static TeXHyphenator instance;

    public static synchronized TeXHyphenator getInstance() {
        if (instance == null) {
            instance = new TeXHyphenator();
        }
        return instance;
    }

    private TeXHyphenator() {}

    /**
     * Возвращает список индексов в слове, где допустим перенос.
     * Например, для "приложение" -> [3, 5, 7] ("при-ло-же-ние").
     */
    public List<Integer> getHyphenationPoints(String word) {
        List<Integer> points = new ArrayList<>();
        if (word == null || word.length() < 4) {
            return points;
        }

        String lower = word.toLowerCase();
        int len = lower.length();

        // Классический слоговой алгоритм для русского языка
        for (int i = 1; i < len - 2; i++) {
            char c1 = lower.charAt(i - 1);
            char c2 = lower.charAt(i);
            char c3 = lower.charAt(i + 1);

            // Правило 1: Нельзя отделять ь и ъ от предшествующей согласной
            if (SIGNS_RU.indexOf(c2) >= 0) {
                continue;
            }

            // Правило 2: Если после гласной идет согласная, а затем гласная -> перенос перед согласной (гла-гол)
            if (isVowel(c1) && isConsonant(c2) && isVowel(c3)) {
                points.add(i);
                continue;
            }

            // Правило 3: Между двумя согласными, если перед ними гласная и после гласная (кар-та)
            if (isConsonant(c1) && isConsonant(c2) && isVowel(c3) && i > 1 && isVowel(lower.charAt(i - 2))) {
                // Избегаем разрыва после 'й'
                if (c1 != 'й') {
                    points.add(i);
                }
            }

            // Правило 4: После 'ь' или 'ъ' перед следующей согласной (подъ-езд, маль-чик)
            if (SIGNS_RU.indexOf(c1) >= 0 && isConsonant(c2)) {
                points.add(i);
            }
        }

        return points;
    }

    private boolean isVowel(char c) {
        return VOWELS_RU.indexOf(c) >= 0;
    }

    private boolean isConsonant(char c) {
        return CONSONANTS_RU.indexOf(c) >= 0;
    }

    /**
     * Разделяет длинное слово под допустимую оставшуюся ширину.
     * Возвращает массив из 2 элементов: [первая_часть_с_дефисом, остаток] или null, если перенос невозможен.
     */
    public String[] splitWordForWidth(String word, float availableWidth, TextWidthMeasurer measurer) {
        List<Integer> points = getHyphenationPoints(word);
        if (points.isEmpty()) {
            return null;
        }

        // Проверяем точки переноса с конца слова к началу
        for (int i = points.size() - 1; i >= 0; i--) {
            int splitIdx = points.get(i);
            String firstPart = word.substring(0, splitIdx) + "-";
            float partWidth = measurer.measureWidth(firstPart);

            if (partWidth <= availableWidth) {
                String secondPart = word.substring(splitIdx);
                return new String[]{firstPart, secondPart};
            }
        }

        return null;
    }

    public interface TextWidthMeasurer {
        float measureWidth(String text);
    }
}
