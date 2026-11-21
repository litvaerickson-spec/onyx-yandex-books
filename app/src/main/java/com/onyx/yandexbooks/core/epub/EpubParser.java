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
        public int originalOpfSpineIndex = -1;
        public String opfItemId = "";
        public int rawHtmlStartOffset = 0;
        public int rawHtmlEndOffset = -1;
    }

    private static class SpineItem {
        final String href;
        final String idref;
        final int originalOpfSpineIndex;

        SpineItem(String href, String idref, int originalOpfSpineIndex) {
            this.href = href;
            this.idref = idref != null ? idref : "";
            this.originalOpfSpineIndex = originalOpfSpineIndex;
        }
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
            List<SpineItem> spineItems = new ArrayList<>();
            Map<String, String> manifestHrefMap = new HashMap<>();
            String ncxPathFromOpf = null;
            String navPathFromOpf = null;

            if (opfPath != null) {
                spineItems = parseOpfSpineDetailed(zip, opfPath, opfBaseDir, manifestHrefMap);
                for (SpineItem si : spineItems) {
                    chapterHrefs.add(si.href);
                }
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
                    String lower = name.toLowerCase(java.util.Locale.US);
                    int lastSlash = lower.lastIndexOf('/');
                    String baseName = lastSlash >= 0 ? lower.substring(lastSlash + 1) : lower;
                    boolean isMetaDoc = baseName.startsWith("cover")
                            || baseName.startsWith("toc")
                            || "nav.xhtml".equals(baseName)
                            || "nav.html".equals(baseName)
                            || baseName.startsWith("nav-")
                            || baseName.startsWith("nav_");
                    if ((lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm"))
                            && !isMetaDoc) {
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
            Map<String, EpubFormatting.CssSheet> parsedCssSheetCache = new HashMap<>();
            List<ChapterData> chapters = new ArrayList<>();

            if (chapterHrefs.size() == 1) {
                // Если файл книги всего 1 большой HTML: проверяем деление по h1/h2
                SpineItem singleMeta = spineItems.isEmpty() ? null : spineItems.get(0);
                List<ChapterData> byHeadings = splitSingleFileByHeadings(zip, chapterHrefs.get(0), singleMeta, rawHtmlCache);
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

                    EpubFormatting.CssSheet cssSheet = EpubFormatting.buildCssSheet(zip, rawHtml, href, cssFileCache, parsedCssSheetCache);
                    String richText = EpubFormatting.extractRichHtmlText(rawHtml, href, cssSheet);

                    ChapterData cd = new ChapterData();
                    cd.id = "ch_" + chIndex;
                    cd.fileHref = href;
                    cd.spineIndex = s;
                    if (s < spineItems.size()) {
                        cd.originalOpfSpineIndex = spineItems.get(s).originalOpfSpineIndex;
                        cd.opfItemId = spineItems.get(s).idref;
                    } else {
                        cd.originalOpfSpineIndex = s;
                    }
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
                    coverCh.originalOpfSpineIndex = -1;
                    coverCh.opfItemId = "";
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

    private static final class TocLookupIndex {
        final Map<String, List<Integer>> exactNormToChapters = new HashMap<>();
        final Map<String, List<Integer>> shortNameToChapters = new HashMap<>();
        final List<String> normalizedChapterPaths = new ArrayList<>();
        final List<String> normalizedSpinePaths = new ArrayList<>();
        final Map<String, Integer> exactNormToSpine = new HashMap<>();

        TocLookupIndex(List<ChapterData> chapters, List<String> spineHrefs) {
            for (int i = 0; i < chapters.size(); i++) {
                ChapterData cd = chapters.get(i);
                String norm = (cd != null && cd.fileHref != null && !"ch_0_cover".equals(cd.id))
                        ? resolveZipPath("", cd.fileHref).toLowerCase(java.util.Locale.US) : "";
                normalizedChapterPaths.add(norm);
                if (!norm.isEmpty()) {
                    List<Integer> list = exactNormToChapters.get(norm);
                    if (list == null) {
                        list = new ArrayList<>(1);
                        exactNormToChapters.put(norm, list);
                    }
                    list.add(i);

                    int slash = norm.lastIndexOf('/');
                    String fname = slash >= 0 ? norm.substring(slash + 1) : norm;
                    List<Integer> sList = shortNameToChapters.get(fname);
                    if (sList == null) {
                        sList = new ArrayList<>(1);
                        shortNameToChapters.put(fname, sList);
                    }
                    sList.add(i);
                }
            }
            if (spineHrefs != null) {
                for (int s = 0; s < spineHrefs.size(); s++) {
                    String sn = resolveZipPath("", spineHrefs.get(s)).toLowerCase(java.util.Locale.US);
                    normalizedSpinePaths.add(sn);
                    if (!sn.isEmpty() && !exactNormToSpine.containsKey(sn)) {
                        exactNormToSpine.put(sn, s);
                    }
                }
            }
        }

        List<Integer> findChapterCandidates(String rawPath) {
            if (rawPath == null || rawPath.isEmpty()) return null;
            String norm = resolveZipPath("", rawPath).toLowerCase(java.util.Locale.US);
            if (norm.isEmpty()) return null;

            List<Integer> exact = exactNormToChapters.get(norm);
            if (exact != null && !exact.isEmpty()) return exact;

            for (Map.Entry<String, List<Integer>> e : exactNormToChapters.entrySet()) {
                String k = e.getKey();
                if (k.endsWith("/" + norm) || norm.endsWith("/" + k)) {
                    return e.getValue();
                }
            }

            int slash = norm.lastIndexOf('/');
            String fname = slash >= 0 ? norm.substring(slash + 1) : norm;
            String dir = slash >= 0 ? norm.substring(0, slash) : "";
            List<Integer> byName = shortNameToChapters.get(fname);
            if (byName != null && !byName.isEmpty()) {
                if (dir.isEmpty()) return byName;
                List<Integer> filtered = new ArrayList<>();
                for (int idx : byName) {
                    String cp = normalizedChapterPaths.get(idx);
                    String cDir = cp.contains("/") ? cp.substring(0, cp.lastIndexOf('/')) : "";
                    if (cDir.isEmpty() || cDir.equals(dir) || cDir.endsWith("/" + dir) || dir.endsWith("/" + cDir)) {
                        filtered.add(idx);
                    }
                }
                if (!filtered.isEmpty()) return filtered;
            }
            return null;
        }

        int findSpineIdx(String rawPath) {
            if (rawPath == null || rawPath.isEmpty()) return -1;
            String norm = resolveZipPath("", rawPath).toLowerCase(java.util.Locale.US);
            if (norm.isEmpty()) return -1;
            Integer exact = exactNormToSpine.get(norm);
            if (exact != null) return exact;
            for (int s = 0; s < normalizedSpinePaths.size(); s++) {
                String sp = normalizedSpinePaths.get(s);
                if (sp.endsWith("/" + norm) || norm.endsWith("/" + sp)) return s;
            }
            int slash = norm.lastIndexOf('/');
            String fname = slash >= 0 ? norm.substring(slash + 1) : norm;
            for (int s = 0; s < normalizedSpinePaths.size(); s++) {
                String sp = normalizedSpinePaths.get(s);
                String sf = sp.contains("/") ? sp.substring(sp.lastIndexOf('/') + 1) : sp;
                if (sf.equals(fname)) return s;
            }
            return -1;
        }
    }

    private static void resolveNodeOffsets(List<TocNode> nodes, List<ChapterData> chapters, List<String> spineHrefs, Map<Integer, String> rawHtmlCache) {
        if (nodes == null || chapters == null || chapters.isEmpty()) return;
        TocLookupIndex lookup = new TocLookupIndex(chapters, spineHrefs);
        resolveNodeOffsetsRecursive(nodes, chapters, lookup, rawHtmlCache);
    }

    private static void resolveNodeOffsetsRecursive(List<TocNode> nodes, List<ChapterData> chapters, TocLookupIndex lookup, Map<Integer, String> rawHtmlCache) {
        if (nodes == null || chapters == null || chapters.isEmpty()) return;

        for (TocNode node : nodes) {
            List<Integer> candidates = lookup.findChapterCandidates(node.fileHref);
            if ((candidates == null || candidates.isEmpty()) && node.rawHref != null && !node.rawHref.isEmpty()) {
                candidates = lookup.findChapterCandidates(node.rawHref);
            }

            int matchedChapter = (candidates != null && !candidates.isEmpty()) ? candidates.get(0) : -1;
            if (matchedChapter < 0) {
                int sIdx = lookup.findSpineIdx(node.fileHref);
                if (sIdx >= 0 && sIdx < lookup.normalizedSpinePaths.size()) {
                    candidates = lookup.findChapterCandidates(lookup.normalizedSpinePaths.get(sIdx));
                    if (candidates != null && !candidates.isEmpty()) {
                        matchedChapter = candidates.get(0);
                    }
                }
            }
            if (matchedChapter < 0) {
                matchedChapter = 0;
            }

            node.spineIndex = matchedChapter;
            node.charOffset = 0;

            if (node.anchor != null && !node.anchor.isEmpty()) {
                int spineFileIndex = lookup.findSpineIdx(node.fileHref);
                String rawHtml = (spineFileIndex >= 0) ? rawHtmlCache.get(spineFileIndex) : null;
                if (rawHtml != null) {
                    int anchorTagStart = findAnchorOffset(rawHtml, node.anchor);
                    if (anchorTagStart >= 0 && anchorTagStart < rawHtml.length()) {
                        // Если один HTML-файл разбит по заголовкам h1/h2 на несколько глав — находим точный фрагмент!
                        if (candidates != null && candidates.size() > 1) {
                            for (int cIdx : candidates) {
                                ChapterData cd = chapters.get(cIdx);
                                int end = (cd.rawHtmlEndOffset > cd.rawHtmlStartOffset) ? cd.rawHtmlEndOffset : rawHtml.length();
                                if (anchorTagStart >= cd.rawHtmlStartOffset && anchorTagStart < end) {
                                    matchedChapter = cIdx;
                                    node.spineIndex = matchedChapter;
                                    break;
                                }
                            }
                        }
                        ChapterData cdMatched = (matchedChapter >= 0 && matchedChapter < chapters.size())
                                ? chapters.get(matchedChapter) : null;
                        int sliceStart = (cdMatched != null) ? cdMatched.rawHtmlStartOffset : 0;
                        int sliceEnd = (cdMatched != null && cdMatched.rawHtmlEndOffset > sliceStart)
                                ? Math.min(cdMatched.rawHtmlEndOffset, rawHtml.length()) : rawHtml.length();
                        int localAnchorStart = anchorTagStart - sliceStart;
                        if (localAnchorStart > 0 && sliceStart < sliceEnd) {
                            String chText = cdMatched != null ? cdMatched.textContent : null;
                            node.charOffset = estimateAnchorCharOffsetFast(rawHtml, sliceStart, sliceEnd, anchorTagStart, chText);
                        }
                    }
                }
            }

            node.hasChildren = (node.children != null && !node.children.isEmpty());
            if (node.children != null && !node.children.isEmpty()) {
                resolveNodeOffsetsRecursive(node.children, chapters, lookup, rawHtmlCache);
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
    private static List<ChapterData> splitSingleFileByHeadings(ZipFile zip, String href, SpineItem singleMeta, Map<Integer, String> rawHtmlCache) {
        List<ChapterData> list = new ArrayList<>();
        String rawHtml = readEntryRawHtml(zip, href);
        if (rawHtml == null || rawHtml.length() < 200) return list;
        if (rawHtmlCache != null) {
            rawHtmlCache.put(0, rawHtml);
        }
        int baseOpfIdx = singleMeta != null ? singleMeta.originalOpfSpineIndex : 0;
        String baseOpfId = (singleMeta != null && singleMeta.idref != null) ? singleMeta.idref : "";

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
                    cd0.spineIndex = list.size();
                    cd0.originalOpfSpineIndex = baseOpfIdx;
                    cd0.opfItemId = baseOpfId;
                    cd0.title = "Начало книги";
                    cd0.textContent = front;
                    cd0.rawHtmlStartOffset = 0;
                    cd0.rawHtmlEndOffset = headings.get(0).start;
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
                    cd.spineIndex = list.size();
                    cd.originalOpfSpineIndex = baseOpfIdx;
                    cd.opfItemId = baseOpfId;
                    cd.title = TocNode.normalizeTitle(headings.get(i).title);
                    cd.textContent = formatted;
                    cd.rawHtmlStartOffset = from;
                    cd.rawHtmlEndOffset = to;
                    list.add(cd);
                }
            }
        }
        return list;
    }

    /**
     * Быстрый расчёт точного символьного смещения якоря внутри уже распарсенного текста главы
     * без повторного вызова EpubFormatting.extractRichHtmlText (пропорция видимого текста + привязка к абзацу).
     */
    private static int estimateAnchorCharOffsetFast(String rawHtml, int sliceStart, int sliceEnd, int anchorTagStart, String chapterText) {
        if (rawHtml == null || anchorTagStart <= sliceStart) return 0;
        int len = Math.min(sliceEnd, rawHtml.length());
        int start = Math.max(0, sliceStart);
        if (start == 0) {
            int bodyStart = rawHtml.indexOf("<body");
            if (bodyStart < 0) bodyStart = rawHtml.indexOf("<BODY");
            if (bodyStart >= 0 && bodyStart < len) {
                int gt = rawHtml.indexOf('>', bodyStart);
                if (gt > bodyStart && gt < len) start = gt + 1;
            }
        }
        if (anchorTagStart <= start) return 0;

        int visibleBefore = 0;
        int visibleTotal = 0;
        boolean inTag = false;
        boolean prevSpace = true;
        int i = start;
        while (i < len) {
            if (!inTag && rawHtml.startsWith("<!--", i)) {
                int endCom = rawHtml.indexOf("-->", i + 4);
                i = (endCom >= 0 && endCom + 3 <= len) ? endCom + 3 : len;
                continue;
            }
            char c = rawHtml.charAt(i);
            if (c == '<') {
                inTag = true;
                if (i + 6 < len && (rawHtml.regionMatches(true, i, "<style", 0, 6) || rawHtml.regionMatches(true, i, "<script", 0, 7))) {
                    String closeTag = rawHtml.regionMatches(true, i, "<style", 0, 6) ? "</style>" : "</script>";
                    int endBlk = -1;
                    for (int k = i + 6; k + closeTag.length() <= len; k++) {
                        if (rawHtml.regionMatches(true, k, closeTag, 0, closeTag.length())) {
                            endBlk = k + closeTag.length();
                            break;
                        }
                    }
                    if (endBlk > i) {
                        i = endBlk;
                        inTag = false;
                        continue;
                    }
                }
            } else if (c == '>') {
                inTag = false;
            } else if (!inTag) {
                if (c <= ' ') {
                    if (!prevSpace) {
                        visibleTotal++;
                        if (i < anchorTagStart) visibleBefore++;
                        prevSpace = true;
                    }
                } else {
                    visibleTotal++;
                    if (i < anchorTagStart) visibleBefore++;
                    prevSpace = false;
                }
            }
            i++;
        }

        if (visibleBefore < 40) return 0;
        if (chapterText == null || chapterText.isEmpty()) {
            return visibleBefore;
        }
        int chLen = chapterText.length();
        int approx = (visibleTotal > 0)
                ? (int) Math.round(((double) visibleBefore / (double) visibleTotal) * chLen)
                : Math.min(visibleBefore, chLen - 1);
        approx = Math.max(0, Math.min(approx, Math.max(0, chLen - 1)));

        // Точная привязка к началу строки по текстовому сниппету сразу после якоря
        StringBuilder snippet = new StringBuilder(20);
        boolean snInTag = false;
        int snLimit = Math.min(len, anchorTagStart + 500);
        for (int k = anchorTagStart; k < snLimit && snippet.length() < 16; k++) {
            char ch = rawHtml.charAt(k);
            if (ch == '<') {
                snInTag = true;
            } else if (ch == '>') {
                snInTag = false;
            } else if (!snInTag && ch > ' ' && ch != '&' && ch != ';') {
                if (snippet.length() > 0 || Character.isLetterOrDigit(ch)) {
                    snippet.append(ch);
                }
            } else if (!snInTag && ch <= ' ' && snippet.length() > 0 && snippet.charAt(snippet.length() - 1) != ' ') {
                snippet.append(' ');
            }
        }
        String probe = snippet.toString().trim();
        if (probe.length() >= 4) {
            int winStart = Math.max(0, approx - 1500);
            int winEnd = Math.min(chLen, approx + 1500);
            int hit = chapterText.indexOf(probe, winStart);
            if (hit >= winStart && hit < winEnd) {
                int lineStart = chapterText.lastIndexOf('\n', hit);
                return (lineStart >= 0) ? (lineStart + 1) : 0;
            }
        }
        return approx;
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

        // 0. Сверхбыстрый путь без компиляции регулярных выражений (99% реальных EPUB)
        String[] fastNeedles = new String[]{
                "id=\"" + cleanAnchor + "\"",
                "id='" + cleanAnchor + "'",
                "name=\"" + cleanAnchor + "\"",
                "name='" + cleanAnchor + "'"
        };
        for (int k = 0; k < fastNeedles.length; k++) {
            int idx = html.indexOf(fastNeedles[k]);
            while (idx >= 0) {
                boolean boundaryOk = (idx == 0) || !Character.isLetterOrDigit(html.charAt(idx - 1));
                if (boundaryOk) {
                    int tagStart = html.lastIndexOf('<', idx);
                    int tagCloseBefore = html.lastIndexOf('>', idx);
                    if (tagStart >= 0 && tagStart > tagCloseBefore) {
                        return tagStart;
                    }
                }
                idx = html.indexOf(fastNeedles[k], idx + 1);
            }
        }

        // 1. Резервный поиск с пробелами вокруг '=' или другим регистром атрибута
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
        List<SpineItem> detailed = parseOpfSpineDetailed(zip, opfPath, baseDir, outManifestMap);
        List<String> result = new ArrayList<>(detailed.size());
        for (SpineItem item : detailed) {
            result.add(item.href);
        }
        return result;
    }

    private static String extractXmlTagAttr(String attrs, String attrName) {
        if (attrs == null || attrs.isEmpty() || attrName == null) return null;
        Pattern p = Pattern.compile("(?:^|\\s)" + Pattern.quote(attrName) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(attrs);
        if (m.find()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }

    private static List<SpineItem> parseOpfSpineDetailed(ZipFile zip, String opfPath, String baseDir, Map<String, String> outManifestMap) {
        List<SpineItem> result = new ArrayList<>();
        ZipEntry opfEntry = findZipEntry(zip, opfPath);
        if (opfEntry == null) return result;

        InputStream is = null;
        try {
            is = zip.getInputStream(opfEntry);
            String opfXml = readStreamToString(is);

            // Сопоставление id -> href из <manifest> независимо от порядка атрибутов
            Pattern itemTagPattern = Pattern.compile("<item\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher m = itemTagPattern.matcher(opfXml);
            while (m.find()) {
                String attrs = m.group(1);
                String id = extractXmlTagAttr(attrs, "id");
                String href = extractXmlTagAttr(attrs, "href");
                if (id != null && !id.isEmpty() && href != null && !href.isEmpty()) {
                    outManifestMap.put(id, href);
                }
            }

            // Порядок чтения из <spine> с сохранением точного 0-based индекса <itemref> и opfItemId
            Pattern itemrefPattern = Pattern.compile("<itemref\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher mSpine = itemrefPattern.matcher(opfXml);
            int opfIdx = 0;
            while (mSpine.find()) {
                String idref = extractXmlTagAttr(mSpine.group(1), "idref");
                String href = idref != null ? outManifestMap.get(idref) : null;
                if (href != null) {
                    String fullPath = resolveZipPath(baseDir, href);
                    if (!fullPath.isEmpty()) {
                        boolean alreadyExists = false;
                        for (int k = 0; k < result.size(); k++) {
                            if (fullPath.equals(result.get(k).href)) {
                                alreadyExists = true;
                                break;
                            }
                        }
                        if (!alreadyExists) {
                            result.add(new SpineItem(fullPath, idref, opfIdx));
                        }
                    }
                }
                opfIdx++;
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
        ZipEntry opfEntry = findZipEntry(zip, opfPath);
        if (opfEntry == null) return null;
        InputStream is = null;
        try {
            is = zip.getInputStream(opfEntry);
            String opfXml = readStreamToString(is);
            Pattern itemTagPattern = Pattern.compile("<item\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher m = itemTagPattern.matcher(opfXml);
            String fallbackNcxHref = null;
            while (m.find()) {
                String attrs = m.group(1);
                String href = extractXmlTagAttr(attrs, "href");
                if (href == null || href.isEmpty()) continue;
                String mediaType = extractXmlTagAttr(attrs, "media-type");
                if ("application/x-dtbncx+xml".equalsIgnoreCase(mediaType)) {
                    return resolveZipPath(baseDir, href);
                }
                if (fallbackNcxHref == null && href.toLowerCase(java.util.Locale.US).endsWith(".ncx")) {
                    fallbackNcxHref = href;
                }
            }
            if (fallbackNcxHref != null) {
                return resolveZipPath(baseDir, fallbackNcxHref);
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
        ZipEntry opfEntry = findZipEntry(zip, opfPath);
        if (opfEntry == null) return null;
        InputStream is = null;
        try {
            is = zip.getInputStream(opfEntry);
            String opfXml = readStreamToString(is);
            Pattern itemTagPattern = Pattern.compile("<item\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher m = itemTagPattern.matcher(opfXml);
            while (m.find()) {
                String attrs = m.group(1);
                String props = extractXmlTagAttr(attrs, "properties");
                if (props != null && props.matches("(?is).*\\bnav\\b.*")) {
                    String href = extractXmlTagAttr(attrs, "href");
                    if (href != null && !href.isEmpty()) {
                        return resolveZipPath(baseDir, href);
                    }
                }
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
        ZipEntry opfEntry = findZipEntry(zip, opfPath);
        if (opfEntry == null) return null;
        InputStream is = null;
        try {
            is = zip.getInputStream(opfEntry);
            String opfXml = readStreamToString(is);
            Map<String, String> idToHref = new HashMap<>();
            String coverPropHref = null;
            String coverIdHref = null;
            Pattern itemTagPattern = Pattern.compile("<item\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher im = itemTagPattern.matcher(opfXml);
            while (im.find()) {
                String attrs = im.group(1);
                String id = extractXmlTagAttr(attrs, "id");
                String href = extractXmlTagAttr(attrs, "href");
                String props = extractXmlTagAttr(attrs, "properties");
                if (id != null && !id.isEmpty() && href != null && !href.isEmpty()) {
                    idToHref.put(id, href);
                    if (coverIdHref == null && ("cover".equalsIgnoreCase(id) || "cover-image".equalsIgnoreCase(id) || "book-cover".equalsIgnoreCase(id))) {
                        String lowHref = href.toLowerCase(java.util.Locale.US);
                        if (lowHref.endsWith(".jpg") || lowHref.endsWith(".jpeg") || lowHref.endsWith(".png") || lowHref.endsWith(".webp") || lowHref.endsWith(".gif")) {
                            coverIdHref = href;
                        }
                    }
                }
                if (coverPropHref == null && href != null && props != null && props.toLowerCase(java.util.Locale.US).contains("cover-image")) {
                    coverPropHref = href;
                }
            }

            // 1. EPUB 2: <meta name="cover" content="id"/> (независимо от порядка атрибутов)
            Pattern metaPattern = Pattern.compile("<meta\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher metaM = metaPattern.matcher(opfXml);
            while (metaM.find()) {
                String mAttrs = metaM.group(1);
                if ("cover".equalsIgnoreCase(extractXmlTagAttr(mAttrs, "name"))) {
                    String coverId = extractXmlTagAttr(mAttrs, "content");
                    if (coverId != null && idToHref.containsKey(coverId)) {
                        return resolveZipPath(baseDir, idToHref.get(coverId));
                    }
                }
            }

            // 2. EPUB 3: properties="...cover-image..."
            if (coverPropHref != null) {
                return resolveZipPath(baseDir, coverPropHref);
            }

            // 3. Fallback: <item id="cover" ...>
            if (coverIdHref != null) {
                return resolveZipPath(baseDir, coverIdHref);
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
                                int hash = src.indexOf('#');
                                String rawFilePart = hash >= 0 ? src.substring(0, hash).trim() : src.trim();
                                String anchorPart = hash >= 0 ? src.substring(hash + 1).trim() : null;
                                if (anchorPart != null) {
                                    try { anchorPart = URLDecoder.decode(anchorPart, "UTF-8"); } catch (Exception ignored) {}
                                }
                                String resolvedFile = resolveZipPath(ncxBaseDir, rawFilePart);
                                TocNode top = stack.peek();
                                top.fileHref = resolvedFile;
                                top.anchor = (anchorPart != null && !anchorPart.isEmpty()) ? anchorPart : null;
                                top.rawHref = top.anchor != null
                                        ? (resolvedFile + "#" + top.anchor)
                                        : resolvedFile;
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
        List<TocNode> tocNavRoots = new ArrayList<>();
        List<TocNode> fallbackNavRoots = new ArrayList<>();
        ZipEntry entry = findZipEntry(zip, navPath);
        if (entry == null) return tocNavRoots;

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
            StringBuilder textBuffer = new StringBuilder();
            TocNode currentNode = null;
            boolean inNav = false;
            boolean currentNavIsToc = false;
            boolean inAnchor = false;
            int anchorNestedDepth = 0;
            int navDepth = 0;
            boolean capturedFallbackNav = false;

            while (eventType != XmlPullParser.END_DOCUMENT) {
                String name = parser.getName();
                if (eventType == XmlPullParser.START_TAG) {
                    if ("nav".equalsIgnoreCase(name)) {
                        inNav = true;
                        navDepth = 1;
                        stack.clear();
                        currentNode = null;
                        inAnchor = false;
                        anchorNestedDepth = 0;
                        String epubType = parser.getAttributeValue(null, "epub:type");
                        String typeAttr = parser.getAttributeValue(null, "type");
                        String idAttr = parser.getAttributeValue(null, "id");
                        String combined = ((epubType != null ? epubType : "") + " "
                                + (typeAttr != null ? typeAttr : "") + " "
                                + (idAttr != null ? idAttr : "")).toLowerCase(java.util.Locale.US);
                        currentNavIsToc = combined.matches(".*\\btoc\\b.*");
                    } else if (inNav) {
                        if ("nav".equalsIgnoreCase(name)) {
                            navDepth++;
                        } else if ("ol".equalsIgnoreCase(name)) {
                            if (currentNode != null) {
                                stack.push(currentNode);
                                currentNode = null;
                            }
                        } else if ("a".equalsIgnoreCase(name)) {
                            inAnchor = true;
                            anchorNestedDepth = 1;
                            String href = parser.getAttributeValue(null, "href");
                            int level = stack.size();
                            currentNode = new TocNode();
                            currentNode.level = level;
                            currentNode.isExpanded = (level == 0);
                            if (href != null) {
                                int hash = href.indexOf('#');
                                String rawFilePart = hash >= 0 ? href.substring(0, hash).trim() : href.trim();
                                String anchorPart = hash >= 0 ? href.substring(hash + 1).trim() : null;
                                if (anchorPart != null) {
                                    try { anchorPart = URLDecoder.decode(anchorPart, "UTF-8"); } catch (Exception ignored) {}
                                }
                                String resolvedFile = rawFilePart.isEmpty()
                                        ? resolveZipPath("", navPath)
                                        : resolveZipPath(navBaseDir, rawFilePart);
                                currentNode.fileHref = resolvedFile;
                                currentNode.anchor = (anchorPart != null && !anchorPart.isEmpty()) ? anchorPart : null;
                                currentNode.rawHref = currentNode.anchor != null
                                        ? (resolvedFile + "#" + currentNode.anchor)
                                        : resolvedFile;
                            }
                            textBuffer.setLength(0);
                        } else if (inAnchor) {
                            anchorNestedDepth++;
                        }
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    if (inNav && inAnchor) {
                        textBuffer.append(parser.getText());
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if (inNav) {
                        if (inAnchor) {
                            if ("a".equalsIgnoreCase(name)) {
                                anchorNestedDepth--;
                                if (anchorNestedDepth <= 0) {
                                    inAnchor = false;
                                    anchorNestedDepth = 0;
                                    if (currentNode != null) {
                                        String clean = cleanHtmlText(textBuffer.toString()).replaceAll("\\s+", " ").trim();
                                        currentNode.title = TocNode.normalizeTitle(clean);
                                        if (currentNode.title.isEmpty()) currentNode.title = "Глава";
                                        if (stack.isEmpty()) {
                                            if (currentNavIsToc) {
                                                tocNavRoots.add(currentNode);
                                            } else if (!capturedFallbackNav) {
                                                fallbackNavRoots.add(currentNode);
                                            }
                                        } else {
                                            stack.peek().children.add(currentNode);
                                            stack.peek().hasChildren = true;
                                        }
                                    }
                                }
                            }
                        } else if ("ol".equalsIgnoreCase(name)) {
                            if (!stack.isEmpty()) {
                                stack.pop();
                            }
                            currentNode = null;
                        } else if ("li".equalsIgnoreCase(name)) {
                            currentNode = null;
                        } else if ("nav".equalsIgnoreCase(name)) {
                            navDepth--;
                            if (navDepth <= 0) {
                                inNav = false;
                                if (!currentNavIsToc && !fallbackNavRoots.isEmpty()) {
                                    capturedFallbackNav = true;
                                }
                                currentNavIsToc = false;
                                stack.clear();
                                currentNode = null;
                            }
                        }
                    }
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
        return !tocNavRoots.isEmpty() ? tocNavRoots : fallbackNavRoots;
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
        if (zip == null || name == null) return null;
        String cleanName = resolveZipPath("", name);
        if (!cleanName.isEmpty()) {
            ZipEntry cleanEntry = zip.getEntry(cleanName);
            if (cleanEntry != null) return cleanEntry;
        }
        ZipEntry entry = zip.getEntry(name);
        if (entry != null) return entry;

        String target = cleanName.isEmpty() ? name.replace('\\', '/') : cleanName;
        while (target.startsWith("/")) target = target.substring(1);
        String lower = target.toLowerCase(java.util.Locale.US);
        Enumeration<? extends ZipEntry> entries = zip.entries();
        ZipEntry suffixMatch = null;
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String eName = e.getName().replace('\\', '/');
            if (eName.equalsIgnoreCase(target)) {
                return e;
            }
            if (suffixMatch == null && !lower.isEmpty() && eName.toLowerCase(java.util.Locale.US).endsWith("/" + lower)) {
                suffixMatch = e;
            }
        }
        return suffixMatch;
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

    private static final Pattern FOOTNOTE_REF_PATTERN =
            Pattern.compile("(?is)<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']*#[^\"']+)[\"'][^>]*>(.*?)</a>");
    private static final Pattern FOOTNOTE_TAG_STRIP_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern FOOTNOTE_BACKLINK_PATTERN = Pattern.compile("(?is)<a\\b[^>]*>(?:\\s*[↑^←↩]\\s*)</a>");
    private static final Pattern FOOTNOTE_WS_PATTERN = Pattern.compile("\\s+");

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

                String scanHtml = srcHtml;
                if (cd.rawHtmlEndOffset > cd.rawHtmlStartOffset && cd.rawHtmlEndOffset <= srcHtml.length()) {
                    scanHtml = srcHtml.substring(Math.max(0, cd.rawHtmlStartOffset), cd.rawHtmlEndOffset);
                }

                Matcher m = FOOTNOTE_REF_PATTERN.matcher(scanHtml);
                while (m.find()) {
                    String href = m.group(1);
                    String innerRaw = m.group(2);
                    if (href == null) continue;
                    String cleanLabel = "";
                    if (innerRaw != null) {
                        String noTags = innerRaw.indexOf('<') >= 0
                                ? FOOTNOTE_TAG_STRIP_PATTERN.matcher(innerRaw).replaceAll("") : innerRaw;
                        cleanLabel = FOOTNOTE_WS_PATTERN.matcher(noTags).replaceAll(" ").trim();
                    }
                    String bare = normalizeFootnoteBareToken(cleanLabel);
                    if (bare == null) continue;

                    int hashIdx = href.indexOf('#');
                    if (hashIdx < 0 || hashIdx + 1 >= href.length()) continue;
                    String filePart = href.substring(0, hashIdx).trim();
                    String anchorId = href.substring(hashIdx + 1).trim();
                    if (anchorId.indexOf('%') >= 0) {
                        try {
                            anchorId = URLDecoder.decode(anchorId, "UTF-8");
                        } catch (Exception ignored) {}
                    }
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
            int tagStart = findAnchorOffset(html, targetId);
            if (tagStart < 0 || tagStart + 2 >= html.length()) {
                return null;
            }
            int openEnd = html.indexOf('>', tagStart);
            if (openEnd <= tagStart) {
                return null;
            }
            int nameEnd = tagStart + 1;
            while (nameEnd < openEnd && !Character.isWhitespace(html.charAt(nameEnd))) {
                nameEnd++;
            }
            String tagName = html.substring(tagStart + 1, nameEnd).toLowerCase(java.util.Locale.US);
            if ("a".equals(tagName) || "span".equals(tagName) || "sup".equals(tagName)) {
                // Ищем обрамляющий блочный контейнер (p/div/aside/li/dd/section) для встроенных якорей вида <p><a id="fn1"/>...</p>
                String inlineSelf = extractTagInnerText(html, tagStart, tagName);
                String strippedSelf = stripFootnoteNumberPrefix(inlineSelf, bareMarker);
                if (!strippedSelf.isEmpty() && strippedSelf.length() >= 12) {
                    return strippedSelf.length() > 1200 ? strippedSelf.substring(0, 1197).trim() + "…" : strippedSelf;
                }
                int searchFrom = Math.max(0, tagStart - 300);
                int bestBlockStart = -1;
                String prefixSlice = html.substring(searchFrom, tagStart);
                Matcher bm = Pattern.compile("<(aside|div|p|li|dd|section)\\b", Pattern.CASE_INSENSITIVE).matcher(prefixSlice);
                while (bm.find()) {
                    bestBlockStart = searchFrom + bm.start();
                }
                if (bestBlockStart < 0) {
                    return null;
                }
                int bOpenEnd = html.indexOf('>', bestBlockStart);
                if (bOpenEnd < tagStart) {
                    tagStart = bestBlockStart;
                    openEnd = bOpenEnd;
                    nameEnd = tagStart + 1;
                    while (nameEnd < openEnd && !Character.isWhitespace(html.charAt(nameEnd))) {
                        nameEnd++;
                    }
                    tagName = html.substring(tagStart + 1, nameEnd).toLowerCase(java.util.Locale.US);
                } else {
                    return null;
                }
            } else if (!"aside".equals(tagName) && !"div".equals(tagName) && !"p".equals(tagName)
                    && !"li".equals(tagName) && !"dd".equals(tagName) && !"section".equals(tagName)) {
                return null;
            }
            int bodyStart = openEnd + 1;
            int limit = Math.min(html.length(), bodyStart + 6000);
            int depth = 1;
            int bodyEnd = -1;
            int cursor = bodyStart;
            int tagLen = tagName.length();
            while (cursor < limit) {
                int lt = html.indexOf('<', cursor);
                if (lt < 0 || lt >= limit) break;
                int gt = html.indexOf('>', lt + 1);
                if (gt < 0) break;
                if (lt + 1 + tagLen < gt && html.charAt(lt + 1) == '/' && html.regionMatches(true, lt + 2, tagName, 0, tagLen)) {
                    char after = html.charAt(lt + 2 + tagLen);
                    if (after == '>' || Character.isWhitespace(after)) {
                        depth--;
                        if (depth == 0) {
                            bodyEnd = lt;
                            break;
                        }
                    }
                } else if (lt + 1 + tagLen <= gt && html.regionMatches(true, lt + 1, tagName, 0, tagLen)) {
                    char after = html.charAt(lt + 1 + tagLen);
                    if ((after == '>' || Character.isWhitespace(after)) && html.charAt(gt - 1) != '/') {
                        depth++;
                    }
                }
                cursor = gt + 1;
            }
            if (bodyEnd <= bodyStart) {
                return null;
            }
            String rawInner = html.substring(bodyStart, bodyEnd);
            if (rawInner.indexOf('<') >= 0) {
                rawInner = FOOTNOTE_BACKLINK_PATTERN.matcher(rawInner).replaceAll("");
            }
            String clean = FOOTNOTE_WS_PATTERN.matcher(cleanHtmlText(rawInner, targetHref)).replaceAll(" ").trim();
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

    private static String extractTagInnerText(String html, int tagStart, String tagName) {
        int openEnd = html.indexOf('>', tagStart);
        if (openEnd <= tagStart || html.charAt(openEnd - 1) == '/') return "";
        int bodyStart = openEnd + 1;
        int limit = Math.min(html.length(), bodyStart + 4000);
        int end = findMatchingCloseTagEnd(html, bodyStart, tagName, limit);
        if (end <= bodyStart) return "";
        String rawInner = html.substring(bodyStart, end);
        if (rawInner.indexOf('<') >= 0) {
            rawInner = FOOTNOTE_BACKLINK_PATTERN.matcher(rawInner).replaceAll("");
        }
        return FOOTNOTE_WS_PATTERN.matcher(cleanHtmlText(rawInner, "")).replaceAll(" ").trim();
    }

    private static String stripFootnoteNumberPrefix(String rawClean, String bareMarker) {
        if (rawClean == null) return "";
        String clean = rawClean.trim();
        if (bareMarker != null && !bareMarker.isEmpty()) {
            String bracketPrefix = "[" + bareMarker + "]";
            if (clean.startsWith(bracketPrefix)) clean = clean.substring(bracketPrefix.length()).trim();
            else if (clean.startsWith(bareMarker + ".")) clean = clean.substring(bareMarker.length() + 1).trim();
            else if (clean.startsWith(bareMarker + ")")) clean = clean.substring(bareMarker.length() + 1).trim();
            else if (clean.startsWith(bareMarker + " ")) clean = clean.substring(bareMarker.length() + 1).trim();
        }
        if (clean.startsWith("^") || clean.startsWith("↑") || clean.startsWith("←")) {
            clean = clean.substring(1).trim();
        }
        return clean;
    }

    public static final class CfiDomLocation {
        public final int spineIndex;
        public final String opfItemId;
        public final String blockSnippet;
        public final String inlineSnippet;
        public final int charOffsetInInline;
        public final int charPrefixInBlock;

        public CfiDomLocation(int spineIndex, String opfItemId, String blockSnippet,
                              String inlineSnippet, int charOffsetInInline, int charPrefixInBlock) {
            this.spineIndex = spineIndex;
            this.opfItemId = opfItemId != null ? opfItemId : "";
            this.blockSnippet = blockSnippet != null ? blockSnippet : "";
            this.inlineSnippet = inlineSnippet != null ? inlineSnippet : "";
            this.charOffsetInInline = Math.max(0, charOffsetInInline);
            this.charPrefixInBlock = Math.max(0, charPrefixInBlock);
        }

        public boolean hasSnippet() {
            return !blockSnippet.isEmpty() || !inlineSnippet.isEmpty();
        }
    }

    public static final class DomBlockStep {
        public final String cfiElemPath;
        public final String normalizedPrefix;

        public DomBlockStep(String cfiElemPath, String normalizedPrefix) {
            this.cfiElemPath = cfiElemPath;
            this.normalizedPrefix = normalizedPrefix;
        }
    }

    private static boolean isHtmlVoidTag(String tagName) {
        return "br".equals(tagName) || "hr".equals(tagName) || "img".equals(tagName)
                || "meta".equals(tagName) || "link".equals(tagName) || "input".equals(tagName)
                || "source".equals(tagName) || "wbr".equals(tagName) || "col".equals(tagName)
                || "area".equals(tagName) || "base".equals(tagName) || "param".equals(tagName);
    }

    private static boolean isDomLeafBlockTag(String tagName) {
        return "p".equals(tagName) || "blockquote".equals(tagName) || "li".equals(tagName)
                || "dd".equals(tagName) || "dt".equals(tagName) || "pre".equals(tagName)
                || (tagName.length() == 2 && tagName.charAt(0) == 'h' && tagName.charAt(1) >= '1' && tagName.charAt(1) <= '6');
    }

    private static String normalizeSnippetText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String cleaned = cleanHtmlText(raw, "");
        return cleaned.replaceAll("\\[IMG:[^\\]]+\\]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static int findBodyInnerStart(String html) {
        Matcher m = Pattern.compile("(?is)<body\\b[^>]*>").matcher(html);
        if (m.find()) return m.end();
        return -1;
    }

    private static int findBodyInnerEnd(String html, int bodyStart) {
        Matcher m = Pattern.compile("(?is)</body>").matcher(html);
        if (m.find(Math.max(0, bodyStart))) return m.start();
        return html.length();
    }

    private static int findMatchingCloseTagEnd(String html, int afterOpenGt, String tagName, int limitEnd) {
        int depth = 1;
        int len = Math.min(html.length(), limitEnd);
        int cursor = afterOpenGt;
        while (cursor < len) {
            int lt = html.indexOf('<', cursor);
            if (lt < 0 || lt >= len) break;
            if (html.startsWith("<!--", lt)) {
                int endCom = html.indexOf("-->", lt + 4);
                cursor = (endCom >= 0) ? Math.min(len, endCom + 3) : len;
                continue;
            }
            if (html.startsWith("<![CDATA[", lt)) {
                int endC = html.indexOf("]]>", lt + 9);
                cursor = (endC >= 0) ? Math.min(len, endC + 3) : len;
                continue;
            }
            int gt = -1;
            char q = '\0';
            for (int k = lt + 1; k < len; k++) {
                char c = html.charAt(k);
                if (q != '\0') {
                    if (c == q) q = '\0';
                } else if (c == '"' || c == '\'') {
                    q = c;
                } else if (c == '>') {
                    gt = k;
                    break;
                }
            }
            if (gt < 0) break;
            String rawTag = html.substring(lt + 1, gt).trim();
            if (rawTag.startsWith("!")) {
                cursor = gt + 1;
                continue;
            }
            boolean isClose = rawTag.startsWith("/");
            boolean isSelfClose = rawTag.endsWith("/");
            String inner = isClose ? rawTag.substring(1).trim() : rawTag;
            if (isSelfClose && inner.endsWith("/")) inner = inner.substring(0, inner.length() - 1).trim();
            int ws = -1;
            for (int k = 0; k < inner.length(); k++) {
                if (Character.isWhitespace(inner.charAt(k))) { ws = k; break; }
            }
            String nm = (ws > 0 ? inner.substring(0, ws) : inner).toLowerCase(java.util.Locale.US);
            if (nm.equals(tagName)) {
                if (isClose) {
                    depth--;
                    if (depth == 0) return lt;
                } else if (!isSelfClose && !isHtmlVoidTag(nm)) {
                    depth++;
                }
            }
            cursor = gt + 1;
        }
        return len;
    }

    /**
     * Разбирает EPUB 3 CFI относительно реального XHTML DOM внутри локального .epub,
     * чтобы точно локализовать целевой абзац и символ даже при наличии обёрток (.section1, .titleblock)
     * или вложенных тегов (&lt;em&gt;, &lt;a&gt;, &lt;span&gt;).
     */
    public static CfiDomLocation resolveCfiInEpub(File epubFile, String rawCfi) {
        if (epubFile == null || !epubFile.exists() || rawCfi == null || rawCfi.trim().isEmpty()) {
            return null;
        }
        ZipFile zip = null;
        try {
            String clean = rawCfi.trim();
            String[] parts = clean.split("!", 2);
            String spinePart = parts[0];
            String elemPart = parts.length > 1 ? parts[1] : "";

            int spineIndex = -1;
            String opfItemId = "";
            Matcher mSpine = Pattern.compile("/(\\d+)(?:\\[([^\\]]+)\\])?").matcher(spinePart);
            int lastSpineNum = -1;
            while (mSpine.find()) {
                try {
                    lastSpineNum = Integer.parseInt(mSpine.group(1));
                    String idG = mSpine.group(2);
                    if (idG != null && !idG.isEmpty()) opfItemId = idG;
                } catch (Exception ignored) {}
            }
            if (lastSpineNum >= 2) {
                spineIndex = (lastSpineNum / 2) - 1;
            }
            if (spineIndex < 0 && opfItemId.isEmpty()) return null;

            zip = new ZipFile(epubFile);
            String opfPath = findOpfPath(zip);
            if (opfPath == null || opfPath.isEmpty()) return null;
            String baseDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            Map<String, String> manifestMap = new HashMap<>();
            List<SpineItem> spineItems = parseOpfSpineDetailed(zip, opfPath, baseDir, manifestMap);
            String targetHref = null;
            if (!opfItemId.isEmpty()) {
                for (SpineItem si : spineItems) {
                    if (opfItemId.equals(si.idref)) {
                        targetHref = si.href;
                        if (spineIndex < 0) spineIndex = si.originalOpfSpineIndex;
                        break;
                    }
                }
                if (targetHref == null && manifestMap.containsKey(opfItemId)) {
                    targetHref = resolveZipPath(baseDir, manifestMap.get(opfItemId));
                }
            }
            if (targetHref == null && spineIndex >= 0) {
                for (SpineItem si : spineItems) {
                    if (si.originalOpfSpineIndex == spineIndex) {
                        targetHref = si.href;
                        if (opfItemId.isEmpty()) opfItemId = si.idref;
                        break;
                    }
                }
                if (targetHref == null && spineIndex < spineItems.size()) {
                    targetHref = spineItems.get(spineIndex).href;
                }
            }
            if (targetHref == null || elemPart.isEmpty()) return null;

            String html = readEntryRawHtml(zip, targetHref);
            if (html == null || html.isEmpty()) return null;

            int charOff = 0;
            String elemPath = elemPart;
            int colonIdx = elemPart.indexOf(':');
            if (colonIdx >= 0) {
                elemPath = elemPart.substring(0, colonIdx);
                String offDigits = elemPart.substring(colonIdx + 1).replaceAll("[^0-9]", "");
                if (!offDigits.isEmpty()) {
                    try { charOff = Integer.parseInt(offDigits); } catch (Exception ignored) {}
                }
            }

            List<Integer> targetChildIndices = new ArrayList<>();
            String lastIdAssertion = "";
            int lastIdElementStepIdx = -1;
            Matcher mStep = Pattern.compile("/(\\d+)(?:\\[([^\\]]+)\\])?").matcher(elemPath);
            boolean skippedBodyRoot = false;
            while (mStep.find()) {
                int stepNum = Integer.parseInt(mStep.group(1));
                String stepId = mStep.group(2);
                if (!skippedBodyRoot && stepNum == 4) {
                    skippedBodyRoot = true;
                    if (stepId != null && !stepId.isEmpty()) {
                        lastIdAssertion = stepId;
                        lastIdElementStepIdx = -1;
                    }
                    continue;
                }
                skippedBodyRoot = true;
                if (stepNum >= 2 && stepNum % 2 == 0) {
                    targetChildIndices.add(stepNum / 2);
                    if (stepId != null && !stepId.isEmpty()) {
                        lastIdAssertion = stepId;
                        lastIdElementStepIdx = targetChildIndices.size() - 1;
                    }
                } else if (stepId != null && !stepId.isEmpty()) {
                    lastIdAssertion = stepId;
                }
            }

            int bodyStart = findBodyInnerStart(html);
            if (bodyStart < 0) return null;
            int bodyEnd = findBodyInnerEnd(html, bodyStart);

            int traversalStart = bodyStart;
            int traversalEnd = bodyEnd;
            List<Integer> activeChildIndices = targetChildIndices;

            // Если присутствует ID-утверждение ([anchor_id]):
            // на финальном шаге возвращаем точный элемент; на промежуточном — используем его как корень DOM-обхода
            if (!lastIdAssertion.isEmpty()) {
                int anchorStart = findAnchorOffset(html, lastIdAssertion);
                if (anchorStart >= bodyStart && anchorStart < bodyEnd) {
                    int openGt = html.indexOf('>', anchorStart);
                    if (openGt > anchorStart) {
                        int sp = anchorStart + 1;
                        while (sp < openGt && !Character.isWhitespace(html.charAt(sp))) sp++;
                        String tName = html.substring(anchorStart + 1, sp).toLowerCase(java.util.Locale.US);
                        int closeLt = (openGt > 0 && html.charAt(openGt - 1) == '/')
                                ? openGt : findMatchingCloseTagEnd(html, openGt + 1, tName, bodyEnd);
                        boolean idIsFinalStep = targetChildIndices.isEmpty()
                                || (lastIdElementStepIdx >= targetChildIndices.size() - 1);
                        if (idIsFinalStep) {
                            String rawSlice = html.substring(anchorStart, Math.min(bodyEnd, Math.max(openGt + 1, closeLt)));
                            String snip = normalizeSnippetText(rawSlice);
                            if (!snip.isEmpty()) {
                                return new CfiDomLocation(spineIndex, opfItemId, snip, snip, charOff, 0);
                            }
                        } else if (lastIdElementStepIdx >= 0 && lastIdElementStepIdx + 1 < targetChildIndices.size()
                                && closeLt > openGt + 1) {
                            traversalStart = openGt + 1;
                            traversalEnd = Math.min(bodyEnd, closeLt);
                            activeChildIndices = new ArrayList<>(
                                    targetChildIndices.subList(lastIdElementStepIdx + 1, targetChildIndices.size()));
                        }
                    }
                }
            }

            if (activeChildIndices.isEmpty()) return null;

            int[] childCounters = new int[64];
            String[] tagStack = new String[64];
            int[] openStartStack = new int[64];
            int[] innerStartStack = new int[64];
            int depth = 0;
            int cursor = traversalStart;
            int targetMatchStart = -1;
            int targetMatchInnerStart = -1;
            int targetMatchEnd = -1;
            int blockStart = -1;
            int blockInnerStart = -1;
            int blockEnd = -1;
            int targetDepthNeeded = activeChildIndices.size() - 1;
            boolean pathPrefixMatches = true;

            while (cursor < traversalEnd) {
                int lt = html.indexOf('<', cursor);
                if (lt < 0 || lt >= traversalEnd) break;
                if (html.startsWith("<!--", lt)) {
                    int ec = html.indexOf("-->", lt + 4);
                    cursor = (ec >= 0) ? Math.min(traversalEnd, ec + 3) : traversalEnd;
                    continue;
                }
                if (html.startsWith("<![CDATA[", lt)) {
                    int ec = html.indexOf("]]>", lt + 9);
                    cursor = (ec >= 0) ? Math.min(traversalEnd, ec + 3) : traversalEnd;
                    continue;
                }
                int gt = -1;
                char q = '\0';
                for (int k = lt + 1; k < traversalEnd; k++) {
                    char c = html.charAt(k);
                    if (q != '\0') { if (c == q) q = '\0'; }
                    else if (c == '"' || c == '\'') q = c;
                    else if (c == '>') { gt = k; break; }
                }
                if (gt < 0) break;

                String rawTag = html.substring(lt + 1, gt).trim();
                cursor = gt + 1;
                if (rawTag.isEmpty() || rawTag.startsWith("!") || rawTag.startsWith("?")) continue;

                boolean isClose = rawTag.startsWith("/");
                boolean isSelfClose = rawTag.endsWith("/");
                String inner = isClose ? rawTag.substring(1).trim() : rawTag;
                if (isSelfClose && inner.endsWith("/")) inner = inner.substring(0, inner.length() - 1).trim();
                int ws = -1;
                for (int k = 0; k < inner.length(); k++) {
                    if (Character.isWhitespace(inner.charAt(k))) { ws = k; break; }
                }
                String tagName = (ws > 0 ? inner.substring(0, ws) : inner).toLowerCase(java.util.Locale.US);

                if (isClose) {
                    if (depth > 0) {
                        for (int s = depth - 1; s >= 0; s--) {
                            if (tagName.equals(tagStack[s])) {
                                depth = s;
                                pathPrefixMatches = (depth <= targetDepthNeeded);
                                break;
                            }
                        }
                    }
                    continue;
                }

                if ("script".equals(tagName) || "style".equals(tagName)) {
                    if (!isSelfClose) {
                        cursor = Math.min(traversalEnd, findMatchingCloseTagEnd(html, cursor, tagName, traversalEnd) + tagName.length() + 3);
                    }
                    continue;
                }

                if (depth < 64) {
                    childCounters[depth]++;
                    boolean stepMatches = pathPrefixMatches && depth <= targetDepthNeeded
                            && childCounters[depth] == activeChildIndices.get(depth);

                    if (stepMatches && depth == targetDepthNeeded) {
                        targetMatchStart = lt;
                        targetMatchInnerStart = cursor;
                        targetMatchEnd = (isSelfClose || isHtmlVoidTag(tagName))
                                ? cursor : findMatchingCloseTagEnd(html, cursor, tagName, traversalEnd);
                        break;
                    }

                    if (!isSelfClose && !isHtmlVoidTag(tagName)) {
                        tagStack[depth] = tagName;
                        openStartStack[depth] = lt;
                        innerStartStack[depth] = cursor;
                        if (stepMatches && isDomLeafBlockTag(tagName)) {
                            blockStart = lt;
                            blockInnerStart = cursor;
                            blockEnd = findMatchingCloseTagEnd(html, cursor, tagName, traversalEnd);
                        }
                        depth++;
                        if (depth < 64) childCounters[depth] = 0;
                        pathPrefixMatches = stepMatches;
                    } else if (stepMatches) {
                        // Шаг уперся в одиночный тег (<img/> и т.п.)
                        targetMatchStart = lt;
                        targetMatchInnerStart = cursor;
                        targetMatchEnd = cursor;
                        break;
                    }
                }
            }

            if (targetMatchStart >= 0 && targetMatchEnd >= targetMatchStart) {
                String inlineSnip = normalizeSnippetText(html.substring(targetMatchStart, Math.min(traversalEnd, targetMatchEnd)));
                String blockSnip = inlineSnip;
                int prefixInBlock = 0;
                if (blockStart >= 0 && blockEnd >= blockStart && targetMatchStart >= blockInnerStart && targetMatchStart <= blockEnd) {
                    blockSnip = normalizeSnippetText(html.substring(blockStart, Math.min(traversalEnd, blockEnd)));
                    prefixInBlock = normalizeSnippetText(html.substring(blockInnerStart, targetMatchStart)).length();
                }
                if (blockSnip.length() > 140) blockSnip = blockSnip.substring(0, 140);
                if (inlineSnip.length() > 140) inlineSnip = inlineSnip.substring(0, 140);
                return new CfiDomLocation(spineIndex, opfItemId, blockSnip, inlineSnip, charOff, prefixInBlock);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error resolving CFI in EPUB DOM: " + rawCfi, t);
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Строит таблицу соответствия DOM-блоков XHTML-файла главы и их точных EPUB 3 CFI-путей
     * (/4/..., включая вложенные обёртки .section1) для обратного сохранения позиции на сервер.
     */
    public static List<DomBlockStep> extractChapterDomBlocks(File epubFile, int opfSpineIndex, String opfItemId) {
        List<DomBlockStep> blocks = new ArrayList<>();
        if (epubFile == null || !epubFile.exists()) return blocks;
        ZipFile zip = null;
        try {
            zip = new ZipFile(epubFile);
            String opfPath = findOpfPath(zip);
            if (opfPath == null || opfPath.isEmpty()) return blocks;
            String baseDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            Map<String, String> manifestMap = new HashMap<>();
            List<SpineItem> spineItems = parseOpfSpineDetailed(zip, opfPath, baseDir, manifestMap);

            String targetHref = null;
            if (opfItemId != null && !opfItemId.isEmpty()) {
                for (SpineItem si : spineItems) {
                    if (opfItemId.equals(si.idref)) {
                        targetHref = si.href;
                        break;
                    }
                }
                if (targetHref == null && manifestMap.containsKey(opfItemId)) {
                    targetHref = resolveZipPath(baseDir, manifestMap.get(opfItemId));
                }
            }
            if (targetHref == null && opfSpineIndex >= 0) {
                for (SpineItem si : spineItems) {
                    if (si.originalOpfSpineIndex == opfSpineIndex) {
                        targetHref = si.href;
                        break;
                    }
                }
                if (targetHref == null && opfSpineIndex < spineItems.size()) {
                    targetHref = spineItems.get(opfSpineIndex).href;
                }
            }
            if (targetHref == null) return blocks;

            String html = readEntryRawHtml(zip, targetHref);
            if (html == null || html.isEmpty()) return blocks;
            int bodyStart = findBodyInnerStart(html);
            if (bodyStart < 0) return blocks;
            int bodyEnd = findBodyInnerEnd(html, bodyStart);

            int[] childCounters = new int[64];
            String[] tagStack = new String[64];
            int depth = 0;
            int cursor = bodyStart;

            while (cursor < bodyEnd) {
                int lt = html.indexOf('<', cursor);
                if (lt < 0 || lt >= bodyEnd) break;
                if (html.startsWith("<!--", lt)) {
                    int ec = html.indexOf("-->", lt + 4);
                    cursor = (ec >= 0) ? Math.min(bodyEnd, ec + 3) : bodyEnd;
                    continue;
                }
                if (html.startsWith("<![CDATA[", lt)) {
                    int ec = html.indexOf("]]>", lt + 9);
                    cursor = (ec >= 0) ? Math.min(bodyEnd, ec + 3) : bodyEnd;
                    continue;
                }
                int gt = -1;
                char q = '\0';
                for (int k = lt + 1; k < bodyEnd; k++) {
                    char c = html.charAt(k);
                    if (q != '\0') { if (c == q) q = '\0'; }
                    else if (c == '"' || c == '\'') q = c;
                    else if (c == '>') { gt = k; break; }
                }
                if (gt < 0) break;

                String rawTag = html.substring(lt + 1, gt).trim();
                cursor = gt + 1;
                if (rawTag.isEmpty() || rawTag.startsWith("!") || rawTag.startsWith("?")) continue;

                boolean isClose = rawTag.startsWith("/");
                boolean isSelfClose = rawTag.endsWith("/");
                String inner = isClose ? rawTag.substring(1).trim() : rawTag;
                if (isSelfClose && inner.endsWith("/")) inner = inner.substring(0, inner.length() - 1).trim();
                int ws = -1;
                for (int k = 0; k < inner.length(); k++) {
                    if (Character.isWhitespace(inner.charAt(k))) { ws = k; break; }
                }
                String tagName = (ws > 0 ? inner.substring(0, ws) : inner).toLowerCase(java.util.Locale.US);

                if (isClose) {
                    if (depth > 0) {
                        for (int s = depth - 1; s >= 0; s--) {
                            if (tagName.equals(tagStack[s])) {
                                depth = s;
                                break;
                            }
                        }
                    }
                    continue;
                }

                if ("script".equals(tagName) || "style".equals(tagName)) {
                    if (!isSelfClose) {
                        cursor = Math.min(bodyEnd, findMatchingCloseTagEnd(html, cursor, tagName, bodyEnd) + tagName.length() + 3);
                    }
                    continue;
                }

                if (depth < 64) {
                    childCounters[depth]++;
                    if (!isSelfClose && !isHtmlVoidTag(tagName)) {
                        if (isDomLeafBlockTag(tagName)) {
                            int blockEnd = findMatchingCloseTagEnd(html, cursor, tagName, bodyEnd);
                            String snip = normalizeSnippetText(html.substring(cursor, Math.min(bodyEnd, blockEnd)));
                            if (!snip.isEmpty()) {
                                StringBuilder pathSb = new StringBuilder("/4");
                                for (int d = 0; d <= depth; d++) {
                                    pathSb.append('/').append(childCounters[d] * 2);
                                }
                                String pref = snip.length() > 64 ? snip.substring(0, 64) : snip;
                                blocks.add(new DomBlockStep(pathSb.toString(), pref));
                            }
                        }
                        tagStack[depth] = tagName;
                        depth++;
                        if (depth < 64) childCounters[depth] = 0;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error extracting DOM blocks for CFI", t);
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (Exception ignored) {}
            }
        }
        return blocks;
    }

    /**
     * Находит точное смещение в отформатированном rawText главы по сниппету текста из DOM-узла CFI.
     */
    public static int findSnippetOffsetInRawText(String rawText, CfiDomLocation domLoc) {
        if (rawText == null || rawText.isEmpty() || domLoc == null || !domLoc.hasSnippet()) {
            return -1;
        }
        String probe = !domLoc.blockSnippet.isEmpty() ? domLoc.blockSnippet : domLoc.inlineSnippet;
        String probeNorm = probe.replaceAll("\\s+", " ").trim();
        if (probeNorm.length() < 4) return -1;
        String probeHead = probeNorm.length() > 42 ? probeNorm.substring(0, 42).trim() : probeNorm;
        String inlineNorm = domLoc.inlineSnippet != null ? domLoc.inlineSnippet.replaceAll("\\s+", " ").trim() : "";
        String inlineHead = inlineNorm.length() > 32 ? inlineNorm.substring(0, 32).trim() : inlineNorm;

        int pos = 0;
        int len = rawText.length();
        while (pos < len) {
            int nl = rawText.indexOf('\n', pos);
            int lineEnd = (nl >= 0) ? nl : len;
            String rawLine = rawText.substring(pos, lineEnd);
            String plainLine = EpubFormatting.stripFormattingTokens(rawLine).replaceAll("\\s+", " ").trim();
            if (!plainLine.isEmpty()) {
                boolean matchedBlock = plainLine.startsWith(probeHead)
                        || probeHead.startsWith(plainLine) && plainLine.length() >= 12
                        || (probeHead.length() >= 16 && plainLine.contains(probeHead));
                boolean matchedInline = !matchedBlock && !inlineHead.isEmpty() && inlineHead.length() >= 12 && plainLine.contains(inlineHead);
                if (matchedBlock || matchedInline) {
                    int charInPara = 0;
                    if (matchedBlock && domLoc.charPrefixInBlock > 0) {
                        charInPara = domLoc.charPrefixInBlock + domLoc.charOffsetInInline;
                    } else if (matchedInline && !inlineHead.isEmpty()) {
                        int inIdx = plainLine.indexOf(inlineHead);
                        charInPara = Math.max(0, inIdx) + domLoc.charOffsetInInline;
                    } else {
                        charInPara = domLoc.charOffsetInInline;
                    }
                    int blkPrefixLen = 0;
                    if (!rawLine.isEmpty() && rawLine.charAt(0) == EpubFormatting.BLK_START) {
                        int be = rawLine.indexOf(EpubFormatting.BLK_END);
                        if (be >= 0) blkPrefixLen = be + 1;
                    }
                    int target = pos + blkPrefixLen + Math.min(Math.max(0, rawLine.length() - blkPrefixLen - 1), Math.max(0, charInPara));
                    return Math.min(lineEnd, Math.max(pos, target));
                }
            }
            if (nl < 0) break;
            pos = nl + 1;
        }
        return -1;
    }

    private static String readStreamToString(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), "UTF-8");
    }
}
