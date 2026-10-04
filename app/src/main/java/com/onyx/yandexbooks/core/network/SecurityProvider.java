package com.onyx.yandexbooks.core.network;

import android.util.Log;

import java.security.Provider;
import java.security.Security;

/**
 * Инициализатор современного криптографического провайдера Google Conscrypt.
 * Решает проблему устаревшего системного OpenSSL на Android 4.2 / 4.4,
 * добавляя поддержку TLSv1.2, TLSv1.3 и современных наборов шифров (GCM, ChaCha20).
 */
public class SecurityProvider {

    private static final String TAG = "SecurityProvider";
    private static boolean isInitialized = false;

    public static synchronized boolean initializeSecurityProvider() {
        if (isInitialized) {
            return true;
        }

        try {
            // Динамическая загрузка Conscrypt для предотвращения сбоя при отсутствии в classpath
            Class<?> conscryptClass = Class.forName("org.conscrypt.Conscrypt");
            Provider provider = (Provider) conscryptClass.getMethod("newProvider").invoke(null);
            
            if (provider != null) {
                // Вставляем на первое место в списке провайдеров
                int position = Security.insertProviderAt(provider, 1);
                Log.i(TAG, "Conscrypt provider installed at position " + position);
                isInitialized = true;
                return true;
            }
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "Conscrypt library not found in classpath. Relying on system SSL engine.");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize Conscrypt provider", t);
        }

        return false;
    }

    public static boolean isModernTlsAvailable() {
        return isInitialized;
    }
}
