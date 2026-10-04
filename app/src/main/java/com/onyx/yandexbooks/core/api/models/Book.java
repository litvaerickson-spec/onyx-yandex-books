package com.onyx.yandexbooks.core.api.models;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Модель данных книги.
 */
public class Book implements Serializable {
    private String uuid;
    private String title;
    private String author;
    private String coverUrl;
    private String localCoverPath;
    private double percent;
    private int totalChapters;
    private int currentChapterIndex;
    private int currentParagraphIndex;
    private String shelfType; // reading, to_read, done
    private boolean isDownloaded;
    private long lastReadTimestamp;

    public Book() {
    }

    public Book(String uuid, String title, String author, String coverUrl) {
        this.uuid = uuid;
        this.title = title;
        this.author = author;
        this.coverUrl = coverUrl;
        this.percent = 0.0;
        this.isDownloaded = false;
        this.lastReadTimestamp = System.currentTimeMillis();
    }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }

    public String getLocalCoverPath() { return localCoverPath; }
    public void setLocalCoverPath(String localCoverPath) { this.localCoverPath = localCoverPath; }

    public double getPercent() { return percent; }
    public void setPercent(double percent) { this.percent = percent; }

    public int getTotalChapters() { return totalChapters; }
    public void setTotalChapters(int totalChapters) { this.totalChapters = totalChapters; }

    public int getCurrentChapterIndex() { return currentChapterIndex; }
    public void setCurrentChapterIndex(int currentChapterIndex) { this.currentChapterIndex = currentChapterIndex; }

    public int getCurrentParagraphIndex() { return currentParagraphIndex; }
    public void setCurrentParagraphIndex(int currentParagraphIndex) { this.currentParagraphIndex = currentParagraphIndex; }

    public String getShelfType() { return shelfType; }
    public void setShelfType(String shelfType) { this.shelfType = shelfType; }

    public boolean isDownloaded() { return isDownloaded; }
    public void setDownloaded(boolean downloaded) { isDownloaded = downloaded; }

    private String annotation;

    public String getAnnotation() { return annotation; }
    public void setAnnotation(String annotation) { this.annotation = annotation; }

    public long getLastReadTimestamp() { return lastReadTimestamp; }
    public void setLastReadTimestamp(long lastReadTimestamp) { this.lastReadTimestamp = lastReadTimestamp; }
}
