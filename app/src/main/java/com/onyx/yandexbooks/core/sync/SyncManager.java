package com.onyx.yandexbooks.core.sync;

import android.content.Context;
import android.util.Log;

import com.onyx.yandexbooks.core.api.YandexBooksApiClient;
import com.onyx.yandexbooks.core.api.models.ReadingProgress;
import com.onyx.yandexbooks.core.storage.DatabaseHelper;

import java.util.List;

/**
 * Менеджер синхронизации с защитой от перезаписи (Smart Conflict Resolution).
 */
public class SyncManager {

    private static final String TAG = "SyncManager";

    public interface ConflictResolutionCallback {
        void onProgressUpdated(ReadingProgress progress);
        void onConflictDetected(ReadingProgress localProgress, ReadingProgress remoteProgress);
    }

    public interface SyncCompleteCallback {
        void onSyncFinished(int count);
    }

    private final DatabaseHelper dbHelper;
    private final YandexBooksApiClient apiClient;
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> RECENT_LOCAL_SAVES_ELAPSED =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE_CLOUD_SYNCS =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> IDLE_WAITERS =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static void onCloudSyncStarted() {
        ACTIVE_CLOUD_SYNCS.incrementAndGet();
    }

    private static void onCloudSyncFinished() {
        int rem = ACTIVE_CLOUD_SYNCS.decrementAndGet();
        if (rem <= 0) {
            ACTIVE_CLOUD_SYNCS.compareAndSet(rem, 0);
            Runnable r;
            while ((r = IDLE_WAITERS.poll()) != null) {
                try {
                    r.run();
                } catch (Throwable ignored) {}
            }
        }
    }

    public void runWhenIdle(final Runnable callback) {
        if (callback == null) return;
        if (ACTIVE_CLOUD_SYNCS.get() <= 0) {
            callback.run();
            return;
        }
        IDLE_WAITERS.offer(callback);
        if (ACTIVE_CLOUD_SYNCS.get() <= 0) {
            Runnable r = IDLE_WAITERS.poll();
            while (r != null) {
                try { r.run(); } catch (Throwable ignored) {}
                r = IDLE_WAITERS.poll();
            }
        }
    }

    public static void markBookSavedRecently(String bookUuid) {
        if (bookUuid == null || bookUuid.isEmpty()) return;
        try {
            RECENT_LOCAL_SAVES_ELAPSED.put(bookUuid, android.os.SystemClock.elapsedRealtime());
        } catch (Throwable ignored) {}
    }

    public static boolean wasBookSavedRecently(String bookUuid, long windowMs) {
        if (bookUuid == null || bookUuid.isEmpty()) return false;
        Long ts = RECENT_LOCAL_SAVES_ELAPSED.get(bookUuid);
        if (ts == null) return false;
        try {
            return (android.os.SystemClock.elapsedRealtime() - ts) <= windowMs;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public SyncManager(Context context, YandexBooksApiClient apiClient) {
        this.dbHelper = DatabaseHelper.getInstance(context);
        this.apiClient = apiClient;
    }

    /**
     * Сохранение новой позиции чтения с умным решением конфликтов.
     */
    public void saveAndSyncProgress(final ReadingProgress localProgress, boolean isOnline, final ConflictResolutionCallback callback) {
        if (localProgress == null || localProgress.getBookUuid() == null) return;
        try {
            if (localProgress.getLibraryCardId() == null || localProgress.getLibraryCardId().isEmpty()
                    || localProgress.getDocumentUuid() == null || localProgress.getDocumentUuid().isEmpty()) {
                try {
                    com.onyx.yandexbooks.core.api.models.Book b = dbHelper.getBookByUuid(localProgress.getBookUuid());
                    if (b != null) {
                        if (b.getLibraryCardId() != null && !b.getLibraryCardId().isEmpty()) {
                            localProgress.setLibraryCardId(b.getLibraryCardId());
                        }
                        if (b.getDocumentUuid() != null && !b.getDocumentUuid().isEmpty()) {
                            localProgress.setDocumentUuid(b.getDocumentUuid());
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Всегда сохраняем в локальную БД мгновенно и защищаем от гонки с устаревшим кэшем облака
            markBookSavedRecently(localProgress.getBookUuid());
            dbHelper.saveProgress(localProgress);

            if (!isOnline) {
                // Офлайн-режим: добавляем в очередь синхронизации
                dbHelper.enqueueOfflineProgress(localProgress);
                Log.d(TAG, "Device is offline. Progress enqueued for future sync: " + localProgress.getPercent() + "%");
                if (callback != null) {
                    callback.onProgressUpdated(localProgress);
                }
                return;
            }

            // Онлайн: отправляем на сервер
            final ReadingProgress snapshot = new ReadingProgress(
                    localProgress.getBookUuid(),
                    localProgress.getPercent(),
                    localProgress.getChapterIndex(),
                    localProgress.getParagraphIndex(),
                    localProgress.getPageIndex(),
                    localProgress.getTimestamp()
            );
            snapshot.setLibraryCardId(localProgress.getLibraryCardId());
            snapshot.setDocumentUuid(localProgress.getDocumentUuid());
            snapshot.setCfi(localProgress.getCfi());

            onCloudSyncStarted();
            apiClient.sendReadingProgress(snapshot, new YandexBooksApiClient.ApiCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    try {
                        dbHelper.markProgressSynced(snapshot.getBookUuid(), snapshot.getTimestamp());
                        ReadingProgress latest = dbHelper.getProgress(snapshot.getBookUuid());
                        if (latest == null || latest.getTimestamp() <= snapshot.getTimestamp()) {
                            dbHelper.removeFromSyncQueue(snapshot.getBookUuid());
                        }
                        Log.d(TAG, "Progress successfully synced to Yandex cloud.");
                        if (callback != null) {
                            callback.onProgressUpdated(snapshot);
                        }
                    } finally {
                        onCloudSyncFinished();
                    }
                }

                @Override
                public void onError(String errorMessage) {
                    try {
                        Log.w(TAG, "Failed to send progress: " + errorMessage + ". Enqueuing.");
                        dbHelper.enqueueOfflineProgress(snapshot);
                    } finally {
                        onCloudSyncFinished();
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Error in saveAndSyncProgress", t);
        }
    }

    /**
     * Сравнение прогресса между сервером и ридером при входе или подключении Wi-Fi.
     * Возвращает true, если конфликт отсутствует и локальная позиция актуальна.
     */
    public void evaluateProgressConflict(ReadingProgress local, ReadingProgress remote, ConflictResolutionCallback callback) {
        if (remote == null) {
            if (callback != null) callback.onProgressUpdated(local);
            return;
        }
        if (local == null) {
            dbHelper.saveProgress(remote);
            if (callback != null) callback.onProgressUpdated(remote);
            return;
        }

        // 1. Позиция на сервере строго больше и дата новее -> обнаружено чтение со смартфона/ПК
        if (remote.getPercent() > local.getPercent() && remote.getTimestamp() > local.getTimestamp()) {
            Log.i(TAG, "Conflict: Remote progress is further (" + remote.getPercent() + "%) than local (" + local.getPercent() + "%).");
            if (callback != null) {
                callback.onConflictDetected(local, remote);
            }
            return;
        }

        // 2. Локальная позиция больше или равна -> обновляем облако
        if (local.getPercent() >= remote.getPercent()) {
            saveAndSyncProgress(local, true, callback);
        } else {
            // В спорных случаях предлагаем выбор
            if (callback != null) {
                callback.onConflictDetected(local, remote);
            }
        }
    }

    /**
     * Отправка накопившейся офлайн-очереди при восстановлении Wi-Fi.
     */
    public void flushOfflineQueue() {
        flushOfflineQueue(null);
    }

    public void flushOfflineQueue(final SyncCompleteCallback onComplete) {
        List<ReadingProgress> queue = dbHelper.getUnsyncedProgressList();
        if (queue.isEmpty()) {
            if (onComplete != null) onComplete.onSyncFinished(0);
            return;
        }

        final int total = queue.size();
        final java.util.concurrent.atomic.AtomicInteger syncedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger processedCount = new java.util.concurrent.atomic.AtomicInteger(0);

        Log.i(TAG, "Flushing " + total + " pending offline progress events to Yandex cloud...");
        for (final ReadingProgress p : queue) {
            if (p.getLibraryCardId() == null || p.getLibraryCardId().isEmpty()
                    || p.getDocumentUuid() == null || p.getDocumentUuid().isEmpty()) {
                try {
                    com.onyx.yandexbooks.core.api.models.Book b = dbHelper.getBookByUuid(p.getBookUuid());
                    if (b != null) {
                        if (b.getLibraryCardId() != null && !b.getLibraryCardId().isEmpty()) {
                            p.setLibraryCardId(b.getLibraryCardId());
                        }
                        if (b.getDocumentUuid() != null && !b.getDocumentUuid().isEmpty()) {
                            p.setDocumentUuid(b.getDocumentUuid());
                        }
                    }
                } catch (Exception ignored) {}
            }

            onCloudSyncStarted();
            apiClient.sendReadingProgress(p, new YandexBooksApiClient.ApiCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    try {
                        dbHelper.markProgressSynced(p.getBookUuid(), p.getTimestamp());
                        ReadingProgress latest = dbHelper.getProgress(p.getBookUuid());
                        if (latest == null || latest.getTimestamp() <= p.getTimestamp()) {
                            dbHelper.removeFromSyncQueue(p.getBookUuid());
                        }
                        syncedCount.incrementAndGet();
                        checkDone();
                    } finally {
                        onCloudSyncFinished();
                    }
                }

                @Override
                public void onError(String errorMessage) {
                    try {
                        Log.w(TAG, "Sync retry failed for book " + p.getBookUuid() + ": " + errorMessage + ". Will retry later.");
                        checkDone();
                    } finally {
                        onCloudSyncFinished();
                    }
                }

                private void checkDone() {
                    if (processedCount.incrementAndGet() >= total) {
                        Log.i(TAG, "Flush completed. Successfully synced " + syncedCount.get() + " of " + total + " books.");
                        if (onComplete != null) {
                            onComplete.onSyncFinished(syncedCount.get());
                        }
                    }
                }
            });
        }
    }
}
