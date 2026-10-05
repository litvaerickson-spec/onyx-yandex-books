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
 * Надежный встроенный парсер EPUB архивов для Android.
 * Работает без сторонних библиотек через стандартный java.util.zip.ZipFile.
 * Извлекает оглавление и текст глав для отображения в E-Ink ридере.
 */
public class EpubParser {

    private static final String TAG = "EpubParser";

    public static class ChapterData {
        public String id;
        public String title;
        public String textContent;
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

            // 2. Парсинг spine из OPF
            List<String> chapterHrefs = new ArrayList<>();
            if (opfPath != null) {
                chapterHrefs = parseOpfSpine(zip, opfPath, opfBaseDir);
            }

            // 3. Fallback: если spine не удалось прочесть, собираем все xhtml/html файлы
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

            // 4. Чтение текста каждой главы
            int chapterIndex = 1;
            for (String href : chapterHrefs) {
                ZipEntry entry = findZipEntry(zip, href);
                if (entry != null) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        String rawHtml = readStreamToString(is);
                        String cleanText = cleanHtmlText(rawHtml);
                        if (cleanText != null && cleanText.trim().length() > 20) {
                            ChapterData ch = new ChapterData();
                            ch.id = "ch_" + chapterIndex;
                            ch.title = extractTitle(rawHtml, "Глава " + chapterIndex);
                            ch.textContent = cleanText;
                            result.add(ch);
                            chapterIndex++;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to read chapter entry: " + href, e);
                    }
                }
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

        // Поиск любого .opf файла в архиве
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e.getName().toLowerCase().endsWith(".opf")) {
                return e.getName();
            }
        }
        return null;
    }

    private static List<String> parseOpfSpine(ZipFile zip, String opfPath, String baseDir) {
        List<String> result = new ArrayList<>();
        ZipEntry opfEntry = zip.getEntry(opfPath);
        if (opfEntry == null) return result;

        try (InputStream is = zip.getInputStream(opfEntry)) {
            String opfXml = readStreamToString(is);

            // Сопоставление id -> href из <manifest>
            Map<String, String> manifestMap = new HashMap<>();
            Pattern itemPattern = Pattern.compile("<item\\s+[^>]*?id\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher m = itemPattern.matcher(opfXml);
            while (m.find()) {
                manifestMap.put(m.group(1), m.group(2));
            }
            // Также проверяем обратный порядок атрибутов (href перед id)
            Pattern itemPatternRev = Pattern.compile("<item\\s+[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?id\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher mRev = itemPatternRev.matcher(opfXml);
            while (mRev.find()) {
                manifestMap.put(mRev.group(2), mRev.group(1));
            }

            // Порядок чтения из <spine>
            Pattern itemrefPattern = Pattern.compile("<itemref\\s+[^>]*?idref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
            Matcher mSpine = itemrefPattern.matcher(opfXml);
            while (mSpine.find()) {
                String idref = mSpine.group(1);
                String href = manifestMap.get(idref);
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

    private static ZipEntry findZipEntry(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry != null) return entry;

        // Поиск без учета регистра или со слэшами
        String lower = name.toLowerCase();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e.getName().equalsIgnoreCase(name) || e.getName().toLowerCase().endsWith(lower)) {
                return e;
            }
        }
        return null;
    }

    private static String extractTitle(String html, String defaultTitle) {
        if (html == null || html.isEmpty()) return defaultTitle;
        try {
            int h1Start = html.indexOf("<h1");
            if (h1Start == -1) h1Start = html.indexOf("<H1");
            if (h1Start != -1) {
                int closeTag = html.indexOf('>', h1Start);
                int endH1 = html.indexOf("</h1", closeTag);
                if (endH1 == -1) endH1 = html.indexOf("</H1", closeTag);
                if (closeTag != -1 && endH1 != -1 && endH1 > closeTag) {
                    String raw = html.substring(closeTag + 1, endH1);
                    String clean = cleanHtmlText(raw);
                    if (!clean.isEmpty() && clean.length() < 100) return clean;
                }
            }

            int titleStart = html.indexOf("<title");
            if (titleStart == -1) titleStart = html.indexOf("<TITLE");
            if (titleStart != -1) {
                int closeTag = html.indexOf('>', titleStart);
                int endTitle = html.indexOf("</title", closeTag);
                if (endTitle == -1) endTitle = html.indexOf("</TITLE", closeTag);
                if (closeTag != -1 && endTitle != -1 && endTitle > closeTag) {
                    String raw = html.substring(closeTag + 1, endTitle);
                    String clean = cleanHtmlText(raw);
                    if (!clean.isEmpty() && clean.length() < 100) return clean;
                }
            }
        } catch (Throwable ignored) {}
        return defaultTitle;
    }

    /**
     * Сверхбыстрый потоковый очиститель HTML для E-Ink ридеров.
     * Не использует медленный Html.fromHtml (TagSoup) и тяжелые регулярные выражения,
     * исключая зависания и OutOfMemoryError на процессорах Onyx Boox.
     */
    public static String cleanHtmlText(String html) {
        if (html == null || html.isEmpty()) return "";
        try {
            int len = html.length();
            StringBuilder sb = new StringBuilder(len);
            boolean inTag = false;

            int i = 0;
            while (i < len) {
                char c = html.charAt(i);

                if (!inTag && c == '<') {
                    // Пропускаем теги <head>...</head>, <style>...</style>, <script>...</script> целиком
                    if (i + 5 < len) {
                        String prefix = html.substring(i, Math.min(len, i + 8)).toLowerCase();
                        if (prefix.startsWith("<head") || prefix.startsWith("<style") || prefix.startsWith("<script")) {
                            String endTag = prefix.startsWith("<head") ? "</head>" :
                                            (prefix.startsWith("<style") ? "</style>" : "</script>");
                            int endIdx = html.toLowerCase().indexOf(endTag, i);
                            if (endIdx != -1) {
                                i = endIdx + endTag.length();
                                continue;
                            }
                        }
                    }

                    inTag = true;
                    // Вставляем перенос строки для структурных блоков
                    String tagPrefix = html.substring(i, Math.min(len, i + 6)).toLowerCase();
                    if (tagPrefix.startsWith("<p") || tagPrefix.startsWith("</p")
                            || tagPrefix.startsWith("<br")
                            || tagPrefix.startsWith("<div") || tagPrefix.startsWith("</div")
                            || tagPrefix.startsWith("<h") || tagPrefix.startsWith("</h")
                            || tagPrefix.startsWith("<tr") || tagPrefix.startsWith("<li")) {
                        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                            sb.append('\n');
                        }
                    }
                    i++;
                    continue;
                }

                if (inTag) {
                    if (c == '>') {
                        inTag = false;
                    }
                    i++;
                    continue;
                }

                // Декодирование типографических сущностей HTML
                if (c == '&') {
                    int semi = html.indexOf(';', i);
                    if (semi != -1 && (semi - i) <= 10) {
                        String entity = html.substring(i + 1, semi);
                        char decoded = decodeEntity(entity);
                        if (decoded != 0) {
                            sb.append(decoded);
                            i = semi + 1;
                            continue;
                        } else if (entity.equalsIgnoreCase("laquo")) {
                            sb.append('«');
                            i = semi + 1;
                            continue;
                        } else if (entity.equalsIgnoreCase("raquo")) {
                            sb.append('»');
                            i = semi + 1;
                            continue;
                        } else if (entity.equalsIgnoreCase("mdash")) {
                            sb.append('—');
                            i = semi + 1;
                            continue;
                        } else if (entity.equalsIgnoreCase("ndash")) {
                            sb.append('–');
                            i = semi + 1;
                            continue;
                        } else if (entity.equalsIgnoreCase("hellip")) {
                            sb.append('…');
                            i = semi + 1;
                            continue;
                        }
                    }
                }

                if (c == '\r') {
                    i++;
                    continue;
                }

                sb.append(c);
                i++;
            }

            // Нормализация множественных пустых строк (не более 2 подряд)
            String raw = sb.toString();
            StringBuilder out = new StringBuilder(raw.length());
            int newlineCount = 0;
            for (int k = 0; k < raw.length(); k++) {
                char ch = raw.charAt(k);
                if (ch == '\n') {
                    newlineCount++;
                    if (newlineCount <= 2) {
                        out.append('\n');
                    }
                } else {
                    newlineCount = 0;
                    out.append(ch);
                }
            }
            return out.toString().trim();
        } catch (Throwable t) {
            return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        }
    }

    private static char decodeEntity(String entity) {
        if (entity.equalsIgnoreCase("nbsp")) return ' ';
        if (entity.equalsIgnoreCase("quot")) return '"';
        if (entity.equalsIgnoreCase("apos")) return '\'';
        if (entity.equalsIgnoreCase("amp")) return '&';
        if (entity.equalsIgnoreCase("lt")) return '<';
        if (entity.equalsIgnoreCase("gt")) return '>';
        if (entity.startsWith("#x") || entity.startsWith("#X")) {
            try {
                return (char) Integer.parseInt(entity.substring(2), 16);
            } catch (Exception ignored) {}
        } else if (entity.startsWith("#")) {
            try {
                return (char) Integer.parseInt(entity.substring(1));
            } catch (Exception ignored) {}
        }
        return 0;
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
