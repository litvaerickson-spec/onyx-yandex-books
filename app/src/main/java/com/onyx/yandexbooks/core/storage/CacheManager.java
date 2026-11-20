package com.onyx.yandexbooks.core.storage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;
import android.util.LruCache;

import com.onyx.yandexbooks.core.api.YandexBooksApiClient;
import com.onyx.yandexbooks.core.api.models.Book;
import com.onyx.yandexbooks.core.api.models.Chapter;
import com.onyx.yandexbooks.core.epub.EpubParser;
import com.onyx.yandexbooks.core.ui.EinkDitherer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Менеджер кэширования и загрузки книг в формате EPUB.
 * Скачивает EPUB из Яндекс Книг, парсит главы для встроенного Canvas-ридера
 * и сохраняет файл в каталог /sdcard/Books/YandexBooks/ для внешней библиотеки Onyx.
 */
public class CacheManager {

    private static final String TAG = "CacheManager";
    private final Context context;
    private final File booksDir;
    private final YandexBooksApiClient apiClient;
    private final DatabaseHelper dbHelper;
    private final LruCache<String, Bitmap> imageCache;
    private final java.util.concurrent.ConcurrentHashMap<String, int[]> imageDimCache = new java.util.concurrent.ConcurrentHashMap<>();

    public interface DownloadProgressCallback {
        void onProgress(int downloadedCount, int totalCount);
        void onComplete();
        void onError(String message);
    }

    public interface BookReadyCallback {
        void onReady(List<Chapter> chapters);
        void onError(String message);
    }

    private static class ImageBitmapCache extends LruCache<String, Bitmap> {
        ImageBitmapCache(int maxSize) {
            super(maxSize);
        }

        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    }

    public CacheManager(Context context, YandexBooksApiClient apiClient) {
        this.context = context.getApplicationContext();
        this.booksDir = new File(this.context.getFilesDir(), "cached_books");
        if (!booksDir.exists()) {
            booksDir.mkdirs();
        }
        this.apiClient = apiClient;
        this.dbHelper = DatabaseHelper.getInstance(this.context);

        // Выделяем до 6 МБ под кэш декодированных картинок на E-Ink ридере
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = Math.max(2048, Math.min(6144, maxMemory / 8));
        this.imageCache = new ImageBitmapCache(cacheSize);
    }

    public File getEpubFile(String bookUuid) {
        File bDir = new File(booksDir, bookUuid);
        if (!bDir.exists()) {
            bDir.mkdirs();
        }
        return new File(bDir, "book.epub");
    }

    public File getChapterFile(String bookUuid, String chapterId) {
        File bDir = new File(booksDir, bookUuid);
        if (!bDir.exists()) {
            bDir.mkdirs();
        }
        String cleanId = chapterId != null ? chapterId.trim() : "0";
        if (cleanId.startsWith("ch_")) {
            cleanId = cleanId.substring(3);
        }
        return new File(bDir, "ch_" + cleanId + ".txt");
    }

    public File getTocFile(String bookUuid) {
        File bDir = new File(booksDir, bookUuid);
        if (!bDir.exists()) {
            bDir.mkdirs();
        }
        return new File(bDir, "toc.json");
    }

    public File getFootnotesFile(String bookUuid) {
        File bDir = new File(booksDir, bookUuid);
        if (!bDir.exists()) {
            bDir.mkdirs();
        }
        return new File(bDir, "footnotes.json");
    }

    public File getEpubFontsDir(String bookUuid) {
        File bDir = new File(booksDir, bookUuid);
        if (!bDir.exists()) {
            bDir.mkdirs();
        }
        return new File(bDir, "epub_fonts");
    }

    private File getBookFile(String bookUuid, String fileName) {
        File bDir = new File(booksDir, bookUuid);
        if (!bDir.exists()) {
            bDir.mkdirs();
        }
        return new File(bDir, fileName);
    }

    private void writeTextFile(File file, String text) {
        if (file == null || text == null) return;
        FileOutputStream fos = null;
        OutputStreamWriter writer = null;
        try {
            fos = new FileOutputStream(file);
            writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(text);
        } catch (Exception e) {
            Log.e(TAG, "Error writing file " + file.getName(), e);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Throwable ignored) {}
            }
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private String readTextFile(File file) {
        if (file == null || !file.exists() || file.length() == 0) return null;
        FileInputStream fis = null;
        BufferedReader reader = null;
        try {
            fis = new FileInputStream(file);
            reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignored) {}
            }
            if (fis != null) {
                try { fis.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public boolean isFormattedCacheCurrent(String bookUuid) {
        if (bookUuid == null) return true;
        File flag = getBookFile(bookUuid, "format_v204.flag");
        return flag.exists() && flag.length() > 0;
    }

    public void markFormattedCacheCurrent(String bookUuid) {
        if (bookUuid == null) return;
        writeTextFile(getBookFile(bookUuid, "format_v204.flag"), "v2.0.4");
    }

    public void saveBookEpubFonts(String bookUuid, Map<String, String> fonts) {
        if (bookUuid == null || fonts == null) return;
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> e : fonts.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    obj.put(e.getKey(), e.getValue());
                }
            }
            writeTextFile(getBookFile(bookUuid, "epub_fonts.json"), obj.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error saving epub fonts map", e);
        }
    }

    public Map<String, String> loadBookEpubFonts(String bookUuid) {
        Map<String, String> map = new HashMap<>();
        if (bookUuid == null) return map;
        File metaFile = getBookFile(bookUuid, "epub_fonts.json");
        String raw = readTextFile(metaFile);
        if ((raw == null || raw.isEmpty())) {
            File epub = getEpubFile(bookUuid);
            if (epub != null && epub.exists() && epub.length() > 0) {
                java.util.zip.ZipFile zip = null;
                try {
                    zip = new java.util.zip.ZipFile(epub);
                    map = com.onyx.yandexbooks.core.epub.EpubFormatting.extractEpubFonts(zip, getEpubFontsDir(bookUuid));
                    saveBookEpubFonts(bookUuid, map);
                } catch (Throwable ignored) {
                } finally {
                    if (zip != null) {
                        try { zip.close(); } catch (Throwable ignored) {}
                    }
                }
            }
            return map;
        }
        try {
            JSONObject obj = new JSONObject(raw);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = obj.optString(k, "");
                if (!k.isEmpty() && !v.isEmpty() && new File(v).exists()) {
                    map.put(k, v);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading epub fonts map", e);
        }
        return map;
    }

    public void savePageCountsCache(String bookUuid, String sig, int[] counts) {
        if (bookUuid == null || sig == null || counts == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("sig", sig);
            JSONArray arr = new JSONArray();
            for (int c : counts) {
                arr.put(c);
            }
            obj.put("counts", arr);
            writeTextFile(getBookFile(bookUuid, "page_counts.json"), obj.toString());
        } catch (Exception ignored) {}
    }

    public int[] loadPageCountsCache(String bookUuid, String expectedSig, int expectedLen) {
        if (bookUuid == null || expectedSig == null || expectedLen <= 0) return null;
        String raw = readTextFile(getBookFile(bookUuid, "page_counts.json"));
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject obj = new JSONObject(raw);
            if (!expectedSig.equals(obj.optString("sig", ""))) return null;
            JSONArray arr = obj.optJSONArray("counts");
            if (arr == null || arr.length() != expectedLen) return null;
            int[] out = new int[expectedLen];
            for (int i = 0; i < expectedLen; i++) {
                int c = arr.optInt(i, 0);
                if (c <= 0) return null;
                out[i] = c;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public void invalidatePageCountsCache(String bookUuid) {
        if (bookUuid == null) return;
        File f = getBookFile(bookUuid, "page_counts.json");
        if (f.exists()) {
            f.delete();
        }
    }

    public void saveFootnotes(String bookUuid, Map<String, String> footnotes) {
        if (bookUuid == null || footnotes == null) return;
        FileOutputStream fos = null;
        OutputStreamWriter writer = null;
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> e : footnotes.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    obj.put(e.getKey(), e.getValue());
                }
            }
            fos = new FileOutputStream(getFootnotesFile(bookUuid));
            writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(obj.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error saving footnotes", e);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Throwable ignored) {}
            }
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public Map<String, String> loadFootnotes(String bookUuid) {
        Map<String, String> map = new HashMap<>();
        if (bookUuid == null) return map;
        File fnFile = getFootnotesFile(bookUuid);
        if (!fnFile.exists() || fnFile.length() == 0) {
            return map;
        }
        FileInputStream fis = null;
        BufferedReader reader = null;
        try {
            fis = new FileInputStream(fnFile);
            reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONObject obj = new JSONObject(sb.toString());
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = obj.optString(k, "");
                if (!k.isEmpty() && !v.isEmpty()) {
                    map.put(k, v);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading footnotes", e);
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignored) {}
            }
            if (fis != null) {
                try { fis.close(); } catch (Throwable ignored) {}
            }
        }
        return map;
    }

    /**
     * Синхронное (для фонового потока) доизвлечение сносок для книг, скачанных до v1.6.7-beta.
     */
    public Map<String, String> ensureFootnotesExtractedSync(String bookUuid) {
        File fnFile = getFootnotesFile(bookUuid);
        if (fnFile.exists() && fnFile.length() > 0) {
            return loadFootnotes(bookUuid);
        }
        Map<String, String> map = new HashMap<>();
        File epub = getEpubFile(bookUuid);
        if (epub != null && epub.exists() && epub.length() > 0) {
            try {
                EpubParser.ParseResult parsed = EpubParser.parseEpubFull(epub);
                if (parsed != null && parsed.footnotes != null) {
                    saveFootnotes(bookUuid, parsed.footnotes);
                    return parsed.footnotes;
                }
            } catch (Throwable ignored) {}
        }
        saveFootnotes(bookUuid, map);
        return map;
    }

    public void saveTocTree(String bookUuid, List<EpubParser.TocNode> tocTree) {
        if (tocTree == null) return;
        FileOutputStream fos = null;
        OutputStreamWriter writer = null;
        try {
            JSONArray arr = new JSONArray();
            for (EpubParser.TocNode node : tocTree) {
                arr.put(tocNodeToJson(node));
            }
            File file = getTocFile(bookUuid);
            fos = new FileOutputStream(file);
            writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(arr.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error saving TOC tree", e);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Throwable ignored) {}
            }
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public List<EpubParser.TocNode> loadTocTree(String bookUuid) {
        File file = getTocFile(bookUuid);
        if (!file.exists()) return null;
        FileInputStream fis = null;
        BufferedReader reader = null;
        try {
            fis = new FileInputStream(file);
            reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            List<EpubParser.TocNode> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    list.add(tocNodeFromJson(obj));
                }
            }
            return list;
        } catch (Exception e) {
            Log.e(TAG, "Error loading TOC tree", e);
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignored) {}
            }
            if (fis != null) {
                try { fis.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private JSONObject tocNodeToJson(EpubParser.TocNode node) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", node.id != null ? node.id : "");
            obj.put("title", node.title != null ? node.title : "");
            obj.put("rawHref", node.rawHref != null ? node.rawHref : "");
            obj.put("fileHref", node.fileHref != null ? node.fileHref : "");
            obj.put("anchor", node.anchor != null ? node.anchor : "");
            obj.put("level", node.level);
            obj.put("spineIndex", node.spineIndex);
            obj.put("charOffset", node.charOffset);
            obj.put("pageNumber", node.pageNumber);
            obj.put("hasChildren", node.hasChildren);
            if (node.children != null && !node.children.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (EpubParser.TocNode child : node.children) {
                    arr.put(tocNodeToJson(child));
                }
                obj.put("children", arr);
            }
        } catch (Exception ignored) {}
        return obj;
    }

    private EpubParser.TocNode tocNodeFromJson(JSONObject obj) {
        EpubParser.TocNode node = new EpubParser.TocNode();
        node.id = obj.optString("id", "");
        node.title = obj.optString("title", "");
        node.rawHref = obj.optString("rawHref", "");
        node.fileHref = obj.optString("fileHref", "");
        node.anchor = obj.optString("anchor", null);
        if ("".equals(node.anchor) || "null".equals(node.anchor)) node.anchor = null;
        node.level = obj.optInt("level", 0);
        node.spineIndex = obj.optInt("spineIndex", 0);
        node.charOffset = obj.optInt("charOffset", 0);
        node.pageNumber = obj.optInt("pageNumber", 1);
        node.hasChildren = obj.optBoolean("hasChildren", false);
        node.isExpanded = (node.level == 0);
        JSONArray arr = obj.optJSONArray("children");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject cObj = arr.optJSONObject(i);
                if (cObj != null) {
                    node.children.add(tocNodeFromJson(cObj));
                }
            }
            if (!node.children.isEmpty()) {
                node.hasChildren = true;
            }
        }
        return node;
    }

    public long getChapterLength(String bookUuid, String chapterId) {
        File f = getChapterFile(bookUuid, chapterId);
        if (f != null && f.exists()) {
            return f.length();
        }
        return 0;
    }

    public boolean isBookDownloaded(String bookUuid) {
        if (bookUuid == null || bookUuid.trim().isEmpty()) return false;
        File epub = getEpubFile(bookUuid);
        if (epub.exists() && epub.length() > 500) {
            return true;
        }
        Book b = dbHelper.getBookByUuid(bookUuid);
        if (b != null && b.isDownloaded()) {
            return true;
        }
        return false;
    }

    public boolean isChapterCached(String bookUuid, String chapterId) {
        File file = getChapterFile(bookUuid, chapterId);
        return file.exists() && file.length() > 0;
    }

    /**
     * Проверка валидности кэшированных глав.
     * Если большинство глав пусты (< 50 байт текста) или общий объем подозрительно мал,
     * возвращает false для запуска автоматического самовосстановления из book.epub.
     */
    public boolean isChapterCacheValid(String bookUuid, List<Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return false;
        int n = chapters.size();
        // Защита от сдвинутых глав: индексы обязаны начинаться с 0 и идти непрерывно до n - 1
        if (chapters.get(0).getChapterIndex() != 0 || chapters.get(n - 1).getChapterIndex() != n - 1) {
            return false;
        }
        if (n <= 600) {
            for (int i = 0; i < n; i++) {
                if (chapters.get(i).getChapterIndex() != i) {
                    return false;
                }
            }
        }
        // Быстрая выборка для больших книг с актуальным флагом формата (5 stat-вызовов вместо сотен на UI-потоке)
        if (n > 8 && isFormattedCacheCurrent(bookUuid)) {
            int[] sampleIdx = new int[]{0, n / 4, n / 2, (3 * n) / 4, n - 1};
            int validSamples = 0;
            long sampleBytes = 0;
            for (int idx : sampleIdx) {
                long len = getChapterLength(bookUuid, chapters.get(idx).getId());
                if (len > 50) {
                    validSamples++;
                    sampleBytes += len;
                }
            }
            if (validSamples >= 2 && sampleBytes >= 500) {
                return true;
            }
        }
        long totalBytes = 0;
        int nonZeroChapters = 0;
        for (int i = 0; i < n; i++) {
            Chapter ch = chapters.get(i);
            long len = getChapterLength(bookUuid, ch.getId());
            if (len > 50) {
                nonZeroChapters++;
                totalBytes += len;
            }
        }
        if (n > 3 && (nonZeroChapters < n / 3 || totalBytes < 5000)) {
            return false;
        }
        return true;
    }

    public void saveChapter(String bookUuid, String chapterId, String content) {
        java.io.BufferedOutputStream bos = null;
        try {
            File file = getChapterFile(bookUuid, chapterId);
            bos = new java.io.BufferedOutputStream(new FileOutputStream(file), 16384);
            if (content != null) {
                bos.write(content.getBytes("UTF-8"));
            }
            bos.flush();
        } catch (Exception e) {
            Log.e(TAG, "Error saving chapter " + chapterId, e);
        } finally {
            if (bos != null) {
                try { bos.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public String loadChapter(String bookUuid, String chapterId) {
        File file = getChapterFile(bookUuid, chapterId);
        if (!file.exists()) {
            // Проверяем альтернативные форматы имен файлов из кэша
            File bDir = new File(booksDir, bookUuid);
            String rawId = chapterId != null ? chapterId.trim() : "0";
            File f1 = new File(bDir, rawId + ".txt");
            File f2 = new File(bDir, "ch_" + rawId + ".txt");
            File f3 = new File(bDir, "ch_ch_" + rawId + ".txt");
            if (f1.exists()) file = f1;
            else if (f2.exists()) file = f2;
            else if (f3.exists()) file = f3;
        }

        if (!file.exists()) {
            // Файла нет на диске: однократно извлекаем из book.epub
            String onTheFly = extractChapterOnTheFly(bookUuid, chapterId);
            if (onTheFly != null) {
                return onTheFly;
            }
            return "";
        }

        FileInputStream fis = null;
        try {
            long fileLen = file.length();
            if (fileLen <= 0) return "";
            fis = new FileInputStream(file);
            if (fileLen <= 4 * 1024 * 1024) {
                byte[] buf = new byte[(int) fileLen];
                int offset = 0;
                int read;
                while (offset < buf.length && (read = fis.read(buf, offset, buf.length - offset)) > 0) {
                    offset += read;
                }
                return new String(buf, 0, offset, "UTF-8");
            }
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int nRead;
            while ((nRead = fis.read(chunk)) > 0) {
                baos.write(chunk, 0, nRead);
            }
            return new String(baos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Error reading chapter " + chapterId, e);
            return "";
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public synchronized String extractChapterOnTheFly(String bookUuid, String chapterId) {
        try {
            File epub = getEpubFile(bookUuid);
            if (!epub.exists() || epub.length() == 0) return null;

            int chIndex = -1;
            String cleanId = chapterId != null ? chapterId.trim() : "0";
            if (cleanId.startsWith("ch_")) cleanId = cleanId.substring(3);
            try {
                chIndex = Integer.parseInt(cleanId);
            } catch (Exception ignored) {}

            EpubParser.ParseResult parsed = EpubParser.parseEpubFull(epub, getEpubFontsDir(bookUuid));
            if (parsed != null && parsed.epubFonts != null && !parsed.epubFonts.isEmpty()) {
                saveBookEpubFonts(bookUuid, parsed.epubFonts);
            }
            if (parsed == null || parsed.chapters == null || parsed.chapters.isEmpty()) return null;

            EpubParser.ChapterData targetCd = null;
            if (chIndex >= 0 && chIndex < parsed.chapters.size()) {
                targetCd = parsed.chapters.get(chIndex);
            } else {
                for (EpubParser.ChapterData cd : parsed.chapters) {
                    if (cd.id != null && (cd.id.equals(chapterId) || cd.id.equals("ch_" + cleanId) || cd.id.equals(cleanId))) {
                        targetCd = cd;
                        break;
                    }
                }
            }

            if (targetCd != null) {
                String text = targetCd.textContent != null ? targetCd.textContent : "";
                saveChapter(bookUuid, cleanId, text);
                return text;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed on-the-fly chapter extraction for " + chapterId, t);
        }
        return null;
    }

    public void deleteBookCache(String bookUuid, String title) {
        try {
            File bDir = new File(booksDir, bookUuid);
            if (bDir.exists()) {
                deleteDirRecursive(bDir);
            }
            File pub = getPublicEpubFile(title);
            if (pub != null && pub.exists()) {
                pub.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting book cache", e);
        }
    }

    private void deleteDirRecursive(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirRecursive(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private List<Chapter> saveParsedBookChapters(String bookUuid, EpubParser.ParseResult parsed) {
        saveTocTree(bookUuid, parsed.tocTree);
        saveFootnotes(bookUuid, parsed.footnotes);
        saveBookEpubFonts(bookUuid, parsed.epubFonts);
        invalidatePageCountsCache(bookUuid);

        List<Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < parsed.chapters.size(); i++) {
            EpubParser.ChapterData cd = parsed.chapters.get(i);
            String chId = String.valueOf(i);
            saveChapter(bookUuid, chId, cd.textContent);

            Chapter ch = new Chapter();
            ch.setId(chId);
            ch.setBookUuid(bookUuid);
            ch.setChapterIndex(i);
            ch.setTitle(cd.title);
            ch.setOriginalOpfSpineIndex(cd.originalOpfSpineIndex);
            ch.setOpfItemId(cd.opfItemId);
            ch.setCharCount(com.onyx.yandexbooks.core.epub.EpubFormatting.countPlainChars(cd.textContent));
            chapters.add(ch);
        }

        markFormattedCacheCurrent(bookUuid);
        dbHelper.saveChapters(bookUuid, chapters);
        dbHelper.updateBookDownloaded(bookUuid, true);
        return chapters;
    }

    /**
     * Прямая синхронная загрузка книги (EPUB) и извлечение глав в текущем фоновом потоке.
     */
    public YandexBooksApiClient.DownloadResult downloadBookSync(final String bookUuid, final String bookTitle) {
        final File epubFile = getEpubFile(bookUuid);
        Book bookMeta = dbHelper.getBookByUuid(bookUuid);
        String directFileUrl = bookMeta != null ? bookMeta.getFileUrl() : null;

        YandexBooksApiClient.DownloadResult dlResult = apiClient.downloadBookEpubSync(bookUuid, directFileUrl, epubFile);
        if (!dlResult.success || !epubFile.exists() || epubFile.length() < 500) {
            return dlResult;
        }

        try {
            EpubParser.ParseResult parsed = EpubParser.parseEpubFull(epubFile, getEpubFontsDir(bookUuid));
            if (parsed == null || parsed.chapters.isEmpty()) {
                return YandexBooksApiClient.DownloadResult.error("Не удалось извлечь главы из книги");
            }

            saveParsedBookChapters(bookUuid, parsed);
            exportToPublicBooksDir(epubFile, bookTitle);

            return YandexBooksApiClient.DownloadResult.ok(epubFile);
        } catch (Throwable e) {
            Log.e(TAG, "Error in sync EPUB processing for " + bookUuid, e);
            return YandexBooksApiClient.DownloadResult.error("Ошибка парсинга: " + e.getMessage());
        }
    }

    /**
     * Загрузка книги (EPUB) и извлечение глав.
     */
    public void downloadBookAsync(final String bookUuid, final String bookTitle, final DownloadProgressCallback callback) {
        final File epubFile = getEpubFile(bookUuid);
        Book bookMeta = dbHelper.getBookByUuid(bookUuid);
        String directFileUrl = bookMeta != null ? bookMeta.getFileUrl() : null;

        apiClient.downloadBookEpub(bookUuid, directFileUrl, epubFile, new YandexBooksApiClient.ApiCallback<File>() {
            @Override
            public void onSuccess(final File downloadedFile) {
                // В фоновом потоке парсим главы из скачанного EPUB архива
                new AsyncTask<Void, Void, List<Chapter>>() {
                    @Override
                    protected List<Chapter> doInBackground(Void... voids) {
                        try {
                            EpubParser.ParseResult parsed = EpubParser.parseEpubFull(downloadedFile, getEpubFontsDir(bookUuid));
                            if (parsed == null || parsed.chapters.isEmpty()) {
                                return null;
                            }

                            List<Chapter> chapters = saveParsedBookChapters(bookUuid, parsed);

                            // Также экспортируем копию в /sdcard/Books/YandexBooks/ для системы Onyx
                            exportToPublicBooksDir(downloadedFile, bookTitle);

                            return chapters;
                        } catch (Throwable e) {
                            Log.e(TAG, "Error parsing downloaded EPUB", e);
                            return null;
                        }
                    }

                    @Override
                    protected void onPostExecute(List<Chapter> chapters) {
                        if (chapters != null && !chapters.isEmpty()) {
                            if (callback != null) {
                                callback.onProgress(chapters.size(), chapters.size());
                                callback.onComplete();
                            }
                        } else {
                            if (callback != null) {
                                callback.onError("Не удалось извлечь текст из книги");
                            }
                        }
                    }
                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }

            @Override
            public void onError(String errorMessage) {
                if (callback != null) {
                    callback.onError(errorMessage);
                }
            }
        });
    }

    /**
     * Обеспечивает готовность книги к чтению (берет из локального кэша или скачивает).
     */
    public void ensureBookReady(final String bookUuid, final String bookTitle, final BookReadyCallback callback) {
        final File epub = getEpubFile(bookUuid);
        List<Chapter> chapters = dbHelper.getChapters(bookUuid);

        // Если EPUB файл уже на устройстве, но список глав неполный или кэш от старой версии без форматирования v1.7.0:
        if (epub.exists() && epub.length() > 0 && ((chapters == null || chapters.size() <= 1) || !isFormattedCacheCurrent(bookUuid))) {
            reparseAndSaveBook(bookUuid, bookTitle, callback);
            return;
        }

        if (isBookDownloaded(bookUuid) && chapters != null && !chapters.isEmpty()) {
            callback.onReady(chapters);
            return;
        }

        // Скачиваем книгу
        downloadBookAsync(bookUuid, bookTitle, new DownloadProgressCallback() {
            @Override
            public void onProgress(int downloadedCount, int totalCount) {}

            @Override
            public void onComplete() {
                List<Chapter> chs = dbHelper.getChapters(bookUuid);
                if (chs != null && !chs.isEmpty()) {
                    callback.onReady(chs);
                } else {
                    callback.onError("Книга загружена, но главы не найдены");
                }
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    /**
     * Принудительно парсит существующий локальный EPUB файл и обновляет список глав в базе данных.
     */
    public void reparseAndSaveBook(final String bookUuid, final String bookTitle, final BookReadyCallback callback) {
        final File epub = getEpubFile(bookUuid);
        if (epub != null && epub.exists() && epub.length() > 0) {
            new AsyncTask<Void, Void, List<Chapter>>() {
                @Override
                protected List<Chapter> doInBackground(Void... voids) {
                    try {
                        EpubParser.ParseResult parsed = EpubParser.parseEpubFull(epub, getEpubFontsDir(bookUuid));
                        if (parsed == null || parsed.chapters.isEmpty()) return null;

                        return saveParsedBookChapters(bookUuid, parsed);
                    } catch (Throwable e) {
                        Log.e(TAG, "Error reparsing EPUB", e);
                        return null;
                    }
                }

                @Override
                protected void onPostExecute(List<Chapter> res) {
                    if (res != null && !res.isEmpty()) {
                        if (callback != null) callback.onReady(res);
                    } else {
                        // Если повторный парсинг не удался, скачиваем заново
                        downloadBookAsync(bookUuid, bookTitle, new DownloadProgressCallback() {
                            @Override
                            public void onProgress(int downloadedCount, int totalCount) {}
                            @Override
                            public void onComplete() {
                                List<Chapter> chs = dbHelper.getChapters(bookUuid);
                                if (chs != null && !chs.isEmpty()) callback.onReady(chs);
                                else callback.onError("Книга загружена, но главы не найдены");
                            }
                            @Override
                            public void onError(String message) {
                                callback.onError(message);
                            }
                        });
                    }
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            downloadBookAsync(bookUuid, bookTitle, new DownloadProgressCallback() {
                @Override
                public void onProgress(int downloadedCount, int totalCount) {}
                @Override
                public void onComplete() {
                    List<Chapter> chs = dbHelper.getChapters(bookUuid);
                    if (chs != null && !chs.isEmpty()) callback.onReady(chs);
                    else callback.onError("Книга загружена, но главы не найдены");
                }
                @Override
                public void onError(String message) {
                    callback.onError(message);
                }
            });
        }
    }

    public File getPublicEpubFile(String title) {
        File extStorage = Environment.getExternalStorageDirectory();
        if (extStorage != null) {
            File yandexBooksDir = new File(extStorage, "Books/YandexBooks");
            String cleanName = (title != null ? title : "book").replaceAll("[\\\\/:*?\"<>|]", "_");
            if (cleanName.length() > 60) {
                cleanName = cleanName.substring(0, 60);
            }
            return new File(yandexBooksDir, cleanName + ".epub");
        }
        return null;
    }

    public File ensurePublicEpubFile(String bookUuid, String bookTitle) {
        File publicFile = getPublicEpubFile(bookTitle);
        if (publicFile != null && publicFile.exists() && publicFile.length() > 0) {
            return publicFile;
        }
        File privateEpub = getEpubFile(bookUuid);
        if (privateEpub.exists() && privateEpub.length() > 0) {
            return exportToPublicBooksDir(privateEpub, bookTitle);
        }
        return null;
    }

    public File exportToPublicBooksDir(File sourceEpub, String title) {
        try {
            File extStorage = Environment.getExternalStorageDirectory();
            if (extStorage != null && extStorage.canWrite()) {
                File yandexBooksDir = new File(extStorage, "Books/YandexBooks");
                if (!yandexBooksDir.exists()) {
                    yandexBooksDir.mkdirs();
                }
                String cleanName = (title != null ? title : "book").replaceAll("[\\\\/:*?\"<>|]", "_");
                if (cleanName.length() > 60) {
                    cleanName = cleanName.substring(0, 60);
                }
                File destFile = new File(yandexBooksDir, cleanName + ".epub");
                copyFile(sourceEpub, destFile);
                Log.d(TAG, "Exported EPUB to: " + destFile.getAbsolutePath());

                // Регистрация в системном медиа-сканере Onyx для появления в Библиотеке
                android.media.MediaScannerConnection.scanFile(
                        context,
                        new String[]{ destFile.getAbsolutePath() },
                        new String[]{ "application/epub+zip" },
                        null
                );

                return destFile;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to export EPUB to public Books directory", e);
        }
        return null;
    }

    public static final String[] ALREADER_PACKAGES = new String[]{
            "com.neverland.alreader",
            "com.neverland.alreaderx",
            "com.neverland.alreaderxpro",
            "com.neverland.alreader.onyx",
            "ru.alreader"
    };

    public static final String[] NEOREADER_PACKAGES = new String[]{
            "com.onyx.kreader",
            "com.onyx.reader",
            "cn.onyxsdk.neoreader"
    };

    private static void disableFileUriExposureIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            try {
                java.lang.reflect.Method m = android.os.StrictMode.class.getMethod("disableDeathOnFileUriExposure");
                m.invoke(null);
            } catch (Throwable ignored) {
                try {
                    android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder().build());
                } catch (Throwable ignored2) {}
            }
        }
    }

    public static String findInstalledPackage(Context context, String[] candidates) {
        if (context == null || candidates == null) return null;
        android.content.pm.PackageManager pm = context.getPackageManager();
        for (String pkg : candidates) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String findMatchingEpubHandler(Context context, android.content.Intent intent, String[] keywords) {
        if (context == null || intent == null || keywords == null) return null;
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            java.util.List<android.content.pm.ResolveInfo> handlers = pm.queryIntentActivities(intent, 0);
            if (handlers != null) {
                for (android.content.pm.ResolveInfo ri : handlers) {
                    if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                    String pkg = ri.activityInfo.packageName.toLowerCase();
                    for (String kw : keywords) {
                        if (pkg.contains(kw)) {
                            return ri.activityInfo.packageName;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean openInSystemReader(android.app.Activity activity, File epubFile) {
        return openInSystemReader(activity, epubFile, 0.0, 0, null);
    }

    public static boolean openInSystemReader(android.app.Activity activity, File epubFile, double percent, int chapterIndex) {
        String mode = null;
        if (activity != null) {
            mode = new AppSettings(activity).getReaderMode();
        }
        return openInSystemReader(activity, epubFile, percent, chapterIndex, mode);
    }

    private static android.content.Intent buildEpubViewIntent(android.net.Uri uri, double percent, int chapterIndex) {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/epub+zip");
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (percent > 0.0) {
            intent.putExtra("percent", (float) percent);
            intent.putExtra("position", (int) Math.round(percent * 100.0));
            intent.putExtra("progress", (float) (percent / 100.0));
            intent.putExtra("chapter", Math.max(0, chapterIndex));
            intent.putExtra("chapter_index", Math.max(0, chapterIndex));
            intent.putExtra("org.geometerplus.zlibrary.ui.android.action.VIEW", uri);
        }
        return intent;
    }

    private static boolean tryLaunchWithIntent(android.app.Activity activity, android.content.Intent intent,
                                               android.net.Uri uri, double percent, String targetReaderMode) {
        if (activity == null || intent == null) return false;

        // 0. Если явно запрошен системный выбор приложения (Chooser)
        if (AppSettings.READER_MODE_CHOOSER.equalsIgnoreCase(targetReaderMode)) {
            try {
                android.content.Intent chooser = android.content.Intent.createChooser(intent, "Выберите приложение для чтения:");
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                chooser.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(chooser);
                return true;
            } catch (Exception ignored) {}
        }

        // 0.1. Если явно выбран AlReader или NeoReader — проверяем установленные пакеты и перечень обработчиков EPUB
        if (AppSettings.READER_MODE_ALREADER.equalsIgnoreCase(targetReaderMode)) {
            String alPkg = findInstalledPackage(activity, ALREADER_PACKAGES);
            if (alPkg == null) {
                alPkg = findMatchingEpubHandler(activity, intent, new String[]{"alreader", "neverland"});
            }
            if (alPkg != null) {
                try {
                    if ("content".equalsIgnoreCase(uri.getScheme())) {
                        activity.grantUriPermission(alPkg, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }
                    android.content.Intent direct = new android.content.Intent(intent);
                    direct.setPackage(alPkg);
                    activity.startActivity(direct);
                    return true;
                } catch (Exception ignored) {}
            }
            try {
                android.content.Intent chooser = android.content.Intent.createChooser(intent, "AlReader не найден — выберите ридер:");
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                chooser.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(chooser);
                return true;
            } catch (Exception ignored) {}
        } else if (AppSettings.READER_MODE_NEOREADER.equalsIgnoreCase(targetReaderMode)) {
            String neoPkg = findInstalledPackage(activity, NEOREADER_PACKAGES);
            if (neoPkg == null) {
                neoPkg = findMatchingEpubHandler(activity, intent, new String[]{"kreader", "neoreader", "onyx.reader"});
            }
            if (neoPkg != null) {
                try {
                    if ("content".equalsIgnoreCase(uri.getScheme())) {
                        activity.grantUriPermission(neoPkg, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }
                    android.content.Intent direct = new android.content.Intent(intent);
                    direct.setPackage(neoPkg);
                    activity.startActivity(direct);
                    return true;
                } catch (Exception ignored) {}
            }
        }

        // 1. Прямой запуск системного обработчика (ловим также FileUriExposedException и SecurityException)
        try {
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            try {
                android.content.Intent chooser = android.content.Intent.createChooser(intent, "Выберите приложение для чтения:");
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                chooser.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(chooser);
                return true;
            } catch (Exception ex) {
                try {
                    android.content.Intent fallback = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    fallback.setDataAndType(uri, "*/*");
                    fallback.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    fallback.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    if (percent > 0.0) {
                        fallback.putExtra("percent", (float) percent);
                        fallback.putExtra("progress", (float) (percent / 100.0));
                    }
                    activity.startActivity(fallback);
                    return true;
                } catch (Exception e2) {
                    Log.e(TAG, "Intent launch failed for URI " + uri, e2);
                    return false;
                }
            }
        }
    }

    public static boolean openInSystemReader(android.app.Activity activity, File epubFile, double percent, int chapterIndex, String targetReaderMode) {
        if (activity == null || epubFile == null || !epubFile.exists()) {
            return false;
        }
        boolean isAppPrivate = epubFile.getAbsolutePath().startsWith(activity.getFilesDir().getAbsolutePath());
        if (!isAppPrivate) {
            disableFileUriExposureIfNeeded();
            android.net.Uri fileUri = android.net.Uri.fromFile(epubFile);
            if (tryLaunchWithIntent(activity, buildEpubViewIntent(fileUri, percent, chapterIndex), fileUri, percent, targetReaderMode)) {
                return true;
            }
        }
        // ContentProvider URI (безопасно для Android 7–14 без FileUriExposedException и для внутреннего кэша)
        android.net.Uri contentUri = com.onyx.yandexbooks.core.update.LocalFileProvider.getUriForFile(epubFile);
        return tryLaunchWithIntent(activity, buildEpubViewIntent(contentUri, percent, chapterIndex), contentUri, percent, targetReaderMode);
    }

    /**
     * Быстрый замер габаритов изображений (PNG/JPG/GIF/WEBP) внутри EPUB без декодирования пикселей.
     * Используется пагинатором для отличия декоративных виньеток заголовков от полностраничных иллюстраций.
     */
    public void warmupImageDimensionsFromEpub(String bookUuid) {
        if (bookUuid == null || bookUuid.trim().isEmpty()) return;
        File epubFile = getEpubFile(bookUuid);
        if (!epubFile.exists() || epubFile.length() == 0) return;
        ZipFile zip = null;
        try {
            zip = new ZipFile(epubFile);
            Enumeration<? extends ZipEntry> en = zip.entries();
            BitmapFactory.Options opts = new BitmapFactory.Options();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String name = ze.getName();
                if (name == null) continue;
                String lower = name.toLowerCase(java.util.Locale.US);
                if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")
                        && !lower.endsWith(".gif") && !lower.endsWith(".webp")) {
                    continue;
                }
                String key = bookUuid + ":" + name.trim();
                if (imageDimCache.containsKey(key)) continue;
                opts.inJustDecodeBounds = true;
                opts.outWidth = 0;
                opts.outHeight = 0;
                InputStream is = null;
                try {
                    is = zip.getInputStream(ze);
                    BitmapFactory.decodeStream(is, null, opts);
                    if (opts.outWidth > 0 && opts.outHeight > 0) {
                        int[] dims = new int[]{opts.outWidth, opts.outHeight};
                        imageDimCache.put(key, dims);
                        imageDimCache.put(bookUuid + ":__file__:" + (lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower), dims);
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (is != null) {
                        try { is.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public int[] getImageDimensionsFromEpub(String bookUuid, String zipPath) {
        if (bookUuid == null || zipPath == null || zipPath.trim().isEmpty()) return null;
        String cleanPath = zipPath.trim();
        String key = bookUuid + ":" + cleanPath;
        int[] cached = imageDimCache.get(key);
        if (cached != null) {
            return (cached[0] > 0 && cached[1] > 0) ? cached : null;
        }
        String lower = cleanPath.toLowerCase(java.util.Locale.US);
        String fileKey = bookUuid + ":__file__:" + (lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower);
        cached = imageDimCache.get(fileKey);
        if (cached != null && cached[0] > 0 && cached[1] > 0) {
            imageDimCache.put(key, cached);
            return cached;
        }

        File epubFile = getEpubFile(bookUuid);
        if (!epubFile.exists() || epubFile.length() == 0) {
            return null;
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(epubFile);
            ZipEntry entry = zip.getEntry(cleanPath);
            if (entry == null) {
                String targetFileName = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String name = ze.getName().toLowerCase(java.util.Locale.US);
                    if (name.equals(lower) || name.endsWith("/" + lower) || name.endsWith("/" + targetFileName)) {
                        entry = ze;
                        break;
                    }
                }
            }
            if (entry == null) {
                imageDimCache.put(key, new int[]{-1, -1});
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            InputStream is = null;
            try {
                is = zip.getInputStream(entry);
                BitmapFactory.decodeStream(is, null, opts);
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Throwable ignored) {}
                }
            }
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                int[] dims = new int[]{opts.outWidth, opts.outHeight};
                imageDimCache.put(key, dims);
                imageDimCache.put(fileKey, dims);
                return dims;
            }
            imageDimCache.put(key, new int[]{-1, -1});
            return null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public Bitmap loadImageFromEpub(String bookUuid, String zipPath, int reqWidth, int reqHeight) {
        if (bookUuid == null || zipPath == null || zipPath.trim().isEmpty()) return null;
        String key = bookUuid + ":" + zipPath.trim();
        Bitmap cached = imageCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        File epubFile = getEpubFile(bookUuid);
        if (!epubFile.exists() || epubFile.length() == 0) {
            return null;
        }

        ZipFile zip = null;
        try {
            zip = new ZipFile(epubFile);
            ZipEntry entry = zip.getEntry(zipPath.trim());
            if (entry == null) {
                // Fallback: регистронезависимый поиск или поиск по имени файла
                String cleanTarget = zipPath.trim().toLowerCase();
                String targetFileName = cleanTarget.contains("/") ? cleanTarget.substring(cleanTarget.lastIndexOf('/') + 1) : cleanTarget;
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String name = ze.getName().toLowerCase();
                    if (name.equals(cleanTarget) || name.endsWith("/" + cleanTarget) || name.endsWith("/" + targetFileName)) {
                        entry = ze;
                        break;
                    }
                }
            }

            if (entry == null) return null;

            // 1. Быстрый замер размеров изображения без аллокации пикселей
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            InputStream is = null;
            try {
                is = zip.getInputStream(entry);
                BitmapFactory.decodeStream(is, null, opts);
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Throwable ignored) {}
                }
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                return null;
            }

            // 2. Расчет коэффициента дискретизации и декодирование в RGB_565 для экономии RAM на Onyx
            opts.inSampleSize = calculateInSampleSize(opts, Math.max(100, reqWidth), Math.max(100, reqHeight));
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;

            Bitmap bmp;
            InputStream is2 = null;
            try {
                is2 = zip.getInputStream(entry);
                bmp = BitmapFactory.decodeStream(is2, null, opts);
            } finally {
                if (is2 != null) {
                    try { is2.close(); } catch (Throwable ignored) {}
                }
            }

            if (bmp != null) {
                imageCache.put(key, bmp);
            }
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "Error loading image from epub: " + zipPath, t);
            return null;
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public Bitmap getCoverBitmap(String bookUuid, int reqWidth, int reqHeight) {
        if (bookUuid == null) return null;
        String key = bookUuid + ":__cover__";
        Bitmap cached = imageCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        // 1. Проверяем локальный кэш обложек на диске
        File diskCover = new File(context.getCacheDir(), "covers/" + bookUuid + ".jpg");
        if (diskCover.exists() && diskCover.length() > 0) {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(diskCover.getAbsolutePath(), opts);
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight);
                    opts.inJustDecodeBounds = false;
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap bmp = BitmapFactory.decodeFile(diskCover.getAbsolutePath(), opts);
                    if (bmp != null) {
                        if (new AppSettings(context).isDitherCoversEnabled()) {
                            bmp = EinkDitherer.applyFloydSteinbergDither(bmp);
                        }
                        imageCache.put(key, bmp);
                        return bmp;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 2. Проверяем наличие обложки в самом EPUB файле
        File epubFile = getEpubFile(bookUuid);
        if (epubFile.exists() && epubFile.length() > 0) {
            ZipFile zip = null;
            try {
                zip = new ZipFile(epubFile);
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String name = ze.getName().toLowerCase();
                    if ((name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) && name.contains("cover")) {
                        Bitmap bmp = loadImageFromEpub(bookUuid, ze.getName(), reqWidth, reqHeight);
                        if (bmp != null) {
                            if (new AppSettings(context).isDitherCoversEnabled()) {
                                bmp = EinkDitherer.applyFloydSteinbergDither(bmp);
                            }
                            imageCache.put(key, bmp);
                            return bmp;
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (zip != null) {
                    try { zip.close(); } catch (Throwable ignored) {}
                }
            }
        }

        return null;
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private void copyFile(File src, File dst) throws Exception {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) {}
            }
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
