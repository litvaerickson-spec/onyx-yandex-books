package com.onyx.yandexbooks.core.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.LruCache;
import android.widget.ImageView;

import com.onyx.yandexbooks.core.network.HttpClientFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Асинхронный загрузчик обложек с кэшем в памяти и на диске.
 * При отсутствии изображения генерирует контрастную обложку для E-Ink экрана.
 */
public class CoverLoader {

    private static CoverLoader instance;

    private final LruCache<String, Bitmap> memoryCache;
    private final File diskCacheDir;
    private final OkHttpClient httpClient;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public static synchronized CoverLoader getInstance(Context context) {
        if (instance == null) {
            instance = new CoverLoader(context.getApplicationContext());
        }
        return instance;
    }

    private CoverLoader(Context context) {
        // Кэш в памяти на ~30 обложек (около 4-6 МБ)
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = Math.max(1024, maxMemory / 8);
        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        diskCacheDir = new File(context.getCacheDir(), "book_covers");
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }

        httpClient = HttpClientFactory.getClient();
        executor = Executors.newFixedThreadPool(3);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public void loadCover(final ImageView imageView, final String bookUuid, final String coverUrl, final String title) {
        if (imageView == null) return;

        imageView.setTag(bookUuid);

        // 1. Проверка в памяти
        Bitmap cached = memoryCache.get(bookUuid);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        // Показываем временную контрастную заглушку с названием книги
        Bitmap placeholder = createTypographyCover(title);
        imageView.setImageBitmap(placeholder);

        // 2. Фоновая загрузка с диска или сети
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;

                // Проверка на диске
                File diskFile = new File(diskCacheDir, bookUuid + ".jpg");
                if (diskFile.exists() && diskFile.length() > 0) {
                    bitmap = decodeSampledBitmap(diskFile.getAbsolutePath(), 140, 200);
                }

                // Загрузка из сети
                if (bitmap == null && coverUrl != null && !coverUrl.trim().isEmpty() && !coverUrl.equals("null")) {
                    bitmap = downloadBitmap(coverUrl, diskFile);
                }

                if (bitmap == null) {
                    bitmap = createTypographyCover(title);
                }

                if (bitmap != null) {
                    memoryCache.put(bookUuid, bitmap);
                    final Bitmap finalBmp = bitmap;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (bookUuid.equals(imageView.getTag())) {
                                imageView.setImageBitmap(finalBmp);
                            }
                        }
                    });
                }
            }
        });
    }

    private Bitmap downloadBitmap(String url, File destFile) {
        try {
            Request request = new Request.Builder().url(url).build();
            Response response = httpClient.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                try (InputStream in = response.body().byteStream();
                     FileOutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }
                return decodeSampledBitmap(destFile.getAbsolutePath(), 140, 200);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Bitmap decodeSampledBitmap(String path, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565; // Экономия памяти на Android 4.4
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable t) {
            return null;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    /**
     * Создание контрастной типографической обложки для E-Ink Carta дисплея.
     */
    private Bitmap createTypographyCover(String title) {
        int width = 120;
        int height = 172;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);

        // Белый фон
        canvas.drawColor(Color.WHITE);

        // Черная внешняя рамка 2px
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(Color.BLACK);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);
        canvas.drawRect(new RectF(2, 2, width - 2, height - 2), borderPaint);

        // Внутренняя декоративная рамка
        borderPaint.setStrokeWidth(1f);
        canvas.drawRect(new RectF(6, 6, width - 6, height - 6), borderPaint);

        // Текст названия
        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(13f);
        textPaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));

        String displayTitle = (title != null && !title.trim().isEmpty()) ? title : "Книга";
        int textWidth = width - 20;

        StaticLayout layout = new StaticLayout(
                displayTitle,
                textPaint,
                textWidth,
                Layout.Alignment.ALIGN_CENTER,
                1.1f,
                0.0f,
                false
        );

        canvas.save();
        int textHeight = layout.getHeight();
        int yPos = Math.max(14, (height - textHeight) / 2);
        canvas.translate(10, yPos);
        layout.draw(canvas);
        canvas.restore();

        return bitmap;
    }
}
