package com.onyx.yandexbooks.core.epub;

import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Высокопроизводительный парсер EPUB архивов для E-Ink ридеров Onyx Boox (Android KitKat 4.4+).
 *
 * Ключевые архитектурные принципы:
 * 1. Сохранение целостности глав из <spine>: файлы книги не кромсаются искусственно,
 *    что исключает появление обрывков HTML тегов (<h2, id="...") и пустых частей.
 * 2. Иерархическое дерево оглавления (TocNode): извлекает многоуровневую структуру
 *    из NCX и NAV, нормализует числовые главы ("1" -> "Глава 1"), связывает разделы
 *    со spine-главами и точными символьными смещениями charOffset.
 * 3. Тотальная очистка E-Ink текста: удаляет любые обрывки тегов, атрибутов,
 *    символы [OBJ] (\uFFFC), BOM (\uFEFF) и нормализует межстрочные интервалы.
 */
public class EpubParser {

    private static final String TAG = "EpubParser";

    public static class ChapterData {
        public String id;
        public String title;
        public String textContent;
        public String fileHref;
        public int spineIndex;
    }

    public static class TocNode implements Serializable {
        public String id;
        public String title;
        public String rawHref;     // Полная ссылка (например: text/part1.xhtml#part4)
        public String fileHref;    // Путь к файлу без якоря (например: text/part1.xhtml)
        public String anchor;      // Якорь (например: part4) или null
        public int level;          // 0 = корневой раздел, 1 = подраздел, 2 = под-подраздел...
        public int spineIndex;     // Индекс spine-главы в списке глав (0, 1, 2...)
        public int charOffset;     // Точное символьное смещение в тексте главы (0 если начало)
        public int pageNumber = 1; // Номер страницы в книге
        public boolean hasChildren;
        public boolean isExpanded;
        public List<TocNode> children = new ArrayList<>();

        public TocNode() {}

        public TocNode(String id, String title, String rawHref, int level) {
            this.id = id;
            this.title = normalizeTitle(title);
            this.rawHref = (rawHref != null) ? rawHref.trim() : "";
            if (this.rawHref.contains("#")) {
                int hashIdx = this.rawHref.indexOf('#');
                this.fileHref = this.rawHref.substring(0, hashIdx).trim();
                this.anchor = this.rawHref.substring(hashIdx + 1).trim();
            } else {
                this.fileHref = this.rawHref;
                this.anchor = null;
            }
            this.level = level;
            this.isExpanded = (level == 0); // Раскрыт верхний уровень по умолчанию
        }

        public static String normalizeTitle(String raw) {
            if (raw == null) return "";
            String t = raw.trim();
            if (t.matches("^\\d+$")) {
                return "Глава " + t;
            }
            if (t.matches("(?i)^[ivxlcdm]+$") && t.length() <= 8) {
                return "Глава " + t.toUpperCase();
            }
            return t;
        }
    }

    public static class ParseResult {
        public List<ChapterData> chapters = new ArrayList<>();
        public List<TocNode> tocTree = new ArrayList<>();
        public String coverImagePath = null;
        public Map<String, String> footnotes = new HashMap<>();
        public Map<String, String> epubFonts = new HashMap<>();
    }

    public static List<ChapterData> parseEpub(File epubFile) {
        ParseResult result = parseEpubFull(epubFile, null);
        return result.chapters;
    }

    public static ParseResult parseEpubFull(File epubFile) {
        return parseEpubFull(epubFile, null);
    }

    public static ParseResult parseEpubFull(File epubFile, File destFontsDir) {
        ParseResult result = new ParseResult();
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
                result.coverImagePath = findCoverImageFromOpf(zip, opfPath, opfBaseDir);
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

            // 3. Извлечение реального иерархического оглавления (NCX или EPUB 3 NAV)
            List<TocNode> tocTree = new ArrayList<>();
            if (ncxPathFromOpf != null) {
                tocTree = parseNcxTree(zip, ncxPathFromOpf);
            }
            if (tocTree.isEmpty() && navPathFromOpf != null) {
                tocTree = parseNavTree(zip, navPathFromOpf);
            }
            if (tocTree.isEmpty()) {
                String anyNcx = findAnyEntryByExtension(zip, ".ncx");
                if (anyNcx != null) {
                    tocTree = parseNcxTree(zip, anyNcx);
                }
            }

            // Быстрый поиск названия по fileHref из TOC
            Map<String, String> tocFileTitleMap = new HashMap<>();
            populateTocTitles(tocTree, tocFileTitleMap);

            // 4. Загрузка глав непосредственно из spine с сохранением HTML/CSS разметки и ссылок
            Map<Integer, String> rawHtmlCache = new HashMap<>();
            Map<String, String> cssFileCache = new HashMap<>();
            List<ChapterData> chapters = new ArrayList<>();

            if (chapterHrefs.size() == 1) {
                // Если файл книги всего 1 большой HTML: проверяем деление по h1/h2
                List<ChapterData> byHeadings = splitSingleFileByHeadings(zip, chapterHrefs.get(0));
                if (byHeadings != null && byHeadings.size() > 1) {
                    chapters = byHeadings;
                }
            }

            if (chapters.isEmpty()) {
                int chIndex = 1;
                for (int s = 0; s < chapterHrefs.size(); s++) {
                    String href = chapterHrefs.get(s);
                    String rawHtml = readEntryRawHtml(zip, href);
                    if (rawHtml == null) rawHtml = "";
                    rawHtmlCache.put(s, rawHtml);

                    EpubFormatting.CssSheet cssSheet = EpubFormatting.buildCssSheet(zip, rawHtml, href, cssFileCache);
                    String richText = EpubFormatting.extractRichHtmlText(rawHtml, href, cssSheet);

                    ChapterData cd = new ChapterData();
                    cd.id = "ch_" + chIndex;
                    cd.fileHref = href;
                    cd.spineIndex = s;
                    cd.textContent = richText;

                    // Название главы (приоритет полного пути href над коротким именем файла для многотомников)
                    String normHref = resolveZipPath("", href);
                    String title = tocFileTitleMap.get(normHref);
                    if (title == null) {
                        title = tocFileTitleMap.get(href);
                    }
                    if (title == null) {
                        String fname = href.contains("/") ? href.substring(href.lastIndexOf('/') + 1) : href;
                        title = tocFileTitleMap.get("__short__:" + fname.toLowerCase());
                    }
                    if (title == null || title.isEmpty()) {
                        title = extractTitle(rawHtml, "Глава " + chIndex);
                    }
                    cd.title = TocNode.normalizeTitle(title);

                    chapters.add(cd);
                    chIndex++;
                }
            }

            // 4.1 Гарантированное отображение обложки на первой странице книги (Глава 0)
            if (result.coverImagePath != null && !result.coverImagePath.isEmpty() && !chapters.isEmpty()) {
                ChapterData firstCh = chapters.get(0);
                boolean firstHasImage = (firstCh.textContent != null && firstCh.textContent.contains("[IMG:"))
                        || (firstCh.fileHref != null && firstCh.fileHref.toLowerCase().contains("cover"));
                if (!firstHasImage) {
                    ChapterData coverCh = new ChapterData();
                    coverCh.id = "ch_0_cover";
                    coverCh.title = "Обложка";
                    coverCh.textContent = "[IMG:" + result.coverImagePath + "]";
                    coverCh.fileHref = result.coverImagePath;
                    coverCh.spineIndex = 0;
                    chapters.add(0, coverCh);
                    for (int i = 0; i < chapters.size(); i++) {
                        chapters.get(i).spineIndex = i;
                    }
                }
            }

            // 5. Извлечение интерактивных сносок для всплывающего просмотра без потери страницы
            extractFootnotes(zip, chapters, chapterHrefs, rawHtmlCache, result.footnotes);

            // 5.1 Извлечение встроенных в EPUB шрифтов (опционально включаются в настройках)
            if (destFontsDir != null) {
                result.epubFonts = EpubFormatting.extractEpubFonts(zip, destFontsDir);
            }

            // 6. Если оглавление TOC было пустым, генерируем базовое оглавление из глав
            if (tocTree.isEmpty()) {
                for (int i = 0; i < chapters.size(); i++) {
                    ChapterData cd = chapters.get(i);
                    TocNode node = new TocNode("toc_" + (i + 1), cd.title, cd.fileHref, 0);
                    node.spineIndex = i;
                    node.charOffset = 0;
                    tocTree.add(node);
                }
            } else {
                // Связываем дерево TOC со сформированными главами и символьными смещениями charOffset
                resolveNodeOffsets(tocTree, chapters, chapterHrefs, rawHtmlCache);
            }
            rawHtmlCache.clear();

            result.chapters = chapters;
            result.tocTree = tocTree;

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

    private static void populateTocTitles(List<TocNode> nodes, Map<String, String> outMap) {
        populateTocTitlesWithParent(nodes, null, outMap);
    }

    private static void populateTocTitlesWithParent(List<TocNode> nodes, String parentTitle, Map<String, String> outMap) {
        if (nodes == null) return;
        for (TocNode n : nodes) {
            if (n.title != null && !n.title.isEmpty() && parentTitle != null && !parentTitle.isEmpty()) {
                // В многотомниках ("Всадники Перна" 4-в-1) дополняем "Глава X" названием тома/книги,
                // чтобы не путать Главу 5 Книги 1 и Главу 5 Книги 4
                String lower = n.title.toLowerCase();
                if ((lower.startsWith("глава ") || lower.startsWith("часть ")
                        || lower.startsWith("chapter ") || lower.startsWith("part ")
                        || lower.equals("пролог") || lower.equals("эпилог")
                        || lower.matches("^\\d+.*"))
                        && !n.title.contains("•") && !n.title.contains(parentTitle)) {
                    String shortParent = parentTitle.length() > 28 ? parentTitle.substring(0, 28).trim() + "…" : parentTitle.trim();
                    n.title = shortParent + " • " + n.title;
                }
            }
            if (n.fileHref != null && !n.fileHref.isEmpty() && n.title != null && !n.title.isEmpty()) {
                String normHref = resolveZipPath("", n.fileHref);
                if (!normHref.isEmpty() && !outMap.containsKey(normHref)) {
                    outMap.put(normHref, n.title);
                }
                if (!outMap.containsKey(n.fileHref)) {
                    outMap.put(n.fileHref, n.title);
                }
                String fname = n.fileHref.contains("/") ? n.fileHref.substring(n.fileHref.lastIndexOf('/') + 1) : n.fileHref;
                String shortKey = "__short__:" + fname.toLowerCase();
                if (!outMap.containsKey(shortKey)) {
                    outMap.put(shortKey, n.title);
                } else {
                    // Коллизия одноименных файлов в разных папках многотомника (Book1/ch05.xhtml vs Book4/ch05.xhtml) —
                    // отключаем неточный поиск по короткому имени для этого файла!
                    outMap.put(shortKey, "");
                }
            }
            if (n.children != null && !n.children.isEmpty()) {
                populateTocTitlesWithParent(n.children, n.title, outMap);
            }
        }
    }

    private static void resolveNodeOffsets(List<TocNode> nodes, List<ChapterData> chapters, List<String> spineHrefs, Map<Integer, String> rawHtmlCache) {
        if (nodes == null || chapters == null || chapters.isEmpty()) return;

        for (TocNode node : nodes) {
            int matchedChapter = -1;

            // 1. Поиск точного соответствия полного пути с главами (приоритет над коротким именем для многотомников)
            if (node.fileHref != null && !node.fileHref.isEmpty()) {
                for (int i = 0; i < chapters.size(); i++) {
                    ChapterData cd = chapters.get(i);
                    if (cd.fileHref != null && hrefsExactOrSuffixMatch(cd.fileHref, node.fileHref)) {
                        matchedChapter = i;
                        break;
                    }
                }
                if (matchedChapter < 0) {
                    for (int i = 0; i < chapters.size(); i++) {
                        ChapterData cd = chapters.get(i);
                        if (cd.fileHref != null && hrefsMatch(cd.fileHref, node.fileHref)) {
                            matchedChapter = i;
                            break;
                        }
                    }
                }
            }

            // 2. Поиск по rawHref
            if (matchedChapter < 0 && node.rawHref != null && !node.rawHref.isEmpty()) {
                for (int i = 0; i < chapters.size(); i++) {
                    ChapterData cd = chapters.get(i);
                    if (cd.fileHref != null && hrefsMatch(cd.fileHref, node.rawHref)) {
                        matchedChapter = i;
                        break;
                    }
                }
            }

            // 3. Fallback: поиск по оригинальным spineHrefs
            if (matchedChapter < 0) {
                int spineIdx = findSpineIndex(spineHrefs, node.fileHref);
                if (spineIdx >= 0) {
                    String targetHref = spineHrefs.get(spineIdx);
                    for (int i = 0; i < chapters.size(); i++) {
                        if (hrefsMatch(chapters.get(i).fileHref, targetHref)) {
                            matchedChapter = i;
                            break;
                        }
                    }
                }
            }

            if (matchedChapter < 0) {
                matchedChapter = 0;
            }

            node.spineIndex = matchedChapter;

            // Расчет точного символьного смещения якоря charOffset
            if (node.anchor != null && !node.anchor.isEmpty()) {
                int spineFileIndex = findSpineIndex(spineHrefs, node.fileHref);
                String rawHtml = (spineFileIndex >= 0) ? rawHtmlCache.get(spineFileIndex) : null;
                if (rawHtml != null) {
                    int anchorTagStart = findAnchorOffset(rawHtml, node.anchor);
                    if (anchorTagStart > 0 && anchorTagStart < rawHtml.length()) {
                        String leadingText = EpubFormatting.extractRichHtmlText(rawHtml.substring(0, anchorTagStart), node.fileHref, null);
                        node.charOffset = leadingText.length();
                    } else {
                        node.charOffset = 0;
                    }
                } else {
                    node.charOffset = 0;
                }
            } else {
                node.charOffset = 0;
            }

            node.hasChildren = (node.children != null && !node.children.isEmpty());

            if (node.children != null && !node.children.isEmpty()) {
                resolveNodeOffsets(node.children, chapters, spineHrefs, rawHtmlCache);
            }
        }
    }

    private static boolean hrefsExactOrSuffixMatch(String a, String b) {
        if (a == null || b == null) return false;
        String ca = resolveZipPath("", a);
        String cb = resolveZipPath("", b);
        if (ca.isEmpty() || cb.isEmpty()) return false;
        return ca.equalsIgnoreCase(cb) || ca.toLowerCase().endsWith("/" + cb.toLowerCase()) || cb.toLowerCase().endsWith("/" + ca.toLowerCase());
    }

    private static boolean hrefsMatch(String a, String b) {
        if (a == null || b == null) return false;
        if (hrefsExactOrSuffixMatch(a, b)) return true;
        String ca = a.contains("#") ? a.substring(0, a.indexOf('#')).trim() : a.trim();
        String cb = b.contains("#") ? b.substring(0, b.indexOf('#')).trim() : b.trim();
        // Если оба пути содержат разные подпапки (например Book1/ch05.xhtml и Book4/ch05.xhtml),
        // запрещаем ложное склеивание только по имени файла!
        if (ca.contains("/") && cb.contains("/")) {
            String dirA = ca.substring(0, ca.lastIndexOf('/'));
            String dirB = cb.substring(0, cb.lastIndexOf('/'));
            if (!dirA.equalsIgnoreCase(dirB) && !dirA.toLowerCase().endsWith("/" + dirB.toLowerCase()) && !dirB.toLowerCase().endsWith("/" + dirA.toLowerCase())) {
                return false;
            }
        }
        String fa = ca.contains("/") ? ca.substring(ca.lastIndexOf('/') + 1) : ca;
        String fb = cb.contains("/") ? cb.substring(cb.lastIndexOf('/') + 1) : cb;
        return fa.equalsIgnoreCase(fb);
    }

    /**
     * Разделение единого большого XHTML-файла по тегам h1/h2 для книг без детального spine.
     */
    private static List<ChapterData> splitSingleFileByHeadings(ZipFile zip, String href) {
        List<ChapterData> list = new ArrayList<>();
        String rawHtml = readEntryRawHtml(zip, href);
        if (rawHtml == null || rawHtml.length() < 200) return list;

        Pattern p = Pattern.compile("<(h[1-2])[^>]*>(.*?)</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(rawHtml);

        class HeadingPos {
            int start;
            String title;
            HeadingPos(int start, String title) {
                this.start = start;
                this.title = title;
            }
        }

        List<HeadingPos> headings = new ArrayList<>();
        while (m.find()) {
            String titleText = cleanHtmlText(m.group(2)).replaceAll("\\s+", " ").trim();
            if (!titleText.isEmpty() && titleText.length() < 120) {
                headings.add(new HeadingPos(m.start(), titleText));
            }
        }

        if (headings.size() >= 2) {
            EpubFormatting.CssSheet cssSheet = EpubFormatting.buildCssSheet(zip, rawHtml, href, null);
            if (headings.get(0).start > 100) {
                String front = EpubFormatting.extractRichHtmlText(rawHtml.substring(0, headings.get(0).start), href, cssSheet);
                if (front.length() > 30) {
                    ChapterData cd0 = new ChapterData();
                    cd0.id = "ch_0";
                    cd0.fileHref = href;
                    cd0.title = "Начало книги";
                    cd0.textContent = front;
                    list.add(cd0);
                }
            }

            for (int i = 0; i < headings.size(); i++) {
                int from = headings.get(i).start;
                int to = (i + 1 < headings.size()) ? headings.get(i + 1).start : rawHtml.length();
                String chHtml = rawHtml.substring(from, to);
                String formatted = EpubFormatting.extractRichHtmlText(chHtml, href, cssSheet);
                if (formatted.length() > 10) {
                    ChapterData cd = new ChapterData();
                    cd.id = "ch_" + (list.size() + 1);
                    cd.fileHref = href;
                    cd.title = TocNode.normalizeTitle(headings.get(i).title);
                    cd.textContent = formatted;
                    list.add(cd);
                }
            }
        }
        return list;
    }

    /**
     * Поиск начала открывающего тега <...>, содержащего id="anchor" или name="anchor".
     * Гарантирует возврат индекса '<', исключая разрыв тега наполовину.
     */
    public static int findAnchorOffset(String html, String anchor) {
        if (html == null || anchor == null || anchor.trim().isEmpty()) return -1;
        String cleanAnchor = anchor.trim();
        if (cleanAnchor.startsWith("#")) {
            cleanAnchor = cleanAnchor.substring(1).trim();
        }
        if (cleanAnchor.isEmpty()) return -1;

        // 1. Поиск открывающего тега с id="anchor" или name="anchor"
        Pattern p = Pattern.compile("<[^>]+?\\b(?:id|name)\\s*=\\s*[\"']" + Pattern.quote(cleanAnchor) + "[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) {
            return m.start(); // Начало тега '<'
        }

        // 2. Поиск без кавычек
        Pattern p2 = Pattern.compile("<[^>]+?\\b(?:id|name)\\s*=\\s*" + Pattern.quote(cleanAnchor) + "([\\s>][^>]*)?>", Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(html);
        if (m2.find()) {
            return m2.start();
        }

        // 3. Fallback: поиск строки в кавычках и предшествующего символа '<'
        int idx = html.indexOf("\"" + cleanAnchor + "\"");
        if (idx < 0) idx = html.indexOf("'" + cleanAnchor + "'");
        if (idx >= 0) {
            int tagStart = html.lastIndexOf('<', idx);
            return (tagStart >= 0) ? tagStart : idx;
        }

        return -1;
    }

    private static String readEntryRawHtml(ZipFile zip, String href) {
        ZipEntry entry = findZipEntry(zip, href);
        if (entry == null) return null;
        InputStream is = null;
        try {
            is = zip.getInputStream(entry);
            return readStreamToString(is);
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static int findSpineIndex(List<String> spineHrefs, String targetHref) {
        if (targetHref == null || targetHref.isEmpty()) return -1;
        String cleanTarget = resolveZipPath("", targetHref);
        if (cleanTarget.isEmpty()) {
            cleanTarget = targetHref.contains("#") ? targetHref.substring(0, targetHref.indexOf('#')).trim() : targetHref.trim();
        }

        // 1. Точное или суффиксное совпадение нормализованных путей
        for (int i = 0; i < spineHrefs.size(); i++) {
            String s = resolveZipPath("", spineHrefs.get(i));
            if (s.equalsIgnoreCase(cleanTarget) || s.toLowerCase().endsWith("/" + cleanTarget.toLowerCase()) || cleanTarget.toLowerCase().endsWith("/" + s.toLowerCase())) {
                return i;
            }
        }

        // 2. Сопоставление по имени файла только при отсутствии конфликтующих подпапок (многотомники)
        String targetFilename = cleanTarget.contains("/") ? cleanTarget.substring(cleanTarget.lastIndexOf('/') + 1) : cleanTarget;
        String targetDir = cleanTarget.contains("/") ? cleanTarget.substring(0, cleanTarget.lastIndexOf('/')) : "";
        for (int i = 0; i < spineHrefs.size(); i++) {
            String s = resolveZipPath("", spineHrefs.get(i));
            String sFilename = s.contains("/") ? s.substring(s.lastIndexOf('/') + 1) : s;
            String sDir = s.contains("/") ? s.substring(0, s.lastIndexOf('/')) : "";
            if (!targetDir.isEmpty() && !sDir.isEmpty()
                    && !targetDir.equalsIgnoreCase(sDir)
                    && !targetDir.toLowerCase().endsWith("/" + sDir.toLowerCase())
                    && !sDir.toLowerCase().endsWith("/" + targetDir.toLowerCase())) {
                continue;
            }
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
            InputStream is = null;
            try {
                is = zip.getInputStream(containerEntry);
                String xml = readStreamToString(is);
                Pattern pattern = Pattern.compile("full-path\\s*=\\s*[\"']([^\"']+\\.opf)[\"']", Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(xml);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            } catch (Exception ignored) {
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Exception ignored) {}
                }
            }
        }

        return findAnyEntryByExtension(zip, ".opf");
    }

    private static List<String> parseOpfSpine(ZipFile zip, String opfPath, String baseDir, Map<String, String> outManifestMap) {
        List<String> result = new ArrayList<>();
        ZipEntry opfEntry = zip.getEntry(opfPath);
        if (opfEntry == null) return result;

        InputStream is = null;
        try {
            is = zip.getInputStream(opfEntry);
            String opfXml = readStreamToString(is);

            // Сопоставление id -> href из <manifest>
            Pattern itemPattern = Pattern.compile("<item\\s+[^>]*?id\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher m = itemPattern.matcher(opfXml);
            while (m.find()) {
                outManifestMap.put(m.group(1), m.group(2));
            }
            Pattern itemPatternRev = Pattern.compile("<item\\s+[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?id\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher mRev = itemPatternRev.matcher(opfXml);
            while (mRev.find()) {
                outManifestMap.put(mRev.group(2), mRev.group(1));
            }

            // Порядок чтения из <spine>
            Pattern itemrefPattern = Pattern.compile("<itemref\\s+[^>]*?idref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
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
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
        return result;
    }

    private static String findNcxPathFromOpf(ZipFile zip, String opfPath, String baseDir) {
        ZipEntry opfEntry = zip.getEntry(opfPath);
        if (opfEntry == null) return null;
        InputStream is = null;
        try {
            is = zip.getInputStream(opfEntry);
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
        } catch (Exception ignored) {
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static String findNavPathFromOpf(ZipFile zip, String opfPath, String baseDir) {
        ZipEntry opfEntry = zip.getEntry(opfPath);
        if (opfEntry == null) return null;
        InputStream is = null;
        try {
            is = zip.getInputStream(opfEntry);
            String opfXml = readStreamToString(is);
            Pattern p = Pattern.compile("<item\\s+[^>]*?properties\\s*=\\s*[\"'][^\"']*?\\bnav\\b[^\"']*?[\"'][^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(opfXml);
            if (m.find()) {
                String href = m.group(1);
                return baseDir.isEmpty() ? href : (baseDir + href);
            }
        } catch (Exception ignored) {
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    public static String findCoverImageFromOpf(ZipFile zip, String opfPath, String baseDir) {
        ZipEntry opfEntry = zip.getEntry(opfPath);
        if (opfEntry == null) return null;
        InputStream is = null;
        try {
            is = zip.getInputStream(opfEntry);
            String opfXml = readStreamToString(is);
            // 1. EPUB 2: <meta name="cover" content="id"/>
            Pattern metaPattern = Pattern.compile("<meta\\s+[^>]*?name\\s*=\\s*[\"']cover[\"'][^>]*?content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher metaM = metaPattern.matcher(opfXml);
            if (!metaM.find()) {
                metaPattern = Pattern.compile("<meta\\s+[^>]*?content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?name\\s*=\\s*[\"']cover[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
                metaM = metaPattern.matcher(opfXml);
            }
            if (metaM.find()) {
                String coverId = metaM.group(1);
                Pattern itemPattern = Pattern.compile("<item\\s+[^>]*?id\\s*=\\s*[\"']" + Pattern.quote(coverId) + "[\"'][^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
                Matcher m = itemPattern.matcher(opfXml);
                if (m.find()) {
                    return resolveZipPath(baseDir, m.group(1));
                }
            }

            // 2. EPUB 3: <item ... properties="...cover-image..." href="..." ...>
            Pattern propPattern = Pattern.compile("<item\\s+[^>]*?properties\\s*=\\s*[\"'][^\"']*cover-image[^\"']*[\"'][^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher propM = propPattern.matcher(opfXml);
            if (propM.find()) {
                return resolveZipPath(baseDir, propM.group(1));
            }

            // 3. Fallback: <item id="cover" ... href="...jpg|png..." ...>
            Pattern idPattern = Pattern.compile("<item\\s+[^>]*?id\\s*=\\s*[\"'](?:cover|cover-image|book-cover)[\"'][^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher idM = idPattern.matcher(opfXml);
            if (idM.find()) {
                return resolveZipPath(baseDir, idM.group(1));
            }
        } catch (Exception ignored) {
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Построение подлинного иерархического дерева оглавления из NCX (DAISY).
     */
    private static List<TocNode> parseNcxTree(ZipFile zip, String ncxPath) {
        List<TocNode> roots = new ArrayList<>();
        ZipEntry entry = findZipEntry(zip, ncxPath);
        if (entry == null) return roots;

        String ncxBaseDir = "";
        if (ncxPath.contains("/")) {
            ncxBaseDir = ncxPath.substring(0, ncxPath.lastIndexOf('/') + 1);
        }

        InputStream is = null;
        try {
            is = zip.getInputStream(entry);
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(is, "UTF-8");

            Stack<TocNode> stack = new Stack<>();
            int eventType = parser.getEventType();
            String currentTag = "";
            StringBuilder textBuffer = new StringBuilder();

            while (eventType != XmlPullParser.END_DOCUMENT) {
                String name = parser.getName();
                if (eventType == XmlPullParser.START_TAG) {
                    currentTag = name;
                    if ("navPoint".equalsIgnoreCase(name)) {
                        String id = parser.getAttributeValue(null, "id");
                        int level = stack.size();
                        TocNode node = new TocNode();
                        node.id = (id != null) ? id : ("np_" + (roots.size() + 1));
                        node.level = level;
                        node.isExpanded = (level == 0);
                        stack.push(node);
                    } else if ("content".equalsIgnoreCase(name)) {
                        if (!stack.isEmpty()) {
                            String src = parser.getAttributeValue(null, "src");
                            if (src != null) {
                                try {
                                    src = URLDecoder.decode(src, "UTF-8");
                                } catch (Exception ignored) {}
                                String fullSrc = ncxBaseDir.isEmpty() ? src : (ncxBaseDir + src);
                                TocNode top = stack.peek();
                                top.rawHref = fullSrc;
                                if (fullSrc.contains("#")) {
                                    int hash = fullSrc.indexOf('#');
                                    top.fileHref = fullSrc.substring(0, hash).trim();
                                    top.anchor = fullSrc.substring(hash + 1).trim();
                                } else {
                                    top.fileHref = fullSrc.trim();
                                    top.anchor = null;
                                }
                            }
                        }
                    } else if ("text".equalsIgnoreCase(name)) {
                        textBuffer.setLength(0);
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    if ("text".equalsIgnoreCase(currentTag)) {
                        textBuffer.append(parser.getText());
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if ("text".equalsIgnoreCase(name)) {
                        if (!stack.isEmpty()) {
                            String clean = cleanHtmlText(textBuffer.toString()).replaceAll("\\s+", " ").trim();
                            stack.peek().title = TocNode.normalizeTitle(clean);
                        }
                    } else if ("navPoint".equalsIgnoreCase(name)) {
                        if (!stack.isEmpty()) {
                            TocNode finished = stack.pop();
                            finished.hasChildren = (finished.children != null && !finished.children.isEmpty());
                            if (finished.title == null || finished.title.isEmpty()) {
                                finished.title = "Глава";
                            }
                            if (stack.isEmpty()) {
                                roots.add(finished);
                            } else {
                                stack.peek().children.add(finished);
                                stack.peek().hasChildren = true;
                            }
                        }
                    }
                    currentTag = "";
                }
                eventType = parser.next();
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to parse NCX tree", e);
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
        return roots;
    }

    /**
     * Построение подлинного иерархического дерева оглавления из NAV (EPUB 3).
     */
    private static List<TocNode> parseNavTree(ZipFile zip, String navPath) {
        List<TocNode> roots = new ArrayList<>();
        ZipEntry entry = findZipEntry(zip, navPath);
        if (entry == null) return roots;

        String navBaseDir = "";
        if (navPath.contains("/")) {
            navBaseDir = navPath.substring(0, navPath.lastIndexOf('/') + 1);
        }

        InputStream is = null;
        try {
            is = zip.getInputStream(entry);
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(is, "UTF-8");

            Stack<TocNode> stack = new Stack<>();
            int eventType = parser.getEventType();
            String currentTag = "";
            StringBuilder textBuffer = new StringBuilder();
            TocNode currentNode = null;

            while (eventType != XmlPullParser.END_DOCUMENT) {
                String name = parser.getName();
                if (eventType == XmlPullParser.START_TAG) {
                    currentTag = name;
                    if ("ol".equalsIgnoreCase(name)) {
                        if (currentNode != null) {
                            stack.push(currentNode);
                            currentNode = null;
                        }
                    } else if ("a".equalsIgnoreCase(name)) {
                        String href = parser.getAttributeValue(null, "href");
                        int level = stack.size();
                        currentNode = new TocNode();
                        currentNode.level = level;
                        currentNode.isExpanded = (level == 0);
                        if (href != null) {
                            try {
                                href = URLDecoder.decode(href, "UTF-8");
                            } catch (Exception ignored) {}
                            String fullSrc = navBaseDir.isEmpty() ? href : (navBaseDir + href);
                            currentNode.rawHref = fullSrc;
                            if (fullSrc.contains("#")) {
                                int hash = fullSrc.indexOf('#');
                                currentNode.fileHref = fullSrc.substring(0, hash).trim();
                                currentNode.anchor = fullSrc.substring(hash + 1).trim();
                            } else {
                                currentNode.fileHref = fullSrc.trim();
                                currentNode.anchor = null;
                            }
                        }
                        textBuffer.setLength(0);
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    if ("a".equalsIgnoreCase(currentTag)) {
                        textBuffer.append(parser.getText());
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if ("a".equalsIgnoreCase(name)) {
                        if (currentNode != null) {
                            String clean = cleanHtmlText(textBuffer.toString()).replaceAll("\\s+", " ").trim();
                            currentNode.title = TocNode.normalizeTitle(clean);
                            if (currentNode.title.isEmpty()) currentNode.title = "Глава";
                            if (stack.isEmpty()) {
                                roots.add(currentNode);
                            } else {
                                stack.peek().children.add(currentNode);
                                stack.peek().hasChildren = true;
                            }
                        }
                    } else if ("ol".equalsIgnoreCase(name)) {
                        if (!stack.isEmpty()) {
                            stack.pop();
                        }
                    }
                    currentTag = "";
                }
                eventType = parser.next();
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to parse NAV tree", e);
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
        return roots;
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

    static String extractTitle(String html, String defaultTitle) {
        if (html == null || html.isEmpty()) return defaultTitle;
        try {
            Pattern p1 = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher m1 = p1.matcher(html);
            if (m1.find()) {
                String clean = cleanHtmlText(m1.group(1)).replaceAll("\\s+", " ").trim();
                if (!clean.isEmpty() && clean.length() < 120) return clean;
            }

            Pattern p2 = Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher m2 = p2.matcher(html);
            if (m2.find()) {
                String clean = cleanHtmlText(m2.group(1)).replaceAll("\\s+", " ").trim();
                if (!clean.isEmpty() && clean.length() < 120) return clean;
            }

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
     * - Исключает просачивание любых обрывков тегов и атрибутов (id="...", <h1, <h2)
     * - Формирует нормальное книжное расстояние между абзацами
     * - Сохраняет пропуск одной строки только для явных смысловых разделителей автора
     */
    public static String resolveZipPath(String baseDir, String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) return "";
        String path = relativePath.trim();
        if (path.contains("#")) {
            path = path.substring(0, path.indexOf('#')).trim();
        }
        if (path.contains("?")) {
            path = path.substring(0, path.indexOf('?')).trim();
        }
        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (Exception ignored) {}

        if (path.startsWith("/")) {
            path = path.substring(1);
        } else if (baseDir != null && !baseDir.isEmpty()) {
            path = baseDir + path;
        }

        String[] parts = path.split("/");
        List<String> normalized = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || ".".equals(p)) continue;
            if ("..".equals(p)) {
                if (!normalized.isEmpty()) {
                    normalized.remove(normalized.size() - 1);
                }
            } else {
                normalized.add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < normalized.size(); i++) {
            if (i > 0) sb.append("/");
            sb.append(normalized.get(i));
        }
        return sb.toString();
    }

    public static String cleanHtmlText(String html) {
        return cleanHtmlText(html, "");
    }

    /**
     * Высокопроизводительный очиститель HTML для E-Ink ридеров:
     * - Сохраняет графические иллюстрации в виде тегов [IMG:zipPath]
     * - Исключает появление символов [OBJ] (\uFFFC) и битых глифов (\uFFFD, \uFEFF)
     * - Исключает просачивание любых обрывков тегов и атрибутов (id="...", <h1, <h2)
     * - Формирует нормальное книжное расстояние между абзацами
     * - Сохраняет пропуск одной строки только для явных смысловых разделителей автора
     */
    public static String cleanHtmlText(String html, String chapterFileHref) {
        if (html == null || html.isEmpty()) return "";
        try {
            String baseDir = "";
            if (chapterFileHref != null && chapterFileHref.contains("/")) {
                baseDir = chapterFileHref.substring(0, chapterFileHref.lastIndexOf('/') + 1);
            }

            // 1. Удаление <head>, <style>, <script>
            String text = html.replaceAll("(?is)<(script|style|head).*?>.*?</\\1>", "");

            // 1.1 Преобразование тегов изображений <img> и <image> (SVG) в маркеры
            Pattern imgPattern = Pattern.compile("<img\\s+[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher mImg = imgPattern.matcher(text);
            StringBuffer sbImg = new StringBuffer();
            while (mImg.find()) {
                String rawSrc = mImg.group(1);
                String resolved = resolveZipPath(baseDir, rawSrc);
                if (!resolved.isEmpty()) {
                    mImg.appendReplacement(sbImg, "\n___IMG_MARKER___:" + Matcher.quoteReplacement(resolved) + "\n");
                } else {
                    mImg.appendReplacement(sbImg, "");
                }
            }
            mImg.appendTail(sbImg);
            text = sbImg.toString();

            Pattern svgImgPattern = Pattern.compile("<image\\s+[^>]*?(?:xlink:href|href)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher mSvg = svgImgPattern.matcher(text);
            StringBuffer sbSvg = new StringBuffer();
            while (mSvg.find()) {
                String rawSrc = mSvg.group(1);
                String resolved = resolveZipPath(baseDir, rawSrc);
                if (!resolved.isEmpty()) {
                    mSvg.appendReplacement(sbSvg, "\n___IMG_MARKER___:" + Matcher.quoteReplacement(resolved) + "\n");
                } else {
                    mSvg.appendReplacement(sbSvg, "");
                }
            }
            mSvg.appendTail(sbSvg);
            text = sbSvg.toString();

            // 2. Маркировка явных смысловых разделителей секций
            text = text.replaceAll("(?i)<hr\\s*/?>", "\n___SECTION_BREAK___\n");
            text = text.replaceAll("(?i)<p[^>]*>(\\s*|&nbsp;|<br\\s*/?>|\\*\\s*\\*\\s*\\*)</p>", "\n___SECTION_BREAK___\n");

            // 2.1 Нормализация ссылок-сносок (<a href="...#id">1</a>, <sup><a...>1</a></sup>, [<a...>1</a>]) в токены [1] / [*]
            text = normalizeInlineFootnoteLinks(text);

            // 3. Замена тегов переноса строк и закрытия структурных блоков на единичный \n
            text = text.replaceAll("(?i)<br\\s*/?>", "\n");
            text = text.replaceAll("(?i)</?(?:p|div|h[1-6]|li|blockquote|tr|section|article|header|footer|aside|dd)[^>]*>", "\n");

            // 4. Безопасное удаление всех остальных HTML-тегов с сохранением внутреннего текста
            text = text.replaceAll("<[^>]+>", "");

            // 5. Очистка случайных изолированных осколков неполных тегов на границах
            text = text.replaceAll("<[^>]*$", "");
            text = text.replaceAll("^[^<]*>", "");

            // 9. Тотальное удаление некорректных символов [OBJ], BOM, мягких переносов и непечатных кодов
            text = text.replace("\uFFFC", ""); // Object Replacement Character ([OBJ])
            text = text.replace("\uFFFD", ""); // Unicode Replacement Character
            text = text.replace("\uFEFF", ""); // Byte Order Mark
            text = text.replace("\u200B", ""); // Zero-width space
            text = text.replace("\u200C", ""); // Zero-width non-joiner
            text = text.replace("\u200D", ""); // Zero-width joiner
            text = text.replace("\u00AD", ""); // Soft hyphen

            // 10. Декодирование типографических сущностей HTML
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

            // 11. Построчная сборка текста с аккуратными книжными межстрочными отступами
            String[] lines = text.split("\n");
            StringBuilder result = new StringBuilder(text.length());
            boolean allowEmptyLine = false;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                if (trimmed.startsWith("___IMG_MARKER___:")) {
                    String imgPath = trimmed.substring("___IMG_MARKER___:".length()).trim();
                    if (!imgPath.isEmpty()) {
                        if (result.length() > 0) {
                            result.append("\n");
                        }
                        result.append("[IMG:").append(imgPath).append("]");
                        allowEmptyLine = true;
                    }
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
                if (code == 0xFFFC || code == 0xFFFD || code == 0xFEFF || (code < 32 && code != 10 && code != 9)) {
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

    /**
     * Преобразует инлайновые HTML-ссылки сносок (<sup><a href="...#fn1">1</a></sup>, [<a href="#n1">1</a>], <a href="#fn">*</a>)
     * в единые кликабельные маркеры вида [1] / [*], сохраняя пробел перед маркером для аккуратной разметки.
     */
    static String normalizeInlineFootnoteLinks(String html) {
        if (html == null || html.isEmpty() || !html.contains("#")) {
            return html;
        }
        Pattern fnLinkPattern = Pattern.compile(
                "(?is)(?:<sup>\\s*|\\[\\s*)?<a\\b[^>]*?href\\s*=\\s*[\"'][^\"']*#[^\"']+[\"'][^>]*>(.*?)</a>(?:\\s*</sup>|\\s*\\])?");
        Matcher m = fnLinkPattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String innerRaw = m.group(1);
            String cleanInner = innerRaw != null ? innerRaw.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim() : "";
            String bare = normalizeFootnoteBareToken(cleanInner);
            if (bare != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(" [" + bare + "]"));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String normalizeFootnoteBareToken(String rawLabel) {
        if (rawLabel == null) return null;
        String s = rawLabel.trim();
        if (s.startsWith("[") && s.endsWith("]") && s.length() >= 3) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("(") && s.endsWith(")") && s.length() >= 3) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.matches("\\d{1,4}|\\*{1,3}")) {
            return s;
        }
        return null;
    }

    /**
     * Извлекает определения сносок для каждой главы из EPUB-архива (поддерживает <aside epub:type="footnote">,
     * <p|div|li|dd|section id="...">, в т.ч. перекрёстные ссылки на отдельный notes.xhtml).
     */
    public static void extractFootnotes(
            ZipFile zip,
            List<ChapterData> chapters,
            List<String> chapterHrefs,
            Map<Integer, String> rawHtmlCache,
            Map<String, String> outFootnotes) {
        if (zip == null || chapters == null || chapters.isEmpty() || outFootnotes == null) {
            return;
        }
        try {
            Map<String, String> fileHtmlCache = new HashMap<>();
            if (chapterHrefs != null && rawHtmlCache != null) {
                for (Map.Entry<Integer, String> entry : rawHtmlCache.entrySet()) {
                    int idx = entry.getKey();
                    if (idx >= 0 && idx < chapterHrefs.size()) {
                        String normPath = resolveZipPath("", chapterHrefs.get(idx));
                        if (!normPath.isEmpty() && entry.getValue() != null) {
                            fileHtmlCache.put(normPath, entry.getValue());
                        }
                    }
                }
            }

            Pattern refPattern = Pattern.compile("(?is)<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']*#[^\"']+)[\"'][^>]*>(.*?)</a>");

            for (int chIdx = 0; chIdx < chapters.size(); chIdx++) {
                ChapterData cd = chapters.get(chIdx);
                if (cd == null || "ch_0_cover".equals(cd.id) || cd.fileHref == null || cd.fileHref.isEmpty()) {
                    continue;
                }
                String srcNormHref = resolveZipPath("", cd.fileHref);
                String srcHtml = fileHtmlCache.get(srcNormHref);
                if (srcHtml == null) {
                    srcHtml = readEntryRawHtml(zip, cd.fileHref);
                    if (srcHtml != null) {
                        fileHtmlCache.put(srcNormHref, srcHtml);
                    }
                }
                if (srcHtml == null || srcHtml.isEmpty() || !srcHtml.contains("#")) {
                    continue;
                }

                String srcBaseDir = "";
                if (srcNormHref.contains("/")) {
                    srcBaseDir = srcNormHref.substring(0, srcNormHref.lastIndexOf('/') + 1);
                }

                Matcher m = refPattern.matcher(srcHtml);
                while (m.find()) {
                    String href = m.group(1);
                    String innerRaw = m.group(2);
                    if (href == null) continue;
                    String cleanLabel = innerRaw != null ? innerRaw.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim() : "";
                    String bare = normalizeFootnoteBareToken(cleanLabel);
                    if (bare == null) continue;

                    int hashIdx = href.indexOf('#');
                    if (hashIdx < 0 || hashIdx + 1 >= href.length()) continue;
                    String filePart = href.substring(0, hashIdx).trim();
                    String anchorId = href.substring(hashIdx + 1).trim();
                    try {
                        anchorId = URLDecoder.decode(anchorId, "UTF-8");
                    } catch (Exception ignored) {}
                    if (anchorId.isEmpty()) continue;

                    String targetNormHref = filePart.isEmpty() ? srcNormHref : resolveZipPath(srcBaseDir, filePart);
                    String targetHtml = fileHtmlCache.get(targetNormHref);
                    if (targetHtml == null && !targetNormHref.isEmpty()) {
                        targetHtml = readEntryRawHtml(zip, targetNormHref);
                        if (targetHtml != null) {
                            fileHtmlCache.put(targetNormHref, targetHtml);
                        }
                    }
                    if (targetHtml == null || targetHtml.isEmpty()) continue;

                    String noteText = extractElementTextById(targetHtml, anchorId, targetNormHref, bare);
                    if (noteText == null || noteText.isEmpty()) continue;

                    String marker = "[" + bare + "]";
                    String scopedKey = "ch:" + chIdx + "|" + marker;
                    if (!outFootnotes.containsKey(scopedKey)) {
                        outFootnotes.put(scopedKey, noteText);
                    }
                    if (!outFootnotes.containsKey(marker)) {
                        outFootnotes.put(marker, noteText);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error extracting footnotes", t);
        }
    }

    static String extractElementOrSectionById(String html, String targetId, String targetHref) {
        String exact = extractElementTextById(html, targetId, targetHref, "");
        if (exact != null && exact.length() >= 8) {
            return exact;
        }
        int anchorPos = findAnchorOffset(html, targetId);
        if (anchorPos >= 0 && anchorPos < html.length()) {
            int end = Math.min(html.length(), anchorPos + 3500);
            String slice = html.substring(anchorPos, end);
            String clean = EpubFormatting.stripFormattingTokens(EpubFormatting.extractRichHtmlText(slice, targetHref, null))
                    .replaceAll("\\[IMG:[^\\]]+\\]", "")
                    .trim();
            if (clean.length() > 1200) {
                clean = clean.substring(0, 1197).trim() + "…";
            }
            if (!clean.isEmpty()) return clean;
        }
        return exact;
    }

    private static String extractElementTextById(String html, String targetId, String targetHref, String bareMarker) {
        if (html == null || targetId == null || targetId.isEmpty()) return null;
        try {
            Pattern startPat = Pattern.compile(
                    "(?is)<(aside|div|p|li|dd|section)\\b[^>]*?\\bid\\s*=\\s*[\"']" + Pattern.quote(targetId) + "[\"'][^>]*>");
            Matcher m = startPat.matcher(html);
            if (!m.find()) {
                return null;
            }
            String tagName = m.group(1).toLowerCase();
            int bodyStart = m.end();
            int limit = Math.min(html.length(), bodyStart + 6000);
            Pattern tagPat = Pattern.compile("(?is)<(/?)" + Pattern.quote(tagName) + "\\b[^>]*>");
            Matcher tm = tagPat.matcher(html);
            tm.region(bodyStart, limit);
            int depth = 1;
            int bodyEnd = -1;
            while (tm.find()) {
                String fullTag = tm.group(0);
                if (tm.group(1) != null && !tm.group(1).isEmpty()) {
                    depth--;
                    if (depth == 0) {
                        bodyEnd = tm.start();
                        break;
                    }
                } else if (!fullTag.endsWith("/>")) {
                    depth++;
                }
            }
            if (bodyEnd <= bodyStart) {
                return null;
            }
            String rawInner = html.substring(bodyStart, bodyEnd);
            rawInner = rawInner.replaceAll("(?is)<a\\b[^>]*>(?:\\s*[↑^←↩]\\s*)</a>", "");
            String clean = cleanHtmlText(rawInner, targetHref).replaceAll("\\s+", " ").trim();
            if (clean.isEmpty()) return null;

            if (bareMarker != null && !bareMarker.isEmpty()) {
                String bracketPrefix = "[" + bareMarker + "]";
                if (clean.startsWith(bracketPrefix)) {
                    clean = clean.substring(bracketPrefix.length()).trim();
                } else if (clean.startsWith(bareMarker + ".")) {
                    clean = clean.substring(bareMarker.length() + 1).trim();
                } else if (clean.startsWith(bareMarker + ")")) {
                    clean = clean.substring(bareMarker.length() + 1).trim();
                } else if (clean.startsWith(bareMarker + " ")) {
                    clean = clean.substring(bareMarker.length() + 1).trim();
                }
            }
            if (clean.startsWith("^") || clean.startsWith("↑") || clean.startsWith("←")) {
                clean = clean.substring(1).trim();
            }
            if (clean.isEmpty()) return null;
            if (clean.length() > 1200) {
                clean = clean.substring(0, 1197).trim() + "…";
            }
            return clean;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readStreamToString(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }
}
