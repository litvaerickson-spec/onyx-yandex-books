package com.onyx.yandexbooks.core.auth;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.onyx.yandexbooks.core.network.HttpClientFactory;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Менеджер авторизации Яндекс OAuth Device Code Flow.
 * Позволяет авторизоваться на устройстве без ввода логина и пароля,
 * выводя код и ссылку для подтверждения на смартфоне/ПК.
 */
public class DeviceCodeAuthManager {

    private static final String TAG = "DeviceCodeAuth";
    private static final String DEVICE_CODE_URL = "https://oauth.yandex.ru/device/code";
    private static final String TOKEN_URL = "https://oauth.yandex.ru/token";

    // Официальный Client ID и Secret Яндекс с правами доступа к аккаунту и медиатеке
    private static final String DEFAULT_CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d";
    private static final String DEFAULT_CLIENT_SECRET = "53bc75238f0c4d08a118e51fe9203300";

    public interface DeviceCodeCallback {
        void onSuccess(String deviceCode, String userCode, String verificationUrl, int interval);
        void onError(String errorMessage);
    }

    public interface TokenCallback {
        void onAuthorized(String accessToken, String refreshToken, long expiresIn);
        void onPending(int attemptCount);
        void onError(String errorMessage);
    }

    private final OkHttpClient httpClient;
    private final TokenStorage tokenStorage;
    private final Handler mainHandler;
    private boolean isPollingActive = false;
    private int pollAttempt = 0;

    public DeviceCodeAuthManager(TokenStorage tokenStorage) {
        this.httpClient = HttpClientFactory.getClient();
        this.tokenStorage = tokenStorage;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Запрос кода устройства у Яндекса.
     */
    public void requestDeviceCode(final DeviceCodeCallback callback) {
        RequestBody formBody = new FormBody.Builder()
                .add("client_id", DEFAULT_CLIENT_ID)
                .build();

        Request request = new Request.Builder()
                .url(DEVICE_CODE_URL)
                .post(formBody)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, final IOException e) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onError("Сетевая ошибка: " + e.getMessage());
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    final String err = "Ошибка сервера Яндекса: HTTP " + response.code();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(err);
                        }
                    });
                    return;
                }

                try {
                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);
                    final String deviceCode = json.getString("device_code");
                    final String userCode = json.getString("user_code");
                    final String verificationUrl = json.optString("verification_url", "https://ya.ru/device");
                    final int interval = json.optInt("interval", 5);

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(deviceCode, userCode, verificationUrl, interval);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError("Ошибка парсинга ответа: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    /**
     * Запуск фонового опроса статуса подтверждения кода пользователем.
     */
    public void startPolling(final String deviceCode, final int intervalSeconds, final TokenCallback callback) {
        stopPolling();
        isPollingActive = true;
        pollAttempt = 0;
        pollTokenStep(deviceCode, intervalSeconds, callback);
    }

    private void pollTokenStep(final String deviceCode, final int intervalSeconds, final TokenCallback callback) {
        if (!isPollingActive) {
            return;
        }

        RequestBody formBody = new FormBody.Builder()
                .add("grant_type", "device_code")
                .add("code", deviceCode)
                .add("client_id", DEFAULT_CLIENT_ID)
                .add("client_secret", DEFAULT_CLIENT_SECRET)
                .build();

        Request request = new Request.Builder()
                .url(TOKEN_URL)
                .post(formBody)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                scheduleNextPoll(deviceCode, intervalSeconds, callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!isPollingActive) return;

                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);

                    if (response.isSuccessful() && json.has("access_token")) {
                        final String accessToken = json.getString("access_token");
                        final String refreshToken = json.optString("refresh_token", "");
                        final long expiresIn = json.optLong("expires_in", 31536000L);

                        tokenStorage.saveTokens(accessToken, refreshToken, expiresIn);
                        isPollingActive = false;

                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onAuthorized(accessToken, refreshToken, expiresIn);
                            }
                        });
                        return;
                    }

                    String error = json.optString("error", "");
                    if ("authorization_pending".equals(error) || "slow_down".equals(error)) {
                        pollAttempt++;
                        final int currentAttempt = pollAttempt;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onPending(currentAttempt);
                            }
                        });
                        int nextInterval = intervalSeconds;
                        if ("slow_down".equals(error)) {
                            nextInterval += 3;
                        }
                        scheduleNextPoll(deviceCode, nextInterval, callback);
                    } else if ("expired_token".equals(error)) {
                        isPollingActive = false;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError("Время действия кода истекло. Запрашиваю новый код...");
                            }
                        });
                    } else if ("access_denied".equals(error)) {
                        isPollingActive = false;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError("Вход отклонен в аккаунте Яндекса.");
                            }
                        });
                    } else if ("invalid_grant".equals(error)) {
                        isPollingActive = false;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError("Код недействителен или устарел.");
                            }
                        });
                    } else {
                        pollAttempt++;
                        if (pollAttempt > 60) {
                            isPollingActive = false;
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onError("Время ожидания истекло.");
                                }
                            });
                        } else {
                            scheduleNextPoll(deviceCode, intervalSeconds + 1, callback);
                        }
                    }
                } catch (Exception e) {
                    scheduleNextPoll(deviceCode, intervalSeconds, callback);
                }
            }
        });
    }

    private void scheduleNextPoll(final String deviceCode, final int intervalSeconds, final TokenCallback callback) {
        if (!isPollingActive) return;
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollTokenStep(deviceCode, intervalSeconds, callback);
            }
        }, Math.max(intervalSeconds + 1, 6) * 1000L);
    }

    public void stopPolling() {
        isPollingActive = false;
        mainHandler.removeCallbacksAndMessages(null);
    }
}
