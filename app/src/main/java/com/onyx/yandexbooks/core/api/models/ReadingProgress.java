package com.onyx.yandexbooks.core.api.models;

import java.io.Serializable;

/**
 * Модель позиции и прогресса чтения.
 */
public class ReadingProgress implements Serializable {
    private String bookUuid;
    private double percent;
    private int chapterIndex;
    private int paragraphIndex;
    private int pageIndex;
    private long timestamp;
    private boolean isSyncedWithServer;

    public ReadingProgress() {}

    public ReadingProgress(String bookUuid, double percent, int chapterIndex, int paragraphIndex, int pageIndex, long timestamp) {
        this.bookUuid = bookUuid;
        this.percent = percent;
        this.chapterIndex = chapterIndex;
        this.paragraphIndex = paragraphIndex;
        this.pageIndex = pageIndex;
        this.timestamp = timestamp;
        this.isSyncedWithServer = false;
    }

    public String getBookUuid() { return bookUuid; }
    public void setBookUuid(String bookUuid) { this.bookUuid = bookUuid; }

    public double getPercent() { return percent; }
    public void setPercent(double percent) { this.percent = percent; }

    public int getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(int chapterIndex) { this.chapterIndex = chapterIndex; }

    public int getParagraphIndex() { return paragraphIndex; }
    public void setParagraphIndex(int paragraphIndex) { this.paragraphIndex = paragraphIndex; }

    public int getPageIndex() { return pageIndex; }
    public void setPageIndex(int pageIndex) { this.pageIndex = pageIndex; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public boolean isSyncedWithServer() { return isSyncedWithServer; }
    public void setSyncedWithServer(boolean syncedWithServer) { isSyncedWithServer = syncedWithServer; }
}
