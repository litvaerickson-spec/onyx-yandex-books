package com.onyx.yandexbooks.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.onyx.yandexbooks.R;
import com.onyx.yandexbooks.core.auth.TokenStorage;
import com.onyx.yandexbooks.core.eink.EpdController;

import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Встроенный браузер для прямой авторизации в Яндекс ID на экране ридера.
 * Поддерживает ввод телефона/логина с вызовом экранной клавиатуры и нативным диалогом ввода.
 * Оснащен обходом устаревших SSL-сертификатов Android KitKat и перехватом OAuth-токенов.
 */
public class AuthWebViewActivity extends Activity {

    private static final String TAG = "AuthWebView";
    private static final String BOOKMATE_CLIENT_ID = "4483e97bab6e486a9822973109a14d05";
    private static final String AUTH_URL = "https://oauth.yandex.ru/authorize?response_type=token&client_id=" + BOOKMATE_CLIENT_ID + "&lang=ru";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("y0_[A-Za-z0-9_-]{15,}");

    private WebView webView;
    private TextView webviewHint;
    private Button btnClose;
    private Button btnReload;
    private Button btnOpenKeyboard;
    private Button btnNativeInput;
    private TokenStorage tokenStorage;
    private boolean isFinished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth_webview);

        tokenStorage = new TokenStorage(this);

        webView = (WebView) findViewById(R.id.auth_webview);
        webviewHint = (TextView) findViewById(R.id.auth_webview_tip);
        btnClose = (Button) findViewById(R.id.btn_close_webview);
        btnReload = (Button) findViewById(R.id.btn_reload_webview);
        btnOpenKeyboard = (Button) findViewById(R.id.btn_open_keyboard);
        btnNativeInput = (Button) findViewById(R.id.btn_native_input);

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        btnReload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                webView.reload();
            }
        });

        btnOpenKeyboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showKeyboard();
            }
        });

        btnNativeInput.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNativeInputDialog();
            }
        });

        setupWebView();
        webView.loadUrl(AUTH_URL);
    }

    private void showKeyboard() {
        webView.requestFocus(View.FOCUS_DOWN);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED);
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
        Toast.makeText(this, "Клавиатура вызвана", Toast.LENGTH_SHORT).show();
    }

    private void showNativeInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Ввод логина или пароля");
        builder.setMessage("Введите текст для активного поля (логин или пароль). Он будет сразу вставлен в форму Яндекса:");

        final EditText input = new EditText(this);
        input.setHint("логин, телефон или пароль");
        input.setSingleLine(true);
        builder.setView(input);

        builder.setPositiveButton("Вставить в форму", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String text = input.getText().toString().trim();
                if (!text.isEmpty()) {
                    injectInputValue(text);
                }
            }
        });
        builder.setNegativeButton("Отмена", null);
        AlertDialog dialog = builder.create();
        dialog.show();

        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
    }

    private void injectInputValue(String value) {
        String escaped = value.replace("\\", "\\\\").replace("'", "\\'");
        String js = "(function() {" +
                "  var val = '" + escaped + "';" +
                "  var input = document.activeElement;" +
                "  if (!input || input.tagName !== 'INPUT') {" +
                "    input = document.querySelector('#login') || " +
                "            document.querySelector('#passwd') || " +
                "            document.querySelector('[data-testid=\"text-field-input\"]') || " +
                "            document.querySelector('input[name=\"login\"]') || " +
                "            document.querySelector('input[name=\"passwd\"]') || " +
                "            document.querySelector('input[type=\"tel\"]') || " +
                "            document.querySelector('input[type=\"text\"]') || " +
                "            document.querySelector('input[type=\"password\"]');" +
                "  }" +
                "  if (!input) {" +
                "    var all = document.getElementsByTagName('input');" +
                "    for (var i = 0; i < all.length; i++) {" +
                "      var t = (all[i].type || '').toLowerCase();" +
                "      if (t !== 'hidden' && t !== 'checkbox' && t !== 'radio') { input = all[i]; break; }" +
                "    }" +
                "  }" +
                "  if (input) {" +
                "    input.focus();" +
                "    try {" +
                "      var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;" +
                "      if (setter) { setter.call(input, val); } else { input.value = val; }" +
                "    } catch(e) {" +
                "      input.value = val;" +
                "    }" +
                "    input.dispatchEvent(new Event('input', {bubbles: true}));" +
                "    input.dispatchEvent(new Event('change', {bubbles: true}));" +
                "  }" +
                "})();";

        if (android.os.Build.VERSION.SDK_INT >= 19) {
            webView.evaluateJavascript(js, null);
        } else {
            webView.loadUrl("javascript:" + js);
        }
        Toast.makeText(this, "Значение вставлено в форму Яндекса", Toast.LENGTH_SHORT).show();
    }

    private void setupWebView() {
        // Программный рендеринг для устранения артефактов E-Ink
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        // Обеспечиваем гарантированный фокус для экранной клавиатуры Onyx
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus(View.FOCUS_DOWN);
        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_UP:
                        if (!v.hasFocus()) {
                            v.requestFocus();
                        }
                        break;
                }
                return false;
            }
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setTextZoom(70);
        // Аутентичный User-Agent Android KitKat: принудительно активирует ультралегкий режим Яндекса (Granny / Domik)
        // со статическим PNG QR-кодом для приложения Яндекс Ключ и нативными полями ввода
        settings.setUserAgentString("Mozilla/5.0 (Linux; U; Android 4.4.4; ru-ru; Onyx Darwin Build/KTU84P) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (checkUrlForToken(url)) {
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                checkUrlForToken(url);
                injectEinkStyles(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                checkUrlForToken(url);
                injectEinkStyles(view);
                EpdController.requestFullRefresh(AuthWebViewActivity.this, webView);
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                super.onLoadResource(view, url);
                checkUrlForToken(url);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Игнорируем проверку устаревших корневых сертификатов KitKat для яндексовских доменов
                Log.w(TAG, "SSL Certificate Notice in WebView: " + error.toString());
                handler.proceed();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                Log.e(TAG, "WebView error " + errorCode + ": " + description + " for " + failingUrl);
            }
        });
    }

    private boolean checkUrlForToken(String rawUrl) {
        if (isFinished || rawUrl == null) return false;

        String decodedUrl = rawUrl;
        try {
            decodedUrl = URLDecoder.decode(rawUrl, "UTF-8");
        } catch (Exception ignored) {}

        Matcher matcher = TOKEN_PATTERN.matcher(decodedUrl);
        if (matcher.find()) {
            isFinished = true;
            String token = matcher.group();
            Log.i(TAG, "Found OAuth access token in URL redirect!");
            tokenStorage.saveTokens(token, "", 31536000L);
            Toast.makeText(this, "Авторизация успешно выполнена!", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
            return true;
        }
        return false;
    }

    private void injectEinkStyles(WebView view) {
        if (view == null) return;
        String css = "var st = document.getElementById('eink_auth_override');" +
                "if (!st) { st = document.createElement('style'); st.id = 'eink_auth_override'; (document.head || document.documentElement).appendChild(st); }" +
                "st.innerHTML = '* { box-sizing: border-box !important; } " +
                "html, body { margin: 0 !important; padding: 2px !important; width: 100% !important; overflow-x: hidden !important; } " +
                "header, .Header, .passp-auth-header, [class*=\"header\"], [class*=\"Header\"] { padding: 1px 0 !important; margin: 0 !important; } " +
                "h1, h2, h3, [class*=\"title\"], [class*=\"Title\"], .passp-title { font-size: 13px !important; line-height: 1.15 !important; margin: 2px 0 !important; } " +
                "p, [class*=\"subtitle\"], [class*=\"description\"], .passp-auth-content__description { font-size: 10px !important; margin: 1px 0 !important; line-height: 1.15 !important; } " +
                "ol, ul { margin: 2px 0 !important; padding-left: 18px !important; } " +
                "li { font-size: 10px !important; margin: 1px 0 !important; line-height: 1.15 !important; } " +
                "footer, .passp-footer, [class*=\"footer\"], [class*=\"Footer\"], .passp-auth-footer { display: none !important; } " +
                ".passp-auth-content { padding: 1px !important; margin: 0 auto !important; max-width: 100% !important; } " +
                "[data-testid*=\"qr\"], [class*=\"qr\"], [class*=\"Qr\"], .MagicField-qr, canvas, svg, .passp-auth-content img { max-width: 52vw !important; max-height: 38vh !important; margin: 4px auto !important; display: block !important; }';" +
                "var qr = document.querySelector('[data-testid*=\"qr\"]') || document.querySelector('canvas') || document.querySelector('.MagicField') || document.querySelector('img[src*=\"data:image\"]');" +
                "if (qr) { qr.scrollIntoView({block: 'center', inline: 'center'}); }";

        if (android.os.Build.VERSION.SDK_INT >= 19) {
            view.evaluateJavascript(css, null);
        } else {
            view.loadUrl("javascript:" + css);
        }
    }

    private final Handler styleHandler = new Handler();
    private final Runnable styleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && webView != null) {
                injectEinkStyles(webView);
                styleHandler.postDelayed(this, 1500);
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        styleHandler.post(styleRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        styleHandler.removeCallbacks(styleRunnable);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        styleHandler.removeCallbacks(styleRunnable);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
