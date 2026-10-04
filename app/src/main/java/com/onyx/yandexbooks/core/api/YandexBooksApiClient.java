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

    public static class ParsedProgress {
        public double percent = 0.0;
        public int chapterIndex = 0;
        public int paragraphIndex = 0;
        public long timestamp = 0L;
    }

    public static ParsedProgress extractProgress(JSONObject card, JSONObject bObj) {
        ParsedProgress result = new ParsedProgress();
        JSONObject[] candidates = new JSONObject[] {
            card != null ? card.optJSONObject("last_reading_position") : null,
            card != null ? card.optJSONObject("reading_position") : null,
            card != null ? card.optJSONObject("position") : null,
            card != null ? card.optJSONObject("progress") : null,
            card != null ? card.optJSONObject("reading_status") : null,
            bObj != null ? bObj.optJSONObject("last_reading_position") : null,
            bObj != null ? bObj.optJSONObject("reading_position") : null,
            bObj != null ? bObj.optJSONObject("position") : null,
            bObj != null ? bObj.optJSONObject("progress") : null,
            card,
            bObj
        };

        // 1. Поиск процента прочитанного
        for (JSONObject obj : candidates) {
            if (obj == null) continue;
            double p = -1.0;
            if (obj.has("percent")) p = obj.optDouble("percent", -1.0);
            else if (obj.has("reading_progress")) p = obj.optDouble("reading_progress", -1.0);
            else if (obj.has("progress")) p = obj.optDouble("progress", -1.0);
            else if (obj.has("progress_percent")) p = obj.optDouble("progress_percent", -1.0);
            else if (obj.has("percentage")) p = obj.optDouble("percentage", -1.0);

            if (p > 0.0) {
                if (p <= 1.0) {
                    result.percent = p * 100.0;
                } else {
                    result.percent = Math.min(100.0, p);
                }
                break;
            }
        }

        // 2. Поиск индекса главы
        for (JSONObject obj : candidates) {
            if (obj == null) continue;
            int ch = -1;
            if (obj.has("chapter_index")) ch = obj.optInt("chapter_index", -1);
            else if (obj.has("chapter")) ch = obj.optInt("chapter", -1);
            else if (obj.has("chap_index")) ch = obj.optInt("chap_index", -1);
            else if (obj.has("chapter_number")) ch = obj.optInt("chapter_number", -1);

            if (ch >= 0) {
                result.chapterIndex = ch;
                break;
            }
        }

        // 3. Поиск параграфа / смещения
        for (JSONObject obj : candidates) {
            if (obj == null) continue;
            int par = -1;
            if (obj.has("paragraph_index")) par = obj.optInt("paragraph_index", -1);
            else if (obj.has("paragraph")) par = obj.optInt("paragraph", -1);
            else if (obj.has("point")) par = obj.optInt("point", -1);
            else if (obj.has("offset")) par = obj.optInt("offset", -1);

            if (par >= 0) {
                result.paragraphIndex = par;
                break;
            }
        }

        // 4. Поиск временной метки
        for (JSONObject obj : candidates) {
            if (obj == null) continue;
            long ts = 0L;
            if (obj.has("timestamp")) ts = obj.optLong("timestamp", 0L);
            else if (obj.has("updated_at")) ts = obj.optLong("updated_at", 0L);
            else if (obj.has("last_read_at")) ts = obj.optLong("last_read_at", 0L);

            if (ts > 0) {
                if (ts < 10000000000L) ts *= 1000L;
                result.timestamp = ts;
                break;
            }
        }
        if (result.timestamp <= 0) {
            result.timestamp = System.currentTimeMillis();
        }

        return result;
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

        // Комплексный парсинг полного ФИО авторов
        String authorText = parseAuthorString(bObj, card);

        // Корректный парсинг обложки
        String coverUrl = "";
        JSONObject coverObj = bObj.optJSONObject("cover");
        if (coverObj != null) {
            coverUrl = coverObj.optString("medium", coverObj.optString("large", coverObj.optString("small", coverObj.optString("thumbnail", ""))));
        }
        if (coverUrl.isEmpty()) {
            coverUrl = bObj.optString("cover_url", bObj.optString("cover", ""));
        }

        // Комплексный парсинг прогресса и позиции чтения из всех возможных полей Bookmate
        ParsedProgress pr = extractProgress(card, bObj);

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
            if (pr.percent >= 99.0) {
                mappedShelf = "done";
            } else if (pr.percent > 0.0) {
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

        Book book = new Book();
        book.setUuid(uuid);
        book.setTitle(title);
        book.setAuthor(authorText);
        book.setAnnotation(cleanAnn);
        book.setCoverUrl(coverUrl);
        book.setPercent(pr.percent);
        book.setCurrentChapterIndex(pr.chapterIndex);
        book.setCurrentParagraphIndex(pr.paragraphIndex);
        book.setShelfType(mappedShelf);
        book.setLastReadTimestamp(pr.timestamp);

        return book;
    }

    private String parseAuthorString(JSONObject bObj, JSONObject card) {
        // 1. Проверяем authors_text
        String authorsText = bObj != null ? bObj.optString("authors_text", "") : "";
        if (authorsText.isEmpty() && card != null) {
            authorsText = card.optString("authors_text", "");
        }

        // 2. Извлекаем авторов из массива authors / authors_objects
        JSONArray arr = bObj != null ? bObj.optJSONArray("authors") : null;
        if (arr == null && bObj != null) arr = bObj.optJSONArray("authors_objects");
        if (arr == null && card != null) arr = card.optJSONArray("authors");

        String fullFromArr = "";
        if (arr != null && arr.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                String name = extractPersonName(item);
                if (!name.isEmpty()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(name);
                }
            }
            fullFromArr = sb.toString().trim();
        }

        // 3. Извлекаем автора из одиночного объекта author
        JSONObject aObj = bObj != null ? bObj.optJSONObject("author") : null;
        if (aObj == null && card != null) aObj = card.optJSONObject("author");
        String fullFromObj = "";
        if (aObj != null) {
            fullFromObj = extractPersonName(aObj);
        }

        // Выбираем наиболее информативное имя
        if (!fullFromArr.isEmpty()) {
            return fullFromArr;
        }
        if (!fullFromObj.isEmpty()) {
            return fullFromObj;
        }
        if (isValidAuthor(authorsText)) {
            return authorsText.trim();
        }

        String singleAuthor = bObj != null ? bObj.optString("author", "") : "";
        if (isValidAuthor(singleAuthor)) {
            return singleAuthor.trim();
        }

        return "Автор не указан";
    }

    private String extractPersonName(Object item) {
        if (item == null) return "";
        if (item instanceof String) {
            return ((String) item).trim();
        }
        if (item instanceof JSONObject) {
            JSONObject obj = (JSONObject) item;
            String fn = obj.optString("first_name", "").trim();
            String mn = obj.optString("middle_name", "").trim();
            String ln = obj.optString("last_name", "").trim();
            String full = obj.optString("full_name", obj.optString("name", "")).trim();

            if (!ln.isEmpty() && !fn.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append(fn).append(" ");
                if (!mn.isEmpty()) sb.append(mn).append(" ");
                sb.append(ln);
                return sb.toString().trim();
            }
            if (!full.isEmpty()) {
                if (!ln.isEmpty() && !full.contains(ln)) {
                    return (full + " " + ln).trim();
                }
                return full;
            }
            if (!ln.isEmpty()) return ln;
            if (!fn.isEmpty()) return fn;
        }
        return "";
    }

    private boolean isValidAuthor(String text) {
        if (text == null) return false;
        String t = text.trim();
        return !t.isEmpty() && !"null".equalsIgnoreCase(t) && !"undefined".equalsIgnoreCase(t);
    }

    /**
     * Запрос актуальной позиции чтения из облака Яндекса.
     */
    public void getReadingProgress(final String bookUuid, final ApiCallback<ReadingProgress> callback) {
        String endpoint = BASE_URL + "/profile/library_cards?book_uuid=" + bookUuid;
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                getReadingProgressFallback(bookUuid, callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    getReadingProgressFallback(bookUuid, callback);
                    return;
                }
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONArray arr = json.optJSONArray("library_cards");
                    if (arr != null && arr.length() > 0) {
                        JSONObject card = arr.getJSONObject(0);
                        JSONObject bObj = card.optJSONObject("book");
                        ParsedProgress pr = extractProgress(card, bObj);
                        final ReadingProgress progress = new ReadingProgress(bookUuid, pr.percent, pr.chapterIndex, pr.paragraphIndex, 0, pr.timestamp);
                        progress.setSyncedWithServer(true);
                        postSuccess(callback, progress);
                        return;
                    }
                    getReadingProgressFallback(bookUuid, callback);
                } catch (Exception e) {
                    getReadingProgressFallback(bookUuid, callback);
                }
            }
        });
    }

    private void getReadingProgressFallback(final String bookUuid, final ApiCallback<ReadingProgress> callback) {
        String endpoint = BASE_URL + "/books/" + bookUuid + "/reading_position";
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                getReadingProgressFallbackCards(bookUuid, callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    getReadingProgressFallbackCards(bookUuid, callback);
                    return;
                }
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    ParsedProgress pr = extractProgress(json, json.optJSONObject("book"));
                    final ReadingProgress progress = new ReadingProgress(bookUuid, pr.percent, pr.chapterIndex, pr.paragraphIndex, 0, pr.timestamp);
                    progress.setSyncedWithServer(true);
                    postSuccess(callback, progress);
                } catch (Exception e) {
                    getReadingProgressFallbackCards(bookUuid, callback);
                }
            }
        });
    }

    private void getReadingProgressFallbackCards(final String bookUuid, final ApiCallback<ReadingProgress> callback) {
        String endpoint = BASE_URL + "/profile/library_cards/" + bookUuid;
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
                    JSONObject card = json.optJSONObject("library_card");
                    if (card == null) card = json;
                    ParsedProgress pr = extractProgress(card, card.optJSONObject("book"));
                    final ReadingProgress progress = new ReadingProgress(bookUuid, pr.percent, pr.chapterIndex, pr.paragraphIndex, 0, pr.timestamp);
                    progress.setSyncedWithServer(true);
                    postSuccess(callback, progress);
                } catch (Exception e) {
                    postError(callback, "Ошибка парсинга fallback прогресса: " + e.getMessage());
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
     * Отправка позиции чтения в облако Яндекса с поддержкой формата Bookmate.
     */
    public void sendReadingProgress(final ReadingProgress progress, final ApiCallback<Boolean> callback) {
        String endpoint = BASE_URL + "/books/" + progress.getBookUuid() + "/progress";

        try {
            double normalizedFraction = Math.min(1.0, Math.max(0.0, progress.getPercent() / 100.0));
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("percent", normalizedFraction);
            bodyJson.put("reading_progress", normalizedFraction);
            bodyJson.put("chapter_index", progress.getChapterIndex());
            bodyJson.put("paragraph_index", progress.getParagraphIndex());
            bodyJson.put("timestamp", progress.getTimestamp());

            JSONObject posObj = new JSONObject();
            posObj.put("chapter", progress.getChapterIndex());
            posObj.put("chapter_index", progress.getChapterIndex());
            posObj.put("paragraph", progress.getParagraphIndex());
            posObj.put("paragraph_index", progress.getParagraphIndex());
            posObj.put("percent", normalizedFraction);
            posObj.put("timestamp", progress.getTimestamp());
            bodyJson.put("position", posObj);
            bodyJson.put("reading_position", posObj);

            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
            Request request = createAuthRequestBuilder(endpoint).post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    sendReadingProgressFallback(progress, callback);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    if (response.isSuccessful()) {
                        postSuccess(callback, true);
                    } else {
                        sendReadingProgressFallback(progress, callback);
                    }
                }
            });
        } catch (Exception e) {
            postError(callback, e.getMessage());
        }
    }

    private void sendReadingProgressFallback(final ReadingProgress progress, final ApiCallback<Boolean> callback) {
        String endpoint = BASE_URL + "/profile/library_cards/" + progress.getBookUuid() + "/reading_position";
        try {
            double normalizedFraction = Math.min(1.0, Math.max(0.0, progress.getPercent() / 100.0));
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("percent", normalizedFraction);
            bodyJson.put("reading_progress", normalizedFraction);
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
                    if (response.isSuccessful()) {
                        postSuccess(callback, true);
                    } else {
                        postError(callback, "Ошибка сервера при синхронизации прогресса: HTTP " + response.code());
                    }
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
