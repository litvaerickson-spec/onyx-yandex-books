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

        } catch (Exception e) {
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
        try {
            Pattern titlePattern = Pattern.compile("<title\\b[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher m = titlePattern.matcher(html);
            if (m.find()) {
                String t = cleanHtmlEntities(m.group(1)).trim();
                if (!t.isEmpty() && t.length() < 100) return t;
            }

            Pattern h1Pattern = Pattern.compile("<h[1-2]\\b[^>]*>(.*?)</h[1-2]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher mH1 = h1Pattern.matcher(html);
            if (mH1.find()) {
                String t = cleanHtmlEntities(mH1.group(1)).trim();
                if (!t.isEmpty() && t.length() < 100) return t;
            }
        } catch (Exception ignored) {}
        return defaultTitle;
    }

    private static String cleanHtmlText(String html) {
        if (html == null) return "";
        try {
            // Удаляем теги <script>, <style>, <head> целиком
            String stripped = html.replaceAll("(?i)<head[\\s\\S]*?</head>", "")
                                  .replaceAll("(?i)<script[\\s\\S]*?</script>", "")
                                  .replaceAll("(?i)<style[\\s\\S]*?</style>", "");

            // Преобразуем HTML сущности и разметку
            CharSequence parsed = Html.fromHtml(stripped);
            String text = parsed.toString();

            // Нормализуем переносы строк (не более двух подряд)
            text = text.replaceAll("\r\n", "\n")
                       .replaceAll("\r", "\n")
                       .replaceAll("\n{3,}", "\n\n")
                       .trim();
            return text;
        } catch (Exception e) {
            // Простейший fallback при сбое парсера
            return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        }
    }

    private static String cleanHtmlEntities(String text) {
        if (text == null) return "";
        return Html.fromHtml(text).toString().replaceAll("\\s+", " ").trim();
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
