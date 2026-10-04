package com.onyx.yandexbooks.core.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.onyx.yandexbooks.core.api.models.Book;
import com.onyx.yandexbooks.core.api.models.Chapter;
import com.onyx.yandexbooks.core.api.models.ReadingProgress;

import java.util.ArrayList;
import java.util.List;

/**
 * База данных SQLite для хранения локальных полок, глав, аннотаций, прогресса и очереди офлайн-синхронизации.
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "yandex_books_lite.db";
    private static final int DATABASE_VERSION = 3;

    private static DatabaseHelper instance;

    public static synchronized DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createTables(db);
    }

    private void createTables(SQLiteDatabase db) {
        // Таблица книг
        db.execSQL("CREATE TABLE IF NOT EXISTS books (" +
                "uuid TEXT PRIMARY KEY," +
                "title TEXT," +
                "author TEXT," +
                "annotation TEXT," +
                "cover_url TEXT," +
                "local_cover_path TEXT," +
                "percent REAL," +
                "total_chapters INTEGER," +
                "current_chapter INTEGER," +
                "current_paragraph INTEGER," +
                "shelf_type TEXT," +
                "is_downloaded INTEGER," +
                "last_read_timestamp INTEGER" +
                ")");

        // Миграция колонки annotation для существующих баз
        try {
            db.execSQL("ALTER TABLE books ADD COLUMN annotation TEXT");
        } catch (Exception ignored) {}

        // Таблица глав
        db.execSQL("CREATE TABLE IF NOT EXISTS chapters (" +
                "id TEXT PRIMARY KEY," +
                "book_uuid TEXT," +
                "chapter_index INTEGER," +
                "title TEXT," +
                "content TEXT," +
                "is_downloaded INTEGER" +
                ")");

        // Таблица прогресса
        db.execSQL("CREATE TABLE IF NOT EXISTS progress (" +
                "book_uuid TEXT PRIMARY KEY," +
                "percent REAL," +
                "chapter_index INTEGER," +
                "paragraph_index INTEGER," +
                "page_index INTEGER," +
                "timestamp INTEGER," +
                "is_synced INTEGER" +
                ")");

        // Очередь офлайн-синхронизации прогресса
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_queue (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "book_uuid TEXT," +
                "percent REAL," +
                "chapter_index INTEGER," +
                "paragraph_index INTEGER," +
                "timestamp INTEGER" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        createTables(db);
    }

    public synchronized void saveBooks(List<Book> books, String shelfType) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Book book : books) {
                saveOrUpdateBookInternal(db, book, shelfType);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void saveAllBooks(List<Book> books) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Book book : books) {
                saveOrUpdateBookInternal(db, book, null);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void saveOrUpdateBookInternal(SQLiteDatabase db, Book book, String overrideShelfType) {
        double effectivePercent = book.getPercent();
        boolean effectiveDownloaded = book.isDownloaded();
        long effectiveTimestamp = book.getLastReadTimestamp();

        // 1. Проверяем локальную БД: не перезаписываем уже прочитанное нулевым прогрессом
        try {
            Cursor c = db.rawQuery("SELECT percent, is_downloaded, last_read_timestamp FROM books WHERE uuid = ?", new String[]{book.getUuid()});
            if (c != null) {
                if (c.moveToFirst()) {
                    double localPercent = c.getDouble(0);
                    int localDown = c.getInt(1);
                    long localTs = c.getLong(2);

                    if (localPercent > effectivePercent) {
                        effectivePercent = localPercent;
                    }
                    if (localDown == 1) {
                        effectiveDownloaded = true;
                    }
                    if (localTs > effectiveTimestamp) {
                        effectiveTimestamp = localTs;
                    }
                }
                c.close();
            }

            // 2. Дополнительно сверяем с таблицей progress
            Cursor pc = db.rawQuery("SELECT percent, timestamp FROM progress WHERE book_uuid = ?", new String[]{book.getUuid()});
            if (pc != null) {
                if (pc.moveToFirst()) {
                    double progPercent = pc.getDouble(0);
                    long progTs = pc.getLong(1);
                    if (progPercent > effectivePercent) {
                        effectivePercent = progPercent;
                    }
                    if (progTs > effectiveTimestamp) {
                        effectiveTimestamp = progTs;
                    }
                }
                pc.close();
            }
        } catch (Exception ignored) {}

        ContentValues cv = new ContentValues();
        cv.put("uuid", book.getUuid());
        cv.put("title", book.getTitle());
        cv.put("author", book.getAuthor());
        cv.put("annotation", book.getAnnotation());
        cv.put("cover_url", book.getCoverUrl());
        cv.put("percent", effectivePercent);
        cv.put("shelf_type", overrideShelfType != null ? overrideShelfType : book.getShelfType());
        cv.put("is_downloaded", effectiveDownloaded ? 1 : 0);
        cv.put("last_read_timestamp", effectiveTimestamp);

        db.insertWithOnConflict("books", null, cv, SQLiteDatabase.CONFLICT_REPLACE);

        if (effectivePercent > 0) {
            book.setPercent(effectivePercent);
            syncInitialCloudProgress(db, book);
        }
    }

    private void syncInitialCloudProgress(SQLiteDatabase db, Book book) {
        try {
            Cursor c = db.rawQuery("SELECT percent, timestamp FROM progress WHERE book_uuid = ?", new String[]{book.getUuid()});
            boolean shouldUpdate = true;
            if (c != null) {
                if (c.moveToFirst()) {
                    double existingPercent = c.getDouble(0);
                    if (existingPercent >= book.getPercent()) {
                        shouldUpdate = false;
                    }
                }
                c.close();
            }

            if (shouldUpdate) {
                ContentValues pCv = new ContentValues();
                pCv.put("book_uuid", book.getUuid());
                pCv.put("percent", book.getPercent());
                pCv.put("chapter_index", book.getCurrentChapterIndex());
                pCv.put("paragraph_index", book.getCurrentParagraphIndex());
                pCv.put("page_index", 0);
                pCv.put("timestamp", book.getLastReadTimestamp());
                pCv.put("is_synced", 1);
                db.insertWithOnConflict("progress", null, pCv, SQLiteDatabase.CONFLICT_REPLACE);
            }
        } catch (Exception ignored) {}
    }

    public synchronized void updateBookDownloaded(String uuid, boolean isDownloaded) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("is_downloaded", isDownloaded ? 1 : 0);
        db.update("books", cv, "uuid = ?", new String[]{uuid});
    }

    public synchronized int getBooksCountByShelf(String shelfType) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM books WHERE shelf_type = ?", new String[]{shelfType});
        int count = 0;
        if (c != null) {
            if (c.moveToFirst()) {
                count = c.getInt(0);
            }
            c.close();
        }
        return count;
    }

    public synchronized Book getBookByUuid(String uuid) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM books WHERE uuid = ?", new String[]{uuid});
        Book b = null;
        if (c != null) {
            if (c.moveToFirst()) {
                b = parseBookFromCursor(c);
            }
            c.close();
        }
        return b;
    }

    public synchronized List<Book> getBooksByShelf(String shelfType) {
        List<Book> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM books WHERE shelf_type = ? ORDER BY last_read_timestamp DESC", new String[]{shelfType});
        if (c != null) {
            while (c.moveToNext()) {
                list.add(parseBookFromCursor(c));
            }
            c.close();
        }
        return list;
    }

    private Book parseBookFromCursor(Cursor c) {
        Book b = new Book();
        b.setUuid(c.getString(c.getColumnIndex("uuid")));
        b.setTitle(c.getString(c.getColumnIndex("title")));
        b.setAuthor(c.getString(c.getColumnIndex("author")));

        int annIdx = c.getColumnIndex("annotation");
        if (annIdx >= 0) {
            b.setAnnotation(c.getString(annIdx));
        }

        b.setCoverUrl(c.getString(c.getColumnIndex("cover_url")));
        b.setPercent(c.getDouble(c.getColumnIndex("percent")));
        b.setShelfType(c.getString(c.getColumnIndex("shelf_type")));
        b.setDownloaded(c.getInt(c.getColumnIndex("is_downloaded")) == 1);
        b.setLastReadTimestamp(c.getLong(c.getColumnIndex("last_read_timestamp")));
        return b;
    }

    public synchronized void saveChapters(String bookUuid, List<Chapter> chapters) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Chapter ch : chapters) {
                ContentValues cv = new ContentValues();
                cv.put("id", ch.getId());
                cv.put("book_uuid", bookUuid);
                cv.put("chapter_index", ch.getChapterIndex());
                cv.put("title", ch.getTitle());
                cv.put("is_downloaded", 1);
                db.insertWithOnConflict("chapters", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<Chapter> getChapters(String bookUuid) {
        List<Chapter> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM chapters WHERE book_uuid = ? ORDER BY chapter_index ASC", new String[]{bookUuid});
        if (c != null) {
            while (c.moveToNext()) {
                Chapter ch = new Chapter();
                ch.setId(c.getString(c.getColumnIndex("id")));
                ch.setBookUuid(c.getString(c.getColumnIndex("book_uuid")));
                ch.setChapterIndex(c.getInt(c.getColumnIndex("chapter_index")));
                ch.setTitle(c.getString(c.getColumnIndex("title")));
                list.add(ch);
            }
            c.close();
        }
        return list;
    }

    public synchronized void saveProgress(ReadingProgress progress) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("book_uuid", progress.getBookUuid());
        cv.put("percent", progress.getPercent());
        cv.put("chapter_index", progress.getChapterIndex());
        cv.put("paragraph_index", progress.getParagraphIndex());
        cv.put("page_index", progress.getPageIndex());
        cv.put("timestamp", progress.getTimestamp());
        cv.put("is_synced", progress.isSyncedWithServer() ? 1 : 0);
        db.insertWithOnConflict("progress", null, cv, SQLiteDatabase.CONFLICT_REPLACE);

        // Также обновляем процент в таблице books
        ContentValues bookCv = new ContentValues();
        bookCv.put("percent", progress.getPercent());
        bookCv.put("last_read_timestamp", progress.getTimestamp());
        db.update("books", bookCv, "uuid = ?", new String[]{progress.getBookUuid()});
    }

    public synchronized ReadingProgress getProgress(String bookUuid) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM progress WHERE book_uuid = ?", new String[]{bookUuid});
        ReadingProgress p = null;
        if (c != null) {
            if (c.moveToFirst()) {
                p = new ReadingProgress();
                p.setBookUuid(c.getString(c.getColumnIndex("book_uuid")));
                p.setPercent(c.getDouble(c.getColumnIndex("percent")));
                p.setChapterIndex(c.getInt(c.getColumnIndex("chapter_index")));
                p.setParagraphIndex(c.getInt(c.getColumnIndex("paragraph_index")));
                p.setPageIndex(c.getInt(c.getColumnIndex("page_index")));
                p.setTimestamp(c.getLong(c.getColumnIndex("timestamp")));
                p.setSyncedWithServer(c.getInt(c.getColumnIndex("is_synced")) == 1);
            }
            c.close();
        }
        return p;
    }

    public synchronized void enqueueOfflineProgress(ReadingProgress p) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("book_uuid", p.getBookUuid());
        cv.put("percent", p.getPercent());
        cv.put("chapter_index", p.getChapterIndex());
        cv.put("paragraph_index", p.getParagraphIndex());
        cv.put("timestamp", p.getTimestamp());
        db.insert("sync_queue", null, cv);
    }

    public synchronized List<ReadingProgress> getPendingSyncQueue() {
        List<ReadingProgress> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM sync_queue ORDER BY timestamp ASC", null);
        if (c != null) {
            while (c.moveToNext()) {
                ReadingProgress p = new ReadingProgress();
                p.setBookUuid(c.getString(c.getColumnIndex("book_uuid")));
                p.setPercent(c.getDouble(c.getColumnIndex("percent")));
                p.setChapterIndex(c.getInt(c.getColumnIndex("chapter_index")));
                p.setParagraphIndex(c.getInt(c.getColumnIndex("paragraph_index")));
                p.setTimestamp(c.getLong(c.getColumnIndex("timestamp")));
                list.add(p);
            }
            c.close();
        }
        return list;
    }

    public synchronized void clearSyncQueue() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("sync_queue", null, null);
    }
}
