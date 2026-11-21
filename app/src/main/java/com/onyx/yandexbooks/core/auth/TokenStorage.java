package com.onyx.yandexbooks.core.auth;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Хранилище токенов авторизации OAuth.
 */
public class TokenStorage {

    private static final String PREF_NAME = "yandex_auth_prefs";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_USER_LOGIN = "user_login";

    private static final java.util.concurrent.atomic.AtomicLong AUTH_SESSION_EPOCH =
            new java.util.concurrent.atomic.AtomicLong(1L);

    private final SharedPreferences prefs;

    public TokenStorage(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static long getCurrentAuthEpoch() {
        return AUTH_SESSION_EPOCH.get();
    }

    public synchronized void saveTokens(String accessToken, String refreshToken, long expiresInSeconds) {
        long safeExpires = expiresInSeconds > 0L ? expiresInSeconds : 31536000L;
        long expiresAt = System.currentTimeMillis() + (safeExpires * 1000L);
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .commit();
        AUTH_SESSION_EPOCH.incrementAndGet();
    }

    public synchronized String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, null);
    }

    public synchronized String getRefreshToken() {
        return prefs.getString(KEY_REFRESH_TOKEN, null);
    }

    public synchronized long getExpiresAt() {
        return prefs.getLong(KEY_EXPIRES_AT, 0L);
    }

    public synchronized boolean isTokenExpired() {
        long exp = prefs.getLong(KEY_EXPIRES_AT, 0L);
        return exp > 0L && System.currentTimeMillis() >= exp;
    }

    public synchronized boolean isAuthorized() {
        String token = getAccessToken();
        return token != null && !token.isEmpty();
    }

    public synchronized void clear() {
        AUTH_SESSION_EPOCH.incrementAndGet();
        prefs.edit().clear().commit();
    }

    public synchronized void setUserLogin(String login) {
        prefs.edit().putString(KEY_USER_LOGIN, login).apply();
    }

    public synchronized String getUserLogin() {
        return prefs.getString(KEY_USER_LOGIN, "");
    }
}
