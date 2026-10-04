package com.onyx.yandexbooks;

import android.app.Application;
import android.util.Log;

import com.onyx.yandexbooks.core.network.SecurityProvider;
import com.onyx.yandexbooks.core.storage.DatabaseHelper;

/**
 * Главный класс приложения.
 * Выполняет первичную инициализацию криптопровайдера Conscrypt для поддержки TLS 1.2/1.3
 * на старых версиях Android 4.2 (Darwin 3) и Android 4.4 (Darwin 5/6).
 */
public class YandexBooksApp extends Application {

    private static final String TAG = "YandexBooksApp";
    private static YandexBooksApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // 1. Активация современного TLS 1.2 / TLS 1.3 через Conscrypt
        boolean securityInitialized = SecurityProvider.initializeSecurityProvider();
        if (securityInitialized) {
            Log.i(TAG, "Conscrypt security provider successfully installed. TLS 1.2/1.3 active.");
        } else {
            Log.w(TAG, "Conscrypt provider initialization skipped or failed, fallback to system TLS.");
        }

        // 2. Инициализация базы данных SQLite
        DatabaseHelper.getInstance(this);
    }

    public static YandexBooksApp getInstance() {
        return instance;
    }
}
