package com.onyx.yandexbooks.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.onyx.yandexbooks.R;
import com.onyx.yandexbooks.core.auth.LocalAuthServer;
import com.onyx.yandexbooks.core.auth.TokenStorage;
import com.onyx.yandexbooks.core.eink.EpdController;
import com.onyx.yandexbooks.core.network.HttpClientFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Универсальный экран авторизации в Яндекс Книги.
 * Поддерживает 5 способов входа (не зависит от одной сети!):
 * 1. Встроенный браузер прямо на устройстве (включая QR-код Яндекс Ключа).
 * 2. Облачный мост через интернет (любая сеть со смартфона: 4G / LTE / другой Wi-Fi).
 * 3. Локальный Wi-Fi мост устройства (если устройства в одной сети или режим модема).
 * 4. Загрузка файла токена yandex_token.txt с внутренней памяти/SD-карты по USB.
 * 5. Прямой ввод токена или ссылки вручную.
 */
public class AuthActivity extends Activity {

    private static final String TAG = "AuthActivity";
    private static final int REQUEST_WEBVIEW = 1002;
    private static final Pattern TOKEN_REGEX = Pattern.compile("y0_[A-Za-z0-9_-]{15,}");

    private ImageView qrImageView;
    private TextView localUrlTextView;
    private TextView statusTextView;
    private Button btnOpenWebView;
    private Button btnImportFile;
    private Button manualTokenButton;
    private Button retryButton;

    private TokenStorage tokenStorage;
    private LocalAuthServer localAuthServer;

    private String cloudSessionId;
    private Thread cloudPollThread;
    private volatile boolean isCloudPolling = false;

    private static final String BOOKMATE_CLIENT_ID = "4483e97bab6e486a9822973109a14d05";
    private static final String DIRECT_AUTH_URL = "https://oauth.yandex.ru/authorize?response_type=token&client_id=" + BOOKMATE_CLIENT_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        qrImageView = (ImageView) findViewById(R.id.qr_image);
        localUrlTextView = (TextView) findViewById(R.id.local_url_text);
        statusTextView = (TextView) findViewById(R.id.status_text);
        btnOpenWebView = (Button) findViewById(R.id.btn_open_webview);
        btnImportFile = (Button) findViewById(R.id.btn_import_file);
        manualTokenButton = (Button) findViewById(R.id.manual_token_button);
        retryButton = (Button) findViewById(R.id.retry_button);

        tokenStorage = new TokenStorage(this);

        setupLocalServer();

        btnOpenWebView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(AuthActivity.this, AuthWebViewActivity.class);
                startActivityForResult(intent, REQUEST_WEBVIEW);
            }
        });

        btnImportFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!tryImportTokenFromFile()) {
                    showFileNotFoundDialog();
                }
            }
        });

        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAuthorizationFlow();
                Toast.makeText(AuthActivity.this, "Сетевой статус обновлен", Toast.LENGTH_SHORT).show();
            }
        });

        manualTokenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showManualTokenDialog();
            }
        });

        startAuthorizationFlow();
    }

    private void setupLocalServer() {
        localAuthServer = new LocalAuthServer(new LocalAuthServer.ServerCallback() {
            @Override
            public void onTokenReceived(String token) {
                onAuthSuccess(token, "Локальный Wi-Fi мост");
            }
        });
        localAuthServer.start();
    }

    private void startAuthorizationFlow() {
        stopCloudPolling();

        // Генерируем уникальный облачный сессионный топик
        cloudSessionId = "ybk_" + (100000 + new Random().nextInt(900000));
        final String cloudUrl = "https://ntfy.sh/" + cloudSessionId;

        final String localIp = LocalAuthServer.getLocalIpAddress();
        if (localIp != null) {
            localUrlTextView.setText(cloudUrl);
            statusTextView.setText("Интернет-мост: активен | Локальный Wi-Fi: " + localIp + ":8888\nОжидание подтверждения со смартфона...");
        } else {
            localUrlTextView.setText(cloudUrl);
            statusTextView.setText("Подключите Wi-Fi на устройстве для облачного входа\nили используйте импорт из файла / ручной ввод.");
        }

        // QR-код ведет на облачный шлюз ntfy.sh, доступный из любой мобильной сети (4G/LTE)
        Bitmap qrBitmap = generateQrBitmap(cloudUrl, 240, 240);
        if (qrBitmap != null) {
            qrImageView.setImageBitmap(qrBitmap);
            EpdController.requestFullRefresh(AuthActivity.this, qrImageView);
        }

        sendCloudWelcomeNotice(cloudSessionId);
        startCloudPolling(cloudSessionId);
    }

    private void sendCloudWelcomeNotice(final String topic) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OkHttpClient client = HttpClientFactory.getClient();
                    org.json.JSONObject json = new org.json.JSONObject();
                    json.put("topic", topic);
                    json.put("title", "Вход в Яндекс Книги — Onyx Boox");
                    json.put("message", "1. Нажмите «Войти в Яндекс» ниже.\n2. Скопируйте адрес страницы.\n3. Отправьте ссылку сюда (стрелочка) — устройство авторизуется!");
                    json.put("priority", 4);

                    org.json.JSONArray actions = new org.json.JSONArray();
                    org.json.JSONObject action = new org.json.JSONObject();
                    action.put("action", "view");
                    action.put("label", "1. Нажмите для входа в Яндекс");
                    action.put("url", DIRECT_AUTH_URL);
                    actions.put(action);
                    json.put("actions", actions);

                    okhttp3.RequestBody body = okhttp3.RequestBody.create(
                            okhttp3.MediaType.parse("application/json; charset=utf-8"),
                            json.toString()
                    );

                    Request request = new Request.Builder()
                            .url("https://ntfy.sh")
                            .post(body)
                            .build();

                    Response response = client.newCall(request).execute();
                    if (response.body() != null) {
                        response.body().close();
                    }
                    Log.i(TAG, "Posted JSON welcome notice to cloud relay: " + topic);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to post cloud notice: " + e.getMessage());
                }
            }
        }, "CloudWelcomeNotice").start();
    }

    private void startCloudPolling(final String topic) {
        isCloudPolling = true;
        cloudPollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                OkHttpClient client = HttpClientFactory.getClient();
                String pollUrl = "https://ntfy.sh/" + topic + "/raw?poll=1&since=all";
                Log.i(TAG, "Starting cloud relay poll on " + pollUrl);

                while (isCloudPolling) {
                    try {
                        Request request = new Request.Builder()
                                .url(pollUrl)
                                .get()
                                .build();
                        Response response = client.newCall(request).execute();
                        if (response.isSuccessful() && response.body() != null) {
                            String body = response.body().string().trim();
                            if (!body.isEmpty()) {
                                final String token = extractToken(body);
                                if (token != null) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            onAuthSuccess(token, "Облачный интернет-мост");
                                        }
                                    });
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "Cloud poll tick: " + e.getMessage());
                    }

                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "CloudRelayPollThread");
        cloudPollThread.setDaemon(true);
        cloudPollThread.start();
    }

    private void stopCloudPolling() {
        isCloudPolling = false;
        if (cloudPollThread != null) {
            cloudPollThread.interrupt();
            cloudPollThread = null;
        }
    }

    private void onAuthSuccess(String token, String source) {
        tokenStorage.saveTokens(token, "", 31536000L);
        Toast.makeText(AuthActivity.this, "Вход успешно выполнен (" + source + ")!", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    /**
     * Поиск и импорт файла yandex_token.txt из корня памяти устройства или папки загрузок.
     */
    private boolean tryImportTokenFromFile() {
        java.util.List<File> candidates = new java.util.ArrayList<>();
        
        File extDir = android.os.Environment.getExternalStorageDirectory();
        if (extDir != null) {
            candidates.add(new File(extDir, "yandex_token.txt"));
            candidates.add(new File(extDir, "Download/yandex_token.txt"));
            candidates.add(new File(extDir, "Downloads/yandex_token.txt"));
            candidates.add(new File(extDir, "Books/yandex_token.txt"));
        }
        candidates.add(new File("/sdcard/yandex_token.txt"));
        candidates.add(new File("/sdcard/Download/yandex_token.txt"));
        candidates.add(new File("/storage/emulated/0/yandex_token.txt"));
        candidates.add(new File("/storage/emulated/0/Download/yandex_token.txt"));
        candidates.add(new File("/mnt/sdcard/yandex_token.txt"));
        candidates.add(new File("/mnt/extsd/yandex_token.txt"));
        candidates.add(new File("/mnt/sdcard/Download/yandex_token.txt"));

        for (File file : candidates) {
            if (file.exists() && file.canRead()) {
                try {
                    BufferedReader br = new BufferedReader(new FileReader(file));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append(" ");
                    }
                    br.close();

                    String token = extractToken(sb.toString());
                    if (token != null) {
                        onAuthSuccess(token, "Файл: " + file.getName());
                        return true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to read token file: " + file.getAbsolutePath(), e);
                }
            }
        }
        return false;
    }

    private void showFileNotFoundDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Файл yandex_token.txt не найден");
        builder.setMessage("1. Подключите устройство к компьютеру или телефону по USB кабелю.\n\n" +
                "2. Создайте в корне памяти устройства текстовый файл yandex_token.txt и вставьте туда токен (y0_...).\n\n" +
                "3. Нажмите кнопку «Загрузить токен из файла» снова.\n\n" +
                "Либо воспользуйтесь кнопкой «Войти на этом устройстве».");
        builder.setPositiveButton("Понятно", null);
        builder.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_WEBVIEW && resultCode == RESULT_OK) {
            setResult(RESULT_OK);
            finish();
        }
    }

    private void showManualTokenDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.auth_manual_token_title);
        builder.setMessage("1. Откройте на компьютере или смартфоне ссылку:\n" + DIRECT_AUTH_URL +
                "\n\n2. Нажмите «Разрешить» для аккаунта Яндекс.\n\n3. Скопируйте адрес страницы (или токен y0_...) и вставьте сюда:");

        final EditText input = new EditText(this);
        input.setHint("y0_AgAAAA... или скопированная ссылка");
        input.setSingleLine(false);
        input.setLines(3);
        builder.setView(input);

        builder.setPositiveButton(R.string.btn_save, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String text = input.getText().toString().trim();
                String token = extractToken(text);
                if (token != null && !token.isEmpty()) {
                    onAuthSuccess(token, "Ручной ввод");
                } else {
                    Toast.makeText(AuthActivity.this, "Не удалось распознать токен. Токен должен начинаться с y0_", Toast.LENGTH_LONG).show();
                }
            }
        });
        builder.setNegativeButton(R.string.btn_cancel, null);
        builder.show();
    }

    private String extractToken(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            text = java.net.URLDecoder.decode(text, "UTF-8");
        } catch (Exception ignored) {}
        Matcher m = TOKEN_REGEX.matcher(text);
        if (m.find()) {
            return m.group();
        }
        return (text.trim().startsWith("y0_") && text.trim().length() > 15) ? text.trim() : null;
    }

    private Bitmap generateQrBitmap(String text, int width, int height) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            BitMatrix bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height, hints);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCloudPolling();
        if (localAuthServer != null) {
            localAuthServer.stop();
            localAuthServer = null;
        }
    }
}
