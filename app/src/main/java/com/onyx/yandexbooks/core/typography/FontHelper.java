package com.onyx.yandexbooks.core.typography;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Менеджер и реестр шрифтов, оптимизированных для экранов E-Ink Carta и устройств Android 4.2–14+.
 * Поддерживает 8 встроенных гарнитур (Literata, Charis SIL, PT Serif, PT Sans, Inglobal и системные),
 * а также автоматическое сканирование пользовательских шрифтов из /storage/emulated/0/Fonts, /sdcard/Fonts,
 * Books/Fonts и импорт .ttf/.otf/.ttc файлов через системный файловый менеджер (SAF) для Android 11–14.
 */
public class FontHelper {

    private static final String TAG = "FontHelper";
    public static final String CUSTOM_PREFIX = "custom:";

    public static class FontItem {
        public final String id;
        public final String name;
        public final String subtitle;
        public final String sample;
        public final String assetPath;
        public final String filePath;

        public FontItem(String id, String name, String subtitle, String sample, String assetPath) {
            this(id, name, subtitle, sample, assetPath, null);
        }

        public FontItem(String id, String name, String subtitle, String sample, String assetPath, String filePath) {
            this.id = id;
            this.name = name;
            this.subtitle = subtitle;
            this.sample = sample;
            this.assetPath = assetPath;
            this.filePath = filePath;
        }
    }

    private static final List<FontItem> AVAILABLE_FONTS = new ArrayList<>();
    private static final Map<String, Typeface> TYPEFACE_CACHE = new HashMap<>();

    static {
        AVAILABLE_FONTS.add(new FontItem("literata", "Literata", "Специальный книжный шрифт от Google для E-Ink", "В чащах юга жил-был цитрус? Да! 12345", "fonts/literata.ttf"));
        AVAILABLE_FONTS.add(new FontItem("charis_sil", "Charis SIL", "Эталонный шрифт KOReader и AlReader", "В чащах юга жил-был цитрус? Да! 12345", "fonts/charis_sil.ttf"));
        AVAILABLE_FONTS.add(new FontItem("pt_serif", "PT Serif", "Классический русский книжный с засечками (ПараТайп)", "В чащах юга жил-был цитрус? Да! 12345", "fonts/pt_serif.ttf"));
        AVAILABLE_FONTS.add(new FontItem("pt_sans", "PT Sans", "Чистый рубленый шрифт без засечек (ПараТайп)", "В чащах юга жил-был цитрус? Да! 12345", "fonts/pt_sans.ttf"));
        AVAILABLE_FONTS.add(new FontItem("inglobal", "Inglobal", "Мягкий рубленый шрифт (Apache 2.0)", "В чащах юга жил-был цитрус? Да! 12345", "fonts/inglobal.ttf"));
        AVAILABLE_FONTS.add(new FontItem("serif", "Системный Serif", "Встроенный системный Android-шрифт с засечками", "В чащах юга жил-был цитрус? Да! 12345", null));
        AVAILABLE_FONTS.add(new FontItem("sans-serif", "Системный Sans", "Стандартный интерфейсный системный шрифт", "В чащах юга жил-был цитрус? Да! 12345", null));
        AVAILABLE_FONTS.add(new FontItem("monospace", "Моноширинный", "Фиксированная ширина символов для кода", "В чащах юга жил-был цитрус? Да! 12345", null));
    }

    public static List<FontItem> getAvailableFonts() {
        return Collections.unmodifiableList(AVAILABLE_FONTS);
    }

    public static List<FontItem> getAvailableFonts(Context context) {
        List<FontItem> result = new ArrayList<>(AVAILABLE_FONTS);
        if (context == null) {
            return result;
        }
        List<FontItem> customFonts = scanCustomFonts(context);
        result.addAll(customFonts);
        return result;
    }

    public static File getPrivateCustomFontsDir(Context context) {
        if (context == null) return null;
        File dir = new File(context.getFilesDir(), "custom_fonts");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static List<File> getCandidateFontDirectories(Context context) {
        List<File> dirs = new ArrayList<>();
        if (context != null) {
            File internalCustom = new File(context.getFilesDir(), "custom_fonts");
            dirs.add(internalCustom);
            try {
                File extAppFonts = context.getExternalFilesDir("Fonts");
                if (extAppFonts != null) dirs.add(extAppFonts);
            } catch (Throwable ignored) {}
        }
        try {
            File extRoot = Environment.getExternalStorageDirectory();
            if (extRoot != null) {
                dirs.add(new File(extRoot, "Fonts"));
                dirs.add(new File(extRoot, "fonts"));
                dirs.add(new File(extRoot, "Books/Fonts"));
                dirs.add(new File(extRoot, "Books/fonts"));
                dirs.add(new File(extRoot, "Books/YandexBooks/Fonts"));
            }
        } catch (Throwable ignored) {}
        dirs.add(new File("/storage/emulated/0/Fonts"));
        dirs.add(new File("/storage/emulated/0/fonts"));
        dirs.add(new File("/storage/emulated/0/Books/Fonts"));
        dirs.add(new File("/sdcard/Fonts"));
        dirs.add(new File("/sdcard/fonts"));
        dirs.add(new File("/sdcard/Books/Fonts"));
        return dirs;
    }

    private static List<FontItem> scanCustomFonts(Context context) {
        List<File> rawFiles = new ArrayList<>();
        Set<String> seenDirs = new HashSet<>();
        Set<String> seenFiles = new HashSet<>();

        for (File dir : getCandidateFontDirectories(context)) {
            if (dir == null) continue;
            String canonicalDir;
            try {
                canonicalDir = dir.getCanonicalPath();
            } catch (Throwable t) {
                canonicalDir = dir.getAbsolutePath();
            }
            if (!seenDirs.add(canonicalDir)) continue;
            if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) continue;
            collectFontFilesRecursive(dir, 0, 2, rawFiles, seenFiles);
        }

        // Оставляем базовые начертания, не захламляя список парными Bold/Italic при наличии Regular
        Set<String> existingRegularPrefixes = new HashSet<>();
        for (File f : rawFiles) {
            String lower = f.getName().toLowerCase(Locale.US);
            String basePrefix = extractRegularPrefix(f.getParent(), lower);
            if (basePrefix != null) {
                existingRegularPrefixes.add(basePrefix);
            }
        }

        Collections.sort(rawFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        List<FontItem> items = new ArrayList<>();
        Set<String> seenLabels = new HashSet<>();
        for (File f : rawFiles) {
            String name = f.getName();
            String lower = name.toLowerCase(Locale.US);
            String variant = detectVariantSuffix(lower);
            if (!"regular".equals(variant)) {
                String stem = stripVariantAndExt(lower);
                String parentKey = (f.getParent() != null ? f.getParent() : "") + "|" + stem;
                if (existingRegularPrefixes.contains(parentKey)) {
                    continue;
                }
            }

            String displayName = formatFontFileName(name);
            String dedupKey = displayName.toLowerCase(Locale.US) + "|" + f.length();
            if (!seenLabels.add(dedupKey)) {
                continue;
            }
            String parentName = f.getParentFile() != null ? f.getParentFile().getName() : "Fonts";
            String subtitle = "Пользовательский (" + parentName + ")";
            items.add(new FontItem(CUSTOM_PREFIX + f.getAbsolutePath(), displayName, subtitle, "В чащах юга жил-был цитрус? Да! 12345", null, f.getAbsolutePath()));
        }
        return items;
    }

    private static void collectFontFilesRecursive(File dir, int depth, int maxDepth, List<File> out, Set<String> seenFiles) {
        if (depth > maxDepth || dir == null || !dir.isDirectory()) return;
        File[] children;
        try {
            children = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (children == null) return;
        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                collectFontFilesRecursive(child, depth + 1, maxDepth, out, seenFiles);
            } else if (child.isFile() && child.length() > 1024 && child.canRead()) {
                String lower = child.getName().toLowerCase(Locale.US);
                if (lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc")) {
                    String canon;
                    try {
                        canon = child.getCanonicalPath();
                    } catch (Throwable t) {
                        canon = child.getAbsolutePath();
                    }
                    if (seenFiles.add(canon)) {
                        out.add(child);
                    }
                }
            }
        }
    }

    private static String extractRegularPrefix(String parentDir, String lowerName) {
        if (!"regular".equals(detectVariantSuffix(lowerName))) return null;
        String stem = stripVariantAndExt(lowerName);
        return (parentDir != null ? parentDir : "") + "|" + stem;
    }

    private static String detectVariantSuffix(String lowerName) {
        String noExt = lowerName;
        int dot = noExt.lastIndexOf('.');
        if (dot > 0) noExt = noExt.substring(0, dot);
        boolean isBold = noExt.endsWith("-bold") || noExt.endsWith("_bold") || noExt.endsWith(" bold")
                || noExt.endsWith("-bolditalic") || noExt.endsWith("_bolditalic") || noExt.endsWith(" bolditalic")
                || noExt.endsWith(" bold italic") || noExt.endsWith("-bd") || noExt.endsWith("_bd");
        boolean isItalic = noExt.endsWith("-italic") || noExt.endsWith("_italic") || noExt.endsWith(" italic")
                || noExt.endsWith("-bolditalic") || noExt.endsWith("_bolditalic") || noExt.endsWith(" bolditalic")
                || noExt.endsWith(" bold italic") || noExt.endsWith("-oblique") || noExt.endsWith("-it") || noExt.endsWith("_it");
        if (isBold && isItalic) return "bold_italic";
        if (isBold) return "bold";
        if (isItalic) return "italic";
        return "regular";
    }

    private static String stripVariantAndExt(String lowerName) {
        String s = lowerName;
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        String[] suffixes = new String[]{
                "-bolditalic", "_bolditalic", " bolditalic", " bold italic",
                "-bold_italic", "_bold_italic", "-bold-italic",
                "-regular", "_regular", " regular",
                "-bold", "_bold", " bold", "-bd", "_bd",
                "-italic", "_italic", " italic", "-oblique", "-it", "_it"
        };
        for (String suf : suffixes) {
            if (s.endsWith(suf)) {
                return s.substring(0, s.length() - suf.length());
            }
        }
        return s;
    }

    private static String formatFontFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "Шрифт";
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        base = base.replace("_", " ").trim();
        if (base.toLowerCase(Locale.US).endsWith("-regular")) {
            base = base.substring(0, base.length() - "-regular".length());
        } else if (base.toLowerCase(Locale.US).endsWith(" regular")) {
            base = base.substring(0, base.length() - " regular".length());
        }
        return base.trim();
    }

    /**
     * Ищет парные файлы начертаний (bold / italic / bold_italic) рядом с выбранным пользовательским шрифтом.
     */
    public static Map<String, String> resolveCustomFontFamilyPaths(String fontId) {
        Map<String, String> map = new HashMap<>();
        if (fontId == null || !fontId.startsWith(CUSTOM_PREFIX)) {
            return map;
        }
        String path = fontId.substring(CUSTOM_PREFIX.length());
        File mainFile = new File(path);
        if (!mainFile.exists() || !mainFile.canRead()) {
            return map;
        }
        map.put("regular", mainFile.getAbsolutePath());
        File parent = mainFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            return map;
        }
        String targetStem = stripVariantAndExt(mainFile.getName().toLowerCase(Locale.US));
        File[] siblings = parent.listFiles();
        if (siblings == null) return map;
        for (File sib : siblings) {
            if (sib == null || !sib.isFile() || !sib.canRead()) continue;
            String lower = sib.getName().toLowerCase(Locale.US);
            if (!lower.endsWith(".ttf") && !lower.endsWith(".otf") && !lower.endsWith(".ttc")) continue;
            if (!stripVariantAndExt(lower).equals(targetStem)) continue;
            String variant = detectVariantSuffix(lower);
            if (!map.containsKey(variant)) {
                map.put(variant, sib.getAbsolutePath());
            }
        }
        return map;
    }

    /**
     * Импортирует файл шрифта (.ttf/.otf/.ttc) по URI из SAF в приватное хранилище приложения,
     * обеспечивая гарантированный доступ на Android 11–14+ без разрешений на все файлы.
     */
    public static String importFontFromUri(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        String fileName = resolveUriFileName(context, uri);
        if (fileName == null || fileName.trim().isEmpty()) {
            fileName = "custom_font_" + System.currentTimeMillis() + ".ttf";
        }
        String lower = fileName.toLowerCase(Locale.US);
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf") && !lower.endsWith(".ttc")) {
            return null;
        }
        fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
        File dir = getPrivateCustomFontsDir(context);
        if (dir == null) return null;
        File dst = new File(dir, fileName);

        ContentResolver resolver = context.getContentResolver();
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = resolver.openInputStream(uri);
            if (in == null) return null;
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int r;
            long total = 0;
            while ((r = in.read(buf)) > 0) {
                total += r;
                if (total > 24L * 1024L * 1024L) break;
                out.write(buf, 0, r);
            }
            out.flush();
            if (dst.exists() && dst.length() > 1024) {
                Typeface test = Typeface.createFromFile(dst);
                if (test != null) {
                    String id = CUSTOM_PREFIX + dst.getAbsolutePath();
                    TYPEFACE_CACHE.put(id.toLowerCase(Locale.US).trim(), test);
                    return id;
                }
            }
            if (dst.exists()) dst.delete();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to import custom font from URI", t);
            try { if (dst.exists()) dst.delete(); } catch (Throwable ignored) {}
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String resolveUriFileName(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Throwable ignored) {}
            }
        }
        String path = uri.getLastPathSegment();
        if (path != null && path.contains("/")) {
            path = path.substring(path.lastIndexOf('/') + 1);
        }
        return path;
    }

    public static String getFontDisplayName(String fontId) {
        if (fontId == null) return "Literata";
        if (fontId.startsWith(CUSTOM_PREFIX)) {
            File f = new File(fontId.substring(CUSTOM_PREFIX.length()));
            return formatFontFileName(f.getName());
        }
        for (FontItem item : AVAILABLE_FONTS) {
            if (item.id.equalsIgnoreCase(fontId)) {
                return item.name;
            }
        }
        return "Literata";
    }

    public static synchronized Typeface getTypeface(Context context, String fontId) {
        if (fontId == null || fontId.trim().isEmpty()) {
            fontId = "literata";
        }
        String trimmed = fontId.trim();
        if (trimmed.startsWith(CUSTOM_PREFIX)) {
            String path = trimmed.substring(CUSTOM_PREFIX.length());
            String cacheKey = "custom_path:" + path;
            if (TYPEFACE_CACHE.containsKey(cacheKey)) {
                return TYPEFACE_CACHE.get(cacheKey);
            }
            try {
                File f = new File(path);
                if (f.exists() && f.length() > 0 && f.canRead()) {
                    Typeface customTf = Typeface.createFromFile(f);
                    if (customTf != null) {
                        TYPEFACE_CACHE.put(cacheKey, customTf);
                        return customTf;
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to load custom font file: " + path, t);
            }
            return Typeface.SERIF;
        }

        String key = trimmed.toLowerCase(Locale.US);
        if (TYPEFACE_CACHE.containsKey(key)) {
            return TYPEFACE_CACHE.get(key);
        }

        Typeface tf = null;
        if ("serif".equals(key)) {
            tf = Typeface.SERIF;
        } else if ("sans-serif".equals(key)) {
            tf = Typeface.SANS_SERIF;
        } else if ("monospace".equals(key)) {
            tf = Typeface.MONOSPACE;
        } else {
            // Ищем шрифт в assets
            for (FontItem item : AVAILABLE_FONTS) {
                if (item.id.equals(key) && item.assetPath != null && context != null) {
                    try {
                        tf = Typeface.createFromAsset(context.getAssets(), item.assetPath);
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to load typeface from asset: " + item.assetPath, t);
                    }
                    break;
                }
            }
            if (tf == null) {
                tf = Typeface.SERIF;
            }
        }

        TYPEFACE_CACHE.put(key, tf);
        return tf;
    }
}