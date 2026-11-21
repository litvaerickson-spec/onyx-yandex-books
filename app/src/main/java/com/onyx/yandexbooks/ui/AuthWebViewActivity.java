package com.onyx.yandexbooks.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.onyx.yandexbooks.R;
import com.onyx.yandexbooks.core.auth.TokenStorage;
import com.onyx.yandexbooks.core.eink.EinkWebView;
import com.onyx.yandexbooks.core.eink.EpdController;
import com.onyx.yandexbooks.core.network.HttpClientFactory;
import com.onyx.yandexbooks.core.storage.AppSettings;
import com.onyx.yandexbooks.core.ui.UiScaleHelper;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.X509TrustManager;

/**
 * Встроенный браузер для прямой авторизации в Яндекс ID на экране устройства.
 * Поддерживает ввод телефона/логина с вызовом экранной клавиатуры и нативным диалогом ввода.
 * Проверяет SSL-сертификаты доверенных доменов и перехватывает OAuth-токен при редиректе.
 */
public class AuthWebViewActivity extends Activity {

    private static final String TAG = "AuthWebView";
    private static final String BOOKMATE_CLIENT_ID = "4483e97bab6e486a9822973109a14d05";
    private static final String AUTH_URL = "https://oauth.yandex.ru/authorize?response_type=token&client_id=" + BOOKMATE_CLIENT_ID + "&lang=ru&force_confirm=yes";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("y0_[A-Za-z0-9_-]{15,}");
    private static final String[] TRUSTED_AUTH_DOMAINS = new String[]{
            "yandex.ru", "yandex.net", "ya.ru", "bookmate.com", "bookmate.ru"
    };
    private static final int MAX_STYLE_RETRIES = 4;

    private EinkWebView webView;
    private TextView webviewHint;
    private Button btnClose;
    private Button btnReload;
    private Button btnOpenKeyboard;
    private Button btnNativeInput;
    private TokenStorage tokenStorage;
    private AlertDialog activeDialog;
    private boolean isFinished = false;
    private boolean isPageLoaded = false;
    private int styleRetryCount = 0;

    @Override
    protected void attachBaseContext(Context newBase) {
        AppSettings settings = new AppSettings(newBase);
        super.attachBaseContext(UiScaleHelper.wrapBaseContext(newBase, settings.getUiScale()));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        AppSettings settings = new AppSettings(this);
        UiScaleHelper.applyScale(this, settings.getUiScale());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppSettings settings = new AppSettings(this);
        UiScaleHelper.applyScale(this, settings.getUiScale());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth_webview);

        tokenStorage = new TokenStorage(this);

        webView = (EinkWebView) findViewById(R.id.auth_webview);
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
                EpdController.requestFullRefresh(AuthWebViewActivity.this, webView);
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
        clearWebViewSessionCookies();
        webView.loadUrl(AUTH_URL);
    }

    @SuppressWarnings("deprecation")
    private void clearWebViewSessionCookies() {
        try {
            android.webkit.CookieSyncManager.createInstance(this);
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            if (cm != null) {
                cm.removeSessionCookie();
                cm.removeAllCookie();
                try {
                    java.lang.reflect.Method flush = cm.getClass().getMethod("flush");
                    flush.invoke(cm);
                } catch (Throwable ignored) {}
            }
            android.webkit.CookieSyncManager.getInstance().sync();
        } catch (Throwable ignored) {}
        try {
            android.webkit.WebStorage.getInstance().deleteAllData();
        } catch (Throwable ignored) {}
        try {
            if (webView != null) {
                webView.clearCache(true);
                webView.clearHistory();
                webView.clearFormData();
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean isPageOrVolume = keyCode == KeyEvent.KEYCODE_PAGE_DOWN
                || keyCode == KeyEvent.KEYCODE_PAGE_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP;
        if (isPageOrVolume) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && webView != null) {
                boolean down = (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN);
                webView.pageScrollEink(down);
            }
            // Поглощаем оба ACTION_DOWN и ACTION_UP для подавления VolumePanel на Darwin 1 (RK3026 / Android 4.2.2)
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void showKeyboard() {
        if (webView == null) return;
        webView.requestFocus(View.FOCUS_DOWN);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED);
        }
    }

    private void showNativeInputDialog() {
        if (isFinishing()) return;
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
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
        Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        activeDialog = dialog;
        dialog.show();

        input.requestFocus();
    }

    private void executeJavascript(WebView view, String js) {
        if (view == null || js == null) return;
        try {
            view.loadUrl("javascript:" + js);
        } catch (Throwable ignored) {}
    }

    private void injectInputValue(String value) {
        String quoted = JSONObject.quote(value != null ? value : "");
        String js = "(function() {" +
                "  var val = " + quoted + ";" +
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
                "    try {" +
                "      var evInput = document.createEvent('HTMLEvents');" +
                "      evInput.initEvent('input', true, true);" +
                "      input.dispatchEvent(evInput);" +
                "      var evChange = document.createEvent('HTMLEvents');" +
                "      evChange.initEvent('change', true, true);" +
                "      input.dispatchEvent(evChange);" +
                "    } catch(e2) {}" +
                "  }" +
                "})();";

        executeJavascript(webView, js);
        EpdController.requestFullRefresh(this, webView);
    }

    private void setupWebView() {
        // Используем LAYER_TYPE_NONE: в AndroidManifest.xml уже включен android:hardwareAccelerated="false",
        // а промежуточный LAYER_TYPE_SOFTWARE в Android 4.2.2 (API 17) обрезает буфер при mScrollY > 0
        webView.setLayerType(View.LAYER_TYPE_NONE, null);

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
                isPageLoaded = false;
                styleRetryCount = 0;
                styleHandler.removeCallbacks(styleRunnable);
                checkUrlForToken(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isPageLoaded = true;
                styleRetryCount = 0;
                checkUrlForToken(url);
                injectEinkStyles(view);
                EpdController.requestFullRefresh(AuthWebViewActivity.this, webView);
                styleHandler.removeCallbacks(styleRunnable);
                styleHandler.postDelayed(styleRunnable, 1200);
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                super.onLoadResource(view, url);
                checkUrlForToken(url);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                if (Build.VERSION.SDK_INT < 24 && isTrustedSslError(view, error)) {
                    Log.w(TAG, "Accepted Conscrypt-verified certificate for legacy WebView: " + error.getUrl());
                    handler.proceed();
                } else {
                    Log.e(TAG, "Rejected untrusted SSL certificate in WebView: " + (error != null ? error.toString() : "null"));
                    handler.cancel();
                }
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
            setResult(RESULT_OK);
            finish();
            return true;
        }
        return false;
    }

    private void injectEinkStyles(WebView view) {
        if (view == null) return;
        String css = "(function() {" +
                "var st = document.getElementById('eink_auth_override');" +
                "if (!st) { st = document.createElement('style'); st.id = 'eink_auth_override'; (document.head || document.documentElement).appendChild(st); }" +
                "st.innerHTML = '* { box-sizing: border-box !important; -webkit-transition: none !important; transition: none !important; -webkit-animation: none !important; animation: none !important; } " +
                "html, body { margin: 0 !important; padding: 2px !important; width: 100% !important; overflow-x: hidden !important; background: #FFFFFF !important; color: #000000 !important; } " +
                "header, .Header, .passp-auth-header, [class*=\"header\"], [class*=\"Header\"] { padding: 1px 0 !important; margin: 0 !important; } " +
                "h1, h2, h3, [class*=\"title\"], [class*=\"Title\"], .passp-title { font-size: 13px !important; line-height: 1.15 !important; margin: 2px 0 !important; color: #000000 !important; } " +
                "p, [class*=\"subtitle\"], [class*=\"description\"], .passp-auth-content__description { font-size: 10px !important; margin: 1px 0 !important; line-height: 1.15 !important; color: #000000 !important; } " +
                "ol, ul { margin: 2px 0 !important; padding-left: 18px !important; } " +
                "li { font-size: 10px !important; margin: 1px 0 !important; line-height: 1.15 !important; color: #000000 !important; } " +
                "footer, .passp-footer, [class*=\"footer\"], [class*=\"Footer\"], .passp-auth-footer { display: none !important; } " +
                ".passp-auth-content { padding: 1px !important; margin: 0 auto !important; max-width: 100% !important; background: #FFFFFF !important; } " +
                "[data-testid*=\"qr\"], [class*=\"qr\"], [class*=\"Qr\"], .MagicField-qr, canvas, svg, .passp-auth-content img { max-width: 52vw !important; max-height: 38vh !important; margin: 4px auto !important; display: block !important; }';" +
                "var qr = document.querySelector('[data-testid*=\"qr\"]') || document.querySelector('canvas') || document.querySelector('.MagicField') || document.querySelector('img[src*=\"data:image\"]');" +
                "if (qr && !qr.getAttribute('data-eink-centered')) { qr.setAttribute('data-eink-centered', '1'); qr.scrollIntoView(true); }" +
                "})();";

        executeJavascript(view, css);
    }

    private final Handler styleHandler = new Handler();
    private final Runnable styleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && webView != null && isPageLoaded && styleRetryCount < MAX_STYLE_RETRIES) {
                injectEinkStyles(webView);
                styleRetryCount++;
                if (styleRetryCount < MAX_STYLE_RETRIES) {
                    styleHandler.postDelayed(this, 1500);
                }
            }
        }
    };

    private boolean isTrustedSslError(WebView view, SslError error) {
        if (error == null) return false;
        String url = error.getUrl();
        if ((url == null || url.trim().isEmpty()) && view != null) {
            url = view.getUrl();
        }
        String host = extractHost(url);
        if (!isAllowedAuthHost(host)) {
            return false;
        }
        SslCertificate sslCert = error.getCertificate();
        if (sslCert == null) {
            return false;
        }
        try {
            Bundle state = SslCertificate.saveState(sslCert);
            byte[] certBytes = state != null ? state.getByteArray("x509-certificate") : null;
            if (certBytes == null || certBytes.length == 0) {
                return false;
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate x509 = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
            x509.checkValidity();
            if (!verifyHostMatchesCertificate(host, x509)) {
                return false;
            }
            X509TrustManager trustManager = HttpClientFactory.getSharedTrustManager();
            String alg = x509.getPublicKey() != null ? x509.getPublicKey().getAlgorithm() : "RSA";
            String authType = "EC".equalsIgnoreCase(alg) ? "ECDHE_ECDSA" : "RSA";
            trustManager.checkServerTrusted(new X509Certificate[]{x509}, authType);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "WebView SSL validation failed for host " + host + ": " + t.getMessage());
            return false;
        }
    }

    private String extractHost(String url) {
        if (url == null) return null;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            return host != null ? host.toLowerCase(Locale.US) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isAllowedAuthHost(String host) {
        if (host == null || host.isEmpty()) return false;
        for (String domain : TRUSTED_AUTH_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    private boolean verifyHostMatchesCertificate(String host, X509Certificate cert) {
        try {
            Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
            if (altNames != null) {
                for (List<?> entry : altNames) {
                    if (entry == null || entry.size() < 2) continue;
                    Object typeObj = entry.get(0);
                    Object valueObj = entry.get(1);
                    if (typeObj instanceof Integer && ((Integer) typeObj) == 2 && valueObj instanceof String) {
                        String pattern = ((String) valueObj).toLowerCase(Locale.US);
                        if (matchesHostnamePattern(host, pattern)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean matchesHostnamePattern(String host, String pattern) {
        if (host == null || pattern == null || host.isEmpty() || pattern.isEmpty()) return false;
        if (!pattern.contains("*")) {
            return host.equals(pattern);
        }
        if (pattern.startsWith("*.") && pattern.indexOf('*', 1) < 0) {
            String suffix = pattern.substring(1); // includes leading dot
            if (!host.endsWith(suffix)) return false;
            int prefixLen = host.length() - suffix.length();
            return prefixLen > 0 && host.substring(0, prefixLen).indexOf('.') < 0;
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isPageLoaded && styleRetryCount < MAX_STYLE_RETRIES) {
            styleHandler.removeCallbacks(styleRunnable);
            styleHandler.post(styleRunnable);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        styleHandler.removeCallbacks(styleRunnable);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (webView != null) {
                webView.pageScrollEink(true);
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (webView != null) {
                webView.pageScrollEink(false);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
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
        if (activeDialog != null && activeDialog.isShowing()) {
            try {
                activeDialog.dismiss();
            } catch (Exception ignored) {}
            activeDialog = null;
        }
        if (webView != null) {
            try {
                webView.stopLoading();
                if (webView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) webView.getParent()).removeView(webView);
                }
                webView.removeAllViews();
                webView.destroy();
            } catch (Throwable ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}
