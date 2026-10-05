package com.onyx.yandexbooks.core.epub;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * Извлекает подлинное оглавление (NCX / NAV), поддерживает якорную нарезку глав и частей (#anchor),
 * гарантирует сохранение всех частей книг (включая части, расположенные в общем spine-файле),
 * нарезает однофайловые книги по заголовкам h1/h2 и полностью очищает артефакты [OBJ].
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
        public String rawHref;     // Полная ссылка (например: text/part1.xhtml#part4)
        public String fileHref;    // Путь к файлу без якоря (например: text/part1.xhtml)
        public String anchor;      // Якорь (например: part4) или null

        public TocItem(String title, String rawHref) {
            this.title = (title != null) ? title.trim() : "";
            this.rawHref = (rawHref != null) ? rawHref.trim() : "";
            if (this.rawHref.contains("#")) {
                int hashIdx = this.rawHref.indexOf('#');
                this.fileHref = this.rawHref.substring(0, hashIdx).trim();
                this.anchor = this.rawHref.substring(hashIdx + 1).trim();
            } else {
                this.fileHref = this.rawHref;
                this.anchor = null;
            }
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

            // 4. Группировка файлов spine по оглавлению TOC (с поддержкой якорей и срезов)
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
     * Сборка глав на основе оглавления TOC с полной поддержкой якорных переходов (#anchor).
     * Корректно разделяет главы и части, расположенные в одном HTML-файле,
     * объединяет связанные файлы spine и гарантирует, что ни одна часть (например, Часть 4) не потеряется.
     */
    private static List<ChapterData> assembleChaptersFromToc(ZipFile zip, List<String> spineHrefs, List<TocItem> tocItems) {
        List<ChapterData> chapters = new ArrayList<>();

        class TocSpinePoint {
            TocItem item;
            int spineIndex;
            int offsetInFile = -1;
            int originalOrder;

            TocSpinePoint(TocItem item, int spineIndex, int originalOrder) {
                this.item = item;
                this.spineIndex = spineIndex;
                this.originalOrder = originalOrder;
            }
        }

        List<TocSpinePoint> matched = new ArrayList<>();
        for (int i = 0; i < tocItems.size(); i++) {
            TocItem ti = tocItems.get(i);
            int idx = findSpineIndex(spineHrefs, ti.fileHref);
            if (idx < 0 && ti.rawHref != null) {
                idx = findSpineIndex(spineHrefs, ti.rawHref);
            }
            if (idx >= 0) {
                matched.add(new TocSpinePoint(ti, idx, i));
            }
        }

        if (matched.isEmpty()) {
            return chapters;
        }

        // Если в оглавлении всего 1 элемент:
        if (matched.size() <= 1) {
            // Если файлов spine несколько, нарезаем по файлам
            if (spineHrefs.size() > 1) {
                return assembleChaptersFallback(zip, spineHrefs);
            }
            // Если и spine всего 1, пробуем нарезать по заголовкам h1/h2
            List<ChapterData> byHeadings = splitSingleFileByHeadings(zip, spineHrefs.get(0));
            if (byHeadings != null && byHeadings.size() > 1) {
                return byHeadings;
            }
        }

        // Читаем сырой HTML и находим смещения якорей/заголовков для каждого элемента
        Map<Integer, String> spineHtmlCache = new HashMap<>();
        for (TocSpinePoint pt : matched) {
            String rawHtml = spineHtmlCache.get(pt.spineIndex);
            if (rawHtml == null) {
                rawHtml = readEntryRawHtml(zip, spineHrefs.get(pt.spineIndex));
                if (rawHtml == null) rawHtml = "";
                spineHtmlCache.put(pt.spineIndex, rawHtml);
            }

            int offset = -1;
            if (pt.item.anchor != null) {
                offset = findAnchorOffset(rawHtml, pt.item.anchor);
            }
            if (offset < 0 && pt.item.title != null) {
                offset = findHeadingByTitleOffset(rawHtml, pt.item.title);
            }
            pt.offsetInFile = offset;
        }

        // Для каждого spine-файла упорядочиваем элементы по смещению, если смещения найдены
        Collections.sort(matched, new Comparator<TocSpinePoint>() {
            @Override
            public int compare(TocSpinePoint a, TocSpinePoint b) {
                if (a.spineIndex != b.spineIndex) {
                    return Integer.compare(a.spineIndex, b.spineIndex);
                }
                if (a.offsetInFile >= 0 && b.offsetInFile >= 0) {
                    return Integer.compare(a.offsetInFile, b.offsetInFile);
                }
                return Integer.compare(a.originalOrder, b.originalOrder);
            }
        });

        // 1. Фронт-материалы до первой главы TOC (титульный лист, выходные данные)
        TocSpinePoint first = matched.get(0);
        StringBuilder frontSb = new StringBuilder();
        for (int s = 0; s < first.spineIndex; s++) {
            String text = readEntryText(zip, spineHrefs.get(s));
            if (text != null && !text.trim().isEmpty()) {
                if (frontSb.length() > 0) frontSb.append("\n\n");
                frontSb.append(text.trim());
            }
        }
        if (first.offsetInFile > 300) {
            String firstFileHtml = spineHtmlCache.get(first.spineIndex);
            if (firstFileHtml != null && first.offsetInFile <= firstFileHtml.length()) {
                String leading = cleanHtmlText(firstFileHtml.substring(0, first.offsetInFile));
                if (leading.length() > 40) {
                    if (frontSb.length() > 0) frontSb.append("\n\n");
                    frontSb.append(leading);
                }
            }
        }
        if (frontSb.length() > 30) {
            ChapterData cd = new ChapterData();
            cd.id = "ch_0";
            cd.title = "Начало книги";
            cd.textContent = frontSb.toString();
            chapters.add(cd);
        }

        // 2. Сборка каждой главы с точными границами
        for (int m = 0; m < matched.size(); m++) {
            TocSpinePoint cur = matched.get(m);
            int startSpine = cur.spineIndex;
            int startOffset = Math.max(0, cur.offsetInFile);

            int endSpine;
            int endOffset;
            if (m + 1 < matched.size()) {
                TocSpinePoint next = matched.get(m + 1);
                endSpine = next.spineIndex;
                endOffset = next.offsetInFile;
            } else {
                endSpine = spineHrefs.size() - 1;
                endOffset = -1;
            }

            StringBuilder chSb = new StringBuilder();

            for (int s = startSpine; s <= endSpine; s++) {
                if (s < 0 || s >= spineHrefs.size()) continue;

                String fileHtml = spineHtmlCache.get(s);
                if (fileHtml == null) {
                    fileHtml = readEntryRawHtml(zip, spineHrefs.get(s));
                    if (fileHtml == null) fileHtml = "";
                    spineHtmlCache.put(s, fileHtml);
                }

                int from = (s == startSpine) ? startOffset : 0;
                int to;
                if (s == endSpine && endOffset >= 0 && endOffset <= fileHtml.length()) {
                    to = endOffset;
                } else {
                    to = fileHtml.length();
                }

                if (from < to && to <= fileHtml.length()) {
                    String slice = fileHtml.substring(from, to);
                    String clean = cleanHtmlText(slice);
                    if (!clean.isEmpty()) {
                        if (chSb.length() > 0) chSb.append("\n\n");
                        chSb.append(clean);
                    }
                }
            }

            String fullText = chSb.toString().trim();
            // Если текст пуст (например, якорь стоял в самом конце файла), пробуем взять целый файл
            if (fullText.isEmpty()) {
                String fallbackText = readEntryText(zip, spineHrefs.get(startSpine));
                if (fallbackText != null) fullText = fallbackText.trim();
            }

            if (!fullText.isEmpty()) {
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

    /**
     * Разделение единого большого XHTML-файла по тегам h1/h2 для книг без детального TOC.
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
            if (headings.get(0).start > 100) {
                String front = cleanHtmlText(rawHtml.substring(0, headings.get(0).start));
                if (front.length() > 30) {
                    ChapterData cd0 = new ChapterData();
                    cd0.id = "ch_0";
                    cd0.title = "Начало книги";
                    cd0.textContent = front;
                    list.add(cd0);
                }
            }

            for (int i = 0; i < headings.size(); i++) {
                int from = headings.get(i).start;
                int to = (i + 1 < headings.size()) ? headings.get(i + 1).start : rawHtml.length();
                String chHtml = rawHtml.substring(from, to);
                String clean = cleanHtmlText(chHtml);
                if (clean.length() > 10) {
                    ChapterData cd = new ChapterData();
                    cd.id = "ch_" + (list.size() + 1);
                    cd.title = headings.get(i).title;
                    cd.textContent = clean;
                    list.add(cd);
                }
            }
        }
        return list;
    }

    private static List<ChapterData> assembleChaptersFallback(ZipFile zip, List<String> chapterHrefs) {
        List<ChapterData> result = new ArrayList<>();
        int chapterIndex = 1;
        for (String href : chapterHrefs) {
            String cleanText = readEntryText(zip, href);
            if (cleanText != null && cleanText.trim().length() > 20) {
                String rawHtml = readEntryRawHtml(zip, href);
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

    public static int findAnchorOffset(String html, String anchor) {
        if (html == null || anchor == null || anchor.trim().isEmpty()) return -1;
        String cleanAnchor = anchor.trim();
        if (cleanAnchor.startsWith("#")) {
            cleanAnchor = cleanAnchor.substring(1).trim();
        }
        if (cleanAnchor.isEmpty()) return -1;

        // 1. Поиск id="anchor" или name="anchor" с кавычками
        Pattern p = Pattern.compile("(?:id|name)\\s*=\\s*[\"']" + Pattern.quote(cleanAnchor) + "[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) {
            return m.start();
        }

        // 2. Поиск без кавычек
        Pattern p2 = Pattern.compile("(?:id|name)\\s*=\\s*" + Pattern.quote(cleanAnchor) + "([\\s>]|$)", Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(html);
        if (m2.find()) {
            return m2.start();
        }

        // 3. Прямое строковое вхождение
        int idx = html.indexOf("\"" + cleanAnchor + "\"");
        if (idx >= 0) return idx;

        idx = html.indexOf("'" + cleanAnchor + "'");
        if (idx >= 0) return idx;

        return -1;
    }

    public static int findHeadingByTitleOffset(String html, String title) {
        if (html == null || title == null || title.trim().isEmpty()) return -1;
        String cleanTitle = title.trim();
        if (cleanTitle.length() < 3) return -1;

        String search = cleanTitle;
        if (search.length() > 30) {
            search = search.substring(0, 30);
        }
        Pattern p = Pattern.compile("<(?:h[1-6]|p|div)[^>]*>\\s*[^<]*?" + Pattern.quote(search), Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) {
            return m.start();
        }

        return html.indexOf(search);
    }

    private static String readEntryRawHtml(ZipFile zip, String href) {
        ZipEntry entry = findZipEntry(zip, href);
        if (entry == null) return null;
        try (InputStream is = zip.getInputStream(entry)) {
            return readStreamToString(is);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readEntryText(ZipFile zip, String href) {
        String raw = readEntryRawHtml(zip, href);
        if (raw == null) return null;
        return cleanHtmlText(raw);
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
            Pattern pattern = Pattern.compile(
                    "<navLabel[^>]*>\\s*<text[^>]*>(.*?)</text>\\s*</navLabel>\\s*<content\\s+[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"']",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE
            );
            Matcher matcher = pattern.matcher(xml);

            while (matcher.find()) {
                String rawTitle = matcher.group(1);
                String cleanTitle = cleanHtmlText(rawTitle).replaceAll("\\s+", " ").trim();
                String src = matcher.group(2).trim();
                try {
                    src = URLDecoder.decode(src, "UTF-8");
                } catch (Exception ignored) {}

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
            Pattern navBlockPattern = Pattern.compile("<nav[^>]*?\\b(toc)\\b[^>]*>(.*?)</nav>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher blockMatcher = navBlockPattern.matcher(html);
            String searchHtml = blockMatcher.find() ? blockMatcher.group(2) : html;

            Pattern pattern = Pattern.compile("<a\\s+[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(searchHtml);

            while (matcher.find()) {
                String src = matcher.group(1).trim();
                try {
                    src = URLDecoder.decode(src, "UTF-8");
                } catch (Exception ignored) {}
                String rawTitle = matcher.group(2);
                String cleanTitle = cleanHtmlText(rawTitle).replaceAll("\\s+", " ").trim();
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
     * - Формирует нормальное книжное расстояние между абзацами
     * - Сохраняет пропуск одной строки только для явных смысловых разделителей автора
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

            // 7. Построчная сборка текста
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
