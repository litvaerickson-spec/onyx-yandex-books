package com.onyx.yandexbooks.core.epub;

import android.text.Html;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Надежный встроенный парсер EPUB архивов для Android (KitKat 4.4+).
 * Извлекает подлинное оглавление (NCX / NAV), объединяет связанные файлы spine в единые
 * непрерывные главы без обрывов текста и полностью очищает артефакты [OBJ] и избыточные отступы.
 */
public class EpubParser {

    private static final String TAG = "EpubParser";

    public static class ChapterData {
        public String id;
        public String title;
        public String textContent;
    }

    public static class TocItem {
        public String title;
        public String href; // нормализованный путь к файлу без якоря (#...)

        public TocItem(String title, String href) {
            this.title = title;
            this.href = href;
        }
    }

    public static List<ChapterData> parseEpub(File epubFile) {
        List<ChapterData> result = new ArrayList<>();
        if (epubFile == null || !epubFile.exists() || epubFile.length() == 0) {
            Log.e(TAG, "EPUB file does not exist or is empty");
            return result;
        }

        ZipFile zip = null;
        try {
            zip = new ZipFile(epubFile);

            // 1. Поиск пути к OPF манифесту в META-INF/container.xml
            String opfPath = findOpfPath(zip);
            String opfBaseDir = "";
            if (opfPath != null && opfPath.contains("/")) {
                opfBaseDir = opfPath.substring(0, opfPath.lastIndexOf('/') + 1);
            }

            // 2. Парсинг spine и manifest из OPF
            List<String> chapterHrefs = new ArrayList<>();
            Map<String, String> manifestHrefMap = new HashMap<>();
            String ncxPathFromOpf = null;
            String navPathFromOpf = null;

            if (opfPath != null) {
                chapterHrefs = parseOpfSpine(zip, opfPath, opfBaseDir, manifestHrefMap);
                ncxPathFromOpf = findNcxPathFromOpf(zip, opfPath, opfBaseDir);
                navPathFromOpf = findNavPathFromOpf(zip, opfPath, opfBaseDir);
            }

            // Fallback: если spine не удалось прочесть, собираем все xhtml/html файлы
            if (chapterHrefs.isEmpty()) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                List<String> allHtml = new ArrayList<>();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    String lower = name.toLowerCase();
                    if ((lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm"))
                            && !lower.contains("cover") && !lower.contains("toc") && !lower.contains("nav")) {
                        allHtml.add(name);
                    }
                }
                Collections.sort(allHtml);
                chapterHrefs.addAll(allHtml);
            }

            // 3. Извлечение реального оглавления (NCX или EPUB 3 NAV)
            List<TocItem> tocItems = new ArrayList<>();
            if (ncxPathFromOpf != null) {
                tocItems = parseNcx(zip, ncxPathFromOpf);
            }
            if (tocItems.isEmpty() && navPathFromOpf != null) {
                tocItems = parseNav(zip, navPathFromOpf);
            }
            if (tocItems.isEmpty()) {
                // Поиск любого .ncx файла в архиве
                String anyNcx = findAnyEntryByExtension(zip, ".ncx");
                if (anyNcx != null) {
                    tocItems = parseNcx(zip, anyNcx);
                }
            }

            // 4. Группировка файлов spine по оглавлению TOC
            if (!tocItems.isEmpty() && !chapterHrefs.isEmpty()) {
                result = assembleChaptersFromToc(zip, chapterHrefs, tocItems);
            }

            // 5. Fallback: если по оглавлению собрать не удалось, читаем файлы по порядку
            if (result.isEmpty() && !chapterHrefs.isEmpty()) {
                result = assembleChaptersFallback(zip, chapterHrefs);
            }

        } catch (Throwable e) {
            Log.e(TAG, "Error parsing EPUB: " + epubFile.getAbsolutePath(), e);
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Exception ignored) {}
            }
        }

        return result;
    }

    /**
     * Сборка глав на основе подлинного оглавления (TOC):
     * все промежуточные части и фрагменты между главами объединяются в единый непрерывный текст.
     */
    private static List<ChapterData> assembleChaptersFromToc(ZipFile zip, List<String> spineHrefs, List<TocItem> tocItems) {
        List<ChapterData> chapters = new ArrayList<>();

        // Сопоставление каждого элемента TOC с индексом в spine
        class MatchedToc {
            int spineIndex;
            TocItem item;
            MatchedToc(int spineIndex, TocItem item) {
                this.spineIndex = spineIndex;
                this.item = item;
            }
        }

        List<MatchedToc> matched = new ArrayList<>();
        int lastFoundIndex = -1;

        for (TocItem ti : tocItems) {
            int idx = findSpineIndex(spineHrefs, ti.href);
            if (idx >= 0) {
                // Пропускаем дубликаты на один и тот же файл, если название совпадает
                if (idx != lastFoundIndex) {
                    matched.add(new MatchedToc(idx, ti));
                    lastFoundIndex = idx;
                }
            }
        }

        if (matched.isEmpty()) {
            return chapters;
        }

        // 1. Фронт-материалы до первой главы TOC (титульный лист, выходные данные)
        int firstSpineIdx = matched.get(0).spineIndex;
        if (firstSpineIdx > 0) {
            StringBuilder frontSb = new StringBuilder();
            for (int i = 0; i < firstSpineIdx; i++) {
                String text = readEntryText(zip, spineHrefs.get(i));
                if (text != null && !text.trim().isEmpty()) {
                    if (frontSb.length() > 0) frontSb.append("\n\n");
                    frontSb.append(text.trim());
                }
            }
            if (frontSb.length() > 30) {
                ChapterData cd = new ChapterData();
                cd.id = "ch_0";
                cd.title = "Начало книги";
                cd.textContent = frontSb.toString();
                chapters.add(cd);
            }
        }

        // 2. Объединение файлов spine по главам TOC
        for (int m = 0; m < matched.size(); m++) {
            MatchedToc cur = matched.get(m);
            int startIdx = cur.spineIndex;
            int endIdx = (m + 1 < matched.size()) ? matched.get(m + 1).spineIndex : spineHrefs.size();
            if (endIdx < startIdx) {
                endIdx = startIdx + 1;
            }

            StringBuilder chSb = new StringBuilder();
            for (int s = startIdx; s < endIdx; s++) {
                if (s >= 0 && s < spineHrefs.size()) {
                    String partText = readEntryText(zip, spineHrefs.get(s));
                    if (partText != null && !partText.trim().isEmpty()) {
                        if (chSb.length() > 0) {
                            chSb.append("\n\n");
                        }
                        chSb.append(partText.trim());
                    }
                }
            }

            String fullText = chSb.toString().trim();
            if (fullText.length() > 10) {
                ChapterData cd = new ChapterData();
                cd.id = "ch_" + (chapters.size() + 1);
                String title = cur.item.title;
                if (title == null || title.trim().isEmpty()) {
                    title = "Глава " + (chapters.size() + 1);
                }
                cd.title = title.trim();
                cd.textContent = fullText;
                chapters.add(cd);
            }
        }

        return chapters;
    }

    private static List<ChapterData> assembleChaptersFallback(ZipFile zip, List<String> chapterHrefs) {
        List<ChapterData> result = new ArrayList<>();
        int chapterIndex = 1;
        for (String href : chapterHrefs) {
            String cleanText = readEntryText(zip, href);
            if (cleanText != null && cleanText.trim().length() > 20) {
                ZipEntry entry = findZipEntry(zip, href);
                String rawHtml = "";
                if (entry != null) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        rawHtml = readStreamToString(is);
                    } catch (Exception ignored) {}
                }
                ChapterData ch = new ChapterData();
                ch.id = "ch_" + chapterIndex;
                ch.title = extractTitle(rawHtml, "Глава " + chapterIndex);
                ch.textContent = cleanText;
                result.add(ch);
                chapterIndex++;
            }
        }
        return result;
    }

    private static String readEntryText(ZipFile zip, String href) {
        ZipEntry entry = findZipEntry(zip, href);
        if (entry == null) return null;
        try (InputStream is = zip.getInputStream(entry)) {
            String rawHtml = readStreamToString(is);
            return cleanHtmlText(rawHtml);
        } catch (Exception e) {
            return null;
        }
    }

    private static int findSpineIndex(List<String> spineHrefs, String targetHref) {
        if (targetHref == null || targetHref.isEmpty()) return -1;
        String cleanTarget = targetHref.trim();
        if (cleanTarget.contains("#")) {
            cleanTarget = cleanTarget.substring(0, cleanTarget.indexOf('#'));
        }

        // 1. Точное совпадение
        for (int i = 0; i < spineHrefs.size(); i++) {
            String s = spineHrefs.get(i);
            if (s.equalsIgnoreCase(cleanTarget) || s.endsWith("/" + cleanTarget) || cleanTarget.endsWith("/" + s)) {
                return i;
            }
        }

        // 2. Сопоставление только по имени файла
        String targetFilename = cleanTarget.contains("/") ? cleanTarget.substring(cleanTarget.lastIndexOf('/') + 1) : cleanTarget;
        for (int i = 0; i < spineHrefs.size(); i++) {
            String s = spineHrefs.get(i);
            String sFilename = s.contains("/") ? s.substring(s.lastIndexOf('/') + 1) : s;
            if (sFilename.equalsIgnoreCase(targetFilename)) {
                return i;
            }
        }

        return -1;
    }

    private static String findOpfPath(ZipFile zip) {
        ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
        if (containerEntry == null) {
            containerEntry = zip.getEntry("meta-inf/container.xml");
        }
        if (containerEntry != null) {
            try (InputStream is = zip.getInputStream(containerEntry)) {
                String xml = readStreamToString(is);
                Pattern pattern = Pattern.compile("full-path\\s*=\\s*[\"']([^\"']+\\.opf)[\"']", Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(xml);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            } catch (Exception ignored) {}
        }

        return findAnyEntryByExtension(zip, ".opf");
    }

    private static List<String> parseOpfSpine(ZipFile zip, String opfPath, String baseDir, Map<String, String> outManifestMap) {
        List<String> result = new ArrayList<>();
        ZipEntry opfEntry = zip.getEntry(opfPath);
        if (opfEntry == null) return result;

        try (InputStream is = zip.getInputStream(opfEntry)) {
            String opfXml = readStreamToString(is);

            // Сопоставление id -> href из <manifest>
            Pattern itemPattern = Pattern.compile("<item\\s+[^>]*?id\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher m = itemPattern.matcher(opfXml);
            while (m.find()) {
                outManifestMap.put(m.group(1), m.group(2));
            }
            Pattern itemPatternRev = Pattern.compile("<item\\s+[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?id\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher mRev = itemPatternRev.matcher(opfXml);
            while (mRev.find()) {
                outManifestMap.put(mRev.group(2), mRev.group(1));
            }

            // Порядок чтения из <spine>
            Pattern itemrefPattern = Pattern.compile("<itemref\\s+[^>]*?idref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher mSpine = itemrefPattern.matcher(opfXml);
            while (mSpine.find()) {
                String idref = mSpine.group(1);
                String href = outManifestMap.get(idref);
                if (href != null) {
                    if (href.contains("#")) {
                        href = href.substring(0, href.indexOf('#'));
                    }
                    String fullPath = baseDir.isEmpty() ? href : (baseDir + href);
                    if (!result.contains(fullPath)) {
                        result.add(fullPath);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error reading OPF spine", e);
        }
        return result;
    }

    private static String findNcxPathFromOpf(ZipFile zip, String opfPath, String baseDir) {
        ZipEntry opfEntry = zip.getEntry(opfPath);
        if (opfEntry == null) return null;
        try (InputStream is = zip.getInputStream(opfEntry)) {
            String opfXml = readStreamToString(is);
            Pattern p = Pattern.compile("<item\\s+[^>]*?href\\s*=\\s*[\"']([^\"']+\\.ncx)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(opfXml);
            if (m.find()) {
                String href = m.group(1);
                return baseDir.isEmpty() ? href : (baseDir + href);
            }
            Pattern p2 = Pattern.compile("<item\\s+[^>]*?media-type\\s*=\\s*[\"']application/x-dtbncx\\+xml[\"'][^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher m2 = p2.matcher(opfXml);
            if (m2.find()) {
                String href = m2.group(1);
                return baseDir.isEmpty() ? href : (baseDir + href);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String findNavPathFromOpf(ZipFile zip, String opfPath, String baseDir) {
        ZipEntry opfEntry = zip.getEntry(opfPath);
        if (opfEntry == null) return null;
        try (InputStream is = zip.getInputStream(opfEntry)) {
            String opfXml = readStreamToString(is);
            Pattern p = Pattern.compile("<item\\s+[^>]*?properties\\s*=\\s*[\"'][^\"']*?\\bnav\\b[^\"']*?[\"'][^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(opfXml);
            if (m.find()) {
                String href = m.group(1);
                return baseDir.isEmpty() ? href : (baseDir + href);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static List<TocItem> parseNcx(ZipFile zip, String ncxPath) {
        List<TocItem> result = new ArrayList<>();
        ZipEntry entry = findZipEntry(zip, ncxPath);
        if (entry == null) return result;

        String ncxBaseDir = "";
        if (ncxPath.contains("/")) {
            ncxBaseDir = ncxPath.substring(0, ncxPath.lastIndexOf('/') + 1);
        }

        try (InputStream is = zip.getInputStream(entry)) {
            String xml = readStreamToString(is);
            Pattern pattern = Pattern.compile("<navLabel>\\s*<text>(.*?)</text>\\s*</navLabel>\\s*<content\\s+[^>]*?src=[\"']([^\"']+)[\"']", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(xml);

            while (matcher.find()) {
                String rawTitle = matcher.group(1);
                String cleanTitle = cleanHtmlText(rawTitle).replaceAll("\\s+", " ").trim();
                String src = matcher.group(2).trim();
                if (src.contains("#")) {
                    src = src.substring(0, src.indexOf('#'));
                }
                String fullSrc = ncxBaseDir.isEmpty() ? src : (ncxBaseDir + src);
                if (!cleanTitle.isEmpty() && !src.isEmpty()) {
                    result.add(new TocItem(cleanTitle, fullSrc));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse NCX TOC", e);
        }
        return result;
    }

    private static List<TocItem> parseNav(ZipFile zip, String navPath) {
        List<TocItem> result = new ArrayList<>();
        ZipEntry entry = findZipEntry(zip, navPath);
        if (entry == null) return result;

        String navBaseDir = "";
        if (navPath.contains("/")) {
            navBaseDir = navPath.substring(0, navPath.lastIndexOf('/') + 1);
        }

        try (InputStream is = zip.getInputStream(entry)) {
            String html = readStreamToString(is);
            Pattern pattern = Pattern.compile("<a\\s+[^>]*?href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(html);

            while (matcher.find()) {
                String src = matcher.group(1).trim();
                String rawTitle = matcher.group(2);
                String cleanTitle = cleanHtmlText(rawTitle).replaceAll("\\s+", " ").trim();
                if (src.contains("#")) {
                    src = src.substring(0, src.indexOf('#'));
                }
                String fullSrc = navBaseDir.isEmpty() ? src : (navBaseDir + src);
                if (!cleanTitle.isEmpty() && !src.isEmpty()) {
                    result.add(new TocItem(cleanTitle, fullSrc));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse NAV TOC", e);
        }
        return result;
    }

    private static String findAnyEntryByExtension(ZipFile zip, String ext) {
        String lowerExt = ext.toLowerCase();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e.getName().toLowerCase().endsWith(lowerExt)) {
                return e.getName();
            }
        }
        return null;
    }

    private static ZipEntry findZipEntry(ZipFile zip, String name) {
        if (name == null) return null;
        ZipEntry entry = zip.getEntry(name);
        if (entry != null) return entry;

        String lower = name.toLowerCase();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String eName = e.getName();
            if (eName.equalsIgnoreCase(name) || eName.toLowerCase().endsWith(lower)) {
                return e;
            }
        }
        return null;
    }

    private static String extractTitle(String html, String defaultTitle) {
        if (html == null || html.isEmpty()) return defaultTitle;
        try {
            // 1. Поиск <h1>
            Pattern p1 = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher m1 = p1.matcher(html);
            if (m1.find()) {
                String clean = cleanHtmlText(m1.group(1)).replaceAll("\\s+", " ").trim();
                if (!clean.isEmpty() && clean.length() < 120) return clean;
            }

            // 2. Поиск <h2>
            Pattern p2 = Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher m2 = p2.matcher(html);
            if (m2.find()) {
                String clean = cleanHtmlText(m2.group(1)).replaceAll("\\s+", " ").trim();
                if (!clean.isEmpty() && clean.length() < 120) return clean;
            }

            // 3. Поиск <title>
            Pattern pt = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher mt = pt.matcher(html);
            if (mt.find()) {
                String clean = cleanHtmlText(mt.group(1)).replaceAll("\\s+", " ").trim();
                if (!clean.isEmpty() && clean.length() < 120) return clean;
            }
        } catch (Throwable ignored) {}
        return defaultTitle;
    }

    /**
     * Высокопроизводительный очиститель HTML для E-Ink ридеров:
     * - Исключает появление символов [OBJ] (\uFFFC) и битых глифов (\uFFFD, \uFEFF)
     * - Формирует нормальное книжное расстояние между абзацами (следующая строка с красной строкой)
     * - Сохраняет пропуск одной строки только для явных смысловых разделителей автора (<hr/>, звездочки)
     */
    public static String cleanHtmlText(String html) {
        if (html == null || html.isEmpty()) return "";
        try {
            // 1. Удаление <head>, <style>, <script>
            String text = html.replaceAll("(?is)<(script|style|head).*?>.*?</\\1>", "");

            // 2. Маркировка явных смысловых разделителей секций
            text = text.replaceAll("(?i)<hr\\s*/?>", "\n___SECTION_BREAK___\n");
            text = text.replaceAll("(?i)<p[^>]*>(\\s*|&nbsp;|<br\\s*/?>|\\*\\s*\\*\\s*\\*)</p>", "\n___SECTION_BREAK___\n");

            // 3. Замена тегов переноса строк и закрытия структурных блоков на единичный \n
            text = text.replaceAll("(?i)<br\\s*/?>", "\n");
            text = text.replaceAll("(?i)</?(p|div|h[1-6]|li|blockquote|tr)[^>]*>", "\n");

            // 4. Удаление всех остальных тегов (включая <img>, <span>, <i>, <b> и др.)
            text = text.replaceAll("<[^>]+>", "");

            // 5. Тотальное удаление некорректных символов [OBJ], BOM, мягких переносов и непечатных кодов
            text = text.replace("\uFFFC", ""); // Object Replacement Character ([OBJ])
            text = text.replace("\uFFFD", ""); // Unicode Replacement Character
            text = text.replace("\uFEFF", ""); // Byte Order Mark
            text = text.replace("\u200B", ""); // Zero-width space
            text = text.replace("\u200C", ""); // Zero-width non-joiner
            text = text.replace("\u200D", ""); // Zero-width joiner
            text = text.replace("\u00AD", ""); // Soft hyphen

            // 6. Декодирование типографических сущностей HTML
            text = text.replace("&nbsp;", " ");
            text = text.replace("&laquo;", "«");
            text = text.replace("&raquo;", "»");
            text = text.replace("&mdash;", "—");
            text = text.replace("&ndash;", "–");
            text = text.replace("&hellip;", "…");
            text = text.replace("&amp;", "&");
            text = text.replace("&lt;", "<");
            text = text.replace("&gt;", ">");
            text = text.replace("&quot;", "\"");
            text = text.replace("&apos;", "'");

            // Числовые сущности &#...;
            if (text.contains("&#")) {
                text = decodeNumericEntities(text);
            }

            // 7. Построчная сборка текста: нормальные абзацы идут друг за другом,
            // пустая строка допускается максимум одна и только для авторских разделителей
            String[] lines = text.split("\n");
            StringBuilder result = new StringBuilder(text.length());
            boolean allowEmptyLine = false;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                if ("___SECTION_BREAK___".equals(trimmed)) {
                    if (result.length() > 0 && allowEmptyLine) {
                        result.append("\n");
                        allowEmptyLine = false;
                    }
                    continue;
                }

                if (result.length() > 0) {
                    result.append("\n");
                }
                result.append(trimmed);
                allowEmptyLine = true;
            }

            return result.toString().trim();
        } catch (Throwable t) {
            return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        }
    }

    private static String decodeNumericEntities(String str) {
        Pattern pattern = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");
        Matcher matcher = pattern.matcher(str);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String val = matcher.group(1);
            try {
                int code;
                if (val.startsWith("x") || val.startsWith("X")) {
                    code = Integer.parseInt(val.substring(1), 16);
                } else {
                    code = Integer.parseInt(val);
                }
                // Исключаем вставку [OBJ] и спецсимволов замены
                if (code == 0xFFFC || code == 0xFFFD || code == 0xFEFF || code < 32 && code != 10 && code != 9) {
                    matcher.appendReplacement(sb, "");
                } else {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(code))));
                }
            } catch (Exception ignored) {
                matcher.appendReplacement(sb, "");
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String readStreamToString(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }
}
