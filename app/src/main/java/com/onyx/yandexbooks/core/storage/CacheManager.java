package com.onyx.yandexbooks.core.storage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;
import android.util.LruCache;

import com.onyx.yandexbooks.core.api.YandexBooksApiClient;
import com.onyx.yandexbooks.core.api.models.Chapter;
import com.onyx.yandexbooks.core.epub.EpubParser;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Менеджер кэширования и загрузки книг в формате EPUB.
 * Скачивает EPUB из Яндекс Книг, парсит главы для Canvas-читалки
 * и сохраняет файл в каталог /sdcard/Books/YandexBooks/ для внешней библиотеки Onyx.
 */
public class CacheManager {

    private static final String TAG = "CacheManager";
    private final Context context;
    private final File booksDir;
    private final YandexBooksApiClient apiClient;
    private final DatabaseHelper dbHelper;
    private final LruCache<String, Bitmap> imageCache;

    public interface DownloadProgressCallback {
        void onProgress(int downloadedCount, int totalCount);
        void onComplete();
        void onError(String message);
    }

    public interface BookReadyCallback {
        void onReady(List<Chapter> chapters);
        void onError(String message);
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
        this.imageCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
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

    public void saveTocTree(String bookUuid, List<EpubParser.TocNode> tocTree) {
        if (tocTree == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (EpubParser.TocNode node : tocTree) {
                arr.put(tocNodeToJson(node));
            }
            File file = getTocFile(bookUuid);
            try (FileOutputStream fos = new FileOutputStream(file);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(arr.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving TOC tree", e);
        }
    }

    public List<EpubParser.TocNode> loadTocTree(String bookUuid) {
        File file = getTocFile(bookUuid);
        if (!file.exists()) return null;
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
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
        File epub = getEpubFile(bookUuid);
        if (epub.exists() && epub.length() > 0) {
            List<Chapter> chs = dbHelper.getChapters(bookUuid);
            return chs != null && !chs.isEmpty();
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
        long totalBytes = 0;
        int nonZeroChapters = 0;
        for (int i = 0; i < chapters.size(); i++) {
            Chapter ch = chapters.get(i);
            long len = getChapterLength(bookUuid, ch.getId());
            if (len > 50) {
                nonZeroChapters++;
                totalBytes += len;
            }
        }
        if (chapters.size() > 3 && (nonZeroChapters < chapters.size() / 3 || totalBytes < 5000)) {
            return false;
        }
        return true;
    }

    public void saveChapter(String bookUuid, String chapterId, String content) {
        try {
            File file = getChapterFile(bookUuid, chapterId);
            try (FileOutputStream fos = new FileOutputStream(file);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(content);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving chapter " + chapterId, e);
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

        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error reading chapter " + chapterId, e);
            return "";
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

            EpubParser.ParseResult parsed = EpubParser.parseEpubFull(epub);
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

    /**
     * Загрузка книги (EPUB) и извлечение глав.
     */
    public void downloadBookAsync(final String bookUuid, final String bookTitle, final DownloadProgressCallback callback) {
        final File epubFile = getEpubFile(bookUuid);

        apiClient.downloadBookEpub(bookUuid, epubFile, new YandexBooksApiClient.ApiCallback<File>() {
            @Override
            public void onSuccess(final File downloadedFile) {
                // В фоновом потоке парсим главы из скачанного EPUB архива
                new AsyncTask<Void, Void, List<Chapter>>() {
                    @Override
                    protected List<Chapter> doInBackground(Void... voids) {
                        try {
                            EpubParser.ParseResult parsed = EpubParser.parseEpubFull(downloadedFile);
                            if (parsed == null || parsed.chapters.isEmpty()) {
                                return null;
                            }

                            saveTocTree(bookUuid, parsed.tocTree);

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
                                chapters.add(ch);
                            }

                            dbHelper.saveChapters(bookUuid, chapters);
                            dbHelper.updateBookDownloaded(bookUuid, true);

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

        // Если EPUB файл уже на устройстве, но список глав пуст или подозрительно мал (1 глава, миграция якорного оглавления TOC):
        if (epub.exists() && epub.length() > 0 && (chapters == null || chapters.size() <= 1)) {
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
                        EpubParser.ParseResult parsed = EpubParser.parseEpubFull(epub);
                        if (parsed == null || parsed.chapters.isEmpty()) return null;

                        saveTocTree(bookUuid, parsed.tocTree);

                        List<Chapter> newChapters = new ArrayList<>();
                        for (int i = 0; i < parsed.chapters.size(); i++) {
                            EpubParser.ChapterData cd = parsed.chapters.get(i);
                            String chId = String.valueOf(i);
                            saveChapter(bookUuid, chId, cd.textContent);
                            Chapter ch = new Chapter();
                            ch.setId(chId);
                            ch.setBookUuid(bookUuid);
                            ch.setChapterIndex(i);
                            ch.setTitle(cd.title);
                            newChapters.add(ch);
                        }
                        dbHelper.saveChapters(bookUuid, newChapters);
                        dbHelper.updateBookDownloaded(bookUuid, true);
                        return newChapters;
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

    public static boolean openInSystemReader(android.app.Activity activity, File epubFile) {
        return openInSystemReader(activity, epubFile, 0.0, 0);
    }

    public static boolean openInSystemReader(android.app.Activity activity, File epubFile, double percent, int chapterIndex) {
        if (epubFile == null || !epubFile.exists()) {
            return false;
        }
        android.net.Uri uri = android.net.Uri.fromFile(epubFile);

        // 1. Попытка открыть напрямую через Intent для EPUB
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/epub+zip");
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);

        if (percent > 0.0) {
            intent.putExtra("percent", (float) percent);
            intent.putExtra("position", (int) Math.round(percent * 100.0));
            intent.putExtra("progress", (float) (percent / 100.0));
            intent.putExtra("chapter", Math.max(0, chapterIndex));
            intent.putExtra("chapter_index", Math.max(0, chapterIndex));
            intent.putExtra("org.geometerplus.zlibrary.ui.android.action.VIEW", uri);
        }

        try {
            activity.startActivity(intent);
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            // 2. Попытка с общим MIME-типом или chooser
            try {
                android.content.Intent chooser = android.content.Intent.createChooser(intent, "Выберите приложение для чтения:");
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(chooser);
                return true;
            } catch (Exception ex) {
                try {
                    android.content.Intent fallback = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    fallback.setDataAndType(uri, "*/*");
                    fallback.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (percent > 0.0) {
                        fallback.putExtra("percent", (float) percent);
                        fallback.putExtra("progress", (float) (percent / 100.0));
                    }
                    activity.startActivity(fallback);
                    return true;
                } catch (Exception e2) {
                    Log.e(TAG, "No app found to open EPUB", e2);
                    return false;
                }
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

        try (ZipFile zip = new ZipFile(epubFile)) {
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
            try (InputStream is = zip.getInputStream(entry)) {
                BitmapFactory.decodeStream(is, null, opts);
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                return null;
            }

            // 2. Расчет коэффициента дискретизации и декодирование в RGB_565 для экономии RAM на Onyx
            opts.inSampleSize = calculateInSampleSize(opts, Math.max(100, reqWidth), Math.max(100, reqHeight));
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;

            Bitmap bmp;
            try (InputStream is2 = zip.getInputStream(entry)) {
                bmp = BitmapFactory.decodeStream(is2, null, opts);
            }

            if (bmp != null) {
                imageCache.put(key, bmp);
            }
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "Error loading image from epub: " + zipPath, t);
            return null;
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
                        imageCache.put(key, bmp);
                        return bmp;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 2. Проверяем наличие обложки в самом EPUB файле
        File epubFile = getEpubFile(bookUuid);
        if (epubFile.exists() && epubFile.length() > 0) {
            try (ZipFile zip = new ZipFile(epubFile)) {
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String name = ze.getName().toLowerCase();
                    if ((name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) && name.contains("cover")) {
                        Bitmap bmp = loadImageFromEpub(bookUuid, ze.getName(), reqWidth, reqHeight);
                        if (bmp != null) {
                            imageCache.put(key, bmp);
                            return bmp;
                        }
                    }
                }
            } catch (Throwable ignored) {}
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
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
        }
    }
}
