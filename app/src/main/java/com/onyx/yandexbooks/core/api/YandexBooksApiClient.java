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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

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
        if (card == null) return result;

        String state = card.optString("state", "");
        boolean isUserCard = card.has("last_read_at") || card.has("state") || card.has("updated_at")
                || card.has("last_reading_position") || card.has("reading_position") || card.has("position")
                || card.has("library_card");

        // Если это не карточка пользователя (а элемент каталога или публичной полки), прогресс строго нулевой
        if (!isUserCard) {
            return result;
        }

        // 1. Приоритетные вложенные объекты позиции чтения (как в мобильном приложении на телефоне)
        JSONObject[] positionObjects = new JSONObject[] {
            card.optJSONObject("last_reading_position"),
            card.optJSONObject("reading_position"),
            card.optJSONObject("position")
        };

        // 1. Поиск процента прочитанного в специализированных объектах позиции
        for (JSONObject obj : positionObjects) {
            if (obj == null) continue;
            double p = -1.0;
            if (obj.has("percent")) p = obj.optDouble("percent", -1.0);
            else if (obj.has("reading_progress")) p = obj.optDouble("reading_progress", -1.0);
            else if (obj.has("progress_percent")) p = obj.optDouble("progress_percent", -1.0);
            else if (obj.has("percentage")) p = obj.optDouble("percentage", -1.0);
            else if (obj.has("progress")) {
                p = obj.optDouble("progress", -1.0);
            }

            if (p > 0.0) {
                if (p < 1.0) {
                    result.percent = p * 100.0;
                } else if (p == 1.0) {
                    // 1.0: если книга явно прочитана, то 100%, иначе 1.0%
                    if ("finished".equalsIgnoreCase(state) || "read".equalsIgnoreCase(state) || "completed".equalsIgnoreCase(state) || "done".equalsIgnoreCase(state)) {
                        result.percent = 100.0;
                    } else {
                        result.percent = 1.0;
                    }
                } else {
                    result.percent = Math.min(100.0, p);
                }
                break;
            }
        }

        // 2. Если во вложенных объектах процент не найден, проверяем свойства самой пользовательской карточки
        if (result.percent <= 0.0) {
            double p = -1.0;
            if (card.has("percent")) p = card.optDouble("percent", -1.0);
            else if (card.has("reading_progress")) p = card.optDouble("reading_progress", -1.0);
            else if (card.has("progress_percent")) p = card.optDouble("progress_percent", -1.0);
            else if (card.has("percentage")) p = card.optDouble("percentage", -1.0);

            if (p > 0.0) {
                if (p < 1.0) {
                    result.percent = p * 100.0;
                } else if (p == 1.0) {
                    if ("finished".equalsIgnoreCase(state) || "read".equalsIgnoreCase(state) || "completed".equalsIgnoreCase(state) || "done".equalsIgnoreCase(state)) {
                        result.percent = 100.0;
                    } else {
                        result.percent = 1.0;
                    }
                } else {
                    result.percent = Math.min(100.0, p);
                }
            }
        }

        // 3. Статус завершенности книги
        if (result.percent <= 0.0) {
            if ("finished".equalsIgnoreCase(state) || "read".equalsIgnoreCase(state) || "completed".equalsIgnoreCase(state) || "done".equalsIgnoreCase(state)) {
                result.percent = 100.0;
            }
        }

        // 4. Поиск индекса главы СТРОГО в объектах позиции чтения (никогда не в метаданных книги bObj!)
        JSONObject[] allCandidates = new JSONObject[] {
            positionObjects[0], positionObjects[1], positionObjects[2]
        };

        for (JSONObject obj : allCandidates) {
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

        // 5. Поиск параграфа / смещения
        for (JSONObject obj : allCandidates) {
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

        // 6. Поиск временной метки (long timestamp или ISO-8601 строка)
        for (JSONObject obj : allCandidates) {
            if (obj == null) continue;
            long ts = 0L;
            if (obj.has("timestamp")) {
                ts = obj.optLong("timestamp", 0L);
                if (ts == 0L) ts = parseIsoTimestamp(obj.optString("timestamp", null));
            } else if (obj.has("updated_at")) {
                ts = obj.optLong("updated_at", 0L);
                if (ts == 0L) ts = parseIsoTimestamp(obj.optString("updated_at", null));
            } else if (obj.has("last_read_at")) {
                ts = obj.optLong("last_read_at", 0L);
                if (ts == 0L) ts = parseIsoTimestamp(obj.optString("last_read_at", null));
            }

            if (ts > 0) {
                if (ts < 10000000000L) ts *= 1000L;
                result.timestamp = ts;
                break;
            }
        }
        // ВАЖНО: Если сервер не прислал timestamp, оставляем 0L, а НЕ System.currentTimeMillis()!
        // Иначе чужая карточка со свежим текущим временем затрет реальный локальный прогресс чтения.
        if (result.timestamp <= 0) {
            result.timestamp = 0L;
        }

        // 7. Защита от искажения 100%: если книга читается (reading) и глава 0, процент не может быть 100%
        if (result.percent >= 99.0 && "reading".equalsIgnoreCase(state) && result.chapterIndex == 0) {
            result.percent = 0.0;
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
            if (pr.percent >= 99.0 && pr.chapterIndex > 0) {
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

        if (bObj != null && bObj.has("authors") && bObj.opt("authors") instanceof String) {
            String sAuthors = bObj.optString("authors", "");
            if (isValidAuthor(sAuthors)) {
                return sAuthors.trim();
            }
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

    private static long parseIsoTimestamp(String iso) {
        if (iso == null || iso.trim().isEmpty()) return 0L;
        try {
            String clean = iso.trim();
            SimpleDateFormat sdf;
            if (clean.contains(".")) {
                sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            } else {
                sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            }
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = sdf.parse(clean);
            return (d != null) ? d.getTime() : 0L;
        } catch (Exception e) {
            try {
                String fallbackStr = iso.replace("Z", "+0000");
                if (fallbackStr.length() > 6 && fallbackStr.charAt(fallbackStr.length() - 3) == ':') {
                    fallbackStr = fallbackStr.substring(0, fallbackStr.length() - 3) + fallbackStr.substring(fallbackStr.length() - 2);
                }
                SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
                Date d = sdf2.parse(fallbackStr);
                return (d != null) ? d.getTime() : 0L;
            } catch (Exception ignored) {
                return 0L;
            }
        }
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
     * Скачивание файла книги в формате EPUB с автоматической привязкой к библиотеке и каскадным fallback.
     */
    public void downloadBookEpub(final String bookUuid, final File destFile, final ApiCallback<File> callback) {
        attemptDownloadBookEpub(bookUuid, destFile, callback, false);
    }

    private void attemptDownloadBookEpub(final String bookUuid, final File destFile, final ApiCallback<File> callback, final boolean isRetryAfterAdd) {
        String urlV4 = BASE_URL + "/books/" + bookUuid + "/content/v4";
        Request request = createAuthRequestBuilder(urlV4)
                .header("Accept", "*/*")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                downloadBookEpubFallback(bookUuid, destFile, callback, e.getMessage(), isRetryAfterAdd);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    downloadBookEpubFallback(bookUuid, destFile, callback, "HTTP " + response.code(), isRetryAfterAdd);
                    return;
                }

                saveResponseBodyToFile(response, destFile, callback, new Runnable() {
                    @Override
                    public void run() {
                        downloadBookEpubFallback(bookUuid, destFile, callback, "v4 returned non-epub or empty file", isRetryAfterAdd);
                    }
                });
            }
        });
    }

    private void downloadBookEpubFallback(final String bookUuid, final File destFile, final ApiCallback<File> callback, final String initialError, final boolean isRetryAfterAdd) {
        if (!isRetryAfterAdd) {
            // Если ошибка (например 403 Forbidden при отсутствии карточки в библиотеке),
            // регистрируем книгу в профиле пользователя и повторяем запрос
            addBookToLibrary(bookUuid, new ApiCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    attemptDownloadBookEpub(bookUuid, destFile, callback, true);
                }

                @Override
                public void onError(String addError) {
                    tryAlternativeEndpoints(bookUuid, destFile, callback, initialError);
                }
            });
            return;
        }

        tryAlternativeEndpoints(bookUuid, destFile, callback, initialError);
    }

    private void tryAlternativeEndpoints(final String bookUuid, final File destFile, final ApiCallback<File> callback, final String initialError) {
        String fallbackUrl = BASE_URL + "/books/" + bookUuid + "/content";
        Request request = createAuthRequestBuilder(fallbackUrl)
                .header("Accept", "*/*")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                tryFileEndpoint(bookUuid, destFile, callback, initialError + " -> " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    tryFileEndpoint(bookUuid, destFile, callback, initialError + " -> HTTP " + response.code());
                    return;
                }
                saveResponseBodyToFile(response, destFile, callback, new Runnable() {
                    @Override
                    public void run() {
                        tryFileEndpoint(bookUuid, destFile, callback, initialError + " -> content returned non-epub");
                    }
                });
            }
        });
    }

    private void tryFileEndpoint(final String bookUuid, final File destFile, final ApiCallback<File> callback, final String initialError) {
        String fileUrl = BASE_URL + "/books/" + bookUuid + "/file";
        Request request = createAuthRequestBuilder(fileUrl)
                .header("Accept", "*/*")
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
                    postError(callback, "Ошибка скачивания книги: " + initialError + " -> HTTP " + response.code());
                    return;
                }
                saveResponseBodyToFile(response, destFile, callback, null);
            }
        });
    }

    private void saveResponseBodyToFile(Response response, File destFile, final ApiCallback<File> callback, final Runnable onFallback) {
        try {
            String contentType = response.header("Content-Type", "");
            if (contentType != null && (contentType.contains("json") || contentType.contains("html"))) {
                String bodyStr = response.body().string();
                if (onFallback != null) {
                    onFallback.run();
                } else {
                    postError(callback, "Сервер вернул сообщение вместо книги: " + bodyStr);
                }
                return;
            }

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

            if (tempFile.exists() && tempFile.length() > 500) {
                if (destFile.exists()) {
                    destFile.delete();
                }
                tempFile.renameTo(destFile);
                postSuccess(callback, destFile);
            } else {
                if (tempFile.exists()) tempFile.delete();
                if (onFallback != null) {
                    onFallback.run();
                } else {
                    postError(callback, "Файл книги оказался поврежден или пуст");
                }
            }
        } catch (Exception e) {
            if (onFallback != null) {
                onFallback.run();
            } else {
                postError(callback, "Ошибка записи файла книги: " + e.getMessage());
            }
        }
    }

    /**
     * Отправка позиции чтения в облако Яндекса с поддержкой формата Bookmate.
     */
    public void sendReadingProgress(final ReadingProgress progress, final ApiCallback<Boolean> callback) {
        String endpoint = BASE_URL + "/books/" + progress.getBookUuid() + "/progress";

        try {
            double rawPercent = progress.getPercent();
            double normalizedFraction = Math.min(1.0, Math.max(0.0, rawPercent / 100.0));
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("percent", rawPercent);
            bodyJson.put("reading_progress", normalizedFraction);
            bodyJson.put("percentage", rawPercent);
            bodyJson.put("chapter_index", progress.getChapterIndex());
            bodyJson.put("chapter", progress.getChapterIndex());
            bodyJson.put("paragraph_index", progress.getParagraphIndex());
            bodyJson.put("paragraph", progress.getParagraphIndex());
            bodyJson.put("point", progress.getParagraphIndex());
            bodyJson.put("timestamp", progress.getTimestamp());

            JSONObject posObj = new JSONObject();
            posObj.put("chapter", progress.getChapterIndex());
            posObj.put("chapter_index", progress.getChapterIndex());
            posObj.put("paragraph", progress.getParagraphIndex());
            posObj.put("paragraph_index", progress.getParagraphIndex());
            posObj.put("point", progress.getParagraphIndex());
            posObj.put("percent", rawPercent);
            posObj.put("reading_progress", normalizedFraction);
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
            double rawPercent = progress.getPercent();
            double normalizedFraction = Math.min(1.0, Math.max(0.0, rawPercent / 100.0));
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("percent", rawPercent);
            bodyJson.put("reading_progress", normalizedFraction);
            bodyJson.put("percentage", rawPercent);
            bodyJson.put("chapter_index", progress.getChapterIndex());
            bodyJson.put("chapter", progress.getChapterIndex());
            bodyJson.put("paragraph_index", progress.getParagraphIndex());
            bodyJson.put("point", progress.getParagraphIndex());
            bodyJson.put("timestamp", progress.getTimestamp());

            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
            Request request = createAuthRequestBuilder(endpoint).post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    sendReadingProgressFallbackBooks(progress, callback);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    if (response.isSuccessful()) {
                        postSuccess(callback, true);
                    } else {
                        sendReadingProgressFallbackBooks(progress, callback);
                    }
                }
            });
        } catch (Exception e) {
            sendReadingProgressFallbackBooks(progress, callback);
        }
    }

    private void sendReadingProgressFallbackBooks(final ReadingProgress progress, final ApiCallback<Boolean> callback) {
        String endpoint = BASE_URL + "/books/" + progress.getBookUuid() + "/reading_position";
        try {
            double rawPercent = progress.getPercent();
            double normalizedFraction = Math.min(1.0, Math.max(0.0, rawPercent / 100.0));
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("percent", rawPercent);
            bodyJson.put("reading_progress", normalizedFraction);
            bodyJson.put("percentage", rawPercent);
            bodyJson.put("chapter_index", progress.getChapterIndex());
            bodyJson.put("chapter", progress.getChapterIndex());
            bodyJson.put("paragraph_index", progress.getParagraphIndex());
            bodyJson.put("point", progress.getParagraphIndex());
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

    private static final String GQL_SEARCH =
            "query Search($query: SearchParamsInput!) {\n" +
            "    search(query: $query) {\n" +
            "        page {\n" +
            "            __typename\n" +
            "            ...searchSnippetAudioBookFragment\n" +
            "            ...searchSnippetTextBookFragment\n" +
            "            ...searchSnippetComicBookFragment\n" +
            "            ...searchSnippetTextSerialFragment\n" +
            "            ...bookshelfFragment\n" +
            "            ...personFragment\n" +
            "            ...publisherFragment\n" +
            "            ...seriesFragment\n" +
            "            ...topicFragment\n" +
            "            ...userFragment\n" +
            "        }\n" +
            "        cursor\n" +
            "        rankedFilter { filterType }\n" +
            "        misspell { correctedText correctionType }\n" +
            "    }\n" +
            "}\n" +
            "fragment coverFragment on Cover { url ratio backgroundColorHex }\n" +
            "fragment personFragment on Person { avatar { __typename ...coverFragment } name uuid worksCount roles }\n" +
            "fragment bookFragment on Book { annotation name cover { __typename ...coverFragment } uuid authors { __typename ...personFragment } ageRestriction editorAnnotation }\n" +
            "fragment publisherFragment on Publisher { avatar { __typename ...coverFragment } name uuid worksCount }\n" +
            "fragment publisherBookFragment on Book { publisher { __typename ...publisherFragment } }\n" +
            "fragment translatorsBookFragment on Book { translators { __typename ...personFragment } }\n" +
            "fragment topicsBookFragment on Book { topics { name totalBook uuid } }\n" +
            "fragment subscriptionLevelsFragment on Book { subscriptionLevels }\n" +
            "fragment snippetBookFragment on Book { __typename ...bookFragment ...publisherBookFragment ...translatorsBookFragment ...topicsBookFragment ...subscriptionLevelsFragment }\n" +
            "fragment bookTagFragment on Tag { name value }\n" +
            "fragment narratorsAudioBookFragment on AudioBook { narrators { __typename ...personFragment } }\n" +
            "fragment progressFragment on Progress { finished inLibrary progress isPublic }\n" +
            "fragment progressAudioBookFragment on AudioBook { progress { __typename ...progressFragment } }\n" +
            "fragment listenersCountAudioBookFragment on AudioBook { listenersCount }\n" +
            "fragment searchSnippetAudioBookFragment on AudioBook { __typename book { __typename ...snippetBookFragment tags { __typename ...bookTagFragment } } ...narratorsAudioBookFragment ...progressAudioBookFragment ...listenersCountAudioBookFragment }\n" +
            "fragment progressTextBookFragment on TextBook { progress { __typename ...progressFragment } }\n" +
            "fragment readersCountTextBookFragment on TextBook { readersCount }\n" +
            "fragment searchSnippetTextBookFragment on TextBook { __typename book { __typename ...snippetBookFragment tags { __typename ...bookTagFragment } } ...progressTextBookFragment ...readersCountTextBookFragment }\n" +
            "fragment progressComicBookFragment on ComicBook { progress { __typename ...progressFragment } }\n" +
            "fragment readersCountComicBookFragment on ComicBook { readersCount }\n" +
            "fragment searchSnippetComicBookFragment on ComicBook { __typename book { __typename ...snippetBookFragment tags { __typename ...bookTagFragment } } ...progressComicBookFragment ...readersCountComicBookFragment }\n" +
            "fragment textSerialFragment on TextSerial { book { __typename ...bookFragment } }\n" +
            "fragment episodesTextSerialFragment on TextSerial { episodes { total } }\n" +
            "fragment readersCountTextSerialFragment on TextSerial { readersCount }\n" +
            "fragment searchSnippetTextSerialFragment on TextSerial { __typename book { __typename ...snippetBookFragment tags { __typename ...bookTagFragment } } ...textSerialFragment ...episodesTextSerialFragment ...readersCountTextSerialFragment }\n" +
            "fragment userFragment on User { avatar { __typename ...coverFragment } name uuid followersCount login }\n" +
            "fragment bookshelfFragment on Bookshelf { cover { __typename ...coverFragment } name uuid user { __typename ...userFragment } posts { total } followersCount description }\n" +
            "fragment seriesFragment on Series { authors { __typename ...personFragment } cover { __typename ...coverFragment } name uuid items { followersCount total } }\n" +
            "fragment topicFragment on Topic { name slug totalBook uuid parent { name slug totalBook uuid } }\n";

    /**
     * Загрузка рекомендаций и популярных книг каталога Яндекс Книг.
     */
    public void getRecommendations(final ApiCallback<List<Book>> callback) {
        String endpoint = BASE_URL + "/popular_searches/ru";
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                loadBookshelfByUuid("byugcjMZ", callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    loadBookshelfByUuid("byugcjMZ", callback);
                    return;
                }

                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONObject pop = json.optJSONObject("popular_searches");
                    String shelfUuid = "byugcjMZ";
                    if (pop != null) {
                        JSONArray res = pop.optJSONArray("resources");
                        if (res != null && res.length() > 0) {
                            for (int i = 0; i < res.length(); i++) {
                                JSONObject item = res.getJSONObject(i);
                                String url = item.optString("url", "");
                                if (url.contains("/bookshelves/")) {
                                    String[] parts = url.split("/bookshelves/");
                                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                                        shelfUuid = parts[1].trim();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    loadBookshelfByUuid(shelfUuid, callback);
                } catch (Exception e) {
                    loadBookshelfByUuid("byugcjMZ", callback);
                }
            }
        });
    }

    private void loadBookshelfByUuid(String shelfUuid, final ApiCallback<List<Book>> callback) {
        String endpoint = BASE_URL + "/bookshelves/" + shelfUuid + "/books";
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postError(callback, "Ошибка загрузки каталога: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    postError(callback, "Ошибка загрузки каталога: HTTP " + response.code());
                    return;
                }

                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONArray booksArray = json.optJSONArray("books");
                    List<Book> books = new ArrayList<>();
                    if (booksArray != null) {
                        for (int i = 0; i < booksArray.length(); i++) {
                            JSONObject bObj = booksArray.getJSONObject(i);
                            Book book = parseBookFromCard(bObj);
                            if (book != null) {
                                book.setPercent(0.0);
                                book.setCurrentChapterIndex(0);
                                book.setCurrentParagraphIndex(0);
                                book.setShelfType("catalog");
                                books.add(book);
                            }
                        }
                    }
                    postSuccess(callback, books);
                } catch (Exception e) {
                    postError(callback, "Ошибка парсинга каталога: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Глобальный поиск книг по базе Яндекс Книг (GraphQL API).
     */
    public void searchBooks(final String query, final ApiCallback<List<Book>> callback) {
        if (query == null || query.trim().isEmpty()) {
            postSuccess(callback, new ArrayList<Book>());
            return;
        }

        try {
            JSONObject variables = new JSONObject();
            JSONObject queryObj = new JSONObject();
            queryObj.put("query", query.trim());
            queryObj.put("noMisspell", false);
            queryObj.put("cursor", "");
            variables.put("query", queryObj);

            JSONObject payload = new JSONObject();
            payload.put("operationName", "Search");
            payload.put("variables", variables);
            payload.put("query", GQL_SEARCH);

            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, payload.toString());
            Request request = createAuthRequestBuilder("https://api-gateway.bookmate.yandex.net/graphql")
                    .post(body)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, "Ошибка сети: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        postError(callback, "Ошибка поиска: HTTP " + response.code());
                        return;
                    }

                    try {
                        String bodyStr = response.body().string();
                        JSONObject json = new JSONObject(bodyStr);
                        JSONObject data = json.optJSONObject("data");
                        if (data == null) {
                            postSuccess(callback, new ArrayList<Book>());
                            return;
                        }
                        JSONObject search = data.optJSONObject("search");
                        if (search == null) {
                            postSuccess(callback, new ArrayList<Book>());
                            return;
                        }
                        JSONArray page = search.optJSONArray("page");
                        List<Book> books = new ArrayList<>();
                        if (page != null) {
                            for (int i = 0; i < page.length(); i++) {
                                JSONObject item = page.getJSONObject(i);
                                String type = item.optString("__typename", "");
                                JSONObject bObj = null;
                                if ("TextBook".equals(type) || "ComicBook".equals(type) || "TextSerial".equals(type)) {
                                    bObj = item.optJSONObject("book");
                                } else if ("Book".equals(type)) {
                                    bObj = item;
                                }
                                if (bObj != null) {
                                    String uuid = bObj.optString("uuid", "");
                                    if (uuid.isEmpty()) continue;
                                    String title = bObj.optString("name", bObj.optString("title", "Без названия"));

                                    String authorText = "";
                                    JSONArray authorsArr = bObj.optJSONArray("authors");
                                    if (authorsArr != null && authorsArr.length() > 0) {
                                        StringBuilder sb = new StringBuilder();
                                        for (int a = 0; a < authorsArr.length(); a++) {
                                            JSONObject aObj = authorsArr.optJSONObject(a);
                                            if (aObj != null) {
                                                String aName = aObj.optString("name", "");
                                                if (!aName.isEmpty()) {
                                                    if (sb.length() > 0) sb.append(", ");
                                                    sb.append(aName);
                                                }
                                            }
                                        }
                                        authorText = sb.toString();
                                    }
                                    if (authorText.isEmpty()) {
                                        authorText = parseAuthorString(bObj, null);
                                    }

                                    String coverUrl = "";
                                    JSONObject coverObj = bObj.optJSONObject("cover");
                                    if (coverObj != null) {
                                        coverUrl = coverObj.optString("url", "");
                                    }

                                    String rawAnn = bObj.optString("annotation", bObj.optString("editorAnnotation", ""));
                                    String cleanAnn = "";
                                    if (!rawAnn.isEmpty()) {
                                        cleanAnn = android.text.Html.fromHtml(rawAnn).toString().trim().replaceAll("\\s+", " ");
                                    }

                                    Book book = new Book();
                                    book.setUuid(uuid);
                                    book.setTitle(title);
                                    book.setAuthor(authorText);
                                    book.setAnnotation(cleanAnn);
                                    book.setCoverUrl(coverUrl);
                                    book.setPercent(0.0);
                                    book.setCurrentChapterIndex(0);
                                    book.setCurrentParagraphIndex(0);
                                    book.setShelfType("search");
                                    books.add(book);
                                }
                            }
                        }
                        postSuccess(callback, books);
                    } catch (Exception e) {
                        postError(callback, "Ошибка обработки результатов: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            postError(callback, "Ошибка запроса поиска: " + e.getMessage());
        }
    }

    /**
     * Добавление книги в личную библиотеку пользователя (на полку «В планах»).
     */
    public void addBookToLibrary(final String bookUuid, final ApiCallback<Boolean> callback) {
        String endpoint = BASE_URL + "/profile/library_cards";
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("book_uuid", bookUuid);
            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
            Request request = createAuthRequestBuilder(endpoint).post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, "Ошибка сети: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    if (response.isSuccessful() || response.code() == 409 || response.code() == 422) {
                        postSuccess(callback, true);
                    } else {
                        postError(callback, "Ошибка сервера: HTTP " + response.code());
                    }
                }
            });
        } catch (Exception e) {
            postError(callback, "Ошибка отправки: " + e.getMessage());
        }
    }

    /**
     * Перемещение книги на заданную полку («reading», «to_read», «finished»).
     */
    public void updateBookShelfState(final String bookUuid, final String shelfState, final ApiCallback<Boolean> callback) {
        String endpoint = BASE_URL + "/profile/library_cards/" + bookUuid;
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("book_uuid", bookUuid);
            bodyJson.put("state", shelfState);
            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
            Request request = createAuthRequestBuilder(endpoint).patch(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    updateBookShelfFallbackPost(bookUuid, shelfState, callback);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    if (response.isSuccessful()) {
                        postSuccess(callback, true);
                    } else {
                        updateBookShelfFallbackPost(bookUuid, shelfState, callback);
                    }
                }
            });
        } catch (Exception e) {
            updateBookShelfFallbackPost(bookUuid, shelfState, callback);
        }
    }

    private void updateBookShelfFallbackPost(final String bookUuid, final String shelfState, final ApiCallback<Boolean> callback) {
        String endpoint = BASE_URL + "/profile/library_cards";
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("book_uuid", bookUuid);
            bodyJson.put("state", shelfState);
            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
            Request request = createAuthRequestBuilder(endpoint).post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, "Ошибка сети: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    if (response.isSuccessful() || response.code() == 409 || response.code() == 422) {
                        postSuccess(callback, true);
                    } else {
                        postError(callback, "Ошибка сервера при смене полки: HTTP " + response.code());
                    }
                }
            });
        } catch (Exception e) {
            postError(callback, e.getMessage());
        }
    }

    /**
     * Удаление книги из личной библиотеки («Убрать с полки»).
     */
    public void removeBookFromLibrary(final String bookUuid, final ApiCallback<Boolean> callback) {
        String endpoint = BASE_URL + "/profile/library_cards/" + bookUuid;
        Request request = createAuthRequestBuilder(endpoint).delete().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                removeBookFallback(bookUuid, callback);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (response.isSuccessful() || response.code() == 404) {
                    postSuccess(callback, true);
                } else {
                    removeBookFallback(bookUuid, callback);
                }
            }
        });
    }

    private void removeBookFallback(final String bookUuid, final ApiCallback<Boolean> callback) {
        String endpoint = BASE_URL + "/profile/library_cards?book_uuid=" + bookUuid;
        Request request = createAuthRequestBuilder(endpoint).delete().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postError(callback, "Ошибка сети: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (response.isSuccessful() || response.code() == 404) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, "Ошибка сервера при удалении: HTTP " + response.code());
                }
            }
        });
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
