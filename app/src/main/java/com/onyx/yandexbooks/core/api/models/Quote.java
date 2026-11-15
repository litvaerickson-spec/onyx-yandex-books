package com.onyx.yandexbooks.core.api.models;

import java.io.Serializable;

/**
 * Модель выделенной цитаты и заметки к ней.
 */
public class Quote implements Serializable {
    public long id;
    public String bookUuid;
    public String quoteUuid;
    public int chapterIndex;
    public int startOffset;
    public int endOffset;
    public String cfi;
    public String text;
    public String note;
    public long createdAt;
    public boolean synced;

    public Quote() {
    }
}
