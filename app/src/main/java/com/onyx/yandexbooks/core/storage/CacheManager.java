package com.onyx.yandexbooks.core.storage;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;

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
import java.util.List;

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
        return new File(bDir, "ch_" + chapterId + ".txt");
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
            return null;
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
            return null;
        }
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
        if (epubFile == null || !epubFile.exists()) {
            return false;
        }
        android.net.Uri uri = android.net.Uri.fromFile(epubFile);

        // 1. Попытка открыть напрямую через Intent для EPUB
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/epub+zip");
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            activity.startActivity(intent);
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            // 2. Попытка с общим MIME-типом или chooser
            try {
                android.content.Intent chooser = android.content.Intent.createChooser(intent, "Выберите читалку Onyx:");
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(chooser);
                return true;
            } catch (Exception ex) {
                try {
                    android.content.Intent fallback = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    fallback.setDataAndType(uri, "*/*");
                    fallback.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(fallback);
                    return true;
                } catch (Exception e2) {
                    Log.e(TAG, "No app found to open EPUB", e2);
                    return false;
                }
            }
        }
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
