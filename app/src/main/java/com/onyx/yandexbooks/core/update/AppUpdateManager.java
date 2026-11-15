package com.onyx.yandexbooks.core.update;

import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
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
     * Очищает Markdown-разметку, блочные цитаты (>), хэштеги заголовков (#/##/###), теги [!WARNING]
     * и спецсимволы эмодзи из описания релиза в чистый читаемый текст (безопасно для JVM и E-Ink).
     */
    public static String cleanReleaseNotes(String raw) {
        java.util.List<MarkdownLine> lines = preprocessReleaseMarkdownLines(raw);
        if (lines.isEmpty()) {
            return "Улучшение стабильности, интерфейса и синхронизации.";
        }
        StringBuilder sb = new StringBuilder();
        for (MarkdownLine line : lines) {
            String plain = stripInlineBoldMarkers(line.text);
            if (sb.length() > 0 && !plain.isEmpty()) {
                sb.append("\n");
            }
            sb.append(plain);
        }
        String res = sb.toString().replaceAll("\n{3,}", "\n\n").trim();
        return res.isEmpty() ? "Улучшение стабильности, интерфейса и синхронизации." : res;
    }

    /**
     * Форматирует Markdown-описание релиза для E-Ink TextView с настоящим жирным начертанием
     * заголовков (#/##/###), предупреждений [!WARNING] и **выделений**, полностью убирая
     * сырые служебные символы Markdown.
     */
    public static CharSequence formatReleaseNotesRich(String raw) {
        try {
            java.util.List<MarkdownLine> lines = preprocessReleaseMarkdownLines(raw);
            if (lines.isEmpty()) {
                return "Улучшение стабильности, интерфейса и синхронизации.";
            }
            android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                MarkdownLine ml = lines.get(i);
                if (ssb.length() > 0 && ml.text.isEmpty()) {
                    // Сохраняем единичный пустой абзац для читаемости
                    if (ssb.length() >= 2 && ssb.charAt(ssb.length() - 1) == '\n' && ssb.charAt(ssb.length() - 2) == '\n') {
                        continue;
                    }
                    ssb.append('\n');
                    continue;
                }
                if (ssb.length() > 0 && ssb.charAt(ssb.length() - 1) != '\n') {
                    ssb.append('\n');
                }
                int lineStart = ssb.length();
                appendBoldParsedLine(ssb, ml.text, ml.isHeading || ml.isWarning);
                int lineEnd = ssb.length();
                if (ml.isHeading && lineEnd > lineStart) {
                    ssb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), lineStart, lineEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ssb.setSpan(new android.text.style.RelativeSizeSpan(1.14f), lineStart, lineEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (ml.isWarning && lineEnd > lineStart) {
                    ssb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), lineStart, lineEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            while (ssb.length() > 0 && (ssb.charAt(ssb.length() - 1) == '\n' || ssb.charAt(ssb.length() - 1) == ' ')) {
                ssb.delete(ssb.length() - 1, ssb.length());
            }
            return ssb.length() == 0 ? "Улучшение стабильности, интерфейса и синхронизации." : ssb;
        } catch (Throwable ignored) {
            // Fallback для окружения без Android Framework (например, JVM unit-тесты)
            return cleanReleaseNotes(raw);
        }
    }

    private static final class MarkdownLine {
        final String text;
        final boolean isHeading;
        final boolean isWarning;
        MarkdownLine(String text, boolean isHeading, boolean isWarning) {
            this.text = text;
            this.isHeading = isHeading;
            this.isWarning = isWarning;
        }
    }

    private static java.util.List<MarkdownLine> preprocessReleaseMarkdownLines(String raw) {
        java.util.List<MarkdownLine> out = new java.util.ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;

        String normalized = raw.replace("\r\n", "\n").replace("\r", "\n");
        String[] rawLines = normalized.split("\n", -1);
        boolean inQuoteAlert = false;

        for (String rLine : rawLines) {
            String line = rLine.trim();
            boolean isQuoted = false;
            while (line.startsWith(">")) {
                isQuoted = true;
                line = line.substring(1).trim();
            }
            if (!isQuoted) {
                inQuoteAlert = false;
            }
            if (line.equalsIgnoreCase("[!WARNING]") || line.equalsIgnoreCase("[!IMPORTANT]")
                    || line.equalsIgnoreCase("[!CAUTION]") || line.equalsIgnoreCase("[!NOTE]")) {
                inQuoteAlert = true;
                continue;
            }
            // Убираем горизонтальные разделители --- / ***
            if (line.matches("^[-*_]{3,}$")) {
                if (!out.isEmpty() && !out.get(out.size() - 1).text.isEmpty()) {
                    out.add(new MarkdownLine("", false, false));
                }
                continue;
            }

            boolean isHeading = false;
            if (line.matches("^#{1,6}\\s+.*")) {
                isHeading = true;
                line = line.replaceFirst("^#{1,6}\\s+", "").trim();
            } else if (line.startsWith("#")) {
                isHeading = true;
                line = line.replaceFirst("^#+\\s*", "").trim();
            }

            // Очищаем ссылки [текст](url), inline `код`, маркеры списков и эмодзи
            line = line.replaceAll("\\[([^\\]]+)\\]\\([^\\)]+\\)", "$1");
            line = line.replaceAll("`([^`]+)`", "$1");
            line = line.replaceAll("\\\\([_*#\\[\\]()~`>-])", "$1");
            line = line.replaceAll("^[\\s]*[-*]\\s+", "• ");
            line = stripEmojiChars(line).trim();
            // Заменяем одиночные курсивные *слово* (после сохранения **bold**)
            line = replaceSingleAsteriskItalics(line);

            if (line.isEmpty()) {
                if (!out.isEmpty() && !out.get(out.size() - 1).text.isEmpty()) {
                    out.add(new MarkdownLine("", false, false));
                }
                continue;
            }
            if (isHeading && !out.isEmpty() && !out.get(out.size() - 1).text.isEmpty()) {
                out.add(new MarkdownLine("", false, false));
            }
            out.add(new MarkdownLine(line, isHeading, isQuoted || inQuoteAlert));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).text.isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static String stripEmojiChars(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int len = s.length();
        while (i < len) {
            int cp = Character.codePointAt(s, i);
            i += Character.charCount(cp);
            if (cp == 0xFE0F || cp == 0x200D
                    || (cp >= 0x2600 && cp <= 0x27BF)
                    || (cp >= 0x1F000 && cp <= 0x1FAFF)) {
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    private static String replaceSingleAsteriskItalics(String s) {
        if (s == null || s.indexOf('*') < 0) return s;
        // Временно экранируем двойные звездочки **, чистим одиночные *, возвращаем **
        String token = "\u0007BOLD\u0007";
        String tmp = s.replace("**", token);
        tmp = tmp.replaceAll("\\*([^*]+)\\*", "$1").replace("*", "");
        return tmp.replace(token, "**");
    }

    private static String stripInlineBoldMarkers(String s) {
        if (s == null) return "";
        return s.replace("**", "");
    }

    private static void appendBoldParsedLine(android.text.SpannableStringBuilder ssb, String line, boolean alreadyAllBold) {
        if (line == null || line.isEmpty()) return;
        int pos = 0;
        int len = line.length();
        while (pos < len) {
            int open = line.indexOf("**", pos);
            if (open < 0) {
                ssb.append(line.substring(pos));
                break;
            }
            int close = line.indexOf("**", open + 2);
            if (close < 0) {
                // Непарная **: добавляем без неё
                ssb.append(line.substring(pos).replace("**", ""));
                break;
            }
            if (open > pos) {
                ssb.append(line.substring(pos, open));
            }
            String inner = line.substring(open + 2, close);
            int spanStart = ssb.length();
            ssb.append(inner);
            int spanEnd = ssb.length();
            if (!alreadyAllBold && spanEnd > spanStart) {
                ssb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), spanStart, spanEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            pos = close + 2;
        }
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
        notesView.setText(formatReleaseNotesRich(release.body));
        notesView.setTextSize(13);
        notesView.setTextColor(Color.BLACK);
        notesView.setLineSpacing(3f, 1.25f);
        notesView.setPadding(0, (int) (2 * density), 0, (int) (4 * density));
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
     * Проверяет, поддерживает ли выбранная версия автоматический подхват
     * резервной копии из /sdcard/Books/YandexBooks/backup/ при первом запуске (>= 1.6.1-beta).
     */
    public static boolean supportsAutoBackupRestore(String tagOrVersion) {
        if (tagOrVersion == null || tagOrVersion.trim().isEmpty()) return false;
        return !isVersionNewer("1.6.1-beta", tagOrVersion);
    }

    private static String buildDowngradeRestoreNotice(String targetTag) {
        if (supportsAutoBackupRestore(targetTag)) {
            return "• **Авторизация, полки и закладки:** версия " + targetTag + " автоматически подхватит резервную копию из /sdcard/Books/YandexBooks/backup/ при первом запуске (после выдачи доступа к памяти).\n" +
                    "• **Внимание про книги в кэше:** скачанные книги, которые хранились только во внутреннем кэше самой программы, система Android удалит при удалении текущей версии — их потребуется скачать заново (файлы в общей памяти /sdcard/ останутся).";
        }
        return "• **Важно для версии " + targetTag + ":** стабильная версия 1.6.0 и более ранние сборки НЕ умеют автоматически подтягивать авторизацию и настройки из резервной копии. После её установки потребуется заново войти в аккаунт Яндекса (затем полки и облачный прогресс загрузятся с сервера).\n" +
                "• **Внимание про книги в кэше:** скачанные книги, которые хранились во внутреннем кэше самой программы, при удалении текущей версии будут удалены системой Android — их нужно будет скачать повторно (резервная копия базы и файлы в общей памяти /sdcard/ сохранятся).";
    }

    /**
     * Подтверждение и выполнение безопасного отката (Downgrade) на предыдущую версию.
     * Перед скачиванием выполняет checkpoint WAL-журнала БД, проверяет резервную копию в
     * /sdcard/Books/YandexBooks/backup/ и сохраняет APK выбранной версии в /sdcard/Download/.
     */
    private void confirmAndPerformDowngrade(final Activity activity, final ReleaseInfo targetRelease) {
        String msg = "Вы выбрали откат на версию " + targetRelease.tagName + ".\n\n" +
                "1. Приложение сохранит резервную копию базы данных (книги, закладки, прогресс и сессия) в /sdcard/Books/YandexBooks/backup/.\n" +
                "2. Скачает файл " + targetRelease.apkFileName + " в папку «Загрузки» (/sdcard/Download/). Если файл с таким же размером уже скачан, повторная загрузка не потребуется.\n" +
                "3. Android запрещает устанавливать старую версию поверх новой (возникает ошибка «Приложение не установлено»), поэтому далее нужно удалить текущую версию и открыть скачанный APK в «Загрузках».\n\n" +
                buildDowngradeRestoreNotice(targetRelease.tagName);

        EinkNotice.showConfirm(
                activity,
                "Откат на " + targetRelease.tagName,
                msg,
                "Сохранить копию и скачать",
                "Отмена",
                new Runnable() {
                    @Override
                    public void run() {
                        downloadAndInstallDowngradeApk(activity, targetRelease);
                    }
                },
                null
        );
    }

    private void downloadAndInstallDowngradeApk(final Activity activity, final ReleaseInfo release) {
        if (activity == null || activity.isFinishing()) return;

        final ProgressDialog progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("Подготовка отката " + release.tagName);
        progressDialog.setMessage("Сохранение резервной копии и подготовка APK...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setProgress(0);
        progressDialog.setCancelable(false);
        progressDialog.show();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final com.onyx.yandexbooks.core.storage.DatabaseHelper.BackupSummary backupSummary =
                            com.onyx.yandexbooks.core.storage.DatabaseHelper.getInstance(activity)
                                    .exportBackupWithSummary(activity);
                    if (backupSummary == null || !backupSummary.success) {
                        throw new Exception("Не удалось сохранить резервную копию в /sdcard/Books/YandexBooks/backup/. Проверьте разрешение доступа к памяти в настройках Android.");
                    }

                    File downloadDir = resolveWritableDownloadDir(activity, false);
                    if (downloadDir == null || !downloadDir.canWrite()) {
                        throw new Exception("Нет прав на запись в папку «Загрузки» (/sdcard/Download). Выдайте разрешение памяти приложению.");
                    }
                    final String fname = release.apkFileName != null ? release.apkFileName : ("yandex-books-lite-" + release.tagName + ".apk");
                    final File targetFile = new File(downloadDir, fname);

                    boolean alreadyDownloaded = targetFile.exists()
                            && release.apkSize > 0
                            && targetFile.length() == release.apkSize;

                    if (!alreadyDownloaded) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (progressDialog.isShowing()) {
                                    progressDialog.setMessage("Скачивание " + fname + " в Загрузки...");
                                }
                            }
                        });

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
                        try {
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
                        } finally {
                            try { out.close(); } catch (Throwable ignored) {}
                            try { in.close(); } catch (Throwable ignored) {}
                            try { response.close(); } catch (Throwable ignored) {}
                        }
                    } else {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (progressDialog.isShowing()) {
                                    progressDialog.setProgress(100);
                                }
                            }
                        });
                    }
                    targetFile.setReadable(true, false);

                    boolean registeredInDm = registerApkInSystemDownloads(activity, targetFile, release.tagName);

                    final String loginLabel = (backupSummary.userLogin != null && !backupSummary.userLogin.isEmpty())
                            ? backupSummary.userLogin : "сохранена";
                    final String dmStatusNote = (android.os.Build.VERSION.SDK_INT < 29 && !registeredInDm)
                            ? "\n(Если системное окно «Загрузки» не покажет APK — откройте «Внутренняя память -> Download» в диспетчере файлов Onyx)\n"
                            : "";
                    final String readyMsg = "Резервная копия проверена и сохранена:\n" +
                            "• Книг в базе: " + backupSummary.booksCount + "\n" +
                            "• Закладок и прогресса: " + backupSummary.bookmarksCount + "\n" +
                            "• Аккаунт: " + loginLabel + "\n\n" +
                            "Установочный файл " + release.tagName + " находится в папке «Загрузки»:\n" +
                            targetFile.getAbsolutePath() + "\n" + dmStatusNote + "\n" +
                            "Как завершить откат в 2 шага:\n" +
                            "1. Нажмите «Удалить и откатить» и подтвердите удаление текущей версии.\n" +
                            "2. Откройте файл " + fname + " в «Загрузках» и нажмите «Установить».\n\n" +
                            buildDowngradeRestoreNotice(release.tagName);

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (progressDialog.isShowing()) progressDialog.dismiss();
                            EinkNotice.showConfirm(
                                    activity,
                                    "Готово к откату на " + release.tagName,
                                    readyMsg,
                                    "Удалить и откатить",
                                    "Показать в Загрузках",
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            openDownloadsFolder(activity, targetFile, true);
                                            launchPackageUninstall(activity, targetFile);
                                        }
                                    },
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            openDownloadsFolder(activity, targetFile, false);
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
                            EinkNotice.show(activity, "Ошибка отката", "Не удалось подготовить откат на версию " + release.tagName + ":\n" + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private static boolean queryDownloadManagerHasFile(DownloadManager dm, File apkFile) {
        if (dm == null || apkFile == null) return false;
        android.database.Cursor c = null;
        try {
            DownloadManager.Query q = new DownloadManager.Query()
                    .setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL);
            c = dm.query(q);
            if (c == null) return false;
            int titleIdx = c.getColumnIndex(DownloadManager.COLUMN_TITLE);
            int uriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            int sizeIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            String targetName = apkFile.getName();
            long targetSize = apkFile.length();
            while (c.moveToNext()) {
                String rowTitle = titleIdx >= 0 ? c.getString(titleIdx) : null;
                String rowUri = uriIdx >= 0 ? c.getString(uriIdx) : null;
                long rowSize = sizeIdx >= 0 ? c.getLong(sizeIdx) : -1L;
                boolean nameMatch = targetName.equals(rowTitle)
                        || (rowUri != null && rowUri.endsWith("/" + targetName));
                if (nameMatch && (targetSize <= 0 || rowSize == targetSize || rowSize <= 0)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "queryDownloadManagerHasFile failed: " + t.getMessage());
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static boolean registerApkInSystemDownloads(Context context, File apkFile, String tagName) {
        if (context == null || apkFile == null || !apkFile.exists()) return false;
        try {
            MediaScannerConnection.scanFile(
                    context.getApplicationContext(),
                    new String[]{apkFile.getAbsolutePath()},
                    new String[]{"application/vnd.android.package-archive"},
                    null
            );
        } catch (Throwable ignored) {}

        // На Android < 10 системное окно «Загрузки» (ACTION_VIEW_DOWNLOADS) читает только БД DownloadProvider.
        // Вызываем addCompletedDownload(..., showNotification=true), чтобы избежать SecurityException на VISIBILITY_HIDDEN,
        // а также проверяем реальное наличие записи через DownloadManager.Query вместо слепого кэша.
        if (android.os.Build.VERSION.SDK_INT < 29) {
            try {
                DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm == null) return false;

                if (queryDownloadManagerHasFile(dm, apkFile)) {
                    return true;
                }

                String title = apkFile.getName();
                String desc = "Яндекс Книги Lite " + (tagName != null ? tagName : "APK");
                long size = apkFile.length();
                String[] candidatePaths;
                String abs = apkFile.getAbsolutePath();
                String can = abs;
                try {
                    can = apkFile.getCanonicalPath();
                } catch (Throwable ignored) {}
                candidatePaths = can.equals(abs) ? new String[]{abs} : new String[]{abs, can};

                for (String path : candidatePaths) {
                    try {
                        long id = dm.addCompletedDownload(
                                title,
                                desc,
                                true,
                                "application/vnd.android.package-archive",
                                path,
                                size,
                                true
                        );
                        if (id > 0 || queryDownloadManagerHasFile(dm, apkFile)) {
                            return true;
                        }
                    } catch (Throwable primaryErr) {
                        Log.w(TAG, "addCompletedDownload(showNotif=true) failed for " + path + ": " + primaryErr.getMessage());
                        try {
                            long id = dm.addCompletedDownload(
                                    title,
                                    desc,
                                    true,
                                    "application/vnd.android.package-archive",
                                    path,
                                    size,
                                    false
                            );
                            if (id > 0 || queryDownloadManagerHasFile(dm, apkFile)) {
                                return true;
                            }
                        } catch (Throwable fallbackErr) {
                            Log.w(TAG, "addCompletedDownload(showNotif=false) failed for " + path + ": " + fallbackErr.getMessage());
                        }
                    }
                }
                return queryDownloadManagerHasFile(dm, apkFile);
            } catch (Throwable t) {
                Log.w(TAG, "registerApkInSystemDownloads error: " + t.getMessage());
                return false;
            }
        }
        return true;
    }

    private void openDownloadsFolder(Activity activity, File apkFile, boolean silentFallback) {
        if (activity == null || activity.isFinishing()) return;
        android.content.pm.PackageManager pm = activity.getPackageManager();
        File downloadDir = (apkFile != null) ? apkFile.getParentFile() : null;

        // 1. Сначала пробуем открыть реальную папку Download в диспетчере файлов (а не пустую БД DownloadManager)
        if (downloadDir != null && downloadDir.exists()) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    try {
                        java.lang.reflect.Method m = android.os.StrictMode.class.getMethod("disableDeathOnFileUriExposure");
                        m.invoke(null);
                    } catch (Throwable ignored) {}
                }
                Intent folderIntent = new Intent(Intent.ACTION_VIEW);
                folderIntent.setDataAndType(Uri.fromFile(downloadDir), "resource/folder");
                folderIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (pm != null && folderIntent.resolveActivity(pm) != null) {
                    activity.startActivity(folderIntent);
                    return;
                }
            } catch (Throwable ignored) {}

            // Пробуем стандартный DocumentsUI (Android 8+) на папку Download
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                try {
                    Intent docIntent = new Intent(Intent.ACTION_VIEW);
                    docIntent.setDataAndType(
                            Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                            "vnd.android.document/directory"
                    );
                    docIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (pm != null && docIntent.resolveActivity(pm) != null) {
                        activity.startActivity(docIntent);
                        return;
                    }
                } catch (Throwable ignored) {}
            }
        }

        // 2. Проверяем регистрацию в системной БД DownloadManager перед запуском ACTION_VIEW_DOWNLOADS
        boolean dmReady = android.os.Build.VERSION.SDK_INT >= 29;
        if (!dmReady && apkFile != null && apkFile.exists()) {
            dmReady = registerApkInSystemDownloads(activity, apkFile, null);
        }

        if (dmReady || silentFallback) {
            try {
                Intent dl = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
                dl.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (pm == null || dl.resolveActivity(pm) != null) {
                    activity.startActivity(dl);
                    return;
                }
            } catch (Exception ignored) {}
        }

        if (!silentFallback && apkFile != null) {
            EinkNotice.show(
                    activity,
                    "Папка «Загрузки»",
                    "Установочный файл сохранён на устройстве:\n" + apkFile.getAbsolutePath() +
                            "\n\nОткройте диспетчер файлов Onyx («Внутренняя память» -> «Download») и запустите этот файл после удаления текущей версии."
            );
        }
    }

    private void launchPackageUninstall(Activity activity, File fallbackApk) {
        if (activity == null || activity.isFinishing()) return;
        try {
            Intent uninstall = new Intent(Intent.ACTION_DELETE);
            uninstall.setData(Uri.parse("package:" + activity.getPackageName()));
            uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(uninstall);
        } catch (Exception e) {
            EinkNotice.show(
                    activity,
                    "Удаление не запущено",
                    "Удалите текущую версию через «Настройки» -> «Приложения», затем откройте файл:\n" + fallbackApk.getAbsolutePath()
            );
        }
    }
}
