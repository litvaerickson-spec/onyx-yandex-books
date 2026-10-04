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

    private final DatabaseHelper dbHelper;
    private final YandexBooksApiClient apiClient;

    public SyncManager(Context context, YandexBooksApiClient apiClient) {
        this.dbHelper = DatabaseHelper.getInstance(context);
        this.apiClient = apiClient;
    }

    /**
     * Сохранение новой позиции чтения с умным решением конфликтов.
     */
    public void saveAndSyncProgress(final ReadingProgress localProgress, boolean isOnline, final ConflictResolutionCallback callback) {
        // Всегда сохраняем в локальную БД мгновенно
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
        apiClient.sendReadingProgress(localProgress, new YandexBooksApiClient.ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                localProgress.setSyncedWithServer(true);
                dbHelper.saveProgress(localProgress);
                Log.d(TAG, "Progress successfully synced to Yandex cloud.");
                if (callback != null) {
                    callback.onProgressUpdated(localProgress);
                }
            }

            @Override
            public void onError(String errorMessage) {
                Log.w(TAG, "Failed to send progress: " + errorMessage + ". Enqueuing.");
                dbHelper.enqueueOfflineProgress(localProgress);
            }
        });
    }

    /**
     * Сравнение прогресса между сервером и читалкой при входе или подключении Wi-Fi.
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
        List<ReadingProgress> queue = dbHelper.getPendingSyncQueue();
        if (queue.isEmpty()) return;

        Log.i(TAG, "Flushing " + queue.size() + " pending offline progress events...");
        for (ReadingProgress p : queue) {
            apiClient.sendReadingProgress(p, new YandexBooksApiClient.ApiCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {}
                @Override
                public void onError(String errorMessage) {}
            });
        }
        dbHelper.clearSyncQueue();
    }
}
