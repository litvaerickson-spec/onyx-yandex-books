package com.onyx.yandexbooks.core.update;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Environment;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.onyx.yandexbooks.R;
import com.onyx.yandexbooks.core.eink.EinkScrollView;
import com.onyx.yandexbooks.core.eink.EpdController;
import com.onyx.yandexbooks.core.network.HttpClientFactory;
import com.onyx.yandexbooks.core.ui.EinkNotice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Менеджер бесшовного обновления приложения (OTA Self-Update) с GitHub Releases.
 * Позволяет проверять наличие новых версий, скачивать релизный APK и запускать
 * установку без потери сессии, сохраненных книг и прогресса чтения.
 */
public class AppUpdateManager {

    private static final String TAG = "AppUpdateManager";
    private static final String GITHUB_RELEASES_LIST_URL =
            "https://api.github.com/repos/litvaerickson-spec/onyx-yandex-books/releases?per_page=50";
    private static final String GITHUB_RAW_VERSION_URL =
            "https://raw.githubusercontent.com/litvaerickson-spec/onyx-yandex-books/main/version.json";
    private static final String GITHUB_LATEST_RELEASE_URL =
            "https://api.github.com/repos/litvaerickson-spec/onyx-yandex-books/releases/latest";

    private static AppUpdateManager instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static class ReleaseInfo {
        public String tagName;       // e.g. "v1.3.0"
        public String versionName;   // e.g. "1.3.0"
        public String title;         // e.g. "v1.3.0 - ..."
        public String body;          // Release notes / changelog
        public String apkDownloadUrl;// Direct download URL
        public String apkFileName;   // e.g. "yandex-books-lite-v1.3.0.apk"
        public long apkSize;         // bytes
    }

    public interface UpdateCheckCallback {
        void onResult(boolean updateAvailable, ReleaseInfo release, String message);
    }

    private AppUpdateManager() {}

    public static synchronized AppUpdateManager getInstance() {
        if (instance == null) {
            instance = new AppUpdateManager();
        }
        return instance;
    }

    public String getCurrentVersionName(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName;
        } catch (Exception e) {
            return "1.2.9";
        }
    }

    /**
     * Сравнение семантических версий (SemVer) с поддержкой pre-release суффиксов (-beta, -rc).
     * Возвращает true, если версия latestTag новее, чем currentVersion.
     * Правило SemVer: 1.6.1 > 1.6.1-beta > 1.6.0.
     */
    public static boolean isVersionNewer(String latestTag, String currentVersion) {
        if (latestTag == null || currentVersion == null) return false;
        String l = latestTag.trim().replaceAll("^[vV]", "");
        String c = currentVersion.trim().replaceAll("^[vV]", "");
        if (l.equalsIgnoreCase(c)) return false;

        int lDash = l.indexOf('-');
        int cDash = c.indexOf('-');
        String lBase = lDash >= 0 ? l.substring(0, lDash) : l;
        String cBase = cDash >= 0 ? c.substring(0, cDash) : c;
        String lPre = lDash >= 0 ? l.substring(lDash + 1) : "";
        String cPre = cDash >= 0 ? c.substring(cDash + 1) : "";

        String[] lParts = lBase.split("\\.");
        String[] cParts = cBase.split("\\.");
        int max = Math.max(lParts.length, cParts.length);
        for (int i = 0; i < max; i++) {
            int lVal = parseIntSafe(i < lParts.length ? lParts[i] : "0");
            int cVal = parseIntSafe(i < cParts.length ? cParts[i] : "0");
            if (lVal > cVal) return true;
            if (lVal < cVal) return false;
        }

        // Базовые версии равны: стабильный релиз (без суффикса) всегда новее pre-release (beta)
        boolean lHasPre = !lPre.isEmpty();
        boolean cHasPre = !cPre.isEmpty();
        if (!lHasPre && cHasPre) return true;
        if (lHasPre && !cHasPre) return false;
        if (lHasPre && cHasPre) {
            int lPreNum = parseIntSafe(lPre);
            int cPreNum = parseIntSafe(cPre);
            if (lPreNum != cPreNum) return lPreNum > cPreNum;
            return lPre.compareToIgnoreCase(cPre) > 0;
        }
        return false;
    }

    private static int parseIntSafe(String part) {
        if (part == null) return 0;
        String digits = part.replaceAll("\\D+", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (Exception ignored) {
            return 0;
        }
    }

    /**
     * Парсит объект релиза из JSON (поддерживает формат GitHub API Releases, а также статический version.json).
     * Возвращает null, если релиз является черновиком или не содержит валидного APK файла.
     */
    public static ReleaseInfo parseReleaseFromJson(JSONObject json) {
        if (json == null) return null;
        if (json.optBoolean("draft", false)) return null;

        ReleaseInfo release = new ReleaseInfo();
        release.tagName = json.optString("tag_name", "");
        if (TextUtils.isEmpty(release.tagName)) {
            release.tagName = json.optString("tagName", "");
        }
        if (TextUtils.isEmpty(release.tagName)) return null;

        release.versionName = release.tagName.replaceAll("^[vV]", "");
        release.title = json.optString("name", release.tagName);
        if (TextUtils.isEmpty(release.title)) {
            release.title = json.optString("title", release.tagName);
        }
        release.body = json.optString("body", "");
        if (TextUtils.isEmpty(release.body)) {
            release.body = json.optString("changelog", "");
        }

        // 1. Проверяем прямые поля (например, из version.json)
        String directApkUrl = json.optString("apkUrl", "");
        if (!TextUtils.isEmpty(directApkUrl)) {
            release.apkDownloadUrl = directApkUrl;
            release.apkFileName = json.optString("apkFileName", "yandex-books-lite-" + release.tagName + ".apk");
            release.apkSize = json.optLong("apkSize", 0);
            return release;
        }

        // 2. Проверяем массив assets (GitHub Releases API)
        JSONArray assets = json.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String assetName = asset.optString("name", "");
                if (assetName.endsWith(".apk")) {
                    release.apkDownloadUrl = asset.optString("browser_download_url", "");
                    release.apkFileName = assetName;
                    release.apkSize = asset.optLong("size", 0);
                    break;
                }
            }
        }

        if (TextUtils.isEmpty(release.apkDownloadUrl)) {
            return null;
        }
        return release;
    }

    /**
     * Мульти-стратегический запрос последней версии с GitHub.
     * 1) Сначала запрашивает список релизов (находит новейшие версии, включая предрелизы и бета).
     * 2) В случае сбоя или лимитов обращается к CDN version.json на raw.githubusercontent.com.
     * 3) В крайнем случае делает fallback на /releases/latest.
     */
    public void fetchLatestRelease(final Context context, final UpdateCheckCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                ReleaseInfo foundRelease = null;
                String lastError = null;
                OkHttpClient client = HttpClientFactory.getClient();
                String currentVer = getCurrentVersionName(context);

                // Стратегия 1: GitHub Releases List (находит свежайшие версии, включая пре-релизы и бета)
                try {
                    Request request = new Request.Builder()
                            .url(GITHUB_RELEASES_LIST_URL)
                            .header("User-Agent", "OnyxYandexBooks-App/" + currentVer)
                            .header("Accept", "application/vnd.github.v3+json")
                            .header("Cache-Control", "no-cache")
                            .build();

                    Response response = client.newCall(request).execute();
                    if (response.isSuccessful() && response.body() != null) {
                        String jsonStr = response.body().string();
                        JSONArray array = new JSONArray(jsonStr);
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject relObj = array.optJSONObject(i);
                            ReleaseInfo rel = parseReleaseFromJson(relObj);
                            if (rel != null) {
                                if (foundRelease == null || isVersionNewer(rel.tagName, foundRelease.tagName)) {
                                    foundRelease = rel;
                                }
                            }
                        }
                    } else {
                        lastError = "GitHub API: HTTP " + (response != null ? response.code() : "null");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Releases list fetch failed, falling back to version.json / latest", e);
                    lastError = e.getMessage();
                }

                // Стратегия 2: Fallback на raw.githubusercontent.com version.json (без лимитов GitHub API)
                if (foundRelease == null) {
                    try {
                        Request request = new Request.Builder()
                                .url(GITHUB_RAW_VERSION_URL)
                                .header("User-Agent", "OnyxYandexBooks-App/" + currentVer)
                                .header("Cache-Control", "no-cache")
                                .build();
                        Response response = client.newCall(request).execute();
                        if (response.isSuccessful() && response.body() != null) {
                            String jsonStr = response.body().string();
                            JSONObject json = new JSONObject(jsonStr);
                            foundRelease = parseReleaseFromJson(json);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Raw version.json fallback failed", e);
                    }
                }

                // Стратегия 3: Fallback на /releases/latest
                if (foundRelease == null) {
                    try {
                        Request request = new Request.Builder()
                                .url(GITHUB_LATEST_RELEASE_URL)
                                .header("User-Agent", "OnyxYandexBooks-App/" + currentVer)
                                .header("Accept", "application/vnd.github.v3+json")
                                .header("Cache-Control", "no-cache")
                                .build();
                        Response response = client.newCall(request).execute();
                        if (response.isSuccessful() && response.body() != null) {
                            String jsonStr = response.body().string();
                            JSONObject json = new JSONObject(jsonStr);
                            foundRelease = parseReleaseFromJson(json);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Releases latest fallback failed", e);
                    }
                }

                if (foundRelease == null) {
                    postResult(callback, false, null, "Не удалось проверить обновления: " + (lastError != null ? lastError : "нет связи"));
                    return;
                }

                boolean isNewer = isVersionNewer(foundRelease.tagName, currentVer);
                postResult(callback, isNewer, foundRelease, isNewer ? "Доступно обновление" : "У вас последняя версия");
            }
        });
    }

    private void postResult(final UpdateCheckCallback callback, final boolean updateAvailable,
                            final ReleaseInfo release, final String message) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onResult(updateAvailable, release, message);
            }
        });
    }

    /**
     * Очищает Markdown-разметку, спецсимволы и эмодзи из описания релиза для четкого E-Ink отображения.
     */
    public static String cleanReleaseNotes(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "Улучшение стабильности, интерфейса и синхронизации.";
        }
        // Убираем заголовки markdown (#, ##, ###)
        String text = raw.replaceAll("(?m)^#+\\s*", "");
        // Убираем жирный/курсивный шрифт: **текст**, *текст*, `код`
        text = text.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
        text = text.replaceAll("\\*(.*?)\\*", "$1");
        text = text.replaceAll("`([^`]+)`", "$1");
        // Убираем markdown-ссылки [текст](url) -> текст
        text = text.replaceAll("\\[([^\\]]+)\\]\\([^\\)]+\\)", "$1");
        // Удаляем эмодзи и спецсимволы, ломающие рендеринг на E-Ink
        text = text.replaceAll("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF]", "");
        // Заменяем маркеры списков (- и *) на аккуратный круглый маркер •
        text = text.replaceAll("(?m)^\\s*[-*]\\s+", "• ");
        // Убираем избыточные пустые строки
        text = text.replaceAll("\n{3,}", "\n\n");
        return text.trim();
    }

    /**
     * Показывает диалог с информацией о релизе и кнопкой «Обновить».
     */
    public void showUpdateDialog(final Activity activity, final ReleaseInfo release) {
        if (activity == null || activity.isFinishing()) return;

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        float density = dm.density;

        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_notice_bg);
        int padH = (int) (16 * density);
        int padV = (int) (14 * density);
        root.setPadding(padH, padV, padH, padV);

        // Заголовок
        TextView titleView = new TextView(activity);
        titleView.setText("Вышла новая версия: " + release.tagName);
        titleView.setTextSize(15);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(titleView);

        // Инфо: размер и версия
        String currentVer = getCurrentVersionName(activity);
        String sizeMb = String.format(java.util.Locale.US, "%.1f МБ", release.apkSize / (1024.0 * 1024.0));
        TextView infoView = new TextView(activity);
        infoView.setText("Текущая: v" + currentVer + "  •  Новая: " + release.tagName + " (" + sizeMb + ")");
        infoView.setTextSize(12);
        infoView.setTypeface(null, Typeface.BOLD);
        infoView.setTextColor(Color.BLACK);
        infoView.setSingleLine(true);
        infoView.setPadding(0, (int) (3 * density), 0, (int) (5 * density));
        root.addView(infoView);

        // Разделитель
        View divider = new View(activity);
        divider.setBackgroundColor(Color.BLACK);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(2, 2 * density)));

        // Описание изменений (Scrollable)
        final EinkScrollView scrollView = new EinkScrollView(activity);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollLp.setMargins(0, (int) (6 * density), 0, (int) (6 * density));
        scrollView.setLayoutParams(scrollLp);

        TextView notesView = new TextView(activity);
        notesView.setText(cleanReleaseNotes(release.body));
        notesView.setTextSize(13);
        notesView.setTextColor(Color.BLACK);
        notesView.setLineSpacing(3f, 1.25f);
        scrollView.addView(notesView);
        root.addView(scrollView);

        // Разделитель
        View divider2 = new View(activity);
        divider2.setBackgroundColor(Color.BLACK);
        root.addView(divider2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(2, 2 * density)));

        // Кнопки действий
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * density)));
        btnRow.setPadding(0, (int) (6 * density), 0, 0);

        Button btnInstall = new Button(activity);
        btnInstall.setText("Обновить");
        btnInstall.setTextSize(12.0f);
        btnInstall.setTypeface(null, Typeface.BOLD);
        btnInstall.setTextColor(Color.BLACK);
        btnInstall.setBackgroundResource(R.drawable.btn_eink_primary);
        btnInstall.setPadding(0, 0, 0, 0);
        btnInstall.setSingleLine(true);
        LinearLayout.LayoutParams lpInst = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpInst.setMargins(0, 0, (int) (4 * density), 0);
        btnInstall.setLayoutParams(lpInst);
        btnInstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                downloadAndInstallApk(activity, release);
            }
        });
        btnRow.addView(btnInstall);

        Button btnCancel = new Button(activity);
        btnCancel.setText("Ознакомился");
        btnCancel.setTextSize(12.0f);
        btnCancel.setTypeface(null, Typeface.BOLD);
        btnCancel.setTextColor(Color.BLACK);
        btnCancel.setBackgroundResource(R.drawable.btn_eink);
        btnCancel.setPadding(0, 0, 0, 0);
        btnCancel.setSingleLine(true);
        LinearLayout.LayoutParams lpCancel = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpCancel.setMargins((int) (4 * density), 0, 0, 0);
        btnCancel.setLayoutParams(lpCancel);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        btnRow.addView(btnCancel);

        root.addView(btnRow);

        Button btnArchive = new Button(activity);
        btnArchive.setText("Архив версий и откат ПО");
        btnArchive.setTextSize(11.5f);
        btnArchive.setTypeface(null, Typeface.BOLD);
        btnArchive.setTextColor(Color.BLACK);
        btnArchive.setBackgroundResource(R.drawable.btn_eink);
        btnArchive.setPadding(0, 0, 0, 0);
        btnArchive.setSingleLine(true);
        LinearLayout.LayoutParams lpArch = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (34 * density));
        lpArch.setMargins(0, (int) (6 * density), 0, 0);
        btnArchive.setLayoutParams(lpArch);
        btnArchive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showReleaseArchiveDialog(activity);
            }
        });
        root.addView(btnArchive);

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final int targetW = (int) (dm.widthPixels * 0.90);
        final int targetH = (int) (dm.heightPixels * 0.82);

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                        || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        boolean down = (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_DOWN);
                        scrollView.pageScrollEink(down);
                    }
                    return true;
                }
                return false;
            }
        });

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setLayout(targetW, targetH);
                }
                EpdController.requestFullRefresh(activity, root);
            }
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                EpdController.requestFullRefresh(activity, null);
            }
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(targetW, targetH);
        }
    }

    /**
     * Скачивание APK с отображением прогресса и последующий вызов системного установщика.
     */
    public void downloadAndInstallApk(final Activity activity, final ReleaseInfo release) {
        if (activity == null || activity.isFinishing()) return;

        final ProgressDialog progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("Яндекс Книги");
        progressDialog.setMessage("Скачивание обновления " + release.tagName + "...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setProgress(0);
        progressDialog.setCancelable(false);
        progressDialog.show();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                File targetFile = null;
                try {
                    // Выбираем доступную для записи директорию Загрузок
                    File downloadDir = resolveWritableDownloadDir(activity, true);

                    String fname = release.apkFileName != null ? release.apkFileName : "yandex-books-update.apk";
                    targetFile = new File(downloadDir, fname);

                    OkHttpClient client = HttpClientFactory.getClient();
                    Request request = new Request.Builder()
                            .url(release.apkDownloadUrl)
                            .header("User-Agent", "OnyxYandexBooks-App/" + getCurrentVersionName(activity))
                            .build();

                    Response response = client.newCall(request).execute();
                    if (!response.isSuccessful()) {
                        throw new Exception("HTTP " + response.code() + " " + response.message());
                    }

                    long contentLength = response.body().contentLength();
                    if (contentLength <= 0 && release.apkSize > 0) {
                        contentLength = release.apkSize;
                    }

                    InputStream in = response.body().byteStream();
                    FileOutputStream out = new FileOutputStream(targetFile);
                    byte[] buffer = new byte[8192];
                    long totalRead = 0;
                    int read;

                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        totalRead += read;
                        if (contentLength > 0) {
                            final int pct = (int) ((totalRead * 100) / contentLength);
                            final long curBytes = totalRead;
                            final long totBytes = contentLength;
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (progressDialog.isShowing()) {
                                        progressDialog.setProgress(pct);
                                        progressDialog.setMessage(String.format("Скачивание: %d%% (%.1f / %.1f МБ)",
                                                pct, curBytes / (1024.0 * 1024.0), totBytes / (1024.0 * 1024.0)));
                                    }
                                }
                            });
                        }
                    }

                    out.flush();
                    out.close();
                    in.close();

                    // Даем права на чтение системному PackageInstaller
                    targetFile.setReadable(true, false);

                    final File finalFile = targetFile;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (progressDialog.isShowing()) progressDialog.dismiss();
                            installApk(activity, finalFile);
                        }
                    });

                } catch (final Exception e) {
                    Log.e(TAG, "Failed to download update APK", e);
                    if (targetFile != null && targetFile.exists()) {
                        targetFile.delete();
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (progressDialog.isShowing()) progressDialog.dismiss();
                            EinkNotice.show(activity, "Сбой обновления", "Сбой загрузки обновления:\n" + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private static File resolveWritableDownloadDir(Context context, boolean allowPrivateFallback) {
        File[] candidates = allowPrivateFallback
                ? new File[]{
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        new File("/sdcard/Download"),
                        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                        context.getCacheDir()
                }
                : new File[]{
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        new File("/sdcard/Download"),
                        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                };
        for (File dir : candidates) {
            if (dir == null) continue;
            if ((dir.exists() || dir.mkdirs()) && dir.canWrite()) {
                return dir;
            }
        }
        return allowPrivateFallback ? context.getCacheDir() : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    /**
     * Запуск нативного PackageInstaller Android для обновления поверх существующей версии.
     * При обновлении без удаления приложения все данные (база SQLite, SharedPreferences, книги)
     * полностью сохраняются.
     */
    public static void installApk(Context context, File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            EinkNotice.show(context, "Установка обновления", "Файл APK обновления не найден.");
            return;
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                try {
                    java.lang.reflect.Method m = android.os.StrictMode.class.getMethod("disableDeathOnFileUriExposure");
                    m.invoke(null);
                } catch (Exception ignored) {
                    android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder().build());
                }
            }
            apkFile.setReadable(true, false);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = (android.os.Build.VERSION.SDK_INT >= 24)
                    ? LocalFileProvider.getUriForFile(apkFile)
                    : Uri.fromFile(apkFile);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                context.startActivity(intent);
            } catch (Exception contentErr) {
                // Fallback на прямой file:// URI для старых прошивок E-Ink
                Intent fallback = new Intent(Intent.ACTION_VIEW);
                fallback.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(fallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error launching package installer", e);
            EinkNotice.show(context, "Установка обновления", "Не удалось запустить установщик:\n" + e.getMessage() + "\n\nФайл сохранен:\n" + apkFile.getAbsolutePath());
        }
    }

    public interface ReleaseArchiveCallback {
        void onResult(java.util.List<ReleaseInfo> releases, String errorMessage);
    }

    /**
     * Загружает полный список всех опубликованных релизов с GitHub (включая стабильные и бета-версии)
     * для предоставления пользователю возможности отката на любую предыдущую версию.
     */
    public void fetchAllReleases(final Context context, final ReleaseArchiveCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final java.util.List<ReleaseInfo> list = new java.util.ArrayList<>();
                String err = null;
                OkHttpClient client = HttpClientFactory.getClient();
                String currentVer = getCurrentVersionName(context);

                try {
                    Request request = new Request.Builder()
                            .url(GITHUB_RELEASES_LIST_URL)
                            .header("User-Agent", "OnyxYandexBooks-App/" + currentVer)
                            .header("Accept", "application/vnd.github.v3+json")
                            .header("Cache-Control", "no-cache")
                            .build();

                    Response response = client.newCall(request).execute();
                    if (response.isSuccessful() && response.body() != null) {
                        String jsonStr = response.body().string();
                        JSONArray array = new JSONArray(jsonStr);
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject relObj = array.optJSONObject(i);
                            ReleaseInfo rel = parseReleaseFromJson(relObj);
                            if (rel != null) {
                                list.add(rel);
                            }
                        }
                    } else {
                        err = "HTTP " + (response != null ? response.code() : "null");
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }

                final String finalErr = err;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onResult(list, finalErr);
                        }
                    }
                });
            }
        });
    }

    /**
     * Показывает окно «Архив версий и откат ПО» со списком всех доступных версий на GitHub.
     * Перед откатом на более раннюю версию автоматически выполняет резервное копирование БД и токена.
     */
    public void showReleaseArchiveDialog(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        final ProgressDialog loading = new ProgressDialog(activity);
        loading.setTitle("Архив версий");
        loading.setMessage("Загрузка списка релизов с GitHub...");
        loading.setCancelable(true);
        loading.show();

        fetchAllReleases(activity, new ReleaseArchiveCallback() {
            @Override
            public void onResult(final java.util.List<ReleaseInfo> releases, String errorMessage) {
                if (loading.isShowing()) {
                    try { loading.dismiss(); } catch (Exception ignored) {}
                }
                if (releases == null || releases.isEmpty()) {
                    EinkNotice.show(activity, "Архив версий", "Не удалось загрузить список версий с GitHub:\n" + (errorMessage != null ? errorMessage : "нет подключения к сети"));
                    return;
                }
                renderReleaseArchiveDialog(activity, releases);
            }
        });
    }

    private void renderReleaseArchiveDialog(final Activity activity, final java.util.List<ReleaseInfo> releases) {
        if (activity == null || activity.isFinishing()) return;

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        final float density = dm.density;
        final String currentVer = getCurrentVersionName(activity);

        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_notice_bg);
        int padH = (int) (14 * density);
        int padV = (int) (12 * density);
        root.setPadding(padH, padV, padH, padV);

        TextView titleView = new TextView(activity);
        titleView.setText("Архив версий и откат ПО");
        titleView.setTextSize(15);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.CENTER);
        root.addView(titleView);

        TextView subView = new TextView(activity);
        subView.setText("Установлена: v" + currentVer + " • Выберите любую версию для установки или отката");
        subView.setTextSize(11);
        subView.setTextColor(Color.BLACK);
        subView.setGravity(Gravity.CENTER);
        subView.setPadding(0, (int) (3 * density), 0, (int) (6 * density));
        root.addView(subView);

        View div1 = new View(activity);
        div1.setBackgroundColor(Color.BLACK);
        root.addView(div1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(2, 2 * density)));

        final EinkScrollView scrollView = new EinkScrollView(activity);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollLp.setMargins(0, (int) (6 * density), 0, (int) (6 * density));
        scrollView.setLayoutParams(scrollLp);

        LinearLayout listLayout = new LinearLayout(activity);
        listLayout.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < releases.size(); i++) {
            final ReleaseInfo rel = releases.get(i);
            final boolean isCurrent = rel.versionName != null && rel.versionName.equalsIgnoreCase(currentVer);
            final boolean isNewer = isVersionNewer(rel.tagName, currentVer);

            String tagLabel = rel.tagName;
            if (isCurrent) {
                tagLabel += "  [ТЕКУЩАЯ]";
            } else if (isNewer) {
                tagLabel += "  [НОВАЯ]";
            } else {
                tagLabel += "  [ОТКАТ]";
            }
            if (rel.apkSize > 0) {
                tagLabel += String.format(java.util.Locale.US, " (%.1f МБ)", rel.apkSize / (1024.0 * 1024.0));
            }

            Button btnRel = new Button(activity);
            btnRel.setText(tagLabel);
            btnRel.setTextSize(12);
            btnRel.setTypeface(null, Typeface.BOLD);
            btnRel.setTextColor(Color.BLACK);
            btnRel.setBackgroundResource(isCurrent ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
            btnRel.setSingleLine(true);
            btnRel.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (36 * density));
            lp.setMargins(0, 0, 0, (int) (5 * density));
            btnRel.setLayoutParams(lp);

            btnRel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    if (isCurrent) {
                        EinkNotice.show(activity, "Версия ПО", "Эта версия (" + rel.tagName + ") уже установлена на вашем устройстве.");
                    } else if (isNewer) {
                        downloadAndInstallApk(activity, rel);
                    } else {
                        confirmAndPerformDowngrade(activity, rel);
                    }
                }
            });
            listLayout.addView(btnRel);
        }

        scrollView.addView(listLayout);
        root.addView(scrollView);

        View div2 = new View(activity);
        div2.setBackgroundColor(Color.BLACK);
        root.addView(div2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(2, 2 * density)));

        Button btnClose = new Button(activity);
        btnClose.setText("Закрыть");
        btnClose.setTextSize(12);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setTextColor(Color.BLACK);
        btnClose.setBackgroundResource(R.drawable.btn_eink);
        LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (36 * density));
        lpClose.setMargins(0, (int) (6 * density), 0, 0);
        btnClose.setLayoutParams(lpClose);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(btnClose);

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final int targetW = (int) (dm.widthPixels * 0.90);
        final int targetH = (int) (dm.heightPixels * 0.82);

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setLayout(targetW, targetH);
                }
                EpdController.requestFullRefresh(activity, root);
            }
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(targetW, targetH);
        }
    }

    /**
     * Подтверждение и выполнение безопасного отката (Downgrade) на предыдущую версию.
     * Сохраняет резервную копию БД/токена в /sdcard/Books/YandexBooks/backup/ и скачивает выбранный APK в /sdcard/Download/.
     */
    private void confirmAndPerformDowngrade(final Activity activity, final ReleaseInfo targetRelease) {
        String msg = "Вы выбрали возврат на предыдущую версию " + targetRelease.tagName + ".\n\n" +
                "1. Приложение автоматически сохранит резервную копию ваших книг, прогресса и авторизации в /sdcard/Books/YandexBooks/backup/.\n" +
                "2. Скачает установочный файл " + targetRelease.apkFileName + " в папку «Загрузки» (/sdcard/Download/).\n" +
                "3. Так как система Android не позволяет устанавливать старую версию поверх новой без удаления, вам будет предложено удалить текущую версию и запустить скачанный файл из папки «Загрузки».";

        EinkNotice.showConfirm(
                activity,
                "Откат на " + targetRelease.tagName,
                msg,
                "Скачать и откатить",
                "Отмена",
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            com.onyx.yandexbooks.core.storage.DatabaseHelper.getInstance(activity)
                                    .exportBackupToPublicStorage(activity);
                        } catch (Throwable ignored) {}
                        downloadAndInstallDowngradeApk(activity, targetRelease);
                    }
                },
                null
        );
    }

    private void downloadAndInstallDowngradeApk(final Activity activity, final ReleaseInfo release) {
        if (activity == null || activity.isFinishing()) return;

        final ProgressDialog progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("Откат версии");
        progressDialog.setMessage("Скачивание " + release.tagName + " в Загрузки...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setProgress(0);
        progressDialog.setCancelable(false);
        progressDialog.show();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                File targetFile = null;
                try {
                    File downloadDir = resolveWritableDownloadDir(activity, false);
                    if (downloadDir == null || !downloadDir.canWrite()) {
                        throw new Exception("Нет прав на запись в папку «Загрузки» (/sdcard/Download). Выдайте разрешение памяти приложению.");
                    }
                    String fname = release.apkFileName != null ? release.apkFileName : ("yandex-books-lite-" + release.tagName + ".apk");
                    targetFile = new File(downloadDir, fname);

                    OkHttpClient client = HttpClientFactory.getClient();
                    Request request = new Request.Builder()
                            .url(release.apkDownloadUrl)
                            .header("User-Agent", "OnyxYandexBooks-App/" + getCurrentVersionName(activity))
                            .build();

                    Response response = client.newCall(request).execute();
                    if (!response.isSuccessful()) {
                        throw new Exception("HTTP " + response.code());
                    }

                    long contentLength = response.body().contentLength();
                    if (contentLength <= 0 && release.apkSize > 0) contentLength = release.apkSize;

                    InputStream in = response.body().byteStream();
                    FileOutputStream out = new FileOutputStream(targetFile);
                    byte[] buffer = new byte[8192];
                    long totalRead = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        totalRead += read;
                        if (contentLength > 0) {
                            final int pct = (int) ((totalRead * 100) / contentLength);
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (progressDialog.isShowing()) {
                                        progressDialog.setProgress(pct);
                                    }
                                }
                            });
                        }
                    }
                    out.flush();
                    out.close();
                    in.close();
                    targetFile.setReadable(true, false);

                    final File finalFile = targetFile;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (progressDialog.isShowing()) progressDialog.dismiss();
                            EinkNotice.showConfirm(
                                    activity,
                                    "Файл " + release.tagName + " готов",
                                    "Резервная копия данных сохранена.\nAPK-файл сохранен в:\n" + finalFile.getAbsolutePath() + "\n\nНажмите «Удалить текущую», подтвердите удаление, а затем установите скачанный APK из папки «Загрузки» (Download).",
                                    "Удалить текущую",
                                    "Установить поверх",
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                Intent uninstall = new Intent(Intent.ACTION_DELETE);
                                                uninstall.setData(Uri.parse("package:" + activity.getPackageName()));
                                                uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                                activity.startActivity(uninstall);
                                            } catch (Exception e) {
                                                installApk(activity, finalFile);
                                            }
                                        }
                                    },
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            installApk(activity, finalFile);
                                        }
                                    }
                            );
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (progressDialog.isShowing()) progressDialog.dismiss();
                            EinkNotice.show(activity, "Ошибка отката", "Не удалось скачать версию " + release.tagName + ":\n" + e.getMessage());
                        }
                    });
                }
            }
        });
    }
}
