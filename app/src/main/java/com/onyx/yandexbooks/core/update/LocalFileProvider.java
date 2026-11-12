package com.onyx.yandexbooks.core.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Легковесный read-only ContentProvider (без зависимости от AndroidX),
 * обеспечивающий безопасную передачу локальных APK и EPUB через content:// URI
 * системному PackageInstaller и внешним ридерам на Android 7.0 – 14+.
 */
public class LocalFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.onyx.yandexbooks.fileprovider";

    public static Uri getUriForFile(File file) {
        if (file == null) return null;
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("file")
                .appendQueryParameter("path", file.getAbsolutePath())
                .build();
    }

    private File resolveAllowedFile(Uri uri) throws FileNotFoundException {
        if (uri == null) throw new FileNotFoundException("Empty URI");
        String path = uri.getQueryParameter("path");
        if (path == null || path.trim().isEmpty()) {
            throw new FileNotFoundException("Missing path");
        }
        Context ctx = getContext();
        if (ctx == null) {
            throw new FileNotFoundException("No context");
        }
        try {
            File target = new File(path).getCanonicalFile();
            if (!target.exists() || !target.isFile()) {
                throw new FileNotFoundException("File does not exist: " + path);
            }
            if (isInsideAllowedRoot(ctx, target)) {
                return target;
            }
            throw new FileNotFoundException("Access denied outside allowed directories: " + path);
        } catch (IOException e) {
            throw new FileNotFoundException("Invalid file path: " + e.getMessage());
        }
    }

    private boolean isInsideAllowedRoot(Context ctx, File canonicalTarget) {
        String targetPath = canonicalTarget.getAbsolutePath();
        File[] roots = new File[] {
                ctx.getFilesDir(),
                ctx.getCacheDir(),
                ctx.getExternalFilesDir(null),
                ctx.getExternalCacheDir(),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                new File(Environment.getExternalStorageDirectory(), "Books/YandexBooks"),
                new File("/sdcard/Download")
        };
        for (File r : roots) {
            if (r == null) continue;
            try {
                String rootPath = r.getCanonicalPath();
                if (targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator)) {
                    return true;
                }
            } catch (IOException ignored) {}
        }
        return false;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolveAllowedFile(uri);
            String[] cols = (projection != null && projection.length > 0)
                    ? projection
                    : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                    row[i] = file.getName();
                } else if (OpenableColumns.SIZE.equals(cols[i])) {
                    row[i] = file.length();
                }
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) {
            return new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, 0);
        }
    }

    @Override
    public String getType(Uri uri) {
        String path = uri != null ? uri.getQueryParameter("path") : null;
        if (path != null) {
            String lower = path.toLowerCase();
            if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
            if (lower.endsWith(".epub")) return "application/epub+zip";
        }
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && (mode.contains("w") || mode.contains("t"))) {
            throw new SecurityException("Read-only provider");
        }
        File file = resolveAllowedFile(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }
}
