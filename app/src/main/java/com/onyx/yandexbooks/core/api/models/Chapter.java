package com.onyx.yandexbooks.core.api.models;

import java.io.Serializable;

/**
 * Модель отдельной главы книги.
 */
public class Chapter implements Serializable {
    private String id;
    private String bookUuid;
    private int chapterIndex;
    private String title;
    private String content; // Распарсенный текст или разметка
    private boolean isDownloaded;

    public Chapter() {}

    public Chapter(String id, String bookUuid, int chapterIndex, String title) {
        this.id = id;
        this.bookUuid = bookUuid;
        this.chapterIndex = chapterIndex;
        this.title = title;
        this.isDownloaded = false;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getBookUuid() { return bookUuid; }
    public void setBookUuid(String bookUuid) { this.bookUuid = bookUuid; }

    public int getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(int chapterIndex) { this.chapterIndex = chapterIndex; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public boolean isDownloaded() { return isDownloaded; }
    public void setDownloaded(boolean downloaded) { isDownloaded = downloaded; }
}
