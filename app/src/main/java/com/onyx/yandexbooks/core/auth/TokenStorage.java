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

    private final SharedPreferences prefs;

    public TokenStorage(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public synchronized void saveTokens(String accessToken, String refreshToken, long expiresInSeconds) {
        long expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L);
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .apply();
    }

    public synchronized String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, null);
    }

    public synchronized String getRefreshToken() {
        return prefs.getString(KEY_REFRESH_TOKEN, null);
    }

    public synchronized boolean isAuthorized() {
        String token = getAccessToken();
        return token != null && !token.isEmpty();
    }

    public synchronized void clear() {
        prefs.edit().clear().apply();
    }

    public synchronized void setUserLogin(String login) {
        prefs.edit().putString(KEY_USER_LOGIN, login).apply();
    }

    public synchronized String getUserLogin() {
        return prefs.getString(KEY_USER_LOGIN, "");
    }
}
