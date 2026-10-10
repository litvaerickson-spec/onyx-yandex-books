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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.onyx.yandexbooks.R;
import com.onyx.yandexbooks.core.eink.EinkScrollView;
import com.onyx.yandexbooks.core.eink.EpdController;
import com.onyx.yandexbooks.core.network.HttpClientFactory;

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
     * Сравнение семантических версий (SemVer).
     * Возвращает true, если версия latestTag новее, чем currentVersion.
     */
    public static boolean isVersionNewer(String latestTag, String currentVersion) {
        if (latestTag == null || currentVersion == null) return false;
        String l = latestTag.trim().replaceAll("^[vV]", "");
        String c = currentVersion.trim().replaceAll("^[vV]", "");
        if (l.equalsIgnoreCase(c)) return false;

        String[] lParts = l.split("[.-]");
        String[] cParts = c.split("[.-]");
        int max = Math.max(lParts.length, cParts.length);
        for (int i = 0; i < max; i++) {
            int lVal = 0;
            int cVal = 0;
            if (i < lParts.length) {
                try {
                    lVal = Integer.parseInt(lParts[i].replaceAll("\\D+", ""));
                } catch (Exception ignored) {}
            }
            if (i < cParts.length) {
                try {
                    cVal = Integer.parseInt(cParts[i].replaceAll("\\D+", ""));
                } catch (Exception ignored) {}
            }
            if (lVal > cVal) return true;
            if (lVal < cVal) return false;
        }
        return false;
    }

    /**
     * Запрос последней версии с GitHub Releases API.
     */
    public void fetchLatestRelease(final Context context, final UpdateCheckCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    OkHttpClient client = HttpClientFactory.getClient();
                    Request request = new Request.Builder()
                            .url(GITHUB_LATEST_RELEASE_URL)
                            .header("User-Agent", "OnyxYandexBooks-App/" + getCurrentVersionName(context))
                            .header("Accept", "application/vnd.github.v3+json")
                            .build();

                    Response response = client.newCall(request).execute();
                    if (!response.isSuccessful()) {
                        postResult(callback, false, null, "Ошибка GitHub API: HTTP " + response.code());
                        return;
                    }

                    String jsonStr = response.body().string();
                    JSONObject json = new JSONObject(jsonStr);

                    final ReleaseInfo release = new ReleaseInfo();
                    release.tagName = json.optString("tag_name", "");
                    release.versionName = release.tagName.replaceAll("^[vV]", "");
                    release.title = json.optString("name", release.tagName);
                    release.body = json.optString("body", "");

                    JSONArray assets = json.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
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
                        postResult(callback, false, release, "В релизе " + release.tagName + " нет APK файла");
                        return;
                    }

                    String current = getCurrentVersionName(context);
                    boolean isNewer = isVersionNewer(release.tagName, current);

                    postResult(callback, isNewer, release, isNewer ? "Доступно обновление" : "У вас последняя версия");
                } catch (Exception e) {
                    Log.e(TAG, "Error checking update from GitHub", e);
                    postResult(callback, false, null, "Сбой проверки обновления: " + e.getMessage());
                }
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

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        float density = dm.density;

        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int padH = (int) (14 * density);
        int padV = (int) (12 * density);
        root.setPadding(padH, padV, padH, padV);

        // Заголовок
        TextView titleView = new TextView(activity);
        titleView.setText("Обновление " + release.tagName);
        titleView.setTextSize(14);
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
        infoView.setTextSize(11);
        infoView.setTextColor(Color.BLACK);
        infoView.setSingleLine(true);
        infoView.setPadding(0, (int) (2 * density), 0, (int) (4 * density));
        root.addView(infoView);

        // Разделитель
        View divider = new View(activity);
        divider.setBackgroundColor(Color.BLACK);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        // Описание изменений (Scrollable)
        final EinkScrollView scrollView = new EinkScrollView(activity);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollLp.setMargins(0, (int) (4 * density), 0, (int) (4 * density));
        scrollView.setLayoutParams(scrollLp);

        TextView notesView = new TextView(activity);
        notesView.setText(cleanReleaseNotes(release.body));
        notesView.setTextSize(12);
        notesView.setTextColor(Color.BLACK);
        notesView.setLineSpacing(3f, 1.15f);
        scrollView.addView(notesView);
        root.addView(scrollView);

        // Разделитель
        View divider2 = new View(activity);
        divider2.setBackgroundColor(Color.BLACK);
        root.addView(divider2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        // Кнопки действий
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (36 * density)));
        btnRow.setPadding(0, (int) (4 * density), 0, 0);

        Button btnInstall = new Button(activity);
        btnInstall.setText("Обновить");
        btnInstall.setTextSize(11);
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
        btnCancel.setText("Позже");
        btnCancel.setTextSize(11);
        btnCancel.setTypeface(null, Typeface.BOLD);
        btnCancel.setTextColor(Color.BLACK);
        btnCancel.setBackgroundResource(R.drawable.btn_eink);
        btnCancel.setPadding(0, 0, 0, 0);
        btnCancel.setSingleLine(true);
        LinearLayout.LayoutParams lpCancel = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f);
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

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final int targetW = (int) (dm.widthPixels * 0.90);
        final int targetH = (int) (dm.heightPixels * 0.82);

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        return scrollView.pageScrollEink(true);
                    } else if (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        return scrollView.pageScrollEink(false);
                    }
                }
                return false;
            }
        });

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
                    dialog.getWindow().setLayout(targetW, targetH);
                }
                EpdController.requestFullRefresh(activity, root);
            }
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
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
                    // Выбираем публичную директорию Загрузок, доступную PackageInstaller
                    File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (downloadDir == null || !downloadDir.exists()) {
                        downloadDir = new File("/sdcard/Download");
                    }
                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs();
                    }

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
                            Toast.makeText(activity, "Сбой загрузки обновления: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    /**
     * Запуск нативного PackageInstaller Android для обновления поверх существующей версии.
     * При обновлении без удаления приложения все данные (база SQLite, SharedPreferences, книги)
     * полностью сохраняются.
     */
    public static void installApk(Context context, File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            Toast.makeText(context, "Файл APK не найден", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            apkFile.setReadable(true, false);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error launching package installer", e);
            Toast.makeText(context, "Не удалось запустить установщик: " + e.getMessage() + "\nФайл сохранен: " + apkFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }
}
