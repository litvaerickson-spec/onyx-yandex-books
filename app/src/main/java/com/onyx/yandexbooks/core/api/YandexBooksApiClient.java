package com.onyx.yandexbooks.core.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.onyx.yandexbooks.core.api.models.Book;
import com.onyx.yandexbooks.core.api.models.Chapter;
import com.onyx.yandexbooks.core.api.models.ReadingProgress;
import com.onyx.yandexbooks.core.auth.TokenStorage;
import com.onyx.yandexbooks.core.network.HttpClientFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Клиент API сервиса Яндекс Книги (Bookmate).
 */
public class YandexBooksApiClient {

    private static final String TAG = "YandexBooksApi";
    private static final String BASE_URL = "https://api.bookmate.yandex.net/api/v5";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    public interface ApiCallback<T> {
        void onSuccess(T result);
        void onError(String errorMessage);
    }

    private final OkHttpClient httpClient;
    private final TokenStorage tokenStorage;
    private final Handler mainHandler;

    public YandexBooksApiClient(TokenStorage tokenStorage) {
        this.httpClient = HttpClientFactory.getClient();
        this.tokenStorage = tokenStorage;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    private Request.Builder createAuthRequestBuilder(String url) {
        String token = tokenStorage.getAccessToken();
        Request.Builder builder = new Request.Builder().url(url);
        if (token != null && !token.isEmpty()) {
            builder.addHeader("Auth-Token", token);
            builder.addHeader("Authorization", "OAuth " + token);
        }
        builder.addHeader("App-Platform", "android");
        builder.addHeader("App-Language", "ru");
        builder.addHeader("App-Locale", "ru");
        builder.addHeader("Bookmate-Version", "20200305");
        builder.addHeader("Device-Os", "Android");
        builder.addHeader("App-User-Agent", "Samsung/Galaxy_A51 Android/12 Bookmate/3.7.3");
        builder.addHeader("Accept", "application/json");
        builder.addHeader("User-Agent", "okhttp/3.12.13");
        return builder;
    }

    /**
     * Загрузка всех книг пользователя с пагинацией (до 100 книг на страницу).
     */
    public void getAllUserBooks(final ApiCallback<List<Book>> callback) {
        final List<Book> allBooks = new ArrayList<>();
        loadLibraryPage(1, allBooks, callback);
    }

    private void loadLibraryPage(final int page, final List<Book> accumulated, final ApiCallback<List<Book>> callback) {
        String endpoint = BASE_URL + "/profile/library_cards?per_page=100&page=" + page;
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, final IOException e) {
                if (!accumulated.isEmpty()) {
                    // Если хоть какие-то страницы успели загрузиться, отдаем их
                    postSuccess(callback, accumulated);
                } else {
                    postError(callback, "Ошибка сети: " + e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    if (!accumulated.isEmpty()) {
                        postSuccess(callback, accumulated);
                    } else {
                        postError(callback, "Ошибка загрузки библиотеки: HTTP " + response.code());
                    }
                    return;
                }

                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONArray cardsArray = json.optJSONArray("library_cards");

                    int countOnPage = 0;
                    if (cardsArray != null) {
                        countOnPage = cardsArray.length();
                        for (int i = 0; i < countOnPage; i++) {
                            JSONObject card = cardsArray.getJSONObject(i);
                            Book book = parseBookFromCard(card);
                            if (book != null) {
                                accumulated.add(book);
                            }
                        }
                    }

                    // Если пришло 100 элементов, возможно есть следующая страница
                    if (countOnPage >= 100 && page < 10) {
                        loadLibraryPage(page + 1, accumulated, callback);
                    } else {
                        postSuccess(callback, accumulated);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing library page " + page, e);
                    if (!accumulated.isEmpty()) {
                        postSuccess(callback, accumulated);
                    } else {
                        postError(callback, "Ошибка парсинга: " + e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Загрузка списка книг для заданной полки (reading, to_read, done).
     */
    public void getShelfBooks(final String shelfType, final ApiCallback<List<Book>> callback) {
        getAllUserBooks(new ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> allBooks) {
                List<Book> filtered = new ArrayList<>();
                for (Book b : allBooks) {
                    if (shelfType.equals(b.getShelfType())) {
                        filtered.add(b);
                    }
                }
                callback.onSuccess(filtered);
            }

            @Override
            public void onError(String errorMessage) {
                callback.onError(errorMessage);
            }
        });
    }

    private Book parseBookFromCard(JSONObject card) {
        if (card == null) return null;

        JSONObject bObj = card.optJSONObject("book");
        if (bObj == null) bObj = card.optJSONObject("audiobook");
        if (bObj == null) bObj = card.optJSONObject("comicbook");
        if (bObj == null) bObj = card;

        String uuid = bObj.optString("uuid", card.optString("uuid", ""));
        if (uuid == null || uuid.isEmpty()) return null;

        String title = bObj.optString("title", bObj.optString("name", "Без названия"));

        // Корректный парсинг авторов
        String authorText = bObj.optString("authors_text", "");
        if (authorText == null || authorText.trim().isEmpty() || "null".equals(authorText)) {
            JSONArray authorsArr = bObj.optJSONArray("authors");
            if (authorsArr == null || authorsArr.length() == 0) {
                authorsArr = bObj.optJSONArray("authors_objects");
            }
            if (authorsArr != null && authorsArr.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int a = 0; a < authorsArr.length(); a++) {
                    Object aItem = authorsArr.opt(a);
                    String aName = "";
                    if (aItem instanceof JSONObject) {
                        aName = ((JSONObject) aItem).optString("name", "");
                    } else if (aItem instanceof String) {
                        aName = (String) aItem;
                    }
                    if (!aName.isEmpty()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(aName);
                    }
                }
                authorText = sb.toString();
            }
        }
        if (authorText == null || authorText.trim().isEmpty() || "null".equals(authorText)) {
            authorText = "Автор не указан";
        }

        // Корректный парсинг обложки
        String coverUrl = "";
        JSONObject coverObj = bObj.optJSONObject("cover");
        if (coverObj != null) {
            coverUrl = coverObj.optString("medium", coverObj.optString("large", coverObj.optString("small", coverObj.optString("thumbnail", ""))));
        }
        if (coverUrl.isEmpty()) {
            coverUrl = bObj.optString("cover_url", bObj.optString("cover", ""));
        }

        // Прогресс чтения
        double prog = card.optDouble("reading_progress", 0.0);
        if (prog <= 1.0 && prog > 0.0) {
            prog = prog * 100.0;
        }

        // Определение полки по состоянию Bookmate
        String state = card.optString("state", "");
        String mappedShelf;
        if ("reading".equalsIgnoreCase(state)) {
            mappedShelf = "reading";
        } else if ("want_to_read".equalsIgnoreCase(state) || "to_read".equalsIgnoreCase(state) || "planned".equalsIgnoreCase(state)) {
            mappedShelf = "to_read";
        } else if ("finished".equalsIgnoreCase(state) || "read".equalsIgnoreCase(state) || "completed".equalsIgnoreCase(state) || "done".equalsIgnoreCase(state)) {
            mappedShelf = "done";
        } else {
            // Если state неизвестен, классифицируем по прогрессу
            if (prog >= 99.0) {
                mappedShelf = "done";
            } else if (prog > 0.0) {
                mappedShelf = "reading";
            } else {
                mappedShelf = "to_read";
            }
        }

        // Парсинг аннотации
        String rawAnn = bObj.optString("annotation", bObj.optString("editor_annotation", bObj.optString("description", "")));
        String cleanAnn = "";
        if (rawAnn != null && !rawAnn.trim().isEmpty() && !rawAnn.equals("null")) {
            cleanAnn = android.text.Html.fromHtml(rawAnn).toString().trim().replaceAll("\\s+", " ");
        }

        // Позиция чтения из облака
        int chapterIndex = 0;
        int paragraphIndex = 0;
        JSONObject posObj = card.optJSONObject("position");
        if (posObj == null) posObj = card.optJSONObject("reading_position");
        if (posObj != null) {
            chapterIndex = posObj.optInt("chapter_index", posObj.optInt("chapter", 0));
            paragraphIndex = posObj.optInt("paragraph_index", posObj.optInt("paragraph", 0));
        }

        Book book = new Book();
        book.setUuid(uuid);
        book.setTitle(title);
        book.setAuthor(authorText);
        book.setAnnotation(cleanAnn);
        book.setCoverUrl(coverUrl);
        book.setPercent(prog);
        book.setCurrentChapterIndex(chapterIndex);
        book.setCurrentParagraphIndex(paragraphIndex);
        book.setShelfType(mappedShelf);
        book.setLastReadTimestamp(System.currentTimeMillis());

        return book;
    }

    /**
     * Запрос актуальной позиции чтения из облака Яндекса.
     */
    public void getReadingProgress(final String bookUuid, final ApiCallback<ReadingProgress> callback) {
        String endpoint = BASE_URL + "/books/" + bookUuid + "/progress";
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postError(callback, "Сетевая ошибка: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    postError(callback, "HTTP " + response.code());
                    return;
                }
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONObject pObj = json.optJSONObject("progress");
                    if (pObj == null) pObj = json;

                    double pct = pObj.optDouble("percent", 0.0);
                    int chIdx = pObj.optInt("chapter_index", pObj.optInt("chapter", 0));
                    int parIdx = pObj.optInt("paragraph_index", pObj.optInt("paragraph", 0));
                    long ts = pObj.optLong("timestamp", System.currentTimeMillis());

                    final ReadingProgress progress = new ReadingProgress(bookUuid, pct, chIdx, parIdx, 0, ts);
                    progress.setSyncedWithServer(true);
                    postSuccess(callback, progress);
                } catch (Exception e) {
                    postError(callback, "Ошибка парсинга прогресса: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Скачивание файла книги в формате EPUB.
     */
    public void downloadBookEpub(final String bookUuid, final File destFile, final ApiCallback<File> callback) {
        String urlV4 = BASE_URL + "/books/" + bookUuid + "/content/v4";
        Request request = createAuthRequestBuilder(urlV4)
                .addHeader("Accept", "*/*")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Попытка альтернативного эндпоинта /content
                downloadBookEpubFallback(bookUuid, destFile, callback, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    downloadBookEpubFallback(bookUuid, destFile, callback, "HTTP " + response.code());
                    return;
                }

                saveResponseBodyToFile(response, destFile, callback);
            }
        });
    }

    private void downloadBookEpubFallback(final String bookUuid, final File destFile, final ApiCallback<File> callback, final String initialError) {
        String fallbackUrl = BASE_URL + "/books/" + bookUuid + "/content";
        Request request = createAuthRequestBuilder(fallbackUrl)
                .addHeader("Accept", "*/*")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postError(callback, "Ошибка загрузки книги: " + initialError + " -> " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    postError(callback, "Ошибка скачивания книги: HTTP " + response.code());
                    return;
                }
                saveResponseBodyToFile(response, destFile, callback);
            }
        });
    }

    private void saveResponseBodyToFile(Response response, File destFile, final ApiCallback<File> callback) {
        try {
            File parent = destFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            File tempFile = new File(destFile.getAbsolutePath() + ".tmp");
            try (InputStream in = response.body().byteStream();
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                if (destFile.exists()) {
                    destFile.delete();
                }
                tempFile.renameTo(destFile);
                postSuccess(callback, destFile);
            } else {
                postError(callback, "Файл книги оказался пустым");
            }
        } catch (Exception e) {
            postError(callback, "Ошибка записи файла книги: " + e.getMessage());
        }
    }

    /**
     * Отправка позиции чтения в облако Яндекса.
     */
    public void sendReadingProgress(final ReadingProgress progress, final ApiCallback<Boolean> callback) {
        String endpoint = BASE_URL + "/books/" + progress.getBookUuid() + "/progress";

        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("percent", progress.getPercent());
            bodyJson.put("chapter_index", progress.getChapterIndex());
            bodyJson.put("paragraph_index", progress.getParagraphIndex());
            bodyJson.put("timestamp", progress.getTimestamp());

            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
            Request request = createAuthRequestBuilder(endpoint).post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, "Ошибка отправки прогресса: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    final boolean ok = response.isSuccessful();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (ok) {
                                callback.onSuccess(true);
                            } else {
                                callback.onError("Ошибка сервера при сохранении прогресса");
                            }
                        }
                    });
                }
            });
        } catch (Exception e) {
            postError(callback, e.getMessage());
        }
    }

    private <T> void postSuccess(final ApiCallback<T> callback, final T result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(result);
            }
        });
    }

    private <T> void postError(final ApiCallback<T> callback, final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message);
            }
        });
    }
}
